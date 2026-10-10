package com.example.engine.effects.media3

import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import com.example.domain.model.VideoClip
import com.example.engine.vfx.VfxEffectsHost
import com.vfx.engine.core.effect.EffectInstance

/**
 * Live-preview Media3 shaders for the production catalog's `vfx:` keys.
 * Export still runs the same ids through [com.example.engine.composition.gpu.VfxStackStage].
 */
@OptIn(UnstableApi::class)
object PreviewVfxEffects {

  fun effectsFor(clip: VideoClip?): List<Effect> {
    val stack = VfxEffectsHost.decode(clip?.vfxStackJson)
    return stack.effects()
      .filter { it.enabled && it.intensity > 0.01f }
      .mapNotNull { toMedia3(it) }
  }

  fun signature(clip: VideoClip?): String = clip?.vfxStackJson.orEmpty()

  fun toMedia3(instance: EffectInstance): Effect? {
    val intensity = instance.intensity.coerceIn(0f, 1f)
    return when (instance.definition.id) {
      "distort.glitch" -> Media3EffectPipeline.createGlitchEffect(intensity)
      "chromatic.aberration", "chromatic.rgbSplit" -> Media3EffectPipeline.createRgbSplitEffect(intensity)
      "blur.directional" -> directionalBlur(intensity)
      "noise.filmGrain" -> filmGrain(intensity)
      "light.leak" -> lightLeak(intensity)
      "color.hdr" -> hdr(intensity)
      "color.pop" -> colorPop(intensity)
      "sharpen.unsharp", "sharpen.sharpen" -> sharpen(intensity)
      "stylize.sketch" -> Media3EffectPipeline.createStyleTransferEffect(StyleTransferType.SKETCH_CHARCOAL, intensity)
      "stylize.halftone" -> Media3EffectPipeline.createStyleTransferEffect(StyleTransferType.HALFTONE_COMIC, intensity)
      "stylize.posterize" -> posterize(intensity)
      "stylize.pixelate" -> pixelate(intensity)
      "stylize.duotone" -> duotone(intensity)
      else -> null
    }
  }

  private fun directionalBlur(intensity: Float) = shader(
    name = "PreviewDirectionalBlur",
    intensity = intensity,
    body = """
  vec2 uv = clamp(vTexSamplingCoord, 0.0, 1.0);
  vec2 step = vec2(0.012 + 0.06 * uIntensity, 0.0);
  vec4 acc = vec4(0.0);
  acc += texture2D(uTexSampler, clamp(uv - 2.0 * step, 0.0, 1.0)) * 0.15;
  acc += texture2D(uTexSampler, clamp(uv - step, 0.0, 1.0)) * 0.20;
  acc += texture2D(uTexSampler, uv) * 0.30;
  acc += texture2D(uTexSampler, clamp(uv + step, 0.0, 1.0)) * 0.20;
  acc += texture2D(uTexSampler, clamp(uv + 2.0 * step, 0.0, 1.0)) * 0.15;
  gl_FragColor = acc;
"""
  )

  private fun filmGrain(intensity: Float) = shader(
    name = "PreviewFilmGrain",
    intensity = intensity,
    body = """
  vec2 uv = clamp(vTexSamplingCoord, 0.0, 1.0);
  vec4 color = texture2D(uTexSampler, uv);
  float n = fract(sin(dot(uv * 240.0, vec2(12.9898, 78.233))) * 43758.5453);
  color.rgb += (n - 0.5) * (0.04 + 0.22 * uIntensity);
  gl_FragColor = color;
"""
  )

  private fun lightLeak(intensity: Float) = shader(
    name = "PreviewLightLeak",
    intensity = intensity,
    body = """
  vec2 uv = clamp(vTexSamplingCoord, 0.0, 1.0);
  vec4 color = texture2D(uTexSampler, uv);
  float blob = exp(-length(uv - vec2(0.18, 0.78)) * 3.2);
  vec3 leak = vec3(1.0, 0.45, 0.12) * blob * (0.25 + 0.9 * uIntensity);
  gl_FragColor = vec4(1.0 - (1.0 - color.rgb) * (1.0 - leak), color.a);
"""
  )

  private fun hdr(intensity: Float) = shader(
    name = "PreviewHdr",
    intensity = intensity,
    body = """
  vec2 uv = clamp(vTexSamplingCoord, 0.0, 1.0);
  vec4 color = texture2D(uTexSampler, uv);
  float l = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
  float shadow = 1.0 - smoothstep(0.0, 0.42, l);
  vec3 lifted = color.rgb + color.rgb * shadow * 0.35 * uIntensity + shadow * 0.06 * uIntensity;
  lifted = (lifted - 0.5) * (1.0 + 0.18 * uIntensity) + 0.5;
  gl_FragColor = vec4(clamp(lifted, 0.0, 1.0), color.a);
"""
  )

  private fun colorPop(intensity: Float) = shader(
    name = "PreviewColorPop",
    intensity = intensity,
    body = """
  vec2 uv = clamp(vTexSamplingCoord, 0.0, 1.0);
  vec4 color = texture2D(uTexSampler, uv);
  float l = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
  float mx = max(color.r, max(color.g, color.b));
  float mn = min(color.r, min(color.g, color.b));
  float sat = (mx - mn) / max(mx, 0.001);
  float keep = step(0.25, sat) * step(color.g, color.r) * step(color.b, color.r);
  vec3 popped = mix(vec3(l), color.rgb, keep);
  gl_FragColor = vec4(mix(color.rgb, popped, uIntensity), color.a);
"""
  )

  private fun sharpen(intensity: Float) = shader(
    name = "PreviewSharpen",
    intensity = intensity,
    extraUniforms = "uniform vec2 uTexelSize;",
    bindTexel = true,
    body = """
  vec2 uv = clamp(vTexSamplingCoord, 0.0, 1.0);
  vec4 color = texture2D(uTexSampler, uv);
  vec4 blur = texture2D(uTexSampler, uv) * 0.4;
  blur += texture2D(uTexSampler, clamp(uv + vec2(uTexelSize.x, 0.0), 0.0, 1.0)) * 0.15;
  blur += texture2D(uTexSampler, clamp(uv - vec2(uTexelSize.x, 0.0), 0.0, 1.0)) * 0.15;
  blur += texture2D(uTexSampler, clamp(uv + vec2(0.0, uTexelSize.y), 0.0, 1.0)) * 0.15;
  blur += texture2D(uTexSampler, clamp(uv - vec2(0.0, uTexelSize.y), 0.0, 1.0)) * 0.15;
  vec3 detail = color.rgb + (color.rgb - blur.rgb) * (0.45 + 1.4 * uIntensity);
  gl_FragColor = vec4(clamp(detail, 0.0, 1.0), color.a);
"""
  )

  private fun posterize(intensity: Float) = shader(
    name = "PreviewPosterize",
    intensity = intensity,
    body = """
  vec2 uv = clamp(vTexSamplingCoord, 0.0, 1.0);
  vec4 color = texture2D(uTexSampler, uv);
  float levels = max(2.0, 8.0 - 5.0 * uIntensity);
  vec3 q = floor(color.rgb * levels + 0.5) / levels;
  gl_FragColor = vec4(mix(color.rgb, q, clamp(uIntensity * 1.2, 0.0, 1.0)), color.a);
"""
  )

  private fun pixelate(intensity: Float) = shader(
    name = "PreviewPixelate",
    intensity = intensity,
    body = """
  vec2 uv = clamp(vTexSamplingCoord, 0.0, 1.0);
  float blocks = mix(120.0, 16.0, uIntensity);
  vec2 grid = floor(uv * blocks) / blocks;
  vec4 cell = texture2D(uTexSampler, clamp(grid + 0.5 / blocks, 0.0, 1.0));
  gl_FragColor = cell;
"""
  )

  private fun duotone(intensity: Float) = shader(
    name = "PreviewDuotone",
    intensity = intensity,
    body = """
  vec2 uv = clamp(vTexSamplingCoord, 0.0, 1.0);
  vec4 color = texture2D(uTexSampler, uv);
  float l = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
  vec3 duo = mix(vec3(0.10, 0.00, 0.20), vec3(1.0, 0.85, 0.50), l);
  gl_FragColor = vec4(mix(color.rgb, duo, uIntensity), color.a);
"""
  )

  private fun shader(
    name: String,
    intensity: Float,
    extraUniforms: String = "",
    bindTexel: Boolean = false,
    body: String,
  ): CustomShaderGlEffect {
    val fragment = """#version 100
precision mediump float;
uniform sampler2D uTexSampler;
uniform float uIntensity;
$extraUniforms
varying vec2 vTexSamplingCoord;
void main() {
$body
}
"""
    return CustomShaderGlEffect(
      name = name,
      fragmentShader = fragment,
      uniformBinder = { program, _, width, height ->
        program.setFloatUniform("uIntensity", intensity)
        if (bindTexel) {
          val w = if (width > 0) width.toFloat() else 1920f
          val h = if (height > 0) height.toFloat() else 1080f
          program.setFloatsUniform("uTexelSize", floatArrayOf(1f / w, 1f / h))
        }
      }
    )
  }
}
