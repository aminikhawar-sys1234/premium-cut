package com.example.engine.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File

/**
 * Reads a real audio/video duration. Returns null when the file cannot be measured
 * so callers do not invent a length (for example the old 8000 ms import fallback).
 */
object AudioMediaDuration {

  fun durationMs(context: Context, pathOrUri: String): Long? {
    if (pathOrUri.isBlank()) return null
    val asFile = File(pathOrUri)
    if (asFile.isFile) {
      WavDuration.durationMs(asFile)?.takeIf { it > 0L }?.let { return it }
    }
    val retriever = MediaMetadataRetriever()
    return try {
      val uri = Uri.parse(pathOrUri)
      when {
        uri.scheme == "content" || uri.scheme == "file" -> retriever.setDataSource(context, uri)
        asFile.isFile -> retriever.setDataSource(asFile.absolutePath)
        else -> retriever.setDataSource(pathOrUri)
      }
      retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        ?.toLongOrNull()
        ?.takeIf { it > 0L }
    } catch (_: Exception) {
      null
    } finally {
      try { retriever.release() } catch (_: Exception) {}
    }
  }
}
