package com.example.engine.ai.scene

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Small colour fingerprint of a video frame: a luma histogram plus a coarse brightness grid.
 * Cheap to compute on a 64 px thumbnail and robust against slow camera motion and exposure drift.
 */
class FrameSignature(val histogram: FloatArray, val grid: FloatArray)

/**
 * Finds hard cuts in a clip from frame fingerprints. The decoder side only has to provide
 * [FrameSignature]s for timestamps, so everything here is pure and unit tested.
 */
object SceneCutDetector {

  const val HIST_BINS = 32
  const val GRID = 4

  fun signature(argb: IntArray, width: Int, height: Int): FrameSignature {
    val hist = FloatArray(HIST_BINS)
    val grid = FloatArray(GRID * GRID)
    val counts = IntArray(GRID * GRID)
    if (width <= 0 || height <= 0 || argb.size < width * height) return FrameSignature(hist, grid)
    for (y in 0 until height) {
      val gy = (y * GRID / height).coerceAtMost(GRID - 1)
      for (x in 0 until width) {
        val p = argb[y * width + x]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        val luma = (0.299f * r + 0.587f * g + 0.114f * b)
        val bin = (luma * HIST_BINS / 256f).toInt().coerceIn(0, HIST_BINS - 1)
        hist[bin] += 1f
        val gi = gy * GRID + (x * GRID / width).coerceAtMost(GRID - 1)
        grid[gi] += luma / 255f
        counts[gi]++
      }
    }
    val total = (width * height).toFloat()
    for (i in hist.indices) hist[i] /= total
    for (i in grid.indices) if (counts[i] > 0) grid[i] /= counts[i]
    return FrameSignature(hist, grid)
  }

  /** 0 = identical, about 1 = unrelated pictures. Mixes histogram and layout change. */
  fun distance(a: FrameSignature, b: FrameSignature): Float {
    var h = 0f
    for (i in a.histogram.indices) h += abs(a.histogram[i] - b.histogram[i])
    h /= 2f
    var g = 0f
    for (i in a.grid.indices) g += abs(a.grid[i] - b.grid[i])
    g /= a.grid.size
    return 0.6f * h + 0.4f * g * 2f
  }

  /**
   * [sampleTimesMs] must be ascending. [signatureAt] returns the fingerprint of the frame at a time.
   * [sensitivity] 0..1: higher finds more (softer) cuts. Returned times are the first frame of the new
   * shot, refined by bisection to about [refineToMs].
   */
  fun findCuts(
    sampleTimesMs: List<Long>,
    sensitivity: Float = 0.5f,
    minShotMs: Long = 600L,
    refineToMs: Long = 40L,
    signatureAt: (Long) -> FrameSignature
  ): List<Long> {
    if (sampleTimesMs.size < 3) return emptyList()
    val sigs = sampleTimesMs.map { signatureAt(it) }
    val d = FloatArray(sigs.size - 1) { distance(sigs[it], sigs[it + 1]) }

    val absoluteFloor = 0.34f - 0.22f * sensitivity.coerceIn(0f, 1f)
    val relativeK = 6.0f - 3.5f * sensitivity.coerceIn(0f, 1f)
    val window = 6
    val candidates = ArrayList<Int>()
    for (i in d.indices) {
      val lo = max(0, i - window)
      val hi = minOf(d.size - 1, i + window)
      var sum = 0f
      var sumSq = 0f
      var n = 0
      for (j in lo..hi) {
        if (j == i) continue
        sum += d[j]; sumSq += d[j] * d[j]; n++
      }
      val mean = if (n > 0) sum / n else 0f
      val std = if (n > 0) sqrt(max(0f, sumSq / n - mean * mean)) else 0f
      val localMax = (i == 0 || d[i] >= d[i - 1]) && (i == d.size - 1 || d[i] > d[i + 1])
      if (localMax && d[i] > absoluteFloor && d[i] > mean + relativeK * std + 0.04f) candidates.add(i)
    }

    val cuts = ArrayList<Long>()
    for (i in candidates) {
      val refined = refine(sampleTimesMs[i], sampleTimesMs[i + 1], sigs[i], sigs[i + 1], refineToMs, signatureAt)
      if (cuts.isEmpty() || refined - cuts.last() >= minShotMs) cuts.add(refined)
      else if (d[i] > 0f) { /* a closer cut in the same flash is the same event */ }
    }
    return cuts.filter { it - sampleTimesMs.first() >= minShotMs / 2 }
  }

  private fun refine(
    t0: Long, t1: Long, before: FrameSignature, after: FrameSignature, toMs: Long,
    signatureAt: (Long) -> FrameSignature
  ): Long {
    var lo = t0
    var hi = t1
    while (hi - lo > toMs) {
      val mid = (lo + hi) / 2
      val s = signatureAt(mid)
      if (distance(s, before) <= distance(s, after)) lo = mid else hi = mid
    }
    return hi
  }
}
