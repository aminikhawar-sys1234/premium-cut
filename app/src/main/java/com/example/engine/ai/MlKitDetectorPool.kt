package com.example.engine.ai

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import java.util.concurrent.TimeUnit

object MlKitDetectorPool {

    private val lock = Any()

    @Volatile private var faceLock: FaceDetector? = null
    @Volatile private var faceLandmarks: FaceDetector? = null
    @Volatile private var pose: PoseDetector? = null

    fun faceLockDetector(): FaceDetector = synchronized(lock) {
        faceLock ?: FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(0.05f)   // pehle 0.15f
                .enableTracking()
                .build()
        ).also { faceLock = it }
    }

    fun faceLandmarkDetector(): FaceDetector = synchronized(lock) {
        faceLandmarks ?: FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(0.05f)   // pehle 0.10f
                .enableTracking()
                .build()
        ).also { faceLandmarks = it }
    }

    fun poseDetector(): PoseDetector = synchronized(lock) {
        pose ?: PoseDetection.getClient(
            AccuratePoseDetectorOptions.Builder()
                .setDetectorMode(AccuratePoseDetectorOptions.SINGLE_IMAGE_MODE) // pehle STREAM_MODE
                .build()
        ).also { pose = it }
    }

    /** Model pehle se load kar deta hai taake pehli detection timeout na ho. */
    fun warmUp(face: Boolean, body: Boolean) {
        val bmp = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        try {
            val img = InputImage.fromBitmap(bmp, 0)
            if (face) runCatching {
                Tasks.await(faceLockDetector().process(img), 20, TimeUnit.SECONDS)
            }
            if (body) runCatching {
                Tasks.await(poseDetector().process(img), 20, TimeUnit.SECONDS)
            }
        } finally {
            bmp.recycle()
        }
    }
}
