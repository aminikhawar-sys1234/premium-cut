package com.ute.text3d

import android.graphics.Path
import android.graphics.PathMeasure
import com.ute.core.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Glyph outline → extruded, beveled 3D mesh (glyph-space px, y-up, z toward the viewer).
 *
 * - Contours are classified into outers and holes by nesting depth ("O", "A", "e", "®" keep their counters).
 * - Faces are triangulated per outer with its holes bridged in; front +Z, back −Z.
 * - Sides are swept along a rounded bevel profile that is carved INTO the solid
 *   (face inset by the bevel width, walls reach the true outline), with smooth vertex normals
 *   on curves and hard normals at sharp corners.
 */
class ExtrusionBuilder {

    data class Mesh(val positions: FloatArray, val normals: FloatArray, val triangleCount: Int)

    fun flattenPath(path: Path, tolerancePx: Float = 1f): List<List<Vec2>> {
        val contours = ArrayList<List<Vec2>>()
        val measure = PathMeasure(path, false)
        val pos = FloatArray(2)
        do {
            val len = measure.length
            if (len < tolerancePx) continue
            val steps = (len / tolerancePx).toInt().coerceIn(8, 1024)
            val raw = ArrayList<Vec2>(steps)
            for (i in 0 until steps) {            // exclusive of i == steps: closed contours end where they began
                measure.getPosTan(i.toFloat() / steps * len, pos, null)
                raw.add(Vec2(pos[0], -pos[1]))    // font space is y-down, GL is y-up
            }
            // Drop consecutive duplicates (degenerate edges break normals and ear clipping).
            val pts = ArrayList<Vec2>(raw.size)
            for (p in raw) {
                val last = pts.lastOrNull()
                if (last == null || hypot((p.x - last.x).toDouble(), (p.y - last.y).toDouble()) > 1e-3) pts.add(p)
            }
            if (pts.size > 1 && hypot((pts.first().x - pts.last().x).toDouble(), (pts.first().y - pts.last().y).toDouble()) <= 1e-3) {
                pts.removeAt(pts.size - 1)
            }
            if (pts.size >= 3) contours.add(pts)
        } while (measure.nextContour())
        return contours
    }

    // ---------------------------------------------------------------------------------------------

    private class FloatList(cap: Int = 4096) {
        var a = FloatArray(cap); var n = 0
        fun add(v: Float) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v }
        fun toArray() = a.copyOf(n)
    }

    /** One classified contour with precomputed per-vertex offset data. */
    private class Ring(val pts: List<Vec2>, val isHole: Boolean) {
        val n = pts.size
        val edgeN = Array(n) { floatArrayOf(0f, 0f) }   // outward-of-solid normal per edge i -> i+1
        val vertN = Array(n) { floatArrayOf(0f, 0f) }   // smooth (or hard) normal for side shading, per vertex
        val sharp = BooleanArray(n)                      // true: vertex i is a hard corner
        val offDir = Array(n) { floatArrayOf(0f, 0f) }  // miter-scaled direction used for insetting
    }

    fun build(path: Path, depthPx: Float, bevelWidthPx: Float, bevelSteps: Int): Mesh {
        val contours = flattenPath(path)
        if (contours.isEmpty() || depthPx <= 0f) return Mesh(FloatArray(0), FloatArray(0), 0)

        val rings = classify(contours)
        val depth = depthPx
        val half = depth / 2f
        val b = bevelWidthPx.coerceIn(0f, half)
        val segs = if (b > 0f) (bevelSteps.coerceIn(0, 4) + 1) else 0

        // Profile rings, front → back: inset, z, side-normal horizontal & z components.
        class P(val inset: Float, val z: Float, val nh: Float, val nz: Float)
        val profile = ArrayList<P>()
        if (segs == 0) {
            profile.add(P(0f, half, 1f, 0f)); profile.add(P(0f, -half, 1f, 0f))
        } else {
            for (k in 0..segs) {
                val th = k.toFloat() / segs * (PI.toFloat() / 2f)
                profile.add(P(b * (1f - sin(th)), half - b + b * cos(th), sin(th), cos(th)))
            }
            for (k in segs downTo 0) {
                val th = k.toFloat() / segs * (PI.toFloat() / 2f)
                profile.add(P(b * (1f - sin(th)), -(half - b + b * cos(th)), sin(th), -cos(th)))
            }
        }

        val pos = FloatList(); val nor = FloatList()
        fun push(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float) {
            pos.add(x); pos.add(y); pos.add(z); nor.add(nx); nor.add(ny); nor.add(nz)
        }

        // ---- Faces ----
        val faceInset = b
        for (outer in rings.filter { !it.isHole }) {
            val holes = rings.filter { it.isHole && parentOf(it, rings) === outer }
            fun insetPts(r: Ring) = List(r.n) { i ->
                Vec2(r.pts[i].x - r.offDir[i][0] * faceInset, r.pts[i].y - r.offDir[i][1] * faceInset)
            }
            val outerPts = insetPts(outer)
            val holePts = holes.map { insetPts(it) }
            val combined = ArrayList<Vec2>(outerPts); holePts.forEach { combined.addAll(it) }
            val idx = Triangulator.triangulate(outerPts, holePts)
            var t = 0
            while (t + 2 < idx.size) {
                val a = combined[idx[t]]; val bb = combined[idx[t + 1]]; val c = combined[idx[t + 2]]
                push(a.x, a.y, half, 0f, 0f, 1f); push(bb.x, bb.y, half, 0f, 0f, 1f); push(c.x, c.y, half, 0f, 0f, 1f)
                push(a.x, a.y, -half, 0f, 0f, -1f); push(c.x, c.y, -half, 0f, 0f, -1f); push(bb.x, bb.y, -half, 0f, 0f, -1f)
                t += 3
            }
        }

        // ---- Sides ----
        for (r in rings) {
            for (i in 0 until r.n) {
                val j = (i + 1) % r.n
                // Per-vertex normal for this edge's two ends (hard corner → the edge's own normal).
                val na = if (r.sharp[i]) r.edgeN[i] else r.vertN[i]
                val nb = if (r.sharp[j]) r.edgeN[i] else r.vertN[j]
                for (k in 0 until profile.size - 1) {
                    val p0 = profile[k]; val p1 = profile[k + 1]
                    if (p0.inset == p1.inset && p0.z == p1.z) continue
                    fun vx(v: Int, p: P) = r.pts[v].x - r.offDir[v][0] * p.inset
                    fun vy(v: Int, p: P) = r.pts[v].y - r.offDir[v][1] * p.inset
                    fun nrm(n: FloatArray, p: P): FloatArray {
                        val x = n[0] * p.nh; val y = n[1] * p.nh; val z = p.nz
                        val l = sqrt(x * x + y * y + z * z).coerceAtLeast(1e-6f)
                        return floatArrayOf(x / l, y / l, z / l)
                    }
                    val nA0 = nrm(na, p0); val nA1 = nrm(na, p1)
                    val nB0 = nrm(nb, p0); val nB1 = nrm(nb, p1)
                    // a0 a1 b1  /  a0 b1 b0   (CCW seen from outside)
                    push(vx(i, p0), vy(i, p0), p0.z, nA0[0], nA0[1], nA0[2])
                    push(vx(i, p1), vy(i, p1), p1.z, nA1[0], nA1[1], nA1[2])
                    push(vx(j, p1), vy(j, p1), p1.z, nB1[0], nB1[1], nB1[2])
                    push(vx(i, p0), vy(i, p0), p0.z, nA0[0], nA0[1], nA0[2])
                    push(vx(j, p1), vy(j, p1), p1.z, nB1[0], nB1[1], nB1[2])
                    push(vx(j, p0), vy(j, p0), p0.z, nB0[0], nB0[1], nB0[2])
                }
            }
        }

        val positions = pos.toArray()
        return Mesh(positions, nor.toArray(), positions.size / 9)
    }

    // ---------------------------------------------------------------------------------------------

    private fun area(p: List<Vec2>): Float {
        var s = 0f
        for (i in p.indices) { val a = p[i]; val c = p[(i + 1) % p.size]; s += a.x * c.y - c.x * a.y }
        return s / 2f
    }

    private fun contains(poly: List<Vec2>, pt: Vec2): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]; val c = poly[j]
            if ((a.y > pt.y) != (c.y > pt.y) &&
                pt.x < (c.x - a.x) * (pt.y - a.y) / (c.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }

    private val parents = java.util.IdentityHashMap<Ring, Ring?>()
    private fun parentOf(hole: Ring, @Suppress("UNUSED_PARAMETER") all: List<Ring>): Ring? = parents[hole]

    private fun classify(contours: List<List<Vec2>>): List<Ring> {
        parents.clear()
        val depth = IntArray(contours.size)
        for (i in contours.indices) {
            for (j in contours.indices) {
                if (i != j && contains(contours[j], contours[i][0])) depth[i]++
            }
        }
        val rings = ArrayList<Ring>(contours.size)
        for (i in contours.indices) {
            val isHole = depth[i] % 2 == 1
            var pts = contours[i]
            val a = area(pts)
            // Outers CCW, holes CW (y-up).
            if ((!isHole && a < 0f) || (isHole && a > 0f)) pts = pts.reversed()
            rings.add(Ring(pts, isHole))
        }
        // Parent of a hole = smallest containing outer ring.
        for (h in rings.filter { it.isHole }) {
            parents[h] = rings.filter { !it.isHole && contains(it.pts, h.pts[0]) }
                .minByOrNull { abs(area(it.pts)) }
        }
        rings.forEach(::prepare)
        return rings
    }

    private fun prepare(r: Ring) {
        val n = r.n
        for (i in 0 until n) {
            val a = r.pts[i]; val c = r.pts[(i + 1) % n]
            val ex = c.x - a.x; val ey = c.y - a.y
            val l = hypot(ex.toDouble(), ey.toDouble()).toFloat().coerceAtLeast(1e-6f)
            // Right-hand side of travel = outside of the solid (outers CCW, holes CW).
            r.edgeN[i][0] = ey / l; r.edgeN[i][1] = -ex / l
        }
        for (i in 0 until n) {
            val np = r.edgeN[(i + n - 1) % n]; val nn = r.edgeN[i]
            val dot = np[0] * nn[0] + np[1] * nn[1]
            r.sharp[i] = dot < 0.5f                   // corner sharper than ~60°
            var bx = np[0] + nn[0]; var by = np[1] + nn[1]
            val bl = hypot(bx.toDouble(), by.toDouble()).toFloat()
            if (bl < 1e-5f) { bx = nn[0]; by = nn[1] } else { bx /= bl; by /= bl }
            r.vertN[i][0] = bx; r.vertN[i][1] = by
            val miter = 1f / max(0.5f, bx * nn[0] + by * nn[1])   // clamp so spikes don't explode
            r.offDir[i][0] = bx * min(miter, 2f); r.offDir[i][1] = by * min(miter, 2f)
        }
    }

    data class Vec3(val x: Float, val y: Float, val z: Float)
}
