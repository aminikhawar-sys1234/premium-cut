package com.example.engine.export

import com.example.engine.export.Mp4ValidationRules.Probe
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests: an export must never be accepted just because the file is big enough. */
class Mp4ValidationRulesTest {

  private fun good() = Probe(fileBytes = 2_000_000L, hasVideo = "yes", width = "1080", height = "1920", durationMs = "12000")

  @Test
  fun validVideo_isAccepted() {
    assertNull(Mp4ValidationRules.rejectionReason(good()))
  }

  @Test
  fun audioOnlyFile_isRejected_evenWhenLarge() {
    // Previously: hasVideo != null || size > 10KB  -> accepted. This is the "audio-only output" bug.
    val reason = Mp4ValidationRules.rejectionReason(good().copy(hasVideo = "no", width = null, height = null))
    assertNotNull(reason)
    assertTrue(reason!!.contains("no video"))
  }

  @Test
  fun missingHasVideo_isRejected() {
    assertNotNull(Mp4ValidationRules.rejectionReason(good().copy(hasVideo = null)))
  }

  @Test
  fun tinyFile_isRejected() {
    assertNotNull(Mp4ValidationRules.rejectionReason(good().copy(fileBytes = 10_240L)))
    assertNotNull(Mp4ValidationRules.rejectionReason(good().copy(fileBytes = 0L)))
  }

  @Test
  fun zeroOrMissingDimensions_areRejected() {
    assertNotNull(Mp4ValidationRules.rejectionReason(good().copy(width = "0")))
    assertNotNull(Mp4ValidationRules.rejectionReason(good().copy(height = "-1")))
    assertNotNull(Mp4ValidationRules.rejectionReason(good().copy(width = null)))
    assertNotNull(Mp4ValidationRules.rejectionReason(good().copy(height = "abc")))
  }

  @Test
  fun zeroOrMissingDuration_isRejected() {
    assertNotNull(Mp4ValidationRules.rejectionReason(good().copy(durationMs = "0")))
    assertNotNull(Mp4ValidationRules.rejectionReason(good().copy(durationMs = null)))
    assertNotNull(Mp4ValidationRules.rejectionReason(good().copy(durationMs = "n/a")))
  }

  @Test
  fun expectedDuration_withinTolerance_isAccepted() {
    assertNull(Mp4ValidationRules.rejectionReason(good(), expectedDurationMs = 12_400L))
  }

  @Test
  fun expectedDuration_farOff_isRejected() {
    // File is 12s but the timeline was 60s: truncated export.
    val reason = Mp4ValidationRules.rejectionReason(good(), expectedDurationMs = 60_000L)
    assertNotNull(reason)
    assertTrue(reason!!.contains("duration mismatch"))
  }
}
