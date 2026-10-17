package com.example.engine.effects

import com.ahstudio.face.deformation.DeformationCodec
import com.ahstudio.face.deformation.DeformationParams
import com.example.data.local.TimelineSerializer
import com.example.domain.model.Timeline
import com.example.domain.model.VideoClip
import com.example.engine.SelectedTrackElement
import com.example.engine.TimelineEngine
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
  fun catalogHasUniqueRealEffectsInEveryCategory() {
    val all = ProductionEffectCatalog.effects
    assertEquals(37, all.size)
    assertEquals(37, all.map { it.id }.toSet().size)
    assertEquals(37, all.map { it.shaderKey }.toSet().size)
    assertEquals(9, all.count { it.category == EffectCategory.VIDEO_EFFECTS })
    assertEquals(5, all.count { it.category == EffectCategory.FACE_EFFECTS })
    assertEquals(9, all.count { it.category == EffectCategory.BODY_EFFECTS })
    assertEquals(10, all.count { it.category == EffectCategory.PHOTO_EFFECTS })
    assertEquals(4, all.count { it.category == EffectCategory.AI_EFFECTS })
    assertEquals(EffectCategory.entries.toList(), ProductionEffectCatalog.categories())
    all.forEach { effect ->
      assertTrue(effect.id, effect.shaderKey?.isNotBlank() == true)
      assertTrue(effect.parameters.contains("intensity"))
      assertTrue(effect.supportsPreview)
      assertTrue(effect.supportsExport)
      assertEquals(null, effect.effectType)
    }
    assertEquals(37, EffectsAssetRegistry.getAllEffects().size)
    assertEquals(9, EffectsAssetRegistry.getEffects(EffectCategory.VIDEO_EFFECTS).size)
    assertEquals(9, EffectsAssetRegistry.getEffects(EffectCategory.BODY_EFFECTS).size)
    assertEquals(10, EffectsAssetRegistry.getEffects(EffectCategory.PHOTO_EFFECTS).size)
    assertEquals(4, EffectsAssetRegistry.getEffects(EffectCategory.AI_EFFECTS).size)
  }

  @Test
  fun everyVfxKeyResolvesToARegisteredEngineDefinition() {
    assertTrue(
      "skipped: ${VfxEffectsHost.registrationSkipped}",
      VfxEffectsHost.registrationSkipped.isEmpty()
    )
    ProductionEffectCatalog.effects.forEach { effect ->
      val key = effect.shaderKey ?: return@forEach
      if (!key.startsWith("vfx:")) return@forEach
      assertNotNull(
        "${effect.id} -> $key is not a registered definition",
        VfxEffectsHost.registry.createEffect(key.removePrefix("vfx:"))
      )
    }
  }

  @Test
  fun videoEffectsUpsertIntoTheRealVfxStack() {
    val clip = VideoClip(id = "v", name = "v")
    val boom = ProductionEffectCatalog.byId("video.explosion")!!
    val fire = ProductionEffectCatalog.byId("video.fire")!!
    val once = ProductionEffectApplicator.apply(clip, boom, 0.8f)
    val twice = ProductionEffectApplicator.apply(once, boom, 0.4f)
    val both = ProductionEffectApplicator.apply(twice, fire, 1f)
    val stack = VfxEffectsHost.decode(both.vfxStackJson)
    assertEquals(2, stack.size)
    assertEquals("trend.explosion", stack.effectAt(0).definition.id)
    assertEquals(0.4f, stack.effectAt(0).intensity, 1e-4f)
    assertEquals(0.40f + 0.60f * 0.4f, stack.effectAt(0).getParam<Float>("scale"), 1e-4f)
    assertEquals("trend.fire", stack.effectAt(1).definition.id)
  }

  @Test
  fun faceWarpAssetsWriteTheChannelsTheCompositorReads() {
    val clip = VideoClip(id = "v", name = "v")
    val funny = ProductionEffectApplicator.apply(clip, ProductionEffectCatalog.byId("face.funny")!!, 0.7f)
    val alien = ProductionEffectApplicator.apply(funny, ProductionEffectCatalog.byId("face.alien_eyes")!!, 0.4f)
    val doll = ProductionEffectApplicator.apply(alien, ProductionEffectCatalog.byId("photo.doll")!!, 0.9f)
    val decoded = DeformationCodec.decode(doll.faceReshape)
    assertEquals(0.7f, decoded.funny, 1e-4f)
    assertEquals(0.4f, decoded.alienEyes, 1e-4f)
    assertEquals(0.9f, decoded.bigHead, 1e-4f)
    // appliedOn reports the strongest channel per category.
    assertEquals("photo.doll", ProductionEffectApplicator.appliedOn(doll, EffectCategory.PHOTO_EFFECTS)?.id)
    assertEquals(0.9f, ProductionEffectApplicator.intensityOn(doll, ProductionEffectCatalog.byId("photo.doll")!!), 1e-4f)
    val cleared = ProductionEffectApplicator.clearCategory(doll, EffectCategory.FACE_EFFECTS)
    assertNull(cleared.faceReshape)
    // Clearing PHOTO also wipes the shared face-reshape struct (documented semantics:
    // the warp channels live in one struct, so a category clear resets all of them).
    val noPhoto = ProductionEffectApplicator.clearCategory(doll, EffectCategory.PHOTO_EFFECTS)
    assertNull(noPhoto.faceReshape)
  }

  @Test
  fun subjectAssetsRideTheSameStackAsVideoAssets() {
    val clip = VideoClip(id = "v", name = "v")
    val scanned = ProductionEffectApplicator.apply(clip, ProductionEffectCatalog.byId("body.optical_scan")!!, 1f)
    val flashed = ProductionEffectApplicator.apply(scanned, ProductionEffectCatalog.byId("video.white_flash")!!, 0.5f)
    val stack = VfxEffectsHost.decode(flashed.vfxStackJson)
    assertEquals(2, stack.size)
    assertEquals("subject.optical_scan", stack.effectAt(0).definition.id)
    assertEquals("trend.white_flash", stack.effectAt(1).definition.id)
    val clearedBody = ProductionEffectApplicator.clearCategory(flashed, EffectCategory.BODY_EFFECTS)
    assertEquals(
      listOf("trend.white_flash"),
      VfxEffectsHost.decode(clearedBody.vfxStackJson).effects().map { it.definition.id }
    )
  }

  @Test
  fun warpChannelCodecRoundTripsThroughTheClip() {
    val clip = VideoClip(id = "v", name = "v")
    val applied = ProductionEffectCatalog.effects
      .filter { it.shaderKey?.startsWith("face:") == true }
      .fold(clip) { c, effect -> ProductionEffectApplicator.apply(c, effect, 0.5f) }
    val d = DeformationCodec.decode(applied.faceReshape)
    assertEquals(0.5f, d.funny, 1e-4f)
    assertEquals(0.5f, d.alienEyes, 1e-4f)
    assertEquals(0.5f, d.smallFace, 1e-4f)
    assertEquals(0.5f, d.melt, 1e-4f)
    assertEquals(0.5f, d.chubby, 1e-4f)
    assertEquals(0.5f, d.wink, 1e-4f)
    assertEquals(0.5f, d.curls, 1e-4f)
    assertEquals(0.5f, d.bigHead, 1e-4f)
    assertTrue(DeformationCodec.encode(d) != null)
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
  fun liveThumbnailsProcessTheSourceFrameNotADummyScene() {
    val w = 32
    val h = 24
    val src = IntArray(w * h) { i ->
      val x = i % w
      val y = i / w
      val r = (x * 9 + if ((x + y) % 3 == 0) 90 else 10) and 0xFF
      val g = (y * 11 + if (x % 2 == 0) 160 else 30) and 0xFF
      val b = ((x xor y) * 13 + 40) and 0xFF
      (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
    val unprocessed = EffectThumbnailRaster.apply(null, src, w, h)
    assertEquals(src.toList(), unprocessed.toList())
    ProductionEffectCatalog.effects.forEach { effect ->
      val out = EffectThumbnailRaster.apply(effect.shaderKey, src, w, h)
      val changed = out.indices.count { out[it] != src[it] }
      assertTrue("${effect.id} live thumb changed $changed pixels", changed > 2)
    }
  }

  @Test
  fun registryRejectsDummyEffectsWithoutShaderKey() {
    val before = EffectsAssetRegistry.getAllEffects().size
    EffectsAssetRegistry.registerEffect(
      com.example.engine.effects.registry.RegisteredEffect(
        id = "dummy.laser",
        name = "Laser Grid",
        category = EffectCategory.VIDEO_EFFECTS,
        effectType = com.example.domain.model.EffectType.LASER_GRID,
        shaderKey = null,
      )
    )
    assertEquals(before, EffectsAssetRegistry.getAllEffects().size)
    assertTrue(EffectsAssetRegistry.getEffects(EffectCategory.VIDEO_EFFECTS).none { it.id == "dummy.laser" })
  }

  @Test
  fun appliedOnReadsTheSameFieldsPreviewAndExportUse() {
    val clip = VideoClip(id = "v", name = "v")
    val boom = ProductionEffectCatalog.byId("video.explosion")!!
    val clone = ProductionEffectCatalog.byId("ai.shadow_clone")!!
    val funny = ProductionEffectCatalog.byId("face.funny")!!
    val looked = ProductionEffectApplicator.apply(
      ProductionEffectApplicator.apply(
        ProductionEffectApplicator.apply(clip, boom, 0.6f),
        clone,
        0.8f
      ),
      funny,
      0.5f
    )
    assertEquals("video.explosion", ProductionEffectApplicator.appliedOn(looked, EffectCategory.VIDEO_EFFECTS)?.id)
    assertEquals("ai.shadow_clone", ProductionEffectApplicator.appliedOn(looked, EffectCategory.AI_EFFECTS)?.id)
    assertEquals("face.funny", ProductionEffectApplicator.appliedOn(looked, EffectCategory.FACE_EFFECTS)?.id)
    assertEquals(0.6f, ProductionEffectApplicator.intensityOn(looked, boom), 1e-4f)
    assertEquals(0.8f, ProductionEffectApplicator.intensityOn(looked, clone), 1e-4f)
    val clearedAi = ProductionEffectApplicator.clearCategory(looked, EffectCategory.AI_EFFECTS)
    assertNull(ProductionEffectApplicator.appliedOn(clearedAi, EffectCategory.AI_EFFECTS))
    assertEquals("video.explosion", ProductionEffectApplicator.appliedOn(clearedAi, EffectCategory.VIDEO_EFFECTS)?.id)
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
      .fold(source) { clip, effect -> ProductionEffectApplicator.apply(clip, effect, 0.75f) }
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
      assertNotNull(clip.vfxStackJson)
    }
  }

  @Test
  fun overlayClipKeepsItsOwnLook() {
    val engine = TimelineEngine()
    val main = VideoClip(id = "main", name = "main", timelineStartMs = 0, durationMs = 5_000, sourceEndMs = 5_000)
    val pip = VideoClip(id = "pip", name = "pip", timelineStartMs = 0, durationMs = 5_000, sourceEndMs = 5_000)
    engine.loadTimeline(Timeline(videoClips = listOf(main), overlayClips = listOf(pip)))
    val pipLook = ProductionEffectApplicator.apply(pip, ProductionEffectCatalog.byId("video.explosion")!!, 1f)
    assertTrue(engine.setClipProductionLook("pip", pipLook))
    val tl = engine.timeline.value
    assertNull(tl.videoClips.first().vfxStackJson)
    assertNotNull(tl.overlayClips.first().vfxStackJson)
    assertNotEquals(tl.videoClips.first().vfxStackJson, tl.overlayClips.first().vfxStackJson)
    val stack = VfxEffectsHost.decode(tl.overlayClips.first().vfxStackJson)
    assertEquals("trend.explosion", stack.effectAt(0).definition.id)
  }
}
