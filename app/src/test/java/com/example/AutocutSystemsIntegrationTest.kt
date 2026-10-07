package com.example

import com.example.domain.model.ClipKeyframe
import com.example.domain.model.Timeline
import com.example.domain.model.VideoClip
import com.example.engine.integration.AdvancedTimelineIndex
import com.example.engine.integration.KeyframeAnimationEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutocutSystemsIntegrationTest {
  private fun timeline() = Timeline(
    videoClips = listOf(
      VideoClip(id = "v1", name = "one", durationMs = 2_000, timelineStartMs = 0),
      VideoClip(id = "v2", name = "two", durationMs = 2_000, timelineStartMs = 1_000)
    ),
    overlayClips = listOf(
      VideoClip(id = "o1", name = "overlay", durationMs = 1_000, timelineStartMs = 1_000)
    )
  )

  @Test
  fun intervalTreeTrackIndexAndSnapIndexQuery() {
    val index = AdvancedTimelineIndex.build(timeline())
    assertEquals("v1", index.findClip("v1")?.id)
    assertEquals(setOf("v1", "v2", "o1"), index.getClipsAt(1_500).map { it.id }.toSet())
    assertEquals(1, index.findOverlaps("video").size)
    assertEquals(1_000L, index.snapIndex.findClosest(995, 10))
    assertNull(index.snapIndex.findClosest(950, 10))
  }

  @Test
  fun keyframeEngineInterpolatesAllCoreTransformProperties() {
    val frames = listOf(
      ClipKeyframe(timeMs = 0, scale = 1f, posX = 0f, opacity = 1f),
      ClipKeyframe(timeMs = 1_000, scale = 2f, posX = 10f, rotation = 90f, opacity = .5f)
    )
    val value = KeyframeAnimationEngine.evaluate(frames, 500)
    assertEquals(1.5f, value.scaleX, .001f)
    assertEquals(5f, value.positionX, .001f)
    assertEquals(45f, value.rotation, .001f)
    assertEquals(.75f, value.opacity, .001f)
  }
}
