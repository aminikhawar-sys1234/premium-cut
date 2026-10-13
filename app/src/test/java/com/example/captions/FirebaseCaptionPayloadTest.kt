package com.example.captions

import com.ahstudio.captions.core.errors.CaptionEngineException
import com.ahstudio.captions.recognition.ProviderAvailability
import com.ahstudio.captions.recognition.RecognitionOptions
import com.ahstudio.captions.recognition.SpeechSource
import com.ahstudio.captions.recognition.parseFirebaseCaptionPayload
import com.ahstudio.captions.recognition.providers.VoskModelSource
import com.ahstudio.captions.recognition.providers.VoskRecognitionEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class FirebaseCaptionPayloadTest {

  @Test
  fun `measured word times become captions`() {
    val data = mapOf(
      "utterances" to listOf(
        mapOf(
          "text" to "hello world",
          "startUs" to 120_000,
          "endUs" to 900_000,
          "words" to listOf(
            mapOf("text" to "hello", "startUs" to 120_000, "endUs" to 400_000, "confidence" to 0.9),
            mapOf("text" to "world", "startUs" to 450_000, "endUs" to 900_000, "confidence" to 0.8),
          ),
        ),
      ),
    )
    val utterances = parseFirebaseCaptionPayload(data)
    assertEquals(1, utterances.size)
    assertEquals("hello world", utterances[0].text)
    assertEquals("hello", utterances[0].words?.get(0)?.text)
    assertEquals(120_000L, utterances[0].words?.get(0)?.startUs)
    assertEquals(900_000L, utterances[0].words?.get(1)?.endUs)
  }

  @Test
  fun `missing words or blank payloads produce no captions`() {
    val guessed = mapOf(
      "utterances" to listOf(
        mapOf("text" to "guessed line", "startUs" to 0, "endUs" to 1_000),
      ),
    )
    assertTrue(parseFirebaseCaptionPayload(guessed).isEmpty())
    assertTrue(parseFirebaseCaptionPayload(emptyMap<String, Any>()).isEmpty())
    assertTrue(parseFirebaseCaptionPayload(null).isEmpty())
  }

  @Test
  fun `offline vosk refuses instead of inventing a transcript`() = runBlocking {
    val engine = VoskRecognitionEngine(object : VoskModelSource {
      override val status = MutableStateFlow<ProviderAvailability>(ProviderAvailability.Unavailable("unused"))
      override fun availableLanguages(): Set<String> = emptySet()
      override suspend fun obtainModelDir(languageTag: String): File = File(".")
    })
    try {
      engine.transcribe(SpeechSource.Pcm(16_000, emptyFlow()), RecognitionOptions("en"))
      fail("Vosk must not return a transcript")
    } catch (error: CaptionEngineException.RecognitionUnavailable) {
      assertTrue(error.message.orEmpty().contains("Firebase"))
    }
  }
}
