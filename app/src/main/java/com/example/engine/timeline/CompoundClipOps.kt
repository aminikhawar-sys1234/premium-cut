package com.example.engine.timeline

import com.example.domain.model.AudioClip
import com.example.domain.model.ClipKeyframe
import com.example.domain.model.CompoundClip
import com.example.domain.model.EffectClip
import com.example.domain.model.ShapeClip
import com.example.domain.model.StickerClip
import com.example.domain.model.TextClip
import com.example.domain.model.Timeline
import com.example.domain.model.TrackType
import com.example.domain.model.Transition
import com.example.domain.model.VideoClip
import com.example.domain.model.WordTiming
import com.example.domain.model.COMPOUND_CLIP_MIME
import com.example.domain.model.compoundClipUri
import com.example.domain.model.compoundOwnerId
import com.example.domain.model.isCompoundContainer
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * Pure compound-clip edits. [com.example.engine.TimelineEngine] wraps these with the state lock,
 * [com.example.engine.history.TimelineActionManager] history, and main-track continuity.
 *
 * A compound is a shell [VideoClip] plus a [CompoundClip] record. Children live in the nested
 * timeline at offsets relative to the selection's bounding start, so lanes and keyframes survive.
 */
object CompoundClipOps {

  data class Created(val timeline: Timeline, val compoundId: String)

  data class Exploded(val timeline: Timeline, val restoredIds: Set<String>)

  fun create(timeline: Timeline, selectedClipIds: Set<String>, name: String = "Compound Clip"): Created? {
    if (selectedClipIds.size < 2) return null
    val members = collect(timeline, selectedClipIds)
    if (members.size != selectedClipIds.size) return null
    if (members.any { it.locked || timeline.trackLocked(it.track) }) return null

    val origin = members.minOf { it.start }
    val end = members.maxOf { it.start + it.duration }
    val span = end - origin
    if (span < 1L) return null

    val selectedMain = timeline.videoClips.filter { it.id in selectedClipIds }
    val hostMain = selectedMain.isNotEmpty()
    if (hostMain && !mainSelectionIsContiguous(timeline, selectedClipIds, origin)) return null
    if (hostMain && timeline.trackLocked(TrackType.MAIN_VIDEO)) return null

    val lane = if (hostMain) 0 else freeOverlayLane(timeline, selectedClipIds, origin, span, members)
    if (!hostMain && timeline.trackLocked(TrackType.OVERLAY)) return null

    val shellId = UUID.randomUUID().toString()
    val sample = (selectedMain + timeline.overlayClips.filter { it.id in selectedClipIds }).firstOrNull()
    val shell = VideoClip(
      id = shellId,
      uri = compoundClipUri(shellId),
      name = name.ifBlank { "Compound Clip" },
      isVideo = true,
      timelineStartMs = origin,
      durationMs = span,
      sourceStartMs = 0L,
      sourceEndMs = span,
      mimeType = COMPOUND_CLIP_MIME,
      hasAudio = selectedMain.any { it.hasAudio } || timeline.audioClips.any { it.id in selectedClipIds },
      width = sample?.width ?: 1920,
      height = sample?.height ?: 1080,
      frameRate = sample?.frameRate ?: 30f,
      trackIndex = lane
    )

    val nestedVideo = timeline.videoClips.filter { it.id in selectedClipIds }.map { it.shiftStart(-origin) }
    val nestedOverlay = timeline.overlayClips.filter { it.id in selectedClipIds }.map { it.shiftStart(-origin) }
    val (nestedTransitions, parentTransitions) = splitTransitions(
      timeline.transitions,
      timeline.videoClips,
      selectedClipIds,
      nestedVideo,
      if (hostMain) parentVideoWithShell(timeline.videoClips, selectedClipIds, shell) else timeline.videoClips
    )

    val nested = Timeline(
      videoClips = nestedVideo,
      overlayClips = nestedOverlay,
      audioClips = timeline.audioClips.filter { it.id in selectedClipIds }.map { it.shiftStart(-origin) },
      textClips = timeline.textClips.filter { it.id in selectedClipIds }.map { it.shiftStart(-origin) },
      stickerClips = timeline.stickerClips.filter { it.id in selectedClipIds }.map { it.shiftStart(-origin) },
      effectClips = timeline.effectClips.filter { it.id in selectedClipIds }.map { it.shiftStart(-origin) },
      shapeClips = timeline.shapeClips.filter { it.id in selectedClipIds }.map { it.shiftStart(-origin) },
      transitions = nestedTransitions,
      adjustments = timeline.adjustments,
      canvasBackgroundColor = timeline.canvasBackgroundColor,
      aspectRatio = timeline.aspectRatio,
      compoundClips = timeline.compoundClips.filter { it.id in selectedClipIds }
    )

    val parentVideo = if (hostMain) {
      packMain(parentVideoWithShell(timeline.videoClips, selectedClipIds, shell))
    } else {
      timeline.videoClips.filter { it.id !in selectedClipIds }
    }

    val next = timeline.copy(
      videoClips = parentVideo,
      overlayClips = if (hostMain) {
        timeline.overlayClips.filter { it.id !in selectedClipIds }
      } else {
        (timeline.overlayClips.filter { it.id !in selectedClipIds } + shell).sortedBy { it.timelineStartMs }
      },
      audioClips = timeline.audioClips.filter { it.id !in selectedClipIds },
      textClips = timeline.textClips.filter { it.id !in selectedClipIds },
      stickerClips = timeline.stickerClips.filter { it.id !in selectedClipIds },
      effectClips = timeline.effectClips.filter { it.id !in selectedClipIds },
      shapeClips = timeline.shapeClips.filter { it.id !in selectedClipIds },
      transitions = if (hostMain) parentTransitions else timeline.transitions,
      compoundClips = timeline.compoundClips.filter { it.id !in selectedClipIds } +
        CompoundClip(id = shellId, name = shell.name, nested = nested, contentDurationMs = span)
    )
    return Created(next, shellId)
  }

  fun breakApart(timeline: Timeline, compoundClipId: String): Exploded? {
    val record = timeline.compoundClips.find { it.id == compoundClipId } ?: return null
    val shell = findShell(timeline, compoundClipId) ?: return null
    val window = sourceWindow(shell, record.contentDurationMs)
    val restored = linkedSetOf<String>()
    val placed = placeWindow(record.nested, window.first, window.second, shell.timelineStartMs, shell.durationMs, restored)
    val onMain = timeline.videoClips.any { it.id == compoundClipId }
    val parentVideo = if (onMain) {
      packMain(spliceShell(timeline.videoClips, compoundClipId, placed.videoClips.sortedBy { it.timelineStartMs }))
    } else {
      timeline.videoClips + placed.videoClips
    }
    val parentOverlay = timeline.overlayClips.filter { it.id != compoundClipId } + placed.overlayClips
    val innerRecords = record.nested.compoundClips.filter { it.id in restored }
    val next = timeline.copy(
      videoClips = parentVideo.sortedBy { it.timelineStartMs },
      overlayClips = parentOverlay.sortedBy { it.timelineStartMs },
      audioClips = (timeline.audioClips + placed.audioClips).sortedBy { it.timelineStartMs },
      textClips = (timeline.textClips + placed.textClips).sortedBy { it.timelineStartMs },
      stickerClips = (timeline.stickerClips + placed.stickerClips).sortedBy { it.timelineStartMs },
      effectClips = (timeline.effectClips + placed.effectClips).sortedBy { it.timelineStartMs },
      shapeClips = (timeline.shapeClips + placed.shapeClips).sortedBy { it.timelineStartMs },
      transitions = if (onMain) {
        timeline.transitions.filter { it.clipIndexBefore < (parentVideo.size - 1).coerceAtLeast(0) }
      } else timeline.transitions,
      compoundClips = timeline.compoundClips.filter { it.id != compoundClipId } + innerRecords
    )
    return Exploded(if (onMain) next.copy(videoClips = packMain(next.videoClips)) else next, restored)
  }

  fun open(timeline: Timeline, compoundClipId: String): Timeline? =
    timeline.compoundClips.find { it.id == compoundClipId }?.nested

  /**
   * Replaces the nest used for child editing. A shell that was showing the full sequence adopts
   * the new length (the main track is packed). A trimmed window is kept when it still fits.
   */
  fun replaceNested(timeline: Timeline, compoundClipId: String, nested: Timeline): Timeline? {
    val record = timeline.compoundClips.find { it.id == compoundClipId } ?: return null
    val shell = findShell(timeline, compoundClipId) ?: return null
    val newContent = nested.totalDurationMs.coerceAtLeast(1L)
    val showedEverything = shell.sourceStartMs == 0L && shell.sourceEndMs == record.contentDurationMs
    val updatedShell = if (showedEverything) {
      shell.copy(durationMs = newContent, sourceStartMs = 0L, sourceEndMs = newContent)
    } else {
      val start = shell.sourceStartMs.coerceIn(0L, (newContent - 1L).coerceAtLeast(0L))
      val end = shell.sourceEndMs.coerceIn(start + 1L, newContent)
      shell.copy(durationMs = (end - start).coerceAtLeast(1L), sourceStartMs = start, sourceEndMs = end)
    }
    val replaced = replaceShell(
      timeline.copy(
        compoundClips = timeline.compoundClips.map { item ->
          if (item.id == compoundClipId) item.copy(nested = nested, contentDurationMs = newContent) else item
        }
      ),
      updatedShell
    )
    val onMain = timeline.videoClips.any { it.id == compoundClipId }
    return if (onMain) replaced.copy(videoClips = packMain(replaced.videoClips)) else replaced
  }

  /**
   * After duplicate or paste, a copied shell still points at the source compound URI.
   * Gives that shell its own nest and id so breaking either copy cannot emit duplicate child ids.
   */
  fun rebindCopiedShells(timeline: Timeline): Timeline {
    val shells = (timeline.videoClips + timeline.overlayClips).filter { it.isCompoundContainer() }
    if (shells.isEmpty()) {
      return if (timeline.compoundClips.isEmpty()) timeline else timeline.copy(compoundClips = emptyList())
    }
    var compounds = timeline.compoundClips
    val fixes = mutableMapOf<String, VideoClip>()
    for (shell in shells) {
      val owner = shell.compoundOwnerId()
      if (owner == shell.id) continue
      if (compounds.none { it.id == shell.id }) {
        val source = compounds.find { it.id == owner } ?: continue
        compounds = compounds + cloneCompound(source, shell.id)
      }
      fixes[shell.id] = shell.copy(
        uri = compoundClipUri(shell.id),
        mimeType = COMPOUND_CLIP_MIME,
        keyframes = shell.keyframes.map { it.copy(id = UUID.randomUUID().toString()) }
      )
    }
    val live = shells.map { it.id }.toSet()
    val pruned = compounds.filter { it.id in live }
    if (fixes.isEmpty() && pruned == timeline.compoundClips) return timeline
    return timeline.copy(
      videoClips = timeline.videoClips.map { fixes[it.id] ?: it },
      overlayClips = timeline.overlayClips.map { fixes[it.id] ?: it },
      compoundClips = pruned
    )
  }

  /**
   * Split already divided the shell's source window. Each half becomes an independent compound
   * whose nest is only the media inside that window, with the window reset to the full half.
   */
  fun applyCompoundSplit(timeline: Timeline, headId: String, tailId: String): Timeline {
    val record = timeline.compoundClips.find { it.id == headId } ?: return timeline
    val head = findShell(timeline, headId) ?: return timeline
    val tail = findShell(timeline, tailId) ?: return timeline
    val headWindow = sourceWindow(head, record.contentDurationMs)
    val tailWindow = sourceWindow(tail, record.contentDurationMs)
    val headSlice = sliceTimeline(record.nested, headWindow.first, headWindow.second)
    val tailSlice = sliceTimeline(cloneCompound(record, tailId).nested, tailWindow.first, tailWindow.second)
    val headContent = (headWindow.second - headWindow.first).coerceAtLeast(1L)
    val tailContent = (tailWindow.second - tailWindow.first).coerceAtLeast(1L)
    val headShell = head.copy(
      uri = compoundClipUri(headId),
      mimeType = COMPOUND_CLIP_MIME,
      sourceStartMs = 0L,
      sourceEndMs = headContent
    )
    val tailShell = tail.copy(
      uri = compoundClipUri(tailId),
      mimeType = COMPOUND_CLIP_MIME,
      sourceStartMs = 0L,
      sourceEndMs = tailContent
    )
    val compounds = timeline.compoundClips.filter { it.id != headId && it.id != tailId } +
      record.copy(nested = headSlice, contentDurationMs = headContent) +
      CompoundClip(id = tailId, name = record.name, nested = tailSlice, contentDurationMs = tailContent)
    return replaceShell(replaceShell(timeline, headShell), tailShell).copy(compoundClips = compounds)
  }

  fun droppingOrphanCompounds(timeline: Timeline): Timeline {
    if (timeline.compoundClips.isEmpty()) return timeline
    val live = HashSet<String>()
    timeline.videoClips.forEach { if (it.isCompoundContainer()) live.add(it.id) }
    timeline.overlayClips.forEach { if (it.isCompoundContainer()) live.add(it.id) }
    if (timeline.compoundClips.all { it.id in live }) return timeline
    return timeline.copy(compoundClips = timeline.compoundClips.filter { it.id in live })
  }

  private data class Member(
    val id: String,
    val start: Long,
    val duration: Long,
    val track: TrackType,
    val trackIndex: Int,
    val locked: Boolean
  )

  private fun collect(timeline: Timeline, ids: Set<String>): List<Member> {
    val found = mutableListOf<Member>()
    val seen = HashSet<String>()
    fun add(id: String, start: Long, duration: Long, track: TrackType, trackIndex: Int, locked: Boolean) {
      if (id !in ids || !seen.add(id)) return
      found += Member(id, start, duration, track, trackIndex, locked)
    }
    timeline.videoClips.forEach { add(it.id, it.timelineStartMs, it.durationMs, TrackType.MAIN_VIDEO, it.trackIndex, it.isLocked) }
    timeline.overlayClips.forEach { add(it.id, it.timelineStartMs, it.durationMs, TrackType.OVERLAY, it.trackIndex, it.isLocked) }
    timeline.audioClips.forEach { add(it.id, it.timelineStartMs, it.durationMs, TrackType.AUDIO, it.trackIndex, it.isLocked) }
    timeline.textClips.forEach { add(it.id, it.timelineStartMs, it.durationMs, TrackType.TEXT, it.trackIndex, it.isLocked) }
    timeline.stickerClips.forEach { add(it.id, it.timelineStartMs, it.durationMs, TrackType.STICKER, it.trackIndex, it.isLocked) }
    timeline.effectClips.forEach { add(it.id, it.timelineStartMs, it.durationMs, TrackType.EFFECT, it.trackIndex, it.isLocked) }
    timeline.shapeClips.forEach { add(it.id, it.timelineStartMs, it.durationMs, TrackType.SHAPE, it.trackIndex, it.isLocked) }
    return found
  }

  private fun Timeline.trackLocked(type: TrackType): Boolean = trackSettings[type]?.isLocked == true

  /**
   * Selected main clips must be one contiguous run, and the group's start cannot cut into the
   * previous main clip. A trailing overhang is allowed: packing ripples the following clips.
   */
  private fun mainSelectionIsContiguous(timeline: Timeline, selected: Set<String>, origin: Long): Boolean {
    val selectedMain = timeline.videoClips.filter { it.id in selected }.sortedBy { it.timelineStartMs }
    if (selectedMain.isEmpty()) return true
    val first = selectedMain.first().timelineStartMs
    val lastEnd = selectedMain.last().let { it.timelineStartMs + it.durationMs }
    if (origin < first) return false
    return timeline.videoClips.none { clip ->
      clip.id !in selected && clip.timelineStartMs >= first && clip.timelineStartMs < lastEnd
    }
  }

  private fun freeOverlayLane(
    timeline: Timeline,
    selected: Set<String>,
    origin: Long,
    span: Long,
    members: List<Member>
  ): Int {
    var lane = members.filter { it.track == TrackType.OVERLAY }.minOfOrNull { it.trackIndex } ?: 1
    var guard = 0
    while (guard++ < 64 && timeline.overlayClips.any { clip ->
        clip.id !in selected && clip.trackIndex == lane && overlaps(clip.timelineStartMs, clip.durationMs, origin, span)
      }
    ) {
      lane++
    }
    return lane
  }

  private fun overlaps(start: Long, duration: Long, origin: Long, span: Long): Boolean {
    val end = start + duration
    val rangeEnd = origin + span
    return start < rangeEnd && end > origin
  }

  private fun parentVideoWithShell(video: List<VideoClip>, selected: Set<String>, shell: VideoClip): List<VideoClip> {
    val parent = ArrayList<VideoClip>(video.size)
    var inserted = false
    for (clip in video) {
      if (clip.id in selected) {
        if (!inserted) {
          parent += shell
          inserted = true
        }
      } else {
        parent += clip
      }
    }
    if (!inserted) parent += shell
    return parent
  }

  private fun spliceShell(clips: List<VideoClip>, shellId: String, replacements: List<VideoClip>): List<VideoClip> {
    val result = ArrayList<VideoClip>(clips.size + replacements.size)
    for (clip in clips) {
      if (clip.id == shellId) result.addAll(replacements) else result += clip
    }
    return result
  }

  private fun packMain(clips: List<VideoClip>): List<VideoClip> {
    var cursor = 0L
    return clips.map { clip ->
      val placed = if (clip.timelineStartMs == cursor) clip else clip.copy(timelineStartMs = cursor)
      cursor += clip.durationMs
      placed
    }
  }

  private fun splitTransitions(
    transitions: List<Transition>,
    oldVideo: List<VideoClip>,
    selected: Set<String>,
    nestedVideo: List<VideoClip>,
    parentVideo: List<VideoClip>
  ): Pair<List<Transition>, List<Transition>> {
    if (transitions.isEmpty()) return emptyList<Transition>() to emptyList()
    val nestedIndex = nestedVideo.mapIndexed { index, clip -> clip.id to index }.toMap()
    val parentIndex = parentVideo.mapIndexed { index, clip -> clip.id to index }.toMap()
    val nested = mutableListOf<Transition>()
    val parent = mutableListOf<Transition>()
    for (transition in transitions) {
      val left = oldVideo.getOrNull(transition.clipIndexBefore) ?: continue
      val right = oldVideo.getOrNull(transition.clipIndexBefore + 1) ?: continue
      val leftIn = left.id in selected
      val rightIn = right.id in selected
      if (leftIn && rightIn) {
        val leftIndex = nestedIndex[left.id]
        val rightIndex = nestedIndex[right.id]
        if (leftIndex != null && rightIndex == leftIndex + 1) {
          nested += transition.copy(id = UUID.randomUUID().toString(), clipIndexBefore = leftIndex)
        }
      } else if (!leftIn && !rightIn) {
        val leftIndex = parentIndex[left.id]
        val rightIndex = parentIndex[right.id]
        if (leftIndex != null && rightIndex == leftIndex + 1) {
          parent += transition.copy(clipIndexBefore = leftIndex)
        }
      }
    }
    return nested to parent
  }

  private data class Placed(
    val videoClips: List<VideoClip> = emptyList(),
    val overlayClips: List<VideoClip> = emptyList(),
    val audioClips: List<AudioClip> = emptyList(),
    val textClips: List<TextClip> = emptyList(),
    val stickerClips: List<StickerClip> = emptyList(),
    val effectClips: List<EffectClip> = emptyList(),
    val shapeClips: List<ShapeClip> = emptyList()
  )

  private data class WindowPlacement(
    val parentStart: Long,
    val headTrim: Long,
    val timelineDuration: Long,
    val sourceDuration: Long
  )

  private fun placeWindow(
    nested: Timeline,
    winStart: Long,
    winEnd: Long,
    shellStart: Long,
    shellDuration: Long,
    restored: MutableSet<String>
  ): Placed {
    fun placement(start: Long, duration: Long): WindowPlacement? {
      val clipEnd = start + duration
      val overlapStart = max(start, winStart)
      val overlapEnd = min(clipEnd, winEnd)
      val visible = overlapEnd - overlapStart
      if (visible <= 0L) return null
      val window = (winEnd - winStart).coerceAtLeast(1L)
      val span = shellDuration.coerceAtLeast(1L)
      return WindowPlacement(
        parentStart = shellStart + (overlapStart - winStart) * span / window,
        headTrim = overlapStart - start,
        timelineDuration = max(1L, visible * span / window),
        sourceDuration = visible
      )
    }
    val videos = nested.videoClips.mapNotNull { clip ->
      placement(clip.timelineStartMs, clip.durationMs)?.let { place ->
        restored += clip.id
        placeVideo(clip, place)
      }
    }
    val overlays = nested.overlayClips.mapNotNull { clip ->
      placement(clip.timelineStartMs, clip.durationMs)?.let { place ->
        restored += clip.id
        placeVideo(clip, place)
      }
    }
    val audios = nested.audioClips.mapNotNull { clip ->
      placement(clip.timelineStartMs, clip.durationMs)?.let { place ->
        restored += clip.id
        placeAudio(clip, place)
      }
    }
    val texts = nested.textClips.mapNotNull { clip ->
      placement(clip.timelineStartMs, clip.durationMs)?.let { place ->
        restored += clip.id
        placeText(clip, place)
      }
    }
    val stickers = nested.stickerClips.mapNotNull { clip ->
      placement(clip.timelineStartMs, clip.durationMs)?.let { place ->
        restored += clip.id
        placeSticker(clip, place)
      }
    }
    val effects = nested.effectClips.mapNotNull { clip ->
      placement(clip.timelineStartMs, clip.durationMs)?.let { place ->
        restored += clip.id
        placeEffect(clip, place)
      }
    }
    val shapes = nested.shapeClips.mapNotNull { clip ->
      placement(clip.timelineStartMs, clip.durationMs)?.let { place ->
        restored += clip.id
        placeShape(clip, place)
      }
    }
    return Placed(videos, overlays, audios, texts, stickers, effects, shapes)
  }

  private fun sliceTimeline(nested: Timeline, winStart: Long, winEnd: Long): Timeline {
    val placed = placeWindow(nested, winStart, winEnd, 0L, (winEnd - winStart).coerceAtLeast(1L), mutableSetOf())
    val kept = placed.videoClips.map { it.id }.toSet() + placed.overlayClips.map { it.id }
    val newIndex = placed.videoClips.mapIndexed { index, clip -> clip.id to index }.toMap()
    val transitions = nested.transitions.mapNotNull { transition ->
      val left = nested.videoClips.getOrNull(transition.clipIndexBefore) ?: return@mapNotNull null
      val right = nested.videoClips.getOrNull(transition.clipIndexBefore + 1) ?: return@mapNotNull null
      val leftIndex = newIndex[left.id] ?: return@mapNotNull null
      val rightIndex = newIndex[right.id] ?: return@mapNotNull null
      if (rightIndex == leftIndex + 1) transition.copy(clipIndexBefore = leftIndex) else null
    }
    return nested.copy(
      videoClips = placed.videoClips,
      overlayClips = placed.overlayClips,
      audioClips = placed.audioClips,
      textClips = placed.textClips,
      stickerClips = placed.stickerClips,
      effectClips = placed.effectClips,
      shapeClips = placed.shapeClips,
      transitions = transitions,
      compoundClips = nested.compoundClips.filter { it.id in kept }
    )
  }

  private fun placeVideo(clip: VideoClip, place: WindowPlacement): VideoClip {
    if (place.headTrim == 0L && place.sourceDuration == clip.durationMs && place.timelineDuration == clip.durationMs) {
      return if (clip.timelineStartMs == place.parentStart) clip else clip.copy(timelineStartMs = place.parentStart)
    }
    val (srcStart, srcEnd) = trimSourceWindow(
      clip.sourceStartMs, clip.sourceEndMs, clip.isReversed, clip.speed, place.headTrim, place.sourceDuration
    )
    return clip.copy(
      timelineStartMs = place.parentStart,
      durationMs = place.timelineDuration,
      sourceStartMs = srcStart,
      sourceEndMs = srcEnd,
      keyframes = shiftKeyframes(clip.keyframes, place.headTrim, place.sourceDuration, place.timelineDuration)
    )
  }

  private fun placeAudio(clip: AudioClip, place: WindowPlacement): AudioClip {
    if (place.headTrim == 0L && place.sourceDuration == clip.durationMs && place.timelineDuration == clip.durationMs) {
      return if (clip.timelineStartMs == place.parentStart) clip else clip.copy(timelineStartMs = place.parentStart)
    }
    val (srcStart, srcEnd) = trimSourceWindow(
      clip.sourceStartMs, clip.sourceEndMs, clip.isReversed, clip.speed, place.headTrim, place.sourceDuration
    )
    return clip.copy(
      timelineStartMs = place.parentStart,
      durationMs = place.timelineDuration,
      sourceStartMs = srcStart,
      sourceEndMs = srcEnd,
      fadeInMs = (clip.fadeInMs - place.headTrim).coerceAtLeast(0L),
      fadeOutMs = (clip.fadeOutMs - (clip.durationMs - place.headTrim - place.sourceDuration)).coerceAtLeast(0L),
      keyframes = shiftKeyframes(clip.keyframes, place.headTrim, place.sourceDuration, place.timelineDuration)
    )
  }

  private fun placeText(clip: TextClip, place: WindowPlacement): TextClip =
    clip.copy(
      timelineStartMs = place.parentStart,
      durationMs = place.timelineDuration,
      keyframes = shiftKeyframes(clip.keyframes, place.headTrim, place.sourceDuration, place.timelineDuration),
      words = trimWords(clip.words, place.headTrim, place.sourceDuration, place.timelineDuration)
    )

  private fun placeSticker(clip: StickerClip, place: WindowPlacement): StickerClip =
    clip.copy(
      timelineStartMs = place.parentStart,
      durationMs = place.timelineDuration,
      keyframes = shiftKeyframes(clip.keyframes, place.headTrim, place.sourceDuration, place.timelineDuration)
    )

  private fun placeEffect(clip: EffectClip, place: WindowPlacement): EffectClip =
    clip.copy(
      timelineStartMs = place.parentStart,
      durationMs = place.timelineDuration,
      keyframes = shiftKeyframes(clip.keyframes, place.headTrim, place.sourceDuration, place.timelineDuration)
    )

  private fun placeShape(clip: ShapeClip, place: WindowPlacement): ShapeClip =
    clip.copy(
      timelineStartMs = place.parentStart,
      durationMs = place.timelineDuration,
      keyframes = shiftKeyframes(clip.keyframes, place.headTrim, place.sourceDuration, place.timelineDuration)
    )

  private fun trimSourceWindow(
    sourceStart: Long,
    sourceEnd: Long,
    reversed: Boolean,
    speed: Float,
    headTrimMs: Long,
    newDurationMs: Long
  ): Pair<Long, Long> {
    val rate = speed.coerceAtLeast(0.01f)
    val headSrc = (headTrimMs * rate).toLong()
    val spanSrc = (newDurationMs * rate).toLong().coerceAtLeast(1L)
    val lo = min(sourceStart, sourceEnd)
    val hi = max(sourceStart, sourceEnd)
    return if (!reversed) {
      val start = (sourceStart + headSrc).coerceIn(lo, hi)
      start to (start + spanSrc).coerceIn(start, hi).coerceAtLeast(start)
    } else {
      val end = (sourceEnd - headSrc).coerceIn(lo, hi)
      (end - spanSrc).coerceIn(lo, end) to end
    }
  }

  private fun shiftKeyframes(
    keyframes: List<ClipKeyframe>,
    headTrim: Long,
    sourceVisible: Long,
    timelineDuration: Long
  ): List<ClipKeyframe> {
    if (keyframes.isEmpty()) return keyframes
    val visible = sourceVisible.coerceAtLeast(1L)
    return keyframes.mapNotNull { keyframe ->
      val relative = keyframe.timeMs - headTrim
      if (relative < 0L || relative > sourceVisible) null
      else keyframe.copy(timeMs = relative * timelineDuration / visible)
    }
  }

  private fun trimWords(
    words: List<WordTiming>,
    headTrim: Long,
    sourceVisible: Long,
    timelineDuration: Long
  ): List<WordTiming> {
    if (words.isEmpty()) return words
    val visible = sourceVisible.coerceAtLeast(1L)
    val end = headTrim + sourceVisible
    return words.mapNotNull { word ->
      val wordEnd = word.startMs + word.durationMs
      val overlapStart = max(word.startMs, headTrim)
      val overlapEnd = min(wordEnd, end)
      if (overlapEnd <= overlapStart) null
      else word.copy(
        startMs = (overlapStart - headTrim) * timelineDuration / visible,
        durationMs = max(1L, (overlapEnd - overlapStart) * timelineDuration / visible)
      )
    }
  }

  private fun sourceWindow(shell: VideoClip, content: Long): Pair<Long, Long> {
    val hi = content.coerceAtLeast(1L)
    val start = min(shell.sourceStartMs, shell.sourceEndMs).coerceIn(0L, hi)
    val end = max(shell.sourceStartMs, shell.sourceEndMs).coerceIn(start, hi)
    return if (end <= start) 0L to hi else start to end
  }

  private fun findShell(timeline: Timeline, id: String): VideoClip? =
    timeline.videoClips.find { it.id == id } ?: timeline.overlayClips.find { it.id == id }

  private fun replaceShell(timeline: Timeline, shell: VideoClip): Timeline = when {
    timeline.videoClips.any { it.id == shell.id } ->
      timeline.copy(videoClips = timeline.videoClips.map { if (it.id == shell.id) shell else it })
    timeline.overlayClips.any { it.id == shell.id } ->
      timeline.copy(overlayClips = timeline.overlayClips.map { if (it.id == shell.id) shell else it })
    else -> timeline
  }

  private fun cloneCompound(source: CompoundClip, newId: String): CompoundClip =
    source.copy(id = newId, nested = remapTimeline(source.nested, mutableMapOf()))

  private fun remapTimeline(timeline: Timeline, ids: MutableMap<String, String>): Timeline {
    val known = timeline.internalIds()
    fun mapId(old: String) = ids.getOrPut(old) { UUID.randomUUID().toString() }
    fun mapRef(old: String?): String? = old?.let { if (it in known || it in ids) mapId(it) else it }
    return timeline.copy(
      videoClips = timeline.videoClips.map { clip ->
        val newId = mapId(clip.id)
        clip.copy(
          id = newId,
          uri = if (clip.isCompoundContainer()) compoundClipUri(newId) else clip.uri,
          keyframes = freshKeyframes(clip.keyframes)
        )
      },
      overlayClips = timeline.overlayClips.map { clip ->
        val newId = mapId(clip.id)
        clip.copy(
          id = newId,
          uri = if (clip.isCompoundContainer()) compoundClipUri(newId) else clip.uri,
          keyframes = freshKeyframes(clip.keyframes)
        )
      },
      audioClips = timeline.audioClips.map { clip ->
        clip.copy(id = mapId(clip.id), keyframes = freshKeyframes(clip.keyframes))
      },
      textClips = timeline.textClips.map { clip ->
        clip.copy(id = mapId(clip.id), keyframes = freshKeyframes(clip.keyframes))
      },
      stickerClips = timeline.stickerClips.map { clip ->
        clip.copy(id = mapId(clip.id), keyframes = freshKeyframes(clip.keyframes))
      },
      effectClips = timeline.effectClips.map { clip ->
        clip.copy(
          id = mapId(clip.id),
          targetClipId = mapRef(clip.targetClipId),
          keyframes = freshKeyframes(clip.keyframes)
        )
      },
      shapeClips = timeline.shapeClips.map { clip ->
        clip.copy(id = mapId(clip.id), keyframes = freshKeyframes(clip.keyframes))
      },
      compoundClips = timeline.compoundClips.map { compound ->
        compound.copy(id = mapId(compound.id), nested = remapTimeline(compound.nested, ids))
      }
    )
  }

  private fun freshKeyframes(keyframes: List<ClipKeyframe>): List<ClipKeyframe> =
    if (keyframes.isEmpty()) keyframes else keyframes.map { it.copy(id = UUID.randomUUID().toString()) }

  private fun Timeline.internalIds(): Set<String> = buildSet {
    videoClips.forEach { add(it.id) }
    overlayClips.forEach { add(it.id) }
    audioClips.forEach { add(it.id) }
    textClips.forEach { add(it.id) }
    stickerClips.forEach { add(it.id) }
    effectClips.forEach { add(it.id) }
    shapeClips.forEach { add(it.id) }
    compoundClips.forEach { compound ->
      add(compound.id)
      addAll(compound.nested.internalIds())
    }
  }

  private fun VideoClip.shiftStart(delta: Long): VideoClip =
    if (delta == 0L) this else copy(timelineStartMs = (timelineStartMs + delta).coerceAtLeast(0L))

  private fun AudioClip.shiftStart(delta: Long): AudioClip =
    if (delta == 0L) this else copy(timelineStartMs = (timelineStartMs + delta).coerceAtLeast(0L))

  private fun TextClip.shiftStart(delta: Long): TextClip =
    if (delta == 0L) this else copy(timelineStartMs = (timelineStartMs + delta).coerceAtLeast(0L))

  private fun StickerClip.shiftStart(delta: Long): StickerClip =
    if (delta == 0L) this else copy(timelineStartMs = (timelineStartMs + delta).coerceAtLeast(0L))

  private fun EffectClip.shiftStart(delta: Long): EffectClip =
    if (delta == 0L) this else copy(timelineStartMs = (timelineStartMs + delta).coerceAtLeast(0L))

  private fun ShapeClip.shiftStart(delta: Long): ShapeClip =
    if (delta == 0L) this else copy(timelineStartMs = (timelineStartMs + delta).coerceAtLeast(0L))
}
