package com.example.engine.effects.media3

import android.graphics.ColorMatrix
import androidx.media3.common.PlaybackException
import com.example.domain.model.FilterSettings
import com.example.domain.model.FilterType
import com.example.domain.model.Timeline
import com.example.domain.model.VideoAdjustments
import com.example.domain.model.VideoClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreviewEffectApplyPolicyTest {

  private fun clip(
    filter: FilterSettings = FilterSettings(),
    adjustments: VideoAdjustments = VideoAdjustments()
  ) = VideoClip(
    id = "v1",
    name = "v1",
    uri = "file:///v1.mp4",
    durationMs = 4000L,
    filter = filter,
    adjustments = adjustments
  )

  @Test
  fun emptyGraphMustNotEnableEffectsRenderer() {
    assertFalse(
      PreviewEffectApplyPolicy.shouldCallSetVideoEffects(
        newEffectCount = 0,
        previousHadEffects = false,
        graphChanged = true,
        force = true
      )
    )
  }

  @Test
  fun sliderTicksDoNotRebuildGraph() {
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
  fun addingOrClearingALookRebuildsGraph() {
    assertTrue(
      PreviewEffectApplyPolicy.shouldCallSetVideoEffects(
        newEffectCount = 1,
        previousHadEffects = false,
        graphChanged = true,
        force = false
      )
    )
    assertTrue(
      PreviewEffectApplyPolicy.shouldCallSetVideoEffects(
        newEffectCount = 0,
        previousHadEffects = true,
        graphChanged = true,
        force = false
      )
    )
  }

  @Test
  fun graphSignatureIgnoresColorAndQualitySliderValues() {
    val timeline = Timeline()
    val filtered = clip(filter = FilterSettings(type = FilterType.CINEMATIC, intensity = 0.4f))
    val stronger = clip(filter = FilterSettings(type = FilterType.CINEMATIC, intensity = 1f))
    val adjusted = clip(
      filter = FilterSettings(type = FilterType.CINEMATIC, intensity = 1f),
      adjustments = VideoAdjustments(brightness = 0.3f, denoise = 0.5f, autoEnhance = 0.6f)
    )
    val quality = clip(adjustments = VideoAdjustments(hdrBoost = 0.7f, colorFix = 0.4f, superClarity = 0.5f))
    val qualityMore = clip(adjustments = VideoAdjustments(hdrBoost = 0.9f, colorFix = 0.8f, superClarity = 0.9f))

    assertEquals(
      PreviewEffectApplyPolicy.graphSignature(filtered, timeline),
      PreviewEffectApplyPolicy.graphSignature(stronger, timeline)
    )
    assertEquals(
      PreviewEffectApplyPolicy.graphSignature(filtered, timeline),
      PreviewEffectApplyPolicy.graphSignature(adjusted, timeline)
    )
    assertEquals(
      PreviewEffectApplyPolicy.graphSignature(quality, timeline),
      PreviewEffectApplyPolicy.graphSignature(qualityMore, timeline)
    )
    assertNotEquals(
      PreviewEffectApplyPolicy.graphSignature(clip(), timeline),
      PreviewEffectApplyPolicy.graphSignature(filtered, timeline)
    )
  }

  @Test
  fun previewQualityDoesNotChangeDecoderGraphWhenAlreadyGrading() {
    val timeline = Timeline()
    val color = clip(adjustments = VideoAdjustments(brightness = 0.2f))
    val quality = clip(adjustments = VideoAdjustments(brightness = 0.2f, denoise = 0.4f, antiFlicker = 0.3f))
    assertEquals(
      PreviewEffectApplyPolicy.graphSignature(color, timeline),
      PreviewEffectApplyPolicy.graphSignature(quality, timeline)
    )
    assertFalse(
      PreviewEffectApplyPolicy.shouldRebuildGraph(
        PreviewEffectApplyPolicy.graphSignature(color, timeline),
        PreviewEffectApplyPolicy.graphSignature(quality, timeline),
        force = false
      )
    )
  }

  @Test
  fun forceOpaqueAlphaKeepsRgbAndForcesATo255() {
    val src = ColorMatrix(
      floatArrayOf(
        1.2f, 0f, 0f, 0f, 10f,
        0f, 1.1f, 0f, 0f, 5f,
        0f, 0f, 0.9f, 0f, -4f,
        0f, 0f, 0f, 1f, 0f
      )
    )
    val opaque = PreviewEffectApplyPolicy.forceOpaqueAlpha(src)
    val a = opaque.array
    assertEquals(1.2f, a[0], 0.001f)
    assertEquals(1.1f, a[6], 0.001f)
    assertEquals(0.9f, a[12], 0.001f)
    assertEquals(0f, a[15], 0.001f)
    assertEquals(0f, a[18], 0.001f)
    assertEquals(255f, a[19], 0.001f)
  }

  @Test
  fun videoProcessingFailureCodesAreRecognized() {
    val processing = PlaybackException("gpu", null, PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED)
    val init = PlaybackException("init", null, PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED)
    val io = PlaybackException("io", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
    assertTrue(PreviewEffectApplyPolicy.isVideoProcessingFailure(processing))
    assertTrue(PreviewEffectApplyPolicy.isVideoProcessingFailure(init))
    assertFalse(PreviewEffectApplyPolicy.isVideoProcessingFailure(io))
  }

  @Test
  fun liveSnapshotPublishesWithoutChangingConstructorIdentity() {
    val effect = ColorGradingGlEffect.fromTimeline(
      VideoAdjustments(brightness = 0.1f),
      FilterSettings(type = FilterType.WARM, intensity = 0.5f)
    )
    assertFalse(effect.isNoOp(1920, 1080))
    val next = ColorGradingSnapshot.from(
      VideoAdjustments(brightness = 0.4f, contrast = 1.3f, denoise = 0.2f),
      FilterSettings(type = FilterType.COOL, intensity = 0.8f)
    )
    effect.publishLive(next)
    assertEquals(0.4f, effect.liveSnapshot?.brightness ?: 0f, 0.001f)
    assertEquals(1.3f, effect.liveSnapshot?.contrast ?: 0f, 0.001f)
    assertEquals(0.2f, effect.liveSnapshot?.denoise ?: 0f, 0.001f)
    assertEquals(0.1f, effect.brightness, 0.001f)
    assertFalse(effect.isNoOp(1920, 1080))
  }

  @Test
  fun combinedFilterAdjustmentAndQualityStillNeedOneGradingNode() {
    val clip = clip(
      filter = FilterSettings(type = FilterType.GOLDEN_AUTUMN, intensity = 0.7f),
      adjustments = VideoAdjustments(
        brightness = 0.15f,
        contrast = 1.2f,
        saturation = 1.1f,
        temperature = 0.2f,
        tint = -0.1f,
        highlights = 0.2f,
        shadows = -0.15f,
        autoEnhance = 0.4f,
        hdrBoost = 0.3f,
        denoise = 0.25f,
        superClarity = 0.3f
      )
    )
    val timeline = Timeline(videoClips = listOf(clip))
    val effects = PreviewFilterEffects.effectsFor(clip, timeline)
    assertEquals(1, effects.filterIsInstance<ColorGradingGlEffect>().size)
    assertTrue(PreviewEffectApplyPolicy.needsGrading(clip, timeline))
    val snapshot = ColorGradingSnapshot.from(clip.adjustments!!, clip.filter!!)
    assertFalse(snapshot.isNoOp())
  }
}
