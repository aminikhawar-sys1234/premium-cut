package com.ahstudio.animation.render

import com.ahstudio.animation.math.Color4
import com.ahstudio.animation.math.Mat3
import com.ahstudio.animation.math.Vec2
import kotlin.math.*

enum class RenderBlend { NORMAL, ADD, MULTIPLY, SCREEN }
enum class FillRule { NON_ZERO, EVEN_ODD }
enum class LineCap { BUTT, ROUND, SQUARE }
enum class LineJoin { MITER, ROUND, BEVEL }

/** Straight-alpha 8-bit frame, row-major ARGB (0xAARRGGBB). */
class Frame(val width: Int, val height: Int, val argb: IntArray) {
    init { require(argb.size == width * height) }
    fun pixel(x: Int, y: Int) = argb[y * width + x]
    /** Raw RGBA bytes (straight alpha), as PNG/GIF encoders want them. */
    fun toRgba(): ByteArray {
        val out = ByteArray(width * height * 4)
        for (i in argb.indices) {
            val p = argb[i]
            out[i * 4] = (p shr 16).toByte(); out[i * 4 + 1] = (p shr 8).toByte(); out[i * 4 + 2] = p.toByte(); out[i * 4 + 3] = (p ushr 24).toByte()
        }
        return out
    }
}

/** Premultiplied float RGBA canvas. */
class Canvas(val w: Int, val h: Int) {
    val px = FloatArray(w * h * 4)
    fun clear() = px.fill(0f)
    fun fill(c: Color4) {
        val a = c.a.toFloat().coerceIn(0f, 1f)
        for (i in 0 until w * h) { px[i * 4] = c.r.toFloat() * a; px[i * 4 + 1] = c.g.toFloat() * a; px[i * 4 + 2] = c.b.toFloat() * a; px[i * 4 + 3] = a }
    }

    /** Composite premultiplied source (r,g,b,a) over pixel index [i]. */
    fun blendPremul(i: Int, r: Float, g: Float, b: Float, a: Float, mode: RenderBlend) {
        if (a <= 0f && mode != RenderBlend.ADD) return
        val o = i * 4
        val dr = px[o]; val dg = px[o + 1]; val db = px[o + 2]; val da = px[o + 3]
        when (mode) {
            RenderBlend.NORMAL -> { val k = 1f - a; px[o] = r + dr * k; px[o + 1] = g + dg * k; px[o + 2] = b + db * k; px[o + 3] = a + da * k }
            RenderBlend.ADD -> { px[o] = min(1f, r + dr); px[o + 1] = min(1f, g + dg); px[o + 2] = min(1f, b + db); px[o + 3] = min(1f, a + da * (1f - a)) }
            RenderBlend.MULTIPLY -> { val k = 1f - a; val kd = 1f - da
                px[o] = r * dr + r * kd + dr * k; px[o + 1] = g * dg + g * kd + dg * k; px[o + 2] = b * db + b * kd + db * k; px[o + 3] = a + da * k }
            RenderBlend.SCREEN -> { val k = 1f - a
                px[o] = r + dr - r * dr; px[o + 1] = g + dg - g * dg; px[o + 2] = b + db - b * db; px[o + 3] = a + da * k }
        }
    }

    /** Composites a whole canvas of the same size. */
    fun composite(src: Canvas, opacity: Float, mode: RenderBlend) {
        require(src.w == w && src.h == h)
        for (i in 0 until w * h) {
            val o = i * 4
            val a = src.px[o + 3] * opacity
            if (a <= 0f) continue
            blendPremul(i, src.px[o] * opacity, src.px[o + 1] * opacity, src.px[o + 2] * opacity, a, mode)
        }
    }

    /** Converts to straight-alpha ARGB. */
    fun toFrame(): Frame {
        val out = IntArray(w * h)
        for (i in 0 until w * h) {
            val o = i * 4; val a = px[o + 3]
            if (a <= 1e-6f) { out[i] = 0; continue }
            val r = (px[o] / a).coerceIn(0f, 1f); val g = (px[o + 1] / a).coerceIn(0f, 1f); val b = (px[o + 2] / a).coerceIn(0f, 1f)
            out[i] = ((a.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 24) or ((r * 255f + 0.5f).toInt() shl 16) or
                ((g * 255f + 0.5f).toInt() shl 8) or (b * 255f + 0.5f).toInt()
        }
        return Frame(w, h, out)
    }

    /** In-place separable box blur x3 (approximates a Gaussian of the given radius in pixels). */
    fun blur(radius: Double) {
        if (radius < 0.3) return
        val boxes = boxSizes(radius, 3)
        val tmp = FloatArray(px.size)
        for (b in boxes) { boxBlurH(px, tmp, (b - 1) / 2); boxBlurV(tmp, px, (b - 1) / 2) }
    }
    private fun boxSizes(sigma: Double, n: Int): IntArray {
        val wIdeal = sqrt(12.0 * sigma * sigma / n + 1)
        var wl = floor(wIdeal).toInt(); if (wl % 2 == 0) wl--
        val wu = wl + 2
        val m = round((12.0 * sigma * sigma - n * wl * wl - 4.0 * n * wl - 3.0 * n) / (-4.0 * wl - 4)).toInt()
        return IntArray(n) { if (it < m) wl.coerceAtLeast(1) else wu }
    }
    private fun boxBlurH(src: FloatArray, dst: FloatArray, r: Int) {
        if (r <= 0) { System.arraycopy(src, 0, dst, 0, src.size); return }
        val inv = 1f / (2 * r + 1)
        for (y in 0 until h) for (c in 0 until 4) {
            var acc = 0f
            for (x in -r..r) acc += src[(y * w + x.coerceIn(0, w - 1)) * 4 + c]
            for (x in 0 until w) {
                dst[(y * w + x) * 4 + c] = acc * inv
                acc += src[(y * w + (x + r + 1).coerceAtMost(w - 1)) * 4 + c] - src[(y * w + (x - r).coerceAtLeast(0)) * 4 + c]
            }
        }
    }
    private fun boxBlurV(src: FloatArray, dst: FloatArray, r: Int) {
        if (r <= 0) { System.arraycopy(src, 0, dst, 0, src.size); return }
        val inv = 1f / (2 * r + 1)
        for (x in 0 until w) for (c in 0 until 4) {
            var acc = 0f
            for (y in -r..r) acc += src[(y.coerceIn(0, h - 1) * w + x) * 4 + c]
            for (y in 0 until h) {
                dst[(y * w + x) * 4 + c] = acc * inv
                acc += src[((y + r + 1).coerceAtMost(h - 1) * w + x) * 4 + c] - src[((y - r).coerceAtLeast(0) * w + x) * 4 + c]
            }
        }
    }
}

/**
 * Exact-area anti-aliased polygon rasterizer (signed-area accumulation, as in font-rs / stb_truetype v2).
 * Returns per-pixel coverage in [0,1], row stride = w.
 */
object Raster {
    fun coverage(polys: List<List<Vec2>>, w: Int, h: Int, rule: FillRule = FillRule.NON_ZERO): FloatArray {
        val stride = w + 2
        val acc = FloatArray(stride * h + 2)
        for (poly in polys) {
            val n = poly.size
            if (n < 2) continue
            for (i in 0 until n) addEdge(acc, stride, w, h, poly[i], poly[(i + 1) % n])
        }
        val cov = FloatArray(w * h)
        for (y in 0 until h) {
            var s = 0f
            for (x in 0 until w) {
                s += acc[y * stride + x]
                val a = abs(s)
                cov[y * w + x] = if (rule == FillRule.NON_ZERO) min(a, 1f) else { val m = a % 2f; if (m > 1f) 2f - m else m }
            }
        }
        return cov
    }

    private fun addEdge(acc: FloatArray, stride: Int, w: Int, h: Int, a: Vec2, b: Vec2) {
        if (a.y == b.y) return
        var x0 = a.x; var y0 = a.y; var x1 = b.x; var y1 = b.y
        var dir = 1f
        if (y0 > y1) { dir = -1f; val tx = x0; x0 = x1; x1 = tx; val ty = y0; y0 = y1; y1 = ty }
        if (y1 <= 0.0 || y0 >= h) return
        val dxdy = (x1 - x0) / (y1 - y0)
        // clip vertically
        if (y0 < 0.0) { x0 -= y0 * dxdy; y0 = 0.0 }
        if (y1 > h) { x1 -= (y1 - h) * dxdy; y1 = h.toDouble() }
        // split at x = 0 and x = w, clamping outside parts onto the boundary (they still contribute winding)
        val pieces = ArrayList<DoubleArray>(3)
        splitX(x0, y0, x1, y1, w.toDouble(), pieces)
        for (p in pieces) addClipped(acc, stride, p[0], p[1], p[2], p[3], dir)
    }

    private fun splitX(x0: Double, y0: Double, x1: Double, y1: Double, w: Double, out: MutableList<DoubleArray>) {
        // cut points where the segment crosses x=0 or x=w
        val ts = ArrayList<Double>(4); ts.add(0.0)
        val dx = x1 - x0
        if (abs(dx) > 1e-12) {
            val tz = (0.0 - x0) / dx; val tw = (w - x0) / dx
            for (t in listOf(tz, tw).sorted()) if (t > 1e-12 && t < 1 - 1e-12) ts.add(t)
        }
        ts.add(1.0)
        for (i in 0 until ts.size - 1) {
            val ta = ts[i]; val tb = ts[i + 1]
            val xa = x0 + dx * ta; val xb = x0 + dx * tb
            val ya = y0 + (y1 - y0) * ta; val yb = y0 + (y1 - y0) * tb
            val mid = (xa + xb) / 2
            if (mid < 0.0) out.add(doubleArrayOf(0.0, ya, 0.0, yb))
            else if (mid > w) out.add(doubleArrayOf(w, ya, w, yb))
            else out.add(doubleArrayOf(xa.coerceIn(0.0, w), ya, xb.coerceIn(0.0, w), yb))
        }
    }

    private fun addClipped(acc: FloatArray, stride: Int, ax: Double, ay: Double, bx: Double, by: Double, dir: Float) {
        if (by <= ay) return
        val dxdy = (bx - ax) / (by - ay)
        var x = ax
        val yStart = floor(ay).toInt(); val yEnd = ceil(by).toInt()
        for (y in yStart until yEnd) {
            val rowStart = y * stride
            val dy = min((y + 1).toDouble(), by) - max(y.toDouble(), ay)
            val xnext = x + dxdy * dy
            val d = (dy * dir).toFloat()
            val xl = min(x, xnext); val xr = max(x, xnext)
            val x0floor = floor(xl); val x0i = x0floor.toInt()
            val x1ceil = ceil(xr); val x1i = x1ceil.toInt()
            if (x1i <= x0i + 1) {
                val xmf = (0.5 * (x + xnext) - x0floor).toFloat()
                acc[rowStart + x0i] += d - d * xmf
                acc[rowStart + x0i + 1] += d * xmf
            } else {
                val s = (1.0 / (xr - xl)).toFloat()
                val x0f = (xl - x0floor).toFloat()
                val a0 = 0.5f * s * (1f - x0f) * (1f - x0f)
                val x1f = (xr - x1ceil + 1.0).toFloat()
                val am = 0.5f * s * x1f * x1f
                acc[rowStart + x0i] += d * a0
                if (x1i == x0i + 2) {
                    acc[rowStart + x0i + 1] += d * (1f - a0 - am)
                } else {
                    val a1 = s * (1.5f - x0f)
                    acc[rowStart + x0i + 1] += d * (a1 - a0)
                    for (xi in x0i + 2 until x1i - 1) acc[rowStart + xi] += d * s
                    val a2 = a1 + (x1i - x0i - 3) * s
                    acc[rowStart + x1i - 1] += d * (1f - a2 - am)
                }
                acc[rowStart + x1i] += d * am
            }
            x = xnext
        }
    }
}

/** Converts polylines into fill polygons for strokes (union via non-zero winding of consistently oriented pieces). */
object Stroker {
    fun stroke(
        polylines: List<Pair<List<Vec2>, Boolean>>, width: Double, cap: LineCap, join: LineJoin,
        miterLimit: Double = 4.0, dash: List<Double> = emptyList(), dashOffset: Double = 0.0
    ): List<List<Vec2>> {
        val hw = width / 2
        if (hw <= 0.0) return emptyList()
        val lines = if (dash.size >= 2 && dash.all { it >= 0.0 } && dash.sum() > 1e-6) polylines.flatMap { dashed(it.first, it.second, dash, dashOffset) } else polylines
        val out = ArrayList<List<Vec2>>()
        for ((pts0, closed) in lines) {
            val pts = dedupe(pts0, closed)
            if (pts.size == 1) { if (cap == LineCap.ROUND) out.add(circle(pts[0], hw)) else if (cap == LineCap.SQUARE) out.add(square(pts[0], hw)); continue }
            if (pts.size < 2) continue
            val n = pts.size; val segs = if (closed) n else n - 1
            for (i in 0 until segs) {
                val a = pts[i]; val b = pts[(i + 1) % n]
                val d = (b - a).normalized(); val nrm = Vec2(-d.y, d.x) * hw
                var a2 = a; var b2 = b
                if (!closed && cap == LineCap.SQUARE) { if (i == 0) a2 = a - d * hw; if (i == segs - 1) b2 = b + d * hw }
                out.add(orient(listOf(a2 + nrm, b2 + nrm, b2 - nrm, a2 - nrm)))
            }
            val joinRange = if (closed) 0 until n else 1 until n - 1
            for (i in joinRange) addJoin(out, pts[(i - 1 + n) % n], pts[i], pts[(i + 1) % n], hw, join, miterLimit)
            if (!closed && cap == LineCap.ROUND) { out.add(circle(pts.first(), hw)); out.add(circle(pts.last(), hw)) }
        }
        return out
    }

    private fun dedupe(p: List<Vec2>, closed: Boolean): List<Vec2> {
        val o = ArrayList<Vec2>(p.size)
        for (v in p) if (o.isEmpty() || (v - o.last()).length() > 1e-9) o.add(v)
        if (closed && o.size > 1 && (o.first() - o.last()).length() <= 1e-9) o.removeAt(o.size - 1)
        return o
    }

    private fun addJoin(out: MutableList<List<Vec2>>, p: Vec2, c: Vec2, n: Vec2, hw: Double, join: LineJoin, miterLimit: Double) {
        val d0 = (c - p).normalized(); val d1 = (n - c).normalized()
        val cross = d0.x * d1.y - d0.y * d1.x
        if (abs(cross) < 1e-9 && d0.dot(d1) > 0) return                        // collinear
        if (join == LineJoin.ROUND) { out.add(circle(c, hw)); return }
        val sgn = if (cross > 0) -1.0 else 1.0                                  // outer side
        val n0 = Vec2(-d0.y, d0.x) * (hw * sgn); val n1 = Vec2(-d1.y, d1.x) * (hw * sgn)
        val a = c + n0; val b = c + n1
        if (join == LineJoin.MITER) {
            val cosHalf = sqrt(((1 + d0.dot(d1)) / 2).coerceAtLeast(0.0))      // cos of half the turning angle
            if (cosHalf > 1e-6 && 1.0 / cosHalf <= miterLimit) {
                val m = (n0 + n1)
                val ml = m.length()
                if (ml > 1e-9) { val tip = c + m * (hw / cosHalf / ml); out.add(orient(listOf(c, a, tip, b))); return }
            }
        }
        out.add(orient(listOf(c, a, b)))                                         // bevel
    }

    private fun orient(poly: List<Vec2>): List<Vec2> {
        var area = 0.0
        for (i in poly.indices) { val a = poly[i]; val b = poly[(i + 1) % poly.size]; area += a.x * b.y - b.x * a.y }
        return if (area < 0) poly.reversed() else poly
    }
    private fun circle(c: Vec2, r: Double): List<Vec2> {
        val n = (PI / acos((1 - 0.1 / max(r, 0.2)).coerceIn(-1.0, 1.0))).toInt().coerceIn(8, 64)
        return List(n) { Vec2(c.x + cos(2 * PI * it / n) * r, c.y + sin(2 * PI * it / n) * r) }
    }
    private fun square(c: Vec2, r: Double) = listOf(Vec2(c.x - r, c.y - r), Vec2(c.x + r, c.y - r), Vec2(c.x + r, c.y + r), Vec2(c.x - r, c.y + r))

    private fun dashed(pts: List<Vec2>, closed: Boolean, dash: List<Double>, offset: Double): List<Pair<List<Vec2>, Boolean>> {
        val path = if (closed) pts + pts.first() else pts
        val out = ArrayList<Pair<List<Vec2>, Boolean>>()
        val period = dash.sum() * (if (dash.size % 2 == 1) 2 else 1)
        var idx = 0; var remain = dash[0]; var on = true
        var off = ((offset % period) + period) % period
        while (off > 0) { if (off >= remain) { off -= remain; idx = (idx + 1) % dash.size; remain = dash[idx]; on = !on } else { remain -= off; off = 0.0 } }
        var cur = ArrayList<Vec2>()
        if (on) cur.add(path[0])
        for (i in 0 until path.size - 1) {
            var a = path[i]; val b = path[i + 1]
            var segLen = (b - a).length()
            if (segLen < 1e-12) continue
            val dir = (b - a) / segLen
            while (segLen > 1e-12) {
                val step = min(segLen, remain)
                val e = a + dir * step
                if (on) { if (cur.isEmpty()) cur.add(a); cur.add(e) }
                segLen -= step; remain -= step; a = e
                if (remain <= 1e-12) {
                    if (on && cur.size >= 2) out.add(cur to false)
                    cur = ArrayList()
                    idx = (idx + 1) % dash.size; remain = max(dash[idx], 1e-6); on = !on
                    if (on) cur.add(a)
                }
            }
        }
        if (on && cur.size >= 2) out.add(cur to false)
        return out
    }
}

/** Gradient / solid paint evaluated in layer-local coordinates. */
sealed class Paint {
    data class Solid(val color: Color4) : Paint()
    data class Stop(val pos: Double, val color: Color4)
    data class Linear(val from: Vec2, val to: Vec2, val stops: List<Stop>) : Paint()
    data class Radial(val center: Vec2, val radius: Double, val stops: List<Stop>) : Paint()

    internal fun colorAt(p: Vec2): Color4 = when (this) {
        is Solid -> color
        is Linear -> { val d = to - from; val l2 = d.dot(d); ramp(stops, if (l2 < 1e-12) 0.0 else (p - from).dot(d) / l2) }
        is Radial -> ramp(stops, if (radius < 1e-12) 1.0 else (p - center).length() / radius)
    }
    companion object {
        fun ramp(stops: List<Stop>, t0: Double): Color4 {
            if (stops.isEmpty()) return Color4(0.0, 0.0, 0.0, 0.0)
            val t = t0.coerceIn(0.0, 1.0)
            if (t <= stops.first().pos) return stops.first().color
            for (i in 0 until stops.size - 1) {
                val a = stops[i]; val b = stops[i + 1]
                if (t <= b.pos) return a.color.lerp(b.color, if (b.pos - a.pos < 1e-12) 1.0 else (t - a.pos) / (b.pos - a.pos))
            }
            return stops.last().color
        }
    }
}

/** Fills a coverage mask onto a canvas with a paint; [inverse] maps canvas pixels back to paint space. */
internal fun Canvas.fillCoverage(cov: FloatArray, paint: Paint, inverse: Mat3, opacity: Double, mode: RenderBlend) {
    val solid = paint as? Paint.Solid
    for (y in 0 until h) for (x in 0 until w) {
        val c = cov[y * w + x]
        if (c <= 0f) continue
        val col = if (solid != null) solid.color else paint.colorAt(inverse.transform(x + 0.5, y + 0.5))
        val a = (col.a * opacity).toFloat().coerceIn(0f, 1f) * c
        blendPremul(y * w + x, col.r.toFloat() * a, col.g.toFloat() * a, col.b.toFloat() * a, a, mode)
    }
}
