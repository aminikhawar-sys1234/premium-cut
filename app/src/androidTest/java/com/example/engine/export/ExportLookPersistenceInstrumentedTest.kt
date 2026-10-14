package com.example.engine.export

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.local.TimelineSerializer
import com.example.domain.model.AspectRatio
import com.example.domain.model.FilterSettings
import com.example.domain.model.FilterType
import com.example.domain.model.FrameRate
import com.example.domain.model.Resolution
import com.example.domain.model.Timeline
import com.example.domain.model.VideoAdjustments
import com.example.domain.model.VideoClip
import com.example.engine.effects.AndroidTestContextHolder
import com.example.engine.effects.ColorLookModel
import com.example.engine.effects.LookGpuHarness
import com.example.engine.effects.LookVerificationReport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * End-to-end device test of the real export pipeline:
 *
 *  source MP4 (MediaCodec) -> project save + reopen (TimelineSerializer) ->
 *  `AsyncFramePipelineEngine.export` -> exported MP4 decoded again -> frames compared with the
 *  GPU rendering of the same look.
 *
 * This is the check that the clip-local Filters / Adjust look reaches the encoded file, that it
 * survives a save / reopen round trip, that audio is still muxed, and that the exported frames
 * match the preview-side GPU rendering within codec tolerance.
 */
@RunWith(AndroidJUnit4::class)
class ExportLookPersistenceInstrumentedTest {

  private lateinit var dir: File
  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
  private val config = ExportConfig(resolution = Resolution.RES_480P, frameRate = FrameRate.FPS_30)

  private val outSize = 320 to 240
  private val durationMs = 1000L

  @Before
  fun setUp() {
    AndroidTestContextHolder.context = context
    dir = File(context.cacheDir, "look-export-${System.nanoTime()}").also { it.mkdirs() }
    LookVerificationReport.section("Real MP4 export (device)")
    LookVerificationReport.line("stamp=${LookVerificationReport.stamp()} dir=$dir")
  }

  @After
  fun tearDown() {
    runCatching { dir.deleteRecursively() }
  }

  private fun clipFor(source: File, graded: Boolean): VideoClip = VideoClip(
    id = if (graded) "graded" else "neutral",
    uri = source.toURI().toString(),
    name = source.name,
    durationMs = durationMs,
    sourceStartMs = 0L,
    sourceEndMs = durationMs,
    sourceTotalDurationMs = durationMs,
    width = 320,
    height = 240,
    frameRate = 15f,
    hasAudio = true,
    filter = if (graded) FilterSettings(FilterType.GOLDEN_AUTUMN, 1f) else null,
    adjustments = if (graded) {
      VideoAdjustments(brightness = 0.25f, saturation = 1.35f)
    } else {
      VideoAdjustments()
    }
  )

  private fun meanRgbOf(bitmap: Bitmap): FloatArray {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    // Centre crop: ignores any padding / letterboxing the compositor may add.
    val x0 = (w * 0.3f).toInt()
    val x1 = (w * 0.7f).toInt()
    val y0 = (h * 0.3f).toInt()
    val y1 = (h * 0.7f).toInt()
    val sums = FloatArray(3)
    var count = 0
    for (y in y0 until y1) for (x in x0 until x1) {
      val p = pixels[y * w + x]
      sums[0] += android.graphics.Color.red(p) / 255f
      sums[1] += android.graphics.Color.green(p) / 255f
      sums[2] += android.graphics.Color.blue(p) / 255f
      count++
    }
    val n = count.coerceAtLeast(1).toFloat()
    return floatArrayOf(sums[0] / n, sums[1] / n, sums[2] / n)
  }

  private fun frameAt(file: File, timeUs: Long): Bitmap {
    val retriever = MediaMetadataRetriever()
    try {
      retriever.setDataSource(file.absolutePath)
      val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
      assertNotNull("no frame decoded from ${file.name} at ${timeUs}us", bitmap)
      return bitmap!!
    } finally {
      runCatching { retriever.release() }
    }
  }

  private fun rgbaOf(bitmap: Bitmap, size: Int): ByteArray {
    val scaled = Bitmap.createScaledBitmap(bitmap, size, size, true)
    val pixels = IntArray(size * size)
    scaled.getPixels(pixels, 0, size, 0, 0, size, size)
    val out = ByteArray(size * size * 4)
    var i = 0
    for (p in pixels) {
      out[i++] = android.graphics.Color.red(p).toByte()
      out[i++] = android.graphics.Color.green(p).toByte()
      out[i++] = android.graphics.Color.blue(p).toByte()
      out[i++] = 0xFF.toByte()
    }
    return out
  }

  /** The look the export renderer resolves for a clip (same call `bindCommonUniforms` makes). */
  private fun lookOf(clip: VideoClip): ColorLookModel.LookParams {
    val filter = clip.filter ?: FilterSettings()
    val pair = ColorLookModel.presetMatrix(filter)?.let { ColorLookModel.filterGlMatrix(it) }
    return ColorLookModel.effective(clip.adjustments ?: VideoAdjustments(), filter, pair?.first, pair?.second)
  }

  @Test
  fun clipLookSurvivesSaveReopenAndReachesTheExportedMp4() {
    val source = File(dir, "source.mp4")
    try {
      TestMp4Factory.write(source, videoMs = durationMs, audioMs = durationMs)
    } catch (t: Throwable) {
      LookVerificationReport.line("could not build the source MP4: ${t.javaClass.simpleName}: ${t.message}")
      assumeNoException(t)
    }

    val gradedTimeline = Timeline(
      videoClips = listOf(clipFor(source, graded = true)),
      aspectRatio = AspectRatio.RATIO_4_3
    )
    val neutralTimeline = Timeline(
      videoClips = listOf(clipFor(source, graded = false)),
      aspectRatio = AspectRatio.RATIO_4_3
    )

    // ---- save + reopen, exactly like project persistence does -------------------------------
    val json = TimelineSerializer.serializeTimeline(gradedTimeline)
    val reopenedOrNull = TimelineSerializer.fromJsonOrNull(json)
    assertNotNull("the saved project could not be reopened", reopenedOrNull)
    val restored: Timeline = reopenedOrNull!!
    val reopenedClip = restored.videoClips.single()
    LookVerificationReport.line(
      "reopened clip: filter=${reopenedClip.filter?.type}/${reopenedClip.filter?.intensity} " +
        "enabled=${reopenedClip.filter?.enabled} brightness=${reopenedClip.adjustments?.brightness} " +
        "saturation=${reopenedClip.adjustments?.saturation}"
    )
    assertEquals(FilterType.GOLDEN_AUTUMN, reopenedClip.filter?.type)
    assertEquals(1f, reopenedClip.filter?.intensity ?: 0f, 0.0001f)
    assertTrue("filter.enabled did not survive the round trip", reopenedClip.filter?.enabled == true)
    assertEquals(0.25f, reopenedClip.adjustments?.brightness ?: 0f, 0.0001f)
    assertEquals(1.35f, reopenedClip.adjustments?.saturation ?: 0f, 0.0001f)

    // ---- two real exports through the app's own pipeline ------------------------------------
    val gradedFile = File(dir, "graded.mp4")
    val neutralFile = File(dir, "neutral.mp4")
    try {
      runBlocking {
        AsyncFramePipelineEngine(context).export(restored, config, gradedFile, sizeOverride = outSize)
      }
      runBlocking {
        AsyncFramePipelineEngine(context).export(neutralTimeline, config, neutralFile, sizeOverride = outSize)
      }
    } catch (t: Throwable) {
      LookVerificationReport.line("device export failed: ${t.javaClass.simpleName}: ${t.message}")
      throw AssertionError("the export pipeline could not produce an MP4 on this device: ${t.message}", t)
    }

    // Should this export carry audio at all? Reported from the exporter's own decision, so a missing
    // track is evidence rather than a guess.
    val audio = DeviceAudioProbe.probeAndReport(context, restored, durationMs, "look export")

    val gradedValidation = ExportValidator.validate(
      gradedFile, config, durationMs, requireAudio = audio.expectsAudioTrack,
      expectedDimensions = outSize,
      sampleFrames = true, expectedRotation = 0, pipelineFailure = null
    )
    val neutralValidation = ExportValidator.validate(
      neutralFile, config, durationMs, requireAudio = audio.expectsAudioTrack,
      expectedDimensions = outSize,
      sampleFrames = true, expectedRotation = 0, pipelineFailure = null
    )
    LookVerificationReport.line(
      "graded.mp4 valid=${gradedValidation.valid} message='${gradedValidation.message}' " +
        "size=${gradedFile.length()}"
    )
    LookVerificationReport.line(
      "neutral.mp4 valid=${neutralValidation.valid} message='${neutralValidation.message}' " +
        "size=${neutralFile.length()}"
    )
    assertTrue("graded export rejected: ${gradedValidation.message}", gradedValidation.valid)
    assertTrue("neutral export rejected: ${neutralValidation.message}", neutralValidation.valid)

    // ---- decode the frames and compare against the GPU rendering of the same look ------------
    val sourceFrame = frameAt(source, 500_000L)
    val gradedFrame = frameAt(gradedFile, 500_000L)
    val neutralFrame = frameAt(neutralFile, 500_000L)

    val sourceMean = meanRgbOf(sourceFrame)
    val gradedMean = meanRgbOf(gradedFrame)
    val neutralMean = meanRgbOf(neutralFrame)

    val harness = LookGpuHarness(LookGpuHarness.DEFAULT_SIZE, rgbaOf(sourceFrame, LookGpuHarness.DEFAULT_SIZE))
    val predicted = try {
      val program = harness.buildExportProgram()
      assertTrue("export program failed to build on this device", program.ok)
      val graded = harness.render(program, lookOf(reopenedClip), 1f, preview = false)
      val neutral = harness.render(program, lookOf(clipFor(source, graded = false)), 1f, preview = false)
      harness.deleteProgram(program)
      arrayOf(meanOf(graded), meanOf(neutral))
    } finally {
      harness.close()
    }

    val exportDelta = FloatArray(3) { gradedMean[it] - neutralMean[it] }
    val gpuDelta = FloatArray(3) { predicted[0][it] - predicted[1][it] }
    val perChannelError = FloatArray(3) { abs(exportDelta[it] - gpuDelta[it]) }

    LookVerificationReport.line(
      "source mean rgb   = %.4f/%.4f/%.4f".format(sourceMean[0], sourceMean[1], sourceMean[2])
    )
    LookVerificationReport.line(
      "neutral export    = %.4f/%.4f/%.4f".format(neutralMean[0], neutralMean[1], neutralMean[2])
    )
    LookVerificationReport.line(
      "graded export     = %.4f/%.4f/%.4f".format(gradedMean[0], gradedMean[1], gradedMean[2])
    )
    LookVerificationReport.line(
      "export delta      = %+.4f/%+.4f/%+.4f".format(exportDelta[0], exportDelta[1], exportDelta[2])
    )
    LookVerificationReport.line(
      "gpu-predicted delta = %+.4f/%+.4f/%+.4f (max channel error %.4f)".format(
        gpuDelta[0], gpuDelta[1], gpuDelta[2],
        maxOf(perChannelError[0], maxOf(perChannelError[1], perChannelError[2]))
      )
    )
    LookVerificationReport.line(
      "neutral export vs decoded source = %.4f/%.4f/%.4f (codec + colour-space tolerance)".format(
        abs(neutralMean[0] - sourceMean[0]),
        abs(neutralMean[1] - sourceMean[1]),
        abs(neutralMean[2] - sourceMean[2])
      )
    )

    val lookReachedTheFile = abs(exportDelta[0]) + abs(exportDelta[1]) + abs(exportDelta[2])
    assertTrue(
      "the graded export is indistinguishable from the neutral export (delta $lookReachedTheFile)",
      lookReachedTheFile > 0.05f
    )
    for (channel in 0..2) {
      assertTrue(
        "export delta for channel $channel (${exportDelta[channel]}) does not match the GPU " +
          "rendering (${gpuDelta[channel]}); error ${perChannelError[channel]}",
        perChannelError[channel] <= 0.10f
      )
    }
    for (channel in 0..2) {
      assertTrue(
        "neutral export channel $channel differs from the decoded source by " +
          "${abs(neutralMean[channel] - sourceMean[channel])}",
        abs(neutralMean[channel] - sourceMean[channel]) <= 0.12f
      )
    }
  }

  private fun meanOf(frame: FloatArray): FloatArray {
    val sums = FloatArray(3)
    var i = 0
    while (i < frame.size) {
      sums[0] += frame[i]
      sums[1] += frame[i + 1]
      sums[2] += frame[i + 2]
      i += 3
    }
    val n = (frame.size / 3).toFloat()
    return floatArrayOf(sums[0] / n, sums[1] / n, sums[2] / n)
  }
}
