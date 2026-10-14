package com.example.engine.effects

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.domain.model.FilterSettings
import com.example.domain.model.FilterType
import com.example.domain.model.VideoAdjustments
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.max

/**
 * Device-level (real GPU) verification of the Filters / Adjust / Video Quality pipeline.
 *
 * Every check here runs the **actual** shaders the app ships — the Media3 preview shader and the
 * `GpuShaders` export shader — on a real GLES driver, with the uniforms resolved by
 * [ColorLookModel], which is the one function both production paths call. Pixels are read back
 * with `glReadPixels`, so a stage that silently does nothing (the original bug) cannot pass.
 *
 * Measurements are appended to `look-verification.txt` in the app's external files dir; the
 * verification workflow pulls that file and posts it into the pull request.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class ColorPipelineGpuInstrumentedTest {

  private var harness: LookGpuHarness? = null

  private val probes = listOf(
    VideoAdjustments(brightness = 0.35f),
    VideoAdjustments(contrast = 1.45f),
    VideoAdjustments(saturation = 1.60f),
    VideoAdjustments(exposure = 0.40f),
    VideoAdjustments(temperature = 0.50f),
    VideoAdjustments(tint = -0.40f),
    VideoAdjustments(highlights = 0.50f),
    VideoAdjustments(shadows = 0.50f),
    VideoAdjustments(whites = 0.45f),
    VideoAdjustments(blacks = -0.35f),
    VideoAdjustments(fade = 0.50f),
    VideoAdjustments(autoEnhance = 0.60f),
    VideoAdjustments(hdrBoost = 0.70f),
    VideoAdjustments(colorCorrect = 0.70f),
    VideoAdjustments(colorFix = 0.80f),
    VideoAdjustments(sharpness = 0.80f),
    VideoAdjustments(clarity = 0.60f),
    VideoAdjustments(denoise = 0.60f),
    VideoAdjustments(antiFlicker = 0.80f),
    VideoAdjustments(vignette = 0.60f),
    VideoAdjustments(grain = 0.50f)
  )

  @Before
  fun setUp() {
    AndroidTestContextHolder.context = InstrumentationRegistry.getInstrumentation().targetContext
    try {
      harness = LookGpuHarness()
    } catch (t: Throwable) {
      // No usable EGL/GLES surface on this device: report it instead of a misleading pass.
      LookVerificationReport.section("GPU harness")
      LookVerificationReport.line("EGL/GLES unavailable: ${t.javaClass.simpleName}: ${t.message}")
      assumeNoException(t)
    }
    LookVerificationReport.section("GPU harness (real device GL)")
    LookVerificationReport.line("stamp=${LookVerificationReport.stamp()}")
    LookVerificationReport.line("device GL_VERSION/RENDERER = ${LookGpuHarness.glVersionString()}")
    LookVerificationReport.line("egl context glVersion=${harness!!.core.glVersion}")
  }

  @After
  fun tearDown() {
    harness?.close()
    harness = null
    AndroidTestContextHolder.context
      ?.let { LookVerificationReport.file(it) }
      ?.let { LookVerificationReport.line("report written to ${it.absolutePath} (${it.length()} bytes)") }
  }

  private fun gl(): LookGpuHarness = harness!!

  private fun meanRgb(frame: FloatArray): FloatArray {
    val sums = FloatArray(3)
    var i = 0
    while (i < frame.size) {
      sums[0] += frame[i]
      sums[1] += frame[i + 1]
      sums[2] += frame[i + 2]
      i += 3
    }
    val pixels = (frame.size / 3).toFloat()
    return floatArrayOf(sums[0] / pixels, sums[1] / pixels, sums[2] / pixels)
  }

  private fun meanAbsDiff(a: FloatArray, b: FloatArray): Float {
    var total = 0f
    for (i in a.indices) total += abs(a[i] - b[i])
    return total / a.size
  }

  private fun maxAbsDiff(a: FloatArray, b: FloatArray): Float {
    var m = 0f
    for (i in a.indices) m = max(m, abs(a[i] - b[i]))
    return m
  }

  private fun luma(frame: FloatArray): Float {
    var sum = 0f
    var i = 0
    while (i < frame.size) {
      sum += 0.2126f * frame[i] + 0.7152f * frame[i + 1] + 0.0722f * frame[i + 2]
      i += 3
    }
    return sum / (frame.size / 3).toFloat()
  }

  /** The look the export renderer resolves for a clip (same call `bindCommonUniforms` makes). */
  private fun exportLook(adj: VideoAdjustments, filter: FilterSettings): ColorLookModel.LookParams {
    val pair = ColorLookModel.presetMatrix(filter)?.let { ColorLookModel.filterGlMatrix(it) }
    return ColorLookModel.effective(adj, filter, pair?.first, pair?.second)
  }

  // -------------------------------------------------------------------------------------
  // 1. Shader compilation — the claim "the GLSL compiles" checked by a real GLES driver
  // -------------------------------------------------------------------------------------

  @Test
  fun previewAndExportShadersCompileOnThisDevice() {
    val preview = gl().buildPreviewProgram()
    val export = gl().buildExportProgram()
    LookVerificationReport.line(
      "preview shader compile ok=${preview.ok} vertexLog='${preview.vertexLog.trim()}' " +
        "fragmentLog='${preview.fragmentLog.trim()}' linkLog='${preview.linkLog.trim()}'"
    )
    LookVerificationReport.line(
      "export shader compile ok=${export.ok} vertexLog='${export.vertexLog.trim()}' " +
        "fragmentLog='${export.fragmentLog.trim()}' linkLog='${export.linkLog.trim()}'"
    )
    try {
      assertTrue("preview shader failed to build: ${preview.fragmentLog}", preview.ok)
      assertTrue("export shader failed to build: ${export.fragmentLog}", export.ok)
    } finally {
      gl().deleteProgram(preview)
      gl().deleteProgram(export)
    }
  }

  // -------------------------------------------------------------------------------------
  // 2. Neutral look is a pass-through, including the decoder's A=0 textures
  // -------------------------------------------------------------------------------------

  @Test
  fun neutralLookIsAPassThroughOnTheGpu() {
    val export = gl().buildExportProgram()
    val preview = gl().buildPreviewProgram()
    try {
      val neutral = exportLook(VideoAdjustments(), FilterSettings())
      val exportFrame = gl().render(export, neutral, 0f, preview = false)
      val previewFrame = gl().render(preview, neutral, 0f, preview = true)

      val source = FloatArray(gl().size * gl().size * 3)
      var i = 0
      while (i < source.size) {
        source[i] = (gl().sourceRgba[i / 3 * 4].toInt() and 0xFF) / 255f
        source[i + 1] = (gl().sourceRgba[i / 3 * 4 + 1].toInt() and 0xFF) / 255f
        source[i + 2] = (gl().sourceRgba[i / 3 * 4 + 2].toInt() and 0xFF) / 255f
        i += 3
      }

      val exportDiff = meanAbsDiff(exportFrame, source)
      val previewDiff = meanAbsDiff(previewFrame, source)
      LookVerificationReport.line(
        "neutral pass-through: exportMeanDiff=%.5f previewMeanDiff=%.5f (1/255=%.5f)"
          .format(exportDiff, previewDiff, 1f / 255f)
      )
      assertTrue("neutral look changed the export frame (mean diff $exportDiff)", exportDiff <= 2f / 255f)
      assertTrue("neutral look changed the preview frame (mean diff $previewDiff)", previewDiff <= 2f / 255f)
    } finally {
      gl().deleteProgram(export)
      gl().deleteProgram(preview)
    }
  }

  @Test
  fun decoderAlphaZeroDoesNotProduceBlackOrTransparentFrames() {
    val export = gl().buildExportProgram()
    val preview = gl().buildPreviewProgram()
    try {
      val neutral = exportLook(VideoAdjustments(), FilterSettings())
      val exportFrame = gl().render(export, neutral, 0f, preview = false, alpha = 0)
      val exportAlpha = gl().lastAlphaAt(gl().size * gl().size / 2)
      val previewFrame = gl().render(preview, neutral, 0f, preview = true, alpha = 0)
      val previewAlpha = gl().lastAlphaAt(gl().size * gl().size / 2)

      val exportLuma = luma(exportFrame)
      val previewLuma = luma(previewFrame)
      LookVerificationReport.line(
        "A=0 decoder frame: exportLuma=%.4f exportAlpha=%d previewLuma=%.4f previewAlpha=%d"
          .format(exportLuma, exportAlpha, previewLuma, previewAlpha)
      )
      assertTrue("A=0 input rendered a black export frame (luma $exportLuma)", exportLuma > 0.05f)
      assertTrue("A=0 input rendered a black preview frame (luma $previewLuma)", previewLuma > 0.05f)
      assertTrue("export left the frame transparent (alpha $exportAlpha)", exportAlpha == 255)
      assertTrue("preview left the frame transparent (alpha $previewAlpha)", previewAlpha == 255)
    } finally {
      gl().deleteProgram(export)
      gl().deleteProgram(preview)
    }
  }

  // -------------------------------------------------------------------------------------
  // 3. Every slider changes real pixels, and matches the CPU reference
  // -------------------------------------------------------------------------------------

  @Test
  fun everySliderChangesRenderedPixelsOnTheGpu() {
    val export = gl().buildExportProgram()
    try {
      val baseline = gl().render(export, exportLook(VideoAdjustments(), FilterSettings()), 1f, preview = false)
      LookVerificationReport.line(
        "baseline mean rgb = [%.4f, %.4f, %.4f]".format(
          meanRgb(baseline)[0], meanRgb(baseline)[1], meanRgb(baseline)[2]
        )
      )
      val failures = mutableListOf<String>()
      var cpuDiffTotal = 0.0
      var pixels = 0
      for (adj in probes) {
        val params = exportLook(adj, FilterSettings())
        val frame = gl().render(export, params, 1f, preview = false)
        val delta = meanAbsDiff(frame, baseline)
        // CPU reference for the same look: the two must agree pixel for pixel.
        val cpu = FloatArray(gl().size * gl().size * 3)
        var p = 0
        while (p < cpu.size) {
          val s = p / 3 * 4
          val out = ColorLookModel.evaluate(
            params,
            (gl().sourceRgba[s].toInt() and 0xFF) / 255f,
            (gl().sourceRgba[s + 1].toInt() and 0xFF) / 255f,
            (gl().sourceRgba[s + 2].toInt() and 0xFF) / 255f,
            1f
          )
          cpu[p] = out[0]; cpu[p + 1] = out[1]; cpu[p + 2] = out[2]
          p += 3
        }
        val cpuDiff = meanAbsDiff(frame, cpu)
        cpuDiffTotal += cpuDiff
        pixels++
        val name = describe(adj)
        LookVerificationReport.line(
          "$name gpuDelta=%.4f gpuVsCpuMeanDiff=%.5f maxPad=%.5f"
            .format(delta, cpuDiff, maxAbsDiff(frame, cpu))
        )
        if (delta <= 1f / 255f) failures += "$name changed nothing on the GPU (delta $delta)"
        if (cpuDiff > 6f / 255f) failures += "$name GPU/CPU mismatch (mean diff $cpuDiff)"
      }
      LookVerificationReport.line(
        "sliders checked=${probes.size} meanGpuVsCpuDiff=%.5f".format(cpuDiffTotal / max(1, pixels))
      )
      assertTrue(failures.joinToString("\n"), failures.isEmpty())
    } finally {
      gl().deleteProgram(export)
    }
  }

  private fun describe(adj: VideoAdjustments): String = buildString {
    if (adj.brightness != 0f) append("brightness=${adj.brightness} ")
    if (adj.contrast != 1f) append("contrast=${adj.contrast} ")
    if (adj.saturation != 1f) append("saturation=${adj.saturation} ")
    if (adj.exposure != 0f) append("exposure=${adj.exposure} ")
    if (adj.temperature != 0f) append("temperature=${adj.temperature} ")
    if (adj.tint != 0f) append("tint=${adj.tint} ")
    if (adj.highlights != 0f) append("highlights=${adj.highlights} ")
    if (adj.shadows != 0f) append("shadows=${adj.shadows} ")
    if (adj.whites != 0f) append("whites=${adj.whites} ")
    if (adj.blacks != 0f) append("blacks=${adj.blacks} ")
    if (adj.fade != 0f) append("fade=${adj.fade} ")
    if (adj.autoEnhance != 0f) append("autoEnhance=${adj.autoEnhance} ")
    if (adj.hdrBoost != 0f) append("hdrBoost=${adj.hdrBoost} ")
    if (adj.colorCorrect != 0f) append("colorCorrect=${adj.colorCorrect} ")
    if (adj.colorFix != 0f) append("colorFix=${adj.colorFix} ")
    if (adj.sharpness != 0f) append("sharpness=${adj.sharpness} ")
    if (adj.clarity != 0f) append("clarity=${adj.clarity} ")
    if (adj.denoise != 0f) append("denoise=${adj.denoise} ")
    if (adj.antiFlicker != 0f) append("antiFlicker=${adj.antiFlicker} ")
    if (adj.vignette != 0f) append("vignette=${adj.vignette} ")
    if (adj.grain != 0f) append("grain=${adj.grain} ")
  }.trim()

  // -------------------------------------------------------------------------------------
  // 4. Filters: real pixel processing, intensity 0..100, and 0 restores the frame
  // -------------------------------------------------------------------------------------

  @Test
  fun everySelectableFilterChangesPixelsAndZeroIntensityIsNeutral() {
    val export = gl().buildExportProgram()
    try {
      val baseline = gl().render(export, exportLook(VideoAdjustments(), FilterSettings()), 1f, preview = false)
      val failures = mutableListOf<String>()
      for (type in FilterCatalog.SELECTABLE) {
        val full = exportLook(VideoAdjustments(), FilterSettings(type = type, intensity = 1f))
        val frame = gl().render(export, full, 1f, preview = false)
        val delta = meanAbsDiff(frame, baseline)
        val zero = exportLook(VideoAdjustments(), FilterSettings(type = type, intensity = 0f))
        val zeroFrame = gl().render(export, zero, 1f, preview = false)
        val zeroDelta = meanAbsDiff(zeroFrame, baseline)
        LookVerificationReport.line(
          "$type delta@100=%.4f delta@0=%.5f meanRgb=%.4f/%.4f/%.4f".format(
            delta, zeroDelta, meanRgb(frame)[0], meanRgb(frame)[1], meanRgb(frame)[2]
          )
        )
        if (delta <= 1f / 255f) failures += "$type did not change the picture (delta $delta)"
        if (zeroDelta > 2f / 255f) failures += "$type at 0% was not neutral (delta $zeroDelta)"
      }
      assertTrue(failures.joinToString("\n"), failures.isEmpty())
    } finally {
      gl().deleteProgram(export)
    }
  }

  @Test
  fun filterIntensityIsMonotonicOnTheGpu() {
    val export = gl().buildExportProgram()
    try {
      val baseline = gl().render(export, exportLook(VideoAdjustments(), FilterSettings()), 1f, preview = false)
      val failures = mutableListOf<String>()
      for (type in FilterCatalog.SELECTABLE) {
        val deltas = listOf(0f, 0.25f, 0.5f, 1f).map { intensity ->
          val look = exportLook(VideoAdjustments(), FilterSettings(type = type, intensity = intensity))
          meanAbsDiff(gl().render(export, look, 1f, preview = false), baseline)
        }
        LookVerificationReport.line(
          "$type intensity deltas 0=%.5f 25=%.4f 50=%.4f 100=%.4f".format(
            deltas[0], deltas[1], deltas[2], deltas[3]
          )
        )
        if (deltas[0] > 2f / 255f) failures += "$type at 0% was not neutral (${deltas[0]})"
        if (!(deltas[1] <= deltas[2] && deltas[2] <= deltas[3])) {
          failures += "$type intensity was not monotonic: $deltas"
        }
      }
      assertTrue(failures.joinToString("\n"), failures.isEmpty())
    } finally {
      gl().deleteProgram(export)
    }
  }

  // -------------------------------------------------------------------------------------
  // 5. Preview shader vs export shader on the GPU (the pixel-level parity check)
  // -------------------------------------------------------------------------------------

  @Test
  fun previewAndExportShadersProduceTheSameFrameOnTheGpu() {
    val export = gl().buildExportProgram()
    val preview = gl().buildPreviewProgram()
    try {
      val cases = listOf(
        "neutral" to exportLook(VideoAdjustments(), FilterSettings()),
        "adjust" to exportLook(
          VideoAdjustments(brightness = 0.30f, contrast = 1.30f, saturation = 1.25f, exposure = 0.25f),
          FilterSettings()
        ),
        "tone" to exportLook(
          VideoAdjustments(highlights = 0.40f, shadows = 0.50f, whites = 0.30f, blacks = -0.25f),
          FilterSettings()
        ),
        "spatial" to exportLook(
          VideoAdjustments(sharpness = 0.80f, clarity = 0.60f, vignette = 0.60f, grain = 0.40f),
          FilterSettings()
        ),
        "golden" to exportLook(VideoAdjustments(), FilterSettings(FilterType.GOLDEN_AUTUMN, 1f)),
        "hdr" to exportLook(VideoAdjustments(), FilterSettings(FilterType.HDR, 1f)),
        "glow" to exportLook(VideoAdjustments(), FilterSettings(FilterType.GLOW, 1f)),
        "everything" to exportLook(
          VideoAdjustments(
            brightness = 0.15f, contrast = 1.20f, saturation = 1.15f, temperature = 0.30f,
            highlights = 0.25f, shadows = 0.25f, sharpness = 0.50f, clarity = 0.40f,
            vignette = 0.45f, grain = 0.30f, autoEnhance = 0.40f
          ),
          FilterSettings(FilterType.ENHANCE, 0.75f)
        )
      )
      val failures = mutableListOf<String>()
      var worst = 0f
      for ((name, look) in cases) {
        val a = gl().render(preview, look, 1.25f, preview = true)
        val b = gl().render(export, look, 1.25f, preview = false)
        val mean = meanAbsDiff(a, b)
        val maxDiff = maxAbsDiff(a, b)
        worst = max(worst, mean)
        LookVerificationReport.line(
          "parity[$name] previewVsExport meanDiff=%.5f maxDiff=%.5f".format(mean, maxDiff)
        )
        if (mean > 4f / 255f || maxDiff > 12f / 255f) {
          failures += "$name preview/export mismatch: mean=$mean max=$maxDiff"
        }
      }
      LookVerificationReport.line("worst preview/export mean diff = %.5f".format(worst))
      assertTrue(failures.joinToString("\n"), failures.isEmpty())
    } finally {
      gl().deleteProgram(export)
      gl().deleteProgram(preview)
    }
  }

  // -------------------------------------------------------------------------------------
  // 6. Repeated filter changes, reset and determinism (no black screen, no residue)
  // -------------------------------------------------------------------------------------

  @Test
  fun repeatedFilterChangesResetAndRepaintStayDeterministic() {
    val export = gl().buildExportProgram()
    try {
      val baseline = gl().render(export, exportLook(VideoAdjustments(), FilterSettings()), 1f, preview = false)
      val firstByType = mutableMapOf<FilterType, FloatArray>()
      val failures = mutableListOf<String>()
      var darkest = 1f
      repeat(6) { round ->
        for (type in FilterCatalog.SELECTABLE) {
          val look = exportLook(VideoAdjustments(), FilterSettings(type = type, intensity = 1f))
          val frame = gl().render(export, look, 1f, preview = false)
          val first = firstByType.getOrPut(type) { frame }
          val drift = meanAbsDiff(frame, first)
          darkest = minOf(darkest, luma(frame))
          if (drift > 1f / 255f) failures += "$type drifted by $drift in round $round"
          if (luma(frame) < 0.02f) failures += "$type blacked the frame out in round $round"
        }
        // Reset back to the neutral look: must return to the untouched baseline.
        val reset = gl().render(export, exportLook(VideoAdjustments(), FilterSettings()), 1f, preview = false)
        val resetDelta = meanAbsDiff(reset, baseline)
        if (resetDelta > 2f / 255f) failures += "reset left residue in round $round (delta $resetDelta)"
      }
      LookVerificationReport.line(
        "repeated filter changes: rounds=6 types=${FilterCatalog.SELECTABLE.size} " +
          "darkestFrameLuma=%.4f".format(darkest)
      )
      assertTrue(failures.joinToString("\n"), failures.isEmpty())
    } finally {
      gl().deleteProgram(export)
    }
  }

  // -------------------------------------------------------------------------------------
  // 7. Two live EGL contexts (preview + export) can render at the same time
  // -------------------------------------------------------------------------------------

  @Test
  fun releasingOneGlContextDoesNotKillTheOther() {
    // EglCore used to call eglTerminate() on the shared default display, so releasing the
    // preview compositor's context left the export renderer with a dead display.
    val first = LookGpuHarness()
    val second = LookGpuHarness()
    try {
      val programA = first.buildExportProgram()
      val programB = second.buildExportProgram()
      assertTrue("first context failed to build the export program", programA.ok)
      assertTrue("second context failed to build the export program", programB.ok)
      val neutral = exportLook(VideoAdjustments(), FilterSettings())
      val beforeA = luma(first.render(programA, neutral, 0f, preview = false))
      val beforeB = luma(second.render(programB, neutral, 0f, preview = false))

      first.close()

      val afterB = luma(second.render(programB, neutral, 0f, preview = false))
      LookVerificationReport.line(
        "two contexts: lumaA=%.4f lumaB(before)=%.4f lumaB(after releasing A)=%.4f".format(
          beforeA, beforeB, afterB
        )
      )
      assertTrue("the surviving context produced a black frame after the other was released", afterB > 0.05f)
      assertTrue("the surviving context changed after the other was released", abs(afterB - beforeB) < 1f / 255f)
    } finally {
      runCatching { second.close() }
    }
  }
}
