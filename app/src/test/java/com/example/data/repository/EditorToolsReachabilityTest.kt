package com.example.data.repository

import com.example.domain.model.EditorToolItem
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
}
