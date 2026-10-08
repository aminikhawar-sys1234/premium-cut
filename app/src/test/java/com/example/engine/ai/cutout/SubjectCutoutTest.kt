package com.example.engine.ai.cutout

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubjectCutoutTest {

  @Test
  fun defaultParamsEncodeToNull() {
    assertNull(BgRemoveCodec.encode(BgRemoveParams()))
    assertEquals(BgRemoveParams(), BgRemoveCodec.decode(null))
    assertEquals(BgRemoveParams(), BgRemoveCodec.decode(""))
  }

  @Test
  fun codecRoundTrips() {
    val p = BgRemoveParams(strength = 0.8f, softness = 0.1f, mode = BgRemoveParams.MODE_COLOR, bgColor = 0xFF00B140.toInt())
    assertEquals(p, BgRemoveCodec.decode(BgRemoveCodec.encode(p)))
  }

  @Test
  fun decodeClampsAndToleratesGarbage() {
    val p = BgRemoveCodec.decode("9,-3,7,notanumber")
    assertEquals(1f, p.strength, 0f)
    assertEquals(0f, p.softness, 0f)
    assertEquals(2, p.mode)
    assertEquals(BgRemoveParams().bgColor, p.bgColor)
  }

  @Test
  fun registryEpochChangesOnlyWhenClipSetChanges() {
    SubjectCutoutRegistry.clear()
    val e0 = SubjectCutoutRegistry.epoch
    SubjectCutoutRegistry.update(mapOf("a" to BgRemoveParams()))
    val e1 = SubjectCutoutRegistry.epoch
    assertTrue(e1 > e0)
    SubjectCutoutRegistry.update(mapOf("a" to BgRemoveParams(strength = 0.9f)))
    assertEquals(e1, SubjectCutoutRegistry.epoch)
    assertTrue(SubjectCutoutRegistry.isActive("a"))
    SubjectCutoutRegistry.clear()
  }

  @Test
  fun rotateThenInverseRotateIsIdentity() {
    val w = 3; val h = 2
    val src = ByteArray(w * h) { it.toByte() }
    for (deg in intArrayOf(90, 180, 270)) {
      val (rw, rh) = MaskGeometry.rotatedSize(w, h, deg)
      val rotated = MaskGeometry.rotateCw(src, w, h, deg)
      val back = MaskGeometry.rotateCw(rotated, rw, rh, (360 - deg) % 360)
      assertArrayEquals("deg=$deg", src, back)
    }
  }

  @Test
  fun rotate90MatchesClockwiseDefinition() {
    // 3x2 image:  0 1 2 / 3 4 5  ->  rotated clockwise (2x3):  3 0 / 4 1 / 5 2
    val src = intArrayOf(0, 1, 2, 3, 4, 5)
    assertArrayEquals(intArrayOf(3, 0, 4, 1, 5, 2), MaskGeometry.rotateCw(src, 3, 2, 90))
  }

  @Test
  fun flipRowsReversesRowOrder() {
    val src = byteArrayOf(1, 2, 3, 4, 5, 6) // 3x2
    assertArrayEquals(byteArrayOf(4, 5, 6, 1, 2, 3), MaskGeometry.flipRows(src, 3, 2))
  }

  @Test
  fun readbackConversionFlipsRowsAndCompositesOverBlack() {
    // 1x2 RGBA, bottom row first: bottom = opaque red, top = fully transparent white
    val rgba = byteArrayOf(
      0xFF.toByte(), 0, 0, 0xFF.toByte(),
      0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0
    )
    val argb = MaskGeometry.rgbaBottomUpToArgb(rgba, 1, 2)
    assertEquals(0xFF000000.toInt(), argb[0])   // top row: transparent -> black
    assertEquals(0xFFFF0000.toInt(), argb[1])   // bottom row: red
  }

  @Test
  fun compositeForegroundUsesMaskAlphaAndDoesNotInventCoverage() {
    val src = intArrayOf(0xFF112233.toInt(), 0xFF445566.toInt())
    val mask = byteArrayOf(0, 255.toByte())
    val (cutout, matte) = MaskGeometry.compositeForeground(src, mask)!!
    assertEquals(0x00112233, cutout[0])
    assertEquals(0xFF445566.toInt(), cutout[1])
    assertEquals(0xFF000000.toInt(), matte[0])
    assertEquals(0xFFFFFFFF.toInt(), matte[1])
    assertNull(MaskGeometry.compositeForeground(src, byteArrayOf(1)))
    assertNull(MaskGeometry.compositeForeground(intArrayOf(), byteArrayOf()))
  }
}
