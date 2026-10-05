package com.ahstudio.animation

import com.ahstudio.animation.speed.SpeedRamp
import com.ahstudio.animation.speed.TimeRemapTools
import org.junit.Assert.*
import org.junit.Test

class SpeedRampTest {
    private val presets = mapOf(
        "easeIn" to SpeedRamp.easeIn(), "easeOut" to SpeedRamp.easeOut(), "easeInOut" to SpeedRamp.easeInOut(),
        "montage" to SpeedRamp.montage(), "bullet" to SpeedRamp.bulletTime(), "jumper" to SpeedRamp.jumper(),
        "flashIn" to SpeedRamp.flashIn(), "flashOut" to SpeedRamp.flashOut(), "bump" to SpeedRamp.speedBump(),
        "bezier" to SpeedRamp.fromBezier(0.42, 0.0, 0.58, 1.0))

    @Test fun everyPresetStartsAtZeroEndsAtOneAndIsMonotoneAndContinuous() {
        for ((name, r) in presets) {
            assertEquals("$name start", 0.0, r.sourceFraction(0.0), 1e-9)
            assertEquals("$name end", 1.0, r.sourceFraction(1.0), 1e-9)
            var prev = 0.0
            for (i in 1..2000) {
                val f = r.sourceFraction(i / 2000.0)
                assertTrue("$name monotone at $i", f >= prev)
                assertTrue("$name no jump at $i (${f - prev})", f - prev < 0.02)   // continuous: old step-function code jumped backwards
                prev = f
            }
        }
    }

    @Test fun montageIsFastSlowFast() {
        val r = SpeedRamp.montage()
        assertTrue(r.speedAt(0.1) > 1.2); assertTrue(r.speedAt(0.5) < 0.6); assertTrue(r.speedAt(0.9) > 1.2)
    }

    @Test fun bulletTimeIsSlowInTheMiddle() {
        val r = SpeedRamp.bulletTime()
        assertTrue(r.speedAt(0.5) < 0.4); assertTrue(r.speedAt(0.05) > 1.0)
    }

    @Test fun constantRampIsIdentityWhenNormalised() {
        val r = SpeedRamp.of(listOf(SpeedRamp.Point(0.0, 3.0), SpeedRamp.Point(1.0, 3.0)))
        for (i in 0..10) assertEquals(i / 10.0, r.sourceFraction(i / 10.0), 1e-9)
    }

    @Test fun unnormalisedRampReportsAverageSpeed() {
        val r = SpeedRamp.of(listOf(SpeedRamp.Point(0.0, 2.0), SpeedRamp.Point(1.0, 2.0)), preserveDuration = false)
        assertEquals(2.0, r.sourceFraction(1.0), 1e-9)
        assertEquals(2.0, r.averageSpeed, 1e-9)
    }

    @Test fun inverseRoundTrips() {
        val r = SpeedRamp.montage()
        for (i in 0..20) { val t = i / 20.0; assertEquals(t, r.outputFraction(r.sourceFraction(t)), 1e-6) }
    }

    @Test fun sourceMsScalesWithSpan() {
        val r = SpeedRamp.easeIn()
        assertEquals(10_000.0, r.sourceMs(4000, 4000, 10_000.0), 1e-6)
        assertTrue(r.sourceMs(2000, 4000, 10_000.0) < 5_000.0)          // ease-in is behind the linear position at the midpoint
    }

    @Test fun neverStallsOrReverses() {
        val r = SpeedRamp.of(listOf(SpeedRamp.Point(0.0, 0.0), SpeedRamp.Point(0.5, -5.0), SpeedRamp.Point(1.0, 0.0)))
        assertTrue(r.points.all { it.speed > 0.0 })
        assertTrue(r.sourceFraction(0.6) > r.sourceFraction(0.5))
    }

    @Test fun handlesUnsortedAndPartialDomainPoints() {
        val r = SpeedRamp.of(listOf(SpeedRamp.Point(0.8, 3.0), SpeedRamp.Point(0.2, 1.0)))
        assertEquals(0.0, r.points.first().t, 0.0); assertEquals(1.0, r.points.last().t, 0.0)
        assertEquals(1.0, r.sourceFraction(1.0), 1e-9)
    }

    @Test fun freezeFrameHoldsSourcePosition() {
        assertEquals(1000L, TimeRemapTools.freezeFrame(1000, 1000, 500, 0))
        assertEquals(1000L, TimeRemapTools.freezeFrame(1400, 1000, 500, 0))
        assertEquals(1100L, TimeRemapTools.freezeFrame(1600, 1000, 500, 0))
    }

    @Test fun reverseRunsBackwards() {
        assertEquals(5000L, TimeRemapTools.reverse(0, 1000, 0, 5000))
        assertEquals(0L, TimeRemapTools.reverse(1000, 1000, 0, 5000))
        assertEquals(2500L, TimeRemapTools.reverse(500, 1000, 0, 5000))
    }
}
