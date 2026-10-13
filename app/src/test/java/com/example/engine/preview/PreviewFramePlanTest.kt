package com.example.engine.preview

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import com.example.domain.model.Timeline
import com.example.domain.model.TransitionType
import com.example.domain.model.VideoClip
import com.example.engine.composition.ComposedFrame
import com.example.engine.composition.ComposedOverlay
import com.example.engine.composition.ComposedTransition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewFramePlanTest {

  private fun clip(id: String, start: Long, dur: Long, uri: String = "file:///$id.mp4", isVideo: Boolean = true, sourceStart: Long = 0) =
    VideoClip(
      id = id, name = id, uri = uri, timelineStartMs = start, durationMs = dur,
      sourceStartMs = sourceStart, sourceEndMs = sourceStart + dur, isVideo = isVideo
    )

  private fun frame(
    pos: Long,
    main: VideoClip?,
    mainSourceMs: Long = 0,
    overlays: List<ComposedOverlay> = emptyList(),
    transition: ComposedTransition? = null
  ) = ComposedFrame(
    timelinePosMs = pos,
    activeClip = main,
    clipSourcePosMs = mainSourceMs,
    activeOverlays = overlays,
    activeTexts = emptyList(),
    activeStickers = emptyList(),
    activeTransition = transition,
    colorMatrix = ColorMatrix(),
    colorFilter = ColorMatrixColorFilter(ColorMatrix())
  )

  private fun overlay(c: VideoClip, sourceMs: Long) =
    ComposedOverlay(clip = c, sourcePosMs = sourceMs, posX = 0f, posY = 0f, scale = 1f, rotation = 0f, opacity = 1f, blendMode = "normal")

  @Test
  fun mainAndOverlaysAreDecodedAtTheirExactSourceTimeInPriorityOrder() {
    val main = clip("main", 0, 10_000)
    val pip = clip("pip", 1000, 5000)
    val plan = PreviewFramePlan.of(frame(2000, main, mainSourceMs = 2000, overlays = listOf(overlay(pip, 1000))))
    assertEquals(listOf("main", "pip"), plan.decodeClipIds)
    assertEquals(listOf(2_000_000L, 1_000_000L), plan.decodes.map { it.sourceUs })
    assertEquals(listOf(PreviewFramePlan.Role.MAIN, PreviewFramePlan.Role.OVERLAY), plan.decodes.map { it.role })
    assertTrue(plan.imageUris.isEmpty())
  }

  @Test
  fun stillImagesBecomeTexturesAndSyntheticClipsAreSkipped() {
    val photo = clip("photo", 0, 3000, uri = "content://media/photo.jpg", isVideo = false)
    val stock = clip("stock", 0, 3000, uri = "stock://beach")
    val plan = PreviewFramePlan.of(frame(100, photo, overlays = listOf(overlay(stock, 100))))
    assertTrue(plan.decodes.isEmpty())
    assertEquals(listOf("content://media/photo.jpg"), plan.imageUris)
  }

  @Test
  fun transitionDecodesTheIncomingSideAtItsOwnSourceTime() {
    val before = clip("a", 0, 2000)
    val after = clip("b", 2000, 2000, sourceStart = 5000)
    val tr = ComposedTransition(TransitionType.values().first { it != TransitionType.NONE }, 0.5f, before, after)
    // Playhead still on the outgoing clip; the incoming one is sampled from its source start.
    val plan = PreviewFramePlan.of(frame(1800, before, mainSourceMs = 1800, transition = tr))
    assertEquals(listOf("a", "b"), plan.decodeClipIds)
    assertEquals(5_000_000L, plan.decodes[1].sourceUs)
    assertEquals(PreviewFramePlan.Role.TRANSITION_AFTER, plan.decodes[1].role)
  }

  @Test
  fun noneTransitionAddsNoDecodes() {
    val before = clip("a", 0, 2000)
    val after = clip("b", 2000, 2000)
    val plan = PreviewFramePlan.of(frame(1800, before, transition = ComposedTransition(TransitionType.NONE, 0.5f, before, after)))
    assertEquals(listOf("a"), plan.decodeClipIds)
  }

  @Test
  fun syntheticSchemesAreRecognised() {
    assertTrue(PreviewFramePlan.isSynthetic(""))
    assertTrue(PreviewFramePlan.isSynthetic("sample://city"))
    assertTrue(PreviewFramePlan.isSynthetic("template://intro"))
    assertFalse(PreviewFramePlan.isSynthetic("file:///sdcard/a.mp4"))
    assertFalse(PreviewFramePlan.isSynthetic("content://media/external/video/1"))
  }

  @Test
  fun canCompositeOnlyRealPlayableProjects() {
    val real = Timeline(videoClips = listOf(clip("a", 0, 1000)), overlayClips = listOf(clip("b", 0, 1000)))
    assertTrue(PreviewFramePlan.canComposite(real) { true })
    assertFalse(PreviewFramePlan.canComposite(real) { it != "file:///b.mp4" })
    assertFalse(PreviewFramePlan.canComposite(Timeline()) { true })
    val withStock = real.copy(overlayClips = listOf(clip("s", 0, 1000, uri = "stock://x")))
    assertFalse(PreviewFramePlan.canComposite(withStock) { true })
  }

  @Test
  fun renderGateRedrawsOnNewFrameOrGeneration() {
    val gate = PreviewRenderGate()
    assertTrue(gate.shouldRender(0, 30, 0))
    assertFalse(gate.shouldRender(20, 30, 0))
    assertTrue(gate.shouldRender(34, 30, 0))
    assertTrue(gate.shouldRender(34, 30, 1))
    assertFalse(gate.shouldRender(34, 30, 1))
    gate.invalidate()
    assertTrue(gate.shouldRender(34, 30, 1))
  }
}
