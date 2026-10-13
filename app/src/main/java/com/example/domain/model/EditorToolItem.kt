package com.example.domain.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.ui.EditorToolbarTab
import com.example.ui.components.navigation.NavItemColorTheme
import com.example.ui.components.navigation.NavItemThemes

data class EditorToolItem(
  val id: String,
  val name: String,
  val actionKey: String,
  val category: String,
  val order: Int,
  val isActive: Boolean = true
) {
  val mappedTab: EditorToolbarTab?
    get() = when (actionKey.uppercase()) {
      "TOOL_EFFECTS" -> EditorToolbarTab.EFFECTS
      "TOOL_FILTERS", "TOOL_LUT_PRESETS" -> EditorToolbarTab.FILTERS
      "TOOL_ANIMATIONS", "TOOL_MOTION_PRESETS" -> EditorToolbarTab.ANIMATIONS
      "TOOL_ADVANCED_ANIMATION", "TOOL_ADVANCED_ANIMATIONS" -> EditorToolbarTab.ADVANCED_ANIMATION
      "TOOL_COLOR_GRADE" -> EditorToolbarTab.COLOR_GRADE
      "TOOL_VFX_STACK" -> EditorToolbarTab.VFX_STACK
      "TOOL_AR_EFFECTS" -> EditorToolbarTab.AR_EFFECTS
      "TOOL_MOTION_TRACKING" -> EditorToolbarTab.MOTION_TRACKING
      "TOOL_TRANSITIONS" -> EditorToolbarTab.TRANSITIONS
      "TOOL_TEXT" -> EditorToolbarTab.TEXT
      "TOOL_AUDIO_MUSIC", "TOOL_SFX", "TOOL_VOICE_TTS" -> EditorToolbarTab.AUDIO
      "TOOL_STICKERS", "TOOL_EMOJIS" -> EditorToolbarTab.STICKERS
      "TOOL_OVERLAYS" -> EditorToolbarTab.OVERLAY
      "TOOL_MASKS", "TOOL_BLEND_PRESETS" -> EditorToolbarTab.MASK
      "TOOL_FRAMES" -> EditorToolbarTab.CANVAS
      "TOOL_CANVAS" -> EditorToolbarTab.CANVAS
      "TOOL_BACKGROUNDS", "TOOL_BACKGROUND" -> EditorToolbarTab.BACKGROUND
      "TOOL_CHROMA_PRESETS", "TOOL_CHROMA" -> EditorToolbarTab.CHROMA
      "TOOL_SHAPES" -> EditorToolbarTab.ELEMENTS
      "TOOL_CAPTIONS" -> EditorToolbarTab.CAPTIONS
      "TOOL_AI_TOOLS" -> EditorToolbarTab.AI
      "TOOL_AI_EFFECTS" -> EditorToolbarTab.AI_MATTING
      "TOOL_FACE_EFFECTS", "TOOL_BEAUTY_RETOUCH", "TOOL_RETOUCH" -> EditorToolbarTab.AR_EFFECTS
      "TOOL_COLOR_PRESETS", "TOOL_ADJUST" -> EditorToolbarTab.ADJUST
      "TOOL_VIDEO_QUALITY" -> EditorToolbarTab.VIDEO_QUALITY
      "TOOL_KEYFRAME_PRESETS", "TOOL_KEYFRAME" -> EditorToolbarTab.KEYFRAME
      "TOOL_SPEED" -> EditorToolbarTab.SPEED
      "TOOL_TRIM" -> EditorToolbarTab.TRIM
      "TOOL_EDIT" -> EditorToolbarTab.EDIT
      "TOOL_VOLUME" -> EditorToolbarTab.VOLUME
      "TOOL_MEDIA" -> EditorToolbarTab.MEDIA
      "TOOL_SMART", "TOOL_SMART_TOOLS" -> EditorToolbarTab.SMART
      "TOOL_TRENDING_PACKS", "TOOL_EFFECT_PACKS", "TOOL_ASSET_PACKS", "TOOL_VIDEO_TEMPLATES" -> EditorToolbarTab.ASSET_STORE
      else -> when (category.lowercase()) {
        "effects" -> EditorToolbarTab.EFFECTS
        "filters", "color" -> EditorToolbarTab.FILTERS
        "animation" -> EditorToolbarTab.ANIMATIONS
        "transitions" -> EditorToolbarTab.TRANSITIONS
        "text" -> EditorToolbarTab.TEXT
        "audio" -> EditorToolbarTab.AUDIO
        "stickers" -> EditorToolbarTab.STICKERS
        "layers" -> EditorToolbarTab.OVERLAY
        "canvas" -> EditorToolbarTab.CANVAS
        "cutout" -> EditorToolbarTab.CHROMA
        "graphics" -> EditorToolbarTab.ELEMENTS
        "ai" -> EditorToolbarTab.AI
        "adjust" -> EditorToolbarTab.ADJUST
        "packs", "templates" -> EditorToolbarTab.ASSET_STORE
        else -> null
      }
    }

  val icon: ImageVector
    get() = when (actionKey.uppercase()) {
      "TOOL_EFFECTS" -> Icons.Default.AutoAwesome
      "TOOL_FILTERS" -> Icons.Default.ColorLens
      "TOOL_ANIMATIONS" -> Icons.Default.Animation
      "TOOL_ADVANCED_ANIMATION", "TOOL_ADVANCED_ANIMATIONS" -> Icons.Default.AutoFixHigh
      "TOOL_COLOR_GRADE" -> Icons.Default.Palette
      "TOOL_VFX_STACK" -> Icons.Default.Layers
      "TOOL_AR_EFFECTS" -> Icons.Default.Face
      "TOOL_FACE_EFFECTS" -> Icons.Default.FaceRetouchingNatural
      "TOOL_AI_EFFECTS" -> Icons.Default.PersonRemove
      "TOOL_MOTION_TRACKING" -> Icons.Default.TrackChanges
      "TOOL_TRANSITIONS" -> Icons.Default.Transform
      "TOOL_TEXT" -> Icons.Default.Edit
      "TOOL_AUDIO_MUSIC" -> Icons.Default.MusicNote
      "TOOL_SFX" -> Icons.Default.GraphicEq
      "TOOL_VOICE_TTS" -> Icons.Default.Mic
      "TOOL_VIDEO_TEMPLATES" -> Icons.Default.DashboardCustomize
      "TOOL_STICKERS" -> Icons.Default.EmojiEmotions
      "TOOL_EMOJIS" -> Icons.Default.Mood
      "TOOL_OVERLAYS" -> Icons.Default.Layers
      "TOOL_MASKS" -> Icons.Default.LayersClear
      "TOOL_FRAMES" -> Icons.Default.CropSquare
      "TOOL_BACKGROUNDS" -> Icons.Default.Texture
      "TOOL_LUT_PRESETS" -> Icons.Default.Palette
      "TOOL_CHROMA_PRESETS", "TOOL_CHROMA" -> Icons.Default.FilterFrames
      "TOOL_BLEND_PRESETS" -> Icons.Default.Blender
      "TOOL_SHAPES" -> Icons.Default.Category
      "TOOL_CAPTIONS" -> Icons.Default.Subtitles
      "TOOL_AI_TOOLS" -> Icons.Default.AutoAwesome
      "TOOL_BEAUTY_RETOUCH" -> Icons.Default.FaceRetouchingNatural
      "TOOL_MOTION_PRESETS" -> Icons.Default.SlowMotionVideo
      "TOOL_KEYFRAME_PRESETS", "TOOL_KEYFRAME" -> Icons.Default.Diamond
      "TOOL_COLOR_PRESETS" -> Icons.Default.Tune
      "TOOL_EXPORT_PRESETS" -> Icons.Default.FileDownload
      "TOOL_TRENDING_PACKS" -> Icons.Default.LocalFireDepartment
      "TOOL_ASSET_PACKS" -> Icons.Default.Download
      "TOOL_EDIT" -> Icons.Default.Edit
      "TOOL_TRIM" -> Icons.Default.ContentCut
      "TOOL_SPEED" -> Icons.Default.Speed
      "TOOL_VOLUME" -> Icons.Default.VolumeUp
      "TOOL_MEDIA" -> Icons.Default.VideoLibrary
      "TOOL_BACKGROUND" -> Icons.Default.Texture
      "TOOL_ADVANCED_ANIMATIONS" -> Icons.Default.AutoFixHigh
      "TOOL_RETOUCH" -> Icons.Default.FaceRetouchingNatural
      "TOOL_VIDEO_QUALITY" -> Icons.Default.HighQuality
      "TOOL_CANVAS" -> Icons.Default.AspectRatio
      "TOOL_SMART", "TOOL_SMART_TOOLS" -> Icons.Default.GraphicEq
      "TOOL_ADJUST" -> Icons.Default.Tune
      else -> when (category.lowercase()) {
        "effects" -> Icons.Default.AutoAwesome
        "filters" -> Icons.Default.ColorLens
        "animation" -> Icons.Default.Animation
        "transitions" -> Icons.Default.Transform
        "text" -> Icons.Default.TextFields
        "audio" -> Icons.Default.MusicNote
        "stickers" -> Icons.Default.EmojiEmotions
        "layers" -> Icons.Default.Layers
        "canvas" -> Icons.Default.CropSquare
        "cutout" -> Icons.Default.FilterFrames
        "graphics" -> Icons.Default.Category
        "ai" -> Icons.Default.AutoAwesome
        "adjust", "color" -> Icons.Default.Tune
        "packs", "templates" -> Icons.Default.Download
        else -> Icons.Default.Build
      }
    }

  val theme: NavItemColorTheme
    get() = when (category.lowercase()) {
      "effects" -> NavItemThemes.Effects
      "filters", "color" -> NavItemThemes.Filters
      "animation" -> NavItemThemes.Animations
      "transitions" -> NavItemThemes.Transitions
      "text" -> if (actionKey.contains("TEMPLATE", ignoreCase = true)) NavItemThemes.TextTemplate else NavItemThemes.AddText
      "audio" -> NavItemThemes.Audio
      "stickers" -> NavItemThemes.Stickers
      "layers" -> NavItemThemes.Overlay
      "ai" -> NavItemThemes.AI
      "graphics" -> NavItemThemes.Elements
      else -> NavItemThemes.DefaultSlate
    }
}

/** Where tapping a toolbar tool leads. Resolved once so the toolbar, "More" dialog and tests agree. */
sealed interface EditorToolRoute {
  data object Split : EditorToolRoute
  data object Export : EditorToolRoute
  data object EffectsMenu : EditorToolRoute
  data object TextMenu : EditorToolRoute
  data object EditMenu : EditorToolRoute
  data class Panel(val tab: EditorToolbarTab) : EditorToolRoute
  data object None : EditorToolRoute
}

/**
 * Tools that must not appear in the navigation bar / "More" list. AI Suite is reached from Edit Tools,
 * asset packs from the Asset Store tool and emojis from Stickers.
 */
val EditorToolItem.isHiddenFromNavigation: Boolean
  get() = id == "tool_asset_packs" || id == "tool_ai_tools" || id == "tool_emojis" ||
    actionKey.trim().uppercase().let { it == "TOOL_ASSET_PACKS" || it == "TOOL_AI_TOOLS" || it == "TOOL_EMOJIS" } ||
    name.contains("Asset Pack", ignoreCase = true) ||
    name.contains("Assets Pack", ignoreCase = true) ||
    name.trim().equals("AI Tools", ignoreCase = true) ||
    name.trim().equals("Emojis", ignoreCase = true)

/**
 * The action key is authoritative. Names and ids are only a fallback for remote tools that carry no
 * usable action key, so a tool such as "Text Filters" is never hijacked by the Text menu.
 */
fun EditorToolItem.resolveRoute(): EditorToolRoute {
  val action = actionKey.trim().uppercase()
  return when {
    action.contains("SPLIT") -> EditorToolRoute.Split
    action == "TOOL_EXPORT_PRESETS" -> EditorToolRoute.Export
    action == "TOOL_EFFECTS" -> EditorToolRoute.EffectsMenu
    action == "TOOL_TEXT" -> EditorToolRoute.TextMenu
    action == "TOOL_EDIT" -> EditorToolRoute.EditMenu
    action == "TOOL_OVERLAYS" -> EditorToolRoute.Panel(EditorToolbarTab.OVERLAY)
    action.isNotBlank() && hasKeyedPanel(action) -> EditorToolRoute.Panel(mappedTab!!)
    id.equals("tool_overlays", true) || name.trim().equals("Overlay", true) || name.trim().equals("Overlays", true) ->
      EditorToolRoute.Panel(EditorToolbarTab.OVERLAY)
    id.equals("tool_effects", true) || name.trim().equals("Effects", true) -> EditorToolRoute.EffectsMenu
    id.equals("tool_text", true) || name.trim().equals("Text", true) -> EditorToolRoute.TextMenu
    else -> mappedTab?.let { EditorToolRoute.Panel(it) } ?: EditorToolRoute.None
  }
}

private fun EditorToolItem.hasKeyedPanel(upperAction: String): Boolean =
  mappedTab != null && copy(category = "").mappedTab != null && upperAction.startsWith("TOOL_")

/**
 * Active, visible tools in display order with duplicates removed (same target panel, same action key
 * or same label). Shared by the bottom toolbar and the "More" dialog.
 */
fun List<EditorToolItem>.navigationTools(): List<EditorToolItem> {
  val seenTabs = mutableSetOf<EditorToolbarTab>()
  val seenActions = mutableSetOf<String>()
  val seenNames = mutableSetOf<String>()
  return filter { it.isActive && !it.isHiddenFromNavigation }
    .sortedBy { it.order }
    .filter { tool ->
      val nameNorm = tool.name.lowercase().trim()
      val action = tool.actionKey.uppercase().trim()
      val tab = tool.mappedTab
      when {
        nameNorm in seenNames -> false
        tab != null -> seenTabs.add(tab)
        action.isNotBlank() -> seenActions.add(action)
        else -> true
      }.also { keep -> if (keep) seenNames.add(nameNorm) }
    }
}
