#!/usr/bin/env python3
"""Black-frame export recovery guard.

The files this checks (`GpuCompositionRenderer`, `AsyncFramePipelineEngine`, the native
`NextGenGpuCompositionEngine`) need Gradle + the Android SDK + the NDK to compile. Where that
toolchain is unavailable this script still *executes* a check on them: it parses the checked-in
sources and asserts the contract that was broken, so a future edit that reintroduces it fails here.

The bug: an export could finish with every frame black while the file looked perfect - right
duration, right size, key frames, EOS, muxer finalised - and only the post-export validator noticed.
Two silent paths produced it:

  A. The native C++ compositor allocated its ping-pong targets without checking that the framebuffers
     were complete. On a driver that refused the textures (out of memory at 1088x1920), every draw
     into them was dropped and `endOffscreen()` handed back a texture that was never written - i.e.
     zeros. `ensureTargets` logged "FBO incomplete" and continued.
  B. Kotlin treated any texture id > 0 as a composed picture, so that empty texture was blitted to
     the encoder. When the native path returned 0 it silently fell back to `fboA`, which had only
     been cleared - black again (export-pipeline-audit R-2).

Contract now enforced:
  1. the native targets are validated and refused (`ensureTargets` returns false, `beginOffscreen`
     reports it, `endOffscreen` returns 0, `renderFrame` does not draw into a dead target);
  2. the JNI + Kotlin boundary carries that failure (`nativeBeginOffscreen` returns jboolean);
  3. the Kotlin compositor re-draws with its own path instead of blitting a cleared buffer, and
     reports what it drew (`RenderReport`);
  4. the export consumes that report, abandons an all-black attempt early, verifies the decoded
     frames of the muxed file, and retries once with the fallback compositor.

Run: python3 toolchain/verify/verify_black_frame_recovery.py
"""

from __future__ import annotations

import os
import re
import sys

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
MAIN = os.path.join(REPO, "app", "src", "main")

NATIVE_ENGINE = os.path.join(MAIN, "cpp/render/NextGenGpuCompositionEngine.cpp")
NATIVE_HEADER = os.path.join(MAIN, "cpp/render/NextGenGpuCompositionEngine.h")
NATIVE_BRIDGE = os.path.join(MAIN, "cpp/native_bridge.cpp")
BRIDGE_KT = os.path.join(MAIN, "java/com/example/engine/composition/gpu/NativeRenderBridge.kt")
TRANSITION = os.path.join(MAIN, "java/com/example/engine/composition/gpu/ShaderTransitionCompositor.kt")
RENDERER = os.path.join(MAIN, "java/com/example/engine/composition/gpu/GpuCompositionRenderer.kt")
GRAPH = os.path.join(MAIN, "java/com/example/engine/composition/CompositionRenderGraph.kt")
EXPORT = os.path.join(MAIN, "java/com/example/engine/export/AsyncFramePipelineEngine.kt")
GUARD = os.path.join(MAIN, "java/com/example/engine/export/ExportBlackFrameGuard.kt")
VALIDATOR = os.path.join(MAIN, "java/com/example/engine/export/ProfessionalExportEngine.kt")
GUARD_TEST = os.path.join(REPO, "app", "src", "test", "java", "com/example/engine/export/ExportBlackFrameGuardTest.kt")
GPU_TEST = os.path.join(REPO, "app", "src", "androidTest", "java",
                        "com/example/engine/effects/ColorPipelineGpuInstrumentedTest.kt")
LOGIC_TESTS = os.path.join(REPO, "toolchain", "verify", "run_logic_tests.sh")

RESULTS: list[tuple[bool, str]] = []


def read(path: str) -> str:
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def check(condition: bool, label: str) -> None:
    RESULTS.append((bool(condition), label))


def slice_between(text: str, start: str, end: str) -> str:
    """The text from the first `start` to the first `end` after it ('' when either is missing)."""
    begin = text.find(start)
    if begin < 0:
        return ""
    stop = text.find(end, begin + len(start))
    return text[begin:stop if stop >= 0 else len(text)]


def main() -> int:
    native = read(NATIVE_ENGINE)
    header = read(NATIVE_HEADER)
    bridge = read(NATIVE_BRIDGE)
    bridge_kt = read(BRIDGE_KT)
    renderer = read(RENDERER)
    graph = read(GRAPH)
    export = read(EXPORT)
    guard = read(GUARD)
    validator = read(VALIDATOR)

    print("-- native compositor refuses unusable targets --")
    check("bool NextGenGpuCompositionEngine::ensureTargets" in native, "ensureTargets reports success/failure")
    check("bool ensureTargets(int width, int height);" in header, "header declares bool ensureTargets")
    ensure = slice_between(native, "bool NextGenGpuCompositionEngine::ensureTargets", "void NextGenGpuCompositionEngine::destroyTargets")
    check("targetsValid_ = true" in ensure, "ensureTargets marks the targets valid only after every check")
    check("glCheckFramebufferStatus(GL_FRAMEBUFFER)" in ensure, "ensureTargets checks framebuffer completeness")
    check("glGetError()" in ensure, "ensureTargets checks the texture allocation error")
    check("destroyTargets();\n            return false;" in ensure, "a refused target is released, not kept")
    check(
        not re.search(r"__android_log_print\([^)]*FBO %d incomplete", ensure),
        "no 'log incomplete FBO and continue' path remains",
    )
    check("targetsValid_ = false;" in slice_between(native, "void NextGenGpuCompositionEngine::destroyTargets", "void NextGenGpuCompositionEngine::destroyPrograms"),
          "destroyTargets clears the validity flag")
    begin = slice_between(native, "bool NextGenGpuCompositionEngine::beginOffscreen", "GLuint NextGenGpuCompositionEngine::endOffscreen")
    check("if (!ensureTargets(width_, height_)) return false;" in begin, "beginOffscreen fails when the target cannot be prepared")
    end = slice_between(native, "GLuint NextGenGpuCompositionEngine::endOffscreen", "void NextGenGpuCompositionEngine::onContextLost")
    check("if (!targetsValid_) return 0;" in end, "endOffscreen never returns a target nothing was drawn into")
    frame = slice_between(native, "void NextGenGpuCompositionEngine::renderFrame", "void NextGenGpuCompositionEngine::applyEffectPass")
    check("offscreenActive_ && !targetsValid_" in frame, "renderFrame does not draw into a dead offscreen target")
    check("targetsValid_ = false;" in slice_between(native, "void NextGenGpuCompositionEngine::onContextLost", "void NextGenGpuCompositionEngine::release"),
          "onContextLost clears the validity flag")

    print("-- the failure crosses JNI and reaches Kotlin --")
    check(
        re.search(r"JNIEXPORT jboolean JNICALL\s*\nJava_[A-Za-z0-9_]*nativeBeginOffscreen", bridge) is not None,
        "nativeBeginOffscreen returns jboolean",
    )
    check("fun beginOffscreen(handle: Long): Boolean" in bridge_kt, "NativeRenderBridge.beginOffscreen returns Boolean")
    check("private external fun nativeBeginOffscreen(handle: Long): Boolean" in bridge_kt, "external declaration matches the JNI signature")

    print("-- the Kotlin compositor never presents a cleared buffer as a frame --")
    compose = slice_between(renderer, "fun composeOnce(): Pair<Int, String?>", "var (currentInputTex, composeFailure)")
    check("if (NativeRenderBridge.beginOffscreen(nativeHandle))" in compose, "the native path is only used when its target bound")
    check("if (nativeTexture > 0)" in compose, "a native texture is only accepted when it is real")
    check('to "the native compositor returned no texture"' in compose, "an empty native texture is reported as a failure")
    check('to "the native offscreen target could not be prepared"' in compose, "a refused native target is reported as a failure")
    kotlin_branch = compose[compose.find("val drew = renderNativeLayersKotlin"):]
    check("if (drew) fboA.getTextureId() to null" in kotlin_branch, "the Kotlin path reports whether it actually drew")
    retry = slice_between(renderer, "var (currentInputTex, composeFailure)", "if (hasPostProcess")
    check("if (nativeFailure != null && nativeAttempted)" in retry, "a failed native composition is retried")
    check("disableNativeCompositor(nativeFailure)" in retry, "the retry sticks: the native compositor stays off")
    check(retry.count("composeOnce()") >= 1, "the retry composes again with the Kotlin compositor")
    check("private fun renderNativeLayersKotlin" in renderer and "): Boolean {" in slice_between(renderer, "private fun renderNativeLayersKotlin", "GLES20.glUseProgram(program2D)"),
          "renderNativeLayersKotlin reports whether it drew")
    check("data class RenderReport" in renderer, "render reports what it drew")
    check("val drewNothing: Boolean get() = layersDrawn <= 0 || compositedTexture <= 0" in renderer, "an empty frame is detectable by the caller")
    check("): RenderReport {" in slice_between(renderer, "  fun render(", "if (viewportWidth <= 0"), "render returns the report")
    check("var nativeCompositorEnabled: Boolean = true" in renderer, "the compositor can be switched off per render surface")
    check("var shaderTransitionsEnabled: Boolean = true" in renderer, "the ES3 transition engine can be switched off per render surface")
    check("if (!shaderTransitionsEnabled || !shaderTransitions.isAvailable()) return 0" in renderer,
          "a disabled transition engine falls back to the legacy single-texture path")
    transitions = read(TRANSITION)
    check("val glError = GLES20.glGetError()" in transitions and "using legacy path" in transitions,
          "a transition pass that left a GL error is reported as a failure, not success")
    check("): GpuCompositionRenderer.RenderReport = renderer.render(" in graph, "the shared render graph forwards the report")

    print("-- the export acts on it instead of muxing black frames --")
    check("val composed = CompositionRenderGraph.present(" in export, "the render loop keeps the composition report")
    check("if (composed.drewNothing) emptyFrames++ else composedFrames++" in export, "frames with no picture are counted")
    check("frameIndex in probeFrames && !composed.drewNothing" in export, "only frames that drew something are probed")
    check("ExportBlackFrameGuard.shouldAbortAttempt(" in export, "an all-black attempt is abandoned while rendering")
    check("failure = ExportFailure.ALL_FRAMES_BLACK" in export, "the black failure is tagged for the retry ladder")
    check("if (composedFrames == 0L && emptyFrames > 0L)" in export, "a clip that never drew anything is refused")
    check("ExportValidator.checkDecodedFrames(outputFile)" in export, "the muxed file's decoded frames are verified")
    check("ExportBlackFrameGuard.mayRetryBlackOutput(blackOutputAttempts)" in export, "a black output is retried a bounded number of times")
    check("ExportBlackFrameGuard.useNativeCompositor(attemptNo)" in export, "the retry switches compositor")
    check("rend.nativeCompositorEnabled = useNativeCompositor" in export, "the retry's renderer really uses the fallback compositor")
    check("fun checkDecodedFrames(file: File): FrameCheckResult" in validator, "the validator exposes a frames-only check")
    check("if (black == stats.size) ExportFailure.ALL_FRAMES_BLACK else null" in validator, "the frames-only check reports all-black output")

    print("-- the GPU case samples the fade where a correct fade is not black --")
    gpu_test = read(GPU_TEST)
    check("renderFade(0.25f)" in gpu_test and "renderFade(0.75f)" in gpu_test,
          "the never-blank assertion samples 25% and 75%, not the black midpoint")
    check("renderFade(0.5f)" in gpu_test and "behaves like a dissolve again" in gpu_test,
          "the 50% dip through black is pinned as intended behaviour")
    check("did not render at progress" in gpu_test, "a failed pass is asserted, not just logged")
    trans = read(TRANSITION)
    check("while (GLES20.glGetError() != GLES20.GL_NO_ERROR) {}" in trans,
          "pending GL errors are drained before the pass so the check is attributable to it")

    print("-- the policy itself is pure Kotlin and executable on a plain JVM --")
    check("import android" not in guard and "import androidx" not in guard, "ExportBlackFrameGuard has no android/androidx import")
    check("fun shouldAbortAttempt(" in guard and "fun probeFrames(" in guard and "fun mayRetryBlackOutput(" in guard, "the policy functions exist")
    check("ExportBlackFrameGuard.kt" in read(LOGIC_TESTS) and "ExportBlackFrameGuardTest.kt" in read(LOGIC_TESTS),
          "run_logic_tests.sh compiles the guard and its test")
    check(os.path.exists(GUARD_TEST), "the guard has a unit test")
    guard_test = read(GUARD_TEST)
    for name in ("probes cover the first frames", "abandoned only when every readable probe is black",
                 "dark intro or fade-in does not abort", "unavailable readbacks never count as black",
                 "retried once", "only the first attempt uses the native compositor"):
        check(name in guard_test, f"test covers: {name}")

    print()
    failures = [label for ok, label in RESULTS if not ok]
    for ok, label in RESULTS:
        print(f"  {'PASS' if ok else 'FAIL'}  {label}")
    print()
    print("=" * 78)
    print(f"RESULT: {len(RESULTS)} checks, {len(failures)} failures")
    print("=" * 78)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
