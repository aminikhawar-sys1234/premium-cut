package com.ute.text3d

import com.ute.core.Vec2

/**
 * Ear-clipping triangulation with hole bridging (keyhole method).
 *
 * Contract:
 *  - [outer] must be counter-clockwise (y-up), every hole clockwise. [ExtrusionBuilder] normalises this.
 *  - Returned indices refer to the COMBINED list `outer + hole0 + hole1 + ...` (cumulative offsets,
 *    holes may have different sizes).
 */
object Triangulator {

    fun triangulate(outer: List<Vec2>, holes: List<List<Vec2>>): List<Int> {
        if (outer.size < 3) return emptyList()

        // Build combined vertex list + per-hole offsets.
        val offsets = IntArray(holes.size)
        var running = outer.size
        for (i in holes.indices) { offsets[i] = running; running += holes[i].size }

        val poly = ArrayList<Vec2>(running + holes.size * 2)
        val map = ArrayList<Int>(running + holes.size * 2)
        outer.forEachIndexed { i, p -> poly.add(p); map.add(i) }

        // Merge holes right-to-left (largest max-x first) so bridges don't cross earlier holes.
        val order = holes.indices.filter { holes[it].size >= 3 }.sortedByDescending { h -> holes[h].maxOf { it.x } }
        for (h in order) {
            val hole = holes[h]
            var mi = 0
            for (k in hole.indices) if (hole[k].x > hole[mi].x) mi = k
            val m = hole[mi]

            // Nearest polygon vertex that is visible from m (bridge must not cross any edge).
            val candidates = poly.indices.sortedBy { dist2(poly[it], m) }
            var bridge = -1
            for (c in candidates) {
                if (visible(poly, hole, m, poly[c], c)) { bridge = c; break }
            }
            if (bridge < 0) bridge = candidates.first()

            // poly[..bridge], hole from mi around, back to hole[mi], poly[bridge] again, rest.
            val ins = ArrayList<Vec2>(hole.size + 2)
            val insMap = ArrayList<Int>(hole.size + 2)
            for (k in 0..hole.size) {
                val idx = (mi + k) % hole.size
                ins.add(hole[idx]); insMap.add(offsets[h] + idx)
            }
            ins.add(poly[bridge]); insMap.add(map[bridge])
            poly.addAll(bridge + 1, ins)
            map.addAll(bridge + 1, insMap)
        }

        return earClip(poly, map)
    }

    private fun earClip(poly: List<Vec2>, map: List<Int>): List<Int> {
        val ring = poly.indices.toMutableList()
        val tris = ArrayList<Int>()
        var guard = 0
        val limit = poly.size * poly.size + 100
        while (ring.size > 3 && guard++ < limit) {
            var clip = -1
            var bestFallback = -1
            var bestFallbackCross = -Float.MAX_VALUE
            for (i in ring.indices) {
                val a = ring[(i + ring.size - 1) % ring.size]
                val b = ring[i]
                val c = ring[(i + 1) % ring.size]
                val cr = cross(poly[a], poly[b], poly[c])
                if (cr > bestFallbackCross) { bestFallbackCross = cr; bestFallback = i }
                if (cr <= 1e-6f) continue
                if (containsAny(poly, ring, a, b, c)) continue
                clip = i; break
            }
            // Degenerate input (collinear runs, touching bridges): force progress, never loop forever.
            if (clip < 0) clip = bestFallback
            val a = ring[(clip + ring.size - 1) % ring.size]
            val b = ring[clip]
            val c = ring[(clip + 1) % ring.size]
            if (cross(poly[a], poly[b], poly[c]) > 1e-6f) {
                tris.add(map[a]); tris.add(map[b]); tris.add(map[c])
            }
            ring.removeAt(clip)
        }
        if (ring.size == 3 && cross(poly[ring[0]], poly[ring[1]], poly[ring[2]]) > 1e-6f) {
            tris.add(map[ring[0]]); tris.add(map[ring[1]]); tris.add(map[ring[2]])
        }
        return tris
    }

    private fun dist2(a: Vec2, b: Vec2) = (a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y)

    private fun cross(a: Vec2, b: Vec2, c: Vec2) =
        (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

    /** True when segment m→v crosses no edge of [poly] or of the hole being merged. */
    private fun visible(poly: List<Vec2>, hole: List<Vec2>, m: Vec2, v: Vec2, vIdx: Int): Boolean {
        for (i in poly.indices) {
            if (i == vIdx || (i + 1) % poly.size == vIdx) continue
            if (segmentsCross(m, v, poly[i], poly[(i + 1) % poly.size])) return false
        }
        for (i in hole.indices) {
            val p = hole[i]; val q = hole[(i + 1) % hole.size]
            if (p === m || q === m) continue
            if (segmentsCross(m, v, p, q)) return false
        }
        return true
    }

    private fun segmentsCross(p1: Vec2, p2: Vec2, q1: Vec2, q2: Vec2): Boolean {
        fun same(a: Vec2, b: Vec2) = a.x == b.x && a.y == b.y
        if (same(p1, q1) || same(p1, q2) || same(p2, q1) || same(p2, q2)) return false
        val d1 = cross(q1, q2, p1); val d2 = cross(q1, q2, p2)
        val d3 = cross(p1, p2, q1); val d4 = cross(p1, p2, q2)
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
    }

    private fun containsAny(poly: List<Vec2>, ring: List<Int>, a: Int, b: Int, c: Int): Boolean {
        val pa = poly[a]; val pb = poly[b]; val pc = poly[c]
        for (i in ring) {
            if (i == a || i == b || i == c) continue
            val p = poly[i]
            // Bridge duplicates share coordinates with triangle corners: not an obstruction.
            if ((p.x == pa.x && p.y == pa.y) || (p.x == pb.x && p.y == pb.y) || (p.x == pc.x && p.y == pc.y)) continue
            if (pointInTri(p, pa, pb, pc)) return true
        }
        return false
    }

    private fun pointInTri(p: Vec2, a: Vec2, b: Vec2, c: Vec2): Boolean {
        val d1 = cross(a, b, p); val d2 = cross(b, c, p); val d3 = cross(c, a, p)
        val hasNeg = d1 < 0 || d2 < 0 || d3 < 0
        val hasPos = d1 > 0 || d2 > 0 || d3 > 0
        return !(hasNeg && hasPos)
    }
}
