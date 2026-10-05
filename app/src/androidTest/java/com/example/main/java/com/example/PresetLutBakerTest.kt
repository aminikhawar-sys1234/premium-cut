package com.example

import com.example.domain.model.FilterType
import com.example.engine.composition.ColorFilterGenerator
import com.example.engine.composition.PresetLutBaker
import com.vfx.engine.core.lut.CubeLutParser
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PresetLutBakerTest {

  /** Unclamped matrix output; probes near 0 or 1 sit in lattice cells that straddle the clamp. */
  private fun raw(m: FloatArray, r: Float, g: Float, b: Float): FloatArray = FloatArray(3) { ch ->
    val o = ch * 5
    (m[o] * r * 255f + m[o + 1] * g * 255f + m[o + 2] * b * 255f + m[o + 4]) / 255f
  }

  private fun applyMatrix(m: FloatArray, r: Float, g: Float, b: Float): FloatArray = FloatArray(3) { ch ->
    val o = ch * 5
    ((m[o] * r * 255f + m[o + 1] * g * 255f + m[o + 2] * b * 255f + m[o + 4]) / 255f).coerceIn(0f, 1f)
  }

  @Test
  fun identityMatrixBakesToIdentityLut() {
    val id = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
    val lut = PresetLutBaker.bake(id, size = 17)
    val out = FloatArray(3)
    for ((r, g, b) in listOf(Triple(0.1f, 0.5f, 0.9f), Triple(0f, 0f, 0f), Triple(1f, 1f, 1f))) {
      lut.sample3dTrilinear(r, g, b, out)
      assertEquals(r, out[0], 1e-4f); assertEquals(g, out[1], 1e-4f); assertEquals(b, out[2], 1e-4f)
    }
  }

  @Test
  fun everyPresetLutMatchesItsMatrixAwayFromClipEdges() {
    val probes = listOf(
      Triple(0.30f, 0.45f, 0.60f), Triple(0.50f, 0.50f, 0.50f), Triple(0.70f, 0.40f, 0.25f),
      Triple(0.15f, 0.80f, 0.55f), Triple(0.40f, 0.35f, 0.75f)
    )
    val out = FloatArray(3)
    for (type in FilterType.values().filter { it != FilterType.NONE }) {
      for (intensity in listOf(1f, 0.5f)) {
        val m = ColorFilterGenerator.getFilterMatrixArray(type, intensity)
        val lut = PresetLutBaker.bake(m, size = 33, title = type.name)
        for ((r, g, b) in probes) {
          if (raw(m, r, g, b).any { it < 0.06f || it > 0.94f }) continue
          val expected = applyMatrix(m, r, g, b)
          lut.sample3dTrilinear(r, g, b, out)
          for (ch in 0 until 3) assertEquals("$type@$intensity ch$ch", expected[ch], out[ch], 0.02f)
        }
      }
    }
  }

  @Test
  fun cubeTextRoundTripsThroughParser() {
    val m = ColorFilterGenerator.getFilterMatrixArray(FilterType.CINEMATIC, 1f)
    val lut = PresetLutBaker.bake(m, size = 9)
    val parsed = CubeLutParser.parse(PresetLutBaker.toCubeText(lut))
    assertEquals(9, parsed.size3d)
    val a = FloatArray(3); val b = FloatArray(3)
    lut.sample3dTrilinear(0.4f, 0.5f, 0.6f, a); parsed.sample3dTrilinear(0.4f, 0.5f, 0.6f, b)
    for (ch in 0 until 3) assertEquals(a[ch], b[ch], 1e-4f)
  }
}
