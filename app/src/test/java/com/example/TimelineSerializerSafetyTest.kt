package com.example

import com.example.data.local.TimelineSerializer
import com.example.domain.model.Timeline
import com.example.domain.model.VideoClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

  @Test
  fun roundTrip_preservesClipFilterAndPlayhead() {
    val clip = VideoClip(
      id = "v1", uri = "file:///v1.mp4", name = "v1",
      durationMs = 8_000L, timelineStartMs = 0L,
      filter = com.example.domain.model.FilterSettings(
        type = com.example.domain.model.FilterType.CINEMATIC,
        intensity = 0.65f,
        enabled = true
      )
    )
    val settings = com.example.domain.model.ProjectSettings(playheadMs = 2_400L)
    val timeline = Timeline(videoClips = listOf(clip))
    val pkg = TimelineSerializer.buildProjectPackage(
      projectId = "p1", projectName = "P", settings = settings, timeline = timeline, isDraft = false
    )
    val json = TimelineSerializer.toPackageJson(pkg)
    val restoredPkg = TimelineSerializer.fromPackageJson(json)
    assertNotNull(restoredPkg)
    val restored = restoredPkg!!.timeline.videoClips.single()
    assertEquals(com.example.domain.model.FilterType.CINEMATIC, restored.filter?.type)
    assertEquals(0.65f, restored.filter?.intensity ?: 0f, 0.001f)
    assertEquals(true, restored.filter?.enabled)
    assertEquals(2_400L, restoredPkg.settings.playheadMs)
    assertFalse(json.contains("textureId") || json.contains("egl") || json.contains("framebuffer"))
  }

  @Test
  fun roundTrip_preservesCompoundNest_andSkipsShellUriAsMedia() {
    val nested = Timeline(
      videoClips = listOf(
        com.example.domain.model.VideoClip(
          id = "inner", uri = "file:///inner.mp4", name = "inner", durationMs = 2000L, sourceEndMs = 2000L
        )
      )
    )
    val shell = com.example.domain.model.VideoClip(
      id = "compound",
      uri = com.example.domain.model.compoundClipUri("compound"),
      name = "Scene",
      durationMs = 2000L,
      sourceEndMs = 2000L,
      mimeType = com.example.domain.model.COMPOUND_CLIP_MIME
    )
    val timeline = Timeline(
      videoClips = listOf(shell),
      compoundClips = listOf(
        com.example.domain.model.CompoundClip(id = "compound", name = "Scene", nested = nested, contentDurationMs = 2000L)
      )
    )
    val json = TimelineSerializer.toJson(timeline)
    val restored = TimelineSerializer.fromJsonOrNull(json)
    assertNotNull(restored)
    assertEquals("inner", restored!!.compoundClips.single().nested.videoClips.single().id)
    assertEquals("file:///inner.mp4", restored.compoundClips.single().nested.videoClips.single().uri)
    val pkg = TimelineSerializer.buildProjectPackage(
      projectId = "p", projectName = "P", settings = com.example.domain.model.ProjectSettings(), timeline = timeline
    )
    assertEquals(listOf("inner"), pkg.mediaReferences.map { it.clipId })
    assertFalse(pkg.mediaReferences.any { it.uri.startsWith("compound://") })
  }
}
