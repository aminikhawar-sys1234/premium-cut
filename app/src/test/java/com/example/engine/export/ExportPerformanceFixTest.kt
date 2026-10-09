package com.example.engine.export

import android.media.MediaFormat
import androidx.test.core.app.ApplicationProvider
import com.example.domain.model.AudioClip
import com.example.domain.model.Timeline
import com.example.domain.model.VideoClip
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ExportPerformanceFixTest {

  @Test
  fun pcmByteSinkRoundTripsLittleEndianSamplesWithoutBoxing() {
    val samples = shortArrayOf(-32768, -1, 0, 1, 32767, 1234)
    val raw = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    samples.forEach { raw.putShort(it) }
    raw.flip()

    val out = PcmByteSink.create(16)
    val scratch = ByteArray(3)
    PcmByteSink.append(out, raw, scratch)

    val decoded = PcmByteSink.toLittleEndianShorts(out)
    assertTrue(decoded.contentEquals(samples))
  }

  @Test
  fun pcmByteSinkEstimatesStayBounded() {
    assertEquals(1024 * 1024, PcmByteSink.estimatedPcmBytes(0L, 48000, 2))
    val fourMinutes = PcmByteSink.estimatedPcmBytes(255_000_000L, 48000, 2)
    assertTrue(fourMinutes in 64 * 1024..256 * 1024 * 1024)
    val twoHours = PcmByteSink.estimatedPcmBytes(7_200_000_000L, 48000, 2)
    assertEquals(256 * 1024 * 1024, twoHours)
  }

  @Test
  fun encoderSpeedHintsRequestFasterThanRealtime() {
    val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1080, 1920)
    ExportEncoderSpeedHints.applyForFastExport(format, 30)
    assertEquals(120, format.getInteger(MediaFormat.KEY_OPERATING_RATE))
    assertEquals(0, format.getInteger(MediaFormat.KEY_PRIORITY))

    val highFps = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 2160, 3840)
    ExportEncoderSpeedHints.applyForFastExport(highFps, 60)
    assertEquals(240, highFps.getInteger(MediaFormat.KEY_OPERATING_RATE))
  }

  @Test
  fun progressMovesWithSubmittedFramesEvenIfEncoderOutputLags() {
    assertEquals(0.05f, ExportProgressMapping.encodingFraction(0, 7659), 0.0001f)
    val half = ExportProgressMapping.encodingFraction(3829, 7659)
    assertTrue("mid-export fraction was $half", half in 0.49f..0.51f)
    val submittedOnly = ExportProgressMapping.encodingFraction(400, 7659)
    assertTrue("submitted frames must move the ring off 5%", submittedOnly > 0.09f)
    assertEquals(0.05f + 0.95f * 0.90f, ExportProgressMapping.encodingFraction(7659, 7659), 0.0001f)
  }

  @Test
  fun synthesizedAudioMixCompletesQuicklyForShortTimeline() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val processor = AudioExportProcessor(context)
    val timeline = Timeline(
      videoClips = listOf(
        VideoClip(
          name = "Clip",
          uri = "sfx_pop",
          durationMs = 3000L,
          isVideo = true,
          hasAudio = true,
          volume = 1.0f
        )
      ),
      audioClips = listOf(
        AudioClip(
          title = "whoosh",
          uri = "sfx_whoosh",
          timelineStartMs = 0L,
          durationMs = 3000L,
          volume = 1.0f
        )
      )
    )
    val startNs = System.nanoTime()
    val pcm = processor.mixTimelineAudio(timeline, 3000L)
    val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
    assertTrue(pcm.isNotEmpty())
    assertTrue("mix took ${elapsedMs}ms", elapsedMs < 4000L)
  }
}
