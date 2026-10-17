package com.example.engine.export

/**
 * The rule that turns *"the export sat at 5 % for twenty minutes and then nothing happened"* into a
 * named failure.
 *
 * ## The defect it closes
 *
 * The pipeline already had an "encoder stopped consuming" watchdog, but it was gated on
 * `swapPending || gpuFrames > encodedFrames` - i.e. it could only fire **after at least one frame
 * had been submitted to the encoder**. An export that never reaches its first frame (a vendor
 * encoder hanging inside `configure()`, a permanently blocked `eglSwapBuffers`, a decoder that
 * never returns, a stalled PCM mix) satisfies neither condition, so the wait loop
 * (`while (!renderCompleteLatch.await(1, SECONDS))`) spun forever: the panel kept the last value it
 * had - the 5 % pre-roll floor with `encoded 0 / N` - and the user got no error at all.
 *
 * ## The rule
 *
 * Every phase has a budget. A budget is either a hard wall-clock limit (a stage that has no
 * measurable internal progress: opening a codec, creating the muxer) or "no measurable progress for
 * N seconds" (a stage that *does* report progress: the PCM mix reports one step per mixed chunk).
 * A paused export is never stalled - the user asked for the stop (see [Heartbeat.paused]).
 *
 * Verdicts:
 *
 * * [Verdict.Continue] - keep waiting.
 * * [Verdict.Stall] - the attempt must be abandoned with [Verdict.Stall.message] and
 *   `retryable = false` (a hang is a resource/vendor condition; re-running the identical attempt
 *   would hang identically and cost the user another twenty minutes).
 *
 * Pure Kotlin (no `android.*`), so the whole policy - including the exact scenario of this report,
 * "0 frames for twenty minutes in the mix phase" - is unit-tested on a plain JVM.
 */
object ExportStallWatchdog {

  /** Nothing at all submitted within this long after the attempt started: the attempt is stuck. */
  const val FIRST_FRAME_DEADLINE_MS = 150_000L

  /** After the first frame, a single frame may not take longer than this. */
  const val FRAME_DEADLINE_MS = 75_000L

  /** A frame was submitted but the encoder produced nothing for this long: it is not consuming. */
  const val ENCODER_IDLE_DEADLINE_MS = 60_000L

  /** Budget of one phase: a hard limit, and (when the phase reports progress) a no-progress limit. */
  data class Budget(
    val hardMs: Long,
    /**
     * Longest time the phase may spend without its own progress counter moving. 0 = the phase has no
     * internal progress to report, so only [hardMs] applies.
     */
    val noProgressMs: Long = 0L
  )

  /**
   * Per-phase budgets. The values are deliberately generous (they must never fire on a slow device
   * doing real work) but finite, because the failure they replace is an *unbounded* wait.
   */
  val budgets: Map<ExportPhase, Budget> = mapOf(
    ExportPhase.DEVICE_CHECK to Budget(hardMs = 30_000L),
    ExportPhase.PREPARING to Budget(hardMs = 30_000L),
    // Reading every image/sticker of the project off disk or a content provider.
    ExportPhase.LOADING_ASSETS to Budget(hardMs = 180_000L),
    // A vendor encoder may hang inside createByCodecName/configure/createInputSurface.
    ExportPhase.OPENING_ENCODER to Budget(hardMs = 90_000L),
    // EGL context + first GL context bind onto the encoder surface.
    ExportPhase.INITIALISING_GPU to Budget(hardMs = 60_000L),
    // The PCM mix of a long, multi-track timeline legitimately takes minutes on a phone, so the
    // budget is "the mix must keep making progress" (one step per mixed chunk) rather than a wall
    // clock. The hard limit only catches a mix that is thrashing without ever finishing a chunk.
    ExportPhase.MIXING_AUDIO to Budget(hardMs = 30 * 60_000L, noProgressMs = 60_000L),
    ExportPhase.OPENING_AUDIO_ENCODER to Budget(hardMs = 60_000L),
    // MediaMuxer construction + videoEncoder.start() + the first audio priming buffers.
    ExportPhase.OPENING_MUXER to Budget(hardMs = 60_000L),
    // Warming is budgeted internally (10 s); this only catches a tracker that never returns.
    ExportPhase.WARMING_TRACKERS to Budget(hardMs = 120_000L),
    ExportPhase.RENDERING to Budget(hardMs = FIRST_FRAME_DEADLINE_MS, noProgressMs = FRAME_DEADLINE_MS),
    // Encoder drain + moov write.
    ExportPhase.FINALISING to Budget(hardMs = 180_000L),
    // Decoding sample frames of the finished file.
    ExportPhase.VERIFYING to Budget(hardMs = 300_000L)
  )

  fun budgetFor(phase: ExportPhase): Budget = budgets.getValue(phase)

  /** The production budgets, or a caller's override: see [verdict]. */
  fun budgetsOf(overrides: Map<ExportPhase, Budget>?): Map<ExportPhase, Budget> = overrides ?: budgets

  /** Everything the watchdog needs to judge one attempt. */
  data class Heartbeat(
    val phase: ExportPhase,
    /** Milliseconds since this attempt started (encoder planning included). */
    val attemptElapsedMs: Long,
    /** Milliseconds inside the current phase. */
    val phaseElapsedMs: Long,
    /**
     * Milliseconds since the current phase last reported internal progress; ignored when the phase's
     * budget has no no-progress limit or when [hasPhaseProgress] is false.
     */
    val phaseProgressAgoMs: Long,
    val hasPhaseProgress: Boolean,
    val framesSubmitted: Long,
    val framesEncoded: Long,
    /** Milliseconds since the last frame reached the encoder; -1 while no frame has. */
    val sinceLastFrameSubmittedMs: Long,
    /** Milliseconds since the encoder last produced a sample; -1 while nothing was produced. */
    val sinceLastEncodedMs: Long,
    /** True while the render thread is blocked inside `eglSwapBuffers` for a frame. */
    val swapPending: Boolean,
    /** The user paused the export: a pause is not a stall. */
    val paused: Boolean,
    /**
     * True while the drain thread is intentionally throttling the video encoder because the muxer
     * is waiting for the audio track of the still-running background PCM mix/encode. While this is
     * set the frame clock is NOT the liveness signal - the background audio path's is.
     */
    val awaitingAudioMix: Boolean = false,
    /**
     * Milliseconds since the background audio path (mix chunk or PCM feed batch) last progressed;
     * -1 while nothing has. Only meaningful together with [awaitingAudioMix].
     */
    val sinceAudioPipelineProgressMs: Long = -1L
  )

  sealed class Verdict {
    object Continue : Verdict()

    /**
     * @param retryable false: a stall is a device/resource condition. Re-running the identical
     *   attempt would hang the same way, and the user has already waited for it once.
     */
    data class Stall(
      val kind: Kind,
      val phase: ExportPhase,
      val waitedMs: Long,
      val message: String,
      val retryable: Boolean = false
    ) : Verdict()
  }

  enum class Kind { PHASE_TIMEOUT, PHASE_NO_PROGRESS, NO_FIRST_FRAME, FRAME_TIMEOUT, ENCODER_STALLED }

  /**
   * Judges one attempt. [Heartbeat.sinceLastFrameSubmittedMs] and [Heartbeat.sinceLastEncodedMs] are
   * `-1` when nothing has happened yet (the state the reported export was stuck in).
   */
  /**
   * @param overrides test/instrumentation budgets, so a stall can be provoked on a device without
   *   waiting out the production deadlines (which are generous on purpose). Production passes none.
   */
  fun verdict(
    heartbeat: Heartbeat,
    overrides: Map<ExportPhase, Budget>? = null
  ): Verdict {
    if (heartbeat.paused) return Verdict.Continue
    val phase = heartbeat.phase
    val budget = budgetsOf(overrides)[phase] ?: budgetFor(phase)

    // A stage that produces frames is judged by its frames, never by a wall clock: rendering a
    // ten-minute project is *supposed* to take minutes, so "the phase is older than its budget" can
    // only mean "not one frame has been submitted yet".
    if (phase.isFrameStage) {
      // Intentional backpressure (CapCut-style mix/encode overlap): the video encoder is
      // throttled on purpose while the muxer waits for the background audio track, so the frame
      // clock says nothing about liveness here. The pipeline is alive exactly as long as the
      // background audio path keeps making progress - a slow mix is legitimate, a wedged one is
      // still reported, by its own no-progress budget.
      if (heartbeat.awaitingAudioMix) {
        val mixBudget = budgetsOf(overrides)[ExportPhase.MIXING_AUDIO] ?: budgetFor(ExportPhase.MIXING_AUDIO)
        if (heartbeat.sinceAudioPipelineProgressMs >= 0L &&
          heartbeat.sinceAudioPipelineProgressMs > mixBudget.noProgressMs
        ) {
          return stall(
            Kind.PHASE_NO_PROGRESS,
            phase,
            heartbeat.sinceAudioPipelineProgressMs,
            "the background audio mix stopped making progress for " +
              seconds(heartbeat.sinceAudioPipelineProgressMs) + " (the muxer is waiting for its " +
              "track; frames submitted=${heartbeat.framesSubmitted}, encoded=${heartbeat.framesEncoded})"
          )
        }
        return Verdict.Continue
      }
      if (heartbeat.framesSubmitted == 0L) {
        if (heartbeat.phaseElapsedMs > budget.hardMs) {
          return stall(
            Kind.NO_FIRST_FRAME,
            phase,
            heartbeat.phaseElapsedMs,
            "no frame reached the video encoder in the ${seconds(heartbeat.phaseElapsedMs)} " +
              "since rendering started (the export is still in '${phase.label}': ${phase.detail}; " +
              "frames submitted=0, encoded=${heartbeat.framesEncoded})"
          )
        }
        return Verdict.Continue
      }

      // Frames are flowing: the attempt is alive as long as it keeps submitting them and the
      // encoder keeps consuming them.
      val sinceFrame = heartbeat.sinceLastFrameSubmittedMs
      if (sinceFrame >= 0L && sinceFrame > FRAME_DEADLINE_MS) {
        val cause = if (heartbeat.swapPending) {
          "the render thread is blocked submitting a frame to the encoder (eglSwapBuffers has not " +
            "returned)"
        } else {
          "the render loop has not submitted a frame (it is inside decode/composition of the frame)"
        }
        return stall(
          Kind.FRAME_TIMEOUT,
          phase,
          sinceFrame,
          "no frame reached the video encoder for ${seconds(sinceFrame)}: $cause. " +
            "Submitted=${heartbeat.framesSubmitted}, encoded=${heartbeat.framesEncoded}."
        )
      }
      val sinceEncoded = heartbeat.sinceLastEncodedMs
      if (sinceEncoded >= 0L && sinceEncoded > ENCODER_IDLE_DEADLINE_MS &&
        heartbeat.framesSubmitted > heartbeat.framesEncoded
      ) {
        return stall(
          Kind.ENCODER_STALLED,
          phase,
          sinceEncoded,
          "the video encoder stopped producing output for ${seconds(sinceEncoded)} " +
            "(frames submitted=${heartbeat.framesSubmitted}, encoded=${heartbeat.framesEncoded}, " +
            "swapPending=${heartbeat.swapPending})"
        )
      }
      return Verdict.Continue
    }

    // A pre-roll (or finalisation) stage has no frames to report, so its own budget is the whole
    // rule: a hard wall clock for stages that cannot measure progress, and a
    // "the progress counter has stopped moving" limit for stages that can (the PCM mix).
    if (heartbeat.phaseElapsedMs > budget.hardMs) {
      return stall(
        Kind.PHASE_TIMEOUT,
        phase,
        heartbeat.phaseElapsedMs,
        "the export is stuck in '${phase.label}' (${phase.detail}) and has been there for " +
          seconds(heartbeat.phaseElapsedMs)
      )
    }
    if (budget.noProgressMs > 0L && heartbeat.hasPhaseProgress &&
      heartbeat.phaseProgressAgoMs > budget.noProgressMs
    ) {
      return stall(
        Kind.PHASE_NO_PROGRESS,
        phase,
        heartbeat.phaseProgressAgoMs,
        "the export is stuck in '${phase.label}': its progress has not moved for " +
          seconds(heartbeat.phaseProgressAgoMs)
      )
    }
    return Verdict.Continue
  }

  private fun stall(kind: Kind, phase: ExportPhase, waitedMs: Long, detail: String) = Verdict.Stall(
    kind = kind,
    phase = phase,
    waitedMs = waitedMs,
    message = "Export stopped: $detail. No partial file was written; the project is untouched."
  )

  /** `12s` / `3min 20s`, so a log line reads like a duration and not like a raw counter. */
  fun seconds(ms: Long): String {
    val safe = ms.coerceAtLeast(0L)
    if (safe < 60_000L) return "${safe / 1000}s"
    val minutes = safe / 60_000L
    val seconds = (safe % 60_000L) / 1000L
    return if (seconds == 0L) "${minutes}min" else "${minutes}min ${seconds}s"
  }
}
