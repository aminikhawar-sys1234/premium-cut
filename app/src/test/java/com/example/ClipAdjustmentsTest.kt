package com.example

import com.example.data.local.TimelineSerializer
import com.example.domain.model.Timeline
import com.example.domain.model.VideoAdjustments
import com.example.domain.model.VideoClip
import com.example.domain.model.effectiveAdjustments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipAdjustmentsTest {

  @Test
  fun clipWithoutOverrideInheritsProjectAdjustments() {
    val timeline = Timeline(adjustments = VideoAdjustments(brightness = 0.3f))
    val clip = VideoClip(name = "a")
    assertEquals(0.3f, clip.effectiveAdjustments(timeline).brightness, 0.0001f)
    assertEquals(0.3f, (null as VideoClip?).effectiveAdjustments(timeline).brightness, 0.0001f)
  }

  @Test
  fun clipOverrideWinsAndLeavesOtherClipsAlone() {
    val timeline = Timeline(adjustments = VideoAdjustments(brightness = 0.3f))
    val a = VideoClip(name = "a", adjustments = VideoAdjustments(brightness = -0.5f))
    val b = VideoClip(name = "b")
    assertEquals(-0.5f, a.effectiveAdjustments(timeline).brightness, 0.0001f)
    assertEquals(0.3f, b.effectiveAdjustments(timeline).brightness, 0.0001f)
  }

  @Test
  fun overrideSurvivesSerializationAndOldProjectsLoadWithNull() {
    val clip = VideoClip(id = "c1", name = "a", adjustments = VideoAdjustments(contrast = 1.4f, hdrBoost = 0.6f))
    val json = TimelineSerializer.serializeTimeline(Timeline(videoClips = listOf(clip)))
    val back = TimelineSerializer.fromJsonOrNull(json)!!.videoClips.single()
    assertEquals(1.4f, back.adjustments!!.contrast, 0.0001f)
    assertEquals(0.6f, back.adjustments!!.hdrBoost, 0.0001f)

    val plain = TimelineSerializer.serializeTimeline(Timeline(videoClips = listOf(VideoClip(id = "c2", name = "b"))))
    assertNull(TimelineSerializer.fromJsonOrNull(plain)!!.videoClips.single().adjustments)
  }

  @Test
  fun sharedSlidersAreIndependentFields() {
    val a = VideoAdjustments().copy(colorCorrect = 0.5f)
    assertEquals(0f, a.hdrBoost, 0f)
    val b = VideoAdjustments().copy(superClarity = 0.7f)
    assertEquals(0f, b.clarity, 0f)
  }
}
