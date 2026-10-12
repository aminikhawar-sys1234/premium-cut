package com.example.ai

import com.example.ui.components.audio.TTSVoicesCatalog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeAiToolsRemovedTest {

  @Test
  fun ttsCatalogDoesNotAdvertiseFakeNeuralVoices() {
    val ids = TTSVoicesCatalog.VOICES.map { it.id }
    assertTrue(ids.isNotEmpty())
    assertFalse(ids.any { it.startsWith("ai_") })
    TTSVoicesCatalog.VOICES.forEach { voice ->
      val haystack = (voice.id + " " + voice.name + " " + voice.subtitle).lowercase()
      assertFalse("TTS voice must not claim a neural/Gemini engine: $haystack", haystack.contains("gemini"))
      assertFalse("TTS voice must not claim a neural engine: $haystack", haystack.contains("neural"))
    }
  }

  @Test
  fun aiToolsServiceDoesNotExposeMockHighlightOrAutoEditApis() {
    val names = AIToolsService::class.members.map { it.name }.toSet()
    assertFalse(names.contains("autoEditMontage"))
    assertFalse(names.contains("analyzeHighlights"))
    assertFalse(names.contains("removeBackground"))
    assertFalse(names.contains("generateAlphaMask"))
    assertFalse(names.contains("highlightDetection"))
    assertTrue(names.contains("generateAutoCaptions"))
    assertTrue(names.contains("translateCaptions"))
    assertTrue(names.contains("synthesizeSpeech"))
    assertTrue(names.contains("reduceAudioNoise"))
  }
}
