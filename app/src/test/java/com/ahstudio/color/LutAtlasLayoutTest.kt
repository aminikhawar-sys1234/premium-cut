package com.ahstudio.color

import com.ahstudio.color.lut.LutAtlasLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 2D LUT atlas layout has to agree with three separate consumers at once: the uploader
 * (`LutTextureManager.getOrCreate2DAtlas`), the `#if HAS_LUT2D` branch of the fragment shader
 * (`uv = vec2(z * n + x, y) * vec2(1/(n*n), 1/n) + half texel`) and the CPU reference
 * (`LutSampler.sampleTrilinear`, `.cube` order with red fastest).
 *
 * `toolchain/verify/verify_texture_units.py` re-derives this layout from the checked-in GLSL and
 * compares it against the CPU sampler numerically, so a change on either side is caught here.
 *
 * Run: toolchain/verify/run_logic_tests.sh
 */
class LutAtlasLayoutTest {

    @Test fun atlasIsOneTilePerBlueSlice() {
        for (n in intArrayOf(2, 3, 5, 17, 33)) {
            assertEquals("width is n slices of n texels", n * n, LutAtlasLayout.width(n))
            assertEquals("height is one row per green step", n, LutAtlasLayout.height(n))
        }
    }

    @Test fun columnAndRowMatchTheShaderFormula() {
        // Shader: uv.xy = vec2(z * n + x, y) * vec2(1/(n*n), 1/n), with x=red, y=green, z=blue.
        val n = 5
        for (blue in 0 until n) {
            for (red in 0 until n) {
                assertEquals(
                    "column = blue * n + red",
                    blue * n + red,
                    LutAtlasLayout.column(n, red, blue)
                )
            }
        }
        for (green in 0 until n) {
            assertEquals("row = green", green, LutAtlasLayout.row(green))
        }
    }

    @Test fun atlasTexelAddressesTheCubeOrderedData() {
        // Lut3D.data is `.cube` order: index = (red + green * n + blue * n * n) * 3.
        val n = 3
        for (blue in 0 until n) {
            for (green in 0 until n) {
                for (red in 0 until n) {
                    val column = LutAtlasLayout.column(n, red, blue)
                    val row = LutAtlasLayout.row(green)
                    val expected = (red + green * n + blue * n * n) * 3
                    assertEquals(
                        "entry ($red,$green,$blue) lives at atlas texel ($column,$row)",
                        expected,
                        LutAtlasLayout.dataOffset(n, column, row)
                    )
                    assertEquals(
                        "and at that same offset when addressed by channel",
                        expected,
                        LutAtlasLayout.dataOffsetAt(n, red, green, blue)
                    )
                }
            }
        }
    }

    @Test fun everyAtlasTexelMapsToADistinctLutEntry() {
        // n*n columns x n rows must cover exactly the n*n*n entries of the LUT, once each: a
        // transposed or "slice-major rows" layout would collide and render a wrong grade.
        for (n in intArrayOf(2, 3, 4, 8)) {
            val seen = HashSet<Int>()
            for (row in 0 until LutAtlasLayout.height(n)) {
                for (column in 0 until LutAtlasLayout.width(n)) {
                    val offset = LutAtlasLayout.dataOffset(n, column, row)
                    assertTrue("offset $offset is 3-component aligned", offset % 3 == 0)
                    val entry = offset / 3
                    assertTrue("entry $entry is inside the LUT", entry in 0 until n * n * n)
                    assertTrue("entry $entry appears once", seen.add(entry))
                }
            }
            assertEquals("atlas covers every LUT entry", n * n * n, seen.size)
        }
    }

    @Test fun uniformSizeIsNeverZero() {
        // `uLutSize` divides the shader's atlas walk. A zero there is inf/NaN uv -> black frames,
        // so the value handed to the uniform is clamped even when the LUT is missing.
        assertEquals(2f, LutAtlasLayout.uniformSize(0), 0f)
        assertEquals(2f, LutAtlasLayout.uniformSize(1), 0f)
        assertEquals(2f, LutAtlasLayout.uniformSize(2), 0f)
        assertEquals("size is passed through unchanged", 33f, LutAtlasLayout.uniformSize(33), 0f)
        assertTrue("33 is a supported LUT size", LutAtlasLayout.isSupportedSize(33))
        assertFalse("a 1-entry LUT cannot be uploaded as an atlas", LutAtlasLayout.isSupportedSize(1))
        assertFalse("a 1-entry LUT cannot be uploaded as an atlas", LutAtlasLayout.isSupportedSize(0))
    }
}
