package com.vfx.engine.effects

import com.vfx.engine.core.effect.EffectCategory
import com.vfx.engine.core.effect.EffectDefinition
import com.vfx.engine.core.registry.EffectRegistry

/**
 * Registers every built-in GPU effect definition into an [EffectRegistry].
 * The Effects tools catalog picks a curated subset; the VFX stack panel lists all of them.
 */
object BuiltinEffects {

    val ALL: List<EffectDefinition> by lazy {
        buildList {
            addAll(ColorEffects.all())
            addAll(BlurEffects.all())
            addAll(LightEffects.all())
            addAll(DistortEffects.all())
            addAll(StylizeEffects.all())
            addAll(TemporalEffects.all())
            // The production catalog's Video Effects assets (video.vhs, light.halation, ...).
            addAll(VideoEffects.all())
            // The CapCut-trending full-frame assets (trend.explosion, trend.sad, ...).
            addAll(TrendEffects.all())
            // The subject-driven assets (subject.optical_scan, subject.clone_golden, ...).
            addAll(SubjectEffects.all())
        }.distinctBy { it.id }
    }

    fun registerAll(registry: EffectRegistry) {
        ALL.forEach { def ->
            if (!registry.hasEffect(def.id)) registry.registerEffect(def)
        }
    }

    fun byCategory(category: EffectCategory): List<EffectDefinition> =
        ALL.filter { it.category == category }
}
