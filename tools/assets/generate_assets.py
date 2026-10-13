#!/usr/bin/env python3
"""Generate 220 ORIGINAL flat-vector assets (20 x 11 categories) for AH Video Studio.

Every asset is drawn procedurally by this script (no third-party artwork):
  firebase-assets/files/{category}/{assetId}.svg        full asset (512x512, transparent)
  firebase-assets/thumbnails/{category}/{assetId}.png   256px PNG render (via resvg)
  firebase-assets/firestore-seed.json                   categories[] + assets[] for Firestore
Also mirrors the seed to app/src/main/assets/elements/catalog.json (offline fallback).
Run:  python3 tools/assets/generate_assets.py
"""
import json, math, os, re
import resvg_py

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
OUT = os.path.join(ROOT, "firebase-assets")
W = H = 512
PALETTES = [
    ("#00E5FF", "#0091FF"), ("#FF4D6D", "#FF8FA3"), ("#FFD166", "#FF9100"),
    ("#06D6A0", "#118AB2"), ("#9D6BFF", "#7000FF"), ("#FF7B54", "#FFB26B"),
    ("#4CC9F0", "#4361EE"), ("#F72585", "#FF70A6"), ("#80ED99", "#38B000"),
    ("#FFC300", "#FF5733"), ("#48CAE4", "#0077B6"), ("#E0AAFF", "#9D4EDD"),
    ("#90E0EF", "#00B4D8"), ("#FFAFCC", "#FF5D8F"), ("#A7C957", "#6A994E"),
    ("#F4A261", "#E76F51"), ("#2EC4B6", "#011627"), ("#E63946", "#F1FA8C"),
    ("#B388FF", "#7C4DFF"), ("#FFD54F", "#FF8F00"),
]

def pts(cx, cy, r, n, rot=-90, inner=None):
    out = []
    for i in range(n * (2 if inner else 1)):
        rad = r if (inner is None or i % 2 == 0) else inner
        a = math.radians(rot + i * 180 / (n if inner else n))
        if inner is None: a = math.radians(rot + i * 360 / n)
        out.append(f"{cx+rad*math.cos(a):.1f},{cy+rad*math.sin(a):.1f}")
    return " ".join(out)

def svg(body, title):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" '
            f'viewBox="0 0 {W} {H}"><title>{title}</title>{body}</svg>')

def slug(name): return re.sub(r"[^a-z0-9]+", "_", name.lower()).strip("_")

# ---------------------------------------------------------------- drawers
def d_shape(kind, c1, c2, i):
    if kind == "circle": return f'<circle cx="256" cy="256" r="170" fill="{c1}"/>'
    if kind == "ring": return f'<circle cx="256" cy="256" r="160" fill="none" stroke="{c1}" stroke-width="42"/>'
    if kind == "square": return f'<rect x="96" y="96" width="320" height="320" rx="28" fill="{c1}"/>'
    if kind == "rect": return f'<rect x="66" y="146" width="380" height="220" rx="24" fill="{c1}"/>'
    if kind == "triangle": return f'<polygon points="{pts(256,256,185,3)}" fill="{c1}"/>'
    if kind == "diamond": return f'<polygon points="{pts(256,256,185,4)}" fill="{c1}"/>'
    if kind == "pentagon": return f'<polygon points="{pts(256,256,180,5)}" fill="{c1}"/>'
    if kind == "hexagon": return f'<polygon points="{pts(256,256,180,6)}" fill="{c1}"/>'
    if kind == "octagon": return f'<polygon points="{pts(256,256,180,8)}" fill="{c1}"/>'
    if kind == "star": return f'<polygon points="{pts(256,256,190,5,inner=82)}" fill="{c1}" stroke="{c2}" stroke-width="8"/>'
    if kind == "heart": return f'<path d="M256 430 C90 310 70 190 150 120 C205 72 256 110 256 150 C256 110 307 72 362 120 C442 190 422 310 256 430Z" fill="{c1}"/>'
    if kind == "cross": return f'<path d="M216 76h80v140h140v80H296v140h-80V296H76v-80h140Z" fill="{c1}"/>'
    if kind == "arrow": return f'<path d="M60 216h240V136l152 120-152 120v-80H60Z" fill="{c1}"/>'
    if kind == "chevron": return f'<path d="M110 90l180 166-180 166 62 62 242-228L172 28Z" fill="{c1}" transform="translate(-30,28)"/>'
    if kind == "blob": return f'<path d="M256 62c96 4 168 62 182 148 13 82-38 150-104 196-70 49-176 58-234-6C38 334 44 210 104 138 146 87 196 59 256 62Z" fill="{c1}"/>'
    if kind == "half": return f'<path d="M86 256a170 170 0 0 1 340 0Z" fill="{c1}"/>'
    if kind == "drop": return f'<path d="M256 46C256 46 96 250 96 332a160 140 0 0 0 320 0C416 250 256 46 256 46Z" fill="{c1}"/>'
    if kind == "crescent": return f'<path d="M340 60a200 200 0 1 0 112 340A232 232 0 0 1 340 60Z" fill="{c1}"/>'
    if kind == "pill": return f'<rect x="56" y="176" width="400" height="160" rx="80" fill="{c1}"/>'
    return f'<circle cx="256" cy="256" r="150" fill="{c1}" stroke="{c2}" stroke-width="24"/>'

def d_graphics(kind, c1, c2, i):
    if kind == "burst": return f'<polygon points="{pts(256,256,215,12,inner=150)}" fill="{c1}"/><circle cx="256" cy="256" r="105" fill="{c2}"/>'
    if kind == "seal": return f'<polygon points="{pts(256,240,165,16,inner=140)}" fill="{c1}"/><circle cx="256" cy="240" r="104" fill="none" stroke="#fff" stroke-width="10"/><path d="M196 360l-24 92 84-44 84 44-24-92Z" fill="{c2}"/>'
    if kind == "tag": return f'<path d="M92 92h190l138 138-190 190L92 282Z" fill="{c1}"/><circle cx="168" cy="168" r="26" fill="#fff"/>'
    if kind == "ribbon": return f'<path d="M66 176h380v110H66Z" fill="{c1}"/><path d="M66 286l-40 64 82-18M446 286l40 64-82-18" fill="{c2}"/>'
    if kind == "sparkle": return f'<path d="M256 36l42 150 150 70-150 70-42 150-42-150-150-70 150-70Z" fill="{c1}"/><circle cx="392" cy="110" r="24" fill="{c2}"/><circle cx="110" cy="392" r="18" fill="{c2}"/>'
    if kind == "banner": return f'<path d="M96 120c106-44 214-44 320 0v250c-106-44-214-44-320 0Z" fill="{c1}"/><path d="M96 120v250l-34 44V154Z" fill="{c2}"/><path d="M416 120v250l34 44V154Z" fill="{c2}"/>'
    if kind == "confetti":
        s = "".join(f'<rect x="{60+(k*67)%360}" y="{60+(k*97)%340}" width="26" height="12" rx="5" fill="{c1 if k%2 else c2}" transform="rotate({k*37} 256 256)"/>' for k in range(18))
        return s + f'<circle cx="256" cy="256" r="72" fill="{c1}"/>'
    if kind == "swirl": return f'<path d="M256 76a180 180 0 1 1-180 180 140 140 0 1 0 140-140 100 100 0 1 1-100 100" fill="none" stroke="{c1}" stroke-width="34" stroke-linecap="round"/>'
    if kind == "rays": return "".join(f'<polygon points="{pts(256,256,225,12,inner=120)}" fill="{c1}" opacity="0.9"/>') + f'<circle cx="256" cy="256" r="92" fill="{c2}"/>'
    if kind == "quote": return f'<rect x="66" y="96" width="380" height="300" rx="36" fill="{c1}"/><path d="M376 396l10 70-92-70Z" fill="{c1}"/><text x="140" y="290" font-size="150" fill="#fff" font-family="sans-serif" font-weight="bold">&#8220;</text>'
    if kind == "label": return f'<rect x="76" y="156" width="360" height="200" rx="100" fill="{c1}"/><rect x="100" y="180" width="312" height="152" rx="76" fill="none" stroke="#fff" stroke-width="8"/>'
    if kind == "wave": return f'<path d="M0 300 Q128 190 256 300 T512 300 V512 H0Z" fill="{c1}"/><path d="M0 360 Q128 260 256 360 T512 360 V512 H0Z" fill="{c2}" opacity="0.75"/>'
    return d_shape("blob", c1, c2, i)

def d_frame(kind, c1, c2, i):
    wdt = 26 + (i % 4) * 8
    if kind == "circle": return f'<circle cx="256" cy="256" r="205" fill="none" stroke="{c1}" stroke-width="{wdt}"/><circle cx="256" cy="256" r="168" fill="none" stroke="{c2}" stroke-width="6"/>'
    if kind == "rounded": return f'<rect x="56" y="56" width="400" height="400" rx="52" fill="none" stroke="{c1}" stroke-width="{wdt}"/>'
    if kind == "double": return f'<rect x="46" y="46" width="420" height="420" fill="none" stroke="{c1}" stroke-width="14"/><rect x="86" y="86" width="340" height="340" fill="none" stroke="{c2}" stroke-width="22"/>'
    if kind == "polaroid": return f'<path d="M106 46h300v420H106Z" fill="#fff" stroke="{c1}" stroke-width="8"/><rect x="136" y="76" width="240" height="270" fill="{c2}" opacity="0.35"/>'
    if kind == "film": return (f'<rect x="56" y="76" width="400" height="360" fill="#16181D" stroke="{c1}" stroke-width="8"/>'
        + "".join(f'<rect x="{76+k*48}" y="94" width="28" height="20" rx="4" fill="#fff"/>' for k in range(8))
        + "".join(f'<rect x="{76+k*48}" y="398" width="28" height="20" rx="4" fill="#fff"/>' for k in range(8))
        + f'<rect x="96" y="140" width="320" height="226" fill="{c2}" opacity="0.4"/>')
    if kind == "ornate": return (f'<rect x="66" y="66" width="380" height="380" fill="none" stroke="{c1}" stroke-width="20"/>'
        + "".join(f'<circle cx="{x}" cy="{y}" r="24" fill="{c2}"/>' for x, y in [(66,66),(446,66),(66,446),(446,446)]))
    if kind == "corner":
        c = "".join(f'<path d="M{x} {y}h{70*sx}v26H{x+44*sx}v44H{x}Z" fill="{c1}"/>' for x,y,sx in [(56,56,1),(456,56,-1),(56,456,1),(456,456,-1)])
        return c.replace('v44H456Z','v-44H456Z').replace('v44H56Z','v-44H56Z') if False else (f'<path d="M56 176V56h120v34H90v86Z" fill="{c1}"/><path d="M456 176V56H336v34h86v86Z" fill="{c1}"/><path d="M56 336v120h120v-34H90v-86Z" fill="{c1}"/><path d="M456 336v120H336v-34h86v-86Z" fill="{c1}"/>')
    if kind == "wide": return f'<rect x="36" y="116" width="440" height="280" rx="20" fill="none" stroke="{c1}" stroke-width="{wdt}"/>'
    if kind == "portrait": return f'<rect x="116" y="36" width="280" height="440" rx="20" fill="none" stroke="{c1}" stroke-width="{wdt}"/>'
    return f'<rect x="56" y="56" width="400" height="400" fill="none" stroke="{c1}" stroke-width="{wdt}" stroke-dasharray="{18+i*3} 12"/>'

def d_table(kind, c1, c2, i):
    rows, cols = 2 + i % 4, 2 + (i // 4) % 4
    x0, y0, tw, th = 56, 86, 400, 340
    cw, ch = tw / cols, th / rows
    s = f'<rect x="{x0}" y="{y0}" width="{tw}" height="{ch}" fill="{c1}"/>'
    s += f'<rect x="{x0}" y="{y0}" width="{tw}" height="{th}" fill="none" stroke="{c2}" stroke-width="6"/>'
    for r in range(rows):
        for c in range(cols):
            s += f'<rect x="{x0+c*cw:.0f}" y="{y0+r*ch:.0f}" width="{cw:.0f}" height="{ch:.0f}" fill="{"#fff" if r else c1}" fill-opacity="{0.92 if r else 1}" stroke="{c2}" stroke-width="3"/>'
            if r: s += f'<rect x="{x0+c*cw+16:.0f}" y="{y0+r*ch+ch/2-7:.0f}" width="{cw-32:.0f}" height="14" rx="7" fill="{c2}" opacity="0.45"/>'
            else: s += f'<rect x="{x0+c*cw+16:.0f}" y="{y0+ch/2-8:.0f}" width="{cw-32:.0f}" height="16" rx="8" fill="#fff" opacity="0.85"/>'
    return s

def d_3d(kind, c1, c2, i):
    if kind == "cube": return (f'<polygon points="256,66 426,160 256,254 86,160" fill="{c1}"/>'
        f'<polygon points="86,160 256,254 256,446 86,352" fill="{c2}"/><polygon points="426,160 256,254 256,446 426,352" fill="{c1}" opacity="0.62"/>')
    if kind == "sphere": return f'<defs><radialGradient id="g{i}" cx="35%" cy="30%"><stop offset="0%" stop-color="#fff" stop-opacity=".75"/><stop offset="45%" stop-color="{c1}"/><stop offset="100%" stop-color="{c2}"/></radialGradient></defs><circle cx="256" cy="256" r="180" fill="url(#g{i})"/>'
    if kind == "pyramid": return f'<polygon points="256,66 446,406 256,406" fill="{c1}"/><polygon points="256,66 66,406 256,406" fill="{c2}"/>'
    if kind == "cylinder": return f'<ellipse cx="256" cy="116" rx="150" ry="48" fill="{c1}"/><path d="M106 116v280a150 48 0 0 0 300 0V116Z" fill="{c2}"/><ellipse cx="256" cy="116" rx="150" ry="48" fill="#fff" opacity="0.25"/>'
    if kind == "cone": return f'<path d="M256 56 416 396a160 52 0 0 1-320 0Z" fill="{c1}"/><ellipse cx="256" cy="396" rx="160" ry="52" fill="{c2}"/>'
    if kind == "torus": return f'<circle cx="256" cy="256" r="165" fill="none" stroke="{c1}" stroke-width="86"/><ellipse cx="200" cy="180" rx="90" ry="40" fill="#fff" opacity="0.28" transform="rotate(-24 200 180)"/>'
    if kind == "prism": return f'<polygon points="256,86 426,386 86,386" fill="{c1}" opacity="0.85"/><polygon points="256,86 426,386 330,386 256,190" fill="{c2}" opacity="0.7"/>'
    return d_3d("cube", c2, c1, i)

ICON_PATHS = {
 "heart": '<path d="M256 430 C90 310 70 190 150 120 C205 72 256 110 256 150 C256 110 307 72 362 120 C442 190 422 310 256 430Z"/>',
 "star": f'<polygon points="{pts(256,256,195,5,inner=84)}"/>',
 "play": '<circle cx="256" cy="256" r="190"/><path d="M210 166l170 90-170 90Z" fill="#fff"/>',
 "mic": '<rect x="216" y="56" width="80" height="220" rx="40"/><path d="M146 220a110 110 0 0 0 220 0h-44a66 66 0 0 1-132 0Z"/><rect x="242" y="330" width="28" height="90"/><rect x="166" y="420" width="180" height="26" rx="13"/>',
 "camera": '<rect x="56" y="146" width="400" height="270" rx="36"/><circle cx="256" cy="281" r="82" fill="#fff"/><path d="M176 146l36-52h88l36 52Z"/>',
 "bell": '<path d="M256 56a140 140 0 0 1 140 140c0 90 30 130 52 160H64c22-30 52-70 52-160a140 140 0 0 1 140-140Z"/><circle cx="256" cy="404" r="36"/>',
 "home": '<path d="M56 266L256 76l200 190h-58v170H302V316h-92v120H114V266Z"/>',
 "search": '<circle cx="226" cy="226" r="140" fill="none" stroke-width="52"/><path d="M330 330l110 110" stroke-width="56" stroke-linecap="round"/>',
 "gear": f'<polygon points="{pts(256,256,200,8,inner=150)}"/><circle cx="256" cy="256" r="76" fill="#fff"/>',
 "download": '<path d="M236 56h40v220l74-74 40 40-134 134L122 242l40-40 74 74Z"/><rect x="76" y="396" width="360" height="40" rx="20"/>',
 "share": '<circle cx="120" cy="256" r="52"/><circle cx="382" cy="106" r="52"/><circle cx="382" cy="406" r="52"/><path d="M168 232l166-102M168 280l166 102" stroke-width="34"/>',
 "music": '<path d="M196 400V140l220-44v246"/><circle cx="146" cy="402" r="52"/><circle cx="366" cy="344" r="52"/>',
 "pin": '<path d="M256 456S106 310 106 186a150 150 0 0 1 300 0c0 124-150 270-150 270Z"/><circle cx="256" cy="186" r="58" fill="#fff"/>',
 "calendar": '<rect x="66" y="96" width="380" height="350" rx="30"/><rect x="66" y="96" width="380" height="86" fill="#fff" opacity=".55"/><rect x="140" y="56" width="28" height="80" rx="14" fill="#fff"/><rect x="344" y="56" width="28" height="80" rx="14" fill="#fff"/>',
 "clock": '<circle cx="256" cy="256" r="190"/><path d="M256 150v110l82 52" stroke="#fff" stroke-width="30" fill="none" stroke-linecap="round"/>',
 "chat": '<path d="M66 66h380v280H236l-96 92v-92H66Z"/>',
 "check": '<path d="M86 266l118 118L426 142l-44-50L204 286 130 216Z"/>',
 "lock": '<rect x="106" y="216" width="300" height="230" rx="30"/><path d="M166 216v-50a90 90 0 0 1 180 0v50h-46v-50a44 44 0 0 0-88 0v50Z"/>',
 "wifi": '<path d="M56 176a300 300 0 0 1 400 0l-42 52a226 226 0 0 0-316 0Z"/><path d="M126 276a190 190 0 0 1 260 0l-42 52a116 116 0 0 0-176 0Z"/><circle cx="256" cy="396" r="34"/>',
 "bolt": '<path d="M286 36L96 296h120l-26 180 216-286H276Z"/>',
}
def d_icon(kind, c1, c2, i):
    body = ICON_PATHS[kind]
    # paths using currentColor-style: wrap with fill/stroke = c1
    return f'<g fill="{c1}" stroke="{c1}">{body}</g>'

def face(c1, c2, ears, extra=""):
    e = {"round": f'<circle cx="116" cy="170" r="52" fill="{c1}"/><circle cx="396" cy="170" r="52" fill="{c1}"/>',
         "pointy": f'<path d="M110 190L86 66l110 62ZM402 190l24-124-110 62Z" fill="{c1}"/>',
         "long": f'<ellipse cx="120" cy="150" rx="34" ry="105" fill="{c1}"/><ellipse cx="392" cy="150" rx="34" ry="105" fill="{c1}"/>',
         "horn": f'<path d="M150 110C120 60 150 30 190 22c-8 34 2 62 22 84ZM362 110c30-50 0-80-40-88 8 34-2 62-22 84Z" fill="{c2}"/>',
         "none": ""}[ears]
    return (e + f'<circle cx="256" cy="256" r="168" fill="{c1}"/>'
        f'<ellipse cx="256" cy="316" rx="92" ry="70" fill="#fff" opacity="0.75"/>'
        f'<circle cx="196" cy="236" r="17" fill="#20242B"/><circle cx="316" cy="236" r="17" fill="#20242B"/>'
        f'<ellipse cx="256" cy="302" rx="24" ry="16" fill="#20242B"/>'
        f'<path d="M226 342q30 24 60 0" stroke="#20242B" stroke-width="9" fill="none" stroke-linecap="round"/>' + extra)

def d_animal(name, c1, c2, i):
    n = name.lower()
    if "panda" in n: return face("#F5F5F5", c1, "round", f'<circle cx="116" cy="170" r="52" fill="#20242B"/><circle cx="396" cy="170" r="52" fill="#20242B"/><ellipse cx="196" cy="236" rx="30" ry="38" fill="#20242B"/><ellipse cx="316" cy="236" rx="30" ry="38" fill="#20242B"/><circle cx="196" cy="230" r="12" fill="#fff"/><circle cx="316" cy="230" r="12" fill="#fff"/>')
    if "tiger" in n or "lion" in n: return face(c1, c2, "round", "".join(f'<path d="M{170+k*58} 96v54" stroke="{c2}" stroke-width="16" stroke-linecap="round"/>' for k in range(3)))
    if "rabbit" in n or "bunny" in n: return face(c1, c2, "long")
    if "fox" in n or "cat" in n or "wolf" in n: return face(c1, c2, "pointy")
    if "elephant" in n: return face(c1, c2, "round", f'<path d="M256 300v96a34 34 0 0 1-68 0" stroke="{c1}" stroke-width="52" fill="none" stroke-linecap="round"/>')
    if "unicorn" in n or "rhino" in n: return face(c1, c2, "round", f'<polygon points="256,6 282,110 230,110" fill="{c2}"/>')
    if "giraffe" in n: return face(c1, c2, "horn", "".join(f'<circle cx="{150+k*70}" cy="{180+(k%2)*110}" r="20" fill="{c2}" opacity=".6"/>' for k in range(4)))
    if "zebra" in n: return face("#F5F5F5", c1, "round", "".join(f'<path d="M{140+k*46} 110v70" stroke="#20242B" stroke-width="13"/>' for k in range(6)))
    if "pig" in n: return face(c1, c2, "pointy", f'<ellipse cx="256" cy="306" rx="52" ry="38" fill="{c2}"/><circle cx="238" cy="306" r="8" fill="#20242B"/><circle cx="274" cy="306" r="8" fill="#20242B"/>')
    if "frog" in n: return (f'<circle cx="166" cy="120" r="56" fill="{c1}"/><circle cx="346" cy="120" r="56" fill="{c1}"/>'
        f'<circle cx="166" cy="120" r="26" fill="#fff"/><circle cx="346" cy="120" r="26" fill="#fff"/>'
        f'<circle cx="166" cy="124" r="13" fill="#20242B"/><circle cx="346" cy="124" r="13" fill="#20242B"/>'
        f'<ellipse cx="256" cy="300" rx="180" ry="140" fill="{c1}"/><path d="M166 316q90 70 180 0" stroke="#20242B" stroke-width="10" fill="none" stroke-linecap="round"/>')
    if "owl" in n: return d_bird("crest", c1, c2, i)
    return face(c1, c2, "round")

def d_bird(kind, c1, c2, i):
    return (f'<ellipse cx="246" cy="286" rx="150" ry="128" fill="{c1}"/>'
        f'<circle cx="330" cy="166" r="82" fill="{c1}"/>'
        f'<path d="M402 150l82 26-82 34Z" fill="#FFB703"/>'
        f'<path d="M150 250c-58-58-44-132 6-176 8 62 34 104 72 140Z" fill="{c2}"/>'
        f'<path d="M196 330c54 44 128 40 176-8-58 4-112-8-156-40Z" fill="{c2}"/>'
        f'<path d="M118 330l-76 62 96 6M128 366l-62 56 84-4" stroke="{c2}" stroke-width="20" stroke-linecap="round" fill="none"/>'
        f'<circle cx="352" cy="152" r="13" fill="#20242B"/>'
        + (f'<path d="M292 92c6-40 34-62 66-66-4 30-18 54-40 70Z" fill="{c2}"/>' if kind == "crest" else "")
        + f'<path d="M216 414v34M286 414v34" stroke="#FFB703" stroke-width="12" stroke-linecap="round"/>')

def d_character(kind, c1, c2, i):
    skins = ["#FFDBB4", "#F1C27D", "#E0AC69", "#C68642", "#8D5524"]
    skin = skins[i % len(skins)]
    hair = {"cap": f'<path d="M146 196a110 104 0 0 1 220 0Z" fill="{c2}"/><rect x="132" y="188" width="248" height="22" rx="11" fill="{c2}"/>',
            "bun": f'<circle cx="256" cy="72" r="38" fill="{c2}"/><path d="M146 200a110 104 0 0 1 220 0l-14-62a124 110 0 0 0-192 0Z" fill="{c2}"/>',
            "spiky": f'<path d="M148 202l14-84 42 44 24-70 34 62 34-58 26 66 40-40 10 80a118 100 0 0 0-224 0Z" fill="{c2}"/>',
            "curly": "".join(f'<circle cx="{160+k*32}" cy="118" r="30" fill="{c2}"/>' for k in range(7)),
            "long": f'<path d="M138 330V190a118 112 0 0 1 236 0v140l-52-18V196H190v116Z" fill="{c2}"/>',
            "none": f'<path d="M148 196a108 100 0 0 1 216 0Z" fill="{c2}"/>'}[kind]
    return (hair + f'<circle cx="256" cy="216" r="106" fill="{skin}"/>'
        f'<circle cx="216" cy="208" r="10" fill="#20242B"/><circle cx="296" cy="208" r="10" fill="#20242B"/>'
        f'<path d="M226 258q30 22 60 0" stroke="#20242B" stroke-width="8" fill="none" stroke-linecap="round"/>'
        f'<path d="M126 470c10-84 62-128 130-128s120 44 130 128Z" fill="{c1}"/>'
        f'<path d="M226 342h60l-30 44Z" fill="{skin}"/>')

def d_vehicle(kind, c1, c2, i):
    wheel = lambda x: f'<circle cx="{x}" cy="378" r="42" fill="#20242B"/><circle cx="{x}" cy="378" r="19" fill="#CFD8DC"/>'
    if kind == "bike": return (f'<circle cx="130" cy="356" r="66" fill="none" stroke="#20242B" stroke-width="16"/><circle cx="382" cy="356" r="66" fill="none" stroke="#20242B" stroke-width="16"/>'
        f'<path d="M130 356l92-128h110l50 128M222 228l-40 128M256 160h76M196 128h52l-26 100" stroke="{c1}" stroke-width="20" fill="none" stroke-linecap="round"/>')
    if kind == "bus":
        wins = "".join(f'<rect x="{92+k*66}" y="146" width="50" height="62" rx="8" fill="#DFF6FF"/>' for k in range(5))
        return f'<rect x="66" y="116" width="380" height="238" rx="30" fill="{c1}"/>{wins}<rect x="66" y="238" width="380" height="26" fill="{c2}"/>{wheel(140)}{wheel(372)}'
    if kind == "truck": return f'<path d="M56 156h250v198H56Z" fill="{c1}"/><path d="M306 226h78l62 62v66H306Z" fill="{c2}"/><rect x="322" y="240" width="52" height="42" fill="#DFF6FF"/>{wheel(130)}{wheel(250)}{wheel(386)}'
    if kind == "plane": return f'<path d="M36 300L476 96l-92 322-86-64-112 62 28-104Z" fill="{c1}"/><path d="M298 354l86 64-24-104Z" fill="{c2}"/>'
    if kind == "boat": return f'<path d="M96 330h320l-52 92H148Z" fill="{c1}"/><rect x="226" y="196" width="90" height="134" fill="{c2}"/><path d="M256 70v126M256 82l84 114h-84" fill="#fff" stroke="{c2}" stroke-width="10"/>'
    if kind == "rocket": return f'<path d="M256 36c66 66 84 160 62 254H194c-22-94 0-188 62-254Z" fill="{c1}"/><circle cx="256" cy="176" r="38" fill="#DFF6FF"/><path d="M194 290l-52 92 62-22M318 290l52 92-62-22" fill="{c2}"/><path d="M226 362h60l-30 84Z" fill="#FFB703"/>'
    if kind == "helicopter": return f'<rect x="156" y="60" width="6" height="34" fill="#20242B"/><rect x="56" y="50" width="400" height="16" rx="8" fill="#20242B"/><ellipse cx="230" cy="216" rx="120" ry="82" fill="{c1}"/><path d="M340 250l116 44M456 294v66" stroke="{c2}" stroke-width="18"/><rect x="166" y="164" width="86" height="58" rx="14" fill="#DFF6FF"/><path d="M140 330h190" stroke="#20242B" stroke-width="14" stroke-linecap="round"/>'
    if kind == "train": return f'<rect x="106" y="86" width="300" height="268" rx="34" fill="{c1}"/><rect x="140" y="120" width="232" height="92" rx="16" fill="#DFF6FF"/><circle cx="176" cy="292" r="24" fill="#FFD166"/><circle cx="336" cy="292" r="24" fill="#FFD166"/><path d="M166 354l-52 92M346 354l52 92M130 446h252" stroke="{c2}" stroke-width="16" stroke-linecap="round"/>'
    if kind == "tractor": return f'<path d="M96 286h210V166h84v120h54v68H96Z" fill="{c1}"/><rect x="322" y="182" width="52" height="66" fill="#DFF6FF"/><circle cx="150" cy="368" r="72" fill="#20242B"/><circle cx="150" cy="368" r="32" fill="#CFD8DC"/><circle cx="380" cy="392" r="46" fill="#20242B"/><circle cx="380" cy="392" r="20" fill="#CFD8DC"/>'
    # car family (sedan / hatch / jeep / van / sport via roof param)
    roof = {"sport": "M120 306l52-66h150l74 66", "jeep": "M126 306V196h220v110", "van": "M110 306V186h260l44 120"}.get(kind, "M116 306l58-84h150l78 84")
    return (f'<path d="{roof}Z" fill="{c2}"/><path d="M56 306h400a24 24 0 0 1 24 24v44H32v-44a24 24 0 0 1 24-24Z" fill="{c1}"/>'
        f'<rect x="196" y="238" width="52" height="52" fill="#DFF6FF"/><rect x="262" y="238" width="52" height="52" fill="#DFF6FF"/>{wheel(138)}{wheel(374)}')

def d_chart(kind, c1, c2, i):
    axis = f'<path d="M76 66v350h370" stroke="#90A4AE" stroke-width="7" fill="none"/>'
    if kind == "bar":
        vals = [120 + (i * 37 + k * 61) % 210 for k in range(5)]
        return axis + "".join(f'<rect x="{104+k*68}" y="{416-v}" width="44" height="{v}" rx="8" fill="{c1 if k%2==0 else c2}"/>' for k, v in enumerate(vals))
    if kind == "line":
        p = " ".join(f"{100+k*78},{330-((i*29+k*67)%230)}" for k in range(5))
        dots = "".join(f'<circle cx="{100+k*78}" cy="{330-((i*29+k*67)%230)}" r="13" fill="{c2}"/>' for k in range(5))
        return axis + f'<polyline points="{p}" fill="none" stroke="{c1}" stroke-width="12" stroke-linecap="round"/>{dots}'
    if kind == "area":
        p = " ".join(f"{100+k*78},{330-((i*29+k*67)%230)}" for k in range(5))
        return axis + f'<polygon points="100,416 {p} 412,416" fill="{c1}" opacity="0.45"/><polyline points="{p}" fill="none" stroke="{c1}" stroke-width="10"/>'
    if kind == "pie":
        return (f'<circle cx="256" cy="250" r="160" fill="{c1}"/><path d="M256 250 L256 90 A160 160 0 0 1 402 330Z" fill="{c2}"/>'
            f'<path d="M256 250 L402 330 A160 160 0 0 1 130 350Z" fill="{c1}" opacity="0.55"/>')
    if kind == "donut":
        return (f'<circle cx="256" cy="250" r="150" fill="none" stroke="{c2}" stroke-width="62"/>'
            f'<path d="M256 100a150 150 0 0 1 130 210" fill="none" stroke="{c1}" stroke-width="62"/>'
            f'<text x="256" y="272" font-size="62" text-anchor="middle" fill="{c2}" font-family="sans-serif" font-weight="bold">{45+i%50}%</text>')
    if kind == "progress":
        return "".join(f'<rect x="76" y="{110+k*92}" width="360" height="42" rx="21" fill="{c2}" opacity="0.3"/><rect x="76" y="{110+k*92}" width="{140+((i*53+k*71)%210)}" height="42" rx="21" fill="{c1}"/>' for k in range(3))
    if kind == "scatter":
        return axis + "".join(f'<circle cx="{110+(k*83+i*17)%320}" cy="{120+(k*97)%270}" r="{13+k%3*5}" fill="{c1 if k%2 else c2}" opacity="0.85"/>' for k in range(9))
    return d_chart("bar", c1, c2, i)

DRAWERS = {"shapes": d_shape, "graphics": d_graphics, "frames": d_frame, "tables": d_table,
           "3d": d_3d, "icons": d_icon, "animals": d_animal, "birds": d_bird,
           "characters": d_character, "vehicles": d_vehicle, "charts": d_chart}

CATS = [
    ("shapes", "Shapes", "Circles, squares, lines, arrows & geometry", [
        ("Solid Circle","circle"),("Circle Ring","ring"),("Rounded Square","square"),("Wide Rectangle","rect"),
        ("Triangle","triangle"),("Diamond","diamond"),("Pentagon","pentagon"),("Hexagon","hexagon"),
        ("Octagon","octagon"),("Star","star"),("Heart","heart"),("Plus Cross","cross"),("Arrow Right","arrow"),
        ("Chevron","chevron"),("Organic Blob","blob"),("Half Circle","half"),("Water Drop","drop"),
        ("Crescent Moon","crescent"),("Pill Label","pill"),("Badge Circle","seal")]),
    ("graphics", "Graphics", "Illustrations, decorative graphics & stickers", [
        ("Starburst Badge","burst"),("Award Seal","seal"),("Price Tag","tag"),("Ribbon Banner","ribbon"),
        ("Sparkle Burst","sparkle"),("Wave Banner","banner"),("Confetti Pop","confetti"),("Swirl Spiral","swirl"),
        ("Sun Rays","rays"),("Quote Bubble","quote"),("Pill Label Art","label"),("Ocean Wave","wave"),
        ("Coral Burst","burst"),("Mint Seal","seal"),("Sunset Tag","tag"),("Violet Ribbon","ribbon"),
        ("Golden Sparkle","sparkle"),("Pink Banner","banner"),("Teal Confetti","confetti"),("Neon Swirl","swirl")]),
    ("frames", "Frames", "Image, video & device frames", [
        ("Circle Frame","circle"),("Rounded Frame","rounded"),("Double Border Frame","double"),("Polaroid Frame","polaroid"),
        ("Film Strip Frame","film"),("Ornate Corner Frame","ornate"),("Corner Marks Frame","corner"),("Cinematic Wide Frame","wide"),
        ("Portrait Frame","portrait"),("Dashed Frame","dashed"),("Thin Ring Frame","circle"),("Soft Rounded Frame","rounded"),
        ("Classic Double Frame","double"),("Instant Photo Frame","polaroid"),("Cinema Film Frame","film"),("Royal Ornate Frame","ornate"),
        ("Focus Corners Frame","corner"),("Widescreen Frame","wide"),("Story Portrait Frame","portrait"),("Stitch Dashed Frame","dashed")]),
    ("tables", "Tables", "Grids, rows/cols & comparison tables",
        [(f"Data Table {k+1}", "grid") for k in range(20)]),
    ("3d", "3D", "3D objects, shapes & volume graphics", [
        ("Isometric Cube","cube"),("Glossy Sphere","sphere"),("Pyramid","pyramid"),("Cylinder","cylinder"),
        ("Cone","cone"),("Torus Ring","torus"),("Glass Prism","prism"),("Sunset Cube","cube"),
        ("Ocean Sphere","sphere"),("Sand Pyramid","pyramid"),("Steel Cylinder","cylinder"),("Coral Cone","cone"),
        ("Violet Torus","torus"),("Crystal Prism","prism"),("Mint Cube","cube"),("Rose Sphere","sphere"),
        ("Amber Pyramid","pyramid"),("Teal Cylinder","cylinder"),("Indigo Cone","cone"),("Lime Torus","torus")]),
    ("icons", "Icons", "Social, business & UI icons", [
        ("Heart Icon","heart"),("Star Icon","star"),("Play Icon","play"),("Microphone Icon","mic"),
        ("Camera Icon","camera"),("Bell Icon","bell"),("Home Icon","home"),("Search Icon","search"),
        ("Settings Gear","gear"),("Download Icon","download"),("Share Icon","share"),("Music Icon","music"),
        ("Location Pin","pin"),("Calendar Icon","calendar"),("Clock Icon","clock"),("Chat Bubble","chat"),
        ("Check Mark","check"),("Lock Icon","lock"),("WiFi Icon","wifi"),("Bolt Icon","bolt")]),
    ("animals", "Animals", "Animal illustrations, shapes & graphics", [
        ("Happy Cat","Cat"),("Playful Dog","Dog"),("Giant Panda","Panda"),("Brave Tiger","Tiger"),
        ("Cute Bunny","Bunny"),("Clever Fox","Fox"),("Gentle Elephant","Elephant"),("King Lion","Lion"),
        ("Little Pig","Pig"),("Happy Frog","Frog"),("Tall Giraffe","Giraffe"),("Striped Zebra","Zebra"),
        ("Magic Unicorn","Unicorn"),("Wise Owl","Owl"),("Sleepy Koala","Koala"),("Brown Bear","Bear"),
        ("Grey Wolf","Wolf"),("White Rabbit","Rabbit"),("Rhino","Rhino"),("Kitten","Cat")]),
    ("birds", "Birds", "Flying birds, bird types & illustrations",
        [(n, "crest" if k % 3 == 0 else "plain") for k, n in enumerate([
        "Bluebird","Parrot","Robin","Flamingo Bird","Owl Bird","Penguin Bird","Eagle Bird","Hummingbird",
        "Cardinal","Peacock Bird","Toucan Bird","Sparrow","Dove","Woodpecker","Kingfisher","Finch",
        "Kite Bird","Heron","Macaw","Chick"])]),
    ("characters", "Characters", "Men, women, kids & elder avatars", [
        ("Man With Cap","cap"),("Woman With Bun","bun"),("Spiky Hair Guy","spiky"),("Curly Hair Woman","curly"),
        ("Long Hair Woman","long"),("Smiling Boy","spiky"),("Girl With Bun","bun"),("Cap Boy","cap"),
        ("Curly Man","curly"),("Long Hair Girl","long"),("Happy Man","none"),("Happy Woman","bun"),
        ("Kid With Cap","cap"),("Teen Spiky","spiky"),("Woman Curly","curly"),("Girl Long Hair","long"),
        ("Man Bun","bun"),("Boy Curly","curly"),("Woman Cap","cap"),("Elder Smile","none")]),
    ("vehicles", "Vehicles", "Cars, bikes, trucks, jeeps & tractors", [
        ("Sedan Car","car"),("Sports Car","sport"),("Jeep","jeep"),("Van","van"),("City Bus","bus"),
        ("Cargo Truck","truck"),("Motorbike","bike"),("Bicycle","bike"),("Airplane","plane"),("Sail Boat","boat"),
        ("Rocket","rocket"),("Helicopter","helicopter"),("Train","train"),("Tractor","tractor"),
        ("Hatchback Car","car"),("Roadster","sport"),("Offroad Jeep","jeep"),("Mini Van","van"),
        ("Tour Bus","bus"),("Pickup Truck","truck")]),
    ("charts", "Charts", "Bar, line, pie, graphs & data charts", [
        ("Bar Chart","bar"),("Line Chart","line"),("Area Chart","area"),("Pie Chart","pie"),
        ("Donut Chart","donut"),("Progress Bars","progress"),("Scatter Plot","scatter"),("Growth Bars","bar"),
        ("Trend Line","line"),("Revenue Area","area"),("Market Pie","pie"),("Goal Donut","donut"),
        ("Skill Progress","progress"),("Data Scatter","scatter"),("Sales Bars","bar"),("Pulse Line","line"),
        ("Traffic Area","area"),("Share Pie","pie"),("Score Donut","donut"),("Stats Progress","progress")]),
]

def main():
    categories, assets = [], []
    for order, (cid, cname, cdesc, specs) in enumerate(CATS):
        categories.append({"id": cid, "name": cname, "description": cdesc, "order": order,
                           "assetCount": len(specs), "status": "published"})
        for i, (name, kind) in enumerate(specs):
            c1, c2 = PALETTES[(i + order * 3) % len(PALETTES)]
            aid = f"{cid}_{slug(name)}"
            if assets and any(a["id"] == aid for a in assets): aid = f"{aid}_{i+1}"
            body = DRAWERS[cid](kind, c1, c2, i)
            fdir = os.path.join(OUT, "files", cid); tdir = os.path.join(OUT, "thumbnails", cid)
            os.makedirs(fdir, exist_ok=True); os.makedirs(tdir, exist_ok=True)
            svg_text = svg(body, name)
            with open(os.path.join(fdir, aid + ".svg"), "w") as f: f.write(svg_text)
            png = resvg_py.svg_to_bytes(svg_string=svg_text, width=256, height=256)
            with open(os.path.join(tdir, aid + ".png"), "wb") as f: f.write(bytes(png))
            props = {"movable": True, "resizable": True, "rotatable": True, "opacity": 1.0,
                     "primaryColor": c1, "secondaryColor": c2, "kind": kind}
            if cid == "tables":
                props.update({"editable": True, "modelType": "table",
                              "rows": 2 + i % 4, "cols": 2 + (i // 4) % 4,
                              "headerColor": c1, "cellColor": "#FFFFFF"})
            if cid == "charts":
                props.update({"editable": True, "modelType": "chart", "chartType": kind,
                              "labels": ["A", "B", "C", "D", "E"],
                              "values": [40 + (i * 7 + k * 13) % 55 for k in range(5)],
                              "colors": [c1, c2, "#FFD166", "#06D6A0", "#9D6BFF"]})
            if cid == "3d":
                props.update({"renderMode": "svg_render", "note": "Isometric SVG render overlay; not a GLB model"})
            assets.append({
                "id": aid, "name": name, "categoryId": cid, "format": "svg",
                "storagePath": f"elements/files/{cid}/{aid}.svg",
                "thumbnailPath": f"elements/thumbnails/{cid}/{aid}.png",
                "width": W, "height": H, "defaultProperties": props, "version": 1,
                "status": "published", "tags": [cid, kind.lower(), name.lower()],
                "sortOrder": i, "createdAt": "__SERVER_TIMESTAMP__", "updatedAt": "__SERVER_TIMESTAMP__"})
    seed = {"categories": categories, "assets": assets}
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "firestore-seed.json"), "w") as f: json.dump(seed, f, indent=1)
    app_catalog = os.path.join(ROOT, "app/src/main/assets/elements")
    os.makedirs(app_catalog, exist_ok=True)
    with open(os.path.join(app_catalog, "catalog.json"), "w") as f: json.dump(seed, f, indent=1)
    print(f"categories={len(categories)} assets={len(assets)}")
    from collections import Counter
    print(Counter(a["categoryId"] for a in assets))

if __name__ == "__main__":
    main()
