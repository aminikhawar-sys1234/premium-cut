package com.example.engine.effects.media3

import android.content.Context
import android.opengl.GLES20
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * A flexible, extensible Media3 [GlEffect] that runs custom OpenGL fragment shaders
 * on video frames before they reach the video encoder.
 *
 * @param name Diagnostic identifier for this custom effect.
 * @param fragmentShader Custom GLSL fragment shader source code.
 * @param vertexShader Custom GLSL vertex shader source code (defaults to Media3 full-screen quad).
 * @param uniformBinder Optional callback to dynamically bind uniforms for each rendered frame.
 * @param onRelease Optional cleanup hook (e.g. deleting custom auxiliary textures).
 */
@OptIn(UnstableApi::class)
open class CustomShaderGlEffect(
  val name: String,
  val fragmentShader: String,
  val vertexShader: String = DEFAULT_VERTEX_SHADER,
  private val uniformBinder: ((program: GlProgram, presentationTimeUs: Long, width: Int, height: Int) -> Unit)? = null,
  private val onRelease: (() -> Unit)? = null
) : GlEffect {

  companion object {
    /**
     * Standard vertex shader compatible with Media3's normalized coordinate quad.
     */
    const val DEFAULT_VERTEX_SHADER: String = """#version 100
attribute vec4 aFramePosition;
uniform mat4 uTransformationMatrix;
uniform mat4 uTexTransformationMatrix;
varying vec2 vTexSamplingCoord;

void main() {
  gl_Position = uTransformationMatrix * aFramePosition;
  vec4 texturePosition = vec4(aFramePosition.x * 0.5 + 0.5,
                              aFramePosition.y * 0.5 + 0.5, 0.0, 1.0);
  vTexSamplingCoord = (uTexTransformationMatrix * texturePosition).xy;
}
"""

    /**
     * Opaque copy. Decoder intermediate textures often carry A=0; leaving that
     * alpha in the output composites as a black TextureView.
     */
    const val IDENTITY_FRAGMENT_SHADER: String = """#version 100
precision mediump float;
uniform sampler2D uTexSampler;
varying vec2 vTexSamplingCoord;

void main() {
  vec4 color = texture2D(uTexSampler, clamp(vTexSamplingCoord, 0.0, 1.0));
  gl_FragColor = vec4(color.rgb, 1.0);
}
"""
  }

  override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
    return CustomShaderGlShaderProgram(
      useHdr = useHdr,
      vertexShader = vertexShader,
      fragmentShader = fragmentShader,
      uniformBinder = uniformBinder,
      onRelease = onRelease,
      diagnosticName = name
    )
  }

  override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false
}

/**
 * Implementation of [BaseGlShaderProgram] that compiles and executes custom GLSL shaders
 * within Media3's frame processing pipeline.
 *
 * Initialization and draw failures fall back to [CustomShaderGlEffect.IDENTITY_FRAGMENT_SHADER]
 * instead of throwing [androidx.media3.common.VideoFrameProcessingException]. Throwing from
 * here kills VideoFrameProcessor, starves MediaCodec of [android.media.MediaCodec.releaseOutputBuffer]
 * calls, and leaves every later project on a black preview.
 *
 * [TEXTURE_POOL_CAPACITY] is 2 (Media3's default) so the processor can pipeline a frame
 * while the previous output is still on the display surface. Capacity 1 deadlocks the
 * decoder the first time an extra intermediate is needed.
 */
@OptIn(UnstableApi::class)
class CustomShaderGlShaderProgram(
  useHdr: Boolean,
  vertexShader: String,
  fragmentShader: String,
  private val uniformBinder: ((program: GlProgram, presentationTimeUs: Long, width: Int, height: Int) -> Unit)? = null,
  private val onRelease: (() -> Unit)? = null,
  private val diagnosticName: String = "CustomShader"
) : BaseGlShaderProgram(useHdr, TEXTURE_POOL_CAPACITY) {

  companion object {
    private const val TAG = "CustomShaderGlProgram"
    const val TEXTURE_POOL_CAPACITY = 2
  }

  private var glProgram: GlProgram? = null
  private var identityProgram: GlProgram? = null
  private var currentWidth: Int = 0
  private var currentHeight: Int = 0
  private var usingIdentityFallback = false

  init {
    glProgram = compileProgram(vertexShader, fragmentShader)
    if (glProgram == null) {
      usingIdentityFallback = true
      Log.e(TAG, "$diagnosticName failed to compile; installing identity passthrough")
      glProgram = compileProgram(CustomShaderGlEffect.DEFAULT_VERTEX_SHADER, CustomShaderGlEffect.IDENTITY_FRAGMENT_SHADER)
    }
  }

  private fun compileProgram(vertex: String, fragment: String): GlProgram? {
    return try {
      GlProgram(vertex, fragment).also { program ->
        program.setBufferAttribute(
          "aFramePosition",
          GlUtil.getNormalizedCoordinateBounds(),
          GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
        )
        val identityMatrix = GlUtil.create4x4IdentityMatrix()
        program.setFloatsUniform("uTransformationMatrix", identityMatrix)
        program.setFloatsUniform("uTexTransformationMatrix", identityMatrix)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to compile $diagnosticName shader", e)
      null
    }
  }

  private fun identityProgram(): GlProgram? {
    val existing = identityProgram
    if (existing != null) return existing
    val compiled = compileProgram(
      CustomShaderGlEffect.DEFAULT_VERTEX_SHADER,
      CustomShaderGlEffect.IDENTITY_FRAGMENT_SHADER
    )
    identityProgram = compiled
    return compiled
  }

  override fun configure(inputWidth: Int, inputHeight: Int): Size {
    currentWidth = inputWidth
    currentHeight = inputHeight
    return Size(inputWidth, inputHeight)
  }

  override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
    val program = glProgram
    if (program == null) {
      blitIdentity(inputTexId)
      return
    }
    try {
      program.use()
      program.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnit= */ 0)
      if (!usingIdentityFallback) {
        uniformBinder?.invoke(program, presentationTimeUs, currentWidth, currentHeight)
      }
      program.bindAttributesAndUniforms()
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
      GlUtil.checkGlError()
    } catch (e: Exception) {
      Log.e(TAG, "$diagnosticName drawFrame failed at $presentationTimeUs; falling back to identity", e)
      usingIdentityFallback = true
      blitIdentity(inputTexId)
    }
  }

  private fun blitIdentity(inputTexId: Int) {
    val program = identityProgram() ?: glProgram ?: return
    try {
      program.use()
      program.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnit= */ 0)
      program.bindAttributesAndUniforms()
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
      runCatching { GlUtil.checkGlError() }
    } catch (e: Exception) {
      Log.e(TAG, "$diagnosticName identity blit failed; skipping frame to keep VideoFrameProcessor alive", e)
    }
  }

  override fun release() {
    try {
      super.release()
    } catch (e: Exception) {
      Log.e(TAG, "$diagnosticName BaseGlShaderProgram.release failed", e)
    }
    try {
      glProgram?.delete()
    } catch (e: Exception) {
      Log.e(TAG, "$diagnosticName glProgram.delete failed", e)
    }
    glProgram = null
    try {
      identityProgram?.delete()
    } catch (e: Exception) {
      Log.e(TAG, "$diagnosticName identityProgram.delete failed", e)
    }
    identityProgram = null
    try {
      onRelease?.invoke()
    } catch (e: Exception) {
      Log.e(TAG, "$diagnosticName onRelease hook failed", e)
    }
  }
}
