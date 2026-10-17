package com.example.engine.export

/**
 * One decoded piece of a track's audio.
 *
 * [samples] is interleaved 16-bit PCM whose index 0 is **exactly** the source sample
 * [originSample] of the clip's in-point window: the decoder drops everything before [windowStartMs]
 * (see `AudioExportProcessor.decodePcmFromMedia`), and the caller states which sample that is.
 * Carrying the origin as a sample index instead of deriving it back from milliseconds is what makes
 * a chunked decode *exact*: the mixer never has to guess where a window starts.
 */
data class MixPcmWindow(
  val samples: ShortArray,
  val sampleRate: Int,
  val channels: Int,
  val windowStartMs: Long,
  /** Source sample index (0 = clip in-point) of `samples[0]`. */
  val originSample: Int = 0
) {
  val frames: Int get() = samples.size / channels.coerceAtLeast(1)

  override fun equals(other: Any?): Boolean =
    other is MixPcmWindow && samples.contentEquals(other.samples) && sampleRate == other.sampleRate &&
      channels == other.channels && windowStartMs == other.windowStartMs && originSample == other.originSample

  override fun hashCode(): Int =
    (((samples.contentHashCode() * 31 + sampleRate) * 31 + channels) * 31 + windowStartMs.hashCode()) * 31 +
      originSample
}

/** A window of one track the mixer asks the decoder for. */
data class AudioMixWindowRequest(
  val trackIndex: Int,
  val chunk: AudioMixChunk,
  val startMs: Long,
  val endMs: Long,
  /** Source sample index (0 = clip in-point) the returned window's first sample must be. */
  val originSample: Int,
  /**
   * True for a single whole-window decode (a reversed / speed-stretched / effect-chained track, see
   * [AudioMixPlan.isStreamable]) instead of a per-chunk window.
   */
  val fullWindow: Boolean = false
)

/** What one [TimelineAudioMixer.mix] pass did. */
data class AudioMixResult(
  /** Timeline frames written into the output buffer. */
  val framesMixed: Int,
  /** True when [TimelineAudioMixer.mix] returned early because the caller cancelled. */
  val cancelled: Boolean,
  /** Per track: timeline frames that had no decoded sample behind them (past the end of a source). */
  val silentFramesPerTrack: Map<Int, Long>,
  /** Per track: windows the decoder could not deliver at all. */
  val decodeFailuresPerTrack: Map<Int, Int>,
  /** Number of chunks that completed; equals the chunk count on a full pass. */
  val chunksMixed: Int
) {
  /** Windows the decoder refused for [trackIndex]; a first-window failure means the track is mute. */
  fun decodeFailures(trackIndex: Int): Int = decodeFailuresPerTrack[trackIndex] ?: 0

  /** Timeline frames of [trackIndex] that had no sample behind them. */
  fun silentFrames(trackIndex: Int): Long = silentFramesPerTrack[trackIndex] ?: 0L
}

/**
 * The timeline mixer: every audio track of the project mixed into one interleaved 16-bit buffer, in
 * bounded chunks, with progress and cancellation at chunk granularity.
 *
 * Why this is split out of [AudioExportProcessor] (which owns the Android decoding): the arithmetic
 * is what has to be *proved* - that a per-chunk pass produces the samples the whole-buffer mixer
 * produced, that the chunks tile the timeline exactly once, that gain / fades / ducking / volume
 * keyframes are applied per frame exactly as before, and that a cancelled or undecodable window is
 * counted and reported instead of being papered over. In this form all of it is unit-testable on a
 * plain JVM with synthetic PCM ([TimelineAudioMixerTest]); the Android side is reduced to "decode
 * this millisecond window of this track".
 *
 * Two consequences the previous whole-timeline implementation did not have:
 *
 * * peak memory is the output buffer plus one decoded window per track - the previous version held
 *   `FloatArray(totalFrames * channels)` *and* `ShortArray(totalFrames * channels)` live at the same
 *   time, plus every decoded track in the PCM cache (~345 MB at ten minutes before the encoder was
 *   even opened), and
 * * progress and cancellation happen per chunk, so the export panel advances through the mix
 *   instead of sitting on the 5 % floor.
 *
 * The float accumulator is filled in track order and soft-clipped exactly once per sample, so the
 * values are the same sums the old mixer produced ([AudioMixMath.softClip] is the same transfer
 * curve applied to the same sum).
 */
object TimelineAudioMixer {

  /**
   * Mixes [tracks] into [out] (interleaved 16-bit, [channels], [targetSampleRate]).
   *
   * @param out destination buffer of `totalFrames * channels` samples; written, never read.
   * @param decodeWindow decodes one [AudioMixWindowRequest]. Returning null means "this window could
   *   not be decoded": those frames stay silent and are counted in
   *   [AudioMixResult.silentFramesPerTrack] / [AudioMixResult.decodeFailuresPerTrack], so the caller
   *   decides whether that is fatal (the production caller fails the export when a whole track would
   *   be silent or the first window of a track cannot be decoded).
   * @param gainAt per-sample linear gain of one track, i.e. [exportMixLinearGain].
   * @param onChunkMixed called after every completed chunk with `(done, total)` - the mix phase's
   *   progress signal, and the watchdog's proof that the mix is still moving.
   */
  fun mix(
    out: ShortArray,
    totalFrames: Int,
    channels: Int,
    targetSampleRate: Int,
    tracks: List<AudioTrackDescriptor>,
    chunks: List<AudioMixChunk>,
    decodeWindow: (AudioMixWindowRequest) -> MixPcmWindow?,
    gainAt: (trackIndex: Int, relativeFrame: Int, durationFrames: Int, sampleRate: Int) -> Float,
    isCancelled: () -> Boolean,
    onChunkMixed: (done: Int, total: Int) -> Unit = { _, _ -> }
  ): AudioMixResult {
    if (totalFrames <= 0 || tracks.isEmpty() || chunks.isEmpty()) {
      return AudioMixResult(0, cancelled = false, emptyMap(), emptyMap(), 0)
    }
    val safeChannels = channels.coerceAtLeast(1)
    val maxChunkFrames = chunks.maxOf { it.frameCount }.coerceAtLeast(1)
    val scratch = FloatArray(maxChunkFrames * safeChannels)

    /** Tracks that cannot be streamed: decoded once (whole source window) and kept for the pass. */
    val wholeWindows = HashMap<Int, MixPcmWindow?>()
    /** Source sample rate of a streaming track, learnt from its first decoded window. */
    val sourceRates = HashMap<Int, Int>()
    val silentFrames = HashMap<Int, Long>()
    val decodeFailures = HashMap<Int, Int>()

    var chunksMixed = 0
    var framesMixed = 0

    for (chunk in chunks) {
      if (isCancelled()) {
        return AudioMixResult(framesMixed, true, silentFrames, decodeFailures, chunksMixed)
      }
      java.util.Arrays.fill(scratch, 0f)

      for (trackIndex in tracks.indices) {
        val track = tracks[trackIndex]
        if (track.isMuted) continue
        val frames = AudioMixPlan.trackFramesInChunk(track, chunk, targetSampleRate)
        if (frames.isEmpty()) continue

        val window: MixPcmWindow? = if (AudioMixPlan.isStreamable(track)) {
          val request = streamingWindowRequest(
            track = track,
            trackIndex = trackIndex,
            chunk = chunk,
            targetSampleRate = targetSampleRate,
            knownSourceRate = sourceRates[trackIndex] ?: 0
          )
          decodeWindow(request)?.also { decoded ->
            if (decoded.sampleRate > 0) sourceRates[trackIndex] = decoded.sampleRate
          }
        } else {
          if (!wholeWindows.containsKey(trackIndex)) {
            val full = audioSourceWindow(track)
            wholeWindows[trackIndex] = decodeWindow(
              AudioMixWindowRequest(
                trackIndex = trackIndex,
                chunk = chunk,
                startMs = full.first,
                endMs = full.second,
                originSample = 0,
                fullWindow = true
              )
            )
          }
          wholeWindows[trackIndex]
        }

        if (window == null) {
          decodeFailures[trackIndex] = (decodeFailures[trackIndex] ?: 0) + 1
          silentFrames[trackIndex] = (silentFrames[trackIndex] ?: 0L) + frames.count()
          continue
        }
        val missing = accumulate(
          scratch = scratch,
          chunk = chunk,
          channels = safeChannels,
          track = track,
          trackIndex = trackIndex,
          frames = frames,
          targetSampleRate = targetSampleRate,
          window = window,
          gainAt = gainAt
        )
        if (missing > 0L) silentFrames[trackIndex] = (silentFrames[trackIndex] ?: 0L) + missing
      }

      val outStart = chunk.startFrame * safeChannels
      val usableFrames = minOf(chunk.frameCount, totalFrames - chunk.startFrame).coerceAtLeast(0)
      for (i in 0 until usableFrames * safeChannels) {
        val index = outStart + i
        if (index in out.indices) out[index] = AudioMixMath.softClip(scratch[i])
      }
      framesMixed += usableFrames
      chunksMixed++
      onChunkMixed(chunksMixed, chunks.size)
    }

    return AudioMixResult(framesMixed, false, silentFrames, decodeFailures, chunksMixed)
  }

  /**
   * The source window a streaming track needs for [chunk].
   *
   * While the source's sample rate is unknown (the very first window of the track), the window is
   * described in *time* - the chunk's own span plus [AudioMixPlan.SOURCE_TAIL_MARGIN_MS] - which is
   * enough to learn the rate from the decoded data. Every later window asks for the sample-exact
   * range the mixer will read, computed by [AudioMixPlan.sourceWindowMs].
   */
  private fun streamingWindowRequest(
    track: AudioTrackDescriptor,
    trackIndex: Int,
    chunk: AudioMixChunk,
    targetSampleRate: Int,
    knownSourceRate: Int
  ): AudioMixWindowRequest {
    if (knownSourceRate <= 0) {
      val startMs = track.sourceStartMs.coerceAtLeast(0L)
      val spanMs = (chunk.frameCount.toLong() * 1000L) / targetSampleRate.coerceAtLeast(1)
      return AudioMixWindowRequest(
        trackIndex = trackIndex,
        chunk = chunk,
        startMs = startMs,
        endMs = startMs + spanMs + AudioMixPlan.SOURCE_TAIL_MARGIN_MS,
        originSample = 0
      )
    }
    val trackStart = AudioMixPlan.trackStartFrame(track, targetSampleRate)
    val firstRelFrame = (chunk.startFrame - trackStart).coerceAtLeast(0)
    val originSample = AudioMixPlan.sampleIndex(firstRelFrame, knownSourceRate, targetSampleRate)
    val planned = AudioMixPlan.sourceWindowMs(track, chunk, targetSampleRate, knownSourceRate)
    return AudioMixWindowRequest(
      trackIndex = trackIndex,
      chunk = chunk,
      startMs = planned.first,
      endMs = planned.second,
      originSample = originSample
    )
  }

  /**
   * Accumulates one track's window into the chunk scratch buffer, frame by frame.
   *
   * @return how many frames of [frames] had no sample behind them (past the end of the decoded
   *   window), so the caller reports that instead of calling it audio.
   */
  private fun accumulate(
    scratch: FloatArray,
    chunk: AudioMixChunk,
    channels: Int,
    track: AudioTrackDescriptor,
    trackIndex: Int,
    frames: IntRange,
    targetSampleRate: Int,
    window: MixPcmWindow,
    gainAt: (Int, Int, Int, Int) -> Float
  ): Long {
    val src = window.samples
    val srcChannels = window.channels.coerceAtLeast(1)
    val srcRate = window.sampleRate.coerceAtLeast(1)
    val windowOrigin = window.originSample
    val trackStart = AudioMixPlan.trackStartFrame(track, targetSampleRate)
    val durationFrames = AudioMixPlan.trackFrameCount(track, targetSampleRate)
    var silentFrames = 0L

    for (frame in frames) {
      val relativeFrame = frame - trackStart
      val scratchFrame = frame - chunk.startFrame
      val srcIndex = AudioMixPlan.sampleIndex(relativeFrame, srcRate, targetSampleRate) - windowOrigin
      // "No sample behind this frame": the window ended before it (a trimmed source, a source
      // shorter than the clip, a window that could not be decoded). Real digital silence in the
      // source is *not* counted - that is audio, and reporting it would be wrong.
      val covered = if (srcChannels == 1) srcIndex in 0 until src.size
      else srcIndex >= 0 && srcIndex * srcChannels < src.size
      if (!covered) {
        silentFrames++
        continue
      }
      val gain = gainAt(trackIndex, relativeFrame, durationFrames, targetSampleRate)
      if (gain == 0f) continue
      for (ch in 0 until channels) {
        // A source with MORE channels than the timeline (5.1/6ch camera audio into stereo) used to
        // be mapped with `ch % srcChannels`, which silently dropped the centre channel (dialogue)
        // and the side channels. A weighted downmix (front + 0.707 centre + 0.5 side) keeps them.
        val sample: Float = when {
          srcChannels == 1 -> src[srcIndex].toFloat()
          srcChannels <= channels -> {
            val idx = srcIndex * srcChannels + ch
            if (idx < src.size) src[idx].toFloat() else 0f
          }
          else -> {
            var v = 0f
            val front = srcIndex * srcChannels + ch
            if (front < src.size) v += src[front]
            if (srcChannels >= 3) {
              val center = srcIndex * srcChannels + 2
              if (center < src.size) v += src[center] * 0.707f
            }
            if (srcChannels >= 6) {
              val side = srcIndex * srcChannels + (4 + ch) // 4 = back-left, 5 = back-right
              if (side < src.size) v += src[side] * 0.5f
            }
            v
          }
        }
        if (sample == 0f) continue
        val outIdx = scratchFrame * channels + ch
        if (outIdx in scratch.indices) scratch[outIdx] += sample * gain
      }
    }
    return silentFrames
  }
}
