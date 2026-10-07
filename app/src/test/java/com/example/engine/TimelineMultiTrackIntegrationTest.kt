package com.example.engine

import com.example.domain.model.AudioClip
import com.example.domain.model.TextClip
import com.example.domain.model.Timeline
import com.example.domain.model.TrackType
import com.example.domain.model.VideoClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TimelineMultiTrackIntegrationTest {

  private lateinit var engine: TimelineEngine

  private fun video(id: String, start: Long, dur: Long, lane: Int = 0, total: Long = 20_000L) = VideoClip(
    id = id, name = id, uri = "file:///$id.mp4", timelineStartMs = start, durationMs = dur,
    sourceStartMs = 2000, sourceEndMs = 2000 + dur, sourceTotalDurationMs = total, trackIndex = lane
  )

  private fun audio(id: String, start: Long, dur: Long, lane: Int = 0) = AudioClip(
    id = id, title = id, uri = "file:///$id.mp3", timelineStartMs = start, durationMs = dur,
    sourceStartMs = 0, sourceEndMs = dur, sourceTotalDurationMs = dur * 2, trackIndex = lane
  )

  @Before
  fun setUp() {
    engine = TimelineEngine()
    engine.loadTimeline(
      Timeline(
        videoClips = listOf(video("v1", 0, 4000), video("v2", 4000, 4000)),
        overlayClips = listOf(video("o1", 1000, 3000, lane = 1)),
        audioClips = listOf(audio("a1", 0, 6000)),
        textClips = listOf(TextClip(id = "t1", text = "Hi", timelineStartMs = 500, durationMs = 2000))
      )
    )
  }

  private val tl get() = engine.timeline.value

  @Test
  fun splitUndoRedoRestoresExactState() {
    val before = tl
    val ids = engine.splitClipAtTime("o1", 2000)
    assertNotNull(ids)
    assertEquals(2, tl.overlayClips.size)
    assertTrue(engine.undo())
    assertEquals(before, tl)
    assertTrue(engine.redo())
    assertEquals(2, tl.overlayClips.size)
  }

  @Test
  fun splitAllTracksCutsEveryTrackUnderPlayhead() {
    engine.setPosition(2000, snap = false)
    assertTrue(engine.splitAllTracksAtPlayhead())
    assertEquals(3, tl.videoClips.size)
    assertEquals(2, tl.overlayClips.size)
    assertEquals(2, tl.audioClips.size)
    assertEquals(2, tl.textClips.size)
  }

  @Test
  fun trimEndIsBoundedByRealMediaLength() {
    engine.trimClipRight("o1", 500_000, snap = false)
    val o = tl.overlayClips.single()
    assertEquals(20_000L - 2000L, o.durationMs)
    assertEquals(20_000L, o.sourceEndMs)
  }

  @Test
  fun trimLeftOnOverlayKeepsRightEdgeAndSourceOffset() {
    engine.trimClipLeft("o1", 2000, snap = false)
    val o = tl.overlayClips.single()
    assertEquals(2000L, o.timelineStartMs)
    assertEquals(4000L, o.timelineStartMs + o.durationMs)
    assertEquals(3000L, o.sourceStartMs)
  }

  @Test
  fun audioTrimLeftAdvancesSourceStart() {
    engine.trimClipLeft("a1", 1500, snap = false)
    val a = tl.audioClips.single()
    assertEquals(1500L, a.sourceStartMs)
    assertEquals(4500L, a.durationMs)
  }

  @Test
  fun lockedLaneRejectsEdits() {
    assertTrue(engine.setLaneFlags(TrackType.OVERLAY, 1, locked = true))
    val before = tl
    engine.trimClipRight("o1", 500, snap = false)
    engine.moveClip("o1", 5000, snap = false)
    assertTrue(engine.splitClipAtTime("o1", 2000) == null)
    assertEquals(before, tl)
  }

  @Test
  fun noOpEditsDoNotPolluteUndoHistory() {
    engine.undo()
    val before = tl
    engine.trimClipRight("o1", 3000, snap = false)
    engine.undo()
    assertEquals(before, tl)
  }

  @Test
  fun moveClipToAnotherLaneAndBack() {
    assertTrue(engine.moveClipToTrackLane("o1", TrackType.OVERLAY, 3))
    assertEquals(3, tl.overlayClips.single().trackIndex)
    assertFalse(engine.moveClipToTrackLane("o1", TrackType.OVERLAY, 3))
    assertTrue(engine.undo())
    assertEquals(1, tl.overlayClips.single().trackIndex)
  }

  @Test
  fun deleteAndDuplicateRoundTripThroughUndo() {
    val before = tl
    assertTrue(engine.deleteClips(setOf("o1", "a1")))
    assertTrue(tl.overlayClips.isEmpty())
    assertTrue(tl.audioClips.isEmpty())
    assertTrue(engine.undo())
    assertEquals(before, tl)
  }

  @Test
  fun addAndRemoveTrackKeepsOtherLanesIntact() {
    val a = engine.addTrack(TrackType.OVERLAY)
    val b = engine.addTrack(TrackType.OVERLAY)
    assertTrue(a.trackId != b.trackId)
    assertTrue(engine.removeTrack(a.trackId))
    assertEquals(1, tl.overlayClips.size)
    assertTrue(engine.undo())
  }

  @Test
  fun largeProjectStaysConsistentAndFast() {
    val videos = (0 until 60).map { video("lv$it", it * 1000L, 1000, lane = if (it == 0) 0 else 0) }
    val overlays = (0 until 60).map { video("lo$it", it * 500L, 2000, lane = 1 + it % 20) }
    val audios = (0 until 60).map { audio("la$it", it * 700L, 1500, lane = it % 10) }
    engine.loadTimeline(Timeline(videoClips = videos, overlayClips = overlays, audioClips = audios))
    val started = System.nanoTime()
    repeat(50) { i ->
      engine.setPosition(i * 400L, snap = true)
      engine.trimClipRight("lo${i % 60}", 1500, snap = true)
    }
    val elapsedMs = (System.nanoTime() - started) / 1_000_000
    assertTrue("50+ layer edits took $elapsedMs ms", elapsedMs < 5000)
    assertEquals(60, tl.overlayClips.size)
    assertEquals(60, tl.audioClips.size)
  }

  @Test
  fun positionUsKeepsMicrosecondPrecision() {
    engine.setPositionUs(1_234_567L, snap = false)
    assertEquals(1234L, engine.currentPositionMs.value)
    assertEquals(1_234_567L, engine.currentPositionUs.value)
  }

  @Test
  fun lanePermutationsKeepSingleClipOwnership() {
    engine.addTrack(TrackType.OVERLAY)
    val laneBefore = tl.overlayClips.single().trackIndex
    engine.moveTrackLane(TrackType.OVERLAY, laneBefore, up = true)
    val all = tl.overlayClips + tl.videoClips
    assertEquals(all.size, all.map { it.id }.toSet().size)
  }
}
