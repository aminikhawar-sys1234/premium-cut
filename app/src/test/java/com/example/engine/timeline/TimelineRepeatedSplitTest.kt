package com.example.engine.timeline

import com.example.domain.model.AudioClip
import com.example.domain.model.ClipKeyframe
import com.example.domain.model.EffectClip
import com.example.domain.model.EffectType
import com.example.domain.model.FilterSettings
import com.example.domain.model.FilterType
import com.example.domain.model.MaskSettings
import com.example.domain.model.MaskShape
import com.example.domain.model.StickerClip
import com.example.domain.model.TextClip
import com.example.domain.model.Timeline
import com.example.domain.model.Transition
import com.example.domain.model.TransitionType
import com.example.domain.model.VideoClip
import com.example.engine.SelectedTrackElement
import com.example.engine.TimelineEngine
import com.example.engine.audio.AudioWaveformManager
import com.example.engine.integration.AdvancedTimelineIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TimelineRepeatedSplitTest {

  private lateinit var engine: TimelineEngine

  private fun video(
    id: String,
    start: Long,
    dur: Long,
    sourceStart: Long = 0L,
    speed: Float = 1f,
    reversed: Boolean = false,
    hasAudio: Boolean = true
  ) = VideoClip(
    id = id,
    name = id,
    uri = "file:///$id.mp4",
    timelineStartMs = start,
    durationMs = dur,
    sourceStartMs = sourceStart,
    sourceEndMs = sourceStart + (dur * speed).toLong(),
    sourceTotalDurationMs = 60_000L,
    speed = speed,
    isReversed = reversed,
    hasAudio = hasAudio
  )

  private fun allIds(tl: Timeline): List<String> = buildList {
    addAll(tl.videoClips.map { it.id })
    addAll(tl.overlayClips.map { it.id })
    addAll(tl.audioClips.map { it.id })
    addAll(tl.textClips.map { it.id })
    addAll(tl.stickerClips.map { it.id })
    addAll(tl.effectClips.map { it.id })
    addAll(tl.shapeClips.map { it.id })
  }

  private fun assertHealthy(tl: Timeline) {
    val ids = allIds(tl)
    assertEquals("duplicate clip ids after split: $ids", ids.size, ids.toSet().size)
    (tl.videoClips + tl.overlayClips).forEach { clip ->
      assertTrue("${clip.id} duration", clip.durationMs > 0L)
      assertTrue("${clip.id} source window", clip.sourceStartMs <= clip.sourceEndMs)
      assertTrue("${clip.id} start<end", clip.timelineStartMs < clip.timelineStartMs + clip.durationMs)
      clip.timelineToSourceMs(clip.timelineStartMs)
      clip.timelineToSourceMs(clip.timelineStartMs + clip.durationMs)
    }
    tl.audioClips.forEach { clip ->
      assertTrue(clip.durationMs > 0L)
      assertTrue(clip.sourceStartMs <= clip.sourceEndMs)
    }
    AdvancedTimelineIndex.build(tl)
    val canEvaluateOnJvm = (tl.videoClips + tl.overlayClips).none { it.filter != null && it.filter.type != FilterType.NONE }
    if (canEvaluateOnJvm) {
      val evaluator = TimelineEvaluator()
      evaluator.evaluate(tl, 0L)
      if (tl.totalDurationMs > 0L) {
        evaluator.evaluate(tl, tl.totalDurationMs / 2)
        evaluator.evaluate(tl, (tl.totalDurationMs - 1L).coerceAtLeast(0L))
      }
    }
  }

  private val tl get() = engine.timeline.value

  @Before
  fun setUp() {
    engine = TimelineEngine()
  }

  @Test
  fun oneSplitProducesTwoContiguousClips() {
    engine.loadTimeline(Timeline(videoClips = listOf(video("A", 0, 10_000))))
    engine.selectElement(SelectedTrackElement.Video("A"))
    engine.setPosition(4_000, snap = false)
    assertTrue(engine.splitAtPlayhead())
    assertEquals(2, tl.videoClips.size)
    assertEquals(0L, tl.videoClips[0].timelineStartMs)
    assertEquals(4_000L, tl.videoClips[0].durationMs)
    assertEquals(4_000L, tl.videoClips[1].timelineStartMs)
    assertEquals(6_000L, tl.videoClips[1].durationMs)
    assertEquals(tl.videoClips[0].sourceEndMs, tl.videoClips[1].sourceStartMs)
    assertNotEquals(tl.videoClips[0].id, tl.videoClips[1].id)
    assertEquals("A", tl.videoClips[0].id)
    assertHealthy(tl)
  }

  @Test
  fun secondSplitOnTailProducesThreeClips() {
    engine.loadTimeline(Timeline(videoClips = listOf(video("A", 0, 10_000))))
    engine.setPosition(4_000, snap = false)
    assertTrue(engine.splitAtPlayhead())
    val tailId = tl.videoClips[1].id
    engine.selectElement(SelectedTrackElement.Video(tailId))
    engine.setPosition(7_000, snap = false)
    assertTrue(engine.splitAtPlayhead())
    assertEquals(3, tl.videoClips.size)
    val sorted = tl.videoClips.sortedBy { it.timelineStartMs }
    assertEquals(0L, sorted[0].timelineStartMs)
    assertEquals(4_000L, sorted[0].durationMs)
    assertEquals(4_000L, sorted[1].timelineStartMs)
    assertEquals(3_000L, sorted[1].durationMs)
    assertEquals(7_000L, sorted[2].timelineStartMs)
    assertEquals(3_000L, sorted[2].durationMs)
    assertEquals(sorted[0].sourceEndMs, sorted[1].sourceStartMs)
    assertEquals(sorted[1].sourceEndMs, sorted[2].sourceStartMs)
    assertEquals(3, sorted.map { it.id }.toSet().size)
    val selected = engine.selectedElement.value as SelectedTrackElement.Video
    assertEquals(sorted[2].id, selected.clipId)
    assertHealthy(tl)
  }

  @Test
  fun thirdSplitOnAnyResultingClipProducesFour() {
    engine.loadTimeline(Timeline(videoClips = listOf(video("A", 0, 10_000))))
    engine.setPosition(4_000, snap = false)
    assertTrue(engine.splitAtPlayhead())
    engine.selectElement(SelectedTrackElement.Video(tl.videoClips[1].id))
    engine.setPosition(7_000, snap = false)
    assertTrue(engine.splitAtPlayhead())
    engine.selectElement(SelectedTrackElement.Video(tl.videoClips[0].id))
    engine.setPosition(2_000, snap = false)
    assertTrue(engine.splitAtPlayhead())
    assertEquals(4, tl.videoClips.size)
    val sorted = tl.videoClips.sortedBy { it.timelineStartMs }
    assertEquals(listOf(0L, 2_000L, 4_000L, 7_000L), sorted.map { it.timelineStartMs })
    assertEquals(10_000L, sorted.last().timelineStartMs + sorted.last().durationMs)
    assertHealthy(tl)
  }

  @Test
  fun tenRepeatedSplitsOnSameOriginalLineage() {
    engine.loadTimeline(Timeline(videoClips = listOf(video("A", 0, 20_000))))
    var currentId = "A"
    for (i in 1..10) {
      val clip = tl.videoClips.first { it.id == currentId }
      val at = clip.timelineStartMs + clip.durationMs / 2
      engine.selectElement(SelectedTrackElement.Video(currentId))
      engine.setPosition(at, snap = false)
      assertTrue("split #$i failed at $at on $currentId", engine.splitAtPlayhead())
      assertEquals(i + 1, tl.videoClips.size)
      assertHealthy(tl)
      currentId = (engine.selectedElement.value as SelectedTrackElement.Video).clipId
      val samples = (0..99).map { it / 100f }
      tl.videoClips.forEach { c ->
        AudioWaveformManager.sliceForTrim(samples, c.sourceStartMs, c.sourceEndMs, c.durationMs)
        AudioWaveformManager.sliceForTrim(
          samples,
          c.sourceStartMs,
          c.sourceEndMs,
          maxOf(c.sourceEndMs, c.sourceStartMs + c.durationMs, c.durationMs, 1L)
        )
      }
    }
    assertEquals(11, tl.videoClips.size)
  }

  @Test
  fun splitAtClipStartAndEndIsRejected() {
    engine.loadTimeline(Timeline(videoClips = listOf(video("A", 0, 4_000))))
    engine.selectElement(SelectedTrackElement.Video("A"))
    engine.setPosition(0, snap = false)
    assertFalse(engine.splitAtPlayhead())
    engine.setPosition(4_000, snap = false)
    assertFalse(engine.splitAtPlayhead())
    engine.setPosition(5, snap = false)
    assertFalse(engine.splitAtPlayhead())
    engine.setPosition(3_995, snap = false)
    assertFalse(engine.splitAtPlayhead())
    assertEquals(1, tl.videoClips.size)
  }

  @Test
  fun secondSplitAtUnmovedCtiIsRejectedNotCrash() {
    engine.loadTimeline(Timeline(videoClips = listOf(video("A", 0, 10_000))))
    engine.setPosition(4_000, snap = false)
    assertTrue(engine.splitAtPlayhead())
    assertFalse(engine.splitAtPlayhead())
    assertEquals(2, tl.videoClips.size)
    assertHealthy(tl)
  }

  @Test
  fun splitAfterSeekAndAfterPause() {
    engine.loadTimeline(Timeline(videoClips = listOf(video("A", 0, 10_000))))
    engine.setPosition(1_500, snap = false)
    engine.setPosition(3_500, snap = false)
    assertTrue(engine.splitAtPlayhead())
    engine.setPosition(6_000, snap = false)
    assertTrue(engine.splitAtPlayhead())
    assertEquals(3, tl.videoClips.size)
    assertHealthy(tl)
  }

  @Test
  fun splitPreservesFilterMaskAndKeyframes() {
    val keys = listOf(
      ClipKeyframe(id = "k1", timeMs = 1_000),
      ClipKeyframe(id = "k2", timeMs = 5_000),
      ClipKeyframe(id = "k3", timeMs = 9_000)
    )
    engine.loadTimeline(
      Timeline(
        videoClips = listOf(
          video("A", 0, 10_000).copy(
            filter = FilterSettings(FilterType.CINEMATIC, 0.8f),
            mask = MaskSettings(enabled = true, shape = MaskShape.CIRCLE),
            keyframes = keys,
            colorGradeJson = """{"lut":"teal_orange"}""",
            vfxStackJson = """{"effects":[]}"""
          )
        )
      )
    )
    engine.setPosition(4_000, snap = false)
    assertTrue(engine.splitAtPlayhead())
    engine.setPosition(7_000, snap = false)
    assertTrue(engine.splitAtPlayhead())
    tl.videoClips.forEach {
      assertEquals(FilterType.CINEMATIC, it.filter?.type)
      assertTrue(it.mask.enabled)
      assertEquals("""{"lut":"teal_orange"}""", it.colorGradeJson)
    }
    assertTrue(tl.videoClips[0].keyframes.all { it.timeMs <= tl.videoClips[0].durationMs })
    assertHealthy(tl)
  }

  @Test
  fun splitShiftsOutgoingTransitionOntoTail() {
    engine.loadTimeline(
      Timeline(
        videoClips = listOf(video("A", 0, 4_000), video("B", 4_000, 4_000)),
        transitions = listOf(Transition(id = "tr1", clipIndexBefore = 0, type = TransitionType.DISSOLVE, durationMs = 400))
      )
    )
    engine.setPosition(2_000, snap = false)
    assertTrue(engine.splitAtPlayhead())
    assertEquals(3, tl.videoClips.size)
    assertEquals(1, tl.transitions.size)
    assertEquals(1, tl.transitions[0].clipIndexBefore)
    engine.setPosition(3_000, snap = false)
    engine.selectElement(SelectedTrackElement.Video(tl.videoClips[1].id))
    assertTrue(engine.splitAtPlayhead())
    assertEquals(4, tl.videoClips.size)
    assertTrue(tl.transitions[0].clipIndexBefore < tl.videoClips.size - 1)
    assertEquals("tr1", tl.transitions[0].id)
    assertHealthy(tl)
  }

  @Test
  fun splitVideoAlsoSplitsAlignedLinkedAudio() {
    engine.loadTimeline(
      Timeline(
        videoClips = listOf(video("A", 0, 8_000)),
        audioClips = listOf(
          AudioClip(
            id = "aud",
            title = "linked",
            uri = "file:///A.mp4",
            timelineStartMs = 0,
            durationMs = 8_000,
            sourceStartMs = 0,
            sourceEndMs = 8_000
          )
        )
      )
    )
    engine.setPosition(3_000, snap = false)
    assertTrue(engine.splitAtPlayhead())
    assertEquals(2, tl.videoClips.size)
    assertEquals(2, tl.audioClips.size)
    assertEquals(3_000L, tl.audioClips[0].durationMs)
    assertEquals(3_000L, tl.audioClips[1].timelineStartMs)
    engine.setPosition(5_000, snap = false)
    assertTrue(engine.splitAtPlayhead())
    assertEquals(3, tl.videoClips.size)
    assertEquals(3, tl.audioClips.size)
    assertHealthy(tl)
  }

  @Test
  fun splitTextStickersAndEffectsRepeatedly() {
    engine.loadTimeline(
      Timeline(
        videoClips = listOf(video("A", 0, 10_000, hasAudio = false)),
        textClips = listOf(TextClip(id = "t1", text = "Hello", timelineStartMs = 0, durationMs = 10_000)),
        stickerClips = listOf(StickerClip(id = "s1", emojiOrAsset = "⭐", timelineStartMs = 0, durationMs = 10_000)),
        effectClips = listOf(EffectClip(id = "e1", effectType = EffectType.GLOW, timelineStartMs = 0, durationMs = 10_000))
      )
    )
    engine.setPosition(4_000, snap = false)
    assertTrue(engine.splitAllTracksAtPlayhead())
    engine.setPosition(7_000, snap = false)
    assertTrue(engine.splitAllTracksAtPlayhead())
    assertEquals(3, tl.videoClips.size)
    assertEquals(3, tl.textClips.size)
    assertEquals(3, tl.stickerClips.size)
    assertEquals(3, tl.effectClips.size)
    assertHealthy(tl)
  }

  @Test
  fun undoRedoRestoresRepeatedSplits() {
    engine.loadTimeline(Timeline(videoClips = listOf(video("A", 0, 10_000))))
    val original = tl
    engine.setPosition(4_000, snap = false)
    assertTrue(engine.splitAtPlayhead())
    engine.setPosition(7_000, snap = false)
    assertTrue(engine.splitAtPlayhead())
    assertEquals(3, tl.videoClips.size)
    assertTrue(engine.undo())
    assertEquals(2, tl.videoClips.size)
    assertTrue(engine.undo())
    assertEquals(original.videoClips.size, tl.videoClips.size)
    assertEquals("A", tl.videoClips.single().id)
    assertEquals(10_000L, tl.videoClips.single().durationMs)
    assertTrue(engine.redo())
    assertEquals(2, tl.videoClips.size)
    assertTrue(engine.redo())
    assertEquals(3, tl.videoClips.size)
    assertHealthy(tl)
  }

  @Test
  fun splitDifferentResultingSegmentsIndependently() {
    engine.loadTimeline(
      Timeline(
        videoClips = listOf(video("A", 0, 12_000), video("B", 12_000, 8_000)),
        overlayClips = listOf(video("O", 1_000, 6_000).copy(trackIndex = 1))
      )
    )
    engine.selectElement(SelectedTrackElement.Video("A"))
    engine.setPosition(4_000, snap = false)
    assertTrue(engine.splitAtPlayhead())
    engine.selectElement(SelectedTrackElement.Video(tl.videoClips[0].id))
    engine.setPosition(2_000, snap = false)
    assertTrue(engine.splitAtPlayhead())
    engine.selectElement(SelectedTrackElement.Overlay("O"))
    engine.setPosition(3_000, snap = false)
    assertTrue(engine.splitAtPlayhead())
    engine.selectElement(SelectedTrackElement.Video("B"))
    engine.setPosition(16_000, snap = false)
    assertTrue(engine.splitAtPlayhead())
    assertEquals(5, tl.videoClips.size)
    assertEquals(2, tl.overlayClips.size)
    assertHealthy(tl)
  }

  @Test
  fun reversedAndSpedClipsSurviveRepeatedSplits() {
    engine.loadTimeline(Timeline(videoClips = listOf(video("R", 0, 8_000, reversed = true, speed = 2f))))
    engine.setPosition(2_000, snap = false)
    assertTrue(engine.splitAtPlayhead())
    engine.setPosition(5_000, snap = false)
    assertTrue(engine.splitAtPlayhead())
    tl.videoClips.forEach {
      assertTrue(it.isReversed)
      assertEquals(2f, it.speed)
      assertTrue(it.sourceStartMs <= it.sourceEndMs)
    }
    assertHealthy(tl)
  }

  @Test
  fun timelineIndexAndEvaluatorSurviveTenSplits() {
    engine.loadTimeline(Timeline(videoClips = listOf(video("A", 0, 30_000))))
    for (i in 1..10) {
      val at = i * 2_000L + 500L
      engine.setPosition(at, snap = false)
      assertTrue(engine.splitAtPlayhead())
      assertHealthy(tl)
    }
    assertEquals(11, tl.videoClips.size)
  }
}
