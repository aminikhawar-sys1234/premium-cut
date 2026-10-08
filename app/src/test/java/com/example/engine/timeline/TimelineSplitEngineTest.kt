package com.example.engine.timeline

import com.example.domain.model.AudioClip
import com.example.domain.model.TextClip
import com.example.domain.model.Timeline
import com.example.domain.model.TrackType
import com.example.domain.model.VideoClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineSplitEngineTest {

  private val unlocked: (TrackType, Int) -> Boolean = { _, _ -> false }

  private fun video(id: String, start: Long, dur: Long, speed: Float = 1f, reversed: Boolean = false) =
    VideoClip(
      id = id, name = id, uri = "file:///$id.mp4", timelineStartMs = start, durationMs = dur,
      sourceStartMs = 1000, sourceEndMs = 1000 + (dur * speed).toLong(), speed = speed, isReversed = reversed
    )

  @Test
  fun forwardSplitIsLossless() {
    val r = TimelineSplitEngine.splitVideoClip(video("v", 0, 4000), 1500)
    assertTrue(r.isSuccess)
    val head = r.headClip!!
    val tail = r.tailClip!!
    assertEquals(1500L, head.durationMs)
    assertEquals(1500L, tail.timelineStartMs)
    assertEquals(2500L, tail.durationMs)
    assertEquals(head.sourceEndMs, tail.sourceStartMs)
    assertEquals(1000L, head.sourceStartMs)
    assertEquals(5000L, tail.sourceEndMs)
    assertNotEquals(head.id, tail.id)
  }

  @Test
  fun speedChangedSplitKeepsSourceContinuity() {
    val r = TimelineSplitEngine.splitVideoClip(video("v", 0, 4000, speed = 2f), 1000)
    val head = r.headClip!!
    val tail = r.tailClip!!
    assertEquals(2000L, head.sourceEndMs - head.sourceStartMs)
    assertEquals(head.sourceEndMs, tail.sourceStartMs)
    assertEquals(2f, tail.speed, 0f)
  }

  @Test
  fun reversedSplitPlaysTailFirstSourceHalf() {
    val clip = video("v", 0, 4000, reversed = true)
    val r = TimelineSplitEngine.splitVideoClip(clip, 1000)
    val head = r.headClip!!
    val tail = r.tailClip!!
    assertEquals(clip.sourceEndMs, head.sourceEndMs)
    assertEquals(head.sourceStartMs, tail.sourceEndMs)
    assertEquals(clip.sourceStartMs, tail.sourceStartMs)
    assertTrue(tail.isReversed)
  }

  @Test
  fun splitTooCloseToEdgeIsRejected() {
    assertFalse(TimelineSplitEngine.splitVideoClip(video("v", 0, 4000), 5).isSuccess)
    assertFalse(TimelineSplitEngine.splitVideoClip(video("v", 0, 4000), 3995).isSuccess)
    assertFalse(TimelineSplitEngine.splitVideoClip(video("v", 0, 4000), 9000).isSuccess)
  }

  @Test
  fun audioSplitPreservesSourceWindow() {
    val a = AudioClip(
      id = "a", title = "a", uri = "file:///a.mp3", timelineStartMs = 2000, durationMs = 6000,
      sourceStartMs = 500, sourceEndMs = 6500, fadeInMs = 300, fadeOutMs = 400
    )
    val r = TimelineSplitEngine.splitAudioClip(a, 5000)
    val head = r.headClip!!
    val tail = r.tailClip!!
    assertEquals(3000L, head.durationMs)
    assertEquals(3500L, head.sourceEndMs)
    assertEquals(3500L, tail.sourceStartMs)
    assertEquals(6500L, tail.sourceEndMs)
    assertEquals(300L, head.fadeInMs)
    assertEquals(400L, tail.fadeOutMs)
  }

  @Test
  fun textSplitKeepsTotalDuration() {
    val t = TextClip(id = "t", text = "hello", timelineStartMs = 1000, durationMs = 3000)
    val r = TimelineSplitEngine.splitTextClip(t, 2000)
    assertEquals(1000L, r.headClip!!.durationMs)
    assertEquals(2000L, r.tailClip!!.timelineStartMs)
    assertEquals(2000L, r.tailClip!!.durationMs)
  }

  @Test
  fun splitInTimelineReplacesOnlyTheTargetClip() {
    val timeline = Timeline(
      videoClips = listOf(video("v1", 0, 3000), video("v2", 3000, 3000)),
      overlayClips = listOf(video("o1", 500, 4000).copy(trackIndex = 1))
    )
    val split = TimelineSplitEngine.splitClipInTimeline(timeline, "o1", 2000, unlocked)
    assertNotNull(split)
    assertEquals(2, split!!.timeline.videoClips.size)
    assertEquals(2, split.timeline.overlayClips.size)
    assertTrue(split.timeline.overlayClips.all { it.trackIndex == 1 })
    assertEquals(0L, split.timeline.videoClips[0].timelineStartMs)
  }

  @Test
  fun lockedLaneRefusesSplit() {
    val timeline = Timeline(videoClips = listOf(video("v1", 0, 3000)))
    val split = TimelineSplitEngine.splitClipInTimeline(timeline, "v1", 1000) { _, _ -> true }
    assertNull(split)
  }

  @Test
  fun unknownClipOrOutOfRangeSplitReturnsNull() {
    val timeline = Timeline(videoClips = listOf(video("v1", 0, 3000)))
    assertNull(TimelineSplitEngine.splitClipInTimeline(timeline, "nope", 1000, unlocked))
    assertNull(TimelineSplitEngine.splitClipInTimeline(timeline, "v1", 3000, unlocked))
  }

  @Test
  fun repeatedSplitsKeepUniqueIdsAndOrderedSourceWindows() {
    var timeline = Timeline(videoClips = listOf(video("v", 0, 10_000)))
    val cuts = listOf(2_000L, 4_000L, 6_000L, 8_000L)
    for (cut in cuts) {
      val target = timeline.videoClips.first { clip ->
        TimelineSplitEngine.isInsideSplitRange(cut, clip.timelineStartMs, clip.durationMs)
      }
      val split = TimelineSplitEngine.splitClipInTimeline(timeline, target.id, cut, unlocked)
      assertNotNull(split)
      timeline = split!!.timeline
      val ids = timeline.videoClips.map { it.id }
      assertEquals(ids.size, ids.toSet().size)
      timeline.videoClips.forEach {
        assertTrue(it.sourceStartMs <= it.sourceEndMs)
        assertTrue(it.durationMs > 0L)
      }
    }
    assertEquals(5, timeline.videoClips.size)
  }
}
