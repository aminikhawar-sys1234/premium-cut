package com.example.engine.composition.gpu

/**
 * Texture-unit map of the composition fragment shaders (`GpuShaders.buildFragmentShader`).
 *
 * The map is fixed and explicit. Every sampler uniform in a program is written on every bind,
 * whether or not the feature that reads it is enabled, because an unwritten sampler uniform keeps
 * its default value — **0** — which is the unit the clip picture is bound to. Two active samplers
 * of different types (the picture sampler is `samplerExternalOES` on video frames, everything else
 * is `sampler2D`) resolving to the same unit is `GL_INVALID_OPERATION` on the draw call, and a
 * rejected draw leaves the target black. That is the `glErrors=[0x502]` + all-black-export failure
 * mode, so the units below are asserted by `toolchain/verify/verify_texture_units.py`.
 */
object GpuTextureUnits {

    /** The clip / layer picture: `samplerExternalOES` on OES frames, `sampler2D` otherwise. */
    const val PICTURE = 0

    /** Chroma-key background (`uChromaBgTex`, always `sampler2D`, 0 when there is no background). */
    const val CHROMA_BG = 2

    /** Highest unit the composition program touches. */
    const val MAX = 2
}
