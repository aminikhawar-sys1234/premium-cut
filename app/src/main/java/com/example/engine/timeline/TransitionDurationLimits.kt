package com.example.engine.timeline

import kotlin.math.min

/**
 * How long a centered transition may run between two clips.
 * The window is split across the cut, so it cannot be longer than the shorter clip
 * (and the tools slider never offers more than [UI_MAX_MS]).
 */
object TransitionDurationLimits {
  const val MIN_MS = 100L
  const val UI_MAX_MS = 2000L

  fun maxMs(beforeDurationMs: Long, afterDurationMs: Long): Long {
    val span = min(beforeDurationMs, afterDurationMs).coerceAtLeast(1L)
    return span.coerceAtMost(UI_MAX_MS)
  }

  fun clamp(requestedMs: Long, beforeDurationMs: Long, afterDurationMs: Long): Long {
    val max = maxMs(beforeDurationMs, afterDurationMs)
    val minAllowed = min(MIN_MS, max)
    return requestedMs.coerceIn(minAllowed, max)
  }
}
