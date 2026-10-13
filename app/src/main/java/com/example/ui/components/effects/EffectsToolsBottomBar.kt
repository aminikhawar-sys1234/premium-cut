package com.example.ui.components.effects

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.engine.effects.registry.EffectCategory
import com.example.engine.effects.registry.EffectsAssetRegistry
import com.example.ui.components.navigation.FuturisticBottomNavBarContainer
import com.example.ui.components.navigation.FuturisticNavItemData
import com.example.ui.components.navigation.NavItemColorTheme

/**
 * Dedicated Effects sub-navigation. Categories come from the live registry
 * (ProductionEffectCatalog + any real remote packages), not a hardcoded row.
 */
@Composable
fun EffectsToolsBottomBar(
  activeCategory: EffectCategory?,
  onSelectCategory: (EffectCategory) -> Unit,
  onBackToMainMenu: () -> Unit,
  modifier: Modifier = Modifier
) {
  val snapshot = EffectsAssetRegistry.snapshot()
  val categories = remember(snapshot) { EffectsAssetRegistry.populatedCategories() }

  val items = categories.map { category ->
    FuturisticNavItemData(
      id = category.name,
      label = category.displayName,
      icon = iconFor(category),
      theme = themeFor(category),
      isSelected = activeCategory == category,
      testTag = category.tag,
      onClick = { onSelectCategory(category) }
    )
  }

  FuturisticBottomNavBarContainer(
    onBackClick = onBackToMainMenu,
    items = items,
    modifier = modifier,
    showDividers = true
  )
}

private fun iconFor(category: EffectCategory): ImageVector = when (category) {
  EffectCategory.VIDEO_EFFECTS -> Icons.Default.Videocam
  EffectCategory.BODY_EFFECTS -> Icons.Default.AccessibilityNew
  EffectCategory.FACE_EFFECTS -> Icons.Default.Face
  EffectCategory.PHOTO_EFFECTS -> Icons.Default.Image
  EffectCategory.AI_EFFECTS -> Icons.Default.AutoFixHigh
}

private fun themeFor(category: EffectCategory): NavItemColorTheme = when (category) {
  EffectCategory.VIDEO_EFFECTS -> NavItemColorTheme(Color(0xFF0F3B37), Color(0xFF00E5FF))
  EffectCategory.BODY_EFFECTS -> NavItemColorTheme(Color(0xFF0E382A), Color(0xFF10B981))
  EffectCategory.FACE_EFFECTS -> NavItemColorTheme(Color(0xFF3A2430), Color(0xFFF9A8D4))
  EffectCategory.PHOTO_EFFECTS -> NavItemColorTheme(Color(0xFF132F42), Color(0xFF38BDF8))
  EffectCategory.AI_EFFECTS -> NavItemColorTheme(Color(0xFF2A1B4E), Color(0xFFC084FC))
}
