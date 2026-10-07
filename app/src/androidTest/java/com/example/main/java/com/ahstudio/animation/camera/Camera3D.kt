package com.ahstudio.animation.camera

import com.ahstudio.animation.math.Vec2
import com.ahstudio.animation.math.Vec3
import kotlin.math.*

operator fun Vec3.plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
operator fun Vec3.minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
operator fun Vec3.times(s: Double) = Vec3(x * s, y * s, z * s)
fun Vec3.dot(o: Vec3) = x * o.x + y * o.y + z * o.z
fun Vec3.cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
fun Vec3.length() = sqrt(dot(this))
fun Vec3.normalized(): Vec3 { val l = length(); return if (l < 1e-12) Vec3.ZERO else Vec3(x / l, y / l, z / l) }

/** Unit quaternion (rotation without gimbal lock; slerp gives constant-speed 3D rotation like Blender quaternion mode). */
data class Quat(val w: Double, val x: Double, val y: Double, val z: Double) {
    operator fun times(o: Quat) = Quat(
        w * o.w - x * o.x - y * o.y - z * o.z, w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x, w * o.z + x * o.y - y * o.x + z * o.w)
    fun normalized(): Quat { val n = sqrt(w * w + x * x + y * y + z * z); return if (n < 1e-12) IDENTITY else Quat(w / n, x / n, y / n, z / n) }
    fun conjugate() = Quat(w, -x, -y, -z)
    fun rotate(v: Vec3): Vec3 {
        val p = Quat(0.0, v.x, v.y, v.z); val r = this * p * conjugate()
        return Vec3(r.x, r.y, r.z)
    }
    fun toMat4(): Mat4 {
        val q = normalized()
        val xx = q.x * q.x; val yy = q.y * q.y; val zz = q.z * q.z
        val xy = q.x * q.y; val xz = q.x * q.z; val yz = q.y * q.z; val wx = q.w * q.x; val wy = q.w * q.y; val wz = q.w * q.z
        return Mat4(doubleArrayOf(
            1 - 2 * (yy + zz), 2 * (xy + wz), 2 * (xz - wy), 0.0,
            2 * (xy - wz), 1 - 2 * (xx + zz), 2 * (yz + wx), 0.0,
            2 * (xz + wy), 2 * (yz - wx), 1 - 2 * (xx + yy), 0.0,
            0.0, 0.0, 0.0, 1.0))
    }
    companion object {
        val IDENTITY = Quat(1.0, 0.0, 0.0, 0.0)
        fun axisAngle(axis: Vec3, deg: Double): Quat {
            val a = axis.normalized(); val h = Math.toRadians(deg) / 2
            return Quat(cos(h), a.x * sin(h), a.y * sin(h), a.z * sin(h))
        }
        /** Applies Z, then Y, then X (After Effects order: orientation then X/Y/Z rotation) -- q = qz * qy * qx. */
        fun fromEulerDeg(xDeg: Double, yDeg: Double, zDeg: Double): Quat =
            (axisAngle(Vec3(0.0, 0.0, 1.0), zDeg) * axisAngle(Vec3(0.0, 1.0, 0.0), yDeg) * axisAngle(Vec3(1.0, 0.0, 0.0), xDeg)).normalized()
        fun slerp(a: Quat, b: Quat, t: Double): Quat {
            var cosT = a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z
            var bb = b
            if (cosT < 0) { bb = Quat(-b.w, -b.x, -b.y, -b.z); cosT = -cosT }       // shortest path
            if (cosT > 0.9995) return Quat(a.w + (bb.w - a.w) * t, a.x + (bb.x - a.x) * t, a.y + (bb.y - a.y) * t, a.z + (bb.z - a.z) * t).normalized()
            val th = acos(cosT.coerceIn(-1.0, 1.0)); val s = sin(th)
            val wa = sin((1 - t) * th) / s; val wb = sin(t * th) / s
            return Quat(a.w * wa + bb.w * wb, a.x * wa + bb.x * wb, a.y * wa + bb.y * wb, a.z * wa + bb.z * wb).normalized()
        }
    }
}

/** Column-major 4x4 matrix (OpenGL convention): element (row r, col c) = m[c*4 + r]. */
class Mat4(val m: DoubleArray) {
    init { require(m.size == 16) }
    operator fun times(o: Mat4): Mat4 {
        val r = DoubleArray(16)
        for (c in 0..3) for (row in 0..3) {
            var s = 0.0
            for (k in 0..3) s += m[k * 4 + row] * o.m[c * 4 + k]
            r[c * 4 + row] = s
        }
        return Mat4(r)
    }
    /** Returns (x, y, z, w) of M * (p, 1). */
    fun transform4(p: Vec3): DoubleArray = doubleArrayOf(
        m[0] * p.x + m[4] * p.y + m[8] * p.z + m[12], m[1] * p.x + m[5] * p.y + m[9] * p.z + m[13],
        m[2] * p.x + m[6] * p.y + m[10] * p.z + m[14], m[3] * p.x + m[7] * p.y + m[11] * p.z + m[15])
    fun transformPoint(p: Vec3): Vec3 { val r = transform4(p); val w = if (abs(r[3]) < 1e-12) 1.0 else r[3]; return Vec3(r[0] / w, r[1] / w, r[2] / w) }

    companion object {
        val IDENTITY = Mat4(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0))
        fun translation(x: Double, y: Double, z: Double) = Mat4(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, x, y, z, 1.0))
        fun scaling(x: Double, y: Double, z: Double) = Mat4(doubleArrayOf(x, 0.0, 0.0, 0.0, 0.0, y, 0.0, 0.0, 0.0, 0.0, z, 0.0, 0.0, 0.0, 0.0, 1.0))
        fun lookAt(eye: Vec3, target: Vec3, up: Vec3 = Vec3(0.0, 1.0, 0.0)): Mat4 {
            val f = (target - eye).normalized()
            var s = f.cross(up).normalized()
            if (s.length() < 1e-9) s = f.cross(Vec3(0.0, 0.0, 1.0)).normalized()
            val u = s.cross(f)
            return Mat4(doubleArrayOf(
                s.x, u.x, -f.x, 0.0, s.y, u.y, -f.y, 0.0, s.z, u.z, -f.z, 0.0,
                -s.dot(eye), -u.dot(eye), f.dot(eye), 1.0))
        }
        fun perspective(fovYDeg: Double, aspect: Double, near: Double, far: Double): Mat4 {
            val f = 1.0 / tan(Math.toRadians(fovYDeg) / 2)
            return Mat4(doubleArrayOf(f / aspect, 0.0, 0.0, 0.0, 0.0, f, 0.0, 0.0,
                0.0, 0.0, (far + near) / (near - far), -1.0, 0.0, 0.0, 2 * far * near / (near - far), 0.0))
        }
    }
}

/**
 * After Effects style 3D layer transform: anchor, position, scale (percent), orientation + X/Y/Z rotation (deg).
 * Layer-local coordinates are pixels with the origin at the layer's top-left and +Y down (composition space).
 */
data class Layer3D(
    val position: Vec3, val anchor: Vec3 = Vec3.ZERO, val scalePercent: Vec3 = Vec3(100.0, 100.0, 100.0),
    val rotationDeg: Vec3 = Vec3.ZERO, val orientationDeg: Vec3 = Vec3.ZERO
) {
    fun model(): Mat4 {
        val rot = Quat.fromEulerDeg(rotationDeg.x, rotationDeg.y, rotationDeg.z) * Quat.fromEulerDeg(orientationDeg.x, orientationDeg.y, orientationDeg.z)
        return Mat4.translation(position.x, position.y, position.z) * rot.toMat4() *
            Mat4.scaling(scalePercent.x / 100.0, scalePercent.y / 100.0, scalePercent.z / 100.0) *
            Mat4.translation(-anchor.x, -anchor.y, -anchor.z)
    }
}

data class ScreenPoint(val x: Double, val y: Double, val depth: Double)

/**
 * Perspective camera in composition space (pixels, +Y down, +Z away from the viewer like AE).
 * Default AE camera: [zoomPx] = distance at which a z=0 layer appears at 100% scale.
 */
class Camera3D(
    val position: Vec3, val pointOfInterest: Vec3, val zoomPx: Double,
    val viewportW: Double, val viewportH: Double,
    val apertureRadiusPx: Double = 0.0, val focusDistance: Double = zoomPx, val near: Double = 1.0
) {
    private val forward = (pointOfInterest - position).normalized()
    // composition space is +Y down: right = worldDown x forward, down = forward x right
    private val right = Vec3(0.0, 1.0, 0.0).cross(forward).normalized().let { if (it.length() < 1e-9) Vec3(1.0, 0.0, 0.0) else it }
    private val down = forward.cross(right).normalized()

    /** Default "AE" camera looking down +Z at the comp centre. */
    companion object {
        fun aeDefault(w: Double, h: Double, zoomPx: Double = 1777.78): Camera3D =
            Camera3D(Vec3(w / 2, h / 2, -zoomPx), Vec3(w / 2, h / 2, 0.0), zoomPx, w, h)
    }

    val fovVerticalDeg: Double get() = Math.toDegrees(2 * atan(viewportH / 2 / zoomPx))

    /** View-space coordinates: x right, y down, z forward (distance along view axis). */
    fun toView(p: Vec3): Vec3 {
        val d = p - position
        return Vec3(d.dot(right), d.dot(down), d.dot(forward))
    }

    fun project(p: Vec3): ScreenPoint? {
        val v = toView(p)
        if (v.z <= near) return null
        val s = zoomPx / v.z
        return ScreenPoint(viewportW / 2 + v.x * s, viewportH / 2 + v.y * s, v.z)
    }

    /** Circle-of-confusion radius (px) for a point at view depth [depth] -- drives depth-of-field blur. */
    fun blurRadius(depth: Double): Double {
        if (apertureRadiusPx <= 0.0 || depth <= 0.0) return 0.0
        return apertureRadiusPx * abs(depth - focusDistance) / depth * (zoomPx / focusDistance.coerceAtLeast(1.0))
    }

    /**
     * Projects the four corners (TL, TR, BR, BL) of a [w]x[h] layer; null when any corner is behind the camera.
     */
    fun projectLayer(layer: Layer3D, w: Double, h: Double): List<ScreenPoint>? {
        val m = layer.model()
        val corners = listOf(Vec3(0.0, 0.0, 0.0), Vec3(w, 0.0, 0.0), Vec3(w, h, 0.0), Vec3(0.0, h, 0.0))
        val out = ArrayList<ScreenPoint>(4)
        for (c in corners) out.add(project(m.transformPoint(c)) ?: return null)
        return out
    }
}

/** Projective mapping between a unit-rectangle-sized source and a screen quad (needed for perspective-correct texture sampling). */
class Homography private constructor(private val h: DoubleArray) {
    fun apply(x: Double, y: Double): Vec2 {
        val w = h[6] * x + h[7] * y + 1.0
        return Vec2((h[0] * x + h[1] * y + h[2]) / w, (h[3] * x + h[4] * y + h[5]) / w)
    }
    fun inverse(): Homography? {
        val a = h[0]; val b = h[1]; val c = h[2]; val d = h[3]; val e = h[4]; val f = h[5]; val g = h[6]; val hh = h[7]; val i = 1.0
        val det = a * (e * i - f * hh) - b * (d * i - f * g) + c * (d * hh - e * g)
        if (abs(det) < 1e-14) return null
        val inv = doubleArrayOf(
            (e * i - f * hh) / det, (c * hh - b * i) / det, (b * f - c * e) / det,
            (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
            (d * hh - e * g) / det, (b * g - a * hh) / det, (a * e - b * d) / det)
        val s = inv[8]
        if (abs(s) < 1e-14) return null
        return Homography(doubleArrayOf(inv[0] / s, inv[1] / s, inv[2] / s, inv[3] / s, inv[4] / s, inv[5] / s, inv[6] / s, inv[7] / s))
    }
    companion object {
        /** Maps (0,0),(w,0),(w,h),(0,h) onto [q] (TL,TR,BR,BL). Null if degenerate. */
        fun rectToQuad(w: Double, h: Double, q: List<Vec2>): Homography? {
            val src = listOf(Vec2(0.0, 0.0), Vec2(w, 0.0), Vec2(w, h), Vec2(0.0, h))
            val a = Array(8) { DoubleArray(9) }
            for (i in 0..3) {
                val (x, y) = src[i].let { it.x to it.y }; val (u, v) = q[i].let { it.x to it.y }
                a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
                a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
            }
            // Gaussian elimination with partial pivoting
            for (col in 0 until 8) {
                var piv = col
                for (r in col + 1 until 8) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
                if (abs(a[piv][col]) < 1e-12) return null
                val t = a[col]; a[col] = a[piv]; a[piv] = t
                for (r in 0 until 8) if (r != col) {
                    val f = a[r][col] / a[col][col]
                    for (k in col..8) a[r][k] -= f * a[col][k]
                }
            }
            return Homography(DoubleArray(8) { a[it][8] / a[it][it] })
        }
    }
}
