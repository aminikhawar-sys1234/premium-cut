package com.example.engine.export

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ahstudio.face.core.TrackedFace
import com.ahstudio.face.deformation.DeformationParams
import com.ahstudio.face.deformation.FaceWarpFaceSource
import com.ahstudio.face.deformation.FaceWarpRegistry
import com.ahstudio.face.overlay.ArOverlayParams
import com.ahstudio.face.overlay.ArOverlayRegistry
import com.ahstudio.face.tracking.ClipFaceTracker
import com.example.domain.model.AspectRatio
import com.example.domain.model.FrameRate
import com.example.domain.model.Resolution
import com.example.domain.model.Timeline
import com.example.domain.model.VideoClip
import com.example.engine.ai.MotionFaceFallbackSource
import com.example.engine.effects.AndroidTestContextHolder
import com.example.engine.effects.LookVerificationReport
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The export path of a project with a **tracked effect** (face reshape, AR filter, body reshape).
 *
 * This is the shape the report lists as "motion tracking" and the suites next door do not cover: the
 * compositor asks the tracking source for a pose for *every exported frame* with `blocking = true`,
 * on the GL thread that has to keep the encoder fed.
 *
 * The two failure modes this test pins down:
 *  - a tracker that cannot deliver must not take the whole export with it (it used to throw into the
 *    frame loop when it was not wrapped, and be swallowed *silently* when it was), and
 *  - whatever the tracker could not deliver must be **recorded**, so an exported video with a
 *    missing tracked effect is explained instead of looking like a success.
 */
@RunWith(AndroidJUnit4::class)
class TrackedEffectExportInstrumentedTest {

  private lateinit var dir: File
  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
  private val config = ExportConfig(resolution = Resolution.RES_720P, frameRate = FrameRate.FPS_30)

  @Before
  fun setUp() {
    AndroidTestContextHolder.context = context
    dir = File(context.cacheDir, "tracked-export-${System.nanoTime()}").also { it.mkdirs() }
    LookVerificationReport.section("Tracked effect export (face reshape / AR / body reshape)")
    LookVerificationReport.line("stamp=${LookVerificationReport.stamp()}")
  }

  @After
  fun tearDown() {
    FaceWarpRegistry.clear()
    ArOverlayRegistry.clear()
    runCatching { dir.deleteRecursively() }
  }

  private fun source(name: String, ms: Long): File {
    val sources = File(dir, "sources").apply { mkdirs() }
    val file = File(sources, name)
    TestMp4Factory.write(file, videoMs = ms, audioMs = ms, picture = TestMp4Factory.Picture.COLORFUL)
    return file
  }

  private fun clip(id: String, file: File, ms: Long) = VideoClip(
    id = id,
    uri = android.net.Uri.fromFile(file).toString(),
    name = file.name,
    isVideo = true,
    timelineStartMs = 0L,
    durationMs = ms,
    sourceStartMs = 0L,
    sourceEndMs = ms,
    sourceTotalDurationMs = ms,
    width = TestMp4Factory.W,
    height = TestMp4Factory.H,
    frameRate = TestMp4Factory.FPS.toFloat(),
    hasAudio = true
  )

  /** Wires the registries exactly like the editor does when a face/AR tool is used. */
  private fun installRealTracker(uri: String): ClipFaceTracker {
    val tracker = ClipFaceTracker(context, uriFor = { uri })
    FaceWarpRegistry.faceSource = MotionFaceFallbackSource(tracker) { _, _ -> emptyList() }
    FaceWarpRegistry.update(mapOf(CLIP_ID to DeformationParams(eyeEnlarge = 0.5f, faceSlim = 0.6f, skinSmooth = 0.4f)))
    ArOverlayRegistry.update(mapOf(CLIP_ID to ArOverlayParams(filterId = "crown", scale = 0.8f)))
    return tracker
  }

  private data class Outcome(val wallMs: Long, val validation: ExportValidationResult)

  private fun exportAndValidate(timeline: Timeline, name: String): Outcome {
    val out = File(dir, name)
    val started = System.currentTimeMillis()
    runBlocking { AsyncFramePipelineEngine(context).export(timeline, config, out) }
    val wallMs = System.currentTimeMillis() - started
    val expected = ExportDimensionResolver.resolve(config.resolution, timeline.aspectRatio)
    val validation = ExportValidator.validate(
      out, config, timeline.totalDurationMs,
      requireAudio = true, expectedDimensions = expected, sampleFrames = true, expectedRotation = 0
    )
    LookVerificationReport.line(
      "export $name took ${wallMs}ms for ${timeline.totalDurationMs}ms of video " +
        "(valid=${validation.valid} '${validation.message}') | " +
        (TrackingHealth.summary() ?: "no tracking failures")
    )
    return Outcome(wallMs, validation)
  }

  @Test
  fun faceReshapeAndArFilterExportWithATrackedClip() {
    // The tracked path must simply keep working: the tracker runs (bundled ML Kit, no face in this
    // fixture), the export completes, the file validates. Before the prewarm existed the same export
    // asked the tracker for a fresh retriever frame + inference per exported frame, on the GL thread.
    val ms = 600L
    val file = source("tracked.mp4", ms)
    val timeline = Timeline(
      videoClips = listOf(clip(CLIP_ID, file, ms)),
      aspectRatio = AspectRatio.RATIO_9_16
    )
    val tracker = installRealTracker(android.net.Uri.fromFile(file).toString())
    try {
      val result = exportAndValidate(timeline, "tracked.mp4")
      assertTrue("tracked export rejected: ${result.validation.message}", result.validation.valid)
      // A stall would show up here: 18 frames cannot take minutes unless the frame loop is waiting on
      // the tracker per frame. The bound is generous for a software-GL emulator on purpose.
      assertTrue("tracked export did not finish in bounded time: ${result.wallMs}ms", result.wallMs < 240_000L)
    } finally {
      runCatching { tracker.shutdown() }
    }
  }

  @Test
  fun aTrackerThatCannotDeliverIsReportedInsteadOfSilentlyDroppingTheEffect() {
    val ms = 300L
    val file = source("failing.mp4", ms)
    val timeline = Timeline(
      videoClips = listOf(clip(CLIP_ID, file, ms)),
      aspectRatio = AspectRatio.RATIO_9_16
    )
    // Production wiring: the editor always wraps the tracker, so a tracker failure lands in the
    // fallback. That used to be a silent swallow - the effect vanished with nothing in logcat.
    FaceWarpRegistry.faceSource = MotionFaceFallbackSource(
      object : FaceWarpFaceSource {
        override fun facesAt(clipId: String, sourceTimeUs: Long, blocking: Boolean): List<TrackedFace> =
          throw IllegalStateException("stale retriever for '$clipId' (test)")
      }
    ) { _, _ -> emptyList() }
    FaceWarpRegistry.update(mapOf(CLIP_ID to DeformationParams(faceSlim = 0.6f)))

    val result = exportAndValidate(timeline, "failing.mp4")

    assertTrue("the export must survive a tracker failure: ${result.validation.message}", result.validation.valid)
    assertTrue(
      "the tracker failure was not recorded (it used to be swallowed silently)",
      TrackingHealth.totalFailures() > 0L
    )
    val summary = TrackingHealth.summary() ?: error("no tracking summary after a failing tracker")
    LookVerificationReport.line("tracking failure summary: $summary")
    assertTrue("the reason must name the tracker failure: $summary", summary.contains("stale retriever"))
    assertEquals("one entry per clip keeps a long export bounded", 1, TrackingHealth.clipIds().size)
  }

  private companion object {
    const val CLIP_ID = "tracked-clip"
  }
}
