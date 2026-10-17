package com.example.engine.composition.gpu

import android.opengl.GLES20
import android.util.Log
import com.ahstudio.face.core.Vec2
import com.ahstudio.face.deformation.FaceWarpMapper
import com.ahstudio.face.deformation.WarpOp
import com.ahstudio.face.deformation.WarpType
import com.ahstudio.face.rendering.FaceWarpPass
import com.example.domain.model.VideoClip
import com.example.engine.InterpolatedClipTransform
import com.example.engine.effects.BodyPose
import com.example.engine.effects.BodyReshapeParams
import com.example.engine.effects.BodyWarpRegistry

/**
 * Pulls the detected torso, waist, shoulders and legs. Pass-through when the clip has no body
 * reshape, no pose yet, or detection fails. Preview does not block; export does.
 *
 * Temporal stability (CapCut-style): warp strengths scale with the pose confidence, one-sided
 * joint detections are mirrored across the torso axis so the reshape stays symmetric, and the
 * torso radius is smoothed per clip so the warp cannot jump between scenes. A failed frame is
 * skipped for a short cooldown instead of disabling the stage for the whole session.
 */
class BodyWarpStage {
  private val pass = FaceWarpPass()
  private val fbo = GlFramebuffer()
  private var cooldownUntilElapsedMs = 0L

  // Per-clip smoothed torso height (0..1 texture space); keeps the warp radius from jumping
  // scene-to-scene when a shoulder/hip pair drops out for a few frames.
  private val smoothedTorso = HashMap<String, Float>()

  fun apply(
    clip: VideoClip?,
    timelinePosMs: Long,
    srcTex: Int,
    width: Int,
    height: Int,
    blocking: Boolean,
    transform: InterpolatedClipTransform? = null,
    placementOverride: FaceWarpMapper.Placement? = null,
  ): Int {
    if (clip == null || srcTex <= 0 || width <= 0 || height <= 0) return srcTex
    if (!blocking && System.currentTimeMillis() < cooldownUntilElapsedMs) return srcTex
    val params = BodyWarpRegistry.paramsFor(clip.id) ?: return srcTex
    val source = BodyWarpRegistry.poseSource ?: return srcTex
    val pose = try {
      source.poseAt(clip.id, faceSourceTimeUs(clip, timelinePosMs), blocking)
    } catch (t: Throwable) {
      if (blocking) throw t
      null
    } ?: return srcTex
    // A barely-detected pose (one joint in frame) would place a full-strength warp at a guess.
    // Better a weaker but stable reshape than an asymmetric squeeze - and below the floor the
    // tracker itself already coasts to the last-good pose, so this is the hard stop.
    if (pose.confidence < MIN_CONFIDENCE) return srcTex
    val ops = bodyOps(clip.id, pose, params)
    if (ops.isEmpty()) return srcTex
    val placement = placementOverride ?: faceClipPlacement(clip, timelinePosMs, width, height, transform)
    val mapped = FaceWarpMapper.toTextureSpace(ops, placement)
    if (mapped.isEmpty()) return srcTex

    val prevFbo = IntArray(1); GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
    val prevViewport = IntArray(4); GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, prevViewport, 0)
    val prevProgram = IntArray(1); GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, prevProgram, 0)
    val prevActive = IntArray(1); GLES20.glGetIntegerv(GLES20.GL_ACTIVE_TEXTURE, prevActive, 0)
    val prevTex2d = IntArray(1); GLES20.glGetIntegerv(GLES20.GL_TEXTURE_BINDING_2D, prevTex2d, 0)
    val prevVao = GlEsCompat.currentVao()
    val prevArrayBuf = IntArray(1); GLES20.glGetIntegerv(GLES20.GL_ARRAY_BUFFER_BINDING, prevArrayBuf, 0)
    val blendWasOn = GLES20.glIsEnabled(GLES20.GL_BLEND)
    val depthWasOn = GLES20.glIsEnabled(GLES20.GL_DEPTH_TEST)
    val scissorWasOn = GLES20.glIsEnabled(GLES20.GL_SCISSOR_TEST)
    return try {
      fbo.setup(width, height)
      GlEsCompat.bindVao(0)
      GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
      GLES20.glDisable(GLES20.GL_BLEND)
      GLES20.glDisable(GLES20.GL_DEPTH_TEST)
      GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
      pass.render(srcTex, fbo.getFboId(), width, height, mapped)
      fbo.getTextureId()
    } catch (t: Throwable) {
      Log.e(TAG, "Body warp failed; skipping frames for a short cooldown", t)
      if (blocking) throw t
      cooldownUntilElapsedMs = System.currentTimeMillis() + RETRY_COOLDOWN_MS
      srcTex
    } finally {
      GlEsCompat.bindVao(prevVao)
      GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, prevArrayBuf[0])
      GLES20.glUseProgram(prevProgram[0])
      GLES20.glActiveTexture(prevActive[0])
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prevTex2d[0])
      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prevFbo[0])
      GLES20.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3])
      if (blendWasOn) GLES20.glEnable(GLES20.GL_BLEND)
      if (depthWasOn) GLES20.glEnable(GLES20.GL_DEPTH_TEST)
      if (scissorWasOn) GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
    }
  }

  fun onContextLost() {
    pass.reset()
    fbo.release()
    cooldownUntilElapsedMs = 0L
    smoothedTorso.clear()
  }

  fun release() {
    pass.release()
    fbo.release()
    smoothedTorso.clear()
  }

  private fun bodyOps(clipId: String, pose: BodyPose, p: BodyReshapeParams): List<WarpOp> {
    val ops = ArrayList<WarpOp>(8)
    val waist = (p.waist + p.reshape * 0.7f + p.proportions * 0.35f).coerceIn(0f, 1f)
    val legs = (p.legs + p.reshape * 0.45f + p.proportions * 0.55f).coerceIn(0f, 1f)
    val shoulders = (p.shoulders + p.reshape * 0.4f).coerceIn(0f, 1f)
    // Weaker pose => proportionally weaker warp, so a half-detected body deforms half as much
    // instead of snapping to full strength whenever the tracker re-locks.
    val conf = pose.confidence.coerceIn(0f, 1f)
    fun slim(pt: Vec2?, dir: Vec2, radius: Float, strength: Float) {
      if (pt == null || strength <= 0.001f) return
      ops += WarpOp(WarpType.SLIM_LATERAL, pt, radius, strength * conf, dir)
    }
    fun push(pt: Vec2?, dir: Vec2, radius: Float, strength: Float) {
      if (pt == null || strength <= 0.001f) return
      ops += WarpOp(WarpType.PUSH, pt, radius, strength * conf, dir)
    }
    fun mirrored(pt: Vec2?, axisX: Float?): Vec2? {
      if (pt == null || axisX == null) return null
      return Vec2((2f * axisX - pt.x).coerceIn(0f, 1f), pt.y)
    }
    val hips = listOfNotNull(pose.leftHip, pose.rightHip)
    val shoulderPts = listOfNotNull(pose.leftShoulder, pose.rightShoulder)
    val torsoRaw = if (hips.isNotEmpty() && shoulderPts.isNotEmpty()) {
      (hips.map { it.y }.average() - shoulderPts.map { it.y }.average()).toFloat().coerceAtLeast(0.08f)
    } else {
      0.2f
    }
    // EMA on the torso radius: a dropped shoulder/hip pair for a few frames must not shrink or
    // double the warp radius (the "different body in every scene" look).
    val torso = smoothedTorso[clipId]?.let { prev -> prev + (torsoRaw - prev) * TORSO_EMA }
      ?: torsoRaw
    smoothedTorso[clipId] = torso
    val axisX = pose.bodyCenterX()
    // Waist: keep the reshape symmetric even when ML Kit only locks one side - the missing side
    // is mirrored across the torso axis instead of leaving one half of the body untouched.
    val leftWaistPair = pose.leftHip != null && pose.leftShoulder != null
    val rightWaistPair = pose.rightHip != null && pose.rightShoulder != null
    if (leftWaistPair) {
      val waistL = Vec2(pose.leftHip!!.x, pose.leftShoulder!!.y + torso * 0.62f)
      slim(waistL, Vec2(1f, 0f), torso * 0.55f, waist * 0.08f)
    }
    if (rightWaistPair) {
      val waistR = Vec2(pose.rightHip!!.x, pose.rightShoulder!!.y + torso * 0.62f)
      slim(waistR, Vec2(-1f, 0f), torso * 0.55f, waist * 0.08f)
    }
    if (leftWaistPair && !rightWaistPair) {
      val waistL = Vec2(pose.leftHip!!.x, pose.leftShoulder!!.y + torso * 0.62f)
      slim(mirrored(waistL, axisX), Vec2(-1f, 0f), torso * 0.55f, waist * 0.08f)
    } else if (rightWaistPair && !leftWaistPair) {
      val waistR = Vec2(pose.rightHip!!.x, pose.rightShoulder!!.y + torso * 0.62f)
      slim(mirrored(waistR, axisX), Vec2(1f, 0f), torso * 0.55f, waist * 0.08f)
    }
    val leftShoulderOp = pose.leftShoulder
    val rightShoulderOp = pose.rightShoulder
    if (leftShoulderOp != null) {
      slim(leftShoulderOp, Vec2(-1f, 0f), torso * 0.4f, shoulders * 0.05f)
      if (rightShoulderOp == null) slim(mirrored(leftShoulderOp, axisX), Vec2(1f, 0f), torso * 0.4f, shoulders * 0.05f)
    } else if (rightShoulderOp != null) {
      slim(rightShoulderOp, Vec2(1f, 0f), torso * 0.4f, shoulders * 0.05f)
      slim(mirrored(rightShoulderOp, axisX), Vec2(-1f, 0f), torso * 0.4f, shoulders * 0.05f)
    }
    val leftAnkleOp = pose.leftAnkle
    val rightAnkleOp = pose.rightAnkle
    if (leftAnkleOp != null) {
      push(leftAnkleOp, Vec2(0f, 1f), torso * 0.45f, legs * 0.07f)
      if (rightAnkleOp == null) push(mirrored(leftAnkleOp, axisX), Vec2(0f, 1f), torso * 0.45f, legs * 0.07f)
    } else if (rightAnkleOp != null) {
      push(rightAnkleOp, Vec2(0f, 1f), torso * 0.45f, legs * 0.07f)
      push(mirrored(rightAnkleOp, axisX), Vec2(0f, 1f), torso * 0.45f, legs * 0.07f)
    }
    val leftKneeOp = pose.leftKnee
    val rightKneeOp = pose.rightKnee
    if (leftKneeOp != null) {
      push(leftKneeOp, Vec2(0f, 1f), torso * 0.35f, legs * 0.04f)
      if (rightKneeOp == null) push(mirrored(leftKneeOp, axisX), Vec2(0f, 1f), torso * 0.35f, legs * 0.04f)
    } else if (rightKneeOp != null) {
      push(rightKneeOp, Vec2(0f, 1f), torso * 0.35f, legs * 0.04f)
      push(mirrored(rightKneeOp, axisX), Vec2(0f, 1f), torso * 0.35f, legs * 0.04f)
    }
    return ops
  }

  private companion object {
    const val TAG = "BodyWarpStage"
    /** Skip this many ms after a failed frame before trying again (preview only). */
    const val RETRY_COOLDOWN_MS = 250L
    /** Poses below this overall confidence do not drive the warp at all. */
    const val MIN_CONFIDENCE = 0.35f
    /** Torso-radius smoothing factor per frame (higher follows detection faster). */
    const val TORSO_EMA = 0.2f
  }
}
