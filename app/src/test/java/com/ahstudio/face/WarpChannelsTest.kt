package com.ahstudio.face

import com.ahstudio.face.core.*
import com.ahstudio.face.deformation.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Contract of the effects-tools warp channels (Big Head, Small Face, Funny, Alien Eyes, Melt,
 * Chubby, Wink, Perm Curls): each active channel emits real mesh-warp ops, the codec round-trips
 * all sixteen fields, and the time-based Wink channel is a pure function of clip-local time.
 */
class WarpChannelsTest {

    private fun face(): TrackedFace {
        val lm = FaceLandmarks(mapOf(
            FaceLandmarkType.LEFT_EYE to Vec2(0.60f, 0.40f),
            FaceLandmarkType.RIGHT_EYE to Vec2(0.40f, 0.40f),
            FaceLandmarkType.NOSE_BASE to Vec2(0.50f, 0.50f),
            FaceLandmarkType.MOUTH_LEFT to Vec2(0.56f, 0.60f),
            FaceLandmarkType.MOUTH_RIGHT to Vec2(0.44f, 0.60f),
            FaceLandmarkType.MOUTH_BOTTOM to Vec2(0.50f, 0.64f),
        ))
        return TrackedFace(
            trackId = FaceTrackId(1L), detectorTrackId = 1, timestampUs = 0,
            bounds = FaceBounds(0.35f, 0.25f, 0.65f, 0.75f),
            rotation = FaceRotation(0f, 0f, 0f), landmarks = lm, classification = null,
            confidence = 1f, state = FaceTrackState.DETECTED, interOcular = 0.20f,
        )
    }

    @Test fun everyWarpChannelEmitsOps() {
        assertEquals(1, WarpOps.forFace(face(), DeformationParams(bigHead = 1f)).size)
        assertEquals(1, WarpOps.forFace(face(), DeformationParams(smallFace = 1f)).size)
        assertEquals(4, WarpOps.forFace(face(), DeformationParams(funny = 1f)).size)
        assertEquals(2, WarpOps.forFace(face(), DeformationParams(alienEyes = 1f)).size)
        assertEquals(2, WarpOps.forFace(face(), DeformationParams(melt = 1f)).size)
        assertEquals(2, WarpOps.forFace(face(), DeformationParams(chubby = 1f)).size)
        assertEquals(3, WarpOps.forFace(face(), DeformationParams(curls = 1f)).size)
    }

    @Test fun warpChannelsUseRealOpTypes() {
        assertTrue(WarpOps.forFace(face(), DeformationParams(bigHead = 1f)).all { it.type == WarpType.MAGNIFY_EYE })
        assertTrue(WarpOps.forFace(face(), DeformationParams(smallFace = 1f)).all { it.type == WarpType.MAGNIFY_EYE })
        // Small face shrinks: the magnify strength is negative.
        assertTrue(WarpOps.forFace(face(), DeformationParams(smallFace = 1f)).all { it.strength < 0f })
        assertTrue(WarpOps.forFace(face(), DeformationParams(melt = 1f)).all { it.type == WarpType.PUSH })
        assertTrue(WarpOps.forFace(face(), DeformationParams(curls = 1f)).all { it.type == WarpType.SWIRL })
        // Curls alternate spin direction across the hair zone.
        val spins = WarpOps.forFace(face(), DeformationParams(curls = 1f)).map { it.strength }
        assertEquals(2, spins.count { it > 0f })
        assertEquals(1, spins.count { it < 0f })
    }

    @Test fun winkIsAPureFunctionOfClipLocalTime() {
        // At the pulse peaks the squint op is emitted; far from them it is not.
        val peakLeft = WarpOps.forFace(face(), DeformationParams(wink = 1f), clipTimeMs = 432L)
        assertTrue(peakLeft.isNotEmpty())
        val quiet = WarpOps.forFace(face(), DeformationParams(wink = 1f), clipTimeMs = 1200L)
        assertTrue(quiet.isEmpty())
        // Deterministic: the same time always produces the same ops (export == preview).
        assertEquals(
            WarpOps.forFace(face(), DeformationParams(wink = 1f), clipTimeMs = 432L),
            WarpOps.forFace(face(), DeformationParams(wink = 1f), clipTimeMs = 432L),
        )
    }

    @Test fun codecRoundTripsAllSixteenFields() {
        val full = DeformationParams(
            0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f,
            0.05f, 0.15f, 0.25f, 0.35f, 0.45f, 0.55f, 0.65f, 0.75f,
        )
        val encoded = DeformationCodec.encode(full)!!
        assertEquals(16, encoded.split(',').size)
        assertEquals(full, DeformationCodec.decode(encoded))
    }

    @Test fun legacyEightFieldStringsDecodeWithZeroedWarpChannels() {
        val legacy = "0.5,0.4,0.0,0.1,0.0,0.0,0.2,0.0"
        val d = DeformationCodec.decode(legacy)
        assertEquals(0.5f, d.eyeEnlarge, 1e-4f)
        assertEquals(0.4f, d.faceSlim, 1e-4f)
        assertEquals(0f, d.bigHead, 1e-4f)
        assertEquals(0f, d.curls, 1e-4f)
    }

    @Test fun warpChannelsCountAsActiveAndAsMeshWarp() {
        assertTrue(DeformationParams(bigHead = 0.4f).isActive())
        assertTrue(DeformationParams(curls = 0.4f).hasMeshWarp())
        assertFalse(DeformationParams(wink = 0.4f, curls = 0f).let { it.wink > 0f && !it.hasMeshWarp() })
    }
}
