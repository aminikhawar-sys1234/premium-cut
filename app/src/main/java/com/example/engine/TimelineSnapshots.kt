package com.example.engine

import com.example.domain.model.AudioClip
import com.example.domain.model.EffectClip
import com.example.domain.model.StickerClip
import com.example.domain.model.TextClip
import com.example.domain.model.TrackType
import com.example.domain.model.Transition
import com.example.domain.model.VideoClip

/** Snapshot of all active elements on the timeline at a given timestamp. */
data class TimelineActiveSnapshot(
  val timelinePosMs: Long,
  val primaryVideoClip: VideoClip?,
  val activeOverlays: List<VideoClip>,
  val activeAudioClips: List<AudioClip>,
  val activeTextClips: List<TextClip>,
  val activeStickerClips: List<StickerClip>,
  val activeEffects: List<EffectClip>,
  val activeTransition: Transition?,
  val transitionProgress: Float,
  val transitionClipBefore: VideoClip?,
  val transitionClipAfter: VideoClip?,
  val frameIndex: Long = 0L,
  val fps: Int = 30,
  val activeOverlaps: List<ClipOverlapInfo> = emptyList(),
  val activeAudioSyncInfo: List<AudioSyncInfo> = emptyList()
)

data class ClipOverlapInfo(
  val clipAId: String,
  val clipBId: String,
  val trackType: TrackType,
  val trackIndex: Int,
  val overlapStartMs: Long,
  val overlapEndMs: Long,
  val overlapDurationMs: Long
)

data class ClipTrimmedRange(
  val clipId: String,
  val trackType: TrackType,
  val trackIndex: Int,
  val timelineStartMs: Long,
  val timelineEndMs: Long,
  val durationMs: Long,
  val sourceStartMs: Long,
  val sourceEndMs: Long,
  val sourceDurationMs: Long,
  val speed: Float,
  val isTrimmed: Boolean
)

data class TransitionTimingInfo(
  val transition: Transition,
  val startMs: Long,
  val endMs: Long,
  val durationMs: Long,
  val progress: Float,
  val clipBefore: VideoClip,
  val clipAfter: VideoClip
)

data class AudioSyncInfo(
  val clipId: String,
  val trackIndex: Int,
  val isVideoTrack: Boolean,
  val uri: String,
  val timelineStartMs: Long,
  val timelineEndMs: Long,
  val timelinePosMs: Long,
  val sourcePosMs: Long,
  val sourcePosUs: Long,
  val effectiveVolume: Float,
  val speed: Float,
  val isMuted: Boolean,
  val pitchShiftSemitones: Float = 0f
)
