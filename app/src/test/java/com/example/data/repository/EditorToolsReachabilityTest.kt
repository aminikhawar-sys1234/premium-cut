package com.example.data.repository

import com.example.domain.model.EditorToolItem
import com.example.domain.model.EditorToolRoute
import com.example.domain.model.isHiddenFromNavigation
import com.example.domain.model.navigationTools
import com.example.domain.model.resolveRoute
import com.example.ui.EditorToolbarTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorToolsReachabilityTest {
  private val repo = EditorToolsRepository()

  @Test
  fun defaultToolsExposeBackgroundFaceAndAdvancedAnimation() {
    val tabs = repo.getDefaultEditorTools().mapNotNull { it.mappedTab }.toSet()
    assertTrue(EditorToolbarTab.BACKGROUND in tabs)
    assertTrue(EditorToolbarTab.AR_EFFECTS in tabs)
    assertTrue(EditorToolbarTab.ADVANCED_ANIMATION in tabs)
    assertTrue(EditorToolbarTab.AI_MATTING in tabs)
  }

  @Test
  fun remoteListMissingToolsStillGetsRequiredTools() {
    val remote = listOf(EditorToolItem("tool_text", "Text", "TOOL_TEXT", "text", 1, true))
    val merged = repo.withRequiredTools(remote)
    val tabs = merged.mapNotNull { it.mappedTab }.toSet()
    assertTrue(EditorToolbarTab.BACKGROUND in tabs)
    assertTrue(EditorToolbarTab.AR_EFFECTS in tabs)
    assertTrue(EditorToolbarTab.ADVANCED_ANIMATION in tabs)
    assertTrue(EditorToolbarTab.AI_MATTING in tabs)
    assertEquals(remote.first(), merged.first())
  }

  @Test
  fun mergingTwiceDoesNotDuplicate() {
    val once = repo.withRequiredTools(repo.getDefaultEditorTools())
    assertEquals(once, repo.withRequiredTools(once))
  }

  @Test
  fun everyEditorTabWithAPanelHasAToolMapping() {
    val actionKeys = listOf(
      "TOOL_COLOR_GRADE", "TOOL_VFX_STACK", "TOOL_AR_EFFECTS", "TOOL_ADVANCED_ANIMATION", "TOOL_FACE_EFFECTS",
      "TOOL_BACKGROUND", "TOOL_AI_EFFECTS", "TOOL_OVERLAYS", "TOOL_KEYFRAME", "TOOL_CHROMA", "TOOL_CAPTIONS",
      "TOOL_AUDIO_MUSIC", "TOOL_STICKERS", "TOOL_SHAPES", "TOOL_TRANSITIONS", "TOOL_MASKS"
    )
    for (key in actionKeys) {
      assertTrue(key, EditorToolItem("x", "x", key, "general", 0).mappedTab != null)
    }
  }

  @Test
  fun defaultToolbarIncludesOverlayPanel() {
    val overlay = repo.getDefaultEditorTools().first { it.actionKey == "TOOL_OVERLAYS" }
    assertEquals(EditorToolbarTab.OVERLAY, overlay.mappedTab)
  }

  private fun tool(key: String, name: String = key, category: String = "general", order: Int = 0, active: Boolean = true, id: String = "id_$key") =
    EditorToolItem(id, name, key, category, order, active)

  @Test
  fun everyToolbarTabIsReachableFromAnActionKey() {
    val keys = listOf(
      "TOOL_MEDIA", "TOOL_OVERLAYS", "TOOL_EDIT", "TOOL_TRIM", "TOOL_AUDIO_MUSIC", "TOOL_VOLUME", "TOOL_TEXT",
      "TOOL_SHAPES", "TOOL_STICKERS", "TOOL_EFFECTS", "TOOL_FILTERS", "TOOL_TRANSITIONS", "TOOL_ADJUST",
      "TOOL_SPEED", "TOOL_COLOR_GRADE", "TOOL_VFX_STACK", "TOOL_CHROMA", "TOOL_AI_TOOLS", "TOOL_CANVAS",
      "TOOL_KEYFRAME", "TOOL_CAPTIONS", "TOOL_BACKGROUND", "TOOL_VIDEO_QUALITY", "TOOL_ANIMATIONS", "TOOL_MASKS",
      "TOOL_AI_EFFECTS", "TOOL_TRENDING_PACKS", "TOOL_MOTION_TRACKING", "TOOL_ADVANCED_ANIMATION",
      "TOOL_AR_EFFECTS", "TOOL_SMART"
    )
    val reached = keys.mapNotNull { tool(it).mappedTab }.toSet()
    assertEquals(EditorToolbarTab.values().toSet(), reached)
  }

  @Test
  fun everyPanelToolHasADedicatedIcon() {
    val fallback = tool("TOOL_UNKNOWN_KEY", category = "nothing").icon
    listOf("TOOL_BACKGROUND", "TOOL_VIDEO_QUALITY", "TOOL_SMART", "TOOL_RETOUCH", "TOOL_ADVANCED_ANIMATIONS")
      .forEach { assertTrue(it, tool(it, category = "nothing").icon != fallback) }
  }

  @Test
  fun routingUsesActionKeyBeforeName() {
    assertEquals(EditorToolRoute.TextMenu, tool("TOOL_TEXT", "Text").resolveRoute())
    assertEquals(EditorToolRoute.Panel(EditorToolbarTab.FILTERS), tool("TOOL_FILTERS", "Text Filters").resolveRoute())
    assertEquals(EditorToolRoute.Panel(EditorToolbarTab.FILTERS), tool("TOOL_FILTERS", "Effects").resolveRoute())
    assertEquals(EditorToolRoute.EffectsMenu, tool("TOOL_EFFECTS", "Effects").resolveRoute())
    assertEquals(EditorToolRoute.Panel(EditorToolbarTab.AR_EFFECTS), tool("TOOL_AR_EFFECTS", "AR Effects").resolveRoute())
    assertEquals(EditorToolRoute.EditMenu, tool("TOOL_EDIT").resolveRoute())
    assertEquals(EditorToolRoute.Export, tool("TOOL_EXPORT_PRESETS", "Export").resolveRoute())
    assertEquals(EditorToolRoute.Panel(EditorToolbarTab.ASSET_STORE), tool("TOOL_VIDEO_TEMPLATES").resolveRoute())
    assertEquals(EditorToolRoute.Panel(EditorToolbarTab.SMART), tool("TOOL_SMART").resolveRoute())
  }

  @Test
  fun routingFallsBackToNameIdAndCategoryForRemoteTools() {
    assertEquals(EditorToolRoute.TextMenu, tool("", "Text", id = "tool_text").resolveRoute())
    assertEquals(EditorToolRoute.EffectsMenu, tool("CUSTOM", "Effects").resolveRoute())
    assertEquals(EditorToolRoute.Panel(EditorToolbarTab.OVERLAY), tool("CUSTOM", "Overlay").resolveRoute())
    assertEquals(EditorToolRoute.Panel(EditorToolbarTab.AUDIO), tool("CUSTOM", "Sounds", category = "audio").resolveRoute())
    assertEquals(EditorToolRoute.None, tool("CUSTOM", "Mystery", category = "other").resolveRoute())
  }

  @Test
  fun hiddenToolsAreNotNavigable() {
    assertTrue(tool("TOOL_AI_TOOLS", "AI Suite").isHiddenFromNavigation)
    assertTrue(tool("tool_ai_tools".uppercase(), "x").isHiddenFromNavigation)
    assertTrue(tool("TOOL_EMOJIS").isHiddenFromNavigation)
    assertTrue(tool("X", "Asset Packs").isHiddenFromNavigation)
    assertTrue(!tool("TOOL_TEXT", "Text").isHiddenFromNavigation)
  }

  @Test
  fun navigationToolsDedupesSortsAndDropsInactive() {
    val list = listOf(
      tool("TOOL_FILTERS", "Filters", order = 5),
      tool("TOOL_LUT_PRESETS", "LUTs", order = 6),
      tool("TOOL_TEXT", "Text", order = 1),
      tool("TOOL_STICKERS", "Stickers", order = 3, active = false),
      tool("TOOL_AI_TOOLS", "AI Suite", order = 2),
      tool("TOOL_EFFECTS", "Fx", order = 4),
      tool("", "Custom A", category = "unknown", order = 7),
      tool("", "Custom B", category = "unknown", order = 8)
    )
    assertEquals(listOf("Text", "Fx", "Filters", "Custom A", "Custom B"), list.navigationTools().map { it.name })
  }

  @Test
  fun defaultToolbarHasNoDuplicatePanelsAndExportIsPresent() {
    val nav = repo.getDefaultEditorTools().navigationTools()
    val tabs = nav.mapNotNull { it.mappedTab }
    assertEquals(tabs.size, tabs.toSet().size)
    assertTrue(nav.any { it.resolveRoute() == EditorToolRoute.Export })
  }
}
