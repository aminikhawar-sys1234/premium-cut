package com.example.engine.text

import android.content.Context
import android.graphics.Paint
import android.graphics.Path
import android.opengl.GLES20
import android.opengl.GLES30
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.util.Log
import com.example.domain.model.TextClip
import com.example.util.FontManager
import com.ute.core.Mat4
import com.ute.gpu.GlMeshRenderer
import com.ute.scene3d.TextMaterial
import com.ute.text3d.ExtrusionBuilder
import java.text.Normalizer
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Real 3D text: glyph outlines -> [ExtrusionBuilder] mesh -> [GlMeshRenderer] (PBR-ish shader,
 * material + light rig from [Text3DStyling]) -> MSAA offscreen target -> a viewport-sized RGBA texture
 * that drops into the same compositing path as the Canvas text bitmap.
 *
 * Output convention matches the Canvas path: premultiplied alpha, first texture row = top of the frame.
 *
 * Must be called on the GL thread that owns the compositor's context (ES 3.0+). If anything is
 * unsupported or fails, [render] returns 0 and the caller falls back to [TextLayerRenderer].
 *
 * Not drawn in this mode (Canvas-only features): background plate, stroke, shadow/glow, effectStyle and
 * per-character text animators. The caller routes clips with enabled animators to the Canvas path.
 */
class Text3DGlRenderer(private val context: Context) {

  private val mesh = GlMeshRenderer()

  // Offscreen target (re-created when the viewport size changes).
  private var tw = 0
  private var th = 0
  private var msaaFbo = 0
  private var colorRb = 0
  private var depthRb = 0
  private var resolveFbo = 0
  private var samples = 0
  private val texSizes = HashMap<Int, Long>()   // texId -> packed size we last allocated

  private var failed = false

  private data class CachedMesh(val mesh: ExtrusionBuilder.Mesh)
  private val meshCache = object : LinkedHashMap<String, CachedMesh>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedMesh>?) = size > MAX_MESHES
  }

  /** True if this context can run the mesh pipeline (GLES 3.0+) and it hasn't failed before. */
  fun isSupported(): Boolean {
    if (failed) return false
    val v = GLES20.glGetString(GLES20.GL_VERSION) ?: return false
    return v.contains("OpenGL ES 3")
  }

  /**
   * Renders [clip] at [currentPosMs]. Returns the texture id (re-using [existingTexId] when given),
   * or 0 when the mesh path can't be used for this frame.
   */
  fun render(clip: TextClip, currentPosMs: Long, width: Int, height: Int, existingTexId: Int): Int {
    if (!isSupported() || width < 16 || height < 16) return 0
    val saved = GlState.save()
    try {
      val state = TextLayerRenderer.evaluateAnimation(clip, currentPosMs)
      ensureTarget(width, height)
      val texId = prepareTexture(existingTexId, width, height)
      if (texId == 0) return 0

      GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, msaaFbo)
      GLES30.glViewport(0, 0, width, height)
      GLES30.glDisable(GLES30.GL_CULL_FACE)
      GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
      GLES30.glDepthMask(true)
      GLES30.glColorMask(true, true, true, true)
      GLES30.glClearColor(0f, 0f, 0f, 0f)
      GLES30.glClearDepthf(1f)
      GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

      val hidden = clip.isHidden || state.opacity <= 0f || state.visibleText.isEmpty()
      if (!hidden) drawClip(clip, state, width, height)

      // Resolve MSAA -> texture.
      GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, msaaFbo)
      GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, resolveFbo)
      GLES30.glFramebufferTexture2D(
        GLES30.GL_DRAW_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texId, 0
      )
      GLES30.glBlitFramebuffer(
        0, 0, width, height, 0, 0, width, height, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST
      )
      GLES30.glFramebufferTexture2D(
        GLES30.GL_DRAW_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, 0, 0
      )
      return texId
    } catch (t: Throwable) {
      Log.w(TAG, "3D mesh render failed, falling back to Canvas path", t)
      failed = true
      while (GLES20.glGetError() != GLES20.GL_NO_ERROR) { /* drain */ }
      return 0
    } finally {
      GlState.restore(saved)
    }
  }

  /** The context died: its objects are already gone, so only forget our handles (no glDelete*). */
  fun onContextLost() {
    msaaFbo = 0; resolveFbo = 0; colorRb = 0; depthRb = 0; tw = 0; th = 0
    texSizes.clear()
    mesh.onContextLost()
    failed = false
  }

  /** Frees GL objects; call when the compositor is released while its context is still valid. */
  fun release() {
    if (msaaFbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(msaaFbo), 0)
    if (resolveFbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(resolveFbo), 0)
    if (colorRb != 0) GLES30.glDeleteRenderbuffers(1, intArrayOf(colorRb), 0)
    if (depthRb != 0) GLES30.glDeleteRenderbuffers(1, intArrayOf(depthRb), 0)
    msaaFbo = 0; resolveFbo = 0; colorRb = 0; depthRb = 0; tw = 0; th = 0
    texSizes.clear()
    meshCache.clear()
    mesh.release()
  }

  // ---------------------------------------------------------------------------------------------
  // Drawing
  // ---------------------------------------------------------------------------------------------

  private fun drawClip(clip: TextClip, state: EvaluatedTextState, width: Int, height: Int) {
    val scaleFactor = width / 360f
    val fontPx = clip.fontSizeSp * scaleFactor            // geometry is built at scale 1; model matrix applies state.scale
    val depthPx = max(1f, if (state.depth3D > 0f) state.depth3D else clip.depth3D) * 1.25f * scaleFactor
    val bevelPx = (clip.bevelRadius3D * scaleFactor).coerceIn(0f, fontPx * 0.12f)
    val bevelSteps = if (bevelPx > 0f) 3 else 0

    val rawText = state.visibleText.let { t ->
      val c = if (clip.isAllCaps) t.uppercase() else t
      if (c.any { it.code > 0x7F }) Normalizer.normalize(c, Normalizer.Form.NFC) else c
    }
    val key = buildString {
      append(rawText).append('|').append(clip.fontFamily).append('|').append(clip.customFontPath)
      append('|').append(clip.fontWeight).append('|').append(clip.isItalic).append('|').append(clip.subtitleStyle)
      append('|').append(fontPx).append('|').append(clip.letterSpacing).append('|').append(clip.lineSpacing)
      append('|').append(clip.alignment).append('|').append(depthPx).append('|').append(bevelPx)
    }
    val cached = meshCache[key] ?: CachedMesh(buildMesh(clip, rawText, fontPx, depthPx, bevelPx, bevelSteps)).also {
      meshCache[key] = it
    }
    if (cached.mesh.triangleCount == 0) return

    // --- Material: front colour from the text colour, walls from color3D ---
    val base = FrontMaterial.resolve(clip, state.opacity)
    val rig = Text3DStyling.toLightRig(clip)

    // --- Camera: z=0 plane maps 1:1 to frame pixels (fov chosen from the distance). ---
    val d = height * 1.5f
    val fov = Math.toDegrees(2.0 * atan(height / (2.0 * d))).toFloat()
    val proj = Mat4.perspective(fov, width.toFloat() / height, d * 0.1f, d * 4f)
    val view = Mat4.translation(0f, 0f, -d)
    // Output rows are top-first, so mirror Y; that flips triangle winding (handled via flipWinding).
    val viewProj = Mat4.multiply(Mat4.multiply(Mat4.scale(1f, -1f, 1f), proj), view)

    // Position/rotation/scale mirror TextLayerRenderer (note it scales font size AND canvas by state.scale).
    val tx = state.posX * width / 2f
    val ty = -state.posY * height / 2f
    val s = state.scale * state.scale

    // "Direction" (bevelAngle3D + 45°) tilts the view slightly so the extrusion reads toward that side.
    val theta = Math.toRadians((clip.bevelAngle3D + 45f).toDouble())
    val viewTilt = VIEW_TILT_DEG
    val ax = -viewTilt * sin(theta).toFloat() + ROT_SIGN_X * state.rot3DX
    val ay = -viewTilt * cos(theta).toFloat() + ROT_SIGN_Y * state.rot3DY
    var model = Mat4.translation(tx, ty, 0f)
    model = Mat4.multiply(model, Mat4.rotationZ(-state.rotation - state.rot3DZ))
    model = Mat4.multiply(model, Mat4.rotationX(ax))
    model = Mat4.multiply(model, Mat4.rotationY(ay))
    model = Mat4.multiply(model, Mat4.scale(s, s, s))

    val style = GlMeshRenderer.MeshStyle(
      sideColor = clip.color3D.toInt(),
      gradientEnabled = clip.hasGradient,
      gradientEnd = clip.gradientColorEnd.toInt(),
      gradientVertical = clip.gradientDirection.equals("Vertical", true),
      flipWinding = true
    )
    mesh.drawMesh(cached.mesh, model, viewProj, base, rig, floatArrayOf(0f, 0f, d), style)
  }

  /** Lays the text out exactly like the Canvas path, then extrudes each line's outline. */
  private fun buildMesh(
    clip: TextClip, text: String, fontPx: Float, depthPx: Float, bevelPx: Float, bevelSteps: Int
  ): ExtrusionBuilder.Mesh {
    val typeface = FontManager.loadTypeface(
      context = context,
      fontFamily = clip.fontFamily,
      customFontPath = clip.customFontPath,
      fontWeight = if (clip.subtitleStyle.equals("Bold", true)) 900 else clip.fontWeight,
      isItalic = clip.isItalic
    )
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
      this.typeface = typeface
      textSize = fontPx
      letterSpacing = clip.letterSpacing / 10f
    }
    val rtl = text.any { it.code in 0x0600..0x06FF || it.code in 0x0750..0x077F || it.code in 0xFB50..0xFEFF }
    val hindi = text.any { it.code in 0x0900..0x097F }
    val spacing = when {
      rtl -> max(clip.lineSpacing, 1.35f)
      hindi -> max(clip.lineSpacing, 1.25f)
      else -> clip.lineSpacing
    }
    val align = when (clip.alignment.lowercase()) {
      "left" -> if (rtl) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL
      "right" -> if (rtl) Layout.Alignment.ALIGN_NORMAL else Layout.Alignment.ALIGN_OPPOSITE
      else -> Layout.Alignment.ALIGN_CENTER
    }
    val maxLine = text.split("\n").maxOfOrNull { paint.measureText(it) } ?: 0f
    val layoutWidth = max(maxLine.toInt() + 16, 32)
    val dir = if (rtl) TextDirectionHeuristics.ANYRTL_LTR else TextDirectionHeuristics.FIRSTSTRONG_LTR
    val layout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      StaticLayout.Builder.obtain(text, 0, text.length, paint, layoutWidth)
        .setAlignment(align).setTextDirection(dir).setLineSpacing(0f, spacing).setIncludePad(true).build()
    } else {
      @Suppress("DEPRECATION")
      StaticLayout(text, paint, layoutWidth, align, spacing, 0f, true)
    }

    // One path for the whole block, centred on the origin (y-down here; the builder flips to y-up).
    val cx = layout.width / 2f
    val cy = layout.height / 2f
    val path = Path()
    for (line in 0 until layout.lineCount) {
      val start = layout.getLineStart(line)
      var end = layout.getLineEnd(line)
      while (end > start && (text[end - 1] == '\n' || text[end - 1] == '\r')) end--
      if (end <= start) continue
      val x = layout.getLineLeft(line) - cx
      val y = layout.getLineBaseline(line) - cy
      paint.getTextPath(text, start, end, x, y, path)
    }
    return ExtrusionBuilder().build(path, depthPx, bevelPx, bevelSteps)
  }

  // ---------------------------------------------------------------------------------------------
  // GL target management
  // ---------------------------------------------------------------------------------------------

  private fun ensureTarget(w: Int, h: Int) {
    if (msaaFbo != 0 && tw == w && th == h) return
    if (msaaFbo != 0) {
      GLES30.glDeleteFramebuffers(1, intArrayOf(msaaFbo), 0)
      GLES30.glDeleteRenderbuffers(1, intArrayOf(colorRb), 0)
      GLES30.glDeleteRenderbuffers(1, intArrayOf(depthRb), 0)
      msaaFbo = 0; colorRb = 0; depthRb = 0
    }
    if (resolveFbo == 0) {
      val ids = IntArray(1); GLES30.glGenFramebuffers(1, ids, 0); resolveFbo = ids[0]
    }
    val maxS = IntArray(1); GLES30.glGetIntegerv(GLES30.GL_MAX_SAMPLES, maxS, 0)
    // Full-frame MSAA costs memory; use fewer samples on big frames.
    var want = if (w.toLong() * h > 1_500_000L) 2 else 4
    want = min(want, maxS[0])

    fun attempt(n: Int): Boolean {
      val rb = IntArray(2); GLES30.glGenRenderbuffers(2, rb, 0)
      colorRb = rb[0]; depthRb = rb[1]
      GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, colorRb)
      if (n > 0) GLES30.glRenderbufferStorageMultisample(GLES30.GL_RENDERBUFFER, n, GLES30.GL_RGBA8, w, h)
      else GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_RGBA8, w, h)
      GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, depthRb)
      if (n > 0) GLES30.glRenderbufferStorageMultisample(GLES30.GL_RENDERBUFFER, n, GLES30.GL_DEPTH_COMPONENT24, w, h)
      else GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT24, w, h)
      val f = IntArray(1); GLES30.glGenFramebuffers(1, f, 0); msaaFbo = f[0]
      GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, msaaFbo)
      GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_RENDERBUFFER, colorRb)
      GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, depthRb)
      val ok = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
      if (!ok) {
        GLES30.glDeleteFramebuffers(1, intArrayOf(msaaFbo), 0)
        GLES30.glDeleteRenderbuffers(2, intArrayOf(colorRb, depthRb), 0)
        msaaFbo = 0; colorRb = 0; depthRb = 0
      }
      return ok
    }
    samples = if (want >= 2 && attempt(want)) want else { if (attempt(0)) 0 else throw IllegalStateException("3D target incomplete") }
    tw = w; th = h
  }

  /** Allocates (or re-uses) an RGBA texture of exactly w×h. */
  private fun prepareTexture(existing: Int, w: Int, h: Int): Int {
    var id = existing
    if (id == 0) {
      val t = IntArray(1); GLES20.glGenTextures(1, t, 0); id = t[0]
      if (id == 0) return 0
    }
    val packed = (w.toLong() shl 32) or h.toLong()
    if (texSizes[id] != packed) {
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
      GLES20.glTexImage2D(
        GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
      )
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
      texSizes[id] = packed
    }
    return id
  }

  /** Material for the clip: tint = text colour, opacity = material opacity × clip opacity. */
  private object FrontMaterial {
    fun resolve(clip: TextClip, opacity: Float): TextMaterial {
      val preset = Text3DStyling.material(clip.material3D)
      return preset.copy(
        baseColorTint = clip.textColor.toInt(),
        opacity = (preset.opacity * opacity).coerceIn(0f, 1f)
      )
    }
  }

  /** The compositor shares this context, so everything we touch is restored afterwards. */
  private class GlState(
    val readFbo: Int, val drawFbo: Int, val fbo: Int, val viewport: IntArray, val program: Int,
    val vao: Int, val blend: Boolean, val depthTest: Boolean, val cull: Boolean, val scissor: Boolean,
    val depthMask: Boolean, val activeTex: Int, val tex2d: Int, val blendFunc: IntArray, val frontFace: Int
  ) {
    companion object {
      private fun i(p: Int): Int { val a = IntArray(1); GLES30.glGetIntegerv(p, a, 0); return a[0] }
      fun save(): GlState {
        val vp = IntArray(4); GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, vp, 0)
        return GlState(
          i(GLES30.GL_READ_FRAMEBUFFER_BINDING), i(GLES30.GL_DRAW_FRAMEBUFFER_BINDING), i(GLES30.GL_FRAMEBUFFER_BINDING),
          vp, i(GLES30.GL_CURRENT_PROGRAM), i(GLES30.GL_VERTEX_ARRAY_BINDING),
          GLES30.glIsEnabled(GLES30.GL_BLEND), GLES30.glIsEnabled(GLES30.GL_DEPTH_TEST),
          GLES30.glIsEnabled(GLES30.GL_CULL_FACE), GLES30.glIsEnabled(GLES30.GL_SCISSOR_TEST),
          i(GLES30.GL_DEPTH_WRITEMASK) != 0, i(GLES30.GL_ACTIVE_TEXTURE), i(GLES30.GL_TEXTURE_BINDING_2D),
          intArrayOf(
            i(GLES30.GL_BLEND_SRC_RGB), i(GLES30.GL_BLEND_DST_RGB),
            i(GLES30.GL_BLEND_SRC_ALPHA), i(GLES30.GL_BLEND_DST_ALPHA)
          ),
          i(GLES30.GL_FRONT_FACE)
        )
      }
      fun restore(s: GlState) {
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, s.readFbo)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, s.drawFbo)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, s.fbo)
        GLES30.glViewport(s.viewport[0], s.viewport[1], s.viewport[2], s.viewport[3])
        GLES30.glUseProgram(s.program)
        GLES30.glBindVertexArray(s.vao)
        fun set(cap: Int, on: Boolean) { if (on) GLES30.glEnable(cap) else GLES30.glDisable(cap) }
        set(GLES30.GL_BLEND, s.blend); set(GLES30.GL_DEPTH_TEST, s.depthTest)
        set(GLES30.GL_CULL_FACE, s.cull); set(GLES30.GL_SCISSOR_TEST, s.scissor)
        GLES30.glDepthMask(s.depthMask)
        GLES30.glBlendFuncSeparate(s.blendFunc[0], s.blendFunc[1], s.blendFunc[2], s.blendFunc[3])
        GLES30.glFrontFace(s.frontFace)
        GLES30.glActiveTexture(s.activeTex)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, s.tex2d)
      }
    }
  }

  companion object {
    private const val TAG = "Text3DGlRenderer"
    private const val MAX_MESHES = 8

    /** How far the view is tilted toward the "Direction" angle so the extrusion reads on screen. */
    private const val VIEW_TILT_DEG = 12f

    /**
     * Sign conventions for the animated rot3DX/rot3DY (which the Canvas path feeds to android.graphics.Camera).
     * If tilts look mirrored compared with Canvas mode on device, flip these two constants.
     */
    private const val ROT_SIGN_X = 1f
    private const val ROT_SIGN_Y = -1f
  }
}
