package com.vfx.engine.effects

import com.vfx.engine.core.effect.EffectCategory
import com.vfx.engine.core.effect.EffectDefinition
import com.vfx.engine.core.params.ColorP
import com.vfx.engine.core.params.FloatP
import com.vfx.engine.gpu.glsl.SubjectGlsl

/**
 * The subject-driven assets of the production catalog: nine Body Effects and four AI Effects
 * (segmentation-driven character restyle). Each definition renders through [SubjectMaskRuntime]:
 * the on-device subject segmentation mask (the same ML Kit pipeline the background-removal tool
 * uses) gates where the effect draws, and with no mask available the body passes the frame
 * through unchanged — a body effect can never leak onto the whole scene.
 *
 * The AI clones are honest on-device transforms of the tracked person (shadow silhouette, golden
 * statue, cyber suit, cartoon avatar). Fully generative identity replacement is not possible
 * offline; these restyles are the real, render-everywhere equivalent.
 */
object SubjectEffects {

    private val definitions: List<EffectDefinition> by lazy {
        listOf(
            // ---------------- Body Effects ----------------
            subjectEffect(
                "subject.optical_scan", "Optical Scan", EffectCategory.STYLIZE,
                listOf(
                    FloatP("speed", "Sweeps / Sec", 0.1f, 2f, 0.05f, 0.45f),
                    FloatP("band", "Band Width", 0.03f, 0.3f, 0.01f, 0.10f)
                ),
                SubjectGlsl.OPTICAL_SCAN,
                description = "A cyan scanning band with a tracking grid sweeps the tracked body top-to-bottom. One pass."
            ) { p, s, _ ->
                p.setFloat("u_speed", s.float("speed"))
                p.setFloat("u_band", s.float("band"))
                p.setVec3("u_tint", 0.20f, 0.90f, 1.0f)
            },

            subjectEffect(
                "subject.phantom_clone", "Phantom Clone", EffectCategory.COMPOSITING,
                listOf(
                    FloatP("offset", "Clone Offset", 0.02f, 0.3f, 0.01f, 0.12f),
                    FloatP("speed", "Swing Speed", 0.1f, 2f, 0.05f, 0.6f),
                    FloatP("ghost", "Ghost Strength", 0f, 1f, 0.05f, 0.8f)
                ),
                SubjectGlsl.PHANTOM_CLONE,
                description = "A flickering phantom copy of the tracked person drifts beside the real body. One pass."
            ) { p, s, _ ->
                p.setFloat("u_offset", s.float("offset"))
                p.setFloat("u_speed", s.float("speed"))
                p.setFloat("u_ghost", s.float("ghost"))
            },

            subjectEffect(
                "subject.light_trails", "Light Trails", EffectCategory.LIGHT,
                listOf(
                    FloatP("speed", "Flow Speed", 0.1f, 3f, 0.05f, 1.2f),
                    FloatP("glow", "Trail Glow", 0f, 2f, 0.05f, 1f)
                ),
                SubjectGlsl.LIGHT_TRAILS,
                description = "Energy streaks flow along the body contour with a rim-brightened outline. One pass."
            ) { p, s, _ ->
                p.setFloat("u_speed", s.float("speed"))
                p.setFloat("u_glow", s.float("glow"))
            },

            subjectEffect(
                "subject.pixel_mask", "Pixel Mask", EffectCategory.STYLIZE,
                listOf(
                    FloatP("blocks", "Block Columns", 8f, 120f, 1f, 42f)
                ),
                SubjectGlsl.PIXEL_MASK,
                description = "Mosaic blocks only the tracked person; the background stays crisp. One pass."
            ) { p, s, _ ->
                p.setFloat("u_blocks", s.float("blocks"))
            },

            subjectEffect(
                "subject.action_paint", "Action Paint", EffectCategory.STYLIZE,
                listOf(
                    FloatP("speed", "Cycle Speed", 0.2f, 3f, 0.05f, 1f),
                    FloatP("splash", "Splash", 0f, 1f, 0.05f, 0.85f)
                ),
                SubjectGlsl.ACTION_PAINT,
                description = "Animated paint splats cycle hue across the tracked body, overlay-blended. One pass."
            ) { p, s, _ ->
                p.setFloat("u_speed", s.float("speed"))
                p.setFloat("u_splash", s.float("splash"))
            },

            subjectEffect(
                "subject.face_scan", "Face Scan", EffectCategory.STYLIZE,
                listOf(
                    FloatP("speed", "Sweeps / Sec", 0.1f, 2f, 0.05f, 0.5f),
                    FloatP("band", "Band Width", 0.03f, 0.3f, 0.01f, 0.09f)
                ),
                SubjectGlsl.FACE_SCAN,
                description = "A green verification band with ticks scans the head zone of the tracked subject. One pass."
            ) { p, s, _ ->
                p.setFloat("u_speed", s.float("speed"))
                p.setFloat("u_band", s.float("band"))
                p.setVec3("u_tint", 0.15f, 1.0f, 0.55f)
            },

            subjectEffect(
                "subject.light_dissolve", "Light Dissolve", EffectCategory.STYLIZE,
                listOf(
                    FloatP("speed", "Cycle Speed", 0.1f, 1.5f, 0.05f, 0.4f),
                    FloatP("ember", "Ember Glow", 0f, 2f, 0.05f, 1f)
                ),
                SubjectGlsl.LIGHT_DISSOLVE,
                description = "The tracked person dissolves into glowing embers along a noise front, then reforms. One pass."
            ) { p, s, _ ->
                p.setFloat("u_speed", s.float("speed"))
                p.setFloat("u_ember", s.float("ember"))
            },

            subjectEffect(
                "subject.luminescence", "Luminescence", EffectCategory.LIGHT,
                listOf(
                    FloatP("glow", "Glow", 0f, 2f, 0.05f, 1f),
                    FloatP("pulse", "Breathe", 0f, 1f, 0.05f, 0.6f)
                ),
                SubjectGlsl.LUMINESCENCE,
                description = "A breathing rim light traces the subject silhouette with a soft inner glow. One pass."
            ) { p, s, _ ->
                p.setFloat("u_glow", s.float("glow"))
                p.setFloat("u_pulse", s.float("pulse"))
                p.setVec3("u_tint", 0.45f, 0.85f, 1.0f)
            },

            subjectEffect(
                "subject.hand_drawn", "Hand Drawn", EffectCategory.STYLIZE,
                listOf(
                    FloatP("outline", "Ink Outline", 0f, 1f, 0.05f, 0.85f),
                    FloatP("paper", "Paper Grain", 0f, 1f, 0.05f, 0.55f)
                ),
                SubjectGlsl.HAND_DRAWN,
                description = "The tracked person becomes a pencil sketch: luminance hatching, ink edges, paper grain. One pass."
            ) { p, s, _ ->
                p.setFloat("u_outline", s.float("outline"))
                p.setFloat("u_paper", s.float("paper"))
            },

            // ---------------- AI Effects ----------------
            subjectEffect(
                "subject.clone_shadow", "Shadow Clone", EffectCategory.COMPOSITING,
                listOf(
                    FloatP("lift", "Volume Lift", 0f, 1f, 0.05f, 0.7f),
                    FloatP("rim", "Neon Rim", 0f, 2f, 0.05f, 1.1f)
                ),
                SubjectGlsl.CLONE_SHADOW,
                description = "AI character restyle: the tracked person becomes a cold shadow silhouette with a neon rim. One pass."
            ) { p, s, _ ->
                p.setFloat("u_lift", s.float("lift"))
                p.setFloat("u_rim", s.float("rim"))
            },

            subjectEffect(
                "subject.clone_golden", "Golden Statue", EffectCategory.COMPOSITING,
                listOf(
                    FloatP("shine", "Specular", 0f, 2f, 0.05f, 1.1f),
                    FloatP("relief", "Relief", 0f, 2f, 0.05f, 1f)
                ),
                SubjectGlsl.CLONE_GOLDEN,
                description = "AI character restyle: the tracked person becomes a golden statue — relief shading and key-light specular. One pass."
            ) { p, s, _ ->
                p.setFloat("u_shine", s.float("shine"))
                p.setFloat("u_relief", s.float("relief"))
            },

            subjectEffect(
                "subject.clone_cyber", "Cyber Neon", EffectCategory.COMPOSITING,
                listOf(
                    FloatP("lines", "Circuit Lines", 0f, 2f, 0.05f, 1f),
                    FloatP("glow", "Neon Glow", 0f, 2f, 0.05f, 1f)
                ),
                SubjectGlsl.CLONE_CYBER,
                description = "AI character restyle: the tracked person wears a carbon suit with crawling circuit traces and a neon rim. One pass."
            ) { p, s, _ ->
                p.setFloat("u_lines", s.float("lines"))
                p.setFloat("u_glow", s.float("glow"))
                p.setVec3("u_neon", 0.10f, 0.95f, 1.0f)
            },

            subjectEffect(
                "subject.clone_cartoon", "Cartoon Character", EffectCategory.COMPOSITING,
                listOf(
                    FloatP("levels", "Shade Levels", 2f, 12f, 1f, 7f),
                    FloatP("outline", "Ink Outline", 0f, 1f, 0.05f, 0.8f),
                    FloatP("boost", "Saturation", 0f, 1f, 0.05f, 0.65f)
                ),
                SubjectGlsl.CLONE_CARTOON,
                description = "AI character restyle: the tracked person becomes a cartoon avatar — posterize, saturation, ink outline. One pass."
            ) { p, s, _ ->
                p.setFloat("u_levels", s.float("levels"))
                p.setFloat("u_outline", s.float("outline"))
                p.setFloat("u_boost", s.float("boost"))
            },
        )
    }

    private fun subjectEffect(
        id: String,
        name: String,
        category: EffectCategory,
        params: List<FloatP>,
        body: String,
        description: String = "",
        uniforms: (com.vfx.engine.gpu.ShaderProgram, com.vfx.engine.core.effect.EffectSnapshot, com.vfx.engine.graph.RenderContext) -> Unit
    ) = EffectDefinition(
        id = id,
        name = name,
        category = category,
        params = params,
        description = description,
        runtimeFactory = { SubjectMaskRuntime(id, body, uniforms) }
    )

    /** The shared definition instances — registered once, reused by registry and tests. */
    fun all(): List<EffectDefinition> = definitions

    /** Engine ids of the subject-driven assets, in catalog order. */
    val ids: List<String> by lazy { definitions.map { it.id } }
}
