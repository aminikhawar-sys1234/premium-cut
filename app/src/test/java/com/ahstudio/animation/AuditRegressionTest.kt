package com.ahstudio.animation

import com.ahstudio.animation.core.AnimationEngine
import com.ahstudio.animation.curves.CubicBezierTiming
import com.ahstudio.animation.easing.EasingType
import com.ahstudio.animation.keyframes.*
import com.ahstudio.animation.math.Vec2
import com.ahstudio.animation.procedural.Oscillator
import com.ahstudio.animation.procedural.Waveform
import com.ahstudio.animation.procedural.Wiggle
import com.ahstudio.animation.properties.BindingKey
import com.ahstudio.animation.properties.PropertyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Regression tests for defects found during the animation audit. Each one failed on the original code. */
class AuditRegressionTest {
    private val key = BindingKey("L", "P")

    private fun engineWith(vararg kfs: Keyframe, type: PropertyType = PropertyType.FLOAT): AnimationEngine {
        val e = AnimationEngine()
        e.ensureTrack(key, type)
        kfs.forEach { e.addKeyframe(key, it) }
        return e
    }

    @Test fun customEasingHonoursKeyframeBezier() {
        // CUSTOM easing + explicit bezier must follow the bezier, not silently fall back to linear.
        val bez = CubicBezierTiming(0.0, 0.0, 0.0, 1.0)            // very fast start
        val e = engineWith(
            Keyframe(KeyframeId(1), 0, value = 0.0, easing = EasingType.CUSTOM, bezier = bez),
            Keyframe(KeyframeId(2), 1000, value = 100.0))
        val v = (e.evaluateKey(key, 250) as EvaluatedValue.FloatV).value
        assertTrue("expected well above linear 25, got $v", v > 50.0)
    }

    @Test fun triangleOscillatorSpansFullAmplitude() {
        val o = Oscillator(1, 1.0, 10.0, Waveform.TRIANGLE)
        val samples = (0..1000).map { o.value(it.toLong()) }
        assertEquals(10.0, samples.max(), 0.05)
        assertEquals(-10.0, samples.min(), 0.05)
        assertEquals(0.0, o.value(0), 1e-6)                          // sine-like phase: starts at zero crossing
    }

    @Test fun sawtoothHandlesNegativePhase() {
        val o = Oscillator(1, 1.0, 1.0, Waveform.SAWTOOTH, phaseDeg = -90.0)
        for (t in 0..2000 step 50) assertTrue(abs(o.value(t.toLong())) <= 1.0 + 1e-9)
    }

    @Test fun bezierTangentsCanOvershootBetweenEqualValues() {
        // Both keys at the same value but with non-zero tangents -> a bump (AE "overshoot" idiom).
        val e = engineWith(
            Keyframe(KeyframeId(1), 0, value = 10.0, interpolation = InterpolationType.BEZIER, outTangent = 60.0),
            Keyframe(KeyframeId(2), 1000, value = 10.0, interpolation = InterpolationType.BEZIER, inTangent = -60.0))
        val mid = (e.evaluateKey(key, 500) as EvaluatedValue.FloatV).value
        assertTrue("expected a bump above 10, got $mid", mid > 12.0)
    }

    @Test fun wiggleIsAdditiveOnTopOfKeyframes() {
        val e = engineWith(
            Keyframe(KeyframeId(1), 0, value = 100.0), Keyframe(KeyframeId(2), 1000, value = 100.0))
        e.setWiggle(key, Wiggle(7, 3.0, 5.0, 2))
        val vs = (0..1000 step 20).map { (e.evaluateKey(key, it.toLong()) as EvaluatedValue.FloatV).value }
        assertTrue("wiggle must ride on keyframed value (~100), min=${vs.min()}", vs.min() > 90.0)
    }
}
