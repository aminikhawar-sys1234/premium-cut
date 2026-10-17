package com.example.engine.composition.gpu

import android.opengl.GLES20
import android.util.Log
import com.ahstudio.face.deformation.FaceWarpMapper
import com.ahstudio.face.deformation.FaceWarpRegistry
import com.ahstudio.face.deformation.WarpOps
import com.ahstudio.face.deformation.hasMeshWarp
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
  // Quarantine, not a session latch: a failed frame is skipped for a short cooldown and the
  // stage retries, so one GL hiccup cannot silently remove the reshape from the preview for
  // the rest of the session while export still applies it.
  private var cooldownUntilElapsedMs = 0L

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
    if (clip == null || srcTex <= 0 || width <= 0 || height <= 0) return srcTex
    if (!blocking && System.currentTimeMillis() < cooldownUntilElapsedMs) return srcTex
    val params = FaceWarpRegistry.paramsFor(clip.id)?.takeIf { it.hasMeshWarp() } ?: return srcTex
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
    // Clip-local time drives the time-based warp channels (Wink); preview and export both pass the
    // timeline position, so the same frame always warps identically.
    val clipTimeMs = (timelinePosMs - clip.timelineStartMs).coerceAtLeast(0L)
    val ops = FaceWarpMapper.toTextureSpace(faces.flatMap { WarpOps.forFace(it, params, clipTimeMs) }, placement)
    if (ops.isEmpty()) return srcTex

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
      // The warp pass feeds its quad from client memory, which is only legal on the default VAO.
      GlEsCompat.bindVao(0)
      GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
      GLES20.glDisable(GLES20.GL_BLEND)
      GLES20.glDisable(GLES20.GL_DEPTH_TEST)
      GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
      pass.render(srcTex, fbo.getFboId(), width, height, ops)
      fbo.getTextureId()
    } catch (t: Throwable) {
      Log.e(TAG, "Face warp failed; skipping frames for a short cooldown", t)
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

  /** GL context lost: all handles are dead; they are rebuilt lazily on the next frame. */
  fun onContextLost() {
    pass.reset()
    fbo.release()
    cooldownUntilElapsedMs = 0L
  }

  fun release() {
    pass.release()
    fbo.release()
  }

  private companion object {
    const val TAG = "FaceWarpStage"
    /** Skip this many ms after a failed frame before trying again (preview only). */
    const val RETRY_COOLDOWN_MS = 250L
  }
}
