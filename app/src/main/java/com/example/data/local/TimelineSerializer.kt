package com.example.data.local

import com.example.domain.model.MediaReference
import com.example.domain.model.ProjectPackage
import com.example.domain.model.ProjectSettings
import com.example.domain.model.SourceMetadata
import com.example.domain.model.Timeline
import com.example.domain.model.isCompoundContainer
import com.squareup.moshi.FromJson
import com.squareup.moshi.Moshi
import com.squareup.moshi.ToJson
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.json.JSONObject

class PairJsonAdapter {
  @ToJson
  fun toJson(pair: Pair<Float, Float>): List<Float> {
    return listOf(pair.first, pair.second)
  }

  @FromJson
  fun fromJson(list: List<Float>): Pair<Float, Float> {
    return if (list.size >= 2) Pair(list[0], list[1]) else Pair(0f, 0f)
  }
}

object TimelineSerializer {
  private val moshi: Moshi = Moshi.Builder()
    .add(PairJsonAdapter())
    .add(KotlinJsonAdapterFactory())
    .build()

  private val timelineAdapter = moshi.adapter(Timeline::class.java)
  private val projectPackageAdapter = moshi.adapter(ProjectPackage::class.java)
  private val projectSettingsAdapter = moshi.adapter(ProjectSettings::class.java)

  fun serializeTimeline(timeline: Timeline): String {
    return timelineAdapter.toJson(timeline)
  }

  /**
   * Constructs a complete, self-contained ProjectPackage extracted from the timeline and settings.
   */
  fun buildProjectPackage(
    projectId: String,
    projectName: String,
    settings: ProjectSettings,
    timeline: Timeline,
    isDraft: Boolean = false
  ): ProjectPackage {
    val mediaRefs = mutableListOf<MediaReference>()
    val sourceMetas = mutableListOf<SourceMetadata>()
    collectTimelineMedia(timeline, settings, mediaRefs, sourceMetas)

    // Extract from Chroma Key Background (project default)
    fun addChromaBackground(clipId: String, uri: String?, isVideo: Boolean) {
      if (uri.isNullOrBlank()) return
      mediaRefs.add(
        MediaReference(
          clipId = clipId,
          uri = uri,
          originalPath = uri,
          filename = uri.substringAfterLast("/").ifBlank { "Chroma Background" },
          mediaType = if (isVideo) "VIDEO" else "IMAGE",
          mimeType = if (isVideo) "video/mp4" else "image/jpeg"
        )
      )
    }
    addChromaBackground(
      clipId = "chroma_background",
      uri = timeline.chromaKey.backgroundUri,
      isVideo = timeline.chromaKey.backgroundType == "Video"
    )
    // Per-clip chroma backgrounds ship with the project too, so a reopened project still has its media.
    (timeline.videoClips + timeline.overlayClips).forEach { clip ->
      val settings = clip.chromaKey ?: return@forEach
      if (settings.backgroundType != "Image" && settings.backgroundType != "Video") return@forEach
      addChromaBackground(
        clipId = "${clip.id}::chroma_background",
        uri = settings.backgroundUri,
        isVideo = settings.backgroundType == "Video"
      )
    }

    return ProjectPackage(
      version = 2,
      projectId = projectId,
      projectName = projectName,
      settings = settings,
      mediaReferences = mediaRefs,
      sourceMetadata = sourceMetas,
      timeline = timeline,
      isDraft = isDraft
    )
  }

  private fun collectTimelineMedia(
    timeline: Timeline,
    settings: ProjectSettings,
    mediaRefs: MutableList<MediaReference>,
    sourceMetas: MutableList<SourceMetadata>
  ) {
    timeline.videoClips.forEach { clip ->
      if (clip.uri.isNotBlank() && !clip.isCompoundContainer()) {
        val fileName = clip.uri.substringAfterLast("/").ifBlank { clip.name }
        mediaRefs.add(
          MediaReference(
            clipId = clip.id,
            uri = clip.uri,
            originalPath = clip.uri,
            filename = fileName,
            mediaType = if (clip.isVideo) "VIDEO" else "IMAGE",
            mimeType = clip.mimeType
          )
        )
        sourceMetas.add(
          SourceMetadata(
            clipId = clip.id,
            width = clip.width,
            height = clip.height,
            naturalRotation = clip.naturalRotation,
            durationMs = clip.sourceEndMs - clip.sourceStartMs,
            frameRate = clip.frameRate,
            videoCodec = if (clip.mimeType.contains("hevc", ignoreCase = true)) "hevc" else "h264"
          )
        )
      }
    }
    timeline.overlayClips.forEach { clip ->
      if (clip.uri.isNotBlank() && !clip.isCompoundContainer()) {
        val fileName = clip.uri.substringAfterLast("/").ifBlank { clip.name }
        mediaRefs.add(
          MediaReference(
            clipId = clip.id,
            uri = clip.uri,
            originalPath = clip.uri,
            filename = fileName,
            mediaType = if (clip.isVideo) "VIDEO" else "IMAGE",
            mimeType = clip.mimeType
          )
        )
        sourceMetas.add(
          SourceMetadata(
            clipId = clip.id,
            width = clip.width,
            height = clip.height,
            naturalRotation = clip.naturalRotation,
            durationMs = clip.sourceEndMs - clip.sourceStartMs,
            frameRate = clip.frameRate
          )
        )
      }
    }
    timeline.audioClips.forEach { clip ->
      if (clip.uri.isNotBlank()) {
        val fileName = clip.uri.substringAfterLast("/").ifBlank { clip.title }
        mediaRefs.add(
          MediaReference(
            clipId = clip.id,
            uri = clip.uri,
            originalPath = clip.uri,
            filename = fileName,
            mediaType = "AUDIO",
            mimeType = "audio/mpeg"
          )
        )
        sourceMetas.add(
          SourceMetadata(
            clipId = clip.id,
            durationMs = clip.sourceEndMs - clip.sourceStartMs,
            audioChannels = 2,
            audioSampleRate = settings.sampleRateHz,
            audioCodec = "aac"
          )
        )
      }
    }
    timeline.compoundClips.forEach { compound ->
      collectTimelineMedia(compound.nested, settings, mediaRefs, sourceMetas)
    }
  }

  fun toJson(timeline: Timeline): String {
    return try {
      timelineAdapter.toJson(timeline)
    } catch (e: Exception) {
      "{}"
    }
  }

  /**
   * Parses a stored timeline. Returns an empty Timeline ONLY for genuinely empty input (blank or "{}").
   * Returns null when non-empty JSON could not be parsed, so callers can refuse to open (and later
   * overwrite) a project whose data is unreadable instead of silently presenting an empty one.
   */
  fun fromJsonOrNull(json: String): Timeline? {
    if (json.isBlank() || json == "{}") return Timeline()
    return try {
      // Check if this is a v2 ProjectPackage wrapper
      if (json.contains("\"timeline\"") && (json.contains("\"settings\"") || json.contains("\"mediaReferences\""))) {
        val jsonObject = JSONObject(json)
        if (jsonObject.has("timeline")) {
          val timelineSubJson = jsonObject.getJSONObject("timeline").toString()
          timelineAdapter.fromJson(timelineSubJson)
        } else {
          timelineAdapter.fromJson(json)
        }
      } else {
        timelineAdapter.fromJson(json)
      }
    } catch (e: Exception) {
      android.util.Log.e("TimelineSerializer", "Timeline JSON parse failed, trying direct adapter", e)
      try {
        timelineAdapter.fromJson(json)
      } catch (fallbackEx: Exception) {
        android.util.Log.e("TimelineSerializer", "Timeline JSON is unreadable", fallbackEx)
        null
      }
    }
  }

  /** Legacy lenient entry point (templates, reliability snapshots): unreadable input yields an empty Timeline. */
  fun fromJson(json: String): Timeline = fromJsonOrNull(json) ?: Timeline()

  fun toPackageJson(projectPackage: ProjectPackage): String {
    return try {
      projectPackageAdapter.toJson(projectPackage)
    } catch (e: Exception) {
      // Do NOT degrade to a "{}" timeline here: that would overwrite the stored project with an empty one.
      // Failing the save keeps the previous saved version and the crash-recovery session intact.
      throw IllegalStateException("Project serialization failed: ${e.message}", e)
    }
  }

  fun fromPackageJson(json: String): ProjectPackage? {
    if (json.isBlank()) return null
    return try {
      projectPackageAdapter.fromJson(json)
    } catch (e: Exception) {
      android.util.Log.e("TimelineSerializer", "Project package parse failed, trying bare timeline", e)
      // Fallback construct package from a bare timeline; null when that is unreadable too.
      fromJsonOrNull(json)?.let { timeline ->
        ProjectPackage(
          projectId = "",
          projectName = "Restored Project",
          timeline = timeline
        )
      }
    }
  }

  fun settingsToJson(settings: ProjectSettings): String {
    return try {
      projectSettingsAdapter.toJson(settings)
    } catch (e: Exception) {
      "{}"
    }
  }

  fun settingsFromJson(json: String): ProjectSettings {
    if (json.isBlank() || json == "{}") return ProjectSettings()
    return try {
      projectSettingsAdapter.fromJson(json) ?: ProjectSettings()
    } catch (e: Exception) {
      ProjectSettings()
    }
  }
}

