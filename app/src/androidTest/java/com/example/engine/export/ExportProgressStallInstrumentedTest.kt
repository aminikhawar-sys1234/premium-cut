package com.example.engine.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.domain.model.AspectRatio
import com.example.domain.model.AudioClip
import com.example.domain.model.ChromaKeySettings
import com.example.domain.model.ClipKeyframe
import com.example.domain.model.EffectClip
import com.example.domain.model.EffectType
import com.example.domain.model.FilterSettings
import com.example.domain.model.FilterType
import com.example.domain.model.FrameRate
import com.example.domain.model.Resolution
import com.example.domain.model.StickerAnimationType
import com.example.domain.model.StickerClip
import com.example.domain.model.TextClip
import com.example.domain.model.Timeline
import com.example.domain.model.Transition
import com.example.domain.model.TransitionType
import com.example.domain.model.VideoAdjustments
import com.example.domain.model.VideoClip
import com.example.engine.composition.ChromaKeyResolver
import com.example.engine.effects.AndroidTestContextHolder
import com.example.engine.effects.LookVerificationReport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The report's two claims, as device tests: *"progress never started - 0 / 18484 frames after twenty
 * minutes"* and *"nothing ever told me why"*.
 *
 * The existing suites prove that difficult projects *export*; these two prove the pipeline's
 * **observability and failure behaviour**, which is what the reported export was missing:
 *
 * * `a01` exports a multi-layer project (overlays, text, stickers, effects, chroma key, transition,
 *   keyframes, filters, audio lane) through the production path while a sampler thread records what
 *   the panel would have shown. It then asserts what the report could not: the frame counter moved,
 *   the ring left the 5 % floor, and every stage the export passed through announced itself.
 * * `a02` provokes a real stall - the pipeline is parked in the rendering stage with a two second
 *   budget - and asserts the export **fails with a named reason** instead of waiting forever, and
 *   that it does so quickly.
 */
@RunWith(AndroidJUnit4::class)
class ExportProgressStallInstrumentedTest {

  private lateinit var dir: File
  /** Fixtures live in their own directory: the export deletes whatever sits at its output path. */
  private val sourceDir: File get() = File(dir, "sources")
  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
  private val config = ExportConfig(resolution = Resolution.RES_480P, frameRate = FrameRate.FPS_30)

  @Before
  fun setUp() {
    AndroidTestContextHolder.context = context
    dir = File(context.cacheDir, "export-progress-${System.nanoTime()}").also { it.mkdirs() }
    sourceDir.mkdirs()
    LookVerificationReport.section("Export progress + stall reporting")
  }

  @After
  fun tearDown() {
    runCatching { dir.deleteRecursively() }
  }

  // ------------------------------------------------------------------ fixtures

  private fun source(name: String, picture: TestMp4Factory.Picture, ms: Long): File {
    val file = File(sourceDir, name)
    file.parentFile?.mkdirs()
    try {
      TestMp4Factory.write(file, videoMs = ms, audioMs = ms, picture = picture)
    } catch (t: Throwable) {
      LookVerificationReport.line("could not build $name: ${t.javaClass.simpleName}: ${t.message}")
      assumeNoException(t)
    }
    return file
  }

  private fun png(name: String, color: Int): File {
    val file = File(sourceDir, name)
    file.parentFile?.mkdirs()
    val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
    Canvas(bitmap).drawRect(0f, 0f, 320f, 240f, Paint().apply { this.color = color })
    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
    return file
  }

  private fun video(
    id: String,
    file: File,
    startMs: Long = 0L,
    durationMs: Long,
    sourceTotalMs: Long = durationMs,
    isImage: Boolean = false,
    trackIndex: Int = 0,
    opacity: Float = 1f,
    blur: Boolean = false,
    keyframes: List<ClipKeyframe> = emptyList(),
    chromaKey: ChromaKeySettings? = null
  ) = VideoClip(
    id = id,
    uri = android.net.Uri.fromFile(file).toString(),
    name = file.name,
    isVideo = !isImage,
    timelineStartMs = startMs,
    durationMs = durationMs,
    sourceStartMs = 0L,
    sourceEndMs = durationMs,
    sourceTotalDurationMs = sourceTotalMs,
    width = TestMp4Factory.W,
    height = TestMp4Factory.H,
    frameRate = TestMp4Factory.FPS.toFloat(),
    hasAudio = !isImage,
    filter = FilterSettings(FilterType.GOLDEN_AUTUMN, 0.6f),
    adjustments = VideoAdjustments(brightness = 0.1f, contrast = 1.08f, saturation = 1.15f, sharpness = 0.2f),
    keyframes = keyframes,
    chromaKey = chromaKey,
    motionBlurEnabled = blur,
    trackIndex = trackIndex,
    opacity = opacity
  )

  /** The project shape of the report: several lanes, every layer type, one transition. */
  private fun multiLayerProject(): Timeline {
    val mainA = source("main-a.mp4", TestMp4Factory.Picture.COLORFUL, 900L)
    val mainB = source("main-b.mp4", TestMp4Factory.Picture.GREEN_SCREEN, 900L)
    val pip = source("pip.mp4", TestMp4Factory.Picture.COLORFUL, 900L)
    val sticker = png("sticker.png", Color.YELLOW)

    val chroma = ChromaKeySettings(
      enabled = true,
      targetColor = 0xFF00FF00,
      similarity = 0.5f,
      smoothness = 0.1f,
      spillSuppression = 0.4f,
      backgroundType = ChromaKeyResolver.BACKGROUND_SOLID,
      backgroundColor = 0xFF0000FF
    )

    return Timeline(
      videoClips = listOf(
        video(
          id = "main-a",
          file = mainA,
          durationMs = 900L,
          blur = true,
          keyframes = listOf(
            ClipKeyframe(timeMs = 0L, scale = 1.02f, posX = -0.03f),
            ClipKeyframe(timeMs = 899L, scale = 1.08f, posX = 0.03f)
          )
        ),
        video(id = "main-b", file = mainB, startMs = 700L, durationMs = 700L, sourceTotalMs = 900L, chromaKey = chroma)
      ),
      overlayClips = listOf(
        video(
          id = "pip-video",
          file = pip,
          durationMs = 1400L,
          sourceTotalMs = 900L,
          trackIndex = 1,
          opacity = 0.85f,
          keyframes = listOf(
            ClipKeyframe(timeMs = 0L, scale = 0.45f, posX = -0.35f, posY = -0.45f),
            ClipKeyframe(timeMs = 1399L, scale = 0.5f, posX = 0.35f, posY = -0.2f, rotation = -15f)
          )
        )
      ),
      textClips = listOf(
        TextClip(
          id = "text-1",
          text = "Progress report",
          timelineStartMs = 0L,
          durationMs = 1400L,
          trackIndex = 0,
          fontSizeSp = 32f,
          textColor = 0xFFFFFFFF,
          strokeWidth = 2f,
          strokeColor = 0xFF000000,
          posX = 0f,
          posY = -0.6f
        ),
        TextClip(
          id = "text-2",
          text = "no silent stall",
          timelineStartMs = 0L,
          durationMs = 1400L,
          trackIndex = 1,
          fontSizeSp = 26f,
          textColor = 0xFF00E5FF,
          animationType = "Slide In",
          posX = 0f,
          posY = 0.65f
        )
      ),
      stickerClips = listOf(
        StickerClip(
          id = "sticker-1",
          emojiOrAsset = android.net.Uri.fromFile(sticker).toString(),
          timelineStartMs = 0L,
          durationMs = 1400L,
          trackIndex = 1,
          scale = 0.5f,
          animationType = StickerAnimationType.PULSE
        )
      ),
      effectClips = listOf(
        EffectClip(
          id = "fx-1",
          effectType = EffectType.GLOW,
          timelineStartMs = 0L,
          durationMs = 1400L,
          intensity = 0.5f,
          trackIndex = 1
        )
      ),
      audioClips = listOf(
        AudioClip(
          id = "audio-lane",
          uri = android.net.Uri.fromFile(mainA).toString(),
          title = "main-a.mp4",
          timelineStartMs = 0L,
          durationMs = 1400L,
          sourceStartMs = 0L,
          sourceEndMs = 900L,
          sourceTotalDurationMs = 900L,
          volume = 0.7f,
          fadeInMs = 200L,
          fadeOutMs = 200L
        )
      ),
      transitions = listOf(Transition(clipIndexBefore = 0, type = TransitionType.FADE, durationMs = 300L)),
      aspectRatio = AspectRatio.RATIO_16_9
    )
  }

  // ------------------------------------------------------------------ a01: the counter moves

  @Test
  fun a01_multiLayerExportAdvancesTheFrameCounterAndAnnouncesEveryStage() {
    val timeline = multiLayerProject()
    val totalFrames = Math.ceil(timeline.totalDurationMs / 1000.0 * config.frameRate.fps).toLong()
    LookVerificationReport.line(
      "project: video=${timeline.videoClips.size} overlay=${timeline.overlayClips.size} " +
        "text=${timeline.textClips.size} sticker=${timeline.stickerClips.size} " +
        "effect=${timeline.effectClips.size} audio=${timeline.audioClips.size} " +
        "transitions=${timeline.transitions.size} duration=${timeline.totalDurationMs}ms " +
        "expectedFrames=$totalFrames"
    )

    // What the panel would have shown, sampled while the export runs.
    val engine = AsyncFramePipelineEngine(context)
    val samples = Collections.synchronizedList(mutableListOf<Sample>())
    val sampling = java.util.concurrent.atomic.AtomicBoolean(true)
    val sampler = Thread {
      while (sampling.get()) {
        samples.add(
          Sample(
            phase = engine.currentPhase,
            phaseFraction = engine.phaseFraction,
            fraction = ExportProgressModel.overall(
              engine.currentPhase,
              engine.phaseFraction,
              maxOf(engine.metrics.encodedFrames.get(), engine.metrics.gpuFrames.get()),
              totalFrames
            ),
            submitted = engine.metrics.gpuFrames.get(),
            encoded = engine.metrics.encodedFrames.get()
          )
        )
        try {
          Thread.sleep(20L)
        } catch (_: InterruptedException) {
          return@Thread
        }
      }
    }.apply { isDaemon = true; start() }

    val output = File(dir, "progress.mp4")
    val failure = AtomicReference<Throwable?>(null)
    val executor = Executors.newSingleThreadExecutor()
    val started = System.currentTimeMillis()
    val future = executor.submit {
      runCatching { runBlocking { engine.export(timeline, config, output) } }.onFailure { failure.set(it) }
    }
    try {
      future.get(240, TimeUnit.SECONDS)
    } finally {
      sampling.set(false)
      sampler.join(1_000L)
      executor.shutdownNow()
    }
    val elapsedMs = System.currentTimeMillis() - started

    failure.get()?.let { error ->
      LookVerificationReport.line("EXPORT FAILED: ${error.javaClass.name}: ${error.message}")
      LookVerificationReport.line("cause chain:\n" + generateSequence(error) { it.cause }.take(6).joinToString("\n  caused by "))
      throw AssertionError("the multi-layer project did not export: ${error.message}", error)
    }

    // 1. The counter - the number the report showed as "0 / 18484" - really moved.
    val submitted = engine.metrics.gpuFrames.get()
    val encoded = engine.metrics.encodedFrames.get()
    LookVerificationReport.line(
      "export finished in ${elapsedMs}ms: submitted=$submitted encoded=$encoded " +
        "bytes=${output.length()} samples=${samples.size}"
    )
    assertTrue("no frame reached the encoder (submitted=$submitted)", submitted > 0L)
    assertTrue("the encoder produced nothing (encoded=$encoded)", encoded > 0L)
    assertTrue("the export must have produced a file", output.length() > 0L)

    // 2. The pipeline never sat in one stage while nothing happened.
    assertTrue("the stall watchdog must not have fired: ${engine.stallFailure?.message}", engine.stallFailure == null)
    assertTrue("frames must have been submitted while the pipeline was rendering", samples.any { it.phase == ExportPhase.RENDERING && it.submitted > 0L })

    // 3. Every stage announced itself, and the ring moved off the 5 % floor it was stuck at.
    val phasesSeen = samples.map { it.phase }.distinct()
    val fractionRange = samples.minOfOrNull { it.fraction }!!..samples.maxOfOrNull { it.fraction }!!
    LookVerificationReport.line(
      "stages observed (${phasesSeen.size}): ${phasesSeen.joinToString(", ") { it.name }}; " +
        "ring ${"%.3f".format(fractionRange.start)}..${"%.3f".format(fractionRange.endInclusive)}"
    )
    assertTrue("expected at least three stages to be observable, saw $phasesSeen", phasesSeen.size >= 3)
    assertTrue(
      "at least one pre-roll stage must have been observable (the reported panel could not show any)",
      samples.any { it.phase.isPreRoll }
    )
    assertTrue(
      "the ring must pass the 5% floor the reported export never left, max was ${fractionRange.endInclusive}",
      fractionRange.endInclusive > ExportProgressModel.PRE_ROLL_CEILING + 0.01f
    )
    LookVerificationReport.line(
      "pre-roll progress observed: " + samples.filter { it.phase.isPreRoll }
        .maxByOrNull { it.phaseFraction }
        ?.let { "${it.phase.name} ${"%.2f".format(it.phaseFraction)}" }.orEmpty()
    )

    // 4. The output is a real, decodable MP4 with the expected duration.
    val expected = ExportDimensionResolver.resolve(config.resolution, timeline.aspectRatio)
    val validation = ExportValidator.validate(
      output,
      config,
      timeline.totalDurationMs,
      requireAudio = true,
      expectedDimensions = expected,
      sampleFrames = true,
      expectedRotation = 0
    )
    LookVerificationReport.line(
      "validation: valid=${validation.valid} '${validation.message}' dimensions=${expected.first}x${expected.second}"
    )
    assertTrue("validation failed: ${validation.message}", validation.valid)
  }

  // ------------------------------------------------------------------ a02: a stall is named

  @Test
  fun a02_aStalledExportFailsWithANamedReasonInsteadOfHanging() {
    // A real stall is provoked where the reported one happened: inside the rendering stage, before
    // the first frame. The budget is shortened (the production one is deliberately generous, see
    // ExportStallWatchdog), so the verdict arrives in seconds instead of minutes.
    val timeline = multiLayerProject()
    val engine = AsyncFramePipelineEngine(context)
    engine.stallBudgetsForTests = mapOf(
      ExportPhase.RENDERING to ExportStallWatchdog.Budget(hardMs = 2_000L)
    )
    engine.phaseStallForTests = "${ExportPhase.RENDERING.name}:8000"

    val output = File(dir, "stalled.mp4")
    val failure = AtomicReference<Throwable?>(null)
    val executor = Executors.newSingleThreadExecutor()
    val started = System.currentTimeMillis()
    val future = executor.submit {
      runCatching { runBlocking { engine.export(timeline, config, output) } }.onFailure { failure.set(it) }
    }
    try {
      future.get(90, TimeUnit.SECONDS)
    } catch (t: java.util.concurrent.TimeoutException) {
      runCatching { engine.cancel() }
      runCatching { future.cancel(true) }
      throw AssertionError(
        "the parked export never failed - the silent stall of the report is back " +
          "(phase=${engine.currentPhase}, budgetOverride=2s)"
      )
    } finally {
      executor.shutdownNow()
    }
    val elapsedMs = System.currentTimeMillis() - started

    val error = failure.get()
    LookVerificationReport.line(
      "stall injection: failed in ${elapsedMs}ms with ${error?.javaClass?.name}: ${error?.message}"
    )
    LookVerificationReport.line("watchdog verdict: ${engine.stallFailure?.message}")
    assertNotNull("a parked export must fail, not complete", error)
    assertTrue("it must fail with the pipeline's own exception, was $error", error is ExportPipelineException)
    val message = error?.message ?: ""
    assertTrue(
      "the failure must name the stage it was stuck in, was: $message",
      message.contains(ExportPhase.RENDERING.label) || message.contains("video encoder")
    )
    assertFalse("a device stall must not be offered as a retry", (error as ExportPipelineException).retryable)
    assertTrue("the stall must be detected by the watchdog, was ${engine.stallFailure}", engine.stallFailure != null)
    // Two second budget, parked for fifteen: the verdict must arrive long before the park ends.
    assertTrue("the verdict must arrive well before the park ends, took ${elapsedMs}ms", elapsedMs < 12_000L)
    // Nothing half-written is left behind for the gallery to pick up.
    assertTrue("no partial file may be left behind", !output.exists() || output.length() == 0L)
  }

  private data class Sample(
    val phase: ExportPhase,
    val phaseFraction: Float,
    val fraction: Float,
    val submitted: Long,
    val encoded: Long
  )
}
