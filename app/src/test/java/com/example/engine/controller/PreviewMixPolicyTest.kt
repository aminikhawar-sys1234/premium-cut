package com.example.engine.controller

import com.example.domain.model.AudioClip
import com.example.domain.model.Timeline
import com.example.domain.model.TrackSettings
import com.example.domain.model.TrackType
import com.example.domain.model.VideoClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live preview used to ignore the timeline's per-track mute / solo buttons while the
 * export mix honoured them. These tests pin the shared rule both paths now use.
 */
class PreviewMixPolicyTest {

  private fun clip(volume: Float = 1.0f, muted: Boolean = false) = VideoClip(
    id = "clip_1",
    name = "vid1.mp4",
    uri = "content://media/vid1.mp4",
    isVideo = true,
    durationMs = 4_000L,
    sourceEndMs = 4_000L,
    volume = volume,
    isMuted = muted
  )

  private fun timeline(vararg settings: TrackSettings) = Timeline(
    videoClips = listOf(clip()),
    trackSettings = settings.associateBy { it.type }
  )

  @Test
  fun defaultTrackStateIsVisibleAndAudible() {
    val tl = timeline(TrackSettings(TrackType.MAIN_VIDEO))
    assertTrue(PreviewMixPolicy.isTrackVisible(tl, TrackType.MAIN_VIDEO))
    assertTrue(PreviewMixPolicy.isTrackAudible(tl, TrackType.MAIN_VIDEO))
    assertEquals(1.0f, PreviewMixPolicy.clipGain(clip(), tl, TrackType.MAIN_VIDEO), 0.0001f)
  }

  @Test
  fun mutedVideoTrackSilencesTheMainLane() {
    val tl = timeline(TrackSettings(TrackType.MAIN_VIDEO, isMuted = true))
    assertFalse(PreviewMixPolicy.isTrackAudible(tl, TrackType.MAIN_VIDEO))
    assertEquals(0f, PreviewMixPolicy.clipGain(clip(), tl, TrackType.MAIN_VIDEO), 0.0001f)
  }

  @Test
  fun soloingAnotherTrackSilencesNonSoloedLanes() {
    val tl = timeline(
      TrackSettings(TrackType.MAIN_VIDEO),
      TrackSettings(TrackType.AUDIO, isSolo = true)
    )
    assertFalse(PreviewMixPolicy.isTrackAudible(tl, TrackType.MAIN_VIDEO))
    assertEquals(0f, PreviewMixPolicy.clipGain(clip(), tl, TrackType.MAIN_VIDEO), 0.0001f)
    assertTrue(PreviewMixPolicy.isTrackAudible(tl, TrackType.AUDIO))
  }

  @Test
  fun soloedMainTrackKeepsPlayingWhileTheOthersAreGated() {
    val tl = timeline(
      TrackSettings(TrackType.MAIN_VIDEO, isSolo = true),
      TrackSettings(TrackType.OVERLAY)
    )
    assertTrue(PreviewMixPolicy.isTrackAudible(tl, TrackType.MAIN_VIDEO))
    assertEquals(0.5f, PreviewMixPolicy.clipGain(clip(volume = 0.5f), tl, TrackType.MAIN_VIDEO), 0.0001f)
    assertFalse(PreviewMixPolicy.isTrackAudible(tl, TrackType.OVERLAY))
  }

  @Test
  fun clipMuteWinsOverAnAudibleTrack() {
    val tl = timeline(TrackSettings(TrackType.MAIN_VIDEO))
    assertEquals(0f, PreviewMixPolicy.clipGain(clip(muted = true), tl, TrackType.MAIN_VIDEO), 0.0001f)
  }

  @Test
  fun gainIsClampedToThePlayerVolumeRange() {
    val tl = timeline(TrackSettings(TrackType.MAIN_VIDEO))
    assertEquals(2f, PreviewMixPolicy.clipGain(clip(volume = 4f), tl, TrackType.MAIN_VIDEO), 0.0001f)
    assertEquals(0f, PreviewMixPolicy.clipGain(clip(volume = -1f), tl, TrackType.MAIN_VIDEO), 0.0001f)
  }

  @Test
  fun hidingAVideoTrackSwitchesThePictureOffButNotTheAudio() {
    val tl = timeline(TrackSettings(TrackType.MAIN_VIDEO, isHidden = true))
    assertFalse(PreviewMixPolicy.isTrackVisible(tl, TrackType.MAIN_VIDEO))
    // Same rule as the export mix: a hidden video track still contributes its audio.
    assertTrue(PreviewMixPolicy.isTrackAudible(tl, TrackType.MAIN_VIDEO))
    assertEquals(1.0f, PreviewMixPolicy.clipGain(clip(), tl, TrackType.MAIN_VIDEO), 0.0001f)
  }

  @Test
  fun audioLaneHonoursTrackGateClipSoloAndClipMute() {
    val audioClip = AudioClip(
      id = "audio_1",
      uri = "content://media/voice.m4a",
      title = "voice",
      durationMs = 2_000L,
      volume = 0.8f,
      isSolo = true
    )
    val audible = timeline(TrackSettings(TrackType.AUDIO))
    assertEquals(0.8f, PreviewMixPolicy.audioClipGain(audioClip, audible, hasClipSolo = true), 0.0001f)

    val mutedTrack = timeline(TrackSettings(TrackType.AUDIO, isMuted = true))
    assertEquals(0f, PreviewMixPolicy.audioClipGain(audioClip, mutedTrack, hasClipSolo = true), 0.0001f)

    // No clip is soloed: the clip keeps playing at its own gain.
    assertEquals(0.8f, PreviewMixPolicy.audioClipGain(audioClip, audible, hasClipSolo = false), 0.0001f)

    // Another audio clip is soloed: a non-soloed clip is silenced.
    val notSoloed = audioClip.copy(isSolo = false)
    assertEquals(0f, PreviewMixPolicy.audioClipGain(notSoloed, audible, hasClipSolo = true), 0.0001f)

    val mutedClip = audioClip.copy(isMuted = true)
    assertEquals(0f, PreviewMixPolicy.audioClipGain(mutedClip, audible, hasClipSolo = true), 0.0001f)
  }

  @Test
  fun mixSignatureReactsToFlagChangesOnly() {
    val base = timeline(TrackSettings(TrackType.MAIN_VIDEO), TrackSettings(TrackType.OVERLAY))
    val sameAgain = timeline(TrackSettings(TrackType.MAIN_VIDEO), TrackSettings(TrackType.OVERLAY))
    val muted = timeline(TrackSettings(TrackType.MAIN_VIDEO, isMuted = true), TrackSettings(TrackType.OVERLAY))
    val soloed = timeline(TrackSettings(TrackType.MAIN_VIDEO), TrackSettings(TrackType.OVERLAY, isSolo = true))

    assertEquals(PreviewMixPolicy.mixSignature(base), PreviewMixPolicy.mixSignature(sameAgain))
    assertNotEquals(PreviewMixPolicy.mixSignature(base), PreviewMixPolicy.mixSignature(muted))
    assertNotEquals(PreviewMixPolicy.mixSignature(base), PreviewMixPolicy.mixSignature(soloed))
  }
}
