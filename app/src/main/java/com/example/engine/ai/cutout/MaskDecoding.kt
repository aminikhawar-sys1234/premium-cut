package com.example.engine.ai.cutout

import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.FloatBuffer

/**
 * Turns a raw segmentation mask buffer into one confidence byte per pixel (0 = background, 255 = subject),
 * row-major, top-down.
 *
 * Written against `java.nio.Buffer` on purpose: the ML Kit subject segmenter documents the foreground
 * confidence mask as floating-point values in [0, 1] (one per pixel), but the accessor is typed as a
 * plain byte buffer in several releases. Reading the buffer as bytes there produced a mask that was
 * garbage 3 out of every 4 pixels - the classic "background removal is grey noise" report - so both
 * layouts are decoded here explicitly, and neither is guessed from the pixel count alone.
 *
 * Pure Kotlin (no Android types) so the JVM test harness can execute it.
 */
object MaskDecoding {

  /** Confidence values are floats in [0, 1]; anything else is not a mask this file understands. */
  fun confidenceToBytes(mask: Buffer?, pixelCount: Int): ByteArray? {
    if (mask == null || pixelCount <= 0) return null
    return when (mask) {
      is FloatBuffer -> fromFloats(mask, pixelCount)
      is ByteBuffer -> fromByteBuffer(mask, pixelCount)
      else -> null
    }
  }

  private fun fromFloats(buffer: FloatBuffer, pixelCount: Int): ByteArray? {
    if (buffer.capacity() < pixelCount) return null
    val out = ByteArray(pixelCount)
    buffer.rewind()
    for (i in 0 until pixelCount) out[i] = toByte(buffer.get())
    return out
  }

  private fun fromByteBuffer(buffer: ByteBuffer, pixelCount: Int): ByteArray? {
    val capacity = buffer.capacity()
    if (capacity >= pixelCount * 4) {
      // Documented layout: float confidence per pixel, in the buffer's own byte order.
      // (No chained call on rewind(): its covariant return type is not guaranteed by every Kotlin/JDK
      // combination the app is built with.)
      buffer.rewind()
      val floats: FloatBuffer = buffer.asFloatBuffer()
      val out = ByteArray(pixelCount)
      for (i in 0 until pixelCount) out[i] = toByte(floats.get())
      return out
    }
    if (capacity >= pixelCount) {
      // Byte mask (0..255) - the layout some API levels expose.
      buffer.rewind()
      val out = ByteArray(pixelCount)
      for (i in 0 until pixelCount) out[i] = buffer.get()
      return out
    }
    return null
  }

  /** One confidence float -> one alpha byte. */
  fun toByte(confidence: Float): Byte {
    val clamped = if (confidence.isNaN()) 0f else confidence.coerceIn(0f, 1f)
    return (clamped * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
  }

  /**
   * Bytes of a mask the segmenter returned in [sourceWidth] x [sourceHeight], resized to
   * [targetWidth] x [targetHeight] with nearest sampling (nearest keeps the matte edges crisp; a
   * bilinear resample of a hard matte produces halos around the subject).
   */
  fun resampleNearest(
    mask: ByteArray,
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int
  ): ByteArray {
    if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) return mask
    if (sourceWidth == targetWidth && sourceHeight == targetHeight) return mask
    if (mask.size < sourceWidth * sourceHeight) return mask
    val out = ByteArray(targetWidth * targetHeight)
    for (y in 0 until targetHeight) {
      val sy = ((y.toLong() * sourceHeight) / targetHeight).toInt().coerceIn(0, sourceHeight - 1)
      for (x in 0 until targetWidth) {
        val sx = ((x.toLong() * sourceWidth) / targetWidth).toInt().coerceIn(0, sourceWidth - 1)
        out[y * targetWidth + x] = mask[sy * sourceWidth + sx]
      }
    }
    return out
  }

  /** Mean confidence over the whole mask, 0..1. Used to detect a mask that is empty or fully opaque. */
  fun meanConfidence(mask: ByteArray): Float {
    if (mask.isEmpty()) return 0f
    var sum = 0L
    for (b in mask) sum += (b.toInt() and 0xFF)
    return sum.toFloat() / (mask.size * 255f)
  }
}
