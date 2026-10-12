package com.example.engine.preview

import android.content.Context
import android.view.TextureView
import com.example.domain.model.Timeline
import com.example.domain.model.VideoClip
import com.example.engine.composition.ComposedFrame
import com.example.engine.composition.graph.CompositionLayer
import com.example.engine.composition.graph.SharedCompositionGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * CapCut-style preview compositor: one timeline clock, N clock-slave decoders, one graph.
 *
 * Overlay ExoPlayers are not created here. Each overlay clip is a [ClockDrivenSurfaceDecoder]
 * that only presents the frame at [CompositionLayer.sourcePosMs].
 */
class SingleClockPreviewCompositor(
  private val context: Context
) {
  private val overlayDecoders = ConcurrentHashMap<String, ClockDrivenSurfaceDecoder>()
  private val pendingViews = ConcurrentHashMap<String, TextureView>()

  private val _graph = MutableStateFlow<SharedCompositionGraph?>(null)
  val graph: StateFlow<SharedCompositionGraph?> = _graph.asStateFlow()

  private val _overlaySurfaceIds = MutableStateFlow<Set<String>>(emptySet())
  val overlaySurfaceIds: StateFlow<Set<String>> = _overlaySurfaceIds.asStateFlow()

  fun evaluate(timeline: Timeline, clockMs: Long, fps: Int = 30): SharedCompositionGraph {
    val next = SharedCompositionGraph.evaluate(timeline, clockMs, fps)
    _graph.value = next
    return next
  }

  /**
   * Samples the shared graph at [clockMs] and presents overlay video frames on that clock.
   * Returns the composed frame so export and preview share the same evaluation.
   */
  fun sync(timeline: Timeline, clockMs: Long, fps: Int = 30): ComposedFrame {
    val graph = evaluate(timeline, clockMs, fps)
    val overlayLayers = graph.layers.filter { it.kind == CompositionLayer.Kind.OVERLAY }
    val activeIds = overlayLayers.map { it.clipId }.toSet()
    overlayDecoders.keys.toList().filter { it !in activeIds }.forEach { id ->
      overlayDecoders.remove(id)?.release()
      pendingViews.remove(id)
    }
    for (layer in overlayLayers) {
      val clip = timeline.overlayClips.find { it.id == layer.clipId } ?: continue
      if (!clip.isVideo) continue
      val decoder = overlayDecoders.getOrPut(layer.clipId) {
        ClockDrivenSurfaceDecoder(context.applicationContext, layer.clipId)
      }
      pendingViews.remove(layer.clipId)?.let { decoder.attach(it) }
      if (PreviewCompositorPolicy.shouldPresentOverlayOnClockTick()) {
        decoder.present(clip, layer.sourcePosMs)
      }
    }
    _overlaySurfaceIds.value = overlayDecoders.keys.toSet()
    return graph.composedFrame
  }

  fun bindOverlaySurface(clipId: String, view: TextureView) {
    val decoder = overlayDecoders[clipId]
    if (decoder != null) {
      decoder.attach(view)
    } else {
      pendingViews[clipId] = view
    }
  }

  fun composedFrame(): ComposedFrame? = _graph.value?.composedFrame

  fun overlayClipAt(clipId: String, timeline: Timeline): VideoClip? =
    timeline.overlayClips.find { it.id == clipId }

  fun release() {
    overlayDecoders.values.forEach { it.release() }
    overlayDecoders.clear()
    pendingViews.clear()
    _overlaySurfaceIds.value = emptySet()
    _graph.value = null
  }
}
