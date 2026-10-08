package com.example.engine.timeline

/**
 * Shared start-inclusive / end-exclusive clip activity test used by preview, playback and export
 * so a layer never appears for one extra millisecond on the paused preview that export omits.
 */
object TimelineClipVisibility {
  fun isActiveAt(positionMs: Long, timelineStartMs: Long, durationMs: Long): Boolean {
    val endMs = timelineStartMs + durationMs
    return positionMs >= timelineStartMs && positionMs < endMs
  }
}
