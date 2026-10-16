package com.example.engine.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The orientation contract of the export path, on the plain JVM (no android.* imports, so
 * `toolchain/verify/run_logic_tests.sh` executes this file wherever a JDK + kotlinc exist).
 *
 * Background: a MediaCodec encoder input Surface is a GLES-fed BufferQueue with the same coordinate
 * convention as the preview surface, so the composed frame must be handed to it exactly the way the
 * preview shows it. Android's `EncodeDecodeTest` pins that convention: a `glScissor` rect drawn in
 * the upper window half lands in the top rows of the decoded image.
 *
 * The regression this guards: `FLIP_Y_FOR_ENCODER` was `true`, which mirrored every exported frame
 * vertically (upside down) while the preview stayed upright.
 */
class ExportOrientationPolicyTest {

  @Test
  fun encoderAndPreviewBlitTheSameOrientation() {
    // `FramePresentation.PREVIEW` blits with flipY = false; the export must match it. The cross-file
    // version of this check (both call sites in one assertion) lives in
    // `toolchain/verify/verify_export_orientation.py`, which can read both sources.
    assertFalse(
      "the encoder blit must not flip Y: the encoder Surface shares the preview's GLES orientation",
      ExportOrientationPolicy.FLIP_Y_FOR_ENCODER
    )
  }

  @Test
  fun orientationIsBakedOnceAndTheContainerClaimsNone() {
    // Baked once on the GPU -> the muxer must not rotate the pixels a second time ...
    assertEquals(0, ExportOrientationPolicy.MUXER_ORIENTATION_HINT_DEGREES)
    // ... and the decoder must not bake the container rotation either, because the compositor
    // applies naturalRotation itself (rotating twice is what turned 90/270 sources upside down).
    assertEquals(0, ExportOrientationPolicy.DECODER_KEY_ROTATION_DEGREES)
  }

  @Test
  fun metadataRotationIsAppliedClockwiseInGlCoordinates() {
    // Android rotation metadata is clockwise, OpenGL's positive Z rotation is counter-clockwise.
    for (degrees in listOf(0, 90, 180, 270)) {
      assertEquals(-degrees.toFloat(), ExportOrientationPolicy.glRotationDegrees(degrees.toFloat()), 0.01f)
    }
    // Half turns are their own inverse: a 180 source stays upside-down-free either way.
    assertEquals(180f, ExportOrientationPolicy.totalRotationDegrees(180, 0), 0.01f)
    assertEquals(0f, ExportOrientationPolicy.totalRotationDegrees(0, 0), 0.01f)
    assertEquals(0f, ExportOrientationPolicy.totalRotationDegrees(350, 10), 0.01f)
    assertEquals(10f, ExportOrientationPolicy.totalRotationDegrees(-10, 20), 0.01f)
  }

  @Test
  fun displaySizeCoversPortraitLandscapeSquareAndBothTransposingRotations() {
    // Landscape source (1920x1080 coded)
    assertEquals(1920 to 1080, ExportOrientationPolicy.displaySize(1920, 1080, 0))
    assertEquals(1080 to 1920, ExportOrientationPolicy.displaySize(1920, 1080, 90))
    assertEquals(1920 to 1080, ExportOrientationPolicy.displaySize(1920, 1080, 180))
    assertEquals(1080 to 1920, ExportOrientationPolicy.displaySize(1920, 1080, 270))
    // Portrait source (1080x1920 coded)
    assertEquals(1080 to 1920, ExportOrientationPolicy.displaySize(1080, 1920, 0))
    assertEquals(1920 to 1080, ExportOrientationPolicy.displaySize(1080, 1920, 90))
    assertEquals(1080 to 1920, ExportOrientationPolicy.displaySize(1080, 1920, 180))
    assertEquals(1920 to 1080, ExportOrientationPolicy.displaySize(1080, 1920, 270))
    // Square stays square for every rotation
    for (rotation in listOf(0, 90, 180, 270)) {
      assertEquals(1080 to 1080, ExportOrientationPolicy.displaySize(1080, 1080, rotation))
    }
    // A missing/silly size never yields 0 (would divide by zero in the aspect fit)
    assertEquals(1 to 1, ExportOrientationPolicy.displaySize(0, 0, 90))
    assertEquals(1920 to 1, ExportOrientationPolicy.displaySize(0, 1920, 90))
  }

  @Test
  fun normalizationAcceptsNegativeAndWrappedMetadata() {
    assertEquals(270, ExportOrientationPolicy.normalizeDegrees(-90))
    assertEquals(90, ExportOrientationPolicy.normalizeDegrees(450))
    assertEquals(0, ExportOrientationPolicy.normalizeDegrees(360))
    assertTrue(ExportOrientationPolicy.isTransposed(-90))
    assertTrue(ExportOrientationPolicy.isTransposed(270))
    assertFalse(ExportOrientationPolicy.isTransposed(180))
    assertFalse(ExportOrientationPolicy.isTransposed(360))
  }

  @Test
  fun mirroredSourcesKeepTheirOwnOrientationMath() {
    // A front-camera/mirrored clip is a horizontal user transform, not a rotation: it must not move
    // the orientation math (that is what pair()/the total rotation is computed from).
    assertEquals(90f, ExportOrientationPolicy.totalRotationDegrees(90, 0), 0.01f)
    assertEquals(1080 to 1920, ExportOrientationPolicy.displaySize(1920, 1080, 90))
  }
}
