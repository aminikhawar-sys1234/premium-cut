# Export Pipeline and Rendering Audit — AH Video Studio

- **Date:** 2026-10-09
- **Branch:** `arena/e36db9de-premium-cut` (HEAD `fa3fb05` at the time of reading)
- **Type:** Read-only audit. No code was changed for this report. No build, unit test, instrumented test, export, or benchmark was run (see [Limitations](#9-limitations-and-what-was-not-verified)).

## Labels used

- **Verified (code)** — confirmed by reading the cited lines or by a grep with a stated count.
- **Unverified** — inferred from code reading, but not reproduced, measured, or executed.

Severity:

- **High** — can produce silently wrong output, a hang, or a memory failure on realistic projects.
- **Medium** — correctness or quality gap, or a significant cost, with a plausible trigger.
- **Low** — maintainability, dead code, or minor quality issue.

File paths are relative to `app/src/main/java/com/example/` unless they start with `app/src/main/cpp/`.

---

## 1. Scope and method

The audit covers the export path from the timeline to the file on disk:

1. Entry: `ui/screens/EditorScreen.kt` → `ui/StudioViewModel.kt` `startExport` → `engine/export/ProfessionalExportEngine.kt`.
2. Audio: `engine/export/AudioExportProcessor.kt` (decode, window, mix, soft-clip, AAC encode).
3. Video: `engine/export/AsyncFramePipelineEngine.kt` (EGL/render loop, encoder setup, muxer, teardown), `engine/composition/gpu/HardwareVideoTextureSource.kt` (per-clip decoder), `engine/composition/gpu/GpuCompositionRenderer.kt` (Kotlin GL compositor), `app/src/main/cpp/native_bridge.cpp` and `app/src/main/cpp/render/NextGenGpuCompositionEngine.cpp` (native compositor).
4. Encoder planning: `ExportEncoderPlanner.kt`, `ExportCodecFormat.kt`, `controller/DecoderManager.kt`.
5. Validation and progress/cancel: `ExportContentRules.kt`, `ProfessionalExportEngine.kt`, `StudioViewModel.kt`, `ui/screens/ExportScreen.kt`.

**Not audited in depth** (see §9): `app/src/main/cpp/render/GpuRenderEngine.cpp` (445 lines, built by CMake), the internals of `MuxerCoordinator`, the probe internals of the validator, and the audio DSP in `AdvancedAudioProcessor`.

---

## 2. Summary

| ID | Severity | Area | One-line summary | Status |
|---|---|---|---|---|
| A-1 | High | Audio | A failed audio decode is replaced with silence; the export still reports success. | Verified (code) |
| A-2 | High | Audio | The audio decode loop has no deadline. Cancel is checked only between tracks. | Verified (code); hang unverified |
| A-3 | High | Audio | The whole timeline is held in a `FloatArray` plus a `ShortArray`. This is about 345 MB at 10 min and about 1.04 GB at 30 min. | Verified (arithmetic); device heap unverified |
| V-1 | High | Video decode | A failed decoder init is retried on every frame. | Verified (code); cost unmeasured |
| V-2 | High | Video decode | On a decode deadline or frame-arrival timeout, the previous picture is returned silently, which duplicates frames. | Verified (code); output effect unverified |
| A-4 | Medium | Audio | Speed and resampling use nearest-sample indexing. Speed also changes pitch. | Verified (code); preview parity unverified |
| A-5 | Medium | Audio | Clip volume keyframes are carried in the descriptor but never applied in the mix. | Verified (grep) |
| R-1 | Medium | Render | `GpuCompositionRenderer` makes no `glGetError` calls. GL errors are invisible. | Verified (grep) |
| R-2 | Medium | Render | Native-path fallback is a silent black frame. `fboA` is cleared and bound every frame even when the native path is active. | Verified (code); trigger unverified |
| R-3 | Medium | Render | Export waits synchronously for face, pose, and AR tracking every frame, with a 20 s timeout. | Verified (code); cost unmeasured |
| P-1 | Medium | Lifecycle | Teardown continues after a 10 s wait even if the render thread may still be running. | Verified (code); race unverified |
| A-6 | Low | Audio | The decoder output format is not checked (16-bit PCM is assumed). | Verified (grep) |
| A-7 | Low | Audio | Soft-clip only. There is no limiter. | Verified (code) |
| A-8 | Low | Audio | Audibility logic is duplicated. `sampleRate` and `channelCount` are shared mutable fields. | Verified (code) |
| R-4 | Low | Render | Text texture cache keys are XOR-combined, which can collide. Animated text re-rasterises every 33 ms. | Verified (code); collision unverified |
| R-5 | Low | Encode | `KEY_I_FRAME_INTERVAL = 1` produces an all-intra stream, which increases file size. | Verified (code); trade-off unmeasured |
| R-6 | Low | Encode | `KEY_OPERATING_RATE` hint = 120 at 1080p. This is the leading hypothesis for the paused task A. | Hypothesis (see task A) |
| P-2 | Low | Lifecycle | Software fallback is scoped per pipeline, and it never re-promotes to hardware. | Verified (code) |
| Q-1 | Low | Validation | Validator tolerances are loose, and audio content is never checked. | Verified (grep) |
| D-1 | Low | Dead code | Several export classes and functions have no production callers. | Verified (grep) |
| D-2 | Low | Docs | `EditorScreen.kt` comment says "Media3 Transformer". The code path does not use Media3. | Verified (code) |

Overall: the GPU zero-copy video path and the cancel/pause wiring look structurally sound. The main risks are in the audio path (silent failure and unbounded work) and in video decode failure handling (silent duplicate frames and per-frame retries).

---

## 3. Audio path

### A-1 (High) — Failed audio decode becomes silence, and the export reports success

- `AudioExportProcessor.kt:587–588`: `decodePcmFromMedia` catches `Exception`, logs with `Log.e`, and returns `null`.
- `AudioExportProcessor.kt:429` and `:431`: `?: silentPcmForTrack(track)`. The track is replaced with silence.
- `silentPcmForTrack` (`:743`) returns a real buffer of zeros. The track is still encoded as AAC, so the output has an audio track.
- `ExportContentRules.kt` checks audio track presence and duration (`:259–263`). It has no audio-energy check (see Q-1). So a silent export passes validation.

**Impact:** A user can receive a "successful" export whose audio is missing for the whole clip. The only evidence is in logcat. This violates the project rule "do not report success on failed export".

**Recommended fix:** Make an undecodable audio track an explicit export failure, or at minimum collect per-track warnings and show them in the result. Decide the policy with the product owner. Do not silently fall back to zeros.

### A-2 (High) — Audio decode loop has no deadline, and cancel is coarse

- `AudioExportProcessor.kt:544`: `while (!isEos && !reachedWindowEnd)`. The loop runs until EOS or the window end. Inside it, `dequeueInputBuffer(TIMEOUT_US)` and the output dequeue both use a timeout, but there is no overall deadline and no iteration cap.
- Cancellation is checked only once per track, at `:287` (`if (isCancelled()) return@withContext ShortArray(0)`).
- The video side has deadlines (`HardwareVideoTextureSource.kt:41–45`: 12 s, 4 s first picture, 1.5 s frame arrival). The audio side has none.

**Impact:** A decoder that never produces output or EOS for a bad or unusual file could stall the export indefinitely. Cancel would then wait for that decode to return. The hang is inferred from the code, not reproduced.

**Recommended fix:** Add a wall-clock deadline and a no-progress counter to the decode loop, and check `isCancelled()` inside the loop. Surface a timeout as an export error (see A-1).

### A-3 (High) — Whole-timeline PCM buffers scale with project length

- `AudioExportProcessor.kt:283`: `FloatArray(totalFrames * targetChannelCount)`.
- `:292`: `ShortArray(mixedPcm.size)` for the final output. Both arrays are alive during the soft-clip loop at `:294`.
- `:318` and `:326` repeat the same pattern in `renderMixedAudioToMuxer`, which has no callers (see D-1).
- `pcmCache` (`:82`, stored at `:466`) keeps every decoded track alive until it is cleared at `:85`. Each decoded track is a `ShortArray`.

Corrected arithmetic (48 kHz, stereo, 2 channels):

| Timeline | Float mix buffer | Short output buffer | Peak for these two buffers |
|---|---|---|---|
| 10 min | 230 MB | 115 MB | about 345 MB |
| 30 min | 691 MB | 346 MB | about 1.04 GB |

The earlier working estimate of "173 MB for 10 min" was wrong. It undercounted by about 2×. These numbers do not include the decoded-track cache or the encoder buffers.

The app sets `android:largeHeap="true"` (`app/src/main/AndroidManifest.xml:14`). This raises the heap limit but does not remove the risk. The device heap limit has not been measured.

**Impact:** Long timelines may hit `OutOfMemoryError` during audio mix, and the export would then fail. Peak memory has not been measured on a device.

**Recommended fix:** Stream the mix in fixed-size chunks through the AAC encoder, instead of materialising the whole timeline. Clear `pcmCache` after each track is mixed. This is a structural change to the audio mix, so it should be scoped as its own piece of work.

### A-4 (Medium) — Speed and resampling use nearest-sample indexing

- `AudioExportProcessor.kt:614`: `rateRatio = (srcRate / targetRate) * speed`.
- `:622`: `srcIndex = (relativeFrame * rateRatio).toInt()`. This is nearest-sample, with no interpolation and no anti-alias filter.
- The `speed` is clamped to 0.25–4× (`:613`), and because it is applied by resampling, a speed change also changes pitch.
- `MasterAudioRenderer` and `WsolaTimeStretcher` exist under `app/src/main/java/com/ahstudio/audio/master/` and are referenced from `MasterAudioRenderer.kt`. Whether the editor's preview uses them, and so whether preview and export differ in pitch, is **unverified**.

**Impact:** Resampling 44.1 kHz sources to 48 kHz will alias. Speed-changed clips will sound pitched up or down in export.

**Recommended fix:** Use linear or better interpolation for resampling. For speed changes, use the same time-stretch as preview, if preview uses one. Confirm preview parity first.

### A-5 (Medium) — Clip volume keyframes are not applied in export

- `keyframes` is carried in `AudioTrackDescriptor` (`:50`) and copied from the clip at `:358`, `:379`, and `:400`.
- A grep for `keyframes` in `AudioExportProcessor.kt` finds only those four plumbing lines. `mixTrackIntoBuffer` (`:597`) does not read them.

**Impact:** Automated volume curves that the user set on a clip would be ignored in the exported file. Whether they are applied in preview is **unverified**.

**Recommended fix:** Evaluate the keyframe volume per sample (or per block) inside `mixTrackIntoBuffer`, using the same interpolation as preview.

### A-6 (Low) — Decoder output format is not checked

- A grep for `getOutputFormat` and `KEY_PCM_ENCODING` in `AudioExportProcessor.kt` finds no matches.
- `decodePcmFromMedia` assumes 16-bit PCM and the source channel count. A decoder that changes its output format (`INFO_OUTPUT_FORMAT_CHANGED`) is not handled.

**Impact:** AAC decoders normally output 16-bit PCM, so this is unlikely to trigger for common sources. Some float-PCM decoders could produce wrong sample values or length. **Unverified** on device.

**Recommended fix:** Read `getOutputFormat()` after `INFO_OUTPUT_FORMAT_CHANGED` and convert or reject unsupported encodings.

### A-7 (Low) — Soft-clip only

- `AudioExportProcessor.kt:814–825`: `softClipSample` applies a knee above 0.75 and hard-clips at ±1. It is not a limiter. Many loud tracks summed together will distort.

**Recommended fix:** Optional. Normalise the mix or add a simple look-ahead limiter if loudness is a product requirement.

### A-8 (Low) — Duplicated logic and shared mutable state

- Audibility rules are duplicated in `hasActiveAudio` (`:257–261`) and `collectAudioTracks` (`:338–340`).
- `sampleRate` and `channelCount` are mutable fields on the processor (`:78–79`). They are shared across calls.

**Recommended fix:** Derive audibility in one place. Pass the output format as parameters.

### Verified OK in the audio path

- Reversed audio is handled. The decoded buffer is reversed after decode (`:435–446`), and `audioSourceWindow` anchors the reversed window to the source end (`audioSourceWindow`, after `:830`).
- Each track decodes only its source window, plus a 250 ms margin (`audioSourceWindow`, `AUDIO_WINDOW_MARGIN_MS`). This bounds per-track decode size.
- Audio fades are applied per sample inside the mix.

---

## 4. Video decode path

### V-1 (High) — A failed decoder init is retried on every frame

- `AsyncFramePipelineEngine.kt:709`: `getOrCreateDecoder` is called from the render loop.
- `:728`: `undecodableClipIds` is declared.
- `:743`: `if (clip.id in undecodableClipIds) return null`.
- `:745`: `val dec = getOrCreateDecoder(clip, activeIds) ?: return null`. When init fails, this `return null` exits before the clip ID is added to `undecodableClipIds`.
- `:761`: `undecodableClipIds.add(clip.id)` runs only after the try loop has exhausted its retries on a decode error. An init failure never reaches it.

So a clip whose decoder cannot be created is re-initialised on every frame. Each attempt includes `MediaExtractor` and `MediaMetadataHelper.extractMetadata` (`HardwareVideoTextureSource.kt:157`) and a codec create.

**Impact:** This is a plausible contributor to the "extremely slow export" in task A, alongside the `fetchFallbackBitmap` path. The cost per attempt has not been measured, so the contribution is **unverified**.

**Recommended fix:** Record the clip in `undecodableClipIds` on an init failure too, so the fallback decision is made once per clip. Keep the still-frame fallback and log the decision.

### V-2 (High) — Previous picture is returned silently on timeout

- `HardwareVideoTextureSource.kt:315–321`: when the decode deadline passes, the loop logs a warning and `break`s. It throws only when there has been no output at all (`noPictureYet && outputsDequeued == 0L`).
- `:390–397`: if `rendered` is true but `awaitFrameArrival` fails, the function logs a warning and still returns `hasPicture`.
- `hasPicture` is not reset on these paths. The function can therefore return `true` and the caller's texture still holds the picture from an earlier frame.

**Impact:** The encoder can receive a duplicate of the previous frame with no error and no failed export. Repeated frames would make motion look stuttered. The validator's frame-count check (`ExportContentRules.kt:304`) fails only below 50% of expected frames, so it would not catch this. The effect on output is **unverified**.

**Recommended fix:** Return an explicit result (`Picture`, `Stale`, `Error`) from `decodeFrame`. Treat `Stale` as a frame error, or count and report it in the export result. Do not encode a stale frame silently.

### Verified OK in the video decode path

- Decode deadlines and first-picture deadlines exist (`:41–45`).
- Clip-level software fallback is handled by the still-frame path (`AsyncFramePipelineEngine.kt:745–761`).

---

## 5. Render path

### R-1 (Medium) — GL errors are not checked in the compositor

- A grep for `glGetError` in `GpuCompositionRenderer.kt` returns 0 matches. Only `GlShaderUtil.kt` calls `checkGlError`.
- The EGL swap error is now reported (commit `fa3fb05`, in `AsyncFramePipelineEngine`). GL draw and framebuffer errors are not.

**Impact:** An out-of-memory or invalid-framebuffer error during a draw call is invisible. The encoder receives a black or garbage frame, and the export reports success unless the post-export validator catches it. The validator has black-frame thresholds (`ExportContentRules.kt:50–51`), but how it samples frames was not verified.

**Recommended fix:** Add a debug-gated `checkGlError` after each render stage, and an optional strict mode that fails the export on a GL error. Do not add per-draw checks in release builds without measuring the cost.

### R-2 (Medium) — Native-path fallback is a silent black frame

- `GpuCompositionRenderer.kt:673–691`: `fboA` is set up, bound, and cleared on every frame. The native path then draws into its own offscreen target (`:686–687`), so the `fboA` clear is wasted work when native is active.
- `:694–695`: `currentInputTex = endOffscreen(...)`. If that returns 0, the code falls back to `fboA.getTextureId()`, which was cleared to black. No error is raised.

**Impact:** If the native compositor returns no texture on a frame, the output is a black frame with no error. The trigger is **unverified**. Native shader or init failure falls back only at init, and only with a log.

**Recommended fix:** When native is active, skip the `fboA` clear and bind. If `endOffscreen` returns 0, treat it as a frame error.

### R-3 (Medium) — Synchronous tracking on every export frame

- `GpuCompositionRenderer.kt:372`: `trackedBlocking = flipYForEncoder || deterministicMasks`. This is true on the export path. The same flag is passed as `blocking` to the face-warp, body-warp, AR, and subject-cutout stages (`:379`, `:390`, `:401`, `:417`, `:429`, `:491`, `:514`).
- `ClipFaceTracker.kt:35–38`: with `blocking`, `facesAt` waits for the exact result for that frame, up to `EXPORT_TIMEOUT_MS = 20_000L` (`:60`). On timeout it throws, which fails the export.

This is a deliberate choice for determinism, and it is documented in the code. Its cost is that export throughput is bounded by the tracker's per-frame latency for any project that uses face-warp, pose, or AR. The tracker latency has not been measured.

**Recommended fix:** Measure the tracker per frame before changing anything. Consider a pre-pass that computes tracking results once and caches them by timestamp, so the export render loop does not wait on the tracker.

### Verified OK in the render path

- The EGL swap error is reported with the EGL code (`fa3fb05`).
- The per-clip texture pipeline and the flip-Y blit (`blitToSurface`, `:1921`) are in place and unchanged by this audit.

---

## 6. Encode, lifecycle, and fallback

### R-5 (Low) — All-intra encoding

- `AsyncFramePipelineEngine.kt:351`: `KEY_I_FRAME_INTERVAL = 1`. Every frame is a keyframe. File size grows and the bitrate spent per frame goes down. The effect on encode speed is **unverified**.

**Recommended fix:** Measure the size and speed trade-off at 720p and 1080p before changing it. Any change should keep the quality target.

### R-6 (Low) — Operating-rate hint

- `ExportEncoderPlanner.kt:239`: `KEY_OPERATING_RATE` is set. `:245`: `KEY_PRIORITY = 0`.
- At 1080p/30 fps the hint is 120. This is the leading hypothesis for the paused task A. It is not confirmed. A change requires updating `ExportPerformanceFixTest`.

### P-1 (Medium) — Teardown may release resources while the render thread still runs

- `AsyncFramePipelineEngine.kt:1087–1092`: in `finally`, `stop.set(true)` is called, and then `renderCompleteLatch.await(10, SECONDS)` is awaited. Teardown then releases decoders and continues. The latch timeout is not checked, so release proceeds either way.

**Impact:** If the render thread is still inside a GL or MediaCodec call after 10 s, it could touch released resources. This would show as a crash or an error log. Whether it occurs is **unverified**.

**Recommended fix:** If the latch times out, log an error and skip the release of resources the render thread may still use, or wait longer. Do not release them under a running thread.

### P-2 (Low) — Fallback scope

- `AsyncFramePipelineEngine.kt:148` and `:204`: each pipeline creates its own `DecoderManager`. The software fallback flag is therefore per export, not process-wide. This corrects an earlier working lead.
- `DecoderManager.kt:439` and `:516`: after one hardware error, the rest of that export uses software decoders. There is no re-promotion to hardware.

**Recommended fix:** Scope the fallback per clip, not per export, if the hardware error is clip-specific. Measure the software-decode cost first.

---

## 7. Validation, progress, cancel, and UI

- **Validation** (`ExportContentRules.kt`): AV duration tolerance is `max(500 ms, 5%)` (`:204`, `:260`). Duration tolerance is `max(1 s, 5%)` (`:254`). The frame-count check fails only below 50% of expected (`:304`). The rules include black-frame checks (`:50–51`) and file-size and timestamp checks. No audio-energy constant appears in the file (grep of `const val`). **Q-1 (Low):** tolerances are loose, and silent audio passes.
- **Progress and cancel:** the progress loop checks `shouldCancel` (`ProfessionalExportEngine.kt:354`) and cancels the pipeline. The ViewModel passes `shouldCancel = videoExporter.isCancelRequested()` (`StudioViewModel.kt:2504`). The Export screen wires pause and resume (`ExportScreen.kt:490–492`) and cancel (`:512`). This is verified as wired. Behaviour was not exercised.
- **Stale comment (D-2, Low):** `EditorScreen.kt:1357` says "Media3 Transformer". The button at `:1369` calls `viewModel.startExport(config)`. Media3 is not used in this path.

---

## 8. Dead and unwired code (D-1, Low)

Verified by grep (no production callers outside the definition and tests):

- `engine/export/ChunkedExportEngine.kt`
- `engine/export/CodecCapabilityDetector.kt`
- `engine/export/Phase7ExportSupport.kt` (including its checkpoint store and retry policy). `Phase7ExportSupportTest.kt` exists.
- `AudioExportProcessor.processAudioTrack` (`:91`) and `renderMixedAudioToMuxer` (`:299`). Zero callers.
- `checkLowResolutionAssets` and `check4KSupport`. Zero callers.

`AhStudioAudioMasterEngine` → `AudioExportEngine` in the `ahstudio` package has callers in `IntegrationWiring` and `AudioDiagnostics`. Its wider wiring was **not** traced.

Removing dead code is out of scope for this audit. Recommend a separate cleanup change.

---

## 9. Limitations and what was not verified

- **No build, test, or export was run.** The sandbox has no JDK, Gradle, or Android SDK. The Gradle wrapper needs network access to services that are blocked. Everything here is from reading and grepping.
- **No device measurements.** Memory, timing, and output-quality claims are unverified. Heap limits, decode latency, tracker latency, and encode throughput were not measured.
- **Not read in depth:** `app/src/main/cpp/render/GpuRenderEngine.cpp` (445 lines, built by CMake), `MuxerCoordinator` internals, validator probe internals, `AdvancedAudioProcessor` DSP, and the preview path (used only to check parity claims).
- **Partly read:** `AudioExportProcessor.kt` lines 120–257 and 660–810; `HardwareVideoTextureSource.kt` outside the decode loop.
- **Preview parity** (A-4, A-5) is unverified. The audit did not trace how the preview applies speed or keyframe volume.
- **Prior audit:** `docs/export-render-audit.md` exists. Its claims were not re-checked against the current code.

---

## 10. Recommended order of work

1. **A-1** and **A-2**: make audio decode failure and hang explicit errors, with a deadline and cancel check in the decode loop. Small change, high value.
2. **V-2** and **V-1**: return an explicit picture result from `decodeFrame`, and record init failures per clip. Small to medium change.
3. **R-1** and **R-2**: GL error reporting in debug or strict mode, and no silent black frame on native fallback.
4. **Measure first**, then decide: the tracker cost (R-3), the all-intra trade-off (R-5), the operating-rate hint (R-6), and the decode and tracker timings behind task A.
5. **A-3**: streaming audio mix. Large change, scope as separate work.
6. **A-4** and **A-5**: confirm preview parity, then fix resampling, speed, and keyframe volume in export.
7. **P-1**, **P-2**, **Q-1**, **D-1**, **D-2**, and the low items: cleanup, after the above.

No fix in this list has been implemented or tested.
