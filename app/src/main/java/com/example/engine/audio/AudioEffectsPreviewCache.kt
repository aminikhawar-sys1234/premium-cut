package com.example.engine.audio

import android.content.Context
import com.ahstudio.audio.master.decoder.MediaCodecAudioDecoder
import com.ahstudio.audio.master.recording.WavWriter
import com.example.domain.model.AudioClip
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Offline bake of clip [AudioClip.audioEffects] so the ExoPlayer preview can hear the
 * same DSP [AudioExportProcessor] applies at export. The timeline URI is not rewritten,
 * so export still processes the original source once.
 */
object AudioEffectsPreviewCache {

  private val cache = ConcurrentHashMap<String, File>()
  private val inflight = ConcurrentHashMap.newKeySet<String>()

  fun cachedUri(clip: AudioClip): String? {
    if (!clip.audioEffects.hasActiveEffects()) return null
    val file = cache[key(clip)] ?: return null
    return file.takeIf { it.exists() && it.length() > 0L }?.absolutePath
  }

  fun request(context: Context, clip: AudioClip, scope: CoroutineScope, onReady: () -> Unit) {
    if (!clip.audioEffects.hasActiveEffects()) return
    val cacheKey = key(clip)
    if (cachedUri(clip) != null) return
    if (!inflight.add(cacheKey)) return
    scope.launch(Dispatchers.IO) {
      try {
        val baked = bake(context, clip)
        if (baked != null) {
          cache[cacheKey] = baked
          withContext(Dispatchers.Main) { onReady() }
        }
      } finally {
        inflight.remove(cacheKey)
      }
    }
  }

  private fun key(clip: AudioClip): String {
    val fx = clip.audioEffects
    return listOf(
      clip.uri,
      clip.sourceStartMs,
      clip.sourceEndMs,
      fx.voiceEffect,
      fx.pitchShiftSemitones,
      fx.noiseReductionDb,
      fx.lowGainDb,
      fx.midGainDb,
      fx.highGainDb,
      fx.normalizeVolume
    ).joinToString("|")
  }

  private fun bake(context: Context, clip: AudioClip): File? {
    return try {
      val endUs = if (clip.sourceEndMs > clip.sourceStartMs) clip.sourceEndMs * 1000L else -1L
      val decoded = MediaCodecAudioDecoder(context).decode(
        clip.uri,
        clip.sourceStartMs.coerceAtLeast(0L) * 1000L,
        endUs
      )
      val channels = decoded.channels.coerceAtLeast(1)
      val frames = decoded.data.firstOrNull()?.size ?: 0
      if (frames <= 0) return null
      val interleaved = ShortArray(frames * channels)
      for (f in 0 until frames) {
        for (c in 0 until channels) {
          val sample = decoded.data[c][f].coerceIn(-1f, 1f)
          interleaved[f * channels + c] = (sample * 32767f).toInt().toShort()
        }
      }
      val processed = AdvancedAudioProcessor.processPcmBuffer(
        pcm = interleaved,
        sampleRate = decoded.sampleRate,
        channels = channels,
        effects = clip.audioEffects
      )
      val outDir = File(context.cacheDir, "audio_fx_preview").apply { mkdirs() }
      val outFile = File(outDir, "fx_${clip.id.take(8)}_${System.currentTimeMillis()}.wav")
      val writer = WavWriter(outFile, decoded.sampleRate, channels, floatPcm = false)
      try {
        val floats = FloatArray(processed.size) { i -> processed[i] / 32768f }
        writer.write(floats, processed.size / channels)
      } finally {
        writer.close()
      }
      outFile.takeIf { it.exists() && it.length() > 0L }
    } catch (_: Exception) {
      null
    }
  }
}
