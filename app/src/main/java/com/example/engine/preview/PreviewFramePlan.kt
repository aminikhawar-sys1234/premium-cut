package com.example.engine.preview

import com.example.domain.model.Timeline
import com.example.domain.model.VideoClip
import com.example.engine.composition.ComposedFrame
import com.example.engine.composition.CompositionRenderGraph

/**
 * What the preview compositor has to fetch to draw one [ComposedFrame]: one decode per video
 * layer at its exact source time, and one texture per still image.
 */
data class PreviewFramePlan(
  val frame: ComposedFrame,
  val decodes: List<DecodeRequest>,
  val imageUris: List<String>
) {
  enum class Role { MAIN, TRANSITION_BEFORE, TRANSITION_AFTER, OVERLAY }

  data class DecodeRequest(val clip: VideoClip, val sourceUs: Long, val role: Role)

  /** Decoder priority order: main picture first, then transition sources, then overlays. */
  val decodeClipIds: List<String> get() = decodes.map { it.clip.id }.distinct()

  companion object {

    private val SYNTHETIC_SCHEMES = listOf("stock://", "sample://", "demo://", "internal://", "template://")

    fun isSynthetic(uri: String): Boolean = uri.isBlank() || SYNTHETIC_SCHEMES.any { uri.startsWith(it) }

    private fun needsDecoder(clip: VideoClip) = clip.isVideo && !isSynthetic(clip.uri)

    private fun CompositionRenderGraph.LayerRole.toPreview(): Role = when (this) {
      CompositionRenderGraph.LayerRole.MAIN -> Role.MAIN
      CompositionRenderGraph.LayerRole.TRANSITION_BEFORE -> Role.TRANSITION_BEFORE
      CompositionRenderGraph.LayerRole.TRANSITION_AFTER -> Role.TRANSITION_AFTER
      CompositionRenderGraph.LayerRole.OVERLAY -> Role.OVERLAY
    }

    /**
     * Decode and image requests for [frame]. Source times come from [CompositionRenderGraph.layersOf],
     * the same list export samples. The side of a transition that is the main clip is not requested
     * again. Text and stickers are left out of [PreviewFramePlan.frame]: Compose still draws them.
     */
    fun of(frame: ComposedFrame): PreviewFramePlan {
      val previewFrame = frame.copy(activeTexts = emptyList(), activeStickers = emptyList())
      val decodes = mutableListOf<DecodeRequest>()
      val images = LinkedHashSet<String>()
      for (layer in CompositionRenderGraph.layersOf(frame)) {
        if (layer.role != CompositionRenderGraph.LayerRole.MAIN && layer.clip.id == frame.activeClip?.id) continue
        if (needsDecoder(layer.clip)) {
          decodes.add(DecodeRequest(layer.clip, layer.sourceUs, layer.role.toPreview()))
        } else if (!layer.clip.isVideo && !isSynthetic(layer.clip.uri)) {
          images.add(layer.clip.uri)
        }
      }
      return PreviewFramePlan(previewFrame, decodes, images.toList())
    }

    /**
     * The compositor can only draw real media. Projects with placeholder clips (stock / sample
     * previews that the Compose path paints procedurally) stay on the legacy preview.
     */
    fun canComposite(timeline: Timeline, isPlayable: (String) -> Boolean): Boolean {
      val visual = timeline.videoClips + timeline.overlayClips
      return visual.isNotEmpty() && visual.all { !isSynthetic(it.uri) && isPlayable(it.uri) }
    }
  }
}

/**
 * Redraw only when the frame on screen would change: a different frame index at the timeline
 * rate, or a new [generation] (timeline edit, surface resize). Not thread-safe; owned by the GL thread.
 */
class PreviewRenderGate {
  private var lastFrameIndex = Long.MIN_VALUE
  private var lastGeneration = Long.MIN_VALUE

  fun shouldRender(posMs: Long, fps: Int, generation: Long): Boolean {
    val frameIndex = posMs * fps.coerceAtLeast(1) / 1000L
    if (frameIndex == lastFrameIndex && generation == lastGeneration) return false
    lastFrameIndex = frameIndex
    lastGeneration = generation
    return true
  }

  fun invalidate() {
    lastGeneration = Long.MIN_VALUE
  }
}
