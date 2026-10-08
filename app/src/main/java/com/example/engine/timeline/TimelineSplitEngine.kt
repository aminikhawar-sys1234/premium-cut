package com.example.engine.timeline

import com.example.domain.model.AudioClip
import com.example.domain.model.ClipKeyframe
import com.example.domain.model.EffectClip
import com.example.domain.model.InAnimationType
import com.example.domain.model.OutAnimationType
import com.example.domain.model.ShapeClip
import com.example.domain.model.StickerClip
import com.example.domain.model.TextClip
import com.example.domain.model.Timeline
import com.example.domain.model.TrackType
import com.example.domain.model.VideoClip
import com.example.domain.model.WordTiming
import com.example.engine.SelectedTrackElement
import java.util.UUID

/**
 * The single, pure implementation of clip splitting for the multi-track timeline.
 *
 * Nothing here touches engine state, history or selection; [com.example.engine.TimelineEngine]
 * wraps these functions with locking, undo recording and selection. A split is lossless: the head
 * and tail play back exactly what the original clip played (source in/out points, speed, reverse,
 * keyframes, audio fades and the transition that followed the clip are all preserved).
 */
object TimelineSplitEngine {

  /** Splits closer than this to a clip edge are rejected (they would create a zero-length clip). */
  const val MIN_SPLIT_MARGIN_MS = 10L

  data class SplitResult<T>(
    val isSuccess: Boolean,
    val headClip: T? = null,
    val tailClip: T? = null,
    val message: String = ""
  )

  data class TimelineSplit(
    val timeline: Timeline,
    val headId: String,
    val tailId: String,
    val tailElement: SelectedTrackElement
  )

  private enum class Kind(val trackType: TrackType) {
    VIDEO(TrackType.MAIN_VIDEO),
    OVERLAY(TrackType.OVERLAY),
    AUDIO(TrackType.AUDIO),
    TEXT(TrackType.TEXT),
    STICKER(TrackType.STICKER),
    EFFECT(TrackType.EFFECT),
    SHAPE(TrackType.SHAPE)
  }

  fun isInsideSplitRange(timeMs: Long, startMs: Long, durationMs: Long, marginMs: Long = MIN_SPLIT_MARGIN_MS): Boolean =
    timeMs > startMs + marginMs && timeMs < startMs + durationMs - marginMs

  /**
   * Distributes keyframes (times relative to the clip start) across a split at [headDurationMs].
   * A keyframe exactly on the cut is kept by both halves so the value at the cut is unchanged.
   */
  fun splitKeyframes(keyframes: List<ClipKeyframe>, headDurationMs: Long): Pair<List<ClipKeyframe>, List<ClipKeyframe>> {
    if (keyframes.isEmpty()) return emptyList<ClipKeyframe>() to emptyList()
    val head = keyframes.filter { it.timeMs <= headDurationMs }
    val tail = keyframes.filter { it.timeMs >= headDurationMs }
      .map { it.copy(id = UUID.randomUUID().toString(), timeMs = it.timeMs - headDurationMs) }
    return head to tail
  }

  private fun splitWords(words: List<WordTiming>, headDurationMs: Long): Pair<List<WordTiming>, List<WordTiming>> {
    if (words.isEmpty()) return emptyList<WordTiming>() to emptyList()
    val head = words.filter { it.startMs < headDurationMs }
      .map { if (it.startMs + it.durationMs > headDurationMs) it.copy(durationMs = headDurationMs - it.startMs) else it }
    val tail = words.filter { it.startMs + it.durationMs > headDurationMs }
      .map {
        val start = (it.startMs - headDurationMs).coerceAtLeast(0L)
        it.copy(startMs = start, durationMs = it.startMs + it.durationMs - headDurationMs - start)
      }
    return head to tail
  }

  private fun newId(existing: Set<String> = emptySet()): String {
    var id: String
    do {
      id = UUID.randomUUID().toString()
    } while (id in existing)
    return id
  }

  private fun allClipIds(timeline: Timeline): Set<String> = buildSet {
    timeline.videoClips.forEach { add(it.id) }
    timeline.overlayClips.forEach { add(it.id) }
    timeline.audioClips.forEach { add(it.id) }
    timeline.textClips.forEach { add(it.id) }
    timeline.stickerClips.forEach { add(it.id) }
    timeline.effectClips.forEach { add(it.id) }
    timeline.shapeClips.forEach { add(it.id) }
  }

  /**
   * Source frame that plays at [atMs]. Always inside `[min(sourceStart,sourceEnd), max(...)]`
   * so a later split cannot invert the window and crash `coerceIn` / Media3 clipping.
   */
  internal fun sourceCutMs(clip: VideoClip, atMs: Long): Long {
    val lo = minOf(clip.sourceStartMs, clip.sourceEndMs)
    val hi = maxOf(clip.sourceStartMs, clip.sourceEndMs)
    if (lo >= hi) return lo
    val headDur = (atMs - clip.timelineStartMs).coerceAtLeast(0L)
    val span = hi - lo
    val dur = clip.durationMs.coerceAtLeast(1L)
    val proportional = if (clip.isReversed) {
      hi - (span.toDouble() * headDur.toDouble() / dur.toDouble()).toLong()
    } else {
      lo + (span.toDouble() * headDur.toDouble() / dur.toDouble()).toLong()
    }
    val mapped = clip.timelineToSourceMs(atMs)
    val cut = if (clip.speedCurve.preset == com.example.domain.model.SpeedCurvePreset.STANDARD) mapped else proportional
    return if (cut < lo) lo else if (cut > hi) hi else cut
  }

  private fun orderedWindow(start: Long, end: Long): Pair<Long, Long> {
    val lo = minOf(start, end)
    val hi = maxOf(start, end)
    return lo to hi
  }

  /**
   * Source position that plays at [timelineMs] for a constant-speed audio clip (reverse aware).
   * This is the point both halves are cut at, so it never changes what is heard.
   */
  fun audioSourcePositionAt(clip: AudioClip, timelineMs: Long): Long {
    val dur = clip.durationMs.coerceAtLeast(0L)
    val rawRel = timelineMs - clip.timelineStartMs
    val rel = when {
      rawRel < 0L -> 0L
      rawRel > dur -> dur
      else -> rawRel
    }
    val offset = Math.round(rel * clip.speed.coerceAtLeast(0.01f).toDouble())
    val lo = minOf(clip.sourceStartMs, clip.sourceEndMs)
    val hi = maxOf(clip.sourceStartMs, clip.sourceEndMs)
    val raw = if (clip.isReversed) clip.sourceEndMs - offset else clip.sourceStartMs + offset
    return if (raw < lo) lo else if (raw > hi) hi else raw
  }

  // ---- Pure per-type splits (no margin check; callers validate the range) ----

  internal fun splitVideoUnchecked(
    clip: VideoClip,
    atMs: Long,
    existingIds: Set<String> = emptySet()
  ): Pair<VideoClip, VideoClip> {
    val headDur = atMs - clip.timelineStartMs
    val tailDur = clip.durationMs - headDur
    val cut = sourceCutMs(clip, atMs)
    val (headKf, tailKf) = splitKeyframes(clip.keyframes, headDur)
    val srcStart = minOf(clip.sourceStartMs, clip.sourceEndMs)
    val srcEnd = maxOf(clip.sourceStartMs, clip.sourceEndMs)
    val headSrc = if (clip.isReversed) orderedWindow(cut, srcEnd) else orderedWindow(srcStart, cut)
    val tailSrc = if (clip.isReversed) orderedWindow(srcStart, cut) else orderedWindow(cut, srcEnd)
    val used = existingIds + clip.id
    val head = clip.copy(
      durationMs = headDur,
      sourceStartMs = headSrc.first,
      sourceEndMs = headSrc.second,
      keyframes = headKf,
      animation = clip.animation.copy(outType = OutAnimationType.NONE)
    )
    val tail = clip.copy(
      id = newId(used),
      timelineStartMs = atMs,
      durationMs = tailDur,
      sourceStartMs = tailSrc.first,
      sourceEndMs = tailSrc.second,
      keyframes = tailKf,
      animation = clip.animation.copy(inType = InAnimationType.NONE)
    )
    return head to tail
  }

  private fun splitAudioUnchecked(
    clip: AudioClip,
    atMs: Long,
    existingIds: Set<String> = emptySet()
  ): Pair<AudioClip, AudioClip> {
    val headDur = atMs - clip.timelineStartMs
    val tailDur = clip.durationMs - headDur
    val cut = audioSourcePositionAt(clip, atMs)
    val (headKf, tailKf) = splitKeyframes(clip.keyframes, headDur)
    val srcStart = minOf(clip.sourceStartMs, clip.sourceEndMs)
    val srcEnd = maxOf(clip.sourceStartMs, clip.sourceEndMs)
    val headSrc = if (clip.isReversed) orderedWindow(cut, srcEnd) else orderedWindow(srcStart, cut)
    val tailSrc = if (clip.isReversed) orderedWindow(srcStart, cut) else orderedWindow(cut, srcEnd)
    val used = existingIds + clip.id
    val head = clip.copy(
      durationMs = headDur,
      sourceStartMs = headSrc.first,
      sourceEndMs = headSrc.second,
      fadeInMs = minOf(clip.fadeInMs, headDur),
      fadeOutMs = 0L,
      keyframes = headKf
    )
    val tail = clip.copy(
      id = newId(used),
      timelineStartMs = atMs,
      durationMs = tailDur,
      sourceStartMs = tailSrc.first,
      sourceEndMs = tailSrc.second,
      fadeInMs = 0L,
      fadeOutMs = minOf(clip.fadeOutMs, tailDur),
      keyframes = tailKf
    )
    return head to tail
  }

  private fun splitTextUnchecked(
    clip: TextClip,
    atMs: Long,
    existingIds: Set<String> = emptySet()
  ): Pair<TextClip, TextClip> {
    val headDur = atMs - clip.timelineStartMs
    val tailDur = clip.durationMs - headDur
    val (headKf, tailKf) = splitKeyframes(clip.keyframes, headDur)
    val (headWords, tailWords) = splitWords(clip.words, headDur)
    return clip.copy(durationMs = headDur, keyframes = headKf, words = headWords) to
      clip.copy(id = newId(existingIds + clip.id), timelineStartMs = atMs, durationMs = tailDur, keyframes = tailKf, words = tailWords)
  }

  private fun splitStickerUnchecked(
    clip: StickerClip,
    atMs: Long,
    existingIds: Set<String> = emptySet()
  ): Pair<StickerClip, StickerClip> {
    val headDur = atMs - clip.timelineStartMs
    val tailDur = clip.durationMs - headDur
    val (headKf, tailKf) = splitKeyframes(clip.keyframes, headDur)
    return clip.copy(durationMs = headDur, keyframes = headKf) to
      clip.copy(id = newId(existingIds + clip.id), timelineStartMs = atMs, durationMs = tailDur, keyframes = tailKf)
  }

  private fun splitEffectUnchecked(
    clip: EffectClip,
    atMs: Long,
    existingIds: Set<String> = emptySet()
  ): Pair<EffectClip, EffectClip> {
    val headDur = atMs - clip.timelineStartMs
    val tailDur = clip.durationMs - headDur
    val (headKf, tailKf) = splitKeyframes(clip.keyframes, headDur)
    return clip.copy(durationMs = headDur, keyframes = headKf) to
      clip.copy(id = newId(existingIds + clip.id), timelineStartMs = atMs, durationMs = tailDur, keyframes = tailKf)
  }

  private fun splitShapeUnchecked(
    clip: ShapeClip,
    atMs: Long,
    existingIds: Set<String> = emptySet()
  ): Pair<ShapeClip, ShapeClip> {
    val headDur = atMs - clip.timelineStartMs
    val tailDur = clip.durationMs - headDur
    val (headKf, tailKf) = splitKeyframes(clip.keyframes, headDur)
    return clip.copy(durationMs = headDur, keyframes = headKf) to
      clip.copy(id = newId(existingIds + clip.id), timelineStartMs = atMs, durationMs = tailDur, keyframes = tailKf)
  }

  fun splitVideoClip(clip: VideoClip, splitTimestampMs: Long): SplitResult<VideoClip> =
    if (!isInsideSplitRange(splitTimestampMs, clip.timelineStartMs, clip.durationMs)) {
      SplitResult(false, message = "Split location is too close to clip start or end.")
    } else splitVideoUnchecked(clip, splitTimestampMs).let { SplitResult(true, it.first, it.second, "Split successfully.") }

  fun splitAudioClip(clip: AudioClip, splitTimestampMs: Long): SplitResult<AudioClip> =
    if (!isInsideSplitRange(splitTimestampMs, clip.timelineStartMs, clip.durationMs)) {
      SplitResult(false, message = "Split location is too close to audio clip start or end.")
    } else splitAudioUnchecked(clip, splitTimestampMs).let { SplitResult(true, it.first, it.second, "Audio split successfully.") }

  fun splitTextClip(clip: TextClip, splitTimestampMs: Long): SplitResult<TextClip> =
    if (!isInsideSplitRange(splitTimestampMs, clip.timelineStartMs, clip.durationMs)) {
      SplitResult(false, message = "Split location is too close to text clip boundary.")
    } else splitTextUnchecked(clip, splitTimestampMs).let { SplitResult(true, it.first, it.second, "Text split successfully.") }

  fun splitEffectClip(clip: EffectClip, splitTimestampMs: Long): SplitResult<EffectClip> =
    if (!isInsideSplitRange(splitTimestampMs, clip.timelineStartMs, clip.durationMs)) {
      SplitResult(false, message = "Split location is too close to effect boundary.")
    } else splitEffectUnchecked(clip, splitTimestampMs).let { SplitResult(true, it.first, it.second, "Effect split successfully.") }

  fun splitStickerClip(clip: StickerClip, splitTimestampMs: Long): SplitResult<StickerClip> =
    if (!isInsideSplitRange(splitTimestampMs, clip.timelineStartMs, clip.durationMs)) {
      SplitResult(false, message = "Split location is too close to sticker boundary.")
    } else splitStickerUnchecked(clip, splitTimestampMs).let { SplitResult(true, it.first, it.second, "Sticker split successfully.") }

  fun splitShapeClip(clip: ShapeClip, splitTimestampMs: Long): SplitResult<ShapeClip> =
    if (!isInsideSplitRange(splitTimestampMs, clip.timelineStartMs, clip.durationMs)) {
      SplitResult(false, message = "Split location is too close to shape boundary.")
    } else splitShapeUnchecked(clip, splitTimestampMs).let { SplitResult(true, it.first, it.second, "Shape split successfully.") }

  // ---- Timeline level ----

  /**
   * Splits [clipId] at [atMs] on whatever track it lives on and returns the resulting timeline,
   * or null if the clip does not exist, is locked (clip flag or track), or [atMs] is not strictly
   * inside the clip.
   *
   * @param isTrackLocked answers "is this lane locked" for a (track type, lane index) pair.
   */
  fun splitClipInTimeline(
    timeline: Timeline,
    clipId: String,
    atMs: Long,
    isTrackLocked: (TrackType, Int) -> Boolean
  ): TimelineSplit? {
    val existingIds = allClipIds(timeline)
    timeline.videoClips.indexOfFirst { it.id == clipId }.takeIf { it >= 0 }?.let { index ->
      val clip = timeline.videoClips[index]
      if (clip.isLocked || isTrackLocked(Kind.VIDEO.trackType, clip.trackIndex)) return null
      if (!isInsideSplitRange(atMs, clip.timelineStartMs, clip.durationMs)) return null
      val (head, tail) = splitVideoUnchecked(clip, atMs, existingIds)
      val list = timeline.videoClips.toMutableList().apply { this[index] = head; add(index + 1, tail) }
      // The transition that followed the original clip now follows the tail clip.
      val transitions = timeline.transitions.map { tr ->
        if (tr.clipIndexBefore >= index) tr.copy(clipIndexBefore = tr.clipIndexBefore + 1) else tr
      }
      return TimelineSplit(timeline.copy(videoClips = list, transitions = transitions), head.id, tail.id, SelectedTrackElement.Video(tail.id))
    }
    timeline.overlayClips.indexOfFirst { it.id == clipId }.takeIf { it >= 0 }?.let { index ->
      val clip = timeline.overlayClips[index]
      if (clip.isLocked || isTrackLocked(Kind.OVERLAY.trackType, clip.trackIndex)) return null
      if (!isInsideSplitRange(atMs, clip.timelineStartMs, clip.durationMs)) return null
      val (head, tail) = splitVideoUnchecked(clip, atMs, existingIds)
      val list = timeline.overlayClips.toMutableList().apply { this[index] = head; add(index + 1, tail) }
      return TimelineSplit(timeline.copy(overlayClips = list), head.id, tail.id, SelectedTrackElement.Overlay(tail.id))
    }
    timeline.audioClips.indexOfFirst { it.id == clipId }.takeIf { it >= 0 }?.let { index ->
      val clip = timeline.audioClips[index]
      if (clip.isLocked || isTrackLocked(Kind.AUDIO.trackType, clip.trackIndex)) return null
      if (!isInsideSplitRange(atMs, clip.timelineStartMs, clip.durationMs)) return null
      val (head, tail) = splitAudioUnchecked(clip, atMs, existingIds)
      val list = timeline.audioClips.toMutableList().apply { this[index] = head; add(index + 1, tail) }
      return TimelineSplit(timeline.copy(audioClips = list), head.id, tail.id, SelectedTrackElement.Audio(tail.id))
    }
    timeline.textClips.indexOfFirst { it.id == clipId }.takeIf { it >= 0 }?.let { index ->
      val clip = timeline.textClips[index]
      if (clip.isLocked || isTrackLocked(Kind.TEXT.trackType, clip.trackIndex)) return null
      if (!isInsideSplitRange(atMs, clip.timelineStartMs, clip.durationMs)) return null
      val (head, tail) = splitTextUnchecked(clip, atMs, existingIds)
      val list = timeline.textClips.toMutableList().apply { this[index] = head; add(index + 1, tail) }
      return TimelineSplit(timeline.copy(textClips = list), head.id, tail.id, SelectedTrackElement.Text(tail.id))
    }
    timeline.stickerClips.indexOfFirst { it.id == clipId }.takeIf { it >= 0 }?.let { index ->
      val clip = timeline.stickerClips[index]
      if (clip.isLocked || isTrackLocked(Kind.STICKER.trackType, clip.trackIndex)) return null
      if (!isInsideSplitRange(atMs, clip.timelineStartMs, clip.durationMs)) return null
      val (head, tail) = splitStickerUnchecked(clip, atMs, existingIds)
      val list = timeline.stickerClips.toMutableList().apply { this[index] = head; add(index + 1, tail) }
      return TimelineSplit(timeline.copy(stickerClips = list), head.id, tail.id, SelectedTrackElement.Sticker(tail.id))
    }
    timeline.effectClips.indexOfFirst { it.id == clipId }.takeIf { it >= 0 }?.let { index ->
      val clip = timeline.effectClips[index]
      if (clip.isLocked || isTrackLocked(Kind.EFFECT.trackType, clip.trackIndex)) return null
      if (!isInsideSplitRange(atMs, clip.timelineStartMs, clip.durationMs)) return null
      val (head, tail) = splitEffectUnchecked(clip, atMs, existingIds)
      val list = timeline.effectClips.toMutableList().apply { this[index] = head; add(index + 1, tail) }
      return TimelineSplit(timeline.copy(effectClips = list), head.id, tail.id, SelectedTrackElement.Effect(tail.id))
    }
    timeline.shapeClips.indexOfFirst { it.id == clipId }.takeIf { it >= 0 }?.let { index ->
      val clip = timeline.shapeClips[index]
      if (clip.isLocked || isTrackLocked(Kind.SHAPE.trackType, clip.trackIndex)) return null
      if (!isInsideSplitRange(atMs, clip.timelineStartMs, clip.durationMs)) return null
      val (head, tail) = splitShapeUnchecked(clip, atMs, existingIds)
      val list = timeline.shapeClips.toMutableList().apply { this[index] = head; add(index + 1, tail) }
      return TimelineSplit(timeline.copy(shapeClips = list), head.id, tail.id, SelectedTrackElement.None)
    }
    return null
  }

  /** Ids of every clip (all lanes of all tracks) that the playhead strictly crosses. */
  fun clipIdsCrossing(timeline: Timeline, atMs: Long): List<String> = buildList {
    timeline.videoClips.forEach { if (isInsideSplitRange(atMs, it.timelineStartMs, it.durationMs)) add(it.id) }
    timeline.overlayClips.forEach { if (isInsideSplitRange(atMs, it.timelineStartMs, it.durationMs)) add(it.id) }
    timeline.audioClips.forEach { if (isInsideSplitRange(atMs, it.timelineStartMs, it.durationMs)) add(it.id) }
    timeline.textClips.forEach { if (isInsideSplitRange(atMs, it.timelineStartMs, it.durationMs)) add(it.id) }
    timeline.stickerClips.forEach { if (isInsideSplitRange(atMs, it.timelineStartMs, it.durationMs)) add(it.id) }
    timeline.effectClips.forEach { if (isInsideSplitRange(atMs, it.timelineStartMs, it.durationMs)) add(it.id) }
    timeline.shapeClips.forEach { if (isInsideSplitRange(atMs, it.timelineStartMs, it.durationMs)) add(it.id) }
  }

  /**
   * Identifies the clip a "split at playhead" should act on. Priority: the explicitly selected
   * clip when the playhead crosses it, then the main video clip, then overlay, audio, text,
   * sticker and effect clips under the playhead.
   */
  fun findTargetClipAtTimestamp(
    timeline: Timeline,
    playheadMs: Long,
    selectedElement: SelectedTrackElement = SelectedTrackElement.None
  ): Pair<SelectedTrackElement, String?> {
    fun crosses(start: Long, duration: Long) = isInsideSplitRange(playheadMs, start, duration)

    when (selectedElement) {
      is SelectedTrackElement.Video -> timeline.videoClips.find { it.id == selectedElement.clipId }
        ?.takeIf { crosses(it.timelineStartMs, it.durationMs) }?.let { return selectedElement to it.id }
      is SelectedTrackElement.Overlay -> timeline.overlayClips.find { it.id == selectedElement.clipId }
        ?.takeIf { crosses(it.timelineStartMs, it.durationMs) }?.let { return selectedElement to it.id }
      is SelectedTrackElement.Audio -> timeline.audioClips.find { it.id == selectedElement.clipId }
        ?.takeIf { crosses(it.timelineStartMs, it.durationMs) }?.let { return selectedElement to it.id }
      is SelectedTrackElement.Text -> timeline.textClips.find { it.id == selectedElement.clipId }
        ?.takeIf { crosses(it.timelineStartMs, it.durationMs) }?.let { return selectedElement to it.id }
      is SelectedTrackElement.Sticker -> timeline.stickerClips.find { it.id == selectedElement.clipId }
        ?.takeIf { crosses(it.timelineStartMs, it.durationMs) }?.let { return selectedElement to it.id }
      is SelectedTrackElement.Effect -> timeline.effectClips.find { it.id == selectedElement.clipId }
        ?.takeIf { crosses(it.timelineStartMs, it.durationMs) }?.let { return selectedElement to it.id }
      SelectedTrackElement.None -> Unit
    }

    timeline.videoClips.find { crosses(it.timelineStartMs, it.durationMs) }?.let { return SelectedTrackElement.Video(it.id) to it.id }
    timeline.overlayClips.find { crosses(it.timelineStartMs, it.durationMs) }?.let { return SelectedTrackElement.Overlay(it.id) to it.id }
    timeline.audioClips.find { crosses(it.timelineStartMs, it.durationMs) }?.let { return SelectedTrackElement.Audio(it.id) to it.id }
    timeline.textClips.find { crosses(it.timelineStartMs, it.durationMs) }?.let { return SelectedTrackElement.Text(it.id) to it.id }
    timeline.stickerClips.find { crosses(it.timelineStartMs, it.durationMs) }?.let { return SelectedTrackElement.Sticker(it.id) to it.id }
    timeline.effectClips.find { crosses(it.timelineStartMs, it.durationMs) }?.let { return SelectedTrackElement.Effect(it.id) to it.id }
    return SelectedTrackElement.None to null
  }
}
