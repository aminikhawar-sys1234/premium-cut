package com.example.engine.effects.media3

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.TextureView
import android.view.View
import com.example.domain.model.FilterSettings
import com.example.domain.model.Timeline
import com.example.domain.model.VideoClip
import com.example.engine.composition.ColorFilterGenerator

/**
 * Applies the Filters-tools look to the live TextureView without touching ExoPlayer.
 *
 * [ColorFilterGenerator] is the same matrix source GpuCompositionRenderer uses on export,
 * so preview and export stay on one definition. Artistic filters must not go through
 * [androidx.media3.exoplayer.ExoPlayer.setVideoEffects]: that rebuilds the video graph,
 * drops the bound TextureView, freezes the last frame and can mute source audio.
 */
object PreviewDisplayFilter {

  fun matrixFor(clip: VideoClip?, timeline: Timeline): ColorMatrix? {
    val filter = PreviewFilterEffects.effectiveFilter(clip, timeline)
    return matrixFor(filter)
  }

  fun matrixFor(filter: FilterSettings): ColorMatrix? {
    if (!PreviewFilterEffects.isActiveLook(filter)) return null
    return ColorFilterGenerator.getFilterMatrix(filter.type, filter.intensity)
      ?.takeUnless { ColorFilterGenerator.isIdentityMatrix(it) }
  }

  fun apply(view: TextureView, matrix: ColorMatrix?) {
    try {
      if (matrix == null) {
        clear(view)
        return
      }
      val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(matrix)
      }
      view.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
    } catch (t: Throwable) {
      android.util.Log.w("PreviewDisplayFilter", "Failed to apply live filter paint", t)
    }
  }

  fun clear(view: TextureView) {
    try {
      view.setLayerType(View.LAYER_TYPE_NONE, null)
      view.setLayerPaint(null)
    } catch (_: Throwable) {
    }
  }
}
