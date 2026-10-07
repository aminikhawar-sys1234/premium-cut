package com.example.engine.ai.tracking

import kotlin.math.abs
import kotlin.math.hypot

data class LkResult(
    val x: Float,
    val y: Float,
    val ok: Boolean,
    /** Mean absolute intensity residual inside the window (lower is better). */
    val residual: Float
)

/**
 * Pyramidal iterative Lucas-Kanade optical flow with sub-pixel accuracy and a
 * forward-backward consistency check (the same family of tracker After Effects,
 * Premiere and Nuke use for point tracking).
 */
class LucasKanade(
    private val halfWindow: Int = 7,
    private val maxIterations: Int = 25,
    private val epsilon: Float = 0.01f,
    private val minEigenThreshold: Float = 1e-3f,
    private val maxResidual: Float = 40f,
    /** Max (mean abs residual / template contrast); rejects matches onto unrelated texture. */
    private val maxResidualRatio: Float = 0.5f
) {

    /**
     * Tracks one point from [prev] to [next].
     * [guessDx]/[guessDy] is an optional level-0 displacement prediction.
     */
    fun track(
        prev: ImagePyramid,
        next: ImagePyramid,
        px: Float,
        py: Float,
        guessDx: Float = 0f,
        guessDy: Float = 0f
    ): LkResult {
        val top = minOf(prev.levels.size, next.levels.size) - 1
        var gx = guessDx / (1 shl top).toFloat()
        var gy = guessDy / (1 shl top).toFloat()
        var residual = 0f
        var templateStd = 1f
        val win = 2 * halfWindow + 1
        val n = win * win
        val tmpI = FloatArray(n)
        val tmpGx = FloatArray(n)
        val tmpGy = FloatArray(n)

        for (level in top downTo 0) {
            val scale = 1f / (1 shl level).toFloat()
            val ux = px * scale
            val uy = py * scale
            val pl = prev.levels[level]
            val nl = next.levels[level]
            val w = nl.image.width.toFloat()
            val h = nl.image.height.toFloat()

            // Template patch + structure tensor from the previous image.
            var gxx = 0.0
            var gxy = 0.0
            var gyy = 0.0
            var sumI = 0.0
            var sumI2 = 0.0
            var k = 0
            for (j in -halfWindow..halfWindow) {
                for (i in -halfWindow..halfWindow) {
                    val sx = ux + i
                    val sy = uy + j
                    val iv = pl.image.sample(sx, sy)
                    val dx = pl.gx.sample(sx, sy)
                    val dy = pl.gy.sample(sx, sy)
                    tmpI[k] = iv
                    tmpGx[k] = dx
                    tmpGy[k] = dy
                    gxx += dx * dx
                    gxy += dx * dy
                    gyy += dy * dy
                    sumI += iv
                    sumI2 += iv * iv
                    k++
                }
            }
            if (level == 0) {
                val mean = sumI / n
                templateStd = kotlin.math.sqrt((sumI2 / n - mean * mean).coerceAtLeast(0.0)).toFloat()
            }
            val det = gxx * gyy - gxy * gxy
            val minEig = 0.5 * ((gxx + gyy) - kotlin.math.sqrt(((gxx - gyy) * (gxx - gyy) + 4 * gxy * gxy).coerceAtLeast(0.0)))
            if (det < 1e-9 || (minEig / n) < minEigenThreshold) {
                return LkResult(px + guessDx, py + guessDy, false, Float.MAX_VALUE)
            }

            var iter = 0
            while (iter < maxIterations) {
                val cx = ux + gx
                val cy = uy + gy
                if (cx < -halfWindow || cy < -halfWindow || cx > w + halfWindow || cy > h + halfWindow) {
                    return LkResult(px + guessDx, py + guessDy, false, Float.MAX_VALUE)
                }
                var bx = 0.0
                var by = 0.0
                var sumAbs = 0.0
                k = 0
                for (j in -halfWindow..halfWindow) {
                    for (i in -halfWindow..halfWindow) {
                        val jv = nl.image.sample(cx + i, cy + j)
                        val diff = tmpI[k] - jv
                        bx += diff * tmpGx[k]
                        by += diff * tmpGy[k]
                        sumAbs += abs(diff)
                        k++
                    }
                }
                residual = (sumAbs / n).toFloat()
                val dx = ((gyy * bx - gxy * by) / det).toFloat()
                val dy = ((gxx * by - gxy * bx) / det).toFloat()
                gx += dx
                gy += dy
                iter++
                if (hypot(dx, dy) < epsilon) break
            }
            if (level > 0) {
                gx *= 2f
                gy *= 2f
            }
        }

        val rx = px + gx
        val ry = py + gy
        val base = next.base
        val inside = rx >= 0f && ry >= 0f && rx <= base.width - 1 && ry <= base.height - 1
        val ratio = residual / templateStd.coerceAtLeast(1f)
        return LkResult(rx, ry, inside && residual <= maxResidual && ratio <= maxResidualRatio, residual)
    }

    /**
     * Forward-backward validated tracking. A point is accepted only if tracking it back
     * lands within [fbThreshold] pixels of where it started.
     */
    fun trackValidated(
        prev: ImagePyramid,
        next: ImagePyramid,
        px: Float,
        py: Float,
        guessDx: Float = 0f,
        guessDy: Float = 0f,
        fbThreshold: Float = 1.0f
    ): LkResult {
        val fwd = track(prev, next, px, py, guessDx, guessDy)
        if (!fwd.ok) return fwd
        val back = track(next, prev, fwd.x, fwd.y, -(fwd.x - px), -(fwd.y - py))
        if (!back.ok) return fwd.copy(ok = false)
        val err = hypot(back.x - px, back.y - py)
        return if (err <= fbThreshold) fwd else fwd.copy(ok = false)
    }
}
