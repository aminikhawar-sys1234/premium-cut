package com.example.engine.effects

import com.ahstudio.face.deformation.DeformationCodec
import com.ahstudio.face.deformation.DeformationParams
import com.example.domain.model.VideoClip
import com.example.engine.ai.cutout.BgRemoveCodec
import com.example.engine.ai.cutout.BgRemoveParams
import com.example.engine.effects.registry.EffectCategory
import com.example.engine.effects.registry.RegisteredEffect
import com.example.engine.vfx.VfxEffectsHost
import com.vfx.engine.core.effect.EffectInstance

/**
 * Writes a catalog effect onto one clip. Preview and export read the same fields
 * (`vfxStackJson`, `faceReshape`, `bodyReshape`, `bgRemove`).
 */
object ProductionEffectApplicator {

  fun apply(clip: VideoClip, effect: RegisteredEffect, intensity: Float): VideoClip {
    val key = effect.shaderKey ?: return clip
    val amount = intensity.coerceIn(0f, 1f)
    return when {
      key.startsWith("vfx:") -> clip.copy(
        vfxStackJson = upsertVfx(clip.vfxStackJson, key.removePrefix("vfx:"), amount)
      )
      key.startsWith("face:") -> clip.copy(
        faceReshape = DeformationCodec.encode(faceParams(DeformationCodec.decode(clip.faceReshape), key.removePrefix("face:"), amount))
      )
      key.startsWith("body:") -> clip.copy(
        bodyReshape = BodyReshapeCodec.encode(bodyParams(BodyReshapeCodec.decode(clip.bodyReshape), key.removePrefix("body:"), amount))
      )
      key.startsWith("cutout:") -> cutout(clip, key.removePrefix("cutout:"), amount)
      else -> clip
    }
  }

  /** Catalog (or registry) effect currently written on [clip] for this category, if any. */
  fun appliedOn(clip: VideoClip?, category: EffectCategory): RegisteredEffect? {
    if (clip == null) return null
    val candidates = uniqueEffects(category)
    return candidates.lastOrNull { matches(clip, it) }
  }

  fun intensityOn(clip: VideoClip?, effect: RegisteredEffect): Float {
    if (clip == null) return effect.intensity
    val key = effect.shaderKey ?: return effect.intensity
    return when {
      key.startsWith("vfx:") -> {
        val id = key.removePrefix("vfx:")
        val stack = VfxEffectsHost.decode(clip.vfxStackJson)
        stack.effects().firstOrNull { it.definition.id == id }?.intensity ?: effect.intensity
      }
      key.startsWith("face:") -> faceIntensity(DeformationCodec.decode(clip.faceReshape), key.removePrefix("face:"))
      key.startsWith("body:") -> bodyIntensity(BodyReshapeCodec.decode(clip.bodyReshape), key.removePrefix("body:"))
      key.startsWith("cutout:") -> cutoutIntensity(clip, key.removePrefix("cutout:"))
      else -> effect.intensity
    }.coerceIn(0f, 1f)
  }

  /** Removes this category's catalog effects and leaves every other category on the clip. */
  fun clearCategory(clip: VideoClip, category: EffectCategory): VideoClip {
    val keys = uniqueEffects(category).mapNotNull { it.shaderKey }
    var next = clip
    val vfxIds = keys.filter { it.startsWith("vfx:") }.map { it.removePrefix("vfx:") }.toSet()
    if (vfxIds.isNotEmpty()) next = next.copy(vfxStackJson = removeVfx(next.vfxStackJson, vfxIds))
    if (keys.any { it.startsWith("face:") }) {
      val cleared = DeformationCodec.decode(next.faceReshape).copy(
        eyeEnlarge = 0f, faceSlim = 0f, jawSharp = 0f, noseReshape = 0f,
        chinAdjust = 0f, smileAdjust = 0f, skinSmooth = 0f, teethWhiten = 0f,
        bigHead = 0f, smallFace = 0f, funny = 0f, alienEyes = 0f,
        melt = 0f, chubby = 0f, wink = 0f, curls = 0f
      )
      next = next.copy(faceReshape = DeformationCodec.encode(cleared))
    }
    if (keys.any { it.startsWith("body:") }) next = next.copy(bodyReshape = null)
    if (keys.any { it.startsWith("cutout:") }) {
      val params = BgRemoveCodec.decode(next.bgRemove)
      if (next.isBackgroundRemoved && params.mode == BgRemoveParams.MODE_BLUR) {
        next = next.copy(isBackgroundRemoved = false, bgRemove = null)
      }
    }
    return next
  }

  private fun faceParams(current: DeformationParams, kind: String, amount: Float): DeformationParams = when (kind) {
    "reshape" -> current.copy(
      eyeEnlarge = amount * 0.55f,
      faceSlim = amount * 0.45f,
      jawSharp = amount * 0.40f,
      noseReshape = amount * 0.25f,
      chinAdjust = amount * 0.30f,
      smileAdjust = amount * 0.20f,
    )
    "slim" -> current.copy(faceSlim = amount)
    "eyes" -> current.copy(eyeEnlarge = amount)
    "skin" -> current.copy(skinSmooth = amount)
    "teeth" -> current.copy(teethWhiten = amount)
    // Effects-tools warp channels (one per mesh-warp asset).
    "big_head" -> current.copy(bigHead = amount)
    "small" -> current.copy(smallFace = amount)
    "funny" -> current.copy(funny = amount)
    "alien" -> current.copy(alienEyes = amount)
    "melt" -> current.copy(melt = amount)
    "chubby" -> current.copy(chubby = amount)
    "wink" -> current.copy(wink = amount)
    "curls" -> current.copy(curls = amount)
    else -> current
  }

  private fun bodyParams(current: BodyReshapeParams, kind: String, amount: Float): BodyReshapeParams = when (kind) {
    "reshape" -> current.copy(reshape = amount)
    "waist" -> current.copy(waist = amount)
    "legs" -> current.copy(legs = amount)
    "shoulders" -> current.copy(shoulders = amount)
    "proportions" -> current.copy(proportions = amount)
    else -> current
  }

  private fun cutout(clip: VideoClip, kind: String, amount: Float): VideoClip {
    if (amount <= 0.001f) {
      val params = BgRemoveCodec.decode(clip.bgRemove)
      return if (params.mode == BgRemoveParams.MODE_BLUR) clip.copy(isBackgroundRemoved = false, bgRemove = null) else clip
    }
    val params = when (kind) {
      "portrait" -> BgRemoveParams(
        strength = (0.55f + 0.35f * amount).coerceIn(0f, 1f),
        softness = 0.22f,
        mode = BgRemoveParams.MODE_BLUR
      )
      else -> BgRemoveParams(
        strength = 0.4f,
        softness = (0.35f + 0.45f * amount).coerceIn(0f, 1f),
        mode = BgRemoveParams.MODE_BLUR
      )
    }
    return clip.copy(isBackgroundRemoved = true, bgRemove = BgRemoveCodec.encode(params))
  }

  private fun upsertVfx(json: String?, effectId: String, intensity: Float): String? {
    val stack = VfxEffectsHost.decode(json)
    val existing = stack.effects().indexOfFirst { it.definition.id == effectId }
    val inst = if (existing >= 0) stack.effectAt(existing) else stack.addEffect(effectId)
    inst.intensity = intensity
    tune(inst, effectId, intensity)
    return VfxEffectsHost.encode(stack)
  }

  private fun removeVfx(json: String?, ids: Set<String>): String? {
    val stack = VfxEffectsHost.decode(json)
    val keep = stack.effects().filter { it.definition.id !in ids }
    if (keep.size == stack.size) return if (stack.isEmpty()) null else VfxEffectsHost.encode(stack)
    stack.clear()
    keep.forEach { stack.addInstance(it) }
    return VfxEffectsHost.encode(stack)
  }

  private fun tune(inst: EffectInstance, effectId: String, intensity: Float) {
    fun set(pid: String, value: Float) {
      if (inst.hasParam(pid)) runCatching { inst.setParam(pid, value) }
    }
    when (effectId) {
      // ---- Video Effects assets: intensity -> the params the shader actually reads. ----
      "blur.motion" -> {
        set("distance", 0.006f + 0.075f * intensity)
        set("angle", 0f)
      }
      "video.vhs" -> {
        set("bleed", 0.004f + 0.024f * intensity)
        set("noise", 0.03f + 0.12f * intensity)
        set("tracking", 0.15f + 0.65f * intensity)
        set("scanline", 0.10f + 0.35f * intensity)
      }
      "video.crt" -> {
        set("curvature", 0.08f + 0.24f * intensity)
        set("scanline", 0.25f + 0.50f * intensity)
        set("grille", 0.30f + 0.50f * intensity)
        set("flicker", 0.10f + 0.30f * intensity)
        set("vignette", 0.15f + 0.35f * intensity)
      }
      "distort.datamosh" -> {
        set("amount", 0.02f + 0.14f * intensity)
        set("blockSize", 64f - 32f * intensity)
        set("smear", 0.15f + 0.60f * intensity)
      }
      "light.anamorphic" -> {
        set("amount", 0.25f + 1.10f * intensity)
        set("threshold", 0.75f - 0.28f * intensity)
        set("length", 0.08f + 0.28f * intensity)
      }
      "noise.grain" -> {
        set("amount", 0.03f + 0.14f * intensity)
        set("size", 1.40f - 0.60f * intensity)
        set("shadow", 0.30f + 0.30f * intensity)
        set("chroma", 0.20f + 0.40f * intensity)
      }
      "light.halation" -> {
        set("amount", 0.30f + 1.10f * intensity)
        set("radius", 0.008f + 0.020f * intensity)
        set("threshold", 0.75f - 0.28f * intensity)
      }
      "light.flare" -> {
        set("amount", 0.25f + 1.10f * intensity)
        set("halo", 0.45f + 0.45f * intensity)
        set("ghosts", 0.20f + 0.60f * intensity)
      }
      "stylize.speedLines" -> {
        set("amount", 0.20f + 0.70f * intensity)
        set("lines", 150f - 70f * intensity)
        set("speed", 2f + 5f * intensity)
        set("reach", 0.15f + 0.35f * intensity)
      }
      "distort.kaleidoscope" -> {
        set("segments", (4f + 8f * intensity).toInt().coerceIn(2, 16).toFloat())
        set("rotation", 45f * intensity)
        set("zoom", 1.15f - 0.30f * intensity)
      }
      // ---- Photo / AI assets on the same vfx stack. ----
      "color.hdr" -> set("amount", 0.35f + 0.9f * intensity)
      "color.pop" -> set("amount", intensity.coerceAtLeast(0.05f))
      "sharpen.unsharp" -> set("amount", 0.45f + 1.4f * intensity)
      "stylize.sketch" -> set("strength", 0.8f + 2.4f * intensity)
      "stylize.halftone" -> set("dotSize", 6f + 18f * intensity)
      "stylize.posterize" -> set("levels", (8f - 5f * intensity).coerceAtLeast(2f))
      "stylize.pixelate" -> {
        val blocks = (24f + 120f * (1f - intensity)).coerceIn(8f, 160f)
        set("blocksX", blocks)
        set("blocksY", blocks)
      }
      "stylize.duotone" -> set("detail", 0.06f + 0.4f * intensity)
      // ---- Trending assets: intensity -> the params the shaders actually read. ----
      "trend.explosion" -> {
        set("scale", 0.40f + 0.60f * intensity)
        set("heat", 0.40f + 1.20f * intensity)
      }
      "trend.ink_spill" -> {
        set("spread", 0.50f + 0.60f * intensity)
        set("dark", 0.50f + 0.45f * intensity)
      }
      "trend.earth" -> set("size", 0.14f + 0.20f * intensity)
      "trend.magical_tome" -> set("scale", 0.60f + 0.80f * intensity)
      "trend.cut_shift" -> {
        set("rgb", 0.20f + 0.60f * intensity)
        set("speed", 1.20f + 2.00f * intensity)
      }
      "trend.fire" -> {
        set("heat", 0.40f + 1.20f * intensity)
        set("height", 0.25f + 0.20f * intensity)
      }
      "trend.mini_zoom" -> {
        set("amount", 0.06f + 0.18f * intensity)
        set("shake", 0.005f + 0.030f * intensity)
      }
      "trend.white_flash" -> set("bloom", 0.15f + 0.40f * intensity)
      "trend.expand_x" -> set("expand", 0.05f + 0.22f * intensity)
      "trend.orbit360" -> {
        set("reach", 0.25f + 0.55f * intensity)
        set("twist", 0.10f + 0.30f * intensity)
      }
      "trend.layer_echo" -> set("mixAmt", 0.25f + 0.50f * intensity)
      "trend.zoom3d" -> {
        set("zoom", 0.08f + 0.25f * intensity)
        set("depth", 0.30f + 0.50f * intensity)
      }
      "trend.ai_filter" -> {
        set("warmth", 0.50f + 0.30f * intensity)
        set("glow", 0.20f + 0.40f * intensity)
        set("pop", 0.40f + 0.40f * intensity)
      }
      "trend.sad" -> set("mood", 0.40f + 0.50f * intensity)
      "trend.cartoon3d" -> {
        set("outline", 0.40f + 0.50f * intensity)
        set("sat", 0.40f + 0.50f * intensity)
      }
      // ---- Subject-driven assets: the mask gates where they draw; intensity is the mix. ----
      "subject.pixel_mask" -> set("blocks", 90f - 55f * intensity)
      "subject.phantom_clone" -> set("ghost", 0.30f + 0.60f * intensity)
    }
  }

  private fun uniqueEffects(category: EffectCategory): List<RegisteredEffect> {
    val catalog = ProductionEffectCatalog.effectsIn(category)
    val extras = com.example.engine.effects.registry.EffectsAssetRegistry.getEffects(category)
      .filter { extra -> catalog.none { it.id == extra.id || it.shaderKey == extra.shaderKey } }
    return catalog + extras
  }

  private fun matches(clip: VideoClip, effect: RegisteredEffect): Boolean {
    val key = effect.shaderKey ?: return false
    return when {
      key.startsWith("vfx:") -> {
        val id = key.removePrefix("vfx:")
        VfxEffectsHost.decode(clip.vfxStackJson).effects().any { it.enabled && it.definition.id == id && it.intensity > 0.01f }
      }
      key.startsWith("face:") -> faceKind(DeformationCodec.decode(clip.faceReshape)) == key.removePrefix("face:")
      key.startsWith("body:") -> bodyKind(BodyReshapeCodec.decode(clip.bodyReshape)) == key.removePrefix("body:")
      key.startsWith("cutout:") -> cutoutKind(clip) == key.removePrefix("cutout:")
      else -> false
    }
  }

  private fun faceKind(d: DeformationParams): String? {
    val geometric = listOf(d.jawSharp, d.noseReshape, d.chinAdjust, d.smileAdjust).count { it > 0.02f }
    if (geometric >= 2) return "reshape"
    val singles = listOf(
      "skin" to d.skinSmooth,
      "teeth" to d.teethWhiten,
      "eyes" to d.eyeEnlarge,
      "slim" to d.faceSlim,
      "big_head" to d.bigHead,
      "small" to d.smallFace,
      "funny" to d.funny,
      "alien" to d.alienEyes,
      "melt" to d.melt,
      "chubby" to d.chubby,
      "wink" to d.wink,
      "curls" to d.curls,
    )
    return singles.maxByOrNull { it.second }?.takeIf { it.second > 0.02f }?.first
      ?: if (geometric > 0) "reshape" else null
  }

  private fun bodyKind(b: BodyReshapeParams): String? {
    if (!b.isActive()) return null
    return listOf(
      "reshape" to b.reshape,
      "waist" to b.waist,
      "legs" to b.legs,
      "shoulders" to b.shoulders,
      "proportions" to b.proportions,
    ).maxByOrNull { it.second }?.takeIf { it.second > 0.02f }?.first
  }

  private fun cutoutKind(clip: VideoClip): String? {
    if (!clip.isBackgroundRemoved) return null
    val params = BgRemoveCodec.decode(clip.bgRemove)
    if (params.mode != BgRemoveParams.MODE_BLUR) return null
    return if (params.softness > 0.30f) "background" else "portrait"
  }

  private fun faceIntensity(d: DeformationParams, kind: String): Float = when (kind) {
    "reshape" -> listOf(d.eyeEnlarge / 0.55f, d.faceSlim / 0.45f, d.jawSharp / 0.40f).average().toFloat()
    "slim" -> d.faceSlim
    "eyes" -> d.eyeEnlarge
    "skin" -> d.skinSmooth
    "teeth" -> d.teethWhiten
    "big_head" -> d.bigHead
    "small" -> d.smallFace
    "funny" -> d.funny
    "alien" -> d.alienEyes
    "melt" -> d.melt
    "chubby" -> d.chubby
    "wink" -> d.wink
    "curls" -> d.curls
    else -> 1f
  }

  private fun bodyIntensity(b: BodyReshapeParams, kind: String): Float = when (kind) {
    "reshape" -> b.reshape
    "waist" -> b.waist
    "legs" -> b.legs
    "shoulders" -> b.shoulders
    "proportions" -> b.proportions
    else -> 1f
  }

  private fun cutoutIntensity(clip: VideoClip, kind: String): Float {
    if (!clip.isBackgroundRemoved) return 0f
    val params = BgRemoveCodec.decode(clip.bgRemove)
    return when (kind) {
      "portrait" -> ((params.strength - 0.55f) / 0.35f)
      else -> ((params.softness - 0.35f) / 0.45f)
    }.coerceIn(0f, 1f)
  }
}
