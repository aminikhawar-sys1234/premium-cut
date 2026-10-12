package com.example.engine.effects.media3

import com.example.domain.model.FilterSettings
import com.example.domain.model.FilterType
import com.example.domain.model.Timeline
import com.example.domain.model.VideoAdjustments
import com.example.domain.model.VideoClip
import com.example.engine.composition.ColorFilterGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * Repeatable evidence that Filters / Adjust / Video Quality change real pixels
 * without rebuilding the Media3 graph. Device playback cannot run in this
 * environment; these tests lock the CPU-side contract the live shader uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FilterEngineForensicTest {

  private val sample = floatArrayOf(0.62f, 0.41f, 0.28f)
  private val transparentSampleAlpha = 0f

  private fun clip(
    filter: FilterSettings = FilterSettings(),
    adjustments: VideoAdjustments = VideoAdjustments(),
    colorGradeJson: String? = null,
    vfxStackJson: String? = null
  ) = VideoClip(
    id = "v1",
    name = "v1",
    uri = "file:///v1.mp4",
    durationMs = 4000L,
    filter = filter,
    adjustments = adjustments,
    colorGradeJson = colorGradeJson,
    vfxStackJson = vfxStackJson
  )

  private fun pixelDelta(a: FloatArray, b: FloatArray): Float =
    abs(a[0] - b[0]) + abs(a[1] - b[1]) + abs(a[2] - b[2])

  @Test
  fun previewShaderForcesOpaqueAlpha() {
    assertTrue(
      ColorGradingGlEffect.COLOR_GRADING_FRAGMENT_SHADER.contains("color.a = 1.0;")
    )
    assertTrue(
      ColorGradingGlEffect.COLOR_GRADING_FRAGMENT_SHADER.contains("gl_FragColor = color;")
    )
  }

  @Test
  fun residentGradingNodeNeverDropsAndNeverRebuildsForLooks() {
    val timeline = Timeline()
    val none = clip()
    val cinematic = clip(filter = FilterSettings(type = FilterType.CINEMATIC, intensity = 0.85f))
    val brighter = clip(adjustments = VideoAdjustments(brightness = 0.35f, temperature = 0.4f, tint = -0.2f))
    val quality = clip(adjustments = VideoAdjustments(autoEnhance = 0.6f, hdrBoost = 0.4f, denoise = 0.3f))
    val reset = clip()

    val noneEffects = PreviewFilterEffects.effectsFor(none, timeline)
    val lookEffects = PreviewFilterEffects.effectsFor(cinematic, timeline)
    assertEquals(1, noneEffects.filterIsInstance<ColorGradingGlEffect>().size)
    assertEquals(1, lookEffects.filterIsInstance<ColorGradingGlEffect>().size)
    assertTrue(noneEffects.filterIsInstance<ColorGradingGlEffect>().single().isIdentityLook())
    assertFalse(lookEffects.filterIsInstance<ColorGradingGlEffect>().single().isIdentityLook())
    assertFalse(lookEffects.filterIsInstance<ColorGradingGlEffect>().single().isNoOp(1920, 1080))

    val signatures = listOf(none, cinematic, brighter, quality, reset).map {
      PreviewFilterEffects.media3GraphSignature(it, timeline)
    }
    assertTrue(signatures.all { it == signatures.first() })
    assertTrue(signatures.all { it.startsWith("G|") })
    assertFalse(
      PreviewEffectApplyPolicy.shouldRebuildGraph(
        signatures.first(),
        PreviewFilterEffects.media3GraphSignature(cinematic, timeline),
        force = false
      )
    )
    assertFalse(
      PreviewEffectApplyPolicy.shouldCallSetVideoEffects(
        newEffectCount = 1,
        previousHadEffects = true,
        graphChanged = false,
        force = false
      )
    )
  }

  @Test
  fun identityLookLeavesPixelsEquivalentAndForcesOpaque() {
    val identity = ColorGradingSnapshot.from(VideoAdjustments(), FilterSettings())
    val out = identity.evaluatePreviewPixel(sample[0], sample[1], sample[2], transparentSampleAlpha)
    assertEquals(sample[0], out[0], 0.001f)
    assertEquals(sample[1], out[1], 0.001f)
    assertEquals(sample[2], out[2], 0.001f)
    assertEquals(1f, out[3], 0.001f)
    assertTrue(identity.isNoOp())
  }

  @Test
  fun everyFilterPresetChangesPixelsIncludingTransparentSource() {
    val identity = ColorGradingSnapshot.from(VideoAdjustments(), FilterSettings())
    val baseline = identity.evaluatePreviewPixel(sample[0], sample[1], sample[2], transparentSampleAlpha)
    var checked = 0
    for (type in FilterType.values()) {
      if (type == FilterType.NONE) continue
      val snapshot = ColorGradingSnapshot.from(
        VideoAdjustments(),
        FilterSettings(type = type, intensity = 1f)
      )
      val out = snapshot.evaluatePreviewPixel(sample[0], sample[1], sample[2], transparentSampleAlpha)
      assertEquals("Filter ${type.name} must write opaque alpha", 1f, out[3], 0.001f)
      assertTrue(
        "Filter ${type.name} must change RGB vs original",
        pixelDelta(baseline, out) > 0.01f
      )
      val weak = ColorGradingSnapshot.from(
        VideoAdjustments(),
        FilterSettings(type = type, intensity = 0.2f)
      )
      val strong = ColorGradingSnapshot.from(
        VideoAdjustments(),
        FilterSettings(type = type, intensity = 1f)
      )
      assertTrue(
        "Intensity must change ${type.name} strength",
        pixelDelta(
          weak.evaluatePreviewPixel(sample[0], sample[1], sample[2], 0f),
          strong.evaluatePreviewPixel(sample[0], sample[1], sample[2], 0f)
        ) > 0.001f
      )
      checked++
    }
    assertTrue(checked >= 15)
  }

  @Test
  fun everyManualAdjustmentChangesPixelsAndResetRestoresIdentity() {
    val identity = ColorGradingSnapshot.from(VideoAdjustments(), FilterSettings())
    val probes = listOf(
      floatArrayOf(0.62f, 0.41f, 0.28f),
      floatArrayOf(0.90f, 0.88f, 0.82f),
      floatArrayOf(0.12f, 0.10f, 0.14f)
    )
    val cases = listOf(
      VideoAdjustments(brightness = 0.35f),
      VideoAdjustments(contrast = 1.45f),
      VideoAdjustments(saturation = 1.6f),
      VideoAdjustments(exposure = 0.4f),
      VideoAdjustments(temperature = 0.7f),
      VideoAdjustments(tint = -0.55f),
      VideoAdjustments(highlights = 0.6f),
      VideoAdjustments(shadows = -0.5f),
      VideoAdjustments(whites = 0.5f),
      VideoAdjustments(blacks = -0.4f),
      VideoAdjustments(fade = 0.6f),
      VideoAdjustments(autoEnhance = 0.7f),
      VideoAdjustments(hdrBoost = 0.65f),
      VideoAdjustments(colorFix = 0.8f),
      VideoAdjustments(colorCorrect = 0.7f)
    )
    for (adj in cases) {
      val snapshot = ColorGradingSnapshot.from(adj, FilterSettings())
      val maxDelta = probes.maxOf { probe ->
        pixelDelta(
          identity.evaluatePreviewPixel(probe[0], probe[1], probe[2], 0f),
          snapshot.evaluatePreviewPixel(probe[0], probe[1], probe[2], 0f)
        )
      }
      assertTrue("Adjustment $adj must reach the shader math", maxDelta > 0.01f)
      assertEquals(1f, snapshot.evaluatePreviewPixel(sample[0], sample[1], sample[2], 0f)[3], 0.001f)
    }
    val reset = ColorGradingSnapshot.from(VideoAdjustments(), FilterSettings())
    val restored = reset.evaluatePreviewPixel(sample[0], sample[1], sample[2], 0f)
    val identityPixel = identity.evaluatePreviewPixel(sample[0], sample[1], sample[2], 0f)
    assertTrue(pixelDelta(identityPixel, restored) < 0.001f)
  }

  @Test
  fun filterPlusAdjustmentsCombineAndLivePublishDoesNotRebuildGraph() {
    val clip = clip(
      filter = FilterSettings(type = FilterType.GOLDEN_AUTUMN, intensity = 0.7f),
      adjustments = VideoAdjustments(brightness = 0.2f, contrast = 1.25f, temperature = 0.3f, highlights = 0.2f)
    )
    val timeline = Timeline(videoClips = listOf(clip))
    val effect = PreviewFilterEffects.effectsFor(clip, timeline).filterIsInstance<ColorGradingGlEffect>().single()
    val before = ColorGradingSnapshot.from(effect)
    val next = ColorGradingSnapshot.from(
      VideoAdjustments(brightness = 0.45f, contrast = 1.4f, temperature = 0.5f, highlights = 0.35f),
      FilterSettings(type = FilterType.OCEANIC_VIEW, intensity = 0.9f)
    )
    effect.publishLive(next)
    val original = floatArrayOf(0.55f, 0.48f, 0.33f)
    assertTrue(
      pixelDelta(
        before.evaluatePreviewPixel(original[0], original[1], original[2], 0f),
        next.evaluatePreviewPixel(original[0], original[1], original[2], 0f)
      ) > 0.02f
    )
    assertEquals(0.45f, effect.liveSnapshot?.brightness ?: 0f, 0.001f)
    assertEquals(0.2f, effect.brightness, 0.001f)
    assertEquals(
      PreviewFilterEffects.media3GraphSignature(clip, timeline),
      PreviewFilterEffects.media3GraphSignature(
        clip.copy(filter = FilterSettings(type = FilterType.OCEANIC_VIEW, intensity = 0.9f)),
        timeline
      )
    )
  }

  @Test
  fun disabledFilterAndZeroIntensityAreIdentity() {
    val disabled = ColorGradingSnapshot.from(
      VideoAdjustments(),
      FilterSettings(type = FilterType.CINEMATIC, intensity = 1f, enabled = false)
    )
    val zero = ColorGradingSnapshot.from(
      VideoAdjustments(),
      FilterSettings(type = FilterType.CINEMATIC, intensity = 0f)
    )
    assertTrue(disabled.isNoOp())
    assertTrue(zero.isNoOp())
    assertFalse(PreviewFilterEffects.isActiveLook(FilterSettings(type = FilterType.CINEMATIC, enabled = false)))
    assertFalse(PreviewFilterEffects.isActiveLook(FilterSettings(type = FilterType.CINEMATIC, intensity = 0f)))
  }

  @Test
  fun lutOrVfxStillChangesGraphWhileLooksDoNot() {
    val timeline = Timeline()
    val plain = clip()
    val filtered = clip(filter = FilterSettings(type = FilterType.WARM, intensity = 1f))
    assertEquals(
      PreviewFilterEffects.media3GraphSignature(plain, timeline),
      PreviewFilterEffects.media3GraphSignature(filtered, timeline)
    )
    val withLut = clip(
      colorGradeJson = com.example.engine.color.ColorEngineHost.encode(
        com.example.engine.color.ColorEngineHost.decodeOrDefault(null).copy(
          lut = com.ahstudio.color.core.LutState(lutId = "teal_orange", intensity = 0.7f)
        )
      )
    )
    assertNotEquals(
      PreviewFilterEffects.media3GraphSignature(plain, timeline),
      PreviewFilterEffects.media3GraphSignature(withLut, timeline)
    )
  }

  @Test
  fun previewAndExportSharePresetMatrixAndOpaqueConversion() {
    for (type in listOf(FilterType.BLACK_AND_WHITE, FilterType.CINEMATIC, FilterType.FOUR_K, FilterType.ALMOND)) {
      val filter = FilterSettings(type = type, intensity = 1f)
      val preview = PreviewDisplayFilter.matrixFor(filter)
      val export = ColorFilterGenerator.getFilterMatrix(type, 1f)
      assertNotNull(preview)
      assertNotNull(export)
      assertEquals(export!!.array.toList(), preview!!.array.toList())
      val (_, offset) = ColorGradingGlEffect.convertColorMatrixToGl(
        PreviewEffectApplyPolicy.forceOpaqueAlpha(export)
      )
      assertEquals(1f, offset[3], 0.001f)
    }
  }

  @Test
  fun saturationExposureAndTintReachSharedShaderMath() {
    val snapshot = ColorGradingSnapshot.from(
      VideoAdjustments(saturation = 1.8f, exposure = 0.35f, tint = -0.4f),
      FilterSettings()
    )
    val identity = ColorGradingSnapshot.from(VideoAdjustments(), FilterSettings())
    assertTrue(
      pixelDelta(
        identity.evaluatePreviewPixel(sample[0], sample[1], sample[2], 0f),
        snapshot.evaluatePreviewPixel(sample[0], sample[1], sample[2], 0f)
      ) > 0.01f
    )
    assertTrue(ColorGradingGlEffect.COLOR_GRADING_FRAGMENT_SHADER.contains("uSaturation"))
    assertTrue(ColorGradingGlEffect.COLOR_GRADING_FRAGMENT_SHADER.contains("pow(2.0, uExposure)"))
    assertTrue(ColorGradingGlEffect.COLOR_GRADING_FRAGMENT_SHADER.contains("uTint"))
    assertTrue(ColorGradingGlEffect.COLOR_GRADING_FRAGMENT_SHADER.contains("uGlow"))
  }
}
