package com.ahstudio.animation

import com.ahstudio.animation.core.AnimationEngine
import com.ahstudio.animation.expression.*
import com.ahstudio.animation.keyframes.*
import com.ahstudio.animation.math.Vec2
import com.ahstudio.animation.properties.BindingKey
import com.ahstudio.animation.properties.PropertyType
import org.junit.Assert.*
import org.junit.Test

class ExpressionTest {
    private class FakeHost(
        override val timeMs: Long = 0, override val value: XVal = XVal.Num(0.0),
        val keys: List<Pair<Long, Double>> = emptyList(), override val seed: Long = 5
    ) : ExpressionHost {
        override fun valueAt(timeMs: Long): XVal {
            if (keys.isEmpty()) return value
            val a = keys.last { it.first <= timeMs.coerceIn(keys.first().first, keys.last().first) }
            val bIdx = keys.indexOf(a) + 1
            if (bIdx >= keys.size) return XVal.Num(a.second)
            val b = keys[bIdx]
            val u = (timeMs - a.first).toDouble() / (b.first - a.first)
            return XVal.Num(a.second + (b.second - a.second) * u.coerceIn(0.0, 1.0))
        }
        override fun keyTimes() = keys.map { it.first }
        override fun propertyAt(targetId: String?, property: String, timeMs: Long): XVal? =
            if (property == "Transform.Rotation") XVal.Num(42.0) else null
    }
    private fun ev(src: String, host: ExpressionHost = FakeHost()) = Expression.compile(src).evaluate(host)
    private fun num(src: String, host: ExpressionHost = FakeHost()) = (ev(src, host) as XVal.Num).v

    @Test fun arithmeticAndPrecedence() {
        assertEquals(14.0, num("2 + 3 * 4"), 0.0)
        assertEquals(20.0, num("(2 + 3) * 4"), 0.0)
        assertEquals(512.0, num("2 ^ 3 ^ 2"), 0.0)           // right associative
        assertEquals(-4.0, num("-2 ^ 2 + 0"), 0.0)          // unary binds looser than ^ like maths
        assertEquals(1.0, num("7 % 3"), 0.0)
        assertEquals(0.0, num("5 / 0"), 0.0)                 // AE semantics: no NaN/Inf
    }

    @Test fun comparisonsLogicTernary() {
        assertEquals(1.0, num("3 > 2 && 2 >= 2"), 0.0)
        assertEquals(10.0, num("1 == 1 ? 10 : 20"), 0.0)
        assertEquals(20.0, num("!(1 == 1) ? 10 : 20"), 0.0)
        assertEquals(1.0, num("0 || 5"), 0.0)
    }

    @Test fun variablesAndMultiStatement() {
        assertEquals(30.0, num("var a = 10;\nvar b = a * 2\na + b"), 0.0)
    }

    @Test fun timeIsInSeconds() {
        assertEquals(1.5, num("time", FakeHost(timeMs = 1500)), 1e-12)
        assertEquals(100.0 + 1.5 * 10, num("value + time * 10", FakeHost(timeMs = 1500, value = XVal.Num(100.0))), 1e-9)
    }

    @Test fun vectorsIndexingAndBroadcast() {
        val v = ev("[1, 2] * 3 + [0, 1]") as XVal.Vec
        assertArrayEquals(doubleArrayOf(3.0, 7.0), v.v, 0.0)
        assertEquals(2.0, num("[5, 2][1]"), 0.0)
        assertEquals(5.0, num("length([3, 4])"), 1e-12)
        assertEquals(4.0, num("value.y", FakeHost(value = XVal.of(Vec2(1.0, 4.0)))), 0.0)
        assertThrows(ExpressionException::class.java) { ev("[1,2] + [1,2,3]") }
    }

    @Test fun mathFunctions() {
        assertEquals(1.0, num("sin(pi / 2)"), 1e-12)
        assertEquals(5.0, num("clamp(9, 0, 5)"), 0.0)
        assertEquals(30.0, num("linear(0.5, 0, 1, 10, 50)"), 1e-9)
        assertEquals(50.0, num("ease(0.5, 0, 1, 0, 100)"), 1e-9)
        assertEquals(0.0, num("ease(-3, 0, 1, 0, 100)"), 0.0)           // clamped outside range
        assertEquals(180.0, num("radiansToDegrees(pi)"), 1e-9)
        assertEquals(3.0, num("max(1, 3, 2)"), 0.0)
        assertEquals(0.0, num("sqrt(-4)"), 0.0)                          // never NaN
    }

    @Test fun wiggleIsDeterministicAndBounded() {
        val h = { t: Long -> FakeHost(timeMs = t, value = XVal.Num(100.0)) }
        val a = num("wiggle(3, 10)", h(777)); val b = num("wiggle(3, 10)", h(777))
        assertEquals(a, b, 0.0)
        for (t in 0..3000 step 17) assertTrue(num("wiggle(3, 10)", h(t.toLong())) in 89.9..110.1)
        assertNotEquals(num("wiggle(3, 10)", h(100)), num("wiggle(3, 10)", h(400)), 1e-9)
    }

    @Test fun randomIsFrameStableAndSeeded() {
        val a = num("random()", FakeHost(timeMs = 1000)); val b = num("random()", FakeHost(timeMs = 1010))   // same 30fps frame
        assertEquals(a, b, 0.0)
        assertTrue(a in 0.0..1.0)
        assertNotEquals(num("seedRandom(1); random()", FakeHost(timeMs = 1000)), num("seedRandom(2); random()", FakeHost(timeMs = 1000)), 1e-9)
    }

    private val keys = listOf(0L to 0.0, 1000L to 100.0)
    @Test fun loopOutCycle() {
        val h = { t: Long -> FakeHost(timeMs = t, keys = keys) }
        assertEquals(25.0, num("loopOut(\"cycle\")", h(1250)), 1e-9)
        assertEquals(50.0, num("loopOut()", h(2500)), 1e-9)
    }
    @Test fun loopOutPingPong() {
        val h = { t: Long -> FakeHost(timeMs = t, keys = keys) }
        assertEquals(75.0, num("loopOut(\"pingpong\")", h(1250)), 1e-9)      // going back
        assertEquals(25.0, num("loopOut(\"pingpong\")", h(2250)), 1e-9)      // going forward again
    }
    @Test fun loopOutOffsetAccumulates() {
        val h = { t: Long -> FakeHost(timeMs = t, keys = keys) }
        assertEquals(125.0, num("loopOut(\"offset\")", h(1250)), 1e-9)
        assertEquals(225.0, num("loopOut(\"offset\")", h(2250)), 1e-9)
    }
    @Test fun loopOutContinueExtrapolates() {
        assertEquals(150.0, num("loopOut(\"continue\")", FakeHost(timeMs = 1500, keys = keys)), 1e-6)
    }
    @Test fun loopInCycle() {
        assertEquals(75.0, num("loopIn(\"cycle\")", FakeHost(timeMs = -250, keys = keys)), 1e-9)
    }
    @Test fun loopInsideKeyRangeReturnsKeyframedValue() {
        assertEquals(40.0, num("loopOut()", FakeHost(timeMs = 400, keys = keys)), 1e-9)
    }

    @Test fun pickWhip() {
        assertEquals(84.0, num("rotation * 2"), 0.0)
        assertEquals(42.0, num("prop(\"\", \"Transform.Rotation\")"), 0.0)
        assertEquals(42.0, num("thisProp(\"Transform.Rotation\")"), 0.0)
    }

    @Test fun syntaxErrorsReportPosition() {
        val e = assertThrows(ExpressionException::class.java) { Expression.compile("1 + * 2") }
        assertTrue(e.position >= 0)
        assertThrows(ExpressionException::class.java) { Expression.compile("foo(") }
        assertThrows(ExpressionException::class.java) { Expression.compile("") }
        assertThrows(ExpressionException::class.java) { Expression.compile("1 \$\$ 2") }
        assertThrows(ExpressionException::class.java) { ev("unknownThing + 1") }
        assertThrows(ExpressionException::class.java) { ev("sin()") }
    }

    @Test fun sandboxLimitsResources() {
        assertThrows(ExpressionException::class.java) { Expression.compile("(".repeat(200) + "1" + ")".repeat(200)) }
        assertThrows(ExpressionException::class.java) { Expression.compile("1+".repeat(3000) + "1") }
        val big = (1..5000).joinToString(";") { "1" }
        // 5000 trivial statements is within the char limit but must stay within the step limit or be rejected, never hang
        runCatching { ev(big) }
    }

    // ------------------------- engine integration -------------------------
    private val rot = BindingKey("L", "Transform.Rotation")
    private val pos = BindingKey("L", "Transform.Position")

    @Test fun engineExpressionOverlaysKeyframes() {
        val e = AnimationEngine()
        e.ensureTrack(rot, PropertyType.FLOAT)
        e.addKeyframe(rot, Keyframe(KeyframeId(1), 0, value = 0.0))
        e.addKeyframe(rot, Keyframe(KeyframeId(2), 1000, value = 90.0))
        assertTrue(e.setExpression(rot, "value + 10").isSuccess)
        assertEquals(55.0, (e.evaluateKey(rot, 500) as EvaluatedValue.FloatV).value, 1e-9)
        assertEquals(55.0, e.evaluate(500).floatValue(rot)!!, 1e-9)
    }

    @Test fun engineExpressionVelocityIsDerivative() {
        val e = AnimationEngine()
        e.setBaseValue(rot, 0.0)
        e.setExpression(rot, "time * 30")
        val v = e.evaluateKey(rot, 700) as EvaluatedValue.FloatV
        assertEquals(21.0, v.value, 1e-9); assertEquals(30.0, v.velocityPerSec, 1e-6)
    }

    @Test fun engineExpressionOnVec2WithoutKeyframesUsesBaseValue() {
        val e = AnimationEngine()
        e.setBaseValue(pos, Vec2(10.0, 20.0))
        e.setExpression(pos, "value + [time * 100, 0]")
        val v = e.evaluateKey(pos, 500) as EvaluatedValue.Vec2V
        assertEquals(60.0, v.value.x, 1e-9); assertEquals(20.0, v.value.y, 1e-9)
    }

    @Test fun engineCrossPropertyPickWhip() {
        val e = AnimationEngine()
        val other = BindingKey("A", "Transform.Rotation")
        e.setBaseValue(other, 0.0)
        e.setExpression(other, "time * 90")
        e.setBaseValue(rot, 0.0)
        e.setExpression(rot, "prop(\"A\", \"Transform.Rotation\") / 3")
        assertEquals(30.0, (e.evaluateKey(rot, 1000) as EvaluatedValue.FloatV).value, 1e-9)
    }

    @Test fun engineCyclicExpressionsDoNotRecurseForever() {
        val e = AnimationEngine()
        val a = BindingKey("A", "P"); val b = BindingKey("B", "P")
        e.setBaseValue(a, 1.0); e.setBaseValue(b, 2.0)
        e.setExpression(a, "prop(\"B\", \"P\") + 1"); e.setExpression(b, "prop(\"A\", \"P\") + 1")
        val v = (e.evaluateKey(a, 0) as EvaluatedValue.FloatV).value
        assertTrue(v.isFinite())
    }

    @Test fun engineRuntimeErrorFallsBackToBaseValueAndReports() {
        val e = AnimationEngine()
        e.setBaseValue(rot, 7.0)
        assertTrue(e.setExpression(rot, "keyValue(3)").isSuccess)            // compiles, fails at runtime
        assertEquals(7.0, (e.evaluateKey(rot, 0) as EvaluatedValue.FloatV).value, 0.0)
        assertNotNull(e.expressionError(rot))
        e.setExpression(rot, "value + 1")
        e.evaluateKey(rot, 0)
        assertNull(e.expressionError(rot))
    }

    @Test fun engineRejectsBadSyntaxAndKeepsPreviousExpression() {
        val e = AnimationEngine()
        e.setBaseValue(rot, 5.0)
        e.setExpression(rot, "value * 2")
        assertTrue(e.setExpression(rot, "value *").isFailure)
        assertEquals("value * 2", e.expressionOf(rot))
    }

    @Test fun engineNonFiniteResultIsRejected() {
        val e = AnimationEngine()
        e.setBaseValue(rot, 5.0)
        e.setExpression(rot, "log(0) * 1e308 * 1e308 + 0")                    // log(0)=0 by our rule; use pow overflow instead
        e.setExpression(rot, "pow(10, 1000)")
        assertEquals(5.0, (e.evaluateKey(rot, 0) as EvaluatedValue.FloatV).value, 0.0)
        assertNotNull(e.expressionError(rot))
    }

    @Test fun engineExpressionLoopOutOverKeyframes() {
        val e = AnimationEngine()
        e.ensureTrack(rot, PropertyType.FLOAT)
        e.addKeyframe(rot, Keyframe(KeyframeId(1), 0, value = 0.0))
        e.addKeyframe(rot, Keyframe(KeyframeId(2), 1000, value = 100.0))
        e.setExpression(rot, "loopOut(\"pingpong\")")
        assertEquals(75.0, (e.evaluateKey(rot, 1250) as EvaluatedValue.FloatV).value, 1e-6)
    }

    @Test fun engineVersionBumpsOnExpressionChange() {
        val e = AnimationEngine(); val v0 = e.version
        e.setExpression(rot, "1"); assertTrue(e.version > v0)
        val v1 = e.version; e.clearExpression(rot); assertTrue(e.version > v1)
    }

    @Test fun engineProducesSameResultForScrubbingInAnyOrder() {
        val e = AnimationEngine()
        e.setBaseValue(rot, 0.0); e.setExpression(rot, "wiggle(2, 30) + time * 5")
        val times = listOf(900L, 100L, 5000L, 100L, 900L)
        val first = times.map { (e.evaluateKey(rot, it) as EvaluatedValue.FloatV).value }
        assertEquals(first[1], first[3], 0.0); assertEquals(first[0], first[4], 0.0)
    }
}
