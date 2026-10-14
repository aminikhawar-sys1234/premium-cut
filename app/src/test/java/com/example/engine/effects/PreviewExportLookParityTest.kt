package com.example.engine.effects

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.example.domain.model.FilterSettings
import com.example.domain.model.FilterType
import com.example.domain.model.VideoAdjustments
import com.example.engine.effects.media3.ColorGradingGlEffect
import com.example.engine.effects.media3.Media3EffectPipeline
import com.example.engine.effects.media3.ColorGradingSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The mandatory "export uses the same effective filter and adjustment parameters as preview"
 * test, at the level where it can be checked without a GPU.
 *
 * Preview resolves a look through [ColorGradingGlEffect.fromTimeline] ->
 * [ColorLookModel.effectiveWithAndroidMatrix]. Export resolves it inside
 * `GpuCompositionRenderer.bindCommonUniforms` -> [ColorLookModel.effective] +
 * [ColorLookModel.filterGlMatrix]. Both must produce byte-identical uniforms for the same
 * clip state, otherwise the exported file cannot match the editor.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreviewExportLookParityTest {

  /**
   * Reproduces the export renderer's resolution step (`GpuCompositionRenderer.bindCommonUniforms`):
   * preset matrix (or an effect-clip matrix when the clip has one) -> GL 4x4 + offset ->
   * [ColorLookModel.effective].
   */
  private fun exportLook(adj: VideoAdjustments, filter: FilterSettings): ColorLookModel.LookParams {
    val pair = ColorLookModel.presetMatrix(filter)?.let { ColorLookModel.filterGlMatrix(it) }
    return ColorLookModel.effective(
      adjustments = adj,
      filter = filter,
      filterMatrix = pair?.first,
      filterOffset = pair?.second
    )
  }

  private val cases: List<Pair<VideoAdjustments, FilterSettings>> = listOf(
    VideoAdjustments() to FilterSettings(),
    VideoAdjustments(brightness = 0.35f, contrast = 1.4f, saturation = 0.8f) to FilterSettings(),
    VideoAdjustments(exposure = 0.5f, temperature = -0.6f, tint = 0.3f) to FilterSettings(),
    VideoAdjustments(highlights = -0.7f, shadows = 0.6f, whites = 0.4f, blacks = -0.3f) to FilterSettings(),
    VideoAdjustments(autoEnhance = 0.7f, hdrBoost = 0.5f, colorCorrect = 0.4f) to FilterSettings(),
    VideoAdjustments(denoise = 0.6f, antiFlicker = 0.8f, colorFix = 0.5f) to FilterSettings(),
    VideoAdjustments(sharpness = 0.8f, clarity = 0.6f, superClarity = 0.4f) to FilterSettings(),
    VideoAdjustments(vignette = 0.5f, grain = 0.4f, fade = 0.3f) to FilterSettings(),
    VideoAdjustments() to FilterSettings(FilterType.GOLDEN_AUTUMN, 1f),
    VideoAdjustments() to FilterSettings(FilterType.BLACKLIGHT_FIX, 0.65f),
    VideoAdjustments() to FilterSettings(FilterType.HDR, 0.8f),
    VideoAdjustments() to FilterSettings(FilterType.ENHANCE, 0.45f),
    VideoAdjustments() to FilterSettings(FilterType.GLOW, 1f),
    VideoAdjustments() to FilterSettings(FilterType.FOUR_K, 0.9f),
    VideoAdjustments(brightness = 0.2f, saturation = 1.3f, sharpness = 0.5f) to
      FilterSettings(FilterType.ENHANCE, 0.7f),
    VideoAdjustments() to FilterSettings(FilterType.GOLDEN_AUTUMN, 1f, enabled = false)
  )

  @Test
  fun previewAndExportResolveIdenticalUniforms() {
    for ((adj, filter) in cases) {
      val previewLook = ColorGradingSnapshot.from(adj, filter).lookParams()
      val exportLook = exportLook(adj, filter)
      assertTrue(
        "preview and export must agree for ${filter.type}/${filter.intensity}",
        ColorLookModel.sameUniforms(previewLook, exportLook)
      )
    }
  }

  @Test
  fun previewAndExportUploadTheSamePresetMatrix() {
    for ((_, filter) in cases) {
      if (filter.type == FilterType.NONE || !filter.enabled) continue
      val previewMatrix = ColorGradingSnapshot.from(VideoAdjustments(), filter).colorMatrix4x4
      val exportMatrix = exportLook(VideoAdjustments(), filter).colorMatrix
      if (previewMatrix == null) {
        assertTrue("no matrix expected for ${filter.type}", exportMatrix == null)
        continue
      }
      assertTrue("${filter.type} matrix must match", previewMatrix.contentEquals(exportMatrix))
      val previewOffset = ColorGradingSnapshot.from(VideoAdjustments(), filter).colorOffset!!
      val exportOffset = exportLook(VideoAdjustments(), filter).colorOffset!!
      assertTrue("${filter.type} offset must match", previewOffset.contentEquals(exportOffset))
    }
  }

  @Test
  fun aNeutralLookIsNeutralForBothPaths() {
    for ((adj, filter) in cases) {
      val isNeutralCase = adj == VideoAdjustments() &&
        (filter.type == FilterType.NONE || !filter.enabled || filter.intensity <= 0.01f)
      val preview = ColorGradingSnapshot.from(adj, filter)
      assertEquals(
        "neutrality must agree for ${filter.type}/${filter.intensity}",
        isNeutralCase, preview.isNoOp()
      )
      assertEquals(isNeutralCase, exportLook(adj, filter).isNeutral())
    }
  }

  @Test
  fun thePreviewKeepsOneResidentGradingNodeForEveryLook() {
    // A look must never add or remove Media3 nodes: rebuilding the graph on every slider
    // tick is what dropped the TextureView, produced black frames and stopped source audio.
    // The node is resident even when the look is neutral (identity uniforms = plain copy).
    for ((adj, filter) in cases) {
      val effects = Media3EffectPipeline.buildRealtimePreviewEffects(
        adjustments = adj,
        filterSettings = filter
      )
      val grading = effects.filterIsInstance<ColorGradingGlEffect>()
      assertEquals(
        "exactly one resident grading node for ${filter.type}/${filter.intensity}",
        1, grading.size
      )
      // The resident node must always carry the resolved look, never a stale default.
      assertTrue(
        "the resident node must hold the clip's look",
        ColorLookModel.sameUniforms(exportLook(adj, filter), grading.single().constructorSnapshot().lookParams())
      )
    }
  }

  @Test
  fun aNeutralLookAddsNoMedia3NodeOnTheTransformerExportPath() {
    // buildVideoEffects (Transformer-based export) is the one place that may legally skip the
    // grading node, and only when the resolved look is neutral. It must use the epsilon-based
    // neutrality test, not exact float equality, or a chained postConcat residue keeps a
    // no-op node in the graph (and, worse, used to drop a real one).
    for ((adj, filter) in cases) {
      val effect = ColorGradingGlEffect.fromTimeline(adj, filter)
      val neutral = exportLook(adj, filter).isNeutral()
      assertEquals("neutrality must agree for ${filter.type}", neutral, effect.isIdentityLook())
    }
  }
}
