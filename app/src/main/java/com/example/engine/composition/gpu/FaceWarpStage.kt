package com.example.engine.composition.gpu

import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import com.ahstudio.face.deformation.FaceWarpMapper
import com.ahstudio.face.deformation.FaceWarpRegistry
import com.ahstudio.face.deformation.WarpOps
import com.ahstudio.face.rendering.FaceWarpPass
import com.example.domain.model.VideoClip
import com.example.engine.InterpolatedClipTransform

/**
 * Compositor stage that applies the user's Face Reshape sliders (Big Eyes, Slim Face, Jawline, ...)
 * to the clip's composed 2D texture using the face-mesh warp pass.
 *
 * It is a strict pass-through (returns [srcTex]) when the clip has no active reshape, no face is
 * tracked yet, or anything fails, so projects without reshape render exactly as before.
 * Must be used on the GL thread that owns the compositor.
 */
class FaceWarpStage {
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
    /** The matrix-exact placement the compositor used for this frame's main clip (preferred). */
    placementOverride: FaceWarpMapper.Placement? = null
  ): Int {
    if (disabled || clip == null || srcTex <= 0 || width <= 0 || height <= 0) return srcTex
    val params = FaceWarpRegistry.paramsFor(clip.id) ?: return srcTex
    val source = FaceWarpRegistry.faceSource ?: return srcTex

    val faces = try {
      source.facesAt(clip.id, faceSourceTimeUs(clip, timelinePosMs), blocking)
    } catch (t: Throwable) {
      // Export (blocking) must not silently drop the reshape: surface the tracking failure.
      if (blocking) throw t
      emptyList()
    }
    if (faces.isEmpty()) return srcTex

    val placement = placementOverride ?: faceClipPlacement(clip, timelinePosMs, width, height, transform)
    val ops = FaceWarpMapper.toTextureSpace(faces.flatMap { WarpOps.forFace(it, params) }, placement)
    if (ops.isEmpty()) return srcTex

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
      // The warp pass feeds its quad from client memory, which is only legal on the default VAO.
      GLES30.glBindVertexArray(0)
      GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
      GLES20.glDisable(GLES20.GL_BLEND)
      GLES20.glDisable(GLES20.GL_DEPTH_TEST)
      GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
      pass.render(srcTex, fbo.getFboId(), width, height, ops)
      fbo.getTextureId()
    } catch (t: Throwable) {
      Log.e(TAG, "Face warp failed; passing frames through", t)
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

  /** GL context lost: all handles are dead; they are rebuilt lazily on the next frame. */
  fun onContextLost() {
    pass.reset()
    fbo.release()
    disabled = false
  }

  fun release() {
    pass.release()
    fbo.release()
  }

  private companion object { const val TAG = "FaceWarpStage" }
}
