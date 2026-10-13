package com.example.ui.components.effects

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.effects.EffectThumbnailRaster
import com.example.engine.effects.ProductionEffectApplicator
import com.example.engine.effects.registry.EffectCategory
import com.example.engine.effects.registry.EffectsAssetRegistry
import com.example.engine.effects.registry.RegisteredEffect
import com.example.engine.media.VideoThumbnailManager
import com.example.ui.StudioViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Effects category panel: live clip-frame thumbnails, real registry effects only.
 */
@Composable
fun EffectsCategoryPanel(
  category: EffectCategory,
  viewModel: StudioViewModel,
  onClose: () -> Unit,
  onApply: (RegisteredEffect?) -> Unit,
  onIntensity: (RegisteredEffect, Float) -> Unit = { _, _ -> },
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val timeline by viewModel.timelineEngine.timeline.collectAsState()
  val clip = remember(timeline) { viewModel.targetClipForEffects() }
  val registrySnapshot = EffectsAssetRegistry.snapshot()

  var searchQuery by remember(category) { mutableStateOf("") }
  var draftSelectedEffect by remember(category, clip?.id) { mutableStateOf<RegisteredEffect?>(null) }
  var isNoneSelected by remember(category, clip?.id) { mutableStateOf(false) }
  var strength by remember(category, clip?.id) { mutableFloatStateOf(1f) }

  LaunchedEffect(category, clip?.id) {
    val current = viewModel.targetClipForEffects()
    val applied = ProductionEffectApplicator.appliedOn(current, category)
    draftSelectedEffect = applied
    isNoneSelected = applied == null
    strength = applied?.let { ProductionEffectApplicator.intensityOn(current, it) } ?: 1f
  }

  val availableEffects = remember(category, searchQuery, registrySnapshot) {
    EffectsAssetRegistry.searchEffects(category, searchQuery)
  }

  val previewUri = clip?.uri.orEmpty()
  val previewStartMs = clip?.sourceStartMs ?: 0L
  var baseThumbnail by remember(previewUri, previewStartMs) {
    val key = VideoThumbnailManager.makeKey(previewUri, previewStartMs, 96, 96)
    mutableStateOf(VideoThumbnailManager.getCachedThumbnail(key))
  }
  LaunchedEffect(previewUri, previewStartMs) {
    if (previewUri.isNotBlank() && baseThumbnail == null) {
      runCatching {
        VideoThumbnailManager.requestThumbnail(
          context = context,
          uri = previewUri,
          sourceTimeMs = previewStartMs,
          targetWidth = 96,
          targetHeight = 96,
          isVideo = true
        ) { bmp -> baseThumbnail = bmp }
      }
    }
  }

  val panelBackground = Brush.verticalGradient(
    colors = listOf(Color(0xFF0A2B27), Color(0xFF0F263B))
  )

  Column(
    modifier = modifier
      .fillMaxWidth()
      .heightIn(min = 260.dp, max = 380.dp)
      .height(320.dp)
      .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
      .background(panelBackground)
      .border(1.dp, Color(0xFF164746), RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
      .padding(horizontal = 14.dp, vertical = 8.dp)
      .testTag("effects_panel_${category.name.lowercase()}")
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(bottom = 10.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      IconButton(
        onClick = onClose,
        modifier = Modifier.size(38.dp).testTag("effects_close_btn")
      ) {
        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
      }

      Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF071B20),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.5f)),
        modifier = Modifier
          .weight(1f)
          .height(38.dp)
          .padding(horizontal = 8.dp)
      ) {
        Row(
          modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          Icon(Icons.Default.Search, contentDescription = "Search", tint = Color(0xFF10B981), modifier = Modifier.size(16.dp))
          Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (searchQuery.isEmpty()) {
              Text("Search ${category.displayName}...", color = Color(0xFF64748B), fontSize = 13.sp)
            }
            BasicTextField(
              value = searchQuery,
              onValueChange = { searchQuery = it },
              textStyle = TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium),
              cursorBrush = SolidColor(Color(0xFF10B981)),
              singleLine = true,
              modifier = Modifier.fillMaxWidth().testTag("effects_search_input")
            )
          }
          if (searchQuery.isNotEmpty()) {
            IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(20.dp)) {
              Icon(Icons.Default.Close, contentDescription = "Clear search", tint = Color(0xFF94A3B8), modifier = Modifier.size(14.dp))
            }
          }
        }
      }

      IconButton(
        onClick = {
          if (isNoneSelected) onApply(null)
          else draftSelectedEffect?.let { onIntensity(it, strength) }
          onClose()
        },
        modifier = Modifier.size(38.dp).testTag("effects_confirm_btn")
      ) {
        Icon(Icons.Default.Check, contentDescription = "Apply Effect", tint = Color(0xFF10B981))
      }
    }

    if (draftSelectedEffect != null && !isNoneSelected) {
      Text(
        text = "Strength ${(strength * 100).toInt()}%",
        color = Color(0xFF99F6E4),
        fontSize = 12.sp,
        modifier = Modifier.padding(bottom = 2.dp)
      )
      Slider(
        value = strength,
        onValueChange = { value ->
          strength = value
          draftSelectedEffect?.let { onIntensity(it, value) }
        },
        valueRange = 0f..1f,
        modifier = Modifier.fillMaxWidth().testTag("effect_strength_slider")
      )
    }

    LazyVerticalGrid(
      columns = GridCells.Fixed(4),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.fillMaxWidth().weight(1f)
    ) {
      item {
        val isSelected = isNoneSelected || draftSelectedEffect == null
        Surface(
          shape = RoundedCornerShape(10.dp),
          color = if (isSelected) Color(0xFF10B981).copy(alpha = 0.25f) else Color(0xFF0F2F32),
          border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isSelected) Color(0xFF10B981) else Color(0xFF1E5253)
          ),
          modifier = Modifier
            .fillMaxWidth()
            .height(86.dp)
            .clickable {
              isNoneSelected = true
              draftSelectedEffect = null
              onApply(null)
            }
            .testTag("effect_none_option")
        ) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(4.dp)
          ) {
            if (baseThumbnail != null) {
              Image(
                bitmap = baseThumbnail!!.asImageBitmap(),
                contentDescription = "None",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(6.dp))
              )
            } else {
              Icon(
                imageVector = Icons.Default.Block,
                contentDescription = "None",
                tint = if (isSelected) Color(0xFF10B981) else Color.White,
                modifier = Modifier.size(20.dp)
              )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text("None", color = Color.White, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, fontSize = 11.sp)
          }
        }
      }

      items(availableEffects, key = { it.id }) { effect ->
        val isSelected = !isNoneSelected && draftSelectedEffect?.id == effect.id
        Surface(
          shape = RoundedCornerShape(10.dp),
          color = if (isSelected) Color(0xFF10B981).copy(alpha = 0.25f) else Color(0xFF0E2530),
          border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isSelected) Color(0xFF10B981) else Color(0xFF1A4557)
          ),
          modifier = Modifier
            .fillMaxWidth()
            .height(86.dp)
            .clickable {
              isNoneSelected = false
              draftSelectedEffect = effect
              strength = 1f
              onApply(effect)
            }
            .testTag("effect_item_${effect.id}")
        ) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(4.dp)
          ) {
            EffectLiveSwatch(
              shaderKey = effect.shaderKey,
              baseBitmap = baseThumbnail,
              modifier = Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(6.dp))
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
              text = effect.name,
              color = Color.White,
              fontSize = 10.sp,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
              textAlign = TextAlign.Center
            )
          }
        }
      }
    }
  }
}

@Composable
internal fun EffectLiveSwatch(
  shaderKey: String?,
  baseBitmap: Bitmap?,
  modifier: Modifier = Modifier
) {
  var image by remember(shaderKey, baseBitmap) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
  LaunchedEffect(shaderKey, baseBitmap) {
    image = withContext(Dispatchers.Default) {
      val processed = if (baseBitmap != null && !baseBitmap.isRecycled) {
        EffectThumbnailRaster.applyToBitmap(baseBitmap, shaderKey)
      } else {
        val pixels = EffectThumbnailRaster.argb(shaderKey, 48)
        Bitmap.createBitmap(pixels, 48, 48, Bitmap.Config.ARGB_8888)
      }
      processed.asImageBitmap()
    }
  }
  val shown = image
  if (shown != null) {
    Image(
      bitmap = shown,
      contentDescription = shaderKey,
      contentScale = ContentScale.Crop,
      modifier = modifier
    )
  } else {
    Box(modifier = modifier.background(Color(0xFF071419)))
  }
}
