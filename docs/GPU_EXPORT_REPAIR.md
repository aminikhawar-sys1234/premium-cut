# Zero-copy surface export repair

## Findings

The generic “Export Failed” UI is not a root-cause report. No device Logcat or
screenshot was available in this checkout, so these changes address code risks,
not a confirmed reproduction on a Redmi/Mali device.

- `ExportEncoderPlanner` explicitly kept unaligned 1920x1080, including an
  axis-range-only capability bypass. The surface pipeline now rounds each axis
  **up** to 16 pixels, including its unknown-capabilities fallback. 1080p becomes
  1920x1088 (portrait 1088x1920). UI/Media3 sizes remain unchanged. This is coded
  size alignment, not padding/cropping metadata: composition uses the aligned
  canvas, so the exact output aspect ratio changes slightly. 16 alignment is a
  compatibility policy, not an Android requirement or a guarantee of codec support.
- EGL was bound only during setup. The render task now explicitly binds at entry
  and each frame, before decoder texture/renderer work. EGL operations enforce
  their creating thread; failed make-current operations retain EGL error codes.
  Constructor failures release the shared-display reference.
- Failure/cancel could loop forever waiting for rendering (`stop -> continue`),
  especially after a drain failure. The waiter now observes cancellation and
  worker errors; the watchdog also covers a stall in the **first** swap.
- Timed waits followed by `shutdownNow()` did not establish worker termination.
  Cleanup now waits for the drain consumer, serializes decoder/GL cleanup behind
  rendering, unbinds before destroying EGL surfaces, and only then releases input
  surfaces/codecs/muxer. On a driver hang, cleanup is deferred and automatic
  retries are disabled rather than releasing resources under a live worker.
  A permanently hung driver can still retain resources until process restart.
- Output buffers now release in `finally`, including when muxer writes fail.
- `MuxerCoordinator.stopAndRelease()` previously returned true on every repeated
  call, even after failed finalization. It now retains the actual result, clears
  pending packets, and resets started state. Release failures mark failure too.
- Native composition has no independent EGL pipeline: it uses Kotlin's context.
  JNI now verifies the engine's owning thread and context before GL calls, and
  Kotlin propagates render failures instead of merely logging and continuing.
  Context-loss notification remains CPU-only and does not require a live context.

## EOS contract (retained)

Frames receive monotonic nanosecond presentation timestamps before successful
`eglSwapBuffers`. Only after rendering finishes does the video codec receive one
`signalEndOfInputStream()`. Audio receives a zero-size input buffer flagged
`BUFFER_FLAG_END_OF_STREAM`. Output EOS is checked independently of buffer size,
so zero-size EOS and payload-bearing EOS are handled. Successful muxer stop is
allowed only after the drain task finishes and both expected output EOS flags
are observed. Failure cleanup does not report a partial file as successful.

## Validation

`verify_render_contract.py`: 52 checks passed.
`verify_shader_parity.py`: 55 checks passed.
`git diff --check`: passed.

Alignment and muxer-finalization regression tests were added. The pure Kotlin
alignment tests are included in `toolchain/verify/run_logic_tests.sh`.
Gradle/JVM/NDK builds and device tests were **not run**: this sandbox has no Java
runtime/Android SDK/NDK. Run in an Android development environment:

```sh
./gradlew testDebugUnitTest assembleDebug
```

On the affected device test: landscape/portrait 1080p, custom unaligned sizes,
720p/4K, with/without audio, very short timelines, pause/resume, cancel during
rendering, repeated exports, and preview active during export. Check duration,
first keyframe, frame count, and A/V sync in the resulting MP4.

Capture a failing attempt (the first exception, not only the final UI message):

```sh
adb logcat -v threadtime AsyncFramePipeline:V ExportDiagnostics:V \
  MuxerCoordinator:V EglCore:V NativeRenderBridge:V NativeRenderBridgeJNI:V \
  MediaCodec:V ACodec:V CCodec:V '*:S'
```

New diagnostics include codec name, full requested configure format, relaxed
attempt state, device model/API level, codec diagnostic/recoverable/transient
fields, render thread, frame index, submitted-frame count, and EGL error code.

## PR CI follow-up

The first GitHub Actions run for PR #59 passed Android Lint but failed unit tests
because two existing planner tests still asserted the previous unaligned coded
sizes (1920x1080 and 854x480). Those assertions now expect 1920x1088 and 864x480,
including portrait coverage and alignment assertions for every tested resolution.
Requested/UI dimensions remain unchanged. A new CI run is required to confirm
the corrected suite and complete APK/AAB builds.

## Black-frame repair (export failed verification: every sampled frame is black)

Symptom: the export ran to the end, the MP4 was muxed and finalised, and only the
acceptance gate rejected it -
`Exported video failed verification: Every sampled frame of the exported video is
black (p99 luma <= 16, stddev <= 2.0)`. Every other statistic was correct: right
duration, right size, no rotation, key frames present, strictly increasing
timestamps, samples with real payload. That combination is the signature of a
compositor that produced no pixels, not of a broken container.

Two silent paths could produce exactly that (export-pipeline-audit R-1/R-2):

- **The native compositor's targets were never validated.**
  `NextGenGpuCompositionEngine::ensureTargets` allocated three ping-pong
  textures/FBOs at the export size (1088x1920 for 1080p portrait: 8.4 MB each)
  and, when a framebuffer came back incomplete or the allocation ran out of
  memory, logged `FBO %d incomplete` and **continued**. Drawing into an
  incomplete framebuffer is a no-op, so the texture kept its initial zero
  content and `endOffscreen()` returned a texture id that was never written -
  indistinguishable from a real picture by its id alone.
- **Kotlin trusted any texture id.**
  `currentInputTex = endOffscreen(...)`; when that returned 0 the code fell back
  to `fboA.getTextureId()` - a buffer that had only been *cleared*. Either way
  the encoder received a black frame and the pipeline reported success.

What changed:

- `ensureTargets` now checks the allocation error and the framebuffer status of
  every target, releases them and returns `false` on failure. `beginOffscreen()`
  returns that result across JNI (`jboolean`), `renderFrame` refuses to draw into
  a dead target, and `endOffscreen()` returns 0 instead of an empty texture.
- `GpuCompositionRenderer.render()` composes through one `composeOnce()` helper
  and, when the native path yields no picture, re-draws the same layers with the
  Kotlin compositor and keeps using it for the rest of that render surface. It
  returns a `RenderReport` (`layersDrawn`, `compositedTexture`,
  `usedNativeCompositor`, `failure`, `glErrors`); `drewNothing` is true only when
  the frame carries no picture at all.
- The export consumes that report: frames that drew nothing are counted, not
  probed; a clip that never drew anything is refused instead of muxed; and the
  back-buffer probes abort an attempt early via `ExportBlackFrameGuard`
  (4 readable black probes spanning at least half the clip). An unavailable
  `glReadPixels` still proves nothing and cannot fail an export.
- After muxing, the pipeline decodes sampled frames of its own output
  (`ExportValidator.checkDecodedFrames`). An all-black file is now a *retryable*
  attempt failure tagged `ExportFailure.ALL_FRAMES_BLACK`, so the attempt ladder
  runs again with the Kotlin compositor and the next encoder plan instead of
  handing the user a dead end. One retry, then the failure is reported with the
  per-frame p99/stddev evidence in Logcat.

A retry drops **both** GPU paths that can hand back an empty picture: the native
compositor and the ES3 shader transition engine. The second one matters because
`renderShaderTransition` output *replaces* the whole main picture
(`main2dTexRaw = if (shaderTransitionTexId > 0) ... else processMainVideoTo2D(...)`),
so a blank pass is a black frame that no layer check can see. Note that the ES3
`FADE` legitimately dips **through** black at 50% (see `POST_PATCH_VERIFICATION.md`
§5.1) - that is the shader being correct, not an engine failure, which is why the
instrumented case now samples 25%/75% for the never-blank contract and pins the 50%
dip separately. `ShaderTransitionCompositor.render` additionally drains pending GL
errors before the pass and treats an error raised by the pass itself as a failure,
so the caller takes the legacy single-texture path instead of blanking the frame.

`ExportBlackFrameGuard` is pure Kotlin and unit-tested
(`ExportBlackFrameGuardTest`, also compiled by `toolchain/verify/run_logic_tests.sh`).
`toolchain/verify/verify_black_frame_recovery.py` is the drift guard for the
Kotlin/C++ contract above (58 checks) for sandboxes without Gradle/NDK.
