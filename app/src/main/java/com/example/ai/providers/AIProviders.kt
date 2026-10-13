package com.example.ai.providers

import com.example.domain.model.TextClip
import java.io.File

/**
 * SpeechToText provider interface.
 * Transcribes actual imported audio files into timed caption segments.
 */
interface SpeechToTextProvider {
  val isAvailable: Boolean
  val providerName: String
  suspend fun transcribeAudio(audioFile: File, language: String): Result<List<TextClip>>
  suspend fun transcribeAudio(audioUriOrPath: String, language: String): Result<List<TextClip>>
}
typealias SpeechToText = SpeechToTextProvider

/**
 * Translation provider interface.
 * Translates subtitle text or caption clips while preserving timing and structure.
 */
interface TranslationProvider {
  val isAvailable: Boolean
  val providerName: String
  suspend fun translateText(text: String, targetLanguage: String): Result<String>
  suspend fun translateCaptions(captions: List<TextClip>, targetLanguage: String): Result<List<TextClip>>
}
typealias Translation = TranslationProvider

/**
 * NoiseReduction provider interface.
 * Processes real audio files to reduce background noise, hiss, and hum.
 */
interface NoiseReductionProvider {
  val isAvailable: Boolean
  val providerName: String
  suspend fun reduceNoise(audioFile: File): Result<File>
}
typealias NoiseReduction = NoiseReductionProvider

/**
 * TextToSpeech provider interface.
 * Synthesizes written text into an actual playable audio file.
 */
interface TextToSpeechProvider {
  val isAvailable: Boolean
  val providerName: String
  suspend fun synthesizeSpeech(text: String, pitch: Float, speed: Float): Result<File>
}
typealias TextToSpeech = TextToSpeechProvider
