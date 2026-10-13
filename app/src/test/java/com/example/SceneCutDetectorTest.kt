package com.example

import com.example.engine.ai.scene.FrameSignature
import com.example.engine.ai.scene.SceneCutDetector
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneCutDetectorTest {

  private fun frame(w: Int, h: Int, pixel: (Int, Int) -> Int): FrameSignature {
    val px = IntArray(w * h)
    for (y in 0 until h) for (x in 0 until w) px[y * w + x] = pixel(x, y)
    return SceneCutDetector.signature(px, w, h)
  }

  private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

  /** Scene id by time: A until 3000, B until 7300, C after. Each scene has its own brightness layout. */
  private fun synthetic(t: Long): FrameSignature {
    val drift = (sin(t / 900.0) * 6).toInt()
    return when {
      t < 3000 -> frame(32, 32) { x, _ -> gray((60 + drift + x).coerceIn(0, 255)) }
      t < 7300 -> frame(32, 32) { _, y -> gray((190 + drift - y).coerceIn(0, 255)) }
      else -> frame(32, 32) { x, y -> if ((x / 8 + y / 8) % 2 == 0) gray(30) else gray(220) }
    }
  }

  private val times = (0L..10_000L step 250L).toList()

  @Test
  fun identicalFramesHaveZeroDistance() {
    val a = synthetic(100)
    assertEquals(0f, SceneCutDetector.distance(a, a), 1e-6f)
  }

  @Test
  fun differentScenesAreFarApart() {
    assertTrue(SceneCutDetector.distance(synthetic(100), synthetic(5000)) > 0.4f)
  }

  @Test
  fun findsBothCutsNearTheRealOnes() {
    val cuts = SceneCutDetector.findCuts(times) { synthetic(it) }
    assertEquals("cuts: $cuts", 2, cuts.size)
    assertTrue(abs(cuts[0] - 3000) <= 60)
    assertTrue(abs(cuts[1] - 7300) <= 60)
  }

  @Test
  fun slowDriftAloneProducesNoCuts() {
    val cuts = SceneCutDetector.findCuts(times) { t ->
      frame(32, 32) { x, _ -> gray((100 + (t / 80).toInt() + x).coerceIn(0, 255)) }
    }
    assertTrue("unexpected cuts $cuts", cuts.isEmpty())
  }

  @Test
  fun minShotLengthSuppressesFlashDoubles() {
    val flash: (Long) -> FrameSignature = { t ->
      if (t in 2000..2200) frame(32, 32) { _, _ -> gray(250) } else synthetic(t)
    }
    val cuts = SceneCutDetector.findCuts(times, minShotMs = 600L, signatureAt = flash)
    assertTrue("flash must not create several cuts: $cuts", cuts.count { it in 1500..2600 } <= 1)
  }

  @Test
  fun tooFewSamplesReturnsNothing() {
    assertTrue(SceneCutDetector.findCuts(listOf(0L, 500L)) { synthetic(it) }.isEmpty())
  }

  @Test
  fun signatureOfShortArrayDoesNotCrash() {
    val s = SceneCutDetector.signature(IntArray(2), 10, 10)
    assertEquals(SceneCutDetector.HIST_BINS, s.histogram.size)
  }
}
