#!/usr/bin/env python3
"""
Preview <-> export GLSL parity check.

Reads the two fragment shaders out of the repository and asserts that the stages
they share use the same constants. This is the drift guard for the bug where
Highlights/Shadows used Rec.601 luma + 0.0/0.5/1.0 masks on export while the preview
used Rec.709 + 0.0/0.6, 0.4/1.0, and where export sharpening ran at 2.67x the
preview's gain.

Executes on the real repository sources. Does not compile Kotlin.
"""
import os, re, sys

REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))

prev_src = open(os.path.join(REPO, "app/src/main/java/com/example/engine/effects/media3/ColorGradingGlEffect.kt")).read()
export_src = open(os.path.join(REPO, "app/src/main/java/com/example/engine/composition/gpu/GpuShaders.kt")).read()

PREVIEW = re.search(r'const val COLOR_GRADING_FRAGMENT_SHADER: String = """(.*?)"""', prev_src, re.S).group(1)
EXPORT = re.search(r'return """(.*?)"""', export_src, re.S).group(1)

print("preview shader: %d chars, %d lines" % (len(PREVIEW), PREVIEW.count("\n")))
print("export  shader: %d chars, %d lines" % (len(EXPORT), EXPORT.count("\n")))
print()

FAIL = []
N = [0]


def check(name, cond, detail=""):
    N[0] += 1
    print(("  PASS  " if cond else "  FAIL  ") + name + ("  " + detail if detail else ""))
    if not cond:
        FAIL.append(name)


def norm(s):
    """Collapse whitespace/comments so formatting differences are not counted as drift."""
    return " ".join(re.sub(r"//[^\n]*", "", s).split())


def has(shader, pattern):
    return re.search(pattern, norm(shader)) is not None


def block(shader, header):
    """Float literals of one `if (uX > ...) { ... }` block, taken from the RAW shader.

    Raises when the block is missing so a vacuous None == None can never pass.
    """
    i = shader.index(header)
    start = shader.index("{", i)
    depth = 0
    for j in range(start, len(shader)):
        if shader[j] == "{":
            depth += 1
        elif shader[j] == "}":
            depth -= 1
            if depth == 0:
                body = re.sub(r"//[^\n]*", "", shader[start:j + 1])
                return re.findall(r"-?\d+\.?\d*", body)
    raise AssertionError("unterminated block: " + header)


def luma_weights(shader, var):
    m = re.search(
        r"dot\(color\.rgb, vec3\(([^)]*)\)\)[^;]*;\s*"
        r"float \w+ = 1\.0 - smoothstep\([^)]*, %s\);" % var, norm(shader))
    return [x.strip() for x in m.group(1).split(",")] if m else None


print("-- shared uniforms must exist in both shaders --")
UNIFORMS = ["uBrightness", "uContrast", "uSaturation", "uExposure", "uTemperature", "uTint",
            "uHighlights", "uShadows", "uWhites", "uBlacks", "uFade", "uAutoEnhance",
            "uHdrBoost", "uColorFix", "uDenoise", "uGlow", "uVignette", "uGrain",
            "uGrainSeed", "uSharpness", "uClarity", "uTexelSize", "uColorMatrix",
            "uColorOffset", "uUseColorMatrix"]
for u in UNIFORMS:
    in_prev = re.search(r"uniform\s+\w+\s+%s\s*;" % u, PREVIEW) is not None
    in_exp = re.search(r"uniform\s+\w+\s+%s\s*;" % u, EXPORT) is not None
    check("uniform %-16s declared in preview and export" % u, in_prev and in_exp,
          "preview=%s export=%s" % (in_prev, in_exp))

print()
print("-- stage-by-stage constant parity --")

check("exposure uses pow(2.0, uExposure) in both",
      has(PREVIEW, r"pow\(2\.0, uExposure\)") and has(EXPORT, r"pow\(2\.0, uExposure\)"))

check("contrast pivots on 0.5 in both",
      has(PREVIEW, r"\(color\.rgb - 0\.5\) \* uContrast \+ 0\.5") and
      has(EXPORT, r"\(color\.rgb - 0\.5\) \* uContrast \+ 0\.5"))

check("saturation mixes against Rec.709 luma in both",
      has(PREVIEW, r"mix\(vec3\(lum\), color\.rgb, uSaturation\)") and
      has(EXPORT, r"mix\(vec3\(luminance\), color\.rgb, uSaturation\)"))

plw = luma_weights(PREVIEW, "lum")
elw = luma_weights(EXPORT, "l")
check("highlights/shadows luma weights identical", plw == elw, "preview=%s export=%s" % (plw, elw))
check("highlights/shadows luma weights are Rec.709 (0.2126, 0.7152, 0.0722)",
      plw == elw == ["0.2126", "0.7152", "0.0722"], "preview=%s export=%s" % (plw, elw))

def hl_edges(shader, var):
    n = norm(shader)
    m = re.search(r"float \w+ = 1\.0 - smoothstep\(([\d.]+), ([\d.]+), %s\);\s*"
                  r"float \w+ = smoothstep\(([\d.]+), ([\d.]+), %s\);" % (var, var), n)
    return m.groups() if m else None


pm = hl_edges(PREVIEW, "lum")
em = hl_edges(EXPORT, "l")
check("highlight/shadow mask edges identical",
      pm == em == ("0.0", "0.6", "0.4", "1.0"), "preview=%s export=%s" % (pm, em))

pw = block(PREVIEW, "if (abs(uWhites) > 0.001 || abs(uBlacks) > 0.001) {")
ew = block(EXPORT, "if (uWhites != 0.0 || uBlacks != 0.0) {")
check("whites/blacks stage identical", pw == ew, "preview=%s export=%s" % (pw, ew))

for label, header in [
    ("autoEnhance", "if (uAutoEnhance > 0.01) {"),
    ("hdrBoost", "if (uHdrBoost > 0.01) {"),
    ("colorFix", "if (uColorFix > 0.01) {"),
    ("fade", "if (uFade > 0.01) {"),
    ("glow", "if (uGlow > 0.01) {"),
    ("clarity", "if (uClarity > 0.01) {"),
    ("grain", "if (uGrain > 0.01) {"),
    ("denoise", "if (uDenoise > 0.01) {"),
]:
    pb = block(PREVIEW, header)
    eb = block(EXPORT, header)
    check("%-12s stage identical (%d numbers)" % (label, len(pb)), pb == eb,
          "preview=%s export=%s" % (pb, eb))

check("grain amplitude is 0.25 (ColorLookModel.GRAIN_AMPLITUDE) in both",
      "0.25" in block(PREVIEW, "if (uGrain > 0.01) {") and
      "0.25" in block(EXPORT, "if (uGrain > 0.01) {"))

ps = re.findall(r"\(uSharpness \* ([\d.]+)\)", norm(PREVIEW))
es = re.findall(r"\(uSharpness \* ([\d.]+)\)", norm(EXPORT))
check("sharpen gain identical", ps == es, "preview=%s export=%s" % (ps, es))
check("sharpen gain is 1.5 (ColorLookModel.SHARPEN_GAIN)", ps == es == ["1.5"], str(ps))
check("both sharpen against a 4-tap average (* 0.25)",
      has(PREVIEW, r"\* 0\.25") and has(EXPORT, r"\* 0\.25"))
check("export no longer uses the 4.0 Laplacian form",
      not has(EXPORT, r"- 4\.0 \* c"))

pv = re.search(r"vec2 coord = \((\w+) - 0\.5\) \* 2\.0;", norm(PREVIEW))
ev = re.search(r"vec2 coord = \(([^)]+) - 0\.5\) \* 2\.0;", norm(EXPORT))
check("vignette uses the CLAMPED coordinate in both",
      pv is not None and ev is not None and pv.group(1) == "uv" and ev.group(1) == "clampedCoord",
      "preview=%s export=%s" % (pv.group(1) if pv else None, ev.group(1) if ev else None))
check("preview clamps uv before the vignette",
      has(PREVIEW, r"vec2 uv = clamp\(vTexSamplingCoord, 0\.0, 1\.0\);"))
check("export clamps clampedCoord before the vignette",
      has(EXPORT, r"vec2 clampedCoord = clamp\(vTextureCoord, 0\.0, 1\.0\);"))

check("grain uses the same seeded hash input in both",
      has(PREVIEW, r"rand\(uv \+ vec2\(uGrainSeed \* 0\.13, uGrainSeed \* 0\.17\)\)") and
      has(EXPORT, r"rand\(clampedCoord \+ vec2\(uGrainSeed \* 0\.13, uGrainSeed \* 0\.17\)\)"))

check("both clamp rgb to 0..1",
      has(PREVIEW, r"clamp\(color\.rgb, 0\.0, 1\.0\)") and has(EXPORT, r"clamp\(color\.rgb, 0\.0, 1\.0\)"))
check("preview forces opaque alpha (TextureView A=0 black-frame guard)", has(PREVIEW, r"color\.a = 1\.0;"))
check("export forces opaque alpha for non-chroma video",
      has(EXPORT, r"if \(uPremultiply < 0\.5 && uChromaEnabled == 0\) \{ color\.a = 1\.0; \}"))

print()
print("-- stage ordering --")
# Export applies contrast and saturation unconditionally (mathematically identical at
# neutral) where preview guards them; those two are compared separately.
check("preview guards contrast/saturation at neutral, export applies them unconditionally",
      has(PREVIEW, r"if \(abs\(uContrast - 1\.0\) > 0\.001\)") and
      has(PREVIEW, r"if \(abs\(uSaturation - 1\.0\) > 0\.001\)") and
      not has(EXPORT, r"if \(abs\(uContrast"))

MARKERS = ["uSharpness > 0.01", "uClarity > 0.01", "pow(2.0, uExposure)",
           "(color.rgb - 0.5) * uContrast", "uSaturation)",
           "uTemperature * 0.15", "abs(uHighlights) > 0.001", "uWhites * whiteW",
           "uAutoEnhance > 0.01", "uGlow > 0.01", "uUseColorMatrix == 1",
           "uVignette > 0.01", "uGrain > 0.01"]
pp, ep = [], []
for mk in MARKERS:
    pp.append(norm(PREVIEW).find(mk))
    ep.append(norm(EXPORT).find(mk))
check("shared stages appear in both shaders", all(x >= 0 for x in pp) and all(x >= 0 for x in ep),
      "preview=%s export=%s" % (pp, ep))
check("shared stages run in the same relative order in both shaders",
      pp == sorted(pp) and ep == sorted(ep),
      "preview=%s export=%s" % (pp, ep))

print()
print("=" * 78)
print("RESULT: %d checks, %d failures" % (N[0], len(FAIL)))
for f in FAIL:
    print("  FAILED:", f)
print("=" * 78)
sys.exit(1 if FAIL else 0)
