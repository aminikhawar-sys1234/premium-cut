package com.example.engine.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pitch-preserving time stretch for interleaved 16-bit PCM (WSOLA).
 *
 * Export used to play a 2x clip by skipping source samples, which also doubled the pitch while the
 * live player (Media3, pitch fixed at 1.0) kept the voice natural. This is the same effect applied
 * offline so the exported file matches the preview.
 *
 * The overlap search runs on a 4x decimated mono copy and is refined at full rate, which keeps a
 * ten minute stereo clip in the order of seconds on a phone instead of minutes.
 */
object PitchSafeStretch {

  private const val MIN_FACTOR = 0.2
  private const val MAX_FACTOR = 6.0
  private const val DECIMATION = 4

  /** Speeds inside this band of 1.0 are treated as 1.0 (no processing, no artifacts). */
  private const val UNITY_TOLERANCE = 0.005

  /** Output length in frames for [inputFrames] played at [speed]. */
  fun outputFrames(inputFrames: Int, speed: Float): Int =
    max(1, (inputFrames / speed.coerceAtLeast(0.01f).toDouble()).roundToInt())

  fun isUnity(speed: Float): Boolean = abs(speed - 1f) < UNITY_TOLERANCE

  /**
   * Plays [pcm] at [speed] (2.0 = twice as fast, half as long) without changing its pitch.
   * [pcm] is interleaved with [channels] channels; the result has the same channel count.
   */
  fun applySpeed(pcm: ShortArray, channels: Int, sampleRate: Int, speed: Float): ShortArray =
    stretch(pcm, channels, sampleRate, 1.0 / speed.coerceAtLeast(0.01f))

  /** [factor] is output length / input length: above 1 slows down, below 1 speeds up. */
  fun stretch(pcm: ShortArray, channels: Int, sampleRate: Int, factor: Double): ShortArray {
    val ch = channels.coerceAtLeast(1)
    val inFrames = pcm.size / ch
    if (inFrames == 0 || abs(factor - 1.0) < UNITY_TOLERANCE) return pcm
    val f = factor.coerceIn(MIN_FACTOR, MAX_FACTOR)

    val frame = (sampleRate * 0.032).toInt().coerceAtLeast(256) and 1.inv()
    val hopS = frame / 2
    val outFrames = max(1, (inFrames * f).roundToInt())
    // A clip shorter than one analysis frame cannot be overlap-added; resample it instead.
    if (inFrames < frame * 2) return linearResample(pcm, ch, outFrames)

    val maxDelta = (sampleRate * 0.010).toInt().coerceAtLeast(DECIMATION * 2)
    val mono = FloatArray(inFrames)
    for (i in 0 until inFrames) {
      var s = 0f
      for (c in 0 until ch) s += pcm[i * ch + c]
      mono[i] = s / ch
    }
    val dec = decimate(mono)
    val window = FloatArray(frame) { (0.5 - 0.5 * cos(2.0 * PI * it / frame)).toFloat() }

    val out = FloatArray((outFrames + frame) * ch)
    var prevChosen = 0
    var k = 0
    while (true) {
      val outPos = k * hopS
      if (outPos >= outFrames) break
      val nominal = (outPos / f).roundToInt()
      val lo = (nominal - maxDelta).coerceAtLeast(0)
      val hi = (nominal + maxDelta).coerceAtMost(inFrames - frame).coerceAtLeast(0)
      val chosen = if (k == 0) 0 else {
        val template = prevChosen + hopS
        if (template + hopS > inFrames) nominal.coerceIn(0, (inFrames - frame).coerceAtLeast(0))
        else bestOffset(mono, dec, template, lo, hi, hopS)
      }
      overlapAdd(pcm, ch, inFrames, chosen, frame, window, out, outPos)
      prevChosen = chosen
      k++
    }

    val result = ShortArray(outFrames * ch)
    for (i in result.indices) {
      result[i] = out[i].roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
    return result
  }

  private fun decimate(mono: FloatArray): FloatArray {
    val n = mono.size / DECIMATION
    val d = FloatArray(n)
    for (i in 0 until n) {
      var s = 0f
      val base = i * DECIMATION
      for (j in 0 until DECIMATION) s += mono[base + j]
      d[i] = s / DECIMATION
    }
    return d
  }

  /** Candidate start in [lo, hi] whose first [len] samples best continue the segment at [template]. */
  private fun bestOffset(mono: FloatArray, dec: FloatArray, template: Int, lo: Int, hi: Int, len: Int): Int {
    if (hi <= lo) return lo.coerceAtLeast(0)
    val dLen = len / DECIMATION
    val dT = template / DECIMATION
    if (dT + dLen > dec.size) return ((lo + hi) / 2)
    var eT = 0.0
    for (i in 0 until dLen) eT += dec[dT + i].toDouble() * dec[dT + i]

    var bestD = lo / DECIMATION
    var bestScore = -Double.MAX_VALUE
    val dLo = lo / DECIMATION
    val dHi = hi / DECIMATION
    for (c in dLo..dHi) {
      if (c + dLen > dec.size) break
      var dot = 0.0
      var eC = 0.0
      for (i in 0 until dLen) {
        val a = dec[dT + i]
        val b = dec[c + i]
        dot += a.toDouble() * b
        eC += b.toDouble() * b
      }
      val score = dot / sqrt(eT * eC + 1e-9)
      if (score > bestScore) { bestScore = score; bestD = c }
    }

    var best = (bestD * DECIMATION).coerceIn(lo, hi)
    var bestFull = -Double.MAX_VALUE
    val refineLo = max(lo, best - DECIMATION)
    val refineHi = min(hi, best + DECIMATION)
    val step = 2
    val cmpLen = min(len, mono.size - template).coerceAtLeast(0)
    var eTf = 0.0
    var i0 = 0
    while (i0 < cmpLen) { eTf += mono[template + i0].toDouble() * mono[template + i0]; i0 += step }
    for (c in refineLo..refineHi) {
      if (c + cmpLen > mono.size) break
      var dot = 0.0
      var eC = 0.0
      var i = 0
      while (i < cmpLen) {
        val a = mono[template + i]
        val b = mono[c + i]
        dot += a.toDouble() * b
        eC += b.toDouble() * b
        i += step
      }
      val score = dot / sqrt(eTf * eC + 1e-9)
      if (score > bestFull) { bestFull = score; best = c }
    }
    return best
  }

  private fun overlapAdd(
    pcm: ShortArray, ch: Int, inFrames: Int, start: Int, frame: Int,
    window: FloatArray, out: FloatArray, outPos: Int
  ) {
    for (i in 0 until frame) {
      val src = start + i
      if (src >= inFrames) break
      val w = window[i]
      val o = (outPos + i) * ch
      if (o + ch > out.size) break
      val s = src * ch
      for (c in 0 until ch) out[o + c] += pcm[s + c] * w
    }
  }

  private fun linearResample(pcm: ShortArray, ch: Int, outFrames: Int): ShortArray {
    val inFrames = pcm.size / ch
    val out = ShortArray(outFrames * ch)
    if (inFrames == 0) return out
    val ratio = if (outFrames > 1) (inFrames - 1).toDouble() / (outFrames - 1) else 0.0
    for (i in 0 until outFrames) {
      val pos = i * ratio
      val i0 = pos.toInt().coerceIn(0, inFrames - 1)
      val i1 = (i0 + 1).coerceAtMost(inFrames - 1)
      val frac = (pos - i0).toFloat()
      for (c in 0 until ch) {
        val a = pcm[i0 * ch + c]
        val b = pcm[i1 * ch + c]
        out[i * ch + c] = (a + (b - a) * frac).roundToInt().toShort()
      }
    }
    return out
  }
}
