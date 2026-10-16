package com.example.engine.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.domain.model.AspectRatio
import com.example.domain.model.AudioClip
import com.example.domain.model.BadgeType
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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * The reported failure in one test: a *simple* 1-30 s single-clip project exports, while a project
 * with multiple tracks, overlays, stickers, text, filters, colour adjustments, motion-tracked
 * layers, animations and a chroma key fails with "Export Failed".
 *
 * These tests drive the exact production path -
 * `AsyncFramePipelineEngine.export(timeline, config, file)` with no size override, i.e. the same
 * dimension resolver, encoder planner, attempt ladder, GPU compositor and validator the app uses -
 * and measure the decoded output pixels afterwards. Everything is reported through
 * [LookVerificationReport], which the CI device job pulls back, so a failure carries the real
 * exception, the render report and the measured frame statistics instead of only a red cross.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ComplexProjectExportInstrumentedTest {

  private lateinit var dir: File
  /**
   * Fixtures live in their own directory: the export deletes whatever sits at its output path
   * (`outputFile.delete()` before the muxer opens it), so a fixture that shares a directory *and*
   * name with an output file is destroyed by the export that is supposed to read it. That is
   * exactly what made this suite's control case fail its first CI run.
   */
  private val sourceDir: File get() = File(dir, "sources")
  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

  private val portraitConfig = ExportConfig(resolution = Resolution.RES_720P, frameRate = FrameRate.FPS_30)
  private val landscapeConfig = ExportConfig(resolution = Resolution.RES_1080P, frameRate = FrameRate.FPS_30)

  @Before
  fun setUp() {
    AndroidTestContextHolder.context = context
    dir = File(context.cacheDir, "complex-export-${System.nanoTime()}").also { it.mkdirs() }
    sourceDir.mkdirs()
    LookVerificationReport.section("Complex project export (multi-track, all features)")
    LookVerificationReport.line("stamp=${LookVerificationReport.stamp()} dir=$dir")
  }

  @After
  fun tearDown() {
    runCatching { dir.deleteRecursively() }
  }

  // ------------------------------------------------------------------ helpers

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

  /**
   * A timeline clip. [isImage] must be true for a still picture: the pipeline picks the image path
   * for it and, just as important, the audio mixer must not try to decode a PNG.
   */
  private fun video(
    id: String,
    file: File,
    startMs: Long = 0L,
    durationMs: Long,
    sourceStartMs: Long = 0L,
    sourceTotalMs: Long = durationMs,
    isImage: Boolean = false,
    filter: FilterSettings? = null,
    adjustments: VideoAdjustments? = null,
    keyframes: List<ClipKeyframe> = emptyList(),
    chromaKey: ChromaKeySettings? = null,
    blur: Boolean = false,
    trackIndex: Int = 0,
    opacity: Float = 1f,
    blendMode: String = "Normal",
    isBackgroundRemoved: Boolean = false,
    rotationDegrees: Int = 0
  ) = VideoClip(
    id = id,
    uri = android.net.Uri.fromFile(file).toString(),
    name = file.name,
    isVideo = !isImage,
    timelineStartMs = startMs,
    durationMs = durationMs,
    sourceStartMs = sourceStartMs,
    sourceEndMs = sourceStartMs + durationMs,
    sourceTotalDurationMs = sourceTotalMs,
    width = TestMp4Factory.W,
    height = TestMp4Factory.H,
    frameRate = TestMp4Factory.FPS.toFloat(),
    hasAudio = !isImage,
    filter = filter,
    adjustments = adjustments,
    keyframes = keyframes,
    chromaKey = chromaKey,
    motionBlurEnabled = blur,
    trackIndex = trackIndex,
    opacity = opacity,
    blendMode = blendMode,
    isBackgroundRemoved = isBackgroundRemoved,
    rotationDegrees = rotationDegrees
  )

  private fun export(timeline: Timeline, config: ExportConfig, name: String): File {
    val out = File(dir, name)
    // The export deletes the file at its output path before muxing. Writing over a source would
    // silently destroy the very clip the export has to decode, so make that a test error.
    val sources = (timeline.videoClips + timeline.overlayClips).mapNotNull { clip ->
      android.net.Uri.parse(clip.uri).path?.let { File(it).canonicalPath }
    }
    assertTrue(
      "the export output '$name' overwrites a source clip of the same test",
      sources.none { it == out.canonicalPath }
    )
    val started = System.currentTimeMillis()
    val engine = AsyncFramePipelineEngine(context)
    try {
      runBlocking { engine.export(timeline, config, out) }
    } catch (t: Throwable) {
      LookVerificationReport.line(
        "engine metrics: ${engine.metrics.describe()} plan=${engine.activePlan?.describe()}"
      )
      // Diagnostics that decide *what* failed: the whole cause chain (a MediaCodec failure usually
      // sits under an ExportPipelineException), what the device's decoders are, and whether the
      // very first video source can be decoded outside the pipeline at all. Without this the test
      // report only says "Could not decode a video frame from X".
      LookVerificationReport.line("EXPORT FAILED $name: ${t.javaClass.name}: ${t.message}")
      LookVerificationReport.line("cause chain:\n" + t.causeChain())
      LookVerificationReport.line("decoders on this device: " + decoderInventory())
      (timeline.videoClips + timeline.overlayClips)
        .firstOrNull { it.isVideo && it.uri.isNotBlank() }
        ?.let { clip ->
          val path = android.net.Uri.parse(clip.uri).path
          if (path == null) {
            LookVerificationReport.line("source '${clip.name}' is not a local file (${clip.uri})")
          } else {
            val file = File(path)
            LookVerificationReport.line("source '${clip.name}' tracks: " + describeTracks(file))
            LookVerificationReport.line("source '${clip.name}' direct decode: " + probeDecodeOnce(file))
          }
        }
      LookVerificationReport.line(t.stackTraceToString().lineSequence().take(40).joinToString("\n"))
      throw AssertionError(
        "the export pipeline could not produce $name (${timeline.describeShape()}): ${t.message}",
        t
      )
    }
    LookVerificationReport.line(
      "export $name took ${System.currentTimeMillis() - started}ms, ${out.length()} bytes, " +
        "timeline=${timeline.describeShape()}"
    )
    return out
  }

  private fun Timeline.describeShape(): String =
    "video=${videoClips.size} overlay=${overlayClips.size} text=${textClips.size} " +
      "sticker=${stickerClips.size} effect=${effectClips.size} audio=${audioClips.size} " +
      "transitions=${transitions.size} duration=${totalDurationMs}ms aspect=$aspectRatio"

  // ------------------------------------------------------------------ failure diagnostics

  /** `ExportPipelineException -> MediaCodec.CodecException -> ...` flattened into one string. */
  private fun Throwable.causeChain(): String {
    val parts = mutableListOf<String>()
    var current: Throwable? = this
    while (current != null && parts.size < 8) {
      val message = current.message?.replace('\n', ' ')?.take(400)
      parts.add("${current.javaClass.name}: $message")
      current = current.cause?.takeIf { it !== current }
    }
    return parts.joinToString("\n  caused by ")
  }

  /** Which video decoders this device advertises (a codec-instance limit shows up here first). */
  private fun decoderInventory(): String = try {
    val infos = android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).codecInfos
    infos
      .filter { info -> info.supportedTypes.any { it.startsWith("video/") } }
      .joinToString("; ") { info ->
        val hw = if (android.os.Build.VERSION.SDK_INT >= 29 && info.isHardwareAccelerated) "hw" else "sw"
        "${info.name}[$hw]"
      }
  } catch (t: Throwable) {
    "unavailable: ${t.javaClass.simpleName}: ${t.message}"
  }

  /** MediaExtractor's view of a fixture: proves the file is a real, complete MP4. */
  private fun describeTracks(file: File): String = try {
    val extractor = MediaExtractor()
    try {
      extractor.setDataSource(file.absolutePath)
      (0 until extractor.trackCount).joinToString("; ") { index ->
        val format = extractor.getTrackFormat(index)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: "?"
        val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) {
          format.getLong(MediaFormat.KEY_DURATION)
        } else -1L
        val w = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else -1
        val h = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else -1
        "#$index $mime ${w}x$h ${duration / 1000}ms"
      }
    } finally {
      runCatching { extractor.release() }
    }
  } catch (t: Throwable) {
    "unreadable: ${t.javaClass.simpleName}: ${t.message}"
  }

  /**
   * Decodes one frame of [file] with a fresh MediaCodec, outside the export pipeline. "ok" with the
   * pipeline failing means the engine's decode path failed, not the file; a codec error here names
   * the device-side reason (instance limit, unsupported profile, dead decoder).
   */
  private fun probeDecodeOnce(file: File): String {
    val extractor = MediaExtractor()
    var codec: android.media.MediaCodec? = null
    return try {
      extractor.setDataSource(file.absolutePath)
      val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
        extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
      }
      if (trackIndex == null) {
        "no video track"
      } else {
        val format = extractor.getTrackFormat(trackIndex)
        extractor.selectTrack(trackIndex)
        val decoder = android.media.MediaCodec.createDecoderByType(
          format.getString(MediaFormat.KEY_MIME) ?: MediaFormat.MIMETYPE_VIDEO_AVC
        )
        codec = decoder
        decoder.configure(format, null, null, 0)
        decoder.start()
        val info = android.media.MediaCodec.BufferInfo()
        val deadline = android.os.SystemClock.elapsedRealtime() + 5_000L
        var result = "no output within 5s (decoder produced nothing)"
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
          val input = decoder.dequeueInputBuffer(2_000L)
          if (input >= 0) {
            val buffer = decoder.getInputBuffer(input)
            val size = if (buffer != null) extractor.readSampleData(buffer, 0) else -1
            if (size < 0) {
              decoder.queueInputBuffer(input, 0, 0, 0L, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            } else {
              decoder.queueInputBuffer(input, 0, size, extractor.sampleTime, 0)
              extractor.advance()
            }
          }
          val output = decoder.dequeueOutputBuffer(info, 2_000L)
          if (output >= 0) {
            decoder.releaseOutputBuffer(output, false)
            result = "ok frame pts=${info.presentationTimeUs}us size=${info.size}"
            break
          }
        }
        result
      }
    } catch (t: Throwable) {
      "${t.javaClass.name}: ${t.message}"
    } finally {
      runCatching { codec?.stop() }
      runCatching { codec?.release() }
      runCatching { extractor.release() }
    }
  }

  private fun frameAt(file: File, timeUs: Long): Bitmap {
    val retriever = MediaMetadataRetriever()
    try {
      retriever.setDataSource(file.absolutePath)
      val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
      assertTrue("no frame decoded from ${file.name} at ${timeUs}us", bitmap != null)
      return bitmap!!
    } finally {
      runCatching { retriever.release() }
    }
  }

  private fun meanRgb(bitmap: Bitmap, x0f: Float, x1f: Float, y0f: Float, y1f: Float): FloatArray {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    val x0 = (w * x0f).toInt().coerceIn(0, w - 1)
    val x1 = (w * x1f).toInt().coerceIn(x0 + 1, w)
    val y0 = (h * y0f).toInt().coerceIn(0, h - 1)
    val y1 = (h * y1f).toInt().coerceIn(y0 + 1, h)
    val sums = FloatArray(3)
    var count = 0
    for (y in y0 until y1) for (x in x0 until x1) {
      val p = pixels[y * w + x]
      sums[0] += Color.red(p) / 255f
      sums[1] += Color.green(p) / 255f
      sums[2] += Color.blue(p) / 255f
      count++
    }
    val n = count.coerceAtLeast(1).toFloat()
    return floatArrayOf(sums[0] / n, sums[1] / n, sums[2] / n)
  }

  private fun luma(bitmap: Bitmap): Float {
    val rgb = meanRgb(bitmap, 0.05f, 0.95f, 0.05f, 0.95f)
    return (rgb[0] + rgb[1] + rgb[2]) / 3f
  }

  private fun format(rgb: FloatArray) = "%.4f/%.4f/%.4f".format(rgb[0], rgb[1], rgb[2])

  private fun audioTrackMime(file: File): String {
    val extractor = MediaExtractor()
    try {
      extractor.setDataSource(file.absolutePath)
      for (i in 0 until extractor.trackCount) {
        val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
        if (mime.startsWith("audio/")) return mime
      }
    } finally {
      runCatching { extractor.release() }
    }
    return "none"
  }

  // ------------------------------------------------------------------ projects

  /**
   * The project the report describes: two main clips joined by a transition, two overlay lanes
   * (one video, one still image), three text layers (static, animated, mesh 3D), two stickers
   * (emoji + image), two effect clips (a post-process shader pass and a procedural overlay),
   * keyframed motion on several layers, a per-clip filter and colour grade, a chroma key whose
   * background is another *video*, and a second audio lane.
   */
  private fun complexProject(aspect: AspectRatio): Timeline {
    val mainA = source("main-a.mp4", TestMp4Factory.Picture.COLORFUL, 900L)
    val mainB = source("main-b.mp4", TestMp4Factory.Picture.GREEN_SCREEN, 900L)
    val pip = source("pip.mp4", TestMp4Factory.Picture.COLORFUL, 900L)
    val keyBg = source("key-bg.mp4", TestMp4Factory.Picture.DARK_NIGHT, 900L)
    val sticker = png("sticker.png", Color.YELLOW)
    val overlayPng = png("overlay.png", Color.RED)

    val chroma = ChromaKeySettings(
      enabled = true,
      targetColor = 0xFF00FF00,
      similarity = 0.5f,
      smoothness = 0.1f,
      spillSuppression = 0.4f,
      backgroundType = ChromaKeyResolver.BACKGROUND_VIDEO,
      backgroundColor = 0xFF0000FF,
      backgroundUri = android.net.Uri.fromFile(keyBg).toString()
    )

    return Timeline(
      videoClips = listOf(
        video(
          id = "main-a",
          file = mainA,
          durationMs = 900L,
          filter = FilterSettings(FilterType.GOLDEN_AUTUMN, 0.7f),
          adjustments = VideoAdjustments(brightness = 0.12f, contrast = 1.1f, saturation = 1.2f, sharpness = 0.25f),
          keyframes = listOf(
            ClipKeyframe(timeMs = 0L, scale = 1.02f, posX = -0.04f, opacity = 1f),
            ClipKeyframe(timeMs = 500L, scale = 1.10f, posX = 0.04f, opacity = 1f),
            ClipKeyframe(timeMs = 899L, scale = 1.04f, posX = 0f, opacity = 1f)
          ),
          blur = true
        ),
        video(
          id = "main-b",
          file = mainB,
          startMs = 700L,
          durationMs = 700L,
          sourceTotalMs = 900L,
          chromaKey = chroma,
          adjustments = VideoAdjustments(brightness = 0.05f, saturation = 1.05f),
          rotationDegrees = 90
        )
      ),
      overlayClips = listOf(
        video(
          id = "pip-video",
          file = pip,
          durationMs = 1400L,
          sourceTotalMs = 900L,
          trackIndex = 1,
          opacity = 0.85f,
          blendMode = "Screen",
          adjustments = VideoAdjustments(saturation = 1.3f),
          keyframes = listOf(
            ClipKeyframe(timeMs = 0L, scale = 0.45f, posX = -0.35f, posY = -0.45f, rotation = 0f),
            ClipKeyframe(timeMs = 700L, scale = 0.6f, posX = 0.3f, posY = 0.3f, rotation = 25f),
            ClipKeyframe(timeMs = 1399L, scale = 0.5f, posX = 0.35f, posY = -0.2f, rotation = -15f)
          )
        ),
        video(
          id = "pip-image",
          file = overlayPng,
          durationMs = 1400L,
          sourceTotalMs = 1400L,
          isImage = true,
          trackIndex = 2,
          opacity = 0.55f,
          blendMode = "Multiply",
          keyframes = listOf(
            ClipKeyframe(timeMs = 0L, scale = 0.35f, posX = 0.5f, posY = -0.5f),
            ClipKeyframe(timeMs = 900L, scale = 0.5f, posX = -0.2f, posY = 0.5f, rotation = 40f)
          )
        )
      ),
      textClips = listOf(
        TextClip(
          id = "text-static",
          text = "Complex project",
          timelineStartMs = 0L,
          durationMs = 1400L,
          trackIndex = 0,
          fontSizeSp = 34f,
          textColor = 0xFFFFFFFF,
          strokeWidth = 2f,
          strokeColor = 0xFF000000,
          hasShadow = true,
          posX = 0f,
          posY = -0.6f
        ),
        TextClip(
          id = "text-animated",
          text = "animated",
          timelineStartMs = 0L,
          durationMs = 1400L,
          trackIndex = 1,
          fontSizeSp = 28f,
          textColor = 0xFF00E5FF,
          hasGlow = true,
          animationType = "Slide In",
          animationIn = "Zoom In",
          animationOut = "Fade Out",
          posX = 0f,
          posY = 0.1f,
          keyframes = listOf(
            ClipKeyframe(timeMs = 0L, posX = -0.6f, rotation = -10f, opacity = 0.6f),
            ClipKeyframe(timeMs = 700L, posX = 0.6f, rotation = 12f, opacity = 1f),
            ClipKeyframe(timeMs = 1399L, posX = 0.2f, rotation = 0f, opacity = 1f)
          )
        ),
        TextClip(
          id = "text-3d",
          text = "3D",
          timelineStartMs = 0L,
          durationMs = 1400L,
          trackIndex = 2,
          fontSizeSp = 64f,
          textColor = 0xFFFFC107,
          is3D = true,
          depth3D = 18f,
          trueMesh3D = true,
          bevelRadius3D = 4f,
          material3D = "metal",
          lightPreset3D = "studio",
          posX = 0.55f,
          posY = 0.55f
        )
      ),
      stickerClips = listOf(
        StickerClip(
          id = "sticker-emoji",
          emojiOrAsset = "\uD83C\uDFAC",
          timelineStartMs = 0L,
          durationMs = 1400L,
          trackIndex = 1,
          scale = 0.9f,
          animationType = StickerAnimationType.BOUNCE,
          keyframes = listOf(
            ClipKeyframe(timeMs = 0L, posX = -0.5f, posY = 0.55f, scale = 0.24f),
            ClipKeyframe(timeMs = 700L, posX = 0.0f, posY = -0.2f, scale = 0.34f),
            ClipKeyframe(timeMs = 1399L, posX = 0.5f, posY = 0.4f, scale = 0.26f)
          )
        ),
        StickerClip(
          id = "sticker-image",
          emojiOrAsset = android.net.Uri.fromFile(sticker).toString(),
          timelineStartMs = 0L,
          durationMs = 1400L,
          trackIndex = 2,
          scale = 0.5f,
          animationType = StickerAnimationType.PULSE,
          badgeType = BadgeType.NEW,
          posX = -0.45f,
          posY = -0.15f
        )
      ),
      effectClips = listOf(
        EffectClip(
          id = "fx-shader",
          effectType = EffectType.GLOW,
          timelineStartMs = 0L,
          durationMs = 1400L,
          intensity = 0.55f,
          trackIndex = 1,
          keyframes = listOf(
            ClipKeyframe(timeMs = 0L, effectParam = 0.2f),
            ClipKeyframe(timeMs = 1399L, effectParam = 0.9f)
          )
        ),
        EffectClip(
          id = "fx-procedural",
          effectType = EffectType.CELEBRATE_CONFETTI,
          timelineStartMs = 0L,
          durationMs = 1400L,
          intensity = 0.7f,
          trackIndex = 2
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
          volume = 0.6f,
          fadeInMs = 200L,
          fadeOutMs = 300L
        )
      ),
      transitions = listOf(Transition(clipIndexBefore = 0, type = TransitionType.FADE, durationMs = 400L)),
      adjustments = VideoAdjustments(brightness = 0.05f, contrast = 1.05f, exposure = 0.05f, temperature = 0.1f),
      filter = FilterSettings(FilterType.NONE, 1f),
      chromaKey = ChromaKeySettings(),
      aspectRatio = aspect
    )
  }

  // ------------------------------------------------------------------ tests

  @Test
  fun b01_complexProjectExportsAValidPortraitVideo() {
    LookVerificationReport.line(">>> START b01_complexProjectExportsAValidPortraitVideo")
    val timeline = complexProject(AspectRatio.RATIO_9_16)
    val out = export(timeline, portraitConfig, "complex-portrait.mp4")

    val expected = ExportDimensionResolver.resolve(portraitConfig.resolution, timeline.aspectRatio)
    LookVerificationReport.line("portrait dimensions: expected=${expected.first}x${expected.second}")

    val validation = ExportValidator.validate(
      out, portraitConfig, timeline.totalDurationMs,
      requireAudio = true, expectedDimensions = expected, sampleFrames = true, expectedRotation = 0
    )
    LookVerificationReport.line("portrait validation: valid=${validation.valid} '${validation.message}' " +
      "size=${validation.width}x${validation.height} video=${validation.videoCodec} audio=${validation.audioCodec}")
    assertTrue("complex portrait export rejected: ${validation.message}", validation.valid)
    assertTrue(
      "portrait export is not portrait: ${validation.width}x${validation.height}",
      validation.height > validation.width
    )
    assertTrue("no audio track in the complex export", audioTrackMime(out) != "none")

    // Visual evidence: the frame carries picture (not black) and the animated layers actually move.
    val first = frameAt(out, 150_000L)
    val middle = frameAt(out, 700_000L)
    val last = frameAt(out, 1_350_000L)
    LookVerificationReport.line(
      "portrait frames: first luma=%.4f mid luma=%.4f last luma=%.4f".format(luma(first), luma(middle), luma(last))
    )
    LookVerificationReport.line("centre crop first=${format(meanRgb(first, 0.3f, 0.7f, 0.3f, 0.7f))} " +
      "mid=${format(meanRgb(middle, 0.3f, 0.7f, 0.3f, 0.7f))}")

    assertTrue("the exported complex frame is black (luma=${luma(middle)})", luma(middle) > 0.05f)
    val moved = abs(meanRgb(first, 0.3f, 0.7f, 0.3f, 0.7f)[0] - meanRgb(middle, 0.3f, 0.7f, 0.3f, 0.7f)[0]) +
      abs(meanRgb(first, 0.3f, 0.7f, 0.3f, 0.7f)[1] - meanRgb(middle, 0.3f, 0.7f, 0.3f, 0.7f)[1])
    assertTrue("animated layers did not change the frame (delta=$moved)", moved > 0.01f)
  }

  @Test
  fun b02_complexProjectExportsAValidLandscapeVideo() {
    LookVerificationReport.line(">>> START b02_complexProjectExportsAValidLandscapeVideo")
    val timeline = complexProject(AspectRatio.RATIO_16_9)
    val out = export(timeline, landscapeConfig, "complex-landscape.mp4")

    val expected = ExportDimensionResolver.resolve(landscapeConfig.resolution, timeline.aspectRatio)
    LookVerificationReport.line("landscape dimensions: expected=${expected.first}x${expected.second}")

    val validation = ExportValidator.validate(
      out, landscapeConfig, timeline.totalDurationMs,
      requireAudio = true, expectedDimensions = expected, sampleFrames = true, expectedRotation = 0
    )
    LookVerificationReport.line("landscape validation: valid=${validation.valid} '${validation.message}' " +
      "size=${validation.width}x${validation.height} video=${validation.videoCodec} audio=${validation.audioCodec}")
    assertTrue("complex landscape export rejected: ${validation.message}", validation.valid)
    assertTrue(
      "landscape export is not landscape: ${validation.width}x${validation.height}",
      validation.width > validation.height
    )
    assertTrue("nothing was drawn in the landscape export", luma(frameAt(out, 500_000L)) > 0.05f)
  }

  @Test
  fun c01_manySimultaneousLayersStillExport() {
    LookVerificationReport.line(">>> START c01_manySimultaneousLayersStillExport")
    // Resource-shaped stress: several decoders, several textures, several full-viewport passes at
    // once. This is the shape that separates a 1-30 s single-clip export from a real project.
    val ms = 900L
    val a = source("many-a.mp4", TestMp4Factory.Picture.COLORFUL, ms)
    val b = source("many-b.mp4", TestMp4Factory.Picture.DARK_NIGHT, ms)
    val c = source("many-c.mp4", TestMp4Factory.Picture.GREEN_SCREEN, ms)
    val png = png("many.png", Color.MAGENTA)

    val timeline = Timeline(
      videoClips = listOf(
        video("m-a", a, durationMs = ms, adjustments = VideoAdjustments(saturation = 1.2f)),
        video("m-b", b, startMs = 450L, durationMs = 900L, adjustments = VideoAdjustments(brightness = 0.15f))
      ),
      overlayClips = (0..2).map { i ->
        video(
          id = "many-ov-$i",
          file = if (i == 2) c else a,
          durationMs = 1400L,
          sourceTotalMs = 900L,
          trackIndex = i + 1,
          opacity = 0.8f,
          keyframes = listOf(
            ClipKeyframe(timeMs = 0L, scale = 0.4f, posX = -0.5f + i * 0.5f, posY = -0.5f + i * 0.3f),
            ClipKeyframe(timeMs = 1300L, scale = 0.5f, posX = 0.5f - i * 0.4f, posY = 0.4f - i * 0.2f, rotation = 20f * i)
          )
        )
      },
      textClips = (0..2).map { i ->
        TextClip(
          id = "many-text-$i",
          text = "layer $i",
          timelineStartMs = 0L,
          durationMs = 1400L,
          trackIndex = i,
          fontSizeSp = 26f,
          textColor = 0xFFFFFFFF,
          animationType = "Slide In",
          posX = -0.4f + i * 0.4f,
          posY = 0.2f - i * 0.25f
        )
      },
      stickerClips = listOf(
        StickerClip(id = "many-stk-1", emojiOrAsset = "\uD83D\uDD25", durationMs = 1400L, trackIndex = 1, scale = 0.7f, animationType = StickerAnimationType.SPIN),
        StickerClip(id = "many-stk-2", emojiOrAsset = android.net.Uri.fromFile(png).toString(), durationMs = 1400L, trackIndex = 2, scale = 0.4f, badgeType = BadgeType.HOT),
        StickerClip(id = "many-stk-3", emojiOrAsset = "\u2728", durationMs = 1400L, trackIndex = 3, scale = 0.6f)
      ),
      effectClips = listOf(
        EffectClip(id = "many-fx-1", effectType = EffectType.GLOW, durationMs = 1400L, intensity = 0.5f),
        EffectClip(id = "many-fx-2", effectType = EffectType.VIGNETTE, durationMs = 1400L, intensity = 0.6f),
        EffectClip(id = "many-fx-3", effectType = EffectType.CELEBRATE_FIREWORKS, durationMs = 1400L, intensity = 0.6f)
      ),
      transitions = listOf(Transition(clipIndexBefore = 0, type = TransitionType.SLIDE_LEFT, durationMs = 300L)),
      aspectRatio = AspectRatio.RATIO_9_16,
      adjustments = VideoAdjustments(brightness = 0.04f, contrast = 1.06f)
    )

    val out = export(timeline, portraitConfig, "many-layers.mp4")
    val expected = ExportDimensionResolver.resolve(portraitConfig.resolution, timeline.aspectRatio)
    val validation = ExportValidator.validate(
      out, portraitConfig, timeline.totalDurationMs,
      requireAudio = true, expectedDimensions = expected, sampleFrames = true, expectedRotation = 0
    )
    LookVerificationReport.line("many-layers validation: valid=${validation.valid} '${validation.message}' " +
      "size=${validation.width}x${validation.height}")
    assertTrue("many-layer export rejected: ${validation.message}", validation.valid)
    assertTrue("many-layer export is black", luma(frameAt(out, 600_000L)) > 0.05f)
    assertTrue(
      "many-layer export has the wrong shape: ${validation.width}x${validation.height}",
      validation.height > validation.width && abs(validation.width - expected.first) <= 16
    )
  }

  @Test
  fun a01_simpleSingleClipExportStillWorks() {
    LookVerificationReport.line(">>> START a01_simpleSingleClipExportStillWorks")
    // The control case from the report: one clip, no other layers. It must keep working.
    val ms = 900L
    val file = source("simple.mp4", TestMp4Factory.Picture.COLORFUL, ms)
    val timeline = Timeline(
      videoClips = listOf(video("simple", file, durationMs = ms)),
      aspectRatio = AspectRatio.RATIO_9_16
    )
    val out = export(timeline, portraitConfig, "simple.mp4")
    val expected = ExportDimensionResolver.resolve(portraitConfig.resolution, timeline.aspectRatio)
    val validation = ExportValidator.validate(
      out, portraitConfig, ms, requireAudio = true, expectedDimensions = expected, sampleFrames = true
    )
    LookVerificationReport.line("simple validation: valid=${validation.valid} '${validation.message}'")
    assertTrue("the simple control export broke: ${validation.message}", validation.valid)
    assertTrue("the simple export is black", luma(frameAt(out, 400_000L)) > 0.05f)
  }

  @Test
  fun e01_multipleAudioTracksAndDifferentFiltersPerClipStillExport() {
    LookVerificationReport.line(">>> START e01_multipleAudioTracksAndDifferentFiltersPerClipStillExport")
    // Two explicit Phase-3 shapes the suites above only cover singly: several audio tracks mixed
    // into one soundtrack, and different filters on different clips of the same project. The source
    // is a saturated green-screen picture, so the monochrome look's desaturation is measurable
    // against the warm look's saturation on the very same source frame.
    val ms = 900L
    val sourceA = source("filters-a.mp4", TestMp4Factory.Picture.GREEN_SCREEN, ms)
    val sourceB = source("filters-b.mp4", TestMp4Factory.Picture.GREEN_SCREEN, ms)

    val timeline = Timeline(
      videoClips = listOf(
        video("filter-warm", sourceA, startMs = 0L, durationMs = ms, filter = FilterSettings(FilterType.GOLDEN_AUTUMN, 1f)),
        video("filter-mono", sourceB, startMs = ms, durationMs = ms, filter = FilterSettings(FilterType.BLACK_AND_WHITE, 1f))
      ),
      audioClips = listOf(
        AudioClip(
          id = "audio-bed", uri = android.net.Uri.fromFile(sourceA).toString(), title = "filters-a.mp4",
          timelineStartMs = 0L, durationMs = ms, sourceStartMs = 0L, sourceEndMs = ms,
          sourceTotalDurationMs = ms, volume = 0.8f, fadeInMs = 100L
        ),
        AudioClip(
          id = "audio-layer", uri = android.net.Uri.fromFile(sourceB).toString(), title = "filters-b.mp4",
          timelineStartMs = ms, durationMs = ms, sourceStartMs = 0L, sourceEndMs = ms,
          sourceTotalDurationMs = ms, volume = 0.5f, fadeOutMs = 150L
        )
      ),
      aspectRatio = AspectRatio.RATIO_9_16
    )

    val out = export(timeline, portraitConfig, "filters-and-audio.mp4")
    val expected = ExportDimensionResolver.resolve(portraitConfig.resolution, timeline.aspectRatio)
    val validation = ExportValidator.validate(
      out, portraitConfig, timeline.totalDurationMs,
      requireAudio = true, expectedDimensions = expected, sampleFrames = true, expectedRotation = 0
    )
    LookVerificationReport.line(
      "filters/audio validation: valid=${validation.valid} '${validation.message}' " +
        "size=${validation.width}x${validation.height} audioMime=${audioTrackMime(out)}"
    )
    assertTrue("two filters + two audio tracks were rejected: ${validation.message}", validation.valid)
    assertTrue("no mixed audio track in the export", audioTrackMime(out) != "none")

    // Same source content at the same source time in both clips: any difference is the filter.
    val warm = meanRgb(frameAt(out, 300_000L), 0.05f, 0.45f, 0.3f, 0.7f)
    val mono = meanRgb(frameAt(out, 1_200_000L), 0.05f, 0.45f, 0.3f, 0.7f)
    val warmSpread = warm.max() - warm.min()
    val monoSpread = mono.max() - mono.min()
    LookVerificationReport.line(
      "filtered subject half: golden=%.3f/%.3f/%.3f (spread %.3f) mono=%.3f/%.3f/%.3f (spread %.3f)".format(
        warm[0], warm[1], warm[2], warmSpread, mono[0], mono[1], mono[2], monoSpread
      )
    )
    assertTrue("the filtered clip is black: %.3f/%.3f/%.3f".format(warm[0], warm[1], warm[2]), warm.max() > 0.05f)
    // Different filters on different clips must reach the export as different pixels. Both clips read
    // the same source frame, so the difference cannot come from the source.
    val perChannelDelta = (0..2).maxOf { abs(warm[it] - mono[it]) }
    assertTrue(
      "both clips exported with the same look (max channel delta=%.3f)".format(perChannelDelta),
      perChannelDelta > 0.05f
    )
    // A monochrome look must drain more of the source's saturation than a warm colour grade does;
    // the source half is strongly green, so this only holds when the filters really ran.
    assertTrue(
      "BLACK_AND_WHITE did not desaturate the source (golden spread=%.3f, mono spread=%.3f)".format(warmSpread, monoSpread),
      monoSpread < warmSpread
    )
  }

  @Test
  fun d01_simpleProjectStillExportsAfterHeavyProjects() {
    LookVerificationReport.line(">>> START d01_simpleProjectStillExportsAfterHeavyProjects")
    // The report the bug is about is *comparative*: a simple project exports, a complex one does
    // not. This runs the simple project again, at the end, after the heavy exports above have opened
    // dozens of decoders, EGL contexts, FBOs and textures in this process. If a resource the export
    // does not release is what breaks the complex case, this control fails where the first one did
    // not - and the engine metrics printed by `export()` show how far it got.
    val ms = 900L
    val file = source("after-heavy.mp4", TestMp4Factory.Picture.COLORFUL, ms)
    val timeline = Timeline(
      videoClips = listOf(video("after-heavy", file, durationMs = ms)),
      aspectRatio = AspectRatio.RATIO_9_16
    )
    val out = export(timeline, portraitConfig, "after-heavy-out.mp4")
    val expected = ExportDimensionResolver.resolve(portraitConfig.resolution, timeline.aspectRatio)
    val validation = ExportValidator.validate(
      out, portraitConfig, ms, requireAudio = true, expectedDimensions = expected, sampleFrames = true
    )
    LookVerificationReport.line("post-heavy simple validation: valid=${validation.valid} '${validation.message}'")
    assertTrue("a simple export broke after the heavy ones: ${validation.message}", validation.valid)
    assertTrue("the post-heavy simple export is black", luma(frameAt(out, 400_000L)) > 0.05f)
  }
}
