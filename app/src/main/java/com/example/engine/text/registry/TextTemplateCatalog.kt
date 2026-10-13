package com.example.engine.text.registry

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.data.firebase.FirebaseBootstrap
import com.example.domain.model.TextClip
import com.example.domain.plugin.PluginCategory
import com.example.engine.plugin.PluginManager
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.ute.model.PaintSpec
import com.ute.templates.TemplateEngine
import com.ute.templates.TextTemplate
import org.json.JSONArray
import org.json.JSONObject

/**
 * Loads real text templates from packaged JSON, installed plugins, and the public Firestore catalog.
 * The UI never hardcodes a catalog; uploaded templates are parsed into the same live registry.
 * Duplicate ids are skipped so assets, plugins, and Firestore entries cannot register twice.
 */
object TextTemplateCatalog {

  private const val TAG = "TextTemplateCatalog"
  private const val REMOTE_COLLECTION = "text_templates"
  const val ASSET_DIR = "text/templates"
  const val UTE_DIR = "ute/templates"

  @Volatile
  private var loaded = false
  @Volatile
  private var firestoreListener: ListenerRegistration? = null
  private val lock = Any()
  private val remoteLock = Any()
  private val mainHandler = Handler(Looper.getMainLooper())
  private val remoteTemplateIds = mutableSetOf<String>()

  /** Loads the packaged/plugin fallback without starting a network request. */
  fun ensureLocalLoaded(context: Context) {
    val app = context.applicationContext
    synchronized(lock) {
      if (!loaded || TextAssetRegistry.getTemplates().isEmpty()) {
        loadAssetPack(app, ASSET_DIR)
        loadUtePack(app)
        mergeInstalledPlugins(app)
        loaded = true
      }
    }
  }

  /** Loads local fallback styles and subscribes to live Firestore templates. */
  fun ensureLoaded(context: Context) {
    val app = context.applicationContext
    ensureLocalLoaded(app)
    startFirestoreSync(app)
  }

  fun reload(context: Context) {
    synchronized(lock) {
      loaded = false
    }
    ensureLocalLoaded(context)
  }

  fun parseObject(json: JSONObject): RegisteredTextTemplate? =
    parseNormalizedObject(normalizeTemplateObject(json, fallbackId = null))

  /**
   * Parses a Firestore document using its document ID as the stable app-side template ID.
   * Supports both flat fields (name/text/fontSizeSp) and common uploaded shapes such as
   * { title, content, style: { fontSize, color } } or { clip: { text, ... } }.
   */
  fun parseFirestoreDocument(documentId: String, data: Map<String, Any?>): RegisteredTextTemplate? {
    if (documentId.isBlank()) return null
    return runCatching {
      val source = JSONObject()
      data.forEach { (key, value) -> source.put(key, toJsonValue(value)) }
      parseNormalizedObject(normalizeTemplateObject(source, fallbackId = documentId))
    }.onFailure { error ->
      Log.w(TAG, "Skipped Firestore text template $documentId: ${error.message}")
    }.getOrNull()
  }

  private fun parseNormalizedObject(json: JSONObject): RegisteredTextTemplate? {
    val id = json.optString("id").trim()
    val name = json.optString("name").trim()
    if (id.isEmpty() || name.isEmpty()) return null
    val clipObj = json.optJSONObject("clip")
    val explicitText = sequenceOf(
      clipObj?.optString("text"),
      clipObj?.optString("content"),
      json.optString("text"),
      json.optString("content"),
      json.optString("defaultText")
    ).map { it?.trim().orEmpty() }.firstOrNull { it.isNotEmpty() }
    if (explicitText.isNullOrBlank()) return null
    val clip = if (clipObj != null) clipFromJson(clipObj, json) else clipFromJson(json, json)
    if (clip.text.isBlank()) return null
    val preview = sequenceOf("preview", "previewThumbnailUrl", "thumbnailUrl")
      .map { json.optString(it).trim() }
      .firstOrNull { it.isNotEmpty() }
    return RegisteredTextTemplate(
      id = id,
      name = name,
      category = json.optString("category", "3D").ifBlank { "3D" },
      templateClip = clip,
      previewThumbnailUrl = preview
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

  private val wrapperKeys = setOf("template", "templateData", "data")
  private val styleContainerKeys = listOf("style", "styles", "textStyle", "text_style", "properties")
  private val allContainerKeys = wrapperKeys + styleContainerKeys

  private fun normalizeTemplateObject(source: JSONObject, fallbackId: String?): JSONObject {
    val normalized = JSONObject()

    fun copyFields(from: JSONObject, overwrite: Boolean, excluded: Set<String> = emptySet()) {
      from.keys().asSequence().forEach { key ->
        if (key in excluded) return@forEach
        val value = from.opt(key) ?: return@forEach
        if (overwrite || !normalized.has(key) || normalized.isNull(key)) {
          normalized.put(key, value)
        }
      }
    }

    // Data nested under template/data is accepted, then style maps, then top-level values.
    // This gives explicitly-uploaded top-level fields the final say.
    wrapperKeys.forEach { key ->
      val parent = source.optJSONObject(key) ?: return@forEach
      copyFields(parent, overwrite = false, excluded = allContainerKeys)
      styleContainerKeys.forEach { styleKey ->
        parent.optJSONObject(styleKey)?.let { copyFields(it, overwrite = false) }
      }
    }
    styleContainerKeys.forEach { key ->
      source.optJSONObject(key)?.let { copyFields(it, overwrite = false) }
    }
    copyFields(source, overwrite = true, excluded = allContainerKeys)

    normalizeStyleAliases(normalized)
    normalized.optJSONObject("clip")?.let(::normalizeStyleAliases)

    val resolvedId = fallbackId?.takeIf { it.isNotBlank() } ?: stringValue(normalized, "id")
    if (!resolvedId.isNullOrBlank()) normalized.put("id", resolvedId)

    if (stringValue(normalized, "name").isNullOrBlank()) {
      val title = stringValue(normalized, "title")
      // Only Firestore documents can fall back to their document ID; asset/plugin parsing
      // continues to reject nameless templates as before.
      val name = title ?: fallbackId?.takeIf { it.isNotBlank() }
      if (!name.isNullOrBlank()) normalized.put("name", name)
    }

    val clip = normalized.optJSONObject("clip")
    if (stringValue(normalized, "text").isNullOrBlank()) {
      val text = sequenceOf(
        clip?.let { stringValue(it, "text") ?: stringValue(it, "content") },
        stringValue(normalized, "content"),
        stringValue(normalized, "defaultText")
      ).firstOrNull { !it.isNullOrBlank() }
      if (!text.isNullOrBlank()) normalized.put("text", text)
    }
    return normalized
  }

  private fun normalizeStyleAliases(json: JSONObject) {
    fun alias(canonical: String, vararg aliases: String) {
      if (hasScalarValue(json, canonical)) return
      aliases.asSequence()
        .mapNotNull { key -> json.opt(key)?.takeIf { hasScalarValue(it) } }
        .firstOrNull()
        ?.let { value -> json.put(canonical, value) }
    }

    alias("name", "title")
    alias("text", "content", "defaultText")
    alias("fontFamily", "font")
    alias("fontSizeSp", "fontSize")
    alias("textColor", "color", "textColorHex")
    alias("strokeColor", "strokeColorHex")
    alias("animationIn", "animation", "entranceAnimation")
    alias("preview", "previewThumbnailUrl", "thumbnailUrl")
    alias("is3D", "is3d")
  }

  private fun hasScalarValue(json: JSONObject, key: String): Boolean =
    hasScalarValue(json.opt(key))

  private fun hasScalarValue(value: Any?): Boolean {
    if (value == null || value === JSONObject.NULL || value is JSONObject || value is JSONArray) return false
    return value !is String || (value.isNotBlank() && value != "null")
  }

  private fun stringValue(json: JSONObject, key: String): String? {
    val value = json.opt(key) ?: return null
    if (value === JSONObject.NULL || value is JSONObject || value is JSONArray) return null
    return value.toString().trim().takeIf { it.isNotEmpty() && it != "null" }
  }

  private fun toJsonValue(value: Any?): Any = when (value) {
    null -> JSONObject.NULL
    is Map<*, *> -> JSONObject().apply {
      value.forEach { (key, nested) ->
        if (key != null) put(key.toString(), toJsonValue(nested))
      }
    }
    is Iterable<*> -> JSONArray().apply { value.forEach { put(toJsonValue(it)) } }
    is Array<*> -> JSONArray().apply { value.forEach { put(toJsonValue(it)) } }
    is String, is Number, is Boolean, is JSONObject, is JSONArray -> value
    else -> value.toString()
  }

  private fun startFirestoreSync(context: Context) {
    synchronized(lock) {
      if (firestoreListener != null) return
      try {
        FirebaseBootstrap.ensure(context)
        if (FirebaseApp.getApps(context).isEmpty()) return

        firestoreListener = FirebaseFirestore.getInstance()
          .collection(REMOTE_COLLECTION)
          .addSnapshotListener { snapshot, error ->
            if (error != null) {
              // Firestore listeners terminate on permission/configuration errors; allow a later
              // ensureLoaded() (for example after rules are deployed) to attach again.
              firestoreListener = null
              Log.w(TAG, "Firestore $REMOTE_COLLECTION listener failed: ${error.message}")
              return@addSnapshotListener
            }
            val documents = snapshot?.documents ?: return@addSnapshotListener
            val templates = documents.mapNotNull { document ->
              parseFirestoreDocument(document.id, document.data ?: emptyMap())
            }
            mainHandler.post { replaceRemoteTemplates(templates) }
          }
      } catch (error: Exception) {
        Log.w(TAG, "Could not start Firestore text-template sync: ${error.message}", error)
      }
    }
  }

  private fun replaceRemoteTemplates(templates: List<RegisteredTextTemplate>) {
    synchronized(remoteLock) {
      val nextById = templates.associateBy { it.id }
      val removedIds = remoteTemplateIds.filterNot { it in nextById.keys }
      removedIds.forEach(TextAssetRegistry::removeTemplate)
      remoteTemplateIds.removeAll(removedIds.toSet())

      // Reverse insertion at the front preserves Firestore's snapshot order while making
      // uploaded templates immediately visible above the packaged catalog in the grid.
      nextById.values.toList().asReversed().forEach { template ->
        val alreadyRemote = template.id in remoteTemplateIds
        val alreadyRegistered = TextAssetRegistry.getTemplates().any { it.id == template.id }
        // A Firestore item should not silently replace an asset/plugin template with the same id.
        if (alreadyRemote || !alreadyRegistered) {
          TextAssetRegistry.upsertTemplateAtFront(template)
          remoteTemplateIds.add(template.id)
        }
      }
      Log.d(TAG, "Loaded ${remoteTemplateIds.size} Firestore text templates")
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
      backgroundShape = source.backgroundShape,
      cornerRadius = source.cornerRadius,
      bgPadding = source.bgPadding,
      opacity = source.opacity,
      rotation = source.rotation,
      posX = source.posX,
      posY = source.posY,
      scale = source.scale,
      subtitleStyle = source.subtitleStyle,
      highlightColor = source.highlightColor,
      hasGlow = source.hasGlow,
      glowColor = source.glowColor,
      glowRadius = source.glowRadius,
      animationType = source.animationType,
      animationIn = source.animationIn,
      animationOut = source.animationOut,
      animDurationMs = source.animDurationMs,
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
      trueMesh3D = source.trueMesh3D,
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
      val clean = raw.trim().removePrefix("#").removePrefix("0x").removePrefix("0X")
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
    fun rawValue(key: String): Any? {
      val srcValue = src.opt(key)
      if (srcValue != null && srcValue !== JSONObject.NULL) return srcValue
      val rootValue = root.opt(key)
      return rootValue?.takeUnless { it === JSONObject.NULL }
    }

    fun color(key: String, fallback: Long): Long = when (val value = rawValue(key)) {
      is Number -> value.toLong() and 0xFFFFFFFFL
      null -> fallback
      else -> parseColor(value.toString(), fallback)
    }

    fun num(key: String, fallback: Double): Double = when (val value = rawValue(key)) {
      is Number -> value.toDouble()
      is String -> value.toDoubleOrNull() ?: fallback
      else -> fallback
    }

    fun bool(key: String, fallback: Boolean): Boolean = when (val value = rawValue(key)) {
      is Boolean -> value
      is String -> value.toBooleanStrictOrNull() ?: fallback
      else -> fallback
    }

    fun str(key: String, fallback: String): String =
      rawValue(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: fallback

    val is3D = bool(
      "is3D",
      src.has("material3D") || root.has("material3D") ||
        (src.has("depth3D") && src.optDouble("depth3D") > 0.0) ||
        (root.has("depth3D") && root.optDouble("depth3D") > 0.0)
    )

    return TextClip(
      text = str("text", str("defaultText", "Aa")),
      fontFamily = str("fontFamily", "sans-serif-black"),
      customFontPath = str("customFontPath", "").takeIf { it.isNotBlank() },
      fontSizeSp = num("fontSizeSp", 44.0).toFloat(),
      fontWeight = num("fontWeight", 800.0).toInt(),
      isItalic = bool("isItalic", false),
      isUnderline = bool("isUnderline", false),
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
      shadowBlur = num("shadowBlur", 4.0).toFloat(),
      shadowOffsetX = num("shadowOffsetX", 2.0).toFloat(),
      shadowOffsetY = num("shadowOffsetY", 2.0).toFloat(),
      hasBackground = bool("hasBackground", false),
      backgroundColor = color("backgroundColor", 0xAA000000),
      backgroundShape = str("backgroundShape", "Rounded"),
      cornerRadius = num("cornerRadius", 12.0).toFloat(),
      bgPadding = num("bgPadding", 16.0).toFloat(),
      opacity = num("opacity", 1.0).toFloat().coerceIn(0f, 1f),
      rotation = num("rotation", 0.0).toFloat(),
      posX = num("posX", 0.0).toFloat(),
      posY = num("posY", 0.35).toFloat(),
      scale = num("scale", 1.0).toFloat(),
      hasGlow = bool("hasGlow", false),
      glowColor = color("glowColor", 0xFF00E5FF),
      glowRadius = num("glowRadius", 10.0).toFloat(),
      animationType = str("animationType", str("animationIn", "None")),
      animationIn = str("animationIn", "None"),
      animationOut = str("animationOut", "Fade"),
      animDurationMs = num("animDurationMs", 1600.0).toLong().coerceAtLeast(200L),
      animationDelayMs = num("animationDelayMs", 0.0).toLong().coerceAtLeast(0L),
      animationEasing = str("animationEasing", "Ease Out"),
      subtitleStyle = str("subtitleStyle", "None"),
      highlightColor = color("highlightColor", 0xFFFFEB3B),
      is3D = is3D,
      depth3D = num("depth3D", if (is3D) 12.0 else 0.0).toFloat(),
      bevelAngle3D = num("bevelAngle3D", 24.0).toFloat(),
      color3D = color("color3D", 0xFF1E293B),
      bevelRadius3D = num("bevelRadius3D", 2.0).toFloat(),
      material3D = str("material3D", "matte"),
      lightPreset3D = str("lightPreset3D", "studio"),
      lightAngle3D = num("lightAngle3D", 315.0).toFloat(),
      lightIntensity3D = num("lightIntensity3D", 1.0).toFloat(),
      trueMesh3D = bool("trueMesh3D", false),
      animation3D = str("animation3D", "None"),
      effectStyle = str("effectStyle", "None")
    )
  }
}
