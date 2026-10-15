#!/usr/bin/env python3
"""Rendering-contract drift guard.

The Kotlin files this checks (`VideoPlaybackEngine`, `AsyncFramePipelineEngine`,
`PreviewCompositor`, `GpuCompositionRenderer`) import `android.*` / `androidx.media3.*`, so they can
only be compiled by Gradle + the Android SDK. Where that toolchain is unavailable this script still
*executes* a check on them: it parses the checked-in sources and asserts the two contracts that were
found broken, so a future edit that reintroduces either one fails here.

Contract A - one graphChanged for both stages of a preview look update.
  `applyActiveLookToPlayers` / `applyOverlayLooks` must decide "did the graph change?" once
  (`PreviewGraphDecision.graphChanged[OrForced]`) and feed that same value to the install decision
  (`PreviewGraphDecision.outcomeFor`). The bug was a second, narrower recalculation at the install
  site (`graphChanged = lastMedia3GraphSignature != graphSig`) which dropped the
  "no grading node is resident" term, so the recovery path could never install.

Contract B - the renderer's look arguments are project-level fallbacks.
  Both `CompositionRenderGraph.present` call sites (preview + export) must pass
  `CompositionLookResolver.timelineFallback(...)`, never a clip-pre-resolved look, because
  `GpuCompositionRenderer` resolves every layer against those arguments itself. The bug was the
  export passing `frame.activeClip?.adjustments ?: timeline.adjustments`, which made ungraded
  overlays and transition sides inherit the MAIN clip's grade in the export only.

Run: python3 toolchain/verify/verify_render_contract.py
"""

from __future__ import annotations

import os
import re
import sys

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
MAIN = os.path.join(REPO, "app", "src", "main", "java")

PLAYBACK = os.path.join(MAIN, "com/example/engine/playback/VideoPlaybackEngine.kt")
EXPORT = os.path.join(MAIN, "com/example/engine/export/AsyncFramePipelineEngine.kt")
PREVIEW = os.path.join(MAIN, "com/example/engine/preview/PreviewCompositor.kt")
RENDERER = os.path.join(MAIN, "com/example/engine/composition/gpu/GpuCompositionRenderer.kt")

checks = 0
failures: list[str] = []


def read(path: str) -> str:
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def check(ok: bool, label: str, detail: str = "") -> None:
    global checks
    checks += 1
    if ok:
        print(f"  PASS  {label}")
    else:
        print(f"  FAIL  {label}" + (f"  [{detail}]" if detail else ""))
        failures.append(label)


def strip_comments(src: str) -> str:
    """Drop `//` line comments so documentation quoting old code is not mistaken for code."""
    return "\n".join(re.sub(r"//.*$", "", line) for line in src.splitlines())


def slice_fn(src: str, name: str) -> str:
    """Body of `fun <name>(...)` up to the next top-level `  fun ` / `  private fun ` declaration."""
    start = src.index(f"fun {name}(")
    rest = src[start:]
    match = re.search(r"\n  (?:private |internal |public )?fun ", rest[1:])
    return rest[: match.start() + 1] if match else rest


def present_call(src: str) -> tuple[int, str]:
    """(start index, text) of the `CompositionRenderGraph.present(...)` call, comments stripped."""
    start = src.index("CompositionRenderGraph.present(")
    depth = 0
    end = -1
    for i in range(start + len("CompositionRenderGraph.present"), len(src)):
        c = src[i]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                end = i + 1
                break
    if end < 0:
        raise AssertionError("unbalanced CompositionRenderGraph.present( call")
    call = src[start:end]
    # Comments must not be mistaken for argument expressions.
    return start, "\n".join(re.sub(r"//.*$", "", line) for line in call.splitlines())


def arg(call: str, name: str) -> str:
    match = re.search(rf"\b{name}\s*=\s*([^,\n]+)", call)
    assert match, f"argument {name}= not found in the present() call"
    return match.group(1).strip()


# --------------------------------------------------------------------------------------------
print("-- A. one graphChanged for both stages of a preview look update --")
playback = read(PLAYBACK)

main_fn = slice_fn(playback, "applyActiveLookToPlayers")
overlay_fn = slice_fn(playback, "applyOverlayLooks")

check(
    "PreviewGraphDecision.graphChanged(" in main_fn,
    "applyActiveLookToPlayers asks PreviewGraphDecision for graphChanged",
)
check(
    "PreviewGraphDecision.outcomeFor(" in main_fn,
    "applyActiveLookToPlayers installs through PreviewGraphDecision.outcomeFor",
)
check(
    "graphChanged = graphChanged," in main_fn,
    "the install decision is fed the SAME graphChanged value the rebuild gate used",
)
check(
    "graphChanged = lastMedia3GraphSignature != graphSig" not in playback,
    "no install site recalculates graphChanged from the graph signature alone",
)
check(
    "shouldCallSetVideoEffects(" not in main_fn and "shouldCallSetVideoEffects(" not in overlay_fn,
    "neither look-update path bypasses PreviewGraphDecision for the install decision",
)
check(
    "residentGradingNode = boundMainGrading != null" in main_fn,
    "the main path passes its resident-node state into the decision",
)
check(
    "identityFallbackActive = lookFallbackActive" in main_fn,
    "the identity-fallback throttle still reaches the decision (no setVideoEffects thrash)",
)
check(
    "PreviewGraphDecision.needsResidentGradingInstall(" in main_fn,
    "the skip branch does not record a signature while a grading node is missing",
)

dispatch_fn = slice_fn(playback, "replaceTimelineLooks")
check(
    "PreviewGraphDecision.needsResidentGradingInstall(boundMainGrading != null, lookFallbackActive)"
    in dispatch_fn,
    "the look dispatch uses the same resident-node definition as the update itself",
)
check(
    "boundMainGrading == null && !lookFallbackActive" not in playback,
    "no hand-inlined copy of the resident-node condition remains",
)

check(
    "PreviewGraphDecision.graphChangedOrForced(" in overlay_fn,
    "applyOverlayLooks uses the force-honouring gate (its historical semantics)",
)
check(
    "residentGradingNode = boundOverlayGrading[overlay.id] != null" in overlay_fn,
    "the overlay path passes its resident-node state into the decision",
)
check(
    "graphChanged = overlayGraphChanged," in overlay_fn,
    "the overlay install decision is fed the SAME graphChanged its gate used",
)

# The resident node may only be recorded once the graph is actually live.
accepted_block = overlay_fn[overlay_fn.index("if (accepted) {") :]
before_accepted = overlay_fn[: overlay_fn.index("if (accepted) {")]
check(
    "boundOverlayGrading[overlay.id] = grading" in accepted_block,
    "an overlay's resident node is recorded inside the accepted (installed) branch",
)
check(
    "boundOverlayGrading[overlay.id] = grading" not in before_accepted,
    "an overlay's resident node is NOT recorded before the install decision",
)
check(
    "boundOverlayGrading.remove(overlay.id)" in accepted_block,
    "a rejected overlay install drops the resident node so the next tick retries",
)

applied_block = main_fn[main_fn.index("Media3EffectPipeline.ApplyResult.APPLIED ->") :]
check(
    "boundMainGrading = effects.filterIsInstance<ColorGradingGlEffect>()" in applied_block,
    "the main resident node is recorded inside the APPLIED branch",
)

sync_fn = slice_fn(playback, "syncOverlayPlayers")
release_block = sync_fn[
    sync_fn.index("overlayPlayers.keys.toList().filter { it !in activeIds }") :
]
release_block = release_block[: release_block.index("for (overlay in activeOverlays)")]
check(
    "boundOverlayGrading.remove(id)" in release_block,
    "releasing an overlay player also drops its now-dead resident grading node",
)
check(
    "lastOverlayGraphSignatures.remove(id)" in release_block,
    "releasing an overlay player also drops its recorded graph signature",
)

# --------------------------------------------------------------------------------------------
print("\n-- B. preview and export hand the renderer identical look fallbacks --")
export_src, preview_src = read(EXPORT), read(PREVIEW)
export_start, export_call = present_call(export_src)
preview_start, preview_call = present_call(preview_src)

RESOLVER_BINDING = re.compile(
    r"val\s+lookFallback\s*=\s*CompositionLookResolver\.timelineFallback\(\s*(\w+)\s*\)"
)


def fallback_source(src: str, call_start: int) -> str | None:
    """The timeline variable the caller binds `lookFallback` from, just before present()."""
    matches = RESOLVER_BINDING.findall(src[:call_start])
    return matches[-1] if matches else None


for label, src, start, call in (
    ("export", export_src, export_start, export_call),
    ("preview", preview_src, preview_start, preview_call),
):
    binding = fallback_source(src, start)
    adj, filt = arg(call, "adjustments"), arg(call, "filter")
    check(
        binding is not None,
        f"{label} binds its renderer fallback with CompositionLookResolver.timelineFallback(<timeline>)",
        "no `val lookFallback = CompositionLookResolver.timelineFallback(...)` before present()",
    )
    check(
        adj == "lookFallback.adjustments",
        f"{label} passes the project-level adjustments fallback",
        adj,
    )
    check(
        filt == "lookFallback.filter",
        f"{label} passes the project-level filter fallback",
        filt,
    )
    check(
        "activeClip" not in adj and "activeClip" not in filt,
        f"{label} does not pre-resolve a clip into the renderer's fallback arguments",
    )

check(
    fallback_source(export_src, export_start) == "timeline"
    and fallback_source(preview_src, preview_start) == "tl",
    "each caller binds the fallback from its own Timeline, not from a clip",
    f"export={fallback_source(export_src, export_start)} preview={fallback_source(preview_src, preview_start)}",
)
check(
    arg(export_call, "adjustments") == arg(preview_call, "adjustments")
    and arg(export_call, "filter") == arg(preview_call, "filter"),
    "both callers pass literally the same fallback expressions to the shared render graph",
    f"export={arg(export_call, 'adjustments')} preview={arg(preview_call, 'adjustments')}",
)
check(
    "frame.activeClip?.adjustments ?:" not in strip_comments(export_src)
    and "frame.activeClip?.filter ?:" not in strip_comments(export_src),
    "no clip-pre-resolved fallback remains in the export code (comments excluded)",
)
check(
    "CompositionLookResolver.TimelineFallback(" not in strip_comments(export_src)
    and "CompositionLookResolver.TimelineFallback(" not in strip_comments(preview_src),
    "neither caller builds the fallback by hand instead of asking the resolver",
)

check(
    arg(export_call, "chromaKey") == "timeline.chromaKey"
    and arg(preview_call, "chromaKey") == "tl.chromaKey",
    "both callers pass the project-wide chroma default (clips override it in the renderer)",
)
check(
    arg(preview_call, "presentation").endswith("FramePresentation.PREVIEW")
    and "FramePresentation.export(" in arg(export_call, "presentation"),
    "the two callers differ only by FramePresentation, as the shared graph documents",
)

# --------------------------------------------------------------------------------------------
print("\n-- C. every layer's look is resolved in exactly one place --")
renderer = read(RENDERER)
sites = {
    "main picture adjustments": "CompositionLookResolver.adjustmentsFor(frame.activeClip, adjustments)",
    "main picture filter": "CompositionLookResolver.filterFor(clip, filter)",
    "overlay adjustments": "CompositionLookResolver.adjustmentsFor(overlay.clip, fallbackAdjustments)",
    "overlay filter": "CompositionLookResolver.filterFor(overlay.clip, fallbackFilter)",
}
for label, needle in sites.items():
    check(needle in renderer, f"the renderer resolves {label} through CompositionLookResolver")

for pattern, label in (
    (r"activeClip\?\.adjustments\s*\?:", "main-layer adjustments"),
    (r"clip\?\.filter\s*\?:", "main-layer filter"),
    (r"overlay\.clip\.adjustments\s*\?:", "overlay adjustments"),
    (r"overlay\.clip\.filter\s*\?:", "overlay filter"),
):
    hits = re.findall(pattern, renderer)
    check(not hits, f"no hand-inlined fallback remains for {label}", f"{len(hits)} found")

check(
    renderer.count("import com.example.engine.composition.CompositionLookResolver") == 1,
    "GpuCompositionRenderer imports the resolver",
)
for path, label in ((EXPORT, "AsyncFramePipelineEngine"), (PREVIEW, "PreviewCompositor")):
    check(
        "import com.example.engine.composition.CompositionLookResolver" in read(path),
        f"{label} imports the resolver",
    )

# --------------------------------------------------------------------------------------------
print("\n-- D. the pure decision objects stay JVM-testable --")
for rel, label in (
    ("com/example/engine/effects/media3/PreviewGraphDecision.kt", "PreviewGraphDecision"),
    ("com/example/engine/composition/CompositionLookResolver.kt", "CompositionLookResolver"),
):
    src = read(os.path.join(MAIN, rel))
    imports = re.findall(r"^import\s+(.+)$", src, re.MULTILINE)
    bad = [i for i in imports if i.startswith(("android.", "androidx."))]
    check(not bad, f"{label} has no android/androidx import (harness can execute it)", str(bad))

harness = read(os.path.join(REPO, "toolchain/verify/run_logic_tests.sh"))
for needle, label in (
    ("PreviewGraphDecision.kt", "PreviewGraphDecision.kt compiled by run_logic_tests.sh"),
    ("CompositionLookResolver.kt", "CompositionLookResolver.kt compiled by run_logic_tests.sh"),
    ("PreviewGraphDecisionTest.kt", "PreviewGraphDecisionTest.kt compiled by run_logic_tests.sh"),
    ("CompositionLookResolverTest.kt", "CompositionLookResolverTest.kt compiled by run_logic_tests.sh"),
    ("com.example.engine.effects.media3.PreviewGraphDecisionTest", "PreviewGraphDecisionTest executed"),
    ("com.example.engine.composition.CompositionLookResolverTest", "CompositionLookResolverTest executed"),
):
    check(needle in harness, label)

# --------------------------------------------------------------------------------------------
print()
print("=" * 78)
print(f"RESULT: {checks} checks, {len(failures)} failures")
print("=" * 78)
sys.exit(1 if failures else 0)
