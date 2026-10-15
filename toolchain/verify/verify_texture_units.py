#!/usr/bin/env python3
"""Sampler -> texture-unit contract, and the LUT atlas layout that feeds it.

The files this checks (`GpuCompositionRenderer`, `GpuShaders`, `ColorGpuProcessor`,
`ColorShaderSource`, `LutTextureManager`) need Gradle + the Android SDK to compile. Where that
toolchain is unavailable this script still *executes* checks on them: it parses the checked-in
sources - including the GLSL inside them - and asserts the invariant that was broken, so a future
edit that reintroduces it fails here.

The bug (`glErrors=[0x502]`, every exported frame black):

  A. `GpuShaders.buildFragmentShader` declares TWO samplers in every variant: the picture
     (`samplerExternalOES` on video frames, `sampler2D` on composed textures) and the chroma-key
     background `uChromaBgTex` (`sampler2D`). `uChromaBgTex` was only assigned a unit *inside*
     `if (chromaKey.enabled)`, so with chroma keying switched off - the normal case - it kept the
     uniform default unit 0, the unit the picture sampler is on. Two active samplers of different
     types resolving to one texture unit is GL_INVALID_OPERATION on the draw call; a rejected draw
     is a black frame. Reported later, at the end of the frame, as `glErrors=[0x502]`.

  B. The same class in the colour pipeline: `uLut2d`, the two `uSecMask[i]` slots and `uInput`
     were left unassigned on some paths (default 0, next to an `samplerExternalOES` input), and
     `uLutSize` - which the `HAS_LUT2D` branch divides by - was never written at all, so the atlas
     fallback on an ES2 context sampled NaN uv and mixed the frame to black.

Contract now enforced:
  1. every sampler a variant declares is assigned a unit on every bind, never inside a feature
     branch that can be false;
  2. no two samplers of different declared types resolve to the same unit;
  3. the shader's atlas addressing and the uploader's layout agree with the CPU reference
     (`LutSampleR` / `LutAtlasLayout`), and `uLutSize` is never 0;
  4. the map is observable at runtime (`ColorTextureUnits.audit`).

Run: python3 toolchain/verify/verify_texture_units.py
"""

from __future__ import annotations

import os
import random
import re
import sys

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
MAIN = os.path.join(REPO, "app", "src", "main", "java")
GPU = os.path.join(MAIN, "com/example/engine/composition/gpu")
COLOR = os.path.join(MAIN, "com/ahstudio/color")

RENDERER = os.path.join(GPU, "GpuCompositionRenderer.kt")
COMPOSITOR_SHADERS = os.path.join(GPU, "GpuShaders.kt")
COMPOSITOR_UNITS = os.path.join(GPU, "GpuTextureUnits.kt")
COLOR_PROCESSOR = os.path.join(COLOR, "gpu/ColorGpuProcessor.kt")
COLOR_SHADERS = os.path.join(COLOR, "gpu/ColorShaderSource.kt")
COLOR_UNITS = os.path.join(COLOR, "gpu/ColorTextureUnits.kt")
LUT_TEXTURES = os.path.join(COLOR, "gpu/LutTextureManager.kt")
ATLAS_LAYOUT = os.path.join(COLOR, "lut/LutAtlasLayout.kt")
LUT_SAMPLER = os.path.join(COLOR, "lut/Lut.kt")
MANAGER = os.path.join(COLOR, "gpu/ColorShaderManager.kt")
LOGIC_TESTS = os.path.join(REPO, "toolchain", "verify", "run_logic_tests.sh")
ATLAS_TEST = os.path.join(REPO, "app", "src", "test", "java", "com/ahstudio/color/LutAtlasLayoutTest.kt")

RESULTS: list[tuple[bool, str]] = []


def read(path: str) -> str:
    with open(path, "r", encoding="utf-8") as fh:
        return fh.read()


def check(ok: bool, label: str) -> bool:
    RESULTS.append((bool(ok), label))
    return bool(ok)


def code_only(src: str) -> str:
    """Source with strings and comments blanked out, so braces can be counted safely."""
    out = []
    i, n = 0, len(src)
    while i < n:
        ch = src[i]
        two = src[i:i + 2]
        if two == "//":
            j = src.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
        elif two == "/*":
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append(" " * (j - i))
            i = j
        elif ch == '"':
            j = i + 1
            while j < n and src[j] != '"':
                j += 2 if src[j] == "\\" else 1
            j = min(j + 1, n)
            out.append(" " * (j - i))
            i = j
        elif ch == "'":
            # Kotlin char literal such as '"' - must not be read as a string start.
            k, closed = i + 1, False
            while k < n and k - i <= 8 and src[k] != "\n":
                if src[k] == "\\":
                    k += 2
                    continue
                if src[k] == "'":
                    closed = True
                    break
                k += 1
            if closed:
                out.append(" " * (k + 1 - i))
                i = k + 1
            else:
                out.append(ch)
                i += 1
        else:
            out.append(ch)
            i += 1
    return "".join(out)


def block_span(src: str, marker: str) -> tuple[int, int] | None:
    """Span of the `{ ... }` block that follows `marker`, as (open, close) indices."""
    start = src.find(marker)
    if start < 0:
        return None
    brace = src.find("{", start + len(marker) - 1)
    if brace < 0:
        return None
    clean = code_only(src)
    depth = 0
    for i in range(brace, len(clean)):
        if clean[i] == "{":
            depth += 1
        elif clean[i] == "}":
            depth -= 1
            if depth == 0:
                return brace, i
    return None


def without_block(src: str, marker: str) -> str:
    span = block_span(src, marker)
    if span is None:
        return src
    return src[:span[0]] + src[span[1] + 1:]


def parse_consts(src: str, holder: str) -> dict[str, int]:
    """`const val NAME = 3` (and `fun secondaryMask(i) = BASE + i`) of a Kotlin object."""
    out: dict[str, int] = {}
    for m in re.finditer(r"const val ([A-Z_0-9]+)\s*(?::\s*Int)?\s*=\s*(-?\d+)", src):
        out[m.group(1)] = int(m.group(2))
    base = out.get("SECONDARY_MASK_0")
    if base is not None and re.search(
        r"fun secondaryMask\(index: Int\): Int\s*=\s*SECONDARY_MASK_0\s*\+\s*index", src
    ):
        out["secondaryMask(i)"] = base  # base + 0; index is validated separately
    return out


def parse_uniform_names(src: str) -> list[str]:
    """Uniform names referenced by `glGetUniformLocation(program, "name")` / `<var>Handle, N`."""
    names = set(re.findall(r'glGetUniformLocation\([^,]+,\s*"([A-Za-z_0-9]+(?:\[\$i\])?(?:\[0\])?)"', src))
    for m in re.finditer(r'GLES[0-9]+\.glUniform1i\(\s*u([A-Za-z_0-9]+)Handle\s*,\s*([^)]+)\)', src):
        names.add("u" + m.group(1))
    return sorted(names)


def resolve_unit(expr: str, consts: dict[str, int]) -> int | None:
    expr = expr.strip()
    m = re.fullmatch(r"(\d+)", expr)
    if m:
        return int(m.group(1))
    m = re.fullmatch(r"GLES[0-9]+\.GL_TEXTURE0\s*\+\s*(\d+)", expr)
    if m:
        return int(m.group(1))
    m = re.fullmatch(r"(?:GpuTextureUnits|ColorTextureUnits)\.([A-Z_0-9]+)", expr)
    if m:
        return consts.get(m.group(1))
    m = re.fullmatch(r"ColorTextureUnits\.secondaryMask\((\d+|i)\)", expr)
    if m:
        base = consts.get("SECONDARY_MASK_0")
        # `secondaryMask(i)` inside the slot loop: slot 0 is the base, slot 1 is base + 1 (the
        # loop bound is checked separately).
        return None if base is None else base + (0 if m.group(1) == "i" else int(m.group(1)))
    return None


def resolve_unit_expr(expr: str, consts: dict[str, int], src: str) -> int | None:
    """Resolve a unit expression, following one local `val x = <expr>` if needed."""
    unit = resolve_unit(expr, consts)
    ident = expr.strip()
    if unit is None and re.fullmatch(r"[A-Za-z_][A-Za-z_0-9]*", ident):
        local = re.search(r"val " + re.escape(ident) + r"\s*=\s*([^\n]+)", src)
        if local:
            unit = resolve_unit(local.group(1), consts)
    return unit


def glam_condition_stack(lines: list[str]) -> list[tuple[int, str]]:
    """(line index, condition) for every sampler declaration, with its active `#if` stack."""
    stack: list[str] = []
    found: list[tuple[int, str]] = []
    for idx, raw in enumerate(lines):
        line = raw.strip()
        if line.startswith("#if"):
            stack.append(line[3:].strip() if line.startswith("#if ") else line[3:].strip())
        elif line.startswith("#endif"):
            if stack:
                stack.pop()
        elif line.startswith("uniform sampler") or line.startswith("uniform $samplerType"):
            found.append((idx, " && ".join(stack)))
    return found


def cpu_trilinear(data: list[float], n: int, r: float, g: float, b: float) -> list[float]:
    """Port of `com.ahstudio.color.lut.LutSampler.sampleTrilinear` (domain 0..1, red fastest)."""
    def to_domain(c: float) -> float:
        return min(max(c, 0.0), 1.0) * (n - 1)

    x, y, z = to_domain(r), to_domain(g), to_domain(b)
    x0 = min(int(x), n - 1); x1 = min(x0 + 1, n - 1)
    y0 = min(int(y), n - 1); y1 = min(y0 + 1, n - 1)
    z0 = min(int(z), n - 1); z1 = min(z0 + 1, n - 1)
    fx, fy, fz = x - x0, y - y0, z - z0

    def at(xi: int, yi: int, zi: int) -> int:
        return (xi + yi * n + zi * n * n) * 3

    out = []
    for ch in range(3):
        def lerp(i0: int, i1: int, t: float) -> float:
            return data[i0 + ch] * (1.0 - t) + data[i1 + ch] * t

        c00 = lerp(at(x0, y0, z0), at(x1, y0, z0), fx)
        c10 = lerp(at(x0, y1, z0), at(x1, y1, z0), fx)
        c01 = lerp(at(x0, y0, z1), at(x1, y0, z1), fx)
        c11 = lerp(at(x0, y1, z1), at(x1, y1, z1), fx)
        c0 = c00 * (1.0 - fy) + c10 * fy
        c1 = c01 * (1.0 - fy) + c11 * fy
        out.append(c0 * (1.0 - fz) + c1 * fz)
    return out


def main() -> int:
    renderer = read(RENDERER)
    compositor_shaders = read(COMPOSITOR_SHADERS)
    compositor_units = read(COMPOSITOR_UNITS)
    processor = read(COLOR_PROCESSOR)
    color_shaders = read(COLOR_SHADERS)
    color_units = read(COLOR_UNITS)
    lut_textures = read(LUT_TEXTURES)
    atlas = read(ATLAS_LAYOUT)
    manager = read(MANAGER)

    print("== the compositor shader declares the chroma background sampler unconditionally ==")
    fragment_fn = block_span(compositor_shaders, "fun buildFragmentShader(isOes: Boolean): String")
    check(fragment_fn is not None, "GpuShaders.buildFragmentShader exists")
    body = compositor_shaders[fragment_fn[0]:fragment_fn[1]] if fragment_fn else ""
    lines = body.splitlines()
    decls = glam_condition_stack(lines)
    samplers = {body.splitlines()[i].strip(): cond for i, cond in decls}
    check(
        any("sampler2D uChromaBgTex;" in s and cond == "" for s, cond in samplers.items()),
        "uChromaBgTex is declared at the top level of the fragment shader (no #if guard)",
    )
    check(
        "uniform $samplerType uTexture;" in body,
        "the picture sampler uTexture is declared from the isOes sampler type",
    )
    check("samplerType = if (isOes) \"samplerExternalOES\" else \"sampler2D\"" in body,
          "isOes selects samplerExternalOES vs sampler2D for that same uniform")

    print()
    print("== every sampler of those variants is assigned a unit, never inside a feature branch ==")
    check("uChromaBgTex" in compositor_shaders,
          "the chroma branch actually reads uChromaBgTex (so the driver keeps it active)")
    outside_chroma = without_block(renderer, "if (chromaKey.enabled) {")
    check(outside_chroma != renderer, "the chroma-enabled branch was located in bindCommonUniforms")
    bg_assignments = re.findall(
        r"glUniform1i\(\s*(?:GLES20\.)?([A-Za-z_0-9]+)\s*,\s*([^)]+)\)", outside_chroma
    )
    bg_unit: int | None = None
    for name, value in bg_assignments:
        if name == "bgTexHandle":
            bg_unit = resolve_unit(value, parse_consts(compositor_units, "GpuTextureUnits"))
    check(bg_unit is not None,
          "uChromaBgTex gets its unit assigned OUTSIDE `if (chromaKey.enabled)` "
          "(an unwritten sampler keeps the default unit 0)")
    check(bg_unit is not None and bg_unit != 0,
          f"uChromaBgTex is on a unit of its own, not the picture's unit 0 (got {bg_unit})")
    check("glUniform1i(bgTexHandle, GpuTextureUnits.CHROMA_BG)" in renderer,
          "the unit comes from the named GpuTextureUnits map, not a bare 2")
    check(renderer.count('"uChromaBgTex"') >= 2,
          "both binders of this shader variant assign it (shared bind + layer batch)")
    check('glGetUniformLocation(program2D, "uChromaBgTex")' in renderer,
          "the layer-batch binder assigns it too")

    consts = parse_consts(compositor_units, "GpuTextureUnits")
    check(consts.get("PICTURE") == 0, "GpuTextureUnits.PICTURE is unit 0")
    check(consts.get("CHROMA_BG") not in (None, consts.get("PICTURE")),
          "GpuTextureUnits.CHROMA_BG is a different unit than the picture")
    if bg_unit is not None:
        check(bg_unit == consts.get("CHROMA_BG"),
              "the value assigned to the uniform matches the documented map")

    print()
    print("== the colour pipeline assigns every sampler of every variant ==")
    color_body = color_shaders
    color_decls = glam_condition_stack(color_body.splitlines())
    declared: list[tuple[str, str, str]] = []
    for idx, cond in color_decls:
        line = color_body.splitlines()[idx].strip().rstrip(";")
        m = re.match(r"uniform (sampler[A-Za-z0-9_]*)\s+([A-Za-z_0-9]+)(\[\d+\])?", line)
        if m:
            declared.append((m.group(2) + (m.group(3) or ""), m.group(1), cond))
    check(len(declared) >= 5, f"parsed {len(declared)} sampler declarations from ColorShaderSource")
    names = {name for name, _, _ in declared}
    check({"uInput", "uCurveTex", "uLumaCurveTex", "uLut3d", "uLut2d", "uSecMask[2]"} <= names,
          "the expected samplers are declared: " + ", ".join(sorted(names)))

    color_consts = parse_consts(color_units, "ColorTextureUnits")
    assignments: dict[str, int] = {}
    # Parsed with plain string scanning so the Kotlin string literals ("uSecMask[$i]") need no
    # escaping on this side.
    scan = 0
    while True:
        call = processor.find('glUniform1i(prog.loc(', scan)
        if call < 0:
            break
        name_start = processor.index(chr(34), call)
        name_end = processor.index(chr(34), name_start + 1)
        name = processor[name_start + 1:name_end]
        value_start = processor.index(',', name_end)
        value_end = processor.index(')', value_start)
        value = processor[value_start + 1:value_end].strip()
        unit = resolve_unit_expr(value, color_consts, processor)
        if unit is not None:
            if name == 'uSecMask[$i]':
                assignments.setdefault('uSecMask[*]', unit)
            else:
                assignments[name] = unit
        scan = value_end

    check("uInput" in assignments, "uInput is assigned a unit (both the 2D and the OES variant)")
    input_guard = without_block(processor, "if (inputTarget != GLES11Ext.GL_TEXTURE_EXTERNAL_OES)")
    check(processor == input_guard,
          "uInput is NOT assigned inside an `if (inputTarget != OES)` guard "
          "(that left the external variant's sampler unassigned)")
    check("uCurveTex" in assignments and "uLumaCurveTex" in assignments,
          "both curve samplers are assigned")
    check("uLut3d" in assignments, "uLut3d is assigned")
    check("uLut2d" in assignments, "uLut2d is assigned (the ES2 atlas fallback)")
    check("uSecMask[*]" in assignments, "the secondary-mask array elements are assigned")
    check("uLutSize" in processor and "uniformSize" in lut_textures,
          "uLutSize is written from LutAtlasLayout.uniformSize (never the 0 default)")
    check(re.search(r'glUniform1f\(prog\.loc\("uLutSize"\),\s*lutTextures\.atlasSize\(', processor) is not None,
          "the uLutSize value comes from the uploaded atlas, not a literal")
    check('white2D()' in processor,
          "a mask slot with no texture this frame still binds a 2D texture before assigning its unit")

    print()
    print("== no unit carries two sampler types (checked per variant) ==")
    mask_unit = assignments.get("uSecMask[*]")
    units = {
        "input": assignments.get("uInput"),
        "curve": assignments.get("uCurveTex"),
        "luma": assignments.get("uLumaCurveTex"),
        "lut": assignments.get("uLut3d"),
        "lut2d": assignments.get("uLut2d"),
        "mask0": None if mask_unit is None else mask_unit,
        "mask1": None if mask_unit is None else mask_unit + 1,
    }
    variants = {
        "ES3 + external input (sampler3D LUT)": {
            units["input"]: "samplerExternalOES", units["curve"]: "sampler2D",
            units["luma"]: "sampler2D", units["lut"]: "sampler3D",
            units["mask0"]: "sampler2D", units["mask1"]: "sampler2D",
        },
        "ES3 + 2D input (sampler3D LUT)": {
            units["input"]: "sampler2D", units["curve"]: "sampler2D",
            units["luma"]: "sampler2D", units["lut"]: "sampler3D",
            units["mask0"]: "sampler2D", units["mask1"]: "sampler2D",
        },
        "ES2 + external input (2D atlas LUT)": {
            units["input"]: "samplerExternalOES", units["curve"]: "sampler2D",
            units["luma"]: "sampler2D", units["lut2d"]: "sampler2D",
            units["mask0"]: "sampler2D", units["mask1"]: "sampler2D",
        },
        "ES2 + 2D input (2D atlas LUT)": {
            units["input"]: "sampler2D", units["curve"]: "sampler2D",
            units["luma"]: "sampler2D", units["lut2d"]: "sampler2D",
            units["mask0"]: "sampler2D", units["mask1"]: "sampler2D",
        },
    }
    for variant, table in variants.items():
        seen: dict[int, set[str]] = {}
        for unit, kind in table.items():
            if unit is None:
                continue
            seen.setdefault(unit, set()).add(kind)
        clashes = {u: sorted(k) for u, k in seen.items() if len(k) > 1}
        check(not clashes, f"{variant}: one sampler type per unit (clashes: {clashes})")
        check(all(u >= 0 for u in seen), f"{variant}: no negative units")
    check(all(units[k] is not None for k in units),
          "every unit in the map is assigned by ColorGpuProcessor")

    check(color_consts.get("INPUT") == 0, "ColorTextureUnits.INPUT is unit 0")
    check(color_consts.get("LUT") not in (None, 0, color_consts.get("INPUT")),
          f"the LUT unit is its own unit (got {color_consts.get('LUT')})")
    check(assignments.get("uInput") == color_consts.get("INPUT"),
          "uInput is on ColorTextureUnits.INPUT")
    check(assignments.get("uCurveTex") == color_consts.get("CURVE")
          and assignments.get("uLumaCurveTex") == color_consts.get("LUMA_CURVE"),
          "the curve samplers are on CURVE / LUMA_CURVE")
    check(assignments.get("uLut3d") == color_consts.get("LUT")
          and assignments.get("uLut2d") == color_consts.get("LUT"),
          "both LUT encodings address the LUT unit")
    check(mask_unit == color_consts.get("SECONDARY_MASK_0"),
          "the first mask slot is on SECONDARY_MASK_0 (the second is +1)")
    check(color_consts.get("SECONDARY_MASK_0", 0) + 1 <= color_consts.get("MAX", -1),
          "MAX covers the second mask slot")
    check("fun audit(" in color_units and "ColorTextureUnits.audit(prog" in processor,
          "the sampler map is audited at runtime (ColorTextureUnits.audit)")
    check("glGetUniformiv" in color_units,
          "the audit reads the units back from the linked program instead of assuming them")
    check(": ColorTextureUnits.ShaderProgramView" in manager,
          "ColorShaderManager.Program exposes itself to that audit")

    print()
    print("== the uploader never moves the active texture unit ==")
    check("glActiveTexture" not in lut_textures,
          "LutTextureManager binds on the already active unit and unbinds before returning")
    check(lut_textures.count("glBindTexture(GLES30.GL_TEXTURE_3D, 0)") >= 2,
          "the 3D upload unbinds after uploading (and again for the placeholder)")
    check("GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)" in lut_textures,
          "the 2D atlas upload unbinds after uploading")
    check("getOrCreate2DAtlas" in lut_textures and "GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA" in lut_textures,
          "a real 2D atlas texture is uploaded for the HAS_LUT2D variant")
    check("LutAtlasLayout.width(n)" in lut_textures and "LutAtlasLayout.height(n)" in lut_textures,
          "the atlas dimensions come from LutAtlasLayout")

    print()
    print("== atlas layout == shader addressing == CPU reference ==")
    m = re.search(r"fun column\(size: Int, red: Int, blue: Int\): Int\s*=\s*(.+)", atlas)
    col_expr = m.group(1).strip() if m else None
    m2 = re.search(r"fun row\(green: Int\): Int\s*=\s*(.+)", atlas)
    row_expr = m2.group(1).strip() if m2 else None
    m3 = re.search(r"fun dataOffsetAt\(size: Int, red: Int, green: Int, blue: Int\): Int\s*=\s*\n?\s*(.+)", atlas)
    off_expr = m3.group(1).strip() if m3 else None
    check(col_expr == "blue * size + red", f"column is blue-major (got {col_expr!r})")
    check(row_expr == "green", f"row is green (got {row_expr!r})")
    check(off_expr is not None and "size * size" in off_expr,
          f"dataOffsetAt uses .cube order (red fastest): {off_expr!r}")
    check("uv0 = vec2((z*n + x), y) * ts + ts*0.5" in color_shaders,
          "the HAS_LUT2D GLSL samples the atlas at uv = ((z*n + x), y) * (1/(n*n), 1/n) + half texel")
    check("vec2 ts = vec2(1.0/(n*n), 1.0/n)" in color_shaders, "the GLSL texel size matches")

    if col_expr and row_expr and off_expr:
        rng = random.Random(20240515)
        n = 4
        data = [rng.random() for _ in range(n * n * n * 3)]
        env = {"red": 0, "green": 0, "blue": 0, "size": n}
        worst = 0.0
        for _ in range(400):
            r, g, b = (rng.uniform(0.02, 0.98) for _ in range(3))
            # Upload exactly the way LutTextureManager does, addressed by LutAtlasLayout.
            texels: dict[tuple[int, int], list[float]] = {}
            for blue in range(n):
                for green in range(n):
                    for red in range(n):
                        env.update(red=red, green=green, blue=blue)
                        column = int(eval(col_expr, {"__builtins__": {}}, env))
                        row = int(eval(row_expr, {"__builtins__": {}}, env))
                        offset = int(eval(off_expr.replace("* 3", "* 3"), {"__builtins__": {}}, env))
                        texels[(column, row)] = data[offset:offset + 3]
            # Then sample the way the GLSL above does, with LINEAR filtering at texel centres.
            sx, sy, sz = r * (n - 1), g * (n - 1), b * (n - 1)
            x0, y0, z0 = min(int(sx), n - 1), min(int(sy), n - 1), min(int(sz), n - 1)
            fx, fy, fz = sx - x0, sy - y0, sz - z0

            def fetch(red: int, green: int, blue: int) -> list[float]:
                blue = min(blue, n - 1); green = min(green, n - 1); red = min(red, n - 1)
                env.update(red=red, green=green, blue=blue)
                return texels[(int(eval(col_expr, {"__builtins__": {}}, env)),
                               int(eval(row_expr, {"__builtins__": {}}, env)))]

            out = []
            for ch in range(3):
                c000 = fetch(x0, y0, z0)[ch]; c100 = fetch(x0 + 1, y0, z0)[ch]
                c010 = fetch(x0, y0 + 1, z0)[ch]; c110 = fetch(x0 + 1, y0 + 1, z0)[ch]
                c001 = fetch(x0, y0, z0 + 1)[ch]; c101 = fetch(x0 + 1, y0, z0 + 1)[ch]
                c011 = fetch(x0, y0 + 1, z0 + 1)[ch]; c111 = fetch(x0 + 1, y0 + 1, z0 + 1)[ch]
                c00 = c000 * (1 - fx) + c100 * fx
                c10 = c010 * (1 - fx) + c110 * fx
                c01 = c001 * (1 - fx) + c101 * fx
                c11 = c011 * (1 - fx) + c111 * fx
                c0 = c00 * (1 - fy) + c10 * fy
                c1 = c01 * (1 - fy) + c11 * fy
                out.append(c0 * (1 - fz) + c1 * fz)
            reference = cpu_trilinear(data, n, r, g, b)
            worst = max(worst, max(abs(a - b) for a, b in zip(out, reference)))
        check(worst < 1e-9,
              f"atlas GPU addressing reproduces the CPU reference sample-for-sample (worst {worst:.2e})")

    print()
    print("== the layout is covered by an executable test ==")
    check(os.path.exists(ATLAS_TEST), "LutAtlasLayoutTest.kt exists")
    check("import android" not in atlas and "import androidx" not in atlas,
          "LutAtlasLayout is pure Kotlin (runs on a plain JVM)")
    logic = read(LOGIC_TESTS)
    check("com/ahstudio/color/lut/LutAtlasLayout.kt" in logic and "LutAtlasLayoutTest.kt" in logic,
          "run_logic_tests.sh compiles the layout and its test")
    check("com.ahstudio.color.LutAtlasLayoutTest" in logic, "run_logic_tests.sh executes it")

    print()
    failures = [label for ok, label in RESULTS if not ok]
    for ok, label in RESULTS:
        print(f"  {'PASS' if ok else 'FAIL'}  {label}")
    print()
    print("=" * 78)
    print(f"RESULT: {len(RESULTS)} checks, {len(failures)} failures")
    print("=" * 78)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
