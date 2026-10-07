package com.example.engine.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipTrimMathTest {

  @Test
  fun forwardTrimStartAdvancesSourceInPointAndKeepsRightEdge() {
    val r = ClipTrimMath.trimStart(
      timelineStartMs = 1000, durationMs = 4000, sourceStartMs = 0, sourceEndMs = 4000,
      speed = 1f, reversed = false, mediaDurationMs = 10_000, requestedStartMs = 2000
    )
    assertEquals(2000L, r.timelineStartMs)
    assertEquals(3000L, r.durationMs)
    assertEquals(1000L, r.sourceStartMs)
    assertEquals(4000L, r.sourceEndMs)
    assertEquals(5000L, r.timelineStartMs + r.durationMs)
  }

  @Test
  fun trimStartAtSpeedScalesSourceDelta() {
    val r = ClipTrimMath.trimStart(
      timelineStartMs = 0, durationMs = 4000, sourceStartMs = 0, sourceEndMs = 8000,
      speed = 2f, reversed = false, mediaDurationMs = 20_000, requestedStartMs = 1000
    )
    assertEquals(3000L, r.durationMs)
    assertEquals(2000L, r.sourceStartMs)
    assertEquals(8000L, r.sourceEndMs)
  }

  @Test
  fun extendingStartIsBoundedBySourceHeadroom() {
    val r = ClipTrimMath.trimStart(
      timelineStartMs = 5000, durationMs = 2000, sourceStartMs = 500, sourceEndMs = 2500,
      speed = 1f, reversed = false, mediaDurationMs = 10_000, requestedStartMs = 1000
    )
    assertEquals(4500L, r.timelineStartMs)
    assertEquals(0L, r.sourceStartMs)
    assertEquals(2500L, r.durationMs)
  }

  @Test
  fun reversedTrimStartMovesSourceOutPoint() {
    val r = ClipTrimMath.trimStart(
      timelineStartMs = 0, durationMs = 4000, sourceStartMs = 2000, sourceEndMs = 6000,
      speed = 1f, reversed = true, mediaDurationMs = 10_000, requestedStartMs = 1000
    )
    assertEquals(3000L, r.durationMs)
    assertEquals(2000L, r.sourceStartMs)
    assertEquals(5000L, r.sourceEndMs)
  }

  @Test
  fun trimEndIsBoundedByMediaLength() {
    val r = ClipTrimMath.trimEnd(
      timelineStartMs = 0, durationMs = 3000, sourceStartMs = 0, sourceEndMs = 3000,
      speed = 1f, reversed = false, mediaDurationMs = 5000, requestedDurationMs = 99_000
    )
    assertEquals(5000L, r.durationMs)
    assertEquals(5000L, r.sourceEndMs)
  }

  @Test
  fun unboundedMediaCanBeExtendedFreely() {
    val r = ClipTrimMath.trimEnd(
      timelineStartMs = 0, durationMs = 3000, sourceStartMs = 0, sourceEndMs = 3000,
      speed = 1f, reversed = false, mediaDurationMs = 0, requestedDurationMs = 9000
    )
    assertEquals(9000L, r.durationMs)
  }

  @Test
  fun trimNeverGoesBelowMinimumOrNegative() {
    val s = ClipTrimMath.trimStart(
      timelineStartMs = 0, durationMs = 1000, sourceStartMs = 0, sourceEndMs = 1000,
      speed = 1f, reversed = false, mediaDurationMs = 5000, requestedStartMs = 5000
    )
    assertTrue(s.durationMs >= ClipTrimMath.MIN_TRIM_DURATION_MS)
    val e = ClipTrimMath.trimEnd(
      timelineStartMs = 0, durationMs = 1000, sourceStartMs = 0, sourceEndMs = 1000,
      speed = 1f, reversed = false, mediaDurationMs = 5000, requestedDurationMs = -50
    )
    assertEquals(ClipTrimMath.MIN_TRIM_DURATION_MS, e.durationMs)
  }

  @Test
  fun shortClipKeepsItsOwnLengthAsMinimum() {
    val r = ClipTrimMath.trimEnd(
      timelineStartMs = 0, durationMs = 50, sourceStartMs = 0, sourceEndMs = 50,
      speed = 1f, reversed = false, mediaDurationMs = 5000, requestedDurationMs = 0
    )
    assertEquals(50L, r.durationMs)
  }

  @Test
  fun pinnedMainTrackStartStaysAtZero() {
    val r = ClipTrimMath.trimStart(
      timelineStartMs = 0, durationMs = 4000, sourceStartMs = 0, sourceEndMs = 4000,
      speed = 1f, reversed = false, mediaDurationMs = 10_000, requestedStartMs = 1500, pinToZero = true
    )
    assertEquals(0L, r.timelineStartMs)
    assertEquals(2500L, r.durationMs)
    assertEquals(1500L, r.sourceStartMs)
  }
}
