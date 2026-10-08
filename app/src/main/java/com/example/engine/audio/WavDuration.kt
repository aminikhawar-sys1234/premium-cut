package com.example.engine.audio

import java.io.File
import java.io.RandomAccessFile

/**
 * Duration of a PCM WAV file from its fmt and data chunks.
 * Returns null when the file is not a readable WAVE, so callers do not invent a length.
 */
object WavDuration {

  fun durationMs(file: File): Long? {
    if (!file.isFile || file.length() < 44L) return null
    return try {
      RandomAccessFile(file, "r").use { raf ->
        val header = ByteArray(minOf(file.length(), 65536L).toInt())
        val read = raf.read(header)
        if (read < 44) null else durationMs(header, read)
      }
    } catch (_: Throwable) {
      null
    }
  }

  fun durationMs(bytes: ByteArray, length: Int = bytes.size): Long? {
    if (length < 44 || bytes.size < length) return null
    if (tag(bytes, 0) != "RIFF" || tag(bytes, 8) != "WAVE") return null
    var offset = 12
    var byteRate = 0
    var dataBytes = 0L
    while (offset + 8 <= length) {
      val id = tag(bytes, offset)
      val size = u32(bytes, offset + 4)
      val body = offset + 8
      if (id == "fmt " && body + 16 <= length) {
        byteRate = u32(bytes, body + 8).toInt()
      } else if (id == "data") {
        dataBytes = size
        break
      }
      val step = size + if (size % 2L == 1L) 1L else 0L
      val next = body.toLong() + step
      if (next <= offset || next > length) break
      offset = next.toInt()
    }
    if (byteRate <= 0 || dataBytes <= 0L) return null
    return dataBytes * 1000L / byteRate
  }

  private fun tag(bytes: ByteArray, offset: Int): String =
    String(bytes, offset, 4, Charsets.US_ASCII)

  private fun u32(bytes: ByteArray, offset: Int): Long {
    val b0 = bytes[offset].toLong() and 0xff
    val b1 = bytes[offset + 1].toLong() and 0xff
    val b2 = bytes[offset + 2].toLong() and 0xff
    val b3 = bytes[offset + 3].toLong() and 0xff
    return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
  }
}
