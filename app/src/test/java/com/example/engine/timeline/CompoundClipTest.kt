package com.example.engine.timeline

import com.example.domain.model.AudioClip
import com.example.domain.model.ClipKeyframe
import com.example.domain.model.TextClip
import com.example.domain.model.Timeline
import com.example.domain.model.VideoClip
import com.example.domain.model.isCompoundContainer
import com.example.engine.TimelineEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompoundClipTest {

  private fun video(id: String, start: Long, duration: Long = 3000L) = VideoClip(
    id = id,
    uri = "file:///$id.mp4",
    name = id,
    timelineStartMs = start,
    durationMs = duration,
    sourceStartMs = 0L,
    sourceEndMs = duration
  )

  private fun packed(vararg clips: VideoClip): Timeline {
    var cursor = 0L
    val packed = clips.map { clip ->
      val placed = clip.copy(timelineStartMs = cursor, durationMs = clip.durationMs, sourceEndMs = clip.durationMs)
      cursor += clip.durationMs
      placed
    }
    return Timeline(videoClips = packed)
  }

  @Test
  fun groupsAcrossTracks_preservesOffsetsLanesAndKeyframes() {
    val key = ClipKeyframe(id = "kf", timeMs = 400L, posX = 0.25f)
    val timeline = packed(video("a", 0L), video("b", 3000L)).copy(
      textClips = listOf(TextClip(id = "title", text = "Hi", timelineStartMs = 3200L, durationMs = 800L, trackIndex = 3, keyframes = listOf(key))),
      audioClips = listOf(AudioClip(id = "vo", uri = "file:///vo.wav", title = "VO", timelineStartMs = 3000L, durationMs = 3000L, sourceEndMs = 3000L, trackIndex = 2))
    )
    val engine = TimelineEngine()
    engine.loadTimeline(timeline)

    val id = engine.createCompoundClipFromSelection(setOf("b", "title", "vo"), "Scene")
    assertNotNull(id)
    val shell = engine.timeline.value.videoClips.single { it.id == id }
    assertTrue(shell.isCompoundContainer())
    assertEquals("Scene", shell.name)
    assertEquals(3000L, shell.durationMs)
    assertEquals(3000L, shell.timelineStartMs)
    assertEquals(listOf(0L, 3000L), engine.timeline.value.videoClips.map { it.timelineStartMs })
    assertTrue(engine.timeline.value.textClips.isEmpty())
    assertTrue(engine.timeline.value.audioClips.isEmpty())

    val nested = engine.openCompoundTimeline(id!!)!!
    val title = nested.textClips.single()
    assertEquals(0L, nested.videoClips.single().timelineStartMs)
    assertEquals(200L, title.timelineStartMs)
    assertEquals(3, title.trackIndex)
    assertEquals(400L, title.keyframes.single().timeMs)
    assertEquals(0.25f, title.keyframes.single().posX)
    assertEquals("kf", title.keyframes.single().id)
    assertEquals(2, nested.audioClips.single().trackIndex)
    assertEquals(0L, nested.audioClips.single().timelineStartMs)
    assertEquals("b", nested.videoClips.single().id)
  }

  @Test
  fun trailingOverhangRipplesMainTrackWithoutAGap() {
    val timeline = packed(video("a", 0L, 1000L), video("b", 1000L, 1000L), video("c", 2000L, 1000L)).copy(
      textClips = listOf(TextClip(id = "late", text = "x", timelineStartMs = 1000L, durationMs = 1500L))
    )
    val engine = TimelineEngine()
    engine.loadTimeline(timeline)
    val id = engine.createCompoundClipFromSelection(setOf("b", "late"))
    assertNotNull(id)
    val clips = engine.timeline.value.videoClips
    assertEquals(listOf("a", id, "c"), clips.map { it.id })
    assertEquals(0L, clips[0].timelineStartMs)
    assertEquals(1000L, clips[1].timelineStartMs)
    assertEquals(1500L, clips[1].durationMs)
    assertEquals(2500L, clips[2].timelineStartMs)
    assertEquals(clips[1].timelineStartMs + clips[1].durationMs, clips[2].timelineStartMs)
  }

  @Test
  fun overlayGroupDoesNotMoveMainTrack() {
    val timeline = packed(video("a", 0L), video("b", 3000L)).copy(
      textClips = listOf(
        TextClip(id = "t1", text = "one", timelineStartMs = 200L, durationMs = 400L),
        TextClip(id = "t2", text = "two", timelineStartMs = 500L, durationMs = 400L, trackIndex = 2)
      )
    )
    val engine = TimelineEngine()
    engine.loadTimeline(timeline)
    val id = engine.createCompoundClipFromSelection(setOf("t1", "t2"))
    assertNotNull(id)
    assertEquals(listOf("a", "b"), engine.timeline.value.videoClips.map { it.id })
    assertEquals(listOf(0L, 3000L), engine.timeline.value.videoClips.map { it.timelineStartMs })
    val shell = engine.timeline.value.overlayClips.single()
    assertEquals(id, shell.id)
    assertEquals(200L, shell.timelineStartMs)
    assertEquals(700L, shell.durationMs)
    assertTrue(engine.timeline.value.textClips.isEmpty())
    val nested = engine.openCompoundTimeline(id!!)!!
    assertEquals(0L, nested.textClips.single { it.id == "t1" }.timelineStartMs)
    assertEquals(300L, nested.textClips.single { it.id == "t2" }.timelineStartMs)
    assertEquals(2, nested.textClips.single { it.id == "t2" }.trackIndex)
  }

  @Test
  fun rejectsSingleNoncontiguousAndLeadingOverhang() {
    val engine = TimelineEngine()
    engine.loadTimeline(packed(video("a", 0L), video("b", 3000L), video("c", 6000L)))
    assertNull(engine.createCompoundClipFromSelection(setOf("a")))
    assertNull(engine.createCompoundClipFromSelection(setOf("a", "c")))
    assertNull(engine.createCompoundClipFromSelection(setOf("missing", "a")))

    val leading = packed(video("a", 0L, 1000L), video("b", 1000L, 1000L)).copy(
      textClips = listOf(TextClip(id = "early", text = "e", timelineStartMs = 500L, durationMs = 800L))
    )
    engine.loadTimeline(leading)
    assertNull(engine.createCompoundClipFromSelection(setOf("b", "early")))
    assertEquals(listOf("a", "b"), engine.timeline.value.videoClips.map { it.id })
  }

  @Test
  fun breakApartRestoresChildren_andTrimWindowDropsTheHead() {
    val timeline = packed(video("a", 0L, 3000L), video("b", 3000L, 3000L)).copy(
      textClips = listOf(
        TextClip(
          id = "title",
          text = "Hi",
          timelineStartMs = 3000L,
          durationMs = 3000L,
          keyframes = listOf(ClipKeyframe(id = "early", timeMs = 100L), ClipKeyframe(id = "late", timeMs = 800L))
        )
      )
    )
    val created = CompoundClipOps.create(timeline, setOf("b", "title"), "Scene")!!
    val shell = created.timeline.videoClips.single { it.id == created.compoundId }
    val trimmed = created.timeline.copy(
      videoClips = created.timeline.videoClips.map { clip ->
        if (clip.id == shell.id) clip.copy(sourceStartMs = 500L, sourceEndMs = 2500L, durationMs = 2000L) else clip
      }
    )
    val exploded = CompoundClipOps.breakApart(trimmed, created.compoundId)!!
    val title = exploded.timeline.textClips.single()
    assertEquals(2000L, title.durationMs)
    assertEquals(3000L, title.timelineStartMs)
    assertEquals(listOf("late"), title.keyframes.map { it.id })
    assertEquals(300L, title.keyframes.single().timeMs)
    val restoredVideo = exploded.timeline.videoClips.single { it.id == "b" }
    assertEquals(500L, restoredVideo.sourceStartMs)
    assertEquals(2000L, restoredVideo.durationMs)
    assertEquals(listOf(0L, 3000L), exploded.timeline.videoClips.map { it.timelineStartMs })
    assertEquals(listOf(3000L, 2000L), exploded.timeline.videoClips.map { it.durationMs })
    assertTrue(exploded.timeline.compoundClips.isEmpty())
  }

  @Test
  fun undoRedo_createBreakAndChildEdit() {
    val engine = TimelineEngine()
    val timeline = packed(video("a", 0L), video("b", 3000L)).copy(
      textClips = listOf(TextClip(id = "title", text = "Hi", timelineStartMs = 3000L, durationMs = 1000L))
    )
    engine.loadTimeline(timeline)
    val id = engine.createCompoundClipFromSelection(setOf("b", "title"))!!

    assertTrue(engine.undo())
    assertEquals(listOf("a", "b"), engine.timeline.value.videoClips.map { it.id })
    assertEquals(1, engine.timeline.value.textClips.size)
    assertTrue(engine.redo())
    assertNotNull(engine.openCompoundTimeline(id))

    val nested = engine.openCompoundTimeline(id)!!
    val longer = nested.copy(textClips = nested.textClips.map { it.copy(durationMs = 4000L) })
    assertTrue(engine.replaceCompoundTimeline(id, longer))
    assertEquals(4000L, engine.timeline.value.videoClips.single { it.id == id }.durationMs)
    assertTrue(engine.undo())
    assertEquals(3000L, engine.timeline.value.videoClips.single { it.id == id }.durationMs)

    assertTrue(engine.breakApartCompoundClip(id))
    assertEquals(listOf("a", "b"), engine.timeline.value.videoClips.map { it.id })
    assertEquals("title", engine.timeline.value.textClips.single().id)
    assertTrue(engine.undo())
    assertNotNull(engine.openCompoundTimeline(id))
    assertTrue(engine.redo())
    assertNull(engine.openCompoundTimeline(id))
  }

  @Test
  fun duplicateAndSplit_areIndependentBlocks() {
    val engine = TimelineEngine()
    engine.loadTimeline(
      packed(video("a", 0L), video("b", 3000L)).copy(
        textClips = listOf(
          TextClip(
            id = "title",
            text = "Hi",
            timelineStartMs = 0L,
            durationMs = 3000L,
            keyframes = listOf(ClipKeyframe(id = "kf", timeMs = 2000L))
          )
        )
      )
    )
    val id = engine.createCompoundClipFromSelection(setOf("a", "title"))!!
    assertTrue(engine.duplicateClips(setOf(id)))
    val compounds = engine.timeline.value.compoundClips
    assertEquals(2, compounds.size)
    val childIds = compounds.map { it.nested.textClips.single().id }.toSet()
    assertEquals(2, childIds.size)

    assertTrue(engine.undo())
    assertEquals(1, engine.timeline.value.compoundClips.size)
    val split = engine.splitClipAtTime(id, 1500L)
    assertNotNull(split)
    val (head, tail) = split!!
    val headText = engine.openCompoundTimeline(head)!!.textClips.single()
    val tailText = engine.openCompoundTimeline(tail)!!.textClips.single()
    assertEquals(1500L, headText.durationMs)
    assertTrue(headText.keyframes.isEmpty())
    assertEquals(1500L, tailText.durationMs)
    assertEquals(500L, tailText.keyframes.single().timeMs)
    assertNotEquals(headText.id, tailText.id)

    assertTrue(engine.breakApartCompoundClip(head))
    assertTrue(engine.breakApartCompoundClip(tail))
    val starts = engine.timeline.value.videoClips.map { it.timelineStartMs }
    assertEquals(starts.sorted(), starts)
    var cursor = 0L
    engine.timeline.value.videoClips.forEach { clip ->
      assertEquals(cursor, clip.timelineStartMs)
      cursor += clip.durationMs
    }
  }

  @Test
  fun deleteDropsTheNest_andUndoRestoresIt() {
    val engine = TimelineEngine()
    engine.loadTimeline(
      packed(video("a", 0L), video("b", 3000L)).copy(
        textClips = listOf(TextClip(id = "title", text = "Hi", timelineStartMs = 3000L, durationMs = 1000L))
      )
    )
    val id = engine.createCompoundClipFromSelection(setOf("b", "title"))!!
    assertTrue(engine.deleteClips(setOf(id)))
    assertTrue(engine.timeline.value.compoundClips.isEmpty())
    assertTrue(engine.timeline.value.videoClips.none { it.id == id })
    assertTrue(engine.timeline.value.textClips.isEmpty())
    assertTrue(engine.undo())
    assertEquals("title", engine.openCompoundTimeline(id)!!.textClips.single().id)
  }

  @Test
  fun shellMoveAndKeyframe_treatTheGroupAsOneClip() {
    val engine = TimelineEngine()
    engine.loadTimeline(
      packed(video("a", 0L, 4000L)).copy(
        textClips = listOf(TextClip(id = "t1", text = "a", timelineStartMs = 100L, durationMs = 1000L)),
        audioClips = listOf(AudioClip(id = "vo", uri = "file:///vo.wav", title = "VO", timelineStartMs = 100L, durationMs = 2000L, sourceEndMs = 2000L))
      )
    )
    val id = engine.createCompoundClipFromSelection(setOf("t1", "vo"))!!
    engine.moveClip(id, 800L, snap = false)
    assertEquals(800L, engine.timeline.value.overlayClips.single().timelineStartMs)
    assertEquals(0L, engine.openCompoundTimeline(id)!!.textClips.single().timelineStartMs)
    assertEquals(0L, engine.timeline.value.videoClips.single().timelineStartMs)
    assertTrue(engine.addKeyframeToClip(id, ClipKeyframe(id = "group", timeMs = 100L, scaleX = 1.2f)))
    assertEquals(1, engine.timeline.value.overlayClips.single().keyframes.size)
    assertTrue(engine.openCompoundTimeline(id)!!.textClips.single().keyframes.isEmpty())
    assertTrue(engine.breakApartCompoundClip(id))
    assertEquals(800L, engine.timeline.value.textClips.single().timelineStartMs)
    assertEquals(800L, engine.timeline.value.audioClips.single().timelineStartMs)
  }
}
