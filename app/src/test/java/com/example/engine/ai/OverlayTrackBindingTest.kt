package com.example.engine.ai

import com.example.domain.model.AspectRatio
import com.example.domain.model.EffectType
import com.example.domain.model.EffectClip
import com.example.domain.model.MaskSettings
import com.example.domain.model.MaskShape
import com.example.domain.model.StickerClip
import com.example.domain.model.TextClip
import com.example.domain.model.Timeline
import com.example.domain.model.VideoClip
import com.example.engine.KeyframeInterpolator
import com.example.engine.timeline.TimelineEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayTrackBindingTest {

    private fun movingFace(): TrackingResult {
        val keys = listOf(
            MotionKeyframe(0L, 0.20f, 0.30f, 1.0f, 1.0f, 0f, 0.95f, landmarkPoints = listOf(0.18f to 0.28f)),
            MotionKeyframe(500_000L, 0.50f, 0.30f, 1.2f, 1.1f, 15f, 0.92f),
            MotionKeyframe(1_000_000L, 0.80f, 0.55f, 1.4f, 1.3f, 30f, 0.90f)
        )
        return TrackingResult("face-a", "host", 0L, 1_000_000L, keys, TrackingCategory.FACE)
    }

    private fun movingObject(): TrackingResult {
        val keys = listOf(
            MotionKeyframe(0L, 0.70f, 0.70f, 1.0f, 1.0f, 0f, 0.9f),
            MotionKeyframe(1_000_000L, 0.30f, 0.70f, 1.0f, 1.0f, 0f, 0.88f)
        )
        return TrackingResult("obj-b", "host", 0L, 1_000_000L, keys, TrackingCategory.OBJECT)
    }

    private fun rotatingScaling(): TrackingResult {
        val keys = listOf(
            MotionKeyframe(0L, 0.5f, 0.5f, 1.0f, 1.0f, 0f, 0.95f),
            MotionKeyframe(1_000_000L, 0.5f, 0.5f, 2.0f, 2.0f, 90f, 0.95f)
        )
        return TrackingResult("rot", "host", 0L, 1_000_000L, keys, TrackingCategory.OBJECT)
    }

    private fun occlusionThenReenter(): TrackingResult {
        val keys = listOf(
            MotionKeyframe(0L, 0.2f, 0.5f, 1f, 1f, 0f, 0.95f),
            MotionKeyframe(200_000L, 0.2f, 0.5f, 1f, 1f, 0f, 0.12f),
            MotionKeyframe(400_000L, 0.2f, 0.5f, 1f, 1f, 0f, 0.12f),
            MotionKeyframe(800_000L, 0.8f, 0.5f, 1f, 1f, 0f, 0.93f)
        )
        return TrackingResult("occ", "host", 0L, 1_000_000L, keys, TrackingCategory.FACE)
    }

    private fun host(
        id: String = "host",
        timelineStartMs: Long = 0L,
        durationMs: Long = 4000L,
        sourceStartMs: Long = 0L,
        sourceEndMs: Long = 4000L,
        speed: Float = 1f,
        width: Int = 1080,
        height: Int = 1920,
        rotation: Int = 0
    ) = VideoClip(
        id = id,
        name = "V",
        timelineStartMs = timelineStartMs,
        durationMs = durationMs,
        sourceStartMs = sourceStartMs,
        sourceEndMs = sourceEndMs,
        speed = speed,
        width = width,
        height = height,
        naturalRotation = rotation
    )

    @Test
    fun roundTripIndependentOfClipMotionTrack() {
        val result = movingFace()
        val binding = OverlayTrackCodec.fromHost(result, host(), true, true, true, 0.1f, -0.2f, canvasAspect = 9f / 16f)
        val decoded = OverlayTrackCodec.decode(OverlayTrackCodec.encode(binding))!!
        assertEquals("face-a", decoded.targetId)
        assertEquals("host", decoded.hostClipId)
        assertEquals(0.1f, decoded.offsetX, 1e-4f)
        val restored = MotionTrackCodec.decode(decoded.encodedTrack)!!
        assertEquals(3, restored.keyframes.size)
        assertEquals(TrackingCategory.FACE, restored.targetCategory)
    }

    @Test
    fun stickerFollowsFaceAndPreservesUserOffset() {
        val h = host()
        val result = movingFace()
        val json = OverlayTrackCodec.encode(OverlayTrackCodec.fromHost(result, h, true, true, true, 0f, 0f))
        val sticker = StickerClip(
            id = "s1",
            emojiOrAsset = "😎",
            timelineStartMs = 0L,
            durationMs = 4000L,
            posX = 0.15f,
            posY = -0.05f,
            scale = 1f,
            trackBindJson = json
        )
        val t0 = KeyframeInterpolator.interpolate(sticker, 0L)
        val t1 = KeyframeInterpolator.interpolate(sticker, 1000L)
        assertTrue(t1.posX > t0.posX)
        val tracked0 = OverlayTrackCodec.sample(OverlayTrackCodec.decode(json)!!, 0L, Timeline(videoClips = listOf(h)))
        assertEquals(tracked0.posX + 0.15f, t0.posX, 1e-3f)
        assertEquals(tracked0.posY - 0.05f, t0.posY, 1e-3f)
    }

    @Test
    fun textFollowsObjectIndependentlyOfFaceSticker() {
        val h = host()
        val faceJson = OverlayTrackCodec.encode(OverlayTrackCodec.fromHost(movingFace(), h, true, true, true, 0f, 0f))
        val objJson = OverlayTrackCodec.encode(OverlayTrackCodec.fromHost(movingObject(), h, true, true, true, 0f, 0f))
        val sticker = StickerClip(id = "s", timelineStartMs = 0L, durationMs = 2000L, trackBindJson = faceJson)
        val text = TextClip(id = "t", timelineStartMs = 0L, durationMs = 2000L, posY = 0f, trackBindJson = objJson)
        val s0 = KeyframeInterpolator.interpolate(sticker, 0L)
        val s1 = KeyframeInterpolator.interpolate(sticker, 1000L)
        val t0 = KeyframeInterpolator.interpolate(text, 0L)
        val t1 = KeyframeInterpolator.interpolate(text, 1000L)
        assertTrue(s1.posX > s0.posX)
        assertTrue(t1.posX < t0.posX)
        assertTrue(kotlin.math.abs(s0.posX - t0.posX) > 0.05f)
    }

    @Test
    fun rotationAndScaleFollowTarget() {
        val h = host()
        val json = OverlayTrackCodec.encode(OverlayTrackCodec.fromHost(rotatingScaling(), h, true, true, true, 0f, 0f))
        val sticker = StickerClip(id = "s", durationMs = 2000L, scale = 1f, trackBindJson = json)
        val start = KeyframeInterpolator.interpolate(sticker, 0L)
        val end = KeyframeInterpolator.interpolate(sticker, 1000L)
        assertEquals(1f, start.scaleX, 0.05f)
        assertEquals(2f, end.scaleX, 0.05f)
        assertEquals(90f, end.rotation, 0.5f)
    }

    @Test
    fun temporaryOcclusionHoldsThenReacquires() {
        val mid = MotionTrackingEvaluator(occlusionThenReenter()).evaluate(300_000L)
        assertEquals(0.2f, mid.centerX, 0.02f)
        assertTrue(mid.confidence < 0.2f)
        val back = MotionTrackingEvaluator(occlusionThenReenter()).evaluate(800_000L)
        assertEquals(0.8f, back.centerX, 0.02f)
        assertTrue(back.confidence > 0.8f)
    }

    @Test
    fun seekAndPlayUseSourceTimeNotUiFrames() {
        val h = host(timelineStartMs = 2000L, durationMs = 4000L, sourceStartMs = 1000L, sourceEndMs = 5000L)
        val result = TrackingResult(
            "t", "host", 1_000_000L, 2_000_000L,
            listOf(
                MotionKeyframe(1_000_000L, 0.2f, 0.5f, 1f, 1f, 0f, 1f),
                MotionKeyframe(2_000_000L, 0.8f, 0.5f, 1f, 1f, 0f, 1f)
            ),
            TrackingCategory.OBJECT
        )
        val binding = OverlayTrackCodec.fromHost(result, h, true, true, true, 0f, 0f)
        val atStart = OverlayTrackCodec.sample(binding, 2000L, Timeline(videoClips = listOf(h)))
        val atOneSec = OverlayTrackCodec.sample(binding, 3000L, Timeline(videoClips = listOf(h)))
        assertEquals(1_000_000L, atStart.sourceTimeUs)
        assertEquals(2_000_000L, atOneSec.sourceTimeUs)
        assertTrue(atOneSec.posX > atStart.posX)
    }

    @Test
    fun trimAndSpeedChangeRemapViaLiveHost() {
        val trimmed = host(sourceStartMs = 500L, sourceEndMs = 2500L, durationMs = 2000L, speed = 2f)
        val result = TrackingResult(
            "t", "host", 500_000L, 2_500_000L,
            listOf(
                MotionKeyframe(500_000L, 0.1f, 0.5f),
                MotionKeyframe(2_500_000L, 0.9f, 0.5f)
            )
        )
        val binding = OverlayTrackCodec.fromHost(result, trimmed, true, true, true, 0f, 0f)
        val pose = OverlayTrackCodec.sample(binding, 1000L, Timeline(videoClips = listOf(trimmed)))
        assertEquals(2_500_000L, pose.sourceTimeUs)
    }

    @Test
    fun splitKeepsBindingOnMatchingUri() {
        val first = host(id = "host", durationMs = 1000L, sourceEndMs = 1000L).copy(uri = "file://clip.mp4")
        val second = host(id = "host-b", timelineStartMs = 1000L, durationMs = 1000L, sourceStartMs = 1000L, sourceEndMs = 2000L).copy(uri = "file://clip.mp4")
        val result = movingFace()
        val binding = OverlayTrackCodec.fromHost(result, first, true, true, true, 0f, 0f)
        val found = OverlayTrackCodec.findHost(Timeline(videoClips = listOf(first, second)), binding, 1500L)
        assertEquals("host-b", found?.id)
    }

    @Test
    fun previewAndExportSampleTheSamePose() {
        val h = host()
        val result = movingFace()
        val json = OverlayTrackCodec.encode(OverlayTrackCodec.fromHost(result, h, true, true, true, 0.05f, 0f))
        val sticker = StickerClip(id = "s", durationMs = 4000L, posX = 0.1f, trackBindJson = json)
        val timeline = Timeline(videoClips = listOf(h), stickerClips = listOf(sticker), aspectRatio = AspectRatio.RATIO_9_16)
        val preview = TrackingEvalContext.withTimeline(timeline) {
            KeyframeInterpolator.interpolate(sticker, 500L)
        }
        val exported = TimelineEvaluator().evaluate(timeline, 500L).activeStickers.single()
        assertEquals(preview.posX, exported.posX, 1e-4f)
        assertEquals(preview.posY, exported.posY, 1e-4f)
        assertEquals(preview.scale, exported.scale, 1e-4f)
        assertEquals(preview.rotation, exported.rotation, 1e-3f)
    }

    @Test
    fun maskBindingDoesNotUseLaterClipTrack() {
        val face = movingFace()
        val obj = movingObject()
        val maskBind = OverlayTrackCodec.encode(OverlayTrackCodec.fromHost(face, host(), true, true, true, 0f, 0f))
        val mask = MaskSettings(enabled = true, shape = MaskShape.RECTANGLE, width = 0.4f, height = 0.4f, followTracking = true, trackBindJson = maskBind)
        val atFace = OverlayTrackCodec.applyToMask(mask, MotionTrackCodec.encode(obj), 0L)
        val atObj = MotionTrackCodec.applyToMask(mask.copy(trackBindJson = null, followTracking = true), MotionTrackCodec.encode(obj), 0L)
        assertTrue(kotlin.math.abs(atFace.posX - atObj.posX) > 0.2f)
    }

    @Test
    fun letterboxMappingKeepsCenterAndContractsVertical() {
        val (ndcX, ndcY) = TrackingCoordinateSpace.videoNormToCanvasNdc(
            centerX = 0.5f, centerY = 0.5f,
            videoWidth = 1920, videoHeight = 1080, naturalRotation = 0,
            canvasAspect = 9f / 16f
        )
        assertEquals(0f, ndcX, 1e-4f)
        assertEquals(0f, ndcY, 1e-4f)
        val (topX, topY) = TrackingCoordinateSpace.videoNormToCanvasNdc(
            0.5f, 0f, 1920, 1080, 0, 9f / 16f
        )
        assertEquals(0f, topX, 1e-4f)
        assertTrue(topY > -1f && topY < 0f)
    }

    @Test
    fun rotatedSourceUsesDisplayAspect() {
        val landscape = TrackingCoordinateSpace.videoNormToCanvasNdc(0.5f, 0f, 1920, 1080, 0, 9f / 16f)
        val rotated = TrackingCoordinateSpace.videoNormToCanvasNdc(0.5f, 0f, 1920, 1080, 90, 9f / 16f)
        assertTrue(kotlin.math.abs(landscape.second - rotated.second) > 0.1f)
    }

    @Test
    fun fourKAnd1080ShareNormalizedPose() {
        val h1080 = host(width = 1080, height = 1920)
        val h4k = host(id = "4k", width = 2160, height = 3840)
        val result = movingFace()
        val a = OverlayTrackCodec.sample(OverlayTrackCodec.fromHost(result, h1080, true, true, true, 0f, 0f), 500L)
        val b = OverlayTrackCodec.sample(OverlayTrackCodec.fromHost(result, h4k, true, true, true, 0f, 0f), 500L)
        assertEquals(a.posX, b.posX, 1e-4f)
        assertEquals(a.posY, b.posY, 1e-4f)
    }

    @Test
    fun trackingDoesNotMutateVideoColorFields() {
        val clip = host().copy(
            filter = com.example.domain.model.FilterSettings(),
            adjustments = com.example.domain.model.VideoAdjustments(brightness = 0.2f)
        )
        val json = OverlayTrackCodec.encode(OverlayTrackCodec.fromHost(movingFace(), clip, true, true, true, 0f, 0f))
        val bound = clip.copy(trackBindJson = json)
        val kf = KeyframeInterpolator.interpolate(bound, 500L)
        assertEquals(0.2f, bound.adjustments!!.brightness, 0f)
        assertEquals(1f, kf.opacity, 0f)
    }

    @Test
    fun multipleTargetsDoNotOverwriteEachOther() {
        val h = host()
        val a = OverlayTrackCodec.encode(OverlayTrackCodec.fromHost(movingFace().copy(targetId = "A"), h, true, true, true, 0f, 0f))
        val b = OverlayTrackCodec.encode(OverlayTrackCodec.fromHost(movingObject().copy(targetId = "B"), h, true, true, true, 0f, 0f))
        assertNotEquals(OverlayTrackCodec.decode(a)!!.targetId, OverlayTrackCodec.decode(b)!!.targetId)
        val sA = StickerClip(id = "a", durationMs = 2000L, trackBindJson = a)
        val sB = StickerClip(id = "b", durationMs = 2000L, trackBindJson = b)
        assertTrue(
            kotlin.math.abs(
                KeyframeInterpolator.interpolate(sA, 0L).posX -
                    KeyframeInterpolator.interpolate(sB, 0L).posX
            ) > 0.05f
        )
    }

    @Test
    fun blurEffectStoresIndependentBinding() {
        val json = OverlayTrackCodec.encode(OverlayTrackCodec.fromHost(movingFace(), host(), true, true, true, 0f, 0f))
        val effect = EffectClip(effectType = EffectType.BLUR, durationMs = 3000L, trackBindJson = json)
        assertEquals("face-a", OverlayTrackCodec.decode(effect.trackBindJson)!!.targetId)
    }

    @Test
    fun longDurationSamplingStaysInsideTrack() {
        val keys = (0..240).map { i ->
            MotionKeyframe(i * 41_666L, 0.2f + i * 0.002f, 0.5f, 1f, 1f, 0f, 0.9f)
        }
        val result = TrackingResult("long", "host", 0L, keys.last().timestampUs, keys, TrackingCategory.OBJECT)
        val last = MotionTrackingEvaluator(result).evaluate(10_000_000L)
        assertEquals(keys.last().centerX, last.centerX, 1e-4f)
        assertEquals(keys.last().timestampUs, last.timestampUs)
    }
}
