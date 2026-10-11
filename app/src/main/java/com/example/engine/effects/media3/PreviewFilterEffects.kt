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
 * Builds the live-preview Media3 look for Filters / Adjust / Video Quality, LUT and catalog VFX.
 *
 * Color and spatial parameters share [ColorGradingGlEffect]. Slider ticks publish a
 * [ColorGradingSnapshot] onto the already-bound effect; [media3GraphSignature] stays
 * stable so [androidx.media3.exoplayer.ExoPlayer.setVideoEffects] is not called again
 * (that rebuild dropped the TextureView, produced black frames and stopped source audio).
 *
 * Thumbnails and still images still use [PreviewDisplayFilter] / ColorFilterGenerator.
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
      adjustments = clip.effectiveAdjustments(timeline),
      filterSettings = effectiveFilter(clip, timeline),
      customLut = lutBitmap,
      customLutIntensity = lutIntensity
    ) + PreviewVfxEffects.effectsFor(clip)

  /**
   * Structural Media3 graph identity. Value-only look edits must not change this
   * or [androidx.media3.exoplayer.ExoPlayer.setVideoEffects] will rebuild the decoder.
   */
  fun media3GraphSignature(clip: VideoClip?, timeline: Timeline): String =
    PreviewEffectApplyPolicy.graphSignature(clip, timeline)

  /**
   * Full value signature of the live look (filters + every adjustment + LUT + VFX).
   * Used for logging and paused-frame refresh, not for setVideoEffects.
   */
  fun media3Signature(clip: VideoClip?, timeline: Timeline): String {
    val lut = lutLook(clip)
    return signature(
      clip.effectiveAdjustments(timeline),
      effectiveFilter(clip, timeline),
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
