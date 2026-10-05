package com.example.engine.export

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Builds small real MP4 files (H.264 + AAC) for the validator's instrumentation tests. */
object TestMp4Factory {
  const val W = 320
  const val H = 240
  const val FPS = 15

  enum class Picture { COLORFUL, BLACK, DARK_NIGHT }

  private class Sample(val data: ByteArray, val info: MediaCodec.BufferInfo)
  private class Encoded(val format: MediaFormat, val samples: List<Sample>)

  /**
   * @param videoMs video length; null = no video track (audio-only file)
   * @param audioMs audio length; null = no audio track
   * @param rotation container rotation hint
   */
  fun write(
    file: File,
    videoMs: Long? = 2_000L,
    audioMs: Long? = 2_000L,
    picture: Picture = Picture.COLORFUL,
    rotation: Int = 0,
  ): File {
    val video = videoMs?.let { ms ->
      val e = encodeVideo(ms, picture)
      // videoMs <= 0 builds a video track that has a format but no samples (zero-frame output).
      if (ms <= 0L) Encoded(e.format, emptyList()) else e
    }
    val audio = audioMs?.let { encodeAudio(it) }
    val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    if (rotation != 0) muxer.setOrientationHint(rotation)
    val vIdx = video?.let { muxer.addTrack(it.format) } ?: -1
    val aIdx = audio?.let { muxer.addTrack(it.format) } ?: -1
    muxer.start()
    video?.samples?.forEach { muxer.writeSampleData(vIdx, ByteBuffer.wrap(it.data), it.info) }
    audio?.samples?.forEach { muxer.writeSampleData(aIdx, ByteBuffer.wrap(it.data), it.info) }
    muxer.stop(); muxer.release()
    return file
  }

  private fun lumaAt(picture: Picture, frame: Int, x: Int, y: Int, rnd: Random): Int = when (picture) {
    Picture.BLACK -> 16
    Picture.COLORFUL -> 40 + ((x * 180 / W + y * 20 / H + frame * 6) % 180)
    Picture.DARK_NIGHT ->
      if (x in 20..60 && y in 20..60) 210                // a street lamp, ~3% of the frame
      else 20 + rnd.nextInt(0, 22)                       // dark but noisy sensor
  }

  private fun encodeVideo(durationMs: Long, picture: Picture): Encoded {
    val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H).apply {
      setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
      setInteger(MediaFormat.KEY_BIT_RATE, 800_000)
      setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
      setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
    }
    val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    codec.start()
    val total = (durationMs * FPS / 1000L).toInt().coerceAtLeast(1)
    val out = ArrayList<Sample>()
    var outFormat: MediaFormat? = null
    val info = MediaCodec.BufferInfo()
    val rnd = Random(42)
    var fed = 0; var eos = false
    while (!eos) {
      if (fed <= total) {
        val i = codec.dequeueInputBuffer(10_000)
        if (i >= 0) {
          if (fed == total) {
            codec.queueInputBuffer(i, 0, 0, fed * 1_000_000L / FPS, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
          } else {
            val img = codec.getInputImage(i)!!
            val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
            for (y in 0 until H) for (x in 0 until W) {
              yP.buffer.put(y * yP.rowStride + x * yP.pixelStride, lumaAt(picture, fed, x, y, rnd).toByte())
            }
            for (y in 0 until H / 2) for (x in 0 until W / 2) {
              uP.buffer.put(y * uP.rowStride + x * uP.pixelStride, 128.toByte())
              vP.buffer.put(y * vP.rowStride + x * vP.pixelStride, 128.toByte())
            }
            codec.queueInputBuffer(i, 0, W * H * 3 / 2, fed * 1_000_000L / FPS, 0)
          }
          fed++
        }
      }
      when (val o = codec.dequeueOutputBuffer(info, 10_000)) {
        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outFormat = codec.outputFormat
        in 0..Int.MAX_VALUE -> {
          val buf = codec.getOutputBuffer(o)!!
          if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
            val bytes = ByteArray(info.size); buf.position(info.offset); buf.get(bytes)
            out += Sample(bytes, MediaCodec.BufferInfo().also { it.set(0, info.size, info.presentationTimeUs, info.flags) })
          }
          eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
          codec.releaseOutputBuffer(o, false)
        }
      }
    }
    codec.stop(); codec.release()
    return Encoded(outFormat!!, out)
  }

  private fun encodeAudio(durationMs: Long): Encoded {
    val rate = 44_100
    val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
      setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
      setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
      setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
    }
    val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    codec.start()
    val totalSamples = durationMs * rate / 1000L
    var written = 0L
    val out = ArrayList<Sample>()
    var outFormat: MediaFormat? = null
    val info = MediaCodec.BufferInfo()
    var sentEos = false; var eos = false
    while (!eos) {
      if (!sentEos) {
        val i = codec.dequeueInputBuffer(10_000)
        if (i >= 0) {
          val buf = codec.getInputBuffer(i)!!; buf.clear()
          val n = minOf(2048L, totalSamples - written).toInt()
          val pts = written * 1_000_000L / rate
          if (n <= 0) {
            codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); sentEos = true
          } else {
            for (k in 0 until n) buf.putShort((sin(2 * PI * 440.0 * (written + k) / rate) * 6000).toInt().toShort())
            codec.queueInputBuffer(i, 0, n * 2, pts, 0); written += n
          }
        }
      }
      when (val o = codec.dequeueOutputBuffer(info, 10_000)) {
        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outFormat = codec.outputFormat
        in 0..Int.MAX_VALUE -> {
          val buf = codec.getOutputBuffer(o)!!
          if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
            val bytes = ByteArray(info.size); buf.position(info.offset); buf.get(bytes)
            out += Sample(bytes, MediaCodec.BufferInfo().also { it.set(0, info.size, info.presentationTimeUs, info.flags) })
          }
          eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
          codec.releaseOutputBuffer(o, false)
        }
      }
    }
    codec.stop(); codec.release()
    return Encoded(outFormat!!, out)
  }
}
