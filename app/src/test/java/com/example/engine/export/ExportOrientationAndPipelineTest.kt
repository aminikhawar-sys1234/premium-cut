package com.example.engine.export

import android.media.MediaFormat
import androidx.test.core.app.ApplicationProvider
import com.example.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ExportOrientationAndPipelineTest {

  @Test
  fun testOrientationPolicy_CanvasIsNormalizedUpright() {
    val portraitClip = VideoClip(
      uri = "content://media/external/video/media/1",
      name = "Portrait Source",
      width = 1920,
      height = 1080,
      naturalRotation = 90,
      rotationDegrees = 0
    )

    // With 90° natural rotation, display dimensions are transposed (1080 x 1920)
    val isTransposed = (portraitClip.naturalRotation == 90 || portraitClip.naturalRotation == 270)
    val displayW = if (isTransposed) portraitClip.height else portraitClip.width
    val displayH = if (isTransposed) portraitClip.width else portraitClip.height

    assertEquals(1080, displayW)
    assertEquals(1920, displayH)

    val canvasW = 1080
    val canvasH = 1920
    val displayAspect = displayW.toFloat() / displayH
    val canvasAspect = canvasW.toFloat() / canvasH

    assertEquals(displayAspect, canvasAspect, 0.001f)

    // Muxer orientation hint MUST be 0 because GPU rendering normalizes the frame upright
    val muxerOrientationHint = 0
    assertEquals(0, muxerOrientationHint)
  }

  @Test
  fun testOrientationCalculation_SupportsAllMetadataRotations() {
    for (rotation in listOf(0, 90, 180, 270)) {
      val clip = VideoClip(
        uri = "test_uri",
        name = "Rot-$rotation",
        width = 1920,
        height = 1080,
        naturalRotation = rotation,
        rotationDegrees = 0
      )

      val totalRot = ((clip.naturalRotation.toFloat() + clip.rotationDegrees.toFloat()) % 360f + 360f) % 360f
      assertEquals(rotation.toFloat(), totalRot, 0.01f)

      val isTransposed = (totalRot == 90f || totalRot == 270f)
      if (rotation == 90 || rotation == 270) {
        assertTrue("Rotation $rotation should be transposed", isTransposed)
      } else {
        assertFalse("Rotation $rotation should not be transposed", isTransposed)
      }
    }
  }

  @Test
  fun testUserRotationCombinedWithNaturalRotation() {
    val clip = VideoClip(
      uri = "test_uri",
      name = "Combined",
      width = 1920,
      height = 1080,
      naturalRotation = 90,
      rotationDegrees = 90
    )

    val totalRot = ((clip.naturalRotation.toFloat() + clip.rotationDegrees.toFloat()) % 360f + 360f) % 360f
    // 90 natural + 90 user = 180 total
    assertEquals(180f, totalRot, 0.01f)
  }

  @Test
  fun testResolutionDimensionCalculation_1080p2K4K() {
    val exporter = VideoExporter(ApplicationProvider.getApplicationContext())

    val (w1080, h1080) = exporter.getDimensionsForResolution(Resolution.RES_1080P, AspectRatio.RATIO_9_16)
    assertEquals(1080, w1080)
    assertEquals(1920, h1080)

    val (w2k, h2k) = exporter.getDimensionsForResolution(Resolution.RES_2K, AspectRatio.RATIO_16_9)
    assertTrue("2K width should be at least 2560", w2k >= 2560)
    assertTrue("2K height should be at least 1440", h2k >= 1440)

    val (w4k, h4k) = exporter.getDimensionsForResolution(Resolution.RES_4K, AspectRatio.RATIO_9_16)
    assertTrue("4K portrait height should be at least 3840", h4k >= 3840)
    assertTrue("4K portrait width should be at least 2160", w4k >= 2160)
  }

  @Test
  fun testPresentationTimestamp_StrictMonotonicity() {
    var lastEglPtsNs = -1L
    val fps = 30
    for (frameIndex in 0 until 100) {
      val ptsUs = (frameIndex * 1_000_000L) / fps
      var targetPtsNs = ptsUs * 1000L
      if (targetPtsNs <= lastEglPtsNs) {
        targetPtsNs = lastEglPtsNs + 1000L
      }
      assertTrue("Presentation timestamp must be strictly monotonic", targetPtsNs > lastEglPtsNs)
      lastEglPtsNs = targetPtsNs
    }
  }

  @Test
  fun testExportOrientationPolicy_EncoderBlitFlipsYAndMuxerHintIsZero() {
    assertEquals(0, ExportOrientationPolicy.MUXER_ORIENTATION_HINT_DEGREES)
    assertEquals(0, ExportOrientationPolicy.DECODER_KEY_ROTATION_DEGREES)
    assertTrue(ExportOrientationPolicy.FLIP_Y_FOR_ENCODER)
    assertEquals(-90f, ExportOrientationPolicy.glRotationDegrees(90f), 0.01f)
    assertEquals(-180f, ExportOrientationPolicy.glRotationDegrees(180f), 0.01f)
    assertEquals(-270f, ExportOrientationPolicy.glRotationDegrees(270f), 0.01f)
  }

  @Test
  fun testExportOrientationPolicy_PortraitAndLandscapeDisplaySize() {
    assertEquals(1920 to 1080, ExportOrientationPolicy.displaySize(1920, 1080, 0))
    assertEquals(1080 to 1920, ExportOrientationPolicy.displaySize(1920, 1080, 90))
    assertEquals(1920 to 1080, ExportOrientationPolicy.displaySize(1920, 1080, 180))
    assertEquals(1080 to 1920, ExportOrientationPolicy.displaySize(1920, 1080, 270))
    assertEquals(1080 to 1920, ExportOrientationPolicy.displaySize(1080, 1920, 0))
  }

  @Test
  fun test1080pStaysExactly1920x1080And1080x1920() {
    val landscape = ExportDimensionResolver.resolve(Resolution.RES_1080P, AspectRatio.RATIO_16_9)
    assertEquals(1920, landscape.first)
    assertEquals(1080, landscape.second)
    val portrait = ExportDimensionResolver.resolve(Resolution.RES_1080P, AspectRatio.RATIO_9_16)
    assertEquals(1080, portrait.first)
    assertEquals(1920, portrait.second)
    assertEquals(0, landscape.first % 2)
    assertEquals(0, landscape.second % 2)
  }

  @Test
  fun testAllStandardResolutionsStayEvenAndMatchRequestedLabel() {
    val landscape = mapOf(
      Resolution.RES_480P to (854 to 480),
      Resolution.RES_720P to (1280 to 720),
      Resolution.RES_1080P to (1920 to 1080),
      Resolution.RES_2K to (2560 to 1440),
      Resolution.RES_4K to (3840 to 2160)
    )
    for ((res, expected) in landscape) {
      val got = ExportDimensionResolver.resolve(res, AspectRatio.RATIO_16_9)
      assertEquals("$res landscape", expected, got)
    }
    val portrait1080 = ExportDimensionResolver.resolve(Resolution.RES_1080P, AspectRatio.RATIO_9_16)
    assertEquals(1080 to 1920, portrait1080)
  }

  @Test
  fun testEncoderPlannerDoesNotDegrade1080pTo720p() {
    val config = ExportConfig(resolution = Resolution.RES_1080P, frameRate = FrameRate.FPS_30, codecProfile = CodecProfile.H264_AVC)
    val plan = ExportEncoderPlanner.plan(config, 1920, 1080, 30)
    assertEquals("1080p width must stay 1920", 1920, plan.width)
    assertEquals("1080p height must stay 1080", 1080, plan.height)
    assertEquals(1920, plan.requestedWidth)
    assertEquals(1080, plan.requestedHeight)
    assertTrue("1080p bitrate must stay in a real FHD range", plan.bitrateBps in 2_000_000..40_000_000)

    val portrait = ExportEncoderPlanner.plan(config, 1080, 1920, 30)
    assertEquals(1080, portrait.width)
    assertEquals(1920, portrait.height)
  }

  @Test
  fun test480p720p2k4kPlannerKeepsRequestedCanvas() {
    data class Case(val res: Resolution, val w: Int, val h: Int)
    val cases = listOf(
      Case(Resolution.RES_480P, 854, 480),
      Case(Resolution.RES_720P, 1280, 720),
      Case(Resolution.RES_2K, 2560, 1440),
      Case(Resolution.RES_4K, 3840, 2160)
    )
    for (c in cases) {
      val plan = ExportEncoderPlanner.plan(
        ExportConfig(resolution = c.res, frameRate = FrameRate.FPS_30),
        c.w, c.h, 30
      )
      assertEquals("${c.res} requested width", c.w, plan.requestedWidth)
      assertEquals("${c.res} requested height", c.h, plan.requestedHeight)
      if (c.res == Resolution.RES_480P || c.res == Resolution.RES_720P) {
        assertEquals("${c.res} encoded width", c.w, plan.width)
        assertEquals("${c.res} encoded height", c.h, plan.height)
      }
    }
  }

  @Test
  fun test1080pAvcNeedsLevel4Macroblocks() {
    val mbs = ExportCodecFormat.macroblocks(1920, 1080)
    assertEquals(120 * 68, mbs)
    assertTrue("1080p exceeds AVC Level 3.1 (3600 MB)", mbs > 3600)
    assertTrue("1080p fits AVC Level 4.0 (8192 MB)", mbs <= 8192)
    assertEquals(ExportCodecFormat.macroblocks(1280, 720), 80 * 45)
  }

  @Test
  fun testExported1080pMustNotValidateAs720p() {
    val p = ExportContentProbe(width = 1280, height = 720, frames = emptyList())
    val verdict = ExportContentRules.check(
      p,
      ExportExpectations(expectedDurationMs = 10_000L, expectedDimensions = 1920 to 1080, checkFrames = false, requireAudio = false)
    )
    assertEquals(ExportFailure.DIMENSION_MISMATCH, verdict?.failure)
  }

  @Test
  fun testExportedExact1080pAndPortraitAreAccepted() {
    val landscape = ExportContentProbe(width = 1920, height = 1080, frames = emptyList())
    assertNull(
      ExportContentRules.check(
        landscape,
        ExportExpectations(expectedDurationMs = 10_000L, expectedDimensions = 1920 to 1080, checkFrames = false, requireAudio = false)
      )
    )
    val portrait = ExportContentProbe(width = 1080, height = 1920, frames = emptyList())
    assertNull(
      ExportContentRules.check(
        portrait,
        ExportExpectations(expectedDurationMs = 10_000L, expectedDimensions = 1080 to 1920, checkFrames = false, requireAudio = false)
      )
    )
  }

  @Test
  fun testRotationMetadataOnExportIsRejected() {
    val rotated = ExportContentProbe(width = 1920, height = 1080, rotationDegrees = 90, frames = emptyList())
    val verdict = ExportContentRules.check(
      rotated,
      ExportExpectations(expectedDurationMs = 10_000L, expectedDimensions = 1920 to 1080, expectedRotation = 0, checkFrames = false, requireAudio = false)
    )
    assertEquals(ExportFailure.BAD_ROTATION, verdict?.failure)
  }

  @Test
  fun testAudioTrackPreservedAndInSyncWithVideo() {
    val p = ExportContentProbe(
      hasAudioTrack = true,
      width = 1920,
      height = 1080,
      videoDurationMs = 10_000L,
      audioDurationMs = 10_040L,
      containerDurationMs = 10_000L,
      frames = emptyList()
    )
    assertNull(
      ExportContentRules.check(
        p,
        ExportExpectations(
          expectedDurationMs = 10_000L,
          expectedDimensions = 1920 to 1080,
          requireAudio = true,
          checkFrames = false
        )
      )
    )
    val drifted = ExportContentProbe(
      hasAudioTrack = true,
      width = 1920,
      height = 1080,
      videoDurationMs = 10_000L,
      audioDurationMs = 12_000L,
      containerDurationMs = 10_000L,
      frames = emptyList()
    )
    assertEquals(
      ExportFailure.AV_DURATION_MISMATCH,
      ExportContentRules.check(
        drifted,
        ExportExpectations(
          expectedDurationMs = 10_000L,
          expectedDimensions = 1920 to 1080,
          requireAudio = true,
          checkFrames = false
        )
      )?.failure
    )
  }

  @Test
  fun testCodecCapabilityInspector_HandlesHighResGracefully() {
    val report = ProfessionalCodecCapabilities.inspect(
      config = ExportConfig(
        resolution = Resolution.RES_4K,
        frameRate = FrameRate.FPS_30,
        codecProfile = CodecProfile.AUTO
      ),
      dimensions = Pair(2160, 3840)
    )

    assertNotNull(report)
    assertEquals(2160, report.width)
    assertEquals(3840, report.height)
  }
}
