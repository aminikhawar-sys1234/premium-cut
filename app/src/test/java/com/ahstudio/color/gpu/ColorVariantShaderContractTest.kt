package com.ahstudio.color.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executable contract for the generated colour-grade shader variants.
 *
 * Regression background: the shader tests most feature flags BY VALUE
 * (`#if HAS_CURVES`, `#if LUT_AFTER && (HAS_LUT3D || HAS_LUT2D)`), but the variant builder used to
 * emit valueless `#define HAS_CURVES` style flags and leave disabled flags undefined. A valueless
 * macro in `#if` is an empty (invalid) expression and an undefined macro in `#if` is an error on
 * strict GLSL ES preprocessors (GLSL ES 3.0 §3.4) — `#if LUT_AFTER && (...)` appears in EVERY
 * variant, so no colour-grade shader ever compiled and ColorGradeStage silently dropped all grades
 * from preview and export.
 *
 * This test preprocesses every variant with a STRICT mini-preprocessor (same rules a conformant
 * driver applies) and then pins the surviving sampler declarations to the texture units
 * ColorGpuProcessor assigns, so a sampler can never be left on unit 0 next to a different-type
 * sampler (the GL_INVALID_OPERATION / black-frame class fixed in GpuCompositionRenderer).
 */
class ColorVariantShaderContractTest {

  // ---- strict mini GLSL preprocessor -------------------------------------------------------

  private class PreprocessError(message: String) : RuntimeException(message)

  /** Evaluates an integer #if expression; every identifier must already be substituted. */
  private fun evalExpr(expr: String): Int {
    val src = expr.trim()
    if (src.isEmpty()) throw PreprocessError("empty #if expression")
    if (Regex("[A-Za-z_]").containsMatchIn(src)) {
      throw PreprocessError("undefined macro in #if expression: '$src'")
    }
    val tokens = Regex("\\d+|&&|\\|\\||[!<>=]=|[-!<>()+]").findAll(src).map { it.value }.toList()
    if (tokens.joinToString("").replace(" ", "") != src.replace(" ", "")) {
      throw PreprocessError("unparseable #if expression: '$src'")
    }
    val parser = ExprParser(tokens, src)
    val result = parser.parseOr()
    if (!parser.atEnd()) throw PreprocessError("trailing tokens in '$src'")
    return result
  }

  private class ExprParser(private val tokens: List<String>, private val src: String) {
    private var pos = 0
    fun atEnd(): Boolean = pos == tokens.size
    private fun peek(): String? = tokens.getOrNull(pos)
    private fun take(): String = tokens[pos++]
    private fun fail(msg: String): Nothing = throw RuntimeException("$msg in '$src'")
    private fun parsePrimary(): Int = when (val t = peek() ?: fail("truncated expression")) {
      "(" -> { take(); val v = parseOr(); if (take() != ")") fail("missing ')'"); v }
      "!" -> { take(); if (parsePrimary() == 0) 1 else 0 }
      "-" -> { take(); -parsePrimary() }
      else -> { take(); t.toIntOrNull() ?: fail("bad token '$t'") }
    }
    private fun parseCmp(): Int {
      var left = parsePrimary()
      while (peek() in setOf(">", "<", ">=", "<=", "==", "!=")) {
        val op = take(); val right = parsePrimary()
        left = if (when (op) {
          ">" -> left > right; "<" -> left < right; ">=" -> left >= right
          "<=" -> left <= right; "==" -> left == right; else -> left != right
        }) 1 else 0
      }
      return left
    }
    private fun parseAnd(): Int {
      var v = parseCmp()
      while (peek() == "&&") { take(); val r = parseCmp(); v = if (v != 0 && r != 0) 1 else 0 }
      return v
    }
    fun parseOr(): Int {
      var v = parseAnd()
      while (peek() == "||") { take(); val r = parseAnd(); v = if (v != 0 || r != 0) 1 else 0 }
      return v
    }
  }

  /** Runs the strict preprocessor; returns the active (surviving) source lines. */
  private fun preprocess(source: String): List<String> {
    val macros = HashMap<String, String>()
    val active = ArrayList<String>()
    // stack of [branch currently active, any branch taken yet, parent active]
    val stack = ArrayDeque<Triple<Boolean, Boolean, Boolean>>()
    fun currentlyActive() = stack.all { it.first && it.third }
    for (raw in source.lines()) {
      val line = raw.trim()
      if (!line.startsWith("#")) {
        if (currentlyActive()) active.add(raw)
        continue
      }
      val directive = line.removePrefix("#").trim()
      when {
        directive.startsWith("version") || directive.startsWith("extension") ||
          directive.startsWith("pragma") -> if (currentlyActive()) active.add(raw)
        directive.startsWith("define") -> {
          if (currentlyActive()) {
            val body = directive.removePrefix("define").trim()
            val name = body.takeWhile { it.isLetterOrDigit() || it == '_' }
            macros[name] = body.removePrefix(name).trim()
          }
        }
        directive.startsWith("ifdef") -> {
          val name = directive.removePrefix("ifdef").trim()
          val on = macros.containsKey(name)
          stack.addLast(Triple(on, on, currentlyActive()))
        }
        directive.startsWith("ifndef") -> {
          val name = directive.removePrefix("ifndef").trim()
          val on = !macros.containsKey(name)
          stack.addLast(Triple(on, on, currentlyActive()))
        }
        directive.startsWith("if") -> {
          var expr = directive.removePrefix("if").trim()
          val parentActive = currentlyActive()
          // Evaluate strictly even inside skipped groups: every #if in this shader must be a
          // valid expression over always-defined macros (a surviving identifier is an error).
          for ((k, v) in macros) expr = Regex("\\b${Regex.escape(k)}\\b").replace(expr, v)
          val on = evalExpr(expr) != 0
          stack.addLast(Triple(on, on, parentActive))
        }
        directive.startsWith("else") -> {
          val top = stack.removeLast()
          stack.addLast(Triple(!top.second, true, top.third))
        }
        directive.startsWith("endif") -> stack.removeLast()
        else -> throw PreprocessError("unknown directive: '$line'")
      }
    }
    if (stack.isNotEmpty()) throw PreprocessError("unterminated #if/#ifdef")
    return active
  }

  // ---- variant sweep -----------------------------------------------------------------------

  private data class Variant(
    val externalTex: Boolean, val hasCurves: Boolean, val hasHsl: Boolean, val hasWheels: Boolean,
    val hasLut3d: Boolean, val hasLut2d: Boolean, val hasSplit: Boolean, val hasBw: Boolean,
    val secCount: Int, val secMask: Boolean, val lutBefore: Boolean,
    val rangeExpand: Boolean, val hdrOutput: Boolean
  )

  private fun allVariants(): List<Variant> {
    val bools = listOf(false, true)
    val out = ArrayList<Variant>()
    for (ext in bools) for (curves in bools) for (hsl in bools) for (wheels in bools)
      for (lut in listOf("none", "3d", "2d")) for (split in bools) for (bw in bools)
        for (sec in 0..2) for (mask in bools) for (before in bools)
          for (range in bools) for (hdr in bools) {
            if (mask && sec == 0) continue // builder never emits this combination
            out.add(Variant(ext, curves, hsl, wheels, lut == "3d", lut == "2d",
              split, bw, sec, mask, before, range, hdr))
          }
    return out
  }

  private fun fragmentOf(v: Variant): String = ColorShaderSource.fragment(
    ColorShaderSource.variantDefines(
      externalTex = v.externalTex, hasCurves = v.hasCurves, hasHsl = v.hasHsl,
      hasWheels = v.hasWheels, hasLut3d = v.hasLut3d, hasLut2d = v.hasLut2d,
      hasSplit = v.hasSplit, hasBw = v.hasBw, secCount = v.secCount, secMask = v.secMask,
      lutBefore = v.lutBefore, rangeExpand = v.rangeExpand, hdrOutput = v.hdrOutput
    )
  )

  private val samplerDecl = Regex("uniform\\s+(samplerExternalOES|sampler2D|sampler3D)\\s+(\\w+)")

  // ---- tests --------------------------------------------------------------------------------

  @Test
  fun everyVariantSurvivesAStrictPreprocessor() {
    val variants = allVariants()
    assertTrue("variant sweep must not be empty", variants.isNotEmpty())
    for (v in variants) {
      try {
        preprocess(fragmentOf(v))
      } catch (e: PreprocessError) {
        throw AssertionError("variant $v fails strict preprocessing: ${e.message}")
      }
    }
  }

  @Test
  fun survivingSamplersMatchThePinnedUnitContract() {
    // Units ColorGpuProcessor assigns on EVERY draw. A sampler declared by the variant but absent
    // here would default to unit 0 and conflict with a different-type uInput (GL_INVALID_OPERATION,
    // dropped draw, black frame).
    val pinnedUnits = mapOf(
      "uInput" to 0, "uCurveTex" to 1, "uLumaCurveTex" to 2,
      "uLut3d" to 3, "uLut2d" to 3, "uSecMask" to 4
    )
    for (v in allVariants()) {
      val declared = samplerDecl.findAll(preprocess(fragmentOf(v)).joinToString("\n"))
        .map { it.groupValues[2].substringBefore("[") }.toSet()
      val expected = buildSet {
        add("uInput")
        if (v.hasCurves) { add("uCurveTex"); add("uLumaCurveTex") }
        if (v.hasLut3d) add("uLut3d")
        if (v.hasLut2d) add("uLut2d")
        if (v.secCount > 0 && v.secMask) add("uSecMask")
      }
      assertEquals("sampler set for $v", expected, declared)
      declared.forEach {
        assertTrue("sampler $it has no pinned texture unit in ColorGpuProcessor", pinnedUnits.containsKey(it))
      }
    }
  }

  @Test
  fun oesVariantDeclaresTheExternalSamplerAndExtensionFirst() {
    val src = fragmentOf(allVariants().first { it.externalTex })
    val lines = src.lines()
    val extIdx = lines.indexOfFirst { it.trim().startsWith("#extension GL_OES_EGL_image_external_essl3") }
    assertTrue("OES variant must request the external-image extension", extIdx >= 0)
    val firstToken = lines.indexOfFirst { l ->
      val t = l.trim(); t.isNotEmpty() && !t.startsWith("#") && !t.startsWith("//")
    }
    assertTrue(
      "#extension (line $extIdx) must precede every non-preprocessor token (first at line $firstToken)",
      firstToken == -1 || extIdx < firstToken
    )
    val active = preprocess(src).joinToString("\n")
    assertTrue("OES variant samples uInput as samplerExternalOES",
      Regex("uniform\\s+samplerExternalOES\\s+uInput").containsMatchIn(active))
    assertTrue("OES variant must not also declare a 2D uInput",
      !Regex("uniform\\s+sampler2D\\s+uInput\\b").containsMatchIn(active))
  }

  @Test
  fun valueTestedFlagsAreAlwaysDefinedWithAValue() {
    val defines = ColorShaderSource.variantDefines(
      externalTex = false, hasCurves = false, hasHsl = false, hasWheels = false,
      hasLut3d = false, hasLut2d = false, hasSplit = false, hasBw = false,
      secCount = 0, secMask = false, lutBefore = false, rangeExpand = false, hdrOutput = false
    )
    for (flag in listOf("HAS_CURVES", "HAS_HSL", "HAS_WHEELS", "HAS_LUT3D", "HAS_LUT2D",
      "HAS_SPLIT", "HAS_BW", "SEC_COUNT", "SEC_HAS_MASK", "LUT_BEFORE", "LUT_AFTER", "RANGE_EXPAND")) {
      assertTrue("flag $flag must be defined with an explicit value in every variant",
        Regex("#define $flag -?\\d+\\n").containsMatchIn(defines))
    }
    assertTrue("TEXTURE_EXTERNAL is presence-tested and must be absent when off",
      !defines.contains("TEXTURE_EXTERNAL"))
    assertTrue("HDR_OUTPUT is presence-tested and must be absent when off",
      !defines.contains("HDR_OUTPUT"))
  }
}
