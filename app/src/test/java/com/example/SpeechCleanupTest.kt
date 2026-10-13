package com.example

import com.example.domain.model.TextClip
import com.example.domain.model.WordTiming
import com.example.engine.captions.CaptionWordOps
import com.example.engine.captions.SpeechCleanup
import com.example.engine.timeline.TimelineRangeOps.TimeRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechCleanupTest {

  private fun clip(vararg w: Triple<String, Long, Long>, start: Long = 1000L, id: String = "c1"): TextClip {
    val words = w.map { WordTiming(it.first, it.second, it.third) }
    return TextClip(
      id = id, text = w.joinToString(" ") { it.first },
      timelineStartMs = start, durationMs = (words.last().startMs + words.last().durationMs),
      words = words
    )
  }

  private val sample = clip(
    Triple("So", 0L, 200L), Triple("um", 250L, 300L), Triple("this", 600L, 250L),
    Triple("is", 900L, 150L), Triple("uh,", 1100L, 250L), Triple("great", 1400L, 400L)
  )

  @Test
  fun wordsAreMappedToTimelineTime() {
    val words = SpeechCleanup.wordsOf(listOf(sample))
    assertEquals(1250L, words[1].startMs)
    assertEquals(1550L, words[1].endMs)
    assertEquals(6, words.size)
  }

  @Test
  fun findsFillersIgnoringCaseAndPunctuation() {
    val fillers = SpeechCleanup.fillerWords(SpeechCleanup.wordsOf(listOf(sample)))
    assertEquals(listOf("um", "uh,"), fillers.map { it.text })
  }

  @Test
  fun realWordsAreNeverFillers() {
    val c = clip(Triple("Hum", 0, 200), Triple("umbrella", 300, 300), Triple("ہم", 700, 200), Triple("ہمم", 1000, 200))
    val found = SpeechCleanup.fillerWords(SpeechCleanup.wordsOf(listOf(c))).map { it.text }
    assertEquals(listOf("ہمم"), found)
  }

  @Test
  fun youKnowIsOneFillerPair() {
    val c = clip(Triple("It", 0, 150), Triple("you", 200, 150), Triple("know", 380, 200), Triple("works", 700, 300))
    val found = SpeechCleanup.fillerWords(SpeechCleanup.wordsOf(listOf(c)))
    assertEquals(listOf("you", "know"), found.map { it.text })
  }

  @Test
  fun likeIsOnlyRemovedWhenAsked() {
    val c = clip(Triple("It's", 0, 150), Triple("like", 200, 150), Triple("great", 400, 300))
    assertTrue(SpeechCleanup.fillerWords(SpeechCleanup.wordsOf(listOf(c))).isEmpty())
    assertEquals(1, SpeechCleanup.fillerWords(SpeechCleanup.wordsOf(listOf(c)), includeLike = true).size)
  }

  @Test
  fun rangesKeepAGuardAroundNeighbours() {
    val fillers = SpeechCleanup.fillerWords(SpeechCleanup.wordsOf(listOf(sample)))
    val ranges = SpeechCleanup.rangesFor(fillers, guardMs = 20)
    assertEquals(TimeRange(1270, 1530), ranges[0])
  }

  @Test
  fun tinyFillerIsSkippedRatherThanCutToNothing() {
    val c = clip(Triple("a", 0, 100), Triple("um", 120, 40), Triple("b", 200, 100))
    val ranges = SpeechCleanup.rangesFor(SpeechCleanup.fillerWords(SpeechCleanup.wordsOf(listOf(c))))
    assertTrue(ranges.isEmpty())
  }

  @Test
  fun longPauseIsShortenedNotDeleted() {
    val c = clip(Triple("one", 0, 300), Triple("two", 2300, 300))
    val pauses = SpeechCleanup.pauses(SpeechCleanup.wordsOf(listOf(c)), minGapMs = 700, keepMs = 250)
    assertEquals(1, pauses.size)
    assertEquals(1000L + 300 + 125, pauses[0].startMs)
    assertEquals(1000L + 2300 - 125, pauses[0].endMs)
    assertEquals(1750L, pauses[0].lengthMs)
  }

  @Test
  fun shortGapsAreLeftAlone() {
    val c = clip(Triple("one", 0, 300), Triple("two", 600, 300))
    assertTrue(SpeechCleanup.pauses(SpeechCleanup.wordsOf(listOf(c))).isEmpty())
  }

  @Test
  fun deletingWordsKeepsTheRestInSyncAndTrimsTheClip() {
    val out = CaptionWordOps.deleteWords(sample, setOf(0, 1))!!
    assertEquals("this is uh, great", out.text)
    assertEquals(1600L, out.timelineStartMs)
    assertEquals(0L, out.words[0].startMs)
    assertEquals(1800L - 600L, out.durationMs)
  }

  @Test
  fun deletingTheLastWordTrimsTheTail() {
    val out = CaptionWordOps.deleteWords(sample, setOf(5))!!
    assertEquals(1000L, out.timelineStartMs)
    assertEquals(1350L, out.durationMs)
    assertEquals("So um this is uh,", out.text)
  }

  @Test
  fun deletingEveryWordGivesNull() {
    assertNull(CaptionWordOps.deleteWords(sample, sample.words.indices.toSet()))
  }

  @Test
  fun editingAWordChangesTextAndTiming() {
    val out = CaptionWordOps.editWord(sample, 2, "that")
    assertEquals("So um that is uh, great", out.text)
    assertEquals("that", out.words[2].word)
    assertEquals(sample.words[2].startMs, out.words[2].startMs)
  }

  @Test
  fun blankEditChangesNothing() {
    assertEquals(sample, CaptionWordOps.editWord(sample, 2, "   "))
  }

  @Test
  fun retimeCannotOverlapNeighboursOrLeaveTheClip() {
    val a = CaptionWordOps.retimeWord(sample, 2, 100, 5000)
    assertTrue(a.words[2].startMs >= sample.words[1].startMs + sample.words[1].durationMs)
    assertTrue(a.words[2].startMs + a.words[2].durationMs <= sample.words[3].startMs)
    val b = CaptionWordOps.retimeWord(sample, 5, 1500, 5000)
    assertTrue(b.words[5].startMs + b.words[5].durationMs <= sample.durationMs)
    assertNotNull(b)
  }
}
