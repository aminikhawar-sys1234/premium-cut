package com.example.engine.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.example.domain.model.*
import com.example.engine.composition.ChromaKeyResolver
import com.example.engine.composition.CompositionLookResolver
import com.example.engine.composition.CompositionRenderGraph
import com.example.engine.composition.VideoCompositionEngine
import com.example.engine.composition.graph.SharedCompositionGraph
import com.example.engine.composition.gpu.ChromaBackgroundFrame
import com.example.engine.composition.gpu.EglCore
import com.example.engine.composition.gpu.GpuCompositionRenderer
import com.example.engine.composition.gpu.HardwareVideoTextureSource
import com.example.engine.composition.gpu.WindowSurface
import com.example.engine.controller.DecoderManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Performance metrics for the hardware video export pipeline.
 */
class AsyncFramePipelineMetrics {
  val decodedFrames = AtomicLong()
  val gpuFrames = AtomicLong()
  val encodedFrames = AtomicLong()
  val zeroCopyFrames = AtomicLong()
  val gpuToCpuCopies = AtomicLong()
  val decodeTimeNs = AtomicLong()
  val gpuRenderTimeNs = AtomicLong()
  /** Wall time spent blocked on eglSwapBuffers (i.e. waiting for the video encoder). */
  val encodeWaitTimeNs = AtomicLong()
  /** Wall time of the composited render call itself (GPU work + readbacks). */
  val composeTimeNs = AtomicLong()
  /**
   * Frames that had to come from a MediaMetadataRetriever still-frame extraction because no codec
   * could decode the clip. Those frames each cost a retriever seek + decode (~50-200 ms), so this
   * counter is the first thing to look at when an export is far slower than realtime.
   */
  val fallbackFrames = AtomicLong()
  val fallbackTimeNs = AtomicLong()

  fun reset() {
    decodedFrames.set(0)
    gpuFrames.set(0)
    encodedFrames.set(0)
    zeroCopyFrames.set(0)
    gpuToCpuCopies.set(0)
    decodeTimeNs.set(0)
    gpuRenderTimeNs.set(0)
    encodeWaitTimeNs.set(0)
    composeTimeNs.set(0)
    fallbackFrames.set(0)
    fallbackTimeNs.set(0)
  }

  fun snapshot() = mapOf(
    "decodedFrames" to decodedFrames.get(),
    "gpuFrames" to gpuFrames.get(),
    "encodedFrames" to encodedFrames.get(),
    "zeroCopyFrames" to zeroCopyFrames.get(),
    "gpuToCpuCopies" to gpuToCpuCopies.get()
  )

  /** Human readable per-stage breakdown of one attempt, for the export log. */
  fun describe(): String {
    val frames = gpuFrames.get().coerceAtLeast(1L)
    fun perFrame(ns: Long) = "%.2fms".format(ns / 1_000_000.0 / frames)
    val fallbacks = fallbackFrames.get()
    return "frames=$frames decode=${perFrame(decodeTimeNs.get())}/frame " +
      "compose=${perFrame(composeTimeNs.get())}/frame " +
      "gpuRender=${perFrame(gpuRenderTimeNs.get())}/frame " +
      "encodeWait=${perFrame(encodeWaitTimeNs.get())}/frame " +
      "fallback=$fallbacks(${perFrame(fallbackTimeNs.get())}/frame)"
  }
}

/**
 * Thrown by the export pipeline for any failure that must surface to the user as a clear error
 * (instead of a partial / audio-only / black file). [retryable] tells the retry ladder whether a different
 * encoder configuration could plausibly succeed (false for e.g. an undecodable source clip).
 */
class ExportPipelineException(
  message: String,
  cause: Throwable? = null,
  val retryable: Boolean = true
) : RuntimeException(message, cause)

/**
 * Thread-safe bounded queue for pipeline frame synchronization.
 */
class FramePacketQueue<T>(val capacity: Int) {
  private val queue = java.util.concurrent.ArrayBlockingQueue<T>(capacity)

  fun put(item: T, cancelled: AtomicBoolean): Boolean {
    while (!cancelled.get()) {
      if (queue.offer(item, 50, TimeUnit.MILLISECONDS)) return true
    }
    return false
  }

  fun take(cancelled: AtomicBoolean): T? {
    while (!cancelled.get()) {
      val item = queue.poll(50, TimeUnit.MILLISECONDS)
      if (item != null) return item
    }
    return null
  }

  fun depth(): Int = queue.size
}

/**
 * Hardware-accelerated clip decoder rendering directly to an OpenGL OES texture with software fallback.
 */
private class HardwareClipDecoder(
  private val context: Context,
  val clip: VideoClip,
  private val glHandler: Handler,
  private val decoderHandler: Handler,
  private val decoderManager: DecoderManager = DecoderManager()
) {
  private val textureSource = HardwareVideoTextureSource()

  val textureId: Int get() = textureSource.oesTextureId
  /** Raw encoded size. Display rotation is applied by the GPU compositor, not here. */
  val width: Int get() = textureSource.width
  val height: Int get() = textureSource.height
  val transformMatrix: FloatArray get() = textureSource.transformMatrix

  fun init(): Boolean {
    return textureSource.initialize(
      context = context,
      clip = clip,
      glHandler = glHandler,
      decoderHandler = decoderHandler,
      decoderManager = decoderManager
    )
  }

  /** @return true when the OES texture holds a real decoded picture for [targetUs]. */
  fun decodeFrame(targetUs: Long, cancelled: AtomicBoolean): Boolean {
    return textureSource.decodeFrame(targetUs, cancelled)
  }

  fun updateTexImageOnGl() {
    textureSource.updateTexImage()
  }

  /** Decoded track duration in microseconds (0 when unknown); used to loop chroma background videos. */
  val durationUs: Long get() = textureSource.durationUs
  val effectiveWidth: Int get() = textureSource.effectiveWidth
  val effectiveHeight: Int get() = textureSource.effectiveHeight
  val rotationDegrees: Int get() = textureSource.rotationDegrees

  fun release() {
    textureSource.release()
  }
}

/**
 * THE authoritative video export pipeline (Timeline -> frame evaluation -> MediaExtractor/MediaCodec decode
 * -> OES texture -> GPU composition incl. AR / effects / text / stickers -> encoder input Surface ->
 * MediaCodec -> MediaMuxer -> MP4).
 *
 * Guarantees:
 *  - Every output frame i is rendered into the encoder input Surface with presentation time
 *    i * 1e6 / fps (strictly monotonic, independent of decode timing).
 *  - Decoder EOS is never forwarded; the encoder gets exactly one EOS via signalEndOfInputStream() after the
 *    last frame, and the muxer is finalised only after the encoder has emitted its own EOS.
 *  - The encoder Surface / EGL objects are released only after the encoder drained (or on failure/cancel).
 *  - The MP4 is produced only when video samples were actually written; otherwise export() throws an
 *    [ExportPipelineException] with a clear reason. No partial / audio-only file is ever returned.
 *  - Encoder / setup failures are retried on a fallback ladder (HEVC -> AVC, then <=1080p/30fps) with fresh
 *    resources each time; source-media failures are not retried.
 */
class AsyncFramePipelineEngine(private val context: Context) {
  private val tag = "AsyncFramePipeline"
  private val userCancelled = AtomicBoolean(false)
  private val exportRunning = AtomicBoolean(false)
  private val cleanupPending = AtomicBoolean(false)
  @Volatile private var currentStop: AtomicBoolean? = null
  private val composition = VideoCompositionEngine(context)
  private val audioProcessor = AudioExportProcessor(context)
  private val decoderManager = DecoderManager()
  val metrics = AsyncFramePipelineMetrics()

  /** Frames the current attempt will render (for progress UIs). */
  @Volatile var plannedTotalFrames: Long = 0L
    private set

  /** Encoder configuration of the current attempt (after capability planning / fallbacks). */
  @Volatile var activePlan: EncoderPlan? = null
    private set

  fun cancel() {
    userCancelled.set(true)
    currentStop?.set(true)
  }

  private fun checkUserCancelled() {
    if (userCancelled.get()) throw CancellationException("Export cancelled")
  }

  /**
   * @param sizeOverride exact output size (even numbers are enforced); defaults to the config's resolution.
   * @param isPaused polled once per frame; the render loop idles while it returns true.
   * @return the finished, muxed MP4 (never null)
   * @throws ExportPipelineException when no valid video could be produced
   * @throws CancellationException when cancelled
   */
  suspend fun export(
    timeline: Timeline,
    config: ExportConfig,
    outputFile: File,
    sizeOverride: Pair<Int, Int>? = null,
    isPaused: () -> Boolean = { false }
  ): File = withContext(Dispatchers.IO) {
    if (!exportRunning.compareAndSet(false, true)) {
      throw ExportPipelineException("An export is already running on this engine.", retryable = false)
    }
    try {
      if (cleanupPending.get()) {
        throw ExportPipelineException("Previous GPU export cleanup is still pending; wait or restart the editor.", retryable = false)
      }
      userCancelled.set(false)
      val requestedFps = config.frameRate.fps.coerceIn(15, 120)
      val (requestedWidth, requestedHeight) = sizeOverride
        ?.let { (max(2, it.first / 2 * 2)) to (max(2, it.second / 2 * 2)) }
        ?: ExportDimensionResolver.resolve(config.resolution, timeline.aspectRatio)
      val durationMs = timeline.totalDurationMs
      if (durationMs <= 0L) {
        throw ExportPipelineException("Timeline has no duration to export.", retryable = false)
      }

      // Mix audio on its own thread so encoder/EGL setup is not blocked behind PCM decode
      // (that stall is what left the progress ring at 5% for minutes).
      var hasAudio = audioProcessor.hasActiveAudio(timeline)
      val mixResult = AtomicReference<ShortArray>(ShortArray(0))
      val mixError = AtomicReference<Throwable?>(null)
      val mixLatch = CountDownLatch(1)
      if (hasAudio) {
        Thread({
          try {
            mixResult.set(
              runBlocking {
                audioProcessor.mixTimelineAudio(timeline, durationMs) { userCancelled.get() }
              }
            )
          } catch (t: Throwable) {
            mixError.set(t)
          } finally {
            mixLatch.countDown()
          }
        }, "AH-AudioMix").apply {
          isDaemon = true
          start()
        }
      } else {
        mixLatch.countDown()
      }

      // Pick codec/size/fps/bitrate the device encoder really supports (4K degrades gracefully).
      val firstPlan = ExportEncoderPlanner.plan(config, requestedWidth, requestedHeight, requestedFps)
      val attempts = buildAttempts(firstPlan)

      checkUserCancelled()
      val audioSampleRate = 48000
      val audioChannels = 2

      var lastError: Throwable? = null
      for ((index, plan) in attempts.withIndex()) {
        checkUserCancelled()
        Log.i(tag, "Export attempt ${index + 1}/${attempts.size}: ${plan.describe()} duration=${durationMs}ms audio=$hasAudio")
        try {
          return@withContext runAttempt(
            attemptNo = index + 1,
            timeline = timeline,
            outputFile = outputFile,
            plan = plan,
            durationMs = durationMs,
            hasAudio = hasAudio,
            mixLatch = mixLatch,
            mixResult = mixResult,
            mixError = mixError,
            audioSampleRate = audioSampleRate,
            audioChannels = audioChannels,
            isPaused = isPaused
          )
        } catch (e: CancellationException) {
          runCatching { outputFile.delete() }
          throw e
        } catch (t: Throwable) {
          runCatching { outputFile.delete() }
          if (userCancelled.get()) throw CancellationException("Export cancelled")
          lastError = t
          Log.e(tag, "Export attempt ${index + 1} failed: ${t.message}", t)
          val retryable = (t as? ExportPipelineException)?.retryable ?: true
          if (!retryable) break
        }
      }
      val root = lastError
      throw if (root is ExportPipelineException && !root.retryable) root
      else ExportPipelineException("Video export failed: ${root?.message ?: "unknown error"}", root)
    } finally {
      exportRunning.set(false)
    }
  }

  /** Encoder configurations to try, most capable first. */
  private fun buildAttempts(first: EncoderPlan): List<EncoderPlan> {
    val list = mutableListOf(first)
    val avc = MediaFormat.MIMETYPE_VIDEO_AVC
    if (first.mime != avc) {
      list += first.copy(mime = avc, bitrateBps = (first.bitrateBps.toLong() * 4 / 3).coerceAtMost(120_000_000L).toInt())
    }
    val hevc = MediaFormat.MIMETYPE_VIDEO_HEVC
    if (first.mime != hevc) {
      list += first.copy(mime = hevc, bitrateBps = (first.bitrateBps.toLong() * 3 / 4).coerceAtLeast(1_000_000L).toInt())
    }
    // Larger plans get a fresh, aligned <=1080p-class fallback.
    val tooBig = max(first.width, first.height) > 1920
    if (tooBig || first.fps > 30) {
      val (w, h) = if (tooBig) ExportDimensionResolver.fit(first.width, first.height, 1920) else first.width to first.height
      val fps = min(first.fps, 30)
      val scale = (w.toDouble() * h / (first.width.toDouble() * first.height)) * (fps.toDouble() / first.fps)
      list += EncoderPlan(
        mime = avc, width = w, height = h, fps = fps,
        bitrateBps = (first.bitrateBps * scale).toInt().coerceAtLeast(2_000_000),
        requestedWidth = first.requestedWidth, requestedHeight = first.requestedHeight, requestedFps = first.requestedFps
      )
    }
    return list.distinctBy { "${it.mime}:${it.width}x${it.height}@${it.fps}" }
  }

  private fun buildVideoFormat(plan: EncoderPlan, relaxed: Boolean): MediaFormat =
    MediaFormat.createVideoFormat(plan.mime, plan.width, plan.height).apply {
      ExportColorTagging.applySdrRec709(this)
      setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
      setInteger(MediaFormat.KEY_BIT_RATE, if (relaxed) (plan.bitrateBps * 0.85f).toInt() else plan.bitrateBps)
      setInteger(MediaFormat.KEY_FRAME_RATE, plan.fps)
      setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
      if (!relaxed) {
        try { setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) } catch (_: Exception) {}
        // No B-frames: encoder output order == presentation order, so muxer timestamps stay strictly increasing.
        if (Build.VERSION.SDK_INT >= 29) {
          try { setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0) } catch (_: Exception) {}
        }
        ExportEncoderSpeedHints.applyForFastExport(this, plan.fps)
      }
    }

  private data class OpenedEncoder(
    val codec: MediaCodec,
    val inputSurface: Surface,
    val format: MediaFormat,
    val hardware: Boolean
  )

  /**
   * Configure a surface-input encoder at exactly [plan.width] x [plan.height].
   * Tries hardware then software, with and without profile/level — never a smaller resolution.
   */
  private fun openVideoEncoder(plan: EncoderPlan): OpenedEncoder {
    require(plan.width % 16 == 0 && plan.height % 16 == 0) { "Surface encoder size must be 16-aligned: ${plan.width}x${plan.height}" }
    val candidates = decoderManager.listExportEncoderCandidates(plan.mime, plan.width, plan.height, requireSurface = true)
      .ifEmpty { listOf(DecoderManager.EncoderCandidate("", plan.mime, hardware = true)) }
    var lastError: Throwable? = null
    for (candidate in candidates) {
      var codec: MediaCodec? = null
      try {
        codec = if (candidate.name.isNotBlank()) {
          MediaCodec.createByCodecName(candidate.name)
        } else {
          MediaCodec.createEncoderByType(plan.mime)
        }
        val hardware = !candidate.name.lowercase().let {
          it.contains("google") || it.contains("android") || it.contains("software") || it.startsWith("c2.android.")
        } && candidate.hardware
        for (relaxed in listOf(false, true)) {
          val format = buildVideoFormat(plan, relaxed)
          if (!relaxed) {
            ExportCodecFormat.applyCompatibleProfileLevel(format, codec, plan.width, plan.height, plan.fps)
          }
          try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            return OpenedEncoder(codec, surface, format, hardware || candidate.hardware)
          } catch (cfg: Exception) {
            lastError = cfg
            Log.e(tag, "Video configure/createInputSurface failed relaxed=$relaxed format=$format thread=${Thread.currentThread().name}", cfg)
            ExportDiagnostics.configureError(codec.name, plan.mime, plan.width, plan.height, cfg)
            try { codec.reset() } catch (reset: Exception) {
              Log.w(tag, "Encoder reset failed; skipping relaxed reuse", reset)
              throw cfg
            }
          }
        }
        try { codec.release() } catch (_: Exception) {}
      } catch (t: Throwable) {
        lastError = t
        ExportDiagnostics.configureError(candidate.name.ifBlank { plan.mime }, plan.mime, plan.width, plan.height, t)
        try { codec?.release() } catch (_: Exception) {}
      }
    }
    throw ExportPipelineException(
      "No encoder could be configured for ${plan.width}x${plan.height} ${plan.mime} (requested ${plan.requestedWidth}x${plan.requestedHeight}). ${lastError?.message ?: ""}",
      lastError
    )
  }

  /** One complete export try with its own threads, codecs, EGL context and muxer. */
  private fun runAttempt(
    attemptNo: Int,
    timeline: Timeline,
    outputFile: File,
    plan: EncoderPlan,
    durationMs: Long,
    hasAudio: Boolean,
    mixLatch: CountDownLatch,
    mixResult: AtomicReference<ShortArray>,
    mixError: AtomicReference<Throwable?>,
    audioSampleRate: Int,
    audioChannels: Int,
    isPaused: () -> Boolean
  ): File {
    metrics.reset()
    activePlan = plan
    val fps = plan.fps
    val exportWidth = plan.width
    val exportHeight = plan.height
    val totalFrames = max(1L, ceil(durationMs.toDouble() / 1000.0 * fps).toLong())
    plannedTotalFrames = totalFrames

    Log.i(tag, "Starting Hardware GPU Export: ${exportWidth}x${exportHeight} @ ${fps}fps ($durationMs ms, $totalFrames frames)")
    val attemptStartNs = System.nanoTime()

    val stop = AtomicBoolean(userCancelled.get())
    currentStop = stop
    val failure = AtomicReference<Throwable?>(null)
    val videoEos = AtomicBoolean(false)
    val audioEos = AtomicBoolean(false)
    val lastOutputNs = AtomicLong(System.nanoTime())
    val swapPending = AtomicBoolean(false)

    // The export is a frame pipeline on a loaded device (the editor UI keeps rendering progress
    // while it runs), so the two pipeline threads must not fall behind the UI thread. DISPLAY
    // priority for the GL submit thread keeps the encoder fed without stuttering the UI;
    // FOREGROUND for the decode thread.
    val glThread = HandlerThread("AH-GPU-Pipeline-$attemptNo", android.os.Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    val glHandler = Handler(glThread.looper)
    val decoderThread = HandlerThread("AH-Decoder-Pipeline-$attemptNo", android.os.Process.THREAD_PRIORITY_FOREGROUND).apply { start() }
    val decoderHandler = Handler(decoderThread.looper)

    var eglCore: EglCore? = null
    var windowSurface: WindowSurface? = null
    var gpuRenderer: GpuCompositionRenderer? = null
    var videoEncoderRef: MediaCodec? = null
    var audioEncoderRef: MediaCodec? = null
    var encoderInputSurface: Surface? = null
    var muxerCoordinator: MuxerCoordinator? = null
    var drainExecutor: ExecutorService? = null
    val renderStarted = AtomicBoolean(false)
    val renderCompleteLatch = CountDownLatch(1)

    val decoders = mutableMapOf<String, HardwareClipDecoder>()
    val imageBitmaps = mutableMapOf<String, Bitmap>()
    val imageTextures = mutableMapOf<String, Int>()

    try {
      // 1. Pre-load images (clamped to export resolution to avoid texture bloat). Chroma-key background
      //    images are included: they are drawn every frame of the clip that keys over them.
      val imageUris = LinkedHashSet<String>()
      for (clip in timeline.videoClips + timeline.overlayClips) {
        if (!clip.isVideo && clip.uri.isNotBlank()) imageUris.add(clip.uri)
      }
      imageUris.addAll(ChromaKeyResolver.imageBackgroundUris(timeline))
      for (uri in imageUris) {
        try {
          val bmp = decodeSampledBitmap(context, uri, exportWidth, exportHeight)
          if (bmp != null) imageBitmaps[uri] = bmp
        } catch (e: Exception) {
          Log.w(tag, "Failed to load image for $uri", e)
        }
      }

      // 2. Video encoder (surface input). Size is always the plan size — never silently 720p.
      val opened = openVideoEncoder(plan)
      videoEncoderRef = opened.codec
      val videoEncoder: MediaCodec = opened.codec
      val surface: Surface = opened.inputSurface
      encoderInputSurface = surface
      val configuredFormat = opened.format
      Log.i(tag, "Video Encoder initialized: ${videoEncoder.name} (hardwareAccelerated=${opened.hardware}, surface=true, ${exportWidth}x${exportHeight})")
      ExportDiagnostics.session(
        requestedWidth = plan.requestedWidth,
        requestedHeight = plan.requestedHeight,
        plan = plan,
        codecName = videoEncoder.name,
        mime = plan.mime,
        profile = ExportCodecFormat.profileOf(configuredFormat),
        level = ExportCodecFormat.levelOf(configuredFormat),
        bitrateBps = plan.bitrateBps,
        fps = fps,
        rotationHint = ExportOrientationPolicy.MUXER_ORIENTATION_HINT_DEGREES,
        eglWidth = exportWidth,
        eglHeight = exportHeight,
        flipYForEncoder = ExportOrientationPolicy.FLIP_Y_FOR_ENCODER,
        surfaceTransformLogged = true
      )

      // 3. EGL + GPU composition on the dedicated GL thread, bound to the encoder Surface
      val glInitLatch = CountDownLatch(1)
      glHandler.post {
        try {
          val core = EglCore(null, EglCore.FLAG_RECORDABLE or EglCore.FLAG_TRY_GLES3)
          eglCore = core
          val winSurface = WindowSurface(core, surface, false)
          windowSurface = winSurface
          winSurface.makeCurrent()

          val rend = GpuCompositionRenderer(context)
          gpuRenderer = rend
          rend.initGl()
          for ((uri, bmp) in imageBitmaps) {
            imageTextures[uri] = rend.uploadImageTexture(uri, bmp)
          }
        } catch (t: Throwable) {
          Log.e(tag, "EGL/GPU initialization failed size=${exportWidth}x$exportHeight thread=${Thread.currentThread().name}", t)
          failure.compareAndSet(null, t)
        } finally {
          glInitLatch.countDown()
        }
      }
      if (!glInitLatch.await(20, TimeUnit.SECONDS)) {
        throw ExportPipelineException("Timed out initializing the GPU encoder surface (EGL).")
      }
      failure.get()?.let { throw wrap("GPU initialization failed", it) }

      // Audio mix runs in parallel with encoder/EGL setup. Wait here so the 5% screen is not
      // blocked on PCM decode before the GPU pipeline is even opened.
      if (!mixLatch.await(3, TimeUnit.MINUTES)) {
        throw ExportPipelineException("Timed out mixing timeline audio.", retryable = false)
      }
      mixError.get()?.let { throw wrap("Audio mix failed", it) }
      val masterPcm = mixResult.get() ?: ShortArray(0)
      val encodeAudio = hasAudio && masterPcm.isNotEmpty()

      // 4. Audio encoder. If the timeline has audio, failing to encode it is an error, not a silent drop.
      var audioEncoder: MediaCodec? = null
      if (encodeAudio) {
        try {
          val aacFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, audioSampleRate, audioChannels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
          }
          audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also {
            audioEncoderRef = it
            it.configure(aacFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            it.start()
          }
        } catch (e: Exception) {
          ExportDiagnostics.configureError(audioEncoderRef?.name, MediaFormat.MIMETYPE_AUDIO_AAC, 0, 0, e)
          throw ExportPipelineException("The AAC audio encoder could not be started (${e.message}); the project has audio, so export was stopped instead of dropping it.", e, retryable = false)
        }
      }
      val aenc: MediaCodec? = audioEncoder

      // 5. Muxer. The GPU compositor already outputs upright pixels, so the container carries no rotation hint
      //    (a hint here would rotate the already-normalised frames a second time).
      outputFile.parentFile?.mkdirs()
      if (outputFile.exists()) outputFile.delete()
      val localMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
      val coordinator = MuxerCoordinator(localMuxer, aenc != null, videoMime = plan.mime)
      muxerCoordinator = coordinator
      coordinator.setOrientationHint(ExportOrientationPolicy.MUXER_ORIENTATION_HINT_DEGREES)

      videoEncoder.start()

      // 6. Drain thread: the ONLY consumer of encoder output (video + audio) -> muxer.
      val drainDone = CountDownLatch(1)
      // The drain thread feeds the muxer; if it is starved, eglSwapBuffers blocks the render
      // thread and the whole export slows down with it.
      val drain = Executors.newSingleThreadExecutor { r ->
        Thread({
          android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND)
          r.run()
        }, "AH-GPU-MuxDrain")
      }
      drainExecutor = drain
      drain.execute {
        try {
          val vInfo = MediaCodec.BufferInfo()
          val aInfo = MediaCodec.BufferInfo()
          while (!stop.get()) {
            if (videoEos.get() && (aenc == null || audioEos.get())) {
              Log.i(tag, "Drain: video and audio encoders both reached end of stream")
              break
            }
            var drainedSomething = false

            if (!videoEos.get()) {
              while (!videoEos.get() && !stop.get()) {
                val vIndex = videoEncoder.dequeueOutputBuffer(vInfo, if (drainedSomething) 0L else 2_000L)
                if (vIndex >= 0) {
                  drainedSomething = true
                  lastOutputNs.set(System.nanoTime())
                  val isEos = (vInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                  try {
                    // CODEC_CONFIG buffers go to the coordinator too: some encoders put the first IDR
                    // picture in the same buffer as SPS/PPS, and dropping it leaves no decodable first frame.
                    if (vInfo.size > 0) {
                      val out = videoEncoder.getOutputBuffer(vIndex)
                        ?: throw IllegalStateException("Video encoder returned a null output buffer")
                      when (coordinator.writeVideoSample(out, vInfo)) {
                        MuxerCoordinator.VideoSampleResult.WRITTEN -> metrics.encodedFrames.incrementAndGet()
                        MuxerCoordinator.VideoSampleResult.DROPPED_BEFORE_KEYFRAME -> {
                          metrics.encodedFrames.incrementAndGet()
                          requestSyncFrame(videoEncoder, "first encoder output was not a key frame")
                        }
                        else -> Unit
                      }
                    }
                  } finally {
                    videoEncoder.releaseOutputBuffer(vIndex, false)
                  }
                  if (isEos) {
                    videoEos.set(true)
                    Log.i(tag, "Video encoder signaled BUFFER_FLAG_END_OF_STREAM")
                    break
                  }
                } else if (vIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                  drainedSomething = true
                  val newFormat = videoEncoder.outputFormat
                  Log.i(tag, "Video encoder output format changed: $newFormat")
                  ExportDiagnostics.encoderOutputFormat(newFormat)
                  coordinator.setVideoFormat(newFormat)
                } else {
                  break // INFO_TRY_AGAIN_LATER (or buffers-changed): nothing more right now
                }
              }
            }

            if (aenc != null && !audioEos.get()) {
              while (!audioEos.get() && !stop.get()) {
                val aIndex = aenc.dequeueOutputBuffer(aInfo, if (drainedSomething) 0L else 2_000L)
                if (aIndex >= 0) {
                  drainedSomething = true
                  lastOutputNs.set(System.nanoTime())
                  val isEos = (aInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                  try {
                    if ((aInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && aInfo.size > 0) {
                      val out = aenc.getOutputBuffer(aIndex)
                        ?: throw IllegalStateException("Audio encoder returned a null output buffer")
                      coordinator.writeAudioSample(out, aInfo)
                    }
                  } finally {
                    aenc.releaseOutputBuffer(aIndex, false)
                  }
                  if (isEos) {
                    audioEos.set(true)
                    Log.i(tag, "Audio encoder signaled BUFFER_FLAG_END_OF_STREAM")
                    break
                  }
                } else if (aIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                  drainedSomething = true
                  val newFormat = aenc.outputFormat
                  Log.i(tag, "Audio encoder output format changed: $newFormat")
                  coordinator.setAudioFormat(newFormat)
                } else {
                  break
                }
              }
            }

            if (!drainedSomething) Thread.sleep(1)
          }
        } catch (t: Throwable) {
          Log.e(tag, "Error in drain thread", t)
          failure.compareAndSet(null, t)
          stop.set(true)
        } finally {
          drainDone.countDown()
        }
      }

      // 7. Audio feeding (PCM -> AAC encoder) with sample-exact timestamps.
      val totalAudioFrames = if (aenc != null) masterPcm.size / audioChannels else 0
      var fedAudioFrames = 0

      /** Feeds PCM up to [upToFrames]; [waitUs] is how long to wait for a free input buffer. */
      fun feedAudio(upToFrames: Int, waitUs: Long, maxBuffers: Int = Int.MAX_VALUE): Boolean {
        val enc = aenc ?: return true
        val target = min(upToFrames, totalAudioFrames)
        var buffers = 0
        while (fedAudioFrames < target && !stop.get() && buffers < maxBuffers) {
          val framesToFeed = min(1024, target - fedAudioFrames)
          val inputIndex = enc.dequeueInputBuffer(waitUs)
          if (inputIndex < 0) return false
          val inputBuffer = enc.getInputBuffer(inputIndex) ?: return false
          inputBuffer.clear()
          inputBuffer.order(ByteOrder.nativeOrder())
          val samplesToFeed = framesToFeed * audioChannels
          val startIdx = fedAudioFrames * audioChannels
          val shortView = inputBuffer.asShortBuffer()
          val available = (masterPcm.size - startIdx).coerceAtLeast(0)
          val copy = min(samplesToFeed, available)
          if (copy > 0) shortView.put(masterPcm, startIdx, copy)
          if (copy < samplesToFeed) {
            repeat(samplesToFeed - copy) { shortView.put(0) }
          }
          val audioPtsUs = (fedAudioFrames.toLong() * 1_000_000L) / audioSampleRate
          enc.queueInputBuffer(inputIndex, 0, samplesToFeed * 2, audioPtsUs, 0)
          fedAudioFrames += framesToFeed
          buffers++
        }
        return true
      }

      // Prime the audio encoder so its output format (needed to start the muxer) appears early.
      if (aenc != null && totalAudioFrames > 0) feedAudio(2048, 10_000L)

      val maxConcurrentDecoders = DecoderManager.MAX_RECOMMENDED_HARDWARE_DECODERS

      fun getOrCreateDecoder(clip: VideoClip, currentActiveIds: Set<String>): HardwareClipDecoder? {
        decoders[clip.id]?.let { return it }
        if (decoders.size >= maxConcurrentDecoders) {
          val evictCandidate = decoders.keys.firstOrNull { it !in currentActiveIds }
          if (evictCandidate != null) {
            decoders.remove(evictCandidate)?.release()
            Log.d(tag, "Evicted idle decoder for clip $evictCandidate to avoid codec exhaustion")
          }
        }
        val newDecoder = HardwareClipDecoder(context, clip, glHandler, decoderHandler, decoderManager)
        return if (newDecoder.init()) {
          decoders[clip.id] = newDecoder
          newDecoder
        } else {
          newDecoder.release()
          null
        }
      }

      val undecodableClipIds = HashSet<String>()

      val firstVideoClip = timeline.videoClips.firstOrNull { clip ->
        clip.isVideo && clip.uri.isNotBlank() &&
          clip.timelineStartMs < durationMs && (clip.timelineStartMs + clip.durationMs) > 0L
      }
      if (firstVideoClip != null) {
        getOrCreateDecoder(firstVideoClip, setOf(firstVideoClip.id))
      }

      /**
       * Decodes the source frame for [srcPosMs] into the clip's OES texture and latches it (GL thread).
       * A codec error triggers ONE retry on a software decoder; null means "no usable picture".
       */
      fun decodeClipFrame(clip: VideoClip, srcPosMs: Long, activeIds: Set<String>): HardwareClipDecoder? {
        if (clip.id in undecodableClipIds) return null
        for (tryNo in 0..1) {
          val dec = getOrCreateDecoder(clip, activeIds) ?: return null
          try {
            val t0 = System.nanoTime()
            val ok = dec.decodeFrame(srcPosMs * 1000L, stop)
            metrics.decodeTimeNs.addAndGet(System.nanoTime() - t0)
            if (!ok) return null
            dec.updateTexImageOnGl()
            metrics.decodedFrames.incrementAndGet()
            metrics.zeroCopyFrames.incrementAndGet()
            return dec
          } catch (e: IllegalStateException) {
            Log.w(tag, "Decoder error for clip ${clip.name} at ${srcPosMs}ms (try ${tryNo + 1}): ${e.message}", e)
            decoders.remove(clip.id)?.release()
            decoderManager.triggerSoftwareFallback("Runtime decoder error: ${e.message}")
          }
        }
        undecodableClipIds.add(clip.id)
        Log.w(tag, "Clip ${clip.name} could not be decoded by hardware or software codecs; using still-frame extraction")
        return null
      }

      // 8. Render loop (GL thread): every frame is drawn into the encoder Surface and its swap submits it.
      renderStarted.set(true)
      glHandler.post {
        var renderingFrame = -1L
        try {
          val renderSurface = windowSurface ?: error("Encoder surface missing")
          renderSurface.makeCurrent()
          var lastEglPtsNs = -1L
          val probeFrames = linkedSetOf(0L, totalFrames / 4, totalFrames / 2, totalFrames * 3 / 4, totalFrames - 1)
          // The back-buffer readback is a lightweight hint only. Some EGL encoder surfaces
          // do not support glReadPixels reliably, and five tiny patches cannot represent a
          // transformed/PiP frame; the decoded MP4 validator is the authoritative full-frame check.
          var probes = 0
          var blackProbes = 0
          var unavailableProbes = 0

          for (frameIndex in 0 until totalFrames) {
            renderingFrame = frameIndex
            renderSurface.makeCurrent()
            while (isPaused() && !stop.get()) Thread.sleep(40)
            if (stop.get()) break

            if (frameIndex == 0L) requestSyncFrame(videoEncoder, "first frame")

            val ptsUs = (frameIndex * 1_000_000L) / fps
            val timelinePosMs = (ptsUs / 1000L).coerceAtMost(durationMs - 1L)
            val frame = SharedCompositionGraph.evaluate(timeline, timelinePosMs, fps).composedFrame
            val activeClip = frame.activeClip

            // The same layer list the preview samples. A NONE transition still pins its decoders
            // so a following real transition does not have to reopen them.
            val layers = CompositionRenderGraph.layersOf(frame)
            val currentNeededClipIds = layers.filter { it.clip.isVideo }.map { it.clip.id }.toMutableSet()
            frame.activeTransition?.let { tr ->
              if (tr.clipBefore.isVideo) currentNeededClipIds.add(tr.clipBefore.id)
              if (tr.clipAfter.isVideo) currentNeededClipIds.add(tr.clipAfter.id)
            }

            // Chroma-key background: a video has to be decoded like any other layer, at the looped
            // source time, so the export draws the same background picture the preview draws.
            // Image / solid backgrounds are decoded and uploaded by the GPU renderer itself.
            val frameChroma = ChromaKeyResolver.settingsFor(frame.activeClip, timeline.chromaKey)
            val chromaBgClip = ChromaKeyResolver.backgroundClip(frameChroma)
            var chromaBackgroundFrame: ChromaBackgroundFrame? = null
            if (chromaBgClip != null && chromaBgClip.isVideo) {
              currentNeededClipIds.add(chromaBgClip.id)
              val loopMs = (decoders[chromaBgClip.id]?.durationUs ?: 0L) / 1000L
              val chromaSrcMs = ChromaKeyResolver.backgroundSourceMs(frameChroma, timelinePosMs, loopMs)
              val bgDec = decodeClipFrame(chromaBgClip, chromaSrcMs, currentNeededClipIds)
              if (bgDec != null) {
                chromaBackgroundFrame = ChromaBackgroundFrame(
                  textureId = bgDec.textureId,
                  isOes = true,
                  texMatrix = bgDec.transformMatrix.copyOf(),
                  width = bgDec.effectiveWidth,
                  height = bgDec.effectiveHeight,
                  rotationDegrees = bgDec.rotationDegrees
                )
              } else {
                Log.w(tag, "Chroma-key background video could not be decoded at ${chromaSrcMs}ms; keying to transparency for this frame")
              }
            }

            val decoded = HashMap<String, CompositionRenderGraph.ResolvedLayer>()
            fun rememberDecoded(clip: VideoClip, dec: HardwareClipDecoder) {
              if (dec.textureId == 0 || clip.id in decoded) return
              decoded[clip.id] = CompositionRenderGraph.ResolvedLayer(
                textureId = dec.textureId,
                isOes = true,
                texMatrix = dec.transformMatrix.copyOf(),
                width = dec.width,
                height = dec.height
              )
            }
            fun rememberFallback(clip: VideoClip, key: String, srcPosMs: Long) {
              if (clip.id in decoded) return
              val bmp = fetchFallbackBitmap(clip, srcPosMs, exportWidth, exportHeight) ?: return
              val texId = gpuRenderer?.uploadImageTexture(key, bmp) ?: 0
              bmp.recycle()
              if (texId > 0) {
                decoded[clip.id] = CompositionRenderGraph.ResolvedLayer(texId, false, null, 0, 0)
              }
            }

            for (layer in layers) {
              val clip = layer.clip
              if (!clip.isVideo || clip.uri.isBlank() || clip.id in decoded) continue
              val srcMs = layer.sourceUs / 1000L
              decodeClipFrame(clip, srcMs, currentNeededClipIds)?.let { rememberDecoded(clip, it) }
              if (clip.id in decoded) continue
              val key = when (layer.role) {
                CompositionRenderGraph.LayerRole.MAIN -> "fallback_main_${clip.id}"
                CompositionRenderGraph.LayerRole.OVERLAY -> "fallback_ov_${clip.id}"
                else -> "fallback_tr_${clip.id}"
              }
              rememberFallback(clip, key, srcMs)
              if (clip.id !in decoded && layer.role == CompositionRenderGraph.LayerRole.OVERLAY) {
                Log.w(tag, "Overlay '${clip.name}' has no decodable frame at ${srcMs}ms; it will be missing from this frame")
              }
            }

            if (activeClip != null && activeClip.isVideo && activeClip.uri.isNotBlank() && activeClip.id !in decoded) {
              throw ExportPipelineException(
                "Could not decode a video frame from '${activeClip.name}' at ${frame.clipSourcePosMs} ms " +
                  "(timeline ${timelinePosMs} ms). Export stopped instead of writing black frames.",
                retryable = false
              )
            }
            if (activeClip != null && !activeClip.isVideo && activeClip.uri.isNotBlank() && (imageTextures[activeClip.uri] ?: 0) == 0) {
              throw ExportPipelineException("Image '${activeClip.name}' could not be loaded for export.", retryable = false)
            }

            // 3. Compose (video + AR + effects + text + stickers) into the encoder Surface's back buffer.
            // Assembly is shared with the preview, so both draw the same layers from the same source times.
            val assembled = CompositionRenderGraph.assemble(
              frame = frame,
              decoded = decoded,
              imageTextureId = { uri -> imageTextures[uri] ?: 0 },
              drawTextAndStickers = true
            )
            val composeStart = System.nanoTime()
            val rend = gpuRenderer ?: throw IllegalStateException("GPU renderer is not initialised")
            val lookFallback = CompositionLookResolver.timelineFallback(timeline)
            CompositionRenderGraph.present(
              renderer = rend,
              assembled = assembled,
              viewportWidth = exportWidth,
              viewportHeight = exportHeight,
              // These two arguments are the PROJECT-LEVEL FALLBACK, not "the look of this frame".
              // GpuCompositionRenderer resolves every layer against them, per layer: the main
              // picture with `activeClip.adjustments ?: fallback`, each transition side with its own
              // clip, each PIP / overlay with `overlay.clip.adjustments ?: fallback`
              // (CompositionLookResolver). Passing the timeline values is therefore what gives the
              // main clip its own look - and it is what PreviewCompositor passes, so preview and
              // export now hand the renderer identical arguments.
              //
              // This used to pre-resolve the main clip (`frame.activeClip?.adjustments ?:
              // timeline.adjustments`). For the main layer that is idempotent, so the exported look
              // matched the panel. For EVERY OTHER layer it was wrong: an overlay or transition side
              // with no clip-local look inherited the *main clip's* grade in the export while the
              // preview fell back to the timeline's. Since the Filters panel writes clip-local looks
              // and clears Timeline.filter to NONE, exported PIPs carried a grade the preview never
              // showed.
              adjustments = lookFallback.adjustments,
              filter = lookFallback.filter,
              chromaKey = timeline.chromaKey,
              presentation = CompositionRenderGraph.FramePresentation.export(ExportOrientationPolicy.FLIP_Y_FOR_ENCODER),
              chromaBackgroundFrame = chromaBackgroundFrame
            )
            // Submit GPU work without a full GPU/CPU stall. eglSwapBuffers already publishes
            // the frame to the encoder; glFinish serialized every frame and made long exports
            // crawl after the encoder queue filled (typically around the midpoint).
            GLES20.glFlush()

            // Advisory black-frame diagnostic: sample a few points in the real back buffer. The final
            // decoded-MP4 validator is authoritative; this point sample must never reject transformed/PiP
            // content or treat an unsupported GL readback as proof that the output is black.
            if (frameIndex in probeFrames && assembled.mainTextureId > 0) {
              when (isBackBufferBlack(exportWidth, exportHeight)) {
                true -> {
                  probes++
                  blackProbes++
                }
                false -> probes++
                null -> unavailableProbes++
              }
            }

            var targetPtsNs = ptsUs * 1000L
            if (targetPtsNs <= lastEglPtsNs) targetPtsNs = lastEglPtsNs + 1000L
            lastEglPtsNs = targetPtsNs
            val win = windowSurface ?: throw IllegalStateException("Encoder window surface is not initialised")
            // Without a valid EGL timestamp the encoder stamps frames with the swap time, which breaks
            // the 0-based monotonic PTS the muxer and the verifier rely on.
            if (!win.setPresentationTime(targetPtsNs)) {
              throw ExportPipelineException(
                "eglPresentationTimeANDROID failed for frame $frameIndex (EGL error 0x${Integer.toHexString(EGL14.eglGetError())})."
              )
            }
            val swapStart = System.nanoTime()
            swapPending.set(true)
            try {
              if (!win.swapBuffers()) {
                // Capture the real EGL error instead of discarding it, so the cause (e.g. a dead encoder
                // input surface vs. buffer allocation failure) is visible in logcat and in the export error.
                val eglError = android.opengl.EGL14.eglGetError()
                Log.e(
                  tag,
                  "eglSwapBuffers failed on frame $frameIndex (${exportWidth}x$exportHeight, " +
                    "codec=${videoEncoder.name}, mime=${plan.mime}, eglError=0x${Integer.toHexString(eglError)})"
                )
                throw ExportPipelineException(
                  "Submitting frame $frameIndex to the video encoder failed (eglSwapBuffers). " +
                    "EGL error 0x${Integer.toHexString(eglError)}."
                )
              }
            } finally {
              swapPending.set(false)
            }
            metrics.encodeWaitTimeNs.addAndGet(System.nanoTime() - swapStart)
            metrics.gpuRenderTimeNs.addAndGet(System.nanoTime() - composeStart)
            metrics.gpuFrames.incrementAndGet()

            // 4. Audio, pro-rata with the video position. Cap buffers per frame so a catch-up
            // burst cannot stall the GL/encoder pipeline around the midpoint of a long export.
            if (aenc != null) {
              feedAudio((((frameIndex + 1).toDouble() * audioSampleRate) / fps).toInt(), 0L, maxBuffers = 3)
            }
          }

          if (!stop.get() && probes >= 3 && blackProbes == probes) {
            Log.w(
              tag,
              "Encoder-surface point probes were black in every readable sample ($blackProbes/$probes; " +
                "$unavailableProbes unavailable). Continuing to mux: this small back-buffer probe can miss " +
                "transformed/PiP content, so ExportValidator will inspect decoded frames before success."
            )
          } else if (unavailableProbes > 0) {
            Log.w(tag, "Encoder-surface black-frame probe unavailable for $unavailableProbes sample(s); final MP4 validation remains authoritative")
          }
        } catch (t: Throwable) {
          Log.e(tag, "Render loop failed frame=$renderingFrame size=${exportWidth}x$exportHeight codec=${videoEncoder.name} mime=${plan.mime} thread=${Thread.currentThread().name} submitted=${metrics.gpuFrames.get()}", t)
          failure.compareAndSet(null, t)
          stop.set(true)
        } finally {
          renderCompleteLatch.countDown()
        }
      }

      // Wait for the render loop; watch for an encoder that stopped consuming frames (swap would block forever).
      while (!renderCompleteLatch.await(1, TimeUnit.SECONDS)) {
        failure.get()?.let { throw wrap("Pipeline worker failed", it) }
        checkUserCancelled()
        if (stop.get()) throw CancellationException("Export stopped")
        val idleSec = (System.nanoTime() - lastOutputNs.get()) / 1_000_000_000L
        if (idleSec > 60 && (swapPending.get() || metrics.gpuFrames.get() > metrics.encodedFrames.get())) {
          throw ExportPipelineException("The video encoder stopped producing output for ${idleSec}s (frames submitted=${metrics.gpuFrames.get()}, encoded=${metrics.encodedFrames.get()}).")
        }
      }
      failure.get()?.let { throw wrap("Rendering failed", it) }
      checkUserCancelled()
      if (stop.get()) throw CancellationException("Export cancelled")

      // 9. End of stream. All frames are already queued on the Surface; signal exactly once.
      Log.i(tag, "Signaling end of stream to video encoder input surface (${metrics.gpuFrames.get()} frames submitted)")
      try {
        videoEncoder.signalEndOfInputStream()
      } catch (e: Exception) {
        Log.e(tag, "signalEndOfInputStream failed codec=${videoEncoder.name} submitted=${metrics.gpuFrames.get()}", e)
        throw ExportPipelineException("Video encoder rejected end-of-stream", e)
      }

      if (aenc != null) {
        // Flush the remaining PCM (deadline based, never silently truncated), then EOS at the exact end PTS.
        val flushDeadline = System.nanoTime() + 30_000_000_000L
        while (fedAudioFrames < totalAudioFrames && !stop.get()) {
          if (!feedAudio(totalAudioFrames, 10_000L) && System.nanoTime() > flushDeadline) {
            throw ExportPipelineException("The audio encoder stopped accepting input (fed $fedAudioFrames of $totalAudioFrames frames).")
          }
        }
        val eosDeadline = System.nanoTime() + 30_000_000_000L
        var audioEosSent = false
        while (!audioEosSent && !stop.get()) {
          val inputIndex = aenc.dequeueInputBuffer(10_000L)
          if (inputIndex >= 0) {
            val audioPtsUs = (fedAudioFrames.toLong() * 1_000_000L) / audioSampleRate
            aenc.queueInputBuffer(inputIndex, 0, 0, audioPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            audioEosSent = true
            Log.i(tag, "Audio encoder EOS queued at audioPtsUs=$audioPtsUs")
          } else if (System.nanoTime() > eosDeadline) {
            throw ExportPipelineException("The audio encoder never accepted the end-of-stream marker.")
          }
        }
      }

      // 10. Wait until the encoders have emitted their own EOS and the drain thread consumed everything.
      while (!drainDone.await(1, TimeUnit.SECONDS)) {
        if (stop.get()) break
        val idleSec = (System.nanoTime() - lastOutputNs.get()) / 1_000_000_000L
        if (idleSec > 30) {
          throw ExportPipelineException("The encoder did not finish after end of stream (video EOS=${videoEos.get()}, audio EOS=${audioEos.get()}, idle ${idleSec}s).")
        }
      }
      failure.get()?.let { throw wrap("Encoding failed", it) }
      checkUserCancelled()
      if (stop.get()) throw CancellationException("Export cancelled")
      if (!videoEos.get() || (aenc != null && !audioEos.get())) {
        throw ExportPipelineException("Encoder finished without end-of-stream (video=${videoEos.get()}, audio=${audioEos.get()}).")
      }

      // 11. Finalise the MP4 (moov) and verify what actually reached the file.
      val finalized = try {
        coordinator.stopAndRelease()
      } catch (e: Exception) {
        throw ExportPipelineException("MediaMuxer could not finalise the MP4 (${e.message})", e)
      }
      val written = coordinator.videoSamplesWritten
      val submitted = metrics.gpuFrames.get()
      if (!finalized) {
        throw ExportPipelineException("MediaMuxer was not started or could not finalise the MP4 (video samples=$written, audio samples=${coordinator.audioSamplesWritten}).")
      }
      if (written <= 0L) {
        throw ExportPipelineException("No video frames reached the output file ($submitted frames were rendered). Export stopped instead of producing an audio-only MP4.")
      }
      if (written * 2 < submitted) {
        throw ExportPipelineException("The video encoder dropped most frames (rendered=$submitted, written=$written).")
      }
      if (aenc != null && coordinator.audioSamplesWritten <= 0L) {
        throw ExportPipelineException("No audio samples reached the output file although the project has audio.")
      }
      if (!outputFile.exists() || outputFile.length() <= 0L) {
        throw ExportPipelineException("The exported file is missing or empty.")
      }
      if (!coordinator.hasWrittenKeyFrame) {
        throw ExportPipelineException("The video encoder never produced a key frame; the exported file could not be decoded.")
      }
      if (coordinator.droppedLeadingVideoSamples > 0L) {
        Log.w(tag, "Dropped ${coordinator.droppedLeadingVideoSamples} non-key frame(s) the encoder emitted before its first key frame")
      }
      if (coordinator.timestampCorrections > 0L) {
        Log.w(tag, "Muxer corrected ${coordinator.timestampCorrections} non-monotonic timestamp(s)")
      }
      val wallMs = (System.nanoTime() - attemptStartNs) / 1_000_000
      val realtimeSpeed = if (wallMs > 0) (durationMs.toDouble() / wallMs) else 0.0
      Log.i(
        tag,
        "Hardware Export Finished: ${outputFile.absolutePath} (${outputFile.length()} bytes, " +
          "video samples=$written/$submitted, audio samples=${coordinator.audioSamplesWritten})"
      )
      Log.i(
        tag,
        "Export performance: ${wallMs}ms for ${durationMs}ms of video " +
          "(${"%.2f".format(realtimeSpeed)}x realtime) | ${metrics.describe()}"
      )
      ExportDiagnostics.finished(outputFile.absolutePath, exportWidth, exportHeight, durationMs, outputFile.length())
      return outputFile
    } finally {
      stop.set(true)
      cleanupPending.set(true)
      currentStop = null
      // Do not free a codec/muxer under its consumer or an input surface under eglSwapBuffers.
      drainExecutor?.shutdown()
      val drainStopped = drainExecutor?.awaitTermination(5, TimeUnit.SECONDS) ?: true
      var renderStopped = !renderStarted.get() || renderCompleteLatch.await(5, TimeUnit.SECONDS)
      if (!renderStopped && drainStopped) {
        // A vendor encoder may be stuck holding the BufferQueue in swapBuffers. Stop (not
        // release) it only after the output consumer exits, to unblock the producer on abort.
        Log.w(tag, "Aborting stalled render: stopping video encoder to unblock its input surface")
        runCatching { videoEncoderRef?.stop() }.onFailure { Log.w(tag, "Abort codec stop failed", it) }
        renderStopped = renderCompleteLatch.await(5, TimeUnit.SECONDS)
      }

      val cleanLatch = CountDownLatch(1)
      glHandler.post {
        try {
          // This task is serialized behind init/render, even if those exceeded our deadline.
          // In the pathological driver-hang case retain resources rather than race their release.
          drainExecutor?.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
          fun cleanup(label: String, block: () -> Unit) {
            runCatching(block).onFailure { Log.w(tag, "Export cleanup failed: $label", it) }
          }
          cleanup("makeCurrent") { windowSurface?.makeCurrent() }
          decoders.values.forEach { decoder -> cleanup("decoder") { decoder.release() } }
          decoders.clear()
          cleanup("renderer") { gpuRenderer?.release() }
          cleanup("unbind EGL") { eglCore?.makeNothingCurrent() }
          cleanup("EGL surface") { windowSurface?.release() }
          cleanup("EGL context") { eglCore?.release() }
          fallbackRetrievers.values.forEach { retriever -> cleanup("retriever") { retriever.release() } }
          fallbackRetrievers.clear()
          imageBitmaps.values.forEach { bitmap -> cleanup("bitmap") { bitmap.recycle() } }
          imageBitmaps.clear()
          cleanup("encoder input surface") { encoderInputSurface?.release() }
          cleanup("video stop") { videoEncoderRef?.stop() }
          cleanup("video release") { videoEncoderRef?.release() }
          cleanup("audio stop") { audioEncoderRef?.stop() }
          cleanup("audio release") { audioEncoderRef?.release() }
          cleanup("muxer") { if (muxerCoordinator?.isStopped == false) muxerCoordinator?.stopAndRelease() }
        } finally {
          cleanupPending.set(false)
          cleanLatch.countDown()
          glThread.quitSafely()
          decoderThread.quitSafely()
        }
      }
      if (!cleanLatch.await(10, TimeUnit.SECONDS) || !renderStopped || !drainStopped) {
        // No fallback attempt may reuse shared decoder/retriever state while cleanup is pending.
        Log.e(tag, "Export teardown timed out; cleanup deferred on render thread, retries disabled")
        throw ExportPipelineException("GPU/codec teardown timed out; export aborted safely. Check Logcat for the stalled worker.", retryable = false)
      }
    }
  }

  private fun requestSyncFrame(encoder: MediaCodec, reason: String) {
    try {
      encoder.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
    } catch (e: Exception) {
      Log.w(tag, "Sync frame request failed ($reason): ${e.message}")
    }
  }

  private fun wrap(prefix: String, t: Throwable): Throwable =
    if (t is ExportPipelineException || t is CancellationException) t
    else ExportPipelineException("$prefix: ${t.message ?: t.javaClass.simpleName}", t)

  /**
   * A cheap, advisory probe of five 8x8 back-buffer regions. Returns null when the driver reports
   * a GL error: glReadPixels can be unsupported/unreliable on some encoder window surfaces, and an
   * untouched (zero-filled) ByteBuffer must not be mistaken for proof of a black frame.
   */
  private fun isBackBufferBlack(width: Int, height: Int): Boolean? {
    if (width <= 0 || height <= 0) return null
    return try {
      val pendingErrors = consumeGlErrors()
      if (pendingErrors.isNotEmpty()) {
        Log.w(tag, "Skipping black-frame probe after pending GL error(s): ${formatGlErrors(pendingErrors)}")
        return null
      }

      val size = 8
      val midX = ((width - size) / 2).coerceAtLeast(0)
      val midY = ((height - size) / 2).coerceAtLeast(0)
      val qX = (width / 4 - size / 2).coerceAtLeast(0)
      val qY = (height / 4 - size / 2).coerceAtLeast(0)
      val rX = (width * 3 / 4 - size / 2).coerceAtLeast(0)
      val rY = (height * 3 / 4 - size / 2).coerceAtLeast(0)
      val origins = listOf(
        midX to midY,
        qX to qY,
        rX to qY,
        qX to rY,
        rX to rY
      )
      var allBlack = true
      for ((x, y) in origins) {
        val blockBlack = isBlockBlack(x, y, size) ?: return null
        if (!blockBlack) allBlack = false
      }
      allBlack
    } catch (e: Exception) {
      Log.w(tag, "Black-frame probe unavailable: ${e.message}")
      null
    }
  }

  private fun isBlockBlack(x: Int, y: Int, size: Int): Boolean? {
    val px = ByteBuffer.allocateDirect(size * size * 4)
    GLES20.glReadPixels(x, y, size, size, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px)
    val errors = consumeGlErrors()
    if (errors.isNotEmpty()) {
      Log.w(tag, "glReadPixels failed during black-frame probe: ${formatGlErrors(errors)}")
      return null
    }
    var i = 0
    while (i < size * size) {
      val base = i * 4
      val r = px.get(base).toInt() and 0xFF
      val g = px.get(base + 1).toInt() and 0xFF
      val b = px.get(base + 2).toInt() and 0xFF
      if (r > 6 || g > 6 || b > 6) return false
      i++
    }
    return true
  }

  private fun consumeGlErrors(): List<Int> {
    val errors = ArrayList<Int>(2)
    repeat(16) {
      val error = GLES20.glGetError()
      if (error == GLES20.GL_NO_ERROR) return errors
      errors += error
    }
    return errors
  }

  private fun formatGlErrors(errors: List<Int>): String =
    errors.joinToString(prefix = "[", postfix = "]") { "0x${Integer.toHexString(it)}" }

  private val fallbackRetrievers = mutableMapOf<String, MediaMetadataRetriever>()

  private fun fetchFallbackBitmap(clip: VideoClip, sourcePosMs: Long, maxW: Int, maxH: Int): Bitmap? {
    val startedNs = System.nanoTime()
    if (metrics.fallbackFrames.incrementAndGet() == 1L) {
      Log.w(
        tag,
        "Clip ${clip.name} has no working hardware/software decoder: falling back to " +
          "MediaMetadataRetriever still-frame extraction for this clip (much slower per frame)."
      )
    }
    try {
      return extractFallbackBitmap(clip, sourcePosMs, maxW, maxH)
    } finally {
      metrics.fallbackTimeNs.addAndGet(System.nanoTime() - startedNs)
    }
  }

  private fun extractFallbackBitmap(clip: VideoClip, sourcePosMs: Long, maxW: Int, maxH: Int): Bitmap? {
    return try {
      val retriever = fallbackRetrievers.getOrPut(clip.uri) {
        MediaMetadataRetriever().apply {
          val uri = try { Uri.parse(clip.uri) } catch (_: Exception) { null }
          if (uri != null && (uri.scheme == "content" || uri.scheme == "file")) {
            setDataSource(context, uri)
          } else {
            setDataSource(clip.uri)
          }
        }
      }
      val sourceUs = sourcePosMs * 1000L
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        retriever.getScaledFrameAtTime(sourceUs, MediaMetadataRetriever.OPTION_CLOSEST, maxW, maxH)
          ?: retriever.getFrameAtTime(sourceUs, MediaMetadataRetriever.OPTION_CLOSEST)
      } else {
        retriever.getFrameAtTime(sourceUs, MediaMetadataRetriever.OPTION_CLOSEST)
      }
    } catch (e: Exception) {
      Log.w(tag, "Failed to retrieve fallback frame for ${clip.uri}", e)
      null
    }
  }

  private fun decodeSampledBitmap(context: Context, uriString: String, maxW: Int, maxH: Int): Bitmap? {
    return try {
      val uri = Uri.parse(uriString)
      val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      if (uri.scheme == "content") {
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, boundsOpts) }
      } else {
        val path = if (uri.scheme == "file") uri.path ?: uriString else uriString
        BitmapFactory.decodeFile(path, boundsOpts)
      }
      var sampleSize = 1
      val srcW = boundsOpts.outWidth
      val srcH = boundsOpts.outHeight
      if (srcW > 0 && srcH > 0 && maxW > 0 && maxH > 0) {
        while ((srcW / (sampleSize * 2)) >= maxW && (srcH / (sampleSize * 2)) >= maxH) {
          sampleSize *= 2
        }
      }
      val decodeOpts = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
      }
      val rawBmp = if (uri.scheme == "content") {
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
      } else {
        val path = if (uri.scheme == "file") uri.path ?: uriString else uriString
        BitmapFactory.decodeFile(path, decodeOpts)
      } ?: return null

      // Scale to strictly fit within timeline export resolution if still larger
      if (rawBmp.width > maxW || rawBmp.height > maxH) {
        val scale = minOf(maxW.toFloat() / rawBmp.width, maxH.toFloat() / rawBmp.height)
        val targetW = (rawBmp.width * scale).toInt().coerceAtLeast(1)
        val targetH = (rawBmp.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(rawBmp, targetW, targetH, true)
        if (scaled != rawBmp) rawBmp.recycle()
        scaled
      } else {
        rawBmp
      }
    } catch (e: Exception) {
      Log.w(tag, "Failed to decode sampled bitmap for $uriString: ${e.message}")
      null
    }
  }
}
