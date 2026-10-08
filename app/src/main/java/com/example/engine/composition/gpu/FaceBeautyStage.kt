package com.example.engine.composition.gpu

import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import com.ahstudio.face.deformation.FaceWarpRegistry
import com.ahstudio.face.gl.FullscreenQuad
import com.example.domain.model.VideoClip
import com.example.engine.InterpolatedClipTransform

/**
 * Skin smoothing and teeth whitening inside the tracked face. The ellipse and mouth box come from
 * the same face source as Face Reshape, so the grade follows the subject. Pass-through otherwise.
 */
class FaceBeautyStage {
  private val quad = FullscreenQuad()
  private val fbo = GlFramebuffer()
  private var program = 0
  private var disabled = false

  fun apply(
    clip: VideoClip?,
    timelinePosMs: Long,
    srcTex: Int,
    width: Int,
    height: Int,
    blocking: Boolean,
    transform: InterpolatedClipTransform? = null,
    placementOverride: com.ahstudio.face.deformation.FaceWarpMapper.Placement? = null,
  ): Int {
    if (disabled || clip == null || srcTex <= 0 || width <= 0 || height <= 0) return srcTex
    val params = FaceWarpRegistry.paramsFor(clip.id) ?: return srcTex
    if (params.skinSmooth <= 0.001f && params.teethWhiten <= 0.001f) return srcTex
    val source = FaceWarpRegistry.faceSource ?: return srcTex
    val faces = try {
      source.facesAt(clip.id, faceSourceTimeUs(clip, timelinePosMs), blocking)
    } catch (t: Throwable) {
      if (blocking) throw t
      emptyList()
    }
    val face = faces.maxByOrNull { it.bounds.width * it.bounds.height } ?: return srcTex
    val b = face.bounds
    val placement = placementOverride ?: faceClipPlacement(clip, timelinePosMs, width, height, transform)
    fun uvOf(p: com.ahstudio.face.core.Vec2): Pair<Float, Float> {
      val mapped = com.ahstudio.face.deformation.FaceWarpMapper.toTextureSpace(
        listOf(com.ahstudio.face.deformation.WarpOp(
          com.ahstudio.face.deformation.WarpType.PUSH, p, 0.04f, 0.001f, com.ahstudio.face.core.Vec2(0f, 1f)
        )),
        placement
      ).firstOrNull() ?: return 0.5f to 0.5f
      return (mapped.center.x / width.toFloat()) to (mapped.center.y / height.toFloat())
    }
    val faceUv = uvOf(com.ahstudio.face.core.Vec2(b.centerX, b.centerY))
    val faceEdge = uvOf(com.ahstudio.face.core.Vec2(b.right, b.centerY))
    val faceRx = kotlin.math.abs(faceEdge.first - faceUv.first).coerceAtLeast(0.02f)
    val faceRy = (faceRx * (b.height / b.width.coerceAtLeast(0.01f))).coerceAtLeast(0.02f)
    val mouthPt = face.landmarks?.mouthCenter ?: com.ahstudio.face.core.Vec2(b.centerX, b.top + b.height * 0.72f)
    val mouthUv = uvOf(mouthPt)
    if (program == 0) program = compile()
    if (program == 0) return srcTex

    val prevFbo = IntArray(1); GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
    val prevViewport = IntArray(4); GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, prevViewport, 0)
    val prevProgram = IntArray(1); GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, prevProgram, 0)
    return try {
      fbo.setup(width, height)
      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo.getFboId())
      GLES20.glViewport(0, 0, width, height)
      GLES20.glDisable(GLES20.GL_BLEND)
      GLES20.glUseProgram(program)
      GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex)
      GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)
      GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uFaceCenter"), faceUv.first, faceUv.second)
      GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uFaceRadius"), faceRx, faceRy)
      GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uMouthCenter"), mouthUv.first, mouthUv.second)
      GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uMouthRadius"), faceRx * 0.38f, faceRy * 0.16f)
      GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uSkin"), params.skinSmooth)
      GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uTeeth"), params.teethWhiten)
      GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uTexel"), 1f / width, 1f / height)
      val aPos = GLES20.glGetAttribLocation(program, "aPos")
      quad.draw(aPos)
      fbo.getTextureId()
    } catch (t: Throwable) {
      Log.e(TAG, "Face beauty failed; passing frames through", t)
      if (blocking) throw t
      disabled = true
      srcTex
    } finally {
      GLES20.glUseProgram(prevProgram[0])
      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prevFbo[0])
      GLES20.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3])
    }
  }

  fun onContextLost() {
    if (program != 0) GLES20.glDeleteProgram(program)
    program = 0
    fbo.release()
    disabled = false
  }

  fun release() = onContextLost()

  private fun compile(): Int {
    val vs = """
      attribute vec2 aPos;
      varying vec2 vUv;
      void main() {
        vUv = aPos * 0.5 + 0.5;
        gl_Position = vec4(aPos, 0.0, 1.0);
      }
    """.trimIndent()
    val fs = """
      precision mediump float;
      varying vec2 vUv;
      uniform sampler2D uTex;
      uniform vec2 uFaceCenter;
      uniform vec2 uFaceRadius;
      uniform vec2 uMouthCenter;
      uniform vec2 uMouthRadius;
      uniform float uSkin;
      uniform float uTeeth;
      uniform vec2 uTexel;
      void main() {
        vec4 src = texture2D(uTex, vUv);
        vec2 fp = (vUv - uFaceCenter) / max(uFaceRadius, vec2(0.001));
        float face = 1.0 - smoothstep(0.75, 1.05, length(fp));
        vec3 blur = src.rgb;
        blur += texture2D(uTex, vUv + vec2(uTexel.x * 2.0, 0.0)).rgb;
        blur += texture2D(uTex, vUv - vec2(uTexel.x * 2.0, 0.0)).rgb;
        blur += texture2D(uTex, vUv + vec2(0.0, uTexel.y * 2.0)).rgb;
        blur += texture2D(uTex, vUv - vec2(0.0, uTexel.y * 2.0)).rgb;
        blur /= 5.0;
        vec3 smoothed = mix(src.rgb, blur, face * uSkin * 0.85);
        vec2 mp = (vUv - uMouthCenter) / max(uMouthRadius, vec2(0.001));
        float mouth = 1.0 - smoothstep(0.65, 1.0, length(mp));
        float luma = dot(smoothed, vec3(0.299, 0.587, 0.114));
        vec3 white = mix(smoothed, vec3(luma), 0.35) + vec3(0.08, 0.08, 0.05);
        white.r *= 0.96;
        vec3 outRgb = mix(smoothed, white, mouth * uTeeth);
        gl_FragColor = vec4(outRgb, src.a);
      }
    """.trimIndent()
    return link(vs, fs)
  }

  private fun link(vsSrc: String, fsSrc: String): Int {
    fun shader(type: Int, src: String): Int {
      val id = GLES20.glCreateShader(type)
      GLES20.glShaderSource(id, src)
      GLES20.glCompileShader(id)
      return id
    }
    val vs = shader(GLES20.GL_VERTEX_SHADER, vsSrc)
    val fs = shader(GLES20.GL_FRAGMENT_SHADER, fsSrc)
    val p = GLES20.glCreateProgram()
    GLES20.glAttachShader(p, vs)
    GLES20.glAttachShader(p, fs)
    GLES20.glLinkProgram(p)
    GLES20.glDeleteShader(vs)
    GLES20.glDeleteShader(fs)
    return p
  }

  private companion object {
    const val TAG = "FaceBeautyStage"
  }
}
