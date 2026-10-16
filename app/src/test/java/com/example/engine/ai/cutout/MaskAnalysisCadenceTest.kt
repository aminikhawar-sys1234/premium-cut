package com.example.engine.ai.cutout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The export's mask cadence, on a plain JVM.
 *
 * This is the number that decides how much of a long export is spent segmenting the subject: the
 * stage used to key masks per 30 ms bucket, which at 30 fps is *every frame*, and the export path
 * asks for masks with `blocking = true` - a 384 px readback plus an on-device segmentation on the GL
 * thread, per frame, per background-removal clip.
 *
 * What is pinned here: the export gets one analysis per 200 ms of timeline, the preview keeps its
 * per-frame cadence, the mapping is monotonic and total, and the saving is the documented ~6x.
 */
class MaskAnalysisCadenceTest {

  private val fps = 30

  /** Presentation time of frame [frame] of a 30 fps timeline, in microseconds. */
  private fun frameUs(frame: Int): Long = frame * 1_000_000L / fps

  @Test
  fun exportAnalysesOncePerTwoHundredMillisecondsOfTimelineNotOncePerFrame() {
    val window = MaskAnalysisCadence.EXPORT_WINDOW_US
    assertEquals("the window is 200ms", 200_000L, window)
    // Frames 0..5 of a 30 fps timeline (0, 33, 67, 100, 133, 167 ms) share the first analysis.
    for (frame in 0 until 6) {
      assertEquals(
        "frame $frame must reuse the mask of its 200ms window",
        0L,
        MaskAnalysisCadence.exportBucketFor(frameUs(frame))
      )
    }
    // Frame 6 lands on 200 ms exactly: a new analysis.
    assertEquals(0L, MaskAnalysisCadence.exportBucketFor(frameUs(6) - 1L))
    assertEquals(1L, MaskAnalysisCadence.exportBucketFor(frameUs(6)))
    assertEquals(1L, MaskAnalysisCadence.exportBucketFor(200_000L))
    assertEquals(1L, MaskAnalysisCadence.exportBucketFor(399_999L))
    assertEquals(2L, MaskAnalysisCadence.exportBucketFor(400_000L))
  }

  @Test
  fun previewKeepsOneBucketPerFrameAndNeverUsesTheExportWindow() {
    assertEquals(0L, MaskAnalysisCadence.previewBucketFor(0L))
    assertEquals(0L, MaskAnalysisCadence.previewBucketFor(29_999L))
    assertEquals(1L, MaskAnalysisCadence.previewBucketFor(30_000L))
    assertEquals(1L, MaskAnalysisCadence.previewBucketFor(frameUs(1)))
    assertTrue(
      "the preview cadence must be finer than the export's",
      MaskAnalysisCadence.FRAME_BUCKET_US < MaskAnalysisCadence.EXPORT_WINDOW_US
    )
  }

  @Test
  fun aTenMinuteExportPaysSixTimesFewerSegmentations() {
    val tenMinutesUs = 10L * 60L * 1_000_000L
    assertEquals(3_000L, MaskAnalysisCadence.exportAnalysesFor(tenMinutesUs, fps))
    assertEquals(18_000L, MaskAnalysisCadence.frameAnalysesFor(tenMinutesUs, fps))
    assertEquals(
      "the export must pay at least 5x fewer analyses than the per-frame cadence did",
      true,
      MaskAnalysisCadence.frameAnalysesFor(tenMinutesUs, fps) >=
        5L * MaskAnalysisCadence.exportAnalysesFor(tenMinutesUs, fps)
    )
  }

  @Test
  fun theMappingIsMonotonicAndNeverCollapsesANegativePositionOntoZero() {
    var previous = Long.MIN_VALUE
    var us = -1_000_000L
    while (us <= 1_000_000L) {
      val bucket = MaskAnalysisCadence.exportBucketFor(us)
      assertTrue("bucket must not walk backwards at ${us}us", bucket >= previous)
      previous = bucket
      us += 33_333L
    }
    // Floor division: a negative key can never be mistaken for "current", because the temporal
    // filter blends within `gapBuckets in 1..4` only.
    assertEquals(-1L, MaskAnalysisCadence.exportBucketFor(-1L))
    assertEquals(-1L, MaskAnalysisCadence.exportBucketFor(-199_999L))
    assertEquals(0L, MaskAnalysisCadence.exportBucketFor(0L))
  }

  @Test
  fun anEmptyTimelineOrABogusWindowIsStillTotal() {
    assertEquals(0L, MaskAnalysisCadence.exportAnalysesFor(0L, fps))
    assertEquals(0L, MaskAnalysisCadence.exportAnalysesFor(-5_000L, fps))
    assertEquals(0L, MaskAnalysisCadence.frameAnalysesFor(10_000_000L, 0))
    // A window of 0 must fall back to the per-frame bucket instead of dividing by zero.
    assertEquals(MaskAnalysisCadence.previewBucketFor(90_000L), MaskAnalysisCadence.bucketFor(90_000L, 0L))
    assertEquals(MaskAnalysisCadence.previewBucketFor(90_000L), MaskAnalysisCadence.bucketFor(90_000L, -1L))
  }

  @Test
  fun oneSecondNeedsFiveAnalysesBecauseTheLastWindowIsNotFull() {
    assertEquals(5L, MaskAnalysisCadence.exportAnalysesFor(1_000_000L, fps))
    assertEquals(1L, MaskAnalysisCadence.exportAnalysesFor(1L, fps))
  }
}
