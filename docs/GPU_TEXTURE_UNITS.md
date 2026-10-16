# Texture units and sampler uniforms (the `glErrors=[0x502]` repair)

`0x502` is `GL_INVALID_OPERATION`. When an export reports it *and* comes out black, the two
symptoms have one cause: **the draw call was rejected**, so the target kept its cleared (black)
content. This note records what produced it, what was changed, and how the wiring is checked
without a device.

## How a sampler-related `GL_INVALID_OPERATION` is produced

`glActiveTexture`, `glBindTexture` and `glUniform1i` are three different things and the error comes
from their *combination* at draw time:

| call | what it does | what it does **not** do |
| --- | --- | --- |
| `glActiveTexture(GL_TEXTURE0 + n)` | selects unit `n` for the *following* bind calls | does not bind anything, does not touch shaders |
| `glBindTexture(target, id)` | binds `id` at the **bind point of `target`** on the active unit | does not select a unit; a target's bind point is separate from another target's on the same unit (`GL_TEXTURE_2D` vs `GL_TEXTURE_EXTERNAL_OES` vs `GL_TEXTURE_3D` are three bind points) |
| `glUniform1i(loc, n)` | records "this sampler reads unit `n`" | does not select a unit, does not bind a texture |

The GLES spec then rejects the draw when, for the **current program**:

1. two *active* samplers of **different types** resolve to the same unit
   (`samplerExternalOES` + `sampler2D` on unit 0 is the classic), or
2. an active sampler reads a unit whose bound texture does not match the sampler's type
   (`sampler2D` reading a unit with only a `GL_TEXTURE_3D` bound, etc.), or
3. a sampler uniform holds a unit number that is out of range.

A sampler uniform nobody writes holds its **default value, 0**. "The feature is switched off, so
the sampler is unused" is not a safety argument: drivers keep a sampler active whenever the
compiler cannot prove the sampling is unreachable, and GLSL compilers routinely keep
`texture2D(uTexture, ...)` inside a branch gated by a uniform. So *every* sampler a program declares
must be pointed at a unit of its own type on every bind — including the samplers whose feature is
off this frame.

Black (rather than an error) also comes from the same area when a sampler *is* assigned but the
value is wrong: the 2D-LUT variant divides by `uLutSize`, and `0` there yields `inf`/`NaN`
coordinates, i.e. `texture()` returns black, which `mix(c, black, intensity)` then applies to the
whole frame.

## What was wrong here

Two programs declared more samplers than the binding code assigned.

`GpuShaders.buildFragmentShader` (the compositor's one fragment shader, used by `program2D`,
`programOes`, `programStab2D`, `programStabOes`) declares the picture sampler **and**

```glsl
uniform sampler2D uChromaBgTex;         // GpuShaders.kt, unconditional
```

which the shader reads in its `uChromaBgType == 2` branch. `GpuCompositionRenderer.bindCommonUniforms`
assigned that sampler's unit **only inside `if (chromaKey.enabled)`**. With chroma keying off — the
normal case — it kept the default unit 0, on which the picture sampler sits as
`samplerExternalOES` for any video frame. Different sampler types on one unit: `GL_INVALID_OPERATION`
on every draw of that variant. This is the failure the report line carried as `glErrors=[0x502]`,
and the rejected draw is why every frame was black. The same shader is also bound by the overlay
batch (`program2D`), which never assigned the sampler at all.

`ColorShaderSource.fragment` (the colour pipeline) declares up to seven samplers:
`uInput` (`samplerExternalOES` on video frames, `sampler2D` on composed textures), `uCurveTex`,
`uLumaCurveTex`, `uLut3d` **or** `uLut2d`, and `uSecMask[0..1]`. `ColorGpuProcessor.render`:

* assigned `uInput` only `if (inputTarget != GL_TEXTURE_EXTERNAL_OES)`, so in the external variant
  the uniform was never written (harmlessly equal to 0 by default, but the pattern is what produced
  the rest);
* never assigned `uLut2d` or `uLutSize` anywhere in the app — and `LutTextureManager` only ever
  uploaded a `GL_TEXTURE_3D`, so the `HAS_LUT2D` variant (selected by `ColorShaderManager.variantKey`
  when `lut3dCapable` is false, i.e. on an ES2-class context) had no atlas, no unit and a
  `uLutSize` of 0 → `NaN` atlas coordinates → black;
* assigned `uSecMask[i]` only for the slots whose mask texture happened to exist *this frame*, so a
  missing mask left that sampler at unit 0 next to the OES input;
* bound the LUT upload on whatever unit was active (`LutTextureManager.getOrCreate` has no
  `glActiveTexture`), i.e. on unit 0 right after the picture was bound there.

`GpuCaps.detect()` additionally cached its capabilities process-wide while preview and export run
on **different EGL contexts**; since `glVersionMajor` decides between the 3D and the 2D LUT variant,
a stale cache could select the variant the other context had to run.

## The repair

* **One map per shader.** `com.ahstudio.color.gpu.ColorTextureUnits` (colour pipeline) and
  `com.example.engine.composition.gpu.GpuTextureUnits` (compositor) name every unit; no bare
  numbers and no "whatever unit is free".
* **Unconditional assignment.** Every sampler of the program is written on every bind, feature on
  or off: `uChromaBgTex` → unit 2 (bound to the chroma background texture, or 0) outside the chroma
  branch; `uInput` → unit 0 in both variants; the curve samplers → 1 and 2; the LUT → 3; the mask
  slots → 4 and 5, each bound to its mask or to a 1×1 white texture when the slot has none this
  frame.
* **The 2D LUT fallback exists now.** `LutAtlasLayout` defines the tiled atlas
  (`column = blue * n + red`, `row = green`, `.cube` order preserved) exactly as the `#if HAS_LUT2D`
  GLSL samples it; `LutTextureManager.getOrCreate2DAtlas` uploads it; `uLut2d` gets unit 3 and
  `uLutSize` is set from `LutAtlasLayout.uniformSize` so it can never be 0. When the 3D variant is
  selected but the LUT data is missing, a 2×2×2 black 3D texture is bound with `uLutIntensity = 0`
  rather than leaving a sampler with no texture of its own type.
* **Uploads stop moving the active unit.** `LutTextureManager` binds on the unit that is already
  active and unbinds again, so it can never leave a 3D LUT behind on unit 0; `ColorGpuProcessor`
  restores unit 0 and clears the 3D LUT's unit when it is done.
* **The map is observable.** `ColorTextureUnits.audit(program, variantKey)` reads every sampler's
  unit back from the linked program once per variant, logs the whole map, and logs an error naming
  the clashing samplers when two types share a unit — so the next occurrence of this class of
  failure is attributable from the log instead of being a black video.
* **`GpuCaps` cache is keyed on `GL_VERSION`**, so a second context re-detects instead of reusing
  the first context's answer.

## Verification without a device

* `toolchain/verify/verify_texture_units.py` — parses the GLSL in `GpuShaders.kt`/`ColorShaderSource.kt`
  *and* the Kotlin that binds it, and asserts: `uChromaBgTex` is declared unconditionally and
  assigned outside any `if (chromaKey.enabled)`; `uInput` is not guarded by
  `if (inputTarget != ...OES)`; every declared sampler is assigned; no unit carries two sampler
  types (checked per variant: ES3/ES2 × external/2D input); `uLutSize` is written; the uploader
  never calls `glActiveTexture`; and the atlas addressing re-derives the CPU reference sample for
  sample. It fails 30+ checks against the pre-repair sources.
* `app/src/test/java/com/ahstudio/color/LutAtlasLayoutTest.kt` — the layout itself, compiled and
  executed on a plain JVM by `toolchain/verify/run_logic_tests.sh`.
* `toolchain/verify/verify_black_frame_recovery.py`, `verify_shader_parity.py`,
  `verify_render_contract.py`, `verify_look_model.py`, `verify_chroma_key.py` — unchanged and still
  green, so the repair does not disturb the export contract or the preview/export parity.

The GL calls themselves still need a device (or Robolectric with a GL implementation) to execute;
what is checked here is that the shader/sampler/unit contract they encode is consistent everywhere
it is declared.
