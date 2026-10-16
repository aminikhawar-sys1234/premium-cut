package com.example.engine.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides which codec the export asks for, and what it does when the device refuses.
 *
 * The case that produced this: a real device failed a four-minute multi-track export at 56% with
 * `MediaCodec.CodecException: (errorCode=1100 ... diagnostic=android.media.MediaCodec.error_1100)`
 * - ERROR_INSUFFICIENT_RESOURCE - while opening the decoder of the clip the frame needed.
 */
class CodecAllocationPolicyTest {

  // ---------------------------------------------------------------- the ladder itself

  @Test
  fun hardwareIsTriedFirstAndSoftwareSecond() {
    val candidates = CodecAllocationPolicy.candidates(
      mime = "video/avc",
      hardwareName = "c2.qti.avc.decoder",
      softwareName = "c2.android.avc.decoder",
      preferHardware = true
    )
    assertEquals(2, candidates.size)
    assertEquals("c2.qti.avc.decoder", candidates[0].name)
    assertTrue("the first ladder step must be the hardware codec", candidates[0].hardware)
    assertEquals("c2.android.avc.decoder", candidates[1].name)
    assertFalse("the second ladder step must be the software codec", candidates[1].hardware)
  }

  @Test
  fun aDeviceWithoutAListedHardwareCodecStillGetsTwoSteps() {
    val candidates = CodecAllocationPolicy.candidates(
      mime = "video/avc",
      hardwareName = null,
      softwareName = "c2.android.avc.decoder",
      preferHardware = true
    )
    // The platform default is the first step when no hardware codec is listed; the software decoder
    // is still the second one, which is what keeps a device without vendor codecs working.
    assertEquals(2, candidates.size)
    assertNull(candidates[0].name)
    assertEquals("c2.android.avc.decoder", candidates[1].name)
  }

  @Test
  fun theSameCodecIsNotTriedTwice() {
    val candidates = CodecAllocationPolicy.candidates(
      mime = "video/avc",
      hardwareName = "c2.android.avc.decoder",
      softwareName = "c2.android.avc.decoder",
      preferHardware = true
    )
    assertEquals(1, candidates.size)
  }

  @Test
  fun theSoftwareStepDoesNotAskForHardwareAgain() {
    // The export is on this step *because* a hardware instance was refused. Falling back to the
    // platform default would resolve to the same hardware codec and fail the same way.
    val candidates = CodecAllocationPolicy.candidates(
      mime = "video/avc",
      hardwareName = "c2.qti.avc.decoder",
      softwareName = "c2.android.avc.decoder",
      preferHardware = false
    )
    assertEquals(listOf("c2.android.avc.decoder"), candidates.map { it.name })
  }

  @Test
  fun aDeviceWithoutAListedSoftwareCodecUsesThePlatformDefault() {
    val candidates = CodecAllocationPolicy.candidates(
      mime = "video/hevc",
      hardwareName = "c2.qti.hevc.decoder",
      softwareName = null,
      preferHardware = false
    )
    assertEquals(1, candidates.size)
    assertNull(candidates[0].name)
  }

  // ---------------------------------------------------------------- counting hardware honestly

  @Test
  fun aPlatformSoftwareCodecIsNotCountedAgainstTheHardwareBudget() {
    assertTrue(CodecAllocationPolicy.looksSoftware("c2.android.avc.decoder"))
    assertTrue(CodecAllocationPolicy.looksSoftware("OMX.google.h264.decoder"))
    assertTrue(CodecAllocationPolicy.looksSoftware("OMX.ffmpeg.h264.decoder"))
    assertTrue(CodecAllocationPolicy.looksSoftware("c2.goldfish.sw.decoder"))
    assertFalse(CodecAllocationPolicy.looksSoftware("c2.qti.avc.decoder"))
    assertFalse(CodecAllocationPolicy.looksSoftware("OMX.hisi.video.decoder.avc"))
  }

  // ---------------------------------------------------------------- telling a refusal apart

  @Test
  fun aResourceRefusalIsRecognisedFromTheCodecMessage() {
    // Verbatim from the device that failed the user's export.
    val deviceMessage = "android.media.MediaCodec\$CodecException: (errorCode=1100 " +
      "recoverable=false transient=false diagnostic=android.media.MediaCodec.error_1100)"
    assertTrue(CodecAllocationPolicy.isResourceRefusal(deviceMessage))
    assertTrue(CodecAllocationPolicy.isResourceRefusal("MediaCodec errorCode=1100"))
    assertTrue(CodecAllocationPolicy.isResourceRefusal("ERROR_INSUFFICIENT_RESOURCE"))
    assertTrue(CodecAllocationPolicy.isResourceRefusal("Failed to allocate component instance"))
    assertTrue(CodecAllocationPolicy.isResourceRefusal("MediaCodec error: 0xfffffc0e"))
    assertTrue(CodecAllocationPolicy.isResourceRefusal("CodecException: NO_MEMORY"))
  }

  @Test
  fun anUndecodableClipIsNotTreatedAsAResourceProblem() {
    // A corrupt or unsupported file must keep failing as a corrupt file: retrying it on another
    // codec would only make the export slower and its reason misleading.
    assertFalse(CodecAllocationPolicy.isResourceRefusal("no video track in file:///x/y.mp4"))
    assertFalse(CodecAllocationPolicy.isResourceRefusal("decoder produced no output for target=8000us within 12000ms"))
    assertFalse(CodecAllocationPolicy.isResourceRefusal("decoded frame never arrived at the SurfaceTexture"))
    assertFalse(CodecAllocationPolicy.isResourceRefusal("MediaExtractor: could not find metadata"))
    assertFalse(CodecAllocationPolicy.isResourceRefusal(null))
    assertFalse(CodecAllocationPolicy.isResourceRefusal(""))
  }

  @Test
  fun theFailureReasonExplainsACodecExhaustionToTheUser() {
    val hint = CodecAllocationPolicy.hintFor("decoder setup failed: errorCode=1100")
    assertTrue("the hint must name the codec error", hint!!.contains("errorCode=1100"))
    assertTrue("the hint must say what the device did", hint.contains("ran out of codec instances"))
    assertNull(CodecAllocationPolicy.hintFor("no video track in file:///x/y.mp4"))
  }

  // ---------------------------------------------------------------- learning the device's limit

  @Test
  fun nothingIsKnownUntilEitherTheDeviceOrARealRefusalTellsUs() {
    assertNull(CodecAllocationPolicy.hardwareLimit(deviceMaxInstances = null, learnedLimit = null))
    assertEquals(4, CodecAllocationPolicy.hardwareLimit(deviceMaxInstances = 4, learnedLimit = null))
    assertEquals(3, CodecAllocationPolicy.hardwareLimit(deviceMaxInstances = null, learnedLimit = 3))
  }

  @Test
  fun whatActuallyHappenedWinsOverWhatTheDeviceDeclares() {
    // A codec that declares four instances but refuses the third one is a device with two usable
    // instances; the export must believe the refusal, not the declaration.
    assertEquals(2, CodecAllocationPolicy.hardwareLimit(deviceMaxInstances = 4, learnedLimit = 2))
    assertEquals(4, CodecAllocationPolicy.hardwareLimit(deviceMaxInstances = 4, learnedLimit = 6))
  }

  @Test
  fun aDeclaredZeroMeansUnknownButARefusalAtZeroMeansSoftwareFromNowOn() {
    assertNull(CodecAllocationPolicy.hardwareLimit(deviceMaxInstances = 0, learnedLimit = null))
    assertEquals(0, CodecAllocationPolicy.hardwareLimit(deviceMaxInstances = null, learnedLimit = 0))
  }

  @Test
  fun theNextClipGoesStraightToSoftwareOnceTheInstancesAreUsedUp() {
    assertFalse(CodecAllocationPolicy.needsSoftwareFallback(hardwareOpen = 3, limit = 4))
    assertTrue(CodecAllocationPolicy.needsSoftwareFallback(hardwareOpen = 4, limit = 4))
    assertTrue("a device that refused even the first instance gets software decoders", 
      CodecAllocationPolicy.needsSoftwareFallback(hardwareOpen = 0, limit = 0))
    assertFalse("an unknown limit must not force software decoding", 
      CodecAllocationPolicy.needsSoftwareFallback(hardwareOpen = 9, limit = null))
  }
}
