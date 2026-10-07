package com.example.engine.timeline

/**
 * Pure, allocation-light math for source-aware clip trimming.
 *
 * A trim never changes what is shown at a given media position: it only moves the in/out points.
 * For a forward clip the timeline start is bound to the source IN point, for a reversed clip the
 * timeline start is bound to the source OUT point. Speed maps timeline time to source time linearly
 * (sourceSpan = timelineSpan * speed). A bounded media length keeps the clip inside the real file;
 * an unbounded one (still images, legacy projects without a recorded length) only clamps at 0.
 */
object ClipTrimMath {

  const val MIN_TRIM_DURATION_MS = 200L

  data class Range(
    val timelineStartMs: Long,
    val durationMs: Long,
    val sourceStartMs: Long,
    val sourceEndMs: Long
  )

  /**
   * Moves the clip's left edge to [requestedStartMs]. The right edge on the timeline and the media
   * shown there stay fixed.
   *
   * @param mediaDurationMs real length of the source media, or 0 when unbounded.
   * @param pinToZero when true the left edge stays at 0 and only the source in-point/duration change
   *   (main track "zero point lock").
   */
  fun trimStart(
    timelineStartMs: Long,
    durationMs: Long,
    sourceStartMs: Long,
    sourceEndMs: Long,
    speed: Float,
    reversed: Boolean,
    mediaDurationMs: Long,
    requestedStartMs: Long,
    pinToZero: Boolean = false,
    hasSource: Boolean = true,
    minDurationMs: Long = MIN_TRIM_DURATION_MS
  ): Range {
    val s = speed.coerceAtLeast(MIN_SPEED).toDouble()
    val endMs = timelineStartMs + durationMs
    val minDur = minDurationMs.coerceAtMost(durationMs).coerceAtLeast(1L)

    var target = requestedStartMs
    if (pinToZero) target = timelineStartMs + (requestedStartMs - timelineStartMs).coerceAtLeast(0L)
    target = target.coerceIn(0L, endMs - minDur)
    var delta = target - timelineStartMs

    if (delta < 0L && hasSource) {
      val room = if (!reversed) sourceStartMs else (if (mediaDurationMs > 0L) mediaDurationMs - sourceEndMs else Long.MAX_VALUE)
      val maxExtendMs = if (room == Long.MAX_VALUE) Long.MAX_VALUE else (room / s).toLong()
      if (-delta > maxExtendMs) delta = -maxExtendMs
    }

    val sourceDelta = Math.round(delta * s)
    val newStart = if (pinToZero) timelineStartMs else timelineStartMs + delta
    val newDuration = durationMs - delta
    if (!hasSource) return Range(newStart, newDuration, sourceStartMs, sourceEndMs)
    return if (!reversed) {
      Range(newStart, newDuration, (sourceStartMs + sourceDelta).coerceAtLeast(0L), sourceEndMs)
    } else {
      Range(newStart, newDuration, sourceStartMs, (sourceEndMs - sourceDelta).coerceAtLeast(sourceStartMs))
    }
  }

  /** Moves the clip's right edge so the clip lasts [requestedDurationMs] (clamped to the media). */
  fun trimEnd(
    timelineStartMs: Long,
    durationMs: Long,
    sourceStartMs: Long,
    sourceEndMs: Long,
    speed: Float,
    reversed: Boolean,
    mediaDurationMs: Long,
    requestedDurationMs: Long,
    hasSource: Boolean = true,
    minDurationMs: Long = MIN_TRIM_DURATION_MS
  ): Range {
    val s = speed.coerceAtLeast(MIN_SPEED).toDouble()
    val minDur = minDurationMs.coerceAtMost(durationMs).coerceAtLeast(1L)
    var newDuration = requestedDurationMs.coerceAtLeast(minDur)

    if (newDuration > durationMs && hasSource) {
      val room = if (!reversed) (if (mediaDurationMs > 0L) mediaDurationMs - sourceStartMs else Long.MAX_VALUE) else sourceStartMs
      if (room != Long.MAX_VALUE) {
        val maxDuration = (room / s).toLong().coerceAtLeast(durationMs)
        newDuration = newDuration.coerceAtMost(maxDuration)
      }
    }

    if (!hasSource) return Range(timelineStartMs, newDuration, sourceStartMs, sourceEndMs)
    val sourceSpan = Math.round(newDuration * s)
    return if (!reversed) {
      Range(timelineStartMs, newDuration, sourceStartMs, sourceStartMs + sourceSpan)
    } else {
      Range(timelineStartMs, newDuration, (sourceEndMs - sourceSpan).coerceAtLeast(0L), sourceEndMs)
    }
  }

  private const val MIN_SPEED = 0.01f
}
