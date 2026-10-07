package com.example.engine.ai.tracking

import kotlin.math.sqrt

data class Point2(val x: Float, val y: Float)

/**
 * Shi-Tomasi "good features to track": minimum eigenvalue of the windowed structure tensor,
 * non-maximum suppressed with a minimum-distance grid. Optional region mask (polygon).
 */
object FeatureDetector {

    fun detect(
        image: GrayImage,
        maxCorners: Int,
        qualityLevel: Float = 0.01f,
        minDistance: Float = 6f,
        windowRadius: Int = 2,
        border: Int = 6,
        mask: ((Float, Float) -> Boolean)? = null
    ): List<Point2> {
        val w = image.width
        val h = image.height
        if (w < 2 * border + 3 || h < 2 * border + 3) return emptyList()

        val level = PyramidLevel(image)
        val ixx = FloatArray(w * h)
        val ixy = FloatArray(w * h)
        val iyy = FloatArray(w * h)
        for (i in 0 until w * h) {
            val gx = level.gx.data[i]
            val gy = level.gy.data[i]
            ixx[i] = gx * gx
            ixy[i] = gx * gy
            iyy[i] = gy * gy
        }
        val sxx = boxSum(ixx, w, h, windowRadius)
        val sxy = boxSum(ixy, w, h, windowRadius)
        val syy = boxSum(iyy, w, h, windowRadius)

        val score = FloatArray(w * h)
        var maxScore = 0f
        for (y in border until h - border) {
            for (x in border until w - border) {
                val i = y * w + x
                val a = sxx[i]
                val b = sxy[i]
                val c = syy[i]
                val tr = a + c
                val disc = sqrt(((a - c) * (a - c) + 4f * b * b).coerceAtLeast(0f))
                val minEig = 0.5f * (tr - disc)
                score[i] = minEig
                if (minEig > maxScore) maxScore = minEig
            }
        }
        if (maxScore <= 0f) return emptyList()

        val threshold = maxScore * qualityLevel
        val candidates = ArrayList<Int>()
        for (y in border until h - border) {
            for (x in border until w - border) {
                val i = y * w + x
                val s = score[i]
                if (s < threshold) continue
                // 3x3 local maximum
                var isMax = true
                loop@ for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        if (score[(y + dy) * w + (x + dx)] > s) {
                            isMax = false
                            break@loop
                        }
                    }
                }
                if (!isMax) continue
                if (mask != null && !mask(x.toFloat(), y.toFloat())) continue
                candidates.add(i)
            }
        }
        candidates.sortByDescending { score[it] }

        val cell = minDistance.coerceAtLeast(1f)
        val gridW = (w / cell).toInt() + 1
        val gridH = (h / cell).toInt() + 1
        val grid = arrayOfNulls<MutableList<Point2>>(gridW * gridH)
        val out = ArrayList<Point2>()
        val minD2 = minDistance * minDistance

        for (idx in candidates) {
            if (out.size >= maxCorners) break
            val px = (idx % w).toFloat()
            val py = (idx / w).toFloat()
            val gx = (px / cell).toInt()
            val gy = (py / cell).toInt()
            var ok = true
            loop@ for (yy in (gy - 1).coerceAtLeast(0)..(gy + 1).coerceAtMost(gridH - 1)) {
                for (xx in (gx - 1).coerceAtLeast(0)..(gx + 1).coerceAtMost(gridW - 1)) {
                    val cellList = grid[yy * gridW + xx] ?: continue
                    for (q in cellList) {
                        val dx = q.x - px
                        val dy = q.y - py
                        if (dx * dx + dy * dy < minD2) {
                            ok = false
                            break@loop
                        }
                    }
                }
            }
            if (!ok) continue
            val p = Point2(px, py)
            out.add(p)
            val cellIdx = gy * gridW + gx
            val list = grid[cellIdx] ?: ArrayList<Point2>().also { grid[cellIdx] = it }
            list.add(p)
        }
        return out
    }

    /** Box sum over a (2r+1)^2 window using a summed-area table. */
    private fun boxSum(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val integral = DoubleArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0.0
            for (x in 0 until w) {
                rowSum += src[y * w + x]
                integral[(y + 1) * (w + 1) + (x + 1)] = integral[y * (w + 1) + (x + 1)] + rowSum
            }
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val y0 = (y - r).coerceAtLeast(0)
            val y1 = (y + r + 1).coerceAtMost(h)
            for (x in 0 until w) {
                val x0 = (x - r).coerceAtLeast(0)
                val x1 = (x + r + 1).coerceAtMost(w)
                val s = integral[y1 * (w + 1) + x1] - integral[y0 * (w + 1) + x1] -
                    integral[y1 * (w + 1) + x0] + integral[y0 * (w + 1) + x0]
                out[y * w + x] = s.toFloat()
            }
        }
        return out
    }
}
