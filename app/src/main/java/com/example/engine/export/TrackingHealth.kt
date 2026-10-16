package com.example.engine.export

import java.util.concurrent.ConcurrentHashMap

/**
 * Failures of the face / body tracking sources while a project is exported.
 *
 * The tracking sources answer "where is the face (or body) at this source time". When they cannot,
 * the effect they drive - face reshape, AR filter, body reshape - cannot be drawn. Every failure was
 * swallowed inside the tracker (or its fallback wrapper) and turned into an empty result, so the
 * effect vanished from the exported video without a single line in logcat: a silent quality loss
 * *and* the reason a "why does my tracked effect disappear from the export" report cannot be
 * diagnosed. Each failure is recorded here, so the callers can log it and the export can say how
 * many frames were affected instead of pretending everything was rendered.
 *
 * One entry per clip (count + last reason + last source time) so a 30 s export cannot grow this
 * without bound.
 */
object TrackingHealth {

  data class Failure(val count: Long, val lastSourceUs: Long, val lastReason: String)

  private val failures = ConcurrentHashMap<String, Failure>()

  /** Records one failed tracking lookup for [clipId] at [sourceTimeUs]. */
  fun record(clipId: String, sourceTimeUs: Long, reason: String) {
    failures.compute(clipId) { _, previous ->
      Failure(
        count = (previous?.count ?: 0L) + 1L,
        lastSourceUs = sourceTimeUs,
        lastReason = reason
      )
    }
  }

  fun failureFor(clipId: String): Failure? = failures[clipId]

  fun totalFailures(): Long = failures.values.sumOf { it.count }

  fun clipIds(): Set<String> = failures.keys.toSet()

  /** `clip=3 frames (last: reason)`, or null when every tracked lookup succeeded. */
  fun summary(): String? {
    if (failures.isEmpty()) return null
    return failures.entries
      .sortedBy { it.key }
      .joinToString("; ") { (clipId, failure) ->
        "$clipId=${failure.count} frame(s) (last: ${failure.lastReason})"
      }
  }

  fun reset() = failures.clear()
}
