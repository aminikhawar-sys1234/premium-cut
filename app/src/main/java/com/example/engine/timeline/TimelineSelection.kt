package com.example.engine.timeline

import com.example.domain.model.Timeline
import com.example.engine.SelectedTrackElement

/**
 * Pure selection math. [com.example.engine.TimelineEngine] owns the StateFlows and lock;
 * this object never mutates engine state.
 */
object TimelineSelection {

  data class Snapshot(
    val element: SelectedTrackElement = SelectedTrackElement.None,
    val clipIds: Set<String> = emptySet(),
    val multiSelect: Boolean = false
  )

  fun findTrackElementForClip(timeline: Timeline, clipId: String): SelectedTrackElement {
    if (timeline.videoClips.any { it.id == clipId }) return SelectedTrackElement.Video(clipId)
    if (timeline.overlayClips.any { it.id == clipId }) return SelectedTrackElement.Overlay(clipId)
    if (timeline.audioClips.any { it.id == clipId }) return SelectedTrackElement.Audio(clipId)
    if (timeline.textClips.any { it.id == clipId }) return SelectedTrackElement.Text(clipId)
    if (timeline.stickerClips.any { it.id == clipId }) return SelectedTrackElement.Sticker(clipId)
    if (timeline.effectClips.any { it.id == clipId }) return SelectedTrackElement.Effect(clipId)
    return SelectedTrackElement.None
  }

  fun clipExists(timeline: Timeline, clipId: String): Boolean =
    findTrackElementForClip(timeline, clipId) != SelectedTrackElement.None

  fun allClipIds(timeline: Timeline): Set<String> = buildSet {
    addAll(timeline.videoClips.map { it.id })
    addAll(timeline.overlayClips.map { it.id })
    addAll(timeline.textClips.map { it.id })
    addAll(timeline.audioClips.map { it.id })
    addAll(timeline.stickerClips.map { it.id })
    addAll(timeline.effectClips.map { it.id })
    addAll(timeline.shapeClips.map { it.id })
  }

  fun selectElement(element: SelectedTrackElement): Snapshot {
    val clipId = element.clipIdOrNull
    return if (clipId == null) Snapshot() else Snapshot(element, setOf(clipId), false)
  }

  fun selectMultiple(timeline: Timeline, clipIds: Set<String>): Snapshot {
    val first = clipIds.firstOrNull()
    return if (first == null) {
      Snapshot()
    } else {
      Snapshot(
        element = findTrackElementForClip(timeline, first),
        clipIds = clipIds,
        multiSelect = clipIds.size > 1
      )
    }
  }

  fun addClip(timeline: Timeline, current: Snapshot, clipId: String): Snapshot {
    val updated = current.clipIds + clipId
    return Snapshot(
      element = findTrackElementForClip(timeline, clipId),
      clipIds = updated,
      multiSelect = updated.size > 1
    )
  }

  fun removeClip(timeline: Timeline, current: Snapshot, clipId: String): Snapshot {
    val updated = current.clipIds - clipId
    return if (updated.isEmpty()) {
      Snapshot()
    } else {
      Snapshot(
        element = findTrackElementForClip(timeline, updated.last()),
        clipIds = updated,
        multiSelect = updated.size > 1
      )
    }
  }

  fun isClipSelected(current: Snapshot, clipId: String): Boolean {
    if (current.clipIds.contains(clipId)) return true
    return current.element.clipIdOrNull == clipId
  }

  fun toggleMultiSelect(timeline: Timeline, current: Snapshot): Snapshot {
    if (current.multiSelect) {
      val first = current.clipIds.firstOrNull()
      return if (first == null) Snapshot() else Snapshot(
        element = findTrackElementForClip(timeline, first),
        clipIds = setOf(first),
        multiSelect = false
      )
    }
    return current.copy(multiSelect = true)
  }

  fun toggleSelectClip(
    timeline: Timeline,
    current: Snapshot,
    clipId: String,
    trackElement: SelectedTrackElement? = null
  ): Snapshot {
    val element = trackElement ?: findTrackElementForClip(timeline, clipId)
    if (current.multiSelect) {
      val next = current.clipIds.toMutableSet()
      if (!next.add(clipId)) next.remove(clipId)
      return if (next.isEmpty()) {
        Snapshot()
      } else {
        Snapshot(
          element = findTrackElementForClip(timeline, next.last()),
          clipIds = next,
          multiSelect = true
        )
      }
    }
    return if (current.clipIds.isNotEmpty() && !current.clipIds.contains(clipId)) {
      Snapshot(element = element, clipIds = current.clipIds + clipId, multiSelect = true)
    } else {
      Snapshot(element = element, clipIds = setOf(clipId), multiSelect = false)
    }
  }

  fun selectAll(timeline: Timeline): Snapshot {
    val allIds = allClipIds(timeline)
    val first = allIds.firstOrNull()
    return if (first == null) Snapshot() else Snapshot(
      element = findTrackElementForClip(timeline, first),
      clipIds = allIds,
      multiSelect = allIds.size > 1
    )
  }

  fun pruneAfterHistoryChange(timeline: Timeline, current: Snapshot): Snapshot {
    val clipId = current.element.clipIdOrNull
    val element = if (clipId != null && !clipExists(timeline, clipId)) {
      SelectedTrackElement.None
    } else {
      current.element
    }
    val validIds = current.clipIds.filter { clipExists(timeline, it) }.toSet()
    return Snapshot(
      element = if (element == SelectedTrackElement.None && validIds.isNotEmpty()) {
        findTrackElementForClip(timeline, validIds.last())
      } else element,
      clipIds = validIds,
      multiSelect = validIds.size > 1
    )
  }
}
