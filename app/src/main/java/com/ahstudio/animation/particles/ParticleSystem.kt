package com.ahstudio.animation.particles

import com.ahstudio.animation.easing.Easing
import com.ahstudio.animation.easing.EasingType
import com.ahstudio.animation.math.Color4
import com.ahstudio.animation.math.Vec2
import com.ahstudio.animation.procedural.Procedural
import kotlin.math.*

enum class EmitterShape { POINT, LINE, CIRCLE, RECT }

data class ParticleConfig(
    val seed: Long = 1L,
    val shape: EmitterShape = EmitterShape.POINT,
    val position: Vec2 = Vec2.ZERO,
    /** LINE: length along X. CIRCLE: radius. RECT: width/height. */
    val extent: Vec2 = Vec2.ZERO,
    val ratePerSec: Double = 30.0,
    /** If > 0, this many particles spawn at once at [startMs] and [ratePerSec] is ignored. */
    val burstCount: Int = 0,
    val startMs: Long = 0L,
    /** null = emits forever. */
    val emitDurationMs: Long? = null,
    val lifeMs: ClosedFloatingPointRange<Double> = 1000.0..2000.0,
    val speed: ClosedFloatingPointRange<Double> = 80.0..160.0,
    val directionDeg: Double = -90.0,
    val spreadDeg: Double = 30.0,
    val gravity: Vec2 = Vec2(0.0, 120.0),
    /** Linear drag coefficient k (1/s): v' = a - k v. */
    val drag: Double = 0.0,
    val sizeStart: Double = 8.0, val sizeEnd: Double = 0.0,
    val colorStart: Color4 = Color4(1.0, 0.8, 0.2, 1.0), val colorEnd: Color4 = Color4(1.0, 0.1, 0.0, 0.0),
    val spinDegPerSec: ClosedFloatingPointRange<Double> = 0.0..0.0,
    val lifeEasing: EasingType = EasingType.LINEAR,
    val maxParticles: Int = 5000
)

data class Particle(val index: Long, val pos: Vec2, val vel: Vec2, val size: Double, val color: Color4, val rotationDeg: Double, val age01: Double)

/**
 * Deterministic, SEEKABLE particle system (After Effects CC Particle World / Blender particle emitter, but closed-form):
 * every particle is a pure function of (seed, index, time), so scrubbing, preview and export are frame-identical
 * and there is no state to replay.
 */
class ParticleSystem(val cfg: ParticleConfig) {
    private fun r(i: Long, k: Int) = Procedural.rand01(cfg.seed, i * 16 + k)
    private fun rng(i: Long, k: Int, range: ClosedFloatingPointRange<Double>) = range.start + r(i, k) * (range.endInclusive - range.start)

    private fun birthMs(i: Long): Double =
        if (cfg.burstCount > 0) cfg.startMs.toDouble() else cfg.startMs + i * 1000.0 / cfg.ratePerSec.coerceAtLeast(1e-6)

    private fun emitCount(): Long {
        if (cfg.burstCount > 0) return cfg.burstCount.toLong().coerceAtMost(cfg.maxParticles.toLong())
        val dur = cfg.emitDurationMs ?: return Long.MAX_VALUE / 4
        return (dur * cfg.ratePerSec / 1000.0).toLong().coerceAtLeast(0)
    }

    fun particlesAt(timeMs: Long): List<Particle> {
        if (timeMs < cfg.startMs) return emptyList()
        val maxLife = cfg.lifeMs.endInclusive
        val total = emitCount()
        val first: Long; val last: Long
        if (cfg.burstCount > 0) { first = 0; last = total - 1 }
        else {
            first = max(0.0, ceil((timeMs - maxLife - cfg.startMs) * cfg.ratePerSec / 1000.0)).toLong()
            last = min(total - 1, floor((timeMs - cfg.startMs) * cfg.ratePerSec / 1000.0).toLong())
        }
        val out = ArrayList<Particle>()
        var i = first
        while (i <= last && out.size < cfg.maxParticles) {
            particle(i, timeMs)?.let { out.add(it) }
            i++
        }
        return out
    }

    fun particle(i: Long, timeMs: Long): Particle? {
        val life = rng(i, 0, cfg.lifeMs)
        val age = timeMs - birthMs(i)
        if (age < 0 || age >= life) return null
        val tau = age / 1000.0
        val origin = spawnPoint(i)
        val ang = Math.toRadians(cfg.directionDeg + (r(i, 3) - 0.5) * cfg.spreadDeg)
        val spd = rng(i, 4, cfg.speed)
        val v0 = Vec2(cos(ang) * spd, sin(ang) * spd)
        val k = cfg.drag
        val g = cfg.gravity
        val pos: Vec2; val vel: Vec2
        if (k < 1e-9) {
            pos = origin + v0 * tau + g * (0.5 * tau * tau); vel = v0 + g * tau
        } else {
            val e = exp(-k * tau)
            val gk = g / k
            vel = gk + (v0 - gk) * e
            pos = origin + gk * tau + (v0 - gk) * ((1 - e) / k)
        }
        val u = Easing.apply(cfg.lifeEasing, age / life)
        val size = cfg.sizeStart + (cfg.sizeEnd - cfg.sizeStart) * u
        return Particle(i, pos, vel, size, cfg.colorStart.lerp(cfg.colorEnd, u),
            rng(i, 5, cfg.spinDegPerSec) * tau + r(i, 6) * 360.0 * (if (cfg.spinDegPerSec.endInclusive != 0.0) 1.0 else 0.0), age / life)
    }

    private fun spawnPoint(i: Long): Vec2 = when (cfg.shape) {
        EmitterShape.POINT -> cfg.position
        EmitterShape.LINE -> cfg.position + Vec2((r(i, 1) - 0.5) * cfg.extent.x, 0.0)
        EmitterShape.CIRCLE -> { val a = r(i, 1) * 2 * PI; val rad = sqrt(r(i, 2)) * cfg.extent.x; cfg.position + Vec2(cos(a) * rad, sin(a) * rad) }
        EmitterShape.RECT -> cfg.position + Vec2((r(i, 1) - 0.5) * cfg.extent.x, (r(i, 2) - 0.5) * cfg.extent.y)
    }
}
