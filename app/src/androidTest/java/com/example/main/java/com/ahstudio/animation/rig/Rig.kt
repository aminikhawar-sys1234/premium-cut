package com.ahstudio.animation.rig

import com.ahstudio.animation.math.Vec2
import kotlin.math.*

/** One bone of a 2D skeleton. Angles in degrees, measured from +X, counter-clockwise in math space (y down on screen = clockwise). */
data class Bone(
    val id: String, val parentId: String?, val length: Double,
    /** Rest rotation relative to the parent bone (or world for roots). */
    val restAngleDeg: Double = 0.0,
    /** Head position relative to the parent's TAIL (roots: world position). */
    val restOffset: Vec2 = Vec2.ZERO,
    val minAngleDeg: Double = -360.0, val maxAngleDeg: Double = 360.0
)

data class BonePose(val id: String, val head: Vec2, val tail: Vec2, val worldAngleDeg: Double)

/** Forward-kinematics skeleton (Blender armature pose / AE Duik FK). Cycle-safe: bones whose parent chain loops are dropped. */
class Skeleton(bones: List<Bone>) {
    val bones: List<Bone>
    private val byId: Map<String, Bone>
    init {
        val ids = bones.associateBy { it.id }
        fun acyclic(b: Bone): Boolean {
            var cur: Bone? = b; var n = 0
            while (cur != null) { if (++n > bones.size) return false; cur = cur.parentId?.let { ids[it] } }
            return true
        }
        val ok = bones.filter { acyclic(it) && (it.parentId == null || ids.containsKey(it.parentId)) }
        // topological order: parents before children
        val ordered = ArrayList<Bone>(); val done = HashSet<String>()
        fun visit(b: Bone) {
            if (!done.add(b.id)) return
            b.parentId?.let { pid -> ok.firstOrNull { it.id == pid }?.let { visit(it) } }
            ordered.add(b)
        }
        ok.forEach { visit(it) }
        this.bones = ordered; byId = ordered.associateBy { it.id }
    }

    /** [localAngles]: extra rotation (deg) per bone added to its rest angle; clamped to the bone's limits. */
    fun pose(localAngles: Map<String, Double> = emptyMap(), rootOffset: Vec2 = Vec2.ZERO): Map<String, BonePose> {
        val out = LinkedHashMap<String, BonePose>()
        for (b in bones) {
            val rot = (b.restAngleDeg + (localAngles[b.id] ?: 0.0)).coerceIn(b.minAngleDeg, b.maxAngleDeg)
            val parent = b.parentId?.let { out[it] }
            val worldAngle = (parent?.worldAngleDeg ?: 0.0) + rot
            val head = if (parent == null) b.restOffset + rootOffset else parent.tail + rotate(b.restOffset, parent.worldAngleDeg)
            val rad = Math.toRadians(worldAngle)
            out[b.id] = BonePose(b.id, head, head + Vec2(cos(rad), sin(rad)) * b.length, worldAngle)
        }
        return out
    }
    fun get(id: String) = byId[id]
    companion object { fun rotate(v: Vec2, deg: Double): Vec2 { val r = Math.toRadians(deg); return Vec2(v.x * cos(r) - v.y * sin(r), v.x * sin(r) + v.y * cos(r)) } }
}

/** Inverse kinematics solvers. */
object IK {
    /**
     * Analytic two-bone IK (arm/leg). Returns (angle of bone 1 in world degrees, angle of bone 2 RELATIVE to bone 1).
     * Unreachable targets stretch straight toward the target; too-close targets fold. [bendPositive] chooses the elbow side.
     */
    fun twoBone(root: Vec2, target: Vec2, l1: Double, l2: Double, bendPositive: Boolean = true): Pair<Double, Double> {
        val d = target - root
        val dist = d.length().coerceIn(abs(l1 - l2) + 1e-9, l1 + l2 - 1e-9)
        val base = atan2(d.y, d.x)
        val cosA = ((l1 * l1 + dist * dist - l2 * l2) / (2 * l1 * dist)).coerceIn(-1.0, 1.0)
        val cosB = ((l1 * l1 + l2 * l2 - dist * dist) / (2 * l1 * l2)).coerceIn(-1.0, 1.0)
        val a = acos(cosA); val b = acos(cosB)
        val sgn = if (bendPositive) 1.0 else -1.0
        val a1 = base - sgn * a
        val rel = sgn * (PI - b)
        return Math.toDegrees(a1) to Math.toDegrees(rel)
    }

    /**
     * FABRIK for chains of any length. [joints] are world positions root->tip; segment lengths are preserved.
     * The root stays pinned. Returns the solved joint positions.
     */
    fun fabrik(joints: List<Vec2>, target: Vec2, iterations: Int = 16, tolerance: Double = 1e-3): List<Vec2> {
        if (joints.size < 2) return joints
        val n = joints.size
        val lens = DoubleArray(n - 1) { (joints[it + 1] - joints[it]).length().coerceAtLeast(1e-9) }
        val root = joints[0]
        val p = joints.toMutableList()
        val reach = lens.sum()
        if ((target - root).length() >= reach) {          // out of reach: straighten
            val dir = (target - root).normalized()
            var cur = root
            for (i in 1 until n) { cur += dir * lens[i - 1]; p[i] = cur }
            return p
        }
        repeat(iterations) {
            if ((p[n - 1] - target).length() < tolerance) return p
            p[n - 1] = target
            for (i in n - 2 downTo 0) p[i] = p[i + 1] + (p[i] - p[i + 1]).normalized() * lens[i]
            p[0] = root
            for (i in 1 until n) p[i] = p[i - 1] + (p[i] - p[i - 1]).normalized() * lens[i - 1]
        }
        return p
    }

    /** Chain joints positions from a [Skeleton] pose along bone ids (each bone's head, then the last tail). */
    fun chainJoints(pose: Map<String, BonePose>, chain: List<String>): List<Vec2> =
        chain.mapNotNull { pose[it]?.head } + listOfNotNull(pose[chain.last()]?.tail)

    /** Converts solved joint positions back into per-bone LOCAL angle deltas to feed [Skeleton.pose]. */
    fun anglesFromJoints(skeleton: Skeleton, chain: List<String>, joints: List<Vec2>): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        var parentWorld = skeleton.get(chain.first())?.parentId?.let { 0.0 } ?: 0.0
        for ((i, id) in chain.withIndex()) {
            val b = skeleton.get(id) ?: continue
            val d = joints[i + 1] - joints[i]
            val world = Math.toDegrees(atan2(d.y, d.x))
            out[id] = normalize(world - parentWorld - b.restAngleDeg)
            parentWorld = world
        }
        return out
    }
    private fun normalize(d: Double): Double { var x = d % 360.0; if (x > 180) x -= 360; if (x < -180) x += 360; return x }
}

/** Constraints (Blender "Track To", "Limit Rotation", "Damped"): pure helpers returning adjusted angles. */
object Constraints {
    /** Angle (deg) that makes an object at [from] face [to]; [offsetDeg] is the object's authored forward direction. */
    fun lookAt(from: Vec2, to: Vec2, offsetDeg: Double = 0.0): Double {
        val d = to - from
        return if (d.length() < 1e-12) 0.0 else Math.toDegrees(atan2(d.y, d.x)) - offsetDeg
    }
    fun limitAngle(deg: Double, min: Double, max: Double) = deg.coerceIn(min, max)
    /** Shortest-path angular smoothing: moves [current] toward [target] by [factor] (0..1) without spinning the long way. */
    fun dampedAngle(current: Double, target: Double, factor: Double): Double {
        var diff = (target - current) % 360.0
        if (diff > 180) diff -= 360; if (diff < -180) diff += 360
        return current + diff * factor.coerceIn(0.0, 1.0)
    }
}

// -------------------------------------------------------------------------------------------------
// Puppet pin deformation (After Effects "Puppet Pin tool")
// -------------------------------------------------------------------------------------------------
data class PuppetPin(val id: String, val rest: Vec2, val current: Vec2)

/** Regular grid mesh over a [width]x[height] image; deformed with rigid Moving Least Squares (Schaefer et al. 2006). */
class PuppetMesh(val width: Double, val height: Double, val cols: Int = 12, val rows: Int = 12) {
    val vertexCount = (cols + 1) * (rows + 1)
    val rest: Array<Vec2> = Array(vertexCount) { i -> Vec2((i % (cols + 1)) * width / cols, (i / (cols + 1)) * height / rows) }
    val uv: Array<Vec2> = Array(vertexCount) { i -> Vec2((i % (cols + 1)).toDouble() / cols, (i / (cols + 1)).toDouble() / rows) }
    val triangles: IntArray = IntArray(cols * rows * 6).also { t ->
        var k = 0
        for (r in 0 until rows) for (c in 0 until cols) {
            val a = r * (cols + 1) + c; val b = a + 1; val d = a + cols + 1; val e = d + 1
            t[k++] = a; t[k++] = b; t[k++] = d; t[k++] = b; t[k++] = e; t[k++] = d
        }
    }

    /** Deformed vertex positions for the given pins. With no pins the rest mesh is returned. */
    fun deform(pins: List<PuppetPin>, alpha: Double = 1.0): Array<Vec2> {
        if (pins.isEmpty()) return rest.copyOf()
        return Array(vertexCount) { i -> mls(rest[i], pins, alpha) }
    }

    private fun mls(v: Vec2, pins: List<PuppetPin>, alpha: Double): Vec2 {
        // exact hit on a pin -> snap
        for (p in pins) if ((p.rest - v).length() < 1e-9) return p.current
        if (pins.size == 1) return v + (pins[0].current - pins[0].rest)          // single pin = pure translation
        val w = DoubleArray(pins.size) { val d = (pins[it].rest - v).length(); 1.0 / d.pow(2 * alpha).coerceAtLeast(1e-12) }
        val wSum = w.sum()
        var ps = Vec2.ZERO; var qs = Vec2.ZERO
        for (i in pins.indices) { ps += pins[i].rest * w[i]; qs += pins[i].current * w[i] }
        ps = ps / wSum; qs = qs / wSum
        // complex-number form of the rigid fit: c = sum w * q^ * conj(p^)
        var cr = 0.0; var ci = 0.0
        for (i in pins.indices) {
            val p = pins[i].rest - ps; val q = pins[i].current - qs
            cr += w[i] * (q.x * p.x + q.y * p.y)
            ci += w[i] * (q.y * p.x - q.x * p.y)
        }
        val m = sqrt(cr * cr + ci * ci)
        val (cs, sn) = if (m < 1e-12) 1.0 to 0.0 else cr / m to ci / m
        val d = v - ps
        return Vec2(d.x * cs - d.y * sn, d.x * sn + d.y * cs) + qs
    }
}
