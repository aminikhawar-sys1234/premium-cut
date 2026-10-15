# Post-patch verification — Filters / Adjust / Video Quality (PR #52 / #53)

Scope: the colour-look patch that unified the preview and export colour pipelines. The patch was
merged without ever being compiled or rendered, so this document separates **what has now actually
been executed** from **what is still unverified**, and names the environment behind each layer.

Evidence is reproducible from two commands and two links:

| Layer | Where it ran | Result |
|---|---|---|
| Automated math checks (Python) | this workstation | `verify_look_model.py` 50/0, `verify_shader_parity.py` 55/0 |
| Kotlin compilation + unit tests (`./gradlew`) | GitHub Actions, ubuntu-24.04, JDK 21 | 183 suites / 1463 cases, 0 failures; lint, `assembleDebug` and `bundleRelease` green |
| Real-GPU preview/export + real MP4 export (instrumented) | GitHub Actions emulator, API 30 x86_64, `-gpu swiftshader_indirect` (GLES 3.0 SwiftShader) | 36 cases, 35 pass, 1 pre-existing failure (see §5.1) |

Every device measurement below is written by the tests themselves into `look-verification.txt` and
posted as a pull-request comment by
[`.github/workflows/postpatch-verify.yml`](../.github/workflows/postpatch-verify.yml), because this
workstation cannot read runner logs or download artifacts (the endpoints answer `EOF`).

## 1. What the patch changes, restated from the code

* `engine/effects/ColorLookModel.kt` — one stage list, one uniform resolution and one CPU reference
  (`evaluate`, documented as the **non-spatial** stages only) shared by preview and export.
* `media3/ColorGradingGlEffect.kt` — the preview shader now builds its look through
  `presetMatrixFor` → `effectiveWithAndroidMatrix` → `ColorLookModel.effective`, and
  `isNoOp()` is `lookParams().isNeutral()` instead of an ad-hoc threshold.
* `composition/gpu/GpuShaders.kt` + `GpuCompositionRenderer.bindCommonUniforms` — the export shader
  uses the same Rec.709 luma, masks, fade, grain and spatial stages as the preview; the grain seed is
  passed in (`uGrainSeed`) instead of being derived from wall-clock time.
* `GpuCompositionRenderer` also fixes the overlay look fallback (`clip.adjustments ?:
  timeline.adjustments`, `clip.filter ?: timeline.filter`) so an overlay without a clip-local look is
  not exported ungraded.
* `EglCore.kt` — the default display is reference counted and `activeGlVersion` can no longer be
  downgraded while a GLES3 context is alive.
* `playback/VideoPlaybackEngine.kt`, `export/AsyncFramePipelineEngine.kt` — clip-local look
  resolution on both paths.

## 2. Automated math checks (this workstation)

```
cd toolchain/verify
python3 verify_look_model.py     # 50 checks, 0 failures
python3 verify_shader_parity.py  # 55 checks, 0 failures
```

These compare the GLSL stage order/constants with `ColorLookModel.evaluate` and the Android
`ColorMatrix` path in Python. They are not a build and not a render: they prove the *math* is
consistent, nothing more.

## 3. Kotlin compilation and unit tests

Run on GitHub Actions (JDK 21 Temurin, AGP/Kotlin 2.2.10, compileSdk 36, NDK 28.2.13676358):

```
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
./gradlew test
./gradlew :app:assembleDebugAndroidTest
./gradlew :app:lintDebug
```

* `:app:compileDebugKotlin` — **passes** (run 38537569761, commit `b7d981a`).
* `:app:testDebugUnitTest` and `test` — **pass**: 183 suites, 1463 cases, 0 failures.
* `:app:lintDebug`, `:app:assembleDebug`, `:app:bundleRelease` — pass.
* `:app:assembleDebugAndroidTest` — **fails on the pre-patch tree and initially on mine**; the
  errors were in the new test sources (loop-scoped accumulators in
  `ColorPipelineGpuInstrumentedTest`), fixed in `b7d981a`, and it now compiles (`ffc4c15`, `a118b56`).

This workstation has no JDK, no Android SDK, no Gradle cache and no route to `services.gradle.org`,
`repo1.maven.org` or `dl.google.com` (HTTP 000), so **no build or test in this document was run
locally**, and none is claimed to have been.

## 4. Device tests: real GPU, real encodes

`.github/workflows/postpatch-verify.yml` runs the instrumentation suite on an emulator with a real
GLES driver and posts the numbers. The harness (`LookGpuHarness`) creates a genuine EGL context
through the app's own `EglCore`, compiles the **shipped** preview and export fragment shaders,
renders them into an FBO with the uniforms `ColorLookModel` resolves, and reads the pixels back.

Measured on API 30 x86_64, `OpenGL ES 3.0 SwiftShader 4.0.0.1`:

| Phase 3 requirement | Measurement | Verdict |
|---|---|---|
| Shaders compile on a real driver | preview `ok=true`, export `ok=true`, empty logs | ✅ |
| Neutral preview/export look is a pass-through | `exportMeanDiff=0.00000 previewMeanDiff=0.00000` (1/255 = 0.00392) | ✅ |
| Decoder `A=0` frames are not black/transparent | `exportLuma=0.5118 exportAlpha=255`, same on preview | ✅ |
| Every slider changes real pixels | 21 sliders, per-pixel max delta 0.0196…0.3490 | ✅ |
| Sliders match the CPU reference | 15 non-spatial sliders: mean diff 0.00060, worst 0.00107 | ✅ |
| Filters change pixels | 4K 0.0625, Black Light 0.1385, Golden 0.1241, HDR 0.0973, Enhance 0.1084, Glow 0.1781 (mean delta at 100 %) | ✅ |
| Intensity 0 restores the frame | `delta@0=0.00000` for all six, monotonic 25→50→100 | ✅ |
| Preview vs export parity (pixels) | 8 looks incl. spatial stages: `meanDiff=0.00000 maxDiff=0.00000` | ✅ |
| Repeated filter changes / reset | 6 rounds × 6 filters, `darkestFrameLuma=0.5337`, no drift, reset returns to baseline | ✅ |
| Two live EGL contexts survive one release | lumaA=0.5118, lumaB before/after = 0.5118/0.5118 (each on its own thread) | ✅ |
| Save + reopen keeps the look | `filter=GOLDEN_AUTUMN/1.0 enabled=true brightness=0.25 saturation=1.35` | ✅ |
| Look reaches the exported MP4 | export delta `+0.4345/+0.3640/+0.0408` vs GPU-predicted `+0.3877/+0.3088/+0.0493`; max channel error **0.0552** (tolerance 0.10); neutral export vs decoded source ≤ 0.0076 | ✅ |
| Export is a valid MP4 | `valid=true message='Verified'` for graded and neutral | ✅ |
| Audio survives a graded export | `hasActiveAudio=true`, mix peak 0.1885, exported `audio/mp4a-latm` **1194 ms** vs 1200 ms timeline | ✅ |
| Chroma key regression | keyed half: green dominance 1.0000 → −1.0000; other half unchanged (0.0000) | ✅ |
| Overlay compositing regression | frame delta 0.5288 with a red overlay | ✅ |
| Transitions | see §5.1 | ❌ pre-existing |

## 5. Failures, root causes, and what is *not* verified

### 5.1 "Shader transitions blank the frame" was a wrong test sample point (fixed)

The instrumented case sampled `FADE` at exactly `progress = 0.5` and asserted the frame was not
black:

```
shader transition compositor: available=true (OpenGL ES 3.0 SwiftShader 4.0.0.1)
transition FADE@50%: rendered=true meanRgb=0.0000/0.0000/0.0000
transition fades: before(600ms)=0.5328 mid(1000ms)=0.0000 after(1450ms)=0.0000
```

That is not an engine failure - it is the shader being correct. `FADE_FRAG` is a dip *through*
black (`p < 0.5` fades clip A down to zero, `p >= 0.5` fades clip B up from zero), which is exactly
what `docs/transition-tools-audit.md` bug 1 changed on purpose ("Fade now uses the fade shader"
because it had behaved like a dissolve). At `p = 0.5` the multiplier is `(0.5 - 0.5) * 2 = 0`, so a
working engine returns pure black - the reported `meanRgb=0.0000/0.0000/0.0000` with
`deltaA=1.0 / deltaB=1.5` is the arithmetic of the two test colours, not a blanked framebuffer.

`shaderTransitionsStillRenderIntoTheFrameOnTheGpu` now samples 25% and 75% (where a correct fade
still shows half-brightness clip A / clip B) for the "never blank a frame" contract, and pins the
50% dip explicitly so a Fade that quietly becomes a dissolve again is caught here.

`ShaderTransitionCompositor.render` additionally drains pending GL errors before the pass and
reports failure when the pass itself raised one, so a genuinely blanked pass falls back to the
legacy single-texture path instead of handing the export a black texture.

### 5.2 Bugs found in the *verification* code (fixed)

* `glReadPixels` bytes were read as a flat stream, so the alpha byte landed inside the colour stream
  every fourth component: this alone produced the "neutral look changed the frame (0.32)" and the
  per-slider CPU mismatches in the first run. Fixed by walking the RGBA stride.
* Both EGL contexts were created on one thread; EGL state is per thread, so releasing one made the
  other's `eglMakeCurrent` fail. The app keeps preview and export on different threads; the test now
  does the same (per-thread workers).
* The synthetic frame had no near-black/near-white pixels, so `whites`/`blacks` (which weight by
  luma) legitimately did nothing. Bands at 0.02/0.98 added.
* `File.toURI()` produces `file:/data/...` (one slash), which `isExportableAudioUri` rejects, so the
  export correctly wrote no audio track and the test wrongly blamed the muxer. The app stores
  `Uri.fromFile(...)` = `file:///data/...`; the fixtures now use the same form.
* `ColorLookModel.evaluate` is documented as the **non-spatial** reference (one pixel, no
  coordinate), so sharpness/clarity/denoise/antiFlicker/vignette/grain cannot match it. Those six are
  now measured on the GPU only and reported separately; the other 15 agree within 0.0011.
* `ExampleInstrumentedTest` asserted package `com.example` against an app whose applicationId is
  `com.aistudio.videostudio.hkmqzp` — a stale template test that failed on every device.
* `ExportValidatorInstrumentedTest.fileWithoutMoovRejectedAsMuxerFailure` truncated the file at the
  moov offset; when the muxer writes moov early that leaves a ~24-byte file, which the validator
  rejects as `FILE_TOO_SMALL` before it inspects any box. It now removes the moov box instead.

### 5.3 Still unverified

* **Real hardware GPU.** All device numbers come from SwiftShader. Chroma key, overlays, transitions
  and the spatial stages should be re-measured on a physical device.
* **Chroma/mask uniform parity beyond the keyed case**, alpha/opacity blending of overlays, and the
  preview *screen* path (`VideoPlaybackEngine` + Media3 `TextureView`) are covered by unit tests and
  by the shader-level GPU tests, but not by an end-to-end preview test on a device.
* **Playback, pause, scrubbing and seeking** were not driven on a device: the harness renders
  frames, it does not run the player.
* **Devices below API 30 / GLES 2.0.** The export shader compiles on SwiftShader GLES 3.0; an ES2
  context takes a different branch in `GpuShaders.buildFragmentShader` and was not exercised.
* **HEVC / high-resolution exports.** All exports were H.264 at 320×240 (`sizeOverride`) to keep the
  emulator run bounded.
