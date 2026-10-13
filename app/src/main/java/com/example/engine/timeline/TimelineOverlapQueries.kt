package com.example.engine.timeline

import com.example.domain.model.Timeline
import com.example.domain.model.TrackType
import com.example.engine.ClipOverlapInfo
import com.example.engine.ClipTrimmedRange

/** Read-only queries over clip ranges: per-lane overlaps and trim state. */
object TimelineOverlapQueries {

  private data class Span(val id: String, val startMs: Long, val endMs: Long)

  /**
   * Clips on one lane. Main video, sticker and effect tracks are single-lane, so [trackIndex]
   * is ignored for them.
   */
  private fun laneSpans(timeline: Timeline, trackType: TrackType, trackIndex: Int): List<Span> = when (trackType) {
    TrackType.MAIN_VIDEO -> timeline.videoClips.map { Span(it.id, it.timelineStartMs, it.timelineStartMs + it.durationMs) }
    TrackType.OVERLAY, TrackType.ADJUSTMENT, TrackType.ELEMENT -> timeline.overlayClips.filter { it.trackIndex == trackIndex }.map { Span(it.id, it.timelineStartMs, it.timelineStartMs + it.durationMs) }
    TrackType.AUDIO, TrackType.MUSIC, TrackType.SFX -> timeline.audioClips.filter { it.trackIndex == trackIndex }.map { Span(it.id, it.timelineStartMs, it.timelineStartMs + it.durationMs) }
    TrackType.TEXT, TrackType.CAPTION -> timeline.textClips.filter { it.trackIndex == trackIndex }.map { Span(it.id, it.timelineStartMs, it.timelineStartMs + it.durationMs) }
    TrackType.STICKER -> timeline.stickerClips.map { Span(it.id, it.timelineStartMs, it.timelineStartMs + it.durationMs) }
    TrackType.EFFECT -> timeline.effectClips.map { Span(it.id, it.timelineStartMs, it.timelineStartMs + it.durationMs) }
    TrackType.SHAPE -> timeline.shapeClips.filter { it.trackIndex == trackIndex }.map { Span(it.id, it.timelineStartMs, it.timelineStartMs + it.durationMs) }
  }

  /** Every overlapping pair on a lane, ordered by the earlier clip's start. */
  fun trackOverlaps(timeline: Timeline, trackType: TrackType, trackIndex: Int = 0): List<ClipOverlapInfo> {
    val sorted = laneSpans(timeline, trackType, trackIndex).sortedBy { it.startMs }
    val overlaps = mutableListOf<ClipOverlapInfo>()
    for (i in 0 until sorted.size - 1) {
      val a = sorted[i]
      for (j in i + 1 until sorted.size) {
        val b = sorted[j]
        if (b.startMs >= a.endMs) break
        val start = maxOf(a.startMs, b.startMs)
        val end = minOf(a.endMs, b.endMs)
        if (end > start) {
          overlaps.add(ClipOverlapInfo(a.id, b.id, trackType, trackIndex, start, end, end - start))
        }
      }
    }
    return overlaps
  }

  /** True when `[startMs, startMs + durationMs)` intersects any clip on the lane except [ignoreClipId]. */
  fun hasOverlap(
    timeline: Timeline,
    trackType: TrackType,
    trackIndex: Int,
    startMs: Long,
    durationMs: Long,
    ignoreClipId: String? = null
  ): Boolean {
    val endMs = startMs + durationMs
    return laneSpans(timeline, trackType, trackIndex).any { it.id != ignoreClipId && startMs < it.endMs && endMs > it.startMs }
  }

  fun clipStartEnd(timeline: Timeline, clipId: String): Pair<Long, Long>? =
    timeline.clipView(clipId)?.let { it.startMs to it.endMs }

  /** Trim state of a media clip (main video, overlay or audio); null for generated layers. */
  fun trimmedRange(timeline: Timeline, clipId: String): ClipTrimmedRange? {
    timeline.videoClips.find { it.id == clipId }?.let {
      val isTrimmed = it.sourceStartMs > 0L || (it.sourceTotalDurationMs > 0L && it.sourceEndMs < it.sourceTotalDurationMs)
      return ClipTrimmedRange(
        it.id, TrackType.MAIN_VIDEO, 0, it.timelineStartMs, it.timelineStartMs + it.durationMs, it.durationMs,
        it.sourceStartMs, it.sourceEndMs, it.sourceEndMs - it.sourceStartMs, it.speed, isTrimmed
      )
    }
    timeline.overlayClips.find { it.id == clipId }?.let {
      val isTrimmed = it.sourceStartMs > 0L || (it.sourceTotalDurationMs > 0L && it.sourceEndMs < it.sourceTotalDurationMs)
      return ClipTrimmedRange(
        it.id, TrackType.OVERLAY, it.trackIndex, it.timelineStartMs, it.timelineStartMs + it.durationMs, it.durationMs,
        it.sourceStartMs, it.sourceEndMs, it.sourceEndMs - it.sourceStartMs, it.speed, isTrimmed
      )
    }
    timeline.audioClips.find { it.id == clipId }?.let {
      val isTrimmed = it.sourceStartMs > 0L || it.sourceEndMs < it.durationMs
      return ClipTrimmedRange(
        it.id, TrackType.AUDIO, it.trackIndex, it.timelineStartMs, it.timelineStartMs + it.durationMs, it.durationMs,
        it.sourceStartMs, it.sourceEndMs, it.sourceEndMs - it.sourceStartMs, it.speed, isTrimmed
      )
    }
    return null
  }
}
