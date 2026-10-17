package com.example.engine.preview

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.Surface
import android.view.TextureView
import com.example.domain.model.VideoClip
import com.example.engine.media.MediaPlayableUri
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Overlay/PIP decoder with no playback clock. [present] seeks to [sourcePosMs] on the
 * compositor's tick so every overlay stays on the same CTI as the main lane.
 *
 * All MediaCodec/MediaExtractor work runs on a dedicated single worker thread. Codec
 * creation (extractor data source, createDecoderByType, configure, start) and the
 * seek-decode-render loop used to execute on the caller thread (Main) and blocked the UI
 * for 100-400 ms per overlay per timeline update - with several overlays the editor froze
 * while importing or scrubbing. Bursts of present() calls collapse into one pending slot,
 * so scrub ticks can never queue up behind slow decodes.
 */
class ClockDrivenSurfaceDecoder(
  private val context: Context,
  val clipId: String
) {
  companion object {
    private const val TAG = "ClockDrivenDecoder"
    private const val TIMEOUT_US = 8_000L
    private const val SAME_FRAME_EPSILON_MS = 16L
  }

  private data class PresentRequest(val clip: VideoClip, val sourcePosMs: Long)

  @Volatile private var attachedView: TextureView? = null
  @Volatile private var outputSurface: Surface? = null
  @Volatile private var lastPresentedMs = Long.MIN_VALUE

  // Executor-confined state: only the worker thread touches codec/extractor/loadedUri.
  private var extractor: MediaExtractor? = null
  private var codec: MediaCodec? = null
  private var loadedUri: String? = null

  private val released = AtomicBoolean(false)
  private val workerScheduled = AtomicBoolean(false)
  private val pendingPresent = AtomicReference<PresentRequest?>(null)
  private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
    Thread(r, "clock-decoder-$clipId").apply { isDaemon = true }
  }

  private fun post(block: () -> Unit) {
    if (released.get()) return
    try {
      worker.execute(block)
    } catch (_: RejectedExecutionException) {
      // Worker already shut down (release raced with a UI callback) - safe to drop.
    }
  }

  fun attach(view: TextureView) {
    if (released.get()) return
    if (attachedView === view && outputSurface != null) return
    attachedView = view
    val st = view.surfaceTexture
    if (st != null) {
      post { bindSurfaceTexture(st) }
    } else {
      view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
          if (attachedView === view) post { bindSurfaceTexture(surface) }
        }
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
          if (attachedView === view) post { detachSurface() }
          return true
        }
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
      }
    }
  }

  fun present(clip: VideoClip, sourcePosMs: Long) {
    if (released.get()) return
    val target = sourcePosMs.coerceAtLeast(0L)
    // Cheap caller-thread dedupe: skip requests within a frame of the last render.
    if (lastPresentedMs != Long.MIN_VALUE && abs(target - lastPresentedMs) < SAME_FRAME_EPSILON_MS) return
    // Collapse bursts into one slot: while the worker decodes, newer ticks simply replace
    // the pending request instead of queueing up behind it.
    pendingPresent.set(PresentRequest(clip, target))
    if (workerScheduled.compareAndSet(false, true)) {
      post { drainPresents() }
    }
  }

  private fun drainPresents() {
    try {
      while (true) {
        val request = pendingPresent.getAndSet(null) ?: break
        presentOnWorker(request.clip, request.sourcePosMs)
      }
    } finally {
      workerScheduled.set(false)
      // A request may have landed between the last getAndSet(null) and the flag reset.
      if (pendingPresent.get() != null && workerScheduled.compareAndSet(false, true)) {
        post { drainPresents() }
      }
    }
  }

  private fun presentOnWorker(clip: VideoClip, target: Long) {
    if (released.get()) return
    if (lastPresentedMs != Long.MIN_VALUE && abs(target - lastPresentedMs) < SAME_FRAME_EPSILON_MS) return
    try {
      ensureCodec(clip)
      val c = codec ?: return
      val ex = extractor ?: return
      val targetUs = target * 1000L
      ex.seekTo(targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
      c.flush()
      renderNearestFrame(c, ex, targetUs)
      lastPresentedMs = target
    } catch (t: Throwable) {
      Log.w(TAG, "present failed for $clipId @${target}ms", t)
    }
  }

  fun release() {
    if (!released.compareAndSet(false, true)) return
    attachedView = null
    pendingPresent.set(null)
    // Detach and free codec resources on the worker so cleanup never races a running decode.
    try {
      worker.execute {
        detachSurface()
        outputSurface = null
        try { codec?.stop() } catch (_: Throwable) {}
        try { codec?.release() } catch (_: Throwable) {}
        codec = null
        try { extractor?.release() } catch (_: Throwable) {}
        extractor = null
        loadedUri = null
        lastPresentedMs = Long.MIN_VALUE
      }
    } catch (_: RejectedExecutionException) {}
    // shutdown() lets the queued cleanup above run, then the worker thread exits (no leak).
    worker.shutdown()
  }

  private fun bindSurfaceTexture(st: SurfaceTexture) {
    if (released.get()) return
    try {
      outputSurface?.release()
    } catch (_: Throwable) {}
    outputSurface = Surface(st)
    // Reconfigure the codec onto the new surface on the next present().
    try { codec?.stop() } catch (_: Throwable) {}
    try { codec?.release() } catch (_: Throwable) {}
    codec = null
    lastPresentedMs = Long.MIN_VALUE
  }

  private fun detachSurface() {
    try { outputSurface?.release() } catch (_: Throwable) {}
    outputSurface = null
    try { codec?.stop() } catch (_: Throwable) {}
    try { codec?.release() } catch (_: Throwable) {}
    codec = null
  }

  private fun ensureCodec(clip: VideoClip) {
    val uri = clip.uri
    if (codec != null && loadedUri == uri && outputSurface != null) return
    val surface = outputSurface ?: return
    try { codec?.stop() } catch (_: Throwable) {}
    try { codec?.release() } catch (_: Throwable) {}
    codec = null
    try { extractor?.release() } catch (_: Throwable) {}
    val ex = MediaExtractor()
    val playable = MediaPlayableUri.toExoPlayerUri(context, uri)
    if (playable.scheme == "content" || playable.scheme == "file" || playable.scheme == "android.resource") {
      ex.setDataSource(context, playable, null)
    } else {
      ex.setDataSource(context, Uri.parse(uri), null)
    }
    var videoTrack = -1
    var format: MediaFormat? = null
    for (i in 0 until ex.trackCount) {
      val f = ex.getTrackFormat(i)
      val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
      if (mime.startsWith("video/")) {
        videoTrack = i
        format = f
        break
      }
    }
    if (videoTrack < 0 || format == null) {
      ex.release()
      return
    }
    ex.selectTrack(videoTrack)
    val mime = format.getString(MediaFormat.KEY_MIME) ?: return
    val dec = MediaCodec.createDecoderByType(mime)
    if (Build.VERSION.SDK_INT >= 23) {
      try { format.setInteger(MediaFormat.KEY_ROTATION, 0) } catch (_: Throwable) {}
    }
    dec.configure(format, surface, null, 0)
    dec.start()
    extractor = ex
    codec = dec
    loadedUri = uri
  }

  private fun renderNearestFrame(codec: MediaCodec, extractor: MediaExtractor, targetUs: Long) {
    val info = MediaCodec.BufferInfo()
    var inputEos = false
    var rendered = false
    val deadline = System.nanoTime() + 250_000_000L
    while (!rendered && System.nanoTime() < deadline) {
      if (!inputEos) {
        val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
        if (inIndex >= 0) {
          val buffer = codec.getInputBuffer(inIndex)
          val sample = if (buffer != null) extractor.readSampleData(buffer, 0) else -1
          if (sample < 0) {
            codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputEos = true
          } else {
            val pts = extractor.sampleTime
            codec.queueInputBuffer(inIndex, 0, sample, pts.coerceAtLeast(0L), 0)
            extractor.advance()
          }
        }
      }
      val out = codec.dequeueOutputBuffer(info, TIMEOUT_US)
      if (out >= 0) {
        val pts = info.presentationTimeUs
        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
        val closeEnough = pts >= targetUs - 40_000L || eos
        codec.releaseOutputBuffer(out, closeEnough && info.size > 0)
        if (closeEnough) rendered = true
        if (eos) break
      }
    }
  }
}
