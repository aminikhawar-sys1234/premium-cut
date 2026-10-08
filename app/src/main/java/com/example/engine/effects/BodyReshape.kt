package com.example.engine.effects

import com.ahstudio.face.core.Vec2

/**
 * Body-reshape strengths stored on the clip. Every value is 0..1.
 * The compositor turns these into mesh pulls at the detected pose, not at fixed screen points.
 */
data class BodyReshapeParams(
  val reshape: Float = 0f,
  val waist: Float = 0f,
  val legs: Float = 0f,
  val shoulders: Float = 0f,
  val proportions: Float = 0f,
) {
  fun isActive(): Boolean =
    reshape > 0.001f || waist > 0.001f || legs > 0.001f || shoulders > 0.001f || proportions > 0.001f
}

object BodyReshapeCodec {
  fun encode(p: BodyReshapeParams): String? =
    if (!p.isActive()) null
    else listOf(p.reshape, p.waist, p.legs, p.shoulders, p.proportions).joinToString(",")

  fun decode(s: String?): BodyReshapeParams {
    if (s.isNullOrBlank()) return BodyReshapeParams()
    val v = s.split(',').map { it.trim().toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f }
    fun at(i: Int) = v.getOrElse(i) { 0f }
    return BodyReshapeParams(at(0), at(1), at(2), at(3), at(4))
  }
}

/** One upright-normalized pose (0..1, origin top-left). Missing joints are null. */
data class BodyPose(
  val leftShoulder: Vec2?,
  val rightShoulder: Vec2?,
  val leftHip: Vec2?,
  val rightHip: Vec2?,
  val leftKnee: Vec2?,
  val rightKnee: Vec2?,
  val leftAnkle: Vec2?,
  val rightAnkle: Vec2?,
)

interface BodyPoseSource {
  fun poseAt(clipId: String, sourceTimeUs: Long, blocking: Boolean): BodyPose?
}

object BodyWarpRegistry {
  @Volatile private var params: Map<String, BodyReshapeParams> = emptyMap()
  @Volatile var poseSource: BodyPoseSource? = null

  fun update(next: Map<String, BodyReshapeParams>) {
    params = next.filterValues { it.isActive() }
  }

  fun paramsFor(clipId: String?): BodyReshapeParams? = clipId?.let { params[it] }

  fun clear() {
    params = emptyMap()
    poseSource = null
  }
}
