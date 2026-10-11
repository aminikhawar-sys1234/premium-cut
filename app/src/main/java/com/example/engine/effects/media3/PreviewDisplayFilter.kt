package com.example.engine.effects.media3

import android.graphics.ColorMatrix
import android.os.Build
import android.view.TextureView
import com.example.domain.model.FilterSettings
import com.example.domain.model.Timeline
import com.example.domain.model.VideoAdjustments
import com.example.domain.model.VideoClip
import com.example.domain.model.effectiveAdjustments

/**
 * Color-matrix helper for still images, filter thumbnails, and export parity tests.
 *
 * The live ExoPlayer [TextureView] must NOT receive a ColorMatrix layer paint.
 * TextureView already owns a SurfaceTexture; `setLayerPaint(ColorMatrixColorFilter)`
 * composites video frames with A=0 as fully transparent (black preview) the moment
 * playback starts updating the surface. Filters / Adjust / Video Quality are applied
 * by [ColorGradingGlEffect] so the decoder, EGL surface and source audio stay intact.
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

  /**
   * Clears any leftover TextureView color paint. Does not apply [matrix] — that path
   * is what produced the black live preview.
   */
  fun apply(view: TextureView, matrix: ColorMatrix?) {
    clear(view)
    if (matrix != null) {
      android.util.Log.d(
        "PreviewDisplayFilter",
        "Live video ColorMatrix is applied by ColorGradingGlEffect, not TextureView paint"
      )
    }
  }

  fun clear(view: TextureView) {
    try {
      view.isOpaque = true
      view.setLayerPaint(null)
      if (Build.VERSION.SDK_INT >= 31) {
        view.setRenderEffect(null)
      }
    } catch (t: Throwable) {
      android.util.Log.e("PreviewDisplayFilter", "Failed to clear live filter paint", t)
    }
  }
}
