package com.example.engine.preview

/**
 * Preview composition rules. Overlay clips never own an ExoPlayer clock; they only supply
 * decoded frames that [SingleClockPreviewCompositor] blends against the master timeline CTI.
 */
object PreviewCompositorPolicy {

  /** One ExoPlayer is allowed: the main video lane. Overlay PIP must not allocate more. */
  const val OVERLAY_EXOPLAYERS_ENABLED = false

  /** Overlay decoders are seeked to the graph's source time; they never run playWhenReady. */
  const val OVERLAY_DECODERS_FOLLOW_MASTER_CLOCK = true

  fun shouldAllocateOverlayExoPlayer(): Boolean = OVERLAY_EXOPLAYERS_ENABLED

  fun shouldPresentOverlayOnClockTick(): Boolean = OVERLAY_DECODERS_FOLLOW_MASTER_CLOCK
}
