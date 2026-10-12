package com.example.engine.timeline

import java.util.Locale

/**
 * Pure frame/time conversion used by playhead stepping, split/trim alignment and export.
 * Independent of [com.example.engine.TimelineEngine] state so unit tests can lock the math.
 */
object TimelineFrameMath {

  fun timeToFrame(timeMs: Long, fps: Int): Long {
    val safeFps = fps.coerceAtLeast(1)
    return Math.round(timeMs.toDouble() * safeFps.toDouble() / 1000.0).toLong().coerceAtLeast(0L)
  }

  fun frameToTime(frameIndex: Long, fps: Int): Long {
    val safeFps = fps.coerceAtLeast(1)
    return Math.round(frameIndex.toDouble() * 1000.0 / safeFps.toDouble()).toLong().coerceAtLeast(0L)
  }

  fun alignToFrame(timeMs: Long, fps: Int): Long = frameToTime(timeToFrame(timeMs, fps), fps)

  fun frameDurationMs(fps: Int): Long = (1000L / fps.coerceAtLeast(1)).coerceAtLeast(1L)

  fun frameDurationUs(fps: Int): Long = (1_000_000L / fps.coerceAtLeast(1)).coerceAtLeast(1L)

  fun timeUsToFrame(timeUs: Long, fps: Int): Long =
    (timeUs * fps.coerceAtLeast(1) + 500_000L) / 1_000_000L

  fun frameToTimeUs(frameIndex: Long, fps: Int): Long {
    val safeFps = fps.coerceAtLeast(1)
    return (frameIndex * 1_000_000L + (safeFps / 2)) / safeFps
  }

  fun timeMsToMicros(timeMs: Long): Long = timeMs * 1000L

  fun microsToTimeMs(timeUs: Long): Long = timeUs / 1000L

  fun microsToFrame(timeUs: Long, fps: Int): Long = timeToFrame(microsToTimeMs(timeUs), fps)

  fun frameToMicros(frameIndex: Long, fps: Int): Long = frameToTimeUs(frameIndex, fps)

  fun formatTimecode(timeMs: Long, fps: Int): String {
    val safeFps = fps.coerceAtLeast(1)
    val totalFrames = timeToFrame(timeMs, safeFps)
    val frames = (totalFrames % safeFps).toInt()
    val totalSeconds = totalFrames / safeFps
    val seconds = (totalSeconds % 60).toInt()
    val minutes = ((totalSeconds / 60) % 60).toInt()
    val hours = (totalSeconds / 3600).toInt()
    return String.format(Locale.US, "%02d:%02d:%02d:%02d", hours, minutes, seconds, frames)
  }
}
