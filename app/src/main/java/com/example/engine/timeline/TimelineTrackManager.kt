package com.example.engine.timeline

import com.example.domain.model.*
import com.example.ui.components.timeline.ActiveTracksSnapshot
import com.example.ui.components.timeline.LaneClipItem
import com.example.ui.components.timeline.LaneKind
import com.example.ui.components.timeline.TimelineLane
import com.example.ui.components.timeline.TrackLaneManager
import java.util.UUID

/**
 * Principal NLE Multi-Track Manager.
 *
 * Responsibilities:
 * 1. Capture and enforce Authoritative CTI Playhead insertion time (never fallback to 0s or auto-end).
 * 2. Dynamic Track Generation & Unique Z-Index Allocation for multiple simultaneous layers.
 * 3. Track Auto-Assignment: Finds active compatible track or creates a new track dynamically.
 * 4. Locked track protection (skips locked tracks; never places content on locked tracks).
 * 5. Track-level state management (Lock, Visibility, Mute, Solo, Height, Collapse).
 */
object TimelineTrackManager {

    /**
     * Captures the authoritative CTI playhead position to be used as insertion start time.
     * Guaranteed to use the exact playhead timestamp without resetting to 0s or snapping to end.
     */
    fun getAuthoritativeInsertionTime(currentCtiMs: Long): Long {
        return currentCtiMs.coerceAtLeast(0L)
    }

    /**
     * Allocates a guaranteed unique Z-Index / TrackIndex for a newly created overlay.
     */
    fun allocateOverlayTrackIndex(timeline: Timeline): Int {
        val allTracks = timeline.tracks.ifEmpty { timeline.getEffectiveTracks() }
        val maxOrder = (allTracks.map { it.order } + allTracks.map { it.zOrder } + timeline.overlayClips.map { it.trackIndex }).maxOrNull() ?: 0
        return maxOrder + 1
    }

    /**
     * Allocates a guaranteed unique TrackIndex for a newly created text clip.
     */
    fun allocateTextTrackIndex(timeline: Timeline): Int {
        val allTracks = timeline.tracks.ifEmpty { timeline.getEffectiveTracks() }
        val maxOrder = (allTracks.map { it.order } + allTracks.map { it.zOrder } + timeline.textClips.map { it.trackIndex }).maxOrNull() ?: 0
        return maxOrder + 1
    }

    /**
     * Allocates a guaranteed unique TrackIndex for a newly created audio clip.
     */
    fun allocateAudioTrackIndex(timeline: Timeline): Int {
        val allTracks = timeline.tracks.ifEmpty { timeline.getEffectiveTracks() }
        val maxOrder = (allTracks.map { it.order } + allTracks.map { it.zOrder } + timeline.audioClips.map { it.trackIndex }).maxOrNull() ?: 0
        return maxOrder + 1
    }

    /**
     * Allocates a guaranteed unique TrackIndex for a newly created sticker / shape / emoji / element clip.
     */
    fun allocateStickerTrackIndex(timeline: Timeline): Int {
        val allTracks = timeline.tracks.ifEmpty { timeline.getEffectiveTracks() }
        val maxOrder = (allTracks.map { it.order } + allTracks.map { it.zOrder } + timeline.stickerClips.map { it.trackIndex }).maxOrNull() ?: 0
        return maxOrder + 1
    }

    /**
     * Allocates a guaranteed unique TrackIndex for a newly created effect / filter clip.
     */
    fun allocateEffectTrackIndex(timeline: Timeline): Int {
        val allTracks = timeline.tracks.ifEmpty { timeline.getEffectiveTracks() }
        val maxOrder = (allTracks.map { it.order } + allTracks.map { it.zOrder } + timeline.effectClips.map { it.trackIndex }).maxOrNull() ?: 0
        return maxOrder + 1
    }

    /**
     * Allocates a guaranteed unique TrackIndex for any TrackType.
     */
    fun allocateTrackIndex(timeline: Timeline, trackType: TrackType): Int {
        val allTracks = timeline.tracks.ifEmpty { timeline.getEffectiveTracks() }
        val maxOrder = (allTracks.map { it.order } + allTracks.map { it.zOrder }).maxOrNull() ?: 0
        return maxOrder + 1
    }

    /**
     * Appends a brand new track to the timeline track list (tracks + newTrack)
     * with a unique UUID and proper incremented order. Never overwrites existing tracks.
     */
    fun addTrack(
        timeline: Timeline,
        trackType: TrackType,
        displayName: String? = null
    ): Pair<Timeline, NleTrack> {
        // A track's lane index is the stacking order of its clips, so it must not collide with a
        // lane that already holds clips (or another explicit track) of the same type.
        val firstLane = when (trackType) {
            TrackType.OVERLAY, TrackType.ELEMENT, TrackType.ADJUSTMENT -> 1
            else -> 0
        }
        val lane = maxOf((lanesOf(timeline, trackType).maxOrNull() ?: (firstLane - 1)) + 1, firstLane)

        val newNleTrack = NleTrack(
            trackId = UUID.randomUUID().toString(),
            trackType = trackType,
            displayName = displayName ?: "${trackType.name.replace("_", " ")} ${lane + 1}",
            order = lane,
            zOrder = lane,
            isLocked = false,
            isVisible = true,
            isMuted = false,
            isSolo = false
        )
        return Pair(timeline.copy(tracks = timeline.tracks + newNleTrack), newNleTrack)
    }

    /**
     * Track Auto-Assignment:
     * Finds active compatible track for [trackType] at [requestedCtiMs].
     * If an existing compatible track is unlocked and available, returns its index.
     * If all compatible tracks are locked or occupied at that timestamp, dynamically creates
     * a new track and returns updated Timeline + newly allocated trackIndex.
     */
    fun findOrCreateTrackForClip(
        timeline: Timeline,
        trackType: TrackType,
        requestedCtiMs: Long,
        durationMs: Long = 3000L
    ): Pair<Timeline, Int> {
        val effectiveTracks = timeline.getEffectiveTracks()
        val compatibleTracks = effectiveTracks.filter { it.trackType == trackType }

        // Find an unlocked compatible track without collision at requestedCtiMs
        for (track in compatibleTracks) {
            if (track.isLocked) continue // Skip locked tracks

            val hasCollision = when (trackType) {
                TrackType.MAIN_VIDEO -> false // Main track uses insert/append/ripple
                TrackType.OVERLAY, TrackType.ELEMENT, TrackType.ADJUSTMENT -> {
                    timeline.overlayClips.any { it.trackIndex == track.zOrder &&
                        it.overlapsWith(requestedCtiMs, durationMs) }
                }
                TrackType.AUDIO, TrackType.MUSIC, TrackType.SFX -> {
                    timeline.audioClips.any { it.trackIndex == track.order &&
                        it.overlapsWith(requestedCtiMs, durationMs) }
                }
                TrackType.TEXT, TrackType.CAPTION -> {
                    timeline.textClips.any { it.trackIndex == track.order &&
                        it.overlapsWith(requestedCtiMs, durationMs) }
                }
                TrackType.EFFECT -> false
                TrackType.STICKER -> false
                TrackType.SHAPE -> false
            }

            if (!hasCollision) {
                return Pair(timeline, track.zOrder)
            }
        }

        // No free unlocked track found -> dynamically create a new track appended with unique UUID & incremented order
        val currentTracks = timeline.tracks
        val nextOrder = (currentTracks.maxOfOrNull { it.order } ?: -1) + 1
        val newTrackUuid = UUID.randomUUID().toString()
        val newNleTrack = NleTrack(
            trackId = newTrackUuid,
            trackType = trackType,
            displayName = "${trackType.name.replace("_", " ")} ${nextOrder + 1}",
            order = nextOrder,
            zOrder = nextOrder,
            isLocked = false,
            isVisible = true,
            isMuted = false,
            isSolo = false
        )
        val updatedTracks = currentTracks + newNleTrack
        return Pair(timeline.copy(tracks = updatedTracks), nextOrder)
    }

    /**
     * Checks if a track of a given type and index is locked.
     */
    fun isTrackLocked(timeline: Timeline, trackType: TrackType, trackIndex: Int = 0): Boolean {
        val directSettingLocked = timeline.trackSettings[trackType]?.isLocked == true
        if (directSettingLocked) return true

        val matchingTrack = timeline.tracks.firstOrNull { it.trackType == trackType && it.zOrder == trackIndex }
        return matchingTrack?.isLocked == true
    }

    /**
     * Sets locked state of a track by trackId.
     */
    fun setTrackLocked(timeline: Timeline, trackId: String, locked: Boolean): Timeline {
        val tracks = timeline.getEffectiveTracks().map { track ->
            if (track.trackId == trackId) track.copy(isLocked = locked) else track
        }
        val targetTrack = tracks.firstOrNull { it.trackId == trackId }
        val updatedSettings = if (targetTrack != null) {
            val curr = timeline.trackSettings[targetTrack.trackType] ?: TrackSettings(targetTrack.trackType)
            timeline.trackSettings + (targetTrack.trackType to curr.copy(isLocked = locked))
        } else timeline.trackSettings
        return timeline.copy(tracks = tracks, trackSettings = updatedSettings)
    }

    /**
     * Sets visibility state of a track by trackId.
     */
    fun setTrackVisible(timeline: Timeline, trackId: String, visible: Boolean): Timeline {
        val tracks = timeline.getEffectiveTracks().map { track ->
            if (track.trackId == trackId) track.copy(isVisible = visible) else track
        }
        val targetTrack = tracks.firstOrNull { it.trackId == trackId }
        val updatedSettings = if (targetTrack != null) {
            val curr = timeline.trackSettings[targetTrack.trackType] ?: TrackSettings(targetTrack.trackType)
            timeline.trackSettings + (targetTrack.trackType to curr.copy(isHidden = !visible))
        } else timeline.trackSettings
        return timeline.copy(tracks = tracks, trackSettings = updatedSettings)
    }

    /**
     * Sets muted state of an audio track by trackId.
     */
    fun setTrackMuted(timeline: Timeline, trackId: String, muted: Boolean): Timeline {
        val tracks = timeline.getEffectiveTracks().map { track ->
            if (track.trackId == trackId) track.copy(isMuted = muted) else track
        }
        val targetTrack = tracks.firstOrNull { it.trackId == trackId }
        val updatedSettings = if (targetTrack != null && targetTrack.trackType.isAudioTrack) {
            val curr = timeline.trackSettings[targetTrack.trackType] ?: TrackSettings(targetTrack.trackType)
            timeline.trackSettings + (targetTrack.trackType to curr.copy(isMuted = muted))
        } else timeline.trackSettings
        return timeline.copy(tracks = tracks, trackSettings = updatedSettings)
    }

    /**
     * Sets solo state of an audio track by trackId.
     */
    fun setTrackSolo(timeline: Timeline, trackId: String, solo: Boolean): Timeline {
        val tracks = timeline.getEffectiveTracks().map { track ->
            if (track.trackId == trackId) track.copy(isSolo = solo) else track
        }
        val targetTrack = tracks.firstOrNull { it.trackId == trackId }
        val updatedSettings = if (targetTrack != null && targetTrack.trackType.isAudioTrack) {
            val curr = timeline.trackSettings[targetTrack.trackType] ?: TrackSettings(targetTrack.trackType)
            timeline.trackSettings + (targetTrack.trackType to curr.copy(isSolo = solo))
        } else timeline.trackSettings
        return timeline.copy(tracks = tracks, trackSettings = updatedSettings)
    }

    /**
     * Sets track display height.
     */
    fun setTrackHeight(timeline: Timeline, trackId: String, height: TrackHeight): Timeline {
        val tracks = timeline.getEffectiveTracks().map { track ->
            if (track.trackId == trackId) track.copy(height = height) else track
        }
        return timeline.copy(tracks = tracks)
    }

    /**
     * Re-assigns strict unique sequential Z-Indices to all overlay clips.
     */
    fun normalizeOverlayZIndices(overlays: List<VideoClip>): List<VideoClip> {
        return overlays.mapIndexed { index, clip ->
            clip.copy(trackIndex = index + 1)
        }
    }

    /**
     * Generates all dynamic timeline lanes via TrackLaneManager.
     */
    fun generateDynamicLanes(timeline: Timeline): List<TimelineLane> {
        return TrackLaneManager.computeLanes(timeline)
    }

    /**
     * Evaluates all active tracks simultaneously at [currentCtiMs] without hardcoded limits.
     */
    fun evaluateActiveTracks(timeline: Timeline, currentCtiMs: Long): ActiveTracksSnapshot {
        return TrackLaneManager.evaluateTimelineAt(timeline, currentCtiMs)
    }

    /**
     * Moves an existing clip between tracks (e.g. from Lane 1 to Lane 2, or Main Video to Overlay).
     */
    fun moveClipToTrack(timeline: Timeline, clipId: String, targetTrackIndex: Int): Timeline {
        val targetIdx = targetTrackIndex.coerceAtLeast(0)

        // Case 1: Video clip moving to overlay
        val videoIndex = timeline.videoClips.indexOfFirst { it.id == clipId }
        if (videoIndex != -1 && targetIdx > 0) {
            val movingClip = timeline.videoClips[videoIndex]
            val updatedVideos = timeline.videoClips.filterIndexed { index, _ -> index != videoIndex }
            val updatedOverlays = timeline.overlayClips + movingClip.copy(trackIndex = targetIdx)
            return timeline.copy(videoClips = updatedVideos, overlayClips = updatedOverlays)
        }

        // Case 2: Overlay clip moving to main video or another overlay tier
        val overlayIndex = timeline.overlayClips.indexOfFirst { it.id == clipId }
        if (overlayIndex != -1) {
            val movingClip = timeline.overlayClips[overlayIndex]
            if (targetIdx == 0) {
                // Move overlay down to main video
                val updatedOverlays = timeline.overlayClips.filterIndexed { index, _ -> index != overlayIndex }
                val updatedVideos = (timeline.videoClips + movingClip.copy(trackIndex = 0)).sortedBy { it.timelineStartMs }
                return timeline.copy(videoClips = updatedVideos, overlayClips = updatedOverlays)
            } else {
                // Move between overlay tiers
                val updatedOverlays = timeline.overlayClips.map { clip ->
                    if (clip.id == clipId) clip.copy(trackIndex = targetIdx) else clip
                }
                return timeline.copy(overlayClips = updatedOverlays)
            }
        }

        // Case 3: Audio clip moving between audio lanes
        val audioIndex = timeline.audioClips.indexOfFirst { it.id == clipId }
        if (audioIndex != -1) {
            val updatedAudios = timeline.audioClips.map { clip ->
                if (clip.id == clipId) clip.copy(trackIndex = targetIdx) else clip
            }
            return timeline.copy(audioClips = updatedAudios)
        }

        // Case 4: Text clip moving between text lanes
        val textIndex = timeline.textClips.indexOfFirst { it.id == clipId }
        if (textIndex != -1) {
            val updatedTexts = timeline.textClips.map { clip ->
                if (clip.id == clipId) clip.copy(trackIndex = targetIdx) else clip
            }
            return timeline.copy(textClips = updatedTexts)
        }

        return timeline
    }

    /**
     * Preserves stable user-created track indices without squashing intermediate empty tracks.
     */
    fun compactTracks(timeline: Timeline): Timeline {
        return timeline
    }

    // ---- Lane (per-track) operations -------------------------------------------------------

    /** Clip lane the given track id refers to, or null when the id is unknown. Lane -1 means "every lane of that type". */
    fun resolveTrack(timeline: Timeline, trackId: String): Pair<TrackType, Int>? {
        timeline.tracks.firstOrNull { it.trackId == trackId }?.let { return it.trackType to it.zOrder }
        return when {
            trackId == "track_main_video" -> TrackType.MAIN_VIDEO to 0
            trackId.startsWith("track_overlay_") -> trackId.removePrefix("track_overlay_").toIntOrNull()?.let { TrackType.OVERLAY to it }
            trackId.startsWith("track_audio_") -> trackId.removePrefix("track_audio_").toIntOrNull()?.let { TrackType.AUDIO to it }
            trackId.startsWith("track_text_") -> trackId.removePrefix("track_text_").toIntOrNull()?.let { TrackType.TEXT to it }
            trackId == "track_effects" -> TrackType.EFFECT to -1
            trackId == "track_stickers" -> TrackType.STICKER to -1
            trackId == "track_shapes" -> TrackType.SHAPE to -1
            else -> null
        }
    }

    /** Ids of all clips on a lane (or on every lane of the type when [lane] is -1). */
    fun laneClipIds(timeline: Timeline, trackType: TrackType, lane: Int): Set<String> {
        fun matches(trackIndex: Int) = lane < 0 || trackIndex == lane
        return when (trackType) {
            TrackType.MAIN_VIDEO -> timeline.videoClips.map { it.id }.toSet()
            TrackType.OVERLAY, TrackType.ELEMENT, TrackType.ADJUSTMENT ->
                timeline.overlayClips.filter { matches(it.trackIndex) }.map { it.id }.toSet()
            TrackType.AUDIO, TrackType.MUSIC, TrackType.SFX ->
                timeline.audioClips.filter { matches(it.trackIndex) }.map { it.id }.toSet()
            TrackType.TEXT, TrackType.CAPTION ->
                timeline.textClips.filter { matches(it.trackIndex) }.map { it.id }.toSet()
            TrackType.STICKER -> timeline.stickerClips.filter { matches(it.trackIndex) }.map { it.id }.toSet()
            TrackType.EFFECT -> timeline.effectClips.filter { matches(it.trackIndex) }.map { it.id }.toSet()
            TrackType.SHAPE -> timeline.shapeClips.filter { matches(it.trackIndex) }.map { it.id }.toSet()
        }
    }

    /** Removes a lane together with every clip on it. The main video track can never be removed. */
    fun removeLane(timeline: Timeline, trackType: TrackType, lane: Int): Timeline? {
        if (trackType == TrackType.MAIN_VIDEO) return null
        val ids = laneClipIds(timeline, trackType, lane)
        val remainingTracks = timeline.tracks.filterNot { it.trackType == trackType && (lane < 0 || it.zOrder == lane) }
        if (ids.isEmpty() && remainingTracks.size == timeline.tracks.size) return null
        return timeline.copy(
            overlayClips = timeline.overlayClips.filterNot { it.id in ids },
            audioClips = timeline.audioClips.filterNot { it.id in ids },
            textClips = timeline.textClips.filterNot { it.id in ids },
            stickerClips = timeline.stickerClips.filterNot { it.id in ids },
            effectClips = timeline.effectClips.filterNot { it.id in ids },
            shapeClips = timeline.shapeClips.filterNot { it.id in ids },
            tracks = remainingTracks
        )
    }

    /** All lanes of a type that currently exist (have clips or an explicit track), ascending. */
    fun lanesOf(timeline: Timeline, trackType: TrackType): List<Int> {
        val fromClips = when (trackType) {
            TrackType.OVERLAY, TrackType.ELEMENT, TrackType.ADJUSTMENT -> timeline.overlayClips.map { it.trackIndex }
            TrackType.AUDIO, TrackType.MUSIC, TrackType.SFX -> timeline.audioClips.map { it.trackIndex }
            TrackType.TEXT, TrackType.CAPTION -> timeline.textClips.map { it.trackIndex }
            TrackType.STICKER -> timeline.stickerClips.map { it.trackIndex }
            TrackType.EFFECT -> timeline.effectClips.map { it.trackIndex }
            TrackType.SHAPE -> timeline.shapeClips.map { it.trackIndex }
            TrackType.MAIN_VIDEO -> listOf(0)
        }
        return (fromClips + timeline.tracks.filter { it.trackType == trackType }.map { it.zOrder }).distinct().sorted()
    }

    /**
     * Swaps the contents (and z-order) of two lanes of the same type. Because lane index is the
     * clips' stacking order, this is how a track is moved up or down in the layer stack.
     */
    fun swapLanes(timeline: Timeline, trackType: TrackType, laneA: Int, laneB: Int): Timeline? {
        if (laneA == laneB || trackType == TrackType.MAIN_VIDEO) return null
        fun swap(i: Int) = when (i) { laneA -> laneB; laneB -> laneA; else -> i }
        val tracks = timeline.tracks.map {
            if (it.trackType == trackType && (it.zOrder == laneA || it.zOrder == laneB)) {
                it.copy(zOrder = swap(it.zOrder), order = if (it.order == it.zOrder) swap(it.zOrder) else it.order)
            } else it
        }
        return when (trackType) {
            TrackType.OVERLAY, TrackType.ELEMENT, TrackType.ADJUSTMENT ->
                timeline.copy(overlayClips = timeline.overlayClips.map { it.copy(trackIndex = swap(it.trackIndex)) }, tracks = tracks)
            TrackType.AUDIO, TrackType.MUSIC, TrackType.SFX ->
                timeline.copy(audioClips = timeline.audioClips.map { it.copy(trackIndex = swap(it.trackIndex)) }, tracks = tracks)
            TrackType.TEXT, TrackType.CAPTION ->
                timeline.copy(textClips = timeline.textClips.map { it.copy(trackIndex = swap(it.trackIndex)) }, tracks = tracks)
            TrackType.STICKER ->
                timeline.copy(stickerClips = timeline.stickerClips.map { it.copy(trackIndex = swap(it.trackIndex)) }, tracks = tracks)
            TrackType.EFFECT ->
                timeline.copy(effectClips = timeline.effectClips.map { it.copy(trackIndex = swap(it.trackIndex)) }, tracks = tracks)
            TrackType.SHAPE ->
                timeline.copy(shapeClips = timeline.shapeClips.map { it.copy(trackIndex = swap(it.trackIndex)) }, tracks = tracks)
            TrackType.MAIN_VIDEO -> null
        }
    }

    /** Applies lock / hide / mute to every clip of a lane and to its explicit track record, if any. */
    fun setLaneFlags(
        timeline: Timeline,
        trackType: TrackType,
        lane: Int,
        locked: Boolean? = null,
        hidden: Boolean? = null,
        muted: Boolean? = null
    ): Timeline {
        fun matches(trackIndex: Int) = lane < 0 || trackIndex == lane
        val tracks = timeline.tracks.map {
            if (it.trackType == trackType && (lane < 0 || it.zOrder == lane)) {
                it.copy(
                    isLocked = locked ?: it.isLocked,
                    isVisible = hidden?.let { h -> !h } ?: it.isVisible,
                    isMuted = muted ?: it.isMuted
                )
            } else it
        }
        return when (trackType) {
            TrackType.MAIN_VIDEO -> timeline.copy(
                videoClips = timeline.videoClips.map {
                    it.copy(isLocked = locked ?: it.isLocked, isHidden = hidden ?: it.isHidden, isMuted = muted ?: it.isMuted)
                },
                tracks = tracks
            )
            TrackType.OVERLAY, TrackType.ELEMENT, TrackType.ADJUSTMENT -> timeline.copy(
                overlayClips = timeline.overlayClips.map {
                    if (!matches(it.trackIndex)) it
                    else it.copy(isLocked = locked ?: it.isLocked, isHidden = hidden ?: it.isHidden, isMuted = muted ?: it.isMuted)
                },
                tracks = tracks
            )
            TrackType.AUDIO, TrackType.MUSIC, TrackType.SFX -> timeline.copy(
                audioClips = timeline.audioClips.map {
                    if (!matches(it.trackIndex)) it
                    else it.copy(isLocked = locked ?: it.isLocked, isHidden = hidden ?: it.isHidden, isMuted = muted ?: it.isMuted)
                },
                tracks = tracks
            )
            TrackType.TEXT, TrackType.CAPTION -> timeline.copy(
                textClips = timeline.textClips.map {
                    if (!matches(it.trackIndex)) it
                    else it.copy(isLocked = locked ?: it.isLocked, isHidden = hidden ?: it.isHidden)
                },
                tracks = tracks
            )
            TrackType.STICKER -> timeline.copy(
                stickerClips = timeline.stickerClips.map {
                    if (!matches(it.trackIndex)) it
                    else it.copy(isLocked = locked ?: it.isLocked, isHidden = hidden ?: it.isHidden)
                },
                tracks = tracks
            )
            TrackType.EFFECT -> timeline.copy(
                effectClips = timeline.effectClips.map {
                    if (!matches(it.trackIndex)) it
                    else it.copy(isLocked = locked ?: it.isLocked, isHidden = hidden ?: it.isHidden)
                },
                tracks = tracks
            )
            TrackType.SHAPE -> timeline.copy(
                shapeClips = timeline.shapeClips.map {
                    if (!matches(it.trackIndex)) it
                    else it.copy(isLocked = locked ?: it.isLocked, isHidden = hidden ?: it.isHidden)
                },
                tracks = tracks
            )
        }
    }
}
