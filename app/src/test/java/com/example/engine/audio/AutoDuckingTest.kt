package com.example.engine.audio

import com.example.data.local.TimelineSerializer
import com.example.domain.model.AudioClip
import com.example.domain.model.AudioDuckingSettings
import com.example.domain.model.AudioTrackRole
import com.example.domain.model.ClipKeyframe
import com.example.domain.model.Timeline
import com.example.domain.model.TrackType
import com.example.domain.model.VideoClip
import com.example.engine.TimelineEngine
import com.example.engine.export.AudioTrackDescriptor
import com.example.engine.export.exportMixLinearGain
import com.example.engine.timeline.TimelineClipQueries
import com.example.engine.timeline.TimelineTrackManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoDuckingTest {

  private val bgm = AudioClip(id = "bgm", uri = "content://music.mp3", title = "Music", timelineStartMs = 0L, durationMs = 20_000L, sourceEndMs = 20_000L, trackIndex = 1)

  private fun voice(id: String, start: Long, end: Long, lane: Int = 0, isVoiceOver: Boolean = true, muted: Boolean = false) =
    AudioClip(id = id, uri = "content://$id.m4a", title = id, timelineStartMs = start, durationMs = end - start, sourceEndMs = end - start, trackIndex = lane, isVoiceOver = isVoiceOver, isMuted = muted)

  private fun ducking(ratio: Float = 0.25f, attack: Long = 200L, release: Long = 400L, underVideo: Boolean = false) =
    AudioDuckingSettings(targetTrackIndex = 1, duckRatio = ratio, attackMs = attack, releaseMs = release, duckUnderVideoAudio = underVideo)

  private fun timeline(vararg voices: AudioClip, settings: AudioDuckingSettings? = ducking(), videos: List<VideoClip> = emptyList()) =
    Timeline(videoClips = videos, audioClips = listOf(bgm) + voices, audioDucking = listOfNotNull(settings))

  private fun gain(tl: Timeline, posMs: Long) = AutoDucking.gainAt(tl, bgm, posMs)

  @Test
  fun laneWithoutDuckingIsUntouched() {
    val tl = timeline(voice("vo", 5_000L, 8_000L), settings = null)
    assertEquals(1f, gain(tl, 6_000L), 0f)
  }

  @Test
  fun attackLooksAheadHoldsAndReleases() {
    val tl = timeline(voice("vo", 5_000L, 8_000L))
    assertEquals(1f, gain(tl, 4_000L), 1e-4f)
    assertEquals(1f, gain(tl, 4_800L), 1e-4f)
    // Halfway through the S-curve the depth is 0.5: 0.25^0.5 = 0.5 (half the dB attenuation).
    assertEquals(0.5f, gain(tl, 4_900L), 1e-3f)
    assertEquals(0.25f, gain(tl, 5_000L), 1e-4f)
    assertEquals(0.25f, gain(tl, 6_500L), 1e-4f)
    assertEquals(0.25f, gain(tl, 8_000L), 1e-4f)
    assertEquals(0.5f, gain(tl, 8_200L), 1e-3f)
    assertEquals(1f, gain(tl, 8_400L), 1e-4f)
  }

  @Test
  fun shortGapsStayDuckedLongGapsRecover() {
    val bridged = timeline(voice("a", 1_000L, 2_000L), voice("b", 2_300L, 3_000L))
    assertEquals(0.25f, gain(bridged, 2_150L), 1e-4f)
    assertEquals(1, AutoDucking.speechIntervals(bridged, ducking()).size)

    val separate = timeline(voice("a", 1_000L, 2_000L), voice("b", 4_000L, 5_000L))
    assertEquals(1f, gain(separate, 3_000L), 1e-4f)
    assertEquals(
      listOf(SpeechInterval(1_000L, 2_000L), SpeechInterval(4_000L, 5_000L)),
      AutoDucking.speechIntervals(separate, ducking())
    )
  }

  @Test
  fun zeroAttackAndReleaseSwitchInstantly() {
    val tl = timeline(voice("vo", 5_000L, 8_000L), settings = ducking(attack = 0L, release = 0L))
    assertEquals(1f, gain(tl, 4_999L), 0f)
    assertEquals(0.25f, gain(tl, 5_000L), 1e-4f)
    assertEquals(1f, gain(tl, 8_001L), 0f)
  }

  @Test
  fun zeroRatioFadesToSilence() {
    val tl = timeline(voice("vo", 5_000L, 8_000L), settings = ducking(ratio = 0f))
    assertEquals(0f, gain(tl, 6_000L), 0f)
    assertEquals(0.5f, gain(tl, 4_900L), 1e-3f)
  }

  @Test
  fun inaudibleOrNonSpeechClipsDoNotTrigger() {
    assertEquals(1f, gain(timeline(voice("vo", 5_000L, 8_000L, muted = true)), 6_000L), 0f)
    assertEquals(1f, gain(timeline(voice("sfx", 5_000L, 8_000L, isVoiceOver = false)), 6_000L), 0f)

    val mutedAudioTrack = timeline(voice("vo", 5_000L, 8_000L)).let {
      it.copy(trackSettings = it.trackSettings + (TrackType.AUDIO to it.trackSettings.getValue(TrackType.AUDIO).copy(isMuted = true)))
    }
    assertEquals(1f, gain(mutedAudioTrack, 6_000L), 0f)
  }

  @Test
  fun laneRolesOverrideTheVoiceoverFlag() {
    val dialogue = timeline(voice("d", 5_000L, 8_000L, isVoiceOver = false))
      .let { AutoDucking.withRole(it, 0, AudioTrackRole.DIALOGUE) }
    assertEquals(0.25f, gain(dialogue, 6_000L), 1e-4f)

    val music = timeline(voice("vo", 5_000L, 8_000L))
      .let { AutoDucking.withRole(it, 0, AudioTrackRole.MUSIC) }
    assertEquals(1f, gain(music, 6_000L), 0f)
  }

  @Test
  fun duckedLanesNeverTriggerEachOther() {
    val voiceOnBgmLane = voice("vo", 5_000L, 8_000L, lane = 1)
    assertEquals(1f, gain(timeline(voiceOnBgmLane), 6_000L), 0f)
  }

  @Test
  fun primaryVideoAudioTriggersWhenEnabled() {
    val video = VideoClip(id = "v", uri = "content://v.mp4", name = "v", timelineStartMs = 2_000L, durationMs = 3_000L, sourceEndMs = 3_000L)
    assertEquals(0.25f, gain(timeline(settings = ducking(underVideo = true), videos = listOf(video)), 3_000L), 1e-4f)
    assertEquals(1f, gain(timeline(settings = ducking(underVideo = false), videos = listOf(video)), 3_000L), 0f)
    val muted = video.copy(isMuted = true)
    assertEquals(1f, gain(timeline(settings = ducking(underVideo = true), videos = listOf(muted)), 3_000L), 0f)
  }

  @Test
  fun effectiveVolumeMultipliesExistingKeyframes() {
    val keyed = bgm.copy(keyframes = listOf(ClipKeyframe(timeMs = 0L, volume = 0.8f), ClipKeyframe(timeMs = 20_000L, volume = 0.8f)))
    val tl = Timeline(audioClips = listOf(keyed, voice("vo", 5_000L, 8_000L)), audioDucking = listOf(ducking()))
    assertEquals(0.8f, TimelineClipQueries.getEffectiveAudioVolumeAt(tl, keyed, 2_000L), 1e-3f)
    assertEquals(0.2f, TimelineClipQueries.getEffectiveAudioVolumeAt(tl, keyed, 6_000L), 1e-3f)
  }

  @Test
  fun clipEnvelopeIsRelativeToClipAndCoversRamps() {
    val shifted = bgm.copy(timelineStartMs = 1_000L)
    val tl = Timeline(audioClips = listOf(shifted, voice("vo", 5_000L, 8_000L)), audioDucking = listOf(ducking()))
    val points = AutoDucking.clipEnvelope(tl, shifted, rampStepMs = 50L)
    assertEquals(0L, points.first().relTimeMs)
    assertEquals(1f, points.first().gain, 0f)
    assertEquals(20_000L, points.last().relTimeMs)
    assertTrue(points.any { it.relTimeMs == 4_000L && kotlin.math.abs(it.gain - 0.25f) < 1e-4f })
    assertTrue(points.zipWithNext().all { (a, b) -> a.relTimeMs < b.relTimeMs })
    assertTrue(AutoDucking.clipEnvelope(timeline(voice("vo", 50_000L, 51_000L)), bgm).isEmpty())
  }

  @Test
  fun exportGainAppliesDuckInTimelineTime() {
    val tl = timeline(voice("vo", 5_000L, 8_000L))
    val track = AudioTrackDescriptor(
      uri = bgm.uri, title = bgm.title, timelineStartMs = 0L, durationMs = 20_000L,
      sourceStartMs = 0L, sourceEndMs = 20_000L, speed = 1f, volume = 1f, gainDb = 0f,
      fadeInMs = 0L, fadeOutMs = 0L, isMuted = false,
      duckEnvelope = AutoDucking.envelopeFor(tl, 1)
    )
    assertEquals(1f, exportMixLinearGain(track, 1_000, 20 * 1_000, 1_000), 1e-4f)
    assertEquals(0.25f, exportMixLinearGain(track, 6_000, 20 * 1_000, 1_000), 1e-4f)
  }

  @Test
  fun laneRemovalAndSwapKeepDuckingAttachedToItsLane() {
    val tl = timeline(voice("vo", 5_000L, 8_000L))
    val swapped = TimelineTrackManager.swapLanes(tl, TrackType.AUDIO, 0, 1)!!
    assertEquals(0, swapped.audioDucking.single().targetTrackIndex)
    assertEquals(0.25f, AutoDucking.gainAt(swapped, swapped.audioClips.first { it.id == "bgm" }, 6_000L), 1e-4f)

    val removed = TimelineTrackManager.removeLane(tl, TrackType.AUDIO, 1)!!
    assertTrue(removed.audioDucking.isEmpty())
  }

  @Test
  fun settingsSurviveSerialization() {
    val tl = AutoDucking.withRole(timeline(voice("vo", 5_000L, 8_000L)), 0, AudioTrackRole.VOICEOVER)
    val restored = TimelineSerializer.fromJsonOrNull(TimelineSerializer.serializeTimeline(tl))!!
    assertEquals(tl.audioDucking, restored.audioDucking)
    assertEquals(tl.audioLaneRoles, restored.audioLaneRoles)
  }

  @Test
  fun engineEnableDisableAreUndoable() {
    val engine = TimelineEngine()
    engine.loadTimeline(Timeline(audioClips = listOf(bgm, voice("vo", 5_000L, 8_000L))))

    assertTrue(engine.enableAutoDucking(1, duckIntensity = 0.25f, attackMs = 200L, releaseMs = 400L))
    assertEquals(0.25f, engine.getEffectiveAudioVolumeAt(bgm, 6_000L), 1e-4f)
    assertFalse(engine.enableAutoDucking(1, duckIntensity = 0.25f, attackMs = 200L, releaseMs = 400L))

    assertTrue(engine.undo())
    assertFalse(engine.isAutoDuckingEnabled(1))
    assertEquals(1f, engine.getEffectiveAudioVolumeAt(bgm, 6_000L), 1e-4f)

    assertTrue(engine.redo())
    assertEquals(0.25f, engine.getAutoDuckingSettings(1)!!.duckRatio, 0f)

    assertTrue(engine.disableAutoDucking(1))
    assertFalse(engine.disableAutoDucking(1))
    assertEquals(1f, engine.getEffectiveAudioVolumeAt(bgm, 6_000L), 1e-4f)
    assertTrue(engine.undo())
    assertTrue(engine.isAutoDuckingEnabled(1))
  }

  @Test
  fun engineRejectsInvalidTargetsAndClampsSettings() {
    val engine = TimelineEngine()
    engine.loadTimeline(Timeline(audioClips = listOf(bgm, voice("vo", 5_000L, 8_000L))))
    assertFalse(engine.enableAutoDucking(-1))
    assertFalse(engine.enableAutoDucking(1, duckIntensity = Float.NaN))

    assertTrue(engine.enableAutoDucking(1, duckIntensity = 3f, attackMs = -10L, releaseMs = 60_000L))
    val settings = engine.getAutoDuckingSettings(1)!!
    assertEquals(1f, settings.duckRatio, 0f)
    assertEquals(0L, settings.attackMs)
    assertEquals(AudioDuckingSettings.MAX_RAMP_MS, settings.releaseMs)
  }

  @Test
  fun speechRoleTurnsDuckingOffInOneUndoStep() {
    val engine = TimelineEngine()
    engine.loadTimeline(Timeline(audioClips = listOf(bgm, voice("vo", 5_000L, 8_000L))))
    assertTrue(engine.enableAutoDucking(1))

    assertTrue(engine.setAudioTrackRole(1, AudioTrackRole.DIALOGUE))
    assertFalse(engine.isAutoDuckingEnabled(1))
    assertFalse(engine.enableAutoDucking(1))

    assertTrue(engine.undo())
    assertEquals(AudioTrackRole.UNSPECIFIED, engine.getAudioTrackRole(1))
    assertTrue(engine.isAutoDuckingEnabled(1))
    assertNull(engine.getAutoDuckingSettings(0))
  }
}
