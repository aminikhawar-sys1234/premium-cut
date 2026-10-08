package com.example.engine.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.engine.TimelineEngine

class PlaybackSyncPolicyTest {

  @Test
  fun masterClockNeverRebasesOntoPlayerPosition() {
    // The cases that used to yank the needle: player 1–2s behind, or still at 0.
    assertFalse(PlaybackSyncPolicy.shouldRebaseMasterClock(5_000L, 3_500L))
    assertFalse(PlaybackSyncPolicy.shouldRebaseMasterClock(1_500L, 0L))
    assertFalse(PlaybackSyncPolicy.shouldRebaseMasterClock(8_000L, 10_000L))
  }

  @Test
  fun smallDecoderLagDoesNotTriggerAKeyframeSeek() {
    assertFalse(
      PlaybackSyncPolicy.shouldCorrectSourceDrift(
        playerSourceMs = 4_800L,
        expectedSourceMs = 5_000L,
        playerReadyAndPlaying = true,
        millisSinceLastCorrection = 5_000L
      )
    )
    // The old 120ms threshold re-seeked to the previous keyframe on every tick.
    assertFalse(
      PlaybackSyncPolicy.shouldCorrectSourceDrift(
        playerSourceMs = 4_850L,
        expectedSourceMs = 5_000L,
        playerReadyAndPlaying = true,
        millisSinceLastCorrection = Long.MAX_VALUE
      )
    )
  }

  @Test
  fun largeDriftWaitsForCooldownThenSeeksOnce() {
    assertFalse(
      PlaybackSyncPolicy.shouldCorrectSourceDrift(
        playerSourceMs = 0L,
        expectedSourceMs = 4_000L,
        playerReadyAndPlaying = true,
        millisSinceLastCorrection = 100L
      )
    )
    assertFalse(
      PlaybackSyncPolicy.shouldCorrectSourceDrift(
        playerSourceMs = 0L,
        expectedSourceMs = 4_000L,
        playerReadyAndPlaying = false,
        millisSinceLastCorrection = 5_000L
      )
    )
    assertTrue(
      PlaybackSyncPolicy.shouldCorrectSourceDrift(
        playerSourceMs = 2_000L,
        expectedSourceMs = 4_000L,
        playerReadyAndPlaying = true,
        millisSinceLastCorrection = 900L
      )
    )
  }
}

class PlayheadInsertionTest {

  @Test
  fun newClipsLandAtTheCtiAndPlaybackResumesThere() {
    val engine = TimelineEngine()
    engine.addVideoClip(uri = "file:///main.mp4", name = "Main", durationMs = 10_000L)
    engine.seekTo(4_000L)

    val overlayId = engine.addOverlayClip(
      uri = "file:///pip.mp4",
      name = "Overlay",
      durationMs = 2_000L
    ).let { engine.timeline.value.overlayClips.last().id }
    engine.addAudioClip(title = "Voice", uri = "file:///voice.m4a", durationMs = 2_000L, waveformData = emptyList())
    engine.addTextClip(text = "Title", durationMs = 2_000L)

    assertEquals(4_000L, engine.timeline.value.overlayClips.first { it.id == overlayId }.timelineStartMs)
    assertEquals(4_000L, engine.timeline.value.audioClips.last().timelineStartMs)
    assertEquals(4_000L, engine.timeline.value.textClips.last().timelineStartMs)

    val inserted = engine.addVideoClip(uri = "file:///cut.mp4", name = "Insert", durationMs = 1_500L, atPlayhead = true)
    val clip = engine.timeline.value.videoClips.first { it.id == inserted }
    assertEquals(4_000L, clip.timelineStartMs)

    engine.setPosition(6_500L, snap = false)
    engine.pause()
    engine.play()
    assertEquals(6_500L, engine.currentPositionMs.value)
    assertTrue(engine.isPlaying.value)
  }
}
