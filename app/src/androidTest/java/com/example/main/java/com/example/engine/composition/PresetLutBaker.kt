package com.example.engine.composition

import com.vfx.engine.core.lut.CubeLut

/**
 * Bakes a filter preset's 4x5 colour matrix (Android ColorMatrix layout, offsets in 0..255)
 * into a 3D LUT, so presets can be previewed and, when wanted, graded through the same LUT
 * path as the bundled .cube looks. Pure Kotlin, no Android types.
 *
 * Android applies a ColorMatrix to 0..255 sRGB values and clamps once at the end; the bake does
 * the same per lattice point. The only difference from the matrix is trilinear interpolation
 * across lattice cells that straddle the clamp edge.
 */
object PresetLutBaker {
  const val DEFAULT_SIZE = 33

  fun bake(matrix: FloatArray, size: Int = DEFAULT_SIZE, title: String = ""): CubeLut {
    require(matrix.size >= 20) { "ColorMatrix array needs 20 floats" }
    require(size >= 2) { "LUT size must be >= 2" }
    val data = FloatArray(size * size * size * 3)
    val last = (size - 1).toFloat()
    // .cube order: red varies fastest, then green, then blue (matches CubeLut.entry3d).
    for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
      val r255 = r / last * 255f
      val g255 = g / last * 255f
      val b255 = b / last * 255f
      val i = (r + g * size + b * size * size) * 3
      for (ch in 0 until 3) {
        val o = ch * 5
        val v = matrix[o] * r255 + matrix[o + 1] * g255 + matrix[o + 2] * b255 + matrix[o + 4]
        data[i + ch] = (v / 255f).coerceIn(0f, 1f)
      }
    }
    return CubeLut(size3d = size, size1d = null, data3d = data, data1d = null, title = title)
  }

  /** .cube text for [lut] (3D only), so a baked preset can be stored next to the bundled LUTs. */
  fun toCubeText(lut: CubeLut): String {
    val n = requireNotNull(lut.size3d) { "3D LUT required" }
    val d = requireNotNull(lut.data3d)
    val sb = StringBuilder()
    if (lut.title.isNotBlank()) sb.append("TITLE \"").append(lut.title.replace('"', '\'')).append("\"\n")
    sb.append("LUT_3D_SIZE ").append(n).append('\n')
    var i = 0
    while (i < d.size) {
      sb.append(d[i]).append(' ').append(d[i + 1]).append(' ').append(d[i + 2]).append('\n')
      i += 3
    }
    return sb.toString()
  }
}
