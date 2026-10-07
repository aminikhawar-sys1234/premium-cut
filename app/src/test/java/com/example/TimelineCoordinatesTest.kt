package com.example

import com.example.ui.components.timeline.TimelineCoordinates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for the single time <-> pixel converter used by the timeline CTI sync. */
class TimelineCoordinatesTest {

  private val densities = floatArrayOf(1f, 1.5f, 2f, 2.625f, 2.75f, 3f, 3.5f)
  private val zooms = floatArrayOf(0.25f, 0.5f, 1f, 2f, 4f, 8f)

  @Test
  fun msPerDp_isClampedAndMonotonic() {
    assertEquals(TimelineCoordinates.MAX_MS_PER_DP, TimelineCoordinates.msPerDp(0.001f), 0f)
    assertEquals(TimelineCoordinates.MIN_MS_PER_DP, TimelineCoordinates.msPerDp(1000f), 0f)
    assertEquals(20f, TimelineCoordinates.msPerDp(1f), 0f)
    assertTrue(TimelineCoordinates.msPerDp(2f) < TimelineCoordinates.msPerDp(1f))
  }

  @Test(expected = IllegalArgumentException::class)
  fun msPerDp_rejectsZeroZoom() {
    TimelineCoordinates.msPerDp(0f)
  }

  @Test
  fun roundTrip_isStableToHalfAPixelOfTime() {
    val maxMs = 3_600_000L
    for (density in densities) for (zoom in zooms) {
      val scale = TimelineCoordinates.msPerDp(zoom)
      val halfPixelMs = scale / density / 2.0 + 1.0 // +1ms for rounding to whole ms
      var t = 0L
      while (t <= 600_000L) {
        val px = TimelineCoordinates.timeToScrollPx(t, scale, density)
        val back = TimelineCoordinates.scrollPxToTime(px, scale, density, maxMs)
        assertTrue(
          "t=$t density=$density zoom=$zoom px=$px back=$back",
          kotlin.math.abs(back - t) <= halfPixelMs
        )
        t += 137L
      }
    }
  }

  @Test
  fun roundTrip_doesNotAccumulateDrift() {
    // Feeding the converted time back in repeatedly (playback -> scroll -> time -> scroll ...) must be a fixed point.
    val maxMs = 3_600_000L
    for (density in densities) for (zoom in zooms) {
      val scale = TimelineCoordinates.msPerDp(zoom)
      var t = 123_457L
      val first = TimelineCoordinates.scrollPxToTime(
        TimelineCoordinates.timeToScrollPx(t, scale, density), scale, density, maxMs
      )
      t = first
      repeat(1000) {
        t = TimelineCoordinates.scrollPxToTime(
          TimelineCoordinates.timeToScrollPx(t, scale, density), scale, density, maxMs
        )
      }
      assertEquals("drifted at density=$density zoom=$zoom", first, t)
    }
  }

  @Test
  fun zoomChange_doesNotChangeTimeAtPlayhead() {
    // Zoom is a view-only change: the time under the playhead is re-derived from the same time value.
    val density = 2.75f
    val playheadMs = 48_000L
    for (zoom in zooms) {
      val scale = TimelineCoordinates.msPerDp(zoom)
      val px = TimelineCoordinates.timeToScrollPx(playheadMs, scale, density)
      val back = TimelineCoordinates.scrollPxToTime(px, scale, density, 3_600_000L)
      assertTrue("zoom=$zoom back=$back", kotlin.math.abs(back - playheadMs) <= scale / density / 2.0 + 1.0)
    }
  }

  @Test
  fun results_areClamped() {
    assertEquals(0, TimelineCoordinates.timeToScrollPx(-500L, 20f, 2f))
    assertEquals(0L, TimelineCoordinates.scrollPxToTime(-10, 20f, 2f, 10_000L))
    assertEquals(10_000L, TimelineCoordinates.scrollPxToTime(1_000_000, 20f, 2f, 10_000L))
  }

  @Test
  fun largeTimelines_doNotLoseFloatPrecision() {
    // 3 hours at the finest zoom: Float math would be off by whole pixels here.
    val scale = TimelineCoordinates.MIN_MS_PER_DP
    val density = 3f
    val t = 3L * 3600L * 1000L
    val px = TimelineCoordinates.timeToScrollPx(t, scale, density)
    assertEquals((t.toDouble() / scale * density).let { Math.round(it) }.toInt(), px)
  }

  @Test
  fun durationToDp_matchesScale() {
    assertEquals(50f, TimelineCoordinates.durationToDp(1000L, 20f), 1e-4f)
    assertEquals(0f, TimelineCoordinates.durationToDp(-5L, 20f), 0f)
  }
}
