#!/usr/bin/env bash
# Compiles and RUNS the pure-Kotlin logic tests (chroma key, mask decoding, preview policy) on a plain JVM.
#
# Why this exists: the Android unit tests need Gradle + the Android SDK + a Maven mirror for androidx /
# ML Kit / Media3. In a sandbox that has none of those, this script still executes the real test files
# against the real production sources - the only difference is the JUnit runner and a `kotlinc` binary.
#
# Requirements: a JDK 17+ and kotlinc 2.x on PATH (or $KOTLINC / $JAVA_HOME pointing at them).
#   java   -> $JAVA_HOME/bin/java
#   kotlinc-> $KOTLINC (default: `kotlinc` from PATH)
#
# Usage: toolchain/verify/run_logic_tests.sh [--keep]
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MAIN="$REPO_ROOT/app/src/main/java"
TEST="$REPO_ROOT/app/src/test/java"
HARNESS="$REPO_ROOT/toolchain/verify/kotlin_logic"
OUT="${TMPDIR:-/tmp}/ahstudio-logic-tests"
JAVA_BIN="${JAVA:-${JAVA_HOME:+$JAVA_HOME/bin/java}}"
JAVA_BIN="${JAVA_BIN:-java}"
KOTLINC_BIN="${KOTLINC:-kotlinc}"

command -v "$JAVA_BIN" >/dev/null || { echo "java not found (set JAVA_HOME or JAVA)"; exit 2; }
command -v "$KOTLINC_BIN" >/dev/null || { echo "kotlinc not found (set KOTLINC)"; exit 2; }

rm -rf "$OUT"; mkdir -p "$OUT"

SOURCES=(
  # production: pure Kotlin only (no android.* / androidx.* imports)
  "$MAIN/com/example/domain/model/TimelineModels.kt"
  "$MAIN/com/example/domain/model/ProjectSettings.kt"
  "$MAIN/com/example/domain/model/ClipMotion.kt"
  "$MAIN/com/ahstudio/animation/math/AnimMath.kt"
  "$MAIN/com/ahstudio/animation/curves/Bezier.kt"
  "$MAIN/com/ahstudio/animation/speed/SpeedRamp.kt"
  "$MAIN/com/ahstudio/animation/speed/SpeedPresets.kt"
  "$MAIN/com/example/engine/composition/ChromaKeyMath.kt"
  "$MAIN/com/example/engine/composition/ChromaKeyResolver.kt"
  "$MAIN/com/example/engine/composition/CompositionLookResolver.kt"
  "$MAIN/com/example/engine/effects/media3/PreviewGraphDecision.kt"
  "$MAIN/com/example/engine/ai/cutout/MaskDecoding.kt"
  "$MAIN/com/example/engine/preview/PreviewCompositionPolicy.kt"
  "$MAIN/com/example/engine/export/ExportSurfaceSize.kt"
  "$MAIN/com/example/engine/export/ExportBlackFrameGuard.kt"
  "$MAIN/com/example/engine/composition/gpu/GpuShaders.kt"
  "$MAIN/com/ahstudio/color/primary/PipelineConsts.kt"
  "$MAIN/com/ahstudio/color/gpu/ColorShaderSource.kt"
  # tests
  "$TEST/com/example/engine/export/ExportSurfaceSizeTest.kt"
  "$TEST/com/example/engine/export/ExportBlackFrameGuardTest.kt"
  "$TEST/com/example/engine/composition/gpu/GpuSamplerContractTest.kt"
  "$TEST/com/ahstudio/color/gpu/ColorVariantShaderContractTest.kt"
  "$TEST/com/example/engine/composition/ChromaKeyMathTest.kt"
  "$TEST/com/example/engine/composition/ChromaKeyResolverTest.kt"
  "$TEST/com/example/engine/composition/CompositionLookResolverTest.kt"
  "$TEST/com/example/engine/effects/media3/PreviewGraphDecisionTest.kt"
  "$TEST/com/example/engine/ai/cutout/MaskDecodingTest.kt"
  "$TEST/com/example/engine/preview/PreviewCompositionPolicyTest.kt"
  # harness
  "$HARNESS/JUnitShim.kt"
  "$HARNESS/TestRunner.kt"
)

# kotlinc lives in a distribution: <home>/bin/kotlinc with the stdlib in <home>/lib.
KOTLINC_REAL="$(readlink -f "$(command -v "$KOTLINC_BIN")")"
KOTLIN_HOME="$(cd "$(dirname "$KOTLINC_REAL")/.." && pwd)"
STDLIB="$KOTLIN_HOME/lib/kotlin-stdlib.jar"
[ -f "$STDLIB" ] || { echo "kotlin-stdlib.jar not found next to kotlinc ($STDLIB)"; exit 2; }

echo "== compiling ${#SOURCES[@]} files with $KOTLINC_BIN"
"$KOTLINC_BIN" -nowarn -jvm-target 17 -d "$OUT/classes.jar" "${SOURCES[@]}"

echo "== running tests"
"$JAVA_BIN" -cp "$OUT/classes.jar:$STDLIB" \
  com.example.toolchain.TestRunner \
  com.example.engine.composition.ChromaKeyMathTest \
  com.example.engine.composition.ChromaKeyResolverTest \
  com.example.engine.composition.CompositionLookResolverTest \
  com.example.engine.effects.media3.PreviewGraphDecisionTest \
  com.example.engine.ai.cutout.MaskDecodingTest \
  com.example.engine.preview.PreviewCompositionPolicyTest \
  com.example.engine.export.ExportSurfaceSizeTest \
  com.example.engine.export.ExportBlackFrameGuardTest \
  com.example.engine.composition.gpu.GpuSamplerContractTest \
  com.ahstudio.color.gpu.ColorVariantShaderContractTest
