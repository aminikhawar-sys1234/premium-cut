package com.example.engine.composition.gpu

import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import com.ahstudio.face.gl.FullscreenQuad
import com.ahstudio.face.gl.GlUtil
import com.example.engine.ai.cutout.BgRemoveParams
import com.example.engine.ai.cutout.CutoutStatus
import com.example.engine.ai.cutout.SubjectCutoutRegistry
import com.example.engine.ai.cutout.SubjectMaskPixels
import com.example.engine.ai.cutout.SubjectMaskResult
import com.example.engine.ai.cutout.SubjectMaskWorker
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Compositor stage that removes the background of a clip using on-device subject segmentation.
 *
 * How it stays light:
 *  - The clip's 2D texture is read back at <= [ANALYSIS_LONG_SIDE] px and segmented off the GL thread
 *    (ML Kit); the resulting mask lives in the texture's own UV space, so no placement maths is needed
 *    and keyframes / rotation / flips / overlays all line up automatically.
 *  - The final pass is a single full-screen shader (threshold + feather, optional colour / blur fill).
 *  - Preview never blocks the GL thread (it re-uses the latest mask, a few frames old at worst);
 *    export ([blocking] = true) waits for an exact mask for every frame so output is deterministic.
 *
 * Strict pass-through (returns the input texture) when the clip has no cutout, the model is not
 * available, no mask exists yet, or anything fails. Must be used on the GL thread that owns the compositor.
 */
class SubjectCutoutStage {

  private class Slot {
    val out = GlFramebuffer()
    val read = GlFramebuffer()
    var readBuf: ByteBuffer? = null
    var maskTex = 0
    var hasMask = false
    var maskBucket = Long.MIN_VALUE
    var epoch = -1
    var lastRequestNs = 0L
    val inFlight = AtomicBoolean(false)
    val pending = AtomicReference<SubjectMaskResult?>(null)
  }

  private val slots = HashMap<String, Slot>()
  private val quad = FullscreenQuad()
  private var copyProgram = 0
  private var cutProgram = 0
  private var disabled = false
  private var prunedEpoch = -1
  private var lastEpoch = Int.MIN_VALUE

  private val jobs = AtomicInteger(0)
  private val consecutiveFailures = AtomicInteger(0)

  // copy program handles
  private var cAPos = 0
  private var cTex = 0

  // cut program handles
  private var aPos = 0
  private var uTex = 0
  private var uMask = 0
  private var uCenter = 0
  private var uHalf = 0
  private var uMode = 0
  private var uBgColor = 0
  private var uPremul = 0
  private var uBlurStep = 0

  /**
   * @param clipId clip whose texture [srcTex] holds (also keys the per-clip mask cache)
   * @param params the clip's cutout look, or null when the clip has no cutout
   * @param analysisAspect width / height of the texture CONTENT (not the viewport) so the analysis image is not stretched
   * @param analysisRotation clockwise degrees that turn the texture content upright (0 when already upright)
   * @param frameKeyUs time key of the frame (any monotonic time base; identical for identical frames)
   * @param blocking true on export: compute an exact mask for this frame before returning
   * @param isBaseLayer true for the main clip (the compositor draws it without blending, so colour is pre-multiplied)
   */
  fun apply(
    clipId: String?,
    params: BgRemoveParams?,
    srcTex: Int,
    width: Int,
    height: Int,
    analysisAspect: Float,
    analysisRotation: Int,
    frameKeyUs: Long,
    blocking: Boolean,
    isBaseLayer: Boolean
  ): Int {
    if (disabled || clipId == null || params == null || srcTex <= 0 || width <= 0 || height <= 0) return srcTex
    if (SubjectCutoutRegistry.status.value == CutoutStatus.UNAVAILABLE) return srcTex

    val prevFbo = IntArray(1); GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
    val prevViewport = IntArray(4); GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, prevViewport, 0)
    val prevProgram = IntArray(1); GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, prevProgram, 0)
    val prevActive = IntArray(1); GLES20.glGetIntegerv(GLES20.GL_ACTIVE_TEXTURE, prevActive, 0)
    GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
    val prevTex1 = IntArray(1); GLES20.glGetIntegerv(GLES20.GL_TEXTURE_BINDING_2D, prevTex1, 0)
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    val prevTex0 = IntArray(1); GLES20.glGetIntegerv(GLES20.GL_TEXTURE_BINDING_2D, prevTex0, 0)
    val prevVao = IntArray(1); GLES30.glGetIntegerv(GLES30.GL_VERTEX_ARRAY_BINDING, prevVao, 0)
    val prevArrayBuf = IntArray(1); GLES20.glGetIntegerv(GLES20.GL_ARRAY_BUFFER_BINDING, prevArrayBuf, 0)
    val blendWasOn = GLES20.glIsEnabled(GLES20.GL_BLEND)
    val depthWasOn = GLES20.glIsEnabled(GLES20.GL_DEPTH_TEST)
    val scissorWasOn = GLES20.glIsEnabled(GLES20.GL_SCISSOR_TEST)

    return try {
      ensureGl()
      // The passes feed their quad from client memory, which is only legal on the default VAO.
      GLES30.glBindVertexArray(0)
      GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
      GLES20.glDisable(GLES20.GL_BLEND)
      GLES20.glDisable(GLES20.GL_DEPTH_TEST)
      GLES20.glDisable(GLES20.GL_SCISSOR_TEST)

      val epoch = SubjectCutoutRegistry.epoch
      if (epoch != lastEpoch) {
        lastEpoch = epoch
        consecutiveFailures.set(0)
      }
      pruneSlots(epoch)

      val slot = slots.getOrPut(clipId) { Slot() }
      if (slot.epoch != epoch) {
        slot.epoch = epoch
        slot.hasMask = false
        slot.maskBucket = Long.MIN_VALUE
        slot.pending.set(null)
      }

      // 1. Pick up a mask the worker finished since the last frame.
      slot.pending.getAndSet(null)?.let { r ->
        if (r.epoch == epoch) uploadMask(slot, r.pixels, r.bucket)
      }

      // 2. Make sure we have (or are getting) a mask for this frame.
      val bucket = frameKeyUs / BUCKET_US
      val exact = slot.hasMask && slot.maskBucket == bucket
      if (!exact) {
        if (blocking) {
          val bytes = readBack(slot, srcTex, analysisAspect)
          val dims = analysisSize(analysisAspect)
          val pixels = if (bytes != null) SubjectMaskWorker.analyse(bytes, dims.first, dims.second, analysisRotation) else null
          if (pixels != null) {
            consecutiveFailures.set(0)
            uploadMask(slot, pixels, bucket)
          } else {
            noteFailure()
          }
        } else if (!slot.inFlight.get() && System.nanoTime() - slot.lastRequestNs >= MIN_INTERVAL_NS) {
          requestAsync(clipId, slot, srcTex, analysisAspect, analysisRotation, bucket, epoch)
        }
      }

      // 3. Use the mask only if it is for this frame (export) or recent enough (preview).
      val fresh = if (blocking) slot.maskBucket == bucket else abs(slot.maskBucket - bucket) <= HOLD_BUCKETS
      val usable = slot.hasMask && slot.maskTex != 0 && fresh
      if (!usable) {
        srcTex
      } else {
        cut(slot, params, srcTex, width, height, isBaseLayer)
        slot.out.getTextureId()
      }
    } catch (t: Throwable) {
      Log.e(TAG, "Background removal failed; passing frames through", t)
      disabled = true
      srcTex
    } finally {
      GLES30.glBindVertexArray(prevVao[0])
      GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, prevArrayBuf[0])
      GLES20.glUseProgram(prevProgram[0])
      GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prevTex1[0])
      GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prevTex0[0])
      GLES20.glActiveTexture(prevActive[0])
      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prevFbo[0])
      GLES20.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3])
      if (blendWasOn) GLES20.glEnable(GLES20.GL_BLEND)
      if (depthWasOn) GLES20.glEnable(GLES20.GL_DEPTH_TEST)
      if (scissorWasOn) GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Analysis
  // ---------------------------------------------------------------------------------------------

  private fun analysisSize(aspect: Float): Pair<Int, Int> {
    val a = if (aspect.isFinite() && aspect > 0.05f) aspect else 1f
    return if (a >= 1f) {
      Pair(ANALYSIS_LONG_SIDE, max(16, (ANALYSIS_LONG_SIDE / a).roundToInt()))
    } else {
      Pair(max(16, (ANALYSIS_LONG_SIDE * a).roundToInt()), ANALYSIS_LONG_SIDE)
    }
  }

  /** Draws [srcTex] into a small FBO and reads it back (RGBA, rows bottom-up). */
  private fun readBack(slot: Slot, srcTex: Int, aspect: Float): ByteArray? {
    val (aw, ah) = analysisSize(aspect)
    slot.read.setup(aw, ah)
    val needed = aw * ah * 4
    var buf = slot.readBuf
    if (buf == null || buf.capacity() != needed) {
      buf = ByteBuffer.allocateDirect(needed)
      slot.readBuf = buf
    }
    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, slot.read.getFboId())
    GLES20.glViewport(0, 0, aw, ah)
    GLES20.glUseProgram(copyProgram)
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex)
    GLES20.glUniform1i(cTex, 0)
    quad.draw(cAPos)
    buf!!.clear()
    GLES20.glReadPixels(0, 0, aw, ah, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
    if (GLES20.glGetError() != GLES20.GL_NO_ERROR) return null
    val bytes = ByteArray(needed)
    buf.rewind()
    buf.get(bytes)
    return bytes
  }

  private fun requestAsync(
    clipId: String,
    slot: Slot,
    srcTex: Int,
    aspect: Float,
    rotation: Int,
    bucket: Long,
    epoch: Int
  ) {
    val bytes = readBack(slot, srcTex, aspect) ?: return
    val (aw, ah) = analysisSize(aspect)
    slot.inFlight.set(true)
    slot.lastRequestNs = System.nanoTime()
    jobs.incrementAndGet()
    if (SubjectCutoutRegistry.status.value != CutoutStatus.UNAVAILABLE) {
      SubjectCutoutRegistry.setStatus(CutoutStatus.WORKING)
    }
    SubjectMaskWorker.submit(Runnable {
      try {
        val pixels = SubjectMaskWorker.analyse(bytes, aw, ah, rotation)
        if (pixels != null) {
          consecutiveFailures.set(0)
          slot.pending.set(SubjectMaskResult(pixels, bucket, epoch))
          SubjectCutoutRegistry.onMaskReady?.invoke(clipId)
        } else {
          noteFailure()
        }
      } catch (t: Throwable) {
        Log.w(TAG, "Mask job failed", t)
        noteFailure()
      } finally {
        slot.inFlight.set(false)
        if (jobs.decrementAndGet() <= 0 && SubjectCutoutRegistry.status.value == CutoutStatus.WORKING) {
          SubjectCutoutRegistry.setStatus(CutoutStatus.IDLE)
        }
      }
    })
  }

  private fun noteFailure() {
    if (consecutiveFailures.incrementAndGet() >= MAX_FAILURES) {
      SubjectCutoutRegistry.setStatus(CutoutStatus.UNAVAILABLE)
    }
  }

  // ---------------------------------------------------------------------------------------------
  // GL
  // ---------------------------------------------------------------------------------------------

  private fun uploadMask(slot: Slot, pixels: SubjectMaskPixels, bucket: Long) {
    if (slot.maskTex == 0) {
      val t = IntArray(1)
      GLES20.glGenTextures(1, t, 0)
      slot.maskTex = t[0]
    }
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, slot.maskTex)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
    val data = ByteBuffer.allocateDirect(pixels.alpha.size)
    data.put(pixels.alpha).position(0)
    GLES20.glTexImage2D(
      GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE,
      pixels.width, pixels.height, 0,
      GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, data
    )
    slot.hasMask = true
    slot.maskBucket = bucket
  }

  private fun cut(slot: Slot, p: BgRemoveParams, srcTex: Int, width: Int, height: Int, isBaseLayer: Boolean) {
    slot.out.setup(width, height)
    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, slot.out.getFboId())
    GLES20.glViewport(0, 0, width, height)
    GLES20.glClearColor(0f, 0f, 0f, 0f)
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    GLES20.glUseProgram(cutProgram)

    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex)
    GLES20.glUniform1i(uTex, 0)
    GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, slot.maskTex)
    GLES20.glUniform1i(uMask, 1)

    // strength: higher = tighter cut. softness: wider feather around the cut line.
    val center = 0.25f + p.strength.coerceIn(0f, 1f) * 0.5f
    val half = 0.03f + p.softness.coerceIn(0f, 1f) * 0.27f
    GLES20.glUniform1f(uCenter, center)
    GLES20.glUniform1f(uHalf, half)
    GLES20.glUniform1i(uMode, p.mode.coerceIn(0, 2))
    val c = p.bgColor
    GLES20.glUniform3f(
      uBgColor,
      ((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f
    )
    GLES20.glUniform1f(uPremul, if (isBaseLayer) 1f else 0f)
    // ~3% of the frame height, circular in pixels
    val radiusY = BLUR_RADIUS
    GLES20.glUniform2f(uBlurStep, radiusY * height.toFloat() / max(1, width), radiusY)

    quad.draw(aPos)

    GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
  }

  private fun ensureGl() {
    if (copyProgram != 0 && cutProgram != 0) return
    copyProgram = GlUtil.createProgram(VS, COPY_FS)
    cAPos = GLES20.glGetAttribLocation(copyProgram, "aPos")
    cTex = GLES20.glGetUniformLocation(copyProgram, "uTex")

    cutProgram = GlUtil.createProgram(VS, CUT_FS)
    aPos = GLES20.glGetAttribLocation(cutProgram, "aPos")
    uTex = GLES20.glGetUniformLocation(cutProgram, "uTex")
    uMask = GLES20.glGetUniformLocation(cutProgram, "uMask")
    uCenter = GLES20.glGetUniformLocation(cutProgram, "uCenter")
    uHalf = GLES20.glGetUniformLocation(cutProgram, "uHalf")
    uMode = GLES20.glGetUniformLocation(cutProgram, "uMode")
    uBgColor = GLES20.glGetUniformLocation(cutProgram, "uBgColor")
    uPremul = GLES20.glGetUniformLocation(cutProgram, "uPremul")
    uBlurStep = GLES20.glGetUniformLocation(cutProgram, "uBlurStep")
  }

  /** Frees GL resources of clips that no longer have a cutout. */
  private fun pruneSlots(epoch: Int) {
    if (prunedEpoch == epoch) return
    prunedEpoch = epoch
    val it = slots.entries.iterator()
    while (it.hasNext()) {
      val e = it.next()
      if (!SubjectCutoutRegistry.isActive(e.key)) {
        releaseSlot(e.value, deleteGl = true)
        it.remove()
      }
    }
  }

  private fun releaseSlot(slot: Slot, deleteGl: Boolean) {
    if (deleteGl) {
      slot.out.release()
      slot.read.release()
      if (slot.maskTex != 0) GLES20.glDeleteTextures(1, intArrayOf(slot.maskTex), 0)
    }
    slot.maskTex = 0
    slot.hasMask = false
    slot.pending.set(null)
  }

  /** GL context lost: all handles are dead; they are rebuilt lazily on the next frame. */
  fun onContextLost() {
    slots.values.forEach { releaseSlot(it, deleteGl = false) }
    slots.clear()
    copyProgram = 0
    cutProgram = 0
    disabled = false
    prunedEpoch = -1
    lastEpoch = Int.MIN_VALUE
  }

  fun release() {
    slots.values.forEach { releaseSlot(it, deleteGl = true) }
    slots.clear()
    if (copyProgram != 0) { GLES20.glDeleteProgram(copyProgram); copyProgram = 0 }
    if (cutProgram != 0) { GLES20.glDeleteProgram(cutProgram); cutProgram = 0 }
  }

  private companion object {
    const val TAG = "SubjectCutoutStage"

    /** Longest side of the analysis image. Small on purpose: fast segmentation, tiny mask. */
    const val ANALYSIS_LONG_SIDE = 384

    /** Frames closer than this share one mask (about 30 fps). */
    const val BUCKET_US = 30_000L

    /** Preview keeps using the last mask for up to this many buckets (about 0.6 s) while a new one is computed. */
    const val HOLD_BUCKETS = 20L

    /** Preview analyses at most ~15 times per second. */
    const val MIN_INTERVAL_NS = 66_000_000L

    /** After this many failed analyses in a row the feature reports UNAVAILABLE instead of retrying forever. */
    const val MAX_FAILURES = 3

    const val BLUR_RADIUS = 0.03f

    const val VS = """
      attribute vec2 aPos;
      varying vec2 vUv;
      void main() { vUv = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }
    """

    const val COPY_FS = """
      precision mediump float;
      varying vec2 vUv;
      uniform sampler2D uTex;
      void main() { gl_FragColor = texture2D(uTex, vUv); }
    """

    const val CUT_FS = """
      precision highp float;
      varying vec2 vUv;
      uniform sampler2D uTex;
      uniform sampler2D uMask;
      uniform float uCenter;
      uniform float uHalf;
      uniform int uMode;
      uniform vec3 uBgColor;
      uniform float uPremul;
      uniform vec2 uBlurStep;

      float cutAlpha(vec2 uv) {
        float m = texture2D(uMask, uv).r;
        return smoothstep(uCenter - uHalf, uCenter + uHalf, m);
      }

      void main() {
        vec4 src = texture2D(uTex, vUv);
        float a = cutAlpha(vUv);
        if (uMode == 0) {
          vec3 rgb = (uPremul > 0.5) ? src.rgb * a : src.rgb;
          gl_FragColor = vec4(rgb, src.a * a);
        } else if (uMode == 1) {
          gl_FragColor = vec4(mix(uBgColor, src.rgb, a), src.a);
        } else {
          // Blur only the background: taps are weighted by how "background" they are,
          // so the subject's colours do not bleed into the blur.
          vec3 acc = src.rgb * (1.0 - a);
          float wsum = 1.0 - a;
          for (int i = 0; i < 12; i++) {
            float ring = (i < 6) ? 0.5 : 1.0;
            float ang = 1.0471976 * float(i) + ((i < 6) ? 0.0 : 0.5235988);
            vec2 uv = clamp(vUv + vec2(cos(ang), sin(ang)) * ring * uBlurStep, vec2(0.0), vec2(1.0));
            float w = 1.0 - cutAlpha(uv);
            acc += texture2D(uTex, uv).rgb * w;
            wsum += w;
          }
          vec3 blurred = (wsum > 0.001) ? acc / wsum : src.rgb;
          gl_FragColor = vec4(mix(blurred, src.rgb, a), src.a);
        }
      }
    """
  }
}
