package com.ahstudio.color.gpu

import android.opengl.GLES30
import com.ahstudio.color.core.ColorConfig
import com.ahstudio.color.core.ColorState

/** Variant key -> compiled program. Never compiles per frame (§49). */
class ColorShaderManager {

    data class VariantKey(
        val externalTex: Boolean,
        val hasCurves: Boolean,
        val hasHsl: Boolean,
        val hasWheels: Boolean,
        val hasLut3d: Boolean,
        val hasLut2d: Boolean,
        val hasSplit: Boolean,
        val hasBw: Boolean,
        val secCount: Int,
        val secMask: Boolean,
        val lutBefore: Boolean,
        val rangeExpand: Boolean,
        val hdrOutput: Boolean
    )

    class Program(val id: Int, val key: VariantKey) : ColorTextureUnits.ShaderProgramView {
        val uniforms = HashMap<String, Int>()
        fun loc(name: String): Int = uniforms.getOrPut(name) {
            GLES30.glGetUniformLocation(id, name)
        }

        override val programId: Int get() = id
        override fun uniformLocation(name: String): Int = loc(name)
    }

    private val cache = HashMap<VariantKey, Program>()

    fun variantKey(
        state: ColorState,
        cfg: ColorConfig,
        externalTex: Boolean,
        lut3dCapable: Boolean,
        hasMaskSource: Boolean = false
    ): VariantKey {
        val lut = state.lut
        val secs = state.secondaries.filter { it.enabled }.take(2)
        return VariantKey(
            externalTex = externalTex,
            hasCurves = !state.curves.isIdentity(),
            hasHsl = !state.hsl.isZero(),
            hasWheels = !state.wheels.isZero(),
            hasLut3d = lut != null && lut.isValid() && lut3dCapable && lut.kind != "1d",
            hasLut2d = lut != null && lut.isValid() && !lut3dCapable && lut.kind != "1d",
            hasSplit = !state.splitTone.isZero() || state.bw.toneSat > 0f,
            hasBw = state.bw.enabled,
            secCount = secs.size,
            secMask = hasMaskSource && secs.any { it.maskId != null },
            lutBefore = lut?.placement == "before_grade",
            rangeExpand = cfg.inputMetadata.range == com.ahstudio.color.core.ColorRange.LIMITED && cfg.inputMetadata.isMetadataReliable,
            hdrOutput = cfg.hdrOutput
        )
    }

    fun getProgram(key: VariantKey): Program {
        cache[key]?.let { return it }
        // The #define block is built by pure, JVM-tested code: the shader tests most flags BY
        // VALUE (`#if HAS_CURVES`, `#if LUT_AFTER && (HAS_LUT3D || HAS_LUT2D)`), so every
        // value-tested flag must carry an explicit 0/1 in EVERY variant. The previous valueless
        // `#define HAS_CURVES` style made `#if LUT_AFTER && (...)` expand to `#if && (...)` — a
        // guaranteed preprocessor error in every variant — so no colour-grade shader ever
        // compiled and ColorGradeStage silently disabled itself for preview and export.
        // See ColorShaderSource.variantDefines and ColorVariantShaderContractTest.
        val defines = ColorShaderSource.variantDefines(
            externalTex = key.externalTex,
            hasCurves = key.hasCurves,
            hasHsl = key.hasHsl,
            hasWheels = key.hasWheels,
            hasLut3d = key.hasLut3d,
            hasLut2d = key.hasLut2d,
            hasSplit = key.hasSplit,
            hasBw = key.hasBw,
            secCount = key.secCount,
            secMask = key.secMask,
            lutBefore = key.lutBefore,
            rangeExpand = key.rangeExpand,
            hdrOutput = key.hdrOutput
        )

        val vs = compile(GLES30.GL_VERTEX_SHADER, ColorShaderSource.VERT)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, ColorShaderSource.fragment(defines))
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        val status = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(prog)
            GLES30.glDeleteProgram(prog)
            throw IllegalStateException("Color shader link failed: $log")
        }
        GLES30.glDeleteShader(vs); GLES30.glDeleteShader(fs)
        val p = Program(prog, key)
        cache[key] = p
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val sh = GLES30.glCreateShader(type)
        GLES30.glShaderSource(sh, src)
        GLES30.glCompileShader(sh)
        val status = IntArray(1)
        GLES30.glGetShaderiv(sh, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(sh)
            GLES30.glDeleteShader(sh)
            throw IllegalStateException("Color shader compile failed: $log")
        }
        return sh
    }

    /** Call on EGL context loss / Activity recreation (§34, §67). */
    fun releaseAll() {
        cache.values.forEach { GLES30.glDeleteProgram(it.id) }
        cache.clear()
    }
}
