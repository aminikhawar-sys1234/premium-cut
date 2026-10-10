package com.example.engine.export

import com.example.domain.model.AudioClip
import com.example.domain.model.Timeline
import com.example.domain.model.TrackSettings
import com.example.domain.model.TrackType
import com.example.domain.model.VideoClip
import com.example.engine.composition.gpu.NativeLayer
import com.example.engine.timeline.TimelineEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])

class RenderExportPipelineAuditTest {

  @Test
  fun placeholderUrisAreNotExportable() {
    assertTrue(isPlaceholderCatalogUri(""))
    assertTrue(isPlaceholderCatalogUri("internal://beat"))
    assertTrue(isPlaceholderCatalogUri("demo://clip"))
    assertTrue(isPlaceholderCatalogUri("built_in_sfx_pop"))
    assertFalse(isPlaceholderCatalogUri("content://media/audio.m4a"))
    assertFalse(isPlaceholderCatalogUri("/sdcard/music.mp3"))
    assertFalse(isExportableAudioUri("sfx_pop"))
    assertFalse(isExportableAudioUri("internal://beat"))
    assertTrue(isExportableAudioUri("content://media/audio.m4a"))
    assertTrue(isExportableAudioUri("file:///tmp/a.wav"))
    assertTrue(isExportableAudioUri("/data/user/0/app/files/a.wav"))
  }

  @Test
  fun hasActiveAudioIgnoresCatalogPlaceholdersAndMutedTracks() {
    val processor = AudioExportProcessor()
    val placeholder = Timeline(
      videoClips = listOf(
        VideoClip(name = "v", uri = "sfx_pop", isVideo = true, hasAudio = true, durationMs = 1000L)
      ),
      audioClips = listOf(
        AudioClip(title = "catalog", uri = "internal://beat", durationMs = 1000L)
      )
    )
    assertFalse(processor.hasActiveAudio(placeholder))

    val real = Timeline(
      audioClips = listOf(
        AudioClip(title = "music", uri = "content://media/song.m4a", durationMs = 1000L, volume = 1f)
      )
    )
    assertTrue(processor.hasActiveAudio(real))

    val muted = Timeline(
      audioClips = listOf(
        AudioClip(title = "music", uri = "content://media/song.m4a", durationMs = 1000L, volume = 1f)
      ),
      trackSettings = mapOf(TrackType.AUDIO to TrackSettings(TrackType.AUDIO, isMuted = true))
    )
    assertFalse(processor.hasActiveAudio(muted))
  }

  @Test
  fun hiddenAudioTrackStillEvaluatesAudibleClips() {
    val clip = AudioClip(
      id = "a1",
      title = "VO",
      uri = "content://media/vo.m4a",
      timelineStartMs = 0L,
      durationMs = 2000L,
      volume = 1f
    )
    val hidden = Timeline(
      audioClips = listOf(clip),
      trackSettings = mapOf(TrackType.AUDIO to TrackSettings(TrackType.AUDIO, isHidden = true))
    )
    val state = TimelineEvaluator().evaluate(hidden, 500L)
    assertEquals(1, state.activeAudios.size)

    val muted = Timeline(
      audioClips = listOf(clip),
      trackSettings = mapOf(TrackType.AUDIO to TrackSettings(TrackType.AUDIO, isMuted = true))
    )
    assertTrue(TimelineEvaluator().evaluate(muted, 500L).activeAudios.isEmpty())
  }

  @Test
  fun nativeComposeLayersDefaultToTexture2dNotOes() {
    val layer = NativeLayer(textureId = 7)
    assertFalse(
      "Kotlin already converts decoder OES frames to GL_TEXTURE_2D before native compose",
      layer.isExternal
    )
  }

  @Test
  fun templatesDoNotSeedInventedCaptionsOrFakeAudio() {
    com.example.data.presets.TemplatesCatalog.templates.forEach { template ->
      template.textPlaceholders.forEach { slot ->
        assertTrue("Template '${template.title}' slot ${slot.slotId} must not invent default copy", slot.defaultText.isEmpty())
      }
      val timeline = template.createDefaultTimeline()
      timeline.audioClips.forEach { clip ->
        assertTrue(
          "Template '${template.title}' audio '${clip.title}' must not use a fake catalog URI",
          clip.uri.isEmpty() || isExportableAudioUri(clip.uri)
        )
        assertFalse(isPlaceholderCatalogUri(clip.uri) && clip.uri.isNotEmpty())
      }
      timeline.textClips.forEach { clip ->
        assertTrue(
          "Unfilled template '${template.title}' must not render invented captions",
          clip.text.isEmpty()
        )
      }
    }
  }

  @Test
  fun exportProgressMappingStaysInTheLiveRing() {
    assertEquals(0.05f, ExportProgressMapping.encodingFraction(0, 100), 0.0001f)
    val mid = ExportProgressMapping.encodingFraction(50, 100)
    assertTrue(mid > 0.05f && mid < 0.95f)
    assertTrue(ExportProgressMapping.encodingFraction(100, 100) <= 0.95f)
  }
}
