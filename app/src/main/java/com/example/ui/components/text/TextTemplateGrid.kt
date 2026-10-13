package com.example.ui.components.text

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.domain.model.TextClip
import com.example.engine.text.TextLayerRenderer
import com.example.engine.text.registry.RegisteredTextTemplate
import com.example.engine.text.registry.TextAssetRegistry
import com.example.engine.text.registry.TextTemplateCatalog
import com.example.engine.text.registry.TextTemplateSyncState

private val CardBg = Color(0xFF0E2530)
private val CardBorder = Color(0xFF1A4557)
private val SelectedFill = Color(0xFF10B981).copy(alpha = 0.22f)
private val SelectedBorder = Color(0xFF10B981)
private val ThumbBg = Color(0xFF07141C)
private const val GRID_COLUMNS = 4

@Composable
internal fun rememberTemplatePreviewClockMs(): Long {
  val clock = remember { mutableLongStateOf(0L) }
  LaunchedEffect(Unit) {
    val origin = withFrameNanos { it }
    while (true) {
      withFrameNanos { now ->
        val next = ((now - origin) / 1_000_000L).coerceAtLeast(0L)
        if (next - clock.longValue >= 48L) {
          clock.longValue = next
        }
      }
    }
  }
  return clock.longValue
}

@Composable
fun TextTemplateCardGrid(
  templates: List<RegisteredTextTemplate>,
  selectedClip: TextClip? = null,
  showNone: Boolean = false,
  onNone: (() -> Unit)? = null,
  onApply: (RegisteredTextTemplate) -> Unit,
  modifier: Modifier = Modifier
) {
  val clockMs = rememberTemplatePreviewClockMs()

  LazyVerticalGrid(
    columns = GridCells.Fixed(GRID_COLUMNS),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
    contentPadding = PaddingValues(bottom = 8.dp),
    modifier = modifier
      .fillMaxWidth()
      .testTag("text_template_grid")
  ) {
    if (showNone && onNone != null) {
      item(key = "template-none") {
        NoneTemplateCard(onClick = onNone)
      }
    }
    items(templates, key = { it.id }) { template ->
      val selected = selectedClip != null && TextTemplateCatalog.matches(selectedClip, template)
      TextTemplateCard(
        template = template,
        selected = selected,
        clockMs = clockMs,
        onClick = { onApply(template) }
      )
    }
  }
}

@Composable
internal fun rememberInstalledTextTemplates(): List<RegisteredTextTemplate> {
  val context = LocalContext.current
  LaunchedEffect(Unit) {
    // Panel opened (Add Text / Text Templates): start the Firebase download right away.
    // Local templates render instantly; cloud templates merge in as soon as they arrive.
    TextTemplateCatalog.ensureLoaded(context)
    TextTemplateCatalog.refreshRemoteTemplates(context)
  }
  // Return a snapshot so Compose observes registry mutations and passes a fresh list to the grid.
  return TextAssetRegistry.getTemplates().toList()
}

/**
 * Firebase download status for the text-template panels: a compact progress row while the
 * cloud catalog downloads, a loaded-count row once cloud templates are merged, and an
 * error row with Retry when Firebase cannot be reached.
 */
@Composable
internal fun TextTemplateSyncBar(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val syncState by TextTemplateCatalog.syncState.collectAsState()

  when (val state = syncState) {
    TextTemplateSyncState.Idle -> Unit
    TextTemplateSyncState.Loading -> {
      Row(
        modifier = modifier.fillMaxWidth().padding(bottom = 6.dp).testTag("text_templates_sync_loading"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        CircularProgressIndicator(
          color = SelectedBorder,
          strokeWidth = 2.dp,
          modifier = Modifier.size(14.dp)
        )
        Text(
          text = "Downloading templates from Firebase…",
          color = Color(0xFFCBD5E1),
          fontSize = 11.sp
        )
      }
    }
    is TextTemplateSyncState.Ready -> {
      if (state.remoteCount > 0) {
        Row(
          modifier = modifier.fillMaxWidth().padding(bottom = 6.dp).testTag("text_templates_sync_ready"),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          Icon(
            imageVector = Icons.Default.CloudDownload,
            contentDescription = null,
            tint = SelectedBorder,
            modifier = Modifier.size(14.dp)
          )
          Text(
            text = "${state.remoteCount} Firebase template${if (state.remoteCount == 1) "" else "s"} loaded",
            color = Color(0xFFCBD5E1),
            fontSize = 11.sp
          )
        }
      }
    }
    is TextTemplateSyncState.Error -> {
      Row(
        modifier = modifier.fillMaxWidth().padding(bottom = 6.dp).testTag("text_templates_sync_error"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Icon(
          imageVector = Icons.Default.CloudOff,
          contentDescription = null,
          tint = Color(0xFFFCA5A5),
          modifier = Modifier.size(14.dp)
        )
        Text(
          text = "Firebase templates failed to load",
          color = Color(0xFFFCA5A5),
          fontSize = 11.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f)
        )
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = SelectedBorder,
          modifier = Modifier
            .clickable { TextTemplateCatalog.refreshRemoteTemplates(context) }
            .testTag("text_templates_sync_retry")
        ) {
          Text(
            text = "Retry",
            color = Color(0xFF06221D),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
          )
        }
      }
    }
  }
}

@Composable
private fun NoneTemplateCard(onClick: () -> Unit) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(8.dp))
      .background(Color(0xFF0F2F32))
      .border(1.dp, Color(0xFF1E5253), RoundedCornerShape(8.dp))
      .clickable(onClick = onClick)
      .testTag("template_none_opt")
      .padding(4.dp),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(1.05f)
        .clip(RoundedCornerShape(6.dp))
        .background(ThumbBg),
      contentAlignment = Alignment.Center
    ) {
      Text("None", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
    Text(
      text = "None",
      color = Color.White,
      fontSize = 9.sp,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(top = 3.dp)
    )
  }
}

@Composable
private fun TextTemplateCard(
  template: RegisteredTextTemplate,
  selected: Boolean,
  clockMs: Long,
  onClick: () -> Unit
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(8.dp))
      .background(if (selected) SelectedFill else CardBg)
      .border(1.dp, if (selected) SelectedBorder else CardBorder, RoundedCornerShape(8.dp))
      .clickable(onClick = onClick)
      .testTag("text_template_card_${template.id}")
      .padding(4.dp),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(1.05f)
        .clip(RoundedCornerShape(6.dp))
        .background(ThumbBg)
    ) {
      AnimatedTextTemplateThumbnail(
        clip = template.templateClip,
        clockMs = clockMs,
        modifier = Modifier.fillMaxSize()
      )
      // A cloud template may ship its own preview image; download and show it over the
      // live-rendered style preview (the renderer stays visible while it loads/fails).
      val previewUrl = template.previewThumbnailUrl?.takeIf {
        it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
      }
      if (previewUrl != null) {
        AsyncImage(
          model = previewUrl,
          contentDescription = template.name,
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize().testTag("text_template_preview_${template.id}")
        )
      }
      if (template.isRemote) {
        Box(
          modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(3.dp)
            .clip(RoundedCornerShape(50))
            .background(SelectedBorder)
            .testTag("text_template_cloud_badge_${template.id}")
            .padding(3.dp)
        ) {
          Icon(
            imageVector = Icons.Default.CloudDownload,
            contentDescription = "From Firebase",
            tint = Color(0xFF06221D),
            modifier = Modifier.size(10.dp)
          )
        }
      }
    }
    Text(
      text = template.name,
      color = Color.White,
      fontSize = 9.sp,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(top = 3.dp)
    )
  }
}

@Composable
fun AnimatedTextTemplateThumbnail(
  clip: TextClip,
  clockMs: Long,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  Canvas(modifier = modifier.testTag("text_template_thumb")) {
    val w = size.width.toInt().coerceAtLeast(1)
    val h = size.height.toInt().coerceAtLeast(1)
    drawIntoCanvas { composeCanvas ->
      val native = composeCanvas.nativeCanvas
      val save = native.save()
      try {
        runCatching {
          TextLayerRenderer.drawTemplatePreview(
            canvas = native,
            clip = clip,
            previewLoopMs = clockMs,
            width = w,
            height = h,
            context = context
          )
        }
      } finally {
        native.restoreToCount(save)
      }
    }
  }
}

@Composable
internal fun TextTemplateGridViewport(
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit
) {
  Box(
    modifier = modifier
      .fillMaxWidth()
      .height(214.dp)
  ) {
    content()
  }
}
