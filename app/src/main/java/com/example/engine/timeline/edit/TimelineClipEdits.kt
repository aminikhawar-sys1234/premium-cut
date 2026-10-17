package com.example.engine.timeline.edit

import com.example.domain.model.Timeline
import com.example.engine.timeline.CompoundClipOps
import com.example.domain.model.TrackType
import com.example.engine.timeline.ClipTrimMath
import com.example.engine.timeline.ClipView
import com.example.engine.timeline.TimelineRangeOps
import com.example.engine.timeline.TimelineTrackManager
import com.example.engine.timeline.clipView
import com.example.engine.timeline.withClipRange
import com.example.engine.timeline.withClipStart
import com.example.engine.timeline.withClipsShifted

/**
 * Pure clip/track edit commands. Each function takes a [Timeline] and returns a new one
 * (or null when the edit is a no-op / rejected). TimelineEngine wraps these with locking,
 * snap, undo and selection.
 */
object TimelineClipEdits {

  fun isLaneLocked(timeline: Timeline, view: ClipView): Boolean =
    view.locked || TimelineTrackManager.isTrackLocked(timeline, view.trackType, view.lane)

  fun isTrackLocked(timeline: Timeline, trackType: TrackType): Boolean =
    timeline.trackSettings[trackType]?.isLocked == true

  fun trimLeft(timeline: Timeline, clipId: String, requestedStartMs: Long): Timeline? {
    val view = timeline.clipView(clipId) ?: return null
    if (isLaneLocked(timeline, view)) return null
    val isFirstMainClip = view.trackType == TrackType.MAIN_VIDEO &&
      timeline.videoClips.minByOrNull { it.timelineStartMs }?.id == clipId
    val range = ClipTrimMath.trimStart(
      view.startMs, view.durationMs, view.sourceStartMs, view.sourceEndMs, view.speed, view.reversed,
      view.mediaDurationMs, requestedStartMs, pinToZero = isFirstMainClip, hasSource = view.hasSource
    )
    if (range.timelineStartMs == view.startMs && range.durationMs == view.durationMs) return null
    return timeline.withClipRange(clipId, range, leftTrimDeltaMs = view.durationMs - range.durationMs)
  }

  fun trimRight(timeline: Timeline, clipId: String, requestedDurationMs: Long): Timeline? {
    val view = timeline.clipView(clipId) ?: return null
    if (isLaneLocked(timeline, view)) return null
    val range = ClipTrimMath.trimEnd(
      view.startMs, view.durationMs, view.sourceStartMs, view.sourceEndMs, view.speed, view.reversed,
      view.mediaDurationMs, requestedDurationMs, hasSource = view.hasSource
    )
    if (range.durationMs == view.durationMs) return null
    return timeline.withClipRange(clipId, range)
  }

  fun moveClip(timeline: Timeline, clipId: String, newStartMs: Long): Timeline? {
    val view = timeline.clipView(clipId) ?: return null
    if (isLaneLocked(timeline, view)) return null
    val start = newStartMs.coerceAtLeast(0L)
    if (start == view.startMs) return null
    return timeline.withClipStart(clipId, start)
  }

  fun shiftClips(timeline: Timeline, clipIds: Set<String>, deltaMs: Long): Timeline? {
    if (clipIds.isEmpty() || deltaMs == 0L) return null
    val movable = clipIds.mapNotNull { timeline.clipView(it) }.filter { !isLaneLocked(timeline, it) }
    if (movable.isEmpty()) return null
    val movableIds = movable.mapTo(HashSet()) { it.id }
    val minStart = movable.minOf { it.startMs }
    val effectiveDelta = if (deltaMs < 0) maxOf(deltaMs, -minStart) else deltaMs
    if (effectiveDelta == 0L) return null
    return timeline.withClipsShifted(movableIds, effectiveDelta)
  }

  fun slipClip(timeline: Timeline, clipId: String, deltaMs: Long): Timeline? {
    if (deltaMs == 0L) return null
    val view = timeline.clipView(clipId) ?: return null
    if (isLaneLocked(timeline, view) || !view.hasSource) return null
    val sourceDelta = (deltaMs * view.speed).toLong()
    val newSourceStart = (view.sourceStartMs + sourceDelta).coerceAtLeast(0L)
    val sourceSpan = (view.durationMs * view.speed).toLong()
    val newSourceEnd = newSourceStart + sourceSpan
    return when (view.trackType) {
      TrackType.MAIN_VIDEO -> timeline.copy(
        videoClips = timeline.videoClips.map {
          if (it.id == clipId) it.copy(sourceStartMs = newSourceStart, sourceEndMs = newSourceEnd) else it
        }
      )
      TrackType.OVERLAY -> timeline.copy(
        overlayClips = timeline.overlayClips.map {
          if (it.id == clipId) it.copy(sourceStartMs = newSourceStart, sourceEndMs = newSourceEnd) else it
        }
      )
      TrackType.AUDIO -> timeline.copy(
        audioClips = timeline.audioClips.map {
          if (it.id == clipId) it.copy(sourceStartMs = newSourceStart, sourceEndMs = newSourceEnd) else it
        }
      )
      else -> null
    }
  }

  fun rippleDownstream(timeline: Timeline, fromTimeMs: Long, deltaMs: Long): Timeline {
    if (deltaMs == 0L) return timeline
    fun shiftStart(start: Long) = if (start >= fromTimeMs) (start + deltaMs).coerceAtLeast(0L) else start
    return timeline.copy(
      videoClips = if (!isTrackLocked(timeline, TrackType.MAIN_VIDEO)) {
        timeline.videoClips.map { if (it.timelineStartMs >= fromTimeMs) it.copy(timelineStartMs = shiftStart(it.timelineStartMs)) else it }
          .sortedBy { it.timelineStartMs }
      } else timeline.videoClips,
      overlayClips = if (!isTrackLocked(timeline, TrackType.OVERLAY)) {
        timeline.overlayClips.map { if (it.timelineStartMs >= fromTimeMs) it.copy(timelineStartMs = shiftStart(it.timelineStartMs)) else it }
          .sortedBy { it.timelineStartMs }
      } else timeline.overlayClips,
      audioClips = if (!isTrackLocked(timeline, TrackType.AUDIO)) {
        timeline.audioClips.map { if (it.timelineStartMs >= fromTimeMs) it.copy(timelineStartMs = shiftStart(it.timelineStartMs)) else it }
          .sortedBy { it.timelineStartMs }
      } else timeline.audioClips,
      textClips = if (!isTrackLocked(timeline, TrackType.TEXT)) {
        timeline.textClips.map { if (it.timelineStartMs >= fromTimeMs) it.copy(timelineStartMs = shiftStart(it.timelineStartMs)) else it }
          .sortedBy { it.timelineStartMs }
      } else timeline.textClips,
      stickerClips = if (!isTrackLocked(timeline, TrackType.STICKER)) {
        timeline.stickerClips.map { if (it.timelineStartMs >= fromTimeMs) it.copy(timelineStartMs = shiftStart(it.timelineStartMs)) else it }
          .sortedBy { it.timelineStartMs }
      } else timeline.stickerClips,
      effectClips = if (!isTrackLocked(timeline, TrackType.EFFECT)) {
        timeline.effectClips.map { if (it.timelineStartMs >= fromTimeMs) it.copy(timelineStartMs = shiftStart(it.timelineStartMs)) else it }
          .sortedBy { it.timelineStartMs }
      } else timeline.effectClips
    )
  }

  /**
   * Moves every unlocked non-main-video clip by [deltaMs], clamping so nothing goes before 0.
   * Main video stays put (zero-point lock).
   */
  fun moveUnlockedTracks(timeline: Timeline, deltaMs: Long): Pair<Timeline, Long>? {
    if (deltaMs == 0L) return null
    val starts = mutableListOf<Long>()
    if (!isTrackLocked(timeline, TrackType.MAIN_VIDEO)) timeline.videoClips.forEach { starts.add(it.timelineStartMs) }
    if (!isTrackLocked(timeline, TrackType.OVERLAY)) timeline.overlayClips.forEach { starts.add(it.timelineStartMs) }
    if (!isTrackLocked(timeline, TrackType.TEXT)) timeline.textClips.forEach { starts.add(it.timelineStartMs) }
    if (!isTrackLocked(timeline, TrackType.AUDIO)) timeline.audioClips.forEach { starts.add(it.timelineStartMs) }
    if (!isTrackLocked(timeline, TrackType.EFFECT)) timeline.effectClips.forEach { starts.add(it.timelineStartMs) }
    if (!isTrackLocked(timeline, TrackType.STICKER)) timeline.stickerClips.forEach { starts.add(it.timelineStartMs) }
    if (starts.isEmpty()) return null
    val minStart = starts.minOrNull() ?: 0L
    val effectiveDelta = if (deltaMs < 0) maxOf(deltaMs, -minStart) else deltaMs
    if (effectiveDelta == 0L) return null
    fun shift(start: Long) = (start + effectiveDelta).coerceAtLeast(0L)
    val next = timeline.copy(
      overlayClips = if (!isTrackLocked(timeline, TrackType.OVERLAY)) {
        timeline.overlayClips.map { it.copy(timelineStartMs = shift(it.timelineStartMs)) }.sortedBy { it.timelineStartMs }
      } else timeline.overlayClips,
      textClips = if (!isTrackLocked(timeline, TrackType.TEXT)) {
        timeline.textClips.map { it.copy(timelineStartMs = shift(it.timelineStartMs)) }.sortedBy { it.timelineStartMs }
      } else timeline.textClips,
      audioClips = if (!isTrackLocked(timeline, TrackType.AUDIO)) {
        timeline.audioClips.map { it.copy(timelineStartMs = shift(it.timelineStartMs)) }.sortedBy { it.timelineStartMs }
      } else timeline.audioClips,
      effectClips = if (!isTrackLocked(timeline, TrackType.EFFECT)) {
        timeline.effectClips.map { it.copy(timelineStartMs = shift(it.timelineStartMs)) }.sortedBy { it.timelineStartMs }
      } else timeline.effectClips,
      stickerClips = if (!isTrackLocked(timeline, TrackType.STICKER)) {
        timeline.stickerClips.map { it.copy(timelineStartMs = shift(it.timelineStartMs)) }.sortedBy { it.timelineStartMs }
      } else timeline.stickerClips
    )
    return next to (minStart + effectiveDelta).coerceAtLeast(0L)
  }

  fun normalDelete(timeline: Timeline, clipIds: Set<String>): Timeline? {
    if (clipIds.isEmpty()) return null
    val mainLocked = isTrackLocked(timeline, TrackType.MAIN_VIDEO)
    val newVideo = if (!mainLocked) timeline.videoClips.filterNot { it.id in clipIds } else timeline.videoClips
    val newOverlay = if (!isTrackLocked(timeline, TrackType.OVERLAY)) timeline.overlayClips.filterNot { it.id in clipIds } else timeline.overlayClips
    val newAudio = if (!isTrackLocked(timeline, TrackType.AUDIO)) timeline.audioClips.filterNot { it.id in clipIds } else timeline.audioClips
    val newText = if (!isTrackLocked(timeline, TrackType.TEXT)) timeline.textClips.filterNot { it.id in clipIds } else timeline.textClips
    val newSticker = if (!isTrackLocked(timeline, TrackType.STICKER)) timeline.stickerClips.filterNot { it.id in clipIds } else timeline.stickerClips
    val newEffect = if (!isTrackLocked(timeline, TrackType.EFFECT)) timeline.effectClips.filterNot { it.id in clipIds } else timeline.effectClips
    // Transitions are index-based: deleting main clips shifts every later cut, so the survivors
    // must be re-indexed (and transitions touching a removed clip dropped), not just filtered.
    val removedMain = if (!mainLocked) {
      timeline.videoClips.mapIndexedNotNull { idx, clip -> if (clip.id in clipIds) idx else null }.toSet()
    } else emptySet()
    val validTransitions = TimelineRangeOps.reindexTransitions(timeline.transitions, removedMain)
    return CompoundClipOps.droppingOrphanCompounds(
      timeline.copy(
        videoClips = newVideo,
        overlayClips = newOverlay,
        audioClips = newAudio,
        textClips = newText,
        stickerClips = newSticker,
        effectClips = newEffect,
        transitions = validTransitions
      )
    )
  }

  fun rippleDelete(timeline: Timeline, clipIds: Set<String>): Timeline? {
    if (clipIds.isEmpty()) return null
    fun <T> rippleList(
      items: List<T>,
      locked: Boolean,
      idOf: (T) -> String,
      startOf: (T) -> Long,
      durationOf: (T) -> Long,
      shift: (T, Long) -> T,
      sortKey: (T) -> Long
    ): List<T> {
      if (locked) return items
      val toDelete = items.filter { idOf(it) in clipIds }.sortedBy(sortKey)
      if (toDelete.isEmpty()) return items
      val remaining = items.filterNot { idOf(it) in clipIds }.toMutableList()
      for (del in toDelete) {
        remaining.indices.forEach { i ->
          if (startOf(remaining[i]) >= startOf(del)) {
            remaining[i] = shift(remaining[i], (startOf(remaining[i]) - durationOf(del)).coerceAtLeast(0L))
          }
        }
      }
      return remaining.sortedBy(sortKey)
    }

    val newVideo = rippleList(
      timeline.videoClips, isTrackLocked(timeline, TrackType.MAIN_VIDEO),
      { it.id }, { it.timelineStartMs }, { it.durationMs },
      { c, start -> c.copy(timelineStartMs = start) }, { it.timelineStartMs }
    )
    val newOverlay = rippleList(
      timeline.overlayClips, isTrackLocked(timeline, TrackType.OVERLAY),
      { it.id }, { it.timelineStartMs }, { it.durationMs },
      { c, start -> c.copy(timelineStartMs = start) }, { it.timelineStartMs }
    )
    val newAudio = rippleList(
      timeline.audioClips, isTrackLocked(timeline, TrackType.AUDIO),
      { it.id }, { it.timelineStartMs }, { it.durationMs },
      { c, start -> c.copy(timelineStartMs = start) }, { it.timelineStartMs }
    )
    val newText = rippleList(
      timeline.textClips, isTrackLocked(timeline, TrackType.TEXT),
      { it.id }, { it.timelineStartMs }, { it.durationMs },
      { c, start -> c.copy(timelineStartMs = start) }, { it.timelineStartMs }
    )
    val newSticker = rippleList(
      timeline.stickerClips, isTrackLocked(timeline, TrackType.STICKER),
      { it.id }, { it.timelineStartMs }, { it.durationMs },
      { c, start -> c.copy(timelineStartMs = start) }, { it.timelineStartMs }
    )
    val newEffect = rippleList(
      timeline.effectClips, isTrackLocked(timeline, TrackType.EFFECT),
      { it.id }, { it.timelineStartMs }, { it.durationMs },
      { c, start -> c.copy(timelineStartMs = start) }, { it.timelineStartMs }
    )
    // Transitions are index-based: ripple-deleting main clips shifts every later cut, so the
    // survivors must be re-indexed (and transitions touching a removed clip dropped).
    val removedMain = if (!isTrackLocked(timeline, TrackType.MAIN_VIDEO)) {
      timeline.videoClips.mapIndexedNotNull { idx, clip -> if (clip.id in clipIds) idx else null }.toSet()
    } else emptySet()
    val validTransitions = TimelineRangeOps.reindexTransitions(timeline.transitions, removedMain)
    return CompoundClipOps.droppingOrphanCompounds(
      timeline.copy(
        videoClips = newVideo,
        overlayClips = newOverlay,
        audioClips = newAudio,
        textClips = newText,
        stickerClips = newSticker,
        effectClips = newEffect,
        transitions = validTransitions
      )
    )
  }
}
