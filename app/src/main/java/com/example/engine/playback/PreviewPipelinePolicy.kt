package com.example.engine.playback

import androidx.media3.common.PlaybackException
import com.example.engine.effects.media3.PreviewEffectApplyPolicy

/**
 * Recovery / session-reset decisions for the live preview pipeline.
 *
 * Kept as a pure policy so unit tests can lock the fallback order without constructing
 * ExoPlayer or a VideoFrameProcessor:
 *   1. restore the last graph that rendered a frame
 *   2. install the identity / passthrough transform
 *   3. recreate ExoPlayer + MediaCodec + VideoFrameProcessor when the processor is dead
 */
object PreviewPipelinePolicy {

  enum class RecoveryStep {
    RESTORE_LAST_GOOD,
    APPLY_IDENTITY,
    RECREATE_PLAYER
  }

  fun nextRecoveryStep(
    lastGoodEffectCount: Int,
    lastRestoreSucceeded: Boolean,
    identityApplySucceeded: Boolean,
    playerHasError: Boolean
  ): RecoveryStep {
    if (lastGoodEffectCount > 0 && !lastRestoreSucceeded && !playerHasError) {
      return RecoveryStep.RESTORE_LAST_GOOD
    }
    if (!identityApplySucceeded && !playerHasError) {
      return RecoveryStep.APPLY_IDENTITY
    }
    if (playerHasError || !identityApplySucceeded) {
      return RecoveryStep.RECREATE_PLAYER
    }
    return RecoveryStep.APPLY_IDENTITY
  }

  fun shouldRecreatePlayer(error: PlaybackException?, applyFailed: Boolean): Boolean {
    if (applyFailed) return true
    val e = error ?: return false
    return PreviewEffectApplyPolicy.isVideoProcessingFailure(e) ||
      e.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
      e.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED
  }

  fun shouldResetOnProjectChange(): Boolean = true
}
