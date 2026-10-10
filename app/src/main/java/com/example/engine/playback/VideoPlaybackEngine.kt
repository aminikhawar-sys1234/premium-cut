package com.example.engine.playback

import android.content.Context
import android.graphics.ColorMatrix
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.SeekParameters
import com.example.domain.model.Timeline
import com.example.domain.model.TrackType
import com.example.domain.model.VideoClip
import com.example.domain.model.timelineToSourceMs
import com.example.engine.controller.CustomVideoEngineController
import com.example.engine.controller.PlaybackSyncPolicy
import com.example.engine.controller.PreviewMixPolicy
import com.example.engine.color.ColorEngineHost
import com.example.engine.effects.media3.LutStripBaker
import com.example.engine.effects.media3.Media3EffectPipeline
import com.example.engine.effects.media3.PreviewFilterEffects
import com.example.engine.media.MediaRelinkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Public playback facade retained for the existing editor UI.
 * All primary playback/seek/surface commands are delegated to one CustomVideoEngineController -> PlaybackController.
 * No independent elapsed-realtime playback clock is used here.
 */
@OptIn(UnstableApi::class)
class VideoPlaybackEngine(
  private val context: Context,
  onTimelinePositionChanged: (Long) -> Unit,
  onPlaybackEnded: () -> Unit,
  private val proxyEngine: ProxyMediaEngine? = null,
  /**
   * Called when the system paused playback (audio focus lost to another app, headphones
   * unplugged). The editor playhead is clock driven, so the timeline transport has to stop
   * with the player; otherwise the CTI keeps counting while the picture is frozen.
   */
  private val onPlaybackInterrupted: () -> Unit = {}
) {
  companion object {
    private const val TAG = "VideoPlaybackEngine"
    private const val UI_TICK_MS = 16L
    private const val PAUSED_SEEK_EPSILON_MS = 30L
  }

  private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
  private var trimPollJob: Job? = null
  private val seekGeneration = AtomicLong(0L)

  private val _isPlaying = MutableStateFlow(false)
  val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
  private val _currentPositionMs = MutableStateFlow(0L)
  val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()
  private val _activeClip = MutableStateFlow<VideoClip?>(null)
  val activeClip: StateFlow<VideoClip?> = _activeClip.asStateFlow()
  private val _playerError = MutableStateFlow<String?>(null)
  val playerError: StateFlow<String?> = _playerError.asStateFlow()
  private val _trimPlaybackPositionMs = MutableStateFlow(0L)
  val trimPlaybackPositionMs: StateFlow<Long> = _trimPlaybackPositionMs.asStateFlow()
  /**
   * Ids of the overlay clips whose ExoPlayer currently exists.
   * The preview surface is a plain texture view inside an AndroidView, so the UI cannot
   * observe `getOverlayPlayer()` (a map lookup). Publishing the id set makes the video
   * layer appear as soon as its player is created instead of after an unrelated recomposition.
   */
  private val _overlayPlayerIds = MutableStateFlow<Set<String>>(emptySet())
  val overlayPlayerIds: StateFlow<Set<String>> = _overlayPlayerIds.asStateFlow()

  private var currentTimeline = Timeline()
  private var currentPosMs = 0L
  private var isScrubbingMode = false
  private var wasPlayingBeforeScrub = false
  private var isTrimPreviewMode = false
  private var trimPreviewClip: VideoClip? = null
  private var trimRangeStartMs = 0L
  private var trimRangeEndMs = 0L

  val engineController: CustomVideoEngineController = CustomVideoEngineController(
    context = context,
    onTimelinePositionChanged = { pos ->
      currentPosMs = pos
      _currentPositionMs.value = pos
      val nextClip = findClipAt(pos)
      val lookChanged = PreviewFilterEffects.signature(nextClip, currentTimeline) != lastPreviewFilterSignature
      _activeClip.value = nextClip
      if (lookChanged) applyActiveLookToPlayers()
      syncOverlayPlayers(pos)
      syncAudioTrackPlayers(pos)
      onTimelinePositionChanged(pos)
    },
    onPlaybackEnded = {
      _isPlaying.value = false
      onPlaybackEnded()
    },
    proxyEngine = proxyEngine
  )

  val player: ExoPlayer get() = engineController.player
  val isScrubbing: Boolean get() = isScrubbingMode
  val isTrimPreview: Boolean get() = isTrimPreviewMode

  private val overlayPlayers = ConcurrentHashMap<String, ExoPlayer>()
  private val overlayLoadedUris = ConcurrentHashMap<String, String>()
  private val audioTrackPlayers = ConcurrentHashMap<String, ExoPlayer>()
  private val audioLoadedUris = ConcurrentHashMap<String, String>()
  private val secondaryCorrectionAtMs = ConcurrentHashMap<String, Long>()
  private var lastPreviewFilterSignature: String? = null
  private val lastOverlayFilterSignatures = ConcurrentHashMap<String, String>()

  init {
    player.addListener(object : Player.Listener {
      override fun onIsPlayingChanged(isPlaying: Boolean) {
        _isPlaying.value = isPlaying
        if (isTrimPreviewMode && isPlaying) startTrimPolling() else trimPollJob?.cancel()
      }
      override fun onPlaybackStateChanged(state: Int) {
        // A clip that loads fine again must clear the previous error, otherwise the stale
        // message stayed on the preview for the rest of the session.
        if (state == Player.STATE_READY) _playerError.value = null
      }
      override fun onPlayerError(error: PlaybackException) {
        _playerError.value = "${error.errorCodeName}: ${error.message}"
        Log.e(TAG, "Preview player error", error)
      }

      override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (playWhenReady) return
        val systemPausedUs = reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ||
          reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY
        if (!systemPausedUs) return
        Log.d(TAG, "Playback interrupted by the system (reason=$reason)")
        engineController.pause()
        overlayPlayers.values.forEach { try { it.pause() } catch (_: Exception) {} }
        audioTrackPlayers.values.forEach { try { it.pause() } catch (_: Exception) {} }
        _isPlaying.value = false
        onPlaybackInterrupted()
      }
    })
  }

  fun isPlayableInPlayer(uriString: String?): Boolean = MediaRelinkManager.isRealPlayableMedia(context, uriString)

  /**
   * Applies the selected Filters-tools look to the live ExoPlayer preview.
   *
   * The look is rebuilt from the active clip's filter + adjustments (the same ColorGrading
   * shader the export uses), so the incoming export ColorMatrix is intentionally unused.
   * It used to be silently dropped, which made the call look like it did nothing.
   */
  fun applyVideoFilter(@Suppress("UNUSED_PARAMETER") colorMatrix: ColorMatrix?) {
    applyActiveLookToPlayers()
  }

  /**
   * Pushes clip-local (or timeline) filter + adjustments + LUT onto ExoPlayer via Media3 effects.
   * [force] rebinds even when the look signature is unchanged (needed after a failed setVideoEffects
   * or when the paused frame must be redrawn). [preferClipId] uses that main-track clip's look when
   * the playhead is still on a different clip (filter tap before seek).
   */
  fun applyActiveLookToPlayers(force: Boolean = false, preferClipId: String? = null) {
    val preferred = preferClipId?.let { id -> currentTimeline.videoClips.find { it.id == id } }
    val clip = preferred ?: _activeClip.value
    val sig = PreviewFilterEffects.signature(clip, currentTimeline)
    if (force || sig != lastPreviewFilterSignature) {
      val lut = PreviewFilterEffects.lutLook(clip)
      val lutBmp = if (clip != null && ColorEngineHost.isBypassed(clip.id)) {
        null
      } else {
        lut?.first?.let { LutStripBaker.bitmapFor(context, it) }
      }
      val effects = PreviewFilterEffects.effectsFor(clip, currentTimeline, lutBmp, lut?.second ?: 1f)
      if (Media3EffectPipeline.applyRealtimeEffects(player, effects)) {
        lastPreviewFilterSignature = sig
        Log.d(
          TAG,
          "Applied preview filter ${PreviewFilterEffects.effectiveFilter(clip, currentTimeline).type}" +
            " lut=${lut?.first ?: "none"} effects=${effects.size} force=$force"
        )
      }
    }
    applyOverlayLooks(force)
  }

  private fun applyOverlayLooks(force: Boolean = false) {
    val activeIds = currentTimeline.overlayClips.map { it.id }.toSet()
    lastOverlayFilterSignatures.keys.toList().filter { it !in activeIds }.forEach { lastOverlayFilterSignatures.remove(it) }
    for (overlay in currentTimeline.overlayClips) {
      val p = overlayPlayers[overlay.id] ?: continue
      val sig = PreviewFilterEffects.signature(overlay, currentTimeline)
      if (!force && lastOverlayFilterSignatures[overlay.id] == sig) continue
      val lut = PreviewFilterEffects.lutLook(overlay)
      val lutBmp = lut?.first?.let { LutStripBaker.bitmapFor(context, it) }
      if (Media3EffectPipeline.applyRealtimeEffects(
          p,
          PreviewFilterEffects.effectsFor(overlay, currentTimeline, lutBmp, lut?.second ?: 1f)
        )
      ) {
        lastOverlayFilterSignatures[overlay.id] = sig
      }
    }
  }

  fun getOverlayPlayer(clipId: String): ExoPlayer? = try { overlayPlayers[clipId] } catch (_: Exception) { null }

  /**
   * Decides whether a secondary (overlay/audio) player must be re-seeked while the timeline is playing.
   * A player that is still BUFFERING/IDLE reports a stale currentPosition (often 0), so comparing it with
   * the source position would issue a new seek on every sync tick (seek storm -> repeated audio "tuk tuk").
   * The initial seek is already issued when playWhenReady is switched on.
   */
  private fun needsDriftReseek(playerId: String, p: ExoPlayer, sourceMs: Long): Boolean {
    val state = try { p.playbackState } catch (_: Exception) { return false }
    if (state == Player.STATE_BUFFERING || state == Player.STATE_IDLE) return false
    val playing = try { p.isPlaying } catch (_: Exception) { return false }
    val playerPos = try { p.currentPosition } catch (_: Exception) { return false }
    // No recorded correction yet: treat it as "long ago" so the cooldown check passes.
    // (The old Long.MIN_VALUE sentinel overflowed into a negative "since", defeating the check.)
    val lastCorrection = secondaryCorrectionAtMs[playerId]
    val since = if (lastCorrection == null) Long.MAX_VALUE else SystemClock.elapsedRealtime() - lastCorrection
    return PlaybackSyncPolicy.shouldCorrectSourceDrift(playerPos, sourceMs, playing, since)
  }

  /**
   * True when a secondary player that already reached its source end has to be restarted.
   * ExoPlayer keeps `playWhenReady = true` and `isPlaying = false` in STATE_ENDED, so the
   * old code neither seeked nor resumed it: audio / PIP stayed silent for the rest of the
   * session once a source had played through (second pass, or a seek back into the clip).
   */
  private fun needsRestartFromEnd(p: ExoPlayer): Boolean =
    try { p.playbackState == Player.STATE_ENDED } catch (_: Exception) { false }

  /** Exact seek so overlay and audio players stay on the CTI instead of the previous keyframe. */
  private fun seekSecondaryExact(playerId: String, p: ExoPlayer, sourceMs: Long) {
    try {
      p.setSeekParameters(SeekParameters.EXACT)
      p.seekTo(sourceMs.coerceAtLeast(0L))
      secondaryCorrectionAtMs[playerId] = SystemClock.elapsedRealtime()
    } catch (e: Exception) {
      Log.w(TAG, "Secondary seek failed", e)
    }
  }

  /** Paused scrub position: skip the seek when the player is already on (nearly) the requested frame. */
  private fun needsPausedSeek(p: ExoPlayer, sourceMs: Long): Boolean {
    val playerPos = try { p.currentPosition } catch (_: Exception) { return true }
    return kotlin.math.abs(playerPos - sourceMs) > PAUSED_SEEK_EPSILON_MS
  }

  fun syncOverlayPlayers(posMs: Long) {
    if (isTrimPreviewMode) return
    if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return
    try {
      val activeOverlays = currentTimeline.overlayClips.filter { it.isVideo && !it.isHidden && isPlayableInPlayer(it.uri) }
      val activeIds = activeOverlays.map { it.id }.toSet()
      overlayPlayers.keys.toList().filter { it !in activeIds }.forEach { id ->
        overlayPlayers.remove(id)?.let { p -> try { p.stop(); p.clearVideoSurface(); p.release() } catch (_: Exception) {} }
        overlayLoadedUris.remove(id)
      }
      for (overlay in activeOverlays) {
        val effectiveUri = proxyEngine?.getProxyUri(overlay) ?: overlay.uri
        var p = overlayPlayers[overlay.id]
        if (p == null) {
          p = try {
            ExoPlayer.Builder(context.applicationContext, DefaultRenderersFactory(context.applicationContext).setEnableDecoderFallback(true))
              .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(500, 5000, 250, 500).build())
              .setSeekParameters(SeekParameters.EXACT).build().apply { repeatMode = Player.REPEAT_MODE_OFF }
          } catch (e: Exception) {
            Log.w(TAG, "Overlay player creation failed", e); continue
          }
          overlayPlayers[overlay.id] = p
        }
        if (overlayLoadedUris[overlay.id] != effectiveUri || p.mediaItemCount == 0 || p.playbackState == Player.STATE_IDLE) {
          try {
            p.setMediaItem(MediaItem.fromUri(Uri.parse(effectiveUri)))
            p.prepare()
            overlayLoadedUris[overlay.id] = effectiveUri
          } catch (e: Exception) {
            Log.w(TAG, "Overlay prepare failed", e)
          }
        }
        try {
          p.playbackParameters = androidx.media3.common.PlaybackParameters(overlay.speed.coerceAtLeast(0.01f))
          // Track level mute / solo gate, same mix rule as the export.
          p.volume = PreviewMixPolicy.clipGain(overlay, currentTimeline, TrackType.OVERLAY)
          val active = posMs >= overlay.timelineStartMs && posMs < overlay.timelineStartMs + overlay.durationMs
          val source = overlay.timelineToSourceMs(posMs)
          if (active && engineController.timelineSyncManager.isPlaying) {
            val isPlayWhenReady = try { p.playWhenReady } catch (_: Exception) { false }
            if (!isPlayWhenReady || needsRestartFromEnd(p)) {
              seekSecondaryExact(overlay.id, p, source)
              p.playWhenReady = true
            } else if (needsDriftReseek(overlay.id, p, source)) {
              seekSecondaryExact(overlay.id, p, source)
            }
          } else {
            val isPlayWhenReady = try { p.playWhenReady } catch (_: Exception) { false }
            if (isPlayWhenReady) {
              p.playWhenReady = false
            }
            if (!engineController.timelineSyncManager.isPlaying) {
              val pausedTarget = source.coerceAtLeast(0L)
              if (needsPausedSeek(p, pausedTarget)) seekSecondaryExact(overlay.id, p, pausedTarget)
            }
          }
        } catch (e: Exception) {
          Log.w(TAG, "Overlay sync error", e)
        }
      }
      applyOverlayLooks()
      _overlayPlayerIds.value = overlayPlayers.keys.toSet()
    } catch (e: Exception) {
      Log.w(TAG, "syncOverlayPlayers error", e)
    }
  }

  fun syncAudioTrackPlayers(posMs: Long) {
    if (isTrimPreviewMode) return
    if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return
    try {
      val hasSolo = currentTimeline.audioClips.any { it.isSolo }
      // Same gate as AudioExportProcessor.collectAudioTracks(): mute/solo only. Hiding a track
      // switches the picture off, it must not silence the mix (a hidden audio track is still
      // exported, so muting it in the preview would be a preview/export divergence).
      val isAudioTrackMuted = !PreviewMixPolicy.isTrackAudible(currentTimeline, TrackType.AUDIO)
      val activeAudios = currentTimeline.audioClips.filter {
        !it.isHidden && !it.isMuted && !isAudioTrackMuted &&
        (!hasSolo || it.isSolo) &&
        isPlayableInPlayer(it.uri)
      }

      val activeIds = activeAudios.map { it.id }.toSet()
      audioTrackPlayers.keys.toList().filter { it !in activeIds }.forEach { id ->
        audioTrackPlayers.remove(id)?.let { p -> try { p.stop(); p.release() } catch (_: Exception) {} }
        audioLoadedUris.remove(id)
      }

      for (audio in activeAudios) {
        val effectiveUri = audio.uri
        var p = audioTrackPlayers[audio.id]
        if (p == null) {
          p = try {
            ExoPlayer.Builder(context.applicationContext, DefaultRenderersFactory(context.applicationContext).setEnableDecoderFallback(true))
              .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(1000, 6000, 400, 800).build())
              .setSeekParameters(SeekParameters.EXACT).build().apply { repeatMode = Player.REPEAT_MODE_OFF }
          } catch (e: Exception) {
            Log.w(TAG, "Audio player creation failed", e); continue
          }
          audioTrackPlayers[audio.id] = p
        }
        if (audioLoadedUris[audio.id] != effectiveUri || p.mediaItemCount == 0 || p.playbackState == Player.STATE_IDLE) {
          try {
            p.setMediaItem(MediaItem.fromUri(Uri.parse(effectiveUri)))
            p.prepare()
            audioLoadedUris[audio.id] = effectiveUri
          } catch (e: Exception) {
            Log.w(TAG, "Audio player prepare failed", e)
          }
        }
        try {
          p.playbackParameters = androidx.media3.common.PlaybackParameters(audio.speed.coerceAtLeast(0.01f))
          val rel = (posMs - audio.timelineStartMs).coerceAtLeast(0L)
          var calculatedVol = audio.volume.coerceIn(0f, 2f)
          if (audio.fadeInMs > 0L && rel < audio.fadeInMs) {
            calculatedVol *= (rel.toFloat() / audio.fadeInMs.toFloat()).coerceIn(0f, 1f)
          }
          val timeFromEnd = (audio.durationMs - rel).coerceAtLeast(0L)
          if (audio.fadeOutMs > 0L && timeFromEnd < audio.fadeOutMs) {
            calculatedVol *= (timeFromEnd.toFloat() / audio.fadeOutMs.toFloat()).coerceIn(0f, 1f)
          }
          p.volume = if (audio.isMuted) 0f else calculatedVol
          val active = posMs >= audio.timelineStartMs && posMs < audio.timelineStartMs + audio.durationMs
          val source = audio.timelineToSourceMs(posMs)
          if (active && engineController.timelineSyncManager.isPlaying) {
            val isPlayWhenReady = try { p.playWhenReady } catch (_: Exception) { false }
            // A source that already played to its end must be seeked back, otherwise the
            // second playback pass of the project (or a jump back into the clip) stayed silent.
            if (!isPlayWhenReady || needsRestartFromEnd(p)) {
              seekSecondaryExact(audio.id, p, source)
              p.playWhenReady = true
            } else if (needsDriftReseek(audio.id, p, source)) {
              seekSecondaryExact(audio.id, p, source)
            }
          } else {
            val isPlayWhenReady = try { p.playWhenReady } catch (_: Exception) { false }
            if (isPlayWhenReady) {
              p.playWhenReady = false
            }
            if (!engineController.timelineSyncManager.isPlaying) {
              val pausedTarget = source.coerceAtLeast(0L)
              if (needsPausedSeek(p, pausedTarget)) seekSecondaryExact(audio.id, p, pausedTarget)
            }
          }
        } catch (e: Exception) {
          Log.w(TAG, "Audio track sync error", e)
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "syncAudioTrackPlayers error", e)
    }
  }

  fun updateTimeline(timeline: Timeline) {
    currentTimeline = timeline
    currentPosMs = currentPosMs.coerceIn(0L, timeline.totalDurationMs.coerceAtLeast(0L))
    _currentPositionMs.value = currentPosMs
    _activeClip.value = findClipAt(currentPosMs)
    // Bind the look before the controller seeks so a paused preview shows the new filter.
    applyActiveLookToPlayers()
    engineController.updateTimeline(timeline)
    syncOverlayPlayers(currentPosMs)
    syncAudioTrackPlayers(currentPosMs)
  }

  fun startScrubbing() {
    isScrubbingMode = true
    wasPlayingBeforeScrub = _isPlaying.value
    engineController.startScrubbing()
  }

  fun scrubTo(timelinePosMs: Long) {
    if (!isScrubbingMode) startScrubbing()
    val bounded = timelinePosMs.coerceIn(0L, currentTimeline.totalDurationMs.coerceAtLeast(0L))
    currentPosMs = bounded
    _currentPositionMs.value = bounded
    _activeClip.value = findClipAt(bounded)
    engineController.scrubTo(bounded)
    syncOverlayPlayers(bounded)
    syncAudioTrackPlayers(bounded)
  }

  fun stopScrubbing(finalPosMs: Long? = null) {
    val target = finalPosMs ?: currentPosMs
    isScrubbingMode = false
    engineController.stopScrubbing(target)
    _currentPositionMs.value = target
    syncOverlayPlayers(target)
    syncAudioTrackPlayers(target)
    if (wasPlayingBeforeScrub) wasPlayingBeforeScrub = false
  }

  fun seekTo(timelinePosMs: Long) {
    isScrubbingMode = false
    val bounded = timelinePosMs.coerceIn(0L, currentTimeline.totalDurationMs.coerceAtLeast(0L))
    currentPosMs = bounded
    _currentPositionMs.value = bounded
    _activeClip.value = findClipAt(bounded)
    engineController.seekTo(bounded)
    syncOverlayPlayers(bounded)
    syncAudioTrackPlayers(bounded)
  }

  fun play(startPosMs: Long? = null) {
    if (isTrimPreviewMode) { playTrimPreview(); return }
    _playerError.value = null
    engineController.play(startPosMs)
  }

  fun pause() {
    if (isTrimPreviewMode) { pauseTrimPreview(); return }
    engineController.pause()
    overlayPlayers.values.forEach { try { it.playWhenReady = false; it.pause() } catch (_: Exception) {} }
    audioTrackPlayers.values.forEach { try { it.playWhenReady = false; it.pause() } catch (_: Exception) {} }
    _isPlaying.value = false
  }

  fun togglePlayPause() {
    if (isTrimPreviewMode) { toggleTrimPlayPause(); return }
    engineController.togglePlayPause()
  }

  fun stepFrame(forward: Boolean, fps: Int? = null) {
    pause()
    val effectiveFps = fps ?: (_activeClip.value?.frameRate?.toInt() ?: 30).coerceIn(12, 120)
    val frameMs = (1000L / effectiveFps).coerceAtLeast(1L)
    seekTo(if (forward) currentPosMs + frameMs else currentPosMs - frameMs)
  }

  fun previewTrimRange(clip: VideoClip, startMs: Long, endMs: Long, loop: Boolean = true) {
    isTrimPreviewMode = true
    trimPreviewClip = clip
    trimRangeStartMs = startMs.coerceAtLeast(0L)
    trimRangeEndMs = endMs.coerceAtLeast(trimRangeStartMs + 50L)
    _trimPlaybackPositionMs.value = trimRangeStartMs
    if (!isPlayableInPlayer(clip.uri)) return
    engineController.playbackController.loadTrimPreview(
      Uri.parse(clip.uri), trimRangeStartMs, trimRangeEndMs,
      clip.speed, if (clip.isMuted) 0f else clip.volume, loop
    )
  }

  fun seekTrimPreview(offsetFromStartMs: Long) {
    if (!isTrimPreviewMode) return
    val offset = offsetFromStartMs.coerceIn(0L, trimRangeEndMs - trimRangeStartMs)
    engineController.playbackController.seekTo(offset, resumeAfter = false, exact = true)
    _trimPlaybackPositionMs.value = trimRangeStartMs + offset
  }

  fun seekTrimPreviewToSourceMs(sourceTimeMs: Long) {
    if (!isTrimPreviewMode) return
    val target = sourceTimeMs.coerceIn(trimRangeStartMs, trimRangeEndMs)
    engineController.playbackController.seekTo(target - trimRangeStartMs, resumeAfter = false, exact = true)
    _trimPlaybackPositionMs.value = target
  }

  fun stepTrimFrame(forward: Boolean, fps: Int? = null) {
    if (!isTrimPreviewMode) return
    pauseTrimPreview()
    val effectiveFps = fps ?: (trimPreviewClip?.frameRate?.toInt() ?: 30).coerceIn(12, 120)
    val delta = (1000L / effectiveFps).coerceAtLeast(1L)
    seekTrimPreviewToSourceMs(if (forward) _trimPlaybackPositionMs.value + delta else _trimPlaybackPositionMs.value - delta)
  }

  // Trim preview transport is player level on purpose: the timeline transport (play/pause)
  // re-seeks to the timeline CTI, which is outside the clipped trim window. See
  // PlaybackController.playMediaPreview().
  fun pauseTrimPreview() { if (isTrimPreviewMode) engineController.playbackController.pauseMediaPreview() }
  fun playTrimPreview() { if (isTrimPreviewMode) engineController.playbackController.playMediaPreview() }

  fun toggleTrimPlayPause() {
    if (!isTrimPreviewMode) return
    val controller = engineController.playbackController
    val player = engineController.player
    val playing = try { player.isPlaying } catch (_: Exception) { false }
    if (playing) controller.pauseMediaPreview() else controller.playMediaPreview()
  }

  fun exitTrimPreview() {
    if (!isTrimPreviewMode) return
    isTrimPreviewMode = false
    trimPreviewClip = null
    trimPollJob?.cancel()
    // The trim tool loaded a clipped MediaItem into the shared preview player. Dropping it is
    // mandatory: the old exit kept the clipped item loaded (the uri cache still matched), so
    // the preview stayed trapped inside the trim window until another clip was selected.
    engineController.playbackController.endTrimPreview()
    engineController.seekTo(currentPosMs)
  }

  private fun startTrimPolling() {
    trimPollJob?.cancel()
    trimPollJob = scope.launch {
      while (isActive && isTrimPreviewMode) {
        val p = player.currentPosition
        val source = (trimRangeStartMs + p).coerceIn(trimRangeStartMs, trimRangeEndMs)
        _trimPlaybackPositionMs.value = source
        delay(UI_TICK_MS)
      }
    }
  }

  private fun findClipAt(posMs: Long): VideoClip? = currentTimeline.videoClips.firstOrNull {
    posMs >= it.timelineStartMs && posMs < it.timelineStartMs + it.durationMs
  }

  fun release() {
    trimPollJob?.cancel()
    scope.cancel()
    overlayPlayers.values.forEach { try { it.stop(); it.release() } catch (_: Exception) {} }
    overlayPlayers.clear()
    overlayLoadedUris.clear()
    audioTrackPlayers.values.forEach { try { it.stop(); it.release() } catch (_: Exception) {} }
    audioTrackPlayers.clear()
    audioLoadedUris.clear()
    engineController.release()
  }
}
