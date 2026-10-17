package com.example.engine.timeline

import com.example.domain.model.AudioClip
import com.example.domain.model.TextClip
import com.example.domain.model.Timeline
import com.example.domain.model.TrackHeight
import com.example.domain.model.TrackType
import com.example.domain.model.VideoClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineTrackRulesTest {

  private fun video(id: String, start: Long, dur: Long) =
    VideoClip(id = id, name = id, timelineStartMs = start, durationMs = dur, sourceEndMs = dur)

  private fun audio(id: String, start: Long, dur: Long) =
    AudioClip(id = id, uri = id, title = id, timelineStartMs = start, durationMs = dur, sourceEndMs = dur)

  private fun text(id: String, start: Long, dur: Long) = TextClip(id = id, timelineStartMs = start, durationMs = dur)

  private val unlocked: (TrackType) -> Boolean = { false }

  @Test
  fun cutPositionsAreSortedDistinctEdges() {
    val tl = Timeline(
      videoClips = listOf(video("a", 0, 1000), video("b", 1000, 2000)),
      audioClips = listOf(audio("m", 500, 1000)),
      textClips = listOf(text("t", 2500, 1500))
    )
    assertEquals(listOf(0L, 500L, 1000L, 1500L, 2500L, 3000L, 4000L), TimelineTrackRules.cutPositions(tl))
  }

  @Test
  fun packMainTrackRemovesGapsInListOrder() {
    val tl = Timeline(videoClips = listOf(video("a", 200, 1000), video("b", 1500, 500), video("c", 5000, 700)))
    val packed = TimelineTrackRules.packMainTrack(tl)!!
    assertEquals(listOf(0L, 1000L, 1500L), packed.videoClips.map { it.timelineStartMs })
  }

  @Test
  fun packMainTrackReturnsNullWhenAlreadyPacked() {
    val tl = Timeline(videoClips = listOf(video("a", 0, 1000), video("b", 1000, 500)))
    assertNull(TimelineTrackRules.packMainTrack(tl))
    assertNull(TimelineTrackRules.packMainTrack(Timeline()))
  }

  @Test
  fun reorderMainTrackMovesClipAndRepacks() {
    val tl = Timeline(videoClips = listOf(video("a", 0, 1000), video("b", 1000, 500), video("c", 1500, 700)))
    val r = TimelineTrackRules.reorderMainTrack(tl, 2, 0)!!
    assertEquals(listOf("c", "a", "b"), r.videoClips.map { it.id })
    assertEquals(listOf(0L, 700L, 1700L), r.videoClips.map { it.timelineStartMs })
    assertNull(TimelineTrackRules.reorderMainTrack(tl, 1, 1))
    assertNull(TimelineTrackRules.reorderMainTrack(tl, 0, 3))
  }

  @Test
  fun clampToMainTrackEndDropsAndTrimsClipsPastTheEnd() {
    val tl = Timeline(
      videoClips = listOf(video("v", 0, 3000)),
      overlayClips = listOf(video("inside", 0, 1000), video("crossing", 2500, 2000), video("after", 3000, 500)),
      audioClips = listOf(audio("nearEnd", 2950, 1000))
    )
    val r = TimelineTrackRules.clampToMainTrackEnd(tl)!!
    assertEquals(listOf("inside", "crossing"), r.overlayClips.map { it.id })
    assertEquals(500L, r.overlayClips[1].durationMs)
    assertEquals(TimelineTrackRules.MIN_CLAMPED_DURATION_MS, r.audioClips.single().durationMs)
  }

  @Test
  fun clampToMainTrackEndIsNullWhenEverythingFits() {
    val tl = Timeline(videoClips = listOf(video("v", 0, 3000)), audioClips = listOf(audio("a", 0, 3000)))
    assertNull(TimelineTrackRules.clampToMainTrackEnd(tl))
    assertNull(TimelineTrackRules.clampToMainTrackEnd(Timeline(audioClips = listOf(audio("a", 0, 3000)))))
  }

  @Test
  fun rippleShiftsOnlyClipsAtOrAfterTheEditPoint() {
    val tl = Timeline(
      videoClips = listOf(video("a", 0, 1000), video("b", 1000, 1000)),
      audioClips = listOf(audio("early", 500, 300), audio("late", 1000, 300))
    )
    val r = TimelineTrackRules.rippleFrom(tl, fromMs = 1000, deltaMs = 400, isLocked = unlocked)
    assertEquals(listOf(0L, 1400L), r.videoClips.map { it.timelineStartMs })
    assertEquals(listOf(500L, 1400L), r.audioClips.map { it.timelineStartMs })
  }

  @Test
  fun rippleSkipsLockedTracksAndClampsAtZero() {
    val tl = Timeline(
      videoClips = listOf(video("a", 1000, 1000)),
      audioClips = listOf(audio("m", 1000, 300))
    )
    val r = TimelineTrackRules.rippleFrom(tl, 0, -5000, isLocked = { it == TrackType.AUDIO })
    assertEquals(0L, r.videoClips.single().timelineStartMs)
    assertSame(tl.audioClips, r.audioClips)
  }

  @Test
  fun rippleCanExcludeAlreadyShiftedMainVideo() {
    val tl = Timeline(
      videoClips = listOf(video("v", 1400, 1000)),
      overlayClips = listOf(video("o", 1000, 300)),
      audioClips = listOf(audio("early", 500, 300), audio("late", 1000, 300)),
      textClips = listOf(text("locked", 1000, 300))
    )
    val r = TimelineTrackRules.rippleFrom(
      tl,
      fromMs = 1000,
      deltaMs = 400,
      isLocked = { it == TrackType.TEXT },
      includeMainVideo = false
    )
    assertSame(tl.videoClips, r.videoClips)
    assertEquals(1400L, r.overlayClips.single().timelineStartMs)
    assertEquals(listOf(500L, 1400L), r.audioClips.map { it.timelineStartMs })
    assertSame(tl.textClips, r.textClips)
  }

  @Test
  fun earliestStartIgnoresLockedTracks() {
    val tl = Timeline(videoClips = listOf(video("v", 0, 1000)), audioClips = listOf(audio("a", 800, 100)))
    assertEquals(0L, TimelineTrackRules.earliestUnlockedStart(tl, unlocked))
    assertEquals(800L, TimelineTrackRules.earliestUnlockedStart(tl) { it == TrackType.MAIN_VIDEO })
    assertNull(TimelineTrackRules.earliestUnlockedStart(tl) { true })
  }

  @Test
  fun shiftDeltaStopsTheEarliestClipAtZero() {
    assertEquals(-300L, TimelineTrackRules.clampShiftDelta(-1000, 300))
    assertEquals(0L, TimelineTrackRules.clampShiftDelta(-1000, 0))
    assertEquals(250L, TimelineTrackRules.clampShiftDelta(250, 0))
  }

  @Test
  fun shiftTracksMovesSelectedTracksOnly() {
    val tl = Timeline(
      videoClips = listOf(video("v", 0, 1000)),
      overlayClips = listOf(video("o", 100, 500)),
      textClips = listOf(text("t", 200, 500))
    )
    val r = TimelineTrackRules.shiftTracks(tl, 300, shouldShift = { it != TrackType.MAIN_VIDEO })
    assertSame(tl.videoClips, r.videoClips)
    assertEquals(400L, r.overlayClips.single().timelineStartMs)
    assertEquals(500L, r.textClips.single().timelineStartMs)
  }

  @Test
  fun shiftTracksWithSortAllReordersUntouchedLists() {
    val tl = Timeline(audioClips = listOf(audio("late", 900, 100), audio("early", 100, 100)))
    val r = TimelineTrackRules.shiftTracks(tl, 50, shouldShift = { false }, sortAll = true)
    assertEquals(listOf("early", "late"), r.audioClips.map { it.id })
  }

  @Test
  fun withTrackSettingsCreatesMissingEntries() {
    val tl = Timeline(trackSettings = emptyMap())
    val r = TimelineTrackRules.withTrackSettings(tl, TrackType.AUDIO) { it.copy(isMuted = true, height = TrackHeight.EXPANDED) }
    val s = r.trackSettings.getValue(TrackType.AUDIO)
    assertTrue(s.isMuted)
    assertEquals(TrackHeight.EXPANDED, s.height)
  }
}
