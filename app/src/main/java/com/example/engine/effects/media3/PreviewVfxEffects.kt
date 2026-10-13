package com.example.engine.effects.media3

import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.UnstableApi
import com.example.domain.model.VideoClip
import com.example.engine.vfx.VfxEffectsHost
import com.vfx.engine.core.effect.EffectInstance

/**
 * Live-preview Media3 shaders for the production catalog's `vfx:` keys.
 *
 * Every shader here mirrors the GLSL body that [com.vfx.engine.gpu.glsl.EffectGlsl] runs in the
 * compositor, reads the effect's **real parameters** from the instance (not just intensity) and
 * blends its result with `uIntensity` exactly like the export template does
 * (`mix(base, result, u_intensity)`), so preview and export show the same frame.
 *
 * Export still runs the same ids through [com.example.engine.composition.gpu.VfxStackStage].
 */
@OptIn(UnstableApi::class)
object PreviewVfxEffects {

  fun effectsFor(clip: VideoClip?): List<Effect> {
    val stack = VfxEffectsHost.decode(clip?.vfxStackJson)
    return stack.effects()
      .filter { it.enabled && it.intensity > 0.01f }
      .mapNotNull { instance ->
        runCatching { toMedia3(instance) }
          .onFailure { android.util.Log.e("PreviewVfxEffects", "Skipped failing effect ${instance.definition.id}", it) }
          .getOrNull()
      }
  }

  fun signature(clip: VideoClip?): String = clip?.vfxStackJson.orEmpty()

  fun toMedia3(instance: EffectInstance): Effect? {
    val intensity = instance.intensity.coerceIn(0f, 1f)
    return when (instance.definition.id) {
      // ---- The ten Video Effects assets (engine bodies in EffectGlsl). ----
      "blur.motion" -> motionBlur(
        intensity = intensity,
        distance = instance.param("distance", 0.03f),
        angle = instance.param("angle", 0f),
        samples = instance.param("samples", 24).coerceIn(2, 64)
      )
      "video.vhs" -> vhs(
        intensity = intensity,
        bleed = instance.param("bleed", 0.012f),
        noise = instance.param("noise", 0.08f),
        tracking = instance.param("tracking", 0.5f),
        scanline = instance.param("scanline", 0.35f)
      )
      "video.crt" -> crt(
        intensity = intensity,
        curvature = instance.param("curvature", 0.22f),
        scanline = instance.param("scanline", 0.6f),
        grille = instance.param("grille", 0.6f),
        flicker = instance.param("flicker", 0.25f),
        vignette = instance.param("vignette", 0.35f)
      )
      "distort.datamosh" -> datamosh(
        intensity = intensity,
        amount = instance.param("amount", 0.08f),
        blockSize = instance.param("blockSize", 32f),
        smear = instance.param("smear", 0.45f)
      )
      "light.anamorphic" -> anamorphic(
        intensity = intensity,
        amount = instance.param("amount", 0.6f),
        threshold = instance.param("threshold", 0.55f),
        length = instance.param("length", 0.22f),
        tint = instance.param("tint", floatArrayOf(0.55f, 0.75f, 1f, 1f))
      )
      "noise.grain" -> filmGrainPro(
        intensity = intensity,
        amount = instance.param("amount", 0.08f),
        size = instance.param("size", 1.1f),
        shadow = instance.param("shadow", 0.5f),
        chroma = instance.param("chroma", 0.35f)
      )
      "light.halation" -> halation(
        intensity = intensity,
        amount = instance.param("amount", 0.7f),
        radius = instance.param("radius", 0.016f),
        threshold = instance.param("threshold", 0.62f),
        tint = instance.param("tint", floatArrayOf(1f, 0.35f, 0.25f, 1f))
      )
      "light.flare" -> lensFlare(
        intensity = intensity,
        position = instance.param("position", floatArrayOf(0.25f, 0.75f)),
        amount = instance.param("amount", 0.6f),
        halo = instance.param("halo", 0.7f),
        ghosts = instance.param("ghosts", 0.5f),
        tint = instance.param("tint", floatArrayOf(1f, 0.82f, 0.55f, 1f))
      )
      "stylize.speedLines" -> speedLines(
        intensity = intensity,
        center = instance.param("center", floatArrayOf(0.5f, 0.5f)),
        amount = instance.param("amount", 0.55f),
        lines = instance.param("lines", 90f),
        speed = instance.param("speed", 4f),
        reach = instance.param("reach", 0.35f)
      )
      "distort.kaleidoscope" -> kaleidoscope(
        intensity = intensity,
        center = instance.param("center", floatArrayOf(0.5f, 0.5f)),
        segments = instance.param("segments", 6).toFloat(),
        rotation = instance.param("rotation", 0f),
        zoom = instance.param("zoom", 1f)
      )

      // ---- Engine effects used by the Photo / AI assets and the VFX stack panel. ----
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

  /** Reads a parameter from the instance; missing / renamed params fall back to the default. */
  private fun <T> EffectInstance.param(id: String, fallback: T): T =
    runCatching { getParam<T>(id) }.getOrDefault(fallback)

  // ==========================================================================
  // Video Effects preview shaders (GLSL ES 1.00, identical math to EffectGlsl)
  // ==========================================================================

  private fun motionBlur(intensity: Float, distance: Float, angle: Float, samples: Int): CustomShaderGlEffect =
    shader(
      name = "PreviewMotionBlur",
      intensity = intensity,
      uniforms = "uniform float uDistance;\nuniform float uAngle;\nuniform float uSamples;",
      body = """
  vec2 uv = clamp(vTexSamplingCoord, 0.0, 1.0);
  vec4 base = texture2D(uTexSampler, uv);
  float a = radians(uAngle);
  vec2 dir = vec2(cos(a), sin(a));
  float jitter = fract(sin(dot(uv, vec2(12.9898, 78.233))) * 43758.5453) - 0.5;
  vec3 acc = vec3(0.0);
  float taps = 0.0;
  // Constant-bound loop (GLSL ES 1.00 well-formed): taps past uSamples get zero weight.
  // Preview budget is 32 taps; export runs up to 64 with the same math and params.
  for (int i = 0; i < 32; i++) {
    float fi = float(i);
    float on = fi < uSamples ? 1.0 : 0.0;
    float t = (fi + jitter) / max(uSamples - 1.0, 1.0) - 0.5;
    acc += texture2D(uTexSampler, clamp(uv + dir * t * uDistance, 0.0, 1.0)).rgb * on;
    taps += on;
  }
  vec3 rgb = acc / max(taps, 1.0);
  gl_FragColor = vec4(clamp(mix(base.rgb, rgb, uIntensity), 0.0, 1.0), base.a);
""",
      binder = { program, _, _, _ ->
        program.setFloatUniform("uDistance", distance)
        program.setFloatUniform("uAngle", angle)
        program.setFloatUniform("uSamples", samples.toFloat())
      }
    )

  private fun vhs(intensity: Float, bleed: Float, noise: Float, tracking: Float, scanline: Float): CustomShaderGlEffect =
    shader(
      name = "PreviewVhsTape",
      intensity = intensity,
      uniforms = "uniform float uBleed;\nuniform float uNoise;\nuniform float uTracking;\nuniform float uScanline;",
      body = """
  vec2 uv = clamp(vTexSamplingCoord, 0.0, 1.0);
  vec2 res = 1.0 / max(uTexelSize, vec2(1.0e-5));
  vec4 base = texture2D(uTexSampler, uv);
  float line = floor(uv.y * res.y);
  float frame = floor(uTime * 24.0);
  float band = smoothstep(0.10, 0.0, abs(fract(uv.y - uTime * 0.22) - 0.5));
  float jitter = (hash21(vec2(line, frame)) - 0.5) * (0.0015 + 0.012 * uTracking * band);
  vec2 juv = clamp(uv + vec2(jitter, 0.0), 0.0, 1.0);
  vec4 sharp = texture2D(uTexSampler, juv);
  vec3 chroma = vec3(0.0);
  for (int i = 0; i < 8; i++) {
    chroma += texture2D(uTexSampler, clamp(juv + vec2(float(i) / 7.0 * uBleed, 0.0), 0.0, 1.0)).rgb;
  }
  chroma /= 8.0;
  float sharpLuma = dot(sharp.rgb, vec3(0.2126, 0.7152, 0.0722));
  vec3 rgb = vec3(sharpLuma) + (chroma - vec3(dot(chroma, vec3(0.2126, 0.7152, 0.0722))));
  rgb += (hash21(vec2(line * 1.37, frame * 2.11)) - 0.5) * uNoise * 0.55;
  rgb += step(0.996 - 0.004 * uTracking, hash21(vec2(frame, line * 0.73))) * 0.22;
  rgb *= 1.0 - uScanline * 0.30 * (0.5 + 0.5 * sin(uv.y * res.y * 3.14159265));
  rgb = mix(rgb, vec3(dot(rgb, vec3(0.2126, 0.7152, 0.0722))), 0.32);
  gl_FragColor = vec4(clamp(mix(base.rgb, rgb, uIntensity), 0.0, 1.0), base.a);
""",
      binder = { program, _, _, _ ->
        program.setFloatUniform("uBleed", bleed)
        program.setFloatUniform("uNoise", noise)
        program.setFloatUniform("uTracking", tracking)
        program.setFloatUniform("uScanline", scanline)
      }
    )

  private fun crt(
    intensity: Float,
    curvature: Float,
    scanline: Float,
    grille: Float,
    flicker: Float,
    vignette: Float,
  ): CustomShaderGlEffect = shader(
    name = "PreviewCrtScanlines",
    intensity = intensity,
    uniforms = "uniform float uCurvature;\nuniform float uScanline;\nuniform float uGrille;\nuniform float uFlicker;\nuniform float uVignette;",
    body = """
  vec2 uv = clamp(vTexSamplingCoord, 0.0, 1.0);
  vec2 res = 1.0 / max(uTexelSize, vec2(1.0e-5));
  vec4 base = texture2D(uTexSampler, uv);
  vec2 p = (uv - 0.5) * 2.0;
  float r2 = dot(p, p);
  vec2 warped = clamp(p * (1.0 + uCurvature * r2) * 0.5 + 0.5, 0.0, 1.0);
  vec2 s = mix(uv, warped, clamp(uIntensity, 0.0, 1.0));
  vec3 src = texture2D(uTexSampler, s).rgb;
  float triad = fract(s.x * res.x / 3.0);
  vec3 grilleMask = vec3(
    step(triad, 0.3333),
    step(0.3333, triad) * step(triad, 0.6667),
    step(0.6667, triad));
  grilleMask = mix(vec3(1.0), grilleMask * 1.55 + 0.35, clamp(uGrille, 0.0, 1.0));
  float scan = 1.0 - uScanline * 0.45 * (0.5 + 0.5 * sin(s.y * res.y * 3.14159265));
  float mains = 1.0 - uFlicker * 0.10 * (0.5 + 0.5 * sin(uTime * 41.0));
  float vig = 1.0 - uVignette * smoothstep(0.25, 0.85, length(s - 0.5));
  vec3 rgb = src * grilleMask * scan * mains * vig;
  rgb += texture2D(uTexSampler, clamp(s + vec2(0.0, uTexelSize.y), 0.0, 1.0)).rgb * (1.0 - scan) * 0.35;
  gl_FragColor = vec4(clamp(mix(base.rgb, rgb, uIntensity), 0.0, 1.0), base.a);
""",
    binder = { program, _, _, _ ->
      program.setFloatUniform("uCurvature", curvature)
      program.setFloatUniform("uScanline", scanline)
      program.setFloatUniform("uGrille", grille)
      program.setFloatUniform("uFlicker", flicker)
      program.setFloatUniform("uVignette", vignette)
    }
  )

  private fun datamosh(intensity: Float, amount: Float, blockSize: Float, smear: Float): CustomShaderGlEffect =
    shader(
      name = "PreviewDatamosh",
      intensity = intensity,
      uniforms = "uniform float uAmount;\nuniform float uBlockSize;\nuniform float uSmear;",
      body = """
  vec2 uv = clamp(vTexSamplingCoord, 0.0, 1.0);
  vec2 res = 1.0 / max(uTexelSize, vec2(1.0e-5));
  vec4 base = texture2D(uTexSampler, uv);
  vec2 blocks = max(res / max(uBlockSize, 6.0), vec2(2.0));
  vec2 cell = floor(uv * blocks);
  float tick = floor(uTime * 6.0);
  float h = hash21(cell + vec2(tick * 0.37, tick * 0.11));
  float active = step(1.0 - clamp(uSmear, 0.0, 1.0), h);
  vec2 dir = vec2(hash21(cell + 2.3) - 0.5, hash21(cell + 7.9) - 0.5) * 2.0;
  vec2 cellCenter = (cell + 0.5) / blocks;
  vec3 pulled = texture2D(uTexSampler, clamp(cellCenter + dir * uAmount * active, 0.0, 1.0)).rgb;
  vec3 rgb = mix(base.rgb, pulled, active * 0.85);
  float tear = step(0.86, h);
  rgb.r = texture2D(uTexSampler, clamp(uv + vec2(uAmount * 0.5 * tear, 0.0), 0.0, 1.0)).r;
  rgb.b = texture2D(uTexSampler, clamp(uv - vec2(uAmount * 0.5 * tear, 0.0), 0.0, 1.0)).b;
  gl_FragColor = vec4(clamp(mix(base.rgb, rgb, uIntensity), 0.0, 1.0), base.a);
""",
      binder = { program, _, _, _ ->
        program.setFloatUniform("uAmount", amount)
        program.setFloatUniform("uBlockSize", blockSize)
        program.setFloatUniform("uSmear", smear)
      }
    )

  private fun anamorphic(intensity: Float, amount: Float, threshold: Float, length: Float, tint: FloatArray): CustomShaderGlEffect =
    shader(
      name = "PreviewAnamorphicStreaks",
      intensity = intensity,
      uniforms = "uniform float uAmount;\nuniform float uThreshold;\nuniform float uLength;\nuniform vec3 uTint;",
      body = """
  vec2 uv = clamp(vTexSamplingCoord, 0.0, 1.0);
  vec4 base = texture2D(uTexSampler, uv);
  vec3 streak = vec3(0.0);
  float total = 0.0;
  for (int i = 0; i < 17; i++) {
    float t = float(i) / 16.0 - 0.5;
    float w = exp(-abs(t) * 6.0);
    vec3 s = texture2D(uTexSampler, clamp(uv + vec2(t * uLength * 2.0, 0.0), 0.0, 1.0)).rgb;
    streak += s * w * smoothstep(uThreshold, uThreshold + 0.30, dot(s, vec3(0.2126, 0.7152, 0.0722)));
    total += w;
  }
  streak /= max(total, 1.0e-4);
  vec3 rgb = base.rgb + streak * uTint * uAmount * 2.4;
  gl_FragColor = vec4(clamp(mix(base.rgb, rgb, uIntensity), 0.0, 1.0), base.a);
""",
      binder = { program, _, _, _ ->
        program.setFloatUniform("uAmount", amount)
        program.setFloatUniform("uThreshold", threshold)
        program.setFloatUniform("uLength", length)
        program.setFloatsUniform("uTint", floatArrayOf(tint[0], tint[1], tint[2]))
      }
    )

  private fun filmGrainPro(intensity: Float, amount: Float, size: Float, shadow: Float, chroma: Float): CustomShaderGlEffect =
    shader(
      name = "PreviewFilmGrainPro",
      intensity = intensity,
      uniforms = "uniform float uAmount;\nuniform float uSize;\nuniform float uShadow;\nuniform float uChroma;",
      body = """
  vec2 uv = clamp(vTexSamplingCoord, 0.0, 1.0);
  vec2 res = 1.0 / max(uTexelSize, vec2(1.0e-5));
  vec4 base = texture2D(uTexSampler, uv);
  float frame = floor(uTime * 24.0);
  vec2 p = uv * res / max(uSize, 0.25);
  float n = hash21(floor(p) + vec2(frame * 7.13, frame * 3.71)) - 0.5;
  float n2 = hash21(floor(p * 1.7) + vec2(frame * 2.9, frame * 5.1)) - 0.5;
  float l = dot(base.rgb, vec3(0.2126, 0.7152, 0.0722));
  float shape = mix(1.0, 1.6 - 1.4 * smoothstep(0.0, 0.7, l), clamp(uShadow, 0.0, 1.0));
  float amt = uAmount * shape;
  vec3 rgb = base.rgb + n * amt + vec3(n2 - n) * amt * clamp(uChroma, 0.0, 1.0) * 0.5;
  gl_FragColor = vec4(clamp(mix(base.rgb, rgb, uIntensity), 0.0, 1.0), base.a);
""",
      binder = { program, _, _, _ ->
        program.setFloatUniform("uAmount", amount)
        program.setFloatUniform("uSize", size)
        program.setFloatUniform("uShadow", shadow)
        program.setFloatUniform("uChroma", chroma)
      }
    )

  private fun halation(intensity: Float, amount: Float, radius: Float, threshold: Float, tint: FloatArray): CustomShaderGlEffect =
    shader(
      name = "PreviewHalation",
      intensity = intensity,
      uniforms = "uniform float uAmount;\nuniform float uRadius;\nuniform float uThreshold;\nuniform vec3 uTint;",
      body = """
  vec2 uv = clamp(vTexSamplingCoord, 0.0, 1.0);
  vec4 base = texture2D(uTexSampler, uv);
  vec3 glow = vec3(0.0);
  float total = 0.0;
  for (int i = 0; i < 12; i++) {
    float a = float(i) * 0.52359878;
    float w = 1.0 - float(i) / 18.0;
    vec3 s = texture2D(uTexSampler, clamp(uv + vec2(cos(a), sin(a)) * uRadius, 0.0, 1.0)).rgb;
    glow += s * w * smoothstep(uThreshold, uThreshold + 0.30, dot(s, vec3(0.2126, 0.7152, 0.0722)));
    total += w;
  }
  for (int i = 0; i < 6; i++) {
    float a = float(i) * 1.04719755 + 0.4;
    vec3 s = texture2D(uTexSampler, clamp(uv + vec2(cos(a), sin(a)) * uRadius * 2.2, 0.0, 1.0)).rgb;
    glow += s * 0.4 * smoothstep(uThreshold, uThreshold + 0.30, dot(s, vec3(0.2126, 0.7152, 0.0722)));
    total += 0.4;
  }
  glow = glow / max(total, 1.0e-4) * uTint;
  vec3 rgb = 1.0 - (1.0 - base.rgb) * (1.0 - clamp(glow * uAmount * 2.4, 0.0, 1.0));
  gl_FragColor = vec4(clamp(mix(base.rgb, rgb, uIntensity), 0.0, 1.0), base.a);
""",
      binder = { program, _, _, _ ->
        program.setFloatUniform("uAmount", amount)
        program.setFloatUniform("uRadius", radius)
        program.setFloatUniform("uThreshold", threshold)
        program.setFloatsUniform("uTint", floatArrayOf(tint[0], tint[1], tint[2]))
      }
    )

  private fun lensFlare(
    intensity: Float,
    position: FloatArray,
    amount: Float,
    halo: Float,
    ghosts: Float,
    tint: FloatArray,
  ): CustomShaderGlEffect = shader(
    name = "PreviewLensFlare",
    intensity = intensity,
    uniforms = "uniform vec2 uPosition;\nuniform float uAmount;\nuniform float uHalo;\nuniform float uGhosts;\nuniform vec3 uTint;",
    body = """
  vec2 uv = clamp(vTexSamplingCoord, 0.0, 1.0);
  vec4 base = texture2D(uTexSampler, uv);
  vec2 toCenter = vec2(0.5) - uPosition;
  vec2 luv = uv - uPosition;
  vec3 flare = uTint * exp(-length(luv) * 4.0) * uHalo;
  vec3 ghosts = vec3(0.0);
  for (int i = 1; i <= 3; i++) {
    float k = float(i) * 0.55;
    vec2 gp = uPosition + toCenter * (2.0 * k);
    ghosts += uTint * exp(-length(uv - gp) * 22.0) * (0.65 / float(i));
  }
  float bar = exp(-abs(luv.y) * 60.0) * exp(-abs(luv.x) * 6.0);
  flare += ghosts * uGhosts + uTint * bar * uGhosts * 0.7;
  vec3 rgb = base.rgb + flare * uAmount * 1.8;
  gl_FragColor = vec4(clamp(mix(base.rgb, rgb, uIntensity), 0.0, 1.0), base.a);
""",
    binder = { program, _, _, _ ->
      program.setFloatsUniform("uPosition", floatArrayOf(position[0], position[1]))
      program.setFloatUniform("uAmount", amount)
      program.setFloatUniform("uHalo", halo)
      program.setFloatUniform("uGhosts", ghosts)
      program.setFloatsUniform("uTint", floatArrayOf(tint[0], tint[1], tint[2]))
    }
  )

  private fun speedLines(
    intensity: Float,
    center: FloatArray,
    amount: Float,
    lines: Float,
    speed: Float,
    reach: Float,
  ): CustomShaderGlEffect = shader(
    name = "PreviewSpeedLines",
    intensity = intensity,
    uniforms = "uniform vec2 uCenter;\nuniform float uAmount;\nuniform float uLines;\nuniform float uSpeed;\nuniform float uReach;",
    body = """
  vec2 uv = clamp(vTexSamplingCoord, 0.0, 1.0);
  vec4 base = texture2D(uTexSampler, uv);
  vec2 d = uv - uCenter;
  float r = length(d);
  vec3 streak = vec3(0.0);
  for (int i = 0; i < 10; i++) {
    float t = float(i) / 9.0;
    streak += texture2D(uTexSampler, clamp(uv - d * t * uReach, 0.0, 1.0)).rgb;
  }
  streak /= 10.0;
  float line = 0.0;
  if (r > 1.0e-4) {
    float angle = atan(d.y, d.x) / 6.2831853 + 0.5;
    float seg = floor(angle * max(uLines, 2.0));
    line = smoothstep(0.55, 1.0, hash21(vec2(seg, floor(uTime * max(uSpeed, 0.0) * 6.0))));
  }
  float mask = line * clamp(uAmount, 0.0, 1.0) * smoothstep(0.02, 0.32, r);
  vec3 rgb = mix(base.rgb, streak, mask) + line * mask * 0.05;
  gl_FragColor = vec4(clamp(mix(base.rgb, rgb, uIntensity), 0.0, 1.0), base.a);
""",
    binder = { program, _, _, _ ->
      program.setFloatsUniform("uCenter", floatArrayOf(center[0], center[1]))
      program.setFloatUniform("uAmount", amount)
      program.setFloatUniform("uLines", lines)
      program.setFloatUniform("uSpeed", speed)
      program.setFloatUniform("uReach", reach)
    }
  )

  private fun kaleidoscope(
    intensity: Float,
    center: FloatArray,
    segments: Float,
    rotation: Float,
    zoom: Float,
  ): CustomShaderGlEffect = shader(
    name = "PreviewKaleidoscope",
    intensity = intensity,
    uniforms = "uniform vec2 uCenter;\nuniform float uSegments;\nuniform float uRotation;\nuniform float uZoom;",
    body = """
  vec2 uv = clamp(vTexSamplingCoord, 0.0, 1.0);
  vec4 base = texture2D(uTexSampler, uv);
  vec2 p = uv - uCenter;
  float r = length(p) / max(uZoom, 0.05);
  vec2 warped = uv;
  if (r > 1.0e-5) {
    float wedge = 6.2831853 / max(uSegments, 2.0);
    float a = atan(p.y, p.x) + radians(uRotation);
    a = mod(a, wedge);
    a = abs(a - wedge * 0.5);
    warped = clamp(uCenter + vec2(cos(a), sin(a)) * r, 0.0, 1.0);
  }
  vec2 s = mix(uv, warped, clamp(uIntensity, 0.0, 1.0));
  vec3 rgb = texture2D(uTexSampler, s).rgb;
  gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), base.a);
""",
    binder = { program, _, _, _ ->
      program.setFloatsUniform("uCenter", floatArrayOf(center[0], center[1]))
      program.setFloatUniform("uSegments", segments)
      program.setFloatUniform("uRotation", rotation)
      program.setFloatUniform("uZoom", zoom)
    }
  )

  // ==========================================================================
  // Legacy / engine-only preview shaders (VFX stack panel, Photo + AI assets)
  // ==========================================================================

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

  /**
   * Builds one Media3 shader node.
   *
   * `uIntensity` (blend, mirroring the export template) plus `uTime` (clip time for the temporal
   * effects) and `uTexelSize` (pixel maths) are declared **and set** only when the body actually
   * reads them: Media3's `GlProgram` throws when a uniform is set that the driver did not report
   * as active, and a declared-but-unused uniform is optimised out on most drivers. That throw
   * would swap the node for the identity blit, so the effect would silently vanish. `hash21` is
   * shared with the compositor's noise chunk so preview grain matches export.
   */
  private fun shader(
    name: String,
    intensity: Float,
    body: String,
    uniforms: String = "",
    binder: (program: GlProgram, presentationTimeUs: Long, width: Int, height: Int) -> Unit = { _, _, _, _ -> },
  ): CustomShaderGlEffect {
    val usesIntensity = body.contains("uIntensity")
    val usesTime = body.contains("uTime")
    val usesTexel = body.contains("uTexelSize")
    val fragment = buildString {
      append("#version 100\n")
      append("precision mediump float;\n")
      append("uniform sampler2D uTexSampler;\n")
      if (usesIntensity) append("uniform float uIntensity;\n")
      if (usesTime) append("uniform float uTime;\n")
      if (usesTexel) append("uniform vec2 uTexelSize;\n")
      if (uniforms.isNotBlank()) append(uniforms).append('\n')
      append("varying vec2 vTexSamplingCoord;\n\n")
      append("float hash21(vec2 p) {\n")
      append("  return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);\n")
      append("}\n\n")
      append("void main() {\n")
      append(body)
      append("\n}\n")
    }
    return CustomShaderGlEffect(
      name = name,
      fragmentShader = fragment,
      uniformBinder = { program, presentationTimeUs, width, height ->
        if (usesIntensity) program.setFloatUniform("uIntensity", intensity)
        if (usesTime) program.setFloatUniform("uTime", (presentationTimeUs / 1_000_000.0).toFloat())
        if (usesTexel) {
          val w = if (width > 0) width.toFloat() else 1920f
          val h = if (height > 0) height.toFloat() else 1080f
          program.setFloatsUniform("uTexelSize", floatArrayOf(1f / w, 1f / h))
        }
        binder(program, presentationTimeUs, width, height)
      }
    )
  }
}
