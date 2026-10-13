package com.example.engine.composition.gpu

import android.opengl.GLES20
import android.util.Log
import com.example.engine.vfx.VfxEffectsHost
import com.vfx.engine.core.effect.EffectRuntime
import com.vfx.engine.core.stack.EffectStack
import com.vfx.engine.gpu.GpuContext
import com.vfx.engine.gpu.GpuEffectRuntime
import com.vfx.engine.gpu.GpuTexture
import com.vfx.engine.graph.GraphBuilder
import com.vfx.engine.graph.RenderContext

/**
 * Per-GL-context pass that runs a clip's com.vfx [EffectStack] on its composed 2D texture and
 * returns the resulting texture. Returns the input unchanged when the clip has no stack (or no
 * active effects), so projects without effects render exactly as before. On any failure the stage
 * disables itself and passes frames through, so an effect bug can never break preview or export.
 *
 * Must be created and used on the GL thread that owns the compositor (ES3 context).
 */
class VfxStackStage {
  private var gpu: GpuContext? = null

  // Decoded stack is cached per JSON string, so instance ids (and therefore runtimes) stay stable across frames.
  private var cachedJson: String? = null
  private var cachedStack: EffectStack? = null
  private val runtimes = HashMap<String, EffectRuntime>()

  // Output of the previous frame; kept alive until the next call so the compositor can sample it safely.
  private var pending: GraphBuilder? = null
  private var vao = 0
  private var disabled = false

  fun apply(clipId: String?, stackJson: String?, clipTimeMs: Long, srcTex: Int, width: Int, height: Int): Int {
    releasePending()
    if (disabled || clipId == null || stackJson.isNullOrBlank() || srcTex <= 0 || width <= 0 || height <= 0) return srcTex

    val stack = stackFor(stackJson)
    val timeUs = clipTimeMs.coerceAtLeast(0L) * 1000L
    val resolved = stack.resolveAt(timeUs)
    if (resolved.isEmpty()) return srcTex

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
      val ctxGpu = gpu ?: GpuContext().also { it.initializeOnCurrentContext(); gpu = it }
      if (vao == 0) vao = GlEsCompat.genVao()
      // Private VAO: the effect quad enables vertex attribs 0/1 and must not leak into the compositor's state.
      GlEsCompat.bindVao(vao)
      GLES20.glDisable(GLES20.GL_BLEND)
      GLES20.glDisable(GLES20.GL_DEPTH_TEST)
      GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
      GLES20.glViewport(0, 0, width, height)

      val builder = GraphBuilder(RenderContext(ctxGpu, width, height, timeUs, RenderContext.Quality.BALANCED))
      var tex = GpuTexture.adoptExternal(srcTex, width, height)
      for (r in resolved) {
        val rt = runtimes.getOrPut(r.instance.instanceId) { r.instance.definition.createRuntime(r.instance) }
        val gpuRt = rt as? GpuEffectRuntime ?: continue
        gpuRt.onFrameStart(timeUs)
        tex = gpuRt.buildPasses(builder, tex, r.snapshot)
      }
      if (builder.passCount() == 0) return srcTex
      val out = builder.execute()
      pending = builder
      out.handle
    } catch (t: Throwable) {
      Log.e(TAG, "Effect stack failed; passing frames through", t)
      disabled = true
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

  private fun stackFor(json: String): EffectStack {
    cachedStack?.let { if (cachedJson == json) return it }
    val next = VfxEffectsHost.decode(json)
    // New decode => new instance ids; drop runtimes that no longer belong to any instance.
    val live = next.effects().map { it.instanceId }.toSet()
    runtimes.keys.filter { it !in live }.forEach { id -> runCatching { runtimes.remove(id)?.release() } }
    cachedJson = json
    cachedStack = next
    return next
  }

  private fun releasePending() {
    pending?.disposeFinal()
    pending = null
  }

  /** GL context lost: every GL handle is dead; rebuild lazily on the next frame. */
  fun onContextLost() {
    pending = null
    runtimes.clear()
    cachedJson = null
    cachedStack = null
    runCatching { gpu?.handleContextLoss() }
    gpu = null
    vao = 0
    disabled = false
  }

  fun release() {
    releasePending()
    runtimes.values.forEach { runCatching { it.release() } }
    runtimes.clear()
    runCatching { gpu?.release() }
    gpu = null
    if (vao != 0) { GlEsCompat.deleteVao(vao); vao = 0 }
    cachedJson = null
    cachedStack = null
  }

  private companion object { const val TAG = "VfxStackStage" }
}
