package com.vfx.engine.effects

import com.vfx.engine.core.effect.EffectCategory
import com.vfx.engine.core.effect.EffectDefinition
import com.vfx.engine.core.params.ColorP
import com.vfx.engine.core.params.FloatP
import com.vfx.engine.core.params.IntP
import com.vfx.engine.core.params.Vec2P
import com.vfx.engine.gpu.glsl.EffectGlsl

/**
 * The ten Video Effects assets of the production catalog.
 *
 * They are ordinary engine definitions — same contract as every other effect in
 * [BuiltinEffects] — so one registration drives all three consumers:
 *
 *  - live preview  -> [com.example.engine.effects.media3.PreviewVfxEffects] mirrors the same math
 *                     as a Media3 GL shader (params + intensity are read from the instance),
 *  - shading       -> [EffectGlsl] body, compiled by `SinglePassRuntime` (mask aware),
 *  - export        -> the clip's `vfxStackJson` runs through
 *                     `com.example.engine.composition.gpu.VfxStackStage` on the GPU compositor.
 *
 * Every effect is a single pass, deterministic for identical (frame, timestamp) except the
 * deliberately temporal ones (VHS tracking, datamosh blocks, grain, speed lines), which are
 * driven by the clip-local `u_time` the render graph already publishes.
 */
object VideoEffects {

    private val definitions: List<EffectDefinition> by lazy {
        listOf(
            simpleEffect(
                "blur.motion", "Motion Blur", EffectCategory.BLUR,
                listOf(
                    FloatP("distance", "Distance", 0f, 0.25f, 0.005f, 0.03f),
                    FloatP("angle", "Angle (deg)", 0f, 360f, 1f, 0f),
                    IntP("samples", "Samples", 2, 64, 24)
                ),
                EffectGlsl.MOTION_BLUR,
                description = "Directional accumulation with a stable per-pixel offset so the streak does not band. One pass."
            ) { p, s, _ ->
                p.setFloat("u_distance", s.float("distance"))
                p.setFloat("u_angle", s.float("angle"))
                p.setInt("u_samples", s.int("samples"))
            },

            simpleEffect(
                "video.vhs", "VHS Tape", EffectCategory.STYLIZE,
                listOf(
                    FloatP("bleed", "Chroma Bleed", 0f, 0.06f, 0.001f, 0.012f),
                    FloatP("noise", "Tape Noise", 0f, 0.5f, 0.005f, 0.08f),
                    FloatP("tracking", "Tracking Band", 0f, 1f, 0.01f, 0.5f),
                    FloatP("scanline", "Scanlines", 0f, 1f, 0.01f, 0.35f)
                ),
                EffectGlsl.VHS_TAPE,
                description = "Colour-under chroma bleed, per-line transport jitter, a rolling tracking band, hiss, dropouts and scanlines. One pass."
            ) { p, s, _ ->
                p.setFloat("u_bleed", s.float("bleed"))
                p.setFloat("u_noise", s.float("noise"))
                p.setFloat("u_tracking", s.float("tracking"))
                p.setFloat("u_scanline", s.float("scanline"))
            },

            simpleEffect(
                "video.crt", "CRT Scanlines", EffectCategory.STYLIZE,
                listOf(
                    FloatP("curvature", "Curvature", 0f, 0.6f, 0.01f, 0.22f),
                    FloatP("scanline", "Scanlines", 0f, 1f, 0.01f, 0.6f),
                    FloatP("grille", "Aperture Grille", 0f, 1f, 0.01f, 0.6f),
                    FloatP("flicker", "Mains Flicker", 0f, 1f, 0.01f, 0.25f),
                    FloatP("vignette", "Tube Vignette", 0f, 1f, 0.01f, 0.35f)
                ),
                EffectGlsl.CRT_SCANLINES,
                description = "Barrel-warped tube with RGB triad mask, scanlines, mains flicker, phosphor bleed and tube vignette. One pass."
            ) { p, s, _ ->
                p.setFloat("u_curvature", s.float("curvature"))
                p.setFloat("u_scanline", s.float("scanline"))
                p.setFloat("u_grille", s.float("grille"))
                p.setFloat("u_flicker", s.float("flicker"))
                p.setFloat("u_vignette", s.float("vignette"))
            },

            simpleEffect(
                "distort.datamosh", "Datamosh", EffectCategory.DISTORTION,
                listOf(
                    FloatP("amount", "Displacement", 0f, 0.3f, 0.005f, 0.08f),
                    FloatP("blockSize", "Block Size (px)", 6f, 128f, 2f, 32f),
                    FloatP("smear", "Smear Amount", 0f, 1f, 0.01f, 0.45f)
                ),
                EffectGlsl.DATAMOSH,
                description = "Macroblock displacement that re-picks its motion vectors over time, with flat pulled blocks and RGB channel tear. One pass."
            ) { p, s, _ ->
                p.setFloat("u_amount", s.float("amount"))
                p.setFloat("u_blockSize", s.float("blockSize"))
                p.setFloat("u_smear", s.float("smear"))
            },

            simpleEffect(
                "light.anamorphic", "Anamorphic Streaks", EffectCategory.LIGHT,
                listOf(
                    FloatP("amount", "Streak Amount", 0f, 2f, 0.02f, 0.6f),
                    FloatP("threshold", "Highlight Threshold", 0f, 1f, 0.01f, 0.55f),
                    FloatP("length", "Streak Length", 0.02f, 0.6f, 0.005f, 0.22f),
                    ColorP("tint", "Streak Tint", floatArrayOf(0.55f, 0.75f, 1f, 1f))
                ),
                EffectGlsl.ANAMORPHIC_STREAK,
                description = "33-tap horizontal highlight streak in the flare tint — the anamorphic lens look. One pass."
            ) { p, s, _ ->
                val tint = s.floats("tint")
                p.setFloat("u_amount", s.float("amount"))
                p.setFloat("u_threshold", s.float("threshold"))
                p.setFloat("u_length", s.float("length"))
                p.setVec3("u_tint", tint[0], tint[1], tint[2])
            },

            simpleEffect(
                "noise.grain", "Film Grain", EffectCategory.NOISE,
                listOf(
                    FloatP("amount", "Grain Amount", 0f, 0.5f, 0.005f, 0.08f),
                    FloatP("size", "Grain Size", 0.5f, 4f, 0.1f, 1.1f),
                    FloatP("shadow", "Shadow Bias", 0f, 1f, 0.01f, 0.5f),
                    FloatP("chroma", "Chroma Grain", 0f, 1f, 0.01f, 0.35f)
                ),
                EffectGlsl.FILM_GRAIN_PRO,
                description = "Sized grain re-seeded every 24 fps tick, weighted towards the shadows, with optional colour grain. One pass."
            ) { p, s, _ ->
                p.setFloat("u_amount", s.float("amount"))
                p.setFloat("u_size", s.float("size"))
                p.setFloat("u_shadow", s.float("shadow"))
                p.setFloat("u_chroma", s.float("chroma"))
            },

            simpleEffect(
                "light.halation", "Halation", EffectCategory.LIGHT,
                listOf(
                    FloatP("amount", "Bleed Amount", 0f, 2f, 0.02f, 0.7f),
                    FloatP("radius", "Bleed Radius", 0.002f, 0.06f, 0.001f, 0.016f),
                    FloatP("threshold", "Highlight Threshold", 0f, 1f, 0.01f, 0.62f),
                    ColorP("tint", "Bleed Tint", floatArrayOf(1f, 0.35f, 0.25f, 1f))
                ),
                EffectGlsl.HALATION,
                description = "Two-ring highlight bleed in a red-weighted tint, screened over the frame — the film-stock halation look. One pass."
            ) { p, s, _ ->
                val tint = s.floats("tint")
                p.setFloat("u_amount", s.float("amount"))
                p.setFloat("u_radius", s.float("radius"))
                p.setFloat("u_threshold", s.float("threshold"))
                p.setVec3("u_tint", tint[0], tint[1], tint[2])
            },

            simpleEffect(
                "light.flare", "Lens Flare", EffectCategory.LIGHT,
                listOf(
                    Vec2P("position", "Light Position", floatArrayOf(0.25f, 0.75f)),
                    FloatP("amount", "Flare Amount", 0f, 2f, 0.02f, 0.6f),
                    FloatP("halo", "Halo", 0f, 1f, 0.01f, 0.7f),
                    FloatP("ghosts", "Ghosts & Bar", 0f, 1f, 0.01f, 0.5f),
                    ColorP("tint", "Flare Tint", floatArrayOf(1f, 0.82f, 0.55f, 1f))
                ),
                EffectGlsl.LENS_FLARE,
                description = "Source halo, three ghosts mirrored through the frame centre and an anamorphic bar, all additive. One pass."
            ) { p, s, _ ->
                val pos = s.floats("position")
                val tint = s.floats("tint")
                p.setVec2("u_position", pos[0], pos[1])
                p.setFloat("u_amount", s.float("amount"))
                p.setFloat("u_halo", s.float("halo"))
                p.setFloat("u_ghosts", s.float("ghosts"))
                p.setVec3("u_tint", tint[0], tint[1], tint[2])
            },

            simpleEffect(
                "stylize.speedLines", "Speed Lines", EffectCategory.STYLIZE,
                listOf(
                    Vec2P("center", "Center", floatArrayOf(0.5f, 0.5f)),
                    FloatP("amount", "Line Amount", 0f, 1f, 0.01f, 0.55f),
                    FloatP("lines", "Line Count", 8f, 220f, 1f, 90f),
                    FloatP("speed", "Flicker Speed", 0f, 10f, 0.1f, 4f),
                    FloatP("reach", "Streak Reach", 0.05f, 0.8f, 0.01f, 0.35f)
                ),
                EffectGlsl.SPEED_LINES,
                description = "Radial anime speed lines that re-roll per angle segment over time, smeared inward from the edge. One pass."
            ) { p, s, _ ->
                val c = s.floats("center")
                p.setVec2("u_center", c[0], c[1])
                p.setFloat("u_amount", s.float("amount"))
                p.setFloat("u_lines", s.float("lines"))
                p.setFloat("u_speed", s.float("speed"))
                p.setFloat("u_reach", s.float("reach"))
            },

            simpleEffect(
                "distort.kaleidoscope", "Kaleidoscope", EffectCategory.DISTORTION,
                listOf(
                    IntP("segments", "Segments", 2, 16, 6),
                    FloatP("rotation", "Rotation (deg)", 0f, 360f, 1f, 0f),
                    FloatP("zoom", "Zoom", 0.4f, 3f, 0.05f, 1f),
                    Vec2P("center", "Center", floatArrayOf(0.5f, 0.5f))
                ),
                EffectGlsl.KALEIDOSCOPE,
                description = "Polar wedge fold: mirrored segments around a centre with rotation and zoom. One warp pass."
            ) { p, s, _ ->
                val c = s.floats("center")
                p.setVec2("u_center", c[0], c[1])
                p.setFloat("u_segments", s.int("segments").toFloat())
                p.setFloat("u_rotation", s.float("rotation"))
                p.setFloat("u_zoom", s.float("zoom"))
            }
        )
    }

    /** The shared definition instances — registered once, reused by registry and tests. */
    fun all(): List<EffectDefinition> = definitions

    /** Engine ids of the catalog's Video Effects, in catalog order. */
    val ids: List<String> by lazy { definitions.map { it.id } }
}
