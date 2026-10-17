package com.example.engine.effects

import android.graphics.Bitmap
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Icon-resolution processing of the same effect keys the GPU path uses.
 * Live tiles pass the current clip frame; the fallback scene is only used when
 * no clip thumbnail is available yet.
 *
 * Every branch is a pure function of (x, y, timeSec): panel swatches animate through
 * a seamless [LIVE_LOOP_SECONDS] loop (fire licks, globe spins, scan sweeps, warps
 * breathe), so a tile shows the effect's real motion — never one frozen frame.
 *
 * The `subject.*` icons gate their look on a synthetic person silhouette (the same
 * face/torso/legs shapes the fallback scene draws), so a body/AI icon communicates
 * "this styles the tracked person", not the whole frame.
 */
object EffectThumbnailRaster {

  /** Loop period of the animated swatches: every animated branch is periodic in this window. */
  const val LIVE_LOOP_SECONDS = 2.4f
  private const val TWO_PI = 6.2831853f

  fun argb(shaderKey: String?, size: Int = 48, timeSec: Float = 0f): IntArray {
    val n = size.coerceIn(8, 96)
    return apply(shaderKey, baseScene(n), n, n, timeSec)
  }

  fun apply(shaderKey: String?, src: IntArray, width: Int, height: Int, timeSec: Float = 0f): IntArray {
    val w = width.coerceAtLeast(1)
    val h = height.coerceAtLeast(1)
    if (src.size < w * h) return src
    val out = IntArray(w * h)
    return renderInto(shaderKey, src, out, w, h, timeSec)
  }

  /**
   * Animated-tile entry point: renders one frame into the caller's reusable buffer so a
   * grid of live swatches never allocates per frame. Returns [out] (or [src] on a size
   * mismatch, mirroring [apply]).
   */
  fun renderInto(shaderKey: String?, src: IntArray, out: IntArray, width: Int, height: Int, timeSec: Float): IntArray {
    val w = width.coerceAtLeast(1)
    val h = height.coerceAtLeast(1)
    if (src.size < w * h || out.size < w * h) return src
    if (shaderKey.isNullOrBlank()) {
      src.copyInto(out, 0, 0, w * h)
      return out
    }
    for (y in 0 until h) {
      for (x in 0 until w) out[y * w + x] = shade(shaderKey, src, x, y, w, h, timeSec)
    }
    return out
  }

  /** The synthetic fallback scene with no effect applied (person silhouette + accents). */
  fun baseScene(size: Int = 64): IntArray {
    val n = size.coerceIn(8, 96)
    val src = IntArray(n * n)
    for (y in 0 until n) {
      for (x in 0 until n) src[y * n + x] = basePixel(x, y, n)
    }
    return src
  }

  fun applyToBitmap(src: Bitmap, shaderKey: String?, timeSec: Float = 0f): Bitmap {
    if (src.isRecycled || src.width <= 0 || src.height <= 0) return src
    val w = src.width
    val h = src.height
    val pixels = IntArray(w * h)
    src.getPixels(pixels, 0, w, 0, 0, w, h)
    val out = apply(shaderKey, pixels, w, h, timeSec)
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

  /** Soft synthetic silhouette of the fallback scene's person (face + torso + legs). */
  private fun personMask(u: Float, v: Float): Float {
    val face = ellipse(u, v, 0.36f, 0.30f, 0.15f, 0.19f)
    val torsoW = if (u in 0.25f..0.50f && v in 0.48f..0.84f) 0.85f else 0f
    val legs = if (u in 0.28f..0.47f && v in 0.82f..0.98f) 0.8f else 0f
    return max(face, max(torsoW, legs))
  }

  private fun shade(key: String, src: IntArray, x: Int, y: Int, w: Int, h: Int, timeSec: Float): Int {
    val u = x / (w - 1f).coerceAtLeast(1f)
    val v = y / (h - 1f).coerceAtLeast(1f)
    // Normalised loop phase in [0,1): every animated branch below is a pure function of it,
    // so the swatch loop wraps without a pop and any time offset replays deterministically.
    val ph = fract(timeSec / LIVE_LOOP_SECONDS)
    return when (key) {
      "vfx:blur.motion" -> average(src, x, y, w, h, 3 + (3f * wave(ph)).toInt(), 1, 0)

      // ---- Video Effects: CapCut-trending assets. ----
      "vfx:trend.explosion" -> {
        val c = src[y * w + x]
        val d = hypot(u - 0.5f, v - 0.55f)
        val ringR = 0.14f + 0.24f * tri(ph)
        val ring = exp(-((d - ringR) * 7f) * ((d - ringR) * 7f))
        val core = exp(-d * d * 22f) * (0.75f + 0.5f * hash3(3.1f, 7.7f, (ph * 12f).toInt().toFloat()))
        val heat = (ring * 0.85f + core * 1.2f).coerceIn(0f, 1.4f)
        pack(
          red(c) + heat * 1.0f,
          green(c) + heat * 0.62f,
          blue(c) + heat * 0.22f
        )
      }
      "vfx:trend.ink_spill" -> {
        val c = src[y * w + x]
        val d = hypot((u - 0.5f) * 1.2f, v - 0.5f)
        val n = hash(u * 9f, v * 9f)
        val edge = 0.20f + 0.26f * tri(ph) + 0.20f * n
        val ink = (1f - smooth(edge - 0.10f, edge, d)).coerceIn(0f, 1f)
        pack(mix(red(c), 0.03f, ink), mix(green(c), 0.03f, ink), mix(blue(c), 0.07f, ink))
      }
      "vfx:trend.earth" -> {
        val c = src[y * w + x]
        val cx = 0.72f; val cy = 0.34f; val rr = 0.20f
        val d = hypot((u - cx) * 1.0f, v - cy)
        if (d > rr) c else {
          // Longitude scrolls with the loop phase and wraps mod 24 cells -> seamless spin.
          val lon = u + ph
          val land = hash(((lon * 24f).toInt().mod(24)).toFloat(), (v * 24f).toInt().toFloat())
          val sphere = 1f - (d / rr) * (d / rr)
          val shade = (0.35f + 0.75f * sphere).coerceIn(0f, 1f)
          val rim = exp(-((rr - d) * 18f)) * 0.5f * (0.8f + 0.4f * wave(ph))
          val base = if (land > 0.62f) floatArrayOf(0.16f, 0.42f, 0.14f) else floatArrayOf(0.05f, 0.20f, 0.45f)
          pack(
            base[0] * shade + rim * 0.35f,
            base[1] * shade + rim * 0.55f,
            base[2] * shade + rim * 0.9f
          )
        }
      }
      "vfx:trend.magical_tome" -> {
        val c = src[y * w + x]
        val d = hypot(u - 0.5f, v - 0.45f)
        var glow = 0f
        for (i in 0..2) {
          val ringR2 = 0.08f + 0.22f * fract(ph + i / 3f)
          val ring = exp(-((d - ringR2) * 16f) * ((d - ringR2) * 16f))
          val ticks = if (hash((d * 40f).toInt().toFloat(), i.toFloat() + (ph * 8f).toInt().toFloat()) > 0.35f) 1f else 0.3f
          glow += ring * ticks
        }
        val ang = TWO_PI * ph
        val sparkle =
          exp(-hypot(u - (0.68f + 0.05f * cos(ang)), v - (0.28f + 0.05f * sin(ang))) * 16f) +
            exp(-hypot(u - (0.32f - 0.05f * cos(ang)), v - (0.62f - 0.05f * sin(ang))) * 16f)
        val g2 = (glow + sparkle * (0.5f + 0.6f * wave(ph))).coerceIn(0f, 1.3f)
        pack(red(c) + g2 * 0.75f, green(c) + g2 * 0.45f, blue(c) + g2 * 1.0f)
      }
      "vfx:trend.cut_shift" -> {
        val band = (v * 10f).toInt()
        val roll = (ph * 8f).toInt().toFloat()
        val gate = hash(band.toFloat() + roll * 1.7f, 7f)
        val shift = if (gate > 0.55f) (hash(band.toFloat() + roll * 2.3f, 3f) - 0.5f) * w * 0.30f else 0f
        val sx = (x + shift).toInt()
        val c = sample(src, sx, y, w, h)
        val cr = sample(src, sx + 2, y, w, h)
        val cb = sample(src, sx - 2, y, w, h)
        pack(red(cr), green(c), blue(cb))
      }
      "vfx:trend.fire" -> {
        val c = src[y * w + x]
        val band = smooth(0.35f, 0.95f, 1f - v)
        val rise = fract(v + ph)
        val n = hash(u * 14f, rise * 9f + u * 5f) * 0.6f + hash(u * 31f, rise * 21f) * 0.4f
        val flicker = 0.85f + 0.3f * hash3(u * 7f, 1.3f, (ph * 14f).toInt().toFloat())
        val flame = band * smooth(0.30f, 0.85f, n + band * 0.35f) * flicker
        pack(
          red(c) + flame * 1.05f,
          green(c) + flame * 0.45f,
          blue(c) + flame * 0.05f
        )
      }
      "vfx:trend.mini_zoom" -> {
        val zoom = 0.30f + 0.12f * sin(TWO_PI * ph)
        val toward = sample(src, (mix(u, 0.5f, zoom) * (w - 1)).toInt(), (mix(v, 0.5f, zoom) * (h - 1)).toInt(), w, h)
        val c = src[y * w + x]
        val d = hypot(u - 0.5f, v - 0.5f)
        val vig = 1f - 0.30f * smooth(0.35f, 0.85f, d)
        pack(mix(red(c), red(toward), 0.45f) * vig, mix(green(c), green(toward), 0.45f) * vig, mix(blue(c), blue(toward), 0.45f) * vig)
      }
      "vfx:trend.white_flash" -> {
        val c = src[y * w + x]
        val center = 1f - smooth(0.0f, 0.75f, hypot(u - 0.5f, v - 0.5f))
        val flash = ((0.45f + 0.55f * center) * (0.30f + 0.70f * tri(ph))).coerceIn(0f, 1f)
        pack(mix(red(c), 1f, flash), mix(green(c), 1f, flash), mix(blue(c), 1f, flash))
      }

      // ---- Photo Effects. ----
      "vfx:trend.expand_x" -> {
        val e = 0.16f - 0.07f * sin(TWO_PI * ph)
        val su = when {
          u < e -> (e - u) / max(1f - 2f * e, 0.05f)
          u > 1f - e -> 1f - (u - (1f - e)) / max(1f - 2f * e, 0.05f) + (1f - e) / max(1f - 2f * e, 0.05f)
          else -> (u - e) / max(1f - 2f * e, 0.05f)
        }
        val s = sample(src, (su.coerceIn(0f, 1f) * (w - 1)).toInt(), y, w, h)
        val dEdge = max(e - u, u - (1f - e)) / max(e, 1e-4f)
        val k = if (dEdge > 0f) (1f - 0.20f * dEdge.coerceIn(0f, 1f)) else 1f
        pack(red(s) * k, green(s) * k, blue(s) * k)
      }
      "vfx:trend.orbit360" -> {
        val k = 1.8f
        val xx = (atan((u - 0.5f) * k) / atan(k)) * 0.5f + 0.5f
        val pan = (14f * sin(TWO_PI * ph)).toInt()
        sample(src, (xx.coerceIn(0f, 1f) * (w - 1)).toInt() + pan, y, w, h)
      }
      "vfx:trend.layer_echo" -> {
        val c = src[y * w + x]
        val drift = sin(TWO_PI * ph)
        val l1 = sample(src, (mix(u, 0.5f, 0.85f) * (w - 1)).toInt() - 3 - (3f * drift).toInt(), (y + 2).coerceIn(0, h - 1), w, h)
        val l2 = sample(src, (mix(u, 0.5f, 0.72f) * (w - 1)).toInt() + 3 + (3f * drift).toInt(), (y - 2).coerceIn(0, h - 1), w, h)
        fun screen(a: Float, b: Float) = 1f - (1f - a) * (1f - b)
        pack(
          screen(screen(red(c), red(l1) * 0.5f), red(l2) * 0.3f),
          screen(screen(green(c), green(l1) * 0.5f), green(l2) * 0.3f),
          screen(screen(blue(c), blue(l1) * 0.5f), blue(l2) * 0.3f)
        )
      }
      "vfx:trend.zoom3d" -> {
        val z = 0.78f - 0.10f * sin(TWO_PI * ph)
        val toward = sample(src, (mix(u, 0.5f, z) * (w - 1)).toInt(), (mix(v, 0.5f, z) * (h - 1)).toInt(), w, h)
        val parallax = (hash(x.toFloat(), y.toFloat()) - 0.5f) * 1.6f
        sample(src, (mix(u, 0.5f, z) * (w - 1)).toInt() + parallax.toInt(), (mix(v, 0.5f, z) * (h - 1)).toInt(), w, h).let {
          pack(mix(red(it), red(toward), 0.3f), mix(green(it), green(toward), 0.3f), mix(blue(it), blue(toward), 0.3f))
        }
      }
      "vfx:trend.ai_filter" -> {
        val c = src[y * w + x]
        val blur = average(src, x, y, w, h, 1, 1, 1)
        val l = 0.2126f * red(c) + 0.7152f * green(c) + 0.0722f * blue(c)
        val toned = if (l < 0.4f) {
          pack(red(c) * 0.82f, green(c) * 0.95f, blue(c) * 1.14f)
        } else {
          pack(min(1f, red(c) * 1.08f), min(1f, green(c) * 1.02f), min(1f, blue(c) * 0.92f))
        }
        val blurMix = 0.25f + 0.15f * wave(ph)
        pack(
          mix(red(toned), red(blur), blurMix),
          mix(green(toned), green(blur), blurMix),
          mix(blue(toned), blue(blur), blurMix)
        )
      }
      "vfx:trend.sad" -> {
        val c = src[y * w + x]
        val l = 0.2126f * red(c) + 0.7152f * green(c) + 0.0722f * blue(c)
        var r = mix(l, red(c), 0.45f) * 0.86f
        var g = mix(l, green(c), 0.45f) * 0.94f
        var b = mix(l, blue(c), 0.45f) * 1.12f
        val d = hypot(u - 0.5f, v - 0.5f)
        val vig = 1f - 0.35f * smooth(0.45f, 0.95f, d)
        r *= vig; g *= vig; b *= vig
        val n = (hash(x * 1.7f, y * 2.3f + (ph * 12f).toInt().toFloat() * 29f) - 0.5f) * 0.10f
        pack(r + n, g + n, b + n)
      }
      "vfx:trend.cartoon3d" -> {
        val c = src[y * w + x]
        fun q(ch: Float) = kotlin.math.round(ch * 5f) / 5f
        val l1 = 0.2126f * red(sample(src, x + 1, y, w, h)) - 0.2126f * red(sample(src, x - 1, y, w, h))
        val edge = (kotlin.math.abs(l1) * (4.5f + 1.5f * wave(ph))).coerceIn(0f, 1f)
        pack(
          mix(q(red(c)) * 1.06f, 0.06f, edge),
          mix(q(green(c)) * 1.06f, 0.06f, edge),
          mix(q(blue(c)) * 1.06f, 0.06f, edge)
        )
      }

      // ---- Body Effects + AI Effects: styles gated on the synthetic person silhouette. ----
      "vfx:subject.optical_scan" -> {
        val c = src[y * w + x]
        val m = personMask(u, v)
        if (m <= 0.1f) c else {
          val bandV = 0.42f + 0.38f * sin(TWO_PI * ph)
          val band = exp(-((v - bandV) * 6f) * ((v - bandV) * 6f))
          val grid = if (v % 0.08f < 0.03f || u % 0.06f < 0.02f) 0.35f else 0f
          val glow = (band * (0.4f + grid) * (0.75f + 0.25f * wave(ph))).coerceIn(0f, 1f) * m
          pack(red(c) + glow * 0.20f, green(c) + glow * 0.90f, blue(c) + glow)
        }
      }
      "vfx:subject.phantom_clone" -> {
        val c = src[y * w + x]
        val ghostU = u + 0.14f + 0.05f * sin(TWO_PI * ph)
        val gm = personMask(ghostU, v)
        if (gm <= 0.1f) c else {
          val gs = sample(src, (ghostU * (w - 1)).toInt(), y, w, h)
          val gr = mix(0.55f, red(gs), 0.55f)
          val gg = mix(0.75f, green(gs), 0.55f)
          val gb = mix(1.0f, blue(gs), 0.55f)
          val a = gm * 0.75f * (0.5f + 0.5f * hash(u * 20f, v * 3f + (ph * 10f).toInt().toFloat() * 13f))
          pack(mix(red(c), gr, a), mix(green(c), gg, a), mix(blue(c), gb, a))
        }
      }
      "vfx:subject.light_trails" -> {
        val c = src[y * w + x]
        val m = personMask(u, v)
        val inner = personMask(u + 0.035f, v) + personMask(u - 0.035f, v) + personMask(u, v + 0.035f) + personMask(u, v - 0.035f)
        val edge = (m * 4f - inner).coerceIn(0f, 1f)
        val streak = smooth(0.45f, 0.9f, hash(u * 6f, v * 3f + (ph * 8f).toInt().toFloat() * 13f))
        val g2 = (edge * (0.9f + streak) * (0.8f + 0.2f * sin(TWO_PI * ph)) + m * streak * 0.35f).coerceIn(0f, 1f)
        pack(red(c) + g2 * 0.30f, green(c) + g2 * 0.75f, blue(c) + g2)
      }
      "vfx:subject.pixel_mask" -> {
        val m = personMask(u, v)
        if (m <= 0.3f) src[y * w + x] else {
          val blocks = 10
          val bw = max(1, w / blocks)
          val bh = max(1, h / blocks)
          val step = (ph * 8f).toInt()
          val bx = ((x * blocks) / w) * bw + (step * 3) % bw
          val by = ((y * blocks) / h) * bh + (step * 5) % bh
          sample(src, bx, by, w, h)
        }
      }
      "vfx:subject.action_paint" -> {
        val c = src[y * w + x]
        val m = personMask(u, v)
        if (m <= 0.2f) c else {
          val roll = (ph * 6f).toInt().toFloat()
          val n = hash((u * 8f).toInt().toFloat() + roll * 7f, (v * 8f).toInt().toFloat())
          val splat = smooth(0.35f, 0.6f, n)
          val hue = fract(hash((u * 5f).toInt().toFloat() + 3f, (v * 5f).toInt().toFloat()) + ph)
          val paint = hsv(hue, 0.85f, 1f)
          val a = m * splat * 0.85f
          pack(mix(red(c), max(red(c), paint[0]), a), mix(green(c), max(green(c), paint[1]), a), mix(blue(c), max(blue(c), paint[2]), a))
        }
      }
      "vfx:subject.face_scan" -> {
        val c = src[y * w + x]
        val m = max(personMask(u, v) * smooth(0.62f, 0.30f, v), 0f)
        if (m <= 0.1f) c else {
          val bandV = 0.30f + 0.14f * sin(TWO_PI * ph)
          val band = exp(-((v - bandV) * 9f) * ((v - bandV) * 9f))
          val ticks = if (u % 0.05f < 0.018f) 0.5f else 0f
          val glow = (band * (0.45f + ticks) * (0.75f + 0.25f * wave(ph))).coerceIn(0f, 1f) * m
          pack(red(c) + glow * 0.15f, green(c) + glow, blue(c) + glow * 0.55f)
        }
      }
      "vfx:subject.light_dissolve" -> {
        val c = src[y * w + x]
        val m = personMask(u, v)
        if (m <= 0.1f) c else {
          val gone = smooth(0.15f, 0.75f, v) * 0.65f * (0.25f + 0.75f * tri(ph))
          val px = if (hash((x * 3).toFloat(), (y * 3).toFloat() + (ph * 10f).toInt().toFloat() * 17f) > 0.86f) 1f else 0f
          pack(
            mix(red(c), 0.02f, gone * m) + px * gone * m,
            mix(green(c), 0.02f, gone * m) + px * gone * m * 0.85f,
            mix(blue(c), 0.03f, gone * m) + px * gone * m * 0.5f
          )
        }
      }
      "vfx:subject.luminescence" -> {
        val c = src[y * w + x]
        val m = personMask(u, v)
        val inner = personMask(u + 0.03f, v) + personMask(u - 0.03f, v) + personMask(u, v + 0.03f) + personMask(u, v - 0.03f)
        val edge = (m * 4f - inner).coerceIn(0f, 1f)
        val glow = (edge * (1.1f + 0.4f * sin(TWO_PI * ph)) + m * (0.25f + 0.08f * sin(TWO_PI * ph + 1f))).coerceIn(0f, 1f)
        pack(red(c) + glow * 0.45f, green(c) + glow * 0.85f, blue(c) + glow)
      }
      "vfx:subject.hand_drawn" -> {
        val c = src[y * w + x]
        val m = personMask(u, v)
        if (m <= 0.2f) c else {
          val l = 0.2126f * red(c) + 0.7152f * green(c) + 0.0722f * blue(c)
          val hatch = sin((u + v) * 60f + l * 12f + (ph * 8f).toInt().toFloat() * 2.399f)
          val shadeD = smooth(0.0f, 0.5f, 0.55f - l) * (0.5f + 0.5f * hatch)
          val s = (0.98f - shadeD * 0.45f).coerceIn(0f, 1f)
          pack(mix(red(c), s, m), mix(green(c), s, m), mix(blue(c), s, m))
        }
      }
      "vfx:subject.clone_shadow" -> {
        val c = src[y * w + x]
        val m = personMask(u, v)
        if (m <= 0.1f) c else {
          val inner = personMask(u + 0.03f, v) + personMask(u - 0.03f, v) + personMask(u, v + 0.03f) + personMask(u, v - 0.03f)
          val rim = (m * 4f - inner).coerceIn(0f, 1f)
          val rimP = rim * (0.85f + 0.15f * sin(TWO_PI * ph))
          val l = 0.2126f * red(c) + 0.7152f * green(c) + 0.0722f * blue(c)
          val sr = 0.03f + 0.10f * l
          val sg = 0.035f + 0.12f * l
          val sb = 0.05f + 0.18f * l
          pack(mix(red(c), sr + rimP * 0.35f, m), mix(green(c), sg + rimP * 0.55f, m), mix(blue(c), sb + rimP, m))
        }
      }
      "vfx:subject.clone_golden" -> {
        val c = src[y * w + x]
        val m = personMask(u, v)
        if (m <= 0.1f) c else {
          val l = 0.2126f * red(c) + 0.7152f * green(c) + 0.0722f * blue(c)
          val spec = exp(-((l - 0.8f) * 4f) * ((l - 0.8f) * 4f)) * (0.7f + 0.3f * sin(TWO_PI * ph + 1f))
          val gr = mix(0.28f, 1.0f, l) + spec * 0.6f
          val gg = mix(0.16f, 0.78f, l) + spec * 0.5f
          val gb = mix(0.03f, 0.30f, l) + spec * 0.25f
          pack(mix(red(c), gr, m), mix(green(c), gg, m), mix(blue(c), gb, m))
        }
      }
      "vfx:subject.clone_cyber" -> {
        val c = src[y * w + x]
        val m = personMask(u, v)
        if (m <= 0.1f) c else {
          val l = 0.2126f * red(c) + 0.7152f * green(c) + 0.0722f * blue(c)
          val grid = if (u % 0.05f < 0.008f || v % 0.05f < 0.008f) 1f else 0f
          val pulse = smooth(0.5f, 1f, hash((u * 10f).toInt().toFloat() + (ph * 8f).toInt().toFloat() * 7f, (v * 10f).toInt().toFloat()))
          val circuit = grid * pulse
          val sr = 0.05f + 0.04f * l
          val sg = 0.06f + 0.05f * l
          val sb = 0.09f + 0.07f * l
          pack(mix(red(c), sr, m) + circuit * 0.10f * m, mix(green(c), sg, m) + circuit * 0.95f * m, mix(blue(c), sb, m) + circuit * m)
        }
      }
      "vfx:subject.clone_cartoon" -> {
        val c = src[y * w + x]
        val m = personMask(u, v)
        if (m <= 0.2f) c else {
          fun q(ch: Float) = kotlin.math.round(ch * 6f) / 6f
          val l1 = 0.2126f * red(sample(src, x + 1, y, w, h)) - 0.2126f * red(sample(src, x - 1, y, w, h))
          val edge = (kotlin.math.abs(l1) * (4.5f + 1.5f * wave(ph))).coerceIn(0f, 1f)
          pack(
            mix(red(c), mix(q(red(c)) * 1.08f, 0.05f, edge), m),
            mix(green(c), mix(q(green(c)) * 1.08f, 0.05f, edge), m),
            mix(blue(c), mix(q(blue(c)) * 1.08f, 0.05f, edge), m)
          )
        }
      }

      // ---- Face / photo warp assets (icon-level equivalents of the mesh warp ops). ----
      "face:big_head" -> warpIcon2D(src, x, y, w, h) { uu, vv ->
        val dx = uu - 0.36f; val dy = vv - 0.34f
        val s = 1.35f - 0.15f * sin(TWO_PI * ph)
        if (hypot(dx, dy) < 0.30f) floatArrayOf(0.36f + dx / s, 0.34f + dy / s) else floatArrayOf(uu, vv)
      }
      "face:small" -> warpIcon2D(src, x, y, w, h) { uu, vv ->
        val dx = uu - 0.36f; val dy = vv - 0.34f
        val s = 1.35f + 0.25f * sin(TWO_PI * ph)
        if (hypot(dx, dy) < 0.30f) floatArrayOf(0.36f + dx * s, 0.34f + dy * s) else floatArrayOf(uu, vv)
      }
      "face:alien" -> warpIcon2D(src, x, y, w, h) { uu, vv ->
        val dl = hypot(uu - 0.31f, vv - 0.26f)
        val dr = hypot(uu - 0.41f, vv - 0.26f)
        val es = 0.45f + 0.15f * sin(TWO_PI * ph)
        when {
          dl < 0.09f -> floatArrayOf(0.31f + (uu - 0.31f) * es, 0.26f + (vv - 0.26f) * es)
          dr < 0.09f -> floatArrayOf(0.41f + (uu - 0.41f) * es, 0.26f + (vv - 0.26f) * es)
          else -> floatArrayOf(uu, vv)
        }
      }
      "face:funny" -> warpIcon2D(src, x, y, w, h) { uu, vv ->
        val df = hypot(uu - 0.36f, vv - 0.14f)
        val dn = hypot(uu - 0.36f, vv - 0.52f)
        val fs = 0.55f + 0.12f * sin(TWO_PI * ph)
        val ns = 1.5f - 0.30f * sin(TWO_PI * ph)
        when {
          df < 0.16f -> floatArrayOf(0.36f + (uu - 0.36f) * fs, 0.14f + (vv - 0.14f) * fs)
          dn < 0.14f -> floatArrayOf(0.36f + (uu - 0.36f) * ns, 0.52f + (vv - 0.52f) * ns)
          else -> floatArrayOf(uu, vv)
        }
      }
      "face:melt" -> warpIcon2D(src, x, y, w, h) { uu, vv ->
        val inFace = ellipse(uu, vv, 0.36f, 0.30f, 0.15f, 0.19f)
        if (inFace > 0f && vv > 0.24f) floatArrayOf(uu, vv + 0.10f * inFace * (0.6f + 0.4f * tri(ph))) else floatArrayOf(uu, vv)
      }
      "face:chubby" -> warpIcon2D(src, x, y, w, h) { uu, vv ->
        val inFace = ellipse(uu, vv, 0.36f, 0.34f, 0.17f, 0.20f)
        if (inFace > 0f) floatArrayOf(0.36f + (uu - 0.36f) * (1f - (0.18f + 0.06f * sin(TWO_PI * ph)) * inFace), vv) else floatArrayOf(uu, vv)
      }
      "face:wink" -> warpIcon2D(src, x, y, w, h) { uu, vv ->
        val dl = hypot(uu - 0.31f, vv - 0.26f)
        val s = 1.8f - 1.2f * tri(fract(ph + 0.25f))
        if (dl < 0.06f) floatArrayOf(0.31f + (uu - 0.31f) * s, 0.26f + (vv - 0.26f) * s) else floatArrayOf(uu, vv)
      }
      "face:curls" -> warpIcon2D(src, x, y, w, h) { uu, vv ->
        val cx = 0.30f; val cy = 0.12f
        val dx = uu - cx; val dy = vv - cy
        val d = hypot(dx, dy)
        if (d < 0.20f) {
          val ang = (1f - d / 0.20f) * 1.8f + TWO_PI * ph
          val ca = cos(ang); val sa = sin(ang)
          floatArrayOf(cx + dx * ca - dy * sa, cy + dx * sa + dy * ca)
        } else floatArrayOf(uu, vv)
      }

      else -> src[y * w + x]
    }
  }

  /** Displacement icon helper mapping (u, v) -> (u, v). */
  private fun warpIcon2D(src: IntArray, x: Int, y: Int, w: Int, h: Int, map: (Float, Float) -> FloatArray): Int {
    val u = x / (w - 1f).coerceAtLeast(1f)
    val v = y / (h - 1f).coerceAtLeast(1f)
    val m = map(u, v)
    return sample(src, (m[0].coerceIn(0f, 1f) * (w - 1)).toInt(), (m[1].coerceIn(0f, 1f) * (h - 1)).toInt(), w, h)
  }

  private fun hsv(h: Float, s: Float, vv: Float): FloatArray {
    val i = (h * 6f).toInt() % 6
    val f = h * 6f - (h * 6f).toInt()
    val p = vv * (1f - s)
    val q = vv * (1f - f * s)
    val t = vv * (1f - (1f - f) * s)
    return when (i) {
      0 -> floatArrayOf(vv, t, p)
      1 -> floatArrayOf(q, vv, p)
      2 -> floatArrayOf(p, vv, t)
      3 -> floatArrayOf(p, q, vv)
      4 -> floatArrayOf(t, p, vv)
      else -> floatArrayOf(vv, p, q)
    }
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

  /** Periodic helper: x wrapped into [0,1). */
  private fun fract(x: Float): Float = x - kotlin.math.floor(x)

  /** Cosine pulse 0 -> 1 -> 0 over one loop phase; wave(0) == 0 keeps the loop seam exact. */
  private fun wave(ph: Float): Float = 0.5f - 0.5f * cos(TWO_PI * ph)

  /** Triangle sweep 0 -> 1 -> 0 over one loop phase. */
  private fun tri(ph: Float): Float = 1f - kotlin.math.abs(1f - 2f * ph)

  /** Time-seeded hash for effects that re-roll their noise pattern a few times per loop. */
  private fun hash3(x: Float, y: Float, seed: Float): Float = hash(x, y + seed * 57.31f)

  private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)
  private fun red(c: Int) = ((c shr 16) and 0xFF) / 255f
  private fun green(c: Int) = ((c shr 8) and 0xFF) / 255f
  private fun blue(c: Int) = (c and 0xFF) / 255f
  private fun pack(r: Float, g: Float, b: Float): Int {
    fun ch(v: Float) = (v.coerceIn(0f, 1f) * 255f).toInt()
    return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
  }
}
