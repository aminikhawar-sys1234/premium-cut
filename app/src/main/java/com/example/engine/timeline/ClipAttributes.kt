package com.example.engine.timeline

import com.example.domain.model.AudioEffectsSettings
import com.example.domain.model.ClipAnimationSettings
import com.example.domain.model.FilterSettings
import com.example.domain.model.MaskSettings
import com.example.domain.model.VideoAdjustments
import com.example.domain.model.VideoClip

/** Groups of attributes that "Paste attributes" can copy between clips. */
enum class AttributeGroup(val label: String) {
  LOOK("Filter, adjust, color, effects"),
  MOTION("Animation, transform, opacity, mask"),
  AUDIO("Volume and audio effects")
}

/**
 * A snapshot of the look of one video clip that can be stamped onto other clips. Everything tied to
 * the clip's own footage (speed, trim, motion-tracking data, stabilizer data, keyframes) is
 * deliberately left out because it would be wrong on a different clip.
 */
data class ClipAttributes(
  val filter: FilterSettings?,
  val adjustments: VideoAdjustments?,
  val colorGradeJson: String?,
  val vfxStackJson: String?,
  val faceReshape: String?,
  val bodyReshape: String?,
  val arOverlay: String?,
  val animation: ClipAnimationSettings,
  val opacity: Float,
  val blendMode: String,
  val mask: MaskSettings,
  val cropScale: Float,
  val cropOffsetX: Float,
  val cropOffsetY: Float,
  val rotationDegrees: Int,
  val flipHorizontal: Boolean,
  val flipVertical: Boolean,
  val motionBlurEnabled: Boolean,
  val volume: Float,
  val isMuted: Boolean,
  val audioEffects: AudioEffectsSettings
) {
  fun applyTo(clip: VideoClip, groups: Set<AttributeGroup> = AttributeGroup.values().toSet()): VideoClip {
    var out = clip
    if (AttributeGroup.LOOK in groups) {
      out = out.copy(
        filter = filter,
        adjustments = adjustments,
        colorGradeJson = colorGradeJson,
        vfxStackJson = vfxStackJson,
        faceReshape = faceReshape,
        bodyReshape = bodyReshape,
        arOverlay = arOverlay
      )
    }
    if (AttributeGroup.MOTION in groups) {
      out = out.copy(
        animation = animation,
        opacity = opacity,
        blendMode = blendMode,
        mask = mask,
        cropScale = cropScale,
        cropOffsetX = cropOffsetX,
        cropOffsetY = cropOffsetY,
        rotationDegrees = rotationDegrees,
        flipHorizontal = flipHorizontal,
        flipVertical = flipVertical,
        motionBlurEnabled = motionBlurEnabled
      )
    }
    if (AttributeGroup.AUDIO in groups) {
      out = out.copy(volume = volume, isMuted = isMuted, audioEffects = audioEffects)
    }
    return out
  }

  companion object {
    fun from(clip: VideoClip) = ClipAttributes(
      filter = clip.filter,
      adjustments = clip.adjustments,
      colorGradeJson = clip.colorGradeJson,
      vfxStackJson = clip.vfxStackJson,
      faceReshape = clip.faceReshape,
      bodyReshape = clip.bodyReshape,
      arOverlay = clip.arOverlay,
      animation = clip.animation,
      opacity = clip.opacity,
      blendMode = clip.blendMode,
      mask = clip.mask,
      cropScale = clip.cropScale,
      cropOffsetX = clip.cropOffsetX,
      cropOffsetY = clip.cropOffsetY,
      rotationDegrees = clip.rotationDegrees,
      flipHorizontal = clip.flipHorizontal,
      flipVertical = clip.flipVertical,
      motionBlurEnabled = clip.motionBlurEnabled,
      volume = clip.volume,
      isMuted = clip.isMuted,
      audioEffects = clip.audioEffects
    )
  }
}
