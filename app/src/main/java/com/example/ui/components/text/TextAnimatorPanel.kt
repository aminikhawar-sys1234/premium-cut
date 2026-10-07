package com.example.ui.components.text

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.TextAnimatorSpec
import com.example.domain.model.TextClip
import com.example.engine.text.animator.TextAnimatorEngine
import com.example.engine.text.animator.TextAnimatorRecipes

private val Accent = Color(0xFF10B981)
private val AccentSoft = Color(0xFF34D399)
private val ChipIdle = Color(0xFF0F2C2C)
private val ChipIdleBorder = Color(0xFF194444)
private val FillPalette = listOf(
  0xFFFFEB3B, 0xFFFF5252, 0xFFFF9800, 0xFF00E5FF, 0xFF69F0AE, 0xFFE040FB, 0xFFFFFFFF
)

/**
 * Text Animator: per-character / per-word / per-line animation with range selectors, shapes,
 * random order, easing, looping and stackable animators. Every control edits
 * [TextClip.textAnimators] directly; the preview and export render the result through
 * TextLayerRenderer. Scrub or play the timeline to see the animation.
 */
@Composable
internal fun TextAnimatorSubToolView(
  currentClip: TextClip?,
  onUpdateClip: (TextClip) -> Unit
) {
  if (currentClip == null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text("Select or add a text clip first", color = Color(0xFF94A3B8), fontSize = 12.sp)
    }
    return
  }

  val animators = currentClip.textAnimators
  var selectedId by remember { mutableStateOf<String?>(null) }
  val selected = animators.firstOrNull { it.id == selectedId } ?: animators.firstOrNull()

  fun commit(list: List<TextAnimatorSpec>) = onUpdateClip(currentClip.copy(textAnimators = list))
  fun edit(transform: (TextAnimatorSpec) -> TextAnimatorSpec) {
    val target = selected ?: return
    commit(animators.map { if (it.id == target.id) transform(it) else it })
  }
  fun add(spec: TextAnimatorSpec) {
    commit(animators + spec)
    selectedId = spec.id
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .testTag("text_animator_panel"),
    verticalArrangement = Arrangement.spacedBy(6.dp)
  ) {
    // --- Start from a setup ---
    SectionLabel("Add animator")
    Row(
      Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      TextAnimatorRecipes.all.forEach { recipe ->
        Chip(recipe.label, selected = false, tag = "animator_recipe_${recipe.label}") {
          add(recipe.build().copy(name = "${recipe.label} ${animators.size + 1}"))
        }
      }
      Chip("+ Blank", selected = false, tag = "animator_add_blank") {
        add(
          TextAnimatorSpec(
            name = "Animator ${animators.size + 1}",
            softness = 25f,
            opacityPct = 0f
          )
        )
      }
    }

    // --- Animator stack ---
    if (animators.isNotEmpty()) {
      SectionLabel("Stack (${animators.size})")
      Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        animators.forEach { a ->
          Chip(
            label = a.name + if (a.enabled) "" else " (off)",
            selected = a.id == selected?.id,
            tag = "animator_chip_${a.id}"
          ) { selectedId = a.id }
        }
      }
    } else {
      Text(
        "No animators yet. Pick a setup above, then edit the range, shape, timing and properties.",
        color = Color(0xFF94A3B8),
        fontSize = 11.sp,
        modifier = Modifier.padding(vertical = 4.dp)
      )
    }

    if (selected != null) {
      // --- Enable / delete ---
      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
      ) {
        IconButton(
          onClick = { edit { it.copy(enabled = !it.enabled) } },
          modifier = Modifier.size(32.dp).testTag("animator_toggle_enabled")
        ) {
          Icon(
            if (selected.enabled) Icons.Default.Visibility else Icons.Default.VisibilityOff,
            contentDescription = "Enable or disable animator",
            tint = Color.White,
            modifier = Modifier.size(18.dp)
          )
        }
        IconButton(
          onClick = {
            commit(animators.filterNot { it.id == selected.id })
            selectedId = null
          },
          modifier = Modifier.size(32.dp).testTag("animator_delete")
        ) {
          Icon(
            Icons.Default.Delete,
            contentDescription = "Delete animator",
            tint = Color(0xFFFF6B6B),
            modifier = Modifier.size(18.dp)
          )
        }
      }

      // --- Selector ---
      SectionLabel("Selector")
      ChipRow("Animate by", TextAnimatorEngine.BASES, selected.basis) { v -> edit { it.copy(basis = v) } }
      ChipRow("Shape", TextAnimatorEngine.SHAPES, selected.shape) { v -> edit { it.copy(shape = v) } }
      ParamSlider("Start from", selected.startFrom, -100f..200f, "%") { v -> edit { it.copy(startFrom = v) } }
      ParamSlider("Start to", selected.startTo, -100f..200f, "%") { v -> edit { it.copy(startTo = v) } }
      ParamSlider("End from", selected.endFrom, -100f..200f, "%") { v -> edit { it.copy(endFrom = v) } }
      ParamSlider("End to", selected.endTo, -100f..200f, "%") { v -> edit { it.copy(endTo = v) } }
      ParamSlider("Offset", selected.offset, -100f..100f, "%") { v -> edit { it.copy(offset = v) } }
      if (selected.shape == "Square") {
        ParamSlider("Softness", selected.softness, 0f..60f, "%") { v -> edit { it.copy(softness = v) } }
      }
      ParamSlider("Ease high", selected.easeHigh, -100f..100f, "%") { v -> edit { it.copy(easeHigh = v) } }
      ParamSlider("Ease low", selected.easeLow, -100f..100f, "%") { v -> edit { it.copy(easeLow = v) } }
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("Random order", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
        if (selected.randomize) {
          Chip("Reshuffle", selected = false, tag = "animator_reshuffle") {
            edit { it.copy(randomSeed = it.randomSeed + 1) }
          }
          Spacer(Modifier.size(8.dp))
        }
        Switch(
          checked = selected.randomize,
          onCheckedChange = { v -> edit { it.copy(randomize = v) } },
          colors = SwitchDefaults.colors(checkedTrackColor = Accent)
        )
      }

      // --- Timing ---
      SectionLabel("Timing")
      ParamSlider("Delay", selected.delayMs.toFloat(), 0f..3000f, " ms") { v ->
        edit { it.copy(delayMs = v.toLong()) }
      }
      ParamSlider("Duration", selected.durationMs.toFloat(), 100f..5000f, " ms") { v ->
        edit { it.copy(durationMs = v.toLong().coerceAtLeast(100L)) }
      }
      ChipRow("Easing", TextAnimatorEngine.TIME_EASINGS, selected.timeEasing) { v -> edit { it.copy(timeEasing = v) } }
      ChipRow("Repeat", TextAnimatorEngine.LOOP_MODES, selected.loopMode) { v -> edit { it.copy(loopMode = v) } }

      // --- Properties ---
      SectionLabel("Properties at full selection")
      ParamSlider("Position X", selected.posX, -2f..2f, " em", decimals = 2) { v -> edit { it.copy(posX = v) } }
      ParamSlider("Position Y", selected.posY, -2f..2f, " em", decimals = 2) { v -> edit { it.copy(posY = v) } }
      ParamSlider("Scale", selected.scalePct, 0f..300f, "%") { v -> edit { it.copy(scalePct = v) } }
      ParamSlider("Rotation", selected.rotationDeg, -360f..360f, "°") { v -> edit { it.copy(rotationDeg = v) } }
      ParamSlider("Skew", selected.skewDeg, -45f..45f, "°") { v -> edit { it.copy(skewDeg = v) } }
      ParamSlider("Opacity", selected.opacityPct, 0f..100f, "%") { v -> edit { it.copy(opacityPct = v) } }
      ParamSlider("Tracking", selected.trackingEm, -0.5f..1.5f, " em", decimals = 2) { v -> edit { it.copy(trackingEm = v) } }
      SectionLabel("3D properties")
      ParamSlider("Extra depth", selected.depthPx, -30f..60f, " px", tag = "animator_depth") { v -> edit { it.copy(depthPx = v) } }
      ParamSlider("Tilt X", selected.tiltXDeg, -90f..90f, "°", tag = "animator_tilt_x") { v -> edit { it.copy(tiltXDeg = v) } }
      ParamSlider("Tilt Y (flip)", selected.tiltYDeg, -90f..90f, "°", tag = "animator_tilt_y") { v -> edit { it.copy(tiltYDeg = v) } }
      ParamSlider("Light shift", selected.lightShiftDeg, -180f..180f, "°", tag = "animator_light_shift") { v -> edit { it.copy(lightShiftDeg = v) } }
      if (selected.depthPx > 0f || selected.lightShiftDeg != 0f) {
        Text(
          if (currentClip.is3D) "Depth and light follow the 3D Text tab (material, bevel, side colour)."
          else "3D switch is off: only the selected letters get depth. Turn on 3D Text for the full extrusion.",
          color = Color(0xFF94A3B8), fontSize = 10.sp
        )
      }
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("Fill colour", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Switch(
          checked = selected.useFill,
          onCheckedChange = { v -> edit { it.copy(useFill = v) } },
          colors = SwitchDefaults.colors(checkedTrackColor = Accent)
        )
      }
      if (selected.useFill) {
        Row(
          Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          FillPalette.forEach { argb ->
            val isOn = selected.fillColor == argb
            Box(
              Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(Color(argb.toInt()))
                .border(if (isOn) 2.dp else 1.dp, if (isOn) Color.White else ChipIdleBorder, CircleShape)
                .clickable { edit { it.copy(fillColor = argb) } }
            )
          }
        }
      }
      Spacer(Modifier.height(12.dp))
    }
  }
}

@Composable
private fun SectionLabel(text: String) {
  Text(
    text = text.uppercase(),
    color = AccentSoft,
    fontSize = 10.sp,
    fontWeight = FontWeight.Bold,
    modifier = Modifier.padding(top = 6.dp)
  )
}

@Composable
private fun Chip(label: String, selected: Boolean, tag: String? = null, onClick: () -> Unit) {
  Surface(
    shape = RoundedCornerShape(16.dp),
    color = if (selected) Accent else ChipIdle,
    border = BorderStroke(1.dp, if (selected) AccentSoft else ChipIdleBorder),
    modifier = Modifier
      .clickable(onClick = onClick)
      .then(if (tag != null) Modifier.testTag(tag) else Modifier)
  ) {
    Text(
      text = label,
      color = if (selected) Color(0xFF06221D) else Color.White,
      fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
      fontSize = 12.sp,
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
    )
  }
}

@Composable
private fun ChipRow(title: String, options: List<String>, current: String, onPick: (String) -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
    Text(title, color = Color(0xFFCBD5E1), fontSize = 11.sp)
    Row(
      Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      options.forEach { opt -> Chip(opt, selected = opt == current) { onPick(opt) } }
    }
  }
}

@Composable
private fun ParamSlider(
  label: String,
  value: Float,
  range: ClosedFloatingPointRange<Float>,
  unit: String,
  decimals: Int = 0,
  tag: String? = null,
  onChange: (Float) -> Unit
) {
  Column {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(label, color = Color(0xFFCBD5E1), fontSize = 11.sp, modifier = Modifier.weight(1f))
      Text(
        text = String.format("%.${decimals}f", value) + unit,
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold
      )
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
