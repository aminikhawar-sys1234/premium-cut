package com.example.engine.timeline

import com.example.domain.model.AudioClip
import com.example.domain.model.ShapeClip
import com.example.domain.model.Timeline
import com.example.domain.model.TrackType
import com.example.domain.model.VideoClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineOverlapQueriesTest {

  private fun overlay(id: String, lane: Int, start: Long, dur: Long) =
    VideoClip(id = id, name = id, trackIndex = lane, timelineStartMs = start, durationMs = dur, sourceEndMs = dur)

  private val tl = Timeline(
    overlayClips = listOf(
      overlay("a", 1, 0, 1000),
      overlay("b", 1, 500, 1000),
      overlay("c", 1, 800, 100),
      overlay("d", 1, 2000, 500),
      overlay("other", 2, 0, 5000)
    )
  )

  @Test
  fun overlapsAreReportedPerLanePair() {
    val o = TimelineOverlapQueries.trackOverlaps(tl, TrackType.OVERLAY, 1)
    assertEquals(listOf("a" to "b", "a" to "c", "b" to "c"), o.map { it.clipAId to it.clipBId })
    assertEquals(500L, o[0].overlapStartMs)
    assertEquals(1000L, o[0].overlapEndMs)
    assertEquals(100L, o[1].overlapDurationMs)
  }

  @Test
  fun touchingClipsDoNotOverlap() {
    val t = Timeline(overlayClips = listOf(overlay("x", 1, 0, 1000), overlay("y", 1, 1000, 1000)))
    assertTrue(TimelineOverlapQueries.trackOverlaps(t, TrackType.OVERLAY, 1).isEmpty())
  }

  @Test
  fun hasOverlapRespectsLaneAndIgnoredClip() {
    assertTrue(TimelineOverlapQueries.hasOverlap(tl, TrackType.OVERLAY, 1, 1600, 500))
    assertFalse(TimelineOverlapQueries.hasOverlap(tl, TrackType.OVERLAY, 1, 1500, 500))
    assertFalse(TimelineOverlapQueries.hasOverlap(tl, TrackType.OVERLAY, 1, 2100, 100, ignoreClipId = "d"))
    assertFalse(TimelineOverlapQueries.hasOverlap(tl, TrackType.OVERLAY, 3, 0, 10_000))
  }

  @Test
  fun clipStartEndCoversGeneratedLayers() {
    val t = tl.copy(shapeClips = listOf(ShapeClip(id = "s", timelineStartMs = 300, durationMs = 200)))
    assertEquals(500L to 1500L, TimelineOverlapQueries.clipStartEnd(t, "b"))
    assertEquals(300L to 500L, TimelineOverlapQueries.clipStartEnd(t, "s"))
    assertNull(TimelineOverlapQueries.clipStartEnd(t, "missing"))
  }

  @Test
  fun trimmedRangeFlagsTrimmedMedia() {
    val t = Timeline(
      videoClips = listOf(
        VideoClip(id = "full", name = "f", durationMs = 2000, sourceEndMs = 2000, sourceTotalDurationMs = 2000),
        VideoClip(id = "cut", name = "c", durationMs = 1000, sourceStartMs = 0, sourceEndMs = 1000, sourceTotalDurationMs = 2000)
      ),
      audioClips = listOf(AudioClip(id = "a", uri = "a", title = "a", durationMs = 1000, sourceStartMs = 250, sourceEndMs = 1250))
    )
    assertFalse(TimelineOverlapQueries.trimmedRange(t, "full")!!.isTrimmed)
    assertTrue(TimelineOverlapQueries.trimmedRange(t, "cut")!!.isTrimmed)
    val a = TimelineOverlapQueries.trimmedRange(t, "a")!!
    assertTrue(a.isTrimmed)
    assertEquals(TrackType.AUDIO, a.trackType)
    assertEquals(1000L, a.sourceDurationMs)
  }
}
