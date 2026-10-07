package com.example.engine.composition

import com.example.domain.model.SpeedCurve
import com.example.domain.model.SpeedCurvePreset

/**
 * Evaluates non-linear Bézier speed curves and recalculates Presentation Timestamps (PTS)
 * for both live preview rendering and MP4 export.
 * Preserves strict microsecond sync across video frames and audio samples.
 */
object SpeedCurveEvaluator {

  /**
   * Given a target playhead position relative to clip start (ms),
   * calculates the corresponding source media offset position (ms) according to the speed curve.
   */
  fun calculateSourcePositionMs(
    relativePlayheadMs: Long,
    clipDurationMs: Long,
    speedCurve: SpeedCurve,
    linearSpeedMultiplier: Float = 1.0f
  ): Long {
    if (clipDurationMs <= 0L || relativePlayheadMs <= 0L) return 0L
    if (speedCurve.preset == SpeedCurvePreset.STANDARD) {
      return (relativePlayheadMs * linearSpeedMultiplier).toLong()
    }

    // Single source of truth: the continuous, monotone SpeedRamp (same maths as VideoClip.timelineToSourceMs).
    val ramp = com.ahstudio.animation.speed.SpeedPresets.rampFor(speedCurve.preset.name, speedCurve.bezierPoints)
      ?: return (relativePlayheadMs * linearSpeedMultiplier).toLong()
    return ramp.sourceMs(relativePlayheadMs, clipDurationMs, clipDurationMs * linearSpeedMultiplier.toDouble()).toLong()
  }
}
