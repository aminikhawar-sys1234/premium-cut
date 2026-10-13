package com.example.engine.effects

import com.example.domain.model.VideoClip
import com.example.engine.effects.media3.CustomShaderGlEffect
import com.example.engine.effects.media3.PreviewVfxEffects
import com.example.engine.effects.registry.EffectCategory
import com.example.engine.effects.registry.EffectsAssetRegistry
import com.example.engine.vfx.VfxEffectsHost
import com.vfx.engine.core.effect.EffectDefinition
import com.vfx.engine.effects.VideoEffects
import com.vfx.engine.gpu.glsl.EffectGlsl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Contract of the ten Video Effects assets: they must be real engine definitions (GLSL body +
 * params), reachable from the catalog, wired into the live preview shader, and they must survive
 * the `vfxStackJson` round-trip the GPU compositor (preview and export) runs every frame.
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
  fun videoCategoryShipsExactlyTenRealAssetsAndNoLeftovers() {
    val assets = videoAssets
    assertEquals(10, assets.size)
    assertEquals(10, assets.map { it.id }.toSet().size)
    assertEquals(10, assets.map { it.shaderKey }.toSet().size)
    // The old assets are gone: three ids disappeared, the two kept ids now point at the new
    // engine implementations instead of the deleted `blur.directional` / `noise.filmGrain` wiring.
    listOf("video.glitch", "video.chromatic", "video.light_leak").forEach { removed ->
      assertNull(removed, ProductionEffectCatalog.byId(removed))
    }
    assertEquals("vfx:blur.motion", ProductionEffectCatalog.byId("video.motion_blur")!!.shaderKey)
    assertEquals("vfx:noise.grain", ProductionEffectCatalog.byId("video.film_grain")!!.shaderKey)
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
    assertEquals(VideoEffects.ids, videoAssets.map { it.shaderKey!!.removePrefix("vfx:") })
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
      EffectGlsl.MOTION_BLUR,
      EffectGlsl.VHS_TAPE,
      EffectGlsl.CRT_SCANLINES,
      EffectGlsl.DATAMOSH,
      EffectGlsl.ANAMORPHIC_STREAK,
      EffectGlsl.FILM_GRAIN_PRO,
      EffectGlsl.HALATION,
      EffectGlsl.LENS_FLARE,
      EffectGlsl.SPEED_LINES,
      EffectGlsl.KALEIDOSCOPE
    )
    assertEquals(10, bodies.toSet().size)
    bodies.forEach { body ->
      assertTrue(body.contains("process("))
      // Reads the incoming frame: either samples the texture itself (blur, streak, bleed …) or
      // works on the `c` the template already sampled (grain, flare, wedge fold).
      assertTrue(body, body.contains("u_tex") || body.contains("c.rgb") || body.contains("return c"))
    }
    assertTrue(EffectGlsl.CRT_SCANLINES.contains("vec2 warpUV"))
    assertTrue(EffectGlsl.KALEIDOSCOPE.contains("vec2 warpUV"))
    // The tube curve and the kaleidoscope are coordinate warps, so they must not fake it in process()
    assertTrue(EffectGlsl.KALEIDOSCOPE.contains("vec4 process(vec4 c, vec2 uv){ return c; }"))
  }

  @Test
  fun applyingAnAssetTunesTheParamsItsShaderReads() {
    val vhs = ProductionEffectCatalog.byId("video.vhs")!!
    val tuned = ProductionEffectApplicator.apply(VideoClip(id = "v", name = "v"), vhs, 1f)
    val inst = VfxEffectsHost.decode(tuned.vfxStackJson).effectAt(0)
    assertEquals("video.vhs", inst.definition.id)
    assertEquals(1f, inst.intensity, 1e-4f)
    assertTrue(inst.getParam<Float>("bleed") > 0f)
    assertTrue(inst.getParam<Float>("tracking") > 0f)
    assertTrue(inst.getParam<Float>("scanline") > 0f)
    assertEquals(1f, ProductionEffectApplicator.intensityOn(tuned, vhs), 1e-4f)
    assertEquals("video.vhs", ProductionEffectApplicator.appliedOn(tuned, EffectCategory.VIDEO_EFFECTS)?.id)
    assertNull(ProductionEffectApplicator.clearCategory(tuned, EffectCategory.VIDEO_EFFECTS).vfxStackJson)
  }

  @Test
  fun aClipWithAllTenAssetsResolvesInTheCompositorStack() {
    val looked = videoAssets.fold(VideoClip(id = "v", name = "v")) { clip, asset ->
      ProductionEffectApplicator.apply(clip, asset, 0.8f)
    }
    val stack = VfxEffectsHost.decode(looked.vfxStackJson)
    assertEquals(10, stack.size)
    assertEquals(VideoEffects.ids, stack.effects().map { it.definition.id })
    // What VfxStackStage.apply() runs every frame on both preview and export.
    val resolved = stack.resolveAt(1_000_000L)
    assertEquals(10, resolved.size)
    resolved.forEach { r ->
      assertNotNull(r.instance.definition.runtimeFactory(r.instance))
      assertTrue(r.snapshot.intensity > 0.01f)
    }
  }

  @Test
  fun everyAssetIsMappedIntoTheLivePreviewShaderGraph() {
    videoAssets.forEach { asset ->
      val looked = ProductionEffectApplicator.apply(VideoClip(id = "v", name = "v"), asset, 0.6f)
      val inst = VfxEffectsHost.decode(looked.vfxStackJson).effectAt(0)
      val effect = PreviewVfxEffects.toMedia3(inst)
      assertNotNull("${asset.id} has no Media3 preview shader", effect)
      val shader = effect as CustomShaderGlEffect
      assertTrue(shader.name, shader.name.startsWith("Preview"))
      assertTrue(
        "${asset.id} does not gate on uIntensity",
        shader.fragmentShader.contains("mix(base.rgb") || shader.fragmentShader.contains("mix(uv, warped")
      )
      assertTrue("${asset.id} does not declare its params", Regex("uniform (float|vec2|vec3|vec4) u[A-Z]").containsMatchIn(shader.fragmentShader))
    }
  }

  @Test
  fun previewAndExportShareTheSameAssetIdSet() {
    val keys = videoAssets.mapNotNull { it.shaderKey }
    val previewIds = keys.map { it.removePrefix("vfx:") }.toSet()
    assertEquals(10, previewIds.size)
    assertEquals(VideoEffects.ids.toSet(), previewIds)
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
