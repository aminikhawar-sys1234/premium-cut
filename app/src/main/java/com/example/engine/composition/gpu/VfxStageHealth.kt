package com.example.engine.composition.gpu

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Session-level health of the GPU effect stages, consumed by the effects UI.
 *
 * When a stage quarantines an effect runtime (GLSL failed to compile / a pass threw on this
 * specific device), the video silently keeps playing without that effect - historically the
 * number one "effects are fake / live preview does not show" report. Surfacing the quarantined
 * ids lets the panel TELL the user that this asset is not renderable on their device instead
 * of leaving a silent no-op.
 *
 * Marks are additive for the session (a stack change clears per-stage quarantine, but the
 * badge stays until the process restarts - a GPU that failed once will fail again).
 */
object VfxStageHealth {

  private val _quarantinedIds = MutableStateFlow<Set<String>>(emptySet())

  /** Engine effect ids that at least one GPU stage could not render this session. */
  val quarantinedIds: StateFlow<Set<String>> = _quarantinedIds.asStateFlow()

  fun markQuarantined(effectId: String) {
    _quarantinedIds.value = _quarantinedIds.value + effectId
  }
}
