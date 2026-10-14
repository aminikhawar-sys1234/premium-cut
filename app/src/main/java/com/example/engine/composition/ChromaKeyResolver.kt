package com.example.engine.composition

import com.example.domain.model.ChromaKeySettings
import com.example.domain.model.Timeline
import com.example.domain.model.VideoClip
import kotlin.math.max
import kotlin.math.min

/**
 * Where a chroma-key setting applies, and what the keyer needs to draw it.
 *
 * Settings live **on the clip** (`VideoClip.chromaKey`), so keying one layer never keys the rest of the
 * project and the values save / undo / redo with the timeline. `Timeline.chromaKey` keeps its old meaning
 * as the *project default*: it is used for clips that have no per-clip setting, which keeps projects saved
 * before per-clip keying (and the "apply to all clips" action) working exactly as before.
 *
 * Pure Kotlin (domain models only) so the JVM test harness can execute it.
 */
object ChromaKeyResolver {

  /** Stable id of the synthetic clip that carries a chroma background image / video. Never a timeline clip. */
  const val BACKGROUND_CLIP_ID = "chroma_background"

  /** How long one loop of a background video is assumed to be when its real duration is unknown. */
  const val BACKGROUND_VIDEO_LOOP_MS = 60_000L

  /**
   * The settings the given layer draws with. A clip that carries its own settings always wins, including
   * when they are disabled (an explicit "no keying here" beats the project default). Clips without their
   * own settings inherit the project default.
   */
  fun settingsFor(clip: VideoClip?, projectDefault: ChromaKeySettings): ChromaKeySettings {
    clip?.chromaKey?.let { return it }
    return projectDefault
  }

  /** Settings actually used to key [clip], or null when that layer is not keyed at all. */
  fun activeSettingsFor(clip: VideoClip?, projectDefault: ChromaKeySettings): ChromaKeySettings? =
    settingsFor(clip, projectDefault).takeIf { it.enabled }

  /** True when the keyed layer draws over an imported image or video instead of transparency / a colour. */
  fun usesBackgroundMedia(settings: ChromaKeySettings?): Boolean {
    val s = settings ?: return false
    if (!s.enabled) return false
    if (s.backgroundUri.isNullOrBlank()) return false
    return s.backgroundType == BACKGROUND_IMAGE || s.backgroundType == BACKGROUND_VIDEO
  }

  /**
   * True when the key paints a background of its own (solid colour or imported media) instead of leaving
   * transparency. Background removal must not run on such a layer: its mask cuts exactly the pixels the key
   * just filled, so the two tools would silently cancel each other out. A transparent key keeps both.
   */
  fun replacesBackground(settings: ChromaKeySettings?): Boolean =
    usesSolidBackground(settings) || usesBackgroundMedia(settings)

  /** True when the keyed layer is filled with a solid colour. */
  fun usesSolidBackground(settings: ChromaKeySettings?): Boolean =
    settings?.enabled == true && settings.backgroundType == BACKGROUND_SOLID

  /**
   * A stand-in clip for the background media. It is never added to the timeline; it only gives the frame
   * planner, the decoders and the export pre-loader one common description of "this picture, at this time".
   */
  fun backgroundClip(settings: ChromaKeySettings?): VideoClip? {
    if (!usesBackgroundMedia(settings)) return null
    val uri = settings!!.backgroundUri!!.trim()
    if (uri.isEmpty()) return null
    val isVideo = settings.backgroundType == BACKGROUND_VIDEO
    val duration = if (isVideo) BACKGROUND_VIDEO_LOOP_MS else 1L
    return VideoClip(
      id = BACKGROUND_CLIP_ID,
      uri = uri,
      name = "Chroma Background",
      isVideo = isVideo,
      timelineStartMs = 0L,
      durationMs = duration,
      sourceStartMs = 0L,
      sourceEndMs = duration,
      width = 0,
      height = 0,
      hasAudio = false
    )
  }

  /**
   * Source time to sample the background video at, looping it over the whole timeline.
   *
   * [loopMs] is the background video's own duration when the caller knows it (both the preview and the
   * export hand over the decoded clip's duration), so a short background actually loops instead of playing
   * once and freezing. [BACKGROUND_VIDEO_LOOP_MS] is only the fallback for an unknown duration.
   */
  fun backgroundSourceMs(
    settings: ChromaKeySettings?,
    timelinePosMs: Long,
    loopMs: Long = BACKGROUND_VIDEO_LOOP_MS
  ): Long {
    if (!usesBackgroundMedia(settings)) return 0L
    val loop = if (loopMs > 0L) loopMs else BACKGROUND_VIDEO_LOOP_MS
    val pos = timelinePosMs.coerceAtLeast(0L)
    return pos % loop
  }

  /**
   * Image URIs the export pre-loader must have as GPU textures: the project default plus every clip's own
   * setting, because any of them can be the one the frame draws with.
   */
  fun imageBackgroundUris(timeline: Timeline): List<String> {
    val uris = LinkedHashSet<String>()
    val candidates = buildList {
      add(timeline.chromaKey)
      timeline.videoClips.forEach { it.chromaKey?.let(::add) }
      timeline.overlayClips.forEach { it.chromaKey?.let(::add) }
    }
    for (settings in candidates) {
      if (usesBackgroundMedia(settings) && settings.backgroundType == BACKGROUND_IMAGE) {
        settings.backgroundUri?.trim()?.takeIf { it.isNotEmpty() }?.let(uris::add)
      }
    }
    return uris.toList()
  }

  /**
   * True when the frame cannot be drawn by the plain player path: some layer needs a per-pixel key or a
   * segmentation mask. The editor uses this to switch the preview to the GPU compositor, which is the only
   * preview path that runs these two effects (and the path export already uses, so both agree).
   */
  fun requiresComposition(timeline: Timeline): Boolean {
    val clips = timeline.videoClips + timeline.overlayClips
    if (clips.any { it.isBackgroundRemoved }) return true
    if (clips.any { it.chromaKey?.enabled == true }) return true
    if (clips.any { it.chromaKey == null } && timeline.chromaKey.enabled) return true
    return false
  }

  /**
   * uv transform that draws a background of [bgWidth] x [bgHeight] over a [viewportWidth] x
   * [viewportHeight] frame without distorting it: the shorter side is cropped ("cover"), never stretched.
   *
   * Returned as [scaleX, scaleY] for `uv = (screenUv - 0.5) * scale + 0.5`, so 1/1 keeps the picture as is.
   * Unknown sizes (0) keep the picture as is as well.
   */
  fun coverFit(bgWidth: Int, bgHeight: Int, viewportWidth: Int, viewportHeight: Int): FloatArray {
    if (bgWidth <= 0 || bgHeight <= 0 || viewportWidth <= 0 || viewportHeight <= 0) {
      return floatArrayOf(1f, 1f)
    }
    val bgAspect = bgWidth.toFloat() / bgHeight.toFloat()
    val vpAspect = viewportWidth.toFloat() / viewportHeight.toFloat()
    return if (bgAspect > vpAspect) {
      // Background is relatively wider: crop left/right, fill vertically.
      floatArrayOf(vpAspect / bgAspect, 1f)
    } else {
      floatArrayOf(1f, bgAspect / vpAspect)
    }
  }

  /** Shader background mode id, kept in one place so the GLSL and the Kotlin agree. */
  fun backgroundModeId(settings: ChromaKeySettings?): Int = when {
    usesSolidBackground(settings) -> BACKGROUND_MODE_SOLID
    usesBackgroundMedia(settings) -> BACKGROUND_MODE_TEXTURE
    else -> BACKGROUND_MODE_TRANSPARENT
  }

  const val BACKGROUND_TRANSPARENT = "Transparent"
  const val BACKGROUND_SOLID = "SolidColor"
  const val BACKGROUND_IMAGE = "Image"
  const val BACKGROUND_VIDEO = "Video"

  const val BACKGROUND_MODE_TRANSPARENT = 0
  const val BACKGROUND_MODE_SOLID = 1
  const val BACKGROUND_MODE_TEXTURE = 2

  /** Blend a keyed (possibly soft) subject pixel over a background colour. */
  fun compositeOver(background: Int, keyedArgb: Int): Int {
    val a = (keyedArgb ushr 24) and 0xFF
    if (a >= 255) return keyedArgb
    val inv = (255 - a) / 255f
    val af = a / 255f
    val r = (((keyedArgb ushr 16) and 0xFF) * af + ((background ushr 16) and 0xFF) * inv + 0.5f).toInt()
    val g = (((keyedArgb ushr 8) and 0xFF) * af + ((background ushr 8) and 0xFF) * inv + 0.5f).toInt()
    val b = ((keyedArgb and 0xFF) * af + (background and 0xFF) * inv + 0.5f).toInt()
    return (255 shl 24) or
      (r.coerceIn(0, 255) shl 16) or
      (g.coerceIn(0, 255) shl 8) or
      b.coerceIn(0, 255)
  }

  /** Key-colour presets shared by the panel and the tests (label, ARGB). */
  val KEY_PRESETS: List<Pair<String, Long>> = listOf(
    "Green Screen" to 0xFF00B140L,
    "Pure Green" to 0xFF00FF00L,
    "Blue Screen" to 0xFF0047BBL,
    "Pure Blue" to 0xFF0000FFL,
    "Cyan" to 0xFF00FFFFL,
    "Magenta" to 0xFFFF00FFL,
    "Red" to 0xFFFF0000L,
    "White" to 0xFFFFFFFFL,
    "Black" to 0xFF000000L
  )

  /** Keeps a value inside a slider range without importing Compose. */
  fun clamp(value: Float, min: Float, max: Float): Float = min(max, max(min, value))
}
