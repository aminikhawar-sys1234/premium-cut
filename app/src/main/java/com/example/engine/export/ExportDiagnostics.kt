package com.example.engine.export

import android.media.MediaFormat
import android.media.MediaCodec
import android.os.Build
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
    val codecError = (error as? MediaCodec.CodecException)?.let {
      " diagnostic=${it.diagnosticInfo} recoverable=${it.isRecoverable} transient=${it.isTransient}"
    }.orEmpty()
    Log.e(TAG, "EXPORT_CONFIGURE_FAIL codec=$codecName mime=$mime size=${width}x$height " +
      "device=${Build.MANUFACTURER}/${Build.MODEL} sdk=${Build.VERSION.SDK_INT} " +
      "thread=${Thread.currentThread().name}$codecError: ${error.message}", error)
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

  /**
   * One-shot record of a decoder that needed the resource ladder (see `CodecAllocationPolicy`):
   * either the device refused an instance and the export recovered by releasing one, or the clip now
   * decodes on a software codec because the hardware instances are in use. Never per frame.
   */
  fun decoderLadder(clipName: String, event: String, detail: String) {
    Log.i(TAG, "EXPORT_DECODER_LADDER clip='$clipName' event=$event $detail")
  }

  /** Stage timing of the export's pre-roll, so a slow device can be read off the log. */
  fun stage(name: String, ms: Long, detail: String = "") {
    Log.i(TAG, "EXPORT_STAGE $name took=${ms}ms ${detail}".trim())
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
