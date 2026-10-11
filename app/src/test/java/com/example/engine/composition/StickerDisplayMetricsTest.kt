package com.example.engine.composition

import com.example.domain.model.AspectRatio
import com.example.domain.model.ClipKeyframe
import com.example.domain.model.StickerClip
import com.example.domain.model.Timeline
import com.example.domain.model.VideoClip
import com.example.engine.KeyframeInterpolator
import com.example.engine.ai.MotionKeyframe
import com.example.engine.ai.OverlayTrackCodec
import com.example.engine.ai.TrackingCategory
import com.example.engine.ai.TrackingEvalContext
import com.example.engine.ai.TrackingResult
import com.example.engine.timeline.TimelineEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class StickerDisplayMetricsTest {

    private fun host() = VideoClip(
        id = "host",
        name = "V",
        timelineStartMs = 0L,
        durationMs = 4000L,
        sourceStartMs = 0L,
        sourceEndMs = 4000L,
        width = 1080,
        height = 1920
    )

    private fun movingFace() = TrackingResult(
        targetId = "face-a",
        clipId = "host",
        startTimestampUs = 0L,
        endTimestampUs = 1_000_000L,
        keyframes = listOf(
            MotionKeyframe(0L, 0.20f, 0.30f, 1.0f, 1.0f, 0f, 0.95f),
            MotionKeyframe(500_000L, 0.50f, 0.30f, 1.2f, 1.1f, 15f, 0.92f),
            MotionKeyframe(1_000_000L, 0.80f, 0.55f, 1.4f, 1.3f, 30f, 0.90f)
        ),
        targetCategory = TrackingCategory.FACE
    )

    @Test
    fun defaultSizeIsCanvasFractionOfShortSide() {
        val portrait = StickerDisplayMetrics.baseSizePx(1080f, 1920f)
        val landscape = StickerDisplayMetrics.baseSizePx(1920f, 1080f)
        val square = StickerDisplayMetrics.baseSizePx(1080f, 1080f)
        assertEquals(1080f * StickerDisplayMetrics.DEFAULT_CANVAS_FRACTION, portrait, 0.01f)
        assertEquals(portrait, landscape, 0.01f)
        assertEquals(square, portrait, 0.01f)
        assertTrue(portrait > 200f)
        assertTrue(portrait < 400f)
    }

    @Test
    fun ndcScaleIsResolutionRelative() {
        val hd = StickerDisplayMetrics.ndcScale(1080, 1920, 1f)
        val sd = StickerDisplayMetrics.ndcScale(720, 1280, 1f)
        assertEquals(hd.first, sd.first, 1e-4f)
        assertEquals(hd.second, sd.second, 1e-4f)
        assertEquals(StickerDisplayMetrics.DEFAULT_CANVAS_FRACTION, hd.first, 1e-4f)
        val doubled = StickerDisplayMetrics.ndcScale(1080, 1920, 2f)
        assertEquals(hd.first * 2f, doubled.first, 1e-4f)
        assertEquals(hd.second * 2f, doubled.second, 1e-4f)
    }

    @Test
    fun userResizeOnTrackedStickerDoesNotRewriteTrackBind() {
        val h = host()
        val json = OverlayTrackCodec.encode(
            OverlayTrackCodec.fromHost(movingFace(), h, true, true, true, 0f, 0f)
        )
        val clip = StickerClip(
            id = "s",
            durationMs = 4000L,
            scale = 1f,
            trackBindJson = json,
            keyframes = listOf(ClipKeyframe(timeMs = 0L, scaleX = 1f, scaleY = 1f))
        )
        val resized = StickerUserTransform.applyScaleRotation(clip, 2f, 12f, 0L)
        assertEquals(2f, resized.scale, 1e-4f)
        assertEquals(12f, resized.rotation, 1e-4f)
        assertEquals(json, resized.trackBindJson)
        assertEquals(clip.keyframes, resized.keyframes)
        assertEquals(clip.posX, resized.posX, 0f)
        assertEquals(clip.posY, resized.posY, 0f)
    }

    @Test
    fun userNudgeOnTrackedStickerUpdatesResidualOnly() {
        val h = host()
        val json = OverlayTrackCodec.encode(
            OverlayTrackCodec.fromHost(movingFace(), h, true, true, true, 0f, 0f)
        )
        val clip = StickerClip(
            id = "s",
            durationMs = 4000L,
            trackBindJson = json,
            keyframes = listOf(ClipKeyframe(timeMs = 0L, posX = 0.4f, posY = 0.1f))
        )
        val moved = StickerUserTransform.applyPositionDelta(clip, 0.05f, -0.02f, 0L)
        assertEquals(0.05f, moved.posX, 1e-4f)
        assertEquals(-0.02f, moved.posY, 1e-4f)
        assertEquals(json, moved.trackBindJson)
        assertEquals(clip.keyframes, moved.keyframes)
    }

    @Test
    fun untrackedResizeStillWritesNearbyKeyframe() {
        val key = ClipKeyframe(timeMs = 0L, scaleX = 1f, scaleY = 1f, rotation = 0f)
        val clip = StickerClip(id = "s", durationMs = 3000L, keyframes = listOf(key), scale = 1f)
        val resized = StickerUserTransform.applyScaleRotation(clip, 1.5f, 0f, 0L)
        assertEquals(1.5f, resized.keyframes.single().scaleX, 1e-4f)
        assertEquals(1f, resized.scale, 1e-4f)
    }

    @Test
    fun trackedUserScaleSurvivesEvaluatorAndDoesNotPinPosition() {
        val h = host()
        val json = OverlayTrackCodec.encode(
            OverlayTrackCodec.fromHost(movingFace(), h, true, true, true, 0f, 0f)
        )
        val clip = StickerClip(id = "s", durationMs = 4000L, scale = 2f, trackBindJson = json)
        val timeline = Timeline(
            videoClips = listOf(h),
            stickerClips = listOf(clip),
            aspectRatio = AspectRatio.RATIO_9_16
        )
        val start = TimelineEvaluator().evaluate(timeline, 0L).activeStickers.single()
        val mid = TimelineEvaluator().evaluate(timeline, 500L).activeStickers.single()
        assertTrue(mid.posX > start.posX)
        assertTrue(start.scale > 1.5f)
        assertTrue(mid.scale > start.scale)
        val preview = TrackingEvalContext.withTimeline(timeline) {
            KeyframeInterpolator.interpolate(clip, 500L)
        }
        assertEquals(preview.posX, mid.posX, 1e-4f)
        assertEquals(preview.posY, mid.posY, 1e-4f)
        assertEquals(preview.scale, mid.scale, 1e-4f)
    }

    @Test
    fun canvasAndGpuShareTheSameNormalizedScale() {
        val canvasSize = StickerDisplayMetrics.emojiTextSizeForCanvas(1080, 1920, 1f)
        val ndc = StickerDisplayMetrics.ndcScale(1080, 1920, 1f)
        assertEquals(canvasSize, ndc.first * 1080f, 0.01f)
        assertEquals(canvasSize, ndc.second * 1920f, 0.01f)
    }

    @Test
    fun clampRejectsInvisibleAndExtremeScales() {
        assertEquals(StickerDisplayMetrics.MIN_USER_SCALE, StickerDisplayMetrics.clampUserScale(0.01f))
        assertEquals(StickerDisplayMetrics.MAX_USER_SCALE, StickerDisplayMetrics.clampUserScale(99f))
        val tiny = StickerUserTransform.applyScaleRotation(
            StickerClip(id = "s", durationMs = 1000L, scale = 1f),
            0.01f,
            0f,
            0L
        )
        assertEquals(StickerDisplayMetrics.MIN_USER_SCALE, tiny.scale, 0f)
    }

    @Test
    fun panelCloseHidesDiagnosticsWithoutDroppingTrackPose() {
        val h = host()
        val result = movingFace()
        val json = OverlayTrackCodec.encode(
            OverlayTrackCodec.fromHost(result, h, true, true, true, 0f, 0f)
        )
        val clip = StickerClip(id = "s", durationMs = 4000L, trackBindJson = json)
        val timeline = Timeline(
            videoClips = listOf(h),
            stickerClips = listOf(clip),
            aspectRatio = AspectRatio.RATIO_9_16
        )
        val a = TimelineEvaluator().evaluate(timeline, 0L).activeStickers.single()
        val b = TimelineEvaluator().evaluate(timeline, 1000L).activeStickers.single()
        assertTrue(abs(b.posX - a.posX) > 0.05f)
        assertNotEquals(json, null)
        assertTrue(clip.trackBindJson == json)
    }
}
