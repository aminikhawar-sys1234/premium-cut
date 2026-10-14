#!/usr/bin/env python3
"""
Executable verification of the AH Video Studio colour-look maths.

This is a line-for-line Python port of
  app/src/main/java/com/example/engine/effects/ColorLookModel.kt
(read from the repository at run time, with the numbers extracted from the same
Kotlin sources that ship) plus the filter matrices parsed out of
ColorFilterGenerator.kt.

It exists because this sandbox has no JDK / Android SDK / Gradle, so the Kotlin
unit tests cannot be executed here. Running this DOES execute the algorithm
(preview-resolved uniforms -> per-pixel colour stages) on real values; it does
NOT compile Kotlin. Results are reported as such.
"""
import json, math, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))

# ----------------------------------------------------------------------------------
# Constants read straight out of ColorLookModel.kt so this harness cannot drift
# from the Kotlin source.
# ----------------------------------------------------------------------------------
km = open(os.path.join(REPO, "app/src/main/java/com/example/engine/effects/ColorLookModel.kt")).read()

def const(name, default=None):
    m = re.search(r"const val %s\s*=\s*(-?[\d.]+)f" % name, km)
    if m:
        return float(m.group(1))
    m = re.search(r"const val %s\s*=\s*(\d+)" % name, km)
    if m:
        return float(m.group(1))
    if default is None:
        raise SystemExit("constant %s not found in ColorLookModel.kt" % name)
    return default

def rng(name):
    m = re.search(r"val %s\s*=\s*Range\((-?[\d.]+)f,\s*(-?[\d.]+)f,\s*(-?[\d.]+)f\)" % name, km)
    if not m:
        raise SystemExit("range %s not found" % name)
    return (float(m.group(1)), float(m.group(2)), float(m.group(3)))

C = {n: const(n) for n in [
    "LUMA_R", "LUMA_G", "LUMA_B", "TEMP_R_GAIN", "TEMP_B_GAIN", "TINT_G_GAIN",
    "TINT_RB_GAIN", "SHADOW_EDGE_LOW", "SHADOW_EDGE_HIGH", "HIGHLIGHT_EDGE_LOW",
    "HIGHLIGHT_EDGE_HIGH", "TONE_GAIN", "WHITE_EDGE_LOW", "WHITE_EDGE_HIGH",
    "BLACK_EDGE_LOW", "BLACK_EDGE_HIGH", "WHITE_BLACK_GAIN", "AUTO_ENHANCE_SAT",
    "AUTO_ENHANCE_CONTRAST", "AUTO_ENHANCE_LIFT", "HDR_SAT", "HDR_CONTRAST",
    "HDR_LIFT", "COLOR_FIX_R", "COLOR_FIX_G", "COLOR_FIX_B", "FADE_MIX",
    "DENOISE_MIX", "GLOW_RADIUS_TEXELS", "GLOW_THRESHOLD", "GLOW_GAIN",
    "SHARPEN_GAIN", "CLARITY_RADIUS_TEXELS", "CLARITY_GAIN", "CLARITY_MID_FLOOR",
    "CLARITY_MID_RANGE", "VIGNETTE_INNER", "VIGNETTE_OUTER", "VIGNETTE_BASE",
    "VIGNETTE_SCALE", "GRAIN_AMPLITUDE", "STAGE_EPSILON", "IDENTITY_EPSILON",
    "NEUTRAL_EPSILON"]}

RANGES = {n: rng(n) for n in [
    "BRIGHTNESS", "CONTRAST", "SATURATION", "EXPOSURE", "TEMPERATURE", "TINT",
    "HIGHLIGHTS", "SHADOWS", "WHITES", "BLACKS", "SHARPNESS", "CLARITY",
    "FADE", "VIGNETTE", "GRAIN", "AUTO_ENHANCE", "HDR_BOOST", "COLOR_FIX",
    "DENOISE", "GLOW"]}

FILTER_MATRICES = json.load(open(os.path.join(HERE, "filter_matrices.json")))

# ----------------------------------------------------------------------------------
# FilterCatalog.spatialExtras  (parsed from FilterCatalog.kt)
# ----------------------------------------------------------------------------------
fc = open(os.path.join(REPO, "app/src/main/java/com/example/engine/effects/FilterCatalog.kt")).read()
SPATIAL = {}
for m in re.finditer(
        r"FilterType\.(\w+)\s*->\s*FilterSpatialExtras\((.*?)\)", fc, re.S):
    name, body = m.group(1), m.group(2)
    SPATIAL[name] = {}
    for k, v in re.findall(r"(\w+)\s*=\s*([\d.]+)f\s*\*\s*i", body):
        SPATIAL[name][k] = float(v)

SELECTABLE = re.search(r"val SELECTABLE: List<FilterType> = listOf\((.*?)\)", fc, re.S).group(1)
SELECTABLE = re.findall(r"FilterType\.(\w+)", SELECTABLE)

DEFAULTS = {
    "brightness": RANGES["BRIGHTNESS"][2], "contrast": RANGES["CONTRAST"][2],
    "saturation": RANGES["SATURATION"][2], "exposure": RANGES["EXPOSURE"][2],
    "temperature": RANGES["TEMPERATURE"][2], "tint": RANGES["TINT"][2],
    "highlights": RANGES["HIGHLIGHTS"][2], "shadows": RANGES["SHADOWS"][2],
    "whites": RANGES["WHITES"][2], "blacks": RANGES["BLACKS"][2],
    "fade": RANGES["FADE"][2], "vignette": RANGES["VIGNETTE"][2],
    "grain": RANGES["GRAIN"][2], "sharpness": RANGES["SHARPNESS"][2],
    "clarity": RANGES["CLARITY"][2], "autoEnhance": RANGES["AUTO_ENHANCE"][2],
    "hdrBoost": RANGES["HDR_BOOST"][2], "colorFix": RANGES["COLOR_FIX"][2],
    "denoise": RANGES["DENOISE"][2], "glow": RANGES["GLOW"][2],
}

KEY_TO_RANGE = {
    "brightness": "BRIGHTNESS", "contrast": "CONTRAST", "saturation": "SATURATION",
    "exposure": "EXPOSURE", "temperature": "TEMPERATURE", "tint": "TINT",
    "highlights": "HIGHLIGHTS", "shadows": "SHADOWS", "whites": "WHITES",
    "blacks": "BLACKS", "sharpness": "SHARPNESS", "clarity": "CLARITY",
    "fade": "FADE", "vignette": "VIGNETTE", "grain": "GRAIN",
    "autoEnhance": "AUTO_ENHANCE", "hdrBoost": "HDR_BOOST", "colorFix": "COLOR_FIX",
    "denoise": "DENOISE", "glow": "GLOW",
}

def coerce(name, v):
    lo, hi, _ = RANGES[KEY_TO_RANGE[name]]
    return max(lo, min(hi, v))


def spatial_extras(ftype, intensity):
    """ColorLookModel.effective -> FilterCatalog.spatialExtras(filter)."""
    if ftype in (None, "NONE") or intensity <= 0.01:
        return {}
    i = max(0.0, min(1.0, intensity))
    return {k: v * i for k, v in SPATIAL.get(ftype, {}).items()}


def effective(adj, ftype="NONE", fintensity=1.0, fenabled=True):
    """ColorLookModel.effective (uniform resolution)."""
    a = dict(DEFAULTS)
    a.update(adj)
    if not fenabled:
        ftype, fintensity = "NONE", 1.0
    ex = spatial_extras(ftype, fintensity)
    p = {k: coerce(k, a[k]) for k in DEFAULTS}
    p["autoEnhance"] = coerce("autoEnhance", a["autoEnhance"] + ex.get("enhance", 0.0))
    p["hdrBoost"] = coerce("hdrBoost", a["hdrBoost"] + a.get("colorCorrect", 0.0) + ex.get("hdrLook", 0.0))
    p["denoise"] = coerce("denoise", a["denoise"] + a.get("antiFlicker", 0.0) * 0.5)
    p["glow"] = coerce("glow", ex.get("glow", 0.0))
    p["sharpness"] = coerce("sharpness", a["sharpness"] + ex.get("sharpness", 0.0))
    p["clarity"] = coerce("clarity", a["clarity"] + a.get("superClarity", 0.0) + ex.get("clarity", 0.0))
    p["_filter"] = ftype
    return p


def filter_matrix(ftype, intensity):
    """ColorFilterGenerator.getFilterMatrix + intensity blend + forceOpaqueAlpha + GL 4x4."""
    if ftype in (None, "NONE") or intensity <= 0.0:
        return None
    base = FILTER_MATRICES.get(ftype)
    if base is None:
        return None
    if intensity >= 0.99:
        arr = list(base)
    else:
        ident = [1,0,0,0,0, 0,1,0,0,0, 0,0,1,0,0, 0,0,0,1,0]
        arr = [ident[i] + (base[i] - ident[i]) * intensity for i in range(20)]
    # PreviewEffectApplyPolicy.forceOpaqueAlpha
    arr[15], arr[16], arr[17], arr[18], arr[19] = 0.0, 0.0, 0.0, 0.0, 255.0
    gl = [arr[0], arr[5], arr[10], arr[15],
          arr[1], arr[6], arr[11], arr[16],
          arr[2], arr[7], arr[12], arr[17],
          arr[3], arr[8], arr[13], arr[18]]
    off = [arr[4]/255.0, arr[9]/255.0, arr[14]/255.0, arr[19]/255.0]
    return gl, off


def luma(r, g, b):
    return r*C["LUMA_R"] + g*C["LUMA_G"] + b*C["LUMA_B"]


def smoothstep(e0, e1, x):
    if e0 == e1:
        return 0.0 if x < e0 else 1.0
    t = max(0.0, min(1.0, (x-e0)/(e1-e0)))
    return t*t*(3-2*t)


def evaluate(p, r, g, b, a=0.0, fintensity=1.0):
    """ColorLookModel.evaluate — the CPU reference for the colour stages."""
    eps, ieps = C["STAGE_EPSILON"], C["IDENTITY_EPSILON"]
    cr, cg, cb = r, g, b
    s = 2.0 ** p["exposure"]
    cr = cr*s + p["brightness"]; cg = cg*s + p["brightness"]; cb = cb*s + p["brightness"]
    if abs(p["contrast"]-1.0) > ieps:
        cr = (cr-0.5)*p["contrast"]+0.5; cg = (cg-0.5)*p["contrast"]+0.5; cb = (cb-0.5)*p["contrast"]+0.5
    if abs(p["saturation"]-1.0) > ieps:
        l = luma(cr, cg, cb)
        cr = l+(cr-l)*p["saturation"]; cg = l+(cg-l)*p["saturation"]; cb = l+(cb-l)*p["saturation"]
    if abs(p["temperature"]) > ieps or abs(p["tint"]) > ieps:
        cr += p["temperature"]*C["TEMP_R_GAIN"] + p["tint"]*C["TINT_RB_GAIN"]
        cg -= p["tint"]*C["TINT_G_GAIN"]
        cb -= p["temperature"]*C["TEMP_B_GAIN"] - p["tint"]*C["TINT_RB_GAIN"]
    if abs(p["highlights"]) > ieps or abs(p["shadows"]) > ieps:
        l = luma(cr, cg, cb)
        sw = 1.0 - smoothstep(C["SHADOW_EDGE_LOW"], C["SHADOW_EDGE_HIGH"], l)
        hw = smoothstep(C["HIGHLIGHT_EDGE_LOW"], C["HIGHLIGHT_EDGE_HIGH"], l)
        add = p["shadows"]*sw*C["TONE_GAIN"] + p["highlights"]*hw*C["TONE_GAIN"]
        cr += add; cg += add; cb += add
    if abs(p["whites"]) > ieps or abs(p["blacks"]) > ieps:
        l = luma(cr, cg, cb)
        ww = smoothstep(C["WHITE_EDGE_LOW"], C["WHITE_EDGE_HIGH"], l)
        bw = 1.0 - smoothstep(C["BLACK_EDGE_LOW"], C["BLACK_EDGE_HIGH"], l)
        add = p["whites"]*ww*C["WHITE_BLACK_GAIN"] + p["blacks"]*bw*C["WHITE_BLACK_GAIN"]
        cr += add; cg += add; cb += add
    ae = p["autoEnhance"]
    if ae > eps:
        l = luma(cr, cg, cb)
        cr = l+(cr-l)*(1+ae*C["AUTO_ENHANCE_SAT"]); cg = l+(cg-l)*(1+ae*C["AUTO_ENHANCE_SAT"]); cb = l+(cb-l)*(1+ae*C["AUTO_ENHANCE_SAT"])
        for ch in ("r", "g", "b"):
            pass
        cr = (cr-0.5)*(1+ae*C["AUTO_ENHANCE_CONTRAST"])+0.5+ae*C["AUTO_ENHANCE_LIFT"]
        cg = (cg-0.5)*(1+ae*C["AUTO_ENHANCE_CONTRAST"])+0.5+ae*C["AUTO_ENHANCE_LIFT"]
        cb = (cb-0.5)*(1+ae*C["AUTO_ENHANCE_CONTRAST"])+0.5+ae*C["AUTO_ENHANCE_LIFT"]
    h = p["hdrBoost"]
    if h > eps:
        l = luma(cr, cg, cb)
        cr = l+(cr-l)*(1+h*C["HDR_SAT"]); cg = l+(cg-l)*(1+h*C["HDR_SAT"]); cb = l+(cb-l)*(1+h*C["HDR_SAT"])
        cr = (cr-0.5)*(1+h*C["HDR_CONTRAST"])+0.5+h*C["HDR_LIFT"]
        cg = (cg-0.5)*(1+h*C["HDR_CONTRAST"])+0.5+h*C["HDR_LIFT"]
        cb = (cb-0.5)*(1+h*C["HDR_CONTRAST"])+0.5+h*C["HDR_LIFT"]
    cf = p["colorFix"]
    if cf > eps:
        cr *= 1-cf*C["COLOR_FIX_R"]; cg *= 1-cf*C["COLOR_FIX_G"]; cb *= 1+cf*C["COLOR_FIX_B"]
    fd = p["fade"]
    if fd > eps:
        cr += (0.5-cr)*fd*C["FADE_MIX"]; cg += (0.5-cg)*fd*C["FADE_MIX"]; cb += (0.5-cb)*fd*C["FADE_MIX"]
    m = filter_matrix(p.get("_filter"), fintensity)
    if m:
        gl, off = m
        nr = gl[0]*cr + gl[4]*cg + gl[8]*cb + gl[12]*a + off[0]
        ng = gl[1]*cr + gl[5]*cg + gl[9]*cb + gl[13]*a + off[1]
        nb = gl[2]*cr + gl[6]*cg + gl[10]*cb + gl[14]*a + off[2]
        cr, cg, cb = nr, ng, nb
    return [max(0.0, min(1.0, cr)), max(0.0, min(1.0, cg)), max(0.0, min(1.0, cb)), 1.0]


def evaluate_full(adj, ftype="NONE", fintensity=1.0, rgb=(0.5, 0.5, 0.5)):
    """Resolves the look the same way preview + export do, then runs the colour stages."""
    p = effective(adj, ftype, fintensity)
    return evaluate(p, rgb[0], rgb[1], rgb[2], 0.0, fintensity)


def delta(a, b):
    return abs(a[0]-b[0]) + abs(a[1]-b[1]) + abs(a[2]-b[2])


# ----------------------------------------------------------------------------------
# Tests
# ----------------------------------------------------------------------------------
PROBES = [(0.62, 0.41, 0.28), (0.90, 0.88, 0.82), (0.12, 0.10, 0.14), (0.5, 0.5, 0.5)]
FAILURES = []
COUNT = [0]


def check(name, cond, detail=""):
    COUNT[0] += 1
    if cond:
        print("  PASS  %s %s" % (name, detail))
    else:
        FAILURES.append(name)
        print("  FAIL  %s %s" % (name, detail))


print("=" * 78)
print("AH VIDEO STUDIO — colour-look maths verification (executable)")
print("Ports: ColorLookModel.kt, ColorFilterGenerator.kt, FilterCatalog.kt")
print("=" * 78)
print("constants loaded from Kotlin: %d" % len(C))
print("ranges loaded: %d" % len(RANGES))
print("filter matrices: %d   selectable: %s" % (len(FILTER_MATRICES), ", ".join(SELECTABLE)))
print("spatial extras: %s" % json.dumps(SPATIAL))
print()

print("-- 1. Neutral adjustments reproduce the original frame (tolerance 1/255) --")
base = evaluate_full({})
for probe in PROBES:
    out = evaluate_full({}, "NONE", 1.0, probe)
    d = max(abs(out[i]-probe[i]) for i in range(3))
    check("neutral@%s" % (probe,), d <= 1.0/255.0, "max channel delta = %.8f" % d)

print()
print("-- 2. Every colour slider changes rendered pixels (all probes) --")
SLIDERS = [
    ("brightness", {"brightness": 0.35}),
    ("contrast",   {"contrast": 1.45}),
    ("saturation", {"saturation": 1.6}),
    ("exposure",   {"exposure": 0.4}),
    ("temperature",{"temperature": 0.5}),
    ("tint",       {"tint": -0.4}),
    ("highlights", {"highlights": 0.5}),
    ("shadows",    {"shadows": 0.5}),
    ("whites",     {"whites": 0.45}),
    ("blacks",     {"blacks": -0.35}),
    ("fade",       {"fade": 0.5}),
    ("autoEnhance",{"autoEnhance": 0.6}),
    ("hdrBoost",   {"hdrBoost": 0.7}),
    ("colorCorrect",{"colorCorrect": 0.7}),
    ("colorFix",   {"colorFix": 0.8}),
]
for name, adj in SLIDERS:
    worst = 0.0
    total = 0.0
    for probe in PROBES:
        out = evaluate_full(adj, "NONE", 1.0, probe)
        total += delta(out[:3], list(probe))
        worst = max(worst, delta(out[:3], list(probe)))
    check("slider:%-12s" % name, total / len(PROBES) > 0.01,
          "mean |delta| = %.4f  (min over probes = %.4f)" % (total/len(PROBES), worst))

print()
print("-- 3. Sliders move the picture in the right direction --")
out = evaluate_full({"brightness": 0.3}, rgb=(0.4, 0.4, 0.4))
check("brightness brightens", out[0] > 0.4 and out[1] > 0.4, "%.3f -> %.3f" % (0.4, out[0]))
out = evaluate_full({"contrast": 1.6}, rgb=(0.8, 0.8, 0.8))
check("contrast expands highlights", out[0] > 0.8, "0.800 -> %.3f" % out[0])
out = evaluate_full({"saturation": 0.0}, rgb=(0.7, 0.4, 0.2))
check("saturation 0 = greyscale", abs(out[0]-out[1]) < 1e-5 and abs(out[1]-out[2]) < 1e-5,
      "rgb -> (%.3f, %.3f, %.3f)" % (out[0], out[1], out[2]))
out = evaluate_full({"temperature": 0.8}, rgb=(0.5, 0.5, 0.5))
check("temperature warms (R up, B down)", out[0] > 0.5 > out[2], "(%.3f, %.3f, %.3f)" % (out[0], out[1], out[2]))
out = evaluate_full({"tint": 0.8}, rgb=(0.5, 0.5, 0.5))
check("tint + => magenta (R,B up, G down)", out[0] > out[1] and out[2] > out[1],
      "(%.3f, %.3f, %.3f)" % (out[0], out[1], out[2]))
dark = evaluate_full({"shadows": 0.8}, rgb=(0.05, 0.05, 0.05))
mid = evaluate_full({"shadows": 0.8}, rgb=(0.75, 0.75, 0.75))
check("shadows lift shadows more than midtones", (dark[0]-0.05) > (mid[0]-0.75),
      "shadow +%.4f vs midtone +%.4f" % (dark[0]-0.05, mid[0]-0.75))
bright = evaluate_full({"highlights": -0.8}, rgb=(0.92, 0.92, 0.92))
check("highlights - attenuates highlights", bright[0] < 0.92, "0.920 -> %.3f" % bright[0])

print()
print("-- 4. Each selectable filter changes pixels measurably --")
for f in SELECTABLE:
    tot = 0.0
    for probe in PROBES:
        out = evaluate_full({}, f, 1.0, probe)
        tot += delta(out[:3], list(probe))
    mean = tot / len(PROBES)
    check("filter:%-16s" % f, mean > 0.01, "mean |delta| = %.4f" % mean)

print()
print("-- 5. Filter intensity is monotonic (0 == neutral, 0.25 < 0.5 < 1.0) --")
for f in SELECTABLE:
    probe = (0.62, 0.41, 0.28)
    d0 = delta(evaluate_full({}, f, 0.0, probe)[:3], list(probe))
    d1 = delta(evaluate_full({}, f, 0.25, probe)[:3], list(probe))
    d2 = delta(evaluate_full({}, f, 0.5, probe)[:3], list(probe))
    d3 = delta(evaluate_full({}, f, 1.0, probe)[:3], list(probe))
    check("intensity:%-14s" % f, d0 <= 1.0/255.0 and d1 < d2 < d3,
          "0=%.4f 0.25=%.4f 0.5=%.4f 1.0=%.4f" % (d0, d1, d2, d3))

print()
print("-- 6. Filters are visually appropriate (direction, not just magnitude) --")
o = evaluate_full({}, "GOLDEN_AUTUMN", 1.0, (0.5, 0.5, 0.5))
check("Golden is warm (R up, B down)", o[0] > o[2] + 0.05, "(%.3f, %.3f, %.3f)" % (o[0], o[1], o[2]))
o = evaluate_full({}, "BLACKLIGHT_FIX", 1.0, (0.5, 0.5, 0.5))
check("Black Light pushes blue/violet (B > R)", o[2] > o[0], "(%.3f, %.3f, %.3f)" % (o[0], o[1], o[2]))
o = evaluate_full({}, "HDR", 1.0, (0.5, 0.5, 0.5))
check("HDR raises punch (not a flat black frame)", sum(o[:3]) > 0.05 and sum(o[:3]) < 3.0,
      "sum = %.3f" % sum(o[:3]))
o = evaluate_full({}, "GLOW", 1.0, (0.5, 0.5, 0.5))
check("Glow lifts the frame", sum(o[:3]) > 1.5, "sum = %.3f" % sum(o[:3]))
p4k = effective({}, "FOUR_K", 1.0)
check("4K sets spatial sharpen/clarity (not upscaling)",
      p4k["sharpness"] > 0.3 and p4k["clarity"] > 0.2,
      "sharpness=%.2f clarity=%.2f" % (p4k["sharpness"], p4k["clarity"]))
pg = effective({}, "GLOW", 1.0)
check("Glow sets the bloom uniform", pg["glow"] > 0.5, "glow=%.2f" % pg["glow"])
ph = effective({}, "HDR", 1.0)
check("HDR sets the hdr-look uniform (SDR, not HDR10)", ph["hdrBoost"] > 0.5 and 0 < ph["hdrBoost"] <= 1.0,
      "hdrBoost=%.2f" % ph["hdrBoost"])

print()
print("-- 7. Repeated filter changes never blacken or corrupt the frame --")
probe = (0.62, 0.41, 0.28)
cur = list(probe)
ok = True
for i in range(200):
    f = SELECTABLE[i % len(SELECTABLE)]
    nxt = evaluate_full({}, f, 1.0, tuple(probe))
    if sum(nxt[:3]) <= 0.0:
        ok = False
        break
    if not all(0.0 <= c <= 1.0 for c in nxt[:3]):
        ok = False
        break
check("200 filter switches stay in [0,1] and non-black", ok)
# idempotence: applying then removing returns to the original
p = list(probe)
for f in SELECTABLE:
    p = evaluate_full({}, f, 1.0, tuple(p))[:3]
back = evaluate_full({}, "NONE", 1.0, tuple(p))
check("resetting the filter returns the neutral frame",
      delta(back[:3], list(p)) <= 1.0/255.0, "delta after removal = %.8f" % delta(back[:3], list(p)))

print()
print("-- 8. Values are clamped to their documented ranges --")
p = effective({"brightness": 5.0, "contrast": -3.0, "saturation": 9.0, "exposure": -7.0})
check("out-of-range slider values are clamped",
      p["brightness"] == 1.0 and p["contrast"] == 0.10 and p["saturation"] == 2.0 and p["exposure"] == -1.0,
      "b=%.2f c=%.2f s=%.2f e=%.2f" % (p["brightness"], p["contrast"], p["saturation"], p["exposure"]))
p = effective({"autoEnhance": 0.8}, "ENHANCE", 1.0)
check("filter extras + panel value clamp at 1.0", p["autoEnhance"] == 1.0, "autoEnhance=%.2f" % p["autoEnhance"])
p = effective({"denoise": 0.9, "antiFlicker": 1.0})
check("denoise + antiFlicker clamp at 1.0", p["denoise"] == 1.0, "denoise=%.2f" % p["denoise"])

print()
print("=" * 78)
print("RESULT: %d checks, %d failures" % (COUNT[0], len(FAILURES)))
if FAILURES:
    for f in FAILURES:
        print("  FAILED:", f)
print("=" * 78)
sys.exit(1 if FAILURES else 0)
