package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.ai.cutout.BgRemoveCodec
import com.example.engine.ai.cutout.BgRemoveParams
import com.example.engine.ai.cutout.CutoutStatus
import com.example.engine.ai.cutout.SubjectCutoutRegistry
import com.example.ui.StudioViewModel
import com.example.ui.theme.*

/** One "what replaces the background" choice. [color] is only used for [BgRemoveParams.MODE_COLOR]. */
private data class BgChoice(val label: String, val mode: Int, val color: Int, val swatch: Color?)

private val BG_CHOICES = listOf(
  BgChoice("Transparent", BgRemoveParams.MODE_TRANSPARENT, 0xFF000000.toInt(), null),
  BgChoice("Blur", BgRemoveParams.MODE_BLUR, 0xFF000000.toInt(), null),
  BgChoice("Black", BgRemoveParams.MODE_COLOR, 0xFF000000.toInt(), Color.Black),
  BgChoice("White", BgRemoveParams.MODE_COLOR, 0xFFFFFFFF.toInt(), Color.White),
  BgChoice("Green", BgRemoveParams.MODE_COLOR, 0xFF00B140.toInt(), Color(0xFF00B140))
)

/**
 * Simple, lightweight Remove Background panel: one switch, two sliders, one row of background choices.
 * Everything is stored on the clip (so it saves / undoes / exports) and rendered by the GPU compositor.
 */
@Composable
fun AiMattingToolPanel(
  viewModel: StudioViewModel,
  modifier: Modifier = Modifier,
  onClose: () -> Unit = {}
) {
  val timeline by viewModel.timelineEngine.timeline.collectAsState()
  val selectedIds by viewModel.timelineEngine.selectedClipIds.collectAsState()
  val selectedElement by viewModel.timelineEngine.selectedElement.collectAsState()
  val status by SubjectCutoutRegistry.status.collectAsState()

  val cutClip = remember(timeline, selectedIds, selectedElement) { viewModel.getSelectedCutoutClip() }
  val isMainTrackClip = remember(timeline, cutClip?.id) { timeline.videoClips.any { it.id == cutClip?.id } }

  Column(
    modifier = modifier
      .fillMaxWidth()
      .background(StudioSurface)
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    // Header
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.PersonRemove, contentDescription = null, tint = StudioPrimary)
        Text(
          text = "Remove Background",
          style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = TextPrimary)
        )
      }
      IconButton(onClick = onClose) {
        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
      }
    }

    if (cutClip == null) {
      Text(
        "Select a video or photo clip on the timeline to remove its background.",
        fontSize = 12.sp,
        color = TextSecondary
      )
      return@Column
    }

    val enabled = cutClip.isBackgroundRemoved
    val params = remember(cutClip.bgRemove) { BgRemoveCodec.decode(cutClip.bgRemove) }
    val push: (Boolean, BgRemoveParams) -> Unit = { on, p -> viewModel.setBackgroundRemoval(cutClip.id, on, p) }

    Column(
      modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      // Main switch
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .background(StudioSurfaceVariant)
          .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("Remove background", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
          Text("On-device AI, no green screen needed", fontSize = 11.sp, color = TextSecondary)
        }
        Switch(
          checked = enabled,
          onCheckedChange = { on -> push(on, params) },
          colors = SwitchDefaults.colors(checkedThumbColor = StudioPrimary)
        )
      }

      if (enabled) {
        when (status) {
          CutoutStatus.WORKING -> LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            color = StudioPrimary
          )
          CutoutStatus.UNAVAILABLE -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              "The AI model isn't ready yet. It downloads once through Google Play services (needs internet).",
              fontSize = 11.sp,
              color = TextSecondary,
              modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { viewModel.retryBackgroundRemoval() }) { Text("Retry") }
          }
          else -> {}
        }

        var softness by remember(cutClip.id, params.softness) { mutableStateOf(params.softness) }
        CutoutSlider("Edge softness", softness) {
          softness = it
          push(true, params.copy(softness = it))
        }

        var strength by remember(cutClip.id, params.strength) { mutableStateOf(params.strength) }
        CutoutSlider("Cutout strength", strength) {
          strength = it
          push(true, params.copy(strength = it))
        }

        Text("Background", fontSize = 12.sp, color = TextSecondary)
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          BG_CHOICES.forEach { choice ->
            val selected = params.mode == choice.mode &&
              (choice.mode != BgRemoveParams.MODE_COLOR || params.bgColor == choice.color)
            BgChoiceChip(choice, selected) {
              push(true, params.copy(mode = choice.mode, bgColor = choice.color))
            }
          }
        }

        if (params.mode == BgRemoveParams.MODE_TRANSPARENT && isMainTrackClip) {
          Text(
            "Transparent shows black on the main track. Put the clip on an overlay to reveal the video below it.",
            fontSize = 11.sp,
            color = TextSecondary
          )
        }
      }
    }
  }
}

@Composable
private fun CutoutSlider(label: String, value: Float, onChange: (Float) -> Unit) {
  Column(modifier = Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Text(label, fontSize = 12.sp, color = TextSecondary)
      Text("${(value * 100).toInt()}%", fontSize = 12.sp, color = TextSecondary)
    }
    Slider(
      value = value,
      onValueChange = onChange,
      valueRange = 0f..1f,
      colors = SliderDefaults.colors(thumbColor = StudioPrimary, activeTrackColor = StudioPrimary)
    )
  }
}

@Composable
private fun BgChoiceChip(choice: BgChoice, selected: Boolean, onClick: () -> Unit) {
  val borderColor = if (selected) StudioPrimary else TextSecondary.copy(alpha = 0.35f)
  Row(
    modifier = Modifier
      .clip(RoundedCornerShape(50))
      .border(1.dp, borderColor, RoundedCornerShape(50))
      .background(if (selected) StudioPrimary.copy(alpha = 0.15f) else Color.Transparent)
      .clickable(onClick = onClick)
      .padding(horizontal = 12.dp, vertical = 7.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp)
  ) {
    if (choice.swatch != null) {
      Box(
        modifier = Modifier
          .size(14.dp)
          .clip(CircleShape)
          .background(choice.swatch)
          .border(1.dp, TextSecondary.copy(alpha = 0.5f), CircleShape)
      )
    }
    Text(
      choice.label,
      fontSize = 12.sp,
      color = if (selected) TextPrimary else TextSecondary,
      fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
    )
  }
}
