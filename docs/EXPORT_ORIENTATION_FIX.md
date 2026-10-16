# Upside-down export — root cause, fix, verification

Report: *"Export ke baad video upside down ya vertical orientation mein ulat ho rahi hai"* — the
exported MP4 was vertically mirrored relative to the preview (portrait sources looked "rotated"
too, because a vertical mirror of a 9:16 frame reads as a rotation).

## 1. Root cause

The composed frame reaches its destination in exactly one place: the final blit in
`GpuCompositionRenderer.render()` —

```kotlin
// GpuCompositionRenderer.kt:940  (helper at :2445)
blitToSurface(currentInputTex, flipY = flipYForEncoder, flipX = flipXForEncoder)
```

`flipYForEncoder` comes from `CompositionRenderGraph.FramePresentation` and both destinations went
through it:

| Destination | Presentation | `flipY` |
| --- | --- | --- |
| Preview (`PreviewCompositor.kt:277`, `VideoCompositionEngine.kt:150`) | `FramePresentation.PREVIEW` | `false` |
| Export (`AsyncFramePipelineEngine.kt:972`) | `FramePresentation.export(ExportOrientationPolicy.FLIP_Y_FOR_ENCODER)` | **`true`** |

`true` there mirrors the whole picture vertically (`GpuShaders.ENCODER_BLIT_VERTEX_SHADER`:
`float y = (uFlipY != 0) ? 1.0 - coords.y : coords.y;`), so **export was upside down by exactly the
one flag the preview did not set** — same compositor, same layers, same matrices, one mirrored blit.
This is the double-flip the task warned about: the source's own orientation is already handled once,
inside the layer matrix (below), and the encoder blit added a second, vertical one.

Why the encoder Surface must **not** be flipped separately: the preview Surface, the screen and
`MediaCodec.createInputSurface()` are all GLES-fed BufferQueues with the same convention — NDC
`y = +1` is the top of the picture, on screen and in the encoded frame. AOSP's own round-trip test
(`cts/.../EncodeDecodeTest.java`) draws into the encoder input Surface with `glScissor`
(bottom-left origin) in the *upper* window half and then asserts the drawn content in the **top**
rows of the raw encoder buffer (`checkFrame`); the same test flips when it *reads back* through a
SurfaceTexture (`checkSurfaceFrame`: "coordinates are inverted on the Y-axis in GL"), which is a
consumer-sampling convention the compositor's OES path already honours via the SurfaceTexture
transform and the non-OES texture matrix (`translateM(0, 1)` + `scaleM(1, -1)` for layers without a
custom matrix). Third-party flip samples (`yellowcath/VideoProcessor`, Telegram's `OutputSurface`)
flip for *their* destination; copying that flip onto our destination is what broke export.

## 2. Orientation is still baked exactly once (unchanged, on purpose)

| Stage | Setting | Why |
| --- | --- | --- |
| Decoder (`HardwareVideoTextureSource.kt:214–221`) | `KEY_ROTATION = ExportOrientationPolicy.DECODER_KEY_ROTATION_DEGREES` = `0` | The container rotation must not be baked by MediaCodec; the compositor applies `naturalRotation` itself, so 90/270 sources cannot be rotated twice. |
| Compositor (`GpuCompositionRenderer.kt:1373–1380`) | `totalRot = naturalRot + clip.rotationDegrees + kf.rotation`, `Matrix.rotateM(..., ExportOrientationPolicy.glRotationDegrees(totalRot), 0, 0, 1)` | One rotation, in the layer matrix, with `glRotationDegrees(total) = -total` because container metadata is clockwise and `Matrix.rotateM` is counter-clockwise. `isTransposed` swaps the contain-fit axes for 90/270, so portrait sources are fitted, not cropped. |
| Muxer (`AsyncFramePipelineEngine.kt`) | `setOrientationHint(ExportOrientationPolicy.MUXER_ORIENTATION_HINT_DEGREES)` = `0` | The pixels *are* the display orientation; a hint on top would rotate a second time in the player. Also policed by `ExportContentRules.expectedRotation = 0` (`BAD_ROTATION`). |

The fix therefore changes exactly one value: `FLIP_Y_FOR_ENCODER` `true` → `false`. Filters, colour
adjustments, chroma key, overlays, text/stickers, transitions, audio and quality settings are not
touched: they all run before the final blit and none of them read the flag. The only other reader is
`trackedBlocking = flipYForEncoder || deterministicMasks` (`GpuCompositionRenderer.kt:518`), and the
export path still sets `deterministicMasks = true`, so export tracking stays blocking/deterministic.

## 3. What was run here, and what was not

### Executed, with results

| Command | Result |
| --- | --- |
| `python3 toolchain/verify/verify_export_orientation.py` | **127 checks, 0 failures** (20 source-contract + 48 simulated + 59 re-evaluated test expectations) |
| `python3 toolchain/verify/verify_export_orientation.py --blit-flip true` | **36 failures** — putting the old flip back reproduces the upside-down/mirrored export, so the guard is not vacuous |

The new guard (`toolchain/verify/verify_export_orientation.py`) does two jobs:

* **Part 1 — source contract (20 checks).** Reads the checked-in Kotlin and asserts: the policy
  constants (`FLIP_Y_FOR_ENCODER = false`, `MUXER_ORIENTATION_HINT_DEGREES = 0`,
  `DECODER_KEY_ROTATION_DEGREES = 0`), that the export presentation takes its flip from the policy and
  nothing hard-codes `flipY = true`, `FramePresentation.PREVIEW = (false, false, false)` vs
  `export(flipY) = (flipY, false, true)`, that the single final blit passes
  `flipYForEncoder`/`flipXForEncoder` while the FBO-to-FBO blit stays `flipY = false`, the blit
  shader's `uFlipY` branch, the decoder's `KEY_ROTATION` pin, the `setOrientationHint` call, and
  `ExportContentRules.expectedRotation == 0`.
* **Part 2 — numeric simulation (48 checks).** Re-implements the exact matrices the renderer builds
  (sampling, `rotateM(glRotationDegrees(totalRot))`, swapped fit scales, the blit flip) in Python,
  pushes the four corners of the source through them and checks, for landscape/portrait/square
  sources × 0/90/180/270 metadata onto their real 16-aligned canvases: the picture is upright and
  rotated by its metadata, it is not mirrored (signed handedness), it is fully inside the canvas
  (contain fit, no crop), and the preview blit and the encoder blit produce **identical** frames.
* **Part 3 — the Kotlin tests' expectations, re-evaluated (59 checks).** The unit test cannot run
  without a JDK and the instrumented test needs a device, so this part parses both test sources and
  re-derives their expectations: every `displaySize`/`glRotationDegrees`/`totalRotationDegrees`/
  `normalizeDegrees` literal in `ExportOrientationPolicyTest` is compared against a transcription of
  the shipped policy, the eight-transform table of `ExportOrientationInstrumentedTest` must match the
  guard's own table, and each of its 14 cases is run through a re-implementation of the test's
  picture-box + quadrant sampling + classifier: every case must classify as `rot<metadata>` when the
  exported picture is upright and must **not** classify as that when the picture is mirrored
  vertically. (This part caught a wrong expectation while the guard was being written - a swapped
  90/270 case - which is exactly the class of mistake nobody can catch without running the test.)

Whole guard suite afterwards (same interpreter, no device):

| Guard | Result |
| --- | --- |
| `verify_export_orientation.py` | 127 checks, 0 failures |
| `verify_render_contract.py` | 52 checks, 0 failures |
| `verify_shader_parity.py` | 55 checks, 0 failures |
| `verify_gl_sampler_contract.py` | 37 checks, 0 failures |
| `verify_texture_units.py` | 59 checks, 0 failures |
| `verify_look_model.py` | 50 checks, 0 failures |
| `verify_black_frame_recovery.py` | 60 checks, 0 failures |
| `verify_chroma_key.py` | OK — parity holds, 52 920 colour/slider combinations (max alpha delta 4.44e-16) |
| `verify_workflow_shell.py` | 241 checks, 0 failures (needs `pip install pyyaml`) |

### Not executed — environment, stated plainly

This sandbox has **no JDK, no Android SDK, no Gradle** (`java` is not installed,
`ANDROID_HOME`/`ANDROID_SDK_ROOT` unset). Therefore none of the following were run and **no result is
claimed for them**:

* `toolchain/verify/run_logic_tests.sh` (needs `kotlinc`) — including the new
  `ExportOrientationPolicyTest`.
* `:app:testDebugUnitTest` / Robolectric (`ExportOrientationAndPipelineTest`,
  `CompositionRenderGraphTest`, …).
* `:app:assembleDebugAndroidTest` and every test under `app/src/androidTest`, including the new
  `ExportOrientationInstrumentedTest`.

`main.yml` / `postpatch-verify.yml` run these on a runner with JDK 21 + NDK 28.2.13676358. Until they
do, the orientation of an exported frame is *designed* to be upright, not *measured* to be upright on
a device.

## 4. Tests added / updated

* **New** `app/src/androidTest/.../export/ExportOrientationInstrumentedTest.kt` — end-to-end, from
  pixels: exports each case through `AsyncFramePipelineEngine.export`, re-decodes the MP4 with
  `MediaExtractor` + `MediaCodec` via an `ImageReader`, reads the Y plane and classifies the exported
  picture against **all eight** dihedral transforms (4 rotations × horizontal mirror) of the source's
  four quadrant luma levels. A stray Y flip, an unapplied rotation hint or a mirrored frame cannot
  pass, and the message names the transform it saw. Cases: portrait 9:16 (0/180), landscape 16:9
  (0/180), landscape and portrait sources carrying 90/270 metadata onto the opposite-aspect canvas,
  square 1:1 (0/90/180/270), a letterboxed aspect mismatch, and a mirrored clip (the GPU path a
  front-camera recording takes when mirroring is on). It also asserts the container contract:
  dimensions equal the export size and the rotation hint equals
  `MUXER_ORIENTATION_HINT_DEGREES` (a nonzero hint would rotate the picture a second time in a
  player), and that preview and export blits agree on `flipY`.
* **New** `app/src/test/.../export/ExportOrientationPolicyTest.kt` — pure JVM (no `android.*`), so it
  runs in `run_logic_tests.sh`: blit parity between the two presentation structs, single-rotation and
  zero-hint claims, `glRotationDegrees`, `displaySize` for landscape/portrait/square × 0/90/180/270
  plus degenerate sizes, degree normalisation and mirrored sources.
* **Updated** `ExportOrientationAndPipelineTest` (flip asserted `false`, export presentation asserted
  to match the preview), `CompositionRenderGraphTest` (export blit flip `false` + preview parity),
  `OesSamplerRegressionInstrumentedTest` (both call sites take the flip from the policy instead of
  hard-coding it), `toolchain/verify/run_logic_tests.sh` (new sources registered).
* **Updated** fixtures: `TestMp4Factory` gained `Picture.ORIENTATION_QUADRANTS` (+ exported
  `ORIENTATION_LUMA`) and per-file `width`/`height`, so orientation can be measured at all three
  aspects instead of only the old 192×160 fixture.

## 5. Still unverified / residual risk

* **Nothing ran on a device or on real hardware GPUs.** The device numbers above do not exist yet;
  `ExportOrientationInstrumentedTest` has never been executed.
* **The native compositor path was not read in this pass.** `AsyncFramePipelineEngine.kt:581–585`
  drives `nativeCompositorEnabled` *and* `shaderTransitionsEnabled` from
  `ExportBlackFrameGuard.useNativeCompositor(attemptNo)` (native on attempt 1, Kotlin on the retry).
  The native block still ends in the same shared `blitToSurface`, but
  `app/src/main/cpp/render/GpuRenderEngine.cpp` (its `uMVP`/`uST` uniforms and its own quad winding)
  has not been audited. **If a device run shows the first export attempt flipped and the retried
  (Kotlin) attempt upright, the suspect is that native intermediate, not this blit constant.**
* **Real footage / real decoder metadata.** Coverage uses synthetic H.264 sources with
  `setOrientationHint` metadata; camera recordings (including the odd `180°` files some front cameras
  write) were not available.
* **HEVC, 4K and non-16-aligned canvases** were not exercised.
* The instrumented measurement reads the Y plane of a decoder output Image; on devices where an
  `ImageReader` surface cannot be used as a decoder target the test reports the failure through
  `assumeNoException` (skipped, never a fake pass). No simulated device run has confirmed which
  branch a real emulator takes.
