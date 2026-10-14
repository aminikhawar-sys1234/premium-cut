package com.example.engine.effects

import com.example.domain.model.FilterSettings
import com.example.domain.model.FilterType
import com.example.domain.model.VideoAdjustments
import com.example.engine.effects.media3.ColorGradingSnapshot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * Unit tests for [ColorLookModel] — the single deterministic look resolution shared by the
 * live preview and the export renderer.
 *
 * These cover the mandatory "each slider changes real pixels" and "neutral preserves the
 * frame" requirements on the CPU reference, which is the same function
 * [ColorGradingSnapshot.evaluatePreviewPixel] is built on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ColorLookModelTest {

  private val probes = listOf(
    floatArrayOf(0.62f, 0.41f, 0.28f), // mid warm
    floatArrayOf(0.90f, 0.88f, 0.82f), // highlight
    floatArrayOf(0.12f, 0.10f, 0.14f), // shadow
    floatArrayOf(0.50f, 0.50f, 0.50f) // neutral grey
  )

  private fun pixel(adj: VideoAdjustments, filter: FilterSettings = FilterSettings(), p: FloatArray) =
    ColorGradingSnapshot.from(adj, filter).evaluatePreviewPixel(p[0], p[1], p[2], 0f)

  private fun meanDelta(adj: VideoAdjustments, filter: FilterSettings = FilterSettings()): Float {
    var total = 0f
    for (p in probes) {
      val out = pixel(adj, filter, p)
      total += abs(out[0] - p[0]) + abs(out[1] - p[1]) + abs(out[2] - p[2])
    }
    return total / probes.size
  }

  // ----------------------------------------------------------------------------------
  // 1. Neutral values reproduce the original frame
  // ----------------------------------------------------------------------------------

  @Test
  fun neutralAdjustmentsPreserveTheFrameExactly() {
    val neutral = ColorLookModel.effective(VideoAdjustments())
    assertTrue("neutral look must be reported neutral", neutral.isNeutral())
    for (p in probes) {
      val out = ColorLookModel.evaluate(neutral, p[0], p[1], p[2], 0f)
      assertEquals(p[0], out[0], 1f / 255f)
      assertEquals(p[1], out[1], 1f / 255f)
      assertEquals(p[2], out[2], 1f / 255f)
      // Decoder textures arrive with A=0; the look must force opaque or the frame is black.
      assertEquals(1f, out[3], 0.001f)
    }
  }

  @Test
  fun zeroFilterIntensityPreservesTheFrame() {
    for (type in FilterCatalog.SELECTABLE) {
      val out = pixel(VideoAdjustments(), FilterSettings(type = type, intensity = 0f), probes[0])
      assertEquals("$type at 0% must not change the frame", probes[0][0], out[0], 1f / 255f)
      assertEquals("$type at 0% must not change the frame", probes[0][1], out[1], 1f / 255f)
      assertEquals("$type at 0% must not change the frame", probes[0][2], out[2], 1f / 255f)
    }
  }

  // ----------------------------------------------------------------------------------
  // 2. Every colour slider changes rendered pixels
  // ----------------------------------------------------------------------------------

  @Test
  fun everyRequiredSliderChangesRenderedPixels() {
    val sliders = mapOf(
      "brightness" to VideoAdjustments(brightness = 0.35f),
      "contrast" to VideoAdjustments(contrast = 1.45f),
      "saturation" to VideoAdjustments(saturation = 1.6f),
      "exposure" to VideoAdjustments(exposure = 0.40f),
      "temperature" to VideoAdjustments(temperature = 0.50f),
      "tint" to VideoAdjustments(tint = -0.40f),
      "highlights" to VideoAdjustments(highlights = 0.50f),
      "shadows" to VideoAdjustments(shadows = 0.50f),
      // The rest of the Adjust / Video Quality panel.
      "whites" to VideoAdjustments(whites = 0.45f),
      "blacks" to VideoAdjustments(blacks = -0.35f),
      "fade" to VideoAdjustments(fade = 0.50f),
      "autoEnhance" to VideoAdjustments(autoEnhance = 0.60f),
      "hdrBoost" to VideoAdjustments(hdrBoost = 0.70f),
      "colorCorrect" to VideoAdjustments(colorCorrect = 0.70f),
      "colorFix" to VideoAdjustments(colorFix = 0.80f)
    )
    for ((name, adj) in sliders) {
      val d = meanDelta(adj)
      assertTrue("$name must change rendered pixels (delta=$d)", d > 0.01f)
    }
  }

  @Test
  fun slidersMoveThePictureInTheDocumentedDirection() {
    val flat = floatArrayOf(0.5f, 0.5f, 0.5f)
    val brighter = pixel(VideoAdjustments(brightness = 0.3f), p = flat)
    assertTrue("brightness must brighten", brighter[0] > flat[0] && brighter[1] > flat[1])

    val desaturated = pixel(VideoAdjustments(saturation = 0f), p = floatArrayOf(0.7f, 0.4f, 0.2f))
    assertEquals(
      "saturation 0 must produce greyscale",
      desaturated[0], desaturated[1], 0.0001f
    )
    assertEquals(desaturated[1], desaturated[2], 0.0001f)

    val warm = pixel(VideoAdjustments(temperature = 0.8f), p = flat)
    assertTrue("warm temperature: R up, B down", warm[0] > flat[0] && warm[2] < flat[2])

    val tint = pixel(VideoAdjustments(tint = 0.8f), p = flat)
    assertTrue("positive tint: R/B up vs G", tint[0] > tint[1] && tint[2] > tint[1])

    val shadowProbe = floatArrayOf(0.05f, 0.05f, 0.05f)
    val midProbe = floatArrayOf(0.75f, 0.75f, 0.75f)
    val shadowLift = pixel(VideoAdjustments(shadows = 0.8f), p = shadowProbe)[0] - shadowProbe[0]
    val midLift = pixel(VideoAdjustments(shadows = 0.8f), p = midProbe)[0] - midProbe[0]
    assertTrue("shadows must lift shadows more than midtones ($shadowLift vs $midLift)",
      shadowLift > midLift)

    val highlightProbe = floatArrayOf(0.92f, 0.92f, 0.92f)
    val attenuated = pixel(VideoAdjustments(highlights = -0.8f), p = highlightProbe)[0]
    assertTrue("negative highlights must pull highlights down", attenuated < highlightProbe[0])
  }

  // ----------------------------------------------------------------------------------
  // 3. Filters are real pixel processing, with working intensity
  // ----------------------------------------------------------------------------------

  @Test
  fun everySelectableFilterChangesPixelsMeasurably() {
    for (type in FilterCatalog.SELECTABLE) {
      val d = meanDelta(VideoAdjustments(), FilterSettings(type = type, intensity = 1f))
      assertTrue("$type must change rendered pixels (delta=$d)", d > 0.01f)
    }
  }

  @Test
  fun filterIntensityIsMonotonicAndZeroIsNeutral() {
    val probe = probes[0]
    for (type in FilterCatalog.SELECTABLE) {
      fun deltaAt(intensity: Float): Float {
        val out = pixel(VideoAdjustments(), FilterSettings(type = type, intensity = intensity), probe)
        return abs(out[0] - probe[0]) + abs(out[1] - probe[1]) + abs(out[2] - probe[2])
      }
      val d0 = deltaAt(0f)
      val d25 = deltaAt(0.25f)
      val d50 = deltaAt(0.5f)
      val d100 = deltaAt(1f)
      assertEquals("$type at 0% must be neutral", 0f, d0, 1f / 255f)
      assertTrue("$type intensity must be monotonic ($d25, $d50, $d100)", d25 < d50 && d50 < d100)
    }
  }

  @Test
  fun filtersProduceTheDocumentedVisualDirection() {
    val flat = floatArrayOf(0.5f, 0.5f, 0.5f)
    val golden = pixel(VideoAdjustments(), FilterSettings(FilterType.GOLDEN_AUTUMN, 1f), flat)
    assertTrue("Golden must be warm", golden[0] > golden[2] + 0.05f)

    val blackLight = pixel(VideoAdjustments(), FilterSettings(FilterType.BLACKLIGHT_FIX, 1f), flat)
    assertTrue("Black Light must push blue/violet", blackLight[2] > blackLight[0])

    // Capability honesty: 4K and HDR are looks, not an upscaler / not HDR10.
    val fourK = ColorLookModel.effective(VideoAdjustments(), FilterSettings(FilterType.FOUR_K, 1f))
    assertTrue("4K must drive spatial detail, not resolution", fourK.sharpness > 0.3f && fourK.clarity > 0.2f)
    val hdr = ColorLookModel.effective(VideoAdjustments(), FilterSettings(FilterType.HDR, 1f))
    assertTrue("HDR must drive the SDR local-contrast look", hdr.hdrBoost > 0.5f && hdr.hdrBoost <= 1f)
    val glow = ColorLookModel.effective(VideoAdjustments(), FilterSettings(FilterType.GLOW, 1f))
    assertTrue("Glow must drive the bloom pass", glow.glow > 0.5f)
  }

  // ----------------------------------------------------------------------------------
  // 4. Stability: repeated filter changes cannot blacken or corrupt later projects
  // ----------------------------------------------------------------------------------

  @Test
  fun repeatedFilterChangesNeverBlackenOrCorruptTheFrame() {
    val probe = probes[0]
    var firstFrame: FloatArray? = null
    for (i in 0 until 200) {
      val type = FilterCatalog.SELECTABLE[i % FilterCatalog.SELECTABLE.size]
      val out = ColorGradingSnapshot.from(
        VideoAdjustments(), FilterSettings(type = type, intensity = 1f)
      ).evaluatePreviewPixel(probe[0], probe[1], probe[2], 0f)

      assertTrue("$type must keep the frame in range", out.all { it in 0f..1f })
      assertTrue("$type must not black the frame out", out[0] + out[1] + out[2] > 0.01f)
      assertTrue("$type must not blow the frame out to white", out[0] + out[1] + out[2] < 2.99f)

      // Determinism: the same look must produce the same frame every time it is selected,
      // including after 199 other selections. Stale uniforms / leaked GL state would drift.
      if (firstFrame == null) {
        firstFrame = out.copyOf()
      } else if (i % FilterCatalog.SELECTABLE.size == 0) {
        assertArrayEquals(
          "$type must be reproducible after ${i / FilterCatalog.SELECTABLE.size} round(s)",
          firstFrame, out, 1f / 255f
        )
      }
    }

    // Clearing the filter returns the untouched frame: no residue, no corrupted later project.
    val cleared = ColorGradingSnapshot.from(VideoAdjustments(), FilterSettings())
      .evaluatePreviewPixel(probe[0], probe[1], probe[2], 0f)
    assertEquals(cleared[0], probe[0], 1f / 255f)
    assertEquals(cleared[1], probe[1], 1f / 255f)
    assertEquals(cleared[2], probe[2], 1f / 255f)
  }

  // ----------------------------------------------------------------------------------
  // 5. Ranges, clamping and reset
  // ----------------------------------------------------------------------------------

  @Test
  fun outOfRangeValuesAreClampedToTheDocumentedRanges() {
    val p = ColorLookModel.effective(
      VideoAdjustments(brightness = 5f, contrast = -3f, saturation = 9f, exposure = -7f)
    )
    assertEquals(1f, p.brightness, 0f)
    assertEquals(0.10f, p.contrast, 0f)
    assertEquals(2f, p.saturation, 0f)
    assertEquals(-1f, p.exposure, 0f)
  }

  @Test
  fun filterExtrasAndPanelValuesClampTogether() {
    val p = ColorLookModel.effective(VideoAdjustments(autoEnhance = 0.8f), FilterSettings(FilterType.ENHANCE, 1f))
    assertEquals(1f, p.autoEnhance, 0f)
    val d = ColorLookModel.effective(VideoAdjustments(denoise = 0.9f, antiFlicker = 1f))
    assertEquals(1f, d.denoise, 0f)
  }

  @Test
  fun uiRangesAndRenderRangesAgree() {
    val pairs = listOf(
      ColorAdjustmentRanges.brightness to ColorLookModel.BRIGHTNESS,
      ColorAdjustmentRanges.contrast to ColorLookModel.CONTRAST,
      ColorAdjustmentRanges.saturation to ColorLookModel.SATURATION,
      ColorAdjustmentRanges.exposure to ColorLookModel.EXPOSURE,
      ColorAdjustmentRanges.temperature to ColorLookModel.TEMPERATURE,
      ColorAdjustmentRanges.tint to ColorLookModel.TINT,
      ColorAdjustmentRanges.highlights to ColorLookModel.HIGHLIGHTS,
      ColorAdjustmentRanges.shadows to ColorLookModel.SHADOWS,
      ColorAdjustmentRanges.whites to ColorLookModel.WHITES,
      ColorAdjustmentRanges.blacks to ColorLookModel.BLACKS,
      ColorAdjustmentRanges.sharpness to ColorLookModel.SHARPNESS,
      ColorAdjustmentRanges.clarity to ColorLookModel.CLARITY,
      ColorAdjustmentRanges.fade to ColorLookModel.FADE,
      ColorAdjustmentRanges.vignette to ColorLookModel.VIGNETTE,
      ColorAdjustmentRanges.grain to ColorLookModel.GRAIN
    )
    for ((ui, model) in pairs) {
      assertEquals("${ui.label} min", ui.min, model.min, 0f)
      assertEquals("${ui.label} max", ui.max, model.max, 0f)
      assertEquals("${ui.label} default", ui.default, model.neutral, 0f)
    }
  }

  @Test
  fun resetRestoresNeutralAndClearsSmartAutoSlidersShownInTheAdjustTab() {
    val edited = VideoAdjustments(
      brightness = 0.6f, contrast = 1.7f, saturation = 0.3f, exposure = -0.4f,
      temperature = 0.7f, tint = 0.5f, highlights = -0.6f, shadows = 0.8f,
      autoEnhance = 0.9f, colorFix = 0.8f, colorCorrect = 0.7f
    )
    val reset = ColorAdjustmentRanges.resetPrimary(edited)
    val look = ColorLookModel.effective(reset)
    assertTrue("Adjust-Reset must return a neutral look", look.isNeutral())
    assertEquals(0f, reset.autoEnhance, 0f)
    assertEquals(0f, reset.colorFix, 0f)
    assertEquals(0f, reset.colorCorrect, 0f)
  }

  // ----------------------------------------------------------------------------------
  // 6. Shared GL matrix conversion
  // ----------------------------------------------------------------------------------

  @Test
  fun filterGlMatrixIsColumnMajorAndForcesOpaqueAlpha() {
    val arr = floatArrayOf(
      1f, 0f, 0f, 0f, 10f,
      0f, 1f, 0f, 0f, 20f,
      0f, 0f, 1f, 0f, 30f,
      0f, 0f, 0f, 1f, 0f
    )
    val (m, off) = ColorLookModel.filterGlMatrix(arr)
    // Column-major 4x4: column 0 is the first row's rgb weights.
    assertEquals(1f, m[0], 0f)
    assertEquals(0f, m[1], 0f)
    assertEquals(0f, m[2], 0f)
    assertEquals(0f, m[3], 0f) // arr[18] forced to 0 by forceOpaqueAlpha
    // Offsets normalised to 0..1
    assertEquals(10f / 255f, off[0], 0.0001f)
    assertEquals(20f / 255f, off[1], 0.0001f)
    assertEquals(30f / 255f, off[2], 0.0001f)
    assertEquals(1f, off[3], 0.0001f) // arr[19] forced to 255
  }

  @Test
  fun disabledFilterProducesNoMatrix() {
    val look = ColorLookModel.effectiveWithAndroidMatrix(
      VideoAdjustments(),
      FilterSettings(type = FilterType.GOLDEN_AUTUMN, intensity = 1f, enabled = false),
      null
    )
    assertTrue(look.colorMatrix == null)
    assertTrue(look.colorOffset == null)
  }
}
