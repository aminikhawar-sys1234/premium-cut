package com.example.engine.preview

import com.example.domain.model.Timeline
import com.example.engine.composition.ChromaKeyResolver

/**
 * Decides whether the live preview has to run the GPU compositor instead of the plain player path.
 *
 * Chroma key and background removal are per-pixel effects of the compositor's shader, which is also the
 * renderer the export uses. Keeping the decision in this pure object means the "same result in preview and
 * export" rule is one testable function instead of a condition buried in the playback engine.
 *
 * Pure Kotlin (domain models only) so the JVM test harness can execute it.
 */
object PreviewCompositionPolicy {

  /**
   * True when some layer of [timeline] cannot be drawn by the plain player path: a per-pixel key or
   * segmentation mask (chroma key / background removal), or any effect the Media3 player graph has no
   * implementation for — registry VFX without a preview shader, face / body reshape, AR overlays,
   * colour-engine grades beyond a LUT, clip-local looks on overlay clips, tracked effect clips
   * (see [PreviewCoveragePolicy]). Such a project must not silently fall back to the player path:
   * the effect would simply be missing from the preview while it is present in the export.
   */
  fun requiresCompositor(timeline: Timeline): Boolean =
    ChromaKeyResolver.requiresComposition(timeline) || PreviewCoveragePolicy.requiresComposition(timeline)

  /**
   * @param alwaysOn the user turned the GPU preview on in Settings (kept as an explicit override).
   * @param compositorFailed a previous attempt failed on this device; stay on the player path.
   * @param trimPreviewActive the trimming tool owns the player with its own preview item.
   * @param sourcesPlayable every layer can be decoded by our own decoder.
   */
  fun shouldActivate(
    alwaysOn: Boolean,
    compositorFailed: Boolean,
    trimPreviewActive: Boolean,
    sourcesPlayable: Boolean,
    requiresPixelEffects: Boolean
  ): Boolean = (alwaysOn || requiresPixelEffects) && !compositorFailed && !trimPreviewActive && sourcesPlayable

  /**
   * Short line for diagnostics / the settings screen: why the compositor is on, or why it is not.
   * Never a status placeholder - it names the actual gate that decided.
   */
  fun explain(
    timeline: Timeline,
    alwaysOn: Boolean,
    compositorFailed: Boolean,
    trimPreviewActive: Boolean,
    sourcesPlayable: Boolean
  ): String {
    // Name the actual gate: chroma key / background removal vs the wider coverage rule
    // (registry VFX without a preview shader, reshape, AR, non-LUT grades, ...).
    val chromaKeyed = ChromaKeyResolver.requiresComposition(timeline)
    val covered = PreviewCoveragePolicy.requiresComposition(timeline)
    val required = chromaKeyed || covered
    val effectGate = when {
      chromaKeyed && covered -> "chroma key and an effect the player path cannot draw"
      chromaKeyed -> "chroma key"
      else -> "an effect the player path cannot draw"
    }
    return when {
      compositorFailed -> "GPU preview unavailable after a failure; using the player path"
      trimPreviewActive -> "Trimming preview owns the player"
      !sourcesPlayable -> "A layer cannot be decoded by the compositor"
      required && alwaysOn -> "GPU preview on ($effectGate, always-on setting)"
      required -> "GPU preview on ($effectGate)"
      alwaysOn -> "GPU preview on (always-on setting)"
      else -> "Player path (no effect that requires the compositor in this project)"
    }
  }
}
