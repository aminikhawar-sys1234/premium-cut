package com.example.engine.effects

import com.example.domain.model.VideoClip
import com.example.engine.effects.registry.EffectCategory
import com.example.engine.effects.registry.EffectsAssetRegistry
import com.example.engine.effects.registry.RegisteredEffect

/**
 * The only curated catalog the Effects tools UI shows. [shaderKey] is the implementation:
 * `vfx:` runs in [com.example.engine.composition.gpu.VfxStackStage] (preview + export),
 * `face:` / `body:` run in the face and body warp stages,
 * `cutout:` runs in [com.example.engine.composition.gpu.SubjectCutoutStage] blur mode.
 * Thumbnails use the same key via [EffectThumbnailRaster] on the live clip frame.
 */
object ProductionEffectCatalog {

  val effects: List<RegisteredEffect> = listOf(
    fx("video.motion_blur", "Motion Blur", EffectCategory.VIDEO_EFFECTS, "vfx:blur.directional", listOf("intensity", "distance")),
    fx("video.glitch", "Glitch", EffectCategory.VIDEO_EFFECTS, "vfx:distort.glitch", listOf("intensity", "amount")),
    fx("video.chromatic", "Chromatic Aberration", EffectCategory.VIDEO_EFFECTS, "vfx:chromatic.aberration", listOf("intensity", "amount")),
    fx("video.film_grain", "Film Grain", EffectCategory.VIDEO_EFFECTS, "vfx:noise.filmGrain", listOf("intensity", "amount")),
    fx("video.light_leak", "Light Leak", EffectCategory.VIDEO_EFFECTS, "vfx:light.leak", listOf("intensity", "amount")),

    fx("face.reshape", "Face Reshape", EffectCategory.FACE_EFFECTS, "face:reshape", listOf("intensity")),
    fx("face.skin", "Skin Smooth", EffectCategory.FACE_EFFECTS, "face:skin", listOf("intensity")),
    fx("face.slim", "Face Slim", EffectCategory.FACE_EFFECTS, "face:slim", listOf("intensity")),
    fx("face.eyes", "Eye Enhancement", EffectCategory.FACE_EFFECTS, "face:eyes", listOf("intensity")),
    fx("face.teeth", "Teeth Whitening", EffectCategory.FACE_EFFECTS, "face:teeth", listOf("intensity")),

    fx("body.reshape", "Body Reshape", EffectCategory.BODY_EFFECTS, "body:reshape", listOf("intensity")),
    fx("body.waist", "Waist Slim", EffectCategory.BODY_EFFECTS, "body:waist", listOf("intensity")),
    fx("body.legs", "Legs Lengthen", EffectCategory.BODY_EFFECTS, "body:legs", listOf("intensity")),
    fx("body.shoulders", "Shoulder Reshape", EffectCategory.BODY_EFFECTS, "body:shoulders", listOf("intensity")),
    fx("body.proportions", "Body Proportions", EffectCategory.BODY_EFFECTS, "body:proportions", listOf("intensity")),

    fx("photo.hdr", "HDR Enhance", EffectCategory.PHOTO_EFFECTS, "vfx:color.hdr", listOf("intensity", "amount")),
    fx("photo.portrait_blur", "Portrait Blur", EffectCategory.PHOTO_EFFECTS, "cutout:portrait", listOf("intensity", "strength", "softness")),
    fx("photo.background_blur", "Background Blur", EffectCategory.PHOTO_EFFECTS, "cutout:background", listOf("intensity", "strength", "softness")),
    fx("photo.color_pop", "Color Pop", EffectCategory.PHOTO_EFFECTS, "vfx:color.pop", listOf("intensity", "amount", "hue")),
    fx("photo.sharpen", "Sharpen & Enhance", EffectCategory.PHOTO_EFFECTS, "vfx:sharpen.unsharp", listOf("intensity", "amount")),

    fx("ai.sketch", "Sketch", EffectCategory.AI_EFFECTS, "vfx:stylize.sketch", listOf("intensity", "strength")),
    fx("ai.halftone", "Halftone", EffectCategory.AI_EFFECTS, "vfx:stylize.halftone", listOf("intensity", "dotSize")),
    fx("ai.posterize", "Posterize", EffectCategory.AI_EFFECTS, "vfx:stylize.posterize", listOf("intensity", "levels")),
    fx("ai.pixelate", "Pixelate", EffectCategory.AI_EFFECTS, "vfx:stylize.pixelate", listOf("intensity", "blocksX", "blocksY")),
    fx("ai.duotone", "Duotone", EffectCategory.AI_EFFECTS, "vfx:stylize.duotone", listOf("intensity", "detail")),
  )

  fun install() {
    effects.forEach { EffectsAssetRegistry.registerEffect(it) }
  }

  fun byId(id: String): RegisteredEffect? = effects.find { it.id == id }

  fun byShaderKey(shaderKey: String): RegisteredEffect? = effects.find { it.shaderKey == shaderKey }

  /** Categories that actually have registered implementations, in enum order. */
  fun categories(): List<EffectCategory> =
    EffectCategory.entries.filter { cat -> effects.any { it.category == cat } }

  fun effectsIn(category: EffectCategory): List<RegisteredEffect> =
    effects.filter { it.category == category }

  fun shaderKeys(category: EffectCategory): List<String> =
    effectsIn(category).mapNotNull { it.shaderKey }

  fun appliedOn(clip: VideoClip?, category: EffectCategory): RegisteredEffect? =
    ProductionEffectApplicator.appliedOn(clip, category)

  private fun fx(
    id: String,
    name: String,
    category: EffectCategory,
    shaderKey: String,
    parameters: List<String>,
  ) = RegisteredEffect(
    id = id,
    name = name,
    category = category,
    effectType = null,
    shaderKey = shaderKey,
    intensity = 1f,
    parameters = parameters,
    supportsPreview = true,
    supportsExport = true,
  )
}
