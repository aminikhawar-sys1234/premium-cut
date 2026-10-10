package com.example.engine.composition.gpu

import android.content.Context
import android.graphics.*
import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.ahstudio.face.deformation.FaceWarpMapper
import android.opengl.Matrix
import android.util.Log
import com.example.domain.model.*
import com.example.engine.KeyframeInterpolator
import com.example.engine.ai.cutout.SubjectCutoutRegistry
import com.example.engine.composition.ComposedFrame
import com.example.engine.composition.TransitionPreviewMotion
import com.example.engine.composition.ComposedOverlay
import com.example.engine.composition.ComposedSticker
import com.example.engine.composition.ComposedText
import com.example.engine.composition.StickerLayerRenderer
import com.example.engine.composition.VideoEffectRenderer
import com.example.engine.text.Text3DGlRenderer
import com.example.engine.text.TextLayerRenderer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max

/**
 * Production-Grade GPU Composition Renderer powered by Native C++ OpenGL ES 3.0 Engine.
 * Supports 1, 3, 10, 20, and 25+ simultaneous layers (Base Video, PIP Videos, Image Stickers,
 * Text Layers, Visual Effects) with 60 FPS performance, deterministic Z-ordering,
 * texture recycling, and premultiplied alpha blending.
 */
class GpuCompositionRenderer(private val context: Context) {
  private var nativeHandle: Long = 0L

  companion object {
    private const val TAG = "GpuCompositionRenderer"
    /** Shutter window (ms) over which a clip's on-screen motion is measured for Motion Blur. */
    private const val MOTION_BLUR_SHUTTER_MS = 33L
    /** Upper bound of the blur streak, as a fraction of the clip's texture. */
    private const val MOTION_BLUR_MAX = 0.12f

    fun isProceduralOverlayEffect(type: EffectType): Boolean {
      return when (type) {
        EffectType.FIRE_SPARK,
        EffectType.LASER_GRID, EffectType.BACKGROUND_NEON_GRID,
        EffectType.MANGA_LINE, EffectType.AI_MANGA_UNIVERSE,
        EffectType.BODY_AURA, EffectType.FIRE_AURA,
        EffectType.NEON_OUTLINE, EffectType.GLOW_EYES,
        EffectType.ANGEL_WINGS, EffectType.CYBER_WINGS,
        EffectType.LIGHTNING_BODY, EffectType.AI_SPEED_FORCE,
        EffectType.HEART_TRAIL, EffectType.FLORAL_CROWN,
        EffectType.CYBER_FACE, EffectType.CYBER_VISOR,
        EffectType.NEON_SPARKLE_CHEEKS, EffectType.DRAGON_FLAME,
        EffectType.MUSCLE_GLOW, EffectType.GHOST_CLONE,
        EffectType.FUNNY_BIG_EYES, EffectType.DARK_SHADOW_AURA,
        EffectType.DOUBLE_EXPOSURE,
        EffectType.CELEBRATE_CONFETTI, EffectType.CELEBRATE_FIREWORKS,
        EffectType.STAMP_ART,
        EffectType.SCREEN_SWAP_HOLO, EffectType.FACE_SWAP_AI,
        EffectType.AI_CYBERPUNK_CITY, EffectType.AI_BG_SWAP,
        EffectType.AI_PARTICLE_DISPERSE, EffectType.AI_NEON_TRAIL,
        EffectType.AI_SCI_FI_PORTAL, EffectType.AI_FREEZE_TIME,
        EffectType.AI_LIQUID_GOLD, EffectType.AI_GOLDEN_GOD,
        EffectType.AI_EXPANSION,
        EffectType.AI_FANTASY_KINGDOM,
        EffectType.AI_STYLE_MORPH, EffectType.AI_ANIME_WORLD,
        EffectType.ANIME_SILHOUETTE, EffectType.SKELETON_XRAY,
        EffectType.FACE_BEAUTY, EffectType.SLIM_SHAPE,
        EffectType.WATERCOLOR, EffectType.CHARCOAL_DRAW,
        EffectType.PASTEL_DREAM, EffectType.COLOR_POP_SPLASH,
        EffectType.Y2K_CHROME, EffectType.DIGICAM_2004,
        EffectType.OLD_PAPER_TEXTURE -> true
        else -> type.name.startsWith("VFX_BODY_") || type.name.startsWith("VFX_AI_") || type.name.startsWith("VFX_STICKER_")
      }
    }

    fun isPostProcessShaderEffect(type: EffectType): Boolean {
      return when (type) {
        EffectType.BLUR, EffectType.SOFT_FOCUS, EffectType.SHARPEN,
        EffectType.VFX_BLUR_1, EffectType.VFX_BLUR_3, EffectType.VFX_BLUR_8,
        EffectType.VFX_BLUR_9, EffectType.VFX_BLUR_10, EffectType.VFX_BLUR_11,
        EffectType.VFX_BLUR_12, EffectType.VFX_BLUR_13, EffectType.VFX_BLUR_15,
        EffectType.MOTION_BLUR, EffectType.VFX_VIRAL_1, EffectType.VFX_VIRAL_14,
        EffectType.VFX_BLUR_2, EffectType.VFX_BLUR_4, EffectType.VFX_BLUR_5,
        EffectType.VFX_BLUR_6, EffectType.VFX_BLUR_7, EffectType.VFX_BLUR_14,
        EffectType.GLOW, EffectType.HALO_GLOW,
        EffectType.VFX_LIGHT_4, EffectType.VFX_LIGHT_7, EffectType.VFX_LIGHT_14,
        EffectType.VFX_LIGHT_18, EffectType.VFX_LIGHT_19, EffectType.VFX_VIRAL_21,
        EffectType.VFX_VIRAL_22,
        EffectType.SHAKE, EffectType.CAMERA_MOVEMENT, EffectType.PARTY_CONFUSED,
        EffectType.VFX_VIRAL_6, EffectType.VFX_VIRAL_15, EffectType.VFX_GLITCH_14,
        EffectType.VFX_GLITCH_16, EffectType.VFX_GLITCH_19,
        EffectType.ZOOM, EffectType.SKATER_ZOOM, EffectType.VERTIGO_DOLLY, EffectType.WARP_SPEED,
        EffectType.VFX_VIRAL_12, EffectType.VFX_VIRAL_16, EffectType.VFX_VIRAL_17,
        EffectType.VFX_VIRAL_35, EffectType.VFX_3D_9,
        EffectType.SPIN, EffectType.VFX_VIRAL_20, EffectType.VFX_3D_8, EffectType.VFX_3D_15,
        EffectType.FLASH, EffectType.STROBE, EffectType.VFX_VIRAL_11, EffectType.VFX_VIRAL_36,
        EffectType.VFX_LIGHT_16, EffectType.VFX_VIRAL_23,
        EffectType.GLITCH, EffectType.CRT_TV, EffectType.VHS_VINTAGE, EffectType.AI_GLITCH_REALITY,
        EffectType.VFX_GLITCH_1, EffectType.VFX_GLITCH_2, EffectType.VFX_GLITCH_3,
        EffectType.VFX_GLITCH_6, EffectType.VFX_GLITCH_7, EffectType.VFX_GLITCH_8,
        EffectType.VFX_GLITCH_9, EffectType.VFX_GLITCH_10, EffectType.VFX_GLITCH_11,
        EffectType.VFX_GLITCH_13, EffectType.VFX_GLITCH_17, EffectType.VFX_GLITCH_18,
        EffectType.VFX_GLITCH_20, EffectType.VFX_RETRO_1, EffectType.VFX_RETRO_10,
        EffectType.VFX_VIRAL_4, EffectType.VFX_VIRAL_30, EffectType.VFX_VIRAL_31,
        EffectType.RGB_SPLIT, EffectType.VFX_VIRAL_5, EffectType.VFX_VIRAL_29,
        EffectType.VFX_GLITCH_12,
        EffectType.DISTORTION, EffectType.WAVE, EffectType.RIPPLE, EffectType.FISHEYE,
        EffectType.ACID_TRIP, EffectType.FUNNY_ALIEN_WARP, EffectType.VFX_GLITCH_4,
        EffectType.VFX_GLITCH_5, EffectType.VFX_GLITCH_15, EffectType.VFX_VIRAL_8,
        EffectType.VFX_VIRAL_9, EffectType.VFX_3D_2,
        EffectType.LENS_FLARE, EffectType.SOLAR_FLARE, EffectType.VFX_LIGHT_2,
        EffectType.LIGHT_LEAK, EffectType.GOLDEN_HOUR, EffectType.BOKEH, EffectType.PARTY_PRISM,
        EffectType.VFX_LIGHT_1, EffectType.VFX_LIGHT_3, EffectType.VFX_LIGHT_5,
        EffectType.VFX_LIGHT_10, EffectType.VFX_LIGHT_17, EffectType.VFX_RETRO_7,
        EffectType.VFX_VIRAL_24,
        EffectType.VIGNETTE, EffectType.NOISE, EffectType.THERMAL_CAMERA,
        EffectType.BLUEPRINT_CAD, EffectType.POP_ART_POSTER, EffectType.SEPIA_VINTAGE,
        EffectType.POLAROID_VINTAGE, EffectType.OIL_PAINTING, EffectType.HALFTONE_DOT,
        EffectType.COMIC_SKETCH, EffectType.MIRROR -> true
        else -> type.name.startsWith("VFX_BLUR_") || type.name.startsWith("VFX_GLITCH_") ||
                type.name.startsWith("VFX_LIGHT_") || type.name.startsWith("VFX_RETRO_") ||
                type.name.startsWith("VFX_DISTORT_") || type.name.startsWith("VFX_COLOR_")
      }
    }
    private const val FLOAT_SIZE_BYTES = 4
    private const val TRIANGLE_VERTICES_DATA_STRIDE_BYTES = 4 * FLOAT_SIZE_BYTES
    private const val POSITION_DATA_OFFSET = 0
    private const val TEXTURE_DATA_OFFSET = 2
    private const val STAB_MESH_COLS = 12
    private const val STAB_MESH_ROWS = 32
    private const val STAB_MESH_VERTEX_COUNT = STAB_MESH_COLS * STAB_MESH_ROWS * 6
    private const val MAX_UNTOUCHED_CACHE_FRAMES = 60
    /** Above the base clip (z 0), below PIP overlays (z 100+). */
    private const val AR_OVERLAY_Z = 50
    private const val AR_OVERLAY_LAYER_ID = 0x41524F56L
  }

  // Full-screen quad geometry: (x, y, u, v)
  private val quadVertices = floatArrayOf(
    -1.0f, -1.0f,  0.0f, 0.0f,
     1.0f, -1.0f,  1.0f, 0.0f,
    -1.0f,  1.0f,  0.0f, 1.0f,
     1.0f,  1.0f,  1.0f, 1.0f
  )

  private val vertexBuffer: FloatBuffer = ByteBuffer
    .allocateDirect(quadVertices.size * FLOAT_SIZE_BYTES)
    .order(ByteOrder.nativeOrder())
    .asFloatBuffer()
    .apply {
      put(quadVertices)
      position(0)
    }

  /**
   * The full-screen quad cut into a grid (x, y, u, v per vertex, plain triangles) so a per-vertex curved warp
   * bends the picture smoothly instead of only moving four corners.
   */
  private val stabMeshBuffer: FloatBuffer = run {
    val cols = STAB_MESH_COLS
    val rows = STAB_MESH_ROWS
    val data = FloatArray(STAB_MESH_VERTEX_COUNT * 4)
    var o = 0
    fun put(ix: Int, iy: Int) {
      val x = -1f + 2f * ix / cols
      val y = -1f + 2f * iy / rows
      data[o++] = x; data[o++] = y; data[o++] = (x + 1f) * 0.5f; data[o++] = (y + 1f) * 0.5f
    }
    for (iy in 0 until rows) for (ix in 0 until cols) {
      put(ix, iy); put(ix + 1, iy); put(ix, iy + 1)
      put(ix + 1, iy); put(ix + 1, iy + 1); put(ix, iy + 1)
    }
    ByteBuffer.allocateDirect(data.size * FLOAT_SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()
      .apply { put(data); position(0) }
  }

  // OpenGL Programs for OES conversion & post-process effects
  private var program2D = 0
  private var programOes = 0
  /** Main-video programs with the rolling-shutter row warp (see GpuShaders.STAB_VERTEX_SHADER); 0 = unavailable. */
  private var programStab2D = 0
  private var programStabOes = 0
  private var programTransition = 0
  private var programEffect = 0
  private var programBlit = 0

  // Framebuffers for OES conversion & multi-pass effect rendering
  private val fboMain2D = GlFramebuffer()
  private val colorGradeStage = ColorGradeStage()
  private val vfxStackStage = VfxStackStage()
  private val faceWarpStage = FaceWarpStage()
  private val bodyWarpStage = BodyWarpStage()
  private val faceBeautyStage = FaceBeautyStage()
  /** Second instances so an overlay pass cannot release the main clip's textures mid-frame. */
  private val overlayVfxStage = VfxStackStage()
  private val overlayFaceWarpStage = FaceWarpStage()
  private val overlayBodyWarpStage = BodyWarpStage()
  private val overlayFaceBeautyStage = FaceBeautyStage()
  private val arOverlayStage = ArOverlayStage()
  private val subjectCutoutStage = SubjectCutoutStage()
  private val fboTransitionA = GlFramebuffer()
  private val fboTransitionB = GlFramebuffer()
  private val shaderTransitions = ShaderTransitionCompositor()
  private val fboOverlayMap = mutableMapOf<String, GlFramebuffer>()
  private val fboA = GlFramebuffer()
  private val fboB = GlFramebuffer()

  // Cached Text & Sticker textures with frame-access tracking
  internal data class CachedTexture(
    val texId: Int,
    val width: Int,
    val height: Int,
    val hash: Int,
    var lastFrameUsed: Long = 0L
  )

  private val textTextureCache = mutableMapOf<String, CachedTexture>()

  /** Real-mesh 3D text (created on first use, on the GL thread). */
  private var text3dGl: Text3DGlRenderer? = null

  /** The GPU mesh path draws the text itself, so it can't combine with per-character animators. */
  private fun usesMesh3D(clip: TextClip): Boolean =
    clip.is3D && clip.trueMesh3D && clip.depth3D > 0f && clip.textAnimators.none { it.enabled }
  private val stickerTextureCache = mutableMapOf<String, CachedTexture>()
  private val imageTextureCache = mutableMapOf<String, CachedTexture>()
  private val proceduralEffectCache = mutableMapOf<String, CachedTexture>()
  private var proceduralBitmap: Bitmap? = null
  private var proceduralCanvas: Canvas? = null

  private var currentFrameCounter = 0L

  /**
   * The exact transform the main clip got this frame (single source of truth for everything that must stay
   * glued to the picture: AR overlay, face reshape). Null when the main picture came from the shader
   * transition path or there is no main clip.
   */
  private class MainGeometry(val placement: FaceWarpMapper.Placement, val opacity: Float)
  private var mainGeometry: MainGeometry? = null

  // Reusable Matrix buffers
  private val mvpMatrix = FloatArray(16)
  private val texMatrix = FloatArray(16)

  private var isInitialized = false
  private var currentViewportWidth = 0
  private var currentViewportHeight = 0

  fun initGl() {
    if (isInitialized) return

    program2D = GlShaderUtil.createProgram(GpuShaders.VERTEX_SHADER, GpuShaders.buildFragmentShader(isOes = false))
    programOes = GlShaderUtil.createProgram(GpuShaders.VERTEX_SHADER, GpuShaders.buildFragmentShader(isOes = true))
    programStab2D = runCatching {
      GlShaderUtil.createProgram(GpuShaders.STAB_VERTEX_SHADER, GpuShaders.buildFragmentShader(isOes = false))
    }.getOrDefault(0)
    programStabOes = runCatching {
      GlShaderUtil.createProgram(GpuShaders.STAB_VERTEX_SHADER, GpuShaders.buildFragmentShader(isOes = true))
    }.getOrDefault(0)
    programTransition = GlShaderUtil.createProgram(GpuShaders.VERTEX_SHADER, GpuShaders.TRANSITION_FRAGMENT_SHADER)
    programEffect = GlShaderUtil.createProgram(GpuShaders.VERTEX_SHADER, GpuShaders.EFFECT_FRAGMENT_SHADER)
    programBlit = GlShaderUtil.createProgram(GpuShaders.ENCODER_BLIT_VERTEX_SHADER, GpuShaders.BLIT_FRAGMENT_SHADER)

    isInitialized = true
  }

  /**
   * Main GPU composition entry point:
   * Converts OES video frames, prepares text/sticker/overlay textures, converts layer models into
   * native C++ layer representations, and renders the composed frame via NativeRenderBridge.
   */
  fun render(
    frame: ComposedFrame,
    mainTextureId: Int,
    isMainOes: Boolean,
    mainTexMatrix: FloatArray? = null,
    overlayTextures: Map<String, Int> = emptyMap(),
    overlayTexMatrices: Map<String, FloatArray> = emptyMap(),
    viewportWidth: Int,
    viewportHeight: Int,
    timelineAdjustments: VideoAdjustments = VideoAdjustments(),
    timelineFilter: FilterSettings = FilterSettings(),
    chromaKey: ChromaKeySettings = ChromaKeySettings(),
    flipYForEncoder: Boolean = false,
    flipXForEncoder: Boolean = false,
    transitionBefore: TransitionSourceTexture? = null,
    transitionAfter: TransitionSourceTexture? = null,
    /** Export: background-removal masks are computed synchronously per frame so output is deterministic. */
    deterministicMasks: Boolean = false
  ) {
    if (viewportWidth <= 0 || viewportHeight <= 0) return

    if (!isInitialized) {
      initGl()
    }

    currentFrameCounter++
    mainGeometry = null

    // Initialize or resize native C++ OpenGL ES 3.0 renderer
    if (viewportWidth != currentViewportWidth || viewportHeight != currentViewportHeight || nativeHandle == 0L) {
      currentViewportWidth = viewportWidth
      currentViewportHeight = viewportHeight
      if (nativeHandle == 0L && NativeRenderBridge.isLoaded) {
        nativeHandle = NativeRenderBridge.init(viewportWidth, viewportHeight)
      } else if (nativeHandle != 0L) {
        NativeRenderBridge.resize(nativeHandle, viewportWidth, viewportHeight)
      }
    }

    val nativeLayers = mutableListOf<NativeLayer>()

    val fxColorMatrix = if (frame.activeEffects.isNotEmpty()) {
      VideoEffectRenderer.calculateEffectColorMatrix(
        frame.activeEffects.map { it.clip },
        frame.timelinePosMs
      )
    } else null

    // 1. Process Main Base Video Clip
    // When both sides of an active transition are supplied (export path), render it with the
    // shader transition engine. Otherwise (or on any failure) fall through to the legacy path.
    val shaderTransitionTexId = renderShaderTransition(
      frame = frame,
      before = transitionBefore,
      after = transitionAfter,
      viewportWidth = viewportWidth,
      viewportHeight = viewportHeight,
      adjustments = timelineAdjustments,
      filter = timelineFilter,
      chromaKey = chromaKey,
      effectColorMatrix = fxColorMatrix
    )

    if (mainTextureId > 0 || shaderTransitionTexId > 0) {
      val main2dTexRaw = if (shaderTransitionTexId > 0) shaderTransitionTexId else processMainVideoTo2D(
        frame = frame,
        textureId = mainTextureId,
        isOes = isMainOes,
        customTexMatrix = mainTexMatrix,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
        adjustments = timelineAdjustments,
        filter = timelineFilter,
        chromaKey = chromaKey,
        effectColorMatrix = fxColorMatrix
      )

      // Colour engine grade (no-op for clips without a grade)
      val gradedTexId = colorGradeStage.grade(
        clipId = frame.activeClip?.id,
        timeMs = frame.timelinePosMs,
        srcTex = main2dTexRaw,
        width = viewportWidth,
        height = viewportHeight
      )

      // com.vfx effect stack (no-op for clips without one). Runs after the colour grade.
      val vfxTexId = vfxStackStage.apply(
        clipId = frame.activeClip?.id,
        stackJson = frame.activeClip?.vfxStackJson,
        clipTimeMs = frame.activeClip?.let { frame.timelinePosMs - it.timelineStartMs } ?: 0L,
        srcTex = gradedTexId,
        width = viewportWidth,
        height = viewportHeight
      )

      // Face Reshape sliders (Big Eyes / Slim Face / Jawline ...): mesh warp on the composed clip.
      // No-op for clips without reshape. The export path (deterministicMasks) waits for tracking
      // so exported frames are deterministic; the live preview never blocks the GL thread.
      val trackedBlocking = flipYForEncoder || deterministicMasks
      val faceTexId = faceWarpStage.apply(
        clip = frame.activeClip,
        timelinePosMs = frame.timelinePosMs,
        srcTex = vfxTexId,
        width = viewportWidth,
        height = viewportHeight,
        blocking = trackedBlocking,
        transform = frame.activeClipTransform,
        placementOverride = mainGeometry?.placement
      )

      val bodyTexId = bodyWarpStage.apply(
        clip = frame.activeClip,
        timelinePosMs = frame.timelinePosMs,
        srcTex = faceTexId,
        width = viewportWidth,
        height = viewportHeight,
        blocking = trackedBlocking,
        transform = frame.activeClipTransform,
        placementOverride = mainGeometry?.placement
      )

      val beautyTexId = faceBeautyStage.apply(
        clip = frame.activeClip,
        timelinePosMs = frame.timelinePosMs,
        srcTex = bodyTexId,
        width = viewportWidth,
        height = viewportHeight,
        blocking = trackedBlocking,
        transform = frame.activeClipTransform,
        placementOverride = mainGeometry?.placement
      )

      // Background removal (AI cutout): no-op for clips without it. Runs last so the mask is cut from
      // exactly what is displayed (after grade / FX / face / body).
      val main2dTexId = subjectCutoutStage.apply(
        clipId = frame.activeClip?.id,
        params = SubjectCutoutRegistry.paramsFor(frame.activeClip?.id),
        srcTex = beautyTexId,
        width = viewportWidth,
        height = viewportHeight,
        analysisAspect = viewportWidth.toFloat() / max(1, viewportHeight),
        analysisRotation = 0,
        frameKeyUs = frame.timelinePosMs * 1000L,
        blocking = deterministicMasks,
        isBaseLayer = true
      )

      // AR face filter (crown, glasses, ears ...). Drawn after the cutout so the mask cannot erase
      // parts that sit outside the subject (a crown above the head), and as its own layer so it
      // stays under PIP overlays, stickers and text.
      val arTexId = arOverlayStage.render(
        clip = frame.activeClip,
        timelinePosMs = frame.timelinePosMs,
        width = viewportWidth,
        height = viewportHeight,
        blocking = flipYForEncoder || deterministicMasks,
        transform = frame.activeClipTransform,
        placementOverride = mainGeometry?.placement
      )

      if (main2dTexId > 0) {
        val baseLayer = NativeLayer(
          id = frame.activeClip?.id?.hashCode()?.toLong() ?: 1L,
          textureId = main2dTexId,
          type = NativeLayerType.BASE_VIDEO,
          isVisible = true,
          zOrder = 0,
          opacity = 1.0f,
          blendMode = NativeBlendMode.NORMAL,
          useCustomMatrix = false
        )
        nativeLayers.add(baseLayer)
        if (arTexId > 0) {
          val arMatrix = FloatArray(16)
          Matrix.setIdentityM(arMatrix, 0)
          nativeLayers.add(
            NativeLayer(
              id = AR_OVERLAY_LAYER_ID,
              textureId = arTexId,
              type = NativeLayerType.VIDEO,
              isVisible = true,
              zOrder = AR_OVERLAY_Z,
              // Follows the clip's keyframed opacity / fades / transition so the overlay never outlives the face.
              opacity = (mainGeometry?.opacity ?: 1.0f).coerceIn(0f, 1f),
              blendMode = NativeBlendMode.PREMULTIPLIED,
              useCustomMatrix = true,
              transformMatrix = arMatrix
            )
          )
        }
      }
    }

    // 2. Process PIP Overlays (Deterministically ordered)
    for (i in frame.activeOverlays.indices) {
      val overlay = frame.activeOverlays[i]
      val overlayTexId = overlayTextures[overlay.clip.id]
      if (overlayTexId != null && overlayTexId > 0) {
        val isOvOes = overlay.clip.isVideo
        val ovRawTexId = processOverlayVideoTo2D(
          overlay = overlay,
          textureId = overlayTexId,
          isOes = isOvOes,
          customTexMatrix = overlayTexMatrices[overlay.clip.id],
          viewportWidth = viewportWidth,
          viewportHeight = viewportHeight,
          chromaKey = chromaKey,
          effectColorMatrix = fxColorMatrix,
          timelinePosMs = frame.timelinePosMs
        )

        val ovFxTex = applyTrackedEffects(
          clip = overlay.clip,
          srcTex = ovRawTexId,
          width = viewportWidth,
          height = viewportHeight,
          timelinePosMs = frame.timelinePosMs,
          blocking = flipYForEncoder || deterministicMasks,
          placement = fullBleedPlacement(viewportWidth, viewportHeight),
          vfx = overlayVfxStage,
          face = overlayFaceWarpStage,
          body = overlayBodyWarpStage,
          beauty = overlayFaceBeautyStage,
        )
        val ovSettled = settleOverlayTexture(overlay.clip.id, ovRawTexId, ovFxTex, viewportWidth, viewportHeight)

        // Background removal for PIP / overlay clips. The overlay texture is the clip's raw frame
        // (rotation is applied later by the layer matrix), so analyse it with its raw aspect and
        // rotate it upright for the segmenter only.
        val ovRawW = if (overlay.clip.width > 0) overlay.clip.width else viewportWidth
        val ovRawH = if (overlay.clip.height > 0) overlay.clip.height else viewportHeight
        val ov2dTexId = subjectCutoutStage.apply(
          clipId = overlay.clip.id,
          params = SubjectCutoutRegistry.paramsFor(overlay.clip.id),
          srcTex = ovSettled,
          width = viewportWidth,
          height = viewportHeight,
          analysisAspect = ovRawW.toFloat() / max(1, ovRawH),
          analysisRotation = if (isOvOes) overlay.clip.naturalRotation else 0,
          frameKeyUs = overlay.sourcePosMs * 1000L,
          blocking = deterministicMasks,
          isBaseLayer = false
        )

        if (ov2dTexId > 0) {
          val ovNaturalRot = if (isOvOes) overlay.clip.naturalRotation else 0
          val isRawTransposed = (ovNaturalRot == 90 || ovNaturalRot == 270)
          val rawW = if (overlay.clip.width > 0) overlay.clip.width else viewportWidth
          val rawH = if (overlay.clip.height > 0) overlay.clip.height else viewportHeight
          val ovDisplayW = if (isRawTransposed) rawH else rawW
          val ovDisplayH = if (isRawTransposed) rawW else rawH
          val ovDisplayAspect = ovDisplayW.toFloat() / max(1, ovDisplayH)
          val vpAspect = viewportWidth.toFloat() / max(1, viewportHeight)

          val ovScreenFitX: Float
          val ovScreenFitY: Float
          if (ovDisplayAspect > vpAspect) {
            ovScreenFitX = 1.0f
            ovScreenFitY = vpAspect / ovDisplayAspect
          } else {
            ovScreenFitX = ovDisplayAspect / vpAspect
            ovScreenFitY = 1.0f
          }

          val flipX = if (overlay.clip.flipHorizontal) -overlay.clip.cropScale else overlay.clip.cropScale
          val flipY = if (overlay.clip.flipVertical) -overlay.clip.cropScale else overlay.clip.cropScale

          val totalRot = ((ovNaturalRot.toFloat() + overlay.clip.rotationDegrees.toFloat() + overlay.rotation) % 360f + 360f) % 360f
          val isTransposed = (totalRot == 90f || totalRot == 270f)

          val baseScaleX = ovScreenFitX * overlay.scaleX * 0.5f * flipX
          val baseScaleY = ovScreenFitY * overlay.scaleY * 0.5f * flipY

          val localScaleX = if (isTransposed) baseScaleY else baseScaleX
          val localScaleY = if (isTransposed) baseScaleX else baseScaleY

          val ovMatrix = FloatArray(16)
          Matrix.setIdentityM(ovMatrix, 0)
          Matrix.translateM(ovMatrix, 0, overlay.clip.cropOffsetX + overlay.posX, -(overlay.clip.cropOffsetY + overlay.posY), 0f)
          Matrix.rotateM(ovMatrix, 0, com.example.engine.export.ExportOrientationPolicy.glRotationDegrees(totalRot), 0f, 0f, 1f)
          Matrix.scaleM(ovMatrix, 0, localScaleX, localScaleY, 1f)

          val calculatedZ = 100 + (i * 10)
          val overlayLayer = NativeLayer(
            id = overlay.clip.id.hashCode().toLong(),
            textureId = ov2dTexId,
            type = NativeLayerType.VIDEO,
            isVisible = true,
            zOrder = calculatedZ,
            opacity = overlay.opacity.coerceIn(0f, 1f),
            blendMode = mapBlendMode(overlay.blendMode),
            useCustomMatrix = true,
            transformMatrix = ovMatrix
          )
          nativeLayers.add(overlayLayer)
        }
      }
    }

    // 2.5. Process Procedural Visual Effects Overlay (Canvas drawing, Particles, Wings, Confetti, Sparks, Grids)
    val proceduralOverlayEffects = frame.activeEffects.filter { isProceduralOverlayEffect(it.effectType) }
    if (proceduralOverlayEffects.isNotEmpty()) {
      val fxCached = getOrCreateProceduralEffectTexture(frame, viewportWidth, viewportHeight)
      if (fxCached != null && fxCached.texId > 0) {
        fxCached.lastFrameUsed = currentFrameCounter
        val fxMatrix = FloatArray(16)
        Matrix.setIdentityM(fxMatrix, 0)
        val fxLayer = NativeLayer(
          id = 99998888L,
          textureId = fxCached.texId,
          type = NativeLayerType.VIDEO,
          isVisible = true,
          zOrder = 450,
          opacity = 1.0f,
          vScale = -1.0f,
          vOffset = 1.0f,
          blendMode = NativeBlendMode.PREMULTIPLIED,
          useCustomMatrix = true,
          transformMatrix = fxMatrix
        )
        nativeLayers.add(fxLayer)
      }
    }

    // 3. Process Sticker Layers (Deterministically ordered)
    for (i in frame.activeStickers.indices) {
      val sticker = frame.activeStickers[i]
      val cached = getOrCreateStickerTexture(sticker.clip, viewportWidth, viewportHeight)
      if (cached != null && cached.texId > 0) {
        cached.lastFrameUsed = currentFrameCounter
        val aspect = viewportWidth.toFloat() / max(1, viewportHeight)
        val stickerAspect = cached.width.toFloat() / max(1, cached.height)
        val scaleY = ((cached.height.toFloat() / viewportHeight) * 2f * sticker.scale).coerceAtLeast(0.01f)
        val scaleX = (scaleY * stickerAspect / aspect).coerceAtLeast(0.01f)

        val stkMatrix = FloatArray(16)
        Matrix.setIdentityM(stkMatrix, 0)
        Matrix.translateM(stkMatrix, 0, sticker.posX, -sticker.posY, 0f)
        Matrix.rotateM(stkMatrix, 0, -sticker.rotation, 0f, 0f, 1f)
        Matrix.scaleM(stkMatrix, 0, scaleX, scaleY, 1f)

        val calculatedZ = 500 + (i * 10)
        val stickerLayer = NativeLayer(
          id = sticker.clip.id.hashCode().toLong(),
          textureId = cached.texId,
          type = NativeLayerType.IMAGE_STICKER,
          isVisible = true,
          zOrder = calculatedZ,
          opacity = sticker.opacity.coerceIn(0f, 1f),
          vScale = -1.0f,
          vOffset = 1.0f,
          blendMode = NativeBlendMode.PREMULTIPLIED,
          useCustomMatrix = true,
          transformMatrix = stkMatrix
        )
        nativeLayers.add(stickerLayer)
      }
    }

    // 4. Process Text Layers (Deterministically ordered with highest Z-Order to guarantee visibility)
    for (i in frame.activeTexts.indices) {
      val text = frame.activeTexts[i]
      val cached = getOrCreateTextTexture(text.clip, text.currentPosMs, viewportWidth, viewportHeight)
      if (cached != null && cached.texId > 0) {
        cached.lastFrameUsed = currentFrameCounter

        // TextLayerRenderer draws text onto a full viewport bitmap at exact coordinates and scale.
        // Identity matrix maps the full-viewport texture 1:1 onto the GPU framebuffer.
        val txtMatrix = FloatArray(16)
        Matrix.setIdentityM(txtMatrix, 0)

        val calculatedZ = 1000 + (text.clip.trackIndex * 10) + i
        val textLayer = NativeLayer(
          id = text.clip.id.hashCode().toLong(),
          textureId = cached.texId,
          type = NativeLayerType.TEXT,
          isVisible = true,
          zOrder = calculatedZ,
          opacity = 1.0f,
          vScale = -1.0f,
          vOffset = 1.0f,
          blendMode = NativeBlendMode.PREMULTIPLIED,
          useCustomMatrix = true,
          transformMatrix = txtMatrix
        )
        nativeLayers.add(textLayer)
      }
    }

    // 5. Clean up stale textures periodically
    if (currentFrameCounter % 30L == 0L) {
      cleanStaleTextureCaches()
    }

    // 6. Render via Native C++ OpenGL ES 3.0 Engine or Kotlin OpenGL ES Fallback Compositor
    val postProcessEffects = frame.activeEffects.filter { isPostProcessShaderEffect(it.effectType) }
    val hasPostProcess = postProcessEffects.isNotEmpty()
    val isNativeActive = (nativeHandle != 0L)

    fboA.setup(viewportWidth, viewportHeight)
    fboA.bind()
    GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
    if (chromaKey.enabled && chromaKey.backgroundType == "Transparent") {
      GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f)
    } else {
      GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
    }
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

    if (isNativeActive) {
      // The native compositor only draws into its own offscreen target between begin/end; without
      // beginOffscreen it paints framebuffer 0 and endOffscreen() returns an empty texture (black output).
      NativeRenderBridge.beginOffscreen(nativeHandle)
      NativeRenderBridge.renderFrame(nativeHandle, nativeLayers)
    } else {
      renderNativeLayersKotlin(nativeLayers, viewportWidth, viewportHeight)
    }
    fboA.unbind()

    // 7. Apply Active Visual Effects (Multi-pass ping-ponging)
    var currentInputTex = if (isNativeActive) NativeRenderBridge.endOffscreen(nativeHandle) else fboA.getTextureId()
    if (currentInputTex <= 0) currentInputTex = fboA.getTextureId()

    if (hasPostProcess && currentInputTex > 0) {
      GLES20.glDisable(GLES20.GL_BLEND)
      fboB.setup(viewportWidth, viewportHeight)
      var currentOutputFbo = fboB

      for (i in postProcessEffects.indices) {
        val effect = postProcessEffects[i]
        currentOutputFbo.bind()
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glDisable(GLES20.GL_BLEND)
        applyEffect(
          effectType = effect.effectType,
          intensity = effect.intensity,
          timeSec = effect.timeInEffectMs / 1000f,
          inputTexId = currentInputTex,
          viewportWidth = viewportWidth,
          viewportHeight = viewportHeight
        )
        currentOutputFbo.unbind()
        currentInputTex = currentOutputFbo.getTextureId()
        currentOutputFbo = if (currentOutputFbo == fboB) fboA else fboB
      }
    }

    // 8. Final Blit Pass to Framebuffer 0 (MediaCodec encoder surface or display surface)
    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
    GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    GLES20.glDisable(GLES20.GL_BLEND)
    blitToSurface(currentInputTex, flipY = flipYForEncoder, flipX = flipXForEncoder)
  }

  private fun renderNativeLayersKotlin(
    layers: List<NativeLayer>,
    viewportWidth: Int,
    viewportHeight: Int
  ) {
    if (layers.isEmpty() || program2D == 0) return
    GLES20.glUseProgram(program2D)

    val uMVPMatrixHandle = GLES20.glGetUniformLocation(program2D, "uMVPMatrix")
    val uTexMatrixHandle = GLES20.glGetUniformLocation(program2D, "uTexMatrix")
    val uTextureHandle = GLES20.glGetUniformLocation(program2D, "uTexture")
    val uOpacityHandle = GLES20.glGetUniformLocation(program2D, "uOpacity")
    val uBrightnessHandle = GLES20.glGetUniformLocation(program2D, "uBrightness")
    val uContrastHandle = GLES20.glGetUniformLocation(program2D, "uContrast")
    val uSaturationHandle = GLES20.glGetUniformLocation(program2D, "uSaturation")
    val uExposureHandle = GLES20.glGetUniformLocation(program2D, "uExposure")
    val uTemperatureHandle = GLES20.glGetUniformLocation(program2D, "uTemperature")
    val uTintHandle = GLES20.glGetUniformLocation(program2D, "uTint")
    val uHighlightsHandle = GLES20.glGetUniformLocation(program2D, "uHighlights")
    val uShadowsHandle = GLES20.glGetUniformLocation(program2D, "uShadows")
    val uVignetteHandle = GLES20.glGetUniformLocation(program2D, "uVignette")
    val uGrainHandle = GLES20.glGetUniformLocation(program2D, "uGrain")
    val uSharpnessHandle = GLES20.glGetUniformLocation(program2D, "uSharpness")
    val uClarityResetHandle = GLES20.glGetUniformLocation(program2D, "uClarity")
    val uTexelSizeHandle = GLES20.glGetUniformLocation(program2D, "uTexelSize")
    val uChromaEnabledHandle = GLES20.glGetUniformLocation(program2D, "uChromaEnabled")
    val uBlurHandle = GLES20.glGetUniformLocation(program2D, "uBlur")
    val uEffectParamHandle = GLES20.glGetUniformLocation(program2D, "uEffectParam")

    if (uBrightnessHandle >= 0) GLES20.glUniform1f(uBrightnessHandle, 0f)
    if (uContrastHandle >= 0) GLES20.glUniform1f(uContrastHandle, 1f)
    if (uSaturationHandle >= 0) GLES20.glUniform1f(uSaturationHandle, 1f)
    if (uExposureHandle >= 0) GLES20.glUniform1f(uExposureHandle, 0f)
    if (uTemperatureHandle >= 0) GLES20.glUniform1f(uTemperatureHandle, 0f)
    if (uTintHandle >= 0) GLES20.glUniform1f(uTintHandle, 0f)
    if (uHighlightsHandle >= 0) GLES20.glUniform1f(uHighlightsHandle, 0f)
    if (uShadowsHandle >= 0) GLES20.glUniform1f(uShadowsHandle, 0f)
    if (uVignetteHandle >= 0) GLES20.glUniform1f(uVignetteHandle, 0f)
    if (uGrainHandle >= 0) GLES20.glUniform1f(uGrainHandle, 0f)
    if (uSharpnessHandle >= 0) GLES20.glUniform1f(uSharpnessHandle, 0f)
    if (uClarityResetHandle >= 0) GLES20.glUniform1f(uClarityResetHandle, 0f)
    if (uTexelSizeHandle >= 0) GLES20.glUniform2f(uTexelSizeHandle, 1.0f / max(1, viewportWidth), 1.0f / max(1, viewportHeight))
    if (uChromaEnabledHandle >= 0) GLES20.glUniform1i(uChromaEnabledHandle, 0)
    bindMaskUniforms(program2D, null)
    if (uBlurHandle >= 0) GLES20.glUniform1f(uBlurHandle, 0f)
    GLES20.glGetUniformLocation(program2D, "uMotionVec").let { if (it >= 0) GLES20.glUniform2f(it, 0f, 0f) }
    if (uEffectParamHandle >= 0) GLES20.glUniform1f(uEffectParamHandle, 0f)

    val sortedLayers = layers.filter { it.isVisible && it.textureId > 0 }.sortedBy { it.zOrder }

    for ((idx, layer) in sortedLayers.withIndex()) {
      if (idx == 0 && (layer.type == NativeLayerType.BASE_VIDEO || layer.type == NativeLayerType.VIDEO)) {
        GLES20.glDisable(GLES20.GL_BLEND)
      } else {
        GLES20.glEnable(GLES20.GL_BLEND)
        when (layer.blendMode) {
          NativeBlendMode.ADDITIVE -> GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
          NativeBlendMode.MULTIPLY -> GLES20.glBlendFunc(GLES20.GL_DST_COLOR, GLES20.GL_ONE_MINUS_SRC_ALPHA)
          NativeBlendMode.SCREEN -> GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_COLOR)
          NativeBlendMode.PREMULTIPLIED -> GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
          else -> {
            if (layer.type == NativeLayerType.TEXT || layer.type == NativeLayerType.IMAGE_STICKER) {
              GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            } else {
              GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            }
          }
        }
      }

      val mMatrix = FloatArray(16)
      if (layer.useCustomMatrix && layer.transformMatrix != null) {
        System.arraycopy(layer.transformMatrix, 0, mMatrix, 0, 16)
      } else {
        Matrix.setIdentityM(mMatrix, 0)
        Matrix.translateM(mMatrix, 0, layer.posX, -layer.posY, 0f)
        Matrix.rotateM(mMatrix, 0, -layer.rotation, 0f, 0f, 1f)
        Matrix.scaleM(mMatrix, 0, layer.scaleX, layer.scaleY, 1f)
      }
      GLES20.glUniformMatrix4fv(uMVPMatrixHandle, 1, false, mMatrix, 0)

      val tMatrix = FloatArray(16)
      Matrix.setIdentityM(tMatrix, 0)
      if (layer.uOffset != 0f || layer.vOffset != 0f || layer.uScale != 1f || layer.vScale != 1f) {
        Matrix.translateM(tMatrix, 0, layer.uOffset, layer.vOffset, 0f)
        Matrix.scaleM(tMatrix, 0, layer.uScale, layer.vScale, 1f)
      }
      GLES20.glUniformMatrix4fv(uTexMatrixHandle, 1, false, tMatrix, 0)

      GLES20.glUniform1f(uOpacityHandle, layer.opacity.coerceIn(0f, 1f))

      GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, layer.textureId)
      GLES20.glUniform1i(uTextureHandle, 0)

      drawQuad(program2D)
    }

    GLES20.glDisable(GLES20.GL_BLEND)
  }

  private fun processMainVideoTo2D(
    frame: ComposedFrame,
    textureId: Int,
    isOes: Boolean,
    customTexMatrix: FloatArray?,
    viewportWidth: Int,
    viewportHeight: Int,
    adjustments: VideoAdjustments,
    filter: FilterSettings,
    chromaKey: ChromaKeySettings,
    effectColorMatrix: android.graphics.ColorMatrix? = null,
    targetFbo: GlFramebuffer = fboMain2D
  ): Int {
    targetFbo.setup(viewportWidth, viewportHeight)
    targetFbo.bind()

    GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
    GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f)
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

    val clip = frame.activeClip
    // Warp stabilizer: screen-space correction (cancels measured camera shake) + zoom that hides the borders.
    val stabData = com.example.engine.ai.tracking.StabilizeCodec.decode(clip?.stabilize)
    val stabCorr = stabData?.let {
      com.example.engine.ai.tracking.StabilizeCodec.sample(it, frame.clipSourcePosMs * 1000L)
    }
    // Curved (per-row) rolling-shutter correction needs the subdivided-mesh program; otherwise the plain one.
    val rowProgram = if (isOes) programStabOes else programStab2D
    val useRowWarp = stabCorr != null && rowProgram > 0 &&
      com.example.engine.ai.tracking.StabilizeCodec.hasRowWarp(stabCorr)
    val program = when {
      useRowWarp -> rowProgram
      isOes -> programOes
      else -> program2D
    }
    GLES20.glUseProgram(program)

    Matrix.setIdentityM(mvpMatrix, 0)
    Matrix.setIdentityM(stabOuterMatrix, 0)
    var rowQuadX = 0f
    var rowQuadY = 0f
    if (stabData != null && stabCorr != null) {
      val c = stabCorr
      val z = stabData.zoom * c.scale.coerceIn(0.8f, 1.25f)
      // With the row warp the shift/rotate is applied after it (in the shader), so it goes to its own matrix.
      val shiftTarget = if (useRowWarp) stabOuterMatrix else mvpMatrix
      Matrix.translateM(shiftTarget, 0, c.dx * 2f, -c.dy * 2f, 0f)
      Matrix.rotateM(shiftTarget, 0, -c.rotationDeg, 0f, 0f, 1f)
      if (useRowWarp) {
        // The warp acts on zoomed screen coordinates: x_screen = z * x_content, so a y^2 term scales by 1/z.
        rowQuadX = c.rsX2 / z
        rowQuadY = c.rsY2 / z
      }
      if (com.example.engine.ai.tracking.StabilizeCodec.hasWarp(c)) {
        // Rolling-shutter skew/stretch + perspective correction (acts on screen coordinates, before shift/rotate).
        com.example.engine.ai.tracking.StabilizeCodec.warpMatrix(c, stabWarpMatrix)
        Matrix.multiplyMM(stabTmpMatrix, 0, mvpMatrix, 0, stabWarpMatrix, 0)
        System.arraycopy(stabTmpMatrix, 0, mvpMatrix, 0, 16)
      }
      Matrix.scaleM(mvpMatrix, 0, z, z, 1f)
    }
    var keyframeBlur = 0f
    var keyframeEffectParam = 0f
    // Per-clip Adjust / Video Quality override; project-wide values are the fallback.
    val baseAdjustments = frame.activeClip?.adjustments ?: adjustments
    var finalAdjustments = baseAdjustments
    var finalOpacity = 1.0f

    if (clip != null) {
      val kf = frame.activeClipTransform ?: KeyframeInterpolator.interpolate(clip, frame.timelinePosMs - clip.timelineStartMs)

      val naturalRot = if (isOes) clip.naturalRotation else 0
      val isRawTransposed = (naturalRot == 90 || naturalRot == 270)
      val rawW = if (clip.width > 0) clip.width else viewportWidth
      val rawH = if (clip.height > 0) clip.height else viewportHeight
      val effW = if (isRawTransposed) rawH else rawW
      val effH = if (isRawTransposed) rawW else rawH

      val cachedMain = imageTextureCache.values.find { it.texId == textureId }
      val displayW = cachedMain?.width ?: effW
      val displayH = cachedMain?.height ?: effH
      val displayAspect = displayW.toFloat() / max(1, displayH)
      val vpAspect = viewportWidth.toFloat() / max(1, viewportHeight)

      val screenFitX: Float
      val screenFitY: Float
      if (displayAspect > vpAspect) {
        screenFitX = 1.0f
        screenFitY = vpAspect / displayAspect
      } else {
        screenFitX = displayAspect / vpAspect
        screenFitY = 1.0f
      }

      val userScaleX = (if (clip.flipHorizontal) -clip.cropScale else clip.cropScale) * kf.scaleX
      val userScaleY = (if (clip.flipVertical) -clip.cropScale else clip.cropScale) * kf.scaleY

      val totalRot = ((naturalRot.toFloat() + clip.rotationDegrees.toFloat() + kf.rotation) % 360f + 360f) % 360f
      val isTransposed = (totalRot == 90f || totalRot == 270f)

      val localScaleX = (if (isTransposed) screenFitY else screenFitX) * userScaleX
      val localScaleY = (if (isTransposed) screenFitX else screenFitY) * userScaleY

      Matrix.translateM(mvpMatrix, 0, clip.cropOffsetX + kf.posX, -(clip.cropOffsetY + kf.posY), 0f)
      Matrix.rotateM(mvpMatrix, 0, com.example.engine.export.ExportOrientationPolicy.glRotationDegrees(totalRot), 0f, 0f, 1f)
      Matrix.scaleM(mvpMatrix, 0, localScaleX, localScaleY, 1f)

      if (clip.motionBlurEnabled) {
        // Real shutter blur: how far the clip moved on screen during the shutter window, expressed in the
        // clip's own texture space so the shader can smear along the actual motion path.
        val relMs = frame.timelinePosMs - clip.timelineStartMs
        val prev = KeyframeInterpolator.interpolate(clip, (relMs - MOTION_BLUR_SHUTTER_MS).coerceAtLeast(0L))
        val wx = kf.posX - prev.posX
        val wy = -(kf.posY - prev.posY)
        val rad = Math.toRadians(totalRot.toDouble())
        val lx = (wx * kotlin.math.cos(rad) - wy * kotlin.math.sin(rad)).toFloat()
        val ly = (wx * kotlin.math.sin(rad) + wy * kotlin.math.cos(rad)).toFloat()
        pendingMotionVecX = (lx / (2f * max(0.05f, kotlin.math.abs(localScaleX)))).coerceIn(-MOTION_BLUR_MAX, MOTION_BLUR_MAX)
        pendingMotionVecY = (ly / (2f * max(0.05f, kotlin.math.abs(localScaleY)))).coerceIn(-MOTION_BLUR_MAX, MOTION_BLUR_MAX)
      }

      finalOpacity *= kf.opacity
      keyframeBlur = kf.blur
      keyframeEffectParam = kf.effectParam
      finalAdjustments = baseAdjustments.copy(
        brightness = (baseAdjustments.brightness + kf.brightness).coerceIn(-1f, 1f),
        contrast = (baseAdjustments.contrast * kf.contrast).coerceAtLeast(0f),
        saturation = (baseAdjustments.saturation * kf.saturation).coerceAtLeast(0f)
      )
    }

    if (frame.activeEffects.isNotEmpty()) {
      val motion = VideoEffectRenderer.calculateMotionTransform(frame.activeEffects.map { it.clip }, frame.timelinePosMs)
      Matrix.scaleM(mvpMatrix, 0, motion.scaleX, motion.scaleY, 1f)
      Matrix.rotateM(mvpMatrix, 0, -motion.rotation, 0f, 0f, 1f)
      Matrix.translateM(mvpMatrix, 0, motion.translationX * 2f, -motion.translationY * 2f, 0f)
      finalOpacity *= motion.alpha
    }

    if (customTexMatrix != null) {
      System.arraycopy(customTexMatrix, 0, texMatrix, 0, 16)
    } else {
      Matrix.setIdentityM(texMatrix, 0)
      if (!isOes) {
        Matrix.translateM(texMatrix, 0, 0f, 1f, 0f)
        Matrix.scaleM(texMatrix, 0, 1f, -1f, 1f)
      }
    }

    var transitionClipStart = -1f
    var transitionClipEnd = -1f
    if (frame.activeTransition != null) {
      val tr = frame.activeTransition
      val p = tr.progress.coerceIn(0f, 1f)
      val incoming = clip?.id == tr.clipAfter.id
      val pose = TransitionPreviewMotion.resolve(tr.type, p, incoming)
      finalOpacity = (finalOpacity * pose.opacity).coerceIn(0f, 1f)
      if (pose.translateX != 0f || pose.translateY != 0f) {
        Matrix.translateM(mvpMatrix, 0, pose.translateX * 2f, -pose.translateY * 2f, 0f)
      }
      if (pose.scale != 1f) {
        Matrix.scaleM(mvpMatrix, 0, pose.scale, pose.scale, 1f)
      }
      if (pose.rotationDeg != 0f) {
        Matrix.rotateM(mvpMatrix, 0, pose.rotationDeg, 0f, 0f, 1f)
      }
      if (pose.extraBlur > 0f) {
        keyframeBlur = (keyframeBlur + pose.extraBlur).coerceIn(0f, 1f)
      }
      if (pose.brightness != 0f || pose.temperature != 0f || pose.flash > 0f) {
        finalAdjustments = finalAdjustments.copy(
          brightness = (finalAdjustments.brightness + pose.brightness + pose.flash * 0.85f).coerceIn(-1f, 1f),
          exposure = (finalAdjustments.exposure + pose.flash * 0.5f).coerceIn(-1f, 1f),
          temperature = (finalAdjustments.temperature + pose.temperature).coerceIn(-1f, 1f)
        )
      }
      if (pose.clipStart != null && pose.clipEnd != null) {
        transitionClipStart = pose.clipStart
        transitionClipEnd = pose.clipEnd
      }
    }

    val effectiveFilter = clip?.filter ?: filter
    bindCommonUniforms(
      program = program,
      textureId = textureId,
      isOes = isOes,
      opacity = finalOpacity,
      adjustments = finalAdjustments,
      filter = effectiveFilter,
      chromaKey = chromaKey,
      viewportWidth = viewportWidth,
      viewportHeight = viewportHeight,
      blur = keyframeBlur,
      effectParam = keyframeEffectParam,
      effectColorMatrix = effectColorMatrix,
      mask = clip?.let {
        com.example.engine.ai.OverlayTrackCodec.applyToMask(
          it.mask,
          it.motionTrackJson,
          frame.clipSourcePosMs * 1000L
        )
      }
    )

    val scissorTransition = transitionClipStart >= 0f && transitionClipEnd > transitionClipStart
    if (scissorTransition) {
      val x = (transitionClipStart * viewportWidth).toInt().coerceIn(0, viewportWidth)
      val right = (transitionClipEnd * viewportWidth).toInt().coerceIn(0, viewportWidth)
      GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
      GLES20.glScissor(x, 0, (right - x).coerceAtLeast(0), viewportHeight)
    }
    try {
      if (useRowWarp) {
        GLES20.glGetUniformLocation(program, "uOuterMatrix").let {
          if (it >= 0) GLES20.glUniformMatrix4fv(it, 1, false, stabOuterMatrix, 0)
        }
        GLES20.glGetUniformLocation(program, "uRowQuad").let {
          if (it >= 0) GLES20.glUniform2f(it, rowQuadX, rowQuadY)
        }
        drawGeometry(program, stabMeshBuffer, GLES20.GL_TRIANGLES, STAB_MESH_VERTEX_COUNT)
      } else {
        drawQuad(program)
      }
    } finally {
      if (scissorTransition) GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
    }
    if (targetFbo === fboMain2D && clip != null) {
      val finalMvp = FloatArray(16)
      if (useRowWarp) Matrix.multiplyMM(finalMvp, 0, stabOuterMatrix, 0, mvpMatrix, 0)
      else System.arraycopy(mvpMatrix, 0, finalMvp, 0, 16)
      mainGeometry = MainGeometry(
        FaceWarpMapper.Placement.fromRenderMatrix(
          viewportWidth, viewportHeight, if (isOes) clip.naturalRotation else 0, finalMvp
        ),
        finalOpacity.coerceIn(0f, 1f)
      )
    }
    targetFbo.unbind()

    return targetFbo.getTextureId()
  }

  /**
   * Renders the active transition with real clip A + clip B pixels through the shader transition
   * engine. Returns the viewport-sized 2D texture holding the blended result, or 0 when this path
   * does not apply (no transition, missing source, ES2 context, engine failure) so the caller uses
   * its legacy single-texture behaviour.
   */
  private fun renderShaderTransition(
    frame: ComposedFrame,
    before: TransitionSourceTexture?,
    after: TransitionSourceTexture?,
    viewportWidth: Int,
    viewportHeight: Int,
    adjustments: VideoAdjustments,
    filter: FilterSettings,
    chromaKey: ChromaKeySettings,
    effectColorMatrix: android.graphics.ColorMatrix?
  ): Int {
    val tr = frame.activeTransition ?: return 0
    if (tr.type == TransitionType.NONE || before == null || after == null) return 0
    if (before.textureId <= 0 || after.textureId <= 0) return 0
    if (!shaderTransitions.isAvailable()) return 0

    val pos = frame.timelinePosMs
    val frameA = frame.copy(
      activeClip = tr.clipBefore,
      clipSourcePosMs = tr.clipBefore.timelineToSourceMs(pos),
      activeTransition = null,
      activeClipTransform = null
    )
    val frameB = frame.copy(
      activeClip = tr.clipAfter,
      clipSourcePosMs = tr.clipAfter.timelineToSourceMs(pos),
      activeTransition = null,
      activeClipTransform = null
    )
    val texA = processMainVideoTo2D(
      frameA, before.textureId, before.isOes, before.texMatrix,
      viewportWidth, viewportHeight, adjustments, filter, chromaKey, effectColorMatrix,
      targetFbo = fboTransitionA
    )
    val texB = processMainVideoTo2D(
      frameB, after.textureId, after.isOes, after.texMatrix,
      viewportWidth, viewportHeight, adjustments, filter, chromaKey, effectColorMatrix,
      targetFbo = fboTransitionB
    )
    if (texA <= 0 || texB <= 0) return 0

    fboMain2D.setup(viewportWidth, viewportHeight)
    val ok = shaderTransitions.render(
      type = tr.type,
      progress = tr.progress,
      texA = texA,
      texB = texB,
      width = viewportWidth,
      height = viewportHeight,
      outputFbo = fboMain2D.getFboId()
    )
    return if (ok) fboMain2D.getTextureId() else 0
  }

  /**
   * Motion Blur for PIP / overlay clips: same shutter-window measurement as the main clip, using the overlay's
   * own keyframes. Result goes to [pendingMotionVecX]/[pendingMotionVecY] (consumed by the next bindCommonUniforms).
   */
  private fun computeOverlayMotionVector(
    overlay: ComposedOverlay,
    timelinePosMs: Long,
    viewportWidth: Int,
    viewportHeight: Int
  ) {
    val clip = overlay.clip
    val relMs = timelinePosMs - clip.timelineStartMs
    val prev = KeyframeInterpolator.interpolate(clip, (relMs - MOTION_BLUR_SHUTTER_MS).coerceAtLeast(0L))

    val naturalRot = if (clip.isVideo) clip.naturalRotation else 0
    val rawTransposed = naturalRot == 90 || naturalRot == 270
    val rawW = if (clip.width > 0) clip.width else viewportWidth
    val rawH = if (clip.height > 0) clip.height else viewportHeight
    val dispAspect = (if (rawTransposed) rawH else rawW).toFloat() / max(1, if (rawTransposed) rawW else rawH)
    val vpAspect = viewportWidth.toFloat() / max(1, viewportHeight)
    val fitX = if (dispAspect > vpAspect) 1f else dispAspect / vpAspect
    val fitY = if (dispAspect > vpAspect) vpAspect / dispAspect else 1f

    val totalRot = ((naturalRot.toFloat() + clip.rotationDegrees.toFloat() + overlay.rotation) % 360f + 360f) % 360f
    val transposed = totalRot == 90f || totalRot == 270f
    val baseX = fitX * overlay.scaleX * 0.5f * clip.cropScale
    val baseY = fitY * overlay.scaleY * 0.5f * clip.cropScale
    val localScaleX = if (transposed) baseY else baseX
    val localScaleY = if (transposed) baseX else baseY

    val wx = overlay.posX - prev.posX
    val wy = -(overlay.posY - prev.posY)
    val rad = Math.toRadians(totalRot.toDouble())
    val lx = (wx * kotlin.math.cos(rad) - wy * kotlin.math.sin(rad)).toFloat()
    val ly = (wx * kotlin.math.sin(rad) + wy * kotlin.math.cos(rad)).toFloat()
    pendingMotionVecX = (lx / (2f * max(0.05f, kotlin.math.abs(localScaleX)))).coerceIn(-MOTION_BLUR_MAX, MOTION_BLUR_MAX)
    pendingMotionVecY = (ly / (2f * max(0.05f, kotlin.math.abs(localScaleY)))).coerceIn(-MOTION_BLUR_MAX, MOTION_BLUR_MAX)
  }

  private fun processOverlayVideoTo2D(
    overlay: ComposedOverlay,
    textureId: Int,
    isOes: Boolean,
    customTexMatrix: FloatArray? = null,
    viewportWidth: Int,
    viewportHeight: Int,
    chromaKey: ChromaKeySettings,
    effectColorMatrix: android.graphics.ColorMatrix? = null,
    /** Timeline time of this frame; needed to sample the overlay's previous keyframe state for Motion Blur. */
    timelinePosMs: Long = overlay.clip.timelineStartMs
  ): Int {
    val fbo = fboOverlayMap.getOrPut(overlay.clip.id) { GlFramebuffer() }
    fbo.setup(viewportWidth, viewportHeight)
    fbo.bind()

    GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
    GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f)
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

    val program = if (isOes) programOes else program2D
    GLES20.glUseProgram(program)

    Matrix.setIdentityM(mvpMatrix, 0)
    if (customTexMatrix != null) {
      System.arraycopy(customTexMatrix, 0, texMatrix, 0, 16)
    } else {
      Matrix.setIdentityM(texMatrix, 0)
      if (!isOes) {
        Matrix.translateM(texMatrix, 0, 0f, 1f, 0f)
        Matrix.scaleM(texMatrix, 0, 1f, -1f, 1f)
      }
    }

    if (overlay.clip.motionBlurEnabled) {
      computeOverlayMotionVector(overlay, timelinePosMs, viewportWidth, viewportHeight)
    }

    val overlayBase = overlay.clip.adjustments
    val overlayAdj = if (overlayBase == null) {
      VideoAdjustments(
        brightness = overlay.brightness,
        contrast = overlay.contrast,
        saturation = overlay.saturation
      )
    } else {
      overlayBase.copy(
        brightness = (overlayBase.brightness + overlay.brightness).coerceIn(-1f, 1f),
        contrast = (overlayBase.contrast * overlay.contrast).coerceAtLeast(0f),
        saturation = (overlayBase.saturation * overlay.saturation).coerceAtLeast(0f)
      )
    }

    bindCommonUniforms(
      program = program,
      textureId = textureId,
      isOes = isOes,
      opacity = 1.0f,
      adjustments = overlayAdj,
      filter = overlay.clip.filter ?: FilterSettings(),
      chromaKey = chromaKey,
      viewportWidth = viewportWidth,
      viewportHeight = viewportHeight,
      blur = overlay.blur,
      effectParam = overlay.effectParam,
      effectColorMatrix = effectColorMatrix,
      mask = com.example.engine.ai.OverlayTrackCodec.applyToMask(
        overlay.clip.mask,
        overlay.clip.motionTrackJson,
        overlay.sourcePosMs * 1000L
      )
    )

    drawQuad(program)
    fbo.unbind()

    return fbo.getTextureId()
  }

  private fun getOrCreateTextTexture(
    clip: TextClip,
    currentPosMs: Long,
    viewportWidth: Int,
    viewportHeight: Int
  ): CachedTexture? {
    val hasAnim = clip.animationType != "None" || clip.animation3D != "None" || clip.keyframes.isNotEmpty() ||
      clip.textAnimators.any { it.enabled }
    val animTimeStep = if (hasAnim) (currentPosMs / 33L).toInt() else 0

    val hash = clip.text.hashCode() xor
        clip.textColor.toInt() xor
        clip.fontSizeSp.toInt() xor
        clip.fontWeight.hashCode() xor
        clip.backgroundColor.toInt() xor
        clip.strokeColor.toInt() xor
        clip.strokeWidth.toInt() xor
        clip.fontFamily.hashCode() xor
        (clip.customFontPath?.hashCode() ?: 0) xor
        clip.animationType.hashCode() xor
        clip.animation3D.hashCode() xor
        clip.effectStyle.hashCode() xor
        clip.depth3D.toInt() xor
        clip.bevelAngle3D.toInt() xor
        clip.color3D.hashCode() xor
        clip.bevelRadius3D.hashCode() xor
        clip.trueMesh3D.hashCode() xor
        clip.isAllCaps.hashCode() xor
        clip.letterSpacing.hashCode() xor
        clip.lineSpacing.hashCode() xor
        clip.textAnimators.hashCode() xor
        clip.material3D.hashCode() xor
        clip.lightPreset3D.hashCode() xor
        clip.lightAngle3D.hashCode() xor
        clip.lightIntensity3D.hashCode() xor
        clip.is3D.hashCode() xor
        clip.hasGradient.hashCode() xor
        clip.gradientColorStart.toInt() xor
        clip.gradientColorEnd.toInt() xor
        clip.hasGlow.hashCode() xor
        clip.hasShadow.hashCode() xor
        clip.opacity.hashCode() xor
        clip.scale.hashCode() xor
        clip.rotation.hashCode() xor
        clip.posX.hashCode() xor
        clip.posY.hashCode() xor
        (clip.trackBindJson?.hashCode() ?: 0) xor
        clip.alignment.hashCode() xor
        animTimeStep xor
        viewportWidth xor
        (viewportHeight shl 16)

    val cached = textTextureCache[clip.id]
    if (cached != null && cached.hash == hash && cached.texId > 0) {
      return cached
    }

    // True 3D: mesh pipeline renders straight into a texture; on any failure fall through to Canvas.
    if (usesMesh3D(clip)) {
      val renderer = text3dGl ?: Text3DGlRenderer(context).also { text3dGl = it }
      val reuse = if (cached != null) cached.texId else 0
      val meshTex = renderer.render(clip, currentPosMs, viewportWidth, viewportHeight, reuse)
      if (meshTex > 0) {
        val entry = CachedTexture(meshTex, viewportWidth, viewportHeight, hash, currentFrameCounter)
        textTextureCache[clip.id] = entry
        return entry
      }
    }

    // Animated text re-rasterises on (almost) every frame: reuse one scratch bitmap instead of
    // allocating a fresh full-viewport ARGB bitmap each time.
    val scratch = obtainTextScratchBitmap(viewportWidth, viewportHeight)
    val bitmap = TextLayerRenderer.renderToBitmap(
      clip = clip,
      currentPosMs = currentPosMs,
      width = viewportWidth,
      height = viewportHeight,
      context = context,
      reuse = scratch
    ) ?: return null

    val oldTexId = if (cached != null && cached.hash != hash) cached.texId else 0
    // Same size as the previous upload for this clip -> reuse the texture storage (glTexSubImage2D)
    // instead of reallocating a multi-megabyte texture every animated frame.
    val reuseTexId = cached?.takeIf {
      it.texId > 0 && it.width == bitmap.width && it.height == bitmap.height
    }?.texId ?: 0
    val texId = GlShaderUtil.uploadBitmapToTexture(
      bitmap,
      if (reuseTexId != 0) reuseTexId else oldTexId,
      reuseStorage = reuseTexId != 0
    )
    // The bitmap belongs to this renderer when it is the scratch one; never recycle that.
    if (bitmap !== textScratchBitmap) bitmap.recycle()

    if (texId == 0) return null
    val entry = CachedTexture(texId, bitmap.width, bitmap.height, hash, currentFrameCounter)
    textTextureCache[clip.id] = entry
    return entry
  }

  /** Single scratch bitmap reused for every text layer of the current viewport size. */
  private var textScratchBitmap: Bitmap? = null

  /**
   * Returns the reusable text bitmap for this viewport size (allocating it on the first frame or
   * after a size change). No clearing here: [TextLayerRenderer.renderToBitmap] clears the bitmap it
   * is handed, and clearing twice would memset a full-screen ARGB buffer twice per text frame.
   */
  private fun obtainTextScratchBitmap(width: Int, height: Int): Bitmap {
    val targetW = max(width, 64)
    val targetH = max(height, 64)
    val current = textScratchBitmap
    if (current != null && !current.isRecycled && current.width == targetW && current.height == targetH) {
      return current
    }
    current?.recycle()
    val fresh = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
    textScratchBitmap = fresh
    return fresh
  }

  private fun getOrCreateStickerTexture(
    clip: StickerClip,
    viewportWidth: Int,
    viewportHeight: Int
  ): CachedTexture? {
    val hash = clip.emojiOrAsset.hashCode() xor clip.badgeType.hashCode() xor viewportWidth
    val cached = stickerTextureCache[clip.id]
    if (cached != null && cached.hash == hash && cached.texId > 0) {
      return cached
    }

    val isBadge = clip.badgeType != null
    val targetWidth = if (isBadge) {
      (160f * (viewportWidth.toFloat() / 600f)).toInt().coerceIn(128, 384)
    } else {
      (80f * (viewportWidth.toFloat() / 600f)).toInt().coerceIn(64, 256)
    }
    val targetHeight = if (isBadge) {
      (targetWidth * 0.42f).toInt().coerceIn(54, 160)
    } else {
      targetWidth
    }

    val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    StickerLayerRenderer.draw(
      canvas = canvas,
      clip = StickerLayerRenderer.forLocalSprite(clip),
      currentPosMs = clip.timelineStartMs,
      width = targetWidth,
      height = targetHeight
    )

    val oldTexId = cached?.texId ?: 0
    val texId = GlShaderUtil.uploadBitmapToTexture(bitmap, oldTexId)
    bitmap.recycle()

    if (texId == 0) return null
    val entry = CachedTexture(texId, targetWidth, targetHeight, hash, currentFrameCounter)
    stickerTextureCache[clip.id] = entry
    return entry
  }

  fun uploadImageTexture(id: String, bitmap: Bitmap): Int {
    val existing = imageTextureCache[id]
    if (existing != null && existing.hash == bitmap.generationId && existing.width == bitmap.width && existing.height == bitmap.height) {
      existing.lastFrameUsed = currentFrameCounter
      return existing.texId
    }
    // Re-uploading a same-size bitmap (e.g. one extracted frame per export frame on the
    // retriever fallback path) goes into the existing texture storage instead of reallocating it.
    val reuse = existing != null && existing.texId > 0 &&
      existing.width == bitmap.width && existing.height == bitmap.height
    val texId = GlShaderUtil.uploadBitmapToTexture(bitmap, existing?.texId ?: 0, reuseStorage = reuse)
    val entry = CachedTexture(texId, bitmap.width, bitmap.height, bitmap.generationId, currentFrameCounter)
    imageTextureCache[id] = entry
    return texId
  }

  internal fun getOrCreateProceduralEffectTexture(
    frame: ComposedFrame,
    viewportWidth: Int,
    viewportHeight: Int
  ): CachedTexture? {
    val overlayEffects = frame.activeEffects.filter { isProceduralOverlayEffect(it.effectType) }
    if (overlayEffects.isEmpty() || viewportWidth <= 0 || viewportHeight <= 0) return null

    val timeStep = (frame.timelinePosMs / 33L).toInt()
    var hash = timeStep xor viewportWidth xor (viewportHeight shl 16)
    for (eff in overlayEffects) {
      hash = hash xor eff.clip.id.hashCode() xor eff.effectType.hashCode() xor (eff.intensity * 1000f).toInt()
    }

    val key = "procedural_overlay_fx"
    val cached = proceduralEffectCache[key]
    if (cached != null && cached.hash == hash && cached.width == viewportWidth && cached.height == viewportHeight) {
      cached.lastFrameUsed = currentFrameCounter
      return cached
    }

    val currentBmp = proceduralBitmap
    val bmp = if (currentBmp != null && !currentBmp.isRecycled && currentBmp.width == viewportWidth && currentBmp.height == viewportHeight) {
      currentBmp.eraseColor(0)
      currentBmp
    } else {
      currentBmp?.recycle()
      val newBmp = Bitmap.createBitmap(viewportWidth, viewportHeight, Bitmap.Config.ARGB_8888)
      proceduralBitmap = newBmp
      proceduralCanvas = Canvas(newBmp)
      newBmp
    }
    val canvas = proceduralCanvas ?: Canvas(bmp)

    VideoEffectRenderer.renderEffectsOnCanvas(
      canvas = canvas,
      activeEffects = overlayEffects.map { it.clip },
      currentPosMs = frame.timelinePosMs,
      width = viewportWidth,
      height = viewportHeight
    )

    val oldTexId = cached?.texId ?: 0
    // The procedural overlay is redrawn every ~33 ms step at the same viewport size: keep the
    // texture storage and upload into it instead of reallocating the full-viewport texture.
    val reuseStorage = cached != null && cached.texId > 0 && cached.width == bmp.width && cached.height == bmp.height
    val texId = GlShaderUtil.uploadBitmapToTexture(bmp, if (reuseStorage) cached.texId else oldTexId, reuseStorage)

    if (texId == 0) return null
    val entry = CachedTexture(texId, viewportWidth, viewportHeight, hash, currentFrameCounter)
    proceduralEffectCache[key] = entry
    return entry
  }

  private fun cleanStaleTextureCaches() {
    val textToDel = textTextureCache.filter { currentFrameCounter - it.value.lastFrameUsed > MAX_UNTOUCHED_CACHE_FRAMES }
    for ((id, tex) in textToDel) {
      if (tex.texId > 0) {
        GLES20.glDeleteTextures(1, intArrayOf(tex.texId), 0)
      }
      textTextureCache.remove(id)
    }

    val stickerToDel = stickerTextureCache.filter { currentFrameCounter - it.value.lastFrameUsed > MAX_UNTOUCHED_CACHE_FRAMES }
    for ((id, tex) in stickerToDel) {
      if (tex.texId > 0) {
        GLES20.glDeleteTextures(1, intArrayOf(tex.texId), 0)
      }
      stickerTextureCache.remove(id)
    }

    val fxToDel = proceduralEffectCache.filter { currentFrameCounter - it.value.lastFrameUsed > MAX_UNTOUCHED_CACHE_FRAMES }
    for ((id, tex) in fxToDel) {
      if (tex.texId > 0) {
        GLES20.glDeleteTextures(1, intArrayOf(tex.texId), 0)
      }
      proceduralEffectCache.remove(id)
    }
  }

  private fun mapBlendMode(modeStr: String): NativeBlendMode {
    return when (modeStr.lowercase().trim()) {
      "screen" -> NativeBlendMode.SCREEN
      "multiply" -> NativeBlendMode.MULTIPLY
      "add", "additive" -> NativeBlendMode.ADDITIVE
      "premultiplied" -> NativeBlendMode.PREMULTIPLIED
      else -> NativeBlendMode.NORMAL
    }
  }

  /** Texture-space motion-blur vector for the next [bindCommonUniforms] call; consumed (reset) by it. */
  private val stabWarpMatrix = FloatArray(16)
  private val stabOuterMatrix = FloatArray(16)
  private val stabTmpMatrix = FloatArray(16)
  private var pendingMotionVecX = 0f
  private var pendingMotionVecY = 0f

  private fun bindCommonUniforms(
    program: Int,
    textureId: Int,
    isOes: Boolean,
    opacity: Float,
    adjustments: VideoAdjustments,
    filter: FilterSettings,
    chromaKey: ChromaKeySettings,
    viewportWidth: Int,
    viewportHeight: Int,
    blur: Float = 0f,
    effectParam: Float = 0f,
    effectColorMatrix: android.graphics.ColorMatrix? = null,
    mask: MaskSettings? = null
  ) {
    val uMVPMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
    val uTexMatrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix")
    val uTextureHandle = GLES20.glGetUniformLocation(program, "uTexture")
    val uOpacityHandle = GLES20.glGetUniformLocation(program, "uOpacity")
    val uBlurHandle = GLES20.glGetUniformLocation(program, "uBlur")
    val uEffectParamHandle = GLES20.glGetUniformLocation(program, "uEffectParam")

    GLES20.glUniformMatrix4fv(uMVPMatrixHandle, 1, false, mvpMatrix, 0)
    GLES20.glUniformMatrix4fv(uTexMatrixHandle, 1, false, texMatrix, 0)
    GLES20.glUniform1f(uOpacityHandle, opacity)
    if (uBlurHandle >= 0) GLES20.glUniform1f(uBlurHandle, blur)
    if (uEffectParamHandle >= 0) GLES20.glUniform1f(uEffectParamHandle, effectParam)
    val uMotionVecHandle = GLES20.glGetUniformLocation(program, "uMotionVec")
    if (uMotionVecHandle >= 0) GLES20.glUniform2f(uMotionVecHandle, pendingMotionVecX, pendingMotionVecY)
    pendingMotionVecX = 0f
    pendingMotionVecY = 0f

    val target = if (isOes) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    GLES20.glBindTexture(target, textureId)
    GLES20.glUniform1i(uTextureHandle, 0)

    val uBrightnessHandle = GLES20.glGetUniformLocation(program, "uBrightness")
    val uContrastHandle = GLES20.glGetUniformLocation(program, "uContrast")
    val uSaturationHandle = GLES20.glGetUniformLocation(program, "uSaturation")
    val uExposureHandle = GLES20.glGetUniformLocation(program, "uExposure")
    val uTemperatureHandle = GLES20.glGetUniformLocation(program, "uTemperature")
    val uTintHandle = GLES20.glGetUniformLocation(program, "uTint")
    val uHighlightsHandle = GLES20.glGetUniformLocation(program, "uHighlights")
    val uShadowsHandle = GLES20.glGetUniformLocation(program, "uShadows")
    val uWhitesHandle = GLES20.glGetUniformLocation(program, "uWhites")
    val uBlacksHandle = GLES20.glGetUniformLocation(program, "uBlacks")
    val uFadeHandle = GLES20.glGetUniformLocation(program, "uFade")
    val uAutoEnhanceHandle = GLES20.glGetUniformLocation(program, "uAutoEnhance")
    val uHdrBoostHandle = GLES20.glGetUniformLocation(program, "uHdrBoost")
    val uColorFixHandle = GLES20.glGetUniformLocation(program, "uColorFix")
    val uDenoiseHandle = GLES20.glGetUniformLocation(program, "uDenoise")
    val uVignetteHandle = GLES20.glGetUniformLocation(program, "uVignette")
    val uGrainHandle = GLES20.glGetUniformLocation(program, "uGrain")
    val uSharpnessHandle = GLES20.glGetUniformLocation(program, "uSharpness")
    val uClarityHandle = GLES20.glGetUniformLocation(program, "uClarity")
    val uTexelSizeHandle = GLES20.glGetUniformLocation(program, "uTexelSize")

    if (uBrightnessHandle >= 0) GLES20.glUniform1f(uBrightnessHandle, adjustments.brightness)
    if (uContrastHandle >= 0) GLES20.glUniform1f(uContrastHandle, adjustments.contrast)
    if (uSaturationHandle >= 0) GLES20.glUniform1f(uSaturationHandle, adjustments.saturation)
    if (uExposureHandle >= 0) GLES20.glUniform1f(uExposureHandle, adjustments.exposure)
    if (uTemperatureHandle >= 0) GLES20.glUniform1f(uTemperatureHandle, adjustments.temperature)
    if (uTintHandle >= 0) GLES20.glUniform1f(uTintHandle, adjustments.tint)
    if (uHighlightsHandle >= 0) GLES20.glUniform1f(uHighlightsHandle, adjustments.highlights)
    if (uShadowsHandle >= 0) GLES20.glUniform1f(uShadowsHandle, adjustments.shadows)
    if (uWhitesHandle >= 0) GLES20.glUniform1f(uWhitesHandle, adjustments.whites)
    if (uBlacksHandle >= 0) GLES20.glUniform1f(uBlacksHandle, adjustments.blacks)
    if (uFadeHandle >= 0) GLES20.glUniform1f(uFadeHandle, adjustments.fade)
    if (uAutoEnhanceHandle >= 0) GLES20.glUniform1f(uAutoEnhanceHandle, adjustments.autoEnhance)
    if (uHdrBoostHandle >= 0) GLES20.glUniform1f(uHdrBoostHandle, (adjustments.hdrBoost + adjustments.colorCorrect).coerceIn(0f, 1f))
    if (uColorFixHandle >= 0) GLES20.glUniform1f(uColorFixHandle, adjustments.colorFix)
    if (uDenoiseHandle >= 0) GLES20.glUniform1f(uDenoiseHandle, (adjustments.denoise + adjustments.antiFlicker * 0.5f).coerceIn(0f, 1f))
    if (uVignetteHandle >= 0) GLES20.glUniform1f(uVignetteHandle, adjustments.vignette)
    if (uGrainHandle >= 0) GLES20.glUniform1f(uGrainHandle, adjustments.grain)
    if (uSharpnessHandle >= 0) GLES20.glUniform1f(uSharpnessHandle, adjustments.sharpness)
    if (uClarityHandle >= 0) GLES20.glUniform1f(uClarityHandle, (adjustments.clarity + adjustments.superClarity).coerceIn(0f, 1f))
    if (uTexelSizeHandle >= 0) GLES20.glUniform2f(uTexelSizeHandle, 1.0f / max(1, viewportWidth), 1.0f / max(1, viewportHeight))

    val uChromaEnabledHandle = GLES20.glGetUniformLocation(program, "uChromaEnabled")
    if (uChromaEnabledHandle >= 0) {
      if (chromaKey.enabled) {
        val color = chromaKey.targetColor.toInt()
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f

        GLES20.glUniform1i(uChromaEnabledHandle, 1)
        val keyLoc = GLES20.glGetUniformLocation(program, "uChromaKeyColor").let { if (it >= 0) it else GLES20.glGetUniformLocation(program, "uKeyColor") }
        if (keyLoc >= 0) {
          GLES20.glUniform3f(keyLoc, r, g, b)
        }
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uChromaSimilarity"), chromaKey.similarity)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uChromaSmoothness"), max(0.001f, chromaKey.smoothness))
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uChromaSpill"), chromaKey.spillSuppression)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uChromaEdge"), chromaKey.edgeControl)

        val bgType = if (chromaKey.backgroundType == "SolidColor") 1 else 0
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uChromaBgType"), bgType)
        val bgColor = chromaKey.backgroundColor.toInt()
        val bgR = Color.red(bgColor) / 255f
        val bgG = Color.green(bgColor) / 255f
        val bgB = Color.blue(bgColor) / 255f
        val bgA = Color.alpha(bgColor) / 255f
        GLES20.glUniform4f(GLES20.glGetUniformLocation(program, "uChromaBgColor"), bgR, bgG, bgB, bgA)
      } else {
        GLES20.glUniform1i(uChromaEnabledHandle, 0)
      }
    }

    bindMaskUniforms(program, mask)

    val uColorMatrixHandle = GLES20.glGetUniformLocation(program, "uColorMatrix")
    val uColorOffsetHandle = GLES20.glGetUniformLocation(program, "uColorOffset")
    val uUseColorMatrixHandle = GLES20.glGetUniformLocation(program, "uUseColorMatrix")

    val filterMatrix = com.example.engine.composition.ColorFilterGenerator.getFilterMatrix(filter.type, filter.intensity)
    val finalMatrix = when {
      filterMatrix != null && effectColorMatrix != null -> {
        val combined = android.graphics.ColorMatrix(filterMatrix)
        combined.postConcat(effectColorMatrix)
        combined
      }
      filterMatrix != null -> filterMatrix
      effectColorMatrix != null -> effectColorMatrix
      else -> null
    }

    if (finalMatrix != null && uUseColorMatrixHandle >= 0) {
      val arr = finalMatrix.array
      val glMat = floatArrayOf(
        arr[0], arr[5], arr[10], arr[15],
        arr[1], arr[6], arr[11], arr[16],
        arr[2], arr[7], arr[12], arr[17],
        arr[3], arr[8], arr[13], arr[18]
      )
      val glOffset = floatArrayOf(
        arr[4] / 255.0f,
        arr[9] / 255.0f,
        arr[14] / 255.0f,
        arr[19] / 255.0f
      )
      if (uColorMatrixHandle >= 0) GLES20.glUniformMatrix4fv(uColorMatrixHandle, 1, false, glMat, 0)
      if (uColorOffsetHandle >= 0) GLES20.glUniform4fv(uColorOffsetHandle, 1, glOffset, 0)
      GLES20.glUniform1i(uUseColorMatrixHandle, 1)
    } else if (uUseColorMatrixHandle >= 0) {
      GLES20.glUniform1i(uUseColorMatrixHandle, 0)
    }
  }

  private fun bindMaskUniforms(program: Int, mask: MaskSettings?) {
    val enabledLoc = GLES20.glGetUniformLocation(program, "uMaskEnabled")
    if (enabledLoc < 0) return
    val enabled = GpuMaskUniforms.isEnabled(mask)
    GLES20.glUniform1i(enabledLoc, if (enabled) 1 else 0)
    if (!enabled || mask == null) return
    val shapeLoc = GLES20.glGetUniformLocation(program, "uMaskShape")
    if (shapeLoc >= 0) GLES20.glUniform1i(shapeLoc, GpuMaskUniforms.shapeId(mask))
    val posLoc = GLES20.glGetUniformLocation(program, "uMaskPos")
    if (posLoc >= 0) GLES20.glUniform2f(posLoc, mask.posX, mask.posY)
    val sizeLoc = GLES20.glGetUniformLocation(program, "uMaskSize")
    if (sizeLoc >= 0) GLES20.glUniform2f(sizeLoc, mask.width, mask.height)
    val rotLoc = GLES20.glGetUniformLocation(program, "uMaskRotation")
    if (rotLoc >= 0) GLES20.glUniform1f(rotLoc, mask.rotation)
    val featherLoc = GLES20.glGetUniformLocation(program, "uMaskFeather")
    if (featherLoc >= 0) GLES20.glUniform1f(featherLoc, mask.feather)
    val opacityLoc = GLES20.glGetUniformLocation(program, "uMaskOpacity")
    if (opacityLoc >= 0) GLES20.glUniform1f(opacityLoc, mask.opacity)
    val invLoc = GLES20.glGetUniformLocation(program, "uMaskInverted")
    if (invLoc >= 0) GLES20.glUniform1i(invLoc, if (mask.isInverted) 1 else 0)
  }

  private fun drawQuad(program: Int) = drawGeometry(program, vertexBuffer, GLES20.GL_TRIANGLE_STRIP, 4)

  private fun drawGeometry(program: Int, buffer: FloatBuffer, mode: Int, vertexCount: Int) {
    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    val aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
    val aTextureCoordHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")

    if (aPositionHandle >= 0) {
      buffer.position(POSITION_DATA_OFFSET)
      GLES20.glVertexAttribPointer(
        aPositionHandle, 2, GLES20.GL_FLOAT, false,
        TRIANGLE_VERTICES_DATA_STRIDE_BYTES, buffer
      )
      GLES20.glEnableVertexAttribArray(aPositionHandle)
    }

    if (aTextureCoordHandle >= 0) {
      buffer.position(TEXTURE_DATA_OFFSET)
      GLES20.glVertexAttribPointer(
        aTextureCoordHandle, 2, GLES20.GL_FLOAT, false,
        TRIANGLE_VERTICES_DATA_STRIDE_BYTES, buffer
      )
      GLES20.glEnableVertexAttribArray(aTextureCoordHandle)
    }

    GLES20.glDrawArrays(mode, 0, vertexCount)

    if (aPositionHandle >= 0) GLES20.glDisableVertexAttribArray(aPositionHandle)
    if (aTextureCoordHandle >= 0) GLES20.glDisableVertexAttribArray(aTextureCoordHandle)
  }

  fun onContextLost() {
    isInitialized = false
    colorGradeStage.onContextLost()
    vfxStackStage.onContextLost()
    faceWarpStage.onContextLost()
    bodyWarpStage.onContextLost()
    faceBeautyStage.onContextLost()
    overlayVfxStage.onContextLost()
    overlayFaceWarpStage.onContextLost()
    overlayBodyWarpStage.onContextLost()
    overlayFaceBeautyStage.onContextLost()
    arOverlayStage.onContextLost()
    subjectCutoutStage.onContextLost()
    textTextureCache.clear()
    stickerTextureCache.clear()
    imageTextureCache.clear()
    proceduralEffectCache.clear()
    text3dGl?.onContextLost()
    proceduralBitmap?.recycle()
    proceduralBitmap = null
    proceduralCanvas = null
    textScratchBitmap?.recycle()
    textScratchBitmap = null
    fboOverlayMap.clear()
    fboTransitionA.release()
    fboTransitionB.release()
    shaderTransitions.onContextLost()
    if (nativeHandle != 0L) {
      NativeRenderBridge.onContextLost(nativeHandle)
    }
  }

  fun release() {
    colorGradeStage.release()
    vfxStackStage.release()
    faceWarpStage.release()
    bodyWarpStage.release()
    faceBeautyStage.release()
    overlayVfxStage.release()
    overlayFaceWarpStage.release()
    overlayBodyWarpStage.release()
    overlayFaceBeautyStage.release()
    arOverlayStage.release()
    subjectCutoutStage.release()
    fboMain2D.release()
    fboTransitionA.release()
    fboTransitionB.release()
    shaderTransitions.release()
    fboA.release()
    fboB.release()

    for (fbo in fboOverlayMap.values) {
      fbo.release()
    }
    fboOverlayMap.clear()

    val texturesToDelete = mutableListOf<Int>()
    for (t in textTextureCache.values) texturesToDelete.add(t.texId)
    for (s in stickerTextureCache.values) texturesToDelete.add(s.texId)
    for (i in imageTextureCache.values) texturesToDelete.add(i.texId)
    for (fx in proceduralEffectCache.values) texturesToDelete.add(fx.texId)

    if (texturesToDelete.isNotEmpty()) {
      GLES20.glDeleteTextures(texturesToDelete.size, texturesToDelete.toIntArray(), 0)
    }
    text3dGl?.release()
    text3dGl = null
    textTextureCache.clear()
    stickerTextureCache.clear()
    imageTextureCache.clear()
    proceduralEffectCache.clear()
    proceduralBitmap?.recycle()
    proceduralBitmap = null
    proceduralCanvas = null
    textScratchBitmap?.recycle()
    textScratchBitmap = null

    if (program2D != 0) {
      GLES20.glDeleteProgram(program2D)
      program2D = 0
    }
    if (programOes != 0) {
      GLES20.glDeleteProgram(programOes)
      programOes = 0
    }
    if (programTransition != 0) {
      GLES20.glDeleteProgram(programTransition)
      programTransition = 0
    }
    if (programEffect != 0) {
      GLES20.glDeleteProgram(programEffect)
      programEffect = 0
    }
    if (programBlit != 0) {
      GLES20.glDeleteProgram(programBlit)
      programBlit = 0
    }

    isInitialized = false
    if (nativeHandle != 0L) {
      NativeRenderBridge.release(nativeHandle)
      nativeHandle = 0L
    }
    Log.d(TAG, "GpuCompositionRenderer & Native Engine cleanly released")
  }

  /**
   * Same effect chain as the main clip: vfx stack, face mesh, body pose warp, face beauty.
   * Each layer uses its own stage instances so textures are not released out from under another layer.
   */
  private fun applyTrackedEffects(
    clip: VideoClip,
    srcTex: Int,
    width: Int,
    height: Int,
    timelinePosMs: Long,
    blocking: Boolean,
    placement: com.ahstudio.face.deformation.FaceWarpMapper.Placement,
    vfx: VfxStackStage,
    face: FaceWarpStage,
    body: BodyWarpStage,
    beauty: FaceBeautyStage,
  ): Int {
    val vfxTex = vfx.apply(
      clipId = clip.id,
      stackJson = clip.vfxStackJson,
      clipTimeMs = timelinePosMs - clip.timelineStartMs,
      srcTex = srcTex,
      width = width,
      height = height
    )
    val faceTex = face.apply(
      clip = clip,
      timelinePosMs = timelinePosMs,
      srcTex = vfxTex,
      width = width,
      height = height,
      blocking = blocking,
      placementOverride = placement
    )
    val bodyTex = body.apply(
      clip = clip,
      timelinePosMs = timelinePosMs,
      srcTex = faceTex,
      width = width,
      height = height,
      blocking = blocking,
      placementOverride = placement
    )
    return beauty.apply(
      clip = clip,
      timelinePosMs = timelinePosMs,
      srcTex = bodyTex,
      width = width,
      height = height,
      blocking = blocking,
      placementOverride = placement
    )
  }

  /** Copies an effect-stage texture back into the overlay's own framebuffer before the next overlay reuses the stage. */
  private fun settleOverlayTexture(clipId: String, rawTex: Int, processed: Int, width: Int, height: Int): Int {
    if (processed <= 0 || processed == rawTex) return if (rawTex > 0) rawTex else processed
    val fbo = fboOverlayMap[clipId] ?: return processed
    if (fbo.getTextureId() == processed) return processed
    fbo.bind()
    GLES20.glViewport(0, 0, width, height)
    GLES20.glDisable(GLES20.GL_BLEND)
    blitToSurface(processed, flipY = false)
    fbo.unbind()
    return fbo.getTextureId()
  }

  private fun blitToSurface(textureId: Int, flipY: Boolean, flipX: Boolean = false) {
    if (programBlit == 0 || textureId <= 0) return
    GLES20.glUseProgram(programBlit)

    val uMVPMatrixHandle = GLES20.glGetUniformLocation(programBlit, "uMVPMatrix")
    val uTexMatrixHandle = GLES20.glGetUniformLocation(programBlit, "uTexMatrix")
    val uFlipYHandle = GLES20.glGetUniformLocation(programBlit, "uFlipY")
    val uFlipXHandle = GLES20.glGetUniformLocation(programBlit, "uFlipX")
    val uTextureHandle = GLES20.glGetUniformLocation(programBlit, "uTexture")

    val identity = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    if (uMVPMatrixHandle >= 0) GLES20.glUniformMatrix4fv(uMVPMatrixHandle, 1, false, identity, 0)
    if (uTexMatrixHandle >= 0) GLES20.glUniformMatrix4fv(uTexMatrixHandle, 1, false, identity, 0)
    if (uFlipYHandle >= 0) GLES20.glUniform1i(uFlipYHandle, if (flipY) 1 else 0)
    if (uFlipXHandle >= 0) GLES20.glUniform1i(uFlipXHandle, if (flipX) 1 else 0)

    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
    if (uTextureHandle >= 0) GLES20.glUniform1i(uTextureHandle, 0)

    drawQuad(programBlit)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
  }

  private fun applyEffect(
    effectType: EffectType,
    intensity: Float,
    timeSec: Float,
    inputTexId: Int,
    viewportWidth: Int,
    viewportHeight: Int
  ) {
    if (programEffect == 0) return
    GLES20.glUseProgram(programEffect)

    val uMVPMatrixHandle = GLES20.glGetUniformLocation(programEffect, "uMVPMatrix")
    val uTexMatrixHandle = GLES20.glGetUniformLocation(programEffect, "uTexMatrix")
    val uTextureHandle = GLES20.glGetUniformLocation(programEffect, "uTexture")
    val uEffectTypeHandle = GLES20.glGetUniformLocation(programEffect, "uEffectType")
    val uIntensityHandle = GLES20.glGetUniformLocation(programEffect, "uIntensity")
    val uTimeHandle = GLES20.glGetUniformLocation(programEffect, "uTime")
    val uTexelSizeHandle = GLES20.glGetUniformLocation(programEffect, "uTexelSize")

    val identity = FloatArray(16)
    Matrix.setIdentityM(identity, 0)
    if (uMVPMatrixHandle >= 0) GLES20.glUniformMatrix4fv(uMVPMatrixHandle, 1, false, identity, 0)
    if (uTexMatrixHandle >= 0) GLES20.glUniformMatrix4fv(uTexMatrixHandle, 1, false, identity, 0)

    val glEffectType = when (effectType) {
      // Blur & Soft Focus
      EffectType.BLUR, EffectType.SOFT_FOCUS, EffectType.SHARPEN,
      EffectType.VFX_BLUR_1, EffectType.VFX_BLUR_3, EffectType.VFX_BLUR_8,
      EffectType.VFX_BLUR_9, EffectType.VFX_BLUR_10, EffectType.VFX_BLUR_11,
      EffectType.VFX_BLUR_12, EffectType.VFX_BLUR_13, EffectType.VFX_BLUR_15 -> GpuShaders.EFFECT_BLUR

      // Motion Blur
      EffectType.MOTION_BLUR, EffectType.VFX_VIRAL_1, EffectType.VFX_VIRAL_14,
      EffectType.VFX_BLUR_2, EffectType.VFX_BLUR_4, EffectType.VFX_BLUR_5,
      EffectType.VFX_BLUR_6, EffectType.VFX_BLUR_7, EffectType.VFX_BLUR_14 -> GpuShaders.EFFECT_MOTION_BLUR

      // Glow, Halo
      EffectType.GLOW, EffectType.HALO_GLOW,
      EffectType.VFX_LIGHT_4, EffectType.VFX_LIGHT_7, EffectType.VFX_LIGHT_14,
      EffectType.VFX_LIGHT_18, EffectType.VFX_LIGHT_19, EffectType.VFX_VIRAL_21,
      EffectType.VFX_VIRAL_22 -> GpuShaders.EFFECT_GLOW

      // Shake & Camera Shake
      EffectType.SHAKE, EffectType.CAMERA_MOVEMENT, EffectType.PARTY_CONFUSED,
      EffectType.VFX_VIRAL_6, EffectType.VFX_VIRAL_15, EffectType.VFX_GLITCH_14,
      EffectType.VFX_GLITCH_16, EffectType.VFX_GLITCH_19 -> GpuShaders.EFFECT_SHAKE

      // Zoom & Pulse
      EffectType.ZOOM, EffectType.SKATER_ZOOM, EffectType.VERTIGO_DOLLY, EffectType.WARP_SPEED,
      EffectType.VFX_VIRAL_12, EffectType.VFX_VIRAL_16, EffectType.VFX_VIRAL_17,
      EffectType.VFX_VIRAL_35, EffectType.VFX_3D_9 -> GpuShaders.EFFECT_ZOOM

      // Spin
      EffectType.SPIN, EffectType.VFX_VIRAL_20, EffectType.VFX_3D_8, EffectType.VFX_3D_15 -> GpuShaders.EFFECT_SPIN

      // Flash & Strobe
      EffectType.FLASH, EffectType.STROBE, EffectType.VFX_VIRAL_11, EffectType.VFX_VIRAL_36,
      EffectType.VFX_LIGHT_16, EffectType.VFX_VIRAL_23 -> GpuShaders.EFFECT_FLASH

      // Glitch, CRT, VHS
      EffectType.GLITCH, EffectType.AI_GLITCH_REALITY,
      EffectType.VFX_GLITCH_1, EffectType.VFX_GLITCH_2, EffectType.VFX_GLITCH_3,
      EffectType.VFX_GLITCH_6, EffectType.VFX_GLITCH_7, EffectType.VFX_GLITCH_8,
      EffectType.VFX_GLITCH_9, EffectType.VFX_GLITCH_10, EffectType.VFX_GLITCH_11,
      EffectType.VFX_GLITCH_13, EffectType.VFX_GLITCH_17, EffectType.VFX_GLITCH_18,
      EffectType.VFX_GLITCH_20, EffectType.VFX_RETRO_10,
      EffectType.VFX_VIRAL_4, EffectType.VFX_VIRAL_30, EffectType.VFX_VIRAL_31 -> GpuShaders.EFFECT_GLITCH

      EffectType.CRT_TV, EffectType.VFX_RETRO_1 -> GpuShaders.EFFECT_CRT
      EffectType.VHS_VINTAGE -> GpuShaders.EFFECT_VHS

      // RGB Split
      EffectType.RGB_SPLIT, EffectType.VFX_VIRAL_5, EffectType.VFX_VIRAL_29,
      EffectType.VFX_GLITCH_12 -> GpuShaders.EFFECT_RGB_SPLIT

      // Distortion, Wave, Fisheye
      EffectType.DISTORTION, EffectType.WAVE, EffectType.FISHEYE,
      EffectType.FUNNY_ALIEN_WARP, EffectType.VFX_GLITCH_4,
      EffectType.VFX_GLITCH_5, EffectType.VFX_GLITCH_15, EffectType.VFX_VIRAL_8,
      EffectType.VFX_VIRAL_9, EffectType.VFX_3D_2 -> GpuShaders.EFFECT_DISTORTION

      EffectType.RIPPLE -> GpuShaders.EFFECT_RIPPLE

      // Lens Flare & Solar Flare
      EffectType.LENS_FLARE, EffectType.VFX_LIGHT_2 -> GpuShaders.EFFECT_LENS_FLARE
      EffectType.SOLAR_FLARE -> GpuShaders.EFFECT_SOLAR_FLARE

      // Light Leak, Golden Hour, Bokeh, Prism
      EffectType.LIGHT_LEAK, EffectType.GOLDEN_HOUR,
      EffectType.VFX_LIGHT_1, EffectType.VFX_LIGHT_3, EffectType.VFX_LIGHT_5,
      EffectType.VFX_LIGHT_10, EffectType.VFX_LIGHT_17, EffectType.VFX_RETRO_7,
      EffectType.VFX_VIRAL_24 -> GpuShaders.EFFECT_LIGHT_LEAK

      EffectType.BOKEH -> GpuShaders.EFFECT_BOKEH
      EffectType.PARTY_PRISM -> GpuShaders.EFFECT_PRISM
      EffectType.VIGNETTE -> GpuShaders.EFFECT_VIGNETTE
      EffectType.NOISE -> GpuShaders.EFFECT_NOISE
      EffectType.THERMAL_CAMERA -> GpuShaders.EFFECT_THERMAL
      EffectType.BLUEPRINT_CAD -> GpuShaders.EFFECT_BLUEPRINT
      EffectType.ACID_TRIP -> GpuShaders.EFFECT_ACID_TRIP
      EffectType.POP_ART_POSTER -> GpuShaders.EFFECT_POP_ART
      EffectType.SEPIA_VINTAGE -> GpuShaders.EFFECT_SEPIA
      EffectType.POLAROID_VINTAGE -> GpuShaders.EFFECT_POLAROID
      EffectType.OIL_PAINTING -> GpuShaders.EFFECT_OIL_PAINTING
      EffectType.HALFTONE_DOT -> GpuShaders.EFFECT_HALFTONE
      EffectType.COMIC_SKETCH -> GpuShaders.EFFECT_COMIC
      EffectType.MIRROR -> GpuShaders.EFFECT_MIRROR

      else -> -1
    }

    if (uEffectTypeHandle >= 0) GLES20.glUniform1i(uEffectTypeHandle, glEffectType)
    if (uIntensityHandle >= 0) GLES20.glUniform1f(uIntensityHandle, intensity)
    if (uTimeHandle >= 0) GLES20.glUniform1f(uTimeHandle, timeSec)
    if (uTexelSizeHandle >= 0) GLES20.glUniform2f(uTexelSizeHandle, 1.0f / max(1, viewportWidth), 1.0f / max(1, viewportHeight))

    GLES20.glDisable(GLES20.GL_BLEND)
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexId)
    if (uTextureHandle >= 0) GLES20.glUniform1i(uTextureHandle, 0)

    drawQuad(programEffect)
  }

  fun invalidateClip(clipId: String) {
    textTextureCache.remove(clipId)?.let {
      if (it.texId > 0) {
        GLES20.glDeleteTextures(1, intArrayOf(it.texId), 0)
      }
    }
    stickerTextureCache.remove(clipId)?.let {
      if (it.texId > 0) {
        GLES20.glDeleteTextures(1, intArrayOf(it.texId), 0)
      }
    }
    imageTextureCache.remove(clipId)?.let {
      if (it.texId > 0) {
        GLES20.glDeleteTextures(1, intArrayOf(it.texId), 0)
      }
    }
  }

  fun invalidateAll() {
    for ((_, item) in textTextureCache) {
      if (item.texId > 0) GLES20.glDeleteTextures(1, intArrayOf(item.texId), 0)
    }
    textTextureCache.clear()
    for ((_, item) in stickerTextureCache) {
      if (item.texId > 0) GLES20.glDeleteTextures(1, intArrayOf(item.texId), 0)
    }
    stickerTextureCache.clear()
    for ((_, item) in imageTextureCache) {
      if (item.texId > 0) GLES20.glDeleteTextures(1, intArrayOf(item.texId), 0)
    }
    imageTextureCache.clear()
  }
}
