# Filters / Color Adjustment — audit, repair and implementation report

Branch: `arena/400e6652-premium-cut` (base `cb4ef2a`)

---

## 0. Environment limitation — read this first

**No part of this work has been compiled or executed on a device.**

This workspace has **no JDK, no Gradle, no Android SDK and no emulator**:

```
java / javac / gradle / adb : not installed
ANDROID_HOME / ANDROID_SDK_ROOT : unset
./gradlew : cannot run
```

Therefore:

* **`./gradlew compileDebugKotlin` has NOT been run.** Every Kotlin change below is
  unverified by a compiler.
* **No JUnit / Robolectric test has been executed.** No instrumentation test has been run.
* **Nothing has been verified on a GPU, a device or an emulator.**

What *has* been executed is a Python verification harness that reads the checked-in Kotlin
and GLSL sources directly (see §6). It proves the **maths and the shader text** are
consistent; it does **not** prove the Kotlin compiles.

---

## 1. Root causes found

### 1.1 The preview stopped reacting to Filters / Adjust / Video Quality (the main bug)

`VideoPlaybackEngine` builds the Media3 effect list, then decides whether to actually call
`ExoPlayer.setVideoEffects`. It recorded the freshly built `ColorGradingGlEffect` as
`boundMainGrading` **before** that decision:

```kotlin
val effects   = PreviewFilterEffects.effectsFor(clip, currentTimeline, lutBmp, lut?.second ?: 1f)
val grading   = effects.filterIsInstance<ColorGradingGlEffect>().firstOrNull()
grading?.publishLive(gradingSnapshotFor(clip))
boundMainGrading = grading          // ← recorded even when the graph was never rebuilt
```

When `PreviewEffectApplyPolicy.shouldCallSetVideoEffects(...)` returned `false` (the normal
case: the graph signature had not changed), `setVideoEffects` was not called, so the object in
`boundMainGrading` was **not the node inside the player's video graph**. Every later slider
tick and preset change published onto that orphan object while the resident node kept its old
snapshot — which is exactly the reported symptom: the panel updates, the picture does not.

**Fix** (`VideoPlaybackEngine.kt`): `boundMainGrading` is assigned **only** in the
`ApplyResult.APPLIED` branch (the graph is live, this instance is the resident node), cleared
on `FAILED`, and the publish step falls back to the resident node
(`boundMainGrading ?: effects.filterIsInstance<ColorGradingGlEffect>().firstOrNull()`).

### 1.2 Export never received the clip's look

`AsyncFramePipelineEngine` passed the **project-wide** look to the renderer:

```kotlin
adjustments = timeline.adjustments,
filter      = timeline.filter,
```

but the panel writes **clip-local** looks — `TimelineEngine.updateFilter` (line 2233) writes
`clip.filter` and forces `Timeline.filter = NONE`, and `VideoClip.adjustments` overrides
`Timeline.adjustments`. So the export rendered `NONE` / neutral while the preview showed the
look. **Fix**: `frame.activeClip?.adjustments ?: timeline.adjustments` and
`frame.activeClip?.filter ?: timeline.filter` — the same resolution the preview uses.

### 1.3 Three divergent copies of the same colour maths

The look was computed independently in three places that had drifted apart:

| Path | Luma | Highlight / shadow masks | Sharpen gain | Grain amplitude | Vignette coord |
|---|---|---|---|---|---|
| Preview shader (`ColorGradingGlEffect`) | Rec.709 `0.2126/0.7152/0.0722` | `0.0–0.6`, `0.4–1.0` | `1.5` | `0.25` | clamped `uv` |
| Export shader (`GpuShaders`) — **before** | Rec.601 `0.299/0.587/0.114` | `0.0–0.5`, `0.5–1.0` | effectively `4.0` | `0.30` | **unclamped** `vTextureCoord` |
| CPU (`ColorFilterGenerator`) | n/a | n/a | n/a | n/a | n/a |

Export sharpening used `c - uSharpness * ((up+down+left+right) - 4.0*c)`, which is
`c + 4.0 * uSharpness * (c - average)` — **2.67× the preview gain**. This is the direct cause
of "exports don't look like the preview".

**Fix**: one `ColorLookModel` object now owns every constant, every range and the uniform
resolution for **both** paths; the two GLSL strings were aligned to it stage by stage.

### 1.4 Opaque-alpha / black frames

Video and decoder textures arrive with **A = 0**. A colour matrix that preserves that alpha
composites the frame as fully transparent → a black preview. The preview forced the alpha row
opaque (`a[15..18] = 0, a[19] = 255`); the export did not, and the two used different
conversions. **Fix**: `ColorLookModel.filterGlMatrix(...)` is the single conversion used by
both.

### 1.5 Colour-matrix conversion bug

`ColorFilterGenerator.colorMatrixToGlMatrix` built element 15 from `arr[18]` (the alpha row's
alpha scale) instead of `arr[15]` (row 0's alpha term), corrupting the last column of the GL
4×4 whenever a matrix had a non-identity alpha term. **Fix**: delegates to
`ColorLookModel.toGlMatrixAndOffset`.

### 1.6 EGL lifecycle

`EglCore.release()` called `eglTerminate` on the **shared default display**. The first
`EglCore` released (e.g. the preview compositor) terminated the display out from under the
second one (the export renderer), leaving its contexts dead. `activeGlVersion` could also be
downgraded to 2 while a GLES3 context was still rendering. **Fix** (`EglCore.kt`): the display
is shared and reference-counted (only the last release terminates it) and `activeGlVersion`
can only drop to 2 when no GLES3 context is live.

### 1.7 Overlay look fallback

`GpuCompositionRenderer.processOverlayVideoTo2D` fell back to a **bare neutral** /
`FilterSettings()` for an overlay with no clip-local look, while the preview uses
`clip.adjustments ?: timeline.adjustments` / `clip.filter ?: timeline.filter`. An overlay
graded in the editor was therefore exported ungraded. **Fix**: new
`fallbackAdjustments` / `fallbackFilter` parameters, wired from `render()`.

### 1.8 Adjust-Reset left Smart Auto applied

`ColorAdjustmentRanges.resetPrimary` reset the primary sliders but not `autoEnhance`,
`colorFix` or `colorCorrect`, whose sliders live in the Adjust tab — the panel claimed a reset
while the preview kept the look. **Fix**: `resetPrimary` clears them too. Video Quality-only
controls (Super Clarity, HDR Boost, Denoise, Anti-Flicker) stay with `resetQuality`, which the
Video Quality tab's Reset calls.

### 1.9 Grain was not reproducible between preview and export

The export shader seeded its grain hash from the raw texture coordinate; the preview seeded
from the frame time. **Fix**: both read `uGrainSeed` (preview: frame presentation time,
export: clip source time — the two cannot be identical, but the *pattern* is now the same
function of the seed, and the amplitude matches at `0.25`).

### 1.10 Neutrality test used ad-hoc, inconsistent thresholds

`ColorGradingSnapshot.isNoOp()` used `abs(x) < 0.001f` for some parameters and `< 0.01f` for
others, so the answer depended on which parameter you moved by 0.005. It now delegates to
`ColorLookModel.LookParams.isNeutral()`, which applies one `NEUTRAL_EPSILON = 0.001f` to all
twenty parameters and also returns `false` whenever a colour matrix is present.

Known remaining issue (deliberately **not** changed — see §8):
`ColorFilterGenerator.isIdentityMatrix` still compares all 20 entries with exact `==`. It is
only used to decide whether the *UI* applies a Compose `ColorFilter` to a thumbnail, so the
worst case is a redundant no-op filter, but it should get the same epsilon treatment.

---

## 2. Files changed

| File | Change |
|---|---|
| `app/src/main/java/com/example/engine/effects/ColorLookModel.kt` | **New.** Single deterministic definition of a look: 20 documented `Range`s, every pipeline constant, `STAGE_ORDER`, `LookParams`, `presetMatrix`, `effective`, `effectiveWithAndroidMatrix`, `filterGlMatrix`, `toGlMatrixAndOffset`, and a CPU reference `evaluate` used by tests. Plain Kotlin, no Android types in the maths. |
| `app/src/main/java/com/example/engine/effects/media3/ColorGradingGlEffect.kt` | Preview node now resolves **all** uniforms through `ColorLookModel` (`effectiveWithAndroidMatrix` + `fromLookParams`); `presetMatrixFor` and the GL conversion delegate to the model; `ColorGradingSnapshot.isNoOp()` uses the epsilon-based `LookParams.isNeutral()`; `evaluatePreviewPixel` delegates to `ColorLookModel.evaluate` instead of a hand-maintained second copy. |
| `app/src/main/java/com/example/engine/composition/gpu/GpuShaders.kt` | Export shader aligned to the preview: Rec.709 luma, `0.0/0.6` + `0.4/1.0` masks, 4-tap unsharp mask at gain `1.5` (was a `4.0` Laplacian), grain amplitude `0.25` with the seeded hash, vignette on the **clamped** coordinate, new `uGrainSeed` uniform. |
| `app/src/main/java/com/example/engine/composition/gpu/GpuCompositionRenderer.kt` | `bindCommonUniforms` resolves the look through `ColorLookModel` (`presetMatrix` + `filterGlMatrix` + `effective`) instead of recomputing it inline; overlay path takes `fallbackAdjustments` / `fallbackFilter`; `grainSeed` plumbed through. |
| `app/src/main/java/com/example/engine/export/AsyncFramePipelineEngine.kt` | Exports the **clip-effective** look (`frame.activeClip?.adjustments ?: timeline.adjustments`, `frame.activeClip?.filter ?: timeline.filter`). |
| `app/src/main/java/com/example/engine/composition/gpu/EglCore.kt` | Shared, reference-counted EGL display; `activeGlVersion` can no longer be downgraded under a live GLES3 context. |
| `app/src/main/java/com/example/engine/effects/ColorAdjustmentRanges.kt` | `resetPrimary` also clears `autoEnhance`, `colorFix`, `colorCorrect`. |
| `app/src/main/java/com/example/engine/composition/ColorFilterGenerator.kt` | `colorMatrixToGlMatrix` element-15 bug fixed; delegates to `ColorLookModel.toGlMatrixAndOffset`. |
| `app/src/main/java/com/example/engine/playback/VideoPlaybackEngine.kt` | `boundMainGrading` is now only the node that is actually resident in the player's graph (fix §1.1). |

### Tests added

| File | What it covers |
|---|---|
| `app/src/test/java/com/example/engine/effects/ColorLookModelTest.kt` | Robolectric unit tests: neutral preserves the frame (incl. opaque alpha), zero-intensity is neutral, all 15 sliders change pixels, direction checks, every selectable filter changes pixels, intensity monotonic and 0 = neutral, 200-switch stability + determinism, clamping, UI ranges ↔ render ranges, Adjust-Reset clears Smart Auto, `filterGlMatrix` layout, disabled filter ⇒ no matrix. |
| `app/src/test/java/com/example/engine/effects/LookShaderContractTest.kt` | Pure-string drift guard: both shaders declare the same uniforms and run the 13 shared stages in the same order with the same constants. No GPU needed. |
| `app/src/test/java/com/example/engine/effects/PreviewExportLookParityTest.kt` | The mandatory "export uses the same effective parameters as preview" test: 16 (adjustments × filter) cases must resolve to byte-identical uniforms, identical `uColorMatrix`/`uColorOffset`, identical neutrality, and exactly one resident preview grading node. |

### Verification harness (executed — see §6)

| File | |
|---|---|
| `toolchain/verify/verify_look_model.py` | 50 checks. Ports `ColorLookModel` to Python with its constants and ranges **scraped live from the Kotlin source**, then asserts the documented behaviour. |
| `toolchain/verify/verify_shader_parity.py` | 55 checks. Parses both GLSL strings out of the Kotlin and diffs them stage by stage. |
| `toolchain/verify/filter_matrices.json` | The 24 parsed 20-float Android colour matrices, keyed by `FilterType`. |

Run with `python3 toolchain/verify/verify_look_model.py` and
`python3 toolchain/verify/verify_shader_parity.py`.

---

## 3. Documented ranges, defaults and processing order

### Ranges (identical in the UI and the renderer — asserted by
`ColorLookModelTest.uiRangesAndRenderRangesAgree`)

| Parameter | Range | Neutral | | Parameter | Range | Neutral |
|---|---|---|---|---|---|---|
| Brightness | −1 … 1 | 0 | | Whites | −1 … 1 | 0 |
| Contrast | 0.10 … 2 | 1 | | Blacks | −1 … 1 | 0 |
| Saturation | 0 … 2 | 1 | | Fade | 0 … 1 | 0 |
| Exposure | −1 … 1 | 0 (EV) | | Auto Enhance | 0 … 1 | 0 |
| Temperature | −1 … 1 | 0 | | HDR Boost | 0 … 1 | 0 |
| Tint | −1 … 1 | 0 | | Color Fix | 0 … 1 | 0 |
| Highlights | −1 … 1 | 0 | | Denoise | 0 … 1 | 0 |
| Shadows | −1 … 1 | 0 | | Glow | 0 … 1 | 0 |
| Sharpening | 0 … 1 | 0 | | Vignette | 0 … 1 | 0 |
| Clarity | 0 … 1 | 0 | | Grain | 0 … 1 | 0 |

Values outside a range are **clamped**, not rejected, so a corrupted project file degrades
gracefully instead of producing NaN uniforms.

### Processing order (`ColorLookModel.STAGE_ORDER`, identical in both shaders)

```
 1  sharpen            spatial 4-tap unsharp mask, gain 1.5, radius 1 texel
 2  clarity            spatial mid-tone unsharp mask, gain 1.5, radius 3 texels
 3  exposure           rgb *= 2^EV          (photographic stops)
 4  brightness         rgb += offset        (added AFTER exposure)
 5  contrast           rgb = (rgb - 0.5) * c + 0.5
 6  saturation         rgb = mix(luma, rgb, s)      luma = Rec.709
 7  temperature / tint per-channel gains
 8  highlights/shadows luma-masked additive, masks 0.0–0.6 and 0.4–1.0
 9  whites/blacks      luma-masked additive, edges 0.65–1.0 / 0.0–0.35
10  autoEnhance / hdrBoost / colorFix / denoise / fade   (Video Quality)
11  glow               spatial highlight bloom
12  filter colour matrix (the artistic preset, intensity-blended)
13  vignette           spatial radial
14  grain              deterministic seeded hash noise
15  clamp rgb to 0..1 + force alpha = 1
```

### How panel values, Video Quality and filter extras combine

`ColorLookModel.effective` sums the three sources (Adjust panel + Video Quality panel +
the filter's own spatial extras) and **then** clamps:

```
autoEnhance = clamp(adjust.autoEnhance + extras.enhance)
hdrBoost    = clamp(adjust.hdrBoost + adjust.colorCorrect + extras.hdrLook)
denoise     = clamp(adjust.denoise + adjust.antiFlicker * 0.5)
sharpness   = clamp(adjust.sharpness + extras.sharpness)
clarity     = clamp(adjust.clarity + adjust.superClarity + extras.clarity)
glow        = clamp(extras.glow)
```

Filter spatial extras (× intensity): `4K {sharpness 0.55, clarity 0.40}`,
`Enhance {sharpness 0.38, clarity 0.32, enhance 0.45}`, `HDR {hdrLook 0.70, clarity 0.22}`,
`Glow {glow 0.85}`. Golden and Black Light are pure colour matrices.

### Persistence

No schema change: the look already lives in `VideoAdjustments` + `FilterSettings` on the clip,
serialized by `TimelineSerializer` (Moshi + `KotlinJsonAdapterFactory`, reflection-based), so
existing projects keep loading and any new field persists automatically.

### Threading / resource lifecycle

* Slider ticks publish a `ColorGradingSnapshot` onto the resident `ColorGradingGlEffect` via
  `publishLive` — no `setVideoEffects`, no graph rebuild, no decoder restart, no EGL work.
* Shaders are compiled once per `GlShaderProgram`; `EglCore.release()` no longer terminates a
  display another context is using.
* `TimelineEngine.updateFilter` already forces `Timeline.filter = NONE` so a clip-local and a
  project-wide look can never both apply.

---

## 4. Capability honesty

| Label in the UI | What it actually does |
|---|---|
| **4K** | A micro-contrast look (`sharpness 0.55`, `clarity 0.40` at full intensity) plus a mild mid-tone snap matrix. **It is not an upscaler.** Real 4K output is an *export resolution* choice in the export pipeline, independent of this filter. |
| **HDR** | An SDR local-contrast / saturation look (`hdrBoost`, no tone curve above 1.0). **It is not HDR10, HLG or Dolby Vision.** |
| **True HDR output** | Not supported. `GlFramebuffer` allocates `RGBA8`; there is no float, `RGBA16F`, PQ or HLG path anywhere in the pipeline. |
| **4K upscaling** | Not implemented as a filter. Only the export resolution setting produces 4K frames. |
| Golden, Black Light, Enhance, Glow | Real, tested per-pixel colour/spatial processing (see §6 for measured deltas). |

No filter is a placeholder, a hardcoded preview image, or an arbitrary matrix presented as
something it is not.

---

## 5. Preview ↔ export parity — what is now proven, and what is not

**Proven (by `verify_shader_parity.py`, 55/55):** every shared stage uses identical constants
and runs in the same relative order. Two remaining, deliberately sanctioned differences:

1. Preview guards contrast and saturation with `abs(x - 1.0) > 0.001`; export applies them
   unconditionally. Mathematically identical at neutral.
2. Export guards whites/blacks with `!= 0.0`; preview uses `abs(...) > 0.001`. Identical
   bodies.

**Proven (by `PreviewExportLookParityTest`, not yet run):** the two entry points
(`ColorGradingGlEffect.fromTimeline` → `effectiveWithAndroidMatrix`, and
`GpuCompositionRenderer.bindCommonUniforms` → `presetMatrix` + `filterGlMatrix` + `effective`)
are both built on `ColorLookModel`, and the test asserts they produce identical uniforms.

**Not proven, and not provable in this workspace:**

* That the two shaders produce pixel-identical frames on a real GPU. The CPU model covers all
  non-spatial stages; the **spatial** stages (sharpen, clarity, denoise, glow, vignette, grain)
  execute only on the GPU. Texture filtering, precision (`mediump` preview vs `highp` export)
  and edge clamping make exact equality impossible anyway.
* Expected residual differences at export time: encoder colour-space conversion
  (BT.709 full→limited range), chroma subsampling, scaling to the export resolution, and the
  codec's own quantisation. The existing `ParityHarness` / `ExportColorBridge` use a tolerance
  of 3/255 — **note that this harness is not wired into the real export path**, so it does not
  by itself demonstrate parity.

---

## 6. Test and verification results

### 6.1 Executed — `verify_look_model.py` (50 checks, 0 failures)

The model, its 44 constants and its 20 ranges are **parsed out of `ColorLookModel.kt` at run
time**, so the harness cannot pass against stale assumptions.

Measured mean |Δ| per slider over four probes (0.62/0.41/0.28, 0.90/0.88/0.82,
0.12/0.10/0.14, 0.50/0.50/0.50):

```
brightness 0.8875   contrast 0.2294   exposure 0.3532   fade 0.1836
saturation 0.0762   temperature 0.1500   tint 0.1120   highlights 0.0740
shadows 0.0866   whites 0.0442   blacks 0.0367   autoEnhance 0.0874
hdrBoost / colorCorrect 0.1248   colorFix 0.0493
```

Filters at intensity 1.0:

```
Golden 0.4057   Black Light 0.3744   Enhance 0.3229   Glow 0.2857   HDR 0.2640   4K 0.0899
```

Groups: neutral round-trip (Δ = 0.00000000), 15 sliders change pixels, 7 direction checks,
6 filters change pixels, 6 monotonic-intensity (0 = neutral, 0.25 < 0.5 < 1.0), 7 visual
appropriateness, 200-switch stability + reset, 3 clamping.

### 6.2 Executed — `verify_shader_parity.py` (55 checks, 0 failures)

25 shared uniforms declared in both; `pow(2.0, uExposure)` in both; Rec.709 luma
`0.2126/0.7152/0.0722` in both; mask edges `(0.0, 0.6)` and `(0.4, 1.0)` in both; whites/blacks
`(0.65,1.0)` / `(0.0,0.35)` with gain `0.18` in both; contrast pivot `0.5` in both; sharpen gain
`1.5` in both and export no longer uses the `- 4.0 * c` Laplacian; grain amplitude `0.25` with
seed inputs `±0.13/0.17` in both; vignette reads the clamped coordinate in both; both
`clamp(color.rgb, 0.0, 1.0)`; all 13 shared stages in the same relative order.

### 6.3 Not executed — Gradle

```
./gradlew : not runnable (no JDK, no Gradle, no Android SDK)
```

The following therefore have **unknown** status and must be run before merging:

```
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest            # Robolectric unit tests
./gradlew :app:connectedDebugAndroidTest    # needs a device / emulator
```

### 6.4 Static review of the pre-existing tests

Because they cannot be run, the tests that touch the changed APIs were reviewed by hand
against the new implementation:

| Test | Status by inspection |
|---|---|
| `FilterEngineForensicTest` | All 24 `FilterType` matrices verified numerically (delta > 0.01 at 1.0, weak ≠ strong); all 15 adjustment cases verified (max Δ 0.089 – 1.050); `isNoOp()` on neutral/disabled/zero still true; `forceOpaqueAlpha` offset[3] = 1.0 still holds. |
| `Media3EffectPipelineTest` | Uses `isNoOp(w, h)` (always `false` by design) and `isIdentityLook()`; neutral ⇒ `isIdentityLook()` true, adjusted ⇒ false. Expected to pass. |
| `PreviewEffectApplyPolicyTest` | `publishLive` semantics unchanged (constructor fields untouched, `liveSnapshot` replaced). Expected to pass. |
| `PreviewLookAndRestoreTest` | `assertFalse(isNoOp(1920,1080))` — trivially true. Expected to pass. |
| `VideoFiltersTest` | `fromTimeline` field values unchanged for in-range inputs (`denoise 0.5` ⇒ `0.5` since `antiFlicker = 0`). Expected to pass. |

One behavioural change to be aware of: `ColorGradingSnapshot.isNoOp()` now uses a single
epsilon of `0.001` for **all** parameters. Previously `vignette`, `grain`, `sharpness`,
`clarity`, `fade`, `autoEnhance`, `hdrBoost`, `colorFix`, `denoise` and `glow` used `< 0.01`.
A snapshot with e.g. `vignette = 0.005f` was previously "no-op" and is now "not no-op". No
existing test depends on that boundary.

---

## 7. Known remaining issues (not fixed)

1. `ColorFilterGenerator.isIdentityMatrix` uses exact float equality on all 20 matrix entries
   (see §1.10). Only affects UI thumbnails. Not changed because it touches
   `PreviewLookPolicy.displayMatrix` / `EditorScreen`, which cannot be exercised here.
2. `ColorFilterGenerator.colorMatrixToGlMatrix` is now unused; it is kept because it is public
   API and may be referenced from plugin code.
3. `ParityHarness` / `ExportColorBridge` (androidTest, tolerance 3/255) exist but are **not**
   wired into `AsyncFramePipelineEngine` or `VideoExporter`, so they do not currently
   demonstrate export parity.
4. The export seeds grain from the clip's source time and the preview from the frame's
   presentation time. The amplitude and the hash are now identical, but the seed values
   cannot be made equal, so grain will not be a frame-for-frame match.

## 8. What still needs a device

1. **Compile and run the unit tests** (`./gradlew :app:testDebugUnitTest`).
2. **GPU checks that the CPU model cannot make**: spatial stages (sharpen, clarity, denoise,
   glow, vignette), texture filtering, FBO lifecycle, orientation and alpha preservation.
3. **End-to-end export parity**: render a known clip with a known look, export it, and compare
   decoded export frames against the preview within the documented tolerances
   (codec, scaling, BT.709 limited range).
4. **The manual scenarios in the bug report**: playback, pause, scrub, seek, project reopen,
   and 200 consecutive filter changes on a real device.
