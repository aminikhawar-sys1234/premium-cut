package com.example.engine

import com.example.domain.model.AudioClip
import com.example.domain.model.EditorToolItem
import com.example.domain.model.MaskSettings
import com.example.domain.model.MaskShape
import com.example.domain.model.Timeline
import com.example.domain.model.VideoClip
import com.example.ui.EditorToolbarTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPipelineIntegrationTest {
  @Test
  fun overlayActionOpensOverlayPanel() {
    val tool = EditorToolItem("tool_overlays", "Overlay", "TOOL_OVERLAYS", "layers", 3, true)
    assertEquals(EditorToolbarTab.OVERLAY, tool.mappedTab)
  }

  @Test
  fun enhanceVoiceAppliesToSelectedAudioNotText() {
    val engine = TimelineEngine()
    engine.loadTimeline(
      Timeline(
        videoClips = listOf(VideoClip(id = "v1", name = "V", timelineStartMs = 0L, durationMs = 4000L)),
        audioClips = listOf(AudioClip(id = "a1", uri = "file:///tmp/a.wav", title = "A", timelineStartMs = 0L, durationMs = 4000L))
      )
    )
    engine.selectElement(SelectedTrackElement.Text("missing"))
    assertFalse(engine.enhanceSelectedAudio())

    engine.selectElement(SelectedTrackElement.Audio("a1"))
    assertTrue(engine.enhanceSelectedAudio())
    val audio = engine.timeline.value.audioClips.first { it.id == "a1" }
    assertTrue(audio.audioEffects.normalizeVolume)
    assertEquals(12f, audio.audioEffects.noiseReductionDb, 0.01f)
  }

  @Test
  fun maskSettingsPersistOnSelectedVideoClip() {
    val engine = TimelineEngine()
    engine.loadTimeline(
      Timeline(videoClips = listOf(VideoClip(id = "v1", name = "V", timelineStartMs = 0L, durationMs = 4000L)))
    )
    engine.selectElement(SelectedTrackElement.Video("v1"))
    val mask = MaskSettings(enabled = true, shape = MaskShape.CIRCLE, width = 0.4f, height = 0.4f)
    assertTrue(engine.setClipMask("v1", mask))
    val stored = engine.timeline.value.videoClips.first { it.id == "v1" }.mask
    assertTrue(stored.enabled)
    assertEquals(MaskShape.CIRCLE, stored.shape)
    assertEquals(0.4f, stored.width, 0.001f)
  }

  @Test
  fun splitKeepsSourceOffsetOnBothHalves() {
    val engine = TimelineEngine()
    engine.loadTimeline(
      Timeline(
        videoClips = listOf(
          VideoClip(
            id = "v1",
            name = "V",
            timelineStartMs = 0L,
            durationMs = 4000L,
            sourceStartMs = 500L,
            sourceEndMs = 4500L
          )
        )
      )
    )
    engine.selectElement(SelectedTrackElement.Video("v1"))
    engine.setPosition(2000L)
    assertTrue(engine.splitSelectedClipAtPlayhead())
    val clips = engine.timeline.value.videoClips.sortedBy { it.timelineStartMs }
    assertEquals(2, clips.size)
    assertEquals(500L, clips[0].sourceStartMs)
    assertEquals(2500L, clips[1].sourceStartMs)
    assertEquals(2000L, clips[0].durationMs)
    assertEquals(2000L, clips[1].durationMs)
  }
}
