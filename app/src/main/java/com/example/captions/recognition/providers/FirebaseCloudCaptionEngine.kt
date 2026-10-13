package com.ahstudio.captions.recognition.providers

import android.content.Context
import android.net.Uri
import com.ahstudio.captions.core.errors.CaptionEngineException
import com.ahstudio.captions.core.language.LanguageTag
import com.ahstudio.captions.recognition.CaptionLanguages
import com.ahstudio.captions.recognition.RecognitionOptions
import com.ahstudio.captions.recognition.RecognitionProgress
import com.ahstudio.captions.recognition.SpeechRecognitionEngine
import com.ahstudio.captions.recognition.SpeechRecognitionResult
import com.ahstudio.captions.recognition.SpeechSource
import com.ahstudio.captions.recognition.parseFirebaseCaptionPayload
import com.example.data.firebase.FirebaseBootstrap
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Uploads the clip's audio and asks the Firebase `transcribeCaptions` function
 * to run Cloud Speech-to-Text. The recognizer runs in Firebase, not on the device.
 */
class FirebaseCloudCaptionEngine(private val context: Context) : SpeechRecognitionEngine {
    override val id = "firebase-speech"
    override val availability: StateFlow<com.ahstudio.captions.recognition.ProviderAvailability> =
        MutableStateFlow(com.ahstudio.captions.recognition.ProviderAvailability.Available)

    override fun supportedLanguages(): Set<String> =
        setOf("en", "ur", "ar", "hi", "es", "fr", "de", "zh", "ja", "ko", "pt", "it")

    override suspend fun transcribe(
        source: SpeechSource,
        options: RecognitionOptions,
        onProgress: (RecognitionProgress) -> Unit,
    ): SpeechRecognitionResult = withContext(Dispatchers.IO) {
        val pcm = source as? SpeechSource.Pcm
            ?: throw CaptionEngineException.RecognitionFailed("unsupported speech source")
        val language = CaptionLanguages.tag(LanguageTag.baseOf(options.languageTag))
        if (!supportsLanguage(language)) {
            throw CaptionEngineException.UnsupportedLanguage(language, id)
        }

        onProgress(RecognitionProgress(0.05f, 0))
        FirebaseBootstrap.ensure(context)
        val uid = ensureSignedIn()
        val wav = File.createTempFile("ahcap", ".wav", context.cacheDir)
        var storagePath: String? = null
        try {
            val durationMs = writeWav(wav, pcm, options.sampleRateHz)
            if (durationMs <= 0L) {
                return@withContext SpeechRecognitionResult(id, language, emptyList())
            }
            onProgress(RecognitionProgress(0.25f, 0))
            val objectName = "${UUID.randomUUID()}.wav"
            storagePath = "captions-audio/$uid/$objectName"
            val ref = FirebaseStorage.getInstance().reference.child(storagePath)
            ref.putFile(Uri.fromFile(wav)).await()
            onProgress(RecognitionProgress(0.55f, 0))

            val payload = hashMapOf(
                "storagePath" to storagePath,
                "language" to language,
                "durationMs" to durationMs,
            )
            val response = try {
                FirebaseFunctions.getInstance("us-central1")
                    .getHttpsCallable("transcribeCaptions")
                    .call(payload)
                    .await()
            } catch (error: FirebaseFunctionsException) {
                throw CaptionEngineException.RecognitionFailed(
                    error.message ?: "Firebase captions function failed (${error.code})",
                    error,
                )
            }
            onProgress(RecognitionProgress(0.95f, 0))
            @Suppress("UNCHECKED_CAST")
            val data = response.getData() as? Map<*, *>
            val utterances = parseFirebaseCaptionPayload(data)
            onProgress(RecognitionProgress(1f, utterances.size))
            SpeechRecognitionResult(providerId = id, languageTag = language, utterances = utterances)
        } finally {
            wav.delete()
            storagePath?.let { path ->
                runCatching { FirebaseStorage.getInstance().reference.child(path).delete().await() }
            }
        }
    }

    private suspend fun ensureSignedIn(): String {
        val auth = FirebaseAuth.getInstance()
        auth.currentUser?.uid?.let { return it }
        val signedIn = try {
            auth.signInAnonymously().await().user
        } catch (error: Exception) {
            throw CaptionEngineException.RecognitionUnavailable(
                "Sign in with Firebase before generating captions. ${error.message ?: "Anonymous sign-in is not enabled."}",
            )
        }
        return signedIn?.uid
            ?: throw CaptionEngineException.RecognitionUnavailable("Firebase Auth did not return a user.")
    }

    private suspend fun writeWav(wav: File, pcm: SpeechSource.Pcm, targetRate: Int): Long {
        var written = 0L
        RandomAccessFile(wav, "rw").use { raf ->
            raf.seek(44)
            val resampler = com.ahstudio.captions.audio.LinearResampler(pcm.sampleRateHz, targetRate)
            pcm.chunks.collect { chunk ->
                currentCoroutineContext().ensureActive()
                val samples = if (pcm.sampleRateHz == targetRate) chunk.samples else resampler.process(chunk.samples)
                val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (sample in samples) {
                    buffer.putShort((sample.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                }
                val bytes = buffer.array()
                raf.write(bytes)
                written += bytes.size
            }
            raf.seek(0)
            raf.write(wavHeader(written.toInt(), targetRate))
        }
        return if (written <= 0) 0L else written / 2 * 1000L / targetRate
    }

    private fun wavHeader(dataLen: Int, sampleRate: Int): ByteArray {
        val byteRate = sampleRate * 2
        val buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + dataLen)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(1)
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(2)
        buffer.putShort(16)
        buffer.put("data".toByteArray())
        buffer.putInt(dataLen)
        return buffer.array()
    }
}
