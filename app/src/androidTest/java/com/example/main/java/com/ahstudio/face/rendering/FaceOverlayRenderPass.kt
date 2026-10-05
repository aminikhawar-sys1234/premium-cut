package com.ahstudio.face.rendering

import android.opengl.GLES20.*
import com.ahstudio.face.geometry.VideoTransform
import com.ahstudio.face.gl.GlUtil
import com.ahstudio.face.overlay.BlendMode
import com.ahstudio.face.overlay.OverlayPlacement
import java.nio.FloatBuffer

data class OverlayDrawItem(val placement: OverlayPlacement, val textureId: Int, val aspect: Float)

/**
 * One textured quad in viewport pixels (origin top-left, y down).
 * [rotationDegCcw] is a visual counter-clockwise rotation about the centre.
 */
data class PxOverlayItem(
    val centerX: Float,
    val centerY: Float,
    val halfWidthPx: Float,
    val halfHeightPx: Float,
    val rotationDegCcw: Float,
    val opacity: Float,
    val textureId: Int,
    val blendMode: BlendMode = BlendMode.NORMAL,
    val mirrored: Boolean = false,
    /** True when the texture already holds premultiplied alpha (Android Bitmaps uploaded with GLUtils). */
    val premultipliedTexture: Boolean = false,
    val zOrder: Int = 0,
)

class FaceOverlayRenderPass {

    private var program = 0
    private var aPos = 0; private var aUv = 0
    private var uCenter = 0; private var uHalfPx = 0; private var uViewport = 0; private var uRot = 0
    private var uOpacity = 0; private var uMirror = 0; private var uTex = 0; private var uPremul = 0

    private val quad: FloatBuffer = GlUtil.direct(floatArrayOf(
        -1f, -1f, 0f, 1f,
         1f, -1f, 1f, 1f,
        -1f,  1f, 0f, 0f,
         1f,  1f, 1f, 0f))

    // The quad is sized and rotated in PIXEL space and only then converted to clip space. Rotating
    // the unit quad first and scaling it afterwards would shear a rotated sticker on any
    // non-square viewport.
    private val vs = """
        attribute vec2 aPos;
        attribute vec2 aUv;
        uniform vec2 uCenter;
        uniform vec2 uHalfPx;
        uniform vec2 uViewport;
        uniform float uRot;
        varying vec2 vUv;
        void main() {
            float c = cos(uRot), s = sin(uRot);
            vec2 q = aPos * uHalfPx;
            vec2 p = vec2(q.x * c - q.y * s, q.x * s + q.y * c);
            gl_Position = vec4(uCenter + p / (uViewport * 0.5), 0.0, 1.0);
            vUv = aUv;
        }""".trimIndent()

    private val fs = """
        precision mediump float;
        uniform sampler2D uTex;
        uniform float uOpacity;
        uniform float uMirror;
        uniform float uPremul;
        varying vec2 vUv;
        void main() {
            vec2 uv = vec2(mix(vUv.x, 1.0 - vUv.x, uMirror), vUv.y);
            vec4 c = texture2D(uTex, uv);
            vec4 pm = mix(vec4(c.rgb * c.a, c.a), c, uPremul);
            gl_FragColor = pm * uOpacity;
        }""".trimIndent()

    fun ensureGl() {
        if (program != 0) return
        program = GlUtil.createProgram(vs, fs)
        aPos = glGetAttribLocation(program, "aPos")
        aUv = glGetAttribLocation(program, "aUv")
        uCenter = glGetUniformLocation(program, "uCenter")
        uHalfPx = glGetUniformLocation(program, "uHalfPx")
        uViewport = glGetUniformLocation(program, "uViewport")
        uRot = glGetUniformLocation(program, "uRot")
        uOpacity = glGetUniformLocation(program, "uOpacity")
        uMirror = glGetUniformLocation(program, "uMirror")
        uTex = glGetUniformLocation(program, "uTex")
        uPremul = glGetUniformLocation(program, "uPremul")
    }

    /** GL context lost: the program handle is dead, rebuild lazily. */
    fun reset() { program = 0 }

    fun release() {
        if (program != 0) glDeleteProgram(program)
        program = 0
    }

    /** Draws sticker placements given in normalized frame space (preview path of FaceEngineHost). */
    fun render(transform: VideoTransform, items: List<OverlayDrawItem>) {
        val px = items.map { item ->
            val pl = item.placement
            val c = transform.toViewport(pl.centerFrame)
            val widthPx = pl.widthFrame * transform.visibleFrameWidthPx
            PxOverlayItem(
                centerX = c.x, centerY = c.y,
                halfWidthPx = widthPx / 2f,
                halfHeightPx = widthPx / item.aspect / 2f,
                rotationDegCcw = pl.rotationDeg,
                opacity = pl.opacity,
                textureId = item.textureId,
                blendMode = pl.blendMode,
                mirrored = pl.mirrored,
                zOrder = pl.zOrder,
            )
        }
        renderPx(transform.viewportWidth, transform.viewportHeight, px)
    }

    /**
     * Draws quads into the currently bound framebuffer. The caller owns framebuffer, viewport and
     * client-array state (VAO 0, no ARRAY_BUFFER bound).
     */
    fun renderPx(viewportWidth: Int, viewportHeight: Int, items: List<PxOverlayItem>) {
        if (items.isEmpty() || viewportWidth <= 0 || viewportHeight <= 0) return
        ensureGl()
        glUseProgram(program)
        glEnable(GL_BLEND)
        glDisable(GL_DEPTH_TEST)
        val vw = viewportWidth.toFloat()
        val vh = viewportHeight.toFloat()
        glUniform2f(uViewport, vw, vh)
        items.sortedBy { it.zOrder }.forEach { item ->
            when (item.blendMode) {
                BlendMode.ADDITIVE -> glBlendFunc(GL_ONE, GL_ONE)
                else -> glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
            }
            glActiveTexture(GL_TEXTURE0)
            glBindTexture(GL_TEXTURE_2D, item.textureId)
            glUniform1i(uTex, 0)
            glUniform2f(uCenter, item.centerX / vw * 2f - 1f, 1f - item.centerY / vh * 2f)
            glUniform2f(uHalfPx, item.halfWidthPx, item.halfHeightPx)
            glUniform1f(uRot, Math.toRadians(item.rotationDegCcw.toDouble()).toFloat())
            glUniform1f(uOpacity, item.opacity)
            glUniform1f(uMirror, if (item.mirrored) 1f else 0f)
            glUniform1f(uPremul, if (item.premultipliedTexture) 1f else 0f)
            quad.position(0)
            glVertexAttribPointer(aPos, 2, GL_FLOAT, false, 16, quad)
            glEnableVertexAttribArray(aPos)
            quad.position(2)
            glVertexAttribPointer(aUv, 2, GL_FLOAT, false, 16, quad)
            glEnableVertexAttribArray(aUv)
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
            glDisableVertexAttribArray(aPos)
            glDisableVertexAttribArray(aUv)
        }
        glDisable(GL_BLEND)
        glBindTexture(GL_TEXTURE_2D, 0)
    }
}
