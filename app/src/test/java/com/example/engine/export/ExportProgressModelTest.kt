package com.example.engine.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reported panel showed `5 %` / `encoded 0 / 18484 frames (0% · 0.0 fps)` / `Remaining:
 * Finalizing...` for twenty minutes. The number it showed came from one formula that only knew about
 * frames, so *every* stage before the first frame rendered as 5 % - "opening the encoder", "the PCM
 * mix of a ten minute project", "warming tracked effects" and "rendering the first frame" were all
 * literally the same reading.
 *
 * These tests pin both halves of the fix:
 *
 * * the frame formula the previous release used is **unchanged** (`0.05 + frames/total * 0.90`,
 *   floor 0.05, ceiling 0.905) - existing users' numbers must not shift, and
 *   `ExportPerformanceFixTest` / `RenderExportPipelineAuditTest` already assert it;
 * * every stage before the first frame now has a place in the bar of its own, the bar moves while
 *   those stages run (a mix that has done 60 of 120 chunks is halfway through *its* slice), and the
 *   value it produces never walks backwards as the export advances.
 */
class ExportProgressModelTest {

  // ------------------------------------------------------------------ the frame formula (unchanged)

  @Test
  fun zeroFramesStillReportsTheFivePercentFloor() {
    // The exact reading of the reported screenshot: 0 of 18484 frames, ring at 5 %.
    assertEquals(0.05f, ExportProgressModel.encodingFraction(0L, 18_484L), 1e-6f)
    // ...and the same for a timeline whose frame count is not known yet.
    assertEquals(0.05f, ExportProgressModel.encodingFraction(0L, 0L), 1e-6f)
    assertEquals(0.05f, ExportProgressModel.encodingFraction(7L, -1L), 1e-6f)
  }

  @Test
  fun frameFormulaIsTheOneThePreviousReleaseUsed() {
    val expectedMax = 0.05f + 0.95f * 0.90f
    assertEquals(expectedMax, ExportProgressModel.encodingFraction(18_484L, 18_484L), 1e-6f)
    assertEquals(0.05f + (9_242f / 18_484f) * 0.90f, ExportProgressModel.encodingFraction(9_242L, 18_484L), 1e-6f)
    // More frames than expected must not push the ring into the verification range.
    assertEquals(expectedMax, ExportProgressModel.encodingFraction(100_000L, 18_484L), 1e-6f)
    // The legacy entry point delegates, so both names agree for every caller.
    for (done in longArrayOf(0L, 1L, 999L, 18_483L, 18_484L)) {
      assertEquals(
        ExportProgressModel.encodingFraction(done, 18_484L),
        ExportProgressMapping.encodingFraction(done, 18_484L),
        0f
      )
    }
  }

  // ------------------------------------------------------------------ the pre-roll (the fix)

  @Test
  fun everyStageBeforeTheFirstFrameIsAPreRollStage() {
    val preRoll = listOf(
      ExportPhase.DEVICE_CHECK,
      ExportPhase.PREPARING,
      ExportPhase.LOADING_ASSETS,
      ExportPhase.OPENING_ENCODER,
      ExportPhase.INITIALISING_GPU,
      ExportPhase.MIXING_AUDIO,
      ExportPhase.OPENING_AUDIO_ENCODER,
      ExportPhase.OPENING_MUXER,
      ExportPhase.WARMING_TRACKERS
    )
    assertEquals(preRoll.toSet(), ExportPhase.entries.filter { it.isPreRoll }.toSet())
    assertTrue(!ExportPhase.RENDERING.isPreRoll)
    assertTrue(!ExportPhase.FINALISING.isPreRoll)
    assertTrue(!ExportPhase.VERIFYING.isPreRoll)
    assertTrue(ExportPhase.RENDERING.isFrameStage)
    assertTrue(!ExportPhase.MIXING_AUDIO.isFrameStage)
    // Every stage carries a name for the panel and a sentence for the log/error message.
    ExportPhase.entries.forEach { phase ->
      assertTrue("${phase.name} has no label", phase.label.isNotBlank())
      assertTrue("${phase.name} has no detail", phase.detail.isNotBlank())
    }
  }

  @Test
  fun preRollFractionsStayInsideTheirOwnRangeAndNeverDecreaseBetweenStages() {
    var previousEnd = 0f
    for (phase in ExportPhase.entries.filter { it.isPreRoll }) {
      val start = ExportProgressModel.preRollFraction(phase, 0f)
      val end = ExportProgressModel.preRollFraction(phase, 1f)
      assertTrue("${phase.name} start must be >= the previous stage's end", start >= previousEnd - 1e-6f)
      assertTrue("${phase.name} must advance inside its slice", end > start)
      assertTrue("${phase.name} must stay above the floor", start >= ExportProgressModel.FLOOR_FRACTION - 1e-6f)
      assertTrue("${phase.name} must stay under the ceiling", end <= ExportProgressModel.PRE_ROLL_CEILING + 1e-6f)
      previousEnd = end
    }
    // The last pre-roll stage reaches the ceiling exactly: the frame count owns everything above it.
    assertEquals(
      ExportProgressModel.PRE_ROLL_CEILING,
      ExportProgressModel.preRollFraction(ExportPhase.WARMING_TRACKERS, 1f),
      1e-6f
    )
  }

  @Test
  fun aLongMixMovesTheRingWhileItRuns() {
    // The reported export never moved during the mix. Half the chunks of a ten minute project must be
    // visibly past the start of the mix's own slice *and* well below the frame range.
    val start = ExportProgressModel.preRollFraction(ExportPhase.MIXING_AUDIO, 0f)
    val half = ExportProgressModel.preRollFraction(ExportPhase.MIXING_AUDIO, 0.5f)
    val done = ExportProgressModel.preRollFraction(ExportPhase.MIXING_AUDIO, 1f)
    assertTrue("the mix must start above the encoder phase", start > ExportProgressModel.preRollFraction(ExportPhase.INITIALISING_GPU, 1f) - 1e-6f)
    assertTrue("half a mix must be visible", half > start + 0.001f)
    assertTrue("the mix must not reach the frame range", done <= ExportProgressModel.PRE_ROLL_CEILING + 1e-6f)
    // Non-pre-roll phases report the ceiling, never something inside the mix's slice.
    assertEquals(
      ExportProgressModel.PRE_ROLL_CEILING,
      ExportProgressModel.preRollFraction(ExportPhase.RENDERING, 0.5f),
      1e-6f
    )
  }

  @Test
  fun overallNeverWalksBackwardsAlongARealExport() {
    // A plausible visit order of the stages with plausible progress, including the case the report
    // is about: rendering that has produced no frame yet.
    fun walk(phase: ExportPhase, fraction: Float, done: Long, total: Long) =
      ExportProgressModel.overall(phase, fraction, done, total)

    val samples = mutableListOf<Float>()
    samples += walk(ExportPhase.DEVICE_CHECK, 0f, 0L, 18_484L)
    samples += walk(ExportPhase.PREPARING, 1f, 0L, 18_484L)
    samples += walk(ExportPhase.LOADING_ASSETS, 0.5f, 0L, 18_484L)
    samples += walk(ExportPhase.OPENING_ENCODER, 0f, 0L, 18_484L)
    samples += walk(ExportPhase.INITIALISING_GPU, 0f, 0L, 18_484L)
    samples += walk(ExportPhase.MIXING_AUDIO, 0f, 0L, 18_484L)
    samples += walk(ExportPhase.MIXING_AUDIO, 0.75f, 0L, 18_484L)
    samples += walk(ExportPhase.OPENING_AUDIO_ENCODER, 0f, 0L, 18_484L)
    samples += walk(ExportPhase.OPENING_MUXER, 0f, 0L, 18_484L)
    samples += walk(ExportPhase.WARMING_TRACKERS, 0f, 0L, 18_484L)
    samples += walk(ExportPhase.RENDERING, 0f, 0L, 18_484L)
    samples += walk(ExportPhase.RENDERING, 0f, 1L, 18_484L)
    samples += walk(ExportPhase.RENDERING, 0f, 9_242L, 18_484L)
    samples += walk(ExportPhase.RENDERING, 0f, 18_484L, 18_484L)
    samples += walk(ExportPhase.FINALISING, 0f, 18_484L, 18_484L)
    samples += walk(ExportPhase.VERIFYING, 0f, 18_484L, 18_484L)

    for (index in 1 until samples.size) {
      assertTrue(
        "the ring went backwards at step $index: ${samples[index - 1]} -> ${samples[index]}",
        samples[index] >= samples[index - 1] - 1e-6f
      )
    }
    // Rendering with zero frames is exactly the pre-roll ceiling, i.e. the frame formula's floor.
    assertEquals(
      ExportProgressModel.PRE_ROLL_CEILING,
      samples[10],
      1e-6f
    )
  }

  @Test
  fun finalisingAndVerifyingSitAboveTheHighestFrameValue() {
    val highestFrameValue = ExportProgressModel.encodingFraction(18_484L, 18_484L)
    assertTrue(ExportProgressModel.FINALISING_FRACTION > highestFrameValue)
    assertTrue(ExportProgressModel.overall(ExportPhase.VERIFYING) > ExportProgressModel.FINALISING_FRACTION)
    assertTrue(ExportProgressModel.overall(ExportPhase.VERIFYING) < 1f)
    assertEquals(
      ExportProgressModel.FINALISING_FRACTION,
      ExportProgressModel.overall(ExportPhase.FINALISING),
      1e-6f
    )
  }

  // ------------------------------------------------------------------ what the panel says

  @Test
  fun theReportedStatusLineIsUnchangedWhileFramesAreRendering() {
    val message = ExportProgressModel.message(
      phase = ExportPhase.RENDERING,
      phaseFraction = 0f,
      doneFrames = 0L,
      totalFrames = 18_484L,
      encodeFps = 0f
    )
    assertEquals("Hardware GPU pipeline: encoded 0 / 18484 frames (0% · 0.0 fps)", message)

    val half = ExportProgressModel.message(
      phase = ExportPhase.RENDERING,
      phaseFraction = 0f,
      doneFrames = 9_242L,
      totalFrames = 18_484L,
      encodeFps = 3.25f
    )
    // %.1f rounds HALF_UP, so 3.25 prints as "3.3".
    assertEquals("Hardware GPU pipeline: encoded 9242 / 18484 frames (50% · 3.3 fps)", half)
  }

  @Test
  fun theFrameRateIsFormattedIndependentlyOfThePhoneLocale() {
    // The panel was read off a physical phone, and that phone is not guaranteed to run an en-US
    // locale. Under the default locale the same line came out as "(50% · 3,3 fps)" (de-DE) or with
    // non-Latin digits (ar-EG), which makes a screen recording of a stalled export unreadable. The
    // decimal separator and the digits are now pinned to Locale.US.
    val previous = java.util.Locale.getDefault()
    try {
      java.util.Locale.setDefault(java.util.Locale.forLanguageTag("de-DE"))
      val message = ExportProgressModel.message(
        phase = ExportPhase.RENDERING,
        phaseFraction = 0f,
        doneFrames = 9_242L,
        totalFrames = 18_484L,
        encodeFps = 3.25f
      )
      assertEquals("Hardware GPU pipeline: encoded 9242 / 18484 frames (50% · 3.3 fps)", message)
    } finally {
      java.util.Locale.setDefault(previous)
    }
  }

  @Test
  fun aStuckPreRollStageIsNamedInsteadOfSayingFinalizing() {
    // The panel used to print "Finalizing..." for everything it could not explain - including a mix
    // that had not started. Each pre-roll stage now says what it is doing.
    assertEquals("Opening the video encoder...", ExportProgressModel.message(ExportPhase.OPENING_ENCODER, 0f, 0L, 100L))
    assertEquals("Mixing audio (37%)", ExportProgressModel.message(ExportPhase.MIXING_AUDIO, 0.37f, 0L, 100L))
    assertEquals("Mixing audio...", ExportProgressModel.message(ExportPhase.MIXING_AUDIO, 0f, 0L, 100L))
    assertTrue(ExportProgressModel.message(ExportPhase.FINALISING, 0f, 100L, 100L).contains("MP4"))
    assertTrue(ExportProgressModel.message(ExportPhase.VERIFYING, 0f, 100L, 100L).contains("Verif"))
  }

  @Test
  fun everyPhaseProducesANonEmptyStatusLine() {
    for (phase in ExportPhase.entries) {
      val message = ExportProgressModel.message(phase, 0.5f, 10L, 100L, 1f)
      assertTrue("${phase.name} produced an empty status", message.isNotBlank())
    }
  }
}
