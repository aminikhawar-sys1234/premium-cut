package com.example

import com.example.data.local.TimelineSerializer
import com.example.domain.model.Timeline
import com.example.domain.model.VideoClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests: unreadable project data must be reported as unreadable, never silently turned into an
 * empty timeline that would then be auto-saved over the user's real project.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TimelineSerializerSafetyTest {

  @Test
  fun emptyInput_isAnEmptyTimeline() {
    assertEquals(Timeline(), TimelineSerializer.fromJsonOrNull(""))
    assertEquals(Timeline(), TimelineSerializer.fromJsonOrNull("   "))
    assertEquals(Timeline(), TimelineSerializer.fromJsonOrNull("{}"))
  }

  @Test
  fun garbageInput_isReportedAsUnreadable_notEmpty() {
    assertNull(TimelineSerializer.fromJsonOrNull("this is not json"))
    assertNull(TimelineSerializer.fromJsonOrNull("{\"videoClips\": [ {\"id\": "))
  }

  @Test
  fun garbageInput_legacyFromJson_stillReturnsEmpty_forNonProjectCallers() {
    assertEquals(Timeline(), TimelineSerializer.fromJson("this is not json"))
  }

  @Test
  fun garbagePackage_isNull() {
    assertNull(TimelineSerializer.fromPackageJson("{\"timeline\": not-json"))
  }

  @Test
  fun roundTrip_preservesClipFields() {
    val clip = VideoClip(
      id = "v1", uri = "file:///v1.mp4", name = "v1",
      timelineStartMs = 1_500L, durationMs = 4_000L, sourceStartMs = 1_000L, sourceEndMs = 5_000L, speed = 1.25f
    )
    val timeline = Timeline(videoClips = listOf(clip))
    val pkg = TimelineSerializer.buildProjectPackage(
      projectId = "p1", projectName = "P", settings = com.example.domain.model.ProjectSettings(), timeline = timeline, isDraft = false
    )
    val json = TimelineSerializer.toPackageJson(pkg)
    val restored = TimelineSerializer.fromJsonOrNull(json)
    assertNotNull(restored)
    val c = restored!!.videoClips.single()
    assertEquals("v1", c.id)
    assertEquals(1_500L, c.timelineStartMs)
    assertEquals(4_000L, c.durationMs)
    assertEquals(1_000L, c.sourceStartMs)
    assertEquals(5_000L, c.sourceEndMs)
    assertEquals(1.25f, c.speed, 0.001f)
    assertTrue(restored.totalDurationMs == timeline.totalDurationMs)
  }
}
