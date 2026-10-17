package com.example.engine.effects

import android.graphics.Bitmap
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import com.example.engine.composition.gpu.EglCore
import com.example.engine.composition.gpu.VfxStackStage
import com.example.engine.vfx.VfxEffectsHost
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Renders effect-tile previews through the REAL GPU pipeline - the very [VfxStackStage] /
 * [com.example.engine.graph] path the composited preview and the export draw with - instead of
 * the CPU look-alike in [EffectThumbnailRaster].
 *
 * Why: the raster is an approximation. A smoke / explosion / earth tile that animates on the
 * CPU still does not look like what the video will actually show, which users read as "fake
 * thumbnails". Routing every `vfx:` swatch through a private EGL pbuffer context and the real
 * stack runtime makes the tile pixel-faithful to the on-video result (same GLSL, same params,
 * same graph builder).
 *
 * Contract:
 *  - [render] is safe from any thread; the GL work is serialized on one private HandlerThread.
 *  - Returns false (caller falls back to the CPU raster) when GL is unavailable, the key has no
 *    `vfx:` shader, the runtime was quarantined by the stage (identity output), or the request
 *    timed out. A failure never throws into the caller.
 *  - The first fatal GL problem flips [available] off for the whole session; tiles keep working
 *    via the raster fallback.
 *
 * Orientation: the source rows are flipped before upload and the readback rows are flipped
 * again, so the shader sees the same y convention as the compositor (v=0 = frame bottom) and
 * the output bitmap stays upright.
 */
object GlslSwatchRenderer {

  @Volatile
  var available: Boolean = true
    private set

  private val lock = Any()
  private var handler: Handler? = null
  private var ownerThread: HandlerThread? = null
  private var egl: EglCore? = null
  private var pbuffer: EGLSurface? = null
  private var stage: VfxStackStage? = null
  private val stackJsonCache = HashMap<String, String>()
  private var fbo = 0
  private var pixelBuf: ByteBuffer? = null

  /**
   * Renders one tile frame of [shaderKey] (e.g. `vfx:trend.fire`) over the [src] frame sample
   * (row 0 = top, as produced by [android.graphics.Bitmap.getPixels]) at [timeSec] and writes
   * the result into [out] (same layout). Blocks up to ~120ms for the GL thread. On timeout the
   * GL job is marked stale and will NOT write into [out] after the caller moved on.
   */
  fun render(shaderKey: String, src: IntArray, size: Int, timeSec: Float, out: IntArray): Boolean {
    if (!available || size <= 0 || src.size < size * size || out.size < size * size) return false
    if (!shaderKey.startsWith(VFX_KEY_PREFIX)) return false
    val latch = CountDownLatch(1)
    val stale = java.util.concurrent.atomic.AtomicBoolean(false)
    var ok = false
    val posted = synchronized(lock) {
      ensureThread()
      handler?.post {
        try {
          ok = renderOnGlThread(shaderKey, src, size, timeSec, out, stale)
        } catch (_: Throwable) {
          ok = false
        } finally {
          latch.countDown()
        }
      } == true
    }
    if (!posted) return false
    return try {
      if (latch.await(120, TimeUnit.MILLISECONDS)) ok else {
        stale.set(true)
        false
      }
    } catch (_: InterruptedException) {
      stale.set(true)
      false
    }
  }

  /** Kills the GL context and the thread. Safe to call from any thread / multiple times. */
  fun shutdown() {
    synchronized(lock) {
      val h = handler
      handler = null
      // Capture before nulling: the posted lambdas run after this method returns.
      val thread = ownerThread
      ownerThread = null
      h?.post {
        runCatching { stage?.release() }
        runCatching {
          if (fbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
          fbo = 0
        }
        val e = egl
        val pb = pbuffer
        egl = null
        pbuffer = null
        runCatching {
          if (e != null && pb != null && pb != android.opengl.EGL14.EGL_NO_SURFACE) e.releaseSurface(pb)
        }
        runCatching { e?.release() }
        stackJsonCache.clear()
        available = true
      }
      h?.post { thread?.quitSafely() }
    }
  }

  private fun ensureThread() {
    if (handler != null) return
    val thread = HandlerThread("glsl-swatch").also { it.start() }
    ownerThread = thread
    handler = Handler(thread.looper)
  }

  private fun renderOnGlThread(
    shaderKey: String,
    src: IntArray,
    size: Int,
    timeSec: Float,
    out: IntArray,
    stale: java.util.concurrent.atomic.AtomicBoolean,
  ): Boolean {
    val e = try {
      egl ?: run {
        val core = EglCore(
          null,
          // Match the compositor's GL level: GlEsCompat keys off the shared static, so a swatch
          // context must never claim ES3 while a live ES2 compositor is still rendering.
          if (EglCore.activeGlVersion >= 3) EglCore.FLAG_TRY_GLES3 else 0
        ).also {
          egl = it
          pbuffer = it.createOffscreenSurface(size, size)
        }
        core
      }
    } catch (_: Throwable) {
      // No EGL / no config on this device: park the GPU path for the session, tiles use the raster.
      available = false
      return false
    }
    val pb = pbuffer ?: return false
    try {
      e.makeCurrent(pb)
    } catch (_: Throwable) {
      available = false
      return false
    }

    val json = stackJsonFor(shaderKey) ?: return false
    val st = stage ?: VfxStackStage().also { stage = it }

    // Upload the source frame (rows flipped: GL v=0 must be the frame BOTTOM, the compositor's
    // convention, so vertically asymmetric effects like fire rise the same way tiles and video do).
    val texIds = IntArray(1)
    GLES20.glGenTextures(1, texIds, 0)
    val srcTex = texIds[0]
    if (srcTex == 0) return false
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    val flipped = IntArray(size * size) { i ->
      val x = i % size
      val y = i / size
      src[(size - 1 - y) * size + x]
    }
    val bmp = Bitmap.createBitmap(flipped, size, size, Bitmap.Config.ARGB_8888)
    // GLUtils derives internalFormat/type from the bitmap; the 4-arg overload's last param is
    // BORDER (must be 0), not the pixel type.
    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
    bmp.recycle()

    val outTex = try {
      st.apply(
        clipId = SWATCH_CLIP_ID,
        stackJson = json,
        clipTimeMs = (timeSec * 1000f).toLong().coerceAtLeast(0L),
        srcTex = srcTex,
        width = size,
        height = size
      )
    } catch (t: Throwable) {
      GLES20.glDeleteTextures(1, intArrayOf(srcTex), 0)
      throw t
    }

    // Identity output = the stage skipped the effect (runtime quarantined / no passes built):
    // the device cannot draw this id, report it so the tile can fall back and flag itself.
    val drewEffect = outTex != srcTex

    if (fbo == 0) {
      val gen = IntArray(1)
      GLES20.glGenFramebuffers(1, gen, 0)
      fbo = gen[0]
    }
    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
    GLES20.glFramebufferTexture2D(
      GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, outTex, 0
    )
    val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)

    var read = false
    if (status == GLES20.GL_FRAMEBUFFER_COMPLETE) {
      val buf = pixelBuf?.takeIf { it.capacity() >= size * size * 4 }
        ?: ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder()).also { pixelBuf = it }
      buf.clear()
      GLES20.glReadPixels(0, 0, size, size, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
      // GL readback: RGBA bytes, row 0 = framebuffer bottom. Flip rows back and repack to ARGB.
      // Staged in scratch first: a timed-out (stale) request must never touch the caller's buffer.
      buf.rewind()
      val scratch = scratchHold?.takeIf { it.size >= size * size }
        ?: IntArray(size * size).also { scratchHold = it }
      var i = 0
      for (y in size - 1 downTo 0) {
        var rowStart = y * size * 4
        for (x in 0 until size) {
          val r = buf.get(rowStart).toInt() and 0xFF
          val g = buf.get(rowStart + 1).toInt() and 0xFF
          val b = buf.get(rowStart + 2).toInt() and 0xFF
          val a = buf.get(rowStart + 3).toInt() and 0xFF
          scratch[i++] = (a shl 24) or (r shl 16) or (g shl 8) or b
          rowStart += 4
        }
      }
      read = true
      if (!stale.get()) System.arraycopy(scratch, 0, out, 0, size * size)
    }
    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    GLES20.glDeleteTextures(1, intArrayOf(srcTex), 0)
    if (!read) return false
    return drewEffect && !stale.get()
  }

  private var scratchHold: IntArray? = null

  private fun stackJsonFor(key: String): String? {
    stackJsonCache[key]?.let { return it }
    val id = key.removePrefix(VFX_KEY_PREFIX)
    val raw = VfxEffectsHost.addEffect(null, id) ?: return null
    // Tiles must show the exact look a tap produces, so run the same intensity->param mapping
    // the catalog apply runs at full strength.
    val stack = VfxEffectsHost.decode(raw)
    val inst = stack.effects().lastOrNull { it.definition.id == id }
    if (inst != null) runCatching { ProductionEffectApplicator.tuneForPreview(inst, id, 1f) }
    val tuned = VfxEffectsHost.encode(stack) ?: return null
    stackJsonCache[key] = tuned
    return tuned
  }

  private const val VFX_KEY_PREFIX = "vfx:"
  private const val SWATCH_CLIP_ID = "glsl-swatch"
}
