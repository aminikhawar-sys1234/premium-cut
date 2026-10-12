package com.example.engine.timeline

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineFrameMathTest {

  @Test
  fun thirtyFpsRoundTripsWholeFrames() {
    assertEquals(0L, TimelineFrameMath.timeToFrame(0L, 30))
    assertEquals(15L, TimelineFrameMath.timeToFrame(500L, 30))
    assertEquals(30L, TimelineFrameMath.timeToFrame(1000L, 30))
    assertEquals(500L, TimelineFrameMath.frameToTime(15L, 30))
    assertEquals(1000L, TimelineFrameMath.frameToTime(30L, 30))
  }

  @Test
  fun sixtyFpsAndMicroseconds() {
    assertEquals(60L, TimelineFrameMath.timeToFrame(1000L, 60))
    assertEquals(1000L, TimelineFrameMath.frameToTime(60L, 60))
    assertEquals(1_000_000L, TimelineFrameMath.timeMsToMicros(1000L))
    assertEquals(1000L, TimelineFrameMath.microsToTimeMs(1_000_000L))
    assertEquals(30L, TimelineFrameMath.microsToFrame(1_000_000L, 30))
    assertEquals(1_000_000L, TimelineFrameMath.frameToMicros(30L, 30))
  }

  @Test
  fun alignToFrameSnapsToNearestFrameCenter() {
    assertEquals(0L, TimelineFrameMath.alignToFrame(16L, 30))
    assertEquals(33L, TimelineFrameMath.alignToFrame(33L, 30))
  }

  @Test
  fun formatTimecodeIncludesFrames() {
    assertEquals("00:00:00:00", TimelineFrameMath.formatTimecode(0L, 30))
    assertEquals("00:00:01:00", TimelineFrameMath.formatTimecode(1000L, 30))
    assertEquals("01:00:00:00", TimelineFrameMath.formatTimecode(3_600_000L, 30))
  }

  @Test
  fun fpsIsNeverZero() {
    assertEquals(1000L, TimelineFrameMath.frameDurationMs(0))
    assertEquals(1_000_000L, TimelineFrameMath.frameDurationUs(0))
  }
}
