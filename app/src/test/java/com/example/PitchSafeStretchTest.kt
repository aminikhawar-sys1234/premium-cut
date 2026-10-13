package com.example

import com.example.engine.audio.PitchSafeStretch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PitchSafeStretchTest {

  private fun assertNear(expected: Int, actual: Int, delta: Int) =
    assertTrue("expected $expected +-$delta, got $actual", abs(expected - actual) <= delta)

  private val rate = 48_000

  private fun sine(freq: Double, seconds: Double, channels: Int = 2, amp: Double = 12_000.0): ShortArray {
    val frames = (seconds * rate).toInt()
    val out = ShortArray(frames * channels)
    for (i in 0 until frames) {
      val v = (amp * sin(2.0 * PI * freq * i / rate)).toInt().toShort()
      for (c in 0 until channels) out[i * channels + c] = v
    }
    return out
  }

  /** Dominant frequency from rising zero crossings of the left channel. */
  private fun estimateHz(pcm: ShortArray, channels: Int): Double {
    val frames = pcm.size / channels
    var crossings = 0
    for (i in 1 until frames) {
      if (pcm[(i - 1) * channels] < 0 && pcm[i * channels] >= 0) crossings++
    }
    return crossings / (frames.toDouble() / rate)
  }

  private fun rms(pcm: ShortArray, from: Int, to: Int): Double {
    var s = 0.0
    for (i in from until to) s += pcm[i].toDouble() * pcm[i]
    return sqrt(s / (to - from))
  }

  @Test
  fun doubleSpeedHalvesLengthAndKeepsPitch() {
    val input = sine(440.0, 3.0)
    val out = PitchSafeStretch.applySpeed(input, 2, rate, 2f)
    assertNear(input.size / 2, out.size, 8)
    val hz = estimateHz(out, 2)
    assertEquals("pitch must stay at 440 Hz, got $hz", 440.0, hz, 8.0)
  }

  @Test
  fun halfSpeedDoublesLengthAndKeepsPitch() {
    val input = sine(220.0, 2.0)
    val out = PitchSafeStretch.applySpeed(input, 2, rate, 0.5f)
    assertNear(input.size * 2, out.size, 8)
    assertEquals(220.0, estimateHz(out, 2), 6.0)
  }

  @Test
  fun sampleSkippingWouldHaveDoubledPitch() {
    val input = sine(440.0, 2.0)
    val skipped = ShortArray(input.size / 2)
    for (f in 0 until skipped.size / 2) {
      skipped[f * 2] = input[f * 4]
      skipped[f * 2 + 1] = input[f * 4 + 1]
    }
    assertEquals(880.0, estimateHz(skipped, 2), 10.0)
    val stretched = PitchSafeStretch.applySpeed(input, 2, rate, 2f)
    assertTrue(estimateHz(stretched, 2) < 500.0)
  }

  @Test
  fun levelStaysClose() {
    val input = sine(330.0, 3.0, amp = 10_000.0)
    val out = PitchSafeStretch.applySpeed(input, 2, rate, 1.5f)
    val mid = out.size / 2
    val inRms = rms(input, input.size / 2, input.size / 2 + 20_000)
    val outRms = rms(out, mid, mid + 20_000)
    assertEquals(inRms, outRms, inRms * 0.12)
  }

  @Test
  fun monoInputWorks() {
    val input = sine(500.0, 2.0, channels = 1)
    val out = PitchSafeStretch.applySpeed(input, 1, rate, 3f)
    assertNear(input.size / 3, out.size, 4)
    assertEquals(500.0, estimateHz(out, 1), 12.0)
  }

  @Test
  fun unitySpeedReturnsSameBuffer() {
    val input = sine(440.0, 0.5)
    assertSame(input, PitchSafeStretch.applySpeed(input, 2, rate, 1.0f))
    assertTrue(PitchSafeStretch.isUnity(1.002f))
  }

  @Test
  fun veryShortClipDoesNotCrashAndHasRightLength() {
    val input = ShortArray(2 * 200) { (it % 50).toShort() }
    val out = PitchSafeStretch.applySpeed(input, 2, rate, 2f)
    assertEquals(100 * 2, out.size)
  }

  @Test
  fun emptyInputIsReturned() {
    val empty = ShortArray(0)
    assertSame(empty, PitchSafeStretch.applySpeed(empty, 2, rate, 2f))
  }

  @Test
  fun outputFramesMatchesSpeed() {
    assertEquals(500, PitchSafeStretch.outputFrames(1000, 2f))
    assertTrue(abs(PitchSafeStretch.outputFrames(1000, 0.5f) - 2000) <= 1)
  }
}
