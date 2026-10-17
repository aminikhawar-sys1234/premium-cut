package com.example.engine.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The report's exact failure, as a test: *"the panel opened at 5 % / `encoded 0 / 18484 frames` and
 * after twenty minutes the progress had still not started"*.
 *
 * The pipeline's old watchdog could not produce a verdict for that state. Its condition was
 *
 * ```
 * idleSec > 60 && (swapPending || metrics.gpuFrames.get() > metrics.encodedFrames.get())
 * ```
 *
 * i.e. it required **at least one submitted frame** before it could say anything. An export stuck
 * before frame 0 (a vendor encoder hanging inside `configure()`, a decoder that never returns, a PCM
 * mix that stopped making progress, a blocked `MediaMetadataRetriever`) satisfied neither side of the
 * `||`, so the wait loop spun forever and the panel kept its last value: the 5 % pre-roll floor.
 *
 * Every case below is a state the pipeline really reaches; the watchdog's verdict is what turns each
 * of them into a named, non-retryable failure instead of a silent wait.
 */
class ExportStallWatchdogTest {

  private fun heartbeat(
    phase: ExportPhase = ExportPhase.RENDERING,
    attemptElapsedMs: Long = 10_000L,
    phaseElapsedMs: Long = 10_000L,
    phaseProgressAgoMs: Long = 10_000L,
    hasPhaseProgress: Boolean = ExportStallWatchdog.budgetFor(phase).noProgressMs > 0L,
    framesSubmitted: Long = 0L,
    framesEncoded: Long = 0L,
    sinceLastFrameSubmittedMs: Long = -1L,
    sinceLastEncodedMs: Long = -1L,
    swapPending: Boolean = false,
    paused: Boolean = false,
    awaitingAudioMix: Boolean = false,
    sinceAudioPipelineProgressMs: Long = -1L
  ) = ExportStallWatchdog.Heartbeat(
    phase = phase,
    attemptElapsedMs = attemptElapsedMs,
    phaseElapsedMs = phaseElapsedMs,
    phaseProgressAgoMs = phaseProgressAgoMs,
    hasPhaseProgress = hasPhaseProgress,
    framesSubmitted = framesSubmitted,
    framesEncoded = framesEncoded,
    sinceLastFrameSubmittedMs = sinceLastFrameSubmittedMs,
    sinceLastEncodedMs = sinceLastEncodedMs,
    swapPending = swapPending,
    paused = paused,
    awaitingAudioMix = awaitingAudioMix,
    sinceAudioPipelineProgressMs = sinceAudioPipelineProgressMs
  )

  // ------------------------------------------------------------------ the reported state

  @Test
  fun zeroFramesAfterTwentyMinutesIsAVerdictNotASilentWait() {
    val verdict = ExportStallWatchdog.verdict(
      heartbeat(
        phase = ExportPhase.RENDERING,
        attemptElapsedMs = 20 * 60_000L,
        phaseElapsedMs = 20 * 60_000L,
        framesSubmitted = 0L,
        framesEncoded = 0L
      )
    )
    assertTrue("the reported export must produce a verdict, was $verdict", verdict is ExportStallWatchdog.Verdict.Stall)
    val stall = verdict as ExportStallWatchdog.Verdict.Stall
    assertEquals(ExportStallWatchdog.Kind.NO_FIRST_FRAME, stall.kind)
    assertEquals(ExportPhase.RENDERING, stall.phase)
    assertTrue(
      "the message must name the frame counter: ${stall.message}",
      stall.message.contains("no frame reached the video encoder")
    )
    assertTrue("the message must carry the duration: ${stall.message}", stall.message.contains("20min"))
    assertTrue("the message must name the stage: ${stall.message}", stall.message.contains(ExportPhase.RENDERING.label))
    assertFalse("a stall is not retryable", stall.retryable)
  }

  @Test
  fun zeroFramesBeforeTheDeadlineKeepsWaiting() {
    // The budget is generous on purpose: a slow device rendering its first frame must not be failed.
    assertTrue(
      ExportStallWatchdog.verdict(
        heartbeat(phaseElapsedMs = ExportStallWatchdog.FIRST_FRAME_DEADLINE_MS - 1_000L)
      ) is ExportStallWatchdog.Verdict.Continue
    )
  }

  @Test
  fun theFirstFrameDeadlineIsTheRenderingBudget() {
    assertEquals(
      ExportStallWatchdog.FIRST_FRAME_DEADLINE_MS,
      ExportStallWatchdog.budgetFor(ExportPhase.RENDERING).hardMs
    )
    // ...and it is what the verdict uses: one millisecond past it fails, one millisecond before it
    // keeps waiting.
    assertTrue(
      ExportStallWatchdog.verdict(
        heartbeat(phaseElapsedMs = ExportStallWatchdog.FIRST_FRAME_DEADLINE_MS + 1L)
      ) is ExportStallWatchdog.Verdict.Stall
    )
  }

  // ------------------------------------------------------------------ not a stall (no false positives)

  @Test
  fun aSlowButProgressingTenMinuteExportIsNeverFailed() {
    // 18484 frames rendered over 25 minutes on a phone: a long time, but the pipeline is moving. A
    // wall-clock budget on the frame stage would kill this export, so the frame stage is judged by
    // its frames only.
    val verdict = ExportStallWatchdog.verdict(
      heartbeat(
        phase = ExportPhase.RENDERING,
        attemptElapsedMs = 25 * 60_000L,
        phaseElapsedMs = 24 * 60_000L,
        framesSubmitted = 18_300L,
        framesEncoded = 18_250L,
        sinceLastFrameSubmittedMs = 2_500L,
        sinceLastEncodedMs = 2_400L
      )
    )
    assertTrue("a slow but moving export must keep running, got $verdict", verdict is ExportStallWatchdog.Verdict.Continue)
  }

  @Test
  fun aPausedExportIsNeverAStall() {
    val paused = ExportStallWatchdog.verdict(
      heartbeat(
        phase = ExportPhase.MIXING_AUDIO,
        attemptElapsedMs = 60 * 60_000L,
        phaseElapsedMs = 60 * 60_000L,
        phaseProgressAgoMs = 60 * 60_000L,
        framesSubmitted = 0L,
        paused = true
      )
    )
    assertTrue("a user pause is not a stall, got $paused", paused is ExportStallWatchdog.Verdict.Continue)
  }

  @Test
  fun aMixThatIsStillMakingProgressIsNeverFailed() {
    // The mix of a ten minute, multi-track project legitimately runs for minutes. What matters is
    // that it keeps completing chunks.
    val verdict = ExportStallWatchdog.verdict(
      heartbeat(
        phase = ExportPhase.MIXING_AUDIO,
        attemptElapsedMs = 12 * 60_000L,
        phaseElapsedMs = 9 * 60_000L,
        phaseProgressAgoMs = 4_000L,
        framesSubmitted = 0L
      )
    )
    assertTrue("a progressing mix must keep running, got $verdict", verdict is ExportStallWatchdog.Verdict.Continue)
  }

  // ------------------------------------------------------------------ every stage can report

  @Test
  fun aMixWhoseProgressStopsIsReportedWithItsStageName() {
    val verdict = ExportStallWatchdog.verdict(
      heartbeat(
        phase = ExportPhase.MIXING_AUDIO,
        attemptElapsedMs = 6 * 60_000L,
        phaseElapsedMs = 5 * 60_000L,
        phaseProgressAgoMs = 90_000L,
        framesSubmitted = 0L
      )
    )
    val stall = verdict as? ExportStallWatchdog.Verdict.Stall ?: error("expected a stall, got $verdict")
    assertEquals(ExportStallWatchdog.Kind.PHASE_NO_PROGRESS, stall.kind)
    assertTrue(stall.message.contains(ExportPhase.MIXING_AUDIO.label))
    assertTrue(stall.message.contains("1min 30s"))
    assertFalse(stall.retryable)
  }

  @Test
  fun aMixThatNeverFinishesItsChunksIsReportedByItsHardBudget() {
    val budget = ExportStallWatchdog.budgetFor(ExportPhase.MIXING_AUDIO)
    assertTrue("the mix must be able to run for minutes", budget.hardMs >= 10 * 60_000L)
    val verdict = ExportStallWatchdog.verdict(
      heartbeat(
        phase = ExportPhase.MIXING_AUDIO,
        attemptElapsedMs = budget.hardMs + 1_000L,
        phaseElapsedMs = budget.hardMs + 1_000L,
        phaseProgressAgoMs = 1_000L,
        framesSubmitted = 0L
      )
    )
    val stall = verdict as? ExportStallWatchdog.Verdict.Stall ?: error("expected a stall, got $verdict")
    assertEquals(ExportStallWatchdog.Kind.PHASE_TIMEOUT, stall.kind)
  }

  @Test
  fun aVendorCallThatNeverReturnsIsReportedByTheStagesOwnBudget() {
    // MediaCodec.createByCodecName/configure/createInputSurface has no timeout of its own. Before
    // this budget existed the export thread stayed inside that call forever and reported nothing.
    for (phase in ExportPhase.entries.filter { it.isPreRoll }) {
      val budget = ExportStallWatchdog.budgetFor(phase)
      assertTrue("${phase.name} must have a finite budget", budget.hardMs in 1..60 * 60_000L)
      val verdict = ExportStallWatchdog.verdict(
        heartbeat(
          phase = phase,
          attemptElapsedMs = budget.hardMs + 5_000L,
          phaseElapsedMs = budget.hardMs + 5_000L,
          framesSubmitted = 0L
        )
      )
      val stall = verdict as? ExportStallWatchdog.Verdict.Stall
        ?: error("${phase.name} overrunning its budget must be a stall, got $verdict")
      assertEquals("${phase.name} must time out, not no-progress", ExportStallWatchdog.Kind.PHASE_TIMEOUT, stall.kind)
      assertTrue(stall.message.contains(phase.label))
    }
  }

  @Test
  fun theDrainAndVerificationStagesHaveTheirOwnBudgets() {
    // "Remaining: Finalizing..." was the reported panel's other stuck row: the drain/moov stage is
    // now judged by a budget of its own instead of the frame clock.
    for (phase in listOf(ExportPhase.FINALISING, ExportPhase.VERIFYING)) {
      val budget = ExportStallWatchdog.budgetFor(phase)
      assertTrue(budget.hardMs > 0L)
      assertTrue(
        ExportStallWatchdog.verdict(
          heartbeat(phase = phase, phaseElapsedMs = budget.hardMs + 1L, framesSubmitted = 18_484L)
        ) is ExportStallWatchdog.Verdict.Stall
      )
      assertTrue(
        ExportStallWatchdog.verdict(
          heartbeat(phase = phase, phaseElapsedMs = 5_000L, framesSubmitted = 18_484L)
        ) is ExportStallWatchdog.Verdict.Continue
      )
    }
  }

  // ------------------------------------------------------------------ encoder-side conditions

  @Test
  fun anEncoderThatStopsConsumingFramesIsReported() {
    val verdict = ExportStallWatchdog.verdict(
      heartbeat(
        framesSubmitted = 5_000L,
        framesEncoded = 4_000L,
        sinceLastFrameSubmittedMs = 3_000L,
        sinceLastEncodedMs = ExportStallWatchdog.ENCODER_IDLE_DEADLINE_MS + 1_000L
      )
    )
    val stall = verdict as? ExportStallWatchdog.Verdict.Stall ?: error("expected a stall, got $verdict")
    assertEquals(ExportStallWatchdog.Kind.ENCODER_STALLED, stall.kind)
    assertTrue(stall.message.contains("submitted=5000"))
    assertTrue(stall.message.contains("encoded=4000"))
  }

  @Test
  fun aRenderLoopParkedInSwapBuffersIsReportedWithThatCause() {
    val verdict = ExportStallWatchdog.verdict(
      heartbeat(
        framesSubmitted = 120L,
        framesEncoded = 120L,
        sinceLastFrameSubmittedMs = ExportStallWatchdog.FRAME_DEADLINE_MS + 1_000L,
        sinceLastEncodedMs = 1_000L,
        swapPending = true
      )
    )
    val stall = verdict as? ExportStallWatchdog.Verdict.Stall ?: error("expected a stall, got $verdict")
    assertEquals(ExportStallWatchdog.Kind.FRAME_TIMEOUT, stall.kind)
    assertTrue("the cause must be visible: ${stall.message}", stall.message.contains("eglSwapBuffers"))
  }

  @Test
  fun aRenderLoopStuckInsideAframeIsReportedWithThatCause() {
    val verdict = ExportStallWatchdog.verdict(
      heartbeat(
        framesSubmitted = 120L,
        framesEncoded = 120L,
        sinceLastFrameSubmittedMs = ExportStallWatchdog.FRAME_DEADLINE_MS + 1_000L,
        sinceLastEncodedMs = 1_000L,
        swapPending = false
      )
    )
    val stall = verdict as? ExportStallWatchdog.Verdict.Stall ?: error("expected a stall, got $verdict")
    assertTrue("the cause must be visible: ${stall.message}", stall.message.contains("decode/composition"))
  }

  @Test
  fun anEncoderThatIsBehindButStillMovingIsNotAStall() {
    // Encoder output lags submitted frames by design (the drain thread writes samples as they come);
    // a lag is only a stall when the encoder has produced *nothing* for a minute.
    val verdict = ExportStallWatchdog.verdict(
      heartbeat(
        framesSubmitted = 1_000L,
        framesEncoded = 400L,
        sinceLastFrameSubmittedMs = 500L,
        sinceLastEncodedMs = ExportStallWatchdog.ENCODER_IDLE_DEADLINE_MS / 2
      )
    )
    assertTrue(verdict is ExportStallWatchdog.Verdict.Continue)
  }

  // ------------------------------------------------------------------ audio backpressure (mix/encode overlap)

  @Test
  fun awaitingAudioMixKeepsRenderingAliveWhileTheBackgroundAudioProgresses() {
    // CapCut-style overlap: the muxer waits for the background audio track, so the drain
    // intentionally throttles the encoder. The frame clock freezes, but the background audio
    // path is progressing - this is liveness, not a stall (it would be FRAME_TIMEOUT and
    // ENCODER_STALLED without the flag).
    val verdict = ExportStallWatchdog.verdict(
      heartbeat(
        framesSubmitted = 500L,
        framesEncoded = 480L,
        sinceLastFrameSubmittedMs = 90_000L,
        sinceLastEncodedMs = 90_000L,
        awaitingAudioMix = true,
        sinceAudioPipelineProgressMs = 5_000L
      )
    )
    assertTrue("an intentionally throttled, progressing export must continue, was $verdict", verdict is ExportStallWatchdog.Verdict.Continue)
  }

  @Test
  fun awaitingAudioMixStillFailsWhenTheBackgroundAudioStopsProgressing() {
    val verdict = ExportStallWatchdog.verdict(
      heartbeat(
        framesSubmitted = 500L,
        framesEncoded = 480L,
        sinceLastFrameSubmittedMs = 90_000L,
        sinceLastEncodedMs = 90_000L,
        awaitingAudioMix = true,
        sinceAudioPipelineProgressMs = 61_000L
      )
    )
    val stall = verdict as? ExportStallWatchdog.Verdict.Stall ?: error("expected a stall, got $verdict")
    assertEquals(ExportStallWatchdog.Kind.PHASE_NO_PROGRESS, stall.kind)
    assertTrue(
      "the message must name the background audio path: ${stall.message}",
      stall.message.contains("background audio mix stopped making progress")
    )
  }

  @Test
  fun awaitingAudioMixAlsoCoversTheNoFirstFrameWindow() {
    // A slow mix can hold the muxer before frame 0 ever reaches the encoder; that window is
    // judged by the audio path's progress too, not by the render clock.
    val verdict = ExportStallWatchdog.verdict(
      heartbeat(
        framesSubmitted = 0L,
        framesEncoded = 0L,
        awaitingAudioMix = true,
        sinceAudioPipelineProgressMs = 2_000L
      )
    )
    assertTrue(verdict is ExportStallWatchdog.Verdict.Continue)
  }

  // ------------------------------------------------------------------ shape of the contract

  @Test
  fun everyPhaseHasABudgetAndOnlyMeasurableStagesUseNoProgressLimits() {
    for (phase in ExportPhase.entries) {
      assertTrue("${phase.name} has no budget", ExportStallWatchdog.budgetFor(phase).hardMs > 0L)
    }
    assertTrue(ExportStallWatchdog.budgetFor(ExportPhase.MIXING_AUDIO).noProgressMs > 0L)
    // A stage that reports no internal progress must not pretend to (0 = only the hard budget).
    assertEquals(0L, ExportStallWatchdog.budgetFor(ExportPhase.OPENING_ENCODER).noProgressMs)
    assertEquals(0L, ExportStallWatchdog.budgetFor(ExportPhase.FINALISING).noProgressMs)
  }

  @Test
  fun stallMessagesAlwaysSayTheExportStoppedAndThatNoFileWasWritten() {
    val verdict = ExportStallWatchdog.verdict(
      heartbeat(phase = ExportPhase.OPENING_ENCODER, phaseElapsedMs = 10 * 60_000L)
    ) as ExportStallWatchdog.Verdict.Stall
    assertTrue(verdict.message.startsWith("Export stopped:"))
    assertTrue(verdict.message.contains("No partial file was written"))
    assertTrue(
      ExportStallWatchdog.verdict(
        heartbeat(phaseElapsedMs = 20 * 60_000L)
      ).let { it is ExportStallWatchdog.Verdict.Stall && (it as ExportStallWatchdog.Verdict.Stall).message.contains("project is untouched") }
    )
  }

  @Test
  fun durationsAreRenderedTheWayALogLineReadsThem() {
    assertEquals("0s", ExportStallWatchdog.seconds(0L))
    assertEquals("12s", ExportStallWatchdog.seconds(12_400L))
    assertEquals("1min", ExportStallWatchdog.seconds(60_000L))
    assertEquals("1min 30s", ExportStallWatchdog.seconds(90_000L))
    assertEquals("20min", ExportStallWatchdog.seconds(20 * 60_000L))
    assertEquals("3min 20s", ExportStallWatchdog.seconds(200_000L))
    assertEquals("0s", ExportStallWatchdog.seconds(-5L))
  }

  @Test
  fun nothingHasHappenedYetIsNotImmediatelyAVerdict() {
    // The state the export starts in: no phase progress, no frames, no encoder output. The verdict
    // must be "keep waiting" for every stage until its own budget is up.
    for (phase in ExportPhase.entries) {
      val verdict = ExportStallWatchdog.verdict(
        heartbeat(phase = phase, attemptElapsedMs = 1_000L, phaseElapsedMs = 1_000L, phaseProgressAgoMs = 1_000L)
      )
      assertTrue("${phase.name} must not fail after one second, got $verdict", verdict is ExportStallWatchdog.Verdict.Continue)
    }
  }
}
