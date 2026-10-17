package com.vfx.engine.gpu.glsl

/**
 * GLSL bodies for the subject-driven effects assets (Body Effects + AI Effects).
 *
 * These bodies read the on-device subject segmentation mask through `u_maskTex` and gate on
 * `u_hasMask`, both bound by `SubjectMaskRuntime` (declared there via the shader template's
 * extra uniforms — never redeclare them here). With no mask available the bodies pass the frame
 * through unchanged, so a missing model can never smear a body effect across the whole scene.
 *
 * [SUBJECT_COMMON] must precede every body in the composed shader; it defines the shared
 * mask/edge helpers so the per-effect code stays one-liners over the same primitives.
 */
object SubjectGlsl {

    /** Shared helpers: mask sample + mask-gradient edge. Prepended to every subject body. */
    const val SUBJECT_COMMON = """
float subjectMask(vec2 uv){ return texture(u_maskTex, clampUv(uv)).r * u_hasMask; }
float subjectEdge(vec2 uv){
    float mx = texture(u_maskTex, clampUv(uv + vec2(u_texel.x * 1.5, 0.0))).r
             - texture(u_maskTex, clampUv(uv - vec2(u_texel.x * 1.5, 0.0))).r;
    float my = texture(u_maskTex, clampUv(uv + vec2(0.0, u_texel.y * 1.5))).r
             - texture(u_maskTex, clampUv(uv - vec2(0.0, u_texel.y * 1.5))).r;
    return clamp(length(vec2(mx, my)) * 2.6, 0.0, 1.0);
}
"""

    // ---------------- BODY EFFECTS ----------------

    /** Scanning band sweeping the subject top-to-bottom with a tracking grid. One pass. */
    const val OPTICAL_SCAN = """
uniform float u_speed;
uniform float u_band;
uniform vec3 u_tint;
vec4 process(vec4 c, vec2 uv){
    float m = subjectMask(uv);
    float sweep = fract(u_time * u_speed);
    float bandPos = 1.0 - sweep;
    float band = exp(-pow((uv.y - bandPos) * (0.175 / max(u_band, 0.02)), 2.0));
    float line = exp(-pow((uv.y - bandPos) * 420.0, 2.0));
    float grid = step(0.5, fract(uv.y * 90.0)) * 0.25 + step(0.92, fract(uv.x * 60.0)) * 0.25;
    float glow = band * (0.35 + grid) + line * 1.6;
    vec3 col = c.rgb + u_tint * glow * 0.85 * m;
    col = mix(col, col * (1.0 + u_tint * 0.18), band * m);
    return vec4(col, c.a);
}"""

    /** Flickering offset ghost of the tracked person, composited beside the real body. One pass. */
    const val PHANTOM_CLONE = """
uniform float u_offset;
uniform float u_speed;
uniform float u_ghost;
vec4 process(vec4 c, vec2 uv){
    float m = subjectMask(uv);
    float phase = 0.5 + 0.5 * sin(u_time * u_speed * 3.14159265);
    float dir = sign(sin(u_time * u_speed * 3.14159265 + 0.4));
    vec2 gUv = vec2(uv.x - u_offset * dir * (0.4 + 0.6 * phase), uv.y);
    float gm = texture(u_maskTex, clampUv(gUv)).r * u_hasMask;
    vec3 ghost = texture(u_tex, clampUv(gUv)).rgb;
    vec3 tinted = mix(vec3(0.55, 0.75, 1.0), ghost, 0.55);
    float flick = 0.45 + 0.55 * abs(sin(u_time * 9.0 + uv.y * 14.0));
    float a = gm * u_ghost * flick * (1.0 - m * 0.55);
    vec3 col = mix(c.rgb, max(c.rgb, tinted), clamp(a, 0.0, 1.0));
    return vec4(col, c.a);
}"""

    /** Energy streaks flowing along the subject contour plus a rim-brightened outline. One pass. */
    const val LIGHT_TRAILS = """
uniform float u_speed;
uniform float u_glow;
vec4 process(vec4 c, vec2 uv){
    float m = subjectMask(uv);
    float edge = subjectEdge(uv);
    float flow = fbm(vec2(uv.y * 6.0 - u_time * u_speed * 2.2, uv.x * 3.0));
    float streak = smoothstep(0.45, 0.9, flow);
    vec3 trailCol = mix(vec3(0.25, 0.75, 1.0), vec3(0.85, 0.35, 1.0), flow);
    vec3 col = c.rgb + trailCol * (edge * (0.8 + streak * 1.4) + m * streak * 0.35) * u_glow;
    return vec4(col, c.a);
}"""

    /** Blocks only the tracked person: aspect-correct square cells, stable edges. One pass. */
    const val PIXEL_MASK = """
uniform float u_blocks;
vec4 process(vec4 c, vec2 uv){
    float m = subjectMask(uv);
    float cw = 1.0 / max(u_blocks, 4.0);
    float ch = cw * u_resolution.x / max(u_resolution.y, 1.0);
    vec2 cell = vec2(floor(uv.x / cw) * cw + cw * 0.5, floor(uv.y / ch) * ch + ch * 0.5);
    vec3 px = texture(u_tex, clampUv(cell)).rgb;
    float gate = smoothstep(0.35, 0.55, m);
    return vec4(mix(c.rgb, px, gate), c.a);
}"""

    /** Animated paint splats cycling hue over the subject, overlay-blended. One pass. */
    const val ACTION_PAINT = """
uniform float u_speed;
uniform float u_splash;
vec4 process(vec4 c, vec2 uv){
    float m = subjectMask(uv);
    float aspect = u_resolution.x / max(u_resolution.y, 1.0);
    vec2 p = uv * vec2(aspect, 1.0) * 5.0;
    float n = fbm(p + floor(u_time * u_speed) * 3.1);
    float n2 = fbm(p * 2.3 - floor(u_time * u_speed) * 1.7 + 9.0);
    float splat = smoothstep(0.42, 0.62, n * 0.6 + n2 * 0.5);
    float hue = fract(n * 2.0 + u_time * u_speed * 0.25);
    vec3 paint = hsv2rgb(vec3(hue, 0.85, 1.0));
    vec3 col = mix(c.rgb, blendOverlay(c.rgb, paint), clamp(m * splat * u_splash, 0.0, 1.0));
    return vec4(col, c.a);
}"""

    /** Targeted scan over the head zone of the mask with verification ticks. One pass. */
    const val FACE_SCAN = """
uniform float u_speed;
uniform float u_band;
uniform vec3 u_tint;
vec4 process(vec4 c, vec2 uv){
    float m = subjectMask(uv);
    float upper = smoothstep(0.62, 0.30, uv.y);
    float sweep = fract(u_time * u_speed);
    float y = mix(0.55, 0.12, sweep);
    float band = exp(-pow((uv.y - y) * (0.165 / max(u_band, 0.02)), 2.0));
    float line = exp(-pow((uv.y - y) * 420.0, 2.0));
    float ticks = step(0.72, fract(uv.x * 48.0 + floor(u_time * u_speed) * 7.0)) * 0.5;
    float zone = m * upper;
    vec3 col = c.rgb + u_tint * (band * (0.4 + ticks) + line * 1.8) * zone;
    return vec4(col, c.a);
}"""

    /** Noise-threshold dissolve of the subject into glowing embers, front-lit. One pass. */
    const val LIGHT_DISSOLVE = """
uniform float u_speed;
uniform float u_ember;
vec4 process(vec4 c, vec2 uv){
    float m = subjectMask(uv);
    float t = fract(u_time * u_speed);
    float env = sin(3.14159265 * t);
    float aspect = u_resolution.x / max(u_resolution.y, 1.0);
    float n = fbm(uv * vec2(aspect, 1.0) * 7.0 + 4.7);
    float thr = mix(-0.25, 1.25, env);
    float gone = smoothstep(thr, thr - 0.18, n) * step(0.02, env);
    float px = step(0.86, hash21(floor(uv * vec2(160.0, 160.0 * max(u_resolution.y / max(u_resolution.x, 1.0), 1.0))) + floor(u_time * 12.0)));
    vec3 col = mix(c.rgb, vec3(0.0), clamp(gone * m * 0.85, 0.0, 1.0));
    col += vec3(1.0, 0.85, 0.5) * gone * px * m * 1.4 * u_ember;
    float front = smoothstep(0.04, 0.0, abs(n - thr)) * step(0.02, env);
    col += vec3(1.0, 0.7, 0.35) * front * m * 1.2;
    return vec4(col, c.a);
}"""

    /** Breathing rim light + soft inner glow along the subject silhouette. One pass. */
    const val LUMINESCENCE = """
uniform float u_glow;
uniform float u_pulse;
uniform vec3 u_tint;
vec4 process(vec4 c, vec2 uv){
    float m = subjectMask(uv);
    float edge = subjectEdge(uv);
    float breathe = 0.75 + 0.25 * sin(u_time * 2.4) * u_pulse;
    vec3 col = c.rgb + u_tint * edge * 1.5 * u_glow * breathe;
    col += u_tint * m * 0.22 * u_glow * breathe;
    col = blendSoftLight(col, u_tint * 0.35 * m * u_glow * breathe);
    return vec4(col, c.a);
}"""

    /** Pencil look inside the subject: hatching from luminance, edges, paper grain. One pass. */
    const val HAND_DRAWN = """
uniform float u_outline;
uniform float u_paper;
vec4 process(vec4 c, vec2 uv){
    float m = subjectMask(uv);
    float l0 = luma709(c.rgb);
    float lx = luma709(texture(u_tex, clampUv(uv + vec2(u_texel.x, 0.0))).rgb)
             - luma709(texture(u_tex, clampUv(uv - vec2(u_texel.x, 0.0))).rgb);
    float ly = luma709(texture(u_tex, clampUv(uv + vec2(0.0, u_texel.y))).rgb)
             - luma709(texture(u_tex, clampUv(uv - vec2(0.0, u_texel.y))).rgb);
    float edge = clamp(length(vec2(lx, ly)) * 4.5, 0.0, 1.0);
    float hatch = sin((uv.x + uv.y) * u_resolution.y * 0.55 + l0 * 12.0);
    float shade = smoothstep(0.0, 0.5, 0.55 - l0) * (0.5 + 0.5 * hatch);
    vec3 sketch = vec3(0.98) - vec3(shade * 0.45) - vec3(edge * u_outline);
    sketch += (hash21(uv * u_resolution * 0.5) - 0.5) * u_paper * 0.10;
    vec3 col = mix(c.rgb, clamp(sketch, 0.0, 1.0), clamp(m, 0.0, 1.0));
    return vec4(col, c.a);
}"""

    // ---------------- AI EFFECTS (segmentation-driven character restyle) ----------------

    /** Character replace: cold shadow silhouette with a neon rim and volume shading. One pass. */
    const val CLONE_SHADOW = """
uniform float u_lift;
uniform float u_rim;
vec4 process(vec4 c, vec2 uv){
    float m = subjectMask(uv);
    float edge = subjectEdge(uv);
    float l = luma709(c.rgb);
    vec3 shadow = vec3(0.03, 0.035, 0.05) * (1.0 + u_lift * l);
    shadow += vec3(0.10, 0.12, 0.18) * l * u_lift;
    shadow += vec3(0.35, 0.55, 1.0) * edge * u_rim;
    vec3 col = mix(c.rgb, shadow, clamp(m, 0.0, 1.0));
    return vec4(col, c.a);
}"""

    /** Character replace: golden statue — luminance relief, key-light specular, statue rim. One pass. */
    const val CLONE_GOLDEN = """
uniform float u_shine;
uniform float u_relief;
vec4 process(vec4 c, vec2 uv){
    float m = subjectMask(uv);
    float l = luma709(c.rgb);
    float mx = texture(u_maskTex, clampUv(uv + vec2(u_texel.x * 1.5, 0.0))).r
             - texture(u_maskTex, clampUv(uv - vec2(u_texel.x * 1.5, 0.0))).r;
    float my = texture(u_maskTex, clampUv(uv + vec2(0.0, u_texel.y * 1.5))).r
             - texture(u_maskTex, clampUv(uv - vec2(0.0, u_texel.y * 1.5))).r;
    vec3 nrm = normalize(vec3(-mx * 8.0 * u_relief, my * 8.0 * u_relief, 1.0));
    float spec = pow(clamp(dot(nrm, normalize(vec3(-0.4, 0.6, 0.8))), 0.0, 1.0), 14.0);
    vec3 gold = mix(vec3(0.28, 0.16, 0.03), vec3(1.0, 0.78, 0.30), l);
    gold += vec3(1.0, 0.95, 0.75) * spec * u_shine;
    gold += vec3(1.0, 0.85, 0.45) * clamp(length(vec2(mx, my)) * 2.2, 0.0, 1.0) * 0.5;
    vec3 col = mix(c.rgb, gold, clamp(m, 0.0, 1.0));
    return vec4(col, c.a);
}"""

    /** Character replace: cyber suit — carbon base, crawling circuit traces, neon rim. One pass. */
    const val CLONE_CYBER = """
uniform float u_lines;
uniform float u_glow;
uniform vec3 u_neon;
vec4 process(vec4 c, vec2 uv){
    float m = subjectMask(uv);
    float edge = subjectEdge(uv);
    float l = luma709(c.rgb);
    vec3 suit = vec3(0.05, 0.06, 0.09) * (0.6 + l * 0.9);
    float aspect = u_resolution.x / max(u_resolution.y, 1.0);
    vec2 gp = uv * vec2(aspect, 1.0) * 26.0;
    float gx = step(0.92, fract(gp.x)) + step(0.92, fract(gp.y));
    float pulse = smoothstep(0.5, 1.0, fbm(gp * 0.35 + vec2(0.0, u_time * 1.4)));
    suit += u_neon * gx * pulse * u_lines;
    suit += u_neon * edge * 1.4 * u_glow;
    vec3 col = mix(c.rgb, suit, clamp(m, 0.0, 1.0));
    return vec4(col, c.a);
}"""

    /** Character replace: cartoon avatar — posterize, saturation, ink outline on the person. One pass. */
    const val CLONE_CARTOON = """
uniform float u_levels;
uniform float u_outline;
uniform float u_boost;
vec4 process(vec4 c, vec2 uv){
    float m = subjectMask(uv);
    float lx = luma709(texture(u_tex, clampUv(uv + vec2(u_texel.x * 1.5, 0.0))).rgb)
             - luma709(texture(u_tex, clampUv(uv - vec2(u_texel.x * 1.5, 0.0))).rgb);
    float ly = luma709(texture(u_tex, clampUv(uv + vec2(0.0, u_texel.y * 1.5))).rgb)
             - luma709(texture(u_tex, clampUv(uv - vec2(0.0, u_texel.y * 1.5))).rgb);
    float edge = clamp(length(vec2(lx, ly)) * 5.0, 0.0, 1.0);
    vec3 col = floor(c.rgb * u_levels + 0.5) / max(u_levels, 2.0);
    float l = luma709(col);
    col = mix(vec3(l), col, 1.0 + 0.55 * u_boost);
    col = mix(col, vec3(0.05), edge * u_outline);
    vec3 col2 = mix(c.rgb, col, clamp(m, 0.0, 1.0));
    return vec4(col2, c.a);
}"""
}
