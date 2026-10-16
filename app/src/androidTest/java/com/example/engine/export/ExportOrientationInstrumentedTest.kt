package com.example.engine.export

import android.graphics.ImageFormat
import android.graphics.Rect
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.domain.model.AspectRatio
import com.example.domain.model.FrameRate
import com.example.domain.model.Resolution
import com.example.domain.model.Timeline
import com.example.domain.model.VideoClip
import com.example.engine.effects.AndroidTestContextHolder
import com.example.engine.effects.LookVerificationReport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * End-to-end orientation cover for the "export comes out upside down / rotated" report.
 *
 * Every other export test in this package reads a single scalar (audio duration, a chroma colour,
 * "is it black"), which cannot tell upright from upside down. This test uses a source whose four
 * quadrants carry four *different* luma levels ([TestMp4Factory.Picture.ORIENTATION_QUADRANTS]),
 * re-decodes the exported MP4 with MediaExtractor + MediaCodec and reads the Y plane, so the
 * exported picture can be classified against all eight dihedral transforms (four rotations x the
 * horizontal mirror). Only one of them is upright, so a single stray Y flip, an unapplied rotation
 * hint or a mirrored frame fails here with the observed arrangement in the message.
 *
 * Covered: portrait 9:16, landscape 16:9, square 1:1, sources carrying 0/90/180/270 rotation
 * metadata, a letterboxed (aspect mismatch) export, a horizontally mirrored clip (the GPU path a
 * front-camera recording takes) and the container contract itself (dimensions + rotation hint =
 * [ExportOrientationPolicy.MUXER_ORIENTATION_HINT_DEGREES], i.e. 0 because the orientation is baked
 * into the pixels).
 */
@RunWith(AndroidJUnit4::class)
class ExportOrientationInstrumentedTest {

  private companion object {
    /** Same four levels as [TestMp4Factory.ORIENTATION_LUMA]: top-left, top-right, bottom-left, bottom-right. */
    val Q = TestMp4Factory.ORIENTATION_LUMA

    /**
     * Index permutations of (tl, tr, bl, br) for every dihedral transform of the coded frame.
     * "rot*" = the coded frame rotated clockwise by that much, "rot*+mirrorX" = that rotation then
     * mirrored horizontally. The exported picture must classify as the `rot<metadata>` variant.
     */
    val VARIANTS: List<Pair<String, IntArray>> = listOf(
      "rot0" to intArrayOf(0, 1, 2, 3),
      "rot90" to intArrayOf(2, 0, 3, 1),
      "rot180" to intArrayOf(3, 2, 1, 0),
      "rot270" to intArrayOf(1, 3, 0, 2),
      "rot0+mirrorX" to intArrayOf(1, 0, 3, 2),
      "rot90+mirrorX" to intArrayOf(0, 2, 1, 3),
      "rot180+mirrorX" to intArrayOf(2, 3, 0, 1),
      "rot270+mirrorX" to intArrayOf(3, 1, 2, 0)
    )

    /** Quadrant levels are >= 60 apart; the compositor's colour round trip moves them by a few. */
    const val MATCH_TOLERANCE = 30
    /** The runner-up transform must be clearly worse, otherwise the measurement is ambiguous. */
    const val MATCH_MARGIN = 20
    const val MS = 1200L
  }

  private lateinit var dir: File
  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
  private val config = ExportConfig(resolution = Resolution.RES_480P, frameRate = FrameRate.FPS_30)

  @Before
  fun setUp() {
    AndroidTestContextHolder.context = context
    dir = File(context.cacheDir, "export-orientation-${System.nanoTime()}").also { it.mkdirs() }
    LookVerificationReport.section("Export orientation (real export + re-decode, device)")
    LookVerificationReport.line("stamp=${LookVerificationReport.stamp()} dir=$dir")
  }

  @After
  fun tearDown() {
    runCatching { dir.deleteRecursively() }
  }

  // ------------------------------------------------------------------ cases

  private data class OrientationCase(
    val label: String,
    val codedW: Int,
    val codedH: Int,
    val sourceRotation: Int,
    val aspect: AspectRatio,
    val outW: Int,
    val outH: Int,
    val mirrorX: Boolean = false
  )

  private val cases = listOf(
    // Coded sizes are 16-aligned and the canvas matches the *display* aspect exactly, so no decoder
    // or encoder padding can move the quadrant borders the measurement samples.
    OrientationCase("portrait 9:16, metadata 0", 576, 1024, 0, AspectRatio.RATIO_9_16, 288, 512),
    OrientationCase("portrait 9:16, metadata 180", 576, 1024, 180, AspectRatio.RATIO_9_16, 288, 512),
    OrientationCase("landscape 16:9, metadata 0", 1024, 576, 0, AspectRatio.RATIO_16_9, 512, 288),
    OrientationCase("landscape 16:9, metadata 180", 1024, 576, 180, AspectRatio.RATIO_16_9, 512, 288),
    OrientationCase("landscape source + metadata 90, portrait canvas", 1024, 576, 90, AspectRatio.RATIO_9_16, 288, 512),
    OrientationCase("landscape source + metadata 270, portrait canvas", 1024, 576, 270, AspectRatio.RATIO_9_16, 288, 512),
    OrientationCase("portrait source + metadata 90, landscape canvas", 576, 1024, 90, AspectRatio.RATIO_16_9, 512, 288),
    OrientationCase("portrait source + metadata 270, landscape canvas", 576, 1024, 270, AspectRatio.RATIO_16_9, 512, 288),
    OrientationCase("square 1:1, metadata 0", 640, 640, 0, AspectRatio.RATIO_1_1, 320, 320),
    OrientationCase("square 1:1, metadata 90", 640, 640, 90, AspectRatio.RATIO_1_1, 320, 320),
    OrientationCase("square 1:1, metadata 180", 640, 640, 180, AspectRatio.RATIO_1_1, 320, 320),
    OrientationCase("square 1:1, metadata 270", 640, 640, 270, AspectRatio.RATIO_1_1, 320, 320),
    // Aspect mismatch on purpose: the picture is letterboxed inside the canvas, which is where a
    // flipped fit or a swapped crop axis would show up.
    OrientationCase("portrait source into landscape canvas", 576, 1024, 0, AspectRatio.RATIO_16_9, 512, 288),
    // The GPU mirror path a front-camera recording takes when mirroring is enabled for the clip.
    OrientationCase("portrait source, mirrored clip", 576, 1024, 0, AspectRatio.RATIO_9_16, 288, 512, mirrorX = true)
  )

  @Test
  fun exportedFrameKeepsTheSourcesOrientation() {
    var measured = 0
    for (case in cases) {
      val source = File(dir, "src-${case.label.replace(Regex("[^A-Za-z0-9]+"), "-")}.mp4")
      try {
        TestMp4Factory.write(
          source,
          videoMs = MS,
          audioMs = null,
          picture = TestMp4Factory.Picture.ORIENTATION_QUADRANTS,
          rotation = case.sourceRotation,
          width = case.codedW,
          height = case.codedH
        )
      } catch (t: Throwable) {
        LookVerificationReport.line("could not build the ${case.label} source: ${t.message}")
        continue
      }

      val clip = VideoClip(
        id = "c-${case.label.hashCode()}",
        uri = android.net.Uri.fromFile(source).toString(),
        name = source.name,
        timelineStartMs = 0L,
        durationMs = MS,
        sourceStartMs = 0L,
        sourceEndMs = MS,
        sourceTotalDurationMs = MS,
        width = case.codedW,
        height = case.codedH,
        frameRate = TestMp4Factory.FPS.toFloat(),
        hasAudio = false,
        naturalRotation = case.sourceRotation,
        flipHorizontal = case.mirrorX
      )
      val timeline = Timeline(videoClips = listOf(clip), aspectRatio = case.aspect)
      val out = File(dir, "out-${case.outW}x${case.outH}-${case.sourceRotation}-${case.mirrorX}.mp4")

      try {
        runBlocking {
          AsyncFramePipelineEngine(context).export(timeline, config, out, sizeOverride = case.outW to case.outH)
        }
      } catch (t: Throwable) {
        LookVerificationReport.line("export failed for ${case.label}: ${t.javaClass.simpleName}: ${t.message}")
        assumeNoException(t)
      }

      // 1. Container contract first: a rotation hint must not come back on top of baked-in pixels.
      val container = containerContract(out)
      LookVerificationReport.line(
        "${case.label}: container ${container.width}x${container.height} rotation=${container.rotation}"
      )
      assertEquals("the exported MP4 reports the wrong width (${case.label})", case.outW, container.width)
      assertEquals("the exported MP4 reports the wrong height (${case.label})", case.outH, container.height)
      assertEquals(
        "the exported MP4 carries rotation metadata ${container.rotation}; the orientation is baked " +
          "into the pixels, so a nonzero hint rotates the picture twice in a player (${case.label})",
        ExportOrientationPolicy.MUXER_ORIENTATION_HINT_DEGREES, container.rotation
      )

      // 2. Preview/export parity, asserted on the values the two destinations actually consume.
      assertEquals(
        "preview and export blit the composite with a different Y flip (${case.label})",
        com.example.engine.composition.CompositionRenderGraph.PREVIEW.flipY,
        ExportOrientationPolicy.FLIP_Y_FOR_ENCODER
      )

      // 3. The picture itself, read back from the decoded Y plane.
      val expected = ExportOrientationPolicy.displaySize(case.codedW, case.codedH, case.sourceRotation)
      val box = pictureBox(case.outW, case.outH, expected.first, expected.second)
      val observed = quadrants(out, box)
      if (observed == null) {
        LookVerificationReport.line("${case.label}: decoder produced no frame")
        continue
      }
      measured++
      val expectedVariant = "rot${case.sourceRotation}" + if (case.mirrorX) "+mirrorX" else ""
      val classification = classify(observed)
      LookVerificationReport.line(
        "${case.label}: observed=[${observed.joinToString(",")}] classified=${classification.first} " +
          "(distance ${classification.second}) expected=$expectedVariant"
      )
      val message =
        "exported frame is '${classification.first}' (quadrant luma [${observed.joinToString(",")}]) " +
          "instead of '$expectedVariant' for ${case.label}"
      assertEquals(message, expectedVariant, classification.first)
      assertTrue("the measured frame is ambiguous: $message", classification.second <= MATCH_TOLERANCE)
    }
    assertTrue(
      "no exported frame could be measured on this device, so the orientation claim is unverified",
      measured > 0
    )
  }

  // -------------------------------------------------------------- measurement

  private class Container(val width: Int, val height: Int, val rotation: Int)

  private fun containerContract(file: File): Container {
    val extractor = MediaExtractor()
    try {
      extractor.setDataSource(file.absolutePath)
      for (i in 0 until extractor.trackCount) {
        val format = extractor.getTrackFormat(i)
        if ((format.getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")) {
          return Container(
            format.getInteger(MediaFormat.KEY_WIDTH),
            format.getInteger(MediaFormat.KEY_HEIGHT),
            if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
          )
        }
      }
    } catch (t: Throwable) {
      LookVerificationReport.line("could not read ${file.name}: ${t.javaClass.simpleName}: ${t.message}")
      assumeNoException(t)
    } finally {
      runCatching { extractor.release() }
    }
    throw AssertionError("${file.name} has no video track")
  }

  /** The contain-fit rectangle the compositor draws the picture into (same math as render()). */
  private fun pictureBox(canvasW: Int, canvasH: Int, dispW: Int, dispH: Int): Rect {
    val displayAspect = dispW.toFloat() / maxOf(1, dispH)
    val canvasAspect = canvasW.toFloat() / maxOf(1, canvasH)
    val boxW: Int
    val boxH: Int
    if (displayAspect > canvasAspect) {
      boxW = canvasW
      boxH = Math.round(canvasW / displayAspect)
    } else {
      boxH = canvasH
      boxW = Math.round(canvasH * displayAspect)
    }
    val left = (canvasW - boxW) / 2
    val top = (canvasH - boxH) / 2
    return Rect(left, top, left + boxW, top + boxH)
  }

  /**
   * Mean luma of the four quarters of [box] (tl, tr, bl, br), read from the first decodable frame of
   * [file]. The box is the picture inside the canvas, so the sample points stay 25% away from every
   * quadrant border and are unaffected by letterbox bars.
   */
  private fun quadrants(file: File, box: Rect): IntArray? {
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    var reader: ImageReader? = null
    var surface: Surface? = null
    try {
      extractor.setDataSource(file.absolutePath)
      var track = -1
      var format: MediaFormat? = null
      for (i in 0 until extractor.trackCount) {
        val f = extractor.getTrackFormat(i)
        if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")) {
          track = i
          format = f
          break
        }
      }
      val videoFormat = format ?: return null
      if (track < 0) return null
      extractor.selectTrack(track)

      val w = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
      val h = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
      reader = ImageReader.newInstance(w, h, ImageFormat.YUV_420_888, 2)
      surface = reader.surface
      codec = MediaCodec.createDecoderByType(videoFormat.getString(MediaFormat.KEY_MIME)!!)
      codec.configure(videoFormat, surface, null, 0)
      codec.start()

      val info = MediaCodec.BufferInfo()
      var sawInputEos = false
      var lastPtsUs = -1L
      val deadline = System.nanoTime() + 20_000_000_000L
      while (System.nanoTime() < deadline) {
        if (!sawInputEos) {
          val inIdx = codec.dequeueInputBuffer(10_000)
          if (inIdx >= 0) {
            val buf = codec.getInputBuffer(inIdx)!!
            val size = extractor.readSampleData(buf, 0)
            if (size < 0) {
              codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
              sawInputEos = true
            } else {
              codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
              extractor.advance()
            }
          }
        }
        val outIdx = codec.dequeueOutputBuffer(info, 10_000)
        if (outIdx >= 0) {
          lastPtsUs = info.presentationTimeUs
          codec.releaseOutputBuffer(outIdx, true)
          if (info.presentationTimeUs >= 200_000L) break
        }
      }
      if (lastPtsUs < 0) return null

      // Give the renderer thread a moment to fill the ImageReader, then read the newest image.
      val img = acquire(reader, deadline) ?: return null
      try {
        return intArrayOf(
          regionLuma(img, box, 0.25f, 0.25f),
          regionLuma(img, box, 0.75f, 0.25f),
          regionLuma(img, box, 0.25f, 0.75f),
          regionLuma(img, box, 0.75f, 0.75f)
        )
      } finally {
        img.close()
      }
    } catch (t: Throwable) {
      LookVerificationReport.line(
        "could not decode ${file.name} for measurement: ${t.javaClass.simpleName}: ${t.message}"
      )
      assumeNoException(t)
      return null
    } finally {
      runCatching { codec?.stop() }
      runCatching { codec?.release() }
      runCatching { surface?.release() }
      runCatching { reader?.close() }
      runCatching { extractor.release() }
    }
  }

  private fun acquire(reader: ImageReader, deadline: Long): Image? {
    while (System.nanoTime() < deadline) {
      val i = reader.acquireLatestImage()
      if (i != null) return i
      Thread.sleep(10)
    }
    return null
  }

  /** Mean luma of a 5x5 patch centred on the (fx, fy) fraction of [box], from the decoded Y plane. */
  private fun regionLuma(image: Image, box: Rect, fx: Float, fy: Float): Int {
    val plane = image.planes[0]
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val cx = box.left + (box.width() * fx).toInt()
    val cy = box.top + (box.height() * fy).toInt()
    var sum = 0
    var n = 0
    for (dy in -2..2) for (dx in -2..2) {
      val x = (cx + dx).coerceIn(0, image.width - 1)
      val y = (cy + dy).coerceIn(0, image.height - 1)
      sum += buffer.get(y * rowStride + x * pixelStride).toInt() and 0xFF
      n++
    }
    return sum / n
  }

  /** Nearest of the eight dihedral transforms; returns the label and the distance to it. */
  private fun classify(observed: IntArray): Pair<String, Int> {
    var best = ""
    var bestDistance = Int.MAX_VALUE
    var runnerUp = Int.MAX_VALUE
    for ((name, order) in VARIANTS) {
      var d = 0
      for (i in 0..3) d += abs(Q[order[i]] - observed[i])
      if (d < bestDistance) {
        runnerUp = bestDistance
        bestDistance = d
        best = name
      } else if (d < runnerUp) {
        runnerUp = d
      }
    }
    val margin = runnerUp - bestDistance
    return best to if (margin < MATCH_MARGIN) bestDistance + (MATCH_MARGIN - margin) else bestDistance
  }
}
