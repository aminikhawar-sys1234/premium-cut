package com.example.captions

import com.ahstudio.captions.audio.AudioChunk
import com.ahstudio.captions.core.model.CaptionWord
import com.ahstudio.captions.core.time.TimelineUs
import com.ahstudio.captions.recognition.SpeechSource
import com.ahstudio.captions.speaker.MfccDiarizationEngine
import com.ahstudio.captions.speaker.Mfcc
import com.ahstudio.captions.speaker.KMeans
import com.ahstudio.captions.speaker.Silhouette
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Pure-JVM tests for [MfccDiarizationEngine]. Audio is synthetic: two "voices" with a different pitch and
 * formant layout, separated by silence longer than the VAD merge gap (300 ms) so every burst becomes its own
 * speech region. All bursts / gaps are multiples of the 30 ms VAD frame (480 samples @ 16 kHz).
 */
class MfccDiarizationEngineTest {

  private val rate = 16_000
  private val lead = 4_800          // 300 ms of silence before the first burst
  private val burst = 12_480        // 26 VAD frames (~780 ms)
  private val gap = 12_480          // > 300 ms merge gap

  private val voiceA = Voice(f0 = 110.0, formants = listOf(500.0, 1500.0, 2500.0))
  private val voiceB = Voice(f0 = 240.0, formants = listOf(800.0, 2200.0, 3200.0))

  private class Voice(val f0: Double, val formants: List<Double>)

  private fun synth(voice: Voice, n: Int): FloatArray {
    val out = DoubleArray(n)
    var h = 1
    while (h * voice.f0 < 7_500.0) {
      val f = h * voice.f0
      val amp = voice.formants.sumOf { fm -> exp(-((f - fm) / 300.0).let { it * it }) } + 0.02
      for (i in 0 until n) out[i] += amp * sin(2.0 * PI * f * i / rate)
      h++
    }
    val peak = out.maxOf { kotlin.math.abs(it) }
    val ramp = (0.05 * rate).toInt()
    return FloatArray(n) { i ->
      val env = min(1.0, min(i.toDouble() / ramp, (n - 1 - i).toDouble() / ramp))
      (out[i] / peak * 0.5 * env).toFloat()
    }
  }

  /** Builds [lead silence][burst][gap][burst]... and returns the PCM source plus the start sample of every burst. */
  private fun buildAudio(voices: List<Voice>): Pair<SpeechSource.Pcm, List<Int>> {
    val total = lead + voices.size * (burst + gap)
    val samples = FloatArray(total)
    val starts = ArrayList<Int>()
    voices.forEachIndexed { i, v ->
      val start = lead + i * (burst + gap)
      starts += start
      synth(v, burst).copyInto(samples, start)
    }
    return SpeechSource.Pcm(rate, flowOf(AudioChunk(samples, 0L, rate))) to starts
  }

  private fun us(sample: Int) = TimelineUs(sample.toLong() * 1_000_000L / rate)

  /** Three words inside each burst. Returns words grouped by burst index. */
  private fun wordsFor(starts: List<Int>): List<List<CaptionWord>> = starts.mapIndexed { b, s ->
    (0 until 3).map { w ->
      CaptionWord("w${b}_$w", us(s + burst * (2 * w + 1) / 8), us(s + burst * (2 * w + 2) / 8))
    }
  }

  private fun diarize(voices: List<Voice>, engine: MfccDiarizationEngine = MfccDiarizationEngine()) = runBlocking {
    val (pcm, starts) = buildAudio(voices)
    val grouped = wordsFor(starts)
    val (tagged, speakers) = engine.assignSpeakers(grouped.flatten(), pcm)
    Triple(tagged.chunked(3), speakers, grouped)
  }

  // ---- fallbacks -------------------------------------------------------------------------------------------

  @Test
  fun `empty word list yields the single-speaker fallback`() = runBlocking {
    val (pcm, _) = buildAudio(listOf(voiceA, voiceB))
    val (tagged, speakers) = MfccDiarizationEngine().assignSpeakers(emptyList(), pcm)
    assertTrue(tagged.isEmpty())
    assertEquals(listOf("spk_1"), speakers.map { it.id })
    assertEquals("Speaker 1", speakers.single().label)
  }

  @Test
  fun `missing audio yields one speaker for every word`() = runBlocking {
    val words = listOf(CaptionWord("a", us(0), us(1000)), CaptionWord("b", us(2000), us(3000)))
    val (tagged, speakers) = MfccDiarizationEngine().assignSpeakers(words, null)
    assertEquals(1, speakers.size)
    assertTrue(tagged.all { it.speakerId == "spk_1" })
  }

  @Test
  fun `digital silence yields one speaker`() = runBlocking {
    val pcm = SpeechSource.Pcm(rate, flowOf(AudioChunk(FloatArray(rate * 3), 0L, rate)))
    val words = listOf(CaptionWord("a", us(1000), us(4000)), CaptionWord("b", us(20_000), us(30_000)))
    val (tagged, speakers) = MfccDiarizationEngine().assignSpeakers(words, pcm)
    assertEquals(1, speakers.size)
    assertTrue(tagged.all { it.speakerId == "spk_1" })
  }

  // ---- real clustering -------------------------------------------------------------------------------------

  @Test
  fun `two alternating voices are split into two speakers`() {
    val (byBurst, speakers, _) = diarize(listOf(voiceA, voiceB, voiceA, voiceB))
    assertEquals(2, speakers.size)
    val idA = byBurst[0].first().speakerId
    val idB = byBurst[1].first().speakerId
    assertNotEquals(idA, idB)
    // every word of a burst carries the same id, and the same voice always maps to the same speaker
    byBurst.forEach { burstWords -> assertEquals(1, burstWords.map { it.speakerId }.toSet().size) }
    assertEquals(idA, byBurst[2].first().speakerId)
    assertEquals(idB, byBurst[3].first().speakerId)
  }

  @Test
  fun `speakers are numbered in order of first appearance`() {
    val (byBurst, speakers, _) = diarize(listOf(voiceB, voiceA, voiceB, voiceA))
    assertEquals(listOf("spk_1", "spk_2"), speakers.map { it.id })
    assertEquals(listOf("Speaker 1", "Speaker 2"), speakers.map { it.label })
    assertEquals("spk_1", byBurst[0].first().speakerId) // voiceB spoke first
    assertEquals("spk_2", byBurst[1].first().speakerId)
  }

  @Test
  fun `each detected speaker gets its own colour`() {
    val (_, speakers, _) = diarize(listOf(voiceA, voiceB, voiceA, voiceB))
    assertEquals(speakers.size, speakers.map { it.colorArgb }.toSet().size)
  }

  @Test
  fun `word order text and timing are preserved`() {
    val (byBurst, _, original) = diarize(listOf(voiceA, voiceB, voiceA, voiceB))
    val before = original.flatten()
    val after = byBurst.flatten()
    assertEquals(before.map { it.text }, after.map { it.text })
    assertEquals(before.map { it.start }, after.map { it.start })
    assertEquals(before.map { it.end }, after.map { it.end })
  }

  @Test
  fun `maxSpeakers of one never splits`() {
    val (byBurst, speakers, _) = diarize(listOf(voiceA, voiceB, voiceA, voiceB), MfccDiarizationEngine(maxSpeakers = 1))
    assertEquals(1, speakers.size)
    assertTrue(byBurst.flatten().all { it.speakerId == "spk_1" })
  }

  @Test
  fun `same input and seed give the same result`() {
    val a = diarize(listOf(voiceA, voiceB, voiceA, voiceB)).first.flatten().map { it.speakerId }
    val b = diarize(listOf(voiceA, voiceB, voiceA, voiceB)).first.flatten().map { it.speakerId }
    assertEquals(a, b)
  }

  // ---- building blocks -------------------------------------------------------------------------------------

  @Test
  fun `mfcc of a too-short buffer is the zero vector`() {
    val v = Mfcc.mean(FloatArray(100), 0, 100, rate)
    assertEquals(Mfcc.MFCC_DIM, v.size)
    assertTrue(v.all { it == 0f })
  }

  @Test
  fun `mfcc differs clearly between two voices and is stable for the same voice`() {
    val a1 = Mfcc.mean(synth(voiceA, burst), 0, burst, rate)
    val a2 = Mfcc.mean(synth(voiceA, burst), 0, burst, rate)
    val b = Mfcc.mean(synth(voiceB, burst), 0, burst, rate)
    assertArrayEquals(a1, a2, 1e-6f)
    assertTrue(KMeans.dist2(a1, b) > 100.0 * (KMeans.dist2(a1, a2) + 1e-6))
  }

  @Test
  fun `kmeans separates two obvious groups`() {
    val pts = listOf(
      floatArrayOf(0f, 0f), floatArrayOf(0.1f, 0f), floatArrayOf(0f, 0.1f),
      floatArrayOf(10f, 10f), floatArrayOf(10.1f, 10f), floatArrayOf(10f, 10.1f),
    )
    val a = KMeans.run(pts, 2, seed = 42).assignments
    assertEquals(a[0], a[1]); assertEquals(a[0], a[2])
    assertEquals(a[3], a[4]); assertEquals(a[3], a[5])
    assertNotEquals(a[0], a[3])
  }

  @Test
  fun `silhouette is high for clean clusters`() {
    val pts = listOf(floatArrayOf(1f, 0f), floatArrayOf(1f, 0.05f), floatArrayOf(0f, 1f), floatArrayOf(0.05f, 1f))
    assertTrue(Silhouette.score(intArrayOf(0, 0, 1, 1), pts) > 0.9f)
  }

  /** Regression: used to throw NoSuchElementException when every point sat in one cluster. */
  @Test
  fun `silhouette with a single cluster does not throw and reports undefined`() {
    val pts = List(5) { floatArrayOf(1f, 2f, 3f) }
    assertEquals(-2f, Silhouette.score(IntArray(5), pts), 0f)
  }

  /** Regression: the sampled subset can miss a whole cluster; that point must be skipped, not crash. */
  @Test
  fun `silhouette tolerates a cluster that the sample misses`() {
    val n = 1_000
    val pts = List(n) { i -> if (i < n - 1) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f) }
    val assign = IntArray(n) { if (it < n - 1) 0 else 1 }
    val s = Silhouette.score(assign, pts, sample = 200)
    assertFalse(s.isNaN())
  }
}
