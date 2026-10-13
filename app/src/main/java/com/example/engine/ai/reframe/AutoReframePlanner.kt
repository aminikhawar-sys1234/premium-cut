package com.example.engine.ai.reframe

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Where the subject was at [timeMs] (clip-relative timeline ms). cx, cy are 0..1 from the top-left of the picture. */
data class SubjectSample(val timeMs: Long, val cx: Float, val cy: Float, val found: Boolean = true)

/**
 * One keyframe of the reframe move. [scale] is the uniform clip scale that fills the canvas; [posX] and
 * [posY] are the clip translation in canvas units (-1..1 spans the canvas), the same units
 * `ClipKeyframe.posX/posY` use in the compositor.
 */
data class ReframeKey(val timeMs: Long, val scale: Float, val posX: Float, val posY: Float)

/**
 * Plans a "follow the subject" crop when a clip's aspect ratio does not match the canvas (a 16:9
 * clip in a 9:16 project, or the reverse). Pure math: subject samples in, keyframes out.
 *
 * The virtual camera only starts moving once the subject leaves a dead zone around the middle of
 * the crop, then pans at a limited speed, so the result looks like an operator following a person
 * and not like jitter from the detector.
 */
object AutoReframePlanner {

  /** Uniform scale that makes the clip cover the canvas (no bars). 1.0 when aspects already match. */
  fun fillScale(sourceAspect: Float, canvasAspect: Float): Float {
    if (sourceAspect <= 0f || canvasAspect <= 0f) return 1f
    return if (sourceAspect >= canvasAspect) sourceAspect / canvasAspect else canvasAspect / sourceAspect
  }

  /** True when there is something to reframe (aspects differ by more than 2%). */
  fun needsReframe(sourceAspect: Float, canvasAspect: Float): Boolean =
    sourceAspect > 0f && canvasAspect > 0f && abs(sourceAspect / canvasAspect - 1f) > 0.02f

  fun plan(
    samples: List<SubjectSample>,
    sourceAspect: Float,
    canvasAspect: Float,
    durationMs: Long,
    deadZone: Float = 0.12f,
    maxPanPerSecond: Float = 0.6f
  ): List<ReframeKey> {
    if (!needsReframe(sourceAspect, canvasAspect) || durationMs <= 0L) return emptyList()
    val scale = fillScale(sourceAspect, canvasAspect)
    val horizontal = sourceAspect >= canvasAspect
    // Fraction of the source (along the pan axis) that the canvas shows.
    val visible = 1f / scale
    val half = visible / 2f

    val subject = fillGaps(samples.sortedBy { it.timeMs }, horizontal)
    val times = ArrayList<Long>()
    val axis = ArrayList<Float>()
    if (subject.isEmpty()) {
      times += 0L; axis += 0.5f; times += durationMs; axis += 0.5f
    } else {
      if (subject.first().first > 0L) { times += 0L; axis += subject.first().second }
      for ((t, v) in subject) { times += t.coerceIn(0L, durationMs); axis += v }
      if (times.last() < durationMs) { times += durationMs; axis += subject.last().second }
    }

    val cam = FloatArray(axis.size)
    cam[0] = axis[0].coerceIn(half, 1f - half)
    val dead = visible * deadZone
    for (i in 1 until axis.size) {
      val dt = (times[i] - times[i - 1]).coerceAtLeast(1L) / 1000f
      val delta = axis[i] - cam[i - 1]
      val target = when {
        delta > dead -> axis[i] - dead
        delta < -dead -> axis[i] + dead
        else -> cam[i - 1]
      }
      val limit = maxPanPerSecond * visible * dt
      val move = (target - cam[i - 1]).coerceIn(-limit, limit)
      cam[i] = (cam[i - 1] + move).coerceIn(half, 1f - half)
    }

    val maxShift = scale - 1f
    val keys = ArrayList<ReframeKey>(times.size)
    for (i in times.indices) {
      val shift = (-(2f * cam[i] - 1f) * scale).coerceIn(-maxShift, maxShift)
      // Screen y grows upwards in the compositor while picture rows grow downwards, hence the sign.
      keys += if (horizontal) ReframeKey(times[i], scale, shift, 0f)
      else ReframeKey(times[i], scale, 0f, -shift)
    }
    return thin(keys, 0.012f)
  }

  /** Subject position along the pan axis, with samples where nothing was found filled from their neighbours. */
  private fun fillGaps(sorted: List<SubjectSample>, horizontal: Boolean): List<Pair<Long, Float>> {
    if (sorted.none { it.found }) return emptyList()
    val values = sorted.map { if (horizontal) it.cx else it.cy }
    val out = ArrayList<Pair<Long, Float>>(sorted.size)
    var last = -1
    for (i in sorted.indices) {
      if (sorted[i].found) { last = i; out += sorted[i].timeMs to values[i].coerceIn(0f, 1f); continue }
      val next = (i + 1 until sorted.size).firstOrNull { sorted[it].found }
      val prevIdx = if (last >= 0) last else null
      val v = when {
        prevIdx != null && next != null -> {
          val a = sorted[prevIdx]; val b = sorted[next]
          val f = (sorted[i].timeMs - a.timeMs).toFloat() / max(1L, b.timeMs - a.timeMs)
          values[prevIdx] + (values[next] - values[prevIdx]) * f
        }
        prevIdx != null -> values[prevIdx]
        else -> values[next!!]
      }
      out += sorted[i].timeMs to v.coerceIn(0f, 1f)
    }
    return out
  }

  /** Drops keys that a straight line between their neighbours reproduces within [tolerance]. */
  internal fun thin(keys: List<ReframeKey>, tolerance: Float): List<ReframeKey> {
    if (keys.size <= 2) return keys
    val keep = BooleanArray(keys.size)
    keep[0] = true; keep[keys.size - 1] = true
    fun rec(lo: Int, hi: Int) {
      if (hi <= lo + 1) return
      var worst = -1
      var worstErr = tolerance
      for (i in lo + 1 until hi) {
        val f = (keys[i].timeMs - keys[lo].timeMs).toFloat() / max(1L, keys[hi].timeMs - keys[lo].timeMs)
        val ex = keys[lo].posX + (keys[hi].posX - keys[lo].posX) * f
        val ey = keys[lo].posY + (keys[hi].posY - keys[lo].posY) * f
        val err = max(abs(ex - keys[i].posX), abs(ey - keys[i].posY))
        if (err > worstErr) { worstErr = err; worst = i }
      }
      if (worst >= 0) { keep[worst] = true; rec(lo, worst); rec(worst, hi) }
    }
    rec(0, keys.size - 1)
    return keys.filterIndexed { i, _ -> keep[i] }
  }

  /** Subject position in canvas units for a key, to check it stays inside the visible window (-1..1). */
  fun subjectScreenX(key: ReframeKey, cx: Float): Float = key.posX + key.scale * (2f * cx - 1f)

  internal fun clamp01(v: Float) = min(1f, max(0f, v))
}
