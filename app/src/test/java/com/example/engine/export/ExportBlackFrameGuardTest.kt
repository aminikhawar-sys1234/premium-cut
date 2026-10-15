package com.example.engine.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The black-frame policy of the export pipeline. A black export is invisible to every container
 * statistic, so these decisions - when to abandon an attempt, and how often to retry it - are the
 * only thing standing between a GPU failure and a rejected export.
 */
class ExportBlackFrameGuardTest {

  @Test fun `probes cover the first frames and every quarter of the clip`() {
    val probes = ExportBlackFrameGuard.probeFrames(100L)
    assertTrue("early frames must be probed so a black pipeline is caught quickly", probes.containsAll(listOf(0L, 1L, 2L, 3L)))
    assertTrue("the quarters keep the probes spread over the clip", probes.containsAll(listOf(25L, 50L, 75L, 99L)))
    assertEquals("early frames plus the four quarters", 8, probes.size)
  }

  @Test fun `probe frames stay inside the clip for any length`() {
    for (total in 0L..40L) {
      for (frame in ExportBlackFrameGuard.probeFrames(total)) {
        assertTrue("frame $frame of $total", frame in 0 until maxOf(1L, total))
      }
    }
    assertTrue(ExportBlackFrameGuard.probeFrames(0L).isEmpty())
    assertEquals(setOf(0L), ExportBlackFrameGuard.probeFrames(1L))
  }

  @Test fun `an attempt is abandoned only when every readable probe is black`() {
    // Six readable black probes spanning half the clip: black output.
    assertTrue(ExportBlackFrameGuard.shouldAbortAttempt(blackProbes = 6, readableProbes = 6, lastProbeFrame = 50L, totalFrames = 100L))
    // One non-black probe anywhere proves the pipeline can draw.
    assertFalse(ExportBlackFrameGuard.shouldAbortAttempt(blackProbes = 5, readableProbes = 6, lastProbeFrame = 50L, totalFrames = 100L))
    // Too few probes to conclude anything.
    assertFalse(ExportBlackFrameGuard.shouldAbortAttempt(blackProbes = 3, readableProbes = 3, lastProbeFrame = 2L, totalFrames = 100L))
  }

  @Test fun `a dark intro or fade-in does not abort the attempt`() {
    // The first frames are black but the probes have not reached half of the clip yet.
    assertFalse(ExportBlackFrameGuard.shouldAbortAttempt(blackProbes = 5, readableProbes = 5, lastProbeFrame = 25L, totalFrames = 100L))
    // ... and once a later probe is readable and not black, the attempt continues.
    assertFalse(ExportBlackFrameGuard.shouldAbortAttempt(blackProbes = 5, readableProbes = 6, lastProbeFrame = 50L, totalFrames = 100L))
  }

  @Test fun `unavailable readbacks never count as black`() {
    // glReadPixels refused the readback on every probe: nothing is known, so nothing is aborted.
    assertFalse(ExportBlackFrameGuard.shouldAbortAttempt(blackProbes = 0, readableProbes = 0, lastProbeFrame = 99L, totalFrames = 100L))
  }

  @Test fun `a black output is retried once and then reported`() {
    assertTrue(ExportBlackFrameGuard.mayRetryBlackOutput(1))
    assertFalse(ExportBlackFrameGuard.mayRetryBlackOutput(ExportBlackFrameGuard.MAX_BLACK_FRAME_ATTEMPTS))
    assertEquals(2, ExportBlackFrameGuard.MAX_BLACK_FRAME_ATTEMPTS)
  }

  @Test fun `only the first attempt uses the native compositor`() {
    assertTrue(ExportBlackFrameGuard.useNativeCompositor(1))
    assertFalse(ExportBlackFrameGuard.useNativeCompositor(2))
    assertFalse(ExportBlackFrameGuard.useNativeCompositor(3))
  }
}
