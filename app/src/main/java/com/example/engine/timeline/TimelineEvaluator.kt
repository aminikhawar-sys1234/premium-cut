package com.example.engine.timeline

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import com.example.domain.model.*
import com.example.engine.KeyframeInterpolator
import com.example.engine.composition.*
import com.example.engine.composition.ColorFilterGenerator
import com.example.engine.composition.VideoEffectRenderer
import com.example.engine.controller.PreviewMixPolicy
import com.example.engine.text.TextLayerRenderer

/**
 * Authoritative Timeline Evaluator.
 * Given an authoritative Timeline and timestamp, calculates the exact frame state.
 * Both Preview and Export must use this EXACT evaluator to guarantee preview/export parity.
 */
class TimelineEvaluator {

  fun evaluate(timeline: Timeline, timeMs: Long): TimelineFrameState {
    return com.example.engine.ai.TrackingEvalContext.withTimeline(timeline) {
      evaluateWithTrackingContext(timeline, timeMs)
    }
  }

  private fun evaluateWithTrackingContext(timeline: Timeline, timeMs: Long): TimelineFrameState {
    val posMs = timeMs.coerceAtLeast(0L)

    val isVideoHidden = timeline.trackSettings[TrackType.MAIN_VIDEO]?.isHidden == true
    val isOverlayHidden = timeline.trackSettings[TrackType.OVERLAY]?.isHidden == true
    val isTextHidden = timeline.trackSettings[TrackType.TEXT]?.isHidden == true
    val isStickerHidden = timeline.trackSettings[TrackType.STICKER]?.isHidden == true
    val isEffectHidden = timeline.trackSettings[TrackType.EFFECT]?.isHidden == true

    // 1. Main Video Clip
    val activeClip = if (!isVideoHidden) {
      timeline.videoClips.find {
        !it.isHidden && TimelineClipVisibility.isActiveAt(posMs, it.timelineStartMs, it.durationMs)
      }
    } else null

    val sourcePosMs = activeClip?.timelineToSourceMs(posMs) ?: 0L

    // 2. Active Transitions
    var activeTransition: ComposedTransition? = null
    if (!isVideoHidden) {
      for (tr in timeline.transitions) {
        if (tr.clipIndexBefore >= 0 && tr.clipIndexBefore < timeline.videoClips.size - 1) {
          val clipBefore = timeline.videoClips[tr.clipIndexBefore]
          val clipAfter = timeline.videoClips[tr.clipIndexBefore + 1]
          val transitionStart = clipBefore.timelineStartMs + clipBefore.durationMs - (tr.durationMs / 2)
          val transitionEnd = transitionStart + tr.durationMs

          if (posMs in transitionStart until transitionEnd) {
            val progress = ((posMs - transitionStart).toFloat() / tr.durationMs).coerceIn(0f, 1f)
            activeTransition = ComposedTransition(
              type = tr.type,
              progress = progress,
              clipBefore = clipBefore,
              clipAfter = clipAfter
            )
            break
          }
        }
      }
    }

    // 3. Active Overlays (Multi-Track, sorted by trackIndex & timelineStartMs)
    val overlays = if (!isOverlayHidden) {
      timeline.overlayClips.filter {
        !it.isHidden && TimelineClipVisibility.isActiveAt(posMs, it.timelineStartMs, it.durationMs)
      }.sortedWith(compareBy({ it.trackIndex }, { it.timelineStartMs })).map { clip ->
        val rel = posMs - clip.timelineStartMs
        val kf = KeyframeInterpolator.interpolate(clip, rel)
        ComposedOverlay(
          clip = clip,
          sourcePosMs = clip.timelineToSourceMs(posMs),
          posX = kf.posX,
          posY = kf.posY,
          scaleX = kf.scaleX,
          scaleY = kf.scaleY,
          rotation = kf.rotation,
          opacity = kf.opacity,
          blendMode = clip.blendMode,
          blur = kf.blur,
          brightness = kf.brightness,
          contrast = kf.contrast,
          saturation = kf.saturation,
          effectParam = kf.effectParam
        )
      }
    } else emptyList()

    // 4. Active Text Layers
    val texts = if (!isTextHidden) {
      timeline.textClips.filter {
        !it.isHidden && TimelineClipVisibility.isActiveAt(posMs, it.timelineStartMs, it.durationMs)
      }.sortedWith(compareBy({ it.trackIndex }, { it.timelineStartMs })).map { clip ->
        val relMs = posMs - clip.timelineStartMs
        val kf = KeyframeInterpolator.interpolate(clip, relMs)
        val effectiveClip = if (clip.keyframes.isNotEmpty() || !clip.trackBindJson.isNullOrBlank()) {
          clip.copy(
            posX = kf.posX,
            posY = kf.posY,
            scale = kf.scale,
            rotation = kf.rotation,
            opacity = kf.opacity,
            trackBindJson = null,
            keyframes = emptyList()
          )
        } else clip
        val state = TextLayerRenderer.evaluateAnimation(effectiveClip, posMs)
        ComposedText(
          clip = effectiveClip,
          posX = state.posX,
          posY = state.posY,
          scale = state.scale,
          rotation = state.rotation,
          opacity = state.opacity,
          currentPosMs = posMs
        )
      }
    } else emptyList()

    // 5. Active Stickers
    val stickers = if (!isStickerHidden) {
      timeline.stickerClips.filter {
        !it.isHidden && TimelineClipVisibility.isActiveAt(posMs, it.timelineStartMs, it.durationMs)
      }.map { clip ->
        val state = StickerLayerRenderer.evaluateAnimation(clip, posMs)
        ComposedSticker(
          clip = clip,
          posX = state.posX,
          posY = state.posY,
          scale = state.scale,
          rotation = state.rotation,
          opacity = state.opacity
        )
      }
    } else emptyList()

    // 6. Active Effects (Clip-local vs adjustment layer)
    val activeEffects = if (!isEffectHidden) {
      timeline.effectClips.filter { clip ->
        !clip.isHidden && when {
          clip.targetClipId != null -> activeClip != null && activeClip.id == clip.targetClipId &&
            posMs >= activeClip.timelineStartMs &&
            posMs < activeClip.timelineStartMs + activeClip.durationMs
          else -> posMs >= clip.timelineStartMs && posMs < clip.timelineStartMs + clip.durationMs
        }
      }.sortedBy { it.timelineStartMs }.map { clip ->
        val relTime = (posMs - clip.timelineStartMs).coerceAtLeast(0L)
        val intensity = KeyframeInterpolator.interpolateEffectIntensity(clip, relTime)
        ComposedEffect(
          clip = clip,
          effectType = clip.effectType,
          intensity = intensity,
          timeInEffectMs = relTime,
          progress = (relTime.toFloat() / clip.durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
        )
      }
    } else emptyList()

    // 7. Active Audio Clips (track mute/solo — hide does not silence, matching export mix)
    val hasClipSolo = timeline.audioClips.any { it.isSolo }
    val activeAudios = timeline.audioClips.filter { clip ->
      !clip.isHidden &&
        PreviewMixPolicy.audioClipGain(clip, timeline, hasClipSolo) > 0f &&
        posMs >= clip.timelineStartMs && posMs < clip.timelineStartMs + clip.durationMs
    }

    // 8. Adjustments & Color Filters
    val baseMatrix = ColorFilterGenerator.createCombinedMatrix(activeClip.effectiveAdjustments(timeline), FilterSettings(), activeClip?.filter)
    val colorMatrix = ColorMatrix(baseMatrix)
    if (activeEffects.isNotEmpty()) {
      val effectMat = VideoEffectRenderer.calculateEffectColorMatrix(activeEffects.map { it.clip }, posMs)
      if (effectMat != null) {
        colorMatrix.postConcat(effectMat)
      }
    }
    val colorFilter = ColorMatrixColorFilter(colorMatrix)

    // 9. Keyframe Transform for Main Clip
    val activeClipTransform = if (activeClip != null) {
      val rel = posMs - activeClip.timelineStartMs
      KeyframeInterpolator.interpolate(activeClip, rel)
    } else null

    return TimelineFrameState(
      timestampMs = posMs,
      activeVideoClip = activeClip,
      clipSourcePosMs = sourcePosMs,
      activeOverlays = overlays,
      activeTexts = texts,
      activeStickers = stickers,
      activeEffects = activeEffects,
      activeAudios = activeAudios,
      activeTransition = activeTransition,
      activeClipTransform = activeClipTransform,
      colorMatrix = colorMatrix,
      colorFilter = colorFilter,
      activeMasks = activeClip?.let { clip ->
        mapOf(
          clip.id to com.example.engine.ai.OverlayTrackCodec.applyToMask(
            clip.mask,
            clip.motionTrackJson,
            sourcePosMs * 1000L
          )
        )
      } ?: emptyMap(),
      trackVisibility = timeline.trackSettings.mapValues { !it.value.isHidden },
      trackMute = timeline.trackSettings.mapValues { it.value.isMuted },
      trackSolo = timeline.trackSettings.mapValues { it.value.isSolo },
      zOrder = overlays.map { it.clip.trackIndex }.distinct()
    )
  }
}
