package com.example.engine.effects.media3

import android.content.Context
import android.graphics.ColorMatrix
import androidx.annotation.OptIn
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.example.domain.model.FilterSettings
import com.example.domain.model.FilterType
import com.example.domain.model.VideoAdjustments
import com.example.engine.composition.ColorFilterGenerator
import com.example.engine.effects.FilterCatalog
import kotlin.math.abs
import kotlin.math.pow

/**
 * OpenGL-based Color Grading effect for Media3 Effect API.
 *
 * Applies color adjustments, film grain, vignette, sharpness, highlight bloom,
 * and the six selectable look presets (plus legacy matrices for saved projects)
 * directly on video frames before they reach the encoder.
 */
@OptIn(UnstableApi::class)
class ColorGradingGlEffect(
  val brightness: Float = 0f,         // -1f to 1f
  val contrast: Float = 1f,           // 0.1f to 3f
  val saturation: Float = 1f,         // 0f to 3f
  val exposure: Float = 0f,           // -2f to 2f
  val temperature: Float = 0f,        // -1f to 1f (Kelvin warm/cool)
  val tint: Float = 0f,               // -1f to 1f (green/magenta)
  val highlights: Float = 0f,         // -1f to 1f
  val shadows: Float = 0f,            // -1f to 1f
  val whites: Float = 0f,             // -1f to 1f
  val blacks: Float = 0f,             // -1f to 1f
  val vignette: Float = 0f,           // 0f to 1f
  val grain: Float = 0f,              // 0f to 1f
  val sharpness: Float = 0f,          // 0f to 1f
  val clarity: Float = 0f,            // 0f to 1f (Adjust clarity + Super Clarity, mid-tone local contrast)
  val fade: Float = 0f,               // 0f to 1f
  val autoEnhance: Float = 0f,        // 0f to 1f
  val hdrBoost: Float = 0f,           // 0f to 1f
  val colorFix: Float = 0f,           // 0f to 1f
  val denoise: Float = 0f,            // 0f to 1f (denoise + anti-flicker)
  val glow: Float = 0f,               // 0f to 1f (highlight bloom for Glow look)
  val colorMatrix4x4: FloatArray? = null,
  val colorOffset: FloatArray? = null
) : GlEffect {

  /**
   * Latest Filters / Adjust / Video Quality values. The shader reads this every
   * frame so slider ticks update the look without calling [androidx.media3.exoplayer.ExoPlayer.setVideoEffects].
   */
  @Volatile
  var liveSnapshot: ColorGradingSnapshot? = null
    private set

  fun publishLive(snapshot: ColorGradingSnapshot) {
    liveSnapshot = snapshot
  }

  fun constructorSnapshot(): ColorGradingSnapshot = ColorGradingSnapshot(
    brightness = brightness,
    contrast = contrast,
    saturation = saturation,
    exposure = exposure,
    temperature = temperature,
    tint = tint,
    highlights = highlights,
    shadows = shadows,
    whites = whites,
    blacks = blacks,
    vignette = vignette,
    grain = grain,
    sharpness = sharpness,
    clarity = clarity,
    fade = fade,
    autoEnhance = autoEnhance,
    hdrBoost = hdrBoost,
    colorFix = colorFix,
    denoise = denoise,
    glow = glow,
    colorMatrix4x4 = colorMatrix4x4,
    colorOffset = colorOffset
  )

  companion object {
    /**
     * Creates a [ColorGradingGlEffect] from domain [VideoAdjustments] and [FilterSettings].
     */
    fun fromTimeline(
      adjustments: VideoAdjustments,
      filterSettings: FilterSettings = FilterSettings()
    ): ColorGradingGlEffect {
      var mat4x4: FloatArray? = null
      var offset: FloatArray? = null

      if (filterSettings.enabled && filterSettings.type != FilterType.NONE && filterSettings.intensity > 0.01f) {
        val colorMatrix = ColorFilterGenerator.getFilterMatrix(filterSettings.type, filterSettings.intensity)
        if (colorMatrix != null) {
          val (m4, off) = convertColorMatrixToGl(PreviewEffectApplyPolicy.forceOpaqueAlpha(colorMatrix))
          mat4x4 = m4
          offset = off
        }
      }

      val extras = FilterCatalog.spatialExtras(filterSettings)
      return ColorGradingGlEffect(
        brightness = adjustments.brightness,
        contrast = adjustments.contrast,
        saturation = adjustments.saturation,
        exposure = adjustments.exposure,
        temperature = adjustments.temperature,
        tint = adjustments.tint,
        highlights = adjustments.highlights,
        shadows = adjustments.shadows,
        whites = adjustments.whites,
        blacks = adjustments.blacks,
        vignette = adjustments.vignette,
        grain = adjustments.grain,
        sharpness = (adjustments.sharpness + extras.sharpness).coerceIn(0f, 1f),
        clarity = (adjustments.clarity + adjustments.superClarity + extras.clarity).coerceIn(0f, 1f),
        fade = adjustments.fade,
        autoEnhance = (adjustments.autoEnhance + extras.enhance).coerceIn(0f, 1f),
        hdrBoost = (adjustments.hdrBoost + adjustments.colorCorrect + extras.hdrLook).coerceIn(0f, 1f),
        colorFix = adjustments.colorFix,
        denoise = (adjustments.denoise + adjustments.antiFlicker * 0.5f).coerceIn(0f, 1f),
        glow = extras.glow.coerceIn(0f, 1f),
        colorMatrix4x4 = mat4x4,
        colorOffset = offset
      )
    }

    /**
     * Converts a 20-element Android [ColorMatrix] (4 rows x 5 cols) into
     * an OpenGL 4x4 column-major matrix and a 4-element normalized offset vector.
     */
    fun convertColorMatrixToGl(colorMatrix: ColorMatrix): Pair<FloatArray, FloatArray> {
      val arr = colorMatrix.array
      // arr is: [a, b, c, d, e,
      //          f, g, h, i, j,
      //          k, l, m, n, o,
      //          p, q, r, s, t]
      val gl4x4 = FloatArray(16)
      // Column 0
      gl4x4[0] = arr[0]
      gl4x4[1] = arr[5]
      gl4x4[2] = arr[10]
      gl4x4[3] = arr[15]

      // Column 1
      gl4x4[4] = arr[1]
      gl4x4[5] = arr[6]
      gl4x4[6] = arr[11]
      gl4x4[7] = arr[16]

      // Column 2
      gl4x4[8] = arr[2]
      gl4x4[9] = arr[7]
      gl4x4[10] = arr[12]
      gl4x4[11] = arr[17]

      // Column 3
      gl4x4[12] = arr[3]
      gl4x4[13] = arr[8]
      gl4x4[14] = arr[13]
      gl4x4[15] = arr[18]

      // Offset normalized by 255.0
      val off = floatArrayOf(
        arr[4] / 255f,
        arr[9] / 255f,
        arr[14] / 255f,
        arr[19] / 255f
      )

      return Pair(gl4x4, off)
    }

    const val COLOR_GRADING_FRAGMENT_SHADER: String = """#version 100
precision mediump float;
uniform sampler2D uTexSampler;
varying vec2 vTexSamplingCoord;

// Color Grading Parameters
uniform float uBrightness;
uniform float uContrast;
uniform float uSaturation;
uniform float uExposure;
uniform float uTemperature;
uniform float uTint;
uniform float uHighlights;
uniform float uShadows;
uniform float uWhites;
uniform float uBlacks;
uniform float uFade;
uniform float uAutoEnhance;
uniform float uHdrBoost;
uniform float uColorFix;
uniform float uDenoise;
uniform float uGlow;
uniform float uVignette;
uniform float uGrain;
uniform float uGrainSeed;
uniform float uSharpness;
uniform float uClarity;
uniform vec2 uTexelSize;

// Color Matrix for artistic presets
uniform mat4 uColorMatrix;
uniform vec4 uColorOffset;
uniform int uUseColorMatrix;

float rand(vec2 co) {
  return fract(sin(dot(co.xy, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
  vec2 uv = clamp(vTexSamplingCoord, 0.0, 1.0);
  vec4 color = texture2D(uTexSampler, uv);

  // 1. Sharpness enhancement
  if (uSharpness > 0.01) {
    vec4 n = texture2D(uTexSampler, clamp(uv + vec2(0.0, uTexelSize.y), 0.0, 1.0));
    vec4 s = texture2D(uTexSampler, clamp(uv - vec2(0.0, uTexelSize.y), 0.0, 1.0));
    vec4 e = texture2D(uTexSampler, clamp(uv + vec2(uTexelSize.x, 0.0), 0.0, 1.0));
    vec4 w = texture2D(uTexSampler, clamp(uv - vec2(uTexelSize.x, 0.0), 0.0, 1.0));
    vec4 laplacian = (n + s + e + w) * 0.25;
    color.rgb = color.rgb + (color.rgb - laplacian.rgb) * (uSharpness * 1.5);
  }

  // 1b. Clarity / Super Clarity: mid-tone weighted local contrast (wide-radius unsharp mask)
  if (uClarity > 0.01) {
    vec2 cr = uTexelSize * 3.0;
    vec3 cb = texture2D(uTexSampler, clamp(uv + vec2(cr.x, 0.0), 0.0, 1.0)).rgb
            + texture2D(uTexSampler, clamp(uv - vec2(cr.x, 0.0), 0.0, 1.0)).rgb
            + texture2D(uTexSampler, clamp(uv + vec2(0.0, cr.y), 0.0, 1.0)).rgb
            + texture2D(uTexSampler, clamp(uv - vec2(0.0, cr.y), 0.0, 1.0)).rgb
            + texture2D(uTexSampler, clamp(uv + cr, 0.0, 1.0)).rgb
            + texture2D(uTexSampler, clamp(uv - cr, 0.0, 1.0)).rgb
            + texture2D(uTexSampler, clamp(uv + vec2(cr.x, -cr.y), 0.0, 1.0)).rgb
            + texture2D(uTexSampler, clamp(uv + vec2(-cr.x, cr.y), 0.0, 1.0)).rgb;
    cb *= 0.125;
    float cLum = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
    float cMid = 1.0 - abs(cLum * 2.0 - 1.0);
    color.rgb += (color.rgb - cb) * uClarity * 1.5 * (0.35 + 0.65 * cMid);
  }

  // 2. Exposure & Brightness (same photographic 2^EV as GpuShaders export)
  color.rgb = color.rgb * pow(2.0, uExposure) + vec3(uBrightness);

  // 3. Contrast
  if (abs(uContrast - 1.0) > 0.001) {
    color.rgb = (color.rgb - 0.5) * uContrast + 0.5;
  }

  // 4. Saturation
  if (abs(uSaturation - 1.0) > 0.001) {
    float lum = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
    color.rgb = mix(vec3(lum), color.rgb, uSaturation);
  }

  // 5. Temperature & Tint (same coefficients as GpuShaders export)
  if (abs(uTemperature) > 0.001 || abs(uTint) > 0.001) {
    color.r += uTemperature * 0.15 + uTint * 0.08;
    color.g -= uTint * 0.12;
    color.b -= uTemperature * 0.15 - uTint * 0.08;
  }

  // 6. Highlights & Shadows
  if (abs(uHighlights) > 0.001 || abs(uShadows) > 0.001) {
    float lum = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
    float shadowWeight = 1.0 - smoothstep(0.0, 0.6, lum);
    float highlightWeight = smoothstep(0.4, 1.0, lum);
    color.rgb += vec3(uShadows * shadowWeight * 0.2 + uHighlights * highlightWeight * 0.2);
  }

  // 6b. Whites & Blacks
  if (abs(uWhites) > 0.001 || abs(uBlacks) > 0.001) {
    float lum = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
    float whiteW = smoothstep(0.65, 1.0, lum);
    float blackW = 1.0 - smoothstep(0.0, 0.35, lum);
    color.rgb += vec3(uWhites * whiteW * 0.18 + uBlacks * blackW * 0.18);
  }

  // 6c. Video Quality: Auto Enhance, HDR, Color Fix, Denoise
  if (uAutoEnhance > 0.01) {
    float lum = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
    color.rgb = mix(vec3(lum), color.rgb, 1.0 + uAutoEnhance * 0.20);
    color.rgb = (color.rgb - 0.5) * (1.0 + uAutoEnhance * 0.15) + 0.5 + vec3(uAutoEnhance * 0.04);
  }
  if (uHdrBoost > 0.01) {
    float lum = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
    color.rgb = mix(vec3(lum), color.rgb, 1.0 + uHdrBoost * 0.25);
    color.rgb = (color.rgb - 0.5) * (1.0 + uHdrBoost * 0.22) + 0.5 + vec3(uHdrBoost * 0.02);
  }
  if (uColorFix > 0.01) {
    color.r *= 1.0 - uColorFix * 0.04;
    color.g *= 1.0 - uColorFix * 0.03;
    color.b *= 1.0 + uColorFix * 0.06;
  }
  if (uDenoise > 0.01) {
    vec3 blur = texture2D(uTexSampler, clamp(uv + vec2(uTexelSize.x, 0.0), 0.0, 1.0)).rgb
              + texture2D(uTexSampler, clamp(uv - vec2(uTexelSize.x, 0.0), 0.0, 1.0)).rgb
              + texture2D(uTexSampler, clamp(uv + vec2(0.0, uTexelSize.y), 0.0, 1.0)).rgb
              + texture2D(uTexSampler, clamp(uv - vec2(0.0, uTexelSize.y), 0.0, 1.0)).rgb;
    color.rgb = mix(color.rgb, blur * 0.25, uDenoise * 0.65);
  }
  if (uFade > 0.01) {
    color.rgb = mix(color.rgb, vec3(0.5), uFade * 0.55);
  }

  // 6d. Glow: real highlight bloom (not a colour-only lift)
  if (uGlow > 0.01) {
    vec2 gs = uTexelSize * 3.5;
    vec3 blur = texture2D(uTexSampler, clamp(uv + vec2(-gs.x, -gs.y), 0.0, 1.0)).rgb * 0.15
              + texture2D(uTexSampler, clamp(uv + vec2( gs.x, -gs.y), 0.0, 1.0)).rgb * 0.15
              + texture2D(uTexSampler, clamp(uv + vec2(-gs.x,  gs.y), 0.0, 1.0)).rgb * 0.15
              + texture2D(uTexSampler, clamp(uv + vec2( gs.x,  gs.y), 0.0, 1.0)).rgb * 0.15
              + texture2D(uTexSampler, uv).rgb * 0.40;
    vec3 bloom = max(blur - 0.35, 0.0) * 1.6;
    color.rgb += bloom * uGlow;
  }

  // 7. Color Matrix transformation
  if (uUseColorMatrix == 1) {
    color = (uColorMatrix * color) + uColorOffset;
  }

  // 8. Vignette
  if (uVignette > 0.01) {
    vec2 coord = (uv - 0.5) * 2.0;
    float dist = length(coord);
    float vig = 1.0 - smoothstep(0.7, 1.4, dist * (0.8 + uVignette * 0.8));
    color.rgb *= vig;
  }

  // 9. Film Grain
  if (uGrain > 0.01) {
    float noise = (rand(uv + vec2(uGrainSeed * 0.13, uGrainSeed * 0.17)) - 0.5) * uGrain * 0.25;
    color.rgb += vec3(noise);
  }

  color.rgb = clamp(color.rgb, 0.0, 1.0);
  // Media3 / decoder intermediate textures often carry A=0. Leaving that
  // alpha in the graded output makes TextureView composite the frame as
  // fully transparent (black preview) while Compose overlays stay visible.
  color.a = 1.0;
  gl_FragColor = color;
}
"""

    @Volatile
    var debugFrameCount: Long = 0
      internal set

    internal fun bindUniforms(
      program: GlProgram,
      snapshot: ColorGradingSnapshot,
      presentationTimeUs: Long,
      width: Int,
      height: Int
    ) {
      program.setFloatUniform("uBrightness", snapshot.brightness)
      program.setFloatUniform("uContrast", snapshot.contrast)
      program.setFloatUniform("uSaturation", snapshot.saturation)
      program.setFloatUniform("uExposure", snapshot.exposure)
      program.setFloatUniform("uTemperature", snapshot.temperature)
      program.setFloatUniform("uTint", snapshot.tint)
      program.setFloatUniform("uHighlights", snapshot.highlights)
      program.setFloatUniform("uShadows", snapshot.shadows)
      program.setFloatUniform("uWhites", snapshot.whites)
      program.setFloatUniform("uBlacks", snapshot.blacks)
      program.setFloatUniform("uFade", snapshot.fade)
      program.setFloatUniform("uAutoEnhance", snapshot.autoEnhance)
      program.setFloatUniform("uHdrBoost", snapshot.hdrBoost)
      program.setFloatUniform("uColorFix", snapshot.colorFix)
      program.setFloatUniform("uDenoise", snapshot.denoise)
      program.setFloatUniform("uGlow", snapshot.glow)
      program.setFloatUniform("uVignette", snapshot.vignette)
      program.setFloatUniform("uGrain", snapshot.grain)
      program.setFloatUniform("uGrainSeed", (presentationTimeUs / 1000L % 10000).toFloat() / 1000f)
      program.setFloatUniform("uSharpness", snapshot.sharpness)
      program.setFloatUniform("uClarity", snapshot.clarity)

      val texelW = if (width > 0) 1f / width else 1f / 1920f
      val texelH = if (height > 0) 1f / height else 1f / 1080f
      program.setFloatsUniform("uTexelSize", floatArrayOf(texelW, texelH))

      val matrix = snapshot.colorMatrix4x4 ?: GlUtil.create4x4IdentityMatrix()
      val offset = snapshot.colorOffset ?: floatArrayOf(0f, 0f, 0f, 0f)
      val useMatrix = if (snapshot.colorMatrix4x4 != null && snapshot.colorOffset != null) 1 else 0
      program.setIntUniform("uUseColorMatrix", useMatrix)
      program.setFloatsUniform("uColorMatrix", matrix)
      program.setFloatsUniform("uColorOffset", offset)

      val frame = ++debugFrameCount
      if (frame == 1L || frame % 120L == 0L) {
        android.util.Log.d(
          "ColorGradingGlEffect",
          "frame=$frame ${width}x${height} ptsUs=$presentationTimeUs useMatrix=$useMatrix " +
            "bright=${snapshot.brightness} contrast=${snapshot.contrast} sat=${snapshot.saturation} " +
            "temp=${snapshot.temperature} tint=${snapshot.tint}"
        )
      }
    }
  }

  /**
   * Always false. Media3 drops [GlEffect] programs whose [isNoOp] is true, which
   * disconnected [publishLive] from the GPU (filters updated Kotlin state only).
   * Identity vs active look is [isIdentityLook].
   */
  override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false

  fun isIdentityLook(): Boolean = (liveSnapshot ?: constructorSnapshot()).isNoOp()

  override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
    return CustomShaderGlShaderProgram(
      useHdr = useHdr,
      vertexShader = CustomShaderGlEffect.DEFAULT_VERTEX_SHADER,
      fragmentShader = COLOR_GRADING_FRAGMENT_SHADER,
      uniformBinder = { program: GlProgram, presentationTimeUs: Long, width: Int, height: Int ->
        bindUniforms(program, liveSnapshot ?: constructorSnapshot(), presentationTimeUs, width, height)
      }
    )
  }
}

/**
 * Frame-time color-grading parameters. Publishing a new snapshot updates the live
 * preview shader without rebuilding ExoPlayer's video graph.
 */
data class ColorGradingSnapshot(
  val brightness: Float = 0f,
  val contrast: Float = 1f,
  val saturation: Float = 1f,
  val exposure: Float = 0f,
  val temperature: Float = 0f,
  val tint: Float = 0f,
  val highlights: Float = 0f,
  val shadows: Float = 0f,
  val whites: Float = 0f,
  val blacks: Float = 0f,
  val vignette: Float = 0f,
  val grain: Float = 0f,
  val sharpness: Float = 0f,
  val clarity: Float = 0f,
  val fade: Float = 0f,
  val autoEnhance: Float = 0f,
  val hdrBoost: Float = 0f,
  val colorFix: Float = 0f,
  val denoise: Float = 0f,
  val glow: Float = 0f,
  val colorMatrix4x4: FloatArray? = null,
  val colorOffset: FloatArray? = null
) {
  fun isNoOp(): Boolean {
    val isDefaultAdjustments = abs(brightness) < 0.001f &&
      abs(contrast - 1f) < 0.001f &&
      abs(saturation - 1f) < 0.001f &&
      abs(exposure) < 0.001f &&
      abs(temperature) < 0.001f &&
      abs(tint) < 0.001f &&
      abs(highlights) < 0.001f &&
      abs(shadows) < 0.001f &&
      abs(whites) < 0.001f &&
      abs(blacks) < 0.001f &&
      vignette < 0.01f &&
      grain < 0.01f &&
      sharpness < 0.01f &&
      clarity < 0.01f &&
      fade < 0.01f &&
      autoEnhance < 0.01f &&
      hdrBoost < 0.01f &&
      colorFix < 0.01f &&
      denoise < 0.01f &&
      glow < 0.01f
    return isDefaultAdjustments && colorMatrix4x4 == null
  }

  /**
   * CPU replica of the preview fragment shader for a single sample (no spatial
   * taps). Used to prove a look actually changes pixels, including when the
   * incoming video alpha is 0.
   */
  fun evaluatePreviewPixel(r: Float, g: Float, b: Float, a: Float = 0f): FloatArray {
    var cr = r
    var cg = g
    var cb = b
    val expScale = 2.0.pow(exposure.toDouble()).toFloat()
    cr = cr * expScale + brightness
    cg = cg * expScale + brightness
    cb = cb * expScale + brightness
    if (abs(contrast - 1f) > 0.001f) {
      cr = (cr - 0.5f) * contrast + 0.5f
      cg = (cg - 0.5f) * contrast + 0.5f
      cb = (cb - 0.5f) * contrast + 0.5f
    }
    if (abs(saturation - 1f) > 0.001f) {
      val lum = cr * 0.2126f + cg * 0.7152f + cb * 0.0722f
      cr = lum + (cr - lum) * saturation
      cg = lum + (cg - lum) * saturation
      cb = lum + (cb - lum) * saturation
    }
    if (abs(temperature) > 0.001f || abs(tint) > 0.001f) {
      cr += temperature * 0.15f + tint * 0.08f
      cg -= tint * 0.12f
      cb -= temperature * 0.15f - tint * 0.08f
    }
    if (abs(highlights) > 0.001f || abs(shadows) > 0.001f) {
      val lum = cr * 0.2126f + cg * 0.7152f + cb * 0.0722f
      val shadowWeight = 1f - smoothstep(0f, 0.6f, lum)
      val highlightWeight = smoothstep(0.4f, 1f, lum)
      val add = shadows * shadowWeight * 0.2f + highlights * highlightWeight * 0.2f
      cr += add
      cg += add
      cb += add
    }
    if (abs(whites) > 0.001f || abs(blacks) > 0.001f) {
      val lum = cr * 0.2126f + cg * 0.7152f + cb * 0.0722f
      val whiteW = smoothstep(0.65f, 1f, lum)
      val blackW = 1f - smoothstep(0f, 0.35f, lum)
      val add = whites * whiteW * 0.18f + blacks * blackW * 0.18f
      cr += add
      cg += add
      cb += add
    }
    if (autoEnhance > 0.01f) {
      val lum = cr * 0.2126f + cg * 0.7152f + cb * 0.0722f
      cr = lum + (cr - lum) * (1f + autoEnhance * 0.20f)
      cg = lum + (cg - lum) * (1f + autoEnhance * 0.20f)
      cb = lum + (cb - lum) * (1f + autoEnhance * 0.20f)
      cr = (cr - 0.5f) * (1f + autoEnhance * 0.15f) + 0.5f + autoEnhance * 0.04f
      cg = (cg - 0.5f) * (1f + autoEnhance * 0.15f) + 0.5f + autoEnhance * 0.04f
      cb = (cb - 0.5f) * (1f + autoEnhance * 0.15f) + 0.5f + autoEnhance * 0.04f
    }
    if (hdrBoost > 0.01f) {
      val lum = cr * 0.2126f + cg * 0.7152f + cb * 0.0722f
      cr = lum + (cr - lum) * (1f + hdrBoost * 0.25f)
      cg = lum + (cg - lum) * (1f + hdrBoost * 0.25f)
      cb = lum + (cb - lum) * (1f + hdrBoost * 0.25f)
      cr = (cr - 0.5f) * (1f + hdrBoost * 0.22f) + 0.5f + hdrBoost * 0.02f
      cg = (cg - 0.5f) * (1f + hdrBoost * 0.22f) + 0.5f + hdrBoost * 0.02f
      cb = (cb - 0.5f) * (1f + hdrBoost * 0.22f) + 0.5f + hdrBoost * 0.02f
    }
    if (colorFix > 0.01f) {
      cr *= 1f - colorFix * 0.04f
      cg *= 1f - colorFix * 0.03f
      cb *= 1f + colorFix * 0.06f
    }
    if (fade > 0.01f) {
      cr = cr + (0.5f - cr) * fade * 0.55f
      cg = cg + (0.5f - cg) * fade * 0.55f
      cb = cb + (0.5f - cb) * fade * 0.55f
    }
    val matrix = colorMatrix4x4
    val offset = colorOffset
    if (matrix != null && offset != null) {
      val nr = matrix[0] * cr + matrix[4] * cg + matrix[8] * cb + matrix[12] * a + offset[0]
      val ng = matrix[1] * cr + matrix[5] * cg + matrix[9] * cb + matrix[13] * a + offset[1]
      val nb = matrix[2] * cr + matrix[6] * cg + matrix[10] * cb + matrix[14] * a + offset[2]
      cr = nr
      cg = ng
      cb = nb
    }
    return floatArrayOf(cr.coerceIn(0f, 1f), cg.coerceIn(0f, 1f), cb.coerceIn(0f, 1f), 1f)
  }

  private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
  }

  companion object {
    fun from(effect: ColorGradingGlEffect): ColorGradingSnapshot = effect.constructorSnapshot()

    fun from(adjustments: VideoAdjustments, filter: FilterSettings = FilterSettings()): ColorGradingSnapshot =
      from(ColorGradingGlEffect.fromTimeline(adjustments, filter))
  }
}
