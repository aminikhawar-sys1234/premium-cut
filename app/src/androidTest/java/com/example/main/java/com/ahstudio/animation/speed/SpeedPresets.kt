package com.ahstudio.animation.speed

/**
 * Bridge between the app's [SpeedCurvePreset] names (kept as strings so this package stays Android/domain free)
 * and the continuous, monotone [SpeedRamp].  The app's old per-preset "factor" multiplied source *position* by a
 * step function, which made the video jump at the step edges; here the preset defines *speed* and position is its integral.
 */
object SpeedPresets {
    /** @return null for STANDARD / unknown (caller uses plain linear speed). */
    fun rampFor(preset: String, bezier: List<Float>? = null): SpeedRamp? = when (preset.uppercase()) {
        "EASE_IN" -> SpeedRamp.easeIn()
        "EASE_OUT" -> SpeedRamp.easeOut()
        "HERO_MONTAGE" -> SpeedRamp.montage()
        "BULLET_TIME" -> SpeedRamp.bulletTime()
        "JUMPER" -> SpeedRamp.jumper()
        "CUSTOM_BEZIER" -> {
            val p = if (bezier != null && bezier.size >= 4) bezier else listOf(0.42f, 0f, 0.58f, 1f)
            SpeedRamp.fromBezier(p[0].toDouble().coerceIn(0.0, 1.0), p[1].toDouble(), p[2].toDouble().coerceIn(0.0, 1.0), p[3].toDouble())
        }
        else -> null
    }
}
