package com.example.engine.timeline

import java.util.Locale

/**
 * Frame <-> time conversions shared by the timeline, playback and export.
 * Millisecond conversions round to the nearest frame; microsecond conversions use integer
 * rounding so a frame index survives a round trip at any fps.
 */
object TimelineFrameMath {

  fun timeToFrame(timeMs: Long, fps: Int): Long =
    Math.round(timeMs.toDouble() * fps.toDouble() / 1000.0).coerceAtLeast(0L)

  fun frameToTime(frameIndex: Long, fps: Int): Long =
    Math.round(frameIndex.toDouble() * 1000.0 / fps.toDouble()).coerceAtLeast(0L)

  fun alignToFrame(timeMs: Long, fps: Int): Long = frameToTime(timeToFrame(timeMs, fps), fps)

  fun frameDurationMs(fps: Int): Long = (1000L / fps.coerceAtLeast(1)).coerceAtLeast(1L)

  fun frameDurationUs(fps: Int): Long = (1_000_000L / fps.coerceAtLeast(1)).coerceAtLeast(1L)

  fun timeUsToFrame(timeUs: Long, fps: Int): Long = (timeUs * fps + 500_000L) / 1_000_000L

  fun frameToTimeUs(frameIndex: Long, fps: Int): Long = (frameIndex * 1_000_000L + (fps / 2)) / fps

  /** Truncating conversion (floor), used by bridge APIs that address whole decoded frames. */
  fun frameToMicros(frame: Long, fps: Int): Long = (frame * 1_000_000L) / fps.coerceAtLeast(1)

  fun microsToFrame(micros: Long, fps: Int): Long = (micros * fps.coerceAtLeast(1)) / 1_000_000L

  /** `MM:SS:FF`, or `HH:MM:SS:FF` once the time reaches an hour. */
  fun formatTimecode(timeMs: Long, fps: Int): String {
    val totalFrames = timeToFrame(timeMs, fps)
    val frames = (totalFrames % fps).toInt()
    val totalSeconds = totalFrames / fps
    val seconds = (totalSeconds % 60).toInt()
    val minutes = ((totalSeconds / 60) % 60).toInt()
    val hours = (totalSeconds / 3600).toInt()
    return if (hours > 0) {
      String.format(Locale.US, "%02d:%02d:%02d:%02d", hours, minutes, seconds, frames)
    } else {
      String.format(Locale.US, "%02d:%02d:%02d", minutes, seconds, frames)
    }
  }
}
