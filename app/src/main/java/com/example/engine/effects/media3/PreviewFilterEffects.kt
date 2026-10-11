package com.example.engine.effects.media3

import android.graphics.Bitmap
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import com.example.domain.model.FilterSettings
import com.example.domain.model.FilterType
import com.example.domain.model.Timeline
import com.example.domain.model.VideoAdjustments
import com.example.domain.model.VideoClip
import com.example.domain.model.effectiveAdjustments
import com.example.engine.color.ColorEngineHost

/**
 * Builds the live-preview Media3 look for spatial Adjust / Video Quality, LUT and catalog VFX.
 *
 * Color-matrix work (brightness, highlights, whites, artistic filters, Auto Enhance, HDR, …)
 * is painted onto the live TextureView through [PreviewDisplayFilter] using the same
 * [com.example.engine.composition.ColorFilterGenerator] matrices as export. Those parameters
 * are intentionally omitted from [effectsFor] so a slider tick cannot rebuild ExoPlayer's
 * video graph (ANR / frozen frame / silent audio).
 *
 * Neighbor-pixel operations (vignette, grain, sharpness, clarity, denoise, anti-flicker)
 * still go through Media3, but callers must coalesce them with [PreviewLookPolicy.LOOK_DEBOUNCE_MS].
 */
@OptIn(UnstableApi::class)
object PreviewFilterEffects {

  fun effectiveFilter(clip: VideoClip?, timeline: Timeline): FilterSettings =
    clip?.filter ?: timeline.filter

  fun lutLook(clip: VideoClip?): Pair<String, Float>? {
    val grade = ColorEngineHost.decodeOrDefault(clip?.colorGradeJson)
    val lut = grade.lut?.takeIf { it.isValid() } ?: return null
    return lut.lutId to lut.intensity.coerceIn(0f, 1f)
  }

  fun effectsFor(
    clip: VideoClip?,
    timeline: Timeline,
    lutBitmap: Bitmap? = null,
    lutIntensity: Float = 1f
  ): List<Effect> =
    Media3EffectPipeline.buildRealtimePreviewEffects(
      adjustments = PreviewLookPolicy.spatialAdjustments(clip.effectiveAdjustments(timeline)),
      // Color-matrix filters / sliders are painted onto the TextureView.
      filterSettings = FilterSettings(),
      customLut = lutBitmap,
      customLutIntensity = lutIntensity
    ) + PreviewVfxEffects.effectsFor(clip)

  /**
   * Signature of the ExoPlayer effect list only. Artistic filters and color-matrix sliders
   * must not be included: a brightness tick would otherwise call setVideoEffects and ANR.
   */
  fun media3Signature(clip: VideoClip?, timeline: Timeline): String {
    val lut = lutLook(clip)
    return signature(
      PreviewLookPolicy.spatialAdjustments(clip.effectiveAdjustments(timeline)),
      FilterSettings(),
      lutId = lut?.first,
      lutIntensity = lut?.second ?: 0f
    ) + "|" + PreviewVfxEffects.signature(clip)
  }

  fun signature(clip: VideoClip?, timeline: Timeline): String {
    val lut = lutLook(clip)
    return signature(
      clip.effectiveAdjustments(timeline),
      effectiveFilter(clip, timeline),
      lutId = lut?.first,
      lutIntensity = lut?.second ?: 0f
    ) + "|" + PreviewVfxEffects.signature(clip)
  }

  fun signature(
    adjustments: VideoAdjustments,
    filter: FilterSettings,
    lutId: String? = null,
    lutIntensity: Float = 0f
  ): String {
    return buildString {
      append(filter.type.name)
      append('|')
      append(filter.intensity)
      append('|')
      append(lutId.orEmpty())
      append('@')
      append(lutIntensity)
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
    filter.enabled && filter.type != FilterType.NONE && filter.intensity > 0.01f
}
