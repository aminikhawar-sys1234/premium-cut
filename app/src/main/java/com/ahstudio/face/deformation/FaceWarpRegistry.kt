package com.ahstudio.face.deformation

import com.ahstudio.face.core.TrackedFace
import com.ahstudio.face.core.Vec2

/** Supplies tracked faces (oriented, normalized source space) for a clip at a source timestamp. */
interface FaceWarpFaceSource {
    /**
     * @param blocking true on the export path (must be deterministic), false for live preview
     *                 (may return an empty list while detection is still running).
     */
    fun facesAt(clipId: String, sourceTimeUs: Long, blocking: Boolean): List<TrackedFace>
}

/**
 * Process-wide bridge between the editor UI (sliders) and the GL compositor.
 * Preview and export both read the same registry, so what the user sees is what is exported.
 */
object FaceWarpRegistry {
    @Volatile private var params: Map<String, DeformationParams> = emptyMap()
    @Volatile var faceSource: FaceWarpFaceSource? = null

    fun update(newParams: Map<String, DeformationParams>) {
        params = newParams.filterValues { it.isActive() }
    }

    fun paramsFor(clipId: String?): DeformationParams? = clipId?.let { params[it] }

    fun clear() { params = emptyMap(); faceSource = null }
}

fun DeformationParams.isActive(): Boolean =
    eyeEnlarge > 0.001f || faceSlim > 0.001f || jawSharp > 0.001f ||
        noseReshape > 0.001f || chinAdjust > 0.001f || smileAdjust > 0.001f

/**
 * Converts face-space warp ops (top-left origin, upright source frame, radius in frame-width units,
 * `dir` = direction the *content* moves) into the compositor's texture space
 * (u right, v UP, radius in viewport-HEIGHT units, `dir` = unit vector in pixel space) as expected
 * by `FaceWarpPass`.
 *
 * The placement replicates GpuCompositionRenderer.processMainVideoTo2D exactly:
 *   mvp = T(offset + keyframe pos) * R(-totalRotation) * S(screenFit * userScale * keyframe scale)
 * applied to a quad whose local frame is the raw (container-unrotated) video frame.
 */
object FaceWarpMapper {

    class Placement(
        val viewportWidth: Int,
        val viewportHeight: Int,
        /** Container rotation (0/90/180/270) that turned the raw frame into the upright frame. */
        val naturalRotation: Int,
        /** natural + user + keyframe rotation, degrees, as used for the quad rotation. */
        val totalRotation: Float,
        val localScaleX: Float,
        val localScaleY: Float,
        val translateX: Float,
        val translateY: Float,
        /**
         * When set, the renderer's ACTUAL final main-clip matrix (column-major 4x4, maps the local quad
         * [-1,1]^2 to NDC, including stabilization, effect motion and transition transforms). All mapping
         * then goes through this single matrix, so overlays/warps use exactly the transform the pixels got.
         */
        val mvp: FloatArray? = null,
    ) {
        companion object {
            /** Placement taken from the matrix the compositor really used for the main clip this frame. */
            fun fromRenderMatrix(
                viewportWidth: Int, viewportHeight: Int, naturalRotation: Int, mvp: FloatArray
            ): Placement = Placement(
                viewportWidth, viewportHeight, ((naturalRotation % 360) + 360) % 360,
                0f, 1f, 1f, 0f, 0f, mvp.copyOf()
            )

            /** Mirrors the renderer's fit / user-scale / keyframe maths; all offsets are NDC (y down for [cropOffsetY]). */
            fun forClip(
                viewportWidth: Int, viewportHeight: Int,
                rawWidth: Int, rawHeight: Int,
                naturalRotation: Int, userRotation: Int,
                flipHorizontal: Boolean, flipVertical: Boolean,
                cropScale: Float, cropOffsetX: Float, cropOffsetY: Float,
                kfScaleX: Float = 1f, kfScaleY: Float = 1f, kfRotation: Float = 0f,
                kfPosX: Float = 0f, kfPosY: Float = 0f,
            ): Placement {
                val nat = ((naturalRotation % 360) + 360) % 360
                val rawTransposed = nat == 90 || nat == 270
                val effW = if (rawTransposed) rawHeight else rawWidth
                val effH = if (rawTransposed) rawWidth else rawHeight
                val displayAspect = effW.toFloat() / maxOf(1, effH)
                val vpAspect = viewportWidth.toFloat() / maxOf(1, viewportHeight)
                val fitX: Float; val fitY: Float
                if (displayAspect > vpAspect) { fitX = 1f; fitY = vpAspect / displayAspect }
                else { fitX = displayAspect / vpAspect; fitY = 1f }
                val userX = (if (flipHorizontal) -cropScale else cropScale) * kfScaleX
                val userY = (if (flipVertical) -cropScale else cropScale) * kfScaleY
                val total = ((nat.toFloat() + userRotation.toFloat() + kfRotation) % 360f + 360f) % 360f
                val transposed = total == 90f || total == 270f
                return Placement(
                    viewportWidth, viewportHeight, nat, total,
                    (if (transposed) fitY else fitX) * userX,
                    (if (transposed) fitX else fitY) * userY,
                    cropOffsetX + kfPosX, -(cropOffsetY + kfPosY),
                )
            }
        }
    }

    private fun unrotatePoint(rot: Int, x: Float, y: Float): Vec2 = when (rot) {
        90 -> Vec2(y, 1f - x)
        180 -> Vec2(1f - x, 1f - y)
        270 -> Vec2(1f - y, x)
        else -> Vec2(x, y)
    }

    private fun unrotateVector(rot: Int, x: Float, y: Float): Vec2 = when (rot) {
        90 -> Vec2(y, -x)
        180 -> Vec2(-x, -y)
        270 -> Vec2(-y, x)
        else -> Vec2(x, y)
    }

    /** NDC delta of a local quad vector (scale, then rotate by -totalRotation). */
    private fun rotateScale(p: Placement, x: Float, y: Float): Vec2 {
        p.mvp?.let { m -> return Vec2(m[0] * x + m[4] * y, m[1] * x + m[5] * y) }
        val a = Math.toRadians(-p.totalRotation.toDouble())
        val c = kotlin.math.cos(a).toFloat(); val s = kotlin.math.sin(a).toFloat()
        val lx = x * p.localScaleX; val ly = y * p.localScaleY
        return Vec2(lx * c - ly * s, lx * s + ly * c)
    }

    /** upright normalized frame point (y down) -> viewport pixels (y down) */
    private fun toPixels(p: Placement, pt: Vec2): Vec2 {
        val raw = unrotatePoint(p.naturalRotation, pt.x, pt.y)
        val d = rotateScale(p, 2f * raw.x - 1f, 1f - 2f * raw.y)
        val nx = d.x + (p.mvp?.get(12) ?: p.translateX)
        val ny = d.y + (p.mvp?.get(13) ?: p.translateY)
        return Vec2((nx + 1f) * 0.5f * p.viewportWidth, (1f - ny) * 0.5f * p.viewportHeight)
    }

    /**
     * Upright normalized frame point (y down) -> viewport pixels (y down). Same placement maths as the
     * face warp, so overlays honour container rotation, user rotation, flips, crop and keyframes.
     */
    fun pointToViewportPx(p: Placement, pt: Vec2): Vec2 = toPixels(p, pt)

    fun toTextureSpace(ops: List<WarpOp>, p: Placement): List<WarpOp> {
        if (ops.isEmpty() || p.viewportWidth <= 0 || p.viewportHeight <= 0) return emptyList()
        val vw = p.viewportWidth.toFloat()
        val vh = p.viewportHeight.toFloat()
        return ops.map { op ->
            val c = toPixels(p, op.center)
            val edge = toPixels(p, Vec2(op.center.x + op.radius, op.center.y))
            val radiusPx = kotlin.math.hypot(edge.x - c.x, edge.y - c.y)
            val center = Vec2(c.x / vw, 1f - c.y / vh)

            val len = kotlin.math.hypot(op.dir.x, op.dir.y)
            var dir = Vec2.ZERO
            if (len > 1e-6f) {
                val raw = unrotateVector(p.naturalRotation, op.dir.x, op.dir.y)   // still y-down
                val nd = rotateScale(p, raw.x, -raw.y)                              // -> NDC delta, y up
                val px = nd.x * vw * 0.5f; val py = nd.y * vh * 0.5f              // -> pixel space, y up
                val pl = kotlin.math.hypot(px, py)
                if (pl > 1e-6f) dir = Vec2(px / pl * len, py / pl * len)
            }
            WarpOp(op.type, center, radiusPx / vh, op.strength, dir)
        }
    }
}

/** Compact text form stored on the clip so reshape saves, loads and undoes with the timeline. */
object DeformationCodec {
    fun encode(d: DeformationParams): String? =
        if (!d.isActive()) null
        else listOf(d.eyeEnlarge, d.faceSlim, d.jawSharp, d.noseReshape, d.chinAdjust, d.smileAdjust)
            .joinToString(",")

    fun decode(s: String?): DeformationParams {
        if (s.isNullOrBlank()) return DeformationParams()
        val v = s.split(',').map { it.trim().toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f }
        fun at(i: Int) = v.getOrElse(i) { 0f }
        return DeformationParams(at(0), at(1), at(2), at(3), at(4), at(5))
    }
}
