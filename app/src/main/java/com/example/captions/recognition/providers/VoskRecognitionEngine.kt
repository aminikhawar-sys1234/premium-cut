package com.ahstudio.captions.recognition.providers

import com.ahstudio.captions.core.errors.CaptionEngineException
import com.ahstudio.captions.recognition.ProviderAvailability
import com.ahstudio.captions.recognition.RecognitionOptions
import com.ahstudio.captions.recognition.RecognitionProgress
import com.ahstudio.captions.recognition.SpeechRecognitionEngine
import com.ahstudio.captions.recognition.SpeechRecognitionResult
import com.ahstudio.captions.recognition.SpeechSource
import kotlinx.coroutines.flow.StateFlow

/**
 * Kept only so older call sites cannot emit invented offline sentences.
 * Auto captions go through [FirebaseCloudCaptionEngine].
 */
class VoskRecognitionEngine(private val modelSource: VoskModelSource) : SpeechRecognitionEngine {

    override val id = "vosk-offline"
    override val availability: StateFlow<ProviderAvailability> get() = modelSource.status

    override fun supportedLanguages(): Set<String> = emptySet()

    override suspend fun transcribe(
        source: SpeechSource,
        options: RecognitionOptions,
        onProgress: (RecognitionProgress) -> Unit,
    ): SpeechRecognitionResult {
        throw CaptionEngineException.RecognitionUnavailable(
            "Offline Vosk is not a speech recognizer in this app. Auto captions use the Firebase speech engine.",
        )
    }
}
