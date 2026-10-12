package com.example.engine.composition.graph

import com.example.domain.model.TextClip
import com.example.domain.model.Timeline
import com.example.domain.model.VideoClip
import com.example.engine.composition.VideoCompositionEngine
import com.example.engine.preview.PreviewCompositorPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharedCompositionGraphTest {

  private val timeline = Timeline(
    videoClips = listOf(
      VideoClip(id = "v1", name = "V1", uri = "file:///v1.mp4", timelineStartMs = 0L, durationMs = 3000L, isVideo = true, sourceStartMs = 0L, sourceEndMs = 3000L)
    ),
    overlayClips = listOf(
      VideoClip(id = "o1", name = "O1", uri = "file:///o1.mp4", timelineStartMs = 500L, durationMs = 1500L, trackIndex = 1, isVideo = true, opacity = 0.8f, sourceStartMs = 0L, sourceEndMs = 1500L)
    ),
    textClips = listOf(
      TextClip(id = "t1", text = "Hello", timelineStartMs = 400L, durationMs = 2000L)
    )
  )

  @Test
  fun previewAndExportSampleTheSameClockAndLayers() {
    val graph = SharedCompositionGraph.evaluate(timeline, 1000L, fps = 30)
    val export = VideoCompositionEngine(RuntimeEnvironment.getApplication()).evaluateFrame(timeline, 1000L)
    assertEquals(1000L, graph.clockMs)
    assertEquals(export.timelinePosMs, graph.composedFrame.timelinePosMs)
    assertEquals(export.activeClip?.id, graph.composedFrame.activeClip?.id)
    assertEquals(export.clipSourcePosMs, graph.composedFrame.clipSourcePosMs)
    assertEquals(export.activeOverlays.map { it.clip.id }, graph.composedFrame.activeOverlays.map { it.clip.id })
    assertEquals(export.activeTexts.map { it.clip.id }, graph.composedFrame.activeTexts.map { it.clip.id })
    assertEquals(setOf("o1"), graph.overlayLayerIds)
    assertTrue(graph.layers.any { it.kind == CompositionLayer.Kind.MAIN_VIDEO && it.sourcePosMs == export.clipSourcePosMs })
    assertTrue(graph.layers.any { it.kind == CompositionLayer.Kind.OVERLAY && it.clipId == "o1" })
    assertTrue(graph.layers.any { it.kind == CompositionLayer.Kind.TEXT && it.clipId == "t1" })
  }

  @Test
  fun overlayLayersUseClipSourceTimeNotTheirOwnClock() {
    val graph = SharedCompositionGraph.evaluate(timeline, 1000L, fps = 30)
    val overlay = graph.layers.first { it.kind == CompositionLayer.Kind.OVERLAY }
    assertEquals("o1", overlay.clipId)
    assertEquals(500L, overlay.sourcePosMs)
  }

  @Test
  fun overlayExoPlayersAreDisabledForPreview() {
    assertFalse(PreviewCompositorPolicy.shouldAllocateOverlayExoPlayer())
    assertTrue(PreviewCompositorPolicy.shouldPresentOverlayOnClockTick())
  }
}
