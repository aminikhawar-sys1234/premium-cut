package com.example.engine.effects

import com.example.domain.model.VideoClip
import com.example.engine.effects.media3.PreviewVfxCoverage
import com.example.engine.effects.registry.EffectCategory
import com.example.engine.effects.registry.EffectsAssetRegistry
import com.example.engine.vfx.VfxEffectsHost
import com.vfx.engine.effects.SubjectEffects
import com.vfx.engine.effects.SubjectMaskRuntime
import com.vfx.engine.effects.SubjectVfx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Contract of the subject-driven assets (Body Effects + AI character restyles): real engine
 * definitions rendering through SubjectMaskRuntime, persisted on the clip's vfx stack, routed to
 * the GPU compositor, and gated by the on-device segmentation mask (SubjectVfx.referenced drives
 * the compositor's mask publication).
 */
@RunWith(RobolectricTestRunner::class)
class SubjectEffectsTest {

  @Before
  fun setUp() {
    EffectsAssetRegistry.clearAll()
    ProductionEffectCatalog.install()
  }

  @Test
  fun bodyAndAiCategoriesShipExactlyNineAndFourSubjectAssets() {
    val body = ProductionEffectCatalog.effectsIn(EffectCategory.BODY_EFFECTS)
    val ai = ProductionEffectCatalog.effectsIn(EffectCategory.AI_EFFECTS)
    assertEquals(9, body.size)
    assertEquals(4, ai.size)
    (body + ai).forEach { asset ->
      assertTrue(asset.id, asset.shaderKey!!.startsWith("vfx:subject."))
      assertEquals(asset.id, "intensity", asset.parameters.first())
      assertTrue(asset.id, asset.supportsPreview && asset.supportsExport)
    }
    // The engine ids match the definitions one-to-one, in catalog order.
    assertEquals(SubjectEffects.ids, (body + ai).map { it.shaderKey!!.removePrefix("vfx:") })
  }

  @Test
  fun everySubjectAssetIsAMaskRuntimeDefinition() {
    SubjectEffects.all().forEach { def ->
      val registered = VfxEffectsHost.registry.getEffectOrNull(def.id)
      assertNotNull("${def.id} is not registered", registered)
      assertTrue("${def.id} has no description", registered!!.description.isNotBlank())
      val runtime = registered.runtimeFactory(VfxEffectsHost.registry.createEffect(registered.id))
      assertTrue("${def.id} must render through SubjectMaskRuntime", runtime is SubjectMaskRuntime)
    }
    assertTrue(VfxEffectsHost.registrationSkipped.isEmpty())
  }

  @Test
  fun subjectEffectsPersistOnTheVfxStackAndRouteToTheCompositor() {
    val clip = VideoClip(id = "v", name = "v")
    val body = ProductionEffectCatalog.byId("body.optical_scan")!!
    val ai = ProductionEffectCatalog.byId("ai.golden_statue")!!
    val looked = ProductionEffectApplicator.apply(
      ProductionEffectApplicator.apply(clip, body, 0.8f),
      ai,
      0.9f
    )
    val stack = VfxEffectsHost.decode(looked.vfxStackJson)
    assertEquals(2, stack.size)
    assertEquals("subject.optical_scan", stack.effectAt(0).definition.id)
    assertEquals("subject.clone_golden", stack.effectAt(1).definition.id)
    assertEquals(0.8f, stack.effectAt(0).intensity, 1e-4f)
    // Both preview routing and the compositor's mask publication key off the same marker.
    assertTrue(SubjectVfx.referenced(looked.vfxStackJson))
    assertTrue(PreviewVfxCoverage.hasAnyActiveEffects(looked.vfxStackJson))
  }

  @Test
  fun subjectVfxMarkerIgnoresFullFrameStacks() {
    val json = VfxEffectsHost.addEffect(null, "trend.explosion")
    assertTrue(!SubjectVfx.referenced(json))
    val subjectJson = VfxEffectsHost.addEffect(null, "subject.optical_scan")
    assertTrue(SubjectVfx.referenced(subjectJson))
    assertTrue(!SubjectVfx.referenced(null))
    assertTrue(!SubjectVfx.referenced(""))
  }

  @Test
  fun pixelMaskAssetTunesItsBlockCount() {
    val asset = ProductionEffectCatalog.byId("body.pixel_mask")!!
    val soft = ProductionEffectApplicator.apply(VideoClip(id = "v", name = "v"), asset, 0.2f)
    val hard = ProductionEffectApplicator.apply(VideoClip(id = "v", name = "v"), asset, 1f)
    val softBlocks = VfxEffectsHost.decode(soft.vfxStackJson).effectAt(0).getParam<Float>("blocks")
    val hardBlocks = VfxEffectsHost.decode(hard.vfxStackJson).effectAt(0).getParam<Float>("blocks")
    assertTrue("lower intensity must keep more (smaller) blocks", softBlocks > hardBlocks)
    assertEquals(
      0.2f,
      ProductionEffectApplicator.intensityOn(soft, asset),
      1e-4f
    )
  }

  @Test
  fun clearingBodyLeavesAiAndViceVersa() {
    val clip = VideoClip(id = "v", name = "v")
    val looked = ProductionEffectApplicator.apply(
      ProductionEffectApplicator.apply(clip, ProductionEffectCatalog.byId("body.optical_scan")!!, 0.8f),
      ProductionEffectCatalog.byId("ai.shadow_clone")!!,
      0.8f
    )
    val noBody = ProductionEffectApplicator.clearCategory(looked, EffectCategory.BODY_EFFECTS)
    assertEquals(
      listOf("subject.clone_shadow"),
      VfxEffectsHost.decode(noBody.vfxStackJson).effects().map { it.definition.id }
    )
    val noAi = ProductionEffectApplicator.clearCategory(noBody, EffectCategory.AI_EFFECTS)
    assertEquals(null, noAi.vfxStackJson)
  }
}
