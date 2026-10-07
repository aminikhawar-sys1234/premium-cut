package com.example.domain.model

/** Transform channels that an After-Effects style expression can drive. */
enum class MotionChannel(val key: String, val label: String, val hint: String) {
  POSITION("position", "Position", "[x, y] normalized, -1..1 (0,0 = center)"),
  SCALE("scale", "Scale", "[sx, sy] or one number, 1.0 = 100%"),
  ROTATION("rotation", "Rotation", "degrees"),
  OPACITY("opacity", "Opacity", "0..1");

  companion object {
    fun fromKey(key: String): MotionChannel? = values().firstOrNull { it.key == key }
  }
}

/**
 * 2D rig binding: this clip becomes a child bone of [parentClipId] (Rig.kt forward-kinematics).
 * The child's own position keyframes are interpreted as an offset from the parent bone's TAIL (joint), and the
 * child's rotation is added to the parent's world rotation, exactly like Skeleton.pose().
 */
data class ClipRigBinding(
  val parentClipId: String,
  val inheritScale: Boolean = true,
  val inheritOpacity: Boolean = false,
  /** Offset from the parent's joint (parent frame, normalized) captured when linking so the clip does not jump. */
  val offsetX: Float = 0f,
  val offsetY: Float = 0f,
  /** Rest rotation relative to the parent bone, degrees. */
  val restAngleDeg: Float = 0f
)

/**
 * Motion script of one clip: per-channel expressions (ExpressionLanguage.kt) and an optional parent bone link.
 * [boneLength] is the distance (normalized units) from this clip's pivot to the joint where its children attach.
 */
data class ClipMotionScript(
  val expressions: Map<String, String> = emptyMap(),
  val rig: ClipRigBinding? = null,
  val boneLength: Float = 0.2f
) {
  val isEmpty: Boolean get() = expressions.isEmpty() && rig == null
  fun expressionFor(channel: MotionChannel): String? = expressions[channel.key]?.takeIf { it.isNotBlank() }
  fun withExpression(channel: MotionChannel, source: String?): ClipMotionScript =
    copy(expressions = if (source.isNullOrBlank()) expressions - channel.key else expressions + (channel.key to source))
}
