package com.example.engine.ai

import com.example.domain.model.MaskSettings
import com.example.domain.model.MaskShape
import com.example.domain.model.Timeline
import com.example.domain.model.VideoClip
import com.example.data.local.TimelineSerializer
import com.example.engine.TimelineEngine
import com.example.engine.integration.KeyframeAnimationEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTrackCodecTest {

    private fun sampleResult(): TrackingResult {
        val keys = listOf(
            MotionKeyframe(0L, 0.25f, 0.40f, 1.0f, 1.0f, 5f, 0.95f, landmarkPoints = listOf(0.2f to 0.3f)),
            MotionKeyframe(500_000L, 0.55f, 0.45f, 1.2f, 1.1f, 8f, 0.90f, cornerPin = listOf(
                0.1f to 0.1f, 0.4f to 0.1f, 0.4f to 0.4f, 0.1f to 0.4f
            ))
        )
        return TrackingResult("tid", "clip-1", 0L, 1_000_000L, keys, TrackingCategory.FACE)
    }

    @Test
    fun roundTripPreservesKeyframesLandmarksAndCategory() {
        val original = sampleResult()
        val decoded = MotionTrackCodec.decode(MotionTrackCodec.encode(original))!!
        assertEquals(original.clipId, decoded.clipId)
        assertEquals(TrackingCategory.FACE, decoded.targetCategory)
        assertEquals(2, decoded.keyframes.size)
        assertEquals(0.55f, decoded.keyframes[1].centerX, 1e-4f)
        assertEquals(1, decoded.keyframes[0].landmarkPoints.size)
        assertEquals(4, decoded.keyframes[1].cornerPin.size)
    }

    @Test
    fun malformedInputIsNull() {
        assertNull(MotionTrackCodec.decode(null))
        assertNull(MotionTrackCodec.decode(""))
        assertNull(MotionTrackCodec.decode("nope"))
        assertNull(MotionTrackCodec.decode("v9|a|OBJECT|0|1|id|"))
    }

    @Test
    fun applyToMaskFollowsTrackAtSourceTime() {
        val mask = MaskSettings(enabled = true, shape = MaskShape.RECTANGLE, width = 0.4f, height = 0.4f, followTracking = true)
        val encoded = MotionTrackCodec.encode(sampleResult())
        val mid = MotionTrackCodec.applyToMask(mask, encoded, 250_000L)
        assertTrue(mid.posX > -0.6f && mid.posX < 0.2f)
        assertTrue(mid.width > 0.05f)
        val ignored = MotionTrackCodec.applyToMask(mask.copy(followTracking = false), encoded, 250_000L)
        assertEquals(0f, ignored.posX, 0f)
    }

    @Test
    fun timelinePersistsMotionTrackJson() {
        val engine = TimelineEngine()
        val clip = VideoClip(id = "v1", name = "V", timelineStartMs = 0L, durationMs = 4000L)
        engine.loadTimeline(Timeline(videoClips = listOf(clip)))
        val encoded = MotionTrackCodec.encode(sampleResult().copy(clipId = "v1"))
        assertTrue(engine.setClipMotionTrack("v1", encoded))
        val stored = engine.timeline.value.videoClips.single().motionTrackJson
        assertEquals(encoded, stored)
        val json = TimelineSerializer.toJson(engine.timeline.value)
        val restored = TimelineSerializer.fromJson(json).videoClips.single().motionTrackJson
        assertEquals(encoded, restored)
        val decoded = MotionTrackCodec.decode(restored)
        assertNotNull(decoded)
        assertEquals(2, decoded!!.keyframes.size)
    }

    @Test
    fun convertUsesHostClipSourceMapping() {
        val clip = VideoClip(
            id = "v1",
            name = "V",
            timelineStartMs = 1000L,
            durationMs = 4000L,
            sourceStartMs = 2000L,
            sourceEndMs = 6000L,
            speed = 1f
        )
        val result = TrackingResult(
            "t", "v1", 2_000_000L, 3_000_000L,
            listOf(MotionKeyframe(2_000_000L, 0.5f, 0.5f), MotionKeyframe(3_000_000L, 1f, 0.5f)),
            TrackingCategory.OBJECT
        )
        val keys = KeyframeAnimationEngine.convertTrackingResultToClipKeyframes(
            trackingResult = result,
            hostClip = clip,
            layerTimelineStartMs = 1000L
        )
        assertEquals(2, keys.size)
        assertEquals(0L, keys[0].timeMs)
        assertEquals(1000L, keys[1].timeMs)
        assertEquals(0f, keys[0].posX, 1e-4f)
        assertEquals(1f, keys[1].posX, 1e-4f)
    }

    @Test
    fun convertWithoutHostClipKeepsLegacyUsToMs() {
        val result = TrackingResult(
            "t", "c", 0L, 500_000L,
            listOf(MotionKeyframe(0L, 0.5f, 0.5f), MotionKeyframe(500_000L, 1f, 0f))
        )
        val keys = KeyframeAnimationEngine.convertTrackingResultToClipKeyframes(result)
        assertEquals(500L, keys[1].timeMs)
        assertEquals(1f, keys[1].posX, 1e-4f)
        assertEquals(-1f, keys[1].posY, 1e-4f)
    }

    @Test
    fun attachmentTargetsIncludeTransformAndEffect() {
        val names = AttachmentTarget.values().map { it.name }.toSet()
        assertTrue(names.contains("TRANSFORM"))
        assertTrue(names.contains("EFFECT"))
        assertTrue(names.contains("MASK"))
        assertFalse(names.contains("FAKE"))
    }
}
