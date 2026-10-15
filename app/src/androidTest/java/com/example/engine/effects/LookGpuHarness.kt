package com.example.engine.effects

import android.opengl.EGLSurface
import android.opengl.GLES20
import com.example.engine.composition.gpu.EglCore
import com.example.domain.model.TransitionType
import com.example.engine.composition.gpu.GpuShaders
import com.example.engine.composition.gpu.ShaderTransitionCompositor
import com.example.engine.effects.media3.ColorGradingGlEffect
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Real-GPU harness for the Filters / Adjust / Video Quality pipeline.
 *
 * It creates a genuine EGL context through the app's own [EglCore], compiles the **actual**
 * fragment shader sources that ship in the app (the Media3 preview shader and the export
 * shader), renders them into an FBO with real textures and reads the pixels back.
 *
 * The point is that preview and export are both exercised on a real GLES driver with the exact
 * uniforms [ColorLookModel] resolves — the same function both production paths call — so a
 * divergence between the two shaders shows up as different pixels instead of a passing string
 * comparison.
 */
internal class LookGpuHarness(val size: Int = DEFAULT_SIZE, source: ByteArray? = null) : Closeable {

  companion object {
    const val DEFAULT_SIZE = 96

    /** Deterministic synthetic frame: gradients plus saturated patches, RGBA, top-down rows. */
    fun syntheticFrame(size: Int = DEFAULT_SIZE): ByteArray = ByteArray(size * size * 4).also { px ->
      var i = 0
      for (y in 0 until size) for (x in 0 until size) {
        val fx = x.toFloat() / (size - 1)
        val fy = y.toFloat() / (size - 1)
        val patch = ((x * 6 / size) + (y * 6 / size)) % 3
        // The first and last 1/8 of the frame are near-black and near-white bands: the tone
        // controls (blacks / whites / shadows / highlights) weight pixels by luma, so a frame that
        // only covers the mid range cannot show whether they do anything.
        val r: Float = when {
          fx < 0.125f -> 0.02f
          fx > 0.875f -> 0.98f
          patch == 0 -> 0.80f + 0.15f * fx
          patch == 1 -> 0.15f + 0.30f * fy
          else -> 0.30f * fx + 0.20f
        }
        val g: Float = when {
          fx < 0.125f -> 0.02f
          fx > 0.875f -> 0.98f
          else -> 0.25f + 0.55f * fy
        }
        val b: Float = when {
          fx < 0.125f -> 0.02f
          fx > 0.875f -> 0.98f
          patch == 0 -> 0.12f
          patch == 1 -> 0.75f - 0.25f * fx
          else -> 0.55f + 0.35f * fx
        }
        px[i++] = (r.coerceIn(0f, 1f) * 255f).toInt().toByte()
        px[i++] = (g.coerceIn(0f, 1f) * 255f).toInt().toByte()
        px[i++] = (b.coerceIn(0f, 1f) * 255f).toInt().toByte()
        px[i++] = 0xFF.toByte()
      }
    }

    /** The exact preview shader Media3 runs (`ColorGradingGlEffect`). */
    val PREVIEW_SHADER: String = ColorGradingGlEffect.COLOR_GRADING_FRAGMENT_SHADER

    /** The exact export shader the compositor runs (`GpuShaders.buildFragmentShader`). */
    val EXPORT_SHADER: String = GpuShaders.buildFragmentShader(false)

    /** Minimal full-screen quad. Only the varying name must match each fragment shader. */
    fun vertexShader(varying: String): String = """
      attribute vec4 aPosition;
      attribute vec2 aTexCoord;
      varying vec2 $varying;
      void main() {
        gl_Position = aPosition;
        $varying = aTexCoord;
      }
    """.trimIndent()

    /**
     * Renders a FADE between two solid colours through the shipped ES3 transition compositor and
     * returns true only when the result is a visible blend. False also covers "the compositor says
     * it rendered but the frame came back black", which is what the exporter would fall back from.
     */
    fun shaderTransitionBlendWorks(): Boolean {
      val harness = try {
        LookGpuHarness()
      } catch (_: Throwable) {
        return false
      }
      try {
        harness.bindFbo()
        val compositor = ShaderTransitionCompositor()
        if (!compositor.isAvailable()) return false
        val texA = harness.uploadSolidRgb(0.10f, 0.70f, 0.20f)
        val texB = harness.uploadSolidRgb(0.80f, 0.10f, 0.60f)
        val rendered = compositor.render(
          TransitionType.FADE, 0.5f, texA, texB, harness.size, harness.size, harness.fboId
        )
        harness.bindFbo()
        val frame = harness.readBackRgb()
        var sum = 0f
        for (v in frame) sum += v
        val mean = sum / frame.size
        runCatching { compositor.release() }
        return rendered && mean > 0.05f
      } catch (_: Throwable) {
        return false
      } finally {
        runCatching { harness.close() }
      }
    }

    fun glVersionString(): String =
      (GLES20.glGetString(GLES20.GL_VERSION) ?: "unknown") + " / " +
        (GLES20.glGetString(GLES20.GL_RENDERER) ?: "unknown")
  }

  data class Program(val id: Int, val vertexLog: String, val fragmentLog: String, val linkLog: String) {
    val ok: Boolean get() = id != 0
  }

  val core: EglCore = EglCore(null, EglCore.FLAG_TRY_GLES3)
  private var surface: EGLSurface? = null
  private var fbo: Int = 0
  private var fboTexture: Int = 0
  private var quadVbo: Int = 0
  private var inputTexture: Int = 0
  private val readback: ByteBuffer =
    ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder())

  /** Deterministic synthetic frame (or the caller's pixels), RGBA, top-down rows. */
  val sourceRgba: ByteArray = source ?: syntheticFrame(size)

  init {
    surface = createSurface()
    core.makeCurrent(surface!!)
    setupTargets()
    inputTexture = uploadTexture(sourceRgba)
  }

  private fun createSurface(): EGLSurface = core.createOffscreenSurface(size, size)

  private fun setupTargets() {
    val tex = IntArray(1)
    GLES20.glGenTextures(1, tex, 0)
    fboTexture = tex[0]
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexture)
    GLES20.glTexImage2D(
      GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, size, size, 0,
      GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
    )
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

    val fb = IntArray(1)
    GLES20.glGenFramebuffers(1, fb, 0)
    fbo = fb[0]
    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
    GLES20.glFramebufferTexture2D(
      GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTexture, 0
    )
    val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
    check(status == GLES20.GL_FRAMEBUFFER_COMPLETE) { "FBO incomplete: 0x${Integer.toHexString(status)}" }
    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

    val vbo = IntArray(1)
    GLES20.glGenBuffers(1, vbo, 0)
    quadVbo = vbo[0]
    // x, y, u, v for a GL_TRIANGLE_STRIP covering clip space.
    val quad = floatArrayOf(
      -1f, -1f, 0f, 0f,
      1f, -1f, 1f, 0f,
      -1f, 1f, 0f, 1f,
      1f, 1f, 1f, 1f
    )
    val bytes = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder())
    bytes.asFloatBuffer().put(quad)
    bytes.position(0)
    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVbo)
    GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, quad.size * 4, bytes, GLES20.GL_STATIC_DRAW)
    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
  }

  private fun uploadTexture(pixels: ByteArray): Int {
    val ids = IntArray(1)
    GLES20.glGenTextures(1, ids, 0)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    val buf = ByteBuffer.allocateDirect(pixels.size).order(ByteOrder.nativeOrder())
    buf.put(pixels)
    buf.position(0)
    GLES20.glTexImage2D(
      GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, size, size, 0,
      GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf
    )
    return ids[0]
  }

  // ---------------------------------------------------------------------------------------
  // Program compilation
  // ---------------------------------------------------------------------------------------

  private fun compileShader(type: Int, source: String): Pair<Int, String> {
    val shader = GLES20.glCreateShader(type)
    GLES20.glShaderSource(shader, source)
    GLES20.glCompileShader(shader)
    val status = IntArray(1)
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
    val log = GLES20.glGetShaderInfoLog(shader) ?: ""
    return if (status[0] != 0) shader to log else 0 to log
  }

  fun buildProgram(fragmentSource: String, varying: String): Program {
    val (vertex, vertexLog) = compileShader(GLES20.GL_VERTEX_SHADER, vertexShader(varying))
    val (fragment, fragmentLog) = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
    val program = GLES20.glCreateProgram()
    var linkLog = ""
    if (program != 0 && vertex != 0 && fragment != 0) {
      GLES20.glAttachShader(program, vertex)
      GLES20.glAttachShader(program, fragment)
      GLES20.glLinkProgram(program)
      val status = IntArray(1)
      GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
      linkLog = GLES20.glGetProgramInfoLog(program) ?: ""
      if (status[0] == 0) {
        GLES20.glDeleteProgram(program)
        return Program(0, vertexLog, fragmentLog, linkLog)
      }
      return Program(program, vertexLog, fragmentLog, linkLog)
    }
    if (program != 0) GLES20.glDeleteProgram(program)
    return Program(0, vertexLog, fragmentLog, "vertex=${vertex != 0} fragment=${fragment != 0}")
  }

  fun buildPreviewProgram(): Program = buildProgram(PREVIEW_SHADER, "vTexSamplingCoord")

  fun buildExportProgram(): Program = buildProgram(EXPORT_SHADER, "vTextureCoord")

  fun deleteProgram(program: Program) {
    if (program.id != 0) GLES20.glDeleteProgram(program.id)
  }

  // ---------------------------------------------------------------------------------------
  // Rendering
  // ---------------------------------------------------------------------------------------

  /**
   * Renders [look] through [program] and returns the RGB output as floats in 0..1, one
   * RGB triple per pixel, in the framebuffer's own (bottom-up) order.
   *
   * @param preview true for the Media3 preview program (`uTexSampler`), false for the export
   *   program (`uTexture` plus the chroma / mask / opacity inputs held neutral).
   * @param alpha input alpha to test the decoder's A=0 case, 255 for a normal opaque frame.
   */
  fun render(
    program: Program,
    look: ColorLookModel.LookParams,
    seed: Float,
    preview: Boolean,
    alpha: Int = 255
  ): FloatArray {
    check(program.ok) { "program did not compile" }
    makeCurrent()
    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
    GLES20.glViewport(0, 0, size, size)
    GLES20.glDisable(GLES20.GL_BLEND)
    GLES20.glDisable(GLES20.GL_DEPTH_TEST)
    GLES20.glUseProgram(program.id)

    val input = if (alpha == 255) inputTexture else alphaZeroTexture()
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, input)
    setInt(program.id, if (preview) "uTexSampler" else "uTexture", 0)

    // Colour-grading uniforms — the exact set ColorLookModel resolves for both paths.
    setFloat(program.id, "uBrightness", look.brightness)
    setFloat(program.id, "uContrast", look.contrast)
    setFloat(program.id, "uSaturation", look.saturation)
    setFloat(program.id, "uExposure", look.exposure)
    setFloat(program.id, "uTemperature", look.temperature)
    setFloat(program.id, "uTint", look.tint)
    setFloat(program.id, "uHighlights", look.highlights)
    setFloat(program.id, "uShadows", look.shadows)
    setFloat(program.id, "uWhites", look.whites)
    setFloat(program.id, "uBlacks", look.blacks)
    setFloat(program.id, "uFade", look.fade)
    setFloat(program.id, "uAutoEnhance", look.autoEnhance)
    setFloat(program.id, "uHdrBoost", look.hdrBoost)
    setFloat(program.id, "uColorFix", look.colorFix)
    setFloat(program.id, "uDenoise", look.denoise)
    setFloat(program.id, "uGlow", look.glow)
    setFloat(program.id, "uVignette", look.vignette)
    setFloat(program.id, "uGrain", look.grain)
    setFloat(program.id, "uGrainSeed", seed)
    setFloat(program.id, "uSharpness", look.sharpness)
    setFloat(program.id, "uClarity", look.clarity)
    setFloats(program.id, "uTexelSize", floatArrayOf(1f / size, 1f / size))

    val matrix = look.colorMatrix
    val offset = look.colorOffset
    if (matrix != null && offset != null) {
      setInt(program.id, "uUseColorMatrix", 1)
      setMatrix4(program.id, "uColorMatrix", matrix)
      setFloats(program.id, "uColorOffset", offset)
    } else {
      setInt(program.id, "uUseColorMatrix", 0)
    }

    if (!preview) {
      // Export-only inputs. Neutral values keep chroma key, masks, transitions and blending out
      // of the comparison; uPremultiply = 0 is what the real export passes for video textures.
      setFloat(program.id, "uPremultiply", 0f)
      setFloat(program.id, "uBlur", 0f)
      setFloat(program.id, "uEffectParam", 0f)
      setFloats(program.id, "uMotionVec", floatArrayOf(0f, 0f))
      setFloat(program.id, "uOpacity", 1f)
      setInt(program.id, "uChromaEnabled", 0)
      setInt(program.id, "uMaskEnabled", 0)
      setInt(program.id, "uBlendMode", 0)
      setInt(program.id, "uTransitionType", 0)
    }

    drawQuad(program.id)
    GLES20.glFinish()

    return readBackRgb()
  }

  /**
   * Reads the current framebuffer back as RGB floats in 0..1 and returns the pixel buffer to the
   * start, so [lastAlphaAt] can still sample it. The readback is RGBA, four bytes per pixel: the
   * RGB triple of pixel n lives at byte offsets 4n, 4n+1, 4n+2 (reading it as a flat byte stream
   * mixed the alpha byte into every fourth colour component).
   */
  fun readBackRgb(): FloatArray {
    readback.clear()
    GLES20.glReadPixels(0, 0, size, size, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readback)
    readback.position(0)
    val out = FloatArray(size * size * 3)
    for (pixel in 0 until size * size) {
      out[pixel * 3] = (readback.get(pixel * 4).toInt() and 0xFF) / 255f
      out[pixel * 3 + 1] = (readback.get(pixel * 4 + 1).toInt() and 0xFF) / 255f
      out[pixel * 3 + 2] = (readback.get(pixel * 4 + 2).toInt() and 0xFF) / 255f
    }
    return out
  }

  /** Makes this harness' EGL context current on the calling thread. */
  fun makeCurrent() {
    try {
      core.makeCurrent(surface!!)
    } catch (t: Throwable) {
      val eglError = try {
        "0x" + Integer.toHexString(android.opengl.EGL14.eglGetError())
      } catch (_: Throwable) {
        "unknown"
      }
      throw RuntimeException("EGL makeCurrent failed (eglGetError=$eglError, glVersion=${core.glVersion}, " +
        "GL_VERSION=${GLES20.glGetString(GLES20.GL_VERSION)})", t)
    }
  }

  /** The framebuffer this harness renders into, for callers that draw with their own GL code. */
  val fboId: Int get() = fbo

  /** Binds this harness' framebuffer and viewport (for callers that draw with their own GL code). */
  fun bindFbo() {
    makeCurrent()
    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
    GLES20.glViewport(0, 0, size, size)
  }

  /** Uploads a solid RGB colour as a texture of this harness' size. */
  fun uploadSolidRgb(r: Float, g: Float, b: Float): Int {
    val px = ByteArray(size * size * 4)
    var i = 0
    while (i < px.size) {
      px[i++] = (r.coerceIn(0f, 1f) * 255f).toInt().toByte()
      px[i++] = (g.coerceIn(0f, 1f) * 255f).toInt().toByte()
      px[i++] = (b.coerceIn(0f, 1f) * 255f).toInt().toByte()
      px[i++] = 0xFF.toByte()
    }
    return uploadTexture(px)
  }

  /** Output alpha of the last read-back frame, sampled from the raw RGBA bytes. */
  fun lastAlphaAt(pixel: Int): Int = readback.get(pixel * 4 + 3).toInt() and 0xFF

  private var cachedAlphaZero: Int = 0

  /** The same frame with A=0, i.e. what a MediaCodec decoder texture actually contains. */
  private fun alphaZeroTexture(): Int {
    if (cachedAlphaZero == 0) {
      val copy = sourceRgba.copyOf()
      var i = 3
      while (i < copy.size) {
        copy[i] = 0
        i += 4
      }
      cachedAlphaZero = uploadTexture(copy)
    }
    return cachedAlphaZero
  }

  private fun drawQuad(program: Int) {
    val position = GLES20.glGetAttribLocation(program, "aPosition")
    val texCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVbo)
    if (position >= 0) {
      GLES20.glEnableVertexAttribArray(position)
      GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, 0)
    }
    if (texCoord >= 0) {
      GLES20.glEnableVertexAttribArray(texCoord)
      GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 16, 8)
    }
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    if (position >= 0) GLES20.glDisableVertexAttribArray(position)
    if (texCoord >= 0) GLES20.glDisableVertexAttribArray(texCoord)
    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
  }

  private fun setFloat(program: Int, name: String, value: Float) {
    val loc = GLES20.glGetUniformLocation(program, name)
    if (loc >= 0) GLES20.glUniform1f(loc, value)
  }

  private fun setInt(program: Int, name: String, value: Int) {
    val loc = GLES20.glGetUniformLocation(program, name)
    if (loc >= 0) GLES20.glUniform1i(loc, value)
  }

  private fun setFloats(program: Int, name: String, values: FloatArray) {
    val loc = GLES20.glGetUniformLocation(program, name)
    if (loc >= 0) {
      when (values.size) {
        2 -> GLES20.glUniform2fv(loc, 1, values, 0)
        3 -> GLES20.glUniform3fv(loc, 1, values, 0)
        4 -> GLES20.glUniform4fv(loc, 1, values, 0)
      }
    }
  }

  private fun setMatrix4(program: Int, name: String, values: FloatArray) {
    val loc = GLES20.glGetUniformLocation(program, name)
    if (loc >= 0 && values.size == 16) GLES20.glUniformMatrix4fv(loc, 1, false, values, 0)
  }

  override fun close() {
    try {
      if (surface != null) {
        core.makeCurrent(surface!!)
        if (fbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        if (fboTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(fboTexture), 0)
        if (inputTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(inputTexture), 0)
        if (cachedAlphaZero != 0) GLES20.glDeleteTextures(1, intArrayOf(cachedAlphaZero), 0)
        if (quadVbo != 0) GLES20.glDeleteBuffers(1, intArrayOf(quadVbo), 0)
      }
    } catch (_: Throwable) {
      // Cleanup must never mask the test result.
    }
    try {
      surface?.let { core.releaseSurface(it) }
    } catch (_: Throwable) {
    }
    surface = null
    try {
      core.release()
    } catch (_: Throwable) {
    }
  }
}
