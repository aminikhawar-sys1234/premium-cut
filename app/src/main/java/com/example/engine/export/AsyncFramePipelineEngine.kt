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
import java.util.Locale
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
 * [failure] names the rule that was violated when the failure came from inspecting the output, so the
 * caller can apply a policy (a black output retries with the fallback compositor; nothing else does).
 */
class ExportPipelineException(
  message: String,
  cause: Throwable? = null,
  val retryable: Boolean = true,
  val failure: ExportFailure? = null
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
  private val decoderManager: DecoderManager = DecoderManager(),
  /** False puts this clip on the software ladder step (see CodecAllocationPolicy). */
  private val preferHardware: Boolean = true
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
      decoderManager = decoderManager,
      preferHardware = preferHardware
    )
  }

  /** True while this decoder runs on a hardware codec (false on the software ladder step). */
  val isHardware: Boolean get() = textureSource.isHardwareAccelerated

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

  /** Why the last decode attempt failed (null when it produced a picture); reported to the user. */
  val lastError: String? get() = textureSource.lastError

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
  /** True once the current attempt has produced a validated output file (reset at attempt start). */
  @Volatile private var attemptSucceeded = false
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

  /**
   * Instrumentation-test seam only: caps how many hardware decoders this export may keep open, so the
   * codec-pressure ladder (release what the frame does not draw, retry, software fallback) runs on a
   * device whose codecs are nowhere near exhausted. Production never sets it - the real limit is
   * learned from the device itself (see `learnedHardwareLimit` in [export]).
   */
  var decoderBudgetForTests: Int? = null

  /**
   * Instrumentation-test seam only: budget overrides for [ExportStallWatchdog], so a test can prove
   * that a stall is detected and reported without waiting for the production deadlines (which are
   * generous on purpose). Production never sets it.
   */
  var stallBudgetsForTests: Map<ExportPhase, ExportStallWatchdog.Budget>? = null

  /**
   * Instrumentation-test seam only: parks this long inside a named phase, so the stall watchdog can
   * be exercised end to end (`AsyncFramePipelineEngine` accepts `phaseStallForTests` like
   * `<phase>:<ms>`).
   */
  var phaseStallForTests: String? = null

  // ---------------------------------------------------------------------------------------------
  // Observable progress: which stage of the export is running right now, and when it last moved.
  //
  // The panel used to have only "encoded N / M frames", which reads 0 for *every* stage before the
  // first frame - that is why a stalled export was indistinguishable from a slow one. These fields
  // are the pipeline's side of the fix: the progress job in ProfessionalExportEngine reads them, the
  // UI shows the phase label, and the stall watchdog judges them.
  // ---------------------------------------------------------------------------------------------

  /** Stage the export is in right now. */
  @Volatile var currentPhase: ExportPhase = ExportPhase.PREPARING
    private set

  /** Progress inside [currentPhase] (0..1); 0 for stages that have nothing to report. */
  @Volatile var phaseFraction: Float = 0f
    private set

  /** `System.currentTimeMillis()` when the current phase was entered. */
  @Volatile var phaseStartedMs: Long = 0L
    private set

  /** `System.currentTimeMillis()` when the current attempt started. */
  @Volatile var attemptStartedMs: Long = 0L
    private set

  /** `System.currentTimeMillis()` of the last observable movement (phase change or phase progress). */
  @Volatile var lastHeartbeatMs: Long = 0L
    private set

  /** `System.currentTimeMillis()` when the last frame reached the encoder; 0 while none has. */
  @Volatile var lastFrameSubmittedMs: Long = 0L
    private set

  /** `System.currentTimeMillis()` when the encoder last produced a sample; 0 while none has. */
  @Volatile var lastEncodedSampleMs: Long = 0L
    private set

  /**
   * Set when the stall watchdog abandoned the attempt. The export thread reads it and fails with
   * this exact message (rather than spinning on a worker that will never return).
   */
  @Volatile var stallFailure: ExportPipelineException? = null
    private set

  /** Enters [phase] and resets its fraction. Also the phase-change heartbeat for the watchdog. */
  private fun enterPhase(phase: ExportPhase, fraction: Float = 0f) {
    currentPhase = phase
    phaseFraction = fraction.coerceIn(0f, 1f)
    val now = System.currentTimeMillis()
    phaseStartedMs = now
    lastHeartbeatMs = now
    ExportDiagnostics.phase(phase, attemptElapsedMs = if (attemptStartedMs > 0L) now - attemptStartedMs else 0L)
  }

  /** Reports internal progress of the current phase (the PCM mix calls this once per chunk). */
  private fun reportPhaseFraction(fraction: Float) {
    phaseFraction = fraction.coerceIn(0f, 1f)
    lastHeartbeatMs = System.currentTimeMillis()
  }

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
      // Set once the attempt has produced a validated file. Lets the finally block tell a
      // "teardown was slow" apart from a real failure without throwing out of the finally block
      // (a throw from finally replaces the attempt's own result or exception).
      attemptSucceeded = false
      userCancelled.set(false)
      stallFailure = null
      lastFrameSubmittedMs = 0L
      lastEncodedSampleMs = 0L
      enterPhase(ExportPhase.DEVICE_CHECK)
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
      //
      // The mix reports its own progress once per chunk (plus the phase heartbeats below), which is
      // what makes the panel move through a long project's mix instead of freezing on the pre-roll
      // floor, and what lets the stall watchdog tell "still mixing" from "stuck mixing".
      var hasAudio = audioProcessor.hasActiveAudio(timeline)
      val mixResult = AtomicReference<ShortArray>(ShortArray(0))
      val mixError = AtomicReference<Throwable?>(null)
      val mixLatch = CountDownLatch(1)
      // Attempt-failure cancel, separate from userCancelled: the mix thread is a daemon that only
      // observed user cancellation, so after a non-cancel attempt failure it kept decoding/mixing
      // the whole timeline in the background (minutes of CPU/IO and the ~115MB PCM of a 10-minute
      // project held until it finished). It is set on EVERY exit path of export() below.
      val mixCancelled = AtomicBoolean(false)
      if (hasAudio) {
        Thread({
          try {
            mixResult.set(
              runBlocking {
                audioProcessor.mixTimelineAudio(
                  timeline = timeline,
                  totalDurationMs = durationMs,
                  isCancelled = { userCancelled.get() || mixCancelled.get() },
                  onProgress = { fraction -> reportPhaseFraction(fraction) }
                )
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
      // Mutable: a black output on the LAST entry of the ladder still gets its dedicated
      // Kotlin-compositor retry (one synthetic extra attempt) instead of silently ending the loop.
      val attempts = buildAttempts(firstPlan).toMutableList()

      checkUserCancelled()
      val audioSampleRate = 48000
      val audioChannels = 2

      var lastError: Throwable? = null
      var blackOutputAttempts = 0
      for ((index, plan) in attempts.withIndex()) {
        checkUserCancelled()
        val attemptNo = index + 1
        // A black output is retried with the Kotlin compositor: the native C++ one is the component
        // that can hand back an empty texture while every codec statistic still looks correct.
        val useNativeCompositor = ExportBlackFrameGuard.useNativeCompositor(attemptNo)
        Log.i(
          tag,
          "Export attempt $attemptNo/${attempts.size}: ${plan.describe()} duration=${durationMs}ms " +
            "audio=$hasAudio compositor=${if (useNativeCompositor) "native" else "kotlin (fallback)"}"
        )
        try {
          return@withContext runAttempt(
            attemptNo = attemptNo,
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
            useNativeCompositor = useNativeCompositor,
            isPaused = isPaused
          )
        } catch (e: CancellationException) {
          runCatching { outputFile.delete() }
          throw e
        } catch (t: Throwable) {
          runCatching { outputFile.delete() }
          if (userCancelled.get()) throw CancellationException("Export cancelled")
          lastError = t
          Log.e(tag, "Export attempt $attemptNo failed: ${t.message}", t)
          if ((t as? ExportPipelineException)?.failure == ExportFailure.ALL_FRAMES_BLACK) {
            blackOutputAttempts++
            if (!ExportBlackFrameGuard.mayRetryBlackOutput(blackOutputAttempts)) {
              Log.e(tag, "Every attempt produced black frames ($blackOutputAttempts attempts); giving up")
              throw t
            }
            Log.w(tag, "Black output on attempt $attemptNo; retrying with the fallback compositor")
            // The dedicated Kotlin-compositor retry must actually exist: if the black output
            // landed on the last ladder entry, `continue` would end the loop with the fallback
            // never run and the real black-frame reason wrapped away as a generic failure.
            // Clone the current plan once so the next attempt number flips the compositor.
            if (index == attempts.size - 1) {
              attempts += plan
              Log.i(tag, "Black output on the last encoder plan; adding one Kotlin-compositor retry")
            }
            continue
          }
          val retryable = (t as? ExportPipelineException)?.retryable ?: true
          if (!retryable) break
        }
      }
      val root = lastError
      throw if (root is ExportPipelineException && !root.retryable) root
      else ExportPipelineException("Video export failed: ${root?.message ?: "unknown error"}", root)
    } finally {
      // Stop the mix thread on every exit path (success, failure, cancellation). On success the
      // mix already finished (its latch was awaited), so this is a no-op there.
      mixCancelled.set(true)
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

  /**
   * Runs one pre-roll step that has no deadline of its own - opening the video encoder, creating the
   * muxer, starting the audio encoder - with a hard wall-clock budget.
   *
   * These are the calls that made the reported export *silent*: `MediaCodec.configure()`,
   * `createInputSurface()`, `MediaCodec.start()` and `MediaMuxer(...)` are vendor/platform calls with
   * no timeout of their own, and a device that hangs inside one of them (a driver lock held by
   * another encoder session, a codec service that never answers) hangs the export thread forever.
   * Nothing then fails, so nothing is reported - the panel simply keeps the last progress value,
   * which for every pre-roll stage is the 5 % floor.
   *
   * The step therefore runs on its own thread and the export thread waits with a deadline. On
   * timeout the attempt is abandoned with a message naming the step, tag and codec - the user gets
   * an error and can retry, instead of a frozen ring. The abandoned worker is deliberately left
   * alone (interrupting a MediaCodec call is not supported): it keeps one codec instance until the
   * process exits, which is why a timeout is never retried ([ExportPipelineException.retryable] is
   * false).
   */
  private fun <T> runBoundedStage(
    label: String,
    timeoutMs: Long,
    stalled: () -> ExportPipelineException?,
    block: () -> T
  ): T {
    val executor = Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, "AH-Export-$label").apply { isDaemon = true }
    }
    val future = executor.submit(java.util.concurrent.Callable { block() })
    try {
      val deadlineAt = System.currentTimeMillis() + timeoutMs
      while (true) {
        try {
          return future.get(200, TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
          stalled()?.let { throw it }
          checkUserCancelled()
          if (System.currentTimeMillis() > deadlineAt) {
            future.cancel(true)
            throw ExportPipelineException(
              "The export is stuck in '$label': the device did not answer within " +
                "${timeoutMs / 1000}s. The project is untouched, so it can be retried after " +
                "restarting the editor.",
              retryable = false
            )
          }
        }
      }
    } catch (e: java.util.concurrent.ExecutionException) {
      throw (e.cause ?: e)
    } finally {
      executor.shutdown()
    }
  }

  /**
   * Watches one attempt for the failure the previous release could not see: an export that never
   * reaches its first frame (or that stops moving after one). See [ExportStallWatchdog] for the rule
   * and for why the old `swapPending || gpuFrames > encodedFrames` condition could not fire while
   * zero frames existed.
   *
   * The watcher runs on its own daemon thread so it keeps judging the attempt while the export
   * thread is blocked inside a vendor call, and it leaves the finding in [stallFailure] for that
   * thread to throw. When the block is a stalled `eglSwapBuffers` - the encoder no longer consuming
   * - it also stops the encoder, which is the documented way to unblock the producer.
   */
  private inner class AttemptWatchdog(
    private val stop: AtomicBoolean,
    private val isPaused: () -> Boolean,
    private val swapPending: AtomicBoolean,
    private val videoEncoder: () -> MediaCodec?,
    private val done: AtomicBoolean
  ) {
    private val thread = Thread({ run() }, "AH-ExportWatchdog").apply { isDaemon = true }

    fun start() = also { thread.start() }

    fun join(timeoutMs: Long) {
      done.set(true)
      runCatching { thread.join(timeoutMs) }
    }

    private fun run() {
      while (!done.get()) {
        try {
          Thread.sleep(1_000L)
        } catch (_: InterruptedException) {
          return
        }
        if (done.get()) return
        val verdict = ExportStallWatchdog.verdict(heartbeat(isPaused, swapPending), stallBudgetsForTests)
        if (verdict is ExportStallWatchdog.Verdict.Stall) {
          val failure = ExportPipelineException(verdict.message, retryable = verdict.retryable)
          if (stallFailure == null) {
            stallFailure = failure
            Log.e(
              tag,
              "EXPORT_STALL kind=${verdict.kind} phase=${verdict.phase} waitedMs=${verdict.waitedMs} " +
                "framesSubmitted=${metrics.gpuFrames.get()} framesEncoded=${metrics.encodedFrames.get()} " +
                "swapPending=${swapPending.get()}: ${verdict.message}"
            )
            ExportDiagnostics.stall(verdict)
          }
          // Unblock what can be unblocked: a stopped encoder releases the input-surface queue, so a
          // render thread parked in eglSwapBuffers returns with an error instead of never.
          runCatching { videoEncoder()?.stop() }.onFailure {
            Log.w(tag, "Stall abort: encoder stop failed", it)
          }
          stop.set(true)
          return
        }
      }
    }
  }

  /** Watchdog budget of [phase], with the instrumentation-test override when one is set. */
  private fun phaseBudget(phase: ExportPhase): ExportStallWatchdog.Budget =
    stallBudgetsForTests?.get(phase) ?: ExportStallWatchdog.budgetFor(phase)

  /**
   * Instrumentation-test seam: `"<phase>:<ms>"` parks the export inside [phase] so the stall
   * watchdog can be exercised on a device without waiting for a real vendor hang.
   */
  private fun applyPhaseStall(spec: String) {
    val (name, millis) = spec.split(':', limit = 2).let { it.first() to (it.getOrNull(1)?.toLongOrNull() ?: 0L) }
    val phase = ExportPhase.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return
    if (millis <= 0L) return
    Log.w(tag, "Test seam: parking the export in $phase for ${millis}ms")
    enterPhase(phase)
    Thread.sleep(millis)
  }

  /** Current heartbeat of the attempt, as [ExportStallWatchdog] expects it. */
  private fun heartbeat(
    isPaused: () -> Boolean,
    swapPending: AtomicBoolean
  ): ExportStallWatchdog.Heartbeat {
    val now = System.currentTimeMillis()
    return ExportStallWatchdog.Heartbeat(
      phase = currentPhase,
      attemptElapsedMs = if (attemptStartedMs > 0L) now - attemptStartedMs else 0L,
      phaseElapsedMs = if (phaseStartedMs > 0L) now - phaseStartedMs else 0L,
      phaseProgressAgoMs = now - lastHeartbeatMs,
      hasPhaseProgress = ExportStallWatchdog.budgetFor(currentPhase).noProgressMs > 0L,
      framesSubmitted = metrics.gpuFrames.get(),
      framesEncoded = metrics.encodedFrames.get(),
      sinceLastFrameSubmittedMs = if (lastFrameSubmittedMs > 0L) now - lastFrameSubmittedMs else -1L,
      sinceLastEncodedMs = if (lastEncodedSampleMs > 0L) now - lastEncodedSampleMs else -1L,
      swapPending = swapPending.get(),
      paused = isPaused()
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
    /** False on a retry after a black output: composite with Kotlin instead of the native C++ engine. */
    useNativeCompositor: Boolean = true,
    isPaused: () -> Boolean = { false }
  ): File {
    metrics.reset()
    activePlan = plan
    fallbackBudget = ExportFallbackBudget()
    stallFailure = null
    attemptStartedMs = System.currentTimeMillis()
    lastFrameSubmittedMs = 0L
    lastEncodedSampleMs = 0L
    enterPhase(ExportPhase.LOADING_ASSETS)
    // Wall clock from here to the first encoded frame: the UI shows the preparation state for that
    // whole stretch, so its length is worth a log line (see the "audio-mix" / "preroll" stages).
    val attemptStartMs = System.currentTimeMillis()
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
    var watchdog: AttemptWatchdog? = null
    val renderStarted = AtomicBoolean(false)
    val renderCompleteLatch = CountDownLatch(1)

    val decoders = mutableMapOf<String, HardwareClipDecoder>()
    val imageBitmaps = mutableMapOf<String, Bitmap>()
    val imageTextures = mutableMapOf<String, Int>()
    // Owned by THIS attempt (see StillFrameFallback): engine-level state was released by this
    // attempt's async cleanup while the next attempt was already using it.
    val stillFrames = StillFrameFallback()

    try {
      // 1. Pre-load images (clamped to export resolution to avoid texture bloat). Chroma-key background
      //    images are included: they are drawn every frame of the clip that keys over them.
      val imageUris = LinkedHashSet<String>()
      for (clip in timeline.videoClips + timeline.overlayClips) {
        if (!clip.isVideo && clip.uri.isNotBlank()) imageUris.add(clip.uri)
      }
      imageUris.addAll(ChromaKeyResolver.imageBackgroundUris(timeline))
      var imagesLoaded = 0
      val imageExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AH-ImageLoad").apply { isDaemon = true }
      }
      for (uri in imageUris) {
        try {
          // Bounded: `openInputStream`/`decodeStream` of a cloud-backed `content://` photo can block
          // for minutes with no timeout (the same hazard as the still-frame retriever below), and this
          // loop runs before the first frame - the exact window in which the reported export showed
          // "0 / 18484 frames" and nothing else. A timed-out image is treated like the failed load the
          // loop already tolerated (skip + log), never like a reason to hang.
          val future = imageExecutor.submit(
            java.util.concurrent.Callable { decodeSampledBitmap(context, uri, exportWidth, exportHeight) }
          )
          val bmp = try {
            future.get(IMAGE_LOAD_DEADLINE_MS, TimeUnit.MILLISECONDS)
          } catch (_: java.util.concurrent.TimeoutException) {
            future.cancel(true)
            Log.e(
              tag,
              "Loading image $uri exceeded ${IMAGE_LOAD_DEADLINE_MS}ms; that clip keeps its " +
                "placeholder background instead of holding up the whole export."
            )
            null
          }
          if (bmp != null) imageBitmaps[uri] = bmp
        } catch (e: Exception) {
          Log.w(tag, "Failed to load image for $uri", e)
        }
        imagesLoaded++
        reportPhaseFraction(imagesLoaded.toFloat() / imageUris.size.coerceAtLeast(1))
      }
      imageExecutor.shutdown()

      // 2. Video encoder (surface input). Size is always the plan size — never silently 720p.
      enterPhase(ExportPhase.OPENING_ENCODER)
      val opened = runBoundedStage(
        label = "opening the video encoder (${plan.width}x${plan.height} ${plan.mime})",
        timeoutMs = phaseBudget(ExportPhase.OPENING_ENCODER).hardMs,
        stalled = { stallFailure }
      ) { openVideoEncoder(plan) }
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
      enterPhase(ExportPhase.INITIALISING_GPU)
      val glInitLatch = CountDownLatch(1)
      glHandler.post {
        try {
          val core = EglCore(null, EglCore.FLAG_RECORDABLE or EglCore.FLAG_TRY_GLES3)
          eglCore = core
          val winSurface = WindowSurface(core, surface, false)
          windowSurface = winSurface
          winSurface.makeCurrent()

          val rend = GpuCompositionRenderer(context)
          // A retry after a black output drops both GPU paths that can hand back an empty picture:
          // the native compositor (unusable offscreen target) and the ES3 transition engine (a blank
          // pass replaces the whole main picture). The legacy paths always draw the real frames.
          rend.nativeCompositorEnabled = useNativeCompositor
          rend.shaderTransitionsEnabled = useNativeCompositor
          gpuRenderer = rend
          rend.initGl()
          if (!useNativeCompositor) {
            Log.w(tag, "Attempt $attemptNo composites with the Kotlin renderer and legacy transitions (GPU fast paths disabled)")
          }
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
      val glWaitStartMs = System.currentTimeMillis()
      while (!glInitLatch.await(1, TimeUnit.SECONDS)) {
        stallFailure?.let { throw it }
        checkUserCancelled()
        if (System.currentTimeMillis() - glWaitStartMs > phaseBudget(ExportPhase.INITIALISING_GPU).hardMs) {
          throw ExportPipelineException(
            "Timed out initializing the GPU encoder surface (EGL) after " +
              "${System.currentTimeMillis() - glWaitStartMs}ms."
          )
        }
      }
      failure.get()?.let { throw wrap("GPU initialization failed", it) }

      // Audio mix runs in parallel with encoder/EGL setup. Wait here so the 5% screen is not
      // blocked on PCM decode before the GPU pipeline is even opened.
      //
      // The wait is stall-aware now: the watchdogs below judge the mix phase by its own per-chunk
      // progress, so a mix that stops moving (a hung decoder, a device thrashing) is reported with
      // the phase name instead of holding the export thread on a latch forever.
      enterPhase(ExportPhase.MIXING_AUDIO)
      val mixWaitStartMs = System.currentTimeMillis()
      while (!mixLatch.await(1, TimeUnit.SECONDS)) {
        stallFailure?.let { throw it }
        checkUserCancelled()
        val waitedMs = System.currentTimeMillis() - mixWaitStartMs
        if (waitedMs > phaseBudget(ExportPhase.MIXING_AUDIO).hardMs) {
          throw ExportPipelineException(
            "Timed out mixing timeline audio after ${ExportStallWatchdog.seconds(waitedMs)}.",
            retryable = false
          )
        }
        // The AttemptWatchdog is not running yet (it starts with RENDERING), so the documented
        // "no progress for 60s" rule of this phase is enforced here against the mix's own
        // heartbeats (one reportPhaseFraction per mixed chunk). Without it a mix wedged in a
        // non-decode path (DSP/pitch/concat loops) held the export for the full 30-minute hard
        // wall with a frozen "Mixing audio" ring.
        val mixSilentMs = System.currentTimeMillis() - lastHeartbeatMs
        if (mixSilentMs > phaseBudget(ExportPhase.MIXING_AUDIO).noProgressMs) {
          throw ExportPipelineException(
            "Audio mixing stopped making progress for ${ExportStallWatchdog.seconds(mixSilentMs)}.",
            retryable = false
          )
        }
      }
      mixError.get()?.let { throw wrap("Audio mix failed", it) }
      val masterPcm = mixResult.get() ?: ShortArray(0)
      val encodeAudio = hasAudio && masterPcm.isNotEmpty()
      // How long a long project's PCM mix really takes on the user's device: the render loop cannot
      // start before this, so it is the phase a "0% for two minutes" report is about.
      ExportDiagnostics.stage(
        "audio-mix",
        System.currentTimeMillis() - attemptStartMs,
        "active=$hasAudio samples=${masterPcm.size} channels=$audioChannels rate=$audioSampleRate"
      )

      // 4. Audio encoder. If the timeline has audio, failing to encode it is an error, not a silent drop.
      var audioEncoder: MediaCodec? = null
      if (encodeAudio) {
        enterPhase(ExportPhase.OPENING_AUDIO_ENCODER)
        try {
          val aacFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, audioSampleRate, audioChannels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
          }
          audioEncoder = runBoundedStage(
            label = "starting the AAC audio encoder",
            timeoutMs = phaseBudget(ExportPhase.OPENING_AUDIO_ENCODER).hardMs,
            stalled = { stallFailure }
          ) {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also {
              audioEncoderRef = it
              it.configure(aacFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
              it.start()
            }
          }
        } catch (e: Exception) {
          ExportDiagnostics.configureError(audioEncoderRef?.name, MediaFormat.MIMETYPE_AUDIO_AAC, 0, 0, e)
          throw ExportPipelineException("The AAC audio encoder could not be started (${e.message}); the project has audio, so export was stopped instead of dropping it.", e, retryable = false)
        }
      }
      val aenc: MediaCodec? = audioEncoder

      // 5. Muxer. The GPU compositor already outputs upright pixels, so the container carries no rotation hint
      //    (a hint here would rotate the already-normalised frames a second time).
      enterPhase(ExportPhase.OPENING_MUXER)
      outputFile.parentFile?.mkdirs()
      if (outputFile.exists()) outputFile.delete()
      val localMuxer = runBoundedStage(
        label = "creating the MP4 muxer",
        timeoutMs = phaseBudget(ExportPhase.OPENING_MUXER).hardMs,
        stalled = { stallFailure }
      ) { MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) }
      val coordinator = MuxerCoordinator(localMuxer, aenc != null, videoMime = plan.mime)
      muxerCoordinator = coordinator
      coordinator.setOrientationHint(ExportOrientationPolicy.MUXER_ORIENTATION_HINT_DEGREES)

      runBoundedStage(
        label = "starting the video encoder",
        timeoutMs = phaseBudget(ExportPhase.OPENING_MUXER).hardMs,
        stalled = { stallFailure }
      ) { videoEncoder.start() }

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
                  lastEncodedSampleMs = System.currentTimeMillis()
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
                  lastEncodedSampleMs = System.currentTimeMillis()
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

      /**
       * Why the last decode of a clip produced no picture. Written by the decoder pre-open (this
       * thread) and by the render loop (GL thread), read by the render loop; carried into the export
       * exception, so "Could not decode a video frame" names the codec-level reason instead of
       * leaving the user with a bare failure.
       */
      val decodeFailures = java.util.concurrent.ConcurrentHashMap<String, String>()

      /**
       * Clips whose *hardware* decoder failed while decoding (a runtime codec error, not a refusal to
       * open). Their next attempt goes straight to the software ladder step; without this the retry
       * in [decodeClipFrame] would reopen the very codec that just failed.
       */
      val softwareOnlyClips = HashSet<String>()

      /**
       * How many open decoders the export tolerates before it starts releasing the ones this frame
       * does not need. It is a soft threshold: the hard limit is whatever the device actually gives,
       * which is discovered below and remembered in [learnedHardwareLimit].
       */
      val maxConcurrentDecodersDefault = DecoderManager.MAX_RECOMMENDED_HARDWARE_DECODERS
      /** Pause between a refused decoder request and the retry: the platform must destroy the released instances. */
      val decoderRetryPauseMs = 80L
      val maxConcurrentDecoders = decoderBudgetForTests ?: maxConcurrentDecodersDefault

      /**
       * What the device has actually allowed so far, or null while nothing has been refused.
       *
       * Seeded from [decoderBudgetForTests] (instrumentation tests only) and otherwise learned from a
       * real refusal: the number of hardware decoders that were open when the device said "not
       * enough resource" *is* that device's practical limit under this export's load. Once it is
       * known, a clip that would need one instance more goes straight to the software ladder step
       * instead of repeating a request the device has just refused.
       */
      var learnedHardwareLimit: Int? = decoderBudgetForTests

      /** How many hardware decoders this clip may be opened with, or null while nothing is known. */
      fun hardwareLimit(): Int? = CodecAllocationPolicy.hardwareLimit(null, learnedHardwareLimit)

      /**
       * Releases every open decoder no layer of this frame draws, returns how many were freed.
       *
       * [pinned] decoders are kept warm on purpose but are not part of the picture: the "before"
       * side of a NONE transition is the case that matters, where holding its decoder is an
       * optimisation. They are released last, and only when the device needs the instance for a
       * picture the frame really draws - never the other way round.
       */
      fun evictIdleDecoders(needed: Set<String>, pinned: Set<String>): Int {
        val idle = DecoderBudget.evictions(decoders.keys, needed, pinned)
        for (id in idle) {
          decoders.remove(id)?.release()
          Log.d(tag, "Evicted idle decoder for clip $id to stay inside the device's codec instances")
        }
        return idle.size
      }

      /** One ladder attempt: the opened decoder, or why the attempt failed. */
      fun attemptDecoder(clip: VideoClip, preferHardware: Boolean): Pair<HardwareClipDecoder?, String?> {
        val candidate = HardwareClipDecoder(
          context = context,
          clip = clip,
          glHandler = glHandler,
          decoderHandler = decoderHandler,
          decoderManager = decoderManager,
          preferHardware = preferHardware
        )
        if (candidate.init()) return candidate to null
        val failure = candidate.lastError
          ?: "the decoder for '${clip.name}' could not be opened (${clip.uri})"
        candidate.release()
        return null to failure
      }

      /**
       * Opens the decoder of [clip] through the resource ladder. Never returns a decoder that is not
       * fully started, and never trades a layer away to get one:
       *
       *  1. hardware codec (or the software one right away when the device's instances are used up),
       *  2. release what the frame does not draw, pause so the platform can destroy those instances,
       *     and try the same codec again - a refusal caused by a momentary peak ends here,
       *  3. the software decoder for the same MIME type: same pictures, decoded on the CPU, so a
       *     project with more simultaneous layers than the device has hardware codec instances still
       *     exports instead of failing.
       *
       * A clip whose file genuinely cannot be decoded walks the same ladder, fails on every step and
       * reports every reason - it never reports "resource" for a corrupt file.
       */
      fun getOrCreateDecoder(clip: VideoClip, needed: Set<String>, pinned: Set<String>): HardwareClipDecoder? {
        decoders[clip.id]?.let { return it }
        if (decoders.size >= maxConcurrentDecoders) evictIdleDecoders(needed, pinned)

        val limit = hardwareLimit()
        val hardwareOpen = decoders.values.count { it.isHardware }
        val startOnSoftware = clip.id in softwareOnlyClips ||
          CodecAllocationPolicy.needsSoftwareFallback(hardwareOpen, limit)
        val trail = mutableListOf<String>()

        var attempt = attemptDecoder(clip, preferHardware = !startOnSoftware)
        if (attempt.first == null && !startOnSoftware) {
          attempt.second?.let { trail.add(it) }
          val freed = evictIdleDecoders(needed, pinned)
          if (freed > 0) {
            Log.w(
              tag,
              "Decoder for ${clip.name} was refused (${trail.last()}); released $freed decoder(s) the " +
                "frame does not draw and retrying"
            )
            Thread.sleep(decoderRetryPauseMs)
            attempt = attemptDecoder(clip, preferHardware = true)
            if (attempt.first == null) {
              attempt.second?.let { trail.add("after releasing $freed decoder(s): $it") }
            }
          }
          if (attempt.first == null && CodecAllocationPolicy.isResourceRefusal(trail.joinToString(" "))) {
            // The device has named its limit: the instances that were already open.
            learnedHardwareLimit = CodecAllocationPolicy.hardwareLimit(learnedHardwareLimit, hardwareOpen)
            ExportDiagnostics.decoderLadder(
              clip.name,
              "refused",
              "hardwareCodecsOpen=$hardwareOpen, freed=$freed, limit=${learnedHardwareLimit ?: 0}, ${trail.last()}"
            )
          }
        }

        if (attempt.first == null && !startOnSoftware) {
          // Software ladder step. This is the bounded resource strategy for "more simultaneous video
          // layers than the device has hardware codec instances": the layer keeps its picture and
          // nothing is silently dropped or graded down.
          attempt = attemptDecoder(clip, preferHardware = false)
          if (attempt.first != null) {
            ExportDiagnostics.decoderLadder(
              clip.name,
              "software",
              "hardwareCodecsOpen=$hardwareOpen, limit=${hardwareLimit() ?: 0}, ${trail.joinToString("; ")}"
            )
          } else {
            attempt.second?.let { trail.add("software fallback: $it") }
          }
        }

        if (attempt.first == null && startOnSoftware) {
          // This clip was opened on the software step because an earlier clip exhausted the hardware
          // instances - but a few devices have no software decoder for the codec at all, and a refusal
          // is not proof about *this* clip's codec. One bounded hardware attempt is the last resort
          // before the export reports that it cannot decode the clip.
          attempt.second?.let { trail.add(it) }
          attempt = attemptDecoder(clip, preferHardware = true)
          if (attempt.first == null) attempt.second?.let { trail.add("hardware retry: $it") }
        }

        val opened = attempt.first
        if (opened == null) {
          val reason = trail.joinToString("; ")
            .ifEmpty { "the decoder for '${clip.name}' could not be opened (${clip.uri})" }
          decodeFailures[clip.id] = CodecAllocationPolicy.hintFor(reason)?.let { "$reason ($it)" } ?: reason
          return null
        }
        decoders[clip.id] = opened
        decodeFailures.remove(clip.id)
        return opened
      }

      val undecodableClipIds = HashSet<String>()

      val firstVideoClip = timeline.videoClips.firstOrNull { clip ->
        clip.isVideo && clip.uri.isNotBlank() &&
          clip.timelineStartMs < durationMs && (clip.timelineStartMs + clip.durationMs) > 0L
      }
      if (firstVideoClip != null) {
        // Bounded: this pre-open runs setDataSource / metadata extraction / codec configure+start
        // with no watchdog behind it yet and no deadline of its own - the exact unbounded vendor
        // call the pre-roll bounds exist for. A hung open (cloud-backed URI, a driver wedge) used
        // to park the export in "Preparing the MP4 muxer" forever, before frame 0.
        runBoundedStage(
          label = "opening the first clip decoder ('${firstVideoClip.name.ifBlank { "clip" }}')",
          timeoutMs = phaseBudget(ExportPhase.OPENING_MUXER).hardMs,
          stalled = { stallFailure }
        ) {
          getOrCreateDecoder(firstVideoClip, setOf(firstVideoClip.id), emptySet())
        }
      }

      /**
       * Decodes the source frame for [srcPosMs] into the clip's OES texture and latches it (GL thread).
       * A codec error triggers ONE retry on a software decoder; null means "no usable picture".
       */
      fun decodeClipFrame(
        clip: VideoClip,
        srcPosMs: Long,
        activeIds: Set<String>,
        pinnedIds: Set<String> = emptySet()
      ): HardwareClipDecoder? {
        if (clip.id in undecodableClipIds) return null
        for (tryNo in 0..1) {
          val dec = getOrCreateDecoder(clip, activeIds, pinnedIds) ?: return null
          try {
            val t0 = System.nanoTime()
            val ok = dec.decodeFrame(srcPosMs * 1000L, stop)
            metrics.decodeTimeNs.addAndGet(System.nanoTime() - t0)
            if (!ok) {
              decodeFailures[clip.id] = dec.lastError
                ?: "the decoder for '${clip.name}' returned no picture for ${srcPosMs}ms"
              return null
            }
            dec.updateTexImageOnGl()
            metrics.decodedFrames.incrementAndGet()
            metrics.zeroCopyFrames.incrementAndGet()
            return dec
          } catch (e: IllegalStateException) {
            Log.w(tag, "Decoder error for clip ${clip.name} at ${srcPosMs}ms (try ${tryNo + 1}): ${e.message}", e)
            decodeFailures[clip.id] = "try ${tryNo + 1}: ${e.message}" +
              (dec.lastError?.let { " (${it})" } ?: "")
            decoders.remove(clip.id)?.release()
            if (dec.isHardware) {
              // The retry must not reopen the codec that just failed: this clip takes the software
              // ladder step from now on (see CodecAllocationPolicy).
              softwareOnlyClips.add(clip.id)
              ExportDiagnostics.decoderLadder(clip.name, "runtime-error", "${e.javaClass.simpleName}: ${e.message}")
            }
            decoderManager.triggerSoftwareFallback("Runtime decoder error: ${e.message}")
          }
        }
        undecodableClipIds.add(clip.id)
        Log.w(tag, "Clip ${clip.name} could not be decoded by hardware or software codecs; using still-frame extraction")
        return null
      }

      // 8. Warm the tracked poses this project will ask for while rendering. A clip with face
      //    reshape / AR filter / body reshape is asked for a pose once per exported frame *with
      //    blocking = true*, i.e. a MediaMetadataRetriever frame extraction plus an ML Kit inference
      //    on the thread that has to keep the encoder fed, once per frame - because nothing warmed
      //    the tracker first (FaceTrackingEngine.prewarm existed but no export path called it).
      //    This runs the render loop's own lookups, before the encoder is in the pipeline, bounded by
      //    a wall-clock budget and a sample cap. Samples that cannot be warmed stay cache misses and
      //    the render loop behaves exactly as before, so the picture never changes - only when the
      //    work happens.
      TrackingHealth.reset()
      enterPhase(ExportPhase.WARMING_TRACKERS)
      val prewarmStartMs = System.currentTimeMillis()
      // Bounded like every other pre-roll vendor call: warm()'s own 10s budget is only checked
      // BETWEEN samples, and one blocking facesAt()/poseAt() (a retriever frame extraction plus
      // an ML Kit inference) cannot be preempted. A tracker wedged inside one call used to park
      // the export in "Warming tracked effects" for minutes with no verdict; the phase's 120s
      // budget is enforced by this bound. The abandoned-worker policy matches runBoundedStage.
      runBoundedStage(
        label = "warming the tracked effects",
        timeoutMs = phaseBudget(ExportPhase.WARMING_TRACKERS).hardMs,
        stalled = { stallFailure }
      ) {
        runCatching { ExportTrackingPrewarm.warm(timeline, fps) }
          .onFailure { Log.w(tag, "Tracking prewarm failed; the render loop will track per frame", it) }
      }
      ExportDiagnostics.stage(
        "tracking-prewarm",
        System.currentTimeMillis() - prewarmStartMs,
        "trackedClips=${ExportTrackingPrewarm.trackedClips(timeline).size}"
      )
      // Everything before frame 0: encoder + EGL setup, the PCM mix, this prewarm. The UI shows the
      // preparation state for this whole time, so its length belongs in the device log.
      ExportDiagnostics.stage(
        "preroll",
        System.currentTimeMillis() - attemptStartMs,
        "frames=$totalFrames decoderBudget=${maxConcurrentDecoders}"
      )

      // Render loop (GL thread): every frame is drawn into the encoder Surface and its swap submits it.
      enterPhase(ExportPhase.RENDERING)
      renderStarted.set(true)
      watchdog = AttemptWatchdog(
        stop = stop,
        isPaused = isPaused,
        swapPending = swapPending,
        videoEncoder = { videoEncoderRef },
        done = AtomicBoolean(false)
      ).start()
      phaseStallForTests?.let { spec -> runCatching { applyPhaseStall(spec) } }
      glHandler.post {
        var renderingFrame = -1L
        try {
          val renderSurface = windowSurface ?: error("Encoder surface missing")
          renderSurface.makeCurrent()
          var lastEglPtsNs = -1L
          val probeFrames = ExportBlackFrameGuard.probeFrames(totalFrames)
          // Cheap black-frame probes of the real back buffer. A probe whose glReadPixels readback is
          // unavailable proves nothing and is not counted; the decoded-MP4 check after muxing is the
          // authoritative full-frame verdict (see ExportValidator.checkDecodedFrames).
          var probes = 0
          var blackProbes = 0
          var unavailableProbes = 0
          var lastProbeFrame = -1L
          var emptyFrames = 0L
          var composedFrames = 0L

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

            // The same layer list the preview samples.
            //
            // `needed` is what this frame *draws*: a decoder in it may never be released, because
            // releasing one would drop a layer the user added. `pinned` is what the export keeps warm
            // on purpose - the "before" side of a NONE transition, which draws nothing (see
            // CompositionRenderGraph.layersOf) but is held so a following real transition does not
            // have to reopen it. Those two sets are what the codec budget is spent on, and the
            // difference matters on a device whose codec instances are limited: an old export
            // treated the pinned decoder as needed, so a clip boundary that needed one more instance
            // than the device had failed with errorCode=1100 although a whole decoder could be freed.
            val layers = CompositionRenderGraph.layersOf(frame)
            val neededClipIds = layers.filter { it.clip.isVideo }.map { it.clip.id }.toMutableSet()
            val pinnedClipIds = HashSet<String>()
            frame.activeTransition?.let { tr ->
              val drawnByThisFrame = tr.type != TransitionType.NONE
              if (tr.clipBefore.isVideo) {
                if (drawnByThisFrame) neededClipIds.add(tr.clipBefore.id) else pinnedClipIds.add(tr.clipBefore.id)
              }
              if (tr.clipAfter.isVideo) {
                if (drawnByThisFrame) neededClipIds.add(tr.clipAfter.id) else pinnedClipIds.add(tr.clipAfter.id)
              }
            }
            pinnedClipIds.removeAll(neededClipIds)

            // Chroma-key background: a video has to be decoded like any other layer, at the looped
            // source time, so the export draws the same background picture the preview draws.
            // Image / solid backgrounds are decoded and uploaded by the GPU renderer itself.
            val frameChroma = ChromaKeyResolver.settingsFor(frame.activeClip, timeline.chromaKey)
            val chromaBgClip = ChromaKeyResolver.backgroundClip(frameChroma)
            var chromaBackgroundFrame: ChromaBackgroundFrame? = null
            if (chromaBgClip != null && chromaBgClip.isVideo) {
              neededClipIds.add(chromaBgClip.id)
              val loopMs = (decoders[chromaBgClip.id]?.durationUs ?: 0L) / 1000L
              val chromaSrcMs = ChromaKeyResolver.backgroundSourceMs(frameChroma, timelinePosMs, loopMs)
              val bgDec = decodeClipFrame(chromaBgClip, chromaSrcMs, neededClipIds, pinnedClipIds)
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
              val bmp = fetchFallbackBitmap(clip, srcPosMs, exportWidth, exportHeight, stillFrames) ?: return
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
              decodeClipFrame(clip, srcMs, neededClipIds, pinnedClipIds)?.let { rememberDecoded(clip, it) }
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
              val reason = decodeFailures[activeClip.id]
              throw ExportPipelineException(
                "Could not decode a video frame from '${activeClip.name}' at ${frame.clipSourcePosMs} ms " +
                  "(timeline ${timelinePosMs} ms)" +
                  (reason?.let { "; decoder reported: $it" } ?: "") +
                  ". Export stopped instead of writing black frames.",
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
            val composed = CompositionRenderGraph.present(
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
            if (composed.failure != null && frameIndex < 8L) {
              Log.w(tag, "Frame $frameIndex composition: ${composed.describe()}")
            }
            if (composed.drewNothing) emptyFrames++ else composedFrames++
            // Submit GPU work without a full GPU/CPU stall. eglSwapBuffers already publishes
            // the frame to the encoder; glFinish serialized every frame and made long exports
            // crawl after the encoder queue filled (typically around the midpoint).
            GLES20.glFlush()

            // Black-frame probe on the real back buffer. Frames that legitimately carry no picture
            // (a timeline gap) are not probed, and an unavailable readback proves nothing - only
            // readable probes count, so a driver that refuses glReadPixels cannot fail an export.
            if (frameIndex in probeFrames && !composed.drewNothing) {
              val probeBlack = isBackBufferBlack(exportWidth, exportHeight)
              if (probeBlack == null) {
                unavailableProbes++
              } else {
                probes++
                if (probeBlack) blackProbes++
                lastProbeFrame = frameIndex
              }
              if (ExportBlackFrameGuard.shouldAbortAttempt(blackProbes, probes, lastProbeFrame, totalFrames)) {
                throw ExportPipelineException(
                  "Every sampled frame of the export came out black ($blackProbes/$probes readable probes " +
                    "up to frame $lastProbeFrame of $totalFrames; ${composed.describe()}). Export stopped " +
                    "instead of muxing a black video.",
                  failure = ExportFailure.ALL_FRAMES_BLACK
                )
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
            // Heartbeat for the stall watchdog and the progress panel: a frame really did reach the
            // encoder just now.
            lastFrameSubmittedMs = System.currentTimeMillis()

            // 4. Audio, pro-rata with the video position. Cap buffers per frame so a catch-up
            // burst cannot stall the GL/encoder pipeline around the midpoint of a long export.
            if (aenc != null) {
              feedAudio((((frameIndex + 1).toDouble() * audioSampleRate) / fps).toInt(), 0L, maxBuffers = 3)
            }
          }

          if (!stop.get()) {
            Log.i(
              tag,
              "Composition summary: frames=$totalFrames composed=$composedFrames empty=$emptyFrames " +
                "probes=$probes black=$blackProbes unavailable=$unavailableProbes " +
                "compositor=${if (gpuRenderer?.nativeCompositorEnabled == true) "native" else "kotlin"}"
            )
            if (composedFrames == 0L && emptyFrames > 0L) {
              // Nothing at all was drawable for the whole clip. Muxing it would produce a valid,
              // decodable, perfectly timed black video - exactly what the validator rejects later.
              throw ExportPipelineException(
                "No frame of the timeline could be drawn ($emptyFrames of $totalFrames frames had no picture). " +
                  "Export stopped instead of muxing a black video.",
                failure = ExportFailure.ALL_FRAMES_BLACK
              )
            }
            if (unavailableProbes > 0) {
              Log.w(tag, "Encoder-surface black-frame probe unavailable for $unavailableProbes sample(s); final MP4 validation remains authoritative")
            }
            TrackingHealth.summary()?.let { tracked ->
              Log.w(
                tag,
                "Tracked effects could not be drawn for every frame of the export: $tracked. " +
                  "Those frames carry the layer without its tracked deformation."
              )
            }
          }
        } catch (t: Throwable) {
          Log.e(tag, "Render loop failed frame=$renderingFrame size=${exportWidth}x$exportHeight codec=${videoEncoder.name} mime=${plan.mime} thread=${Thread.currentThread().name} submitted=${metrics.gpuFrames.get()}", t)
          failure.compareAndSet(null, t)
          stop.set(true)
        } finally {
          renderCompleteLatch.countDown()
        }
      }

      // Wait for the render loop.
      //
      // The decision to keep waiting lives in the watchdog ([AttemptWatchdog] /
      // [ExportStallWatchdog]): it judges the attempt every second whether or not a single frame has
      // been submitted yet. The inline condition this replaces
      // (`idleSec > 60 && (swapPending || framesSubmitted > framesEncoded)`) could not fire at all
      // while zero frames existed, which is exactly the state the reported export was stuck in - the
      // loop then spun forever and the panel kept displaying the last value it had, 5 %.
      while (!renderCompleteLatch.await(1, TimeUnit.SECONDS)) {
        failure.get()?.let { throw wrap("Pipeline worker failed", it) }
        checkUserCancelled()
        stallFailure?.let { throw it }
        if (stop.get()) throw CancellationException("Export stopped")
      }
      failure.get()?.let { throw wrap("Rendering failed", it) }
      // The watchdog's verdict is the real reason whenever it fired: without this, the abort (which
      // sets `stop`) would be reported as a user cancellation and the device log would lose the
      // only line that says what was stuck.
      stallFailure?.let { throw it }
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
      //     Own phase: the panel says so (this is the "Finalizing..."-forever row of the report) and
      //     the watchdog judges it by its own budget instead of the frame clock.
      enterPhase(ExportPhase.FINALISING)
      while (!drainDone.await(1, TimeUnit.SECONDS)) {
        stallFailure?.let { throw it }
        if (stop.get()) break
        val idleSec = (System.nanoTime() - lastOutputNs.get()) / 1_000_000_000L
        if (idleSec > 30) {
          throw ExportPipelineException("The encoder did not finish after end of stream (video EOS=${videoEos.get()}, audio EOS=${audioEos.get()}, idle ${idleSec}s).")
        }
      }
      stallFailure?.let { throw it }
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
        // A handful of +1us nudges are harmless boundary corrections, but a stream worth of them
        // means the encoder emitted B-frames (decode order) on an API level that cannot opt out
        // (KEY_MAX_B_FRAMES requires API 29). Muxing that as-is writes pictures in decode order
        // with rewritten timestamps - visible temporal artifacts. Failing the attempt lets the
        // ladder try a different encoder instead of shipping a reordered stream.
        val correctionLimit = (totalFrames / 10).coerceAtLeast(100L)
        if (coordinator.timestampCorrections > correctionLimit) {
          throw ExportPipelineException(
            "The video encoder produced ${coordinator.timestampCorrections} out-of-order timestamps " +
              "(frame reordering this export path cannot represent). Retrying with a different encoder.",
            retryable = true
          )
        }
      }
      // Decode a few frames of the finished file. The in-loop back-buffer probe can be unavailable
      // (some encoder surfaces refuse glReadPixels), and a black output is invisible to every
      // container statistic above - this is what lets the attempt ladder retry it.
      //
      // Own phase + bounded: checkDecodedFrames runs MediaMetadataRetriever/extractor calls with
      // no timeout of their own (the same vendor-call hazard the pre-roll stages are bounded
      // against). Unbounded, a hung verify kept the attempt thread blocked forever - the watchdog
      // verdict could not unblock it (only encoder.stop() can) and was never re-checked, so
      // runAttempt never returned, the finally never ran and the engine stayed "running" until
      // process death. The bounded-stage timeout fails the attempt with a named step instead.
      enterPhase(ExportPhase.VERIFYING)
      val frameCheck = runBoundedStage(
        label = "verifying the finished file",
        timeoutMs = phaseBudget(ExportPhase.VERIFYING).hardMs,
        stalled = { stallFailure }
      ) { ExportValidator.checkDecodedFrames(outputFile) }
      // The watchdog may have fired while the verify ran; its verdict is the real reason and
      // must not be discarded by the success path below.
      stallFailure?.let { throw it }
      Log.i(tag, "Decoded-frame check: ${frameCheck.detail}")
      if (frameCheck.failure == ExportFailure.ALL_FRAMES_BLACK) {
        throw ExportPipelineException(
          "Every decoded frame of the exported video is black (${frameCheck.detail}).",
          failure = ExportFailure.ALL_FRAMES_BLACK
        )
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
          "(${String.format(Locale.US, "%.2f", realtimeSpeed)}x realtime) | ${metrics.describe()}"
      )
      ExportDiagnostics.finished(outputFile.absolutePath, exportWidth, exportHeight, durationMs, outputFile.length())
      attemptSucceeded = true
      return outputFile
    } finally {
      stop.set(true)
      cleanupPending.set(true)
      currentStop = null
      watchdog?.join(2_000L)
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
          cleanup("still-frame worker") { stillFrames.executor?.shutdownNow() }
          stillFrames.executor = null
          stillFrames.retrievers.values.forEach { retriever -> cleanup("retriever") { retriever.release() } }
          stillFrames.retrievers.clear()
          imageBitmaps.values.forEach { bitmap -> cleanup("bitmap") { bitmap.recycle() } }
          imageBitmaps.clear()
          // Canonical release order: stop the codec, release it, THEN retire its input surface.
          // The previous order abandoned the surface while the codec was still in started state;
          // vendor codecs log surface-abandoned errors (or throw at stop()) for exactly that.
          cleanup("video stop") { videoEncoderRef?.stop() }
          cleanup("video release") { videoEncoderRef?.release() }
          cleanup("encoder input surface") { encoderInputSurface?.release() }
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
        Log.e(
          tag,
          "Export teardown timed out; cleanup deferred on render thread, retries disabled " +
            "(renderStopped=$renderStopped drainStopped=$drainStopped phase=$currentPhase)"
        )
        // Never throw from finally: it would (a) discard a fully rendered, validated file when
        // cleanup was merely slow, or (b) mask the attempt's real failure with this generic
        // message. cleanupPending stays true, so the NEXT export is blocked until the deferred
        // GL-thread cleanup actually completes - which is the safe behaviour.
        if (attemptSucceeded) {
          Log.w(tag, "Keeping the finished export despite the deferred cleanup (cleanupPending=true)")
        }
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

  private fun wrap(prefix: String, t: Throwable): Throwable {
    if (t is ExportPipelineException || t is CancellationException) return t
    // A failure raised while a tracked effect could not produce a pose is usually *caused* by it
    // (the frame was rendered without the deformation, or a tracker threw). Naming it turns
    // "Rendering failed: null" into something the user and the log can act on.
    val tracking = TrackingHealth.summary()?.let { " Tracking: $it." }.orEmpty()
    return ExportPipelineException("$prefix: ${t.message ?: t.javaClass.simpleName}.$tracking", t)
  }

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

  /**
   * Per-attempt still-frame fallback state. It used to live on the engine: attempt N's GL cleanup
   * (async, and its 10s teardown wait can expire with the cleanup still running) released and
   * cleared it while attempt N+1 was already putting live retrievers into it and submitting calls
   * to its executor - a MediaMetadataRetriever released under a running native call
   * (use-after-free) or a RejectedExecutionException from the shutdown executor failing a
   * healthy attempt. Owning the state per attempt removes the cross-attempt race entirely.
   */
  private class StillFrameFallback {
    // Concurrent: written by the AH-StillFrame worker (getOrPut) and cleared by the GL-thread
    // cleanup task. A plain HashMap corrupted under that race (lost entries / CME / resize loops).
    val retrievers = java.util.concurrent.ConcurrentHashMap<String, MediaMetadataRetriever>()

    /** One worker for retriever calls, so a hung call can be abandoned without blocking the GL thread. */
    var executor: ExecutorService? = null
  }

  /**
   * Policy of the still-frame fallback for the current attempt (see [ExportFallbackBudget]): every
   * retriever call gets a deadline, and the first call that overruns it retires the retriever for
   * that clip - a `MediaMetadataRetriever` that hung once will hang again, and the render loop is the
   * one thread that must keep the encoder fed.
   */
  private var fallbackBudget: ExportFallbackBudget = ExportFallbackBudget()

  companion object {
    /**
     * Deadline for loading one still image/sticker of the project. A local image loads in
     * milliseconds; a file that needs 20 s is a cloud-backed or damaged asset, and waiting for the
     * provider is not something the export can succeed at.
     */
    private const val IMAGE_LOAD_DEADLINE_MS = 20_000L
  }

  private fun fetchFallbackBitmap(
    clip: VideoClip,
    sourcePosMs: Long,
    maxW: Int,
    maxH: Int,
    fallback: StillFrameFallback
  ): Bitmap? {
    val budget = fallbackBudget
    if (!budget.mayUse(clip.id)) return null
    val startedNs = System.nanoTime()
    if (metrics.fallbackFrames.incrementAndGet() == 1L) {
      Log.w(
        tag,
        "Clip ${clip.name} has no working hardware/software decoder: falling back to " +
          "MediaMetadataRetriever still-frame extraction for this clip (much slower per frame)."
      )
    }
    val executor = fallback.executor ?: Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, "AH-StillFrame").apply { isDaemon = true }
    }.also { fallback.executor = it }
    val future = executor.submit(java.util.concurrent.Callable { extractFallbackBitmap(clip, sourcePosMs, maxW, maxH, fallback) })
    return try {
      val bitmap = future.get(budget.perCallDeadlineMs, TimeUnit.MILLISECONDS)
      budget.recordCall(clip.id, (System.nanoTime() - startedNs) / 1_000_000L, timedOut = false)
      if (budget.isSlowPath(clip.id) && budget.callsFor(clip.id) == budget.slowPathWarningCalls) {
        Log.w(
          tag,
          "Still-frame extraction has produced ${budget.callsFor(clip.id)} frames of '${clip.name}' " +
            "(${budget.timeSpentMs(clip.id)}ms so far): this export decodes that clip one still frame " +
            "at a time, which is why it runs far slower than realtime."
        )
      }
      bitmap
    } catch (_: java.util.concurrent.TimeoutException) {
      future.cancel(true)
      // Retire the retriever from the shared map BEFORE cleanup runs: the timed-out worker is
      // abandoned inside native getFrameAtTime and cannot be interrupted, so cleanup releasing
      // this retriever would be a use-after-free. The retriever is held until process exit
      // instead (same policy as abandoned codecs).
      fallback.retrievers.remove(clip.uri)
      val retired = budget.recordCall(clip.id, budget.perCallDeadlineMs, timedOut = true)
      // The worker is inside native code and cannot be interrupted. Drop the executor so a later
      // clip's retriever call is not queued behind it; the clip itself is retired below.
      runCatching { fallback.executor?.shutdownNow() }
      fallback.executor = null
      Log.e(
        tag,
        "Still-frame extraction timed out after ${budget.perCallDeadlineMs}ms for '${clip.name}' " +
          "(${clip.uri}) at ${sourcePosMs}ms; " +
          (if (retired) "the retriever is retired for this clip for the rest of this attempt"
           else "this clip is already retired") +
          ". Export continues with the layers it can decode."
      )
      null
    } catch (t: Throwable) {
      budget.recordCall(clip.id, (System.nanoTime() - startedNs) / 1_000_000L, timedOut = false)
      Log.w(tag, "Still-frame extraction failed for ${clip.uri}", t)
      null
    } finally {
      metrics.fallbackTimeNs.addAndGet(System.nanoTime() - startedNs)
    }
  }

  private fun extractFallbackBitmap(
    clip: VideoClip,
    sourcePosMs: Long,
    maxW: Int,
    maxH: Int,
    fallback: StillFrameFallback
  ): Bitmap? {
    return try {
      val retriever = fallback.retrievers.getOrPut(clip.uri) {
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
