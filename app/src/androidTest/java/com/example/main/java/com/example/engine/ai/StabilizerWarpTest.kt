package com.example.engine.ai

import com.example.engine.ai.MotionKeyframe
import com.example.engine.ai.tracking.Stabilizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StabilizerWarpTest {

  private val square = listOf(0.2f to 0.2f, 0.8f to 0.2f, 0.8f to 0.8f, 0.2f to 0.8f)

  private fun key(i: Int, dx: Float, dy: Float, pin: List<Pair<Float, Float>> = square) =
    MotionKeyframe(
      timestampUs = i * 41_666L,
      centerX = 0.5f + dx, centerY = 0.5f + dy,
      cornerPin = pin.map { (x, y) -> (x + dx) to (y + dy) }
    )

  @Test
  fun staticCameraProducesNoWarp() {
    val path = List(30) { key(it, 0f, 0f) }
    Stabilizer.solve(path, 0.6f, warp = true).forEach {
      assertEquals(0f, it.rsX, 1e-5f); assertEquals(0f, it.rsY, 1e-5f)
      assertEquals(0f, it.persX, 1e-4f); assertEquals(0f, it.persY, 1e-4f)
    }
  }

  @Test
  fun horizontalPanGivesRollingShutterSkewWithMatchingSign() {
    // Content moving right (+x) at constant speed -> positive skew term.
    val path = List(30) { key(it, it * 0.004f, 0f) }
    val c = Stabilizer.solve(path, 0.6f, warp = true)[15]
    assertTrue("rsX=${c.rsX}", c.rsX > 0f)
    assertEquals(0f, c.rsY, 1e-4f)
  }

  @Test
  fun warpOffKeepsLegacyBehaviour() {
    val path = List(20) { key(it, it * 0.004f, 0f) }
    Stabilizer.solve(path, 0.6f, warp = false).forEach {
      assertEquals(0f, it.rsX, 0f); assertEquals(0f, it.persX, 0f)
    }
  }

  @Test
  fun trapezoidJitterYieldsPerspectiveCorrection() {
    // Corners breathe in/out asymmetrically frame to frame (perspective wobble).
    val path = List(40) { i ->
      val k = if (i % 2 == 0) 0.04f else -0.04f
      key(i, 0f, 0f, listOf(0.2f - k to 0.2f, 0.8f + k to 0.2f, 0.8f to 0.8f, 0.2f to 0.8f))
    }
    val any = Stabilizer.solve(path, 0.8f, warp = true).any { kotlin.math.abs(it.persY) > 1e-3f || kotlin.math.abs(it.persX) > 1e-3f }
    assertTrue(any)
  }

  @Test
  fun readoutTimeScalesSkew() {
    val path = List(30) { key(it, it * 0.004f, 0f) }
    val base = Stabilizer.solve(path, 0.6f, warp = true)[15].rsX
    val slow = Stabilizer.solve(path, 0.6f, warp = true, readoutUs = 10_000.0)[15].rsX
    assertTrue("base=$base slow=$slow", base > 0f)
    assertEquals(base / 2f, slow, 1e-5f)
  }

  @Test
  fun readoutIsClampedAndNanSafe() {
    val path = List(30) { key(it, it * 0.004f, 0f) }
    val def = Stabilizer.solve(path, 0.6f, warp = true)[15].rsX
    assertEquals(def, Stabilizer.solve(path, 0.6f, warp = true, readoutUs = Double.NaN)[15].rsX, 1e-6f)
    val huge = Stabilizer.solve(path, 0.6f, warp = true, readoutUs = 5e9)[15].rsX
    val max = Stabilizer.solve(path, 0.6f, warp = true, readoutUs = Stabilizer.MAX_READOUT_US)[15].rsX
    assertEquals(max, huge, 1e-6f)
  }

  // ---- Fast vibration (jelly): measured from per-band motion ----

  private val framePeriodUs = 41_667L
  private val readoutS = 0.020
  private val bands = 6

  /** Camera path in NDC: steady pan plus a 28 Hz vibration and a 9 Hz shake. */
  private fun content(t: Double) =
    0.3 * t + 0.004 * kotlin.math.sin(2 * Math.PI * 28 * t + 0.7) + 0.006 * kotlin.math.sin(2 * Math.PI * 9 * t)

  /** Row-band offset (s) of band j relative to the frame centre: rows are read out top -> bottom. */
  private fun bandTime(j: Int) = ((j + 0.5) / bands - 0.5) * readoutS

  private fun vibrationPath(n: Int): List<MotionKeyframe> = List(n) { i ->
    val t = i * framePeriodUs / 1e6
    val d = if (i == 0) emptyList() else List(2 * bands) { idx ->
      if (idx % 2 == 0) {
        val j = idx / 2
        (content(t + bandTime(j)) - content(t - framePeriodUs / 1e6 + bandTime(j))).toFloat()
      } else 0f
    }
    MotionKeyframe(
      timestampUs = i * framePeriodUs,
      centerX = ((content(t) + 1.0) / 2.0).toFloat(), centerY = 0.5f,
      bandMotion = d
    )
  }

  /**
   * RMS distance (NDC) between where each row ends up and the intended smooth camera pose (the pure pan, since
   * the vibration is meant to be removed), over the middle of the clip. Row y is shifted by
   * 2*dx + rsX*y + rsX2*y^2.
   */
  private fun residualRms(corr: List<com.example.engine.ai.tracking.StabilizeCorrection>, useCorrection: Boolean): Double {
    var sum = 0.0
    var count = 0
    for (i in 12 until corr.size - 12) {
      val t = i * framePeriodUs / 1e6
      val target = 0.3 * t
      for (j in 0 until bands) {
        val y = 1.0 - 2.0 * (j + 0.5) / bands
        val c = corr[i]
        val shift = if (useCorrection) 2.0 * c.dx + c.rsX * y + c.rsX2 * y * y else 0.0
        val r = content(t + bandTime(j)) + shift - target
        sum += r * r
        count++
      }
    }
    return kotlin.math.sqrt(sum / count)
  }

  @Test
  fun fastVibrationIsCorrectedPerRowNotJustSkewed() {
    val path = vibrationPath(120)
    val corr = Stabilizer.solve(path, 0.5f, warp = true)
    assertTrue("curved term present", corr.drop(12).take(90).any { kotlin.math.abs(it.rsX2) > 5e-4f })
    val before = residualRms(corr, useCorrection = false)
    val after = residualRms(corr, useCorrection = true)
    assertTrue("before=$before after=$after", after < 0.5 * before)
  }

  @Test
  fun withoutBandMotionThereIsNoCurvedTerm() {
    val path = List(30) { key(it, it * 0.004f, 0f) }
    Stabilizer.solve(path, 0.6f, warp = true).forEach {
      assertEquals(0f, it.rsX2, 0f); assertEquals(0f, it.rsY2, 0f)
    }
  }

  @Test
  fun steadyPanWithBandsStillGivesSkewOnly() {
    // Straight motion: every band moves the same amount per frame -> no vibration, no curve.
    val path = List(60) { i ->
      MotionKeyframe(
        timestampUs = i * framePeriodUs, centerX = 0.5f + i * 0.004f, centerY = 0.5f,
        bandMotion = if (i == 0) emptyList() else List(2 * bands) { if (it % 2 == 0) 0.008f else 0f }
      )
    }
    val c = Stabilizer.solve(path, 0.6f, warp = true)[30]
    assertTrue("rsX=${c.rsX}", c.rsX > 0f)
    assertEquals(0f, c.rsX2, 2e-4f)
  }
}
