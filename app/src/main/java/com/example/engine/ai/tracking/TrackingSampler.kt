package com.example.engine.ai.tracking

import com.example.engine.ai.NormalizedRect
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure scheduling / quality helpers for Object, Face and Body tracking.
 * Kept Android-free so the detect-then-track policy is unit-tested on the JVM.
 */
object TrackingSampler {

    const val DETECT_LOOKAHEAD_US = 800_000L
    const val DETECT_STEP_US = 250_000L
    const val REDETECT_INTERVAL = 16
    const val CONFIDENCE_REDETECT = 0.35f
    const val CONFIDENCE_LOST = 0.18f
    const val JUMP_REDETECT = 0.18f
    const val JUMP_REJECT = 0.28f
    const val LOST_FRAMES = 6
    const val PREDICT_FRAMES = 2
    const val DETECT_WIDTH = 256
    const val TRACK_WIDTH = 320
    const val FEATURE_WIDTH = 480
    const val PROGRESS_MIN_MS = 80L

    /**
     * Sparse initial-detection timestamps: current frame first, then 250 ms steps
     * up to [maxLookaheadUs] (capped by [endUs]). Never denser than the step, so
     * Face/Body lock does not run a full-rate detector for a whole second.
     */
    fun initialScanTimes(
        startUs: Long,
        endUs: Long,
        maxLookaheadUs: Long = DETECT_LOOKAHEAD_US,
        stepUs: Long = DETECT_STEP_US
    ): List<Long> {
        val safeStart = startUs.coerceAtLeast(0L)
        val limit = min(endUs.coerceAtLeast(safeStart), safeStart + maxLookaheadUs.coerceAtLeast(0L))
        val step = stepUs.coerceAtLeast(1L)
        val times = ArrayList<Long>(6)
        var t = safeStart
        while (t <= limit) {
            times.add(t)
            val next = t + step
            if (next <= t) break
            t = next
        }
        if (times.isEmpty()) times.add(safeStart)
        if (times.last() < limit) times.add(limit)
        return times
    }

    /**
     * Adaptive re-detect: periodic refresh, confidence drop, spatial jump, or lost lock.
     * Intermediate frames must be tracked (LK / SAD), not re-inferred.
     */
    fun shouldRedetect(
        frameIndex: Int,
        consecutiveLosses: Int,
        lastConfidence: Float,
        jumpDistance: Float = 0f,
        baseInterval: Int = REDETECT_INTERVAL
    ): Boolean {
        if (consecutiveLosses > 0) return true
        if (lastConfidence < CONFIDENCE_REDETECT) return true
        if (jumpDistance > JUMP_REDETECT) return true
        val interval = baseInterval.coerceAtLeast(2)
        return frameIndex > 0 && frameIndex % interval == 0
    }

    fun jumpDistance(a: NormalizedRect, b: NormalizedRect): Float {
        val dx = a.centerX - b.centerX
        val dy = a.centerY - b.centerY
        return sqrt(dx * dx + dy * dy)
    }

    /** Constant-velocity prediction of the next box from the last two observations. */
    fun predictBox(previous: NormalizedRect, beforePrevious: NormalizedRect): NormalizedRect {
        val dx = previous.centerX - beforePrevious.centerX
        val dy = previous.centerY - beforePrevious.centerY
        val dw = previous.width - beforePrevious.width
        val dh = previous.height - beforePrevious.height
        val cx = (previous.centerX + dx).coerceIn(0f, 1f)
        val cy = (previous.centerY + dy).coerceIn(0f, 1f)
        val w = (previous.width + dw).coerceIn(0.02f, 0.95f)
        val h = (previous.height + dh).coerceIn(0.02f, 0.95f)
        return NormalizedRect(
            left = (cx - w / 2f).coerceIn(0f, 1f),
            top = (cy - h / 2f).coerceIn(0f, 1f),
            right = (cx + w / 2f).coerceIn(0f, 1f),
            bottom = (cy + h / 2f).coerceIn(0f, 1f)
        ).clamped()
    }

    fun boxFromCenter(
        cx: Float,
        cy: Float,
        width: Float,
        height: Float
    ): NormalizedRect {
        val w = width.coerceIn(0.02f, 0.95f)
        val h = height.coerceIn(0.02f, 0.95f)
        return NormalizedRect(
            left = (cx - w / 2f).coerceIn(0f, 1f),
            top = (cy - h / 2f).coerceIn(0f, 1f),
            right = (cx + w / 2f).coerceIn(0f, 1f),
            bottom = (cy + h / 2f).coerceIn(0f, 1f)
        ).clamped()
    }

    /**
     * Throttles progress callbacks so the UI thread is not flooded once per decoded frame.
     * State changes and forced completions always pass through.
     */
    class ProgressGate(
        private val minIntervalMs: Long = PROGRESS_MIN_MS,
        private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L }
    ) {
        private var lastEmitMs: Long = 0L
        private var lastState: Any? = null

        fun shouldEmit(state: Any?, force: Boolean = false): Boolean {
            if (force) {
                lastEmitMs = nowMs()
                lastState = state
                return true
            }
            val now = nowMs()
            if (state != lastState || now - lastEmitMs >= minIntervalMs) {
                lastEmitMs = now
                lastState = state
                return true
            }
            return false
        }
    }
}
