# Composition & rendering consistency — audit, repair and verification report

Branch: `arena/583c2f5a-premium-cut` (base `913535b`)

Scope: make timeline composition, live preview, filters, Chroma Key, overlays and final export follow
**one** composition model and **one** rendering contract, without rebuilding anything that works.

---

## 0. Environment limitation — read this first

This workspace had **no JDK, no Gradle, no Android SDK, no emulator**, and outbound network is
restricted to `github.com`, `api.github.com`, `registry.npmjs.org`, `pypi.org` and
`files.pythonhosted.org`. `./gradlew` cannot resolve AGP, androidx, Media3 or ML Kit, so:

* **`./gradlew compileDebugKotlin` has NOT been run.** The four Android-tainted files changed below
  (`VideoPlaybackEngine`, `AsyncFramePipelineEngine`, `PreviewCompositor`, `GpuCompositionRenderer`)
  are **not compiler-verified**.
* **No Robolectric / JUnit-Android test has been executed** (`FilterEngineForensicTest`,
  `PreviewEffectApplyPolicyTest`, `PreviewExportLookParityTest`, `LookShaderContractTest`, …).
* **No instrumented test, no device, no emulator, no GPU.** Exported-video playback and audio sync
  have **not** been observed.

**This gap was subsequently closed by CI** — see §6. The branch was pushed and PR **#57** opened, which
triggered `Android CI/CD Build and Test` (ubuntu-24.04, JDK 21, Android SDK + NDK 28.2.13676358 +
CMake 3.22.1). Run `38667106238` completed **success**: Android Lint, `./gradlew test` (the full
Robolectric/JUnit suite, including both new test classes), **Build Debug APK** and **Build Release
Bundle (AAB)** all passed. So the four Android-tainted files **do compile** and the whole unit-test
suite **does pass** on the real toolchain. `Report failed unit tests` and `Gradle diagnostics on
failure` (both `if: failure()`) were **skipped**, confirming no test failures.

What still has *not* been done anywhere: anything on a **device, emulator or GPU** (§5 item 8 and 10).

What I ran locally, in order of strength:

1. **Bootstrapped a real JVM toolchain from the allowed registries** — `jdk4py` (Temurin JDK 25) from
   PyPI and `kotlin-compiler` 2.4.21 from npm — and used the repo's own
   `toolchain/verify/run_logic_tests.sh` harness to **compile and execute the real production Kotlin
   and the real test files** on a plain JVM: **67 tests, 0 failures**.
2. Ran the repo's existing Python verifiers: **50** look-model checks, **55** shader-parity checks,
   Chroma Key parity over **52 920** colour/slider combinations — all pass, none regressed.
3. Added `toolchain/verify/verify_render_contract.py` (**52 checks**) — a source-level drift guard for
   the invariants in the four files that cannot be compiled here.
4. **Negative controls**: each bug was deliberately reintroduced in a throwaway copy of the tree and
   confirmed to fail the new tests/guard (3 Kotlin test failures; 4–10 guard failures, exit 1). The
   checks are not vacuous.

Reproduce the toolchain:

```bash
python3 -m venv .venv && ./.venv/bin/pip install jdk4py
npm install --prefix ./kc kotlin-compiler@2.4.21
export JAVA_HOME=$(.venv/bin/python -c 'import jdk4py;print(jdk4py.JAVA_HOME)')
export PATH="$JAVA_HOME/bin:$PWD/kc/node_modules/kotlin-compiler/bin:$PATH"
bash toolchain/verify/run_logic_tests.sh
```

---

## 1. Architecture map (from the audit, not assumed)

### 1.1 Composition state — single source of truth: `Timeline`

`TimelineEngine.timeline: StateFlow<Timeline>` owns everything the renderers draw:
video/image clips, track and layer order, trim/timing, transforms, opacity, `filter`,
`adjustments`, `colorGradeJson` (LUT), `vfxStackJson`, `chromaKey`, masks, keyframes, enabled flags.

* Clip-local `VideoClip.filter` / `VideoClip.adjustments` are **nullable** and override the
  project-level `Timeline.filter` / `Timeline.adjustments`.
* The UI does **not** keep an isolated effect state. `FilterToolsPanel` →
  `StudioViewModel.publishLiveLook` → `VideoPlaybackEngine.publishLiveLook` writes the look into the
  timeline (`PreviewLookPolicy.applyLookToTimeline`) and commits it (debounced) via `commitLiveLook`.
  `_livePreviewLook` is a transient in-flight value, not a second source of truth.
* Undo/redo, project saving and timeline editing were **not touched**.

### 1.2 There are two preview renderers, and that is deliberate

| | Renderer A — GPU compositor | Renderer B — player path |
|---|---|---|
| Entry | `PreviewCompositor` (own GL thread + Choreographer) | `ExoPlayer` (Media3) |
| Composition | `CompositionRenderGraph.assemble/present` → `GpuCompositionRenderer.render` | `setVideoEffects` graph: `ColorGradingGlEffect` + LUT + `PreviewVfxEffects` |
| Decode | `HardwareVideoTextureSource` (MediaCodec) per layer at exact source time | Media3 decoder |
| Chroma Key / background removal | **yes** (per-pixel, in the shader) | **no** |

The gate is `PreviewCompositionPolicy.shouldActivate` in `VideoPlaybackEngine.refreshCompositorMode`:
Chroma Key or background removal on **any** layer forces Renderer A on; otherwise Renderer B runs.
`compositorFailed` falls back to B. Verified by the executed tests
`chromaKeyOnAnyLayerActivatesTheCompositorWithoutASetting`,
`backgroundRemovalActivatesTheCompositorToo`, `aProjectWithNoPerPixelEffectStaysOnThePlayerPath`.

### 1.3 Export renderer

`AsyncFramePipelineEngine` → the **same** `CompositionRenderGraph.assemble/present` →
the **same** `GpuCompositionRenderer.render`, differing only by
`FramePresentation.export(flipY)` + `deterministicMasks = true`, then MediaCodec encode and
`AudioExportProcessor` mux. Export never uses Renderer B.

So **export ≡ Renderer A**. The consistency risk is entirely (a) inside Renderer A's caller contract
and (b) between Renderer A/export and Renderer B.

### 1.4 Effect ordering (Renderer A / export shader, `GpuShaders`)

mask → **1. Chroma Key** (chroma-distance key, soft edge, spill suppression, foreground opacity,
transparent / solid / image-video background) → 2. Exposure & Brightness → 3. Contrast →
4. Saturation → 5. Temperature & Tint → 6. Highlights & Shadows → 7. Color Matrix Filter →
8. Vignette → 9. Grain → 10. Opacity; then the colour-grade (LUT) stage → VFX stack → face warp →
overlays → text/stickers → transitions.

Chroma Key runs **first, on the raw source colour**, before any grade. That is the correct order and
it is already consistent between preview and export: `verify_chroma_key.py` keys **52 920**
colour/slider combinations identically on both paths (max alpha delta 4.44e-16, max spill delta 0).
**No Chroma Key ordering defect was found, and none was changed.**

### 1.5 Duplicate / conflicting pipelines found

* **Two grading shader implementations** (`GpuShaders` for A/export, `ColorGradingGlEffect` for B).
  This duplication is *managed*, not accidental: `ColorLookModel` owns every constant and range for
  both, and `LookShaderContractTest` + `verify_shader_parity.py` (55 checks, 13 shared stages in the
  same order) pin them together. Per the brief ("do not assume preview and export must use the same
  low-level renderer … ensure both implement equivalent, tested visual semantics") this was **left
  alone** — unifying it would be a large, risky change with no evidence of a current defect.
* `VideoCompositionEngine.renderGpuFrame` — a **third** `CompositionRenderGraph.present` call site
  with **zero callers**. Left in place (removing it is out of scope and its deletion is not needed for
  any fix), but flagged in §6.
* `PreviewEffectApplyPolicy` and `VideoPlaybackEngine` each contained their own idea of
  "the graph changed" → **RC1**.
* The preview and export callers disagreed on what the renderer's look arguments mean → **RC3**.

---

## 2. Confirmed root causes

All three were confirmed by reading the code paths and, where possible, by an executed test. None was
assumed.

### RC1 — `graphChanged` was computed twice, with two different definitions (main preview path)

`VideoPlaybackEngine.applyActiveLookToPlayers`:

```kotlin
// (1) the rebuild gate
val missingResidentGrading = boundMainGrading == null
val graphChanged = PreviewEffectApplyPolicy.shouldRebuildGraph(lastMedia3GraphSignature, graphSig, force) ||
  missingResidentGrading
...
// (2) the install decision — recalculated, and the resident term is GONE
val shouldSetEffects = PreviewEffectApplyPolicy.shouldCallSetVideoEffects(
  ..., graphChanged = lastMedia3GraphSignature != graphSig, force = force)
```

This is exactly the mismatch the brief asked me to look for, and it is present. Consequence: whenever
"no grading node is resident" was the **only** reason to rebuild, the engine built a fresh effect list
(including baking a LUT bitmap) and then **skipped `setVideoEffects`** — the recovery path could never
recover, and it recorded `lastMedia3GraphSignature` on the way out, making the state sticky.

The existing comment block at the same site shows the author had already fixed *half* of this bug
(`boundMainGrading` is now assigned only in the `APPLIED` branch) but the install-side recalculation
was left behind, so the half-fix removed the wrong-object publish without restoring the install.

Honest scoping note: the only state that currently reaches the divergence is `FALLBACK_IDENTITY`
(`boundMainGrading = null` + `lookFallbackActive = true` + signature recorded), which the
`lookFallbackActive` throttle already gates. So RC1 today costs wasted work and a structurally dead
recovery branch rather than a guaranteed black preview. It is a real defect in the state machine and
it is fragile — any future path that clears `boundMainGrading` without clearing the signature turns it
into a stuck preview. Fixed because the code confirms the mismatch.

### RC2 — the same bug, unfixed and unmasked, in the overlay/PiP path

`applyOverlayLooks` had all three halves of the problem:

```kotlin
// skip-check tests the resident node…
if (!force && lastOverlayGraphSignatures[overlay.id] == graphSig && boundOverlayGrading[overlay.id] != null) continue
…
// …but the node is recorded BEFORE the install decision (the §1.1 orphan bug, never fixed here)
if (grading != null) boundOverlayGrading[overlay.id] = grading else boundOverlayGrading.remove(overlay.id)
// …and the install-check drops the resident term again
if (!PreviewEffectApplyPolicy.shouldCallSetVideoEffects(..., graphChanged = lastOverlayGraphSignatures[overlay.id] != graphSig, force)) {
  lastOverlayGraphSignatures[overlay.id] = graphSig   // recorded even though nothing was installed
  continue
}
```

Reachable trigger confirmed in `syncOverlayPlayers`: overlay players exist only around the playhead, so
an overlay that leaves the window has its `ExoPlayer` released — but `boundOverlayGrading[id]` and
`lastOverlayGraphSignatures[id]` were **not** cleared (only the full `releaseOverlayPlayers()` teardown
cleared them). When the overlay scrolls back, a **fresh** player is created with no effects, while
`applyOverlayLooks` sees an "unchanged" graph *with* a resident node and skips the install. The overlay
comes back **unfiltered**, and every later `publishLive` writes uniforms into the dead instance from the
released player. Unlike RC1 there is no `lookFallbackActive` throttle masking it.

### RC3 — export passed a clip-pre-resolved look as the renderer's *per-layer fallback*

`GpuCompositionRenderer.render`'s `timelineAdjustments` / `timelineFilter` are **project-level
fallbacks**; the renderer resolves every layer against them itself — main picture
(`frame.activeClip?.adjustments ?: adjustments`, `clip?.filter ?: filter`), each transition side (via
`processMainVideoTo2D`), and each overlay (`overlay.clip.adjustments ?: fallbackAdjustments`,
`overlay.clip.filter ?: fallbackFilter`).

The two callers disagreed about that contract:

```kotlin
adjustments = frame.activeClip?.adjustments ?: timeline.adjustments   // export
adjustments = tl.adjustments                                          // preview
```

For the **main** layer the two are idempotent, so the exported look matched the panel — which is why the
earlier fix in this area looked complete. For **every other layer** they differ: an ungraded overlay or
transition side inherited the **main clip's** grade in the export while the preview fell back to the
**timeline's**. Because the Filters panel writes clip-local looks and clears `Timeline.filter` to `NONE`,
exported PiP overlays carried a grade the preview never showed.

---

## 3. Architecture before / after

**Before**

```
Timeline (source of truth)
   │
   ├─ Renderer A: PreviewCompositor ──present(tl.adjustments, tl.filter)──┐
   │                                                                     ├─ GpuCompositionRenderer
   ├─ Export:  AsyncFramePipeline ──present(mainClip.adjustments ?: tl)──┘   (resolves per layer)
   │                        ▲ divergence: non-main layers fell back differently
   └─ Renderer B: ExoPlayer ─ applyActiveLookToPlayers
                    graphChanged = rebuild || missingResident      (gate)
                    shouldSetEffects = (lastSig != sig) || force   (install)  ▲ two definitions
                    applyOverlayLooks: same split + resident node recorded pre-install
                                     + stale node kept when the overlay player is released
```

**After**

```
Timeline (source of truth — unchanged)
   │
   ├─ CompositionLookResolver  ← ONE rule: a layer's own look wins, the project look is the fallback
   │     ▲ used by GpuCompositionRenderer for main / transition / overlay layers
   │     ▲ used by BOTH callers to build the fallback they pass
   ├─ Renderer A: PreviewCompositor ──present(lookFallback)──┐
   ├─ Export:  AsyncFramePipeline ──present(lookFallback)────┘  identical arguments
   │
   └─ Renderer B: ExoPlayer ─ PreviewGraphDecision  ← ONE graphChanged for both stages
                     graphChanged(…)            (gate)
                     outcomeFor(graphChanged=…) (install)   same value, cannot disagree
                     applyOverlayLooks: graphChangedOrForced (old force semantics preserved)
                                        resident node recorded only after a successful install
                                        resident node + signature cleared with the player
```

Two new **pure-Kotlin** owners (no `android.*` / `androidx.*`), which is what makes them executable in
the JVM harness:

* `PreviewGraphDecision` — the one Media3 preview graph decision (gate + install + reason).
* `CompositionLookResolver` — the one per-layer look-resolution rule and the one caller contract.

---

## 4. Files changed and why

### New

| File | Why |
|---|---|
| `app/src/main/java/com/example/engine/effects/media3/PreviewGraphDecision.kt` | Single authoritative definition of `graphChanged` for the live-preview Media3 graph, shared by the rebuild gate and the install decision (RC1, RC2). Pure Kotlin → executable in the JVM harness. `graphChanged`, `graphChangedOrForced` (the overlay path's historical force semantics), `needsResidentGradingInstall` (with the identity-fallback throttle), `mayInstallEmptyGraph`, `outcomeFor`, `decide`. |
| `app/src/main/java/com/example/engine/composition/CompositionLookResolver.kt` | Single rule for "which look does this layer draw" plus the caller contract (`timelineFallback`) (RC3). Pure Kotlin → executable. |
| `app/src/test/java/com/example/engine/effects/media3/PreviewGraphDecisionTest.kt` | 13 tests. Includes the RC1 regression test, an exhaustive stage-1/stage-2 agreement sweep, an exhaustive equivalence proof against the **old** `shouldCallSetVideoEffects` (all 24 combinations) and against the **old** overlay gate (all 24 combinations), the identity-fallback throttle, empty-graph and clear-graph rules, and the overlay-lost-its-player case. |
| `app/src/test/java/com/example/engine/composition/CompositionLookResolverTest.kt` | 11 tests. Includes the RC3 regression test (overlay falls back to the timeline, not the main clip), transition sides, idempotence for the main layer only, empty/no-effect projects, disabled and zero-intensity filters. |
| `toolchain/verify/verify_render_contract.py` | 52 checks. Drift guard for the four files that cannot be compiled here: one `graphChanged` for both stages, resident node recorded only after install, overlay-player release clears resident state, preview/export pass identical fallbacks, all four renderer resolution sites go through the resolver, and the pure objects stay JVM-testable and wired into the harness. |

### Modified

| File | Change |
|---|---|
| `engine/playback/VideoPlaybackEngine.kt` | **RC1**: `graphChanged` computed once via `PreviewGraphDecision.graphChanged` (now including the `lookFallbackActive` throttle, so the fallback state stops baking LUT bitmaps on every tick) and the *same* value fed to `outcomeFor`; the skip branch no longer records the signature while a node is missing. **RC2**: overlay gate → `graphChangedOrForced`; install → `outcomeFor`; `boundOverlayGrading[id]` recorded **only** in the `accepted` branch and removed on failure; `syncOverlayPlayers` clears `boundOverlayGrading` / `lastOverlayGraphSignatures` / `lastOverlayFilterSignatures` when it releases an overlay player. `replaceTimelineLooks` now uses the shared `needsResidentGradingInstall` instead of a hand-inlined copy. Log line reports the decision `reason`. |
| `engine/export/AsyncFramePipelineEngine.kt` | **RC3**: passes `CompositionLookResolver.timelineFallback(timeline)` (hoisted to one `val` per frame) instead of the clip-pre-resolved look, so it hands the renderer the same arguments the preview does. Comment corrected — the previous one described the contract backwards. |
| `engine/preview/PreviewCompositor.kt` | Same resolver call as the export (behaviour unchanged: it already passed the timeline values), so the two callers cannot drift again. |
| `engine/composition/gpu/GpuCompositionRenderer.kt` | The four per-layer resolution sites (main adjustments, main filter, overlay adjustments, overlay filter) delegate to `CompositionLookResolver`. Behaviour-preserving; removes the hand-inlined copies. |
| `engine/effects/media3/PreviewEffectApplyPolicy.kt` | `shouldRebuildGraph` and `shouldCallSetVideoEffects` delegate to `PreviewGraphDecision`, so there is one definition. Signatures and semantics unchanged — proven equivalent over all 24 input combinations by `forceRebindsAnUnchangedGraphExactlyAsTheOldPolicyDid`. |
| `toolchain/verify/run_logic_tests.sh` | Compiles and executes the two new production files and the two new test classes. |

Nothing was deleted. No UI, timeline-editing, undo/redo, persistence, encoding or muxing code was
touched.

---

## 5. Tests executed and actual results

| Suite | Command | Result |
|---|---|---|
| Pure-Kotlin logic tests (**executed on a real JVM**, kotlinc 2.4.21 + Temurin JDK 25, against the real production sources) | `bash toolchain/verify/run_logic_tests.sh` | **67 passed, 0 failed** (43 pre-existing + 24 new) |
| Rendering-contract drift guard (new) | `python3 toolchain/verify/verify_render_contract.py` | **52 checks, 0 failures** |
| Look model | `python3 toolchain/verify/verify_look_model.py` | **50 checks, 0 failures** (unchanged from baseline) |
| Preview/export shader parity | `python3 toolchain/verify/verify_shader_parity.py` | **55 checks, 0 failures** (unchanged) |
| Chroma Key preview/export parity | `python3 toolchain/verify/verify_chroma_key.py` | **OK** — shared constants equal, **52 920** combinations keyed identically (max alpha delta 4.44e-16, max spill delta 0.00e+00) |

All five exit 0.

New tests by name (`PreviewGraphDecisionTest`): `aMissingResidentNodeAloneMustInstallTheGraph`,
`bothStagesOfOneUpdateAgreeOnGraphChanged`, `sliderTicksWithAResidentNodeNeverRebuildOrInstall`,
`identityFallbackStopsRetryingTheGraph`, `identityFallbackStillRecoversWhenTheGraphReallyChanges`,
`anEmptyGraphIsNeverInstalledJustToApplyNothing`, `clearingAnAppliedGraphIsStillAllowed`,
`forceRebindsAnUnchangedGraphExactlyAsTheOldPolicyDid`, `firstApplyWithNoRecordedSignatureInstalls`,
`shouldRebuildGraphIsUnchangedByTheRefactor`, `theOverlayGateKeepsItsOldForceSemanticsExactly`,
`graphChangedAloneDoesNotRebindAnUnchangedGraphJustBecauseForceWasSet`,
`anOverlayThatLostItsPlayerRetriesInsteadOfSkipping`.

(`CompositionLookResolverTest`): `aLayersOwnLookWinsOverTheProjectDefault`,
`aLayerWithoutALookFallsBackToTheProjectDefault`, `anOverlayFallsBackToTheTimelineNotToTheMainClip`,
`anOverlayKeepsItsOwnLookUnderBothFallbacks`, `eachTransitionSideResolvesItsOwnLook`,
`preResolvingTheMainClipIsIdempotentForTheMainLayerOnly`,
`timelineFallbackIsTheTimelineLookForEveryCaller`,
`anEmptyProjectAndAProjectWithoutEffectsResolveToTheDefaults`,
`anUngradedClipInAGradedProjectInheritsTheProjectLook`, `aDisabledFilterIsStillTheLayersOwnFilter`,
`zeroIntensityIsStillTheLayersOwnLook`.

### Negative controls (proof the new checks are not vacuous)

| Reintroduced bug | Detected by | Result |
|---|---|---|
| Install check recalculated from the signature alone | `PreviewGraphDecisionTest` | 3 failures (`aMissingResidentNodeAloneMustInstallTheGraph`, `anOverlayThatLostItsPlayerRetriesInsteadOfSkipping`, `bothStagesOfOneUpdateAgreeOnGraphChanged`) |
| Same, in the engine source | `verify_render_contract.py` | 2 failures, exit 1 |
| Overlay resident node recorded before install | `verify_render_contract.py` | 1 failure |
| Overlay player released without clearing resident state | `verify_render_contract.py` | 2 failures |
| Export pre-resolves the main clip into the fallback | `verify_render_contract.py` | 4 failures, exit 1 |

### Coverage of the brief's §8 checklist

| # | Requirement | Status |
|---|---|---|
| 1 | A filter changes the preview immediately | **Partial** — CPU-side contract executed (`FilterEngineForensicTest` exists; the decision path is now tested). Not observed on a GPU. |
| 2 | Filters work without first applying Chroma Key | **Verified statically + by executed policy tests** — Renderer B path; `aProjectWithNoPerPixelEffectStaysOnThePlayerPath` executed. Not observed on a device. |
| 3 | Filters still work after applying and removing Chroma Key | **Verified statically** — `exitCompositorMode` resets signature + resident node, so the decision reinstalls; RC1 fix removes the sticky state. Not observed on a device. |
| 4 | Changing filter parameters during playback updates subsequent frames | **Partial** — `publishLive` path unchanged and covered by existing tests; not observed. |
| 5 | Seeking and switching clips preserve effect state | **Partial** — `anOverlayThatLostItsPlayerRetriesInsteadOfSkipping` executed (the seek-away/seek-back overlay case, RC2). Main-clip case not observed. |
| 6 | Multiple layers render in the correct order | **Verified** — `CompositionLookResolverTest` (executed) + unchanged renderer ordering. |
| 7 | Preview and exported frames use equivalent effect settings | **Verified** — 52-check contract guard + 55-check shader parity + 52 920-combination Chroma Key parity, all executed. RC3 removed the last caller-level divergence. |
| 8 | Exported video plays correctly with synchronized audio | **NOT VERIFIED** — needs a device/emulator. Encoding and muxing were not touched. |
| 9 | Empty projects and projects without effects keep working | **Verified** — `anEmptyProjectAndAProjectWithoutEffectsResolveToTheDefaults` executed. |
| 10 | Repeated editor open/close does not leak renderer resources | **NOT VERIFIED at runtime** — lifecycle code unchanged; the overlay-state clearing added here is a correctness fix, not a leak fix. |

---

## 6. Build status and unverified runtime behaviour

**Build status: GREEN on the real toolchain.** PR **#57** → workflow `Android CI/CD Build and Test`,
run `38667106238`, conclusion **success**. Every step that matters:

| CI step | Conclusion |
|---|---|
| Install Android SDK toolchain (CMake + NDK) | success |
| Run Android Lint (Code Quality) | success |
| **Run Unit Tests** (`./gradlew test`) | **success** |
| Report failed unit tests (`if: failure()`) | **skipped** → no test failed |
| **Build Debug APK** | **success** |
| **Build Release Bundle (AAB)** | **success** |
| Gradle diagnostics on failure (`if: failure()`) | **skipped** → no build failure |

So the four Android-tainted files (`VideoPlaybackEngine`, `AsyncFramePipelineEngine`,
`PreviewCompositor`, `GpuCompositionRenderer`) **compile**, and the full unit-test suite passes.

Both new test classes were genuinely **executed** by Gradle, not silently skipped: `app/build.gradle.kts`
declares `testImplementation(libs.junit)` and sets **no** `useJUnitPlatform()`, so the default JUnit 4
runner discovers them — the same runner the pre-existing `ChromaKeyResolverTest` /
`PreviewEffectApplyPolicyTest` use, and both new classes are plain JUnit 4 (`org.junit.Test` +
`org.junit.Assert`) in the standard `app/src/test/java` root. `PreviewGraphDecisionTest` needs no
Robolectric because `PreviewGraphDecision` is pure.

The raw CI log could not be downloaded from this sandbox (`results-receiver.actions.githubusercontent.com`
is outside the allowed hosts), so the per-test counts come from the local JVM harness rather than from
Gradle's XML reports. The step conclusions above are from the GitHub API and are authoritative for
pass/fail.

Still unverified anywhere — **needs a device or emulator**:

* Actual GPU behaviour, `setVideoEffects` timing, surface rebinding, black-frame and audio-silence
  regressions.
* Exported-file playback and A/V sync (§5 item 8).
* Renderer resource cleanup across repeated editor open/close (§5 item 10).
* Whether RC2's overlay symptom reproduces on a real device exactly as the code path implies — the
  state machine is now proven correct by executed tests, but the on-screen result has not been observed.

Deliberate behaviour preservation (verified by exhaustive equivalence tests, not assumed):

* `PreviewEffectApplyPolicy.shouldCallSetVideoEffects` is bit-for-bit equivalent to the old
  implementation for all 24 input combinations.
* The overlay rebuild gate is equivalent to the old `!force && sigEqual && resident` skip for all 24
  combinations — this matters because `graphChanged` alone has *narrower* force semantics than the
  overlay path historically had, and silently switching it would have regressed `flushDeferredLooks`
  and `recreatePreviewPlayer`.
* The identity-fallback throttle still stops `setVideoEffects` thrash on devices that cannot run the
  grading graph; it now also stops the wasted LUT-bitmap baking that the old divergent path performed.

---

## 7. Remaining limitations and recommended next steps

1. ~~**Run CI.**~~ **Done — green** (run `38667106238`: Lint, `./gradlew test`, Debug APK, Release AAB).
   Nothing further is needed for compilation or the unit-test suite.
2. **On-device verification of the three fixes**, in this order — this is now the only remaining gap:
   * PiP/overlay filter: grade an overlay, scrub the playhead away until the overlay player is
     released, scrub back → the overlay must still be graded (RC2).
   * Filter with no Chroma Key, then add Chroma Key, then remove it, then change filter intensity
     during playback (RC1 + requirement §5 items 2–4).
   * Export a project with a graded main clip **and an ungraded PiP**, and one with a transition into
     an ungraded clip → the PiP / transition side must export ungraded, matching the preview (RC3).
3. **Two grading shaders remain** (`GpuShaders` for A/export, `ColorGradingGlEffect` for B). Parity is
   enforced by `ColorLookModel` + `LookShaderContractTest` + `verify_shader_parity.py`, which is the
   architecture the brief permits. If a future requirement needs an effect Renderer B cannot express,
   the cheaper path is to widen `PreviewCompositionPolicy.requiresCompositor` (route that effect to
   Renderer A) rather than to unify the shaders.
4. **`VideoCompositionEngine.renderGpuFrame` has zero callers** and is a third `present()` call site
   with neutral default looks. Not deleted (out of scope, and no fix depends on it), but it is a
   latent place for the RC3 contract to be broken again. Recommend deleting it, or routing it through
   `CompositionLookResolver`, once someone confirms no external module reflects on it.
5. **`ColorFilterGenerator.isIdentityMatrix`** still compares all 20 matrix entries with exact `==`
   (carried over from the previous audit, §1.10). UI-thumbnail-only; worst case is a redundant no-op
   filter. Worth the same epsilon treatment.
6. **Consider promoting `verify_render_contract.py` into CI** alongside the existing verifiers, so the
   two contracts stay enforced on every PR rather than only in sandboxes without an SDK.
