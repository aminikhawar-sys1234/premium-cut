# Filters tools — audit & fixes

Scope: the editor Filters / Adjust / Video Quality sheet, the live ExoPlayer preview,
and the GPU export compositor.

```
FilterToolsPanel (Filters | Adjust | Video Quality)
   └─ TimelineEngine.updateFilter / updateClipAdjustments / setClipColorGrade
        ├─ VideoPlaybackEngine.applyActiveLookToPlayers
        │    └─ PreviewFilterEffects → Media3 ColorGradingGlEffect + LutGlEffect
        └─ AsyncFramePipelineEngine → GpuCompositionRenderer.bindCommonUniforms
             + ColorGradeStage (LUT)
```

## Bugs fixed

1. **Filter tap did not redraw the live video immediately.**  
   `refreshCurrentFrame()` only seeked when paused and raced the timeline collector, so
   `setVideoEffects` often landed after the seek (or not at all). It now syncs the latest
   timeline, force-rebinds Media3 effects, then seeks the held frame.

2. **Look applied to a clip off the playhead stayed invisible.**  
   Preview used `findClipAt(CTI)`. Selecting a filter on another clip now seeks onto that
   clip and binds that clip's look.

3. **LUT looks were export-only.**  
   `colorGradeJson` is now part of the preview signature. Bundled `.cube` files are baked
   into a Media3 vertical-strip LUT (`LutStripBaker`) and pushed to ExoPlayer.

4. **Video Quality / whites / blacks / fade were stubbed in the shaders.**  
   The same uniforms now run in `ColorGradingGlEffect` (preview) and `GpuShaders`
   (export): auto enhance, HDR / color correct, color fix, denoise + anti-flicker,
   whites, blacks, fade.

5. **Dummy / duplicate filter UI.**  
   Unused `FilterVideoPreview.kt` (animated fake landscape cards) is removed. Missing
   thumbnails use a shared neutral swatch + the real ColorMatrix, not per-filter cartoons.

6. **Panel layout.**  
   Filters / Adjust / Video Quality fill 45% of editor height so the 4-column grid has
   real space (`weight(1f)` no longer collapses inside `wrapContentHeight`).

7. **Filters Reset left the LUT on.**  
   Reset now clears the clip's LUT as well as `FilterType`.

8. **A published look was graded onto the wrong clip.**  
   `VideoPlaybackEngine.publishLiveGrading()` always resolved the look from the playhead
   clip (`findClipAt(CTI)`), so an Adjust / Video Quality edit on a *selected* clip that was
   not under the playhead changed nothing on screen, and an edit on an overlay (PiP) clip
   never reached that overlay's grading node at all. The engine now has one dedicated entry
   point, `publishLiveLook(look)`, which publishes onto the node of the clip the look
   belongs to (main-track node or overlay node) instead of the playhead's.

9. **The held (paused) frame was never repainted after a value change.**  
   `applyActiveLookToPlayers()` pushed new uniforms onto the resident `ColorGradingGlEffect`
   and returned. While paused, ExoPlayer draws no further frames, so the whole call was a
   no-op: `refreshCurrentFrame()` (LUT taps) and every slider tick updated uniforms that
   nothing rendered again. The unchanged-graph path now calls `requestPausedFrameRefresh()`.

10. **The repaint throttle dropped the slider's final value.**  
    `presentCurrentFrame()` rate-limited to one repaint per 40 ms and simply discarded every
    request inside that window. A slider drag emits faster than 40 ms, so the last tick of a
    drag was frequently swallowed. The throttle now *coalesces*: a trailing repaint is
    scheduled for the end of the window, so the value on screen is always the final one.
    `presentCurrentFrame()` gained a `force` parameter for that trailing repaint.

11. **Identity-fallback devices hammered `setVideoEffects`.**  
    When Media3 could only run the identity passthrough, `boundMainGrading` stayed `null` and
    every slider tick rebuilt the graph again (and failed again). `lookFallbackActive` stops
    the rebuild loop; the flag clears on a successful apply, on editor entry and on session
    reset.

12. **No way to see or tune what was applied.**  
    - The Filters tab had no intensity control at all: a tapped preset always landed at 100%.
      It now has a *selected look* row with a live 0–100% intensity slider.
    - The panel grew a **live status bar**: the scope (clip vs project) plus one removable
      chip per non-default value (`Golden 80% ×`, `Brightness +25 ×`, `Auto Enhance 40% ×`, …).
      Tapping a chip clears just that one parameter.
    - The preview gained a **live look HUD** (`VideoPreviewSurface`), rendered from the
      published `LivePreviewLook` and auto-hiding after ~1.8 s. When the look targets a clip
      that is not on screen it names that clip (`On Clip 2`) instead of implying a broken
      preview. `_livePreviewLook` is now cleared by `flushLiveLook()`, so the HUD only lives
      while the sheet is open.

13. **Closing the sheet could strand the look.**  
    `EditorScreen` overrode the panel's `onClose` and skipped `flushLiveLook()`; the edit
    survived only by luck of the panel's `DisposableEffect`. All three call sites now flush,
    and `navigateTo()` flushes when leaving the editor.

## Preview = export

| Look | Live preview | Export |
|------|--------------|--------|
| `FilterType` presets | `ColorGradingGlEffect` matrix | `GpuCompositionRenderer` matrix |
| Adjust sliders | same shader uniforms | same shader uniforms |
| Video Quality sliders | same shader uniforms | same shader uniforms |
| Bundled LUT looks | `LutGlEffect` / Media3 LUT | `ColorGradeStage` |

## Tests

`VideoFiltersTest` and `Media3EffectPipelineTest` cover clip-local filters, LUT
signatures, Video Quality / fade / whites not being no-ops, and LUT strip baking.
`LiveLookStatusBarTest` covers the applied-look summary (every non-default value is listed,
and clearing one chip resets only that parameter).
