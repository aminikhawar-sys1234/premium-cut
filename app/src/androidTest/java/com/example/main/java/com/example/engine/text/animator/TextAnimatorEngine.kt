package com.example.engine.text.animator

import com.example.domain.model.TextAnimatorSpec
import java.text.BreakIterator
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/** A renderable piece of the text: [start, end) in the text that is drawn as one run. */
data class TextUnitSpan(
  val start: Int,
  val end: Int,
  /** Index used by a selector of the same basis (non-whitespace characters, words or lines). */
  val unitIndex: Int
)

data class TextSegmentation(
  val spans: List<TextUnitSpan>,
  val unitCount: Int,
  /** The basis that was really used (Characters is coerced to Words for cursive / complex scripts). */
  val effectiveBasis: String
)

/** Accumulated effect of all animators on a single text unit. Identity = no change. */
data class GlyphFx(
  val offsetXEm: Float = 0f,
  val offsetYEm: Float = 0f,
  val scale: Float = 1f,
  val rotationDeg: Float = 0f,
  val skewDeg: Float = 0f,
  val opacity: Float = 1f,
  val trackingEm: Float = 0f,
  val tintArgb: Int = 0,
  val tintAmount: Float = 0f,
  val depthExtraPx: Float = 0f,
  val tiltXDeg: Float = 0f,
  val tiltYDeg: Float = 0f,
  val lightShiftDeg: Float = 0f
) {
  val isIdentity: Boolean
    get() = offsetXEm == 0f && offsetYEm == 0f && scale == 1f && rotationDeg == 0f &&
      skewDeg == 0f && opacity == 1f && trackingEm == 0f && tintAmount == 0f &&
      depthExtraPx == 0f && tiltXDeg == 0f && tiltYDeg == 0f && lightShiftDeg == 0f
}

data class AnimatedTextFrame(
  val spans: List<TextUnitSpan>,
  val fx: List<GlyphFx>
)

/**
 * Per-character / per-word / per-line text animation, modelled on After Effects text animators:
 * a range selector produces an "amount" (0..1) per text unit, and the animator's properties are
 * scaled by that amount. Everything here is pure math so it can be unit-tested without Android.
 */
object TextAnimatorEngine {

  val BASES = listOf("Characters", "Words", "Lines")
  val SHAPES = listOf("Square", "Ramp Up", "Ramp Down", "Triangle", "Round", "Smooth")
  val TIME_EASINGS = listOf("Linear", "Ease In", "Ease Out", "Ease In Out")
  val LOOP_MODES = listOf("Once", "Loop", "Ping-Pong")

  fun hasActiveAnimators(animators: List<TextAnimatorSpec>): Boolean = animators.any { it.enabled }

  /** True when any enabled animator touches a 3D property, so the renderer must extrude even if the clip's 3D switch is off. */
  fun uses3D(animators: List<TextAnimatorSpec>): Boolean =
    animators.any { it.enabled && it.depthPx > 0f }

  // ------------------------------------------------------------------------------------------
  // Segmentation
  // ------------------------------------------------------------------------------------------

  /**
   * Cursive and shaping-dependent scripts (Arabic/Urdu, Indic, Thai) must never be split into
   * single characters, otherwise joining forms and matras break.
   */
  fun needsShapedRuns(text: CharSequence): Boolean {
    for (ch in text) {
      val c = ch.code
      if (c in 0x0600..0x06FF || c in 0x0750..0x077F || c in 0x08A0..0x08FF ||
        c in 0xFB50..0xFDFF || c in 0xFE70..0xFEFF || c in 0x0900..0x0DFF ||
        c in 0x0E00..0x0E7F || c in 0xA8E0..0xA8FF
      ) return true
    }
    return false
  }

  fun segment(text: String, basis: String): TextSegmentation {
    val complex = needsShapedRuns(text)
    val effective = when {
      basis == "Lines" -> "Lines"
      basis == "Words" -> "Words"
      complex -> "Words"
      else -> "Characters"
    }
    val spans = ArrayList<TextUnitSpan>()
    var unit = 0
    var lineStart = 0
    while (lineStart <= text.length) {
      var lineEnd = text.indexOf('\n', lineStart)
      if (lineEnd < 0) lineEnd = text.length
      when (effective) {
        "Lines" -> {
          if (text.substring(lineStart, lineEnd).isNotBlank()) {
            spans.add(TextUnitSpan(lineStart, lineEnd, unit++))
          }
        }
        "Words" -> {
          var i = lineStart
          while (i < lineEnd) {
            while (i < lineEnd && text[i].isWhitespace()) i++
            val ws = i
            while (i < lineEnd && !text[i].isWhitespace()) i++
            if (i > ws) spans.add(TextUnitSpan(ws, i, unit++))
          }
        }
        else -> {
          if (lineEnd > lineStart) {
            val iter = BreakIterator.getCharacterInstance()
            val lineText = text.substring(lineStart, lineEnd)
            iter.setText(lineText)
            var b = iter.first()
            var n = iter.next()
            while (n != BreakIterator.DONE) {
              val cluster = lineText.substring(b, n)
              if (cluster.isNotBlank()) spans.add(TextUnitSpan(lineStart + b, lineStart + n, unit++))
              b = n
              n = iter.next()
            }
          }
        }
      }
      if (lineEnd >= text.length) break
      lineStart = lineEnd + 1
    }
    return TextSegmentation(spans, unit, effective)
  }

  // ------------------------------------------------------------------------------------------
  // Time
  // ------------------------------------------------------------------------------------------

  /** Eased progress 0..1 of an animator at [timeMs] after clip start. */
  fun timeProgress(spec: TextAnimatorSpec, timeMs: Long): Float {
    val local = timeMs - spec.delayMs
    if (local < 0L) return 0f
    val dur = max(1L, spec.durationMs).toFloat()
    val raw = when (spec.loopMode) {
      "Loop" -> (local % spec.durationMs.coerceAtLeast(1L)).toFloat() / dur
      "Ping-Pong" -> {
        val period = 2f * dur
        val m = (local.toFloat() % period)
        if (m < dur) m / dur else 2f - m / dur
      }
      else -> (local.toFloat() / dur).coerceIn(0f, 1f)
    }
    return applyTimeEasing(spec.timeEasing, raw.coerceIn(0f, 1f))
  }

  private fun applyTimeEasing(name: String, t: Float): Float = when (name) {
    "Ease In" -> t * t
    "Ease Out" -> 1f - (1f - t) * (1f - t)
    "Ease In Out" -> if (t < 0.5f) 2f * t * t else 1f - (-2f * t + 2f) * (-2f * t + 2f) / 2f
    else -> t
  }

  // ------------------------------------------------------------------------------------------
  // Selector
  // ------------------------------------------------------------------------------------------

  /** Deterministic shuffle so randomised order is identical on every frame and on export. */
  fun permutation(n: Int, seed: Int): IntArray {
    val arr = IntArray(n) { it }
    val rnd = Random(seed.toLong() * 31L + n)
    for (i in n - 1 downTo 1) {
      val j = rnd.nextInt(i + 1)
      val t = arr[i]; arr[i] = arr[j]; arr[j] = t
    }
    return arr
  }

  private fun smooth01(x: Float): Float {
    val v = x.coerceIn(0f, 1f)
    return v * v * (3f - 2f * v)
  }

  /**
   * Selection amount (0..1) of one unit.
   * @param position unit position in selector percent (0..100)
   */
  fun selectorAmount(spec: TextAnimatorSpec, position: Float, progress: Float): Float {
    var s = spec.startFrom + (spec.startTo - spec.startFrom) * progress + spec.offset
    var e = spec.endFrom + (spec.endTo - spec.endFrom) * progress + spec.offset
    if (e < s) { val t = s; s = e; e = t }

    val raw: Float = if (spec.shape == "Square") {
      // The feather zone is mapped so that start=0 / end=100 select everything and start=100 /
      // end=0 select nothing, which keeps reveals continuous: no unit is left half-selected
      // when an edge reaches the end of the text.
      val soft = spec.softness.coerceAtLeast(0f)
      val a: Float
      val b: Float
      if (soft > 0f) {
        val sEff = s * (100f + soft) / 100f - soft
        val eEff = e * (100f + soft) / 100f
        a = smooth01((position - sEff) / soft)
        b = 1f - smooth01((position - (eEff - soft)) / soft)
      } else {
        a = if (position >= s) 1f else 0f
        b = if (position <= e) 1f else 0f
      }
      a * b
    } else {
      if (position < s || position > e) {
        0f
      } else {
        val w = e - s
        if (w < 1e-4f) 1f else {
          val t = (position - s) / w
          when (spec.shape) {
            "Ramp Up" -> t
            "Ramp Down" -> 1f - t
            "Triangle" -> 1f - abs(2f * t - 1f)
            "Round" -> sqrt(max(0f, 1f - (2f * t - 1f) * (2f * t - 1f)))
            "Smooth" -> 0.5f - 0.5f * cos(2.0 * PI * t).toFloat()
            else -> 1f
          }
        }
      }
    }
    return shapeEase(raw.coerceIn(0f, 1f), spec.easeHigh, spec.easeLow)
  }

  /** Ease High acts on the upper half of the selection, Ease Low on the lower half. */
  fun shapeEase(v: Float, easeHigh: Float, easeLow: Float): Float {
    val k = ((if (v >= 0.5f) easeHigh else easeLow) / 100f).coerceIn(-1f, 1f)
    if (k == 0f) return v
    val smooth = v * v * (3f - 2f * v)
    val out = if (k > 0f) v + (smooth - v) * k else v + (v - smooth) * (-k)
    return out.coerceIn(0f, 1f)
  }

  // ------------------------------------------------------------------------------------------
  // Evaluation
  // ------------------------------------------------------------------------------------------

  /**
   * Evaluates every enabled animator for [text] at [timeMs] (relative to clip start) and returns
   * one [GlyphFx] per renderable span. Spans are the finest segmentation (characters, or words for
   * complex scripts); coarser animators (words/lines) map each span to the unit that contains it.
   */
  fun evaluate(text: String, animators: List<TextAnimatorSpec>, timeMs: Long): AnimatedTextFrame {
    val renderSeg = segment(text, "Characters")
    val spans = renderSeg.spans
    val fx = MutableList(spans.size) { GlyphFx() }
    val active = animators.filter { it.enabled }
    if (spans.isEmpty() || active.isEmpty()) return AnimatedTextFrame(spans, fx)

    for (spec in active) {
      val seg = segment(text, spec.basis)
      if (seg.unitCount == 0) continue
      val offsetToUnit = IntArray(text.length + 1) { -1 }
      for (u in seg.spans) for (i in u.start until u.end) offsetToUnit[i] = u.unitIndex
      val perm = if (spec.randomize) permutation(seg.unitCount, spec.randomSeed) else null
      val progress = timeProgress(spec, timeMs)
      for (idx in spans.indices) {
        val unit = offsetToUnit[spans[idx].start]
        if (unit < 0) continue
        val slot = perm?.get(unit) ?: unit
        val position = (slot + 0.5f) / seg.unitCount * 100f
        val amount = selectorAmount(spec, position, progress)
        if (amount > 0f) fx[idx] = apply(fx[idx], spec, amount)
      }
    }
    return AnimatedTextFrame(spans, fx)
  }

  private fun apply(cur: GlyphFx, spec: TextAnimatorSpec, a: Float): GlyphFx {
    var tint = cur.tintArgb
    var tintAmt = cur.tintAmount
    if (spec.useFill) {
      tint = if (cur.tintAmount == 0f) spec.fillColor.toInt() else lerpArgb(cur.tintArgb, spec.fillColor.toInt(), a)
      tintAmt = 1f - (1f - cur.tintAmount) * (1f - a)
    }
    return GlyphFx(
      offsetXEm = cur.offsetXEm + a * spec.posX,
      offsetYEm = cur.offsetYEm + a * spec.posY,
      scale = max(0f, cur.scale * (1f + a * (spec.scalePct / 100f - 1f))),
      rotationDeg = cur.rotationDeg + a * spec.rotationDeg,
      skewDeg = cur.skewDeg + a * spec.skewDeg,
      opacity = (cur.opacity * (1f + a * (spec.opacityPct / 100f - 1f))).coerceIn(0f, 1f),
      trackingEm = cur.trackingEm + a * spec.trackingEm,
      tintArgb = tint,
      tintAmount = tintAmt,
      depthExtraPx = cur.depthExtraPx + a * spec.depthPx,
      tiltXDeg = cur.tiltXDeg + a * spec.tiltXDeg,
      tiltYDeg = cur.tiltYDeg + a * spec.tiltYDeg,
      lightShiftDeg = cur.lightShiftDeg + a * spec.lightShiftDeg
    )
  }

  fun lerpArgb(from: Int, to: Int, t: Float): Int {
    val k = t.coerceIn(0f, 1f)
    fun ch(shift: Int): Int {
      val a = (from ushr shift) and 0xFF
      val b = (to ushr shift) and 0xFF
      return (a + (b - a) * k).toInt().coerceIn(0, 255)
    }
    return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
  }
}

/** Ready-made parameter sets for the animator tool. They are plain specs the user can edit further. */
object TextAnimatorRecipes {
  data class Recipe(val label: String, val build: () -> TextAnimatorSpec)

  val all: List<Recipe> = listOf(
    Recipe("Rise Reveal") {
      TextAnimatorSpec(
        name = "Rise Reveal", basis = "Characters", shape = "Square", softness = 30f,
        startFrom = 0f, startTo = 100f, endFrom = 100f, endTo = 100f,
        durationMs = 900L, timeEasing = "Linear",
        opacityPct = 0f, posY = 0.45f
      )
    },
    Recipe("Scatter Pop") {
      TextAnimatorSpec(
        name = "Scatter Pop", basis = "Characters", shape = "Square",
        startFrom = 0f, startTo = 100f, endFrom = 100f, endTo = 100f,
        randomize = true, softness = 40f, durationMs = 800L, timeEasing = "Linear",
        opacityPct = 0f, scalePct = 0f, rotationDeg = -25f
      )
    },
    Recipe("Wave") {
      TextAnimatorSpec(
        name = "Wave", basis = "Characters", shape = "Smooth",
        startFrom = -50f, startTo = 100f, endFrom = 0f, endTo = 150f,
        durationMs = 1400L, timeEasing = "Linear", loopMode = "Loop",
        posY = -0.4f, scalePct = 115f
      )
    },
    Recipe("Spotlight") {
      TextAnimatorSpec(
        name = "Spotlight", basis = "Words", shape = "Round",
        startFrom = -40f, startTo = 100f, endFrom = 20f, endTo = 160f,
        durationMs = 1800L, timeEasing = "Ease In Out", loopMode = "Loop",
        scalePct = 120f, useFill = true, fillColor = 0xFFFFC107
      )
    },
    Recipe("3D Flip In") {
      TextAnimatorSpec(
        name = "3D Flip In", basis = "Characters", shape = "Square", softness = 35f,
        startFrom = 0f, startTo = 100f, endFrom = 100f, endTo = 100f,
        durationMs = 1000L, timeEasing = "Ease Out",
        opacityPct = 0f, tiltYDeg = -90f, depthPx = 14f
      )
    },
    Recipe("Depth Pop") {
      TextAnimatorSpec(
        name = "Depth Pop", basis = "Characters", shape = "Smooth",
        startFrom = -50f, startTo = 100f, endFrom = 0f, endTo = 150f,
        durationMs = 1200L, timeEasing = "Linear", loopMode = "Loop",
        depthPx = 18f, scalePct = 108f, posY = -0.12f
      )
    },
    Recipe("Light Sweep") {
      TextAnimatorSpec(
        name = "Light Sweep", basis = "Characters", shape = "Smooth",
        startFrom = -60f, startTo = 100f, endFrom = 0f, endTo = 160f,
        durationMs = 1600L, timeEasing = "Linear", loopMode = "Loop",
        lightShiftDeg = 140f
      )
    },
    Recipe("Tumble") {
      TextAnimatorSpec(
        name = "Tumble", basis = "Words", shape = "Square", softness = 30f,
        startFrom = 0f, startTo = 100f, endFrom = 100f, endTo = 100f,
        durationMs = 1100L, timeEasing = "Ease Out",
        opacityPct = 0f, tiltXDeg = 80f, depthPx = 10f, posY = 0.3f
      )
    }
  )
}
