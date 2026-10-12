package com.example.engine.preview

import com.example.domain.model.Timeline
import com.example.domain.model.TrackType

/**
 * Decides which media sources the live preview keeps open around the playhead.
 * Pure (no Android) so the windowing and decoder budget rules are unit-tested.
 */
object PreviewLayerPlanner {

  /** Mirrors DecoderManager.MAX_RECOMMENDED_HARDWARE_DECODERS: most devices fail beyond this. */
  const val MAX_VIDEO_DECODERS = 4

  /** How far ahead a source is opened so its first frame is ready when the playhead reaches it. */
  const val VIDEO_PREROLL_MS = 1_500L

  /** ExoPlayer instances (audio, legacy overlay video) need longer to prepare than a bare decoder. */
  const val PLAYER_PREROLL_MS = 3_000L

  /** Keeps a source briefly after its clip ended so a small scrub back does not reopen it. */
  const val RETAIN_TAIL_MS = 500L

  /** True when `[startMs, startMs + durationMs)` lies within the window around [posMs]. */
  fun inWindow(startMs: Long, durationMs: Long, posMs: Long, prerollMs: Long, tailMs: Long = RETAIN_TAIL_MS): Boolean {
    val endMs = startMs + durationMs.coerceAtLeast(0L)
    return startMs <= posMs + prerollMs && endMs + tailMs > posMs
  }

  /** Overlay clips that should have a source open now: visible, in the window, ordered by start. */
  fun overlayIdsInWindow(timeline: Timeline, posMs: Long, prerollMs: Long = VIDEO_PREROLL_MS): List<String> {
    if (timeline.trackSettings[TrackType.OVERLAY]?.isHidden == true) return emptyList()
    return timeline.overlayClips
      .filter { !it.isHidden && inWindow(it.timelineStartMs, it.durationMs, posMs, prerollMs) }
      .sortedBy { it.timelineStartMs }
      .map { it.id }
  }

  fun audioIdsInWindow(timeline: Timeline, posMs: Long, prerollMs: Long = PLAYER_PREROLL_MS): Set<String> =
    timeline.audioClips
      .filter { inWindow(it.timelineStartMs, it.durationMs, posMs, prerollMs) }
      .mapTo(LinkedHashSet()) { it.id }

  /**
   * Video sources to keep decoders for. Layers drawn this frame ([activeIds], in priority order)
   * come first; while playing, the next main-track clip and upcoming overlays are prerolled with
   * whatever budget is left. Never returns more than [maxDecoders] ids.
   */
  fun videoDecoderPlan(
    timeline: Timeline,
    posMs: Long,
    activeIds: List<String>,
    playing: Boolean,
    maxDecoders: Int = MAX_VIDEO_DECODERS,
    prerollMs: Long = VIDEO_PREROLL_MS
  ): List<String> {
    val plan = LinkedHashSet<String>()
    for (id in activeIds) {
      if (plan.size >= maxDecoders) return plan.toList()
      plan.add(id)
    }
    if (!playing) return plan.toList()

    val upcoming = buildList {
      timeline.videoClips
        .filter { it.isVideo && it.timelineStartMs > posMs && it.timelineStartMs <= posMs + prerollMs }
        .forEach { add(it.timelineStartMs to it.id) }
      if (timeline.trackSettings[TrackType.OVERLAY]?.isHidden != true) {
        timeline.overlayClips
          .filter { it.isVideo && !it.isHidden && it.timelineStartMs > posMs && it.timelineStartMs <= posMs + prerollMs }
          .forEach { add(it.timelineStartMs to it.id) }
      }
    }.sortedBy { it.first }

    for ((_, id) in upcoming) {
      if (plan.size >= maxDecoders) break
      plan.add(id)
    }
    return plan.toList()
  }

  /** Decoders open but no longer in [plan]; the caller releases them before opening new ones. */
  fun evictions(open: Collection<String>, plan: List<String>): List<String> {
    val keep = plan.toHashSet()
    return open.filter { it !in keep }
  }
}
