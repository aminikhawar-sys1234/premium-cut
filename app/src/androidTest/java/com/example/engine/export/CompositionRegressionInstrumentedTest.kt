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
import com.example.domain.model.ChromaKeySettings
import com.example.domain.model.FilterSettings
import com.example.domain.model.FilterType
import com.example.domain.model.FrameRate
import com.example.domain.model.Resolution
import com.example.domain.model.Timeline
import com.example.domain.model.Transition
import com.example.domain.model.TransitionType
import com.example.domain.model.VideoAdjustments
import com.example.domain.model.VideoClip
import com.example.engine.composition.gpu.ShaderTransitionCompositor
import com.example.engine.effects.AndroidTestContextHolder
import com.example.engine.effects.LookGpuHarness
import com.example.engine.effects.LookVerificationReport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max

/**
 * Regression cover for the compositing features that sit next to the Filters / Adjust / Video
 * Quality patch, driven through the **real** export pipeline (MediaCodec source in, MediaCodec MP4
 * out, `AsyncFramePipelineEngine.export` -> `GpuCompositionRenderer.render`):
 *
 *  * audio still muxes and survives a graded export,
 *  * chroma key still replaces the keyed colour,
 *  * overlay clips are still composited onto the frame,
 *  * a timeline transition still blends the two clips it joins,
 *  * the exported frames are not black.
 *
 * Everything is measured from decoded output pixels rather than from internal state, so a wiring
 * regression (a uniform dropped, a layer skipped) fails here even when every unit test passes.
 */
@RunWith(AndroidJUnit4::class)
class CompositionRegressionInstrumentedTest {

  private lateinit var dir: File
  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
  private val config = ExportConfig(resolution = Resolution.RES_480P, frameRate = FrameRate.FPS_30)
  private val outSize = 320 to 240

  @Before
  fun setUp() {
    AndroidTestContextHolder.context = context
    dir = File(context.cacheDir, "composition-regression-${System.nanoTime()}").also { it.mkdirs() }
    LookVerificationReport.section("Composition regression (real exports, device)")
    LookVerificationReport.line("stamp=${LookVerificationReport.stamp()} dir=$dir")
  }

  @After
  fun tearDown() {
    runCatching { dir.deleteRecursively() }
  }

  // ---------------------------------------------------------------- helpers

  private fun source(name: String, picture: TestMp4Factory.Picture, ms: Long): File {
    val file = File(dir, name)
    try {
      TestMp4Factory.write(file, videoMs = ms, audioMs = ms, picture = picture)
    } catch (t: Throwable) {
      LookVerificationReport.line("could not build $name: ${t.javaClass.simpleName}: ${t.message}")
      assumeNoException(t)
    }
    return file
  }

  private fun clip(
    id: String,
    file: File,
    startMs: Long = 0L,
    durationMs: Long,
    filter: FilterSettings? = null,
    adjustments: VideoAdjustments? = null,
    hasAudio: Boolean = true
  ) = VideoClip(
    id = id,
    uri = file.toURI().toString(),
    name = file.name,
    timelineStartMs = startMs,
    durationMs = durationMs,
    sourceStartMs = 0L,
    sourceEndMs = durationMs,
    sourceTotalDurationMs = durationMs,
    width = TestMp4Factory.W,
    height = TestMp4Factory.H,
    frameRate = TestMp4Factory.FPS.toFloat(),
    hasAudio = hasAudio,
    filter = filter,
    adjustments = adjustments
  )

  private fun export(timeline: Timeline, name: String): File {
    val out = File(dir, name)
    try {
      runBlocking { AsyncFramePipelineEngine(context).export(timeline, config, out, sizeOverride = outSize) }
    } catch (t: Throwable) {
      LookVerificationReport.line("export $name failed: ${t.javaClass.simpleName}: ${t.message}")
      throw AssertionError("the export pipeline could not produce $name: ${t.message}", t)
    }
    return out
  }

  private fun frameAt(file: File, timeUs: Long, closest: Boolean = true): Bitmap {
    val retriever = MediaMetadataRetriever()
    try {
      retriever.setDataSource(file.absolutePath)
      val option = if (closest) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC
      val bitmap = retriever.getFrameAtTime(timeUs, option)
      assertTrue("no frame decoded from ${file.name} at ${timeUs}us", bitmap != null)
      return bitmap!!
    } finally {
      runCatching { retriever.release() }
    }
  }

  /** Mean RGB of a sub-rectangle given as fractions of the frame (defaults to the centre crop). */
  private fun meanRgb(
    bitmap: Bitmap,
    x0f: Float = 0.3f,
    x1f: Float = 0.7f,
    y0f: Float = 0.3f,
    y1f: Float = 0.7f
  ): FloatArray {
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

  // ---------------------------------------------------------------- 1. audio

  @Test
  fun gradedExportStillCarriesTheAudioTrack() {
    val ms = 1200L
    val src = source("audio-src.mp4", TestMp4Factory.Picture.COLORFUL, ms)
    val timeline = Timeline(
      videoClips = listOf(
        clip(
          "a", src, durationMs = ms,
          filter = FilterSettings(FilterType.GOLDEN_AUTUMN, 0.8f),
          adjustments = VideoAdjustments(brightness = 0.2f, saturation = 1.2f, sharpness = 0.3f)
        )
      ),
      aspectRatio = AspectRatio.RATIO_4_3
    )
    // What does the exporter itself think about this timeline's audio? Reported before the export so
    // a missing track can be attributed (source / mixer / muxer) instead of guessed at.
    val audio = DeviceAudioProbe.probeAndReport(context, timeline, ms, "graded audio")

    val out = export(timeline, "graded-audio.mp4")

    val validation = ExportValidator.validate(
      out, config, ms, requireAudio = audio.expectsAudioTrack, expectedDimensions = outSize,
      sampleFrames = true, expectedRotation = 0, pipelineFailure = null
    )
    LookVerificationReport.line(
      "graded-audio.mp4 valid=${validation.valid} message='${validation.message}' " +
        "video=${validation.videoCodec} audio=${validation.audioCodec} size=${out.length()}"
    )
    assertTrue("graded export rejected: ${validation.message}", validation.valid)

    // Independent of the validator: read the container with MediaExtractor.
    val extractor = MediaExtractor()
    var audioDurationUs = -1L
    var audioMime = "none"
    try {
      extractor.setDataSource(out.absolutePath)
      for (i in 0 until extractor.trackCount) {
        val format = extractor.getTrackFormat(i)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
        if (mime.startsWith("audio/")) {
          audioMime = mime
          if (format.containsKey(MediaFormat.KEY_DURATION)) audioDurationUs = format.getLong(MediaFormat.KEY_DURATION)
        }
      }
    } finally {
      runCatching { extractor.release() }
    }
    LookVerificationReport.line(
      "exported audio: mime=$audioMime duration=${if (audioDurationUs >= 0) audioDurationUs / 1000 else -1}ms " +
        "(source ${ms}ms)"
    )
    assertTrue(
      "the clip declares an audio track and the mixer produced ${audio.mixedSamples} samples " +
        "(peak ${audio.peak}), but the exported MP4 has no audio track",
      !audio.expectsAudioTrack || audioMime != "none"
    )
    if (audio.expectsAudioTrack && audioDurationUs > 0) {
      assertTrue(
        "audio duration $audioDurationUs us does not match the ${ms}ms timeline (audio/video desync)",
        abs(audioDurationUs / 1000L - ms) <= max(250L, ms / 4)
      )
    }
  }

  // ------------------------------------------------------------ 2. chroma key

  @Test
  fun chromaKeyStillReplacesTheKeyedColourInExport() {
    val ms = 800L
    val src = source("green-src.mp4", TestMp4Factory.Picture.GREEN_SCREEN, ms)
    val base = Timeline(
      videoClips = listOf(clip("green", src, durationMs = ms, hasAudio = false)),
      aspectRatio = AspectRatio.RATIO_4_3
    )
    val keyed = base.copy(
      chromaKey = ChromaKeySettings(
        enabled = true,
        targetColor = 0xFF00FF00,
        similarity = 0.5f,
        smoothness = 0.1f,
        spillSuppression = 0f,
        backgroundType = "SolidColor",
        backgroundColor = 0xFF0000FF
      )
    )

    val off = frameAt(export(base, "chroma-off.mp4"), 400_000L)
    val on = frameAt(export(keyed, "chroma-on.mp4"), 400_000L)

    // The subject (green) is the left half of the synthetic source; the right half is magenta.
    val offLeft = meanRgb(off, 0.08f, 0.42f, 0.2f, 0.8f)
    val onLeft = meanRgb(on, 0.08f, 0.42f, 0.2f, 0.8f)
    val offRight = meanRgb(off, 0.58f, 0.92f, 0.2f, 0.8f)
    val onRight = meanRgb(on, 0.58f, 0.92f, 0.2f, 0.8f)

    fun greenDominance(rgb: FloatArray) = rgb[1] - max(rgb[0], rgb[2])
    val dominanceOff = greenDominance(offLeft)
    val dominanceOn = greenDominance(onLeft)

    LookVerificationReport.line("chroma off: left=${format(offLeft)} right=${format(offRight)}")
    LookVerificationReport.line("chroma on : left=${format(onLeft)} right=${format(onRight)}")
    LookVerificationReport.line(
      "green dominance left: off=%.4f on=%.4f (delta %.4f)".format(dominanceOff, dominanceOn, dominanceOff - dominanceOn)
    )
    LookVerificationReport.line(
      "right half change (must not be keyed): %.4f".format(
        abs(offRight[0] - onRight[0]) + abs(offRight[1] - onRight[1]) + abs(offRight[2] - onRight[2])
      )
    )

    assertTrue(
      "the synthetic green screen is not green to begin with (dominance $dominanceOff) - test source is wrong",
      dominanceOff > 0.15f
    )
    assertTrue(
      "chroma key did not remove the green: dominance went from $dominanceOff to $dominanceOn",
      dominanceOn < dominanceOff - 0.1f
    )
  }

  // -------------------------------------------------------------- 3. overlays

  @Test
  fun overlayClipsAreStillCompositedInExport() {
    val ms = 800L
    val src = source("overlay-base.mp4", TestMp4Factory.Picture.COLORFUL, ms)
    val png = File(dir, "overlay.png")
    val bitmap = Bitmap.createBitmap(TestMp4Factory.W, TestMp4Factory.H, Bitmap.Config.ARGB_8888)
    Canvas(bitmap).drawRect(
      0f, 0f, TestMp4Factory.W.toFloat(), TestMp4Factory.H.toFloat(),
      Paint().apply { color = Color.RED }
    )
    FileOutputStream(png).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()

    val base = Timeline(
      videoClips = listOf(clip("base", src, durationMs = ms)),
      aspectRatio = AspectRatio.RATIO_4_3
    )
    val withOverlay = base.copy(
      overlayClips = listOf(
        VideoClip(
          id = "overlay",
          uri = png.toURI().toString(),
          name = "overlay.png",
          isVideo = false,
          timelineStartMs = 0L,
          durationMs = ms,
          sourceEndMs = ms,
          width = TestMp4Factory.W,
          height = TestMp4Factory.H,
          hasAudio = false,
          opacity = 1f
        )
      )
    )

    val without = frameAt(export(base, "overlay-none.mp4"), 400_000L)
    val with = frameAt(export(withOverlay, "overlay-on.mp4"), 400_000L)

    val withoutMean = meanRgb(without, 0.05f, 0.95f, 0.05f, 0.95f)
    val withMean = meanRgb(with, 0.05f, 0.95f, 0.05f, 0.95f)
    val delta = abs(withoutMean[0] - withMean[0]) + abs(withoutMean[1] - withMean[1]) + abs(withoutMean[2] - withMean[2])

    LookVerificationReport.line("overlay off: mean=${format(withoutMean)}")
    LookVerificationReport.line("overlay on : mean=${format(withMean)} (red square #FF0000 over the whole frame)")
    LookVerificationReport.line("overlay frame delta = %.4f".format(delta))

    assertTrue(
      "the overlay clip was not composited: with/without frames differ by only $delta",
      delta > 0.05f
    )
    assertTrue(
      "the red overlay did not raise the red channel (off=${withoutMean[0]}, on=${withMean[0]})",
      withMean[0] > withoutMean[0] + 0.05f
    )
    assertTrue("the exported frame with an overlay is black", luma(with) > 0.05f)
  }

  // ------------------------------------------------------------ 4. transitions

  @Test
  fun transitionBetweenClipsStillBlendsInExport() {
    val ms = 1000L
    val bright = source("transition-bright.mp4", TestMp4Factory.Picture.COLORFUL, ms)
    val dark = source("transition-dark.mp4", TestMp4Factory.Picture.BLACK, ms)
    val timeline = Timeline(
      videoClips = listOf(
        clip("bright", bright, startMs = 0L, durationMs = ms),
        clip("dark", dark, startMs = ms, durationMs = ms)
      ),
      // Window: 750ms .. 1250ms (clipBefore.durationMs - durationMs / 2 .. + durationMs).
      transitions = listOf(Transition(clipIndexBefore = 0, type = TransitionType.FADE, durationMs = 500L)),
      aspectRatio = AspectRatio.RATIO_4_3
    )
    val out = export(timeline, "transition.mp4")

    val beforeLuma = luma(frameAt(out, 600_000L))
    val midLuma = luma(frameAt(out, 1_000_000L))
    val afterLuma = luma(frameAt(out, 1_450_000L))

    LookVerificationReport.line(
      "transition fades: before(600ms)=%.4f mid(1000ms)=%.4f after(1450ms)=%.4f".format(
        beforeLuma, midLuma, afterLuma
      )
    )

    assertTrue("the first clip is already black ($beforeLuma)", beforeLuma > 0.15f)
    assertTrue("the second clip is not dark ($afterLuma)", afterLuma < 0.2f)

    // The pixel blend is done by ShaderTransitionCompositor, which is GLES3-only and returns false
    // on an ES2 context (the caller then keeps its legacy hard cut - by design). Ask a real GL
    // context on this device which of the two paths can run before judging the exported pixels.
    val shaderTransitions = runCatching {
      LookGpuHarness().use { harness ->
        harness.bindFbo()
        ShaderTransitionCompositor().isAvailable()
      }
    }.getOrElse { false }
    LookVerificationReport.line(
      "shader transitions available on this device = $shaderTransitions " +
        "(false => the exporter uses the legacy hard cut by design)"
    )
    if (!shaderTransitions) {
      LookVerificationReport.line(
        "FADE blend assertions skipped on this device: the ES3 transition engine is unavailable, " +
          "so a hard cut at the clip boundary is the documented fallback (measured mid=$midLuma)"
      )
      return
    }
    assertTrue(
      "the 500ms FADE did not blend: mid=$midLuma vs before=$beforeLuma",
      midLuma < beforeLuma - 0.05f
    )
    assertTrue(
      "the 500ms FADE did not blend: mid=$midLuma vs after=$afterLuma",
      midLuma > afterLuma + 0.05f
    )
  }
}
