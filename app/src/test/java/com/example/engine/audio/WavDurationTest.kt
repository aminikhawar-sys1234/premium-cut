package com.example.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class WavDurationTest {

  @Test
  fun pcmStereoOneSecondIsOneThousandMs() {
    val bytes = wav(sampleRate = 44100, channels = 2, bits = 16, dataBytes = 176400)
    assertEquals(1000L, WavDuration.durationMs(bytes))
  }

  @Test
  fun oddSizedChunkBeforeDataIsSkipped() {
    val bytes = wav(sampleRate = 16000, channels = 1, bits = 16, dataBytes = 32000, factSize = 1)
    assertEquals(1000L, WavDuration.durationMs(bytes))
  }

  @Test
  fun nonWaveReturnsNull() {
    assertNull(WavDuration.durationMs(ByteArray(64) { 'x'.code.toByte() }))
  }

  @Test
  fun fileRoundTripMatchesDeclaredDataSize() {
    val bytes = wav(sampleRate = 48000, channels = 1, bits = 16, dataBytes = 96000)
    val file = File.createTempFile("speech", ".wav")
    file.writeBytes(bytes)
    try {
      assertEquals(1000L, WavDuration.durationMs(file))
    } finally {
      file.delete()
    }
  }

  private fun wav(
    sampleRate: Int,
    channels: Int,
    bits: Int,
    dataBytes: Int,
    factSize: Int = 0,
  ): ByteArray {
    val blockAlign = channels * bits / 8
    val byteRate = sampleRate * blockAlign
    val fact = if (factSize > 0) {
      val padded = factSize + (factSize and 1)
      ByteArray(8 + padded).also { chunk ->
        writeTag(chunk, 0, "fact")
        writeU32(chunk, 4, factSize.toLong())
        chunk[8] = 7
      }
    } else {
      ByteArray(0)
    }
    val total = 12 + 24 + fact.size + 8
    val out = ByteArray(total)
    writeTag(out, 0, "RIFF")
    writeU32(out, 4, (total - 8).toLong())
    writeTag(out, 8, "WAVE")
    writeTag(out, 12, "fmt ")
    writeU32(out, 16, 16)
    writeU16(out, 20, 1)
    writeU16(out, 22, channels)
    writeU32(out, 24, sampleRate.toLong())
    writeU32(out, 28, byteRate.toLong())
    writeU16(out, 32, blockAlign)
    writeU16(out, 34, bits)
    var o = 36
    fact.copyInto(out, o)
    o += fact.size
    writeTag(out, o, "data")
    writeU32(out, o + 4, dataBytes.toLong())
    return out
  }

  private fun writeTag(dst: ByteArray, offset: Int, tag: String) {
    tag.toByteArray(Charsets.US_ASCII).copyInto(dst, offset)
  }

  private fun writeU16(dst: ByteArray, offset: Int, value: Int) {
    dst[offset] = (value and 0xff).toByte()
    dst[offset + 1] = ((value shr 8) and 0xff).toByte()
  }

  private fun writeU32(dst: ByteArray, offset: Int, value: Long) {
    dst[offset] = (value and 0xff).toByte()
    dst[offset + 1] = ((value shr 8) and 0xff).toByte()
    dst[offset + 2] = ((value shr 16) and 0xff).toByte()
    dst[offset + 3] = ((value shr 24) and 0xff).toByte()
  }
}
