package com.example.engine.ai

import android.graphics.Matrix
import kotlin.math.abs

class MotionTrackingEvaluator(private val trackingResult: TrackingResult) {

    /**
     * Evaluates the track at any playback / export timestamp.
     * Position and scale use Catmull-Rom splines (smooth velocity, no corner jitter between
     * tracked frames), rotation takes the shortest arc, corner pins are interpolated per corner.
     * With only two keys the spline degenerates to exact linear interpolation.
     */
    fun evaluate(timestampUs: Long): MotionKeyframe {
        val keys = trackingResult.keyframes
        if (keys.isEmpty()) {
            return MotionKeyframe(timestampUs, 0.5f, 0.5f, 1f, 1f, 0f)
        }

        if (timestampUs <= keys.first().timestampUs) return keys.first()
        if (timestampUs >= keys.last().timestampUs) return keys.last()

        var low = 0
        var high = keys.size - 1

        while (low <= high) {
            val mid = (low + high) ushr 1
            when {
                keys[mid].timestampUs < timestampUs -> low = mid + 1
                keys[mid].timestampUs > timestampUs -> high = mid - 1
                else -> return keys[mid]
            }
        }

        val i1 = maxOf(0, low - 1)
        val i2 = minOf(keys.size - 1, low)
        val k1 = keys[i1]
        val k2 = keys[i2]

        val span = k2.timestampUs - k1.timestampUs
        if (span <= 0L) return k1

        val t = (timestampUs - k1.timestampUs).toFloat() / span.toFloat()
        // Temporary lock-loss / occlusion: do not spline across a low-confidence gap
        // (that would slide the overlay through empty space until re-acquisition).
        if (k1.confidence < 0.2f && k2.confidence < 0.2f) {
            return k1.copy(timestampUs = timestampUs)
        }
        if (k1.confidence < 0.2f && k2.confidence >= 0.2f && t < 0.92f) {
            return k1.copy(timestampUs = timestampUs)
        }
        // Phantom neighbours reflect the end segments so a 2-key track stays linear.
        val k0 = if (i1 > 0) keys[i1 - 1] else null
        val k3 = if (i2 < keys.size - 1) keys[i2 + 1] else null

        fun spline(sel: (MotionKeyframe) -> Float): Float {
            val p1 = sel(k1)
            val p2 = sel(k2)
            val p0 = if (k0 != null) sel(k0) else 2f * p1 - p2
            val p3 = if (k3 != null) sel(k3) else 2f * p2 - p1
            return catmullRom(p0, p1, p2, p3, t)
        }

        val rot = k1.rotationDeg + shortestArcDeg(k1.rotationDeg, k2.rotationDeg) * t

        val pin = if (k1.cornerPin.size == k2.cornerPin.size && k1.cornerPin.isNotEmpty()) {
            k1.cornerPin.indices.map { c ->
                Pair(
                    k1.cornerPin[c].first + (k2.cornerPin[c].first - k1.cornerPin[c].first) * t,
                    k1.cornerPin[c].second + (k2.cornerPin[c].second - k1.cornerPin[c].second) * t
                )
            }
        } else emptyList()

        val landmarks = interpolateLandmarks(k1.landmarkPoints, k2.landmarkPoints, t)

        return MotionKeyframe(
            timestampUs = timestampUs,
            centerX = spline { it.centerX },
            centerY = spline { it.centerY },
            scaleX = spline { it.scaleX },
            scaleY = spline { it.scaleY },
            rotationDeg = rot,
            confidence = k1.confidence + (k2.confidence - k1.confidence) * t,
            landmarkPoints = landmarks,
            cornerPin = pin
        )
    }

    /**
     * Converts normalized keyframe coordinates to standard canvas transformation matrix.
     */
    fun computeTransformMatrix(
        timestampUs: Long,
        viewportWidth: Float,
        viewportHeight: Float,
        contentWidth: Float,
        contentHeight: Float
    ): Matrix {
        val frame = evaluate(timestampUs)
        val matrix = Matrix()

        val targetScreenX = frame.centerX * viewportWidth
        val targetScreenY = frame.centerY * viewportHeight

        matrix.postTranslate(-contentWidth / 2f, -contentHeight / 2f)
        matrix.postScale(frame.scaleX, frame.scaleY)
        matrix.postRotate(frame.rotationDeg)
        matrix.postTranslate(targetScreenX, targetScreenY)

        return matrix
    }

    /**
     * Corner-pin matrix mapping a content rectangle onto the tracked plane (perspective).
     * Returns null when the track carries no corner pin.
     */
    fun computeCornerPinMatrix(
        timestampUs: Long,
        viewportWidth: Float,
        viewportHeight: Float,
        contentWidth: Float,
        contentHeight: Float
    ): Matrix? {
        val pin = evaluate(timestampUs).cornerPin
        if (pin.size != 4) return null
        val src = floatArrayOf(0f, 0f, contentWidth, 0f, contentWidth, contentHeight, 0f, contentHeight)
        val dst = FloatArray(8)
        for (i in 0 until 4) {
            dst[2 * i] = pin[i].first * viewportWidth
            dst[2 * i + 1] = pin[i].second * viewportHeight
        }
        val m = Matrix()
        return if (m.setPolyToPoly(src, 0, dst, 0, 4)) m else null
    }

    companion object {
        fun interpolateLandmarks(
            a: List<Pair<Float, Float>>,
            b: List<Pair<Float, Float>>,
            t: Float
        ): List<Pair<Float, Float>> {
            if (a.isEmpty() && b.isEmpty()) return emptyList()
            if (a.isEmpty()) return b
            if (b.isEmpty()) return a
            val n = minOf(a.size, b.size)
            return (0 until n).map { i ->
                val (ax, ay) = a[i]
                val (bx, by) = b[i]
                val aOk = ax >= 0f && ay >= 0f
                val bOk = bx >= 0f && by >= 0f
                when {
                    aOk && bOk -> Pair(ax + (bx - ax) * t, ay + (by - ay) * t)
                    aOk -> a[i]
                    bOk -> b[i]
                    else -> Pair(-1f, -1f)
                }
            }
        }

        fun catmullRom(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
            val t2 = t * t
            val t3 = t2 * t
            return 0.5f * ((2f * p1) + (-p0 + p2) * t +
                (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 +
                (-p0 + 3f * p1 - 3f * p2 + p3) * t3)
        }

        /** Signed shortest rotation from [from] to [to] in degrees, in (-180, 180]. */
        fun shortestArcDeg(from: Float, to: Float): Float {
            var d = (to - from) % 360f
            if (d > 180f) d -= 360f
            if (d <= -180f) d += 360f
            return if (abs(d) < 1e-6f) 0f else d
        }
    }
}
