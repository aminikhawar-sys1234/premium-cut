package com.example.engine.composition.gpu

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.ahstudio.face.core.TrackedFace
import com.ahstudio.face.core.Vec2
import com.ahstudio.face.deformation.FaceWarpMapper
import com.ahstudio.face.deformation.FaceWarpRegistry
import com.ahstudio.face.overlay.ArOverlayRegistry
import com.ahstudio.face.rendering.FaceOverlayRenderPass
import com.ahstudio.face.rendering.PxOverlayItem
import com.example.domain.model.VideoClip
import com.example.engine.InterpolatedClipTransform
import com.example.ui.components.ar.ArFaceRenderer
import com.example.ui.components.ar.ArFilterCatalog
import com.example.ui.components.ar.ArFilterItem
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Compositor stage that draws the clip's AR face filter (crown, glasses, ears, ...) on the tracked
 * faces. Preview and export both go through it, so what the user sees is what is exported.
 *
 * The filter art is the very same drawing the live preview uses ([ArFaceRenderer.drawFaceArt]),
 * baked once per size into a texture and then placed per face by [FaceOverlayRenderPass]. Face
 * positions go through [FaceWarpMapper], i.e. container rotation, user rotation, flips, crop and
 * keyframed transforms are all honoured.
 *
 * [render] returns a transparent, premultiplied-alpha texture the size of the viewport, or 0 when
 * there is nothing to draw (no overlay on the clip, no face yet, or any failure).
 * Must be used on the GL thread that owns the compositor.
 */
class ArOverlayStage {
  private val pass = FaceOverlayRenderPass()
  private val fbo = GlFramebuffer()
  private var disabled = false

  /** Baked filter art. [heightRatio] = texture half-height / face-box half-height. */
  private class Art(val texId: Int, val heightRatio: Float)

  private val artCache = LinkedHashMap<String, Art>(16, 0.75f, true)

  fun render(
    clip: VideoClip?,
    timelinePosMs: Long,
    width: Int,
    height: Int,
    blocking: Boolean,
    transform: InterpolatedClipTransform? = null,
    /** The matrix-exact placement the compositor used for this frame's main clip (preferred). */
    placementOverride: FaceWarpMapper.Placement? = null
  ): Int {
    if (disabled || clip == null || width <= 0 || height <= 0) return 0
    val params = ArOverlayRegistry.paramsFor(clip.id) ?: return 0
    val filter = ArFilterCatalog.filters.firstOrNull { it.id == params.filterId } ?: return 0
    val source = FaceWarpRegistry.faceSource ?: return 0

    val faces = try {
      source.facesAt(clip.id, faceSourceTimeUs(clip, timelinePosMs), blocking)
    } catch (t: Throwable) {
      // Export (blocking) must not silently drop the AR layer: surface the tracking failure.
      if (blocking) throw t
      emptyList()
    }
    if (faces.isEmpty()) return 0

    val placement = placementOverride ?: faceClipPlacement(clip, timelinePosMs, width, height, transform)
    val nat = ((if (clip.isVideo) clip.naturalRotation else 0) % 360 + 360) % 360
    val rawW = (if (clip.width > 0) clip.width else width).toFloat()
    val rawH = (if (clip.height > 0) clip.height else height).toFloat()
    val transposed = nat == 90 || nat == 270
    val uprightW = if (transposed) rawH else rawW
    val uprightH = if (transposed) rawW else rawH

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

    val oneShotTextures = ArrayList<Int>()
    return try {
      fbo.setup(width, height)
      val items = ArrayList<PxOverlayItem>(faces.size)
      for (face in faces) {
        val g = arFaceGeometry(face, params.scale, params.offsetY, placement, uprightW, uprightH) ?: continue
        val art = obtainArt(filter, face, clip.flipHorizontal, g, params.opacity, oneShotTextures) ?: continue
        items += PxOverlayItem(
          centerX = g.centerX, centerY = g.centerY,
          halfWidthPx = g.halfW * ART_HALF_WIDTH_RATIO,
          halfHeightPx = g.halfH * art.heightRatio,
          rotationDegCcw = g.rotationDegCcw,
          opacity = 1f,                       // opacity is baked into the art, like in the preview
          textureId = art.texId,
          premultipliedTexture = true,
        )
      }
      if (items.isEmpty()) return 0

      // The overlay pass feeds its quad from client memory, which is only legal on the default VAO.
      GLES30.glBindVertexArray(0)
      GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
      GLES20.glDisable(GLES20.GL_DEPTH_TEST)
      GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
      fbo.bind()
      GLES20.glClearColor(0f, 0f, 0f, 0f)
      GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
      pass.renderPx(width, height, items)
      fbo.getTextureId()
    } catch (t: Throwable) {
      Log.e(TAG, "AR overlay failed; drawing nothing", t)
      if (blocking) throw t
      disabled = true
      0
    } finally {
      if (oneShotTextures.isNotEmpty()) {
        GLES20.glDeleteTextures(oneShotTextures.size, oneShotTextures.toIntArray(), 0)
      }
      trimCache()
      GLES30.glBindVertexArray(prevVao[0])
      GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, prevArrayBuf[0])
      GLES20.glUseProgram(prevProgram[0])
      GLES20.glActiveTexture(prevActive[0])
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prevTex2d[0])
      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prevFbo[0])
      GLES20.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3])
      if (blendWasOn) GLES20.glEnable(GLES20.GL_BLEND) else GLES20.glDisable(GLES20.GL_BLEND)
      if (depthWasOn) GLES20.glEnable(GLES20.GL_DEPTH_TEST)
      if (scissorWasOn) GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
    }
  }

  /**
   * Returns the baked art for [face]. Plain filters are cached by size; filters that draw the
   * face's own landmarks (mesh overlays) are baked per frame and deleted after the frame.
   */
  private fun obtainArt(
    filter: ArFilterItem,
    face: TrackedFace,
    flipHorizontal: Boolean,
    g: FaceGeometry,
    opacity: Float,
    oneShot: MutableList<Int>,
  ): Art? {
    val hwB = quantize(g.halfW, MIN_HALF, MAX_HALF_W)
    val hhB = quantize(g.halfH, MIN_HALF, MAX_HALF_H)
    val halfTexW = ART_HALF_WIDTH_RATIO * hwB
    val halfTexH = max(2.0f * hhB, 1.35f * hwB)
    val wt = ceil(2f * halfTexW).toInt()
    val ht = ceil(2f * halfTexH).toInt()
    val heightRatio = halfTexH / hhB

    val perFace = filter.isMeshOverlay
    val key = "${filter.id}|$hwB|$hhB|${(opacity * 100f).roundToInt()}|${filter.primaryColor.value}|${filter.secondaryColor.value}"
    if (!perFace) artCache[key]?.let { return it }

    val bmp = bake(filter, face, flipHorizontal, hwB, hhB, wt, ht, opacity)
    val tex = try { upload(bmp) } finally { bmp.recycle() }
    if (tex == 0) return null
    val art = Art(tex, heightRatio)
    if (perFace) oneShot += tex else artCache[key] = art
    return art
  }

  private fun bake(
    filter: ArFilterItem,
    face: TrackedFace,
    flipHorizontal: Boolean,
    hwB: Float,
    hhB: Float,
    wt: Int,
    ht: Int,
    opacity: Float,
  ): Bitmap {
    val image = ImageBitmap(wt, ht)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(wt.toFloat(), ht.toFloat())) {
      ArFaceRenderer.drawFaceArt(
        s = this,
        face = face,
        flipHorizontal = flipHorizontal,
        activeFilter = filter,
        showTrackingGrid = false,
        cx = wt / 2f, cy = ht / 2f,
        fw = 2f * hwB, fh = 2f * hhB,
        primaryColor = filter.primaryColor,
        secondaryColor = filter.secondaryColor,
        opacity = opacity,
      )
    }
    return image.asAndroidBitmap()
  }

  private fun upload(bmp: Bitmap): Int {
    val t = IntArray(1)
    GLES20.glGenTextures(1, t, 0)
    if (t[0] == 0) return 0
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
    return t[0]
  }

  private fun quantize(v: Float, lo: Float, hi: Float): Float =
    (ceil(v / STEP) * STEP).coerceIn(lo, hi)

  private fun trimCache() {
    while (artCache.size > MAX_CACHED_ART) {
      val eldest = artCache.entries.iterator().next()
      GLES20.glDeleteTextures(1, intArrayOf(eldest.value.texId), 0)
      artCache.remove(eldest.key)
    }
  }

  /** GL context lost: every handle is dead; they are rebuilt lazily on the next frame. */
  fun onContextLost() {
    artCache.clear()
    pass.reset()
    fbo.release()
    disabled = false
  }

  fun release() {
    for (a in artCache.values) GLES20.glDeleteTextures(1, intArrayOf(a.texId), 0)
    artCache.clear()
    pass.release()
    fbo.release()
  }

  private companion object {
    const val TAG = "ArOverlayStage"
    /** Texture half-width / face-box half-width: the widest art (visor, HUD ring) reaches ~1.15x. */
    const val ART_HALF_WIDTH_RATIO = 1.3f
    const val STEP = 8f
    const val MIN_HALF = 16f
    const val MAX_HALF_W = 400f
    const val MAX_HALF_H = 500f
    const val MAX_CACHED_ART = 12
  }
}
