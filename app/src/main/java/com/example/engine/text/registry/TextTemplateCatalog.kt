package com.example.engine.text.registry

import android.content.Context
import android.util.Log
import com.example.domain.model.TextClip
import com.example.domain.plugin.PluginCategory
import com.example.engine.plugin.PluginManager
import com.ute.model.PaintSpec
import com.ute.templates.TemplateEngine
import com.ute.templates.TextTemplate
import org.json.JSONArray
import org.json.JSONObject

/**
 * Loads real text templates from packaged JSON and installed plugins.
 * The UI never hardcodes a catalog — adding a template is adding a JSON file.
 * Duplicate ids are skipped so asset + plugin packs cannot register twice.
 */
object TextTemplateCatalog {

  private const val TAG = "TextTemplateCatalog"
  const val ASSET_DIR = "text/templates"
  const val UTE_DIR = "ute/templates"

  @Volatile
  private var loaded = false
  private val lock = Any()

  fun ensureLoaded(context: Context) {
    if (loaded && TextAssetRegistry.getTemplates().isNotEmpty()) return
    synchronized(lock) {
      if (loaded && TextAssetRegistry.getTemplates().isNotEmpty()) return
      val app = context.applicationContext
      loadAssetPack(app, ASSET_DIR)
      loadUtePack(app)
      mergeInstalledPlugins(app)
      loaded = true
    }
  }

  fun reload(context: Context) {
    synchronized(lock) {
      loaded = false
    }
    ensureLoaded(context)
  }

  fun parseObject(json: JSONObject): RegisteredTextTemplate? {
    val id = json.optString("id").trim()
    val name = json.optString("name", json.optString("title")).trim()
    if (id.isEmpty() || name.isEmpty()) return null
    val clipObj = json.optJSONObject("clip")
    val clip = if (clipObj != null) clipFromJson(clipObj, json) else clipFromJson(json, json)
    if (clip.text.isBlank()) return null
    return RegisteredTextTemplate(
      id = id,
      name = name,
      category = json.optString("category", "3D").ifBlank { "3D" },
      templateClip = clip,
      previewThumbnailUrl = json.optString("preview").takeIf { it.isNotBlank() }
    )
  }

  fun parseArray(json: String): List<RegisteredTextTemplate> {
    val trimmed = json.trim()
    if (trimmed.isEmpty()) return emptyList()
    return if (trimmed.startsWith("[")) {
      val arr = JSONArray(trimmed)
      (0 until arr.length()).mapNotNull { parseObject(arr.optJSONObject(it) ?: return@mapNotNull null) }
    } else {
      listOfNotNull(parseObject(JSONObject(trimmed)))
    }
  }

  fun applyStyle(target: TextClip, source: TextClip, keepText: Boolean): TextClip {
    val text = if (keepText && target.text.isNotBlank()) target.text else source.text
    return target.copy(
      text = text,
      fontFamily = source.fontFamily,
      customFontPath = source.customFontPath,
      fontSizeSp = source.fontSizeSp,
      fontWeight = source.fontWeight,
      isItalic = source.isItalic,
      isUnderline = source.isUnderline,
      isAllCaps = source.isAllCaps,
      alignment = source.alignment,
      letterSpacing = source.letterSpacing,
      lineSpacing = source.lineSpacing,
      textColor = source.textColor,
      hasGradient = source.hasGradient,
      gradientColorStart = source.gradientColorStart,
      gradientColorEnd = source.gradientColorEnd,
      gradientDirection = source.gradientDirection,
      strokeWidth = source.strokeWidth,
      strokeColor = source.strokeColor,
      hasShadow = source.hasShadow,
      shadowColor = source.shadowColor,
      shadowBlur = source.shadowBlur,
      shadowOffsetX = source.shadowOffsetX,
      shadowOffsetY = source.shadowOffsetY,
      hasBackground = source.hasBackground,
      backgroundColor = source.backgroundColor,
      cornerRadius = source.cornerRadius,
      bgPadding = source.bgPadding,
      hasGlow = source.hasGlow,
      glowColor = source.glowColor,
      glowRadius = source.glowRadius,
      animationType = source.animationType,
      animationIn = source.animationIn,
      animationOut = source.animationOut,
      animationDelayMs = source.animationDelayMs,
      animationEasing = source.animationEasing,
      is3D = source.is3D,
      depth3D = source.depth3D,
      bevelAngle3D = source.bevelAngle3D,
      color3D = source.color3D,
      bevelRadius3D = source.bevelRadius3D,
      material3D = source.material3D,
      lightPreset3D = source.lightPreset3D,
      lightAngle3D = source.lightAngle3D,
      lightIntensity3D = source.lightIntensity3D,
      animation3D = source.animation3D,
      effectStyle = source.effectStyle
    )
  }

  fun resetStyle(target: TextClip): TextClip = target.copy(
    fontFamily = "Default",
    customFontPath = null,
    fontWeight = 700,
    letterSpacing = 0f,
    strokeWidth = 0f,
    strokeColor = 0xFF000000,
    textColor = 0xFFFFFFFF,
    hasGradient = false,
    hasGlow = false,
    hasShadow = false,
    hasBackground = false,
    is3D = false,
    depth3D = 0f,
    bevelAngle3D = 0f,
    bevelRadius3D = 0f,
    color3D = 0xFF1E293B,
    material3D = "matte",
    lightPreset3D = "studio",
    lightAngle3D = 315f,
    lightIntensity3D = 1f,
    animationIn = "None",
    animationOut = "None",
    animation3D = "None",
    animationType = "None",
    effectStyle = "None"
  )

  fun matches(clip: TextClip, template: RegisteredTextTemplate): Boolean {
    val src = template.templateClip
    return clip.material3D == src.material3D &&
      clip.textColor == src.textColor &&
      clip.is3D == src.is3D &&
      clip.animationIn == src.animationIn &&
      clip.animation3D == src.animation3D
  }

  internal fun parseColor(raw: String?, fallback: Long): Long {
    if (raw.isNullOrBlank()) return fallback
    return try {
      val clean = raw.trim().removePrefix("#")
      val v = clean.toLong(16)
      if (clean.length <= 6) 0xFF000000L or v else v
    } catch (_: Exception) {
      fallback
    }
  }

  private fun loadAssetPack(context: Context, dir: String) {
    val names = runCatching { context.assets.list(dir)?.toList().orEmpty() }.getOrDefault(emptyList())
    names.sorted().forEach { file ->
      if (!file.endsWith(".json", ignoreCase = true)) return@forEach
      runCatching {
        context.assets.open("$dir/$file").bufferedReader().use { it.readText() }
      }.onSuccess { text ->
        parseArray(text).forEach { TextAssetRegistry.registerTemplate(it) }
      }.onFailure { err ->
        Log.w(TAG, "Skipped template file $file: ${err.message}")
      }
    }
  }

  private fun loadUtePack(context: Context) {
    runCatching {
      val engine = TemplateEngine(context)
      engine.loadFromAssets(UTE_DIR)
      engine.all().forEach { ute ->
        fromUte(ute)?.let { TextAssetRegistry.registerTemplate(it) }
      }
    }.onFailure { err ->
      Log.w(TAG, "UTE templates skipped: ${err.message}")
    }
  }

  private fun mergeInstalledPlugins(context: Context) {
    runCatching {
      PluginManager.initialize(context)
      PluginManager.getEnabledItemsForCategory(PluginCategory.TEXT_TEMPLATE).forEach { (plugin, item) ->
        val fromFile = plugin.getItemFile(item)?.takeIf { it.exists() }?.let { file ->
          runCatching { parseArray(file.readText()) }.getOrDefault(emptyList())
        }.orEmpty()
        if (fromFile.isNotEmpty()) {
          fromFile.forEach { TextAssetRegistry.registerTemplate(it) }
        } else {
          fromPluginItem(item)?.let { TextAssetRegistry.registerTemplate(it) }
        }
      }
    }.onFailure { err ->
      Log.w(TAG, "Plugin templates skipped: ${err.message}")
    }
  }

  private fun fromPluginItem(item: com.example.domain.plugin.PluginItemManifest): RegisteredTextTemplate? {
    if (item.id.isBlank() || item.name.isBlank()) return null
    val params = item.parameters
    val text = stringParam(params, "text", "sampleText", "sample", "defaultText")
      ?: item.name
    if (text.isBlank()) return null
    val json = JSONObject()
    params.forEach { (k, v) ->
      if (v is Number || v is Boolean || v is String) json.put(k, v)
    }
    json.put("id", item.id)
    json.put("name", item.name)
    json.put("category", item.parameters["category"]?.toString() ?: "3D")
    json.put("text", text)
    return parseObject(json)
  }

  private fun stringParam(params: Map<String, Any>, vararg keys: String): String? {
    keys.forEach { key ->
      val value = params[key]?.toString()?.trim()
      if (!value.isNullOrBlank()) return value
    }
    return null
  }

  private fun fromUte(template: TextTemplate): RegisteredTextTemplate? {
    if (template.id.isBlank() || template.id == "invalid" || template.name.isBlank()) return null
    if (template.defaultText.isBlank()) return null
    val fill = when (val paint = template.appearance.fill) {
      is PaintSpec.Solid -> paint.color.toLong() and 0xFFFFFFFFL
      is PaintSpec.Material -> paint.tint.toLong() and 0xFFFFFFFFL
      is PaintSpec.Gradient -> paint.gradient.stops.firstOrNull()?.color?.toLong()?.and(0xFFFFFFFFL) ?: 0xFFFFFFFFL
    }
    val outline = template.appearance.outline
    val threeD = template.threeD
    val glow = template.appearance.glow
    val clip = TextClip(
      text = template.defaultText,
      fontFamily = template.style.fontFamily,
      fontSizeSp = (template.style.sizePx * 0.38f).coerceIn(16f, 42f),
      fontWeight = template.style.weight.cssValue,
      isItalic = template.style.italic,
      letterSpacing = template.style.letterSpacingEm,
      lineSpacing = template.style.lineHeightMultiple,
      textColor = fill,
      strokeWidth = outline?.widthPx ?: 0f,
      strokeColor = outline?.color?.toLong()?.and(0xFFFFFFFFL) ?: 0xFF000000,
      hasGlow = glow != null,
      glowColor = glow?.color?.toLong()?.and(0xFFFFFFFFL) ?: 0xFF00E5FF,
      glowRadius = glow?.radiusPx ?: 10f,
      animationIn = template.motion?.preset?.name?.replace('_', ' ')?.lowercase() ?: "None",
      is3D = threeD != null,
      depth3D = threeD?.extrusionDepthPx ?: 0f,
      bevelRadius3D = threeD?.bevelWidthPx ?: 0f,
      material3D = threeD?.materialId ?: "matte",
      color3D = threeD?.materialTint?.toLong()?.and(0xFFFFFFFFL) ?: 0xFF1E293B
    )
    return RegisteredTextTemplate(
      id = template.id,
      name = template.name,
      category = template.category.name,
      templateClip = clip
    )
  }

  private fun clipFromJson(src: JSONObject, root: JSONObject): TextClip {
    fun color(key: String, fallback: Long): Long =
      parseColor(src.optString(key).ifBlank { root.optString(key) }, fallback)

    fun num(key: String, fallback: Double): Double {
      if (src.has(key)) return src.optDouble(key, fallback)
      if (root.has(key)) return root.optDouble(key, fallback)
      return fallback
    }

    fun bool(key: String, fallback: Boolean): Boolean {
      if (src.has(key)) return src.optBoolean(key, fallback)
      if (root.has(key)) return root.optBoolean(key, fallback)
      return fallback
    }

    fun str(key: String, fallback: String): String {
      val v = src.optString(key).ifBlank { root.optString(key) }
      return v.ifBlank { fallback }
    }

    return TextClip(
      text = str("text", str("defaultText", "Aa")),
      fontFamily = str("fontFamily", "sans-serif-black"),
      customFontPath = str("customFontPath", "").takeIf { it.isNotBlank() },
      fontSizeSp = num("fontSizeSp", 28.0).toFloat(),
      fontWeight = num("fontWeight", 800.0).toInt(),
      isItalic = bool("isItalic", false),
      isAllCaps = bool("isAllCaps", false),
      alignment = str("alignment", "Center"),
      letterSpacing = num("letterSpacing", 0.0).toFloat(),
      lineSpacing = num("lineSpacing", 1.0).toFloat(),
      textColor = color("textColor", 0xFFFFFFFF),
      hasGradient = bool("hasGradient", false),
      gradientColorStart = color("gradientColorStart", 0xFF00E5FF),
      gradientColorEnd = color("gradientColorEnd", 0xFF8B5CF6),
      gradientDirection = str("gradientDirection", "Horizontal"),
      strokeWidth = num("strokeWidth", 0.0).toFloat(),
      strokeColor = color("strokeColor", 0xFF000000),
      hasShadow = bool("hasShadow", false),
      shadowColor = color("shadowColor", 0x88000000),
      hasGlow = bool("hasGlow", false),
      glowColor = color("glowColor", 0xFF00E5FF),
      glowRadius = num("glowRadius", 10.0).toFloat(),
      animationType = str("animationType", str("animationIn", "None")),
      animationIn = str("animationIn", "None"),
      animationOut = str("animationOut", "Fade"),
      is3D = bool(
        "is3D",
        src.has("material3D") || root.has("material3D") || src.has("depth3D") || root.has("depth3D")
      ),
      depth3D = num("depth3D", 12.0).toFloat(),
      bevelAngle3D = num("bevelAngle3D", 24.0).toFloat(),
      color3D = color("color3D", 0xFF1E293B),
      bevelRadius3D = num("bevelRadius3D", 2.0).toFloat(),
      material3D = str("material3D", "matte"),
      lightPreset3D = str("lightPreset3D", "studio"),
      lightAngle3D = num("lightAngle3D", 315.0).toFloat(),
      lightIntensity3D = num("lightIntensity3D", 1.0).toFloat(),
      animation3D = str("animation3D", "None"),
      effectStyle = str("effectStyle", "None")
    )
  }
}
