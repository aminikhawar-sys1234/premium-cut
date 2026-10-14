package com.example.engine.effects

import com.example.domain.model.VideoAdjustments
import java.util.Locale
import kotlin.math.abs

/**
 * Shared ranges and defaults for Adjust sliders. Preview and export read the
 * same [VideoAdjustments] fields; this object only documents valid UI ranges.
 *
 * The rendering-side ranges and constants live in [ColorLookModel]; `ColorLookModelRangesTest`
 * asserts the two stay in sync, so a slider can never offer a value the shader clamps away.
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

  /**
   * Neutral values for the Adjust tab.
   *
   * This also clears the three Smart Auto values (Auto Enhance, Color Fixings, Color Correct)
   * because their sliders live in the Adjust tab — resetting Adjust used to leave them applied,
   * so the preview kept a look the panel claimed to have cleared.
   * Video Quality-only controls (Super Clarity, HDR Boost, Denoise, Anti-Flicker) are cleared by
   * [resetQuality], which the Video Quality tab's Reset calls.
   */
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
    grain = grain.default,
    // Smart Auto: reachable from the Adjust tab, so Adjust-Reset must clear them too.
    autoEnhance = 0f,
    colorFix = 0f,
    colorCorrect = 0f
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
