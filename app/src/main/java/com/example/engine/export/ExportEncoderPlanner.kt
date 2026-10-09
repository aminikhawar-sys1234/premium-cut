package com.example.engine.export

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import com.example.domain.model.AspectRatio
import com.example.domain.model.ExportQuality
import com.example.domain.model.Resolution
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Single source of truth for export frame sizes (used by the export dialog, the
 * GPU pipeline and the Media3 path) so what the user sees is what gets encoded.
 *
 * Convention: the resolution's short side (720/1080/2160...) is the short side of the output for
 * every aspect ratio (720p 1:1 = 720x720, 720p 4:3 = 960x720). Sizes are even (YUV420) and never
 * exceed [MAX_SIDE]; aspect ratio is preserved when clamping, so 4K 21:9 becomes 3840x1646 instead
 * of being squashed into 3840x2160. Exact sizes are kept (1080p stays 1920x1080); the encoder
 * planner only falls back to 16-aligned smaller sizes if the device rejects the exact one.
 */
object ExportDimensionResolver {
  const val MAX_SIDE = 3840
  private const val ALIGN = 16

  fun resolve(resolution: Resolution, aspect: AspectRatio): Pair<Int, Int> {
    val (w, h) = when (resolution) {
      Resolution.RES_SQUARE_2K -> 2048 to 2048
      Resolution.RES_VERTICAL_2K -> 1440 to 2560
      Resolution.RES_VERTICAL_4K -> 2160 to 3840
      else -> {
        val shortSide = min(resolution.width, resolution.height)
        val longSide = max(resolution.width, resolution.height)
        when (aspect) {
          AspectRatio.RATIO_9_16 -> shortSide to longSide
          AspectRatio.RATIO_16_9 -> longSide to shortSide
          AspectRatio.RATIO_1_1 -> shortSide to shortSide
          AspectRatio.RATIO_4_5 -> (shortSide * 4) / 5 to shortSide
          AspectRatio.RATIO_4_3 -> (shortSide * 4) / 3 to shortSide
          AspectRatio.RATIO_3_4 -> (shortSide * 3) / 4 to shortSide
          AspectRatio.RATIO_21_9 -> (shortSide * 21) / 9 to shortSide
          AspectRatio.CUSTOM -> shortSide to (shortSide / aspect.ratio).toInt().coerceAtLeast(1)
        }
      }
    }
    return fitEven(w, h)
  }

  /** Scales (w,h) down to fit a [maxSide] box, preserving aspect, aligned to 16 px. */
  fun fit(w: Int, h: Int, maxSide: Int): Pair<Int, Int> {
    val scale = min(1.0, min(maxSide.toDouble() / w, maxSide.toDouble() / h))
    var sw = alignUp((w * scale).roundToInt())
    var sh = alignUp((h * scale).roundToInt())
    while (sw > maxSide) sw -= ALIGN
    while (sh > maxSide) sh -= ALIGN
    return max(sw, ALIGN) to max(sh, ALIGN)
  }

  /**
   * Even-aligned variant for UI/Media3 paths: keeps the requested aspect ratio when a size
   * exceeds [MAX_SIDE] (e.g. 4K 21:9 = 5040x2160 -> 3840x1646) instead of squashing each axis.
   */
  fun fitEven(w: Int, h: Int, maxSide: Int = MAX_SIDE, minSide: Int = 320): Pair<Int, Int> {
    val scale = min(1.0, min(maxSide.toDouble() / w, maxSide.toDouble() / h))
    val sw = (((w * scale).toInt()) / 2) * 2
    val sh = (((h * scale).toInt()) / 2) * 2
    return sw.coerceAtLeast(minSide) to sh.coerceAtLeast(minSide)
  }

  private fun alignUp(v: Int) = ((v + ALIGN - 1) / ALIGN) * ALIGN
}

data class EncoderPlan(
  val mime: String,
  val width: Int,
  val height: Int,
  val fps: Int,
  val bitrateBps: Int,
  val requestedWidth: Int,
  val requestedHeight: Int,
  val requestedFps: Int
) {
  val isDegraded: Boolean get() = width != requestedWidth || height != requestedHeight || fps != requestedFps
  fun describe(): String = "${width}x$height@${fps}fps ${mime.substringAfter('/')} ${bitrateBps / 1_000_000}Mbps"
}

/**
 * Picks codec, size, frame-rate and bitrate that the device's encoders really support.
 * 4K requests that the hardware cannot do (e.g. 4K@60 on mid-range SoCs) degrade
 * gracefully (fps first, then resolution) instead of failing at MediaCodec.configure().
 */
object ExportEncoderPlanner {
  private const val TAG = "ExportEncoderPlanner"
  private const val MAX_BITRATE_BPS = 120_000_000
  private const val MIN_BITRATE_BPS = 1_000_000
  private val LONG_SIDE_LADDER = intArrayOf(3840, 2560, 1920, 1280, 854)

  fun plan(config: ExportConfig, requestedW: Int, requestedH: Int, requestedFps: Int): EncoderPlan {
    val highRes = max(requestedW, requestedH) >= 2160
    val mimes = when (config.codecProfile) {
      CodecProfile.H265_HEVC -> listOf(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC)
      CodecProfile.H264_AVC -> listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC)
      CodecProfile.AUTO ->
        if (highRes) listOf(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC)
        else listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC)
    }

    // 1080p (and below) must stay at the requested pixel size. 1920x1080 is even but not
    // 16-aligned; isSizeSupported() often returns false and the old ladder silently fell
    // through to 1280x720 — which is the 1080p-only "export failed" the user hit, while
    // 720p/2K/4K (all 16-aligned) succeeded. 2K/4K may still drop size when the SoC cannot
    // encode them.
    val allowSizeDegrade = max(requestedW, requestedH) >= 2160

    var best: Triple<String, Int, Triple<Int, Int, Int>>? = null // mime, score, (w,h,fps)
    for (mime in mimes) {
      val fit = fitToEncoder(mime, requestedW, requestedH, requestedFps, allowSizeDegrade) ?: continue
      val (w, h, fps) = fit
      val full = w == requestedW && h == requestedH && fps == requestedFps
      val score = if (full) Int.MAX_VALUE else w * h / 1000 * 10 + fps
      if (best == null || score > best.second) best = Triple(mime, score, fit)
      if (full) break
    }

    val (mime, w, h, fps) = best?.let { Quad(it.first, it.third.first, it.third.second, it.third.third) }
      ?: Quad(mimes.first(), requestedW, requestedH, requestedFps) // no capability info: try as requested

    val bitrate = bitrateFor(config, mime, w, h, fps, requestedW, requestedH)
    return EncoderPlan(mime, w, h, fps, bitrate, requestedW, requestedH, requestedFps).also {
      if (it.isDegraded) Log.w(TAG, "Device cannot encode ${requestedW}x$requestedH@$requestedFps, using ${it.describe()}")
      else Log.i(TAG, "Encoder plan: ${it.describe()}")
    }
  }

  private data class Quad(val mime: String, val w: Int, val h: Int, val fps: Int)

  /** Returns (w, h, fps) the best matching surface-input encoder supports, or null if none/unknown. */
  private fun fitToEncoder(
    mime: String,
    w: Int,
    h: Int,
    fps: Int,
    allowSizeDegrade: Boolean
  ): Triple<Int, Int, Int>? {
    val codecs = encodersFor(mime)
    if (codecs.isEmpty()) return null
    val sizes = ArrayList<Pair<Int, Int>>()
    sizes += w to h
    if (allowSizeDegrade) {
      for (longSide in LONG_SIDE_LADDER) {
        if (longSide >= max(w, h)) continue
        val scale = longSide.toDouble() / max(w, h)
        sizes += ExportDimensionResolver.fit((w * scale).roundToInt(), (h * scale).roundToInt(), longSide)
      }
    }
    val fpsOptions = listOf(fps, 30, 24).filter { it <= fps }.distinct()
    for ((sw, sh) in sizes) {
      for (f in fpsOptions) {
        for (caps in codecs) {
          val vc = caps.videoCapabilities ?: continue
          val strict = runCatching {
            vc.isSizeSupported(sw, sh) && vc.areSizeAndRateSupported(sw, sh, f.toDouble())
          }.getOrDefault(false)
          if (strict) return Triple(sw, sh, f)
        }
      }
    }
    // Alignment-tolerant pass: 1920x1080 is in range on almost every SoC even when
    // isSizeSupported is false because 1080 % 16 != 0. Keep the exact size.
    for (f in fpsOptions) {
      for (caps in codecs) {
        val vc = caps.videoCapabilities ?: continue
        if (withinSupportedRange(vc, w, h, f)) return Triple(w, h, f)
      }
    }
    return if (allowSizeDegrade) null else Triple(w, h, fpsOptions.first())
  }

  internal fun withinSupportedRange(
    vc: MediaCodecInfo.VideoCapabilities,
    w: Int,
    h: Int,
    fps: Int
  ): Boolean {
    val widths = runCatching { vc.supportedWidths }.getOrNull() ?: return true
    val heights = runCatching { vc.supportedHeights }.getOrNull() ?: return true
    if (w < widths.lower || w > widths.upper) return false
    if (h < heights.lower || h > heights.upper) return false
    return runCatching { vc.supportedFrameRates.upper + 0.001 >= fps }.getOrDefault(true)
  }

  private fun encodersFor(mime: String): List<MediaCodecInfo.CodecCapabilities> {
    val out = ArrayList<Pair<Boolean, MediaCodecInfo.CodecCapabilities>>()
    for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
      if (!info.isEncoder || info.supportedTypes.none { it.equals(mime, true) }) continue
      val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: continue
      if (!caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)) continue
      val hw = if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else !info.name.startsWith("OMX.google.")
      out += hw to caps
    }
    return out.sortedByDescending { it.first }.map { it.second } // hardware first
  }

  private fun bitrateFor(config: ExportConfig, mime: String, w: Int, h: Int, fps: Int, reqW: Int, reqH: Int): Int {
    val raw: Long = if (config.quality == ExportQuality.CUSTOM && config.customBitrateKbps > 0) {
      config.customBitrateKbps * 1000L
    } else {
      val base = when (config.resolution) {
        Resolution.RES_480P -> 2_500_000L
        Resolution.RES_720P -> 5_000_000L
        Resolution.RES_1080P -> 10_000_000L
        Resolution.RES_2K, Resolution.RES_VERTICAL_2K -> 18_000_000L
        Resolution.RES_4K, Resolution.RES_VERTICAL_4K -> 35_000_000L
        Resolution.RES_SQUARE_2K -> 22_000_000L
      }
      val codecMultiplier = if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) 0.75 else 1.0
      val pixelRatio = (w.toDouble() * h) / (reqW.toDouble() * reqH)
      (base * config.quality.bitrateMultiplier * (fps / 30.0) * codecMultiplier * pixelRatio).toLong()
    }
    var bps = raw.coerceIn(MIN_BITRATE_BPS.toLong(), MAX_BITRATE_BPS.toLong()).toInt()
    // Never exceed what the chosen encoder accepts.
    encodersFor(mime).firstOrNull()?.videoCapabilities?.bitrateRange?.let { bps = bps.coerceIn(it.lower, it.upper) }
    return bps
  }
}

/**
 * Tells the encoder this is an offline export, not a live camera stream, so it can run
 * faster than realtime instead of throttling to the timeline frame rate.
 */
object ExportEncoderSpeedHints {
  fun applyForFastExport(format: MediaFormat, fps: Int) {
    if (Build.VERSION.SDK_INT < 23) return
    val operatingRate = max(120, fps * 4)
    try {
      format.setInteger(MediaFormat.KEY_OPERATING_RATE, operatingRate)
    } catch (_: Exception) {
    }
    try {
      // 0 = realtime / as-fast-as-possible. Combined with OPERATING_RATE this avoids
      // the encoder pacing itself to 1x playback speed during a long export.
      format.setInteger(MediaFormat.KEY_PRIORITY, 0)
    } catch (_: Exception) {
    }
  }
}
