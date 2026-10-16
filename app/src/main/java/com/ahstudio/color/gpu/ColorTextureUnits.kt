package com.ahstudio.color.gpu

import android.opengl.GLES30
import android.util.Log

/**
 * THE texture-unit map of the colour pipeline. One place, one number per sampler — the fragment
 * shader declares several samplers and every one of them is assigned here.
 *
 * Why this exists (GL_INVALID_OPERATION, `glErrors=[0x502]`, black graded frames):
 *
 *  - A sampler uniform that is never written keeps its default value **0**. Unit 0 is the input
 *    unit, and on a video frame the input sampler is `samplerExternalOES`. Two *active* samplers
 *    of different types on one unit is an error the driver reports at draw time as
 *    `GL_INVALID_OPERATION` (0x502) — and a draw that fails leaves the target black.
 *  - A sampler whose unit has no texture of the sampler's own target bound is the second half of
 *    the same family: the sample is undefined/black (mobile drivers) or another 0x502 (desktop
 *    drivers validate it). So a unit must never be left holding nothing, and never hold a
 *    *different* target than the sampler that reads it (a `sampler3D` LUT and a `sampler2D`
 *    curve may therefore not share a unit).
 *  - The 2D-LUT fallback used to bind nothing at all: `uLut2d` stayed at unit 0 (clash with the
 *    OES input) and `uLutSize` stayed 0 (divide by zero -> NaN atlas uv -> black output).
 *
 * The map is therefore *fixed and explicit* rather than "whatever unit is free": every unit holds
 * exactly one kind of texture for the whole frame, which is what makes the 0x502 impossible.
 */
object ColorTextureUnits {

    /** The clip / layer picture: `GL_TEXTURE_2D` or `GL_TEXTURE_EXTERNAL_OES`. */
    const val INPUT = 0

    /** 256x1 RGBA curve texture (`uCurveTex`). */
    const val CURVE = 1

    /** 256x1 single-channel luma curve texture (`uLumaCurveTex`). */
    const val LUMA_CURVE = 2

    /**
     * Colour LUT. Holds a `GL_TEXTURE_3D` on ES3 contexts and the tiled `GL_TEXTURE_2D` atlas on
     * contexts that cannot sample 3D textures; never both at the same time, so the sampler type
     * always matches what is bound.
     */
    const val LUT = 3

    /** First secondary-mask slot (`uSecMask[0]`), then `SECONDARY_MASK_0 + i`. */
    const val SECONDARY_MASK_0 = 4

    /** Highest unit this pipeline uses. */
    const val MAX = 5

    /** Unit of secondary-mask slot [index]; the shader array has two entries. */
    fun secondaryMask(index: Int): Int = SECONDARY_MASK_0 + index

    /**
     * Samplers this pipeline can declare, with their type and unit. Used by [audit] to prove the
     * assignment the driver will actually see. Keys match the GLSL uniform names exactly
     * (`name[0]` for the first array element, as the driver reports it).
     */
    private val EXPECTED: List<Sampler> = listOf(
        Sampler("uInput", SamplerType.SAMPLER_2D_OR_EXTERNAL, INPUT),
        Sampler("uCurveTex", SamplerType.SAMPLER_2D, CURVE),
        Sampler("uLumaCurveTex", SamplerType.SAMPLER_2D, LUMA_CURVE),
        Sampler("uLut3d", SamplerType.SAMPLER_3D, LUT),
        Sampler("uLut2d", SamplerType.SAMPLER_2D, LUT),
        Sampler("uSecMask[0]", SamplerType.SAMPLER_2D, SECONDARY_MASK_0),
        Sampler("uSecMask[1]", SamplerType.SAMPLER_2D, secondaryMask(1))
    )

    enum class SamplerType { SAMPLER_2D, SAMPLER_3D, SAMPLER_2D_OR_EXTERNAL }

    private data class Sampler(val name: String, val type: SamplerType, val unit: Int)

    private val audited = HashSet<String>()

    /**
     * Reads back the unit of every sampler the linked [program] declares and logs the map once per
     * program. Reports the two defects that are otherwise invisible until a frame comes out black:
     * a sampler still on its default unit 0 next to another one, and two different sampler types
     * sharing a unit.
     *
     * Runs once per program variant; the (it is a read-back, not a draw) cost is irrelevant there.
     */
    fun audit(program: ShaderProgramView, variantKey: String) {
        val first = synchronized(audited) { audited.add(variantKey) }
        if (!first) return
        val maxUnits = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS, maxUnits, 0)
        val limit = if (maxUnits[0] > 0) maxUnits[0] else 16

        val assigned = LinkedHashMap<String, Pair<SamplerType, Int>>()
        val units = HashMap<Int, MutableList<String>>()
        for (s in EXPECTED) {
            val loc = program.uniformLocation(s.name)
            if (loc < 0) continue
            val value = IntArray(1)
            GLES30.glGetUniformiv(program.programId, loc, value, 0)
            val unit = value[0]
            assigned[s.name] = s.type to unit
            units.getOrPut(unit) { ArrayList() }.add(s.name)
        }

        val problems = ArrayList<String>()
        for ((unit, names) in units) {
            val kinds = names.mapNotNull { n -> assigned[n]?.first }.toSet()
            // samplerExternalOES and sampler2D are different types to the driver: same unit = 0x502.
            if (kinds.size > 1) {
                problems += "unit $unit is claimed by different sampler types: ${names.joinToString()}"
            }
            if (unit < 0 || unit >= limit) {
                problems += "unit $unit is outside MAX_COMBINED_TEXTURE_IMAGE_UNITS=$limit: ${names.joinToString()}"
            }
        }
        val map = assigned.entries.joinToString(prefix = "[", postfix = "]") { (name, v) -> "$name=${v.first}@unit${v.second}" }
        val message = "sampler units variant=\"$variantKey\" $map"
        if (problems.isEmpty()) {
            Log.i(TAG, message)
        } else {
            Log.e(
                TAG,
                "$message -> ${problems.joinToString("; ")}. This is the GL_INVALID_OPERATION (0x502) " +
                    "class of failure: the draw is rejected (or samples an undefined texture), which shows up " +
                    "as a black frame while every other export statistic still looks healthy."
            )
        }
    }

    /** What [audit] needs from a linked program, so it does not depend on the shader manager. */
    interface ShaderProgramView {
        val programId: Int
        fun uniformLocation(name: String): Int
    }

    private const val TAG = "ColorTextureUnits"
}
