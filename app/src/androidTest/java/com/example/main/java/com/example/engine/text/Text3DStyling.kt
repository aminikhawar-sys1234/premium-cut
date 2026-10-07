package com.example.engine.text

import com.example.domain.model.TextClip
import com.ute.model.Text3DConfig
import com.ute.scene3d.Light
import com.ute.scene3d.LightRig
import com.ute.scene3d.TextMaterial
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Single place that turns the 3D fields on [TextClip] into:
 *  - com.ute.scene3d material / light rig / Text3DConfig (for the GL mesh pipeline), and
 *  - per-slice shading factors for the Canvas extrusion preview in [TextLayerRenderer].
 * Both paths read the same presets, so UI -> preview -> GL stay consistent.
 */
object Text3DStyling {

  data class LightPreset(
    val id: String, val label: String,
    val ambient: Float, val key: Float, val rim: Float, val fill: Float = 0f
  )

  val LIGHT_PRESETS: List<LightPreset> = listOf(
    LightPreset("studio", "Studio", ambient = 0.35f, key = 1.0f, rim = 0.5f),
    LightPreset("soft", "Soft", ambient = 0.65f, key = 0.55f, rim = 0.2f, fill = 0.3f),
    LightPreset("dramatic", "Dramatic", ambient = 0.12f, key = 1.6f, rim = 0.3f),
    LightPreset("rim", "Rim glow", ambient = 0.2f, key = 0.6f, rim = 1.4f),
    LightPreset("flat", "Flat", ambient = 1.0f, key = 0.0f, rim = 0f),
  )

  val MATERIAL_IDS: List<String> = TextMaterial.PRESETS.keys.toList()

  fun lightPreset(id: String): LightPreset = LIGHT_PRESETS.firstOrNull { it.id == id } ?: LIGHT_PRESETS[0]
  fun material(id: String): TextMaterial = TextMaterial.PRESETS[id] ?: TextMaterial.PRESETS.getValue("matte")

  // ---------- GL pipeline bridge ----------

  fun toText3DConfig(clip: TextClip, canvasScale: Float = 1f): Text3DConfig = Text3DConfig(
    extrusionDepthPx = clip.depth3D * canvasScale,
    bevelWidthPx = clip.bevelRadius3D * canvasScale,
    bevelSteps = if (clip.bevelRadius3D > 0f) 3 else 0,
    materialId = clip.material3D,
    materialTint = clip.color3D.toInt(),
  )

  fun toLightRig(clip: TextClip): LightRig {
    val p = lightPreset(clip.lightPreset3D)
    val a = Math.toRadians(clip.lightAngle3D.toDouble())
    val k = (p.key * clip.lightIntensity3D).coerceIn(0f, 4f)
    val lights = ArrayList<Light>()
    lights += Light.Ambient(intensity = p.ambient)
    if (k > 0f) lights += Light.Directional(
      // lightAngle3D is a screen angle (y-down); GL world is y-up, so the Y component flips sign.
      // dir points FROM the light TO the scene, hence the leading minus on X.
      intensity = k, dirX = -cos(a).toFloat(), dirY = sin(a).toFloat(), dirZ = -1f
    )
    if (p.fill > 0f) lights += Light.Point(intensity = p.fill)
    if (p.rim > 0f) lights += Light.Rim(intensity = p.rim)
    return LightRig(lights)
  }

  // ---------- Canvas preview shading ----------

  /**
   * Brightness multiplier (0..~1.6) for extrusion slice [d] of [steps]
   * (d = steps is the farthest/back slice, d = 1 is just behind the front face).
   * [extrudeAngleRad] is the direction the extrusion travels on screen.
   */
  fun sliceShade(clip: TextClip, d: Int, steps: Int, extrudeAngleRad: Double, lightShiftDeg: Float = 0f): Float {
    val m = material(clip.material3D)
    val l = lightPreset(clip.lightPreset3D)
    val t = d.toFloat() / steps.coerceAtLeast(1)            // 1 = back, 0 = front
    val lightRad = Math.toRadians((clip.lightAngle3D + lightShiftDeg).toDouble())
    // Side faces point roughly perpendicular to the extrusion direction; the light grazes them
    // more when its azimuth lines up with it.
    val facing = (0.5 + 0.5 * cos(lightRad - extrudeAngleRad)).toFloat()
    val key = l.key * clip.lightIntensity3D
    var shade = l.ambient * 0.6f + key * (0.35f + 0.65f * facing) * (1f - 0.45f * t)
    shade += l.rim * m.rimStrength * 0.5f * t                 // fresnel-ish rim toward the back edge
    shade += l.fill * 0.25f

    // Specular: narrow band for low roughness, wide for high; metallic gets strong banding.
    val shininess = (1f - m.roughness).coerceIn(0f, 1f)
    if (shininess > 0.05f) {
      val band = (sin(t * PI * (2.0 + 3.0 * m.metallic)).toFloat()).coerceAtLeast(0f)
      shade += band.pow(1f + 6f * shininess) * shininess * (0.5f + 0.7f * m.metallic) * max(key, 0.3f)
    }
    shade += m.emissiveStrength * 0.35f
    return shade.coerceIn(0.12f, 1.6f)
  }

  /** Alpha multiplier for the extrusion (glass is see-through). */
  fun extrusionAlpha(clip: TextClip): Float = material(clip.material3D).opacity.coerceIn(0.2f, 1f)

  /** Whether the front face should get a gloss tint pass. */
  fun frontGloss(clip: TextClip): Float {
    val m = material(clip.material3D)
    return ((1f - m.roughness) * 0.35f + m.glassiness * 0.2f).coerceIn(0f, 0.5f)
  }
}
