package com.example.engine.export

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.util.Log
import com.example.domain.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import kotlin.math.min

enum class CodecProfile(val label: String, val mimeType: String) {
  AUTO("Auto (Best Performance)", "auto"),
  H264_AVC("H.264 / AVC (Universal)", MediaFormat.MIMETYPE_VIDEO_AVC),
  H265_HEVC("H.265 / HEVC (High Efficiency 4K)", MediaFormat.MIMETYPE_VIDEO_HEVC)
}

data class ExportConfig(
  val resolution: Resolution = Resolution.RES_1080P,
  val frameRate: FrameRate = FrameRate.FPS_30,
  val quality: ExportQuality = ExportQuality.HIGH,
  val customBitrateKbps: Int = 12000,
  val codecProfile: CodecProfile = CodecProfile.AUTO
)

sealed class ExportState {
  object Idle : ExportState()
  data class Rendering(
    val progressPercent: Float,
    val currentFrame: Int,
    val totalFrames: Int,
    val status: String = "Encoding video...",
    val fps: Float = 0f,
    val estimatedRemainingSec: Int = 0,
    val resolution: Resolution = Resolution.RES_1080P,
    val renderEngine: String = "Hardware Video Engine (GPU)",
    val isPaused: Boolean = false
  ) : ExportState()
  data class Success(val file: File, val durationMs: Long, val fileSizeBytes: Long) : ExportState()
  data class Error(
    val message: String,
    val failedFrame: Int = 0,
    val failedLayer: String? = null,
    val canRetry: Boolean = true
  ) : ExportState()
}

/**
 * MuxerCoordinator manages dynamic track registration and sample writing to MediaMuxer.
 * It ensures:
 * 1. Tracks are registered before MediaMuxer is started; the muxer only starts once EVERY expected
 *    track (video, and audio when [hasAudio]) has its real encoder output format.
 * 2. Exact track indices returned by MediaMuxer are stored and used.
 * 3. CODEC_CONFIG packets are filtered out (CSD is supplied via MediaFormat) and the codec EOS flag
 *    is stripped from samples (it is a codec-queue signal, not a container flag).
 * 4. Per-track strictly monotonic timestamps; any correction is counted so callers can detect it.
 * 5. Any muxer failure (add track, start, write) is thrown, never swallowed: a file that silently lost
 *    its video samples (audio-only / empty-video MP4) is worse than a clear export error.
 * 6. Safe finalization of the MP4 container (moov atom) before file inspection.
 */
class MuxerCoordinator(
  private val mediaMuxer: MediaMuxer,
  private val hasAudio: Boolean,
  /** Upper bound for samples buffered while waiting for the remaining track format(s). */
  private val maxPendingBytes: Long = 96L * 1024 * 1024
) {
  private val tag = "MuxerCoordinator"

  var videoTrackIndex: Int = -1
    private set
  var audioTrackIndex: Int = -1
    private set
  var isStarted: Boolean = false
    private set
  var isStopped: Boolean = false
    private set

  private var lastVideoPtsUs: Long = -1L
  private var lastAudioPtsUs: Long = -1L
  private var pendingBytes: Long = 0L

  /** Samples actually handed to MediaMuxer (queued samples count once they are flushed). */
  var videoSamplesWritten: Long = 0L
    private set
  var audioSamplesWritten: Long = 0L
    private set
  /** Number of timestamps that had to be nudged to stay strictly increasing. */
  var timestampCorrections: Long = 0L
    private set
  val lastWrittenVideoPtsUs: Long get() = lastVideoPtsUs

  private class QueuedPacket(
    val isAudio: Boolean,
    val data: ByteArray,
    val presentationTimeUs: Long,
    val flags: Int
  ) : Comparable<QueuedPacket> {
    override fun compareTo(other: QueuedPacket): Int {
      return presentationTimeUs.compareTo(other.presentationTimeUs)
    }
  }

  private val pendingQueue = mutableListOf<QueuedPacket>()

  @Synchronized
  fun setOrientationHint(rotation: Int) {
    if (!isStarted && !isStopped) {
      try {
        val normalized = ((rotation % 360) + 360) % 360
        mediaMuxer.setOrientationHint(normalized)
        Log.i(tag, "MuxerCoordinator setOrientationHint: $normalized degrees")
      } catch (e: Exception) {
        Log.w(tag, "Failed to set orientation hint on MediaMuxer", e)
      }
    }
  }

  /**
   * Registers the video track. Codec output-format changes after the muxer started cannot be applied to
   * an MP4; they are only acceptable if the format is the one already registered (encoders re-announce
   * the same format), so an attempted second registration is ignored rather than corrupting the file.
   */
  @Synchronized
  fun setVideoFormat(format: MediaFormat) {
    if (isStopped) return
    if (videoTrackIndex >= 0) {
      Log.w(tag, "Ignoring repeated video output format after track registration: $format")
      return
    }
    try {
      videoTrackIndex = mediaMuxer.addTrack(format)
    } catch (e: Exception) {
      throw IllegalStateException("MediaMuxer rejected the video track (${e.message})", e)
    }
    Log.d(tag, "Added video track with index $videoTrackIndex")
    checkStart()
  }

  @Synchronized
  fun setAudioFormat(format: MediaFormat) {
    if (isStopped) return
    if (audioTrackIndex >= 0) {
      Log.w(tag, "Ignoring repeated audio output format after track registration: $format")
      return
    }
    try {
      audioTrackIndex = mediaMuxer.addTrack(format)
    } catch (e: Exception) {
      throw IllegalStateException("MediaMuxer rejected the audio track (${e.message})", e)
    }
    Log.d(tag, "Added audio track with index $audioTrackIndex")
    checkStart()
  }

  @Synchronized
  private fun checkStart() {
    if (isStarted || isStopped) return
    val videoReady = videoTrackIndex >= 0
    val audioReady = !hasAudio || audioTrackIndex >= 0
    if (videoReady && audioReady) {
      try {
        mediaMuxer.start()
      } catch (e: Exception) {
        throw IllegalStateException("MediaMuxer failed to start (${e.message})", e)
      }
      isStarted = true
      Log.d(tag, "MediaMuxer started successfully (videoTrack=$videoTrackIndex, audioTrack=$audioTrackIndex)")
      flushPending()
    }
  }

  private fun nextPts(isAudio: Boolean, raw: Long): Long {
    var pts = if (raw < 0L) 0L else raw
    val last = if (isAudio) lastAudioPtsUs else lastVideoPtsUs
    if (pts <= last) {
      pts = last + 1L
      timestampCorrections++
    }
    return pts
  }

  private fun writeNow(isAudio: Boolean, buffer: ByteBuffer, offset: Int, size: Int, rawPts: Long, flags: Int) {
    val trackIndex = if (isAudio) audioTrackIndex else videoTrackIndex
    val pts = nextPts(isAudio, rawPts)
    val info = MediaCodec.BufferInfo().apply {
      set(offset, size, pts, flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
    }
    buffer.position(offset)
    buffer.limit(offset + size)
    try {
      mediaMuxer.writeSampleData(trackIndex, buffer, info)
    } catch (e: Exception) {
      throw IllegalStateException("MediaMuxer failed writing ${if (isAudio) "audio" else "video"} sample at pts=${pts}us (${e.message})", e)
    }
    if (isAudio) {
      lastAudioPtsUs = pts
      audioSamplesWritten++
    } else {
      lastVideoPtsUs = pts
      videoSamplesWritten++
    }
  }

  @Synchronized
  private fun flushPending() {
    if (!isStarted || isStopped) return
    pendingQueue.sort()
    for (packet in pendingQueue) {
      writeNow(packet.isAudio, ByteBuffer.wrap(packet.data), 0, packet.data.size, packet.presentationTimeUs, packet.flags)
    }
    pendingQueue.clear()
    pendingBytes = 0L
  }

  private fun enqueue(isAudio: Boolean, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
    pendingBytes += info.size
    if (pendingBytes > maxPendingBytes) {
      val missing = if (videoTrackIndex < 0) "video" else "audio"
      throw IllegalStateException("The $missing encoder never produced an output format; export aborted instead of buffering without bound.")
    }
    val bytes = ByteArray(info.size)
    val currentPos = buffer.position()
    val currentLim = buffer.limit()
    buffer.position(info.offset)
    buffer.limit(info.offset + info.size)
    buffer.get(bytes)
    buffer.position(currentPos)
    buffer.limit(currentLim)
    pendingQueue.add(QueuedPacket(isAudio, bytes, info.presentationTimeUs, info.flags))
  }

  @Synchronized
  fun writeVideoSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
    if (isStopped) return
    // Ignore codec config buffers (CSD) and empty buffers
    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 || info.size <= 0) return
    if (isStarted && videoTrackIndex >= 0) {
      writeNow(false, buffer, info.offset, info.size, info.presentationTimeUs, info.flags)
    } else {
      enqueue(false, buffer, info)
    }
  }

  @Synchronized
  fun writeAudioSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
    if (isStopped) return
    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 || info.size <= 0) return
    if (isStarted && audioTrackIndex >= 0) {
      writeNow(true, buffer, info.offset, info.size, info.presentationTimeUs, info.flags)
    } else {
      enqueue(true, buffer, info)
    }
  }

  /**
   * Starts the muxer with the tracks registered so far. Only valid when no audio is expected, or when
   * the caller has explicitly decided to give up on audio; never called implicitly by [stopAndRelease].
   */
  @Synchronized
  fun forceStartIfPossible() {
    if (isStarted || isStopped) return
    if (videoTrackIndex >= 0) {
      try {
        mediaMuxer.start()
      } catch (e: Exception) {
        throw IllegalStateException("MediaMuxer failed to start (${e.message})", e)
      }
      isStarted = true
      Log.i(tag, "MediaMuxer started (videoTrack=$videoTrackIndex, audioTrack=$audioTrackIndex)")
      flushPending()
    }
  }

  /**
   * Finalizes and releases the muxer. Returns true only when a started muxer stopped cleanly (moov written).
   * A muxer that never started because an expected track never appeared is NOT silently started here, so an
   * audio-less (or video-less) MP4 can never be produced by accident.
   */
  @Synchronized
  fun stopAndRelease(): Boolean {
    if (isStopped) return true
    var success = true
    try {
      if (!isStarted && !hasAudio && videoTrackIndex >= 0) {
        forceStartIfPossible()
      }
      if (isStarted) {
        flushPending()
        try {
          mediaMuxer.stop()
          Log.i(tag, "MediaMuxer stopped successfully. MOOV atom finalized.")
        } catch (e: Exception) {
          Log.e(tag, "MediaMuxer.stop() failed", e)
          success = false
        }
      } else {
        Log.w(tag, "MuxerCoordinator was never started prior to stopAndRelease (video=$videoTrackIndex audio=$audioTrackIndex)")
        success = false
      }
    } finally {
      isStopped = true
      try {
        mediaMuxer.release()
        Log.d(tag, "MediaMuxer released.")
      } catch (e: Exception) {
        Log.w(tag, "MediaMuxer.release() warning", e)
      }
    }
    return success
  }
}

/**
 * High-Performance, Professional Video Rendering & Export Engine.
 * Supports 4K UHD, 2K QHD, 1080p FHD, 60fps, complex multi-layer compositions,
 * GPU hardware surface rendering, memory-bounded bitmap pooling, orientation stabilization,
 * and sample-accurate multi-track audio mixing with smooth, freeze-free gallery playback.
 */
class VideoExporter(private val context: Context) {

  private val tag = "VideoExporter"

  private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
  val exportState: StateFlow<ExportState> = _exportState.asStateFlow()

  @Volatile
  private var isCancelled = false
  @Volatile
  private var isPaused = false

  /** Human-readable reason of the most recent failed [exportVideo]/[exportTimelineSegment] call. */
  @Volatile
  var lastError: String? = null
    private set

  /** True once the user asked to cancel (UI button) and until the next export begins. */
  fun isCancelRequested(): Boolean = isCancelled

  /** True while the user has export paused (UI button). */
  fun isPauseRequested(): Boolean = isPaused

  /**
   * Exports a single media file (optionally trimmed) to an MP4 of [targetWidth]x[targetHeight].
   *
   * This is NOT a separate engine: it builds a one-clip timeline and runs it through the one authoritative
   * pipeline ([AsyncFramePipelineEngine]) — decode -> OES texture -> GPU composition -> encoder input
   * Surface -> MediaCodec -> MediaMuxer — so every encoded frame really is rendered into the encoder Surface,
   * rotation metadata is honoured by the GPU compositor, timestamps are frame-index based (monotonic) and
   * audio is muxed with the video.
   *
   * @return true only when a validated MP4 with a real video track was written to [outputFilePath].
   *   On any failure the output file is removed, [lastError] holds the reason and [exportState] is Error.
   */
  suspend fun exportVideo(
    inputFilePath: String,
    outputFilePath: String,
    targetWidth: Int = 1080,
    targetHeight: Int = 1920,
    clipStartOffsetUs: Long = 0L,
    clipDurationUs: Long = Long.MAX_VALUE,
    onProgress: (Float) -> Unit
  ): Boolean = withContext(Dispatchers.IO) {
    isCancelled = false
    lastError = null
    val outputFile = File(outputFilePath)
    fun fail(message: String, cause: Throwable? = null): Boolean {
      Log.e(tag, "exportVideo failed: $message", cause)
      lastError = message
      _exportState.value = ExportState.Error(message = message)
      try { if (outputFile.exists()) outputFile.delete() } catch (_: Exception) {}
      return false
    }

    // 1. Probe the source (real video track required).
    var srcW = 0
    var srcH = 0
    var srcFps = 30
    var srcDurationUs = 0L
    var hasAudioTrack = false
    var rotation = 0
    val probe = MediaExtractor()
    try {
      probe.setDataSource(inputFilePath)
      var videoFormat: MediaFormat? = null
      for (i in 0 until probe.trackCount) {
        val f = probe.getTrackFormat(i)
        val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
        if (mime.startsWith("video/") && videoFormat == null) videoFormat = f
        if (mime.startsWith("audio/")) hasAudioTrack = true
      }
      val vf = videoFormat ?: return@withContext fail("Source contains no video track: $inputFilePath")
      srcW = vf.getInteger(MediaFormat.KEY_WIDTH)
      srcH = vf.getInteger(MediaFormat.KEY_HEIGHT)
      if (vf.containsKey(MediaFormat.KEY_FRAME_RATE)) srcFps = vf.getInteger(MediaFormat.KEY_FRAME_RATE)
      if (vf.containsKey(MediaFormat.KEY_DURATION)) srcDurationUs = vf.getLong(MediaFormat.KEY_DURATION)
      rotation = com.example.engine.media.MediaMetadataHelper.extractRotation(context, inputFilePath, vf)
    } catch (e: Exception) {
      return@withContext fail("Cannot read source media: ${e.message}", e)
    } finally {
      try { probe.release() } catch (_: Exception) {}
    }

    val startMs = (clipStartOffsetUs / 1000L).coerceAtLeast(0L)
    val availableMs = if (srcDurationUs > 0L) (srcDurationUs / 1000L - startMs) else Long.MAX_VALUE
    val requestedMs = if (clipDurationUs == Long.MAX_VALUE) Long.MAX_VALUE else clipDurationUs / 1000L
    val durationMs = minOf(availableMs, requestedMs)
    if (durationMs <= 0L || durationMs == Long.MAX_VALUE) {
      return@withContext fail("Clip has no usable duration (start=${startMs}ms, available=${availableMs}ms)")
    }

    // 2. One-clip timeline (rotation comes from the file; the GPU compositor applies it).
    val clip = VideoClip(
      uri = inputFilePath,
      name = File(inputFilePath).name,
      isVideo = true,
      timelineStartMs = 0L,
      durationMs = durationMs,
      sourceStartMs = startMs,
      sourceEndMs = startMs + durationMs,
      width = srcW,
      height = srcH,
      naturalRotation = rotation,
      hasAudio = hasAudioTrack
    )
    val timeline = Timeline(videoClips = listOf(clip))
    val fps = FrameRate.values().minByOrNull { kotlin.math.abs(it.fps - srcFps.coerceIn(24, 60)) } ?: FrameRate.FPS_30
    val bitrateKbps = ((targetWidth.toLong() * targetHeight * fps.fps * 0.15f) / 1000L).toInt().coerceIn(3_000, 20_000)
    val config = ExportConfig(
      frameRate = fps,
      quality = ExportQuality.CUSTOM,
      customBitrateKbps = bitrateKbps
    )

    // 3. Run the authoritative pipeline.
    val pipeline = AsyncFramePipelineEngine(context)
    try {
      coroutineScope {
        val total = maxOf(1L, kotlin.math.ceil(durationMs / 1000.0 * fps.fps).toLong())
        val progressJob = launch(Dispatchers.Default) {
          while (isActive) {
            if (isCancelled) pipeline.cancel()
            onProgress((pipeline.metrics.encodedFrames.get().toFloat() / total).coerceIn(0f, 1f))
            delay(100L)
          }
        }
        try {
          pipeline.export(timeline, config, outputFile, sizeOverride = targetWidth to targetHeight,
            isPaused = { isPaused })
        } finally {
          progressJob.cancel()
        }
      }
    } catch (e: CancellationException) {
      try { if (outputFile.exists()) outputFile.delete() } catch (_: Exception) {}
      throw e
    } catch (e: Throwable) {
      return@withContext fail(e.message ?: e.javaClass.simpleName, e)
    }

    if (isCancelled) {
      try { if (outputFile.exists()) outputFile.delete() } catch (_: Exception) {}
      return@withContext false
    }
    if (!validateMp4(outputFile)) {
      return@withContext fail("Export finished but the MP4 has no valid video track.")
    }
    onProgress(1f)
    true
  }

  fun pauseExport() {
    isPaused = true
    (_exportState.value as? ExportState.Rendering)?.let { _exportState.value = it.copy(isPaused = true) }
  }

  fun resumeExport() {
    isPaused = false
    (_exportState.value as? ExportState.Rendering)?.let { _exportState.value = it.copy(isPaused = false) }
  }

  fun cancelExport() {
    isCancelled = true
    isPaused = false
  }

  fun beginExternalExport(config: ExportConfig) {
    isCancelled = false
    isPaused = false
    _exportState.value = ExportState.Rendering(
      progressPercent = 0f,
      currentFrame = 0,
      totalFrames = 0,
      status = "Preparing hardware export...",
      resolution = config.resolution,
      renderEngine = "Hardware GPU Engine"
    )
  }

  fun updateExternalExportProgress(progress: Float, status: String) {
    val current = _exportState.value as? ExportState.Rendering ?: return
    _exportState.value = current.copy(
      progressPercent = progress.coerceIn(0f, 1f),
      status = status
    )
  }

  fun completeExternalExport(file: File, durationMs: Long) {
    _exportState.value = ExportState.Success(
      file = file,
      durationMs = durationMs,
      fileSizeBytes = file.length()
    )
  }

  fun failExternalExport(message: String) {
    _exportState.value = ExportState.Error(message = message)
  }

  /** The user cancelled an externally driven export: return to the idle (settings) state. */
  fun markExternalCancelled() {
    isPaused = false
    _exportState.value = ExportState.Idle
  }


  /**
   * Evaluates if any asset in the project has a native resolution significantly below 1080p
   * when targeting 2K / 4K UHD rendering.
   */
  fun checkLowResolutionAssets(timeline: Timeline, targetRes: Resolution): List<String> {
    if (targetRes != Resolution.RES_2K &&
        targetRes != Resolution.RES_4K &&
        targetRes != Resolution.RES_VERTICAL_2K &&
        targetRes != Resolution.RES_VERTICAL_4K &&
        targetRes != Resolution.RES_SQUARE_2K
    ) {
      return emptyList()
    }

    val lowRes = mutableListOf<String>()
    for (clip in timeline.videoClips + timeline.overlayClips) {
      if (clip.width > 0 && clip.height > 0) {
        val minDim = min(clip.width, clip.height)
        if (minDim < 720) {
          lowRes.add("${clip.name} (${clip.width}×${clip.height})")
        }
      }
    }
    return lowRes
  }

  /**
   * Validates hardware encoder capability for 4K / 2K HEVC and AVC encoding.
   */
  fun check4KSupport(): Boolean {
    return try {
      val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
      val hevcMime = MediaFormat.MIMETYPE_VIDEO_HEVC
      for (info in codecList.codecInfos) {
        if (info.isEncoder) {
          try {
            val caps = info.getCapabilitiesForType(hevcMime)
            val videoCaps = caps.videoCapabilities
            if (videoCaps != null && videoCaps.isSizeSupported(3840, 2160)) {
              return true
            }
          } catch (ignored: Exception) {}
        }
      }
      true
    } catch (e: Exception) {
      true
    }
  }

  fun calculateEstimatedSizeBytes(durationMs: Long, config: ExportConfig): Long {
    val durationSec = (durationMs / 1000f).coerceAtLeast(1f)
    val effectiveBitrate = if (config.quality == ExportQuality.CUSTOM && config.customBitrateKbps > 0) {
      config.customBitrateKbps * 1000L
    } else {
      val baseBitrate = when (config.resolution) {
        Resolution.RES_480P -> 2_500_000L
        Resolution.RES_720P -> 5_000_000L
        Resolution.RES_1080P -> 10_000_000L
        Resolution.RES_2K, Resolution.RES_VERTICAL_2K -> 18_000_000L
        Resolution.RES_4K, Resolution.RES_VERTICAL_4K -> 35_000_000L
        Resolution.RES_SQUARE_2K -> 22_000_000L
      }
      val codecMultiplier = if (config.codecProfile == CodecProfile.H265_HEVC) 0.75f else 1.0f
      (baseBitrate * config.quality.bitrateMultiplier * (config.frameRate.fps / 30f) * codecMultiplier).toLong()
    }
    return (effectiveBitrate * durationSec / 8).toLong()
  }

  /**
   * Exports one slice of a timeline through the authoritative pipeline (used by [ChunkedExportEngine]).
   * Returns true only if a file with a valid video track was written.
   */
  suspend fun exportTimelineSegment(
    timeline: Timeline,
    config: ExportConfig,
    outputFile: File,
    startMs: Long,
    endMs: Long
  ): Boolean = withContext(Dispatchers.IO) {
    val segmentDuration = (endMs - startMs).coerceAtLeast(1000L)
    val shiftedVideo = timeline.videoClips.mapNotNull { clip ->
      val clipEnd = clip.timelineStartMs + clip.durationMs
      if (clipEnd <= startMs || clip.timelineStartMs >= endMs) null
      else {
        val newStart = (clip.timelineStartMs - startMs).coerceAtLeast(0L)
        val newEnd = (clipEnd - startMs).coerceIn(0L, segmentDuration)
        clip.copy(timelineStartMs = newStart, durationMs = maxOf(100L, newEnd - newStart))
      }
    }
    val shiftedAudio = timeline.audioClips.mapNotNull { clip ->
      val clipEnd = clip.timelineStartMs + clip.durationMs
      if (clipEnd <= startMs || clip.timelineStartMs >= endMs) null
      else {
        val newStart = (clip.timelineStartMs - startMs).coerceAtLeast(0L)
        val newEnd = (clipEnd - startMs).coerceIn(0L, segmentDuration)
        clip.copy(timelineStartMs = newStart, durationMs = maxOf(100L, newEnd - newStart))
      }
    }
    val segmentTimeline = timeline.copy(
      videoClips = shiftedVideo,
      audioClips = shiftedAudio
    )

    lastError = null
    try {
      AsyncFramePipelineEngine(context).export(segmentTimeline, config, outputFile)
      validateMp4(outputFile)
    } catch (e: CancellationException) {
      try { if (outputFile.exists()) outputFile.delete() } catch (_: Exception) {}
      throw e
    } catch (e: Throwable) {
      lastError = e.message ?: e.javaClass.simpleName
      Log.e(tag, "exportTimelineSegment failed: $lastError", e)
      false
    }
  }

  fun release() {
    isCancelled = true
  }

  fun updateSuccessFile(file: File) {
    val currentState = _exportState.value
    if (currentState is ExportState.Success) {
      _exportState.value = currentState.copy(file = file, fileSizeBytes = file.length())
    }
  }

  /**
   * Strict validation of an exported MP4: it must contain a real video track with positive dimensions and duration.
   * The file is probed with MediaMetadataRetriever and, if that cannot parse it, with MediaExtractor.
   * A file is never accepted merely because it is large enough; if it cannot be parsed it is rejected.
   */
  fun validateMp4(file: File): Boolean {
    if (!file.exists()) {
      Log.w(tag, "MP4 validation failed: Output file does not exist at ${file.absolutePath}")
      return false
    }
    val fileLength = file.length()
    if (fileLength <= 10240L) {
      Log.w(tag, "MP4 validation failed: File size too small or incomplete ($fileLength bytes, expected > 10240 bytes)")
      return false
    }

    val probe = probeWithRetriever(file) ?: probeWithExtractor(file)
    if (probe == null) {
      Log.e(tag, "MP4 validation failed: neither MediaMetadataRetriever nor MediaExtractor could parse ${file.absolutePath}")
      return false
    }
    val reason = Mp4ValidationRules.rejectionReason(probe)
    if (reason != null) {
      Log.e(tag, "MP4 validation failed for ${file.absolutePath}: $reason")
      return false
    }
    Log.i(tag, "validateMp4 verified playable MP4: $probe")
    return true
  }

  private fun probeWithRetriever(file: File): Mp4ValidationRules.Probe? {
    var fis: FileInputStream? = null
    val retriever = MediaMetadataRetriever()
    return try {
      fis = FileInputStream(file)
      retriever.setDataSource(fis.fd)
      Mp4ValidationRules.Probe(
        fileBytes = file.length(),
        hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO),
        width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),
        height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
        durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
      )
    } catch (e: Exception) {
      Log.w(tag, "MediaMetadataRetriever could not read output (${e.javaClass.simpleName}: ${e.message}); trying MediaExtractor", e)
      null
    } finally {
      try { retriever.release() } catch (ignored: Exception) {}
      try { fis?.close() } catch (ignored: Exception) {}
    }
  }

  /** Independent parse of the container: a real video track must be present. Never accepts a file just for its size. */
  private fun probeWithExtractor(file: File): Mp4ValidationRules.Probe? {
    val extractor = MediaExtractor()
    return try {
      extractor.setDataSource(file.absolutePath)
      for (i in 0 until extractor.trackCount) {
        val f = extractor.getTrackFormat(i)
        val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
        if (!mime.startsWith("video/")) continue
        val durationUs = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L
        return Mp4ValidationRules.Probe(
          fileBytes = file.length(),
          hasVideo = "yes",
          width = if (f.containsKey(MediaFormat.KEY_WIDTH)) f.getInteger(MediaFormat.KEY_WIDTH).toString() else null,
          height = if (f.containsKey(MediaFormat.KEY_HEIGHT)) f.getInteger(MediaFormat.KEY_HEIGHT).toString() else null,
          durationMs = (durationUs / 1000L).toString()
        )
      }
      Mp4ValidationRules.Probe(file.length(), hasVideo = "no", width = null, height = null, durationMs = null)
    } catch (e: Exception) {
      Log.e(tag, "MediaExtractor could not read output (${e.javaClass.simpleName}: ${e.message})", e)
      null
    } finally {
      try { extractor.release() } catch (ignored: Exception) {}
    }
  }

  fun validateMp4Safely(file: File): Boolean = validateMp4(file)

  private fun validatePlayableMp4(file: File): Boolean = validateMp4(file)

  fun getDimensionsForResolution(res: Resolution, aspect: AspectRatio): Pair<Int, Int> =
    ExportDimensionResolver.resolve(res, aspect)
}
