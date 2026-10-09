package com.example.engine.ai

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.util.concurrent.TimeUnit

/**
 * Premium Cut — Shared ML Kit Detector Pool.
 *
 * Optimized for real-time video motion tracking:
 * - Single unified face detector with landmarks & tracking enabled.
 * - Fast stream-mode pose detector for high frame-rate tracking without UI stutter.
 * - Thread-safe lazy instantiation and safe warm-up.
 */
object MlKitDetectorPool {

    private val lock = Any()

    @Volatile
    private var faceDetector: FaceDetector? = null

    @Volatile
    private var poseDetector: PoseDetector? = null

    /**
     * Fast face detector with tracking IDs and full landmarks.
     * Suitable for attaching overlays, stickers, and tracking facial rotation.
     */
    fun faceDetector(): FaceDetector =
        synchronized(lock) {
            faceDetector ?: FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                    .setMinFaceSize(0.15f)
                    .enableTracking()
                    .build()
            ).also {
                faceDetector = it
            }
        }

    /**
     * Fast stream-mode pose detector for continuous body movement tracking.
     */
    fun poseDetector(): PoseDetector =
        synchronized(lock) {
            poseDetector ?: PoseDetection.getClient(
                PoseDetectorOptions.Builder()
                    .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                    .build()
            ).also {
                poseDetector = it
            }
        }

    /**
     * Warm up detectors in the background to eliminate initial frame drop.
     * Call this on a dedicated background coroutine/thread.
     */
    fun warmUp(
        face: Boolean = true,
        body: Boolean = true,
        timeoutSeconds: Long = 10L
    ): Boolean {
        val bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)

        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            var success = true

            if (face) {
                val faceReady = runCatching {
                    Tasks.await(
                        faceDetector().process(image),
                        timeoutSeconds,
                        TimeUnit.SECONDS
                    )
                }.isSuccess

                success = success && faceReady
            }

            if (body) {
                val bodyReady = runCatching {
                    Tasks.await(
                        poseDetector().process(image),
                        timeoutSeconds,
                        TimeUnit.SECONDS
                    )
                }.isSuccess

                success = success && bodyReady
            }

            success
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Close detectors and release resources when the editing engine stops.
     */
    fun close() {
        synchronized(lock) {
            faceDetector?.close()
            poseDetector?.close()

            faceDetector = null
            poseDetector = null
        }
    }
}
