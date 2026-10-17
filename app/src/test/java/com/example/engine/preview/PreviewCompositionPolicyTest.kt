package com.example.engine.preview

import com.example.domain.model.ChromaKeySettings
import com.example.domain.model.Timeline
import com.example.domain.model.VideoClip
import com.example.engine.vfx.VfxEffectsHost
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The preview must draw exactly what the export draws.
 *
 * The reported symptom was that the Chroma Key and Remove BG panels changed nothing on screen: the plain
 * player path cannot run either effect, and the GPU compositor - the preview path that can, and the one the
 * export already used - was behind an opt-in setting that defaults to off. These tests pin the rule that a
 * project which needs a per-pixel effect activates it on its own.
 */
class PreviewCompositionPolicyTest {

  private fun clip(id: String, chroma: ChromaKeySettings? = null, bgRemoved: Boolean = false) =
    VideoClip(id = id, uri = "file:///$id.mp4", name = id, chromaKey = chroma, isBackgroundRemoved = bgRemoved)

  @Test
  fun aProjectWithNoPerPixelEffectStaysOnThePlayerPath() {
    val timeline = Timeline(videoClips = listOf(clip("a")))
    assertFalse(PreviewCompositionPolicy.requiresCompositor(timeline))
    assertFalse(
      PreviewCompositionPolicy.shouldActivate(
        alwaysOn = false, compositorFailed = false, trimPreviewActive = false,
        sourcesPlayable = true, requiresPixelEffects = false
      )
    )
  }

  @Test
  fun chromaKeyOnAnyLayerActivatesTheCompositorWithoutASetting() {
    assertTrue(
      PreviewCompositionPolicy.requiresCompositor(
        Timeline(videoClips = listOf(clip("a", ChromaKeySettings(enabled = true))))
      )
    )
    assertTrue(
      PreviewCompositionPolicy.requiresCompositor(
        Timeline(overlayClips = listOf(clip("o", ChromaKeySettings(enabled = true))))
      )
    )
    // The project default counts too: a clip without its own key is keyed by it.
    assertTrue(
      PreviewCompositionPolicy.requiresCompositor(
        Timeline(videoClips = listOf(clip("a")), chromaKey = ChromaKeySettings(enabled = true))
      )
    )
  }

  @Test
  fun backgroundRemovalActivatesTheCompositorToo() {
    assertTrue(
      PreviewCompositionPolicy.requiresCompositor(Timeline(videoClips = listOf(clip("a", bgRemoved = true))))
    )
  }

  @Test
  fun aClipThatExplicitlyDisablesKeyingDoesNotForceTheCompositor() {
    val timeline = Timeline(
      videoClips = listOf(clip("a", ChromaKeySettings(enabled = false))),
      chromaKey = ChromaKeySettings(enabled = true)
    )
    assertFalse(PreviewCompositionPolicy.requiresCompositor(timeline))
  }

  @Test
  fun theSettingsSwitchAndTheFailureFallbackStillWin() {
    // Opt-in switch on, nothing requires it: compositor runs because the user asked for it.
    assertTrue(
      PreviewCompositionPolicy.shouldActivate(
        alwaysOn = true, compositorFailed = false, trimPreviewActive = false,
        sourcesPlayable = true, requiresPixelEffects = false
      )
    )
    // A previous failure keeps the player path even when the project needs the compositor.
    assertFalse(
      PreviewCompositionPolicy.shouldActivate(
        alwaysOn = true, compositorFailed = true, trimPreviewActive = false,
        sourcesPlayable = true, requiresPixelEffects = true
      )
    )
    // Trimming owns the player, and an unplayable layer cannot be composited.
    assertFalse(
      PreviewCompositionPolicy.shouldActivate(
        alwaysOn = false, compositorFailed = false, trimPreviewActive = true,
        sourcesPlayable = true, requiresPixelEffects = true
      )
    )
    assertFalse(
      PreviewCompositionPolicy.shouldActivate(
        alwaysOn = false, compositorFailed = false, trimPreviewActive = false,
        sourcesPlayable = false, requiresPixelEffects = true
      )
    )
  }

  @Test
  fun explainNamesTheGateThatDecided() {
    val keyed = Timeline(videoClips = listOf(clip("a", ChromaKeySettings(enabled = true))))
    assertTrue(
      PreviewCompositionPolicy.explain(keyed, false, false, false, true).contains("chroma key", ignoreCase = true)
    )
    assertTrue(
      PreviewCompositionPolicy.explain(Timeline(videoClips = listOf(clip("a"))), false, false, false, true)
        .contains("Player path")
    )
    assertTrue(
      PreviewCompositionPolicy.explain(keyed, false, true, false, true).contains("failure")
    )
  }

  @Test
  fun explainDistinguishesTheCoverageGateFromChromaKey() {
    // No chroma key anywhere: the compositor is on because of a coverage-only effect
    // (blur.gaussian has no Media3 preview shader), and the explanation must say so.
    val stackJson = VfxEffectsHost.addEffect(null, "blur.gaussian")
    val covered = Timeline(videoClips = listOf(clip("a").copy(vfxStackJson = stackJson)))
    val explanation = PreviewCompositionPolicy.explain(covered, false, false, false, true)
    assertTrue(explanation.contains("cannot draw"))
    assertFalse(explanation.contains("chroma key", ignoreCase = true))
    // Both gates together are named together.
    val both = covered.copy(chromaKey = ChromaKeySettings(enabled = true))
    assertTrue(
      PreviewCompositionPolicy.explain(both, false, false, false, true)
        .contains("chroma key and an effect the player path cannot draw")
    )
  }
}
