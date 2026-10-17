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
     *
     * Two rules, matched to what the compositor actually decodes:
     *  - ANY synthetic clip keeps the whole project off the compositor (GL has no way to draw it).
     *  - Playability is required only for the sources the CURRENT frame decodes (main clip under
     *    the playhead, its transition neighbours, overlays covering the playhead). A broken clip
     *    anywhere else must not blind the whole editor - that static whole-timeline probe used to
     *    latch the compositor off for projects where playback worked fine (missing proxy file,
     *    revoked content grant, exotic scheme), and every registry effect then stayed invisible
     *    forever while the player kept playing. Off-screen failures surface per frame at runtime
     *    through the compositor's `onMainLayerUndecodable` -> player fallback instead.
     *
     * The check is per clip, and the CALLER resolves the uri exactly the way the compositor's own
     * decoders will (proxy / relink). Probing the raw project uri here used to answer "not playable"
     * for clips that playback plays fine through their proxy - which latched the GPU compositor off
     * and made every registry effect permanently invisible while the same clip previewed unfiltered.
     */
    fun canComposite(timeline: Timeline, playheadMs: Long, isPlayable: (VideoClip) -> Boolean): Boolean {
      val visual = timeline.videoClips + timeline.overlayClips
      if (visual.isEmpty()) return false
      if (visual.any { isSynthetic(it.uri) }) return false
      return clipsVisibleAt(timeline, playheadMs).all { isPlayable(it) }
    }

    /**
     * The clips whose sources the compositor decodes for the frame at [playheadMs]: the main clip
     * under the playhead and every overlay covering it. Transition sides are intentionally NOT
     * probed - they are decoded only while a transition overlaps the playhead, and a broken
     * transition source surfaces per frame at runtime (player fallback), never as a permanent
     * whole-project veto.
     */
    fun clipsVisibleAt(timeline: Timeline, playheadMs: Long): List<VideoClip> {
      val mains = timeline.videoClips.sortedBy { it.timelineStartMs }
      if (mains.isEmpty()) return emptyList()
      val active = mains.firstOrNull {
        playheadMs >= it.timelineStartMs && playheadMs < it.timelineStartMs + it.durationMs
      } ?: mains.last()
      val visible = ArrayList<VideoClip>(2 + timeline.overlayClips.size)
      visible.add(active)
      timeline.overlayClips
        .filter { playheadMs >= it.timelineStartMs && playheadMs < it.timelineStartMs + it.durationMs }
        .forEach { visible.add(it) }
      return visible.distinctBy { it.id }
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
