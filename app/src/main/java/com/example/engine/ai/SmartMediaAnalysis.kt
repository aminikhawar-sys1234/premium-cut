package com.example.engine.ai

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.example.domain.model.AudioClip
import com.example.domain.model.Timeline
import com.example.domain.model.VideoClip
import com.example.engine.ai.reframe.AutoReframePlanner
import com.example.engine.ai.reframe.ReframeKey
import com.example.engine.ai.reframe.SubjectSample
import com.example.engine.ai.scene.SceneCutDetector
import com.example.engine.audio.BeatDetector
import com.example.engine.export.AudioExportProcessor
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TimelineBeats(val bpm: Double, val beatsMs: List<Long>, val confidence: Float, val sourceName: String)

data class ReframePlan(val keys: List<ReframeKey>, val subjectFoundRatio: Float)

/**
 * Device-side glue for the smart tools: it decodes audio and video frames and hands plain numbers to the
 * pure engines (beat detector, scene cut detector, reframe planner), which hold all the logic and the tests.
 */
object SmartMediaAnalysis {

  private const val MAX_BEAT_ANALYSIS_MS = 4 * 60_000L
  private const val SCENE_SAMPLE_MS = 250L
  private const val MAX_SCENE_SAMPLES = 1500
  private const val SIGNATURE_SIZE = 64
  private const val REFRAME_SAMPLE_MS = 400L
  private const val MAX_REFRAME_SAMPLES = 160
  private const val DETECT_WIDTH = 480

  /** Beats of the longest music / audio clip on the timeline (own audio), or of the main video's sound when no audio clip exists. */
  suspend fun analyzeBeats(context: Context, timeline: Timeline): TimelineBeats? = withContext(Dispatchers.Default) {
    val audio: AudioClip? = timeline.audioClips
      .filter { !it.isMuted && !it.isReversed && it.durationMs > 2000L }
      .sortedWith(compareBy<AudioClip> { it.isVoiceOver }.thenByDescending { it.durationMs })
      .firstOrNull()
    val uri: String
    val name: String
    val startMs: Long
    val endMs: Long
    val timelineStart: Long
    val timelineEnd: Long
    val speed: Float
    if (audio != null) {
      uri = audio.uri; name = audio.title; startMs = audio.sourceStartMs; endMs = audio.sourceEndMs
      timelineStart = audio.timelineStartMs; timelineEnd = audio.timelineStartMs + audio.durationMs; speed = audio.speed
    } else {
      val video = timeline.videoClips.filter { it.hasAudio && !it.isMuted && !it.isReversed && it.isVideo && it.durationMs > 2000L }
        .maxByOrNull { it.durationMs } ?: return@withContext null
      uri = video.uri; name = video.name; startMs = video.sourceStartMs; endMs = video.sourceEndMs
      timelineStart = video.timelineStartMs; timelineEnd = video.timelineStartMs + video.durationMs; speed = video.speed
    }
    val window = (endMs - startMs).coerceIn(0L, MAX_BEAT_ANALYSIS_MS)
    val pcm = AudioExportProcessor(context).decodeForAnalysis(uri, startMs, startMs + window) ?: return@withContext null
    val (mono, rate) = BeatDetector.toMono(pcm.samples, pcm.channels, pcm.sampleRate)
    val analysis = BeatDetector.analyze(mono, rate)
    if (!analysis.hasBeat) return@withContext null
    val safeSpeed = speed.coerceIn(0.1f, 8f)
    val beats = analysis.beatsMs
      .map { timelineStart + (it / safeSpeed).toLong() }
      .filter { it in timelineStart until timelineEnd }
    if (beats.size < 2) null else TimelineBeats(analysis.bpm, beats, analysis.confidence, name)
  }

  /** Timeline times (ms) where the picture of [clip] changes to a new shot. */
  suspend fun detectSceneChanges(context: Context, clip: VideoClip, sensitivity: Float): List<Long> =
    withContext(Dispatchers.Default) {
      if (!clip.isVideo || clip.uri.isBlank()) return@withContext emptyList()
      val retriever = openRetriever(context, clip.uri) ?: return@withContext emptyList()
      try {
        val lo = minOf(clip.sourceStartMs, clip.sourceEndMs)
        val hi = maxOf(clip.sourceStartMs, clip.sourceEndMs)
        val step = max(SCENE_SAMPLE_MS, (hi - lo) / MAX_SCENE_SAMPLES)
        val times = ArrayList<Long>()
        var t = lo
        while (t < hi) { times += t; t += step }
        times += hi - 1
        val cuts = SceneCutDetector.findCuts(times, sensitivity) { sourceMs ->
          val frame = frameAt(retriever, sourceMs, SIGNATURE_SIZE, SIGNATURE_SIZE, exact = true)
          if (frame == null) SceneCutDetector.signature(IntArray(0), 0, 0) else signatureOf(frame)
        }
        cuts.map { clip.sourceToTimelineMs(it) }
          .filter { it > clip.timelineStartMs + 100L && it < clip.timelineStartMs + clip.durationMs - 100L }
          .distinct()
      } finally {
        runCatching { retriever.release() }
      }
    }

  /**
   * Plans a follow-the-subject crop of [clip] for a canvas with [canvasAspect] (width / height).
   * Returns null when the clip already fits the canvas or is not a video.
   */
  suspend fun planAutoReframe(context: Context, clip: VideoClip, canvasAspect: Float): ReframePlan? =
    withContext(Dispatchers.Default) {
      if (!clip.isVideo || clip.uri.isBlank() || clip.rotationDegrees % 360 != 0) return@withContext null
      val retriever = openRetriever(context, clip.uri) ?: return@withContext null
      try {
        val first = frameAt(retriever, clip.timelineToSourceMs(clip.timelineStartMs), DETECT_WIDTH, 0, exact = false)
          ?: return@withContext null
        val sourceAspect = first.width.toFloat() / first.height.toFloat()
        if (!AutoReframePlanner.needsReframe(sourceAspect, canvasAspect)) return@withContext null

        val step = max(REFRAME_SAMPLE_MS, clip.durationMs / MAX_REFRAME_SAMPLES)
        val samples = ArrayList<SubjectSample>()
        var rel = 0L
        while (rel <= clip.durationMs) {
          val sourceMs = clip.timelineToSourceMs(clip.timelineStartMs + rel)
          val frame = if (rel == 0L) first else frameAt(retriever, sourceMs, DETECT_WIDTH, 0, exact = false)
          samples += if (frame == null) SubjectSample(rel, 0.5f, 0.5f, found = false) else locateSubject(frame, rel, clip.flipHorizontal)
          rel += step
        }
        val found = samples.count { it.found }.toFloat() / samples.size.coerceAtLeast(1)
        ReframePlan(AutoReframePlanner.plan(samples, sourceAspect, canvasAspect, clip.durationMs), found)
      } finally {
        runCatching { retriever.release() }
      }
    }

  private fun locateSubject(frame: Bitmap, relMs: Long, flipX: Boolean): SubjectSample {
    val image = InputImage.fromBitmap(frame, 0)
    val w = frame.width.toFloat()
    val h = frame.height.toFloat()
    fun sample(cx: Float, cy: Float): SubjectSample = SubjectSample(relMs, if (flipX) 1f - cx else cx, cy, true)

    val faces = runCatching { Tasks.await(MlKitDetectorPool.faceLockDetector().process(image), 8, TimeUnit.SECONDS) }.getOrNull().orEmpty()
    faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }?.let {
      return sample(it.boundingBox.exactCenterX() / w, it.boundingBox.exactCenterY() / h)
    }
    val objects = runCatching { Tasks.await(MlKitDetectorPool.objectDetector().process(image), 8, TimeUnit.SECONDS) }.getOrNull().orEmpty()
    objects.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }?.let {
      return sample(it.boundingBox.exactCenterX() / w, it.boundingBox.exactCenterY() / h)
    }
    return SubjectSample(relMs, 0.5f, 0.5f, found = false)
  }

  private fun signatureOf(frame: Bitmap) = SceneCutDetector.signature(
    IntArray(frame.width * frame.height).also { frame.getPixels(it, 0, frame.width, 0, 0, frame.width, frame.height) },
    frame.width,
    frame.height
  )

  private fun openRetriever(context: Context, uriString: String): MediaMetadataRetriever? {
    val retriever = MediaMetadataRetriever()
    return try {
      val uri = Uri.parse(uriString)
      if (uri.scheme == "content" || uri.scheme == "file") retriever.setDataSource(context, uri)
      else {
        val f = File(uriString)
        if (f.exists()) retriever.setDataSource(f.absolutePath) else retriever.setDataSource(uriString, HashMap())
      }
      retriever
    } catch (_: Exception) {
      runCatching { retriever.release() }
      null
    }
  }

  /** A decoded frame scaled to [width] (height from the aspect when 0). [exact] asks for the real frame, not the nearest key frame. */
  private fun frameAt(retriever: MediaMetadataRetriever, sourceMs: Long, width: Int, height: Int, exact: Boolean): Bitmap? {
    val option = if (exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC
    return try {
      val timeUs = sourceMs.coerceAtLeast(0L) * 1000L
      if (height > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        retriever.getScaledFrameAtTime(timeUs, option, width, height)
      } else {
        val full = retriever.getFrameAtTime(timeUs, option) ?: return null
        val h = if (height > 0) height else (full.height * width.toFloat() / full.width).toInt().coerceAtLeast(1)
        if (full.width == width && full.height == h) full
        else Bitmap.createScaledBitmap(full, width, h, true).also { if (it !== full) full.recycle() }
      }
    } catch (_: Exception) {
      null
    }
  }
}
