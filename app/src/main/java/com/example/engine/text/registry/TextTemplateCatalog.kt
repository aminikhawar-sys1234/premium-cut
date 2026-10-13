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
import com.google.firebase.storage.FirebaseStorage
import com.ute.model.PaintSpec
import com.ute.templates.TemplateEngine
import com.ute.templates.TextTemplate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Download state of the Firebase (Firestore + Storage) text-template catalog. */
sealed interface TextTemplateSyncState {
  /** No Firebase download has been attempted yet. */
  data object Idle : TextTemplateSyncState

  /** A Firebase download / live sync is in progress. */
  data object Loading : TextTemplateSyncState

  /** Firebase answered; [remoteCount] cloud templates are merged into the registry. */
  data class Ready(val remoteCount: Int, val updatedAtMs: Long) : TextTemplateSyncState

  /** Firebase could not be reached; built-in templates keep working and Retry re-downloads. */
  data class Error(val message: String) : TextTemplateSyncState
}

/**
 * Loads real text templates from packaged JSON, installed plugins, and Firebase.
 *
 * Firebase sources, all merged into the same live [TextAssetRegistry] (cloud entries first):
 *  1. Firestore collection `text_templates` (plus the `textTemplates` alias collection) —
 *     live snapshot listeners plus a one-shot download on every panel open / refresh.
 *  2. Firebase Storage catalog files (`text/templates/catalog.json` and aliases) —
 *     a JSON array in the exact format of `assets/text/templates/catalog.json`.
 *
 * The UI never hardcodes a catalog; uploaded templates are parsed into the same live registry.
 * Duplicate ids are skipped so assets, plugins, and cloud entries cannot register twice.
 */
object TextTemplateCatalog {

  private const val TAG = "TextTemplateCatalog"
  private const val REMOTE_COLLECTION = "text_templates"
  private const val REMOTE_COLLECTION_ALIAS = "textTemplates"
  private val REMOTE_COLLECTIONS = listOf(REMOTE_COLLECTION, REMOTE_COLLECTION_ALIAS)

  /** Well-known Firebase Storage paths for a downloadable JSON text-template catalog. */
  val STORAGE_CATALOG_PATHS = listOf(
    "text/templates/catalog.json",
    "text_templates/catalog.json",
    "text-templates/catalog.json",
    "text/templates.json"
  )
  private const val STORAGE_CATALOG_MAX_BYTES = 4L * 1024L * 1024L

  /** Preview text used for style-only cloud templates that carry no sample text. */
  const val DEFAULT_PREVIEW_TEXT = "Aa"
  const val ASSET_DIR = "text/templates"
  const val UTE_DIR = "ute/templates"

  @Volatile
  private var loaded = false
  private val lock = Any()
  private val remoteLock = Any()
  private val mainHandler = Handler(Looper.getMainLooper())
  private val firestoreListeners = mutableMapOf<String, ListenerRegistration>()

  // Remote bookkeeping is per source key ("firestore:<collection>", "storage:<path>") so
  // one source refreshing can never wipe the templates another source provided.
  private val remoteIdsBySource = mutableMapOf<String, MutableSet<String>>()

  private fun firestoreSourceKey(collection: String) = "firestore:$collection"
  private fun storageSourceKey(path: String) = "storage:$path"

  @Volatile
  private var storageFetchAttempted = false

  private val _syncState = MutableStateFlow<TextTemplateSyncState>(TextTemplateSyncState.Idle)

  /** Observable Firebase download state for the text-template panels. */
  val syncState: StateFlow<TextTemplateSyncState> = _syncState.asStateFlow()

  /** How many Firebase (Firestore + Storage) templates are currently merged in. */
  fun remoteTemplateCount(): Int = synchronized(remoteLock) {
    remoteIdsBySource.values.flatten().toSet().size
  }

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

  /** Loads local fallback styles, subscribes to live Firestore templates, and downloads the cloud catalog. */
  fun ensureLoaded(context: Context) {
    val app = context.applicationContext
    ensureLocalLoaded(app)
    startFirestoreSync(app)
    refreshRemoteTemplates(app)
  }

  /**
   * One-shot Firebase download of every cloud text template (Firestore `get()` for both
   * collections + the Storage catalog JSON). Safe to call on every panel open and from the
   * Retry button: results are merged into the registry and [syncState] is updated so the UI
   * can show downloading / loaded / error states. Live snapshot listeners keep the catalog
   * updated afterwards, so templates uploaded later appear without restarting the app.
   */
  fun refreshRemoteTemplates(context: Context) {
    val app = context.applicationContext
    ensureLocalLoaded(app)
    startFirestoreSync(app)
    downloadStorageCatalog(app, force = true)
    try {
      FirebaseBootstrap.ensure(app)
      if (FirebaseApp.getApps(app).isEmpty()) {
        markSyncError("Firebase is not configured for this build")
        return
      }
      if (_syncState.value !is TextTemplateSyncState.Ready) {
        _syncState.value = TextTemplateSyncState.Loading
      }
      val db = FirebaseFirestore.getInstance()
      REMOTE_COLLECTIONS.forEach { collection ->
        db.collection(collection).get()
          .addOnSuccessListener { snapshot ->
            val templates = templatesFromDocuments(snapshot.documents.map { it.id to (it.data ?: emptyMap()) })
            mainHandler.post {
              replaceRemoteTemplates(firestoreSourceKey(collection), templates)
              markSyncReady()
            }
          }
          .addOnFailureListener { error ->
            Log.w(TAG, "Firestore $collection download failed: ${error.message}")
            // Keep live listeners as a fallback; only surface an error when nothing remote loaded.
            if (remoteTemplateCount() == 0) {
              markSyncError(error.message ?: "Could not download text templates from Firebase")
            }
          }
      }
    } catch (error: Exception) {
      Log.w(TAG, "Could not download Firestore text templates: ${error.message}", error)
      if (remoteTemplateCount() == 0) {
        markSyncError(error.message ?: "Could not download text templates from Firebase")
      }
    }
  }

  fun reload(context: Context) {
    synchronized(lock) {
      loaded = false
    }
    ensureLocalLoaded(context)
  }

  private fun markSyncReady() {
    _syncState.value = TextTemplateSyncState.Ready(
      remoteCount = remoteTemplateCount(),
      updatedAtMs = System.currentTimeMillis()
    )
  }

  private fun markSyncError(message: String) {
    _syncState.value = TextTemplateSyncState.Error(message)
  }

  /**
   * Parses Firestore documents into templates, ordered by the optional
   * `sortOrder` / `order` / `priority` / `displayOrder` field when uploads provide one.
   */
  internal fun templatesFromDocuments(
    documents: List<Pair<String, Map<String, Any?>>>
  ): List<RegisteredTextTemplate> {
    val sorted = documents.sortedBy { (_, data) -> sortKeyOf(data) ?: Double.MAX_VALUE }
    return sorted.flatMap { (id, data) -> parseFirestoreDocuments(id, data) }
  }

  private fun sortKeyOf(data: Map<String, Any?>): Double? {
    for (key in listOf("sortOrder", "order", "priority", "displayOrder")) {
      when (val value = data[key]) {
        is Number -> return value.toDouble()
        is String -> value.toDoubleOrNull()?.let { return it }
      }
    }
    return null
  }

  /**
   * Downloads a JSON text-template catalog from Firebase Storage (same array format as
   * `assets/text/templates/catalog.json`). Missing files are ignored — Firestore remains
   * the primary source — but a found catalog is merged at the top of the panel grid.
   */
  private fun downloadStorageCatalog(context: Context, force: Boolean) {
    if (storageFetchAttempted && !force) return
    storageFetchAttempted = true
    try {
      FirebaseBootstrap.ensure(context)
      if (FirebaseApp.getApps(context).isEmpty()) return
      val storage = FirebaseStorage.getInstance()
      STORAGE_CATALOG_PATHS.forEach { path ->
        runCatching {
          storage.reference.child(path).getBytes(STORAGE_CATALOG_MAX_BYTES)
            .addOnSuccessListener { bytes ->
              val templates = runCatching {
                parseArray(String(bytes, Charsets.UTF_8)).map { it.copy(isRemote = true) }
              }.getOrElse { error ->
                Log.w(TAG, "Skipped Storage text-template catalog $path: ${error.message}")
                emptyList()
              }
              if (templates.isNotEmpty()) {
                mainHandler.post {
                  replaceRemoteTemplates(storageSourceKey(path), templates)
                  markSyncReady()
                }
                Log.d(TAG, "Downloaded ${templates.size} text templates from Storage $path")
              }
            }
            .addOnFailureListener { error ->
              Log.d(TAG, "No Storage text-template catalog at $path: ${error.message}")
            }
        }.onFailure { error ->
          Log.w(TAG, "Storage text-template catalog $path skipped: ${error.message}")
        }
      }
    } catch (error: Exception) {
      Log.w(TAG, "Could not download Storage text templates: ${error.message}", error)
    }
  }

  fun parseObject(json: JSONObject): RegisteredTextTemplate? =
    parseNormalizedObject(normalizeTemplateObject(json, fallbackId = null))

  /**
   * Parses a Firestore document using its document ID as the stable app-side template ID.
   * Supports both flat fields (name/text/fontSizeSp) and common uploaded shapes such as
   * { title, content, style: { fontSize, color } }, { clip: { text, ... } },
   * { templateClip: { ... } }, a JSON-string payload ({ templateJson: "{...}" }), and
   * style-only documents (no sample text — the preview falls back to [DEFAULT_PREVIEW_TEXT]).
   * Use [parseFirestoreDocuments] when a document may hold a whole catalog.
   */
  fun parseFirestoreDocument(documentId: String, data: Map<String, Any?>): RegisteredTextTemplate? =
    parseFirestoreDocuments(documentId, data).firstOrNull()

  /**
   * Parses one Firestore document into zero or more templates. A single document may also
   * be an uploaded catalog — { templates: [ {...}, {...} ] } — in which case every entry is
   * returned. Documents explicitly disabled (enabled/published/visible/isActive = false, or
   * status draft/archived/hidden) are skipped so unpublished uploads never leak into the app.
   */
  fun parseFirestoreDocuments(documentId: String, data: Map<String, Any?>): List<RegisteredTextTemplate> {
    if (documentId.isBlank()) return emptyList()
    if (isExplicitlyHidden(data)) return emptyList()
    return runCatching {
      val source = JSONObject()
      data.forEach { (key, value) -> source.put(key, toJsonValue(value)) }

      // Catalog document: one Firestore doc holding an array of templates.
      val catalogArray = sequenceOf("templates", "items", "textTemplates", "templatesList")
        .mapNotNull { source.optJSONArray(it) }
        .firstOrNull()
      if (catalogArray != null) {
        return@runCatching (0 until catalogArray.length()).mapNotNull { index ->
          val element = catalogArray.optJSONObject(index) ?: return@mapNotNull null
          val elementId = element.optString("id").trim()
            .ifBlank { "$documentId-${index + 1}" }
          parseFirestoreElement(element, fallbackId = elementId)
        }
      }

      // JSON-string payload: the whole template (or catalog) stored as a string field.
      val payload = sequenceOf("templateJson", "json", "payload", "data", "template")
        .mapNotNull { key -> (data[key] as? String)?.trim()?.takeIf { it.startsWith("{") || it.startsWith("[") } }
        .firstOrNull()
      if (payload != null) {
        return@runCatching if (payload.startsWith("[")) {
          val array = JSONArray(payload)
          (0 until array.length()).mapNotNull { index ->
            val element = array.optJSONObject(index) ?: return@mapNotNull null
            val elementId = element.optString("id").trim().ifBlank { "$documentId-${index + 1}" }
            parseFirestoreElement(element, fallbackId = elementId)
          }
        } else {
          listOfNotNull(parseFirestoreElement(JSONObject(payload), fallbackId = documentId))
        }
      }

      listOfNotNull(parseFirestoreElement(source, fallbackId = documentId))
    }.onFailure { error ->
      Log.w(TAG, "Skipped Firestore text template $documentId: ${error.message}")
    }.getOrDefault(emptyList())
  }

  /**
   * Parses a single template object coming from Firestore. Unlike packaged assets, cloud
   * templates may be style-only (a font/color/3D/animation preset with no sample text) —
   * those stay valid and preview with [DEFAULT_PREVIEW_TEXT] instead of being dropped.
   */
  private fun parseFirestoreElement(source: JSONObject, fallbackId: String): RegisteredTextTemplate? {
    if (isExplicitlyHidden(source)) return null
    val normalized = normalizeTemplateObject(source, fallbackId)
    val clipObj = normalized.optJSONObject("clip") ?: normalized.optJSONObject("templateClip")
    if (stringValue(normalized, "text").isNullOrBlank() &&
      stringValue(clipObj ?: JSONObject(), "text").isNullOrBlank() &&
      (clipObj != null || hasStyleSignal(normalized))
    ) {
      // Style-only cloud template: keep it, previewed with the sample text. A document with
      // nothing but a name carries no style at all and is still rejected as before.
      normalized.put("text", DEFAULT_PREVIEW_TEXT)
    }
    return parseNormalizedObject(normalized)?.copy(isRemote = true)
  }

  /** True when a document carries at least one real text-style field. */
  private fun hasStyleSignal(json: JSONObject): Boolean {
    val styleKeys = listOf(
      "fontFamily", "fontSizeSp", "fontWeight", "letterSpacing", "lineSpacing",
      "textColor", "hasGradient", "gradientColorStart", "gradientColorEnd",
      "strokeWidth", "strokeColor", "hasShadow", "shadowColor", "hasGlow", "glowColor",
      "hasBackground", "backgroundColor", "is3D", "depth3D", "material3D", "color3D",
      "animationIn", "animationType", "animation3D", "effectStyle"
    )
    return styleKeys.any { key -> json.has(key) && !json.isNull(key) && hasScalarValue(json, key) } ||
      json.has("clip") || json.has("templateClip")
  }

  private fun isExplicitlyHidden(data: Map<String, Any?>): Boolean {
    for (key in listOf("enabled", "isEnabled", "published", "isPublished", "visible", "isVisible", "isActive", "active")) {
      val value = data[key]
      if (value is Boolean && !value) return true
      if (value is String && (value.equals("false", true) || value.equals("no", true))) return true
    }
    val status = (data["status"] as? String)?.trim()?.lowercase()
    return status in setOf("draft", "archived", "hidden", "unpublished", "disabled")
  }

  private fun isExplicitlyHidden(json: JSONObject): Boolean {
    for (key in listOf("enabled", "isEnabled", "published", "isPublished", "visible", "isVisible", "isActive", "active")) {
      if (!json.has(key) || json.isNull(key)) continue
      val value = json.opt(key)
      if (value is Boolean && !value) return true
      if (value is String && (value.equals("false", true) || value.equals("no", true))) return true
    }
    val status = json.optString("status").trim().lowercase()
    return status in setOf("draft", "archived", "hidden", "unpublished", "disabled")
  }

  private fun parseNormalizedObject(json: JSONObject): RegisteredTextTemplate? {
    val id = json.optString("id").trim()
    val name = json.optString("name").trim()
    if (id.isEmpty() || name.isEmpty()) return null
    val clipObj = json.optJSONObject("clip") ?: json.optJSONObject("templateClip")
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
    normalized.optJSONObject("templateClip")?.let(::normalizeStyleAliases)

    val resolvedId = fallbackId?.takeIf { it.isNotBlank() } ?: stringValue(normalized, "id")
    if (!resolvedId.isNullOrBlank()) normalized.put("id", resolvedId)

    if (stringValue(normalized, "name").isNullOrBlank()) {
      val title = stringValue(normalized, "title")
      // Only Firestore documents can fall back to their document ID; asset/plugin parsing
      // continues to reject nameless templates as before.
      val name = title ?: fallbackId?.takeIf { it.isNotBlank() }
      if (!name.isNullOrBlank()) normalized.put("name", name)
    }

    val clip = normalized.optJSONObject("clip") ?: normalized.optJSONObject("templateClip")
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
    alias("text", "content", "defaultText", "sampleText", "previewText", "textContent", "captionText")
    alias("fontFamily", "font", "fontName")
    alias("fontSizeSp", "fontSize", "textSize")
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
      try {
        FirebaseBootstrap.ensure(context)
        if (FirebaseApp.getApps(context).isEmpty()) return

        val db = FirebaseFirestore.getInstance()
        REMOTE_COLLECTIONS.forEach { collection ->
          if (firestoreListeners.containsKey(collection)) return@forEach
          val registration = db.collection(collection)
            .addSnapshotListener { snapshot, error ->
              if (error != null) {
                // Firestore listeners terminate on permission/configuration errors; clear the
                // slot so a later ensureLoaded()/refresh (e.g. after rules deploy) re-attaches.
                synchronized(lock) { firestoreListeners.remove(collection) }
                Log.w(TAG, "Firestore $collection listener failed: ${error.message}")
                if (remoteTemplateCount() == 0) {
                  markSyncError(error.message ?: "Could not load text templates from Firebase")
                }
                return@addSnapshotListener
              }
              val documents = snapshot?.documents ?: return@addSnapshotListener
              val templates = templatesFromDocuments(
                documents.map { it.id to (it.data ?: emptyMap()) }
              )
              mainHandler.post {
                replaceRemoteTemplates(firestoreSourceKey(collection), templates)
                markSyncReady()
              }
            }
          firestoreListeners[collection] = registration
        }
      } catch (error: Exception) {
        Log.w(TAG, "Could not start Firestore text-template sync: ${error.message}", error)
      }
    }
  }

  /** Stops the live Firestore listeners. Remote templates stay merged in the registry. */
  fun stopFirestoreSync() {
    synchronized(lock) {
      firestoreListeners.values.forEach { runCatching { it.remove() } }
      firestoreListeners.clear()
    }
  }

  private fun replaceRemoteTemplates(
    source: String,
    templates: List<RegisteredTextTemplate>
  ) {
    synchronized(remoteLock) {
      val nextById = templates.associateBy { it.id }
      val previousIds = remoteIdsBySource[source].orEmpty()
      val removedIds = previousIds.filterNot { id ->
        id in nextById.keys ||
          // Another cloud source still provides this id — keep it in the registry.
          remoteIdsBySource.any { (other, ids) -> other != source && id in ids }
      }
      removedIds.forEach(TextAssetRegistry::removeTemplate)

      val updatedIds = previousIds.intersect(nextById.keys) + nextById.keys
      remoteIdsBySource[source] = updatedIds.toMutableSet()

      // Reverse insertion at the front preserves the cloud snapshot order while making
      // uploaded templates immediately visible above the packaged catalog in the grid.
      nextById.values.toList().asReversed().forEach { template ->
        val alreadyRemote = remoteIdsBySource.values.any { template.id in it }
        val alreadyRegistered = TextAssetRegistry.getTemplates().any { it.id == template.id }
        // A cloud item should not silently replace an asset/plugin template with the same id.
        if (alreadyRemote || !alreadyRegistered) {
          TextAssetRegistry.upsertTemplateAtFront(template)
        }
      }
      Log.d(TAG, "Loaded ${remoteIdsBySource.values.flatten().toSet().size} Firebase text templates")
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
