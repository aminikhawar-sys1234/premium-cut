package com.example.engine.export

import com.example.domain.model.VideoClip

/**
 * Which (clip, source time) pairs a tracked effect will ask for while the project is exported.
 *
 * The export asks the tracking sources with `blocking = true` for **every rendered frame** of a clip
 * that carries face reshape / AR filter / body reshape, and that lookup is a cache miss unless the
 * exact source time was already computed: it costs a `MediaMetadataRetriever` frame extraction plus
 * an ML Kit inference (face: up to the 20 s export timeout; body: an 8 s detector wait) *on the
 * thread that has to keep the encoder fed*. Nothing pre-warms those lookups today, although
 * `FaceTrackingEngine.prewarm` exists and is never called from the export path.
 *
 * This object is the pure part of the fix: it lists exactly the source times the render loop will
 * ask for, in the same order, thinned to a bounded number of entries. [com.example.engine.export.ExportTrackingPrewarm]
 * hands those samples to the trackers on a worker thread before the encoder starts rendering, so the
 * per-frame lookups become cache hits and the render thread stops stalling. It never replaces a
 * tracked result with a guess: a sample that cannot be warmed simply stays a miss, exactly the
 * behaviour the export has today.
 *
 * The mapping from timeline position to source time is the one the render loop uses
 * (`faceSourceTimeUs` in `com.example.engine.composition.gpu.FaceStageSupport`): a clip with a
 * freeze frame always reports that frame, otherwise `timelineToSourceMs` (speed, speed ramp and
 * reverse included) is used.
 */
object TrackingPrewarmPlan {

  data class Sample(val clipId: String, val sourceUs: Long)

  /** Frames the export renders for a clip of [durationMs] at [fps]; at least one. */
  fun frameCount(durationMs: Long, fps: Int): Long {
    val safeFps = fps.coerceAtLeast(1)
    return ((durationMs.coerceAtLeast(0L) * safeFps) / 1000L).coerceAtLeast(1L)
  }

  /** Source time the tracked stages sample for [clip] at [timelinePosMs]. */
  fun sourceUsAt(clip: VideoClip, timelinePosMs: Long): Long {
    clip.freezeFrameAtMs?.let { return it.coerceAtLeast(0L) * 1000L }
    return clip.timelineToSourceMs(timelinePosMs).coerceAtLeast(0L) * 1000L
  }

  /**
   * Source times the render loop will ask for while drawing [clip], thinned to at most
   * [maxSamples] entries (first and last frame always kept) so warming stays bounded.
   */
  fun samplesFor(clip: VideoClip, fps: Int, maxSamples: Int = 240): List<Sample> {
    if (maxSamples <= 0) return emptyList()
    val frames = frameCount(clip.durationMs, fps).coerceAtLeast(1L)
    val step = ((frames + maxSamples - 1L) / maxSamples.toLong()).coerceAtLeast(1L)
    val positions = ArrayList<Long>()
    var frame = 0L
    while (frame < frames) {
      positions += clip.timelineStartMs + (frame * 1000L) / fps.coerceAtLeast(1)
      frame += step
    }
    val lastFrame = frames - 1L
    val lastPosition = clip.timelineStartMs + (lastFrame * 1000L) / fps.coerceAtLeast(1)
    if (positions.isEmpty() || positions.last() != lastPosition) positions += lastPosition
    return positions.map { Sample(clip.id, sourceUsAt(clip, it)) }
  }

  /**
   * Samples for every clip of [clips] that needs tracking, thinned evenly across the whole list so
   * the total stays within [maxTotalSamples]. An empty result means there is nothing to warm.
   */
  fun samplesFor(clips: List<VideoClip>, fps: Int, maxTotalSamples: Int = 600): List<Sample> {
    if (maxTotalSamples <= 0) return emptyList()
    val perClip = (maxTotalSamples / clips.size.coerceAtLeast(1)).coerceAtLeast(1)
    return clips.flatMap { samplesFor(it, fps, maxSamples = perClip) }
      .take(maxTotalSamples)
  }
}
