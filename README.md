<div align="center">
<img width="1200" height="475" alt="GHBanner" src="https://ai.google.dev/static/site-assets/images/share-ais-513315318.png" />
</div>

# Run and deploy your AI Studio app

This contains everything you need to run your app locally.

View your app in AI Studio: https://ai.studio/apps/bccd2e77-a292-4cfc-8b39-b9c4cacf9d7e

## Run Locally

**Prerequisites:**  [Android Studio](https://developer.android.com/studio)


1. Open Android Studio
2. Select **Open** and choose the directory containing this project
3. Allow Android Studio to fix any incompatibilities as it imports the project.
4. Create a file named `.env` in the project directory and set `GEMINI_API_KEY` in that file to your Gemini API key (see `.env.example` for an example)
5. Remove this line from the app's `build.gradle.kts` file: `signingConfig = signingConfigs.getByName("debugConfig")`
6. Run the app on an emulator or physical device
7. If you have already published your app in AI Studio, please [request upload key reset](https://support.google.com/googleplay/android-developer/answer/9842756#zippy=%2Crequest-an-upload-key-reset) in Google Play Console.

## Native renderer & 4K export

- `libah_engine.so` (OpenGL ES 3.0 compositor, `app/src/main/cpp`) is built by CMake and loaded through
  `NativeEngineLoader.loadLibrary()`. If loading fails, the Kotlin GPU pipeline is used automatically.
  Requires the Android NDK and CMake 3.22.1 (SDK Manager).
- 4K (3840x2160 / 2160x3840) export uses `ExportEncoderPlanner`, which picks HEVC/AVC and lowers fps, then
  resolution, only if the device encoder cannot handle the requested size.

## Stabilizer: rolling shutter and jelly

- The planar tracker also measures the picture motion separately in 6 horizontal row bands per frame. Because
  every band is read out at a different moment, this samples the camera path several times per frame, so fast
  vibration (jelly) becomes measurable. The solver lines the band paths up, high-passes them and fits, per frame,
  a curved row correction `x' = x + rsX*y + rsX2*y^2` (same for y), plus a small whole-frame shift.
- The curved part is applied on the GPU by a subdivided-mesh vertex shader (`GpuShaders.STAB_VERTEX_SHADER`);
  the straight skew/perspective part still goes through the 4x4 warp matrix. Saved projects use format `v3`
  (older `v1`/`v2` strings still load).
- Limits: vibration is only measurable up to about (bands x frame rate) / 2 Hz (~70 Hz at 24 fps, but the
  practical limit is lower because of tracking noise, roughly 35 Hz); tracks made before this change carry no band
  data and fall back to the constant-speed skew model until the clip is stabilised again; rows are read out
  top-to-bottom (portrait-held phones in landscape sensor orientation are not special-cased).
- The sensor readout time defaults to an estimate of 20 ms. Phones differ, so it is adjustable: use the
  "Sensor readout" slider in the Stabilize dialog, or pass `readoutUs` to `Stabilizer.solve(...)`
  (`engine/ai/tracking/TrackPostProcessing.kt`). Values are clamped to 1-60 ms.
