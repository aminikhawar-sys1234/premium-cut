package com.example

import com.example.domain.model.MarkerType
import com.example.domain.model.TextClip
import com.example.domain.model.Timeline
import com.example.domain.model.VideoClip
import com.example.domain.model.WordTiming
import com.example.engine.TimelineEngine
import com.example.engine.ai.reframe.ReframeKey
import com.example.engine.history.TimelineActionType
import com.example.engine.timeline.AttributeGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineEngineSmartOpsTest {

  private fun video(id: String, start: Long, dur: Long, opacity: Float = 1f) = VideoClip(
    id = id, uri = "file:///$id.mp4", name = id, timelineStartMs = start, durationMs = dur,
    sourceStartMs = 0L, sourceEndMs = dur, opacity = opacity
  )

  private fun engine(tl: Timeline) = TimelineEngine().also { it.loadTimeline(tl) }

  @Test fun beatCutIsOneUndoStep() {
    val e = engine(Timeline(videoClips = listOf(video("a", 0, 10_000))))
    val cuts = e.splitMainTrackAtTimes(listOf(2000, 4000, 6000, 8000), TimelineActionType.BEAT_SYNC, "Cut on beats")
    assertEquals(4, cuts)
    assertEquals(5, e.timeline.value.videoClips.size)
    assertTrue(e.undo())
    assertEquals(1, e.timeline.value.videoClips.size)
  }

  @Test fun markersReplaceOnlyTheirOwnType() {
    val e = engine(Timeline(videoClips = listOf(video("a", 0, 10_000))))
    e.setMarkers(MarkerType.BEAT, listOf(500, 1000))
    e.setMarkers(MarkerType.SCENE, listOf(3000))
    e.setMarkers(MarkerType.BEAT, listOf(700))
    val markers = e.timeline.value.markers
    assertEquals(listOf(700L), markers.filter { it.type == MarkerType.BEAT }.map { it.timeMs })
    assertEquals(listOf(3000L), markers.filter { it.type == MarkerType.SCENE }.map { it.timeMs })
    e.clearMarkers(MarkerType.BEAT)
    assertTrue(e.timeline.value.markers.none { it.type == MarkerType.BEAT })
  }

  @Test fun autoReframeWritesKeyframesAndResetsCrop() {
    val clip = video("a", 0, 4000).copy(cropScale = 1.5f, cropOffsetX = 0.2f)
    val e = engine(Timeline(videoClips = listOf(clip)))
    val ok = e.applyAutoReframe("a", listOf(ReframeKey(0, 3.1f, 0.5f, 0f), ReframeKey(4000, 3.1f, -0.5f, 0f)))
    assertTrue(ok)
    val out = e.timeline.value.videoClips.single()
    assertEquals(2, out.keyframes.size)
    assertEquals(1f, out.cropScale, 0f)
    assertEquals(0f, out.cropOffsetX, 0f)
    assertEquals(3.1f, out.keyframes[0].scaleX, 0f)
    assertTrue(e.undo())
    assertEquals(1.5f, e.timeline.value.videoClips.single().cropScale, 0f)
  }

  @Test fun pasteAttributesCopiesLookButNotSpeed() {
    val e = engine(Timeline(videoClips = listOf(video("a", 0, 3000, opacity = 0.4f), video("b", 3000, 3000).copy(speed = 2f))))
    assertTrue(e.copyClipAttributes("a"))
    assertEquals(1, e.pasteClipAttributes(setOf("b"), setOf(AttributeGroup.MOTION)))
    val b = e.timeline.value.videoClips.first { it.id == "b" }
    assertEquals(0.4f, b.opacity, 0f)
    assertEquals(2f, b.speed, 0f)
    assertTrue(e.undo())
    assertEquals(1f, e.timeline.value.videoClips.first { it.id == "b" }.opacity, 0f)
  }

  @Test fun removeFillerWordsCutsThemOutOfTheTimeline() {
    val words = listOf(
      WordTiming("hello", 0, 400), WordTiming("um", 500, 300), WordTiming("world", 900, 400)
    )
    val caption = TextClip(id = "c", text = "hello um world", timelineStartMs = 0, durationMs = 1400, words = words)
    val e = engine(Timeline(videoClips = listOf(video("a", 0, 1400)), textClips = listOf(caption)))
    assertEquals(1, e.removeFillerWords())
    val tl = e.timeline.value
    assertTrue(tl.totalDurationMs < 1400L)
    assertFalse(tl.textClips.toString(), tl.textClips.any { clip -> clip.words.any { it.word == "um" } })
    assertTrue(e.undo())
    assertEquals(1400L, e.timeline.value.totalDurationMs)
  }

  @Test fun deletingTheLastCaptionWordRemovesTheCaption() {
    val caption = TextClip(id = "c", text = "solo", timelineStartMs = 0, durationMs = 500, words = listOf(WordTiming("solo", 0, 500)))
    val e = engine(Timeline(textClips = listOf(caption)))
    assertTrue(e.editCaptionWord("c", 0, null))
    assertTrue(e.timeline.value.textClips.isEmpty())
  }
}
