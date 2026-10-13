package com.example.engine.preview

import com.example.domain.model.AudioClip
import com.example.domain.model.Timeline
import com.example.domain.model.TrackSettings
import com.example.domain.model.TrackType
import com.example.domain.model.VideoClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewLayerPlannerTest {

  private fun clip(id: String, start: Long, dur: Long, isVideo: Boolean = true, hidden: Boolean = false) =
    VideoClip(id = id, name = id, uri = "file:///$id.mp4", timelineStartMs = start, durationMs = dur, sourceEndMs = dur, isVideo = isVideo, isHidden = hidden)

  private fun audio(id: String, start: Long, dur: Long) =
    AudioClip(id = id, uri = "file:///$id.m4a", title = id, timelineStartMs = start, durationMs = dur)

  @Test
  fun windowCoversPrerollAndTail() {
    // clip [1000, 2000)
    assertFalse(PreviewLayerPlanner.inWindow(1000, 1000, posMs = 0, prerollMs = 500, tailMs = 0))
    assertTrue(PreviewLayerPlanner.inWindow(1000, 1000, posMs = 500, prerollMs = 500, tailMs = 0))
    assertTrue(PreviewLayerPlanner.inWindow(1000, 1000, posMs = 1999, prerollMs = 0, tailMs = 0))
    assertFalse(PreviewLayerPlanner.inWindow(1000, 1000, posMs = 2000, prerollMs = 0, tailMs = 0))
    assertTrue(PreviewLayerPlanner.inWindow(1000, 1000, posMs = 2400, prerollMs = 0, tailMs = 500))
    assertFalse(PreviewLayerPlanner.inWindow(1000, 1000, posMs = 2500, prerollMs = 0, tailMs = 500))
  }

  @Test
  fun overlayWindowSkipsHiddenAndFarClipsSortedByStart() {
    val tl = Timeline(
      overlayClips = listOf(
        clip("late", 1200, 1000),
        clip("now", 0, 5000),
        clip("hidden", 0, 5000, hidden = true),
        clip("far", 60_000, 1000),
        clip("ended", 0, 100)
      )
    )
    assertEquals(listOf("now", "late"), PreviewLayerPlanner.overlayIdsInWindow(tl, posMs = 1000))
  }

  @Test
  fun hiddenOverlayTrackHasNoVisualSources() {
    val tl = Timeline(
      overlayClips = listOf(clip("a", 0, 5000)),
      trackSettings = mapOf(TrackType.OVERLAY to TrackSettings(TrackType.OVERLAY, isHidden = true))
    )
    assertTrue(PreviewLayerPlanner.overlayIdsInWindow(tl, 0).isEmpty())
  }

  @Test
  fun audioWindowKeepsOnlyClipsNearThePlayhead() {
    val tl = Timeline(audioClips = listOf(audio("bed", 0, 120_000), audio("sfx", 30_000, 500), audio("soon", 12_000, 1000)))
    assertEquals(setOf("bed", "soon"), PreviewLayerPlanner.audioIdsInWindow(tl, posMs = 10_000))
  }

  @Test
  fun decoderPlanPutsActiveLayersFirstAndRespectsTheBudget() {
    val tl = Timeline(videoClips = listOf(clip("m1", 0, 1000), clip("m2", 1000, 1000)))
    val plan = PreviewLayerPlanner.videoDecoderPlan(tl, 0, listOf("m1", "o1", "o2", "o3", "o4"), playing = true, maxDecoders = 4)
    assertEquals(listOf("m1", "o1", "o2", "o3"), plan)
  }

  @Test
  fun decoderPlanPrerollsUpcomingClipsOnlyWhilePlaying() {
    val tl = Timeline(
      videoClips = listOf(clip("m1", 0, 1000), clip("m2", 1000, 1000), clip("img", 1100, 1000, isVideo = false), clip("m3", 2000, 1000)),
      overlayClips = listOf(clip("o1", 1200, 500), clip("oHidden", 900, 500, hidden = true))
    )
    assertEquals(listOf("m1", "m2", "o1"), PreviewLayerPlanner.videoDecoderPlan(tl, 500, listOf("m1"), playing = true, prerollMs = 1000))
    assertEquals(listOf("m1"), PreviewLayerPlanner.videoDecoderPlan(tl, 500, listOf("m1"), playing = false, prerollMs = 1000))
  }

  @Test
  fun decoderPlanDeduplicatesActiveIds() {
    val plan = PreviewLayerPlanner.videoDecoderPlan(Timeline(), 0, listOf("a", "a", "b"), playing = false)
    assertEquals(listOf("a", "b"), plan)
  }

  @Test
  fun evictionsAreOpenSourcesOutsideThePlan() {
    assertEquals(listOf("old"), PreviewLayerPlanner.evictions(listOf("keep", "old"), listOf("keep", "new")))
  }
}
