package com.example.ui.components.timeline

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Single, deterministic time <-> pixel conversion used by the multi-track timeline.
 *
 * Time is Long milliseconds (the unit the timeline model uses). "msPerDp" is the horizontal scale of the
 * timeline (how many milliseconds one dp represents); it is derived from the zoom factor in exactly one place.
 *
 * Rules that keep the playhead from drifting:
 *  - math is done in Double, never Float, so large timelines do not lose precision;
 *  - both directions ROUND (never truncate), so ms -> px -> ms is stable to within half a pixel of time;
 *  - results are clamped to the valid range instead of being left to the caller.
 *
 * Pure Kotlin (no Compose / Android types) so it can be unit-tested on the JVM.
 */
object TimelineCoordinates {
  const val MIN_MS_PER_DP = 1.25f
  const val MAX_MS_PER_DP = 120f
  private const val BASE_MS_PER_DP = 20f

  /** Timeline scale for a zoom factor. The only place the zoom -> scale mapping is defined. */
  fun msPerDp(zoom: Float): Float {
    require(zoom > 0f && zoom.isFinite()) { "zoom must be a positive finite number, was $zoom" }
    return (BASE_MS_PER_DP / zoom).coerceIn(MIN_MS_PER_DP, MAX_MS_PER_DP)
  }

  /** Scroll offset in pixels that places [timeMs] under the playhead. */
  fun timeToScrollPx(timeMs: Long, msPerDp: Float, density: Float): Int {
    require(msPerDp > 0f && density > 0f) { "msPerDp and density must be positive" }
    val px = timeMs.coerceAtLeast(0L).toDouble() / msPerDp.toDouble() * density.toDouble()
    return px.roundToInt().coerceAtLeast(0)
  }

  /** Timeline time under the playhead for a scroll offset in pixels. */
  fun scrollPxToTime(scrollPx: Int, msPerDp: Float, density: Float, maxTimeMs: Long): Long {
    require(msPerDp > 0f && density > 0f) { "msPerDp and density must be positive" }
    val ms = scrollPx.coerceAtLeast(0).toDouble() / density.toDouble() * msPerDp.toDouble()
    return ms.roundToLong().coerceIn(0L, maxTimeMs.coerceAtLeast(0L))
  }

  /** Width in dp of a span of timeline time. */
  fun durationToDp(durationMs: Long, msPerDp: Float): Float {
    require(msPerDp > 0f) { "msPerDp must be positive" }
    return (durationMs.coerceAtLeast(0L).toDouble() / msPerDp.toDouble()).toFloat()
  }
}
