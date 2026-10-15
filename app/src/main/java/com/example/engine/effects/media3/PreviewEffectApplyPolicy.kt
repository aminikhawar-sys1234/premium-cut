package com.example.engine.effects.media3

import android.graphics.ColorMatrix
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import com.example.domain.model.FilterSettings
import com.example.domain.model.Timeline
import com.example.domain.model.VideoAdjustments
import com.example.domain.model.VideoClip
import com.example.domain.model.effectiveAdjustments

/**
 * Decides when the live preview may call ExoPlayer.setVideoEffects.
 *
 * Calling setVideoEffects rebuilds Media3's video renderer / EGL graph. Doing that on
 * every Filters / Adjust / Video Quality slider tick drops the bound TextureView, can
 * fail the video frame processor (black preview), and can error the same ExoPlayer
 * instance that plays source audio.
 *
 * Value changes (brightness, a different preset, denoise amount, …) update
 * [ColorGradingGlEffect] uniforms in place. The preview graph always keeps a
 * resident grading node so turning a look on or off never calls setVideoEffects.
 * The graph is rebuilt only when LUT id or catalog VFX nodes change.
 */
@OptIn(UnstableApi::class)
object PreviewEffectApplyPolicy {

  const val SURFACE_REBIND_DELAY_MS = 48L

  fun needsGrading(adjustments: VideoAdjustments, filter: FilterSettings): Boolean =
    PreviewLookPolicy.hasDisplayLook(adjustments, filter) ||
      PreviewLookPolicy.hasSpatialLook(adjustments)

  fun needsGrading(clip: VideoClip?, timeline: Timeline): Boolean =
    needsGrading(clip.effectiveAdjustments(timeline), PreviewFilterEffects.effectiveFilter(clip, timeline))

  /**
   * Structural Media3 graph identity. The grading node is always present (`G`).
   * Slider / intensity / colour / filter-preset values are omitted — those are
   * published through [ColorGradingGlEffect.publishLive].
   */
  fun graphSignature(clip: VideoClip?, timeline: Timeline): String {
    val lut = PreviewFilterEffects.lutLook(clip)
    return buildString {
      append('G')
      append('|')
      append(lut?.first.orEmpty())
      append('|')
      append(PreviewVfxEffects.signature(clip))
    }
  }

  /** Delegates to [PreviewGraphDecision] so there is exactly one definition of "the graph changed". */
  fun shouldRebuildGraph(previousGraphSignature: String?, nextGraphSignature: String, force: Boolean): Boolean =
    PreviewGraphDecision.shouldRebuildGraph(previousGraphSignature, nextGraphSignature, force)

  /**
   * Never enable Media3's effects renderer with an empty list just to "apply nothing".
   * That swap is what invalidated the decoder surface and silenced source audio.
   * Clearing a previously applied graph is required when the look is reset.
   *
   * Delegates to [PreviewGraphDecision]. Callers that also track whether a grading node is resident
   * must use [PreviewGraphDecision.graphChanged] for `graphChanged` — passing a value recalculated
   * from the signature alone is what let a missing resident node be ignored here.
   */
  fun shouldCallSetVideoEffects(
    newEffectCount: Int,
    previousHadEffects: Boolean,
    graphChanged: Boolean,
    force: Boolean
  ): Boolean =
    PreviewGraphDecision.outcomeFor(
      graphChanged = graphChanged,
      signatureChanged = graphChanged,
      residentGradingNode = true,
      newEffectCount = newEffectCount,
      previousHadEffects = previousHadEffects,
      force = force
    ).callSetVideoEffects

  /**
   * `setVideoEffects` on a freshly recreated player with no TextureView builds a dead
   * VideoFrameProcessor: playhead and text overlays keep moving, picture stays black,
   * and source audio is silenced. Wait for a bound surface (or STATE_READY) first.
   */
  fun shouldDeferLookApply(surfaceBound: Boolean): Boolean = !surfaceBound

  @OptIn(UnstableApi::class)
  fun isVideoProcessingFailure(error: PlaybackException): Boolean {
    return error.errorCode == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED ||
      error.errorCode == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED
  }

  /** Last graph that rendered a frame, or the identity passthrough when none exists. */
  fun recoveryEffects(lastGood: List<androidx.media3.common.Effect>): List<androidx.media3.common.Effect> =
    if (lastGood.isNotEmpty()) lastGood else Media3EffectPipeline.identityEffects()

  fun shouldRecreatePlayer(applyFailed: Boolean, playerHasError: Boolean): Boolean =
    applyFailed || playerHasError

  /**
   * Video SurfaceTextures often carry A=0. A ColorMatrix that preserves alpha then
   * composites as fully transparent — the user sees a black preview. Force opaque.
   */
  fun forceOpaqueAlpha(src: ColorMatrix): ColorMatrix {
    val out = ColorMatrix(src)
    val a = out.array
    a[15] = 0f
    a[16] = 0f
    a[17] = 0f
    a[18] = 0f
    a[19] = 255f
    return out
  }

  fun displayMatrix(adjustments: VideoAdjustments, filter: FilterSettings): ColorMatrix? =
    PreviewLookPolicy.displayMatrix(adjustments, filter)
}
