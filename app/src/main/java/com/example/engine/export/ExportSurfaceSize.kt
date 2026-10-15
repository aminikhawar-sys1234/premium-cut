package com.example.engine.export

/** Vendor-safe coded dimensions for the GPU-to-encoder surface path, not UI/Media3 sizing. */
object ExportSurfaceSize {
  fun align(width: Int, height: Int): Pair<Int, Int> = alignSide(width) to alignSide(height)

  private fun alignSide(value: Int): Int {
    require(value > 0 && value <= Int.MAX_VALUE - 15) { "Invalid encoder dimension: $value" }
    return ((value + 15) / 16) * 16
  }
}
