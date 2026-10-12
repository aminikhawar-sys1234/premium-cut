package com.example.engine.timeline

import com.example.domain.model.AudioClip
import com.example.domain.model.Timeline
import com.example.domain.model.TrackType
import com.example.domain.model.VideoClip
import com.example.domain.model.timelineToSourceMs
import com.example.engine.AudioSyncInfo
import com.example.engine.ClipOverlapInfo
import com.example.engine.ClipTrimmedRange
import com.example.engine.KeyframeInterpolator
import com.example.engine.TimelineActiveSnapshot
import com.example.engine.TransitionTimingInfo

/**
 * Read-only timeline queries used by the editor, preview compositor and export.
 * Kept free of engine state so split/trim/sync tests can assert against the same math.
 */
object TimelineClipQueries {

  fun getActiveClipsAt(timeline: Timeline, posMs: Long, fps: Int): TimelineActiveSnapshot {
    val isVideoHidden = timeline.trackSettings[TrackType.MAIN_VIDEO]?.isHidden == true
    val isOverlayHidden = timeline.trackSettings[TrackType.OVERLAY]?.isHidden == true
    val isAudioHidden = timeline.trackSettings[TrackType.AUDIO]?.isHidden == true
    val isTextHidden = timeline.trackSettings[TrackType.TEXT]?.isHidden == true
    val isStickerHidden = timeline.trackSettings[TrackType.STICKER]?.isHidden == true
    val isEffectHidden = timeline.trackSettings[TrackType.EFFECT]?.isHidden == true

    val activeVideo = if (!isVideoHidden) {
      timeline.videoClips.find { !it.isHidden && TimelineClipVisibility.isActiveAt(posMs, it.timelineStartMs, it.durationMs) }
    } else null

    val activeOverlays = if (!isOverlayHidden) {
      timeline.overlayClips.filter { !it.isHidden && TimelineClipVisibility.isActiveAt(posMs, it.timelineStartMs, it.durationMs) }
        .sortedBy { it.trackIndex }
    } else emptyList()

    val activeAudios = if (!isAudioHidden) {
      timeline.audioClips.filter { !it.isHidden && TimelineClipVisibility.isActiveAt(posMs, it.timelineStartMs, it.durationMs) }
        .sortedBy { it.trackIndex }
    } else emptyList()

    val activeTexts = if (!isTextHidden) {
      timeline.textClips.filter { !it.isHidden && TimelineClipVisibility.isActiveAt(posMs, it.timelineStartMs, it.durationMs) }
        .sortedBy { it.trackIndex }
    } else emptyList()

    val activeStickers = if (!isStickerHidden) {
      timeline.stickerClips.filter { !it.isHidden && TimelineClipVisibility.isActiveAt(posMs, it.timelineStartMs, it.durationMs) }
    } else emptyList()

    val activeEffects = if (!isEffectHidden) {
      timeline.effectClips.filter { clip ->
        !clip.isHidden && when {
          clip.targetClipId != null -> activeVideo != null && activeVideo.id == clip.targetClipId &&
            TimelineClipVisibility.isActiveAt(posMs, activeVideo.timelineStartMs, activeVideo.durationMs)
          else -> TimelineClipVisibility.isActiveAt(posMs, clip.timelineStartMs, clip.durationMs)
        }
      }
    } else emptyList()

    var activeTransition = null as com.example.domain.model.Transition?
    var transitionProgress = 0f
    var transitionClipBefore: VideoClip? = null
    var transitionClipAfter: VideoClip? = null
    if (!isVideoHidden) {
      val trInfo = getTransitionTimingAt(timeline, posMs)
      if (trInfo != null) {
        activeTransition = trInfo.transition
        transitionProgress = trInfo.progress
        transitionClipBefore = trInfo.clipBefore
        transitionClipAfter = trInfo.clipAfter
      }
    }

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
      frameIndex = TimelineFrameMath.timeToFrame(posMs, fps),
      fps = fps,
      activeOverlaps = getOverlapsAt(timeline, posMs),
      activeAudioSyncInfo = getActiveAudioSyncInfoAt(timeline, posMs)
    )
  }

  fun getClipStartEnd(timeline: Timeline, clipId: String): Pair<Long, Long>? {
    timeline.videoClips.find { it.id == clipId }?.let { return Pair(it.timelineStartMs, it.timelineStartMs + it.durationMs) }
    timeline.overlayClips.find { it.id == clipId }?.let { return Pair(it.timelineStartMs, it.timelineStartMs + it.durationMs) }
    timeline.audioClips.find { it.id == clipId }?.let { return Pair(it.timelineStartMs, it.timelineStartMs + it.durationMs) }
    timeline.textClips.find { it.id == clipId }?.let { return Pair(it.timelineStartMs, it.timelineStartMs + it.durationMs) }
    timeline.stickerClips.find { it.id == clipId }?.let { return Pair(it.timelineStartMs, it.timelineStartMs + it.durationMs) }
    timeline.effectClips.find { it.id == clipId }?.let { return Pair(it.timelineStartMs, it.timelineStartMs + it.durationMs) }
    timeline.shapeClips.find { it.id == clipId }?.let { return Pair(it.timelineStartMs, it.timelineStartMs + it.durationMs) }
    return null
  }

  fun getClipTrimmedRange(timeline: Timeline, clipId: String): ClipTrimmedRange? {
    timeline.videoClips.find { it.id == clipId }?.let {
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
    timeline.overlayClips.find { it.id == clipId }?.let {
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
    timeline.audioClips.find { it.id == clipId }?.let {
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

  fun getTrackOverlaps(timeline: Timeline, trackType: TrackType, trackIndex: Int = 0): List<ClipOverlapInfo> {
    val clips: List<Pair<String, Pair<Long, Long>>> = when (trackType) {
      TrackType.MAIN_VIDEO -> timeline.videoClips.map { it.id to (it.timelineStartMs to (it.timelineStartMs + it.durationMs)) }
      TrackType.OVERLAY, TrackType.ADJUSTMENT, TrackType.ELEMENT ->
        timeline.overlayClips.filter { it.trackIndex == trackIndex }.map { it.id to (it.timelineStartMs to (it.timelineStartMs + it.durationMs)) }
      TrackType.AUDIO, TrackType.MUSIC, TrackType.SFX ->
        timeline.audioClips.filter { it.trackIndex == trackIndex }.map { it.id to (it.timelineStartMs to (it.timelineStartMs + it.durationMs)) }
      TrackType.TEXT, TrackType.CAPTION ->
        timeline.textClips.filter { it.trackIndex == trackIndex }.map { it.id to (it.timelineStartMs to (it.timelineStartMs + it.durationMs)) }
      TrackType.STICKER -> timeline.stickerClips.map { it.id to (it.timelineStartMs to (it.timelineStartMs + it.durationMs)) }
      TrackType.EFFECT -> timeline.effectClips.map { it.id to (it.timelineStartMs to (it.timelineStartMs + it.durationMs)) }
      TrackType.SHAPE -> timeline.shapeClips.filter { it.trackIndex == trackIndex }.map { it.id to (it.timelineStartMs to (it.timelineStartMs + it.durationMs)) }
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

  fun getAllOverlaps(timeline: Timeline): List<ClipOverlapInfo> {
    val result = mutableListOf<ClipOverlapInfo>()
    result.addAll(getTrackOverlaps(timeline, TrackType.MAIN_VIDEO, 0))
    TimelineTrackCommands.videoTrackIndices(timeline).filter { it > 0 }.forEach {
      result.addAll(getTrackOverlaps(timeline, TrackType.OVERLAY, it))
    }
    TimelineTrackCommands.audioTrackIndices(timeline).forEach {
      result.addAll(getTrackOverlaps(timeline, TrackType.AUDIO, it))
    }
    timeline.textClips.map { it.trackIndex }.distinct().forEach {
      result.addAll(getTrackOverlaps(timeline, TrackType.TEXT, it))
    }
    return result
  }

  fun getOverlapsAt(timeline: Timeline, posMs: Long): List<ClipOverlapInfo> =
    getAllOverlaps(timeline).filter { posMs in it.overlapStartMs until it.overlapEndMs }

  fun hasOverlapOnTrack(
    timeline: Timeline,
    trackType: TrackType,
    trackIndex: Int,
    startMs: Long,
    durationMs: Long,
    ignoreClipId: String? = null
  ): Boolean {
    val endMs = startMs + durationMs
    val clips = when (trackType) {
      TrackType.MAIN_VIDEO -> timeline.videoClips.filter { it.id != ignoreClipId }.map { it.timelineStartMs to (it.timelineStartMs + it.durationMs) }
      TrackType.OVERLAY, TrackType.ADJUSTMENT, TrackType.ELEMENT ->
        timeline.overlayClips.filter { it.trackIndex == trackIndex && it.id != ignoreClipId }.map { it.timelineStartMs to (it.timelineStartMs + it.durationMs) }
      TrackType.AUDIO, TrackType.MUSIC, TrackType.SFX ->
        timeline.audioClips.filter { it.trackIndex == trackIndex && it.id != ignoreClipId }.map { it.timelineStartMs to (it.timelineStartMs + it.durationMs) }
      TrackType.TEXT, TrackType.CAPTION ->
        timeline.textClips.filter { it.trackIndex == trackIndex && it.id != ignoreClipId }.map { it.timelineStartMs to (it.timelineStartMs + it.durationMs) }
      TrackType.STICKER -> timeline.stickerClips.filter { it.id != ignoreClipId }.map { it.timelineStartMs to (it.timelineStartMs + it.durationMs) }
      TrackType.EFFECT -> timeline.effectClips.filter { it.id != ignoreClipId }.map { it.timelineStartMs to (it.timelineStartMs + it.durationMs) }
      TrackType.SHAPE -> timeline.shapeClips.filter { it.trackIndex == trackIndex && it.id != ignoreClipId }.map { it.timelineStartMs to (it.timelineStartMs + it.durationMs) }
    }
    return clips.any { (cStart, cEnd) -> startMs < cEnd && endMs > cStart }
  }

  fun getTransitionTiming(timeline: Timeline, transitionId: String, posMs: Long): TransitionTimingInfo? {
    val tr = timeline.transitions.find { it.id == transitionId } ?: return null
    if (tr.clipIndexBefore < 0 || tr.clipIndexBefore >= timeline.videoClips.size - 1) return null
    val clipBefore = timeline.videoClips[tr.clipIndexBefore]
    val clipAfter = timeline.videoClips[tr.clipIndexBefore + 1]
    val start = clipBefore.timelineStartMs + clipBefore.durationMs - (tr.durationMs / 2)
    val end = start + tr.durationMs
    val progress = ((posMs - start).toFloat() / tr.durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
    return TransitionTimingInfo(tr, start, end, tr.durationMs, progress, clipBefore, clipAfter)
  }

  fun getTransitionTimingAt(timeline: Timeline, posMs: Long): TransitionTimingInfo? {
    for (tr in timeline.transitions) {
      if (tr.clipIndexBefore >= 0 && tr.clipIndexBefore < timeline.videoClips.size - 1) {
        val clipBefore = timeline.videoClips[tr.clipIndexBefore]
        val clipAfter = timeline.videoClips[tr.clipIndexBefore + 1]
        val start = clipBefore.timelineStartMs + clipBefore.durationMs - (tr.durationMs / 2)
        val end = start + tr.durationMs
        if (posMs in start until end) {
          val progress = ((posMs - start).toFloat() / tr.durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
          return TransitionTimingInfo(tr, start, end, tr.durationMs, progress, clipBefore, clipAfter)
        }
      }
    }
    return null
  }

  fun getEffectiveAudioVolumeAt(timeline: Timeline, clip: AudioClip, posMs: Long): Float {
    val trackSettings = timeline.trackSettings[TrackType.AUDIO]
    if (trackSettings?.isMuted == true) return 0f
    val anySolo = timeline.trackSettings.values.any { it.isSolo }
    if (anySolo && trackSettings?.isSolo != true) return 0f
    val relMs = posMs - clip.timelineStartMs
    if (relMs < 0L || relMs > clip.durationMs) return 0f
    var baseVol = if (clip.keyframes.isNotEmpty()) {
      KeyframeInterpolator.interpolateVolume(clip.keyframes, relMs, clip.volume)
    } else {
      clip.volume
    }
    if (clip.fadeInMs > 0L && relMs < clip.fadeInMs) {
      val progress = (relMs.toFloat() / clip.fadeInMs).coerceIn(0f, 1f)
      baseVol *= (progress * progress)
    }
    if (clip.fadeOutMs > 0L && relMs > clip.durationMs - clip.fadeOutMs) {
      val remaining = (clip.durationMs - relMs).coerceAtLeast(0L)
      val progress = (remaining.toFloat() / clip.fadeOutMs).coerceIn(0f, 1f)
      baseVol *= (progress * progress)
    }
    return baseVol.coerceAtLeast(0f)
  }

  fun getVideoClipAudioVolumeAt(timeline: Timeline, clip: VideoClip, posMs: Long): Float {
    if (clip.isMuted || !clip.hasAudio) return 0f
    val trackType = if (clip.trackIndex == 0) TrackType.MAIN_VIDEO else TrackType.OVERLAY
    val trackSettings = timeline.trackSettings[trackType]
    if (trackSettings?.isMuted == true) return 0f
    val anySolo = timeline.trackSettings.values.any { it.isSolo }
    if (anySolo && trackSettings?.isSolo != true) return 0f
    val relMs = posMs - clip.timelineStartMs
    if (relMs < 0L || relMs > clip.durationMs) return 0f
    val baseVol = if (clip.keyframes.isNotEmpty()) {
      KeyframeInterpolator.interpolate(clip, relMs).volume
    } else {
      clip.volume
    }
    return baseVol.coerceAtLeast(0f)
  }

  fun getActiveAudioSyncInfoAt(timeline: Timeline, posMs: Long): List<AudioSyncInfo> {
    val list = mutableListOf<AudioSyncInfo>()
    val isAudioTrackHidden = timeline.trackSettings[TrackType.AUDIO]?.isHidden == true
    if (!isAudioTrackHidden) {
      timeline.audioClips
        .filter { !it.isHidden && TimelineClipVisibility.isActiveAt(posMs, it.timelineStartMs, it.durationMs) }
        .forEach { clip ->
          val relMs = posMs - clip.timelineStartMs
          val sourceMs = clip.sourceStartMs + (relMs * clip.speed).toLong()
          val vol = getEffectiveAudioVolumeAt(timeline, clip, posMs)
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
    val isMainVideoHidden = timeline.trackSettings[TrackType.MAIN_VIDEO]?.isHidden == true
    if (!isMainVideoHidden) {
      timeline.videoClips
        .filter { !it.isHidden && it.hasAudio && !it.isMuted && TimelineClipVisibility.isActiveAt(posMs, it.timelineStartMs, it.durationMs) }
        .forEach { clip ->
          val sourceMs = clip.timelineToSourceMs(posMs)
          val vol = getVideoClipAudioVolumeAt(timeline, clip, posMs)
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
    val isOverlayHidden = timeline.trackSettings[TrackType.OVERLAY]?.isHidden == true
    if (!isOverlayHidden) {
      timeline.overlayClips
        .filter { !it.isHidden && it.hasAudio && !it.isMuted && TimelineClipVisibility.isActiveAt(posMs, it.timelineStartMs, it.durationMs) }
        .forEach { clip ->
          val sourceMs = clip.timelineToSourceMs(posMs)
          val vol = getVideoClipAudioVolumeAt(timeline, clip, posMs)
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
}
