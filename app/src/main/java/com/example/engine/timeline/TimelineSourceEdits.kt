package com.example.engine.timeline

import com.example.domain.model.AudioClip
import com.example.domain.model.Timeline
import com.example.domain.model.VideoClip

/**
 * Edits that change which part of the source media a clip shows (slip, slide, in/out points).
 * Pure functions: they return the edited [Timeline], or null when the edit does not apply.
 * Lock checks and undo history stay with the caller.
 */
object TimelineSourceEdits {

  const val MIN_SOURCE_SPAN_MS = 100L

  /** Shortest a neighbour may become when a slide eats into it. */
  const val MIN_SLIDE_NEIGHBOR_MS = 200L

  data class SourceRange(val sourceStartMs: Long, val sourceEndMs: Long, val durationMs: Long)

  /**
   * Clamps a requested source in/out pair to `[0, maxSourceMs]` with at least
   * [MIN_SOURCE_SPAN_MS] of media, and derives the timeline duration at [speed].
   */
  fun clampSourceRange(requestedStartMs: Long, requestedEndMs: Long, maxSourceMs: Long, speed: Float): SourceRange {
    val start = requestedStartMs.coerceIn(0L, (maxSourceMs - MIN_SOURCE_SPAN_MS).coerceAtLeast(0L))
    val end = requestedEndMs.coerceIn(start + MIN_SOURCE_SPAN_MS, maxOf(maxSourceMs, start + MIN_SOURCE_SPAN_MS))
    val duration = ((end - start) / speed).toLong().coerceAtLeast(MIN_SOURCE_SPAN_MS)
    return SourceRange(start, end, duration)
  }

  private fun VideoClip.maxSourceMs(): Long =
    if (sourceTotalDurationMs > 0L) sourceTotalDurationMs else maxOf(sourceEndMs, durationMs)

  /** Full untrimmed source length of a video clip, used to reset its trim. */
  fun fullSourceDurationMs(clip: VideoClip): Long = clip.maxSourceMs()

  private fun slipRange(sourceStartMs: Long, durationMs: Long, speed: Float, deltaMs: Long): Pair<Long, Long> {
    val newStart = (sourceStartMs + (deltaMs * speed).toLong()).coerceAtLeast(0L)
    return newStart to newStart + (durationMs * speed).toLong()
  }

  /** Slides the source window of a video or overlay clip under a fixed timeline range. */
  fun slipVideo(clips: List<VideoClip>, clipId: String, deltaMs: Long): List<VideoClip>? {
    val clip = clips.find { it.id == clipId } ?: return null
    val (start, end) = slipRange(clip.sourceStartMs, clip.durationMs, clip.speed, deltaMs)
    return clips.map { if (it.id == clipId) it.copy(sourceStartMs = start, sourceEndMs = end) else it }
  }

  fun slipAudio(clips: List<AudioClip>, clipId: String, deltaMs: Long): List<AudioClip>? {
    val clip = clips.find { it.id == clipId } ?: return null
    val (start, end) = slipRange(clip.sourceStartMs, clip.durationMs, clip.speed, deltaMs)
    return clips.map { if (it.id == clipId) it.copy(sourceStartMs = start, sourceEndMs = end) else it }
  }

  /**
   * Slides an interior main-track clip: it moves by [deltaMs] while the previous clip grows and
   * the next one shrinks by the same amount, so the total length is unchanged. Returns null for
   * the first or last clip, which have no neighbour to absorb the change.
   */
  fun slideMainTrackClip(timeline: Timeline, clipId: String, deltaMs: Long): Timeline? {
    val clips = timeline.videoClips
    val index = clips.indexOfFirst { it.id == clipId }
    if (index <= 0 || index >= clips.size - 1) return null
    val prev = clips[index - 1]
    val clip = clips[index]
    val next = clips[index + 1]
    val delta = deltaMs.coerceIn(-(prev.durationMs - MIN_SLIDE_NEIGHBOR_MS), next.durationMs - MIN_SLIDE_NEIGHBOR_MS)
    val list = clips.toMutableList()
    list[index - 1] = prev.copy(
      durationMs = prev.durationMs + delta,
      sourceEndMs = prev.sourceEndMs + (delta * prev.speed).toLong()
    )
    list[index] = clip.copy(timelineStartMs = clip.timelineStartMs + delta)
    list[index + 1] = next.copy(
      timelineStartMs = next.timelineStartMs + delta,
      durationMs = next.durationMs - delta,
      sourceStartMs = next.sourceStartMs + (delta * next.speed).toLong()
    )
    return timeline.copy(videoClips = list)
  }

  /**
   * Sets new source in/out points on a main-track clip. With [rippleContiguous] the whole main
   * track is repacked from 0 so later clips follow the new duration.
   */
  fun setMainTrackSourceRange(
    timeline: Timeline,
    clipId: String,
    requestedStartMs: Long,
    requestedEndMs: Long,
    rippleContiguous: Boolean
  ): Timeline? {
    val clips = timeline.videoClips.toMutableList()
    val index = clips.indexOfFirst { it.id == clipId }
    if (index == -1) return null
    val clip = clips[index]
    val range = clampSourceRange(requestedStartMs, requestedEndMs, clip.maxSourceMs(), clip.speed)
    val total = if (clip.sourceTotalDurationMs > 0L) clip.sourceTotalDurationMs else maxOf(clip.sourceEndMs, range.sourceEndMs)
    clips[index] = clip.copy(
      sourceStartMs = range.sourceStartMs,
      sourceEndMs = range.sourceEndMs,
      durationMs = range.durationMs,
      sourceTotalDurationMs = total
    )
    if (rippleContiguous) {
      var cursor = 0L
      for (i in clips.indices) {
        clips[i] = clips[i].copy(timelineStartMs = cursor)
        cursor += clips[i].durationMs
      }
    }
    return timeline.copy(videoClips = clips)
  }

  fun setOverlaySourceRange(timeline: Timeline, clipId: String, requestedStartMs: Long, requestedEndMs: Long): Timeline? {
    val clip = timeline.overlayClips.find { it.id == clipId } ?: return null
    val range = clampSourceRange(requestedStartMs, requestedEndMs, clip.maxSourceMs(), clip.speed)
    val total = if (clip.sourceTotalDurationMs > 0L) clip.sourceTotalDurationMs else maxOf(clip.sourceEndMs, range.sourceEndMs)
    return timeline.copy(overlayClips = timeline.overlayClips.map {
      if (it.id == clipId) it.copy(
        sourceStartMs = range.sourceStartMs,
        sourceEndMs = range.sourceEndMs,
        durationMs = range.durationMs,
        sourceTotalDurationMs = total
      ) else it
    })
  }

  fun setAudioSourceRange(timeline: Timeline, clipId: String, requestedStartMs: Long, requestedEndMs: Long): Timeline? {
    val clip = timeline.audioClips.find { it.id == clipId } ?: return null
    val range = clampSourceRange(requestedStartMs, requestedEndMs, maxOf(clip.sourceEndMs, clip.durationMs), clip.speed)
    return timeline.copy(audioClips = timeline.audioClips.map {
      if (it.id == clipId) it.copy(
        sourceStartMs = range.sourceStartMs,
        sourceEndMs = range.sourceEndMs,
        durationMs = range.durationMs
      ) else it
    })
  }
}
