# Export stalled at 5 % / "0 of 18484 frames" — audit and fix (2026-10-16)

Device report, real phone (not the emulator reference runs):

* A ~10 minute project (multi-track: overlays, text, filters, effects, transitions, chroma key,
  stickers, shapes, animation) → **Export** opened the progress panel and it never moved:
  ring ≈ 5 %, `Hardware GPU pipeline: encoded 0 / 18484 frames (0% · 0.0 fps)`,
  `Frames 0 / 18484`, `Remaining: Finalizing...` — for **twenty minutes**.
* No error was ever shown, so the user could not tell whether the export was slow, stuck, or dead.

18484 frames at 30 fps is 616 s ≈ 10.3 minutes of timeline, so the panel was reporting a real project
of the reported length with **zero** frames encoded.

---

## 1. What the numbers in the screenshot mean

| Reading | Source |
| --- | --- |
| `5 %` | `ExportProgressMapping.encodingFraction(0, total)` = `0.05f` — the ring's **floor**, i.e. "no frame has been reported yet" |
| `encoded 0 / 18484 frames (0% · 0.0 fps)` | `done = max(metrics.encodedFrames, metrics.gpuFrames)` with both counters at 0 |
| `Remaining: Finalizing...` | `ExportScreen.kt` shows that string whenever `estimatedRemainingSec <= 0`, which is every state in which no ETA exists (including the whole pre-roll) |
| `Resolution Full HD (1080p)` | the encoder plan was resolved and the pipeline had started |

So the panel's whole vocabulary was *frame-count*. Everything that happens **before the first frame
reaches the encoder** — encoder negotiation, EGL/GPU bring-up, the PCM mix of the timeline audio, the
audio encoder, the MP4 muxer, the tracked-effect prewarm — was indistinguishable from "rendering,
just slow", and every one of those stages rendered as exactly 5 %.

## 2. Why nothing ever reported a failure

`AsyncFramePipelineEngine` waited for the render loop with

```kotlin
while (!renderCompleteLatch.await(1, TimeUnit.SECONDS)) {
  failure.get()?.let { throw wrap("Pipeline worker failed", it) }
  val idleSec = (System.nanoTime() - lastOutputNs.get()) / 1_000_000_000L
  if (idleSec > 60 && (swapPending.get() || metrics.gpuFrames.get() > metrics.encodedFrames.get())) {
    throw ExportPipelineException("The video encoder stopped producing output ...")
  }
}
```

The `||` is the defect: it requires **at least one submitted frame** (`swapPending` is only set around
a swap, and `gpuFrames > encodedFrames` is false while both are 0). An export that never reaches its
first frame satisfies neither side, so the loop is genuinely infinite and silent.

That state is reachable from several places, and each of them is an unbounded wait on this device:

| # | Where | Why it can hang forever |
| --- | --- | --- |
| 1 | `openVideoEncoder` → `MediaCodec.configure()`, `createInputSurface()` | vendor call, no timeout of its own; a driver lock held by another encoder session or a dead codec service never returns |
| 2 | `MediaMuxer(...)` / `videoEncoder.start()` / AAC `createEncoderByType().start()` | same class of platform call |
| 3 | `audioProcessor.mixTimelineAudio(...)` | decoded every source **in full** inside a loop with no deadline, allocated `FloatArray(total * ch)` **and** `ShortArray(total * ch)` (a 10 min timeline: ≈230 MB + 115 MB, plus a decoded buffer per track) and reported **no progress at all** while it ran; the export thread was parked on a 3-minute latch — and that latch's timeout is the only reason this stage could ever fail |
| 4 | pre-roll image/sticker loading (`decodeSampledBitmap` → `ContentResolver.openInputStream`) | cloud-backed `content://` media (Google Photos/Drive) blocks with no timeout, on the export's setup path |
| 5 | the render loop's still-frame fallback `MediaMetadataRetriever.getFrameAtTime(OPTION_CLOSEST)` | no timeout; runs on the GL thread that must keep the encoder fed |
| 6 | `glHandler` init task / `EglCore` creation | waits were bounded, but nothing reported *which* stage was waiting |
| 7 | `AudioExportProcessor.processAudioTrack` / `encodeAndMuxPcm` | `while (!isMuxerStarted()) Thread.sleep(..)` — an unbounded spin on the muxer the *video* side owns |

None of these produced a log line, an error, or a state change. That is the "silent stall" of the
report, and (3) plus (4) are the two candidates that match a **project with many layers and images
sitting at 0 frames** precisely: both run before frame 0.

## 3. What changed

### 3.1 Every stage now has a name, a place in the bar, and a budget

* `ExportProgressModel.kt` (new, pure Kotlin): `ExportPhase` = `DEVICE_CHECK`, `PREPARING`,
  `LOADING_ASSETS`, `OPENING_ENCODER`, `INITIALISING_GPU`, `MIXING_AUDIO`, `OPENING_AUDIO_ENCODER`,
  `OPENING_MUXER`, `WARMING_TRACKERS`, `RENDERING`, `FINALISING`, `VERIFYING` — each with a
  user-facing label, a one-line explanation, and its own slice of the **0.5 % → 5 %** pre-roll bar.
  A stage that can measure its own progress reports it (the PCM mix reports one step per mixed chunk),
  so the ring moves during the pre-roll instead of sitting on the floor.
* The frame range is **bit-for-bit** the previous formula (`0.05 + frames/total * 0.90`, capped at
  0.905), and `ExportProgressMapping` still exists as the legacy entry point, so no user-visible
  number moved: `ExportPerformanceFixTest` and `RenderExportPipelineAuditTest` pin it.
* `FINALISING` (drain + moov) and `VERIFYING` have their own brackets above the frame range, so the
  ring never walks backwards.
* The status line names the stage (`Mixing audio (37%)`, `Opening the video encoder...`) and only the
  frame stage says `Hardware GPU pipeline: encoded N / M frames (X% · Y fps)`. `ExportScreen` shows the
  stage next to the ring, and `Remaining:` shows the stage name instead of the meaningless
  `Finalizing...` whenever no ETA exists yet.

### 3.2 Silent waits became named failures

* `ExportStallWatchdog.kt` (new, pure Kotlin) judges one attempt per second:
  * a **frame stage** is judged by its frames — no frame within `FIRST_FRAME_DEADLINE_MS` (150 s) of
    entering `RENDERING` is a stall (this is the reported state); once frames flow, only
    "no frame for 75 s" or "encoder produced nothing for 60 s while frames are queued" are stalls, so
    a slow 10-minute export is never killed;
  * a **pre-roll stage** is judged by its own budget — a hard wall clock for stages with no measurable
    progress (`OPENING_ENCODER` 90 s, `INITIALISING_GPU` 60 s, …) and a no-progress limit for stages
    that do report progress (`MIXING_AUDIO`: 30 min hard / 60 s without a completed chunk);
  * a user **pause is never a stall**;
  * a stall is never retryable (re-running the identical attempt would hang identically).
* `AsyncFramePipelineEngine` runs that watchdog on a daemon thread per attempt. On a verdict it logs
  `EXPORT_STALL kind=… phase=… waitedMs=… budget=…`, records the failure, stops the video encoder
  (the documented way to unblock a producer parked in `eglSwapBuffers`), and sets the stop flag so the
  export thread throws the watchdog's message. The export therefore **fails with a reason** instead of
  waiting forever.
* Unbounded vendor calls are bounded: the video encoder open, the AAC encoder start, `MediaMuxer(...)`
  and `videoEncoder.start()` run through `runBoundedStage(...)`, which waits with the phase budget and
  fails with a message naming the step. The two `while (!isMuxerStarted())` spins in
  `AudioExportProcessor` now have a 60 s deadline and report why they gave up.
* The still-frame fallback got a policy (`ExportFallbackBudget`, new, pure): every
  `MediaMetadataRetriever` call has a 6 s deadline, and the first call that overruns it **retires that
  clip** for the rest of the attempt (a call that hung once will hang again; asking again would only
  repeat the stall on the thread that feeds the encoder). A slow-but-answering retriever keeps working
  exactly as before.
* Pre-roll image/sticker loading is bounded per image (20 s) and reports progress; a timed-out image is
  skipped with a log line, like the load failures that loop already tolerated.

### 3.3 The PCM mix no longer scales with the project length

`AudioMixPlan.kt` + `TimelineAudioMixer.kt` (new, pure Kotlin) replace the whole-timeline mix:

* one **5 s chunk** at a time, into a reused scratch buffer, straight into the output PCM;
* forward/unity-speed tracks are decoded **window by window** (bounded decode window + a 250 ms tail
  margin), instead of decoding every source in full;
* the sources that need whole-buffer processing (reversed, speed-stretched, effect chain) keep the
  previous single-window decode, so the sound is unchanged;
* peak audio memory drops from "timeline × 2 buffers + every decoded track" (several hundred MB on the
  reported project) to the mixed PCM plus one window per track, and the mix reports progress per chunk
  (which is also what makes the watchdog able to tell "still mixing" from "stuck mixing");
* a window that cannot be decoded is still a **fatal** export error (a silent hole in the user's audio
  is not an acceptable success), while a window that lies past the end of a shorter source is
  silence — as before.

### 3.4 Diagnostics

`ExportDiagnostics` gained one line per phase change (`EXPORT_PHASE …`), a stall line
(`EXPORT_STALL …`) and a 5 s progress heartbeat (`EXPORT_HEARTBEAT phase=… frames=… fps=…`). The
device log of a stalled export now ends with the line that says exactly where it stopped — which is
what was missing from the report.

## 4. How this is verified

* **Unit tests (pure JVM, run by `./gradlew test`)**
  * `ExportProgressModelTest` — the frame formula is unchanged; every pre-roll stage has a slice; the
    ring never walks backwards; a long mix moves the bar; the reported status line is reproduced
    verbatim.
  * `ExportStallWatchdogTest` — the reported state (RENDERING, 0 frames, 20 min) produces
    `NO_FIRST_FRAME`; a 25-minute export that *is* progressing is never failed; a stalled mix, a vendor
    hang in the encoder open, an encoder that stops consuming, a render loop parked in
    `eglSwapBuffers` and a decode-stuck frame loop each produce a named verdict; a pause never does.
  * `AudioMixPlanTest` / `TimelineAudioMixerTest` — chunks tile the timeline exactly once; a streaming
    window always starts at or before the sample its chunk reads (the off-by-one that would have
    shifted the audio); the chunked mix is **bit identical** to the old whole-buffer arithmetic at
    44.1 kHz and 48 kHz (the test applies the mixer's own transfer curve to its reference and then
    requires equality on every sample, not "within 1 LSB"); silence vs. decode failure vs.
    end-of-source are distinguished; cancel is immediate.
  * `ExportFallbackBudgetTest` — a hung retriever is retired for that clip only; a slow one keeps
    working.
* **Instrumented tests (device, `:app:connectedDebugAndroidTest`, run by the post-patch workflow on an
  emulator with a real GLES driver)**
  * `ExportProgressStallInstrumentedTest.a01` exports a multi-layer project (overlay video, overlay
    image, two text layers, sticker, effect, chroma key, keyframes, filter + grade, transition, audio
    lane) through the production `AsyncFramePipelineEngine.export(...)` while a sampler records what
    the panel would show, then asserts: the frame counter **moved** (submitted > 0, encoded > 0), the
    ring left the 5 % floor, at least three stages were observable including a pre-roll stage, the
    watchdog never fired, and the output validates as a decodable MP4.
  * `a02` parks the pipeline inside `RENDERING` with a 2 s budget and asserts the export **fails within
    seconds** with a message naming the stage, that the failure is not offered as a retry, and that no
    partial file is left behind. A silent stall cannot pass this test.
* `toolchain/verify/run_logic_tests.sh` now compiles and runs the new pure files/tests on a plain JVM.

## 5. Known limits / still open

* The instrumented proof uses a short multi-layer project (a full 10-minute 1080p export does not fit
  an emulator CI budget). The long-project claims that *are* covered are the ones that scale:
  bounded audio memory, chunked mix parity, phase progress, and time/ budget-based stall detection.
* The 20 s image deadline and the 6 s still-frame deadline are policy choices; both are documented in
  code and both only replace an *unbounded* wait.
* `runBoundedStage` abandons a worker that is stuck inside a vendor call. Stopping the export and
  reporting the reason is preferred over an infinite wait, but that thread can hold one codec instance
  until the process exits — which is why such a failure is never retried automatically.
* If the stall watchdog fires, the encoder is stopped to unblock the producer; that is the same abort
  path the previous release used for a stalled swap, now reachable also when no frame exists yet.

## 6. What actually running the tests found

The five new unit tests had never been *executed* - they existed as files whose transitive closure was
outside the reach of the sandbox harness, so nothing had run their assertions. Wiring them up (see
below) surfaced three real defects, all fixed in this patch:

1. **`AudioMixPlan.sampleIndex` divided by an unguarded target rate.** With `targetSampleRate <= 0` the
   `coerceAtLeast(1)` fallback turned a one-frame offset into an index ~sampleRate times too large
   (`sampleIndex(10, 48_000, 0)` returned `480_000`). The mixer's bounds check would have read that as
   silence instead of failing loudly. It now returns 0 for an unusable rate, which is the only honest
   answer - and the test that pins it (`sampleIndexIsTheFormulaTheWholeBufferMixerUsed`) is the one that
   caught it.
2. **The chunked mixer's parity reference was wrong, and the parity claim was therefore weaker than
   advertised.** The reference in `aForwardUnitySpeedTrackMixesExactlyLikeTheWholeBufferMixer` compared
   the mixer's output against *raw* source samples, but the mixer writes every sample through
   `AudioMixMath.softClip` (the old `softClipSample`, which scales by 32767/32768). So the assertion
   had to be softened to "within 1 LSB / ≥99 % identical" to pass at all. With the curve applied to the
   reference, the same test now demands **bit identity on every sample** - a strictly stronger guarantee
   about what the export sounds like versus what the preview played.
3. **"No gap at a chunk boundary" was tested with the wrong detector.** `everyFrameOfALongTimelineIsWrittenExactlyOnce`
   asserted `value != 0` per frame. That is not a gap detector: the transfer curve legitimately maps a
   source sample of -1 to 0, so the test failed on a *correct* mix (frame 1999, where the synthetic
   source crosses zero) and would have passed on some real gaps. It now compares every frame against the
   expected sample.

Two further defects were found by reading the same code against its callers, and are fixed here too:

4. `ExportProgressStallInstrumentedTest` used `ChromaKeyResolver.BACKGROUND_COLOR`, which does not exist
   (the constant is `BACKGROUND_SOLID`, `"SolidColor"`). That test could never have compiled.
5. The frame-rate readout on the export panel was formatted with the *default locale*, so the very line
   the reporter reads off the phone renders as `3,3 fps` on a German device and with non-Latin digits
   where those are used - unreadable in a screen recording of a stall. It (and the size/diagnostic lines
   around it) is now pinned to `Locale.US`, with a test that switches the default locale to `de-DE`.

For (1)-(3) the harness had to reach a closure that ends in `android.graphics.RectF` / `Matrix`:
`AudioMixMath` -> `AutoDucking` -> `PreviewMixPolicy` -> `KeyframeInterpolator` -> `OverlayTrackCodec` ->
`MotionTrackingModels`. `toolchain/verify/kotlin_logic/AndroidGraphicsStubs.kt` supplies shape-only
stand-ins for exactly those two framework types, so `AudioMixPlanTest`, `TimelineAudioMixerTest` and
`AudioExportWindowTest` now run on a plain JVM (`run_logic_tests.sh`: 194 tests, 0 failures). The stubs
perform no matrix maths - nothing those tests assert depends on them - and the file says so.
