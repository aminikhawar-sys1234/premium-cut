package com.ahstudio.transition.transitions

import com.ahstudio.transition.core.AlphaMode
import com.ahstudio.transition.core.ParamType
import com.ahstudio.transition.core.ParamValue
import com.ahstudio.transition.core.ShaderSource
import com.ahstudio.transition.core.TransitionDefinition
import com.ahstudio.transition.core.TransitionFamily
import com.ahstudio.transition.core.TransitionParameterDefinition
import com.ahstudio.transition.core.TransitionRenderGraphSpec
import com.ahstudio.transition.provider.TransitionProvider

object BuiltinTransitions {
    const val CROSS_DISSOLVE_ID = "com.ahstudio.transition.cross_dissolve"
    const val FADE_ID = "com.ahstudio.transition.fade"
    const val ZOOM_ID = "com.ahstudio.transition.zoom"
    const val ZOOM_OUT_ID = "com.ahstudio.transition.zoom_out"
    const val SLIDE_LEFT_ID = "com.ahstudio.transition.slide_left"
    const val SLIDE_RIGHT_ID = "com.ahstudio.transition.slide_right"
    const val PUSH_UP_ID = "com.ahstudio.transition.push_up"
    const val WIPE_ID = "com.ahstudio.transition.wipe"
    const val RADIAL_WIPE_ID = "com.ahstudio.transition.radial_wipe"
    const val BLUR_ID = "com.ahstudio.transition.blur"
    const val ZOOM_BLUR_ID = "com.ahstudio.transition.zoom_blur"
    const val FLASH_ID = "com.ahstudio.transition.flash"
    const val GLITCH_ID = "com.ahstudio.transition.glitch"
    const val GLITCH_WIPE_ID = "com.ahstudio.transition.glitch_wipe"
    const val SPIN_ID = "com.ahstudio.transition.spin"
    const val WHIP_PAN_ID = "com.ahstudio.transition.whip_pan"
    const val LIGHT_LEAK_ID = "com.ahstudio.transition.light_leak"

    // 1. Dissolve / Cross Dissolve Shader (Smooth Alpha Blending)
    private const val CROSS_DISSOLVE_FRAG = """
precision highp float;
uniform sampler2D uTextureA;
uniform sampler2D uTextureB;
uniform float uProgress;
uniform float uRawProgress;
uniform vec2 uResolution;
uniform float uTime;
uniform float uDuration;
uniform vec2 uDirection;
uniform float uOutputPremultiplied;
uniform float u_softness;
in vec2 vUv;
out vec4 oColor;
void main() {
    vec4 a = texture(uTextureA, vUv);
    vec4 b = texture(uTextureB, vUv);
    float p = uProgress;
    if (u_softness > 0.0001) {
        p = smoothstep(0.5 - u_softness * 0.5, 0.5 + u_softness * 0.5, uProgress);
    }
    vec4 c = mix(a, b, clamp(p, 0.0, 1.0));
    if (uOutputPremultiplied > 0.5) { oColor = vec4(c.rgb * c.a, c.a); } else { oColor = c; }
}
"""

    // 2. Fade to Black/Color Shader
    private const val FADE_FRAG = """
precision highp float;
uniform sampler2D uTextureA;
uniform sampler2D uTextureB;
uniform float uProgress;
uniform float uRawProgress;
uniform vec2 uResolution;
uniform float uTime;
uniform float uDuration;
uniform vec2 uDirection;
uniform float uOutputPremultiplied;
in vec2 vUv;
out vec4 oColor;
void main() {
    float p = clamp(uProgress, 0.0, 1.0);
    vec4 a = texture(uTextureA, vUv);
    vec4 b = texture(uTextureB, vUv);
    vec4 c;
    if (p < 0.5) {
        float f = 1.0 - (p * 2.0);
        c = vec4(a.rgb * f, a.a);
    } else {
        float f = (p - 0.5) * 2.0;
        c = vec4(b.rgb * f, b.a);
    }
    if (uOutputPremultiplied > 0.5) { oColor = vec4(c.rgb * c.a, c.a); } else { oColor = c; }
}
"""

    // 3. Zoom In Shader
    private const val ZOOM_FRAG = """
precision highp float;
uniform sampler2D uTextureA;
uniform sampler2D uTextureB;
uniform float uProgress;
uniform float uRawProgress;
uniform vec2 uResolution;
uniform float uTime;
uniform float uDuration;
uniform vec2 uDirection;
uniform float uOutputPremultiplied;
uniform float u_zoomAmount;
uniform float u_edgeSoftness;
in vec2 vUv;
out vec4 oColor;
void main() {
    float p = clamp(uProgress, 0.0, 1.0);
    float zoom = max(u_zoomAmount, 1.0);
    float scaleA = mix(1.0, zoom, p);
    float scaleB = mix(1.0 + (zoom - 1.0) * 0.35, 1.0, p);
    vec2 uvA = (vUv - 0.5) / scaleA + 0.5;
    vec2 uvB = (vUv - 0.5) / scaleB + 0.5;
    vec4 a = texture(uTextureA, clamp(uvA, vec2(0.0), vec2(1.0)));
    vec4 b = texture(uTextureB, clamp(uvB, vec2(0.0), vec2(1.0)));
    float m = p;
    if (u_edgeSoftness > 0.0001) {
        m = smoothstep(0.5 - u_edgeSoftness * 0.5, 0.5 + u_edgeSoftness * 0.5, p);
    }
    vec4 c = mix(a, b, m);
    if (uOutputPremultiplied > 0.5) { oColor = vec4(c.rgb * c.a, c.a); } else { oColor = c; }
}
"""

    // 4. Slide Left Shader
    private const val SLIDE_LEFT_FRAG = """
precision highp float;
uniform sampler2D uTextureA;
uniform sampler2D uTextureB;
uniform float uProgress;
uniform float uRawProgress;
uniform vec2 uResolution;
uniform float uTime;
uniform float uDuration;
uniform vec2 uDirection;
uniform float uOutputPremultiplied;
in vec2 vUv;
out vec4 oColor;
void main() {
    float p = clamp(uProgress, 0.0, 1.0);
    vec2 uvA = vUv + vec2(p, 0.0);
    vec2 uvB = vUv - vec2(1.0 - p, 0.0);
    vec4 c = (vUv.x < (1.0 - p)) ? texture(uTextureA, uvA) : texture(uTextureB, uvB);
    if (uOutputPremultiplied > 0.5) { oColor = vec4(c.rgb * c.a, c.a); } else { oColor = c; }
}
"""

    // 5. Slide Right Shader
    private const val SLIDE_RIGHT_FRAG = """
precision highp float;
uniform sampler2D uTextureA;
uniform sampler2D uTextureB;
uniform float uProgress;
uniform float uRawProgress;
uniform vec2 uResolution;
uniform float uTime;
uniform float uDuration;
uniform vec2 uDirection;
uniform float uOutputPremultiplied;
in vec2 vUv;
out vec4 oColor;
void main() {
    float p = clamp(uProgress, 0.0, 1.0);
    vec2 uvA = vUv - vec2(p, 0.0);
    vec2 uvB = vUv + vec2(1.0 - p, 0.0);
    vec4 c = (vUv.x > p) ? texture(uTextureA, uvA) : texture(uTextureB, uvB);
    if (uOutputPremultiplied > 0.5) { oColor = vec4(c.rgb * c.a, c.a); } else { oColor = c; }
}
"""

    // 6. Wipe Shader
    private const val WIPE_FRAG = """
precision highp float;
uniform sampler2D uTextureA;
uniform sampler2D uTextureB;
uniform float uProgress;
uniform float uRawProgress;
uniform vec2 uResolution;
uniform float uTime;
uniform float uDuration;
uniform vec2 uDirection;
uniform float uOutputPremultiplied;
uniform float u_feather;
in vec2 vUv;
out vec4 oColor;
void main() {
    float p = clamp(uProgress, 0.0, 1.0);
    float feather = max(u_feather, 0.001);
    float pp = p * (1.0 + 2.0 * feather) - feather;
    float axis = vUv.x;
    if (abs(uDirection.y) > abs(uDirection.x)) {
        axis = uDirection.y < 0.0 ? (1.0 - vUv.y) : vUv.y;
    } else if (uDirection.x < 0.0) {
        axis = 1.0 - vUv.x;
    }
    float edge = smoothstep(pp - feather, pp + feather, axis);
    vec4 a = texture(uTextureA, vUv);
    vec4 b = texture(uTextureB, vUv);
    vec4 c = mix(b, a, edge);
    if (uOutputPremultiplied > 0.5) { oColor = vec4(c.rgb * c.a, c.a); } else { oColor = c; }
}
"""


    // Zoom Out: incoming clip settles from a zoomed-in state
    private const val ZOOM_OUT_FRAG = """
precision highp float;
uniform sampler2D uTextureA;
uniform sampler2D uTextureB;
uniform float uProgress;
uniform float uRawProgress;
uniform vec2 uResolution;
uniform float uTime;
uniform float uDuration;
uniform vec2 uDirection;
uniform float uOutputPremultiplied;
in vec2 vUv;
out vec4 oColor;
void main() {
    float p = clamp(uProgress, 0.0, 1.0);
    float e = p * p * (3.0 - 2.0 * p);
    vec2 uvA = (vUv - 0.5) / mix(1.0, 1.2, e) + 0.5;
    vec2 uvB = (vUv - 0.5) / mix(1.6, 1.0, e) + 0.5;
    vec4 a = texture(uTextureA, clamp(uvA, vec2(0.0), vec2(1.0)));
    vec4 b = texture(uTextureB, clamp(uvB, vec2(0.0), vec2(1.0)));
    vec4 c = mix(a, b, e);
    if (uOutputPremultiplied > 0.5) { oColor = vec4(c.rgb * c.a, c.a); } else { oColor = c; }
}
"""

    // Push Up: outgoing clip is pushed off the top by the incoming clip
    private const val PUSH_UP_FRAG = """
precision highp float;
uniform sampler2D uTextureA;
uniform sampler2D uTextureB;
uniform float uProgress;
uniform float uRawProgress;
uniform vec2 uResolution;
uniform float uTime;
uniform float uDuration;
uniform vec2 uDirection;
uniform float uOutputPremultiplied;
in vec2 vUv;
out vec4 oColor;
void main() {
    float p = clamp(uProgress, 0.0, 1.0);
    vec2 uvA = vUv + vec2(0.0, p);
    vec2 uvB = vUv - vec2(0.0, 1.0 - p);
    vec4 c = (vUv.y < (1.0 - p)) ? texture(uTextureA, uvA) : texture(uTextureB, uvB);
    if (uOutputPremultiplied > 0.5) { oColor = vec4(c.rgb * c.a, c.a); } else { oColor = c; }
}
"""

    // Radial Wipe: clock-style sweep around the frame centre
    private const val RADIAL_WIPE_FRAG = """
precision highp float;
uniform sampler2D uTextureA;
uniform sampler2D uTextureB;
uniform float uProgress;
uniform float uRawProgress;
uniform vec2 uResolution;
uniform float uTime;
uniform float uDuration;
uniform vec2 uDirection;
uniform float uOutputPremultiplied;
in vec2 vUv;
out vec4 oColor;
void main() {
    float p = clamp(uProgress, 0.0, 1.0);
    float f = 0.02;
    float pp = p * (1.0 + 2.0 * f) - f;
    float ang = atan(vUv.x - 0.5, 0.5 - vUv.y) / 6.28318530718 + 0.5;
    float edge = smoothstep(pp - f, pp + f, ang);
    vec4 a = texture(uTextureA, vUv);
    vec4 b = texture(uTextureB, vUv);
    vec4 c = mix(b, a, edge);
    if (uOutputPremultiplied > 0.5) { oColor = vec4(c.rgb * c.a, c.a); } else { oColor = c; }
}
"""

    // Blur: both clips blur out/in around the midpoint of a dissolve
    private const val BLUR_FRAG = """
precision highp float;
uniform sampler2D uTextureA;
uniform sampler2D uTextureB;
uniform float uProgress;
uniform float uRawProgress;
uniform vec2 uResolution;
uniform float uTime;
uniform float uDuration;
uniform vec2 uDirection;
uniform float uOutputPremultiplied;
in vec2 vUv;
out vec4 oColor;
void main() {
    float p = clamp(uProgress, 0.0, 1.0);
    float r = sin(p * 3.14159265) * 0.03;
    vec2 asp = vec2(uResolution.y / max(uResolution.x, 1.0), 1.0);
    vec4 a = vec4(0.0);
    vec4 b = vec4(0.0);
    float wsum = 0.0;
    for (int i = -4; i <= 4; i++) {
        for (int j = -4; j <= 4; j++) {
            vec2 o = vec2(float(i), float(j)) * 0.25 * r * asp;
            float w = exp(-float(i * i + j * j) / 10.0);
            vec2 uv = clamp(vUv + o, vec2(0.0), vec2(1.0));
            a += texture(uTextureA, uv) * w;
            b += texture(uTextureB, uv) * w;
            wsum += w;
        }
    }
    a /= wsum; b /= wsum;
    vec4 c = mix(a, b, smoothstep(0.25, 0.75, p));
    if (uOutputPremultiplied > 0.5) { oColor = vec4(c.rgb * c.a, c.a); } else { oColor = c; }
}
"""

    // Zoom Blur: radial blur streaks toward the centre while cross-dissolving
    private const val ZOOM_BLUR_FRAG = """
precision highp float;
uniform sampler2D uTextureA;
uniform sampler2D uTextureB;
uniform float uProgress;
uniform float uRawProgress;
uniform vec2 uResolution;
uniform float uTime;
uniform float uDuration;
uniform vec2 uDirection;
uniform float uOutputPremultiplied;
in vec2 vUv;
out vec4 oColor;
void main() {
    float p = clamp(uProgress, 0.0, 1.0);
    float strength = sin(p * 3.14159265) * 0.25;
    vec4 a = vec4(0.0);
    vec4 b = vec4(0.0);
    for (int i = 0; i < 16; i++) {
        float t = float(i) / 15.0;
        vec2 uv = clamp((vUv - 0.5) * (1.0 - strength * t) + 0.5, vec2(0.0), vec2(1.0));
        a += texture(uTextureA, uv);
        b += texture(uTextureB, uv);
    }
    a /= 16.0; b /= 16.0;
    vec4 c = mix(a, b, smoothstep(0.3, 0.7, p));
    if (uOutputPremultiplied > 0.5) { oColor = vec4(c.rgb * c.a, c.a); } else { oColor = c; }
}
"""

    // Flash: outgoing clip burns out to white, incoming clip fades in from white
    private const val FLASH_FRAG = """
precision highp float;
uniform sampler2D uTextureA;
uniform sampler2D uTextureB;
uniform float uProgress;
uniform float uRawProgress;
uniform vec2 uResolution;
uniform float uTime;
uniform float uDuration;
uniform vec2 uDirection;
uniform float uOutputPremultiplied;
in vec2 vUv;
out vec4 oColor;
void main() {
    float p = clamp(uProgress, 0.0, 1.0);
    vec4 a = texture(uTextureA, vUv);
    vec4 b = texture(uTextureB, vUv);
    vec4 c;
    if (p < 0.5) {
        float f = smoothstep(0.0, 0.5, p);
        c = vec4(mix(a.rgb, vec3(1.0), f), a.a);
    } else {
        float f = smoothstep(0.5, 1.0, p);
        c = vec4(mix(vec3(1.0), b.rgb, f), b.a);
    }
    if (uOutputPremultiplied > 0.5) { oColor = vec4(c.rgb * c.a, c.a); } else { oColor = c; }
}
"""

    // Glitch: block displacement + RGB split that peaks mid-transition
    private const val GLITCH_FRAG = """
precision highp float;
uniform sampler2D uTextureA;
uniform sampler2D uTextureB;
uniform float uProgress;
uniform float uRawProgress;
uniform vec2 uResolution;
uniform float uTime;
uniform float uDuration;
uniform vec2 uDirection;
uniform float uOutputPremultiplied;
in vec2 vUv;
out vec4 oColor;
float hash11(float n) { return fract(sin(n * 127.1) * 43758.5453123); }
void main() {
    float p = clamp(uProgress, 0.0, 1.0);
    float amt = sin(p * 3.14159265);
    float tick = floor(p * 24.0);
    float band = floor(vUv.y * 24.0);
    float h = hash11(band + tick * 7.0);
    float jitter = (h > 0.6 ? (h - 0.8) : 0.0) * 0.25 * amt;
    float split = 0.012 * amt;
    vec2 uv = vec2(clamp(vUv.x + jitter, 0.0, 1.0), vUv.y);
    float m = step(hash11(band * 3.1 + tick), p);
    vec4 aR = texture(uTextureA, vec2(clamp(uv.x + split, 0.0, 1.0), uv.y));
    vec4 aG = texture(uTextureA, uv);
    vec4 aB = texture(uTextureA, vec2(clamp(uv.x - split, 0.0, 1.0), uv.y));
    vec4 bR = texture(uTextureB, vec2(clamp(uv.x + split, 0.0, 1.0), uv.y));
    vec4 bG = texture(uTextureB, uv);
    vec4 bB = texture(uTextureB, vec2(clamp(uv.x - split, 0.0, 1.0), uv.y));
    vec4 a = vec4(aR.r, aG.g, aB.b, aG.a);
    vec4 b = vec4(bR.r, bG.g, bB.b, bG.a);
    vec4 c = mix(a, b, m);
    if (p > 0.97) { c = b; }
    if (p < 0.03) { c = a; }
    if (uOutputPremultiplied > 0.5) { oColor = vec4(c.rgb * c.a, c.a); } else { oColor = c; }
}
"""

    // Glitch Wipe: horizontal wipe whose edge is torn per scanline band
    private const val GLITCH_WIPE_FRAG = """
precision highp float;
uniform sampler2D uTextureA;
uniform sampler2D uTextureB;
uniform float uProgress;
uniform float uRawProgress;
uniform vec2 uResolution;
uniform float uTime;
uniform float uDuration;
uniform vec2 uDirection;
uniform float uOutputPremultiplied;
in vec2 vUv;
out vec4 oColor;
float hash11(float n) { return fract(sin(n * 127.1) * 43758.5453123); }
void main() {
    float p = clamp(uProgress, 0.0, 1.0);
    float band = floor(vUv.y * 32.0);
    float tear = (hash11(band + floor(p * 16.0)) - 0.5) * 0.25 * sin(p * 3.14159265);
    float pp = p * 1.2 - 0.1;
    float edge = step(pp, vUv.x + tear);
    float split = 0.01 * sin(p * 3.14159265);
    vec4 a = texture(uTextureA, vUv);
    vec4 b = texture(uTextureB, vUv);
    vec4 aS = texture(uTextureA, vec2(clamp(vUv.x + split, 0.0, 1.0), vUv.y));
    vec4 bS = texture(uTextureB, vec2(clamp(vUv.x - split, 0.0, 1.0), vUv.y));
    vec4 c = mix(vec4(bS.r, b.g, b.b, b.a), vec4(aS.r, a.g, a.b, a.a), edge);
    if (uOutputPremultiplied > 0.5) { oColor = vec4(c.rgb * c.a, c.a); } else { oColor = c; }
}
"""

    // Spin: rotating cross-zoom; A rotates away, B rotates in to rest
    private const val SPIN_FRAG = """
precision highp float;
uniform sampler2D uTextureA;
uniform sampler2D uTextureB;
uniform float uProgress;
uniform float uRawProgress;
uniform vec2 uResolution;
uniform float uTime;
uniform float uDuration;
uniform vec2 uDirection;
uniform float uOutputPremultiplied;
in vec2 vUv;
out vec4 oColor;
void main() {
    float p = clamp(uProgress, 0.0, 1.0);
    float e = p * p * (3.0 - 2.0 * p);
    float zoomK = 1.0 + 0.6 * sin(p * 3.14159265);
    float asp = uResolution.x / max(uResolution.y, 1.0);
    vec2 q = (vUv - 0.5) * vec2(asp, 1.0);
    float angA = e * 3.14159265;
    float angB = (e - 1.0) * 3.14159265;
    mat2 rA = mat2(cos(angA), -sin(angA), sin(angA), cos(angA));
    mat2 rB = mat2(cos(angB), -sin(angB), sin(angB), cos(angB));
    vec2 uvA = (rA * q) / zoomK / vec2(asp, 1.0) + 0.5;
    vec2 uvB = (rB * q) / zoomK / vec2(asp, 1.0) + 0.5;
    vec4 a = texture(uTextureA, clamp(uvA, vec2(0.0), vec2(1.0)));
    vec4 b = texture(uTextureB, clamp(uvB, vec2(0.0), vec2(1.0)));
    vec4 c = mix(a, b, smoothstep(0.3, 0.7, p));
    if (uOutputPremultiplied > 0.5) { oColor = vec4(c.rgb * c.a, c.a); } else { oColor = c; }
}
"""

    // Whip Pan: fast slide with horizontal motion blur
    private const val WHIP_PAN_FRAG = """
precision highp float;
uniform sampler2D uTextureA;
uniform sampler2D uTextureB;
uniform float uProgress;
uniform float uRawProgress;
uniform vec2 uResolution;
uniform float uTime;
uniform float uDuration;
uniform vec2 uDirection;
uniform float uOutputPremultiplied;
in vec2 vUv;
out vec4 oColor;
void main() {
    float p = clamp(uProgress, 0.0, 1.0);
    float e = p * p * (3.0 - 2.0 * p);
    float blur = sin(p * 3.14159265) * 0.12;
    vec4 acc = vec4(0.0);
    for (int i = 0; i < 12; i++) {
        float o = (float(i) / 11.0 - 0.5) * blur;
        float x = vUv.x + o;
        vec4 s;
        if (x > e) { s = texture(uTextureA, vec2(clamp(x - e, 0.0, 1.0), vUv.y)); }
        else       { s = texture(uTextureB, vec2(clamp(x + 1.0 - e, 0.0, 1.0), vUv.y)); }
        acc += s;
    }
    vec4 c = acc / 12.0;
    if (uOutputPremultiplied > 0.5) { oColor = vec4(c.rgb * c.a, c.a); } else { oColor = c; }
}
"""

    // Light Leak: cross-dissolve with a warm animated light bloom at the midpoint
    private const val LIGHT_LEAK_FRAG = """
precision highp float;
uniform sampler2D uTextureA;
uniform sampler2D uTextureB;
uniform float uProgress;
uniform float uRawProgress;
uniform vec2 uResolution;
uniform float uTime;
uniform float uDuration;
uniform vec2 uDirection;
uniform float uOutputPremultiplied;
in vec2 vUv;
out vec4 oColor;
void main() {
    float p = clamp(uProgress, 0.0, 1.0);
    vec4 a = texture(uTextureA, vUv);
    vec4 b = texture(uTextureB, vUv);
    vec4 base = mix(a, b, smoothstep(0.35, 0.65, p));
    float k = sin(p * 3.14159265);
    k = k * k;
    float sweep = 0.5 + 0.5 * sin(vUv.x * 3.0 - p * 6.0 + vUv.y * 1.5);
    float glow = k * (0.35 + 0.65 * sweep) * (1.0 - 0.4 * length(vUv - vec2(0.8, 0.2)));
    vec3 warm = vec3(1.0, 0.55, 0.18) * glow * 1.2 + vec3(1.0, 0.85, 0.6) * glow * glow;
    vec4 c = vec4(min(base.rgb + warm, vec3(1.0)), base.a);
    if (uOutputPremultiplied > 0.5) { oColor = vec4(c.rgb * c.a, c.a); } else { oColor = c; }
}
"""

    // 17 distinct transition definitions (each with its own shader)
    fun crossDissolve() = TransitionDefinition(
        id = CROSS_DISSOLVE_ID, name = "Dissolve", family = TransitionFamily.DISSOLVE,
        version = 1, minEngineVersion = 1,
        parameters = listOf(TransitionParameterDefinition(
            "softness", "Softness", ParamType.NORMALIZED,
            ParamValue.NormalizedValue(0f),
            min = ParamValue.NormalizedValue(0f),
            max = ParamValue.NormalizedValue(0.5f))),
        shaders = mapOf("main" to ShaderSource("main", CROSS_DISSOLVE_FRAG)),
        graph = TransitionRenderGraphSpec.singlePass("main"),
        defaultDurationMs = 800,
        alphaMode = AlphaMode.OPAQUE)

    fun fade() = TransitionDefinition(
        id = FADE_ID, name = "Fade", family = TransitionFamily.LIGHT,
        version = 1, minEngineVersion = 1,
        parameters = emptyList(),
        shaders = mapOf("main" to ShaderSource("main", FADE_FRAG)),
        graph = TransitionRenderGraphSpec.singlePass("main"),
        defaultDurationMs = 700,
        alphaMode = AlphaMode.OPAQUE)

    fun zoom() = TransitionDefinition(
        id = ZOOM_ID, name = "Zoom In", family = TransitionFamily.ZOOM,
        version = 1, minEngineVersion = 1,
        parameters = listOf(
            TransitionParameterDefinition("zoomAmount", "Zoom Amount", ParamType.FLOAT,
                ParamValue.FloatValue(1.6f),
                min = ParamValue.FloatValue(1.0f), max = ParamValue.FloatValue(3.0f),
                step = ParamValue.FloatValue(0.05f), animatable = true),
            TransitionParameterDefinition("edgeSoftness", "Edge Softness", ParamType.NORMALIZED,
                ParamValue.NormalizedValue(0.15f),
                min = ParamValue.NormalizedValue(0f), max = ParamValue.NormalizedValue(1f))),
        shaders = mapOf("main" to ShaderSource("main", ZOOM_FRAG)),
        graph = TransitionRenderGraphSpec.singlePass("main"),
        defaultDurationMs = 800,
        alphaMode = AlphaMode.OPAQUE)

    fun slideLeft() = TransitionDefinition(
        id = SLIDE_LEFT_ID, name = "Slide Left", family = TransitionFamily.SLIDE,
        version = 1, minEngineVersion = 1,
        parameters = emptyList(),
        shaders = mapOf("main" to ShaderSource("main", SLIDE_LEFT_FRAG)),
        graph = TransitionRenderGraphSpec.singlePass("main"),
        defaultDurationMs = 600,
        alphaMode = AlphaMode.OPAQUE)

    fun slideRight() = TransitionDefinition(
        id = SLIDE_RIGHT_ID, name = "Slide Right", family = TransitionFamily.SLIDE,
        version = 1, minEngineVersion = 1,
        parameters = emptyList(),
        shaders = mapOf("main" to ShaderSource("main", SLIDE_RIGHT_FRAG)),
        graph = TransitionRenderGraphSpec.singlePass("main"),
        defaultDurationMs = 600,
        alphaMode = AlphaMode.OPAQUE)

    fun wipe() = TransitionDefinition(
        id = WIPE_ID, name = "Wipe", family = TransitionFamily.WIPE,
        version = 1, minEngineVersion = 1,
        parameters = listOf(
            TransitionParameterDefinition("feather", "Feather", ParamType.NORMALIZED,
                ParamValue.NormalizedValue(0.05f),
                min = ParamValue.NormalizedValue(0.0f), max = ParamValue.NormalizedValue(0.2f)),
            TransitionParameterDefinition("direction", "Direction", ParamType.VEC2,
                ParamValue.Vec2Value(listOf(1f, 0f)))),
        shaders = mapOf("main" to ShaderSource("main", WIPE_FRAG)),
        graph = TransitionRenderGraphSpec.singlePass("main"),
        defaultDurationMs = 700,
        alphaMode = AlphaMode.OPAQUE)

    fun zoomOut() = TransitionDefinition(
        id = ZOOM_OUT_ID, name = "Zoom Out", family = TransitionFamily.ZOOM,
        version = 1, minEngineVersion = 1,
        parameters = emptyList(),
        shaders = mapOf("main" to ShaderSource("main", ZOOM_OUT_FRAG)),
        graph = TransitionRenderGraphSpec.singlePass("main"),
        defaultDurationMs = 800,
        alphaMode = AlphaMode.OPAQUE)

    fun pushUp() = TransitionDefinition(
        id = PUSH_UP_ID, name = "Push Up", family = TransitionFamily.PUSH,
        version = 1, minEngineVersion = 1,
        parameters = emptyList(),
        shaders = mapOf("main" to ShaderSource("main", PUSH_UP_FRAG)),
        graph = TransitionRenderGraphSpec.singlePass("main"),
        defaultDurationMs = 600,
        alphaMode = AlphaMode.OPAQUE)

    fun flash() = TransitionDefinition(
        id = FLASH_ID, name = "Flash", family = TransitionFamily.LIGHT,
        version = 1, minEngineVersion = 1,
        parameters = emptyList(),
        shaders = mapOf("main" to ShaderSource("main", FLASH_FRAG)),
        graph = TransitionRenderGraphSpec.singlePass("main"),
        defaultDurationMs = 500,
        alphaMode = AlphaMode.OPAQUE)

    fun glitch() = TransitionDefinition(
        id = GLITCH_ID, name = "Glitch", family = TransitionFamily.GLITCH,
        version = 1, minEngineVersion = 1,
        parameters = emptyList(),
        shaders = mapOf("main" to ShaderSource("main", GLITCH_FRAG)),
        graph = TransitionRenderGraphSpec.singlePass("main"),
        defaultDurationMs = 600,
        alphaMode = AlphaMode.OPAQUE)

    fun glitchWipe() = TransitionDefinition(
        id = GLITCH_WIPE_ID, name = "Glitch Wipe", family = TransitionFamily.GLITCH,
        version = 1, minEngineVersion = 1,
        parameters = emptyList(),
        shaders = mapOf("main" to ShaderSource("main", GLITCH_WIPE_FRAG)),
        graph = TransitionRenderGraphSpec.singlePass("main"),
        defaultDurationMs = 700,
        alphaMode = AlphaMode.OPAQUE)

    fun radialWipe() = TransitionDefinition(
        id = RADIAL_WIPE_ID, name = "Radial Wipe", family = TransitionFamily.RADIAL,
        version = 1, minEngineVersion = 1,
        parameters = emptyList(),
        shaders = mapOf("main" to ShaderSource("main", RADIAL_WIPE_FRAG)),
        graph = TransitionRenderGraphSpec.singlePass("main"),
        defaultDurationMs = 800,
        alphaMode = AlphaMode.OPAQUE)

    fun blur() = TransitionDefinition(
        id = BLUR_ID, name = "Blur", family = TransitionFamily.BLUR,
        version = 1, minEngineVersion = 1,
        parameters = emptyList(),
        shaders = mapOf("main" to ShaderSource("main", BLUR_FRAG)),
        graph = TransitionRenderGraphSpec.singlePass("main"),
        defaultDurationMs = 800,
        alphaMode = AlphaMode.OPAQUE)

    fun zoomBlur() = TransitionDefinition(
        id = ZOOM_BLUR_ID, name = "Zoom Blur", family = TransitionFamily.BLUR,
        version = 1, minEngineVersion = 1,
        parameters = emptyList(),
        shaders = mapOf("main" to ShaderSource("main", ZOOM_BLUR_FRAG)),
        graph = TransitionRenderGraphSpec.singlePass("main"),
        defaultDurationMs = 700,
        alphaMode = AlphaMode.OPAQUE)

    fun spin() = TransitionDefinition(
        id = SPIN_ID, name = "Spin", family = TransitionFamily.CAMERA,
        version = 1, minEngineVersion = 1,
        parameters = emptyList(),
        shaders = mapOf("main" to ShaderSource("main", SPIN_FRAG)),
        graph = TransitionRenderGraphSpec.singlePass("main"),
        defaultDurationMs = 900,
        alphaMode = AlphaMode.OPAQUE)

    fun whipPan() = TransitionDefinition(
        id = WHIP_PAN_ID, name = "Whip Pan", family = TransitionFamily.CAMERA,
        version = 1, minEngineVersion = 1,
        parameters = emptyList(),
        shaders = mapOf("main" to ShaderSource("main", WHIP_PAN_FRAG)),
        graph = TransitionRenderGraphSpec.singlePass("main"),
        defaultDurationMs = 500,
        alphaMode = AlphaMode.OPAQUE)

    fun lightLeak() = TransitionDefinition(
        id = LIGHT_LEAK_ID, name = "Light Leak", family = TransitionFamily.LIGHT,
        version = 1, minEngineVersion = 1,
        parameters = emptyList(),
        shaders = mapOf("main" to ShaderSource("main", LIGHT_LEAK_FRAG)),
        graph = TransitionRenderGraphSpec.singlePass("main"),
        defaultDurationMs = 900,
        alphaMode = AlphaMode.OPAQUE)

    fun allBuiltins(): List<TransitionDefinition> = listOf(
        crossDissolve(),
        fade(),
        slideLeft(),
        slideRight(),
        zoom(),
        zoomOut(),
        pushUp(),
        wipe(),
        radialWipe(),
        blur(),
        zoomBlur(),
        flash(),
        glitch(),
        glitchWipe(),
        spin(),
        whipPan(),
        lightLeak()
    )

    fun builtinProvider() = object : TransitionProvider {
        override fun loadDefinitions() = allBuiltins()
    }
}
