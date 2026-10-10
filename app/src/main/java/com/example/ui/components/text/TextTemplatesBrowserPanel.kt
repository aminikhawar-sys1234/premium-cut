package com.example.ui.components.text

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.text.registry.RegisteredTextTemplate
import com.example.ui.StudioViewModel

/**
 * Main Text Templates panel (Text tools → Text Templates).
 * 4-column card grid, vertical scroll, live 2D/3D thumbnails from the real renderer.
 * Templates come from packaged JSON / installed plugins — no dummy catalog.
 */
@Composable
fun TextTemplatesBrowserPanel(
  viewModel: StudioViewModel,
  onClose: () -> Unit,
  onApplyTemplate: (RegisteredTextTemplate) -> Unit = {},
  modifier: Modifier = Modifier
) {
  val installedTemplates = rememberInstalledTextTemplates()

  val panelBackground = Brush.verticalGradient(
    colors = listOf(
      Color(0xFF0F2537),
      Color(0xFF092825)
    )
  )

  Column(
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
      .background(panelBackground)
      .border(1.dp, Color(0xFF1B4450), RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
      .padding(horizontal = 14.dp, vertical = 6.dp)
      .testTag("text_templates_browser_panel")
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(bottom = 6.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      IconButton(
        onClick = onClose,
        modifier = Modifier
          .size(32.dp)
          .testTag("text_templates_close_btn")
      ) {
        Icon(
          imageVector = Icons.Default.Close,
          contentDescription = "Close",
          tint = Color.White,
          modifier = Modifier.size(20.dp)
        )
      }

      Text(
        text = "Text Templates",
        color = Color.White,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold
      )

      Spacer(modifier = Modifier.size(32.dp))
    }

    if (installedTemplates.isEmpty()) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .padding(16.dp),
        contentAlignment = Alignment.Center
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Text(
            text = "No Text Templates Installed",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            textAlign = TextAlign.Center
          )
          Text(
            text = "Packaged 2D and 3D live-animate templates appear in this 4-column browser.",
            color = Color(0xFF94A3B8),
            fontSize = 12.sp,
            textAlign = TextAlign.Center
          )
        }
      }
    } else {
      TextTemplateGridViewport {
        TextTemplateCardGrid(
          templates = installedTemplates,
          onApply = { template ->
            onApplyTemplate(template)
            onClose()
          },
          modifier = Modifier.fillMaxSize()
        )
      }
    }
  }
}
