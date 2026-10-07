package com.example.ui.components.ar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ahstudio.face.deformation.DeformationParams
import com.ahstudio.face.deformation.isActive
import com.example.ui.StudioViewModel

private val BlueGreenDarkTop = Color(0xFF091E26)
private val BlueGreenDarkBottom = Color(0xFF06151B)
private val BlueGreenBorder = Color(0xFF133B44)
private val EmeraldAccent = Color(0xFF00D1B2)
private val CyanBlue = Color(0xFF00B4D8)
private val CardBg = Color(0xFF0D252E)
private val TextPrimary = Color(0xFFF1F5F9)
private val TextSecondary = Color(0xFF94A3B8)

@Composable
fun ArEffectSelectorPanel(
    viewModel: StudioViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedCategory by remember { mutableStateOf(ArFilterCategory.TRENDING) }
    val activeArFilter by viewModel.activeArFilter.collectAsState()
    val showMeshGrid by viewModel.showFaceMeshGrid.collectAsState()
    val arScale by viewModel.arFilterScale.collectAsState()
    val arOffsetY by viewModel.arFilterOffsetY.collectAsState()
    val arOpacity by viewModel.arFilterOpacity.collectAsState()
    val arStatus by viewModel.arStatusMessage.collectAsState()
    val arApplied by viewModel.arApplied.collectAsState()
    val activeClip = viewModel.getSelectedVideoClip()
    val faceDeformMap by viewModel.faceDeform.collectAsState()
    val deform = activeClip?.id?.let { faceDeformMap[it] } ?: DeformationParams()
    var showReshape by remember { mutableStateOf(false) }

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
            // 1. TOP COMPACT HEADER ROW
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .background(Color(0xFF071920))
                    .padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Reset Button
                TextButton(
                    onClick = {
                        viewModel.selectArFilter(null)
                        viewModel.setShowFaceMeshGrid(false)
                    },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier
                        .height(28.dp)
                        .testTag("ar_filter_reset_button")
                ) {
                    Text("Reset", color = Color(0xFFFF5252), fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                }

                // Title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Face,
                        contentDescription = null,
                        tint = EmeraldAccent,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "AR Face Filters",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.5.sp
                    )
                }

                // Close Button
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(28.dp)
                        .testTag("ar_filter_close_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Divider(color = BlueGreenBorder, thickness = 1.dp)

            // 2. CATEGORY SELECTOR ROW
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF081C23))
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ArFilterCategory.values().forEach { cat ->
                    val isSelected = cat == selectedCategory
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (isSelected) EmeraldAccent else CardBg,
                        border = BorderStroke(1.dp, if (isSelected) EmeraldAccent else BlueGreenBorder),
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { selectedCategory = cat }
                            .testTag("ar_category_${cat.name.lowercase()}")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(text = cat.iconEmoji, fontSize = 11.5.sp)
                            Text(
                                text = cat.title,
                                color = if (isSelected) Color.Black else TextPrimary,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 11.5.sp
                            )
                        }
                    }
                }
            }

            Divider(color = BlueGreenBorder.copy(alpha = 0.5f), thickness = 0.5.dp)

            // 3. TOGGLE MESH GRID ROW
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.Grid4x4, contentDescription = null, tint = CyanBlue, modifier = Modifier.size(16.dp))
                    Text("Face Tracking Grid", color = TextPrimary, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                }
                Switch(
                    checked = showMeshGrid,
                    onCheckedChange = { viewModel.setShowFaceMeshGrid(it) },
                    modifier = Modifier.height(24.dp).testTag("toggle_face_mesh_switch"),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = EmeraldAccent
                    )
                )
            }

            // 4. AR FILTER GRID CATALOG
            val filteredItems = remember(selectedCategory) {
                ArFilterCatalog.filters.filter { it.category == selectedCategory }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(98.dp)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredItems) { item ->
                        val isSelected = activeArFilter?.id == item.id
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) EmeraldAccent.copy(alpha = 0.2f) else CardBg,
                            border = BorderStroke(
                                1.5.dp,
                                if (isSelected) EmeraldAccent else BlueGreenBorder
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(82.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { viewModel.selectArFilter(if (isSelected) null else item) }
                                .testTag("ar_item_${item.id}")
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(text = item.iconEmoji, fontSize = 22.sp)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = item.name,
                                    color = if (isSelected) EmeraldAccent else TextPrimary,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 10.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }

            // 4b. FACE RESHAPE (live mesh warp) - independent of stickers, drives preview + export
            if (activeClip != null) {
                val clipId = activeClip.id
                Column(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF07171E)).padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showReshape = !showReshape }
                            .padding(vertical = 4.dp)
                            .testTag("face_reshape_header"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Face Reshape", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            if (deform.isActive()) {
                                Box(Modifier.size(6.dp).clip(CircleShape).background(EmeraldAccent))
                            }
                        }
                        Text(if (showReshape) "Hide" else "Show", color = EmeraldAccent, fontSize = 11.sp)
                    }
                    AnimatedVisibility(visible = showReshape) {
                        Column(
                            modifier = Modifier.heightIn(max = 230.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            // One-tap looks
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                com.ahstudio.face.ui.FaceReshapePresets.all.forEach { preset ->
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = CardBg,
                                        border = BorderStroke(1.dp, BlueGreenBorder),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable { viewModel.applyFaceReshapePreset(clipId, preset.params) }
                                            .testTag("face_reshape_preset_${preset.name.lowercase()}")
                                    ) {
                                        Text(
                                            "${preset.emoji} ${preset.name}",
                                            color = TextPrimary, fontSize = 11.sp,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                            @Composable
                            fun row(tag: String, label: String, v: Float, set: (DeformationParams, Float) -> DeformationParams) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("$label ${(v * 100).toInt()}", color = TextSecondary, fontSize = 10.5.sp, modifier = Modifier.width(92.dp))
                                    Slider(
                                        value = v,
                                        onValueChange = { nv -> viewModel.setFaceDeform(clipId) { set(it, nv) } },
                                        valueRange = 0f..1f,
                                        modifier = Modifier.weight(1f).height(24.dp).testTag("face_reshape_$tag"),
                                        colors = SliderDefaults.colors(thumbColor = EmeraldAccent, activeTrackColor = EmeraldAccent)
                                    )
                                }
                            }
                            row("eyes", "Big Eyes", deform.eyeEnlarge) { d, v -> d.copy(eyeEnlarge = v) }
                            row("slim", "Slim Face", deform.faceSlim) { d, v -> d.copy(faceSlim = v) }
                            row("jaw", "Jawline", deform.jawSharp) { d, v -> d.copy(jawSharp = v) }
                            row("nose", "Nose", deform.noseReshape) { d, v -> d.copy(noseReshape = v) }
                            row("chin", "Chin", deform.chinAdjust) { d, v -> d.copy(chinAdjust = v) }
                            row("smile", "Smile", deform.smileAdjust) { d, v -> d.copy(smileAdjust = v) }
                            TextButton(
                                onClick = { viewModel.resetFaceDeform(clipId) },
                                enabled = deform.isActive(),
                                modifier = Modifier.testTag("face_reshape_reset")
                            ) { Text("Reset reshape", fontSize = 11.sp) }
                        }
                    }
                }
            }

            // 5. SLIDERS & ACTION ROW (When filter active)
            AnimatedVisibility(visible = activeArFilter != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF07171E))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Size Scale Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Scale: ${(arScale * 100).toInt()}%", color = TextSecondary, fontSize = 10.5.sp)
                        Slider(
                            value = arScale,
                            onValueChange = { viewModel.setArFilterScale(it) },
                            valueRange = 0.5f..2.0f,
                            modifier = Modifier.weight(1f).height(24.dp),
                            colors = SliderDefaults.colors(thumbColor = EmeraldAccent, activeTrackColor = EmeraldAccent)
                        )
                    }

                    // Vertical Offset Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Offset Y: ${(arOffsetY * 100).toInt()}%", color = TextSecondary, fontSize = 10.5.sp)
                        Slider(
                            value = arOffsetY,
                            onValueChange = { viewModel.setArFilterOffsetY(it) },
                            valueRange = -0.5f..0.5f,
                            modifier = Modifier.weight(1f).height(24.dp),
                            colors = SliderDefaults.colors(thumbColor = CyanBlue, activeTrackColor = CyanBlue)
                        )
                    }

                    // Opacity Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Opacity: ${(arOpacity * 100).toInt()}%", color = TextSecondary, fontSize = 10.5.sp)
                        Slider(
                            value = arOpacity,
                            onValueChange = { viewModel.setArFilterOpacity(it) },
                            valueRange = 0.1f..1.0f,
                            modifier = Modifier.weight(1f).height(24.dp),
                            colors = SliderDefaults.colors(thumbColor = EmeraldAccent, activeTrackColor = EmeraldAccent)
                        )
                    }

                    val canExport = activeArFilter?.canExport == true
                    val appliedHere = viewModel.getSelectedVideoClip()?.id?.let { arApplied.containsKey(it) } == true
                    Text(
                        text = arStatus ?: when {
                            canExport -> "Applies a tracked mosaic to the exported video."
                            appliedHere -> "Attached to the clip. It is part of the export; sliders update it live."
                            else -> "Apply to attach this filter to the clip so it is included in the export."
                        },
                        color = if (arStatus != null) CyanBlue else TextSecondary,
                        fontSize = 10.5.sp
                    )

                    // Apply to Timeline Button
                    Button(
                        onClick = { viewModel.applyArFilterToTimeline() },
                        enabled = true,
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldAccent, contentColor = Color.Black),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                            .testTag("apply_ar_filter_to_timeline")
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            if (appliedHere && !canExport) "Update on Timeline Clip" else "Apply to Timeline Clip",
                            fontWeight = FontWeight.Bold, fontSize = 12.sp
                        )
                    }

                    if (appliedHere) {
                        TextButton(
                            onClick = { viewModel.removeArFilterFromClip() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(32.dp)
                                .testTag("remove_ar_filter_from_clip")
                        ) {
                            Text("Remove from Clip", color = TextSecondary, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}
