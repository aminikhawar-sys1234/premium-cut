package com.example.engine.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class Mp4BoxScannerTest {
  private fun box(type: String, payload: Int): ByteArray {
    val size = 8 + payload
    val out = ByteArrayOutputStream()
    out.write(byteArrayOf((size shr 24).toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte()))
    out.write(type.toByteArray(Charsets.ISO_8859_1))
    out.write(ByteArray(payload))
    return out.toByteArray()
  }

  private fun scan(bytes: ByteArray) =
    Mp4BoxScanner.scan(bytes.size.toLong()) { off ->
      val o = off.toInt()
      if (o >= bytes.size) null else bytes.copyOfRange(o, minOf(o + 16, bytes.size))
    }

  @Test fun completeMp4IsAccepted() {
    val r = scan(box("ftyp", 16) + box("mdat", 5000) + box("moov", 300))
    assertTrue(r.hasFtyp && r.hasMoov && r.hasMdat); assertFalse(r.truncated)
  }

  @Test fun muxerNeverWroteMoov() {
    val r = scan(box("ftyp", 16) + box("mdat", 5000))
    assertFalse(r.hasMoov); assertFalse(r.truncated)
  }

  @Test fun fileCutInsideMdatIsTruncated() {
    val full = box("ftyp", 16) + box("mdat", 5000) + box("moov", 300)
    val r = scan(full.copyOf(2000))
    assertTrue(r.truncated); assertFalse(r.hasMoov); assertNotNull(r.problem)
  }

  @Test fun cutMoovDoesNotCount() {
    val full = box("ftyp", 16) + box("mdat", 100) + box("moov", 300)
    val r = scan(full.copyOf(full.size - 50))
    assertTrue(r.truncated); assertFalse(r.hasMoov)
  }

  @Test fun missingFtypIsFlagged() {
    assertFalse(scan(box("mdat", 5000) + box("moov", 300)).hasFtyp)
  }

  @Test fun garbageIsNotAnMp4() {
    val r = scan(ByteArray(8000) { 0x7F })
    assertFalse(r.hasFtyp); assertTrue(r.truncated)
  }

  @Test fun zeroSizedLastBoxExtendsToEnd() {
    val last = box("mdat", 100).also { it[0] = 0; it[1] = 0; it[2] = 0; it[3] = 0 }
    val r = scan(box("ftyp", 16) + box("moov", 50) + last)
    assertTrue(r.hasMoov && r.hasMdat); assertFalse(r.truncated)
    assertEquals(null, r.problem)
  }
}
