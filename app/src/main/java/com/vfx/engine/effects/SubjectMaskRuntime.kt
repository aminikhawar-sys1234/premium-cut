package com.vfx.engine.effects

import android.opengl.GLES30
import com.vfx.engine.core.effect.EffectSnapshot
import com.vfx.engine.core.effect.WorkingSpace
import com.vfx.engine.gpu.GpuEffectRuntime
import com.vfx.engine.gpu.GpuTexture
import com.vfx.engine.gpu.ShaderProgram
import com.vfx.engine.gpu.glsl.ShaderTemplate
import com.vfx.engine.gpu.glsl.SubjectGlsl
import com.vfx.engine.graph.GraphBuilder
import com.vfx.engine.graph.RenderContext
import java.nio.ByteBuffer

/**
 * Single-pass runtime for the subject-driven effects (Body Effects / AI Effects).
 *
 * The effect body reads the on-device subject segmentation mask through `u_maskTex` (bound as a
 * secondary input from [SubjectMaskFeed]) and gates on `u_hasMask`:
 *  - mask available -> the styled frame is composited only where the subject is;
 *  - no mask        -> `u_hasMask = 0` and every body passes the frame through unchanged, so a
 *                      missing model can never smear a body effect across the whole scene.
 *  - thumbnail rasterization ([SubjectMaskFeed.allowFullFrameFallback]) binds a 1x1 white mask
 *    so the panel icon communicates the look on the synthetic base frame.
 *
 * Must run on the GL thread that owns the compositor's VfxStackStage.
 */
class SubjectMaskRuntime(
    private val shaderName: String,
    private val effectBody: String,
    private val uniformSetter: (ShaderProgram, EffectSnapshot, RenderContext) -> Unit = { _, _, _ -> }
) : GpuEffectRuntime {

    override val workingSpace: WorkingSpace = WorkingSpace.GAMMA

    /** 1x1 white mask used only by the thumbnail path (full-frame look preview). */
    private var whiteMask: GpuTexture? = null

    override fun buildPasses(builder: GraphBuilder, input: GpuTexture, s: EffectSnapshot): GpuTexture {
        val ctx = builder.renderCtx
        val es3 = ctx.caps.glesMajor >= 3
        val frag = ShaderTemplate.fragment(
            es3,
            effectBody,
            extraUniforms = listOf("uniform sampler2D u_maskTex;", "uniform float u_hasMask;"),
            chunkExtras = SubjectGlsl.SUBJECT_COMMON
        )
        val program = ctx.programs.get(shaderName, ShaderTemplate.vertex(es3), frag)
        val mask = SubjectMaskFeed.current()
        val fallback = mask == null && SubjectMaskFeed.allowFullFrameFallback
        if (fallback && (whiteMask == null || whiteMask!!.released)) whiteMask = createWhiteMask()
        val out = builder.addPass(shaderName, program, input) { p ->
            p.setFloat("u_intensity", s.intensity)
            p.setFloat("u_warpIntensity", 0f)
            p.setFloat("u_hasMask", if (mask != null || fallback) 1f else 0f)
            uniformSetter(p, s, ctx)
        }
        val bound = mask?.let {
            GpuTexture.adoptExternal(it.maskTex, it.maskWidth, it.maskHeight)
        } ?: (if (fallback) whiteMask else null)
        if (bound != null) builder.addSecondaryInput("u_maskTex", bound)
        return out
    }

    private fun createWhiteMask(): GpuTexture {
        val t = GpuTexture(width = 1, height = 1)
        val px = ByteBuffer.allocateDirect(4).put(byteArrayOf(-1, -1, -1, -1)).position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t.handle)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, 1, 1, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, px)
        return t
    }

    override fun release() {
        whiteMask?.release()
        whiteMask = null
    }
}
