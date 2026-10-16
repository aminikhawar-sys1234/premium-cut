package com.example.engine.export

/**
 * How a long timeline's audio mix is broken into bounded pieces.
 *
 * ## Why this exists
 *
 * The mixer used to allocate **the whole timeline twice**: a `FloatArray(totalFrames * channels)`
 * accumulator plus a `ShortArray` of the same size, while every decoded track (also a `ShortArray`
 * of its full source window) stayed alive in the PCM cache. For the reported project - a ten minute
 * timeline at 48 kHz stereo - that is ~230 MB + ~115 MB of mix/output buffers *plus* ~115 MB for the
 * main clip's own audio: several hundred MB of live allocations before the first frame is rendered,
 * on a phone. That is the phase the export panel sat in at "5 % / encoded 0 frames": the PCM mix
 * runs *before* the render loop, its length scales with the project length, and (before this change)
 * it reported nothing at all while it ran.
 *
 * The mix is now done in chunks of a few seconds: a bounded float scratch buffer (a few MB at most),
 * one decoded source window per track per chunk, and 16-bit samples written straight into the output
 * buffer. Peak audio memory is the mixed PCM the encoder is fed from - nothing else scales with the
 * timeline any more.
 *
 * The chunking is *pure arithmetic*, so the property that matters - "the chunks cover every timeline
 * frame exactly once, in order, and a streaming track's source window is exactly the material that
 * chunk needs" - is unit tested ([AudioMixPlanTest]).
 */
data class AudioMixChunk(
  val index: Int,
  /** First timeline frame (inclusive), in frames of the target sample rate. */
  val startFrame: Int,
  /** Frames in this chunk; the last chunk is the remainder. */
  val frameCount: Int
) {
  val endFrame: Int get() = startFrame + frameCount
}

object AudioMixPlan {

  /**
   * Length of one mix chunk. Long enough that per-chunk decoder opens stay rare (a ten minute
   * timeline is 120 chunks, i.e. 120 decoder seek+start cycles per track), short enough that the
   * scratch buffer stays tiny (~2 MB at 48 kHz stereo) and that progress/сancel react quickly.
   */
  const val DEFAULT_CHUNK_MS = 5_000L

  /** Never chunk smaller than this, so a silly sample rate cannot create thousands of chunks. */
  const val MIN_CHUNK_FRAMES = 4_800

  /** Extra source material decoded past a chunk, absorbing codec priming and frame rounding. */
  const val SOURCE_TAIL_MARGIN_MS = 250L

  /** Frames per chunk for [sampleRate]; always ≥ [MIN_CHUNK_FRAMES]. */
  fun chunkFrames(sampleRate: Int, chunkMs: Long = DEFAULT_CHUNK_MS): Int {
    val rate = sampleRate.coerceAtLeast(1).toLong()
    val ms = chunkMs.coerceAtLeast(1L)
    return ((rate * ms) / 1000L).toInt().coerceAtLeast(MIN_CHUNK_FRAMES)
  }

  fun chunkCount(totalFrames: Int, chunkFrames: Int): Int {
    val size = chunkFrames.coerceAtLeast(1)
    if (totalFrames <= 0) return 0
    return (totalFrames + size - 1) / size
  }

  fun chunk(index: Int, totalFrames: Int, chunkFrames: Int): AudioMixChunk {
    val size = chunkFrames.coerceAtLeast(1)
    val start = (index.coerceAtLeast(0) * size).coerceAtMost(totalFrames.coerceAtLeast(0))
    val remaining = (totalFrames - start).coerceAtLeast(0)
    return AudioMixChunk(index = index, startFrame = start, frameCount = minOf(size, remaining))
  }

  fun chunks(totalFrames: Int, chunkFrames: Int): List<AudioMixChunk> =
    (0 until chunkCount(totalFrames, chunkFrames)).map { chunk(it, totalFrames, chunkFrames) }

  /** First timeline frame of a track (frames of [sampleRate]); the old mixer's own rounding. */
  fun trackStartFrame(track: AudioTrackDescriptor, sampleRate: Int): Int =
    ((track.timelineStartMs.toDouble() / 1000.0) * sampleRate).toInt()

  /** Length of a track on the timeline, in frames of [sampleRate]; the old mixer's own rounding. */
  fun trackFrameCount(track: AudioTrackDescriptor, sampleRate: Int): Int =
    ((track.durationMs.toDouble() / 1000.0) * sampleRate).toInt()

  /** Frames of [chunk] that fall inside [track]; empty when the track is silent there. */
  fun trackFramesInChunk(
    track: AudioTrackDescriptor,
    chunk: AudioMixChunk,
    sampleRate: Int
  ): IntRange {
    val start = trackStartFrame(track, sampleRate)
    val end = start + trackFrameCount(track, sampleRate)
    val from = maxOf(chunk.startFrame, start)
    val to = minOf(chunk.endFrame, end) - 1
    return from..to
  }

  /**
   * True when this track's samples can be decoded window-by-window **without changing the mixed
   * result**. A track is streamable when nothing is applied to it that depends on the whole buffer:
   * no reversal (a chunk-wise reverse is a different signal), no pitch-safe speed stretch (WSOLA is
   * stateful across the buffer) and no audio-effects chain (EQ/normalisation stages carry state and
   * `normalizeVolume` is a whole-buffer measurement). Those tracks keep the previous single-window
   * decode, which the mix maths still bounds by the trim window.
   */
  fun isStreamable(track: AudioTrackDescriptor): Boolean =
    !track.isReversed &&
      com.example.engine.audio.PitchSafeStretch.isUnity(track.speed) &&
      !track.audioEffects.hasActiveEffects()

  /**
   * Source window (milliseconds, `[start, end)`) a streaming decode must cover for [chunk].
   *
   * The mixer reads a forward, unity-speed track as `srcIndex = floor(relFrame * srcRate / targetRate)`
   * frames after the clip's in-point, so the window must start at **or before** the sample that maps
   * to the chunk's first frame (`firstSample`) and end past the chunk's last frame plus
   * [SOURCE_TAIL_MARGIN_MS]. The start is rounded **down** to a millisecond for exactly that reason:
   * the decoder drops everything before the window, and [windowOriginSample] reports the window's
   * first sample as its origin, so an origin *greater* than `firstSample` would push the first frames
   * of the chunk out of the buffer (they would read as silence and the mix would lose up to a
   * millisecond of audio at every five-second boundary).
   *
   * The residual is at most one sample: the decoder's first retained sample can be one source sample
   * later than the millisecond the window nominally starts at.
   *
   * @param sourceSampleRate real sample rate of the source file (learned from the first decode).
   */
  fun sourceWindowMs(
    track: AudioTrackDescriptor,
    chunk: AudioMixChunk,
    targetSampleRate: Int,
    sourceSampleRate: Int
  ): Pair<Long, Long> {
    val rate = sourceSampleRate.coerceAtLeast(1)
    val trackStart = trackStartFrame(track, targetSampleRate)
    val firstRelFrame = (chunk.startFrame - trackStart).coerceAtLeast(0)
    val lastRelFrame = (chunk.endFrame - 1 - trackStart).coerceAtLeast(firstRelFrame)
    val firstSample = sampleIndex(firstRelFrame, rate, targetSampleRate)
    val lastSample = sampleIndex(lastRelFrame, rate, targetSampleRate)
    // Floor, never ceil: see the window-start rule above. `firstSample * 1000` is exact in a Long for
    // any real source length (a ten minute 192 kHz file is ~2.9e11).
    val startMs = track.sourceStartMs.coerceAtLeast(0L) + (firstSample.toLong() * 1000L) / rate.toLong()
    val endMs = track.sourceStartMs.coerceAtLeast(0L) +
      (lastSample.toLong() + 1L) * 1000L / rate.toLong() + SOURCE_TAIL_MARGIN_MS
    return startMs to maxOf(endMs, startMs + 1L)
  }

  /**
   * Source sample index (of the decoded window that starts at the clip's in-point) that chunk frame
   * [relFrame] reads. Identical to the formula the whole-buffer mixer always used, so the streaming
   * and non-streaming paths cannot drift apart.
   */
  fun sampleIndex(relFrame: Int, sourceSampleRate: Int, targetSampleRate: Int): Int {
    // A caller with no target rate (or a bogus source rate) cannot be mapped, and dividing by the
    // `coerceAtLeast(1)` fallback would answer with an index ~sampleRate times too large - which the
    // mixer's bounds check would then read as silence. 0 is the only honest answer.
    if (targetSampleRate <= 0 || sourceSampleRate <= 0) return 0
    return ((relFrame.coerceAtLeast(0).toDouble() * sourceSampleRate) / targetSampleRate).toInt()
  }

  /** Sample index a source window starting at [windowStartMs] begins with (its own index 0). */
  fun windowOriginSample(
    track: AudioTrackDescriptor,
    windowStartMs: Long,
    sourceSampleRate: Int
  ): Int {
    val rate = sourceSampleRate.coerceAtLeast(1)
    val fromInPointMs = (windowStartMs - track.sourceStartMs.coerceAtLeast(0L)).coerceAtLeast(0L)
    return ((fromInPointMs * rate) / 1000L).toInt()
  }

}
