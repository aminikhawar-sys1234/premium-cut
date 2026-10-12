package com.example.engine.timeline.edit

import com.example.domain.model.AudioClip
import com.example.domain.model.TextClip
import com.example.domain.model.Timeline
import com.example.domain.model.VideoClip
import com.example.engine.timeline.clipView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineClipEditsTest {

  private fun sample(): Timeline = Timeline(
    videoClips = listOf(
      VideoClip(id = "v1", name = "V1", uri = "v1", timelineStartMs = 0L, durationMs = 2000L, sourceStartMs = 0L, sourceEndMs = 2000L, sourceTotalDurationMs = 10_000L),
      VideoClip(id = "v2", name = "V2", uri = "v2", timelineStartMs = 2000L, durationMs = 2000L, sourceStartMs = 0L, sourceEndMs = 2000L, sourceTotalDurationMs = 10_000L)
    ),
    overlayClips = listOf(
      VideoClip(id = "o1", name = "O1", uri = "o1", timelineStartMs = 500L, durationMs = 1500L, trackIndex = 1)
    ),
    audioClips = listOf(
      AudioClip(id = "a1", title = "A1", uri = "a1", timelineStartMs = 0L, durationMs = 4000L)
    ),
    textClips = listOf(
      TextClip(id = "t1", text = "Hi", timelineStartMs = 200L, durationMs = 800L)
    )
  )

  @Test
  fun trimRightShortensClipWithoutMovingStart() {
    val next = TimelineClipEdits.trimRight(sample(), "v2", 1000L)
    assertNotNull(next)
    val clip = next!!.videoClips.first { it.id == "v2" }
    assertEquals(2000L, clip.timelineStartMs)
    assertEquals(1000L, clip.durationMs)
  }

  @Test
  fun trimLeftOnFirstMainClipPinsZeroPoint() {
    val next = TimelineClipEdits.trimLeft(sample(), "v1", 400L)
    assertNotNull(next)
    val clip = next!!.videoClips.first { it.id == "v1" }
    assertEquals(0L, clip.timelineStartMs)
    assertTrue(clip.durationMs < 2000L)
  }

  @Test
  fun moveClipNeverGoesBeforeZero() {
    val next = TimelineClipEdits.moveClip(sample(), "o1", -200L)
    assertNotNull(next)
    assertEquals(0L, next!!.overlayClips.first { it.id == "o1" }.timelineStartMs)
  }

  @Test
  fun shiftClipsKeepsRelativeSpacing() {
    val next = TimelineClipEdits.shiftClips(sample(), setOf("o1", "t1"), 300L)
    assertNotNull(next)
    assertEquals(800L, next!!.overlayClips.first { it.id == "o1" }.timelineStartMs)
    assertEquals(500L, next.textClips.first { it.id == "t1" }.timelineStartMs)
  }

  @Test
  fun normalDeleteLeavesAGapOnTheMainTrack() {
    val next = TimelineClipEdits.normalDelete(sample(), setOf("v1"))
    assertNotNull(next)
    assertEquals(1, next!!.videoClips.size)
    assertEquals("v2", next.videoClips.single().id)
    assertEquals(2000L, next.videoClips.single().timelineStartMs)
  }

  @Test
  fun rippleDeleteClosesTheGapOnTheSameLane() {
    val next = TimelineClipEdits.rippleDelete(sample(), setOf("v1"))
    assertNotNull(next)
    assertEquals(1, next!!.videoClips.size)
    assertEquals(0L, next.videoClips.single().timelineStartMs)
  }

  @Test
  fun rippleDownstreamShiftsLaterClipsTogether() {
    val next = TimelineClipEdits.rippleDownstream(sample(), 1500L, 250L)
    assertEquals(2250L, next.videoClips.first { it.id == "v2" }.timelineStartMs)
    assertEquals(0L, next.videoClips.first { it.id == "v1" }.timelineStartMs)
    assertEquals(500L, next.overlayClips.single().timelineStartMs)
  }

  @Test
  fun slipClipMovesSourceWindowNotTimelineStart() {
    val next = TimelineClipEdits.slipClip(sample(), "v2", 200L)
    assertNotNull(next)
    val clip = next!!.videoClips.first { it.id == "v2" }
    assertEquals(2000L, clip.timelineStartMs)
    assertEquals(200L, clip.sourceStartMs)
    assertEquals(2200L, clip.sourceEndMs)
  }

  @Test
  fun lockedClipIsRejected() {
    val locked = sample().copy(
      videoClips = sample().videoClips.map { if (it.id == "v2") it.copy(isLocked = true) else it }
    )
    assertNull(TimelineClipEdits.moveClip(locked, "v2", 3000L))
    assertNull(locked.clipView("missing"))
  }
}
