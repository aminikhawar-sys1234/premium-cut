package com.example.engine.effects

import com.ahstudio.face.deformation.DeformationCodec
import com.example.data.local.TimelineSerializer
import com.example.domain.model.Timeline
import com.example.domain.model.VideoClip
import com.example.engine.SelectedTrackElement
import com.example.engine.TimelineEngine
import com.example.engine.ai.cutout.BgRemoveCodec
import com.example.engine.ai.cutout.BgRemoveParams
import com.example.engine.effects.registry.EffectCategory
import com.example.engine.effects.registry.EffectsAssetRegistry
import com.example.engine.vfx.VfxEffectsHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProductionEffectsTest {

  @Before
  fun setUp() {
    EffectsAssetRegistry.clearAll()
    ProductionEffectCatalog.install()
    ProductionEffectCatalog.install()
  }

  @Test
  fun catalogHasTwentyUniqueRealEffects() {
    val all = ProductionEffectCatalog.effects
    assertEquals(20, all.size)
    assertEquals(20, all.map { it.id }.toSet().size)
    assertEquals(5, all.count { it.category == EffectCategory.VIDEO_EFFECTS })
    assertEquals(5, all.count { it.category == EffectCategory.FACE_EFFECTS })
    assertEquals(5, all.count { it.category == EffectCategory.BODY_EFFECTS })
    assertEquals(5, all.count { it.category == EffectCategory.PHOTO_EFFECTS })
    all.forEach { effect ->
      assertTrue(effect.id, effect.shaderKey?.isNotBlank() == true)
      assertTrue(effect.parameters.contains("intensity"))
      assertTrue(effect.supportsPreview)
      assertTrue(effect.supportsExport)
      assertEquals(null, effect.effectType)
    }
    assertEquals(20, EffectsAssetRegistry.getAllEffects().size)
    assertEquals(5, EffectsAssetRegistry.getEffects(EffectCategory.FACE_EFFECTS).size)
  }

  @Test
  fun videoEffectsUpsertIntoTheRealVfxStack() {
    val clip = VideoClip(id = "v", name = "v")
    val glitch = ProductionEffectCatalog.byId("video.glitch")!!
    val grain = ProductionEffectCatalog.byId("video.film_grain")!!
    val once = ProductionEffectApplicator.apply(clip, glitch, 0.8f)
    val twice = ProductionEffectApplicator.apply(once, glitch, 0.4f)
    val both = ProductionEffectApplicator.apply(twice, grain, 1f)
    val stack = VfxEffectsHost.decode(both.vfxStackJson)
    assertEquals(2, stack.size)
    assertEquals("distort.glitch", stack.effectAt(0).definition.id)
    assertEquals(0.4f, stack.effectAt(0).intensity, 1e-4f)
    assertEquals(0.012f + 0.045f * 0.4f, stack.effectAt(0).getParam<Float>("amount"), 1e-4f)
    assertEquals("noise.filmGrain", stack.effectAt(1).definition.id)
    listOf("blur.directional", "chromatic.aberration", "light.leak", "color.hdr", "color.pop", "sharpen.unsharp").forEach { id ->
      assertNotNull(VfxEffectsHost.registry.createEffect(id))
    }
    assertTrue(VfxEffectsHost.registrationSkipped.isEmpty())
  }

  @Test
  fun faceBodyAndBlurWriteTheFieldsTheCompositorReads() {
    val clip = VideoClip(id = "v", name = "v")
    val slim = ProductionEffectApplicator.apply(clip, ProductionEffectCatalog.byId("face.slim")!!, 0.7f)
    val face = ProductionEffectApplicator.apply(slim, ProductionEffectCatalog.byId("face.eyes")!!, 0.4f)
    val skin = ProductionEffectApplicator.apply(face, ProductionEffectCatalog.byId("face.skin")!!, 0.5f)
    val teeth = ProductionEffectApplicator.apply(skin, ProductionEffectCatalog.byId("face.teeth")!!, 0.6f)
    val decoded = DeformationCodec.decode(teeth.faceReshape)
    assertEquals(0.7f, decoded.faceSlim, 1e-4f)
    assertEquals(0.4f, decoded.eyeEnlarge, 1e-4f)
    assertEquals(0.5f, decoded.skinSmooth, 1e-4f)
    assertEquals(0.6f, decoded.teethWhiten, 1e-4f)

    val body = ProductionEffectApplicator.apply(teeth, ProductionEffectCatalog.byId("body.waist")!!, 0.8f)
    assertEquals(0.8f, BodyReshapeCodec.decode(body.bodyReshape).waist, 1e-4f)

    val portrait = ProductionEffectApplicator.apply(body, ProductionEffectCatalog.byId("photo.portrait_blur")!!, 1f)
    assertTrue(portrait.isBackgroundRemoved)
    assertEquals(BgRemoveParams.MODE_BLUR, BgRemoveCodec.decode(portrait.bgRemove).mode)

    val cleared = ProductionEffectApplicator.clearCategory(portrait, EffectCategory.VIDEO_EFFECTS)
    assertEquals(portrait.faceReshape, cleared.faceReshape)
    assertEquals(portrait.bodyReshape, cleared.bodyReshape)
    val noFace = ProductionEffectApplicator.clearCategory(cleared, EffectCategory.FACE_EFFECTS)
    assertNull(noFace.faceReshape)
    assertEquals(portrait.bodyReshape, noFace.bodyReshape)
    val noBlur = ProductionEffectApplicator.clearCategory(noFace, EffectCategory.PHOTO_EFFECTS)
    assertTrue(!noBlur.isBackgroundRemoved)
  }

  @Test
  fun reshapePresetsEncodeGeometricSliders() {
    val clip = ProductionEffectApplicator.apply(
      VideoClip(id = "v", name = "v"),
      ProductionEffectCatalog.byId("face.reshape")!!,
      1f
    )
    val d = DeformationCodec.decode(clip.faceReshape)
    assertTrue(d.eyeEnlarge > 0f && d.faceSlim > 0f && d.jawSharp > 0f)
    val shaped = ProductionEffectApplicator.apply(clip, ProductionEffectCatalog.byId("body.reshape")!!, 0.5f)
    val legs = ProductionEffectApplicator.apply(shaped, ProductionEffectCatalog.byId("body.legs")!!, 0.3f)
    val shoulders = ProductionEffectApplicator.apply(legs, ProductionEffectCatalog.byId("body.shoulders")!!, 0.2f)
    val proportions = ProductionEffectApplicator.apply(shoulders, ProductionEffectCatalog.byId("body.proportions")!!, 0.4f)
    val b = BodyReshapeCodec.decode(proportions.bodyReshape)
    assertEquals(0.5f, b.reshape, 1e-4f)
    assertEquals(0.3f, b.legs, 1e-4f)
    assertEquals(0.2f, b.shoulders, 1e-4f)
    assertEquals(0.4f, b.proportions, 1e-4f)
  }

  @Test
  fun thumbnailsDifferFromTheUnprocessedFrame() {
    val base = EffectThumbnailRaster.argb(null, 48)
    ProductionEffectCatalog.effects.forEach { effect ->
      val pixels = EffectThumbnailRaster.argb(effect.shaderKey, 48)
      val changed = pixels.indices.count { pixels[it] != base[it] }
      assertTrue("${effect.id} thumbnail changed $changed pixels", changed > 8)
    }
  }

  @Test
  fun effectsSurviveThreeSplitsUndoAndProjectReopen() {
    val engine = TimelineEngine()
    val source = VideoClip(
      id = "A",
      name = "A",
      uri = "file:///A.mp4",
      timelineStartMs = 0L,
      durationMs = 10_000L,
      sourceStartMs = 0L,
      sourceEndMs = 10_000L,
      sourceTotalDurationMs = 60_000L,
    )
    engine.loadTimeline(Timeline(videoClips = listOf(source)))
    val looked = ProductionEffectCatalog.effects
      .filter { it.category != EffectCategory.PHOTO_EFFECTS }
      .fold(source) { clip, effect -> ProductionEffectApplicator.apply(clip, effect, 0.75f) }
      .let { ProductionEffectApplicator.apply(it, ProductionEffectCatalog.byId("photo.hdr")!!, 0.9f) }
      .let { ProductionEffectApplicator.apply(it, ProductionEffectCatalog.byId("photo.background_blur")!!, 0.8f) }
    assertTrue(engine.setClipProductionLook("A", looked))
    val before = engine.timeline.value.videoClips.first()
    engine.selectElement(SelectedTrackElement.Video("A"))
    engine.setPosition(4_000, snap = false)
    assertTrue(engine.splitAtPlayhead())
    engine.selectElement(SelectedTrackElement.Video(engine.timeline.value.videoClips[1].id))
    engine.setPosition(7_000, snap = false)
    assertTrue(engine.splitAtPlayhead())
    engine.selectElement(SelectedTrackElement.Video(engine.timeline.value.videoClips[0].id))
    engine.setPosition(2_000, snap = false)
    assertTrue(engine.splitAtPlayhead())
    val clips = engine.timeline.value.videoClips
    assertEquals(4, clips.size)
    assertEquals(4, clips.map { it.id }.toSet().size)
    clips.forEach { clip ->
      assertEquals(before.vfxStackJson, clip.vfxStackJson)
      assertEquals(before.faceReshape, clip.faceReshape)
      assertEquals(before.bodyReshape, clip.bodyReshape)
      assertEquals(before.isBackgroundRemoved, clip.isBackgroundRemoved)
      assertEquals(before.bgRemove, clip.bgRemove)
      assertTrue(clip.durationMs > 0L)
      assertTrue(clip.sourceStartMs <= clip.sourceEndMs)
    }
    assertTrue(engine.undo())
    assertEquals(3, engine.timeline.value.videoClips.size)
    val restored = TimelineSerializer.fromJson(TimelineSerializer.serializeTimeline(engine.timeline.value))
    restored.videoClips.forEach { clip ->
      assertEquals(before.vfxStackJson, clip.vfxStackJson)
      assertEquals(before.faceReshape, clip.faceReshape)
      assertEquals(before.bodyReshape, clip.bodyReshape)
      assertTrue(clip.isBackgroundRemoved)
    }
  }

  @Test
  fun overlayClipKeepsItsOwnLook() {
    val engine = TimelineEngine()
    val main = VideoClip(id = "main", name = "main", timelineStartMs = 0, durationMs = 5_000, sourceEndMs = 5_000)
    val pip = VideoClip(id = "pip", name = "pip", timelineStartMs = 0, durationMs = 5_000, sourceEndMs = 5_000)
    engine.loadTimeline(Timeline(videoClips = listOf(main), overlayClips = listOf(pip)))
    val pipLook = ProductionEffectApplicator.apply(pip, ProductionEffectCatalog.byId("video.chromatic")!!, 1f)
    assertTrue(engine.setClipProductionLook("pip", pipLook))
    val tl = engine.timeline.value
    assertNull(tl.videoClips.first().vfxStackJson)
    assertNotNull(tl.overlayClips.first().vfxStackJson)
    assertNotEquals(tl.videoClips.first().vfxStackJson, tl.overlayClips.first().vfxStackJson)
    val stack = VfxEffectsHost.decode(tl.overlayClips.first().vfxStackJson)
    assertEquals("chromatic.aberration", stack.effectAt(0).definition.id)
  }
}
