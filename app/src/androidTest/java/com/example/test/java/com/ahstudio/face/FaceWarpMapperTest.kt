package com.ahstudio.face

import com.ahstudio.face.core.*
import com.ahstudio.face.deformation.*
import org.junit.Assert.*
import org.junit.Test

class FaceWarpMapperTest {

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

    private fun place(rot: Int = 0, mirror: Boolean = false, zoom: Float = 1f) =
        FaceWarpMapper.Placement.forClip(
            viewportWidth = 1000, viewportHeight = 1000, rawWidth = 1000, rawHeight = 1000,
            naturalRotation = 0, userRotation = rot,
            flipHorizontal = mirror, flipVertical = false,
            cropScale = zoom, cropOffsetX = 0f, cropOffsetY = 0f,
        )

    @Test fun inactiveParamsProduceNoOps() {
        assertTrue(WarpOps.forFace(face(), DeformationParams()).isEmpty())
        assertFalse(DeformationParams().isActive())
        assertTrue(DeformationParams(faceSlim = 0.3f).isActive())
    }

    @Test fun eachSliderProducesOps() {
        assertEquals(2, WarpOps.forFace(face(), DeformationParams(eyeEnlarge = 1f)).size)
        assertEquals(2, WarpOps.forFace(face(), DeformationParams(faceSlim = 1f)).size)
        assertEquals(1, WarpOps.forFace(face(), DeformationParams(jawSharp = 1f)).size)
        assertEquals(2, WarpOps.forFace(face(), DeformationParams(noseReshape = 1f)).size)
        assertEquals(1, WarpOps.forFace(face(), DeformationParams(chinAdjust = 1f)).size)
        assertEquals(2, WarpOps.forFace(face(), DeformationParams(smileAdjust = 1f)).size)
    }

    @Test fun centerIsFlippedToTextureSpaceAndRadiusScalesWithFrame() {
        val ops = WarpOps.forFace(face(), DeformationParams(eyeEnlarge = 1f))
        val out = FaceWarpMapper.toTextureSpace(ops, place())
        assertEquals(ops.size, out.size)
        // eye at y=0.40 (top-left origin) -> v=0.60 (v up)
        assertEquals(0.60f, out[0].center.y, 1e-4f)
        assertEquals(ops[0].center.x, out[0].center.x, 1e-4f)
        // radius: 0.85*IOD(0.2)=0.17 of frame width; square viewport => 0.17 of height
        assertEquals(0.17f, out[0].radius, 1e-4f)
    }

    @Test fun slimMovesContentInwardOnBothSides() {
        val ops = WarpOps.forFace(face(), DeformationParams(faceSlim = 1f))
        val out = FaceWarpMapper.toTextureSpace(ops, place())
        val left = out.minByOrNull { it.center.x }!!
        val right = out.maxByOrNull { it.center.x }!!
        assertTrue("left cheek content must move right", left.dir.x > 0f)
        assertTrue("right cheek content must move left", right.dir.x < 0f)
    }

    @Test fun jawPushMovesContentDownInTextureSpace() {
        val out = FaceWarpMapper.toTextureSpace(
            WarpOps.forFace(face(), DeformationParams(jawSharp = 1f)), place())
        assertTrue("face-space +y (down) becomes v-up negative", out[0].dir.y < 0f)
    }

    @Test fun mirrorFlipsHorizontalPlacementAndDirection() {
        val ops = WarpOps.forFace(face(), DeformationParams(faceSlim = 1f))
        val plain = FaceWarpMapper.toTextureSpace(ops, place())
        val mirrored = FaceWarpMapper.toTextureSpace(ops, place(mirror = true))
        for (i in ops.indices) {
            assertEquals(1f - plain[i].center.x, mirrored[i].center.x, 1e-4f)
            assertEquals(-plain[i].dir.x, mirrored[i].dir.x, 1e-4f)
        }
    }

    @Test fun rotation90RotatesDirectionVector() {
        val op = WarpOp(WarpType.PUSH, Vec2(0.5f, 0.5f), 0.1f, 0.1f, Vec2(1f, 0f))
        val out = FaceWarpMapper.toTextureSpace(listOf(op), place(rot = 90)).single()
        // (1,0) rotated 90deg in face space -> (0,1) (down), then flipped to v-up -> (0,-1)
        assertEquals(0f, out.dir.x, 1e-4f)
        assertEquals(-1f, out.dir.y, 1e-4f)
    }

    @Test fun zoomScalesRadiusAndMovesCenterAboutViewportCentre() {
        val op = WarpOp(WarpType.MAGNIFY_EYE, Vec2(0.75f, 0.5f), 0.1f, 0.3f)
        val z = FaceWarpMapper.toTextureSpace(listOf(op), place(zoom = 2f)).single()
        assertEquals(1.0f, z.center.x, 1e-4f)   // 0.75 -> ndc 0.5 -> *2 = 1.0 -> u 1.0
        assertEquals(0.2f, z.radius, 1e-4f)
    }

    @Test fun portraitVideoWithContainerRotationMapsCornersCorrectly() {
        // raw 1920x1080 landscape frames with 90deg container rotation, shown on a 1080x1920 canvas
        val p = FaceWarpMapper.Placement.forClip(
            viewportWidth = 1080, viewportHeight = 1920, rawWidth = 1920, rawHeight = 1080,
            naturalRotation = 90, userRotation = 0, flipHorizontal = false, flipVertical = false,
            cropScale = 1f, cropOffsetX = 0f, cropOffsetY = 0f,
        )
        val tl = FaceWarpMapper.toTextureSpace(listOf(WarpOp(WarpType.MAGNIFY_EYE, Vec2(0f, 0f), 0.1f, 0.3f)), p).single()
        assertEquals(0f, tl.center.x, 1e-4f)   // upright top-left -> texture u=0, v=1
        assertEquals(1f, tl.center.y, 1e-4f)
        val br = FaceWarpMapper.toTextureSpace(listOf(WarpOp(WarpType.MAGNIFY_EYE, Vec2(1f, 1f), 0.1f, 0.3f)), p).single()
        assertEquals(1f, br.center.x, 1e-4f)
        assertEquals(0f, br.center.y, 1e-4f)
    }

    @Test fun keyframePositionAndScaleFollowTheClip() {
        val base = place()
        val moved = FaceWarpMapper.Placement.forClip(
            viewportWidth = 1000, viewportHeight = 1000, rawWidth = 1000, rawHeight = 1000,
            naturalRotation = 0, userRotation = 0, flipHorizontal = false, flipVertical = false,
            cropScale = 1f, cropOffsetX = 0f, cropOffsetY = 0f,
            kfScaleX = 0.5f, kfScaleY = 0.5f, kfPosX = 0.5f, kfPosY = 0f,
        )
        val op = WarpOp(WarpType.MAGNIFY_EYE, Vec2(0.5f, 0.5f), 0.2f, 0.3f)
        val a = FaceWarpMapper.toTextureSpace(listOf(op), base).single()
        val b = FaceWarpMapper.toTextureSpace(listOf(op), moved).single()
        assertEquals(0.5f, a.center.x, 1e-4f)
        assertEquals(0.75f, b.center.x, 1e-4f)          // shifted right by 0.5 NDC = 0.25 u
        assertEquals(a.radius / 2f, b.radius, 1e-4f)    // clip shrunk to half size
    }

    @Test fun registryDropsInactiveClips() {
        FaceWarpRegistry.update(mapOf("a" to DeformationParams(), "b" to DeformationParams(eyeEnlarge = 0.5f)))
        assertNull(FaceWarpRegistry.paramsFor("a"))
        assertNotNull(FaceWarpRegistry.paramsFor("b"))
        FaceWarpRegistry.clear()
        assertNull(FaceWarpRegistry.paramsFor("b"))
    }

    @Test fun codecRoundTripsAndTreatsEmptyAsInactive() {
        val d = DeformationParams(0.25f, 0.5f, 0f, 0.1f, 0.75f, 1f)
        assertEquals(d, DeformationCodec.decode(DeformationCodec.encode(d)))
        assertNull(DeformationCodec.encode(DeformationParams()))
        assertEquals(DeformationParams(), DeformationCodec.decode(null))
        assertEquals(DeformationParams(), DeformationCodec.decode("garbage"))
        assertEquals(1f, DeformationCodec.decode("5,0,0,0,0,0").eyeEnlarge, 0f) // clamped
    }

    /** mvp = T * R(-total) * S(local), column-major, exactly as the compositor composes it. */
    private fun mvpFor(p: FaceWarpMapper.Placement): FloatArray {
        val a = Math.toRadians(-p.totalRotation.toDouble())
        val c = Math.cos(a).toFloat(); val s = Math.sin(a).toFloat()
        return floatArrayOf(
            c * p.localScaleX, s * p.localScaleX, 0f, 0f,
            -s * p.localScaleY, c * p.localScaleY, 0f, 0f,
            0f, 0f, 1f, 0f,
            p.translateX, p.translateY, 0f, 1f,
        )
    }

    /** Preview and export both map through the renderer's matrix: it must equal the clip-parameter placement. */
    @Test fun matrixPlacementMatchesClipPlacementForRotationsAndMirror() {
        for (rot in intArrayOf(0, 90, 180, 270)) for (mirror in booleanArrayOf(false, true)) {
            val legacy = FaceWarpMapper.Placement.forClip(
                viewportWidth = 1920, viewportHeight = 1080, rawWidth = 1080, rawHeight = 1920,
                naturalRotation = rot, userRotation = 0, flipHorizontal = mirror, flipVertical = false,
                cropScale = 1.2f, cropOffsetX = 0.1f, cropOffsetY = -0.05f,
            )
            val exact = FaceWarpMapper.Placement.fromRenderMatrix(1920, 1080, rot, mvpFor(legacy))
            for (pt in listOf(Vec2(0.5f, 0.5f), Vec2(0.2f, 0.7f), Vec2(0.9f, 0.1f))) {
                val a = FaceWarpMapper.pointToViewportPx(legacy, pt)
                val b = FaceWarpMapper.pointToViewportPx(exact, pt)
                assertEquals("x rot=$rot mirror=$mirror", a.x, b.x, 0.01f)
                assertEquals("y rot=$rot mirror=$mirror", a.y, b.y, 0.01f)
            }
        }
    }
}
