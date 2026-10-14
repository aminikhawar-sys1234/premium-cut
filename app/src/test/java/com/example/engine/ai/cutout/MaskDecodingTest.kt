package com.example.engine.ai.cutout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * The segmentation mask decoder: the bug this file pins down was "background removal produces grey noise"
 * on a real device, because the ML Kit foreground-confidence mask is a buffer of **floats in [0, 1]** while
 * it was read as one byte per pixel (so three of every four pixels were part of the previous float's bytes).
 */
class MaskDecodingTest {

  private fun floats(vararg values: Float): FloatBuffer =
    FloatBuffer.allocate(values.size).apply { put(values); rewind() }

  private fun floatBytes(vararg values: Float, order: ByteOrder = ByteOrder.nativeOrder()): ByteBuffer {
    val buffer = ByteBuffer.allocate(values.size * 4).order(order)
    values.forEach { buffer.putFloat(it) }
    return buffer.rewind()
  }

  @Test
  fun typedFloatMaskIsReadOneConfidencePerPixel() {
    val mask = MaskDecoding.confidenceToBytes(floats(0f, 0.5f, 1f, 0.25f), 4)
    assertEquals(4, mask!!.size)
    assertEquals(0, mask[0].toInt() and 0xFF)
    assertEquals(128, mask[1].toInt() and 0xFF)
    assertEquals(255, mask[2].toInt() and 0xFF)
    assertEquals(64, mask[3].toInt() and 0xFF)
  }

  @Test
  fun byteBufferOfFloatsIsReadAsFloatsNotBytes() {
    // This is the layout the ML Kit accessor hands back: 4 bytes per pixel, float encoded.
    val mask = MaskDecoding.confidenceToBytes(floatBytes(0f, 1f, 1f, 0f), 4)
    assertEquals(listOf(0, 255, 255, 0), mask!!.map { it.toInt() and 0xFF })
  }

  @Test
  fun byteBufferOfFloatsHonoursLittleEndianAndBigEndianOrder() {
    val little = MaskDecoding.confidenceToBytes(floatBytes(1f, 0f, order = ByteOrder.LITTLE_ENDIAN), 2)
    val big = MaskDecoding.confidenceToBytes(floatBytes(1f, 0f, order = ByteOrder.BIG_ENDIAN), 2)
    assertEquals(listOf(255, 0), little!!.map { it.toInt() and 0xFF })
    assertEquals(listOf(255, 0), big!!.map { it.toInt() and 0xFF })
  }

  @Test
  fun plainByteMaskStillWorks() {
    val bytes = ByteBuffer.allocate(4).apply { put(byteArrayOf(0, -1, 100, 50)); rewind() }
    val mask = MaskDecoding.confidenceToBytes(bytes, 4)
    assertEquals(listOf(0, 255, 100, 50), mask!!.map { it.toInt() and 0xFF })
  }

  @Test
  fun wrongSizedOrMissingMaskIsRejectedInsteadOfGuessed() {
    assertNull(MaskDecoding.confidenceToBytes(null, 4))
    assertNull(MaskDecoding.confidenceToBytes(floats(0f, 1f), 4))
    assertNull(MaskDecoding.confidenceToBytes(ByteBuffer.allocate(3).apply { rewind() }, 4))
    assertNull(MaskDecoding.confidenceToBytes(floats(1f), 0))
  }

  @Test
  fun outOfRangeAndNaNFloatsClampInsteadOfWrappingAround() {
    assertEquals(255, MaskDecoding.toByte(2.5f).toInt() and 0xFF)
    assertEquals(0, MaskDecoding.toByte(-3f).toInt() and 0xFF)
    assertEquals(0, MaskDecoding.toByte(Float.NaN).toInt() and 0xFF)
  }

  @Test
  fun resampleNearestKeepsTheMatteEdgesCrisp() {
    // 2x2 mask: subject on the right column.
    val mask = byteArrayOf(0, 0, 0, -1, -1, -1, -1, -1).copyOf(4)
    val up = MaskDecoding.resampleNearest(byteArrayOf(0, -1, 0, -1), 2, 2, 4, 4)
    assertEquals(16, up.size)
    // No interpolated in-between values: every output pixel is one of the two input values.
    assertTrue(up.all { it == 0.toByte() || it == (-1).toByte() })
    assertEquals(0, up[0].toInt() and 0xFF)
    assertEquals(255, up[3].toInt() and 0xFF)
    val unchanged = MaskDecoding.resampleNearest(mask, 2, 2, 2, 2)
    assertTrue(unchanged === mask)
  }

  @Test
  fun meanConfidenceDetectsEmptyAndFullMasks() {
    assertEquals(0f, MaskDecoding.meanConfidence(ByteArray(4)), 1e-6f)
    assertEquals(1f, MaskDecoding.meanConfidence(ByteArray(4) { 0xFF.toByte() }), 1e-6f)
    assertEquals(0.5f, MaskDecoding.meanConfidence(byteArrayOf(0, 0xFF.toByte())), 0.01f)
    assertEquals(0f, MaskDecoding.meanConfidence(ByteArray(0)), 1e-6f)
  }
}
