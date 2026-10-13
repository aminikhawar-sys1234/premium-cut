package com.example

import com.example.engine.ai.cutout.MaskTemporalFilter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskTemporalFilterTest {

  private fun mask(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
  private fun at(a: ByteArray, i: Int) = a[i].toInt() and 0xFF

  @Test fun noPreviousReturnsCurrent() {
    val cur = mask(10, 20)
    assertSame(cur, MaskTemporalFilter.blend(null, cur))
  }

  @Test fun sizeMismatchReturnsCurrent() {
    val cur = mask(10, 20)
    assertSame(cur, MaskTemporalFilter.blend(mask(1), cur))
  }

  @Test fun smallFlickerIsHalved() {
    val out = MaskTemporalFilter.blend(mask(100), mask(140))
    assertEquals(120, at(out, 0))
  }

  @Test fun largeChangeFollowsNewMaskQuickly() {
    val out = MaskTemporalFilter.blend(mask(0), mask(255))
    assertTrue(at(out, 0) >= 225)
  }

  @Test fun steadyMaskStaysSteady() {
    val m = mask(0, 128, 255)
    assertArrayEquals(m, MaskTemporalFilter.blend(m, m))
  }

  @Test fun alternatingEdgeFlickerShrinks() {
    var prev = mask(100)
    val values = listOf(160, 100, 160, 100, 160, 100, 160, 100)
    var lastSwing = 0
    for (v in values) {
      val out = MaskTemporalFilter.blend(prev, mask(v))
      lastSwing = kotlin.math.abs(at(out, 0) - at(prev, 0))
      prev = out
    }
    assertTrue("swing $lastSwing", lastSwing < 40)
  }

  @Test fun blendsOnlyForwardInShortGaps() {
    val prev = mask(1, 2)
    assertTrue(MaskTemporalFilter.canBlend(prev, 2, 1))
    assertTrue(MaskTemporalFilter.canBlend(prev, 2, 4))
    assertFalse(MaskTemporalFilter.canBlend(prev, 2, 0))
    assertFalse(MaskTemporalFilter.canBlend(prev, 2, -3))
    assertFalse(MaskTemporalFilter.canBlend(prev, 2, 40))
    assertFalse(MaskTemporalFilter.canBlend(null, 2, 1))
    assertFalse(MaskTemporalFilter.canBlend(prev, 3, 1))
  }
}
