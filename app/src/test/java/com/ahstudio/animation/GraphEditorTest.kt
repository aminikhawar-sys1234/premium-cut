package com.ahstudio.animation

import com.ahstudio.animation.core.AnimationEngine
import com.ahstudio.animation.graph.GraphHandles
import com.ahstudio.animation.graph.GraphSampler
import com.ahstudio.animation.graph.TangentSolver
import com.ahstudio.animation.keyframes.*
import com.ahstudio.animation.math.Vec2
import com.ahstudio.animation.properties.BindingKey
import com.ahstudio.animation.properties.PropertyType
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class GraphEditorTest {
    private val key = BindingKey("L", "P")
    private fun k(id: Long, t: Long, v: Double, mode: TangentMode = TangentMode.AUTO) =
        Keyframe(KeyframeId(id), t, value = v, tangentMode = mode)

    private fun engine(kfs: List<Keyframe>): AnimationEngine {
        val e = AnimationEngine(); e.ensureTrack(key, PropertyType.FLOAT)
        kfs.forEach { e.addKeyframe(key, it) }
        return e
    }
    private fun v(e: AnimationEngine, t: Long) = (e.evaluateKey(key, t) as EvaluatedValue.FloatV).value

    @Test fun autoTangentsNeverOvershootMonotoneData() {
        val solved = TangentSolver.solve(listOf(k(1, 0, 0.0), k(2, 500, 10.0), k(3, 600, 11.0), k(4, 2000, 100.0)))
        val e = engine(solved)
        var prev = -1.0
        for (t in 0..2000 step 10) {
            val x = v(e, t.toLong())
            assertTrue("monotone at $t ($x < $prev)", x >= prev - 1e-9)
            assertTrue("no overshoot at $t ($x)", x in -1e-9..100.0 + 1e-9)
            prev = x
        }
    }

    @Test fun autoTangentIsZeroAtLocalExtremum() {
        val s = TangentSolver.solve(listOf(k(1, 0, 0.0), k(2, 1000, 50.0), k(3, 2000, 0.0)))
        assertEquals(0.0, s[1].outTangent, 1e-9)
        assertEquals(0.0, s[1].inTangent, 1e-9)
        // curve passes through the key exactly and never exceeds it
        val e = engine(s)
        for (t in 0..2000 step 25) assertTrue(v(e, t.toLong()) <= 50.0 + 1e-9)
        assertEquals(50.0, v(e, 1000), 1e-9)
    }

    @Test fun easyEaseGivesZeroVelocityAtKeys() {
        val base = listOf(k(1, 0, 0.0, TangentMode.FREE), k(2, 1000, 100.0, TangentMode.FREE))
        val eased = TangentSolver.easyEase(base, setOf(KeyframeId(1), KeyframeId(2)))
        val e = engine(eased)
        assertEquals(0.0, (e.evaluateKey(key, 1) as EvaluatedValue.FloatV).velocityPerSec, 1.0)
        assertTrue(v(e, 100) < 10.0)            // slow start, linear would be 10
        assertEquals(50.0, v(e, 500), 1e-6)     // symmetric
        assertTrue(v(e, 900) > 90.0)            // slow end
    }

    @Test fun easyEaseOutOnlyFlattensOutgoingSide() {
        val base = listOf(k(1, 0, 0.0, TangentMode.FREE), k(2, 1000, 100.0, TangentMode.FREE))
        val r = TangentSolver.easyEaseOut(base, setOf(KeyframeId(1)))
        assertEquals(0.0, r[0].outTangent, 0.0)
        assertEquals(InterpolationType.BEZIER, r[0].interpolation)
        assertEquals(r[1], base[1])
    }

    @Test fun linearModeReproducesStraightLine() {
        val s = TangentSolver.solve(listOf(k(1, 0, 0.0, TangentMode.LINEAR), k(2, 1000, 100.0, TangentMode.LINEAR)))
        val e = engine(s)
        for (t in 0..1000 step 100) assertEquals(t / 10.0, v(e, t.toLong()), 1e-6)
    }

    @Test fun handlesRoundTripThroughDrag() {
        val a = k(1, 0, 0.0, TangentMode.BROKEN); val b = k(2, 900, 90.0, TangentMode.BROKEN)
        val h = GraphHandles.handlesOf(null, a, b).outHandle!!
        assertEquals(300.0, h.timeMs, 1e-9)
        val dragged = GraphHandles.dragOut(a, b, 300.0, 60.0)          // lift handle to value 60
        val h2 = GraphHandles.handlesOf(null, dragged, b).outHandle!!
        assertEquals(60.0, h2.value, 1e-6)
        assertEquals(TangentMode.BROKEN, dragged.tangentMode)
    }

    @Test fun alignedDragMirrorsSlope() {
        val a = k(1, 0, 0.0, TangentMode.ALIGNED); val b = k(2, 900, 90.0)
        val d = GraphHandles.dragOut(a, b, 300.0, 30.0)
        assertEquals(d.outTangent, d.inTangent, 1e-9)
    }

    @Test fun sampleReturnsValueAndSpeed() {
        val e = engine(listOf(k(1, 0, 0.0, TangentMode.LINEAR), k(2, 1000, 100.0, TangentMode.LINEAR)))
        val s = GraphSampler.sample(e, key, 0, 1000, 11)
        assertEquals(11, s.size)
        assertEquals(100.0, s[5].speedPerSec, 1e-6)
        assertEquals(0.0 to 100.0, GraphSampler.valueRange(s))
    }

    @Test fun bakeProducesFrameAlignedKeys() {
        val e = engine(listOf(k(1, 0, 0.0, TangentMode.LINEAR), k(2, 1000, 100.0, TangentMode.LINEAR)))
        var id = 100L
        val baked = GraphSampler.bake(e, key, 0, 1000, 10.0) { KeyframeId(id++) }
        assertEquals(11, baked.size)
        assertEquals(50.0, baked[5].value, 1e-6)
        assertEquals(500L, baked[5].timeMs)
    }

    @Test fun reduceDropsCollinearKeysButKeepsCorners() {
        val kfs = (0..10).map { Keyframe(KeyframeId(it.toLong()), it * 100L, value = if (it <= 5) it * 10.0 else 50.0 - (it - 5) * 30.0) }
        val r = GraphSampler.reduce(kfs, 0.01)
        assertEquals(listOf(0L, 500L, 1000L), r.map { it.timeMs })
    }

    @Test fun reduceHandlesVec2() {
        val kfs = (0..4).map { Keyframe(KeyframeId(it.toLong()), it * 100L, vecValue = Vec2(it * 10.0, it * 5.0)) }
        assertEquals(2, GraphSampler.reduce(kfs, 0.001).size)
    }

    @Test fun smoothAveragesInteriorOnly() {
        val kfs = listOf(k(1, 0, 0.0), k(2, 100, 10.0), k(3, 200, 0.0), k(4, 300, 10.0), k(5, 400, 0.0))
        val s = GraphSampler.smooth(kfs, 3)
        assertEquals(0.0, s[0].value, 0.0); assertEquals(0.0, s[4].value, 0.0)
        assertTrue(abs(s[2].value - 6.6667) < 1e-3)
    }
}
