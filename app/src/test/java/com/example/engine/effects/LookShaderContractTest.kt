package com.example.engine.effects

import com.example.engine.composition.gpu.GpuShaders
import com.example.engine.effects.media3.ColorGradingGlEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drift guard for the two GLSL implementations of the same look.
 *
 * The preview runs [ColorGradingGlEffect.COLOR_GRADING_FRAGMENT_SHADER] inside Media3; the
 * export runs `GpuShaders.buildFragmentShader(...)` inside `GpuCompositionRenderer`. They are
 * separate strings (the export one additionally does chroma key, masks, transitions and blend
 * modes), but every stage they share must use the same constants.
 *
 * Before the fix this file would not have compiled into a green test: export used Rec.601
 * luma with 0.0/0.5 and 0.5/1.0 highlight/shadow masks, a 4.0x sharpen gain, a different
 * grain amplitude and an unclamped vignette coordinate.
 *
 * These are pure string assertions — no Robolectric, no GPU, no Android framework is needed.
 */
class LookShaderContractTest {

  private val preview = ColorGradingGlEffect.COLOR_GRADING_FRAGMENT_SHADER
  private val export = GpuShaders.buildFragmentShader(false)

  /** Collapse whitespace and strip `//` comments so formatting is not counted as drift. */
  private fun norm(s: String): String = s.lineSequence()
    .map { it.substringBefore("//") }
    .joinToString(" ")
    .replace(Regex("""\s+"""), " ")

  private fun has(shader: String, pattern: String) = Regex(pattern).containsMatchIn(norm(shader))

  /**
   * Float literals of one `if (uX ...) { ... }` block, taken from the raw shader with comments
   * removed. Throws when the block is missing so a vacuous `null == null` can never pass.
   */
  private fun block(shader: String, header: String): List<String> {
    val i = shader.indexOf(header)
    assertTrue("stage block not found: $header", i >= 0)
    val start = shader.indexOf('{', i)
    var depth = 0
    for (j in start until shader.length) {
      when (shader[j]) {
        '{' -> depth++
        '}' -> {
          depth--
          if (depth == 0) {
            return Regex("""-?\d+\.?\d*""")
              .findAll(shader.substring(start, j + 1).replace(Regex("""//[^\n]*"""), ""))
              .map { it.value }.toList()
          }
        }
      }
    }
    throw AssertionError("unterminated block: $header")
  }

  @Test
  fun bothShadersDeclareTheSameLookUniforms() {
    val uniforms = listOf(
      "uBrightness", "uContrast", "uSaturation", "uExposure", "uTemperature", "uTint",
      "uHighlights", "uShadows", "uWhites", "uBlacks", "uFade", "uAutoEnhance",
      "uHdrBoost", "uColorFix", "uDenoise", "uGlow", "uVignette", "uGrain",
      "uGrainSeed", "uSharpness", "uClarity", "uTexelSize", "uColorMatrix",
      "uColorOffset", "uUseColorMatrix"
    )
    for (u in uniforms) {
      assertTrue("$u must be declared by the preview shader",
        Regex("""uniform\s+\w+\s+$u\s*;""").containsMatchIn(preview))
      assertTrue("$u must be declared by the export shader",
        Regex("""uniform\s+\w+\s+$u\s*;""").containsMatchIn(export))
    }
  }

  @Test
  fun exposureUsesTheSamePhotographicStopScale() {
    assertTrue(has(preview, """pow\(2\.0, uExposure\)"""))
    assertTrue(has(export, """pow\(2\.0, uExposure\)"""))
  }

  @Test
  fun contrastPivotsOnMidGreyAndSaturationUsesRec709LumaInBoth() {
    assertTrue(has(preview, """\(color\.rgb - 0\.5\) \* uContrast \+ 0\.5"""))
    assertTrue(has(export, """\(color\.rgb - 0\.5\) \* uContrast \+ 0\.5"""))
    assertTrue(has(preview, """mix\(vec3\(lum\), color\.rgb, uSaturation\)"""))
    assertTrue(has(export, """mix\(vec3\(luminance\), color\.rgb, uSaturation\)"""))
    // Preview guards the two neutral-identity stages; export applies them unconditionally.
    // Mathematically identical at neutral, so it is a documented difference, not drift.
    assertTrue(has(preview, """if \(abs\(uContrast - 1\.0\) > 0\.001\)"""))
    assertTrue(has(preview, """if \(abs\(uSaturation - 1\.0\) > 0\.001\)"""))
    assertFalse(has(export, """if \(abs\(uContrast"""))
  }

  @Test
  fun highlightsAndShadowsUseRec709LumaAndTheSameMasks() {
    fun lumaWeights(shader: String, lumaVar: String): List<String> {
      val m = Regex(
        """dot\(color\.rgb, vec3\(([^)]*)\)\)[^;]*;\s*""" +
          """float \w+ = 1\.0 - smoothstep\([^)]*, $lumaVar\);"""
      ).find(norm(shader))
      assertTrue("luma weights not found in stage", m != null)
      return m!!.groupValues[1].split(",").map { it.trim() }
    }

    fun edges(shader: String, lumaVar: String): List<String> {
      val m = Regex(
        """float \w+ = 1\.0 - smoothstep\(([\d.]+), ([\d.]+), $lumaVar\);\s*""" +
          """float \w+ = smoothstep\(([\d.]+), ([\d.]+), $lumaVar\);"""
      ).find(norm(shader))
      assertTrue("highlight/shadow mask edges not found", m != null)
      return m!!.groupValues.drop(1)
    }

    assertEquals(ColorLookModel.LUMA_R, 0.2126f, 0f)
    assertEquals(ColorLookModel.LUMA_G, 0.7152f, 0f)
    assertEquals(ColorLookModel.LUMA_B, 0.0722f, 0f)
    assertEquals("preview luma weights", listOf("0.2126", "0.7152", "0.0722"), lumaWeights(preview, "lum"))
    assertEquals("export luma weights", listOf("0.2126", "0.7152", "0.0722"), lumaWeights(export, "l"))
    assertEquals("preview mask edges", listOf("0.0", "0.6", "0.4", "1.0"), edges(preview, "lum"))
    assertEquals("export mask edges", listOf("0.0", "0.6", "0.4", "1.0"), edges(export, "l"))
    assertEquals(ColorLookModel.SHADOW_EDGE_LOW, 0.0f, 0f)
    assertEquals(ColorLookModel.SHADOW_EDGE_HIGH, 0.6f, 0f)
    assertEquals(ColorLookModel.HIGHLIGHT_EDGE_LOW, 0.4f, 0f)
    assertEquals(ColorLookModel.HIGHLIGHT_EDGE_HIGH, 1.0f, 0f)
  }

  @Test
  fun whitesAndBlacksUseTheSameEdgesAndGain() {
    // The two shaders use different guards (preview: epsilon, export: != 0.0) but the body,
    // which is what actually changes pixels, must be identical.
    assertEquals(
      block(preview, "if (abs(uWhites) > 0.001 || abs(uBlacks) > 0.001) {"),
      block(export, "if (uWhites != 0.0 || uBlacks != 0.0) {")
    )
    assertEquals(ColorLookModel.WHITE_EDGE_LOW, 0.65f, 0f)
    assertEquals(ColorLookModel.WHITE_EDGE_HIGH, 1.0f, 0f)
    assertEquals(ColorLookModel.BLACK_EDGE_LOW, 0.0f, 0f)
    assertEquals(ColorLookModel.BLACK_EDGE_HIGH, 0.35f, 0f)
    assertEquals(ColorLookModel.WHITE_BLACK_GAIN, 0.18f, 0f)
  }

  @Test
  fun sharedVideoQualityStagesUseIdenticalConstants() {
    val headers = listOf(
      "if (uAutoEnhance > 0.01) {",
      "if (uHdrBoost > 0.01) {",
      "if (uColorFix > 0.01) {",
      "if (uFade > 0.01) {",
      "if (uDenoise > 0.01) {",
      "if (uGlow > 0.01) {",
      "if (uClarity > 0.01) {",
      "if (uGrain > 0.01) {"
    )
    for (h in headers) {
      val p = block(preview, h)
      val e = block(export, h)
      assertEquals("stage $h must use the same numbers in preview and export", p, e)
      assertTrue("stage $h must not be empty", p.isNotEmpty())
    }
  }

  @Test
  fun sharpeningUsesTheSameGainInBothShaders() {
    val gain = Regex("""\(uSharpness \* ([\d.]+)\)""")
    val p = gain.findAll(norm(preview)).map { it.groupValues[1] }.toList()
    val e = gain.findAll(norm(export)).map { it.groupValues[1] }.toList()
    assertEquals(listOf(ColorLookModel.SHARPEN_GAIN.toString()), p)
    assertEquals(listOf(ColorLookModel.SHARPEN_GAIN.toString()), e)
    assertTrue(has(preview, """\* 0\.25"""))
    assertTrue(has(export, """\* 0\.25"""))
    // The old export form magnified the Laplacian by 4.0, i.e. 2.67x the preview gain.
    assertFalse("export must not use the 4.0 Laplacian form", has(export, """- 4\.0 \* c"""))
  }

  @Test
  fun grainAmplitudeAndSeedMatch() {
    assertEquals(ColorLookModel.GRAIN_AMPLITUDE, 0.25f, 0f)
    assertTrue("0.25" in block(preview, "if (uGrain > 0.01) {"))
    assertTrue("0.25" in block(export, "if (uGrain > 0.01) {"))
    assertTrue(has(preview, """rand\(uv \+ vec2\(uGrainSeed \* 0\.13, uGrainSeed \* 0\.17\)\)"""))
    assertTrue(has(export, """rand\(clampedCoord \+ vec2\(uGrainSeed \* 0\.13, uGrainSeed \* 0\.17\)\)"""))
  }

  @Test
  fun vignetteUsesTheClampedCoordinateInBothShaders() {
    assertTrue(has(preview, """vec2 uv = clamp\(vTexSamplingCoord, 0\.0, 1\.0\);"""))
    assertTrue(has(export, """vec2 clampedCoord = clamp\(vTextureCoord, 0\.0, 1\.0\);"""))
    val pv = Regex("""vec2 coord = \((\w+) - 0\.5\) \* 2\.0;""").find(norm(preview))
    val ev = Regex("""vec2 coord = \(([^)]+) - 0\.5\) \* 2\.0;""").find(norm(export))
    assertTrue("preview vignette stage not found", pv != null)
    assertTrue("export vignette stage not found", ev != null)
    assertEquals("uv", pv!!.groupValues[1])
    assertEquals("clampedCoord", ev!!.groupValues[1])
  }

  @Test
  fun bothShadersClampAndWriteOpaqueAlpha() {
    assertTrue(has(preview, """clamp\(color\.rgb, 0\.0, 1\.0\)"""))
    assertTrue(has(export, """clamp\(color\.rgb, 0\.0, 1\.0\)"""))
    // Video textures arrive with A=0; leaving it transparent composites a black frame.
    assertTrue(has(preview, """color\.a = 1\.0;"""))
    assertTrue(has(export, """if \(uPremultiply < 0\.5 && uChromaEnabled == 0\) \{ color\.a = 1\.0; \}"""))
  }

  @Test
  fun sharedStagesRunInTheSameOrderInBothShaders() {
    val markers = listOf(
      "uSharpness > 0.01",
      "uClarity > 0.01",
      "pow(2.0, uExposure)",
      "(color.rgb - 0.5) * uContrast",
      "uSaturation)",
      "uTemperature * 0.15",
      "abs(uHighlights) > 0.001",
      "uWhites * whiteW",
      "uAutoEnhance > 0.01",
      "uGlow > 0.01",
      "uUseColorMatrix == 1",
      "uVignette > 0.01",
      "uGrain > 0.01"
    )

    fun positions(shader: String): List<Int> {
      val n = norm(shader)
      return markers.map {
        val idx = n.indexOf(it)
        assertTrue("stage marker not found: $it", idx >= 0)
        idx
      }
    }

    val p = positions(preview)
    val e = positions(export)
    assertEquals("preview stage order must match ColorLookModel.STAGE_ORDER", p.sorted(), p)
    assertEquals("export stage order must match ColorLookModel.STAGE_ORDER", e.sorted(), e)
  }
}
