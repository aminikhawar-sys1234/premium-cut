package com.example.ui.components.text

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * Templates merge the packaged catalog, installed plugins, and live Firestore uploads.
 */
@Composable
fun TextTemplatesBrowserPanel(
  viewModel: StudioViewModel,
  onClose: () -> Unit,
  onApplyTemplate: (RegisteredTextTemplate) -> Unit = {},
  modifier: Modifier = Modifier
) {
  val installedTemplates = rememberInstalledTextTemplates()
  var selectedCategory by remember { mutableStateOf("All") }
  val categories = remember(installedTemplates) {
    listOf("All") + installedTemplates.map { it.category.ifBlank { "General" } }
      .distinct()
      .sorted()
  }
  val visibleTemplates = remember(installedTemplates, selectedCategory) {
    if (selectedCategory == "All") {
      installedTemplates
    } else {
      installedTemplates.filter { it.category.equals(selectedCategory, ignoreCase = true) }
    }
  }

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

    TextTemplateSyncBar()

    if (categories.size > 2) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState())
          .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        categories.forEach { category ->
          val isSelected = selectedCategory == category
          Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (isSelected) Color(0xFF10B981) else Color(0xFF0F2C2C),
            border = androidx.compose.foundation.BorderStroke(
              1.dp,
              if (isSelected) Color(0xFF34D399) else Color(0xFF194444)
            ),
            modifier = Modifier
              .clickable { selectedCategory = category }
              .testTag("text_templates_category_${category.lowercase().replace(' ', '_')}")
          ) {
            Text(
              text = category,
              color = if (isSelected) Color(0xFF06221D) else Color.White,
              fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
              fontSize = 12.sp,
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
            )
          }
        }
      }
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
            text = "Built-in, plugin, and Firestore text templates appear here when available.",
            color = Color(0xFF94A3B8),
            fontSize = 12.sp,
            textAlign = TextAlign.Center
          )
        }
      }
    } else if (visibleTemplates.isEmpty()) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .padding(16.dp),
        contentAlignment = Alignment.Center
      ) {
        Text(
          text = "No templates in $selectedCategory yet",
          color = Color(0xFF94A3B8),
          fontSize = 12.sp,
          textAlign = TextAlign.Center
        )
      }
    } else {
      TextTemplateGridViewport {
        TextTemplateCardGrid(
          templates = visibleTemplates,
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
