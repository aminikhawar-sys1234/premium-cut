package com.example.engine.preview

import com.ahstudio.color.core.ColorState
import com.ahstudio.face.deformation.DeformationCodec
import com.ahstudio.face.deformation.isActive as isFaceDeformActive
import com.example.domain.model.FilterSettings
import com.example.domain.model.Timeline
import com.example.domain.model.VideoAdjustments
import com.example.domain.model.VideoClip
import com.example.engine.color.ColorEngineHost
import com.example.engine.effects.BodyReshapeCodec
import com.example.engine.effects.media3.PreviewLookPolicy
import com.example.engine.effects.media3.PreviewVfxCoverage

/**
 * The other half of the preview/export parity rule: effects the plain player path cannot draw
 * must switch the live preview to the GPU compositor, exactly like chroma key / background
 * removal already do. Without this, an effect landed on the clip (and showed up in the export)
 * while the preview silently kept showing the un-effected picture.
 *
 * Everything checked here renders only in the compositor (`GpuCompositionRenderer` /
 * `VfxStackStage` / `ColorGradeStage`), never in the Media3 player graph:
 *  - registry VFX without a Media3 preview shader ([PreviewVfxCoverage]),
 *  - face / body reshape (mesh warps),
 *  - AR overlays,
 *  - colour-engine grades beyond a plain LUT (`ColorGradeJson` lift/gamma/curves/HSL/...),
 *  - clip-local filter / adjustments on OVERLAY clips (the overlay lane decodes raw),
 *  - tracked blur / mosaic effect clips.
 *
 * Pure Kotlin (domain models + pure codecs only) so the JVM test harness can execute it.
 */
object PreviewCoveragePolicy {

  fun requiresComposition(timeline: Timeline): Boolean {
    val clips = timeline.videoClips + timeline.overlayClips
    // ANY active VFX stack: the compositor is the pipeline the export runs, so routing every
    // effect tap through it keeps preview and export identical. The light player path stays a
    // fallback only (its Media3 shaders can silently degrade to identity passthrough), and the
    // previous "representable subset" split meant a failing Media3 graph showed no effect at all
    // while the export drew it. Disabled / zero-intensity stacks never count (no visual change).
    if (clips.any { PreviewVfxCoverage.hasAnyActiveEffects(it.vfxStackJson) }) return true
    // Mesh-warp retouch (face / body) and AR face overlays.
    if (clips.any { faceReshapeActive(it) || bodyReshapeActive(it) }) return true
    if (clips.any { !it.arOverlay.isNullOrBlank() }) return true
    // A colour-engine grade that is more than a LUT (wheels / curves / HSL / mixer / ...).
    if (clips.any { hasNonLutGrade(it) }) return true
    // Overlay clips get no grading node on the player path: their clip-local look would be
    // invisible, so any explicitly set filter / adjustments moves them to the compositor.
    if (timeline.overlayClips.any { hasClipLocalLook(it) }) return true
    // Tracked blur / mosaic regions are compositor-only.
    if (timeline.effectClips.isNotEmpty()) return true
    return false
  }

  private fun faceReshapeActive(clip: VideoClip): Boolean {
    val json = clip.faceReshape ?: return false
    return runCatching { DeformationCodec.decode(json).isFaceDeformActive() }.getOrDefault(false)
  }

  private fun bodyReshapeActive(clip: VideoClip): Boolean {
    val json = clip.bodyReshape ?: return false
    return runCatching { BodyReshapeCodec.decode(json).isActive() }.getOrDefault(false)
  }

  /** True when the grade changes the picture without its LUT (LUT-only grades stay on the player path). */
  private fun hasNonLutGrade(clip: VideoClip): Boolean {
    val json = clip.colorGradeJson ?: return false
    if (json.isBlank()) return false
    val state = runCatching { ColorEngineHost.decodeOrDefault(json) }.getOrDefault(ColorState())
    return !state.copy(lut = null).isIdentity()
  }

  /** Explicit, non-identity look written on the clip itself (not inherited from the project default). */
  private fun hasClipLocalLook(clip: VideoClip): Boolean {
    val adj = clip.adjustments
    val flt = clip.filter
    if (adj == null && flt == null) return false
    // Same "is anything actually set" rule the Filters tools use, so a clip that carries the
    // defaults written back by a reset (type NONE, identity adjustments) stays on the player path.
    return PreviewLookPolicy.hasAnyLook(adj ?: VideoAdjustments(), flt ?: FilterSettings())
  }
}
