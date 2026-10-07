package com.example.engine.motion

import com.ahstudio.animation.expression.Expression
import com.ahstudio.animation.expression.ExpressionException
import com.ahstudio.animation.expression.ExpressionHost
import com.ahstudio.animation.expression.XVal
import com.ahstudio.animation.math.Vec2
import com.ahstudio.animation.rig.Bone
import com.ahstudio.animation.rig.Skeleton
import com.example.domain.model.ClipKeyframe
import com.example.domain.model.ClipMotionScript
import com.example.domain.model.ClipRigBinding
import com.example.domain.model.MotionChannel
import com.example.domain.model.StickerClip
import com.example.domain.model.TextClip
import com.example.domain.model.Timeline
import com.example.domain.model.VideoClip
import com.example.engine.InterpolatedClipTransform
import com.example.engine.KeyframeInterpolator

/**
 * Lets the pure animation core (ExpressionLanguage + Rig.kt) see the live project without every renderer having to
 * pass the timeline around. TimelineEngine installs the provider; it is only read, never written, by the evaluators.
 */
object ClipMotionRuntime {
  @Volatile var timelineProvider: (() -> Timeline)? = null
}

/** Result of validating / previewing an expression for the UI. */
sealed class ExpressionStatus {
  data class Ok(val preview: String) : ExpressionStatus()
  data class SyntaxError(val message: String, val position: Int) : ExpressionStatus()
  data class RuntimeError(val message: String) : ExpressionStatus()
  object Empty : ExpressionStatus()
}

/** A clip reduced to what the motion evaluators need (works for Video, Overlay, Sticker and Text clips). */
class MotionClipRef(
  val id: String,
  val startMs: Long,
  val durationMs: Long,
  val script: ClipMotionScript,
  val keyframes: List<ClipKeyframe>,
  /** Keyframes + built-in animation only (no expression / rig). */
  val rawAt: (Long) -> InterpolatedClipTransform
)

/**
 * Applies per-clip expressions (After-Effects style language) and 2D bone rigs (forward kinematics) on top of the
 * keyframe interpolator. Everything is a pure function of time => safe for scrubbing, preview and export.
 */
object ClipMotionEngine {

  // ------------------------------------------------------------------ clip references
  fun refOf(clip: VideoClip) = MotionClipRef(clip.id, clip.timelineStartMs, clip.durationMs, clip.motion, clip.keyframes) {
    KeyframeInterpolator.interpolateRaw(clip, it)
  }
  fun refOf(clip: StickerClip) = MotionClipRef(clip.id, clip.timelineStartMs, clip.durationMs, clip.motion, clip.keyframes) {
    KeyframeInterpolator.interpolateRaw(clip, it)
  }
  fun refOf(clip: TextClip) = MotionClipRef(clip.id, clip.timelineStartMs, clip.durationMs, clip.motion, clip.keyframes) {
    KeyframeInterpolator.interpolateRaw(clip, it)
  }

  fun findRef(tl: Timeline, id: String): MotionClipRef? {
    tl.videoClips.firstOrNull { it.id == id }?.let { return refOf(it) }
    tl.overlayClips.firstOrNull { it.id == id }?.let { return refOf(it) }
    tl.stickerClips.firstOrNull { it.id == id }?.let { return refOf(it) }
    tl.textClips.firstOrNull { it.id == id }?.let { return refOf(it) }
    return null
  }

  /** All clips that can take part in expressions / rigs: id -> display label. */
  fun rigTargets(tl: Timeline): List<Pair<String, String>> = buildList {
    tl.videoClips.forEach { add(it.id to (if (it.isVideo) "Video: " else "Photo: ") + it.name) }
    tl.overlayClips.forEach { add(it.id to "Overlay: ${it.name}") }
    tl.stickerClips.forEach { add(it.id to "Sticker: ${it.emojiOrAsset.ifBlank { it.elementCategory ?: "Shape" }.take(14)}") }
    tl.textClips.forEach { add(it.id to "Text: \"${it.text.take(14)}\"") }
  }

  // ------------------------------------------------------------------ expression cache
  private val cache = object : LinkedHashMap<String, Result<Expression>>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Result<Expression>>?) = size > 256
  }
  fun compile(source: String): Result<Expression> = synchronized(cache) {
    cache.getOrPut(source) { Expression.tryCompile(source) }
  }

  // ------------------------------------------------------------------ main entry used by KeyframeInterpolator
  fun apply(ref: MotionClipRef, relMs: Long, raw: InterpolatedClipTransform): InterpolatedClipTransform {
    val script = ref.script
    if (script.isEmpty) return raw
    return try {
      val withExpr = withExpressions(ref, relMs, raw, emptySet())
      if (script.rig == null) withExpr else applyRig(ref, relMs, withExpr)
    } catch (e: RuntimeException) {
      raw   // never let a bad script break rendering
    }
  }

  // ------------------------------------------------------------------ expressions
  private fun valueOf(channel: MotionChannel, t: InterpolatedClipTransform): XVal = when (channel) {
    MotionChannel.POSITION -> XVal.Vec(doubleArrayOf(t.posX.toDouble(), t.posY.toDouble()))
    MotionChannel.SCALE -> XVal.Vec(doubleArrayOf(t.scaleX.toDouble(), t.scaleY.toDouble()))
    MotionChannel.ROTATION -> XVal.Num(t.rotation.toDouble())
    MotionChannel.OPACITY -> XVal.Num(t.opacity.toDouble())
  }

  private fun keyValueOf(channel: MotionChannel, k: ClipKeyframe): XVal = when (channel) {
    MotionChannel.POSITION -> XVal.Vec(doubleArrayOf(k.posX.toDouble(), k.posY.toDouble()))
    MotionChannel.SCALE -> XVal.Vec(doubleArrayOf(k.scaleX.toDouble(), k.scaleY.toDouble()))
    MotionChannel.ROTATION -> XVal.Num(k.rotation.toDouble())
    MotionChannel.OPACITY -> XVal.Num(k.opacity.toDouble())
  }

  private fun fin(v: Double): Double = if (v.isNaN() || v.isInfinite()) throw ExpressionException("expression produced a non-finite value") else v

  fun withExpressions(ref: MotionClipRef, relMs: Long, base: InterpolatedClipTransform, visiting: Set<String>): InterpolatedClipTransform {
    if (ref.script.expressions.isEmpty() || ref.id in visiting) return base
    var out = base
    for (channel in MotionChannel.values()) {
      val src = ref.script.expressionFor(channel) ?: continue
      val expr = compile(src).getOrNull() ?: continue
      val result = try { runChannel(ref, channel, expr, relMs, base, visiting) } catch (e: RuntimeException) { continue }
      out = try {
        when (channel) {
          MotionChannel.POSITION -> result.asVec2().let { out.copy(posX = fin(it.x).toFloat(), posY = fin(it.y).toFloat()) }
          MotionChannel.SCALE -> result.asVec2().let { out.copy(scaleX = fin(it.x).toFloat(), scaleY = fin(it.y).toFloat()) }
          MotionChannel.ROTATION -> out.copy(rotation = fin(result.asDouble()).toFloat())
          MotionChannel.OPACITY -> out.copy(opacity = fin(result.asDouble()).toFloat().coerceIn(0f, 1f))
        }
      } catch (e: RuntimeException) { out }
    }
    return out
  }

  private fun runChannel(
    ref: MotionClipRef, channel: MotionChannel, expr: Expression, relMs: Long,
    base: InterpolatedClipTransform, visiting: Set<String>
  ): XVal = expr.evaluate(ClipExpressionHost(ref, channel, relMs, valueOf(channel, base), visiting))

  /** Property name used by `thisProp()/prop()` and the AE shortcuts (position, scale, rotation, opacity). */
  private fun propertyValue(name: String, t: InterpolatedClipTransform): XVal? {
    val n = name.removePrefix("Transform.").lowercase()
    return when (n) {
      "position" -> valueOf(MotionChannel.POSITION, t)
      "position.x" -> XVal.Num(t.posX.toDouble())
      "position.y" -> XVal.Num(t.posY.toDouble())
      "scale" -> valueOf(MotionChannel.SCALE, t)
      "scale.x" -> XVal.Num(t.scaleX.toDouble())
      "scale.y" -> XVal.Num(t.scaleY.toDouble())
      "rotation" -> XVal.Num(t.rotation.toDouble())
      "opacity" -> XVal.Num(t.opacity.toDouble())
      else -> null
    }
  }

  private class ClipExpressionHost(
    val ref: MotionClipRef,
    val channel: MotionChannel,
    override val timeMs: Long,
    override val value: XVal,
    val visiting: Set<String>
  ) : ExpressionHost {
    override val seed: Long = (ref.id + "|" + channel.key).hashCode().toLong()
    override val fps: Double = 30.0
    override val durationMs: Long = ref.durationMs

    override fun valueAt(timeMs: Long): XVal = valueOf(channel, ref.rawAt(timeMs))
    override fun keyTimes(): List<Long> = ref.keyframes.map { it.timeMs }.sorted()
    override fun keyValue(index: Int): XVal? = ref.keyframes.sortedBy { it.timeMs }.getOrNull(index)?.let { keyValueOf(channel, it) }

    override fun propertyAt(targetId: String?, property: String, timeMs: Long): XVal? {
      val tl = ClipMotionRuntime.timelineProvider?.invoke()
      val other = if (targetId == null || targetId == ref.id) ref else tl?.let { findRef(it, targetId) } ?: return null
      // times in expressions are clip-relative: convert through the absolute timeline position
      val relOther = if (other === ref) timeMs else (ref.startMs + timeMs - other.startMs)
      val t = relOther.coerceIn(0L, other.durationMs.coerceAtLeast(1L))
      val raw = other.rawAt(t)
      val withEx = if (other === ref) raw else withExpressions(other, t, raw, visiting + ref.id)
      return propertyValue(property, withEx)
    }

    override fun markerTime(name: String): Long? {
      val tl = ClipMotionRuntime.timelineProvider?.invoke() ?: return null
      return tl.markers.firstOrNull { it.label == name }?.let { it.timeMs - ref.startMs }
    }
  }

  // ------------------------------------------------------------------ UI helpers
  /** Compile-checks [source] and (when possible) previews its value for [channel] at clip-relative [relMs]. */
  fun check(clipId: String, channel: MotionChannel, source: String, relMs: Long): ExpressionStatus {
    if (source.isBlank()) return ExpressionStatus.Empty
    val expr = compile(source).getOrElse {
      return ExpressionStatus.SyntaxError(it.message ?: "syntax error", (it as? ExpressionException)?.position ?: -1)
    }
    val tl = ClipMotionRuntime.timelineProvider?.invoke() ?: return ExpressionStatus.Ok("(no preview)")
    val ref = findRef(tl, clipId) ?: return ExpressionStatus.Ok("(no preview)")
    return try {
      val t = relMs.coerceIn(0L, ref.durationMs.coerceAtLeast(1L))
      val base = ref.rawAt(t)
      val v = runChannel(ref, channel, expr, t, base, emptySet())
      ExpressionStatus.Ok(format(v))
    } catch (e: RuntimeException) {
      ExpressionStatus.RuntimeError(e.message ?: e.javaClass.simpleName)
    }
  }

  private fun format(v: XVal): String = when (v) {
    is XVal.Num -> "%.3f".format(java.util.Locale.US, v.v)
    is XVal.Vec -> v.v.joinToString(prefix = "[", postfix = "]") { "%.3f".format(java.util.Locale.US, it) }
    is XVal.Str -> v.s
  }

  // ------------------------------------------------------------------ rig (Rig.kt forward kinematics)
  private fun aspectOf(tl: Timeline?): Double = (tl?.aspectRatio?.ratio ?: 1f).toDouble().coerceIn(0.1, 10.0)

  /** Chain root -> [ref]; stops at a missing parent or a cycle. */
  private fun chainOf(tl: Timeline, ref: MotionClipRef): List<MotionClipRef> {
    val out = ArrayList<MotionClipRef>()
    val seen = HashSet<String>()
    var cur: MotionClipRef? = ref
    while (cur != null && seen.add(cur.id) && out.size < 32) {
      out.add(cur)
      cur = cur.script.rig?.parentClipId?.let { findRef(tl, it) }
    }
    return out.reversed()
  }

  private class Solved(val skeleton: Skeleton, val pose: Map<String, com.ahstudio.animation.rig.BonePose>,
                       val chain: List<MotionClipRef>, val exprAt: Map<String, InterpolatedClipTransform>)

  private fun solve(tl: Timeline, chain: List<MotionClipRef>, absMs: Long, selfOverride: Pair<String, InterpolatedClipTransform>?): Solved {
    val asp = aspectOf(tl)
    val exprAt = HashMap<String, InterpolatedClipTransform>()
    val bones = ArrayList<Bone>()
    val angles = HashMap<String, Double>()
    for ((i, c) in chain.withIndex()) {
      val e = if (selfOverride != null && selfOverride.first == c.id) selfOverride.second else {
        val t = (absMs - c.startMs).coerceIn(0L, c.durationMs.coerceAtLeast(1L))
        withExpressions(c, t, c.rawAt(t), emptySet())
      }
      exprAt[c.id] = e
      val bind: ClipRigBinding? = if (i == 0) null else c.script.rig
      bones.add(
        Bone(
          id = c.id,
          parentId = if (bind != null) chain[i - 1].id else null,
          length = c.script.boneLength.toDouble().coerceAtLeast(0.0),
          restAngleDeg = bind?.restAngleDeg?.toDouble() ?: 0.0,
          minAngleDeg = -1e9, maxAngleDeg = 1e9,
          restOffset = Vec2((e.posX + (bind?.offsetX ?: 0f)) * asp, (e.posY + (bind?.offsetY ?: 0f)).toDouble())
        )
      )
      angles[c.id] = e.rotation.toDouble()
    }
    val sk = Skeleton(bones)
    return Solved(sk, sk.pose(angles), chain, exprAt)
  }

  private fun applyRig(ref: MotionClipRef, relMs: Long, own: InterpolatedClipTransform): InterpolatedClipTransform {
    val tl = ClipMotionRuntime.timelineProvider?.invoke() ?: return own
    val chain = chainOf(tl, ref)
    if (chain.size < 2) return own
    val abs = ref.startMs + relMs
    val s = solve(tl, chain, abs, ref.id to own)
    val bp = s.pose[ref.id] ?: return own
    val asp = aspectOf(tl)

    var fx = 1f; var fy = 1f; var fo = 1f
    var i = chain.lastIndex
    while (i > 0) {
      val bind = chain[i].script.rig ?: break
      val parent = s.exprAt[chain[i - 1].id] ?: break
      if (bind.inheritScale) { fx *= parent.scaleX; fy *= parent.scaleY }
      if (bind.inheritOpacity) fo *= parent.opacity
      if (!bind.inheritScale && !bind.inheritOpacity) break
      i--
    }
    return own.copy(
      posX = (bp.head.x / asp).toFloat(),
      posY = bp.head.y.toFloat(),
      rotation = bp.worldAngleDeg.toFloat(),
      scaleX = own.scaleX * fx,
      scaleY = own.scaleY * fy,
      opacity = (own.opacity * fo).coerceIn(0f, 1f)
    )
  }

  /** True if linking [clipId] under [parentId] would create a loop (parentId is clipId or one of its descendants). */
  fun wouldCycle(tl: Timeline, clipId: String, parentId: String): Boolean {
    var cur: String? = parentId
    val seen = HashSet<String>()
    while (cur != null && seen.add(cur)) {
      if (cur == clipId) return true
      cur = findRef(tl, cur)?.script?.rig?.parentClipId
    }
    return false
  }

  /** Human readable chain "Root > Parent > This" for the UI. */
  fun chainLabels(tl: Timeline, clipId: String): List<String> {
    val ref = findRef(tl, clipId) ?: return emptyList()
    val labels = rigTargets(tl).toMap()
    return chainOf(tl, ref).map { labels[it.id] ?: it.id.take(6) }
  }

  fun childrenOf(tl: Timeline, parentId: String): List<String> =
    rigTargets(tl).map { it.first }.filter { id -> findRef(tl, id)?.script?.rig?.parentClipId == parentId }

  /**
   * Builds a binding that keeps [clipId] exactly where it is on screen at [absMs] (offset from the parent's joint and
   * rest angle are captured), so linking does not make the clip jump.
   */
  fun bindKeepingPose(tl: Timeline, clipId: String, parentId: String, absMs: Long,
                      inheritScale: Boolean = true, inheritOpacity: Boolean = false): ClipRigBinding {
    val plain = ClipRigBinding(parentId, inheritScale, inheritOpacity)
    val child = findRef(tl, clipId) ?: return plain
    val parent = findRef(tl, parentId) ?: return plain
    if (wouldCycle(tl, clipId, parentId)) return plain
    val asp = aspectOf(tl)
    val tc = (absMs - child.startMs).coerceIn(0L, child.durationMs.coerceAtLeast(1L))
    val own = withExpressions(child, tc, child.rawAt(tc), emptySet())
    val parentChain = chainOf(tl, parent)
    val s = solve(tl, parentChain, absMs, null)
    val pp = s.pose[parentId] ?: return plain
    // child's ordinary on-screen pose before linking
    val target = Vec2(own.posX * asp, own.posY.toDouble())
    val local = Skeleton.rotate(target - pp.tail, -pp.worldAngleDeg)
    return plain.copy(
      offsetX = ((local.x - own.posX * asp) / asp).toFloat(),
      offsetY = (local.y - own.posY).toFloat(),
      restAngleDeg = (-pp.worldAngleDeg).toFloat()
    )
  }
}
