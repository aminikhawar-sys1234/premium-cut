package com.example.engine.text.animator

import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Matrix
import android.text.Layout
import com.example.domain.model.TextClip
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.tan

/**
 * Draws a [Layout] unit by unit (characters / words) so each unit can have its own transform,
 * opacity and tint from [TextAnimatorEngine]. Positions come from the layout itself, so kerning,
 * letter spacing, alignment and multi-line breaks match the normal (non-animated) rendering.
 * Cursive / Indic scripts are drawn as whole word runs through drawTextRun so shaping stays intact.
 */
internal object AnimatedTextPainter {

  class PlannedGlyph(
    val start: Int,
    val end: Int,
    val contextStart: Int,
    val contextEnd: Int,
    val left: Float,
    val width: Float,
    val baseline: Float,
    val lineCenterY: Float,
    val isRtl: Boolean,
    val trackShiftPx: Float,
    val fx: GlyphFx
  )

  class Plan(val text: CharSequence, val fontPx: Float, val glyphs: List<PlannedGlyph>)

  fun plan(text: String, layout: Layout, clip: TextClip, timeMs: Long): Plan? {
    if (!TextAnimatorEngine.hasActiveAnimators(clip.textAnimators) || text.isEmpty()) return null
    val frame = TextAnimatorEngine.evaluate(text, clip.textAnimators, timeMs)
    if (frame.spans.isEmpty()) return null
    val fontPx = layout.paint.textSize

    class Raw(
      val span: TextUnitSpan, val line: Int, val left: Float, val width: Float,
      val rtl: Boolean, val fx: GlyphFx
    )

    val raws = ArrayList<Raw>(frame.spans.size)
    for (i in frame.spans.indices) {
      val span = frame.spans[i]
      if (span.start >= text.length) continue
      val line = layout.getLineForOffset(span.start)
      val lineEnd = layout.getLineEnd(line)
      val endOffset = min(span.end, lineEnd)
      val x0 = layout.getPrimaryHorizontal(span.start)
      val x1 = layout.getPrimaryHorizontal(endOffset)
      raws.add(
        Raw(
          span, line, min(x0, x1), abs(x1 - x0),
          layout.getParagraphDirection(line) == Layout.DIR_RIGHT_TO_LEFT,
          frame.fx[i]
        )
      )
    }

    // Tracking: every unit pushes the following units on the same (left-to-right) line.
    // The line is then re-aligned according to the clip alignment so it grows around its anchor.
    val shifts = FloatArray(raws.size)
    raws.indices.groupBy { raws[it].line }.forEach { (_, members) ->
      val ordered = members.filter { !raws[it].rtl }.sortedBy { raws[it].left }
      if (ordered.isEmpty()) return@forEach
      var cumulative = 0f
      for (idx in ordered) {
        shifts[idx] = cumulative
        cumulative += raws[idx].fx.trackingEm * fontPx
      }
      val anchor = when (clip.alignment.lowercase()) {
        "left" -> 0f
        "right" -> -cumulative
        else -> -cumulative / 2f
      }
      for (idx in ordered) shifts[idx] += anchor
    }

    val glyphs = raws.mapIndexed { i, r ->
      val line = r.line
      PlannedGlyph(
        start = r.span.start,
        end = min(r.span.end, text.length),
        contextStart = layout.getLineStart(line),
        contextEnd = layout.getLineEnd(line),
        left = r.left,
        width = r.width,
        baseline = layout.getLineBaseline(line).toFloat(),
        lineCenterY = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f,
        isRtl = r.rtl,
        trackShiftPx = shifts[i],
        fx = r.fx
      )
    }
    return Plan(text, fontPx, glyphs)
  }

  /**
   * One slice of the 3D extrusion. [d] is the slice index (1 = just behind the face);
   * each glyph gets `baseSteps + fx.depthExtraPx` slices and skips slices beyond that.
   * [colorFor] returns the (already shaded) slice colour for a glyph so per-unit light shifts work.
   */
  class Slice(
    val d: Int,
    val baseSteps: Int,
    val stepX: Float,
    val stepY: Float,
    val colorFor: (fx: GlyphFx) -> Int
  )

  /** Largest slice count any glyph needs; the caller loops d from this down to 1. */
  fun maxSteps(plan: Plan, baseSteps: Int, cap: Int = 40): Int =
    (baseSteps + (plan.glyphs.maxOfOrNull { it.fx.depthExtraPx } ?: 0f).toInt().coerceAtLeast(0)).coerceIn(0, cap)

  private val tiltCamera = Camera()
  private val tiltMatrix = Matrix()

  /** Draws using the layout's own paint, so every colour / shader / shadow set by the caller applies. */
  fun draw(canvas: Canvas, layout: Layout, plan: Plan, slice: Slice? = null) {
    val paint = layout.paint
    val baseAlpha = paint.alpha
    val baseColor = paint.color
    val canTint = paint.shader == null

    for (g in plan.glyphs) {
      val fx = g.fx
      var alpha = (baseAlpha * fx.opacity).toInt().coerceIn(0, 255)
      if (alpha == 0 || fx.scale <= 0f) continue

      if (slice != null) {
        // Extrusion pass: this glyph only owns baseSteps + its extra depth slices.
        val glyphSteps = (slice.baseSteps + fx.depthExtraPx).toInt().coerceAtMost(40)
        if (slice.d > glyphSteps) continue
        paint.color = slice.colorFor(fx)
        // colorFor carries the slice alpha (e.g. glass); the unit's own fade multiplies it.
        alpha = (android.graphics.Color.alpha(paint.color) * fx.opacity).toInt().coerceIn(0, 255)
      } else if (canTint && fx.tintAmount > 0f) {
        paint.color = TextAnimatorEngine.lerpArgb(baseColor, fx.tintArgb, fx.tintAmount)
      }
      paint.alpha = alpha

      val cx = g.left + g.width / 2f + g.trackShiftPx + fx.offsetXEm * plan.fontPx
      val cy = g.lineCenterY + fx.offsetYEm * plan.fontPx

      canvas.save()
      canvas.translate(cx, cy)
      if (slice != null) canvas.translate(slice.d * slice.stepX, slice.d * slice.stepY)
      if (fx.rotationDeg != 0f) canvas.rotate(fx.rotationDeg)
      if (fx.skewDeg != 0f) canvas.skew(-tan(Math.toRadians(fx.skewDeg.toDouble())).toFloat(), 0f)
      if (fx.scale != 1f) canvas.scale(fx.scale, fx.scale)
      if (fx.tiltXDeg != 0f || fx.tiltYDeg != 0f) {
        tiltCamera.save()
        tiltCamera.rotateX(fx.tiltXDeg)
        tiltCamera.rotateY(fx.tiltYDeg)
        tiltCamera.getMatrix(tiltMatrix)
        tiltCamera.restore()
        canvas.concat(tiltMatrix)
      }
      canvas.drawTextRun(
        plan.text, g.start, g.end, g.contextStart, g.contextEnd,
        -g.width / 2f, g.baseline - g.lineCenterY, g.isRtl, paint
      )
      canvas.restore()

      paint.color = baseColor
    }
    paint.color = baseColor
    paint.alpha = baseAlpha
  }
}
