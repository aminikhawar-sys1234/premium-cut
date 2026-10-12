package com.example.engine.timeline

import com.example.domain.model.Timeline
import com.example.domain.model.TrackHeight
import com.example.domain.model.TrackSettings
import com.example.domain.model.TrackType

/**
 * Pure track-flag mutations. History / locking of the engine session stays in TimelineEngine.
 */
object TimelineTrackCommands {

  fun isLocked(timeline: Timeline, trackType: TrackType): Boolean =
    timeline.trackSettings[trackType]?.isLocked == true

  fun withSettings(
    timeline: Timeline,
    trackType: TrackType,
    update: (TrackSettings) -> TrackSettings
  ): Timeline {
    val settings = timeline.trackSettings.toMutableMap()
    val cur = settings[trackType] ?: TrackSettings(trackType)
    settings[trackType] = update(cur)
    return timeline.copy(trackSettings = settings)
  }

  fun setLocked(timeline: Timeline, trackType: TrackType, locked: Boolean): Timeline =
    withSettings(timeline, trackType) { it.copy(isLocked = locked) }

  fun setHidden(timeline: Timeline, trackType: TrackType, hidden: Boolean): Timeline =
    withSettings(timeline, trackType) { it.copy(isHidden = hidden) }

  fun setMuted(timeline: Timeline, trackType: TrackType, muted: Boolean): Timeline =
    withSettings(timeline, trackType) { it.copy(isMuted = muted) }

  fun setSolo(timeline: Timeline, trackType: TrackType, solo: Boolean): Timeline =
    withSettings(timeline, trackType) { it.copy(isSolo = solo) }

  fun setHeight(timeline: Timeline, trackType: TrackType, height: TrackHeight): Timeline =
    withSettings(timeline, trackType) { it.copy(height = height) }

  fun toggleLock(timeline: Timeline, trackType: TrackType): Timeline =
    withSettings(timeline, trackType) { it.copy(isLocked = !it.isLocked) }

  fun toggleHide(timeline: Timeline, trackType: TrackType): Timeline =
    withSettings(timeline, trackType) { it.copy(isHidden = !it.isHidden) }

  fun toggleMute(timeline: Timeline, trackType: TrackType): Timeline =
    withSettings(timeline, trackType) { it.copy(isMuted = !it.isMuted) }

  fun toggleSolo(timeline: Timeline, trackType: TrackType): Timeline =
    withSettings(timeline, trackType) { it.copy(isSolo = !it.isSolo) }

  fun nextHeight(current: TrackHeight): TrackHeight = when (current) {
    TrackHeight.COMPACT -> TrackHeight.NORMAL
    TrackHeight.NORMAL -> TrackHeight.EXPANDED
    TrackHeight.EXPANDED -> TrackHeight.COMPACT
  }

  fun videoTrackIndices(timeline: Timeline): List<Int> {
    val indices = mutableSetOf(0)
    timeline.overlayClips.forEach { indices.add(it.trackIndex.coerceAtLeast(1)) }
    return indices.sorted()
  }

  fun audioTrackIndices(timeline: Timeline): List<Int> {
    val indices = mutableSetOf(0)
    timeline.audioClips.forEach { indices.add(it.trackIndex.coerceAtLeast(0)) }
    return indices.sorted()
  }

  fun textTrackCount(timeline: Timeline): Int {
    val indices = mutableSetOf(0)
    timeline.textClips.forEach { indices.add(it.trackIndex.coerceAtLeast(0)) }
    return indices.size
  }
}
