package com.example.engine.controller

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import com.example.domain.model.Timeline
import com.example.domain.model.TrackType
import com.example.domain.model.VideoClip
import com.example.engine.media.MediaRelinkManager
import com.example.engine.playback.ProxyMediaEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Unified PlaybackController that strictly manages ExoPlayer state via TimelineState
 * as the single source of truth, ensuring the playhead (CTI) and preview are perfectly
 * synchronized during playback, seeking, and pause cycles.
 */
class PlaybackController(
  context: Context,
  private val onTimelinePositionChanged: (Long) -> Unit = {},
  private val onPlaybackEnded: () -> Unit = {},
  private val onPlayerError: (PlaybackException) -> Unit = {},
  private val proxyMediaEngine: ProxyMediaEngine? = null,
  private val uriResolver: ((VideoClip) -> String)? = null
) {
  companion object {
    private const val TAG = "PlaybackController"
  }

  private val appContext = context.applicationContext
  private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
  private val commandGeneration = AtomicLong(0L)

  // --- Authoritative Single Source of Truth ---
  private val _timelineState = MutableStateFlow(TimelineState())
  val timelineState: StateFlow<TimelineState> = _timelineState.asStateFlow()

  // Backwards-compatible state flows
  private val _state = MutableStateFlow(EnginePlaybackState.IDLE)
  val state: StateFlow<EnginePlaybackState> = _state.asStateFlow()

  private val _timelinePositionMs = MutableStateFlow(0L)
  val timelinePositionMs: StateFlow<Long> = _timelinePositionMs.asStateFlow()

  private val _lastCommandAtMs = MutableStateFlow(0L)
  val lastCommandAtMs: StateFlow<Long> = _lastCommandAtMs.asStateFlow()

  private lateinit var _playbackManager: PlaybackManager
  val playbackManager: PlaybackManager get() = _playbackManager
  val player get() = playbackManager.player

  private var currentLoadedUri: String? = null
  private var currentLoadedClipId: String? = null
  private var disposed = false
  private var pendingCommand: Job? = null
  private var internalPlayerSeeking = false
  private var lastDriftCorrectionElapsedMs = 0L
  /** True while the Trimming tool owns the player with its own clipped MediaItem. */
  private var trimPreviewActive = false
  /** Last applied track hide/mute/solo state, so a mix change re-applies while playing. */
  private var lastMixSignature: String? = null

  init {
    _playbackManager = PlaybackManager(
      context = appContext,
      onPlaybackStateChanged = { state ->
        if (!disposed) {
          when (state) {
            Player.STATE_IDLE -> updatePlaybackEngineState(EnginePlaybackState.IDLE)
            Player.STATE_BUFFERING -> updatePlaybackEngineState(EnginePlaybackState.BUFFERING)
            Player.STATE_READY -> {
              val targetState = if (_timelineState.value.isPlaying) EnginePlaybackState.PLAYING else EnginePlaybackState.READY
              updatePlaybackEngineState(targetState)
            }
            Player.STATE_ENDED -> {
              handleExoPlayerEnded()
            }
          }
        }
      },
      onIsPlayingChanged = { playing ->
        if (!disposed && !internalPlayerSeeking) {
          val nextState = if (playing) EnginePlaybackState.PLAYING else {
            if (_timelineState.value.isSeeking) EnginePlaybackState.SEEKING else EnginePlaybackState.PAUSED
          }
          updatePlaybackEngineState(nextState)
        }
      },
      onPlayerError = { error ->
        if (!disposed) {
          updatePlaybackEngineState(EnginePlaybackState.ERROR)
          onPlayerError(error)
        }
      }
    )
  }

  val isPlaying: Boolean get() = !disposed && _timelineState.value.isPlaying
  val currentPosition: Long get() = if (disposed) _timelinePositionMs.value else _timelineState.value.positionMs
  val duration: Long get() = if (disposed) 0L else _timelineState.value.totalDurationMs
  val bufferedPosition: Long get() = if (disposed) 0L else playbackManager.bufferedPosition

  // =========================================================================
  // Unified TimelineState Operations
  // =========================================================================

  /** Updates the timeline configuration and synchronizes the active clip and player state. */
  fun updateTimeline(timeline: Timeline) {
    if (disposed) return
    val previous = _timelineState.value.activeClip
    val totalDur = timeline.totalDurationMs
    val boundedPos = _timelineState.value.positionMs.coerceIn(0L, maxOf(totalDur, 0L))
    val clip = findClipAt(timeline, boundedPos)
    val sourceWindowChanged = previous != null && clip != null && previous.id == clip.id &&
      (previous.sourceStartMs != clip.sourceStartMs ||
        previous.sourceEndMs != clip.sourceEndMs ||
        previous.durationMs != clip.durationMs ||
        previous.timelineStartMs != clip.timelineStartMs)

    _timelineState.value = _timelineState.value.copy(
      timeline = timeline,
      positionMs = boundedPos,
      positionUs = boundedPos * 1000L,
      totalDurationMs = totalDur,
      activeClip = clip
    )
    _timelinePositionMs.value = boundedPos

    // Track mute/solo/hide changes the audible mix without changing the active clip: apply
    // them to the player even while it is running, otherwise the timeline toolbar's mute
    // buttons only took effect after a manual pause/seek.
    val mixSignature = PreviewMixPolicy.mixSignature(timeline)
    val mixChanged = mixSignature != lastMixSignature
    lastMixSignature = mixSignature

    // While playing, do not re-seek the current clip. A timeline snapshot refresh
    // (filters, undo, autosave) must not snap the decoder back to a keyframe.
    // Split of the loaded head keeps the same id but shrinks the source window — rebind.
    val clipChanged = _timelineState.value.activeClip?.id != currentLoadedClipId || sourceWindowChanged
    val playing = _timelineState.value.isPlaying
    if (!playing || clipChanged) {
      syncExoPlayerToTimelineState(exact = true)
    } else if (mixChanged) {
      // Pure mix change mid-playback: re-apply the gain only. Any seek here would snap the
      // preview back to a sync frame while the clock keeps running.
      applyMainLaneGain()
    }
  }

  /** Re-applies the main lane gain to the loaded clip without touching the position. */
  private fun applyMainLaneGain() {
    val state = _timelineState.value
    val clip = state.activeClip ?: findClipAt(state.timeline, state.positionMs) ?: return
    if (!clip.isVideo || !isPlayableInPlayer(clip.uri)) return
    playbackManager.setVolume(mainVideoGain(clip))
  }

  fun setTimeline(timeline: Timeline) = updateTimeline(timeline)

  /** Authoritative seek to a project timeline position (in milliseconds). */
  fun seekToTimeline(positionMs: Long, resumeAfter: Boolean = false, exact: Boolean = true) {
    if (disposed) return
    val totalDur = _timelineState.value.totalDurationMs
    val maxBound = maxOf(totalDur + 10000L, 10000L)
    val bounded = positionMs.coerceIn(0L, maxBound)

    val clip = findClipAt(_timelineState.value.timeline, bounded)

    _timelineState.value = _timelineState.value.copy(
      positionMs = bounded,
      positionUs = bounded * 1000L,
      isPlaying = resumeAfter,
      isSeeking = true,
      activeClip = clip,
      playbackState = if (resumeAfter) EnginePlaybackState.PLAYING else EnginePlaybackState.PAUSED
    )
    _timelinePositionMs.value = bounded
    _state.value = if (resumeAfter) EnginePlaybackState.PLAYING else EnginePlaybackState.PAUSED

    syncExoPlayerToTimelineState(exact = exact)
    _timelineState.value = _timelineState.value.copy(isSeeking = false)

    onTimelinePositionChanged(bounded)
  }

  /** Start scrubbing (touch/drag gesture). Pauses playback while maintaining responsive frame seeks. */
  fun startScrubbing() {
    if (disposed) return
    _timelineState.value = _timelineState.value.copy(
      isScrubbing = true,
      isPlaying = false,
      playbackState = EnginePlaybackState.SEEKING
    )
    playbackManager.pause()
  }

  /** Scrub to position with fast closest-sync seek for maximum UI fluidity. */
  fun scrubToTimeline(positionMs: Long) {
    if (disposed) return
    if (!_timelineState.value.isScrubbing) {
      startScrubbing()
    }
    val totalDur = _timelineState.value.totalDurationMs
    val maxBound = maxOf(totalDur + 10000L, 10000L)
    val bounded = positionMs.coerceIn(0L, maxBound)
    val clip = findClipAt(_timelineState.value.timeline, bounded)

    _timelineState.value = _timelineState.value.copy(
      positionMs = bounded,
      positionUs = bounded * 1000L,
      activeClip = clip
    )
    _timelinePositionMs.value = bounded

    syncExoPlayerToTimelineState(exact = false)
    onTimelinePositionChanged(bounded)
  }

  /** Stop scrubbing and lock the exact presentation frame under the playhead (CTI). */
  fun stopScrubbingTimeline(finalPosMs: Long? = null, resumeAfter: Boolean = false) {
    if (disposed) return
    val targetPos = (finalPosMs ?: _timelineState.value.positionMs).coerceAtLeast(0L)
    _timelineState.value = _timelineState.value.copy(isScrubbing = false)
    seekToTimeline(targetPos, resumeAfter = resumeAfter, exact = true)
  }

  /** Starts timeline playback from current or requested position. */
  fun playTimeline(startPosMs: Long? = null) {
    if (disposed) return
    val totalDur = _timelineState.value.totalDurationMs
    val currentPos = startPosMs ?: _timelineState.value.positionMs
    val targetPos = if (totalDur > 0L && currentPos >= totalDur) 0L else currentPos.coerceAtLeast(0L)
    val clip = findClipAt(_timelineState.value.timeline, targetPos)

    _timelineState.value = _timelineState.value.copy(
      positionMs = targetPos,
      positionUs = targetPos * 1000L,
      isPlaying = true,
      isScrubbing = false,
      isSeeking = false,
      activeClip = clip,
      playbackState = EnginePlaybackState.PLAYING
    )
    _timelinePositionMs.value = targetPos
    _state.value = EnginePlaybackState.PLAYING

    // Exact seek: Play must show the frame under the CTI, not the previous keyframe.
    syncExoPlayerToTimelineState(exact = true)
  }

  /** Pauses timeline playback and guarantees exact frame lock between playhead and preview. */
  fun pauseTimeline(resyncFrame: Boolean = true) {
    if (disposed) return
    _timelineState.value = _timelineState.value.copy(
      isPlaying = false,
      playbackState = EnginePlaybackState.PAUSED
    )
    playbackManager.pause()
    _state.value = EnginePlaybackState.PAUSED

    // Exact seek on pause eliminates any audio-video stop latency or frame drift.
    // Callers that are about to seek anyway (user seek / scrub end) pass resyncFrame = false:
    // re-seeking to the old position first only wasted a decoder flush and flashed a stale frame.
    if (resyncFrame) syncExoPlayerToTimelineState(exact = true)
  }

  fun togglePlayPause() {
    if (isPlaying) pauseTimeline() else playTimeline()
  }

  /** Periodic clock tick during playback to advance the playhead and synchronize ExoPlayer. */
  fun updateTimelinePosition(positionMs: Long) {
    if (disposed) return
    // The trim preview plays its own clipped item: the master clock may keep ticking, but it
    // must never re-bind the shared player to a timeline clip while the tool is open.
    if (trimPreviewActive) {
      _timelinePositionMs.value = positionMs.coerceAtLeast(0L)
      onTimelinePositionChanged(_timelinePositionMs.value)
      return
    }
    val bounded = positionMs.coerceIn(0L, maxOf(_timelineState.value.totalDurationMs, 0L))
    val currentClip = _timelineState.value.activeClip
    val nextClip = findClipAt(_timelineState.value.timeline, bounded)

    _timelineState.value = _timelineState.value.copy(
      positionMs = bounded,
      positionUs = bounded * 1000L,
      activeClip = nextClip
    )
    _timelinePositionMs.value = bounded
    onTimelinePositionChanged(bounded)

    // Clip boundary detection
    if (currentClip?.id != nextClip?.id) {
      handleClipTransition(nextClip, bounded, resumeAfter = _timelineState.value.isPlaying)
    } else if (_timelineState.value.isPlaying && nextClip != null && nextClip.isVideo) {
      correctSourceDriftIfNeeded(nextClip, bounded)
    }
  }

  /**
   * One exact corrective seek when the decoder is actually lost, never a
   * closest-keyframe seek on every tick. Keyframe seeks land 1–2s early and
   * the next tick seeks again, so the preview jumps backward for the whole play.
   */
  private fun correctSourceDriftIfNeeded(clip: VideoClip, timelinePosMs: Long) {
    val ready = try {
      playbackManager.playbackState == Player.STATE_READY && playbackManager.player.isPlaying
    } catch (_: Throwable) {
      false
    }
    val expectedSourcePos = clip.timelineToSourceMs(timelinePosMs)
    val playerPos = try { playbackManager.currentPosition } catch (_: Throwable) { return }
    val sinceCorrection = SystemClock.elapsedRealtime() - lastDriftCorrectionElapsedMs
    if (PlaybackSyncPolicy.shouldCorrectSourceDrift(playerPos, expectedSourcePos, ready, sinceCorrection)) {
      lastDriftCorrectionElapsedMs = SystemClock.elapsedRealtime()
      playbackManager.seekToExact(expectedSourcePos)
    }
  }

  /** Handles seamless transition between clips or entering/exiting gaps. */
  fun handleClipTransition(nextClip: VideoClip?, timelinePosMs: Long, resumeAfter: Boolean = true) {
    if (disposed) return
    if (trimPreviewActive) return
    _timelineState.value = _timelineState.value.copy(
      activeClip = nextClip,
      positionMs = timelinePosMs,
      positionUs = timelinePosMs * 1000L
    )

    if (nextClip != null && nextClip.isVideo && isPlayableInPlayer(nextClip.uri)) {
      val sourcePos = nextClip.timelineToSourceMs(timelinePosMs)
      ensureClipLoaded(nextClip, sourcePos)
      playbackManager.setPlaybackSpeed(nextClip.speed)
      playbackManager.setVolume(mainVideoGain(nextClip))
      playbackManager.seekTo(sourcePos)
      if (resumeAfter && _timelineState.value.isPlaying) {
        playbackManager.play()
      }
    } else {
      // Non-video clip or gap: pause ExoPlayer so stale frames do not show,
      // but do NOT stop the master timeline clock!
      playbackManager.pause()
    }
  }

  // =========================================================================
  // Internal ExoPlayer Reconciliation
  // =========================================================================

  private fun syncExoPlayerToTimelineState(exact: Boolean) {
    if (disposed) return
    // The Trimming tool owns the player while it is open (it loaded its own clipped item).
    if (trimPreviewActive) return
    val state = _timelineState.value
    val clip = state.activeClip ?: findClipAt(state.timeline, state.positionMs)

    if (clip != null && clip.isVideo && isPlayableInPlayer(clip.uri)) {
      val sourcePos = clip.timelineToSourceMs(state.positionMs)
      ensureClipLoaded(clip, sourcePos)
      playbackManager.setPlaybackSpeed(clip.speed)
      playbackManager.setVolume(mainVideoGain(clip))

      internalPlayerSeeking = true
      try {
        if (exact) {
          playbackManager.seekToExact(sourcePos)
        } else {
          playbackManager.seekToFast(sourcePos)
        }
        lastDriftCorrectionElapsedMs = SystemClock.elapsedRealtime()
      } finally {
        internalPlayerSeeking = false
      }

      if (state.isPlaying && !state.isScrubbing) {
        playbackManager.play()
      } else {
        playbackManager.pause()
      }
    } else {
      // Gap or non-video: pause ExoPlayer
      playbackManager.pause()
    }
  }

  private fun ensureClipLoaded(clip: VideoClip, targetSeekMs: Long = clip.sourceStartMs) {
    val resolvedUri = resolveClipUri(clip)
    if (resolvedUri == currentLoadedUri && playbackManager.playbackState != Player.STATE_IDLE) {
      currentLoadedClipId = clip.id
      return
    }
    currentLoadedClipId = clip.id
    currentLoadedUri = resolvedUri
    val parsedUri = try {
      Uri.parse(resolvedUri)
    } catch (e: Exception) {
      Log.e(TAG, "Invalid clip URI: $resolvedUri", e)
      return
    }
    playbackManager.loadMedia(normalizeUri(parsedUri), targetSeekMs.coerceAtLeast(0L), autoPlay = false)
  }

  private fun resolveClipUri(clip: VideoClip): String {
    return proxyMediaEngine?.getProxyUri(clip)
      ?: uriResolver?.invoke(clip)
      ?: clip.uri
  }

  private fun isPlayableInPlayer(uriString: String?): Boolean {
    return MediaRelinkManager.isRealPlayableMedia(appContext, uriString)
  }

  /**
   * Preview gain of the main video lane: clip mute/volume combined with the track level
   * mute / solo state (the same gate the export mix uses).
   */
  private fun mainVideoGain(clip: VideoClip): Float {
    val state = _timelineState.value
    if (state.isMuted) return 0f
    if (!PreviewMixPolicy.isTrackAudible(state.timeline, TrackType.MAIN_VIDEO)) return 0f
    return PreviewMixPolicy.clipGain(clip, state.timeline, TrackType.MAIN_VIDEO) * state.volume
  }

  private fun findClipAt(timeline: Timeline, posMs: Long): VideoClip? {
    return timeline.videoClips.firstOrNull {
      posMs >= it.timelineStartMs && posMs < (it.timelineStartMs + it.durationMs)
    }
  }

  private fun handleExoPlayerEnded() {
    val currentPos = _timelineState.value.positionMs
    val totalDur = _timelineState.value.totalDurationMs
    val timeline = _timelineState.value.timeline

    // Look for the next clip after current position
    val nextClip = findClipAt(timeline, currentPos + 1L)
      ?: timeline.videoClips
        .asSequence()
        .filter { it.timelineStartMs > currentPos }
        .minByOrNull { it.timelineStartMs }

    if (nextClip != null) {
      handleClipTransition(nextClip, nextClip.timelineStartMs, resumeAfter = _timelineState.value.isPlaying)
    } else if (totalDur > 0L && currentPos >= totalDur) {
      // Reached the true end of the project timeline
      _timelineState.value = _timelineState.value.copy(
        isPlaying = false,
        playbackState = EnginePlaybackState.COMPLETED
      )
      updatePlaybackEngineState(EnginePlaybackState.COMPLETED)
      onPlaybackEnded()
    }
  }

  private fun updatePlaybackEngineState(engineState: EnginePlaybackState) {
    _state.value = engineState
    _timelineState.value = _timelineState.value.copy(playbackState = engineState)
  }

  // =========================================================================
  // Legacy / Direct Playback Controller API (Full Backwards Compatibility)
  // =========================================================================

  fun play() = playTimeline()
  fun pause() = pauseTimeline()

  fun loadMedia(uri: Uri, startPosMs: Long = 0L, autoPlay: Boolean = false) {
    if (disposed) return
    val normalized = normalizeUri(uri)
    val key = normalized.toString()
    if (key == currentLoadedUri && playbackManager.playbackState != Player.STATE_IDLE) {
      playbackManager.seekToExact(startPosMs)
      if (autoPlay) playbackManager.play()
      return
    }
    currentLoadedUri = key
    _state.value = EnginePlaybackState.PREPARING
    playbackManager.loadMedia(normalized, startPosMs, autoPlay)
  }

  /** True while the Trimming tool has its clipped preview item loaded in the player. */
  val isTrimPreviewActive: Boolean get() = trimPreviewActive

  /**
   * Player level transport for media that is not on the timeline (trim preview).
   * Using [playTimeline]/[pauseTimeline] here was wrong: those re-seek the player to the
   * timeline CTI, which for a clipped trim item lands outside its window (the decoder clamps
   * to the window edge), so pressing play/pause in the trim dialog jumped out of the trim range.
   */
  fun playMediaPreview() {
    if (!disposed) playbackManager.play()
  }

  fun pauseMediaPreview() {
    if (!disposed) playbackManager.pause()
  }

  /** Leaves trim preview mode and drops the clipped item so the full clip is loaded again. */
  fun endTrimPreview() {
    trimPreviewActive = false
    if (disposed) return
    try {
      playbackManager.player.repeatMode = Player.REPEAT_MODE_OFF
      playbackManager.player.playbackParameters = PlaybackParameters(1f)
    } catch (_: Throwable) {
    }
    playbackManager.clearMediaItems()
    currentLoadedUri = null
    currentLoadedClipId = null
    _state.value = EnginePlaybackState.IDLE
  }

  fun loadTrimPreview(uri: Uri, startMs: Long, endMs: Long, speed: Float, volume: Float, loop: Boolean) = enqueue("trimLoad") {
    if (disposed) return@enqueue
    // The clipped item replaces whatever the timeline had loaded, so freeze the timeline
    // transport (the master clock may still be running) and mark the player as trim owned.
    trimPreviewActive = true
    _timelineState.value = _timelineState.value.copy(
      isPlaying = false,
      isScrubbing = false,
      isSeeking = false,
      playbackState = EnginePlaybackState.PAUSED
    )
    _state.value = EnginePlaybackState.PAUSED
    val item = MediaItem.Builder()
      .setUri(normalizeUri(uri))
      .setClippingConfiguration(
        MediaItem.ClippingConfiguration.Builder()
          .setStartPositionMs(startMs.coerceAtLeast(0L))
          .setEndPositionMs(endMs.coerceAtLeast(startMs + 50L))
          .setStartsAtKeyFrame(false)
          .build()
      ).build()
    playbackManager.player.stop()
    playbackManager.player.clearMediaItems()
    playbackManager.player.setMediaItem(item)
    playbackManager.player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    playbackManager.player.playbackParameters = PlaybackParameters(speed.coerceIn(0.1f, 10f))
    playbackManager.player.volume = volume.coerceIn(0f, 2f)
    playbackManager.player.prepare()
    playbackManager.player.play()
    currentLoadedUri = normalizeUri(uri).toString()
  }

  fun seekTo(positionMs: Long, resumeAfter: Boolean = false, exact: Boolean = true, generation: Long = 0L) {
    if (disposed) return
    _state.value = EnginePlaybackState.SEEKING
    if (exact) {
      playbackManager.seekToExact(positionMs)
    } else {
      playbackManager.seekToFast(positionMs)
    }
    if (resumeAfter) {
      playbackManager.play()
      _state.value = EnginePlaybackState.PLAYING
    } else {
      playbackManager.pause()
      _state.value = EnginePlaybackState.PAUSED
    }
  }

  fun invalidatePendingSeeks(): Long = commandGeneration.incrementAndGet()
  fun setPlaybackSpeed(speed: Float) = enqueue("speed") {
    if (!disposed) {
      _timelineState.value = _timelineState.value.copy(playbackSpeed = speed)
      playbackManager.setPlaybackSpeed(speed)
    }
  }
  fun setVolume(volume: Float) = enqueue("volume") {
    if (!disposed) {
      _timelineState.value = _timelineState.value.copy(volume = volume)
      playbackManager.setVolume(volume)
    }
  }
  fun setMuted(muted: Boolean) = enqueue("mute") {
    if (!disposed) {
      _timelineState.value = _timelineState.value.copy(isMuted = muted)
      playbackManager.setMuted(muted)
    }
  }
  fun setSurface(surface: Surface?) = enqueue("surface") { if (!disposed) playbackManager.setSurface(surface) }
  fun clearSurface() = enqueue("clearSurface") { if (!disposed) playbackManager.clearSurface() }
  fun setRepeatMode(mode: Int) = enqueue("repeat") { if (!disposed) playbackManager.player.repeatMode = mode }

  fun sampleClockPositionMs(): Long = if (disposed) _timelinePositionMs.value else playbackManager.currentPosition

  fun release() {
    if (disposed) return
    disposed = true
    pendingCommand?.cancel()
    scope.cancel()
    playbackManager.release()
    _state.value = EnginePlaybackState.RELEASED
    _timelineState.value = _timelineState.value.copy(playbackState = EnginePlaybackState.RELEASED, isPlaying = false)
  }

  private fun enqueue(name: String, block: () -> Unit) {
    if (disposed) return
    pendingCommand = scope.launch {
      _lastCommandAtMs.value = SystemClock.elapsedRealtime()
      try { block() } catch (t: Throwable) {
        if (!disposed) {
          _state.value = EnginePlaybackState.ERROR
          Log.e(TAG, "Command $name failed", t)
        }
      }
    }
  }

  private fun normalizeUri(uri: Uri): Uri = when {
    uri.scheme == "asset" -> {
      var path = uri.path ?: ""
      if (path.startsWith("/")) path = path.substring(1)
      if (path.isEmpty()) path = uri.authority ?: ""
      Uri.parse("asset:///$path")
    }
    uri.scheme == null || uri.scheme == "file" -> {
      val path = uri.path ?: uri.toString()
      val file = java.io.File(path)
      if (file.exists()) Uri.fromFile(file) else uri
    }
    else -> uri
  }
}
