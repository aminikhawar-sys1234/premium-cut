package com.example.engine.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportVideoBitstreamTest {
  private val sc4 = byteArrayOf(0, 0, 0, 1)
  private val sc3 = byteArrayOf(0, 0, 1)
  private val avcSps = sc4 + byteArrayOf(0x67, 0x42, 0x00, 0x1f, 0x11)
  private val avcPps = sc4 + byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
  private val avcIdr = sc4 + byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte(), 0x21)
  private val avcP = sc4 + byteArrayOf(0x41, 0x9A.toByte(), 0x02)

  @Test
  fun `combined codec-config plus IDR buffer splits into CSD and the key frame picture`() {
    val split = ExportVideoBitstream.splitParameterSets(avcSps + avcPps + avcIdr, hevc = false)
    assertArrayEquals(avcSps + avcPps, split.parameterSets)
    assertArrayEquals(avcIdr, split.picture)
    assertTrue(ExportVideoBitstream.containsRandomAccess(split.picture, hevc = false))
  }

  @Test
  fun `pure codec-config buffer has no picture data`() {
    val split = ExportVideoBitstream.splitParameterSets(avcSps + avcPps, hevc = false)
    assertArrayEquals(avcSps + avcPps, split.parameterSets)
    assertEquals(0, split.picture.size)
  }

  @Test
  fun `avc csd separates SPS into csd-0 and PPS into csd-1`() {
    val csd = ExportVideoBitstream.avcCsd(avcSps + avcPps)
    assertNotNull(csd)
    assertArrayEquals(avcSps, csd!!.sps)
    assertArrayEquals(avcPps, csd.pps)
    assertNull(ExportVideoBitstream.avcCsd(avcSps))
  }

  @Test
  fun `three byte start codes and P frames are recognised`() {
    val data = sc3 + byteArrayOf(0x65, 0x10) + avcP
    val nals = ExportVideoBitstream.nals(data, hevc = false)
    assertEquals(listOf(5, 1), nals.map { it.type })
    assertFalse(ExportVideoBitstream.containsRandomAccess(avcP, hevc = false))
  }

  @Test
  fun `hevc VPS SPS PPS become csd-0 and IDR_W_RADL is a random access picture`() {
    val vps = sc4 + byteArrayOf(0x40, 0x01, 0x0C)
    val sps = sc4 + byteArrayOf(0x42, 0x01, 0x01)
    val pps = sc4 + byteArrayOf(0x44, 0x01, 0xC1.toByte())
    val idr = sc4 + byteArrayOf(0x26, 0x01, 0xAF.toByte())
    val split = ExportVideoBitstream.splitParameterSets(vps + sps + pps + idr, hevc = true)
    assertArrayEquals(vps + sps + pps, ExportVideoBitstream.hevcCsd(split.parameterSets))
    assertArrayEquals(idr, split.picture)
    assertTrue(ExportVideoBitstream.containsRandomAccess(split.picture, hevc = true))
  }

  @Test
  fun `data without start codes is passed through as picture`() {
    val raw = byteArrayOf(0x12, 0x34, 0x56)
    val split = ExportVideoBitstream.splitParameterSets(raw, hevc = false)
    assertEquals(0, split.parameterSets.size)
    assertArrayEquals(raw, split.picture)
  }
}
