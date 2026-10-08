package com.example.engine.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The audio mixer used to decode every source file in full, even when a clip only used a few
 * seconds of it (a 10 minute song trimmed to 10 seconds was decoded completely, boxed byte per
 * byte). These tests pin the source window that bounds that decode, and through it the clip
 * in-point that the mix starts from.
 */
class AudioExportWindowTest {

  private fun track(
    durationMs: Long,
    sourceStartMs: Long = 0L,
    sourceEndMs: Long = 0L,
    speed: Float = 1f
  ) = AudioTrackDescriptor(
    uri = "content://media/song.m4a",
    title = "song",
    timelineStartMs = 2_000L,
    durationMs = durationMs,
    sourceStartMs = sourceStartMs,
    sourceEndMs = sourceEndMs,
    speed = speed,
    volume = 1f,
    gainDb = 0f,
    fadeInMs = 0L,
    fadeOutMs = 0L,
    isMuted = false
  )

  private fun window(t: AudioTrackDescriptor): Pair<Long, Long> = audioSourceWindow(t)

  @Test
  fun untrimmedClipDecodesOnlyTheUsedSpan() {
    // 10 s clip taken from the head of a long file: no reason to decode past ~10.25 s.
    val (start, end) = window(track(durationMs = 10_000L))
    assertEquals(0L, start)
    assertTrue("window must stay near the used span, was ${end}ms", end <= 10_500L)
    assertTrue("window must cover the used span, was ${end}ms", end >= 10_000L)
  }

  @Test
  fun trimmedClipStartsAtItsInPoint() {
    val (start, end) = window(track(durationMs = 5_000L, sourceStartMs = 30_000L, sourceEndMs = 45_000L))
    assertEquals(30_000L, start)
    assertTrue("must not decode past the clip end, was ${end}ms", end <= 35_500L)
    assertTrue("must cover the clip, was ${end}ms", end >= 35_000L)
  }

  @Test
  fun fastMotionNeedsMoreSourceMaterial() {
    // 2x speed: 10 s of timeline consumes 20 s of source (sourceMs = rel * speed).
    val (_, end) = window(track(durationMs = 10_000L, speed = 2f))
    assertTrue("2x speed must read ~20 s of source, got ${end}ms", end >= 20_000L)
    assertTrue("and not much more, got ${end}ms", end <= 21_000L)
  }

  @Test
  fun slowMotionNeedsLessSourceMaterial() {
    // 0.5x speed stretches 5 s of source over 10 s of timeline.
    val (_, end) = window(track(durationMs = 10_000L, speed = 0.5f))
    assertTrue("0.5x speed must read ~5 s of source, got ${end}ms", end >= 5_000L)
    assertTrue("and not much more, got ${end}ms", end <= 6_000L)
  }

  @Test
  fun explicitSourceEndStillClampsTheWindow() {
    val (start, end) = window(track(durationMs = 60_000L, sourceStartMs = 1_000L, sourceEndMs = 4_000L))
    assertEquals(1_000L, start)
    assertEquals(4_000L, end)
  }

  @Test
  fun reversedClipIsAnchoredToTheSourceEnd() {
    // Reversed playback walks backwards from sourceEndMs, so that is where the window must end.
    val reversed = track(durationMs = 4_000L, sourceStartMs = 10_000L, sourceEndMs = 20_000L).copy(isReversed = true)
    val (start, end) = window(reversed)
    assertEquals(20_000L, end)
    assertTrue("must cover the played span, got start=${start}ms", start <= 16_000L)
    assertTrue("and not decode the whole file, got start=${start}ms", start >= 15_000L)
  }

  @Test
  fun windowIsNeverEmpty() {
    val (start, end) = window(track(durationMs = 0L, sourceStartMs = 500L, sourceEndMs = 0L))
    assertEquals(500L, start)
    assertTrue(end > start)
  }
}
