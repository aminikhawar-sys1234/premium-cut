package com.ahstudio.captions.recognition

object CaptionLanguages {
    fun tag(language: String): String {
        val raw = language.trim().lowercase()
        return when {
            raw.startsWith("en") || raw == "english" -> "en"
            raw.startsWith("ur") || raw == "urdu" -> "ur"
            raw.startsWith("ar") || raw == "arabic" -> "ar"
            raw.startsWith("hi") || raw == "hindi" -> "hi"
            raw.startsWith("es") || raw == "spanish" -> "es"
            raw.startsWith("fr") || raw == "french" -> "fr"
            raw.startsWith("de") || raw == "german" -> "de"
            raw.startsWith("zh") || raw == "chinese" -> "zh"
            raw.startsWith("ja") || raw == "japanese" -> "ja"
            raw.startsWith("ko") || raw == "korean" -> "ko"
            raw.startsWith("pt") || raw == "portuguese" -> "pt"
            raw.startsWith("it") || raw == "italian" -> "it"
            raw.length == 2 -> raw
            else -> "en"
        }
    }
}

/**
 * Reads the Firebase `transcribeCaptions` payload. Blank text is ignored.
 * This does not synthesize caption words when the recognizer returned none.
 */
fun parseFirebaseCaptionPayload(data: Map<*, *>?): List<RecognizedUtterance> {
    val utterances = data?.get("utterances") as? List<*> ?: return emptyList()
    return utterances.mapNotNull { item ->
        val map = item as? Map<*, *> ?: return@mapNotNull null
        val text = map["text"]?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return@mapNotNull null
        val startUs = (map["startUs"] as? Number)?.toLong() ?: return@mapNotNull null
        val endUs = (map["endUs"] as? Number)?.toLong() ?: return@mapNotNull null
        val words = (map["words"] as? List<*>)?.mapNotNull { wordItem ->
            val wordMap = wordItem as? Map<*, *> ?: return@mapNotNull null
            val token = wordMap["text"]?.toString()?.trim().orEmpty()
            if (token.isEmpty()) return@mapNotNull null
            val wordStart = (wordMap["startUs"] as? Number)?.toLong() ?: return@mapNotNull null
            val wordEnd = (wordMap["endUs"] as? Number)?.toLong() ?: return@mapNotNull null
            RecognizedWord(
                text = token,
                startUs = wordStart,
                endUs = wordEnd.coerceAtLeast(wordStart),
                confidence = (wordMap["confidence"] as? Number)?.toFloat() ?: 1f,
            )
        }.orEmpty()
        if (words.isEmpty()) return@mapNotNull null
        RecognizedUtterance(
            text = text,
            startUs = startUs,
            endUs = endUs.coerceAtLeast(startUs),
            words = words,
        )
    }
}
