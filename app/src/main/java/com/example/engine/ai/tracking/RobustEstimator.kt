package com.example.engine.ai.tracking

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.random.Random

/** 3x3 projective transform, row-major, h[8] normalised to 1 where possible. */
class Homography(val h: DoubleArray) {
    init { require(h.size == 9) }

    fun apply(x: Double, y: Double): DoubleArray {
        val w = h[6] * x + h[7] * y + h[8]
        val ww = if (abs(w) < 1e-12) 1e-12 else w
        return doubleArrayOf((h[0] * x + h[1] * y + h[2]) / ww, (h[3] * x + h[4] * y + h[5]) / ww)
    }

    fun compose(other: Homography): Homography {
        // this * other  (apply other first)
        val r = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) {
            var s = 0.0
            for (k in 0..2) s += h[i * 3 + k] * other.h[k * 3 + j]
            r[i * 3 + j] = s
        }
        return Homography(normalize(r))
    }

    fun inverse(): Homography? {
        val m = h
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val hh = m[7]; val i = m[8]
        val det = a * (e * i - f * hh) - b * (d * i - f * g) + c * (d * hh - e * g)
        if (abs(det) < 1e-14) return null
        val inv = doubleArrayOf(
            (e * i - f * hh) / det, (c * hh - b * i) / det, (b * f - c * e) / det,
            (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
            (d * hh - e * g) / det, (b * g - a * hh) / det, (a * e - b * d) / det
        )
        return Homography(normalize(inv))
    }

    /**
     * Local affine Jacobian [[a b],[c d]] of the mapping at (x, y); gives translation-free
     * scale / rotation of the plane under the point (used to turn a planar track into
     * position + scale + rotation keyframes).
     */
    fun jacobianAt(x: Double, y: Double): DoubleArray {
        val w = h[6] * x + h[7] * y + h[8]
        val ww = if (abs(w) < 1e-12) 1e-12 else w
        val mx = (h[0] * x + h[1] * y + h[2]) / ww
        val my = (h[3] * x + h[4] * y + h[5]) / ww
        return doubleArrayOf(
            (h[0] - h[6] * mx) / ww, (h[1] - h[7] * mx) / ww,
            (h[3] - h[6] * my) / ww, (h[4] - h[7] * my) / ww
        )
    }

    companion object {
        fun identity() = Homography(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))

        fun fromSimilarity(s: Similarity) = Homography(
            doubleArrayOf(s.a, -s.b, s.tx, s.b, s.a, s.ty, 0.0, 0.0, 1.0)
        )

        fun normalize(m: DoubleArray): DoubleArray {
            val d = if (abs(m[8]) > 1e-12) m[8] else return m
            return DoubleArray(9) { m[it] / d }
        }
    }
}

/** x' = a x - b y + tx ; y' = b x + a y + ty (rotation + uniform scale + translation). */
data class Similarity(val a: Double, val b: Double, val tx: Double, val ty: Double) {
    val scale: Double get() = hypot(a, b)
    val rotationRad: Double get() = atan2(b, a)

    fun apply(x: Double, y: Double) = doubleArrayOf(a * x - b * y + tx, b * x + a * y + ty)
}

class RansacResult<T>(val model: T, val inliers: BooleanArray, val inlierCount: Int)

object RobustEstimator {

    /** Closed-form least-squares similarity over the selected pairs. */
    fun fitSimilarity(src: DoubleArray, dst: DoubleArray, idx: IntArray): Similarity? {
        val n = idx.size
        if (n < 2) return null
        var spx = 0.0; var spy = 0.0; var sqx = 0.0; var sqy = 0.0
        for (i in idx) {
            spx += src[2 * i]; spy += src[2 * i + 1]
            sqx += dst[2 * i]; sqy += dst[2 * i + 1]
        }
        val pcx = spx / n; val pcy = spy / n
        val qcx = sqx / n; val qcy = sqy / n
        var dot = 0.0; var cross = 0.0; var pp = 0.0
        for (i in idx) {
            val px = src[2 * i] - pcx; val py = src[2 * i + 1] - pcy
            val qx = dst[2 * i] - qcx; val qy = dst[2 * i + 1] - qcy
            dot += px * qx + py * qy
            cross += px * qy - py * qx
            pp += px * px + py * py
        }
        if (pp < 1e-9) return null
        val a = dot / pp
        val b = cross / pp
        val tx = qcx - (a * pcx - b * pcy)
        val ty = qcy - (b * pcx + a * pcy)
        return Similarity(a, b, tx, ty)
    }

    /**
     * RANSAC similarity. [src]/[dst] are interleaved x,y pairs.
     * Returns null when fewer than [minInliers] points agree.
     */
    fun ransacSimilarity(
        src: DoubleArray,
        dst: DoubleArray,
        threshold: Double = 2.0,
        iterations: Int = 120,
        minInliers: Int = 4,
        seed: Int = 1234
    ): RansacResult<Similarity>? {
        val n = src.size / 2
        if (n < 2) return null
        val rnd = Random(seed)
        var best: Similarity? = null
        var bestMask = BooleanArray(n)
        var bestCount = 0
        val th2 = threshold * threshold

        val tries = if (n == 2) 1 else iterations
        for (t in 0 until tries) {
            val i0 = if (n == 2) 0 else rnd.nextInt(n)
            var i1 = if (n == 2) 1 else rnd.nextInt(n)
            if (i1 == i0) i1 = (i0 + 1) % n
            val cand = fitSimilarity(src, dst, intArrayOf(i0, i1)) ?: continue
            if (cand.scale < 0.2 || cand.scale > 5.0) continue
            val mask = BooleanArray(n)
            var c = 0
            for (i in 0 until n) {
                val p = cand.apply(src[2 * i], src[2 * i + 1])
                val dx = p[0] - dst[2 * i]; val dy = p[1] - dst[2 * i + 1]
                if (dx * dx + dy * dy <= th2) { mask[i] = true; c++ }
            }
            if (c > bestCount) { bestCount = c; best = cand; bestMask = mask }
        }
        if (best == null || bestCount < minInliers.coerceAtMost(n)) return null

        // Refit on all inliers, then re-evaluate once.
        var model: Similarity = best
        for (round in 0 until 2) {
            val idx = (0 until n).filter { bestMask[it] }.toIntArray()
            model = fitSimilarity(src, dst, idx) ?: break
            val mask = BooleanArray(n)
            var c = 0
            for (i in 0 until n) {
                val p = model.apply(src[2 * i], src[2 * i + 1])
                val dx = p[0] - dst[2 * i]; val dy = p[1] - dst[2 * i + 1]
                if (dx * dx + dy * dy <= th2) { mask[i] = true; c++ }
            }
            if (c < minInliers.coerceAtMost(n)) break
            bestMask = mask
            bestCount = c
        }
        return RansacResult(model, bestMask, bestCount)
    }

    /**
     * Least-squares homography on the selected pairs using Hartley normalisation and the
     * normal equations (h33 fixed to 1 in normalised space). Exact for 4 pairs.
     */
    fun fitHomography(src: DoubleArray, dst: DoubleArray, idx: IntArray): Homography? {
        val n = idx.size
        if (n < 4) return null
        val ts = normalizationOf(src, idx)
        val td = normalizationOf(dst, idx)

        val ata = Array(8) { DoubleArray(8) }
        val atb = DoubleArray(8)
        val rowA = DoubleArray(8)
        for (i in idx) {
            val x = (src[2 * i] - ts[0]) * ts[2]
            val y = (src[2 * i + 1] - ts[1]) * ts[2]
            val u = (dst[2 * i] - td[0]) * td[2]
            val v = (dst[2 * i + 1] - td[1]) * td[2]

            // Row for u: [x y 1 0 0 0 -ux -uy] h = u
            rowA[0] = x; rowA[1] = y; rowA[2] = 1.0; rowA[3] = 0.0; rowA[4] = 0.0; rowA[5] = 0.0
            rowA[6] = -u * x; rowA[7] = -u * y
            accumulate(ata, atb, rowA, u)
            // Row for v: [0 0 0 x y 1 -vx -vy] h = v
            rowA[0] = 0.0; rowA[1] = 0.0; rowA[2] = 0.0; rowA[3] = x; rowA[4] = y; rowA[5] = 1.0
            rowA[6] = -v * x; rowA[7] = -v * y
            accumulate(ata, atb, rowA, v)
        }
        val sol = solveLinear(ata, atb) ?: return null
        val hn = doubleArrayOf(sol[0], sol[1], sol[2], sol[3], sol[4], sol[5], sol[6], sol[7], 1.0)

        // Denormalise: H = Td^-1 * Hn * Ts
        val tsM = doubleArrayOf(ts[2], 0.0, -ts[0] * ts[2], 0.0, ts[2], -ts[1] * ts[2], 0.0, 0.0, 1.0)
        val tdInv = doubleArrayOf(1.0 / td[2], 0.0, td[0], 0.0, 1.0 / td[2], td[1], 0.0, 0.0, 1.0)
        val r = mul3(tdInv, mul3(hn, tsM))
        if (r.any { it.isNaN() || it.isInfinite() }) return null
        return Homography(Homography.normalize(r))
    }

    fun ransacHomography(
        src: DoubleArray,
        dst: DoubleArray,
        threshold: Double = 2.0,
        iterations: Int = 200,
        minInliers: Int = 6,
        seed: Int = 4321
    ): RansacResult<Homography>? {
        val n = src.size / 2
        if (n < 4) return null
        val rnd = Random(seed)
        var best: Homography? = null
        var bestMask = BooleanArray(n)
        var bestCount = 0
        val th2 = threshold * threshold

        for (t in 0 until iterations) {
            val sample = pickDistinct(rnd, n, 4)
            if (isDegenerate(src, sample)) continue
            val cand = fitHomography(src, dst, sample) ?: continue
            val mask = BooleanArray(n)
            var c = 0
            for (i in 0 until n) {
                val p = cand.apply(src[2 * i], src[2 * i + 1])
                val dx = p[0] - dst[2 * i]; val dy = p[1] - dst[2 * i + 1]
                if (dx * dx + dy * dy <= th2) { mask[i] = true; c++ }
            }
            if (c > bestCount) { bestCount = c; best = cand; bestMask = mask }
        }
        if (best == null || bestCount < minInliers.coerceAtMost(n)) return null

        var model: Homography = best
        for (round in 0 until 2) {
            val idx = (0 until n).filter { bestMask[it] }.toIntArray()
            model = fitHomography(src, dst, idx) ?: break
            val mask = BooleanArray(n)
            var c = 0
            for (i in 0 until n) {
                val p = model.apply(src[2 * i], src[2 * i + 1])
                val dx = p[0] - dst[2 * i]; val dy = p[1] - dst[2 * i + 1]
                if (dx * dx + dy * dy <= th2) { mask[i] = true; c++ }
            }
            if (c < minInliers.coerceAtMost(n)) break
            bestMask = mask
            bestCount = c
        }
        return RansacResult(model, bestMask, bestCount)
    }

    private fun pickDistinct(rnd: Random, n: Int, k: Int): IntArray {
        val out = IntArray(k)
        var count = 0
        while (count < k) {
            val c = rnd.nextInt(n)
            var dup = false
            for (j in 0 until count) if (out[j] == c) { dup = true; break }
            if (!dup) out[count++] = c
        }
        return out
    }

    /** Rejects samples where three of four points are (nearly) collinear. */
    private fun isDegenerate(src: DoubleArray, s: IntArray): Boolean {
        fun area(a: Int, b: Int, c: Int): Double {
            val ax = src[2 * s[a]]; val ay = src[2 * s[a] + 1]
            val bx = src[2 * s[b]]; val by = src[2 * s[b] + 1]
            val cx = src[2 * s[c]]; val cy = src[2 * s[c] + 1]
            return abs((bx - ax) * (cy - ay) - (by - ay) * (cx - ax)) * 0.5
        }
        return area(0, 1, 2) < 4.0 || area(0, 1, 3) < 4.0 || area(0, 2, 3) < 4.0 || area(1, 2, 3) < 4.0
    }

    /** Returns [cx, cy, scale] so that (p - c) * scale has mean distance sqrt(2). */
    private fun normalizationOf(pts: DoubleArray, idx: IntArray): DoubleArray {
        var cx = 0.0; var cy = 0.0
        for (i in idx) { cx += pts[2 * i]; cy += pts[2 * i + 1] }
        cx /= idx.size; cy /= idx.size
        var md = 0.0
        for (i in idx) md += hypot(pts[2 * i] - cx, pts[2 * i + 1] - cy)
        md /= idx.size
        val s = if (md < 1e-9) 1.0 else sqrt(2.0) / md
        return doubleArrayOf(cx, cy, s)
    }

    private fun accumulate(ata: Array<DoubleArray>, atb: DoubleArray, row: DoubleArray, rhs: Double) {
        for (i in 0 until 8) {
            atb[i] += row[i] * rhs
            for (j in 0 until 8) ata[i][j] += row[i] * row[j]
        }
    }

    private fun mul3(a: DoubleArray, b: DoubleArray): DoubleArray {
        val r = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) {
            var s = 0.0
            for (k in 0..2) s += a[i * 3 + k] * b[k * 3 + j]
            r[i * 3 + j] = s
        }
        return r
    }

    /** Gaussian elimination with partial pivoting; returns null if singular. */
    fun solveLinear(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { i -> DoubleArray(n + 1) { j -> if (j < n) a[i][j] else b[i] } }
        for (col in 0 until n) {
            var piv = col
            for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
            if (abs(m[piv][col]) < 1e-12) return null
            val tmp = m[col]; m[col] = m[piv]; m[piv] = tmp
            for (r in col + 1 until n) {
                val f = m[r][col] / m[col][col]
                if (f == 0.0) continue
                for (c in col until n + 1) m[r][c] -= f * m[col][c]
            }
        }
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var s = m[i][n]
            for (j in i + 1 until n) s -= m[i][j] * x[j]
            x[i] = s / m[i][i]
        }
        return x
    }
}
