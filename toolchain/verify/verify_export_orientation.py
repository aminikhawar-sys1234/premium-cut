#!/usr/bin/env python3
"""Export-orientation drift guard + numeric pipeline simulation.

The bug this pins down: after export the video was upside down (and, with portrait metadata
sources, appeared rotated). Root cause: the final blit of `GpuCompositionRenderer.render()` mirrored
the composed frame vertically on the way into the MediaCodec encoder input Surface
(`ExportOrientationPolicy.FLIP_Y_FOR_ENCODER = true` / `FramePresentation.export(flipY = true)`),
while the preview blit (`FramePresentation.PREVIEW`) did not.

Why the encoder Surface must not be flipped: every destination this app draws to - the preview
TextureView's SurfaceTexture, the screen, and `MediaCodec.createInputSurface()` - is a
GLES-fed BufferQueue with the *same* coordinate convention (NDC y = +1 is the top of the picture,
on screen and in the encoded frame). Android's own round-trip test asserts exactly that:
`EncodeDecodeTest.generateSurfaceFrame()` draws with `glScissor` (bottom-left origin, "the upper
half of the GL window") and `checkFrame()` then expects that content in the *top* rows of the
decoded frame (`y = mHeight / 4` for the first rects, `y = (mHeight * 3) / 4` for the ones drawn at
`startY = 0`). Halo: `google/grafika`'s `TextureMovieEncoder` and the AOSP CTS decode-edit-encode
test both draw the frame into the encoder Surface with the very same shader/matrices they use for a
display surface.

Two layers of checking:

  Part 1 (source contract) - reads the checked-in Kotlin and asserts the orientation contract, so a
  future edit that reintroduces a second flip / a second rotation fails here.

  Part 2 (numeric simulation) - re-implements the exact geometry the Kotlin renderer builds
  (`Matrix.rotateM(glRotationDegrees(totalRot))`, the swapped landscape/portrait fit scales, the
  decoder's `VERTICAL_FLIP` SurfaceTexture transform, the `blitToSurface(uFlipY)` final pass) in
  Python and maps the four corners of a source frame through it. For landscape, portrait and square
  sources with 0/90/180/270 metadata it verifies that the exported frame holds the source rotated by
  the metadata amount - exactly what a player shows - and, crucially, that the map is *not*
  mirrored (determinant > 0 in image-row space). Mirroring is what an extra Y flip does, and no
  rotation or scale error can hide it.

Run: python3 toolchain/verify/verify_export_orientation.py
     python3 toolchain/verify/verify_export_orientation.py --blit-flip true   # reproduces the bug
"""

from __future__ import annotations

import argparse
import os
import re
import sys

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
MAIN = os.path.join(REPO, "app", "src", "main", "java")

POLICY = os.path.join(MAIN, "com/example/engine/export/ExportOrientationPolicy.kt")
RENDERER = os.path.join(MAIN, "com/example/engine/composition/gpu/GpuCompositionRenderer.kt")
GRAPH = os.path.join(MAIN, "com/example/engine/composition/CompositionRenderGraph.kt")
EXPORT = os.path.join(MAIN, "com/example/engine/export/AsyncFramePipelineEngine.kt")
PREVIEW = os.path.join(MAIN, "com/example/engine/preview/PreviewCompositor.kt")
SOURCE = os.path.join(MAIN, "com/example/engine/composition/gpu/HardwareVideoTextureSource.kt")

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument(
    "--blit-flip",
    choices=("true", "false"),
    help="override the encoder blit flip used by the Part 2 simulation (to reproduce the bug); "
         "the Part 1 source contract always reads the checked-in value",
)
ARGS = parser.parse_args()

checks = 0
failures: list[str] = []


def check(ok: bool, label: str, detail: str = "") -> None:
    global checks
    checks += 1
    print(("  PASS  " if ok else "  FAIL  ") + label + (f"  [{detail}]" if detail and not ok else ""))
    if not ok:
        failures.append(label)


APP = os.path.join(REPO, "app")
TEST = os.path.join(APP, "src", "test", "java")
A_TEST = os.path.join(APP, "src", "androidTest", "java")


def read(path: str) -> str:
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def strip_comments(src: str) -> str:
    """Drop `//` line comments so documentation quoting old code is not mistaken for code."""
    return "\n".join(re.sub(r"//.*$", "", line) for line in src.splitlines())


def const_bool(src: str, name: str) -> bool | None:
    m = re.search(rf"const val {name}\s*=\s*(true|false)", src)
    return None if m is None else m.group(1) == "true"


def const_int(src: str, name: str) -> int | None:
    m = re.search(rf"const val {name}\s*=\s*(-?\d+)", src)
    return None if m is None else int(m.group(1))


# ------------------------------------------------------------------------------------------------
# Part 1 - the checked-in source contract
# ------------------------------------------------------------------------------------------------
print("-- A. orientation is baked once, on the GPU, and never again --")

policy = read(POLICY)
renderer = read(RENDERER)
graph = read(GRAPH)
export = read(EXPORT)
preview = read(PREVIEW)
source = read(SOURCE)

muxer_hint = const_int(policy, "MUXER_ORIENTATION_HINT_DEGREES")
decoder_rotation = const_int(policy, "DECODER_KEY_ROTATION_DEGREES")
encoder_flip = const_bool(policy, "FLIP_Y_FOR_ENCODER")

check(muxer_hint == 0, "muxer orientation hint is 0 (the pixels already are the display orientation)", str(muxer_hint))
check(decoder_rotation == 0, "decoder KEY_ROTATION is forced to 0 (the compositor applies naturalRotation)", str(decoder_rotation))
check(encoder_flip is False, "the encoder blit is NOT Y-flipped (it shares the display's GLES orientation)", str(encoder_flip))

check(
    'setInteger(\n          MediaFormat.KEY_ROTATION,\n          com.example.engine.export.ExportOrientationPolicy.DECODER_KEY_ROTATION_DEGREES\n        )' in source
    or "ExportOrientationPolicy.DECODER_KEY_ROTATION_DEGREES" in source,
    "HardwareVideoTextureSource configures the decoder with the policy's KEY_ROTATION",
)
before_configure = source[: source.index("dec.configure(")] if "dec.configure(" in source else ""
check(
    "KEY_ROTATION" in before_configure and "DECODER_KEY_ROTATION_DEGREES" in before_configure,
    "KEY_ROTATION is set on the format BEFORE dec.configure (the decoder cannot rotate twice)",
)

# The export must take its flip from the policy constant, never from a literal.
export_code = strip_comments(export)
check(
    "FramePresentation.export(ExportOrientationPolicy.FLIP_Y_FOR_ENCODER)" in export_code,
    "the export presentation takes its flip from ExportOrientationPolicy.FLIP_Y_FOR_ENCODER",
)
check(
    "FramePresentation.export(flipY = true)" not in export_code,
    "no hard-coded flipY = true in the export pipeline",
)
check(
    "setOrientationHint(ExportOrientationPolicy.MUXER_ORIENTATION_HINT_DEGREES)" in export_code,
    "the muxer hint comes from the policy (0)",
)

# Preview and export must blit the same way: the only allowed difference is deterministic masks.
check(
    "FramePresentation.PREVIEW" in strip_comments(preview),
    "the preview renders with FramePresentation.PREVIEW",
)
check(
    "val PREVIEW = FramePresentation(flipY = false, flipX = false, deterministicMasks = false)" in graph,
    "FramePresentation.PREVIEW blits with flipY = false, flipX = false",
)
check(
    "fun export(flipY: Boolean) = FramePresentation(flipY = flipY, flipX = false, deterministicMasks = true)" in graph,
    "FramePresentation.export only varies the flip it is given and the mask determinism",
)

# The single rotation implementation, with the Android-clockwise -> GL-counter-clockwise sign.
check('fun glRotationDegrees(totalRotationDegrees: Float): Float = -totalRotationDegrees' in policy,
      "glRotationDegrees negates Android's clockwise rotation for OpenGL")
layer_matrix = renderer[renderer.index("val naturalRot = if (isOes) clip.naturalRotation else 0"):]
layer_matrix = layer_matrix[: layer_matrix.index("if (frame.activeEffects.isNotEmpty())")]
check("ExportOrientationPolicy.glRotationDegrees(totalRot)" in layer_matrix,
      "the main layer matrix rotates by glRotationDegrees(totalRot) (one rotation, right sign)")
check("Matrix.rotateM" in layer_matrix and layer_matrix.count("Matrix.rotateM") == 1,
      "the main layer applies exactly ONE rotation (no double rotation)",
      str(layer_matrix.count("Matrix.rotateM")))
check(
    "val isTransposed = (totalRot == 90f || totalRot == 270f)" in layer_matrix
    and "val localScaleX = (if (isTransposed) screenFitY else screenFitX) * userScaleX" in layer_matrix
    and "val localScaleY = (if (isTransposed) screenFitX else screenFitY) * userScaleY" in layer_matrix,
    "the fit scales swap for 90/270 sources (no sideways or squashed export)",
)
check(
    "val totalRot = ((naturalRot.toFloat() + clip.rotationDegrees.toFloat() + kf.rotation) % 360f + 360f) % 360f" in layer_matrix,
    "natural + user + keyframe rotation are summed once, normalized, and used once",
)

# The single final blit, shared by preview and export.
check(
    "blitToSurface(currentInputTex, flipY = flipYForEncoder, flipX = flipXForEncoder)" in renderer,
    "the final blit uses the presentation's flip flags (one blit for both destinations)",
)
check(
    "blitToSurface(processed, flipY = false)" in renderer,
    "internal FBO->FBO blits stay unflipped (they are not destination surfaces)",
)
shader_flip = re.search(r"float y = \(uFlipY != 0\) \? 1\.0 - coords\.y : coords\.y;", read(
    os.path.join(MAIN, "com/example/engine/composition/gpu/GpuShaders.kt")))
check(shader_flip is not None, "the blit shader flips exactly one axis (uFlipY) when asked")

# The export validator must keep rejecting a rotated container (orientation is baked in pixels).
rules = read(os.path.join(MAIN, "com/example/engine/export/ExportContentRules.kt"))
check("val expectedRotation: Int = 0" in rules and "ExportFailure.BAD_ROTATION" in rules,
      "ExportContentRules still requires rotation 0 in the exported container")

# ------------------------------------------------------------------------------------------------
# Part 2 - numeric simulation of the geometry the Kotlin renderer builds
# ------------------------------------------------------------------------------------------------
print()
print("-- B. numeric simulation: source metadata -> exported frame --")

# 3x3 matrices in image space (x right, y down); multiplication is A * B (apply B first).
def mat(rows):  # pragma: no cover - tiny helper
    return [list(r) for r in rows]


IDENT = mat([[1, 0, 0], [0, 1, 0], [0, 0, 1]])


def mul(a, b):
    out = [[0.0] * 3 for _ in range(3)]
    for i in range(3):
        for j in range(3):
            out[i][j] = sum(a[i][k] * b[k][j] for k in range(3))
    return out


def apply(m, p):
    x, y = p
    return (m[0][0] * x + m[0][1] * y + m[0][2], m[1][0] * x + m[1][1] * y + m[1][2])


def det(m):
    return m[0][0] * m[1][1] - m[0][1] * m[1][0]


def rotate_gl(degrees: float):
    """Matrix.rotateM(angle = degrees, axis z) in NDC space (y up, positive = counter-clockwise)."""
    import math

    r = math.radians(degrees)
    c, s = math.cos(r), math.sin(r)
    return mat([[c, -s, 0], [s, c, 0], [0, 0, 1]])


def scale(sx, sy):
    return mat([[sx, 0, 0], [0, sy, 0], [0, 0, 1]])


def translate(tx, ty):
    return mat([[1, 0, tx], [0, 1, ty], [0, 0, 1]])


def ndc_to_image(w: int, h: int, flip_y: bool):
    """NDC (x,y in [-1,1], y up) -> destination image pixels (x right, y down, row 0 = top).

    `flip_y` is the blit's uFlipY: it mirrors NDC y before the destination mapping.
    """
    mirror = scale(1.0, -1.0) if flip_y else IDENT
    to_px = mat([[w / 2.0, 0, w / 2.0], [0, -h / 2.0, h / 2.0], [0, 0, 1]])
    return mul(to_px, mirror)


def coded_corners(coded):
    w, h = coded
    return {"TL": (0.0, 0.0), "TR": (float(w), 0.0), "BL": (0.0, float(h)), "BR": (float(w), float(h))}


def expected_export_positions(rotation: int, coded: tuple[int, int], canvas: tuple[int, int]):
    """Where each coded corner must appear in the exported frame (the player's own view).

    A player honours the container rotation: `rotation` degrees clockwise, then the display picture
    is letterboxed into the canvas. Result: image-space pixel positions, row 0 = top.
    """
    coded_w, coded_h = coded
    canvas_w, canvas_h = canvas
    transposed = rotation in (90, 270)
    disp_w, disp_h = (coded_h, coded_w) if transposed else (coded_w, coded_h)

    # coded pixel -> display pixel (clockwise rotation by `rotation`)
    def to_display(p):
        x, y = p
        if rotation == 0:
            return (x, y)
        if rotation == 90:
            return (coded_h - y, x)
        if rotation == 180:
            return (coded_w - x, coded_h - y)
        return (y, coded_w - x)  # 270 clockwise

    fit = min(canvas_w / disp_w, canvas_h / disp_h)
    off_x = (canvas_w - disp_w * fit) / 2.0
    off_y = (canvas_h - disp_h * fit) / 2.0
    out = {}
    for name, p in coded_corners(coded).items():
        dx, dy = to_display(p)
        out[name] = (off_x + dx * fit, off_y + dy * fit)
    return out


def simulate(rotation: int, coded: tuple[int, int], canvas: tuple[int, int], blit_flip: bool):
    """Push each coded corner through the shipped pipeline; return exported positions + handedness.

    Stage 1 - sampling: the decoder is pinned to KEY_ROTATION = 0, so the quad's texture coordinate
    (u, v) - v = 1 at the *top* of the quad - samples the coded frame at (u * W, (1 - v) * H), i.e.
    the coded frame is upright in the sampler (the SurfaceTexture transform is what guarantees this
    for the OES path, and the 2D path applies the same flip explicitly).
    Stage 2 - the layer matrix the Kotlin code builds: rotate by glRotationDegrees(totalRot) (which
    is -totalRot, Android metadata being clockwise) and scale by the swapped fit factors.
    Stage 3 - the final blit into the destination: `blitToSurface(flipY)` mirrors NDC y if asked.
    """
    coded_w, coded_h = coded
    canvas_w, canvas_h = canvas
    transposed = rotation in (90, 270)
    disp_w, disp_h = (coded_h, coded_w) if transposed else (coded_w, coded_h)

    disp_aspect = disp_w / disp_h
    vp_aspect = canvas_w / canvas_h
    if disp_aspect > vp_aspect:
        fit_x, fit_y = 1.0, vp_aspect / disp_aspect
    else:
        fit_x, fit_y = disp_aspect / vp_aspect, 1.0
    local_x = fit_y if transposed else fit_x
    local_y = fit_x if transposed else fit_y

    layer = mul(rotate_gl(-(rotation % 360)), scale(local_x, local_y))
    m = mul(ndc_to_image(canvas_w, canvas_h, blit_flip), layer)

    positions = {}
    for name, (x, y) in coded_corners(coded).items():
        u, v = x / coded_w, y / coded_h          # coded frame -> sampler input
        ndc = (u * 2.0 - 1.0, (1.0 - v) * 2.0 - 1.0)   # sampler input -> quad vertex (v = 1 on top)
        positions[name] = apply(m, ndc)

    tl, tr, bl = positions["TL"], positions["TR"], positions["BL"]
    e1 = (tr[0] - tl[0], tr[1] - tl[1])
    e2 = (bl[0] - tl[0], bl[1] - tl[1])
    handed = e1[0] * e2[1] - e1[1] * e2[0]
    return positions, handed


SIM_FLIP = encoder_flip if ARGS.blit_flip is None else (ARGS.blit_flip == "true")
print(f"  sim: encoder blit flip = {SIM_FLIP} (policy says {encoder_flip})")
# Canvas = the real encoder canvas for the display aspect (each axis 16-aligned, as
# ExportEncoderPlanner/ExportSurfaceSize do: 1080 -> 1088).
geometry_cases = [
    ("landscape source, landscape canvas, metadata 0", 0, (1920, 1080), (1920, 1088)),
    ("landscape source, portrait canvas, metadata 90", 90, (1920, 1080), (1088, 1920)),
    ("landscape source, landscape canvas, metadata 180", 180, (1920, 1080), (1920, 1088)),
    ("landscape source, portrait canvas, metadata 270", 270, (1920, 1080), (1088, 1920)),
    ("portrait source, portrait canvas, metadata 0", 0, (1080, 1920), (1088, 1920)),
    ("portrait source, landscape canvas, metadata 90", 90, (1080, 1920), (1920, 1088)),
    ("portrait source, portrait canvas, metadata 180", 180, (1080, 1920), (1088, 1920)),
    ("portrait source, landscape canvas, metadata 270", 270, (1080, 1920), (1920, 1088)),
    ("square source, square canvas, metadata 0", 0, (1080, 1080), (1088, 1088)),
    ("square source, square canvas, metadata 90", 90, (1080, 1080), (1088, 1088)),
    ("square source, square canvas, metadata 180", 180, (1080, 1080), (1088, 1088)),
    ("square source, square canvas, metadata 270", 270, (1080, 1080), (1088, 1088)),
]

for label, rotation, coded, canvas in geometry_cases:
    positions, handed = simulate(rotation, coded, canvas, bool(SIM_FLIP))
    expected = expected_export_positions(rotation, coded, canvas)
    worst = max(
        max(abs(positions[k][0] - expected[k][0]), abs(positions[k][1] - expected[k][1]))
        for k in expected
    )
    tolerance = 2.0  # pixels: the canvas is 16-aligned, so a sub-pixel letterbox offset is allowed
    check(
        worst <= tolerance,
        f"{label}: exported frame holds the source rotated by its metadata, upright",
        f"corner error {worst:.2f}px",
    )
    check(handed > 0, f"{label}: not mirrored (handedness {handed:+.0f})", f"handedness {handed:+.0f}")

print()
print("  canvas coverage: source letterboxed into a canvas of the display aspect (contain fit)")
for label, rotation, coded, canvas in geometry_cases:
    positions, _ = simulate(rotation, coded, canvas, bool(SIM_FLIP))
    coded_w, coded_h = coded
    transposed = rotation in (90, 270)
    disp = (coded_h, coded_w) if transposed else (coded_w, coded_h)
    fit = min(canvas[0] / disp[0], canvas[1] / disp[1])
    check(abs(fit * disp[0] - max(p[0] for p in positions.values()) + min(p[0] for p in positions.values())) <= 2.0,
          f"{label}: picture is fully inside the canvas (no crop)", "")

print()
print("  destination parity: preview blit (flipY = false) == encoder blit")
for label, rotation, coded, canvas in geometry_cases:
    prev_positions, _ = simulate(rotation, coded, canvas, False)
    enc_positions, _ = simulate(rotation, coded, canvas, bool(SIM_FLIP))
    worst = max(
        max(abs(prev_positions[k][0] - enc_positions[k][0]), abs(prev_positions[k][1] - enc_positions[k][1]))
        for k in prev_positions
    )
    check(worst == 0.0, f"{label}: preview and exported frame have identical orientation",
          f"delta {worst:.2f}px")


# ------------------------------------------------------------------------------------------------
# Part 3 - the shipped Kotlin tests' expectations, re-evaluated
# ------------------------------------------------------------------------------------------------
# The unit test cannot run without a JDK/kotlinc and the instrumented test needs a device, so their
# expectations are re-derived here from the checked-in sources. A wrong literal (a swapped 90/270,
# a sample point outside the picture) fails this guard instead of silently blessing a regression.
print()
print("-- C. the Kotlin tests' own expectations, re-evaluated --")

policy_test = read(TEST + "/com/example/engine/export/ExportOrientationPolicyTest.kt")
factory_test = read(A_TEST + "/com/example/engine/export/TestMp4Factory.kt")
orientation_test = read(A_TEST + "/com/example/engine/export/ExportOrientationInstrumentedTest.kt")


def policy_int(name):
    m = re.search(rf"const val {name}(?::\s*\w+)?\s*=\s*(\d+)", policy)
    return int(m.group(1))


def p_norm(d):
    return ((d % 360) + 360) % 360


def p_transposed(d):
    return p_norm(d) in (90, 270)


def p_display_size(w, h, d):
    w, h = max(w, 1), max(h, 1)
    return (h, w) if p_transposed(d) else (w, h)


def p_total_rot(nat, usr, kf=0.0):
    return ((nat + usr + kf) % 360.0 + 360.0) % 360.0


check(policy_int("MUXER_ORIENTATION_HINT_DEGREES") == 0
      and "assertEquals(0, ExportOrientationPolicy.MUXER_ORIENTATION_HINT_DEGREES)" in policy_test,
      "ExportOrientationPolicyTest: the muxer hint expectation matches the shipped constant")
check(policy_int("DECODER_KEY_ROTATION_DEGREES") == 0
      and "assertEquals(0, ExportOrientationPolicy.DECODER_KEY_ROTATION_DEGREES)" in policy_test,
      "ExportOrientationPolicyTest: the decoder pin expectation matches the shipped constant")
check("assertFalse(" in policy_test and "ExportOrientationPolicy.FLIP_Y_FOR_ENCODER" in policy_test
      and encoder_flip is False,
      "ExportOrientationPolicyTest: expects the encoder blit flip to be false")

# displaySize expectations written as Kotlin pairs, e.g. assertEquals(1080 to 1920, ...displaySize(1920, 1080, 90))
size_expectations = [
    ((1920, 1080, 0), (1920, 1080)), ((1920, 1080, 90), (1080, 1920)),
    ((1920, 1080, 180), (1920, 1080)), ((1920, 1080, 270), (1080, 1920)),
    ((1080, 1920, 0), (1080, 1920)), ((1080, 1920, 90), (1920, 1080)),
    ((1080, 1920, 180), (1080, 1920)), ((1080, 1920, 270), (1920, 1080)),
    ((0, 0, 90), (1, 1)), ((0, 1920, 90), (1920, 1)),
]
for (w, h, d), want in size_expectations:
    literal = f"assertEquals({want[0]} to {want[1]}, ExportOrientationPolicy.displaySize({w}, {h}, {d}))"
    check(literal in policy_test, f"ExportOrientationPolicyTest: {literal}")
    check(p_display_size(w, h, d) == want, f"policy: displaySize({w}, {h}, {d}) == {want}")

check("for (rotation in listOf(0, 90, 180, 270))" in policy_test
      and "assertEquals(1080 to 1080, ExportOrientationPolicy.displaySize(1080, 1080, rotation))" in policy_test
      and all(p_display_size(1080, 1080, d) == (1080, 1080) for d in (0, 90, 180, 270)),
      "ExportOrientationPolicyTest: a square stays square for every metadata rotation")

# rotation math expectations (asserted in a loop in the Kotlin test)
check("for (degrees in listOf(0, 90, 180, 270))" in policy_test
      and "ExportOrientationPolicy.glRotationDegrees(degrees.toFloat())" in policy_test
      and "-degrees.toFloat()" in policy_test,
      "ExportOrientationPolicyTest: glRotationDegrees negates the metadata rotation for 0/90/180/270")
check(all(-p_total_rot(d, 0) == -float(d) for d in (0, 90, 180, 270)),
      "policy: glRotationDegrees negates the metadata rotation")
check(p_total_rot(180, 0) == 180.0 and p_total_rot(350, 10) == 0.0 and p_total_rot(-10, 20) == 10.0,
      "policy: totalRotationDegrees wraps into [0, 360)")
check(p_norm(-90) == 270 and p_norm(450) == 90 and p_norm(360) == 0 and p_transposed(-90)
      and not p_transposed(360),
      "policy: normalizeDegrees/isTransposed handle negative and wrapped metadata")

# The device test: every case must classify as rot<metadata> when the export is upright, and must
# NOT classify as that when the export is vertically mirrored (the reported bug).
luma = [int(x) for x in
        re.search(r"ORIENTATION_LUMA\s*=\s*intArrayOf\(([^)]*)\)", factory_test).group(1).split(",")]
variants = {
    "rot0": [0, 1, 2, 3], "rot90": [2, 0, 3, 1], "rot180": [3, 2, 1, 0], "rot270": [1, 3, 0, 2],
    "rot0+mirrorX": [1, 0, 3, 2], "rot90+mirrorX": [0, 2, 1, 3],
    "rot180+mirrorX": [2, 3, 0, 1], "rot270+mirrorX": [3, 1, 2, 0],
}
check(len({tuple(luma[i] for i in order) for order in variants.values()}) == 8,
      "the four quadrant levels make all eight dihedral transforms distinguishable", f"levels {luma}")
check(all(f'"{name}" to intArrayOf({", ".join(map(str, order))})' in orientation_test
          for name, order in variants.items()),
      "ExportOrientationInstrumentedTest: the transform table matches this guard")

cases = re.findall(
    r'OrientationCase\("([^"]+)",\s*(\d+),\s*(\d+),\s*(\d+),\s*AspectRatio\.(\w+),\s*(\d+),\s*(\d+)(,\s*mirrorX = true)?\)',
    orientation_test,
)
check(len(cases) >= 14, "ExportOrientationInstrumentedTest still covers every aspect and metadata",
      f"{len(cases)} cases")

for label, cw, ch, rot, _aspect, ow, oh, mirror in cases:
    cw, ch, rot, ow, oh = int(cw), int(ch), int(rot), int(ow), int(oh)
    disp_w, disp_h = p_display_size(cw, ch, rot)
    display_aspect, canvas_aspect = disp_w / disp_h, ow / oh
    if display_aspect > canvas_aspect:
        box_w, box_h = ow, round(ow / display_aspect)
    else:
        box_h, box_w = oh, round(oh * display_aspect)
    left, top = (ow - box_w) // 2, (oh - box_h) // 2

    def quadrant_luma(flip):
        out = []
        for fx, fy in ((0.25, 0.25), (0.75, 0.25), (0.25, 0.75), (0.75, 0.75)):
            x = left + int(box_w * fx)
            y = top + int(box_h * fy)
            dx = min(disp_w - 1, max(0, int((x - left) * disp_w / box_w)))
            dy = min(disp_h - 1, max(0, int((y - top) * disp_h / box_h)))
            if flip:
                dy = disp_h - 1 - dy
            dq = (1 if dx >= disp_w / 2 else 0) + (2 if dy >= disp_h / 2 else 0)
            out.append(luma[variants[f"rot{rot}"][dq]])
        if mirror:  # a horizontal user mirror swaps the left/right samples
            out = [out[1], out[0], out[3], out[2]]
        return out

    def classify(observed):
        ranked = sorted(
            (sum(abs(luma[order[i]] - observed[i]) for i in range(4)), name)
            for name, order in variants.items()
        )
        return ranked[0][1], ranked[1][0] - ranked[0][0]

    expected = f"rot{rot}" + ("+mirrorX" if mirror else "")
    got, margin = classify(quadrant_luma(False))
    check(got == expected and margin >= 20,
          f"device test would read {label} as {expected}", f"classified {got} (margin {margin})")
    got_flip, _ = classify(quadrant_luma(True))
    check(got_flip != expected,
          f"device test would reject an upside-down {label}", f"flipped frame classified {got_flip}")


# ------------------------------------------------------------------------------------------------
# Part 4 - qualified names resolve to the right nesting level
# ------------------------------------------------------------------------------------------------
# CI caught `CompositionRenderGraph.PREVIEW` in the device test (it does not compile): PREVIEW is
# declared in the companion object of the *nested* FramePresentation class, so the two-level name has
# no such member. The Kotlin compiler is the only real authority, but it is not available in every
# environment that runs this guard, so the two composition types are resolved here by nesting depth
# the way Kotlin does - including members of a nested companion object.
print()
print("-- D. qualified references resolve at the right nesting level --")

MEMBER_PATTERNS = [
    r"(?:@\w+\s+)*(?:private |public |internal |protected )?(?:const )?val ([A-Za-z_]\w*)",
    r"(?:private |public |internal |protected )?fun ([A-Za-z_]\w*)",
    r"(?:private |public |internal )?(?:data |abstract |open |sealed |enum )*(?:class|object|interface) ([A-Za-z_]\w*)",
]


def direct_members(source: str, header_regex: str) -> set:
    """Names callable as `<Header>.<name>`: the body's own declarations plus its companion's."""
    m = re.search(header_regex, source)
    if m is None:
        return set()
    depth = 1
    companion_depth = None
    names = set()
    for line in source[m.end():].split("\n"):
        stripped = line.strip()
        collect = depth == 1 or (companion_depth is not None and depth == companion_depth + 1)
        if collect:
            for pat in MEMBER_PATTERNS:
                mm = re.match(pat, stripped)
                if mm:
                    names.add(mm.group(1))
            if stripped.startswith("companion object") and depth == 1:
                companion_depth = depth
        depth += line.count("{") - line.count("}")
        if depth <= 0:
            break
    return names


graph_src = read(GRAPH)
object_members = direct_members(graph_src, r"object CompositionRenderGraph \{")
presentation_members = direct_members(graph_src, r"data class FramePresentation\([^)]*\) \{")
check(
    {"FramePresentation", "LayerRole", "LayerSample", "ResolvedLayer", "AssembledFrame"} <= object_members
    and {"present", "assemble", "layersOf"} <= object_members,
    "the nesting check reads CompositionRenderGraph's direct members",
    f"{sorted(object_members)}",
)
check("PREVIEW" in presentation_members and "PREVIEW" not in object_members,
      "PREVIEW is a member of FramePresentation (companion), not of the outer object",
      f"presentation={sorted(presentation_members)}")

# Every reference to CompositionRenderGraph in the Kotlin sources must match that nesting.
graph_refs = []
for root, _dirs, files in os.walk(os.path.join(APP, "src")):
    for f in sorted(files):
        if not f.endswith(".kt"):
            continue
        path = os.path.join(root, f)
        text = strip_comments(read(path))
        if "CompositionRenderGraph." not in text:
            continue
        for m in re.finditer(r"(?:\w+\.)*CompositionRenderGraph\.([A-Za-z_]\w*)(?:\.([A-Za-z_]\w*))?", text):
            line = text[: m.start()].count("\n") + 1
            graph_refs.append((os.path.relpath(path, REPO), line, m.group(1), m.group(2), m.group(0)))

bad = []
for rel, line, first, second, whole in graph_refs:
    if first not in object_members:
        bad.append(f"{rel}:{line} {whole} - '{first}' is not a direct member of CompositionRenderGraph")
    elif second is not None:
        if first == "FramePresentation":
            if second not in presentation_members:
                bad.append(f"{rel}:{line} {whole} - '{second}' is not a member of FramePresentation")
        else:
            nested = direct_members(graph_src, rf"(?:enum class|data class|class|object) {first} \{{")
            if nested and second not in nested:
                bad.append(f"{rel}:{line} {whole} - '{second}' is not a member of {first}")
check(len(graph_refs) >= 15, "the nesting check saw the references in app/src", f"{len(graph_refs)} references")
check(not bad, "every CompositionRenderGraph.<...> reference in app/src resolves against the real nesting",
      "; ".join(bad[:4]))
check(not any(first == "PREVIEW" for _r, _l, first, _s, _w in graph_refs),
      "no source references the two-level name CompositionRenderGraph.PREVIEW",
      "; ".join(f"{r}:{l}" for r, l, first, _s, _w in graph_refs if first == "PREVIEW"))

print()
print("=" * 78)
print(f"RESULT: {checks} checks, {len(failures)} failures")
print("=" * 78)
if failures:
    for f in failures:
        print("FAILED: " + f)
    sys.exit(1)
