package com.example.engine.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The export's decoder budget rule: freeing a decoder may never remove a layer from the picture.
 */
class DecoderBudgetTest {

  @Test
  fun onlyDecodersTheFrameDoesNotNeedMayBeReleased() {
    assertEquals(listOf("b"), DecoderBudget.evictions(listOf("a", "b", "c"), setOf("a", "c")))
  }

  @Test
  fun nothingIsReleasedWhileEveryOpenDecoderIsOnScreen() {
    // Five simultaneous layers over a device that only grants four codec instances: the export has
    // nothing it may free, so it has to report the device's refusal instead of dropping a layer.
    assertEquals(emptyList<String>(), DecoderBudget.evictions(listOf("a", "b", "c", "d", "e"), setOf("a", "b", "c", "d", "e")))
    assertFalse(DecoderBudget.canFree(listOf("a", "b", "c", "d", "e"), setOf("a", "b", "c", "d", "e")))
  }

  @Test
  fun theOldestUnneededDecodersAreReportedFirst() {
    assertEquals(
      listOf("main-1", "overlay-7", "bg"),
      DecoderBudget.evictions(listOf("main-1", "overlay-7", "bg", "main-2"), setOf("main-2"))
    )
  }

  @Test
  fun idsThatAreNotOpenAreNotReported() {
    assertEquals(emptyList<String>(), DecoderBudget.evictions(listOf("a"), setOf("a", "not-open")))
  }

  @Test
  fun anEmptyFrameNeedsNothingSoEverythingMayBeReleased() {
    assertTrue(DecoderBudget.canFree(listOf("a", "b"), emptySet()))
    assertEquals(listOf("a", "b"), DecoderBudget.evictions(listOf("a", "b"), emptySet()))
  }

  @Test
  fun aDecoderThatIsOnlyKeptWarmIsReleasedBeforeALayerWouldBe() {
    // "b" is the before-side of a NONE transition: nothing of it is drawn this frame, it is only
    // held so a following real transition does not have to reopen it. "c" is not needed at all.
    // Both may go; the one that was held as an optimisation goes last, and the layer is never the
    // one that pays.
    assertEquals(
      listOf("c", "b"),
      DecoderBudget.evictions(open = listOf("a", "b", "c"), needed = setOf("a"), pinned = setOf("b"))
    )
  }

  @Test
  fun aPinnedDecoderIsNotReleasedWhileSomethingExpendableIsOpen() {
    assertEquals(
      listOf("c"),
      DecoderBudget.evictions(
        open = listOf("a", "b", "c"),
        needed = setOf("a"),
        pinned = setOf("b")
      ).take(1)
    )
  }

  @Test
  fun aPinnedDecoderThatTheFrameDrawsIsNeededAfterAll() {
    // A pinned id that ended up in the frame's picture may never be released: the weaker class a
    // decoder belongs to is not allowed to override what the frame actually draws.
    assertEquals(
      emptyList<String>(),
      DecoderBudget.evictions(open = listOf("a"), needed = setOf("a"), pinned = setOf("a"))
    )
  }

  @Test
  fun theTwoArgumentRuleStillBehavesAsBefore() {
    assertEquals(
      DecoderBudget.evictions(listOf("a", "b", "c"), setOf("a", "c")),
      DecoderBudget.evictions(listOf("a", "b", "c"), setOf("a", "c"), emptySet())
    )
  }
}
