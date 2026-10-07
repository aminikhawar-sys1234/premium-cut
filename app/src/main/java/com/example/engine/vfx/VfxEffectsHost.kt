package com.example.engine.vfx

import com.vfx.engine.core.effect.EffectCategory
import com.vfx.engine.core.effect.EffectDefinition
import com.vfx.engine.core.effect.EffectInstance
import com.vfx.engine.core.registry.EffectRegistry
import com.vfx.engine.core.serialization.Json
import com.vfx.engine.core.stack.EffectStack
import com.vfx.engine.effects.BuiltinEffects

/**
 * App-wide bridge between the com.vfx effect registry and the editor UI.
 *
 * The clip's [com.example.domain.model.VideoClip.vfxStackJson] is the source of truth (it saves,
 * undoes and exports with the timeline). Every edit here is a pure JSON -> JSON transform, so the
 * panel stays stateless and the logic is unit-testable without Android.
 *
 * NOTE: this step wires the registry + parameter editing + persistence. GPU rendering of the stack
 * in the preview/export path is a separate step (see delivery notes).
 */
object VfxEffectsHost {

  /** Definitions that could not be registered (id/alias clash). Empty in a healthy build. */
  val registrationSkipped: MutableList<String> = mutableListOf()

  /** Single registry shared by the panel and stack (de)serialisation. Built lazily, once. */
  val registry: EffectRegistry by lazy {
    EffectRegistry().also { reg ->
      BuiltinEffects.ALL.forEach { def ->
        runCatching { reg.registerEffect(def) }.onFailure { registrationSkipped.add(def.id) }
      }
    }
  }

  fun catalog(): List<EffectDefinition> = registry.listEffects()

  fun categories(): List<EffectCategory> = catalog().map { it.category }.distinct()

  fun byCategory(category: EffectCategory): List<EffectDefinition> = registry.listByCategory(category)

  /** Lenient decode: unknown effect ids are skipped, corrupt JSON gives an empty stack. */
  fun decode(json: String?): EffectStack {
    val stack = EffectStack(registry)
    if (json.isNullOrBlank()) return stack
    runCatching { stack.deserialize(Json.parse(json), lenient = true) }.onFailure { stack.clear() }
    return stack
  }

  /** Empty stack encodes to null so the clip stays "no effects" in the project. */
  fun encode(stack: EffectStack): String? =
    if (stack.isEmpty()) null else Json.write(stack.serialize())

  private inline fun edit(json: String?, block: (EffectStack) -> Unit): String? {
    val stack = decode(json)
    block(stack)
    return encode(stack)
  }

  fun addEffect(json: String?, effectId: String): String? = edit(json) { it.addEffect(effectId) }

  fun removeAt(json: String?, index: Int): String? = edit(json) { s ->
    if (index in 0 until s.size) s.removeAt(index)
  }

  fun move(json: String?, from: Int, to: Int): String? = edit(json) { s ->
    if (from in 0 until s.size && to in 0 until s.size && from != to) s.move(from, to)
  }

  fun duplicate(json: String?, index: Int): String? = edit(json) { s ->
    if (index in 0 until s.size) s.duplicate(s.effectAt(index))
  }

  fun setEnabled(json: String?, index: Int, enabled: Boolean): String? = edit(json) { s ->
    if (index in 0 until s.size) s.effectAt(index).enabled = enabled
  }

  fun setIntensity(json: String?, index: Int, intensity: Float): String? = edit(json) { s ->
    if (index in 0 until s.size) s.effectAt(index).intensity = intensity
  }

  /** Sets one parameter's static value (values are coerced into the descriptor's range). */
  fun setParam(json: String?, index: Int, paramId: String, value: Any): String? = edit(json) { s ->
    if (index in 0 until s.size) {
      val inst: EffectInstance = s.effectAt(index)
      if (inst.hasParam(paramId)) runCatching { inst.setParam<Any>(paramId, value) }
    }
  }

  fun resetEffect(json: String?, index: Int): String? = edit(json) { s ->
    if (index in 0 until s.size) s.effectAt(index).reset()
  }
}
