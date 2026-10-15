package com.example.engine.composition

import com.example.domain.model.FilterSettings
import com.example.domain.model.Timeline
import com.example.domain.model.VideoAdjustments
import com.example.domain.model.VideoClip

/**
 * The ONE rule that decides which look a composed layer draws.
 *
 * ## The contract
 *
 * `GpuCompositionRenderer.render` takes `timelineAdjustments` / `timelineFilter`. Those parameters
 * are **project-level fallbacks**, not "the look of this frame": the renderer resolves every layer
 * against them, per layer —
 *
 * * main picture  → `activeClip.adjustments ?: fallback` / `activeClip.filter ?: fallback`
 * * transition sides → `clipBefore|clipAfter.adjustments ?: fallback` (each side its own)
 * * PIP / overlay → `overlay.clip.adjustments ?: fallback` / `overlay.clip.filter ?: fallback`
 *
 * That only works if every caller passes the **timeline** values. `PreviewCompositor` did;
 * `AsyncFramePipelineEngine` (export) instead pre-resolved the *main* clip's look and passed that
 * as the fallback:
 *
 * ```
 * adjustments = frame.activeClip?.adjustments ?: timeline.adjustments   // export, before
 * adjustments = tl.adjustments                                          // preview
 * ```
 *
 * For the main layer the two agree (resolving twice is idempotent), so the export kept the look the
 * panel showed. For **every other layer** they do not: an overlay or a transition side with no
 * clip-local look fell back to the *main clip's* filter in the export but to the *timeline's*
 * filter in the preview. Because the Filters panel writes clip-local looks and clears
 * `Timeline.filter` to `NONE`, the exported PIP carried the main clip's grade that the preview
 * never showed.
 *
 * [timelineFallback] is what callers must pass; [adjustmentsFor] / [filterFor] are what the
 * renderer resolves each layer with. Keeping both here means a caller cannot re-implement (and
 * re-break) the resolution.
 *
 * Pure Kotlin (domain models only) so the JVM test harness can execute it, the same way it
 * executes [ChromaKeyResolver].
 */
object CompositionLookResolver {

  /** The project-level fallback both the preview and the export must hand to the renderer. */
  data class TimelineFallback(val adjustments: VideoAdjustments, val filter: FilterSettings) {
    companion object {
      fun of(timeline: Timeline) = TimelineFallback(timeline.adjustments, timeline.filter)
    }
  }

  /**
   * The fallback a caller of `CompositionRenderGraph.present` must pass. Always the timeline-level
   * look — never a clip's, or that clip's look leaks onto every layer that has no look of its own.
   */
  fun timelineFallback(timeline: Timeline): TimelineFallback = TimelineFallback.of(timeline)

  /** A layer's own look wins; the project-level look is the fallback. */
  fun adjustmentsFor(clip: VideoClip?, fallback: VideoAdjustments): VideoAdjustments =
    clip?.adjustments ?: fallback

  /** A layer's own filter wins; the project-level filter is the fallback. */
  fun filterFor(clip: VideoClip?, fallback: FilterSettings): FilterSettings =
    clip?.filter ?: fallback

  /**
   * Resolving an already clip-effective value against the same clip is a no-op. This is why the
   * main layer looked correct while the export still passed the wrong fallback, and it is the
   * property that makes passing the timeline values safe for the main layer too.
   */
  fun isIdempotentForMainLayer(clip: VideoClip?, fallback: VideoAdjustments): Boolean =
    adjustmentsFor(clip, adjustmentsFor(clip, fallback)) == adjustmentsFor(clip, fallback)
}
