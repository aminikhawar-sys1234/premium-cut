package com.example.engine.composition.gpu

import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import com.ahstudio.transition.core.Easing
import com.ahstudio.transition.core.TransitionInstance
import com.ahstudio.transition.core.TransitionResult
import com.ahstudio.transition.gl.GlUtil
import com.ahstudio.transition.integration.AcquiredFrame
import com.ahstudio.transition.integration.TransitionAppBridge
import com.ahstudio.transition.integration.TransitionFrameProvider
import com.ahstudio.transition.integration.TransitionFrames
import com.ahstudio.transition.render.SnapshotResult
import com.ahstudio.transition.render.TransitionEngine
import com.example.domain.model.TransitionType

/**
 * One decoded source for a transition side (outgoing or incoming clip).
 * [texMatrix] is the SurfaceTexture transform for OES sources, null for plain 2D textures.
 */
class TransitionSourceTexture(
  val textureId: Int,
  val isOes: Boolean,
  val texMatrix: FloatArray? = null
)

/**
 * Bridges the app's per-frame composition into the shader-based transition engine
 * (`com.ahstudio.transition`). It receives two already-normalised, viewport-sized 2D textures
 * (clip A = outgoing, clip B = incoming) and renders the transition into [outputFbo].
 *
 * Needs an OpenGL ES 3.0 context (shaders are `#version 300 es`). On an ES 2.0 context
 * [isAvailable] is false and the caller keeps its legacy path. Any failure returns false so
 * the caller can fall back — a broken transition must never blank a frame.
 */
class ShaderTransitionCompositor {
  private var engine: TransitionEngine? = null
  private var es3: Boolean? = null
  private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

  fun isAvailable(): Boolean {
    es3?.let { return it }
    val version = try { GLES20.glGetString(GLES20.GL_VERSION) ?: "" } catch (t: Throwable) { "" }
    return version.contains("OpenGL ES 3").also { es3 = it }
  }

  fun render(
    type: TransitionType,
    progress: Float,
    texA: Int,
    texB: Int,
    width: Int,
    height: Int,
    outputFbo: Int
  ): Boolean {
    if (type == TransitionType.NONE || texA <= 0 || texB <= 0 || outputFbo == 0) return false
    if (!isAvailable()) return false
    return try {
      val eng = engine ?: TransitionAppBridge.createEngine().also { engine = it }
      val definition = TransitionAppBridge.getDefinitionForType(type)
      // Progress is fed through a fixed virtual window; linear easing keeps the value unchanged.
      val p = progress.coerceIn(0f, 0.9999f)
      val timeMs = (p * WINDOW_MS).toLong()
      val instance = TransitionInstance(
        instanceId = "composition_transition",
        definitionId = definition.id,
        outgoingClipId = CLIP_A,
        incomingClipId = CLIP_B,
        startMs = 0L,
        endMs = WINDOW_MS,
        easing = Easing.linear(),
        parameters = TransitionAppBridge.parametersFor(type)
      )
      val snapshot = when (val s = eng.snapshot(definition.id, instance, timeMs, width, height, 1)) {
        is SnapshotResult.Ready -> s.snapshot
        else -> return false
      }
      val provider = object : TransitionFrameProvider {
        override fun acquireFrame(clipId: String, timelineTimeMs: Long) = AcquiredFrame(
          textureId = if (clipId == CLIP_A) texA else texB,
          target = GlUtil.TEXTURE_2D,
          width = width,
          height = height,
          transformMatrix = identity,
          sourceTimestampUs = 0L
        )
        override fun releaseFrame(frame: AcquiredFrame) {}
      }
      val result = TransitionFrames.acquire(provider, CLIP_A, CLIP_B, timeMs).use { frames ->
        eng.render(snapshot, frames, outputFbo)
      }
      if (result is TransitionResult.Err) {
        Log.w(TAG, "Shader transition render error: ${result.error}")
        return false
      }
      true
    } catch (t: Throwable) {
      Log.w(TAG, "Shader transition failed, using legacy path", t)
      false
    }
  }

  fun onContextLost() {
    engine?.onContextLost()
    engine = null
    es3 = null
  }

  fun release() = onContextLost()

  private companion object {
    const val TAG = "ShaderTransition"
    const val CLIP_A = "a"
    const val CLIP_B = "b"
    const val WINDOW_MS = 10_000L
  }
}
