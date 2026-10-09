package com.example.engine.composition.gpu

import android.graphics.Bitmap
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log

object GlShaderUtil {
  private const val TAG = "GlShaderUtil"

  fun loadShader(shaderType: Int, source: String): Int {
    var shader = GLES20.glCreateShader(shaderType)
    checkGlError("glCreateShader type=$shaderType")
    GLES20.glShaderSource(shader, source)
    GLES20.glCompileShader(shader)
    val compiled = IntArray(1)
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
    if (compiled[0] == 0) {
      val log = GLES20.glGetShaderInfoLog(shader)
      Log.e(TAG, "Could not compile shader $shaderType: $log")
      GLES20.glDeleteShader(shader)
      shader = 0
    }
    return shader
  }

  fun createProgram(vertexSource: String, fragmentSource: String): Int {
    val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
    if (vertexShader == 0) return 0

    val pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
    if (pixelShader == 0) return 0

    var program = GLES20.glCreateProgram()
    checkGlError("glCreateProgram")
    if (program == 0) {
      Log.e(TAG, "Could not create program")
      return 0
    }
    GLES20.glAttachShader(program, vertexShader)
    checkGlError("glAttachShader vertex")
    GLES20.glAttachShader(program, pixelShader)
    checkGlError("glAttachShader pixel")
    GLES20.glLinkProgram(program)
    val linkStatus = IntArray(1)
    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
    if (linkStatus[0] != GLES20.GL_TRUE) {
      val log = GLES20.glGetProgramInfoLog(program)
      Log.e(TAG, "Could not link program: $log")
      GLES20.glDeleteProgram(program)
      program = 0
    }
    return program
  }

  fun createTexture(target: Int = GLES20.GL_TEXTURE_2D): Int {
    val textures = IntArray(1)
    GLES20.glGenTextures(1, textures, 0)
    val texId = textures[0]
    GLES20.glBindTexture(target, texId)
    GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glBindTexture(target, 0)
    return texId
  }

  fun createOesTexture(): Int {
    return createTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
  }

  /**
   * Uploads [bitmap] into [targetTexId] (allocating the texture when it is 0).
   *
   * [reuseStorage] is for callers that re-render the same size into the same texture every frame
   * (animated text, procedural effect overlays): [GLUtils.texImage2D] reallocates the whole
   * texture on every call (`glTexImage2D`), which on mobile GPUs means a fresh multi-megabyte
   * allocation per frame. With [reuseStorage] the pixels go through `glTexSubImage2D` into the
   * existing storage instead. Any failure falls back to the classic path.
   */
  fun uploadBitmapToTexture(bitmap: Bitmap, targetTexId: Int, reuseStorage: Boolean = false): Int {
    if (bitmap.isRecycled) return targetTexId
    var texId = targetTexId
    if (texId == 0) {
      texId = createTexture(GLES20.GL_TEXTURE_2D)
    }
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
    GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
    var uploaded = false
    if (reuseStorage && bitmap.config == Bitmap.Config.ARGB_8888) {
      uploaded = try {
        val scratch = pixelScratch(bitmap.byteCount)
        scratch.clear()
        bitmap.copyPixelsToBuffer(scratch)
        scratch.position(0)
        GLES20.glTexSubImage2D(
          GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap.width, bitmap.height,
          GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, scratch
        )
        true
      } catch (e: Exception) {
        Log.w(TAG, "glTexSubImage2D upload failed, falling back to full upload", e)
        false
      }
    }
    if (!uploaded) {
      try {
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
      } catch (e: Exception) {
        Log.w(TAG, "Failed to upload bitmap to OpenGL texture", e)
      }
    }
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    return texId
  }

  /**
   * Reusable direct buffer for bitmap uploads, one per GL thread (the preview renderer and the
   * export pipeline render on different threads and both upload bitmaps).
   */
  private val uploadScratch = ThreadLocal<java.nio.ByteBuffer>()

  private fun pixelScratch(bytes: Int): java.nio.ByteBuffer {
    val current = uploadScratch.get()
    if (current != null && current.capacity() >= bytes) return current
    val fresh = java.nio.ByteBuffer.allocateDirect(bytes)
    uploadScratch.set(fresh)
    return fresh
  }

  private val FULLSCREEN_QUAD_BUFFER: java.nio.FloatBuffer by lazy {
    val data = floatArrayOf(
      // X, Y, U, V
      -1.0f, -1.0f, 0.0f, 0.0f,
       1.0f, -1.0f, 1.0f, 0.0f,
      -1.0f,  1.0f, 0.0f, 1.0f,
       1.0f,  1.0f, 1.0f, 1.0f
    )
    java.nio.ByteBuffer.allocateDirect(data.size * 4)
      .order(java.nio.ByteOrder.nativeOrder())
      .asFloatBuffer()
      .apply {
        put(data)
        position(0)
      }
  }

  fun drawFullscreenQuad(program: Int = 0) {
    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    val aPositionHandle = if (program != 0) {
      val pos = GLES20.glGetAttribLocation(program, "a_Position")
      if (pos >= 0) pos else GLES20.glGetAttribLocation(program, "aPosition")
    } else 0

    val aTextureCoordHandle = if (program != 0) {
      val tex = GLES20.glGetAttribLocation(program, "a_TexCoord")
      if (tex >= 0) tex else GLES20.glGetAttribLocation(program, "aTextureCoord")
    } else 1

    FULLSCREEN_QUAD_BUFFER.position(0)
    if (aPositionHandle >= 0) {
      GLES20.glVertexAttribPointer(aPositionHandle, 2, GLES20.GL_FLOAT, false, 16, FULLSCREEN_QUAD_BUFFER)
      GLES20.glEnableVertexAttribArray(aPositionHandle)
    }

    FULLSCREEN_QUAD_BUFFER.position(2)
    if (aTextureCoordHandle >= 0) {
      GLES20.glVertexAttribPointer(aTextureCoordHandle, 2, GLES20.GL_FLOAT, false, 16, FULLSCREEN_QUAD_BUFFER)
      GLES20.glEnableVertexAttribArray(aTextureCoordHandle)
    }

    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

    if (aPositionHandle >= 0) GLES20.glDisableVertexAttribArray(aPositionHandle)
    if (aTextureCoordHandle >= 0) GLES20.glDisableVertexAttribArray(aTextureCoordHandle)
  }

  fun checkGlError(op: String) {
    val error = GLES20.glGetError()
    if (error != GLES20.GL_NO_ERROR) {
      Log.e(TAG, "$op: glError 0x${Integer.toHexString(error)}")
    }
  }
}
