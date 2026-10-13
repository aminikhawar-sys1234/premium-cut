package com.example.engine.text

/**
 * Motion presets that [TextLayerRenderer] actually evaluates.
 * No unused display names and no plugin placeholders.
 */
object TextMotionCatalog {

  data class Preset(val key: String, val label: String)

  val ENTRANCES = listOf(
    Preset("None", "None"),
    Preset("Fade", "Fade"),
    Preset("Slide Up", "Slide Up"),
    Preset("Slide Down", "Slide Down"),
    Preset("Slide Left", "Slide Left"),
    Preset("Slide Right", "Slide Right"),
    Preset("Zoom", "Zoom"),
    Preset("Pop", "Pop"),
    Preset("Bounce", "Bounce"),
    Preset("Typewriter", "Typewriter"),
    Preset("Reveal", "Reveal"),
    Preset("Shake", "Shake"),
    Preset("Glow", "Glow"),
    Preset("Neon", "Neon"),
    Preset("Glitch", "Glitch"),
    Preset("Cinematic", "Cinematic"),
    Preset("Elastic", "Elastic")
  )

  val EXITS = listOf(
    Preset("None", "None"),
    Preset("Fade Out", "Fade"),
    Preset("Zoom Out", "Zoom"),
    Preset("Slide Up", "Slide Up"),
    Preset("Slide Down", "Slide Down"),
    Preset("Pop", "Pop")
  )

  val EFFECTS = listOf(
    Preset("None", "None"),
    Preset("glitch", "Glitch"),
    Preset("neon", "Neon"),
    Preset("chrome", "Chrome"),
    Preset("fire", "Fire"),
    Preset("rainbow", "Rainbow"),
    Preset("holographic", "Holo"),
    Preset("gold", "Gold"),
    Preset("comic", "Comic")
  )

  fun isNone(value: String?): Boolean {
    val v = value?.trim().orEmpty()
    return v.isEmpty() || v.equals("none", true) || v.equals("static", true)
  }

  fun resolveEntrance(animationType: String, animationIn: String): String {
    if (!isNone(animationType)) return animationType.trim()
    if (!isNone(animationIn)) return animationIn.trim()
    return "None"
  }
}
