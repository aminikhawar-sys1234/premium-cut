package com.example.engine.text

import com.example.domain.model.TextAnimatorSpec
import com.example.domain.model.TextClip
import com.example.domain.model.Timeline
import com.example.engine.text.animator.TextAnimatorEngine
import com.example.engine.text.animator.TextAnimatorRecipes
import com.example.engine.timeline.TimelineEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextGlyphParityTest {

  @Test
  fun recipesCoverPerGlyphAndPerWordSelectors() {
    val recipes = TextAnimatorRecipes.all
    assertTrue("Need a CapCut-like recipe set, found ${recipes.size}", recipes.size >= 12)
    val bases = recipes.map { it.build().basis }.toSet()
    assertTrue(bases.contains("Characters"))
    assertTrue(bases.contains("Words"))
    assertEquals(recipes.size, recipes.map { it.label }.toSet().size)
  }

  @Test
  fun evaluatorDrivesTheSameAnimatorTimePreviewAndExportUse() {
    val spec = TextAnimatorSpec(name = "Rise", basis = "Characters", opacityPct = 0f, posY = 0.4f, durationMs = 800L)
    val clip = TextClip(
      id = "t1",
      text = "HELLO",
      timelineStartMs = 0L,
      durationMs = 2000L,
      textAnimators = listOf(spec)
    )
    val state = TimelineEvaluator().evaluate(Timeline(textClips = listOf(clip)), 400L)
    val composed = state.activeTexts.single()
    assertEquals(clip.id, composed.clip.id)
    val frame = TextAnimatorEngine.evaluate(clip.text, listOf(spec), 400L)
    assertEquals(5, frame.spans.size)
    assertEquals(5, frame.fx.size)
    assertTrue(frame.fx.any { !it.isIdentity })
  }
}
