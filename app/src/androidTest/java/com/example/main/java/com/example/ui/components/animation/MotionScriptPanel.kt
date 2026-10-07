package com.example.ui.components.animation

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.ClipMotionScript
import com.example.domain.model.MotionChannel
import com.example.engine.motion.ClipMotionEngine
import com.example.engine.motion.ExpressionStatus
import com.example.ui.StudioViewModel
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.GreenAccent
import com.example.ui.theme.PurpleAccent
import com.example.ui.theme.RedAccent
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioDarkBg
import com.example.ui.theme.StudioSurfaceVariant
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary

/** One-tap starting points for the expression editor. `{id}` is replaced by the clip's own id. */
private data class ExpressionPreset(val label: String, val channel: MotionChannel, val code: String)

private val EXPRESSION_PRESETS = listOf(
  ExpressionPreset("Wiggle", MotionChannel.POSITION, "wiggle(2, 0.03)"),
  ExpressionPreset("Float", MotionChannel.POSITION, "value + [0, sin(time * 2) * 0.04]"),
  ExpressionPreset("Orbit", MotionChannel.POSITION, "[cos(time * 2) * 0.25, sin(time * 2) * 0.25]"),
  ExpressionPreset("Loop", MotionChannel.POSITION, "loopOut(\"pingpong\")"),
  ExpressionPreset("Pulse", MotionChannel.SCALE, "value * (1 + 0.08 * sin(time * 6))"),
  ExpressionPreset("Pop in", MotionChannel.SCALE, "ease(time, 0, 0.4, 0, value)"),
  ExpressionPreset("Spin", MotionChannel.ROTATION, "time * 90"),
  ExpressionPreset("Sway", MotionChannel.ROTATION, "value + sin(time * 3) * 8"),
  ExpressionPreset("Shake", MotionChannel.ROTATION, "wiggle(8, 4)"),
  ExpressionPreset("Fade in/out", MotionChannel.OPACITY, "min(linear(time, 0, 0.5, 0, 1), linear(time, duration - 0.5, duration, 1, 0))"),
  ExpressionPreset("Flicker", MotionChannel.OPACITY, "clamp(value * (0.8 + random() * 0.2), 0, 1)"),
  ExpressionPreset("Follow other", MotionChannel.POSITION, "prop(\"<clipId>\", \"position\") + [0.15, 0]")
)

private val FUNCTION_HELP = """
time, value, duration, fps · sin cos tan abs sqrt pow min max clamp lerp
linear/ease/easeIn/easeOut(t, t1, t2, v1, v2) · smoothstep · random · noise
wiggle(freq, amp) · loopOut("cycle"|"pingpong"|"offset"|"continue") · loopIn
valueAtTime(t) · velocityAtTime(t) · numKeys · keyTime(n) · keyValue(n) · markerTime("name")
prop("clipId", "position"|"scale"|"rotation"|"opacity") · thisProp("rotation")
Vectors: [x, y] · index with v[0] · ternary a ? b : c · Time is in seconds from clip start.
""".trimIndent()

/**
 * Expression text box + 2D bone parent/child binding for the selected clip.
 * Embedded in the Keyframe panel ("Expression & Rig" category) and in the Animations tool panel.
 */
@Composable
fun MotionScriptPanel(
  viewModel: StudioViewModel,
  clipId: String?,
  modifier: Modifier = Modifier,
  /** false when the caller already provides a vertical scroll container. */
  scrollable: Boolean = true
) {
  val engine = viewModel.timelineEngine
  val timeline by engine.timeline.collectAsState()
  val currentPosMs by engine.currentPositionMs.collectAsState()

  if (clipId == null) {
    Box(modifier = modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
      Text(
        "Select a video, overlay, text or sticker clip to add expressions or link it to a parent bone.",
        color = TextSecondary, fontSize = 12.sp
      )
    }
    return
  }
  val script: ClipMotionScript? = remember(timeline, clipId) { engine.getClipMotion(clipId) }
  if (script == null) {
    Box(modifier = modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
      Text("This clip type does not support expressions / rigging.", color = TextSecondary, fontSize = 12.sp)
    }
    return
  }
  val clipStartMs = remember(timeline, clipId) { ClipMotionEngine.findRef(timeline, clipId)?.startMs ?: 0L }

  Column(
    modifier = modifier
      .fillMaxWidth()
      .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
      .padding(horizontal = 10.dp, vertical = 6.dp)
      .testTag("motion_script_panel"),
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    ExpressionSection(
      clipId = clipId,
      script = script,
      relMs = currentPosMs - clipStartMs,
      onApply = { channel, src -> engine.setClipExpression(clipId, channel, src) }
    )
    RigSection(
      clipId = clipId,
      script = script,
      timeline = timeline,
      onLink = { parentId, keep -> engine.setClipRigParent(clipId, parentId, keepPose = keep) },
      onUnlink = { engine.setClipRigParent(clipId, null) },
      onInheritScale = { v -> engine.updateClipRigBinding(clipId) { it.copy(inheritScale = v) } },
      onInheritOpacity = { v -> engine.updateClipRigBinding(clipId) { it.copy(inheritOpacity = v) } },
      onBoneLength = { engine.setClipBoneLength(clipId, it) }
    )
  }
}

// ------------------------------------------------------------------------------------------------
// Expression editor
// ------------------------------------------------------------------------------------------------
@Composable
private fun ExpressionSection(
  clipId: String,
  script: ClipMotionScript,
  relMs: Long,
  onApply: (MotionChannel, String?) -> Unit
) {
  var channel by remember(clipId) { mutableStateOf(MotionChannel.POSITION) }
  val applied = script.expressionFor(channel) ?: ""
  var draft by remember(clipId, channel, applied) { mutableStateOf(applied) }
  var showHelp by remember { mutableStateOf(false) }

  val status = remember(draft, relMs / 33, clipId, channel) { ClipMotionEngine.check(clipId, channel, draft, relMs) }
  val syntaxOk = status !is ExpressionStatus.SyntaxError
  val dirty = draft.trim() != applied.trim()

  SectionCard(title = "Expression", icon = { Icon(Icons.Default.Code, null, tint = PurpleAccent, modifier = Modifier.size(15.dp)) }) {
    // channel selector
    Row(
      modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      MotionChannel.values().forEach { ch ->
        val active = script.expressionFor(ch) != null
        Chip(
          text = ch.label + if (active) " ●" else "",
          selected = ch == channel,
          accent = if (active) GreenAccent else CyanAccent,
          modifier = Modifier.testTag("expr_channel_${ch.key}")
        ) { channel = ch }
      }
    }
    Text(channel.hint, color = TextTertiary, fontSize = 10.sp)

    // code box
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 72.dp)
        .clip(RoundedCornerShape(8.dp))
        .background(StudioDarkBg)
        .border(
          1.dp,
          when {
            !syntaxOk -> RedAccent
            status is ExpressionStatus.RuntimeError -> AmberAccent
            draft.isNotBlank() -> GreenAccent.copy(alpha = 0.6f)
            else -> StudioBorder
          },
          RoundedCornerShape(8.dp)
        )
        .padding(8.dp)
    ) {
      BasicTextField(
        value = draft,
        onValueChange = { draft = it },
        textStyle = TextStyle(color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
        cursorBrush = SolidColor(CyanAccent),
        modifier = Modifier.fillMaxWidth().testTag("expression_text_box"),
        decorationBox = { inner ->
          if (draft.isEmpty()) {
            Text("e.g.  wiggle(2, 0.05)   or   value + [0, sin(time*3)*0.05]", color = TextTertiary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
          }
          inner()
        }
      )
    }

    // live status
    when (val st = status) {
      is ExpressionStatus.Ok -> Text("= ${st.preview}  (at playhead)", color = GreenAccent, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
      is ExpressionStatus.SyntaxError -> Text(
        "Syntax error" + (if (st.position >= 0) " @${st.position}" else "") + ": ${st.message}",
        color = RedAccent, fontSize = 11.sp
      )
      is ExpressionStatus.RuntimeError -> Text("Runtime error: ${st.message} — clip keeps its keyframed value", color = AmberAccent, fontSize = 11.sp)
      ExpressionStatus.Empty -> Text(
        if (applied.isNotBlank()) "Cleared — press Apply to remove the expression" else "No expression on this channel — keyframes drive it",
        color = TextTertiary, fontSize = 11.sp
      )
    }

    // action buttons
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      ActionButton(
        text = "Apply", icon = Icons.Default.Check, color = GreenAccent,
        enabled = syntaxOk && (dirty), tag = "expression_apply"
      ) { onApply(channel, draft.trim().ifEmpty { null }) }
      ActionButton(
        text = "Clear", icon = Icons.Default.Close, color = RedAccent,
        enabled = applied.isNotBlank() || draft.isNotBlank(), tag = "expression_clear"
      ) { draft = ""; onApply(channel, null) }
      Spacer(Modifier.weight(1f))
      Row(
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { showHelp = !showHelp }.padding(4.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text("Functions", color = TextSecondary, fontSize = 11.sp)
        Icon(if (showHelp) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = TextSecondary, modifier = Modifier.size(16.dp))
      }
    }
    if (showHelp) {
      Text(
        FUNCTION_HELP, color = TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(StudioDarkBg).padding(8.dp)
      )
    }

    // presets for the selected channel
    val presets = EXPRESSION_PRESETS.filter { it.channel == channel }
    if (presets.isNotEmpty()) {
      Text("Presets", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
      Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        presets.forEach { p ->
          Chip(text = p.label, selected = false, accent = PurpleAccent) {
            draft = p.code.replace("<clipId>", clipId)
          }
        }
      }
    }
  }
}

// ------------------------------------------------------------------------------------------------
// Rig / bone parent-child binding
// ------------------------------------------------------------------------------------------------
@Composable
private fun RigSection(
  clipId: String,
  script: ClipMotionScript,
  timeline: com.example.domain.model.Timeline,
  onLink: (parentId: String, keepPose: Boolean) -> Boolean,
  onUnlink: () -> Unit,
  onInheritScale: (Boolean) -> Unit,
  onInheritOpacity: (Boolean) -> Unit,
  onBoneLength: (Float) -> Unit
) {
  val targets = remember(timeline) { ClipMotionEngine.rigTargets(timeline).toMap() }
  val candidates = remember(timeline, clipId) {
    targets.filterKeys { it != clipId && !ClipMotionEngine.wouldCycle(timeline, clipId, it) }
  }
  val children = remember(timeline, clipId) { ClipMotionEngine.childrenOf(timeline, clipId) }
  val chain = remember(timeline, clipId, script.rig) { ClipMotionEngine.chainLabels(timeline, clipId) }
  val binding = script.rig
  var menuOpen by remember { mutableStateOf(false) }
  var keepPose by remember { mutableStateOf(true) }
  var error by remember { mutableStateOf<String?>(null) }

  SectionCard(title = "Rig · Bone parent", icon = { Icon(Icons.Default.AccountTree, null, tint = CyanAccent, modifier = Modifier.size(15.dp)) }) {
    Text(
      "Link this clip as a child bone: it follows its parent's position, rotation and scale (forward kinematics). " +
        "Its own keyframes/expressions then act as motion relative to the parent's joint.",
      color = TextTertiary, fontSize = 10.sp
    )

    // parent picker
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("Parent", color = TextSecondary, fontSize = 11.sp, modifier = Modifier.width(44.dp))
      Box {
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = StudioDarkBg,
          border = BorderStroke(1.dp, if (binding != null) CyanAccent else StudioBorder),
          modifier = Modifier.clickable { menuOpen = true }.testTag("rig_parent_picker")
        ) {
          Text(
            text = binding?.let { targets[it.parentClipId] ?: "Missing clip" } ?: "None (root bone) ▾",
            color = TextPrimary, fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
          )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
          if (candidates.isEmpty()) {
            DropdownMenuItem(text = { Text("No other clips available") }, onClick = { menuOpen = false })
          }
          candidates.forEach { (id, label) ->
            DropdownMenuItem(
              text = { Text(label, fontSize = 12.sp) },
              onClick = {
                menuOpen = false
                error = if (onLink(id, keepPose)) null else "That would create a loop"
              }
            )
          }
        }
      }
      if (binding != null) {
        ActionButton(text = "Unlink", icon = Icons.Default.LinkOff, color = RedAccent, enabled = true, tag = "rig_unlink") { onUnlink() }
      }
    }
    error?.let { Text(it, color = RedAccent, fontSize = 11.sp) }

    ToggleRow("Keep current position when linking", keepPose) { keepPose = it }
    if (binding != null) {
      ToggleRow("Inherit parent scale", binding.inheritScale, onInheritScale)
      ToggleRow("Inherit parent opacity", binding.inheritOpacity, onInheritOpacity)
    }

    // this clip's own bone length (joint where children attach)
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("Bone length", color = TextSecondary, fontSize = 11.sp, modifier = Modifier.width(80.dp))
      Slider(
        value = script.boneLength.coerceIn(0f, 1f),
        onValueChange = onBoneLength,
        valueRange = 0f..1f,
        colors = SliderDefaults.colors(thumbColor = CyanAccent, activeTrackColor = CyanAccent, inactiveTrackColor = StudioBorder),
        modifier = Modifier.weight(1f)
      )
      Text("%.2f".format(java.util.Locale.US, script.boneLength), color = TextPrimary, fontSize = 11.sp, modifier = Modifier.width(36.dp))
    }
    Text("Distance from this clip's pivot to the joint where its children attach (the bone points along +X, then rotates with the clip).", color = TextTertiary, fontSize = 10.sp)

    if (chain.size > 1) {
      Text("Chain: " + chain.joinToString("  ›  "), color = CyanAccent, fontSize = 11.sp)
    }
    if (children.isNotEmpty()) {
      Text("Children: " + children.joinToString { targets[it] ?: it.take(6) }, color = GreenAccent, fontSize = 11.sp)
    }
  }
}

// ------------------------------------------------------------------------------------------------
// small shared widgets
// ------------------------------------------------------------------------------------------------
@Composable
private fun SectionCard(title: String, icon: @Composable () -> Unit, content: @Composable () -> Unit) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(10.dp))
      .background(StudioSurfaceVariant)
      .padding(10.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      icon()
      Text(title, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
    content()
  }
}

@Composable
private fun Chip(text: String, selected: Boolean, accent: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
  Box(
    modifier = modifier
      .clip(RoundedCornerShape(14.dp))
      .background(if (selected) accent.copy(alpha = 0.22f) else StudioDarkBg)
      .border(1.dp, if (selected) accent else StudioBorder, RoundedCornerShape(14.dp))
      .clickable(onClick = onClick)
      .padding(horizontal = 10.dp, vertical = 5.dp)
  ) {
    Text(text, color = if (selected) accent else TextSecondary, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
  }
}

@Composable
private fun ActionButton(
  text: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  color: Color,
  enabled: Boolean,
  tag: String,
  onClick: () -> Unit
) {
  val c = if (enabled) color else TextTertiary
  Row(
    modifier = Modifier
      .clip(CircleShape)
      .background(c.copy(alpha = 0.15f))
      .border(1.dp, c.copy(alpha = 0.6f), CircleShape)
      .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
      .padding(horizontal = 12.dp, vertical = 6.dp)
      .testTag(tag),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp)
  ) {
    Icon(icon, null, tint = c, modifier = Modifier.size(13.dp))
    Text(text, color = c, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
  }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
    Text(label, color = TextSecondary, fontSize = 11.sp)
    Switch(
      checked = checked,
      onCheckedChange = onChange,
      colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = CyanAccent),
      modifier = Modifier.height(24.dp)
    )
  }
}
