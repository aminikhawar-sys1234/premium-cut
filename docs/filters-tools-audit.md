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
