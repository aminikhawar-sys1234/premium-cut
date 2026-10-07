package com.example.ui.audio

import android.content.Context
import com.ahstudio.audio.master.dsp.eq.EqualizerBand
import com.ahstudio.audio.master.integration.AudioLevels
import com.ahstudio.audio.master.integration.MasterAudioRenderer
import com.ahstudio.audio.master.integration.MasterAudioSettings
import com.example.domain.model.AudioClip
import com.example.engine.SelectedTrackElement
import com.example.engine.TimelineEngine
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MasterAudioUiState(
  val settings: MasterAudioSettings = MasterAudioSettings(),
  val targetClipId: String? = null,
  val targetTitle: String? = null,
  val isWorking: Boolean = false,
  val progress: Float = 0f,
  val before: AudioLevels? = null,
  val after: AudioLevels? = null,
  val message: String? = null,
)

/**
 * Bridges the master DSP package (EQ, compressor, de-esser, spectral NR, WSOLA) to the
 * editor UI. Rendering is offline: the selected audio clip is processed into a new WAV
 * and the clip is repointed at it. The original file is kept so Reset can restore it.
 */
class MasterAudioController(
  private val context: Context,
  private val timelineEngine: TimelineEngine,
  private val scope: CoroutineScope,
) {
  private val renderer by lazy { MasterAudioRenderer(context) }
  private val _state = MutableStateFlow(MasterAudioUiState())
  val state: StateFlow<MasterAudioUiState> = _state.asStateFlow()

  /** clipId -> clip snapshot before the first master render (for Reset / re-render). */
  private val originals = HashMap<String, AudioClip>()
  private var job: Job? = null

  fun refreshTarget() {
    val id = (timelineEngine.selectedElement.value as? SelectedTrackElement.Audio)?.clipId
      ?: timelineEngine.selectedClipIds.value.firstOrNull { cid ->
        timelineEngine.timeline.value.audioClips.any { it.id == cid }
      }
    val clip = id?.let { cid -> timelineEngine.timeline.value.audioClips.firstOrNull { it.id == cid } }
    _state.update { it.copy(targetClipId = clip?.id, targetTitle = clip?.title) }
  }

  fun update(transform: (MasterAudioSettings) -> MasterAudioSettings) {
    _state.update { it.copy(settings = transform(it.settings)) }
  }

  fun setEqBandGain(index: Int, gainDb: Float) = update { s ->
    s.copy(eqBands = s.eqBands.mapIndexed { i, b -> if (i == index) b.copy(gainDb = gainDb) else b }, eqEnabled = true)
  }

  fun setEqBandEnabled(index: Int, enabled: Boolean) = update { s ->
    s.copy(eqBands = s.eqBands.mapIndexed { i, b -> if (i == index) b.copy(enabled = enabled) else b })
  }

  fun applyEqPreset(name: String) = update { it.copy(eqBands = MasterAudioRenderer.eqPreset(name), eqEnabled = name != "flat") }

  fun resetSettings() = _state.update { it.copy(settings = MasterAudioSettings(), message = null) }

  private fun selectedClip(): AudioClip? {
    refreshTarget()
    val id = _state.value.targetClipId ?: return null
    return timelineEngine.timeline.value.audioClips.firstOrNull { it.id == id }
  }

  fun analyze() {
    val clip = selectedClip() ?: return setMessage("Select an audio clip on the timeline first.")
    launchWork("Analyzing…") {
      val base = originals[clip.id] ?: clip
      val levels = withContext(Dispatchers.Default) { renderer.analyze(base.uri, base.sourceStartMs, base.sourceEndMs) }
      _state.update { it.copy(before = levels, message = "Analysis complete.") }
    }
  }

  fun apply() {
    val clip = selectedClip() ?: return setMessage("Select an audio clip on the timeline first.")
    val settings = _state.value.settings
    if (!settings.hasAnyEffect) return setMessage("Turn on at least one effect.")
    launchWork("Rendering…") {
      // Always render from the original so repeated Apply never stacks effects.
      val base = originals.getOrPut(clip.id) { clip }
      val out = File(context.filesDir, "master_${clip.id.take(8)}_${System.currentTimeMillis()}.wav")
      val result = withContext(Dispatchers.Default) {
        renderer.render(base.uri, settings, out, base.sourceStartMs, base.sourceEndMs) { p ->
          _state.update { it.copy(progress = p) }
        }
      }
      val tl = timelineEngine.timeline.value
      val updated = tl.audioClips.map {
        if (it.id == clip.id) it.copy(
          uri = result.file.absolutePath,
          sourceStartMs = 0L,
          sourceEndMs = result.durationMs,
          durationMs = (result.durationMs / it.speed.coerceAtLeast(0.1f)).toLong(),
          waveformData = emptyList(),
        ) else it
      }
      timelineEngine.replaceTimelineKeepingState(tl.copy(audioClips = updated))
      _state.update { it.copy(before = result.before, after = result.after, message = "Applied to \"${clip.title}\".") }
    }
  }

  /** Restores the clip's original file (full source range). */
  fun restoreOriginal() {
    val clip = selectedClip() ?: return
    val orig = originals.remove(clip.id) ?: return setMessage("Nothing to restore.")
    val tl = timelineEngine.timeline.value
    val updated = tl.audioClips.map {
      if (it.id == clip.id) it.copy(
        uri = orig.uri, sourceStartMs = orig.sourceStartMs, sourceEndMs = orig.sourceEndMs,
        durationMs = orig.durationMs, waveformData = orig.waveformData,
      ) else it
    }
    timelineEngine.replaceTimelineKeepingState(tl.copy(audioClips = updated))
    _state.update { it.copy(after = null, message = "Original restored.") }
  }

  private fun setMessage(m: String) = _state.update { it.copy(message = m) }

  private fun launchWork(label: String, block: suspend () -> Unit) {
    if (job?.isActive == true) return
    _state.update { it.copy(isWorking = true, progress = 0f, message = label) }
    job = scope.launch {
      try {
        block()
      } catch (e: Exception) {
        _state.update { it.copy(message = "Failed: ${e.message ?: e.javaClass.simpleName}") }
      } finally {
        _state.update { it.copy(isWorking = false, progress = 0f) }
      }
    }
  }
}
