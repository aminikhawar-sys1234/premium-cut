package com.example.engine.ai.cutout

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-clip background-removal tuning. Whether removal is ON lives on the clip itself
 * ([com.example.domain.model.VideoClip.isBackgroundRemoved]); this only holds the look.
 */
data class BgRemoveParams(
  /** 0..1 - higher cuts tighter (removes more of the uncertain edge). */
  val strength: Float = 0.5f,
  /** 0..1 - edge feathering. */
  val softness: Float = 0.35f,
  /** What replaces the removed background, see the MODE_* constants. */
  val mode: Int = MODE_TRANSPARENT,
  /** ARGB colour used when [mode] == [MODE_COLOR]. */
  val bgColor: Int = 0xFF000000.toInt()
) {
  companion object {
    const val MODE_TRANSPARENT = 0
    const val MODE_COLOR = 1
    const val MODE_BLUR = 2
  }
}

/** Compact text form stored on the clip so the tuning saves, loads and undoes with the timeline. */
object BgRemoveCodec {
  fun encode(p: BgRemoveParams): String? =
    if (p == BgRemoveParams()) null
    else "${p.strength},${p.softness},${p.mode},${p.bgColor}"

  fun decode(s: String?): BgRemoveParams {
    if (s.isNullOrBlank()) return BgRemoveParams()
    val v = s.split(',').map { it.trim() }
    val d = BgRemoveParams()
    return BgRemoveParams(
      strength = v.getOrNull(0)?.toFloatOrNull()?.coerceIn(0f, 1f) ?: d.strength,
      softness = v.getOrNull(1)?.toFloatOrNull()?.coerceIn(0f, 1f) ?: d.softness,
      mode = v.getOrNull(2)?.toIntOrNull()?.coerceIn(0, 2) ?: d.mode,
      bgColor = v.getOrNull(3)?.toIntOrNull() ?: d.bgColor
    )
  }
}

enum class CutoutStatus {
  IDLE,
  /** A mask is being computed in the background. */
  WORKING,
  /** The on-device model could not run (e.g. not downloaded yet); frames pass through untouched. */
  UNAVAILABLE
}

/**
 * Process-wide bridge between the editor UI and the GL compositor.
 * Preview and export both read the same registry, so what you see is what you export.
 */
object SubjectCutoutRegistry {
  @Volatile private var params: Map<String, BgRemoveParams> = emptyMap()

  /** Bumped whenever the set of cut-out clips changes, so stale masks are never reused. */
  @Volatile var epoch: Int = 0
    private set

  private val _status = MutableStateFlow(CutoutStatus.IDLE)
  val status: StateFlow<CutoutStatus> = _status.asStateFlow()

  /** Invoked off the GL thread with a clip id when a fresh mask is ready (paused preview should re-render). */
  @Volatile var onMaskReady: ((String) -> Unit)? = null

  fun update(newParams: Map<String, BgRemoveParams>) {
    if (newParams.keys != params.keys) {
      epoch++
      // A fresh enable/disable is the user's retry: give the model another chance.
      _status.value = CutoutStatus.IDLE
    }
    params = newParams
  }

  fun paramsFor(clipId: String?): BgRemoveParams? = clipId?.let { params[it] }

  fun isActive(clipId: String): Boolean = params.containsKey(clipId)

  fun setStatus(s: CutoutStatus) { _status.value = s }

  /** Lets the panel's Retry button clear an UNAVAILABLE state. */
  fun retry() {
    epoch++
    _status.value = CutoutStatus.IDLE
  }

  fun clear() {
    params = emptyMap()
    epoch++
    _status.value = CutoutStatus.IDLE
    onMaskReady = null
  }
}
