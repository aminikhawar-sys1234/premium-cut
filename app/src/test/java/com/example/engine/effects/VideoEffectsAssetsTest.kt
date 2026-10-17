package com.example.engine.effects

import com.example.domain.model.VideoClip
import com.example.engine.effects.media3.PreviewVfxCoverage
import com.example.engine.effects.registry.EffectCategory
import com.example.engine.effects.registry.EffectsAssetRegistry
import com.example.engine.vfx.VfxEffectsHost
import com.vfx.engine.core.effect.EffectDefinition
import com.vfx.engine.effects.TrendEffects
import com.vfx.engine.gpu.glsl.TrendGlsl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Contract of the Video Effects assets: they must be real engine definitions (GLSL body +
 * params), reachable from the catalog, routed to the GPU compositor preview, and they must
 * survive the `vfxStackJson` round-trip the compositor (preview and export) runs every frame.
 */
@RunWith(RobolectricTestRunner::class)
class VideoEffectsAssetsTest {

  private val videoAssets: List<com.example.engine.effects.registry.RegisteredEffect>
    get() = ProductionEffectCatalog.effectsIn(EffectCategory.VIDEO_EFFECTS)

  @Before
  fun setUp() {
    EffectsAssetRegistry.clearAll()
    ProductionEffectCatalog.install()
  }

  @Test
  fun videoCategoryShipsExactlyNineRealAssetsAndNoLeftovers() {
    val assets = videoAssets
    assertEquals(9, assets.size)
    assertEquals(9, assets.map { it.id }.toSet().size)
    assertEquals(9, assets.map { it.shaderKey }.toSet().size)
    // The previous generation is gone from the catalog.
    listOf(
      "video.vhs", "video.crt", "video.datamosh", "video.anamorphic", "video.film_grain",
      "video.halation", "video.lens_flare", "video.speed_lines", "video.kaleidoscope",
    ).forEach { removed -> assertNull(removed, ProductionEffectCatalog.byId(removed)) }
    // Motion Blur kept its engine binding.
    assertEquals("vfx:blur.motion", ProductionEffectCatalog.byId("video.motion_blur")!!.shaderKey)
    assets.forEach { asset ->
      val key = asset.shaderKey
      assertNotNull(asset.id, key)
      assertTrue(asset.id, key!!.startsWith("vfx:"))
      assertEquals(asset.id, "intensity", asset.parameters.first())
      assertTrue(asset.id, asset.supportsPreview)
      assertTrue(asset.id, asset.supportsExport)
      assertTrue(asset.id, asset.intensity > 0f)
    }
  }

  @Test
  fun everyAssetKeyResolvesToARegisteredEngineDefinition() {
    assertTrue(
      "skipped: ${VfxEffectsHost.registrationSkipped}",
      VfxEffectsHost.registrationSkipped.isEmpty()
    )
    val trendKeys = videoAssets.mapNotNull { it.shaderKey }
      .filter { it != "vfx:blur.motion" }
      .map { it.removePrefix("vfx:") }
    assertEquals(TrendEffects.ids, trendKeys)
    videoAssets.forEach { asset ->
      val id = asset.shaderKey!!.removePrefix("vfx:")
      val def: EffectDefinition? = VfxEffectsHost.registry.getEffectOrNull(id)
      assertNotNull("$id is not registered", def)
      assertTrue("$id has no params", def!!.params.isNotEmpty())
      assertTrue("$id has no description", def.description.isNotBlank())
    }
  }

  @Test
  fun everyAssetHasItsOwnGlslBodyAndTheWarpEffectsDeclareWarpUv() {
    val bodies = listOf(
      TrendGlsl.EXPLOSION,
      TrendGlsl.INK_SPILL,
      TrendGlsl.EARTH,
      TrendGlsl.MAGICAL_TOME,
      TrendGlsl.CUT_SHIFT,
      TrendGlsl.FIRE,
      TrendGlsl.MINI_ZOOM,
      TrendGlsl.WHITE_FLASH,
    )
    assertEquals(8, bodies.toSet().size)
    bodies.forEach { body ->
      assertTrue(body.contains("process("))
      assertTrue(body, body.contains("u_tex") || body.contains("c.rgb") || body.contains("return c"))
    }
    // Coordinate warps must be real warpUV bodies, not faked in process().
    assertTrue(TrendGlsl.CUT_SHIFT.contains("vec2 warpUV"))
    assertTrue(TrendGlsl.FIRE.contains("vec2 warpUV"))
    assertTrue(TrendGlsl.MINI_ZOOM.contains("vec2 warpUV"))
  }

  @Test
  fun applyingAnAssetTunesTheParamsItsShaderReads() {
    val shift = ProductionEffectCatalog.byId("video.cut_shift")!!
    val tuned = ProductionEffectApplicator.apply(VideoClip(id = "v", name = "v"), shift, 1f)
    val inst = VfxEffectsHost.decode(tuned.vfxStackJson).effectAt(0)
    assertEquals("trend.cut_shift", inst.definition.id)
    assertEquals(1f, inst.intensity, 1e-4f)
    assertTrue(inst.getParam<Float>("rgb") > 0f)
    assertTrue(inst.getParam<Float>("speed") > 0f)
    assertEquals(1f, ProductionEffectApplicator.intensityOn(tuned, shift), 1e-4f)
    assertEquals("video.cut_shift", ProductionEffectApplicator.appliedOn(tuned, EffectCategory.VIDEO_EFFECTS)?.id)
    assertNull(ProductionEffectApplicator.clearCategory(tuned, EffectCategory.VIDEO_EFFECTS).vfxStackJson)
  }

  @Test
  fun aClipWithAllNineAssetsResolvesInTheCompositorStack() {
    val looked = videoAssets.fold(VideoClip(id = "v", name = "v")) { clip, asset ->
      ProductionEffectApplicator.apply(clip, asset, 0.8f)
    }
    val stack = VfxEffectsHost.decode(looked.vfxStackJson)
    assertEquals(9, stack.size)
    val resolved = stack.resolveAt(1_000_000L)
    assertEquals(9, resolved.size)
    resolved.forEach { r ->
      assertNotNull(r.instance.definition.runtimeFactory(r.instance))
      assertTrue(r.snapshot.intensity > 0.01f)
    }
  }

  @Test
  fun everyAssetRoutesTheClipToTheCompositorPreview() {
    // The whole point of the R11 parity rule: an active stack never stays on the Media3 player
    // path, so what the user sees while editing is the exact pipeline the export runs.
    videoAssets.forEach { asset ->
      val looked = ProductionEffectApplicator.apply(VideoClip(id = "v", name = "v"), asset, 0.6f)
      assertTrue(
        "${asset.id} does not route to the compositor",
        PreviewVfxCoverage.hasAnyActiveEffects(looked.vfxStackJson)
      )
    }
  }

  @Test
  fun previewAndExportShareTheSameAssetIdSet() {
    val keys = videoAssets.mapNotNull { it.shaderKey }
    val previewIds = keys.map { it.removePrefix("vfx:") }.toSet()
    assertEquals(9, previewIds.size)
    assertEquals(TrendEffects.ids.toSet() + "blur.motion", previewIds)
    // Nothing in the catalog may point at an id the engine does not know.
    ProductionEffectCatalog.effects.forEach { effect ->
      val key = effect.shaderKey ?: return@forEach
      if (!key.startsWith("vfx:")) return@forEach
      assertNotNull(
        "${effect.id} -> $key is not a registered definition",
        VfxEffectsHost.registry.getEffectOrNull(key.removePrefix("vfx:"))
      )
    }
  }
}
