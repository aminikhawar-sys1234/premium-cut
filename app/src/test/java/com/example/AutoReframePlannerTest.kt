package com.example

import com.example.engine.ai.reframe.AutoReframePlanner
import com.example.engine.ai.reframe.SubjectSample
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoReframePlannerTest {

  private val landscape = 16f / 9f
  private val portrait = 9f / 16f

  private fun sweep(durationMs: Long, from: Float, to: Float, stepMs: Long = 250L): List<SubjectSample> =
    (0L..durationMs step stepMs).map { t ->
      SubjectSample(t, from + (to - from) * t / durationMs.toFloat(), 0.5f)
    }

  @Test fun fillScaleMatchesAspectRatio() {
    assertEquals(landscape / portrait, AutoReframePlanner.fillScale(landscape, portrait), 1e-3f)
    assertEquals(landscape / portrait, AutoReframePlanner.fillScale(portrait, landscape), 1e-3f)
    assertEquals(1f, AutoReframePlanner.fillScale(1f, 1f), 1e-6f)
  }

  @Test fun noReframeWhenAspectsMatch() {
    assertFalse(AutoReframePlanner.needsReframe(portrait, portrait))
    assertTrue(AutoReframePlanner.plan(emptyList(), portrait, portrait, 5000L).isEmpty())
  }

  @Test fun noSubjectKeepsCentered() {
    val keys = AutoReframePlanner.plan(emptyList(), landscape, portrait, 4000L)
    assertTrue(keys.isNotEmpty())
    keys.forEach { assertEquals(0f, it.posX, 1e-4f) }
  }

  @Test fun followsSubjectMovingLeftToRight() {
    val duration = 8000L
    val keys = AutoReframePlanner.plan(sweep(duration, 0.2f, 0.8f), landscape, portrait, duration)
    val scale = keys.first().scale
    // Subject moves right in the picture, so the picture must shift left (negative posX) over time.
    assertTrue(keys.last().posX < keys.first().posX - 0.2f)
    keys.forEach { assertTrue(abs(it.posX) <= scale - 1f + 1e-4f) }
    assertTrue(keys.all { it.posY == 0f })
  }

  @Test fun subjectStaysInsideFrame() {
    val duration = 8000L
    val samples = sweep(duration, 0.15f, 0.85f)
    val keys = AutoReframePlanner.plan(samples, landscape, portrait, duration)
    for (s in samples) {
      val hi = keys.indexOfFirst { it.timeMs >= s.timeMs }.coerceAtLeast(1)
      val a = keys[hi - 1]
      val b = keys[hi]
      val f = ((s.timeMs - a.timeMs).toFloat() / (b.timeMs - a.timeMs).coerceAtLeast(1L)).coerceIn(0f, 1f)
      val key = a.copy(posX = a.posX + (b.posX - a.posX) * f)
      assertTrue("subject off screen at ${s.timeMs}", abs(AutoReframePlanner.subjectScreenX(key, s.cx)) <= 1.0f)
    }
  }

  @Test fun jitterInsideDeadZoneDoesNotMoveCamera() {
    val samples = (0L..4000L step 100L).map { SubjectSample(it, 0.5f + if ((it / 100L) % 2L == 0L) 0.01f else -0.01f, 0.5f) }
    val keys = AutoReframePlanner.plan(samples, landscape, portrait, 4000L)
    assertTrue(keys.size <= 2)
  }

  @Test fun keysAreThinned() {
    val duration = 10000L
    val keys = AutoReframePlanner.plan(sweep(duration, 0.2f, 0.8f, 100L), landscape, portrait, duration)
    assertTrue("got ${keys.size}", keys.size < 25)
  }

  @Test fun panSpeedIsLimited() {
    val samples = listOf(SubjectSample(0L, 0.1f, 0.5f), SubjectSample(200L, 0.9f, 0.5f), SubjectSample(4000L, 0.9f, 0.5f))
    val keys = AutoReframePlanner.plan(samples, landscape, portrait, 4000L)
    for (i in 1 until keys.size) {
      val dt = (keys[i].timeMs - keys[i - 1].timeMs) / 1000f
      val speed = abs(keys[i].posX - keys[i - 1].posX) / dt
      assertTrue("pan too fast: $speed", speed < 0.6f * 2f * keys[i].scale + 0.5f)
    }
  }

  @Test fun verticalPanForPortraitSourceInLandscapeCanvas() {
    val duration = 6000L
    val samples = (0L..duration step 250L).map { SubjectSample(it, 0.5f, 0.2f + 0.6f * it / duration) }
    val keys = AutoReframePlanner.plan(samples, portrait, landscape, duration)
    assertTrue(keys.all { it.posX == 0f })
    assertTrue(keys.any { it.posY != 0f })
    val scale = keys.first().scale
    keys.forEach { assertTrue(abs(it.posY) <= scale - 1f + 1e-4f) }
  }

  @Test fun missingDetectionsAreBridged() {
    val samples = listOf(
      SubjectSample(0L, 0.3f, 0.5f), SubjectSample(1000L, 0.5f, 0.5f, found = false),
      SubjectSample(2000L, 0.3f, 0.5f)
    )
    val keys = AutoReframePlanner.plan(samples, landscape, portrait, 2000L)
    assertTrue(keys.isNotEmpty())
  }
}
