package com.example.ui.components.filter

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.FilterSettings
import com.example.domain.model.FilterType
import com.example.domain.model.VideoAdjustments
import com.example.engine.SelectedTrackElement
import com.example.engine.color.ColorEngineHost
import com.ahstudio.color.core.LutState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.example.ui.StudioViewModel
import kotlin.math.abs
import kotlin.math.roundToInt

// Distinctive Blue + Green mixed studio aesthetic
private val BlueGreenDarkTop = Color(0xFF0B1F27)     // Deep Blue-Green Navy
private val BlueGreenDarkBottom = Color(0xFF07171C)  // Dark Midnight Teal
private val BlueGreenBorder = Color(0xFF133C45)      // Elegant Teal Border
private val BlueGreenAccent = Color(0xFF00D1B2)      // Bright Emerald Teal
private val BlueAccent = Color(0xFF00B4D8)           // Cyan Blue
private val TextPrimary = Color(0xFFF1F5F9)
private val TextSecondary = Color(0xFF94A3B8)
private val CardBg = Color(0xFF0E232B)

enum class FilterToolsTab(val title: String, val icon: ImageVector) {
  FILTERS("Filters", Icons.Default.FilterVintage),
  ADJUST("Adjust", Icons.Default.Tune),
  VIDEO_QUALITY("Video Quality", Icons.Default.HighQuality)
}

enum class AdjustMode(val title: String) {
  SMART_AUTO("Smart Auto"),
  CUSTOMISE("Customise")
}

enum class SmartAutoTool(val title: String, val icon: ImageVector) {
  AUTO_ADJUST("Auto Adjust", Icons.Default.AutoFixHigh),
  COLOR_FIXINGS("Color Fixings", Icons.Default.ColorLens),
  COLOR_CORRECT("Color Correct", Icons.Default.Palette)
}

enum class CustomiseTool(val title: String, val icon: ImageVector) {
  BRIGHTNESS("Brightness", Icons.Default.WbSunny),
  SHARPEN("Sharpen", Icons.Default.Details),
  CLARITY("Clarity", Icons.Default.Deblur),
  HIGHLIGHTS("Highlights", Icons.Default.LightMode),
  WHITES("Whites", Icons.Default.Brightness7),
  BLACKS("Blacks", Icons.Default.Brightness4),
  TEMPERATURE("Temperature", Icons.Default.Thermostat),
  FADE("Fade", Icons.Default.Gradient),
  VIGNETTE("Vignette", Icons.Default.Vignette),
  CONTRAST("Contrast", Icons.Default.Contrast),
  GRAIN("Grain", Icons.Default.Grain),
  SHADOWS("Shadows", Icons.Default.DarkMode)
}

/**
 * Filter Tools Panel:
 * - 45% screen height bottom-sheet style container over editor
 * - Blue + Green mixed background
 * - Header: Reset (Left) | Filter Tools (Center) | Close X (Right)
 * - Tabs: Filters | Adjust | Video Quality
 * - Filters tab: 4 columns, vertical scroll, "None" first, real GPU filters, zero lag
 * - Adjust tab: Smart Auto (Auto Adjust, Color Fixings, Color Correct) + Customise (12 tools with 1..100 sliders)
 * - Video Quality tab: Auto Enhance, Denoise, Clarity, HDR, Anti-Flicker, Color Fix
 */
@Composable
fun FilterToolsPanel(
  viewModel: StudioViewModel,
  modifier: Modifier = Modifier,
  initialTab: FilterToolsTab = FilterToolsTab.FILTERS,
  onClose: () -> Unit = { viewModel.setActiveToolbarTab(null) }
) {
  val context = LocalContext.current
  val timeline by viewModel.timelineEngine.timeline.collectAsState()
  val selectedElement by viewModel.timelineEngine.selectedElement.collectAsState()
  val currentPosMs by viewModel.timelineEngine.currentPositionMs.collectAsState()

  // Selected clip if any, or active clip under current playhead
  val selectedClip = remember(timeline, selectedElement, currentPosMs) {
    val fromSelection = when (selectedElement) {
      is SelectedTrackElement.Video -> timeline.videoClips.find { it.id == (selectedElement as SelectedTrackElement.Video).clipId }
      is SelectedTrackElement.Overlay -> timeline.overlayClips.find { it.id == (selectedElement as SelectedTrackElement.Overlay).clipId }
      else -> null
    }
    fromSelection
      ?: timeline.videoClips.find { currentPosMs >= it.timelineStartMs && currentPosMs < it.timelineStartMs + it.durationMs }
      ?: timeline.videoClips.firstOrNull()
  }

  // LUT looks use the colour engine, which grades main-track clips only.
  val lutClip = remember(timeline, selectedClip) {
    selectedClip?.takeIf { sc -> timeline.videoClips.any { it.id == sc.id } }
  }

  val previewUri = remember(selectedClip, timeline) {
    selectedClip?.uri?.ifBlank { null }
      ?: timeline.videoClips.firstOrNull { it.uri.isNotBlank() }?.uri
      ?: ""
  }
  val previewStartMs = selectedClip?.sourceStartMs ?: 0L

  // Active filter state
  val activeFilterState = selectedClip?.filter ?: timeline.filter
  var currentFilter by remember(activeFilterState) { mutableStateOf(activeFilterState) }

  // Active adjustments state
  // Clip-wise: the selected clip's own values, falling back to the project-wide ones until it is edited.
  val activeAdjustments = selectedClip?.adjustments ?: timeline.adjustments
  var currentAdjustments by remember(activeAdjustments, selectedClip?.id) { mutableStateOf(activeAdjustments) }
  val applyAdjustments: (VideoAdjustments) -> Unit = { adj ->
    currentAdjustments = adj
    if (!viewModel.timelineEngine.updateClipAdjustments(adj, selectedClip?.id)) {
      viewModel.timelineEngine.updateAdjustments(adj)
    }
  }

  var activeTab by remember { mutableStateOf(initialTab) }

  // Lightweight single base thumbnail loaded ONCE for the entire grid (zero lag, zero OOM)
  var baseThumbnailBitmap by remember(previewUri) {
    val key = com.example.engine.media.VideoThumbnailManager.makeKey(previewUri, previewStartMs, 96, 96)
    mutableStateOf(com.example.engine.media.VideoThumbnailManager.getCachedThumbnail(key))
  }

  LaunchedEffect(previewUri, previewStartMs) {
    if (previewUri.isNotBlank() && baseThumbnailBitmap == null) {
      try {
        com.example.engine.media.VideoThumbnailManager.requestThumbnail(
          context = context,
          uri = previewUri,
          sourceTimeMs = previewStartMs,
          targetWidth = 96,
          targetHeight = 96,
          isVideo = true
        ) { bmp ->
          baseThumbnailBitmap = bmp
        }
      } catch (_: Throwable) {
        // Safe fallback to procedural canvas
      }
    }
  }

  val hasActiveFilter = currentFilter.type != FilterType.NONE && currentFilter.intensity > 0.01f
  val hasActiveAdjustments = remember(currentAdjustments) {
    abs(currentAdjustments.brightness) > 0.01f ||
      abs(currentAdjustments.contrast - 1f) > 0.01f ||
      abs(currentAdjustments.saturation - 1f) > 0.01f ||
      abs(currentAdjustments.exposure) > 0.01f ||
      abs(currentAdjustments.temperature) > 0.01f ||
      abs(currentAdjustments.tint) > 0.01f ||
      abs(currentAdjustments.highlights) > 0.01f ||
      abs(currentAdjustments.shadows) > 0.01f ||
      abs(currentAdjustments.whites) > 0.01f ||
      abs(currentAdjustments.blacks) > 0.01f ||
      currentAdjustments.sharpness > 0.01f ||
      currentAdjustments.clarity > 0.01f ||
      currentAdjustments.vignette > 0.01f ||
      currentAdjustments.fade > 0.01f ||
      currentAdjustments.grain > 0.01f
  }
  val hasActiveQuality = remember(currentAdjustments) {
    currentAdjustments.autoEnhance > 0.01f ||
      currentAdjustments.superClarity > 0.01f ||
      currentAdjustments.colorCorrect > 0.01f ||
      currentAdjustments.denoise > 0.01f ||
      currentAdjustments.hdrBoost > 0.01f ||
      currentAdjustments.antiFlicker > 0.01f ||
      currentAdjustments.colorFix > 0.01f
  }

  Surface(
    color = BlueGreenDarkBottom,
    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    border = BorderStroke(1.dp, BlueGreenBorder),
    modifier = modifier.fillMaxWidth().wrapContentHeight()
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .wrapContentHeight()
        .background(
          Brush.verticalGradient(
            listOf(BlueGreenDarkTop, BlueGreenDarkBottom)
          )
        )
        .navigationBarsPadding()
    ) {
      // 1. HEADER ROW: Reset (Left) | Filter Tools (Center) | Close X (Right) - Slim Design
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .background(Color(0xFF081B22))
          .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        // Left: Reset
        TextButton(
          onClick = {
            when (activeTab) {
              FilterToolsTab.FILTERS -> {
                val reset = FilterSettings(type = FilterType.NONE, intensity = 1.0f)
                currentFilter = reset
                viewModel.timelineEngine.updateFilter(reset, selectedClip?.id)
              }
              FilterToolsTab.ADJUST -> {
                val reset = currentAdjustments.copy(
                  brightness = 0f,
                  contrast = 1f,
                  saturation = 1f,
                  exposure = 0f,
                  temperature = 0f,
                  tint = 0f,
                  highlights = 0f,
                  shadows = 0f,
                  whites = 0f,
                  blacks = 0f,
                  sharpness = 0f,
                  clarity = 0f,
                  fade = 0f,
                  vignette = 0f,
                  grain = 0f
                )
                applyAdjustments(reset)
              }
              FilterToolsTab.VIDEO_QUALITY -> {
                val reset = currentAdjustments.copy(
                  autoEnhance = 0f,
                  superClarity = 0f,
                  colorCorrect = 0f,
                  denoise = 0f,
                  hdrBoost = 0f,
                  antiFlicker = 0f,
                  colorFix = 0f
                )
                applyAdjustments(reset)
              }
            }
          },
          colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary),
          contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
          modifier = Modifier.testTag("filter_tools_reset_button")
        ) {
          Icon(Icons.Default.Refresh, contentDescription = "Reset", modifier = Modifier.size(13.dp), tint = TextSecondary)
          Spacer(modifier = Modifier.width(3.dp))
          Text(
            text = "Reset",
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = TextSecondary
          )
        }

        // Center: Title (Slim)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Text(
            text = "Filter Tools",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
          )
          Text(
            text = if (selectedClip != null) "Clip: ${selectedClip.name.take(18)}" else "Timeline Master",
            color = BlueGreenAccent,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium
          )
        }

        // Right: Close Button (X) (Slim)
        IconButton(
          onClick = onClose,
          modifier = Modifier
            .size(28.dp)
            .testTag("filter_tools_close_button")
        ) {
          Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "Close",
            tint = Color.White,
            modifier = Modifier.size(16.dp)
          )
        }
      }

      // 2. MAIN PANEL TABS: Exactly ONE Slim Row (Filters | Adjust | Video Quality)
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .background(Color(0xFF06151B))
          .padding(horizontal = 10.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        FilterToolsTab.values().forEach { tab ->
          val isSelected = activeTab == tab
          val hasBadge = when (tab) {
            FilterToolsTab.FILTERS -> hasActiveFilter
            FilterToolsTab.ADJUST -> hasActiveAdjustments
            FilterToolsTab.VIDEO_QUALITY -> hasActiveQuality
          }

          Surface(
            shape = RoundedCornerShape(6.dp),
            color = if (isSelected) BlueGreenAccent.copy(alpha = 0.2f) else Color(0xFF0C2028),
            border = BorderStroke(
              width = if (isSelected) 1.5.dp else 1.dp,
              color = if (isSelected) BlueGreenAccent else BlueGreenBorder
            ),
            modifier = Modifier
              .weight(1f)
              .height(28.dp)
              .clip(RoundedCornerShape(6.dp))
              .clickable { activeTab = tab }
              .testTag("tab_${tab.name.lowercase()}")
          ) {
            Row(
              modifier = Modifier.fillMaxSize(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.Center
            ) {
              Icon(
                imageVector = tab.icon,
                contentDescription = null,
                tint = if (isSelected) BlueGreenAccent else TextSecondary,
                modifier = Modifier.size(13.dp)
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text(
                text = tab.title,
                color = if (isSelected) Color.White else TextSecondary,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                fontSize = 11.sp
              )
              if (hasBadge) {
                Spacer(modifier = Modifier.width(3.dp))
                Box(
                  modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(BlueGreenAccent)
                )
              }
            }
          }
        }
      }

      // Subtle dividing line
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(1.dp)
          .background(BlueGreenBorder)
      )

      // 3. TAB CONTENT
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f)
      ) {
        when (activeTab) {
          FilterToolsTab.FILTERS -> {
            FiltersTabContent(
              viewModel = viewModel,
              currentFilter = currentFilter,
              selectedClipId = selectedClip?.id,
              lutClipId = lutClip?.id,
              lutClipGradeJson = lutClip?.colorGradeJson,
              baseBitmap = baseThumbnailBitmap,
              onFilterChange = { newFilter ->
                currentFilter = newFilter
                viewModel.timelineEngine.updateFilter(newFilter, selectedClip?.id)
              }
            )
          }
          FilterToolsTab.ADJUST -> {
            AdjustTabContent(
              currentAdjustments = currentAdjustments,
              onAdjustmentsChange = { newAdj ->
                applyAdjustments(newAdj)
              }
            )
          }
          FilterToolsTab.VIDEO_QUALITY -> {
            VideoQualityTabContent(
              currentAdjustments = currentAdjustments,
              onAdjustmentsChange = { newAdj ->
                applyAdjustments(newAdj)
              }
            )
          }
        }
      }
    }
  }
}

/**
 * Tab 1: FILTERS CONTENT
 * 4 Columns, Vertical Scroll, First item "None", Real filters, Lightweight preview
 */
private const val CAT_ALL = "All"
private const val CAT_FAVOURITES = "Favorites"
private const val CAT_RECENT = "Recent"
private const val CAT_LUT = "LUT Looks"

/** One tile in the Filters grid: a built-in preset, a bundled LUT look, or the "no LUT" tile. */
private sealed interface LookItem {
  val key: String
  data class Preset(val type: FilterType) : LookItem { override val key: String get() = type.name }
  data class Lut(val id: String, val name: String) : LookItem { override val key: String get() = "lut:$id" }
  object NoLut : LookItem { override val key: String get() = "lut:none" }
}

private fun lookName(item: LookItem): String = when (item) {
  is LookItem.Preset -> item.type.displayName
  is LookItem.Lut -> item.name
  LookItem.NoLut -> "No LUT"
}

/**
 * Tab 1: FILTERS CONTENT
 * Search, Favorites and Recent, built-in presets, and bundled LUT looks (real .cube grading,
 * thumbnails sampled from the LUT itself). 4 columns, vertical scroll, zero-lag thumbnails.
 */
@Composable
private fun FiltersTabContent(
  viewModel: StudioViewModel,
  currentFilter: FilterSettings,
  selectedClipId: String?,
  lutClipId: String?,
  lutClipGradeJson: String?,
  baseBitmap: Bitmap?,
  onFilterChange: (FilterSettings) -> Unit
) {
  val context = LocalContext.current
  var selectedCategory by remember { mutableStateOf(CAT_ALL) }
  var query by remember { mutableStateOf("") }
  val categories = listOf(CAT_ALL, CAT_FAVOURITES, CAT_RECENT, "Pro Enhancements", "Cinematic & Nature", "Aesthetic Looks", CAT_LUT)

  var favourites by remember { mutableStateOf(FilterPrefs.favourites(context)) }
  var recent by remember { mutableStateOf(FilterPrefs.recent(context)) }

  fun toggleFavourite(key: String) {
    favourites = FilterPrefs.toggled(favourites, key)
    FilterPrefs.saveFavourites(context, favourites)
  }
  fun markRecent(key: String) {
    recent = FilterPrefs.pushedRecent(recent, key)
    FilterPrefs.saveRecent(context, recent)
  }

  // ---- LUT looks: stored on the clip as part of its colour grade (same data the Color panel edits).
  val grade = remember(lutClipGradeJson) { ColorEngineHost.decodeOrDefault(lutClipGradeJson) }
  val activeLutId = grade.lut?.lutId?.takeIf { it.isNotBlank() }
  val lutIntensity = grade.lut?.intensity ?: 1f
  val lutEnabled = lutClipId != null

  fun commitLut(lutId: String?, intensity: Float) {
    val id = lutClipId ?: return
    val next = grade.copy(lut = lutId?.let { LutState(lutId = it, intensity = intensity) })
    ColorEngineHost.applyToEngine(id, next)
    viewModel.timelineEngine.setClipColorGrade(id, if (next.isIdentity()) null else ColorEngineHost.encode(next))
    viewModel.refreshCurrentFrame()
  }

  // LUT files load asynchronously; re-render once the LUT is actually available.
  LaunchedEffect(activeLutId) {
    val id = activeLutId ?: return@LaunchedEffect
    var tries = 0
    while (!ColorEngineHost.isLutReady(id) && tries < 30) { delay(100); tries++ }
    viewModel.refreshCurrentFrame()
  }

  // Real LUT thumbnails: CPU-sample each bundled LUT over the base thumbnail, off the main thread.
  val lutThumbs = remember(baseBitmap) { mutableStateMapOf<String, Bitmap>() }
  LaunchedEffect(baseBitmap) {
    val base = baseBitmap ?: return@LaunchedEffect
    withContext(Dispatchers.Default) {
      ColorEngineHost.BUNDLED_LUTS.forEach { (id, _) ->
        val lut = LutLookPreview.loadLut(context, id) ?: return@forEach
        val bmp = runCatching { if (base.isRecycled) null else LutLookPreview.apply(base, lut) }.getOrNull()
          ?: return@forEach
        lutThumbs[id] = bmp
      }
    }
  }

  val allPresets = remember { FilterType.values().filter { it != FilterType.NONE } }
  val luts = remember { ColorEngineHost.BUNDLED_LUTS.map { LookItem.Lut(it.first, it.second) } }

  fun resolve(key: String): LookItem? =
    if (key.startsWith("lut:")) luts.firstOrNull { it.key == key }
    else allPresets.firstOrNull { it.name == key }?.let { LookItem.Preset(it) }

  val items: List<LookItem> = remember(selectedCategory, query, favourites, recent) {
    val q = query.trim()
    if (q.isNotEmpty()) {
      (allPresets.map { LookItem.Preset(it) } + luts).filter { lookName(it).contains(q, ignoreCase = true) }
    } else when (selectedCategory) {
      CAT_FAVOURITES -> favourites.mapNotNull { resolve(it) }
      CAT_RECENT -> recent.mapNotNull { resolve(it) }
      CAT_LUT -> listOf<LookItem>(LookItem.NoLut) + luts
      CAT_ALL -> listOf<LookItem>(LookItem.Preset(FilterType.NONE)) + allPresets.map { LookItem.Preset(it) } + luts
      else -> listOf<LookItem>(LookItem.Preset(FilterType.NONE)) +
        allPresets.filter { it.category == selectedCategory }.map { LookItem.Preset(it) }
    }
  }

  val gridState = rememberLazyGridState()

  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(horizontal = 8.dp, vertical = 4.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp)
  ) {
    // Category chips
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      categories.forEach { cat ->
        val isCatSelected = selectedCategory == cat && query.isBlank()
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = if (isCatSelected) BlueGreenAccent.copy(alpha = 0.25f) else Color(0xFF0C2028),
          border = BorderStroke(1.dp, if (isCatSelected) BlueGreenAccent else BlueGreenBorder),
          modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { selectedCategory = cat; query = "" }
            .testTag("filter_category_${cat.lowercase().replace(" ", "_").replace("&", "and")}")
        ) {
          Text(
            text = cat,
            color = if (isCatSelected) BlueGreenAccent else TextSecondary,
            fontWeight = if (isCatSelected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 10.5.sp,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
          )
        }
      }
    }

    // Search
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .height(28.dp)
        .clip(RoundedCornerShape(8.dp))
        .background(Color(0xFF0C2028))
        .border(1.dp, BlueGreenBorder, RoundedCornerShape(8.dp))
        .padding(horizontal = 8.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(14.dp))
      Spacer(modifier = Modifier.width(6.dp))
      Box(modifier = Modifier.weight(1f)) {
        if (query.isEmpty()) Text("Search looks", color = TextSecondary, fontSize = 11.sp)
        BasicTextField(
          value = query,
          onValueChange = { query = it },
          singleLine = true,
          textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 11.sp),
          cursorBrush = SolidColor(BlueGreenAccent),
          modifier = Modifier.fillMaxWidth().testTag("filter_search_field")
        )
      }
      if (query.isNotEmpty()) {
        Icon(
          Icons.Default.Close,
          contentDescription = "Clear search",
          tint = TextSecondary,
          modifier = Modifier.size(14.dp).clickable { query = "" }
        )
      }
    }

    // Filter Intensity Slider (Visible when active filter != NONE)
    if (currentFilter.type != FilterType.NONE) {
      Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF0B1E26),
        border = BorderStroke(1.dp, BlueGreenBorder),
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = "${currentFilter.type.displayName} Intensity",
              color = Color.White,
              fontWeight = FontWeight.SemiBold,
              fontSize = 11.sp
            )
            Text(
              text = "${(currentFilter.intensity * 100).roundToInt()}%",
              color = BlueGreenAccent,
              fontWeight = FontWeight.Bold,
              fontSize = 11.sp
            )
          }

          Slider(
            value = currentFilter.intensity,
            onValueChange = { onFilterChange(currentFilter.copy(intensity = it)) },
            valueRange = 0.01f..1f,
            colors = SliderDefaults.colors(
              thumbColor = BlueGreenAccent,
              activeTrackColor = BlueGreenAccent,
              inactiveTrackColor = BlueGreenBorder
            ),
            modifier = Modifier
              .height(24.dp)
              .testTag("filter_intensity_slider")
          )

          // Quick presets & Apply to all clips
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
              listOf(0.25f, 0.50f, 0.75f, 1.0f).forEach { preset ->
                val isPSelected = abs(currentFilter.intensity - preset) < 0.05f
                Surface(
                  shape = RoundedCornerShape(4.dp),
                  color = if (isPSelected) BlueGreenAccent.copy(alpha = 0.25f) else Color(0xFF0E2730),
                  border = BorderStroke(1.dp, if (isPSelected) BlueGreenAccent else BlueGreenBorder),
                  modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onFilterChange(currentFilter.copy(intensity = preset)) }
                ) {
                  Text(
                    text = "${(preset * 100).toInt()}%",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isPSelected) BlueGreenAccent else TextSecondary,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                  )
                }
              }
            }

            Surface(
              shape = RoundedCornerShape(4.dp),
              color = Color(0xFF0E2730),
              border = BorderStroke(1.dp, BlueGreenBorder),
              modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable {
                  viewModel.timelineEngine.applyFilterToAllClips(currentFilter)
                }
                .testTag("filter_apply_all_button")
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
              ) {
                Icon(
                  Icons.Default.DoneAll,
                  contentDescription = null,
                  tint = BlueGreenAccent,
                  modifier = Modifier.size(11.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                  text = "Apply All Clips",
                  fontSize = 9.5.sp,
                  fontWeight = FontWeight.SemiBold,
                  color = BlueGreenAccent
                )
              }
            }
          }
        }
      }
    }

    // LUT intensity (visible when a LUT look is active on the selected main-track clip)
    activeLutId?.let { id ->
      val lutName = ColorEngineHost.BUNDLED_LUTS.firstOrNull { it.first == id }?.second ?: id
      Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF0B1E26),
        border = BorderStroke(1.dp, BlueGreenBorder),
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(text = "$lutName LUT", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(
                text = "${(lutIntensity * 100).roundToInt()}%",
                color = BlueGreenAccent, fontWeight = FontWeight.Bold, fontSize = 11.sp
              )
              Spacer(modifier = Modifier.width(10.dp))
              Text(
                text = "Remove",
                color = TextSecondary, fontSize = 10.sp,
                modifier = Modifier.clickable { commitLut(null, 1f) }.testTag("lut_remove_button")
              )
            }
          }
          Slider(
            value = lutIntensity.coerceIn(0f, 1f),
            onValueChange = { commitLut(id, it) },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
              thumbColor = BlueGreenAccent,
              activeTrackColor = BlueGreenAccent,
              inactiveTrackColor = BlueGreenBorder
            ),
            modifier = Modifier.height(24.dp).testTag("lut_intensity_slider")
          )
        }
      }
    }

    if (!lutEnabled && (selectedCategory == CAT_LUT || query.isNotBlank())) {
      Text(
        text = "LUT looks apply to main-track clips. Select a main-track clip to use them.",
        color = TextSecondary, fontSize = 10.sp
      )
    }

    if (items.isEmpty()) {
      Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
        Text(
          text = when {
            query.isNotBlank() -> "No looks match \"${query.trim()}\""
            selectedCategory == CAT_FAVOURITES -> "No favorites yet. Tap the star on a look."
            selectedCategory == CAT_RECENT -> "Looks you use will show up here."
            else -> "Nothing here."
          },
          color = TextSecondary, fontSize = 11.sp
        )
      }
    }

    // 4-Column Vertically Scrolling Filters Grid
    if (items.isNotEmpty()) LazyVerticalGrid(
      columns = GridCells.Fixed(4),
      state = gridState,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
      contentPadding = PaddingValues(bottom = 12.dp),
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f)
        .testTag("filters_vertical_grid_4_columns")
    ) {
      gridItems(items, key = { it.key }) { item ->
        when (item) {
          is LookItem.Preset -> {
            val type = item.type
            FilterCardItem(
              type = type,
              isSelected = currentFilter.type == type,
              baseBitmap = baseBitmap,
              isFavourite = item.key in favourites,
              onToggleFavourite = if (type == FilterType.NONE) null else ({ toggleFavourite(item.key) }),
              onClick = {
                if (type == FilterType.NONE) {
                  onFilterChange(FilterSettings(type = FilterType.NONE, intensity = 1.0f))
                } else {
                  val targetIntensity = if (currentFilter.intensity <= 0.05f) 1.0f else currentFilter.intensity
                  onFilterChange(FilterSettings(type = type, intensity = targetIntensity))
                  markRecent(item.key)
                }
              }
            )
          }
          is LookItem.Lut -> LutCardItem(
            name = item.name,
            bitmap = lutThumbs[item.id],
            isNone = false,
            isSelected = activeLutId == item.id,
            enabled = lutEnabled,
            isFavourite = item.key in favourites,
            onToggleFavourite = { toggleFavourite(item.key) },
            tag = "lut_look_${item.id}",
            onClick = {
              commitLut(item.id, if (activeLutId == item.id) lutIntensity else 1f)
              markRecent(item.key)
            }
          )
          LookItem.NoLut -> LutCardItem(
            name = "No LUT",
            bitmap = null,
            isNone = true,
            isSelected = activeLutId == null,
            enabled = lutEnabled,
            isFavourite = false,
            onToggleFavourite = null,
            tag = "lut_look_none",
            onClick = { commitLut(null, 1f) }
          )
        }
      }
    }
  }
}

/** Small star toggle drawn over a card's thumbnail. */
@Composable
private fun FavouriteStar(isFavourite: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
  Box(
    modifier = modifier
      .size(18.dp)
      .clip(CircleShape)
      .background(Color(0x66000000))
      .clickable(onClick = onToggle),
    contentAlignment = Alignment.Center
  ) {
    Icon(
      imageVector = if (isFavourite) Icons.Default.Star else Icons.Default.StarBorder,
      contentDescription = if (isFavourite) "Remove from favorites" else "Add to favorites",
      tint = if (isFavourite) Color(0xFFFFC857) else Color.White,
      modifier = Modifier.size(12.dp)
    )
  }
}

/** Card for a bundled LUT look (or the "No LUT" tile). Thumbnail is the LUT sampled over the clip frame. */
@Composable
private fun LutCardItem(
  name: String,
  bitmap: Bitmap?,
  isNone: Boolean,
  isSelected: Boolean,
  enabled: Boolean,
  isFavourite: Boolean,
  onToggleFavourite: (() -> Unit)?,
  tag: String,
  onClick: () -> Unit
) {
  Surface(
    shape = RoundedCornerShape(8.dp),
    color = if (isSelected) Color(0xFF133640) else CardBg,
    border = BorderStroke(
      width = if (isSelected) 2.dp else 1.dp,
      color = if (isSelected) BlueGreenAccent else BlueGreenBorder
    ),
    modifier = Modifier
      .fillMaxWidth()
      .height(86.dp)
      .alpha(if (enabled) 1f else 0.4f)
      .clip(RoundedCornerShape(8.dp))
      .clickable(enabled = enabled, onClick = onClick)
      .testTag(tag)
  ) {
    Column(
      modifier = Modifier.fillMaxSize().padding(3.dp),
      verticalArrangement = Arrangement.SpaceBetween,
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(58.dp)
          .clip(RoundedCornerShape(6.dp))
          .background(Color(0xFF071419)),
        contentAlignment = Alignment.Center
      ) {
        when {
          isNone -> Icon(
            Icons.Default.Block, contentDescription = "No LUT",
            tint = if (isSelected) BlueGreenAccent else TextSecondary, modifier = Modifier.size(22.dp)
          )
          bitmap != null && !bitmap.isRecycled -> Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "$name preview",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
          )
          else -> Box(
            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF334155), Color(0xFF0F172A))))
          )
        }
        if (onToggleFavourite != null) {
          FavouriteStar(isFavourite, onToggleFavourite, Modifier.align(Alignment.TopStart).padding(2.dp))
        }
        if (isSelected && !isNone) {
          Box(
            modifier = Modifier
              .align(Alignment.TopEnd)
              .padding(2.dp)
              .size(15.dp)
              .clip(CircleShape)
              .background(BlueGreenAccent),
            contentAlignment = Alignment.Center
          ) {
            Icon(Icons.Default.Check, contentDescription = "Selected", tint = Color.Black, modifier = Modifier.size(10.dp))
          }
        }
      }
      Text(
        text = name,
        color = if (isSelected) BlueGreenAccent else TextPrimary,
        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
        fontSize = 9.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 1.dp)
      )
    }
  }
}

/**
 * Filter Card Item:
 * - 4 columns layout
 * - Real GPU ColorMatrix applied to thumbnail
 * - Zero background loops or decoding
 */
@Composable
private fun FilterCardItem(
  type: FilterType,
  isSelected: Boolean,
  baseBitmap: Bitmap?,
  onClick: () -> Unit,
  isFavourite: Boolean = false,
  onToggleFavourite: (() -> Unit)? = null,
  modifier: Modifier = Modifier
) {
  val matrixArray = remember(type) {
    com.example.engine.composition.ColorFilterGenerator.getFilterMatrixArray(type, 1.0f)
  }
  val composeColorFilter = remember(matrixArray, type) {
    if (type == FilterType.NONE) null
    else ColorFilter.colorMatrix(ColorMatrix(matrixArray))
  }

  Surface(
    shape = RoundedCornerShape(8.dp),
    color = if (isSelected) Color(0xFF133640) else CardBg,
    border = BorderStroke(
      width = if (isSelected) 2.dp else 1.dp,
      color = if (isSelected) BlueGreenAccent else BlueGreenBorder
    ),
    modifier = modifier
      .fillMaxWidth()
      .height(86.dp)
      .clip(RoundedCornerShape(8.dp))
      .clickable(onClick = onClick)
      .testTag("filter_preset_${type.name.lowercase()}")
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(3.dp),
      verticalArrangement = Arrangement.SpaceBetween,
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      // Preview thumbnail box
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(58.dp)
          .clip(RoundedCornerShape(6.dp))
          .background(Color(0xFF071419)),
        contentAlignment = Alignment.Center
      ) {
        if (type == FilterType.NONE) {
          // Special distinct NONE card
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize()
          ) {
            Icon(
              imageVector = Icons.Default.Block,
              contentDescription = "None",
              tint = if (isSelected) BlueGreenAccent else TextSecondary,
              modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
              text = "Original",
              fontSize = 9.sp,
              color = if (isSelected) BlueGreenAccent else TextSecondary,
              fontWeight = FontWeight.Medium
            )
          }
        } else if (baseBitmap != null && !baseBitmap.isRecycled) {
          Image(
            bitmap = baseBitmap.asImageBitmap(),
            contentDescription = "${type.displayName} Preview",
            colorFilter = composeColorFilter,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
          )
        } else {
          // Clean procedural gradient preview
          ProceduralFilterPreview(type = type)
        }

        if (type != FilterType.NONE && onToggleFavourite != null) {
          FavouriteStar(isFavourite, onToggleFavourite, Modifier.align(Alignment.TopStart).padding(2.dp))
        }

        // Selection Checkmark Badge
        if (isSelected) {
          Box(
            modifier = Modifier
              .align(Alignment.TopEnd)
              .padding(2.dp)
              .size(15.dp)
              .clip(CircleShape)
              .background(BlueGreenAccent),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.Default.Check,
              contentDescription = "Selected",
              tint = Color.Black,
              modifier = Modifier.size(10.dp)
            )
          }
        }
      }

      // Title
      Text(
        text = type.displayName,
        color = if (isSelected) BlueGreenAccent else TextPrimary,
        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
        fontSize = 9.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 1.dp)
      )
    }
  }
}

/**
 * Tab 2: ADJUST CONTENT
 * Secondary row: 1. Smart Auto | 2. Customise
 * - Smart Auto: Auto Adjust, Color Fixings, Color Correct with 1..100 sliders
 * - Customise: Horizontal row of 12 tools (Brightness, Sharpen, Clarity, Highlights, Whites, Blacks, Temperature, Fade, Vignette, Contrast, Grain, Shadows) with 1..100 slider
 */
@Composable
private fun AdjustTabContent(
  currentAdjustments: VideoAdjustments,
  onAdjustmentsChange: (VideoAdjustments) -> Unit
) {
  var mode by remember { mutableStateOf(AdjustMode.SMART_AUTO) }
  var selectedSmartAutoTool by remember { mutableStateOf(SmartAutoTool.AUTO_ADJUST) }
  var selectedCustomiseTool by remember { mutableStateOf(CustomiseTool.BRIGHTNESS) }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(horizontal = 10.dp, vertical = 6.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    // Row 2: Mode Selector (Smart Auto | Customise)
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(Color(0xFF091C23), RoundedCornerShape(8.dp))
        .padding(3.dp),
      horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
      AdjustMode.values().forEach { m ->
        val isSel = mode == m
        Surface(
          shape = RoundedCornerShape(6.dp),
          color = if (isSel) BlueGreenAccent else Color.Transparent,
          modifier = Modifier
            .weight(1f)
            .height(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable { mode = m }
            .testTag("adjust_mode_${m.name.lowercase()}")
        ) {
          Box(contentAlignment = Alignment.Center) {
            Text(
              text = m.title,
              color = if (isSel) Color.Black else TextSecondary,
              fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
              fontSize = 11.5.sp
            )
          }
        }
      }
    }

    when (mode) {
      AdjustMode.SMART_AUTO -> {
        // Exactly three tools in one row: Auto Adjust, Color Fixings, Color Correct
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          SmartAutoTool.values().forEach { tool ->
            val isSel = selectedSmartAutoTool == tool
            val valueStrength = when (tool) {
              SmartAutoTool.AUTO_ADJUST -> (currentAdjustments.autoEnhance * 100).roundToInt().coerceIn(1, 100)
              SmartAutoTool.COLOR_FIXINGS -> (currentAdjustments.colorFix * 100).roundToInt().coerceIn(1, 100)
              SmartAutoTool.COLOR_CORRECT -> (currentAdjustments.colorCorrect * 100).roundToInt().coerceIn(1, 100)
            }
            val isActive = when (tool) {
              SmartAutoTool.AUTO_ADJUST -> currentAdjustments.autoEnhance > 0.01f
              SmartAutoTool.COLOR_FIXINGS -> currentAdjustments.colorFix > 0.01f
              SmartAutoTool.COLOR_CORRECT -> currentAdjustments.colorCorrect > 0.01f
            }

            Surface(
              shape = RoundedCornerShape(10.dp),
              color = if (isSel) Color(0xFF133942) else Color(0xFF0C2028),
              border = BorderStroke(
                width = if (isSel) 1.5.dp else 1.dp,
                color = if (isSel) BlueGreenAccent else BlueGreenBorder
              ),
              modifier = Modifier
                .weight(1f)
                .height(68.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable { selectedSmartAutoTool = tool }
                .testTag("smart_auto_${tool.name.lowercase()}")
            ) {
              Column(
                modifier = Modifier
                  .fillMaxSize()
                  .padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
              ) {
                Icon(
                  imageVector = tool.icon,
                  contentDescription = tool.title,
                  tint = if (isSel) BlueGreenAccent else if (isActive) BlueAccent else TextSecondary,
                  modifier = Modifier.size(20.dp)
                )
                Text(
                  text = tool.title,
                  fontSize = 10.sp,
                  fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                  color = if (isSel) Color.White else TextPrimary,
                  textAlign = TextAlign.Center,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
                Text(
                  text = "$valueStrength%",
                  fontSize = 9.sp,
                  color = if (isActive) BlueGreenAccent else TextSecondary,
                  fontWeight = FontWeight.SemiBold
                )
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Selected Smart Auto Slider: 1 ───────────── 100
        val currentValue = when (selectedSmartAutoTool) {
          SmartAutoTool.AUTO_ADJUST -> if (currentAdjustments.autoEnhance <= 0f) 50f else (currentAdjustments.autoEnhance * 100f)
          SmartAutoTool.COLOR_FIXINGS -> if (currentAdjustments.colorFix <= 0f) 50f else (currentAdjustments.colorFix * 100f)
          SmartAutoTool.COLOR_CORRECT -> if (currentAdjustments.colorCorrect <= 0f) 50f else (currentAdjustments.colorCorrect * 100f)
        }

        Surface(
          shape = RoundedCornerShape(10.dp),
          color = Color(0xFF0A1F26),
          border = BorderStroke(1.dp, BlueGreenBorder),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                  selectedSmartAutoTool.icon,
                  contentDescription = null,
                  tint = BlueGreenAccent,
                  modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                  text = "${selectedSmartAutoTool.title} Strength",
                  color = Color.White,
                  fontWeight = FontWeight.Bold,
                  fontSize = 12.sp
                )
              }

              Text(
                text = "${currentValue.roundToInt()}",
                color = BlueGreenAccent,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
              )
            }

            Slider(
              value = currentValue,
              onValueChange = { newVal ->
                val fraction = (newVal / 100f).coerceIn(0.01f, 1f)
                val updated = when (selectedSmartAutoTool) {
                  SmartAutoTool.AUTO_ADJUST -> currentAdjustments.copy(autoEnhance = fraction)
                  SmartAutoTool.COLOR_FIXINGS -> currentAdjustments.copy(colorFix = fraction)
                  SmartAutoTool.COLOR_CORRECT -> currentAdjustments.copy(colorCorrect = fraction)
                }
                onAdjustmentsChange(updated)
              },
              valueRange = 1f..100f,
              colors = SliderDefaults.colors(
                thumbColor = BlueGreenAccent,
                activeTrackColor = BlueGreenAccent,
                inactiveTrackColor = BlueGreenBorder
              ),
              modifier = Modifier
                .height(30.dp)
                .testTag("smart_auto_slider")
            )

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Text("1 (Subtle)", color = TextSecondary, fontSize = 9.5.sp)
              TextButton(
                onClick = {
                  val updated = when (selectedSmartAutoTool) {
                    SmartAutoTool.AUTO_ADJUST -> currentAdjustments.copy(autoEnhance = 0f)
                    SmartAutoTool.COLOR_FIXINGS -> currentAdjustments.copy(colorFix = 0f)
                    SmartAutoTool.COLOR_CORRECT -> currentAdjustments.copy(colorCorrect = 0f)
                  }
                  onAdjustmentsChange(updated)
                },
                contentPadding = PaddingValues(0.dp)
              ) {
                Text("Disable", color = TextSecondary, fontSize = 10.sp)
              }
              Text("100 (Maximum)", color = TextSecondary, fontSize = 9.5.sp)
            }
          }
        }
      }

      AdjustMode.CUSTOMISE -> {
        // Horizontally scrollable row of exactly 12 tools
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          CustomiseTool.values().forEach { tool ->
            val isSel = selectedCustomiseTool == tool
            val isModified = when (tool) {
              CustomiseTool.BRIGHTNESS -> abs(currentAdjustments.brightness) > 0.01f
              CustomiseTool.SHARPEN -> currentAdjustments.sharpness > 0.01f
              CustomiseTool.CLARITY -> currentAdjustments.clarity > 0.01f
              CustomiseTool.HIGHLIGHTS -> abs(currentAdjustments.highlights) > 0.01f
              CustomiseTool.WHITES -> abs(currentAdjustments.whites) > 0.01f
              CustomiseTool.BLACKS -> abs(currentAdjustments.blacks) > 0.01f
              CustomiseTool.TEMPERATURE -> abs(currentAdjustments.temperature) > 0.01f
              CustomiseTool.FADE -> currentAdjustments.fade > 0.01f
              CustomiseTool.VIGNETTE -> currentAdjustments.vignette > 0.01f
              CustomiseTool.CONTRAST -> abs(currentAdjustments.contrast - 1f) > 0.01f
              CustomiseTool.GRAIN -> currentAdjustments.grain > 0.01f
              CustomiseTool.SHADOWS -> abs(currentAdjustments.shadows) > 0.01f
            }

            Surface(
              shape = RoundedCornerShape(10.dp),
              color = if (isSel) Color(0xFF133942) else Color(0xFF0C2028),
              border = BorderStroke(
                width = if (isSel) 1.5.dp else 1.dp,
                color = if (isSel) BlueGreenAccent else BlueGreenBorder
              ),
              modifier = Modifier
                .width(66.dp)
                .height(68.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable { selectedCustomiseTool = tool }
                .testTag("custom_tool_${tool.name.lowercase()}")
            ) {
              Column(
                modifier = Modifier
                  .fillMaxSize()
                  .padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
              ) {
                Icon(
                  imageVector = tool.icon,
                  contentDescription = tool.title,
                  tint = if (isSel) BlueGreenAccent else if (isModified) BlueAccent else TextSecondary,
                  modifier = Modifier.size(18.dp)
                )
                Text(
                  text = tool.title,
                  fontSize = 9.sp,
                  fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                  color = if (isSel) Color.White else TextPrimary,
                  textAlign = TextAlign.Center,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
                // Small indicator dot if tool has non-default value
                Box(
                  modifier = Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(if (isModified) BlueGreenAccent else Color.Transparent)
                )
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Selected Tool Slider: 1 ───────────────── 100
        val slider1to100 = when (selectedCustomiseTool) {
          CustomiseTool.BRIGHTNESS -> ((currentAdjustments.brightness * 50f) + 50f).coerceIn(1f, 100f)
          CustomiseTool.CONTRAST -> (currentAdjustments.contrast * 50f).coerceIn(1f, 100f)
          CustomiseTool.HIGHLIGHTS -> ((currentAdjustments.highlights * 50f) + 50f).coerceIn(1f, 100f)
          CustomiseTool.SHADOWS -> ((currentAdjustments.shadows * 50f) + 50f).coerceIn(1f, 100f)
          CustomiseTool.WHITES -> ((currentAdjustments.whites * 50f) + 50f).coerceIn(1f, 100f)
          CustomiseTool.BLACKS -> ((currentAdjustments.blacks * 50f) + 50f).coerceIn(1f, 100f)
          CustomiseTool.TEMPERATURE -> ((currentAdjustments.temperature * 50f) + 50f).coerceIn(1f, 100f)
          CustomiseTool.SHARPEN -> ((currentAdjustments.sharpness * 99f) + 1f).coerceIn(1f, 100f)
          CustomiseTool.CLARITY -> ((currentAdjustments.clarity * 99f) + 1f).coerceIn(1f, 100f)
          CustomiseTool.FADE -> ((currentAdjustments.fade * 99f) + 1f).coerceIn(1f, 100f)
          CustomiseTool.VIGNETTE -> ((currentAdjustments.vignette * 99f) + 1f).coerceIn(1f, 100f)
          CustomiseTool.GRAIN -> ((currentAdjustments.grain * 99f) + 1f).coerceIn(1f, 100f)
        }

        Surface(
          shape = RoundedCornerShape(10.dp),
          color = Color(0xFF0A1F26),
          border = BorderStroke(1.dp, BlueGreenBorder),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                  selectedCustomiseTool.icon,
                  contentDescription = null,
                  tint = BlueGreenAccent,
                  modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                  text = selectedCustomiseTool.title,
                  color = Color.White,
                  fontWeight = FontWeight.Bold,
                  fontSize = 12.sp
                )
              }

              Text(
                text = "${slider1to100.roundToInt()}",
                color = BlueGreenAccent,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
              )
            }

            Slider(
              value = slider1to100,
              onValueChange = { newVal ->
                val updated = when (selectedCustomiseTool) {
                  CustomiseTool.BRIGHTNESS -> currentAdjustments.copy(brightness = (newVal - 50f) / 50f)
                  CustomiseTool.CONTRAST -> currentAdjustments.copy(contrast = newVal / 50f)
                  CustomiseTool.HIGHLIGHTS -> currentAdjustments.copy(highlights = (newVal - 50f) / 50f)
                  CustomiseTool.SHADOWS -> currentAdjustments.copy(shadows = (newVal - 50f) / 50f)
                  CustomiseTool.WHITES -> currentAdjustments.copy(whites = (newVal - 50f) / 50f)
                  CustomiseTool.BLACKS -> currentAdjustments.copy(blacks = (newVal - 50f) / 50f)
                  CustomiseTool.TEMPERATURE -> currentAdjustments.copy(temperature = (newVal - 50f) / 50f)
                  CustomiseTool.SHARPEN -> currentAdjustments.copy(sharpness = (newVal - 1f) / 99f)
                  CustomiseTool.CLARITY -> currentAdjustments.copy(clarity = (newVal - 1f) / 99f)
                  CustomiseTool.FADE -> currentAdjustments.copy(fade = (newVal - 1f) / 99f)
                  CustomiseTool.VIGNETTE -> currentAdjustments.copy(vignette = (newVal - 1f) / 99f)
                  CustomiseTool.GRAIN -> currentAdjustments.copy(grain = (newVal - 1f) / 99f)
                }
                onAdjustmentsChange(updated)
              },
              valueRange = 1f..100f,
              colors = SliderDefaults.colors(
                thumbColor = BlueGreenAccent,
                activeTrackColor = BlueGreenAccent,
                inactiveTrackColor = BlueGreenBorder
              ),
              modifier = Modifier
                .height(30.dp)
                .testTag("custom_tool_slider")
            )

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Text("1", color = TextSecondary, fontSize = 9.5.sp)
              TextButton(
                onClick = {
                  val reset = when (selectedCustomiseTool) {
                    CustomiseTool.BRIGHTNESS -> currentAdjustments.copy(brightness = 0f)
                    CustomiseTool.CONTRAST -> currentAdjustments.copy(contrast = 1f)
                    CustomiseTool.HIGHLIGHTS -> currentAdjustments.copy(highlights = 0f)
                    CustomiseTool.SHADOWS -> currentAdjustments.copy(shadows = 0f)
                    CustomiseTool.WHITES -> currentAdjustments.copy(whites = 0f)
                    CustomiseTool.BLACKS -> currentAdjustments.copy(blacks = 0f)
                    CustomiseTool.TEMPERATURE -> currentAdjustments.copy(temperature = 0f)
                    CustomiseTool.SHARPEN -> currentAdjustments.copy(sharpness = 0f)
                    CustomiseTool.CLARITY -> currentAdjustments.copy(clarity = 0f)
                    CustomiseTool.FADE -> currentAdjustments.copy(fade = 0f)
                    CustomiseTool.VIGNETTE -> currentAdjustments.copy(vignette = 0f)
                    CustomiseTool.GRAIN -> currentAdjustments.copy(grain = 0f)
                  }
                  onAdjustmentsChange(reset)
                },
                contentPadding = PaddingValues(0.dp)
              ) {
                Text("Reset this tool", color = TextSecondary, fontSize = 10.sp)
              }
              Text("100", color = TextSecondary, fontSize = 9.5.sp)
            }
          }
        }
      }
    }
  }
}

/**
 * Tab 3: VIDEO QUALITY CONTENT
 * Real GPU enhancement algorithms: Auto Enhance, Denoise, Super Clarity, HDR Boost, Anti-Flicker, Color Fix
 */
@Composable
private fun VideoQualityTabContent(
  currentAdjustments: VideoAdjustments,
  onAdjustmentsChange: (VideoAdjustments) -> Unit
) {
  val qualityTools = listOf(
    QualityToolConfig("Auto Enhance", Icons.Default.AutoFixHigh, currentAdjustments.autoEnhance) {
      onAdjustmentsChange(currentAdjustments.copy(autoEnhance = it))
    },
    QualityToolConfig("Denoise", Icons.Default.BlurOn, currentAdjustments.denoise) {
      onAdjustmentsChange(currentAdjustments.copy(denoise = it))
    },
    QualityToolConfig("Super Clarity", Icons.Default.HighQuality, currentAdjustments.superClarity) {
      onAdjustmentsChange(currentAdjustments.copy(superClarity = it))
    },
    QualityToolConfig("HDR Boost", Icons.Default.HdrOn, currentAdjustments.hdrBoost) {
      onAdjustmentsChange(currentAdjustments.copy(hdrBoost = it))
    },
    QualityToolConfig("Anti-Flicker", Icons.Default.MotionPhotosAuto, currentAdjustments.antiFlicker) {
      onAdjustmentsChange(currentAdjustments.copy(antiFlicker = it))
    },
    QualityToolConfig("Color Fix", Icons.Default.ColorLens, currentAdjustments.colorFix) {
      onAdjustmentsChange(currentAdjustments.copy(colorFix = it))
    }
  )

  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .padding(horizontal = 10.dp, vertical = 6.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp)
  ) {
    items(qualityTools) { tool ->
      val value1to100 = (tool.currentValue * 100f).coerceIn(1f, 100f)
      val isActive = tool.currentValue > 0.01f

      Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF0A1F26),
        border = BorderStroke(1.dp, if (isActive) BlueGreenAccent.copy(alpha = 0.6f) else BlueGreenBorder),
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                tool.icon,
                contentDescription = null,
                tint = if (isActive) BlueGreenAccent else TextSecondary,
                modifier = Modifier.size(15.dp)
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = tool.name,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.5.sp
              )
            }

            Text(
              text = if (isActive) "${(tool.currentValue * 100).roundToInt()}%" else "Off",
              color = if (isActive) BlueGreenAccent else TextSecondary,
              fontWeight = FontWeight.Bold,
              fontSize = 11.sp
            )
          }

          Slider(
            value = if (isActive) value1to100 else 1f,
            onValueChange = { newVal ->
              tool.onUpdate((newVal / 100f).coerceIn(0.01f, 1f))
            },
            valueRange = 1f..100f,
            colors = SliderDefaults.colors(
              thumbColor = if (isActive) BlueGreenAccent else TextSecondary,
              activeTrackColor = BlueGreenAccent,
              inactiveTrackColor = BlueGreenBorder
            ),
            modifier = Modifier
              .height(24.dp)
              .testTag("quality_slider_${tool.name.lowercase().replace(" ", "_")}")
          )
        }
      }
    }
  }
}

private data class QualityToolConfig(
  val name: String,
  val icon: ImageVector,
  val currentValue: Float,
  val onUpdate: (Float) -> Unit
)

/**
 * Procedural fallback preview for filters
 */
@Composable
private fun ProceduralFilterPreview(
  type: FilterType,
  modifier: Modifier = Modifier
) {
  val baseColors = remember(type) {
    when (type) {
      FilterType.NONE -> listOf(Color(0xFF334155), Color(0xFF0F172A))
      FilterType.FOUR_K -> listOf(Color(0xFF0284C7), Color(0xFF1E3A8A), Color(0xFF0F172A))
      FilterType.BLACKLIGHT_FIX -> listOf(Color(0xFFD97706), Color(0xFFB45309), Color(0xFF1E1B4B))
      FilterType.ENHANCE -> listOf(Color(0xFF38BDF8), Color(0xFF818CF8), Color(0xFF312E81))
      FilterType.HDR -> listOf(Color(0xFFFF007F), Color(0xFF7928CA), Color(0xFF0284C7))
      FilterType.GLOW -> listOf(Color(0xFFFDE047), Color(0xFFF472B6), Color(0xFF4F46E5))
      FilterType.FOCUS -> listOf(Color(0xFF10B981), Color(0xFF0369A1), Color(0xFF0F172A))
      FilterType.QUALITY_RESTORATION -> listOf(Color(0xFF2DD4BF), Color(0xFF2563EB), Color(0xFF0F172A))
      FilterType.GOLDEN_AUTUMN -> listOf(Color(0xFFF97316), Color(0xFF9A3412), Color(0xFF451A03))
      FilterType.OCEANIC_VIEW -> listOf(Color(0xFF06B6D4), Color(0xFF0369A1), Color(0xFF082F49))
      FilterType.ALMOND -> listOf(Color(0xFFFDE68A), Color(0xFFD4A373), Color(0xFF78350F))
      FilterType.SUNLIGHT_ORANGE_BLUE -> listOf(Color(0xFFFB923C), Color(0xFF0284C7), Color(0xFF0F172A))
      FilterType.CINEMATIC -> listOf(Color(0xFF0D9488), Color(0xFFF97316), Color(0xFF042F2E))
      FilterType.WARM -> listOf(Color(0xFFF59E0B), Color(0xFFD97706), Color(0xFF451A03))
      FilterType.COOL -> listOf(Color(0xFF0284C7), Color(0xFF38BDF8), Color(0xFF082F49))
      FilterType.PORTRAIT -> listOf(Color(0xFFF43F5E), Color(0xFFFB7185), Color(0xFF881337))
      FilterType.BLACK_AND_WHITE -> listOf(Color(0xFFE2E8F0), Color(0xFF64748B), Color(0xFF0F172A))
      FilterType.VINTAGE -> listOf(Color(0xFFB45309), Color(0xFFFDE68A), Color(0xFF451A03))
      FilterType.SATURATION -> listOf(Color(0xFFEC4899), Color(0xFF3B82F6), Color(0xFF1E1B4B))
      FilterType.FILM -> listOf(Color(0xFFA16207), Color(0xFF78350F), Color(0xFF1C1917))
      FilterType.RETRO -> listOf(Color(0xFFD946EF), Color(0xFF8B5CF6), Color(0xFF3B0764))
      FilterType.NATURE -> listOf(Color(0xFF10B981), Color(0xFF047857), Color(0xFF064E3B))
      FilterType.FOOD -> listOf(Color(0xFFEA580C), Color(0xFFFACC15), Color(0xFF7C2D12))
      FilterType.TRAVEL -> listOf(Color(0xFF0284C7), Color(0xFF10B981), Color(0xFF064E3B))
      FilterType.SOCIAL_MEDIA -> listOf(Color(0xFFFF007F), Color(0xFF8B5CF6), Color(0xFF312E81))
    }
  }

  Canvas(modifier = modifier.fillMaxSize()) {
    drawRect(
      brush = Brush.verticalGradient(baseColors),
      size = size
    )
  }
}
