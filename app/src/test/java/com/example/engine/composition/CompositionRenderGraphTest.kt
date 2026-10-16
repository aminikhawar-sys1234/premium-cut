package com.example.engine.composition

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import com.example.domain.model.TextClip
import com.example.domain.model.TransitionType
import com.example.domain.model.VideoClip
import com.example.engine.preview.PreviewFramePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositionRenderGraphTest {

  private fun clip(id: String, start: Long, dur: Long, uri: String = "file:///$id.mp4", isVideo: Boolean = true, sourceStart: Long = 0, width: Int = 1920, height: Int = 1080) =
    VideoClip(
      id = id, name = id, uri = uri, timelineStartMs = start, durationMs = dur,
      sourceStartMs = sourceStart, sourceEndMs = sourceStart + dur, isVideo = isVideo,
      width = width, height = height
    )

  private fun frame(
    pos: Long,
    main: VideoClip?,
    mainSourceMs: Long = 0,
    overlays: List<ComposedOverlay> = emptyList(),
    transition: ComposedTransition? = null,
    texts: List<ComposedText> = emptyList()
  ) = ComposedFrame(
    timelinePosMs = pos,
    activeClip = main,
    clipSourcePosMs = mainSourceMs,
    activeOverlays = overlays,
    activeTexts = texts,
    activeStickers = emptyList(),
    activeTransition = transition,
    colorMatrix = ColorMatrix(),
    colorFilter = ColorMatrixColorFilter(ColorMatrix())
  )

  private fun overlay(c: VideoClip, sourceMs: Long) =
    ComposedOverlay(clip = c, sourcePosMs = sourceMs, posX = 0f, posY = 0f, scale = 1f, rotation = 0f, opacity = 1f, blendMode = "normal")

  private fun layer(id: Int, oes: Boolean = true, w: Int = 0, h: Int = 0) =
    CompositionRenderGraph.ResolvedLayer(id, oes, floatArrayOf(id.toFloat()), w, h)

  @Test
  fun layersAreSampledAtTheSameSourceTimesThePreviewDecodes() {
    val main = clip("main", 0, 10_000)
    val pip = clip("pip", 1000, 5000)
    val evaluated = frame(2000, main, mainSourceMs = 2000, overlays = listOf(overlay(pip, 1000)))
    val layers = CompositionRenderGraph.layersOf(evaluated)
    assertEquals(listOf(CompositionRenderGraph.LayerRole.MAIN, CompositionRenderGraph.LayerRole.OVERLAY), layers.map { it.role })
    assertEquals(listOf(2_000_000L, 1_000_000L), layers.map { it.sourceUs })

    val plan = PreviewFramePlan.of(evaluated)
    assertEquals(layers.map { it.sourceUs }, plan.decodes.map { it.sourceUs })
    assertEquals(layers.map { it.clip.id }, plan.decodeClipIds)
  }

  @Test
  fun transitionSamplesTheIncomingClipFromItsOwnSource() {
    val before = clip("a", 0, 2000)
    val after = clip("b", 2000, 2000, sourceStart = 5000)
    val tr = ComposedTransition(TransitionType.values().first { it != TransitionType.NONE }, 0.5f, before, after)
    val layers = CompositionRenderGraph.layersOf(frame(1800, before, mainSourceMs = 1800, transition = tr))
    assertEquals(listOf("a", "a", "b"), layers.map { it.clip.id })
    assertEquals(5_000_000L, layers.last().sourceUs)
    assertEquals(CompositionRenderGraph.LayerRole.TRANSITION_AFTER, layers.last().role)
  }

  @Test
  fun noneTransitionAddsNoExtraLayers() {
    val before = clip("a", 0, 2000)
    val after = clip("b", 2000, 2000)
    val layers = CompositionRenderGraph.layersOf(
      frame(1800, before, transition = ComposedTransition(TransitionType.NONE, 0.5f, before, after))
    )
    assertEquals(listOf("a"), layers.map { it.clip.id })
  }

  @Test
  fun assembleReusesTheMainPictureForItsTransitionSideAndPatchesUnknownSizes() {
    val main = clip("a", 0, 2000, width = 0, height = 0)
    val incoming = clip("b", 2000, 2000)
    val pip = clip("pip", 0, 2000, width = 0, height = 0)
    val tr = ComposedTransition(TransitionType.values().first { it != TransitionType.NONE }, 0.4f, main, incoming)
    val evaluated = frame(1000, main, mainSourceMs = 1000, overlays = listOf(overlay(pip, 1000)), transition = tr)
    val assembled = CompositionRenderGraph.assemble(
      frame = evaluated,
      decoded = mapOf("a" to layer(11, w = 1280, h = 720), "pip" to layer(22, oes = false, w = 640, h = 360)),
      imageTextureId = { 0 },
      drawTextAndStickers = true
    )
    assertEquals(11, assembled.mainTextureId)
    assertTrue(assembled.isMainOes)
    assertEquals(11, assembled.transitionBefore?.textureId)
    assertTrue(assembled.transitionBefore?.isOes == true)
    assertNull(assembled.transitionAfter)
    assertEquals(22, assembled.overlayTextures["pip"])
    assertEquals(false, assembled.overlayIsOes["pip"])
    assertEquals(1280, assembled.frame.activeClip?.width)
    assertEquals(720, assembled.frame.activeClip?.height)
    assertEquals(640, assembled.frame.activeOverlays.single().clip.width)
  }

  @Test
  fun stillImagesComeFromTheImageLookupAndVideoMissesStayEmpty() {
    val photo = clip("photo", 0, 3000, uri = "content://media/photo.jpg", isVideo = false)
    val video = clip("vid", 0, 3000)
    val photoFrame = CompositionRenderGraph.assemble(frame(0, photo), emptyMap(), { uri -> if (uri.endsWith("jpg")) 9 else 0 }, true)
    assertEquals(9, photoFrame.mainTextureId)
    assertFalse(photoFrame.isMainOes)

    val missed = CompositionRenderGraph.assemble(frame(0, video), emptyMap(), { 9 }, true)
    assertEquals(0, missed.mainTextureId)
  }

  @Test
  fun textAndStickersAreADestinationChoice() {
    val main = clip("a", 0, 1000)
    val evaluated = frame(0, main, texts = listOf(ComposedText(TextClip(text = "Hi"), 0f, 0f, 1f, 0f, 1f)))
    val preview = CompositionRenderGraph.assemble(evaluated, mapOf("a" to layer(1)), { 0 }, drawTextAndStickers = false)
    val export = CompositionRenderGraph.assemble(evaluated, mapOf("a" to layer(1)), { 0 }, drawTextAndStickers = true)
    assertTrue(preview.frame.activeTexts.isEmpty())
    assertEquals("Hi", export.frame.activeTexts.single().clip.text)
  }

  @Test
  fun presentationIsTheOnlyPreviewExportDifferenceInTheBlit() {
    val preview = CompositionRenderGraph.FramePresentation.PREVIEW
    // The export passes the policy's encoder flip. It must be the preview's: both destinations are
    // GLES-fed BufferQueues with the same orientation, so a differing Y flip rotates the export.
    val export = CompositionRenderGraph.FramePresentation.export(
      flipY = com.example.engine.export.ExportOrientationPolicy.FLIP_Y_FOR_ENCODER
    )
    assertFalse(preview.flipY)
    assertFalse(preview.deterministicMasks)
    assertFalse("the export blit must not flip Y", export.flipY)
    assertFalse(export.flipX)
    assertTrue(export.deterministicMasks)
    assertEquals("only the mask determinism may differ", preview.flipY, export.flipY)
  }
}
