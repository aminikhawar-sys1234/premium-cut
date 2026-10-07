package com.ahstudio.animation.graph

import com.ahstudio.animation.core.AnimationEngine
import com.ahstudio.animation.keyframes.*
import com.ahstudio.animation.math.Vec2
import com.ahstudio.animation.properties.BindingKey
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Graph-editor maths (After Effects "Graph Editor", Blender "Graph Editor / F-curves", Premiere "Bezier handles").
 *
 * Tangents are value-per-second. A segment a->b uses a.outTangent / b.inTangent only when a.interpolation == BEZIER
 * (see KeyframeEvaluator); every function here returns NEW immutable lists.
 */
object TangentSolver {
    /** Applies each keyframe's [Keyframe.tangentMode] to scalar keyframes (value channel). Input need not be sorted. */
    fun solve(input: List<Keyframe>): List<Keyframe> {
        val kfs = input.sortedBy { it.timeMs }
        if (kfs.size < 2) return kfs
        return kfs.mapIndexed { i, k ->
            when (k.tangentMode) {
                TangentMode.AUTO -> {
                    val s = autoSlope(kfs, i)
                    k.copy(inTangent = s, outTangent = s, interpolation = InterpolationType.BEZIER)
                }
                TangentMode.FLAT -> k.copy(inTangent = 0.0, outTangent = 0.0, interpolation = InterpolationType.BEZIER)
                TangentMode.LINEAR -> k.copy(
                    inTangent = if (i > 0) secant(kfs[i - 1], k) else 0.0,
                    outTangent = if (i < kfs.size - 1) secant(k, kfs[i + 1]) else 0.0,
                    interpolation = InterpolationType.BEZIER)
                TangentMode.ALIGNED -> k.copy(inTangent = k.outTangent)   // collinear handles share the slope
                TangentMode.FREE, TangentMode.BROKEN -> k
            }
        }
    }

    fun secant(a: Keyframe, b: Keyframe): Double {
        val dt = (b.timeMs - a.timeMs) / 1000.0
        return if (dt <= 0.0) 0.0 else (b.value - a.value) / dt
    }

    /**
     * Monotone ("auto-clamped") slope: Catmull-Rom for interior keys, zero at local extrema so the curve never
     * overshoots its keyframes, Fritsch-Carlson limited otherwise. End keys get the neighbour secant.
     */
    fun autoSlope(kfs: List<Keyframe>, i: Int): Double {
        if (kfs.size < 2) return 0.0
        if (i == 0) return secant(kfs[0], kfs[1])
        if (i == kfs.size - 1) return secant(kfs[i - 1], kfs[i])
        val dPrev = secant(kfs[i - 1], kfs[i]); val dNext = secant(kfs[i], kfs[i + 1])
        if (dPrev * dNext <= 0.0) return 0.0                                   // extremum / plateau
        val h0 = (kfs[i].timeMs - kfs[i - 1].timeMs).toDouble(); val h1 = (kfs[i + 1].timeMs - kfs[i].timeMs).toDouble()
        val w1 = 2.0 * h1 + h0; val w2 = h1 + 2.0 * h0                          // weighted harmonic mean (monotone cubic)
        return (w1 + w2) / (w1 / dPrev + w2 / dNext)
    }

    /** AE "Easy Ease" (F9): flat in+out tangents -> smooth start/stop. */
    fun easyEase(kfs: List<Keyframe>, ids: Set<KeyframeId>): List<Keyframe> = kfs.map {
        if (it.id in ids) it.copy(inTangent = 0.0, outTangent = 0.0, tangentMode = TangentMode.FLAT, interpolation = InterpolationType.BEZIER) else it
    }
    /** AE "Easy Ease In" (Shift+F9): only the incoming side is flattened. */
    fun easyEaseIn(kfs: List<Keyframe>, ids: Set<KeyframeId>): List<Keyframe> = kfs.map {
        if (it.id in ids) it.copy(inTangent = 0.0, tangentMode = TangentMode.BROKEN) else it
    }
    /** AE "Easy Ease Out" (Ctrl+Shift+F9): only the outgoing side is flattened. */
    fun easyEaseOut(kfs: List<Keyframe>, ids: Set<KeyframeId>): List<Keyframe> = kfs.map {
        if (it.id in ids) it.copy(outTangent = 0.0, tangentMode = TangentMode.BROKEN, interpolation = InterpolationType.BEZIER) else it
    }
    fun toHold(kfs: List<Keyframe>, ids: Set<KeyframeId>): List<Keyframe> =
        kfs.map { if (it.id in ids) it.copy(interpolation = InterpolationType.HOLD) else it }
    fun toLinear(kfs: List<Keyframe>, ids: Set<KeyframeId>): List<Keyframe> =
        kfs.map { if (it.id in ids) it.copy(interpolation = InterpolationType.LINEAR, tangentMode = TangentMode.LINEAR) else it }
}

/** Handle (control point) geometry used by the graph-editor UI: drag handles in (ms, value) space. */
object GraphHandles {
    data class Handle(val timeMs: Double, val value: Double)
    data class Pair2(val inHandle: Handle?, val outHandle: Handle?)

    /** Control points exactly as the evaluator interprets them (1/3 of the neighbouring span). */
    fun handlesOf(prev: Keyframe?, k: Keyframe, next: Keyframe?): Pair2 {
        val outH = next?.let {
            val dt = (it.timeMs - k.timeMs).toDouble()
            Handle(k.timeMs + dt / 3.0, k.value + k.outTangent * (dt / 1000.0) / 3.0)
        }
        val inH = prev?.let {
            val dt = (k.timeMs - it.timeMs).toDouble()
            Handle(k.timeMs - dt / 3.0, k.value - k.inTangent * (dt / 1000.0) / 3.0)
        }
        return Pair2(inH, outH)
    }

    /**
     * User dragged the OUT handle to (handleTimeMs, handleValue). Honors the key's tangent mode:
     * ALIGNED/AUTO mirror the slope on the other side (AUTO converts to ALIGNED, like Blender), BROKEN/FREE do not.
     */
    fun dragOut(k: Keyframe, next: Keyframe, handleTimeMs: Double, handleValue: Double): Keyframe {
        val dtSec = max(1e-3, (handleTimeMs - k.timeMs) / 1000.0).coerceAtMost((next.timeMs - k.timeMs) / 1000.0)
        val slope = (handleValue - k.value) / dtSec
        return when (k.tangentMode) {
            TangentMode.ALIGNED, TangentMode.AUTO, TangentMode.FLAT, TangentMode.LINEAR ->
                k.copy(outTangent = slope, inTangent = slope, tangentMode = TangentMode.ALIGNED, interpolation = InterpolationType.BEZIER)
            else -> k.copy(outTangent = slope, tangentMode = TangentMode.BROKEN, interpolation = InterpolationType.BEZIER)
        }
    }

    fun dragIn(prev: Keyframe, k: Keyframe, handleTimeMs: Double, handleValue: Double): Keyframe {
        val dtSec = max(1e-3, (k.timeMs - handleTimeMs) / 1000.0).coerceAtMost((k.timeMs - prev.timeMs) / 1000.0)
        val slope = (k.value - handleValue) / dtSec
        return when (k.tangentMode) {
            TangentMode.ALIGNED, TangentMode.AUTO, TangentMode.FLAT, TangentMode.LINEAR ->
                k.copy(inTangent = slope, outTangent = slope, tangentMode = TangentMode.ALIGNED)
            else -> k.copy(inTangent = slope, tangentMode = TangentMode.BROKEN)
        }
    }
}

/** Curve sampling for value-graph and speed-graph display, plus keyframe baking/reduction/smoothing. */
object GraphSampler {
    data class Sample(val timeMs: Long, val value: Double, val speedPerSec: Double)

    /** Samples one property of the engine (value graph + speed graph), inclusive of both ends. */
    fun sample(engine: AnimationEngine, key: BindingKey, startMs: Long, endMs: Long, count: Int): List<Sample> {
        require(count >= 2) { "count must be >= 2" }
        val out = ArrayList<Sample>(count)
        for (i in 0 until count) {
            val t = startMs + ((endMs - startMs) * i.toDouble() / (count - 1)).roundToLong()
            when (val v = engine.evaluateKey(key, t)) {
                is EvaluatedValue.FloatV -> out.add(Sample(t, v.value, v.velocityPerSec))
                is EvaluatedValue.Vec2V -> out.add(Sample(t, v.value.length(), v.velocityPerSec.length()))
                null -> {}
            }
        }
        return out
    }

    /** "Convert Expression to Keyframes" / Blender "Bake Action": linear keyframes at [fps] covering [startMs, endMs]. */
    fun bake(engine: AnimationEngine, key: BindingKey, startMs: Long, endMs: Long, fps: Double, newId: () -> KeyframeId): List<Keyframe> {
        require(fps > 0.0 && endMs >= startMs)
        val step = 1000.0 / fps
        val out = ArrayList<Keyframe>()
        var i = 0
        while (true) {
            val t = startMs + (i * step).roundToLong()
            if (t > endMs) break
            when (val v = engine.evaluateKey(key, t)) {
                is EvaluatedValue.FloatV -> out.add(Keyframe(newId(), t, value = v.value))
                is EvaluatedValue.Vec2V -> out.add(Keyframe(newId(), t, vecValue = v.value))
                null -> {}
            }
            i++
        }
        val last = out.lastOrNull()
        if (last != null && last.timeMs < endMs && endMs - last.timeMs > 1) {
            when (val v = engine.evaluateKey(key, endMs)) {
                is EvaluatedValue.FloatV -> out.add(Keyframe(newId(), endMs, value = v.value))
                is EvaluatedValue.Vec2V -> out.add(Keyframe(newId(), endMs, vecValue = v.value))
                null -> {}
            }
        }
        return out
    }

    /**
     * Ramer-Douglas-Peucker keyframe reduction ("Reduce Keyframes" / Blender "Clean Channels").
     * [tolerance] is in value units; time is not part of the error metric (we measure vertical deviation from the
     * chord, which is what the playback interpolation of the reduced curve actually produces).
     */
    fun reduce(kfs: List<Keyframe>, tolerance: Double): List<Keyframe> {
        val sorted = kfs.sortedBy { it.timeMs }
        if (sorted.size <= 2) return sorted
        val keep = BooleanArray(sorted.size)
        keep[0] = true; keep[sorted.size - 1] = true
        fun rec(lo: Int, hi: Int) {
            if (hi <= lo + 1) return
            val a = sorted[lo]; val b = sorted[hi]
            var worst = -1.0; var idx = -1
            for (i in lo + 1 until hi) {
                val u = (sorted[i].timeMs - a.timeMs).toDouble() / (b.timeMs - a.timeMs).coerceAtLeast(1L)
                val d = deviation(a, b, sorted[i], u)
                if (d > worst) { worst = d; idx = i }
            }
            if (worst > tolerance && idx >= 0) { keep[idx] = true; rec(lo, idx); rec(idx, hi) }
        }
        rec(0, sorted.size - 1)
        return sorted.filterIndexed { i, _ -> keep[i] }
    }
    private fun deviation(a: Keyframe, b: Keyframe, k: Keyframe, u: Double): Double {
        val av = a.vecValue; val bv = b.vecValue; val kv = k.vecValue
        if (av != null && bv != null && kv != null) return (av.lerp(bv, u) - kv).length()
        return abs(a.value + (b.value - a.value) * u - k.value)
    }

    /** Moving-average smoothing of the value channel (AE "Smoother"); keeps first/last key fixed. */
    fun smooth(kfs: List<Keyframe>, window: Int): List<Keyframe> {
        val sorted = kfs.sortedBy { it.timeMs }
        val w = window.coerceAtLeast(1) / 2
        if (w == 0 || sorted.size < 3) return sorted
        return sorted.mapIndexed { i, k ->
            if (i == 0 || i == sorted.size - 1) k else {
                val lo = max(0, i - w); val hi = min(sorted.size - 1, i + w)
                if (k.vecValue != null) {
                    var sx = 0.0; var sy = 0.0
                    for (j in lo..hi) { val v = sorted[j].vecValue ?: Vec2(sorted[j].value, sorted[j].value); sx += v.x; sy += v.y }
                    k.copy(vecValue = Vec2(sx / (hi - lo + 1), sy / (hi - lo + 1)))
                } else k.copy(value = (lo..hi).sumOf { sorted[it].value } / (hi - lo + 1))
            }
        }
    }

    /** Peak speed of a sampled curve (for auto-fitting the speed-graph axis). */
    fun peakSpeed(samples: List<Sample>): Double = samples.maxOfOrNull { abs(it.speedPerSec) } ?: 0.0
    fun valueRange(samples: List<Sample>): Pair<Double, Double> =
        if (samples.isEmpty()) 0.0 to 0.0 else samples.minOf { it.value } to samples.maxOf { it.value }
}
