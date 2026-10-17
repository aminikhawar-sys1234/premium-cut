package com.example.ui.components.filter

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
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
import com.example.engine.effects.ColorAdjustmentRanges
import com.example.engine.effects.FilterCatalog
import com.ahstudio.color.core.LutState
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
  CONTRAST("Contrast", Icons.Default.Contrast),
  SATURATION("Saturation", Icons.Default.Palette),
  EXPOSURE("Exposure", Icons.Default.Exposure),
  TEMPERATURE("Temperature", Icons.Default.Thermostat),
  TINT("Tint", Icons.Default.InvertColors),
  HIGHLIGHTS("Highlights", Icons.Default.LightMode),
  SHADOWS("Shadows", Icons.Default.DarkMode),
  SHARPEN("Sharpening", Icons.Default.Details),
  CLARITY("Clarity", Icons.Default.Deblur),
  WHITES("Whites", Icons.Default.Brightness7),
  BLACKS("Blacks", Icons.Default.Brightness4),
  FADE("Fade", Icons.Default.Gradient),
  VIGNETTE("Vignette", Icons.Default.Vignette),
  GRAIN("Grain", Icons.Default.Grain)
}

/**
 * Filter Tools Panel:
 * - 45% screen height bottom-sheet style container over editor
 * - Blue + Green mixed background
 * - Header: Reset (Left) | Filter Tools (Center) | Close X (Right)
 * - Tabs: Filters | Adjust | Video Quality
 * - Filters tab: 4 columns, vertical scroll, "None" first, real GPU filters, zero lag
 * - Adjust tab: Smart Auto + Customise (brightness, contrast, saturation, exposure, temp, tint, highlights, shadows, sharpening, plus working extras)
 * - Video Quality tab: Auto Enhance, Denoise, Clarity, HDR, Anti-Flicker, Color Fix
 */
@Composable
fun FilterToolsPanel(
  viewModel: StudioViewModel,
  modifier: Modifier = Modifier,
  initialTab: FilterToolsTab = FilterToolsTab.FILTERS,
  onClose: () -> Unit = {
    viewModel.flushLiveLook()
    viewModel.setActiveToolbarTab(null)
  }
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

  // The LUT stored on the target clip (the same colour grade the Color panel edits), hoisted
  // so the live status bar can show it and clear it from any tab.
  val lutGrade = remember(lutClip?.colorGradeJson) { ColorEngineHost.decodeOrDefault(lutClip?.colorGradeJson) }
  val liveLutId = lutGrade.lut?.lutId?.takeIf { it.isNotBlank() }
  val liveLutName = liveLutId?.let { id -> ColorEngineHost.BUNDLED_LUTS.firstOrNull { it.first == id }?.second }

  /** Writes (or clears) the bundled LUT look on the target clip and refreshes the preview. */
  val commitActiveLut: (String?, Float) -> Unit = { lutId, intensity ->
    lutClip?.id?.let { id ->
      val next = lutGrade.copy(lut = lutId?.let { LutState(lutId = it, intensity = intensity) })
      ColorEngineHost.applyToEngine(id, next)
      viewModel.timelineEngine.setClipColorGrade(id, if (next.isIdentity()) null else ColorEngineHost.encode(next))
      viewModel.revealClipForLookPreview(viewModel.timelineEngine.timeline.value.videoClips.find { it.id == id })
      viewModel.refreshCurrentFrame(preferClipId = id)
    }
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
    // Publish `adj`, never the remembered state: the shader must receive this exact tick's
    // value so the last drag position is the one on screen.
    // Same reveal the filter taps do: Adjust / Quality edits on a selected-but-offscreen clip
    // must move the playhead, otherwise the change is invisible until the playhead reaches it.
    viewModel.revealClipForLookPreview(selectedClip)
    viewModel.publishLiveLook(selectedClip?.id, adj, currentFilter)
  }

  val applyLook: (FilterSettings) -> Unit = { look ->
    currentFilter = look
    viewModel.revealClipForLookPreview(selectedClip)
    viewModel.publishLiveLook(selectedClip?.id, currentAdjustments, look)
  }

  DisposableEffect(selectedClip?.id) {
    onDispose { viewModel.flushLiveLook() }
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
    modifier = modifier.fillMaxWidth().fillMaxHeight()
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
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
                applyLook(reset)
                lutClip?.id?.let { id ->
                  ColorEngineHost.applyToEngine(id, ColorEngineHost.decodeOrDefault(lutClip.colorGradeJson).copy(lut = null))
                  viewModel.timelineEngine.setClipColorGrade(id, null)
                  viewModel.refreshCurrentFrame(preferClipId = id)
                }
              }
              FilterToolsTab.ADJUST -> {
                applyAdjustments(ColorAdjustmentRanges.resetPrimary(currentAdjustments))
              }
              FilterToolsTab.VIDEO_QUALITY -> {
                applyAdjustments(ColorAdjustmentRanges.resetQuality(currentAdjustments))
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
              lutClipId = lutClip?.id,
              lutClipGradeJson = lutClip?.colorGradeJson,
              baseBitmap = baseThumbnailBitmap,
              onFilterChange = applyLook,
              onCommitLut = commitActiveLut
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

      // 4. LIVE STATUS BAR — exactly what is applied right now, and where.
      //    A slider value alone was never proof that a look reached the picture; this strip
      //    is the panel's own record of the live look, and every chip can be cleared in place.
      LiveLookStatusBar(
        scope = if (selectedClip != null) "Clip: ${selectedClip.name.take(14)}" else "Project",
        entries = appliedLookEntries(
          adjustments = currentAdjustments,
          filter = currentFilter,
          lutName = liveLutName,
          onAdjustmentsChange = applyAdjustments,
          onFilterChange = applyLook,
          onClearLut = { commitActiveLut(null, 1f) }
        ),
        modifier = Modifier.testTag("live_look_status_bar")
      )
    }
  }
}

/** One chip in the live status bar: a single parameter that is currently off its default. */
internal data class AppliedLookEntry(
  val key: String,
  val label: String,
  val value: String,
  val onClear: () -> Unit
)

/**
 * Every filter / adjustment / quality value that is currently non-default, newest control
 * first. Each entry clears just that one parameter, so "where did I set something" is always
 * answerable without resetting the whole look.
 */
internal fun appliedLookEntries(
  adjustments: VideoAdjustments,
  filter: FilterSettings,
  lutName: String?,
  onAdjustmentsChange: (VideoAdjustments) -> Unit,
  onFilterChange: (FilterSettings) -> Unit,
  onClearLut: () -> Unit
): List<AppliedLookEntry> {
  val out = ArrayList<AppliedLookEntry>()

  fun pct(value: Float): String = "${(value.coerceIn(0f, 1f) * 100f).roundToInt()}%"
  fun signed(value: Float): String = if (value >= 0f) "+${(value * 100f).roundToInt()}" else "${(value * 100f).roundToInt()}"

  if (filter.type != FilterType.NONE && filter.intensity > 0.01f) {
    out += AppliedLookEntry(
      key = "filter",
      label = filter.type.displayName,
      value = pct(filter.intensity)
    ) {
      onFilterChange(FilterSettings(type = FilterType.NONE, intensity = 1.0f))
    }
  }
  if (!lutName.isNullOrBlank()) {
    out += AppliedLookEntry("lut", lutName, "LUT", onClearLut)
  }
  val adj = adjustments
  if (abs(adj.brightness) > 0.01f) out += AppliedLookEntry("brightness", "Brightness", signed(adj.brightness)) { onAdjustmentsChange(adj.copy(brightness = 0f)) }
  if (abs(adj.contrast - 1f) > 0.01f) out += AppliedLookEntry("contrast", "Contrast", String.format(java.util.Locale.US, "%.2f", adj.contrast)) { onAdjustmentsChange(adj.copy(contrast = 1f)) }
  if (abs(adj.saturation - 1f) > 0.01f) out += AppliedLookEntry("saturation", "Saturation", String.format(java.util.Locale.US, "%.2f", adj.saturation)) { onAdjustmentsChange(adj.copy(saturation = 1f)) }
  if (abs(adj.exposure) > 0.01f) out += AppliedLookEntry("exposure", "Exposure", signed(adj.exposure)) { onAdjustmentsChange(adj.copy(exposure = 0f)) }
  if (abs(adj.temperature) > 0.01f) out += AppliedLookEntry("temperature", "Temp", signed(adj.temperature)) { onAdjustmentsChange(adj.copy(temperature = 0f)) }
  if (abs(adj.tint) > 0.01f) out += AppliedLookEntry("tint", "Tint", signed(adj.tint)) { onAdjustmentsChange(adj.copy(tint = 0f)) }
  if (abs(adj.highlights) > 0.01f) out += AppliedLookEntry("highlights", "Highlights", signed(adj.highlights)) { onAdjustmentsChange(adj.copy(highlights = 0f)) }
  if (abs(adj.shadows) > 0.01f) out += AppliedLookEntry("shadows", "Shadows", signed(adj.shadows)) { onAdjustmentsChange(adj.copy(shadows = 0f)) }
  if (abs(adj.whites) > 0.01f) out += AppliedLookEntry("whites", "Whites", signed(adj.whites)) { onAdjustmentsChange(adj.copy(whites = 0f)) }
  if (abs(adj.blacks) > 0.01f) out += AppliedLookEntry("blacks", "Blacks", signed(adj.blacks)) { onAdjustmentsChange(adj.copy(blacks = 0f)) }
  if (adj.sharpness > 0.01f) out += AppliedLookEntry("sharpness", "Sharpen", pct(adj.sharpness)) { onAdjustmentsChange(adj.copy(sharpness = 0f)) }
  if (adj.clarity > 0.01f) out += AppliedLookEntry("clarity", "Clarity", pct(adj.clarity)) { onAdjustmentsChange(adj.copy(clarity = 0f)) }
  if (adj.vignette > 0.01f) out += AppliedLookEntry("vignette", "Vignette", pct(adj.vignette)) { onAdjustmentsChange(adj.copy(vignette = 0f)) }
  if (adj.fade > 0.01f) out += AppliedLookEntry("fade", "Fade", pct(adj.fade)) { onAdjustmentsChange(adj.copy(fade = 0f)) }
  if (adj.grain > 0.01f) out += AppliedLookEntry("grain", "Grain", pct(adj.grain)) { onAdjustmentsChange(adj.copy(grain = 0f)) }
  if (adj.autoEnhance > 0.01f) out += AppliedLookEntry("autoEnhance", "Auto Enhance", pct(adj.autoEnhance)) { onAdjustmentsChange(adj.copy(autoEnhance = 0f)) }
  if (adj.colorFix > 0.01f) out += AppliedLookEntry("colorFix", "Color Fix", pct(adj.colorFix)) { onAdjustmentsChange(adj.copy(colorFix = 0f)) }
  if (adj.colorCorrect > 0.01f) out += AppliedLookEntry("colorCorrect", "Color Correct", pct(adj.colorCorrect)) { onAdjustmentsChange(adj.copy(colorCorrect = 0f)) }
  if (adj.hdrBoost > 0.01f) out += AppliedLookEntry("hdrBoost", "HDR Look", pct(adj.hdrBoost)) { onAdjustmentsChange(adj.copy(hdrBoost = 0f)) }
  if (adj.superClarity > 0.01f) out += AppliedLookEntry("superClarity", "Super Clarity", pct(adj.superClarity)) { onAdjustmentsChange(adj.copy(superClarity = 0f)) }
  if (adj.denoise > 0.01f) out += AppliedLookEntry("denoise", "Denoise", pct(adj.denoise)) { onAdjustmentsChange(adj.copy(denoise = 0f)) }
  if (adj.antiFlicker > 0.01f) out += AppliedLookEntry("antiFlicker", "Flicker", pct(adj.antiFlicker)) { onAdjustmentsChange(adj.copy(antiFlicker = 0f)) }
  return out
}

/**
 * The strip under the tabs: the scope (clip vs project) plus one removable chip per live
 * value. It makes "which slider did I move" visible at a glance on every tab.
 */
@Composable
private fun LiveLookStatusBar(
  scope: String,
  entries: List<AppliedLookEntry>,
  modifier: Modifier = Modifier
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .background(Color(0xFF06151B))
      .padding(horizontal = 8.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      text = scope,
      color = BlueGreenAccent,
      fontSize = 9.5.sp,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
      modifier = Modifier.testTag("live_look_scope")
    )
    Spacer(modifier = Modifier.width(6.dp))
    if (entries.isEmpty()) {
      Text(
        text = "No filter or colour change applied",
        color = TextSecondary,
        fontSize = 9.5.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f)
      )
    } else {
      Row(
        modifier = Modifier
          .weight(1f)
          .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
      ) {
        entries.forEach { entry ->
          Surface(
            shape = RoundedCornerShape(8.dp),
            color = BlueGreenAccent.copy(alpha = 0.16f),
            border = BorderStroke(1.dp, BlueGreenAccent.copy(alpha = 0.55f)),
            contentColor = Color.White,
            onClick = entry.onClear,
            modifier = Modifier.testTag("live_look_chip_${entry.key}")
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(text = entry.label, color = Color.White, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold)
              Spacer(modifier = Modifier.width(3.dp))
              Text(text = entry.value, color = BlueGreenAccent, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
              Spacer(modifier = Modifier.width(3.dp))
              Icon(
                Icons.Default.Close,
                contentDescription = "Clear ${entry.label}",
                tint = TextSecondary,
                modifier = Modifier.size(10.dp)
              )
            }
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

/**
 * Tab 1: FILTERS CONTENT
 * Category chips, then a full-height 4-column thumbnail grid of built-in presets and LUT looks.
 */
@Composable
private fun FiltersTabContent(
  viewModel: StudioViewModel,
  currentFilter: FilterSettings,
  lutClipId: String?,
  lutClipGradeJson: String?,
  baseBitmap: Bitmap?,
  onFilterChange: (FilterSettings) -> Unit,
  onCommitLut: (String?, Float) -> Unit = { _, _ -> }
) {
  val context = LocalContext.current
  var selectedCategory by remember { mutableStateOf(CAT_ALL) }
  val categories = listOf(CAT_ALL, CAT_FAVOURITES, CAT_RECENT, CAT_LUT)

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
    if (lutClipId == null) return
    onCommitLut(lutId, intensity)
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

  val allPresets = remember(currentFilter.type) {
    val catalog = FilterCatalog.SELECTABLE
    val legacy = currentFilter.type.takeIf { it != FilterType.NONE && it !in catalog }
    catalog + listOfNotNull(legacy)
  }
  val luts = remember { ColorEngineHost.BUNDLED_LUTS.map { LookItem.Lut(it.first, it.second) } }

  fun resolve(key: String): LookItem? =
    if (key.startsWith("lut:")) luts.firstOrNull { it.key == key }
    else allPresets.firstOrNull { it.name == key }?.let { LookItem.Preset(it) }

  val items: List<LookItem> = remember(selectedCategory, favourites, recent, allPresets) {
    when (selectedCategory) {
      CAT_FAVOURITES -> favourites.mapNotNull { resolve(it) }
      CAT_RECENT -> recent.mapNotNull { resolve(it) }
      CAT_LUT -> listOf<LookItem>(LookItem.NoLut) + luts
      else -> listOf<LookItem>(LookItem.Preset(FilterType.NONE)) + allPresets.map { LookItem.Preset(it) }
    }
  }
  val capabilityNote = FilterCatalog.capabilityNote(currentFilter.type)

  val gridState = rememberLazyGridState()

  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(horizontal = 8.dp, vertical = 4.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp)
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      categories.forEach { cat ->
        val isCatSelected = selectedCategory == cat
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = if (isCatSelected) BlueGreenAccent.copy(alpha = 0.25f) else Color(0xFF0C2028),
          border = BorderStroke(1.dp, if (isCatSelected) BlueGreenAccent else BlueGreenBorder),
          modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { selectedCategory = cat }
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

    if (!capabilityNote.isNullOrBlank() && currentFilter.type != FilterType.NONE) {
      Text(
        text = capabilityNote,
        color = TextSecondary,
        fontSize = 9.5.sp,
        lineHeight = 12.sp,
        modifier = Modifier
          .fillMaxWidth()
          .testTag("filter_capability_note")
      )
    }

    // Selected look + intensity. Without this the preset strength was settable from nowhere
    // in the UI: a tapped look always landed at 100% with no way to dial it back.
    if (currentFilter.type != FilterType.NONE) {
      Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF0A1F26),
        border = BorderStroke(1.dp, BlueGreenBorder),
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = currentFilter.type.displayName,
              color = Color.White,
              fontWeight = FontWeight.Bold,
              fontSize = 11.5.sp,
              modifier = Modifier.testTag("selected_filter_name")
            )
            Text(
              text = "${(currentFilter.intensity.coerceIn(0f, 1f) * 100f).roundToInt()}%",
              color = BlueGreenAccent,
              fontWeight = FontWeight.Bold,
              fontSize = 11.5.sp,
              modifier = Modifier.testTag("selected_filter_intensity")
            )
          }
          Slider(
            value = (currentFilter.intensity.coerceIn(0f, 1f) * 100f),
            onValueChange = { v ->
              onFilterChange(
                currentFilter.copy(
                  type = currentFilter.type,
                  intensity = (v / 100f).coerceIn(0f, 1f),
                  enabled = true
                )
              )
            },
            valueRange = 0f..100f,
            colors = SliderDefaults.colors(
              thumbColor = BlueGreenAccent,
              activeTrackColor = BlueGreenAccent,
              inactiveTrackColor = BlueGreenBorder
            ),
            modifier = Modifier
              .height(24.dp)
              .testTag("filter_intensity_slider")
          )
        }
      }
    }

    if (items.isEmpty()) {
      Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
        Text(
          text = when (selectedCategory) {
            CAT_FAVOURITES -> "No favorites yet. Tap the star on a look."
            CAT_RECENT -> "Looks you use will show up here."
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
          NeutralLookSwatch(colorFilter = composeColorFilter)
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
              SmartAutoTool.AUTO_ADJUST -> (currentAdjustments.autoEnhance * 100).roundToInt().coerceIn(0, 100)
              SmartAutoTool.COLOR_FIXINGS -> (currentAdjustments.colorFix * 100).roundToInt().coerceIn(0, 100)
              SmartAutoTool.COLOR_CORRECT -> (currentAdjustments.colorCorrect * 100).roundToInt().coerceIn(0, 100)
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
                  text = if (isActive) "$valueStrength%" else "Off",
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
          SmartAutoTool.AUTO_ADJUST -> (currentAdjustments.autoEnhance * 100f).coerceIn(0f, 100f)
          SmartAutoTool.COLOR_FIXINGS -> (currentAdjustments.colorFix * 100f).coerceIn(0f, 100f)
          SmartAutoTool.COLOR_CORRECT -> (currentAdjustments.colorCorrect * 100f).coerceIn(0f, 100f)
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
                val fraction = (newVal / 100f).coerceIn(0f, 1f)
                val updated = when (selectedSmartAutoTool) {
                  SmartAutoTool.AUTO_ADJUST -> currentAdjustments.copy(autoEnhance = fraction)
                  SmartAutoTool.COLOR_FIXINGS -> currentAdjustments.copy(colorFix = fraction)
                  SmartAutoTool.COLOR_CORRECT -> currentAdjustments.copy(colorCorrect = fraction)
                }
                onAdjustmentsChange(updated)
              },
              valueRange = 0f..100f,
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
              Text("0 (Off)", color = TextSecondary, fontSize = 9.5.sp)
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
            val isModified = !customiseSpec(tool).isDefault(customiseValue(currentAdjustments, tool))

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

        val spec = customiseSpec(selectedCustomiseTool)
        val sliderValue = customiseValue(currentAdjustments, selectedCustomiseTool).coerceIn(spec.min, spec.max)

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
                text = spec.format(sliderValue),
                color = BlueGreenAccent,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                modifier = Modifier.testTag("custom_tool_value")
              )
            }

            Slider(
              value = sliderValue,
              onValueChange = { newVal ->
                onAdjustmentsChange(applyCustomiseValue(currentAdjustments, selectedCustomiseTool, spec.coerce(newVal)))
              },
              valueRange = spec.min..spec.max,
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
              Text(spec.format(spec.min), color = TextSecondary, fontSize = 9.5.sp)
              TextButton(
                onClick = {
                  onAdjustmentsChange(applyCustomiseValue(currentAdjustments, selectedCustomiseTool, spec.default))
                },
                contentPadding = PaddingValues(0.dp)
              ) {
                Text("Reset this tool", color = TextSecondary, fontSize = 10.sp)
              }
              Text(spec.format(spec.max), color = TextSecondary, fontSize = 9.5.sp)
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
    QualityToolConfig("HDR Look (SDR)", Icons.Default.HdrOn, currentAdjustments.hdrBoost) {
      onAdjustmentsChange(currentAdjustments.copy(hdrBoost = it))
    },
    QualityToolConfig("Flicker soften", Icons.Default.MotionPhotosAuto, currentAdjustments.antiFlicker) {
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
    item {
      Text(
        text = "HDR Look is an SDR contrast/saturation boost. Export stays Rec.709 — not HDR10 or HLG. " +
          "Flicker soften is a spatial denoise mix, not a temporal flicker lock.",
        color = TextSecondary,
        fontSize = 9.5.sp,
        lineHeight = 12.sp,
        modifier = Modifier.testTag("video_quality_capability_note")
      )
    }
    items(qualityTools) { tool ->
      val value1to100 = (tool.currentValue * 100f).coerceIn(0f, 100f)
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
            value = value1to100,
            onValueChange = { newVal ->
              tool.onUpdate((newVal / 100f).coerceIn(0f, 1f))
            },
            valueRange = 0f..100f,
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

private fun customiseSpec(tool: CustomiseTool): ColorAdjustmentRanges.Spec = when (tool) {
  CustomiseTool.BRIGHTNESS -> ColorAdjustmentRanges.brightness
  CustomiseTool.CONTRAST -> ColorAdjustmentRanges.contrast
  CustomiseTool.SATURATION -> ColorAdjustmentRanges.saturation
  CustomiseTool.EXPOSURE -> ColorAdjustmentRanges.exposure
  CustomiseTool.TEMPERATURE -> ColorAdjustmentRanges.temperature
  CustomiseTool.TINT -> ColorAdjustmentRanges.tint
  CustomiseTool.HIGHLIGHTS -> ColorAdjustmentRanges.highlights
  CustomiseTool.SHADOWS -> ColorAdjustmentRanges.shadows
  CustomiseTool.SHARPEN -> ColorAdjustmentRanges.sharpness
  CustomiseTool.CLARITY -> ColorAdjustmentRanges.clarity
  CustomiseTool.WHITES -> ColorAdjustmentRanges.whites
  CustomiseTool.BLACKS -> ColorAdjustmentRanges.blacks
  CustomiseTool.FADE -> ColorAdjustmentRanges.fade
  CustomiseTool.VIGNETTE -> ColorAdjustmentRanges.vignette
  CustomiseTool.GRAIN -> ColorAdjustmentRanges.grain
}

private fun customiseValue(adj: VideoAdjustments, tool: CustomiseTool): Float = when (tool) {
  CustomiseTool.BRIGHTNESS -> adj.brightness
  CustomiseTool.CONTRAST -> adj.contrast
  CustomiseTool.SATURATION -> adj.saturation
  CustomiseTool.EXPOSURE -> adj.exposure
  CustomiseTool.TEMPERATURE -> adj.temperature
  CustomiseTool.TINT -> adj.tint
  CustomiseTool.HIGHLIGHTS -> adj.highlights
  CustomiseTool.SHADOWS -> adj.shadows
  CustomiseTool.SHARPEN -> adj.sharpness
  CustomiseTool.CLARITY -> adj.clarity
  CustomiseTool.WHITES -> adj.whites
  CustomiseTool.BLACKS -> adj.blacks
  CustomiseTool.FADE -> adj.fade
  CustomiseTool.VIGNETTE -> adj.vignette
  CustomiseTool.GRAIN -> adj.grain
}

private fun applyCustomiseValue(adj: VideoAdjustments, tool: CustomiseTool, value: Float): VideoAdjustments =
  when (tool) {
    CustomiseTool.BRIGHTNESS -> adj.copy(brightness = value)
    CustomiseTool.CONTRAST -> adj.copy(contrast = value)
    CustomiseTool.SATURATION -> adj.copy(saturation = value)
    CustomiseTool.EXPOSURE -> adj.copy(exposure = value)
    CustomiseTool.TEMPERATURE -> adj.copy(temperature = value)
    CustomiseTool.TINT -> adj.copy(tint = value)
    CustomiseTool.HIGHLIGHTS -> adj.copy(highlights = value)
    CustomiseTool.SHADOWS -> adj.copy(shadows = value)
    CustomiseTool.SHARPEN -> adj.copy(sharpness = value)
    CustomiseTool.CLARITY -> adj.copy(clarity = value)
    CustomiseTool.WHITES -> adj.copy(whites = value)
    CustomiseTool.BLACKS -> adj.copy(blacks = value)
    CustomiseTool.FADE -> adj.copy(fade = value)
    CustomiseTool.VIGNETTE -> adj.copy(vignette = value)
    CustomiseTool.GRAIN -> adj.copy(grain = value)
  }

private data class QualityToolConfig(
  val name: String,
  val icon: ImageVector,
  val currentValue: Float,
  val onUpdate: (Float) -> Unit
)

/**
 * Shared neutral swatch so a missing clip thumbnail still shows the real ColorMatrix,
 * not a per-filter fake color cartoon.
 */
@Composable
private fun NeutralLookSwatch(
  colorFilter: ColorFilter?,
  modifier: Modifier = Modifier
) {
  val bmp = remember { FilterLookSwatch.bitmap() }
  Image(
    bitmap = bmp.asImageBitmap(),
    contentDescription = null,
    colorFilter = colorFilter,
    contentScale = ContentScale.Crop,
    modifier = modifier.fillMaxSize()
  )
}

private object FilterLookSwatch {
  fun bitmap(): Bitmap {
    val w = 32
    val h = 32
    val px = IntArray(w * h)
    for (y in 0 until h) {
      val t = y / (h - 1).toFloat()
      val r = ((0.55f * (1f - t) + 0.22f * t) * 255f).toInt().coerceIn(0, 255)
      val g = ((0.48f * (1f - t) + 0.28f * t) * 255f).toInt().coerceIn(0, 255)
      val b = ((0.40f * (1f - t) + 0.18f * t) * 255f).toInt().coerceIn(0, 255)
      val row = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
      for (x in 0 until w) {
        val band = when {
          x < w / 3 -> 0x00_18_28
          x < (2 * w) / 3 -> 0x00_00_00
          else -> 0x20_10_00
        }
        px[y * w + x] = row + band
      }
    }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
  }
}
