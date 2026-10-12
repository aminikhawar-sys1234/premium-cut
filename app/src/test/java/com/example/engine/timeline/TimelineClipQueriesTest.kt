package com.example.engine.timeline

import com.example.domain.model.AudioClip
import com.example.domain.model.TextClip
import com.example.domain.model.Timeline
import com.example.domain.model.TrackType
import com.example.domain.model.Transition
import com.example.domain.model.TransitionType
import com.example.domain.model.VideoClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineClipQueriesTest {

  private val timeline = Timeline(
    videoClips = listOf(
      VideoClip(id = "v1", name = "V1", uri = "v1", timelineStartMs = 0L, durationMs = 2000L, hasAudio = true, volume = 0.5f, sourceStartMs = 0L, sourceEndMs = 2000L),
      VideoClip(id = "v2", name = "V2", uri = "v2", timelineStartMs = 2000L, durationMs = 2000L, sourceStartMs = 0L, sourceEndMs = 2000L)
    ),
    overlayClips = listOf(
      VideoClip(id = "o1", name = "O1", uri = "o1", timelineStartMs = 500L, durationMs = 2000L, trackIndex = 1)
    ),
    audioClips = listOf(
      AudioClip(id = "a1", title = "A1", uri = "a1", timelineStartMs = 0L, durationMs = 3000L, volume = 1f, fadeInMs = 0L, fadeOutMs = 0L)
    ),
    textClips = listOf(
      TextClip(id = "t1", text = "Hi", timelineStartMs = 1000L, durationMs = 500L)
    ),
    transitions = listOf(
      Transition(id = "tr1", clipIndexBefore = 0, type = TransitionType.FADE, durationMs = 400L)
    )
  )

  @Test
  fun activeSnapshotIsEndExclusiveAndIncludesOverlays() {
    val at1000 = TimelineClipQueries.getActiveClipsAt(timeline, 1000L, 30)
    assertEquals("v1", at1000.primaryVideoClip?.id)
    assertEquals(listOf("o1"), at1000.activeOverlays.map { it.id })
    assertEquals(listOf("t1"), at1000.activeTextClips.map { it.id })
    assertEquals(listOf("a1"), at1000.activeAudioClips.map { it.id })

    val at2000 = TimelineClipQueries.getActiveClipsAt(timeline, 2000L, 30)
    assertEquals("v2", at2000.primaryVideoClip?.id)
    assertEquals(emptyList<String>(), at2000.activeTextClips.map { it.id })
  }

  @Test
  fun overlapAndTransitionQueries() {
    val overlaps = TimelineClipQueries.getOverlapsAt(timeline, 1000L)
    assertTrue(overlaps.isEmpty())
    val timing = TimelineClipQueries.getTransitionTimingAt(timeline, 1800L)
    assertEquals("tr1", timing?.transition?.id)
    assertTrue(timing!!.progress in 0f..1f)
  }

  @Test
  fun audioSyncUsesTheSameClockAsVideo() {
    val sync = TimelineClipQueries.getActiveAudioSyncInfoAt(timeline, 1000L)
    val audio = sync.first { it.clipId == "a1" }
    assertEquals(1000L, audio.timelinePosMs)
    assertEquals(1000L, audio.sourcePosMs)
    val video = sync.first { it.clipId == "v1" }
    assertEquals(1000L, video.sourcePosMs)
    assertFalse(audio.isVideoTrack)
    assertTrue(video.isVideoTrack)
  }

  @Test
  fun hasOverlapOnTrackDetectsCollision() {
    assertTrue(
      TimelineClipQueries.hasOverlapOnTrack(timeline, TrackType.OVERLAY, 1, 1000L, 800L)
    )
    assertFalse(
      TimelineClipQueries.hasOverlapOnTrack(timeline, TrackType.OVERLAY, 1, 3000L, 500L)
    )
  }
}
