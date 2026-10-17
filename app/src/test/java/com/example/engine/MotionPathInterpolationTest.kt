package com.example.engine

import com.example.domain.model.ClipKeyframe
import com.example.domain.model.KeyframeInterpolation
import com.example.domain.model.StickerClip
import com.example.domain.model.Timeline
import com.example.domain.model.VideoClip
import com.example.engine.timeline.TimelineEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot

/**
 * Pins the motion-path contract:
 *  - position follows a C1-continuous smooth curve through every keyframe point,
 *  - the drawn path (sampleMotionPath) is the same curve the renderer traces,
 *  - 2-keyframe clips stay exactly linear (back-compat),
 *  - HOLD freezes travel, and preview/export share one evaluation.
 */
class MotionPathInterpolationTest {

  private fun kf(t: Long, x: Float, y: Float, interp: KeyframeInterpolation = KeyframeInterpolation.LINEAR) =
    ClipKeyframe(timeMs = t, posX = x, posY = y, interpolation = interp)

  @Test
  fun pathPassesThroughEveryKeyframeExactly() {
    val keys = listOf(
      kf(0, -0.8f, 0.4f),
      kf(900, 0.2f, -0.9f),
      kf(2100, 0.9f, 0.6f),
      kf(3000, -0.4f, -0.7f)
    )
    keys.forEach { k ->
      val p = KeyframeInterpolator.evaluatePathPosition(keys, k.timeMs)
      assertEquals("posX at ${k.timeMs}ms", k.posX, p.first, 1e-4f)
      assertEquals("posY at ${k.timeMs}ms", k.posY, p.second, 1e-4f)
    }
  }

  @Test
  fun sampledPathIsSmoothThroughCorners() {
    // A sharp V corner: a raw polyline flips ~100 deg in ONE step at the middle node,
    // the smooth path must spread that turn gradually across samples.
    val keys = listOf(kf(0, -0.8f, 0.5f), kf(1000, 0f, -0.5f), kf(2000, 0.8f, 0.5f))
    val samples = KeyframeInterpolator.sampleMotionPath(keys, 64)
    var maxTurn = 0f
    for (i in 1 until samples.size - 1) {
      val ax = samples[i].first - samples[i - 1].first
      val ay = samples[i].second - samples[i - 1].second
      val bx = samples[i + 1].first - samples[i].first
      val by = samples[i + 1].second - samples[i].second
      val la = hypot(ax, ay)
      val lb = hypot(bx, by)
      if (la < 1e-6f || lb < 1e-6f) continue
      val dot = ((ax * bx + ay * by) / (la * lb)).coerceIn(-1f, 1f)
      val turn = Math.toDegrees(acos(dot.toDouble())).toFloat()
      if (turn > maxTurn) maxTurn = turn
    }
    assertTrue("path corner not smoothed (max per-step turn $maxTurn deg)", maxTurn < 45f)
  }

  @Test
  fun curveBendsAwayFromSharpPolylineCorner() {
    val clip = VideoClip(
      id = "v", uri = "asset:///v.mp4", name = "V",
      timelineStartMs = 0L, durationMs = 3000L, sourceStartMs = 0L, sourceEndMs = 3000L,
      keyframes = listOf(kf(0, -0.8f, 0.5f), kf(1500, 0f, -0.5f), kf(3000, 0.8f, 0.5f))
    )
    // Hits every keyframe exactly
    val t0 = KeyframeInterpolator.interpolate(clip, 0L)
    val tMid = KeyframeInterpolator.interpolate(clip, 1500L)
    val tEnd = KeyframeInterpolator.interpolate(clip, 3000L)
    assertEquals(-0.8f, t0.posX, 1e-4f)
    assertEquals(0f, tMid.posX, 1e-4f)
    assertEquals(-0.5f, tMid.posY, 1e-4f)
    assertEquals(0.8f, tEnd.posX, 1e-4f)
    // Quarter point must leave the straight polyline (smooth curve, not a rigid zig-zag)
    val quarter = KeyframeInterpolator.interpolate(clip, 375L)
    val linearY = 0.5f + (-1.0f) * 0.25f
    assertTrue("position still follows the sharp polyline", abs(quarter.posY - linearY) > 0.03f)
  }

  @Test
  fun twoKeyframesStayExactlyLinear() {
    val keys = listOf(kf(0, -0.5f, -0.2f), kf(4000, 0.5f, 0.6f))
    val mid = KeyframeInterpolator.evaluatePathPosition(keys, 2000L)
    assertEquals(0f, mid.first, 1e-4f)
    assertEquals(0.2f, mid.second, 1e-4f)
    val quarter = KeyframeInterpolator.evaluatePathPosition(keys, 1000L)
    assertEquals(-0.25f, quarter.first, 1e-4f)
    assertEquals(0f, quarter.second, 1e-4f)
  }

  @Test
  fun holdInterpolationFreezesPositionUntilNextKeyframe() {
    val keys = listOf(
      kf(0, -0.5f, 0.25f),
      kf(1000, 0.5f, -0.25f, KeyframeInterpolation.HOLD),
      kf(2000, 0.9f, 0.9f)
    )
    val p = KeyframeInterpolator.evaluatePathPosition(keys, 1500L)
    assertEquals(0.5f, p.first, 1e-5f)
    assertEquals(-0.25f, p.second, 1e-5f)
  }

  @Test
  fun sampleMotionPathCoversEverySegmentFully() {
    val keys = listOf(kf(0, -0.6f, 0.3f), kf(800, 0.1f, -0.6f), kf(1600, 0.7f, 0.4f), kf(2400, -0.2f, 0.8f))
    val n = 24
    val samples = KeyframeInterpolator.sampleMotionPath(keys, n)
    assertEquals(n * (keys.size - 1) + 1, samples.size)
    assertEquals(keys.first().posX, samples.first().first, 1e-4f)
    assertEquals(keys.first().posY, samples.first().second, 1e-4f)
    assertEquals(keys.last().posX, samples.last().first, 1e-4f)
    assertEquals(keys.last().posY, samples.last().second, 1e-4f)
    samples.forEach { (x, y) ->
      assertTrue(!x.isNaN() && !y.isNaN())
      assertTrue(!x.isInfinite() && !y.isInfinite())
    }
  }

  @Test
  fun previewAndExportShareTheSameSmoothPath() {
    val sticker = StickerClip(
      id = "s", durationMs = 3000L,
      keyframes = listOf(kf(0, -0.6f, 0.3f), kf(1500, 0.1f, -0.6f), kf(3000, 0.7f, 0.4f))
    )
    val timeline = Timeline(stickerClips = listOf(sticker))
    listOf(400L, 1500L, 2600L).forEach { t ->
      val preview = KeyframeInterpolator.interpolate(sticker, t)
      val exported = TimelineEvaluator().evaluate(timeline, t).activeStickers.single()
      assertEquals(preview.posX, exported.posX, 1e-4f)
      assertEquals(preview.posY, exported.posY, 1e-4f)
    }
  }
}
