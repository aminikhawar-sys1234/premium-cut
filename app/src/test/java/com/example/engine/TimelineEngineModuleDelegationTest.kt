package com.example.engine

import com.example.domain.model.AudioClip
import com.example.domain.model.Timeline
import com.example.domain.model.TrackType
import com.example.domain.model.VideoClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Engine-level checks that lock, history and playhead rules survive delegation to engine/timeline modules. */
class TimelineEngineModuleDelegationTest {

  private lateinit var engine: TimelineEngine

  private fun video(id: String, start: Long, dur: Long, total: Long = 10_000) = VideoClip(
    id = id, name = id, timelineStartMs = start, durationMs = dur, sourceEndMs = dur, sourceTotalDurationMs = total
  )

  @Before
  fun setUp() {
    engine = TimelineEngine()
    engine.loadTimeline(
      Timeline(
        videoClips = listOf(video("a", 0, 1000), video("b", 1000, 1000), video("c", 2000, 1000)),
        overlayClips = listOf(video("o", 500, 1000).copy(trackIndex = 1)),
        audioClips = listOf(AudioClip(id = "m", uri = "m", title = "m", timelineStartMs = 200, durationMs = 1000, sourceEndMs = 1000))
      )
    )
    engine.clearHistory()
  }

  @Test
  fun moveTrackByDeltaKeepsMainTrackAndMovesPlayhead() {
    assertTrue(engine.moveTrackByDelta(TrackType.OVERLAY, 1, 300))
    val tl = engine.timeline.value
    assertEquals(listOf(0L, 1000L, 2000L), tl.videoClips.map { it.timelineStartMs })
    assertEquals(800L, tl.overlayClips.single().timelineStartMs)
    assertEquals(500L, tl.audioClips.single().timelineStartMs)
    assertEquals(300L, engine.currentPositionMs.value)
    assertTrue(engine.canUndo.value)
  }

  @Test
  fun lockedTrackBlocksSourceTrimWithoutRecordingHistory() {
    engine.setTrackLocked(TrackType.MAIN_VIDEO, true)
    engine.clearHistory()
    assertFalse(engine.trimClipSourceRange("b", 100, 600))
    assertFalse(engine.canUndo.value)
  }

  @Test
  fun sourceTrimOnMainTrackRipplesAndUndoes() {
    assertTrue(engine.trimClipSourceRange("a", 0, 400))
    assertEquals(listOf(0L, 400L, 1400L), engine.timeline.value.videoClips.map { it.timelineStartMs })
    assertTrue(engine.undo())
    assertEquals(listOf(0L, 1000L, 2000L), engine.timeline.value.videoClips.map { it.timelineStartMs })
  }

  @Test
  fun slideTradesNeighbourDurationsAndMovesNonMainClips() {
    assertTrue(engine.slideClip("b", 200, snap = false))
    assertEquals(1200L, engine.timeline.value.videoClips[1].timelineStartMs)
    assertEquals(800L, engine.timeline.value.videoClips[2].durationMs)

    assertTrue(engine.slideClip("o", 100, snap = false))
    assertEquals(600L, engine.timeline.value.overlayClips.single().timelineStartMs)
  }

  @Test
  fun toggleTrackSettingsAreUndoable() {
    engine.toggleTrackMute(TrackType.AUDIO)
    assertTrue(engine.timeline.value.trackSettings.getValue(TrackType.AUDIO).isMuted)
    engine.undo()
    assertFalse(engine.timeline.value.trackSettings.getValue(TrackType.AUDIO).isMuted)
  }

  @Test
  fun replacingTimelinePrunesSelectionOfRemovedClips() {
    engine.selectMultipleClips(setOf("m", "a"))
    engine.replaceTimelineKeepingState(engine.timeline.value.copy(audioClips = emptyList()))
    assertEquals(setOf("a"), engine.selectedClipIds.value)
    assertEquals(SelectedTrackElement.None, engine.selectedElement.value)
  }

  @Test
  fun reorderRepacksMainTrack() {
    assertTrue(engine.reorderVideoClips(0, 2))
    assertEquals(listOf("b", "c", "a"), engine.timeline.value.videoClips.map { it.id })
    assertEquals(listOf(0L, 1000L, 2000L), engine.timeline.value.videoClips.map { it.timelineStartMs })
  }

  @Test
  fun videoEndLockClampsTrailingOverlays() {
    engine.addOverlayClip(video("late", 2800, 1000).copy(trackIndex = 2))
    engine.setVideoTrackEndLocked(true)
    val late = engine.timeline.value.overlayClips.first { it.id == "late" }
    assertEquals(200L, late.durationMs)
  }
}
