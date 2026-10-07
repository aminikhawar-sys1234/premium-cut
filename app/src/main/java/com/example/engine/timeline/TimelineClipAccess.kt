package com.example.engine.timeline

import com.example.domain.model.ClipKeyframe
import com.example.domain.model.Timeline
import com.example.domain.model.TrackType

/**
 * Type-erased, read-only view of one clip's timing, so edit operations (trim, move, lock checks)
 * are written once instead of once per clip type.
 */
data class ClipView(
  val id: String,
  val trackType: TrackType,
  val lane: Int,
  val locked: Boolean,
  val startMs: Long,
  val durationMs: Long,
  /** True for clips backed by a media file (video / overlay / audio); false for generated layers. */
  val hasSource: Boolean,
  val sourceStartMs: Long,
  val sourceEndMs: Long,
  val speed: Float,
  val reversed: Boolean,
  /** Real length of the source media, 0 when unknown / unbounded (still images). */
  val mediaDurationMs: Long
) {
  val endMs: Long get() = startMs + durationMs
}

fun Timeline.clipView(id: String): ClipView? {
  videoClips.firstOrNull { it.id == id }?.let {
    return ClipView(
      it.id, TrackType.MAIN_VIDEO, it.trackIndex, it.isLocked, it.timelineStartMs, it.durationMs, true,
      it.sourceStartMs, it.sourceEndMs, it.speed, it.isReversed, if (it.isVideo) it.sourceTotalDurationMs else 0L
    )
  }
  overlayClips.firstOrNull { it.id == id }?.let {
    return ClipView(
      it.id, TrackType.OVERLAY, it.trackIndex, it.isLocked, it.timelineStartMs, it.durationMs, true,
      it.sourceStartMs, it.sourceEndMs, it.speed, it.isReversed, if (it.isVideo) it.sourceTotalDurationMs else 0L
    )
  }
  audioClips.firstOrNull { it.id == id }?.let {
    return ClipView(
      it.id, TrackType.AUDIO, it.trackIndex, it.isLocked, it.timelineStartMs, it.durationMs, true,
      it.sourceStartMs, it.sourceEndMs, it.speed, it.isReversed, it.sourceTotalDurationMs
    )
  }
  textClips.firstOrNull { it.id == id }?.let {
    return ClipView(it.id, TrackType.TEXT, it.trackIndex, it.isLocked, it.timelineStartMs, it.durationMs, false, 0L, 0L, 1f, false, 0L)
  }
  stickerClips.firstOrNull { it.id == id }?.let {
    return ClipView(it.id, TrackType.STICKER, it.trackIndex, it.isLocked, it.timelineStartMs, it.durationMs, false, 0L, 0L, 1f, false, 0L)
  }
  effectClips.firstOrNull { it.id == id }?.let {
    return ClipView(it.id, TrackType.EFFECT, it.trackIndex, it.isLocked, it.timelineStartMs, it.durationMs, false, 0L, 0L, 1f, false, 0L)
  }
  shapeClips.firstOrNull { it.id == id }?.let {
    return ClipView(it.id, TrackType.SHAPE, it.trackIndex, it.isLocked, it.timelineStartMs, it.durationMs, false, 0L, 0L, 1f, false, 0L)
  }
  return null
}

private fun List<ClipKeyframe>.shiftedBy(deltaMs: Long): List<ClipKeyframe> {
  if (isEmpty() || deltaMs == 0L) return this
  val shifted = map { it.copy(timeMs = it.timeMs - deltaMs) }
  val (before, onOrAfter) = shifted.partition { it.timeMs < 0L }
  // Keep the value that was in effect at the new clip start as a boundary keyframe at 0.
  val boundary = before.maxByOrNull { it.timeMs }?.takeIf { onOrAfter.none { k -> k.timeMs == 0L } }?.copy(timeMs = 0L)
  return listOfNotNull(boundary) + onOrAfter
}

/**
 * Applies a new timeline range (and, for media clips, source in/out points) to clip [id].
 * [leftTrimDeltaMs] is how far the clip's left edge moved right (>0) or left (<0); keyframes are
 * re-based so they stay on the same media frames.
 */
fun Timeline.withClipRange(id: String, range: ClipTrimMath.Range, leftTrimDeltaMs: Long = 0L): Timeline {
  if (videoClips.any { it.id == id }) {
    return copy(videoClips = videoClips.map {
      if (it.id != id) it else it.copy(
        timelineStartMs = range.timelineStartMs, durationMs = range.durationMs,
        sourceStartMs = range.sourceStartMs, sourceEndMs = range.sourceEndMs,
        keyframes = it.keyframes.shiftedBy(leftTrimDeltaMs)
      )
    })
  }
  if (overlayClips.any { it.id == id }) {
    return copy(overlayClips = overlayClips.map {
      if (it.id != id) it else it.copy(
        timelineStartMs = range.timelineStartMs, durationMs = range.durationMs,
        sourceStartMs = range.sourceStartMs, sourceEndMs = range.sourceEndMs,
        keyframes = it.keyframes.shiftedBy(leftTrimDeltaMs)
      )
    })
  }
  if (audioClips.any { it.id == id }) {
    return copy(audioClips = audioClips.map {
      if (it.id != id) it else it.copy(
        timelineStartMs = range.timelineStartMs, durationMs = range.durationMs,
        sourceStartMs = range.sourceStartMs, sourceEndMs = range.sourceEndMs,
        keyframes = it.keyframes.shiftedBy(leftTrimDeltaMs)
      )
    })
  }
  if (textClips.any { it.id == id }) {
    return copy(textClips = textClips.map {
      if (it.id != id) it else it.copy(
        timelineStartMs = range.timelineStartMs, durationMs = range.durationMs,
        keyframes = it.keyframes.shiftedBy(leftTrimDeltaMs)
      )
    })
  }
  if (stickerClips.any { it.id == id }) {
    return copy(stickerClips = stickerClips.map {
      if (it.id != id) it else it.copy(
        timelineStartMs = range.timelineStartMs, durationMs = range.durationMs,
        keyframes = it.keyframes.shiftedBy(leftTrimDeltaMs)
      )
    })
  }
  if (effectClips.any { it.id == id }) {
    return copy(effectClips = effectClips.map {
      if (it.id != id) it else it.copy(
        timelineStartMs = range.timelineStartMs, durationMs = range.durationMs,
        keyframes = it.keyframes.shiftedBy(leftTrimDeltaMs)
      )
    })
  }
  if (shapeClips.any { it.id == id }) {
    return copy(shapeClips = shapeClips.map {
      if (it.id != id) it else it.copy(
        timelineStartMs = range.timelineStartMs, durationMs = range.durationMs,
        keyframes = it.keyframes.shiftedBy(leftTrimDeltaMs)
      )
    })
  }
  return this
}

/** Moves clip [id] so it starts at [startMs], keeping its lane list ordered by start time. */
fun Timeline.withClipStart(id: String, startMs: Long): Timeline {
  if (videoClips.any { it.id == id }) return copy(videoClips = videoClips.map { if (it.id == id) it.copy(timelineStartMs = startMs) else it }.sortedBy { it.timelineStartMs })
  if (overlayClips.any { it.id == id }) return copy(overlayClips = overlayClips.map { if (it.id == id) it.copy(timelineStartMs = startMs) else it }.sortedBy { it.timelineStartMs })
  if (audioClips.any { it.id == id }) return copy(audioClips = audioClips.map { if (it.id == id) it.copy(timelineStartMs = startMs) else it }.sortedBy { it.timelineStartMs })
  if (textClips.any { it.id == id }) return copy(textClips = textClips.map { if (it.id == id) it.copy(timelineStartMs = startMs) else it }.sortedBy { it.timelineStartMs })
  if (stickerClips.any { it.id == id }) return copy(stickerClips = stickerClips.map { if (it.id == id) it.copy(timelineStartMs = startMs) else it }.sortedBy { it.timelineStartMs })
  if (effectClips.any { it.id == id }) return copy(effectClips = effectClips.map { if (it.id == id) it.copy(timelineStartMs = startMs) else it }.sortedBy { it.timelineStartMs })
  if (shapeClips.any { it.id == id }) return copy(shapeClips = shapeClips.map { if (it.id == id) it.copy(timelineStartMs = startMs) else it }.sortedBy { it.timelineStartMs })
  return this
}

/** Shifts every clip in [ids] by [deltaMs] (never before 0); lists that did not change keep their identity. */
fun Timeline.withClipsShifted(ids: Set<String>, deltaMs: Long): Timeline {
  if (ids.isEmpty() || deltaMs == 0L) return this
  fun shift(start: Long) = (start + deltaMs).coerceAtLeast(0L)
  return copy(
    videoClips = if (videoClips.any { it.id in ids }) videoClips.map { if (it.id in ids) it.copy(timelineStartMs = shift(it.timelineStartMs)) else it }.sortedBy { it.timelineStartMs } else videoClips,
    overlayClips = if (overlayClips.any { it.id in ids }) overlayClips.map { if (it.id in ids) it.copy(timelineStartMs = shift(it.timelineStartMs)) else it }.sortedBy { it.timelineStartMs } else overlayClips,
    audioClips = if (audioClips.any { it.id in ids }) audioClips.map { if (it.id in ids) it.copy(timelineStartMs = shift(it.timelineStartMs)) else it }.sortedBy { it.timelineStartMs } else audioClips,
    textClips = if (textClips.any { it.id in ids }) textClips.map { if (it.id in ids) it.copy(timelineStartMs = shift(it.timelineStartMs)) else it }.sortedBy { it.timelineStartMs } else textClips,
    stickerClips = if (stickerClips.any { it.id in ids }) stickerClips.map { if (it.id in ids) it.copy(timelineStartMs = shift(it.timelineStartMs)) else it }.sortedBy { it.timelineStartMs } else stickerClips,
    effectClips = if (effectClips.any { it.id in ids }) effectClips.map { if (it.id in ids) it.copy(timelineStartMs = shift(it.timelineStartMs)) else it }.sortedBy { it.timelineStartMs } else effectClips,
    shapeClips = if (shapeClips.any { it.id in ids }) shapeClips.map { if (it.id in ids) it.copy(timelineStartMs = shift(it.timelineStartMs)) else it }.sortedBy { it.timelineStartMs } else shapeClips
  )
}
