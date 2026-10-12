package com.example.engine.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import androidx.core.net.toUri
import com.example.domain.model.Timeline
import com.example.domain.model.VideoClip
import com.example.engine.composition.ComposedFrame
import com.example.engine.composition.VideoCompositionEngine
import com.example.engine.composition.gpu.EglCore
import com.example.engine.composition.gpu.GpuCompositionRenderer
import com.example.engine.composition.gpu.HardwareVideoTextureSource
import com.example.engine.composition.gpu.OffscreenSurface
import com.example.engine.composition.gpu.TransitionSourceTexture
import com.example.engine.composition.gpu.WindowSurface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Single-clock GPU preview: every video layer (main track, transition sources, overlays) is decoded
 * to the exact source time for the master clock position and blended in one
 * [GpuCompositionRenderer.render] call, the same renderer and frame evaluation the export uses.
 *
 * Threading: all GL, decoder and renderer state lives on one GL thread driven by its own
 * Choreographer. [positionMs] and [isPlaying] are read from that thread and must be thread-safe.
 */
class PreviewCompositor(
  private val context: Context,
  private val positionMs: () -> Long,
  private val isPlaying: () -> Boolean,
  private val resolveUri: (VideoClip) -> String,
  private val onMainLayerUndecodable: (clipId: String) -> Unit = {}
) {

  companion object {
    private const val TAG = "PreviewCompositor"
    /** Longest buffer edge; the TextureView scales it to the view. Bounds GPU cost on 1440p+ screens. */
    private const val MAX_BUFFER_EDGE = 1280
    private const val MAX_IMAGE_EDGE = 1920
    /** Gate rate: redraw at most once per display frame even for 30 fps sources. */
    private const val GATE_FPS = 60
    private const val STATS_EVERY_FRAMES = 120
  }

  data class Stats(
    val renderedFrames: Long = 0,
    val avgFrameMs: Float = 0f,
    val openDecoders: Int = 0,
    val lastPositionMs: Long = 0
  )

  private val _stats = MutableStateFlow(Stats())
  val stats: StateFlow<Stats> = _stats.asStateFlow()

  private val glThread = HandlerThread("AH-Preview-Compositor", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
  private val glHandler = Handler(glThread.looper)
  private val callbackThread = HandlerThread("AH-Preview-Decoder", Process.THREAD_PRIORITY_FOREGROUND).apply { start() }
  private val callbackHandler = Handler(callbackThread.looper)
  private val imageLoader = Executors.newSingleThreadExecutor { r -> Thread(r, "AH-Preview-Images").apply { isDaemon = true } }

  @Volatile private var timeline: Timeline = Timeline()
  private val generation = AtomicLong(0L)
  private val released = AtomicBoolean(false)
  private val cancelDecode = AtomicBoolean(false)

  // GL-thread state
  private var eglCore: EglCore? = null
  private var offscreen: OffscreenSurface? = null
  private var window: WindowSurface? = null
  private var bufferWidth = 0
  private var bufferHeight = 0
  private var renderer: GpuCompositionRenderer? = null
  private val composition = VideoCompositionEngine(context)
  private val gate = PreviewRenderGate()
  private val decoders = LinkedHashMap<String, HardwareVideoTextureSource>()
  private val decoderUris = HashMap<String, String>()
  private val undecodable = HashSet<String>()
  private val imageTextures = HashMap<String, Int>()
  private val loadedImages = ConcurrentHashMap<String, Bitmap>()
  private val requestedImages = ConcurrentHashMap.newKeySet<String>()
  private var frameCallback: Choreographer.FrameCallback? = null
  private var renderedFrames = 0L
  private var statsWindowNs = 0L

  init {
    glHandler.post { setUpGl() }
  }

  /** Thread-safe. Triggers a redraw of the current position. */
  fun updateTimeline(newTimeline: Timeline) {
    timeline = newTimeline
    generation.incrementAndGet()
  }

  fun requestRender() {
    generation.incrementAndGet()
  }

  /** Binds the compositor output to a TextureView surface. Call from the UI thread. */
  fun attachSurface(surfaceTexture: SurfaceTexture, viewWidth: Int, viewHeight: Int) {
    if (released.get() || viewWidth <= 0 || viewHeight <= 0) return
    val scale = minOf(1f, MAX_BUFFER_EDGE.toFloat() / max(viewWidth, viewHeight))
    val w = max(2, (viewWidth * scale).roundToInt() / 2 * 2)
    val h = max(2, (viewHeight * scale).roundToInt() / 2 * 2)
    surfaceTexture.setDefaultBufferSize(w, h)
    glHandler.post {
      val core = eglCore ?: return@post
      releaseWindow()
      try {
        window = WindowSurface(core, Surface(surfaceTexture), releaseSurfaceOnRelease = true).also { it.makeCurrent() }
        bufferWidth = w
        bufferHeight = h
        gate.invalidate()
        Log.i(TAG, "Surface attached ${w}x$h (view ${viewWidth}x$viewHeight)")
      } catch (t: Throwable) {
        Log.e(TAG, "Could not attach preview surface", t)
        window = null
      }
    }
  }

  /**
   * Stops drawing into the surface. Blocks briefly: the TextureView destroys its SurfaceTexture
   * right after this returns, and EGL must not touch it afterwards.
   */
  fun detachSurface() {
    if (released.get()) return
    val done = CountDownLatch(1)
    glHandler.post {
      releaseWindow()
      done.countDown()
    }
    done.await(500, TimeUnit.MILLISECONDS)
  }

  fun release() {
    if (!released.compareAndSet(false, true)) return
    cancelDecode.set(true)
    glHandler.post {
      frameCallback?.let { runCatching { Choreographer.getInstance().removeFrameCallback(it) } }
      frameCallback = null
      decoders.values.forEach { runCatching { it.release() } }
      decoders.clear()
      runCatching { renderer?.release() }
      renderer = null
      releaseWindow()
      runCatching { offscreen?.release() }
      offscreen = null
      runCatching { eglCore?.release() }
      eglCore = null
      glThread.quitSafely()
      callbackThread.quitSafely()
    }
    imageLoader.shutdownNow()
    loadedImages.values.forEach { it.recycle() }
    loadedImages.clear()
  }

  private fun setUpGl() {
    try {
      val core = EglCore(null, EglCore.FLAG_TRY_GLES3)
      eglCore = core
      offscreen = OffscreenSurface(core, 1, 1).also { it.makeCurrent() }
      renderer = GpuCompositionRenderer(context).also { it.initGl() }
      val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
          if (released.get()) return
          try {
            onVsync()
          } catch (t: Throwable) {
            Log.e(TAG, "Preview frame failed", t)
          }
          Choreographer.getInstance().postFrameCallback(this)
        }
      }
      frameCallback = callback
      Choreographer.getInstance().postFrameCallback(callback)
    } catch (t: Throwable) {
      Log.e(TAG, "GPU preview compositor could not start", t)
    }
  }

  private fun releaseWindow() {
    val w = window ?: return
    window = null
    runCatching { offscreen?.makeCurrent() }
    runCatching { w.release() }
  }

  private fun onVsync() {
    val surface = window ?: return
    val pos = positionMs()
    if (!gate.shouldRender(pos, GATE_FPS, generation.get())) return
    val started = SystemClock.elapsedRealtimeNanos()
    surface.makeCurrent()
    renderFrame(pos)
    surface.swapBuffers()
    recordStats(pos, SystemClock.elapsedRealtimeNanos() - started)
  }

  private fun renderFrame(pos: Long) {
    val rend = renderer ?: return
    val tl = timeline
    uploadLoadedImages(rend)

    val plan = PreviewFramePlan.of(composition.evaluateFrame(tl, pos))
    val keep = PreviewLayerPlanner.videoDecoderPlan(tl, pos, plan.decodeClipIds, isPlaying())
    PreviewLayerPlanner.evictions(decoders.keys.toList(), keep).forEach(::releaseDecoder)

    val textures = HashMap<String, TransitionSourceTexture>()
    val decodedSizes = HashMap<String, Pair<Int, Int>>()
    for (req in plan.decodes) {
      if (req.clip.id !in keep || req.clip.id in textures) continue
      val source = decoderFor(req.clip) ?: continue
      try {
        if (source.decodeFrame(req.sourceUs, cancelDecode)) {
          val matrix = source.updateTexImage().copyOf()
          textures[req.clip.id] = TransitionSourceTexture(source.oesTextureId, true, matrix)
          decodedSizes[req.clip.id] = source.width to source.height
        }
      } catch (e: IllegalStateException) {
        Log.w(TAG, "Decoder failed for ${req.clip.name}: ${e.message}")
        markUndecodable(req.clip.id, req.role == PreviewFramePlan.Role.MAIN)
      }
    }
    for (uri in plan.imageUris) requestImage(uri)
    prerollNext(tl, keep)

    val frame = withDecodedSizes(plan.frame, decodedSizes)
    val main = frame.activeClip
    val mainTexture = main?.let { textures[it.id] ?: imageTextures[it.uri]?.let { id -> TransitionSourceTexture(id, false, null) } }

    fun transitionSource(clip: VideoClip): TransitionSourceTexture? =
      textures[clip.id] ?: imageTextures[clip.uri]?.let { TransitionSourceTexture(it, false, null) }

    val overlayTextures = HashMap<String, Int>()
    val overlayMatrices = HashMap<String, FloatArray>()
    for (overlay in frame.activeOverlays) {
      val tex = transitionSource(overlay.clip) ?: continue
      overlayTextures[overlay.clip.id] = tex.textureId
      tex.texMatrix?.let { overlayMatrices[overlay.clip.id] = it }
    }

    val transition = frame.activeTransition?.takeIf { it.type != com.example.domain.model.TransitionType.NONE }
    rend.render(
      frame = frame,
      mainTextureId = mainTexture?.textureId ?: 0,
      isMainOes = mainTexture?.isOes ?: false,
      mainTexMatrix = mainTexture?.texMatrix,
      overlayTextures = overlayTextures,
      overlayTexMatrices = overlayMatrices,
      viewportWidth = bufferWidth,
      viewportHeight = bufferHeight,
      timelineAdjustments = tl.adjustments,
      timelineFilter = tl.filter,
      chromaKey = tl.chromaKey,
      flipYForEncoder = false,
      flipXForEncoder = false,
      transitionBefore = transition?.let { transitionSource(it.clipBefore) },
      transitionAfter = transition?.let { transitionSource(it.clipAfter) },
      deterministicMasks = false
    )
  }

  /** The renderer fits layers by their pixel size; clips imported without metadata report 0x0. */
  private fun withDecodedSizes(frame: ComposedFrame, sizes: Map<String, Pair<Int, Int>>): ComposedFrame {
    fun VideoClip.sized(): VideoClip {
      if (width > 0 && height > 0) return this
      val (w, h) = sizes[id] ?: return this
      return if (w > 0 && h > 0) copy(width = w, height = h) else this
    }
    return frame.copy(
      activeClip = frame.activeClip?.sized(),
      activeOverlays = frame.activeOverlays.map { it.copy(clip = it.clip.sized()) }
    )
  }

  private fun decoderFor(clip: VideoClip): HardwareVideoTextureSource? {
    if (clip.id in undecodable) return null
    val uri = resolveUri(clip)
    decoders[clip.id]?.let { existing ->
      if (decoderUris[clip.id] == uri) return existing
      releaseDecoder(clip.id)
    }
    if (decoders.size >= PreviewLayerPlanner.MAX_VIDEO_DECODERS) return null
    val source = HardwareVideoTextureSource()
    val ok = source.initialize(context, clip.copy(uri = uri), glHandler = glHandler, decoderHandler = callbackHandler)
    if (!ok) {
      source.release()
      markUndecodable(clip.id, timeline.videoClips.any { it.id == clip.id })
      return null
    }
    decoders[clip.id] = source
    decoderUris[clip.id] = uri
    return source
  }

  /** Opens one upcoming decoder per frame and decodes its first picture, so the cut is instant. */
  private fun prerollNext(tl: Timeline, keep: List<String>) {
    val id = keep.firstOrNull { it !in decoders && it !in undecodable } ?: return
    val clip = tl.videoClips.firstOrNull { it.id == id } ?: tl.overlayClips.firstOrNull { it.id == id } ?: return
    val source = decoderFor(clip) ?: return
    try {
      if (source.decodeFrame(clip.timelineToSourceMs(clip.timelineStartMs) * 1000L, cancelDecode)) source.updateTexImage()
    } catch (e: IllegalStateException) {
      markUndecodable(id, tl.videoClips.any { it.id == id })
    }
  }

  private fun releaseDecoder(id: String) {
    decoders.remove(id)?.let { runCatching { it.release() } }
    decoderUris.remove(id)
  }

  private fun markUndecodable(id: String, isMain: Boolean) {
    releaseDecoder(id)
    undecodable.add(id)
    if (isMain) onMainLayerUndecodable(id)
  }

  private fun requestImage(uri: String) {
    if (uri in imageTextures || !requestedImages.add(uri)) return
    imageLoader.execute {
      val bmp = decodeSampledBitmap(uri)
      if (bmp != null && !released.get()) {
        loadedImages[uri] = bmp
        generation.incrementAndGet()
      }
    }
  }

  private fun uploadLoadedImages(rend: GpuCompositionRenderer) {
    if (loadedImages.isEmpty()) return
    for (uri in loadedImages.keys.toList()) {
      val bmp = loadedImages.remove(uri) ?: continue
      val tex = rend.uploadImageTexture("preview_img_$uri", bmp)
      bmp.recycle()
      if (tex > 0) imageTextures[uri] = tex
    }
  }

  private fun decodeSampledBitmap(uriString: String): Bitmap? = try {
    val uri = uriString.toUri()
    fun open() = if (uri.scheme == "content") context.contentResolver.openInputStream(uri) else java.io.FileInputStream(if (uri.scheme == "file") uri.path ?: uriString else uriString)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_IMAGE_EDGE) sample *= 2
    open()?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
  } catch (t: Throwable) {
    Log.w(TAG, "Image load failed for $uriString: ${t.message}")
    null
  }

  private fun recordStats(pos: Long, frameNs: Long) {
    renderedFrames++
    statsWindowNs += frameNs
    if (renderedFrames % STATS_EVERY_FRAMES == 0L) {
      val avg = statsWindowNs / STATS_EVERY_FRAMES / 1_000_000f
      statsWindowNs = 0L
      _stats.value = Stats(renderedFrames, avg, decoders.size, pos)
      Log.i(TAG, "frames=$renderedFrames avgFrame=%.1fms decoders=${decoders.size} pos=$pos".format(avg))
    }
    GLES20.glGetError()
  }
}
