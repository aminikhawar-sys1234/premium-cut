package com.example.engine.effects

import com.example.domain.model.FilterSettings
import com.example.domain.model.FilterType
import com.example.domain.model.VideoAdjustments
import com.example.engine.effects.media3.ColorGradingGlEffect
import com.example.engine.effects.media3.ColorGradingSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FilterCatalogTest {

  @Test
  fun selectableLooksAreExactlyTheSixRequestedPresets() {
    assertEquals(
      listOf("4K", "Black Light", "Golden", "HDR", "Enhance", "Glow"),
      FilterCatalog.SELECTABLE.map { it.displayName }
    )
    assertTrue(FilterCatalog.isSelectable(FilterType.NONE))
    assertFalse(FilterCatalog.isSelectable(FilterType.CINEMATIC))
    assertFalse(FilterCatalog.isSelectable(FilterType.OCEANIC_VIEW))
  }

  @Test
  fun fourKAndHdrPublishHonestCapabilityNotes() {
    val fourK = FilterCatalog.capabilityNote(FilterType.FOUR_K)!!
    assertTrue(fourK.contains("does not upsample"))
    assertTrue(fourK.contains("Export"))
    val hdr = FilterCatalog.capabilityNote(FilterType.HDR)!!
    assertTrue(hdr.contains("SDR"))
    assertTrue(hdr.contains("HDR10") || hdr.contains("HLG"))
  }

  @Test
  fun retainedLooksChangePixelsAndApplyRealSpatialExtras() {
    val identity = ColorGradingSnapshot.from(VideoAdjustments(), FilterSettings())
    val sample = floatArrayOf(0.62f, 0.41f, 0.28f)
    for (type in FilterCatalog.SELECTABLE) {
      val snapshot = ColorGradingSnapshot.from(
        VideoAdjustments(),
        FilterSettings(type = type, intensity = 1f)
      )
      val out = snapshot.evaluatePreviewPixel(sample[0], sample[1], sample[2], 0f)
      val delta = abs(out[0] - sample[0]) + abs(out[1] - sample[1]) + abs(out[2] - sample[2])
      assertTrue("$type must change RGB", delta > 0.01f)
      assertEquals(1f, out[3], 0.001f)
      assertFalse(snapshot.isNoOp())
    }

    val fourK = ColorGradingGlEffect.fromTimeline(
      VideoAdjustments(),
      FilterSettings(type = FilterType.FOUR_K, intensity = 1f)
    )
    assertTrue("4K look must add real sharpening, not only a colour matrix", fourK.sharpness > 0.2f)
    assertTrue(fourK.clarity > 0.2f)
    assertEquals(0f, fourK.glow, 0.001f)

    val glow = ColorGradingGlEffect.fromTimeline(
      VideoAdjustments(),
      FilterSettings(type = FilterType.GLOW, intensity = 1f)
    )
    assertTrue("Glow must drive the bloom uniform", glow.glow > 0.5f)

    val hdr = ColorGradingGlEffect.fromTimeline(
      VideoAdjustments(),
      FilterSettings(type = FilterType.HDR, intensity = 1f)
    )
    assertTrue(hdr.hdrBoost > 0.4f)

    val enhance = ColorGradingGlEffect.fromTimeline(
      VideoAdjustments(),
      FilterSettings(type = FilterType.ENHANCE, intensity = 1f)
    )
    assertTrue(enhance.sharpness > 0.2f)
    assertTrue(enhance.autoEnhance > 0.2f)
  }

  @Test
  fun sliderAdjustmentsUsePhotographicExposureSharedWithExport() {
    val snapshot = ColorGradingSnapshot.from(VideoAdjustments(exposure = 1f), FilterSettings())
    val out = snapshot.evaluatePreviewPixel(0.4f, 0.4f, 0.4f, 0f)
    assertEquals(0.8f, out[0], 0.02f)
    assertTrue(ColorGradingGlEffect.COLOR_GRADING_FRAGMENT_SHADER.contains("pow(2.0, uExposure)"))
    assertTrue(
      com.example.engine.composition.gpu.GpuShaders.buildFragmentShader(false).contains("pow(2.0, uExposure)")
    )
    assertTrue(
      com.example.engine.composition.gpu.GpuShaders.buildFragmentShader(false).contains("uGlow")
    )
  }

  @Test
  fun resetRangesRestoreIdentityDefaults() {
    val dirty = VideoAdjustments(
      brightness = 0.4f,
      contrast = 1.5f,
      saturation = 1.6f,
      exposure = 0.3f,
      temperature = 0.2f,
      tint = -0.2f,
      highlights = 0.3f,
      shadows = -0.2f,
      sharpness = 0.5f,
      autoEnhance = 0.4f,
      hdrBoost = 0.5f
    )
    val reset = ColorAdjustmentRanges.resetPrimary(dirty)
    assertEquals(0f, reset.brightness, 0f)
    assertEquals(1f, reset.contrast, 0f)
    assertEquals(1f, reset.saturation, 0f)
    assertEquals(0f, reset.exposure, 0f)
    assertEquals(0.4f, reset.autoEnhance, 0f)
    val quality = ColorAdjustmentRanges.resetQuality(reset)
    assertEquals(0f, quality.autoEnhance, 0f)
    assertEquals(0f, quality.hdrBoost, 0f)
    assertNotEquals(dirty.brightness, reset.brightness)
  }

  @Test
  fun legacyPresetsRemainProcessableForSavedProjects() {
    val cinematic = ColorGradingSnapshot.from(
      VideoAdjustments(),
      FilterSettings(type = FilterType.CINEMATIC, intensity = 1f)
    )
    assertFalse(cinematic.isNoOp())
    assertFalse(FilterCatalog.isSelectable(FilterType.CINEMATIC))
  }
}
