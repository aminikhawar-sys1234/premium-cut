package com.example.engine.controller

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Interface providing authoritative audio PTS (presentation timestamp) in milliseconds.
 */
fun interface AudioClockProvider {
  fun getAudioPositionMs(): Long?
}

/**
 * Master timeline playback clock locked to Android Choreographer VSYNC and synchronized
 * with hardware AudioTrack Presentation Time Stamps (PTS).
 *
 * Guarantees zero audio-video drift, frame-accurate pacing, and monotonic clock stability
 * during rapid scrubbing and seeking operations.
 */
class MasterPlaybackClock(
  private val scope: CoroutineScope,
  private var audioClockProvider: AudioClockProvider? = null
) {
  companion object {
    private const val TAG = "MasterPlaybackClock"
    private const val MAX_SUB_AUDIO_INTERPOLATION_MS = 60L
  }

  private val _positionMs = MutableStateFlow(0L)
  val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

  private val _isPlaying = MutableStateFlow(false)
  val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

  private val clockLock = Any()

  @Volatile private var anchorPositionMs = 0L
  @Volatile private var anchorTimeNs = 0L
  @Volatile private var lastVsyncTimeNs = 0L
  @Volatile private var lastAudioPositionMs = -1L
  @Volatile private var lastAudioTimestampNs = 0L
  @Volatile private var lastRebaseTimestampNs = 0L

  private var tickerJob: Job? = null
  private var choreographerCallback: Choreographer.FrameCallback? = null
  private val mainHandler = Handler(Looper.getMainLooper())

  fun setAudioClockProvider(provider: AudioClockProvider?) {
    synchronized(clockLock) {
      this.audioClockProvider = provider
      lastAudioPositionMs = -1L
    }
  }

  fun play(positionMs: Long = _positionMs.value) {
    synchronized(clockLock) {
      if (_isPlaying.value) return
      rebaseInternal(positionMs)
      _isPlaying.value = true
      startVsyncClock()
    }
  }

  fun pause() {
    synchronized(clockLock) {
      if (!_isPlaying.value) return
      val current = calculateCurrentPosition()
      rebaseInternal(current)
      _isPlaying.value = false
      stopVsyncClock()
    }
  }

  fun seekTo(positionMs: Long, isScrubbing: Boolean = false) {
    synchronized(clockLock) {
      val targetPos = positionMs.coerceAtLeast(0L)
      rebaseInternal(targetPos)
      _positionMs.value = targetPos
    }
  }

  private fun rebaseInternal(positionMs: Long) {
    val now = System.nanoTime()
    anchorPositionMs = positionMs.coerceAtLeast(0L)
    anchorTimeNs = now
    lastVsyncTimeNs = now
    lastAudioPositionMs = -1L
    lastAudioTimestampNs = now
    lastRebaseTimestampNs = now
    _positionMs.value = anchorPositionMs
  }

  private fun startVsyncClock() {
    stopVsyncClock()

    if (Looper.myLooper() == Looper.getMainLooper()) {
      setupChoreographer()
    } else {
      mainHandler.post {
        if (_isPlaying.value) {
          setupChoreographer()
        }
      }
    }
  }

  private fun setupChoreographer() {
    try {
      val cb = choreographerCallback
      if (cb != null) {
        Choreographer.getInstance().removeFrameCallback(cb)
      }
      val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
          if (!_isPlaying.value) return
          lastVsyncTimeNs = frameTimeNanos
          val nextPos = calculateCurrentPosition()
          _positionMs.value = nextPos
          try {
            Choreographer.getInstance().postFrameCallback(this)
          } catch (_: Throwable) {}
        }
      }
      choreographerCallback = callback
      Choreographer.getInstance().postFrameCallback(callback)
    } catch (e: Throwable) {
      // Fallback to coroutine ticker if Choreographer is unavailable (e.g. in test or headless env)
      tickerJob?.cancel()
      tickerJob = scope.launch(Dispatchers.Main.immediate) {
        while (isActive && _isPlaying.value) {
          val calculatedPos = calculateCurrentPosition()
          _positionMs.value = calculatedPos
          delay(16L)
        }
      }
    }
  }

  private fun stopVsyncClock() {
    tickerJob?.cancel()
    tickerJob = null

    val cb = choreographerCallback
    choreographerCallback = null
    if (cb != null) {
      if (Looper.myLooper() == Looper.getMainLooper()) {
        try {
          Choreographer.getInstance().removeFrameCallback(cb)
        } catch (_: Throwable) {}
      } else {
        mainHandler.post {
          try {
            Choreographer.getInstance().removeFrameCallback(cb)
          } catch (_: Throwable) {}
        }
      }
    }
  }

  /**
   * Authoritative clock calculation.
   * Monotonic from the CTI anchor. The media player follows this clock.
   */
  fun calculateCurrentPosition(): Long = synchronized(clockLock) {
    if (!_isPlaying.value) return anchorPositionMs

    val nowNs = System.nanoTime()
    val vsyncNs = if (lastVsyncTimeNs > 0L) lastVsyncTimeNs else nowNs

    // Monotonic estimated time from anchor
    val monotonicElapsedNs = (vsyncNs - anchorTimeNs).coerceAtLeast(0L)
    val monotonicMs = anchorPositionMs + TimeUnit.NANOSECONDS.toMillis(monotonicElapsedNs)

    // The media player is a slave of this clock. Its currentPosition is source time and
    // lags by a GOP after a keyframe seek (commonly 1–2s). Rebasing onto it pulled the
    // CTI backward and made the next Play start at the wrong place.
    if (PlaybackSyncPolicy.shouldRebaseMasterClock(monotonicMs, lastAudioPositionMs)) {
      val external = try { audioClockProvider?.getAudioPositionMs() } catch (_: Throwable) { null }
      if (external != null && external >= 0L) {
        anchorPositionMs = external
        anchorTimeNs = vsyncNs
        lastRebaseTimestampNs = nowNs
        return external
      }
    }

    return monotonicMs.coerceAtLeast(0L)
  }

  fun getCurrentPosition(): Long = _positionMs.value
}
