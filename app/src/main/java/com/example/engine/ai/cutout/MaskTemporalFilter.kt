package com.example.engine.ai.cutout

/**
 * Calms the frame-to-frame flicker of a per-frame subject mask. The segmenter judges every frame on its own,
 * so soft edges and thin parts (hair, fingers) flip between frames even when nothing moves.
 *
 * Small changes are averaged with the previous mask; a large change (a real motion or a new subject) follows
 * the new mask almost immediately so the cutout never lags behind the person.
 */
object MaskTemporalFilter {

  /** Weight of the new mask where the change is small. Lower = steadier, more lag. */
  const val STEADY_WEIGHT = 0.5f

  /** Weight of the new mask where the change is large. */
  const val MOTION_WEIGHT = 0.9f

  /** Per-pixel change (0..255) from which a pixel counts as real motion. */
  const val MOTION_THRESHOLD = 110

  /** True when [previous] can be blended into a mask of this size taken [gapBuckets] frames later. */
  fun canBlend(previous: ByteArray?, size: Int, gapBuckets: Long, maxGapBuckets: Long = 4L): Boolean =
    previous != null && previous.size == size && gapBuckets in 1..maxGapBuckets

  /** Returns the blended mask; [current] when there is nothing usable to blend with. Inputs are not modified. */
  fun blend(previous: ByteArray?, current: ByteArray): ByteArray {
    if (previous == null || previous.size != current.size) return current
    val out = ByteArray(current.size)
    for (i in current.indices) {
      val p = previous[i].toInt() and 0xFF
      val c = current[i].toInt() and 0xFF
      val diff = if (c > p) c - p else p - c
      val w = if (diff >= MOTION_THRESHOLD) MOTION_WEIGHT else STEADY_WEIGHT
      out[i] = (p + (c - p) * w + 0.5f).toInt().coerceIn(0, 255).toByte()
    }
    return out
  }
}
