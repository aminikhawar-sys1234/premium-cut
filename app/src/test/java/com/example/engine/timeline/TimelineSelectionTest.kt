package com.example.engine.timeline

import com.example.domain.model.AudioClip
import com.example.domain.model.TextClip
import com.example.domain.model.Timeline
import com.example.domain.model.VideoClip
import com.example.engine.SelectedTrackElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineSelectionTest {

  private val timeline = Timeline(
    videoClips = listOf(VideoClip(id = "v1", name = "V", uri = "v", durationMs = 2000L)),
    overlayClips = listOf(VideoClip(id = "o1", name = "O", uri = "o", durationMs = 1000L, trackIndex = 1)),
    audioClips = listOf(AudioClip(id = "a1", title = "A", uri = "a", durationMs = 2000L)),
    textClips = listOf(TextClip(id = "t1", text = "Hi", durationMs = 1000L))
  )

  @Test
  fun findTrackElementResolvesEveryLane() {
    assertEquals(SelectedTrackElement.Video("v1"), TimelineSelection.findTrackElementForClip(timeline, "v1"))
    assertEquals(SelectedTrackElement.Overlay("o1"), TimelineSelection.findTrackElementForClip(timeline, "o1"))
    assertEquals(SelectedTrackElement.Audio("a1"), TimelineSelection.findTrackElementForClip(timeline, "a1"))
    assertEquals(SelectedTrackElement.Text("t1"), TimelineSelection.findTrackElementForClip(timeline, "t1"))
    assertEquals(SelectedTrackElement.None, TimelineSelection.findTrackElementForClip(timeline, "missing"))
  }

  @Test
  fun toggleSelectEntersMultiSelectWhenAddingASecondClip() {
    val first = TimelineSelection.selectElement(SelectedTrackElement.Video("v1"))
    val second = TimelineSelection.toggleSelectClip(timeline, first, "o1")
    assertTrue(second.multiSelect)
    assertEquals(setOf("v1", "o1"), second.clipIds)
  }

  @Test
  fun pruneDropsDeletedClipsAfterUndo() {
    val selected = TimelineSelection.selectMultiple(timeline, setOf("v1", "o1", "gone"))
    val pruned = TimelineSelection.pruneAfterHistoryChange(timeline, selected)
    assertEquals(setOf("v1", "o1"), pruned.clipIds)
    assertFalse(pruned.clipIds.contains("gone"))
  }

  @Test
  fun selectAllCollectsEveryClipId() {
    val all = TimelineSelection.selectAll(timeline)
    assertEquals(setOf("v1", "o1", "a1", "t1"), all.clipIds)
    assertTrue(all.multiSelect)
  }
}
