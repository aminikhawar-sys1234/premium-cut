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
print("Contract B - bindChromaBackgroundSampler pins uChromaBgTex to its own unit")
try:
    helper = function_body(renderer_src, "bindChromaBackgroundSampler")
except AssertionError as e:
    helper = ""
    check(False, str(e))
if helper:
    check('glGetUniformLocation(program, "uChromaBgTex")' in helper, "helper resolves uChromaBgTex")
    check("GLES20.glActiveTexture(GLES20.GL_TEXTURE2)" in helper, "helper selects texture unit 2")
    check(
        re.search(r"glUniform1i\(\s*bgTexHandle\s*,\s*2\s*\)", helper) is not None,
        "helper assigns the sampler to unit 2",
    )
    check("GLES20.glActiveTexture(GLES20.GL_TEXTURE0)" in helper, "helper restores unit 0")

print()
print("Contract C - the helper is called unconditionally from bindCommonUniforms")
try:
    bind_body = function_body(renderer_src, "bindCommonUniforms")
except AssertionError as e:
    bind_body = ""
    check(False, str(e))
if bind_body:
    calls = bind_body.count("bindChromaBackgroundSampler(")
    top_calls = top_level_offsets(bind_body, "bindChromaBackgroundSampler(")
    check(calls == 1, "exactly one call site", "found %d" % calls)
    check(
        len(top_calls) == 1,
        "the call is at the top level of the function body (NOT inside `if (chromaKey.enabled)`)",
    )

print()
print("Contract D - the old conditional in-branch binding is gone")
if bind_body:
    check(
        'glGetUniformLocation(program, "uChromaBgTex")' not in bind_body,
        "bindCommonUniforms no longer resolves/binds uChromaBgTex inline",
    )
    check(
        "GLES20.glActiveTexture(GLES20.GL_TEXTURE2)" not in bind_body,
        "no second unit-2 bind remains inside bindCommonUniforms",
    )

print()
print("Contract E - targeted draw diagnostics stay wired")
check('checkDrawErrors(' in renderer_src, "checkDrawErrors helper is used")
for site in ("mainVideoTo2D", "overlayVideoTo2D", "kotlinCompositorLayer", "blitToSurface"):
    check(('"%s"' % site) in renderer_src, "draw site instrumented: %s" % site)
check("val glErrorDetail: String? = null" in renderer_src, "RenderReport carries glErrorDetail")
check("glValidateProgram" in renderer_src, "diagnostics validate the program against live sampler state")

print()
print("=" * 78)
print("RESULT: %d checks, %d failures" % (checks, len(failures)))
print("=" * 78)
if failures:
    for f in failures:
        print("FAILED: " + f)
    sys.exit(1)
