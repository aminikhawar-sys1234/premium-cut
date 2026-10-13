package com.example

import com.example.engine.audio.BeatDetector
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BeatDetectorTest {

  private val rate = 22_050

  /** Kick-like clicks every beat over a quiet noise bed, optionally with a weaker off-beat hat. */
  private fun clickTrack(bpm: Double, seconds: Double, offsetMs: Double = 0.0, offBeat: Boolean = true): FloatArray {
    val n = (seconds * rate).toInt()
    val out = FloatArray(n)
    val rnd = Random(7)
    for (i in 0 until n) out[i] = (rnd.nextFloat() - 0.5f) * 0.02f
    val beatSec = 60.0 / bpm
    var t = offsetMs / 1000.0
    while (t < seconds) {
      addHit(out, (t * rate).toInt(), 0.9f, 90.0)
      if (offBeat) addHit(out, ((t + beatSec / 2) * rate).toInt(), 0.25f, 400.0)
      t += beatSec
    }
    return out
  }

  private fun addHit(out: FloatArray, at: Int, amp: Float, freq: Double) {
    val len = (0.06 * rate).toInt()
    for (i in 0 until len) {
      val idx = at + i
      if (idx >= out.size) return
      val env = exp(-i / (0.012 * rate)).toFloat()
      out[idx] += amp * env * sin(2.0 * PI * freq * i / rate).toFloat() + amp * 0.6f * env * (if (i % 2 == 0) 1f else -1f)
    }
  }

  private fun assertBeatsOnGrid(beats: List<Long>, bpm: Double, offsetMs: Double, toleranceMs: Double) {
    val period = 60_000.0 / bpm
    var good = 0
    for (b in beats) {
      val k = Math.round((b - offsetMs) / period)
      if (abs(b - (offsetMs + k * period)) <= toleranceMs) good++
    }
    assertTrue("only $good of ${beats.size} beats on the $bpm grid: $beats", good >= beats.size * 0.9)
  }

  @Test
  fun finds120Bpm() {
    val a = BeatDetector.analyze(clickTrack(120.0, 20.0), rate)
    assertTrue(a.hasBeat)
    assertEquals(120.0, a.bpm, 2.0)
    assertBeatsOnGrid(a.beatsMs, 120.0, 0.0, 45.0)
    assertTrue("expected ~40 beats, got ${a.beatsMs.size}", a.beatsMs.size in 36..42)
  }

  @Test
  fun finds90Bpm() {
    val a = BeatDetector.analyze(clickTrack(90.0, 24.0), rate)
    assertEquals(90.0, a.bpm, 2.0)
    assertBeatsOnGrid(a.beatsMs, 90.0, 0.0, 45.0)
  }

  @Test
  fun finds140BpmWithOffset() {
    val a = BeatDetector.analyze(clickTrack(140.0, 20.0, offsetMs = 230.0), rate)
    assertEquals(140.0, a.bpm, 3.0)
    assertBeatsOnGrid(a.beatsMs, 140.0, 230.0, 45.0)
  }

  @Test
  fun beatTimesAreSortedAndSpacedLikeTheTempo() {
    val a = BeatDetector.analyze(clickTrack(100.0, 20.0), rate)
    val gaps = a.beatsMs.zipWithNext { x, y -> y - x }
    assertTrue(gaps.all { it > 0 })
    assertTrue("gaps should hover near 600 ms: $gaps", gaps.count { abs(it - 600) < 70 } >= gaps.size * 0.9)
  }

  @Test
  fun confidenceIsHighForCleanBeat() {
    val a = BeatDetector.analyze(clickTrack(120.0, 20.0), rate)
    assertTrue("confidence ${a.confidence}", a.confidence > 0.7f)
  }

  @Test
  fun silenceHasNoBeats() {
    val a = BeatDetector.analyze(FloatArray(rate * 10), rate)
    assertFalse(a.hasBeat)
    assertTrue(a.beatsMs.isEmpty())
  }

  @Test
  fun tooShortBufferIsRejected() {
    assertFalse(BeatDetector.analyze(FloatArray(rate), rate).hasBeat)
  }

  @Test
  fun monoConversionAveragesChannelsAndDecimates() {
    val pcm = ShortArray(4800 * 2) { if (it % 2 == 0) 16384 else -16384 }
    val (mono, rateOut) = BeatDetector.toMono(pcm, 2, 44_100, 22_050)
    assertEquals(22_050, rateOut)
    assertEquals(2400, mono.size)
    assertTrue(mono.all { abs(it) < 1e-4f })
  }
}
