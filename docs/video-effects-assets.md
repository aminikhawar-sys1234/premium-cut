# Video Effects assets — replacement & pipeline wiring

The Video Effects tab used to ship five asset rows (`video.motion_blur`, `video.glitch`,
`video.chromatic`, `video.film_grain`, `video.light_leak`) that pointed at generic engine
definitions. They have been **deleted from the catalog** and replaced with **ten real assets**,
each backed by its own GLSL body and wired through preview, shading and export.

## What changed

| Removed catalog asset | Old `vfx:` wiring | Replaced by |
|---|---|---|
| `video.glitch` | `vfx:distort.glitch` | `video.datamosh` (block motion, channel tear) |
| `video.chromatic` | `vfx:chromatic.aberration` | `video.anamorphic` (lens streak) + `video.halation` |
| `video.light_leak` | `vfx:light.leak` | `video.lens_flare` (halo, ghosts, bar) |
| `video.motion_blur` | `vfx:blur.directional` | `video.motion_blur` → **new** `vfx:blur.motion` |
| `video.film_grain` | `vfx:noise.filmGrain` | `video.film_grain` → **new** `vfx:noise.grain` |

The old engine definitions (`blur.directional`, `distort.glitch`, `chromatic.aberration`,
`noise.filmGrain`, `light.leak`) stay registered: the VFX stack panel lists them and projects
saved before this change still decode, preview and export with the same look. Only the catalog
assets are gone, so they can no longer be applied from Video Effects.

## The ten assets

| # | Catalog id | Name | Engine id (`vfx:` key) | Shader body | Params (beyond `intensity`) |
|---|---|---|---|---|---|
| 1 | `video.motion_blur` | Motion Blur | `blur.motion` | `EffectGlsl.MOTION_BLUR` | `distance`, `angle`, `samples` |
| 2 | `video.vhs` | VHS Tape | `video.vhs` | `EffectGlsl.VHS_TAPE` | `bleed`, `noise`, `tracking`, `scanline` |
| 3 | `video.crt` | CRT Scanlines | `video.crt` | `EffectGlsl.CRT_SCANLINES` | `curvature`, `scanline`, `grille`, `flicker`, `vignette` |
| 4 | `video.datamosh` | Datamosh | `distort.datamosh` | `EffectGlsl.DATAMOSH` | `amount`, `blockSize`, `smear` |
| 5 | `video.anamorphic` | Anamorphic Streaks | `light.anamorphic` | `EffectGlsl.ANAMORPHIC_STREAK` | `amount`, `threshold`, `length`, `tint` |
| 6 | `video.film_grain` | Film Grain | `noise.grain` | `EffectGlsl.FILM_GRAIN_PRO` | `amount`, `size`, `shadow`, `chroma` |
| 7 | `video.halation` | Halation | `light.halation` | `EffectGlsl.HALATION` | `amount`, `radius`, `threshold`, `tint` |
| 8 | `video.lens_flare` | Lens Flare | `light.flare` | `EffectGlsl.LENS_FLARE` | `position`, `amount`, `halo`, `ghosts`, `tint` |
| 9 | `video.speed_lines` | Speed Lines | `stylize.speedLines` | `EffectGlsl.SPEED_LINES` | `center`, `amount`, `lines`, `speed`, `reach` |
| 10 | `video.kaleidoscope` | Kaleidoscope | `distort.kaleidoscope` | `EffectGlsl.KALEIDOSCOPE` | `segments`, `rotation`, `zoom`, `center` |

Every asset is a single pass. CRT and Kaleidoscope are coordinate warps (`warpUV`), so their
strength blends the mapping; the other eight blend the shaded pixels with
`mix(base, result, u_intensity)` — the same rule the shader template applies to every effect.

The live preview runs the same math with a smaller tap budget (Motion Blur 32 of 64 samples,
Anamorphic 17 of 33, Halation 12+6 of 16+8) so a 1080p preview stays real-time; parameters,
tints, thresholds and the intensity blend are identical to export.

## Pipeline

```
Effects panel tile  ──►  ProductionEffectCatalog (10 rows, shaderKey = "vfx:<engine id>")
        │                        │
        │                        ├─ apply → ProductionEffectApplicator.apply()
        │                        │            tune(intensity → the params the shader reads)
        │                        │            clip.vfxStackJson  (source of truth, saved with the project)
        │                        │
        ▼                        ▼
EffectThumbnailRaster      VfxStackStage  (com.example.engine.composition.gpu)
 (icon-resolution CPU      └─ GpuCompositionRenderer, main clip **and** overlay layers,
  preview of the same          live GL surface **and** export (AsyncFramePipelineEngine)
  look, per shaderKey)          → EffectGlsl body + params compiled by SinglePassRuntime
                                        │
PreviewVfxEffects (Media3 GlProgram, GLSL ES 1.00)  ─┘ mirrors the same math for the
 live ExoPlayer preview; reads the same instance params and blends with uIntensity
```

* **Preview**: `PreviewFilterEffects.effectsFor()` appends `PreviewVfxEffects.effectsFor(clip)`.
  Each id maps to a `CustomShaderGlEffect` whose `uniformBinder` publishes the real params
  (not just intensity) plus `uTime` from `presentationTimeUs`, so the temporal effects animate.
* **Shading**: `EffectGlsl` bodies are compiled per effect id; params are bound from
  `EffectSnapshot` in the definition's uniform setter.
* **Export**: the clip's `vfxStackJson` runs inside the GPU compositor for both clips of a
  transition, every overlay/PIP layer and the main clip, so an asset renders identically in the
  editor and in the exported file.
* Failure handling is unchanged: a shader that fails to build makes `VfxStackStage` pass frames
  through, and a preview shader that fails to compile falls back to Media3's identity blit.

## Tests

* `app/src/test/java/com/example/engine/effects/VideoEffectsAssetsTest.kt` — the ten assets:
  catalog shape, removed id set, registration, per-asset GLSL bodies, tuned params, the
  ten-effect `vfxStackJson` round-trip with `resolveAt()` (what `VfxStackStage` runs per frame),
  and the Media3 preview mapping for every asset key.
* `ProductionEffectsTest` — catalog/registry counts (30 effects, 10 per Video Effects) and the
  thumbnail contract: every asset must visibly change the live clip frame at icon resolution.
* `Media3EffectPipelineTest` — a Video Effects asset reaches the live preview graph.

## Adding an eleventh asset

1. Add the GLSL body to `EffectGlsl` (`process`, optional `warpUV`).
2. Register a definition in `com.vfx.engine.effects.VideoEffects` with its `ParamDescriptor`s and
   the uniform setter that binds them.
3. Add the catalog row in `ProductionEffectCatalog.videoEffects`.
4. Map the id in `PreviewVfxEffects.toMedia3` (same math, params read via `instance.param(...)`).
5. Add the `vfx:` branch in `EffectThumbnailRaster.shade`.
6. Add the `tune()` case in `ProductionEffectApplicator` so intensity reaches the shader params.
