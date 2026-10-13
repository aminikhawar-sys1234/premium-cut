package com.example.engine.composition.gpu

import android.opengl.GLES20
import android.opengl.GLES30

/**
 * ES 2/3 helpers for compositor stages. Export used to create a GLES2 context while
 * colour / VFX / warp stages called VAO APIs unconditionally, which can throw or
 * corrupt GL state on a strict ES2 driver.
 */
object GlEsCompat {
  fun isEs3(): Boolean = EglCore.activeGlVersion >= 3

  fun currentVao(): Int {
    if (!isEs3()) return 0
    val prev = IntArray(1)
    GLES30.glGetIntegerv(GLES30.GL_VERTEX_ARRAY_BINDING, prev, 0)
    GLES20.glGetError()
    return prev[0]
  }

  fun bindVao(vao: Int) {
    if (!isEs3()) return
    GLES30.glBindVertexArray(vao)
  }

  fun genVao(): Int {
    if (!isEs3()) return 0
    val v = IntArray(1)
    GLES30.glGenVertexArrays(1, v, 0)
    return v[0]
  }

  fun deleteVao(vao: Int) {
    if (vao == 0 || !isEs3()) return
    runCatching { GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0) }
  }
}
