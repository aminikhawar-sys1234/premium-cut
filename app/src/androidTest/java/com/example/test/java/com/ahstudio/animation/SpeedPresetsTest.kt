package com.ahstudio.animation

import com.ahstudio.animation.speed.SpeedPresets
import org.junit.Assert.*
import org.junit.Test

class SpeedPresetsTest {
    private val names = listOf("EASE_IN", "EASE_OUT", "HERO_MONTAGE", "BULLET_TIME", "JUMPER", "CUSTOM_BEZIER")
    @Test fun standardIsNull() { assertNull(SpeedPresets.rampFor("STANDARD")); assertNull(SpeedPresets.rampFor("nope")) }
    @Test fun everyPresetIsContinuousMonotoneAndNormalised() {
        for (n in names) {
            val r = SpeedPresets.rampFor(n)!!
            assertEquals(n, 0.0, r.sourceFraction(0.0), 1e-9); assertEquals(n, 1.0, r.sourceFraction(1.0), 1e-9)
            var prev = 0.0
            for (i in 1..2000) { val v = r.sourceFraction(i / 2000.0); assertTrue("$n monotone @$i", v >= prev); assertTrue("$n continuous @$i", v - prev < 0.01); prev = v }
        }
    }
    @Test fun montageIsSlowInTheMiddle() {
        val r = SpeedPresets.rampFor("HERO_MONTAGE")!!
        assertTrue(r.sourceFraction(0.55) - r.sourceFraction(0.45) < 0.1 * 0.8)
        assertTrue(r.sourceFraction(0.1) - r.sourceFraction(0.0) > 0.1)
    }
    @Test fun outputInvertsSource() {
        val r = SpeedPresets.rampFor("BULLET_TIME")!!
        for (t in listOf(0.1, 0.3, 0.5, 0.9)) assertEquals(t, r.outputFraction(r.sourceFraction(t)), 1e-6)
    }
    @Test fun customBezierUsesPoints() {
        val a = SpeedPresets.rampFor("CUSTOM_BEZIER", listOf(0.1f, 0.9f, 0.9f, 0.1f))!!
        val b = SpeedPresets.rampFor("CUSTOM_BEZIER", listOf(0.9f, 0.1f, 0.1f, 0.9f))!!
        assertNotEquals(a.sourceFraction(0.3), b.sourceFraction(0.3), 1e-3)
    }
}
