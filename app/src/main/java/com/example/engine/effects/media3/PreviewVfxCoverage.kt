package com.example.engine.effects.media3

import com.example.engine.vfx.VfxEffectsHost

/**
 * Pure-Kotlin mirror of the id set [PreviewVfxEffects.toMedia3] can actually render on the
 * player preview path.
 *
 * The VFX registry lists every com.vfx effect, but the light Media3 preview only implements a
 * subset of them; the rest render exclusively in the GPU compositor / export (`VfxStackStage`).
 * A user tapping such an effect used to see the stack update while the picture stayed unchanged.
 * The preview policy reads this set to switch such projects to the compositor automatically, so
 * every registry effect is visible live.
 *
 * Keep this set in sync with the `when (instance.definition.id)` branches of
 * [PreviewVfxEffects.toMedia3]: add the id here when you add a preview shader there.
 */
object PreviewVfxCoverage {

  /** Ids that have a real Media3 preview shader in [PreviewVfxEffects]. */
  val MEDIA3_PREVIEW_EFFECT_IDS: Set<String> = setOf(
    // The ten Video Effects assets.
    "blur.motion", "video.vhs", "video.crt", "distort.datamosh", "light.anamorphic",
    "noise.grain", "light.halation", "light.flare", "stylize.speedLines", "distort.kaleidoscope",
    // Engine effects shared with the Photo / AI assets and the stack panel.
    "distort.glitch", "chromatic.aberration", "chromatic.rgbSplit", "blur.directional",
    "light.leak", "color.hdr", "color.pop", "sharpen.unsharp", "sharpen.sharpen",
    "stylize.sketch", "stylize.halftone", "stylize.posterize", "stylize.pixelate", "stylize.duotone",
  )

  /**
   * True when [vfxStackJson] enables at least one effect the player preview path cannot draw.
   * Disabled / zero-intensity entries never count: they do not change the picture anywhere.
   */
  fun hasUnrepresentableEffects(vfxStackJson: String?): Boolean {
    if (vfxStackJson.isNullOrBlank()) return false
    val stack = VfxEffectsHost.decode(vfxStackJson)
    return stack.effects().any {
      it.enabled && it.intensity > 0.01f && it.definition.id !in MEDIA3_PREVIEW_EFFECT_IDS
    }
  }

  /**
   * True when [vfxStackJson] has any active (enabled, non-zero intensity) effect at all.
   *
   * The live preview renders VFX stacks in the GPU compositor - the very pipeline the export
   * uses - so ANY active stack is compositor-eligible. The player path stays as a fallback
   * (some of its Media3 shaders can silently degrade to identity passthrough when the effect
   * graph fails to build), which is why a project with an active stack must not rely on it:
   * preview and export would disagree the moment that fallback fires.
   */
  fun hasAnyActiveEffects(vfxStackJson: String?): Boolean {
    if (vfxStackJson.isNullOrBlank()) return false
    val stack = VfxEffectsHost.decode(vfxStackJson)
    return stack.effects().any { it.enabled && it.intensity > 0.01f }
  }
}
