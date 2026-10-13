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

  private var timeline = Timeline(
    videoClips = listOf(VideoClip(id = "v1", name = "v1"), VideoClip(id = "v2", name = "v2", timelineStartMs = 3000)),
    overlayClips = listOf(VideoClip(id = "o1", name = "o1", trackIndex = 1)),
    audioClips = listOf(AudioClip(id = "a1", uri = "a", title = "a")),
    textClips = listOf(TextClip(id = "t1"))
  )
  private val selection = TimelineSelection { timeline }

  @Test
  fun trackElementResolvesEachClipKind() {
    assertEquals(SelectedTrackElement.Video("v1"), timeline.trackElementFor("v1"))
    assertEquals(SelectedTrackElement.Overlay("o1"), timeline.trackElementFor("o1"))
    assertEquals(SelectedTrackElement.Audio("a1"), timeline.trackElementFor("a1"))
    assertEquals(SelectedTrackElement.Text("t1"), timeline.trackElementFor("t1"))
    assertEquals(SelectedTrackElement.None, timeline.trackElementFor("missing"))
  }

  @Test
  fun selectReplacesIdsWithTheElementClip() {
    selection.selectMultiple(setOf("v1", "a1"))
    selection.select(SelectedTrackElement.Text("t1"))
    assertEquals(setOf("t1"), selection.selectedClipIds.value)
    selection.select(SelectedTrackElement.None)
    assertTrue(selection.selectedClipIds.value.isEmpty())
  }

  @Test
  fun addAndRemoveTrackMultiSelectModeAndFocus() {
    selection.selectClip("v1")
    selection.add("o1")
    assertTrue(selection.isMultiSelectMode.value)
    assertEquals(SelectedTrackElement.Overlay("o1"), selection.selectedElement.value)

    selection.remove("o1")
    assertFalse(selection.isMultiSelectMode.value)
    assertEquals(SelectedTrackElement.Video("v1"), selection.selectedElement.value)

    selection.remove("v1")
    assertEquals(SelectedTrackElement.None, selection.selectedElement.value)
  }

  @Test
  fun tappingASecondClipOutsideMultiSelectPromotesToMultiSelect() {
    selection.toggle("v1")
    assertFalse(selection.isMultiSelectMode.value)
    selection.toggle("a1")
    assertTrue(selection.isMultiSelectMode.value)
    assertEquals(setOf("v1", "a1"), selection.selectedClipIds.value)
  }

  @Test
  fun togglingTheLastClipOffInMultiSelectExitsTheMode() {
    selection.selectMultiple(setOf("v1", "v2"))
    selection.toggle("v1")
    selection.toggle("v2")
    assertTrue(selection.selectedClipIds.value.isEmpty())
    assertFalse(selection.isMultiSelectMode.value)
    assertEquals(SelectedTrackElement.None, selection.selectedElement.value)
  }

  @Test
  fun leavingMultiSelectKeepsOnlyTheFirstClip() {
    selection.selectMultiple(setOf("v2", "o1", "a1"))
    selection.toggleMultiSelectMode()
    assertEquals(setOf("v2"), selection.selectedClipIds.value)
    assertEquals(SelectedTrackElement.Video("v2"), selection.selectedElement.value)
  }

  @Test
  fun selectAllCoversEveryTrack() {
    selection.selectAll()
    assertEquals(setOf("v1", "v2", "o1", "t1", "a1"), selection.selectedClipIds.value)
    assertTrue(selection.isMultiSelectMode.value)
  }

  @Test
  fun replaceDoesNotEnterMultiSelectMode() {
    selection.replace(setOf("v1", "v2"))
    assertFalse(selection.isMultiSelectMode.value)
    assertEquals(SelectedTrackElement.Video("v1"), selection.selectedElement.value)
  }

  @Test
  fun pruneMissingDropsClipsRemovedFromTheTimeline() {
    selection.selectMultiple(setOf("v1", "o1"))
    selection.add("a1")
    timeline = timeline.copy(audioClips = emptyList(), overlayClips = emptyList())
    selection.pruneMissing()
    assertEquals(setOf("v1"), selection.selectedClipIds.value)
    assertEquals(SelectedTrackElement.None, selection.selectedElement.value)
  }

  @Test
  fun isSelectedChecksFocusedElementToo() {
    selection.selectedElementState.value = SelectedTrackElement.Audio("a1")
    assertTrue(selection.isSelected("a1"))
    assertFalse(selection.isSelected("v1"))
  }
}
