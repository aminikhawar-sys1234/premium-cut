package com.example.engine.export

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs [ExportValidator] against real MP4 files produced with MediaCodec/MediaMuxer on the device,
 * covering each failure case end to end (container + decoder), and the dark-footage must-pass case.
 */
@RunWith(AndroidJUnit4::class)
class ExportValidatorInstrumentedTest {
  private lateinit var dir: File
  private val config = ExportConfig()
  private val size = TestMp4Factory.W to TestMp4Factory.H

  @Before fun setUp() {
    dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "validator_${System.nanoTime()}").also { it.mkdirs() }
  }

  @After fun tearDown() { dir.deleteRecursively() }

  private fun mp4(name: String = "t.mp4", block: (File) -> File) = block(File(dir, name))

  private fun validate(
    f: File, expectedMs: Long = 2_000L, requireAudio: Boolean = true,
    dims: Pair<Int, Int>? = size, sampleFrames: Boolean = true, rotation: Int = 0, pipelineFailure: String? = null,
  ) = ExportValidator.validate(f, config, expectedMs, requireAudio, dims, sampleFrames, rotation, pipelineFailure)

  private fun assertRejected(expected: ExportFailure, r: ExportValidationResult) {
    assertTrue("expected rejection but got: ${r.message}", !r.valid)
    assertEquals(r.message, expected, r.failure)
  }

  @Test fun healthyVideoWithAudioIsAccepted() {
    val r = validate(mp4 { TestMp4Factory.write(it) })
    assertTrue(r.message, r.valid)
  }

  @Test fun darkNightFootageIsNotRejected() {
    val r = validate(mp4 { TestMp4Factory.write(it, picture = TestMp4Factory.Picture.DARK_NIGHT) })
    assertTrue(r.message, r.valid)
  }

  @Test fun blackVideoRejected() =
    assertRejected(ExportFailure.ALL_FRAMES_BLACK, validate(mp4 { TestMp4Factory.write(it, picture = TestMp4Factory.Picture.BLACK) }))

  @Test fun blackVideoPassesWhenFrameSamplingDisabled() {
    val r = validate(mp4 { TestMp4Factory.write(it, picture = TestMp4Factory.Picture.BLACK) }, sampleFrames = false)
    assertTrue(r.message, r.valid)
  }

  @Test fun missingFileRejected() =
    assertRejected(ExportFailure.FILE_MISSING, validate(File(dir, "nope.mp4")))

  @Test fun emptyFileRejected() =
    assertRejected(ExportFailure.FILE_TOO_SMALL, validate(File(dir, "empty.mp4").also { it.writeBytes(ByteArray(0)) }))

  @Test fun garbageFileRejected() {
    val f = File(dir, "garbage.mp4").also { it.writeBytes(ByteArray(50_000) { 0x55 }) }
    val r = validate(f)
    assertTrue(r.message, !r.valid)
  }

  @Test fun audioOnlyRejected() =
    assertRejected(ExportFailure.AUDIO_ONLY, validate(mp4 { TestMp4Factory.write(it, videoMs = null) }))

  @Test fun missingAudioRejectedWhenExpected() =
    assertRejected(ExportFailure.NO_AUDIO_TRACK, validate(mp4 { TestMp4Factory.write(it, audioMs = null) }))

  @Test fun missingAudioAcceptedWhenNotExpected() {
    val r = validate(mp4 { TestMp4Factory.write(it, audioMs = null) }, requireAudio = false)
    assertTrue(r.message, r.valid)
  }

  @Test fun truncatedFileRejectedAsMuxerFailure() {
    val full = mp4 { TestMp4Factory.write(it) }
    val cut = File(dir, "cut.mp4").also { it.writeBytes(full.readBytes().copyOf((full.length() * 6 / 10).toInt())) }
    assertRejected(ExportFailure.MUXER_INCOMPLETE, validate(cut))
  }

  @Test fun fileWithoutMoovRejectedAsMuxerFailure() {
    // Keep only ftyp + mdat: what a muxer that crashed before stop() leaves behind.
    val bytes = mp4 { TestMp4Factory.write(it) }.readBytes()
    var off = 0; var end = bytes.size
    while (off + 8 <= bytes.size) {
      val size = ((bytes[off].toInt() and 0xFF) shl 24) or ((bytes[off + 1].toInt() and 0xFF) shl 16) or
        ((bytes[off + 2].toInt() and 0xFF) shl 8) or (bytes[off + 3].toInt() and 0xFF)
      if (String(bytes, off + 4, 4, Charsets.ISO_8859_1) == "moov") { end = off; break }
      if (size <= 0) break
      off += size
    }
    val f = File(dir, "nomoov.mp4").also { it.writeBytes(bytes.copyOf(end)) }
    assertRejected(ExportFailure.MUXER_INCOMPLETE, validate(f))
  }

  @Test fun durationMismatchRejected() =
    assertRejected(ExportFailure.DURATION_MISMATCH, validate(mp4 { TestMp4Factory.write(it) }, expectedMs = 20_000L))

  @Test fun audioVideoDurationMismatchRejected() =
    assertRejected(ExportFailure.AV_DURATION_MISMATCH, validate(mp4 { TestMp4Factory.write(it, videoMs = 2_000L, audioMs = 5_000L) }, expectedMs = 2_000L))

  @Test fun rotationMetadataRejected() =
    assertRejected(ExportFailure.BAD_ROTATION, validate(mp4 { TestMp4Factory.write(it, rotation = 90) }))

  @Test fun rotationMatchingExpectationAccepted() {
    val r = validate(mp4 { TestMp4Factory.write(it, rotation = 90) }, rotation = 90)
    assertTrue(r.message, r.valid)
  }

  @Test fun dimensionMismatchRejected() =
    assertRejected(ExportFailure.DIMENSION_MISMATCH, validate(mp4 { TestMp4Factory.write(it) }, dims = 1080 to 1920))

  @Test fun pipelineEosFailureRejected() =
    assertRejected(ExportFailure.ENCODER_FAILURE,
      validate(mp4 { TestMp4Factory.write(it) }, pipelineFailure = "Encoder finished without end-of-stream"))

  @Test fun videoMuchShorterThanPlannedRejected() {
    // Planned 6s but the encoder stopped after 2s (lost EOS / dropped tail).
    val r = validate(mp4 { TestMp4Factory.write(it, videoMs = 2_000L, audioMs = 2_000L) }, expectedMs = 6_000L)
    assertTrue(r.message, !r.valid)
  }

  @Test fun zeroFrameVideoTrackRejected() {
    // A video track with no samples. Some platform muxers refuse to finalise such a file; then there is nothing to test.
    val f = File(dir, "zero.mp4")
    try { TestMp4Factory.write(f, videoMs = 0L) } catch (t: Throwable) { assumeNoException(t) }
    val r = validate(f, expectedMs = 2_000L, requireAudio = false)
    assertTrue(r.message, !r.valid)
  }
}
