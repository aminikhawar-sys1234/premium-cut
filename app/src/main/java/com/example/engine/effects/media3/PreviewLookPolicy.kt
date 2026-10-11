package com.example.engine.effects.media3

import com.example.domain.model.FilterSettings
import com.example.domain.model.Timeline
import com.example.domain.model.VideoAdjustments
import com.example.domain.model.VideoClip
import com.example.engine.composition.ColorFilterGenerator
import kotlin.math.abs

/**
 * Latest look the Filters / Adjust / Video Quality sliders have published.
 * The TextureView paints this immediately; the timeline is updated later (latest-wins).
 */
data class LivePreviewLook(
  val clipId: String?,
  val adjustments: VideoAdjustments,
  val filter: FilterSettings
)

/**
 * Splits a Filters-tools look into:
 * - Color-matrix work that the live TextureView can paint without touching ExoPlayer
 * - Spatial / LUT / VFX work that still needs Media3 (never on every slider tick)
 */
object PreviewLookPolicy {
  const val LOOK_DEBOUNCE_MS = 120L

  /**
   * Neighbor-pixel operations that a ColorMatrix cannot represent. These stay on the
   * Media3 / export shader path; color sliders must not rebuild that graph.
   */
  fun spatialAdjustments(src: VideoAdjustments): VideoAdjustments = VideoAdjustments(
    vignette = src.vignette,
    grain = src.grain,
    sharpness = src.sharpness,
    clarity = src.clarity,
    superClarity = src.superClarity,
    denoise = src.denoise,
    antiFlicker = src.antiFlicker
  )

  fun hasSpatialLook(adj: VideoAdjustments): Boolean =
    adj.vignette > 0.01f ||
      adj.grain > 0.01f ||
      adj.sharpness > 0.01f ||
      adj.clarity > 0.01f ||
      adj.superClarity > 0.01f ||
      adj.denoise > 0.01f ||
      adj.antiFlicker > 0.01f

  fun hasAnyLook(adjustments: VideoAdjustments, filter: FilterSettings): Boolean =
    hasDisplayLook(adjustments, filter) || hasSpatialLook(adjustments)

  fun hasDisplayLook(adjustments: VideoAdjustments, filter: FilterSettings): Boolean {
    if (PreviewFilterEffects.isActiveLook(filter)) return true
    return abs(adjustments.brightness) > 0.01f ||
      abs(adjustments.contrast - 1f) > 0.01f ||
      abs(adjustments.saturation - 1f) > 0.01f ||
      abs(adjustments.exposure) > 0.01f ||
      abs(adjustments.temperature) > 0.01f ||
      abs(adjustments.tint) > 0.01f ||
      abs(adjustments.highlights) > 0.01f ||
      abs(adjustments.shadows) > 0.01f ||
      abs(adjustments.whites) > 0.01f ||
      abs(adjustments.blacks) > 0.01f ||
      adjustments.fade > 0.01f ||
      adjustments.autoEnhance > 0.01f ||
      adjustments.hdrBoost > 0.01f ||
      adjustments.colorCorrect > 0.01f ||
      adjustments.colorFix > 0.01f
  }

  fun displayMatrix(adjustments: VideoAdjustments, filter: FilterSettings): android.graphics.ColorMatrix? {
    if (!hasDisplayLook(adjustments, filter)) return null
    val combined = ColorFilterGenerator.createCombinedMatrix(adjustments, FilterSettings(), filter)
    return combined.takeUnless { ColorFilterGenerator.isIdentityMatrix(it) }
  }

  fun appliesTo(look: LivePreviewLook?, clipId: String?): Boolean {
    if (look == null) return false
    return look.clipId == null || look.clipId == clipId
  }

  /**
   * True when [next] differs from [prev] only in filter / adjustments / LUT / VFX look fields.
   * Structural edits (trim, add clip, speed, volume, …) must still rebuild playback.
   */
  fun isLookOnlyChange(prev: Timeline, next: Timeline): Boolean {
    if (prev.audioClips != next.audioClips) return false
    if (prev.textClips != next.textClips) return false
    if (prev.stickerClips != next.stickerClips) return false
    if (prev.effectClips != next.effectClips) return false
    if (prev.shapeClips != next.shapeClips) return false
    if (prev.transitions != next.transitions) return false
    if (prev.chromaKey != next.chromaKey) return false
    if (prev.canvasBackgroundColor != next.canvasBackgroundColor) return false
    if (prev.aspectRatio != next.aspectRatio) return false
    if (prev.trackSettings != next.trackSettings) return false
    if (prev.tracks != next.tracks) return false
    if (prev.markers != next.markers) return false
    if (prev.captionSpeakers != next.captionSpeakers) return false
    if (prev.version != next.version) return false
    return clipsLookOnly(prev.videoClips, next.videoClips) &&
      clipsLookOnly(prev.overlayClips, next.overlayClips)
  }

  fun applyLookToTimeline(timeline: Timeline, look: LivePreviewLook): Timeline {
    val clipId = look.clipId
    if (clipId != null &&
      (timeline.videoClips.any { it.id == clipId } || timeline.overlayClips.any { it.id == clipId })
    ) {
      return timeline.copy(
        videoClips = timeline.videoClips.map {
          if (it.id == clipId) it.copy(adjustments = look.adjustments, filter = look.filter) else it
        },
        overlayClips = timeline.overlayClips.map {
          if (it.id == clipId) it.copy(adjustments = look.adjustments, filter = look.filter) else it
        }
      )
    }
    return timeline.copy(adjustments = look.adjustments, filter = look.filter)
  }

  private fun clipsLookOnly(previous: List<VideoClip>, next: List<VideoClip>): Boolean {
    if (previous.size != next.size) return false
    return previous.zip(next).all { (left, right) ->
      left.copy(
        filter = null,
        adjustments = null,
        colorGradeJson = null,
        vfxStackJson = null
      ) == right.copy(
        filter = null,
        adjustments = null,
        colorGradeJson = null,
        vfxStackJson = null
      )
    }
  }
}
