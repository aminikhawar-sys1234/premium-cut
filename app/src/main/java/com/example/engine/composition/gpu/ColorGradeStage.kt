package com.example.engine.composition.gpu

import android.opengl.GLES20
import android.util.Log
import com.ahstudio.color.core.ColorConfig
import com.ahstudio.color.core.ColorFrame
import com.ahstudio.color.core.InputColorMetadata
import com.ahstudio.color.gpu.ColorGpuProcessor
import com.ahstudio.color.gpu.ColorPipelineRenderer
import com.ahstudio.color.integration.CompositionColorAdapter
import com.example.engine.color.ColorEngineHost

/**
 * Per-GL-context colour grade pass. Runs the shared ColorEngine pipeline (linear-light grading,
 * curves, HSL, wheels, LUT, tone map) on a clip's composed 2D texture and returns the graded
 * texture. Returns the input texture unchanged when the clip has no grade, so ungraded
 * projects render exactly as before. On any failure the stage disables itself and passes
 * frames through, so a colour bug can never break preview or export.
 *
 * Must be created and used on the GL thread that owns the compositor.
 */
class ColorGradeStage {
  private val fbo = GlFramebuffer()
  private val processor by lazy { ColorGpuProcessor() }
  private val renderer by lazy { ColorPipelineRenderer(processor) }
  private var adapter: CompositionColorAdapter? = null
  private val cfg = ColorConfig(inputMetadata = InputColorMetadata.fallback())
  private var disabled = false

  fun grade(clipId: String?, timeMs: Long, srcTex: Int, width: Int, height: Int): Int {
    if (disabled || clipId == null || srcTex <= 0 || width <= 0 || height <= 0) return srcTex
    val engine = ColorEngineHost.engineOrNull() ?: return srcTex
    if (engine.isIdentity(clipId)) return srcTex
    if (ColorEngineHost.isBypassed(clipId)) return srcTex

    fbo.setup(width, height) // binds/unbinds internally, so do it before saving GL state

    val prevFbo = IntArray(1); GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
    val prevViewport = IntArray(4); GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, prevViewport, 0)
    val prevProgram = IntArray(1); GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, prevProgram, 0)
    val prevActive = IntArray(1); GLES20.glGetIntegerv(GLES20.GL_ACTIVE_TEXTURE, prevActive, 0)
    val prevTex2d = IntArray(1); GLES20.glGetIntegerv(GLES20.GL_TEXTURE_BINDING_2D, prevTex2d, 0)
    val prevVao = GlEsCompat.currentVao()
    val blendWasOn = GLES20.glIsEnabled(GLES20.GL_BLEND)
    val depthWasOn = GLES20.glIsEnabled(GLES20.GL_DEPTH_TEST)
    val scissorWasOn = GLES20.glIsEnabled(GLES20.GL_SCISSOR_TEST)

    return try {
      GLES20.glDisable(GLES20.GL_BLEND)
      GLES20.glDisable(GLES20.GL_DEPTH_TEST)
      GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
      val a = adapter ?: CompositionColorAdapter(engine, renderer).also { adapter = it }
      val frame = ColorFrame(srcTex, GLES20.GL_TEXTURE_2D, width, height, 0L, InputColorMetadata.fallback())
      // flipY = 0: FBO texture -> FBO texture keeps the compositor's orientation.
      a.gradeClip(clipId, timeMs, frame, fbo.getFboId(), cfg, flipY = 0f)
      fbo.getTextureId()
    } catch (t: Throwable) {
      Log.e("ColorGradeStage", "Colour grade failed; passing frames through", t)
      disabled = true
      srcTex
    } finally {
      GlEsCompat.bindVao(prevVao)
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

  /** GL context lost: cached GPU objects are invalid; they are rebuilt on the next graded frame. */
  fun onContextLost() {
    runCatching { renderer.onContextDestroyed() }
    fbo.release()
    adapter = null
    disabled = false
  }

  fun release() {
    runCatching { renderer.onContextDestroyed() }
    fbo.release()
    adapter = null
  }
}
