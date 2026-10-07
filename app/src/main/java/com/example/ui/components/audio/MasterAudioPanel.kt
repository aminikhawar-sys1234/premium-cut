package com.example.ui.components.audio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ahstudio.audio.master.integration.AudioLevels
import com.ahstudio.audio.master.integration.MasterAudioRenderer
import com.example.ui.StudioViewModel
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.StudioDark
import kotlin.math.roundToInt

private enum class MasterTab(val label: String) { EQ("EQ"), DYNAMICS("Dynamics"), CLEAN("Clean"), TIME("Time") }

@Composable
fun MasterAudioPanel(
  viewModel: StudioViewModel,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val controller = viewModel.masterAudio
  val ui by controller.state.collectAsState()
  val s = ui.settings
  var tab by remember { mutableStateOf(MasterTab.EQ) }

  LaunchedEffect(Unit) { controller.refreshTarget() }

  Column(modifier = modifier.fillMaxSize().background(StudioDark)) {
    // Header
    Row(
      Modifier.fillMaxWidth().background(Color(0xFF0F1523)).padding(horizontal = 8.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(onClick = onBack, modifier = Modifier.size(32.dp).clip(CircleShape).background(Color(0xFF1E283E))) {
        Icon(Icons.Default.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(16.dp))
      }
      Spacer(Modifier.width(8.dp))
      Column(Modifier.weight(1f)) {
        Text("Master", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(
          ui.targetTitle?.let { "Clip: $it" } ?: "Select an audio clip on the timeline",
          color = if (ui.targetClipId != null) CyanAccent else Color(0xFFFFB74D), fontSize = 10.sp, maxLines = 1,
        )
      }
      TextButton(onClick = { controller.resetSettings() }, enabled = !ui.isWorking) { Text("Reset", fontSize = 12.sp) }
      TextButton(onClick = { controller.restoreOriginal() }, enabled = !ui.isWorking) { Text("Original", fontSize = 12.sp) }
    }

    // Tabs
    TabRow(
      selectedTabIndex = tab.ordinal, containerColor = Color(0xFF0F1523), contentColor = CyanAccent,
      modifier = Modifier.height(36.dp),
    ) {
      MasterTab.values().forEach { t ->
        Tab(selected = tab == t, onClick = { tab = t }, modifier = Modifier.height(36.dp)) {
          Text(t.label, fontSize = 12.sp, color = if (tab == t) CyanAccent else Color.Gray)
        }
      }
    }

    Column(
      Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      when (tab) {
        MasterTab.EQ -> {
          ToggleRow("Parametric EQ", s.eqEnabled) { v -> controller.update { it.copy(eqEnabled = v) } }
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("flat" to "Flat", "vocal" to "Vocal", "bass_boost" to "Bass+", "treble_boost" to "Treble+").forEach { (id, label) ->
              AssistChip(onClick = { controller.applyEqPreset(id) }, label = { Text(label, fontSize = 11.sp) })
            }
          }
          ToggleRow("High-pass 30 Hz", s.eqBands.firstOrNull()?.enabled ?: true) { v -> controller.setEqBandEnabled(0, v) }
          for (i in 1 until s.eqBands.size) {
            val b = s.eqBands[i]
            LabeledSlider(
              label = MasterAudioRenderer.EQ_BAND_LABELS.getOrElse(i) { "Band $i" },
              value = b.gainDb, range = -12f..12f, display = "%+.1f dB".format(b.gainDb),
            ) { controller.setEqBandGain(i, it) }
          }
        }

        MasterTab.DYNAMICS -> {
          ToggleRow("Compressor", s.compressorEnabled) { v -> controller.update { it.copy(compressorEnabled = v) } }
          LabeledSlider("Threshold", s.compThresholdDb, -60f..0f, "%.0f dB".format(s.compThresholdDb)) { v -> controller.update { it.copy(compThresholdDb = v, compressorEnabled = true) } }
          LabeledSlider("Ratio", s.compRatio, 1f..20f, "%.1f:1".format(s.compRatio)) { v -> controller.update { it.copy(compRatio = v, compressorEnabled = true) } }
          LabeledSlider("Attack", s.compAttackMs, 1f..100f, "%.0f ms".format(s.compAttackMs)) { v -> controller.update { it.copy(compAttackMs = v) } }
          LabeledSlider("Release", s.compReleaseMs, 20f..1000f, "%.0f ms".format(s.compReleaseMs)) { v -> controller.update { it.copy(compReleaseMs = v) } }
          LabeledSlider("Makeup", s.compMakeupDb, 0f..18f, "%.1f dB".format(s.compMakeupDb)) { v -> controller.update { it.copy(compMakeupDb = v) } }
          Divider(color = Color(0xFF1E283E), modifier = Modifier.padding(vertical = 4.dp))
          ToggleRow("De-Esser", s.deEsserEnabled) { v -> controller.update { it.copy(deEsserEnabled = v) } }
          LabeledSlider("Threshold", s.deEsserThresholdDb, -60f..-10f, "%.0f dB".format(s.deEsserThresholdDb)) { v -> controller.update { it.copy(deEsserThresholdDb = v, deEsserEnabled = true) } }
          LabeledSlider("Reduction", s.deEsserReductionDb, -24f..0f, "%.0f dB".format(s.deEsserReductionDb)) { v -> controller.update { it.copy(deEsserReductionDb = v, deEsserEnabled = true) } }
        }

        MasterTab.CLEAN -> {
          ToggleRow("Spectral Noise Reduction", s.noiseReductionEnabled) { v -> controller.update { it.copy(noiseReductionEnabled = v) } }
          Text("Noise profile is learned from the first 0.5 s of the clip — keep it free of speech.", color = Color.Gray, fontSize = 10.sp)
          LabeledSlider("Strength", s.noiseStrength, 0f..1f, "${(s.noiseStrength * 100).roundToInt()}%") { v -> controller.update { it.copy(noiseStrength = v, noiseReductionEnabled = true) } }
          LabeledSlider("Floor", s.noiseFloorDb, -80f..-20f, "%.0f dB".format(s.noiseFloorDb)) { v -> controller.update { it.copy(noiseFloorDb = v) } }
          ToggleRow("Preserve voice (<300 Hz)", s.voicePreservation) { v -> controller.update { it.copy(voicePreservation = v) } }
        }

        MasterTab.TIME -> {
          Text("Tempo & pitch are independent (WSOLA).", color = Color.Gray, fontSize = 10.sp)
          LabeledSlider("Tempo", s.tempo, 0.5f..2f, "%.2fx".format(s.tempo)) { v -> controller.update { it.copy(tempo = v) } }
          LabeledSlider("Pitch", s.pitchSemitones, -12f..12f, "%+.1f st".format(s.pitchSemitones)) { v -> controller.update { it.copy(pitchSemitones = v) } }
        }
      }
    }

    // Meters + actions
    Column(Modifier.background(Color(0xFF0F1523)).padding(horizontal = 12.dp, vertical = 6.dp)) {
      if (ui.before != null || ui.after != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          ui.before?.let { MeterCard("Before", it, Modifier.weight(1f)) }
          ui.after?.let { MeterCard("After", it, Modifier.weight(1f)) }
        }
        Spacer(Modifier.height(4.dp))
      }
      if (ui.isWorking) {
        LinearProgressIndicator(progress = ui.progress.coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth(), color = CyanAccent)
        Spacer(Modifier.height(4.dp))
      }
      ui.message?.let { Text(it, color = Color.LightGray, fontSize = 10.sp, maxLines = 2) }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { controller.analyze() }, enabled = !ui.isWorking && ui.targetClipId != null, modifier = Modifier.weight(1f)) {
          Text("Analyze", fontSize = 12.sp)
        }
        Button(
          onClick = { controller.apply() },
          enabled = !ui.isWorking && ui.targetClipId != null && s.hasAnyEffect,
          modifier = Modifier.weight(1f),
        ) { Text("Apply", fontSize = 12.sp) }
      }
    }
  }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
    Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.scale(0.8f))
  }
}

@Composable
private fun LabeledSlider(
  label: String, value: Float, range: ClosedFloatingPointRange<Float>, display: String,
  onChange: (Float) -> Unit,
) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(label, color = Color.LightGray, fontSize = 11.sp, modifier = Modifier.width(64.dp))
    Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f).height(28.dp))
    Text(display, color = CyanAccent, fontSize = 11.sp, modifier = Modifier.width(58.dp))
  }
}

@Composable
private fun MeterCard(title: String, l: AudioLevels, modifier: Modifier = Modifier) {
  Column(modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF151C2C)).padding(6.dp)) {
    Text(title, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    MeterBar("Peak", l.peakDb, if (l.clipped) Color(0xFFE53935) else CyanAccent)
    MeterBar("RMS", l.rmsDb, CyanAccent)
    Text("%.1f LUFS".format(l.lufs), color = Color.LightGray, fontSize = 10.sp)
  }
}

@Composable
private fun MeterBar(label: String, db: Float, color: Color) {
  val frac = ((db + 60f) / 60f).coerceIn(0f, 1f)
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(label, color = Color.Gray, fontSize = 9.sp, modifier = Modifier.width(26.dp))
    Box(Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF0B0F1A))) {
      Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(color))
    }
    Text("%.0f".format(db), color = Color.Gray, fontSize = 9.sp, modifier = Modifier.width(24.dp).padding(start = 4.dp))
  }
}
