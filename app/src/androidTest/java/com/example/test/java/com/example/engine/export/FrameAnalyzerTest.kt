package com.example.engine.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FrameAnalyzerTest {
  private fun argb(r: Int, g: Int = r, b: Int = r) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

  @Test fun solidBlackIsBlack() {
    val s = FrameAnalyzer.analyze(IntArray(576) { argb(0) })
    assertTrue(s.isBlack)
    assertEquals(0, s.p99Luma)
  }

  @Test fun limitedRangeBlack16IsBlack() =
    assertTrue(FrameAnalyzer.analyze(IntArray(576) { argb(16) }).isBlack)

  @Test fun noEarlyPixelsMeansEmptyAndBlack() {
    val s = FrameAnalyzer.analyze(IntArray(0))
    assertTrue(s.isEmpty); assertTrue(s.isBlack)
  }

  @Test fun brightFlatFrameIsNotBlack() =
    assertFalse(FrameAnalyzer.analyze(IntArray(576) { argb(200) }).isBlack)

  @Test fun darkFrameWithTwoPercentHighlightsIsNotBlack() {
    // 2% of pixels (a lamp) at luma 120 on a black background.
    val px = IntArray(1000) { if (it < 20) argb(120) else argb(2) }
    assertFalse(FrameAnalyzer.analyze(px).isBlack)
  }

  @Test fun darkFrameWithSensorNoiseIsNotBlack() {
    val rnd = Random(7)
    val px = IntArray(576) { argb(rnd.nextInt(0, 14)) } // uniform noise 0..13, stddev ~4
    assertFalse(FrameAnalyzer.analyze(px).isBlack)
  }

  @Test fun singleHotPixelDoesNotRescueABlackFrame() {
    // One bright pixel in 576 is <1%: still an empty picture.
    val px = IntArray(576) { if (it == 0) argb(255) else argb(0) }
    assertTrue(FrameAnalyzer.analyze(px).isBlack)
  }

  @Test fun lumaWeightsFollowBt601() {
    assertEquals(76, FrameAnalyzer.luma(argb(255, 0, 0)))
    assertEquals(149, FrameAnalyzer.luma(argb(0, 255, 0)))
    assertEquals(29, FrameAnalyzer.luma(argb(0, 0, 255)))
  }
}
