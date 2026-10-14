#!/usr/bin/env python3
"""Chroma-key shader/Kotlin parity check.

The keyer exists twice on purpose: `GpuShaders.CHROMA_KEY_GLSL` keys every preview and export frame on the
GPU, and `ChromaKeyMath` is the readable reference the JVM unit tests execute. If those two drift apart,
the editor shows something different from what the export writes - the exact class of bug this tool exists
to catch.

What it does:
  1. Reads the shared constants out of both files and requires them to be equal.
  2. Transpiles the GLSL keying helpers (`chromaOf`, `chromaAlpha`, `chromaSpillStrength`, `chromaSpill`)
     into Python and fails on any construct it does not understand (no silent skips).
  3. Runs the transpiled shader code and a Python port of the Kotlin reference over a deterministic grid of
     colours / key colours / slider values and requires them to agree within 1e-4 (alpha) and 1e-3 (rgb).

Run: python3 toolchain/verify/verify_chroma_key.py
"""

from __future__ import annotations

import math
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SHADERS = REPO / "app/src/main/java/com/example/engine/composition/gpu/GpuShaders.kt"
MATH = REPO / "app/src/main/java/com/example/engine/composition/ChromaKeyMath.kt"

FAILURES: list[str] = []


def check(condition: bool, message: str) -> None:
    if not condition:
        FAILURES.append(message)


# ----------------------------------------------------------------------------------------------
# 1. constants
# ----------------------------------------------------------------------------------------------

# Kotlin constant -> GLSL #define, in the order GpuShaders declares them.
CONSTANTS = {
    "LUMA_R": "CHROMA_LUMA_R",
    "LUMA_G": "CHROMA_LUMA_G",
    "LUMA_B": "CHROMA_LUMA_B",
    "CB_SCALE": "CHROMA_CB_SCALE",
    "CR_SCALE": "CHROMA_CR_SCALE",
    "DESATURATED_LUMA_WEIGHT": "CHROMA_DESATURATED_LUMA_WEIGHT",
    "MIN_KEY_CHROMA": "CHROMA_MIN_KEY_CHROMA",
    "SIMILARITY_SCALE": "CHROMA_SIMILARITY_SCALE",
    "SOFTNESS_SCALE": "CHROMA_SOFTNESS_SCALE",
    "MIN_FEATHER": "CHROMA_MIN_FEATHER",
    "MAX_THRESHOLD": "CHROMA_MAX_THRESHOLD",
}


def kotlin_constants(source: str) -> dict[str, float]:
    out: dict[str, float] = {}
    for name, value in re.findall(
        r"const val ([A-Z0-9_]+)\s*(?::\s*\w+)?\s*=\s*([-0-9.eE_]+)f?", source
    ):
        out[name] = float(value.replace("_", ""))
    return out


def glsl_constants(source: str) -> dict[str, float]:
    out: dict[str, float] = {}
    for name, value in re.findall(r"#define\s+([A-Z0-9_]+)\s+([-0-9.eE_]+)", source):
        out[name] = float(value.replace("_", ""))
    # The luma weights are packed in one vec3 in the shader; expand it for comparison.
    luma = re.search(r"#define\s+CHROMA_LUMA\s+vec3\(([^)]*)\)", source)
    if luma:
        parts = [p.strip() for p in luma.group(1).split(",")]
        out["CHROMA_LUMA_R"], out["CHROMA_LUMA_G"], out["CHROMA_LUMA_B"] = (float(p) for p in parts)
    return out


# ----------------------------------------------------------------------------------------------
# 2. a very small GLSL -> Python transpiler for the keying helpers
# ----------------------------------------------------------------------------------------------


class Vec3:
    """GLSL vec3 with both naming conventions (.x/.y/.z and .r/.g/.b)."""

    __slots__ = ("x", "y", "z")

    def __init__(self, x: float, y: float, z: float) -> None:
        self.x = x
        self.y = y
        self.z = z

    @property
    def r(self) -> float:
        return self.x

    @property
    def g(self) -> float:
        return self.y

    @property
    def b(self) -> float:
        return self.z


def _clamp(v, lo, hi):
    # GLSL clamps vectors component-wise, scalars directly.
    if isinstance(v, Vec3):
        return Vec3(_clamp(v.x, lo, hi), _clamp(v.y, lo, hi), _clamp(v.z, lo, hi))
    return min(max(v, lo), hi)


def _mix(a, b, t: float):
    if isinstance(a, Vec3):
        return Vec3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t)
    return a + (b - a) * t


def _smoothstep(edge0: float, edge1: float, x: float) -> float:
    t = _clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def _dot(a: Vec3, b: Vec3) -> float:
    return a.x * b.x + a.y * b.y + a.z * b.z


GLSL_FUNCS = {"sqrt": math.sqrt, "clamp": _clamp, "max": max, "min": min,
              "mix": _mix, "smoothstep": _smoothstep, "dot": _dot}


def normalize_expr(expr: str) -> str:
    expr = expr.strip().rstrip(";")
    # vec3(a, b, c) -> V(a, b, c)
    while re.search(r"vec3\(", expr):
        start = expr.index("vec3(")
        depth = 0
        for i in range(start + 4, len(expr)):
            if expr[i] == "(":
                depth += 1
            elif expr[i] == ")":
                depth -= 1
                if depth == 0:
                    inner = expr[start + 5:i]
                    expr = expr[:start] + f"V({inner})" + expr[i + 1:]
                    break
        else:  # pragma: no cover - malformed shader
            raise ValueError(f"unbalanced vec3() in: {expr}")
    expr = re.sub(r"\bfloat\b\s*", "", expr)
    return expr


def translate_function(glsl: str, name: str) -> str:
    """Turn one GLSL function body into a Python function source string."""
    match = re.search(rf"\b(?:float|vec3)\s+{name}\s*\(([^)]*)\)\s*\{{", glsl)
    if not match:
        raise ValueError(f"{name}() not found in the shader")
    params = [p.strip().split()[1] for p in match.group(1).split(",") if p.strip()]
    body_start = match.end()
    depth, i = 1, body_start
    while depth:
        if glsl[i] == "{":
            depth += 1
        elif glsl[i] == "}":
            depth -= 1
        i += 1
    body = glsl[body_start:i - 1]
    # Strip comments.
    body = re.sub(r"//[^\n]*", "", body)

    lines: list[str] = []
    indent = 1
    for raw in body.split("\n"):
        line = raw.strip()
        if not line:
            continue
        if line.startswith("}"):
            # closing brace of a block; handled by its own line below
            pass
        if re.fullmatch(r"\}\s*else\s*\{\s*", line) or re.fullmatch(r"\}else\{\s*", line):
            indent -= 1
            lines.append("    " * indent + "else:")
            indent += 1
            continue
        if line == "}" or line == "};":
            indent -= 1
            continue
        if re.fullmatch(r"else\s*\{\s*", line):
            indent -= 1
            lines.append("    " * indent + "else:")
            indent += 1
            continue
        if re.fullmatch(r"if\s*\(.*\)\s*\{\s*", line):
            cond = normalize_expr(re.sub(r"^if\s*\(|\)\s*\{\s*$", "", line))
            lines.append("    " * indent + f"if {cond}:")
            indent += 1
            continue
        if re.fullmatch(r"if\s*\(.*\)\s*(?:\{[^}]*\}|[^;{]+;)\s*", line):
            # single-statement form: `if (cond) return x;` / `if (cond) { return x; }`
            cond = normalize_expr(re.sub(r"^if\s*\(|\).*$", "", line))
            inner = re.sub(r"^if\s*\([^)]*\)\s*", "", line).strip()
            if inner == "{":
                lines.append("    " * indent + f"if {cond}:")
                indent += 1
                continue
            inner = inner.strip()
            if inner.startswith("{") and inner.endswith("}"):
                inner = inner[1:-1].strip()
            inner = inner.rstrip(";").strip()
            if inner.startswith("return "):
                lines.append("    " * indent + f"if {cond}:")
                lines.append("    " * (indent + 1) + "return " + normalize_expr(inner[len("return "):]))
                continue
            raise ValueError(f"unsupported single-statement if in a GLSL helper: {line!r}")
        if line.startswith("return "):
            lines.append("    " * indent + "return " + normalize_expr(line[len("return "):]))
            continue
        decl = re.fullmatch(r"(?:float|vec3|int)\s+(\w+)\s*=\s*(.*?);", line)
        if decl:
            lines.append("    " * indent + f"{decl.group(1)} = {normalize_expr(decl.group(2))}")
            continue
        assign = re.fullmatch(r"(\w+)\s*=\s*(.*?);", line)
        if assign:
            lines.append("    " * indent + f"{assign.group(1)} = {normalize_expr(assign.group(2))}")
            continue
        raise ValueError(f"unsupported GLSL statement in {name}(): {line!r}")

    header = f"def {name}({', '.join(params)}):"
    return "\n".join([header] + lines)


def transpile(glsl: str) -> dict:
    """Compiles the four keying helpers plus the shared constants from the shader source."""
    consts = glsl_constants(glsl)
    consts.setdefault("CHROMA_LUMA_R", 0.2126)
    consts.pop("CHROMA_LUMA", None)
    luma = Vec3(consts["CHROMA_LUMA_R"], consts["CHROMA_LUMA_G"], consts["CHROMA_LUMA_B"])
    namespace: dict = {
        "V": Vec3,
        "Vec3": Vec3,
        "float": float,
        "int": int,
        **GLSL_FUNCS,
    }
    # GLSL has no name clash with the uniform names used inside the helpers.
    namespace.update({k: v for k, v in consts.items()})
    namespace["CHROMA_LUMA"] = luma
    source = "import math\n"
    source += "\n".join(
        translate_function(glsl, fn)
        for fn in ("chromaOf", "chromaAlpha", "chromaSpillStrength", "chromaSpill")
    )
    namespace["math"] = math
    exec(compile(source, "<glsl>", "exec"), namespace)  # noqa: S102 - fixed, in-repo source
    return namespace


# ----------------------------------------------------------------------------------------------
# 3. the Kotlin reference, ported 1:1 (its JVM twin is executed by ChromaKeyMathTest)
# ----------------------------------------------------------------------------------------------


class KotlinReference:
    def __init__(self, constants: dict[str, float]) -> None:
        self.c = constants

    def chroma(self, r: float, g: float, b: float) -> tuple[float, float, float]:
        c = self.c
        y = c["LUMA_R"] * r + c["LUMA_G"] * g + c["LUMA_B"] * b
        return ((b - y) / c["CB_SCALE"], (r - y) / c["CR_SCALE"], y)

    def alpha_for(self, px, key, similarity: float, softness: float) -> float:
        c = self.c
        dcb = px[0] - key[0]
        dcr = px[1] - key[1]
        chroma_distance = math.sqrt(dcb * dcb + dcr * dcr)
        key_length = math.sqrt(key[0] * key[0] + key[1] * key[1])
        distance = chroma_distance
        if key_length < c["MIN_KEY_CHROMA"]:
            dl = (px[2] - key[2]) * c["DESATURATED_LUMA_WEIGHT"]
            distance = math.sqrt(chroma_distance * chroma_distance + dl * dl)
        threshold = min(max(similarity, 0.0), c["MAX_THRESHOLD"]) * c["SIMILARITY_SCALE"]
        feather = max(c["MIN_FEATHER"], min(max(softness, 0.0), 1.0) * c["SOFTNESS_SCALE"])
        t = min(max((distance - threshold) / feather, 0.0), 1.0)
        return t * t * (3.0 - 2.0 * t)

    def spill_strength(self, keyed_alpha: float, spill: float, edge: float) -> float:
        e = min(max(edge, 0.0), 1.0) * (1.0 - min(max(keyed_alpha, 0.0), 1.0))
        return min(max(min(max(spill, 0.0), 1.0) + e, 0.0), 1.0)

    def suppress_spill(self, rgb, px, key, spill: float):
        c = self.c
        s = min(max(spill, 0.0), 1.0)
        if s <= 0.0:
            return rgb
        key_length = math.sqrt(key[0] * key[0] + key[1] * key[1])
        if key_length < 1e-5:
            return rgb
        kx, ky = key[0] / key_length, key[1] / key_length
        projection = px[0] * kx + px[1] * ky
        if projection <= 0.0:
            return rgb
        cb = px[0] - kx * projection * s
        cr = px[1] - ky * projection * s
        y = px[2]
        r = y + cr * c["CR_SCALE"]
        b = y + cb * c["CB_SCALE"]
        g = (y - c["LUMA_R"] * r - c["LUMA_B"] * b) / c["LUMA_G"]
        return (
            min(max(r, 0.0), 1.0),
            min(max(g, 0.0), 1.0),
            min(max(b, 0.0), 1.0),
        )


# ----------------------------------------------------------------------------------------------
# 4. compare
# ----------------------------------------------------------------------------------------------


def main() -> int:
    shader_source = SHADERS.read_text()
    math_source = MATH.read_text()

    kt = kotlin_constants(math_source)
    gl = glsl_constants(shader_source)
    for kt_name, gl_name in CONSTANTS.items():
        if kt_name not in kt:
            FAILURES.append(f"ChromaKeyMath.kt is missing const val {kt_name}")
            continue
        if gl_name not in gl:
            FAILURES.append(f"GpuShaders.kt is missing #define {gl_name}")
            continue
        check(
            abs(kt[kt_name] - gl[gl_name]) < 1e-9,
            f"{kt_name} differs: Kotlin {kt[kt_name]} vs GLSL {gl[gl_name]}",
        )

    try:
        shader = transpile(shader_source)
    except ValueError as error:
        print(f"FAIL - could not transpile the shader keying code: {error}")
        return 1

    reference = KotlinReference(kt)
    chroma_of_shader = shader["chromaOf"]
    chroma_alpha_shader = shader["chromaAlpha"]
    spill_strength_shader = shader["chromaSpillStrength"]
    spill_shader = shader["chromaSpill"]

    def set_uniforms(similarity: float, softness: float, spill: float, edge: float) -> None:
        shader["uChromaSimilarity"] = similarity
        shader["uChromaSmoothness"] = softness
        shader["uChromaSpill"] = spill
        shader["uChromaEdge"] = edge

    keys = [
        (0.0, 0.694, 0.251),   # 0xFF00B140 green screen
        (1.0, 1.0, 1.0),       # white key
        (0.0, 0.0, 0.0),       # black key
        (0.0, 0.278, 0.733),   # 0xFF0047BB blue screen
        (0.0, 1.0, 0.0),
        (1.0, 0.0, 1.0),
    ]
    colours = [
        (r / 12.0, g / 12.0, b / 12.0)
        for r in range(13)
        for g in range(13)
        for b in range(13)
    ]
    colours += [
        (0.0, 0.694, 0.251), (0.25, 0.75, 0.35), (0.85, 0.66, 0.55),
        (0.07, 0.065, 0.07), (0.0, 0.278, 0.733), (0.9, 0.9, 0.95),
        (0.65, 0.78, 0.6), (0.66, 0.63, 0.78),
    ]
    sliders = [(0.05, 0.01, 0.0, 0.0), (0.4, 0.15, 0.5, 0.0), (0.95, 0.5, 1.0, 1.0),
               (0.2, 0.3, 0.25, 0.75)]

    max_alpha_delta = 0.0
    max_rgb_delta = 0.0
    comparisons = 0
    for key_rgb in keys:
        key_chroma = reference.chroma(*key_rgb)
        shader_key = Vec3(*key_rgb)
        shader_key_chroma = chroma_of_shader(shader_key)
        for channel, (a, b) in enumerate(zip(key_chroma, (shader_key_chroma.x, shader_key_chroma.y, shader_key_chroma.z))):
            check(abs(a - b) < 1e-6, f"chromaOf disagrees on channel {channel} for key {key_rgb}: {a} vs {b}")
        for colour in colours:
            px = reference.chroma(*colour)
            shader_px = chroma_of_shader(Vec3(*colour))
            for similarity, softness, spill, edge in sliders:
                set_uniforms(similarity, softness, spill, edge)
                expected_alpha = reference.alpha_for(px, key_chroma, similarity, softness)
                actual_alpha = chroma_alpha_shader(Vec3(*colour), shader_key)
                max_alpha_delta = max(max_alpha_delta, abs(expected_alpha - actual_alpha))
                comparisons += 1

                expected_strength = reference.spill_strength(expected_alpha, spill, edge)
                actual_strength = spill_strength_shader(expected_alpha)
                check(
                    abs(expected_strength - actual_strength) < 1e-6,
                    f"chromaSpillStrength differs for alpha {expected_alpha}: "
                    f"{expected_strength} vs {actual_strength}",
                )

                expected_rgb = reference.suppress_spill(colour, px, key_chroma, expected_strength)
                actual_rgb = spill_shader(Vec3(*colour), shader_key, actual_strength)
                for i, (e, a) in enumerate(zip(expected_rgb, (actual_rgb.x, actual_rgb.y, actual_rgb.z))):
                    max_rgb_delta = max(max_rgb_delta, abs(e - a))
                    check(
                        abs(e - a) < 1e-3,
                        f"spill differs on channel {i} for colour {colour} / key {key_rgb}: {e} vs {a}",
                    )

    check(max_alpha_delta < 1e-4, f"alpha mismatch between shader and Kotlin reference: {max_alpha_delta}")
    check(max_rgb_delta < 1e-3, f"spill mismatch between shader and Kotlin reference: {max_rgb_delta}")

    # The shader must also be wired to the three background modes and to the edge-cleanup helper.
    for needle in ("uChromaBgType", "uChromaBgTex", "uChromaBgFit", "uChromaFgOpacity", "chromaSpillStrength(keyAlpha)"):
        check(needle in shader_source, f"GpuShaders.kt no longer uses {needle} in the chroma block")

    if FAILURES:
        print(f"FAIL - {len(FAILURES)} parity problem(s):")
        for failure in FAILURES[:20]:
            print(f"  - {failure}")
        return 1
    print(
        "OK - chroma key parity: shared constants equal, "
        f"{comparisons} colour/slider combinations keyed identically "
        f"(max alpha delta {max_alpha_delta:.2e}, max spill delta {max_rgb_delta:.2e})"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
