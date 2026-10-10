package com.example.engine.ai

import com.ahstudio.face.core.FaceBounds
import com.ahstudio.face.core.FaceLandmarks
import com.ahstudio.face.core.FaceLandmarkType
import com.ahstudio.face.core.FaceRotation
import com.ahstudio.face.core.FaceTrackId
import com.ahstudio.face.core.FaceTrackState
import com.ahstudio.face.core.TrackedFace
import com.ahstudio.face.core.Vec2
import com.ahstudio.face.deformation.FaceWarpFaceSource
import com.example.domain.model.VideoClip
import kotlin.math.hypot
import kotlin.math.max

/**
 * Seeds a [TrackingResult] from a live ML detection so stickers / images / AR
 * can attach as soon as a face, body, or object is locked — without waiting
 * for the full-clip analyze pass.
 */
object MotionTrackingAttach {

    fun resultFromLive(
        clip: VideoClip,
        detections: List<LiveDetection>,
        selectedId: Int?,
        category: TrackingCategory,
        playheadSourceUs: Long,
        fallbackRegion: NormalizedRect? = null
    ): TrackingResult? {
        val det = detections.firstOrNull { it.id == selectedId }
            ?: detections.firstOrNull { it.category == category }
            ?: detections.firstOrNull()
        val box = det?.box ?: fallbackRegion ?: return null
        val startUs = (clip.sourceStartMs * 1000L).coerceAtMost(playheadSourceUs)
        val endUs = (clip.sourceEndMs * 1000L).coerceAtLeast(playheadSourceUs + 100_000L)
        val kf = MotionKeyframe(
            timestampUs = playheadSourceUs.coerceIn(startUs, endUs),
            centerX = box.centerX,
            centerY = box.centerY,
            scaleX = 1f,
            scaleY = 1f,
            confidence = det?.confidence?.coerceIn(0.2f, 1f) ?: 0.7f,
            landmarkPoints = det?.landmarks ?: emptyList()
        )
        return TrackingResult(
            targetId = "live_${clip.id}_${det?.id ?: 0}",
            clipId = clip.id,
            startTimestampUs = startUs,
            endTimestampUs = endUs,
            keyframes = listOf(
                kf.copy(timestampUs = startUs),
                kf,
                kf.copy(timestampUs = endUs)
            ).distinctBy { it.timestampUs },
            targetCategory = det?.category ?: category
        )
    }

    fun pickLockRegion(state: MotionTrackingUiState): NormalizedRect {
        val selected = state.liveDetections.firstOrNull { it.id == state.selectedLiveId }
        return (selected?.box ?: state.liveDetections.firstOrNull()?.box ?: state.targetRegion).clamped()
    }

    fun liveToTrackedFace(detection: LiveDetection, timeUs: Long): TrackedFace {
        val points = linkedMapOf<FaceLandmarkType, Vec2>()
        val lms = detection.landmarks
        when (detection.category) {
            TrackingCategory.FACE -> {
                putLm(points, lms, 0, FaceLandmarkType.LEFT_EYE)
                putLm(points, lms, 1, FaceLandmarkType.RIGHT_EYE)
                putLm(points, lms, 2, FaceLandmarkType.NOSE_BASE)
                putLm(points, lms, 3, FaceLandmarkType.MOUTH_BOTTOM)
            }
            TrackingCategory.BODY -> {
                putLm(points, lms, 0, FaceLandmarkType.NOSE_BASE)
                putLm(points, lms, 2, FaceLandmarkType.LEFT_EYE)
                putLm(points, lms, 5, FaceLandmarkType.RIGHT_EYE)
            }
            else -> Unit
        }
        val bounds = headBounds(detection, points)
        val inter = interOcular(points) ?: (bounds.width * 0.28f).coerceAtLeast(0.02f)
        return TrackedFace(
            trackId = FaceTrackId((detection.trackingId ?: detection.id).toLong()),
            detectorTrackId = detection.trackingId,
            timestampUs = timeUs,
            bounds = bounds,
            rotation = FaceRotation(0f, 0f, 0f),
            landmarks = if (points.isEmpty()) null else FaceLandmarks(points),
            classification = null,
            confidence = detection.confidence.coerceIn(0.2f, 1f),
            state = FaceTrackState.DETECTED,
            interOcular = inter
        )
    }

    fun keyframeToTrackedFace(
        kf: MotionKeyframe,
        category: TrackingCategory,
        lockWidth: Float = 0.30f,
        lockHeight: Float = 0.30f
    ): TrackedFace {
        val w = (lockWidth * kf.scaleX).coerceIn(0.04f, 0.95f)
        val h = (lockHeight * kf.scaleY).coerceIn(0.04f, 0.95f)
        val box = NormalizedRect(
            left = (kf.centerX - w / 2f).coerceIn(0f, 1f),
            top = (kf.centerY - h / 2f).coerceIn(0f, 1f),
            right = (kf.centerX + w / 2f).coerceIn(0f, 1f),
            bottom = (kf.centerY + h / 2f).coerceIn(0f, 1f)
        )
        val synthetic = LiveDetection(
            id = 0,
            category = category,
            box = box,
            label = category.title,
            confidence = kf.confidence,
            landmarks = kf.landmarkPoints
        )
        return liveToTrackedFace(synthetic, kf.timestampUs)
    }

    fun facesFromState(
        state: MotionTrackingUiState,
        clipId: String,
        sourceTimeUs: Long
    ): List<TrackedFace> {
        val live = state.liveDetections.filter {
            it.category == TrackingCategory.FACE || it.category == TrackingCategory.BODY
        }
        if (live.isNotEmpty()) {
            val selected = live.firstOrNull { it.id == state.selectedLiveId }
            val ordered = if (selected != null) listOf(selected) + live.filter { it.id != selected.id } else live
            return ordered.map { liveToTrackedFace(it, sourceTimeUs) }
        }
        val result = state.activeResult ?: return emptyList()
        if (result.clipId != clipId && result.clipId.isNotBlank()) return emptyList()
        if (result.keyframes.isEmpty()) return emptyList()
        if (result.targetCategory != TrackingCategory.FACE &&
            result.targetCategory != TrackingCategory.BODY
        ) {
            return emptyList()
        }
        val kf = MotionTrackingEvaluator(result).evaluate(sourceTimeUs)
        return listOf(
            keyframeToTrackedFace(
                kf,
                result.targetCategory,
                state.lockWidth.coerceAtLeast(0.08f),
                state.lockHeight.coerceAtLeast(0.08f)
            )
        )
    }

    private fun putLm(
        dest: MutableMap<FaceLandmarkType, Vec2>,
        lms: List<Pair<Float, Float>>,
        index: Int,
        type: FaceLandmarkType
    ) {
        val p = lms.getOrNull(index) ?: return
        if (p.first < 0f || p.second < 0f) return
        dest[type] = Vec2(p.first.coerceIn(0f, 1f), p.second.coerceIn(0f, 1f))
    }

    private fun headBounds(
        detection: LiveDetection,
        points: Map<FaceLandmarkType, Vec2>
    ): FaceBounds {
        val box = detection.box
        val nose = points[FaceLandmarkType.NOSE_BASE]
        if (detection.category == TrackingCategory.BODY && nose != null) {
            val headW = max(box.width * 0.42f, 0.10f)
            val headH = max(box.height * 0.22f, 0.12f)
            return FaceBounds(
                left = (nose.x - headW / 2f).coerceIn(0f, 1f),
                top = (nose.y - headH * 0.65f).coerceIn(0f, 1f),
                right = (nose.x + headW / 2f).coerceIn(0f, 1f),
                bottom = (nose.y + headH * 0.45f).coerceIn(0f, 1f)
            )
        }
        return FaceBounds(box.left, box.top, box.right, box.bottom)
    }

    private fun interOcular(points: Map<FaceLandmarkType, Vec2>): Float? {
        val l = points[FaceLandmarkType.LEFT_EYE] ?: return null
        val r = points[FaceLandmarkType.RIGHT_EYE] ?: return null
        return hypot(l.x - r.x, l.y - r.y).takeIf { it > 1e-4f }
    }
}

/**
 * Uses the ML Kit clip tracker first, then the motion-tracking lock (live box or
 * completed path) so AR filters stay on the face / body instead of floating.
 */
class MotionFaceFallbackSource(
    private val primary: FaceWarpFaceSource,
    private val fallback: (clipId: String, sourceTimeUs: Long) -> List<TrackedFace>
) : FaceWarpFaceSource {
    override fun facesAt(clipId: String, sourceTimeUs: Long, blocking: Boolean): List<TrackedFace> {
        val primaryFaces = runCatching { primary.facesAt(clipId, sourceTimeUs, blocking) }
            .getOrDefault(emptyList())
        if (primaryFaces.isNotEmpty()) return primaryFaces
        return runCatching { fallback(clipId, sourceTimeUs) }.getOrDefault(emptyList())
    }
}
