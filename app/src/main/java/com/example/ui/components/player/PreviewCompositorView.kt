package com.example.ui.components.player

import android.graphics.SurfaceTexture
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import com.example.engine.playback.VideoPlaybackEngine

/**
 * Output of the GPU preview compositor. The compositor draws the whole project frame (all video
 * layers, grading, transitions, effects) at the size of this view, so it fills the preview canvas.
 */
@Composable
fun PreviewCompositorView(engine: VideoPlaybackEngine, modifier: Modifier = Modifier) {
  AndroidView(
    factory = { ctx ->
      TextureView(ctx).apply {
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
          override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
            engine.attachCompositorSurface(st, w, h)
          }
          override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
            engine.attachCompositorSurface(st, w, h)
          }
          override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
            engine.detachCompositorSurface(st)
            return true
          }
          override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
      }
    },
    onRelease = { tv -> tv.surfaceTexture?.let(engine::detachCompositorSurface) },
    modifier = modifier.testTag("preview_compositor_surface")
  )
}
