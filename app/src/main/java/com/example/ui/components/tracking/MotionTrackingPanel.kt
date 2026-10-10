package com.example.ui.components.tracking

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.EditLocation
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.EffectType
import com.example.domain.model.VideoClip
import com.example.engine.ai.AttachmentTarget
import com.example.engine.ai.BodyTrackingFeature
import com.example.engine.ai.MotionFeatureType
import com.example.engine.ai.MotionTrackingUiState
import com.example.engine.ai.NormalizedRect
import com.example.engine.ai.TrackRange
import com.example.engine.ai.TrackingCategory
import com.example.ui.StudioViewModel
import kotlin.math.roundToInt

private val PanelBg = Color(0xFF0B1A20)
private val HeaderBg = Color(0xFF071920)
private val Border = Color(0xFF133B44)
private val CardBg = Color(0xFF0D252E)
private val Emerald = Color(0xFF00D1B2)
private val Cyan = Color(0xFF00B4D8)
private val TextPrimary = Color(0xFFF1F5F9)
private val TextSecondary = Color(0xFF94A3B8)

@Composable
fun MotionTrackingPanel(
    viewModel: StudioViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.motionTrackingState.collectAsState()
    val activeClip = viewModel.getSelectedVideoClip()
    val currentPosMs by viewModel.timelineEngine.currentPositionMs.collectAsState()

    Surface(
        color = PanelBg,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        border = BorderStroke(1.dp, Border),
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .heightIn(max = 280.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { viewModel.resetMotionTracking() },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier
                        .height(26.dp)
                        .testTag("motion_tracking_reset_button")
                ) {
                    Text("Reset", color = Color(0xFFFF5252), fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(Icons.Default.TrackChanges, null, tint = Emerald, modifier = Modifier.size(14.dp))
                    Text("Motion Tracking", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(26.dp).testTag("motion_tracking_close_button")
                ) {
                    Icon(Icons.Default.Close, "Close", tint = TextSecondary, modifier = Modifier.size(16.dp))
                }
            }

            Divider(color = Border, thickness = 1.dp)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TrackingCategory.values().forEach { cat ->
                    CategoryPill(cat, uiState.activeCategory == cat) { viewModel.setTrackingCategory(cat) }
                }
            }

            if (uiState.isTracking) {
                TrackingProgressBar(uiState.progress, uiState.statusMessage) { viewModel.cancelMotionTracking() }
            } else if (uiState.statusMessage.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        uiState.statusMessage,
                        color = if (uiState.activeResult != null) Emerald else Cyan,
                        fontSize = 10.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    val keys = uiState.activeResult?.keyframes?.size ?: 0
                    if (keys > 0) Text("$keys keys", color = TextSecondary, fontSize = 10.sp)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 196.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                when (uiState.activeCategory) {
                    TrackingCategory.OBJECT -> ObjectCategoryContent(viewModel, uiState, activeClip)
                    TrackingCategory.FACE -> FaceCategoryContent(viewModel, uiState, activeClip)
                    TrackingCategory.BODY -> BodyCategoryContent(viewModel, uiState, activeClip)
                    TrackingCategory.MOTION -> MotionCategoryContent(viewModel, uiState, activeClip)
                    TrackingCategory.ATTACH -> AttachCategoryContent(viewModel, uiState)
                    TrackingCategory.CONTROLS -> ControlsCategoryContent(viewModel, uiState, currentPosMs)
                }
            }
        }
    }
}

@Composable
private fun CategoryPill(category: TrackingCategory, isSelected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) Emerald else CardBg,
        border = BorderStroke(1.dp, if (isSelected) Emerald else Border),
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag("motion_category_${category.name.lowercase()}")
    ) {
        Text(
            text = category.title,
            color = if (isSelected) Color.Black else TextPrimary,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 11.5.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun TrackingProgressBar(progress: Float, statusMessage: String, onCancel: () -> Unit) {
    val percent = (progress.coerceIn(0f, 1f) * 100).roundToInt()
    val display = if (statusMessage.isNotBlank()) {
        if (statusMessage.contains("%")) statusMessage else "$statusMessage $percent%"
    } else "Tracking... $percent%"
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                CircularProgressIndicator(
                    progress = progress.coerceIn(0f, 1f),
                    modifier = Modifier.size(12.dp),
                    color = Emerald,
                    strokeWidth = 2.dp
                )
                Text(display, color = TextPrimary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = onCancel, contentPadding = PaddingValues(horizontal = 6.dp), modifier = Modifier.height(22.dp)) {
                Text("Cancel", color = Color(0xFFFF5252), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        LinearProgressIndicator(
            progress = progress.coerceIn(0f, 1f),
            modifier = Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(2.dp)),
            color = Emerald,
            trackColor = HeaderBg
        )
    }
}

@Composable
private fun RangeRow(
    viewModel: StudioViewModel,
    category: TrackingCategory,
    enabled: Boolean,
    startTag: String
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TrackAction(
            icon = Icons.Default.PlayArrow,
            label = "Forward",
            modifier = Modifier.weight(1f).testTag(startTag),
            enabled = enabled,
            primary = true
        ) { viewModel.startMotionTracking(category, range = TrackRange.FORWARD) }
        TrackAction(
            icon = Icons.Default.FastRewind,
            label = "Back",
            modifier = Modifier.weight(1f),
            enabled = enabled
        ) { viewModel.startMotionTracking(category, range = TrackRange.BACKWARD) }
        TrackAction(
            icon = Icons.Default.TrackChanges,
            label = "Full",
            modifier = Modifier.weight(1f),
            enabled = enabled
        ) { viewModel.startMotionTracking(category, range = TrackRange.FULL) }
        TrackAction(
            icon = Icons.Default.Refresh,
            label = "Re-track",
            modifier = Modifier.weight(1f).testTag("retrack_object_button"),
            enabled = enabled
        ) { viewModel.retrackCurrentTarget() }
    }
}

@Composable
private fun TrackAction(
    icon: ImageVector,
    label: String,
    modifier: Modifier,
    enabled: Boolean,
    primary: Boolean = false,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) Emerald else CardBg,
            contentColor = if (primary) Color.Black else TextPrimary,
            disabledContainerColor = CardBg.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
        modifier = modifier.height(34.dp)
    ) {
        Icon(icon, null, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(3.dp))
        Text(label, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (selected) Emerald else CardBg,
        border = BorderStroke(1.dp, if (selected) Emerald else Border),
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick)
    ) {
        Text(
            label,
            color = if (selected) Color.Black else TextSecondary,
            fontSize = 10.5.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun LiveHint(uiState: MotionTrackingUiState, empty: String) {
    val n = uiState.liveDetections.size
    Text(
        text = if (n > 0) {
            "Live ${uiState.liveDetections.joinToString(" · ") { it.label }} — tap a box to lock"
        } else empty,
        color = if (n > 0) Emerald else TextSecondary,
        fontSize = 10.5.sp,
        lineHeight = 14.sp
    )
}

@Composable
private fun ObjectCategoryContent(viewModel: StudioViewModel, uiState: MotionTrackingUiState, activeClip: VideoClip?) {
    val enabled = !uiState.isTracking && activeClip != null
    LiveHint(uiState, "ML object lock + LK planar track. Tap a detection or drag the box.")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip("Small", uiState.targetRegion == NormalizedRect.SMALL_CENTER) {
            viewModel.updateTrackingRegion(NormalizedRect.SMALL_CENTER)
        }
        Chip("Medium", uiState.targetRegion == NormalizedRect.DEFAULT_CENTER) {
            viewModel.updateTrackingRegion(NormalizedRect.DEFAULT_CENTER)
        }
        Chip("Large", uiState.targetRegion == NormalizedRect.LARGE_CENTER) {
            viewModel.updateTrackingRegion(NormalizedRect.LARGE_CENTER)
        }
        Chip("Planar", uiState.settings.featureMode == MotionFeatureType.PLANAR) {
            viewModel.updateFeatureMode(MotionFeatureType.PLANAR)
        }
        Chip("Similarity", uiState.settings.featureMode != MotionFeatureType.PLANAR) {
            viewModel.updateFeatureMode(MotionFeatureType.ROTATION_SCALE)
        }
    }
    RangeRow(viewModel, TrackingCategory.OBJECT, enabled, "start_object_tracking_button")
    if (activeClip == null) Text("Select a video clip first.", color = Color(0xFFFFB74D), fontSize = 11.sp)
}

@Composable
private fun FaceCategoryContent(viewModel: StudioViewModel, uiState: MotionTrackingUiState, activeClip: VideoClip?) {
    val enabled = !uiState.isTracking && activeClip != null
    LiveHint(uiState, "ML Kit face detector — playhead scan, then LK between re-detects.")
    RangeRow(viewModel, TrackingCategory.FACE, enabled, "start_face_tracking_button")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        QuickTool(Icons.Default.Face, "AR") { viewModel.setActiveToolbarTab(com.example.ui.EditorToolbarTab.AR_EFFECTS) }
        QuickTool(Icons.Default.BlurOn, "Blur") { viewModel.attachFaceEffect(EffectType.BLUR) }
        QuickTool(Icons.Default.GridOn, "Mosaic") { viewModel.attachFaceEffect(EffectType.MOSAIC) }
        QuickTool(Icons.Default.EmojiEmotions, "Sticker") { viewModel.attachTrackingToLayer(AttachmentTarget.STICKER) }
    }
}

@Composable
private fun BodyCategoryContent(viewModel: StudioViewModel, uiState: MotionTrackingUiState, activeClip: VideoClip?) {
    val enabled = !uiState.isTracking && activeClip != null
    val region = uiState.settings.bodyRegion
    LiveHint(uiState, "33-point BlazePose. Choose a limb set, then analyze.")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            BodyTrackingFeature.FULL_BODY,
            BodyTrackingFeature.UPPER_BODY,
            BodyTrackingFeature.ARMS,
            BodyTrackingFeature.LEGS
        ).forEach { feat ->
            Chip(feat.title, region == feat) { viewModel.updateBodyRegion(feat) }
        }
    }
    RangeRow(viewModel, TrackingCategory.BODY, enabled, "start_body_tracking_button")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        QuickTool(Icons.Default.BlurOn, "Body Blur") { viewModel.attachFaceEffect(EffectType.BLUR) }
        QuickTool(Icons.Default.AccessibilityNew, "Pose track") {
            viewModel.updateBodyRegion(BodyTrackingFeature.POSE_TRACKING)
            viewModel.startMotionTracking(TrackingCategory.BODY, range = TrackRange.FULL)
        }
    }
}

@Composable
private fun MotionCategoryContent(viewModel: StudioViewModel, uiState: MotionTrackingUiState, activeClip: VideoClip?) {
    val enabled = !uiState.isTracking && activeClip != null
    val mode = uiState.settings.featureMode
    LiveHint(uiState, "Shi-Tomasi + pyramidal LK. Point, multi-point, or planar (homography).")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            MotionFeatureType.POINT_TRACKING,
            MotionFeatureType.MULTI_POINT,
            MotionFeatureType.PLANAR,
            MotionFeatureType.POSITION_ONLY,
            MotionFeatureType.ROTATION_SCALE
        ).forEach { feat ->
            Chip(feat.title, mode == feat) { viewModel.updateFeatureMode(feat) }
        }
        Chip("Path", uiState.showMotionPath) { viewModel.toggleMotionPathVisibility() }
    }
    RangeRow(viewModel, TrackingCategory.MOTION, enabled, "start_point_motion_tracking_button")
}

@Composable
private fun AttachCategoryContent(viewModel: StudioViewModel, uiState: MotionTrackingUiState) {
    var selectedTarget by remember { mutableStateOf(uiState.selectedAttachment) }
    val settings = uiState.settings
    Text("Bind the track to a layer. Preview and export sample the same path.", color = TextSecondary, fontSize = 10.5.sp)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        AttachmentTarget.values().forEach { target ->
            Chip(target.title, selectedTarget == target) { selectedTarget = target }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        FollowCheck("Pos", settings.followPosition) { viewModel.updateFollowFlags(it, settings.followScale, settings.followRotation) }
        FollowCheck("Scale", settings.followScale) { viewModel.updateFollowFlags(settings.followPosition, it, settings.followRotation) }
        FollowCheck("Rot", settings.followRotation) { viewModel.updateFollowFlags(settings.followPosition, settings.followScale, it) }
    }
    Button(
        onClick = {
            viewModel.attachTrackingToLayer(
                target = selectedTarget,
                followPos = settings.followPosition,
                followScale = settings.followScale,
                followRot = settings.followRotation
            )
        },
        enabled = uiState.activeResult != null && !uiState.activeResult.isEmpty,
        colors = ButtonDefaults.buttonColors(containerColor = Emerald, contentColor = Color.Black),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().height(34.dp).testTag("apply_attachment_button")
    ) {
        Icon(Icons.Default.Link, null, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text("Attach to ${selectedTarget.title}", fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
    if (uiState.activeResult == null || uiState.activeResult.isEmpty) {
        Text("Track a target first.", color = Color(0xFFFFB74D), fontSize = 10.5.sp)
    }
}

@Composable
private fun FollowCheck(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange, colors = CheckboxDefaults.colors(checkedColor = Emerald))
        Text(label, color = TextPrimary, fontSize = 11.sp)
    }
}

@Composable
private fun ControlsCategoryContent(viewModel: StudioViewModel, uiState: MotionTrackingUiState, currentPosMs: Long) {
    var smoothing by remember { mutableStateOf(uiState.settings.smoothingFactor) }
    var searchWindow by remember { mutableStateOf(uiState.settings.searchWindowFactor) }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Smoothing", color = TextPrimary, fontSize = 11.sp)
            Text("${(smoothing * 100).toInt()}%", color = Emerald, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = smoothing,
            onValueChange = { smoothing = it; viewModel.updateSmoothingFactor(it) },
            valueRange = 0f..0.9f,
            colors = SliderDefaults.colors(thumbColor = Emerald, activeTrackColor = Emerald)
        )
    }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Search window", color = TextPrimary, fontSize = 11.sp)
            Text("${"%.1f".format(searchWindow)}x", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = searchWindow,
            onValueChange = { searchWindow = it; viewModel.updateSearchWindowFactor(it) },
            valueRange = 1.0f..4.0f,
            steps = 6,
            colors = SliderDefaults.colors(thumbColor = Cyan, activeTrackColor = Cyan)
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { viewModel.manualCorrectionAtCurrentTime(currentPosMs) },
            enabled = uiState.activeResult != null,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF133B44), contentColor = TextPrimary),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.weight(1f).height(34.dp)
        ) {
            Icon(Icons.Default.EditLocation, null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("Set Key", fontSize = 11.sp)
        }
        Button(
            onClick = { viewModel.deleteActiveTrackingData() },
            enabled = uiState.activeResult != null,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B1A1A), contentColor = Color(0xFFFF5252)),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.weight(1f).height(34.dp)
        ) {
            Icon(Icons.Default.DeleteOutline, null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("Delete", fontSize = 11.sp)
        }
    }
}

@Composable
private fun QuickTool(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = CardBg,
        border = BorderStroke(1.dp, Border),
        modifier = Modifier.height(40.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, null, tint = Emerald, modifier = Modifier.size(15.dp))
            Text(label, color = TextPrimary, fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
        }
    }
}
