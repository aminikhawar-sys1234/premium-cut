package com.example.engine.effects.media3

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.TextureView
import android.view.View
import com.example.domain.model.FilterSettings
import com.example.domain.model.Timeline
import com.example.domain.model.VideoAdjustments
import com.example.domain.model.VideoClip
import com.example.domain.model.effectiveAdjustments

/**
 * Applies the Filters / Adjust / Video Quality color look to the live TextureView
 * without touching ExoPlayer.
 *
 * [PreviewLookPolicy.displayMatrix] uses the same [com.example.engine.composition.ColorFilterGenerator]
 * path GpuCompositionRenderer uses on export, so preview and export stay on one definition.
 * Artistic filters and color sliders must not go through
 * [androidx.media3.exoplayer.ExoPlayer.setVideoEffects]: that rebuilds the video graph,
 * drops the bound TextureView, freezes the last frame and can mute source audio.
 */
object PreviewDisplayFilter {

  fun matrixFor(clip: VideoClip?, timeline: Timeline): ColorMatrix? {
    return matrixFor(
      clip.effectiveAdjustments(timeline),
      PreviewFilterEffects.effectiveFilter(clip, timeline)
    )
  }

  fun matrixFor(adjustments: VideoAdjustments, filter: FilterSettings): ColorMatrix? =
    PreviewLookPolicy.displayMatrix(adjustments, filter)

  fun matrixFor(filter: FilterSettings): ColorMatrix? =
    matrixFor(VideoAdjustments(), filter)

  fun apply(view: TextureView, matrix: ColorMatrix?) {
    try {
      if (matrix == null) {
        clear(view)
        return
      }
      val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(matrix)
      }
      if (view.layerType != View.LAYER_TYPE_HARDWARE) {
        view.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
      } else {
        view.setLayerPaint(paint)
        view.invalidate()
      }
    } catch (t: Throwable) {
      android.util.Log.w("PreviewDisplayFilter", "Failed to apply live filter paint", t)
    }
  }

  fun clear(view: TextureView) {
    try {
      if (view.layerType != View.LAYER_TYPE_NONE) {
        view.setLayerType(View.LAYER_TYPE_NONE, null)
      }
      view.setLayerPaint(null)
    } catch (t: Throwable) {
      android.util.Log.w("PreviewDisplayFilter", "Failed to clear live filter paint", t)
    }
  }
}
