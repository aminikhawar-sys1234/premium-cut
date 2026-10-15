package com.example.engine.composition

import com.example.domain.model.ChromaKeySettings
import com.example.domain.model.FilterSettings
import com.example.domain.model.TransitionType
import com.example.domain.model.VideoAdjustments
import com.example.domain.model.VideoClip
import com.example.engine.composition.gpu.ChromaBackgroundFrame
import com.example.engine.composition.gpu.GpuCompositionRenderer
import com.example.engine.composition.gpu.TransitionSourceTexture

/**
 * The one description of a composed frame that both the live preview and the export encoder draw.
 *
 * [layersOf] decides which clip is sampled and at which source time. [assemble] turns those
 * already-decoded pictures into the arguments of [GpuCompositionRenderer.render]. Preview and
 * export differ only by [FramePresentation] (encoder orientation, deterministic masks) and by
 * whether text and stickers are drawn here or by the editor overlay.
 */
object CompositionRenderGraph {

  enum class LayerRole { MAIN, TRANSITION_BEFORE, TRANSITION_AFTER, OVERLAY }

  /** One picture the frame needs: a clip plus the source time, in microseconds, to sample. */
  data class LayerSample(val clip: VideoClip, val sourceUs: Long, val role: LayerRole)

  /** A picture that has already been decoded or uploaded. [width] and [height] of 0 mean unknown. */
  data class ResolvedLayer(
    val textureId: Int,
    val isOes: Boolean,
    val texMatrix: FloatArray?,
    val width: Int,
    val height: Int
  )

  /**
   * Where the frame is going. Export flips for the encoder's coordinate system and waits for
   * masks so every frame is reproducible; the preview screen does neither.
   */
  data class FramePresentation(
    val flipY: Boolean,
    val flipX: Boolean,
    val deterministicMasks: Boolean
  ) {
    companion object {
      val PREVIEW = FramePresentation(flipY = false, flipX = false, deterministicMasks = false)

      fun export(flipY: Boolean) = FramePresentation(flipY = flipY, flipX = false, deterministicMasks = true)
    }
  }

  data class AssembledFrame(
    val frame: ComposedFrame,
    val mainTextureId: Int,
    val isMainOes: Boolean,
    val mainTexMatrix: FloatArray?,
    val overlayTextures: Map<String, Int>,
    val overlayTexMatrices: Map<String, FloatArray>,
    val overlayIsOes: Map<String, Boolean>,
    val transitionBefore: TransitionSourceTexture?,
    val transitionAfter: TransitionSourceTexture?
  )

  /**
   * Every layer this frame shows, main first, then both transition sources, then overlays.
   * A transition source that is the main clip is still listed: the caller samples each clip id
   * once and [assemble] reuses that picture.
   */
  fun layersOf(frame: ComposedFrame): List<LayerSample> {
    val layers = mutableListOf<LayerSample>()
    frame.activeClip?.let { main ->
      layers.add(LayerSample(main, frame.clipSourcePosMs * 1000L, LayerRole.MAIN))
    }
    frame.activeTransition?.takeIf { it.type != TransitionType.NONE }?.let { tr ->
      layers.add(LayerSample(tr.clipBefore, tr.clipBefore.timelineToSourceMs(frame.timelinePosMs) * 1000L, LayerRole.TRANSITION_BEFORE))
      layers.add(LayerSample(tr.clipAfter, tr.clipAfter.timelineToSourceMs(frame.timelinePosMs) * 1000L, LayerRole.TRANSITION_AFTER))
    }
    for (overlay in frame.activeOverlays) {
      layers.add(LayerSample(overlay.clip, overlay.sourcePosMs * 1000L, LayerRole.OVERLAY))
    }
    return layers
  }

  /**
   * Builds the render arguments. [decoded] holds pictures already produced for a clip id (video
   * decoders and still-frame fallbacks). Anything else that is not a video is taken from
   * [imageTextureId]. Clips imported without a pixel size take the decoded size, because the
   * renderer fits layers by it.
   *
   * Text and stickers are dropped when [drawTextAndStickers] is false. The editor draws them in
   * Compose so they stay editable; export draws them in this graph.
   */
  fun assemble(
    frame: ComposedFrame,
    decoded: Map<String, ResolvedLayer>,
    imageTextureId: (String) -> Int,
    drawTextAndStickers: Boolean
  ): AssembledFrame {
    fun VideoClip.sized(): VideoClip {
      if (width > 0 && height > 0) return this
      val layer = decoded[id] ?: return this
      return if (layer.width > 0 && layer.height > 0) copy(width = layer.width, height = layer.height) else this
    }

    val presented = frame.copy(
      activeClip = frame.activeClip?.sized(),
      activeOverlays = frame.activeOverlays.map { it.copy(clip = it.clip.sized()) },
      activeTexts = if (drawTextAndStickers) frame.activeTexts else emptyList(),
      activeStickers = if (drawTextAndStickers) frame.activeStickers else emptyList()
    )

    fun picture(clip: VideoClip): ResolvedLayer? {
      decoded[clip.id]?.let { return it }
      if (clip.isVideo) return null
      val tex = imageTextureId(clip.uri)
      return if (tex > 0) ResolvedLayer(tex, false, null, 0, 0) else null
    }

    fun source(clip: VideoClip): TransitionSourceTexture? {
      val layer = picture(clip) ?: return null
      return TransitionSourceTexture(layer.textureId, layer.isOes, layer.texMatrix)
    }

    val main = presented.activeClip?.let { picture(it) }
    val overlayTextures = HashMap<String, Int>()
    val overlayMatrices = HashMap<String, FloatArray>()
    val overlayIsOes = HashMap<String, Boolean>()
    for (overlay in presented.activeOverlays) {
      val layer = picture(overlay.clip) ?: continue
      overlayTextures[overlay.clip.id] = layer.textureId
      overlayIsOes[overlay.clip.id] = layer.isOes
      layer.texMatrix?.let { overlayMatrices[overlay.clip.id] = it }
    }

    val transition = presented.activeTransition?.takeIf { it.type != TransitionType.NONE }
    return AssembledFrame(
      frame = presented,
      mainTextureId = main?.textureId ?: 0,
      isMainOes = main?.isOes ?: false,
      mainTexMatrix = main?.texMatrix,
      overlayTextures = overlayTextures,
      overlayTexMatrices = overlayMatrices,
      overlayIsOes = overlayIsOes,
      transitionBefore = transition?.let { source(it.clipBefore) },
      transitionAfter = transition?.let { source(it.clipAfter) }
    )
  }

  /**
   * Draws [assembled] with the destination's presentation. Call on the GL thread.
   *
   * [chromaKey] is the project-wide default; each clip's own setting wins inside the renderer
   * ([com.example.engine.composition.ChromaKeyResolver]). [chromaBackgroundFrame] is the decoded background
   * video frame for a keyed main clip that keys over a video - the preview and the export each decode it with
   * their own decoder at the same source time, so both draw the same background.
   *
   * @return what the renderer actually drew. The export inspects it: a frame that carries no picture
   *   must never be encoded as if it were one (that is how an all-black export happens).
   */
  fun present(
    renderer: GpuCompositionRenderer,
    assembled: AssembledFrame,
    viewportWidth: Int,
    viewportHeight: Int,
    adjustments: VideoAdjustments,
    filter: FilterSettings,
    chromaKey: ChromaKeySettings,
    presentation: FramePresentation,
    chromaBackgroundFrame: ChromaBackgroundFrame? = null
  ): GpuCompositionRenderer.RenderReport = renderer.render(
    frame = assembled.frame,
    mainTextureId = assembled.mainTextureId,
    isMainOes = assembled.isMainOes,
    mainTexMatrix = assembled.mainTexMatrix,
    overlayTextures = assembled.overlayTextures,
    overlayTexMatrices = assembled.overlayTexMatrices,
    overlayIsOes = assembled.overlayIsOes,
    viewportWidth = viewportWidth,
    viewportHeight = viewportHeight,
    timelineAdjustments = adjustments,
    timelineFilter = filter,
    chromaKey = chromaKey,
    chromaBackgroundFrame = chromaBackgroundFrame,
    flipYForEncoder = presentation.flipY,
    flipXForEncoder = presentation.flipX,
    transitionBefore = assembled.transitionBefore,
    transitionAfter = assembled.transitionAfter,
    deterministicMasks = presentation.deterministicMasks
  )
}
