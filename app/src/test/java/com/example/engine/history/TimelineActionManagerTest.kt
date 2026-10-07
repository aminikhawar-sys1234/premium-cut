package com.example.engine.history

import com.example.domain.model.Timeline
import com.example.domain.model.VideoClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the undo/redo history. The engine records the PRE-edit snapshot, applies the edit,
 * and undo/redo must restore the exact previous/next whole Timeline (no partial restoration).
 */
class TimelineActionManagerTest {

  private fun clip(id: String, start: Long = 0L, dur: Long = 3000L) =
    VideoClip(id = id, uri = "file:///$id.mp4", name = id, timelineStartMs = start, durationMs = dur, sourceEndMs = dur)

  private fun timelineOf(vararg clips: VideoClip) = Timeline(videoClips = clips.toList())

  /** Mimics TimelineEngine: record pre-edit state, then the caller swaps in the edited state. */
  private fun TimelineActionManager.edit(current: Timeline, next: Timeline, type: TimelineActionType = TimelineActionType.GENERIC_EDIT): Timeline {
    recordPreEditHistory(type, type.displayName, current)
    return next
  }

  @Test
  fun undoThenRedo_restoresExactStates() {
    val m = TimelineActionManager()
    val s0 = Timeline()
    val s1 = timelineOf(clip("a"))
    val s2 = timelineOf(clip("a"), clip("b", start = 3000L))

    var cur = m.edit(s0, s1, TimelineActionType.ADD_CLIP)
    cur = m.edit(cur, s2, TimelineActionType.ADD_CLIP)

    val u1 = m.undo(cur)
    assertEquals(s1, u1)
    val u2 = m.undo(u1!!)
    assertEquals(s0, u2)
    assertNull(m.undo(u2!!))

    val r1 = m.redo(u2)
    assertEquals(s1, r1)
    val r2 = m.redo(r1!!)
    assertEquals(s2, r2)
    assertNull(m.redo(r2!!))
  }

  @Test
  fun newEditAfterUndo_clearsRedo() {
    val m = TimelineActionManager()
    val s0 = Timeline()
    val s1 = timelineOf(clip("a"))
    var cur = m.edit(s0, s1)
    cur = m.undo(cur)!!
    assertTrue(m.canRedo.value)

    cur = m.edit(cur, timelineOf(clip("z")))
    assertFalse("a new edit must invalidate the redo branch", m.canRedo.value)
    assertNull(m.redo(cur))
  }

  @Test
  fun history_isBounded() {
    val m = TimelineActionManager(maxHistorySize = 5)
    var cur = Timeline()
    repeat(20) { i -> cur = m.edit(cur, timelineOf(clip("c$i"))) }
    assertEquals(5, m.actionHistory.value.size)

    var undone = 0
    while (true) {
      val prev = m.undo(cur) ?: break
      cur = prev
      undone++
    }
    assertEquals(5, undone)
  }

  @Test
  fun transaction_commit_recordsOneStep_andUndoRestoresInitialState() {
    val m = TimelineActionManager()
    val start = timelineOf(clip("a", start = 0L))
    m.beginTransaction(TimelineActionType.MOVE_CLIP, "Move Clip", start, "a")
    assertTrue(m.isTransactionActive)

    // Pre-edit snapshots recorded while a gesture is in flight must be ignored (one step per gesture).
    m.recordPreEditHistory(TimelineActionType.GENERIC_EDIT, "tick", start)
    m.recordPreEditHistory(TimelineActionType.GENERIC_EDIT, "tick", start)

    val end = timelineOf(clip("a", start = 4000L))
    assertTrue(m.commitTransaction(end))
    assertFalse(m.isTransactionActive)
    assertEquals(1, m.actionHistory.value.size)

    assertEquals(start, m.undo(end))
    assertEquals(end, m.redo(start))
  }

  @Test
  fun transaction_withNoChange_recordsNothing() {
    val m = TimelineActionManager()
    val start = timelineOf(clip("a"))
    m.beginTransaction(TimelineActionType.TRIM_LEFT, "Trim Start", start, "a")
    assertFalse(m.commitTransaction(start))
    assertFalse(m.canUndo.value)
  }

  @Test
  fun transaction_cancel_returnsInitialState_andRecordsNothing() {
    val m = TimelineActionManager()
    val start = timelineOf(clip("a"))
    m.beginTransaction(TimelineActionType.MOVE_CLIP, "Move Clip", start, "a")
    assertEquals(start, m.cancelTransaction())
    assertFalse(m.isTransactionActive)
    assertFalse(m.canUndo.value)
  }

  @Test
  fun clear_resetsEverything() {
    val m = TimelineActionManager()
    val cur = m.edit(Timeline(), timelineOf(clip("a")))
    m.undo(cur)
    m.clear()
    assertFalse(m.canUndo.value)
    assertFalse(m.canRedo.value)
    assertNull(m.undo(cur))
    assertNull(m.redo(cur))
  }

  @Test
  fun undoRedoTitles_followTheStacks() {
    val m = TimelineActionManager()
    val cur = m.edit(Timeline(), timelineOf(clip("a")), TimelineActionType.ADD_CLIP)
    assertEquals("Undo Add Clip", m.undoActionTitle.value)
    m.undo(cur)
    assertNull(m.undoActionTitle.value)
    assertEquals("Redo Add Clip", m.redoActionTitle.value)
  }
}
