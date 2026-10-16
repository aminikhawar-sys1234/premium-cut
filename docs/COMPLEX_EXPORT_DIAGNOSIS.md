# Complex-project export failure — diagnosis, evidence, and what the app now reports

Report: *"simple 1–30 s videos export fine, but projects with multiple tracks, overlays, stickers,
text, filters, colour adjustments, motion tracking, animations and chroma key fail with
'Export Failed'."*

This document records the diagnosis as it stands, the defects that were found and fixed, the device
measurements that back it, and the part that is still open. It is deliberately split into **proven**
and **not yet proven**, because the failure could not be reproduced on the reference device.

## 1. What the work covers

A regression suite now drives the *production* export path end to end —
`AsyncFramePipelineEngine.export(timeline, config, file)`, i.e. the same dimension resolver,
encoder planner, attempt ladder, GPU compositor and validator the app uses — for the exact shapes
the report names, and then measures the decoded MP4:

`app/src/androidTest/java/com/example/engine/export/ComplexProjectExportInstrumentedTest.kt`

| Test | Project shape |
| --- | --- |
| `a01_simpleSingleClipExportStillWorks` | the control: one clip, no other layer |
| `b01_complexProjectExportsAValidPortraitVideo` | 2 main clips + `FADE`, `GOLDEN_AUTUMN` filter, adjustments, motion/scale keyframes, motion blur, chroma key over a **background video**, rotation, 2 overlays (PIP `Screen` + PNG `Multiply`, keyframed), 3 text clips (stroke/shadow, animation, 3D mesh), 2 stickers (emoji `BOUNCE`, image + badge `PULSE`), 2 effect clips (`GLOW` keyframed, `CELEBRATE_CONFETTI`), 1 audio clip, project-level adjustments — portrait 720x1280 |
| `b02_complexProjectExportsAValidLandscapeVideo` | the same project, landscape 1920x1080 |
| `c01_manySimultaneousLayersStillExport` | 2 main + 3 overlay **video** lanes + 3 text + 3 stickers + 3 effects (`GLOW`, `VIGNETTE`, `CELEBRATE_FIREWORKS`) + `SLIDE_LEFT` |
| `d01_simpleProjectStillExportsAfterHeavyProjects` | the control again, after the heavy exports above |
| `e01_multipleAudioTracksAndDifferentFiltersPerClipStillExport` | two clips with **different filters** (`GOLDEN_AUTUMN`, `BLACK_AND_WHITE`) on the same saturated source + **two mixed audio tracks**; asserts the two looks reach the export as different pixels |

Every test asserts the export does not throw, the validator says `Verified` (size, orientation,
duration, A/V track, sampled frames), the MIME has an audio track, the frame is not black and the
animated layers actually change the picture. The suite is ordered (`NAME_ASCENDING`) so a
resource-lifetime bug cannot hide behind JUnit's default hash order.

## 2. What the reference run proves (device, emulator API 30, SwiftShader GLES)

Run [38881864302](https://github.com/aminikhawar-sys1234/premium-cut/actions/runs/38881864302) —
this is measured output, not an assertion that "it compiled":

```
>>> START a01_simpleSingleClipExportStillWorks
export simple.mp4 took 4745ms, 579237 bytes
simple validation: valid=true 'Verified'

>>> START b01_complexProjectExportsAValidPortraitVideo
export complex-portrait.mp4 took 22899ms, 1403147 bytes,
  timeline=video=2 overlay=2 text=3 sticker=2 effect=2 audio=1 transitions=1 duration=1400ms aspect=RATIO_9_16
portrait dimensions: expected=720x1280
portrait validation: valid=true 'Verified' size=720x1280 video=video/avc audio=audio/mp4a-latm
portrait frames: first luma=0.4098 mid luma=0.4216 last luma=0.2542

>>> START b02_complexProjectExportsAValidLandscapeVideo
export complex-landscape.mp4 took 35766ms, 2749533 bytes
landscape validation: valid=true 'Verified' size=1920x1088 video=video/avc audio=audio/mp4a-latm

>>> START c01_manySimultaneousLayersStillExport
export many-layers.mp4 took 23855ms, 1413571 bytes
many-layers validation: valid=true 'Verified' size=720x1280

>>> START d01_simpleProjectStillExportsAfterHeavyProjects
export after-heavy-out.mp4 took 4545ms, 579237 bytes
post-heavy simple validation: valid=true 'Verified'
```

(The 1920x1088 is the 16-px aligned coded size the encoder planner fixes; the validator accepts it
as the expected dimension.)

Conclusions that follow directly:

* **Complex compositions do export and validate** in this environment — multiple tracks, overlays,
  text, stickers, filters, colour adjustments, motion keyframes, animations, a chroma key over a
  background video, and a 5-simultaneous-video-layer project. So the reported failure is *not* a
  universal defect of the composition or export code.
* **The native C++ compositor is used** on the export path (`AsyncFramePipeline:
  Export attempt 1/2: ... compositor=native`, `NativeRenderBridgeJNI: Native Engine instance ...
  cleanly released`), so this is not a case of the Kotlin fallback hiding a broken native path.
* The **post-heavy control is byte-for-byte the same size** as the first control (579237 bytes), so
  four heavy exports leave no leaked decoder/GL resource that changes the result of a later export.
* Known environment limitation, unchanged by this work: on this GL stack the ES3 *shader transition*
  engine reports success and leaves the frame black, so the documented hard-cut fallback is used
  (`Composition regression: "shader transition compositor renders a blend on this device = false"`).
  That is why the transition in the complex suite is asserted through the validator and pixels, not
  by a blend measurement.

## 3. Defects found and fixed in this work

### 3.1 A failed export did not say *what* failed (fixed)

`HardwareVideoTextureSource.initialize()`/`decodeFrame()` reported only "no picture": a decoder that
could not be opened (codec instance limit, unsupported profile, dead codec) produced the same
`false` as a decoder that simply returned no frame in time. The export then ended in

```
Could not decode a video frame from '<clip>' at <t> ms (timeline <t> ms).
```

with no mention of the codec. That is the message the user sees, and it names nothing that can be
acted on.

Now the source records the reason (`lastError`) — including the `MediaCodec.CodecException` error
code, `recoverable`, `transient` and `diagnosticInfo` — and the engine carries it into the export
exception:

```
Could not decode a video frame from 'clip.mp4' at 120 ms (timeline 120 ms); decoder reported:
decoder setup failed for content://...: android.media.MediaCodec$CodecException: Failed to
allocate component instance (errorCode=-10000 recoverable=true transient=false diagnostic=...).
Export stopped instead of writing black frames.
```

The existing "Export Failed" screen already renders that message (no UI change), so the next
real-device failure is diagnosable from the phone.

### 3.2 A refused codec instance aborted the export (mitigated, reactive)

`AsyncFramePipelineEngine` evicts one idle decoder when four are open, but a frame that *needs* more
than four simultaneous pictures (several overlays + a transition side + a chroma-key background
video) asks for a fifth decoder with nothing idle to release. On a device that grants only four
codec instances, `MediaCodec.createByCodecName`/`configure` throws, `initialize()` returns false,
and the export stops — with an error that (before 3.1) did not even name the reason.

The export now retries **once** after releasing the decoders that no layer of the current frame
needs, and only those: the rule is `DecoderBudget` (`evictions`, `canFree`), pure and unit-tested,
because releasing a clip the frame is about to draw would silently drop a layer the user added.
The path starts at an open failure, so a device that hands out the instance immediately never enters
it; when nothing may be freed the export still fails, now with the codec's own reason.

### 3.3 A defect in the new test itself (fixed, disclosed)

The first CI run of the suite failed in the *control* case, and the cause was the test: the fixture
and the export output were written to the same path, and `AsyncFramePipelineEngine` deletes the file
at its output path before it opens the muxer (`if (outputFile.exists()) outputFile.delete()`), so the
export deleted the clip it had to decode. Fixtures now live in a `sources/` subdirectory and
`export()` fails loudly if an output path ever collides with a source clip. This is recorded here
because "the test suite failed" on a pull request is otherwise easy to misread as a product defect.

### 3.4 Reporting (fixed)

Runner logs and artifact downloads of this repository are not reachable from the reviewing
workstation, so the verification workflow now posts the failure message **and stack trace**, the
whole device stdout, and the engine/codec/native-compositor lines of logcat into the pull request.

## 4. What remains open

**The reported failure has not been reproduced.** Complex projects export correctly on the reference
device (section 2). Everything below is therefore a hypothesis with a stated bound, not a claim:

* **Codec instance limits on real hardware.** The emulator's software decoders allow dozens of
  instances; a phone commonly allows four hardware ones. The mitigation in 3.2 covers the case where
  an instance is refused *and* an idle decoder exists. A project that genuinely needs more
  simultaneous pictures than the device can decode (many video overlays at once) still cannot be
  decoded in full; the export now says so instead of failing anonymously. The remaining decision —
  whether to trade a visible layer for another one — is deliberately *not* taken silently.
* **Long timelines / high resolution.** The suite's timelines are 0.9–1.4 s and export at
  720p/1080p, because the emulator's software GL renders ~40x slower than realtime there (a 22.9 s
  export for 1.4 s of video). Encoder-idle (60 s), EOS-drain (30 s) and teardown (10 s) watchdogs,
  and the validator's duration/stall tolerances, are therefore not stressed by these tests.
* **The transitions on this GL stack** fall back to a hard cut (section 2). A device with a working
  ES3 transition path draws a real blend instead; the transition *code* is covered by
  `CompositionRegressionInstrumentedTest`, which measures the blend and skips the assertion only
  where the device reports the fallback.

## 5. How to capture the cause on the failing device

The next failure on a real device now carries the answer:

1. The "Export Failed" screen shows the message, which includes
   `decoder reported: …` (codec name/error code) or the encoder/muxer reason.
2. In logcat: `AsyncFramePipeline` (`Export attempt`, `Composition summary: frames=… composed=…
   empty=… probes=… black=… compositor=native|kotlin`), `HwVideoTextureSource` (decoder timeouts with
   input/output counters), `ExportDiagnostics` (`EXPORT_SETUP`, `EXPORT_FINISHED`), plus `ACodec` /
   `CCodec` (`Failed to allocate component instance` = the codec limit) and `MPEG4Writer`.
3. `ExportBlackFrameGuard` distinguishes "every probe black" from "probe unavailable", so a black
   output and a refused readback no longer look alike.

## 6. Files changed

| File | Change |
| --- | --- |
| `app/src/androidTest/java/com/example/engine/export/ComplexProjectExportInstrumentedTest.kt` | new: the complex-project regression suite (6 tests) |
| `app/src/main/java/com/example/engine/composition/gpu/HardwareVideoTextureSource.kt` | records why a decode produced no picture (`lastError`) |
| `app/src/main/java/com/example/engine/export/AsyncFramePipelineEngine.kt` | carries that reason into the export exception; retries once after freeing decoders no frame layer needs |
| `app/src/main/java/com/example/engine/export/DecoderBudget.kt` | new: the "never release a decoder the frame needs" rule |
| `app/src/test/java/com/example/engine/export/DecoderBudgetTest.kt` | new: 5 unit tests for that rule |
| `.github/workflows/postpatch-verify.yml` | posts failure message + trace, full device stdout and engine/codec logcat into the PR; adds the new suite to the gate |
| `toolchain/verify/run_logic_tests.sh` | compiles/runs `DecoderBudget` (101 logic tests) |

No composition, compositor, codec-planner or UI code was changed: the exported picture for the
projects in the suite is produced by the same code as before this work.
