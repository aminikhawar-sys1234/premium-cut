package com.example.engine.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.domain.model.AudioClip
import com.example.domain.model.MissingMediaItem
import com.example.domain.model.Timeline
import com.example.domain.model.VideoClip
import java.io.File

object MediaRelinkManager {

  /** Suffix that turns a clip id into the missing-media id of that clip's chroma background. */
  private const val CHROMA_BACKGROUND_SUFFIX = "::chroma_background"

  /**
   * Verifies whether a given URI or file path is currently accessible on the device.
   */
  fun isMediaAccessible(context: Context, uriString: String?): Boolean {
    if (uriString.isNullOrBlank()) return false

    // Synthetic demo & internal audio presets are considered accessible for project integrity
    if (uriString.startsWith("demo://") ||
      uriString.startsWith("sample://") ||
      uriString.startsWith("stock://") ||
      uriString.startsWith("internal://") ||
      uriString.startsWith("template://") ||
      uriString.startsWith("android.resource://")
    ) {
      return true
    }

    // Asset scheme check
    if (uriString.startsWith("asset://") || uriString.startsWith("file:///android_asset/")) {
      return isAssetAccessible(context, uriString)
    }

    val playable = MediaPlayableUri.resolvePlayableString(context, uriString)
    val local = MediaPlayableUri.resolveLocalFile(playable)
    if (local != null) {
      return local.exists() && local.length() > 0L
    }

    return try {
      val parsedUri = Uri.parse(playable)
      when (parsedUri.scheme) {
        "content" -> {
          // Check if ContentResolver can open or query the content URI
          val fd = context.contentResolver.openFileDescriptor(parsedUri, "r")
          if (fd != null) {
            fd.close()
            true
          } else {
            false
          }
        }
        "file" -> {
          val path = parsedUri.path ?: ""
          File(path).exists()
        }
        null -> {
          File(playable).exists()
        }
        else -> {
          // For http / https or unknown schemes, check basic structure
          playable.isNotBlank()
        }
      }
    } catch (e: Exception) {
      false
    }
  }

  /**
   * Verifies if a given URI points to a real, playable file/content stream that can be decoded
   * by ExoPlayer or MediaCodec without throwing source or file-not-found exceptions.
   */
  fun isRealPlayableMedia(context: Context, uriString: String?): Boolean {
    if (uriString.isNullOrBlank()) return false
    if (uriString.startsWith("stock://") ||
      uriString.startsWith("sample://") ||
      uriString.startsWith("demo://") ||
      uriString.startsWith("internal://") ||
      uriString.startsWith("template://")
    ) {
      return false
    }

    // Prefer a rewritten local file (absolute path or file://) so a reopened project
    // whose JSON still has a scheme-less path is treated as playable.
    val local = MediaPlayableUri.resolveLocalFile(uriString)
    if (local != null) {
      return local.exists() && local.canRead() && local.length() > 0L
    }
    val resolved = MediaPlayableUri.resolvePlayableString(context, uriString)
    if (resolved != uriString) {
      val resolvedLocal = MediaPlayableUri.resolveLocalFile(resolved)
      if (resolvedLocal != null) {
        return resolvedLocal.exists() && resolvedLocal.canRead() && resolvedLocal.length() > 0L
      }
    }

    return try {
      val parsedUri = Uri.parse(resolved)
      when (parsedUri.scheme) {
        "asset" -> isAssetAccessible(context, resolved)
        "content" -> {
          context.contentResolver.openFileDescriptor(parsedUri, "r")?.use { true } ?: false
        }
        "file" -> {
          val path = parsedUri.path ?: ""
          if (path.startsWith("/android_asset/")) {
            val assetPath = path.removePrefix("/android_asset/")
            try {
              context.assets.open(assetPath).use { true }
            } catch (e: Exception) {
              false
            }
          } else {
            val file = File(path)
            file.exists() && file.canRead() && file.length() > 0L
          }
        }
        "http", "https", "android.resource" -> true
        null -> {
          val file = File(resolved)
          file.exists() && file.canRead() && file.length() > 0L
        }
        else -> false
      }
    } catch (e: Exception) {
      false
    }
  }

  /**
   * Safely verifies if an asset exists in the app package.
   */
  fun isAssetAccessible(context: Context, uriString: String): Boolean {
    return try {
      val parsed = Uri.parse(uriString)
      var path = parsed.path ?: ""
      if (path.startsWith("/")) path = path.substring(1)
      if (path.isEmpty() && parsed.scheme == "asset") {
        path = parsed.authority ?: ""
      }
      if (path.isEmpty()) return false
      context.assets.open(path).use { true }
    } catch (e: Exception) {
      false
    }
  }

  /**
   * Resolves a human-readable display filename from a file path or content URI.
   */
  fun resolveFileName(context: Context, uriString: String?): String {
    if (uriString.isNullOrBlank()) return "Untitled Media"
    return try {
      val uri = Uri.parse(uriString)
      if (uri.scheme == "content") {
        var name: String? = null
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
          if (cursor.moveToFirst()) {
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) name = cursor.getString(idx)
          }
        }
        name ?: uri.lastPathSegment ?: "Media File"
      } else {
        val f = File(uri.path ?: uriString)
        f.name.ifBlank { "Media File" }
      }
    } catch (e: Exception) {
      uriString.substringAfterLast("/").ifBlank { "Media File" }
    }
  }

  /**
   * Scans the timeline for any media clips whose referenced files are no longer accessible.
   */
  fun detectMissingMedia(context: Context, timeline: Timeline): List<MissingMediaItem> {
    val missingList = mutableListOf<MissingMediaItem>()

    // Check Main Video Clips
    timeline.videoClips.forEach { clip ->
      if (!isMediaAccessible(context, clip.uri)) {
        missingList.add(
          MissingMediaItem(
            clipId = clip.id,
            clipName = clip.name,
            currentUri = clip.uri,
            originalFilename = resolveFileName(context, clip.uri),
            mediaType = if (clip.isVideo) "VIDEO" else "IMAGE",
            trackType = "Main Video"
          )
        )
      }
    }

    // Check Overlay Clips
    timeline.overlayClips.forEach { clip ->
      if (!isMediaAccessible(context, clip.uri)) {
        missingList.add(
          MissingMediaItem(
            clipId = clip.id,
            clipName = clip.name,
            currentUri = clip.uri,
            originalFilename = resolveFileName(context, clip.uri),
            mediaType = if (clip.isVideo) "VIDEO" else "IMAGE",
            trackType = "Overlay"
          )
        )
      }
    }

    // Check Audio Clips
    timeline.audioClips.forEach { clip ->
      if (!isMediaAccessible(context, clip.uri)) {
        missingList.add(
          MissingMediaItem(
            clipId = clip.id,
            clipName = clip.title,
            currentUri = clip.uri,
            originalFilename = resolveFileName(context, clip.uri),
            mediaType = "AUDIO",
            trackType = "Audio"
          )
        )
      }
    }

    // Check Chroma Key background image/video if set
    val bgUri = timeline.chromaKey.backgroundUri
    if (!bgUri.isNullOrBlank() && (timeline.chromaKey.backgroundType == "Image" || timeline.chromaKey.backgroundType == "Video")) {
      if (!isMediaAccessible(context, bgUri)) {
        missingList.add(
          MissingMediaItem(
            clipId = "chroma_background",
            clipName = "Chroma Key Background",
            currentUri = bgUri,
            originalFilename = resolveFileName(context, bgUri),
            mediaType = "IMAGE",
            trackType = "Chroma Background"
          )
        )
      }
    }

    // Per-clip chroma-key backgrounds (a clip can key over its own imported image / video).
    (timeline.videoClips + timeline.overlayClips).forEach { clip ->
      val settings = clip.chromaKey ?: return@forEach
      if (settings.backgroundType != "Image" && settings.backgroundType != "Video") return@forEach
      val uri = settings.backgroundUri
      if (uri.isNullOrBlank() || isMediaAccessible(context, uri)) return@forEach
      missingList.add(
        MissingMediaItem(
          clipId = chromaBackgroundClipId(clip.id),
          clipName = "${clip.name} - Chroma Background",
          currentUri = uri,
          originalFilename = resolveFileName(context, uri),
          mediaType = if (settings.backgroundType == "Video") "VIDEO" else "IMAGE",
          trackType = "Chroma Background"
        )
      )
    }

    return missingList
  }

  /** Missing-media id of the chroma background that belongs to one clip. */
  fun chromaBackgroundClipId(clipId: String): String = "$clipId$CHROMA_BACKGROUND_SUFFIX"

  /**
   * Relinks a specific missing media item to a newly provided URI and updates metadata.
   */
  fun relinkClip(context: Context, timeline: Timeline, clipId: String, newUri: String): Timeline {
    val meta = MediaMetadataHelper.extractMetadata(context, newUri)
    val newFileName = resolveFileName(context, newUri)

    var updated = timeline

    // Check main video clips
    val newVideoClips = timeline.videoClips.map { clip ->
      if (clip.id == clipId) {
        clip.copy(
          uri = newUri,
          name = if (clip.name.startsWith("Clip ") || clip.name == "Media File") newFileName else clip.name,
          width = meta.width,
          height = meta.height,
          frameRate = meta.frameRate,
          mimeType = meta.mimeType,
          hasAudio = meta.hasAudio,
          sourceEndMs = if (meta.durationMs > 0) minOf(clip.sourceEndMs, meta.durationMs) else clip.sourceEndMs
        )
      } else clip
    }
    updated = updated.copy(videoClips = newVideoClips)

    // Check overlay clips
    val newOverlayClips = timeline.overlayClips.map { clip ->
      if (clip.id == clipId) {
        clip.copy(
          uri = newUri,
          name = if (clip.name.startsWith("Clip ") || clip.name == "Media File") newFileName else clip.name,
          width = meta.width,
          height = meta.height,
          frameRate = meta.frameRate,
          mimeType = meta.mimeType,
          hasAudio = meta.hasAudio
        )
      } else clip
    }
    updated = updated.copy(overlayClips = newOverlayClips)

    // Check audio clips
    val newAudioClips = timeline.audioClips.map { clip ->
      if (clip.id == clipId) {
        clip.copy(
          uri = newUri,
          title = if (clip.title.startsWith("Audio ") || clip.title == "Media File") newFileName else clip.title,
          sourceEndMs = if (meta.durationMs > 0) minOf(clip.sourceEndMs, meta.durationMs) else clip.sourceEndMs
        )
      } else clip
    }
    updated = updated.copy(audioClips = newAudioClips)

    // Check chroma background (project default)
    if (clipId == "chroma_background") {
      updated = updated.copy(
        chromaKey = updated.chromaKey.copy(backgroundUri = newUri)
      )
    }

    // Per-clip chroma background: the item id carries the clip it belongs to.
    val ownerClipId = clipId.substringAfter("$CHROMA_BACKGROUND_SUFFIX", "").ifEmpty { null }
    if (ownerClipId != null) {
      fun relink(clip: com.example.domain.model.VideoClip): com.example.domain.model.VideoClip {
        val settings = clip.chromaKey ?: return clip
        if (clip.id != ownerClipId) return clip
        return clip.copy(chromaKey = settings.copy(backgroundUri = newUri))
      }
      updated = updated.copy(
        videoClips = updated.videoClips.map(::relink),
        overlayClips = updated.overlayClips.map(::relink)
      )
    }

    return updated
  }
}
