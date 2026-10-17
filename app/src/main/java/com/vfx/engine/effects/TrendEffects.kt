package com.vfx.engine.effects

import com.vfx.engine.core.effect.EffectCategory
import com.vfx.engine.core.effect.EffectDefinition
import com.vfx.engine.core.params.FloatP
import com.vfx.engine.core.params.Vec2P
import com.vfx.engine.gpu.glsl.TrendGlsl

/**
 * The CapCut-trending full-frame assets of the production catalog: eight Video Effects and seven
 * Photo Effects. They are ordinary engine definitions — same contract as every other effect in
 * [BuiltinEffects] — so one registration drives all three consumers:
 *
 *  - shading      -> [TrendGlsl] body, compiled by `SinglePassRuntime` (mask aware),
 *  - preview      -> the clip's `vfxStackJson` routes the whole stack to the GPU compositor
 *                    (PreviewVfxCoverage.hasAnyActiveEffects), which renders this exact runtime,
 *  - export       -> the same stack runs through `VfxStackStage` on the export render graph.
 *
 * Every effect is a single pass and a pure function of (frame, clip-local time).
 */
object TrendEffects {

    private val definitions: List<EffectDefinition> by lazy {
        listOf(
            // ---------------- Video Effects ----------------
            simpleEffect(
                "trend.explosion", "Explosion", EffectCategory.STYLIZE,
                listOf(
                    FloatP("scale", "Blast Size", 0.2f, 1.6f, 0.05f, 0.7f),
                    FloatP("speed", "Loop Speed", 0.1f, 2f, 0.05f, 0.5f),
                    FloatP("heat", "Flame Amount", 0f, 2f, 0.05f, 1f)
                ),
                TrendGlsl.EXPLOSION,
                description = "Shockwave ring, fbm fireball and an ignition flash with an outward frame jolt, looping. One pass."
            ) { p, s, _ ->
                p.setFloat("u_scale", s.float("scale"))
                p.setFloat("u_speed", s.float("speed"))
                p.setFloat("u_heat", s.float("heat"))
            },

            simpleEffect(
                "trend.ink_spill", "Ink Spill", EffectCategory.STYLIZE,
                listOf(
                    FloatP("spread", "Spread", 0.2f, 1.4f, 0.05f, 0.9f),
                    FloatP("speed", "Loop Speed", 0.05f, 1f, 0.01f, 0.22f),
                    FloatP("dark", "Ink Darkness", 0f, 1f, 0.05f, 0.92f)
                ),
                TrendGlsl.INK_SPILL,
                description = "An fbm-perturbed ink front blooms from the centre, wet-sheens, then recedes — looping. One pass."
            ) { p, s, _ ->
                p.setFloat("u_spread", s.float("spread"))
                p.setFloat("u_speed", s.float("speed"))
                p.setFloat("u_dark", s.float("dark"))
            },

            simpleEffect(
                "trend.earth", "Rotating Earth", EffectCategory.STYLIZE,
                listOf(
                    FloatP("size", "Globe Size", 0.10f, 0.5f, 0.01f, 0.24f),
                    Vec2P("position", "Position", floatArrayOf(0.72f, 0.30f)),
                    FloatP("spin", "Spin Speed", 0f, 1.5f, 0.01f, 0.18f),
                    FloatP("clouds", "Clouds", 0f, 1f, 0.05f, 0.55f)
                ),
                TrendGlsl.EARTH,
                description = "Procedural globe: seam-free 3D-noise continents, drifting clouds, key light and an atmosphere rim. One pass."
            ) { p, s, _ ->
                val pos = s.floats("position")
                p.setFloat("u_size", s.float("size"))
                p.setVec2("u_center", pos[0], pos[1])
                p.setFloat("u_spin", s.float("spin"))
                p.setFloat("u_clouds", s.float("clouds"))
            },

            simpleEffect(
                "trend.magical_tome", "Magical Tome", EffectCategory.LIGHT,
                listOf(
                    FloatP("scale", "Sigil Size", 0.3f, 2f, 0.05f, 1f),
                    FloatP("speed", "Loop Speed", 0.1f, 1.5f, 0.05f, 0.35f),
                    FloatP("hue", "Magic Hue", 0f, 1f, 0.02f, 0.15f)
                ),
                TrendGlsl.MAGICAL_TOME,
                description = "Three counter-rotating rune rings with ticks, orbiting twinkling sparkles and a purple-gold cast. One pass."
            ) { p, s, _ ->
                p.setFloat("u_scale", s.float("scale"))
                p.setFloat("u_speed", s.float("speed"))
                p.setFloat("u_hue", s.float("hue"))
            },

            simpleEffect(
                "trend.cut_shift", "Cut Shift", EffectCategory.DISTORTION,
                listOf(
                    FloatP("slices", "Slice Rows", 4f, 24f, 1f, 10f),
                    FloatP("speed", "Shift Speed", 0.5f, 6f, 0.1f, 2.2f),
                    FloatP("rgb", "RGB Split", 0f, 1f, 0.05f, 0.55f)
                ),
                TrendGlsl.CUT_SHIFT,
                description = "Random horizontal slices jump on quantized time steps with a step-synced RGB split. One warp pass."
            ) { p, s, _ ->
                p.setFloat("u_slices", s.float("slices"))
                p.setFloat("u_speed", s.float("speed"))
                p.setFloat("u_rgb", s.float("rgb"))
            },

            simpleEffect(
                "trend.fire", "Fire", EffectCategory.STYLIZE,
                listOf(
                    FloatP("height", "Flame Height", 0.15f, 0.7f, 0.02f, 0.38f),
                    FloatP("speed", "Burn Speed", 0.3f, 2.5f, 0.05f, 1.1f),
                    FloatP("heat", "Heat", 0f, 2f, 0.05f, 1f)
                ),
                TrendGlsl.FIRE,
                description = "Rising two-layer fbm flames from the bottom edge with embers and a heat-shimmer warp. One warp pass."
            ) { p, s, _ ->
                p.setFloat("u_height", s.float("height"))
                p.setFloat("u_speed", s.float("speed"))
                p.setFloat("u_heat", s.float("heat"))
            },

            simpleEffect(
                "trend.mini_zoom", "Mini Zoom", EffectCategory.DISTORTION,
                listOf(
                    FloatP("speed", "Pulses / Sec", 0.2f, 3f, 0.05f, 0.75f),
                    FloatP("amount", "Punch Depth", 0f, 0.5f, 0.01f, 0.16f),
                    FloatP("shake", "Shake", 0f, 0.1f, 0.002f, 0.02f)
                ),
                TrendGlsl.MINI_ZOOM,
                description = "Beat-style zoom punch that snaps in and eases back with a tiny shake and a matching vignette hit. One warp pass."
            ) { p, s, _ ->
                p.setFloat("u_speed", s.float("speed"))
                p.setFloat("u_amount", s.float("amount"))
                p.setFloat("u_shake", s.float("shake"))
            },

            simpleEffect(
                "trend.white_flash", "White Flash", EffectCategory.LIGHT,
                listOf(
                    FloatP("speed", "Flashes / Sec", 0.2f, 4f, 0.05f, 0.9f),
                    FloatP("hold", "Hold", 0f, 0.4f, 0.01f, 0.05f),
                    FloatP("bloom", "Bloom Lift", 0f, 1f, 0.05f, 0.35f)
                ),
                TrendGlsl.WHITE_FLASH,
                description = "Camera strobe: sharp attack, exponential decay, optional hold plateau and a bloom lift. One pass."
            ) { p, s, _ ->
                p.setFloat("u_speed", s.float("speed"))
                p.setFloat("u_hold", s.float("hold"))
                p.setFloat("u_bloom", s.float("bloom"))
            },

            // ---------------- Photo Effects ----------------
            simpleEffect(
                "trend.expand_x", "Expand X", EffectCategory.TRANSFORM,
                listOf(
                    FloatP("expand", "Expand", 0.02f, 0.35f, 0.01f, 0.14f),
                    FloatP("falloff", "Edge Falloff", 0f, 1f, 0.05f, 0.55f)
                ),
                TrendGlsl.EXPAND_X,
                description = "Widens the frame by mirroring the edges outwards with a progressive falloff. One warp pass."
            ) { p, s, _ ->
                p.setFloat("u_expand", s.float("expand"))
                p.setFloat("u_falloff", s.float("falloff"))
            },

            simpleEffect(
                "trend.orbit360", "Orbit 360", EffectCategory.DISTORTION,
                listOf(
                    FloatP("speed", "Orbit Speed", 0.05f, 0.8f, 0.01f, 0.18f),
                    FloatP("reach", "Swing", 0f, 1f, 0.05f, 0.5f),
                    FloatP("twist", "Twist", 0f, 1f, 0.05f, 0.2f)
                ),
                TrendGlsl.ORBIT_360,
                description = "Cylindrical pseudo-360 camera orbit: edges compress as the view swings around. One warp pass."
            ) { p, s, _ ->
                p.setFloat("u_speed", s.float("speed"))
                p.setFloat("u_reach", s.float("reach"))
                p.setFloat("u_twist", s.float("twist"))
            },

            simpleEffect(
                "trend.layer_echo", "Layer", EffectCategory.COMPOSITING,
                listOf(
                    FloatP("offset", "Layer Offset", 0f, 2f, 0.05f, 1f),
                    FloatP("scaleStep", "Scale Step", 0f, 1f, 0.05f, 0.6f),
                    FloatP("mixAmt", "Layer Mix", 0f, 1f, 0.05f, 0.55f)
                ),
                TrendGlsl.LAYER_ECHO,
                description = "Three screen-blended echo layers with staggered offset and scale — the dreamy layered-photo look. One pass."
            ) { p, s, _ ->
                p.setFloat("u_offset", s.float("offset"))
                p.setFloat("u_scaleStep", s.float("scaleStep"))
                p.setFloat("u_mixAmt", s.float("mixAmt"))
            },

            simpleEffect(
                "trend.zoom3d", "3D Zoom", EffectCategory.DISTORTION,
                listOf(
                    FloatP("zoom", "Zoom Depth", 0f, 0.5f, 0.01f, 0.22f),
                    FloatP("depth", "Parallax", 0f, 1f, 0.05f, 0.6f),
                    FloatP("speed", "Push Speed", 0.05f, 0.6f, 0.01f, 0.14f)
                ),
                TrendGlsl.ZOOM_3D,
                description = "Ken-Burns push with luminance parallax: bright content zooms ahead of the background. One warp pass."
            ) { p, s, _ ->
                p.setFloat("u_zoom", s.float("zoom"))
                p.setFloat("u_depth", s.float("depth"))
                p.setFloat("u_speed", s.float("speed"))
            },

            simpleEffect(
                "trend.ai_filter", "AI Filter", EffectCategory.COLOR,
                listOf(
                    FloatP("warmth", "Split Tone", 0f, 1f, 0.05f, 0.65f),
                    FloatP("glow", "Glow", 0f, 1f, 0.05f, 0.4f),
                    FloatP("pop", "Vibrance", 0f, 1f, 0.05f, 0.6f)
                ),
                TrendGlsl.AI_FILTER,
                description = "Detail-aware micro-smooth, teal-orange split tone, vibrance and a bloom lift — the modern AI look. One pass."
            ) { p, s, _ ->
                p.setFloat("u_warmth", s.float("warmth"))
                p.setFloat("u_glow", s.float("glow"))
                p.setFloat("u_pop", s.float("pop"))
            },

            simpleEffect(
                "trend.sad", "Sad", EffectCategory.COLOR,
                listOf(
                    FloatP("mood", "Mood", 0f, 1f, 0.05f, 0.75f),
                    FloatP("vig", "Vignette", 0f, 1f, 0.05f, 0.5f),
                    FloatP("grain", "Grain", 0f, 1f, 0.05f, 0.4f)
                ),
                TrendGlsl.SAD,
                description = "Desaturated cold split with sunken shadows, a sighing brightness dip, vignette and fine grain. One pass."
            ) { p, s, _ ->
                p.setFloat("u_mood", s.float("mood"))
                p.setFloat("u_vig", s.float("vig"))
                p.setFloat("u_grain", s.float("grain"))
            },

            simpleEffect(
                "trend.cartoon3d", "3D Cartoon", EffectCategory.STYLIZE,
                listOf(
                    FloatP("levels", "Shade Levels", 2f, 12f, 1f, 6f),
                    FloatP("outline", "Ink Outline", 0f, 1f, 0.05f, 0.75f),
                    FloatP("sat", "Saturation", 0f, 1f, 0.05f, 0.7f)
                ),
                TrendGlsl.CARTOON_3D,
                description = "Toon shading: posterized bands with band shading, saturation boost and a Sobel ink outline. One pass."
            ) { p, s, _ ->
                p.setFloat("u_levels", s.float("levels"))
                p.setFloat("u_outline", s.float("outline"))
                p.setFloat("u_sat", s.float("sat"))
            },
        )
    }

    /** The shared definition instances — registered once, reused by registry and tests. */
    fun all(): List<EffectDefinition> = definitions

    /** Engine ids of the trending full-frame assets, in catalog order. */
    val ids: List<String> by lazy { definitions.map { it.id } }
}
