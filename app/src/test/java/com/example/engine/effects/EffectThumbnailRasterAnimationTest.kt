package com.example.engine.effects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the animated-tile contract of [EffectThumbnailRaster]:
 *  - every panel swatch key must actually MOVE over time (no frozen frames),
 *  - the loop must wrap seamlessly (t=0 renders exactly like t=loop), so the live
 *    preview never pops at the wrap point,
 *  - the reusable-buffer path must match [EffectThumbnailRaster.apply] and really
 *    write into the caller's buffer (animated tiles never allocate per frame),
 *  - entries without a shader stay a pure passthrough at every time.
 */
class EffectThumbnailRasterAnimationTest {

  companion object {
    private const val W = 48
    private const val H = 48

    private val SRC = IntArray(W * H) { i ->
      val x = i % W
      val y = i / W
      val r = (x * 9 + if ((x + y) % 3 == 0) 90 else 10) and 0xFF
      val g = (y * 11 + if (x % 2 == 0) 160 else 30) and 0xFF
      val b = ((x xor y) * 13 + 40) and 0xFF
      (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Every branch the panel animates: the whole video/body/photo/face asset set. */
    private val ANIMATED_KEYS = listOf(
      "vfx:blur.motion",
      "vfx:trend.explosion", "vfx:trend.ink_spill", "vfx:trend.earth",
      "vfx:trend.magical_tome", "vfx:trend.cut_shift", "vfx:trend.fire",
      "vfx:trend.mini_zoom", "vfx:trend.white_flash", "vfx:trend.expand_x",
      "vfx:trend.orbit360", "vfx:trend.layer_echo", "vfx:trend.zoom3d",
      "vfx:trend.ai_filter", "vfx:trend.sad", "vfx:trend.cartoon3d",
      "vfx:subject.optical_scan", "vfx:subject.phantom_clone", "vfx:subject.light_trails",
      "vfx:subject.pixel_mask", "vfx:subject.action_paint", "vfx:subject.face_scan",
      "vfx:subject.light_dissolve", "vfx:subject.luminescence", "vfx:subject.hand_drawn",
      "vfx:subject.clone_shadow", "vfx:subject.clone_golden", "vfx:subject.clone_cyber",
      "vfx:subject.clone_cartoon",
      "face:big_head", "face:small", "face:alien", "face:funny",
      "face:melt", "face:chubby", "face:wink", "face:curls"
    )

    private fun frame(key: String, t: Float): IntArray =
      EffectThumbnailRaster.apply(key, SRC, W, H, t)
  }

  @Test
  fun everyAnimatedKeyMovesOverTime() {
    ANIMATED_KEYS.forEach { key ->
      val a = frame(key, 0f)
      val b = frame(key, 1.05f)
      val moved = a.indices.count { a[it] != b[it] }
      assertTrue("$key does not animate ($moved px moved)", moved > 8)
    }
  }

  @Test
  fun liveLoopWrapsWithoutAPop() {
    val loop = EffectThumbnailRaster.LIVE_LOOP_SECONDS
    ANIMATED_KEYS.forEach { key ->
      val a = frame(key, 0f)
      val b = frame(key, loop)
      val popped = a.indices.count { i ->
        val ca = a[i]
        val cb = b[i]
        val dr = ((ca shr 16) and 0xFF) - ((cb shr 16) and 0xFF)
        val dg = ((ca shr 8) and 0xFF) - ((cb shr 8) and 0xFF)
        val db = (ca and 0xFF) - (cb and 0xFF)
        maxOf(kotlin.math.abs(dr), kotlin.math.abs(dg), kotlin.math.abs(db)) > 2
      }
      assertTrue("$key pops at the loop seam ($popped px jumped)", popped <= W * H / 50)
    }
  }

  @Test
  fun renderIntoMatchesApplyAndReusesTheBuffer() {
    val key = "vfx:trend.fire"
    val expected = EffectThumbnailRaster.apply(key, SRC, W, H, 0.9f)
    val out = IntArray(W * H)
    val returned = EffectThumbnailRaster.renderInto(key, SRC, out, W, H, 0.9f)
    assertEquals(out, returned)
    assertTrue(expected.contentEquals(returned))
  }

  @Test
  fun nullKeyCopiesTheSourceIntoTheReusableBuffer() {
    val out = IntArray(W * H)
    val returned = EffectThumbnailRaster.renderInto(null, SRC, out, W, H, 1.3f)
    assertEquals(out, returned)
    assertTrue(SRC.contentEquals(returned))
  }

  @Test
  fun baseSceneIsTheUnprocessedFallback() {
    val scene = EffectThumbnailRaster.baseScene(48)
    val viaNull = EffectThumbnailRaster.argb(null, 48)
    assertTrue(scene.contentEquals(viaNull))
    assertEquals(48 * 48, scene.size)
  }

  @Test
  fun unknownLegacyKeysStayStatic() {
    val a = frame("legacy.unknown", 0f)
    val b = frame("legacy.unknown", 1.7f)
    assertTrue(a.contentEquals(b))
  }
}
