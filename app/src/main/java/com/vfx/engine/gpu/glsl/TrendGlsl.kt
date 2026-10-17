package com.vfx.engine.gpu.glsl

/**
 * GLSL bodies for the CapCut-trending effects assets (Video Effects + Photo Effects).
 * Same contract as [EffectGlsl]: every body defines `vec4 process(vec4 c, vec2 uv)`; bodies that
 * displace the frame additionally define `vec2 warpUV(vec2 uv)`. Effect uniforms are declared
 * inline. Helper functions (fbm, hash, rot2, hsv, blends, clampUv) come from [ShaderChunks].
 *
 * Every temporal effect is a pure function of (u_time, uv, u_tex): the clip-local time the render
 * graph publishes makes preview and export render the identical picture for the identical frame.
 */
object TrendGlsl {

    // ---------------- VIDEO EFFECTS ----------------

    /** Shockwave ring, fbm fireball, ignition flash and an outward jolt. One pass. */
    const val EXPLOSION = """
uniform float u_scale;
uniform float u_speed;
uniform float u_heat;
vec4 process(vec4 c, vec2 uv){
    float t = fract(u_time * u_speed);
    float aspect = u_resolution.x / max(u_resolution.y, 1.0);
    vec2 p = (uv - vec2(0.5)) * vec2(aspect, 1.0);
    float dist = max(length(p), 1.0e-4);
    float ringR = t * 1.15 * u_scale;
    float ring = exp(-pow((dist - ringR) * 14.0, 2.0));
    vec2 dirP = p / dist;
    vec3 col = c.rgb + ring * 0.20 * dirP.x * vec3(1.0, 0.85, 0.6);
    float life = smoothstep(1.0, 0.15, t);
    float body = smoothstep(ringR, 0.0, dist) * life;
    float n = fbm(p * 6.0 + vec2(0.0, -t * 5.0) + 31.7);
    float radial = 1.0 - clamp(dist / max(ringR, 1.0e-3), 0.0, 1.0);
    float flame = body * smoothstep(0.35, 0.9, n + radial * 0.4) * u_heat;
    vec3 fireCol = mix(vec3(1.0, 0.30, 0.05), vec3(1.0, 0.85, 0.45), body);
    col = mix(col, max(col, fireCol), clamp(flame, 0.0, 1.0));
    col += exp(-t * 9.0) * 0.65;
    return vec4(col * u_intensity + c.rgb * (1.0 - u_intensity), c.a);
}"""

    /** fbm-perturbed ink front that breathes in and out over the loop. One pass. */
    const val INK_SPILL = """
uniform float u_spread;
uniform float u_speed;
uniform float u_dark;
vec4 process(vec4 c, vec2 uv){
    float t = fract(u_time * u_speed);
    float env = sin(3.14159265 * min(t * 1.25, 1.0));
    float aspect = u_resolution.x / max(u_resolution.y, 1.0);
    vec2 p = (uv - vec2(0.5)) * vec2(aspect, 1.0);
    float dist = length(p);
    vec2 dirP = p / max(dist, 1.0e-4);
    float reach = env * u_spread * 1.4;
    float n = fbm(dirP * 7.0 + 11.3);
    float edge = reach * (0.75 + 0.5 * n);
    float ink = 1.0 - smoothstep(edge - 0.06, edge, dist);
    float sheen = ink * (1.0 - smoothstep(0.0, 0.10, edge - dist));
    vec3 inkCol = vec3(0.02, 0.02, 0.045) * (1.0 + 0.4 * (n - 0.5));
    vec3 stained = mix(inkCol, vec3(0.30, 0.34, 0.42), sheen * 0.35);
    vec3 col = mix(c.rgb, stained, clamp(ink * u_dark, 0.0, 1.0));
    return vec4(col * u_intensity + c.rgb * (1.0 - u_intensity), c.a);
}"""

    /**
     * Procedural globe: 3D noise sampled on the sphere normal (seam-free), drifting clouds,
     * key light, and an atmosphere rim. One pass.
     */
    const val EARTH = """
uniform float u_size;
uniform vec2 u_center;
uniform float u_spin;
uniform float u_clouds;
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
vec4 process(vec4 c, vec2 uv){
    float aspect = u_resolution.x / max(u_resolution.y, 1.0);
    vec2 p = (uv - u_center) * vec2(aspect, 1.0);
    float R = u_size;
    float dist = length(p);
    float inside = 1.0 - smoothstep(R - 0.002, R, dist);
    if (inside <= 0.0) return c;
    float z = sqrt(max(R * R - dist * dist, 0.0));
    vec3 nrm = vec3(p.x, p.y, z) / max(R, 1.0e-4);
    float lat = asin(clamp(nrm.y, -1.0, 1.0));
    float lon = atan(nrm.x, nrm.z) + u_time * u_spin;
    vec3 sphereP = vec3(cos(lat) * cos(lon), sin(lat), cos(lat) * sin(lon));
    float land = fbm3(sphereP * 2.6 + 5.0);
    land = smoothstep(0.50, 0.56, land);
    vec3 ocean = mix(vec3(0.02, 0.09, 0.28), vec3(0.03, 0.20, 0.42), fbm3(sphereP * 5.0) * 0.7 + 0.3);
    vec3 ground = mix(vec3(0.10, 0.32, 0.10), vec3(0.55, 0.45, 0.22), fbm3(sphereP * 6.5 + 3.0));
    vec3 surf = mix(ocean, ground, land);
    float cl = smoothstep(0.52, 0.74, fbm3(sphereP * 3.4 + vec3(u_time * u_spin * 0.45, 0.0, 17.0)));
    surf = mix(surf, vec3(0.95), cl * u_clouds);
    float diff = clamp(dot(nrm, normalize(vec3(-0.45, 0.55, 0.7))), 0.0, 1.0);
    surf *= 0.22 + 0.88 * diff;
    float rim = pow(1.0 - z / max(R, 1.0e-4), 2.5);
    surf += rim * vec3(0.35, 0.55, 0.9) * 0.8;
    return vec4(mix(c.rgb, surf, inside * u_intensity), c.a);
}"""

    /** Glowing rune rings with counter-rotating ticks, orbiting sparkles and a breathing cast. One pass. */
    const val MAGICAL_TOME = """
uniform float u_scale;
uniform float u_speed;
uniform float u_hue;
vec4 process(vec4 c, vec2 uv){
    float t = fract(u_time * u_speed);
    float aspect = u_resolution.x / max(u_resolution.y, 1.0);
    vec2 p = (uv - vec2(0.5, 0.45)) * vec2(aspect, 1.0);
    float r = length(p);
    float ang = atan(p.y, p.x);
    float glow = 0.0;
    for (int i = 0; i < 3; i++) {
        float fi = float(i);
        float rr = u_scale * (0.16 + 0.10 * fi);
        float spin = u_time * (0.6 + 0.35 * fi) * (mod(fi, 2.0) * 2.0 - 1.0);
        float ticks = smoothstep(0.45, 0.95, sin((ang + spin) * (9.0 + 6.0 * fi)));
        glow += exp(-pow((r - rr) * 90.0, 2.0)) * (0.35 + 0.65 * ticks);
    }
    glow += exp(-pow((r - u_scale * 0.10) * 110.0, 2.0)) * 1.3;
    float spark = 0.0;
    for (int i = 0; i < 6; i++) {
        float fi = float(i);
        float seed = hash11(fi + 7.31);
        float sr = u_scale * (0.14 + 0.34 * seed);
        float sa = fi * 1.0471976 + u_time * (0.8 + seed) + seed * 6.2831853;
        vec2 sp = vec2(cos(sa), sin(sa)) * sr;
        float d = length(p - sp);
        float tw = 0.55 + 0.45 * sin(u_time * (6.0 + 5.0 * seed) + fi);
        spark += exp(-d * d * 2600.0) * tw;
    }
    glow += spark * 0.9;
    vec3 tint = mix(vec3(0.55, 0.25, 0.95), vec3(1.0, 0.78, 0.30), 0.5 + 0.5 * sin(u_hue * 6.2831853 + r * 3.0));
    float breathe = 0.55 + 0.45 * sin(3.14159265 * t);
    vec3 col = c.rgb + tint * glow * breathe;
    return vec4(col * u_intensity + c.rgb * (1.0 - u_intensity), c.a);
}"""

    /** Time-quantized slice displacement plus a step-synced RGB split. One warp pass. */
    const val CUT_SHIFT = """
uniform float u_slices;
uniform float u_speed;
uniform float u_rgb;
vec2 warpUV(vec2 uv){
    float stepN = floor(u_time * u_speed);
    float rows = max(u_slices, 2.0);
    float row = floor(uv.y * rows);
    float gate = step(0.55, hash21(vec2(row * 1.7, stepN * 1.3)));
    float amt = (hash21(vec2(row, stepN)) - 0.5) * 0.5 * (0.3 + 0.7 * hash11(stepN + 3.1)) * gate;
    return uv + vec2(amt, 0.0);
}
vec4 process(vec4 c, vec2 uv){
    float stepN = floor(u_time * u_speed);
    float amt = u_rgb * (0.004 + 0.010 * hash11(stepN + 9.7));
    float r = texture(u_tex, clampUv(uv + vec2(amt, 0.0))).r;
    float b = texture(u_tex, clampUv(uv - vec2(amt, 0.0))).b;
    return vec4(vec3(r, c.g, b), c.a);
}"""

    /** Bottom-anchored rising flames with a heat-shimmer warp. One warp pass. */
    const val FIRE = """
uniform float u_height;
uniform float u_speed;
uniform float u_heat;
vec2 warpUV(vec2 uv){
    float t = u_time * u_speed;
    float m = smoothstep(1.0 - u_height * 1.8, 0.0, uv.y);
    float w = fbm(vec2(uv.x * 6.0, uv.y * 4.0 - t * 2.0)) - 0.5;
    return uv + vec2(w * 0.014, 0.0) * m;
}
vec4 process(vec4 c, vec2 uv){
    float t = u_time * u_speed;
    float band = smoothstep(1.0 - u_height, 1.05, 1.0 - uv.y + 0.05);
    vec2 fp = vec2(uv.x * 3.0, uv.y * 3.0 + t * 1.6);
    float n = fbm(fp);
    float n2 = fbm(fp * 2.1 + vec2(7.7, -t * 2.4));
    float flame = band * smoothstep(0.35, 0.85, n * 0.65 + n2 * 0.45);
    flame = pow(flame, 1.4);
    vec3 fireCol = mix(vec3(1.0, 0.25, 0.02), vec3(1.0, 0.9, 0.35), clamp(flame * 0.8 + n2 * 0.2, 0.0, 1.0));
    vec3 col = c.rgb + fireCol * flame * u_heat;
    float ember = step(0.985, hash21(floor(uv * vec2(220.0, 220.0)) + floor(t * 14.0)));
    col += vec3(1.0, 0.6, 0.2) * ember * band * 1.2;
    return vec4(col * u_intensity + c.rgb * (1.0 - u_intensity), c.a);
}"""

    /** Beat zoom punch with a per-pulse shake and a matching vignette hit. One warp pass. */
    const val MINI_ZOOM = """
uniform float u_speed;
uniform float u_amount;
uniform float u_shake;
vec2 warpUV(vec2 uv){
    float t = fract(u_time * u_speed);
    float z = 1.0 + u_amount * pow(1.0 - t, 3.0);
    float j = u_shake * pow(1.0 - t, 5.0);
    vec2 off = (vec2(hash11(floor(u_time * u_speed) + 1.7), hash11(floor(u_time * u_speed) + 5.3)) - 0.5) * j;
    return (uv - vec2(0.5)) / max(z, 0.1) + vec2(0.5) + off;
}
vec4 process(vec4 c, vec2 uv){
    float t = fract(u_time * u_speed);
    float env = pow(1.0 - t, 3.0);
    float aspect = u_resolution.x / max(u_resolution.y, 1.0);
    float d = length((uv - 0.5) * vec2(aspect, 1.0));
    c.rgb *= 1.0 - 0.35 * env * smoothstep(0.3, 0.85, d);
    return c;
}"""

    /** Strobe flash: sharp attack, exponential decay, optional hold and a bloom lift. One pass. */
    const val WHITE_FLASH = """
uniform float u_speed;
uniform float u_hold;
uniform float u_bloom;
vec4 process(vec4 c, vec2 uv){
    float t = fract(u_time * u_speed);
    float attack = smoothstep(0.0, 0.02, t);
    float decay = exp(-max(t - u_hold, 0.0) * 14.0);
    float flash = attack * decay;
    vec3 col = mix(c.rgb, vec3(1.0), clamp(flash, 0.0, 1.0));
    col += u_bloom * decay * 0.25 * (1.0 - luma709(c.rgb));
    return vec4(col * u_intensity + c.rgb * (1.0 - u_intensity), c.a);
}"""

    // ---------------- PHOTO EFFECTS ----------------

    /** Mirrored canvas extension on both sides with a progressive edge falloff. One warp pass. */
    const val EXPAND_X = """
uniform float u_expand;
uniform float u_falloff;
vec2 warpUV(vec2 uv){
    float e = u_expand;
    if (e <= 0.001) return uv;
    float s = max(1.0 - 2.0 * e, 0.05);
    float t = (uv.x - e) / s;
    float x = (uv.x < e) ? -t : ((uv.x > 1.0 - e) ? 2.0 - t : t);
    return vec2(x, uv.y);
}
vec4 process(vec4 c, vec2 uv){
    float e = u_expand;
    float dEdge = clamp(max(e - uv.x, uv.x - (1.0 - e)) / max(e, 1.0e-4), 0.0, 1.0);
    float k = pow(dEdge, 1.5) * u_falloff;
    c.rgb *= 1.0 - 0.22 * k;
    return c;
}"""

    /** Cylindrical pseudo-360 orbit: sample compression at the edges plus a curvature shade. One warp pass. */
    const val ORBIT_360 = """
uniform float u_speed;
uniform float u_reach;
uniform float u_twist;
vec2 warpUV(vec2 uv){
    float w = sin(u_time * u_speed * 6.2831853);
    float k = 1.0 + 1.6 * u_reach;
    float x = (uv.x - 0.5) * k + w * u_reach;
    float xx = atan(x) / atan(k) * 0.5 + 0.5;
    float yy = (uv.y - 0.5) / (1.0 + 0.15 * u_reach * (1.0 - w * 0.5)) + 0.5;
    vec2 r = rot2(u_twist * 0.35 * w) * vec2(xx - 0.5, yy - 0.5);
    return r + 0.5;
}
vec4 process(vec4 c, vec2 uv){
    float d = abs(uv.x - 0.5) * 2.0;
    c.rgb *= 1.0 - 0.25 * u_reach * d * d;
    return c;
}"""

    /** Three screen-blended echo layers with staggered offset and scale. One pass. */
    const val LAYER_ECHO = """
uniform float u_offset;
uniform float u_scaleStep;
uniform float u_mixAmt;
vec4 process(vec4 c, vec2 uv){
    vec3 acc = c.rgb;
    vec2 ctr = vec2(0.5);
    for (int i = 1; i <= 3; i++) {
        float fi = float(i);
        float sc = max(1.0 - u_scaleStep * fi * 0.15, 0.2);
        vec2 o = vec2(u_offset * fi * 0.02, -u_offset * fi * 0.012);
        vec2 s = clampUv((uv - ctr) / sc + ctr + o);
        vec3 layer = texture(u_tex, s).rgb;
        acc = mix(acc, blendScreen(acc, layer), pow(0.62, fi) * u_mixAmt);
    }
    return vec4(acc, c.a);
}"""

    /** Luminance-parallax Ken Burns push: bright content zooms ahead of the background. One warp pass. */
    const val ZOOM_3D = """
uniform float u_zoom;
uniform float u_depth;
uniform float u_speed;
vec2 warpUV(vec2 uv){
    float push = (sin(u_time * u_speed * 3.14159265 - 1.5707963) + 1.0) * 0.5;
    float z = 1.0 + u_zoom * (0.10 + 0.35 * push);
    float lum = luma709(texture(u_tex, clampUv(uv)).rgb);
    float parallax = (lum - 0.5) * u_depth * 0.12 * push;
    vec2 drift = vec2(cos(u_time * 0.7), sin(u_time * 0.9)) * parallax * 0.5;
    return (uv - 0.5) / max(z, 0.1) + 0.5 + drift;
}
vec4 process(vec4 c, vec2 uv){ return c; }"""

    /** Modern AI-look grade: detail-aware micro-smooth, teal-orange split tone, vibrance, bloom. One pass. */
    const val AI_FILTER = """
uniform float u_warmth;
uniform float u_glow;
uniform float u_pop;
vec4 process(vec4 c, vec2 uv){
    vec3 col = c.rgb;
    vec3 blur = (texture(u_tex, clampUv(uv + vec2(u_texel.x, 0.0))).rgb
               + texture(u_tex, clampUv(uv - vec2(u_texel.x, 0.0))).rgb
               + texture(u_tex, clampUv(uv + vec2(0.0, u_texel.y))).rgb
               + texture(u_tex, clampUv(uv - vec2(0.0, u_texel.y))).rgb) * 0.25;
    float detail = abs(luma709(col) - luma709(blur));
    col = mix(col, blur, clamp(0.65 - detail * 3.0, 0.0, 0.65));
    float l = luma709(col);
    vec3 shadowT = vec3(0.10, 0.24, 0.34);
    vec3 highT = vec3(1.0, 0.78, 0.52);
    vec3 toned = mix(col * shadowT * 2.2, highT * col * 1.4, smoothstep(0.35, 0.8, l));
    col = mix(col, toned, 0.35 * u_warmth + 0.10);
    float sat = maxc(col) - minc(col);
    col = mix(vec3(l), col, 1.0 + 0.35 * u_pop * (1.0 - sat));
    col = clamp(col * 1.06 - 0.02, 0.0, 1.0);
    col += max(blur - 0.65, vec3(0.0)) * u_glow * 1.6;
    return vec4(col, c.a);
}"""

    /** Moody grade: desat + cold split, sighing brightness dip, vignette and fine grain. One pass. */
    const val SAD = """
uniform float u_mood;
uniform float u_vig;
uniform float u_grain;
vec4 process(vec4 c, vec2 uv){
    vec3 col = c.rgb;
    float l = luma709(col);
    vec3 cold = mix(vec3(l), col, 0.45) * vec3(0.86, 0.94, 1.10);
    cold = mix(cold, vec3(0.10, 0.16, 0.26), (1.0 - smoothstep(0.0, 0.45, l)) * 0.35);
    col = mix(col, cold, u_mood);
    col *= 1.0 - 0.06 * u_mood * (0.5 + 0.5 * sin(u_time * 0.9));
    float aspect = u_resolution.x / max(u_resolution.y, 1.0);
    float d = length((uv - 0.5) * vec2(aspect, 1.0));
    col *= 1.0 - u_vig * smoothstep(0.45, 0.95, d);
    col += (hash21(uv * u_resolution + fract(u_time) * 61.7) - 0.5) * u_grain * 0.14;
    return vec4(col, c.a);
}"""

    /** Toon shading: posterize, band shading, saturation boost and a Sobel outline. One pass. */
    const val CARTOON_3D = """
uniform float u_levels;
uniform float u_outline;
uniform float u_sat;
vec4 process(vec4 c, vec2 uv){
    float lx = luma709(texture(u_tex, clampUv(uv + vec2(u_texel.x * 1.5, 0.0))).rgb)
             - luma709(texture(u_tex, clampUv(uv - vec2(u_texel.x * 1.5, 0.0))).rgb);
    float ly = luma709(texture(u_tex, clampUv(uv + vec2(0.0, u_texel.y * 1.5))).rgb)
             - luma709(texture(u_tex, clampUv(uv - vec2(0.0, u_texel.y * 1.5))).rgb);
    float edge = clamp(length(vec2(lx, ly)) * 6.0 - 0.12, 0.0, 1.0);
    vec3 col = floor(c.rgb * u_levels + 0.5) / max(u_levels, 2.0);
    float l = luma709(col);
    col = mix(vec3(l), col, 1.0 + 0.5 * u_sat);
    float band = min(fract(l * u_levels), 1.0 - fract(l * u_levels)) * 2.0;
    col *= 1.0 - 0.18 * (1.0 - band) * u_sat;
    col = mix(col, vec3(0.06), edge * u_outline);
    return vec4(col, c.a);
}"""
}
