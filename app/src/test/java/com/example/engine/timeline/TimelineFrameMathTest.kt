package com.example.engine.timeline

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineFrameMathTest {

  @Test
  fun msToFrameRoundsToNearestFrame() {
    assertEquals(0L, TimelineFrameMath.timeToFrame(16, 30))
    assertEquals(1L, TimelineFrameMath.timeToFrame(17, 30))
    assertEquals(30L, TimelineFrameMath.timeToFrame(1000, 30))
    assertEquals(0L, TimelineFrameMath.timeToFrame(-500, 30))
  }

  @Test
  fun frameToMsRoundsAndNeverGoesNegative() {
    assertEquals(33L, TimelineFrameMath.frameToTime(1, 30))
    assertEquals(1000L, TimelineFrameMath.frameToTime(24, 24))
    assertEquals(42L, TimelineFrameMath.frameToTime(1, 24))
    assertEquals(0L, TimelineFrameMath.frameToTime(-3, 30))
  }

  @Test
  fun alignToFrameSnapsToTheNearestFrameBoundary() {
    assertEquals(33L, TimelineFrameMath.alignToFrame(40, 30))
    assertEquals(67L, TimelineFrameMath.alignToFrame(55, 30))
    assertEquals(1000L, TimelineFrameMath.alignToFrame(1010, 30))
  }

  @Test
  fun microsecondFrameIndexSurvivesRoundTripAtCommonRates() {
    for (fps in intArrayOf(24, 25, 30, 50, 60, 120)) {
      for (frame in 0L..600L) {
        val us = TimelineFrameMath.frameToTimeUs(frame, fps)
        assertEquals("fps=$fps frame=$frame", frame, TimelineFrameMath.timeUsToFrame(us, fps))
      }
    }
  }

  @Test
  fun frameDurationsAreClampedForInvalidRates() {
    assertEquals(33L, TimelineFrameMath.frameDurationMs(30))
    assertEquals(33_333L, TimelineFrameMath.frameDurationUs(30))
    assertEquals(1000L, TimelineFrameMath.frameDurationMs(0))
    assertEquals(1_000_000L, TimelineFrameMath.frameDurationUs(-5))
  }

  @Test
  fun truncatingMicrosConversionsFloor() {
    assertEquals(33_333L, TimelineFrameMath.frameToMicros(1, 30))
    assertEquals(0L, TimelineFrameMath.microsToFrame(33_332, 30))
    assertEquals(1L, TimelineFrameMath.microsToFrame(33_334, 30))
    assertEquals(2_000_000L, TimelineFrameMath.frameToMicros(2, 0))
  }

  @Test
  fun timecodeUnderAnHourIsMinutesSecondsFrames() {
    assertEquals("00:00:00", TimelineFrameMath.formatTimecode(0, 30))
    assertEquals("00:01:15", TimelineFrameMath.formatTimecode(1500, 30))
    assertEquals("02:05:00", TimelineFrameMath.formatTimecode(125_000, 30))
  }

  @Test
  fun timecodeFromAnHourIncludesHours() {
    assertEquals("01:00:00:00", TimelineFrameMath.formatTimecode(3_600_000, 25))
    assertEquals("01:01:01:12", TimelineFrameMath.formatTimecode(3_661_500, 24))
  }
}
