# Export & rendering audit — why a 1 minute video took 10–15 minutes

Scope: everything between the timeline and the finished MP4.

```
Timeline
  └─ ProfessionalExportEngine            (plan, headline progress, verification)
       └─ AsyncFramePipelineEngine       (THE export pipeline)
            ├─ AudioExportProcessor      (decode + mix every track -> one PCM master)
            ├─ HardwareVideoTextureSource (per-clip hardware decoder -> OES texture)
            ├─ VideoCompositionEngine / GpuCompositionRenderer  (GPU frame composition)
            │     └─ TextLayerRenderer, GlShaderUtil, effect stages
            └─ MediaCodec (surface input) -> MuxerCoordinator -> MediaMp4Writer
```

`ProfessionalExportEngine` does one deterministic pass through this pipeline and then validates the
file; `VideoExporter.exportVideo` and the editor's Export button both end up in the same pipeline.

## Findings (severity order)

### 1. Audio decode accumulated PCM through a `MutableList<Byte>` — *critical*

`AudioExportProcessor.decodePcmFromMedia()` read every decoded audio buffer into
`pcmBytes.addAll(chunk.toList())`, i.e. one boxed `Byte` element per audio byte, in an
`ArrayList` that grew to millions of entries, only to be copied into a `ByteArray` afterwards.

* 1 minute of stereo 48 kHz audio ≈ 11.5 MB of bytes ≈ **11.5 million list entries** + growth
  copies. A project built from a 5–10 minute source video multiplies that per track, because…
* …every video clip **and** overlay clip with audio is a separate track in the mix
  (`collectAudioTracks`), and each one decoded its source **in full**.

### 2. Audio decode ignored the clip's trim window — *critical (speed and correctness)*

`decodePcmFromMedia(uri)` always decoded the whole file from sample 0, and `mixTrackIntoBuffer()`
indexed source samples from 0 as well. Two consequences:

* a 10 minute song trimmed to 10 seconds was decoded (and kept in memory) completely;
* the mix played a trimmed clip's audio **from the start of the file** instead of the clip
  in-point, so video and audio could not be in sync for a trimmed clip (`timelineToSourceMs()`
  starts at `sourceStartMs`, the audio did not).

### 3. Decoder handshake cost per frame — *high*

`HardwareVideoTextureSource`:

* `feedInput()` queued **one** sample and then `dequeueOutputBuffer(2 ms)`; a hardware decoder only
  produces a picture once its input queue has depth, so most frames paid the 2 ms "no output yet"
  timeout several times before the picture appeared;
* `awaitFrameArrival()` polled the frame-available flag in `Thread.sleep(1)` steps, and the real
  wake-up latency of a 1 ms sleep is coarser than 1 ms — several milliseconds **per decoded frame,
  per clip** (main clip + every overlay + both transition sources).

At 30 fps that is thousands of small stalls inside a 1 minute export.

### 4. Per-frame GPU synchronisation — *high*

`AsyncFramePipelineEngine` called `GLES20.glFinish()` after composing every frame. `glFinish()`
waits until the GPU is completely idle, so the CPU could not even start decoding frame *N+1* while
the GPU finished frame *N* — the whole pipeline was serialised frame by frame. `eglSwapBuffers()`
(which the loop calls right after) already orders rendering against the encoder surface, so the
finish was pure overhead.

### 5. Animated text re-rasterised into a fresh full-viewport bitmap every frame — *high for text projects*

`TextLayerRenderer.renderToBitmap()` allocated a new 1920×1080 ARGB bitmap (≈ 8.3 MB) per animated
text frame, `GpuCompositionRenderer` uploaded it with `GLUtils.texImage2D` (which calls
`glTexImage2D`, i.e. **reallocates the texture storage**) and then recycled the bitmap. Every
animated text layer therefore produced ~8 MB of bitmap churn + ~8 MB of texture reallocation per
frame. `TextLayerRenderer`'s own font lookup was uncached too: `FontManager.loadTypeface()` called
`Typeface.createFromFile` / `Typeface.create` on **every** text draw.

The procedural effect overlay (`getOrCreateProceduralEffectTexture`) had the same upload pattern
with a reused bitmap but still a full texture reallocation per 33 ms step.

### 6. Black-frame probe did 64 GPU round trips — *medium*

`isBackBufferBlack()` issued 64 single-pixel `glReadPixels` calls (a full pipeline round trip each)
on the probed frames. Reading one row of pixels per grid line samples the same 8×8 grid for 8 round
trips instead of 64.

### 7. Pipeline threads ran at default priority — *medium*

The export runs while the editor UI keeps rendering progress. The GL submit thread, the decode
thread and the muxer drain thread all used default priority, so the UI thread regularly preempted
the pipeline (`eglSwapBuffers` waiting on a starved drain thread stalls the render thread too).

## Fixes in this change

| # | Where | Change |
|---|-------|--------|
| 1 | `AudioExportProcessor.decodePcmFromMedia` | PCM is written straight into a growable `ShortArray` (`buffer.asShortBuffer().get(...)`). No boxing, no intermediate `ByteArray`. |
| 2 | `AudioExportProcessor` (+ new `audioSourceWindow`) | Decode is bounded to the window the clip actually uses (`sourceStartMs`, `durationMs * speed`, `sourceEndMs`, ±250 ms margin), the window is part of the PCM cache key, and reversed clips are anchored to their source end — the same range `timelineToSourceMs()` walks. The mix now starts at the clip in-point. Multi-channel sources are indexed with their real channel count (stereo is byte-for-byte unchanged). |
| 3 | `HardwareVideoTextureSource` | `feedCodec()` fills the codec's input queue with non-blocking `dequeueInputBuffer(0)` calls (burst ≤ 16) before waiting for output, and `onFrameAvailable` notifies a monitor the GL thread waits on instead of the `Thread.sleep(1)` poll loop. |
| 4 | `AsyncFramePipelineEngine` | Per-frame `glFinish()` → `glFlush()`; heavy work stays ordered by `eglSwapBuffers`. |
| 5 | `GpuCompositionRenderer`, `TextLayerRenderer`, `GlShaderUtil`, `FontManager` | One reusable scratch bitmap per renderer for animated text (`eraseColor` instead of `createBitmap`), `glTexSubImage2D` upload into the existing texture when the size is unchanged (thread-local scratch buffer, so preview and export GL threads cannot share it), and an LRU `Typeface` cache in `FontManager`. |
| 6 | `AsyncFramePipelineEngine.isBackBufferBlack` | 8 row readbacks instead of 64 pixel readbacks. |
| 7 | `AsyncFramePipelineEngine` | GL thread at `THREAD_PRIORITY_DISPLAY`, decode thread and muxer drain thread at `THREAD_PRIORITY_FOREGROUND`. |

### Verification / measurement

* `AudioExportWindowTest` (new, pure JVM) pins the decode window: untrimmed clip stays near its used
  span, trimmed clip starts at its in-point, 2× / 0.5× speed, reversed clip anchored to the source
  end, explicit source end clamped, never empty.
* The pipeline now reports where the time goes (logcat, tag `AsyncFramePipeline`):

```
Audio mix finished in 820ms (2880000 samples, 30s)
Export performance: 41230ms for 60000ms of video (1.46x realtime) |
  frames=1800 decode=6.10ms/frame compose=4.80ms/frame gpuRender=1.20ms/frame encodeWait=12.30ms/frame
```

`ProfessionalExportEngine` also logs `[EXPORT_TIMING] render=…ms validation=…ms` and the live
progress message shows the current speed (`encoded 900 / 1800 frames (50% · 1.5x realtime)`).

## The "10–15 minutes for 1 minute" case: check the fallback counter first

An export that fast is **not** explained by the per-frame overheads above alone — 60 s at 30 fps is
1800 frames, so 10–15 minutes means 330–500 ms **per frame**. The one place in the pipeline that
costs that much per frame is the still-frame fallback:

`decodeClipFrame()` tries the clip's hardware decoder, then a software decoder; if neither can give
a picture it calls `fetchFallbackBitmap()` → `MediaMetadataRetriever.getScaledFrameAtTime(...
OPTION_CLOSEST ...)`, which **seeks and decodes the source on every frame** (50–200 ms) and
allocates + uploads a new full-size bitmap. The frame still comes from the real source at the real
timestamp (that is why the code keeps it as a last resort), but the pipeline drops to single-digit
fps. It also was not visible in the old metrics: the retriever time is spent outside `decodeTimeNs`.

So the pipeline now reports it directly:

```
Export performance: 640000ms for 60000ms of video (0.09x realtime) |
  frames=1800 decode=0.10ms/frame compose=6.10ms/frame gpuRender=6.30ms/frame
  encodeWait=2.10ms/frame fallback=1800(331.55ms/frame)
```

* `fallback=1800(.../frame)` with the frame count equal to `frames` = the whole export is going
  through the retriever, and that is your 10–15 minutes.
* `fallback=0` = the bottleneck is elsewhere; the four stage numbers then point at decode, compose,
  GPU, or the encoder (`encodeWait`).
* The first fallback frame also logs a one-time warning naming the clip.

For a fallback export the remaining work is the decode path itself: the clip has no working
`MediaCodec`. Capture `adb logcat` for `AsyncFramePipeline|HardwareVideoTextureSource|VideoDecoder`
and the decoder error that precedes the "could not be decoded by hardware or software codecs"
warning tells you which codec/stream combination to fix (that log is already emitted per clip).

## Deliberately not changed (and why)

1. **Decode/GPU pipelining (double-buffered decode).** The pipeline decodes frame *N+1* only after
   frame *N* is submitted. Overlapping them (two OES texture slots per clip, `MediaCodec
   .setOutputSurface()` to alternate, decode on the decoder thread while the GL thread composes)
   is the next structural win, but it is a concurrency redesign that cannot be validated without a
   device. Do it behind the new per-stage metrics: if `decode + compose` dominates the frame time
   after these fixes, that is where the next 1.5–2× lives.
2. **Overlapping the audio mix with the render loop.** The mix still runs before the first frame
   (and is reused by every encoder retry). With the fix above the stage is small; streaming it would
   mean a block-wise mixer (soft clipping is per sample but the mix is per track) — a real
   redesign, not worth the risk until the numbers ask for it.
3. **`ExportValidator` still samples frames with `OPTION_CLOSEST`.** It is bounded (≤ 12 frames) and
   it is the gate that stops a broken file from reaching the gallery; its cost is now logged.
4. **Encoder ladder behaviour is untouched** (HEVC→AVC, ≤1080p/30 fps fallback, bitrate clamps).
   If `encodeWait` dominates in the logs, the codec/bitrate choice is the lever to look at.
