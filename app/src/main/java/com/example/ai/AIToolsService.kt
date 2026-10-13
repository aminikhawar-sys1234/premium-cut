package com.example.ai

import android.content.Context
import com.ahstudio.captions.android.CaptionsGraph
import com.ahstudio.captions.engine.AutoCaptionOptions
import com.ahstudio.captions.recognition.CaptionLanguages
import com.example.ai.providers.*
import com.example.ai.providers.secure.AISecurityConfig
import com.example.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Coordinates the AI providers that actually process media:
 * speech-to-text, translation, DSP noise reduction, and device TTS.
 */
class AIToolsService(private val context: Context? = null) {

  val speechToText: SpeechToTextProvider = GeminiAIProvider(context)
  val translation: TranslationProvider = GeminiAIProvider(context)
  private val safeContext: Context?
    get() = context ?: runCatching { com.example.StudioApplication.instance }.getOrNull()

  val noiseReduction: NoiseReductionProvider by lazy {
    safeContext?.let { SystemNoiseReductionProvider(it) }
      ?: object : NoiseReductionProvider {
        override val providerName: String = "System Noise Reduction (Inactive)"
        override val isAvailable: Boolean = false
        override suspend fun reduceNoise(audioFile: java.io.File): Result<java.io.File> =
          Result.failure(IllegalStateException("Noise reduction unavailable: Context not initialized"))
      }
  }

  val textToSpeech: TextToSpeechProvider by lazy {
    safeContext?.let { AndroidTextToSpeechProvider(it) }
      ?: object : TextToSpeechProvider {
        override val providerName: String = "Android Text to Speech (Inactive)"
        override val isAvailable: Boolean = false
        override suspend fun synthesizeSpeech(text: String, pitch: Float, speed: Float): Result<java.io.File> =
          Result.failure(IllegalStateException("Text to speech service unavailable: Context not initialized"))
      }
  }

  val isAIConfigured: Boolean
    get() = AISecurityConfig.isConfigured(context)

  fun getSecurityStatus(): String {
    return context?.let { AISecurityConfig.getConnectionModeDescription(it) }
      ?: if (isAIConfigured) "Configured" else "Offline / Not Configured"
  }

  /**
   * AI Auto Captions: uploads the imported clip and reads word times from the
   * Firebase `transcribeCaptions` function. An empty list means no speech was
   * measured. Failures stay failures; this method does not invent caption lines.
   */
  suspend fun generateAutoCaptions(
    timeline: Timeline,
    language: String = "English"
  ): Result<List<TextClip>> = withContext(Dispatchers.IO) {
    val ctx = safeContext ?: return@withContext Result.failure(
      IllegalStateException("Auto captions need the app context to reach the Firebase speech engine.")
    )
    val mediaUriStr = timeline.audioClips.firstOrNull { it.uri.isNotBlank() }?.uri
      ?: timeline.videoClips.firstOrNull { it.uri.isNotBlank() }?.uri
      ?: return@withContext Result.failure(
        IllegalStateException("No media on timeline: Please import a video or audio clip first to generate captions.")
      )
    val mediaUri = if (mediaUriStr.startsWith("content://") || mediaUriStr.startsWith("file://")) {
      android.net.Uri.parse(mediaUriStr)
    } else {
      android.net.Uri.fromFile(File(mediaUriStr))
    }
    try {
      val project = CaptionsGraph.get(ctx).autoEngine.generate(
        mediaUri = mediaUri,
        options = AutoCaptionOptions(
          languageTag = CaptionLanguages.tag(language),
          preferredProviderId = "firebase-speech",
        ),
      ) { }
      val clips = project.tracks.firstOrNull()?.clips.orEmpty().map { clip ->
        val startMs = (clip.timing.start.micros / 1000L).coerceAtLeast(0L)
        val durMs = ((clip.timing.end.micros - clip.timing.start.micros) / 1000L).coerceAtLeast(500L)
        val wordTimings = clip.words.map { word ->
          WordTiming(
            word.text,
            ((word.start.micros - clip.timing.start.micros) / 1000L).coerceAtLeast(0L),
            ((word.end.micros - word.start.micros) / 1000L).coerceAtLeast(100L),
          )
        }
        TextClip(
          id = UUID.randomUUID().toString(),
          text = clip.displayText,
          timelineStartMs = startMs,
          durationMs = durMs,
          words = wordTimings,
          subtitleStyle = "Classic",
        )
      }
      Result.success(clips)
    } catch (error: Exception) {
      Result.failure(error)
    }
  }

  /**
   * Auto captions require imported media. A title string is not a substitute for audio.
   */
  suspend fun generateAutoCaptions(
    videoTitle: String,
    @Suppress("UNUSED_PARAMETER") durationMs: Long,
    @Suppress("UNUSED_PARAMETER") language: String = "English"
  ): List<TextClip> = withContext(Dispatchers.IO) {
    throw IllegalStateException(
      "Auto captions require imported audio or video. Cannot transcribe '$videoTitle' without media."
    )
  }

  /**
   * AI Translation: Translates actual caption clips into target language, strictly preserving timestamps.
   */
  suspend fun translateCaptions(
    captions: List<TextClip>,
    targetLanguage: String
  ): Result<List<TextClip>> = withContext(Dispatchers.IO) {
    if (captions.isEmpty()) {
      return@withContext Result.failure(
        IllegalStateException("No captions on timeline to translate. Please create or generate captions first.")
      )
    }
    if (!translation.isAvailable) {
      return@withContext Result.failure(
        IllegalStateException("Translation unavailable: No AI provider configured. Set up your credentials in AI Settings.")
      )
    }
    return@withContext translation.translateCaptions(captions, targetLanguage)
  }

  /**
   * DSP noise reduction: spectral gating and high-pass filtering on a real audio file.
   */
  suspend fun reduceAudioNoise(audioFile: File): Result<File> = withContext(Dispatchers.IO) {
    return@withContext noiseReduction.reduceNoise(audioFile)
  }

  /**
   * AI Text-to-Speech: Synthesizes written text into an actual WAV audio file.
   */
  suspend fun synthesizeSpeech(text: String, pitch: Float = 1.0f, speed: Float = 1.0f): Result<File> = withContext(Dispatchers.IO) {
    return@withContext textToSpeech.synthesizeSpeech(text, pitch, speed)
  }

  /**
   * Exports subtitles as standard SRT format.
   */
  fun exportSrt(captions: List<TextClip>): String {
    val sb = StringBuilder()
    captions.sortedBy { it.timelineStartMs }.forEachIndexed { index, clip ->
      sb.append("${index + 1}\n")
      sb.append("${formatSrtTime(clip.timelineStartMs)} --> ${formatSrtTime(clip.timelineStartMs + clip.durationMs)}\n")
      sb.append("${clip.text}\n\n")
    }
    return sb.toString()
  }

  private fun formatSrtTime(ms: Long): String {
    val hours = ms / 3600000
    val minutes = (ms % 3600000) / 60000
    val seconds = (ms % 60000) / 1000
    val millis = ms % 1000
    return String.format("%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
  }
}
