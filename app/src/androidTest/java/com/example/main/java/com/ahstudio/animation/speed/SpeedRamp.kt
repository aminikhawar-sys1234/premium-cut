package com.ahstudio.animation.speed

import com.ahstudio.animation.curves.CubicBezierTiming
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Speed ramping / time remapping (CapCut "Curve" speed, Premiere "Time Remapping", AE "Time Remap").
 *
 * The ramp is a piecewise-linear speed multiplier m(t) over NORMALISED output time t in [0,1].
 * Source position is the exact integral F(t) = (1/N) * integral_0^t m(s) ds, where N = integral_0^1 m
 * when [preserveDuration] is true, so F(0)=0, F(1)=1 and F is continuous and monotone -- the clip always
 * consumes exactly its source range (no jumps at ramp boundaries, which the old step-function code had).
 */
class SpeedRamp private constructor(
    private val ts: DoubleArray,
    private val ms: DoubleArray,
    private val norm: Double,
    private val area: Double
) {
    data class Point(val t: Double, val speed: Double)

    /** Control points as authored (speed multipliers, normalised by [norm] at evaluation time). */
    val points: List<Point> get() = ts.indices.map { Point(ts[it], ms[it] / norm) }

    // cumulative integral of the (unnormalised) speed at each control point
    private val cum = DoubleArray(ts.size).also { c ->
        for (i in 1 until ts.size) c[i] = c[i - 1] + 0.5 * (ms[i - 1] + ms[i]) * (ts[i] - ts[i - 1])
    }

    private fun seg(t: Double): Int {
        var lo = 0; var hi = ts.size - 2
        while (lo < hi) { val mid = (lo + hi + 1) ushr 1; if (ts[mid] <= t) lo = mid else hi = mid - 1 }
        return lo
    }

    /** Speed multiplier at output fraction t (after normalisation). */
    fun speedAt(t: Double): Double {
        val x = t.coerceIn(0.0, 1.0)
        val i = seg(x)
        val u = (x - ts[i]) / (ts[i + 1] - ts[i])
        return (ms[i] + (ms[i + 1] - ms[i]) * u) / norm
    }

    /** Source fraction consumed after output fraction t (monotone, 0..1 when preserveDuration). */
    fun sourceFraction(t: Double): Double {
        val x = t.coerceIn(0.0, 1.0)
        val i = seg(x)
        val dt = x - ts[i]
        val slope = (ms[i + 1] - ms[i]) / (ts[i + 1] - ts[i])
        return (cum[i] + ms[i] * dt + 0.5 * slope * dt * dt) / norm
    }

    /** Inverse: output fraction at which the given source fraction is reached (bisection; F is monotone). */
    fun outputFraction(sourceFrac: Double): Double {
        val target = sourceFrac.coerceIn(0.0, sourceFraction(1.0))
        var lo = 0.0; var hi = 1.0
        repeat(60) { val mid = 0.5 * (lo + hi); if (sourceFraction(mid) < target) lo = mid else hi = mid }
        return 0.5 * (lo + hi)
    }

    /** Source offset (ms) for an output offset into a clip of [outDurationMs] covering [sourceSpanMs] of source. */
    fun sourceMs(outOffsetMs: Long, outDurationMs: Long, sourceSpanMs: Double): Double =
        sourceFraction(outOffsetMs.toDouble() / max(1L, outDurationMs)) * sourceSpanMs

    /** Mean of the authored speed multiplier over the clip (1.0 = ramp neither shortens nor stretches the clip). */
    val averageSpeed: Double get() = area

    companion object {
        const val MIN_SPEED = 0.02

        fun constant(speed: Double = 1.0) = of(listOf(Point(0.0, speed), Point(1.0, speed)), false)

        /** Build from control points; speeds are clamped to >= [MIN_SPEED] so time never stalls or reverses. */
        fun of(points: List<Point>, preserveDuration: Boolean = true): SpeedRamp {
            require(points.size >= 2) { "need at least two control points" }
            val sorted = points.sortedBy { it.t }
            val t = ArrayList<Double>(); val m = ArrayList<Double>()
            // Force the domain to exactly [0,1] and drop duplicate x's (keep last)
            val first = sorted.first(); val last = sorted.last()
            if (first.t > 0.0) { t.add(0.0); m.add(first.speed.coerceAtLeast(MIN_SPEED)) }
            for (p in sorted) {
                val x = p.t.coerceIn(0.0, 1.0)
                if (t.isNotEmpty() && abs(t.last() - x) < 1e-9) { m[m.size - 1] = p.speed.coerceAtLeast(MIN_SPEED) }
                else { t.add(x); m.add(p.speed.coerceAtLeast(MIN_SPEED)) }
            }
            if (t.last() < 1.0) { t.add(1.0); m.add(last.speed.coerceAtLeast(MIN_SPEED)) }
            val ta = t.toDoubleArray(); val ma = m.toDoubleArray()
            var area = 0.0
            for (i in 1 until ta.size) area += 0.5 * (ma[i - 1] + ma[i]) * (ta[i] - ta[i - 1])
            return SpeedRamp(ta, ma, if (preserveDuration) area else 1.0, area)
        }

        // ---- presets: names mirror the app's SpeedCurvePreset / CapCut "Curve" templates ----
        fun easeIn() = of(listOf(Point(0.0, 0.25), Point(1.0, 1.75)))
        fun easeOut() = of(listOf(Point(0.0, 1.75), Point(1.0, 0.25)))
        fun easeInOut() = of(listOf(Point(0.0, 0.3), Point(0.5, 1.7), Point(1.0, 0.3)))
        /** Fast - slow - fast (CapCut "Montage" / app HERO_MONTAGE). */
        fun montage() = of(listOf(Point(0.0, 2.2), Point(0.30, 2.2), Point(0.40, 0.4), Point(0.60, 0.4), Point(0.70, 2.2), Point(1.0, 2.2)))
        /** Slow - fast - slow (app BULLET_TIME). */
        fun bulletTime() = of(listOf(Point(0.0, 1.8), Point(0.22, 1.8), Point(0.30, 0.25), Point(0.70, 0.25), Point(0.78, 1.8), Point(1.0, 1.8)))
        /** Accelerate then near-pause (app JUMPER). */
        fun jumper() = of(listOf(Point(0.0, 0.6), Point(0.45, 2.4), Point(0.55, 2.4), Point(0.62, 0.25), Point(1.0, 0.25)))
        /** CapCut "Flash In": starts very fast then settles. */
        fun flashIn() = of(listOf(Point(0.0, 3.0), Point(0.25, 0.8), Point(1.0, 0.6)))
        /** CapCut "Flash Out": cruises then bursts at the end. */
        fun flashOut() = of(listOf(Point(0.0, 0.6), Point(0.75, 0.8), Point(1.0, 3.0)))
        /** Single slow-motion dip centred at [centre] (Premiere "speed bump"). */
        fun speedBump(centre: Double = 0.5, width: Double = 0.3, dip: Double = 0.2): SpeedRamp {
            val a = (centre - width / 2).coerceIn(0.02, 0.95); val b = (centre + width / 2).coerceIn(a + 0.01, 0.98)
            return of(listOf(Point(0.0, 1.0), Point(a, 1.0), Point((a + b) / 2, dip), Point(b, 1.0), Point(1.0, 1.0)))
        }
        /** Speed profile from a CSS-style cubic bezier (app CUSTOM_BEZIER): bezier progress value is the speed (floored). */
        fun fromBezier(x1: Double, y1: Double, x2: Double, y2: Double, samples: Int = 24): SpeedRamp {
            val b = CubicBezierTiming(x1, y1, x2, y2)
            val pts = (0..samples).map { i -> val t = i.toDouble() / samples; Point(t, max(MIN_SPEED, b.progress(t))) }
            return of(pts)
        }
    }
}

/** Freeze-frame / reverse helpers used by the clip pipeline (Premiere "Frame Hold", CapCut "Freeze"). */
object TimeRemapTools {
    /** Source ms for output time with a freeze that holds source position [freezeSourceMs] from [holdStartMs] for [holdLenMs]. */
    fun freezeFrame(outMs: Long, holdStartMs: Long, holdLenMs: Long, sourceStartMs: Long, speed: Double = 1.0): Long {
        val tNoHold = when {
            outMs <= holdStartMs -> outMs
            outMs < holdStartMs + holdLenMs -> holdStartMs
            else -> outMs - holdLenMs
        }
        return sourceStartMs + (tNoHold * speed).roundToLong()
    }
    fun reverse(outMs: Long, durationMs: Long, sourceStartMs: Long, sourceEndMs: Long): Long =
        (sourceEndMs - (min(outMs, durationMs) * (sourceEndMs - sourceStartMs).toDouble() / max(1L, durationMs)).roundToLong())
            .coerceIn(sourceStartMs, sourceEndMs)
}
