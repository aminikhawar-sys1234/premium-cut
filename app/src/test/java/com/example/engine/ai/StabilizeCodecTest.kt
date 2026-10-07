package com.example.engine.ai

import com.example.engine.ai.tracking.StabilizeCodec
import com.example.engine.ai.tracking.StabilizeCorrection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StabilizeCodecTest {

  private val frames = listOf(
    StabilizeCorrection(0L, 0.00f, 0.00f, 0f, 1f),
    StabilizeCorrection(1_000_000L, 0.04f, -0.02f, 2f, 1.02f),
    StabilizeCorrection(2_000_000L, -0.03f, 0.01f, -1f, 0.99f)
  )

  @Test
  fun roundTripKeepsFrames() {
    val data = StabilizeCodec.build(0.6f, frames)
    val decoded = StabilizeCodec.decode(StabilizeCodec.encode(data))!!
    assertEquals(3, decoded.frames.size)
    assertEquals(0.04f, decoded.frames[1].dx, 1e-4f)
    assertEquals(data.zoom, decoded.zoom, 1e-3f)
  }

  @Test
  fun zoomCoversLargestShiftAndIsCapped() {
    val data = StabilizeCodec.build(0.5f, frames)
    assertTrue(data.zoom >= 1f + 2f * 0.04f)
    val huge = StabilizeCodec.build(0.5f, listOf(StabilizeCorrection(0L, 0.9f, 0f, 0f, 1f)))
    assertEquals(1.5f, huge.zoom, 1e-6f)
  }

  @Test
  fun sampleInterpolatesAndClamps() {
    val data = StabilizeCodec.build(0.5f, frames)
    assertEquals(0.02f, StabilizeCodec.sample(data, 500_000L).dx, 1e-5f)
    assertEquals(0f, StabilizeCodec.sample(data, -10L).dx, 1e-6f)
    assertEquals(-0.03f, StabilizeCodec.sample(data, 9_000_000L).dx, 1e-6f)
  }

  @Test
  fun malformedInputDecodesToNull() {
    assertNull(StabilizeCodec.decode(null))
    assertNull(StabilizeCodec.decode(""))
    assertNull(StabilizeCodec.decode("garbage"))
    assertNull(StabilizeCodec.decode("v1|0.5|1.1|1,2,3"))
  }

  @Test
  fun warpTermsRoundTripAndWidenZoom() {
    val warped = listOf(
      StabilizeCorrection(0L, 0f, 0f, 0f, 1f, rsX = 0.02f, rsY = -0.01f, persX = 0.03f, persY = -0.02f),
      StabilizeCorrection(1_000_000L, 0f, 0f, 0f, 1f)
    )
    val data = StabilizeCodec.build(0.6f, warped)
    assertTrue(data.zoom > 1.05f)
    val back = StabilizeCodec.decode(StabilizeCodec.encode(data))!!
    assertEquals(0.02f, back.frames[0].rsX, 1e-4f)
    assertEquals(-0.02f, back.frames[0].persY, 1e-4f)
    assertEquals(0.015f, StabilizeCodec.sample(back, 500_000L).persX, 1e-4f)
  }

  @Test
  fun v1StringStillDecodesWithZeroWarp() {
    val d = StabilizeCodec.decode("v1|0.600|1.1000|0,0.01000,0.02000,1.000,1.00000;1000000,0,0,0,1")!!
    assertEquals(0f, d.frames[0].rsX, 0f)
    assertEquals(0.01f, d.frames[0].dx, 1e-5f)
  }

  @Test
  fun warpMatrixIsIdentityWithoutWarpAndShearsWithIt() {
    val m = FloatArray(16)
    StabilizeCodec.warpMatrix(StabilizeCorrection(0L, 0f, 0f, 0f, 1f), m)
    for (i in 0 until 16) assertEquals(if (i % 5 == 0) 1f else 0f, m[i], 0f)
    StabilizeCodec.warpMatrix(StabilizeCorrection(0L, 0f, 0f, 0f, 1f, rsX = 0.1f, persX = 0.2f), m)
    assertEquals(0.1f, m[4], 0f)
    assertEquals(0.2f, m[3], 0f)
  }

  @Test
  fun curvedRowTermsRoundTripAndInterpolate() {
    val data = StabilizeCodec.build(
      0.5f,
      listOf(
        StabilizeCorrection(0L, 0f, 0f, 0f, 1f, rsX2 = 0.02f, rsY2 = -0.01f),
        StabilizeCorrection(1_000_000L, 0f, 0f, 0f, 1f)
      )
    )
    val back = StabilizeCodec.decode(StabilizeCodec.encode(data))!!
    assertEquals(0.02f, back.frames[0].rsX2, 1e-4f)
    assertEquals(-0.01f, back.frames[0].rsY2, 1e-4f)
    assertEquals(0.01f, StabilizeCodec.sample(back, 500_000L).rsX2, 1e-4f)
    assertTrue(StabilizeCodec.hasRowWarp(back.frames[0]))
    assertTrue(!StabilizeCodec.hasRowWarp(back.frames[1]))
  }

  @Test
  fun oldV2StringsStillDecode() {
    val d = StabilizeCodec.decode("v2|0.500|1.0500|0,0.01000,0.00000,0.000,1.00000,0.02000,0.00000,0.00000,0.00000")!!
    assertEquals(0.02f, d.frames[0].rsX, 1e-5f)
    assertEquals(0f, d.frames[0].rsX2, 0f)
  }
}
