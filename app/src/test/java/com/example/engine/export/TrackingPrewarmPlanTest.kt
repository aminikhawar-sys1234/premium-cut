package com.example.engine.export

import com.example.domain.model.VideoClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The prewarm list must be exactly what the export render loop asks the trackers for - a sample the
 * loop never requests wastes export time, a missing one leaves the stall it was supposed to remove.
 */
class TrackingPrewarmPlanTest {

  private fun clip(
    id: String = "c1",
    startMs: Long = 0L,
    durationMs: Long = 1000L,
    speed: Float = 1f,
    freezeFrameAtMs: Long? = null
  ) = VideoClip(
    id = id,
    name = id,
    uri = "file:///$id.mp4",
    timelineStartMs = startMs,
    durationMs = durationMs,
    sourceStartMs = 0L,
    sourceEndMs = durationMs,
    speed = speed,
    freezeFrameAtMs = freezeFrameAtMs
  )

  @Test
  fun samplesMapEveryFrameToItsSourceTime() {
    val samples = TrackingPrewarmPlan.samplesFor(clip(durationMs = 1000L), fps = 10, maxSamples = 100)
    // One second at 10 fps is 10 exported frames (0..9), and the export asks the tracker for each of
    // them, so the plan has to list exactly those ten source times.
    assertEquals(10, samples.size)
    assertEquals(listOf(0L, 100_000L, 200_000L), samples.take(3).map { it.sourceUs })
    assertEquals(900_000L, samples.last().sourceUs)
    assertTrue("every sample is on some frame boundary", samples.all { it.sourceUs % 100_000L == 0L })
  }

  @Test
  fun samplesAreBoundedButKeepTheLastFrame() {
    val samples = TrackingPrewarmPlan.samplesFor(clip(durationMs = 30_000L), fps = 30, maxSamples = 60)
    assertTrue("thinned to the budget: ${samples.size}", samples.size <= 61)
    assertTrue("first frame is sampled", samples.first().sourceUs == 0L)
    // Last exported frame of a 30 s / 30 fps clip is frame 899 -> 29.966 s of source.
    assertEquals("last frame is sampled", 29_966_000L, samples.last().sourceUs)
  }

  @Test
  fun speedScalesTheSourceTime() {
    val fast = TrackingPrewarmPlan.samplesFor(clip(durationMs = 2000L, speed = 2f), fps = 10, maxSamples = 100)
    assertEquals("2x speed reads two seconds of source per second of timeline", 200_000L, fast[1].sourceUs)
  }

  @Test
  fun aFreezeFrameIsSampledOnceWhateverTheTimelinePosition() {
    val frozen = TrackingPrewarmPlan.samplesFor(
      clip(durationMs = 2000L, freezeFrameAtMs = 750L), fps = 10, maxSamples = 100
    )
    assertTrue("a frozen clip asks the tracker for one picture only", frozen.all { it.sourceUs == 750_000L })
  }

  @Test
  fun severalClipsShareTheBudget() {
    val clips = listOf(clip(id = "a"), clip(id = "b", startMs = 1000L), clip(id = "c", startMs = 2000L))
    val samples = TrackingPrewarmPlan.samplesFor(clips, fps = 30, maxTotalSamples = 30)
    assertTrue("never more than the budget: ${samples.size}", samples.size <= 30)
    assertEquals(setOf("a", "b", "c"), samples.map { it.clipId }.toSet())
  }

  @Test
  fun noClipsMeansNoWork() {
    assertEquals(emptyList<TrackingPrewarmPlan.Sample>(), TrackingPrewarmPlan.samplesFor(emptyList(), fps = 30))
    assertEquals(emptyList<TrackingPrewarmPlan.Sample>(), TrackingPrewarmPlan.samplesFor(listOf(clip()), fps = 30, maxTotalSamples = 0))
  }

  @Test
  fun aShorterThanOneFrameClipStillSamplesItsFirstPicture() {
    val samples = TrackingPrewarmPlan.samplesFor(clip(durationMs = 10L), fps = 30, maxSamples = 10)
    assertTrue(samples.isNotEmpty())
    assertEquals(0L, samples.first().sourceUs)
  }
}
