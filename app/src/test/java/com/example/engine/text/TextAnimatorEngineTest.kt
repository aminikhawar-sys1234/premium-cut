package com.example.engine.text

import com.example.domain.model.TextAnimatorSpec
import com.example.engine.text.animator.TextAnimatorEngine
import com.example.engine.text.animator.TextAnimatorRecipes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextAnimatorEngineTest {

  private val riseReveal get() = TextAnimatorRecipes.all.first { it.label == "Rise Reveal" }.build()

  @Test
  fun segment_characters_skips_whitespace_and_counts_units() {
    val seg = TextAnimatorEngine.segment("ab c\nd", "Characters")
    assertEquals("Characters", seg.effectiveBasis)
    assertEquals(4, seg.unitCount)
    assertEquals(listOf(0, 1, 3, 5), seg.spans.map { it.start })
  }

  @Test
  fun segment_words_and_lines() {
    val words = TextAnimatorEngine.segment("hello big\nworld", "Words")
    assertEquals(3, words.unitCount)
    assertEquals(listOf(0, 6, 10), words.spans.map { it.start })

    val lines = TextAnimatorEngine.segment("one\n\ntwo", "Lines")
    assertEquals(2, lines.unitCount) // the empty line is not a unit
  }

  @Test
  fun segment_never_splits_cursive_or_indic_text_into_characters() {
    val urdu = TextAnimatorEngine.segment("اردو متن", "Characters")
    assertEquals("Words", urdu.effectiveBasis)
    assertEquals(2, urdu.unitCount)
    val hindi = TextAnimatorEngine.segment("नमस्ते दुनिया", "Characters")
    assertEquals("Words", hindi.effectiveBasis)
    assertEquals(2, hindi.unitCount)
  }

  @Test
  fun segment_keeps_surrogate_pairs_together() {
    val seg = TextAnimatorEngine.segment("a😀b", "Characters")
    assertEquals(3, seg.unitCount)
    assertEquals(2, seg.spans[1].end - seg.spans[1].start)
  }

  @Test
  fun timeProgress_once_loop_pingpong_and_delay() {
    val base = TextAnimatorSpec(durationMs = 1000L, timeEasing = "Linear", delayMs = 200L)
    assertEquals(0f, TextAnimatorEngine.timeProgress(base, 100L), 1e-6f) // before delay
    assertEquals(0.5f, TextAnimatorEngine.timeProgress(base, 700L), 1e-6f)
    assertEquals(1f, TextAnimatorEngine.timeProgress(base, 5000L), 1e-6f) // Once clamps

    val loop = base.copy(loopMode = "Loop")
    assertEquals(0.5f, TextAnimatorEngine.timeProgress(loop, 2700L), 1e-6f)

    val pingPong = base.copy(loopMode = "Ping-Pong")
    assertEquals(0.5f, TextAnimatorEngine.timeProgress(pingPong, 700L), 1e-6f) // forward leg
    assertEquals(0.8f, TextAnimatorEngine.timeProgress(pingPong, 1400L), 1e-6f) // 1200 ms in: back leg
  }

  @Test
  fun rise_reveal_hides_everything_at_start_and_shows_everything_at_end() {
    val text = "ABCDEFGHIJ"
    val start = TextAnimatorEngine.evaluate(text, listOf(riseReveal), 0L)
    assertEquals(10, start.fx.size)
    assertTrue(start.fx.all { it.opacity == 0f })
    assertTrue(start.fx.all { it.offsetYEm > 0.44f })

    val end = TextAnimatorEngine.evaluate(text, listOf(riseReveal), 900L)
    assertTrue(end.fx.all { it.isIdentity })
  }

  @Test
  fun rise_reveal_reveals_left_to_right() {
    val frame = TextAnimatorEngine.evaluate("ABCDEFGHIJ", listOf(riseReveal), 450L)
    assertEquals(1f, frame.fx.first().opacity, 1e-4f) // first char already revealed
    assertEquals(0f, frame.fx.last().opacity, 1e-4f) // last char still hidden
    for (i in 1 until frame.fx.size) {
      assertTrue(frame.fx[i].opacity <= frame.fx[i - 1].opacity + 1e-6f)
    }
  }

  @Test
  fun wave_bump_travels_and_is_strongest_in_the_middle() {
    val wave = TextAnimatorRecipes.all.first { it.label == "Wave" }.build()
    val flatStart = TextAnimatorEngine.evaluate("ABCDEFGHIJ", listOf(wave), 0L)
    assertTrue(flatStart.fx.all { it.isIdentity })

    val mid = TextAnimatorEngine.evaluate("ABCDEFGHIJ", listOf(wave), 700L)
    val lifts = mid.fx.map { -it.offsetYEm }
    assertTrue(lifts[4] > lifts[1])
    assertTrue(lifts[5] > lifts[8])
    assertEquals(0.4f * 0.905f, lifts[4], 0.01f)
  }

  @Test
  fun hard_square_selector_selects_exact_half() {
    val spec = TextAnimatorSpec(
      shape = "Square", startFrom = 0f, startTo = 0f, endFrom = 50f, endTo = 50f,
      durationMs = 1000L, timeEasing = "Linear", opacityPct = 0f
    )
    val frame = TextAnimatorEngine.evaluate("ABCDEFGHIJ", listOf(spec), 0L)
    assertEquals(listOf(0f, 0f, 0f, 0f, 0f, 1f, 1f, 1f, 1f, 1f), frame.fx.map { it.opacity })
  }

  @Test
  fun shapes_produce_expected_profiles() {
    fun amount(shape: String, p: Float) = TextAnimatorEngine.selectorAmount(
      TextAnimatorSpec(shape = shape, startFrom = 0f, startTo = 0f, endFrom = 100f, endTo = 100f), p, 0f
    )
    assertEquals(0.25f, amount("Ramp Up", 25f), 1e-5f)
    assertEquals(0.75f, amount("Ramp Down", 25f), 1e-5f)
    assertEquals(0.5f, amount("Triangle", 25f), 1e-5f)
    assertEquals(1f, amount("Triangle", 50f), 1e-5f)
    assertEquals(1f, amount("Round", 50f), 1e-5f)
    assertEquals(0f, amount("Smooth", 0f), 1e-5f)
    assertEquals(1f, amount("Smooth", 50f), 1e-5f)
  }

  @Test
  fun shapeEase_is_identity_at_zero_and_stays_in_range() {
    assertEquals(0.3f, TextAnimatorEngine.shapeEase(0.3f, 0f, 0f), 1e-6f)
    for (v in listOf(0f, 0.1f, 0.5f, 0.9f, 1f)) {
      for (k in listOf(-100f, -40f, 40f, 100f)) {
        val out = TextAnimatorEngine.shapeEase(v, k, k)
        assertTrue(out in 0f..1f)
      }
    }
  }

  @Test
  fun permutation_is_a_deterministic_shuffle() {
    val a = TextAnimatorEngine.permutation(10, 1)
    assertArrayEquals(intArrayOf(8, 9, 3, 2, 4, 6, 5, 1, 0, 7), a)
    assertArrayEquals(a, TextAnimatorEngine.permutation(10, 1))
    assertEquals((0 until 10).toList(), a.sorted())
    assertNotEquals(a.toList(), TextAnimatorEngine.permutation(10, 2).toList())
  }

  @Test
  fun randomize_changes_reveal_order_but_not_the_end_state() {
    val ordered = riseReveal.copy(softness = 0f)
    val shuffled = ordered.copy(randomize = true, randomSeed = 1)
    val a = TextAnimatorEngine.evaluate("ABCDEFGHIJ", listOf(ordered), 450L).fx.map { it.opacity }
    val b = TextAnimatorEngine.evaluate("ABCDEFGHIJ", listOf(shuffled), 450L).fx.map { it.opacity }
    assertNotEquals(a, b)
    assertEquals(5, b.count { it == 1f }) // same number revealed, different characters
    assertTrue(TextAnimatorEngine.evaluate("ABCDEFGHIJ", listOf(shuffled), 900L).fx.all { it.isIdentity })
  }

  @Test
  fun animators_stack_and_disabled_ones_are_ignored() {
    val a = TextAnimatorSpec(
      startFrom = 0f, startTo = 0f, endFrom = 100f, endTo = 100f,
      opacityPct = 50f, posY = 0.5f, scalePct = 200f, trackingEm = 0.1f, rotationDeg = 10f
    )
    val b = a.copy(posY = 0.25f, scalePct = 50f, trackingEm = 0.2f, rotationDeg = 5f)
    val fx = TextAnimatorEngine.evaluate("AB", listOf(a, b), 0L).fx[0]
    assertEquals(0.25f, fx.opacity, 1e-6f)
    assertEquals(0.75f, fx.offsetYEm, 1e-6f)
    assertEquals(1f, fx.scale, 1e-6f) // 2.0 * 0.5
    assertEquals(0.3f, fx.trackingEm, 1e-6f)
    assertEquals(15f, fx.rotationDeg, 1e-6f)

    val off = TextAnimatorEngine.evaluate("AB", listOf(a.copy(enabled = false)), 0L)
    assertTrue(off.fx.all { it.isIdentity })
  }

  @Test
  fun coarser_animators_map_every_character_to_its_word() {
    val perWord = TextAnimatorSpec(
      basis = "Words", shape = "Square", startFrom = 50f, startTo = 50f,
      endFrom = 100f, endTo = 100f, opacityPct = 0f
    )
    val fx = TextAnimatorEngine.evaluate("ab cd", listOf(perWord), 0L).fx
    assertEquals(listOf(1f, 1f, 0f, 0f), fx.map { it.opacity }) // word 1 untouched, word 2 hidden
  }

  @Test
  fun fill_tint_blends_toward_the_fill_colour() {
    val spec = TextAnimatorSpec(
      startFrom = 0f, startTo = 0f, endFrom = 100f, endTo = 100f,
      useFill = true, fillColor = 0xFFFF0000
    )
    val fx = TextAnimatorEngine.evaluate("A", listOf(spec), 0L).fx[0]
    assertEquals(1f, fx.tintAmount, 1e-6f)
    assertEquals(0xFFFF0000.toInt(), fx.tintArgb)
    assertEquals(0xFF7F7F7F.toInt(), TextAnimatorEngine.lerpArgb(0xFF000000.toInt(), 0xFFFEFEFE.toInt(), 0.5f))
  }

  @Test
  fun every_recipe_is_valid_and_changes_something_visible() {
    for (recipe in TextAnimatorRecipes.all) {
      val spec = recipe.build()
      assertTrue(spec.durationMs >= 100L)
      assertTrue(spec.shape in TextAnimatorEngine.SHAPES)
      assertTrue(spec.basis in TextAnimatorEngine.BASES)
      val seen = (0L..spec.durationMs step 50L).any { t ->
        TextAnimatorEngine.evaluate("Hello World", listOf(spec), t).fx.any { !it.isIdentity }
      }
      assertTrue("${recipe.label} never changes the text", seen)
    }
  }
}
