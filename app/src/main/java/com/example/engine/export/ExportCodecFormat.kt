package com.example.engine.export

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import kotlin.math.ceil

/**
 * Picks an H.264/HEVC profile+level the chosen encoder actually lists, large enough for the
 * exact frame size. 1080p (1920x1080) needs AVC Level 4.0+ (8160 macroblocks); many encoders
 * default to Baseline@3.1 which is why 1080p configure() fails while 720p succeeds.
 */
object ExportCodecFormat {
  private const val TAG = "ExportCodecFormat"

  fun applyCompatibleProfileLevel(format: MediaFormat, codec: MediaCodec, width: Int, height: Int, fps: Int) {
    val mime = format.getString(MediaFormat.KEY_MIME) ?: return
    val caps = runCatching { codec.codecInfo.getCapabilitiesForType(mime) }.getOrNull() ?: return
    val neededMbs = macroblocks(width, height)
    val neededMbps = neededMbs * fps.coerceAtLeast(1)
    val pick = caps.profileLevels
      .filter { supportsFrame(mime, it, neededMbs, neededMbps) }
      .maxWithOrNull(compareBy({ it.profile }, { it.level }))
      ?: return
    try {
      format.setInteger(MediaFormat.KEY_PROFILE, pick.profile)
      format.setInteger(MediaFormat.KEY_LEVEL, pick.level)
      Log.i(TAG, "Using $mime profile=${pick.profile} level=${pick.level} for ${width}x$height@$fps")
    } catch (t: Throwable) {
      Log.w(TAG, "Could not set profile/level on $mime: ${t.message}")
    }
  }

  fun profileOf(format: MediaFormat): Int? =
    if (format.containsKey(MediaFormat.KEY_PROFILE)) format.getInteger(MediaFormat.KEY_PROFILE) else null

  fun levelOf(format: MediaFormat): Int? =
    if (format.containsKey(MediaFormat.KEY_LEVEL)) format.getInteger(MediaFormat.KEY_LEVEL) else null

  fun macroblocks(width: Int, height: Int): Int {
    val mbW = ceil(width / 16.0).toInt()
    val mbH = ceil(height / 16.0).toInt()
    return mbW * mbH
  }

  private fun supportsFrame(
    mime: String,
    pl: MediaCodecInfo.CodecProfileLevel,
    neededMbs: Int,
    neededMbps: Int
  ): Boolean {
    val (maxFs, maxMbps) = when {
      mime.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) -> avcLimits(pl.level)
      mime.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) -> hevcLimits(pl.level)
      else -> return true
    }
    return neededMbs <= maxFs && neededMbps <= maxMbps
  }

  /** ITU-T H.264 Table A-1 (frame size in macroblocks, MB/s). */
  private fun avcLimits(level: Int): Pair<Int, Int> = when (level) {
    MediaCodecInfo.CodecProfileLevel.AVCLevel1, MediaCodecInfo.CodecProfileLevel.AVCLevel1b -> 99 to 1485
    MediaCodecInfo.CodecProfileLevel.AVCLevel11 -> 396 to 3000
    MediaCodecInfo.CodecProfileLevel.AVCLevel12 -> 396 to 6000
    MediaCodecInfo.CodecProfileLevel.AVCLevel13 -> 396 to 11880
    MediaCodecInfo.CodecProfileLevel.AVCLevel2 -> 396 to 11880
    MediaCodecInfo.CodecProfileLevel.AVCLevel21 -> 792 to 19800
    MediaCodecInfo.CodecProfileLevel.AVCLevel22 -> 1620 to 20250
    MediaCodecInfo.CodecProfileLevel.AVCLevel3 -> 1620 to 40500
    MediaCodecInfo.CodecProfileLevel.AVCLevel31 -> 3600 to 108000
    MediaCodecInfo.CodecProfileLevel.AVCLevel32 -> 5120 to 216000
    MediaCodecInfo.CodecProfileLevel.AVCLevel4 -> 8192 to 245760
    MediaCodecInfo.CodecProfileLevel.AVCLevel41 -> 8192 to 245760
    MediaCodecInfo.CodecProfileLevel.AVCLevel42 -> 8704 to 522240
    MediaCodecInfo.CodecProfileLevel.AVCLevel5 -> 22080 to 589824
    MediaCodecInfo.CodecProfileLevel.AVCLevel51 -> 36864 to 983040
    MediaCodecInfo.CodecProfileLevel.AVCLevel52 -> 36864 to 2073600
    else -> if (level >= MediaCodecInfo.CodecProfileLevel.AVCLevel4) 8192 to 245760 else 3600 to 108000
  }

  private fun hevcLimits(level: Int): Pair<Int, Int> = when {
    level >= MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel5 -> 36864 to 983040
    level >= MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4 -> 8192 to 245760
    else -> 3600 to 108000
  }
}
