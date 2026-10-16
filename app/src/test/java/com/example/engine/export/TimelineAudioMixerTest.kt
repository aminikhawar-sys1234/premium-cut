package com.example.engine.export

import com.example.domain.model.AudioEffectsSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The mix itself, on synthetic sources - the whole point of moving it out of [AudioExportProcessor]
 * is that this can run on a plain JVM.
 *
 * What is asserted is what the previous, whole-buffer mixer promised and what a chunked mixer can
 * silently break:
 *
 * * a forward, unity-speed track mixes **the same samples** as before (the decoder is faked the way
 *   the real one behaves: a window's buffer starts at the source sample the window asked for);
 * * every timeline frame is written exactly once - no gap and no doubled sample at a chunk boundary;
 * * "no sample behind this frame" is counted, digital silence in the source is not;
 * * a window that cannot be decoded is counted per track (the caller decides whether that is fatal);
 * * cancel returns immediately and leaves the output untouched;
 * * progress is reported once per chunk, which is what keeps the panel moving during a long mix.
 */
class TimelineAudioMixerTest {

  private val targetRate = 48_000
  private val channels = 2

  private fun track(
    id: String = "t0",
    timelineStartMs: Long = 0L,
    durationMs: Long = 60_000L,
    sourceStartMs: Long = 0L,
    sourceEndMs: Long = 0L,
    speed: Float = 1f,
    volume: Float = 1f,
    isMuted: Boolean = false,
    isReversed: Boolean = false,
    audioEffects: AudioEffectsSettings = AudioEffectsSettings()
  ) = AudioTrackDescriptor(
    uri = "content://media/$id.m4a",
    title = id,
    timelineStartMs = timelineStartMs,
    durationMs = durationMs,
    sourceStartMs = sourceStartMs,
    sourceEndMs = sourceEndMs,
    speed = speed,
    volume = volume,
    gainDb = 0f,
    fadeInMs = 0L,
    fadeOutMs = 0L,
    isMuted = isMuted,
    isReversed = isReversed,
    audioEffects = audioEffects
  )

  /** Deterministic source material: non-zero everywhere, so "silence" is never an accident. */
  private fun sampleAt(sourceSample: Int): Short {
    val wrapped = ((sourceSample % 4_000) + 4_000) % 4_000
    return (wrapped - 2_000).toShort()
  }

  /**
   * A decoder stand-in: returns the samples of `[startMs, endMs)` of the source and reports the
   * origin (the in-point relative index of its first sample) exactly the way
   * [AudioExportProcessor] does.
   */
  private inner class FakeDecoder(
    private val track: AudioTrackDescriptor,
    private val rate: Int = targetRate,
    private val channelsIn: Int = 2,
    private val failWindows: Set<Int> = emptySet(),
    private val emptyFromMs: Long = Long.MAX_VALUE
  ) {
    var requests = 0
        private set
    val requestedWindows = mutableListOf<Pair<Long, Long>>()

    fun decode(request: AudioMixWindowRequest): MixPcmWindow? {
      requests++
      requestedWindows += request.startMs to request.endMs
      if (request.chunk.index in failWindows && !request.fullWindow) return null
      val origin = if (request.fullWindow) 0 else AudioMixPlan.windowOriginSample(track, request.startMs, rate)
      if (request.startMs >= emptyFromMs) {
        return MixPcmWindow(ShortArray(0), rate, channelsIn, request.startMs, origin)
      }
      val fromMs = maxOf(request.startMs, 0L)
      val spanMs = (request.endMs - fromMs).coerceAtLeast(1L)
      val frames = ((spanMs.toDouble() / 1000.0) * rate).toInt().coerceAtLeast(1)
      val samples = ShortArray(frames * channelsIn)
      for (frame in 0 until frames) {
        val value = sampleAt(origin + frame)
        for (ch in 0 until channelsIn) samples[frame * channelsIn + ch] = value
      }
      return MixPcmWindow(samples, rate, channelsIn, request.startMs, origin)
    }
  }

  private fun mix(
    tracks: List<AudioTrackDescriptor>,
    totalFrames: Int,
    chunkFrames: Int,
    load: (AudioMixWindowRequest) -> MixPcmWindow?,
    gainAt: (Int, Int, Int, Int) -> Float = { _, _, _, _ -> 1f },
    isCancelled: () -> Boolean = { false },
    progress: MutableList<Int>? = null
  ): Pair<ShortArray, AudioMixResult> {
    val out = ShortArray(totalFrames * channels)
    val result = TimelineAudioMixer.mix(
      out = out,
      totalFrames = totalFrames,
      channels = channels,
      targetSampleRate = targetRate,
      tracks = tracks,
      chunks = AudioMixPlan.chunks(totalFrames, chunkFrames),
      decodeWindow = load,
      gainAt = gainAt,
      isCancelled = isCancelled,
      onChunkMixed = { done, total -> progress?.add(if (done == total) -done else done) }
    )
    return out to result
  }

  // ------------------------------------------------------------------ parity with the old mixer

  @Test
  fun aForwardUnitySpeedTrackMixesExactlyLikeTheWholeBufferMixer() {
    val t = track(timelineStartMs = 1_000L, durationMs = 4_000L)
    val totalFrames = 240_000 // 5 s: the clip starts at 1 s, so the tail is silence
    val decoder = FakeDecoder(t)
    val (chunked, result) = mix(listOf(t), totalFrames, chunkFrames = 48_000, load = decoder::decode)

    // Reference: the same arithmetic the whole-buffer mixer used - one pass over the timeline,
    // reading `sampleIndex(relativeFrame)` of a buffer whose index 0 is the clip's in-point, and
    // writing it through the same transfer curve (`softClipSample` in the old mixer). The curve
    // scales by 32767/32768, so comparing against *raw* source samples would show an off-by-one on
    // every non-zero sample and prove nothing.
    val trackStart = AudioMixPlan.trackStartFrame(t, targetRate)
    val durationFrames = AudioMixPlan.trackFrameCount(t, targetRate)
    val reference = ShortArray(totalFrames * channels)
    for (frame in 0 until totalFrames) {
      val relativeFrame = frame - trackStart
      if (relativeFrame < 0 || relativeFrame >= durationFrames) continue
      val sample = AudioMixMath.softClip(
        sampleAt(AudioMixPlan.sampleIndex(relativeFrame, targetRate, targetRate)).toFloat()
      )
      for (ch in 0 until channels) reference[frame * channels + ch] = sample
    }

    assertTrue("the mix must have completed", !result.cancelled && result.framesMixed > 0)
    assertEquals("every timeline frame must be mixed exactly once", totalFrames, result.framesMixed)
    var identical = 0
    var maxDiff = 0
    for (i in chunked.indices) {
      val diff = abs(chunked[i] - reference[i])
      if (diff == 0) identical++ else maxDiff = maxOf(maxDiff, diff)
    }
    assertTrue("no sample may be off by more than 1 LSB, max was $maxDiff", maxDiff <= 1)
    // Bit identity, not "close enough": the chunked mixer must produce the same integers the
    // whole-buffer mixer produced, byte for byte, or the exported audio differs from the preview.
    assertEquals(
      "the chunked mix must be bit identical to the whole-buffer mix",
      chunked.size,
      identical
    )
  }

  @Test
  fun everyFrameOfALongTimelineIsWrittenExactlyOnce() {
    // 120 chunks. A boundary mistake shows up as a silent (or doubled) frame at 5 s intervals.
    val t = track(durationMs = 600_000L)
    val totalFrames = 480_000
    val decoder = FakeDecoder(t)
    val (out, result) = mix(listOf(t), totalFrames, chunkFrames = 4_000, load = decoder::decode)
    assertEquals(totalFrames, result.framesMixed)
    assertEquals(120, result.chunksMixed)
    val trackStart = AudioMixPlan.trackStartFrame(t, targetRate)
    for (frame in 0 until totalFrames) {
      val expected = AudioMixMath.softClip(sampleAt(frame - trackStart).toFloat())
      val value = out[frame * channels]
      // Comparing against the expected *sample* (not against non-zero) is what catches a gap: the
      // transfer curve legitimately maps -1 to 0, so "value != 0" was never a gap detector.
      assertEquals("frame $frame was never written (chunk boundary gap)", expected, value)
      assertEquals("frame $frame has inconsistent channels", value, out[frame * channels + 1])
    }
  }

  @Test
  fun aSourceRateThatDiffersFromTheTargetStillLandsOnTheSameFrames() {
    val t = track(durationMs = 4_000L)
    val decoder = FakeDecoder(t, rate = 44_100)
    val (out, result) = mix(listOf(t), 240_000, chunkFrames = 48_000, load = decoder::decode)
    assertEquals(240_000, result.framesMixed)
    assertEquals(0L, result.silentFrames(0))
    for (frame in 0 until 190_000) {
      val expected = sampleAt(AudioMixPlan.sampleIndex(frame, 44_100, targetRate))
      assertTrue(
        "frame $frame should carry source sample ${AudioMixPlan.sampleIndex(frame, 44_100, targetRate)}",
        abs(out[frame * channels] - expected) <= 1
      )
    }
  }

  // ------------------------------------------------------------------ silence vs. failure

  @Test
  fun framesPastTheEndOfAShorterSourceAreSilenceAndAreCountedAsSuch() {
    // A 1 s source inside a 3 s clip: the last two seconds are silence (the source simply ends).
    val t = track(durationMs = 3_000L)
    val decoder = FakeDecoder(t, emptyFromMs = 1_000L)
    val (out, result) = mix(listOf(t), 144_000, chunkFrames = 24_000, load = decoder::decode)
    assertEquals(144_000, result.framesMixed)
    assertTrue("the silence must be reported", result.silentFrames(0) > 0L)
    assertEquals(0, result.decodeFailures(0))
    // ...and the frames that *do* have samples behind them must not be silent.
    assertTrue(out[0] != 0.toShort())
    assertEquals(0.toShort(), out[143_999 * channels])
  }

  @Test
  fun aWindowThatCannotBeDecodedIsCountedPerTrackAndNotFailed() {
    // The mixer cannot know whether a failed window is a codec hiccup or a missing file: it counts
    // it per track and leaves those frames silent, and the caller decides (the export fails).
    val t = track(durationMs = 10_000L)
    val decoder = FakeDecoder(t, failWindows = setOf(1))
    val (_, result) = mix(listOf(t), 480_000, chunkFrames = 48_000, load = decoder::decode)
    assertEquals(1, result.decodeFailures(0))
    assertTrue(result.silentFrames(0) > 0L)
    assertEquals(480_000, result.framesMixed)
  }

  @Test
  fun aMutedTrackContributesNothingAtAll() {
    val muted = track(id = "muted", durationMs = 2_000L, isMuted = true)
    val decoder = FakeDecoder(muted)
    val (out, _) = mix(listOf(muted), 96_000, chunkFrames = 48_000, load = decoder::decode)
    assertTrue("a muted track must not be decoded", decoder.requests == 0)
    assertTrue("a muted track must leave the mix empty", out.all { it == 0.toShort() })
  }

  @Test
  fun gainIsAppliedPerSampleAndZeroGainWritesNothing() {
    val t = track(durationMs = 1_000L)
    val decoder = FakeDecoder(t)
    val (quiet, _) = mix(listOf(t), 48_000, chunkFrames = 12_000, load = decoder::decode, gainAt = { _, _, _, _ -> 0f })
    assertTrue("a zero gain must write silence", quiet.all { it == 0.toShort() })

    val decoder2 = FakeDecoder(t)
    val (half, _) = mix(listOf(t), 48_000, chunkFrames = 12_000, load = decoder2::decode, gainAt = { _, _, _, _ -> 0.5f })
    val expected = (sampleAt(0) * 0.5f).toInt().toShort()
    assertTrue(abs(half[0] - expected) <= 1)
  }

  @Test
  fun twoTracksAreSummedNotReplaced() {
    val a = track(id = "a", durationMs = 1_000L)
    val b = track(id = "b", durationMs = 1_000L)
    val decoderA = FakeDecoder(a)
    val decoderB = FakeDecoder(b)
    val (out, result) = mix(
      listOf(a, b),
      48_000,
      chunkFrames = 12_000,
      load = { request -> if (request.trackIndex == 0) decoderA.decode(request) else decoderB.decode(request) }
    )
    assertEquals(48_000, result.framesMixed)
    // Both tracks carry the same material, so the sum is the soft-clipped double.
    val single = sampleAt(0).toInt()
    val summed = out[0].toInt()
    assertTrue("two tracks must add up, got $summed for a single track's $single", abs(summed - (single * 2)) <= 1)
  }

  // ------------------------------------------------------------------ cancellation and progress

  @Test
  fun cancellingStopsTheMixWithoutTouchingTheOutput() {
    val t = track(durationMs = 60_000L)
    val decoder = FakeDecoder(t)
    var mixed = 0
    val out = ShortArray(240_000 * channels)
    val result = TimelineAudioMixer.mix(
      out = out,
      totalFrames = 240_000,
      channels = channels,
      targetSampleRate = targetRate,
      tracks = listOf(t),
      chunks = AudioMixPlan.chunks(240_000, 48_000),
      decodeWindow = { request ->
        mixed++
        decoder.decode(request)
      },
      gainAt = { _, _, _, _ -> 1f },
      isCancelled = { mixed >= 2 },
      onChunkMixed = { _, _ -> }
    )
    assertTrue("a cancelled mix must say so", result.cancelled)
    assertEquals("the mix must stop at the cancel, not finish", 2, result.chunksMixed)
    for (index in result.framesMixed * channels until out.size) {
      assertEquals("frames past the cancel must stay untouched", 0.toShort(), out[index])
    }
  }

  @Test
  fun progressIsReportedOncePerChunkInOrder() {
    val t = track(durationMs = 600_000L)
    val decoder = FakeDecoder(t)
    val progress = mutableListOf<Int>()
    val (_, result) = mix(listOf(t), 480_000, chunkFrames = 40_000, load = decoder::decode, progress = progress)
    assertEquals(12, result.chunksMixed)
    assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, -12), progress)
  }

  @Test
  fun anEmptyTimelineOrNoTracksIsAValidMixOfNothing() {
    val (out, result) = mix(
      tracks = listOf(track(durationMs = 1_000L)),
      totalFrames = 0,
      chunkFrames = 48_000,
      load = { null }
    )
    assertTrue(out.isEmpty())
    assertEquals(0, result.framesMixed)
    assertFalse(result.cancelled)
  }
}
