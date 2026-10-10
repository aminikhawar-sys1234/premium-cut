package com.example.engine.ai

import com.ahstudio.face.core.FaceLandmarkType
import com.example.domain.model.VideoClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTrackingAttachTest {

    private fun clip() = VideoClip(
        id = "clip-1",
        name = "clip",
        sourceStartMs = 0L,
        sourceEndMs = 5000L,
        durationMs = 5000L
    )

    @Test
    fun liveFaceSeedsAttachableResultOnDetectedBox() {
        val face = LiveDetection(
            id = 7,
            category = TrackingCategory.FACE,
            box = NormalizedRect(0.30f, 0.20f, 0.55f, 0.52f),
            label = "Face 1",
            confidence = 0.9f,
            landmarks = listOf(0.36f to 0.30f, 0.48f to 0.30f, 0.42f to 0.38f, 0.42f to 0.46f)
        )
        val result = MotionTrackingAttach.resultFromLive(
            clip = clip(),
            detections = listOf(face),
            selectedId = 7,
            category = TrackingCategory.FACE,
            playheadSourceUs = 1_000_000L
        )
        assertNotNull(result)
        assertTrue(result!!.keyframes.isNotEmpty())
        assertEquals(0.425f, result.keyframes[0].centerX, 0.001f)
        assertEquals(0.36f, result.keyframes[0].centerY, 0.001f)
        assertEquals(TrackingCategory.FACE, result.targetCategory)
    }

    @Test
    fun liveFaceConvertsToTrackedFaceAtSameBox() {
        val face = LiveDetection(
            id = 1,
            category = TrackingCategory.FACE,
            box = NormalizedRect(0.20f, 0.25f, 0.50f, 0.65f),
            label = "Face 1",
            confidence = 0.85f,
            landmarks = listOf(0.28f to 0.36f, 0.42f to 0.36f, 0.35f to 0.44f, 0.35f to 0.55f)
        )
        val tracked = MotionTrackingAttach.liveToTrackedFace(face, 2_000_000L)
        assertEquals(0.20f, tracked.bounds.left, 0.001f)
        assertEquals(0.25f, tracked.bounds.top, 0.001f)
        assertEquals(0.50f, tracked.bounds.right, 0.001f)
        assertEquals(0.65f, tracked.bounds.bottom, 0.001f)
        assertEquals(0.28f, tracked.landmarks!![FaceLandmarkType.LEFT_EYE]!!.x, 0.001f)
        assertEquals(0.42f, tracked.landmarks!![FaceLandmarkType.RIGHT_EYE]!!.x, 0.001f)
    }

    @Test
    fun bodyDetectionPinsArFilterToHeadNotTorso() {
        val landmarks = MutableList(33) { -1f to -1f }
        landmarks[0] = 0.50f to 0.22f
        landmarks[2] = 0.46f to 0.20f
        landmarks[5] = 0.54f to 0.20f
        val body = LiveDetection(
            id = 0,
            category = TrackingCategory.BODY,
            box = NormalizedRect(0.25f, 0.10f, 0.75f, 0.95f),
            label = "Full Body",
            confidence = 0.8f,
            landmarks = landmarks
        )
        val tracked = MotionTrackingAttach.liveToTrackedFace(body, 0L)
        assertTrue("head box must be shorter than the body", tracked.bounds.height < body.box.height * 0.5f)
        assertTrue(tracked.bounds.centerY < 0.40f)
        assertEquals(0.50f, tracked.landmarks!![FaceLandmarkType.NOSE_BASE]!!.x, 0.001f)
    }

    @Test
    fun pickLockRegionPrefersSelectedLiveBox() {
        val state = MotionTrackingUiState(
            targetRegion = NormalizedRect.DEFAULT_CENTER,
            selectedLiveId = 2,
            liveDetections = listOf(
                LiveDetection(1, TrackingCategory.FACE, NormalizedRect(0.1f, 0.1f, 0.3f, 0.3f), "A", 0.7f),
                LiveDetection(2, TrackingCategory.FACE, NormalizedRect(0.6f, 0.2f, 0.85f, 0.5f), "B", 0.8f)
            )
        )
        val box = MotionTrackingAttach.pickLockRegion(state)
        assertEquals(0.725f, box.centerX, 0.001f)
    }

    @Test
    fun previewTrackStaysVisibleWithLiveOrCompletedResult() {
        val empty = MotionTrackingUiState()
        assertTrue(!empty.hasPreviewTrack)
        assertTrue(!empty.canAttachLayer)
        val live = MotionTrackingUiState(
            liveDetections = listOf(
                LiveDetection(1, TrackingCategory.FACE, NormalizedRect(0.2f, 0.2f, 0.4f, 0.4f), "Face", 0.9f)
            )
        )
        assertTrue(live.hasPreviewTrack)
        assertTrue(live.canAttachLayer)
        val tracked = MotionTrackingUiState(
            activeResult = MotionTrackingAttach.resultFromLive(
                clip = clip(),
                detections = live.liveDetections,
                selectedId = 1,
                category = TrackingCategory.FACE,
                playheadSourceUs = 0L
            )
        )
        assertTrue(tracked.hasPreviewTrack)
        assertTrue(tracked.canAttachLayer)
    }

    @Test
    fun facesFromStateUseLiveLockBeforeEmptyTrack() {
        val state = MotionTrackingUiState(
            liveDetections = listOf(
                LiveDetection(
                    id = 4,
                    category = TrackingCategory.FACE,
                    box = NormalizedRect(0.4f, 0.3f, 0.7f, 0.7f),
                    label = "Face 1",
                    confidence = 0.9f
                )
            )
        )
        val faces = MotionTrackingAttach.facesFromState(state, "clip-1", 500_000L)
        assertEquals(1, faces.size)
        assertEquals(0.55f, faces[0].bounds.centerX, 0.001f)
    }
}
