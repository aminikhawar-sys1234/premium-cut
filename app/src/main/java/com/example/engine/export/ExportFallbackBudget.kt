package com.example.engine.export

/**
 * How long the export may keep asking `MediaMetadataRetriever` for still frames of a clip that no
 * codec could decode.
 *
 * ## Why this is needed
 *
 * When neither the hardware nor the software decoder of a clip produces a picture, the render loop
 * falls back to `MediaMetadataRetriever.getFrameAtTime(OPTION_CLOSEST)` for that frame - on the GL
 * thread that has to keep the encoder fed. That call has **no timeout**: on a cloud-backed
 * `content://` URI (Google Photos, Drive), a large or damaged file, or a decoder already held by the
 * platform, it can block for minutes. Nothing in the pipeline bounded it, so one such call was
 * enough to stop every frame from ever being encoded - the "0 frames for twenty minutes" shape of
 * the 2026-10-16 report.
 *
 * The retriever is a *fallback*, never the correct answer: a clip that reaches it has already failed
 * both codec ladders. So the policy is deliberately narrow and cannot make a working project worse:
 *
 * * every call runs with a deadline ([perCallDeadlineMs], a few seconds),
 * * **the first call that times out retires the retriever for that clip** for the rest of the
 *   attempt - a call that hung once is not a slow call, it is a broken one, and asking again would
 *   only repeat the stall,
 * * slow-but-working retrievers keep working (a clip that answers in 200 ms per frame still exports,
 *   exactly as before).
 *
 * Pure Kotlin (no `android.*`), so the policy is unit-tested ([ExportFallbackBudgetTest]).
 */
class ExportFallbackBudget(
  /** Wall-clock budget of one retriever call; a call that overruns it is a hang, not slowness. */
  val perCallDeadlineMs: Long = DEFAULT_PER_CALL_DEADLINE_MS,
  /** Calls allowed per clip before the fallback is considered "the export's main path" and logged. */
  val slowPathWarningCalls: Int = 30
) {
  private val timedOutClips = HashSet<String>()
  private val callsPerClip = HashMap<String, Int>()
  private val timePerClipMs = HashMap<String, Long>()
  private var timeouts = 0

  /** True while the retriever may still be asked for a frame of [clipId]. */
  fun mayUse(clipId: String): Boolean = clipId !in timedOutClips

  /** Why [clipId] may no longer be used, or null when it may. */
  fun blockedReason(clipId: String): String? =
    if (clipId in timedOutClips) {
      "still-frame extraction was retired for this clip after a call exceeded ${perCallDeadlineMs}ms"
    } else {
      null
    }

  /** Records one completed call; returns true when that call retired the clip. */
  fun recordCall(clipId: String, elapsedMs: Long, timedOut: Boolean): Boolean {
    callsPerClip[clipId] = (callsPerClip[clipId] ?: 0) + 1
    timePerClipMs[clipId] = (timePerClipMs[clipId] ?: 0L) + elapsedMs.coerceAtLeast(0L)
    if (!timedOut) return false
    timeouts++
    return timedOutClips.add(clipId)
  }

  /** True once the clip has used the fallback for more frames than a warning threshold. */
  fun isSlowPath(clipId: String): Boolean = (callsPerClip[clipId] ?: 0) >= slowPathWarningCalls

  fun callsFor(clipId: String): Int = callsPerClip[clipId] ?: 0

  fun timeSpentMs(clipId: String): Long = timePerClipMs[clipId] ?: 0L

  fun timeoutsSeen(): Int = timeouts

  fun retiredClips(): Set<String> = timedOutClips.toSet()

  companion object {
    /**
     * 6 s: a still-frame extraction of a local file is 30-250 ms, of a large 4K file a few hundred
     * ms. Six seconds is >20x a slow-but-working call and still short enough that a hang is caught
     * while the user is watching the panel.
     */
    const val DEFAULT_PER_CALL_DEADLINE_MS = 6_000L
  }
}
