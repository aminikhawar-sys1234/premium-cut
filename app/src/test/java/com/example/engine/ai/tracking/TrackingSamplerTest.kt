package com.example.engine.ai.tracking

import com.example.engine.ai.NormalizedRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingSamplerTest {

    @Test
    fun initialScanIsSparseAndStartsAtPlayhead() {
        val times = TrackingSampler.initialScanTimes(
            startUs = 1_000_000L,
            endUs = 5_000_000L,
            maxLookaheadUs = 800_000L,
            stepUs = 250_000L
        )
        assertEquals(listOf(1_000_000L, 1_250_000L, 1_500_000L, 1_750_000L, 1_800_000L), times)
        assertTrue(times.size <= 5)
        assertEquals(1_000_000L, times.first())
    }

    @Test
    fun initialScanDoesNotWalkPastClipEnd() {
        val times = TrackingSampler.initialScanTimes(0L, 400_000L, 800_000L, 250_000L)
        assertEquals(listOf(0L, 250_000L, 400_000L), times)
        assertTrue(times.last() <= 400_000L)
    }

    @Test
    fun shouldRedetectOnlyOnIntervalConfidenceJumpOrLoss() {
        assertFalse(TrackingSampler.shouldRedetect(1, 0, 0.9f, 0f, 16))
        assertTrue(TrackingSampler.shouldRedetect(16, 0, 0.9f, 0f, 16))
        assertTrue(TrackingSampler.shouldRedetect(3, 1, 0.9f, 0f, 16))
        assertFalse(TrackingSampler.shouldRedetect(3, 2, 0.9f, 0f, 16, 3))
        assertTrue(TrackingSampler.shouldRedetect(3, 3, 0.9f, 0f, 16, 3))
        assertTrue(TrackingSampler.shouldRedetect(3, 0, 0.20f, 0f, 16))
        assertTrue(TrackingSampler.shouldRedetect(3, 0, 0.9f, 0.25f, 16))
    }

    @Test
    fun lostLockDoesNotAbortRemainingDuration() {
        assertTrue(TrackingSampler.isRecovering(TrackingSampler.LOST_FRAMES))
        assertFalse(TrackingSampler.isUnrecoverableYet(TrackingSampler.LOST_FRAMES))
        assertTrue(TrackingSampler.isUnrecoverableYet(TrackingSampler.RECOVERY_WINDOW_FRAMES))
        assertTrue(TrackingSampler.RECOVERY_WINDOW_FRAMES > TrackingSampler.LOST_FRAMES)
    }

    @Test
    fun predictBoxUsesConstantVelocity() {
        val a = NormalizedRect(0.20f, 0.20f, 0.40f, 0.40f)
        val b = NormalizedRect(0.30f, 0.20f, 0.50f, 0.40f)
        val p = TrackingSampler.predictBox(b, a)
        assertEquals(0.50f, p.centerX, 0.001f)
        assertEquals(0.30f, p.centerY, 0.001f)
        assertEquals(0.20f, p.width, 0.001f)
    }

    @Test
    fun progressGateThrottlesSameState() {
        var t = 0L
        val gate = TrackingSampler.ProgressGate(minIntervalMs = 80L, nowMs = { t })
        assertTrue(gate.shouldEmit("TRACKING"))
        t = 40L
        assertFalse(gate.shouldEmit("TRACKING"))
        t = 90L
        assertTrue(gate.shouldEmit("TRACKING"))
        assertTrue(gate.shouldEmit("LOST"))
        t = 91L
        assertTrue(gate.shouldEmit("TRACKING", force = true))
    }

    @Test
    fun detectionBudgetBeatsNaive24FpsScan() {
        val naive24Fps = 1_000_000L / 41_666L
        val sparse = TrackingSampler.initialScanTimes(0L, 10_000_000L).size
        assertTrue("sparse lock=$sparse should be far below a 1s 24fps scan ($naive24Fps)", sparse <= 5)
        assertTrue(sparse < naive24Fps)
    }
}
