package com.example.engine.timeline

import com.example.domain.model.AudioClip
import com.example.domain.model.EffectClip
import com.example.domain.model.ShapeClip
import com.example.domain.model.StickerClip
import com.example.domain.model.TextClip
import com.example.domain.model.Timeline
import com.example.domain.model.TimelineMarker
import com.example.domain.model.TrackType
import com.example.domain.model.Transition
import com.example.domain.model.VideoClip

/**
 * Whole-timeline range edits as pure functions: cut many points at once, or take time ranges out
 * and close the gap. They are the base of "cut on the beat", "split at scene changes",
 * "remove filler words" and "remove pauses", each of which is a single undo step in the engine.
 */
object TimelineRangeOps {

  /** A range that must be removed from the timeline, in timeline milliseconds. */
  data class TimeRange(val startMs: Long, val endMs: Long) {
    val lengthMs: Long get() = endMs - startMs
  }

  private const val EDGE_MS = TimelineSplitEngine.MIN_SPLIT_MARGIN_MS

  /** Sorts, clamps and merges overlapping or touching ranges; drops empty ones. */
  fun normalize(ranges: List<TimeRange>, minLengthMs: Long = 1L): List<TimeRange> {
    val sorted = ranges.filter { it.endMs - it.startMs >= minLengthMs && it.endMs > 0L }
      .map { TimeRange(it.startMs.coerceAtLeast(0L), it.endMs) }
      .sortedBy { it.startMs }
    val merged = ArrayList<TimeRange>()
    for (r in sorted) {
      val last = merged.lastOrNull()
      if (last != null && r.startMs <= last.endMs) merged[merged.size - 1] = TimeRange(last.startMs, maxOf(last.endMs, r.endMs))
      else merged.add(r)
    }
    return merged
  }

  /**
   * Splits main-track clips at every time in [timesMs] (one pass, as one resulting timeline).
   * A clip on the audio lane that exactly mirrors the split video clip is cut with it, the same rule
   * a single split at the playhead uses. Times too close to a clip edge are skipped.
   */
  fun splitMainTrackAt(
    timeline: Timeline,
    timesMs: List<Long>,
    isTrackLocked: (TrackType, Int) -> Boolean
  ): Timeline {
    var working = timeline
    for (t in timesMs.distinct().sorted()) {
      val clip = working.videoClips.firstOrNull {
        TimelineSplitEngine.isInsideSplitRange(t, it.timelineStartMs, it.durationMs)
      } ?: continue
      val linkedAudio = working.audioClips.filter {
        it.timelineStartMs == clip.timelineStartMs && it.durationMs == clip.durationMs
      }.map { it.id }
      val split = TimelineSplitEngine.splitClipInTimeline(working, clip.id, t, isTrackLocked) ?: continue
      working = split.timeline
      for (audioId in linkedAudio) {
        TimelineSplitEngine.splitClipInTimeline(working, audioId, t, isTrackLocked)?.let { working = it.timeline }
      }
    }
    return working
  }

  /** Splits every clip on every lane that [atMs] strictly crosses. */
  fun splitAllAt(timeline: Timeline, atMs: Long, isTrackLocked: (TrackType, Int) -> Boolean): Timeline {
    var working = timeline
    for (id in TimelineSplitEngine.clipIdsCrossing(timeline, atMs)) {
      TimelineSplitEngine.splitClipInTimeline(working, id, atMs, isTrackLocked)?.let { working = it.timeline }
    }
    return working
  }

  /**
   * Removes [ranges] from every lane and moves everything after each range left by its length.
   * Clips crossing a boundary are split first, so only the part inside the range disappears.
   * Locked clips (or lanes) are neither split nor moved. Transitions touching a removed clip are
   * dropped and the remaining ones are re-indexed. Returns the same timeline when nothing changed.
   */
  fun removeRanges(
    timeline: Timeline,
    ranges: List<TimeRange>,
    isTrackLocked: (TrackType, Int) -> Boolean
  ): Timeline {
    val ordered = normalize(ranges).sortedByDescending { it.startMs }
    var working = timeline
    for (r in ordered) {
      working = splitAllAt(working, r.endMs, isTrackLocked)
      working = splitAllAt(working, r.startMs, isTrackLocked)
      working = removeOne(working, r, isTrackLocked)
    }
    return if (working == timeline) timeline else working
  }

  private fun removeOne(tl: Timeline, r: TimeRange, locked: (TrackType, Int) -> Boolean): Timeline {
    val len = r.lengthMs
    fun inside(start: Long, dur: Long) = start >= r.startMs - EDGE_MS && start + dur <= r.endMs + EDGE_MS
    fun after(start: Long) = start >= r.endMs - EDGE_MS
    fun moved(start: Long) = if (start < r.endMs) r.startMs else start - len

    val removedMain = tl.videoClips.withIndex()
      .filter { (_, c) -> !c.isLocked && !locked(TrackType.MAIN_VIDEO, c.trackIndex) && inside(c.timelineStartMs, c.durationMs) }
      .map { it.index }.toSet()

    fun <T> shift(list: List<T>, start: (T) -> Long, dur: (T) -> Long, isLocked: (T) -> Boolean, copy: (T, Long) -> T): List<T> =
      list.mapNotNull { c ->
        when {
          isLocked(c) -> c
          inside(start(c), dur(c)) -> null
          after(start(c)) -> copy(c, moved(start(c)))
          else -> c
        }
      }

    val video = shift(tl.videoClips, { it.timelineStartMs }, { it.durationMs },
      { it.isLocked || locked(TrackType.MAIN_VIDEO, it.trackIndex) }) { c: VideoClip, s -> c.copy(timelineStartMs = s) }
    val overlay = shift(tl.overlayClips, { it.timelineStartMs }, { it.durationMs },
      { it.isLocked || locked(TrackType.OVERLAY, it.trackIndex) }) { c: VideoClip, s -> c.copy(timelineStartMs = s) }
    val audio = shift(tl.audioClips, { it.timelineStartMs }, { it.durationMs },
      { it.isLocked || locked(TrackType.AUDIO, it.trackIndex) }) { c: AudioClip, s -> c.copy(timelineStartMs = s) }
    val text = shift(tl.textClips, { it.timelineStartMs }, { it.durationMs },
      { it.isLocked || locked(TrackType.TEXT, it.trackIndex) }) { c: TextClip, s -> c.copy(timelineStartMs = s) }
    val sticker = shift(tl.stickerClips, { it.timelineStartMs }, { it.durationMs },
      { it.isLocked || locked(TrackType.STICKER, it.trackIndex) }) { c: StickerClip, s -> c.copy(timelineStartMs = s) }
    val effect = shift(tl.effectClips, { it.timelineStartMs }, { it.durationMs },
      { it.isLocked || locked(TrackType.EFFECT, it.trackIndex) }) { c: EffectClip, s -> c.copy(timelineStartMs = s) }
    val shape = shift(tl.shapeClips, { it.timelineStartMs }, { it.durationMs },
      { it.isLocked || locked(TrackType.SHAPE, it.trackIndex) }) { c: ShapeClip, s -> c.copy(timelineStartMs = s) }

    val markers = tl.markers.mapNotNull { m: TimelineMarker ->
      when {
        m.timeMs in r.startMs until r.endMs -> null
        m.timeMs >= r.endMs -> m.copy(timeMs = m.timeMs - len)
        else -> m
      }
    }

    return tl.copy(
      videoClips = video,
      overlayClips = overlay,
      audioClips = audio,
      textClips = text,
      stickerClips = sticker,
      effectClips = effect,
      shapeClips = shape,
      markers = markers,
      transitions = reindexTransitions(tl.transitions, removedMain)
    )
  }

  /** A transition sits between main clip i and i+1. Drop it when either side is gone, shift the rest. */
  internal fun reindexTransitions(transitions: List<Transition>, removedMain: Set<Int>): List<Transition> {
    if (removedMain.isEmpty()) return transitions
    return transitions.mapNotNull { tr ->
      val i = tr.clipIndexBefore
      if (i in removedMain || (i + 1) in removedMain) null
      else tr.copy(clipIndexBefore = i - removedMain.count { it < i })
    }
  }
}
