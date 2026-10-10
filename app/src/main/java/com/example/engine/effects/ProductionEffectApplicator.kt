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
        chinAdjust = 0f, smileAdjust = 0f, skinSmooth = 0f, teethWhiten = 0f
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
      "blur.directional" -> set("distance", 0.012f + 0.07f * intensity)
      "distort.glitch" -> set("amount", 0.012f + 0.045f * intensity)
      "chromatic.aberration" -> set("amount", 0.004f + 0.02f * intensity)
      "noise.filmGrain" -> set("amount", 0.04f + 0.22f * intensity)
      "light.leak" -> set("amount", 0.25f + 0.9f * intensity)
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
