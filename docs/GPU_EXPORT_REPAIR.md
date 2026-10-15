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
