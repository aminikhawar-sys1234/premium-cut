package com.example.engine.effects.registry

import androidx.compose.runtime.mutableStateListOf
import com.example.domain.model.EffectType

/**
 * Categories for the Effects tools bottom bar and category panels.
 * The bar shows only categories that have at least one real registered effect.
 */
enum class EffectCategory(val displayName: String, val tag: String) {
  VIDEO_EFFECTS("Video Effects", "nav_video_effects"),
  BODY_EFFECTS("Body Effects", "nav_body_effects"),
  FACE_EFFECTS("Face Effects", "nav_face_effects"),
  PHOTO_EFFECTS("Photo Effects", "nav_photo_effects"),
  AI_EFFECTS("AI Effects", "nav_ai_effects")
}

/**
 * A registered effect asset. [shaderKey] is required for preview, export, and thumbnails.
 * Entries without a shader key are rejected — they cannot be applied.
 */
data class RegisteredEffect(
  val id: String,
  val name: String,
  val category: EffectCategory,
  val effectType: EffectType? = null,
  val shaderKey: String? = null,
  val intensity: Float = 1.0f,
  val previewThumbnailUrl: String? = null,
  val isCustom: Boolean = false,
  /** Parameter ids the implementation actually reads. Intensity is always one of them. */
  val parameters: List<String> = listOf("intensity"),
  val supportsPreview: Boolean = true,
  val supportsExport: Boolean = true
) {
  val isRenderable: Boolean get() = !shaderKey.isNullOrBlank()
}

/**
 * Runtime Effects Asset Registry.
 *
 * Starts empty. ProductionEffectCatalog.install() and remote packages register real
 * effects (shaderKey required). Dummy / placeholder / shader-less rows are rejected.
 */
object EffectsAssetRegistry {

  private val _effects = mutableStateListOf<RegisteredEffect>()

  fun registerEffect(effect: RegisteredEffect) {
    if (!effect.isRenderable) return
    val idx = _effects.indexOfFirst { it.id == effect.id }
    if (idx >= 0) {
      if (_effects[idx] != effect) _effects[idx] = effect
    } else {
      _effects.add(effect)
    }
  }

  fun unregisterEffect(effectId: String) {
    _effects.removeAll { it.id == effectId }
  }

  fun getEffects(category: EffectCategory): List<RegisteredEffect> {
    return _effects.filter { it.category == category && it.isRenderable }
  }

  fun searchEffects(category: EffectCategory, query: String): List<RegisteredEffect> {
    val catEffects = getEffects(category)
    if (query.isBlank()) return catEffects
    return catEffects.filter {
      it.name.contains(query, ignoreCase = true) || it.id.contains(query, ignoreCase = true)
    }
  }

  fun getAllEffects(): List<RegisteredEffect> = _effects.filter { it.isRenderable }

  fun populatedCategories(): List<EffectCategory> =
    EffectCategory.entries.filter { getEffects(it).isNotEmpty() }

  fun snapshot(): List<RegisteredEffect> = _effects.toList()

  fun clearAll() {
    _effects.clear()
  }
}
