package com.example.engine.composition

import com.example.domain.model.ChromaKeySettings
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The one chroma-key algorithm of AH Video Studio.
 *
 * Both drawing paths call into this file's maths:
 *  - [com.example.engine.composition.ChromaKeyProcessor] runs it per pixel (CPU composition / still frames).
 *  - `GpuShaders.CHROMA_KEY_GLSL` is a line-for-line GPU port of [keyPixel] used by the live preview and
 *    the export renderer, so a frame keyed on the GPU matches a frame keyed on the CPU.
 *  `toolchain/verify/verify_chroma_key.py` executes both and asserts they agree.
 *
 * The key is a **chroma-plane distance** in Rec.709 YCbCr, not an RGB distance. On a real green screen the
 * lit floor and the shadowed corner of the same screen are the same colour but very different RGB triples;
 * measuring in chroma keys both and leaves a dark grey object alone. The luma term only enters for
 * (nearly) desaturated key colours (black / white / grey picker picks), where chroma carries no information.
 *
 * No Android types here on purpose: this object is compiled and executed by the JVM test harness.
 */
object ChromaKeyMath {

  /** Rec.709 luma weights, matching the rest of the compositor (ColorLookModel / GpuShaders). */
  const val LUMA_R = 0.2126f
  const val LUMA_G = 0.7152f
  const val LUMA_B = 0.0722f

  /** Rec.709 chroma normalisation (Cb / Cr scale). */
  const val CB_SCALE = 1.8556f
  const val CR_SCALE = 1.5748f

  /**
   * How strongly luma differentiates when the key colour has no chroma at all (black / white keys).
   * 0 would make every grey pixel a key candidate, 1 would key only the exact luminance.
   */
  const val DESATURATED_LUMA_WEIGHT = 0.6f

  /** Below this key chroma length the key counts as grey and the luma term is used. */
  const val MIN_KEY_CHROMA = 0.02f

  /** Largest value the similarity slider can reach; the UI range is 0.05 .. 0.95. */
  const val MAX_THRESHOLD = 0.95f

  /**
   * Slider -> chroma-distance scale for [ChromaKeySettings.similarity], tuned against real green/blue
   * screens: with the shipped defaults (similarity 0.4) a screen that is 0.05 .. 0.15 away from the key
   * colour keys completely, while skin (about 0.43 away) keeps full alpha. The two constants are shared
   * with the GLSL port, so both paths key identically.
   */
  const val SIMILARITY_SCALE = 0.45f

  /** Slider -> ramp width for [ChromaKeySettings.smoothness] (the UI's edge softness). */
  const val SOFTNESS_SCALE = 0.30f

  /** Foreground-opacity slider range (0 = fully transparent subject, 1 = untouched). */
  const val MIN_FOREGROUND_OPACITY = 0f
  const val MAX_FOREGROUND_OPACITY = 1f

  /** Alpha value the keying stage writes for a pixel that is fully inside the key colour. */
  const val KEYED_ALPHA = 0

  /** Chroma of one colour, as [Cb, Cr] with Rec.709 luma folded in as the third element. */
  fun chroma(r: Float, g: Float, b: Float): FloatArray {
    val y = LUMA_R * r + LUMA_G * g + LUMA_B * b
    return floatArrayOf((b - y) / CB_SCALE, (r - y) / CR_SCALE, y)
  }

  /**
   * Keyed alpha for one pixel: 1 keeps the pixel, 0 removes it, values between are the soft edge.
   *
   * @param similarity 0..1 - how far from the key colour still counts as background (the UI `Similarity`).
   * @param softness 0..1 - width of the ramp after [similarity] (the UI `Edge Softness` / `Smoothness`).
   */
  fun alphaFor(
    pixelChroma: FloatArray,
    keyChroma: FloatArray,
    similarity: Float,
    softness: Float
  ): Float {
    val dcb = pixelChroma[0] - keyChroma[0]
    val dcr = pixelChroma[1] - keyChroma[1]
    val chromaDistance = sqrt(dcb * dcb + dcr * dcr)

    val keyLength = sqrt(keyChroma[0] * keyChroma[0] + keyChroma[1] * keyChroma[1])
    var distance = chromaDistance
    if (keyLength < MIN_KEY_CHROMA) {
      val dl = (pixelChroma[2] - keyChroma[2]) * DESATURATED_LUMA_WEIGHT
      distance = sqrt(chromaDistance * chromaDistance + dl * dl)
    }

    val threshold = (similarity.coerceIn(0f, MAX_THRESHOLD)) * SIMILARITY_SCALE
    val feather = max(MIN_FEATHER, softness.coerceIn(0f, 1f) * SOFTNESS_SCALE)
    // smoothstep(threshold, threshold + feather, distance): 0 inside the key, 1 well outside it.
    val t = ((distance - threshold) / feather).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
  }

  /** True when the pixel colour is inside the key and would be removed at this similarity. */
  fun isKeyed(rgb: Int, settings: ChromaKeySettings): Boolean =
    alphaFor(chromaOfPixel(rgb), keyChromaOf(settings), settings.similarity, settings.smoothness) < 0.5f

  /**
   * Spill strength for a pixel that came out of the key with [keyedAlpha] (1 = solid subject, 0 = key).
   *
   * The global spill slider de-spills the whole subject; the edge-cleanup slider is added on the *soft edge
   * only*, scaled by how transparent the pixel is, because that is where a green screen leaves its brightest
   * fringe (hair, motion-blurred hands). Away from the edge it adds nothing, so skin tones are not flattened.
   */
  fun spillStrength(keyedAlpha: Float, settings: ChromaKeySettings): Float {
    val edge = settings.edgeControl.coerceIn(0f, 1f) * (1f - keyedAlpha.coerceIn(0f, 1f))
    return (settings.spillSuppression.coerceIn(0f, 1f) + edge).coerceIn(0f, 1f)
  }

  /**
   * Spill suppression: removes the part of the pixel's chroma that points along the key colour's chroma
   * direction (the green cast on hair and shoulders, or the blue cast on a blue screen). Independent of
   * which colour was keyed - the old green-only branch never touched a blue key.
   *
   * @param spill 0..1
   * @return the despilled RGB triple.
   */
  fun suppressSpill(
    rgb: FloatArray,
    pixelChroma: FloatArray,
    keyChroma: FloatArray,
    spill: Float
  ): FloatArray {
    val s = spill.coerceIn(0f, 1f)
    if (s <= 0f) return rgb
    val keyLength = sqrt(keyChroma[0] * keyChroma[0] + keyChroma[1] * keyChroma[1])
    if (keyLength < 1e-5f) return rgb
    val kx = keyChroma[0] / keyLength
    val ky = keyChroma[1] / keyLength
    val projection = pixelChroma[0] * kx + pixelChroma[1] * ky
    if (projection <= 0f) return rgb
    val cb = pixelChroma[0] - kx * projection * s
    val cr = pixelChroma[1] - ky * projection * s
    return yCbCrToRgb(pixelChroma[2], cb, cr)
  }

  /** Inverse of [chroma]; the result is clamped to the displayable range. */
  fun yCbCrToRgb(y: Float, cb: Float, cr: Float): FloatArray {
    val r = y + cr * CR_SCALE
    val b = y + cb * CB_SCALE
    val g = (y - LUMA_R * r - LUMA_B * b) / LUMA_G
    return floatArrayOf(
      r.coerceIn(0f, 1f),
      g.coerceIn(0f, 1f),
      b.coerceIn(0f, 1f)
    )
  }

  /**
   * Keys one ARGB pixel. Returns the pixel as drawn over the keyer's output:
   * alpha = original alpha * keyed alpha * foreground opacity, RGB = subject colours with spill removed.
   * A fully keyed pixel gets [KEYED_ALPHA] so the layer below shows through (the shader discards it).
   */
  fun keyPixel(pixel: Int, settings: ChromaKeySettings, keyChroma: FloatArray = keyChromaOf(settings)): Int {
    val srcA = (pixel ushr 24) and 0xFF
    if (srcA == 0) return pixel
    val r = ((pixel ushr 16) and 0xFF) / 255f
    val g = ((pixel ushr 8) and 0xFF) / 255f
    val b = (pixel and 0xFF) / 255f
    val chroma = chroma(r, g, b)

    val alpha = alphaFor(chroma, keyChroma, settings.similarity, settings.smoothness)
    val fg = settings.foregroundOpacity.coerceIn(MIN_FOREGROUND_OPACITY, MAX_FOREGROUND_OPACITY)
    val outAlpha = (srcA * alpha * fg + 0.5f).toInt().coerceIn(0, 255)
    if (outAlpha == 0) return 0

    // Edge cleanup rides on top of the global spill slider, on the soft edge only (see spillStrength).
    val despilled = suppressSpill(floatArrayOf(r, g, b), chroma, keyChroma, spillStrength(alpha, settings))
    val outR = (despilled[0] * 255f + 0.5f).toInt().coerceIn(0, 255)
    val outG = (despilled[1] * 255f + 0.5f).toInt().coerceIn(0, 255)
    val outB = (despilled[2] * 255f + 0.5f).toInt().coerceIn(0, 255)
    return (outAlpha shl 24) or (outR shl 16) or (outG shl 8) or outB
  }

  /**
   * Keys a whole frame in place-equivalent form. [background] paints the removed pixels instead of
   * making them transparent (solid-colour replacement); null keeps them transparent.
   */
  fun apply(pixels: IntArray, settings: ChromaKeySettings, background: Int? = null): IntArray {
    if (!settings.enabled) return pixels
    val key = keyChromaOf(settings)
    val out = IntArray(pixels.size)
    val bgR = if (background != null) ((background ushr 16) and 0xFF) else 0
    val bgG = if (background != null) ((background ushr 8) and 0xFF) else 0
    val bgB = if (background != null) (background and 0xFF) else 0
    for (i in pixels.indices) {
      val keyed = keyPixel(pixels[i], settings, key)
      val alpha = (keyed ushr 24) and 0xFF
      if (background == null) {
        out[i] = keyed
        continue
      }
      // Composite the (possibly soft) subject over the replacement colour.
      val a = alpha / 255f
      val inv = 1f - a
      val r = (((keyed ushr 16) and 0xFF) * a + bgR * inv + 0.5f).toInt().coerceIn(0, 255)
      val g = (((keyed ushr 8) and 0xFF) * a + bgG * inv + 0.5f).toInt().coerceIn(0, 255)
      val b = ((keyed and 0xFF) * a + bgB * inv + 0.5f).toInt().coerceIn(0, 255)
      val srcA = (pixels[i] ushr 24) and 0xFF
      out[i] = (srcA shl 24) or (r shl 16) or (g shl 8) or b
    }
    return out
  }

  /** [Cb, Cr, Y] of the key colour. */
  fun keyChromaOf(settings: ChromaKeySettings): FloatArray {
    val c = settings.targetColor.toInt()
    return chroma(
      ((c ushr 16) and 0xFF) / 255f,
      ((c ushr 8) and 0xFF) / 255f,
      (c and 0xFF) / 255f
    )
  }

  fun chromaOfPixel(pixel: Int): FloatArray = chroma(
    ((pixel ushr 16) and 0xFF) / 255f,
    ((pixel ushr 8) and 0xFF) / 255f,
    (pixel and 0xFF) / 255f
  )

  /**
   * Nearest colour of [palette] to [rgb] under the same chroma metric, or null when [rgb] is not closer
   * to any palette entry than [maxDistance]. Used to name the picked key colour ("Green screen", ...).
   */
  fun nearestPreset(rgb: Int, palette: List<Pair<String, Long>>, maxDistance: Float = 0.35f): String? {
    val target = chromaOfPixel(rgb)
    var best: String? = null
    var bestDistance = Float.MAX_VALUE
    for ((label, value) in palette) {
      val candidate = keyChromaOfPreset(value)
      val d = sqrt(
        (target[0] - candidate[0]) * (target[0] - candidate[0]) +
          (target[1] - candidate[1]) * (target[1] - candidate[1])
      )
      if (d < bestDistance) {
        bestDistance = d
        best = label
      }
    }
    return if (bestDistance <= maxDistance) best else null
  }

  private fun keyChromaOfPreset(color: Long): FloatArray {
    val c = color.toInt()
    return chroma(
      ((c ushr 16) and 0xFF) / 255f,
      ((c ushr 8) and 0xFF) / 255f,
      (c and 0xFF) / 255f
    )
  }

  /** Smallest feather the keyer is allowed to use, so a zero slider still produces a soft edge. */
  const val MIN_FEATHER = 0.001f

  /** Convenience for callers that only have ints; mirrors the shader's clamp. */
  fun clamp255(v: Float): Int = min(255, max(0, (v * 255f + 0.5f).toInt()))

  /** True when a setting would visibly change pixels (used by tests and by the panel's "is it on"). */
  fun isActive(settings: ChromaKeySettings): Boolean = settings.enabled

  /** Absolute difference helper used by the CPU/GPU parity harness. */
  fun maxChannelDelta(a: Int, b: Int): Int {
    var d = abs(((a ushr 24) and 0xFF) - ((b ushr 24) and 0xFF))
    d = max(d, abs(((a ushr 16) and 0xFF) - ((b ushr 16) and 0xFF)))
    d = max(d, abs(((a ushr 8) and 0xFF) - ((b ushr 8) and 0xFF)))
    d = max(d, abs((a and 0xFF) - (b and 0xFF)))
    return d
  }
}
