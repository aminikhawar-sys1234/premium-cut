package com.example.engine.timeline

import com.example.domain.model.AudioClip
import com.example.domain.model.EffectClip
import com.example.domain.model.StickerClip
import com.example.domain.model.TextClip
import com.example.domain.model.Timeline
import com.example.domain.model.TrackSettings
import com.example.domain.model.TrackType
import com.example.domain.model.VideoClip

/**
 * Whole-timeline layout rules (main-track packing, video-end bound, ripple and synchronized
 * shifts). Every function is pure: it returns a new [Timeline], or null when nothing changed.
 */
object TimelineTrackRules {

  /** Shortest a non-video clip may become when clamped to the main track end. */
  const val MIN_CLAMPED_DURATION_MS = 200L

  private class Lens<C>(
    val start: (C) -> Long,
    val duration: (C) -> Long,
    val withStart: (C, Long) -> C,
    val withDuration: (C, Long) -> C
  )

  private val videoLens = Lens<VideoClip>({ it.timelineStartMs }, { it.durationMs }, { c, v -> c.copy(timelineStartMs = v) }, { c, v -> c.copy(durationMs = v) })
  private val audioLens = Lens<AudioClip>({ it.timelineStartMs }, { it.durationMs }, { c, v -> c.copy(timelineStartMs = v) }, { c, v -> c.copy(durationMs = v) })
  private val textLens = Lens<TextClip>({ it.timelineStartMs }, { it.durationMs }, { c, v -> c.copy(timelineStartMs = v) }, { c, v -> c.copy(durationMs = v) })
  private val stickerLens = Lens<StickerClip>({ it.timelineStartMs }, { it.durationMs }, { c, v -> c.copy(timelineStartMs = v) }, { c, v -> c.copy(durationMs = v) })
  private val effectLens = Lens<EffectClip>({ it.timelineStartMs }, { it.durationMs }, { c, v -> c.copy(timelineStartMs = v) }, { c, v -> c.copy(durationMs = v) })

  /** Every clip edge on every track plus 0 and the timeline end, sorted and distinct. */
  fun cutPositions(timeline: Timeline): List<Long> {
    val cuts = sortedSetOf(0L)
    timeline.videoClips.forEach { cuts.add(it.timelineStartMs); cuts.add(it.timelineStartMs + it.durationMs) }
    timeline.overlayClips.forEach { cuts.add(it.timelineStartMs); cuts.add(it.timelineStartMs + it.durationMs) }
    timeline.audioClips.forEach { cuts.add(it.timelineStartMs); cuts.add(it.timelineStartMs + it.durationMs) }
    timeline.textClips.forEach { cuts.add(it.timelineStartMs); cuts.add(it.timelineStartMs + it.durationMs) }
    timeline.stickerClips.forEach { cuts.add(it.timelineStartMs); cuts.add(it.timelineStartMs + it.durationMs) }
    cuts.add(timeline.totalDurationMs)
    return cuts.toList()
  }

  /** Repacks main-track clips back to back from 0 in list order (the zero-point lock). */
  fun packMainTrack(timeline: Timeline): Timeline? {
    val clips = timeline.videoClips
    if (clips.isEmpty()) return null
    var cursor = 0L
    var modified = false
    val packed = clips.map { clip ->
      val placed = if (clip.timelineStartMs != cursor) {
        modified = true
        clip.copy(timelineStartMs = cursor)
      } else clip
      cursor += clip.durationMs
      placed
    }
    return if (modified) timeline.copy(videoClips = packed) else null
  }

  /** Moves the main-track clip at [fromIndex] to [toIndex] and repacks the track from 0. */
  fun reorderMainTrack(timeline: Timeline, fromIndex: Int, toIndex: Int): Timeline? {
    val clips = timeline.videoClips.toMutableList()
    if (fromIndex !in clips.indices || toIndex !in clips.indices || fromIndex == toIndex) return null
    clips.add(toIndex, clips.removeAt(fromIndex))
    var cursor = 0L
    for (i in clips.indices) {
      clips[i] = clips[i].copy(timelineStartMs = cursor)
      cursor += clips[i].durationMs
    }
    return timeline.copy(videoClips = clips)
  }

  fun mainTrackEndMs(timeline: Timeline): Long? = timeline.videoClips.maxOfOrNull { it.timelineStartMs + it.durationMs }

  /**
   * Drops non-main clips that start at or after the main track end and trims the ones that cross
   * it (never below [MIN_CLAMPED_DURATION_MS]). Assumes the main track is already packed.
   */
  fun clampToMainTrackEnd(timeline: Timeline): Timeline? {
    val end = mainTrackEndMs(timeline) ?: return null
    var modified = false
    fun <C> List<C>.clamp(lens: Lens<C>): List<C> = mapNotNull { clip ->
      val start = lens.start(clip)
      when {
        start >= end -> { modified = true; null }
        start + lens.duration(clip) > end -> {
          modified = true
          lens.withDuration(clip, (end - start).coerceAtLeast(MIN_CLAMPED_DURATION_MS))
        }
        else -> clip
      }
    }
    val clamped = timeline.copy(
      overlayClips = timeline.overlayClips.clamp(videoLens),
      audioClips = timeline.audioClips.clamp(audioLens),
      textClips = timeline.textClips.clamp(textLens),
      stickerClips = timeline.stickerClips.clamp(stickerLens),
      effectClips = timeline.effectClips.clamp(effectLens)
    )
    return if (modified) clamped else null
  }

  /** Shifts clips starting at or after [fromMs] by [deltaMs] on every unlocked track. */
  fun rippleFrom(timeline: Timeline, fromMs: Long, deltaMs: Long, isLocked: (TrackType) -> Boolean): Timeline {
    if (deltaMs == 0L) return timeline
    fun <C> List<C>.ripple(type: TrackType, lens: Lens<C>): List<C> =
      if (isLocked(type)) this
      else map { if (lens.start(it) >= fromMs) lens.withStart(it, (lens.start(it) + deltaMs).coerceAtLeast(0L)) else it }
        .sortedBy(lens.start)
    return timeline.copy(
      videoClips = timeline.videoClips.ripple(TrackType.MAIN_VIDEO, videoLens),
      overlayClips = timeline.overlayClips.ripple(TrackType.OVERLAY, videoLens),
      audioClips = timeline.audioClips.ripple(TrackType.AUDIO, audioLens),
      textClips = timeline.textClips.ripple(TrackType.TEXT, textLens),
      stickerClips = timeline.stickerClips.ripple(TrackType.STICKER, stickerLens),
      effectClips = timeline.effectClips.ripple(TrackType.EFFECT, effectLens)
    )
  }

  /** Earliest clip start across unlocked tracks, or null when every track is empty or locked. */
  fun earliestUnlockedStart(timeline: Timeline, isLocked: (TrackType) -> Boolean): Long? {
    val starts = buildList {
      if (!isLocked(TrackType.MAIN_VIDEO)) timeline.videoClips.mapTo(this) { it.timelineStartMs }
      if (!isLocked(TrackType.OVERLAY)) timeline.overlayClips.mapTo(this) { it.timelineStartMs }
      if (!isLocked(TrackType.AUDIO)) timeline.audioClips.mapTo(this) { it.timelineStartMs }
      if (!isLocked(TrackType.TEXT)) timeline.textClips.mapTo(this) { it.timelineStartMs }
      if (!isLocked(TrackType.STICKER)) timeline.stickerClips.mapTo(this) { it.timelineStartMs }
      if (!isLocked(TrackType.EFFECT)) timeline.effectClips.mapTo(this) { it.timelineStartMs }
    }
    return starts.minOrNull()
  }

  /** Limits a leftward shift so the earliest clip stops at 0; 0 means there is nothing to move. */
  fun clampShiftDelta(deltaMs: Long, earliestStartMs: Long): Long =
    if (deltaMs < 0) maxOf(deltaMs, -earliestStartMs) else deltaMs

  /**
   * Shifts every clip on the tracks selected by [shouldShift] by [deltaMs]. Shifted lists are
   * re-sorted by start; with [sortAll] untouched lists are re-sorted too.
   */
  fun shiftTracks(
    timeline: Timeline,
    deltaMs: Long,
    shouldShift: (TrackType) -> Boolean,
    sortAll: Boolean = false
  ): Timeline {
    fun <C> List<C>.shift(type: TrackType, lens: Lens<C>): List<C> = when {
      shouldShift(type) -> map { lens.withStart(it, (lens.start(it) + deltaMs).coerceAtLeast(0L)) }.sortedBy(lens.start)
      sortAll -> sortedBy(lens.start)
      else -> this
    }
    return timeline.copy(
      videoClips = timeline.videoClips.shift(TrackType.MAIN_VIDEO, videoLens),
      overlayClips = timeline.overlayClips.shift(TrackType.OVERLAY, videoLens),
      audioClips = timeline.audioClips.shift(TrackType.AUDIO, audioLens),
      textClips = timeline.textClips.shift(TrackType.TEXT, textLens),
      stickerClips = timeline.stickerClips.shift(TrackType.STICKER, stickerLens),
      effectClips = timeline.effectClips.shift(TrackType.EFFECT, effectLens)
    )
  }

  fun withTrackSettings(timeline: Timeline, type: TrackType, update: (TrackSettings) -> TrackSettings): Timeline {
    val settings = timeline.trackSettings.toMutableMap()
    settings[type] = update(settings[type] ?: TrackSettings(type))
    return timeline.copy(trackSettings = settings)
  }
}
