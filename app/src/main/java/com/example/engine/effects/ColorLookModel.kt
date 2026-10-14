package com.example.engine.effects

import com.example.domain.model.FilterSettings
import com.example.domain.model.FilterType
import com.example.domain.model.VideoAdjustments
import kotlin.math.abs
import kotlin.math.pow

/**
 * THE single deterministic definition of an AH Video Studio "look".
 *
 * Before this existed there were three independent copies of the same maths:
 *  - [com.example.engine.effects.media3.ColorGradingGlEffect] (preview GLSL),
 *  - `GpuShaders.VIDEO_FRAGMENT_SHADER` (export GLSL),
 *  - `ColorFilterGenerator.createCombinedMatrix` (thumbnails / stills).
 *
 * They had drifted: Highlights & Shadows used Rec.601 luma and 0.0/0.5/1.0 masks on
 * export but Rec.709 luma and 0.0/0.6, 0.4/1.0 masks on preview, and export sharpened
 * with a gain of 4.0 where the preview used 1.5. That is why the exported file did not
 * look like the preview.
 *
 * This object now owns:
 *  1. every documented parameter range and neutral default,
 *  2. every tunable constant of the colour pipeline,
 *  3. the resolution of [VideoAdjustments] + [FilterSettings] into the exact uniforms that
 *     BOTH shaders receive ([effective]),
 *  4. a CPU reference implementation of the colour stages ([evaluate]) used by tests and by
 *     [com.example.engine.effects.media3.ColorGradingSnapshot.evaluatePreviewPixel].
 *
 * It deliberately has **no Android dependency**: it is plain Kotlin so it can be unit tested
 * on the JVM without Robolectric.
 *
 * ### Documented processing order
 * The preview and the export renderer apply the stages in exactly this order. Anything that
 * changes the order must change it in both places (see `LookShaderContractTest`).
 *
 * ```
 *  1  sharpen          spatial, 4-tap unsharp mask
 *  2  clarity          spatial, wide-radius mid-tone unsharp mask
 *  3  exposure         rgb *= 2^EV   (photographic stops)
 *  4  brightness       rgb += offset (added AFTER exposure)
 *  5  contrast         rgb = (rgb - 0.5) * c + 0.5
 *  6  saturation       rgb = mix(luma, rgb, s)
 *  7  temperature/tint per-channel gains
 *  8  highlights/shadows  luma-masked additive
 *  9  whites/blacks       luma-masked additive
 * 10  autoEnhance / hdrBoost / colorFix / denoise / fade   (Video Quality)
 * 11  glow              spatial highlight bloom
 * 12  filter colour matrix (the artistic preset)
 * 13  vignette          spatial, radial
 * 14  grain             spatial-ish, deterministic hash noise
 * 15  clamp + opaque alpha
 * ```
 *
 * ### Capability note (deliberately honest)
 * Everything here is **SDR Rec.709 / limited-range** colour grading. `hdrBoost` is a
 * local-contrast *look*, not HDR10/HLG/Dolby Vision. `4K` is a micro-contrast look, not an
 * upscaler — true 4K output is an export resolution choice. See [FilterCatalog].
 */
object ColorLookModel {

  // -------------------------------------------------------------------------------------------
  // 1. Parameter ranges and neutral defaults.
  //    Mirrors ColorAdjustmentRanges, which remains the UI-facing copy.
  // -------------------------------------------------------------------------------------------

  data class Range(val min: Float, val max: Float, val neutral: Float) {
    fun coerce(value: Float): Float = value.coerceIn(min, max)
    fun isNeutral(value: Float): Boolean = abs(value - neutral) <= ColorLookModel.NEUTRAL_EPSILON
  }

  /** Tolerance used when deciding whether a parameter is at its neutral value. */
  const val NEUTRAL_EPSILON = 0.001f

  val BRIGHTNESS = Range(-1f, 1f, 0f)
  val CONTRAST = Range(0.10f, 2f, 1f)
  val SATURATION = Range(0f, 2f, 1f)
  val EXPOSURE = Range(-1f, 1f, 0f)
  val TEMPERATURE = Range(-1f, 1f, 0f)
  val TINT = Range(-1f, 1f, 0f)
  val HIGHLIGHTS = Range(-1f, 1f, 0f)
  val SHADOWS = Range(-1f, 1f, 0f)
  val WHITES = Range(-1f, 1f, 0f)
  val BLACKS = Range(-1f, 1f, 0f)
  val SHARPNESS = Range(0f, 1f, 0f)
  val CLARITY = Range(0f, 1f, 0f)
  val FADE = Range(0f, 1f, 0f)
  val VIGNETTE = Range(0f, 1f, 0f)
  val GRAIN = Range(0f, 1f, 0f)
  val AUTO_ENHANCE = Range(0f, 1f, 0f)
  val HDR_BOOST = Range(0f, 1f, 0f)
  val COLOR_FIX = Range(0f, 1f, 0f)
  val DENOISE = Range(0f, 1f, 0f)
  val GLOW = Range(0f, 1f, 0f)

  /** The eight colour adjustments the task guarantees are real and adjustable. */
  val PRIMARY_ADJUSTMENTS: List<Pair<String, Range>> = listOf(
    "brightness" to BRIGHTNESS,
    "contrast" to CONTRAST,
    "saturation" to SATURATION,
    "exposure" to EXPOSURE,
    "temperature" to TEMPERATURE,
    "tint" to TINT,
    "highlights" to HIGHLIGHTS,
    "shadows" to SHADOWS
  )

  // -------------------------------------------------------------------------------------------
  // 2. Shared pipeline constants. Preview GLSL, export GLSL and the CPU replica all read these,
  //    and LookShaderContractTest asserts the two shader sources still contain the same numbers.
  // -------------------------------------------------------------------------------------------

  /** Rec.709 luma. Every luma-weighted stage must use it — export used Rec.601 before. */
  const val LUMA_R = 0.2126f
  const val LUMA_G = 0.7152f
  const val LUMA_B = 0.0722f

  /** Temperature / tint per-channel gains. */
  const val TEMP_R_GAIN = 0.15f
  const val TEMP_B_GAIN = 0.15f
  const val TINT_G_GAIN = 0.12f
  const val TINT_RB_GAIN = 0.08f

  /** Highlight / shadow luma masks and gain. */
  const val SHADOW_EDGE_LOW = 0.0f
  const val SHADOW_EDGE_HIGH = 0.6f
  const val HIGHLIGHT_EDGE_LOW = 0.4f
  const val HIGHLIGHT_EDGE_HIGH = 1.0f
  const val TONE_GAIN = 0.2f

  /** Whites / blacks luma masks and gain. */
  const val WHITE_EDGE_LOW = 0.65f
  const val WHITE_EDGE_HIGH = 1.0f
  const val BLACK_EDGE_LOW = 0.0f
  const val BLACK_EDGE_HIGH = 0.35f
  const val WHITE_BLACK_GAIN = 0.18f

  /** Auto Enhance (Video Quality). */
  const val AUTO_ENHANCE_SAT = 0.20f
  const val AUTO_ENHANCE_CONTRAST = 0.15f
  const val AUTO_ENHANCE_LIFT = 0.04f

  /** HDR Boost / Color Correct (Video Quality). SDR local-contrast look, not HDR10. */
  const val HDR_SAT = 0.25f
  const val HDR_CONTRAST = 0.22f
  const val HDR_LIFT = 0.02f

  /** Color Fix (Video Quality). */
  const val COLOR_FIX_R = 0.04f
  const val COLOR_FIX_G = 0.03f
  const val COLOR_FIX_B = 0.06f

  const val FADE_MIX = 0.55f
  const val DENOISE_MIX = 0.65f

  /** Glow bloom. */
  const val GLOW_RADIUS_TEXELS = 3.5f
  const val GLOW_THRESHOLD = 0.35f
  const val GLOW_GAIN = 1.6f

  /**
   * Sharpening gain. Export used a Laplacian scaled by 4.0 (`c - s * (4*(avg-c))`) which is
   * 2.67x stronger than the preview's 1.5. Both now use [SHARPEN_GAIN] on `(centre - average)`.
   */
  const val SHARPEN_GAIN = 1.5f

  const val CLARITY_RADIUS_TEXELS = 3.0f
  const val CLARITY_GAIN = 1.5f
  const val CLARITY_MID_FLOOR = 0.35f
  const val CLARITY_MID_RANGE = 0.65f

  const val VIGNETTE_INNER = 0.7f
  const val VIGNETTE_OUTER = 1.4f
  const val VIGNETTE_BASE = 0.8f
  const val VIGNETTE_SCALE = 0.8f

  /** Export used 0.30, preview 0.25. Both now use 0.25. */
  const val GRAIN_AMPLITUDE = 0.25f

  /** Below this a stage is skipped entirely (`if (u > 0.01)` in both shaders). */
  const val STAGE_EPSILON = 0.01f

  /** Below this a value counts as "at neutral" for the contrast / saturation guards. */
  const val IDENTITY_EPSILON = 0.001f

  /** Names of the stages, in order. Used by the parity contract test. */
  val STAGE_ORDER: List<String> = listOf(
    "sharpen", "clarity", "exposure", "brightness", "contrast", "saturation",
    "temperature", "tint", "highlights", "shadows", "whites", "blacks",
    "autoEnhance", "hdrBoost", "colorFix", "denoise", "fade", "glow",
    "filterMatrix", "vignette", "grain", "clamp"
  )

  // -------------------------------------------------------------------------------------------
  // 3. Resolution: VideoAdjustments + FilterSettings -> the exact uniforms both shaders get.
  // -------------------------------------------------------------------------------------------

  /**
   * The resolved look. Every field is already clamped to its documented range and already has
   * the filter's spatial extras folded in, so preview and export cannot disagree.
   */
  data class LookParams(
    val brightness: Float = ColorLookModel.BRIGHTNESS.neutral,
    val contrast: Float = ColorLookModel.CONTRAST.neutral,
    val saturation: Float = ColorLookModel.SATURATION.neutral,
    val exposure: Float = ColorLookModel.EXPOSURE.neutral,
    val temperature: Float = ColorLookModel.TEMPERATURE.neutral,
    val tint: Float = ColorLookModel.TINT.neutral,
    val highlights: Float = ColorLookModel.HIGHLIGHTS.neutral,
    val shadows: Float = ColorLookModel.SHADOWS.neutral,
    val whites: Float = ColorLookModel.WHITES.neutral,
    val blacks: Float = ColorLookModel.BLACKS.neutral,
    val fade: Float = ColorLookModel.FADE.neutral,
    val autoEnhance: Float = ColorLookModel.AUTO_ENHANCE.neutral,
    val hdrBoost: Float = ColorLookModel.HDR_BOOST.neutral,
    val colorFix: Float = ColorLookModel.COLOR_FIX.neutral,
    val denoise: Float = ColorLookModel.DENOISE.neutral,
    val glow: Float = ColorLookModel.GLOW.neutral,
    val vignette: Float = ColorLookModel.VIGNETTE.neutral,
    val grain: Float = ColorLookModel.GRAIN.neutral,
    val sharpness: Float = ColorLookModel.SHARPNESS.neutral,
    val clarity: Float = ColorLookModel.CLARITY.neutral,
    /** Column-major GL 4x4 for the artistic preset, or null when no preset is selected. */
    val colorMatrix: FloatArray? = null,
    /** Matching normalised RGBA offset (0..1), or null. */
    val colorOffset: FloatArray? = null
  ) {
    /** True when the look leaves a frame untouched (no stage does anything). */
    fun isNeutral(): Boolean {
      if (colorMatrix != null && colorOffset != null) return false
      return ColorLookModel.BRIGHTNESS.isNeutral(brightness) &&
        ColorLookModel.CONTRAST.isNeutral(contrast) &&
        ColorLookModel.SATURATION.isNeutral(saturation) &&
        ColorLookModel.EXPOSURE.isNeutral(exposure) &&
        ColorLookModel.TEMPERATURE.isNeutral(temperature) &&
        ColorLookModel.TINT.isNeutral(tint) &&
        ColorLookModel.HIGHLIGHTS.isNeutral(highlights) &&
        ColorLookModel.SHADOWS.isNeutral(shadows) &&
        ColorLookModel.WHITES.isNeutral(whites) &&
        ColorLookModel.BLACKS.isNeutral(blacks) &&
        ColorLookModel.FADE.isNeutral(fade) &&
        ColorLookModel.AUTO_ENHANCE.isNeutral(autoEnhance) &&
        ColorLookModel.HDR_BOOST.isNeutral(hdrBoost) &&
        ColorLookModel.COLOR_FIX.isNeutral(colorFix) &&
        ColorLookModel.DENOISE.isNeutral(denoise) &&
        ColorLookModel.GLOW.isNeutral(glow) &&
        ColorLookModel.VIGNETTE.isNeutral(vignette) &&
        ColorLookModel.GRAIN.isNeutral(grain) &&
        ColorLookModel.SHARPNESS.isNeutral(sharpness) &&
        ColorLookModel.CLARITY.isNeutral(clarity)
    }
  }

  /**
   * The artistic preset's 20-element Android colour matrix, or `null` when no preset applies.
   *
   * Preview (`ColorGradingGlEffect.presetMatrixFor`) and export
   * (`GpuCompositionRenderer.bindCommonUniforms`) must both ask this function, otherwise the
   * two paths disagree about *whether* a look is active — the export renderer used to test
   * only `filter.enabled` while the preview also rejected `NONE` and sub-epsilon intensities.
   *
   * Returned as a plain [FloatArray] so the selection rule can be exercised from a JVM unit
   * test without constructing an Android `ColorMatrix`.
   */
  fun presetMatrix(filter: FilterSettings): FloatArray? {
    if (!filter.enabled) return null
    if (filter.type == FilterType.NONE) return null
    if (filter.intensity <= STAGE_EPSILON) return null
    return com.example.engine.composition.ColorFilterGenerator
      .getFilterMatrixArray(filter.type, filter.intensity)
  }

  /** True when two looks push byte-identical uniforms (FloatArray needs contentEquals). */
  fun sameUniforms(a: LookParams?, b: LookParams?): Boolean {
    if (a === b) return true
    if (a == null || b == null) return false
    if (a != b.copy(colorMatrix = a.colorMatrix, colorOffset = a.colorOffset)) return false
    val ma = a.colorMatrix
    val mb = b.colorMatrix
    if ((ma == null) != (mb == null)) return false
    if (ma != null && mb != null && !ma.contentEquals(mb)) return false
    val oa = a.colorOffset
    val ob = b.colorOffset
    if ((oa == null) != (ob == null)) return false
    if (oa != null && ob != null && !oa.contentEquals(ob)) return false
    return true
  }

  /**
   * Resolves the look that must be rendered for one layer.
   *
   * @param filterMatrix  GL column-major 4x4 for the selected preset (already intensity-blended).
   * @param filterOffset  matching normalised offset.
   * @param effectMatrix  optional extra colour matrix from effect clips, concatenated after the
   *                      preset matrix by the caller (export) or by `ColorFilterGenerator` (CPU).
   */
  fun effective(
    adjustments: VideoAdjustments,
    filter: FilterSettings = FilterSettings(),
    filterMatrix: FloatArray? = null,
    filterOffset: FloatArray? = null
  ): LookParams {
    val extras = FilterCatalog.spatialExtras(filter)
    return LookParams(
      brightness = BRIGHTNESS.coerce(adjustments.brightness),
      contrast = CONTRAST.coerce(adjustments.contrast),
      saturation = SATURATION.coerce(adjustments.saturation),
      exposure = EXPOSURE.coerce(adjustments.exposure),
      temperature = TEMPERATURE.coerce(adjustments.temperature),
      tint = TINT.coerce(adjustments.tint),
      highlights = HIGHLIGHTS.coerce(adjustments.highlights),
      shadows = SHADOWS.coerce(adjustments.shadows),
      whites = WHITES.coerce(adjustments.whites),
      blacks = BLACKS.coerce(adjustments.blacks),
      fade = FADE.coerce(adjustments.fade),
      // Video Quality: the Adjust panel, the Video Quality panel and the filter's own
      // spatial extras all feed these three, so they are summed then clamped. Preview and
      // export both go through this function, which is what makes them match.
      autoEnhance = AUTO_ENHANCE.coerce(adjustments.autoEnhance + extras.enhance),
      hdrBoost = HDR_BOOST.coerce(adjustments.hdrBoost + adjustments.colorCorrect + extras.hdrLook),
      colorFix = COLOR_FIX.coerce(adjustments.colorFix),
      denoise = DENOISE.coerce(adjustments.denoise + adjustments.antiFlicker * 0.5f),
      glow = GLOW.coerce(extras.glow),
      vignette = VIGNETTE.coerce(adjustments.vignette),
      grain = GRAIN.coerce(adjustments.grain),
      sharpness = SHARPNESS.coerce(adjustments.sharpness + extras.sharpness),
      clarity = CLARITY.coerce(adjustments.clarity + adjustments.superClarity + extras.clarity),
      colorMatrix = filterMatrix,
      colorOffset = filterOffset
    )
  }

  /**
   * Convenience overload for callers that already hold an Android
   * [android.graphics.ColorMatrix] (the preview builds one from [presetMatrix]). The selection
   * rule is duplicated here on purpose: this overload is only entered with a non-null matrix,
   * and it still re-checks the filter so a disabled / NONE / zero-intensity look can never
   * contribute one.
   */
  fun effectiveWithAndroidMatrix(
    adjustments: VideoAdjustments,
    filter: FilterSettings,
    androidMatrix: android.graphics.ColorMatrix?
  ): LookParams {
    if (androidMatrix == null || !filter.enabled || filter.type == FilterType.NONE ||
      filter.intensity <= STAGE_EPSILON
    ) {
      return effective(adjustments, filter)
    }
    val (m, off) = filterGlMatrix(androidMatrix.array)
    return effective(adjustments, filter, m, off)
  }

  /**
   * Android 4x5 row-major [ColorMatrix] array -> GL column-major 4x4 + normalised offset,
   * with the alpha row forced opaque the way the preview path already did it.
   *
   * Video and decoder textures arrive with A=0; a matrix that preserves that alpha makes the
   * frame composite as fully transparent (black preview). Both the preview and the export now
   * run their preset matrix through here, so they upload byte-identical uniforms.
   */
  fun filterGlMatrix(arr: FloatArray, forceOpaqueAlpha: Boolean = true): Pair<FloatArray, FloatArray> {
    val a = arr.copyOf()
    if (forceOpaqueAlpha && a.size >= 20) {
      a[15] = 0f
      a[16] = 0f
      a[17] = 0f
      a[18] = 0f
      a[19] = 255f
    }
    return toGlMatrixAndOffset(a)
  }

  /**
   * Android 4x5 row-major [ColorMatrix] array -> GL column-major 4x4 + normalised offset.
   * Shared by preview and export so the two can never disagree on the layout.
   */
  fun toGlMatrixAndOffset(arr: FloatArray): Pair<FloatArray, FloatArray> {
    require(arr.size >= 20) { "ColorMatrix array must hold 20 floats, got ${arr.size}" }
    val m = floatArrayOf(
      arr[0], arr[5], arr[10], arr[15],
      arr[1], arr[6], arr[11], arr[16],
      arr[2], arr[7], arr[12], arr[17],
      arr[3], arr[8], arr[13], arr[18]
    )
    val off = floatArrayOf(arr[4] / 255f, arr[9] / 255f, arr[14] / 255f, arr[19] / 255f)
    return m to off
  }

  // -------------------------------------------------------------------------------------------
  // 4. CPU reference implementation of the colour stages.
  //    Stage-for-stage replica of the GLSL (the spatial taps are skipped: with sharpness,
  //    clarity, denoise, glow, vignette and grain at neutral they contribute nothing, which is
  //    exactly the case every unit test exercises).
  // -------------------------------------------------------------------------------------------

  fun luma(r: Float, g: Float, b: Float): Float = r * LUMA_R + g * LUMA_G + b * LUMA_B

  fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    if (edge0 == edge1) return if (x < edge0) 0f else 1f
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
  }

  /**
   * Applies every non-spatial stage of the documented order to one pixel and returns
   * `floatArrayOf(r, g, b, a)` with rgb clamped to 0..1 and alpha forced opaque.
   *
   * This is the reference the preview shader, the export shader and
   * [com.example.engine.effects.media3.ColorGradingSnapshot.evaluatePreviewPixel] are all
   * checked against.
   */
  fun evaluate(params: LookParams, r: Float, g: Float, b: Float, a: Float = 0f): FloatArray {
    var cr = r
    var cg = g
    var cb = b

    // 3/4. Exposure (2^EV) then brightness offset.
    val expScale = 2.0.pow(params.exposure.toDouble()).toFloat()
    cr = cr * expScale + params.brightness
    cg = cg * expScale + params.brightness
    cb = cb * expScale + params.brightness

    // 5. Contrast
    if (abs(params.contrast - 1f) > IDENTITY_EPSILON) {
      cr = (cr - 0.5f) * params.contrast + 0.5f
      cg = (cg - 0.5f) * params.contrast + 0.5f
      cb = (cb - 0.5f) * params.contrast + 0.5f
    }

    // 6. Saturation
    if (abs(params.saturation - 1f) > IDENTITY_EPSILON) {
      val l = luma(cr, cg, cb)
      cr = l + (cr - l) * params.saturation
      cg = l + (cg - l) * params.saturation
      cb = l + (cb - l) * params.saturation
    }

    // 7. Temperature & tint
    if (abs(params.temperature) > IDENTITY_EPSILON || abs(params.tint) > IDENTITY_EPSILON) {
      cr += params.temperature * TEMP_R_GAIN + params.tint * TINT_RB_GAIN
      cg -= params.tint * TINT_G_GAIN
      cb -= params.temperature * TEMP_B_GAIN - params.tint * TINT_RB_GAIN
    }

    // 8. Highlights & shadows
    if (abs(params.highlights) > IDENTITY_EPSILON || abs(params.shadows) > IDENTITY_EPSILON) {
      val l = luma(cr, cg, cb)
      val shadowW = 1f - smoothstep(SHADOW_EDGE_LOW, SHADOW_EDGE_HIGH, l)
      val highlightW = smoothstep(HIGHLIGHT_EDGE_LOW, HIGHLIGHT_EDGE_HIGH, l)
      val add = params.shadows * shadowW * TONE_GAIN + params.highlights * highlightW * TONE_GAIN
      cr += add; cg += add; cb += add
    }

    // 9. Whites & blacks
    if (abs(params.whites) > IDENTITY_EPSILON || abs(params.blacks) > IDENTITY_EPSILON) {
      val l = luma(cr, cg, cb)
      val whiteW = smoothstep(WHITE_EDGE_LOW, WHITE_EDGE_HIGH, l)
      val blackW = 1f - smoothstep(BLACK_EDGE_LOW, BLACK_EDGE_HIGH, l)
      val add = params.whites * whiteW * WHITE_BLACK_GAIN + params.blacks * blackW * WHITE_BLACK_GAIN
      cr += add; cg += add; cb += add
    }

    // 10a. Auto Enhance
    if (params.autoEnhance > STAGE_EPSILON) {
      val ae = params.autoEnhance
      val l = luma(cr, cg, cb)
      cr = l + (cr - l) * (1f + ae * AUTO_ENHANCE_SAT)
      cg = l + (cg - l) * (1f + ae * AUTO_ENHANCE_SAT)
      cb = l + (cb - l) * (1f + ae * AUTO_ENHANCE_SAT)
      cr = (cr - 0.5f) * (1f + ae * AUTO_ENHANCE_CONTRAST) + 0.5f + ae * AUTO_ENHANCE_LIFT
      cg = (cg - 0.5f) * (1f + ae * AUTO_ENHANCE_CONTRAST) + 0.5f + ae * AUTO_ENHANCE_LIFT
      cb = (cb - 0.5f) * (1f + ae * AUTO_ENHANCE_CONTRAST) + 0.5f + ae * AUTO_ENHANCE_LIFT
    }

    // 10b. HDR Boost / Color Correct (SDR look)
    if (params.hdrBoost > STAGE_EPSILON) {
      val h = params.hdrBoost
      val l = luma(cr, cg, cb)
      cr = l + (cr - l) * (1f + h * HDR_SAT)
      cg = l + (cg - l) * (1f + h * HDR_SAT)
      cb = l + (cb - l) * (1f + h * HDR_SAT)
      cr = (cr - 0.5f) * (1f + h * HDR_CONTRAST) + 0.5f + h * HDR_LIFT
      cg = (cg - 0.5f) * (1f + h * HDR_CONTRAST) + 0.5f + h * HDR_LIFT
      cb = (cb - 0.5f) * (1f + h * HDR_CONTRAST) + 0.5f + h * HDR_LIFT
    }

    // 10c. Color Fix
    if (params.colorFix > STAGE_EPSILON) {
      cr *= 1f - params.colorFix * COLOR_FIX_R
      cg *= 1f - params.colorFix * COLOR_FIX_G
      cb *= 1f + params.colorFix * COLOR_FIX_B
    }

    // 10e. Fade
    if (params.fade > STAGE_EPSILON) {
      cr += (0.5f - cr) * params.fade * FADE_MIX
      cg += (0.5f - cg) * params.fade * FADE_MIX
      cb += (0.5f - cb) * params.fade * FADE_MIX
    }

    // 12. Filter colour matrix
    val m = params.colorMatrix
    val off = params.colorOffset
    if (m != null && off != null && m.size == 16 && off.size >= 4) {
      val nr = m[0] * cr + m[4] * cg + m[8] * cb + m[12] * a + off[0]
      val ng = m[1] * cr + m[5] * cg + m[9] * cb + m[13] * a + off[1]
      val nb = m[2] * cr + m[6] * cg + m[10] * cb + m[14] * a + off[2]
      cr = nr; cg = ng; cb = nb
    }

    // 15. Clamp + opaque alpha. Video/decoder textures arrive with A=0; leaving it transparent
    // composites the frame as black on a TextureView.
    return floatArrayOf(cr.coerceIn(0f, 1f), cg.coerceIn(0f, 1f), cb.coerceIn(0f, 1f), 1f)
  }
}
