package com.example.engine.controller

import com.example.domain.model.AudioClip
import com.example.domain.model.Timeline
import com.example.domain.model.TrackSettings
import com.example.domain.model.TrackType
import com.example.domain.model.VideoClip

/**
 * Track level (hide / mute / solo) rules for the live preview player.
 *
 * Before this existed the preview only looked at the per-clip flags, so the timeline
 * toolbar's "Mute All Video", the per track mute/solo/solo buttons and the exported mix
 * disagreed: the export (TimelineEvaluator + AudioExportProcessor) silences a muted or
 * non-soloed track, while the preview player kept playing it.
 *
 * Hide is deliberately *not* part of the audibility rule: hiding a video track only
 * switches its picture off, exactly like the export pipeline, which still mixes the audio
 * of a hidden video track. Silence always comes from mute/solo.
 */
object PreviewMixPolicy {

  fun settingsOf(timeline: Timeline, type: TrackType): TrackSettings =
    timeline.trackSettings[type] ?: TrackSettings(type)

  /** True when any track is soloed: everything that is not soloed is then silenced. */
  fun hasSoloedTrack(timeline: Timeline): Boolean =
    timeline.trackSettings.values.any { it.isSolo }

  /** Picture of this track is switched off. */
  fun isTrackVisible(timeline: Timeline, type: TrackType): Boolean =
    !settingsOf(timeline, type).isHidden

  /** Track mute + project wide solo gate, matching the export mix. */
  fun isTrackAudible(timeline: Timeline, type: TrackType): Boolean {
    val settings = settingsOf(timeline, type)
    if (settings.isMuted) return false
    return !hasSoloedTrack(timeline) || settings.isSolo
  }

  /** Effective preview gain for a main video / overlay clip. 0f = silent, else 0..2. */
  fun clipGain(clip: VideoClip, timeline: Timeline, type: TrackType): Float {
    if (!isTrackAudible(timeline, type)) return 0f
    if (clip.isMuted) return 0f
    return clip.volume.coerceIn(0f, 2f)
  }

  /** Effective preview gain for an audio lane clip (track gate + clip mute/solo). */
  fun audioClipGain(clip: AudioClip, timeline: Timeline, hasClipSolo: Boolean): Float {
    if (!isTrackAudible(timeline, TrackType.AUDIO)) return 0f
    if (clip.isMuted) return 0f
    if (hasClipSolo && !clip.isSolo) return 0f
    return clip.volume.coerceIn(0f, 2f)
  }

  /**
   * Compact signature of every track flag that changes the preview mix. A timeline snapshot
   * whose signature changed (mute toggled while the preview is running) must be re-applied
   * to the players even though the active clip did not change.
   */
  fun mixSignature(timeline: Timeline): String = buildString {
    for (type in MIX_TRACKS) {
      val s = settingsOf(timeline, type)
      append(type.name).append(':')
      append(if (s.isHidden) '1' else '0')
      append(if (s.isMuted) '1' else '0')
      append(if (s.isSolo) '1' else '0')
      append('|')
    }
  }

  private val MIX_TRACKS = listOf(
    TrackType.MAIN_VIDEO,
    TrackType.OVERLAY,
    TrackType.AUDIO
  )
}
