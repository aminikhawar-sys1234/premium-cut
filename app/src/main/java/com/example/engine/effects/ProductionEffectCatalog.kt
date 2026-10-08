package com.example.engine.effects

import com.example.engine.effects.registry.EffectCategory
import com.example.engine.effects.registry.EffectsAssetRegistry
import com.example.engine.effects.registry.RegisteredEffect

/**
 * The only catalog of the 20 production effects. [shaderKey] is the implementation:
 * `vfx:` runs in [com.example.engine.composition.gpu.VfxStackStage],
 * `face:` / `body:` run in the face and body warp stages,
 * `cutout:` runs in [com.example.engine.composition.gpu.SubjectCutoutStage] blur mode.
 * Thumbnails use the same key via [EffectThumbnailRaster].
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
  )

  fun install() {
    effects.forEach { EffectsAssetRegistry.registerEffect(it) }
  }

  fun byId(id: String): RegisteredEffect? = effects.find { it.id == id }

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
