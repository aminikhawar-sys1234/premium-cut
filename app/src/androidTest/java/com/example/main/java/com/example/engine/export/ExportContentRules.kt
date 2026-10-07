package com.example.engine.export

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Why an exported MP4 was rejected. Stable codes so callers and tests don't match on message text. */
enum class ExportFailure {
  FILE_MISSING,
  FILE_TOO_SMALL,
  CONTAINER_UNREADABLE,
  MUXER_INCOMPLETE,
  ENCODER_FAILURE,
  NO_VIDEO_TRACK,
  AUDIO_ONLY,
  NO_AUDIO_TRACK,
  INVALID_DIMENSIONS,
  DIMENSION_MISMATCH,
  BAD_ROTATION,
  INVALID_DURATION,
  DURATION_MISMATCH,
  AV_DURATION_MISMATCH,
  ZERO_FRAMES,
  NO_KEYFRAME,
  BAD_TIMESTAMPS,
  TRUNCATED_VIDEO,
  MISSING_FRAMES,
  EMPTY_SAMPLES,
  FIRST_FRAME_UNDECODABLE,
  MIDDLE_FRAME_UNDECODABLE,
  LAST_FRAME_UNDECODABLE,
  ALL_FRAMES_BLACK,
}

/**
 * Pixel statistics + the black-frame heuristic.
 *
 * THRESHOLD (documented, deliberately conservative so dark/night footage is not rejected):
 * a frame counts as BLACK only when BOTH hold on a luma (BT.601, 0..255) sample grid:
 *   - the 99th-percentile luma is <= [BLACK_P99_LUMA] (16 = limited-range video black), i.e. not even
 *     1% of the sampled pixels is brighter than black (no street lamp, screen, star, highlight), and
 *   - the luma standard deviation (brightest 1% trimmed) is <= [BLACK_MAX_STDDEV] (2.0), i.e. no
 *     picture detail and no sensor noise. Real low-light footage has noise/texture (stddev well above 2) or highlights.
 * A whole export is rejected only when EVERY decoded sample (first, middle, last and, if those are
 * all black, nine more spread over the clip) is black, so a fade-in/out or one black frame never fails it.
 */
object FrameAnalyzer {
  const val BLACK_P99_LUMA = 16
  const val BLACK_MAX_STDDEV = 2.0

  data class Stats(val meanLuma: Double, val p99Luma: Int, val stdDevLuma: Double, val sampleCount: Int) {
    /** No pixels at all (0x0 bitmap): the frame carries nothing. */
    val isEmpty: Boolean get() = sampleCount <= 0
    val isBlack: Boolean get() = isEmpty || (p99Luma <= BLACK_P99_LUMA && stdDevLuma <= BLACK_MAX_STDDEV)
  }

  fun luma(argb: Int): Int {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return (299 * r + 587 * g + 114 * b) / 1000
  }

  fun analyze(pixels: IntArray): Stats {
    val n = pixels.size
    if (n == 0) return Stats(0.0, 0, 0.0, 0)
    val hist = IntArray(256)
    var sum = 0.0
    for (p in pixels) {
      val l = luma(p).coerceIn(0, 255)
      hist[l]++
      sum += l
    }
    val mean = sum / n
    val target = kotlin.math.ceil(n * 0.99).toInt()
    var cumulative = 0
    var p99 = 255
    for (v in 0..255) {
      cumulative += hist[v]
      if (cumulative >= target) { p99 = v; break }
    }
    // Spread is measured with the brightest 1% trimmed (everything above the 99th percentile), so a
    // lone stuck/hot pixel cannot make an otherwise empty frame look like it has picture detail.
    var tn = 0; var tSum = 0.0; var tSumSq = 0.0
    for (v in 0..p99) { val c = hist[v]; tn += c; tSum += c.toDouble() * v; tSumSq += c.toDouble() * v * v }
    val tMean = if (tn > 0) tSum / tn else 0.0
    val variance = if (tn > 0) max(0.0, tSumSq / tn - tMean * tMean) else 0.0
    return Stats(mean, p99, sqrt(variance), n)
  }
}

/** Walks the top-level MP4 boxes to catch files the muxer never finalised (no moov) or that are cut short. */
object Mp4BoxScanner {
  data class Result(
    val hasFtyp: Boolean,
    val hasMoov: Boolean,
    val hasMdat: Boolean,
    val truncated: Boolean,
    val problem: String?,
  )

  /** [header] returns up to 16 bytes starting at the offset (fewer near the end), or null if unreadable. */
  fun scan(length: Long, header: (Long) -> ByteArray?): Result {
    var ftyp = false; var moov = false; var mdat = false
    var truncated = false; var problem: String? = null
    var off = 0L
    while (off < length) {
      if (length - off < 8) { truncated = true; problem = "trailing ${length - off} byte(s) after the last box"; break }
      val h = header(off)
      if (h == null || h.size < 8) { truncated = true; problem = "unreadable box header at $off"; break }
      var size = u32(h, 0)
      val type = String(h, 4, 4, Charsets.ISO_8859_1)
      var headerLen = 8L
      if (size == 1L) {
        if (h.size < 16) { truncated = true; problem = "unreadable 64-bit box size at $off"; break }
        size = u64(h, 8); headerLen = 16L
      } else if (size == 0L) {
        size = length - off
      }
      if (size < headerLen) { problem = "invalid size $size for box '$type' at $off"; truncated = true; break }
      when (type) { "ftyp" -> ftyp = true; "moov" -> moov = true; "mdat" -> mdat = true }
      if (off + size > length) {
        truncated = true; problem = "box '$type' at $off needs ${off + size} bytes but the file has $length"
        // 'moov' that is cut short is not a usable moov.
        if (type == "moov") moov = false
        break
      }
      off += size
    }
    return Result(ftyp, moov, mdat, truncated, problem)
  }

  fun scan(file: File): Result = RandomAccessFile(file, "r").use { raf ->
    scan(raf.length()) { offset ->
      try {
        raf.seek(offset)
        val buf = ByteArray(16)
        val read = raf.read(buf)
        if (read <= 0) null else buf.copyOf(read)
      } catch (_: Exception) { null }
    }
  }

  private fun u32(b: ByteArray, o: Int): Long =
    ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
      ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

  private fun u64(b: ByteArray, o: Int): Long = (u32(b, o) shl 32) or u32(b, o + 4)
}

enum class FrameRole { FIRST, MIDDLE, LAST, EXTRA }

/** Result of trying to decode one frame. [stats] is null when it could not be decoded. */
data class FrameProbe(val role: FrameRole, val timeUs: Long, val stats: FrameAnalyzer.Stats?) {
  val decoded: Boolean get() = stats != null
}

/** Everything read from the file, with no Android types so the rules run on the plain JVM. */
class ExportContentProbe(
  val fileExists: Boolean = true,
  val fileBytes: Long = 1_000_000L,
  val containerReadable: Boolean = true,
  val containerError: String? = null,
  val boxes: Mp4BoxScanner.Result? = Mp4BoxScanner.Result(true, true, true, false, null),
  val hasVideoTrack: Boolean = true,
  val hasAudioTrack: Boolean = true,
  val videoMime: String? = "video/avc",
  val audioMime: String? = "audio/mp4a-latm",
  val width: Int = 1080,
  val height: Int = 1920,
  val videoDurationMs: Long = 10_000L,
  val audioDurationMs: Long = 10_000L,
  val containerDurationMs: Long = 10_000L,
  val rotationDegrees: Int = 0,
  /** Presentation time of every video sample in file order (decode order; may be unsorted with B-frames). */
  val sampleTimesUs: LongArray = LongArray(300) { it * 33_333L },
  val sampleBytes: Long = 3_000_000L,
  val hasSyncSample: Boolean = true,
  val frameRate: Int? = 30,
  /** First/middle/last decode attempts plus extra black-check samples. Empty when frame sampling is off. */
  val frames: List<FrameProbe> = emptyList(),
  /** Set when the export pipeline itself reported an encoder/EOS/muxer problem. */
  val pipelineFailure: String? = null,
)

data class ExportExpectations(
  val expectedDurationMs: Long,
  val requireAudio: Boolean = true,
  val expectedDimensions: Pair<Int, Int>? = null,
  /** The exporter bakes orientation into the pixels, so the container must carry no rotation. */
  val expectedRotation: Int = 0,
  val fallbackFrameRate: Int = 30,
  val checkFrames: Boolean = true,
)

data class ContentVerdict(val failure: ExportFailure, val message: String)

/** Pure decision logic for an exported MP4. The first failing rule wins. */
object ExportContentRules {
  const val MIN_FILE_BYTES = 4_096L
  /** Audio and video track lengths may differ by this much (AAC priming / last-frame rounding). */
  const val AV_TOLERANCE_MS = 500L
  const val AV_TOLERANCE_FRACTION = 0.05
  /** First video frame must start near zero. */
  const val MAX_START_OFFSET_MS = 500L
  /** A video sample must carry at least this many bytes on average, or the samples are empty shells. */
  const val MIN_AVG_SAMPLE_BYTES = 8L
  const val MIN_STALL_MS = 2_000L

  fun check(p: ExportContentProbe, e: ExportExpectations): ContentVerdict? {
    if (!p.fileExists) return fail(ExportFailure.FILE_MISSING, "Output file does not exist.")
    if (p.fileBytes <= MIN_FILE_BYTES) {
      return fail(ExportFailure.FILE_TOO_SMALL, "Output file is empty, incomplete, or too small (${p.fileBytes} bytes).")
    }
    p.pipelineFailure?.let { return fail(ExportFailure.ENCODER_FAILURE, "Export pipeline reported a failure: $it") }

    p.boxes?.let { b ->
      if (!b.hasFtyp) return fail(ExportFailure.CONTAINER_UNREADABLE, "File is not an MP4 (no ftyp box).")
      if (!b.hasMoov || b.truncated) {
        return fail(ExportFailure.MUXER_INCOMPLETE, "MP4 was not finalised by the muxer (${b.problem ?: "moov box missing"}).")
      }
    }
    if (!p.containerReadable) {
      return fail(ExportFailure.CONTAINER_UNREADABLE, "MP4 container could not be parsed (${p.containerError ?: "corrupted"}).")
    }

    if (!p.hasVideoTrack) {
      return if (p.hasAudioTrack) fail(ExportFailure.AUDIO_ONLY, "MP4 contains no video track (audio-only output rejected).")
      else fail(ExportFailure.NO_VIDEO_TRACK, "MP4 contains no video track.")
    }
    if (e.requireAudio && !p.hasAudioTrack) return fail(ExportFailure.NO_AUDIO_TRACK, "MP4 contains no audio track.")

    if (p.width <= 0 || p.height <= 0) {
      return fail(ExportFailure.INVALID_DIMENSIONS, "Invalid video dimensions ${p.width}x${p.height}.")
    }
    val rotation = ((p.rotationDegrees % 360) + 360) % 360
    if (rotation != ((e.expectedRotation % 360) + 360) % 360) {
      return fail(ExportFailure.BAD_ROTATION, "Exported video carries rotation metadata ($rotation°, expected ${e.expectedRotation}°); orientation would be wrong.")
    }
    e.expectedDimensions?.let { (ew, eh) ->
      if (abs(p.width - ew) > 16 || abs(p.height - eh) > 16) {
        return fail(ExportFailure.DIMENSION_MISMATCH, "Exported frame size ${p.width}x${p.height} does not match the planned ${ew}x$eh.")
      }
      if (ew != eh && ((p.width >= p.height) != (ew >= eh))) {
        return fail(ExportFailure.DIMENSION_MISMATCH, "Exported video orientation is wrong (${p.width}x${p.height}, expected ${ew}x$eh).")
      }
    }

    val videoMs = if (p.videoDurationMs > 0L) p.videoDurationMs else p.containerDurationMs
    if (videoMs <= 0L) return fail(ExportFailure.INVALID_DURATION, "Video duration is zero or missing.")
    if (e.expectedDurationMs > 0L) {
      val tolerance = max(1_000L, e.expectedDurationMs / 20)
      if (abs(videoMs - e.expectedDurationMs) > tolerance) {
        return fail(ExportFailure.DURATION_MISMATCH, "Duration mismatch (expected ~${e.expectedDurationMs}ms, file reports ${videoMs}ms).")
      }
    }
    if (p.hasAudioTrack && p.audioDurationMs > 0L) {
      val tol = max(AV_TOLERANCE_MS, (videoMs * AV_TOLERANCE_FRACTION).toLong())
      if (abs(p.audioDurationMs - videoMs) > tol) {
        return fail(ExportFailure.AV_DURATION_MISMATCH, "Audio (${p.audioDurationMs}ms) and video (${videoMs}ms) durations differ by more than ${tol}ms.")
      }
    }

    sampleChecks(p, e, videoMs)?.let { return it }
    if (e.checkFrames) frameChecks(p)?.let { return it }
    return null
  }

  private fun sampleChecks(p: ExportContentProbe, e: ExportExpectations, videoMs: Long): ContentVerdict? {
    val count = p.sampleTimesUs.size
    if (count == 0) return fail(ExportFailure.ZERO_FRAMES, "Video track contains no samples (zero-frame output).")
    if (!p.hasSyncSample) return fail(ExportFailure.NO_KEYFRAME, "Video track has no keyframe; it cannot be decoded.")

    val sorted = p.sampleTimesUs.sortedArray()
    if (sorted.first() < 0L) return fail(ExportFailure.BAD_TIMESTAMPS, "Video frame timestamps are negative.")
    for (i in 1 until sorted.size) {
      if (sorted[i] == sorted[i - 1]) return fail(ExportFailure.BAD_TIMESTAMPS, "Two video frames share timestamp ${sorted[i]}us.")
    }
    if (sorted.first() > MAX_START_OFFSET_MS * 1000L) {
      return fail(ExportFailure.BAD_TIMESTAMPS, "First video frame starts at ${sorted.first() / 1000L}ms instead of ~0.")
    }
    val lastUs = sorted.last()
    if (lastUs > (videoMs + max(1_000L, videoMs / 20)) * 1000L) {
      return fail(ExportFailure.BAD_TIMESTAMPS, "Last frame timestamp ${lastUs / 1000L}ms is beyond the ${videoMs}ms duration.")
    }
    if (count >= 4) {
      val gaps = LongArray(count - 1) { sorted[it + 1] - sorted[it] }
      val median = gaps.sortedArray()[gaps.size / 2]
      val maxGap = gaps.maxOrNull() ?: 0L
      if (maxGap > max(MIN_STALL_MS * 1000L, median * 30L)) {
        return fail(ExportFailure.BAD_TIMESTAMPS, "Video stalls: a ${maxGap / 1000L}ms gap between frames (typical ${median / 1000L}ms).")
      }
    }
    if (p.sampleBytes <= 0L || p.sampleBytes / count < MIN_AVG_SAMPLE_BYTES) {
      return fail(ExportFailure.EMPTY_SAMPLES, "Video samples carry no data (${p.sampleBytes} bytes in $count samples); no meaningful frames.")
    }
    if (e.expectedDurationMs > 0L && lastUs < e.expectedDurationMs * 1000L * 85 / 100) {
      return fail(ExportFailure.TRUNCATED_VIDEO, "Video track ends early (last frame at ${lastUs / 1000L}ms of ${e.expectedDurationMs}ms).")
    }
    val fps = p.frameRate ?: e.fallbackFrameRate
    val expectedFrames = if (e.expectedDurationMs > 0L) e.expectedDurationMs * fps / 1000L else 0L
    if (expectedFrames >= 4L && count * 2L < expectedFrames) {
      return fail(ExportFailure.MISSING_FRAMES, "Video track is missing most frames ($count of ~$expectedFrames).")
    }
    return null
  }

  private fun frameChecks(p: ExportContentProbe): ContentVerdict? {
    if (p.frames.isEmpty()) return null
    fun role(r: FrameRole) = p.frames.firstOrNull { it.role == r }
    if (role(FrameRole.FIRST)?.decoded != true) {
      return fail(ExportFailure.FIRST_FRAME_UNDECODABLE, "The first video frame could not be decoded.")
    }
    if (role(FrameRole.MIDDLE)?.decoded != true) {
      return fail(ExportFailure.MIDDLE_FRAME_UNDECODABLE, "The middle video frame could not be decoded.")
    }
    if (role(FrameRole.LAST)?.decoded != true) {
      return fail(ExportFailure.LAST_FRAME_UNDECODABLE, "The last video frame could not be decoded.")
    }
    val decoded = p.frames.mapNotNull { it.stats }
    if (decoded.all { it.isBlack }) {
      return fail(ExportFailure.ALL_FRAMES_BLACK,
        "Every sampled frame of the exported video is black (p99 luma <= ${FrameAnalyzer.BLACK_P99_LUMA}, stddev <= ${FrameAnalyzer.BLACK_MAX_STDDEV}).")
    }
    return null
  }

  private fun fail(f: ExportFailure, m: String) = ContentVerdict(f, m)
}
