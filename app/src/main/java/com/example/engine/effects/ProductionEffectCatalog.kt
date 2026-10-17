package com.example.engine.effects

import com.example.domain.model.VideoClip
import com.example.engine.effects.registry.EffectCategory
import com.example.engine.effects.registry.EffectsAssetRegistry
import com.example.engine.effects.registry.RegisteredEffect

/**
 * The only curated catalog the Effects tools UI shows. [shaderKey] is the implementation:
 * `vfx:trend.*` are the CapCut-trending full-frame GPU effects (explosion, ink spill, rotating
 * earth, magical tome, cut shift, fire, mini zoom, white flash, and the photo set) rendered by
 * [com.example.engine.composition.gpu.VfxStackStage] in preview AND export,
 * `vfx:subject.*` are the subject-driven assets (body effects + AI character restyles) rendered
 * by the same stage through [com.vfx.engine.effects.SubjectMaskRuntime] with the on-device
 * segmentation mask,
 * `face:*` run in the face mesh warp stage (big head, small face, funny, alien eyes, melt,
 * chubby, wink, perm curls).
 * Thumbnails use the same key via [EffectThumbnailRaster] on the live clip frame.
 *
 * Every entry here is a real engine definition — a GLSL body or a mesh-warp channel — registered
 * through [com.vfx.engine.effects.BuiltinEffects]. There are no placeholder rows: the registry
 * rejects effects without a shader key, and every key below resolves to a registered definition
 * (pinned by VideoEffectsAssetsTest / TrendEffectsAssetsTest / SubjectEffectsTest).
 */
object ProductionEffectCatalog {

  /** Video Effects: the CapCut-trending GPU assets (Motion Blur kept from the previous set). */
  private val videoEffects: List<RegisteredEffect> = listOf(
    fx("video.explosion", "Explosion", EffectCategory.VIDEO_EFFECTS, "vfx:trend.explosion", listOf("intensity", "scale", "speed", "heat")),
    fx("video.ink_spill", "Ink Spill", EffectCategory.VIDEO_EFFECTS, "vfx:trend.ink_spill", listOf("intensity", "spread", "speed", "dark")),
    fx("video.rotating_earth", "Rotating Earth", EffectCategory.VIDEO_EFFECTS, "vfx:trend.earth", listOf("intensity", "size", "position", "spin", "clouds")),
    fx("video.magical_tome", "Magical Tome", EffectCategory.VIDEO_EFFECTS, "vfx:trend.magical_tome", listOf("intensity", "scale", "speed", "hue")),
    fx("video.motion_blur", "Motion Blur", EffectCategory.VIDEO_EFFECTS, "vfx:blur.motion", listOf("intensity", "distance", "angle", "samples")),
    fx("video.cut_shift", "Cut Shift", EffectCategory.VIDEO_EFFECTS, "vfx:trend.cut_shift", listOf("intensity", "slices", "speed", "rgb")),
    fx("video.fire", "Fire", EffectCategory.VIDEO_EFFECTS, "vfx:trend.fire", listOf("intensity", "height", "speed", "heat")),
    fx("video.mini_zoom", "Mini Zoom", EffectCategory.VIDEO_EFFECTS, "vfx:trend.mini_zoom", listOf("intensity", "speed", "amount", "shake")),
    fx("video.white_flash", "White Flash", EffectCategory.VIDEO_EFFECTS, "vfx:trend.white_flash", listOf("intensity", "speed", "hold", "bloom")),
  )

  /** Body Effects: subject-segmentation-driven assets — they only draw on the tracked person. */
  private val bodyEffects: List<RegisteredEffect> = listOf(
    fx("body.optical_scan", "Optical Scan", EffectCategory.BODY_EFFECTS, "vfx:subject.optical_scan", listOf("intensity", "speed", "band")),
    fx("body.phantom_clone", "Phantom Clone", EffectCategory.BODY_EFFECTS, "vfx:subject.phantom_clone", listOf("intensity", "offset", "speed", "ghost")),
    fx("body.light_trails", "Light Trails", EffectCategory.BODY_EFFECTS, "vfx:subject.light_trails", listOf("intensity", "speed", "glow")),
    fx("body.pixel_mask", "Pixel Mask", EffectCategory.BODY_EFFECTS, "vfx:subject.pixel_mask", listOf("intensity", "blocks")),
    fx("body.action_paint", "Action Paint", EffectCategory.BODY_EFFECTS, "vfx:subject.action_paint", listOf("intensity", "speed", "splash")),
    fx("body.face_scan", "Face Scan", EffectCategory.BODY_EFFECTS, "vfx:subject.face_scan", listOf("intensity", "speed", "band")),
    fx("body.light_dissolve", "Light Dissolve", EffectCategory.BODY_EFFECTS, "vfx:subject.light_dissolve", listOf("intensity", "speed", "ember")),
    fx("body.luminescence", "Luminescence", EffectCategory.BODY_EFFECTS, "vfx:subject.luminescence", listOf("intensity", "glow", "pulse")),
    fx("body.hand_drawn", "Hand Drawn", EffectCategory.BODY_EFFECTS, "vfx:subject.hand_drawn", listOf("intensity", "outline", "paper")),
  )

  val effects: List<RegisteredEffect> = videoEffects + bodyEffects + listOf(

    fx("face.funny", "Funny Face", EffectCategory.FACE_EFFECTS, "face:funny", listOf("intensity")),
    fx("face.alien_eyes", "Alien Eyes", EffectCategory.FACE_EFFECTS, "face:alien", listOf("intensity")),
    fx("face.small_face", "Small Face", EffectCategory.FACE_EFFECTS, "face:small", listOf("intensity")),
    fx("face.melting", "Melting Face", EffectCategory.FACE_EFFECTS, "face:melt", listOf("intensity")),
    fx("face.chubby", "Chubby Cheeks", EffectCategory.FACE_EFFECTS, "face:chubby", listOf("intensity")),

    fx("photo.expand_x", "Expand X", EffectCategory.PHOTO_EFFECTS, "vfx:trend.expand_x", listOf("intensity", "expand", "falloff")),
    fx("photo.orbit360", "Orbit 360", EffectCategory.PHOTO_EFFECTS, "vfx:trend.orbit360", listOf("intensity", "speed", "reach", "twist")),
    fx("photo.perm_curls", "Perm Curls", EffectCategory.PHOTO_EFFECTS, "face:curls", listOf("intensity")),
    fx("photo.wink", "Wink", EffectCategory.PHOTO_EFFECTS, "face:wink", listOf("intensity")),
    fx("photo.layer", "Layer", EffectCategory.PHOTO_EFFECTS, "vfx:trend.layer_echo", listOf("intensity", "offset", "scaleStep", "mixAmt")),
    fx("photo.doll", "Doll", EffectCategory.PHOTO_EFFECTS, "face:big_head", listOf("intensity")),
    fx("photo.zoom3d", "3D Zoom", EffectCategory.PHOTO_EFFECTS, "vfx:trend.zoom3d", listOf("intensity", "zoom", "depth", "speed")),
    fx("photo.ai_filter", "AI Filter", EffectCategory.PHOTO_EFFECTS, "vfx:trend.ai_filter", listOf("intensity", "warmth", "glow", "pop")),
    fx("photo.sad", "Sad", EffectCategory.PHOTO_EFFECTS, "vfx:trend.sad", listOf("intensity", "mood", "vig", "grain")),
    fx("photo.cartoon3d", "3D Cartoon", EffectCategory.PHOTO_EFFECTS, "vfx:trend.cartoon3d", listOf("intensity", "levels", "outline", "sat")),

    // AI Effects: on-device segmentation-driven character restyles — the tracked person is
    // transformed in place (shadow silhouette / golden statue / cyber suit / cartoon avatar).
    fx("ai.shadow_clone", "Shadow Clone", EffectCategory.AI_EFFECTS, "vfx:subject.clone_shadow", listOf("intensity", "lift", "rim")),
    fx("ai.golden_statue", "Golden Statue", EffectCategory.AI_EFFECTS, "vfx:subject.clone_golden", listOf("intensity", "shine", "relief")),
    fx("ai.cyber_neon", "Cyber Neon", EffectCategory.AI_EFFECTS, "vfx:subject.clone_cyber", listOf("intensity", "lines", "glow")),
    fx("ai.cartoon_character", "Cartoon Character", EffectCategory.AI_EFFECTS, "vfx:subject.clone_cartoon", listOf("intensity", "levels", "outline", "boost")),
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
