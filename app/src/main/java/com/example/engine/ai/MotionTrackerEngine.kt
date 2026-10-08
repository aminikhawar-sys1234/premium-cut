package com.example.engine.ai

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import com.example.domain.model.ClipKeyframe
import com.example.engine.ai.tracking.GrayImage
import com.example.engine.ai.tracking.Homography
import com.example.engine.ai.tracking.PlaneModel
import com.example.engine.ai.tracking.PlaneTrackFrame
import com.example.engine.ai.tracking.PlaneTracker
import com.example.engine.ai.tracking.Point2
import com.example.engine.ai.tracking.TrackFrameConverter
import com.example.engine.ai.tracking.TrackSmoother
import com.example.engine.ai.tracking.TrackingSampler
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.pose.PoseLandmark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Production Motion Tracking Engine.
 *
 * Object: pyramidal LK + RANSAC similarity (SAD fallback).
 * Face: ML Kit FAST detector (lock) then LK between sparse re-detects.
 * Body: ML Kit pose detector then LK between sparse re-detects.
 *
 * Detection never runs on the main thread. Detectors are pooled. Initial lock
 * scans at most four keyframe-aligned frames. Intermediate frames are tracked,
 * not re-inferred, until confidence/drift demands a refresh.
 */
class MotionTrackerEngine {

    companion object {
        private const val TAG = "MotionTrackerEngine"
        private const val MLKIT_TIMEOUT_MS = 450L
        private val BODY_LANDMARK_IDS = intArrayOf(
            PoseLandmark.NOSE,
            PoseLandmark.LEFT_SHOULDER,
            PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.LEFT_ELBOW,
            PoseLandmark.RIGHT_ELBOW,
            PoseLandmark.LEFT_WRIST,
            PoseLandmark.RIGHT_WRIST,
            PoseLandmark.LEFT_HIP,
            PoseLandmark.RIGHT_HIP,
            PoseLandmark.LEFT_KNEE,
            PoseLandmark.RIGHT_KNEE,
            PoseLandmark.LEFT_ANKLE,
            PoseLandmark.RIGHT_ANKLE
        )

        suspend fun trackObjectMotion(
            context: Context? = null,
            clipId: String,
            startMs: Long,
            durationMs: Long,
            initialX: Float = 0f,
            initialY: Float = 0f,
            videoPath: String? = null,
            onProgress: (Float, String) -> Unit
        ): List<ClipKeyframe> {
            if (videoPath.isNullOrBlank()) {
                onProgress(0f, "No video source available for tracking")
                return emptyList()
            }
            val engine = MotionTrackerEngine()
            val startUs = startMs * 1000L
            val durationUs = durationMs * 1000L
            val initBox = NormalizedRect(
                left = (initialX / 2f + 0.5f - 0.1f).coerceIn(0f, 0.8f),
                top = (initialY / 2f + 0.5f - 0.1f).coerceIn(0f, 0.8f),
                right = (initialX / 2f + 0.5f + 0.1f).coerceIn(0.2f, 1.0f),
                bottom = (initialY / 2f + 0.5f + 0.1f).coerceIn(0.2f, 1.0f)
            )

            val result = engine.analyzeMotion(
                context = context,
                videoUri = videoPath,
                targetClipId = clipId,
                initialBox = initBox,
                startUs = startUs,
                durationUs = durationUs,
                category = TrackingCategory.OBJECT,
                onProgress = { p, msg, _ -> onProgress(p, msg) }
            )

            return com.example.engine.integration.KeyframeAnimationEngine.convertTrackingResultToClipKeyframes(result, startMs)
        }
    }

    private var argbScratch: IntArray = IntArray(0)

    private data class DetectedTarget(
        val box: NormalizedRect,
        val rotationDeg: Float,
        val confidence: Float,
        val landmarks: List<Pair<Float, Float>>,
        val trackingId: Int? = null,
        val anchorIndex: Int = 0
    )

    suspend fun analyzeMotion(
        context: Context?,
        videoUri: String,
        targetClipId: String,
        initialBox: NormalizedRect,
        startUs: Long,
        durationUs: Long,
        category: TrackingCategory = TrackingCategory.OBJECT,
        settings: MotionTrackingSettings = MotionTrackingSettings(),
        onProgress: (Float, String, TrackingEngineState) -> Unit
    ): TrackingResult = withContext(Dispatchers.Default) {
        val cacheKey = MotionTrackingCache.makeKey(
            clipId = targetClipId,
            videoUri = videoUri,
            category = category,
            region = initialBox,
            startUs = startUs,
            durationUs = durationUs,
            mode = settings.featureMode.name
        )

        MotionTrackingCache.get(cacheKey)?.let { cached ->
            Log.d(TAG, "Cache hit for tracking result: $cacheKey")
            onProgress(1.0f, "Tracking loaded from cache", TrackingEngineState.COMPLETED)
            return@withContext cached
        }

        val progress = TrackingSampler.ProgressGate()
        fun report(p: Float, msg: String, state: TrackingEngineState, force: Boolean = false) {
            if (progress.shouldEmit(state, force)) onProgress(p, msg, state)
        }

        val retriever = MediaMetadataRetriever()
        val keyframes = mutableListOf<MotionKeyframe>()

        try {
            setDataSourceSafe(retriever, context, videoUri)
            coroutineContext.ensureActive()

            when (category) {
                TrackingCategory.FACE -> trackFaceInternal(retriever, initialBox, startUs, durationUs, settings, keyframes, ::report)
                TrackingCategory.BODY -> trackBodyInternal(retriever, initialBox, startUs, durationUs, settings, keyframes, ::report)
                TrackingCategory.MOTION -> trackMotionPointsInternal(retriever, initialBox, startUs, durationUs, settings, keyframes, ::report)
                else -> trackObjectInternal(retriever, initialBox, startUs, durationUs, settings, keyframes, ::report)
            }
        } catch (e: CancellationException) {
            report(0f, "Tracking cancelled", TrackingEngineState.CANCELLED, force = true)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Tracking failed for category $category: ${e.message}", e)
            report(0f, "Tracking error: ${e.localizedMessage ?: "Unknown error"}", TrackingEngineState.FAILED, force = true)
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }

        coroutineContext.ensureActive()

        val finalKeyframes = if (settings.smoothingFactor > 0.05f && keyframes.size > 2) {
            smoothKeyframes(keyframes, settings.smoothingFactor)
        } else {
            keyframes
        }

        val result = TrackingResult(
            targetId = "track_${System.currentTimeMillis()}",
            clipId = targetClipId,
            startTimestampUs = startUs,
            endTimestampUs = startUs + durationUs,
            keyframes = finalKeyframes,
            targetCategory = category
        )

        if (finalKeyframes.isNotEmpty()) {
            MotionTrackingCache.put(cacheKey, result)
            report(1.0f, "Tracking complete (${finalKeyframes.size} frames)", TrackingEngineState.COMPLETED, force = true)
        } else {
            report(1.0f, "No tracking target detected", TrackingEngineState.FAILED, force = true)
        }

        result
    }

    private suspend fun trackObjectInternal(
        retriever: MediaMetadataRetriever,
        initialBox: NormalizedRect,
        startUs: Long,
        durationUs: Long,
        settings: MotionTrackingSettings,
        keyframes: MutableList<MotionKeyframe>,
        report: (Float, String, TrackingEngineState, Boolean) -> Unit
    ) {
        report(0.05f, "Locking object target...", TrackingEngineState.DETECTING, true)
        if (trackWithPlaneModel(
                retriever, initialBox, startUs, durationUs, settings,
                PlaneModel.SIMILARITY, false, keyframes, report, "object"
            )
        ) return

        val safeDurationUs = durationUs.coerceAtLeast(1L)
        val timeStepUs = (1_000_000L / settings.accuracyFps.coerceIn(12, 60))
        val endUs = startUs + safeDurationUs
        var currentUs = startUs

        val initialBitmap = extractScaledFrame(retriever, startUs, TrackingSampler.TRACK_WIDTH, closestSync = true)
        if (initialBitmap == null) {
            report(0f, "Failed to read video frame at start time", TrackingEngineState.FAILED, true)
            return
        }

        val initFrame: Bitmap = initialBitmap
        var refFrame: Bitmap = initFrame
        val w = refFrame.width
        val h = refFrame.height

        var currentBox = initialBox
        val initialW = initialBox.width.coerceAtLeast(0.01f)
        val initialH = initialBox.height.coerceAtLeast(0.01f)
        var lastGoodBox = initialBox

        keyframes.add(
            MotionKeyframe(
                timestampUs = startUs,
                centerX = initialBox.centerX,
                centerY = initialBox.centerY,
                scaleX = 1.0f,
                scaleY = 1.0f,
                rotationDeg = 0.0f,
                confidence = 1.0f
            )
        )

        currentUs += timeStepUs
        var consecutiveLosses = 0
        var frameIndex = 1

        while (currentUs <= endUs && coroutineContext.isActive) {
            coroutineContext.ensureActive()
            val currFrame = extractScaledFrame(retriever, currentUs, w, h, closestSync = false)
            if (currFrame != null) {
                val matched = matchTemplateSAD(
                    refFrame = refFrame,
                    currFrame = currFrame,
                    lastBox = currentBox,
                    searchFactor = if (consecutiveLosses > 0) settings.searchWindowFactor * 1.5f else settings.searchWindowFactor
                )
                val jump = TrackingSampler.jumpDistance(matched.first, currentBox)
                val accepted = matched.second >= TrackingSampler.CONFIDENCE_LOST && jump <= TrackingSampler.JUMP_REJECT

                if (accepted) {
                    consecutiveLosses = 0
                    val scaleX = (matched.first.width / initialW).coerceIn(0.1f, 5.0f)
                    val scaleY = (matched.first.height / initialH).coerceIn(0.1f, 5.0f)
                    keyframes.add(
                        MotionKeyframe(
                            timestampUs = currentUs,
                            centerX = matched.first.centerX.coerceIn(0f, 1f),
                            centerY = matched.first.centerY.coerceIn(0f, 1f),
                            scaleX = scaleX,
                            scaleY = scaleY,
                            rotationDeg = 0.0f,
                            confidence = matched.second
                        )
                    )
                    lastGoodBox = currentBox
                    currentBox = matched.first
                    if (frameIndex % 10 == 0) {
                        if (refFrame != initFrame && !refFrame.isRecycled) refFrame.recycle()
                        refFrame = currFrame
                    } else if (currFrame != refFrame && !currFrame.isRecycled) {
                        currFrame.recycle()
                    }
                } else {
                    consecutiveLosses++
                    if (consecutiveLosses <= TrackingSampler.PREDICT_FRAMES && keyframes.size >= 2) {
                        val predicted = TrackingSampler.predictBox(currentBox, lastGoodBox)
                        keyframes.add(
                            MotionKeyframe(
                                timestampUs = currentUs,
                                centerX = predicted.centerX,
                                centerY = predicted.centerY,
                                scaleX = (predicted.width / initialW).coerceIn(0.1f, 5.0f),
                                scaleY = (predicted.height / initialH).coerceIn(0.1f, 5.0f),
                                confidence = 0.25f
                            )
                        )
                        currentBox = predicted
                    } else if (keyframes.isNotEmpty()) {
                        keyframes.add(keyframes.last().copy(timestampUs = currentUs, confidence = 0.12f))
                    }
                    if (currFrame != refFrame && !currFrame.isRecycled) currFrame.recycle()
                    if (TrackingSampler.isUnrecoverableYet(consecutiveLosses) &&
                        consecutiveLosses == TrackingSampler.RECOVERY_WINDOW_FRAMES
                    ) {
                        val p = ((currentUs - startUs).toFloat() / safeDurationUs.toFloat()).coerceIn(0f, 1f)
                        report(p, "Object target lost — still scanning", TrackingEngineState.LOST, true)
                    }
                }
            }

            frameIndex++
            val p = ((currentUs - startUs).toFloat() / safeDurationUs.toFloat()).coerceIn(0f, 1f)
            val state = when {
                TrackingSampler.isUnrecoverableYet(consecutiveLosses) -> TrackingEngineState.LOST
                TrackingSampler.isRecovering(consecutiveLosses) -> TrackingEngineState.RECOVERING
                else -> TrackingEngineState.TRACKING
            }
            report(p, "Tracking object... ${(p * 100).toInt()}%", state, false)
            currentUs += timeStepUs
        }

        if (refFrame != initFrame && !refFrame.isRecycled) refFrame.recycle()
        if (!initFrame.isRecycled) initFrame.recycle()
    }

    private suspend fun trackFaceInternal(
        retriever: MediaMetadataRetriever,
        initialBox: NormalizedRect,
        startUs: Long,
        durationUs: Long,
        settings: MotionTrackingSettings,
        keyframes: MutableList<MotionKeyframe>,
        report: (Float, String, TrackingEngineState, Boolean) -> Unit
    ) {
        report(0.05f, "Detecting face...", TrackingEngineState.DETECTING, true)
        val lockDetector = MlKitDetectorPool.faceLockDetector()
        val landmarkDetector = MlKitDetectorPool.faceLandmarkDetector()

        trackDetectThenLk(
            retriever = retriever,
            startUs = startUs,
            durationUs = durationUs,
            settings = settings,
            keyframes = keyframes,
            report = report,
            label = "face",
            lostMessage = "Face not detected in video",
            detect = { bitmap, preferLandmarks, last ->
                detectFace(bitmap, if (preferLandmarks) landmarkDetector else lockDetector, last, initialBox)
            }
        )
    }

    private suspend fun trackBodyInternal(
        retriever: MediaMetadataRetriever,
        initialBox: NormalizedRect,
        startUs: Long,
        durationUs: Long,
        settings: MotionTrackingSettings,
        keyframes: MutableList<MotionKeyframe>,
        report: (Float, String, TrackingEngineState, Boolean) -> Unit
    ) {
        report(0.05f, "Detecting body pose...", TrackingEngineState.DETECTING, true)
        val detector = MlKitDetectorPool.poseDetector()
        trackDetectThenLk(
            retriever = retriever,
            startUs = startUs,
            durationUs = durationUs,
            settings = settings,
            keyframes = keyframes,
            report = report,
            label = "body",
            lostMessage = "Body pose not detected in video",
            detect = { bitmap, _, last -> detectBody(bitmap, detector, last, initialBox) }
        )
    }

    /**
     * Shared Face/Body pipeline: sparse ML Kit lock → pyramidal LK → adaptive re-detect.
     */
    private suspend fun trackDetectThenLk(
        retriever: MediaMetadataRetriever,
        startUs: Long,
        durationUs: Long,
        settings: MotionTrackingSettings,
        keyframes: MutableList<MotionKeyframe>,
        report: (Float, String, TrackingEngineState, Boolean) -> Unit,
        label: String,
        lostMessage: String,
        detect: (Bitmap, Boolean, DetectedTarget?) -> DetectedTarget?
    ) {
        val safeDurationUs = durationUs.coerceAtLeast(1L)
        val timeStepUs = (1_000_000L / settings.accuracyFps.coerceIn(12, 60))
        val endUs = startUs + safeDurationUs
        val (detectW, detectH) = frameSize(retriever, TrackingSampler.DETECT_WIDTH)

        var lock: DetectedTarget? = null
        var lockTime = startUs
        var lockGray: GrayImage? = null
        for (scanUs in TrackingSampler.initialScanTimes(startUs, endUs)) {
            coroutineContext.ensureActive()
            val frame = extractScaledFrame(retriever, scanUs, detectW, detectH, closestSync = true) ?: continue
            try {
                val found = detect(frame, false, null)
                if (found != null) {
                    lock = found
                    lockTime = scanUs
                    lockGray = bitmapToGray(frame)
                    break
                }
            } finally {
                if (!frame.isRecycled) frame.recycle()
            }
        }

        if (lock == null || lockGray == null) {
            report(0f, lostMessage, TrackingEngineState.FAILED, true)
            return
        }

        val initialBox = lock!!.box
        val initialW = initialBox.width.coerceAtLeast(0.01f)
        val initialH = initialBox.height.coerceAtLeast(0.01f)
        keyframes.add(
            MotionKeyframe(
                timestampUs = lockTime,
                centerX = initialBox.centerX,
                centerY = initialBox.centerY,
                scaleX = 1f,
                scaleY = 1f,
                rotationDeg = lock!!.rotationDeg,
                confidence = lock!!.confidence,
                landmarkPoints = lock!!.landmarks
            )
        )
        report(0.12f, "${label.replaceFirstChar { it.uppercase() }} locked. Tracking...", TrackingEngineState.TRACKING, true)

        var currentBox = initialBox
        var lastGoodBox = initialBox
        var lastTarget = lock
        var lastRotation = lock!!.rotationDeg
        var lastLandmarks = lock!!.landmarks
        var lastConfidence = lock!!.confidence
        var consecutiveLosses = 0
        var frameIndex = 1
        var currentUs = (lockTime + timeStepUs).coerceAtMost(endUs)

        var tracker = planeTrackerFor(lockGray!!, currentBox)
        var converter = TrackFrameConverter(
            boxCorners(currentBox, lockGray!!.width, lockGray!!.height),
            lockGray!!.width,
            lockGray!!.height,
            settings.followPosition,
            settings.followScale,
            settings.followRotation
        )
        var seedW = lockGray!!.width
        var seedH = lockGray!!.height
        var scaleRefX = 1f
        var scaleRefY = 1f

        while (currentUs <= endUs && coroutineContext.isActive) {
            coroutineContext.ensureActive()
            val jumpHint = TrackingSampler.jumpDistance(currentBox, lastGoodBox)
            val redetect = TrackingSampler.shouldRedetect(frameIndex, consecutiveLosses, lastConfidence, jumpHint)
            val state = when {
                TrackingSampler.isUnrecoverableYet(consecutiveLosses) -> TrackingEngineState.LOST
                TrackingSampler.isRecovering(consecutiveLosses) -> TrackingEngineState.RECOVERING
                redetect -> TrackingEngineState.REDETECTING
                else -> TrackingEngineState.TRACKING
            }

            val gray = frameGray(retriever, currentUs, detectW, detectH, closestSync = false)
            var emitted = false

            if (redetect) {
                val detectBmp = extractScaledFrame(retriever, currentUs, detectW, detectH, closestSync = true)
                if (detectBmp != null) {
                    try {
                        val found = detect(detectBmp, true, lastTarget)
                        val accept = found != null && (
                            consecutiveLosses > 0 ||
                                TrackingSampler.jumpDistance(found.box, currentBox) <= TrackingSampler.JUMP_REJECT * 1.4f
                            )
                        if (accept && found != null) {
                            val scaleX = (found.box.width / initialW).coerceIn(0.2f, 5f)
                            val scaleY = (found.box.height / initialH).coerceIn(0.2f, 5f)
                            keyframes.add(
                                MotionKeyframe(
                                    timestampUs = currentUs,
                                    centerX = found.box.centerX,
                                    centerY = found.box.centerY,
                                    scaleX = scaleX,
                                    scaleY = scaleY,
                                    rotationDeg = found.rotationDeg,
                                    confidence = found.confidence,
                                    landmarkPoints = found.landmarks
                                )
                            )
                            lastGoodBox = currentBox
                            currentBox = found.box
                            lastTarget = found
                            lastRotation = found.rotationDeg
                            lastLandmarks = found.landmarks
                            lastConfidence = found.confidence
                            consecutiveLosses = 0
                            emitted = true
                            val seedGray = bitmapToGray(detectBmp)
                            val reseeded = planeTrackerFor(seedGray, found.box)
                            if (reseeded != null) {
                                tracker = reseeded
                                seedW = seedGray.width
                                seedH = seedGray.height
                                converter = TrackFrameConverter(
                                    boxCorners(found.box, seedW, seedH),
                                    seedW, seedH,
                                    settings.followPosition, settings.followScale, settings.followRotation
                                )
                                scaleRefX = scaleX
                                scaleRefY = scaleY
                            }
                        }
                    } finally {
                        if (!detectBmp.isRecycled) detectBmp.recycle()
                    }
                }
            }

            if (!emitted && gray != null && tracker != null && gray.width == seedW && gray.height == seedH) {
                val frame = tracker.process(gray)
                if (!frame.lost && frame.confidence >= TrackingSampler.CONFIDENCE_LOST) {
                    val kf = converter.toKeyframe(currentUs, frame, false)
                    val scaleX = (kf.scaleX * scaleRefX).coerceIn(0.2f, 5f)
                    val scaleY = (kf.scaleY * scaleRefY).coerceIn(0.2f, 5f)
                    val mapped = TrackingSampler.boxFromCenter(
                        kf.centerX, kf.centerY,
                        (initialW * scaleX).coerceIn(0.02f, 0.95f),
                        (initialH * scaleY).coerceIn(0.02f, 0.95f)
                    )
                    val jump = TrackingSampler.jumpDistance(mapped, currentBox)
                    if (jump <= TrackingSampler.JUMP_REJECT) {
                        keyframes.add(
                            kf.copy(
                                scaleX = scaleX,
                                scaleY = scaleY,
                                rotationDeg = if (settings.followRotation) kf.rotationDeg else lastRotation,
                                landmarkPoints = lastLandmarks,
                                confidence = frame.confidence
                            )
                        )
                        lastGoodBox = currentBox
                        currentBox = mapped
                        lastConfidence = frame.confidence
                        consecutiveLosses = 0
                        emitted = true
                    }
                }
            }

            if (!emitted) {
                consecutiveLosses++
                if (consecutiveLosses <= TrackingSampler.PREDICT_FRAMES && keyframes.size >= 2) {
                    val predicted = TrackingSampler.predictBox(currentBox, lastGoodBox)
                    keyframes.add(
                        MotionKeyframe(
                            timestampUs = currentUs,
                            centerX = predicted.centerX,
                            centerY = predicted.centerY,
                            scaleX = (predicted.width / initialW).coerceIn(0.2f, 5f),
                            scaleY = (predicted.height / initialH).coerceIn(0.2f, 5f),
                            rotationDeg = lastRotation,
                            confidence = 0.22f,
                            landmarkPoints = lastLandmarks
                        )
                    )
                    currentBox = predicted
                    lastConfidence = 0.22f
                } else if (keyframes.isNotEmpty()) {
                    val held = keyframes.last()
                    keyframes.add(held.copy(timestampUs = currentUs, confidence = 0.12f))
                    lastConfidence = 0.12f
                }
                if (TrackingSampler.isUnrecoverableYet(consecutiveLosses) &&
                    consecutiveLosses == TrackingSampler.RECOVERY_WINDOW_FRAMES
                ) {
                    val p = ((currentUs - startUs).toFloat() / safeDurationUs.toFloat()).coerceIn(0f, 1f)
                    report(p, "${label.replaceFirstChar { it.uppercase() }} target lost — still scanning", TrackingEngineState.LOST, true)
                }
            }

            val p = ((currentUs - startUs).toFloat() / safeDurationUs.toFloat()).coerceIn(0f, 1f)
            report(p, "Tracking $label... ${(p * 100).toInt()}%", state, false)
            frameIndex++
            currentUs += timeStepUs
        }
    }

    private fun planeTrackerFor(gray: GrayImage, box: NormalizedRect): PlaneTracker? {
        val tracker = PlaneTracker(gray, boxCorners(box, gray.width, gray.height), PlaneModel.SIMILARITY)
        return if (tracker.isReady) tracker else null
    }

    private fun boxCorners(box: NormalizedRect, w: Int, h: Int): List<Point2> = listOf(
        Point2(box.left * w, box.top * h),
        Point2(box.right * w, box.top * h),
        Point2(box.right * w, box.bottom * h),
        Point2(box.left * w, box.bottom * h)
    )

    private fun detectFace(
        frame: Bitmap,
        detector: com.google.mlkit.vision.face.FaceDetector,
        last: DetectedTarget?,
        hintBox: NormalizedRect?
    ): DetectedTarget? {
        val faces = runDetector { detector.process(InputImage.fromBitmap(frame, 0)) } ?: return null
        if (faces.isEmpty()) return null
        val fw = frame.width.toFloat().coerceAtLeast(1f)
        val fh = frame.height.toFloat().coerceAtLeast(1f)
        fun faceBox(f: Face): NormalizedRect {
            val box = f.boundingBox
            val cx = (box.centerX().toFloat() / fw).coerceIn(0f, 1f)
            val cy = (box.centerY().toFloat() / fh).coerceIn(0f, 1f)
            val fWidth = (box.width().toFloat() / fw).coerceAtLeast(0.01f)
            val fHeight = (box.height().toFloat() / fh).coerceAtLeast(0.01f)
            return TrackingSampler.boxFromCenter(cx, cy, fWidth, fHeight)
        }
        val primary: Face = if (last != null) {
            val byId = last.trackingId?.let { id -> faces.firstOrNull { it.trackingId == id } }
            byId ?: faces.minByOrNull { f ->
                val fb = faceBox(f)
                TrackingSampler.jumpDistance(fb, last.box) + abs(fb.width - last.box.width)
            }!!
        } else if (hintBox != null) {
            val nearest = faces.minByOrNull { TrackingSampler.jumpDistance(faceBox(it), hintBox) }!!
            val nearBox = faceBox(nearest)
            val overlap = TrackingSampler.overlapRatio(nearBox, hintBox)
            if (overlap > 0.05f || TrackingSampler.jumpDistance(nearBox, hintBox) < 0.45f) {
                nearest
            } else {
                faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }!!
            }
        } else {
            faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }!!
        }
        val box = primary.boundingBox
        val cx = (box.centerX().toFloat() / fw).coerceIn(0f, 1f)
        val cy = (box.centerY().toFloat() / fh).coerceIn(0f, 1f)
        val fWidth = (box.width().toFloat() / fw).coerceAtLeast(0.01f)
        val fHeight = (box.height().toFloat() / fh).coerceAtLeast(0.01f)
        val lms = mutableListOf<Pair<Float, Float>>()
        primary.getLandmark(FaceLandmark.LEFT_EYE)?.position?.let { lms.add(it.x / fw to it.y / fh) }
        primary.getLandmark(FaceLandmark.RIGHT_EYE)?.position?.let { lms.add(it.x / fw to it.y / fh) }
        primary.getLandmark(FaceLandmark.NOSE_BASE)?.position?.let { lms.add(it.x / fw to it.y / fh) }
        primary.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position?.let { lms.add(it.x / fw to it.y / fh) }
        val trackingId = try {
            val id = primary.trackingId
            if (id != null && id >= 0) id else null
        } catch (_: Throwable) {
            null
        }
        return DetectedTarget(
            box = TrackingSampler.boxFromCenter(cx, cy, fWidth, fHeight),
            rotationDeg = primary.headEulerAngleZ,
            confidence = 0.95f,
            landmarks = lms,
            trackingId = trackingId
        )
    }

    private fun detectBody(
        frame: Bitmap,
        detector: com.google.mlkit.vision.pose.PoseDetector,
        last: DetectedTarget?,
        hintBox: NormalizedRect?
    ): DetectedTarget? {
        val pose = runDetector { detector.process(InputImage.fromBitmap(frame, 0)) } ?: return null
        val fw = frame.width.toFloat().coerceAtLeast(1f)
        val fh = frame.height.toFloat().coerceAtLeast(1f)
        val ordered = BODY_LANDMARK_IDS.map { id -> pose.getPoseLandmark(id) }
        val visiblePairs = ordered.map { lm ->
            if (lm != null && lm.inFrameLikelihood > 0.4f) {
                (lm.position.x / fw) to (lm.position.y / fh)
            } else null
        }
        val visibleCount = visiblePairs.count { it != null }
        if (visibleCount < 4) return null

        val leftShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
        val rightShoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
        val leftHip = pose.getPoseLandmark(PoseLandmark.LEFT_HIP)
        val rightHip = pose.getPoseLandmark(PoseLandmark.RIGHT_HIP)
        var rotDeg = last?.rotationDeg ?: 0f
        var span = last?.box?.width ?: 0.2f
        if (leftShoulder != null && rightShoulder != null) {
            val dx = (rightShoulder.position.x - leftShoulder.position.x) / fw
            val dy = (rightShoulder.position.y - leftShoulder.position.y) / fh
            span = sqrt(dx * dx + dy * dy).coerceAtLeast(0.02f)
            rotDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        }

        val torsoX = listOfNotNull(
            leftShoulder?.position?.x, rightShoulder?.position?.x,
            leftHip?.position?.x, rightHip?.position?.x
        ).average().toFloat() / fw
        val torsoY = listOfNotNull(
            leftShoulder?.position?.y, rightShoulder?.position?.y,
            leftHip?.position?.y, rightHip?.position?.y
        ).average().toFloat() / fh

        val hint = last?.box ?: hintBox
        val anchorIndex = last?.anchorIndex ?: run {
            if (hint == null) 0
            else {
                var bestIdx = 0
                var bestDist = Float.MAX_VALUE
                visiblePairs.forEachIndexed { i, p ->
                    if (p == null) return@forEachIndexed
                    val d = hypot(p.first - hint.centerX, p.second - hint.centerY)
                    if (d < bestDist) {
                        bestDist = d
                        bestIdx = i
                    }
                }
                if (bestDist < 0.22f) bestIdx else 0
            }
        }
        val anchor = visiblePairs.getOrNull(anchorIndex)
        val cx = when {
            anchor != null -> anchor.first
            hint != null && last != null -> last.box.centerX
            else -> torsoX.takeIf { it.isFinite() } ?: 0.5f
        }
        val cy = when {
            anchor != null -> anchor.second
            hint != null && last != null -> last.box.centerY
            else -> torsoY.takeIf { it.isFinite() } ?: 0.5f
        }
        val half = (span * 1.2f).coerceIn(0.08f, 0.45f)
        val box = TrackingSampler.boxFromCenter(cx, cy, half * 2f, half * 2f)
        if (last != null && TrackingSampler.jumpDistance(box, last.box) > 0.55f && last.confidence > 0.5f) {
            return null
        }
        return DetectedTarget(
            box = box,
            rotationDeg = rotDeg,
            confidence = 0.90f,
            landmarks = visiblePairs.map { it ?: (-1f to -1f) },
            anchorIndex = anchorIndex
        )
    }

    private fun <T> runDetector(block: () -> com.google.android.gms.tasks.Task<T>): T? {
        return try {
            Tasks.await(block(), MLKIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "Detector inference failed: ${t.message}")
            null
        }
    }

    private suspend fun trackMotionPointsInternal(
        retriever: MediaMetadataRetriever,
        initialBox: NormalizedRect,
        startUs: Long,
        durationUs: Long,
        settings: MotionTrackingSettings,
        keyframes: MutableList<MotionKeyframe>,
        report: (Float, String, TrackingEngineState, Boolean) -> Unit
    ) {
        val planar = settings.featureMode == MotionFeatureType.PLANAR
        val effective = if (settings.featureMode == MotionFeatureType.POSITION_ONLY) {
            settings.copy(followScale = false, followRotation = false)
        } else settings
        val ok = trackWithPlaneModel(
            retriever, initialBox, startUs, durationUs, effective,
            if (planar) PlaneModel.HOMOGRAPHY else PlaneModel.SIMILARITY,
            planar, keyframes, report,
            if (planar) "planar surface" else "motion points"
        )
        if (!ok && keyframes.isEmpty()) {
            report(0f, "Not enough trackable detail in the selected area", TrackingEngineState.FAILED, true)
        }
    }

    private suspend fun trackWithPlaneModel(
        retriever: MediaMetadataRetriever,
        initialBox: NormalizedRect,
        startUs: Long,
        durationUs: Long,
        settings: MotionTrackingSettings,
        model: PlaneModel,
        emitCornerPin: Boolean,
        keyframes: MutableList<MotionKeyframe>,
        report: (Float, String, TrackingEngineState, Boolean) -> Unit,
        label: String
    ): Boolean {
        val safeDurationUs = durationUs.coerceAtLeast(1L)
        val timeStepUs = (1_000_000L / settings.accuracyFps.coerceIn(12, 60))
        val endUs = startUs + safeDurationUs

        val (reqW, reqH) = frameSize(retriever, TrackingSampler.FEATURE_WIDTH)
        val ref = frameGray(retriever, startUs, reqW, reqH, closestSync = true)
        if (ref == null) {
            report(0f, "Failed to read video frame at start time", TrackingEngineState.FAILED, true)
            return true
        }
        val w = ref.width
        val h = ref.height
        val corners = boxCorners(initialBox, w, h)
        var tracker = PlaneTracker(ref, corners, model)
        if (!tracker.isReady) return false

        var converter = TrackFrameConverter(
            corners, w, h, settings.followPosition, settings.followScale, settings.followRotation
        )
        keyframes.add(
            converter.toKeyframe(
                startUs,
                PlaneTrackFrame(Homography.identity(), 1f, tracker.initialFeatureCount, false),
                emitCornerPin
            )
        )

        var currentUs = startUs + timeStepUs
        var consecutiveLosses = 0
        var lastGood: MotionKeyframe? = keyframes.lastOrNull()
        var beforeGood: MotionKeyframe? = null
        while (currentUs <= endUs && coroutineContext.isActive) {
            coroutineContext.ensureActive()
            val gray = frameGray(retriever, currentUs, w, h, closestSync = false)
            if (gray != null && gray.width == w && gray.height == h) {
                val frame = tracker.process(gray)
                if (frame.lost || frame.confidence < TrackingSampler.CONFIDENCE_LOST) {
                    consecutiveLosses++
                    if (consecutiveLosses <= TrackingSampler.PREDICT_FRAMES && lastGood != null && beforeGood != null) {
                        val predicted = TrackingSampler.predictBox(
                            TrackingSampler.boxFromCenter(lastGood.centerX, lastGood.centerY, initialBox.width * lastGood.scaleX, initialBox.height * lastGood.scaleY),
                            TrackingSampler.boxFromCenter(beforeGood.centerX, beforeGood.centerY, initialBox.width * beforeGood.scaleX, initialBox.height * beforeGood.scaleY)
                        )
                        keyframes.add(
                            lastGood.copy(
                                timestampUs = currentUs,
                                centerX = predicted.centerX,
                                centerY = predicted.centerY,
                                confidence = 0.22f
                            )
                        )
                    } else if (lastGood != null) {
                        keyframes.add(lastGood.copy(timestampUs = currentUs, confidence = 0.12f))
                    }
                    if (TrackingSampler.isUnrecoverableYet(consecutiveLosses) &&
                        consecutiveLosses == TrackingSampler.RECOVERY_WINDOW_FRAMES
                    ) {
                        val p = ((currentUs - startUs).toFloat() / safeDurationUs.toFloat()).coerceIn(0f, 1f)
                        report(p, "Tracking lost ($label) — still scanning", TrackingEngineState.LOST, true)
                    }
                    val seedBox = lastGood?.let {
                        TrackingSampler.boxFromCenter(it.centerX, it.centerY, initialBox.width * it.scaleX, initialBox.height * it.scaleY)
                    } ?: initialBox
                    if (TrackingSampler.shouldRedetect(0, consecutiveLosses, 0.1f) && gray != null) {
                        val reseeded = PlaneTracker(gray, boxCorners(seedBox, w, h), model)
                        if (reseeded.isReady) {
                            tracker = reseeded
                            converter = TrackFrameConverter(
                                boxCorners(seedBox, w, h), w, h,
                                settings.followPosition, settings.followScale, settings.followRotation
                            )
                        }
                    }
                } else {
                    consecutiveLosses = 0
                    val kf = converter.toKeyframe(currentUs, frame, emitCornerPin)
                    keyframes.add(kf)
                    beforeGood = lastGood
                    lastGood = kf
                }
            }
            val p = ((currentUs - startUs).toFloat() / safeDurationUs.toFloat()).coerceIn(0f, 1f)
            val state = when {
                TrackingSampler.isUnrecoverableYet(consecutiveLosses) -> TrackingEngineState.LOST
                TrackingSampler.isRecovering(consecutiveLosses) -> TrackingEngineState.RECOVERING
                else -> TrackingEngineState.TRACKING
            }
            report(p, "Tracking $label... ${(p * 100).toInt()}%", state, false)
            currentUs += timeStepUs
        }
        return true
    }

    private fun frameSize(retriever: MediaMetadataRetriever, targetWidth: Int): Pair<Int, Int> {
        val vw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val vh = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val dw = if (rot == 90 || rot == 270) vh else vw
        val dh = if (rot == 90 || rot == 270) vw else vh
        if (dw <= 0 || dh <= 0) return Pair(targetWidth, targetWidth * 16 / 9)
        return Pair(targetWidth, (targetWidth * dh.toFloat() / dw.toFloat()).toInt().coerceAtLeast(32))
    }

    private fun frameGray(
        retriever: MediaMetadataRetriever,
        timeUs: Long,
        w: Int,
        h: Int,
        closestSync: Boolean
    ): GrayImage? {
        val bmp = extractScaledFrame(retriever, timeUs, w, h, closestSync) ?: return null
        return try {
            bitmapToGray(bmp)
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
        }
    }

    private fun bitmapToGray(bmp: Bitmap): GrayImage {
        val bw = bmp.width
        val bh = bmp.height
        val n = bw * bh
        if (argbScratch.size < n) argbScratch = IntArray(n)
        bmp.getPixels(argbScratch, 0, bw, 0, 0, bw, bh)
        return GrayImage.fromArgb(argbScratch, bw, bh)
    }

    /**
     * @param closestSync true for detector lock frames (keyframe-aligned, faster decode).
     *                    false (OPTION_CLOSEST) for tracking frames so timestamps stay accurate.
     */
    private fun extractScaledFrame(
        retriever: MediaMetadataRetriever,
        timeUs: Long,
        targetWidth: Int,
        targetHeight: Int = -1,
        closestSync: Boolean = false
    ): Bitmap? {
        val effectiveHeight = if (targetHeight > 0) targetHeight else (targetWidth * 16 / 9)
        val option = if (closestSync) MediaMetadataRetriever.OPTION_CLOSEST_SYNC else MediaMetadataRetriever.OPTION_CLOSEST
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                try {
                    retriever.getScaledFrameAtTime(timeUs, option, targetWidth, effectiveHeight)
                } catch (_: Throwable) {
                    fallbackScaledFrame(retriever, timeUs, targetWidth, option)
                }
            } else {
                fallbackScaledFrame(retriever, timeUs, targetWidth, option)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Frame extraction error at ${timeUs}us: ${t.message}")
            null
        }
    }

    private fun fallbackScaledFrame(
        retriever: MediaMetadataRetriever,
        timeUs: Long,
        targetWidth: Int,
        option: Int
    ): Bitmap? {
        val original = retriever.getFrameAtTime(timeUs, option) ?: return null
        val aspect = (original.height.toFloat() / original.width.toFloat().coerceAtLeast(1f)).coerceIn(0.1f, 10f)
        val targetHeight = (targetWidth * aspect).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(original, targetWidth, targetHeight, false)
        if (original != scaled && !original.isRecycled) original.recycle()
        return scaled
    }

    private fun setDataSourceSafe(retriever: MediaMetadataRetriever, context: Context?, videoUri: String) {
        val uri = Uri.parse(videoUri)
        if (context != null && (uri.scheme == "content" || uri.scheme == "android.resource")) {
            retriever.setDataSource(context, uri)
        } else {
            val file = File(videoUri)
            if (file.exists()) {
                retriever.setDataSource(videoUri)
            } else if (context != null) {
                retriever.setDataSource(context, uri)
            } else {
                retriever.setDataSource(videoUri)
            }
        }
    }

    private fun hypot(a: Float, b: Float): Float = sqrt(a * a + b * b)

    private fun matchTemplateSAD(
        refFrame: Bitmap,
        currFrame: Bitmap,
        lastBox: NormalizedRect,
        searchFactor: Float
    ): Pair<NormalizedRect, Float> {
        val w = refFrame.width
        val h = refFrame.height

        val patchLeft = (lastBox.left * w).toInt().coerceIn(0, (w - 4).coerceAtLeast(0))
        val patchTop = (lastBox.top * h).toInt().coerceIn(0, (h - 4).coerceAtLeast(0))
        val patchRight = (lastBox.right * w).toInt().coerceIn(patchLeft + 4, w)
        val patchBottom = (lastBox.bottom * h).toInt().coerceIn(patchTop + 4, h)

        val patchW = patchRight - patchLeft
        val patchH = patchBottom - patchTop

        val searchRadiusX = (patchW * searchFactor).toInt().coerceAtLeast(8)
        val searchRadiusY = (patchH * searchFactor).toInt().coerceAtLeast(8)

        val searchMinX = max(0, patchLeft - searchRadiusX)
        val searchMaxX = min(w - patchW, patchLeft + searchRadiusX)
        val searchMinY = max(0, patchTop - searchRadiusY)
        val searchMaxY = min(h - patchH, patchTop + searchRadiusY)

        val patchPixels = IntArray(patchW * patchH)
        refFrame.getPixels(patchPixels, 0, patchW, patchLeft, patchTop, patchW, patchH)

        var bestDiff = Long.MAX_VALUE
        var bestX = patchLeft
        var bestY = patchTop

        val step = 2
        val candidatePixels = IntArray(patchW * patchH)

        for (y in searchMinY until searchMaxY step step) {
            for (x in searchMinX until searchMaxX step step) {
                currFrame.getPixels(candidatePixels, 0, patchW, x, y, patchW, patchH)
                var currentDiff = 0L

                for (i in patchPixels.indices step 4) {
                    val p1 = patchPixels[i]
                    val p2 = candidatePixels[i]
                    val rDiff = abs(((p1 shr 16) and 0xFF) - ((p2 shr 16) and 0xFF))
                    val gDiff = abs(((p1 shr 8) and 0xFF) - ((p2 shr 8) and 0xFF))
                    val bDiff = abs((p1 and 0xFF) - (p2 and 0xFF))
                    currentDiff += (rDiff + gDiff + bDiff)
                    if (currentDiff > bestDiff) break
                }

                if (currentDiff < bestDiff) {
                    bestDiff = currentDiff
                    bestX = x
                    bestY = y
                }
            }
        }

        val totalPixelsSampled = (patchW * patchH) / 4
        val maxPossibleDiff = (totalPixelsSampled * 255 * 3).coerceAtLeast(1)
        val confidence = (1.0f - (bestDiff.toFloat() / maxPossibleDiff.toFloat())).coerceIn(0.1f, 1.0f)

        val matchedRect = NormalizedRect(
            left = bestX.toFloat() / w.toFloat(),
            top = bestY.toFloat() / h.toFloat(),
            right = (bestX + patchW).toFloat() / w.toFloat(),
            bottom = (bestY + patchH).toFloat() / h.toFloat()
        )
        return Pair(matchedRect, confidence)
    }

    fun smoothKeyframes(raw: List<MotionKeyframe>, smoothing: Float): List<MotionKeyframe> =
        TrackSmoother.smooth(raw, smoothing)
}
