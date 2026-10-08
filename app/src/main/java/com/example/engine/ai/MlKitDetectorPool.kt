package com.example.engine.ai

import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions

/**
 * Process-wide ML Kit clients. Creating a Face/Pose detector loads the model; doing that
 * on every Track tap is a large part of why detection felt slow. Clients are thread-safe
 * for sequential `process()` calls from the tracking worker.
 */
object MlKitDetectorPool {

    private val lock = Any()

    @Volatile private var faceLock: FaceDetector? = null
    @Volatile private var faceLandmarks: FaceDetector? = null
    @Volatile private var pose: PoseDetector? = null

    /** Fast lock detector: no landmarks, FAST mode, min face 15% of frame. */
    fun faceLockDetector(): FaceDetector = synchronized(lock) {
        faceLock ?: FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(0.15f)
                .enableTracking()
                .build()
        ).also { faceLock = it }
    }

    /** Periodic refresh: FAST mode with landmarks (eyes / nose / mouth) for attach + rotation. */
    fun faceLandmarkDetector(): FaceDetector = synchronized(lock) {
        faceLandmarks ?: FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(0.10f)
                .enableTracking()
                .build()
        ).also { faceLandmarks = it }
    }

    fun poseDetector(): PoseDetector = synchronized(lock) {
        pose ?: PoseDetection.getClient(
            AccuratePoseDetectorOptions.Builder()
                .setDetectorMode(AccuratePoseDetectorOptions.STREAM_MODE)
                .build()
        ).also { pose = it }
    }
}
