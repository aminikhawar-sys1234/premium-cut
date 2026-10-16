package com.example.engine.export

import com.example.domain.model.AudioEffectsSettings
import kotlin.math.pow

/**
 * Pure audio-mixing maths of the export: PCM containers, gain envelopes, the mixer's transfer curve
 * and the source window a track actually uses.
 *
 * It lives in its own file - with no `android.*` import anywhere - so the numbers that decide what
 * the exported audio sounds like can be compiled and asserted on a plain JVM
 * ([AudioExportWindowTest], [TimelineAudioMixerTest], [AudioMixPlanTest], run by
 * `toolchain/verify/run_logic_tests.sh` as well as by `./gradlew test`). The Android side
 * ([AudioExportProcessor]) keeps only decoding, encoding and muxing.
 */

/** Decoded PCM of one source window: interleaved 16-bit samples plus their format. */
data class DecodedPcm(
  val samples: ShortArray,
  val sampleRate: Int,
  val channels: Int
)

/** Audio format the export muxes. */
data class TimelineAudioFormat(
  val sampleRate: Int = 48000,
  val channelCount: Int = 2,
  val bitrate: Int = 192000
)

/**
 * One audio source of the timeline, flattened out of `VideoClip` / `AudioClip` into exactly what the
 * mixer needs (and nothing Android).
 */
data class AudioTrackDescriptor(
  val uri: String,
  val title: String,
  val timelineStartMs: Long,
  val durationMs: Long,
  val sourceStartMs: Long,
  val sourceEndMs: Long,
  val speed: Float,
  val volume: Float,
  val gainDb: Float,
  val fadeInMs: Long,
  val fadeOutMs: Long,
  val isMuted: Boolean,
  val isReversed: Boolean = false,
  val keyframes: List<com.example.domain.model.ClipKeyframe> = emptyList(),
  val audioEffects: AudioEffectsSettings = AudioEffectsSettings(),
  /** Auto-ducking curve of this clip's lane, evaluated in timeline time; null = not ducked. */
  val duckEnvelope: com.example.engine.audio.AutoDucking.DuckEnvelope? = null
)

/** Built-in / catalog URIs that have no real media file. Never synthesized into fake PCM. */
fun isPlaceholderCatalogUri(uri: String): Boolean {
  if (uri.isBlank()) return true
  return uri.startsWith("internal://") ||
    uri.startsWith("built_in_sfx_") ||
    uri.startsWith("demo://")
}

/** True when [uri] points at real playable media rather than a catalog placeholder or bare id. */
fun isExportableAudioUri(uri: String): Boolean {
  if (isPlaceholderCatalogUri(uri)) return false
  return uri.contains("://") || uri.startsWith("/")
}

/**
 * Source window a track actually uses, in milliseconds: `[start, end)` of the source file that the
 * mix needs. Pure and unit tested ([AudioExportWindowTest]); the mixer uses it to bound both the
 * decode and the memory of every track.
 *
 * `sourceMs = timelineRelMs * speed` (see `AudioClip.timelineToSourceMs`), so a 2x clip consumes
 * twice its timeline length of source, a 0.5x clip half of it.
 */
fun audioSourceWindow(track: AudioTrackDescriptor): Pair<Long, Long> {
  val speed = track.speed.coerceIn(0.25f, 4.0f)
  val neededMs = (track.durationMs.coerceAtLeast(1L) * speed).toLong()
  val hasSourceRange = track.sourceEndMs > track.sourceStartMs
  if (track.isReversed && hasSourceRange) {
    // Reversed playback starts at the source end and walks backwards (sourceMs =
    // sourceEndMs - rel * speed), so the window is anchored to the end. The margin sits
    // *before* the window because the decoded buffer is reversed afterwards.
    val endMs = track.sourceEndMs
    return maxOf(0L, endMs - neededMs - AUDIO_WINDOW_MARGIN_MS) to endMs
  }
  val startMs = track.sourceStartMs.coerceAtLeast(0L)
  // A small margin keeps codec priming / frame rounding inside the window.
  val endMs = minOf(
    if (hasSourceRange) track.sourceEndMs else Long.MAX_VALUE,
    startMs + neededMs + AUDIO_WINDOW_MARGIN_MS
  )
  return startMs to maxOf(endMs, startMs + 1L)
}

/** Extra source material decoded past the used window (codec priming / rounding). */
const val AUDIO_WINDOW_MARGIN_MS = 250L

/**
 * Per-sample mix gain for one track.
 *
 * Volume keyframes replace the static clip volume (they are the envelope the timeline draws).
 * Fade in/out is applied on top, linearly, matching the preview player. [AudioTrackDescriptor.gainDb]
 * is a separate trim. An empty keyframe list keeps the old `volume * fade` result.
 */
fun exportMixLinearGain(
  track: AudioTrackDescriptor,
  relativeFrame: Int,
  durationFrames: Int,
  sampleRate: Int
): Float {
  if (track.isMuted) return 0f
  val rate = sampleRate.coerceAtLeast(1)
  val relativeMs = (relativeFrame.coerceAtLeast(0).toLong() * 1000L) / rate
  val envelope = com.example.engine.KeyframeInterpolator.interpolateVolume(
    track.keyframes,
    relativeMs,
    track.volume
  )
  var fade = 1f
  val fadeInFrames = ((track.fadeInMs / 1000.0) * rate).toInt()
  val fadeOutFrames = ((track.fadeOutMs / 1000.0) * rate).toInt()
  if (fadeInFrames > 0 && relativeFrame < fadeInFrames) {
    fade *= relativeFrame.toFloat() / fadeInFrames
  }
  if (fadeOutFrames > 0 && (durationFrames - relativeFrame) < fadeOutFrames) {
    fade *= ((durationFrames - relativeFrame).toFloat() / fadeOutFrames).coerceIn(0f, 1f)
  }
  val duck = track.duckEnvelope?.gainAt(track.timelineStartMs + relativeMs) ?: 1f
  return envelope * 10f.pow(track.gainDb / 20f) * fade * duck
}

/** The mixer's transfer curve: soft clip above 0.75 full scale, hard clip at full scale. */
object AudioMixMath {
  fun softClip(sample: Float): Short {
    val norm = sample / 32768f
    val clipped = when {
      norm > 1.0f -> 1.0f
      norm < -1.0f -> -1.0f
      norm > 0.75f -> 0.75f + (norm - 0.75f) * 0.5f
      norm < -0.75f -> -0.75f + (norm + 0.75f) * 0.5f
      else -> norm
    }
    return (clipped * 32767f).toInt().toShort()
  }

  /** Timeline frames of [durationMs] at [sampleRate]; the mixer's own rounding. */
  fun framesFor(durationMs: Long, sampleRate: Int): Int =
    ((durationMs / 1000.0) * sampleRate).toInt()
}

/**
 * Grows a PCM byte stream without boxing every sample. The previous
 * `MutableList<Byte>.addAll(chunk.toList())` path allocated tens of millions of
 * boxed Bytes for a few minutes of audio and stalled export at 5% for minutes.
 */
internal object PcmByteSink {
  fun create(estimatedBytes: Int): java.io.ByteArrayOutputStream {
    val cap = estimatedBytes.coerceIn(8 * 1024, 256 * 1024 * 1024)
    return java.io.ByteArrayOutputStream(cap)
  }

  fun estimatedPcmBytes(durationUs: Long, sampleRate: Int, channels: Int): Int {
    if (durationUs <= 0L || sampleRate <= 0 || channels <= 0) return 1024 * 1024
    val bytes = (durationUs / 1_000_000.0) * sampleRate * channels * 2.0
    return bytes.toInt().coerceIn(64 * 1024, 256 * 1024 * 1024)
  }

  fun append(out: java.io.ByteArrayOutputStream, src: java.nio.ByteBuffer, scratch: ByteArray) {
    while (src.hasRemaining()) {
      val n = minOf(scratch.size, src.remaining())
      src.get(scratch, 0, n)
      out.write(scratch, 0, n)
    }
  }

  fun toLittleEndianShorts(out: java.io.ByteArrayOutputStream): ShortArray {
    val byteArr = out.toByteArray()
    if (byteArr.isEmpty()) return ShortArray(0)
    val shortBuf = java.nio.ByteBuffer.wrap(byteArr)
      .order(java.nio.ByteOrder.LITTLE_ENDIAN)
      .asShortBuffer()
    val shorts = ShortArray(shortBuf.remaining())
    shortBuf.get(shorts)
    return shorts
  }
}
