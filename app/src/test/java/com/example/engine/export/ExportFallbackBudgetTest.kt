package com.example.engine.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When neither codec ladder decodes a clip, the render loop falls back to
 * `MediaMetadataRetriever.getFrameAtTime(OPTION_CLOSEST)` - a call with **no timeout of its own**,
 * on the one thread that has to keep the encoder fed. A cloud-backed `content://` file or a damaged
 * clip can block there for minutes; nothing bounded it, so a single such call was enough to stop
 * every frame from being encoded (the "0 frames for twenty minutes" shape of the report).
 *
 * The policy is deliberately narrow, and these tests pin both halves of it:
 *
 * * a retriever call never gets longer than the deadline, and the first call that overruns it retires
 *   that clip for the rest of the attempt (it is a broken call, not a slow one - asking again would
 *   only repeat the stall);
 * * a retriever that is merely *slow* keeps working: a clip that answers in a fraction of the
 *   deadline exports exactly as before, and retiring one clip never affects another.
 */
class ExportFallbackBudgetTest {

  @Test
  fun aWorkingRetrieverIsNeverRetired() {
    val budget = ExportFallbackBudget(perCallDeadlineMs = 6_000L)
    assertTrue(budget.mayUse("clip-a"))
    repeat(50) { budget.recordCall("clip-a", elapsedMs = 180L, timedOut = false) }
    assertTrue("a fast retriever must keep working", budget.mayUse("clip-a"))
    assertNull(budget.blockedReason("clip-a"))
    assertEquals(0, budget.timeoutsSeen())
    assertEquals(50, budget.callsFor("clip-a"))
    assertEquals(9_000L, budget.timeSpentMs("clip-a"))
  }

  @Test
  fun aSlowButAnsweringRetrieverKeepsWorking() {
    val budget = ExportFallbackBudget(perCallDeadlineMs = 6_000L, slowPathWarningCalls = 3)
    assertFalse("under the threshold this is not a slow path yet", budget.isSlowPath("clip-a"))
    repeat(3) { budget.recordCall("clip-a", elapsedMs = 5_000L, timedOut = false) }
    assertTrue("repeated near-deadline calls are worth a warning", budget.isSlowPath("clip-a"))
    assertTrue("but they must still be allowed", budget.mayUse("clip-a"))
  }

  @Test
  fun theFirstTimeoutRetiresTheClipAndOnlyThatClip() {
    val budget = ExportFallbackBudget(perCallDeadlineMs = 6_000L)
    val retired = budget.recordCall("clip-a", elapsedMs = 6_000L, timedOut = true)
    assertTrue("the first overrun retires the clip", retired)
    assertFalse(budget.mayUse("clip-a"))
    assertTrue(budget.blockedReason("clip-a")!!.contains("6000ms"))
    assertEquals(1, budget.timeoutsSeen())
    assertEquals(setOf("clip-a"), budget.retiredClips())

    // A second timeout on the same clip is not a new retirement, and never a new stall.
    assertFalse(budget.recordCall("clip-a", elapsedMs = 6_000L, timedOut = true))
    // A different clip is untouched: the export continues with everything else it can decode.
    assertTrue(budget.mayUse("clip-b"))
    assertEquals(setOf("clip-a"), budget.retiredClips())
  }

  @Test
  fun theDeadlineIsShortEnoughToBeSeenAndLongEnoughForARealFile() {
    // A still-frame extraction of a local file is tens to a few hundred milliseconds; a 4K frame a
    // few hundred more. The default must be far above that and far below "the user gives up".
    assertTrue(ExportFallbackBudget.DEFAULT_PER_CALL_DEADLINE_MS >= 2_000L)
    assertTrue(ExportFallbackBudget.DEFAULT_PER_CALL_DEADLINE_MS <= 10_000L)
    assertEquals(ExportFallbackBudget.DEFAULT_PER_CALL_DEADLINE_MS, ExportFallbackBudget().perCallDeadlineMs)
  }

  @Test
  fun negativeOrNanElapsedTimesCannotCorruptTheAccounting() {
    val budget = ExportFallbackBudget()
    budget.recordCall("clip-a", elapsedMs = -100L, timedOut = false)
    assertEquals(0L, budget.timeSpentMs("clip-a"))
    assertEquals(0L, budget.timeSpentMs("never-seen"))
    assertEquals(0, budget.callsFor("never-seen"))
  }
}
