package com.example.engine.export

import com.example.domain.model.AudioEffectsSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chunked PCM mix replaced "allocate the whole timeline twice, decode every source in full,
 * then mix" with "decode one window per track, mix a few seconds at a time".
 *
 * That only keeps the sound identical if the arithmetic around the chunk boundaries is exact: the
 * chunks must cover the timeline once, in order, with no gap and no overlap; a track must be read
 * only inside its own trim window; and the source window a streaming decode asks for must be exactly
 * the material that chunk reads. These tests pin that arithmetic - it is the part that could quietly
 * shift every sample of a ten minute export by a few milliseconds.
 */
class AudioMixPlanTest {

  private fun track(
    timelineStartMs: Long = 0L,
    durationMs: Long = 60_000L,
    sourceStartMs: Long = 0L,
    sourceEndMs: Long = 0L,
    speed: Float = 1f,
    isReversed: Boolean = false,
    audioEffects: AudioEffectsSettings = AudioEffectsSettings()
  ) = AudioTrackDescriptor(
    uri = "content://media/song.m4a",
    title = "song",
    timelineStartMs = timelineStartMs,
    durationMs = durationMs,
    sourceStartMs = sourceStartMs,
    sourceEndMs = sourceEndMs,
    speed = speed,
    volume = 1f,
    gainDb = 0f,
    fadeInMs = 0L,
    fadeOutMs = 0L,
    isMuted = false,
    isReversed = isReversed,
    audioEffects = audioEffects
  )

  // ------------------------------------------------------------------ chunking

  @Test
  fun chunkSizeIsFiveSecondsOfTheTargetRateAndNeverTiny() {
    assertEquals(240_000, AudioMixPlan.chunkFrames(48_000))
    assertEquals(40_000, AudioMixPlan.chunkFrames(8_000))
    // A silly rate must not create thousands of chunks.
    assertEquals(AudioMixPlan.MIN_CHUNK_FRAMES, AudioMixPlan.chunkFrames(1))
    assertEquals(AudioMixPlan.MIN_CHUNK_FRAMES, AudioMixPlan.chunkFrames(48_000, chunkMs = 1L))
  }

  @Test
  fun aTenMinuteTimelineIsChunkedIntoBoundedPieces() {
    val targetRate = 48_000
    val totalFrames = (10 * 60 * targetRate)
    val chunks = AudioMixPlan.chunks(totalFrames, AudioMixPlan.chunkFrames(targetRate))
    assertEquals(120, chunks.size)
    assertEquals(240_000, chunks.first().frameCount)
  }

  @Test
  fun chunksCoverEveryTimelineFrameExactlyOnceInOrder() {
    for (total in intArrayOf(0, 1, 99, 100, 250, 1_000, 617_283)) {
      val chunks = AudioMixPlan.chunks(total, chunkFrames = 100)
      var expectedStart = 0
      for ((index, chunk) in chunks.withIndex()) {
        assertEquals("chunk $index of $total starts out of order", expectedStart, chunk.startFrame)
        assertTrue("chunk $index of $total is empty", chunk.frameCount > 0)
        assertTrue("chunk $index of $total is longer than the chunk size", chunk.frameCount <= 100)
        expectedStart = chunk.endFrame
      }
      assertEquals("the chunks of $total must end at the timeline end", total, expectedStart)
    }
    assertEquals(0, AudioMixPlan.chunks(0, 100).size)
    assertEquals(0, AudioMixPlan.chunkCount(0, 100))
  }

  // ------------------------------------------------------------------ a track's own window

  @Test
  fun trackStartAndLengthKeepTheRoundingTheMixAlwaysUsed() {
    val t = track(timelineStartMs = 2_500L, durationMs = 3_000L)
    assertEquals((2.5 * 48_000).toInt(), AudioMixPlan.trackStartFrame(t, 48_000))
    assertEquals((3.0 * 48_000).toInt(), AudioMixPlan.trackFrameCount(t, 48_000))
    assertEquals(0, AudioMixPlan.trackFrameCount(track(durationMs = 0L), 48_000))
  }

  @Test
  fun aTrackIsOnlyReadInsideItsOwnTrimWindow() {
    val t = track(timelineStartMs = 1_000L, durationMs = 2_000L)
    val rate = 48_000
    // Frames before the clip starts and after it ends must be untouched.
    assertTrue(AudioMixPlan.trackFramesInChunk(t, AudioMixPlan.chunk(0, 300_000, 100), rate).isEmpty())
    assertTrue(AudioMixPlan.trackFramesInChunk(t, AudioMixPlan.chunk(299, 300_000, 100), rate).isEmpty())
    // The chunk that covers the clip end must stop exactly at the end frame.
    val endFrame = AudioMixPlan.trackStartFrame(t, rate) + AudioMixPlan.trackFrameCount(t, rate)
    val covering = AudioMixPlan.chunks(endFrame, 100)
    val last = AudioMixPlan.trackFramesInChunk(t, covering.last(), rate)
    assertEquals(endFrame - 1, last.last)
    // And the count of frames the clip owns is exactly its timeline length.
    val frames = AudioMixPlan.chunks(endFrame, 1_000)
      .sumOf { chunk -> AudioMixPlan.trackFramesInChunk(t, chunk, rate).count().toLong() }
    assertEquals(AudioMixPlan.trackFrameCount(t, rate).toLong(), frames)
  }

  // ------------------------------------------------------------------ which tracks may be streamed

  @Test
  fun plainForwardUnitySpeedTracksCanBeDecodedWindowByWindow() {
    assertTrue(AudioMixPlan.isStreamable(track()))
    assertFalse("a reversed track is a whole-buffer operation", AudioMixPlan.isStreamable(track(isReversed = true)))
    assertFalse("a speed stretch is a whole-buffer operation", AudioMixPlan.isStreamable(track(speed = 2f)))
    assertFalse("an effect chain is a whole-buffer operation", AudioMixPlan.isStreamable(track(speed = 0.5f)))
    assertFalse(
      "an active effect chain keeps the whole-buffer decode",
      AudioMixPlan.isStreamable(track(audioEffects = AudioEffectsSettings(normalizeVolume = true)))
    )
  }

  // ------------------------------------------------------------------ window arithmetic

  @Test
  fun sampleIndexIsTheFormulaTheWholeBufferMixerUsed() {
    // relFrame * sourceRate / targetRate, truncated - bit for bit the old mixer's index.
    assertEquals(0, AudioMixPlan.sampleIndex(0, 44_100, 48_000))
    assertEquals(44_100, AudioMixPlan.sampleIndex(48_000, 44_100, 48_000))
    assertEquals(48_000, AudioMixPlan.sampleIndex(48_000, 48_000, 48_000))
    assertEquals((1_234.0 * 44_100 / 48_000).toInt(), AudioMixPlan.sampleIndex(1_234, 44_100, 48_000))
    // A negative frame must clamp, not index backwards.
    assertEquals(0, AudioMixPlan.sampleIndex(-500, 48_000, 48_000))
    assertEquals(0, AudioMixPlan.sampleIndex(10, 48_000, 0))
  }

  @Test
  fun windowOriginIsTheInPointRelativeSampleASampleMappingStartsFrom() {
    val t = track(sourceStartMs = 4_000L)
    assertEquals(0, AudioMixPlan.windowOriginSample(t, 4_000L, 48_000))
    assertEquals(48_000, AudioMixPlan.windowOriginSample(t, 5_000L, 48_000))
    assertEquals((2_500L * 44_100 / 1_000L).toInt(), AudioMixPlan.windowOriginSample(t, 6_500L, 44_100))
    // A window that starts before the in-point clamps to the in-point.
    assertEquals(0, AudioMixPlan.windowOriginSample(t, 0L, 48_000))
  }

  @Test
  fun streamingWindowsAreOrderedAndCoverTheChunkTheyAreFor() {
    val rate = 48_000
    val t = track(timelineStartMs = 3_000L, durationMs = 30_000L, sourceStartMs = 10_000L)
    val chunks = AudioMixPlan.chunks(AudioMixPlan.trackStartFrame(t, rate) + AudioMixPlan.trackFrameCount(t, rate), 240_000)
    var previousStart = Long.MIN_VALUE
    for (chunk in chunks) {
      val (startMs, endMs) = AudioMixPlan.sourceWindowMs(t, chunk, rate, rate)
      assertTrue("window start must not walk backwards", startMs >= previousStart)
      assertTrue("window must not be empty", endMs > startMs)
      previousStart = startMs
      // The window starts at the clip in-point (a forward, unity-speed track reads the source
      // linearly from `sourceStartMs`).
      assertTrue(startMs >= t.sourceStartMs)
      // ...and it must cover the chunk's last frame, plus the tail margin.
      val lastFrame = (minOf(chunk.endFrame, AudioMixPlan.trackStartFrame(t, rate) + AudioMixPlan.trackFrameCount(t, rate)) - 1)
      val lastSample = AudioMixPlan.sampleIndex(lastFrame - AudioMixPlan.trackStartFrame(t, rate), rate, rate)
      val lastSampleMs = t.sourceStartMs + lastSample * 1_000L / rate
      assertTrue(
        "window [$startMs, $endMs) must reach the chunk's last sample at ${lastSampleMs}ms",
        endMs > lastSampleMs + AudioMixPlan.SOURCE_TAIL_MARGIN_MS - 1L
      )
    }
  }

  @Test
  fun aStreamingWindowStartsAtOrBeforeTheFirstSampleItsChunkNeeds() {
    // This is the invariant that makes the chunked read correct: the mixer indexes a window as
    // `sampleIndex(relativeFrame) - windowOriginSample(windowStart)`, so a window that starts *after*
    // the sample its chunk needs would push the first frames of that chunk out of the buffer - they
    // would be read as silence and the mix would lose up to a millisecond at every chunk boundary.
    val rate = 48_000
    for (sourceRate in intArrayOf(44_100, 48_000, 22_050)) {
      val t = track(timelineStartMs = 1_000L, durationMs = 30_000L, sourceStartMs = 2_000L)
      val trackStart = AudioMixPlan.trackStartFrame(t, rate)
      val chunks = AudioMixPlan.chunks(trackStart + AudioMixPlan.trackFrameCount(t, rate), 7_919)
      for (chunk in chunks) {
        val firstRelFrame = (chunk.startFrame - trackStart).coerceAtLeast(0)
        val firstSample = AudioMixPlan.sampleIndex(firstRelFrame, sourceRate, rate)
        val (startMs, _) = AudioMixPlan.sourceWindowMs(t, chunk, rate, sourceRate)
        val origin = AudioMixPlan.windowOriginSample(t, startMs, sourceRate)
        assertTrue(
          "sourceRate=$sourceRate chunk=${chunk.index}: window must start at or before sample " +
            "$firstSample (starts at $origin)",
          origin <= firstSample
        )
        // The window must still be tight: the gap can never exceed one sample's worth of time
        // rounded to a millisecond, plus the window's own alignment.
        assertTrue(
          "sourceRate=$sourceRate chunk=${chunk.index}: window starts too far back ($firstSample vs $origin)",
          firstSample - origin <= sourceRate / 500
        )
      }
    }
  }

  @Test
  fun theTailMarginAbsorbsCodecPrimingButStaysSmall() {
    // A few hundred milliseconds: enough for decoder priming at a chunk boundary, small enough that
    // a window never grows into "decode the rest of the file".
    assertTrue(AudioMixPlan.SOURCE_TAIL_MARGIN_MS in 1..1_000L)
    val rate = 48_000
    val t = track(durationMs = 600_000L)
    val chunk = AudioMixPlan.chunks(600_000, 50_000)[5]
    val (start, end) = AudioMixPlan.sourceWindowMs(t, chunk, rate, rate)
    val spanMs = end - start
    assertTrue("a 5s chunk must not decode more than ~6s of source, was ${spanMs}ms", spanMs <= 6_500L)
  }
}
