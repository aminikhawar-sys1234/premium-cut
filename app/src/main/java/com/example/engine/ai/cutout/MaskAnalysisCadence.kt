package com.example.engine.ai.cutout

/**
 * How often a background-removal mask is analysed, expressed as a *time window* of the timeline
 * instead of a wall-clock interval.
 *
 * The stage used a single 30 ms bucket for both paths, i.e. one bucket per frame at 30 fps. That is
 * right for the preview, which analyses off the GL thread at most ~15 times per second and holds the
 * last mask for [SubjectCutoutStage.HOLD_BUCKETS] buckets. It is *not* right for the export, which
 * asks with `blocking = true` and therefore performed, on the GL thread, for **every frame** of a
 * background-removal clip: a 384 px `glReadPixels` of the composed frame, an ARGB conversion, an
 * on-device segmentation and a mask upload. At ~25 ms of analysis per frame that is roughly 2.5x the
 * frame budget of a 1080p30 export, spent re-segmenting a subject that moved a few pixels.
 *
 * Exporting therefore keys its masks by [exportBucketFor] - one analysis per [EXPORT_WINDOW_US] of
 * *timeline*, which is deterministic (the same project always produces the same mask for the same
 * timeline position, so a re-render is frame-exact) and keeps the analysis cost proportional to the
 * timeline instead of to the frame count. The window is deliberately shorter than the preview's
 * 600 ms hold, so the export stays the more accurate of the two.
 *
 * Pure arithmetic on purpose: the cadence decides how much time a long export spends segmenting, so
 * it is pinned by `MaskAnalysisCadenceTest` on a plain JVM.
 */
object MaskAnalysisCadence {

  /** Preview cadence: one bucket per frame of a 30 fps timeline. */
  const val FRAME_BUCKET_US = 30_000L

  /** Export cadence: one segmentation per 200 ms of timeline (6 frames at 30 fps). */
  const val EXPORT_WINDOW_US = 200_000L

  /**
   * Timeline-time key [frameKeyUs] belongs to, for a window of [windowUs]. Uses floor division, so
   * the mapping is total, monotonic and never collapses a negative position onto bucket 0.
   */
  fun bucketFor(frameKeyUs: Long, windowUs: Long): Long =
    Math.floorDiv(frameKeyUs, if (windowUs > 0L) windowUs else FRAME_BUCKET_US)

  /** Bucket of [frameKeyUs] at the preview's per-frame cadence. */
  fun previewBucketFor(frameKeyUs: Long): Long = bucketFor(frameKeyUs, FRAME_BUCKET_US)

  /** Bucket of [frameKeyUs] at the export's decimated cadence. */
  fun exportBucketFor(frameKeyUs: Long): Long = bucketFor(frameKeyUs, EXPORT_WINDOW_US)

  /** How many segmentations a timeline of [durationUs] needs at the export cadence for [frameRate]. */
  fun exportAnalysesFor(durationUs: Long, frameRate: Int): Long {
    if (durationUs <= 0L) return 0L
    return (durationUs + EXPORT_WINDOW_US - 1L) / EXPORT_WINDOW_US
  }

  /** How many the previous per-frame cadence needed - the number the export used to pay. */
  fun frameAnalysesFor(durationUs: Long, frameRate: Int): Long {
    if (durationUs <= 0L || frameRate <= 0) return 0L
    return durationUs * frameRate / 1_000_000L
  }
}
