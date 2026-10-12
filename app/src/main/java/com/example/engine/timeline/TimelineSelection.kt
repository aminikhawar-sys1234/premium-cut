package com.example.engine.timeline

import com.example.domain.model.Timeline
import com.example.engine.SelectedTrackElement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

fun Timeline.trackElementFor(clipId: String): SelectedTrackElement = when {
  videoClips.any { it.id == clipId } -> SelectedTrackElement.Video(clipId)
  overlayClips.any { it.id == clipId } -> SelectedTrackElement.Overlay(clipId)
  audioClips.any { it.id == clipId } -> SelectedTrackElement.Audio(clipId)
  textClips.any { it.id == clipId } -> SelectedTrackElement.Text(clipId)
  stickerClips.any { it.id == clipId } -> SelectedTrackElement.Sticker(clipId)
  effectClips.any { it.id == clipId } -> SelectedTrackElement.Effect(clipId)
  else -> SelectedTrackElement.None
}

val SelectedTrackElement.clipIdOrNull: String?
  get() = when (this) {
    is SelectedTrackElement.Video -> clipId
    is SelectedTrackElement.Overlay -> clipId
    is SelectedTrackElement.Audio -> clipId
    is SelectedTrackElement.Text -> clipId
    is SelectedTrackElement.Sticker -> clipId
    is SelectedTrackElement.Effect -> clipId
    SelectedTrackElement.None -> null
  }

/**
 * Clip selection state: the focused element, the selected id set and multi-select mode.
 * Not thread-safe on its own; [com.example.engine.TimelineEngine] calls it under its state lock.
 */
class TimelineSelection(private val timeline: () -> Timeline) {

  internal val selectedElementState = MutableStateFlow<SelectedTrackElement>(SelectedTrackElement.None)
  val selectedElement: StateFlow<SelectedTrackElement> = selectedElementState.asStateFlow()

  internal val selectedClipIdsState = MutableStateFlow<Set<String>>(emptySet())
  val selectedClipIds: StateFlow<Set<String>> = selectedClipIdsState.asStateFlow()

  internal val multiSelectModeState = MutableStateFlow(false)
  val isMultiSelectMode: StateFlow<Boolean> = multiSelectModeState.asStateFlow()

  private fun elementFor(clipId: String) = timeline().trackElementFor(clipId)

  private fun focus(clipId: String?) {
    selectedElementState.value = clipId?.let(::elementFor) ?: SelectedTrackElement.None
  }

  fun select(element: SelectedTrackElement) {
    selectedElementState.value = element
    selectedClipIdsState.value = element.clipIdOrNull?.let { setOf(it) } ?: emptySet()
  }

  fun selectClip(clipId: String) = select(elementFor(clipId))

  /** Replaces the selection; enters multi-select mode when more than one clip is chosen. */
  fun selectMultiple(clipIds: Set<String>) {
    selectedClipIdsState.value = clipIds
    multiSelectModeState.value = clipIds.size > 1
    focus(clipIds.firstOrNull())
  }

  /** Replaces the selection without touching multi-select mode. */
  fun replace(clipIds: Set<String>) {
    selectedClipIdsState.value = clipIds
    focus(clipIds.firstOrNull())
  }

  fun add(clipId: String) {
    val updated = selectedClipIdsState.value + clipId
    selectedClipIdsState.value = updated
    multiSelectModeState.value = updated.size > 1
    selectedElementState.value = elementFor(clipId)
  }

  fun remove(clipId: String) {
    val updated = selectedClipIdsState.value - clipId
    selectedClipIdsState.value = updated
    multiSelectModeState.value = updated.size > 1
    focus(updated.lastOrNull())
  }

  fun isSelected(clipId: String): Boolean =
    selectedClipIdsState.value.contains(clipId) || selectedElementState.value.clipIdOrNull == clipId

  /** Leaving multi-select keeps only the first selected clip. */
  fun toggleMultiSelectMode() {
    val newMode = !multiSelectModeState.value
    multiSelectModeState.value = newMode
    if (!newMode) {
      val first = selectedClipIdsState.value.firstOrNull()
      selectedClipIdsState.value = first?.let { setOf(it) } ?: emptySet()
      focus(first)
    }
  }

  /**
   * In multi-select mode toggles [clipId] in the set. Otherwise tapping a second clip while one
   * is selected promotes the selection to multi-select; tapping the selected clip keeps it alone.
   */
  fun toggle(clipId: String, trackElement: SelectedTrackElement? = null) {
    val element = trackElement ?: elementFor(clipId)
    if (multiSelectModeState.value) {
      val current = selectedClipIdsState.value.toMutableSet()
      if (!current.remove(clipId)) current.add(clipId)
      selectedClipIdsState.value = current
      if (current.isNotEmpty()) {
        focus(current.last())
      } else {
        selectedElementState.value = SelectedTrackElement.None
        multiSelectModeState.value = false
      }
    } else if (selectedClipIdsState.value.isNotEmpty() && !selectedClipIdsState.value.contains(clipId)) {
      selectedClipIdsState.value = selectedClipIdsState.value + clipId
      multiSelectModeState.value = true
      selectedElementState.value = element
    } else {
      selectedClipIdsState.value = setOf(clipId)
      selectedElementState.value = element
    }
  }

  fun selectAll() {
    val tl = timeline()
    val allIds = buildSet {
      tl.videoClips.mapTo(this) { it.id }
      tl.overlayClips.mapTo(this) { it.id }
      tl.textClips.mapTo(this) { it.id }
      tl.audioClips.mapTo(this) { it.id }
      tl.stickerClips.mapTo(this) { it.id }
      tl.effectClips.mapTo(this) { it.id }
    }
    selectedClipIdsState.value = allIds
    multiSelectModeState.value = allIds.size > 1
    allIds.firstOrNull()?.let { selectedElementState.value = elementFor(it) }
  }

  fun clear() {
    selectedClipIdsState.value = emptySet()
    selectedElementState.value = SelectedTrackElement.None
    multiSelectModeState.value = false
  }

  /** Drops ids that no longer exist, e.g. after undo/redo removed the selected clip. */
  fun pruneMissing() {
    val focused = selectedElementState.value.clipIdOrNull
    if (focused != null && elementFor(focused) == SelectedTrackElement.None) {
      selectedElementState.value = SelectedTrackElement.None
    }
    val current = selectedClipIdsState.value
    if (current.isNotEmpty()) {
      val valid = current.filterTo(LinkedHashSet()) { elementFor(it) != SelectedTrackElement.None }
      if (valid != current) selectedClipIdsState.value = valid
    }
  }
}
