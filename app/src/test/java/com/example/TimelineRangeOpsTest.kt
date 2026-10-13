package com.example

import com.example.domain.model.AudioClip
import com.example.domain.model.TextClip
import com.example.domain.model.Timeline
import com.example.domain.model.TimelineMarker
import com.example.domain.model.Transition
import com.example.domain.model.TransitionType
import com.example.domain.model.VideoClip
import com.example.engine.timeline.TimelineRangeOps
import com.example.engine.timeline.TimelineRangeOps.TimeRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineRangeOpsTest {

  private val unlocked: (com.example.domain.model.TrackType, Int) -> Boolean = { _, _ -> false }

  private fun video(id: String, start: Long, dur: Long) = VideoClip(
    id = id, uri = "file:///$id.mp4", name = id, timelineStartMs = start, durationMs = dur,
    sourceStartMs = 0L, sourceEndMs = dur
  )

  private fun base() = Timeline(
    videoClips = listOf(video("a", 0, 5000), video("b", 5000, 5000)),
    audioClips = listOf(
      AudioClip(id = "music", uri = "file:///m.mp3", title = "m", timelineStartMs = 0, durationMs = 10_000, sourceEndMs = 10_000)
    ),
    textClips = listOf(TextClip(id = "t", text = "hi", timelineStartMs = 4000, durationMs = 2000)),
    transitions = listOf(Transition(clipIndexBefore = 0, type = TransitionType.FADE, durationMs = 300))
  )

  @Test
  fun splitMainTrackAtCutsVideoOnlyAndKeepsMusicWhole() {
    val out = TimelineRangeOps.splitMainTrackAt(base(), listOf(2000, 7500), unlocked)
    assertEquals(4, out.videoClips.size)
    assertEquals(listOf(0L, 2000L, 5000L, 7500L), out.videoClips.map { it.timelineStartMs })
    assertEquals(1, out.audioClips.size)
    assertEquals(10_000L, out.audioClips[0].durationMs)
  }

  @Test
  fun splitMainTrackAtCutsMirroredAudioWithTheVideo() {
    val tl = base().copy(
      audioClips = listOf(
        AudioClip(id = "own", uri = "file:///a.m4a", title = "own", timelineStartMs = 0, durationMs = 5000, sourceEndMs = 5000)
      )
    )
    val out = TimelineRangeOps.splitMainTrackAt(tl, listOf(2500), unlocked)
    assertEquals(2, out.audioClips.size)
    assertEquals(listOf(2500L, 2500L), out.audioClips.map { it.durationMs })
  }

  @Test
  fun splitSkipsTimesNearEdgesAndDuplicates() {
    val out = TimelineRangeOps.splitMainTrackAt(base(), listOf(0, 5, 5000, 4995, 2000, 2000), unlocked)
    assertEquals(3, out.videoClips.size)
  }

  @Test
  fun removeRangeClosesTheGapAcrossAllLanes() {
    val out = TimelineRangeOps.removeRanges(base(), listOf(TimeRange(2000, 3000)), unlocked)
    assertEquals(9000L, out.totalDurationMs)
    assertEquals(listOf(0L, 2000L, 4000L), out.videoClips.map { it.timelineStartMs })
    assertEquals(listOf(2000L, 2000L, 5000L), out.videoClips.map { it.durationMs })
    assertEquals(9000L, out.audioClips.sumOf { it.durationMs })
    assertEquals(3000L, out.textClips.first().timelineStartMs)
  }

  @Test
  fun removedPartOfTheSourceIsActuallySkipped() {
    val out = TimelineRangeOps.removeRanges(base(), listOf(TimeRange(2000, 3000)), unlocked)
    val second = out.videoClips[1]
    assertEquals(2000L, second.timelineStartMs)
    assertEquals(3000L, second.sourceStartMs)
  }

  @Test
  fun textInsideRangeIsDeletedAndCrossingTextIsTrimmed() {
    val tl = base().copy(
      textClips = listOf(
        TextClip(id = "in", text = "in", timelineStartMs = 2200, durationMs = 400),
        TextClip(id = "cross", text = "x y", timelineStartMs = 1500, durationMs = 1000)
      )
    )
    val out = TimelineRangeOps.removeRanges(tl, listOf(TimeRange(2000, 3000)), unlocked)
    assertTrue(out.textClips.none { it.id == "in" })
    val remaining = out.textClips.filter { it.timelineStartMs < 2000 }
    assertEquals(1, remaining.size)
    assertEquals(500L, remaining[0].durationMs)
  }

  @Test
  fun multipleRangesAreAppliedFromTheEndSoTimesStayValid() {
    val out = TimelineRangeOps.removeRanges(
      base(), listOf(TimeRange(1000, 1500), TimeRange(6000, 7000)), unlocked
    )
    assertEquals(8500L, out.totalDurationMs)
    assertEquals(8500L, out.videoClips.sumOf { it.durationMs })
  }

  @Test
  fun overlappingRangesAreMerged() {
    val merged = TimelineRangeOps.normalize(
      listOf(TimeRange(100, 300), TimeRange(250, 400), TimeRange(900, 950), TimeRange(10, 10))
    )
    assertEquals(listOf(TimeRange(100, 400), TimeRange(900, 950)), merged)
  }

  @Test
  fun transitionIsDroppedWhenItsClipIsRemovedAndReindexedOtherwise() {
    val tl = base().copy(
      videoClips = listOf(video("a", 0, 3000), video("b", 3000, 3000), video("c", 6000, 3000)),
      transitions = listOf(
        Transition(clipIndexBefore = 0, type = TransitionType.FADE, durationMs = 300),
        Transition(clipIndexBefore = 1, type = TransitionType.WIPE, durationMs = 300)
      ),
      audioClips = emptyList(), textClips = emptyList()
    )
    val out = TimelineRangeOps.removeRanges(tl, listOf(TimeRange(0, 3000)), unlocked)
    assertEquals(2, out.videoClips.size)
    assertEquals(1, out.transitions.size)
    assertEquals(0, out.transitions[0].clipIndexBefore)
    assertEquals(TransitionType.WIPE, out.transitions[0].type)
  }

  @Test
  fun markersInsideAreRemovedAndLaterOnesMove() {
    val tl = base().copy(
      markers = listOf(TimelineMarker(timeMs = 500), TimelineMarker(timeMs = 2500), TimelineMarker(timeMs = 8000))
    )
    val out = TimelineRangeOps.removeRanges(tl, listOf(TimeRange(2000, 3000)), unlocked)
    assertEquals(listOf(500L, 7000L), out.markers.map { it.timeMs })
  }

  @Test
  fun lockedMusicLaneStaysWhereItIs() {
    val lockedAudio: (com.example.domain.model.TrackType, Int) -> Boolean =
      { type, _ -> type == com.example.domain.model.TrackType.AUDIO }
    val out = TimelineRangeOps.removeRanges(base(), listOf(TimeRange(2000, 3000)), lockedAudio)
    assertEquals(1, out.audioClips.size)
    assertEquals(10_000L, out.audioClips[0].durationMs)
    assertEquals(9000L, out.videoClips.sumOf { it.durationMs })
  }

  @Test
  fun emptyRangesLeaveTheSameInstance() {
    val tl = base()
    assertSame(tl, TimelineRangeOps.removeRanges(tl, emptyList(), unlocked))
  }
}
