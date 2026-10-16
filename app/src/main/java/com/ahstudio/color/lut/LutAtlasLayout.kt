package com.ahstudio.color.lut

/**
 * Address math for the 2D LUT atlas used by contexts that cannot sample a `sampler3D`
 * (see `HAS_LUT2D` in `com.ahstudio.color.gpu.ColorShaderSource`, uploaded by
 * `com.ahstudio.color.gpu.LutTextureManager.getOrCreate2DAtlas`).
 *
 * A 3D LUT of size `n` is stored as one 2D texture of `n * n` columns by `n` rows:
 *
 * ```
 *   column = blue * n + red          (blue picks the slice, red runs inside it)
 *   row    = green
 * ```
 *
 * `size` rows of `size` slices, exactly the addressing the fragment shader uses:
 *
 * ```glsl
 *   vec2 ts  = vec2(1.0 / (n * n), 1.0 / n);
 *   vec2 uv0 = vec2(z * n + x, y) * ts + ts * 0.5;   // texel centre, so LINEAR cannot bleed
 * ```
 *
 * This file is pure Kotlin (no `android.*`) on purpose: the layout is what the shader, the
 * uploader and the CPU reference all have to agree on, so it is unit-tested on the plain JVM
 * (`app/src/test/java/com/ahstudio/color/LutAtlasLayoutTest.kt`, run by
 * `toolchain/verify/run_logic_tests.sh`). `toolchain/verify/verify_texture_units.py` checks the
 * GLSL above still matches this class.
 */
object LutAtlasLayout {

    /** Atlas width in texels: `n` slices of `n` texels side by side. */
    fun width(size: Int): Int = size * size

    /** Atlas height in texels: one row per green step. */
    fun height(size: Int): Int = size

    /** Column of the texel that holds LUT entry `(red, green, blue)`. */
    fun column(size: Int, red: Int, blue: Int): Int = blue * size + red

    /** Row of the texel that holds LUT entry `(red, green, blue)`. */
    fun row(green: Int): Int = green

    /**
     * Offset into [Lut3D.data] of the entry stored at atlas texel `(column, row)`.
     * `Lut3D.data` is `.cube` order — red fastest, then green, then blue — i.e.
     * `(red + green * n + blue * n * n) * 3`.
     */
    fun dataOffset(size: Int, column: Int, row: Int): Int {
        val blue = column / size
        val red = column % size
        val green = row
        return (red + green * size + blue * size * size) * 3
    }

    /** Offset into [Lut3D.data] of LUT entry `(red, green, blue)`. */
    fun dataOffsetAt(size: Int, red: Int, green: Int, blue: Int): Int =
        (red + green * size + blue * size * size) * 3

    /**
     * The uniform value the atlas shader needs: `uLutSize` is the LUT's edge length, never 0.
     * A zero there divides by zero inside the shader, which turns every atlas coordinate into
     * NaN/inf, samples black and is what exported graded clips as a black video.
     */
    fun uniformSize(size: Int): Float = (if (size >= 2) size else 2).toFloat()

    /** True when [size] can be uploaded as an atlas (a LUT needs at least two samples per axis). */
    fun isSupportedSize(size: Int): Boolean = size >= 2 && size <= 256
}
