package com.ahstudio.transition

import com.ahstudio.transition.integration.TransitionAppBridge
import com.ahstudio.transition.transitions.BuiltinTransitions
import com.example.domain.model.TransitionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinTransitionDistinctTest {
    @Test fun `every builtin has a unique id and its own shader source`() {
        val all = BuiltinTransitions.allBuiltins()
        assertEquals(all.size, all.map { it.id }.toSet().size)
        val sources = all.map { it.shaders.getValue("main").fragment }
        assertEquals("Two transitions share the same shader source", sources.size, sources.toSet().size)
    }

    @Test fun `each user facing transition type maps to a matching definition`() {
        val expected = mapOf(
            TransitionType.FADE to BuiltinTransitions.FADE_ID,
            TransitionType.DISSOLVE to BuiltinTransitions.CROSS_DISSOLVE_ID,
            TransitionType.RADIAL_WIPE to BuiltinTransitions.RADIAL_WIPE_ID,
            TransitionType.WIPE_RIGHT to BuiltinTransitions.WIPE_ID,
            TransitionType.ZOOM_OUT to BuiltinTransitions.ZOOM_OUT_ID,
            TransitionType.PUSH_UP to BuiltinTransitions.PUSH_UP_ID,
            TransitionType.FLASH to BuiltinTransitions.FLASH_ID,
            TransitionType.GLITCH to BuiltinTransitions.GLITCH_ID,
            TransitionType.GLITCH_WIPE to BuiltinTransitions.GLITCH_WIPE_ID,
            TransitionType.BLUR to BuiltinTransitions.BLUR_ID,
            TransitionType.ZOOM_BLUR to BuiltinTransitions.ZOOM_BLUR_ID,
            TransitionType.SPIN to BuiltinTransitions.SPIN_ID,
            TransitionType.WHIP_PAN to BuiltinTransitions.WHIP_PAN_ID,
            TransitionType.LIGHT_LEAK to BuiltinTransitions.LIGHT_LEAK_ID,
            TransitionType.WIPE to BuiltinTransitions.WIPE_ID,
        )
        expected.forEach { (type, id) ->
            assertEquals(type.name, id, TransitionAppBridge.getDefinitionForType(type).id)
        }
        assertTrue(
            TransitionAppBridge.getDefinitionForType(TransitionType.FADE).id !=
                TransitionAppBridge.getDefinitionForType(TransitionType.DISSOLVE).id
        )
        val wipeDir = (TransitionAppBridge.parametersFor(TransitionType.WIPE)["direction"] as com.ahstudio.transition.core.ParamValue.Vec2Value).values
        val wipeRightDir = (TransitionAppBridge.parametersFor(TransitionType.WIPE_RIGHT)["direction"] as com.ahstudio.transition.core.ParamValue.Vec2Value).values
        assertTrue(wipeDir[0] > 0f)
        assertTrue(wipeRightDir[0] < 0f)
    }

    @Test fun `shaders do not use non deterministic inputs`() {
        BuiltinTransitions.allBuiltins().forEach { d ->
            val src = d.shaders.getValue("main").fragment
            assertTrue(d.id + " must not depend on uTime", !src.contains("uTime *") && !src.contains("* uTime"))
        }
    }
}
