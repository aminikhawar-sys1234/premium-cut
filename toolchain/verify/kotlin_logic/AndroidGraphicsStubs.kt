// LOCAL VERIFICATION ONLY - not part of the app.
//
// The logic-test harness compiles production Kotlin without the Android SDK, so the handful of
// `android.graphics` types that a *pure* production file happens to mention have to exist for the
// compiler. This file declares the minimum shape of the two that the audio-mix chain reaches
// (`MotionTrackingModels.kt` -> RectF, `MotionTrackingEvaluator.kt` -> Matrix).
//
// WARNING: these are shapes, not behaviour. Matrix here does no matrix maths and RectF does no
// clipping. Nothing asserted by the logic tests may depend on these semantics - they are here so
// that code which never touches them still compiles. On a real device (and in `./gradlew test`)
// the framework classes are the real ones.
package android.graphics

/** Shape-only stand-in for `android.graphics.RectF`. */
class RectF(var left: Float = 0f, var top: Float = 0f, var right: Float = 0f, var bottom: Float = 0f) {
  val width: Float get() = right - left
  val height: Float get() = bottom - top
  val centerX: Float get() = (left + right) / 2f
  val centerY: Float get() = (top + bottom) / 2f
}

/** Shape-only stand-in for `android.graphics.Matrix` (no matrix maths is performed). */
class Matrix {
  fun postTranslate(dx: Float, dy: Float): Boolean = true
  fun postScale(sx: Float, sy: Float): Boolean = true
  fun postRotate(degrees: Float): Boolean = true
  fun setPolyToPoly(src: FloatArray, srcIndex: Int, dst: FloatArray, dstIndex: Int, pointCount: Int): Boolean = true
}
