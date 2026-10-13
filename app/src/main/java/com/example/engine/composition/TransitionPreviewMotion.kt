package com.example.engine.composition

import com.example.domain.model.TransitionType
import kotlin.math.floor
import kotlin.math.sin

/**
 * Single-source motion for a transition, shared by the live preview and the GPU/canvas
 * fallback (the path used when only one clip texture is on screen).
 *
 * [translateX] / [translateY] are fractions of the frame. Positive X is right.
 * Positive Y is down. [clipStart] / [clipEnd] are horizontal keep-fractions in 0..1.
 *
 * The export shader blends both clips. This pose is the matching motion for whichever
 * side is currently visible, so the cut does not play as an unmodified hard switch.
 */
data class TransitionPreviewPose(
  val opacity: Float = 1f,
  val translateX: Float = 0f,
  val translateY: Float = 0f,
  val scale: Float = 1f,
  val rotationDeg: Float = 0f,
  val extraBlur: Float = 0f,
  val brightness: Float = 0f,
  val temperature: Float = 0f,
  val flash: Float = 0f,
  val clipStart: Float? = null,
  val clipEnd: Float? = null
)

object TransitionPreviewMotion {
  fun resolve(type: TransitionType, progress: Float, incoming: Boolean): TransitionPreviewPose {
    val p = progress.coerceIn(0f, 1f)
    val eased = smooth(p, 0f, 1f)
    return when (type) {
      TransitionType.NONE -> TransitionPreviewPose()
      TransitionType.DISSOLVE -> {
        val m = smooth(p, 0.45f, 0.55f)
        TransitionPreviewPose(opacity = if (incoming) m else 1f - m)
      }
      TransitionType.FADE -> {
        val a = if (incoming) (p - 0.5f) * 2f else 1f - p * 2f
        TransitionPreviewPose(opacity = a.coerceIn(0f, 1f))
      }
      TransitionType.SLIDE_LEFT -> TransitionPreviewPose(
        translateX = if (incoming) 1f - p else -p
      )
      TransitionType.SLIDE_RIGHT -> TransitionPreviewPose(
        translateX = if (incoming) p - 1f else p
      )
      TransitionType.WHIP_PAN -> TransitionPreviewPose(
        translateX = if (incoming) eased - 1f else eased,
        extraBlur = sin(p * PI) * 0.08f
      )
      TransitionType.WIPE -> wipe(p, incoming, fromLeft = true)
      TransitionType.WIPE_RIGHT -> wipe(p, incoming, fromLeft = false)
      TransitionType.GLITCH_WIPE -> TransitionPreviewPose(
        opacity = if (incoming) p else 1f - p,
        translateX = bandJitter(p) * 0.04f
      )
      TransitionType.PUSH_UP -> TransitionPreviewPose(
        translateY = if (incoming) -(1f - p) else p
      )
      TransitionType.ZOOM_IN -> {
        val m = smooth(p, 0.425f, 0.575f)
        TransitionPreviewPose(
          opacity = if (incoming) m else 1f - m,
          scale = if (incoming) lerp(1.21f, 1f, p) else lerp(1f, 1.6f, p)
        )
      }
      TransitionType.ZOOM_OUT -> TransitionPreviewPose(
        opacity = if (incoming) eased else 1f - eased,
        scale = if (incoming) lerp(1.6f, 1f, eased) else lerp(1f, 1.2f, eased)
      )
      TransitionType.SPIN -> {
        val ang = eased * 180f
        val mix = smooth(p, 0.3f, 0.7f)
        TransitionPreviewPose(
          opacity = if (incoming) mix else 1f - mix,
          scale = 1f + 0.6f * sin(p * PI),
          rotationDeg = if (incoming) ang - 180f else ang
        )
      }
      TransitionType.BLUR -> TransitionPreviewPose(
        opacity = if (incoming) p else 1f - p,
        extraBlur = sin(p * PI) * 0.06f
      )
      TransitionType.ZOOM_BLUR -> TransitionPreviewPose(
        opacity = if (incoming) p else 1f - p,
        scale = 1f + sin(p * PI) * 0.15f,
        extraBlur = sin(p * PI) * 0.06f
      )
      TransitionType.FLASH -> TransitionPreviewPose(
        flash = if (p < 0.5f) smooth(p, 0f, 0.5f) else 1f - smooth(p, 0.5f, 1f)
      )
      TransitionType.GLITCH -> TransitionPreviewPose(
        opacity = if (incoming) p else 1f - p,
        translateX = bandJitter(p) * 0.08f,
        translateY = bandJitter(p + 0.37f) * 0.04f
      )
      TransitionType.LIGHT_LEAK -> {
        val glow = sin(p * PI)
        TransitionPreviewPose(
          opacity = if (incoming) p else 1f - p,
          brightness = glow * 0.45f,
          temperature = glow * 0.5f
        )
      }
      TransitionType.RADIAL_WIPE -> TransitionPreviewPose(
        opacity = if (incoming) p else 1f - p
      )
    }
  }

  private fun wipe(p: Float, incoming: Boolean, fromLeft: Boolean): TransitionPreviewPose {
    val edge = p.coerceIn(0.001f, 0.999f)
    return if (fromLeft) {
      if (incoming) TransitionPreviewPose(clipStart = 0f, clipEnd = edge)
      else TransitionPreviewPose(clipStart = edge, clipEnd = 1f)
    } else {
      if (incoming) TransitionPreviewPose(clipStart = 1f - edge, clipEnd = 1f)
      else TransitionPreviewPose(clipStart = 0f, clipEnd = 1f - edge)
    }
  }

  private fun smooth(p: Float, edge0: Float, edge1: Float): Float {
    val t = ((p - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
  }

  private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

  private fun bandJitter(p: Float): Float {
    val x = sin(p * 12.9898f) * 43758.5453f
    return x - floor(x) - 0.5f
  }

  private const val PI = 3.14159265f
}
