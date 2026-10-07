package com.example.engine.composition.gpu

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.example.domain.model.VideoClip
import com.example.engine.controller.DecoderManager
import com.example.engine.media.MediaMetadataHelper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Production-grade hardware and software fallback video frame decoder and OpenGL OES texture source.
 *
 * Provides:
 * - MediaExtractor initialization & track selection for content/file/raw URIs.
 * - Hardware MediaCodec decoding with automatic graceful software fallback via DecoderManager.
 * - Direct zero-copy hardware rendering to SurfaceTexture / external OES texture.
 * - Frame-accurate seeking with pre-roll frame dropping (not rendering pre-roll frames to Surface).
 * - CFR & VFR presentation timestamp synchronization and frame repetition upon EOF.
 * - Video geometry handling: orientation metadata (0°, 90°, 180°, 270°), aspect ratio, scaling.
 * - Robust error recovery, codec flush, and leak-free resource release in reverse allocation order.
 */
class HardwareVideoTextureSource : SurfaceTexture.OnFrameAvailableListener {
  companion object {
    private const val TAG = "HwVideoTextureSource"
    private const val DEFAULT_TIMEOUT_US = 2_000L
    /** Upper bound for a single decodeFrame() call (a seek can require decoding a whole GOP). */
    private const val DECODE_DEADLINE_MS = 12_000L
    /** How long to wait for a frame released to the Surface to arrive at the SurfaceTexture. */
    private const val FRAME_ARRIVAL_TIMEOUT_MS = 1_500L
    private const val JUMP_SEEK_THRESHOLD_US = 1_200_000L
  }

  var oesTextureId: Int = 0
    private set
  var surfaceTexture: SurfaceTexture? = null
    private set
  var decoderSurface: Surface? = null
    private set

  private var codec: MediaCodec? = null
  private var extractor: MediaExtractor? = null

  var width: Int = 1920
    private set
  var height: Int = 1080
    private set
  var rotationDegrees: Int = 0
    private set
  val effectiveWidth: Int get() = if (rotationDegrees == 90 || rotationDegrees == 270) height else width
  val effectiveHeight: Int get() = if (rotationDegrees == 90 || rotationDegrees == 270) width else height

  var frameRate: Float = 30f
    private set
  var isHardwareAccelerated: Boolean = true
    private set
  var isInitialized: Boolean = false
    private set

  private var lastRequestUs = Long.MIN_VALUE
  private var isInputEos = false
  private var isOutputEos = false
  private var lastRenderedPtsUs = Long.MIN_VALUE
  private val frameAvailable = AtomicBoolean(false)

  /** Decoded output buffer that is in the future relative to the last request; kept for a later call. */
  private var heldIndex = -1
  private var heldPtsUs = 0L

  /** True once at least one decoded frame has actually reached the SurfaceTexture since the last seek. */
  @Volatile var hasPicture: Boolean = false
    private set

  /** Total frames successfully delivered to the SurfaceTexture (monotonic over the source's life). */
  @Volatile var deliveredFrames: Long = 0L
    private set

  private var ownedHandlerThread: HandlerThread? = null
  private val surfaceLock = Any()

  val transformMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
  private var glHandler: Handler? = null

  /**
   * Initializes the decoder and OES texture surface for the given VideoClip.
   */
  fun initialize(
    context: Context,
    clip: VideoClip,
    glHandler: Handler? = null,
    decoderHandler: Handler? = null,
    decoderManager: DecoderManager = DecoderManager(),
    preferHardware: Boolean = true
  ): Boolean {
    if (isInitialized) return true
    this.glHandler = glHandler

    try {
      // 1. Initialize MediaExtractor
      val ex = MediaExtractor()
      val uri = try { Uri.parse(clip.uri) } catch (_: Exception) { null }
      if (uri != null && (uri.scheme == "content" || uri.scheme == "file")) {
        ex.setDataSource(context, uri, null)
      } else {
        ex.setDataSource(clip.uri)
      }

      // 2. Locate video track
      var trackIndex = -1
      var trackFormat: MediaFormat? = null
      for (i in 0 until ex.trackCount) {
        val format = ex.getTrackFormat(i)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
        if (mime.startsWith("video/")) {
          trackIndex = i
          trackFormat = format
          break
        }
      }

      if (trackIndex < 0 || trackFormat == null) {
        Log.w(TAG, "No valid video track found in ${clip.uri}")
        ex.release()
        return false
      }

      ex.selectTrack(trackIndex)
      extractor = ex

      // 3. Extract dimensions and rotation metadata
      val rawW = trackFormat.getInteger(MediaFormat.KEY_WIDTH).coerceAtLeast(1)
      val rawH = trackFormat.getInteger(MediaFormat.KEY_HEIGHT).coerceAtLeast(1)
      val meta = MediaMetadataHelper.extractMetadata(context, clip.uri)
      rotationDegrees = meta.rotationDegrees
      frameRate = if (meta.frameRate in 10f..120f) meta.frameRate else 30f
      width = if (clip.width > 0) clip.width else rawW
      height = if (clip.height > 0) clip.height else rawH

      // 4. Create OpenGL OES texture and Surface on GL thread
      val latch = CountDownLatch(1)
      var glSuccess = false
      val setupGlAction = Runnable {
        try {
          setupTextureAndSurface(decoderHandler)
          glSuccess = true
        } catch (e: Exception) {
          Log.e(TAG, "Failed creating OES texture/surface", e)
        } finally {
          latch.countDown()
        }
      }

      if (glHandler != null && android.os.Looper.myLooper() != glHandler.looper) {
        glHandler.post(setupGlAction)
        if (!latch.await(3, TimeUnit.SECONDS) || !glSuccess) {
          release()
          return false
        }
      } else {
        setupGlAction.run()
        if (!glSuccess) {
          release()
          return false
        }
      }

      // 5. Configure MediaCodec with hardware and software fallback
      val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: MediaFormat.MIMETYPE_VIDEO_AVC
      val (dec, isHw) = try {
        decoderManager.createDecoder(mime, preferHardware = preferHardware)
      } catch (e: Exception) {
        Log.w(TAG, "Failed to create decoder for $mime, falling back to software decoder", e)
        val swName = decoderManager.findSoftwareDecoderName(mime)
        if (swName != null) {
          Pair(MediaCodec.createByCodecName(swName), false)
        } else {
          Pair(MediaCodec.createDecoderByType(mime), false)
        }
      }

      // The GPU compositor applies clip.naturalRotation itself (see GpuCompositionRenderer).
      // MediaCodec would otherwise also bake KEY_ROTATION into the SurfaceTexture transform
      // matrix, rotating the frame twice (upside-down / sideways export).
      try { trackFormat.setInteger(MediaFormat.KEY_ROTATION, 0) } catch (_: Exception) {}
      dec.configure(trackFormat, decoderSurface, null, 0)
      dec.start()

      codec = dec
      isHardwareAccelerated = isHw
      isInitialized = true
      Log.d(TAG, "Initialized decoder for ${clip.id} ($mime, ${width}x${height}, rot=$rotationDegrees, hw=$isHw)")
      return true
    } catch (e: Exception) {
      Log.e(TAG, "Failed to initialize HardwareVideoTextureSource for ${clip.uri}", e)
      release()
      return false
    }
  }

  private fun setupTextureAndSurface(decoderHandler: Handler?) {
    if (oesTextureId == 0) {
      val textures = IntArray(1)
      GLES20.glGenTextures(1, textures, 0)
      oesTextureId = textures[0]
      GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
      GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

      val st = SurfaceTexture(oesTextureId)
      // The callback must NOT be delivered to the GL thread: decodeFrame() blocks it while waiting
      // for the frame to arrive, which would deadlock (and previously let updateTexImage run early).
      val callbackHandler = decoderHandler ?: run {
        val t = HandlerThread("HwVideoTextureSource-cb").also { it.start() }
        ownedHandlerThread = t
        Handler(t.looper)
      }
      st.setOnFrameAvailableListener(this, callbackHandler)
      surfaceTexture = st
      decoderSurface = Surface(st)
    }
  }

  override fun onFrameAvailable(st: SurfaceTexture) {
    frameAvailable.set(true)
  }

  /**
   * Makes the SurfaceTexture show the source frame that belongs to [targetUs] (microseconds of source time).
   *
   * Semantics (frame-accurate, no drift):
   *  - Frames clearly before the target are decoded and dropped (pre-roll, never sent to the Surface).
   *  - A frame within half a source-frame of the target is rendered to the Surface.
   *  - A frame clearly AFTER the target is held (not consumed); the previously rendered frame stays on the
   *    texture. This is what keeps a 30 fps clip on a 60 fps timeline (or a 24 fps clip at 30 fps)
   *    from running ahead of the timeline.
   *  - After a render the call blocks until the frame has actually arrived at the SurfaceTexture, so the
   *    following updateTexImage() never latches a stale/empty buffer (black or repeated frames).
   *
   * @return true when the texture holds a valid decoded picture for this request (rendered now, or held
   *   from earlier); false if no picture could be produced at all (caller must treat as a decode failure).
   * @throws IllegalStateException if the codec itself reports an error (caller may retry on a software decoder).
   */
  fun decodeFrame(
    targetUs: Long,
    cancelled: AtomicBoolean = AtomicBoolean(false),
    @Suppress("UNUSED_PARAMETER") toleranceUs: Long = 40_000L
  ): Boolean {
    if (!isInitialized) return false
    val c = codec ?: return false
    val ex = extractor ?: return false

    val frameDurUs = (1_000_000.0 / frameRate.coerceIn(10f, 240f)).toLong()
    val halfWindowUs = (frameDurUs / 2).coerceIn(4_000L, 40_000L)

    val lastShownUs = if (lastRenderedPtsUs == Long.MIN_VALUE) lastRequestUs else lastRenderedPtsUs
    val needSeek = lastRequestUs == Long.MIN_VALUE ||
      // jumped backwards past what is already on screen (tiny ms-rounding wobble must not trigger a seek)
      targetUs < lastShownUs - frameDurUs ||
      // jumped far forward: cheaper to seek than to decode through
      (targetUs - maxOf(lastShownUs, lastRequestUs) > JUMP_SEEK_THRESHOLD_US)
    if (needSeek) {
      ex.seekTo(targetUs.coerceAtLeast(0L), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
      c.flush()
      heldIndex = -1
      frameAvailable.set(false)
      isInputEos = false
      isOutputEos = false
      hasPicture = false
      lastRenderedPtsUs = Long.MIN_VALUE
    }
    lastRequestUs = targetUs

    val info = MediaCodec.BufferInfo()
    val deadline = SystemClock.elapsedRealtime() + DECODE_DEADLINE_MS
    var rendered = false

    while (!cancelled.get()) {
      if (SystemClock.elapsedRealtime() > deadline) {
        Log.w(TAG, "decodeFrame timed out for target=${targetUs}us (hasPicture=$hasPicture)")
        break
      }

      val index: Int
      val pts: Long
      val size: Int
      val eos: Boolean
      if (heldIndex >= 0) {
        index = heldIndex
        pts = heldPtsUs
        size = 1 // a held buffer is always a real frame
        eos = false
        heldIndex = -1
      } else {
        feedInput(c, ex)
        val out = c.dequeueOutputBuffer(info, DEFAULT_TIMEOUT_US)
        when {
          out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
            val f = c.outputFormat
            if (f.containsKey(MediaFormat.KEY_WIDTH)) width = f.getInteger(MediaFormat.KEY_WIDTH)
            if (f.containsKey(MediaFormat.KEY_HEIGHT)) height = f.getInteger(MediaFormat.KEY_HEIGHT)
            continue
          }
          out == MediaCodec.INFO_TRY_AGAIN_LATER -> {
            if (isInputEos) {
              // Decoder fully drained and nothing left: hold the last picture (end of clip).
              isOutputEos = true
              break
            }
            continue
          }
          out < 0 -> continue // INFO_OUTPUT_BUFFERS_CHANGED and friends
        }
        index = out
        pts = info.presentationTimeUs.coerceAtLeast(0L)
        size = info.size
        eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
      }

      if (eos && size == 0) {
        c.releaseOutputBuffer(index, false)
        isOutputEos = true
        break
      }

      val future = pts > targetUs + halfWindowUs
      val stale = pts < targetUs - halfWindowUs && !eos
      if (future && hasPicture) {
        heldIndex = index
        heldPtsUs = pts
        break
      }
      if (stale) {
        c.releaseOutputBuffer(index, false)
        continue
      }

      // In window, or the first picture after a seek (nothing to show yet): render it.
      frameAvailable.set(false)
      c.releaseOutputBuffer(index, true)
      lastRenderedPtsUs = pts
      rendered = true
      if (eos) isOutputEos = true
      break
    }

    if (rendered) {
      if (awaitFrameArrival(cancelled)) {
        hasPicture = true
        deliveredFrames++
      } else {
        Log.w(TAG, "Decoded frame never arrived at SurfaceTexture (target=${targetUs}us)")
      }
    }
    return hasPicture
  }

  private fun feedInput(c: MediaCodec, ex: MediaExtractor) {
    if (isInputEos) return
    val inputIndex = c.dequeueInputBuffer(DEFAULT_TIMEOUT_US)
    if (inputIndex < 0) return
    val input = c.getInputBuffer(inputIndex) ?: return
    input.clear()
    val sampleSize = ex.readSampleData(input, 0)
    if (sampleSize < 0) {
      c.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
      isInputEos = true
    } else {
      c.queueInputBuffer(inputIndex, 0, sampleSize, ex.sampleTime.coerceAtLeast(0L), 0)
      ex.advance()
    }
  }

  private fun awaitFrameArrival(cancelled: AtomicBoolean): Boolean {
    val deadline = SystemClock.elapsedRealtime() + FRAME_ARRIVAL_TIMEOUT_MS
    while (!frameAvailable.get()) {
      if (cancelled.get() || SystemClock.elapsedRealtime() > deadline) return false
      try { Thread.sleep(1) } catch (_: InterruptedException) { return false }
    }
    return true
  }

  fun updateTexImage(): FloatArray = synchronized(surfaceLock) {
    val st = surfaceTexture ?: return transformMatrix
    // Only latch when a new frame was actually delivered; otherwise the previous picture stays valid.
    if (frameAvailable.getAndSet(false)) {
      try {
        st.updateTexImage()
        st.getTransformMatrix(transformMatrix)
      } catch (e: Exception) {
        Log.w(TAG, "updateTexImage failed: ${e.message}")
      }
    }
    return transformMatrix
  }

  fun flush() = synchronized(surfaceLock) {
    try {
      codec?.flush()
      heldIndex = -1
      hasPicture = false
      lastRenderedPtsUs = Long.MIN_VALUE
      lastRequestUs = Long.MIN_VALUE
      isInputEos = false
      isOutputEos = false
      frameAvailable.set(false)
    } catch (e: Exception) {
      Log.w(TAG, "flush error: ${e.message}")
    }
  }

  fun release() = synchronized(surfaceLock) {
    isInitialized = false
    heldIndex = -1
    try { codec?.stop() } catch (_: Throwable) {}
    try { codec?.release() } catch (_: Throwable) {}
    codec = null

    try { extractor?.release() } catch (_: Throwable) {}
    extractor = null

    try { decoderSurface?.release() } catch (_: Throwable) {}
    decoderSurface = null

    try { surfaceTexture?.release() } catch (_: Throwable) {}
    surfaceTexture = null

    val texId = oesTextureId
    if (texId != 0) {
      oesTextureId = 0
      val action = Runnable {
        GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
      }
      val handler = glHandler
      if (handler != null && android.os.Looper.myLooper() != handler.looper) {
        handler.post(action)
      } else {
        action.run()
      }
    }
    ownedHandlerThread?.quitSafely()
    ownedHandlerThread = null
    Log.d(TAG, "HardwareVideoTextureSource released")
  }
}
