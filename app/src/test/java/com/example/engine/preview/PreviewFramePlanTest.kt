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
    assertTrue(PreviewFramePlan.canComposite(real, 0) { true })
    // The predicate is per CLIP now: the caller resolves the proxy / relink uri before probing,
    // so a raw uri must never decide the gate by itself.
    assertFalse(PreviewFramePlan.canComposite(real, 0) { it.uri != "file:///b.mp4" })
    assertFalse(PreviewFramePlan.canComposite(Timeline(), 0) { true })
    val withStock = real.copy(overlayClips = listOf(clip("s", 0, 1000, uri = "stock://x")))
    assertFalse(PreviewFramePlan.canComposite(withStock, 0) { true })
  }

  @Test
  fun canCompositeJudgesEachClipByItsOwnUri() {
    // Mirrors the engine's resolver: a raw uri that is not playable but whose proxy uri IS playable
    // must count as playable (the compositor decodes the proxy, not the raw project uri).
    val raw = clip("raw", 0, 1000, uri = "content://media/raw-video")
    val proxied = raw.copy(id = "proxied", uri = "content://media/proxied-video")
    val resolved = mapOf("content://media/raw-video" to "file:///cache/proxy/raw.mp4")
    val timeline = Timeline(videoClips = listOf(raw, proxied))
    assertTrue(
      PreviewFramePlan.canComposite(timeline, 0) { clip ->
        val playable = resolved[clip.uri] ?: clip.uri
        playable == "file:///cache/proxy/raw.mp4" || clip.id == "proxied"
      }
    )
  }

  @Test
  fun brokenClipFarFromThePlayheadDoesNotBlockTheCompositor() {
    // The whole-timeline all-clips probe used to latch the compositor off (and with it every
    // registry effect) for projects where playback worked fine, because ONE far-away source
    // failed the static file probe. Playability now gates only the visible frame's sources.
    val good = clip("good", 0, 1000)
    val broken = clip("broken", 1000, 1000)
    val timeline = Timeline(videoClips = listOf(good, broken))
    // Playhead on the good clip: the broken clip is off-screen, the compositor still enters.
    assertTrue(PreviewFramePlan.canComposite(timeline, 500) { it.id == "good" })
    // Playhead ON the broken clip: the compositor cannot decode the visible frame.
    assertFalse(PreviewFramePlan.canComposite(timeline, 1500) { it.id == "good" })
  }

  @Test
  fun brokenOverlayAwayFromThePlayheadDoesNotBlockButVisibleOneDoes() {
    val main = clip("main", 0, 4000)
    val overlayHere = clip("ovHere", 0, 1000)
    val overlayFar = clip("ovFar", 3000, 1000)
    val timeline = Timeline(videoClips = listOf(main), overlayClips = listOf(overlayHere, overlayFar))
    // Playhead inside ovHere: far overlay is off-screen, its probe failure must not matter.
    assertTrue(PreviewFramePlan.canComposite(timeline, 500) { it.id != "ovFar" })
    // Playhead inside ovFar: it decodes now, so the gate correctly refuses.
    assertFalse(PreviewFramePlan.canComposite(timeline, 3500) { it.id != "ovFar" })
  }

  @Test
  fun clipsVisibleAtReturnsActiveClipAndPlayheadOverlays() {
    val a = clip("a", 0, 1000)
    val b = clip("b", 1000, 1000)
    val c = clip("c", 2000, 1000)
    val ov = clip("ov", 1200, 300)
    val timeline = Timeline(videoClips = listOf(c, a, b), overlayClips = listOf(ov))
    // ov spans [1200, 1500): the playhead sits inside it at 1400.
    val midB = PreviewFramePlan.clipsVisibleAt(timeline, 1400).map { it.id }
    // Active clip + the overlay covering the playhead (transition sides are runtime-owned).
    assertEquals(listOf("b", "ov"), midB)
    val onA = PreviewFramePlan.clipsVisibleAt(timeline, 100).map { it.id }
    assertEquals(listOf("a"), onA)
    // Past the end: falls back to the last main clip.
    val tail = PreviewFramePlan.clipsVisibleAt(timeline, 9_999).map { it.id }
    assertEquals(listOf("c"), tail)
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
