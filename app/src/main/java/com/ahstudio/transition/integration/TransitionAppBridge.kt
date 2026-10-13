package com.ahstudio.transition.integration

import com.ahstudio.transition.core.Easing
import com.ahstudio.transition.core.ParamValue
import com.ahstudio.transition.core.TransitionAlignment
import com.ahstudio.transition.core.TransitionDefinition
import com.ahstudio.transition.core.TransitionInstance
import com.ahstudio.transition.provider.TransitionRegistry
import com.ahstudio.transition.render.TransitionEngine
import com.ahstudio.transition.transitions.BuiltinTransitions
import com.example.domain.model.Transition
import com.example.domain.model.TransitionType
import com.example.domain.model.VideoClip

/**
 * Universal Bridge connecting the AH Video Studio Timeline, ViewModels,
 * UI components, GPU compositor, and Export engines to the high-performance
 * TransitionEngine runtime.
 */
object TransitionAppBridge {

    /** Global singleton registry populated with all built-in transition definitions */
    val registry: TransitionRegistry by lazy {
        TransitionRegistry(listOf(BuiltinTransitions.builtinProvider()))
    }

    /** Mapping from Legacy / Domain [TransitionType] to [TransitionDefinition] */
    fun getDefinitionForType(type: TransitionType): TransitionDefinition {
        val defId = when (type) {
            // NONE never reaches the renderer; the id is only a total mapping.
            TransitionType.NONE, TransitionType.DISSOLVE -> BuiltinTransitions.CROSS_DISSOLVE_ID
            TransitionType.FADE -> BuiltinTransitions.FADE_ID
            TransitionType.ZOOM_IN -> BuiltinTransitions.ZOOM_ID
            TransitionType.ZOOM_OUT -> BuiltinTransitions.ZOOM_OUT_ID
            TransitionType.SLIDE_LEFT -> BuiltinTransitions.SLIDE_LEFT_ID
            TransitionType.SLIDE_RIGHT -> BuiltinTransitions.SLIDE_RIGHT_ID
            TransitionType.PUSH_UP -> BuiltinTransitions.PUSH_UP_ID
            TransitionType.WIPE, TransitionType.WIPE_RIGHT -> BuiltinTransitions.WIPE_ID
            TransitionType.RADIAL_WIPE -> BuiltinTransitions.RADIAL_WIPE_ID
            TransitionType.FLASH -> BuiltinTransitions.FLASH_ID
            TransitionType.GLITCH -> BuiltinTransitions.GLITCH_ID
            TransitionType.GLITCH_WIPE -> BuiltinTransitions.GLITCH_WIPE_ID
            TransitionType.BLUR -> BuiltinTransitions.BLUR_ID
            TransitionType.ZOOM_BLUR -> BuiltinTransitions.ZOOM_BLUR_ID
            TransitionType.SPIN -> BuiltinTransitions.SPIN_ID
            TransitionType.WHIP_PAN -> BuiltinTransitions.WHIP_PAN_ID
            TransitionType.LIGHT_LEAK -> BuiltinTransitions.LIGHT_LEAK_ID
        }
        return registry.definition(defId) ?: BuiltinTransitions.crossDissolve()
    }

    /** Convert Domain Model [Transition] to a [TransitionInstance] given clip timeline spans */
    fun toTransitionInstance(
        transition: Transition,
        clips: List<VideoClip>,
        easing: Easing = Easing.of(Easing.Type.EASE_IN_OUT)
    ): TransitionInstance? {
        if (transition.type == TransitionType.NONE) return null
        if (transition.clipIndexBefore < 0 || transition.clipIndexBefore >= clips.size - 1) return null

        val clipA = clips[transition.clipIndexBefore]
        val clipB = clips[transition.clipIndexBefore + 1]

        val halfDur = transition.durationMs / 2
        val cutPoint = clipA.timelineStartMs + clipA.durationMs
        val startMs = (cutPoint - halfDur).coerceAtLeast(clipA.timelineStartMs)
        val endMs = (cutPoint + halfDur).coerceAtMost(clipB.timelineStartMs + clipB.durationMs)

        val def = getDefinitionForType(transition.type)

        return TransitionInstance(
            instanceId = transition.id,
            definitionId = def.id,
            outgoingClipId = clipA.id,
            incomingClipId = clipB.id,
            startMs = startMs,
            endMs = endMs,
            alignment = TransitionAlignment.CENTERED,
            easing = easing,
            parameters = parametersFor(transition.type),
            enabled = true
        )
    }

    /**
     * Shader overrides for a domain type. Wipe Right reuses the wipe shader with a
     * reversed direction so it is a real right-edge wipe, not a second copy of glitch.
     */
    fun parametersFor(type: TransitionType): Map<String, ParamValue> = when (type) {
        TransitionType.DISSOLVE -> mapOf("softness" to ParamValue.NormalizedValue(0.1f))
        TransitionType.ZOOM_IN -> mapOf("zoomAmount" to ParamValue.FloatValue(1.6f))
        TransitionType.WIPE -> mapOf(
            "feather" to ParamValue.NormalizedValue(0.05f),
            "direction" to ParamValue.Vec2Value(listOf(1f, 0f))
        )
        TransitionType.WIPE_RIGHT -> mapOf(
            "feather" to ParamValue.NormalizedValue(0.05f),
            "direction" to ParamValue.Vec2Value(listOf(-1f, 0f))
        )
        else -> emptyMap()
    }

    /** Create a new standalone TransitionEngine instance for a GL Context */
    fun createEngine(): TransitionEngine = TransitionEngine(registry)
}
