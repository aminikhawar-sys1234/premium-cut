package com.example.engine.preview

import com.example.domain.model.Timeline
import com.example.domain.model.TransitionType
import com.example.domain.model.VideoClip
import com.example.engine.composition.ComposedFrame

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

    /**
     * Text and stickers are left out: the Compose overlay still draws (and edits) them on top of
     * the compositor surface, so drawing them here too would double them.
     */
    fun of(frame: ComposedFrame): PreviewFramePlan {
      val previewFrame = frame.copy(activeTexts = emptyList(), activeStickers = emptyList())
      val decodes = mutableListOf<DecodeRequest>()
      val images = LinkedHashSet<String>()

      fun addImage(clip: VideoClip) {
        if (!clip.isVideo && !isSynthetic(clip.uri)) images.add(clip.uri)
      }

      frame.activeClip?.let { main ->
        if (needsDecoder(main)) decodes.add(DecodeRequest(main, frame.clipSourcePosMs * 1000L, Role.MAIN)) else addImage(main)
      }
      frame.activeTransition?.takeIf { it.type != TransitionType.NONE }?.let { tr ->
        for ((clip, role) in listOf(tr.clipBefore to Role.TRANSITION_BEFORE, tr.clipAfter to Role.TRANSITION_AFTER)) {
          if (clip.id == frame.activeClip?.id) continue
          if (needsDecoder(clip)) {
            decodes.add(DecodeRequest(clip, clip.timelineToSourceMs(frame.timelinePosMs) * 1000L, role))
          } else {
            addImage(clip)
          }
        }
      }
      for (overlay in frame.activeOverlays) {
        if (needsDecoder(overlay.clip)) {
          decodes.add(DecodeRequest(overlay.clip, overlay.sourcePosMs * 1000L, Role.OVERLAY))
        } else {
          addImage(overlay.clip)
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
