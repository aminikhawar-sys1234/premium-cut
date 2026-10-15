package com.example.engine.export

/**
 * When an export attempt must be abandoned because the GPU pipeline is writing black frames, and how
 * often that may be retried.
 *
 * Why this exists: a black export is the one failure every encoder-side statistic hides. Timestamps,
 * sample sizes, key frames, EOS and the muxer are all correct while every picture is empty, so the
 * render loop cannot notice it from the codec - only from the pixels it just drew. Detecting it here
 * (instead of after the file is muxed) stops a long export from finishing only to be rejected, and
 * makes it retryable, so the attempt ladder gets a second try with a different compositor/encoder.
 *
 * Pure Kotlin (no android.*), so the policy is unit-tested on the plain JVM.
 */
object ExportBlackFrameGuard {

  /** Black probes that must agree before an attempt is abandoned. */
  const val MIN_BLACK_PROBES = 4

  /**
   * The black probes must also span at least this fraction of the clip before an attempt is
   * abandoned. A fade-in from black, a dark intro or a deliberate black opening blacken the first
   * frames legitimately; a clip still black halfway through is a broken pipeline, not a creative
   * choice. A half (rather than a quarter) trades some wasted render time for never rejecting a real
   * project - the post-mux decoded-frame check catches whatever this lets through.
   */
  const val MIN_COVERED_DIVISOR = 2

  /**
   * Black outputs that may be retried. One retry: attempt 1 uses the native C++ compositor, the retry
   * uses the Kotlin compositor (and the next encoder in the ladder), so a second identical attempt
   * would only cost the user another full render.
   */
  const val MAX_BLACK_FRAME_ATTEMPTS = 2

  /**
   * Frames whose back buffer is sampled. The first frames are included so an all-black pipeline is
   * caught early, and the quarters so the probes span the whole clip.
   */
  fun probeFrames(totalFrames: Long): Set<Long> {
    if (totalFrames <= 0L) return emptySet()
    val last = totalFrames - 1
    val frames = linkedSetOf<Long>()
    for (frame in listOf(0L, 1L, 2L, 3L, totalFrames / 4, totalFrames / 2, totalFrames * 3 / 4, last)) {
      frames += frame.coerceIn(0L, last)
    }
    return frames
  }

  /**
   * True when every readable probe so far is black and those probes already cover
   * [MIN_COVERED_DIVISOR]th of the clip - i.e. the pipeline is demonstrably writing black frames.
   *
   * @param blackProbes readable probes whose sampled region was black
   * @param readableProbes probes whose GL readback succeeded (an unavailable readback proves nothing)
   * @param lastProbeFrame index of the most recent probe that was read
   * @param totalFrames frames this attempt will render
   */
  fun shouldAbortAttempt(
    blackProbes: Int,
    readableProbes: Int,
    lastProbeFrame: Long,
    totalFrames: Long
  ): Boolean {
    if (totalFrames <= 0L) return false
    if (readableProbes < MIN_BLACK_PROBES) return false
    if (blackProbes != readableProbes) return false
    return lastProbeFrame * MIN_COVERED_DIVISOR >= totalFrames
  }

  /** Whether a black output may be retried; [attemptsSoFar] counts the attempts already made. */
  fun mayRetryBlackOutput(attemptsSoFar: Int): Boolean = attemptsSoFar < MAX_BLACK_FRAME_ATTEMPTS

  /** Whether the given (1-based) attempt number should skip the native compositor. */
  fun useNativeCompositor(attemptNo: Int): Boolean = attemptNo <= 1
}
