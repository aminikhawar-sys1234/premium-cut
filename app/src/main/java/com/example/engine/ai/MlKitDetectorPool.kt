package com.example.engine.ai

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.util.concurrent.TimeUnit

object MlKitDetectorPool {

    private val lock = Any()

    @Volatile private var faceLock: FaceDetector? = null
    @Volatile private var faceLandmarks: FaceDetector? = null
    @Volatile private var faceLive: FaceDetector? = null
    @Volatile private var poseAccurate: PoseDetector? = null
    @Volatile private var poseLive: PoseDetector? = null
    @Volatile private var objectSingle: ObjectDetector? = null
    @Volatile private var objectLive: ObjectDetector? = null

    fun faceLockDetector(): FaceDetector = synchronized(lock) {
        faceLock ?: FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(0.05f)
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
                .setMinFaceSize(0.05f)
                .enableTracking()
                .build()
        ).also { faceLandmarks = it }
    }

    fun faceLiveDetector(): FaceDetector = synchronized(lock) {
        faceLive ?: FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(0.05f)
                .enableTracking()
                .build()
        ).also { faceLive = it }
    }

    fun poseDetector(): PoseDetector = synchronized(lock) {
        poseAccurate ?: PoseDetection.getClient(
            AccuratePoseDetectorOptions.Builder()
                .setDetectorMode(AccuratePoseDetectorOptions.SINGLE_IMAGE_MODE)
                .build()
        ).also { poseAccurate = it }
    }

    fun poseLiveDetector(): PoseDetector = synchronized(lock) {
        poseLive ?: PoseDetection.getClient(
            PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                .build()
        ).also { poseLive = it }
    }

    fun objectDetector(): ObjectDetector = synchronized(lock) {
        objectSingle ?: ObjectDetection.getClient(
            ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
                .enableMultipleObjects()
                .enableClassification()
                .build()
        ).also { objectSingle = it }
    }

    fun objectLiveDetector(): ObjectDetector = synchronized(lock) {
        objectLive ?: ObjectDetection.getClient(
            ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
                .enableMultipleObjects()
                .enableClassification()
                .build()
        ).also { objectLive = it }
    }

    /** Loads on-device models so the first real detection does not hit the await timeout. */
    fun warmUp(face: Boolean, body: Boolean, objects: Boolean = false) {
        val bmp = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        try {
            val img = InputImage.fromBitmap(bmp, 0)
            if (face) {
                runCatching { Tasks.await(faceLockDetector().process(img), 20, TimeUnit.SECONDS) }
                runCatching { Tasks.await(faceLiveDetector().process(img), 20, TimeUnit.SECONDS) }
            }
            if (body) {
                runCatching { Tasks.await(poseLiveDetector().process(img), 20, TimeUnit.SECONDS) }
                runCatching { Tasks.await(poseDetector().process(img), 20, TimeUnit.SECONDS) }
            }
            if (objects) {
                runCatching { Tasks.await(objectLiveDetector().process(img), 20, TimeUnit.SECONDS) }
                runCatching { Tasks.await(objectDetector().process(img), 20, TimeUnit.SECONDS) }
            }
        } finally {
            bmp.recycle()
        }
    }
}
