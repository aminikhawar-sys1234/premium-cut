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
}
