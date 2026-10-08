package com.example.engine.ai

import com.example.domain.model.MaskSettings
import java.util.Locale
import kotlin.math.abs

/**
 * Compact serialisation of a [TrackingResult] onto [com.example.domain.model.VideoClip.motionTrackJson]
 * so tracking survives save / undo / panel close and is sampled identically in preview and export.
 *
 * Format:
 * `v1|clipId|CATEGORY|startUs|endUs|targetId|ts,cx,cy,sx,sy,rot,conf[~lx:ly,...][@x:y,...];...`
 */
object MotionTrackCodec {

    private const val VERSION = "v1"
    private const val CACHE_SIZE = 8
    private val cache = object : LinkedHashMap<String, TrackingResult?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrackingResult?>?) = size > CACHE_SIZE
    }

    fun encode(result: TrackingResult): String {
        val sb = StringBuilder(64 + result.keyframes.size * 48)
        sb.append(VERSION).append('|')
            .append(result.clipId).append('|')
            .append(result.targetCategory.name).append('|')
            .append(result.startTimestampUs).append('|')
            .append(result.endTimestampUs).append('|')
            .append(result.targetId).append('|')
        result.keyframes.forEachIndexed { i, kf ->
            if (i > 0) sb.append(';')
            sb.append(kf.timestampUs).append(',')
                .append(fmt(kf.centerX)).append(',')
                .append(fmt(kf.centerY)).append(',')
                .append(fmt(kf.scaleX)).append(',')
                .append(fmt(kf.scaleY)).append(',')
                .append(fmt(kf.rotationDeg, 3)).append(',')
                .append(fmt(kf.confidence, 3))
            if (kf.landmarkPoints.isNotEmpty()) {
                sb.append('~')
                kf.landmarkPoints.forEachIndexed { li, p ->
                    if (li > 0) sb.append(',')
                    sb.append(fmt(p.first)).append(':').append(fmt(p.second))
                }
            }
            if (kf.cornerPin.size == 4) {
                sb.append('@')
                kf.cornerPin.forEachIndexed { ci, p ->
                    if (ci > 0) sb.append(',')
                    sb.append(fmt(p.first)).append(':').append(fmt(p.second))
                }
            }
        }
        return sb.toString()
    }

    fun decode(encoded: String?): TrackingResult? {
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

    /**
     * When a mask is attached to a track, sample the track at [sourceTimeUs] and
     * drive mask centre / size / rotation. Preview and export share this path.
     */
    fun applyToMask(mask: MaskSettings, encoded: String?, sourceTimeUs: Long): MaskSettings {
        if (!mask.followTracking || encoded.isNullOrBlank()) return mask
        val result = decode(encoded) ?: return mask
        if (result.keyframes.isEmpty()) return mask
        val kf = MotionTrackingEvaluator(result).evaluate(sourceTimeUs)
        val posX = ((kf.centerX - 0.5f) * 2f).coerceIn(-2f, 2f)
        val posY = ((kf.centerY - 0.5f) * 2f).coerceIn(-2f, 2f)
        val width = (mask.width * kf.scaleX).coerceIn(0.05f, 2f)
        val height = (mask.height * kf.scaleY).coerceIn(0.05f, 2f)
        return mask.copy(
            posX = posX,
            posY = posY,
            width = width,
            height = height,
            rotation = kf.rotationDeg
        )
    }

    private fun parse(encoded: String): TrackingResult? = try {
        val parts = encoded.split('|')
        if (parts.size < 7 || parts[0] != VERSION) null
        else {
            val category = runCatching { TrackingCategory.valueOf(parts[2]) }.getOrDefault(TrackingCategory.OBJECT)
            val frames = parts[6].split(';').filter { it.isNotBlank() }.mapNotNull { parseKeyframe(it) }
            if (frames.isEmpty()) null
            else TrackingResult(
                targetId = parts[5],
                clipId = parts[1],
                startTimestampUs = parts[3].toLong(),
                endTimestampUs = parts[4].toLong(),
                keyframes = frames,
                targetCategory = category
            )
        }
    } catch (_: Exception) {
        null
    }

    private fun parseKeyframe(raw: String): MotionKeyframe? {
        val pinSplit = raw.split('@', limit = 2)
        val lmSplit = pinSplit[0].split('~', limit = 2)
        val nums = lmSplit[0].split(',')
        if (nums.size < 7) return null
        val landmarks = if (lmSplit.size > 1) parsePairs(lmSplit[1]) else emptyList()
        val pin = if (pinSplit.size > 1) parsePairs(pinSplit[1]) else emptyList()
        return MotionKeyframe(
            timestampUs = nums[0].toLong(),
            centerX = nums[1].toFloat(),
            centerY = nums[2].toFloat(),
            scaleX = nums[3].toFloat(),
            scaleY = nums[4].toFloat(),
            rotationDeg = nums[5].toFloat(),
            confidence = nums[6].toFloat(),
            landmarkPoints = landmarks,
            cornerPin = pin
        )
    }

    private fun parsePairs(raw: String): List<Pair<Float, Float>> =
        raw.split(',').mapNotNull { token ->
            val xy = token.split(':')
            if (xy.size != 2) null else xy[0].toFloatOrNull()?.let { x ->
                xy[1].toFloatOrNull()?.let { y -> x to y }
            }
        }

    private fun fmt(v: Float, digits: Int = 5): String {
        val spec = "%.${digits}f"
        val s = String.format(Locale.US, spec, v)
        return if (abs(v) < 1e-6f) "0" else s
    }
}
