package com.example.engine.composition.gpu

import com.ahstudio.face.core.*
import com.ahstudio.face.deformation.FaceWarpMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** The pending preview and the GL export both place filters through [arFaceGeometry]. */
class ArFaceGeometryTest {

  private fun face(roll: Float = 0f) = TrackedFace(
    trackId = FaceTrackId(1L), detectorTrackId = 1, timestampUs = 0,
    bounds = FaceBounds(0.1f, 0.3f, 0.3f, 0.5f),
    rotation = FaceRotation(0f, 0f, roll), landmarks = null, classification = null,
    confidence = 1f, state = FaceTrackState.DETECTED, interOcular = 0.1f,
  )

  private fun place(flipH: Boolean = false, rot: Int = 0) = FaceWarpMapper.Placement.forClip(
    viewportWidth = 1000, viewportHeight = 1000, rawWidth = 1000, rawHeight = 1000,
    naturalRotation = 0, userRotation = rot,
    flipHorizontal = flipH, flipVertical = false,
    cropScale = 1f, cropOffsetX = 0f, cropOffsetY = 0f,
  )

  @Test fun identityPlacementMatchesPlainMaths() {
    val g = arFaceGeometry(face(), 1f, 0f, place(), 1000f, 1000f)
    assertNotNull(g)
    assertEquals(200f, g!!.centerX, 1f)
    assertEquals(400f, g.centerY, 1f)
    assertEquals(100f, g.halfW, 1f)
    assertEquals(100f, g.halfH, 1f)
  }

  @Test fun mirroredClipMovesFaceToTheOtherSide() {
    val g = arFaceGeometry(face(), 1f, 0f, place(flipH = true), 1000f, 1000f)!!
    assertEquals(800f, g.centerX, 1f)
    assertEquals(400f, g.centerY, 1f)
  }

  @Test fun userRotation90KeepsFaceSize() {
    val g = arFaceGeometry(face(), 1f, 0f, place(rot = 90), 1000f, 1000f)!!
    assertEquals(100f, g.halfW, 1f)
    assertEquals(100f, g.halfH, 1f)
  }
}
