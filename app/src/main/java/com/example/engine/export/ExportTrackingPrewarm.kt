package com.example.engine.export

import android.util.Log
import com.ahstudio.face.deformation.FaceWarpRegistry
import com.ahstudio.face.overlay.ArOverlayRegistry
import com.example.domain.model.Timeline
import com.example.domain.model.VideoClip
import com.example.engine.effects.BodyWarpRegistry

/**
 * Warms the face / body tracking the export is about to ask for, before the encoder starts
 * rendering.
 *
 * Why this exists: a clip with face reshape, an AR filter or body reshape is asked for a tracked
 * pose **once per exported frame**, with `blocking = true`, on the GL thread that also has to keep
 * the encoder fed (see [TrackingPrewarmPlan]). Each of those lookups is a cache miss unless the
 * exact source time was computed before, and each miss costs a `MediaMetadataRetriever` frame
 * extraction plus an ML Kit inference - up to the tracker's own export timeout. On a real project
 * (30 s at 30 fps = 900 frames) that is minutes of stalling inside the frame loop, which is how a
 * motion-tracked project turns into an export that crawls, trips the encoder-idle watchdog and
 * times out in teardown - while a simple project exports fine.
 *
 * This runs the very same lookups the render loop would run, but *before* the encoder is in the
 * pipeline, on a worker thread, bounded by [BUDGET_MS] and [TrackingPrewarmPlan]'s sample cap. It
 * never invents a result: samples that cannot be warmed stay misses, and the render loop then does
 * exactly what it does today. Nothing about the picture changes - only when the work happens.
 *
 * Nothing is warmed unless a clip actually carries tracking parameters, so projects without tracked
 * effects (and the preview, which never uses blocking lookups) are untouched.
 */
object ExportTrackingPrewarm {
  private const val TAG = "ExportTrackingPrewarm"

  /** Total wall-clock budget. Warming is an optimisation; it must never delay the export for long. */
  const val BUDGET_MS = 10_000L

  /** Upper bound on warmed lookups for the whole export (see TrackingPrewarmPlan). */
  const val MAX_SAMPLES = 600

  /** Clips of [timeline] whose export frames need a tracked pose. */
  fun trackedClips(timeline: Timeline): List<VideoClip> =
    (timeline.videoClips + timeline.overlayClips).filter { clip ->
      FaceWarpRegistry.paramsFor(clip.id) != null ||
        ArOverlayRegistry.paramsFor(clip.id) != null ||
        BodyWarpRegistry.paramsFor(clip.id) != null
    }

  /**
   * Warms every tracked lookup the export will make for [timeline] at [fps]. Returns the number of
   * samples handed to the trackers (0 when the project has no tracked effect).
   */
  fun warm(timeline: Timeline, fps: Int, budgetMs: Long = BUDGET_MS): Int {
    val clips = trackedClips(timeline)
    if (clips.isEmpty()) return 0

    val faceSource = FaceWarpRegistry.faceSource
    val poseSource = BodyWarpRegistry.poseSource
    if (faceSource == null && poseSource == null) {
      // The editor never installed a tracking source, so the stages pass through and there is
      // nothing to warm. (FaceWarpRegistry params restored from a project without a source are the
      // same no-op the render loop performs.)
      Log.i(TAG, "${clips.size} clip(s) carry tracking params but no tracking source is installed")
      return 0
    }

    val samples = TrackingPrewarmPlan.samplesFor(clips, fps, MAX_SAMPLES)
    val deadline = System.currentTimeMillis() + budgetMs.coerceAtLeast(0L)
    var warmed = 0
    var skipped = 0
    val startedMs = System.currentTimeMillis()

    for (sample in samples) {
      if (System.currentTimeMillis() >= deadline) {
        skipped = samples.size - warmed
        break
      }
      // The same call the render loop makes, so the tracker caches exactly what it will be asked
      // for. A failure here is not fatal: the render loop retries it in place, as it does today.
      val ok = runCatching {
        if (faceSource != null) faceSource.facesAt(sample.clipId, sample.sourceUs, true)
        if (poseSource != null) poseSource.poseAt(sample.clipId, sample.sourceUs, true)
      }.isSuccess
      if (ok) warmed++ else skipped++
    }

    Log.i(
      TAG,
      "Warmed $warmed of ${samples.size} tracked lookup(s) for ${clips.size} clip(s) in " +
        "${System.currentTimeMillis() - startedMs}ms${if (skipped > 0) " ($skipped left to the render loop)" else ""}"
    )
    return warmed
  }
}
