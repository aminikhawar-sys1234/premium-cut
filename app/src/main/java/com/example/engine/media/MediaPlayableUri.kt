package com.example.engine.media

import android.content.Context
import android.net.Uri
import com.example.domain.model.AudioClip
import com.example.domain.model.Timeline
import com.example.domain.model.VideoClip
import java.io.File

/**
 * Normalizes clip URIs so ExoPlayer, MediaCodec and file checks all see the same
 * playable source after a project is reopened.
 *
 * Importers historically stored either a one-shot PhotoPicker `content://` grant or a
 * raw absolute path (`/data/user/0/.../clip.mp4`). After leaving the editor those
 * grants expire, and Media3 will not open a scheme-less path until it is a `file://`
 * URI. Timeline text still draws because it does not need a decoder — that is the
 * "black preview, no audio, text still visible" reopen bug.
 */
object MediaPlayableUri {

  fun isSynthetic(uriString: String?): Boolean {
    if (uriString.isNullOrBlank()) return false
    return uriString.startsWith("asset://") ||
      uriString.startsWith("demo://") ||
      uriString.startsWith("sample://") ||
      uriString.startsWith("stock://") ||
      uriString.startsWith("internal://") ||
      uriString.startsWith("template://") ||
      uriString.startsWith("android.resource://") ||
      uriString.startsWith("file:///android_asset/")
  }

  /**
   * Local file backing [uriString], or null when the URI is remote/synthetic/content.
   */
  fun resolveLocalFile(uriString: String?): File? {
    if (uriString.isNullOrBlank() || isSynthetic(uriString)) return null
    return try {
      val parsed = Uri.parse(uriString)
      when (parsed.scheme) {
        null -> File(uriString).takeIf { it.isAbsolute }
        "file" -> {
          val path = parsed.path ?: return null
          if (path.startsWith("/android_asset/")) null else File(path)
        }
        else -> null
      }
    } catch (_: Exception) {
      null
    }
  }

  /**
   * `file://` URI for an existing local file, otherwise the original string.
   * Safe to persist in project JSON.
   */
  fun toStoredUri(uriString: String?): String {
    if (uriString.isNullOrBlank()) return uriString.orEmpty()
    val file = resolveLocalFile(uriString) ?: return uriString
    return if (file.exists() && file.canRead() && file.length() > 0L) {
      Uri.fromFile(file).toString()
    } else {
      uriString
    }
  }

  fun toStoredUri(file: File): String = Uri.fromFile(file).toString()

  /**
   * URI Media3 / ExoPlayer can open. Absolute paths become `file://`; existing
   * `content` / `http(s)` / `asset` URIs are passed through.
   */
  fun toExoPlayerUri(uriString: String?): Uri {
    if (uriString.isNullOrBlank()) return Uri.EMPTY
    val stored = toStoredUri(uriString)
    return try {
      val parsed = Uri.parse(stored)
      if (parsed.scheme.isNullOrBlank()) {
        val file = File(stored)
        if (file.exists()) Uri.fromFile(file) else parsed
      } else {
        parsed
      }
    } catch (_: Exception) {
      Uri.parse(uriString)
    }
  }

  fun toExoPlayerUri(context: Context, uriString: String?): Uri {
    val resolved = resolvePlayableString(context, uriString)
    return toExoPlayerUri(resolved)
  }

  /**
   * Best playable string for a clip: existing local file as `file://`, otherwise the
   * original URI (content grants that are still open, remotes, synthetics).
   */
  fun resolvePlayableString(context: Context, uriString: String?): String {
    if (uriString.isNullOrBlank()) return ""
    if (isSynthetic(uriString)) return uriString
    val local = resolveLocalFile(uriString)
    if (local != null && local.exists() && local.canRead() && local.length() > 0L) {
      return Uri.fromFile(local).toString()
    }
    val parsed = try { Uri.parse(uriString) } catch (_: Exception) { null }
    if (parsed?.scheme == "content") {
      if (isContentAccessible(context, parsed)) return uriString
      MediaPersistenceManager.findPersistedCopy(context, uriString)?.let { copy ->
        return Uri.fromFile(copy).toString()
      }
    }
    MediaPersistenceManager.findPersistedCopy(context, uriString)?.let { copy ->
      return Uri.fromFile(copy).toString()
    }
    return uriString
  }

  fun isContentAccessible(context: Context, uri: Uri): Boolean {
    return try {
      context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (_: Exception) {
      false
    }
  }

  /** Rewrites every clip URI that already has a local file to a stable `file://` form. */
  fun hydrateTimeline(context: Context, timeline: Timeline): Timeline {
    fun video(clip: VideoClip) = clip.copy(uri = resolvePlayableString(context, clip.uri))
    fun audio(clip: AudioClip) = clip.copy(uri = resolvePlayableString(context, clip.uri))
    val bg = timeline.chromaKey.backgroundUri
    val hydratedBg = if (bg.isNullOrBlank()) bg else resolvePlayableString(context, bg)
    return timeline.copy(
      videoClips = timeline.videoClips.map(::video),
      overlayClips = timeline.overlayClips.map(::video),
      audioClips = timeline.audioClips.map(::audio),
      chromaKey = timeline.chromaKey.copy(backgroundUri = hydratedBg)
    )
  }

  fun hasUriChanges(before: Timeline, after: Timeline): Boolean {
    if (before.chromaKey.backgroundUri != after.chromaKey.backgroundUri) return true
    if (before.videoClips.size != after.videoClips.size) return true
    if (before.overlayClips.size != after.overlayClips.size) return true
    if (before.audioClips.size != after.audioClips.size) return true
    before.videoClips.forEachIndexed { i, clip ->
      if (clip.uri != after.videoClips[i].uri) return true
    }
    before.overlayClips.forEachIndexed { i, clip ->
      if (clip.uri != after.overlayClips[i].uri) return true
    }
    before.audioClips.forEachIndexed { i, clip ->
      if (clip.uri != after.audioClips[i].uri) return true
    }
    return false
  }
}
