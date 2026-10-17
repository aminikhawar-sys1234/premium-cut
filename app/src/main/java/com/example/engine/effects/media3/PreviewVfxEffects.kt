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

      // ---- CapCut-trending assets (TrendGlsl mirrors, full-frame GPU shaders). ----
      // Every trend id has a real Media3 shader so an effect project that CANNOT run the GPU
      // compositor (synthetic clips, exhausted decoders) still previews the effect on the player
      // path instead of silently showing nothing. Subject-driven ids (subject.*) have no mirror:
      // they need the on-device segmentation mask, which only the compositor publishes.
      "trend.explosion" -> trendExplosion(
        intensity = intensity,
        scale = instance.param("scale", 0.7f),
        speed = instance.param("speed", 0.5f),
        heat = instance.param("heat", 1f)
      )
      "trend.ink_spill" -> trendInkSpill(
        intensity = intensity,
        spread = instance.param("spread", 0.9f),
        speed = instance.param("speed", 0.22f),
        dark = instance.param("dark", 0.92f)
      )
      "trend.earth" -> trendEarth(
        intensity = intensity,
        size = instance.param("size", 0.24f),
        center = instance.param("position", floatArrayOf(0.72f, 0.30f)),
        spin = instance.param("spin", 0.18f),
        clouds = instance.param("clouds", 0.55f)
      )
      "trend.magical_tome" -> trendMagicalTome(
        intensity = intensity,
        scale = instance.param("scale", 1f),
        speed = instance.param("speed", 0.35f),
        hue = instance.param("hue", 0.15f)
      )
      "trend.cut_shift" -> trendCutShift(
        intensity = intensity,
        slices = instance.param("slices", 10f),
        speed = instance.param("speed", 2.2f),
        rgb = instance.param("rgb", 0.55f)
      )
      "trend.fire" -> trendFire(
        intensity = intensity,
        height = instance.param("height", 0.38f),
        speed = instance.param("speed", 1.1f),
        heat = instance.param("heat", 1f)
      )
      "trend.mini_zoom" -> trendMiniZoom(
        intensity = intensity,
        speed = instance.param("speed", 0.75f),
        amount = instance.param("amount", 0.16f),
        shake = instance.param("shake", 0.02f)
      )
      "trend.white_flash" -> trendWhiteFlash(
        intensity = intensity,
        speed = instance.param("speed", 0.9f),
        hold = instance.param("hold", 0.05f),
        bloom = instance.param("bloom", 0.35f)
      )
      "trend.expand_x" -> trendExpandX(
        intensity = intensity,
        expand = instance.param("expand", 0.14f),
        falloff = instance.param("falloff", 0.55f)
      )
      "trend.orbit360" -> trendOrbit360(
        intensity = intensity,
        speed = instance.param("speed", 0.18f),
        reach = instance.param("reach", 0.5f),
        twist = instance.param("twist", 0.2f)
      )
      "trend.layer_echo" -> trendLayerEcho(
        intensity = intensity,
        offset = instance.param("offset", 1f),
        scaleStep = instance.param("scaleStep", 0.6f),
        mixAmt = instance.param("mixAmt", 0.55f)
      )
      "trend.zoom3d" -> trendZoom3d(
        intensity = intensity,
        zoom = instance.param("zoom", 0.22f),
        depth = instance.param("depth", 0.6f),
        speed = instance.param("speed", 0.14f)
      )
      "trend.ai_filter" -> trendAiFilter(
        intensity = intensity,
        warmth = instance.param("warmth", 0.65f),
        glow = instance.param("glow", 0.4f),
        pop = instance.param("pop", 0.6f)
      )
      "trend.sad" -> trendSad(
        intensity = intensity,
        mood = instance.param("mood", 0.75f),
        vig = instance.param("vig", 0.5f),
        grain = instance.param("grain", 0.4f)
      )
      "trend.cartoon3d" -> trendCartoon3d(
        intensity = intensity,
        levels = instance.param("levels", 6f),
        outline = instance.param("outline", 0.75f),
        sat = instance.param("sat", 0.7f)
      )

      // ---- Engine effects used by the Photo / AI assets and the VFX stack panel. ----
      "distort.glitch" -> Media3EffectPipeline.createGlitchEffect(intensity)
      "chromatic.aberration", "chromatic.rgbSplit" -> Media3EffectPipeline.createRgbSplitEffect(intensity)
      "blur.directional" -> directionalBlur(intensity)
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
   * The compositor's noise/colour library ([com.vfx.engine.gpu.glsl.ShaderChunks]) subset the
   * trend mirrors need, minus `hash21` (already declared by [shader]). Injected through the
   * `uniforms` slot, which lands after `hash21` and before `main`.
   */
  private val TREND_LIB = """
float hash11(float p){ p = fract(p * 0.1031); p *= p + 33.33; p *= p + p; return fract(p); }
float hash31(vec3 p){ p = fract(p * 0.1031); p += dot(p, p.yzx + 33.33); return fract((p.x + p.y) * p.z); }
float valueNoise(vec2 p){
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash21(i), hash21(i + vec2(1.0, 0.0)), f.x),
               mix(hash21(i + vec2(0.0, 1.0)), hash21(i + vec2(1.0, 1.0)), f.x), f.y);
}
float fbm(vec2 p){
    float v = 0.0, a = 0.5;
    for (int i = 0; i < 5; i++) { v += a * valueNoise(p); p = p * 2.03 + 17.31; a *= 0.5; }
    return v;
}
float vnoise3(vec3 p){
    vec3 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float n000 = hash31(i);
    float n100 = hash31(i + vec3(1.0, 0.0, 0.0));
    float n010 = hash31(i + vec3(0.0, 1.0, 0.0));
    float n110 = hash31(i + vec3(1.0, 1.0, 0.0));
    float n001 = hash31(i + vec3(0.0, 0.0, 1.0));
    float n101 = hash31(i + vec3(1.0, 0.0, 1.0));
    float n011 = hash31(i + vec3(0.0, 1.0, 1.0));
    float n111 = hash31(i + vec3(1.0, 1.0, 1.0));
    return mix(mix(mix(n000, n100, f.x), mix(n010, n110, f.x), f.y),
               mix(mix(n001, n101, f.x), mix(n011, n111, f.x), f.y), f.z);
}
float fbm3(vec3 p){
    float v = 0.0, a = 0.5;
    for (int i = 0; i < 4; i++) { v += a * vnoise3(p); p = p * 2.03 + 17.31; a *= 0.5; }
    return v;
}
mat2 rot2(float a){ float c = cos(a), s = sin(a); return mat2(c, -s, s, c); }
float luma709(vec3 c){ return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
float maxc(vec3 c){ return max(c.r, max(c.g, c.b)); }
float minc(vec3 c){ return min(c.r, min(c.g, c.b)); }
vec3 blendScreen(vec3 b, vec3 s){ return b + s - b * s; }
""".trimIndent()

  // ---- TrendGlsl mirrors ---------------------------------------------------------------
  // Faithful ES1 ports of the compositor bodies in com.vfx.engine.gpu.glsl.TrendGlsl: the warp
  // (uv displacement) is folded into the base sample, `process` maths runs on the original uv,
  // and the final `mix(base, result, uIntensity)` matches ShaderTemplate's main exactly, so the
  // player path renders the same frame the compositor and the export do.

  private fun trendExplosion(intensity: Float, scale: Float, speed: Float, heat: Float): CustomShaderGlEffect =
    shader(
      name = "PreviewTrendExplosion",
      intensity = intensity,
      uniforms = "uniform float uScale;\nuniform float uSpeed;\nuniform float uHeat;\n" + TREND_LIB,
      body = """
  vec2 uv = clamp(vTexSamplingCoord, 0.0, 1.0);
  vec2 res = 1.0 / max(uTexelSize, vec2(1.0e-5));
  vec4 base = texture2D(uTexSampler, uv);
  float t = fract(uTime * uSpeed);
  float aspect = res.x / max(res.y, 1.0);
  vec2 p = (uv - vec2(0.5)) * vec2(aspect, 1.0);
  float dist = max(length(p), 1.0e-4);
  float ringR = t * 1.15 * uScale;
  float ring = exp(-pow((dist - ringR) * 14.0, 2.0));
  vec2 dirP = p / dist;
  vec3 col = base.rgb + ring * 0.20 * dirP.x * vec3(1.0, 0.85, 0.6);
  float life = smoothstep(1.0, 0.15, t);
  float body = smoothstep(ringR, 0.0, dist) * life;
  float n = fbm(p * 6.0 + vec2(0.0, -t * 5.0) + 31.7);
  float radial = 1.0 - clamp(dist / max(ringR, 1.0e-3), 0.0, 1.0);
  float flame = body * smoothstep(0.35, 0.9, n + radial * 0.4) * uHeat;
  vec3 fireCol = mix(vec3(1.0, 0.30, 0.05), vec3(1.0, 0.85, 0.45), body);
  col = mix(col, max(col, fireCol), clamp(flame, 0.0, 1.0));
  col += exp(-t * 9.0) * 0.65;
  vec3 result = col * uIntensity + base.rgb * (1.0 - uIntensity);
  gl_FragColor = vec4(clamp(mix(base.rgb, result, uIntensity), 0.0, 1.0), base.a);
""",
      binder = { program, _, _, _ ->
        program.setFloatUniform("uScale", scale)
        program.setFloatUniform("uSpeed", speed)
        program.setFloatUniform("uHeat", heat)
      }
    )

  private fun trendInkSpill(intensity: Float, spread: Float, speed: Float, dark: Float): CustomShaderGlEffect =
    shader(
      name = "PreviewTrendInkSpill",
      intensity = intensity,
      uniforms = "uniform float uSpread;\nuniform float uSpeed;\nuniform float uDark;\n" + TREND_LIB,
      body = """
  vec2 uv = clamp(vTexSamplingCoord, 0.0, 1.0);
  vec2 res = 1.0 / max(uTexelSize, vec2(1.0e-5));
  vec4 base = texture2D(uTexSampler, uv);
  float t = fract(uTime * uSpeed);
  float env = sin(3.14159265 * min(t * 1.25, 1.0));
  float aspect = res.x / max(res.y, 1.0);
  vec2 p = (uv - vec2(0.5)) * vec2(aspect, 1.0);
  float dist = length(p);
  vec2 dirP = p / max(dist, 1.0e-4);
  float reach = env * uSpread * 1.4;
  float n = fbm(dirP * 7.0 + 11.3);
  float edge = reach * (0.75 + 0.5 * n);
  float ink = 1.0 - smoothstep(edge - 0.06, edge, dist);
  float sheen = ink * (1.0 - smoothstep(0.0, 0.10, edge - dist));
  vec3 inkCol = vec3(0.02, 0.02, 0.045) * (1.0 + 0.4 * (n - 0.5));
  vec3 stained = mix(inkCol, vec3(0.30, 0.34, 0.42), sheen * 0.35);
  vec3 col = mix(base.rgb, stained, clamp(ink * uDark, 0.0, 1.0));
  vec3 result = col * uIntensity + base.rgb * (1.0 - uIntensity);
  gl_FragColor = vec4(clamp(mix(base.rgb, result, uIntensity), 0.0, 1.0), base.a);
""",
      binder = { program, _, _, _ ->
        program.setFloatUniform("uSpread", spread)
        program.setFloatUniform("uSpeed", speed)
        program.setFloatUniform("uDark", dark)
      }
    )

  private fun trendEarth(intensity: Float, size: Float, center: FloatArray, spin: Float, clouds: Float): CustomShaderGlEffect =
    shader(
      name = "PreviewTrendEarth",
      intensity = intensity,
      uniforms = "uniform float uSize;\nuniform vec2 uCenter;\nuniform float uSpin;\nuniform float uClouds;\n" + TREND_LIB,
      body = """
  vec2 uv = clamp(vTexSamplingCoord, 0.0, 1.0);
  vec2 res = 1.0 / max(uTexelSize, vec2(1.0e-5));
  vec4 base = texture2D(uTexSampler, uv);
  float aspect = res.x / max(res.y, 1.0);
  vec2 p = (uv - uCenter) * vec2(aspect, 1.0);
  float R = uSize;
  float dist = length(p);
  float inside = 1.0 - smoothstep(R - 0.002, R, dist);
  vec3 surf = base.rgb;
  if (inside > 0.0) {
    float z = sqrt(max(R * R - dist * dist, 0.0));
    vec3 nrm = vec3(p.x, p.y, z) / max(R, 1.0e-4);
    float lat = asin(clamp(nrm.y, -1.0, 1.0));
    float lon = atan(nrm.x, nrm.z) + uTime * uSpin;
    vec3 sphereP = vec3(cos(lat) * cos(lon), sin(lat), cos(lat) * sin(lon));
    float land = fbm3(sphereP * 2.6 + 5.0);
    land = smoothstep(0.50, 0.56, land);
    vec3 ocean = mix(vec3(0.02, 0.09, 0.28), vec3(0.03, 0.20, 0.42), fbm3(sphereP * 5.0) * 0.7 + 0.3);
    vec3 ground = mix(vec3(0.10, 0.32, 0.10), vec3(0.55, 0.45, 0.22), fbm3(sphereP * 6.5 + 3.0));
    surf = mix(ocean, ground, land);
    float cl = smoothstep(0.52, 0.74, fbm3(sphereP * 3.4 + vec3(uTime * uSpin * 0.45, 0.0, 17.0)));
    surf = mix(surf, vec3(0.95), cl * uClouds);
    float diff = clamp(dot(nrm, normalize(vec3(-0.45, 0.55, 0.7))), 0.0, 1.0);
    surf *= 0.22 + 0.88 * diff;
    float rim = pow(1.0 - z / max(R, 1.0e-4), 2.5);
    surf += rim * vec3(0.35, 0.55, 0.9) * 0.8;
    surf = mix(base.rgb, surf, inside);
  }
  vec3 result = surf;
  gl_FragColor = vec4(clamp(mix(base.rgb, result, uIntensity), 0.0, 1.0), base.a);
""",
      binder = { program, _, _, _ ->
        program.setFloatUniform("uSize", size)
        program.setFloatsUniform("uCenter", floatArrayOf(center[0], center[1]))
        program.setFloatUniform("uSpin", spin)
        program.setFloatUniform("uClouds", clouds)
      }
    )

  private fun trendMagicalTome(intensity: Float, scale: Float, speed: Float, hue: Float): CustomShaderGlEffect =
    shader(
      name = "PreviewTrendMagicalTome",
      intensity = intensity,
      uniforms = "uniform float uScale;\nuniform float uSpeed;\nuniform float uHue;\n" + TREND_LIB,
      body = """
  vec2 uv = clamp(vTexSamplingCoord, 0.0, 1.0);
  vec2 res = 1.0 / max(uTexelSize, vec2(1.0e-5));
  vec4 base = texture2D(uTexSampler, uv);
  float t = fract(uTime * uSpeed);
  float aspect = res.x / max(res.y, 1.0);
  vec2 p = (uv - vec2(0.5, 0.45)) * vec2(aspect, 1.0);
  float r = length(p);
  float ang = atan(p.y, p.x);
  float glow = 0.0;
  for (int i = 0; i < 3; i++) {
    float fi = float(i);
    float rr = uScale * (0.16 + 0.10 * fi);
    float spin = uTime * (0.6 + 0.35 * fi) * (mod(fi, 2.0) * 2.0 - 1.0);
    float ticks = smoothstep(0.45, 0.95, sin((ang + spin) * (9.0 + 6.0 * fi)));
    glow += exp(-pow((r - rr) * 90.0, 2.0)) * (0.35 + 0.65 * ticks);
  }
  glow += exp(-pow((r - uScale * 0.10) * 110.0, 2.0)) * 1.3;
  float spark = 0.0;
  for (int i = 0; i < 6; i++) {
    float fi = float(i);
    float seed = hash11(fi + 7.31);
    float sr = uScale * (0.14 + 0.34 * seed);
    float sa = fi * 1.0471976 + uTime * (0.8 + seed) + seed * 6.2831853;
    vec2 sp = vec2(cos(sa), sin(sa)) * sr;
    float d = length(p - sp);
    float tw = 0.55 + 0.45 * sin(uTime * (6.0 + 5.0 * seed) + fi);
    spark += exp(-d * d * 2600.0) * tw;
  }
  glow += spark * 0.9;
  vec3 tint = mix(vec3(0.55, 0.25, 0.95), vec3(1.0, 0.78, 0.30), 0.5 + 0.5 * sin(uHue * 6.2831853 + r * 3.0));
  float breathe = 0.55 + 0.45 * sin(3.14159265 * t);
  vec3 col = base.rgb + tint * glow * breathe;
  vec3 result = col * uIntensity + base.rgb * (1.0 - uIntensity);
  gl_FragColor = vec4(clamp(mix(base.rgb, result, uIntensity), 0.0, 1.0), base.a);
""",
      binder = { program, _, _, _ ->
        program.setFloatUniform("uScale", scale)
        program.setFloatUniform("uSpeed", speed)
        program.setFloatUniform("uHue", hue)
      }
    )

  private fun trendCutShift(intensity: Float, slices: Float, speed: Float, rgb: Float): CustomShaderGlEffect =
    shader(
      name = "PreviewTrendCutShift",
      intensity = intensity,
      uniforms = "uniform float uSlices;\nuniform float uSpeed;\nuniform float uRgb;\n" + TREND_LIB,
      body = """
  vec2 uv = clamp(vTexSamplingCoord, 0.0, 1.0);
  vec4 base = texture2D(uTexSampler, uv);
  float stepN = floor(uTime * uSpeed);
  float rows = max(uSlices, 2.0);
  float row = floor(uv.y * rows);
  float gate = step(0.55, hash21(vec2(row * 1.7, stepN * 1.3)));
  float amt = (hash21(vec2(row, stepN)) - 0.5) * 0.5 * (0.3 + 0.7 * hash11(stepN + 3.1)) * gate;
  vec2 wuv = clamp(mix(uv, uv + vec2(amt, 0.0), clamp(uIntensity, 0.0, 1.0)), 0.0, 1.0);
  base = texture2D(uTexSampler, wuv);
  float split = uRgb * (0.004 + 0.010 * hash11(stepN + 9.7));
  float r = texture2D(uTexSampler, clamp(uv + vec2(split, 0.0), 0.0, 1.0)).r;
  float b = texture2D(uTexSampler, clamp(uv - vec2(split, 0.0), 0.0, 1.0)).b;
  vec3 result = vec3(r, base.g, b);
  gl_FragColor = vec4(clamp(mix(base.rgb, result, uIntensity), 0.0, 1.0), base.a);
""",
      binder = { program, _, _, _ ->
        program.setFloatUniform("uSlices", slices)
        program.setFloatUniform("uSpeed", speed)
        program.setFloatUniform("uRgb", rgb)
      }
    )

  private fun trendFire(intensity: Float, height: Float, speed: Float, heat: Float): CustomShaderGlEffect =
    shader(
      name = "PreviewTrendFire",
      intensity = intensity,
      uniforms = "uniform float uHeight;\nuniform float uSpeed;\nuniform float uHeat;\n" + TREND_LIB,
      body = """
  vec2 uv = clamp(vTexSamplingCoord, 0.0, 1.0);
  vec4 base = texture2D(uTexSampler, uv);
  float t = uTime * uSpeed;
  float m = smoothstep(1.0 - uHeight * 1.8, 0.0, uv.y);
  float w = fbm(vec2(uv.x * 6.0, uv.y * 4.0 - t * 2.0)) - 0.5;
  vec2 wuv = clamp(mix(uv, uv + vec2(w * 0.014, 0.0) * m, clamp(uIntensity, 0.0, 1.0)), 0.0, 1.0);
  base = texture2D(uTexSampler, wuv);
  float band = smoothstep(1.0 - uHeight, 1.05, 1.0 - uv.y + 0.05);
  vec2 fp = vec2(uv.x * 3.0, uv.y * 3.0 + t * 1.6);
  float n = fbm(fp);
  float n2 = fbm(fp * 2.1 + vec2(7.7, -t * 2.4));
  float flame = band * smoothstep(0.35, 0.85, n * 0.65 + n2 * 0.45);
  flame = pow(flame, 1.4);
  vec3 fireCol = mix(vec3(1.0, 0.25, 0.02), vec3(1.0, 0.9, 0.35), clamp(flame * 0.8 + n2 * 0.2, 0.0, 1.0));
  vec3 col = base.rgb + fireCol * flame * uHeat;
  float ember = step(0.985, hash21(floor(uv * vec2(220.0, 220.0)) + floor(t * 14.0)));
  col += vec3(1.0, 0.6, 0.2) * ember * band * 1.2;
  vec3 result = col * uIntensity + base.rgb * (1.0 - uIntensity);
  gl_FragColor = vec4(clamp(mix(base.rgb, result, uIntensity), 0.0, 1.0), base.a);
""",
      binder = { program, _, _, _ ->
        program.setFloatUniform("uHeight", height)
        program.setFloatUniform("uSpeed", speed)
        program.setFloatUniform("uHeat", heat)
      }
    )

  private fun trendMiniZoom(intensity: Float, speed: Float, amount: Float, shake: Float): CustomShaderGlEffect =
    shader(
      name = "PreviewTrendMiniZoom",
      intensity = intensity,
      uniforms = "uniform float uSpeed;\nuniform float uAmount;\nuniform float uShake;\n" + TREND_LIB,
      body = """
  vec2 uv = clamp(vTexSamplingCoord, 0.0, 1.0);
  float t = fract(uTime * uSpeed);
  float z = 1.0 + uAmount * pow(1.0 - t, 3.0);
  float j = uShake * pow(1.0 - t, 5.0);
  vec2 off = (vec2(hash11(floor(uTime * uSpeed) + 1.7), hash11(floor(uTime * uSpeed) + 5.3)) - 0.5) * j;
  vec2 wuv = clamp(mix(uv, (uv - vec2(0.5)) / max(z, 0.1) + vec2(0.5) + off, clamp(uIntensity, 0.0, 1.0)), 0.0, 1.0);
  vec4 base = texture2D(uTexSampler, wuv);
  float env = pow(1.0 - t, 3.0);
  vec2 res = 1.0 / max(uTexelSize, vec2(1.0e-5));
  float aspect = res.x / max(res.y, 1.0);
  float d = length((uv - 0.5) * vec2(aspect, 1.0));
  vec3 result = base.rgb * (1.0 - 0.35 * env * smoothstep(0.3, 0.85, d));
  gl_FragColor = vec4(clamp(mix(base.rgb, result, uIntensity), 0.0, 1.0), base.a);
""",
      binder = { program, _, _, _ ->
        program.setFloatUniform("uSpeed", speed)
        program.setFloatUniform("uAmount", amount)
        program.setFloatUniform("uShake", shake)
      }
    )

  private fun trendWhiteFlash(intensity: Float, speed: Float, hold: Float, bloom: Float): CustomShaderGlEffect =
    shader(
      name = "PreviewTrendWhiteFlash",
      intensity = intensity,
      uniforms = "uniform float uSpeed;\nuniform float uHold;\nuniform float uBloom;\n" + TREND_LIB,
      body = """
  vec2 uv = clamp(vTexSamplingCoord, 0.0, 1.0);
  vec4 base = texture2D(uTexSampler, uv);
  float t = fract(uTime * uSpeed);
  float attack = smoothstep(0.0, 0.02, t);
  float decay = exp(-max(t - uHold, 0.0) * 14.0);
  float flash = attack * decay;
  vec3 col = mix(base.rgb, vec3(1.0), clamp(flash, 0.0, 1.0));
  col += uBloom * decay * 0.25 * (1.0 - luma709(base.rgb));
  vec3 result = col * uIntensity + base.rgb * (1.0 - uIntensity);
  gl_FragColor = vec4(clamp(mix(base.rgb, result, uIntensity), 0.0, 1.0), base.a);
""",
      binder = { program, _, _, _ ->
        program.setFloatUniform("uSpeed", speed)
        program.setFloatUniform("uHold", hold)
        program.setFloatUniform("uBloom", bloom)
      }
    )

  private fun trendExpandX(intensity: Float, expand: Float, falloff: Float): CustomShaderGlEffect =
    shader(
      name = "PreviewTrendExpandX",
      intensity = intensity,
      uniforms = "uniform float uExpand;\nuniform float uFalloff;\n" + TREND_LIB,
      body = """
  vec2 uv = clamp(vTexSamplingCoord, 0.0, 1.0);
  float e = uExpand;
  float s = max(1.0 - 2.0 * e, 0.05);
  float t = (uv.x - e) / s;
  float x = (uv.x < e) ? -t : ((uv.x > 1.0 - e) ? 2.0 - t : t);
  vec2 wuv = clamp(mix(uv, vec2(x, uv.y), clamp(uIntensity, 0.0, 1.0)), 0.0, 1.0);
  vec4 base = texture2D(uTexSampler, wuv);
  float dEdge = clamp(max(e - uv.x, uv.x - (1.0 - e)) / max(e, 1.0e-4), 0.0, 1.0);
  float k = pow(dEdge, 1.5) * uFalloff;
  vec3 result = base.rgb * (1.0 - 0.22 * k);
  gl_FragColor = vec4(clamp(mix(base.rgb, result, uIntensity), 0.0, 1.0), base.a);
""",
      binder = { program, _, _, _ ->
        program.setFloatUniform("uExpand", expand)
        program.setFloatUniform("uFalloff", falloff)
      }
    )

  private fun trendOrbit360(intensity: Float, speed: Float, reach: Float, twist: Float): CustomShaderGlEffect =
    shader(
      name = "PreviewTrendOrbit360",
      intensity = intensity,
      uniforms = "uniform float uSpeed;\nuniform float uReach;\nuniform float uTwist;\n" + TREND_LIB,
      body = """
  vec2 uv = clamp(vTexSamplingCoord, 0.0, 1.0);
  float w = sin(uTime * uSpeed * 6.2831853);
  float k = 1.0 + 1.6 * uReach;
  float x = (uv.x - 0.5) * k + w * uReach;
  float xx = atan(x) / atan(k) * 0.5 + 0.5;
  float yy = (uv.y - 0.5) / (1.0 + 0.15 * uReach * (1.0 - w * 0.5)) + 0.5;
  vec2 rr = rot2(uTwist * 0.35 * w) * vec2(xx - 0.5, yy - 0.5);
  vec2 wuv = clamp(mix(uv, rr + 0.5, clamp(uIntensity, 0.0, 1.0)), 0.0, 1.0);
  vec4 base = texture2D(uTexSampler, wuv);
  float d = abs(uv.x - 0.5) * 2.0;
  vec3 result = base.rgb * (1.0 - 0.25 * uReach * d * d);
  gl_FragColor = vec4(clamp(mix(base.rgb, result, uIntensity), 0.0, 1.0), base.a);
""",
      binder = { program, _, _, _ ->
        program.setFloatUniform("uSpeed", speed)
        program.setFloatUniform("uReach", reach)
        program.setFloatUniform("uTwist", twist)
      }
    )

  private fun trendLayerEcho(intensity: Float, offset: Float, scaleStep: Float, mixAmt: Float): CustomShaderGlEffect =
    shader(
      name = "PreviewTrendLayerEcho",
      intensity = intensity,
      uniforms = "uniform float uOffset;\nuniform float uScaleStep;\nuniform float uMixAmt;\n" + TREND_LIB,
      body = """
  vec2 uv = clamp(vTexSamplingCoord, 0.0, 1.0);
  vec4 base = texture2D(uTexSampler, uv);
  vec3 acc = base.rgb;
  vec2 ctr = vec2(0.5);
  for (int i = 1; i <= 3; i++) {
    float fi = float(i);
    float sc = max(1.0 - uScaleStep * fi * 0.15, 0.2);
    vec2 o = vec2(uOffset * fi * 0.02, -uOffset * fi * 0.012);
    vec2 s = clamp((uv - ctr) / sc + ctr + o, 0.0, 1.0);
    vec3 layer = texture2D(uTexSampler, s).rgb;
    acc = mix(acc, blendScreen(acc, layer), pow(0.62, fi) * uMixAmt);
  }
  gl_FragColor = vec4(clamp(mix(base.rgb, acc, uIntensity), 0.0, 1.0), base.a);
""",
      binder = { program, _, _, _ ->
        program.setFloatUniform("uOffset", offset)
        program.setFloatUniform("uScaleStep", scaleStep)
        program.setFloatUniform("uMixAmt", mixAmt)
      }
    )

  private fun trendZoom3d(intensity: Float, zoom: Float, depth: Float, speed: Float): CustomShaderGlEffect =
    shader(
      name = "PreviewTrendZoom3d",
      intensity = intensity,
      uniforms = "uniform float uZoom;\nuniform float uDepth;\nuniform float uSpeed;\n" + TREND_LIB,
      body = """
  vec2 uv = clamp(vTexSamplingCoord, 0.0, 1.0);
  float push = (sin(uTime * uSpeed * 3.14159265 - 1.5707963) + 1.0) * 0.5;
  float z = 1.0 + uZoom * (0.10 + 0.35 * push);
  float lum = luma709(texture2D(uTexSampler, clamp(uv, 0.0, 1.0)).rgb);
  float parallax = (lum - 0.5) * uDepth * 0.12 * push;
  vec2 drift = vec2(cos(uTime * 0.7), sin(uTime * 0.9)) * parallax * 0.5;
  vec2 wuv = clamp(mix(uv, (uv - 0.5) / max(z, 0.1) + 0.5 + drift, clamp(uIntensity, 0.0, 1.0)), 0.0, 1.0);
  vec4 base = texture2D(uTexSampler, wuv);
  gl_FragColor = vec4(clamp(base.rgb, 0.0, 1.0), base.a);
""",
      binder = { program, _, _, _ ->
        program.setFloatUniform("uZoom", zoom)
        program.setFloatUniform("uDepth", depth)
        program.setFloatUniform("uSpeed", speed)
      }
    )

  private fun trendAiFilter(intensity: Float, warmth: Float, glow: Float, pop: Float): CustomShaderGlEffect =
    shader(
      name = "PreviewTrendAiFilter",
      intensity = intensity,
      uniforms = "uniform float uWarmth;\nuniform float uGlow;\nuniform float uPop;\n" + TREND_LIB,
      body = """
  vec2 uv = clamp(vTexSamplingCoord, 0.0, 1.0);
  vec4 base = texture2D(uTexSampler, uv);
  vec2 texel = max(uTexelSize, vec2(1.0e-5));
  vec3 col = base.rgb;
  vec3 blur = (texture2D(uTexSampler, clamp(uv + vec2(texel.x, 0.0), 0.0, 1.0)).rgb
             + texture2D(uTexSampler, clamp(uv - vec2(texel.x, 0.0), 0.0, 1.0)).rgb
             + texture2D(uTexSampler, clamp(uv + vec2(0.0, texel.y), 0.0, 1.0)).rgb
             + texture2D(uTexSampler, clamp(uv - vec2(0.0, texel.y), 0.0, 1.0)).rgb) * 0.25;
  float detail = abs(luma709(col) - luma709(blur));
  col = mix(col, blur, clamp(0.65 - detail * 3.0, 0.0, 0.65));
  float l = luma709(col);
  vec3 shadowT = vec3(0.10, 0.24, 0.34);
  vec3 highT = vec3(1.0, 0.78, 0.52);
  vec3 toned = mix(col * shadowT * 2.2, highT * col * 1.4, smoothstep(0.35, 0.8, l));
  col = mix(col, toned, 0.35 * uWarmth + 0.10);
  float sat = maxc(col) - minc(col);
  col = mix(vec3(l), col, 1.0 + 0.35 * uPop * (1.0 - sat));
  col = clamp(col * 1.06 - 0.02, 0.0, 1.0);
  col += max(blur - 0.65, vec3(0.0)) * uGlow * 1.6;
  gl_FragColor = vec4(clamp(mix(base.rgb, col, uIntensity), 0.0, 1.0), base.a);
""",
      binder = { program, _, _, _ ->
        program.setFloatUniform("uWarmth", warmth)
        program.setFloatUniform("uGlow", glow)
        program.setFloatUniform("uPop", pop)
      }
    )

  private fun trendSad(intensity: Float, mood: Float, vig: Float, grain: Float): CustomShaderGlEffect =
    shader(
      name = "PreviewTrendSad",
      intensity = intensity,
      uniforms = "uniform float uMood;\nuniform float uVig;\nuniform float uGrain;\n" + TREND_LIB,
      body = """
  vec2 uv = clamp(vTexSamplingCoord, 0.0, 1.0);
  vec2 res = 1.0 / max(uTexelSize, vec2(1.0e-5));
  vec4 base = texture2D(uTexSampler, uv);
  vec3 col = base.rgb;
  float l = luma709(col);
  vec3 cold = mix(vec3(l), col, 0.45) * vec3(0.86, 0.94, 1.10);
  cold = mix(cold, vec3(0.10, 0.16, 0.26), (1.0 - smoothstep(0.0, 0.45, l)) * 0.35);
  col = mix(col, cold, uMood);
  col *= 1.0 - 0.06 * uMood * (0.5 + 0.5 * sin(uTime * 0.9));
  float aspect = res.x / max(res.y, 1.0);
  float d = length((uv - 0.5) * vec2(aspect, 1.0));
  col *= 1.0 - uVig * smoothstep(0.45, 0.95, d);
  col += (hash21(uv * res + fract(uTime) * 61.7) - 0.5) * uGrain * 0.14;
  gl_FragColor = vec4(clamp(mix(base.rgb, col, uIntensity), 0.0, 1.0), base.a);
""",
      binder = { program, _, _, _ ->
        program.setFloatUniform("uMood", mood)
        program.setFloatUniform("uVig", vig)
        program.setFloatUniform("uGrain", grain)
      }
    )

  private fun trendCartoon3d(intensity: Float, levels: Float, outline: Float, sat: Float): CustomShaderGlEffect =
    shader(
      name = "PreviewTrendCartoon3d",
      intensity = intensity,
      uniforms = "uniform float uLevels;\nuniform float uOutline;\nuniform float uSat;\n" + TREND_LIB,
      body = """
  vec2 uv = clamp(vTexSamplingCoord, 0.0, 1.0);
  vec4 base = texture2D(uTexSampler, uv);
  vec2 texel = max(uTexelSize, vec2(1.0e-5));
  float lx = luma709(texture2D(uTexSampler, clamp(uv + vec2(texel.x * 1.5, 0.0), 0.0, 1.0)).rgb)
           - luma709(texture2D(uTexSampler, clamp(uv - vec2(texel.x * 1.5, 0.0), 0.0, 1.0)).rgb);
  float ly = luma709(texture2D(uTexSampler, clamp(uv + vec2(0.0, texel.y * 1.5), 0.0, 1.0)).rgb)
           - luma709(texture2D(uTexSampler, clamp(uv - vec2(0.0, texel.y * 1.5), 0.0, 1.0)).rgb);
  float edge = clamp(length(vec2(lx, ly)) * 6.0 - 0.12, 0.0, 1.0);
  vec3 col = floor(base.rgb * uLevels + 0.5) / max(uLevels, 2.0);
  float l = luma709(col);
  col = mix(vec3(l), col, 1.0 + 0.5 * uSat);
  float band = min(fract(l * uLevels), 1.0 - fract(l * uLevels)) * 2.0;
  col *= 1.0 - 0.18 * (1.0 - band) * uSat;
  col = mix(col, vec3(0.06), edge * uOutline);
  gl_FragColor = vec4(clamp(mix(base.rgb, col, uIntensity), 0.0, 1.0), base.a);
""",
      binder = { program, _, _, _ ->
        program.setFloatUniform("uLevels", levels)
        program.setFloatUniform("uOutline", outline)
        program.setFloatUniform("uSat", sat)
      }
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
      // highp where the driver offers it (all modern GPUs): the trend mirrors reuse the
      // compositor's fbm/hash bodies, whose big hash constants collapse on mediump floats.
      append("#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n")
      append("uniform sampler2D uTexSampler;\n")
      if (usesIntensity) append("uniform float uIntensity;\n")
      if (usesTime) append("uniform float uTime;\n")
      if (usesTexel) append("uniform vec2 uTexelSize;\n")
      append("varying vec2 vTexSamplingCoord;\n\n")
      append("float hash21(vec2 p) {\n")
      append("  return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);\n")
      append("}\n\n")
      // Declared AFTER hash21 so a shared noise library in [uniforms] can call it.
      if (uniforms.isNotBlank()) append(uniforms).append('\n')
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
