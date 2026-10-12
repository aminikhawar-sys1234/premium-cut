package com.example.engine.timeline

import com.example.domain.model.AudioClip
import com.example.domain.model.Timeline
import com.example.domain.model.VideoClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimelineSourceEditsTest {

  private fun video(
    id: String, start: Long, dur: Long, srcStart: Long = 0, srcEnd: Long = srcStart + dur,
    speed: Float = 1f, total: Long = 0
  ) = VideoClip(
    id = id, name = id, timelineStartMs = start, durationMs = dur, sourceStartMs = srcStart,
    sourceEndMs = srcEnd, speed = speed, sourceTotalDurationMs = total
  )

  @Test
  fun clampSourceRangeKeepsMinimumSpanInsideMedia() {
    val r = TimelineSourceEdits.clampSourceRange(9_950, 9_960, maxSourceMs = 10_000, speed = 1f)
    assertEquals(9_900L, r.sourceStartMs)
    assertEquals(10_000L, r.sourceEndMs)
    assertEquals(100L, r.durationMs)
  }

  @Test
  fun clampSourceRangeScalesDurationBySpeed() {
    val r = TimelineSourceEdits.clampSourceRange(-500, 4_000, maxSourceMs = 10_000, speed = 2f)
    assertEquals(0L, r.sourceStartMs)
    assertEquals(4_000L, r.sourceEndMs)
    assertEquals(2_000L, r.durationMs)
  }

  @Test
  fun slipMovesSourceWindowAndKeepsItsLength() {
    val clips = listOf(video("v", 0, 2000, srcStart = 1000, speed = 1.5f))
    val r = TimelineSourceEdits.slipVideo(clips, "v", 400)!!.single()
    assertEquals(1600L, r.sourceStartMs)
    assertEquals(1600L + 3000L, r.sourceEndMs)
    assertEquals(0L, r.timelineStartMs)
    assertEquals(2000L, r.durationMs)
  }

  @Test
  fun slipNeverMovesBeforeSourceStart() {
    val clips = listOf(AudioClip(id = "a", uri = "a", title = "a", durationMs = 1000, sourceStartMs = 200, sourceEndMs = 1200))
    val r = TimelineSourceEdits.slipAudio(clips, "a", -5000)!!.single()
    assertEquals(0L, r.sourceStartMs)
    assertEquals(1000L, r.sourceEndMs)
    assertNull(TimelineSourceEdits.slipAudio(clips, "missing", 10))
  }

  @Test
  fun slideTradesDurationBetweenNeighboursAndKeepsTotalLength() {
    val tl = Timeline(videoClips = listOf(video("a", 0, 1000), video("b", 1000, 1000), video("c", 2000, 1000)))
    val r = TimelineSourceEdits.slideMainTrackClip(tl, "b", 300)!!
    val (a, b, c) = r.videoClips
    assertEquals(1300L, a.durationMs)
    assertEquals(1300L, a.sourceEndMs)
    assertEquals(1300L, b.timelineStartMs)
    assertEquals(1000L, b.durationMs)
    assertEquals(2300L, c.timelineStartMs)
    assertEquals(700L, c.durationMs)
    assertEquals(300L, c.sourceStartMs)
    assertEquals(tl.totalDurationMs, r.totalDurationMs)
  }

  @Test
  fun slideIsLimitedByNeighbourMinimumAndRejectsEdgeClips() {
    val tl = Timeline(videoClips = listOf(video("a", 0, 1000), video("b", 1000, 1000), video("c", 2000, 500)))
    val r = TimelineSourceEdits.slideMainTrackClip(tl, "b", 10_000)!!
    assertEquals(TimelineSourceEdits.MIN_SLIDE_NEIGHBOR_MS, r.videoClips[2].durationMs)
    assertNull(TimelineSourceEdits.slideMainTrackClip(tl, "a", 100))
    assertNull(TimelineSourceEdits.slideMainTrackClip(tl, "c", 100))
  }

  @Test
  fun mainTrackSourceRangeRipplesLaterClips() {
    val tl = Timeline(videoClips = listOf(video("a", 0, 2000, total = 5000), video("b", 2000, 1000)))
    val r = TimelineSourceEdits.setMainTrackSourceRange(tl, "a", 500, 1500, rippleContiguous = true)!!
    assertEquals(1000L, r.videoClips[0].durationMs)
    assertEquals(500L, r.videoClips[0].sourceStartMs)
    assertEquals(5000L, r.videoClips[0].sourceTotalDurationMs)
    assertEquals(1000L, r.videoClips[1].timelineStartMs)
  }

  @Test
  fun mainTrackSourceRangeWithoutRippleLeavesNeighboursAlone() {
    val tl = Timeline(videoClips = listOf(video("a", 0, 2000), video("b", 2000, 1000)))
    val r = TimelineSourceEdits.setMainTrackSourceRange(tl, "a", 0, 1000, rippleContiguous = false)!!
    assertEquals(2000L, r.videoClips[1].timelineStartMs)
    assertEquals(2000L, r.videoClips[0].sourceTotalDurationMs)
  }

  @Test
  fun overlaySourceRangeIsBoundedByKnownMediaLength() {
    val tl = Timeline(overlayClips = listOf(video("o", 0, 1000, total = 3000)))
    val r = TimelineSourceEdits.setOverlaySourceRange(tl, "o", 0, 9000)!!.overlayClips.single()
    assertEquals(3000L, r.sourceEndMs)
    assertEquals(3000L, r.durationMs)
  }

  @Test
  fun fullSourceDurationFallsBackToTrimmedEnd() {
    assertEquals(8000L, TimelineSourceEdits.fullSourceDurationMs(video("v", 0, 1000, total = 8000)))
    assertEquals(1500L, TimelineSourceEdits.fullSourceDurationMs(video("v", 0, 1000, srcStart = 500)))
  }
}
