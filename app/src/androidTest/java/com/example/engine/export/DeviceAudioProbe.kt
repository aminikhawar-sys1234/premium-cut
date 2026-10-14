package com.example.engine.export

import android.content.Context
import com.example.domain.model.Timeline
import com.example.engine.effects.LookVerificationReport
import kotlinx.coroutines.runBlocking
import kotlin.math.abs

/**
 * Answers, on the device, the question "should this export contain an audio track?".
 *
 * `AsyncFramePipelineEngine` decides that from `AudioExportProcessor.hasActiveAudio(timeline)` and
 * then from the length of the mixed PCM. Both are reproduced here and written into the verification
 * report, so a missing audio track can be attributed either to the timeline (no audible source) or
 * to the mixer (it produced nothing) instead of being blamed on the muxer.
 */
internal object DeviceAudioProbe {

  data class Result(
    val pipelineSeesAudio: Boolean,
    val mixedSamples: Int,
    val peak: Float,
    val failure: String?
  ) {
    /** True when the exporter is expected to write an audio track. */
    val expectsAudioTrack: Boolean
      get() = pipelineSeesAudio && mixedSamples > 0 && peak > 1e-4f
  }

  fun probeAndReport(context: Context, timeline: Timeline, durationMs: Long, label: String): Result {
    val processor = AudioExportProcessor(context)
    val seesAudio = try {
      processor.hasActiveAudio(timeline)
    } catch (t: Throwable) {
      LookVerificationReport.line("[$label] hasActiveAudio threw ${t.javaClass.simpleName}: ${t.message}")
      false
    }
    var samples = 0
    var peak = 0f
    var failure: String? = null
    try {
      val pcm = runBlocking { AudioExportProcessor(context).mixTimelineAudio(timeline, durationMs) }
      samples = pcm.size
      for (s in pcm) {
        val v = abs(s.toInt()) / 32768f
        if (v > peak) peak = v
      }
    } catch (t: Throwable) {
      failure = "${t.javaClass.simpleName}: ${t.message}"
    }
    val result = Result(seesAudio, samples, peak, failure)
    LookVerificationReport.line(
      "[$label] hasActiveAudio=$seesAudio mixedSamples=$samples peak=${"%.4f".format(peak)} " +
        "mixFailure=${failure ?: "none"} -> expectsAudioTrack=${result.expectsAudioTrack}"
    )
    return result
  }
}
