package com.example.engine.export

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.util.Log
import com.example.domain.model.Timeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileInputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

data class ExportFramePlan(val frameIndex: Long, val presentationTimeUs: Long, val timelinePositionMs: Long)
data class ExportPlan(val durationMs: Long, val totalFrames: Long, val frameRate: Int, val frames: Sequence<ExportFramePlan>)

/** Deterministic planning metadata; VideoExporter remains the authoritative fallback compositor. */
object ExportRenderPlanner {
  fun build(timeline: Timeline, config: ExportConfig): ExportPlan {
    val durationMs = timeline.totalDurationMs.coerceAtLeast(0L)
    val fps = config.frameRate.fps.coerceAtLeast(1)
    val totalFrames = if (durationMs == 0L) 0L else ceil(durationMs / 1000.0 * fps).toLong()
    val frames = sequence {
      var index = 0L
      while (index < totalFrames) {
        val ptsUs = index * 1_000_000L / fps
        val positionMs = (ptsUs / 1000L).coerceAtMost(max(0L, durationMs - 1L))
        yield(ExportFramePlan(index, ptsUs, positionMs))
        index++
      }
    }
    return ExportPlan(durationMs, totalFrames, fps, frames)
  }

  fun activeVideoClipCount(timeline: Timeline, positionMs: Long): Int =
    timeline.videoClips.count { positionMs >= it.timelineStartMs && positionMs < it.timelineStartMs + it.durationMs }

  fun activeAudioClipCount(timeline: Timeline, positionMs: Long): Int =
    timeline.audioClips.count { positionMs >= it.timelineStartMs && positionMs < it.timelineStartMs + it.durationMs }
}

data class ExportCapabilityReport(
  val videoEncoders: List<String>, val audioEncoders: List<String>, val h264Supported: Boolean,
  val hevcSupported: Boolean, val requestedSupported: Boolean, val width: Int = 0,
  val height: Int = 0, val effectiveMime: String? = null, val reason: String? = null
)

object ProfessionalCodecCapabilities {
  private const val AAC = "audio/mp4a-latm"
  fun inspect(config: ExportConfig, dimensions: Pair<Int, Int>): ExportCapabilityReport {
    val width = dimensions.first; val height = dimensions.second
    val videoEncoders = mutableListOf<String>(); val audioEncoders = mutableListOf<String>()
    var h264 = false; var hevc = false
    return try {
      for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
        if (!info.isEncoder) continue
        val types = info.supportedTypes.asList()
        if (types.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }) h264 = true
        if (types.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) }) hevc = true
        if (types.any { it.equals(AAC, true) }) audioEncoders += info.name
        for (mime in listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC)) {
          if (!types.any { it.equals(mime, true) }) continue
          val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: continue
          val vc = caps.videoCapabilities ?: continue
          val sizeOk = vc.isSizeSupported(width, height) || vc.isSizeSupported(height, width)
          val fpsOk = runCatching {
            vc.supportedFrameRates.contains(config.frameRate.fps) ||
            vc.getSupportedFrameRatesFor(width, height).contains(config.frameRate.fps.toDouble())
          }.getOrDefault(true)
          if (sizeOk && fpsOk) videoEncoders += "${info.name}:$mime"
        }
      }
      val effectiveMime = when (config.codecProfile) {
        CodecProfile.H265_HEVC -> MediaFormat.MIMETYPE_VIDEO_HEVC
        CodecProfile.H264_AVC -> MediaFormat.MIMETYPE_VIDEO_AVC
        CodecProfile.AUTO -> if ((width >= 2160 || height >= 2160) && hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else if (h264) MediaFormat.MIMETYPE_VIDEO_AVC else MediaFormat.MIMETYPE_VIDEO_HEVC
      }
      val requested = videoEncoders.any { it.endsWith(":$effectiveMime") } || videoEncoders.isNotEmpty() || h264 || hevc
      ExportCapabilityReport(videoEncoders.distinct(), audioEncoders.distinct(), h264, hevc, requested, width, height, effectiveMime, if (!requested) "No compatible video encoder found for ${width}x${height} @ ${config.frameRate.fps}fps ($effectiveMime)." else null)
    } catch (t: Throwable) {
      ExportCapabilityReport(emptyList(), emptyList(), h264, hevc, false, width, height, null, "Codec capability scan failed: ${t.message ?: "unknown error"}")
    }
  }
  private fun isHardware(info: MediaCodecInfo): Boolean = if (android.os.Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else {
    val n = info.name.lowercase(); !n.startsWith("omx.google.") && !n.startsWith("c2.android.") && !n.contains("software") && !n.contains("sw.")
  }
}

data class ExportValidationResult(
  val valid: Boolean, val message: String, val durationMs: Long = 0L, val videoCodec: String? = null,
  val audioCodec: String? = null, val width: Int = 0, val height: Int = 0, val frameRate: Int? = null,
  val failure: ExportFailure? = null
)

/**
 * Acceptance gate for every exported file. A file is only valid when it has a real, decodable video track
 * with the planned size/orientation, the right duration, readable samples up to the end, and frames that are
 * actually visible. There is deliberately NO "looks big enough, so accept it" fallback: unparsable or
 * suspicious output is rejected with a reason.
 *
 * The file is probed here (Android: MediaExtractor / MediaMetadataRetriever) into an [ExportContentProbe];
 * every accept/reject decision lives in [ExportContentRules], which is pure and unit-tested. Black-frame
 * detection thresholds are documented on [FrameAnalyzer].
 */
object ExportValidator {
  private const val TAG = "ExportValidator"
  private const val GRID = 24
  private const val EXTRA_SAMPLES = 9

  fun validate(
    file: File,
    config: ExportConfig,
    expectedDurationMs: Long,
    requireAudio: Boolean = true,
    expectedDimensions: Pair<Int, Int>? = null,
    sampleFrames: Boolean = true,
    expectedRotation: Int = 0,
    pipelineFailure: String? = null
  ): ExportValidationResult {
    val expectations = ExportExpectations(
      expectedDurationMs = expectedDurationMs, requireAudio = requireAudio,
      expectedDimensions = expectedDimensions, expectedRotation = expectedRotation,
      fallbackFrameRate = config.frameRate.fps, checkFrames = sampleFrames
    )
    val probe = try {
      probe(file, sampleFrames, pipelineFailure)
    } catch (t: Throwable) {
      Log.e(TAG, "Unexpected error validating ${file.absolutePath}", t)
      return ExportValidationResult(false, "MP4 validation failed (${t.javaClass.simpleName}: ${t.message ?: "unknown parsing error"})", failure = ExportFailure.CONTAINER_UNREADABLE)
    }
    val verdict = ExportContentRules.check(probe, expectations)
    val duration = max(probe.videoDurationMs, probe.containerDurationMs)
    return if (verdict != null) {
      Log.e(TAG, "Validation failed [${verdict.failure}]: ${verdict.message} (${file.absolutePath}, ${probe.fileBytes} bytes)")
      ExportValidationResult(false, verdict.message, duration, probe.videoMime, probe.audioMime, probe.width, probe.height, probe.frameRate, verdict.failure)
    } else {
      Log.i(TAG, "MP4 validation succeeded: video=${probe.videoDurationMs}ms ${probe.width}x${probe.height} samples=${probe.sampleTimesUs.size} frames=${probe.frames.size}")
      ExportValidationResult(true, "Verified", duration, probe.videoMime, probe.audioMime, probe.width, probe.height, probe.frameRate)
    }
  }

  private fun probe(file: File, sampleFrames: Boolean, pipelineFailure: String?): ExportContentProbe {
    if (!file.exists()) return ExportContentProbe(fileExists = false, pipelineFailure = pipelineFailure)
    val fileBytes = file.length()
    // Too small to hold an MP4: let the rules reject it without opening any decoder.
    if (fileBytes <= ExportContentRules.MIN_FILE_BYTES) return ExportContentProbe(fileBytes = fileBytes, pipelineFailure = pipelineFailure)

    val boxes = try { Mp4BoxScanner.scan(file) } catch (_: Throwable) { null }
    val extractor = MediaExtractor()
    val retriever = MediaMetadataRetriever()
    try {
      try {
        extractor.setDataSource(file.absolutePath)
      } catch (e: Throwable) {
        return ExportContentProbe(
          fileBytes = fileBytes, containerReadable = false,
          containerError = "${e.javaClass.simpleName}: ${e.message ?: "corrupted or incomplete moov atom"}",
          boxes = boxes, pipelineFailure = pipelineFailure
        )
      }

      var videoTrack = -1; var audioTrack = -1
      var videoMime: String? = null; var audioMime: String? = null
      var w = 0; var h = 0; var fps: Int? = null
      var videoDurationUs = 0L; var audioDurationUs = 0L; var formatRotation = 0
      for (i in 0 until extractor.trackCount) {
        val f = extractor.getTrackFormat(i)
        val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
        if (mime.startsWith("video/") && videoTrack < 0) {
          videoTrack = i; videoMime = mime
          if (f.containsKey(MediaFormat.KEY_WIDTH)) w = f.getInteger(MediaFormat.KEY_WIDTH)
          if (f.containsKey(MediaFormat.KEY_HEIGHT)) h = f.getInteger(MediaFormat.KEY_HEIGHT)
          if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) fps = f.getInteger(MediaFormat.KEY_FRAME_RATE)
          if (f.containsKey(MediaFormat.KEY_DURATION)) videoDurationUs = f.getLong(MediaFormat.KEY_DURATION)
          if (f.containsKey(MediaFormat.KEY_ROTATION)) formatRotation = f.getInteger(MediaFormat.KEY_ROTATION)
        } else if (mime.startsWith("audio/") && audioTrack < 0) {
          audioTrack = i; audioMime = mime
          if (f.containsKey(MediaFormat.KEY_DURATION)) audioDurationUs = f.getLong(MediaFormat.KEY_DURATION)
        }
      }

      var retrieverError: String? = null
      try { retriever.setDataSource(file.absolutePath) } catch (e: Throwable) {
        retrieverError = "${e.javaClass.simpleName}: ${e.message ?: "unknown"}"
      }
      val rotation = if (retrieverError == null)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: formatRotation
      else formatRotation
      val containerMs = if (retrieverError == null)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L else 0L

      // Walk every video sample header (no decoding): timestamps, payload size, keyframes.
      val times = ArrayList<Long>()
      var bytes = 0L
      var hasSync = false
      if (videoTrack >= 0) {
        extractor.selectTrack(videoTrack)
        while (true) {
          val t = extractor.sampleTime
          if (t < 0) break
          if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) hasSync = true
          val size = extractor.sampleSize
          if (size > 0) bytes += size
          times += t
          if (!extractor.advance()) break
        }
      }
      val sampleTimes = times.toLongArray()
      val videoMs = if (videoDurationUs > 0L) videoDurationUs / 1000L else containerMs

      val frames = ArrayList<FrameProbe>()
      if (sampleFrames && retrieverError == null && videoTrack >= 0 && sampleTimes.isNotEmpty()) {
        val sorted = sampleTimes.sortedArray()
        fun grab(role: FrameRole, us: Long): FrameProbe = FrameProbe(role, us, decodeStats(retriever, us))
        frames += grab(FrameRole.FIRST, sorted.first())
        frames += grab(FrameRole.MIDDLE, sorted[sorted.size / 2])
        var last = grab(FrameRole.LAST, sorted.last())
        // Some retrievers return null for the exact final timestamp; accept the sample just before it.
        if (!last.decoded && sorted.size >= 2) last = grab(FrameRole.LAST, sorted[sorted.size - 2])
        frames += last
        // All key frames black? Look at more of the clip before deciding (fade-ins, dark intros).
        if (frames.mapNotNull { it.stats }.let { it.isNotEmpty() && it.all { s -> s.isBlack } }) {
          for (i in 1..EXTRA_SAMPLES) {
            val idx = (sorted.size * i / (EXTRA_SAMPLES + 1)).coerceIn(0, sorted.size - 1)
            frames += grab(FrameRole.EXTRA, sorted[idx])
          }
        }
      }

      return ExportContentProbe(
        fileBytes = fileBytes,
        containerReadable = retrieverError == null,
        containerError = retrieverError,
        boxes = boxes,
        hasVideoTrack = videoTrack >= 0, hasAudioTrack = audioTrack >= 0,
        videoMime = videoMime, audioMime = audioMime,
        width = w, height = h,
        videoDurationMs = videoMs,
        audioDurationMs = audioDurationUs / 1000L,
        containerDurationMs = containerMs,
        rotationDegrees = rotation,
        sampleTimesUs = sampleTimes, sampleBytes = bytes, hasSyncSample = hasSync,
        frameRate = fps, frames = frames, pipelineFailure = pipelineFailure
      )
    } finally {
      try { retriever.release() } catch (_: Throwable) {}
      try { extractor.release() } catch (_: Throwable) {}
    }
  }

  /** Fully decodes the frame at [timeUs] (OPTION_CLOSEST, not the sync-frame shortcut) and measures it. */
  private fun decodeStats(retriever: MediaMetadataRetriever, timeUs: Long): FrameAnalyzer.Stats? {
    val bmp = try {
      retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
    } catch (_: Throwable) { null } ?: return null
    return try {
      val bw = bmp.width; val bh = bmp.height
      if (bw <= 0 || bh <= 0) FrameAnalyzer.Stats(0.0, 0, 0.0, 0)
      else {
        val px = IntArray(GRID * GRID)
        for (gy in 0 until GRID) for (gx in 0 until GRID) {
          px[gy * GRID + gx] = bmp.getPixel(
            ((gx + 0.5f) / GRID * bw).toInt().coerceIn(0, bw - 1),
            ((gy + 0.5f) / GRID * bh).toInt().coerceIn(0, bh - 1)
          )
        }
        FrameAnalyzer.analyze(px)
      }
    } finally {
      try { bmp.recycle() } catch (_: Throwable) {}
    }
  }
}

enum class ProfessionalExportStage { PREPARING, DECODING, RENDERING, ENCODING_VIDEO, MIXING_AUDIO, MUXING, VERIFYING, COMPLETED, FAILED, CANCELLED }
data class ProfessionalExportProgress(val stage: ProfessionalExportStage = ProfessionalExportStage.PREPARING, val fraction: Float = 0f, val renderedDurationMs: Long = 0L, val estimatedRemainingMs: Long? = null, val message: String = "Preparing export")

internal object ExportProgressMapping {
  fun encodingFraction(doneFrames: Long, totalFrames: Long): Float {
    if (totalFrames <= 0L) return 0.05f
    val frameFrac = (doneFrames.toFloat() / totalFrames).coerceIn(0f, 1f).coerceAtMost(0.95f)
    return 0.05f + frameFrac * 0.90f
  }
}

/**
 * Orchestrates one export through the single authoritative pipeline ([AsyncFramePipelineEngine]), then
 * validates the produced file. There is no secondary renderer: if the pipeline (including its encoder
 * fallback ladder) cannot produce a valid video, the export FAILS with a clear message.
 */
class ProfessionalExportEngine(private val context: Context) {
  private val tag = "ProfessionalExportEngine"
  private val _progress = MutableStateFlow(ProfessionalExportProgress())
  val progress: StateFlow<ProfessionalExportProgress> = _progress.asStateFlow()
  @Volatile private var cancelled = false
  @Volatile private var activePipeline: AsyncFramePipelineEngine? = null

  fun cancel() {
    cancelled = true
    activePipeline?.cancel()
  }

  suspend fun export(
    projectName: String,
    timeline: Timeline,
    config: ExportConfig,
    outputFile: File,
    requireAudio: Boolean = true,
    shouldCancel: () -> Boolean = { false },
    isPaused: () -> Boolean = { false }
  ): Result<File> = withContext(Dispatchers.IO) {
    cancelled = false
    activePipeline = null
    try {
      _progress.value = ProfessionalExportProgress(message = "Checking device encoder capabilities")
      val dimensions = ExportDimensionResolver.resolve(config.resolution, timeline.aspectRatio)
      val capability = ProfessionalCodecCapabilities.inspect(config, dimensions)
      if (!capability.requestedSupported) {
        Log.w(tag, "Capability check warning: ${capability.reason}. The pipeline will try its encoder fallbacks.")
      }
      coroutineContext.ensureActive()
      checkCancelled()

      val plan = ExportRenderPlanner.build(timeline, config)
      if (plan.durationMs <= 0L || plan.totalFrames <= 0L) {
        return@withContext Result.failure(IllegalArgumentException("Timeline contains no renderable duration."))
      }
      _progress.value = ProfessionalExportProgress(ProfessionalExportStage.PREPARING, 0.05f, message = "Prepared ${plan.totalFrames} deterministic output frames")

      val hasAudio = AudioExportProcessor(context).hasActiveAudio(timeline)
      val pipeline = AsyncFramePipelineEngine(context)
      activePipeline = pipeline

      val rendered: File = coroutineScope {
        val progressJob = launch(Dispatchers.Default) {
          while (isActive) {
            if (shouldCancel()) this@ProfessionalExportEngine.cancel()
            val total = pipeline.plannedTotalFrames.takeIf { it > 0L } ?: plan.totalFrames
            val encoded = pipeline.metrics.encodedFrames.get()
            val submitted = pipeline.metrics.gpuFrames.get()
            // Encoder output can lag submitted frames by seconds; count submitted work so the
            // ring is not stuck at 5% while the GPU pipeline is already rendering.
            val done = max(encoded, submitted)
            val mapped = ExportProgressMapping.encodingFraction(done, total)
            val fps = pipeline.activePlan?.fps ?: plan.frameRate
            _progress.value = ProfessionalExportProgress(
              ProfessionalExportStage.ENCODING_VIDEO,
              mapped,
              renderedDurationMs = ((done.toDouble() / max(1, fps)) * 1000L).toLong(),
              message = "Hardware GPU pipeline: encoded $encoded / $total frames (${((done.toFloat() / max(1L, total)).coerceIn(0f, 1f) * 100).toInt()}%)"
            )
            delay(100L)
          }
        }
        try {
          pipeline.export(timeline, config, outputFile, isPaused = isPaused)
        } finally {
          progressJob.cancel()
        }
      }
      checkCancelled()

      _progress.value = ProfessionalExportProgress(ProfessionalExportStage.VERIFYING, 0.96f, plan.durationMs, message = "Verifying exported video integrity...")
      val encoded = pipeline.activePlan?.let { it.width to it.height } ?: dimensions
      // 1080p and below must match the requested canvas exactly; 2K/4K may use a capability fallback size.
      val expectedDims = if (max(dimensions.first, dimensions.second) <= 1920) dimensions else encoded
      val validation = ExportValidator.validate(rendered, config, plan.durationMs, requireAudio && hasAudio, expectedDims)
      Log.i(tag, "[VALIDATION_RESULT] valid=${validation.valid} message=${validation.message} duration=${validation.durationMs}ms videoCodec=${validation.videoCodec} audioCodec=${validation.audioCodec} res=${validation.width}x${validation.height}")
      if (!validation.valid) {
        // Never hand a broken file to the user as a successful export. The project is untouched, so the user can retry.
        Log.e(tag, "Export validation FAILED: ${validation.message} (${rendered.length()} bytes). Rejecting output.")
        try { rendered.delete() } catch (_: Exception) {}
        _progress.value = ProfessionalExportProgress(
          ProfessionalExportStage.FAILED, 0f, plan.durationMs,
          message = "Export failed verification: ${validation.message}"
        )
        return@withContext Result.failure(IllegalStateException("Exported video failed verification: ${validation.message}"))
      }
      checkCancelled()

      Log.i(tag, "[EXPORT_COMPLETE] path=${rendered.absolutePath} sizeBytes=${rendered.length()}")
      _progress.value = ProfessionalExportProgress(ProfessionalExportStage.COMPLETED, 1f, plan.durationMs, message = "Export completed successfully!")
      Result.success(rendered)
    } catch (e: CancellationException) {
      activePipeline?.cancel()
      activePipeline = null
      outputFile.delete()
      _progress.value = ProfessionalExportProgress(ProfessionalExportStage.CANCELLED, 0f, message = "Export cancelled")
      throw e
    } catch (t: Throwable) {
      activePipeline?.cancel()
      activePipeline = null
      outputFile.delete()
      Log.e(tag, "Export failed", t)
      _progress.value = ProfessionalExportProgress(ProfessionalExportStage.FAILED, 0f, message = t.message ?: "Export failed")
      Result.failure(t)
    } finally {
      activePipeline = null
    }
  }

  private fun checkCancelled() {
    if (cancelled) throw CancellationException("Export cancelled")
  }
}
