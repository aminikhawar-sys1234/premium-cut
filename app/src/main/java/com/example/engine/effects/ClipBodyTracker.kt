package com.example.engine.effects

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.ahstudio.face.core.Vec2
import com.example.engine.ai.MlKitDetectorPool
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseLandmark
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Pose source for body reshape. Detection runs off the caller thread.
 * Preview never blocks: it reuses a pose within [HOLD_US]. Export waits for this frame.
 *
 * Temporal stability (why the reshape used to look different in every scene):
 *  - Results live in a timestamp-keyed per-clip LRU (the old single-slot cache kept exactly ONE
 *    pose per clip, so the export prewarm overwrote itself down to the last frame and every
 *    rendered frame re-detected on the GL thread).
 *  - A failed / missing detection coasts to the last-good pose within [COAST_US] instead of
 *    dropping the warp to identity for individual frames (the mid-clip flicker).
 *  - Landmarks are EMA-smoothed per clip, so per-frame ML jitter cannot wobble the warp.
 *  - The [MediaMetadataRetriever] is reused per clip (the old code re-opened the container for
 *    every single frame), and detection samples at 640px for steadier landmarks.
 */
class ClipBodyTracker(
  private val context: Context,
  private val uriFor: (String) -> String?,
) : BodyPoseSource {

  private val executor = Executors.newSingleThreadExecutor()
  private val inflight = ConcurrentHashMap.newKeySet<String>()

  /** Timestamp-keyed pose history per clip (access-ordered, oldest evicted beyond [MAX_HISTORY]). */
  private val histories = HashMap<String, LinkedHashMap<Long, BodyPose>>()

  /** Last successfully detected pose per clip: the coasting source when a frame fails. */
  private val lastGood = ConcurrentHashMap<String, Pair<Long, BodyPose>>()

  /** Reused retrievers per clip (bounded); each open() costs a container parse per frame otherwise. */
  private val retrievers = object : LinkedHashMap<String, MediaMetadataRetriever>(4, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MediaMetadataRetriever>): Boolean {
      if (size <= MAX_RETRIEVERS) return false
      runCatching { eldest.value.release() }
      return true
    }
  }

  override fun poseAt(clipId: String, sourceTimeUs: Long, blocking: Boolean): BodyPose? {
    val cached = nearest(clipId, sourceTimeUs, if (blocking) BLOCK_TOLERANCE_US else HOLD_US)
    if (cached != null) return cached
    if (!blocking) {
      if (inflight.add(clipId)) {
        executor.execute {
          try {
            detectAndCache(clipId, sourceTimeUs)
          } finally {
            inflight.remove(clipId)
          }
        }
      }
      // Coast: any cached pose for this clip is better than dropping the warp mid-clip; the
      // confidence gate in the warp stage keeps a stale-appearing pose from over-deforming.
      return nearest(clipId, sourceTimeUs, Long.MAX_VALUE)
    }
    return detectAndCache(clipId, sourceTimeUs) ?: coast(clipId, sourceTimeUs, EXPORT_COAST_US)
  }

  /** Nearest cached pose for [clipId] within [maxDistanceUs]; null when none is close enough. */
  private fun nearest(clipId: String, sourceTimeUs: Long, maxDistanceUs: Long): BodyPose? {
    synchronized(histories) {
      val history = histories[clipId] ?: return null
      var best: BodyPose? = null
      var bestDistance = Long.MAX_VALUE
      for ((t, pose) in history) {
        val d = kotlin.math.abs(t - sourceTimeUs)
        if (d < bestDistance) {
          bestDistance = d
          best = pose
        }
      }
      return if (bestDistance <= maxDistanceUs) best else null
    }
  }

  /** Last-good pose within [maxAgeUs] of [sourceTimeUs], used when this frame cannot be detected. */
  private fun coast(clipId: String, sourceTimeUs: Long, maxAgeUs: Long): BodyPose? {
    val hit = lastGood[clipId] ?: return null
    return if (kotlin.math.abs(hit.first - sourceTimeUs) <= maxAgeUs) hit.second else null
  }

  private fun detectAndCache(clipId: String, sourceTimeUs: Long): BodyPose? {
    val raw = detect(clipId, sourceTimeUs) ?: return null
    val smoothed = smoothInto(clipId, sourceTimeUs, raw)
    synchronized(histories) {
      val history = histories.getOrPut(clipId) {
        object : LinkedHashMap<Long, BodyPose>(64, 0.75f, true) {
          override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, BodyPose>) =
            size > MAX_HISTORY
        }
      }
      history[sourceTimeUs] = smoothed
    }
    lastGood[clipId] = sourceTimeUs to smoothed
    return smoothed
  }

  /**
   * EMA on the raw landmarks. A small time delta blends heavily (jitter cancelled); a large gap
   * (the user scrubbed, or the subject genuinely moved) snaps to the fresh detection instead of
   * dragging the warp behind the body.
   */
  private fun smoothInto(clipId: String, sourceTimeUs: Long, raw: BodyPose): BodyPose {
    val previous = lastGood[clipId]?.takeIf { sourceTimeUs > it.first } ?: return raw
    val dtUs = (sourceTimeUs - previous.first).coerceAtLeast(0L)
    if (dtUs > SMOOTHING_BREAK_US) return raw
    val alpha = (dtUs.toFloat() / (dtUs + SMOOTHING_TAU_US)).coerceIn(0.15f, 1f)
    fun blend(a: Vec2?, b: Vec2?): Vec2? {
      if (a == null || b == null) return b ?: a
      return Vec2(a.x + (b.x - a.x) * alpha, a.y + (b.y - a.y) * alpha)
    }
    return BodyPose(
      leftShoulder = blend(previous.second.leftShoulder, raw.leftShoulder),
      rightShoulder = blend(previous.second.rightShoulder, raw.rightShoulder),
      leftHip = blend(previous.second.leftHip, raw.leftHip),
      rightHip = blend(previous.second.rightHip, raw.rightHip),
      leftKnee = blend(previous.second.leftKnee, raw.leftKnee),
      rightKnee = blend(previous.second.rightKnee, raw.rightKnee),
      leftAnkle = blend(previous.second.leftAnkle, raw.leftAnkle),
      rightAnkle = blend(previous.second.rightAnkle, raw.rightAnkle),
      confidence = previous.second.confidence + (raw.confidence - previous.second.confidence) * alpha,
    )
  }

  private fun retrieverFor(uri: String): MediaMetadataRetriever =
    synchronized(retrievers) {
      retrievers[uri]?.let { return it }
      val r = MediaMetadataRetriever()
      r.setDataSource(context, Uri.parse(uri))
      retrievers[uri] = r
      r
    }

  private fun detect(clipId: String, sourceTimeUs: Long): BodyPose? {
    val uri = uriFor(clipId)
    if (uri == null) {
      // No source for this clip: the body reshape cannot be drawn for any frame. Recorded, because
      // a silently missing effect is exactly what makes an export look "fine" while the user sees
      // the slider do nothing.
      com.example.engine.export.TrackingHealth.record(clipId, sourceTimeUs, "no media uri for clip $clipId")
      return null
    }
    val retriever = try {
      retrieverFor(uri)
    } catch (t: Throwable) {
      com.example.engine.export.TrackingHealth.record(
        clipId, sourceTimeUs, "could not open media for body tracking: ${t.message ?: t.javaClass.simpleName}"
      )
      return null
    }
    return try {
      val bmp = if (Build.VERSION.SDK_INT >= 27) {
        retriever.getScaledFrameAtTime(sourceTimeUs, MediaMetadataRetriever.OPTION_CLOSEST, 640, 640)
      } else {
        retriever.getFrameAtTime(sourceTimeUs, MediaMetadataRetriever.OPTION_CLOSEST)
      }
      if (bmp == null) {
        com.example.engine.export.TrackingHealth.record(
          clipId, sourceTimeUs, "no frame for body tracking at ${sourceTimeUs / 1000}ms"
        )
        return null
      }
      val w = bmp.width.coerceAtLeast(1).toFloat()
      val h = bmp.height.coerceAtLeast(1).toFloat()
      val pose = Tasks.await(
        MlKitDetectorPool.poseDetector().process(InputImage.fromBitmap(bmp, 0)),
        8,
        TimeUnit.SECONDS
      )
      bmp.recycle()
      val joints = arrayOfNulls<Vec2>(8)
      fun joint(slot: Int, type: Int) {
        val lm = pose.getPoseLandmark(type) ?: return
        if (lm.inFrameLikelihood < MIN_JOINT_LIKELIHOOD) return
        joints[slot] = Vec2(lm.position.x / w, lm.position.y / h)
      }
      joint(0, PoseLandmark.LEFT_SHOULDER)
      joint(1, PoseLandmark.RIGHT_SHOULDER)
      joint(2, PoseLandmark.LEFT_HIP)
      joint(3, PoseLandmark.RIGHT_HIP)
      joint(4, PoseLandmark.LEFT_KNEE)
      joint(5, PoseLandmark.RIGHT_KNEE)
      joint(6, PoseLandmark.LEFT_ANKLE)
      joint(7, PoseLandmark.RIGHT_ANKLE)
      val present = joints.count { it != null }
      BodyPose(
        leftShoulder = joints[0],
        rightShoulder = joints[1],
        leftHip = joints[2],
        rightHip = joints[3],
        leftKnee = joints[4],
        rightKnee = joints[5],
        leftAnkle = joints[6],
        rightAnkle = joints[7],
        confidence = present / 8f,
      )
    } catch (t: Throwable) {
      // Was swallowed to null, so a failing pose detector quietly dropped the body reshape from the
      // exported video. The reason is now recorded (and reported by the export).
      com.example.engine.export.TrackingHealth.record(
        clipId, sourceTimeUs,
        "pose detection failed: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}"
      )
      null
    }
  }

  fun shutdown() {
    executor.shutdownNow()
    synchronized(retrievers) {
      retrievers.values.forEach { runCatching { it.release() } }
      retrievers.clear()
    }
    synchronized(histories) { histories.clear() }
    lastGood.clear()
  }

  private companion object {
    const val HOLD_US = 280_000L
    /** A blocking (export) lookup accepts a pose from an adjacent frame bucket before re-detecting. */
    const val BLOCK_TOLERANCE_US = 33_333L
    /** How far a blocking lookup may coast to the last-good pose before giving up. */
    const val EXPORT_COAST_US = 150_000L
    /** Landmark EMA time constant (~0.12 s): fast enough to follow motion, slow enough to de-jitter. */
    const val SMOOTHING_TAU_US = 120_000f
    /** Gaps larger than this are treated as a jump (no smoothing across it). */
    const val SMOOTHING_BREAK_US = 300_000L
    const val MIN_JOINT_LIKELIHOOD = 0.35f
    const val MAX_HISTORY = 1_200
    const val MAX_RETRIEVERS = 3
  }
}
