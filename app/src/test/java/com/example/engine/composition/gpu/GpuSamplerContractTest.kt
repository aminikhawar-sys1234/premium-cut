package com.example.engine.composition.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drift guard for the GLES sampler layout of the composition shaders.
 *
 * The export's OES program (`GpuShaders.buildFragmentShader(isOes = true)`) contains two samplers
 * of DIFFERENT types: `samplerExternalOES uTexture` and `sampler2D uChromaBgTex`. The GLES 2.0
 * (§2.10.5) / 3.0 (§2.11.9) specifications reject any draw while two samplers of different types
 * reference the same texture unit — the draw raises GL_INVALID_OPERATION (0x502) and writes no
 * fragments. Because fresh programs start every sampler uniform at unit 0, the renderer MUST
 * assign `uChromaBgTex` its own unit on every draw (see
 * `GpuCompositionRenderer.bindChromaBackgroundSampler`), not only when the chroma key is enabled.
 * Leaving the assignment conditional is exactly the bug that produced fully black exports
 * ("layers=1 ... compositor=kotlin glErrors=[0x502]") on spec-enforcing drivers.
 *
 * These assertions document and pin the sampler sets of both shader variants so a new sampler
 * cannot be added without revisiting the unit assignments. Pure string checks — no GPU needed;
 * the draw-time behaviour itself is covered by `OesSamplerRegressionInstrumentedTest` on a real
 * GLES driver.
 */
class GpuSamplerContractTest {

  private val oesShader = GpuShaders.buildFragmentShader(isOes = true)
  private val texShader = GpuShaders.buildFragmentShader(isOes = false)

  private fun samplers(shader: String): Map<String, String> =
    Regex("""uniform\s+(samplerExternalOES|sampler2D)\s+(\w+)\s*;""")
      .findAll(shader)
      .associate { it.groupValues[2] to it.groupValues[1] }

  @Test
  fun oesProgramMixesExactlyTheTwoKnownSamplerTypes() {
    val samplers = samplers(oesShader)
    assertEquals("samplerExternalOES", samplers["uTexture"])
    assertEquals("sampler2D", samplers["uChromaBgTex"])
    // Pin the full set: any NEW sampler added to this program needs its own texture unit assigned
    // unconditionally in GpuCompositionRenderer.bindCommonUniforms, or every draw fails with
    // GL_INVALID_OPERATION on spec-enforcing drivers. Grow this list only together with that code.
    assertEquals(setOf("uTexture", "uChromaBgTex"), samplers.keys)
  }

  @Test
  fun twoDProgramHasNoExternalSampler() {
    val samplers = samplers(texShader)
    assertEquals("sampler2D", samplers["uTexture"])
    assertEquals("sampler2D", samplers["uChromaBgTex"])
    assertFalse(texShader.contains("samplerExternalOES"))
  }

  @Test
  fun oesProgramDeclaresTheExternalImageExtension() {
    assertTrue(oesShader.contains("GL_OES_EGL_image_external"))
  }
}
