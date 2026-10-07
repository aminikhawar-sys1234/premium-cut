package com.example.ui.components.filter

import android.content.Context
import android.graphics.Bitmap
import com.vfx.engine.core.lut.CubeLut
import com.vfx.engine.core.lut.CubeLutParser
import java.util.concurrent.ConcurrentHashMap

/**
 * CPU preview of a bundled .cube LUT on a small thumbnail, so each LUT look card shows the
 * look it will actually produce (trilinear sampling, same maths as the GPU path). Thumbnails are
 * tiny (96px), so this is a few thousand samples; callers run it off the main thread.
 */
object LutLookPreview {
  private val luts = ConcurrentHashMap<String, CubeLut>()

  fun loadLut(context: Context, lutId: String): CubeLut? {
    luts[lutId]?.let { return it }
    val lut = runCatching {
      context.applicationContext.assets.open("luts/$lutId.cube").bufferedReader().use { CubeLutParser.parse(it.readText()) }
    }.getOrNull()?.takeIf { it.is3D }
    if (lut != null) luts[lutId] = lut
    return lut
  }

  /** Returns a new bitmap graded by [lut]; the source is never modified. */
  fun apply(source: Bitmap, lut: CubeLut): Bitmap {
    val w = source.width
    val h = source.height
    val px = IntArray(w * h)
    source.getPixels(px, 0, w, 0, 0, w, h)
    val out = FloatArray(3)
    for (i in px.indices) {
      val c = px[i]
      lut.sample3dTrilinear(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f, out)
      val r = (out[0].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
      val g = (out[1].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
      val b = (out[2].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
      px[i] = (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
    }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
  }
}
