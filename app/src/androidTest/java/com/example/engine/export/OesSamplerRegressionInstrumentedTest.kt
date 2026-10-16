package com.example.engine.export

import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.domain.model.ChromaKeySettings
import com.example.domain.model.FilterSettings
import com.example.domain.model.VideoAdjustments
import com.example.domain.model.VideoClip
import com.example.engine.composition.ComposedFrame
import com.example.engine.composition.gpu.EglCore
import com.example.engine.composition.gpu.GpuCompositionRenderer
import com.example.engine.effects.AndroidTestContextHolder
import com.example.engine.effects.LookVerificationReport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Regression cover for the all-black export caused by a GLES sampler-type conflict
 * (device report: "Every sampled frame of the export came out black ... layers=1 texture=N
 * compositor=kotlin glErrors=[0x502]").
 *
 * Root cause: `GpuShaders.buildFragmentShader(isOes = true)` contains two samplers of DIFFERENT
 * types — `samplerExternalOES uTexture` (texture unit 0) and `sampler2D uChromaBgTex`. The
 * `uChromaBgTex` unit assignment used to happen only when the chroma key was enabled, so for every
 * ordinary video frame both samplers pointed at unit 0. The GLES 2.0/3.0 specs make every draw with
 * that program state fail with GL_INVALID_OPERATION and write nothing, so the OES->2D conversion
 * pass stayed black on spec-enforcing drivers and the whole export came out black.
 *
 * This test drives a REAL OES frame (SurfaceTexture painted through a Canvas — the same external
 * texture type a MediaCodec decoder renders into) through the production
 * [GpuCompositionRenderer.render] on a real GLES context and asserts, from read-back pixels, that:
 *
 *  1. with the chroma key DISABLED (the failing case) the frame reaches the framebuffer un-black
 *     and the renderer reports zero GL errors;
 *  2. with the chroma key ENABLED the key still composites over the configured background.
 */
@RunWith(AndroidJUnit4::class)
class OesSamplerRegressionInstrumentedTest {

  private companion object {
    const val W = 192
    const val H = 160
    /** Distinctive, non-primary colour so a channel swap or a cleared buffer cannot pass. */
    val SOURCE_COLOR = Color.rgb(203, 88, 36)
    const val TOLERANCE = 34
  }

  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

  private lateinit var core: EglCore
  private var eglSurface: EGLSurface? = null
  private lateinit var renderer: GpuCompositionRenderer
  private var oesTexture = 0
  private var surfaceTexture: SurfaceTexture? = null
  private var surface: Surface? = null
  private var callbackThread: HandlerThread? = null

  @Before
  fun setUp() {
    AndroidTestContextHolder.context = context
    LookVerificationReport.section("OES sampler regression (real GLES, device)")
    core = EglCore(null, EglCore.FLAG_TRY_GLES3)
    eglSurface = core.createOffscreenSurface(W, H)
    core.makeCurrent(eglSurface!!)
    LookVerificationReport.line(
      "gl=" + (GLES20.glGetString(GLES20.GL_VERSION) ?: "?") +
        " renderer=" + (GLES20.glGetString(GLES20.GL_RENDERER) ?: "?")
    )
    renderer = GpuCompositionRenderer(context)
    // The field failure happened on the Kotlin compositor path; force it so the test is
    // deterministic on devices where the native engine is available.
    renderer.nativeCompositorEnabled = false
    renderer.initGl()
  }

  @After
  fun tearDown() {
    runCatching { surface?.release() }
    runCatching { surfaceTexture?.release() }
    if (oesTexture != 0) runCatching { GLES20.glDeleteTextures(1, intArrayOf(oesTexture), 0) }
    runCatching { renderer.release() }
    runCatching { core.makeNothingCurrent() }
    eglSurface?.let { runCatching { core.releaseSurface(it) } }
    runCatching { core.release() }
    callbackThread?.quitSafely()
  }

  // ---------------------------------------------------------------- helpers

  /** Creates the OES texture + SurfaceTexture and paints one [color] frame into it. */
  private fun latchOesFrame(color: Int): FloatArray {
    val ids = IntArray(1)
    GLES20.glGenTextures(1, ids, 0)
    oesTexture = ids[0]
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

    val st = SurfaceTexture(oesTexture)
    surfaceTexture = st
    st.setDefaultBufferSize(W, H)
    val arrived = CountDownLatch(1)
    val thread = HandlerThread("oes-test-frames").also { it.start() }
    callbackThread = thread
    st.setOnFrameAvailableListener({ arrived.countDown() }, Handler(thread.looper))

    val out = Surface(st)
    surface = out
    val canvas = out.lockCanvas(null)
    try {
      canvas.drawColor(color)
    } finally {
      out.unlockCanvasAndPost(canvas)
    }
    assertTrue("painted frame never arrived at the SurfaceTexture", arrived.await(5, TimeUnit.SECONDS))
    st.updateTexImage()
    val texMatrix = FloatArray(16)
    st.getTransformMatrix(texMatrix)
    return texMatrix
  }

  private fun composedFrame(clip: VideoClip): ComposedFrame {
    val matrix = ColorMatrix()
    return ComposedFrame(
      timelinePosMs = 0L,
      activeClip = clip,
      clipSourcePosMs = 0L,
      activeOverlays = emptyList(),
      activeTexts = emptyList(),
      activeStickers = emptyList(),
      activeTransition = null,
      colorMatrix = matrix,
      colorFilter = ColorMatrixColorFilter(matrix)
    )
  }

  private fun render(chromaKey: ChromaKeySettings, texMatrix: FloatArray): GpuCompositionRenderer.RenderReport {
    val clip = VideoClip(
      id = "oes-regression-clip",
      name = "oes-regression-clip",
      isVideo = true,
      width = W,
      height = H,
      durationMs = 1000L,
      sourceEndMs = 1000L,
      sourceTotalDurationMs = 1000L
    )
    // Same presentation as the export encoder path: the encoder blit takes the frame in the same
    // orientation the preview shows it (ExportOrientationPolicy.FLIP_Y_FOR_ENCODER), with deterministic masks.
    return renderer.render(
      frame = composedFrame(clip),
      mainTextureId = oesTexture,
      isMainOes = true,
      mainTexMatrix = texMatrix,
      viewportWidth = W,
      viewportHeight = H,
      timelineAdjustments = VideoAdjustments(),
      timelineFilter = FilterSettings(),
      chromaKey = chromaKey,
      flipYForEncoder = ExportOrientationPolicy.FLIP_Y_FOR_ENCODER,
      deterministicMasks = true
    )
  }

  /** RGB of the pixel block at the centre of framebuffer 0 (where the export encoder reads). */
  private fun centerRgb(): Triple<Int, Int, Int> {
    val px = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
    GLES20.glReadPixels(W / 2, H / 2, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px)
    assertEquals("glReadPixels failed", GLES20.GL_NO_ERROR, GLES20.glGetError())
    return Triple(px.get(0).toInt() and 0xFF, px.get(1).toInt() and 0xFF, px.get(2).toInt() and 0xFF)
  }

  private fun assertNear(label: String, expected: Int, actual: Triple<Int, Int, Int>) {
    val (r, g, b) = actual
    val ok = abs(r - Color.red(expected)) <= TOLERANCE &&
      abs(g - Color.green(expected)) <= TOLERANCE &&
      abs(b - Color.blue(expected)) <= TOLERANCE
    LookVerificationReport.line("$label expected=(${Color.red(expected)},${Color.green(expected)},${Color.blue(expected)}) actual=($r,$g,$b) ok=$ok")
    assertTrue("$label: expected ~(${Color.red(expected)},${Color.green(expected)},${Color.blue(expected)}), got ($r,$g,$b)", ok)
  }

  // ------------------------------------------------------------------ tests

  @Test
  fun oesFrameWithoutChromaKeyRendersWithoutGlErrors() {
    val texMatrix = latchOesFrame(SOURCE_COLOR)

    val report = render(ChromaKeySettings(enabled = false), texMatrix)
    LookVerificationReport.line("no-key report: ${report.describe()}")

    // The regression: before the fix this reported glErrors=[0x502] (GL_INVALID_OPERATION from
    // the mixed-sampler-type program) and the pixels below came back black.
    assertTrue(
      "renderer raised GL errors on a plain OES frame: ${report.describe()}",
      report.glErrors.isEmpty()
    )
    assertEquals("exactly the base video layer must draw", 1, report.layersDrawn)
    assertTrue("composited texture missing: ${report.describe()}", report.compositedTexture > 0)

    val rgb = centerRgb()
    assertTrue("frame is black: $rgb — the OES picture never reached the framebuffer", rgb.first + rgb.second + rgb.third > 30)
    assertNear("plain OES frame", SOURCE_COLOR, rgb)
  }

  @Test
  fun oesFrameWithChromaKeyStillComposites() {
    val keyColor = Color.rgb(0, 255, 0)
    val bgColor = Color.rgb(16, 32, 220)
    val texMatrix = latchOesFrame(keyColor)

    val report = render(
      ChromaKeySettings(
        enabled = true,
        targetColor = 0xFF00FF00,
        backgroundType = "SolidColor",
        backgroundColor = 0xFF1020DC
      ),
      texMatrix
    )
    LookVerificationReport.line("keyed report: ${report.describe()}")

    assertTrue("renderer raised GL errors on a keyed frame: ${report.describe()}", report.glErrors.isEmpty())
    assertEquals(1, report.layersDrawn)

    // The whole source is the key colour, so the solid background must show through.
    assertNear("keyed background", bgColor, centerRgb())
  }
}
