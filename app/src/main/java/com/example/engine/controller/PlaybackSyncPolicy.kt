package com.example.engine.controller

/**
 * Rules that keep the editor playhead and the media players on one timeline.
 *
 * The master clock is the CTI. A decoder's current position is source time and,
 * after a keyframe seek, sits about one GOP (typically 1–2 seconds) behind the
 * requested frame. Feeding that position back into the clock, or re-seeking to
 * the nearest keyframe on every tick, makes playback jump backward and makes
 * the next Play start somewhere other than the needle.
 */
object PlaybackSyncPolicy {
  /** Ignore normal decoder lag. Only a real desync is worth a corrective seek. */
  const val DRIFT_CORRECT_THRESHOLD_MS = 500L

  /** Let an in-flight seek land before measuring drift again. */
  const val DRIFT_CORRECT_COOLDOWN_MS = 900L

  /**
   * Player/audio presentation time must never move the editor clock.
   * The clock is anchored at the CTI and advances monotonically.
   */
  fun shouldRebaseMasterClock(@Suppress("UNUSED_PARAMETER") monotonicMs: Long, @Suppress("UNUSED_PARAMETER") externalTimelineMs: Long): Boolean = false

  fun shouldCorrectSourceDrift(
    playerSourceMs: Long,
    expectedSourceMs: Long,
    playerReadyAndPlaying: Boolean,
    millisSinceLastCorrection: Long,
    thresholdMs: Long = DRIFT_CORRECT_THRESHOLD_MS,
    cooldownMs: Long = DRIFT_CORRECT_COOLDOWN_MS
  ): Boolean {
    if (!playerReadyAndPlaying) return false
    if (millisSinceLastCorrection >= 0L && millisSinceLastCorrection < cooldownMs) return false
    return kotlin.math.abs(playerSourceMs - expectedSourceMs) > thresholdMs
  }
}
