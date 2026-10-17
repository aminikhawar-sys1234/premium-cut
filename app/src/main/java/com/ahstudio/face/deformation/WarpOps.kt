package com.ahstudio.face.deformation

import com.ahstudio.face.core.FaceLandmarkType
import com.ahstudio.face.core.TrackedFace
import com.ahstudio.face.core.Vec2
import kotlin.math.abs
import kotlin.math.exp

enum class WarpType { MAGNIFY_EYE, SLIM_LATERAL, PUSH, SWIRL }

data class WarpOp(
    val type: WarpType,
    val center: Vec2,   // normalized frame space
    val radius: Float,  // in IOD units
    val strength: Float, // user intensity x keyframe intensity
    val dir: Vec2 = Vec2.ZERO,
)

data class DeformationParams(
    val eyeEnlarge: Float = 0f,
    val faceSlim: Float = 0f,
    val jawSharp: Float = 0f,
    val noseReshape: Float = 0f,
    val chinAdjust: Float = 0f,
    val smileAdjust: Float = 0f,
    /** Bilateral-style smoothing inside the tracked face ellipse. */
    val skinSmooth: Float = 0f,
    /** Brighten / de-yellow the tracked mouth region. */
    val teethWhiten: Float = 0f,
    // ---- Effects-tools warp channels (CapCut-trending face / photo warps). ----
    /** Enlarges the whole head around the face centre ("Doll" / "Big Head"). */
    val bigHead: Float = 0f,
    /** Shrinks the whole head around the face centre ("Small Face"). */
    val smallFace: Float = 0f,
    /** Caricature squash: bulged forehead, squashed chin, puffed cheeks. */
    val funny: Float = 0f,
    /** Oversized cartoon eyes (stronger than the beauty eyeEnlarge). */
    val alienEyes: Float = 0f,
    /** Face content sags downward from the forehead ("Melting Face"). */
    val melt: Float = 0f,
    /** Cheeks bulge outwards ("Chubby"). */
    val chubby: Float = 0f,
    /** Periodic alternating eye squint ("Wink"); a pure function of clip-local time. */
    val wink: Float = 0f,
    /** Curl vortices across the hair zone ("Perm Curls"). */
    val curls: Float = 0f,
) {
    operator fun plus(o: DeformationParams) = DeformationParams(
        eyeEnlarge = (eyeEnlarge + o.eyeEnlarge).coerceIn(0f, 1f),
        faceSlim = (faceSlim + o.faceSlim).coerceIn(0f, 1f),
        jawSharp = (jawSharp + o.jawSharp).coerceIn(0f, 1f),
        noseReshape = (noseReshape + o.noseReshape).coerceIn(0f, 1f),
        chinAdjust = (chinAdjust + o.chinAdjust).coerceIn(0f, 1f),
        smileAdjust = (smileAdjust + o.smileAdjust).coerceIn(0f, 1f),
        skinSmooth = (skinSmooth + o.skinSmooth).coerceIn(0f, 1f),
        teethWhiten = (teethWhiten + o.teethWhiten).coerceIn(0f, 1f),
        bigHead = (bigHead + o.bigHead).coerceIn(0f, 1f),
        smallFace = (smallFace + o.smallFace).coerceIn(0f, 1f),
        funny = (funny + o.funny).coerceIn(0f, 1f),
        alienEyes = (alienEyes + o.alienEyes).coerceIn(0f, 1f),
        melt = (melt + o.melt).coerceIn(0f, 1f),
        chubby = (chubby + o.chubby).coerceIn(0f, 1f),
        wink = (wink + o.wink).coerceIn(0f, 1f),
        curls = (curls + o.curls).coerceIn(0f, 1f),
    )
}

/** Translates user deformation parameters + landmarks into GPU warp ops. */
object WarpOps {
    fun forFace(face: TrackedFace, params: DeformationParams, clipTimeMs: Long = 0L): List<WarpOp> {
        val iod = face.interOcular
        val L = face.landmarks
        val b = face.bounds
        val ops = ArrayList<WarpOp>(14)
        if (params.eyeEnlarge > 0.001f) {
            listOfNotNull(L?.get(FaceLandmarkType.LEFT_EYE), L?.get(FaceLandmarkType.RIGHT_EYE))
                .forEach { e -> ops += WarpOp(WarpType.MAGNIFY_EYE, e, iod * 0.85f, params.eyeEnlarge * 0.30f) }
        }
        if (params.faceSlim > 0.001f) {
            val y = b.top + 0.58f * b.height
            ops += WarpOp(WarpType.SLIM_LATERAL, Vec2(b.left + 0.22f * b.width, y), iod * 1.4f, params.faceSlim * 0.06f, Vec2(1f, 0f))
            ops += WarpOp(WarpType.SLIM_LATERAL, Vec2(b.right - 0.22f * b.width, y), iod * 1.4f, params.faceSlim * 0.06f, Vec2(-1f, 0f))
        }
        if (params.jawSharp > 0.001f) {
            ops += WarpOp(WarpType.PUSH, Vec2(b.centerX, b.bottom - 0.10f * b.height), iod * 1.2f,
                params.jawSharp * 0.04f, Vec2(0f, 1f))
        }
        val nose = L?.get(FaceLandmarkType.NOSE_BASE)
        if (params.noseReshape > 0.001f && nose != null) {
            // Narrow the nose: pull both flanks towards the centre line.
            ops += WarpOp(WarpType.SLIM_LATERAL, Vec2(nose.x - iod * 0.22f, nose.y), iod * 0.45f, params.noseReshape * 0.05f, Vec2(1f, 0f))
            ops += WarpOp(WarpType.SLIM_LATERAL, Vec2(nose.x + iod * 0.22f, nose.y), iod * 0.45f, params.noseReshape * 0.05f, Vec2(-1f, 0f))
        }
        val chin = L?.get(FaceLandmarkType.MOUTH_BOTTOM)
        if (params.chinAdjust > 0.001f && chin != null) {
            // Same push direction as jaw sharpening, anchored below the lower lip.
            ops += WarpOp(WarpType.PUSH, Vec2(chin.x, chin.y + iod * 0.45f), iod * 0.6f, params.chinAdjust * 0.04f, Vec2(0f, 1f))
        }
        if (params.smileAdjust > 0.001f) {
            // Lift mouth corners: push opposite to the chin/jaw direction.
            listOfNotNull(L?.get(FaceLandmarkType.MOUTH_LEFT), L?.get(FaceLandmarkType.MOUTH_RIGHT))
                .forEach { m -> ops += WarpOp(WarpType.PUSH, m, iod * 0.35f, params.smileAdjust * 0.03f, Vec2(0f, -1f)) }
        }

        // ---- Effects-tools warp channels. ----
        val faceCx = b.centerX
        val faceCy = b.top + 0.42f * b.height
        if (params.bigHead > 0.001f) {
            // One large magnify lens over the whole head; the shoulders stay where they are.
            ops += WarpOp(WarpType.MAGNIFY_EYE, Vec2(faceCx, faceCy), iod * 2.6f, params.bigHead * 0.34f)
        }
        if (params.smallFace > 0.001f) {
            ops += WarpOp(WarpType.MAGNIFY_EYE, Vec2(faceCx, faceCy), iod * 2.2f, -params.smallFace * 0.26f)
        }
        if (params.alienEyes > 0.001f) {
            listOfNotNull(L?.get(FaceLandmarkType.LEFT_EYE), L?.get(FaceLandmarkType.RIGHT_EYE))
                .forEach { e -> ops += WarpOp(WarpType.MAGNIFY_EYE, e, iod * 1.15f, params.alienEyes * 0.50f) }
        }
        if (params.funny > 0.001f) {
            // Bouncy caricature: bulged forehead, squashed chin, cheeks pushed out.
            ops += WarpOp(WarpType.MAGNIFY_EYE, Vec2(faceCx, b.top + 0.16f * b.height), iod * 1.2f, params.funny * 0.30f)
            ops += WarpOp(WarpType.MAGNIFY_EYE, Vec2(faceCx, b.top + 0.88f * b.height), iod * 1.1f, -params.funny * 0.24f)
            ops += WarpOp(WarpType.SLIM_LATERAL, Vec2(b.left + 0.18f * b.width, b.top + 0.45f * b.height), iod * 1.1f, params.funny * 0.07f, Vec2(-1f, 0f))
            ops += WarpOp(WarpType.SLIM_LATERAL, Vec2(b.right - 0.18f * b.width, b.top + 0.45f * b.height), iod * 1.1f, params.funny * 0.07f, Vec2(1f, 0f))
        }
        if (params.melt > 0.001f) {
            // Two stacked downward pushes: the face content sags while the outline holds.
            val chinY = chin?.y ?: (b.top + 0.82f * b.height)
            ops += WarpOp(WarpType.PUSH, Vec2(faceCx, chinY - iod * 0.2f), iod * 1.9f, params.melt * 0.055f, Vec2(0f, 1f))
            ops += WarpOp(WarpType.PUSH, Vec2(faceCx, b.top + 0.25f * b.height), iod * 1.6f, params.melt * 0.030f, Vec2(0f, 1f))
        }
        if (params.chubby > 0.001f) {
            // Outward lateral push on both cheeks: the inverse of the slim operator.
            val cheekY = b.top + 0.52f * b.height
            ops += WarpOp(WarpType.SLIM_LATERAL, Vec2(b.left + 0.20f * b.width, cheekY), iod * 1.3f, params.chubby * 0.055f, Vec2(-1f, 0f))
            ops += WarpOp(WarpType.SLIM_LATERAL, Vec2(b.right - 0.20f * b.width, cheekY), iod * 1.3f, params.chubby * 0.055f, Vec2(1f, 0f))
        }
        if (params.wink > 0.001f) {
            // Alternating squint pulses (left then right), a pure function of clip-local time so
            // preview and export render the identical wink on the identical frame.
            val phase = ((clipTimeMs % WINK_PERIOD_MS).toFloat() / WINK_PERIOD_MS.toFloat()).coerceIn(0f, 1f)
            val leftPulse = gaussianPulse(phase, 0.18f)
            val rightPulse = gaussianPulse(phase, 0.68f)
            L?.get(FaceLandmarkType.LEFT_EYE)?.let { e ->
                val s = -params.wink * 0.38f * leftPulse
                if (abs(s) > 0.004f) ops += WarpOp(WarpType.MAGNIFY_EYE, e, iod * 0.75f, s)
            }
            L?.get(FaceLandmarkType.RIGHT_EYE)?.let { e ->
                val s = -params.wink * 0.38f * rightPulse
                if (abs(s) > 0.004f) ops += WarpOp(WarpType.MAGNIFY_EYE, e, iod * 0.75f, s)
            }
        }
        if (params.curls > 0.001f) {
            // Hair-zone curl vortices across the top of the head, alternating spin direction.
            val headCy = b.top + 0.10f * b.height
            listOf(0.30f, 0.50f, 0.70f).forEachIndexed { i, fx ->
                val spin = if (i % 2 == 0) 1f else -1f
                ops += WarpOp(WarpType.SWIRL, Vec2(b.left + fx * b.width, headCy), iod * 1.05f, spin * params.curls * 2.6f)
            }
        }
        return ops
    }

    /** Gaussian bump around [peak] on the wink timeline: ~0 away from the peak, ~1 at the peak. */
    private fun gaussianPulse(phase: Float, peak: Float): Float {
        val d = (phase - peak) / 0.055f
        return exp(-d * d)
    }

    const val WINK_PERIOD_MS = 2400L
}
