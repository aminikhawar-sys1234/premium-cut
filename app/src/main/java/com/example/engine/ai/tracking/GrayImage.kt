package com.example.engine.ai.tracking

import kotlin.math.floor

/**
 * Float grayscale image (0..255) with no Android dependencies, so the whole tracking core
 * runs and is unit-tested on the plain JVM.
 */
class GrayImage(val width: Int, val height: Int, val data: FloatArray) {

    init {
        require(width > 0 && height > 0) { "Image must be non-empty" }
        require(data.size == width * height) { "Data size does not match ${width}x$height" }
    }

    fun at(x: Int, y: Int): Float = data[y * width + x]

    /** Bilinear sample with edge clamping. */
    fun sample(x: Float, y: Float): Float {
        val cx = x.coerceIn(0f, (width - 1).toFloat())
        val cy = y.coerceIn(0f, (height - 1).toFloat())
        val x0 = floor(cx).toInt().coerceAtMost(width - 2).coerceAtLeast(0)
        val y0 = floor(cy).toInt().coerceAtMost(height - 2).coerceAtLeast(0)
        val x1 = if (width > 1) x0 + 1 else x0
        val y1 = if (height > 1) y0 + 1 else y0
        val fx = cx - x0
        val fy = cy - y0
        val a = data[y0 * width + x0]
        val b = data[y0 * width + x1]
        val c = data[y1 * width + x0]
        val d = data[y1 * width + x1]
        return (a * (1f - fx) + b * fx) * (1f - fy) + (c * (1f - fx) + d * fx) * fy
    }

    /** Separable [1 4 6 4 1]/16 blur followed by 2x decimation. */
    fun downsample(): GrayImage {
        val k = floatArrayOf(1f / 16f, 4f / 16f, 6f / 16f, 4f / 16f, 1f / 16f)
        val tmp = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var s = 0f
                for (i in -2..2) {
                    val xx = (x + i).coerceIn(0, width - 1)
                    s += data[y * width + xx] * k[i + 2]
                }
                tmp[y * width + x] = s
            }
        }
        val nw = (width / 2).coerceAtLeast(1)
        val nh = (height / 2).coerceAtLeast(1)
        val out = FloatArray(nw * nh)
        for (y in 0 until nh) {
            for (x in 0 until nw) {
                val sx = (x * 2).coerceAtMost(width - 1)
                val sy = (y * 2)
                var s = 0f
                for (i in -2..2) {
                    val yy = (sy + i).coerceIn(0, height - 1)
                    s += tmp[yy * width + sx] * k[i + 2]
                }
                out[y * nw + x] = s
            }
        }
        return GrayImage(nw, nh, out)
    }

    companion object {
        /** Converts packed ARGB ints (Bitmap.getPixels layout) to luma using Rec.601 weights. */
        fun fromArgb(pixels: IntArray, width: Int, height: Int): GrayImage {
            val out = FloatArray(width * height)
            for (i in out.indices) {
                val p = pixels[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                out[i] = 0.299f * r + 0.587f * g + 0.114f * b
            }
            return GrayImage(width, height, out)
        }
    }
}

/** One pyramid level with precomputed central-difference gradients. */
class PyramidLevel(val image: GrayImage) {
    val gx: GrayImage
    val gy: GrayImage

    init {
        val w = image.width
        val h = image.height
        val dx = FloatArray(w * h)
        val dy = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val xl = (x - 1).coerceAtLeast(0)
                val xr = (x + 1).coerceAtMost(w - 1)
                val yu = (y - 1).coerceAtLeast(0)
                val yd = (y + 1).coerceAtMost(h - 1)
                dx[y * w + x] = (image.data[y * w + xr] - image.data[y * w + xl]) * 0.5f
                dy[y * w + x] = (image.data[yd * w + x] - image.data[yu * w + x]) * 0.5f
            }
        }
        gx = GrayImage(w, h, dx)
        gy = GrayImage(w, h, dy)
    }
}

class ImagePyramid(base: GrayImage, maxLevels: Int) {
    val levels: List<PyramidLevel>

    init {
        val list = ArrayList<PyramidLevel>()
        var cur = base
        list.add(PyramidLevel(cur))
        var l = 1
        while (l < maxLevels && cur.width >= 32 && cur.height >= 32) {
            cur = cur.downsample()
            list.add(PyramidLevel(cur))
            l++
        }
        levels = list
    }

    val base: GrayImage get() = levels[0].image
}
