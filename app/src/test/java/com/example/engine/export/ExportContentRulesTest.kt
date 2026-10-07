package com.example.engine.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** One test per rejection rule of [ExportContentRules], plus the "must NOT reject" guards. */
class ExportContentRulesTest {

  private val expect = ExportExpectations(expectedDurationMs = 10_000L, expectedDimensions = 1080 to 1920)

  private fun stats(p99: Int, std: Double, mean: Double = p99 / 2.0) = FrameAnalyzer.Stats(mean, p99, std, 576)
  private val picture = stats(p99 = 200, std = 55.0, mean = 110.0)
  private val black = stats(p99 = 0, std = 0.0, mean = 0.0)

  private fun frames(first: FrameAnalyzer.Stats? = picture, mid: FrameAnalyzer.Stats? = picture, last: FrameAnalyzer.Stats? = picture) =
    listOf(
      FrameProbe(FrameRole.FIRST, 0L, first),
      FrameProbe(FrameRole.MIDDLE, 5_000_000L, mid),
      FrameProbe(FrameRole.LAST, 9_966_667L, last),
    )

  private fun probe(
    frames: List<FrameProbe> = frames(),
    block: (ExportContentProbe) -> ExportContentProbe = { it },
  ) = block(ExportContentProbe(frames = frames))

  private fun verdict(p: ExportContentProbe, e: ExportExpectations = expect) = ExportContentRules.check(p, e)?.failure

  @Test fun healthyFileIsAccepted() = assertNull(verdict(probe()))

  // 1. file exists
  @Test fun missingFileRejected() =
    assertEquals(ExportFailure.FILE_MISSING, verdict(ExportContentProbe(fileExists = false)))

  @Test fun tinyFileRejected() =
    assertEquals(ExportFailure.FILE_TOO_SMALL, verdict(ExportContentProbe(fileBytes = 100L)))

  // 2. container readable
  @Test fun unreadableContainerRejected() =
    assertEquals(ExportFailure.CONTAINER_UNREADABLE, verdict(ExportContentProbe(containerReadable = false, containerError = "boom")))

  @Test fun notAnMp4Rejected() =
    assertEquals(ExportFailure.CONTAINER_UNREADABLE,
      verdict(ExportContentProbe(boxes = Mp4BoxScanner.Result(false, false, true, false, null))))

  // 3. + 17. video track / audio-only
  @Test fun audioOnlyRejected() =
    assertEquals(ExportFailure.AUDIO_ONLY, verdict(ExportContentProbe(hasVideoTrack = false, hasAudioTrack = true)))

  @Test fun noTracksAtAllRejected() =
    assertEquals(ExportFailure.NO_VIDEO_TRACK, verdict(ExportContentProbe(hasVideoTrack = false, hasAudioTrack = false)))

  // 4. audio expected
  @Test fun missingAudioRejectedWhenExpected() =
    assertEquals(ExportFailure.NO_AUDIO_TRACK, verdict(probe { ExportContentProbe(hasAudioTrack = false, frames = frames()) }))

  @Test fun missingAudioAcceptedWhenNotExpected() =
    assertNull(verdict(ExportContentProbe(hasAudioTrack = false, audioDurationMs = 0L, frames = frames()), expect.copy(requireAudio = false)))

  // 5. + 6. size
  @Test fun zeroWidthRejected() =
    assertEquals(ExportFailure.INVALID_DIMENSIONS, verdict(ExportContentProbe(width = 0)))

  @Test fun zeroHeightRejected() =
    assertEquals(ExportFailure.INVALID_DIMENSIONS, verdict(ExportContentProbe(height = 0)))

  @Test fun wrongSizeRejected() =
    assertEquals(ExportFailure.DIMENSION_MISMATCH, verdict(ExportContentProbe(width = 720, height = 1280)))

  @Test fun wrongOrientationRejected() =
    assertEquals(ExportFailure.DIMENSION_MISMATCH,
      verdict(ExportContentProbe(width = 1920, height = 1080), expect.copy(expectedDimensions = 1080 to 1920)))

  // 7. duration
  @Test fun zeroDurationRejected() =
    assertEquals(ExportFailure.INVALID_DURATION, verdict(ExportContentProbe(videoDurationMs = 0L, containerDurationMs = 0L)))

  // 15. expected duration tolerance
  @Test fun durationTooShortRejected() =
    assertEquals(ExportFailure.DURATION_MISMATCH, verdict(ExportContentProbe(videoDurationMs = 6_000L, audioDurationMs = 6_000L)))

  @Test fun smallDurationDriftAccepted() =
    assertNull(verdict(ExportContentProbe(videoDurationMs = 10_300L, audioDurationMs = 10_300L, frames = frames())))

  // 16. A/V consistency
  @Test fun audioVideoDurationMismatchRejected() =
    assertEquals(ExportFailure.AV_DURATION_MISMATCH, verdict(ExportContentProbe(videoDurationMs = 10_000L, audioDurationMs = 9_000L)))

  @Test fun aacPrimingDriftAccepted() =
    assertNull(verdict(ExportContentProbe(audioDurationMs = 10_040L, frames = frames())))

  // 14. rotation metadata
  @Test fun unexpectedRotationRejected() =
    assertEquals(ExportFailure.BAD_ROTATION, verdict(ExportContentProbe(rotationDegrees = 90)))

  @Test fun negativeRotationNormalised() =
    assertEquals(ExportFailure.BAD_ROTATION, verdict(ExportContentProbe(rotationDegrees = -90)))

  @Test fun rotationMatchingExpectationAccepted() =
    assertNull(verdict(ExportContentProbe(rotationDegrees = 90, frames = frames()), expect.copy(expectedRotation = 90)))

  // 18. zero-frame output
  @Test fun zeroFramesRejected() =
    assertEquals(ExportFailure.ZERO_FRAMES, verdict(ExportContentProbe(sampleTimesUs = LongArray(0), sampleBytes = 0L)))

  @Test fun noKeyframeRejected() =
    assertEquals(ExportFailure.NO_KEYFRAME, verdict(ExportContentProbe(hasSyncSample = false)))

  // 8. timestamps
  @Test fun negativeTimestampRejected() =
    assertEquals(ExportFailure.BAD_TIMESTAMPS,
      verdict(ExportContentProbe(sampleTimesUs = LongArray(300) { it * 33_333L - 1_000L })))

  @Test fun duplicateTimestampRejected() {
    val t = LongArray(300) { it * 33_333L }; t[10] = t[9]
    assertEquals(ExportFailure.BAD_TIMESTAMPS, verdict(ExportContentProbe(sampleTimesUs = t)))
  }

  @Test fun lateStartRejected() =
    assertEquals(ExportFailure.BAD_TIMESTAMPS,
      verdict(ExportContentProbe(sampleTimesUs = LongArray(300) { 2_000_000L + it * 26_666L })))

  @Test fun timestampBeyondDurationRejected() {
    val t = LongArray(300) { it * 33_333L }; t[299] = 30_000_000L
    assertEquals(ExportFailure.BAD_TIMESTAMPS, verdict(ExportContentProbe(sampleTimesUs = t)))
  }

  @Test fun stalledVideoRejected() {
    // 100 frames in the first 3.3s, then a 6s hole, then 100 frames up to ~10s.
    val a = LongArray(100) { it * 33_333L }
    val b = LongArray(100) { 9_000_000L + it * 10_000L }
    assertEquals(ExportFailure.BAD_TIMESTAMPS, verdict(ExportContentProbe(sampleTimesUs = a + b)))
  }

  @Test fun bFrameOrderedTimestampsAccepted() {
    // Decode order I P B B: presentation times are not monotonic in file order, which is legal.
    val t = LongArray(300) { i -> val g = (i / 3) * 3; when (i % 3) { 0 -> g; 1 -> g + 2; else -> g + 1 }.toLong() * 33_333L }
    assertNull(verdict(ExportContentProbe(sampleTimesUs = t, frames = frames())))
  }

  // 13. no meaningful frames
  @Test fun emptySamplesRejected() =
    assertEquals(ExportFailure.EMPTY_SAMPLES, verdict(ExportContentProbe(sampleBytes = 300L)))

  // 19. encoder EOS / muxer failures
  @Test fun encoderFailureReportedByPipelineRejected() =
    assertEquals(ExportFailure.ENCODER_FAILURE, verdict(ExportContentProbe(pipelineFailure = "Encoder finished without end-of-stream")))

  @Test fun missingMoovMeansMuxerNeverFinalisedRejected() =
    assertEquals(ExportFailure.MUXER_INCOMPLETE,
      verdict(ExportContentProbe(boxes = Mp4BoxScanner.Result(true, false, true, false, null))))

  @Test fun truncatedBoxRejected() =
    assertEquals(ExportFailure.MUXER_INCOMPLETE,
      verdict(ExportContentProbe(boxes = Mp4BoxScanner.Result(true, true, true, true, "cut short"))))

  @Test fun videoEndingEarlyRejected() =
    // EOS lost: last frame at ~5s of 10s although the container claims 10s.
    assertEquals(ExportFailure.TRUNCATED_VIDEO, verdict(ExportContentProbe(sampleTimesUs = LongArray(150) { it * 33_333L })))

  @Test fun mostFramesMissingRejected() =
    assertEquals(ExportFailure.MISSING_FRAMES,
      verdict(ExportContentProbe(sampleTimesUs = LongArray(30) { it * 333_333L }, frameRate = 30)))

  // 9. / 10. / 11. decodable first, middle, last
  @Test fun firstFrameUndecodableRejected() =
    assertEquals(ExportFailure.FIRST_FRAME_UNDECODABLE, verdict(probe(frames(first = null))))

  @Test fun middleFrameUndecodableRejected() =
    assertEquals(ExportFailure.MIDDLE_FRAME_UNDECODABLE, verdict(probe(frames(mid = null))))

  @Test fun lastFrameUndecodableRejected() =
    assertEquals(ExportFailure.LAST_FRAME_UNDECODABLE, verdict(probe(frames(last = null))))

  @Test fun noFrameAttemptsAtAllWhenSamplingRequestedStillAccepted() =
    // sampleFrames=false callers pass checkFrames=false; empty probe list means "not sampled".
    assertNull(verdict(ExportContentProbe(frames = emptyList())))

  // 12. black / empty output
  @Test fun allBlackFramesRejected() =
    assertEquals(ExportFailure.ALL_FRAMES_BLACK, verdict(probe(frames(black, black, black))))

  @Test fun allBlackIncludingExtraSamplesRejected() {
    val f = frames(black, black, black) + (1..9).map { FrameProbe(FrameRole.EXTRA, it * 1_000_000L, black) }
    assertEquals(ExportFailure.ALL_FRAMES_BLACK, verdict(probe(f)))
  }

  @Test fun emptyBitmapCountsAsBlack() {
    val empty = FrameAnalyzer.Stats(0.0, 0, 0.0, 0)
    assertEquals(ExportFailure.ALL_FRAMES_BLACK, verdict(probe(frames(empty, empty, empty))))
  }

  @Test fun fadeInWithBlackFirstFrameAccepted() =
    assertNull(verdict(probe(frames(first = black))))

  @Test fun fadeOutWithBlackLastFrameAccepted() =
    assertNull(verdict(probe(frames(last = black))))

  @Test fun blackKeyFramesButPictureInExtraSampleAccepted() {
    val f = frames(black, black, black) + FrameProbe(FrameRole.EXTRA, 3_000_000L, picture)
    assertNull(verdict(probe(f)))
  }

  @Test fun darkNightFootageWithHighlightsAccepted() =
    // Mostly dark (mean 9) but a street lamp lifts the 99th percentile well above black.
    assertNull(verdict(probe(frames(stats(p99 = 90, std = 14.0, mean = 9.0), stats(p99 = 70, std = 10.0, mean = 8.0), stats(p99 = 85, std = 12.0, mean = 9.0)))))

  @Test fun darkNoisyFootageWithoutHighlightsAccepted() =
    // Pitch-dark but with sensor noise: p99 low, stddev above the black limit.
    assertNull(verdict(probe(frames(stats(p99 = 14, std = 4.5, mean = 6.0), stats(p99 = 15, std = 4.0, mean = 6.0), stats(p99 = 13, std = 3.5, mean = 5.0)))))
}
