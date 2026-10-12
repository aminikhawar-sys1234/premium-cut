package com.example.engine.effects

import com.example.domain.model.VideoAdjustments
import java.util.Locale
import kotlin.math.abs

/**
 * Shared ranges and defaults for Adjust sliders. Preview and export read the
 * same [VideoAdjustments] fields; this object only documents valid UI ranges.
 */
object ColorAdjustmentRanges {
  data class Spec(
    val min: Float,
    val max: Float,
    val default: Float,
    val step: Float,
    val label: String
  ) {
    fun coerce(value: Float): Float = value.coerceIn(min, max)
    fun isDefault(value: Float): Boolean = abs(value - default) <= step
    fun format(value: Float): String {
      val shown = coerce(value)
      return if (abs(shown) < 0.005f && default == 0f) "0.00"
      else String.format(Locale.US, "%.2f", shown)
    }
  }

  val brightness = Spec(-1f, 1f, 0f, 0.01f, "Brightness")
  val contrast = Spec(0.10f, 2f, 1f, 0.01f, "Contrast")
  val saturation = Spec(0f, 2f, 1f, 0.01f, "Saturation")
  val exposure = Spec(-1f, 1f, 0f, 0.01f, "Exposure")
  val temperature = Spec(-1f, 1f, 0f, 0.01f, "Temperature")
  val tint = Spec(-1f, 1f, 0f, 0.01f, "Tint")
  val highlights = Spec(-1f, 1f, 0f, 0.01f, "Highlights")
  val shadows = Spec(-1f, 1f, 0f, 0.01f, "Shadows")
  val sharpness = Spec(0f, 1f, 0f, 0.01f, "Sharpening")
  val whites = Spec(-1f, 1f, 0f, 0.01f, "Whites")
  val blacks = Spec(-1f, 1f, 0f, 0.01f, "Blacks")
  val clarity = Spec(0f, 1f, 0f, 0.01f, "Clarity")
  val fade = Spec(0f, 1f, 0f, 0.01f, "Fade")
  val vignette = Spec(0f, 1f, 0f, 0.01f, "Vignette")
  val grain = Spec(0f, 1f, 0f, 0.01f, "Grain")

  val primary: List<Spec> = listOf(
    brightness, contrast, saturation, temperature, tint, highlights, shadows, exposure, sharpness
  )

  fun resetPrimary(src: VideoAdjustments): VideoAdjustments = src.copy(
    brightness = brightness.default,
    contrast = contrast.default,
    saturation = saturation.default,
    temperature = temperature.default,
    tint = tint.default,
    highlights = highlights.default,
    shadows = shadows.default,
    exposure = exposure.default,
    sharpness = sharpness.default,
    whites = whites.default,
    blacks = blacks.default,
    clarity = clarity.default,
    fade = fade.default,
    vignette = vignette.default,
    grain = grain.default
  )

  fun resetQuality(src: VideoAdjustments): VideoAdjustments = src.copy(
    autoEnhance = 0f,
    superClarity = 0f,
    colorCorrect = 0f,
    denoise = 0f,
    hdrBoost = 0f,
    antiFlicker = 0f,
    colorFix = 0f
  )
}
