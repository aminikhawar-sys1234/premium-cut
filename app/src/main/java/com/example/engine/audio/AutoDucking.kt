package com.example.engine.audio

import com.example.domain.model.AudioClip
import com.example.domain.model.AudioDuckingSettings
import com.example.domain.model.AudioLaneRole
import com.example.domain.model.AudioTrackRole
import com.example.domain.model.Timeline
import com.example.domain.model.TrackType
import com.example.engine.controller.PreviewMixPolicy
import kotlin.math.exp
import kotlin.math.ln

/** One breakpoint of a clip's ducking curve: time relative to the clip start and the linear duck gain there. */
data class DuckEnvelopePoint(
  val relTimeMs: Long,
  val gain: Float
)

/** A merged span of speech on the timeline, end exclusive. */
data class SpeechInterval(
  val startMs: Long,
  val endMs: Long
)

/**
 * Timeline-driven auto-ducking.
 *
 * The duck is evaluated from the live timeline instead of being baked into clip keyframes, so moving,
 * trimming or deleting a voice clip re-flows the ducking immediately, and the user's own volume keyframes
 * are never rewritten. The resulting gain is a multiplier applied on top of the clip's regular envelope
 * (volume / keyframes / fades) by preview, playback and export alike.
 *
 * Speech (the trigger) is:
 * - audio clips on lanes whose role is [AudioTrackRole.VOICEOVER] or [AudioTrackRole.DIALOGUE];
 * - audio clips flagged [AudioClip.isVoiceOver] on lanes without an explicit role;
 * - main-track video clips with audible original sound, when [AudioDuckingSettings.duckUnderVideoAudio] is set.
 * Lanes that are themselves ducking targets never trigger ducking, and inaudible (muted / non-soloed) sources
 * never trigger it either.
 */
object AutoDucking {

  /** Immutable ducking curve of one target lane. Safe to share across the UI, playback and export threads. */
  class DuckEnvelope internal constructor(
    val settings: AudioDuckingSettings,
    private val starts: LongArray,
    private val ends: LongArray
  ) {
    val speechIntervals: List<SpeechInterval>
      get() = starts.indices.map { SpeechInterval(starts[it], ends[it]) }

    val isEmpty: Boolean get() = starts.isEmpty()

    /** Duck depth at [timelineMs]: 0 = untouched, 1 = fully ducked to [AudioDuckingSettings.duckRatio]. */
    fun depthAt(timelineMs: Long): Float {
      if (starts.isEmpty()) return 0f
      val i = lastIntervalStartingBefore(timelineMs)
      if (i < 0) return 0f
      val current = contribution(i, timelineMs)
      return if (i > 0) maxOf(current, contribution(i - 1, timelineMs)) else current
    }

    /** Linear gain multiplier at [timelineMs], in [duckRatio, 1]. */
    fun gainAt(timelineMs: Long): Float = gainForDepth(depthAt(timelineMs), settings.duckRatio)

    /** True when [startMs, endMs) touches any attack, hold or release region. */
    fun affects(startMs: Long, endMs: Long): Boolean {
      for (i in starts.indices) {
        val from = starts[i] - settings.attackMs
        val to = ends[i] + settings.releaseMs
        if (from < endMs && to > startMs) return true
        if (from >= endMs) return false
      }
      return false
    }

    /**
     * Breakpoints of the duck curve over [startMs, endMs] (timeline time), relative to [originMs].
     * Ramps are sampled every [rampStepMs] so a polyline through the points follows the S-curve closely.
     */
    fun breakpoints(startMs: Long, endMs: Long, originMs: Long, rampStepMs: Long = DEFAULT_RAMP_STEP_MS): List<DuckEnvelopePoint> {
      if (endMs <= startMs) return emptyList()
      val step = rampStepMs.coerceAtLeast(1L)
      val times = java.util.TreeSet<Long>()
      times.add(startMs)
      times.add(endMs)
      for (i in starts.indices) {
        val attackStart = starts[i] - settings.attackMs
        val releaseEnd = ends[i] + settings.releaseMs
        if (attackStart > endMs) break
        if (releaseEnd < startMs) continue
        addRamp(times, attackStart, starts[i], step)
        addRamp(times, ends[i], releaseEnd, step)
      }
      return times.subSet(startMs, true, endMs, true).map { t ->
        DuckEnvelopePoint(relTimeMs = t - originMs, gain = gainAt(t))
      }
    }

    private fun addRamp(into: MutableSet<Long>, from: Long, to: Long, step: Long) {
      into.add(from)
      var t = from + step
      while (t < to) {
        into.add(t)
        t += step
      }
      into.add(to)
    }

    /** Largest index whose attack has begun at [t], or -1. */
    private fun lastIntervalStartingBefore(t: Long): Int {
      val attack = settings.attackMs
      var lo = 0
      var hi = starts.size - 1
      var found = -1
      while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (starts[mid] - attack <= t) {
          found = mid
          lo = mid + 1
        } else {
          hi = mid - 1
        }
      }
      return found
    }

    private fun contribution(i: Int, t: Long): Float {
      val s = starts[i]
      val e = ends[i]
      val attack = settings.attackMs
      val release = settings.releaseMs
      return when {
        t < s - attack -> 0f
        t < s -> smoothstep((t - (s - attack)).toFloat() / attack.toFloat())
        t <= e -> 1f
        release > 0L && t < e + release -> 1f - smoothstep((t - e).toFloat() / release.toFloat())
        else -> 0f
      }
    }
  }

  const val DEFAULT_RAMP_STEP_MS = 25L

  private class Cache(val timeline: Timeline, val envelopes: Map<Int, DuckEnvelope>)

  /** Timelines are immutable snapshots, so an identity hit means the envelopes are still exact. */
  @Volatile
  private var cache: Cache? = null

  // --- Queries ------------------------------------------------------------------------------

  fun settingsFor(timeline: Timeline, targetTrackIndex: Int): AudioDuckingSettings? =
    timeline.audioDucking.firstOrNull { it.targetTrackIndex == targetTrackIndex }

  fun roleOf(timeline: Timeline, trackIndex: Int): AudioTrackRole =
    timeline.audioLaneRoles.firstOrNull { it.trackIndex == trackIndex }?.role ?: AudioTrackRole.UNSPECIFIED

  /** Ducking curves of every ducked lane of [timeline], keyed by target lane. */
  fun envelopesFor(timeline: Timeline): Map<Int, DuckEnvelope> {
    if (timeline.audioDucking.isEmpty()) return emptyMap()
    cache?.let { if (it.timeline === timeline) return it.envelopes }
    val built = buildEnvelopes(timeline)
    cache = Cache(timeline, built)
    return built
  }

  fun envelopeFor(timeline: Timeline, targetTrackIndex: Int): DuckEnvelope? =
    envelopesFor(timeline)[targetTrackIndex]

  /** Ducking multiplier for [clip] at timeline position [timelineMs]; 1f when its lane is not ducked. */
  fun gainAt(timeline: Timeline, clip: AudioClip, timelineMs: Long): Float {
    if (timeline.audioDucking.isEmpty()) return 1f
    return envelopeFor(timeline, clip.trackIndex)?.gainAt(timelineMs) ?: 1f
  }

  /** Duck curve across [clip], relative to its start. Empty when the clip's lane is not ducked or no speech touches it. */
  fun clipEnvelope(timeline: Timeline, clip: AudioClip, rampStepMs: Long = DEFAULT_RAMP_STEP_MS): List<DuckEnvelopePoint> {
    val envelope = envelopeFor(timeline, clip.trackIndex) ?: return emptyList()
    val start = clip.timelineStartMs
    val end = clip.timelineStartMs + clip.durationMs.coerceAtLeast(0L)
    if (!envelope.affects(start, end)) return emptyList()
    return envelope.breakpoints(start, end, originMs = start, rampStepMs = rampStepMs)
  }

  /** Merged speech spans that drive [settings] on [timeline] (hold-bridged by attack + release). */
  fun speechIntervals(timeline: Timeline, settings: AudioDuckingSettings): List<SpeechInterval> {
    val raw = collectSpeechSpans(timeline, settings)
    val (starts, ends) = mergeSpans(raw, bridgeGapMs = settings.attackMs + settings.releaseMs)
    return starts.indices.map { SpeechInterval(starts[it], ends[it]) }
  }

  // --- Timeline transforms (pure; the engine wraps them in history + state lock) ---------------

  fun withDucking(timeline: Timeline, settings: AudioDuckingSettings): Timeline {
    val normalized = settings.normalized()
    val others = timeline.audioDucking.filterNot { it.targetTrackIndex == normalized.targetTrackIndex }
    val updated = (others + normalized).sortedBy { it.targetTrackIndex }
    return if (updated == timeline.audioDucking) timeline else timeline.copy(audioDucking = updated)
  }

  fun withoutDucking(timeline: Timeline, targetTrackIndex: Int): Timeline {
    val updated = timeline.audioDucking.filterNot { it.targetTrackIndex == targetTrackIndex }
    return if (updated.size == timeline.audioDucking.size) timeline else timeline.copy(audioDucking = updated)
  }

  fun withRole(timeline: Timeline, trackIndex: Int, role: AudioTrackRole): Timeline {
    val others = timeline.audioLaneRoles.filterNot { it.trackIndex == trackIndex }
    val updated = if (role == AudioTrackRole.UNSPECIFIED) others else (others + AudioLaneRole(trackIndex, role)).sortedBy { it.trackIndex }
    return if (updated == timeline.audioLaneRoles) timeline else timeline.copy(audioLaneRoles = updated)
  }

  /** Drops ducking and role entries of an audio lane that no longer exists ([lane] < 0 = every lane). */
  fun withoutLane(timeline: Timeline, lane: Int): Timeline {
    val ducking = timeline.audioDucking.filterNot { lane < 0 || it.targetTrackIndex == lane }
    val roles = timeline.audioLaneRoles.filterNot { lane < 0 || it.trackIndex == lane }
    if (ducking.size == timeline.audioDucking.size && roles.size == timeline.audioLaneRoles.size) return timeline
    return timeline.copy(audioDucking = ducking, audioLaneRoles = roles)
  }

  /** Keeps ducking and roles attached to their lane's content when two audio lanes swap places. */
  fun withLanesSwapped(timeline: Timeline, laneA: Int, laneB: Int): Timeline {
    if (timeline.audioDucking.isEmpty() && timeline.audioLaneRoles.isEmpty()) return timeline
    fun swap(i: Int) = when (i) { laneA -> laneB; laneB -> laneA; else -> i }
    return timeline.copy(
      audioDucking = timeline.audioDucking.map { it.copy(targetTrackIndex = swap(it.targetTrackIndex)) }.sortedBy { it.targetTrackIndex },
      audioLaneRoles = timeline.audioLaneRoles.map { it.copy(trackIndex = swap(it.trackIndex)) }.sortedBy { it.trackIndex }
    )
  }

  // --- Internals ------------------------------------------------------------------------------

  private fun buildEnvelopes(timeline: Timeline): Map<Int, DuckEnvelope> {
    val result = LinkedHashMap<Int, DuckEnvelope>()
    for (raw in timeline.audioDucking) {
      val settings = raw.normalized()
      if (settings.duckRatio >= 1f) continue
      val (starts, ends) = mergeSpans(
        collectSpeechSpans(timeline, settings),
        bridgeGapMs = settings.attackMs + settings.releaseMs
      )
      result[settings.targetTrackIndex] = DuckEnvelope(settings, starts, ends)
    }
    return result
  }

  private fun collectSpeechSpans(timeline: Timeline, settings: AudioDuckingSettings): List<LongArray> {
    val duckedLanes = timeline.audioDucking.mapTo(HashSet()) { it.targetTrackIndex }
    val spans = ArrayList<LongArray>()

    val hasClipSolo = timeline.audioClips.any { it.isSolo }
    for (clip in timeline.audioClips) {
      if (clip.durationMs <= 0L) continue
      if (clip.trackIndex in duckedLanes) continue
      val isSpeech = when (roleOf(timeline, clip.trackIndex)) {
        AudioTrackRole.VOICEOVER, AudioTrackRole.DIALOGUE -> true
        AudioTrackRole.MUSIC, AudioTrackRole.SFX -> false
        AudioTrackRole.UNSPECIFIED -> clip.isVoiceOver
      }
      if (!isSpeech) continue
      if (PreviewMixPolicy.audioClipGain(clip, timeline, hasClipSolo) <= 0f) continue
      spans.add(longArrayOf(clip.timelineStartMs, clip.timelineStartMs + clip.durationMs))
    }

    if (settings.duckUnderVideoAudio) {
      for (clip in timeline.videoClips) {
        if (clip.durationMs <= 0L || !clip.isVideo || !clip.hasAudio) continue
        if (PreviewMixPolicy.clipGain(clip, timeline, TrackType.MAIN_VIDEO) <= 0f) continue
        spans.add(longArrayOf(clip.timelineStartMs, clip.timelineStartMs + clip.durationMs))
      }
    }
    return spans
  }

  /** Sorts and merges spans; gaps of at most [bridgeGapMs] are held ducked so the bed does not pump between words. */
  private fun mergeSpans(spans: List<LongArray>, bridgeGapMs: Long): Pair<LongArray, LongArray> {
    if (spans.isEmpty()) return LongArray(0) to LongArray(0)
    val sorted = spans.sortedBy { it[0] }
    val starts = ArrayList<Long>(sorted.size)
    val ends = ArrayList<Long>(sorted.size)
    var curStart = sorted[0][0]
    var curEnd = sorted[0][1]
    for (k in 1 until sorted.size) {
      val s = sorted[k][0]
      val e = sorted[k][1]
      if (s - curEnd <= bridgeGapMs) {
        if (e > curEnd) curEnd = e
      } else {
        starts.add(curStart)
        ends.add(curEnd)
        curStart = s
        curEnd = e
      }
    }
    starts.add(curStart)
    ends.add(curEnd)
    return starts.toLongArray() to ends.toLongArray()
  }

  private fun smoothstep(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
  }

  /** Interpolates in dB (ratio^depth) so the ramp sounds even; a ratio of 0 falls back to a linear fade to silence. */
  internal fun gainForDepth(depth: Float, duckRatio: Float): Float {
    if (depth <= 0f) return 1f
    val ratio = duckRatio.coerceIn(0f, 1f)
    if (ratio >= 1f) return 1f
    val d = depth.coerceAtMost(1f)
    if (ratio <= AudioDuckingSettings.MIN_DUCK_RATIO) return 1f - d
    return exp(d * ln(ratio))
  }
}
