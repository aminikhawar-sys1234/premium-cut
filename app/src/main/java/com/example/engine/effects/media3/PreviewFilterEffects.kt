package com.example.engine.effects.media3

import androidx.media3.common.Effect
import com.example.domain.model.FilterSettings
import com.example.domain.model.FilterType
import com.example.domain.model.Timeline
import com.example.domain.model.VideoAdjustments
import com.example.domain.model.VideoClip
import com.example.domain.model.effectiveAdjustments

/**
 * Builds the live-preview Media3 look for Filters tools and Effects tools.
 * Color grading matches export; catalog `vfx:` keys are appended via [PreviewVfxEffects].
 */
object PreviewFilterEffects {

  fun effectiveFilter(clip: VideoClip?, timeline: Timeline): FilterSettings =
    clip?.filter ?: timeline.filter

  fun effectsFor(clip: VideoClip?, timeline: Timeline): List<Effect> =
    Media3EffectPipeline.buildRealtimePreviewEffects(
      adjustments = clip.effectiveAdjustments(timeline),
      filterSettings = effectiveFilter(clip, timeline)
    ) + PreviewVfxEffects.effectsFor(clip)

  fun signature(clip: VideoClip?, timeline: Timeline): String =
    signature(clip.effectiveAdjustments(timeline), effectiveFilter(clip, timeline)) +
      "|" + PreviewVfxEffects.signature(clip)

  fun signature(adjustments: VideoAdjustments, filter: FilterSettings): String {
    return buildString {
      append(filter.type.name)
      append('|')
      append(filter.intensity)
      append('|')
      append(adjustments.brightness)
      append(',')
      append(adjustments.contrast)
      append(',')
      append(adjustments.saturation)
      append(',')
      append(adjustments.exposure)
      append(',')
      append(adjustments.temperature)
      append(',')
      append(adjustments.tint)
      append(',')
      append(adjustments.highlights)
      append(',')
      append(adjustments.shadows)
      append(',')
      append(adjustments.whites)
      append(',')
      append(adjustments.blacks)
      append(',')
      append(adjustments.vignette)
      append(',')
      append(adjustments.grain)
      append(',')
      append(adjustments.sharpness)
      append(',')
      append(adjustments.clarity)
      append(',')
      append(adjustments.fade)
      append(',')
      append(adjustments.autoEnhance)
      append(',')
      append(adjustments.hdrBoost)
      append(',')
      append(adjustments.colorCorrect)
      append(',')
      append(adjustments.superClarity)
      append(',')
      append(adjustments.colorFix)
      append(',')
      append(adjustments.denoise)
      append(',')
      append(adjustments.antiFlicker)
    }
  }

  fun isActiveLook(filter: FilterSettings): Boolean =
    filter.type != FilterType.NONE && filter.intensity > 0.01f
}
