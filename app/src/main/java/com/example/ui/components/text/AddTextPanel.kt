package com.example.ui.components.text

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatAlignRight
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.TextClip
import com.example.engine.SelectedTrackElement
import com.example.engine.text.TextMotionCatalog
import com.example.engine.text.registry.RegisteredTextTemplate
import com.example.engine.text.registry.TextTemplateCatalog
import com.example.ui.StudioViewModel
import com.example.util.FontManager
import com.example.util.FontOption
import java.util.UUID

enum class AddTextSubTool(val label: String, val tag: String) {
  TEMPLATES("Templates", "subtool_templates"),
  FONTS("Fonts", "subtool_fonts"),
  STYLES("Style", "subtool_styles"),
  ANIMATOR("Animate", "subtool_animator")
}

private val Accent = Color(0xFF10B981)
private val AccentSoft = Color(0xFF34D399)
private val ChipIdle = Color(0xFF0F2C2C)
private val ChipIdleBorder = Color(0xFF194444)
private val PanelFill = Color(0xFF0E2530)
private val ColorSwatches = listOf(
  0xFFFFFFFF, 0xFF000000, 0xFFEF4444, 0xFFFACC15, 0xFF10B981,
  0xFF00E5FF, 0xFF3B82F6, 0xFF8B5CF6, 0xFFEC4899, 0xFFF97316
)

@Composable
fun AddTextPanel(
  viewModel: StudioViewModel,
  onClose: () -> Unit,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val timeline by viewModel.timelineEngine.timeline.collectAsState()
  val selectedElement by viewModel.timelineEngine.selectedElement.collectAsState()
  val currentPosMs by viewModel.timelineEngine.currentPositionMs.collectAsState()

  val selectedClip = remember(selectedElement, timeline.textClips) {
    (selectedElement as? SelectedTrackElement.Text)?.let { sel ->
      timeline.textClips.find { it.id == sel.clipId }
    }
  }

  var draftId by remember { mutableStateOf(selectedClip?.id) }
  var textValue by remember { mutableStateOf(selectedClip?.text.orEmpty()) }
  var activeSubTool by remember { mutableStateOf(AddTextSubTool.TEMPLATES) }

  LaunchedEffect(selectedClip?.id) {
    if (selectedClip != null) {
      draftId = selectedClip.id
      textValue = selectedClip.text
    }
  }

  val currentClip = draftId?.let { id -> timeline.textClips.find { it.id == id } } ?: selectedClip

  fun commit(updated: TextClip) {
    viewModel.timelineEngine.updateTextClip(updated)
  }

  fun ensureClip(initialText: String = textValue): TextClip {
    currentClip?.let { return it }
    val newId = UUID.randomUUID().toString()
    val created = TextClip(
      id = newId,
      text = initialText,
      timelineStartMs = currentPosMs.coerceAtLeast(0L),
      durationMs = 3000L,
      fontSizeSp = 28f,
      textColor = 0xFFFFFFFF,
      strokeWidth = 0f,
      alignment = "Center",
      animationType = "None",
      animationIn = "None",
      animationOut = "None"
    )
    draftId = newId
    viewModel.timelineEngine.addTextClipObject(created)
    return created
  }

  fun discardBlankAndClose() {
    val clip = currentClip
    if (clip != null && clip.text.isBlank()) {
      viewModel.timelineEngine.deleteClips(setOf(clip.id))
    }
    onClose()
  }

  val panelBackground = Brush.verticalGradient(
    colors = listOf(Color(0xFF0A2B27), Color(0xFF0F263B))
  )

  Column(
    modifier = modifier
      .fillMaxWidth()
      .wrapContentHeight()
      .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
      .background(panelBackground)
      .border(1.dp, Color(0xFF164746), RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
      .padding(horizontal = 14.dp, vertical = 6.dp)
      .testTag("add_text_panel")
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(bottom = 4.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      IconButton(
        onClick = { discardBlankAndClose() },
        modifier = Modifier.size(32.dp).testTag("add_text_close_btn")
      ) {
        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(20.dp))
      }
      Text("Text", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
      IconButton(
        onClick = {
          if (textValue.isBlank()) {
            discardBlankAndClose()
          } else {
            val clip = ensureClip(textValue)
            commit(clip.copy(text = textValue))
            onClose()
          }
        },
        modifier = Modifier.size(32.dp).testTag("add_text_confirm_btn")
      ) {
        Icon(Icons.Default.Check, contentDescription = "Confirm", tint = Accent, modifier = Modifier.size(20.dp))
      }
    }

    Surface(
      shape = RoundedCornerShape(10.dp),
      color = Color(0xFF071B20),
      border = androidx.compose.foundation.BorderStroke(1.dp, Accent),
      modifier = Modifier
        .fillMaxWidth()
        .height(48.dp)
        .padding(vertical = 2.dp)
    ) {
      Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart
      ) {
        if (textValue.isEmpty()) {
          Text("Type text…", color = Color(0xFF64748B), fontSize = 14.sp)
        }
        BasicTextField(
          value = textValue,
          onValueChange = { newText ->
            textValue = newText
            val clip = currentClip
            if (clip != null) {
              commit(clip.copy(text = newText))
            } else if (newText.isNotBlank()) {
              ensureClip(newText)
            }
          },
          textStyle = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
          cursorBrush = SolidColor(Accent),
          modifier = Modifier.fillMaxWidth().testTag("add_text_input_field")
        )
      }
    }

    Spacer(modifier = Modifier.height(4.dp))

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState())
        .padding(vertical = 2.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      AddTextSubTool.values().forEach { tool ->
        val isSelected = activeSubTool == tool
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = if (isSelected) Accent else ChipIdle,
          border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) AccentSoft else ChipIdleBorder),
          modifier = Modifier.clickable { activeSubTool = tool }.testTag(tool.tag)
        ) {
          Text(
            text = tool.label,
            color = if (isSelected) Color(0xFF06221D) else Color.White,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
          )
        }
      }
    }

    Spacer(modifier = Modifier.height(4.dp))

    Box(modifier = Modifier.fillMaxWidth().heightIn(min = 168.dp, max = 280.dp)) {
      when (activeSubTool) {
        AddTextSubTool.TEMPLATES -> TemplatesSubToolView(
          currentClip = currentClip,
          onApplyTemplate = { tmpl ->
            val clip = currentClip ?: ensureClip()
            val updated = if (tmpl == null) {
              TextTemplateCatalog.resetStyle(clip)
            } else {
              TextTemplateCatalog.applyStyle(clip, tmpl.templateClip, keepText = true)
            }
            commit(updated)
          }
        )
        AddTextSubTool.FONTS -> FontsSubToolView(
          context = context,
          currentClip = currentClip,
          onSelectFont = { font ->
            val clip = currentClip ?: ensureClip()
            commit(
              clip.copy(
                fontFamily = font.id,
                customFontPath = font.filePath
              )
            )
          }
        )
        AddTextSubTool.STYLES -> StylesSubToolView(
          currentClip = currentClip,
          onUpdateClip = { commit(it) }
        )
        AddTextSubTool.ANIMATOR -> AnimateSubToolView(
          currentClip = currentClip,
          onUpdateClip = { commit(it) }
        )
      }
    }
  }
}

// ------------------------------------------------------------------------------------------------
// 1. TEMPLATES SUB-TOOL (4-Column Grid, First option: "None", zero dummy/fake templates)
// ------------------------------------------------------------------------------------------------
@Composable
private fun TemplatesSubToolView(
  currentClip: TextClip?,
  onApplyTemplate: (RegisteredTextTemplate?) -> Unit
) {
  val installedTemplates = rememberInstalledTextTemplates()

  TextTemplateGridViewport {
    TextTemplateCardGrid(
      templates = installedTemplates,
      selectedClip = currentClip,
      showNone = true,
      onNone = { onApplyTemplate(null) },
      onApply = { onApplyTemplate(it) },
      modifier = Modifier.fillMaxSize()
    )
  }
}

@Composable
private fun FontsSubToolView(
  context: android.content.Context,
  currentClip: TextClip?,
  onSelectFont: (FontOption) -> Unit
) {
  var selectedCategory by remember { mutableStateOf(FontManager.FONT_CATEGORIES.first()) }
  var refreshTrigger by remember { mutableStateOf(0) }
  val fonts = remember(selectedCategory, refreshTrigger) {
    FontManager.fontsForCategory(context, selectedCategory)
  }

  val fontPickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument()
  ) { uri: Uri? ->
    if (uri != null && FontManager.importFont(context, uri) != null) {
      selectedCategory = "Imported"
      refreshTrigger++
    }
  }

  Column(modifier = Modifier.fillMaxSize()) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState())
        .padding(bottom = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      FontManager.FONT_CATEGORIES.forEach { cat ->
        val isSelected = selectedCategory == cat
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = if (isSelected) Accent else ChipIdle,
          border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) AccentSoft else ChipIdleBorder),
          modifier = Modifier.clickable { selectedCategory = cat }
        ) {
          Text(
            text = cat,
            color = if (isSelected) Color(0xFF06221D) else Color.White,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
          )
        }
      }
    }

    LazyVerticalGrid(
      columns = GridCells.Fixed(4),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.fillMaxSize().testTag("fonts_grid")
    ) {
      item {
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = Color(0xFF123B37),
          border = androidx.compose.foundation.BorderStroke(1.dp, Accent),
          modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
            .clickable { fontPickerLauncher.launch(FontManager.FONT_PICKER_MIME_TYPES) }
            .testTag("import_font_card")
        ) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(4.dp)
          ) {
            Icon(Icons.Default.Add, contentDescription = "Import font", tint = Accent, modifier = Modifier.size(18.dp))
            Text("Import", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
          }
        }
      }

      items(fonts, key = { "${it.category}:${it.id}:${it.filePath}" }) { font ->
        val isSelected = if (font.isCustom) {
          currentClip?.customFontPath == font.filePath
        } else {
          currentClip?.fontFamily == font.id && currentClip.customFontPath.isNullOrBlank()
        }
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = if (isSelected) Accent.copy(alpha = 0.25f) else PanelFill,
          border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) Accent else Color(0xFF1A4557)),
          modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
            .clickable { onSelectFont(font) }
        ) {
          Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(4.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Text(
                text = font.nativeSample.ifBlank { "Aa" },
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
              )
              Text(
                text = font.name,
                color = Color(0xFFCBD5E1),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun StylesSubToolView(
  currentClip: TextClip?,
  onUpdateClip: (TextClip) -> Unit
) {
  val clip = currentClip ?: return
  var colorTarget by remember { mutableStateOf("Text") }

  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      AlignChip(Icons.Default.FormatAlignLeft, clip.alignment.equals("Left", true)) {
        onUpdateClip(clip.copy(alignment = "Left"))
      }
      AlignChip(Icons.Default.FormatAlignCenter, clip.alignment.equals("Center", true) || clip.alignment.isBlank()) {
        onUpdateClip(clip.copy(alignment = "Center"))
      }
      AlignChip(Icons.Default.FormatAlignRight, clip.alignment.equals("Right", true)) {
        onUpdateClip(clip.copy(alignment = "Right"))
      }
      Spacer(Modifier.weight(1f))
      StyleToggle(Icons.Default.FormatBold, clip.fontWeight >= 700) {
        onUpdateClip(clip.copy(fontWeight = if (clip.fontWeight >= 700) 400 else 700))
      }
      StyleToggle(Icons.Default.FormatItalic, clip.isItalic) {
        onUpdateClip(clip.copy(isItalic = !clip.isItalic))
      }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      listOf(0f to "None", 2f to "Thin", 4f to "Medium", 8f to "Heavy").forEach { (width, label) ->
        val on = clip.strokeWidth == width
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = if (on) Accent else ChipIdle,
          modifier = Modifier.weight(1f).height(30.dp).clickable { onUpdateClip(clip.copy(strokeWidth = width)) }
        ) {
          Box(contentAlignment = Alignment.Center) {
            Text(label, color = if (on) Color(0xFF06221D) else Color.White, fontSize = 11.sp)
          }
        }
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text("$colorTarget color", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("Text", "Stroke").forEach { target ->
          val on = colorTarget == target
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (on) Accent else ChipIdle,
            modifier = Modifier.clickable { colorTarget = target }
          ) {
            Text(
              target,
              color = if (on) Color(0xFF06221D) else Color.White,
              fontSize = 11.sp,
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
          }
        }
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      ColorSwatches.forEach { colorVal ->
        val isCurrent = if (colorTarget == "Text") clip.textColor == colorVal else clip.strokeColor == colorVal
        Box(
          modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(Color(colorVal))
            .border(if (isCurrent) 2.5.dp else 1.dp, if (isCurrent) Accent else Color(0xFF475569), CircleShape)
            .clickable {
              onUpdateClip(
                if (colorTarget == "Text") clip.copy(textColor = colorVal) else clip.copy(strokeColor = colorVal)
              )
            }
        )
      }
    }

    CompactSlider("Size", clip.fontSizeSp, 8f..96f, "sp", "text_size_slider") {
      onUpdateClip(clip.copy(fontSizeSp = it))
    }
    CompactSlider("Opacity", clip.opacity * 100f, 0f..100f, "%", "color_adjustment_slider") {
      onUpdateClip(clip.copy(opacity = it / 100f))
    }

    Text("Look", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    Row(
      modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      TextMotionCatalog.EFFECTS.forEach { effect ->
        val on = clip.effectStyle.equals(effect.key, true) ||
          (TextMotionCatalog.isNone(clip.effectStyle) && effect.key == "None")
        Chip(effect.label, on) { onUpdateClip(clip.copy(effectStyle = effect.key)) }
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Text("3D", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
      Switch(
        checked = clip.is3D,
        onCheckedChange = { on ->
          onUpdateClip(if (on) clip.copy(is3D = true, depth3D = clip.depth3D.takeIf { it > 0f } ?: 12f) else clip.copy(is3D = false))
        }
      )
    }
    if (clip.is3D) {
      CompactSlider("Depth", clip.depth3D, 0f..60f, "px") {
        onUpdateClip(clip.copy(depth3D = it))
      }
    }
  }
}

@Composable
private fun AnimateSubToolView(
  currentClip: TextClip?,
  onUpdateClip: (TextClip) -> Unit
) {
  val clip = currentClip ?: run {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text("Type text to animate", color = Color(0xFF94A3B8), fontSize = 12.sp)
    }
    return
  }

  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    Text("IN", color = AccentSoft, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    FlowChips(TextMotionCatalog.ENTRANCES, clip.animationType.ifBlank { clip.animationIn }) { key ->
      onUpdateClip(
        clip.copy(
          animationType = key,
          animationIn = key,
          keyframes = emptyList()
        )
      )
    }
    Text("OUT", color = AccentSoft, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    FlowChips(TextMotionCatalog.EXITS, clip.animationOut) { key ->
      onUpdateClip(clip.copy(animationOut = key, keyframes = emptyList()))
    }
    CompactSlider("Duration", clip.animDurationMs.toFloat(), 100f..2000f, " ms") {
      onUpdateClip(clip.copy(animDurationMs = it.toLong().coerceAtLeast(100L)))
    }
    TextAnimatorSubToolView(currentClip = clip, onUpdateClip = onUpdateClip)
  }
}

@Composable
private fun FlowChips(
  presets: List<TextMotionCatalog.Preset>,
  current: String,
  onPick: (String) -> Unit
) {
  Row(
    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    horizontalArrangement = Arrangement.spacedBy(6.dp)
  ) {
    presets.forEach { preset ->
      val on = current.equals(preset.key, true) ||
        (TextMotionCatalog.isNone(current) && preset.key == "None")
      Chip(preset.label, on) { onPick(preset.key) }
    }
  }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
  Surface(
    shape = RoundedCornerShape(16.dp),
    color = if (selected) Accent else ChipIdle,
    border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) AccentSoft else ChipIdleBorder),
    modifier = Modifier.clickable(onClick = onClick)
  ) {
    Text(
      label,
      color = if (selected) Color(0xFF06221D) else Color.White,
      fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
      fontSize = 12.sp,
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
    )
  }
}

@Composable
private fun CompactSlider(
  label: String,
  value: Float,
  range: ClosedFloatingPointRange<Float>,
  unit: String,
  tag: String? = null,
  onChange: (Float) -> Unit
) {
  Column {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(label, color = Color(0xFFCBD5E1), fontSize = 12.sp, modifier = Modifier.weight(1f))
      Text("${value.toInt()}$unit", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
    Slider(
      value = value.coerceIn(range.start, range.endInclusive),
      onValueChange = onChange,
      valueRange = range,
      colors = SliderDefaults.colors(
        thumbColor = Accent,
        activeTrackColor = Accent,
        inactiveTrackColor = Color(0xFF1E3A3A)
      ),
      modifier = Modifier.height(28.dp).then(if (tag != null) Modifier.testTag(tag) else Modifier)
    )
  }
}

@Composable
private fun AlignChip(icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
  Surface(
    shape = RoundedCornerShape(8.dp),
    color = if (selected) Accent else ChipIdle,
    modifier = Modifier.size(32.dp).clickable(onClick = onClick)
  ) {
    Box(contentAlignment = Alignment.Center) {
      Icon(icon, contentDescription = null, tint = if (selected) Color(0xFF06221D) else Color.White, modifier = Modifier.size(16.dp))
    }
  }
}

@Composable
private fun StyleToggle(icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
  Surface(
    shape = RoundedCornerShape(8.dp),
    color = if (selected) Accent else ChipIdle,
    modifier = Modifier.size(32.dp).clickable(onClick = onClick)
  ) {
    Box(contentAlignment = Alignment.Center) {
      Icon(icon, contentDescription = null, tint = if (selected) Color(0xFF06221D) else Color.White, modifier = Modifier.size(16.dp))
    }
  }
}
