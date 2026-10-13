package com.example.engine.composition

import com.example.domain.model.Timeline
import com.example.domain.model.TransitionType
import com.example.domain.model.VideoClip
import com.example.engine.TimelineEngine
import com.example.engine.timeline.TransitionDurationLimits
import com.example.ui.components.TRANSITION_CATALOG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransitionToolsAuditTest {

  @Test
  fun catalogNamesMatchTheTransitionTheyApply() {
    val byType = TRANSITION_CATALOG.associateBy { it.type }
    assertEquals("Wipe Left", byType.getValue(TransitionType.WIPE).name)
    assertEquals(TransitionType.WIPE_RIGHT, byType.getValue(TransitionType.WIPE_RIGHT).type)
    assertEquals("Wipe Right", byType.getValue(TransitionType.WIPE_RIGHT).name)
    assertEquals("Glitch Wipe", byType.getValue(TransitionType.GLITCH_WIPE).name)
    assertNotEquals(TransitionType.WIPE, byType.getValue(TransitionType.GLITCH_WIPE).type)
    TransitionType.values().filter { it != TransitionType.NONE }.forEach { type ->
      assertTrue("Catalog is missing ${type.name}", byType.containsKey(type))
    }
  }

  @Test
  fun fadeThroughBlackIsNotADissolve() {
    val fadeOut = TransitionPreviewMotion.resolve(TransitionType.FADE, 0.5f, incoming = false)
    val fadeIn = TransitionPreviewMotion.resolve(TransitionType.FADE, 0.5f, incoming = true)
    assertEquals(0f, fadeOut.opacity, 0.02f)
    assertEquals(0f, fadeIn.opacity, 0.02f)

    val dissolveOut = TransitionPreviewMotion.resolve(TransitionType.DISSOLVE, 0.5f, incoming = false)
    assertTrue(dissolveOut.opacity > 0.3f)
    assertEquals(1f, TransitionPreviewMotion.resolve(TransitionType.FADE, 0f, incoming = false).opacity, 0.02f)
    assertEquals(1f, TransitionPreviewMotion.resolve(TransitionType.FADE, 1f, incoming = true).opacity, 0.02f)
  }

  @Test
  fun incomingClipDoesNotKeepTheOutgoingSlide() {
    val outgoing = TransitionPreviewMotion.resolve(TransitionType.SLIDE_LEFT, 0.5f, incoming = false)
    val incoming = TransitionPreviewMotion.resolve(TransitionType.SLIDE_LEFT, 0.5f, incoming = true)
    assertTrue(outgoing.translateX < 0f)
    assertTrue(incoming.translateX > 0f)
    assertEquals(0f, TransitionPreviewMotion.resolve(TransitionType.SLIDE_LEFT, 1f, incoming = true).translateX, 0.02f)
  }

  @Test
  fun wipeRightKeepsTheOppositeSideFromWipeLeft() {
    val left = TransitionPreviewMotion.resolve(TransitionType.WIPE, 0.25f, incoming = true)
    val right = TransitionPreviewMotion.resolve(TransitionType.WIPE_RIGHT, 0.25f, incoming = true)
    assertEquals(0f, left.clipStart ?: -1f, 0.02f)
    assertTrue((left.clipEnd ?: 0f) < 0.5f)
    assertTrue((right.clipStart ?: 0f) > 0.5f)
    assertEquals(1f, right.clipEnd ?: 0f, 0.02f)
  }

  @Test
  fun durationIsClampedToTheShorterClipAndSliderCap() {
    assertEquals(400L, TransitionDurationLimits.clamp(2000L, 400L, 3000L))
    assertEquals(2000L, TransitionDurationLimits.clamp(9000L, 4000L, 5000L))
    assertEquals(100L, TransitionDurationLimits.clamp(10L, 3000L, 3000L))

    val engine = TimelineEngine()
    engine.loadTimeline(
      Timeline(
        videoClips = listOf(
          VideoClip(id = "a", uri = "asset:///a.mp4", name = "A", timelineStartMs = 0L, durationMs = 400L, sourceStartMs = 0L, sourceEndMs = 400L),
          VideoClip(id = "b", uri = "asset:///b.mp4", name = "B", timelineStartMs = 400L, durationMs = 3000L, sourceStartMs = 0L, sourceEndMs = 3000L)
        )
      )
    )
    engine.setTransition(0, TransitionType.DISSOLVE, 2000L)
    assertEquals(400L, engine.timeline.value.transitions.single().durationMs)
  }

  @Test
  fun durationSliderBurstsShareOneUndoStep() {
    val engine = TimelineEngine()
    engine.loadTimeline(
      Timeline(
        videoClips = listOf(
          VideoClip(id = "a", uri = "asset:///a.mp4", name = "A", timelineStartMs = 0L, durationMs = 3000L, sourceStartMs = 0L, sourceEndMs = 3000L),
          VideoClip(id = "b", uri = "asset:///b.mp4", name = "B", timelineStartMs = 3000L, durationMs = 3000L, sourceStartMs = 0L, sourceEndMs = 3000L)
        )
      )
    )
    engine.setTransition(0, TransitionType.FADE, 500L)
    val afterApply = engine.actionHistory.value.size
    repeat(8) { step ->
      engine.setTransitionDuration(0, 600L + step * 50L)
    }
    assertEquals(afterApply + 1, engine.actionHistory.value.size)
    assertEquals(950L, engine.timeline.value.transitions.single().durationMs)
    assertTrue(engine.undo())
    assertEquals(500L, engine.timeline.value.transitions.single().durationMs)
  }
}
