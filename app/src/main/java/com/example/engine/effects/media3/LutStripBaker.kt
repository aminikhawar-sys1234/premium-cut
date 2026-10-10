package com.example.engine.effects.media3

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.vfx.engine.core.lut.CubeLut
import com.vfx.engine.core.lut.CubeLutParser
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Bakes a bundled .cube LUT into the Media3 vertical-strip format (N × N²)
 * so ExoPlayer live preview can run the same look the export ColorGradeStage uses.
 */
object LutStripBaker {
  private const val DEFAULT_SIZE = 16
  private val bitmaps = ConcurrentHashMap<String, Bitmap>()
  private val cubes = ConcurrentHashMap<String, CubeLut>()

  fun loadCube(context: Context, lutId: String): CubeLut? {
    cubes[lutId]?.let { return it }
    val lut = runCatching {
      context.applicationContext.assets.open("luts/$lutId.cube").bufferedReader().use {
        CubeLutParser.parse(it.readText())
      }
    }.getOrNull()?.takeIf { it.is3D }
    if (lut != null) cubes[lutId] = lut
    return lut
  }

  fun bitmapFor(context: Context, lutId: String): Bitmap? {
    bitmaps[lutId]?.takeIf { !it.isRecycled }?.let { return it }
    val lut = loadCube(context, lutId) ?: return null
    val bmp = toVerticalStrip(lut, DEFAULT_SIZE)
    bitmaps[lutId] = bmp
    return bmp
  }

  fun toVerticalStrip(lut: CubeLut, cubeLength: Int = DEFAULT_SIZE): Bitmap {
    val n = cubeLength.coerceIn(8, 32)
    val w = n
    val h = n * n
    val pixels = IntArray(w * h)
    val sample = FloatArray(3)
    val step = 1f / (n - 1).coerceAtLeast(1)
    for (r in 0 until n) {
      val rNorm = r * step
      for (g in 0 until n) {
        val gNorm = g * step
        for (b in 0 until n) {
          val bNorm = b * step
          lut.sample3dTrilinear(rNorm, gNorm, bNorm, sample)
          val color = Color.argb(
            255,
            (sample[0].coerceIn(0f, 1f) * 255f).roundToInt(),
            (sample[1].coerceIn(0f, 1f) * 255f).roundToInt(),
            (sample[2].coerceIn(0f, 1f) * 255f).roundToInt()
          )
          pixels[(r * n + g) * w + b] = color
        }
      }
    }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
  }
}
