package com.example.engine.effects

import com.example.domain.model.VideoClip
import com.example.engine.effects.registry.EffectCategory
import com.example.engine.effects.registry.EffectsAssetRegistry
import com.example.engine.vfx.VfxEffectsHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the 3-second play-window contract for VIDEO-effect assets:
 * window math, serialization round-trip + legacy compatibility, per-frame resolution
 * (the gate preview AND export both render through) and the applicator's writes.
 */
class EffectPlayWindowTest {

  @Before
  fun setUp() {
    EffectsAssetRegistry.clearAll()
    ProductionEffectCatalog.install()
    ProductionEffectCatalog.install()
  }

  private fun videoAsset(shaderKey: String) =
    ProductionEffectCatalog.effectsIn(EffectCategory.VIDEO_EFFECTS).first { it.shaderKey == shaderKey }

  private fun photoAsset(shaderKey: String) =
    ProductionEffectCatalog.effectsIn(EffectCategory.PHOTO_EFFECTS).first { it.shaderKey == shaderKey }

  /** An empty stack carrying the host registry (same registry the renderer uses). */
  private fun emptyStack() = VfxEffectsHost.decode(null)

  // ---------- window math ----------

  @Test
  fun videoEffectWindowAnchorsAtThePlayhead() {
    // 10s clip, effect dropped at 2s -> [2s, 5s)
    assertEquals(2000L to 3000L, ProductionEffectApplicator.videoEffectWindow(10_000L, 2000L))
  }

  @Test
  fun videoEffectWindowClampsAtTheClipEnd() {
    // Dropped near the end: the window slides back so it still spans a full 3s.
    assertEquals(7000L to 3000L, ProductionEffectApplicator.videoEffectWindow(10_000L, 9500L))
    assertEquals(500L to 3000L, ProductionEffectApplicator.videoEffectWindow(3500L, 3500L))
  }

  @Test
  fun videoEffectWindowIsNullForClipsShorterThanTheRule() {
    // A 2.9s / exactly-3s clip IS the window; no bounded span to write.
    assertNull(ProductionEffectApplicator.videoEffectWindow(2900L, 0L))
    assertNull(ProductionEffectApplicator.videoEffectWindow(3000L, 1200L))
  }

  @Test
  fun videoEffectWindowCoercesNegativePlayheads() {
    assertEquals(0L to 3000L, ProductionEffectApplicator.videoEffectWindow(10_000L, -400L))
  }

  // ---------- EffectInstance window semantics ----------

  @Test
  fun windowSerializesAndRestores() {
    val stack = emptyStack()
    val inst = stack.addEffect("trend.fire")
    inst.setTimeWindow(startUs = 1_500_000L, durationUs = 3_000_000L)

    val json = VfxEffectsHost.encode(stack)!!
    assertTrue("window must serialize: $json", json.contains("\"startUs\":1500000"))
    assertTrue("window span must serialize: $json", json.contains("\"durationUs\":3000000"))

    val restored = VfxEffectsHost.decode(json)
    val back = restored.effects().single()
    assertEquals(1_500_000L, back.windowStartUs)
    assertEquals(3_000_000L, back.windowDurationUs)
  }

  @Test
  fun windowlessInstanceSerializesWithoutWindowKeys() {
    val stack = emptyStack()
    stack.addEffect("trend.fire")
    val json = VfxEffectsHost.encode(stack)!!
    assertFalse("whole-clip effect must stay legacy-shaped: $json", json.contains("startUs"))

    val back = VfxEffectsHost.decode(json).effects().single()
    assertNull(back.windowStartUs)
    assertTrue(back.isActiveAt(0L))
    assertTrue(back.isActiveAt(999_999_999L))
  }

  @Test
  fun windowBoundariesAreStartInclusiveEndExclusive() {
    val stack = emptyStack()
    val inst = stack.addEffect("trend.fire").apply { setTimeWindow(1_000_000L, 3_000_000L) }
    assertFalse(inst.isActiveAt(999_999L))
    assertTrue(inst.isActiveAt(1_000_000L))
    assertTrue(inst.isActiveAt(3_999_999L))
    assertFalse(inst.isActiveAt(4_000_000L))
  }

  @Test
  fun windowRampIsUnityInsideAndZeroOutside() {
    val stack = emptyStack()
    val inst = stack.addEffect("trend.fire").apply { setTimeWindow(1_000_000L, 3_000_000L) }
    // Deep inside: full strength.
    assertEquals(1f, inst.windowedIntensityAt(2_500_000L), 1e-4f)
    // Outside: nothing.
    assertEquals(0f, inst.windowedIntensityAt(500_000L), 1e-4f)
    assertEquals(0f, inst.windowedIntensityAt(4_500_000L), 1e-4f)
    // Edges: partial, monotonic.
    val atStart = inst.windowedIntensityAt(1_100_000L)
    val deeper = inst.windowedIntensityAt(1_200_000L)
    assertTrue("ramp must start soft: $atStart", atStart in 0.01f..0.9f)
    assertTrue("ramp must be monotonic: $atStart -> $deeper", deeper > atStart)
  }

  // ---------- per-frame resolution (what preview + export actually draw) ----------

  @Test
  fun resolveAtSkipsFramesOutsideTheWindow() {
    val stack = emptyStack()
    stack.addEffect("trend.fire").apply { setTimeWindow(1_000_000L, 3_000_000L) }
    assertTrue(stack.resolveAt(200_000L).isEmpty())
    assertTrue(stack.resolveAt(4_500_000L).isEmpty())
    assertEquals(1, stack.resolveAt(2_000_000L).size)
  }

  @Test
  fun resolveAtRampsIntensityAtWindowEdges() {
    val stack = emptyStack()
    stack.addEffect("trend.fire").apply { setTimeWindow(1_000_000L, 3_000_000L) }

    val mid = stack.resolveAt(2_000_000L).single()
    assertEquals(1f, mid.snapshot.intensity, 1e-4f)

    val edge = stack.resolveAt(1_100_000L).single()
    assertTrue("edge must be softened: ${edge.snapshot.intensity}", edge.snapshot.intensity < 0.9f)
    assertTrue(edge.snapshot.intensity > 0f)

    // Disabled + zero-intensity behaviour is untouched by the window logic.
    stack.addEffect("trend.earth").apply {
      setTimeWindow(0L, 10_000_000L)
      enabled = false
    }
    assertTrue(stack.resolveAt(2_000_000L).none { it.instance.definition.id == "trend.earth" })
  }

  @Test
  fun resolveAtKeepsLegacyStacksWholeClip() {
    val stack = VfxEffectsHost.decode(VfxEffectsHost.addEffect(null, "trend.fire"))
    assertTrue(stack.resolveAt(0L).isNotEmpty())
    assertTrue(stack.resolveAt(60_000_000L).isNotEmpty())
  }

  // ---------- applicator writes ----------

  @Test
  fun videoEffectApplyWritesThePlayWindow() {
    val fire = videoAsset("vfx:trend.fire")
    val clip = ProductionEffectApplicator.apply(
      VideoClip(id = "v", name = "v"), fire, 1f, playWindowMs = 2000L to 3000L
    )
    val json = clip.vfxStackJson
    assertTrue("apply must persist the window: $json", json!!.contains("\"startUs\":2000000"))
    assertTrue(json.contains("\"durationUs\":3000000"))

    val inst = VfxEffectsHost.decode(json).effects().single()
    assertEquals(2_000_000L, inst.windowStartUs)
    assertTrue(inst.isActiveAt(2_100_000L))
    assertFalse(inst.isActiveAt(5_500_000L))
  }

  @Test
  fun photoEffectApplyStaysWholeClip() {
    val photo = photoAsset("vfx:trend.expand_x")
    val clip = ProductionEffectApplicator.apply(VideoClip(id = "v", name = "v"), photo, 1f, playWindowMs = null)
    val inst = VfxEffectsHost.decode(clip.vfxStackJson).effects().single()
    assertNull("photo looks cover the WHOLE clip", inst.windowStartUs)
    assertTrue(inst.isActiveAt(0L))
    assertTrue(inst.isActiveAt(999_999_999L))
  }

  @Test
  fun videoEffectApplyWithoutWindowStaysWholeClip() {
    // Short clips pass playWindowMs == null (the whole clip IS the window).
    val fire = videoAsset("vfx:trend.fire")
    val clip = ProductionEffectApplicator.apply(VideoClip(id = "v", name = "v"), fire, 1f, playWindowMs = null)
    val inst = VfxEffectsHost.decode(clip.vfxStackJson).effects().single()
    assertNull(inst.windowStartUs)
  }
}
