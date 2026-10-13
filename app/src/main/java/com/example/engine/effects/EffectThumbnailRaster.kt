package com.example.engine.effects

import android.graphics.Bitmap
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Icon-resolution processing of the same effect keys the GPU path uses.
 * Live tiles pass the current clip frame; the fallback scene is only used when
 * no clip thumbnail is available yet.
 */
object EffectThumbnailRaster {

  fun argb(shaderKey: String?, size: Int = 48): IntArray {
    val n = size.coerceIn(8, 96)
    val src = IntArray(n * n)
    for (y in 0 until n) {
      for (x in 0 until n) src[y * n + x] = basePixel(x, y, n)
    }
    return apply(shaderKey, src, n, n)
  }

  fun apply(shaderKey: String?, src: IntArray, width: Int, height: Int): IntArray {
    val w = width.coerceAtLeast(1)
    val h = height.coerceAtLeast(1)
    if (src.size < w * h) return src
    if (shaderKey.isNullOrBlank()) return src.copyOf(w * h)
    val out = IntArray(w * h)
    for (y in 0 until h) {
      for (x in 0 until w) out[y * w + x] = shade(shaderKey, src, x, y, w, h)
    }
    return out
  }

  fun applyToBitmap(src: Bitmap, shaderKey: String?): Bitmap {
    if (src.isRecycled || src.width <= 0 || src.height <= 0) return src
    val w = src.width
    val h = src.height
    val pixels = IntArray(w * h)
    src.getPixels(pixels, 0, w, 0, 0, w, h)
    val out = apply(shaderKey, pixels, w, h)
    if (shaderKey.isNullOrBlank()) {
      return src.config?.let { src.copy(it, false) } ?: src
    }
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    bmp.setPixels(out, 0, w, 0, 0, w, h)
    return bmp
  }

  private fun basePixel(x: Int, y: Int, n: Int): Int {
    val u = x / (n - 1f)
    val v = y / (n - 1f)
    var r = 0.15f + 0.55f * u
    var g = 0.25f + 0.15f * (1f - v)
    var b = 0.55f * (1f - u) + 0.08f
    val face = ellipse(u, v, 0.36f, 0.30f, 0.13f, 0.17f)
    if (face > 0f) {
      r = mix(r, 0.93f, face); g = mix(g, 0.72f, face); b = mix(b, 0.58f, face)
      val mouth = ellipse(u, v, 0.36f, 0.38f, 0.045f, 0.018f)
      if (mouth > 0.4f) { r = 0.95f; g = 0.93f; b = 0.82f }
    }
    val torso = rect(u, v, 0.27f, 0.50f, 0.48f, 0.82f)
    if (torso) { r = 0.20f; g = 0.45f; b = 0.72f }
    val legs = rect(u, v, 0.30f, 0.82f, 0.45f, 0.98f)
    if (legs) { r = 0.18f; g = 0.32f; b = 0.55f }
    val pop = ellipse(u, v, 0.74f, 0.34f, 0.11f, 0.11f)
    if (pop > 0f) { r = mix(r, 0.92f, pop); g = mix(g, 0.12f, pop); b = mix(b, 0.10f, pop) }
    return pack(r, g, b)
  }

  private fun shade(key: String, src: IntArray, x: Int, y: Int, w: Int, h: Int): Int {
    val u = x / (w - 1f).coerceAtLeast(1f)
    val v = y / (h - 1f).coerceAtLeast(1f)
    return when (key) {
      "vfx:blur.directional" -> average(src, x, y, w, h, 5, 1, 0)
      "vfx:distort.glitch" -> {
        val band = (v * 14f).toInt()
        val shift = if (band % 3 == 0) (hash(band.toFloat(), 2f) - 0.5f) * w * 0.18f else 0f
        val sx = (x + shift).toInt()
        val c = sample(src, sx, y, w, h)
        val cr = sample(src, sx + 2, y, w, h)
        val cb = sample(src, sx - 2, y, w, h)
        pack(red(cr), green(c), blue(cb))
      }
      "vfx:chromatic.aberration" -> {
        val dx = ((u - 0.5f) * 5f).toInt()
        pack(red(sample(src, x + dx, y, w, h)), green(sample(src, x, y, w, h)), blue(sample(src, x - dx, y, w, h)))
      }
      "vfx:noise.filmGrain" -> {
        val c = src[y * w + x]
        val g = (hash(x.toFloat(), y.toFloat()) - 0.5f) * 0.45f
        pack(red(c) + g, green(c) + g, blue(c) + g)
      }
      "vfx:light.leak" -> {
        val c = src[y * w + x]
        val blob = exp(-hypot(u - 0.18f, v - 0.78f) * 3.2f)
        pack(
          1f - (1f - red(c)) * (1f - (blob * 1.15f).coerceIn(0f, 1f)),
          1f - (1f - green(c)) * (1f - (0.45f * blob * 1.15f).coerceIn(0f, 1f)),
          1f - (1f - blue(c)) * (1f - (0.12f * blob * 1.15f).coerceIn(0f, 1f))
        )
      }
      "vfx:color.hdr" -> {
        val c = src[y * w + x]
        val l = 0.2126f * red(c) + 0.7152f * green(c) + 0.0722f * blue(c)
        val shadow = smooth(0.42f, 0f, l)
        fun map(ch: Float): Float {
          var m = ch + ch * shadow * 0.35f + shadow * 0.06f
          m = (m - 0.5f) * 1.18f + 0.5f
          return m
        }
        pack(map(red(c)), map(green(c)), map(blue(c)))
      }
      "vfx:color.pop" -> {
        val c = src[y * w + x]
        val r = red(c); val g = green(c); val b = blue(c)
        val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        val sat = (mx - mn) / max(mx, 0.001f)
        val keep = if (r > g && r > b && sat > 0.25f) 1f else 0f
        pack(mix(l, r, keep), mix(l, g, keep), mix(l, b, keep))
      }
      "vfx:sharpen.unsharp" -> {
        val c = src[y * w + x]
        val blur = average(src, x, y, w, h, 1, 1, 1)
        pack(
          red(c) + (red(c) - red(blur)) * 1.1f,
          green(c) + (green(c) - green(blur)) * 1.1f,
          blue(c) + (blue(c) - blue(blur)) * 1.1f
        )
      }
      "vfx:stylize.sketch" -> {
        val c = src[y * w + x]
        val l = 0.2126f * red(c) + 0.7152f * green(c) + 0.0722f * blue(c)
        val nbor = 0.2126f * red(sample(src, x + 1, y, w, h)) +
          0.7152f * green(sample(src, x, y + 1, w, h)) +
          0.0722f * blue(sample(src, x - 1, y, w, h))
        val edge = (1f - (kotlin.math.abs(l - nbor) * 6.5f).coerceIn(0f, 1f))
        val s = edge * edge
        pack(s, s, s)
      }
      "vfx:stylize.halftone" -> {
        val c = src[y * w + x]
        val l = 0.2126f * red(c) + 0.7152f * green(c) + 0.0722f * blue(c)
        val gx = ((u * 12f) % 1f) - 0.5f
        val gy = ((v * 12f) % 1f) - 0.5f
        val dot = if (hypot(gx, gy) < (1f - l) * 0.55f) 0.08f else l * 1.15f
        pack(dot, dot, mix(dot, 0.12f, 0.15f))
      }
      "vfx:stylize.posterize" -> {
        val c = src[y * w + x]
        fun q(ch: Float) = kotlin.math.round(ch * 5f) / 5f
        pack(q(red(c)), q(green(c)), q(blue(c)))
      }
      "vfx:stylize.pixelate" -> {
        val blocks = 8
        val bx = ((x * blocks) / w) * (w / blocks)
        val by = ((y * blocks) / h) * (h / blocks)
        sample(src, bx, by, w, h)
      }
      "vfx:stylize.duotone" -> {
        val c = src[y * w + x]
        val l = 0.2126f * red(c) + 0.7152f * green(c) + 0.0722f * blue(c)
        pack(mix(0.10f, 1f, l), mix(0.00f, 0.85f, l), mix(0.20f, 0.50f, l))
      }
      "cutout:portrait", "cutout:background" -> {
        val inside = ellipse(u, v, 0.38f, 0.42f, 0.16f, 0.28f)
        if (inside > 0.35f) src[y * w + x] else average(src, x, y, w, h, 3, 1, 1)
      }
      "face:reshape", "face:slim" -> displace(src, x, y, w, h) { uu, vv ->
        val dx = uu - 0.36f
        if (ellipse(uu, vv, 0.36f, 0.30f, 0.16f, 0.20f) > 0f) uu + dx * 0.22f else uu
      }
      "face:eyes" -> displace(src, x, y, w, h) { uu, vv ->
        val dx = uu - 0.36f; val dy = vv - 0.26f
        if (hypot(dx, dy) < 0.06f) 0.36f + dx * 0.7f else uu
      }
      "face:skin" -> {
        val inside = ellipse(u, v, 0.36f, 0.30f, 0.13f, 0.17f)
        if (inside > 0.2f) average(src, x, y, w, h, 2, 1, 1) else src[y * w + x]
      }
      "face:teeth" -> {
        val c = src[y * w + x]
        val mouth = ellipse(u, v, 0.36f, 0.37f, 0.09f, 0.05f)
        if (mouth > 0.15f) {
          pack(
            min(1f, red(c) + 0.12f * mouth),
            min(1f, green(c) + 0.10f * mouth),
            min(1f, blue(c) + 0.22f * mouth)
          )
        } else c
      }
      "body:waist" -> displace(src, x, y, w, h) { uu, vv ->
        if (vv in 0.55f..0.78f) uu - (uu - 0.38f) * 0.18f else uu
      }
      "body:reshape" -> {
        val pinched = if (v in 0.55f..0.78f) u - (u - 0.38f) * 0.18f else u
        val stretched = if (v > 0.72f) 0.72f + (v - 0.72f) * 0.85f else v
        sample(src, (pinched * (w - 1)).toInt(), (stretched * (h - 1)).toInt(), w, h)
      }
      "body:legs", "body:proportions" -> {
        val stretched = if (v > 0.72f) 0.72f + (v - 0.72f) * 0.72f else v
        sample(src, x, (stretched * (h - 1)).toInt(), w, h)
      }
      "body:shoulders" -> displace(src, x, y, w, h) { uu, vv ->
        if (vv in 0.48f..0.62f) uu + (uu - 0.38f) * 0.2f else uu
      }
      else -> src[y * w + x]
    }
  }

  private fun displace(src: IntArray, x: Int, y: Int, w: Int, h: Int, map: (Float, Float) -> Float): Int {
    val u = x / (w - 1f).coerceAtLeast(1f)
    val v = y / (h - 1f).coerceAtLeast(1f)
    val su = map(u, v).coerceIn(0f, 1f)
    return sample(src, (su * (w - 1)).toInt(), y, w, h)
  }

  private fun average(src: IntArray, x: Int, y: Int, w: Int, h: Int, radius: Int, stepX: Int, stepY: Int): Int {
    var r = 0f; var g = 0f; var b = 0f; var c = 0
    for (i in -radius..radius) {
      val p = sample(src, x + i * stepX, y + i * stepY, w, h)
      r += red(p); g += green(p); b += blue(p); c++
    }
    return pack(r / c, g / c, b / c)
  }

  private fun sample(src: IntArray, x: Int, y: Int, w: Int, h: Int): Int {
    val xx = x.coerceIn(0, w - 1)
    val yy = y.coerceIn(0, h - 1)
    return src[yy * w + xx]
  }

  private fun ellipse(u: Float, v: Float, cx: Float, cy: Float, rx: Float, ry: Float): Float {
    val d = ((u - cx) / rx) * ((u - cx) / rx) + ((v - cy) / ry) * ((v - cy) / ry)
    return if (d >= 1f) 0f else 1f - d
  }

  private fun rect(u: Float, v: Float, l: Float, t: Float, r: Float, b: Float) = u in l..r && v in t..b

  private fun hash(x: Float, y: Float): Float {
    val s = sin(x * 127.1f + y * 311.7f) * 43758.5453f
    return s - kotlin.math.floor(s)
  }

  private fun smooth(e0: Float, e1: Float, x: Float): Float {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
  }

  private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)
  private fun red(c: Int) = ((c shr 16) and 0xFF) / 255f
  private fun green(c: Int) = ((c shr 8) and 0xFF) / 255f
  private fun blue(c: Int) = (c and 0xFF) / 255f
  private fun pack(r: Float, g: Float, b: Float): Int {
    fun ch(v: Float) = (v.coerceIn(0f, 1f) * 255f).toInt()
    return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
  }
}
