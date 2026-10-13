package com.example.data.firebase

import android.content.Context
import android.util.Log
import com.example.ui.components.elements.ElementCategory
import com.example.ui.components.elements.ElementItem
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/**
 * Firebase asset library for the bottom-navigation Shapes/Elements panel.
 *
 * Firestore: `categories/{id}` (11 docs) + `assets/{assetId}` — only documents
 * with `status == "published"` are shown. Storage: the full SVG lives at
 * `storagePath`, the PNG thumbnail at `thumbnailPath`; thumbnails are resolved
 * to download URLs and Coil-cached by the panel (the whole library is never
 * downloaded just to render the grid).
 *
 * Offline fallback: the identical catalog ships in
 * `assets/elements/catalog.json`, so the panel keeps working with no network.
 * Selecting an asset still inserts a real editable clip through
 * `TimelineEngine.addElementClip` with the Firebase asset ID + storage path in
 * `renderData` — no dummy local substitute.
 */
object FirebaseElementsRepository {
  private const val TAG = "FirebaseElements"
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val _remoteElements = MutableStateFlow<List<ElementItem>>(emptyList())
  val remoteElements: StateFlow<List<ElementItem>> = _remoteElements

  private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
  val syncState: StateFlow<SyncState> = _syncState

  @Volatile private var loadStarted = false

  sealed interface SyncState {
    data object Idle : SyncState
    data object Loading : SyncState
    data class Loaded(val count: Int, val fromCache: Boolean) : SyncState
    data class Failed(val message: String) : SyncState
  }

  fun ensureLoaded(context: Context) {
    if (loadStarted) return
    loadStarted = true
    refresh(context)
  }

  fun refresh(context: Context) {
    val appContext = context.applicationContext
    scope.launch {
      _syncState.value = SyncState.Loading
      runCatching { loadFromFirestore() }
        .onSuccess { items ->
          _remoteElements.value = items
          _syncState.value = SyncState.Loaded(items.size, fromCache = false)
        }
        .onFailure { error ->
          Log.w(TAG, "Firestore load failed, using bundled catalog", error)
          runCatching { loadBundledCatalog(appContext) }
            .onSuccess { items ->
              _remoteElements.value = items
              _syncState.value =
                if (items.isEmpty()) SyncState.Failed(error.message ?: "Firebase unreachable")
                else SyncState.Loaded(items.size, fromCache = true)
            }
            .onFailure {
              _syncState.value = SyncState.Failed(error.message ?: "Firebase unreachable")
            }
        }
    }
  }

  private suspend fun loadFromFirestore(context: Context): List<ElementItem> {
    FirebaseBootstrap.ensure(context)
    val db = FirebaseFirestore.getInstance()
    // Categories are validated so a missing/renamed category fails loudly in logs,
    // but assets are the source of truth for what the grid renders.
    val categories = db.collection("categories").get().await()
    Log.i(TAG, "categories loaded: ${categories.size()}")
    val snapshot = db.collection("assets")
      .whereEqualTo("status", "published")
      .get()
      .await()
    val storage = FirebaseStorage.getInstance()
    return snapshot.documents.mapNotNull { doc ->
      runCatching {
        val categoryId = doc.getString("categoryId") ?: return@runCatching null
        val category = ElementCategory.values().firstOrNull { it.id == categoryId }
          ?: return@runCatching null
        // Delivery: "storage" (download URL) ya "firestore" (embedded content -
        // jab project par billing/bucket na ho). Dono real Firebase source hain.
        val svgContent = doc.getString("svgContent")
        val thumbnailData = doc.getString("thumbnailBase64")?.let { b64 ->
          runCatching { android.util.Base64.decode(b64, android.util.Base64.DEFAULT) }.getOrNull()
        }
        val thumbnailPath = doc.getString("thumbnailPath")
        val thumbnailUrl = if (thumbnailData == null) {
          thumbnailPath?.let { path ->
            runCatching { storage.reference.child(path).downloadUrl.await().toString() }.getOrNull()
          }
        } else null
        val props = doc.get("defaultProperties") as? Map<*, *>
        ElementItem(
          id = doc.id,
          title = doc.getString("name") ?: doc.id,
          subtitle = "Firebase • ${(doc.getString("format") ?: "svg").uppercase()}",
          category = category,
          tags = (doc.get("tags") as? List<*>)?.mapNotNull { it as? String }
            ?: listOf(categoryId),
          iconSymbol = "☁️",
          vectorType = (props?.get("kind") as? String) ?: categoryId,
          primaryColor = parseColor(props?.get("primaryColor") as? String),
          secondaryColor = parseColor(props?.get("secondaryColor") as? String),
          isRemote = true,
          thumbnailUrl = thumbnailUrl,
          thumbnailData = thumbnailData,
          svgContent = svgContent,
          storagePath = doc.getString("storagePath"),
          format = doc.getString("format"),
          // Timeline clip carries the real asset: inline SVG when embedded,
          // otherwise its Storage path.
          renderData = svgContent ?: doc.getString("storagePath")
        )
      }.getOrNull()
    }.sortedWith(compareBy({ it.category.id }, { it.title }))
  }

  /** Bundled mirror of the Firestore seed (offline / first-launch fallback). */
  private fun loadBundledCatalog(context: Context): List<ElementItem> {
    val json = context.assets.open("elements/catalog.json").bufferedReader().use { it.readText() }
    val assets = JSONObject(json).getJSONArray("assets")
    val items = mutableListOf<ElementItem>()
    for (i in 0 until assets.length()) {
      val a = assets.getJSONObject(i)
      if (a.optString("status") != "published") continue
      val category = ElementCategory.values()
        .firstOrNull { it.id == a.optString("categoryId") } ?: continue
      val props = a.optJSONObject("defaultProperties")
      items += ElementItem(
        id = a.optString("id"),
        title = a.optString("name"),
        subtitle = "Offline catalog • SVG",
        category = category,
        tags = listOf(category.id),
        iconSymbol = "☁️",
        vectorType = props?.optString("kind") ?: category.id,
        primaryColor = parseColor(props?.optString("primaryColor")),
        secondaryColor = parseColor(props?.optString("secondaryColor")),
        isRemote = true,
        storagePath = a.optString("storagePath"),
        format = a.optString("format"),
        renderData = a.optString("storagePath")
      )
    }
    return items
  }

  private fun parseColor(hex: String?): Long {
    if (hex.isNullOrBlank()) return 0xFF00E5FF
    return runCatching {
      val clean = hex.removePrefix("#")
      val value = clean.toLong(16)
      if (clean.length <= 6) 0xFF000000L or value else value
    }.getOrDefault(0xFF00E5FF)
  }
}
