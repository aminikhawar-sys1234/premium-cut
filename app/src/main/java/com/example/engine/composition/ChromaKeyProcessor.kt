package com.example.engine.composition

import android.graphics.Bitmap
import com.example.domain.model.ChromaKeySettings

/**
 * Bitmap wrapper around [ChromaKeyMath] for the Canvas / still-frame composition path.
 *
 * The maths is the same object the GPU shader ports line for line, so a frame keyed here and a frame keyed on
 * the GPU agree. Background media (image / video) is composited by the GPU path only, because this path has no
 * media decoder; [ChromaKeyResolver.usesBackgroundMedia] backgrounds therefore leave the key transparent here
 * instead of drawing an unrequested black frame.
 */
object ChromaKeyProcessor {

  /**
   * Applies chroma key (green screen / blue screen / custom colour keying) to a Bitmap and returns the keyed
   * copy. The source is never modified and is never recycled.
   */
  fun applyChromaKey(source: Bitmap, settings: ChromaKeySettings): Bitmap {
    if (!settings.enabled) return source

    val width = source.width
    val height = source.height
    if (width <= 0 || height <= 0) return source
    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)

    val background = if (ChromaKeyResolver.usesSolidBackground(settings)) settings.backgroundColor.toInt() else null
    val keyed = ChromaKeyMath.apply(pixels, settings, background)

    val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    output.setPixels(keyed, 0, width, 0, 0, width, height)
    return output
  }

  /** Keyed alpha (0..255) of one pixel; exposed for tests and diagnostics. */
  fun alphaOf(pixel: Int, settings: ChromaKeySettings): Int =
    (ChromaKeyMath.keyPixel(pixel, settings) ushr 24) and 0xFF
}
