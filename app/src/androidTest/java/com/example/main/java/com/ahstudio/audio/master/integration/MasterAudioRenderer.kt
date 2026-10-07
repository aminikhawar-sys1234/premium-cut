package com.ahstudio.audio.master.integration

import android.content.Context
import com.ahstudio.audio.master.core.AudioBuffer
import com.ahstudio.audio.master.core.AudioFormat
import com.ahstudio.audio.master.core.AudioRenderContext
import com.ahstudio.audio.master.decoder.DecodedPcm
import com.ahstudio.audio.master.decoder.MediaCodecAudioDecoder
import com.ahstudio.audio.master.dsp.AudioDspChain
import com.ahstudio.audio.master.dsp.eq.BiquadType
import com.ahstudio.audio.master.dsp.eq.EqualizerBand
import com.ahstudio.audio.master.dsp.eq.ParametricEqualizer
import com.ahstudio.audio.master.dsp.noise.NoiseProfileAnalyzer
import com.ahstudio.audio.master.dsp.noise.NoiseReductionConfig
import com.ahstudio.audio.master.dsp.noise.SpectralNoiseReducer
import com.ahstudio.audio.master.dsp.transform.PitchShifter
import com.ahstudio.audio.master.dsp.transform.WsolaTimeStretcher
import com.ahstudio.audio.master.metering.LoudnessMeter
import com.ahstudio.audio.master.model.AudioDspChainSpec
import com.ahstudio.audio.master.model.AudioDspNodeSpec
import com.ahstudio.audio.master.recording.WavWriter
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/** User-facing settings for the "Master" audio panel. Pure data, UI-friendly. */
data class MasterAudioSettings(
  val eqEnabled: Boolean = false,
  /** 6 bands, same layout as [ParametricEqualizer.defaultBands]. */
  val eqBands: List<EqualizerBand> = ParametricEqualizer.defaultBands(),
  val compressorEnabled: Boolean = false,
  val compThresholdDb: Float = -18f,
  val compRatio: Float = 3f,
  val compAttackMs: Float = 10f,
  val compReleaseMs: Float = 120f,
  val compMakeupDb: Float = 0f,
  val deEsserEnabled: Boolean = false,
  val deEsserThresholdDb: Float = -30f,
  val deEsserReductionDb: Float = -12f,
  val noiseReductionEnabled: Boolean = false,
  val noiseStrength: Float = 0.5f,
  val noiseFloorDb: Float = -40f,
  val voicePreservation: Boolean = true,
  /** 1.0 = unchanged. >1 faster/shorter. Pitch is preserved (WSOLA). */
  val tempo: Float = 1f,
  val pitchSemitones: Float = 0f,
) {
  val hasAnyEffect: Boolean
    get() = eqEnabled || compressorEnabled || deEsserEnabled || noiseReductionEnabled ||
      abs(tempo - 1f) > 1e-3f || abs(pitchSemitones) > 0.01f
}

data class AudioLevels(
  val peakDb: Float,
  val rmsDb: Float,
  val lufs: Float,
  val clipped: Boolean,
)

data class MasterRenderResult(
  val file: File,
  val durationMs: Long,
  val before: AudioLevels,
  val after: AudioLevels,
)

/**
 * Offline bridge: app-level audio clip file -> master DSP chain -> WAV file.
 * Runs on the caller's thread; call from Dispatchers.Default / IO.
 */
class MasterAudioRenderer(private val context: Context) {

  private val blockFrames = 1024

  fun analyze(uri: String, sourceStartMs: Long = 0L, sourceEndMs: Long = -1L): AudioLevels {
    val pcm = decode(uri, sourceStartMs, sourceEndMs)
    return measure(pcm.data, pcm.channels, pcm.sampleRate)
  }

  fun render(
    uri: String,
    settings: MasterAudioSettings,
    outFile: File,
    sourceStartMs: Long = 0L,
    sourceEndMs: Long = -1L,
    onProgress: ((Float) -> Unit)? = null,
  ): MasterRenderResult {
    val src = decode(uri, sourceStartMs, sourceEndMs)
    val channels = src.channels.coerceIn(1, 8)
    val sr = src.sampleRate
    val before = measure(src.data, channels, sr)
    onProgress?.invoke(0.1f)

    var data = src.data
    // 1) Time / pitch (WSOLA). tempo>1 => shorter, so stretch factor is the inverse.
    if (abs(settings.tempo - 1f) > 1e-3f) {
      data = WsolaTimeStretcher.stretch(data, channels, 1f / settings.tempo.coerceIn(0.25f, 4f), sr)
    }
    if (abs(settings.pitchSemitones) > 0.01f) {
      data = PitchShifter.shift(data, channels, settings.pitchSemitones, sr)
    }
    onProgress?.invoke(0.35f)

    val total = data[0].size
    val format = AudioFormat(sr, channels)

    // 2) Spectral noise reduction: profile from the first 0.5s (assumed room tone).
    var nr: SpectralNoiseReducer? = null
    if (settings.noiseReductionEnabled && total > 4096) {
      val cfg = NoiseReductionConfig(
        strength = settings.noiseStrength.coerceIn(0f, 1f),
        floorDb = settings.noiseFloorDb,
        voicePreservation = settings.voicePreservation,
      )
      val profileEnd = (total.toDouble() / sr).coerceAtMost(0.5)
      val profile = NoiseProfileAnalyzer.analyze(data, channels, sr, cfg.frameSize, 0.0, profileEnd)
      nr = SpectralNoiseReducer(profile, cfg).also { it.prepare(format, blockFrames) }
    }
    val nrLatency = nr?.latencySamples ?: 0

    // 3) EQ / compressor / de-esser chain.
    val chain = buildChain(settings).let { spec ->
      if (spec.isEmpty) null else AudioDspChain(spec).also { it.prepare(format, blockFrames) }
    }

    val outFrames = total
    val padded = total + nrLatency
    val out = Array(channels) { FloatArray(outFrames) }
    val buf = AudioBuffer(channels, blockFrames)
    var pos = 0
    var written = 0
    var block = 0L
    while (pos < padded) {
      val n = minOf(blockFrames, padded - pos)
      for (c in 0 until channels) {
        val d = buf.data[c]
        for (i in 0 until n) {
          val s = pos + i
          d[i] = if (s < total) data[c][s] else 0f
        }
        for (i in n until blockFrames) d[i] = 0f
      }
      val ctx = AudioRenderContext(format, pos.toDouble() / sr, n, block, realtime = false)
      nr?.process(buf, ctx)
      chain?.process(buf, ctx)
      // Drop the first nrLatency output frames to realign with the input.
      for (i in 0 until n) {
        val outIdx = pos + i - nrLatency
        if (outIdx in 0 until outFrames) {
          for (c in 0 until channels) out[c][outIdx] = buf.data[c][i]
        }
      }
      written += n
      pos += n
      block++
      if (block % 64L == 0L) onProgress?.invoke(0.35f + 0.5f * pos / padded)
    }

    // Safety: avoid hard digital clipping in the exported file.
    var peak = 0f
    for (c in 0 until channels) for (v in out[c]) { val a = abs(v); if (a > peak) peak = a }
    if (peak > 1f) { val g = 0.99f / peak; for (c in 0 until channels) for (i in out[c].indices) out[c][i] *= g }

    writeWav(outFile, out, channels, sr)
    onProgress?.invoke(0.95f)
    val after = measure(out, channels, sr)
    onProgress?.invoke(1f)
    return MasterRenderResult(outFile, outFrames * 1000L / sr, before, after)
  }

  private fun buildChain(s: MasterAudioSettings): AudioDspChainSpec {
    val nodes = ArrayList<AudioDspNodeSpec>()
    if (s.eqEnabled) nodes += AudioDspNodeSpec("eq", bands = s.eqBands)
    if (s.compressorEnabled) {
      nodes += AudioDspNodeSpec(
        "compressor",
        parameters = mapOf(
          "threshold" to s.compThresholdDb, "ratio" to s.compRatio,
          "attack" to s.compAttackMs, "release" to s.compReleaseMs, "makeup" to s.compMakeupDb,
        ),
      )
    }
    if (s.deEsserEnabled) {
      nodes += AudioDspNodeSpec(
        "deesser",
        parameters = mapOf("threshold" to s.deEsserThresholdDb, "reduction" to s.deEsserReductionDb),
      )
    }
    return AudioDspChainSpec(nodes)
  }

  private fun decode(uri: String, startMs: Long, endMs: Long): DecodedPcm {
    val dec = MediaCodecAudioDecoder(context)
    return dec.decode(uri, startMs * 1000L, if (endMs > 0) endMs * 1000L else -1L)
  }

  private fun writeWav(file: File, data: Array<FloatArray>, channels: Int, sr: Int) {
    file.parentFile?.mkdirs()
    val w = WavWriter(file, sr, channels, floatPcm = false)
    try {
      val frames = data[0].size
      val chunk = 4096
      val tmp = FloatArray(chunk * channels)
      var p = 0
      while (p < frames) {
        val n = minOf(chunk, frames - p)
        var k = 0
        for (i in 0 until n) for (c in 0 until channels) tmp[k++] = data[c][p + i]
        w.write(tmp, n)
        p += n
      }
    } finally {
      w.close()
    }
  }

  private fun measure(data: Array<FloatArray>, channels: Int, sr: Int): AudioLevels {
    val frames = data[0].size
    if (frames == 0) return AudioLevels(-120f, -120f, -120f, false)
    var peak = 0f
    var sumSq = 0.0
    for (c in 0 until channels) for (v in data[c]) {
      val a = abs(v); if (a > peak) peak = a
      sumSq += v.toDouble() * v
    }
    val rms = sqrt(sumSq / (frames.toDouble() * channels))
    val meter = LoudnessMeter().also { it.configure(sr, channels) }
    val buf = AudioBuffer(channels, blockFrames)
    var p = 0
    while (p < frames) {
      val n = minOf(blockFrames, frames - p)
      for (c in 0 until channels) System.arraycopy(data[c], p, buf.data[c], 0, n)
      meter.process(buf, n)
      p += n
    }
    val lufs = meter.integratedLufs()
    return AudioLevels(toDb(peak.toDouble()), toDb(rms), if (lufs.isFinite()) lufs.toFloat() else -70f, peak >= 0.999f)
  }

  private fun toDb(v: Double): Float = if (v <= 1e-6) -120f else (20.0 * log10(v)).toFloat()

  companion object {
    val EQ_BAND_LABELS = listOf("HPF 30", "100 Hz", "350 Hz", "1 kHz", "4 kHz", "10 kHz")

    fun eqPreset(name: String): List<EqualizerBand> = when (name) {
      "flat" -> ParametricEqualizer.defaultBands()
      else -> {
        // Map preset onto the 6-band layout so sliders stay in sync.
        val base = ParametricEqualizer.defaultBands().toMutableList()
        when (name) {
          "vocal" -> { base[1] = base[1].copy(gainDb = -2f); base[2] = base[2].copy(gainDb = -3f); base[4] = base[4].copy(gainDb = 3f); base[5] = base[5].copy(gainDb = 1.5f) }
          "bass_boost" -> { base[1] = base[1].copy(gainDb = 6f); base[2] = base[2].copy(gainDb = 2f) }
          "treble_boost" -> { base[4] = base[4].copy(gainDb = 2f); base[5] = base[5].copy(gainDb = 5f) }
        }
        base
      }
    }
  }
}
