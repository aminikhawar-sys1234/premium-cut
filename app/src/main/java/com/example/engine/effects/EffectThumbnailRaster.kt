package com.example.engine.effects

import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Icon-resolution processing of the same effect keys the GPU path uses.
 * This is the thumbnail source. It is not a substitute for preview or export.
 */
object EffectThumbnailRaster {

  fun argb(shaderKey: String?, size: Int = 48): IntArray {
    val n = size.coerceIn(8, 96)
    val src = IntArray(n * n)
    for (y in 0 until n) {
      for (x in 0 until n) src[y * n + x] = basePixel(x, y, n)
    }
    if (shaderKey.isNullOrBlank()) return src
    val out = IntArray(n * n)
    for (y in 0 until n) {
      for (x in 0 until n) out[y * n + x] = shade(shaderKey, src, x, y, n)
    }
    return out
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

  private fun shade(key: String, src: IntArray, x: Int, y: Int, n: Int): Int {
    val u = x / (n - 1f)
    val v = y / (n - 1f)
    return when (key) {
      "vfx:blur.directional" -> average(src, x, y, n, 5, 1, 0)
      "vfx:distort.glitch" -> {
        val band = (v * 14f).toInt()
        val shift = if (band % 3 == 0) (hash(band.toFloat(), 2f) - 0.5f) * n * 0.18f else 0f
        val sx = (x + shift).toInt()
        val c = sample(src, sx, y, n)
        val cr = sample(src, sx + 2, y, n)
        val cb = sample(src, sx - 2, y, n)
        pack(red(cr), green(c), blue(cb))
      }
      "vfx:chromatic.aberration" -> {
        val dx = ((u - 0.5f) * 5f).toInt()
        pack(red(sample(src, x + dx, y, n)), green(sample(src, x, y, n)), blue(sample(src, x - dx, y, n)))
      }
      "vfx:noise.filmGrain" -> {
        val c = src[y * n + x]
        val g = (hash(x.toFloat(), y.toFloat()) - 0.5f) * 0.45f
        pack(red(c) + g, green(c) + g, blue(c) + g)
      }
      "vfx:light.leak" -> {
        val c = src[y * n + x]
        val blob = exp(-hypot(u - 0.18f, v - 0.78f) * 3.2f)
        val leakR = (1f * (blob * 1.15f)).coerceIn(0f, 1f)
        val leakG = (0.45f * (blob * 1.15f)).coerceIn(0f, 1f)
        val leakB = (0.12f * (blob * 1.15f)).coerceIn(0f, 1f)
        pack(
          1f - (1f - red(c)) * (1f - leakR),
          1f - (1f - green(c)) * (1f - leakG),
          1f - (1f - blue(c)) * (1f - leakB)
        )
      }
      "vfx:color.hdr" -> {
        val c = src[y * n + x]
        val l = 0.2126f * red(c) + 0.7152f * green(c) + 0.0722f * blue(c)
        val shadow = smooth(0.42f, 0f, l)
        val contrast = 1.18f
        fun map(ch: Float): Float {
          var m = ch + ch * shadow * 0.35f + shadow * 0.06f
          m = (m - 0.5f) * contrast + 0.5f
          return m
        }
        pack(map(red(c)), map(green(c)), map(blue(c)))
      }
      "vfx:color.pop" -> {
        val c = src[y * n + x]
        val r = red(c); val g = green(c); val b = blue(c)
        val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        val sat = (mx - mn) / max(mx, 0.001f)
        val keep = if (r > g && r > b && sat > 0.25f) 1f else 0f
        pack(mix(l, r, keep), mix(l, g, keep), mix(l, b, keep))
      }
      "vfx:sharpen.unsharp" -> {
        val c = src[y * n + x]
        val blur = average(src, x, y, n, 1, 1, 1)
        val amount = 1.1f
        pack(
          red(c) + (red(c) - red(blur)) * amount,
          green(c) + (green(c) - green(blur)) * amount,
          blue(c) + (blue(c) - blue(blur)) * amount
        )
      }
      "cutout:portrait", "cutout:background" -> {
        val inside = ellipse(u, v, 0.38f, 0.42f, 0.16f, 0.28f)
        if (inside > 0.35f) src[y * n + x] else average(src, x, y, n, 3, 1, 1)
      }
      "face:reshape", "face:slim" -> displace(src, x, y, n) { uu, vv ->
        val dx = uu - 0.36f
        if (ellipse(uu, vv, 0.36f, 0.30f, 0.16f, 0.20f) > 0f) uu + dx * 0.22f else uu
      }
      "face:eyes" -> displace(src, x, y, n) { uu, vv ->
        val cx = 0.36f; val cy = 0.26f
        val dx = uu - cx; val dy = vv - cy
        if (hypot(dx, dy) < 0.06f) cx + dx * 0.7f else uu
      }
      "face:skin" -> {
        val inside = ellipse(u, v, 0.36f, 0.30f, 0.13f, 0.17f)
        if (inside > 0.2f) average(src, x, y, n, 2, 1, 1) else src[y * n + x]
      }
      "face:teeth" -> {
        val c = src[y * n + x]
        val mouth = ellipse(u, v, 0.36f, 0.37f, 0.09f, 0.05f)
        if (mouth > 0.15f) {
          pack(
            min(1f, red(c) + 0.12f * mouth),
            min(1f, green(c) + 0.10f * mouth),
            min(1f, blue(c) + 0.22f * mouth)
          )
        } else c
      }
      "body:waist" -> displace(src, x, y, n) { uu, vv ->
        if (vv in 0.55f..0.78f) uu - (uu - 0.38f) * 0.18f else uu
      }
      "body:reshape" -> {
        val pinched = if (v in 0.55f..0.78f) u - (u - 0.38f) * 0.18f else u
        val stretched = if (v > 0.72f) 0.72f + (v - 0.72f) * 0.85f else v
        sample(src, (pinched * (n - 1)).toInt(), (stretched * (n - 1)).toInt(), n)
      }
      "body:legs", "body:proportions" -> {
        val stretched = if (v > 0.72f) 0.72f + (v - 0.72f) * 0.72f else v
        sample(src, x, (stretched * (n - 1)).toInt(), n)
      }
      "body:shoulders" -> displace(src, x, y, n) { uu, vv ->
        if (vv in 0.48f..0.62f) {
          val dx = uu - 0.38f
          uu + dx * 0.2f
        } else uu
      }
      else -> src[y * n + x]
    }
  }

  private fun displace(src: IntArray, x: Int, y: Int, n: Int, map: (Float, Float) -> Float): Int {
    val u = x / (n - 1f)
    val v = y / (n - 1f)
    val su = map(u, v).coerceIn(0f, 1f)
    return sample(src, (su * (n - 1)).toInt(), y, n)
  }

  private fun average(src: IntArray, x: Int, y: Int, n: Int, radius: Int, stepX: Int, stepY: Int): Int {
    var r = 0f; var g = 0f; var b = 0f; var c = 0
    for (i in -radius..radius) {
      val p = sample(src, x + i * stepX, y + i * stepY, n)
      r += red(p); g += green(p); b += blue(p); c++
    }
    return pack(r / c, g / c, b / c)
  }

  private fun sample(src: IntArray, x: Int, y: Int, n: Int): Int {
    val xx = x.coerceIn(0, n - 1)
    val yy = y.coerceIn(0, n - 1)
    return src[yy * n + xx]
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
