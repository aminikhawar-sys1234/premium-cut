package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ahstudio.color.core.ColorState
import com.ahstudio.color.core.HslAdjust
import com.ahstudio.color.core.HslBands
import com.ahstudio.color.core.LutState
import com.ahstudio.color.core.WheelState
import com.ahstudio.color.core.WheelVec3
import com.ahstudio.color.preset.ColorProperties
import com.example.engine.color.ColorEngineHost
import com.example.ui.StudioViewModel
import com.example.ui.theme.SkyBlue
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceVariant
import kotlinx.coroutines.delay

private val LIGHT = listOf("exposure", "contrast", "highlights", "shadows", "whites", "blacks")
private val COLOR = listOf("temperature", "tint", "saturation", "vibrance")
private val SPLIT = listOf(
  "splitTone.shadowHue", "splitTone.shadowSat", "splitTone.highlightHue",
  "splitTone.highlightSat", "splitTone.balance", "splitTone.strength"
)
private val BAND_NAMES = listOf("Reds", "Oranges", "Yellows", "Greens", "Aquas", "Blues", "Purples", "Magentas")
private val WHEEL_NAMES = listOf("Lift", "Gamma", "Gain", "Offset")
private enum class GradeTab(val label: String) { BASIC("Basic"), WHEELS("Wheels"), HSL("HSL"), LUT("LUT") }

private fun labelFor(path: String): String = when (path) {
  "splitTone.shadowHue" -> "Shadow Hue"
  "splitTone.shadowSat" -> "Shadow Sat"
  "splitTone.highlightHue" -> "Highlight Hue"
  "splitTone.highlightSat" -> "Highlight Sat"
  "splitTone.balance" -> "Balance"
  "splitTone.strength" -> "Strength"
  else -> path.replaceFirstChar { it.uppercase() }
}

internal fun HslBands.band(i: Int): HslAdjust = toArray()[i]

internal fun HslBands.withBand(i: Int, a: HslAdjust): HslBands = when (i) {
  0 -> copy(reds = a); 1 -> copy(oranges = a); 2 -> copy(yellows = a); 3 -> copy(greens = a)
  4 -> copy(aquas = a); 5 -> copy(blues = a); 6 -> copy(purples = a); else -> copy(magentas = a)
}

internal fun WheelState.wheel(i: Int): WheelVec3 = when (i) { 0 -> lift; 1 -> gamma; 2 -> gain; else -> offset }

internal fun WheelState.withWheel(i: Int, v: WheelVec3): WheelState = when (i) {
  0 -> copy(lift = v); 1 -> copy(gamma = v); 2 -> copy(gain = v); else -> copy(offset = v)
}

/**
 * Per-clip colour grade panel: primaries, split toning, lift/gamma/gain/offset, 8-band HSL, and LUTs.
 * The grade is stored on the clip (project), so it saves, undoes and exports with the timeline.
 */
@Composable
fun ColorGradePanel(
  viewModel: StudioViewModel,
  modifier: Modifier = Modifier,
  onClose: () -> Unit = {}
) {
  val timeline by viewModel.timelineEngine.timeline.collectAsState()
  val clip = remember(timeline) { viewModel.getSelectedVideoClip() }
  val clipId = clip?.id
  val grade = remember(clip?.colorGradeJson) { ColorEngineHost.decodeOrDefault(clip?.colorGradeJson) }

  var tab by remember { mutableStateOf(GradeTab.BASIC) }
  var bandIndex by remember { mutableIntStateOf(0) }
  var bypass by remember(clipId) { mutableStateOf(clipId?.let { ColorEngineHost.isBypassed(it) } ?: false) }

  // Bypass is render-only; never leave it on once the panel is gone or the grade would vanish from export.
  DisposableEffect(clipId) {
    onDispose { ColorEngineHost.clearAllBypass() }
  }

  fun commit(next: ColorState) {
    val id = clipId ?: return
    ColorEngineHost.applyToEngine(id, next)
    viewModel.timelineEngine.setClipColorGrade(id, if (next.isIdentity()) null else ColorEngineHost.encode(next))
    viewModel.refreshCurrentFrame()
  }

  // LUT files load asynchronously; re-render once the LUT is actually available.
  val lutId = grade.lut?.lutId
  LaunchedEffect(lutId) {
    if (lutId.isNullOrBlank()) return@LaunchedEffect
    var tries = 0
    while (!ColorEngineHost.isLutReady(lutId) && tries < 30) { delay(100); tries++ }
    viewModel.refreshCurrentFrame()
  }

  Column(
    modifier = modifier
      .fillMaxWidth()
      .heightIn(max = 440.dp)
      .background(StudioSurface)
      .testTag("color_grade_panel")
  ) {
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      IconButton(onClick = {
        ColorEngineHost.clearAllBypass(); bypass = false
        viewModel.refreshCurrentFrame()
        onClose()
      }) { Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White) }
      Text("Color", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
      Text("Before/After", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
      Switch(
        checked = bypass,
        enabled = clipId != null,
        onCheckedChange = { on ->
          val id = clipId ?: return@Switch
          ColorEngineHost.setBypass(id, on); bypass = on
          viewModel.refreshCurrentFrame()
        },
        modifier = Modifier.padding(horizontal = 6.dp)
      )
      IconButton(enabled = clipId != null, onClick = { commit(ColorState()) }) {
        Icon(Icons.Default.Restore, contentDescription = "Reset", tint = SkyBlue)
      }
    }

    if (clipId == null) {
      Text("Select a video clip to grade.", color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(16.dp))
      return@Column
    }

    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      GradeTab.values().forEach { t ->
        FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t.label, fontSize = 12.sp) }, modifier = Modifier.testTag("color_tab_${t.name}"))
      }
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp)) {
      val enabled = !bypass
      when (tab) {
        GradeTab.BASIC -> {
          PropSection("Light", LIGHT, grade, enabled, ::commit)
          PropSection("Color", COLOR, grade, enabled, ::commit)
          PropSection("Split Toning", SPLIT, grade, enabled, ::commit)
        }
        GradeTab.WHEELS -> WHEEL_NAMES.forEachIndexed { i, name ->
          val w = grade.wheels.wheel(i)
          SectionTitle(name)
          GradeSlider("Red", w.x, -1f, 1f, 0f, enabled, "wheel_${i}_r") { commit(grade.copy(wheels = grade.wheels.withWheel(i, w.copy(x = it)))) }
          GradeSlider("Green", w.y, -1f, 1f, 0f, enabled, "wheel_${i}_g") { commit(grade.copy(wheels = grade.wheels.withWheel(i, w.copy(y = it)))) }
          GradeSlider("Blue", w.z, -1f, 1f, 0f, enabled, "wheel_${i}_b") { commit(grade.copy(wheels = grade.wheels.withWheel(i, w.copy(z = it)))) }
          GradeSlider("Master", w.master, -1f, 1f, 0f, enabled, "wheel_${i}_m") { commit(grade.copy(wheels = grade.wheels.withWheel(i, w.copy(master = it)))) }
        }
        GradeTab.HSL -> {
          Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BAND_NAMES.forEachIndexed { i, n ->
              FilterChip(selected = bandIndex == i, onClick = { bandIndex = i }, label = { Text(n, fontSize = 11.sp) })
            }
          }
          val b = grade.hsl.band(bandIndex)
          GradeSlider("Hue", b.hueShift, -0.5f, 0.5f, 0f, enabled, "hsl_hue") { commit(grade.copy(hsl = grade.hsl.withBand(bandIndex, b.copy(hueShift = it)))) }
          GradeSlider("Sat", b.sat, -1f, 1f, 0f, enabled, "hsl_sat") { commit(grade.copy(hsl = grade.hsl.withBand(bandIndex, b.copy(sat = it)))) }
          GradeSlider("Lum", b.lum, -1f, 1f, 0f, enabled, "hsl_lum") { commit(grade.copy(hsl = grade.hsl.withBand(bandIndex, b.copy(lum = it)))) }
        }
        GradeTab.LUT -> {
          SectionTitle("Look")
          Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = lutId.isNullOrBlank(), enabled = enabled, onClick = { commit(grade.copy(lut = null)) }, label = { Text("None", fontSize = 11.sp) })
            ColorEngineHost.BUNDLED_LUTS.forEach { (id, name) ->
              FilterChip(
                selected = lutId == id, enabled = enabled,
                onClick = { commit(grade.copy(lut = LutState(lutId = id, intensity = grade.lut?.intensity ?: 1f))) },
                label = { Text(name, fontSize = 11.sp) }
              )
            }
          }
          grade.lut?.let { l ->
            GradeSlider("Intensity", l.intensity, 0f, 1f, 1f, enabled, "lut_intensity") { commit(grade.copy(lut = l.copy(intensity = it))) }
          }
        }
      }
      Spacer(Modifier.height(12.dp))
    }
  }
}

@Composable
private fun SectionTitle(title: String) {
  Text(title, color = SkyBlue, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
}

@Composable
private fun PropSection(title: String, paths: List<String>, grade: ColorState, enabled: Boolean, onChange: (ColorState) -> Unit) {
  SectionTitle(title)
  paths.forEach { path ->
    val prop = ColorProperties.ALL[path] ?: return@forEach
    GradeSlider(labelFor(path), prop.get(grade), prop.min, prop.max, prop.get(ColorState()), enabled, path) { onChange(prop.set(grade, it)) }
  }
}

@Composable
private fun GradeSlider(
  label: String, value: Float, min: Float, max: Float, neutral: Float,
  enabled: Boolean, tag: String, onChange: (Float) -> Unit
) {
  Row(
    Modifier
      .fillMaxWidth()
      .padding(bottom = 3.dp)
      .clip(RoundedCornerShape(8.dp))
      .background(StudioSurfaceVariant.copy(alpha = 0.35f))
      .padding(horizontal = 10.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(label, color = Color.White, fontSize = 12.sp, modifier = Modifier.width(96.dp))
    Slider(
      value = value.coerceIn(min, max),
      onValueChange = onChange,
      valueRange = min..max,
      enabled = enabled,
      modifier = Modifier.weight(1f).testTag("color_slider_$tag")
    )
    TextButton(
      onClick = { onChange(neutral) },
      enabled = enabled && value != neutral,
      contentPadding = PaddingValues(horizontal = 6.dp)
    ) { Text("%.2f".format(value), fontSize = 11.sp, color = if (value != neutral) SkyBlue else Color.White.copy(alpha = 0.6f)) }
  }
}
