package com.example.engine.color

import android.content.Context
import android.util.Log
import com.ahstudio.color.ColorEngine
import com.ahstudio.color.core.ColorState
import com.ahstudio.color.lut.DefaultLutRepository
import com.ahstudio.color.lut.LutRepository
import com.ahstudio.color.serialize.ColorStateCodec
import com.example.domain.model.VideoClip
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * App-wide owner of the per-clip [ColorEngine] render state.
 *
 * The timeline (VideoClip.colorGradeJson) is the source of truth: it is what gets saved,
 * undone/redone and exported. [syncFromTimeline] mirrors it into the engine, so undo, redo,
 * project load, split and duplicate all update the render state without extra code.
 * GPU resources are NOT shared: each GL context owns its own ColorGradeStage.
 */
object ColorEngineHost {
  private const val TAG = "ColorEngineHost"

  /** Bundled LUT ids (file stems in assets/luts) with display names. */
  val BUNDLED_LUTS: List<Pair<String, String>> = listOf(
    "teal_orange" to "Teal & Orange",
    "bleach_bypass" to "Bleach Bypass",
    "warm_vintage" to "Warm Vintage",
    "moody_film" to "Moody Film",
    "bw_contrast" to "B&W Contrast",
    "cinematic_green" to "Cinematic Green"
  )

  @Volatile private var engine: ColorEngine? = null
  // Last JSON pushed into the engine per clip; lets sync skip unchanged clips (no per-emit decoding).
  private val syncedJson = ConcurrentHashMap<String, String>()
  private val bypassed = ConcurrentHashMap.newKeySet<String>()

  fun init(context: Context) {
    if (engine != null) return
    val dir = File(context.applicationContext.filesDir, "color_luts")
    initWithRepository(DefaultLutRepository(dir))
    copyBundledLuts(context.applicationContext, dir)
  }

  fun initWithRepository(repo: LutRepository) {
    synchronized(this) { if (engine == null) engine = ColorEngine(repo) }
  }

  private fun copyBundledLuts(context: Context, dir: File) {
    runCatching {
      dir.mkdirs()
      BUNDLED_LUTS.forEach { (id, _) ->
        val out = File(dir, "$id.cube")
        if (!out.exists()) context.assets.open("luts/$id.cube").use { input -> out.outputStream().use { input.copyTo(it) } }
      }
    }.onFailure { Log.w(TAG, "Bundled LUT copy failed", it) }
  }

  fun engineOrNull(): ColorEngine? = engine

  fun encode(state: ColorState): String = ColorStateCodec.encode(state)

  fun decodeOrDefault(json: String?): ColorState =
    if (json.isNullOrBlank()) ColorState() else runCatching { ColorStateCodec.decode(json) }.getOrDefault(ColorState())

  /** Applies a grade to the render engine immediately (used for live slider feedback). */
  fun applyToEngine(clipId: String, state: ColorState) {
    val e = engine ?: return
    e.setState(clipId, state)
    state.lut?.takeIf { it.isValid() }?.let { e.setLut(clipId, it) }
    syncedJson[clipId] = encode(state)
  }

  /** Mirrors timeline grades into the engine. Call whenever the timeline changes. */
  fun syncFromTimeline(clips: List<VideoClip>) {
    val e = engine ?: return
    val present = HashSet<String>(clips.size)
    for (clip in clips) {
      present.add(clip.id)
      val json = clip.colorGradeJson
      if (json == null) {
        if (syncedJson.remove(clip.id) != null) e.reset(clip.id)
      } else if (syncedJson[clip.id] != json) {
        applyToEngine(clip.id, decodeOrDefault(json))
        syncedJson[clip.id] = json
      }
    }
    // Clips that disappeared (deleted, undone away) must stop grading.
    syncedJson.keys.filter { it !in present }.forEach { syncedJson.remove(it); e.reset(it); bypassed.remove(it) }
  }

  fun isLutReady(lutId: String): Boolean = engine?.resolveLut3d(lutId) != null

  fun gradeOf(clipId: String): ColorState = engine?.state(clipId) ?: ColorState()

  fun hasGrade(clipId: String): Boolean = engine?.isIdentity(clipId) == false

  /** Before/After toggle: render-only, never touches the stored grade. Cleared when the panel closes. */
  fun setBypass(clipId: String, on: Boolean) { if (on) bypassed.add(clipId) else bypassed.remove(clipId) }

  fun isBypassed(clipId: String): Boolean = bypassed.contains(clipId)

  fun clearAllBypass() = bypassed.clear()
}
