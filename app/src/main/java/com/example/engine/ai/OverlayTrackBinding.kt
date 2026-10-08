package com.example.engine.ai

import com.example.domain.model.MaskSettings
import com.example.domain.model.Timeline
import com.example.domain.model.VideoClip
import com.example.engine.InterpolatedClipTransform
import java.util.Locale
import kotlin.math.abs

/**
 * Per-overlay binding of a [TrackingResult] so Face A → Sticker A and Face B → Text B
 * stay independent. Preview and export sample this same record.
 *
 * Final overlay pose:
 * `TrackedTransform × UserOffset(pos/scale/rotation)`
 */
data class OverlayTrackBinding(
    val hostClipId: String,
    val targetId: String,
    val encodedTrack: String,
    val followPosition: Boolean = true,
    val followScale: Boolean = true,
    val followRotation: Boolean = true,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val userScale: Float = 1f,
    val userRotation: Float = 0f,
    val hostTimelineStartMs: Long = 0L,
    val hostDurationMs: Long = 0L,
    val hostSourceStartMs: Long = 0L,
    val hostSourceEndMs: Long = 0L,
    val hostSpeed: Float = 1f,
    val hostReversed: Boolean = false,
    val hostUri: String = "",
    val canvasAspect: Float = 9f / 16f,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val naturalRotation: Int = 0
)

data class OverlayTrackedPose(
    val posX: Float,
    val posY: Float,
    val scaleX: Float,
    val scaleY: Float,
    val rotationDeg: Float,
    val confidence: Float,
    val centerX: Float,
    val centerY: Float,
    val sourceTimeUs: Long,
    val cornerPin: List<Pair<Float, Float>> = emptyList(),
    val landmarks: List<Pair<Float, Float>> = emptyList()
)

/**
 * Thread-scoped timeline used by [KeyframeInterpolator] / overlay sampling so preview,
 * GPU and export resolve the same live host clip (trim / speed / split) without changing
 * every interpolate() call site.
 */
object TrackingEvalContext {
    private val current = ThreadLocal<Timeline?>()

    fun <T> withTimeline(timeline: Timeline?, block: () -> T): T {
        val prev = current.get()
        current.set(timeline)
        return try {
            block()
        } finally {
            current.set(prev)
        }
    }

    fun bind(timeline: Timeline?) {
        current.set(timeline)
    }

    fun timeline(): Timeline? = current.get()
}

/**
 * Video-normalized tracking (0..1, top-left origin) → canvas NDC (-1..1) accounting for
 * letterbox / pillarbox of the source inside the project canvas.
 */
object TrackingCoordinateSpace {

    fun videoFit(videoAspect: Float, canvasAspect: Float): Pair<Float, Float> {
        val va = videoAspect.coerceAtLeast(0.05f)
        val ca = canvasAspect.coerceAtLeast(0.05f)
        return if (va > ca) {
            1f to (ca / va)
        } else {
            (va / ca) to 1f
        }
    }

    fun videoNormToCanvasNdc(
        centerX: Float,
        centerY: Float,
        videoWidth: Int,
        videoHeight: Int,
        naturalRotation: Int,
        canvasAspect: Float
    ): Pair<Float, Float> {
        val rot = ((naturalRotation % 360) + 360) % 360
        val vw = if (rot == 90 || rot == 270) videoHeight else videoWidth
        val vh = if (rot == 90 || rot == 270) videoWidth else videoHeight
        val videoAspect = if (vw > 0 && vh > 0) vw.toFloat() / vh.toFloat() else canvasAspect
        val (fitX, fitY) = videoFit(videoAspect, canvasAspect.coerceAtLeast(0.05f))
        val ndcX = ((centerX - 0.5f) * 2f * fitX).coerceIn(-2f, 2f)
        val ndcY = ((centerY - 0.5f) * 2f * fitY).coerceIn(-2f, 2f)
        return ndcX to ndcY
    }

    fun videoNormToOverlayPx(
        centerX: Float,
        centerY: Float,
        overlayWidth: Float,
        overlayHeight: Float,
        videoWidth: Int,
        videoHeight: Int,
        naturalRotation: Int,
        canvasAspect: Float
    ): Pair<Float, Float> {
        val (ndcX, ndcY) = videoNormToCanvasNdc(
            centerX, centerY, videoWidth, videoHeight, naturalRotation, canvasAspect
        )
        return (overlayWidth / 2f) * (1f + ndcX) to (overlayHeight / 2f) * (1f + ndcY)
    }
}

object OverlayTrackCodec {

    private const val VERSION = "ob1"
    private const val CACHE_SIZE = 16
    private val cache = object : LinkedHashMap<String, OverlayTrackBinding?>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, OverlayTrackBinding?>?) =
            size > CACHE_SIZE
    }

    fun encode(binding: OverlayTrackBinding): String {
        val sb = StringBuilder(96 + binding.encodedTrack.length)
        sb.append(VERSION).append('|')
            .append(binding.hostClipId).append('|')
            .append(binding.targetId).append('|')
            .append(if (binding.followPosition) '1' else '0').append('|')
            .append(if (binding.followScale) '1' else '0').append('|')
            .append(if (binding.followRotation) '1' else '0').append('|')
            .append(fmt(binding.offsetX)).append('|')
            .append(fmt(binding.offsetY)).append('|')
            .append(fmt(binding.userScale)).append('|')
            .append(fmt(binding.userRotation, 3)).append('|')
            .append(binding.hostTimelineStartMs).append('|')
            .append(binding.hostDurationMs).append('|')
            .append(binding.hostSourceStartMs).append('|')
            .append(binding.hostSourceEndMs).append('|')
            .append(fmt(binding.hostSpeed, 4)).append('|')
            .append(if (binding.hostReversed) '1' else '0').append('|')
            .append(binding.hostUri.replace('|', ' ')).append('|')
            .append(fmt(binding.canvasAspect, 5)).append('|')
            .append(binding.videoWidth).append('|')
            .append(binding.videoHeight).append('|')
            .append(binding.naturalRotation).append('|')
            .append(binding.encodedTrack)
        return sb.toString()
    }

    fun decode(encoded: String?): OverlayTrackBinding? {
        if (encoded.isNullOrBlank()) return null
        synchronized(cache) {
            if (cache.containsKey(encoded)) return cache[encoded]
        }
        val parsed = parse(encoded)
        synchronized(cache) { cache[encoded] = parsed }
        return parsed
    }

    fun invalidate(encoded: String?) {
        if (encoded.isNullOrBlank()) return
        synchronized(cache) { cache.remove(encoded) }
    }

    fun fromHost(
        result: TrackingResult,
        host: VideoClip,
        followPosition: Boolean,
        followScale: Boolean,
        followRotation: Boolean,
        offsetX: Float,
        offsetY: Float,
        userScale: Float = 1f,
        userRotation: Float = 0f,
        canvasAspect: Float = 9f / 16f
    ): OverlayTrackBinding = OverlayTrackBinding(
        hostClipId = host.id,
        targetId = result.targetId,
        encodedTrack = MotionTrackCodec.encode(result),
        followPosition = followPosition,
        followScale = followScale,
        followRotation = followRotation,
        offsetX = offsetX,
        offsetY = offsetY,
        userScale = userScale,
        userRotation = userRotation,
        hostTimelineStartMs = host.timelineStartMs,
        hostDurationMs = host.durationMs,
        hostSourceStartMs = host.sourceStartMs,
        hostSourceEndMs = host.sourceEndMs,
        hostSpeed = host.speed,
        hostReversed = host.isReversed,
        hostUri = host.uri,
        canvasAspect = canvasAspect,
        videoWidth = host.width,
        videoHeight = host.height,
        naturalRotation = host.naturalRotation
    )

    fun findHost(timeline: Timeline?, binding: OverlayTrackBinding, timelinePosMs: Long): VideoClip? {
        if (timeline == null) return null
        val all = timeline.videoClips + timeline.overlayClips
        fun inRange(c: VideoClip) =
            timelinePosMs >= c.timelineStartMs && timelinePosMs < c.timelineStartMs + c.durationMs
        return all.find { it.id == binding.hostClipId && inRange(it) }
            ?: all.find { binding.hostUri.isNotBlank() && it.uri == binding.hostUri && inRange(it) }
            ?: all.find { it.id == binding.hostClipId }
            ?: all.find { binding.hostUri.isNotBlank() && it.uri == binding.hostUri }
    }

    fun sourceTimeUs(
        binding: OverlayTrackBinding,
        timelinePosMs: Long,
        host: VideoClip?
    ): Long {
        if (host != null) return host.timelineToSourceMs(timelinePosMs) * 1000L
        val offset = (timelinePosMs - binding.hostTimelineStartMs).coerceIn(0L, binding.hostDurationMs.coerceAtLeast(0L))
        val scaled = (offset * binding.hostSpeed.coerceAtLeast(0.01f)).toLong()
        val sourceMs = if (binding.hostReversed) {
            (binding.hostSourceEndMs - scaled).coerceIn(binding.hostSourceStartMs, binding.hostSourceEndMs)
        } else {
            (binding.hostSourceStartMs + scaled).coerceIn(binding.hostSourceStartMs, binding.hostSourceEndMs)
        }
        return sourceMs * 1000L
    }

    fun sample(
        binding: OverlayTrackBinding,
        timelinePosMs: Long,
        timeline: Timeline? = TrackingEvalContext.timeline(),
        userPosX: Float = 0f,
        userPosY: Float = 0f,
        userScale: Float = 1f,
        userRotation: Float = 0f
    ): OverlayTrackedPose {
        val host = findHost(timeline, binding, timelinePosMs)
        val sourceUs = sourceTimeUs(binding, timelinePosMs, host)
        val result = MotionTrackCodec.decode(binding.encodedTrack)
            ?: return OverlayTrackedPose(userPosX, userPosY, userScale, userScale, userRotation, 0f, 0.5f, 0.5f, sourceUs)
        if (result.keyframes.isEmpty()) {
            return OverlayTrackedPose(userPosX, userPosY, userScale, userScale, userRotation, 0f, 0.5f, 0.5f, sourceUs)
        }
        val kf = MotionTrackingEvaluator(result).evaluate(sourceUs)
        val vw = host?.width ?: binding.videoWidth
        val vh = host?.height ?: binding.videoHeight
        val nrot = host?.naturalRotation ?: binding.naturalRotation
        val aspect = binding.canvasAspect
        val (ndcX, ndcY) = TrackingCoordinateSpace.videoNormToCanvasNdc(
            kf.centerX, kf.centerY, vw, vh, nrot, aspect
        )
        val posX = (if (binding.followPosition) ndcX else 0f) + binding.offsetX + userPosX
        val posY = (if (binding.followPosition) ndcY else 0f) + binding.offsetY + userPosY
        val scaleX = (if (binding.followScale) kf.scaleX else 1f) * binding.userScale * userScale
        val scaleY = (if (binding.followScale) kf.scaleY else 1f) * binding.userScale * userScale
        val rot = (if (binding.followRotation) kf.rotationDeg else 0f) + binding.userRotation + userRotation
        return OverlayTrackedPose(
            posX = posX.coerceIn(-2f, 2f),
            posY = posY.coerceIn(-2f, 2f),
            scaleX = scaleX.coerceIn(0.05f, 8f),
            scaleY = scaleY.coerceIn(0.05f, 8f),
            rotationDeg = rot,
            confidence = kf.confidence,
            centerX = kf.centerX,
            centerY = kf.centerY,
            sourceTimeUs = sourceUs,
            cornerPin = kf.cornerPin,
            landmarks = kf.landmarkPoints
        )
    }

    fun sampleEncoded(
        encoded: String?,
        layerTimelineStartMs: Long,
        relTimeMs: Long,
        userPosX: Float,
        userPosY: Float,
        userScale: Float,
        userRotation: Float
    ): OverlayTrackedPose? {
        val binding = decode(encoded) ?: return null
        return sample(
            binding = binding,
            timelinePosMs = layerTimelineStartMs + relTimeMs,
            userPosX = userPosX,
            userPosY = userPosY,
            userScale = userScale,
            userRotation = userRotation
        )
    }

    fun toInterpolated(
        encoded: String?,
        layerTimelineStartMs: Long,
        relTimeMs: Long,
        userPosX: Float,
        userPosY: Float,
        userScale: Float,
        userRotation: Float,
        opacity: Float,
        volume: Float = 1f
    ): InterpolatedClipTransform? {
        val pose = sampleEncoded(
            encoded, layerTimelineStartMs, relTimeMs, userPosX, userPosY, userScale, userRotation
        ) ?: return null
        return InterpolatedClipTransform(
            scaleX = pose.scaleX,
            scaleY = pose.scaleY,
            rotation = pose.rotationDeg,
            posX = pose.posX,
            posY = pose.posY,
            opacity = opacity,
            volume = volume
        )
    }

    fun applyToMask(mask: MaskSettings, clipMotionTrackJson: String?, sourceTimeUs: Long): MaskSettings {
        val encodedTrack = decode(mask.trackBindJson)?.encodedTrack ?: clipMotionTrackJson
        return MotionTrackCodec.applyToMask(mask, encodedTrack, sourceTimeUs)
    }

    private fun parse(encoded: String): OverlayTrackBinding? = try {
        val parts = encoded.split('|', limit = 22)
        if (parts.size < 22 || parts[0] != VERSION) null
        else OverlayTrackBinding(
            hostClipId = parts[1],
            targetId = parts[2],
            followPosition = parts[3] == "1",
            followScale = parts[4] == "1",
            followRotation = parts[5] == "1",
            offsetX = parts[6].toFloat(),
            offsetY = parts[7].toFloat(),
            userScale = parts[8].toFloat(),
            userRotation = parts[9].toFloat(),
            hostTimelineStartMs = parts[10].toLong(),
            hostDurationMs = parts[11].toLong(),
            hostSourceStartMs = parts[12].toLong(),
            hostSourceEndMs = parts[13].toLong(),
            hostSpeed = parts[14].toFloat(),
            hostReversed = parts[15] == "1",
            hostUri = parts[16],
            canvasAspect = parts[17].toFloat(),
            videoWidth = parts[18].toInt(),
            videoHeight = parts[19].toInt(),
            naturalRotation = parts[20].toInt(),
            encodedTrack = parts[21]
        )
    } catch (_: Exception) {
        null
    }

    private fun fmt(v: Float, digits: Int = 5): String {
        val s = String.format(Locale.US, "%.${digits}f", v)
        return if (abs(v) < 1e-6f) "0" else s
    }
}
