package com.example.engine.composition.gpu

import android.opengl.GLES20
import android.opengl.GLES30
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
 */
class BodyWarpStage {
  private val pass = FaceWarpPass()
  private val fbo = GlFramebuffer()
  private var disabled = false

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
    if (disabled || clip == null || srcTex <= 0 || width <= 0 || height <= 0) return srcTex
    val params = BodyWarpRegistry.paramsFor(clip.id) ?: return srcTex
    val source = BodyWarpRegistry.poseSource ?: return srcTex
    val pose = try {
      source.poseAt(clip.id, faceSourceTimeUs(clip, timelinePosMs), blocking)
    } catch (t: Throwable) {
      if (blocking) throw t
      null
    } ?: return srcTex
    val ops = bodyOps(pose, params)
    if (ops.isEmpty()) return srcTex
    val placement = placementOverride ?: faceClipPlacement(clip, timelinePosMs, width, height, transform)
    val mapped = FaceWarpMapper.toTextureSpace(ops, placement)
    if (mapped.isEmpty()) return srcTex

    val prevFbo = IntArray(1); GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
    val prevViewport = IntArray(4); GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, prevViewport, 0)
    val prevProgram = IntArray(1); GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, prevProgram, 0)
    val prevActive = IntArray(1); GLES20.glGetIntegerv(GLES20.GL_ACTIVE_TEXTURE, prevActive, 0)
    val prevTex2d = IntArray(1); GLES20.glGetIntegerv(GLES20.GL_TEXTURE_BINDING_2D, prevTex2d, 0)
    val prevVao = IntArray(1); GLES30.glGetIntegerv(GLES30.GL_VERTEX_ARRAY_BINDING, prevVao, 0)
    val prevArrayBuf = IntArray(1); GLES20.glGetIntegerv(GLES20.GL_ARRAY_BUFFER_BINDING, prevArrayBuf, 0)
    val blendWasOn = GLES20.glIsEnabled(GLES20.GL_BLEND)
    val depthWasOn = GLES20.glIsEnabled(GLES20.GL_DEPTH_TEST)
    val scissorWasOn = GLES20.glIsEnabled(GLES20.GL_SCISSOR_TEST)
    return try {
      fbo.setup(width, height)
      GLES30.glBindVertexArray(0)
      GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
      GLES20.glDisable(GLES20.GL_BLEND)
      GLES20.glDisable(GLES20.GL_DEPTH_TEST)
      GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
      pass.render(srcTex, fbo.getFboId(), width, height, mapped)
      fbo.getTextureId()
    } catch (t: Throwable) {
      Log.e(TAG, "Body warp failed; passing frames through", t)
      if (blocking) throw t
      disabled = true
      srcTex
    } finally {
      GLES30.glBindVertexArray(prevVao[0])
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
    disabled = false
  }

  fun release() {
    pass.release()
    fbo.release()
  }

  private fun bodyOps(pose: BodyPose, p: BodyReshapeParams): List<WarpOp> {
    val ops = ArrayList<WarpOp>(8)
    val waist = (p.waist + p.reshape * 0.7f + p.proportions * 0.35f).coerceIn(0f, 1f)
    val legs = (p.legs + p.reshape * 0.45f + p.proportions * 0.55f).coerceIn(0f, 1f)
    val shoulders = (p.shoulders + p.reshape * 0.4f).coerceIn(0f, 1f)
    fun slim(pt: Vec2?, dir: Vec2, radius: Float, strength: Float) {
      if (pt == null || strength <= 0.001f) return
      ops += WarpOp(WarpType.SLIM_LATERAL, pt, radius, strength, dir)
    }
    fun push(pt: Vec2?, dir: Vec2, radius: Float, strength: Float) {
      if (pt == null || strength <= 0.001f) return
      ops += WarpOp(WarpType.PUSH, pt, radius, strength, dir)
    }
    val hips = listOfNotNull(pose.leftHip, pose.rightHip)
    val shoulderPts = listOfNotNull(pose.leftShoulder, pose.rightShoulder)
    val torso = if (hips.isNotEmpty() && shoulderPts.isNotEmpty()) {
      (hips.map { it.y }.average() - shoulderPts.map { it.y }.average()).toFloat().coerceAtLeast(0.08f)
    } else {
      0.2f
    }
    if (pose.leftHip != null && pose.leftShoulder != null) {
      val waistL = Vec2(pose.leftHip.x, pose.leftShoulder.y + torso * 0.62f)
      slim(waistL, Vec2(1f, 0f), torso * 0.55f, waist * 0.08f)
    }
    if (pose.rightHip != null && pose.rightShoulder != null) {
      val waistR = Vec2(pose.rightHip.x, pose.rightShoulder.y + torso * 0.62f)
      slim(waistR, Vec2(-1f, 0f), torso * 0.55f, waist * 0.08f)
    }
    slim(pose.leftShoulder, Vec2(-1f, 0f), torso * 0.4f, shoulders * 0.05f)
    slim(pose.rightShoulder, Vec2(1f, 0f), torso * 0.4f, shoulders * 0.05f)
    push(pose.leftAnkle, Vec2(0f, 1f), torso * 0.45f, legs * 0.07f)
    push(pose.rightAnkle, Vec2(0f, 1f), torso * 0.45f, legs * 0.07f)
    push(pose.leftKnee, Vec2(0f, 1f), torso * 0.35f, legs * 0.04f)
    push(pose.rightKnee, Vec2(0f, 1f), torso * 0.35f, legs * 0.04f)
    return ops
  }

  private companion object {
    const val TAG = "BodyWarpStage"
  }
}
