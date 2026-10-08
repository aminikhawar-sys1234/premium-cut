package com.example.engine.export

import android.media.MediaFormat
import android.util.Log

/** One-shot export setup/teardown logs. Never called from the per-frame render loop. */
object ExportDiagnostics {
  private const val TAG = "ExportDiagnostics"

  fun session(
    requestedWidth: Int,
    requestedHeight: Int,
    plan: EncoderPlan,
    codecName: String?,
    mime: String,
    profile: Int?,
    level: Int?,
    bitrateBps: Int,
    fps: Int,
    rotationHint: Int,
    eglWidth: Int,
    eglHeight: Int,
    flipYForEncoder: Boolean,
    surfaceTransformLogged: Boolean
  ) {
    Log.i(
      TAG,
      "EXPORT_SETUP requested=${requestedWidth}x$requestedHeight " +
        "encoder=${plan.width}x${plan.height} codec=${codecName ?: "?"} mime=$mime " +
        "profile=${profile ?: -1} level=${level ?: -1} bitrate=$bitrateBps fps=$fps " +
        "muxerRotation=$rotationHint egl=${eglWidth}x$eglHeight " +
        "flipYForEncoder=$flipYForEncoder stMatrixApplied=$surfaceTransformLogged"
    )
  }

  fun configureError(codecName: String?, mime: String, width: Int, height: Int, error: Throwable) {
    Log.e(TAG, "EXPORT_CONFIGURE_FAIL codec=$codecName mime=$mime size=${width}x$height: ${error.message}", error)
  }

  fun encoderOutputFormat(format: MediaFormat) {
    val w = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else -1
    val h = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else -1
    val mime = format.getString(MediaFormat.KEY_MIME)
    Log.i(TAG, "EXPORT_ENCODER_OUTPUT mime=$mime size=${w}x$h format=$format")
  }

  fun muxerStarted(videoTrack: Int, audioTrack: Int) {
    Log.i(TAG, "EXPORT_MUXER_STARTED videoTrack=$videoTrack audioTrack=$audioTrack")
  }

  fun finished(path: String, width: Int, height: Int, durationMs: Long, fileBytes: Long) {
    Log.i(TAG, "EXPORT_FINISHED path=$path size=${width}x$height durationMs=$durationMs bytes=$fileBytes")
  }

  fun transformMatrixOnce(clipId: String, rotation: Int, matrix: FloatArray) {
    if (matrix.size < 16) return
    Log.i(
      TAG,
      "EXPORT_ST_MATRIX clip=$clipId rotation=$rotation " +
        "m=[${matrix[0]},${matrix[5]},${matrix[10]},${matrix[12]},${matrix[13]}]"
    )
  }
}
