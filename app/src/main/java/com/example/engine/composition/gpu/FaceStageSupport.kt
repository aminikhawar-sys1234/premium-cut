package com.example.engine.composition.gpu

import com.ahstudio.face.core.TrackedFace
import com.ahstudio.face.core.Vec2
import com.ahstudio.face.deformation.FaceWarpMapper
import com.example.domain.model.VideoClip
import com.example.engine.InterpolatedClipTransform
import com.example.engine.KeyframeInterpolator
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Timeline position -> source media time; honours speed, speed curves, reverse and freeze. */
internal fun faceSourceTimeUs(clip: VideoClip, timelinePosMs: Long): Long {
  clip.freezeFrameAtMs?.let { return it.coerceAtLeast(0L) * 1000L }
  return clip.timelineToSourceMs(timelinePosMs).coerceAtLeast(0L) * 1000L
}

/**
 * Where the clip's upright frame lands in the viewport. Same inputs as
 * GpuCompositionRenderer.processMainVideoTo2D, so anything mapped through it tracks keyframed
 * position / scale / rotation, user rotation, flips and crop exactly.
 */
internal fun faceClipPlacement(
  clip: VideoClip,
  timelinePosMs: Long,
  width: Int,
  height: Int,
  transform: InterpolatedClipTransform?
): FaceWarpMapper.Placement {
  val kf = transform ?: KeyframeInterpolator.interpolate(clip, timelinePosMs - clip.timelineStartMs)
  return FaceWarpMapper.Placement.forClip(
    viewportWidth = width, viewportHeight = height,
    rawWidth = if (clip.width > 0) clip.width else width,
    rawHeight = if (clip.height > 0) clip.height else height,
    naturalRotation = if (clip.isVideo) clip.naturalRotation else 0,
    userRotation = clip.rotationDegrees,
    flipHorizontal = clip.flipHorizontal, flipVertical = clip.flipVertical,
    cropScale = clip.cropScale, cropOffsetX = clip.cropOffsetX, cropOffsetY = clip.cropOffsetY,
    kfScaleX = kf.scaleX, kfScaleY = kf.scaleY, kfRotation = kf.rotation,
    kfPosX = kf.posX, kfPosY = kf.posY
  )
}

/** One face in viewport pixels: pivot, half extents along the face's own axes, and quad rotation. */
internal class FaceGeometry(
  val centerX: Float, val centerY: Float,
  val halfW: Float, val halfH: Float,
  val rotationDegCcw: Float,
)

/**
 * Mirrors ArFaceRenderer.draw: the face box scaled by [scale], the pivot lowered by [offsetY] of
 * the frame height, rotated by the face's roll. Instead of assuming an upright, unflipped video,
 * the pivot and two face axes are pushed through the clip placement.
 */
internal fun arFaceGeometry(
  face: TrackedFace,
  scale: Float,
  offsetY: Float,
  placement: FaceWarpMapper.Placement,
  uprightW: Float,
  uprightH: Float,
): FaceGeometry? {
  val b = face.bounds
  val hw = b.width * uprightW * scale / 2f
  val hh = b.height * uprightH * scale / 2f
  if (hw <= 0f || hh <= 0f) return null
  val pivotX = b.centerX * uprightW
  val pivotY = b.centerY * uprightH + offsetY * uprightH
  val z = Math.toRadians(face.rotation.eulerZ.toDouble())
  val sinZ = sin(z).toFloat(); val cosZ = cos(z).toFloat()

  fun map(x: Float, y: Float) =
    FaceWarpMapper.pointToViewportPx(placement, Vec2(x / uprightW, y / uprightH))

  val c = map(pivotX, pivotY)
  // Face roll is counter-clockwise in the upright frame (y down): right = (cos, -sin), down = (sin, cos).
  val r = map(pivotX + cosZ * hw, pivotY - sinZ * hw)
  val d = map(pivotX + sinZ * hh, pivotY + cosZ * hh)
  val vx = Vec2(r.x - c.x, r.y - c.y)
  val vy = Vec2(d.x - c.x, d.y - c.y)
  val halfW = hypot(vx.x, vx.y)
  val halfH = hypot(vy.x, vy.y)
  if (halfW < 1f || halfH < 1f) return null

  // The art is never mirrored (like in the preview): only its "down" axis decides the rotation, so
  // flips change positions and roll direction but not the drawing itself.
  val cwRad = atan2(-vy.x, vy.y)
  val ccwDeg = -Math.toDegrees(cwRad.toDouble()).toFloat()
  return FaceGeometry(c.x, c.y, halfW, halfH, ccwDeg)
}

/** Upright (container-rotated) frame size of [clip] in source pixels; falls back to the viewport. */
internal fun faceUprightSize(clip: VideoClip, width: Int, height: Int): Pair<Float, Float> {
  val nat = ((if (clip.isVideo) clip.naturalRotation else 0) % 360 + 360) % 360
  val rawW = (if (clip.width > 0) clip.width else width).toFloat()
  val rawH = (if (clip.height > 0) clip.height else height).toFloat()
  return if (nat == 90 || nat == 270) rawH to rawW else rawW to rawH
}
