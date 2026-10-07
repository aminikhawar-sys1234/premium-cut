package com.example.engine.ai.cutout

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A subject mask: one confidence byte (0..255) per pixel, rows stored BOTTOM-UP (GL texture order). */
class SubjectMaskPixels(val width: Int, val height: Int, val alpha: ByteArray)

/** A finished mask tagged with the frame bucket / epoch it was computed for. */
class SubjectMaskResult(
  val pixels: SubjectMaskPixels,
  val bucket: Long,
  val epoch: Int
)

/** Pure array helpers (no Android types) so they can be unit-tested on the JVM. */
object MaskGeometry {

  /** Rotates a row-major image clockwise by [deg] (0/90/180/270). Dimensions swap for 90/270. */
  fun rotateCw(src: IntArray, w: Int, h: Int, deg: Int): IntArray {
    val d = ((deg % 360) + 360) % 360
    if (d == 0) return src
    val out = IntArray(src.size)
    for (y in 0 until h) for (x in 0 until w) {
      val v = src[y * w + x]
      when (d) {
        90 -> out[x * h + (h - 1 - y)] = v
        180 -> out[(h - 1 - y) * w + (w - 1 - x)] = v
        270 -> out[(w - 1 - x) * h + y] = v
        else -> return src
      }
    }
    return out
  }

  fun rotateCw(src: ByteArray, w: Int, h: Int, deg: Int): ByteArray {
    val d = ((deg % 360) + 360) % 360
    if (d == 0) return src
    val out = ByteArray(src.size)
    for (y in 0 until h) for (x in 0 until w) {
      val v = src[y * w + x]
      when (d) {
        90 -> out[x * h + (h - 1 - y)] = v
        180 -> out[(h - 1 - y) * w + (w - 1 - x)] = v
        270 -> out[(w - 1 - x) * h + y] = v
        else -> return src
      }
    }
    return out
  }

  /** Image dimensions after a clockwise rotation by [deg]. */
  fun rotatedSize(w: Int, h: Int, deg: Int): Pair<Int, Int> {
    val d = ((deg % 360) + 360) % 360
    return if (d == 90 || d == 270) Pair(h, w) else Pair(w, h)
  }

  /** Flips row order (top-down <-> bottom-up). */
  fun flipRows(src: ByteArray, w: Int, h: Int): ByteArray {
    val out = ByteArray(src.size)
    for (y in 0 until h) System.arraycopy(src, y * w, out, (h - 1 - y) * w, w)
    return out
  }

  /**
   * Builds a top-down opaque ARGB image from bottom-up RGBA readback bytes
   * (glReadPixels order), compositing any transparency over black.
   */
  fun rgbaBottomUpToArgb(rgba: ByteArray, w: Int, h: Int): IntArray {
    val px = IntArray(w * h)
    for (y in 0 until h) {
      val srcRow = (h - 1 - y) * w * 4
      for (x in 0 until w) {
        val o = srcRow + x * 4
        var r = rgba[o].toInt() and 0xFF
        var g = rgba[o + 1].toInt() and 0xFF
        var b = rgba[o + 2].toInt() and 0xFF
        val a = rgba[o + 3].toInt() and 0xFF
        if (a < 255) { r = r * a / 255; g = g * a / 255; b = b * a / 255 }
        px[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
      }
    }
    return px
  }
}

/**
 * Runs the on-device ML Kit subject segmenter off the GL thread.
 * Input is the raw GL readback of a texture; output is a mask in the SAME orientation as that texture,
 * so it can be sampled with the texture's own UVs without any placement maths.
 */
object SubjectMaskWorker {
  private const val TAG = "SubjectMaskWorker"
  private const val TIMEOUT_S = 4L

  private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
    Thread(r, "subject-cutout").apply {
      isDaemon = true
      priority = Thread.NORM_PRIORITY - 1
    }
  }

  private val segmenter: SubjectSegmenter? by lazy {
    runCatching {
      SubjectSegmentation.getClient(
        SubjectSegmenterOptions.Builder()
          .enableForegroundConfidenceMask()
          .build()
      )
    }.onFailure { Log.w(TAG, "Subject segmenter unavailable", it) }.getOrNull()
  }

  fun submit(job: Runnable) {
    runCatching { executor.execute(job) }
  }

  /**
   * @param rgba glReadPixels bytes (RGBA, rows bottom-up), [w] x [h]
   * @param rotationDeg clockwise rotation that makes the texture content upright (0 for already-upright content)
   * @return mask in the texture's own orientation (rows bottom-up), or null if segmentation failed
   */
  fun analyse(rgba: ByteArray, w: Int, h: Int, rotationDeg: Int): SubjectMaskPixels? {
    if (w <= 0 || h <= 0 || rgba.size < w * h * 4) return null
    val seg = segmenter ?: return null
    val rot = ((rotationDeg % 360) + 360) % 360
    var bitmap: Bitmap? = null
    try {
      val argb = MaskGeometry.rgbaBottomUpToArgb(rgba, w, h)
      val (rw, rh) = MaskGeometry.rotatedSize(w, h, rot)
      val upright = MaskGeometry.rotateCw(argb, w, h, rot)
      bitmap = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
      bitmap.setPixels(upright, 0, rw, 0, 0, rw, rh)

      val result = Tasks.await(seg.process(InputImage.fromBitmap(bitmap, 0)), TIMEOUT_S, TimeUnit.SECONDS)
      val buf = result.foregroundConfidenceMask ?: return null
      val n = rw * rh
      buf.rewind()
      if (buf.remaining() < n) return null
      val topDown = ByteArray(n)
      for (i in 0 until n) {
        topDown[i] = (buf.get().coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
      }
      // Undo the analysis rotation, then convert to GL (bottom-up) row order.
      val back = MaskGeometry.rotateCw(topDown, rw, rh, (360 - rot) % 360)
      return SubjectMaskPixels(w, h, MaskGeometry.flipRows(back, w, h))
    } catch (t: Throwable) {
      Log.w(TAG, "Subject segmentation failed", t)
      return null
    } finally {
      bitmap?.recycle()
    }
  }
}
