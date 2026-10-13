package com.example.engine.captions

import com.example.domain.model.TextClip
import com.example.domain.model.WordTiming
import com.example.engine.timeline.TimelineRangeOps
import com.example.engine.timeline.TimelineRangeOps.TimeRange

/** One recognised word on the timeline, with the caption it came from. */
data class TimedWord(
  val text: String,
  val startMs: Long,
  val endMs: Long,
  val clipId: String,
  val indexInClip: Int
)

/**
 * Finds the parts of a spoken edit that CapCut-style one-tap tools remove: filler words
 * ("um", "uh", "you know") and long pauses. Works on the word timings of the caption clips, so it
 * needs no extra audio analysis. The results are plain ranges for
 * [com.example.engine.timeline.TimelineRangeOps.removeRanges].
 */
object SpeechCleanup {

  private val SINGLE_FILLERS = setOf(
    "um", "umm", "ummm", "uh", "uhh", "uhm", "uhhh", "er", "erm", "err", "ah", "ahh", "eh", "hmm", "hm", "hmmm", "mm", "mmm", "mhm",
    // Hindi / Urdu hesitation sounds as written by recognisers. Real words such as "ہم" (we) are left out.
    "अं", "उम्म", "उम", "हम्म", "ہمم", "امم", "آں", "اںں"
  )

  private val PAIR_FILLERS = setOf("you know", "i mean", "you see")

  /** Lower case, no surrounding punctuation. */
  internal fun normalize(word: String): String =
    word.trim().lowercase().trim { !it.isLetterOrDigit() && it != '\'' }

  fun wordsOf(textClips: List<TextClip>): List<TimedWord> =
    textClips.filter { it.words.isNotEmpty() }
      .sortedBy { it.timelineStartMs }
      .flatMap { clip ->
        clip.words.mapIndexed { i, w ->
          TimedWord(
            text = w.word,
            startMs = clip.timelineStartMs + w.startMs,
            endMs = clip.timelineStartMs + w.startMs + w.durationMs,
            clipId = clip.id,
            indexInClip = i
          )
        }
      }

  /**
   * Words to cut. [includeLike] also takes the verbal-tic "like" and "basically"; off by default
   * because they are often real words.
   */
  fun fillerWords(words: List<TimedWord>, includeLike: Boolean = false): List<TimedWord> {
    val out = ArrayList<TimedWord>()
    var i = 0
    while (i < words.size) {
      val w = words[i]
      val n = normalize(w.text)
      val next = words.getOrNull(i + 1)
      val pair = if (next != null) "$n ${normalize(next.text)}" else ""
      when {
        next != null && pair in PAIR_FILLERS && next.startMs - w.endMs < 400 -> { out += w; out += next; i += 2; continue }
        n in SINGLE_FILLERS -> out += w
        includeLike && (n == "like" || n == "basically" || n == "actually") -> out += w
      }
      i++
    }
    return out
  }

  /**
   * Ranges for the given words, with [guardMs] kept on each side so a neighbouring word's attack or
   * tail is not clipped. Touching ranges are merged.
   */
  fun rangesFor(words: List<TimedWord>, guardMs: Long = 20L): List<TimeRange> =
    TimelineRangeOps.normalize(
      words.mapNotNull {
        val s = it.startMs + guardMs
        val e = it.endMs - guardMs
        if (e - s >= 30L) TimeRange(s, e) else null
      }
    )

  /**
   * Pauses longer than [minGapMs] between two consecutive words, shortened to [keepMs] so speech
   * still breathes. Gaps are measured only inside one continuous speech run.
   */
  fun pauses(words: List<TimedWord>, minGapMs: Long = 700L, keepMs: Long = 250L): List<TimeRange> {
    val sorted = words.sortedBy { it.startMs }
    val out = ArrayList<TimeRange>()
    for (i in 0 until sorted.size - 1) {
      val gapStart = sorted[i].endMs
      val gapEnd = sorted[i + 1].startMs
      if (gapEnd - gapStart >= minGapMs) {
        val cutStart = gapStart + keepMs / 2
        val cutEnd = gapEnd - keepMs / 2
        if (cutEnd - cutStart >= 100L) out += TimeRange(cutStart, cutEnd)
      }
    }
    return out
  }
}

/**
 * Word-level editing of one caption clip: change a word, move its timing, or delete words, while
 * [TextClip.text] and [TextClip.words] stay in step (the renderer shows `text`, karaoke uses `words`).
 */
object CaptionWordOps {

  /** Words as a caption shows them: one token per timing. Falls back to the timings themselves. */
  fun tokens(clip: TextClip): List<String> {
    val parts = clip.text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return if (clip.words.isNotEmpty() && parts.size == clip.words.size) parts else clip.words.map { it.word }
  }

  fun withText(clip: TextClip, tokens: List<String>, words: List<WordTiming>): TextClip =
    clip.copy(text = tokens.joinToString(" "), words = words)

  fun editWord(clip: TextClip, index: Int, newText: String): TextClip {
    if (index !in clip.words.indices) return clip
    val clean = newText.trim().replace(Regex("\\s+"), " ")
    if (clean.isEmpty()) return clip
    val tokens = tokens(clip).toMutableList()
    tokens[index] = clean
    val words = clip.words.toMutableList()
    words[index] = words[index].copy(word = clean)
    return withText(clip, tokens, words)
  }

  /**
   * Moves a word's start and length. Neighbours are not allowed to overlap, and the word stays
   * inside the clip. Minimum length 80 ms.
   */
  fun retimeWord(clip: TextClip, index: Int, startMs: Long, durationMs: Long): TextClip {
    if (index !in clip.words.indices) return clip
    val minStart = if (index > 0) clip.words[index - 1].let { it.startMs + it.durationMs } else 0L
    val maxEnd = if (index < clip.words.size - 1) clip.words[index + 1].startMs else clip.durationMs
    val start = startMs.coerceIn(minStart, (maxEnd - 80L).coerceAtLeast(minStart))
    val end = (start + durationMs.coerceAtLeast(80L)).coerceAtMost(maxEnd)
    val words = clip.words.toMutableList()
    words[index] = words[index].copy(startMs = start, durationMs = (end - start).coerceAtLeast(1L))
    return clip.copy(words = words)
  }

  /**
   * Removes words from the caption. Remaining timings keep their place, so the caption stays in
   * sync with the audio. Returns the clip trimmed to its remaining words, or null if none remain.
   */
  fun deleteWords(clip: TextClip, indices: Set<Int>): TextClip? {
    if (clip.words.isEmpty()) return clip
    val tokens = tokens(clip)
    val keep = clip.words.indices.filter { it !in indices }
    if (keep.isEmpty()) return null
    val words = keep.map { clip.words[it] }
    val remainingTokens = keep.map { tokens.getOrElse(it) { clip.words[it].word } }
    val first = words.first().startMs
    val last = words.last().let { it.startMs + it.durationMs }
    // Trim leading and trailing dead time so the caption is not shown with nobody speaking.
    val shifted = words.map { it.copy(startMs = it.startMs - first) }
    return clip.copy(
      text = remainingTokens.joinToString(" "),
      words = shifted,
      timelineStartMs = clip.timelineStartMs + first,
      durationMs = (last - first).coerceAtLeast(100L)
    )
  }
}
