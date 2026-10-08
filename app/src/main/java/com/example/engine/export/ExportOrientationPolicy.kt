package com.example.engine.export

/**
 * How export bakes orientation into pixels.
 *
 * Preview (ExoPlayer / TextureView) honours container KEY_ROTATION. The export compositor
 * does not: it draws the frame upright into the encoder Surface and the muxer hint stays 0.
 * Reusing the preview transform blindly (KEY_ROTATION on the decoder + GPU rotate + encoder
 * Y-flip off) is what produced sideways / upside-down exports.
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
   * FBO colour is GL y-up; MediaCodec input Surfaces store the first encoded row as the top of
   * the picture. Blit with a Y flip so the encoded pixels match what the compositor drew.
   */
  const val FLIP_Y_FOR_ENCODER = true

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

  fun totalRotationDegrees(naturalRotation: Int, userRotation: Int, keyframeRotation: Float = 0f): Float {
    val sum = naturalRotation.toFloat() + userRotation.toFloat() + keyframeRotation
    return ((sum % 360f) + 360f) % 360f
  }

  /** OpenGL positive-Z is CCW; Android rotation metadata is clockwise. */
  fun glRotationDegrees(totalRotationDegrees: Float): Float = -totalRotationDegrees
}
