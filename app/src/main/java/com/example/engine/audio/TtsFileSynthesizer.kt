package com.example.engine.audio

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Waits for [TextToSpeech.synthesizeToFile] to finish. Callers must not add the
 * output to the timeline until this returns true and the file has bytes.
 */
suspend fun synthesizeSpeechToFile(
  engine: TextToSpeech,
  text: String,
  outputFile: File,
  timeoutMs: Long = 30_000L
): Boolean {
  if (text.isBlank()) return false
  outputFile.parentFile?.mkdirs()
  val utteranceId = "AH_TTS_${UUID.randomUUID()}"
  val completed = CompletableDeferred<Boolean>()
  val listener = object : UtteranceProgressListener() {
    override fun onStart(utteranceId: String?) {}
    override fun onDone(id: String?) {
      if (id == utteranceId && !completed.isCompleted) completed.complete(true)
    }
    override fun onError(id: String?) {
      if (id == utteranceId && !completed.isCompleted) completed.complete(false)
    }
    @Deprecated("Deprecated in Java")
    override fun onError(id: String?, errorCode: Int) {
      if (id == utteranceId && !completed.isCompleted) completed.complete(false)
    }
  }
  engine.setOnUtteranceProgressListener(listener)
  val params = Bundle().apply {
    putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
  }
  val queued = engine.synthesizeToFile(text, params, outputFile, utteranceId)
  if (queued != TextToSpeech.SUCCESS) return false
  val ok = withTimeoutOrNull(timeoutMs) { completed.await() } ?: false
  return ok && outputFile.exists() && outputFile.length() > 0L
}
