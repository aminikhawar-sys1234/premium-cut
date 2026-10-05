package com.ahstudio.animation.shape

import com.ahstudio.animation.curves.Bezier2D
import com.ahstudio.animation.easing.Easing
import com.ahstudio.animation.easing.EasingType
import com.ahstudio.animation.math.Mat3
import com.ahstudio.animation.math.Vec2
import kotlin.math.*

/** Vertex with tangent handles RELATIVE to the vertex (After Effects / Lottie convention). */
data class PathVertex(val p: Vec2, val inT: Vec2 = Vec2.ZERO, val outT: Vec2 = Vec2.ZERO)

/**
 * Cubic-Bezier path (shape layer path). Immutable. Segment i goes vertices[i] -> vertices[i+1]
 * (and last -> first when [closed]).
 */
private const val ARC_N = 256

class BezierPath(val vertices: List<PathVertex>, val closed: Boolean = true) {
    val segmentCount: Int get() = if (vertices.size < 2) 0 else if (closed) vertices.size else vertices.size - 1

    private class Seg(val p0: Vec2, val p1: Vec2, val p2: Vec2, val p3: Vec2)
    private fun seg(i: Int): Seg {
        val a = vertices[i]; val b = vertices[(i + 1) % vertices.size]
        return Seg(a.p, a.p + a.outT, b.p + b.inT, b.p)
    }

    fun transform(m: Mat3): BezierPath = BezierPath(vertices.map { v ->
        val p = m.transform(v.p)
        val o = m.transform(v.p + v.outT) - p
        val i = m.transform(v.p + v.inT) - p
        PathVertex(p, i, o)
    }, closed)

    /** Polyline approximation; [tolerance] is the max flatness error in path units. */
    fun flatten(tolerance: Double = 0.25): List<Vec2> {
        if (vertices.isEmpty()) return emptyList()
        val out = ArrayList<Vec2>()
        out.add(vertices[0].p)
        for (i in 0 until segmentCount) {
            val s = seg(i)
            val approxLen = (s.p1 - s.p0).length() + (s.p2 - s.p1).length() + (s.p3 - s.p2).length()
            val n = if (approxLen < 1e-9) 1 else ceil(sqrt(approxLen / max(tolerance, 1e-3)) * 1.5).toInt().coerceIn(2, 64)
            for (k in 1..n) out.add(Bezier2D.sample(s.p0, s.p1, s.p2, s.p3, k.toDouble() / n))
        }
        if (closed && out.size > 1 && (out.last() - out.first()).length() < 1e-9) out.removeAt(out.size - 1)
        return out
    }

    private val segLengths: DoubleArray by lazy {
        DoubleArray(segmentCount) { i ->
            val s = seg(i); var len = 0.0; var prev = s.p0
            for (k in 1..ARC_N) { val p = Bezier2D.sample(s.p0, s.p1, s.p2, s.p3, k / ARC_N.toDouble()); len += (p - prev).length(); prev = p }
            len
        }
    }
    val length: Double get() = segLengths.sum()

    /** Parameter t on segment [i] at arc-length fraction [f] of that segment (32-sample table). */
    private fun tAtLength(s: Seg, segLen: Double, dist: Double): Double {
        if (segLen < 1e-12) return 0.0
        var acc = 0.0; var prev = s.p0
        for (k in 1..ARC_N) {
            val p = Bezier2D.sample(s.p0, s.p1, s.p2, s.p3, k / ARC_N.toDouble())
            val d = (p - prev).length()
            if (acc + d >= dist) return ((k - 1) + if (d < 1e-12) 0.0 else (dist - acc) / d) / ARC_N
            acc += d; prev = p
        }
        return 1.0
    }

    fun pointAt(distance: Double): Vec2 {
        if (vertices.isEmpty()) return Vec2.ZERO
        if (segmentCount == 0) return vertices[0].p
        var d = distance.coerceIn(0.0, length)
        for (i in 0 until segmentCount) {
            if (d <= segLengths[i] || i == segmentCount - 1) {
                val s = seg(i); val t = tAtLength(s, segLengths[i], d.coerceAtMost(segLengths[i]))
                return Bezier2D.sample(s.p0, s.p1, s.p2, s.p3, t)
            }
            d -= segLengths[i]
        }
        return vertices.last().p
    }

    fun tangentAt(distance: Double): Vec2 {
        if (segmentCount == 0) return Vec2(1.0, 0.0)
        var d = distance.coerceIn(0.0, length)
        for (i in 0 until segmentCount) {
            if (d <= segLengths[i] || i == segmentCount - 1) {
                val s = seg(i); val t = tAtLength(s, segLengths[i], d.coerceAtMost(segLengths[i]))
                val der = Bezier2D.derivative(s.p0, s.p1, s.p2, s.p3, t)
                return if (der.length() < 1e-9) (s.p3 - s.p0).normalized() else der.normalized()
            }
            d -= segLengths[i]
        }
        return Vec2(1.0, 0.0)
    }

    /** Axis-aligned bounds of the flattened outline: (minX, minY, maxX, maxY). */
    fun bounds(): DoubleArray {
        val pts = flatten()
        if (pts.isEmpty()) return doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        return doubleArrayOf(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
    }

    /** Returns an equivalent path with exactly [n] vertices (n >= current), splitting the longest segments (de Casteljau). */
    fun withVertexCount(n: Int): BezierPath {
        if (n <= vertices.size || vertices.size < 2) return this
        // split segments proportional to their length so detail is spread evenly
        var cur = this
        while (cur.vertices.size < n) {
            var best = 0; var bestLen = -1.0
            for (i in 0 until cur.segmentCount) if (cur.segLengths[i] > bestLen) { bestLen = cur.segLengths[i]; best = i }
            cur = cur.splitSegment(best, 0.5)
        }
        return cur
    }

    /** Splits segment [i] at parameter [t]; the new vertex sits between i and i+1. */
    fun splitSegment(i: Int, t: Double): BezierPath {
        val s = seg(i)
        val p01 = s.p0.lerp(s.p1, t); val p12 = s.p1.lerp(s.p2, t); val p23 = s.p2.lerp(s.p3, t)
        val p012 = p01.lerp(p12, t); val p123 = p12.lerp(p23, t); val mid = p012.lerp(p123, t)
        val a = vertices[i]; val bIdx = (i + 1) % vertices.size; val b = vertices[bIdx]
        val newA = a.copy(outT = p01 - a.p)
        val newMid = PathVertex(mid, p012 - mid, p123 - mid)
        val newB = b.copy(inT = p23 - b.p)
        val list = ArrayList(vertices)
        list[i] = newA; list[bIdx] = newB
        list.add(i + 1, newMid)
        // bIdx shifts by one when it wrapped to 0 (closing segment): re-fix
        if (bIdx == 0) { list[0] = newB }
        return BezierPath(list, closed)
    }

    /** Re-index so vertex [shift] becomes the first (closed paths only). */
    fun rotated(shift: Int): BezierPath {
        if (!closed || vertices.isEmpty()) return this
        val k = ((shift % vertices.size) + vertices.size) % vertices.size
        return BezierPath(vertices.drop(k) + vertices.take(k), true)
    }

    fun reversed(): BezierPath = BezierPath(vertices.reversed().map { PathVertex(it.p, it.outT, it.inT) }, closed)

    /**
     * Sub-path between arc-length fractions [start]..[end] (AE "Trim Paths"). With [offset] (fraction, wraps) a closed
     * path may yield two open pieces. Returns open paths.
     */
    fun trim(start: Double, end: Double, offset: Double = 0.0): List<BezierPath> {
        if (segmentCount == 0) return emptyList()
        var s = start.coerceIn(0.0, 1.0) + offset; var e = end.coerceIn(0.0, 1.0) + offset
        if (e < s) { val t = s; s = e; e = t }
        if (e - s <= 1e-9) return emptyList()
        if (e - s >= 1.0 - 1e-9) return listOf(if (closed) closedAsOpen() else this)
        val total = length
        return if (closed) {
            val s0 = ((s % 1.0) + 1.0) % 1.0; val e0 = s0 + (e - s)
            if (e0 <= 1.0) listOfNotNull(extract(s0 * total, e0 * total))
            else listOfNotNull(extract(s0 * total, total), extract(0.0, (e0 - 1.0) * total))
        } else listOfNotNull(extract(s.coerceIn(0.0, 1.0) * total, e.coerceIn(0.0, 1.0) * total))
    }

    private fun closedAsOpen(): BezierPath {
        val first = vertices.first()
        return BezierPath(vertices + PathVertex(first.p, first.inT, Vec2.ZERO), false)
    }

    /** Extracts the part between two arc-length distances as an open path. */
    private fun extract(d0: Double, d1: Double): BezierPath? {
        if (d1 - d0 <= 1e-9) return null
        val out = ArrayList<PathVertex>()
        var acc = 0.0
        for (i in 0 until segmentCount) {
            val len = segLengths[i]; val a0 = acc; val a1 = acc + len; acc = a1
            if (a1 <= d0 || a0 >= d1) continue
            val s = seg(i)
            val ta = if (d0 > a0) tAtLength(s, len, d0 - a0) else 0.0
            val tb = if (d1 < a1) tAtLength(s, len, d1 - a0) else 1.0
            val (q0, q1, q2, q3) = subCurve(s, ta, tb)
            if (out.isEmpty()) out.add(PathVertex(q0, Vec2.ZERO, q1 - q0))
            else out[out.size - 1] = out.last().copy(outT = q1 - out.last().p)
            out.add(PathVertex(q3, q2 - q3, Vec2.ZERO))
        }
        return if (out.size >= 2) BezierPath(out, false) else null
    }

    private data class Quad(val a: Vec2, val b: Vec2, val c: Vec2, val d: Vec2)
    private fun subCurve(s: Seg, t0: Double, t1: Double): Quad {
        fun split(q: Seg, t: Double): Pair<Seg, Seg> {
            val p01 = q.p0.lerp(q.p1, t); val p12 = q.p1.lerp(q.p2, t); val p23 = q.p2.lerp(q.p3, t)
            val p012 = p01.lerp(p12, t); val p123 = p12.lerp(p23, t); val m = p012.lerp(p123, t)
            return Seg(q.p0, p01, p012, m) to Seg(m, p123, p23, q.p3)
        }
        var cur = s
        if (t0 > 0.0) cur = split(cur, t0).second
        val rel = if (t0 >= 1.0) 1.0 else (t1 - t0) / (1.0 - t0)
        if (rel < 1.0) cur = split(cur, rel).first
        return Quad(cur.p0, cur.p1, cur.p2, cur.p3)
    }

    override fun equals(other: Any?) = other is BezierPath && closed == other.closed && vertices == other.vertices
    override fun hashCode() = vertices.hashCode() * 31 + closed.hashCode()
}

/** Shape primitives (AE rectangle / ellipse / polystar tools). Centered at the origin unless [center] given. */
object Shapes {
    private const val K = 0.5522847498307936          // circle->cubic constant

    fun line(a: Vec2, b: Vec2) = BezierPath(listOf(PathVertex(a), PathVertex(b)), false)

    fun ellipse(rx: Double, ry: Double, center: Vec2 = Vec2.ZERO) = BezierPath(listOf(
        PathVertex(center + Vec2(0.0, -ry), Vec2(-rx * K, 0.0), Vec2(rx * K, 0.0)),
        PathVertex(center + Vec2(rx, 0.0), Vec2(0.0, -ry * K), Vec2(0.0, ry * K)),
        PathVertex(center + Vec2(0.0, ry), Vec2(rx * K, 0.0), Vec2(-rx * K, 0.0)),
        PathVertex(center + Vec2(-rx, 0.0), Vec2(0.0, ry * K), Vec2(0.0, -ry * K))), true)

    fun rect(w: Double, h: Double, roundness: Double = 0.0, center: Vec2 = Vec2.ZERO): BezierPath {
        val hw = w / 2; val hh = h / 2
        val r = roundness.coerceIn(0.0, min(hw, hh))
        if (r <= 1e-9) return BezierPath(listOf(
            PathVertex(center + Vec2(-hw, -hh)), PathVertex(center + Vec2(hw, -hh)),
            PathVertex(center + Vec2(hw, hh)), PathVertex(center + Vec2(-hw, hh))), true)
        val k = r * (1 - K)
        fun v(x: Double, y: Double, ix: Double, iy: Double, ox: Double, oy: Double) =
            PathVertex(center + Vec2(x, y), Vec2(ix, iy), Vec2(ox, oy))
        return BezierPath(listOf(
            v(-hw + r, -hh, -k, 0.0, 0.0, 0.0), v(hw - r, -hh, 0.0, 0.0, k, 0.0),
            v(hw, -hh + r, 0.0, -k, 0.0, 0.0), v(hw, hh - r, 0.0, 0.0, 0.0, k),
            v(hw - r, hh, k, 0.0, 0.0, 0.0), v(-hw + r, hh, 0.0, 0.0, -k, 0.0),
            v(-hw, hh - r, 0.0, k, 0.0, 0.0), v(-hw, -hh + r, 0.0, 0.0, 0.0, -k)), true)
    }

    fun polygon(sides: Int, radius: Double, rotationDeg: Double = 0.0, center: Vec2 = Vec2.ZERO): BezierPath {
        val n = sides.coerceAtLeast(3)
        return BezierPath((0 until n).map { i ->
            val a = Math.toRadians(rotationDeg - 90.0) + 2 * PI * i / n
            PathVertex(center + Vec2(cos(a) * radius, sin(a) * radius))
        }, true)
    }

    fun star(points: Int, outerR: Double, innerR: Double, rotationDeg: Double = 0.0, center: Vec2 = Vec2.ZERO): BezierPath {
        val n = points.coerceAtLeast(3)
        return BezierPath((0 until n * 2).map { i ->
            val a = Math.toRadians(rotationDeg - 90.0) + PI * i / n
            val r = if (i % 2 == 0) outerR else innerR
            PathVertex(center + Vec2(cos(a) * r, sin(a) * r))
        }, true)
    }
}

/** Shape morphing (AE path keyframes / Blender shape keys). */
object PathMorph {
    /** Interpolates two paths. Vertex counts are equalised and the start vertex aligned (closed paths) to minimise travel. */
    fun morph(a: BezierPath, b: BezierPath, t: Double): BezierPath {
        val (pa, pb) = match(a, b)
        val u = t.coerceIn(0.0, 1.0)
        return BezierPath(pa.vertices.indices.map { i ->
            val x = pa.vertices[i]; val y = pb.vertices[i]
            PathVertex(x.p.lerp(y.p, u), x.inT.lerp(y.inT, u), x.outT.lerp(y.outT, u))
        }, pa.closed && pb.closed)
    }

    fun match(a: BezierPath, b: BezierPath): Pair<BezierPath, BezierPath> {
        val n = max(a.vertices.size, b.vertices.size)
        val pa = a.withVertexCount(n); var pb = b.withVertexCount(n)
        if (pa.closed && pb.closed && n > 0) {
            var best = 0; var bestCost = Double.MAX_VALUE
            for (s in 0 until n) {
                var cost = 0.0
                for (i in 0 until n) { val d = pa.vertices[i].p - pb.vertices[(i + s) % n].p; cost += d.x * d.x + d.y * d.y }
                if (cost < bestCost) { bestCost = cost; best = s }
            }
            pb = pb.rotated(best)
        }
        return pa to pb
    }
}

/** Keyframed path (AE "Path" property / mask shape). Evaluates by morphing adjacent keys with the segment's easing. */
class PathTrack(keys: List<Key>) {
    fun keyList(): List<Key> = keys
    data class Key(val timeMs: Long, val path: BezierPath, val easing: EasingType = EasingType.LINEAR, val hold: Boolean = false)
    private val keys = keys.sortedBy { it.timeMs }
    init { require(this.keys.isNotEmpty()) { "PathTrack needs at least one key" } }
    private val matched = HashMap<Int, Pair<BezierPath, BezierPath>>()

    fun valueAt(timeMs: Long): BezierPath {
        if (timeMs <= keys.first().timeMs) return keys.first().path
        if (timeMs >= keys.last().timeMs) return keys.last().path
        var i = 0
        while (i < keys.size - 2 && keys[i + 1].timeMs <= timeMs) i++
        val a = keys[i]; val b = keys[i + 1]
        if (a.hold) return a.path
        val u = (timeMs - a.timeMs).toDouble() / (b.timeMs - a.timeMs)
        val (pa, pb) = synchronized(matched) { matched.getOrPut(i) { PathMorph.match(a.path, b.path) } }
        return PathMorph.morph(pa, pb, Easing.apply(a.easing, u))
    }
}
