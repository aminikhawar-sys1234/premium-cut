package com.example.engine.export

/**
 * Pure decision rules for "is this exported MP4 acceptable?", separated from the Android probing code
 * (MediaMetadataRetriever / MediaExtractor) so they can be unit-tested on the JVM.
 *
 * An export must never be reported as successful when it has no decodable video (audio-only output,
 * truncated header, zero-size frames or zero duration).
 */
object Mp4ValidationRules {
  const val MIN_PLAUSIBLE_FILE_BYTES = 10_240L

  /** Everything the platform probe could read from the file. Null means the field was missing. */
  data class Probe(
    val fileBytes: Long,
    val hasVideo: String?,
    val width: String?,
    val height: String?,
    val durationMs: String?
  )

  /**
   * @return null when the file is acceptable, otherwise a human-readable reason it was rejected.
   */
  fun rejectionReason(probe: Probe, expectedDurationMs: Long? = null, durationToleranceMs: Long = 1_000L): String? {
    if (probe.fileBytes <= MIN_PLAUSIBLE_FILE_BYTES) {
      return "file too small (${probe.fileBytes} bytes)"
    }
    if (probe.hasVideo != "yes") {
      return "no video track (has_video=${probe.hasVideo})"
    }
    val w = probe.width?.toIntOrNull() ?: return "missing video width"
    val h = probe.height?.toIntOrNull() ?: return "missing video height"
    if (w <= 0 || h <= 0) return "invalid video dimensions ${w}x$h"
    val dur = probe.durationMs?.toLongOrNull() ?: return "missing duration"
    if (dur <= 0L) return "zero duration"
    if (expectedDurationMs != null && expectedDurationMs > 0L) {
      val diff = kotlin.math.abs(dur - expectedDurationMs)
      if (diff > durationToleranceMs.coerceAtLeast(expectedDurationMs / 20)) {
        return "duration mismatch (expected ~${expectedDurationMs}ms, file reports ${dur}ms)"
      }
    }
    return null
  }
}
