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
            addAll(LutEffects.all())
            addAll(TemporalEffects.all())
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
