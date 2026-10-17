package com.example.engine.preview

import com.ahstudio.color.core.ColorState
import com.ahstudio.color.core.LutState
import com.ahstudio.color.serialize.ColorStateCodec
import com.ahstudio.face.deformation.DeformationCodec
import com.ahstudio.face.deformation.DeformationParams
import com.example.domain.model.EffectClip
import com.example.domain.model.FilterSettings
import com.example.domain.model.Timeline
import com.example.domain.model.VideoAdjustments
import com.example.domain.model.VideoClip
import com.example.engine.effects.BodyReshapeCodec
import com.example.engine.effects.BodyReshapeParams
import com.example.engine.effects.media3.PreviewVfxCoverage
import com.example.engine.vfx.VfxEffectsHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the widened preview/export parity rule: every effect the plain player path cannot draw
 * must flip [PreviewCompositionPolicy.requiresCompositor] (through [PreviewCoveragePolicy]), so
 * applying it from the effects panel is visible in the live preview immediately — not only in
 * the export. Chroma key / background removal keep their own (already tested) rule.
 */
class PreviewCoveragePolicyTest {

  private fun clip(id: String) =
    VideoClip(id = id, uri = "file:///$id.mp4", name = id)

  @Test
  fun aPlainProjectStaysOnThePlayerPath() {
    assertFalse(PreviewCoveragePolicy.requiresComposition(Timeline(videoClips = listOf(clip("a")))))
  }

  @Test
  fun aRegistryVfxWithoutAMedia3PreviewShaderRequiresComposition() {
    // blur.gaussian has no Media3 preview shader; it renders only in the compositor / export.
    val stackJson = VfxEffectsHost.addEffect(null, "blur.gaussian")
    assertTrue(PreviewVfxCoverage.hasUnrepresentableEffects(stackJson))
    assertTrue(
      PreviewCoveragePolicy.requiresComposition(
        Timeline(videoClips = listOf(clip("a").copy(vfxStackJson = stackJson)))
      )
    )
  }

  @Test
  fun anyActiveVfxStackRequiresCompositionForPreviewExportParity() {
    // The compositor is the pipeline the export runs, so EVERY active stack - including ids the
    // light player path can draw - previews through it. The old split (representable ids on the
    // player path) let a failing Media3 graph show no effect at all while the export drew it.
    val stackJson = VfxEffectsHost.addEffect(null, "noise.grain")
    assertTrue(PreviewVfxCoverage.hasAnyActiveEffects(stackJson))
    assertTrue(
      PreviewCoveragePolicy.requiresComposition(
        Timeline(videoClips = listOf(clip("a").copy(vfxStackJson = stackJson)))
      )
    )
  }

  @Test
  fun hasAnyActiveEffectsIgnoresDisabledAndZeroIntensityEntries() {
    val stackJson = VfxEffectsHost.addEffect(null, "noise.grain")
    val stack = VfxEffectsHost.decode(stackJson)
    stack.effectAt(0).intensity = 0f
    val zeroJson = VfxEffectsHost.encode(stack)
    assertFalse(PreviewVfxCoverage.hasAnyActiveEffects(zeroJson))
    stack.effectAt(0).enabled = false
    val disabledJson = VfxEffectsHost.encode(stack)
    assertFalse(PreviewVfxCoverage.hasAnyActiveEffects(disabledJson))
  }

  @Test
  fun disabledOrZeroIntensityEffectsNeverForceTheCompositor() {
    val stackJson = VfxEffectsHost.addEffect(null, "blur.gaussian")
    val stack = VfxEffectsHost.decode(stackJson)
    stack.effectAt(0).enabled = false
    val disabledJson = VfxEffectsHost.encode(stack)
    assertFalse(PreviewVfxCoverage.hasUnrepresentableEffects(disabledJson))
  }

  @Test
  fun faceAndBodyReshapeRequireComposition() {
    val faceJson = DeformationCodec.encode(DeformationParams(eyeEnlarge = 0.4f))
    assertTrue(
      PreviewCoveragePolicy.requiresComposition(
        Timeline(videoClips = listOf(clip("a").copy(faceReshape = faceJson)))
      )
    )
    val bodyJson = BodyReshapeCodec.encode(BodyReshapeParams(waist = 0.5f))
    assertTrue(
      PreviewCoveragePolicy.requiresComposition(
        Timeline(videoClips = listOf(clip("a").copy(bodyReshape = bodyJson)))
      )
    )
  }

  @Test
  fun aClearedFaceReshapeDoesNotForceTheCompositor() {
    // clearCategory writes an all-zero DeformationParams JSON (not null) — it must not
    // keep the compositor switched on forever.
    val cleared = DeformationCodec.encode(DeformationParams())
    assertFalse(
      PreviewCoveragePolicy.requiresComposition(
        Timeline(videoClips = listOf(clip("a").copy(faceReshape = cleared)))
      )
    )
  }

  @Test
  fun arOverlaysRequireComposition() {
    assertTrue(
      PreviewCoveragePolicy.requiresComposition(
        Timeline(videoClips = listOf(clip("a").copy(arOverlay = "{}")))
      )
    )
  }

  @Test
  fun aColourGradeBeyondALutRequiresComposition() {
    val gradeJson = ColorStateCodec.encode(ColorState(exposure = 0.5f))
    assertTrue(
      PreviewCoveragePolicy.requiresComposition(
        Timeline(videoClips = listOf(clip("a").copy(colorGradeJson = gradeJson)))
      )
    )
  }

  @Test
  fun aLutOnlyGradeStaysOnThePlayerPath() {
    val lutOnly = ColorStateCodec.encode(ColorState(lut = LutState(lutId = "teal_orange")))
    assertFalse(
      PreviewCoveragePolicy.requiresComposition(
        Timeline(videoClips = listOf(clip("a").copy(colorGradeJson = lutOnly)))
      )
    )
  }

  @Test
  fun aClipLocalLookOnAnOverlayRequiresComposition() {
    val overlay = clip("o").copy(
      adjustments = VideoAdjustments(brightness = 0.2f),
      filter = FilterSettings(type = com.example.domain.model.FilterType.GLOW)
    )
    assertTrue(PreviewCoveragePolicy.requiresComposition(Timeline(overlayClips = listOf(overlay))))
  }

  @Test
  fun anOverlayCarryingOnlyDefaultLookValuesStaysOnThePlayerPath() {
    // A reset writes the defaults back onto the clip — that is not a look.
    val overlay = clip("o").copy(
      adjustments = VideoAdjustments(),
      filter = FilterSettings()
    )
    assertFalse(PreviewCoveragePolicy.requiresComposition(Timeline(overlayClips = listOf(overlay))))
  }

  @Test
  fun trackedEffectClipsRequireComposition() {
    assertTrue(
      PreviewCoveragePolicy.requiresComposition(
        Timeline(effectClips = listOf(EffectClip(id = "fx1")))
      )
    )
  }

  @Test
  fun theMedia3PreviewIdSetMatchesThePreviewShadersOneToOne() {
    // The set must stay in sync with PreviewVfxEffects.toMedia3: every id the when() handles
    // is listed, and nothing outside the registry leaks in.
    val registryIds = VfxEffectsHost.catalog().map { it.id }.toSet()
    assertEquals(
      PreviewVfxCoverage.MEDIA3_PREVIEW_EFFECT_IDS.intersect(registryIds),
      PreviewVfxCoverage.MEDIA3_PREVIEW_EFFECT_IDS
    )
    assertTrue(
      "blur.gaussian must NOT be Media3-preview representable",
      "blur.gaussian" !in PreviewVfxCoverage.MEDIA3_PREVIEW_EFFECT_IDS
    )
  }
}
