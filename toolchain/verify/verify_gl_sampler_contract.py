#!/usr/bin/env python3
"""GLES sampler-unit contract for the export compositor (black-export 0x502 drift guard).

The device failure this pins down: every exported frame black, abort message
"layers=1 texture=N compositor=kotlin glErrors=[0x502]".

Root cause: `GpuShaders.buildFragmentShader(isOes = true)` links a program with two samplers of
DIFFERENT types — `samplerExternalOES uTexture` (unit 0) and `sampler2D uChromaBgTex`. A freshly
linked program starts every sampler uniform at texture unit 0, and the `uChromaBgTex` unit
assignment used to run only when the chroma key was enabled. For every ordinary video frame the
program therefore had a samplerExternalOES AND a sampler2D both pointing at unit 0 — a state the
GLES 2.0 (2.10.5) / 3.0 (2.11.9) specs define as a draw-time failure: glDrawArrays raises
GL_INVALID_OPERATION (0x502) and writes no fragments. On spec-enforcing drivers the OES->2D
conversion pass stayed black every frame, so the whole export was black while every encoder-side
statistic looked correct.

Contracts enforced on the checked-in sources:

  A. The OES fragment shader still declares exactly the two known samplers (uTexture external,
     uChromaBgTex 2D) — a new sampler must not appear without revisiting unit assignments.
  B. `GpuCompositionRenderer.bindChromaBackgroundSampler` exists and pins uChromaBgTex to its own
     texture unit (GL_TEXTURE2 / glUniform1i(..., 2)), restoring GL_TEXTURE0 afterwards.
  C. `bindCommonUniforms` calls it UNCONDITIONALLY — at the top level of the function body, not
     nested inside any `if` (the bug was exactly a chroma-enabled-only binding).
  D. No other `uChromaBgTex` unit assignment remains inside bindCommonUniforms (the old
     conditional binding must not come back beside the new one).
  E. The targeted draw diagnostics stay wired: the OES->2D conversion and the encoder blit call
     `checkDrawErrors`, and the RenderReport carries `glErrorDetail`.

Run: python3 toolchain/verify/verify_gl_sampler_contract.py
"""

from __future__ import annotations

import os
import re
import sys

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
MAIN = os.path.join(REPO, "app", "src", "main", "java")

SHADERS = os.path.join(MAIN, "com/example/engine/composition/gpu/GpuShaders.kt")
RENDERER = os.path.join(MAIN, "com/example/engine/composition/gpu/GpuCompositionRenderer.kt")

checks = 0
failures: list[str] = []


def check(ok: bool, label: str, detail: str = "") -> None:
    global checks
    checks += 1
    print(("  PASS  " if ok else "  FAIL  ") + label + ("  " + detail if detail else ""))
    if not ok:
        failures.append(label)


def read(path: str) -> str:
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def function_body(src: str, name: str) -> str:
    """Body (between the outermost braces) of `fun name(...)`. Raises if absent."""
    m = re.search(r"fun\s+" + re.escape(name) + r"\s*\(", src)
    if not m:
        raise AssertionError("function not found: " + name)
    start = src.index("{", m.end())
    depth = 0
    for j in range(start, len(src)):
        if src[j] == "{":
            depth += 1
        elif src[j] == "}":
            depth -= 1
            if depth == 0:
                return src[start + 1 : j]
    raise AssertionError("unterminated function: " + name)


def top_level_offsets(body: str, needle: str) -> list[int]:
    """Offsets of `needle` that sit at brace depth 0 of the function body (not inside any block)."""
    out = []
    depth = 0
    i = 0
    while i < len(body):
        c = body[i]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
        elif depth == 0 and body.startswith(needle, i):
            out.append(i)
        i += 1
    return out


shaders_src = read(SHADERS)
renderer_src = read(RENDERER)

print("Contract A - OES fragment shader sampler set")
frag = re.search(r"fun buildFragmentShader\(isOes: Boolean\): String \{(.*?)\n  \}", shaders_src, re.S)
check(frag is not None, "buildFragmentShader found")
frag_src = frag.group(1) if frag else ""
check(
    'val samplerType = if (isOes) "samplerExternalOES" else "sampler2D"' in frag_src,
    "uTexture sampler type is the isOes variant",
)
check("uniform sampler2D uChromaBgTex;" in frag_src, "uChromaBgTex is a sampler2D in BOTH variants")
sampler_decls = re.findall(r"uniform\s+(\$samplerType|sampler2D|samplerExternalOES)\s+(\w+)\s*;", frag_src)
sampler_names = sorted(n for _, n in sampler_decls)
check(
    sampler_names == ["uChromaBgTex", "uTexture"],
    "the program has exactly the two known samplers",
    "found: %s" % sampler_names,
)

print()
print("Contract B - uChromaBgTex is pinned to its own unit (GpuTextureUnits.CHROMA_BG)")
try:
    bind_body = function_body(renderer_src, "bindCommonUniforms")
except AssertionError as e:
    bind_body = ""
    check(False, str(e))
if bind_body:
    check('glGetUniformLocation(program, "uChromaBgTex")' in bind_body,
          "bindCommonUniforms resolves uChromaBgTex")
    check("GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + GpuTextureUnits.CHROMA_BG)" in bind_body,
          "binding selects the registry unit, not unit 0")
    check(re.search(r"glUniform1i\(\s*bgTexHandle\s*,\s*GpuTextureUnits\.CHROMA_BG\s*\)", bind_body)
          is not None, "the sampler is assigned to GpuTextureUnits.CHROMA_BG")
    check("GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + GpuTextureUnits.PICTURE)" in bind_body,
          "the active unit is restored to the picture unit afterwards")

print()
print("Contract C - the assignment is unconditional (top level of bindCommonUniforms)")
if bind_body:
    offsets = top_level_offsets(bind_body, 'glGetUniformLocation(program, "uChromaBgTex")')
    check(len(offsets) == 1,
          "uChromaBgTex is resolved at the top level of the function body "
          "(NOT inside `if (chromaKey.enabled)`)", "found %d top-level sites" % len(offsets))
    # The guard `if (bgTexHandle >= 0)` only skips programs without the uniform; the chroma-state
    # branch must not gate it.
    idx = bind_body.find('val bgTexHandle')
    check(idx >= 0 and "if (bgTexHandle >= 0)" in bind_body[idx:idx + 400],
          "only the uniform-present guard wraps the binding")

print()
print("Contract D - no conditional chroma-only binding remains")
if bind_body:
    enabled_branch = bind_body.find("if (chromaKey.enabled)")
    check(enabled_branch >= 0, "the chroma feature branch still exists")
    # Between the feature branch start and the unconditional binding there must be no
    # glActiveTexture/glUniform1i for uChromaBgTex (the old in-branch binding).
    bg_idx = bind_body.find('val bgTexHandle')
    branch_text = bind_body[enabled_branch:bg_idx] if 0 <= enabled_branch < bg_idx else ""
    check('"uChromaBgTex"' not in branch_text,
          "the chroma-enabled branch no longer touches the uChromaBgTex sampler")
check('GLES20.glGetUniformLocation(program2D, "uChromaBgTex")' in renderer_src,
      "the 2D batch path assigns uChromaBgTex too")

print()
print("Contract E - targeted draw diagnostics stay wired")
check('checkDrawErrors(' in renderer_src, "checkDrawErrors helper is used")
for site in ("mainVideoTo2D", "overlayVideoTo2D", "kotlinCompositorLayer", "blitToSurface"):
    check(('"%s"' % site) in renderer_src, "draw site instrumented: %s" % site)
check("val glErrorDetail: String? = null" in renderer_src, "RenderReport carries glErrorDetail")
check("glValidateProgram" in renderer_src, "diagnostics validate the program against live sampler state")

print()
print("Contract F - colour-grade pipeline (ColorGpuProcessor / ColorShaderManager / ColorGradeStage)")
COLOR_PROC = os.path.join(MAIN, "com/ahstudio/color/gpu/ColorGpuProcessor.kt")
COLOR_MGR = os.path.join(MAIN, "com/ahstudio/color/gpu/ColorShaderManager.kt")
COLOR_SRC = os.path.join(MAIN, "com/ahstudio/color/gpu/ColorShaderSource.kt")
GRADE_STAGE = os.path.join(MAIN, "com/example/engine/composition/gpu/ColorGradeStage.kt")

proc_src = read(COLOR_PROC)
mgr_src = read(COLOR_MGR)
csrc_src = read(COLOR_SRC)
stage_src = read(GRADE_STAGE)

# F1. uLut3d is pinned to its unit on every draw of a HAS_LUT3D variant — including when the
#     LUT data is not loaded yet (lut3d == null). The old `if (key.hasLut3d && lut3d != null)`
#     left a sampler3D on unit 0 next to uInput: GL_INVALID_OPERATION, dropped draw, black frame.
check(
    re.search(r"if \(key\.hasLut3d\)", proc_src) is not None
    and "if (key.hasLut3d && lut3d != null)" not in proc_src,
    "uLut3d unit binding no longer depends on the LUT data being loaded",
)
check('glUniform1i(prog.loc("uLut3d"), ColorTextureUnits.LUT)' in proc_src,
      "uLut3d pinned to the registry LUT unit")
check("lutTextures.black3D()" in proc_src,
      "missing 3D LUT data binds a real texture of the sampler's own target")
check('glUniform1f(prog.loc("uLutIntensity"), if (lut3d != null) lutIntensity else 0f)' in proc_src,
      "missing LUT data renders as the identity (uLutIntensity = 0)")
# F2. The LUT2D variant pins its sampler too and keeps uLutSize finite.
check('glUniform1i(prog.loc("uLut2d"), ColorTextureUnits.LUT)' in proc_src,
      "uLut2d pinned to the registry LUT unit")
check('glUniform1f(prog.loc("uLutSize"), lutTextures.atlasSize(lut3d))' in proc_src
      and "Never 0" in proc_src,
      "uLutSize stays finite (no NaN UVs)")
check("lutTextures.white2D()" in proc_src,
      "missing 2D LUT data binds a real 2D texture (identity)")
# F3. BOTH uSecMask slots are pinned on every masked draw, even when a mask is unavailable this
#     frame (a null provider result used to leave a sampler2D on unit 0 next to a
#     samplerExternalOES uInput).
check('glUniform1i(prog.loc("uSecMask[$i]"), unit)' in proc_src,
      "uSecMask slots get their unit assignment unconditionally")
check("val fallback = if (tex == null) lutTextures.white2D() else 0" in proc_src
      and "glBindTexture(GLES30.GL_TEXTURE_2D, tex ?: fallback)" in proc_src,
      "unavailable mask binds a fallback texture instead of leaving the unit unassigned")
check("ColorTextureUnits.audit(prog" in proc_src,
      "per-variant sampler/unit audit stays wired")

# F4. Variant #defines come from the pure, JVM-tested builder and carry explicit values: the
#     shader tests flags BY VALUE, so valueless/undefined flags are preprocessor errors that made
#     every colour-grade variant fail to compile (grades silently dropped from preview + export).
check("ColorShaderSource.variantDefines(" in mgr_src,
      "ColorShaderManager builds defines via ColorShaderSource.variantDefines")
check(re.search(r'sb\.append\("#define HAS_\w+\\n"\)', mgr_src) is None,
      "no valueless feature #define remains in ColorShaderManager")
check('fun flag(name: String, on: Boolean) = sb.append("#define $name ${if (on) 1 else 0}\\n")'
      in csrc_src, "variantDefines emits explicit 0/1 flag values")
check('flag("LUT_BEFORE", lutBefore)' in csrc_src and 'flag("LUT_AFTER", !lutBefore)' in csrc_src,
      "LUT_BEFORE and LUT_AFTER are both always defined")

# F5. #extension precedes every non-preprocessor token in the generated fragment source.
frag_builder = csrc_src.split('fun fragment(defines: String): String = """')[1].split('"""')[0]
ext_pos = frag_builder.find("#extension GL_OES_EGL_image_external_essl3")
prec_pos = frag_builder.find("precision highp float;")
check(0 <= ext_pos < prec_pos,
      "#extension block precedes the precision statement in fragment()")

# F6. ColorGradeStage refuses to hand an undrawn (black) FBO texture downstream: a GL error from
#     the grade draw passes the ungraded picture through instead.
check("GLES20.glGetError()" in stage_src and "srcTex" in stage_src
      and "glError != GLES20.GL_NO_ERROR" in stage_src,
      "ColorGradeStage checks glGetError after the grade draw")
check("loggedDrawError = false" in stage_src, "draw-error log latch resets on context loss")

print()
print("=" * 78)
print("RESULT: %d checks, %d failures" % (checks, len(failures)))
print("=" * 78)
if failures:
    for f in failures:
        print("FAILED: " + f)
    sys.exit(1)
