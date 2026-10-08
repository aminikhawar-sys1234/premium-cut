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
 */
class ClipBodyTracker(
  private val context: Context,
  private val uriFor: (String) -> String?,
) : BodyPoseSource {

  private val executor = Executors.newSingleThreadExecutor()
  private val cache = ConcurrentHashMap<String, Pair<Long, BodyPose>>()
  private val inflight = ConcurrentHashMap.newKeySet<String>()

  override fun poseAt(clipId: String, sourceTimeUs: Long, blocking: Boolean): BodyPose? {
    val hit = cache[clipId]
    if (hit != null && kotlin.math.abs(hit.first - sourceTimeUs) <= if (blocking) 0L else HOLD_US) {
      if (!blocking || hit.first == sourceTimeUs) return hit.second
    }
    if (!blocking) {
      if (inflight.add(clipId)) {
        executor.execute {
          try {
            detect(clipId, sourceTimeUs)?.let { cache[clipId] = sourceTimeUs to it }
          } finally {
            inflight.remove(clipId)
          }
        }
      }
      return hit?.second
    }
    return detect(clipId, sourceTimeUs)?.also { cache[clipId] = sourceTimeUs to it }
  }

  private fun detect(clipId: String, sourceTimeUs: Long): BodyPose? {
    val uri = uriFor(clipId) ?: return null
    val retriever = MediaMetadataRetriever()
    return try {
      retriever.setDataSource(context, Uri.parse(uri))
      val bmp = if (Build.VERSION.SDK_INT >= 27) {
        retriever.getScaledFrameAtTime(sourceTimeUs, MediaMetadataRetriever.OPTION_CLOSEST, 480, 480)
      } else {
        retriever.getFrameAtTime(sourceTimeUs, MediaMetadataRetriever.OPTION_CLOSEST)
      } ?: return null
      val w = bmp.width.coerceAtLeast(1).toFloat()
      val h = bmp.height.coerceAtLeast(1).toFloat()
      val pose = Tasks.await(
        MlKitDetectorPool.poseDetector().process(InputImage.fromBitmap(bmp, 0)),
        8,
        TimeUnit.SECONDS
      )
      bmp.recycle()
      fun joint(type: Int): Vec2? {
        val lm = pose.getPoseLandmark(type) ?: return null
        if (lm.inFrameLikelihood < 0.35f) return null
        return Vec2(lm.position.x / w, lm.position.y / h)
      }
      BodyPose(
        leftShoulder = joint(PoseLandmark.LEFT_SHOULDER),
        rightShoulder = joint(PoseLandmark.RIGHT_SHOULDER),
        leftHip = joint(PoseLandmark.LEFT_HIP),
        rightHip = joint(PoseLandmark.RIGHT_HIP),
        leftKnee = joint(PoseLandmark.LEFT_KNEE),
        rightKnee = joint(PoseLandmark.RIGHT_KNEE),
        leftAnkle = joint(PoseLandmark.LEFT_ANKLE),
        rightAnkle = joint(PoseLandmark.RIGHT_ANKLE),
      )
    } catch (_: Throwable) {
      null
    } finally {
      runCatching { retriever.release() }
    }
  }

  fun shutdown() {
    executor.shutdownNow()
  }

  private companion object {
    const val HOLD_US = 280_000L
  }
}
