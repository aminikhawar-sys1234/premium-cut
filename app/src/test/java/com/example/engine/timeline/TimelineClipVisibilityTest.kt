package com.example.engine.timeline

import com.example.domain.model.TextClip
import com.example.domain.model.Timeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineClipVisibilityTest {
  @Test
  fun rangeIsStartInclusiveEndExclusive() {
    assertTrue(TimelineClipVisibility.isActiveAt(1000L, 1000L, 2000L))
    assertTrue(TimelineClipVisibility.isActiveAt(2999L, 1000L, 2000L))
    assertFalse(TimelineClipVisibility.isActiveAt(3000L, 1000L, 2000L))
    assertFalse(TimelineClipVisibility.isActiveAt(999L, 1000L, 2000L))
  }

  @Test
  fun evaluatorAndPreviewShareTheSameRange() {
    val clip = TextClip(
      id = "t1",
      text = "Hello",
      timelineStartMs = 1000L,
      durationMs = 2000L
    )
    val timeline = Timeline(textClips = listOf(clip))
    val evaluator = TimelineEvaluator()
    assertEquals(1, evaluator.evaluate(timeline, 1000L).activeTexts.size)
    assertEquals(1, evaluator.evaluate(timeline, 2999L).activeTexts.size)
    assertEquals(0, evaluator.evaluate(timeline, 3000L).activeTexts.size)
  }
}
