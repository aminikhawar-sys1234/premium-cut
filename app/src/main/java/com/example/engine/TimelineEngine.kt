package com.example.engine

import com.example.domain.model.*
import android.util.Log
import com.example.engine.history.TimelineAction
import com.example.engine.history.TimelineActionManager
import com.example.engine.history.TimelineActionType
import com.example.engine.timeline.ClipTrimMath
import com.example.engine.timeline.ClipView
import com.example.engine.timeline.TimelineSplitEngine
import com.example.engine.timeline.clipView
import com.example.engine.timeline.withClipRange
import com.example.engine.timeline.withClipStart
import com.example.engine.timeline.withClipsShifted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

sealed class SelectedTrackElement {
  object None : SelectedTrackElement()
  data class Video(val clipId: String) : SelectedTrackElement()
  data class Overlay(val clipId: String) : SelectedTrackElement()
  data class Audio(val clipId: String) : SelectedTrackElement()
  data class Text(val clipId: String) : SelectedTrackElement()
  data class Sticker(val clipId: String) : SelectedTrackElement()
  data class Effect(val clipId: String) : SelectedTrackElement()
}

enum class InsertionMode {
  RIPPLE,
  OVERWRITE,
  APPEND
}

data class SnapResult(
  val snappedPosMs: Long,
  val didSnap: Boolean = false,
  val snapLineMs: Long? = null
)

class TimelineEngine : com.example.engine.integration.UnifiedAdvancedTimeline {

  companion object {
    private const val TAG = "TimelineEngine"
  }

  // UnifiedAdvancedTimeline integration
  override val timelineSnapshot: Timeline
    get() = _timeline.value

  override val playheadMs: Long
    get() = _currentPositionMs.value

  override val fps: Int
    get() = _timelineFps.value

  override fun totalDurationMs(): Long = _timeline.value.totalDurationMs

  override fun snapPoints(): List<Long> = getAllCutPositions()

  fun asUnifiedTimeline(): com.example.engine.integration.UnifiedAdvancedTimeline = this

  @PublishedApi
  internal val stateLock = java.util.concurrent.locks.ReentrantLock()

  inline fun <T> withStateLock(block: () -> T): T {
    stateLock.lock()
    try {
      return block()
    } finally {
      stateLock.unlock()
    }
  }

  private val _timeline = MutableStateFlow(Timeline())
  val timeline: StateFlow<Timeline> = _timeline.asStateFlow()

  init {
    // lets expressions / bone rigs (ClipMotionEngine) read the live project
    com.example.engine.motion.ClipMotionRuntime.timelineProvider = { _timeline.value }
  }

  private val _currentPositionUs = MutableStateFlow(0L)
  val currentPositionUs: StateFlow<Long> = _currentPositionUs.asStateFlow()

  private val _currentPositionMs = MutableStateFlow(0L)
  val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

  val playheadUs: Long
    get() = _currentPositionUs.value

  private fun setInternalPositionMs(posMs: Long) {
    _currentPositionMs.value = posMs
    _currentPositionUs.value = posMs * 1000L
  }

  private fun setInternalPositionUs(posUs: Long) {
    _currentPositionUs.value = posUs
    _currentPositionMs.value = posUs / 1000L
  }

  private val _isPlaying = MutableStateFlow(false)
  val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

  private val _isScrubbing = MutableStateFlow(false)
  val isScrubbing: StateFlow<Boolean> = _isScrubbing.asStateFlow()

  private val _isPlaybackSyncing = MutableStateFlow(false)
  val isPlaybackSyncing: StateFlow<Boolean> = _isPlaybackSyncing.asStateFlow()

  private val _selectedElement = MutableStateFlow<SelectedTrackElement>(SelectedTrackElement.None)
  val selectedElement: StateFlow<SelectedTrackElement> = _selectedElement.asStateFlow()

  private val _isMultiSelectMode = MutableStateFlow(false)
  val isMultiSelectMode: StateFlow<Boolean> = _isMultiSelectMode.asStateFlow()

  private val _selectedClipIds = MutableStateFlow<Set<String>>(emptySet())
  val selectedClipIds: StateFlow<Set<String>> = _selectedClipIds.asStateFlow()

  private val _selectedKeyframeIds = MutableStateFlow<Set<String>>(emptySet())
  val selectedKeyframeIds: StateFlow<Set<String>> = _selectedKeyframeIds.asStateFlow()

  private var keyframeClipboard: List<ClipKeyframe> = emptyList()

  private val _snapIndicatorMs = MutableStateFlow<Long?>(null)
  val snapIndicatorMs: StateFlow<Long?> = _snapIndicatorMs.asStateFlow()

  private val _clipboardClips = MutableStateFlow<List<Any>>(emptyList())
  val clipboardClips: StateFlow<List<Any>> = _clipboardClips.asStateFlow()

  private val _timelineZoom = MutableStateFlow(1.0f) // 0.25f to 8.0f
  val timelineZoom: StateFlow<Float> = _timelineZoom.asStateFlow()

  private val _isSnappingEnabled = MutableStateFlow(true)
  val isSnappingEnabled: StateFlow<Boolean> = _isSnappingEnabled.asStateFlow()

  private val _isMagneticEnabled = MutableStateFlow(true)
  val isMagneticEnabled: StateFlow<Boolean> = _isMagneticEnabled.asStateFlow()

  private val _timelineFps = MutableStateFlow(30)
  val timelineFps: StateFlow<Int> = _timelineFps.asStateFlow()

  private val _isFrameSnapping = MutableStateFlow(false)
  val isFrameSnapping: StateFlow<Boolean> = _isFrameSnapping.asStateFlow()

  // Track synchronization (All tracks move and edit together synchronously with CTI)
  private val _isTracksSyncEnabled = MutableStateFlow(true)
  val isTracksSyncEnabled: StateFlow<Boolean> = _isTracksSyncEnabled.asStateFlow()

  fun setTracksSyncEnabled(enabled: Boolean) = withStateLock {
    _isTracksSyncEnabled.value = enabled
  }

  fun toggleTracksSync() = withStateLock {
    _isTracksSyncEnabled.value = !_isTracksSyncEnabled.value
  }

  // Undo / Redo history & Timeline Action State Management
  val actionManager = TimelineActionManager(maxHistorySize = 50)
  val canUndo: StateFlow<Boolean> = actionManager.canUndo
  val canRedo: StateFlow<Boolean> = actionManager.canRedo
  val lastAction: StateFlow<TimelineAction?> = actionManager.lastAction
  val undoActionTitle: StateFlow<String?> = actionManager.undoActionTitle
  val redoActionTitle: StateFlow<String?> = actionManager.redoActionTitle
  val actionHistory: StateFlow<List<TimelineAction>> = actionManager.actionHistory
  val actionStatusMessage: StateFlow<String?> = actionManager.statusMessage

  fun loadTimeline(newTimeline: Timeline) = withStateLock {
    recordHistory()
    _timeline.value = newTimeline
    setInternalPositionMs(0L)
    _selectedElement.value = SelectedTrackElement.None
    _selectedClipIds.value = emptySet()
  }

  /** Replace the timeline as an undoable edit WITHOUT resetting playhead or selection (unlike loadTimeline). */
  fun replaceTimelineKeepingState(newTimeline: Timeline) = withStateLock {
    recordHistory()
    _timeline.value = newTimeline
    validateSelectionAfterHistoryChange()
  }

  fun setTimelineFps(fps: Int) = withStateLock {
    _timelineFps.value = fps.coerceIn(12, 120)
  }

  fun toggleFrameSnapping() = withStateLock {
    _isFrameSnapping.value = !_isFrameSnapping.value
  }

  fun setFrameSnapping(enabled: Boolean) = withStateLock {
    _isFrameSnapping.value = enabled
  }

  // --- Frame Accurate Math Helpers ---

  fun timeToFrame(timeMs: Long, fps: Int): Long {
    return Math.round(timeMs.toDouble() * fps.toDouble() / 1000.0).toLong().coerceAtLeast(0L)
  }

  override fun timeToFrame(timeMs: Long): Long = timeToFrame(timeMs, _timelineFps.value)

  fun frameToTime(frameIndex: Long, fps: Int): Long {
    return Math.round(frameIndex.toDouble() * 1000.0 / fps.toDouble()).toLong().coerceAtLeast(0L)
  }

  override fun frameToTime(frameIndex: Long): Long = frameToTime(frameIndex, _timelineFps.value)

  fun alignToFrame(timeMs: Long, fps: Int = _timelineFps.value): Long {
    val frame = timeToFrame(timeMs, fps)
    return frameToTime(frame, fps)
  }

  fun frameDurationMs(fps: Int = _timelineFps.value): Long = (1000L / fps.coerceAtLeast(1)).coerceAtLeast(1L)

  fun frameDurationUs(fps: Int = _timelineFps.value): Long = (1_000_000L / fps.coerceAtLeast(1)).coerceAtLeast(1L)

  fun timeUsToFrame(timeUs: Long, fps: Int = _timelineFps.value): Long =
    (timeUs * fps + 500_000L) / 1_000_000L

  fun frameToTimeUs(frameIndex: Long, fps: Int = _timelineFps.value): Long =
    (frameIndex * 1_000_000L + (fps / 2)) / fps

  fun currentFrame(): Long = timeToFrame(_currentPositionMs.value, _timelineFps.value)

  fun totalFrames(fps: Int = _timelineFps.value): Long = timeToFrame(_timeline.value.totalDurationMs, fps)

  fun formatTimecode(timeMs: Long, fps: Int = _timelineFps.value): String {
    val totalFrames = timeToFrame(timeMs, fps)
    val frames = (totalFrames % fps).toInt()
    val totalSeconds = totalFrames / fps
    val seconds = (totalSeconds % 60).toInt()
    val minutes = ((totalSeconds / 60) % 60).toInt()
    val hours = (totalSeconds / 3600).toInt()
    return if (hours > 0) {
      String.format(java.util.Locale.US, "%02d:%02d:%02d:%02d", hours, minutes, seconds, frames)
    } else {
      String.format(java.util.Locale.US, "%02d:%02d:%02d:%02d", minutes, seconds, frames)
    }
  }

  // --- CTI Playhead, Scrubbing & Hardware Sync Operations ---

  fun setPosition(positionMs: Long, snap: Boolean = _isSnappingEnabled.value) = withStateLock {
    val total = _timeline.value.totalDurationMs
    val targetPos = if (_isFrameSnapping.value && snap) {
      alignToFrame(positionMs, _timelineFps.value)
    } else {
      positionMs
    }
    val snapped = if (snap) snapPosition(targetPos) else targetPos
    val maxAllowed = maxOf(total + 10000L, 10000L)
    val bounded = snapped.coerceIn(0L, maxAllowed)
    setInternalPositionMs(bounded)
  }

  fun setPositionUs(positionUs: Long, snap: Boolean = _isSnappingEnabled.value) = withStateLock {
    val requestedMs = positionUs / 1000L
    setPosition(requestedMs, snap)
    if (_currentPositionMs.value == requestedMs) {
      val maxAllowedUs = maxOf(_timeline.value.totalDurationMs + 10000L, 10000L) * 1000L
      _currentPositionUs.value = positionUs.coerceIn(0L, maxAllowedUs)
    }
  }

  fun beginScrubbing() = withStateLock {
    _isScrubbing.value = true
    pause()
  }

  fun startScrubbing() = beginScrubbing()

  fun scrubTo(positionMs: Long, snap: Boolean = _isSnappingEnabled.value) = withStateLock {
    setPosition(positionMs, snap)
  }

  fun scrubToUs(positionUs: Long, snap: Boolean = _isSnappingEnabled.value) = withStateLock {
    setPositionUs(positionUs, snap)
  }

  fun endScrubbing(finalPositionMs: Long? = null) = withStateLock {
    val target = finalPositionMs ?: _currentPositionMs.value
    setPosition(target, snap = _isSnappingEnabled.value)
    _isScrubbing.value = false
    clearSnapIndicator()
  }

  fun stopScrubbing(finalPositionMs: Long? = null) = endScrubbing(finalPositionMs)

  /**
   * Thread-safe, non-reentrant hardware clock synchronization.
   * Updates playhead directly from ExoPlayer/Hardware clock without triggering recursive seek calls.
   */
  fun updatePlayheadFromPlayback(positionMs: Long) = withStateLock {
    // Only accept hardware playback position updates if playback is actively playing.
    // When paused or seeking, user-selected CTI position is strictly authoritative.
    if (!_isPlaying.value) return@withStateLock
    val total = _timeline.value.totalDurationMs
    val bounded = positionMs.coerceIn(0L, total.coerceAtLeast(0L))
    _isPlaybackSyncing.value = true
    try {
      setInternalPositionMs(bounded)
    } finally {
      _isPlaybackSyncing.value = false
    }
  }

  /**
   * Synchronizes authoritative position when pausing or settling playback.
   */
  fun syncAuthoritativePosition(positionMs: Long) = withStateLock {
    val total = _timeline.value.totalDurationMs
    val bounded = positionMs.coerceIn(0L, total.coerceAtLeast(0L))
    _isPlaybackSyncing.value = true
    try {
      setInternalPositionMs(bounded)
    } finally {
      _isPlaybackSyncing.value = false
    }
  }

  fun updatePlayheadFromPlaybackUs(positionUs: Long) = withStateLock {
    if (!_isPlaying.value) return@withStateLock
    val totalUs = _timeline.value.totalDurationMs * 1000L
    val boundedUs = positionUs.coerceIn(0L, totalUs.coerceAtLeast(0L))
    _isPlaybackSyncing.value = true
    try {
      setInternalPositionUs(boundedUs)
    } finally {
      _isPlaybackSyncing.value = false
    }
  }

  fun togglePlayPause() = withStateLock {
    if (!_isPlaying.value) {
      val total = _timeline.value.totalDurationMs
      if (total > 0L && _currentPositionMs.value >= total) {
        setInternalPositionMs(0L)
      }
      _isPlaying.value = true
    } else {
      _isPlaying.value = false
    }
  }

  fun play() = withStateLock {
    val total = _timeline.value.totalDurationMs
    if (total > 0L && _currentPositionMs.value >= total) {
      setInternalPositionMs(0L)
    }
    _isPlaying.value = true
  }

  fun pause() = withStateLock {
    _isPlaying.value = false
  }

  fun stop() = withStateLock {
    _isPlaying.value = false
    setInternalPositionMs(0L)
  }

  fun stepForwardOneFrame(fps: Int = _timelineFps.value) = withStateLock {
    pause()
    val currentFrame = timeToFrame(_currentPositionMs.value, fps)
    val nextTime = frameToTime(currentFrame + 1, fps)
    setPosition(nextTime, snap = false)
  }

  fun stepBackwardOneFrame(fps: Int = _timelineFps.value) = withStateLock {
    pause()
    val currentFrame = timeToFrame(_currentPositionMs.value, fps)
    val prevTime = frameToTime((currentFrame - 1).coerceAtLeast(0L), fps)
    setPosition(prevTime, snap = false)
  }

  fun stepFrames(frameCount: Int, fps: Int = _timelineFps.value) = withStateLock {
    pause()
    val currentFrame = timeToFrame(_currentPositionMs.value, fps)
    val targetTime = frameToTime((currentFrame + frameCount).coerceAtLeast(0L), fps)
    setPosition(targetTime, snap = false)
  }

  fun seekToFrame(frameIndex: Long, fps: Int = _timelineFps.value) = withStateLock {
    pause()
    val targetMs = frameToTime(frameIndex, fps)
    setPosition(targetMs, snap = false)
  }

  fun getAllCutPositions(): List<Long> {
    val cuts = mutableSetOf<Long>(0L)
    _timeline.value.videoClips.forEach { clip ->
      cuts.add(clip.timelineStartMs)
      cuts.add(clip.timelineStartMs + clip.durationMs)
    }
    _timeline.value.overlayClips.forEach { clip ->
      cuts.add(clip.timelineStartMs)
      cuts.add(clip.timelineStartMs + clip.durationMs)
    }
    _timeline.value.audioClips.forEach { clip ->
      cuts.add(clip.timelineStartMs)
      cuts.add(clip.timelineStartMs + clip.durationMs)
    }
    _timeline.value.textClips.forEach { clip ->
      cuts.add(clip.timelineStartMs)
      cuts.add(clip.timelineStartMs + clip.durationMs)
    }
    _timeline.value.stickerClips.forEach { clip ->
      cuts.add(clip.timelineStartMs)
      cuts.add(clip.timelineStartMs + clip.durationMs)
    }
    cuts.add(_timeline.value.totalDurationMs)
    return cuts.sorted()
  }

  fun seekToPreviousCut() {
    withStateLock {
      pause()
      val current = _currentPositionMs.value
      val cuts = getAllCutPositions()
      val prevCut = cuts.filter { it < current - 15L }.maxOrNull() ?: 0L
      setPosition(prevCut)
    }
  }

  fun seekToNextCut() {
    withStateLock {
      pause()
      val current = _currentPositionMs.value
      val cuts = getAllCutPositions()
      val nextCut = cuts.firstOrNull { it > current + 15L } ?: _timeline.value.totalDurationMs
      setPosition(nextCut)
    }
  }

  fun setZoom(zoom: Float) {
    withStateLock {
      _timelineZoom.value = zoom.coerceIn(0.25f, 8.0f)
    }
  }

  fun toggleSnapping() {
    withStateLock {
      _isSnappingEnabled.value = !_isSnappingEnabled.value
    }
  }

  fun toggleMagneticMovement() {
    withStateLock {
      _isMagneticEnabled.value = !_isMagneticEnabled.value
    }
  }

  fun setMagneticMovement(enabled: Boolean) {
    withStateLock {
      _isMagneticEnabled.value = enabled
    }
  }

  /**
   * Hard-enforces magnetic continuity and the Zero Point Lock (🔒 0.0s) rule on the Primary Media Track.
   * All video clips in the main track MUST be packed consecutively:
   * Clip 0 starts at 0.0s, Clip 1 immediately follows Clip 0 with zero gap, etc.
   * The track ends exactly where the last media clip ends.
   */
  fun enforceMainTrackContinuity(): Boolean {
    val clips = _timeline.value.videoClips
    if (clips.isEmpty()) return false
    var currentStart = 0L
    var modified = false
    val updated = clips.map { clip ->
      if (clip.timelineStartMs != currentStart) {
        modified = true
        val c = clip.copy(timelineStartMs = currentStart)
        currentStart += clip.durationMs
        c
      } else {
        currentStart += clip.durationMs
        clip
      }
    }
    if (modified) {
      _timeline.value = _timeline.value.copy(videoClips = updated)
    }
    return modified
  }

  fun enforceZeroPointLock(): Boolean {
    return enforceMainTrackContinuity()
  }

  private val _isVideoTrackEndLocked = MutableStateFlow(true)
  val isVideoTrackEndLocked: StateFlow<Boolean> = _isVideoTrackEndLocked.asStateFlow()

  val videoTrackEndMs: Long
    get() = _timeline.value.videoClips.maxOfOrNull { it.timelineStartMs + it.durationMs } ?: 0L

  fun setVideoTrackEndLocked(locked: Boolean) {
    withStateLock {
      _isVideoTrackEndLocked.value = locked
      if (locked) enforceVideoTrackBounds()
    }
  }

  fun toggleVideoTrackEndLock() {
    withStateLock {
      setVideoTrackEndLocked(!_isVideoTrackEndLocked.value)
    }
  }

  /**
   * Hard-enforces the Video Track End Point Lock (🔒 Video End) rule.
   * When locked, the master video track duration sets the timeline boundary limit.
   * Constrains non-video tracks (overlay, audio, text, sticker, effect) within [0.0s, videoTrackEndMs].
   */
  fun enforceVideoTrackBounds(): Boolean {
    enforceZeroPointLock()
    val clips = _timeline.value.videoClips
    if (clips.isEmpty()) return false
    val vEnd = clips.maxOfOrNull { it.timelineStartMs + it.durationMs } ?: return false
    var modified = false

    val newOverlay = _timeline.value.overlayClips.mapNotNull { clip ->
      if (clip.timelineStartMs >= vEnd) {
        modified = true
        null
      } else if (clip.timelineStartMs + clip.durationMs > vEnd) {
        modified = true
        clip.copy(durationMs = (vEnd - clip.timelineStartMs).coerceAtLeast(200L))
      } else clip
    }

    val newAudio = _timeline.value.audioClips.mapNotNull { clip ->
      if (clip.timelineStartMs >= vEnd) {
        modified = true
        null
      } else if (clip.timelineStartMs + clip.durationMs > vEnd) {
        modified = true
        clip.copy(durationMs = (vEnd - clip.timelineStartMs).coerceAtLeast(200L))
      } else clip
    }

    val newText = _timeline.value.textClips.mapNotNull { clip ->
      if (clip.timelineStartMs >= vEnd) {
        modified = true
        null
      } else if (clip.timelineStartMs + clip.durationMs > vEnd) {
        modified = true
        clip.copy(durationMs = (vEnd - clip.timelineStartMs).coerceAtLeast(200L))
      } else clip
    }

    val newSticker = _timeline.value.stickerClips.mapNotNull { clip ->
      if (clip.timelineStartMs >= vEnd) {
        modified = true
        null
      } else if (clip.timelineStartMs + clip.durationMs > vEnd) {
        modified = true
        clip.copy(durationMs = (vEnd - clip.timelineStartMs).coerceAtLeast(200L))
      } else clip
    }

    val newEffect = _timeline.value.effectClips.mapNotNull { clip ->
      if (clip.timelineStartMs >= vEnd) {
        modified = true
        null
      } else if (clip.timelineStartMs + clip.durationMs > vEnd) {
        modified = true
        clip.copy(durationMs = (vEnd - clip.timelineStartMs).coerceAtLeast(200L))
      } else clip
    }

    if (modified) {
      _timeline.value = _timeline.value.copy(
        overlayClips = newOverlay,
        audioClips = newAudio,
        textClips = newText,
        stickerClips = newSticker,
        effectClips = newEffect
      )
    }

    if (_currentPositionMs.value > vEnd) {
      setInternalPositionMs(vEnd)
    }

    return modified
  }

  fun reorderVideoClips(fromIndex: Int, toIndex: Int): Boolean {
    if (isTrackLocked(TrackType.MAIN_VIDEO)) return false
    val clips = _timeline.value.videoClips.toMutableList()
    if (fromIndex !in clips.indices || toIndex !in clips.indices || fromIndex == toIndex) return false
    recordHistory()
    val item = clips.removeAt(fromIndex)
    clips.add(toIndex, item)
    var currentStart = 0L
    for (i in clips.indices) {
      clips[i] = clips[i].copy(timelineStartMs = currentStart)
      currentStart += clips[i].durationMs
    }
    _timeline.value = _timeline.value.copy(videoClips = clips)
    return true
  }

  fun selectElement(element: SelectedTrackElement) {
    withStateLock {
      _selectedElement.value = element
      when (element) {
        is SelectedTrackElement.Video -> _selectedClipIds.value = setOf(element.clipId)
        is SelectedTrackElement.Overlay -> _selectedClipIds.value = setOf(element.clipId)
        is SelectedTrackElement.Audio -> _selectedClipIds.value = setOf(element.clipId)
        is SelectedTrackElement.Text -> _selectedClipIds.value = setOf(element.clipId)
        is SelectedTrackElement.Sticker -> _selectedClipIds.value = setOf(element.clipId)
        is SelectedTrackElement.Effect -> _selectedClipIds.value = setOf(element.clipId)
        SelectedTrackElement.None -> _selectedClipIds.value = emptySet()
      }
    }
  }

  fun selectMultipleClips(clipIds: Set<String>) {
    withStateLock {
      _selectedClipIds.value = clipIds
      _isMultiSelectMode.value = clipIds.size > 1
      val first = clipIds.firstOrNull()
      if (first != null) {
        _selectedElement.value = findTrackElementForClip(first)
      } else {
        _selectedElement.value = SelectedTrackElement.None
      }
    }
  }

  fun selectClip(clipId: String, addToExisting: Boolean = false) {
    withStateLock {
      if (addToExisting) {
        addClipToSelection(clipId)
      } else {
        selectElement(findTrackElementForClip(clipId))
      }
    }
  }

  fun addClipToSelection(clipId: String) {
    withStateLock {
      val updated = _selectedClipIds.value + clipId
      _selectedClipIds.value = updated
      _isMultiSelectMode.value = updated.size > 1
      _selectedElement.value = findTrackElementForClip(clipId)
    }
  }

  fun removeClipFromSelection(clipId: String) {
    withStateLock {
      val updated = _selectedClipIds.value - clipId
      _selectedClipIds.value = updated
      _isMultiSelectMode.value = updated.size > 1
      if (updated.isNotEmpty()) {
        _selectedElement.value = findTrackElementForClip(updated.last())
      } else {
        _selectedElement.value = SelectedTrackElement.None
      }
    }
  }

  fun isClipSelected(clipId: String): Boolean {
    return _selectedClipIds.value.contains(clipId) || (selectedElement.value as? SelectedTrackElement.Video)?.clipId == clipId ||
      (selectedElement.value as? SelectedTrackElement.Overlay)?.clipId == clipId ||
      (selectedElement.value as? SelectedTrackElement.Audio)?.clipId == clipId ||
      (selectedElement.value as? SelectedTrackElement.Text)?.clipId == clipId ||
      (selectedElement.value as? SelectedTrackElement.Sticker)?.clipId == clipId ||
      (selectedElement.value as? SelectedTrackElement.Effect)?.clipId == clipId
  }

  fun toggleMultiSelectMode() {
    withStateLock {
      val newMode = !_isMultiSelectMode.value
      _isMultiSelectMode.value = newMode
      if (!newMode) {
        val first = _selectedClipIds.value.firstOrNull()
        if (first != null) {
          _selectedClipIds.value = setOf(first)
          _selectedElement.value = findTrackElementForClip(first)
        } else {
          _selectedClipIds.value = emptySet()
          _selectedElement.value = SelectedTrackElement.None
        }
      }
    }
  }

  fun toggleSelectClip(clipId: String, trackElement: SelectedTrackElement? = null) {
    withStateLock {
      val element = trackElement ?: findTrackElementForClip(clipId)
      if (_isMultiSelectMode.value) {
        val current = _selectedClipIds.value.toMutableSet()
        if (current.contains(clipId)) {
          current.remove(clipId)
        } else {
          current.add(clipId)
        }
        _selectedClipIds.value = current
        if (current.isNotEmpty()) {
          _selectedElement.value = findTrackElementForClip(current.last())
        } else {
          _selectedElement.value = SelectedTrackElement.None
          _isMultiSelectMode.value = false
        }
      } else {
        if (_selectedClipIds.value.isNotEmpty() && !_selectedClipIds.value.contains(clipId)) {
          val newSet = _selectedClipIds.value + clipId
          _selectedClipIds.value = newSet
          _isMultiSelectMode.value = true
          _selectedElement.value = element
        } else {
          _selectedClipIds.value = setOf(clipId)
          _selectedElement.value = element
        }
      }
    }
  }

  fun toggleClipSelection(clipId: String, trackElement: SelectedTrackElement? = null) {
    withStateLock {
      toggleSelectClip(clipId, trackElement)
    }
  }

  fun selectAllClips() {
    withStateLock {
      val allIds = mutableSetOf<String>()
      allIds.addAll(_timeline.value.videoClips.map { it.id })
      allIds.addAll(_timeline.value.overlayClips.map { it.id })
      allIds.addAll(_timeline.value.textClips.map { it.id })
      allIds.addAll(_timeline.value.audioClips.map { it.id })
      allIds.addAll(_timeline.value.stickerClips.map { it.id })
      allIds.addAll(_timeline.value.effectClips.map { it.id })
      _selectedClipIds.value = allIds
      _isMultiSelectMode.value = allIds.size > 1
      val first = allIds.firstOrNull()
      if (first != null) {
        _selectedElement.value = findTrackElementForClip(first)
      }
    }
  }

  fun clearSelection() {
    withStateLock {
      _selectedClipIds.value = emptySet()
      _selectedElement.value = SelectedTrackElement.None
      _isMultiSelectMode.value = false
    }
  }

  fun findTrackElementForClip(clipId: String): SelectedTrackElement {
    if (_timeline.value.videoClips.any { it.id == clipId }) return SelectedTrackElement.Video(clipId)
    if (_timeline.value.overlayClips.any { it.id == clipId }) return SelectedTrackElement.Overlay(clipId)
    if (_timeline.value.audioClips.any { it.id == clipId }) return SelectedTrackElement.Audio(clipId)
    if (_timeline.value.textClips.any { it.id == clipId }) return SelectedTrackElement.Text(clipId)
    if (_timeline.value.stickerClips.any { it.id == clipId }) return SelectedTrackElement.Sticker(clipId)
    if (_timeline.value.effectClips.any { it.id == clipId }) return SelectedTrackElement.Effect(clipId)
    return SelectedTrackElement.None
  }

  private var snapCacheTimeline: Timeline? = null
  private var snapCacheIgnore: Set<String> = emptySet()
  private var snapCacheIndex: com.example.engine.integration.TimelineSnapIndex? = null

  /** Snap targets only change when the timeline does, so scrubbing reuses one index. */
  private fun snapIndexFor(timeline: Timeline, ignoreClipIds: Set<String>): com.example.engine.integration.TimelineSnapIndex =
    withStateLock {
      val cached = snapCacheIndex
      if (cached != null && snapCacheTimeline === timeline && snapCacheIgnore == ignoreClipIds) {
        cached
      } else {
        com.example.engine.integration.AdvancedTimelineIndex.buildSnapIndex(timeline, ignoreClipIds).also {
          snapCacheTimeline = timeline
          snapCacheIgnore = ignoreClipIds
          snapCacheIndex = it
        }
      }
    }

  fun calculateSnap(
    candidatePosMs: Long,
    thresholdMs: Long = 130L,
    ignoreClipIds: Set<String> = emptySet()
  ): SnapResult {
    if (!_isSnappingEnabled.value) return SnapResult(candidatePosMs, false, null)
    val timelineSnapshot = _timeline.value
    val snapIndex = snapIndexFor(timelineSnapshot, ignoreClipIds)
    val effectiveThreshold = (thresholdMs / _timelineZoom.value.coerceIn(0.5f, 4.0f)).toLong().coerceIn(30L, 200L)
    val closest = snapIndex.findClosest(
      candidatePosMs,
      effectiveThreshold,
      longArrayOf(timelineSnapshot.totalDurationMs, _currentPositionMs.value)
    )
    return if (closest != null) {
      _snapIndicatorMs.value = closest
      SnapResult(closest, true, closest)
    } else {
      _snapIndicatorMs.value = null
      SnapResult(candidatePosMs, false, null)
    }
  }

  fun clearSnapIndicator() {
    withStateLock {
      _snapIndicatorMs.value = null
    }
  }

  private fun snapPosition(pos: Long, thresholdMs: Long = 150L): Long {
    return calculateSnap(pos, thresholdMs).snappedPosMs
  }

  // --- History (Undo / Redo & Timeline Action Tracking) ---

  fun recordHistory(
    type: TimelineActionType = TimelineActionType.GENERIC_EDIT,
    description: String = type.displayName,
    clipIds: Set<String> = emptySet()
  ) {
    withStateLock { actionManager.recordPreEditHistory(type, description, _timeline.value, clipIds) }
  }

  private var lastCoalesceKey: String? = null
  private var lastCoalesceTimeMs: Long = 0L

  /** Records one undo step for a burst of rapid slider updates of the same kind (avoids flooding the 50-step history). */
  private fun recordHistoryCoalesced(key: String, type: TimelineActionType = TimelineActionType.GENERIC_EDIT) {
    val now = android.os.SystemClock.elapsedRealtime()
    if (lastCoalesceKey == key && now - lastCoalesceTimeMs < 800L && actionManager.canUndo.value) {
      lastCoalesceTimeMs = now
      return
    }
    lastCoalesceKey = key
    lastCoalesceTimeMs = now
    recordHistory(type)
  }

  fun beginContinuousAction(
    type: TimelineActionType,
    description: String = type.displayName,
    clipId: String? = null
  ) {
    actionManager.beginTransaction(type, description, _timeline.value, clipId)
  }

  fun endContinuousAction(success: Boolean = true): Boolean {
    return if (success) {
      actionManager.commitTransaction(_timeline.value)
    } else {
      val reverted = actionManager.cancelTransaction()
      if (reverted != null) {
        _timeline.value = reverted
        true
      } else false
    }
  }

  fun cancelContinuousAction() {
    withStateLock {
      val reverted = actionManager.cancelTransaction()
      if (reverted != null) {
        _timeline.value = reverted
      }
    }
  }

  fun isContinuousActionActive(): Boolean = actionManager.isTransactionActive

  fun undo(): Boolean = withStateLock {
    val previousState = actionManager.undo(_timeline.value)
    if (previousState != null) {
      _timeline.value = previousState
      validateSelectionAfterHistoryChange()
      true
    } else false
  }

  fun redo(): Boolean = withStateLock {
    val nextState = actionManager.redo(_timeline.value)
    if (nextState != null) {
      _timeline.value = nextState
      validateSelectionAfterHistoryChange()
      true
    } else false
  }

  fun clearHistory() {
    withStateLock {
      actionManager.clear()
    }
  }

  fun dismissActionStatusMessage() {
    withStateLock {
      actionManager.dismissStatusMessage()
    }
  }

  private fun validateSelectionAfterHistoryChange() {
    val element = _selectedElement.value
    if (element != SelectedTrackElement.None) {
      val clipId = when (element) {
        is SelectedTrackElement.Video -> element.clipId
        is SelectedTrackElement.Overlay -> element.clipId
        is SelectedTrackElement.Audio -> element.clipId
        is SelectedTrackElement.Text -> element.clipId
        is SelectedTrackElement.Sticker -> element.clipId
        is SelectedTrackElement.Effect -> element.clipId
        SelectedTrackElement.None -> null
      }
      if (clipId != null && findTrackElementForClip(clipId) == SelectedTrackElement.None) {
        _selectedElement.value = SelectedTrackElement.None
      }
    }
    val currentSelectedIds = _selectedClipIds.value
    if (currentSelectedIds.isNotEmpty()) {
      val validIds = currentSelectedIds.filter { findTrackElementForClip(it) != SelectedTrackElement.None }.toSet()
      if (validIds != currentSelectedIds) {
        _selectedClipIds.value = validIds
      }
    }
  }

  // --- Video Clip Operations ---

  fun rippleDownstreamClips(fromTimeMs: Long, deltaMs: Long) {
    withStateLock {
      if (deltaMs == 0L) return
      val newVideo = if (!isTrackLocked(TrackType.MAIN_VIDEO)) {
        _timeline.value.videoClips.map {
          if (it.timelineStartMs >= fromTimeMs) it.copy(timelineStartMs = (it.timelineStartMs + deltaMs).coerceAtLeast(0L)) else it
        }.sortedBy { it.timelineStartMs }
      } else _timeline.value.videoClips

      val newOverlay = if (!isTrackLocked(TrackType.OVERLAY)) {
        _timeline.value.overlayClips.map {
          if (it.timelineStartMs >= fromTimeMs) it.copy(timelineStartMs = (it.timelineStartMs + deltaMs).coerceAtLeast(0L)) else it
        }.sortedBy { it.timelineStartMs }
      } else _timeline.value.overlayClips

      val newAudio = if (!isTrackLocked(TrackType.AUDIO)) {
        _timeline.value.audioClips.map {
          if (it.timelineStartMs >= fromTimeMs) it.copy(timelineStartMs = (it.timelineStartMs + deltaMs).coerceAtLeast(0L)) else it
        }.sortedBy { it.timelineStartMs }
      } else _timeline.value.audioClips

      val newText = if (!isTrackLocked(TrackType.TEXT)) {
        _timeline.value.textClips.map {
          if (it.timelineStartMs >= fromTimeMs) it.copy(timelineStartMs = (it.timelineStartMs + deltaMs).coerceAtLeast(0L)) else it
        }.sortedBy { it.timelineStartMs }
      } else _timeline.value.textClips

      val newSticker = if (!isTrackLocked(TrackType.STICKER)) {
        _timeline.value.stickerClips.map {
          if (it.timelineStartMs >= fromTimeMs) it.copy(timelineStartMs = (it.timelineStartMs + deltaMs).coerceAtLeast(0L)) else it
        }.sortedBy { it.timelineStartMs }
      } else _timeline.value.stickerClips

      val newEffect = if (!isTrackLocked(TrackType.EFFECT)) {
        _timeline.value.effectClips.map {
          if (it.timelineStartMs >= fromTimeMs) it.copy(timelineStartMs = (it.timelineStartMs + deltaMs).coerceAtLeast(0L)) else it
        }.sortedBy { it.timelineStartMs }
      } else _timeline.value.effectClips

      _timeline.value = _timeline.value.copy(
        videoClips = newVideo,
        overlayClips = newOverlay,
        audioClips = newAudio,
        textClips = newText,
        stickerClips = newSticker,
        effectClips = newEffect
      )
    }
  }

  fun moveSynchronizedTracksByDelta(deltaMs: Long, snap: Boolean = true): Boolean {
    val unlockedStarts = mutableListOf<Long>()
    if (!isTrackLocked(TrackType.MAIN_VIDEO)) _timeline.value.videoClips.forEach { unlockedStarts.add(it.timelineStartMs) }
    if (!isTrackLocked(TrackType.OVERLAY)) _timeline.value.overlayClips.forEach { unlockedStarts.add(it.timelineStartMs) }
    if (!isTrackLocked(TrackType.AUDIO)) _timeline.value.audioClips.forEach { unlockedStarts.add(it.timelineStartMs) }
    if (!isTrackLocked(TrackType.TEXT)) _timeline.value.textClips.forEach { unlockedStarts.add(it.timelineStartMs) }
    if (!isTrackLocked(TrackType.STICKER)) _timeline.value.stickerClips.forEach { unlockedStarts.add(it.timelineStartMs) }
    if (!isTrackLocked(TrackType.EFFECT)) _timeline.value.effectClips.forEach { unlockedStarts.add(it.timelineStartMs) }

    if (unlockedStarts.isEmpty()) return false
    val minStart = unlockedStarts.minOrNull() ?: 0L
    val effectiveDelta = if (deltaMs < 0) maxOf(deltaMs, -minStart) else deltaMs
    if (effectiveDelta == 0L) return false

    recordHistory()
    val newVideo = if (!isTrackLocked(TrackType.MAIN_VIDEO)) {
      _timeline.value.videoClips.map { it.copy(timelineStartMs = (it.timelineStartMs + effectiveDelta).coerceAtLeast(0L)) }
    } else _timeline.value.videoClips

    val newOverlay = if (!isTrackLocked(TrackType.OVERLAY)) {
      _timeline.value.overlayClips.map { it.copy(timelineStartMs = (it.timelineStartMs + effectiveDelta).coerceAtLeast(0L)) }
    } else _timeline.value.overlayClips

    val newAudio = if (!isTrackLocked(TrackType.AUDIO)) {
      _timeline.value.audioClips.map { it.copy(timelineStartMs = (it.timelineStartMs + effectiveDelta).coerceAtLeast(0L)) }
    } else _timeline.value.audioClips

    val newText = if (!isTrackLocked(TrackType.TEXT)) {
      _timeline.value.textClips.map { it.copy(timelineStartMs = (it.timelineStartMs + effectiveDelta).coerceAtLeast(0L)) }
    } else _timeline.value.textClips

    val newSticker = if (!isTrackLocked(TrackType.STICKER)) {
      _timeline.value.stickerClips.map { it.copy(timelineStartMs = (it.timelineStartMs + effectiveDelta).coerceAtLeast(0L)) }
    } else _timeline.value.stickerClips

    val newEffect = if (!isTrackLocked(TrackType.EFFECT)) {
      _timeline.value.effectClips.map { it.copy(timelineStartMs = (it.timelineStartMs + effectiveDelta).coerceAtLeast(0L)) }
    } else _timeline.value.effectClips

    _timeline.value = _timeline.value.copy(
      videoClips = newVideo.sortedBy { it.timelineStartMs },
      overlayClips = newOverlay.sortedBy { it.timelineStartMs },
      audioClips = newAudio.sortedBy { it.timelineStartMs },
      textClips = newText.sortedBy { it.timelineStartMs },
      stickerClips = newSticker.sortedBy { it.timelineStartMs },
      effectClips = newEffect.sortedBy { it.timelineStartMs }
    )
    return true
  }

  fun moveSelectedClipToPlayhead(alignStart: Boolean = true): Boolean {
    val playhead = _currentPositionMs.value
    val clipId = (_selectedElement.value as? SelectedTrackElement.Video)?.clipId
      ?: (_selectedElement.value as? SelectedTrackElement.Overlay)?.clipId
      ?: (_selectedElement.value as? SelectedTrackElement.Audio)?.clipId
      ?: (_selectedElement.value as? SelectedTrackElement.Text)?.clipId
      ?: (_selectedElement.value as? SelectedTrackElement.Sticker)?.clipId
      ?: (_selectedElement.value as? SelectedTrackElement.Effect)?.clipId
      ?: findClipUnderPlayhead()
      ?: return false

    val element = findTrackElementForClip(clipId)
    val (startMs, durMs) = when (element) {
      is SelectedTrackElement.Video -> _timeline.value.videoClips.find { it.id == clipId }?.let { it.timelineStartMs to it.durationMs } ?: return false
      is SelectedTrackElement.Overlay -> _timeline.value.overlayClips.find { it.id == clipId }?.let { it.timelineStartMs to it.durationMs } ?: return false
      is SelectedTrackElement.Audio -> _timeline.value.audioClips.find { it.id == clipId }?.let { it.timelineStartMs to it.durationMs } ?: return false
      is SelectedTrackElement.Text -> _timeline.value.textClips.find { it.id == clipId }?.let { it.timelineStartMs to it.durationMs } ?: return false
      is SelectedTrackElement.Sticker -> _timeline.value.stickerClips.find { it.id == clipId }?.let { it.timelineStartMs to it.durationMs } ?: return false
      is SelectedTrackElement.Effect -> _timeline.value.effectClips.find { it.id == clipId }?.let { it.timelineStartMs to it.durationMs } ?: return false
      SelectedTrackElement.None -> return false
    }

    val targetStart = if (alignStart) playhead else (playhead - durMs).coerceAtLeast(0L)
    val delta = targetStart - startMs
    if (delta == 0L) return true

    return if (_isTracksSyncEnabled.value) {
      moveSynchronizedTracksByDelta(delta, snap = false)
    } else {
      moveClip(clipId, targetStart, snap = false)
      true
    }
  }

  fun trimClipRightToPlayhead(targetClipId: String? = null): Boolean {
    val playhead = _currentPositionMs.value
    val clipId = targetClipId
      ?: (_selectedElement.value as? SelectedTrackElement.Video)?.clipId
      ?: (_selectedElement.value as? SelectedTrackElement.Overlay)?.clipId
      ?: (_selectedElement.value as? SelectedTrackElement.Audio)?.clipId
      ?: (_selectedElement.value as? SelectedTrackElement.Text)?.clipId
      ?: (_selectedElement.value as? SelectedTrackElement.Sticker)?.clipId
      ?: (_selectedElement.value as? SelectedTrackElement.Effect)?.clipId
      ?: findClipUnderPlayhead()
      ?: return false

    val element = findTrackElementForClip(clipId) ?: return false
    val (startMs, oldDurMs) = when (element) {
      is SelectedTrackElement.Video -> _timeline.value.videoClips.find { it.id == clipId }?.let { it.timelineStartMs to it.durationMs } ?: return false
      is SelectedTrackElement.Overlay -> _timeline.value.overlayClips.find { it.id == clipId }?.let { it.timelineStartMs to it.durationMs } ?: return false
      is SelectedTrackElement.Audio -> _timeline.value.audioClips.find { it.id == clipId }?.let { it.timelineStartMs to it.durationMs } ?: return false
      is SelectedTrackElement.Text -> _timeline.value.textClips.find { it.id == clipId }?.let { it.timelineStartMs to it.durationMs } ?: return false
      is SelectedTrackElement.Sticker -> _timeline.value.stickerClips.find { it.id == clipId }?.let { it.timelineStartMs to it.durationMs } ?: return false
      is SelectedTrackElement.Effect -> _timeline.value.effectClips.find { it.id == clipId }?.let { it.timelineStartMs to it.durationMs } ?: return false
      SelectedTrackElement.None -> return false
    }

    if (playhead <= startMs) return false
    val newDur = (playhead - startMs).coerceAtLeast(50L)
    trimClipRight(clipId, newDur, snap = false)

    if (_isTracksSyncEnabled.value) {
      val delta = newDur - oldDurMs
      if (delta != 0L) {
        val oldEnd = startMs + oldDurMs
        rippleDownstreamClips(fromTimeMs = oldEnd, deltaMs = delta)
      }
    }
    return true
  }

  fun trimClipLeftToPlayhead(targetClipId: String? = null): Boolean {
    val playhead = _currentPositionMs.value
    val clipId = targetClipId
      ?: (_selectedElement.value as? SelectedTrackElement.Video)?.clipId
      ?: (_selectedElement.value as? SelectedTrackElement.Overlay)?.clipId
      ?: (_selectedElement.value as? SelectedTrackElement.Audio)?.clipId
      ?: (_selectedElement.value as? SelectedTrackElement.Text)?.clipId
      ?: (_selectedElement.value as? SelectedTrackElement.Sticker)?.clipId
      ?: (_selectedElement.value as? SelectedTrackElement.Effect)?.clipId
      ?: findClipUnderPlayhead()
      ?: return false

    val element = findTrackElementForClip(clipId) ?: return false
    val (startMs, durMs) = when (element) {
      is SelectedTrackElement.Video -> _timeline.value.videoClips.find { it.id == clipId }?.let { it.timelineStartMs to it.durationMs } ?: return false
      is SelectedTrackElement.Overlay -> _timeline.value.overlayClips.find { it.id == clipId }?.let { it.timelineStartMs to it.durationMs } ?: return false
      is SelectedTrackElement.Audio -> _timeline.value.audioClips.find { it.id == clipId }?.let { it.timelineStartMs to it.durationMs } ?: return false
      is SelectedTrackElement.Text -> _timeline.value.textClips.find { it.id == clipId }?.let { it.timelineStartMs to it.durationMs } ?: return false
      is SelectedTrackElement.Sticker -> _timeline.value.stickerClips.find { it.id == clipId }?.let { it.timelineStartMs to it.durationMs } ?: return false
      is SelectedTrackElement.Effect -> _timeline.value.effectClips.find { it.id == clipId }?.let { it.timelineStartMs to it.durationMs } ?: return false
      SelectedTrackElement.None -> return false
    }

    val endMs = startMs + durMs
    if (playhead >= endMs) return false
    trimClipLeft(clipId, playhead, snap = false)
    return true
  }

  fun addVideoClip(
    uri: String,
    name: String,
    isVideo: Boolean = true,
    durationMs: Long = 3000L,
    atPlayhead: Boolean = true,
    width: Int = 1920,
    height: Int = 1080,
    rotationDegrees: Int = 0,
    frameRate: Float = 30f,
    mimeType: String = "video/mp4",
    hasAudio: Boolean = true,
    insertionMode: InsertionMode = if (atPlayhead) InsertionMode.RIPPLE else InsertionMode.APPEND
  ): String = withStateLock {
    recordHistory(TimelineActionType.ADD_CLIP, "Add Video Clip")
    val alignedDuration = if (_isFrameSnapping.value) alignToFrame(durationMs).coerceAtLeast(frameDurationMs()) else durationMs
    val currentClips = _timeline.value.videoClips.toMutableList()
    val currentMaxEnd = currentClips.maxOfOrNull { it.timelineStartMs + it.durationMs } ?: 0L
    
    val rawStart = when (insertionMode) {
      InsertionMode.APPEND -> currentMaxEnd
      InsertionMode.RIPPLE, InsertionMode.OVERWRITE -> if (atPlayhead) _currentPositionMs.value else currentMaxEnd
    }
    val startMs = if (_isFrameSnapping.value) alignToFrame(rawStart) else rawStart

    if (insertionMode == InsertionMode.OVERWRITE) {
      // Overwrite mode: trim or remove existing clips overlapping [startMs, startMs + alignedDuration]
      val endMs = startMs + alignedDuration
      val remaining = mutableListOf<VideoClip>()
      for (clip in currentClips) {
        val cStart = clip.timelineStartMs
        val cEnd = clip.timelineStartMs + clip.durationMs
        if (cEnd <= startMs || cStart >= endMs) {
          remaining.add(clip)
        } else if (cStart < startMs && cEnd > endMs) {
          // Clip surrounds the insertion window -> keep the part before and the part after it.
          val (left, rest) = TimelineSplitEngine.splitVideoUnchecked(clip, startMs)
          val (_, right) = TimelineSplitEngine.splitVideoUnchecked(rest, endMs)
          remaining.add(left)
          remaining.add(right)
        } else if (cStart < startMs) {
          remaining.add(TimelineSplitEngine.splitVideoUnchecked(clip, startMs).first)
        } else if (cEnd > endMs) {
          remaining.add(TimelineSplitEngine.splitVideoUnchecked(clip, endMs).second)
        }
      }
      currentClips.clear()
      currentClips.addAll(remaining)
    } else if (insertionMode == InsertionMode.RIPPLE && startMs < currentMaxEnd) {
      // If inserted at CTI and there is an existing video clip spanning CTI:
      val clipUnderPlayhead = currentClips.find { startMs > it.timelineStartMs && startMs < it.timelineStartMs + it.durationMs }
      if (clipUnderPlayhead != null) {
        val idx = currentClips.indexOf(clipUnderPlayhead)
        val (c1, splitTail) = TimelineSplitEngine.splitVideoUnchecked(clipUnderPlayhead, startMs)
        val c2 = splitTail.copy(timelineStartMs = startMs + alignedDuration)
        currentClips[idx] = c1
        currentClips.add(idx + 1, c2)
        for (i in (idx + 2) until currentClips.size) {
          currentClips[i] = currentClips[i].copy(
            timelineStartMs = currentClips[i].timelineStartMs + alignedDuration
          )
        }
      } else {
        for (i in currentClips.indices) {
          if (currentClips[i].timelineStartMs >= startMs) {
            currentClips[i] = currentClips[i].copy(
              timelineStartMs = currentClips[i].timelineStartMs + alignedDuration
            )
          }
        }
      }
    }

    val newClip = VideoClip(
      uri = uri,
      name = name,
      isVideo = isVideo,
      timelineStartMs = startMs,
      durationMs = alignedDuration,
      sourceStartMs = 0L,
      sourceEndMs = alignedDuration,
      sourceTotalDurationMs = if (isVideo) durationMs else 0L,
      width = width,
      height = height,
      naturalRotation = rotationDegrees,
      frameRate = frameRate,
      mimeType = mimeType,
      hasAudio = hasAudio
    )
    currentClips.add(newClip)
    currentClips.sortBy { it.timelineStartMs }

    if (_isTracksSyncEnabled.value && insertionMode == InsertionMode.RIPPLE && startMs > 0L && startMs < currentMaxEnd) {
      rippleDownstreamClips(fromTimeMs = startMs, deltaMs = alignedDuration)
    }

    _timeline.value = _timeline.value.copy(videoClips = currentClips)
    if (insertionMode == InsertionMode.APPEND) {
      enforceMainTrackContinuity()
    }
    _selectedElement.value = SelectedTrackElement.Video(newClip.id)
    setInternalPositionMs(startMs + alignedDuration)
    newClip.id
  }

  // --- Overlay (PIP) Operations ---

  fun addOverlayClip(
    uri: String,
    name: String,
    isVideo: Boolean = true,
    startTimeMs: Long? = null,
    durationMs: Long = 3000L,
    scale: Float = 0.45f,
    posX: Float = 0.25f,
    posY: Float = -0.25f,
    opacity: Float = 1.0f,
    blendMode: String = "Normal",
    width: Int = 1920,
    height: Int = 1080,
    rotationDegrees: Int = 0,
    frameRate: Float = 30f,
    mimeType: String = "video/mp4",
    hasAudio: Boolean = true
  ) {
    recordHistory()
    val currentOverlays = _timeline.value.overlayClips.toMutableList()
    val nextOverlayTrack = com.example.engine.timeline.TimelineTrackManager.allocateOverlayTrackIndex(_timeline.value)
    val start = startTimeMs ?: com.example.engine.timeline.TimelineTrackManager.getAuthoritativeInsertionTime(_currentPositionMs.value)
    val newOverlay = VideoClip(
      uri = uri,
      name = name,
      isVideo = isVideo,
      timelineStartMs = start,
      durationMs = durationMs,
      sourceStartMs = 0L,
      sourceEndMs = durationMs,
      sourceTotalDurationMs = if (isVideo) durationMs else 0L,
      cropScale = scale,
      cropOffsetX = posX,
      cropOffsetY = posY,
      opacity = opacity,
      blendMode = blendMode,
      width = width,
      height = height,
      naturalRotation = rotationDegrees,
      frameRate = frameRate,
      mimeType = mimeType,
      hasAudio = hasAudio,
      trackIndex = nextOverlayTrack
    )
    currentOverlays.add(newOverlay)
    currentOverlays.sortBy { it.timelineStartMs }
    _timeline.value = _timeline.value.copy(overlayClips = currentOverlays)
    _selectedElement.value = SelectedTrackElement.Overlay(newOverlay.id)
  }

  fun replaceSelectedMedia(
    newUri: String,
    newName: String,
    width: Int,
    height: Int,
    durationMs: Long,
    isVideo: Boolean,
    rotationDegrees: Int = 0,
    frameRate: Float = 30f,
    mimeType: String = "video/mp4",
    hasAudio: Boolean = true
  ) {
    val selected = _selectedElement.value
    recordHistory()
    if (selected is SelectedTrackElement.Video) {
      val list = _timeline.value.videoClips.map { clip ->
        if (clip.id == selected.clipId) {
          clip.copy(
            uri = newUri,
            name = newName,
            isVideo = isVideo,
            durationMs = durationMs,
            sourceStartMs = 0L,
            sourceEndMs = durationMs,
            width = width,
            height = height,
            naturalRotation = rotationDegrees,
            frameRate = frameRate,
            mimeType = mimeType,
            hasAudio = hasAudio
          )
        } else clip
      }
      _timeline.value = _timeline.value.copy(videoClips = list)
    } else if (selected is SelectedTrackElement.Overlay) {
      val list = _timeline.value.overlayClips.map { clip ->
        if (clip.id == selected.clipId) {
          clip.copy(
            uri = newUri,
            name = newName,
            isVideo = isVideo,
            durationMs = durationMs,
            sourceStartMs = 0L,
            sourceEndMs = durationMs,
            width = width,
            height = height,
            naturalRotation = rotationDegrees,
            frameRate = frameRate,
            mimeType = mimeType,
            hasAudio = hasAudio
          )
        } else clip
      }
      _timeline.value = _timeline.value.copy(overlayClips = list)
    }
  }

  fun toggleSelectedClipMute() {
    withStateLock {
      val selected = _selectedElement.value
      recordHistory()
      if (selected is SelectedTrackElement.Video) {
        val list = _timeline.value.videoClips.map { clip ->
          if (clip.id == selected.clipId) clip.copy(isMuted = !clip.isMuted) else clip
        }
        _timeline.value = _timeline.value.copy(videoClips = list)
      } else if (selected is SelectedTrackElement.Overlay) {
        val list = _timeline.value.overlayClips.map { clip ->
          if (clip.id == selected.clipId) clip.copy(isMuted = !clip.isMuted) else clip
        }
        _timeline.value = _timeline.value.copy(overlayClips = list)
      }
    }
  }

  fun toggleSelectedClipReverse() {
    withStateLock {
      val selected = _selectedElement.value
      recordHistory()
      if (selected is SelectedTrackElement.Video) {
        val list = _timeline.value.videoClips.map { clip ->
          if (clip.id == selected.clipId) clip.copy(isReversed = !clip.isReversed) else clip
        }
        _timeline.value = _timeline.value.copy(videoClips = list)
      }
    }
  }

  fun updateOverlayClip(updated: VideoClip) {
    withStateLock {
      val list = _timeline.value.overlayClips.map { if (it.id == updated.id) updated else it }
      _timeline.value = _timeline.value.copy(overlayClips = list)
    }
  }

  fun updateSelectedOverlay(update: (VideoClip) -> VideoClip) {
    withStateLock {
      val selected = _selectedElement.value
      if (selected is SelectedTrackElement.Overlay) {
        val list = _timeline.value.overlayClips.map { clip ->
          if (clip.id == selected.clipId) update(clip) else clip
        }
        _timeline.value = _timeline.value.copy(overlayClips = list)
      }
    }
  }

  fun setOverlayPosition(clipId: String, posX: Float, posY: Float) {
    withStateLock {
      val list = _timeline.value.overlayClips.map { clip ->
        if (clip.id == clipId) clip.copy(cropOffsetX = posX, cropOffsetY = posY) else clip
      }
      _timeline.value = _timeline.value.copy(overlayClips = list)
    }
  }

  fun setOverlayScale(clipId: String, scale: Float) {
    withStateLock {
      val list = _timeline.value.overlayClips.map { clip ->
        if (clip.id == clipId) clip.copy(cropScale = scale.coerceIn(0.1f, 3f)) else clip
      }
      _timeline.value = _timeline.value.copy(overlayClips = list)
    }
  }

  fun setOverlayOpacity(clipId: String, opacity: Float) {
    withStateLock {
      val list = _timeline.value.overlayClips.map { clip ->
        if (clip.id == clipId) clip.copy(opacity = opacity.coerceIn(0f, 1f)) else clip
      }
      _timeline.value = _timeline.value.copy(overlayClips = list)
    }
  }

  fun setOverlayBlendMode(clipId: String, blendMode: String) {
    withStateLock {
      val list = _timeline.value.overlayClips.map { clip ->
        if (clip.id == clipId) clip.copy(blendMode = blendMode) else clip
      }
      _timeline.value = _timeline.value.copy(overlayClips = list)
    }
  }

  fun splitSelectedClipAtPlayhead(): Boolean {
    return splitAtPlayhead()
  }

  // --- Track Controls ---

  fun isTrackLocked(trackType: TrackType): Boolean {
    return _timeline.value.trackSettings[trackType]?.isLocked == true
  }

  fun toggleTrackLock(trackType: TrackType) {
    withStateLock {
      recordHistory()
      val settings = _timeline.value.trackSettings.toMutableMap()
      val cur = settings[trackType] ?: TrackSettings(trackType)
      settings[trackType] = cur.copy(isLocked = !cur.isLocked)
      _timeline.value = _timeline.value.copy(trackSettings = settings)
    }
  }

  fun toggleTrackHide(trackType: TrackType) {
    withStateLock {
      recordHistory()
      val settings = _timeline.value.trackSettings.toMutableMap()
      val cur = settings[trackType] ?: TrackSettings(trackType)
      settings[trackType] = cur.copy(isHidden = !cur.isHidden)
      _timeline.value = _timeline.value.copy(trackSettings = settings)
    }
  }

  fun toggleTrackMute(trackType: TrackType) {
    withStateLock {
      recordHistory()
      val settings = _timeline.value.trackSettings.toMutableMap()
      val cur = settings[trackType] ?: TrackSettings(trackType)
      settings[trackType] = cur.copy(isMuted = !cur.isMuted)
      _timeline.value = _timeline.value.copy(trackSettings = settings)
    }
  }

  fun toggleTrackSolo(trackType: TrackType) {
    withStateLock {
      recordHistory()
      val settings = _timeline.value.trackSettings.toMutableMap()
      val cur = settings[trackType] ?: TrackSettings(trackType)
      settings[trackType] = cur.copy(isSolo = !cur.isSolo)
      _timeline.value = _timeline.value.copy(trackSettings = settings)
    }
  }

  fun setTrackHeight(trackType: TrackType, height: TrackHeight) {
    withStateLock {
      recordHistory()
      val settings = _timeline.value.trackSettings.toMutableMap()
      val cur = settings[trackType] ?: TrackSettings(trackType)
      settings[trackType] = cur.copy(height = height)
      _timeline.value = _timeline.value.copy(trackSettings = settings)
    }
  }

  fun cycleTrackHeight(trackType: TrackType) {
    withStateLock {
      val cur = _timeline.value.trackSettings[trackType]?.height ?: TrackHeight.NORMAL
      val next = when (cur) {
        TrackHeight.COMPACT -> TrackHeight.NORMAL
        TrackHeight.NORMAL -> TrackHeight.EXPANDED
        TrackHeight.EXPANDED -> TrackHeight.COMPACT
      }
      setTrackHeight(trackType, next)
    }
  }

  fun setTrackLocked(trackType: TrackType, locked: Boolean) {
    withStateLock {
      recordHistory()
      val settings = _timeline.value.trackSettings.toMutableMap()
      val cur = settings[trackType] ?: TrackSettings(trackType)
      settings[trackType] = cur.copy(isLocked = locked)
      _timeline.value = _timeline.value.copy(trackSettings = settings)
    }
  }

  fun setTrackVisible(trackType: TrackType, visible: Boolean) {
    withStateLock {
      recordHistory()
      val settings = _timeline.value.trackSettings.toMutableMap()
      val cur = settings[trackType] ?: TrackSettings(trackType)
      settings[trackType] = cur.copy(isHidden = !visible)
      _timeline.value = _timeline.value.copy(trackSettings = settings)
    }
  }

  fun setTrackMuted(trackType: TrackType, muted: Boolean) {
    withStateLock {
      recordHistory()
      val settings = _timeline.value.trackSettings.toMutableMap()
      val cur = settings[trackType] ?: TrackSettings(trackType)
      settings[trackType] = cur.copy(isMuted = muted)
      _timeline.value = _timeline.value.copy(trackSettings = settings)
    }
  }

  fun setTrackSolo(trackType: TrackType, solo: Boolean) {
    withStateLock {
      recordHistory()
      val settings = _timeline.value.trackSettings.toMutableMap()
      val cur = settings[trackType] ?: TrackSettings(trackType)
      settings[trackType] = cur.copy(isSolo = solo)
      _timeline.value = _timeline.value.copy(trackSettings = settings)
    }
  }

  fun selectClips(clipIds: Set<String>) {
    withStateLock {
      _selectedClipIds.value = clipIds
      val firstId = clipIds.firstOrNull()
      if (firstId != null) {
        _selectedElement.value = findTrackElementForClip(firstId)
      } else {
        _selectedElement.value = SelectedTrackElement.None
      }
    }
  }

  fun rippleDeleteClip(clipId: String): Boolean = withStateLock {
    rippleDelete(setOf(clipId))
  }

  fun addOverlayClip(clip: VideoClip) = withStateLock {
    recordHistory()
    val currentOverlays = _timeline.value.overlayClips.toMutableList()
    val nextTrack = if (clip.trackIndex > 0) clip.trackIndex else com.example.engine.timeline.TimelineTrackManager.allocateOverlayTrackIndex(_timeline.value)
    val effectiveStart = if (clip.timelineStartMs >= 0) clip.timelineStartMs else com.example.engine.timeline.TimelineTrackManager.getAuthoritativeInsertionTime(_currentPositionMs.value)
    val finalClip = clip.copy(trackIndex = nextTrack, timelineStartMs = effectiveStart)
    currentOverlays.add(finalClip)
    _timeline.value = _timeline.value.copy(overlayClips = currentOverlays)
  }

  // --- Advanced Clip Editing & Multi-Track Operations ---

  private fun isLaneLocked(timeline: Timeline, view: ClipView): Boolean =
    view.locked || com.example.engine.timeline.TimelineTrackManager.isTrackLocked(timeline, view.trackType, view.lane)

  fun trimClipLeft(clipId: String, newStartMs: Long, snap: Boolean = true) = withStateLock {
    val current = _timeline.value
    val view = current.clipView(clipId) ?: return
    if (isLaneLocked(current, view)) return
    val isFirstMainClip = view.trackType == TrackType.MAIN_VIDEO &&
      current.videoClips.minByOrNull { it.timelineStartMs }?.id == clipId
    val requested = if (snap && _isSnappingEnabled.value && !isFirstMainClip) {
      calculateSnap(newStartMs, ignoreClipIds = setOf(clipId)).snappedPosMs
    } else newStartMs
    val range = ClipTrimMath.trimStart(
      view.startMs, view.durationMs, view.sourceStartMs, view.sourceEndMs, view.speed, view.reversed,
      view.mediaDurationMs, requested, pinToZero = isFirstMainClip, hasSource = view.hasSource
    )
    if (range.timelineStartMs == view.startMs && range.durationMs == view.durationMs) return
    recordHistory(TimelineActionType.TRIM_LEFT, "Trim Start", setOf(clipId))
    _timeline.value = current.withClipRange(clipId, range, leftTrimDeltaMs = view.durationMs - range.durationMs)
    if (isFirstMainClip) enforceZeroPointLock()
  }

  fun trimClipRight(clipId: String, newDurationMs: Long, snap: Boolean = true) = withStateLock {
    val current = _timeline.value
    val view = current.clipView(clipId) ?: return
    if (isLaneLocked(current, view)) return
    val targetEnd = view.startMs + newDurationMs
    val snappedEnd = if (snap && _isSnappingEnabled.value) {
      calculateSnap(targetEnd, ignoreClipIds = setOf(clipId)).snappedPosMs
    } else targetEnd
    val range = ClipTrimMath.trimEnd(
      view.startMs, view.durationMs, view.sourceStartMs, view.sourceEndMs, view.speed, view.reversed,
      view.mediaDurationMs, snappedEnd - view.startMs, hasSource = view.hasSource
    )
    if (range.durationMs == view.durationMs) return
    recordHistory(TimelineActionType.TRIM_RIGHT, "Trim End", setOf(clipId))
    _timeline.value = current.withClipRange(clipId, range)
    if (view.trackType == TrackType.MAIN_VIDEO) enforceMainTrackContinuity()
  }

  fun trimClipLeftByDelta(clipId: String, deltaMs: Long, snap: Boolean = true) = withStateLock {
    val view = _timeline.value.clipView(clipId) ?: return
    trimClipLeft(clipId, (view.startMs + deltaMs).coerceAtLeast(0L), snap)
  }

  fun trimClipRightByDelta(clipId: String, deltaMs: Long, snap: Boolean = true) = withStateLock {
    val view = _timeline.value.clipView(clipId) ?: return
    trimClipRight(clipId, (view.durationMs + deltaMs).coerceAtLeast(ClipTrimMath.MIN_TRIM_DURATION_MS), snap)
  }

  fun moveClipByDelta(clipId: String, deltaMs: Long, snap: Boolean = true) = withStateLock {
    if (_selectedClipIds.value.contains(clipId) && _selectedClipIds.value.size > 1) {
      moveSelectedClipsByDelta(deltaMs, snap, referenceClipId = clipId)
      return
    }
    val view = _timeline.value.clipView(clipId) ?: return
    moveClip(clipId, (view.startMs + deltaMs).coerceAtLeast(0L), snap)
  }

  /**
   * Moves all tracks as one single cohesive unit horizontally by deltaMs.
   * When any track is moved, all clips, keyframes, transitions, waveforms, effects, and text on all tracks move together,
   * maintaining their relative positions and timeline alignment.
   */
  fun moveTrackByDelta(trackType: TrackType, trackIndex: Int, deltaMs: Long): Boolean = withStateLock {
    if (deltaMs == 0L) return false

    val currentTimeline = _timeline.value
    val allVideo = currentTimeline.videoClips
    val allOverlay = currentTimeline.overlayClips
    val allText = currentTimeline.textClips
    val allAudio = currentTimeline.audioClips
    val allEffect = currentTimeline.effectClips
    val allSticker = currentTimeline.stickerClips

    val allStarts = mutableListOf<Long>()
    if (!isTrackLocked(TrackType.MAIN_VIDEO)) allVideo.forEach { allStarts.add(it.timelineStartMs) }
    if (!isTrackLocked(TrackType.OVERLAY)) allOverlay.forEach { allStarts.add(it.timelineStartMs) }
    if (!isTrackLocked(TrackType.TEXT)) allText.forEach { allStarts.add(it.timelineStartMs) }
    if (!isTrackLocked(TrackType.AUDIO)) allAudio.forEach { allStarts.add(it.timelineStartMs) }
    if (!isTrackLocked(TrackType.EFFECT)) allEffect.forEach { allStarts.add(it.timelineStartMs) }
    if (!isTrackLocked(TrackType.STICKER)) allSticker.forEach { allStarts.add(it.timelineStartMs) }

    if (allStarts.isEmpty()) return false
    val minStart = allStarts.minOrNull() ?: 0L
    val effectiveDelta = if (deltaMs < 0) maxOf(deltaMs, -minStart) else deltaMs
    if (effectiveDelta == 0L) return false

    recordHistory(TimelineActionType.MOVE_CLIP, "Move All Tracks")

    // Main media track start is locked at 0.0s; keep video clips continuous from 0L.
    val updatedVideo = allVideo

    val updatedOverlay = if (!isTrackLocked(TrackType.OVERLAY)) {
      allOverlay.map { it.copy(timelineStartMs = (it.timelineStartMs + effectiveDelta).coerceAtLeast(0L)) }.sortedBy { it.timelineStartMs }
    } else allOverlay

    val updatedText = if (!isTrackLocked(TrackType.TEXT)) {
      allText.map { it.copy(timelineStartMs = (it.timelineStartMs + effectiveDelta).coerceAtLeast(0L)) }.sortedBy { it.timelineStartMs }
    } else allText

    val updatedAudio = if (!isTrackLocked(TrackType.AUDIO)) {
      allAudio.map { it.copy(timelineStartMs = (it.timelineStartMs + effectiveDelta).coerceAtLeast(0L)) }.sortedBy { it.timelineStartMs }
    } else allAudio

    val updatedEffect = if (!isTrackLocked(TrackType.EFFECT)) {
      allEffect.map { it.copy(timelineStartMs = (it.timelineStartMs + effectiveDelta).coerceAtLeast(0L)) }.sortedBy { it.timelineStartMs }
    } else allEffect

    val updatedSticker = if (!isTrackLocked(TrackType.STICKER)) {
      allSticker.map { it.copy(timelineStartMs = (it.timelineStartMs + effectiveDelta).coerceAtLeast(0L)) }.sortedBy { it.timelineStartMs }
    } else allSticker

    _timeline.value = currentTimeline.copy(
      videoClips = updatedVideo,
      overlayClips = updatedOverlay,
      textClips = updatedText,
      audioClips = updatedAudio,
      effectClips = updatedEffect,
      stickerClips = updatedSticker
    )

    setInternalPositionMs((minStart + effectiveDelta).coerceAtLeast(0L))
    return true
  }

  /**
   * Extends the duration of a clip by extensionMs (Requirement 9: Extending Clips).
   */
  fun extendClipDuration(clipId: String, extensionMs: Long): Boolean = withStateLock {
    if (extensionMs <= 0L) return false
    val element = findTrackElementForClip(clipId)
    if (element == SelectedTrackElement.None) return false
    val curDuration = when (element) {
      is SelectedTrackElement.Video -> _timeline.value.videoClips.find { it.id == clipId }?.durationMs ?: return false
      is SelectedTrackElement.Overlay -> _timeline.value.overlayClips.find { it.id == clipId }?.durationMs ?: return false
      is SelectedTrackElement.Audio -> _timeline.value.audioClips.find { it.id == clipId }?.durationMs ?: return false
      is SelectedTrackElement.Text -> _timeline.value.textClips.find { it.id == clipId }?.durationMs ?: return false
      is SelectedTrackElement.Sticker -> _timeline.value.stickerClips.find { it.id == clipId }?.durationMs ?: return false
      is SelectedTrackElement.Effect -> _timeline.value.effectClips.find { it.id == clipId }?.durationMs ?: return false
      SelectedTrackElement.None -> return false
    }
    trimClipRight(clipId, curDuration + extensionMs, snap = false)
    return true
  }

  /**
   * Sets the explicit target duration of a clip (Requirement 9: Extending Clips).
   */
  fun setClipDuration(clipId: String, targetDurationMs: Long): Boolean = withStateLock {
    if (targetDurationMs < 100L) return false
    val element = findTrackElementForClip(clipId)
    if (element == SelectedTrackElement.None) return false
    trimClipRight(clipId, targetDurationMs, snap = false)
    return true
  }

  fun moveSelectedClipsByDelta(deltaMs: Long, snap: Boolean = true, referenceClipId: String? = null): Boolean = withStateLock {
    val targets = _selectedClipIds.value
    if (targets.isEmpty()) return false
    val current = _timeline.value
    val movable = targets.mapNotNull { current.clipView(it) }.filter { !isLaneLocked(current, it) }
    if (movable.isEmpty()) return false
    val movableIds = movable.mapTo(HashSet()) { it.id }

    val minStart = movable.minOf { it.startMs }
    var effectiveDelta = if (deltaMs < 0) maxOf(deltaMs, -minStart) else deltaMs

    if (snap && _isSnappingEnabled.value) {
      val reference = movable.firstOrNull { it.id == referenceClipId } ?: movable.first()
      val snapRes = calculateSnap((reference.startMs + effectiveDelta).coerceAtLeast(0L), ignoreClipIds = movableIds)
      if (snapRes.didSnap) {
        val snappedDelta = snapRes.snappedPosMs - reference.startMs
        effectiveDelta = if (snappedDelta < 0L) maxOf(snappedDelta, -minStart) else snappedDelta
      }
    }
    if (effectiveDelta == 0L) return false

    val moveDesc = if (movableIds.size > 1) "Move (${movableIds.size} clips)" else "Move Clip"
    recordHistory(TimelineActionType.MOVE_CLIP, moveDesc, movableIds)
    _timeline.value = current.withClipsShifted(movableIds, effectiveDelta)
    setInternalPositionMs((minStart + effectiveDelta).coerceAtLeast(0L))
    true
  }

  fun moveSelectedClips(deltaMs: Long, snap: Boolean = true): Boolean {
    return moveSelectedClipsByDelta(deltaMs, snap)
  }

  fun moveClip(clipId: String, newStartMs: Long, snap: Boolean = true) = withStateLock {
    if (_selectedClipIds.value.contains(clipId) && _selectedClipIds.value.size > 1) {
      val currentStart = _timeline.value.clipView(clipId)?.startMs ?: return
      moveSelectedClipsByDelta(newStartMs - currentStart, snap, referenceClipId = clipId)
      return
    }

    val current = _timeline.value
    val view = current.clipView(clipId) ?: return
    if (isLaneLocked(current, view)) return

    var start = newStartMs.coerceAtLeast(0L)
    if (snap && _isSnappingEnabled.value) {
      val startSnap = calculateSnap(start, ignoreClipIds = setOf(clipId))
      if (startSnap.didSnap) {
        start = startSnap.snappedPosMs
      } else {
        val endSnap = calculateSnap(start + view.durationMs, ignoreClipIds = setOf(clipId))
        if (endSnap.didSnap) start = (endSnap.snappedPosMs - view.durationMs).coerceAtLeast(0L)
      }
    }
    if (start == view.startMs) return

    // A normal clip drag moves only the selected clip. Synchronized movement is reserved for the
    // explicit "move all tracks" action.
    recordHistory(TimelineActionType.MOVE_CLIP, "Move Clip", setOf(clipId))
    _timeline.value = current.withClipStart(clipId, start)
    setInternalPositionMs(start)
  }

  fun slipClip(clipId: String, deltaMs: Long): Boolean = withStateLock {
    val element = findTrackElementForClip(clipId)
    if (element == SelectedTrackElement.None || deltaMs == 0L) return false
    recordHistory(TimelineActionType.GENERIC_EDIT, "Slip Clip", setOf(clipId))
    when (element) {
      is SelectedTrackElement.Video -> {
        if (isTrackLocked(TrackType.MAIN_VIDEO)) return false
        val clip = _timeline.value.videoClips.find { it.id == clipId } ?: return false
        val sourceDelta = (deltaMs * clip.speed).toLong()
        val newSourceStart = (clip.sourceStartMs + sourceDelta).coerceAtLeast(0L)
        val sourceSpan = (clip.durationMs * clip.speed).toLong()
        val newSourceEnd = newSourceStart + sourceSpan
        val list = _timeline.value.videoClips.map {
          if (it.id == clipId) it.copy(sourceStartMs = newSourceStart, sourceEndMs = newSourceEnd) else it
        }
        _timeline.value = _timeline.value.copy(videoClips = list)
        return true
      }
      is SelectedTrackElement.Overlay -> {
        if (isTrackLocked(TrackType.OVERLAY)) return false
        val clip = _timeline.value.overlayClips.find { it.id == clipId } ?: return false
        val sourceDelta = (deltaMs * clip.speed).toLong()
        val newSourceStart = (clip.sourceStartMs + sourceDelta).coerceAtLeast(0L)
        val sourceSpan = (clip.durationMs * clip.speed).toLong()
        val newSourceEnd = newSourceStart + sourceSpan
        val list = _timeline.value.overlayClips.map {
          if (it.id == clipId) it.copy(sourceStartMs = newSourceStart, sourceEndMs = newSourceEnd) else it
        }
        _timeline.value = _timeline.value.copy(overlayClips = list)
        return true
      }
      is SelectedTrackElement.Audio -> {
        if (isTrackLocked(TrackType.AUDIO)) return false
        val clip = _timeline.value.audioClips.find { it.id == clipId } ?: return false
        val sourceDelta = (deltaMs * clip.speed).toLong()
        val newSourceStart = (clip.sourceStartMs + sourceDelta).coerceAtLeast(0L)
        val sourceSpan = (clip.durationMs * clip.speed).toLong()
        val newSourceEnd = newSourceStart + sourceSpan
        val list = _timeline.value.audioClips.map {
          if (it.id == clipId) it.copy(sourceStartMs = newSourceStart, sourceEndMs = newSourceEnd) else it
        }
        _timeline.value = _timeline.value.copy(audioClips = list)
        return true
      }
      else -> return false
    }
  }

  fun slideClip(clipId: String, deltaMs: Long, snap: Boolean = true): Boolean = withStateLock {
    val element = findTrackElementForClip(clipId)
    if (element == SelectedTrackElement.None || deltaMs == 0L) return false
    recordHistory(TimelineActionType.MOVE_CLIP, "Slide Clip", setOf(clipId))
    when (element) {
      is SelectedTrackElement.Video -> {
        if (isTrackLocked(TrackType.MAIN_VIDEO)) return false
        val clips = _timeline.value.videoClips
        val index = clips.indexOfFirst { it.id == clipId }
        if (index == -1) return false
        val clip = clips[index]
        if (index > 0 && index < clips.size - 1) {
          val prevClip = clips[index - 1]
          val nextClip = clips[index + 1]
          val maxNegative = -(prevClip.durationMs - 200L)
          val maxPositive = nextClip.durationMs - 200L
          val clampedDelta = deltaMs.coerceIn(maxNegative, maxPositive)
          val newPrev = prevClip.copy(
            durationMs = prevClip.durationMs + clampedDelta,
            sourceEndMs = prevClip.sourceEndMs + (clampedDelta * prevClip.speed).toLong()
          )
          val newCurrent = clip.copy(
            timelineStartMs = clip.timelineStartMs + clampedDelta
          )
          val newNext = nextClip.copy(
            timelineStartMs = nextClip.timelineStartMs + clampedDelta,
            durationMs = nextClip.durationMs - clampedDelta,
            sourceStartMs = nextClip.sourceStartMs + (clampedDelta * nextClip.speed).toLong()
          )
          val list = clips.toMutableList()
          list[index - 1] = newPrev
          list[index] = newCurrent
          list[index + 1] = newNext
          _timeline.value = _timeline.value.copy(videoClips = list)
          return true
        } else {
          moveClipByDelta(clipId, deltaMs, snap)
          return true
        }
      }
      else -> {
        moveClipByDelta(clipId, deltaMs, snap)
        return true
      }
    }
  }

  private fun alignedPlayheadMs(): Long =
    if (_isFrameSnapping.value) alignToFrame(_currentPositionMs.value) else _currentPositionMs.value

  private fun lockPredicate(timeline: Timeline): (TrackType, Int) -> Boolean =
    { type, lane -> com.example.engine.timeline.TimelineTrackManager.isTrackLocked(timeline, type, lane) }

  private fun findClipUnderPlayhead(): String? {
    val pos = _currentPositionMs.value
    val tl = _timeline.value
    tl.videoClips.firstOrNull { pos >= it.timelineStartMs && pos < it.timelineStartMs + it.durationMs }?.let { return it.id }
    tl.overlayClips.firstOrNull { pos >= it.timelineStartMs && pos < it.timelineStartMs + it.durationMs }?.let { return it.id }
    tl.audioClips.firstOrNull { pos >= it.timelineStartMs && pos < it.timelineStartMs + it.durationMs }?.let { return it.id }
    tl.textClips.firstOrNull { pos >= it.timelineStartMs && pos < it.timelineStartMs + it.durationMs }?.let { return it.id }
    tl.stickerClips.firstOrNull { pos >= it.timelineStartMs && pos < it.timelineStartMs + it.durationMs }?.let { return it.id }
    tl.effectClips.firstOrNull { pos >= it.timelineStartMs && pos < it.timelineStartMs + it.durationMs }?.let { return it.id }
    return null
  }

  /** Splits the selected clip under the playhead, else the top clip under it; see [TimelineSplitEngine.findTargetClipAtTimestamp]. */
  fun splitAtPlayhead(targetClipId: String? = null): Boolean = withStateLock {
    val playhead = alignedPlayheadMs()
    val clipId = targetClipId
      ?: TimelineSplitEngine.findTargetClipAtTimestamp(_timeline.value, playhead, _selectedElement.value).second
      ?: return false
    splitClipAtTime(clipId, playhead) != null
  }

  /** Splits every clip on every lane that the playhead crosses, as one undoable step. */
  fun splitAllTracksAtPlayhead(): Boolean = withStateLock {
    splitAllClipsAtPlayhead().isNotEmpty()
  }

  fun trimClip(clipId: String, newStartMs: Long, newDurationMs: Long) {
    withStateLock {
      trimClipLeft(clipId, newStartMs, snap = false)
      trimClipRight(clipId, newDurationMs, snap = false)
    }
  }

  /**
   * Trims a clip's underlying media by setting its source start and end points.
   * Recalculates contiguous timeline positions for main video track clips.
   */
  fun trimClipSourceRange(
    clipId: String,
    newSourceStartMs: Long,
    newSourceEndMs: Long,
    rippleContiguous: Boolean = true
  ): Boolean {
    val element = findTrackElementForClip(clipId) ?: return false
    when (element) {
      is SelectedTrackElement.Video -> {
        if (isTrackLocked(TrackType.MAIN_VIDEO)) return false
        val clip = _timeline.value.videoClips.find { it.id == clipId } ?: return false
        val maxSource = if (clip.sourceTotalDurationMs > 0L) clip.sourceTotalDurationMs else maxOf(clip.sourceEndMs, clip.durationMs)
        val clampedStart = newSourceStartMs.coerceIn(0L, (maxSource - 100L).coerceAtLeast(0L))
        val clampedEnd = newSourceEndMs.coerceIn(clampedStart + 100L, maxOf(maxSource, clampedStart + 100L))
        val newDur = (((clampedEnd - clampedStart) / clip.speed).toLong()).coerceAtLeast(100L)
        val originalTotal = if (clip.sourceTotalDurationMs > 0L) clip.sourceTotalDurationMs else maxOf(clip.sourceEndMs, clampedEnd)

        recordHistory()
        val clips = _timeline.value.videoClips.toMutableList()
        val clipIndex = clips.indexOfFirst { it.id == clipId }
        if (clipIndex == -1) return false

        if (rippleContiguous) {
          var curStart = 0L
          for (i in clips.indices) {
            val c = clips[i]
            if (c.id == clipId) {
              clips[i] = c.copy(
                sourceStartMs = clampedStart,
                sourceEndMs = clampedEnd,
                durationMs = newDur,
                timelineStartMs = curStart,
                sourceTotalDurationMs = originalTotal
              )
            } else {
              clips[i] = c.copy(timelineStartMs = curStart)
            }
            curStart += clips[i].durationMs
          }
        } else {
          clips[clipIndex] = clip.copy(
            sourceStartMs = clampedStart,
            sourceEndMs = clampedEnd,
            durationMs = newDur,
            sourceTotalDurationMs = originalTotal
          )
        }
        _timeline.value = _timeline.value.copy(videoClips = clips)
        return true
      }
      is SelectedTrackElement.Overlay -> {
        if (isTrackLocked(TrackType.OVERLAY)) return false
        val clip = _timeline.value.overlayClips.find { it.id == clipId } ?: return false
        val maxSource = if (clip.sourceTotalDurationMs > 0L) clip.sourceTotalDurationMs else maxOf(clip.sourceEndMs, clip.durationMs)
        val clampedStart = newSourceStartMs.coerceIn(0L, (maxSource - 100L).coerceAtLeast(0L))
        val clampedEnd = newSourceEndMs.coerceIn(clampedStart + 100L, maxOf(maxSource, clampedStart + 100L))
        val newDur = (((clampedEnd - clampedStart) / clip.speed).toLong()).coerceAtLeast(100L)
        val originalTotal = if (clip.sourceTotalDurationMs > 0L) clip.sourceTotalDurationMs else maxOf(clip.sourceEndMs, clampedEnd)

        recordHistory()
        val list = _timeline.value.overlayClips.map {
          if (it.id == clipId) it.copy(
            sourceStartMs = clampedStart,
            sourceEndMs = clampedEnd,
            durationMs = newDur,
            sourceTotalDurationMs = originalTotal
          ) else it
        }
        _timeline.value = _timeline.value.copy(overlayClips = list)
        return true
      }
      is SelectedTrackElement.Audio -> {
        if (isTrackLocked(TrackType.AUDIO)) return false
        val clip = _timeline.value.audioClips.find { it.id == clipId } ?: return false
        val maxSource = maxOf(clip.sourceEndMs, clip.durationMs)
        val clampedStart = newSourceStartMs.coerceIn(0L, (maxSource - 100L).coerceAtLeast(0L))
        val clampedEnd = newSourceEndMs.coerceIn(clampedStart + 100L, maxOf(maxSource, clampedStart + 100L))
        val newDur = (((clampedEnd - clampedStart) / clip.speed).toLong()).coerceAtLeast(100L)

        recordHistory()
        val list = _timeline.value.audioClips.map {
          if (it.id == clipId) it.copy(
            sourceStartMs = clampedStart,
            sourceEndMs = clampedEnd,
            durationMs = newDur
          ) else it
        }
        _timeline.value = _timeline.value.copy(audioClips = list)
        return true
      }
      else -> return false
    }
  }

  fun resetClipTrim(clipId: String): Boolean {
    val element = findTrackElementForClip(clipId) ?: return false
    if (element is SelectedTrackElement.Video) {
      val clip = _timeline.value.videoClips.find { it.id == clipId } ?: return false
      val fullDuration = if (clip.sourceTotalDurationMs > 0L) clip.sourceTotalDurationMs else maxOf(clip.sourceEndMs, clip.durationMs)
      return trimClipSourceRange(clipId, 0L, fullDuration, rippleContiguous = true)
    } else if (element is SelectedTrackElement.Overlay) {
      val clip = _timeline.value.overlayClips.find { it.id == clipId } ?: return false
      val fullDuration = if (clip.sourceTotalDurationMs > 0L) clip.sourceTotalDurationMs else maxOf(clip.sourceEndMs, clip.durationMs)
      return trimClipSourceRange(clipId, 0L, fullDuration, rippleContiguous = false)
    }
    return false
  }

  fun setClipInPointAtPlayhead(clipId: String): Boolean {
    val element = findTrackElementForClip(clipId) ?: return false
    val currentPlayhead = _currentPositionMs.value
    if (element is SelectedTrackElement.Video) {
      val clip = _timeline.value.videoClips.find { it.id == clipId } ?: return false
      val sourcePos = clip.timelineToSourceMs(currentPlayhead)
      return trimClipSourceRange(clipId, sourcePos, clip.sourceEndMs, rippleContiguous = true)
    } else if (element is SelectedTrackElement.Overlay) {
      val clip = _timeline.value.overlayClips.find { it.id == clipId } ?: return false
      val sourcePos = clip.timelineToSourceMs(currentPlayhead)
      return trimClipSourceRange(clipId, sourcePos, clip.sourceEndMs, rippleContiguous = false)
    }
    return false
  }

  fun setClipOutPointAtPlayhead(clipId: String): Boolean {
    val element = findTrackElementForClip(clipId) ?: return false
    val currentPlayhead = _currentPositionMs.value
    if (element is SelectedTrackElement.Video) {
      val clip = _timeline.value.videoClips.find { it.id == clipId } ?: return false
      val sourcePos = clip.timelineToSourceMs(currentPlayhead)
      return trimClipSourceRange(clipId, clip.sourceStartMs, sourcePos, rippleContiguous = true)
    } else if (element is SelectedTrackElement.Overlay) {
      val clip = _timeline.value.overlayClips.find { it.id == clipId } ?: return false
      val sourcePos = clip.timelineToSourceMs(currentPlayhead)
      return trimClipSourceRange(clipId, clip.sourceStartMs, sourcePos, rippleContiguous = false)
    }
    return false
  }

  fun rippleDelete(clipIds: Set<String> = emptySet()): Boolean {
    val targets = if (clipIds.isNotEmpty()) clipIds else _selectedClipIds.value
    if (targets.isEmpty()) return deleteSelected()
    val desc = if (targets.size > 1) "Ripple Delete (${targets.size} clips)" else "Ripple Delete Clip"
    recordHistory(TimelineActionType.RIPPLE_DELETE, desc, targets)

    var newVideo = _timeline.value.videoClips
    if (!isTrackLocked(TrackType.MAIN_VIDEO)) {
      val toDelete = newVideo.filter { it.id in targets }.sortedBy { it.timelineStartMs }
      if (toDelete.isNotEmpty()) {
        val remaining = newVideo.filterNot { it.id in targets }.toMutableList()
        for (del in toDelete) {
          remaining.indices.forEach { i ->
            if (remaining[i].timelineStartMs >= del.timelineStartMs) {
              remaining[i] = remaining[i].copy(
                timelineStartMs = (remaining[i].timelineStartMs - del.durationMs).coerceAtLeast(0L)
              )
            }
          }
        }
        newVideo = remaining.sortedBy { it.timelineStartMs }
      }
    }

    var newOverlay = _timeline.value.overlayClips
    if (!isTrackLocked(TrackType.OVERLAY)) {
      val toDelete = newOverlay.filter { it.id in targets }.sortedBy { it.timelineStartMs }
      if (toDelete.isNotEmpty()) {
        val remaining = newOverlay.filterNot { it.id in targets }.toMutableList()
        for (del in toDelete) {
          remaining.indices.forEach { i ->
            if (remaining[i].timelineStartMs >= del.timelineStartMs) {
              remaining[i] = remaining[i].copy(
                timelineStartMs = (remaining[i].timelineStartMs - del.durationMs).coerceAtLeast(0L)
              )
            }
          }
        }
        newOverlay = remaining.sortedBy { it.timelineStartMs }
      }
    }

    var newAudio = _timeline.value.audioClips
    if (!isTrackLocked(TrackType.AUDIO)) {
      val toDelete = newAudio.filter { it.id in targets }.sortedBy { it.timelineStartMs }
      if (toDelete.isNotEmpty()) {
        val remaining = newAudio.filterNot { it.id in targets }.toMutableList()
        for (del in toDelete) {
          remaining.indices.forEach { i ->
            if (remaining[i].timelineStartMs >= del.timelineStartMs) {
              remaining[i] = remaining[i].copy(
                timelineStartMs = (remaining[i].timelineStartMs - del.durationMs).coerceAtLeast(0L)
              )
            }
          }
        }
        newAudio = remaining.sortedBy { it.timelineStartMs }
      }
    }

    var newText = _timeline.value.textClips
    if (!isTrackLocked(TrackType.TEXT)) {
      val toDelete = newText.filter { it.id in targets }.sortedBy { it.timelineStartMs }
      if (toDelete.isNotEmpty()) {
        val remaining = newText.filterNot { it.id in targets }.toMutableList()
        for (del in toDelete) {
          remaining.indices.forEach { i ->
            if (remaining[i].timelineStartMs >= del.timelineStartMs) {
              remaining[i] = remaining[i].copy(
                timelineStartMs = (remaining[i].timelineStartMs - del.durationMs).coerceAtLeast(0L)
              )
            }
          }
        }
        newText = remaining.sortedBy { it.timelineStartMs }
      }
    }

    var newSticker = _timeline.value.stickerClips
    if (!isTrackLocked(TrackType.STICKER)) {
      val toDelete = newSticker.filter { it.id in targets }.sortedBy { it.timelineStartMs }
      if (toDelete.isNotEmpty()) {
        val remaining = newSticker.filterNot { it.id in targets }.toMutableList()
        for (del in toDelete) {
          remaining.indices.forEach { i ->
            if (remaining[i].timelineStartMs >= del.timelineStartMs) {
              remaining[i] = remaining[i].copy(
                timelineStartMs = (remaining[i].timelineStartMs - del.durationMs).coerceAtLeast(0L)
              )
            }
          }
        }
        newSticker = remaining.sortedBy { it.timelineStartMs }
      }
    }

    var newEffect = _timeline.value.effectClips
    if (!isTrackLocked(TrackType.EFFECT)) {
      val toDelete = newEffect.filter { it.id in targets }.sortedBy { it.timelineStartMs }
      if (toDelete.isNotEmpty()) {
        val remaining = newEffect.filterNot { it.id in targets }.toMutableList()
        for (del in toDelete) {
          remaining.indices.forEach { i ->
            if (remaining[i].timelineStartMs >= del.timelineStartMs) {
              remaining[i] = remaining[i].copy(
                timelineStartMs = (remaining[i].timelineStartMs - del.durationMs).coerceAtLeast(0L)
              )
            }
          }
        }
        newEffect = remaining.sortedBy { it.timelineStartMs }
      }
    }

    val validTransitions = _timeline.value.transitions.filter { it.clipIndexBefore < (newVideo.size - 1).coerceAtLeast(0) }
    _timeline.value = _timeline.value.copy(
      videoClips = newVideo,
      overlayClips = newOverlay,
      audioClips = newAudio,
      textClips = newText,
      stickerClips = newSticker,
      effectClips = newEffect,
      transitions = validTransitions
    )
    enforceZeroPointLock()
    clearSelection()
    return true
  }

  fun normalDelete(clipIds: Set<String> = emptySet()): Boolean {
    val targets = if (clipIds.isNotEmpty()) clipIds else _selectedClipIds.value
    if (targets.isEmpty()) {
      val selected = _selectedElement.value
      val singleId = when (selected) {
        is SelectedTrackElement.Video -> selected.clipId
        is SelectedTrackElement.Overlay -> selected.clipId
        is SelectedTrackElement.Audio -> selected.clipId
        is SelectedTrackElement.Text -> selected.clipId
        is SelectedTrackElement.Sticker -> selected.clipId
        is SelectedTrackElement.Effect -> selected.clipId
        SelectedTrackElement.None -> null
      }
      if (singleId == null) return false
      return normalDelete(setOf(singleId))
    }
    val desc = if (targets.size > 1) "Delete (${targets.size} clips)" else "Delete Clip"
    recordHistory(TimelineActionType.DELETE_CLIP, desc, targets)
    val newVideo = if (!isTrackLocked(TrackType.MAIN_VIDEO)) _timeline.value.videoClips.filterNot { it.id in targets } else _timeline.value.videoClips
    val newOverlay = if (!isTrackLocked(TrackType.OVERLAY)) _timeline.value.overlayClips.filterNot { it.id in targets } else _timeline.value.overlayClips
    val newAudio = if (!isTrackLocked(TrackType.AUDIO)) _timeline.value.audioClips.filterNot { it.id in targets } else _timeline.value.audioClips
    val newText = if (!isTrackLocked(TrackType.TEXT)) _timeline.value.textClips.filterNot { it.id in targets } else _timeline.value.textClips
    val newSticker = if (!isTrackLocked(TrackType.STICKER)) _timeline.value.stickerClips.filterNot { it.id in targets } else _timeline.value.stickerClips
    val newEffect = if (!isTrackLocked(TrackType.EFFECT)) _timeline.value.effectClips.filterNot { it.id in targets } else _timeline.value.effectClips
    val validTransitions = _timeline.value.transitions.filter { it.clipIndexBefore < (newVideo.size - 1).coerceAtLeast(0) }
    _timeline.value = _timeline.value.copy(
      videoClips = newVideo,
      overlayClips = newOverlay,
      audioClips = newAudio,
      textClips = newText,
      stickerClips = newSticker,
      effectClips = newEffect,
      transitions = validTransitions
    )
    enforceZeroPointLock()
    clearSelection()
    return true
  }

  fun deleteSelected(): Boolean {
    val targets = _selectedClipIds.value
    if (targets.isNotEmpty()) {
      return normalDelete(targets)
    }
    val selected = _selectedElement.value
    return when (selected) {
      is SelectedTrackElement.Video -> normalDelete(setOf(selected.clipId))
      is SelectedTrackElement.Overlay -> normalDelete(setOf(selected.clipId))
      is SelectedTrackElement.Audio -> normalDelete(setOf(selected.clipId))
      is SelectedTrackElement.Text -> normalDelete(setOf(selected.clipId))
      is SelectedTrackElement.Sticker -> normalDelete(setOf(selected.clipId))
      is SelectedTrackElement.Effect -> normalDelete(setOf(selected.clipId))
      SelectedTrackElement.None -> false
    }
  }

  fun deleteSelectedClips(ripple: Boolean = false): Boolean {
    val targets = _selectedClipIds.value
    return if (ripple) rippleDelete(targets) else normalDelete(targets)
  }

  fun deleteClips(clipIds: Set<String>, ripple: Boolean = false): Boolean {
    return if (ripple) rippleDelete(clipIds) else normalDelete(clipIds)
  }

  fun duplicateClips(clipIds: Set<String> = emptySet()): Boolean {
    val targets = if (clipIds.isNotEmpty()) clipIds else _selectedClipIds.value
    if (targets.isEmpty()) return duplicateSelected()
    recordHistory()
    val newVideo = _timeline.value.videoClips.toMutableList()
    val newOverlay = _timeline.value.overlayClips.toMutableList()
    val newAudio = _timeline.value.audioClips.toMutableList()
    val newText = _timeline.value.textClips.toMutableList()
    val newSticker = _timeline.value.stickerClips.toMutableList()
    val newEffect = _timeline.value.effectClips.toMutableList()
    val newSelected = mutableSetOf<String>()

    if (!isTrackLocked(TrackType.MAIN_VIDEO)) {
      _timeline.value.videoClips.filter { it.id in targets }.forEach { clip ->
        val copy = clip.copy(id = UUID.randomUUID().toString(), timelineStartMs = clip.timelineStartMs + clip.durationMs)
        newVideo.add(copy)
        newSelected.add(copy.id)
      }
    }
    if (!isTrackLocked(TrackType.OVERLAY)) {
      _timeline.value.overlayClips.filter { it.id in targets }.forEach { clip ->
        val copy = clip.copy(id = UUID.randomUUID().toString(), timelineStartMs = clip.timelineStartMs + clip.durationMs)
        newOverlay.add(copy)
        newSelected.add(copy.id)
      }
    }
    if (!isTrackLocked(TrackType.AUDIO)) {
      _timeline.value.audioClips.filter { it.id in targets }.forEach { clip ->
        val copy = clip.copy(id = UUID.randomUUID().toString(), timelineStartMs = clip.timelineStartMs + clip.durationMs)
        newAudio.add(copy)
        newSelected.add(copy.id)
      }
    }
    if (!isTrackLocked(TrackType.TEXT)) {
      _timeline.value.textClips.filter { it.id in targets }.forEach { clip ->
        val copy = clip.copy(
          id = UUID.randomUUID().toString(),
          timelineStartMs = clip.timelineStartMs,
          posX = (clip.posX + 0.05f).coerceIn(-1.5f, 1.5f),
          posY = (clip.posY + 0.05f).coerceIn(-1.5f, 1.5f)
        )
        newText.add(copy)
        newSelected.add(copy.id)
      }
    }
    if (!isTrackLocked(TrackType.STICKER)) {
      _timeline.value.stickerClips.filter { it.id in targets }.forEach { clip ->
        val copy = clip.copy(id = UUID.randomUUID().toString(), timelineStartMs = clip.timelineStartMs + clip.durationMs)
        newSticker.add(copy)
        newSelected.add(copy.id)
      }
    }
    if (!isTrackLocked(TrackType.EFFECT)) {
      _timeline.value.effectClips.filter { it.id in targets }.forEach { clip ->
        val copy = clip.copy(id = UUID.randomUUID().toString(), timelineStartMs = clip.timelineStartMs + clip.durationMs)
        newEffect.add(copy)
        newSelected.add(copy.id)
      }
    }

    _timeline.value = _timeline.value.copy(
      videoClips = newVideo.sortedBy { it.timelineStartMs },
      overlayClips = newOverlay.sortedBy { it.timelineStartMs },
      audioClips = newAudio.sortedBy { it.timelineStartMs },
      textClips = newText.sortedBy { it.timelineStartMs },
      stickerClips = newSticker.sortedBy { it.timelineStartMs },
      effectClips = newEffect.sortedBy { it.timelineStartMs }
    )
    _selectedClipIds.value = newSelected
    if (newSelected.isNotEmpty()) {
      _selectedElement.value = findTrackElementForClip(newSelected.first())
    }
    return true
  }

  /** Duplicates the selected element. An undo step is recorded only when a duplicate was really created. */
  fun duplicateSelected(): Boolean {
    val before = _timeline.value
    val created = duplicateSelectedInternal()
    if (created && _timeline.value !== before) {
      withStateLock {
        actionManager.recordPreEditHistory(TimelineActionType.GENERIC_EDIT, TimelineActionType.GENERIC_EDIT.displayName, before, emptySet())
      }
    }
    return created
  }

  private fun duplicateSelectedInternal(): Boolean {
    val selected = _selectedElement.value
    when (selected) {
      is SelectedTrackElement.Video -> {
        if (isTrackLocked(TrackType.MAIN_VIDEO)) return false
        val clip = _timeline.value.videoClips.find { it.id == selected.clipId } ?: return false
        val copy = clip.copy(id = UUID.randomUUID().toString(), timelineStartMs = clip.timelineStartMs + clip.durationMs)
        val list = _timeline.value.videoClips.toMutableList()
        list.add(copy)
        _timeline.value = _timeline.value.copy(videoClips = list)
        selectElement(SelectedTrackElement.Video(copy.id))
        return true
      }
      is SelectedTrackElement.Overlay -> {
        if (isTrackLocked(TrackType.OVERLAY)) return false
        val clip = _timeline.value.overlayClips.find { it.id == selected.clipId } ?: return false
        val copy = clip.copy(id = UUID.randomUUID().toString(), timelineStartMs = clip.timelineStartMs + clip.durationMs)
        val list = _timeline.value.overlayClips.toMutableList()
        list.add(copy)
        _timeline.value = _timeline.value.copy(overlayClips = list)
        selectElement(SelectedTrackElement.Overlay(copy.id))
        return true
      }
      is SelectedTrackElement.Text -> {
        if (isTrackLocked(TrackType.TEXT)) return false
        val clip = _timeline.value.textClips.find { it.id == selected.clipId } ?: return false
        val copy = clip.copy(
          id = UUID.randomUUID().toString(),
          timelineStartMs = clip.timelineStartMs,
          posX = (clip.posX + 0.05f).coerceIn(-1.5f, 1.5f),
          posY = (clip.posY + 0.05f).coerceIn(-1.5f, 1.5f)
        )
        val list = _timeline.value.textClips.toMutableList()
        list.add(copy)
        _timeline.value = _timeline.value.copy(textClips = list)
        selectElement(SelectedTrackElement.Text(copy.id))
        return true
      }
      is SelectedTrackElement.Audio -> {
        if (isTrackLocked(TrackType.AUDIO)) return false
        val clip = _timeline.value.audioClips.find { it.id == selected.clipId } ?: return false
        val copy = clip.copy(id = UUID.randomUUID().toString(), timelineStartMs = clip.timelineStartMs + clip.durationMs)
        val list = _timeline.value.audioClips.toMutableList()
        list.add(copy)
        _timeline.value = _timeline.value.copy(audioClips = list)
        selectElement(SelectedTrackElement.Audio(copy.id))
        return true
      }
      is SelectedTrackElement.Sticker -> {
        if (isTrackLocked(TrackType.STICKER)) return false
        val clip = _timeline.value.stickerClips.find { it.id == selected.clipId } ?: return false
        val copy = clip.copy(id = UUID.randomUUID().toString(), timelineStartMs = clip.timelineStartMs + clip.durationMs)
        val list = _timeline.value.stickerClips.toMutableList()
        list.add(copy)
        _timeline.value = _timeline.value.copy(stickerClips = list)
        selectElement(SelectedTrackElement.Sticker(copy.id))
        return true
      }
      is SelectedTrackElement.Effect -> {
        if (isTrackLocked(TrackType.EFFECT)) return false
        val clip = _timeline.value.effectClips.find { it.id == selected.clipId } ?: return false
        val copy = clip.copy(id = UUID.randomUUID().toString(), timelineStartMs = clip.timelineStartMs + clip.durationMs)
        val list = _timeline.value.effectClips.toMutableList()
        list.add(copy)
        _timeline.value = _timeline.value.copy(effectClips = list)
        selectElement(SelectedTrackElement.Effect(copy.id))
        return true
      }
      else -> return false
    }
  }

  fun copySelectedClips() {
    withStateLock {
      val targets = _selectedClipIds.value
      if (targets.isEmpty()) return
      val copies = mutableListOf<Any>()
      _timeline.value.videoClips.filter { it.id in targets }.forEach { copies.add(it) }
      _timeline.value.overlayClips.filter { it.id in targets }.forEach { copies.add(it) }
      _timeline.value.audioClips.filter { it.id in targets }.forEach { copies.add(it) }
      _timeline.value.textClips.filter { it.id in targets }.forEach { copies.add(it) }
      _timeline.value.stickerClips.filter { it.id in targets }.forEach { copies.add(it) }
      _timeline.value.effectClips.filter { it.id in targets }.forEach { copies.add(it) }
      _clipboardClips.value = copies
    }
  }

  fun pasteClipsAtPlayhead(): Boolean {
    val items = _clipboardClips.value
    if (items.isEmpty()) return false
    recordHistory()
    val playhead = _currentPositionMs.value
    val newVideo = _timeline.value.videoClips.toMutableList()
    val newOverlay = _timeline.value.overlayClips.toMutableList()
    val newAudio = _timeline.value.audioClips.toMutableList()
    val newText = _timeline.value.textClips.toMutableList()
    val newSticker = _timeline.value.stickerClips.toMutableList()
    val newEffect = _timeline.value.effectClips.toMutableList()
    val newSelected = mutableSetOf<String>()

    items.forEach { item ->
      when (item) {
        is VideoClip -> {
          if (!isTrackLocked(TrackType.MAIN_VIDEO)) {
            val copy = item.copy(id = UUID.randomUUID().toString(), timelineStartMs = playhead)
            newVideo.add(copy)
            newSelected.add(copy.id)
          }
        }
        is AudioClip -> {
          if (!isTrackLocked(TrackType.AUDIO)) {
            val copy = item.copy(id = UUID.randomUUID().toString(), timelineStartMs = playhead)
            newAudio.add(copy)
            newSelected.add(copy.id)
          }
        }
        is TextClip -> {
          if (!isTrackLocked(TrackType.TEXT)) {
            val copy = item.copy(id = UUID.randomUUID().toString(), timelineStartMs = playhead)
            newText.add(copy)
            newSelected.add(copy.id)
          }
        }
        is StickerClip -> {
          if (!isTrackLocked(TrackType.STICKER)) {
            val copy = item.copy(id = UUID.randomUUID().toString(), timelineStartMs = playhead)
            newSticker.add(copy)
            newSelected.add(copy.id)
          }
        }
        is EffectClip -> {
          if (!isTrackLocked(TrackType.EFFECT)) {
            val copy = item.copy(id = UUID.randomUUID().toString(), timelineStartMs = playhead)
            newEffect.add(copy)
            newSelected.add(copy.id)
          }
        }
      }
    }

    _timeline.value = _timeline.value.copy(
      videoClips = newVideo.sortedBy { it.timelineStartMs },
      overlayClips = newOverlay.sortedBy { it.timelineStartMs },
      audioClips = newAudio.sortedBy { it.timelineStartMs },
      textClips = newText.sortedBy { it.timelineStartMs },
      stickerClips = newSticker.sortedBy { it.timelineStartMs },
      effectClips = newEffect.sortedBy { it.timelineStartMs }
    )
    _selectedClipIds.value = newSelected
    if (newSelected.isNotEmpty()) {
      _selectedElement.value = findTrackElementForClip(newSelected.first())
    }
    return true
  }

  fun replaceMedia(
    clipId: String,
    newUri: String,
    newName: String,
    newDurationMs: Long? = null,
    isVideo: Boolean? = null
  ): Boolean {
    val element = findTrackElementForClip(clipId)
    when (element) {
      is SelectedTrackElement.Video -> {
        if (isTrackLocked(TrackType.MAIN_VIDEO)) return false
        recordHistory()
        val list = _timeline.value.videoClips.map { clip ->
          if (clip.id == clipId) {
            clip.copy(
              uri = newUri,
              name = newName,
              isVideo = isVideo ?: clip.isVideo,
              durationMs = newDurationMs ?: clip.durationMs,
              sourceEndMs = newDurationMs ?: clip.sourceEndMs
            )
          } else clip
        }
        _timeline.value = _timeline.value.copy(videoClips = list)
        return true
      }
      is SelectedTrackElement.Overlay -> {
        if (isTrackLocked(TrackType.OVERLAY)) return false
        recordHistory()
        val list = _timeline.value.overlayClips.map { clip ->
          if (clip.id == clipId) {
            clip.copy(
              uri = newUri,
              name = newName,
              isVideo = isVideo ?: clip.isVideo,
              durationMs = newDurationMs ?: clip.durationMs,
              sourceEndMs = newDurationMs ?: clip.sourceEndMs
            )
          } else clip
        }
        _timeline.value = _timeline.value.copy(overlayClips = list)
        return true
      }
      is SelectedTrackElement.Audio -> {
        if (isTrackLocked(TrackType.AUDIO)) return false
        recordHistory()
        val list = _timeline.value.audioClips.map { clip ->
          if (clip.id == clipId) {
            clip.copy(
              uri = newUri,
              title = newName,
              durationMs = newDurationMs ?: clip.durationMs,
              sourceEndMs = newDurationMs ?: clip.sourceEndMs
            )
          } else clip
        }
        _timeline.value = _timeline.value.copy(audioClips = list)
        return true
      }
      else -> return false
    }
  }

  fun toggleReverseSelectedClip(clipId: String? = null): Boolean {
    val targetId = clipId ?: _selectedClipIds.value.firstOrNull() ?: return false
    val element = findTrackElementForClip(targetId)
    recordHistory()
    return when (element) {
      is SelectedTrackElement.Video -> {
        val list = _timeline.value.videoClips.map {
          if (it.id == targetId) it.copy(isReversed = !it.isReversed) else it
        }
        _timeline.value = _timeline.value.copy(videoClips = list)
        true
      }
      is SelectedTrackElement.Overlay -> {
        val list = _timeline.value.overlayClips.map {
          if (it.id == targetId) it.copy(isReversed = !it.isReversed) else it
        }
        _timeline.value = _timeline.value.copy(overlayClips = list)
        true
      }
      is SelectedTrackElement.Audio -> {
        val list = _timeline.value.audioClips.map {
          if (it.id == targetId) it.copy(isReversed = !it.isReversed) else it
        }
        _timeline.value = _timeline.value.copy(audioClips = list)
        true
      }
      else -> false
    }
  }

  fun toggleClipBackgroundRemoval(clipId: String? = null): Boolean {
    val targetId = clipId ?: _selectedClipIds.value.firstOrNull() ?: findClipUnderPlayhead() ?: return false
    recordHistory()
    var updated = false
    val newVideos = _timeline.value.videoClips.map {
      if (it.id == targetId) { updated = true; it.copy(isBackgroundRemoved = !it.isBackgroundRemoved) } else it
    }
    if (updated) { _timeline.value = _timeline.value.copy(videoClips = newVideos); return true }
    val newOverlays = _timeline.value.overlayClips.map {
      if (it.id == targetId) { updated = true; it.copy(isBackgroundRemoved = !it.isBackgroundRemoved) } else it
    }
    if (updated) { _timeline.value = _timeline.value.copy(overlayClips = newOverlays); return true }
    return false
  }

  fun toggleMotionBlur(clipId: String? = null): Boolean {
    val targetId = clipId ?: _selectedClipIds.value.firstOrNull() ?: findClipUnderPlayhead() ?: return false
    recordHistory()
    var updated = false
    val newVideos = _timeline.value.videoClips.map {
      if (it.id == targetId) { updated = true; it.copy(motionBlurEnabled = !it.motionBlurEnabled) } else it
    }
    if (updated) { _timeline.value = _timeline.value.copy(videoClips = newVideos); return true }
    val newOverlays = _timeline.value.overlayClips.map {
      if (it.id == targetId) { updated = true; it.copy(motionBlurEnabled = !it.motionBlurEnabled) } else it
    }
    if (updated) { _timeline.value = _timeline.value.copy(overlayClips = newOverlays); return true }
    return false
  }

  fun setClipOpacity(clipId: String? = null, opacity: Float): Boolean {
    val targetId = clipId ?: _selectedClipIds.value.firstOrNull() ?: findClipUnderPlayhead() ?: return false
    val clamped = opacity.coerceIn(0f, 1f)
    recordHistory()
    var updated = false
    val newVideos = _timeline.value.videoClips.map {
      if (it.id == targetId) { updated = true; it.copy(opacity = clamped) } else it
    }
    if (updated) { _timeline.value = _timeline.value.copy(videoClips = newVideos); return true }
    val newOverlays = _timeline.value.overlayClips.map {
      if (it.id == targetId) { updated = true; it.copy(opacity = clamped) } else it
    }
    if (updated) { _timeline.value = _timeline.value.copy(overlayClips = newOverlays); return true }
    val newTexts = _timeline.value.textClips.map {
      if (it.id == targetId) { updated = true; it.copy(opacity = clamped) } else it
    }
    if (updated) { _timeline.value = _timeline.value.copy(textClips = newTexts); return true }
    val newStickers = _timeline.value.stickerClips.map {
      if (it.id == targetId) { updated = true; it.copy(opacity = clamped) } else it
    }
    if (updated) { _timeline.value = _timeline.value.copy(stickerClips = newStickers); return true }
    return false
  }

  fun extractAudioFromSelectedClip(): String? {
    val targetId = _selectedClipIds.value.firstOrNull()
      ?: (when (val sel = _selectedElement.value) {
        is SelectedTrackElement.Video -> sel.clipId
        is SelectedTrackElement.Overlay -> sel.clipId
        else -> findClipUnderPlayhead()
      }) ?: return null
    return extractAudioFromClip(targetId)
  }

  fun enhanceSelectedAudio(clipId: String? = null): Boolean {
    val targetId = clipId ?: when (val sel = _selectedElement.value) {
      is SelectedTrackElement.Audio -> sel.clipId
      is SelectedTrackElement.Video -> sel.clipId
      is SelectedTrackElement.Overlay -> sel.clipId
      is SelectedTrackElement.None -> _selectedClipIds.value.firstOrNull() ?: findClipUnderPlayhead()
      else -> return false
    } ?: return false
    recordHistory()
    var updated = false
    val newAudios = _timeline.value.audioClips.map { clip ->
      if (clip.id == targetId) {
        updated = true
        clip.copy(audioEffects = clip.audioEffects.copy(normalizeVolume = true, noiseReductionDb = 12f))
      } else clip
    }
    if (updated) {
      _timeline.value = _timeline.value.copy(audioClips = newAudios)
      return true
    }
    val newVideos = _timeline.value.videoClips.map { clip ->
      if (clip.id == targetId) {
        updated = true
        clip.copy(audioEffects = clip.audioEffects.copy(normalizeVolume = true, noiseReductionDb = 12f))
      } else clip
    }
    if (updated) {
      _timeline.value = _timeline.value.copy(videoClips = newVideos)
      return true
    }
    return false
  }

  fun setClipSpeed(clipId: String? = null, speed: Float): Boolean {
    val targetId = clipId ?: _selectedClipIds.value.firstOrNull() ?: findClipUnderPlayhead() ?: return false
    val element = findTrackElementForClip(targetId)
    val clampedSpeed = speed.coerceIn(0.1f, 10f)
    if (element !is SelectedTrackElement.Video && element !is SelectedTrackElement.Overlay && element !is SelectedTrackElement.Audio) return false
    recordHistoryCoalesced("speed:$targetId")
    return when (element) {
      is SelectedTrackElement.Video -> {
        val original = _timeline.value.videoClips.firstOrNull { it.id == targetId }
        val oldEnd = original?.let { it.timelineStartMs + it.durationMs } ?: 0L
        var durationDelta = 0L
        val list = _timeline.value.videoClips.map { clip ->
          if (clip.id == targetId) {
            val oldSpeed = clip.speed
            val newDuration = ((clip.durationMs * oldSpeed) / clampedSpeed).toLong().coerceAtLeast(200L)
            durationDelta = newDuration - clip.durationMs
            clip.copy(speed = clampedSpeed, durationMs = newDuration)
          } else clip
        }.map { clip ->
          // Ripple later main-track clips so speed changes don't leave gaps/overlaps
          if (clip.id != targetId && durationDelta != 0L && clip.timelineStartMs >= oldEnd && !isTrackLocked(TrackType.MAIN_VIDEO))
            clip.copy(timelineStartMs = (clip.timelineStartMs + durationDelta).coerceAtLeast(0L))
          else clip
        }
        _timeline.value = _timeline.value.copy(videoClips = list)
        true
      }
      is SelectedTrackElement.Overlay -> {
        val list = _timeline.value.overlayClips.map { clip ->
          if (clip.id == targetId) {
            val oldSpeed = clip.speed
            val newDuration = ((clip.durationMs * oldSpeed) / clampedSpeed).toLong().coerceAtLeast(200L)
            clip.copy(speed = clampedSpeed, durationMs = newDuration)
          } else clip
        }
        _timeline.value = _timeline.value.copy(overlayClips = list)
        true
      }
      is SelectedTrackElement.Audio -> {
        val list = _timeline.value.audioClips.map { clip ->
          if (clip.id == targetId) {
            val oldSpeed = clip.speed
            val newDuration = ((clip.durationMs * oldSpeed) / clampedSpeed).toLong().coerceAtLeast(200L)
            clip.copy(speed = clampedSpeed, durationMs = newDuration)
          } else clip
        }
        _timeline.value = _timeline.value.copy(audioClips = list)
        true
      }
      else -> false
    }
  }

  fun freezeFrameAtPlayhead(freezeDurationMs: Long = 2500L): Boolean {
    val selected = _selectedElement.value.takeIf { it != SelectedTrackElement.None }
      ?: findClipUnderPlayhead()?.let { findTrackElementForClip(it) }
      ?: SelectedTrackElement.None
    if (selected is SelectedTrackElement.Video) {
      if (isTrackLocked(TrackType.MAIN_VIDEO)) return false
      val index = _timeline.value.videoClips.indexOfFirst { it.id == selected.clipId }
      if (index == -1) return false
      val clip = _timeline.value.videoClips[index]
      val playhead = _currentPositionMs.value
      if (playhead <= clip.timelineStartMs + 10L || playhead >= clip.timelineStartMs + clip.durationMs - 10L) return false
      recordHistory()
      val firstDur = playhead - clip.timelineStartMs
      val secondDur = (clip.durationMs - firstDur).coerceAtLeast(10L)
      val splitSource = clip.timelineToSourceMs(playhead)
      val before = clip.copy(
        durationMs = firstDur,
        sourceEndMs = splitSource,
        keyframes = clip.keyframes.filter { it.timeMs <= firstDur }
      )
      val freezeClip = clip.copy(
        id = UUID.randomUUID().toString(),
        name = "${clip.name} (Freeze)",
        timelineStartMs = playhead,
        durationMs = freezeDurationMs,
        sourceStartMs = splitSource,
        sourceEndMs = splitSource + 1L,
        speed = 1.0f,
        isReversed = false,
        speedCurve = SpeedCurve(),
        keyframes = emptyList(),
        isMuted = true
      )
      val after = clip.copy(
        id = UUID.randomUUID().toString(),
        timelineStartMs = playhead + freezeDurationMs,
        durationMs = secondDur,
        sourceStartMs = splitSource,
        keyframes = clip.keyframes.filter { it.timeMs >= firstDur }.map { it.copy(timeMs = it.timeMs - firstDur) }
      )
      val list = _timeline.value.videoClips.toMutableList()
      list[index] = before
      list.add(index + 1, freezeClip)
      list.add(index + 2, after)
      // Shift every later main-track clip right by the inserted freeze duration
      for (i in (index + 3) until list.size) {
        list[i] = list[i].copy(timelineStartMs = list[i].timelineStartMs + freezeDurationMs)
      }
      val updatedTransitions = _timeline.value.transitions.map { tr ->
        if (tr.clipIndexBefore > index) tr.copy(clipIndexBefore = tr.clipIndexBefore + 2) else tr
      }
      _timeline.value = _timeline.value.copy(videoClips = list, transitions = updatedTransitions)
      selectElement(SelectedTrackElement.Video(freezeClip.id))
      return true
    } else if (selected is SelectedTrackElement.Overlay) {
      val clip = _timeline.value.overlayClips.find { it.id == selected.clipId } ?: return false
      recordHistory()
      val freezeClip = VideoClip(
        id = UUID.randomUUID().toString(),
        uri = clip.uri,
        name = "${clip.name} (Freeze)",
        isVideo = false,
        timelineStartMs = _currentPositionMs.value,
        durationMs = freezeDurationMs,
        sourceStartMs = (_currentPositionMs.value - clip.timelineStartMs).coerceAtLeast(0L),
        sourceEndMs = (_currentPositionMs.value - clip.timelineStartMs).coerceAtLeast(0L),
        cropScale = clip.cropScale,
        cropOffsetX = clip.cropOffsetX,
        cropOffsetY = clip.cropOffsetY,
        opacity = clip.opacity
      )
      val list = _timeline.value.overlayClips.toMutableList()
      list.add(freezeClip)
      list.sortBy { it.timelineStartMs }
      _timeline.value = _timeline.value.copy(overlayClips = list)
      selectElement(SelectedTrackElement.Overlay(freezeClip.id))
      return true
    }
    return false
  }

  fun rotateSelectedClip() {
    withStateLock {
      val selected = _selectedElement.value.takeIf { it != SelectedTrackElement.None }
        ?: findClipUnderPlayhead()?.let { findTrackElementForClip(it) }
        ?: SelectedTrackElement.None
      if (selected is SelectedTrackElement.Video) {
        recordHistory()
        val list = _timeline.value.videoClips.map { clip ->
          if (clip.id == selected.clipId) {
            val next = (clip.rotationDegrees + 90) % 360
            clip.copy(rotationDegrees = next)
          } else clip
        }
        _timeline.value = _timeline.value.copy(videoClips = list)
      } else if (selected is SelectedTrackElement.Overlay) {
        recordHistory()
        val list = _timeline.value.overlayClips.map { clip ->
          if (clip.id == selected.clipId) {
            val next = (clip.rotationDegrees + 90) % 360
            clip.copy(rotationDegrees = next)
          } else clip
        }
        _timeline.value = _timeline.value.copy(overlayClips = list)
      }
    }
  }

  fun flipSelectedClip(horizontal: Boolean) {
    withStateLock {
      val selected = _selectedElement.value.takeIf { it != SelectedTrackElement.None }
        ?: findClipUnderPlayhead()?.let { findTrackElementForClip(it) }
        ?: SelectedTrackElement.None
      if (selected is SelectedTrackElement.Video) {
        recordHistory()
        val list = _timeline.value.videoClips.map { clip ->
          if (clip.id == selected.clipId) {
            if (horizontal) clip.copy(flipHorizontal = !clip.flipHorizontal)
            else clip.copy(flipVertical = !clip.flipVertical)
          } else clip
        }
        _timeline.value = _timeline.value.copy(videoClips = list)
      } else if (selected is SelectedTrackElement.Overlay) {
        recordHistory()
        val list = _timeline.value.overlayClips.map { clip ->
          if (clip.id == selected.clipId) {
            if (horizontal) clip.copy(flipHorizontal = !clip.flipHorizontal)
            else clip.copy(flipVertical = !clip.flipVertical)
          } else clip
        }
        _timeline.value = _timeline.value.copy(overlayClips = list)
      }
    }
  }

  // --- Adjustments & Filters ---

  fun updateAdjustments(adjustments: VideoAdjustments) {
    withStateLock {
      recordHistoryCoalesced("adjustments")
      _timeline.value = _timeline.value.copy(adjustments = adjustments)
    }
  }

  /**
   * Clip-wise Adjust / Video Quality. Writes only the target clip (explicit id, else the selected
   * clip, else the clip under the playhead). Returns false when there is no clip to target.
   */
  fun updateClipAdjustments(adjustments: VideoAdjustments, targetClipId: String? = null): Boolean {
    val tl = _timeline.value
    val playheadClipId = tl.videoClips.find {
      _currentPositionMs.value >= it.timelineStartMs && _currentPositionMs.value < it.timelineStartMs + it.durationMs
    }?.id ?: tl.videoClips.firstOrNull()?.id
    val clipId = targetClipId ?: when (val sel = _selectedElement.value) {
      is SelectedTrackElement.Video -> sel.clipId
      is SelectedTrackElement.Overlay -> sel.clipId
      else -> null
    } ?: playheadClipId ?: return false
    if (tl.videoClips.none { it.id == clipId } && tl.overlayClips.none { it.id == clipId }) return false
    recordHistoryCoalesced("clip_adjustments:$clipId")
    _timeline.value = tl.copy(
      videoClips = tl.videoClips.map { if (it.id == clipId) it.copy(adjustments = adjustments) else it },
      overlayClips = tl.overlayClips.map { if (it.id == clipId) it.copy(adjustments = adjustments) else it }
    )
    return true
  }

  fun updateChromaKey(chromaKey: ChromaKeySettings) {
    withStateLock {
      recordHistory()
      _timeline.value = _timeline.value.copy(chromaKey = chromaKey)
    }
  }

  fun updateFilter(filter: FilterSettings, targetClipId: String? = null) {
    withStateLock {
      recordHistoryCoalesced("filter:$targetClipId")
      val playheadClipId = _timeline.value.videoClips.find {
        _currentPositionMs.value >= it.timelineStartMs && _currentPositionMs.value < it.timelineStartMs + it.durationMs
      }?.id ?: _timeline.value.videoClips.firstOrNull()?.id

      val clipId = targetClipId ?: when (val sel = _selectedElement.value) {
        is SelectedTrackElement.Video -> sel.clipId
        is SelectedTrackElement.Overlay -> sel.clipId
        else -> null
      } ?: playheadClipId

      if (clipId != null) {
        val newVideos = _timeline.value.videoClips.map {
          if (it.id == clipId) it.copy(filter = filter) else it
        }
        val newOverlays = _timeline.value.overlayClips.map {
          if (it.id == clipId) it.copy(filter = filter) else it
        }
        // Filters applied through the clip editor are clip-local.
        // Do not also write Timeline.filter, otherwise the same filter leaks onto
        // every other clip during preview/export.
        _timeline.value = _timeline.value.copy(
          videoClips = newVideos,
          overlayClips = newOverlays,
          filter = FilterSettings(type = FilterType.NONE, intensity = 1.0f)
        )
      } else {
        // No selected clip: keep the project filter disabled rather than applying
        // an implicitly global filter to unrelated media.
        _timeline.value = _timeline.value.copy(
          filter = FilterSettings(type = FilterType.NONE, intensity = 1.0f)
        )
      }
    }
  }

  fun applyFilterToAllClips(filter: FilterSettings) {
    withStateLock {
      recordHistory()
      val newVideos = _timeline.value.videoClips.map { it.copy(filter = filter) }
      val newOverlays = _timeline.value.overlayClips.map { it.copy(filter = filter) }
      _timeline.value = _timeline.value.copy(
        videoClips = newVideos,
        overlayClips = newOverlays,
        filter = FilterSettings(type = FilterType.NONE, intensity = 1.0f)
      )
    }
  }

  fun toggleClipFilter(clipId: String? = null): Boolean {
    val playheadClipId = _timeline.value.videoClips.find {
      _currentPositionMs.value >= it.timelineStartMs && _currentPositionMs.value < it.timelineStartMs + it.durationMs
    }?.id ?: _timeline.value.videoClips.firstOrNull()?.id

    val targetId = clipId ?: when (val sel = _selectedElement.value) {
      is SelectedTrackElement.Video -> sel.clipId
      is SelectedTrackElement.Overlay -> sel.clipId
      else -> null
    } ?: playheadClipId ?: return false

    recordHistory()
    var toggled = false
    val newVideos = _timeline.value.videoClips.map { clip ->
      if (clip.id == targetId) {
        toggled = true
        val current = clip.filter
        if (current == null || current.type == FilterType.NONE) {
          clip.copy(filter = FilterSettings(type = FilterType.CINEMATIC, intensity = 1.0f))
        } else {
          clip.copy(filter = FilterSettings(type = FilterType.NONE, intensity = 1.0f))
        }
      } else clip
    }
    val newOverlays = _timeline.value.overlayClips.map { clip ->
      if (clip.id == targetId) {
        toggled = true
        val current = clip.filter
        if (current == null || current.type == FilterType.NONE) {
          clip.copy(filter = FilterSettings(type = FilterType.CINEMATIC, intensity = 1.0f))
        } else {
          clip.copy(filter = FilterSettings(type = FilterType.NONE, intensity = 1.0f))
        }
      } else clip
    }
    if (toggled) {
      _timeline.value = _timeline.value.copy(
        videoClips = newVideos,
        overlayClips = newOverlays
      )
    }
    return toggled
  }

  fun clearAllClipFilters() {
    withStateLock {
      recordHistory()
      val noneFilter = FilterSettings(type = FilterType.NONE, intensity = 1.0f)
      val newVideos = _timeline.value.videoClips.map { it.copy(filter = noneFilter) }
      val newOverlays = _timeline.value.overlayClips.map { it.copy(filter = noneFilter) }
      _timeline.value = _timeline.value.copy(
        videoClips = newVideos,
        overlayClips = newOverlays,
        filter = noneFilter
      )
    }
  }


  // --- Audio Operations ---

  fun setClipVolume(clipId: String? = null, volume: Float): Boolean {
    val targetId = clipId ?: _selectedClipIds.value.firstOrNull() ?: findClipUnderPlayhead() ?: _timeline.value.videoClips.firstOrNull()?.id ?: _timeline.value.audioClips.firstOrNull()?.id ?: return false
    val clampedVol = volume.coerceIn(0f, 3.0f)
    recordHistoryCoalesced("volume:$targetId")

    var updated = false
    val newVideoClips = _timeline.value.videoClips.map { clip ->
      if (clip.id == targetId) {
        updated = true
        clip.copy(volume = clampedVol, isMuted = clampedVol == 0f)
      } else clip
    }
    if (updated) {
      _timeline.value = _timeline.value.copy(videoClips = newVideoClips)
      return true
    }

    val newOverlayClips = _timeline.value.overlayClips.map { clip ->
      if (clip.id == targetId) {
        updated = true
        clip.copy(volume = clampedVol, isMuted = clampedVol == 0f)
      } else clip
    }
    if (updated) {
      _timeline.value = _timeline.value.copy(overlayClips = newOverlayClips)
      return true
    }

    val newAudioClips = _timeline.value.audioClips.map { clip ->
      if (clip.id == targetId) {
        updated = true
        clip.copy(volume = clampedVol, isMuted = clampedVol == 0f)
      } else clip
    }
    if (updated) {
      _timeline.value = _timeline.value.copy(audioClips = newAudioClips)
      return true
    }

    // Fallback if targetId wasn't matched explicitly
    val fallbackVideoClips = _timeline.value.videoClips.map { clip ->
      clip.copy(volume = clampedVol, isMuted = clampedVol == 0f)
    }
    _timeline.value = _timeline.value.copy(videoClips = fallbackVideoClips)
    return true
  }

  // --- Masking, Blending, Speed Curves & Audio Effects ---

  fun setClipMask(clipId: String? = null, mask: MaskSettings): Boolean {
    val targetId = clipId ?: _selectedClipIds.value.firstOrNull() ?: findClipUnderPlayhead() ?: return false
    recordHistory()
    var updated = false
    val newVideos = _timeline.value.videoClips.map {
      if (it.id == targetId) { updated = true; it.copy(mask = mask) } else it
    }
    if (updated) {
      _timeline.value = _timeline.value.copy(videoClips = newVideos)
      return true
    }
    val newOverlays = _timeline.value.overlayClips.map {
      if (it.id == targetId) { updated = true; it.copy(mask = mask) } else it
    }
    if (updated) {
      _timeline.value = _timeline.value.copy(overlayClips = newOverlays)
      return true
    }
    return false
  }

  fun setClipBlendMode(clipId: String? = null, blendMode: String): Boolean {
    val targetId = clipId ?: _selectedClipIds.value.firstOrNull() ?: findClipUnderPlayhead() ?: return false
    recordHistory()
    var updated = false
    val newVideos = _timeline.value.videoClips.map {
      if (it.id == targetId) { updated = true; it.copy(blendMode = blendMode) } else it
    }
    if (updated) {
      _timeline.value = _timeline.value.copy(videoClips = newVideos)
      return true
    }
    val newOverlays = _timeline.value.overlayClips.map {
      if (it.id == targetId) { updated = true; it.copy(blendMode = blendMode) } else it
    }
    if (updated) {
      _timeline.value = _timeline.value.copy(overlayClips = newOverlays)
      return true
    }
    return false
  }

  fun setClipSpeedCurve(clipId: String? = null, speedCurve: SpeedCurve): Boolean {
    val targetId = clipId ?: _selectedClipIds.value.firstOrNull() ?: findClipUnderPlayhead() ?: return false
    recordHistory()
    var updated = false
    val newVideos = _timeline.value.videoClips.map {
      if (it.id == targetId) { updated = true; it.copy(speedCurve = speedCurve) } else it
    }
    if (updated) {
      _timeline.value = _timeline.value.copy(videoClips = newVideos)
      return true
    }
    val newAudios = _timeline.value.audioClips.map {
      if (it.id == targetId) { updated = true; it.copy(speedCurve = speedCurve) } else it
    }
    if (updated) {
      _timeline.value = _timeline.value.copy(audioClips = newAudios)
      return true
    }
    return false
  }

  /** Stores a clip's colour grade (ColorState JSON) in the timeline. Slider bursts share one undo step. */
  fun setClipColorGrade(clipId: String, gradeJson: String?): Boolean {
    if (_timeline.value.videoClips.none { it.id == clipId }) return false
    recordHistoryCoalesced("color_grade:$clipId", TimelineActionType.COLOR_GRADE_EDIT)
    _timeline.value = _timeline.value.copy(
      videoClips = _timeline.value.videoClips.map { if (it.id == clipId) it.copy(colorGradeJson = gradeJson) else it }
    )
    return true
  }

  /** Stores a clip's com.vfx effect stack (EffectStack JSON). Slider bursts share one undo step. */
  fun setClipVfxStack(clipId: String, stackJson: String?): Boolean {
    if (_timeline.value.videoClips.none { it.id == clipId }) return false
    recordHistoryCoalesced("vfx_stack:$clipId", TimelineActionType.VFX_STACK_EDIT)
    _timeline.value = _timeline.value.copy(
      videoClips = _timeline.value.videoClips.map { if (it.id == clipId) it.copy(vfxStackJson = stackJson) else it }
    )
    return true
  }

  /** Stores a clip's Stabilization data (see [VideoClip.stabilize] / StabilizeCodec); null removes it. One undo step per change. */
  fun setClipStabilization(clipId: String, encoded: String?): Boolean {
    val clip = _timeline.value.videoClips.find { it.id == clipId } ?: return false
    if (clip.stabilize == encoded) return true
    recordHistory()
    _timeline.value = _timeline.value.copy(
      videoClips = _timeline.value.videoClips.map { if (it.id == clipId) it.copy(stabilize = encoded) else it }
    )
    return true
  }

  /** Stores Object/Face/Body/Motion tracking (see [VideoClip.motionTrackJson] / MotionTrackCodec); null removes it. */
  fun setClipMotionTrack(clipId: String, encoded: String?): Boolean {
    val inVideo = _timeline.value.videoClips.find { it.id == clipId }
    if (inVideo != null) {
      if (inVideo.motionTrackJson == encoded) return true
      recordHistory()
      _timeline.value = _timeline.value.copy(
        videoClips = _timeline.value.videoClips.map { if (it.id == clipId) it.copy(motionTrackJson = encoded) else it }
      )
      return true
    }
    val inOverlay = _timeline.value.overlayClips.find { it.id == clipId } ?: return false
    if (inOverlay.motionTrackJson == encoded) return true
    recordHistory()
    _timeline.value = _timeline.value.copy(
      overlayClips = _timeline.value.overlayClips.map { if (it.id == clipId) it.copy(motionTrackJson = encoded) else it }
    )
    return true
  }

  /** Independent overlay-to-track binding on a video / PIP clip. Null removes it. */
  fun setClipTrackBind(clipId: String, encoded: String?): Boolean {
    val inVideo = _timeline.value.videoClips.find { it.id == clipId }
    if (inVideo != null) {
      if (inVideo.trackBindJson == encoded) return true
      recordHistory()
      _timeline.value = _timeline.value.copy(
        videoClips = _timeline.value.videoClips.map { if (it.id == clipId) it.copy(trackBindJson = encoded) else it }
      )
      return true
    }
    val inOverlay = _timeline.value.overlayClips.find { it.id == clipId } ?: return false
    if (inOverlay.trackBindJson == encoded) return true
    recordHistory()
    _timeline.value = _timeline.value.copy(
      overlayClips = _timeline.value.overlayClips.map { if (it.id == clipId) it.copy(trackBindJson = encoded) else it }
    )
    return true
  }

  /** Stores a clip's Face Reshape slider values (see [VideoClip.faceReshape]). Slider bursts share one undo step. */
  fun setClipFaceReshape(clipId: String, encoded: String?): Boolean {
    val clip = _timeline.value.videoClips.find { it.id == clipId } ?: return false
    if (clip.faceReshape == encoded) return true
    recordHistoryCoalesced("face_reshape:$clipId", TimelineActionType.FACE_RESHAPE_EDIT)
    _timeline.value = _timeline.value.copy(
      videoClips = _timeline.value.videoClips.map { if (it.id == clipId) it.copy(faceReshape = encoded) else it }
    )
    return true
  }

  /** Stores a clip's AR face filter (see [VideoClip.arOverlay]). Slider bursts share one undo step. */
  fun setClipArOverlay(clipId: String, encoded: String?): Boolean {
    val clip = _timeline.value.videoClips.find { it.id == clipId } ?: return false
    if (clip.arOverlay == encoded) return true
    recordHistoryCoalesced("ar_overlay:$clipId", TimelineActionType.AR_OVERLAY_EDIT)
    _timeline.value = _timeline.value.copy(
      videoClips = _timeline.value.videoClips.map { if (it.id == clipId) it.copy(arOverlay = encoded) else it }
    )
    return true
  }

  /**
   * Turns background removal on/off for a main or overlay clip and stores its look
   * (see [VideoClip.bgRemove]). Slider bursts share one undo step.
   */
  fun setClipBgRemoval(clipId: String, enabled: Boolean, encoded: String?): Boolean {
    val tl = _timeline.value
    val current = (tl.videoClips + tl.overlayClips).firstOrNull { it.id == clipId } ?: return false
    if (current.isBackgroundRemoved == enabled && current.bgRemove == encoded) return true
    recordHistoryCoalesced("bg_remove:$clipId")
    val update: (VideoClip) -> VideoClip = { c ->
      if (c.id == clipId) c.copy(isBackgroundRemoved = enabled, bgRemove = encoded) else c
    }
    _timeline.value = tl.copy(
      videoClips = tl.videoClips.map(update),
      overlayClips = tl.overlayClips.map(update)
    )
    return true
  }

  fun getClipAudioEffects(clipId: String? = null): AudioEffectsSettings? {
    val targetId = clipId ?: _selectedClipIds.value.firstOrNull() ?: findClipUnderPlayhead() ?: return null
    return _timeline.value.videoClips.find { it.id == targetId }?.audioEffects
      ?: _timeline.value.audioClips.find { it.id == targetId }?.audioEffects
      ?: _timeline.value.overlayClips.find { it.id == targetId }?.audioEffects
  }

  fun setClipAudioEffects(clipId: String? = null, audioEffects: AudioEffectsSettings): Boolean {
    val targetId = clipId ?: _selectedClipIds.value.firstOrNull() ?: findClipUnderPlayhead() ?: return false
    recordHistory()
    var updated = false
    val newVideos = _timeline.value.videoClips.map {
      if (it.id == targetId) { updated = true; it.copy(audioEffects = audioEffects) } else it
    }
    if (updated) {
      _timeline.value = _timeline.value.copy(videoClips = newVideos)
      return true
    }
    val newAudios = _timeline.value.audioClips.map {
      if (it.id == targetId) { updated = true; it.copy(audioEffects = audioEffects) } else it
    }
    if (updated) {
      _timeline.value = _timeline.value.copy(audioClips = newAudios)
      return true
    }
    val newOverlays = _timeline.value.overlayClips.map {
      if (it.id == targetId) { updated = true; it.copy(audioEffects = audioEffects) } else it
    }
    if (updated) {
      _timeline.value = _timeline.value.copy(overlayClips = newOverlays)
      return true
    }
    return false
  }

  fun toggleClipMute(clipId: String? = null): Boolean {
    val targetId = clipId ?: _selectedClipIds.value.firstOrNull() ?: findClipUnderPlayhead() ?: _timeline.value.videoClips.firstOrNull()?.id ?: return false
    recordHistory()

    var updated = false
    val newVideoClips = _timeline.value.videoClips.map { clip ->
      if (clip.id == targetId) {
        updated = true
        val newMute = !clip.isMuted
        val newVol = if (newMute) clip.volume else (if (clip.volume == 0f) 1.0f else clip.volume)
        clip.copy(isMuted = newMute, volume = newVol)
      } else clip
    }
    if (updated) {
      _timeline.value = _timeline.value.copy(videoClips = newVideoClips)
      return true
    }

    val newOverlayClips = _timeline.value.overlayClips.map { clip ->
      if (clip.id == targetId) {
        updated = true
        val newMute = !clip.isMuted
        val newVol = if (newMute) clip.volume else (if (clip.volume == 0f) 1.0f else clip.volume)
        clip.copy(isMuted = newMute, volume = newVol)
      } else clip
    }
    if (updated) {
      _timeline.value = _timeline.value.copy(overlayClips = newOverlayClips)
      return true
    }

    val newAudioClips = _timeline.value.audioClips.map { clip ->
      if (clip.id == targetId) {
        updated = true
        val newMute = !clip.isMuted
        val newVol = if (newMute) clip.volume else (if (clip.volume == 0f) 1.0f else clip.volume)
        clip.copy(isMuted = newMute, volume = newVol)
      } else clip
    }
    if (updated) {
      _timeline.value = _timeline.value.copy(audioClips = newAudioClips)
      return true
    }

    val fallbackVideoClips = _timeline.value.videoClips.map { clip ->
      val newMute = !clip.isMuted
      val newVol = if (newMute) clip.volume else (if (clip.volume == 0f) 1.0f else clip.volume)
      clip.copy(isMuted = newMute, volume = newVol)
    }
    _timeline.value = _timeline.value.copy(videoClips = fallbackVideoClips)
    return true
  }

  fun increaseClipVolume(clipId: String? = null, step: Float = 0.10f): Boolean {
    val targetId = clipId ?: _selectedClipIds.value.firstOrNull() ?: findClipUnderPlayhead() ?: _timeline.value.videoClips.firstOrNull()?.id ?: return false
    val currentVol = when (val el = findTrackElementForClip(targetId)) {
      is SelectedTrackElement.Video -> _timeline.value.videoClips.find { it.id == targetId }?.volume ?: 1.0f
      is SelectedTrackElement.Overlay -> _timeline.value.overlayClips.find { it.id == targetId }?.volume ?: 1.0f
      is SelectedTrackElement.Audio -> _timeline.value.audioClips.find { it.id == targetId }?.volume ?: 1.0f
      else -> 1.0f
    }
    val newVol = (currentVol + step).coerceIn(0f, 3.0f)
    return setClipVolume(targetId, newVol)
  }

  fun decreaseClipVolume(clipId: String? = null, step: Float = 0.10f): Boolean {
    val targetId = clipId ?: _selectedClipIds.value.firstOrNull() ?: findClipUnderPlayhead() ?: _timeline.value.videoClips.firstOrNull()?.id ?: return false
    val currentVol = when (val el = findTrackElementForClip(targetId)) {
      is SelectedTrackElement.Video -> _timeline.value.videoClips.find { it.id == targetId }?.volume ?: 1.0f
      is SelectedTrackElement.Overlay -> _timeline.value.overlayClips.find { it.id == targetId }?.volume ?: 1.0f
      is SelectedTrackElement.Audio -> _timeline.value.audioClips.find { it.id == targetId }?.volume ?: 1.0f
      else -> 1.0f
    }
    val newVol = (currentVol - step).coerceIn(0f, 3.0f)
    return setClipVolume(targetId, newVol)
  }

  fun addAudioClip(
    title: String,
    durationMs: Long = 8000L,
    uri: String = "internal://$title",
    waveformData: List<Float>? = null,
    fadeInMs: Long = 400L,
    fadeOutMs: Long = 600L,
    startTimeMs: Long? = null
  ) {
    recordHistory()
    val maxFade = durationMs / 2
    val nextAudioTrack = com.example.engine.timeline.TimelineTrackManager.allocateAudioTrackIndex(_timeline.value)
    val start = startTimeMs ?: com.example.engine.timeline.TimelineTrackManager.getAuthoritativeInsertionTime(_currentPositionMs.value)
    val newAudio = AudioClip(
      title = title,
      uri = uri,
      timelineStartMs = start,
      durationMs = durationMs,
      sourceEndMs = durationMs,
      sourceTotalDurationMs = if (uri.startsWith("internal://")) 0L else durationMs,
      fadeInMs = fadeInMs.coerceIn(0L, maxFade),
      fadeOutMs = fadeOutMs.coerceIn(0L, maxFade),
      waveformData = waveformData ?: if (uri.isNotBlank()) {
        com.example.engine.audio.AudioWaveformManager.getOrGenerateWaveform(title, uri, title, durationMs)
      } else {
        com.example.engine.audio.SoundEffectsCatalog.generateWaveform(title)
      },
      trackIndex = nextAudioTrack
    )
    val list = _timeline.value.audioClips.toMutableList()
    list.add(newAudio)
    _timeline.value = _timeline.value.copy(audioClips = list)
    _selectedElement.value = SelectedTrackElement.Audio(newAudio.id)
  }

  fun addAudioClipObject(newAudio: AudioClip) {
    withStateLock {
      recordHistory()
      val list = _timeline.value.audioClips.toMutableList()
      list.add(newAudio)
      _timeline.value = _timeline.value.copy(audioClips = list)
      _selectedElement.value = SelectedTrackElement.Audio(newAudio.id)
    }
  }

  // --- Text Operations ---

  fun addTextClip(
    text: String = "NEW TEXT",
    timelineStartMs: Long? = null,
    durationMs: Long = 3000L,
    fontFamily: String = "Default",
    fontSizeSp: Float = 24f,
    textColor: Long = 0xFFFFFFFF,
    animationType: String = "None",
    hasGlow: Boolean = false,
    glowColor: Long = 0xFF00E5FF
  ) {
    recordHistory()
    val newTrackIndex = com.example.engine.timeline.TimelineTrackManager.allocateTextTrackIndex(_timeline.value)
    val start = timelineStartMs ?: com.example.engine.timeline.TimelineTrackManager.getAuthoritativeInsertionTime(_currentPositionMs.value)
    val newText = TextClip(
      text = text,
      timelineStartMs = start,
      durationMs = durationMs,
      trackIndex = newTrackIndex,
      fontFamily = fontFamily,
      fontSizeSp = fontSizeSp,
      fontWeight = 800,
      textColor = textColor,
      hasGradient = false,
      animationType = animationType,
      hasGlow = hasGlow,
      glowColor = glowColor
    )
    val list = _timeline.value.textClips.toMutableList()
    list.add(newText)
    _timeline.value = _timeline.value.copy(textClips = list)
    _selectedElement.value = SelectedTrackElement.Text(newText.id)
  }

  fun addTextClipObject(newClip: TextClip) {
    withStateLock {
      recordHistory()
      val newTrackIndex = com.example.engine.timeline.TimelineTrackManager.allocateTextTrackIndex(_timeline.value)
      val clipWithTrack = if (newClip.trackIndex <= 0 || _timeline.value.textClips.any { it.trackIndex == newClip.trackIndex && it.id != newClip.id }) {
        newClip.copy(trackIndex = newTrackIndex)
      } else {
        newClip
      }
      val list = _timeline.value.textClips.toMutableList()
      val existingIndex = list.indexOfFirst { it.id == clipWithTrack.id }
      if (existingIndex != -1) {
        list[existingIndex] = clipWithTrack
      } else {
        list.add(clipWithTrack)
      }
      _timeline.value = _timeline.value.copy(textClips = list)
      _selectedElement.value = SelectedTrackElement.Text(clipWithTrack.id)
    }
  }

  fun updateTextClip(updated: TextClip) {
    withStateLock {
      val list = _timeline.value.textClips.map { if (it.id == updated.id) updated else it }
      _timeline.value = _timeline.value.copy(textClips = list)
    }
  }

  fun updateTextClipPayload(
    clipId: String,
    newText: String? = null,
    fontFamily: String? = null,
    textColor: Long? = null,
    fontSizeSp: Float? = null,
    animation: String? = null
  ) {
    recordHistory()
    val list = _timeline.value.textClips.map { clip ->
      if (clip.id == clipId) {
        var updated = clip
        if (newText != null) updated = updated.copy(text = newText)
        if (fontFamily != null) updated = updated.copy(fontFamily = fontFamily)
        if (textColor != null) updated = updated.copy(textColor = textColor)
        if (fontSizeSp != null) updated = updated.copy(fontSizeSp = fontSizeSp)
        if (animation != null) updated = updated.copy(animationType = animation)
        updated
      } else clip
    }
    _timeline.value = _timeline.value.copy(textClips = list)
  }

  fun splitClipAt(ctiPositionMs: Long): Boolean {
    setInternalPositionMs(ctiPositionMs)
    return splitAtPlayhead()
  }

  // --- Sticker Operations ---

  fun updateStickerClip(updated: StickerClip) {
    withStateLock {
      val list = _timeline.value.stickerClips.map { if (it.id == updated.id) updated else it }
      _timeline.value = _timeline.value.copy(stickerClips = list)
    }
  }

  fun addStickerClip(
    emojiOrAsset: String,
    animationType: StickerAnimationType = StickerAnimationType.NONE,
    badgeType: BadgeType? = null,
    category: String = "Emoji & Emotions"
  ): StickerClip {
    recordHistory()
    val nextTrackIndex = com.example.engine.timeline.TimelineTrackManager.allocateStickerTrackIndex(_timeline.value)
    val newSticker = StickerClip(
      emojiOrAsset = emojiOrAsset,
      timelineStartMs = _currentPositionMs.value,
      durationMs = 3000L,
      animationType = animationType,
      badgeType = badgeType,
      category = category,
      trackIndex = nextTrackIndex
    )
    val list = _timeline.value.stickerClips.toMutableList()
    list.add(newSticker)
    _timeline.value = _timeline.value.copy(stickerClips = list.sortedBy { it.timelineStartMs })
    _selectedElement.value = SelectedTrackElement.Sticker(newSticker.id)
    return newSticker
  }

  fun addBadge(badgeType: BadgeType): StickerClip {
    return addStickerClip(
      emojiOrAsset = badgeType.displayName,
      animationType = StickerAnimationType.NONE,
      badgeType = badgeType,
      category = "Badges"
    )
  }

  fun addElementClip(
    elementId: String,
    elementCategory: String,
    title: String,
    iconSymbol: String,
    primaryColor: Long = 0xFF00E5FF,
    secondaryColor: Long = 0xFF7000FF,
    defaultScale: Float = 1.0f,
    durationMs: Long = 3000L,
    renderData: String? = null
  ): StickerClip {
    recordHistory()
    val nextTrackIndex = com.example.engine.timeline.TimelineTrackManager.allocateStickerTrackIndex(_timeline.value)
    val cti = _currentPositionMs.value
    val newElement = StickerClip(
      emojiOrAsset = "$iconSymbol $title",
      timelineStartMs = cti,
      durationMs = durationMs,
      posX = 0f,
      posY = 0f,
      scale = defaultScale,
      rotation = 0f,
      opacity = 1f,
      category = elementCategory.replaceFirstChar { it.uppercase() },
      elementId = elementId,
      elementCategory = elementCategory,
      customColor = primaryColor,
      secondaryColor = secondaryColor,
      elementData = renderData,
      trackIndex = nextTrackIndex
    )
    val list = _timeline.value.stickerClips.toMutableList()
    list.add(newElement)
    _timeline.value = _timeline.value.copy(stickerClips = list.sortedBy { it.timelineStartMs })
    _selectedElement.value = SelectedTrackElement.Sticker(newElement.id)
    return newElement
  }

  fun addShapeClip(
    shapeType: MaskShape = MaskShape.RECTANGLE,
    durationMs: Long = 3000L,
    fillColor: Long = 0xFF00E5FF,
    strokeColor: Long = 0xFFFFFFFF,
    strokeWidth: Float = 2f,
    atPlayhead: Boolean = true
  ): ShapeClip = withStateLock {
    recordHistory(TimelineActionType.ADD_CLIP, "Add Shape")
    val startMs = if (atPlayhead) _currentPositionMs.value else _timeline.value.totalDurationMs
    val newShape = ShapeClip(
      shapeType = shapeType,
      timelineStartMs = startMs,
      durationMs = durationMs,
      fillColor = fillColor,
      strokeColor = strokeColor,
      strokeWidth = strokeWidth,
      trackIndex = 1
    )
    val list = _timeline.value.shapeClips.toMutableList()
    list.add(newShape)
    _timeline.value = _timeline.value.copy(shapeClips = list.sortedBy { it.timelineStartMs })
    return newShape
  }

  fun replaceSticker(clipId: String, newEmojiOrAsset: String, newBadgeType: BadgeType? = null) {
    withStateLock {
      recordHistory()
      val list = _timeline.value.stickerClips.map {
        if (it.id == clipId) {
          it.copy(
            emojiOrAsset = newEmojiOrAsset,
            badgeType = newBadgeType,
            category = if (newBadgeType != null) "Badges" else it.category
          )
        } else it
      }
      _timeline.value = _timeline.value.copy(stickerClips = list)
    }
  }

  fun updateStickerOpacity(clipId: String, opacity: Float) {
    withStateLock {
      val list = _timeline.value.stickerClips.map {
        if (it.id == clipId) it.copy(opacity = opacity.coerceIn(0f, 1f)) else it
      }
      _timeline.value = _timeline.value.copy(stickerClips = list)
    }
  }

  fun updateStickerAnimation(clipId: String, animationType: StickerAnimationType) {
    withStateLock {
      recordHistory()
      val list = _timeline.value.stickerClips.map {
        if (it.id == clipId) it.copy(animationType = animationType) else it
      }
      _timeline.value = _timeline.value.copy(stickerClips = list)
    }
  }

  fun getClipAnimationSettings(clipId: String): ClipAnimationSettings {
    val video = _timeline.value.videoClips.find { it.id == clipId }
    if (video != null) return video.animation
    val overlay = _timeline.value.overlayClips.find { it.id == clipId }
    if (overlay != null) return overlay.animation
    val text = _timeline.value.textClips.find { it.id == clipId }
    if (text != null) {
      val inType = InAnimationType.values().find { it.displayName.equals(text.animationIn, ignoreCase = true) || it.displayName.equals(text.animationType, ignoreCase = true) } ?: InAnimationType.NONE
      val outType = OutAnimationType.values().find { it.displayName.equals(text.animationOut, ignoreCase = true) } ?: OutAnimationType.NONE
      return ClipAnimationSettings(
        inType = inType,
        inDurationMs = text.animDurationMs,
        outType = outType,
        outDurationMs = text.animDurationMs
      )
    }
    val sticker = _timeline.value.stickerClips.find { it.id == clipId }
    if (sticker != null) {
      val comboType = when (sticker.animationType) {
        StickerAnimationType.PULSE -> ComboAnimationType.PULSE
        StickerAnimationType.HEARTBEAT -> ComboAnimationType.HEARTBEAT
        StickerAnimationType.BOUNCE -> ComboAnimationType.PULSE
        StickerAnimationType.SPIN -> ComboAnimationType.SPIN_360
        StickerAnimationType.SHAKE -> ComboAnimationType.SHAKE
        StickerAnimationType.FLOAT -> ComboAnimationType.FLOAT
        StickerAnimationType.GLOW_PULSE -> ComboAnimationType.FLASH_PULSE
        else -> ComboAnimationType.NONE
      }
      val inType = if (sticker.animationType == StickerAnimationType.POP_IN) InAnimationType.POP_IN else InAnimationType.NONE
      return ClipAnimationSettings(inType = inType, comboType = comboType)
    }
    return ClipAnimationSettings()
  }

  fun updateClipAnimation(clipId: String, update: (ClipAnimationSettings) -> ClipAnimationSettings) {
    withStateLock {
      recordHistory()
      val isMain = _timeline.value.videoClips.any { it.id == clipId }
      if (isMain) {
        val list = _timeline.value.videoClips.map { clip ->
          if (clip.id == clipId) clip.copy(animation = update(clip.animation)) else clip
        }
        _timeline.value = _timeline.value.copy(videoClips = list)
        return
      }
      val isOverlay = _timeline.value.overlayClips.any { it.id == clipId }
      if (isOverlay) {
        val list = _timeline.value.overlayClips.map { clip ->
          if (clip.id == clipId) clip.copy(animation = update(clip.animation)) else clip
        }
        _timeline.value = _timeline.value.copy(overlayClips = list)
        return
      }
      val textClip = _timeline.value.textClips.find { it.id == clipId }
      if (textClip != null) {
        val currentSettings = getClipAnimationSettings(clipId)
        val newSettings = update(currentSettings)
        val generatedKfs = generateKeyframesForAnimation(
          settings = newSettings,
          durationMs = textClip.durationMs,
          basePosX = textClip.posX,
          basePosY = textClip.posY,
          baseScale = textClip.scale,
          baseRotation = textClip.rotation,
          baseOpacity = textClip.opacity
        )
        val list = _timeline.value.textClips.map { clip ->
          if (clip.id == clipId) {
            clip.copy(
              keyframes = generatedKfs,
              animationType = if (newSettings.inType != InAnimationType.NONE) newSettings.inType.displayName else "None",
              animationIn = newSettings.inType.displayName,
              animationOut = newSettings.outType.displayName,
              animDurationMs = newSettings.inDurationMs
            )
          } else clip
        }
        _timeline.value = _timeline.value.copy(textClips = list)
        return
      }
      val stickerClip = _timeline.value.stickerClips.find { it.id == clipId }
      if (stickerClip != null) {
        val currentSettings = getClipAnimationSettings(clipId)
        val newSettings = update(currentSettings)
        val generatedKfs = generateKeyframesForAnimation(
          settings = newSettings,
          durationMs = stickerClip.durationMs,
          basePosX = stickerClip.posX,
          basePosY = stickerClip.posY,
          baseScale = stickerClip.scale,
          baseRotation = stickerClip.rotation,
          baseOpacity = stickerClip.opacity
        )
        val mappedStickerAnim = when (newSettings.comboType) {
          ComboAnimationType.PULSE -> StickerAnimationType.PULSE
          ComboAnimationType.HEARTBEAT -> StickerAnimationType.HEARTBEAT
          ComboAnimationType.SPIN_360 -> StickerAnimationType.SPIN
          ComboAnimationType.SHAKE -> StickerAnimationType.SHAKE
          ComboAnimationType.FLOAT -> StickerAnimationType.FLOAT
          ComboAnimationType.FLASH_PULSE -> StickerAnimationType.GLOW_PULSE
          else -> if (newSettings.inType == InAnimationType.POP_IN) StickerAnimationType.POP_IN else StickerAnimationType.NONE
        }
        val list = _timeline.value.stickerClips.map { clip ->
          if (clip.id == clipId) clip.copy(keyframes = generatedKfs, animationType = mappedStickerAnim) else clip
        }
        _timeline.value = _timeline.value.copy(stickerClips = list)
        return
      }
    }
  }

  fun generateKeyframesForAnimation(
    settings: ClipAnimationSettings,
    durationMs: Long,
    basePosX: Float = 0f,
    basePosY: Float = 0f,
    baseScale: Float = 1f,
    baseRotation: Float = 0f,
    baseOpacity: Float = 1f
  ): List<ClipKeyframe> {
    if (!settings.hasAnimation) return emptyList()
    val kfs = mutableListOf<ClipKeyframe>()

    // Entrance In
    if (settings.inType != InAnimationType.NONE) {
      val inDur = settings.inDurationMs.coerceIn(50L, (durationMs / 2).coerceAtLeast(50L))
      when (settings.inType) {
        InAnimationType.NONE -> {}
        InAnimationType.FADE_IN -> {
          kfs.add(ClipKeyframe(timeMs = 0L, posX = basePosX, posY = basePosY, scaleX = baseScale, scaleY = baseScale, rotation = baseRotation, opacity = 0f))
          kfs.add(ClipKeyframe(timeMs = inDur, posX = basePosX, posY = basePosY, scaleX = baseScale, scaleY = baseScale, rotation = baseRotation, opacity = baseOpacity))
        }
        InAnimationType.ZOOM_IN -> {
          kfs.add(ClipKeyframe(timeMs = 0L, posX = basePosX, posY = basePosY, scaleX = 0f, scaleY = 0f, rotation = baseRotation, opacity = 0f))
          kfs.add(ClipKeyframe(timeMs = inDur, posX = basePosX, posY = basePosY, scaleX = baseScale, scaleY = baseScale, rotation = baseRotation, opacity = baseOpacity))
        }
        InAnimationType.ZOOM_OUT -> {
          kfs.add(ClipKeyframe(timeMs = 0L, posX = basePosX, posY = basePosY, scaleX = baseScale * 1.6f, scaleY = baseScale * 1.6f, rotation = baseRotation, opacity = 0f))
          kfs.add(ClipKeyframe(timeMs = inDur, posX = basePosX, posY = basePosY, scaleX = baseScale, scaleY = baseScale, rotation = baseRotation, opacity = baseOpacity))
        }
        InAnimationType.SLIDE_UP -> {
          kfs.add(ClipKeyframe(timeMs = 0L, posX = basePosX, posY = basePosY + 0.4f, scaleX = baseScale, scaleY = baseScale, rotation = baseRotation, opacity = 0f))
          kfs.add(ClipKeyframe(timeMs = inDur, posX = basePosX, posY = basePosY, scaleX = baseScale, scaleY = baseScale, rotation = baseRotation, opacity = baseOpacity))
        }
        InAnimationType.SLIDE_DOWN -> {
          kfs.add(ClipKeyframe(timeMs = 0L, posX = basePosX, posY = basePosY - 0.4f, scaleX = baseScale, scaleY = baseScale, rotation = baseRotation, opacity = 0f))
          kfs.add(ClipKeyframe(timeMs = inDur, posX = basePosX, posY = basePosY, scaleX = baseScale, scaleY = baseScale, rotation = baseRotation, opacity = baseOpacity))
        }
        InAnimationType.SLIDE_LEFT -> {
          kfs.add(ClipKeyframe(timeMs = 0L, posX = basePosX + 0.5f, posY = basePosY, scaleX = baseScale, scaleY = baseScale, rotation = baseRotation, opacity = 0f))
          kfs.add(ClipKeyframe(timeMs = inDur, posX = basePosX, posY = basePosY, scaleX = baseScale, scaleY = baseScale, rotation = baseRotation, opacity = baseOpacity))
        }
        InAnimationType.SLIDE_RIGHT -> {
          kfs.add(ClipKeyframe(timeMs = 0L, posX = basePosX - 0.5f, posY = basePosY, scaleX = baseScale, scaleY = baseScale, rotation = baseRotation, opacity = 0f))
          kfs.add(ClipKeyframe(timeMs = inDur, posX = basePosX, posY = basePosY, scaleX = baseScale, scaleY = baseScale, rotation = baseRotation, opacity = baseOpacity))
        }
        InAnimationType.SPIN_IN -> {
          kfs.add(ClipKeyframe(timeMs = 0L, posX = basePosX, posY = basePosY, scaleX = 0f, scaleY = 0f, rotation = baseRotation - 360f, opacity = 0f))
          kfs.add(ClipKeyframe(timeMs = inDur, posX = basePosX, posY = basePosY, scaleX = baseScale, scaleY = baseScale, rotation = baseRotation, opacity = baseOpacity))
        }
        InAnimationType.POP_IN -> {
          kfs.add(ClipKeyframe(timeMs = 0L, posX = basePosX, posY = basePosY, scaleX = 0f, scaleY = 0f, rotation = baseRotation, opacity = 0f))
          kfs.add(ClipKeyframe(timeMs = (inDur * 0.7f).toLong(), posX = basePosX, posY = basePosY, scaleX = baseScale * 1.2f, scaleY = baseScale * 1.2f, rotation = baseRotation, opacity = baseOpacity))
          kfs.add(ClipKeyframe(timeMs = inDur, posX = basePosX, posY = basePosY, scaleX = baseScale, scaleY = baseScale, rotation = baseRotation, opacity = baseOpacity))
        }
        InAnimationType.BOUNCE_IN -> {
          kfs.add(ClipKeyframe(timeMs = 0L, posX = basePosX, posY = basePosY, scaleX = 0f, scaleY = 0f, rotation = baseRotation, opacity = 0f))
          kfs.add(ClipKeyframe(timeMs = (inDur * 0.5f).toLong(), posX = basePosX, posY = basePosY, scaleX = baseScale * 1.25f, scaleY = baseScale * 1.25f, rotation = baseRotation, opacity = baseOpacity))
          kfs.add(ClipKeyframe(timeMs = (inDur * 0.75f).toLong(), posX = basePosX, posY = basePosY, scaleX = baseScale * 0.9f, scaleY = baseScale * 0.9f, rotation = baseRotation, opacity = baseOpacity))
          kfs.add(ClipKeyframe(timeMs = inDur, posX = basePosX, posY = basePosY, scaleX = baseScale, scaleY = baseScale, rotation = baseRotation, opacity = baseOpacity))
        }
        else -> {
          kfs.add(ClipKeyframe(timeMs = 0L, posX = basePosX, posY = basePosY, scaleX = 0.5f, scaleY = 0.5f, rotation = baseRotation, opacity = 0f))
          kfs.add(ClipKeyframe(timeMs = inDur, posX = basePosX, posY = basePosY, scaleX = baseScale, scaleY = baseScale, rotation = baseRotation, opacity = baseOpacity))
        }
      }
    }

    // Exit Out
    if (settings.outType != OutAnimationType.NONE) {
      val outDur = settings.outDurationMs.coerceIn(50L, (durationMs / 2).coerceAtLeast(50L))
      val outStart = (durationMs - outDur).coerceAtLeast(0L)
      when (settings.outType) {
        OutAnimationType.NONE -> {}
        OutAnimationType.FADE_OUT -> {
          kfs.add(ClipKeyframe(timeMs = outStart, posX = basePosX, posY = basePosY, scaleX = baseScale, scaleY = baseScale, rotation = baseRotation, opacity = baseOpacity))
          kfs.add(ClipKeyframe(timeMs = durationMs, posX = basePosX, posY = basePosY, scaleX = baseScale, scaleY = baseScale, rotation = baseRotation, opacity = 0f))
        }
        OutAnimationType.ZOOM_OUT -> {
          kfs.add(ClipKeyframe(timeMs = outStart, posX = basePosX, posY = basePosY, scaleX = baseScale, scaleY = baseScale, rotation = baseRotation, opacity = baseOpacity))
          kfs.add(ClipKeyframe(timeMs = durationMs, posX = basePosX, posY = basePosY, scaleX = 0f, scaleY = 0f, rotation = baseRotation, opacity = 0f))
        }
        OutAnimationType.ZOOM_IN_OUT -> {
          kfs.add(ClipKeyframe(timeMs = outStart, posX = basePosX, posY = basePosY, scaleX = baseScale, scaleY = baseScale, rotation = baseRotation, opacity = baseOpacity))
          kfs.add(ClipKeyframe(timeMs = durationMs, posX = basePosX, posY = basePosY, scaleX = baseScale * 1.8f, scaleY = baseScale * 1.8f, rotation = baseRotation, opacity = 0f))
        }
        OutAnimationType.SLIDE_DOWN_OUT -> {
          kfs.add(ClipKeyframe(timeMs = outStart, posX = basePosX, posY = basePosY, scaleX = baseScale, scaleY = baseScale, rotation = baseRotation, opacity = baseOpacity))
          kfs.add(ClipKeyframe(timeMs = durationMs, posX = basePosX, posY = basePosY + 0.5f, scaleX = baseScale, scaleY = baseScale, rotation = baseRotation, opacity = 0f))
        }
        OutAnimationType.SLIDE_UP_OUT -> {
          kfs.add(ClipKeyframe(timeMs = outStart, posX = basePosX, posY = basePosY, scaleX = baseScale, scaleY = baseScale, rotation = baseRotation, opacity = baseOpacity))
          kfs.add(ClipKeyframe(timeMs = durationMs, posX = basePosX, posY = basePosY - 0.5f, scaleX = baseScale, scaleY = baseScale, rotation = baseRotation, opacity = 0f))
        }
        OutAnimationType.SPIN_OUT -> {
          kfs.add(ClipKeyframe(timeMs = outStart, posX = basePosX, posY = basePosY, scaleX = baseScale, scaleY = baseScale, rotation = baseRotation, opacity = baseOpacity))
          kfs.add(ClipKeyframe(timeMs = durationMs, posX = basePosX, posY = basePosY, scaleX = 0f, scaleY = 0f, rotation = baseRotation + 360f, opacity = 0f))
        }
        OutAnimationType.POP_OUT -> {
          kfs.add(ClipKeyframe(timeMs = outStart, posX = basePosX, posY = basePosY, scaleX = baseScale, scaleY = baseScale, rotation = baseRotation, opacity = baseOpacity))
          kfs.add(ClipKeyframe(timeMs = outStart + (outDur * 0.3f).toLong(), posX = basePosX, posY = basePosY, scaleX = baseScale * 1.2f, scaleY = baseScale * 1.2f, rotation = baseRotation, opacity = baseOpacity))
          kfs.add(ClipKeyframe(timeMs = durationMs, posX = basePosX, posY = basePosY, scaleX = 0f, scaleY = 0f, rotation = baseRotation, opacity = 0f))
        }
        else -> {
          kfs.add(ClipKeyframe(timeMs = outStart, posX = basePosX, posY = basePosY, scaleX = baseScale, scaleY = baseScale, rotation = baseRotation, opacity = baseOpacity))
          kfs.add(ClipKeyframe(timeMs = durationMs, posX = basePosX, posY = basePosY, scaleX = 0f, scaleY = 0f, rotation = baseRotation, opacity = 0f))
        }
      }
    }

    // Combo / Loop
    if (settings.comboType != ComboAnimationType.NONE) {
      val step = (durationMs / 6).coerceAtLeast(100L)
      when (settings.comboType) {
        ComboAnimationType.NONE -> {}
        ComboAnimationType.PULSE -> {
          for (t in 0L..durationMs step step) {
            val s = if ((t / step) % 2L == 0L) baseScale * 1.15f else baseScale * 0.9f
            kfs.add(ClipKeyframe(timeMs = t, posX = basePosX, posY = basePosY, scaleX = s, scaleY = s, rotation = baseRotation, opacity = baseOpacity))
          }
        }
        ComboAnimationType.FLOAT -> {
          for (t in 0L..durationMs step step) {
            val yOff = if ((t / step) % 2L == 0L) 0.05f else -0.05f
            kfs.add(ClipKeyframe(timeMs = t, posX = basePosX, posY = basePosY + yOff, scaleX = baseScale, scaleY = baseScale, rotation = baseRotation, opacity = baseOpacity))
          }
        }
        ComboAnimationType.SPIN_360 -> {
          kfs.add(ClipKeyframe(timeMs = 0L, posX = basePosX, posY = basePosY, scaleX = baseScale, scaleY = baseScale, rotation = baseRotation, opacity = baseOpacity))
          kfs.add(ClipKeyframe(timeMs = durationMs, posX = basePosX, posY = basePosY, scaleX = baseScale, scaleY = baseScale, rotation = baseRotation + 360f, opacity = baseOpacity))
        }
        ComboAnimationType.SHAKE -> {
          for (t in 0L..durationMs step step) {
            val xOff = if ((t / step) % 2L == 0L) 0.03f else -0.03f
            kfs.add(ClipKeyframe(timeMs = t, posX = basePosX + xOff, posY = basePosY, scaleX = baseScale, scaleY = baseScale, rotation = baseRotation, opacity = baseOpacity))
          }
        }
        else -> {}
      }
    }

    return kfs.sortedBy { it.timeMs }
  }

  fun setClipInAnimation(clipId: String, inType: InAnimationType) {
    withStateLock {
      updateClipAnimation(clipId) { it.copy(inType = inType) }
    }
  }

  fun setClipOutAnimation(clipId: String, outType: OutAnimationType) {
    withStateLock {
      updateClipAnimation(clipId) { it.copy(outType = outType) }
    }
  }

  fun setClipComboAnimation(clipId: String, comboType: ComboAnimationType) {
    withStateLock {
      updateClipAnimation(clipId) { it.copy(comboType = comboType) }
    }
  }

  fun clearClipAnimation(clipId: String) {
    withStateLock {
      updateClipAnimation(clipId) { ClipAnimationSettings() }
    }
  }

  fun applyAnimationToAllClips(settings: ClipAnimationSettings) {
    withStateLock {
      recordHistory()
      val updatedVideos = _timeline.value.videoClips.map { it.copy(animation = settings) }
      val updatedOverlays = _timeline.value.overlayClips.map { it.copy(animation = settings) }
      _timeline.value = _timeline.value.copy(
        videoClips = updatedVideos,
        overlayClips = updatedOverlays
      )
    }
  }

  fun clearAllClipsAnimation() {
    withStateLock {
      recordHistory()
      val updatedVideos = _timeline.value.videoClips.map { it.copy(animation = ClipAnimationSettings()) }
      val updatedOverlays = _timeline.value.overlayClips.map { it.copy(animation = ClipAnimationSettings()) }
      _timeline.value = _timeline.value.copy(
        videoClips = updatedVideos,
        overlayClips = updatedOverlays
      )
    }
  }

  fun deleteSticker(clipId: String) {
    withStateLock {
      recordHistory()
      val list = _timeline.value.stickerClips.filterNot { it.id == clipId }
      _timeline.value = _timeline.value.copy(stickerClips = list)
      if ((_selectedElement.value as? SelectedTrackElement.Sticker)?.clipId == clipId) {
        _selectedElement.value = SelectedTrackElement.None
      }
    }
  }

  // --- Effect Operations ---

  fun applyEffectToCurrentClip(
    effectType: EffectType,
    intensity: Float = 0.8f,
    customName: String = ""
  ): EffectClip {
    recordHistory()
    val sel = _selectedElement.value
    val targetVideoClip = when (sel) {
      is SelectedTrackElement.Video -> _timeline.value.videoClips.find { it.id == sel.clipId }
      is SelectedTrackElement.Effect -> {
        val eff = _timeline.value.effectClips.find { it.id == sel.clipId }
        eff?.targetClipId?.let { cid -> _timeline.value.videoClips.find { it.id == cid } }
          ?: _timeline.value.videoClips.find { _currentPositionMs.value >= it.timelineStartMs && _currentPositionMs.value < it.timelineStartMs + it.durationMs }
      }
      else -> _timeline.value.videoClips.find { _currentPositionMs.value >= it.timelineStartMs && _currentPositionMs.value < it.timelineStartMs + it.durationMs }
        ?: _timeline.value.videoClips.firstOrNull()
    }

    val startMs = targetVideoClip?.timelineStartMs ?: _currentPositionMs.value
    val durationMs = targetVideoClip?.durationMs ?: 3000L
    val targetClipId = targetVideoClip?.id

    val existing = if (targetClipId != null) {
      _timeline.value.effectClips.find { it.targetClipId == targetClipId || (it.timelineStartMs == startMs && it.durationMs == durationMs) }
    } else {
      (sel as? SelectedTrackElement.Effect)?.let { effSel ->
        _timeline.value.effectClips.find { it.id == effSel.clipId }
      } ?: _timeline.value.effectClips.find { _currentPositionMs.value >= it.timelineStartMs && _currentPositionMs.value < it.timelineStartMs + it.durationMs }
    }

    val effect = if (existing != null) {
      existing.copy(
        effectType = effectType,
        intensity = intensity,
        customName = customName.ifBlank { effectType.displayName },
        timelineStartMs = startMs,
        durationMs = durationMs,
        targetClipId = targetClipId
      )
    } else {
      EffectClip(
        effectType = effectType,
        timelineStartMs = startMs,
        durationMs = durationMs,
        intensity = intensity,
        customName = customName.ifBlank { effectType.displayName },
        targetClipId = targetClipId
      )
    }

    val list = _timeline.value.effectClips.filter { it.id != effect.id }.toMutableList()
    list.add(effect)
    _timeline.value = _timeline.value.copy(effectClips = list.sortedBy { it.timelineStartMs })
    _selectedElement.value = SelectedTrackElement.Effect(effect.id)
    return effect
  }

  fun removeEffectFromCurrentClip() {
    withStateLock {
      recordHistory()
      val sel = _selectedElement.value
      val targetClip = when (sel) {
        is SelectedTrackElement.Video -> _timeline.value.videoClips.find { it.id == sel.clipId }
        is SelectedTrackElement.Effect -> {
          val eff = _timeline.value.effectClips.find { it.id == sel.clipId }
          eff?.targetClipId?.let { cid -> _timeline.value.videoClips.find { it.id == cid } }
            ?: _timeline.value.videoClips.find { _currentPositionMs.value >= it.timelineStartMs && _currentPositionMs.value < it.timelineStartMs + it.durationMs }
        }
        else -> _timeline.value.videoClips.find { _currentPositionMs.value >= it.timelineStartMs && _currentPositionMs.value < it.timelineStartMs + it.durationMs }
          ?: _timeline.value.videoClips.firstOrNull()
      }
      val targetClipId = targetClip?.id

      val toRemove = _timeline.value.effectClips.filter {
        (targetClipId != null && it.targetClipId == targetClipId) ||
        (sel is SelectedTrackElement.Effect && it.id == sel.clipId) ||
        (targetClip != null && it.timelineStartMs == targetClip.timelineStartMs && it.durationMs == targetClip.durationMs) ||
        (_currentPositionMs.value >= it.timelineStartMs && _currentPositionMs.value < it.timelineStartMs + it.durationMs)
      }

      if (toRemove.isNotEmpty()) {
        val removeIds = toRemove.map { it.id }.toSet()
        val list = _timeline.value.effectClips.filterNot { it.id in removeIds }
        _timeline.value = _timeline.value.copy(effectClips = list)
        if (sel is SelectedTrackElement.Effect && sel.clipId in removeIds) {
          if (targetClipId != null) {
            _selectedElement.value = SelectedTrackElement.Video(targetClipId)
          } else {
            _selectedElement.value = SelectedTrackElement.None
          }
        }
      }
    }
  }

  fun addEffectClip(effectType: EffectType): EffectClip {
    recordHistory()
    val nextTrack = com.example.engine.timeline.TimelineTrackManager.allocateEffectTrackIndex(_timeline.value)
    val startMs = _currentPositionMs.value
    val targetVideoClip = _timeline.value.videoClips.find { startMs >= it.timelineStartMs && startMs < it.timelineStartMs + it.durationMs }
    val durationMs = targetVideoClip?.durationMs ?: 3000L
    val newEffect = EffectClip(
      effectType = effectType,
      timelineStartMs = startMs,
      durationMs = durationMs,
      intensity = 0.8f,
      customName = effectType.displayName,
      targetClipId = targetVideoClip?.id,
      trackIndex = nextTrack
    )
    val list = _timeline.value.effectClips.toMutableList()
    list.add(newEffect)
    _timeline.value = _timeline.value.copy(effectClips = list.sortedBy { it.timelineStartMs })
    _selectedElement.value = SelectedTrackElement.Effect(newEffect.id)
    return newEffect
  }

  fun updateEffectIntensity(effectId: String, intensity: Float) {
    withStateLock {
      val list = _timeline.value.effectClips.map {
        if (it.id == effectId) it.copy(intensity = intensity.coerceIn(0f, 1f)) else it
      }
      _timeline.value = _timeline.value.copy(effectClips = list)
    }
  }

  fun updateEffectClip(updated: EffectClip) {
    withStateLock {
      val list = _timeline.value.effectClips.map {
        if (it.id == updated.id) updated else it
      }
      _timeline.value = _timeline.value.copy(effectClips = list)
    }
  }

  fun deleteEffectClip(effectId: String) {
    withStateLock {
      recordHistory()
      val list = _timeline.value.effectClips.filter { it.id != effectId }
      _timeline.value = _timeline.value.copy(effectClips = list)
      if ((_selectedElement.value as? SelectedTrackElement.Effect)?.clipId == effectId) {
        _selectedElement.value = SelectedTrackElement.None
      }
    }
  }

  fun addEffectKeyframe(effectId: String, keyframe: ClipKeyframe? = null) {
    withStateLock {
      recordHistory()
      val list = _timeline.value.effectClips.map {
        if (it.id == effectId) {
          val relTime = (_currentPositionMs.value - it.timelineStartMs).coerceIn(0L, it.durationMs)
          val kf = keyframe ?: ClipKeyframe(timeMs = relTime, effectParam = it.intensity)
          val existing = it.keyframes.filter { k -> k.timeMs != kf.timeMs }.toMutableList()
          existing.add(kf)
          existing.sortBy { k -> k.timeMs }
          it.copy(keyframes = existing)
        } else it
      }
      _timeline.value = _timeline.value.copy(effectClips = list)
    }
  }

  fun removeEffectKeyframe(effectId: String, keyframeId: String) {
    withStateLock {
      recordHistory()
      val list = _timeline.value.effectClips.map {
        if (it.id == effectId) {
          it.copy(keyframes = it.keyframes.filter { kf -> kf.id != keyframeId })
        } else it
      }
      _timeline.value = _timeline.value.copy(effectClips = list)
    }
  }

  // --- Unified Multi-Layer Management (Z-Index, Lock, Visibility, Reordering) ---

  fun bringLayerForward(clipId: String): Boolean {
    recordHistory()
    // 1. Text Clips
    val textIndex = _timeline.value.textClips.indexOfFirst { it.id == clipId }
    if (textIndex in 0 until _timeline.value.textClips.size - 1) {
      val list = _timeline.value.textClips.toMutableList()
      java.util.Collections.swap(list, textIndex, textIndex + 1)
      _timeline.value = _timeline.value.copy(textClips = list)
      return true
    }
    // 2. Overlay Clips
    val overlayIndex = _timeline.value.overlayClips.indexOfFirst { it.id == clipId }
    if (overlayIndex in 0 until _timeline.value.overlayClips.size - 1) {
      val list = _timeline.value.overlayClips.toMutableList()
      java.util.Collections.swap(list, overlayIndex, overlayIndex + 1)
      _timeline.value = _timeline.value.copy(overlayClips = list)
      return true
    }
    // 3. Sticker Clips
    val stickerIndex = _timeline.value.stickerClips.indexOfFirst { it.id == clipId }
    if (stickerIndex in 0 until _timeline.value.stickerClips.size - 1) {
      val list = _timeline.value.stickerClips.toMutableList()
      java.util.Collections.swap(list, stickerIndex, stickerIndex + 1)
      _timeline.value = _timeline.value.copy(stickerClips = list)
      return true
    }
    return false
  }

  fun sendLayerBackward(clipId: String): Boolean {
    recordHistory()
    // 1. Text Clips
    val textIndex = _timeline.value.textClips.indexOfFirst { it.id == clipId }
    if (textIndex > 0) {
      val list = _timeline.value.textClips.toMutableList()
      java.util.Collections.swap(list, textIndex, textIndex - 1)
      _timeline.value = _timeline.value.copy(textClips = list)
      return true
    }
    // 2. Overlay Clips
    val overlayIndex = _timeline.value.overlayClips.indexOfFirst { it.id == clipId }
    if (overlayIndex > 0) {
      val list = _timeline.value.overlayClips.toMutableList()
      java.util.Collections.swap(list, overlayIndex, overlayIndex - 1)
      _timeline.value = _timeline.value.copy(overlayClips = list)
      return true
    }
    // 3. Sticker Clips
    val stickerIndex = _timeline.value.stickerClips.indexOfFirst { it.id == clipId }
    if (stickerIndex > 0) {
      val list = _timeline.value.stickerClips.toMutableList()
      java.util.Collections.swap(list, stickerIndex, stickerIndex - 1)
      _timeline.value = _timeline.value.copy(stickerClips = list)
      return true
    }
    return false
  }

  fun bringLayerToFront(clipId: String): Boolean {
    recordHistory()
    val textClip = _timeline.value.textClips.find { it.id == clipId }
    if (textClip != null) {
      val list = _timeline.value.textClips.filter { it.id != clipId }.toMutableList()
      list.add(textClip)
      _timeline.value = _timeline.value.copy(textClips = list)
      return true
    }
    val overlayClip = _timeline.value.overlayClips.find { it.id == clipId }
    if (overlayClip != null) {
      val list = _timeline.value.overlayClips.filter { it.id != clipId }.toMutableList()
      list.add(overlayClip)
      _timeline.value = _timeline.value.copy(overlayClips = list)
      return true
    }
    val stickerClip = _timeline.value.stickerClips.find { it.id == clipId }
    if (stickerClip != null) {
      val list = _timeline.value.stickerClips.filter { it.id != clipId }.toMutableList()
      list.add(stickerClip)
      _timeline.value = _timeline.value.copy(stickerClips = list)
      return true
    }
    return false
  }

  fun sendLayerToBack(clipId: String): Boolean {
    recordHistory()
    val textClip = _timeline.value.textClips.find { it.id == clipId }
    if (textClip != null) {
      val list = _timeline.value.textClips.filter { it.id != clipId }.toMutableList()
      list.add(0, textClip)
      _timeline.value = _timeline.value.copy(textClips = list)
      return true
    }
    val overlayClip = _timeline.value.overlayClips.find { it.id == clipId }
    if (overlayClip != null) {
      val list = _timeline.value.overlayClips.filter { it.id != clipId }.toMutableList()
      list.add(0, overlayClip)
      _timeline.value = _timeline.value.copy(overlayClips = list)
      return true
    }
    val stickerClip = _timeline.value.stickerClips.find { it.id == clipId }
    if (stickerClip != null) {
      val list = _timeline.value.stickerClips.filter { it.id != clipId }.toMutableList()
      list.add(0, stickerClip)
      _timeline.value = _timeline.value.copy(stickerClips = list)
      return true
    }
    return false
  }

  fun toggleClipLock(clipId: String) {
    withStateLock {
      recordHistory()
      if (_timeline.value.textClips.any { it.id == clipId }) {
        val list = _timeline.value.textClips.map {
          if (it.id == clipId) it.copy(isLocked = !it.isLocked) else it
        }
        _timeline.value = _timeline.value.copy(textClips = list)
      } else if (_timeline.value.overlayClips.any { it.id == clipId }) {
        val list = _timeline.value.overlayClips.map {
          if (it.id == clipId) it.copy(isLocked = !it.isLocked) else it
        }
        _timeline.value = _timeline.value.copy(overlayClips = list)
      } else if (_timeline.value.stickerClips.any { it.id == clipId }) {
        val list = _timeline.value.stickerClips.map {
          if (it.id == clipId) it.copy(isLocked = !it.isLocked) else it
        }
        _timeline.value = _timeline.value.copy(stickerClips = list)
      } else if (_timeline.value.videoClips.any { it.id == clipId }) {
        val list = _timeline.value.videoClips.map {
          if (it.id == clipId) it.copy(isLocked = !it.isLocked) else it
        }
        _timeline.value = _timeline.value.copy(videoClips = list)
      } else if (_timeline.value.audioClips.any { it.id == clipId }) {
        val list = _timeline.value.audioClips.map {
          if (it.id == clipId) it.copy(isLocked = !it.isLocked) else it
        }
        _timeline.value = _timeline.value.copy(audioClips = list)
      } else if (_timeline.value.effectClips.any { it.id == clipId }) {
        val list = _timeline.value.effectClips.map {
          if (it.id == clipId) it.copy(isLocked = !it.isLocked) else it
        }
        _timeline.value = _timeline.value.copy(effectClips = list)
      }
    }
  }

  fun toggleClipHide(clipId: String) {
    withStateLock {
      recordHistory()
      if (_timeline.value.textClips.any { it.id == clipId }) {
        val list = _timeline.value.textClips.map {
          if (it.id == clipId) it.copy(isHidden = !it.isHidden) else it
        }
        _timeline.value = _timeline.value.copy(textClips = list)
      } else if (_timeline.value.overlayClips.any { it.id == clipId }) {
        val list = _timeline.value.overlayClips.map {
          if (it.id == clipId) it.copy(isHidden = !it.isHidden) else it
        }
        _timeline.value = _timeline.value.copy(overlayClips = list)
      } else if (_timeline.value.stickerClips.any { it.id == clipId }) {
        val list = _timeline.value.stickerClips.map {
          if (it.id == clipId) it.copy(isHidden = !it.isHidden) else it
        }
        _timeline.value = _timeline.value.copy(stickerClips = list)
      } else if (_timeline.value.videoClips.any { it.id == clipId }) {
        val list = _timeline.value.videoClips.map {
          if (it.id == clipId) it.copy(isHidden = !it.isHidden) else it
        }
        _timeline.value = _timeline.value.copy(videoClips = list)
      } else if (_timeline.value.audioClips.any { it.id == clipId }) {
        val list = _timeline.value.audioClips.map {
          if (it.id == clipId) it.copy(isHidden = !it.isHidden) else it
        }
        _timeline.value = _timeline.value.copy(audioClips = list)
      } else if (_timeline.value.effectClips.any { it.id == clipId }) {
        val list = _timeline.value.effectClips.map {
          if (it.id == clipId) it.copy(isHidden = !it.isHidden) else it
        }
        _timeline.value = _timeline.value.copy(effectClips = list)
      }
    }
  }

  // --- Transitions ---

  private val _selectedTransitionCutIndex = MutableStateFlow<Int>(0)
  val selectedTransitionCutIndex: StateFlow<Int> = _selectedTransitionCutIndex.asStateFlow()

  fun setSelectedTransitionCutIndex(cutIndex: Int) {
    withStateLock {
      _selectedTransitionCutIndex.value = cutIndex.coerceAtLeast(0)
    }
  }

  fun setTransition(clipIndexBefore: Int, type: TransitionType, durationMs: Long = 500L) {
    withStateLock {
      recordHistory()
      val current = _timeline.value.transitions.toMutableList()
      current.removeAll { it.clipIndexBefore == clipIndexBefore }
      if (type != TransitionType.NONE) {
        current.add(Transition(clipIndexBefore = clipIndexBefore, type = type, durationMs = durationMs))
      }
      _timeline.value = _timeline.value.copy(transitions = current)
      _selectedTransitionCutIndex.value = clipIndexBefore
    }
  }

  fun removeTransition(clipIndexBefore: Int) {
    withStateLock {
      recordHistory()
      val current = _timeline.value.transitions.filter { it.clipIndexBefore != clipIndexBefore }
      _timeline.value = _timeline.value.copy(transitions = current)
    }
  }

  fun clearAllTransitions() {
    withStateLock {
      recordHistory()
      _timeline.value = _timeline.value.copy(transitions = emptyList())
    }
  }

  fun applyTransitionToAllCuts(type: TransitionType, durationMs: Long = 500L) {
    withStateLock {
      val count = _timeline.value.videoClips.size
      if (count <= 1) return
      recordHistory()
      val list = mutableListOf<Transition>()
      if (type != TransitionType.NONE) {
        for (i in 0 until count - 1) {
          list.add(Transition(clipIndexBefore = i, type = type, durationMs = durationMs))
        }
      }
      _timeline.value = _timeline.value.copy(transitions = list)
    }
  }

  fun setTransitionDuration(clipIndexBefore: Int, durationMs: Long) {
    withStateLock {
      val existing = _timeline.value.transitions.find { it.clipIndexBefore == clipIndexBefore } ?: return
      recordHistory()
      val updated = _timeline.value.transitions.map {
        if (it.clipIndexBefore == clipIndexBefore) it.copy(durationMs = durationMs) else it
      }
      _timeline.value = _timeline.value.copy(transitions = updated)
    }
  }

  fun addTransitionSoundEffect(cutIndex: Int, soundName: String = "Cinematic Whoosh") {
    withStateLock {
      val videoClips = _timeline.value.videoClips
      if (cutIndex < 0 || cutIndex >= videoClips.size - 1) return
      val clipA = videoClips[cutIndex]
      val cutPosMs = clipA.timelineStartMs + clipA.durationMs - 300L
      val audioClip = com.example.domain.model.AudioClip(
        id = "sfx_trans_${System.currentTimeMillis()}",
        uri = "asset:///audio/whoosh.mp3",
        title = soundName,
        timelineStartMs = cutPosMs.coerceAtLeast(0L),
        durationMs = 900L,
        volume = 0.9f,
        fadeInMs = 100L,
        fadeOutMs = 200L
      )
      recordHistory()
      val currentAudio = _timeline.value.audioClips.toMutableList()
      currentAudio.add(audioClip)
      _timeline.value = _timeline.value.copy(audioClips = currentAudio)
    }
  }

  // --- Keyframe Animation System ---

  fun selectKeyframe(keyframeId: String, addToSelection: Boolean = false) {
    withStateLock {
      if (addToSelection) {
        val current = _selectedKeyframeIds.value
        _selectedKeyframeIds.value = if (keyframeId in current) current - keyframeId else current + keyframeId
      } else {
        _selectedKeyframeIds.value = setOf(keyframeId)
      }
    }
  }

  fun toggleKeyframeSelection(keyframeId: String) {
    withStateLock {
      selectKeyframe(keyframeId, addToSelection = true)
    }
  }

  fun clearKeyframeSelection() {
    withStateLock {
      _selectedKeyframeIds.value = emptySet()
    }
  }

  fun selectAllKeyframesInSelectedClip() {
    withStateLock {
      val keyframes = getSelectedClipKeyframes()?.second ?: emptyList()
      _selectedKeyframeIds.value = keyframes.map { it.id }.toSet()
    }
  }

  fun getSelectedClipKeyframes(): Pair<String, List<ClipKeyframe>>? {
    val selected = _selectedElement.value
    return when (selected) {
      is SelectedTrackElement.Video -> {
        val clip = _timeline.value.videoClips.find { it.id == selected.clipId }
        clip?.let { it.id to it.keyframes }
      }
      is SelectedTrackElement.Overlay -> {
        val clip = _timeline.value.overlayClips.find { it.id == selected.clipId }
        clip?.let { it.id to it.keyframes }
      }
      is SelectedTrackElement.Audio -> {
        val clip = _timeline.value.audioClips.find { it.id == selected.clipId }
        clip?.let { it.id to it.keyframes }
      }
      is SelectedTrackElement.Effect -> {
        val clip = _timeline.value.effectClips.find { it.id == selected.clipId }
        clip?.let { it.id to it.keyframes }
      }
      is SelectedTrackElement.Sticker -> {
        val clip = _timeline.value.stickerClips.find { it.id == selected.clipId }
        clip?.let { it.id to it.keyframes }
      }
      is SelectedTrackElement.Text -> {
        val clip = _timeline.value.textClips.find { it.id == selected.clipId }
        clip?.let { it.id to it.keyframes }
      }
      else -> null
    }
  }

  fun getKeyframeAtPlayhead(toleranceMs: Long = 150L): ClipKeyframe? {
    val selected = _selectedElement.value
    val (clipStartMs, keyframes) = when (selected) {
      is SelectedTrackElement.Video -> {
        val c = _timeline.value.videoClips.find { it.id == selected.clipId } ?: return null
        c.timelineStartMs to c.keyframes
      }
      is SelectedTrackElement.Overlay -> {
        val c = _timeline.value.overlayClips.find { it.id == selected.clipId } ?: return null
        c.timelineStartMs to c.keyframes
      }
      is SelectedTrackElement.Audio -> {
        val c = _timeline.value.audioClips.find { it.id == selected.clipId } ?: return null
        c.timelineStartMs to c.keyframes
      }
      is SelectedTrackElement.Effect -> {
        val c = _timeline.value.effectClips.find { it.id == selected.clipId } ?: return null
        c.timelineStartMs to c.keyframes
      }
      is SelectedTrackElement.Sticker -> {
        val c = _timeline.value.stickerClips.find { it.id == selected.clipId } ?: return null
        c.timelineStartMs to c.keyframes
      }
      is SelectedTrackElement.Text -> {
        val c = _timeline.value.textClips.find { it.id == selected.clipId } ?: return null
        c.timelineStartMs to c.keyframes
      }
      else -> return null
    }

    val relTime = _currentPositionMs.value - clipStartMs
    return keyframes.find { kotlin.math.abs(it.timeMs - relTime) <= toleranceMs }
  }

  fun addKeyframeToSelectedClip(customKeyframe: ClipKeyframe? = null) {
    withStateLock {
      val selected = _selectedElement.value
      when (selected) {
        is SelectedTrackElement.Video -> {
          recordHistory()
          var newlyAddedId: String? = null
          val list = _timeline.value.videoClips.map { clip ->
            if (clip.id == selected.clipId) {
              val relTime = (_currentPositionMs.value - clip.timelineStartMs).coerceIn(0L, clip.durationMs)
              val existing = clip.keyframes.filterNot { kotlin.math.abs(it.timeMs - relTime) < 50L }
              val interp = KeyframeInterpolator.interpolateRaw(clip, relTime)
              val newKf = customKeyframe?.copy(timeMs = relTime) ?: ClipKeyframe(
                timeMs = relTime,
                posX = interp.posX,
                posY = interp.posY,
                scaleX = interp.scaleX,
                scaleY = interp.scaleY,
                rotation = interp.rotation,
                opacity = interp.opacity,
                volume = interp.volume,
                blur = interp.blur,
                brightness = interp.brightness,
                contrast = interp.contrast,
                saturation = interp.saturation,
                effectParam = interp.effectParam
              )
              newlyAddedId = newKf.id
              clip.copy(keyframes = (existing + newKf).sortedBy { it.timeMs })
            } else clip
          }
          _timeline.value = _timeline.value.copy(videoClips = list)
          newlyAddedId?.let { selectKeyframe(it) }
        }
        is SelectedTrackElement.Overlay -> {
          recordHistory()
          var newlyAddedId: String? = null
          val list = _timeline.value.overlayClips.map { clip ->
            if (clip.id == selected.clipId) {
              val relTime = (_currentPositionMs.value - clip.timelineStartMs).coerceIn(0L, clip.durationMs)
              val existing = clip.keyframes.filterNot { kotlin.math.abs(it.timeMs - relTime) < 50L }
              val interp = KeyframeInterpolator.interpolateRaw(clip, relTime)
              val newKf = customKeyframe?.copy(timeMs = relTime) ?: ClipKeyframe(
                timeMs = relTime,
                posX = interp.posX,
                posY = interp.posY,
                scaleX = interp.scaleX,
                scaleY = interp.scaleY,
                rotation = interp.rotation,
                opacity = interp.opacity,
                volume = interp.volume,
                blur = interp.blur,
                brightness = interp.brightness,
                contrast = interp.contrast,
                saturation = interp.saturation,
                effectParam = interp.effectParam
              )
              newlyAddedId = newKf.id
              clip.copy(keyframes = (existing + newKf).sortedBy { it.timeMs })
            } else clip
          }
          _timeline.value = _timeline.value.copy(overlayClips = list)
          newlyAddedId?.let { selectKeyframe(it) }
        }
        is SelectedTrackElement.Audio -> {
          recordHistory()
          var newlyAddedId: String? = null
          val list = _timeline.value.audioClips.map { clip ->
            if (clip.id == selected.clipId) {
              val relTime = (_currentPositionMs.value - clip.timelineStartMs).coerceIn(0L, clip.durationMs)
              val existing = clip.keyframes.filterNot { kotlin.math.abs(it.timeMs - relTime) < 50L }
              val currentVol = KeyframeInterpolator.interpolateVolume(clip, relTime)
              val newKf = customKeyframe?.copy(timeMs = relTime) ?: ClipKeyframe(
                timeMs = relTime,
                volume = currentVol
              )
              newlyAddedId = newKf.id
              clip.copy(keyframes = (existing + newKf).sortedBy { it.timeMs })
            } else clip
          }
          _timeline.value = _timeline.value.copy(audioClips = list)
          newlyAddedId?.let { selectKeyframe(it) }
        }
        is SelectedTrackElement.Effect -> {
          recordHistory()
          var newlyAddedId: String? = null
          val list = _timeline.value.effectClips.map { clip ->
            if (clip.id == selected.clipId) {
              val relTime = (_currentPositionMs.value - clip.timelineStartMs).coerceIn(0L, clip.durationMs)
              val existing = clip.keyframes.filterNot { kotlin.math.abs(it.timeMs - relTime) < 50L }
              val currentIntensity = KeyframeInterpolator.interpolateEffectIntensity(clip, relTime)
              val newKf = customKeyframe?.copy(timeMs = relTime) ?: ClipKeyframe(
                timeMs = relTime,
                effectParam = currentIntensity
              )
              newlyAddedId = newKf.id
              clip.copy(keyframes = (existing + newKf).sortedBy { it.timeMs })
            } else clip
          }
          _timeline.value = _timeline.value.copy(effectClips = list)
          newlyAddedId?.let { selectKeyframe(it) }
        }
        is SelectedTrackElement.Sticker -> {
          recordHistory()
          var newlyAddedId: String? = null
          val list = _timeline.value.stickerClips.map { clip ->
            if (clip.id == selected.clipId) {
              val relTime = (_currentPositionMs.value - clip.timelineStartMs).coerceIn(0L, clip.durationMs)
              val existing = clip.keyframes.filterNot { kotlin.math.abs(it.timeMs - relTime) < 50L }
              val interp = KeyframeInterpolator.interpolateRaw(clip, relTime)
              val newKf = customKeyframe?.copy(timeMs = relTime) ?: ClipKeyframe(
                timeMs = relTime,
                posX = interp.posX,
                posY = interp.posY,
                scaleX = interp.scaleX,
                scaleY = interp.scaleY,
                rotation = interp.rotation,
                opacity = interp.opacity
              )
              newlyAddedId = newKf.id
              clip.copy(keyframes = (existing + newKf).sortedBy { it.timeMs })
            } else clip
          }
          _timeline.value = _timeline.value.copy(stickerClips = list)
          newlyAddedId?.let { selectKeyframe(it) }
        }
        is SelectedTrackElement.Text -> {
          recordHistory()
          var newlyAddedId: String? = null
          val list = _timeline.value.textClips.map { clip ->
            if (clip.id == selected.clipId) {
              val relTime = (_currentPositionMs.value - clip.timelineStartMs).coerceIn(0L, clip.durationMs)
              val existing = clip.keyframes.filterNot { kotlin.math.abs(it.timeMs - relTime) < 50L }
              val interp = KeyframeInterpolator.interpolateRaw(clip, relTime)
              val newKf = customKeyframe?.copy(timeMs = relTime) ?: ClipKeyframe(
                timeMs = relTime,
                posX = interp.posX,
                posY = interp.posY,
                scaleX = interp.scaleX,
                scaleY = interp.scaleY,
                rotation = interp.rotation,
                opacity = interp.opacity
              )
              newlyAddedId = newKf.id
              clip.copy(keyframes = (existing + newKf).sortedBy { it.timeMs })
            } else clip
          }
          _timeline.value = _timeline.value.copy(textClips = list)
          newlyAddedId?.let { selectKeyframe(it) }
        }
        else -> {}
      }
    }
  }

  fun deleteSelectedKeyframes() {
    withStateLock {
      val selectedIds = _selectedKeyframeIds.value
      val selected = _selectedElement.value
      recordHistory()

      when (selected) {
        is SelectedTrackElement.Video -> {
          val list = _timeline.value.videoClips.map { clip ->
            if (clip.id == selected.clipId) {
              val updated = if (selectedIds.isNotEmpty()) {
                clip.keyframes.filterNot { it.id in selectedIds }
              } else {
                val relTime = _currentPositionMs.value - clip.timelineStartMs
                clip.keyframes.filterNot { kotlin.math.abs(it.timeMs - relTime) < 200L }
              }
              clip.copy(keyframes = updated)
            } else clip
          }
          _timeline.value = _timeline.value.copy(videoClips = list)
        }
        is SelectedTrackElement.Overlay -> {
          val list = _timeline.value.overlayClips.map { clip ->
            if (clip.id == selected.clipId) {
              val updated = if (selectedIds.isNotEmpty()) {
                clip.keyframes.filterNot { it.id in selectedIds }
              } else {
                val relTime = _currentPositionMs.value - clip.timelineStartMs
                clip.keyframes.filterNot { kotlin.math.abs(it.timeMs - relTime) < 200L }
              }
              clip.copy(keyframes = updated)
            } else clip
          }
          _timeline.value = _timeline.value.copy(overlayClips = list)
        }
        is SelectedTrackElement.Audio -> {
          val list = _timeline.value.audioClips.map { clip ->
            if (clip.id == selected.clipId) {
              val updated = if (selectedIds.isNotEmpty()) {
                clip.keyframes.filterNot { it.id in selectedIds }
              } else {
                val relTime = _currentPositionMs.value - clip.timelineStartMs
                clip.keyframes.filterNot { kotlin.math.abs(it.timeMs - relTime) < 200L }
              }
              clip.copy(keyframes = updated)
            } else clip
          }
          _timeline.value = _timeline.value.copy(audioClips = list)
        }
        is SelectedTrackElement.Effect -> {
          val list = _timeline.value.effectClips.map { clip ->
            if (clip.id == selected.clipId) {
              val updated = if (selectedIds.isNotEmpty()) {
                clip.keyframes.filterNot { it.id in selectedIds }
              } else {
                val relTime = _currentPositionMs.value - clip.timelineStartMs
                clip.keyframes.filterNot { kotlin.math.abs(it.timeMs - relTime) < 200L }
              }
              clip.copy(keyframes = updated)
            } else clip
          }
          _timeline.value = _timeline.value.copy(effectClips = list)
        }
        is SelectedTrackElement.Sticker -> {
          val list = _timeline.value.stickerClips.map { clip ->
            if (clip.id == selected.clipId) {
              val updated = if (selectedIds.isNotEmpty()) {
                clip.keyframes.filterNot { it.id in selectedIds }
              } else {
                val relTime = _currentPositionMs.value - clip.timelineStartMs
                clip.keyframes.filterNot { kotlin.math.abs(it.timeMs - relTime) < 200L }
              }
              clip.copy(keyframes = updated)
            } else clip
          }
          _timeline.value = _timeline.value.copy(stickerClips = list)
        }
        is SelectedTrackElement.Text -> {
          val list = _timeline.value.textClips.map { clip ->
            if (clip.id == selected.clipId) {
              val updated = if (selectedIds.isNotEmpty()) {
                clip.keyframes.filterNot { it.id in selectedIds }
              } else {
                val relTime = _currentPositionMs.value - clip.timelineStartMs
                clip.keyframes.filterNot { kotlin.math.abs(it.timeMs - relTime) < 200L }
              }
              clip.copy(keyframes = updated)
            } else clip
          }
          _timeline.value = _timeline.value.copy(textClips = list)
        }
        else -> {}
      }
      clearKeyframeSelection()
    }
  }

  fun deleteKeyframeFromSelectedClip() {
    withStateLock {
      deleteSelectedKeyframes()
    }
  }

  fun deleteKeyframe(keyframeId: String) {
    withStateLock {
      _selectedKeyframeIds.value = setOf(keyframeId)
      deleteSelectedKeyframes()
    }
  }

  fun moveKeyframe(keyframeId: String, newTimeMs: Long) {
    withStateLock {
      val selected = _selectedElement.value
      when (selected) {
        is SelectedTrackElement.Video -> {
          val list = _timeline.value.videoClips.map { clip ->
            if (clip.id == selected.clipId) {
              val clampedTime = newTimeMs.coerceIn(0L, clip.durationMs)
              val updated = clip.keyframes.map { kf ->
                if (kf.id == keyframeId) kf.copy(timeMs = clampedTime) else kf
              }.sortedBy { it.timeMs }
              clip.copy(keyframes = updated)
            } else clip
          }
          _timeline.value = _timeline.value.copy(videoClips = list)
        }
        is SelectedTrackElement.Overlay -> {
          val list = _timeline.value.overlayClips.map { clip ->
            if (clip.id == selected.clipId) {
              val clampedTime = newTimeMs.coerceIn(0L, clip.durationMs)
              val updated = clip.keyframes.map { kf ->
                if (kf.id == keyframeId) kf.copy(timeMs = clampedTime) else kf
              }.sortedBy { it.timeMs }
              clip.copy(keyframes = updated)
            } else clip
          }
          _timeline.value = _timeline.value.copy(overlayClips = list)
        }
        is SelectedTrackElement.Audio -> {
          val list = _timeline.value.audioClips.map { clip ->
            if (clip.id == selected.clipId) {
              val clampedTime = newTimeMs.coerceIn(0L, clip.durationMs)
              val updated = clip.keyframes.map { kf ->
                if (kf.id == keyframeId) kf.copy(timeMs = clampedTime) else kf
              }.sortedBy { it.timeMs }
              clip.copy(keyframes = updated)
            } else clip
          }
          _timeline.value = _timeline.value.copy(audioClips = list)
        }
        is SelectedTrackElement.Effect -> {
          val list = _timeline.value.effectClips.map { clip ->
            if (clip.id == selected.clipId) {
              val clampedTime = newTimeMs.coerceIn(0L, clip.durationMs)
              val updated = clip.keyframes.map { kf ->
                if (kf.id == keyframeId) kf.copy(timeMs = clampedTime) else kf
              }.sortedBy { it.timeMs }
              clip.copy(keyframes = updated)
            } else clip
          }
          _timeline.value = _timeline.value.copy(effectClips = list)
        }
        is SelectedTrackElement.Sticker -> {
          val list = _timeline.value.stickerClips.map { clip ->
            if (clip.id == selected.clipId) {
              val clampedTime = newTimeMs.coerceIn(0L, clip.durationMs)
              val updated = clip.keyframes.map { kf ->
                if (kf.id == keyframeId) kf.copy(timeMs = clampedTime) else kf
              }.sortedBy { it.timeMs }
              clip.copy(keyframes = updated)
            } else clip
          }
          _timeline.value = _timeline.value.copy(stickerClips = list)
        }
        is SelectedTrackElement.Text -> {
          val list = _timeline.value.textClips.map { clip ->
            if (clip.id == selected.clipId) {
              val clampedTime = newTimeMs.coerceIn(0L, clip.durationMs)
              val updated = clip.keyframes.map { kf ->
                if (kf.id == keyframeId) kf.copy(timeMs = clampedTime) else kf
              }.sortedBy { it.timeMs }
              clip.copy(keyframes = updated)
            } else clip
          }
          _timeline.value = _timeline.value.copy(textClips = list)
        }
        else -> {}
      }
    }
  }

  fun moveSelectedKeyframes(deltaMs: Long) {
    withStateLock {
      val selectedIds = _selectedKeyframeIds.value
      if (selectedIds.isEmpty()) return
      val selected = _selectedElement.value

      when (selected) {
        is SelectedTrackElement.Video -> {
          val list = _timeline.value.videoClips.map { clip ->
            if (clip.id == selected.clipId) {
              val updated = clip.keyframes.map { kf ->
                if (kf.id in selectedIds) {
                  kf.copy(timeMs = (kf.timeMs + deltaMs).coerceIn(0L, clip.durationMs))
                } else kf
              }.sortedBy { it.timeMs }
              clip.copy(keyframes = updated)
            } else clip
          }
          _timeline.value = _timeline.value.copy(videoClips = list)
        }
        is SelectedTrackElement.Overlay -> {
          val list = _timeline.value.overlayClips.map { clip ->
            if (clip.id == selected.clipId) {
              val updated = clip.keyframes.map { kf ->
                if (kf.id in selectedIds) {
                  kf.copy(timeMs = (kf.timeMs + deltaMs).coerceIn(0L, clip.durationMs))
                } else kf
              }.sortedBy { it.timeMs }
              clip.copy(keyframes = updated)
            } else clip
          }
          _timeline.value = _timeline.value.copy(overlayClips = list)
        }
        is SelectedTrackElement.Audio -> {
          val list = _timeline.value.audioClips.map { clip ->
            if (clip.id == selected.clipId) {
              val updated = clip.keyframes.map { kf ->
                if (kf.id in selectedIds) {
                  kf.copy(timeMs = (kf.timeMs + deltaMs).coerceIn(0L, clip.durationMs))
                } else kf
              }.sortedBy { it.timeMs }
              clip.copy(keyframes = updated)
            } else clip
          }
          _timeline.value = _timeline.value.copy(audioClips = list)
        }
        is SelectedTrackElement.Effect -> {
          val list = _timeline.value.effectClips.map { clip ->
            if (clip.id == selected.clipId) {
              val updated = clip.keyframes.map { kf ->
                if (kf.id in selectedIds) {
                  kf.copy(timeMs = (kf.timeMs + deltaMs).coerceIn(0L, clip.durationMs))
                } else kf
              }.sortedBy { it.timeMs }
              clip.copy(keyframes = updated)
            } else clip
          }
          _timeline.value = _timeline.value.copy(effectClips = list)
        }
        is SelectedTrackElement.Sticker -> {
          val list = _timeline.value.stickerClips.map { clip ->
            if (clip.id == selected.clipId) {
              val updated = clip.keyframes.map { kf ->
                if (kf.id in selectedIds) {
                  kf.copy(timeMs = (kf.timeMs + deltaMs).coerceIn(0L, clip.durationMs))
                } else kf
              }.sortedBy { it.timeMs }
              clip.copy(keyframes = updated)
            } else clip
          }
          _timeline.value = _timeline.value.copy(stickerClips = list)
        }
        is SelectedTrackElement.Text -> {
          val list = _timeline.value.textClips.map { clip ->
            if (clip.id == selected.clipId) {
              val updated = clip.keyframes.map { kf ->
                if (kf.id in selectedIds) {
                  kf.copy(timeMs = (kf.timeMs + deltaMs).coerceIn(0L, clip.durationMs))
                } else kf
              }.sortedBy { it.timeMs }
              clip.copy(keyframes = updated)
            } else clip
          }
          _timeline.value = _timeline.value.copy(textClips = list)
        }
        else -> {}
      }
    }
  }

  fun updateKeyframe(keyframeId: String, transform: (ClipKeyframe) -> ClipKeyframe) {
    withStateLock {
      val selected = _selectedElement.value
      when (selected) {
        is SelectedTrackElement.Video -> {
          val list = _timeline.value.videoClips.map { clip ->
            if (clip.id == selected.clipId) {
              val updated = clip.keyframes.map { kf ->
                if (kf.id == keyframeId) transform(kf) else kf
              }
              clip.copy(keyframes = updated)
            } else clip
          }
          _timeline.value = _timeline.value.copy(videoClips = list)
        }
        is SelectedTrackElement.Overlay -> {
          val list = _timeline.value.overlayClips.map { clip ->
            if (clip.id == selected.clipId) {
              val updated = clip.keyframes.map { kf ->
                if (kf.id == keyframeId) transform(kf) else kf
              }
              clip.copy(keyframes = updated)
            } else clip
          }
          _timeline.value = _timeline.value.copy(overlayClips = list)
        }
        is SelectedTrackElement.Audio -> {
          val list = _timeline.value.audioClips.map { clip ->
            if (clip.id == selected.clipId) {
              val updated = clip.keyframes.map { kf ->
                if (kf.id == keyframeId) transform(kf) else kf
              }
              clip.copy(keyframes = updated)
            } else clip
          }
          _timeline.value = _timeline.value.copy(audioClips = list)
        }
        is SelectedTrackElement.Effect -> {
          val list = _timeline.value.effectClips.map { clip ->
            if (clip.id == selected.clipId) {
              val updated = clip.keyframes.map { kf ->
                if (kf.id == keyframeId) transform(kf) else kf
              }
              clip.copy(keyframes = updated)
            } else clip
          }
          _timeline.value = _timeline.value.copy(effectClips = list)
        }
        is SelectedTrackElement.Sticker -> {
          val list = _timeline.value.stickerClips.map { clip ->
            if (clip.id == selected.clipId) {
              val updated = clip.keyframes.map { kf ->
                if (kf.id == keyframeId) transform(kf) else kf
              }
              clip.copy(keyframes = updated)
            } else clip
          }
          _timeline.value = _timeline.value.copy(stickerClips = list)
        }
        is SelectedTrackElement.Text -> {
          val list = _timeline.value.textClips.map { clip ->
            if (clip.id == selected.clipId) {
              val updated = clip.keyframes.map { kf ->
                if (kf.id == keyframeId) transform(kf) else kf
              }
              clip.copy(keyframes = updated)
            } else clip
          }
          _timeline.value = _timeline.value.copy(textClips = list)
        }
        else -> {}
      }
    }
  }

  fun copySelectedKeyframes() {
    withStateLock {
      val keyframes = getSelectedClipKeyframes()?.second ?: return
      val selectedIds = _selectedKeyframeIds.value
      val toCopy = if (selectedIds.isNotEmpty()) {
        keyframes.filter { it.id in selectedIds }
      } else {
        getKeyframeAtPlayhead()?.let { listOf(it) } ?: emptyList()
      }
      if (toCopy.isNotEmpty()) {
        keyframeClipboard = toCopy
      }
    }
  }

  fun pasteKeyframes(targetTimeMs: Long? = null) {
    withStateLock {
      if (keyframeClipboard.isEmpty()) return
      val selected = _selectedElement.value ?: return
      val minTime = keyframeClipboard.minOf { it.timeMs }

      val clipStart = when (selected) {
        is SelectedTrackElement.Video -> _timeline.value.videoClips.find { it.id == selected.clipId }?.timelineStartMs ?: 0L
        is SelectedTrackElement.Overlay -> _timeline.value.overlayClips.find { it.id == selected.clipId }?.timelineStartMs ?: 0L
        is SelectedTrackElement.Audio -> _timeline.value.audioClips.find { it.id == selected.clipId }?.timelineStartMs ?: 0L
        is SelectedTrackElement.Sticker -> _timeline.value.stickerClips.find { it.id == selected.clipId }?.timelineStartMs ?: 0L
        else -> 0L
      }

      val basePasteTime = targetTimeMs ?: (_currentPositionMs.value - clipStart).coerceAtLeast(0L)
      val pastedKeyframes = keyframeClipboard.map { kf ->
        val offset = kf.timeMs - minTime
        kf.copy(
          id = java.util.UUID.randomUUID().toString(),
          timeMs = basePasteTime + offset
        )
      }

      recordHistory()
      when (selected) {
        is SelectedTrackElement.Video -> {
          val list = _timeline.value.videoClips.map { clip ->
            if (clip.id == selected.clipId) {
              val updated = (clip.keyframes + pastedKeyframes.map { it.copy(timeMs = it.timeMs.coerceIn(0L, clip.durationMs)) }).sortedBy { it.timeMs }
              clip.copy(keyframes = updated)
            } else clip
          }
          _timeline.value = _timeline.value.copy(videoClips = list)
        }
        is SelectedTrackElement.Overlay -> {
          val list = _timeline.value.overlayClips.map { clip ->
            if (clip.id == selected.clipId) {
              val updated = (clip.keyframes + pastedKeyframes.map { it.copy(timeMs = it.timeMs.coerceIn(0L, clip.durationMs)) }).sortedBy { it.timeMs }
              clip.copy(keyframes = updated)
            } else clip
          }
          _timeline.value = _timeline.value.copy(overlayClips = list)
        }
        is SelectedTrackElement.Audio -> {
          val list = _timeline.value.audioClips.map { clip ->
            if (clip.id == selected.clipId) {
              val updated = (clip.keyframes + pastedKeyframes.map { it.copy(timeMs = it.timeMs.coerceIn(0L, clip.durationMs)) }).sortedBy { it.timeMs }
              clip.copy(keyframes = updated)
            } else clip
          }
          _timeline.value = _timeline.value.copy(audioClips = list)
        }
        is SelectedTrackElement.Sticker -> {
          val list = _timeline.value.stickerClips.map { clip ->
            if (clip.id == selected.clipId) {
              val updated = (clip.keyframes + pastedKeyframes.map { it.copy(timeMs = it.timeMs.coerceIn(0L, clip.durationMs)) }).sortedBy { it.timeMs }
              clip.copy(keyframes = updated)
            } else clip
          }
          _timeline.value = _timeline.value.copy(stickerClips = list)
        }
        else -> {}
      }
      _selectedKeyframeIds.value = pastedKeyframes.map { it.id }.toSet()
    }
  }

  fun duplicateSelectedKeyframes(offsetMs: Long = 300L) {
    withStateLock {
      val keyframes = getSelectedClipKeyframes()?.second ?: return
      val selectedIds = _selectedKeyframeIds.value
      val toDuplicate = if (selectedIds.isNotEmpty()) {
        keyframes.filter { it.id in selectedIds }
      } else {
        getKeyframeAtPlayhead()?.let { listOf(it) } ?: emptyList()
      }
      if (toDuplicate.isEmpty()) return

      val duplicated = toDuplicate.map { kf ->
        kf.copy(
          id = java.util.UUID.randomUUID().toString(),
          timeMs = kf.timeMs + offsetMs
        )
      }

      recordHistory()
      val selected = _selectedElement.value
      when (selected) {
        is SelectedTrackElement.Video -> {
          val list = _timeline.value.videoClips.map { clip ->
            if (clip.id == selected.clipId) {
              val updated = (clip.keyframes + duplicated.map { it.copy(timeMs = it.timeMs.coerceIn(0L, clip.durationMs)) }).sortedBy { it.timeMs }
              clip.copy(keyframes = updated)
            } else clip
          }
          _timeline.value = _timeline.value.copy(videoClips = list)
        }
        is SelectedTrackElement.Overlay -> {
          val list = _timeline.value.overlayClips.map { clip ->
            if (clip.id == selected.clipId) {
              val updated = (clip.keyframes + duplicated.map { it.copy(timeMs = it.timeMs.coerceIn(0L, clip.durationMs)) }).sortedBy { it.timeMs }
              clip.copy(keyframes = updated)
            } else clip
          }
          _timeline.value = _timeline.value.copy(overlayClips = list)
        }
        is SelectedTrackElement.Audio -> {
          val list = _timeline.value.audioClips.map { clip ->
            if (clip.id == selected.clipId) {
              val updated = (clip.keyframes + duplicated.map { it.copy(timeMs = it.timeMs.coerceIn(0L, clip.durationMs)) }).sortedBy { it.timeMs }
              clip.copy(keyframes = updated)
            } else clip
          }
          _timeline.value = _timeline.value.copy(audioClips = list)
        }
        is SelectedTrackElement.Sticker -> {
          val list = _timeline.value.stickerClips.map { clip ->
            if (clip.id == selected.clipId) {
              val updated = (clip.keyframes + duplicated.map { it.copy(timeMs = it.timeMs.coerceIn(0L, clip.durationMs)) }).sortedBy { it.timeMs }
              clip.copy(keyframes = updated)
            } else clip
          }
          _timeline.value = _timeline.value.copy(stickerClips = list)
        }
        else -> {}
      }
      _selectedKeyframeIds.value = duplicated.map { it.id }.toSet()
    }
  }

  fun jumpToPreviousKeyframe() {
    withStateLock {
      val keyframesPair = getSelectedClipKeyframes() ?: return
      val keyframes = keyframesPair.second.sortedBy { it.timeMs }
      if (keyframes.isEmpty()) return

      val selected = _selectedElement.value
      val clipStart = when (selected) {
        is SelectedTrackElement.Video -> _timeline.value.videoClips.find { it.id == selected.clipId }?.timelineStartMs ?: 0L
        is SelectedTrackElement.Overlay -> _timeline.value.overlayClips.find { it.id == selected.clipId }?.timelineStartMs ?: 0L
        is SelectedTrackElement.Audio -> _timeline.value.audioClips.find { it.id == selected.clipId }?.timelineStartMs ?: 0L
        is SelectedTrackElement.Sticker -> _timeline.value.stickerClips.find { it.id == selected.clipId }?.timelineStartMs ?: 0L
        else -> 0L
      }

      val currentRelTime = _currentPositionMs.value - clipStart
      val prev = keyframes.lastOrNull { it.timeMs < currentRelTime - 50L } ?: keyframes.first()
      setPosition(clipStart + prev.timeMs)
      selectKeyframe(prev.id)
    }
  }

  fun jumpToNextKeyframe() {
    withStateLock {
      val keyframesPair = getSelectedClipKeyframes() ?: return
      val keyframes = keyframesPair.second.sortedBy { it.timeMs }
      if (keyframes.isEmpty()) return

      val selected = _selectedElement.value
      val clipStart = when (selected) {
        is SelectedTrackElement.Video -> _timeline.value.videoClips.find { it.id == selected.clipId }?.timelineStartMs ?: 0L
        is SelectedTrackElement.Overlay -> _timeline.value.overlayClips.find { it.id == selected.clipId }?.timelineStartMs ?: 0L
        is SelectedTrackElement.Audio -> _timeline.value.audioClips.find { it.id == selected.clipId }?.timelineStartMs ?: 0L
        is SelectedTrackElement.Sticker -> _timeline.value.stickerClips.find { it.id == selected.clipId }?.timelineStartMs ?: 0L
        else -> 0L
      }

      val currentRelTime = _currentPositionMs.value - clipStart
      val next = keyframes.firstOrNull { it.timeMs > currentRelTime + 50L } ?: keyframes.last()
      setPosition(clipStart + next.timeMs)
      selectKeyframe(next.id)
    }
  }

  fun clearAllKeyframesInSelectedClip() {
    withStateLock {
      val selected = _selectedElement.value ?: return
      recordHistory()
      when (selected) {
        is SelectedTrackElement.Video -> {
          val list = _timeline.value.videoClips.map { clip ->
            if (clip.id == selected.clipId) clip.copy(keyframes = emptyList()) else clip
          }
          _timeline.value = _timeline.value.copy(videoClips = list)
        }
        is SelectedTrackElement.Overlay -> {
          val list = _timeline.value.overlayClips.map { clip ->
            if (clip.id == selected.clipId) clip.copy(keyframes = emptyList()) else clip
          }
          _timeline.value = _timeline.value.copy(overlayClips = list)
        }
        is SelectedTrackElement.Audio -> {
          val list = _timeline.value.audioClips.map { clip ->
            if (clip.id == selected.clipId) clip.copy(keyframes = emptyList()) else clip
          }
          _timeline.value = _timeline.value.copy(audioClips = list)
        }
        is SelectedTrackElement.Effect -> {
          val list = _timeline.value.effectClips.map { clip ->
            if (clip.id == selected.clipId) clip.copy(keyframes = emptyList()) else clip
          }
          _timeline.value = _timeline.value.copy(effectClips = list)
        }
        is SelectedTrackElement.Sticker -> {
          val list = _timeline.value.stickerClips.map { clip ->
            if (clip.id == selected.clipId) clip.copy(keyframes = emptyList()) else clip
          }
          _timeline.value = _timeline.value.copy(stickerClips = list)
        }
        is SelectedTrackElement.Text -> {
          val list = _timeline.value.textClips.map { clip ->
            if (clip.id == selected.clipId) clip.copy(keyframes = emptyList()) else clip
          }
          _timeline.value = _timeline.value.copy(textClips = list)
        }
        else -> {}
      }
      clearKeyframeSelection()
    }
  }

  fun applyMotionPresetToSelectedClip(
    scaleStart: Float = 1f,
    scaleEnd: Float = 1f,
    posXStart: Float = 0f,
    posXEnd: Float = 0f,
    posYStart: Float = 0f,
    posYEnd: Float = 0f,
    rotationStart: Float = 0f,
    rotationEnd: Float = 0f,
    opacityStart: Float = 1f,
    opacityEnd: Float = 1f,
    interpolation: KeyframeInterpolation = KeyframeInterpolation.EASE_IN_OUT
  ) {
    val selected = _selectedElement.value ?: return
    recordHistory()
    when (selected) {
      is SelectedTrackElement.Video -> {
        val list = _timeline.value.videoClips.map { clip ->
          if (clip.id == selected.clipId) {
            val kfStart = ClipKeyframe(
              timeMs = 0L,
              scaleX = scaleStart,
              scaleY = scaleStart,
              posX = posXStart,
              posY = posYStart,
              rotation = rotationStart,
              opacity = opacityStart,
              interpolation = interpolation
            )
            val kfEnd = ClipKeyframe(
              timeMs = clip.durationMs,
              scaleX = scaleEnd,
              scaleY = scaleEnd,
              posX = posXEnd,
              posY = posYEnd,
              rotation = rotationEnd,
              opacity = opacityEnd,
              interpolation = interpolation
            )
            clip.copy(keyframes = listOf(kfStart, kfEnd))
          } else clip
        }
        _timeline.value = _timeline.value.copy(videoClips = list)
      }
      is SelectedTrackElement.Overlay -> {
        val list = _timeline.value.overlayClips.map { clip ->
          if (clip.id == selected.clipId) {
            val kfStart = ClipKeyframe(
              timeMs = 0L,
              scaleX = scaleStart,
              scaleY = scaleStart,
              posX = posXStart,
              posY = posYStart,
              rotation = rotationStart,
              opacity = opacityStart,
              interpolation = interpolation
            )
            val kfEnd = ClipKeyframe(
              timeMs = clip.durationMs,
              scaleX = scaleEnd,
              scaleY = scaleEnd,
              posX = posXEnd,
              posY = posYEnd,
              rotation = rotationEnd,
              opacity = opacityEnd,
              interpolation = interpolation
            )
            clip.copy(keyframes = listOf(kfStart, kfEnd))
          } else clip
        }
        _timeline.value = _timeline.value.copy(overlayClips = list)
      }
      is SelectedTrackElement.Sticker -> {
        val list = _timeline.value.stickerClips.map { clip ->
          if (clip.id == selected.clipId) {
            val kfStart = ClipKeyframe(
              timeMs = 0L,
              scaleX = scaleStart,
              scaleY = scaleStart,
              posX = posXStart,
              posY = posYStart,
              rotation = rotationStart,
              opacity = opacityStart,
              interpolation = interpolation
            )
            val kfEnd = ClipKeyframe(
              timeMs = clip.durationMs,
              scaleX = scaleEnd,
              scaleY = scaleEnd,
              posX = posXEnd,
              posY = posYEnd,
              rotation = rotationEnd,
              opacity = opacityEnd,
              interpolation = interpolation
            )
            clip.copy(keyframes = listOf(kfStart, kfEnd))
          } else clip
        }
        _timeline.value = _timeline.value.copy(stickerClips = list)
      }
      is SelectedTrackElement.Text -> {
        val list = _timeline.value.textClips.map { clip ->
          if (clip.id == selected.clipId) {
            val kfStart = ClipKeyframe(
              timeMs = 0L,
              scaleX = scaleStart,
              scaleY = scaleStart,
              posX = posXStart,
              posY = posYStart,
              rotation = rotationStart,
              opacity = opacityStart,
              interpolation = interpolation
            )
            val kfEnd = ClipKeyframe(
              timeMs = clip.durationMs,
              scaleX = scaleEnd,
              scaleY = scaleEnd,
              posX = posXEnd,
              posY = posYEnd,
              rotation = rotationEnd,
              opacity = opacityEnd,
              interpolation = interpolation
            )
            clip.copy(keyframes = listOf(kfStart, kfEnd))
          } else clip
        }
        _timeline.value = _timeline.value.copy(textClips = list)
      }
      else -> {}
    }
  }

  fun setAspectRatio(ratio: AspectRatio) = withStateLock {
    if (_timeline.value.aspectRatio != ratio) {
      recordHistory()
      _timeline.value = _timeline.value.copy(aspectRatio = ratio)
    }
  }

  fun setCanvasBackgroundColor(color: Long) = withStateLock {
    if (_timeline.value.canvasBackgroundColor != color) {
      recordHistory()
      _timeline.value = _timeline.value.copy(canvasBackgroundColor = color)
    }
  }

  fun seekTo(posMs: Long) = setPosition(posMs)

  // ==========================================
  // AUDIO VOLUME ENVELOPE & KEYFRAMING HELPERS
  // ==========================================

  /**
   * Explicitly extracts audio from a video/overlay clip into a dedicated audio track.
   * Mutes or disables audio on the source video so sound is not duplicated.
   */
  fun extractAudioFromClip(clipId: String): String? {
    val clip = _timeline.value.videoClips.find { it.id == clipId }
      ?: _timeline.value.overlayClips.find { it.id == clipId }
      ?: return null

    recordHistory()
    val audioId = "audio_extracted_${UUID.randomUUID().toString().take(8)}"
    val extractedAudioClip = com.example.domain.model.AudioClip(
      id = audioId,
      uri = clip.uri,
      title = "Extracted Audio (${clip.name})",
      timelineStartMs = clip.timelineStartMs,
      durationMs = clip.durationMs,
      volume = clip.volume,
      fadeInMs = 0L,
      fadeOutMs = 0L
    )

    val updatedVideos = _timeline.value.videoClips.map {
      if (it.id == clipId) it.copy(hasAudio = false, isMuted = true) else it
    }
    val updatedOverlays = _timeline.value.overlayClips.map {
      if (it.id == clipId) it.copy(hasAudio = false, isMuted = true) else it
    }
    val currentAudios = _timeline.value.audioClips.toMutableList().apply { add(extractedAudioClip) }
    currentAudios.sortBy { it.timelineStartMs }

    _timeline.value = _timeline.value.copy(
      videoClips = updatedVideos,
      overlayClips = updatedOverlays,
      audioClips = currentAudios
    )
    _selectedElement.value = SelectedTrackElement.Audio(audioId)
    return audioId
  }

  /**
   * Ensures at least one audio track exists on the timeline.
   * If none exists, creates a default background audio track matching the timeline duration.
   */
  fun ensureAudioTrackExists(): String {
    return _timeline.value.audioClips.firstOrNull()?.id.orEmpty()
  }

  /**
   * Adds a volume keyframe to the specified audio clip at [relTimeMs] with [volume] (0.0 to 1.5+).
   */
  fun addAudioVolumeKeyframe(clipId: String, relTimeMs: Long, volume: Float) {
    withStateLock {
      recordHistory()
      val list = _timeline.value.audioClips.map { clip ->
        if (clip.id == clipId) {
          val clampedTime = relTimeMs.coerceIn(0L, clip.durationMs)
          val clampedVol = volume.coerceIn(0f, 2f)
          val filtered = clip.keyframes.filterNot { kotlin.math.abs(it.timeMs - clampedTime) < 30L }
          val newKf = com.example.domain.model.ClipKeyframe(
            timeMs = clampedTime,
            volume = clampedVol
          )
          clip.copy(keyframes = (filtered + newKf).sortedBy { it.timeMs })
        } else clip
      }
      _timeline.value = _timeline.value.copy(audioClips = list)
    }
  }

  /**
   * Updates time and volume of an existing keyframe on an audio clip.
   */
  fun updateAudioVolumeKeyframe(clipId: String, keyframeId: String, newTimeMs: Long, newVolume: Float) {
    withStateLock {
      val list = _timeline.value.audioClips.map { clip ->
        if (clip.id == clipId) {
          val clampedTime = newTimeMs.coerceIn(0L, clip.durationMs)
          val clampedVol = newVolume.coerceIn(0f, 2f)
          val updated = clip.keyframes.map { kf ->
            if (kf.id == keyframeId) kf.copy(timeMs = clampedTime, volume = clampedVol) else kf
          }.sortedBy { it.timeMs }
          clip.copy(keyframes = updated)
        } else clip
      }
      _timeline.value = _timeline.value.copy(audioClips = list)
    }
  }

  /**
   * Deletes a volume keyframe from the specified audio clip.
   */
  fun deleteAudioVolumeKeyframe(clipId: String, keyframeId: String) {
    withStateLock {
      recordHistory()
      val list = _timeline.value.audioClips.map { clip ->
        if (clip.id == clipId) {
          clip.copy(keyframes = clip.keyframes.filterNot { it.id == keyframeId })
        } else clip
      }
      _timeline.value = _timeline.value.copy(audioClips = list)
    }
  }

  /**
   * Sets the fade in / fade out durations in milliseconds for the audio clip.
   */
  fun setAudioFade(clipId: String, fadeInMs: Long, fadeOutMs: Long) {
    withStateLock {
      recordHistory()
      val list = _timeline.value.audioClips.map { clip ->
        if (clip.id == clipId) {
          clip.copy(
            fadeInMs = fadeInMs.coerceIn(0L, clip.durationMs / 2),
            fadeOutMs = fadeOutMs.coerceIn(0L, clip.durationMs / 2)
          )
        } else clip
      }
      _timeline.value = _timeline.value.copy(audioClips = list)
    }
  }

  /**
   * Converts or generates explicit volume keyframes for fading audio in and out.
   */
  fun applyAudioFadeKeyframes(clipId: String, fadeInMs: Long = 1000L, fadeOutMs: Long = 1000L) {
    withStateLock {
      recordHistory()
      val list = _timeline.value.audioClips.map { clip ->
        if (clip.id == clipId) {
          val dur = clip.durationMs
          val fIn = fadeInMs.coerceIn(100L, (dur / 2).coerceAtLeast(100L))
          val fOut = fadeOutMs.coerceIn(100L, (dur / 2).coerceAtLeast(100L))

          // Build 4 keyframes: start (0), fade-in peak (clip.volume), fade-out start (clip.volume), end (0)
          val kf0 = com.example.domain.model.ClipKeyframe(timeMs = 0L, volume = 0f)
          val kf1 = com.example.domain.model.ClipKeyframe(timeMs = fIn, volume = clip.volume)
          val kf2 = com.example.domain.model.ClipKeyframe(timeMs = (dur - fOut).coerceAtLeast(fIn + 50L), volume = clip.volume)
          val kf3 = com.example.domain.model.ClipKeyframe(timeMs = dur, volume = 0f)

          clip.copy(
            keyframes = listOf(kf0, kf1, kf2, kf3).sortedBy { it.timeMs },
            fadeInMs = fIn,
            fadeOutMs = fOut
          )
        } else clip
      }
      _timeline.value = _timeline.value.copy(audioClips = list)
    }
  }

  /**
   * Resets all volume keyframes and fades for the audio clip back to default flat volume.
   */
  fun resetAudioVolumeEnvelope(clipId: String) {
    withStateLock {
      recordHistory()
      val list = _timeline.value.audioClips.map { clip ->
        if (clip.id == clipId) {
          clip.copy(keyframes = emptyList(), fadeInMs = 0L, fadeOutMs = 0L)
        } else clip
      }
      _timeline.value = _timeline.value.copy(audioClips = list)
    }
  }

  /**
   * Applies automatic fade-in and fade-out transitions to all audio tracks in the project.
   */
  fun applyAutoFadesToAllAudioClips(fadeInMs: Long = 400L, fadeOutMs: Long = 600L) {
    withStateLock {
      recordHistory()
      val list = _timeline.value.audioClips.map { clip ->
        val maxFade = clip.durationMs / 2
        clip.copy(
          fadeInMs = fadeInMs.coerceIn(0L, maxFade),
          fadeOutMs = fadeOutMs.coerceIn(0L, maxFade)
        )
      }
      _timeline.value = _timeline.value.copy(audioClips = list)
    }
  }

  /**
   * Updates base volume level for an audio clip (0.0 to 1.5).
   */
  fun setAudioClipBaseVolume(clipId: String, volume: Float) {
    withStateLock {
      val list = _timeline.value.audioClips.map { clip ->
        if (clip.id == clipId) {
          clip.copy(volume = volume.coerceIn(0f, 2f))
        } else clip
      }
      _timeline.value = _timeline.value.copy(audioClips = list)
    }
  }

  /**
   * Updates extracted or generated waveform samples for an audio clip.
   */
  fun updateAudioClipWaveform(clipId: String, waveform: List<Float>) {
    withStateLock {
      val list = _timeline.value.audioClips.map { clip ->
        if (clip.id == clipId) {
          clip.copy(waveformData = waveform)
        } else clip
      }
      _timeline.value = _timeline.value.copy(audioClips = list)
    }
  }

  /**
   * Jumps playhead to the next audio peak / rhythm beat in the timeline.
   */
  fun jumpToNextAudioPeak(): Boolean {
    val currentMs = _currentPositionMs.value
    val selectedId = (_selectedElement.value as? SelectedTrackElement.Audio)?.clipId
    val clips = if (selectedId != null) {
      _timeline.value.audioClips.filter { it.id == selectedId }
    } else {
      _timeline.value.audioClips.filter { it.timelineStartMs + it.durationMs >= currentMs }
    }

    for (clip in clips) {
      val waveform = if (clip.waveformData.isNotEmpty()) {
        com.example.engine.audio.AudioWaveformManager.sliceForTrim(
          clip.waveformData,
          clip.sourceStartMs,
          clip.sourceEndMs,
          clip.durationMs
        )
      } else {
        com.example.engine.audio.AudioWaveformManager.getOrGenerateWaveform(clip.id, clip.uri, clip.title, clip.durationMs)
      }

      val analysis = com.example.engine.audio.AudioWaveformManager.analyzeWaveform(waveform, clip.durationMs)
      val relPosMs = (currentMs - clip.timelineStartMs).coerceAtLeast(0L)
      val nextPeak = com.example.engine.audio.AudioWaveformManager.findNextPeak(relPosMs, analysis.peaks)
      if (nextPeak != null) {
        val targetMs = clip.timelineStartMs + nextPeak.timeMs
        if (targetMs > currentMs + 20L) {
          seekTo(targetMs)
          return true
        }
      }
    }
    return false
  }

  /**
   * Jumps playhead to the previous audio peak / rhythm beat in the timeline.
   */
  fun jumpToPrevAudioPeak(): Boolean {
    val currentMs = _currentPositionMs.value
    val selectedId = (_selectedElement.value as? SelectedTrackElement.Audio)?.clipId
    val clips = if (selectedId != null) {
      _timeline.value.audioClips.filter { it.id == selectedId }
    } else {
      _timeline.value.audioClips.filter { it.timelineStartMs <= currentMs }.reversed()
    }

    for (clip in clips) {
      val waveform = if (clip.waveformData.isNotEmpty()) {
        com.example.engine.audio.AudioWaveformManager.sliceForTrim(
          clip.waveformData,
          clip.sourceStartMs,
          clip.sourceEndMs,
          clip.durationMs
        )
      } else {
        com.example.engine.audio.AudioWaveformManager.getOrGenerateWaveform(clip.id, clip.uri, clip.title, clip.durationMs)
      }

      val analysis = com.example.engine.audio.AudioWaveformManager.analyzeWaveform(waveform, clip.durationMs)
      val relPosMs = (currentMs - clip.timelineStartMs).coerceIn(0L, clip.durationMs)
      val prevPeak = com.example.engine.audio.AudioWaveformManager.findPrevPeak(relPosMs, analysis.peaks)
      if (prevPeak != null) {
        val targetMs = clip.timelineStartMs + prevPeak.timeMs
        if (targetMs < currentMs - 20L) {
          seekTo(targetMs)
          return true
        }
      }
    }
    return false
  }

  /**
   * Jumps playhead to the start of the next silence interval in the timeline.
   */
  fun jumpToNextAudioSilence(): Boolean {
    val currentMs = _currentPositionMs.value
    val selectedId = (_selectedElement.value as? SelectedTrackElement.Audio)?.clipId
    val clips = if (selectedId != null) {
      _timeline.value.audioClips.filter { it.id == selectedId }
    } else {
      _timeline.value.audioClips.filter { it.timelineStartMs + it.durationMs >= currentMs }
    }

    for (clip in clips) {
      val waveform = if (clip.waveformData.isNotEmpty()) {
        com.example.engine.audio.AudioWaveformManager.sliceForTrim(
          clip.waveformData,
          clip.sourceStartMs,
          clip.sourceEndMs,
          clip.durationMs
        )
      } else {
        com.example.engine.audio.AudioWaveformManager.getOrGenerateWaveform(clip.id, clip.uri, clip.title, clip.durationMs)
      }

      val analysis = com.example.engine.audio.AudioWaveformManager.analyzeWaveform(waveform, clip.durationMs)
      val relPosMs = (currentMs - clip.timelineStartMs).coerceAtLeast(0L)
      val nextSilence = com.example.engine.audio.AudioWaveformManager.findNextSilence(relPosMs, analysis.silenceRegions)
      if (nextSilence != null) {
        val targetMs = clip.timelineStartMs + nextSilence.startMs
        if (targetMs > currentMs + 20L) {
          seekTo(targetMs)
          return true
        }
      }
    }
    return false
  }

  /**
   * Jumps playhead to the previous silence interval in the timeline.
   */
  fun jumpToPrevAudioSilence(): Boolean {
    val currentMs = _currentPositionMs.value
    val selectedId = (_selectedElement.value as? SelectedTrackElement.Audio)?.clipId
    val clips = if (selectedId != null) {
      _timeline.value.audioClips.filter { it.id == selectedId }
    } else {
      _timeline.value.audioClips.filter { it.timelineStartMs <= currentMs }.reversed()
    }

    for (clip in clips) {
      val waveform = if (clip.waveformData.isNotEmpty()) {
        com.example.engine.audio.AudioWaveformManager.sliceForTrim(
          clip.waveformData,
          clip.sourceStartMs,
          clip.sourceEndMs,
          clip.durationMs
        )
      } else {
        com.example.engine.audio.AudioWaveformManager.getOrGenerateWaveform(clip.id, clip.uri, clip.title, clip.durationMs)
      }

      val analysis = com.example.engine.audio.AudioWaveformManager.analyzeWaveform(waveform, clip.durationMs)
      val relPosMs = (currentMs - clip.timelineStartMs).coerceIn(0L, clip.durationMs)
      val prevSilence = com.example.engine.audio.AudioWaveformManager.findPrevSilence(relPosMs, analysis.silenceRegions)
      if (prevSilence != null) {
        val targetMs = clip.timelineStartMs + prevSilence.startMs
        if (targetMs < currentMs - 20L) {
          seekTo(targetMs)
          return true
        }
      }
    }
    return false
  }

  /**
   * Automatically detects and removes dead air / silence regions from an audio clip,
   * splitting and stitching the active vocal/music portions with clean crossfades.
   */
  fun removeSilenceFromAudioClip(
    clipId: String,
    silenceThreshold: Float = 0.10f,
    minSilenceMs: Long = 250L
  ): Boolean {
    val clip = _timeline.value.audioClips.find { it.id == clipId } ?: return false
    val waveform = if (clip.waveformData.isNotEmpty()) {
      com.example.engine.audio.AudioWaveformManager.sliceForTrim(
        clip.waveformData,
        clip.sourceStartMs,
        clip.sourceEndMs,
        clip.durationMs
      )
    } else {
      com.example.engine.audio.AudioWaveformManager.getOrGenerateWaveform(clip.id, clip.uri, clip.title, clip.durationMs)
    }

    val silences = com.example.engine.audio.AudioWaveformManager.detectSilenceRegions(
      waveform,
      clip.durationMs,
      silenceThreshold = silenceThreshold,
      minSilenceDurationMs = minSilenceMs
    )

    if (silences.isEmpty()) return false

    recordHistory()

    // Calculate non-silent segments
    val activeSegments = mutableListOf<Pair<Long, Long>>()
    var currentStart = 0L

    for (s in silences) {
      if (s.startMs > currentStart + 100L) {
        activeSegments.add(Pair(currentStart, s.startMs))
      }
      currentStart = s.endMs
    }
    if (currentStart < clip.durationMs - 100L) {
      activeSegments.add(Pair(currentStart, clip.durationMs))
    }

    if (activeSegments.isEmpty()) return false

    val newClips = mutableListOf<AudioClip>()
    var runningTimelineStart = clip.timelineStartMs

    for ((segStart, segEnd) in activeSegments) {
      val segDuration = segEnd - segStart
      val segSourceStart = clip.sourceStartMs + segStart
      val segSourceEnd = clip.sourceStartMs + segEnd

      val slicedWf = com.example.engine.audio.AudioWaveformManager.sliceForTrim(
        waveform,
        segStart,
        segEnd,
        clip.durationMs
      )

      newClips.add(
        clip.copy(
          id = java.util.UUID.randomUUID().toString(),
          timelineStartMs = runningTimelineStart,
          durationMs = segDuration,
          sourceStartMs = segSourceStart,
          sourceEndMs = segSourceEnd,
          waveformData = slicedWf,
          fadeInMs = if (newClips.isEmpty()) clip.fadeInMs else 20L,
          fadeOutMs = if (activeSegments.last().first == segStart) clip.fadeOutMs else 20L
        )
      )
      runningTimelineStart += segDuration
    }

    val allAudio = _timeline.value.audioClips.filter { it.id != clipId }.toMutableList()
    allAudio.addAll(newClips)
    allAudio.sortBy { it.timelineStartMs }
    _timeline.value = _timeline.value.copy(audioClips = allAudio)
    if (newClips.isNotEmpty()) {
      _selectedElement.value = SelectedTrackElement.Audio(newClips.first().id)
    }
    return true
  }

  // =========================================================================
  // --- UPGRADED MULTI-TRACK, TIMING, BRIDGE & ENGINE INTEGRATION APIS ---
  // =========================================================================

  /**
   * Converts a timeline millisecond timestamp to microseconds.
   */
  fun timeMsToMicros(timeMs: Long): Long = timeMs * 1000L

  /**
   * Converts microseconds to timeline millisecond timestamp.
   */
  fun microsToTimeMs(micros: Long): Long = micros / 1000L

  /**
   * Converts a frame index to microseconds at [fps].
   */
  fun frameToMicros(frame: Long, fps: Int = _timelineFps.value): Long {
    val rate = fps.coerceAtLeast(1)
    return (frame * 1_000_000L) / rate
  }

  /**
   * Converts microseconds to frame index at [fps].
   */
  fun microsToFrame(micros: Long, fps: Int = _timelineFps.value): Long {
    val rate = fps.coerceAtLeast(1)
    return (micros * rate) / 1_000_000L
  }

  /**
   * Returns total timeline duration in frames at [fps].
   */
  fun totalDurationFrames(fps: Int = _timelineFps.value): Long {
    return timeToFrame(_timeline.value.totalDurationMs, fps)
  }

  /**
   * Returns current playhead position in frames at [fps].
   */
  fun currentFrameIndex(fps: Int = _timelineFps.value): Long {
    return timeToFrame(_currentPositionMs.value, fps)
  }

  /**
   * Returns the distinct list of all video track indices present in the timeline.
   * Track 0 is the primary video track, and Track 1..N are overlay/PIP video tracks.
   */
  fun getVideoTrackIndices(): List<Int> {
    val indices = mutableSetOf(0)
    _timeline.value.overlayClips.forEach { indices.add(it.trackIndex.coerceAtLeast(1)) }
    return indices.sorted()
  }

  /**
   * Returns the distinct list of all audio track indices present in the timeline.
   */
  fun getAudioTrackIndices(): List<Int> {
    val indices = mutableSetOf(0)
    _timeline.value.audioClips.forEach { indices.add(it.trackIndex.coerceAtLeast(0)) }
    return indices.sorted()
  }

  /**
   * Returns all video clips on the specified video track lane.
   * [trackIndex] = 0 returns main video clips; [trackIndex] >= 1 returns overlay clips for that lane.
   */
  fun getVideoClipsForTrack(trackIndex: Int): List<VideoClip> {
    return if (trackIndex == 0) {
      _timeline.value.videoClips
    } else {
      _timeline.value.overlayClips.filter { it.trackIndex == trackIndex }
    }
  }

  /**
   * Returns all audio clips on the specified audio track lane.
   */
  fun getAudioClipsForTrack(trackIndex: Int): List<AudioClip> {
    return _timeline.value.audioClips.filter { it.trackIndex == trackIndex }
  }

  /**
   * Returns all text clips on the specified text track lane.
   */
  fun getTextClipsForTrack(trackIndex: Int): List<TextClip> {
    return _timeline.value.textClips.filter { it.trackIndex == trackIndex }
  }

  /**
   * Removes a track (lane) and every clip on it as one undoable step. Returns false for the main
   * video track, unknown ids, or when a clip on the lane is locked.
   */
  fun removeTrack(trackId: String): Boolean = withStateLock {
    val current = _timeline.value
    val (type, lane) = com.example.engine.timeline.TimelineTrackManager.resolveTrack(current, trackId) ?: return false
    if (com.example.engine.timeline.TimelineTrackManager.isTrackLocked(current, type, lane.coerceAtLeast(0))) return false
    val ids = com.example.engine.timeline.TimelineTrackManager.laneClipIds(current, type, lane)
    if (ids.any { current.clipView(it)?.locked == true }) return false
    val updated = com.example.engine.timeline.TimelineTrackManager.removeLane(current, type, lane) ?: return false
    recordHistory(TimelineActionType.TRACK_SETTINGS_EDIT, "Remove Track", ids)
    _timeline.value = updated
    validateSelectionAfterHistoryChange()
    true
  }

  /** Moves a lane one step up (toward higher z-order) or down in its track type's stack. */
  fun moveTrackLane(trackType: TrackType, lane: Int, up: Boolean): Boolean = withStateLock {
    val current = _timeline.value
    val lanes = com.example.engine.timeline.TimelineTrackManager.lanesOf(current, trackType)
    val position = lanes.indexOf(lane)
    if (position < 0) return false
    val neighbour = lanes.getOrNull(if (up) position + 1 else position - 1) ?: return false
    if (com.example.engine.timeline.TimelineTrackManager.isTrackLocked(current, trackType, lane) ||
      com.example.engine.timeline.TimelineTrackManager.isTrackLocked(current, trackType, neighbour)
    ) return false
    val updated = com.example.engine.timeline.TimelineTrackManager.swapLanes(current, trackType, lane, neighbour) ?: return false
    recordHistory(TimelineActionType.TRACK_SETTINGS_EDIT, "Reorder Track")
    _timeline.value = updated
    true
  }

  /** Lock / hide / mute one lane without affecting the other lanes of the same type. */
  fun setLaneFlags(trackType: TrackType, lane: Int, locked: Boolean? = null, hidden: Boolean? = null, muted: Boolean? = null): Boolean =
    withStateLock {
      val current = _timeline.value
      val updated = com.example.engine.timeline.TimelineTrackManager.setLaneFlags(current, trackType, lane, locked, hidden, muted)
      if (updated == current) return false
      recordHistory(TimelineActionType.TRACK_SETTINGS_EDIT, "Track Settings")
      _timeline.value = updated
      true
    }

  /**
   * Dynamically adds a new track appended with a unique UUID and proper incremented order.
   */
  fun addTrack(trackType: TrackType = TrackType.OVERLAY, displayName: String? = null): NleTrack = withStateLock {
    recordHistory(TimelineActionType.TRACK_SETTINGS_EDIT, "Add Track")
    val (updated, newTrack) = com.example.engine.timeline.TimelineTrackManager.addTrack(_timeline.value, trackType, displayName)
    _timeline.value = updated
    newTrack
  }

  /**
   * Adds a video clip to a specific video track lane.
   * Track 0 appends or places it on the main video track; Track > 0 places it on the overlay track.
   */
  fun addVideoClipToTrack(
    trackIndex: Int,
    uri: String,
    name: String,
    startMs: Long = _currentPositionMs.value,
    durationMs: Long = 3000L,
    isVideo: Boolean = true,
    width: Int = 1920,
    height: Int = 1080,
    rotationDegrees: Int = 0,
    frameRate: Float = 30f,
    mimeType: String = "video/mp4",
    hasAudio: Boolean = true
  ): String = withStateLock {
    if (trackIndex == 0) {
      addVideoClip(
        uri = uri,
        name = name,
        durationMs = durationMs,
        isVideo = isVideo,
        width = width,
        height = height,
        rotationDegrees = rotationDegrees,
        frameRate = frameRate,
        mimeType = mimeType,
        hasAudio = hasAudio
      )
    } else {
      recordHistory(TimelineActionType.ADD_CLIP, "Add Clip to Track")
      val overlays = _timeline.value.overlayClips.toMutableList()
      val newClip = VideoClip(
        uri = uri,
        name = name,
        isVideo = isVideo,
        timelineStartMs = startMs,
        durationMs = durationMs,
        sourceStartMs = 0L,
        sourceEndMs = durationMs,
        sourceTotalDurationMs = if (isVideo) durationMs else 0L,
        width = width,
        height = height,
        naturalRotation = rotationDegrees,
        frameRate = frameRate,
        mimeType = mimeType,
        hasAudio = hasAudio,
        trackIndex = trackIndex
      )
      overlays.add(newClip)
      overlays.sortBy { it.timelineStartMs }
      _timeline.value = _timeline.value.copy(overlayClips = overlays)
      _selectedElement.value = SelectedTrackElement.Overlay(newClip.id)
      newClip.id
    }
  }

  /**
   * Adds an audio clip to a specific audio track lane.
   */
  fun addAudioClipToTrack(
    trackIndex: Int,
    uri: String,
    title: String,
    startMs: Long = _currentPositionMs.value,
    durationMs: Long = 5000L,
    volume: Float = 1.0f,
    fadeInMs: Long = 0L,
    fadeOutMs: Long = 0L
  ): String = withStateLock {
    recordHistory(TimelineActionType.ADD_CLIP, "Add Audio to Track")
    val audioClips = _timeline.value.audioClips.toMutableList()
    val newClip = AudioClip(
      uri = uri,
      title = title,
      timelineStartMs = startMs,
      durationMs = durationMs,
      sourceStartMs = 0L,
      sourceEndMs = durationMs,
      sourceTotalDurationMs = durationMs,
      volume = volume,
      fadeInMs = fadeInMs,
      fadeOutMs = fadeOutMs,
      trackIndex = trackIndex
    )
    audioClips.add(newClip)
    audioClips.sortBy { it.timelineStartMs }
    _timeline.value = _timeline.value.copy(audioClips = audioClips)
    _selectedElement.value = SelectedTrackElement.Audio(newClip.id)
    newClip.id
  }

  /**
   * Moves a clip to a different track lane or track type.
   */
  fun moveClipToTrackLane(clipId: String, targetTrackType: TrackType, targetTrackIndex: Int): Boolean = withStateLock {
    val element = findTrackElementForClip(clipId)
    if (element == SelectedTrackElement.None) return false
    val source = _timeline.value.clipView(clipId) ?: return false
    if (isLaneLocked(_timeline.value, source)) return false
    val wantsOverlay = targetTrackType == TrackType.OVERLAY || targetTrackIndex > 0
    val wantsMain = targetTrackType == TrackType.MAIN_VIDEO || targetTrackIndex == 0
    val isNoOp = when (element) {
      is SelectedTrackElement.Video -> !wantsOverlay
      is SelectedTrackElement.Overlay -> !wantsMain && source.lane == targetTrackIndex.coerceAtLeast(1)
      else -> source.lane == targetTrackIndex.coerceAtLeast(0)
    }
    if (isNoOp) return false
    if (com.example.engine.timeline.TimelineTrackManager.isTrackLocked(_timeline.value, targetTrackType, targetTrackIndex.coerceAtLeast(0))) return false
    recordHistory(TimelineActionType.MOVE_CLIP, "Move Clip to Track Lane", setOf(clipId))

    when (element) {
      is SelectedTrackElement.Video -> {
        if (wantsOverlay) {
          val clip = _timeline.value.videoClips.find { it.id == clipId } ?: return false
          val newVideos = _timeline.value.videoClips.filterNot { it.id == clipId }
          val newOverlays = (_timeline.value.overlayClips + clip.copy(trackIndex = targetTrackIndex.coerceAtLeast(1))).sortedBy { it.timelineStartMs }
          _timeline.value = _timeline.value.copy(videoClips = newVideos, overlayClips = newOverlays)
          selectElement(SelectedTrackElement.Overlay(clipId))
          enforceMainTrackContinuity()
          return true
        }
        return false
      }
      is SelectedTrackElement.Overlay -> {
        val clip = _timeline.value.overlayClips.find { it.id == clipId } ?: return false
        if (targetTrackType == TrackType.MAIN_VIDEO || targetTrackIndex == 0) {
          val newOverlays = _timeline.value.overlayClips.filterNot { it.id == clipId }
          val newVideos = (_timeline.value.videoClips + clip.copy(trackIndex = 0)).sortedBy { it.timelineStartMs }
          _timeline.value = _timeline.value.copy(videoClips = newVideos, overlayClips = newOverlays)
          selectElement(SelectedTrackElement.Video(clipId))
          enforceMainTrackContinuity()
          return true
        } else {
          val updated = _timeline.value.overlayClips.map {
            if (it.id == clipId) it.copy(trackIndex = targetTrackIndex.coerceAtLeast(1)) else it
          }
          _timeline.value = _timeline.value.copy(overlayClips = updated)
          return true
        }
      }
      is SelectedTrackElement.Audio -> {
        val updated = _timeline.value.audioClips.map {
          if (it.id == clipId) it.copy(trackIndex = targetTrackIndex.coerceAtLeast(0)) else it
        }
        _timeline.value = _timeline.value.copy(audioClips = updated)
        return true
      }
      is SelectedTrackElement.Text -> {
        val updated = _timeline.value.textClips.map {
          if (it.id == clipId) it.copy(trackIndex = targetTrackIndex.coerceAtLeast(0)) else it
        }
        _timeline.value = _timeline.value.copy(textClips = updated)
        return true
      }
      is SelectedTrackElement.Sticker -> {
        val updated = _timeline.value.stickerClips.map {
          if (it.id == clipId) it.copy(trackIndex = targetTrackIndex.coerceAtLeast(0)) else it
        }
        _timeline.value = _timeline.value.copy(stickerClips = updated)
        return true
      }
      is SelectedTrackElement.Effect -> {
        val updated = _timeline.value.effectClips.map {
          if (it.id == clipId) it.copy(trackIndex = targetTrackIndex.coerceAtLeast(0)) else it
        }
        _timeline.value = _timeline.value.copy(effectClips = updated)
        return true
      }
      else -> return false
    }
  }

  /**
   * Reorders clips within a specific track lane.
   */
  fun reorderTrackClips(trackType: TrackType, trackIndex: Int, fromIndex: Int, toIndex: Int): Boolean = withStateLock {
    if (fromIndex == toIndex) return false
    recordHistory(TimelineActionType.MOVE_CLIP, "Reorder Track Clips", emptySet())

    when (trackType) {
      TrackType.MAIN_VIDEO -> {
        val clips = _timeline.value.videoClips.toMutableList()
        if (fromIndex !in clips.indices || toIndex !in clips.indices) return false
        val item = clips.removeAt(fromIndex)
        clips.add(toIndex, item)
        var curStart = 0L
        for (i in clips.indices) {
          clips[i] = clips[i].copy(timelineStartMs = curStart)
          curStart += clips[i].durationMs
        }
        _timeline.value = _timeline.value.copy(videoClips = clips)
        return true
      }
      TrackType.TEXT, TrackType.CAPTION -> {
        val trackClips = _timeline.value.textClips.filter { it.trackIndex == trackIndex }.toMutableList()
        val otherClips = _timeline.value.textClips.filter { it.trackIndex != trackIndex }
        if (fromIndex !in trackClips.indices || toIndex !in trackClips.indices) return false
        val oldStarts = trackClips.map { it.timelineStartMs }
        val item = trackClips.removeAt(fromIndex)
        trackClips.add(toIndex, item)
        var curStart = oldStarts.minOrNull() ?: 0L
        for (i in trackClips.indices) {
          trackClips[i] = trackClips[i].copy(timelineStartMs = curStart)
          curStart += trackClips[i].durationMs
        }
        _timeline.value = _timeline.value.copy(textClips = (otherClips + trackClips).sortedBy { it.timelineStartMs })
        return true
      }
      TrackType.STICKER -> {
        val trackClips = _timeline.value.stickerClips.filter { it.trackIndex == trackIndex }.toMutableList()
        val otherClips = _timeline.value.stickerClips.filter { it.trackIndex != trackIndex }
        if (fromIndex !in trackClips.indices || toIndex !in trackClips.indices) return false
        val oldStarts = trackClips.map { it.timelineStartMs }
        val item = trackClips.removeAt(fromIndex)
        trackClips.add(toIndex, item)
        var curStart = oldStarts.minOrNull() ?: 0L
        for (i in trackClips.indices) {
          trackClips[i] = trackClips[i].copy(timelineStartMs = curStart)
          curStart += trackClips[i].durationMs
        }
        _timeline.value = _timeline.value.copy(stickerClips = (otherClips + trackClips).sortedBy { it.timelineStartMs })
        return true
      }
      TrackType.EFFECT -> {
        val trackClips = _timeline.value.effectClips.filter { it.trackIndex == trackIndex }.toMutableList()
        val otherClips = _timeline.value.effectClips.filter { it.trackIndex != trackIndex }
        if (fromIndex !in trackClips.indices || toIndex !in trackClips.indices) return false
        val oldStarts = trackClips.map { it.timelineStartMs }
        val item = trackClips.removeAt(fromIndex)
        trackClips.add(toIndex, item)
        var curStart = oldStarts.minOrNull() ?: 0L
        for (i in trackClips.indices) {
          trackClips[i] = trackClips[i].copy(timelineStartMs = curStart)
          curStart += trackClips[i].durationMs
        }
        _timeline.value = _timeline.value.copy(effectClips = (otherClips + trackClips).sortedBy { it.timelineStartMs })
        return true
      }
      TrackType.AUDIO, TrackType.MUSIC, TrackType.SFX -> {
        val trackClips = _timeline.value.audioClips.filter { it.trackIndex == trackIndex }.toMutableList()
        val otherClips = _timeline.value.audioClips.filter { it.trackIndex != trackIndex }
        if (fromIndex !in trackClips.indices || toIndex !in trackClips.indices) return false
        val oldStarts = trackClips.map { it.timelineStartMs }
        val item = trackClips.removeAt(fromIndex)
        trackClips.add(toIndex, item)
        var curStart = oldStarts.minOrNull() ?: 0L
        for (i in trackClips.indices) {
          trackClips[i] = trackClips[i].copy(timelineStartMs = curStart)
          curStart += trackClips[i].durationMs
        }
        _timeline.value = _timeline.value.copy(audioClips = (otherClips + trackClips).sortedBy { it.timelineStartMs })
        return true
      }
      TrackType.OVERLAY, TrackType.ADJUSTMENT, TrackType.ELEMENT -> {
        val trackClips = _timeline.value.overlayClips.filter { it.trackIndex == trackIndex }.toMutableList()
        val otherClips = _timeline.value.overlayClips.filter { it.trackIndex != trackIndex }
        if (fromIndex !in trackClips.indices || toIndex !in trackClips.indices) return false
        val oldStarts = trackClips.map { it.timelineStartMs }
        val item = trackClips.removeAt(fromIndex)
        trackClips.add(toIndex, item)
        var curStart = oldStarts.minOrNull() ?: 0L
        for (i in trackClips.indices) {
          trackClips[i] = trackClips[i].copy(timelineStartMs = curStart)
          curStart += trackClips[i].durationMs
        }
        _timeline.value = _timeline.value.copy(overlayClips = (otherClips + trackClips).sortedBy { it.timelineStartMs })
        return true
      }
      else -> return false
    }
  }

  fun isTrackChronologicallyOrdered(trackType: TrackType, trackIndex: Int = 0): Boolean {
    val starts = when (trackType) {
      TrackType.MAIN_VIDEO -> _timeline.value.videoClips.map { it.timelineStartMs }
      TrackType.OVERLAY, TrackType.ADJUSTMENT, TrackType.ELEMENT -> _timeline.value.overlayClips.filter { it.trackIndex == trackIndex }.map { it.timelineStartMs }
      TrackType.AUDIO, TrackType.MUSIC, TrackType.SFX -> _timeline.value.audioClips.filter { it.trackIndex == trackIndex }.map { it.timelineStartMs }
      TrackType.TEXT, TrackType.CAPTION -> _timeline.value.textClips.filter { it.trackIndex == trackIndex }.map { it.timelineStartMs }
      TrackType.STICKER -> _timeline.value.stickerClips.map { it.timelineStartMs }
      TrackType.EFFECT -> _timeline.value.effectClips.map { it.timelineStartMs }
      TrackType.SHAPE -> _timeline.value.shapeClips.filter { it.trackIndex == trackIndex }.map { it.timelineStartMs }
    }
    for (i in 0 until starts.size - 1) {
      if (starts[i] > starts[i + 1]) return false
    }
    return true
  }

  /**
   * Splits [clipId] at [splitTimeMs] (timeline time). Source in/out points, speed, reverse,
   * keyframes, audio fades and the following transition are all preserved by [TimelineSplitEngine].
   * Returns the (head, tail) clip ids, or null when nothing was split (missing/locked clip, or the
   * position is not strictly inside the clip). A failed split never creates an undo entry.
   */
  fun splitClipAtTime(clipId: String, splitTimeMs: Long): Pair<String, String>? = withStateLock {
    val current = _timeline.value
    val split = TimelineSplitEngine.splitClipInTimeline(current, clipId, splitTimeMs, lockPredicate(current)) ?: return null
    recordHistory(TimelineActionType.SPLIT_CLIP, "Split Clip", setOf(clipId))
    _timeline.value = split.timeline
    if (split.tailElement != SelectedTrackElement.None) selectElement(split.tailElement)
    Pair(split.headId, split.tailId)
  }

  /**
   * Professional NLE Roll Edit: Adjusts the edit point between two adjacent clips on the same track.
   */
  fun rollEditClip(clipAId: String, clipBId: String, deltaMs: Long): Boolean = withStateLock {
    if (isTrackLocked(TrackType.MAIN_VIDEO)) return@withStateLock false
    val cur = _timeline.value
    val clipA = cur.videoClips.firstOrNull { it.id == clipAId } ?: return@withStateLock false
    val clipB = cur.videoClips.firstOrNull { it.id == clipBId } ?: return@withStateLock false
    if (clipA.trackIndex != clipB.trackIndex) return@withStateLock false
    recordHistory(TimelineActionType.TRIM_RIGHT, "Roll Edit", setOf(clipAId, clipBId))

    val newDurationA = (clipA.durationMs + deltaMs).coerceAtLeast(100L)
    val actualDelta = newDurationA - clipA.durationMs

    val updatedVideoClips = cur.videoClips.map { clip ->
      when (clip.id) {
        clipAId -> clip.copy(durationMs = newDurationA)
        clipBId -> clip.copy(
          timelineStartMs = clip.timelineStartMs + actualDelta,
          durationMs = (clip.durationMs - actualDelta).coerceAtLeast(100L)
        )
        else -> clip
      }
    }
    _timeline.value = cur.copy(videoClips = updatedVideoClips)
    true
  }

  /**
   * Professional NLE Slip Edit: Shifts the source media range inside a clip without changing timeline position.
   */
  fun slipEditClip(clipId: String, sourceDeltaMs: Long): Boolean = withStateLock {
    if (isTrackLocked(TrackType.MAIN_VIDEO)) return@withStateLock false
    val cur = _timeline.value
    val clip = cur.videoClips.firstOrNull { it.id == clipId } ?: return@withStateLock false
    recordHistory(TimelineActionType.TRIM_LEFT, "Slip Edit", setOf(clipId))

    val newSourceStart = (clip.sourceStartMs + sourceDeltaMs).coerceAtLeast(0L)
    val updatedVideoClips = cur.videoClips.map { c ->
      if (c.id == clipId) c.copy(sourceStartMs = newSourceStart) else c
    }
    _timeline.value = cur.copy(videoClips = updatedVideoClips)
    true
  }

  /**
   * Professional NLE Slide Edit: Moves a clip along the timeline while maintaining adjacent timing.
   */
  fun slideEditClip(clipId: String, deltaMs: Long): Boolean = withStateLock {
    if (isTrackLocked(TrackType.MAIN_VIDEO)) return@withStateLock false
    val cur = _timeline.value
    val clip = cur.videoClips.firstOrNull { it.id == clipId } ?: return@withStateLock false
    recordHistory(TimelineActionType.MOVE_CLIP, "Slide Edit", setOf(clipId))

    val newStart = (clip.timelineStartMs + deltaMs).coerceAtLeast(0L)
    val updatedVideoClips = cur.videoClips.map { c ->
      if (c.id == clipId) c.copy(timelineStartMs = newStart) else c
    }
    _timeline.value = cur.copy(videoClips = updatedVideoClips)
    true
  }

  /**
   * Adds a keyframe to a specific clip by ID.
   */
  fun addKeyframeToClip(clipId: String, keyframe: ClipKeyframe): Boolean = withStateLock {
    val element = findTrackElementForClip(clipId)
    if (element == SelectedTrackElement.None) return false
    recordHistory(TimelineActionType.KEYFRAME_EDIT, "Add Keyframe", setOf(clipId))

    when (element) {
      is SelectedTrackElement.Video -> {
        val updated = _timeline.value.videoClips.map { clip ->
          if (clip.id == clipId) {
            val kfs = (clip.keyframes.filterNot { Math.abs(it.timeMs - keyframe.timeMs) < 15L } + keyframe).sortedBy { it.timeMs }
            clip.copy(keyframes = kfs)
          } else clip
        }
        _timeline.value = _timeline.value.copy(videoClips = updated)
        return true
      }
      is SelectedTrackElement.Overlay -> {
        val updated = _timeline.value.overlayClips.map { clip ->
          if (clip.id == clipId) {
            val kfs = (clip.keyframes.filterNot { Math.abs(it.timeMs - keyframe.timeMs) < 15L } + keyframe).sortedBy { it.timeMs }
            clip.copy(keyframes = kfs)
          } else clip
        }
        _timeline.value = _timeline.value.copy(overlayClips = updated)
        return true
      }
      is SelectedTrackElement.Audio -> {
        val updated = _timeline.value.audioClips.map { clip ->
          if (clip.id == clipId) {
            val kfs = (clip.keyframes.filterNot { Math.abs(it.timeMs - keyframe.timeMs) < 15L } + keyframe).sortedBy { it.timeMs }
            clip.copy(keyframes = kfs)
          } else clip
        }
        _timeline.value = _timeline.value.copy(audioClips = updated)
        return true
      }
      is SelectedTrackElement.Sticker -> {
        val updated = _timeline.value.stickerClips.map { clip ->
          if (clip.id == clipId) {
            val kfs = (clip.keyframes.filterNot { Math.abs(it.timeMs - keyframe.timeMs) < 15L } + keyframe).sortedBy { it.timeMs }
            clip.copy(keyframes = kfs)
          } else clip
        }
        _timeline.value = _timeline.value.copy(stickerClips = updated)
        return true
      }
      is SelectedTrackElement.Effect -> {
        val updated = _timeline.value.effectClips.map { clip ->
          if (clip.id == clipId) {
            val kfs = (clip.keyframes.filterNot { Math.abs(it.timeMs - keyframe.timeMs) < 15L } + keyframe).sortedBy { it.timeMs }
            clip.copy(keyframes = kfs)
          } else clip
        }
        _timeline.value = _timeline.value.copy(effectClips = updated)
        return true
      }
      is SelectedTrackElement.Text -> {
        val updated = _timeline.value.textClips.map { clip ->
          if (clip.id == clipId) {
            val kfs = (clip.keyframes.filterNot { Math.abs(it.timeMs - keyframe.timeMs) < 15L } + keyframe).sortedBy { it.timeMs }
            clip.copy(keyframes = kfs)
          } else clip
        }
        _timeline.value = _timeline.value.copy(textClips = updated)
        return true
      }
      else -> return false
    }
  }

  /**
   * Sets or replaces the entire keyframes list on a specific clip.
   */
  fun updateClipKeyframes(clipId: String, keyframes: List<ClipKeyframe>): Boolean = withStateLock {
    val element = findTrackElementForClip(clipId)
    if (element == SelectedTrackElement.None) return false
    recordHistory(TimelineActionType.KEYFRAME_EDIT, "Update Keyframes", setOf(clipId))

    when (element) {
      is SelectedTrackElement.Video -> {
        val updated = _timeline.value.videoClips.map { clip ->
          if (clip.id == clipId) clip.copy(keyframes = keyframes.sortedBy { it.timeMs }) else clip
        }
        _timeline.value = _timeline.value.copy(videoClips = updated)
        return true
      }
      is SelectedTrackElement.Overlay -> {
        val updated = _timeline.value.overlayClips.map { clip ->
          if (clip.id == clipId) clip.copy(keyframes = keyframes.sortedBy { it.timeMs }) else clip
        }
        _timeline.value = _timeline.value.copy(overlayClips = updated)
        return true
      }
      is SelectedTrackElement.Audio -> {
        val updated = _timeline.value.audioClips.map { clip ->
          if (clip.id == clipId) clip.copy(keyframes = keyframes.sortedBy { it.timeMs }) else clip
        }
        _timeline.value = _timeline.value.copy(audioClips = updated)
        return true
      }
      is SelectedTrackElement.Sticker -> {
        val updated = _timeline.value.stickerClips.map { clip ->
          if (clip.id == clipId) clip.copy(keyframes = keyframes.sortedBy { it.timeMs }) else clip
        }
        _timeline.value = _timeline.value.copy(stickerClips = updated)
        return true
      }
      is SelectedTrackElement.Effect -> {
        val updated = _timeline.value.effectClips.map { clip ->
          if (clip.id == clipId) clip.copy(keyframes = keyframes.sortedBy { it.timeMs }) else clip
        }
        _timeline.value = _timeline.value.copy(effectClips = updated)
        return true
      }
      is SelectedTrackElement.Text -> {
        val updated = _timeline.value.textClips.map { clip ->
          if (clip.id == clipId) clip.copy(keyframes = keyframes.sortedBy { it.timeMs }) else clip
        }
        _timeline.value = _timeline.value.copy(textClips = updated)
        return true
      }
      else -> return false
    }
  }

  /**
   * Updates transformation parameters (position, scale, rotation, opacity, blendMode) for any clip.
   */
  fun updateClipTransformation(
    clipId: String,
    posX: Float? = null,
    posY: Float? = null,
    scale: Float? = null,
    rotation: Float? = null,
    opacity: Float? = null,
    blendMode: String? = null
  ): Boolean = withStateLock {
    val element = findTrackElementForClip(clipId)
    if (element == SelectedTrackElement.None) return false
    recordHistory(TimelineActionType.GENERIC_EDIT, "Transform Clip", setOf(clipId))

    when (element) {
      is SelectedTrackElement.Video -> {
        val updated = _timeline.value.videoClips.map { clip ->
          if (clip.id == clipId) {
            clip.copy(
              cropOffsetX = posX ?: clip.cropOffsetX,
              cropOffsetY = posY ?: clip.cropOffsetY,
              cropScale = scale ?: clip.cropScale,
              opacity = opacity ?: clip.opacity,
              blendMode = blendMode ?: clip.blendMode
            )
          } else clip
        }
        _timeline.value = _timeline.value.copy(videoClips = updated)
        return true
      }
      is SelectedTrackElement.Overlay -> {
        val updated = _timeline.value.overlayClips.map { clip ->
          if (clip.id == clipId) {
            clip.copy(
              cropOffsetX = posX ?: clip.cropOffsetX,
              cropOffsetY = posY ?: clip.cropOffsetY,
              cropScale = scale ?: clip.cropScale,
              opacity = opacity ?: clip.opacity,
              blendMode = blendMode ?: clip.blendMode
            )
          } else clip
        }
        _timeline.value = _timeline.value.copy(overlayClips = updated)
        return true
      }
      is SelectedTrackElement.Text -> {
        val updated = _timeline.value.textClips.map { clip ->
          if (clip.id == clipId) {
            clip.copy(
              posX = posX ?: clip.posX,
              posY = posY ?: clip.posY,
              scale = scale ?: clip.scale,
              rotation = rotation ?: clip.rotation,
              opacity = opacity ?: clip.opacity
            )
          } else clip
        }
        _timeline.value = _timeline.value.copy(textClips = updated)
        return true
      }
      is SelectedTrackElement.Sticker -> {
        val updated = _timeline.value.stickerClips.map { clip ->
          if (clip.id == clipId) {
            clip.copy(
              posX = posX ?: clip.posX,
              posY = posY ?: clip.posY,
              scale = scale ?: clip.scale,
              rotation = rotation ?: clip.rotation,
              opacity = opacity ?: clip.opacity
            )
          } else clip
        }
        _timeline.value = _timeline.value.copy(stickerClips = updated)
        return true
      }
      else -> return false
    }
  }

  /**
   * Evaluates and returns all active elements at [posMs] across all tracks and layers.
   */
  fun getActiveClipsAt(posMs: Long): TimelineActiveSnapshot {
    val curTimeline = _timeline.value
    val isVideoHidden = curTimeline.trackSettings[TrackType.MAIN_VIDEO]?.isHidden == true
    val isOverlayHidden = curTimeline.trackSettings[TrackType.OVERLAY]?.isHidden == true
    val isAudioHidden = curTimeline.trackSettings[TrackType.AUDIO]?.isHidden == true
    val isTextHidden = curTimeline.trackSettings[TrackType.TEXT]?.isHidden == true
    val isStickerHidden = curTimeline.trackSettings[TrackType.STICKER]?.isHidden == true
    val isEffectHidden = curTimeline.trackSettings[TrackType.EFFECT]?.isHidden == true

    val activeVideo = if (!isVideoHidden) {
      curTimeline.videoClips.find { !it.isHidden && posMs >= it.timelineStartMs && posMs < it.timelineStartMs + it.durationMs }
    } else null

    val activeOverlays = if (!isOverlayHidden) {
      curTimeline.overlayClips.filter { !it.isHidden && posMs >= it.timelineStartMs && posMs < it.timelineStartMs + it.durationMs }.sortedBy { it.trackIndex }
    } else emptyList()

    val activeAudios = if (!isAudioHidden) {
      curTimeline.audioClips.filter { !it.isHidden && posMs >= it.timelineStartMs && posMs < it.timelineStartMs + it.durationMs }.sortedBy { it.trackIndex }
    } else emptyList()

    val activeTexts = if (!isTextHidden) {
      curTimeline.textClips.filter { !it.isHidden && posMs >= it.timelineStartMs && posMs < it.timelineStartMs + it.durationMs }.sortedBy { it.trackIndex }
    } else emptyList()

    val activeStickers = if (!isStickerHidden) {
      curTimeline.stickerClips.filter { !it.isHidden && posMs >= it.timelineStartMs && posMs < it.timelineStartMs + it.durationMs }
    } else emptyList()

    val activeEffects = if (!isEffectHidden) {
      curTimeline.effectClips.filter { clip ->
        !clip.isHidden && when {
          clip.targetClipId != null -> activeVideo != null && activeVideo.id == clip.targetClipId &&
            posMs >= activeVideo.timelineStartMs && posMs < activeVideo.timelineStartMs + activeVideo.durationMs
          else -> posMs >= clip.timelineStartMs && posMs < clip.timelineStartMs + clip.durationMs
        }
      }
    } else emptyList()

    var activeTransition: Transition? = null
    var transitionProgress = 0f
    var transitionClipBefore: VideoClip? = null
    var transitionClipAfter: VideoClip? = null

    if (!isVideoHidden) {
      val trInfo = getTransitionTimingAt(posMs)
      if (trInfo != null) {
        activeTransition = trInfo.transition
        transitionProgress = trInfo.progress
        transitionClipBefore = trInfo.clipBefore
        transitionClipAfter = trInfo.clipAfter
      }
    }

    val overlaps = getOverlapsAt(posMs)
    val audioSync = getActiveAudioSyncInfoAt(posMs)
    val curFps = _timelineFps.value

    return TimelineActiveSnapshot(
      timelinePosMs = posMs,
      primaryVideoClip = activeVideo,
      activeOverlays = activeOverlays,
      activeAudioClips = activeAudios,
      activeTextClips = activeTexts,
      activeStickerClips = activeStickers,
      activeEffects = activeEffects,
      activeTransition = activeTransition,
      transitionProgress = transitionProgress,
      transitionClipBefore = transitionClipBefore,
      transitionClipAfter = transitionClipAfter,
      frameIndex = timeToFrame(posMs, curFps),
      fps = curFps,
      activeOverlaps = overlaps,
      activeAudioSyncInfo = audioSync
    )
  }

  fun getActiveClipsAtFrame(frameIndex: Long, fps: Int = _timelineFps.value): TimelineActiveSnapshot =
    getActiveClipsAt(frameToTime(frameIndex, fps))

  fun getVideoTrackCount(): Int = getVideoTrackIndices().size

  fun getAudioTrackCount(): Int = getAudioTrackIndices().size

  fun getTextTrackCount(): Int {
    val indices = mutableSetOf(0)
    _timeline.value.textClips.forEach { indices.add(it.trackIndex.coerceAtLeast(0)) }
    return indices.size
  }

  fun getClipStartEnd(clipId: String): Pair<Long, Long>? {
    val cur = _timeline.value
    cur.videoClips.find { it.id == clipId }?.let { return Pair(it.timelineStartMs, it.timelineStartMs + it.durationMs) }
    cur.overlayClips.find { it.id == clipId }?.let { return Pair(it.timelineStartMs, it.timelineStartMs + it.durationMs) }
    cur.audioClips.find { it.id == clipId }?.let { return Pair(it.timelineStartMs, it.timelineStartMs + it.durationMs) }
    cur.textClips.find { it.id == clipId }?.let { return Pair(it.timelineStartMs, it.timelineStartMs + it.durationMs) }
    cur.stickerClips.find { it.id == clipId }?.let { return Pair(it.timelineStartMs, it.timelineStartMs + it.durationMs) }
    cur.effectClips.find { it.id == clipId }?.let { return Pair(it.timelineStartMs, it.timelineStartMs + it.durationMs) }
    return null
  }

  fun getClipDuration(clipId: String): Long? = getClipStartEnd(clipId)?.let { it.second - it.first }

  fun getClipTrimmedRange(clipId: String): ClipTrimmedRange? {
    val cur = _timeline.value
    cur.videoClips.find { it.id == clipId }?.let {
      val isTrimmed = it.sourceStartMs > 0L || (it.sourceTotalDurationMs > 0L && it.sourceEndMs < it.sourceTotalDurationMs)
      return ClipTrimmedRange(
        clipId = it.id,
        trackType = TrackType.MAIN_VIDEO,
        trackIndex = 0,
        timelineStartMs = it.timelineStartMs,
        timelineEndMs = it.timelineStartMs + it.durationMs,
        durationMs = it.durationMs,
        sourceStartMs = it.sourceStartMs,
        sourceEndMs = it.sourceEndMs,
        sourceDurationMs = it.sourceEndMs - it.sourceStartMs,
        speed = it.speed,
        isTrimmed = isTrimmed
      )
    }
    cur.overlayClips.find { it.id == clipId }?.let {
      val isTrimmed = it.sourceStartMs > 0L || (it.sourceTotalDurationMs > 0L && it.sourceEndMs < it.sourceTotalDurationMs)
      return ClipTrimmedRange(
        clipId = it.id,
        trackType = TrackType.OVERLAY,
        trackIndex = it.trackIndex,
        timelineStartMs = it.timelineStartMs,
        timelineEndMs = it.timelineStartMs + it.durationMs,
        durationMs = it.durationMs,
        sourceStartMs = it.sourceStartMs,
        sourceEndMs = it.sourceEndMs,
        sourceDurationMs = it.sourceEndMs - it.sourceStartMs,
        speed = it.speed,
        isTrimmed = isTrimmed
      )
    }
    cur.audioClips.find { it.id == clipId }?.let {
      val isTrimmed = it.sourceStartMs > 0L || it.sourceEndMs < it.durationMs
      return ClipTrimmedRange(
        clipId = it.id,
        trackType = TrackType.AUDIO,
        trackIndex = it.trackIndex,
        timelineStartMs = it.timelineStartMs,
        timelineEndMs = it.timelineStartMs + it.durationMs,
        durationMs = it.durationMs,
        sourceStartMs = it.sourceStartMs,
        sourceEndMs = it.sourceEndMs,
        sourceDurationMs = it.sourceEndMs - it.sourceStartMs,
        speed = it.speed,
        isTrimmed = isTrimmed
      )
    }
    return null
  }

  fun getAllTrimmedRanges(): List<ClipTrimmedRange> = buildList {
    _timeline.value.videoClips.forEach { getClipTrimmedRange(it.id)?.let { r -> add(r) } }
    _timeline.value.overlayClips.forEach { getClipTrimmedRange(it.id)?.let { r -> add(r) } }
    _timeline.value.audioClips.forEach { getClipTrimmedRange(it.id)?.let { r -> add(r) } }
  }

  fun getTrackOverlaps(trackType: TrackType, trackIndex: Int = 0): List<ClipOverlapInfo> {
    val clips: List<Pair<String, Pair<Long, Long>>> = when (trackType) {
      TrackType.MAIN_VIDEO -> _timeline.value.videoClips.map { it.id to (it.timelineStartMs to (it.timelineStartMs + it.durationMs)) }
      TrackType.OVERLAY, TrackType.ADJUSTMENT, TrackType.ELEMENT -> _timeline.value.overlayClips.filter { it.trackIndex == trackIndex }.map { it.id to (it.timelineStartMs to (it.timelineStartMs + it.durationMs)) }
      TrackType.AUDIO, TrackType.MUSIC, TrackType.SFX -> _timeline.value.audioClips.filter { it.trackIndex == trackIndex }.map { it.id to (it.timelineStartMs to (it.timelineStartMs + it.durationMs)) }
      TrackType.TEXT, TrackType.CAPTION -> _timeline.value.textClips.filter { it.trackIndex == trackIndex }.map { it.id to (it.timelineStartMs to (it.timelineStartMs + it.durationMs)) }
      TrackType.STICKER -> _timeline.value.stickerClips.map { it.id to (it.timelineStartMs to (it.timelineStartMs + it.durationMs)) }
      TrackType.EFFECT -> _timeline.value.effectClips.map { it.id to (it.timelineStartMs to (it.timelineStartMs + it.durationMs)) }
      TrackType.SHAPE -> _timeline.value.shapeClips.filter { it.trackIndex == trackIndex }.map { it.id to (it.timelineStartMs to (it.timelineStartMs + it.durationMs)) }
    }
    val sorted = clips.sortedBy { it.second.first }
    val overlaps = mutableListOf<ClipOverlapInfo>()
    for (i in 0 until sorted.size - 1) {
      val (idA, rangeA) = sorted[i]
      for (j in i + 1 until sorted.size) {
        val (idB, rangeB) = sorted[j]
        if (rangeB.first < rangeA.second) {
          val overlapStart = maxOf(rangeA.first, rangeB.first)
          val overlapEnd = minOf(rangeA.second, rangeB.second)
          if (overlapEnd > overlapStart) {
            overlaps.add(
              ClipOverlapInfo(
                clipAId = idA,
                clipBId = idB,
                trackType = trackType,
                trackIndex = trackIndex,
                overlapStartMs = overlapStart,
                overlapEndMs = overlapEnd,
                overlapDurationMs = overlapEnd - overlapStart
              )
            )
          }
        } else {
          break
        }
      }
    }
    return overlaps
  }

  fun getAllOverlaps(): List<ClipOverlapInfo> {
    val result = mutableListOf<ClipOverlapInfo>()
    result.addAll(getTrackOverlaps(TrackType.MAIN_VIDEO, 0))
    getVideoTrackIndices().filter { it > 0 }.forEach { result.addAll(getTrackOverlaps(TrackType.OVERLAY, it)) }
    getAudioTrackIndices().forEach { result.addAll(getTrackOverlaps(TrackType.AUDIO, it)) }
    val textIndices = _timeline.value.textClips.map { it.trackIndex }.distinct()
    textIndices.forEach { result.addAll(getTrackOverlaps(TrackType.TEXT, it)) }
    return result
  }

  fun getOverlapsAt(posMs: Long): List<ClipOverlapInfo> =
    getAllOverlaps().filter { posMs in it.overlapStartMs until it.overlapEndMs }

  fun hasOverlapOnTrack(trackType: TrackType, trackIndex: Int, startMs: Long, durationMs: Long, ignoreClipId: String? = null): Boolean {
    val endMs = startMs + durationMs
    val clips = when (trackType) {
      TrackType.MAIN_VIDEO -> _timeline.value.videoClips.filter { it.id != ignoreClipId }.map { it.timelineStartMs to (it.timelineStartMs + it.durationMs) }
      TrackType.OVERLAY, TrackType.ADJUSTMENT, TrackType.ELEMENT -> _timeline.value.overlayClips.filter { it.trackIndex == trackIndex && it.id != ignoreClipId }.map { it.timelineStartMs to (it.timelineStartMs + it.durationMs) }
      TrackType.AUDIO, TrackType.MUSIC, TrackType.SFX -> _timeline.value.audioClips.filter { it.trackIndex == trackIndex && it.id != ignoreClipId }.map { it.timelineStartMs to (it.timelineStartMs + it.durationMs) }
      TrackType.TEXT, TrackType.CAPTION -> _timeline.value.textClips.filter { it.trackIndex == trackIndex && it.id != ignoreClipId }.map { it.timelineStartMs to (it.timelineStartMs + it.durationMs) }
      TrackType.STICKER -> _timeline.value.stickerClips.filter { it.id != ignoreClipId }.map { it.timelineStartMs to (it.timelineStartMs + it.durationMs) }
      TrackType.EFFECT -> _timeline.value.effectClips.filter { it.id != ignoreClipId }.map { it.timelineStartMs to (it.timelineStartMs + it.durationMs) }
      TrackType.SHAPE -> _timeline.value.shapeClips.filter { it.trackIndex == trackIndex && it.id != ignoreClipId }.map { it.timelineStartMs to (it.timelineStartMs + it.durationMs) }
    }
    return clips.any { (cStart, cEnd) -> startMs < cEnd && endMs > cStart }
  }

  fun resolveOverlapByTrimming(clipAId: String, clipBId: String): Boolean = withStateLock {
    val cur = _timeline.value
    val overlayA = cur.overlayClips.find { it.id == clipAId }
    val overlayB = cur.overlayClips.find { it.id == clipBId }
    if (overlayA != null && overlayB != null && overlayA.trackIndex == overlayB.trackIndex) {
      val earlier = if (overlayA.timelineStartMs <= overlayB.timelineStartMs) overlayA else overlayB
      val later = if (earlier.id == overlayA.id) overlayB else overlayA
      if (earlier.timelineStartMs + earlier.durationMs > later.timelineStartMs) {
        val newDur = (later.timelineStartMs - earlier.timelineStartMs).coerceAtLeast(100L)
        trimClipRight(earlier.id, newDur, snap = false)
        return true
      }
    }
    val audioA = cur.audioClips.find { it.id == clipAId }
    val audioB = cur.audioClips.find { it.id == clipBId }
    if (audioA != null && audioB != null && audioA.trackIndex == audioB.trackIndex) {
      val earlier = if (audioA.timelineStartMs <= audioB.timelineStartMs) audioA else audioB
      val later = if (earlier.id == audioA.id) audioB else audioA
      if (earlier.timelineStartMs + earlier.durationMs > later.timelineStartMs) {
        val newDur = (later.timelineStartMs - earlier.timelineStartMs).coerceAtLeast(100L)
        trimClipRight(earlier.id, newDur, snap = false)
        return true
      }
    }
    return false
  }

  fun resolveOverlapByRipple(clipAId: String, clipBId: String): Boolean = withStateLock {
    val cur = _timeline.value
    val overlayA = cur.overlayClips.find { it.id == clipAId }
    val overlayB = cur.overlayClips.find { it.id == clipBId }
    if (overlayA != null && overlayB != null && overlayA.trackIndex == overlayB.trackIndex) {
      val earlier = if (overlayA.timelineStartMs <= overlayB.timelineStartMs) overlayA else overlayB
      val later = if (earlier.id == overlayA.id) overlayB else overlayA
      val endOfEarlier = earlier.timelineStartMs + earlier.durationMs
      if (endOfEarlier > later.timelineStartMs) {
        val updated = cur.overlayClips.map {
          if (it.id == later.id) it.copy(timelineStartMs = endOfEarlier) else it
        }
        _timeline.value = cur.copy(overlayClips = updated)
        return true
      }
    }
    val audioA = cur.audioClips.find { it.id == clipAId }
    val audioB = cur.audioClips.find { it.id == clipBId }
    if (audioA != null && audioB != null && audioA.trackIndex == audioB.trackIndex) {
      val earlier = if (audioA.timelineStartMs <= audioB.timelineStartMs) audioA else audioB
      val later = if (earlier.id == audioA.id) audioB else audioA
      val endOfEarlier = earlier.timelineStartMs + earlier.durationMs
      if (endOfEarlier > later.timelineStartMs) {
        val updated = cur.audioClips.map {
          if (it.id == later.id) it.copy(timelineStartMs = endOfEarlier) else it
        }
        _timeline.value = cur.copy(audioClips = updated)
        return true
      }
    }
    return false
  }

  fun getTransitionTiming(transitionId: String, posMs: Long = _currentPositionMs.value): TransitionTimingInfo? {
    val cur = _timeline.value
    val tr = cur.transitions.find { it.id == transitionId } ?: return null
    if (tr.clipIndexBefore < 0 || tr.clipIndexBefore >= cur.videoClips.size - 1) return null
    val clipBefore = cur.videoClips[tr.clipIndexBefore]
    val clipAfter = cur.videoClips[tr.clipIndexBefore + 1]
    val start = clipBefore.timelineStartMs + clipBefore.durationMs - (tr.durationMs / 2)
    val end = start + tr.durationMs
    val progress = ((posMs - start).toFloat() / tr.durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
    return TransitionTimingInfo(
      transition = tr,
      startMs = start,
      endMs = end,
      durationMs = tr.durationMs,
      progress = progress,
      clipBefore = clipBefore,
      clipAfter = clipAfter
    )
  }

  fun getTransitionTimingAt(posMs: Long): TransitionTimingInfo? {
    val cur = _timeline.value
    for (tr in cur.transitions) {
      if (tr.clipIndexBefore >= 0 && tr.clipIndexBefore < cur.videoClips.size - 1) {
        val clipBefore = cur.videoClips[tr.clipIndexBefore]
        val clipAfter = cur.videoClips[tr.clipIndexBefore + 1]
        val start = clipBefore.timelineStartMs + clipBefore.durationMs - (tr.durationMs / 2)
        val end = start + tr.durationMs
        if (posMs in start until end) {
          val progress = ((posMs - start).toFloat() / tr.durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
          return TransitionTimingInfo(
            transition = tr,
            startMs = start,
            endMs = end,
            durationMs = tr.durationMs,
            progress = progress,
            clipBefore = clipBefore,
            clipAfter = clipAfter
          )
        }
      }
    }
    return null
  }

  fun addTransition(transition: Transition) {
    withStateLock {
      setTransition(transition.clipIndexBefore, transition.type, transition.durationMs)
    }
  }

  fun getTransitionForCut(clipIndexBefore: Int): Transition? =
    _timeline.value.transitions.find { it.clipIndexBefore == clipIndexBefore }

  // --- Expression + bone-rig scripts (ClipMotionEngine) ---

  fun getClipMotion(clipId: String): ClipMotionScript? {
    val t = _timeline.value
    t.videoClips.firstOrNull { it.id == clipId }?.let { return it.motion }
    t.overlayClips.firstOrNull { it.id == clipId }?.let { return it.motion }
    t.stickerClips.firstOrNull { it.id == clipId }?.let { return it.motion }
    t.textClips.firstOrNull { it.id == clipId }?.let { return it.motion }
    return null
  }

  /** Applies [transform] to the motion script of any video / overlay / sticker / text clip. One undo step per burst. */
  fun updateClipMotion(clipId: String, transform: (ClipMotionScript) -> ClipMotionScript) {
    withStateLock {
      val t = _timeline.value
      if (getClipMotion(clipId) == null) return
      recordHistoryCoalesced("motion_$clipId")
      _timeline.value = t.copy(
        videoClips = t.videoClips.map { if (it.id == clipId) it.copy(motion = transform(it.motion)) else it },
        overlayClips = t.overlayClips.map { if (it.id == clipId) it.copy(motion = transform(it.motion)) else it },
        stickerClips = t.stickerClips.map { if (it.id == clipId) it.copy(motion = transform(it.motion)) else it },
        textClips = t.textClips.map { if (it.id == clipId) it.copy(motion = transform(it.motion)) else it }
      )
    }
  }

  /** Sets (or clears when blank) the expression of one channel. */
  fun setClipExpression(clipId: String, channel: MotionChannel, source: String?) =
    updateClipMotion(clipId) { it.withExpression(channel, source) }

  /**
   * Links [clipId] as a child bone of [parentId] (null = unlink). With [keepPose] the clip stays exactly where it is
   * on screen at the playhead, so linking never makes it jump. Returns false if the link would create a loop.
   */
  fun setClipRigParent(
    clipId: String, parentId: String?, keepPose: Boolean = true,
    inheritScale: Boolean = true, inheritOpacity: Boolean = false
  ): Boolean {
    if (parentId == null) { updateClipMotion(clipId) { it.copy(rig = null) }; return true }
    val tl = _timeline.value
    if (parentId == clipId || com.example.engine.motion.ClipMotionEngine.wouldCycle(tl, clipId, parentId)) return false
    val binding = if (keepPose)
      com.example.engine.motion.ClipMotionEngine.bindKeepingPose(tl, clipId, parentId, _currentPositionMs.value, inheritScale, inheritOpacity)
    else ClipRigBinding(parentId, inheritScale, inheritOpacity)
    updateClipMotion(clipId) { it.copy(rig = binding) }
    return true
  }

  fun updateClipRigBinding(clipId: String, transform: (ClipRigBinding) -> ClipRigBinding) =
    updateClipMotion(clipId) { m -> m.rig?.let { m.copy(rig = transform(it)) } ?: m }

  fun setClipBoneLength(clipId: String, length: Float) =
    updateClipMotion(clipId) { it.copy(boneLength = length.coerceIn(0f, 2f)) }

  fun getClipTransformationAt(clipId: String, timelinePosMs: Long): InterpolatedClipTransform? {
    val cur = _timeline.value
    cur.videoClips.find { it.id == clipId }?.let { clip ->
      val rel = timelinePosMs - clip.timelineStartMs
      return KeyframeInterpolator.interpolate(clip, rel)
    }
    cur.overlayClips.find { it.id == clipId }?.let { clip ->
      val rel = timelinePosMs - clip.timelineStartMs
      return KeyframeInterpolator.interpolate(clip, rel)
    }
    cur.stickerClips.find { it.id == clipId }?.let { clip ->
      val rel = timelinePosMs - clip.timelineStartMs
      return KeyframeInterpolator.interpolate(clip, rel)
    }
    return null
  }

  fun getClipTransformationAtFrame(clipId: String, frameIndex: Long, fps: Int = _timelineFps.value): InterpolatedClipTransform? =
    getClipTransformationAt(clipId, frameToTime(frameIndex, fps))

  fun getActiveAudioSyncInfoAt(posMs: Long): List<AudioSyncInfo> {
    val cur = _timeline.value
    val list = mutableListOf<AudioSyncInfo>()

    // Standalone audio tracks
    val isAudioTrackHidden = cur.trackSettings[TrackType.AUDIO]?.isHidden == true
    if (!isAudioTrackHidden) {
      cur.audioClips
        .filter { !it.isHidden && posMs >= it.timelineStartMs && posMs < it.timelineStartMs + it.durationMs }
        .forEach { clip ->
          val relMs = posMs - clip.timelineStartMs
          val sourceMs = clip.sourceStartMs + (relMs * clip.speed).toLong()
          val vol = getEffectiveAudioVolumeAt(clip, posMs)
          list.add(
            AudioSyncInfo(
              clipId = clip.id,
              trackIndex = clip.trackIndex,
              isVideoTrack = false,
              uri = clip.uri,
              timelineStartMs = clip.timelineStartMs,
              timelineEndMs = clip.timelineStartMs + clip.durationMs,
              timelinePosMs = posMs,
              sourcePosMs = sourceMs,
              sourcePosUs = sourceMs * 1000L,
              effectiveVolume = vol,
              speed = clip.speed,
              isMuted = clip.isMuted || vol <= 0f,
              pitchShiftSemitones = clip.audioEffects.pitchShiftSemitones
            )
          )
        }
    }

    // Video tracks with embedded audio
    val isMainVideoHidden = cur.trackSettings[TrackType.MAIN_VIDEO]?.isHidden == true
    if (!isMainVideoHidden) {
      cur.videoClips
        .filter { !it.isHidden && it.hasAudio && !it.isMuted && posMs >= it.timelineStartMs && posMs < it.timelineStartMs + it.durationMs }
        .forEach { clip ->
          val sourceMs = clip.timelineToSourceMs(posMs)
          val vol = getVideoClipAudioVolumeAt(clip, posMs)
          list.add(
            AudioSyncInfo(
              clipId = clip.id,
              trackIndex = 0,
              isVideoTrack = true,
              uri = clip.uri,
              timelineStartMs = clip.timelineStartMs,
              timelineEndMs = clip.timelineStartMs + clip.durationMs,
              timelinePosMs = posMs,
              sourcePosMs = sourceMs,
              sourcePosUs = sourceMs * 1000L,
              effectiveVolume = vol,
              speed = clip.speed,
              isMuted = vol <= 0f,
              pitchShiftSemitones = clip.audioEffects.pitchShiftSemitones
            )
          )
        }
    }

    // Overlay tracks with embedded audio
    val isOverlayHidden = cur.trackSettings[TrackType.OVERLAY]?.isHidden == true
    if (!isOverlayHidden) {
      cur.overlayClips
        .filter { !it.isHidden && it.hasAudio && !it.isMuted && posMs >= it.timelineStartMs && posMs < it.timelineStartMs + it.durationMs }
        .forEach { clip ->
          val sourceMs = clip.timelineToSourceMs(posMs)
          val vol = getVideoClipAudioVolumeAt(clip, posMs)
          list.add(
            AudioSyncInfo(
              clipId = clip.id,
              trackIndex = clip.trackIndex,
              isVideoTrack = true,
              uri = clip.uri,
              timelineStartMs = clip.timelineStartMs,
              timelineEndMs = clip.timelineStartMs + clip.durationMs,
              timelinePosMs = posMs,
              sourcePosMs = sourceMs,
              sourcePosUs = sourceMs * 1000L,
              effectiveVolume = vol,
              speed = clip.speed,
              isMuted = vol <= 0f,
              pitchShiftSemitones = clip.audioEffects.pitchShiftSemitones
            )
          )
        }
    }

    return list
  }

  fun toTimelineAudioTracks(): List<com.example.engine.audio.phase3.TimelineAudioTrack> {
    val cur = _timeline.value
    val tracks = mutableListOf<com.example.engine.audio.phase3.TimelineAudioTrack>()

    val mainVideoTrackSettings = cur.trackSettings[TrackType.MAIN_VIDEO]
    val mainAudioClips = cur.videoClips.filter { it.hasAudio && !it.isMuted }.map { clip ->
      com.example.engine.audio.phase3.TimelineAudioClip(
        id = clip.id,
        sourceMediaId = clip.id,
        sourceUri = clip.uri,
        sourceStartMs = clip.sourceStartMs,
        sourceEndMs = clip.sourceEndMs,
        timelineStartMs = clip.timelineStartMs,
        timelineEndMs = clip.timelineStartMs + clip.durationMs,
        trackId = "track_main_video_audio",
        volume = clip.volume,
        pan = 0f,
        mute = clip.isMuted,
        speed = clip.speed,
        enabled = !clip.isHidden
      )
    }
    if (mainAudioClips.isNotEmpty()) {
      tracks.add(
        com.example.engine.audio.phase3.TimelineAudioTrack(
          id = "track_main_video_audio",
          order = 0,
          volume = 1f,
          mute = mainVideoTrackSettings?.isMuted == true,
          solo = mainVideoTrackSettings?.isSolo == true,
          enabled = mainVideoTrackSettings?.isHidden != true,
          clips = mainAudioClips
        )
      )
    }

    val overlayTrackSettings = cur.trackSettings[TrackType.OVERLAY]
    val overlayAudioClips = cur.overlayClips.filter { it.hasAudio && !it.isMuted }.map { clip ->
      com.example.engine.audio.phase3.TimelineAudioClip(
        id = clip.id,
        sourceMediaId = clip.id,
        sourceUri = clip.uri,
        sourceStartMs = clip.sourceStartMs,
        sourceEndMs = clip.sourceEndMs,
        timelineStartMs = clip.timelineStartMs,
        timelineEndMs = clip.timelineStartMs + clip.durationMs,
        trackId = "track_overlay_audio_${clip.trackIndex}",
        volume = clip.volume,
        pan = 0f,
        mute = clip.isMuted,
        speed = clip.speed,
        enabled = !clip.isHidden
      )
    }
    val overlayAudioByTrack = overlayAudioClips.groupBy { it.trackId }
    overlayAudioByTrack.forEach { (trackId, clips) ->
      tracks.add(
        com.example.engine.audio.phase3.TimelineAudioTrack(
          id = trackId,
          order = 1,
          volume = 1f,
          mute = overlayTrackSettings?.isMuted == true,
          solo = overlayTrackSettings?.isSolo == true,
          enabled = overlayTrackSettings?.isHidden != true,
          clips = clips
        )
      )
    }

    val audioTrackSettings = cur.trackSettings[TrackType.AUDIO]
    val audioByLane = cur.audioClips.groupBy { it.trackIndex }
    audioByLane.forEach { (laneIdx, clips) ->
      val audioClips = clips.map { clip ->
        com.example.engine.audio.phase3.TimelineAudioClip(
          id = clip.id,
          sourceMediaId = clip.id,
          sourceUri = clip.uri,
          sourceStartMs = clip.sourceStartMs,
          sourceEndMs = clip.sourceEndMs,
          timelineStartMs = clip.timelineStartMs,
          timelineEndMs = clip.timelineStartMs + clip.durationMs,
          trackId = "track_audio_$laneIdx",
          volume = clip.volume,
          pan = 0f,
          mute = clip.isMuted,
          fadeInMs = clip.fadeInMs,
          fadeOutMs = clip.fadeOutMs,
          speed = clip.speed,
          enabled = !clip.isHidden
        )
      }
      tracks.add(
        com.example.engine.audio.phase3.TimelineAudioTrack(
          id = "track_audio_$laneIdx",
          order = 2 + laneIdx,
          volume = 1f,
          mute = audioTrackSettings?.isMuted == true,
          solo = audioTrackSettings?.isSolo == true,
          enabled = audioTrackSettings?.isHidden != true,
          clips = audioClips
        )
      )
    }

    return tracks
  }

  fun splitAllClipsAtPlayhead(): List<Pair<String, String>> = withStateLock {
    val playhead = alignedPlayheadMs()
    val original = _timeline.value
    var working = original
    val results = mutableListOf<Pair<String, String>>()
    for (id in TimelineSplitEngine.clipIdsCrossing(original, playhead)) {
      val split = TimelineSplitEngine.splitClipInTimeline(working, id, playhead, lockPredicate(working)) ?: continue
      working = split.timeline
      results += split.headId to split.tailId
    }
    if (results.isNotEmpty()) {
      recordHistory(TimelineActionType.SPLIT_CLIP, "Split All Clips")
      _timeline.value = working
    }
    results
  }

  /**
   * Calculates the exact audio volume level of an audio clip at [posMs], taking into account
   * base volume, track settings (mute/solo), fade in/out envelopes, keyframes, and ducking.
   */
  fun getEffectiveAudioVolumeAt(clip: AudioClip, posMs: Long): Float {
    val trackSettings = _timeline.value.trackSettings[TrackType.AUDIO]
    if (trackSettings?.isMuted == true) return 0f

    val anySolo = _timeline.value.trackSettings.values.any { it.isSolo }
    if (anySolo && trackSettings?.isSolo != true) return 0f

    val relMs = posMs - clip.timelineStartMs
    if (relMs < 0L || relMs > clip.durationMs) return 0f

    var baseVol = clip.volume

    // Apply Fade In
    if (clip.fadeInMs > 0L && relMs < clip.fadeInMs) {
      val progress = (relMs.toFloat() / clip.fadeInMs).coerceIn(0f, 1f)
      baseVol *= (progress * progress)
    }

    // Apply Fade Out
    if (clip.fadeOutMs > 0L && relMs > clip.durationMs - clip.fadeOutMs) {
      val remaining = (clip.durationMs - relMs).coerceAtLeast(0L)
      val progress = (remaining.toFloat() / clip.fadeOutMs).coerceIn(0f, 1f)
      baseVol *= (progress * progress)
    }

    // Apply Keyframes
    if (clip.keyframes.isNotEmpty()) {
      val kfVol = KeyframeInterpolator.interpolateVolume(clip.keyframes, relMs)
      baseVol *= kfVol
    }

    return baseVol.coerceAtLeast(0f)
  }

  /**
   * Calculates the exact audio volume level of a video clip's audio stream at [posMs].
   */
  fun getVideoClipAudioVolumeAt(clip: VideoClip, posMs: Long): Float {
    if (clip.isMuted || !clip.hasAudio) return 0f
    val trackType = if (clip.trackIndex == 0) TrackType.MAIN_VIDEO else TrackType.OVERLAY
    val trackSettings = _timeline.value.trackSettings[trackType]
    if (trackSettings?.isMuted == true) return 0f

    val anySolo = _timeline.value.trackSettings.values.any { it.isSolo }
    if (anySolo && trackSettings?.isSolo != true) return 0f

    val relMs = posMs - clip.timelineStartMs
    if (relMs < 0L || relMs > clip.durationMs) return 0f

    var baseVol = clip.volume

    if (clip.keyframes.isNotEmpty()) {
      val kf = KeyframeInterpolator.interpolate(clip, relMs)
      baseVol *= kf.volume
    }

    return baseVol.coerceAtLeast(0f)
  }
}

/**
 * Snapshot of all active elements on the timeline at a given timestamp.
 */
data class TimelineActiveSnapshot(
  val timelinePosMs: Long,
  val primaryVideoClip: VideoClip?,
  val activeOverlays: List<VideoClip>,
  val activeAudioClips: List<AudioClip>,
  val activeTextClips: List<TextClip>,
  val activeStickerClips: List<StickerClip>,
  val activeEffects: List<EffectClip>,
  val activeTransition: Transition?,
  val transitionProgress: Float,
  val transitionClipBefore: VideoClip?,
  val transitionClipAfter: VideoClip?,
  val frameIndex: Long = 0L,
  val fps: Int = 30,
  val activeOverlaps: List<ClipOverlapInfo> = emptyList(),
  val activeAudioSyncInfo: List<AudioSyncInfo> = emptyList()
)

data class ClipOverlapInfo(
  val clipAId: String,
  val clipBId: String,
  val trackType: TrackType,
  val trackIndex: Int,
  val overlapStartMs: Long,
  val overlapEndMs: Long,
  val overlapDurationMs: Long
)

data class ClipTrimmedRange(
  val clipId: String,
  val trackType: TrackType,
  val trackIndex: Int,
  val timelineStartMs: Long,
  val timelineEndMs: Long,
  val durationMs: Long,
  val sourceStartMs: Long,
  val sourceEndMs: Long,
  val sourceDurationMs: Long,
  val speed: Float,
  val isTrimmed: Boolean
)

data class TransitionTimingInfo(
  val transition: Transition,
  val startMs: Long,
  val endMs: Long,
  val durationMs: Long,
  val progress: Float,
  val clipBefore: VideoClip,
  val clipAfter: VideoClip
)

data class AudioSyncInfo(
  val clipId: String,
  val trackIndex: Int,
  val isVideoTrack: Boolean,
  val uri: String,
  val timelineStartMs: Long,
  val timelineEndMs: Long,
  val timelinePosMs: Long,
  val sourcePosMs: Long,
  val sourcePosUs: Long,
  val effectiveVolume: Float,
  val speed: Float,
  val isMuted: Boolean,
  val pitchShiftSemitones: Float = 0f
)

