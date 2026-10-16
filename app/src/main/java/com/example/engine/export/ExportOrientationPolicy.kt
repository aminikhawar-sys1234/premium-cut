package com.example.engine.export

/**
 * How export bakes orientation into pixels.
 *
 * Every destination this app renders to - the preview TextureView, the screen and the MediaCodec
 * encoder input Surface - is a BufferQueue fed by GLES, and GLES writes all of them with the same
 * coordinate convention: NDC y = +1 is the top of the picture, both on screen and in the encoded
 * frame. Android's own round-trip test proves it: `EncodeDecodeTest` draws into the encoder input
 * Surface with `glScissor` (bottom-left origin) and then asserts the decoded frame's YUV rows hold
 * the same picture in the same place - a GL frame drawn in the upper window half comes back in the
 * *top* rows of the decoded image. An export that draws the composed frame into the encoder Surface
 * exactly like the preview draws it into its surface is therefore upright; a Y flip there mirrors
 * the whole picture upside down.
 *
 * Orientation is baked exactly once, on the GPU, and never again:
 *  - the decoder must not bake the container rotation (KEY_ROTATION = 0), because the compositor
 *    applies `naturalRotation` itself - otherwise 90/270 sources get rotated twice; and
 *  - the muxer must not carry a rotation hint, because the pixels already are the display
 *    orientation (also enforced by [ExportContentRules]: `expectedRotation = 0`).
 */
object ExportOrientationPolicy {
  /** GPU compositor already outputs upright pixels; a muxer hint would rotate them again. */
  const val MUXER_ORIENTATION_HINT_DEGREES = 0

  /**
   * Decoder KEY_ROTATION is forced to 0 so SurfaceTexture.getTransformMatrix() only carries
   * crop + the decoder's buffer Y convention, not a second copy of the display rotation.
   */
  const val DECODER_KEY_ROTATION_DEGREES = 0

  /**
   * Blit flip for the encoder input Surface. The encoder's buffer and the display's buffer share the
   * GLES orientation (see the class comment), so this must stay `false` - it is the same blit the
   * preview does. `true` mirrors every exported frame vertically (upside down). The flag is kept so
   * the two destinations stay explicitly comparable in tests.
   */
  const val FLIP_Y_FOR_ENCODER = false

  fun normalizeDegrees(rotation: Int): Int = ((rotation % 360) + 360) % 360

  fun isTransposed(rotationDegrees: Int): Boolean {
    val r = normalizeDegrees(rotationDegrees)
    return r == 90 || r == 270
  }

  /** Display size after applying source rotation metadata (not user-transform). */
  fun displaySize(rawWidth: Int, rawHeight: Int, rotationDegrees: Int): Pair<Int, Int> {
    val w = rawWidth.coerceAtLeast(1)
    val h = rawHeight.coerceAtLeast(1)
    return if (isTransposed(rotationDegrees)) h to w else w to h
  }

  /**
   * One rotation, never two. The compositor builds the same sum inline for the layer matrix
   * (`GpuCompositionRenderer.kt` main clip branch) and both forms are pinned together by
   * `toolchain/verify/verify_export_orientation.py`.
   */
  fun totalRotationDegrees(naturalRotation: Int, userRotation: Int, keyframeRotation: Float = 0f): Float {
    val sum = naturalRotation.toFloat() + userRotation.toFloat() + keyframeRotation
    return ((sum % 360f) + 360f) % 360f
  }

  /** OpenGL positive-Z is CCW; Android rotation metadata is clockwise. */
  fun glRotationDegrees(totalRotationDegrees: Float): Float = -totalRotationDegrees
}
