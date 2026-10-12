package com.example.engine.composition.graph

import com.example.domain.model.Timeline
import com.example.domain.model.TrackType
import com.example.domain.model.timelineToSourceMs
import com.example.engine.composition.ComposedFrame
import com.example.engine.timeline.TimelineClipQueries
import com.example.engine.timeline.TimelineEvaluator
import com.example.engine.timeline.TimelineFrameMath
import com.example.engine.timeline.TimelineFrameState

/**
 * Single render graph used by preview and export.
 *
 * Decoders never own a clock: they only supply a frame at [CompositionLayer.sourcePosMs].
 * [SharedCompositionGraph.clockMs] is the one timeline clock both pipelines sample.
 */
data class CompositionLayer(
  val id: String,
  val kind: Kind,
  val clipId: String,
  val sourcePosMs: Long,
  val zIndex: Int,
  val opacity: Float
) {
  enum class Kind { MAIN_VIDEO, OVERLAY, TEXT, STICKER, EFFECT, AUDIO, TRANSITION }
}

data class SharedCompositionGraph(
  val clockMs: Long,
  val frameIndex: Long,
  val fps: Int,
  val layers: List<CompositionLayer>,
  val frameState: TimelineFrameState,
  val composedFrame: ComposedFrame
) {
  val overlayLayerIds: Set<String>
    get() = layers.filter { it.kind == CompositionLayer.Kind.OVERLAY }.map { it.clipId }.toSet()

  companion object {
    private val evaluator = TimelineEvaluator()

    /**
     * Evaluates the authoritative timeline at [clockMs]. Preview and export must call this
     * (or [com.example.engine.composition.VideoCompositionEngine.evaluateFrame], which uses the
     * same [TimelineEvaluator]) so "what you see is what you export".
     */
    fun evaluate(timeline: Timeline, clockMs: Long, fps: Int = 30): SharedCompositionGraph {
      val pos = clockMs.coerceAtLeast(0L)
      val state = evaluator.evaluate(timeline, pos)
      val frame = state.toComposedFrame()
      val layers = ArrayList<CompositionLayer>(8)
      var z = 0
      val main = frame.activeClip
      if (main != null) {
        layers += CompositionLayer(
          id = "main:${main.id}",
          kind = CompositionLayer.Kind.MAIN_VIDEO,
          clipId = main.id,
          sourcePosMs = frame.clipSourcePosMs,
          zIndex = z++,
          opacity = frame.activeClipTransform?.opacity ?: 1f
        )
      }
      if (frame.activeTransition != null) {
        layers += CompositionLayer(
          id = "transition:${frame.activeTransition.clipBefore.id}:${frame.activeTransition.clipAfter.id}",
          kind = CompositionLayer.Kind.TRANSITION,
          clipId = frame.activeTransition.clipAfter.id,
          sourcePosMs = frame.activeTransition.clipAfter.timelineToSourceMs(pos),
          zIndex = z++,
          opacity = frame.activeTransition.progress
        )
      }
      for (overlay in frame.activeOverlays) {
        layers += CompositionLayer(
          id = "overlay:${overlay.clip.id}",
          kind = CompositionLayer.Kind.OVERLAY,
          clipId = overlay.clip.id,
          sourcePosMs = overlay.sourcePosMs,
          zIndex = z++,
          opacity = overlay.opacity
        )
      }
      for (effect in frame.activeEffects) {
        layers += CompositionLayer(
          id = "effect:${effect.clip.id}",
          kind = CompositionLayer.Kind.EFFECT,
          clipId = effect.clip.id,
          sourcePosMs = effect.timeInEffectMs,
          zIndex = z++,
          opacity = effect.intensity
        )
      }
      for (sticker in frame.activeStickers) {
        layers += CompositionLayer(
          id = "sticker:${sticker.clip.id}",
          kind = CompositionLayer.Kind.STICKER,
          clipId = sticker.clip.id,
          sourcePosMs = 0L,
          zIndex = z++,
          opacity = sticker.opacity
        )
      }
      for (text in frame.activeTexts) {
        layers += CompositionLayer(
          id = "text:${text.clip.id}",
          kind = CompositionLayer.Kind.TEXT,
          clipId = text.clip.id,
          sourcePosMs = text.currentPosMs,
          zIndex = z++,
          opacity = text.opacity
        )
      }
      val audioSync = TimelineClipQueries.getActiveAudioSyncInfoAt(timeline, pos)
      for (audio in audioSync) {
        layers += CompositionLayer(
          id = "audio:${audio.clipId}",
          kind = CompositionLayer.Kind.AUDIO,
          clipId = audio.clipId,
          sourcePosMs = audio.sourcePosMs,
          zIndex = z++,
          opacity = audio.effectiveVolume
        )
      }
      return SharedCompositionGraph(
        clockMs = pos,
        frameIndex = TimelineFrameMath.timeToFrame(pos, fps),
        fps = fps,
        layers = layers,
        frameState = state,
        composedFrame = frame
      )
    }

    fun layerKindsPresent(graph: SharedCompositionGraph): Set<TrackType> = buildSet {
      for (layer in graph.layers) {
        when (layer.kind) {
          CompositionLayer.Kind.MAIN_VIDEO -> add(TrackType.MAIN_VIDEO)
          CompositionLayer.Kind.OVERLAY -> add(TrackType.OVERLAY)
          CompositionLayer.Kind.TEXT -> add(TrackType.TEXT)
          CompositionLayer.Kind.STICKER -> add(TrackType.STICKER)
          CompositionLayer.Kind.EFFECT -> add(TrackType.EFFECT)
          CompositionLayer.Kind.AUDIO -> add(TrackType.AUDIO)
          CompositionLayer.Kind.TRANSITION -> add(TrackType.MAIN_VIDEO)
        }
      }
    }
  }
}
