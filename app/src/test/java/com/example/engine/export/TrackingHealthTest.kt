package com.example.engine.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A tracked effect that could not be drawn must be recorded: the export reports what it could not
 * render instead of shipping a video where the effect silently disappeared.
 */
class TrackingHealthTest {

  /** The harness shim has no @After; reset explicitly so each case starts from a clean record. */
  private fun resetRecord() = TrackingHealth.reset()

  @Test
  fun nothingIsReportedWhileEveryLookupSucceeds() {
    resetRecord()
    assertNull(TrackingHealth.summary())
    assertEquals(0L, TrackingHealth.totalFailures())
  }

  @Test
  fun failuresAreCountedPerClipWithTheLastReason() {
    resetRecord()
    TrackingHealth.record("clip-a", 100_000L, "tracking timed out after 20000ms")
    TrackingHealth.record("clip-a", 200_000L, "tracking timed out after 20000ms")
    TrackingHealth.record("clip-b", 50_000L, "no media uri for clip clip-b")

    assertEquals(3L, TrackingHealth.totalFailures())
    assertEquals(2L, TrackingHealth.failureFor("clip-a")?.count)
    assertEquals(200_000L, TrackingHealth.failureFor("clip-a")?.lastSourceUs)
    assertEquals(setOf("clip-a", "clip-b"), TrackingHealth.clipIds())

    val summary = TrackingHealth.summary()!!
    assertTrue(summary, summary.contains("clip-a=2 frame(s)"))
    assertTrue(summary, summary.contains("no media uri"))
  }

  @Test
  fun oneEntryPerClipKeepsALongExportBounded() {
    resetRecord()
    repeat(10_000) { TrackingHealth.record("clip", it.toLong(), "timeout") }
    assertEquals(1, TrackingHealth.clipIds().size)
    assertEquals(10_000L, TrackingHealth.failureFor("clip")?.count)
    assertEquals("clip=10000 frame(s) (last: timeout)", TrackingHealth.summary())
  }

  @Test
  fun resetClearsTheRecord() {
    resetRecord()
    TrackingHealth.record("clip", 0L, "boom")
    TrackingHealth.reset()
    assertNull(TrackingHealth.summary())
  }
}
