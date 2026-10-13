package com.example

import com.example.domain.model.VideoClip
import com.example.engine.timeline.AttributeGroup
import com.example.engine.timeline.ClipAttributes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipAttributesTest {

  private val source = VideoClip(
    id = "src", name = "src", timelineStartMs = 0, durationMs = 4000, sourceEndMs = 4000,
    opacity = 0.6f, blendMode = "Screen", volume = 0.3f, colorGradeJson = "{grade}", vfxStackJson = "{vfx}",
    cropScale = 1.4f, rotationDegrees = 90, speed = 2f
  )
  private val target = VideoClip(
    id = "dst", name = "dst", timelineStartMs = 4000, durationMs = 6000, sourceStartMs = 1000, sourceEndMs = 13000,
    speed = 0.5f, motionTrackJson = "track", stabilize = "stab"
  )

  @Test fun pasteCopiesLookMotionAndAudio() {
    val out = ClipAttributes.from(source).applyTo(target)
    assertEquals("{grade}", out.colorGradeJson)
    assertEquals("{vfx}", out.vfxStackJson)
    assertEquals(0.6f, out.opacity, 0f)
    assertEquals("Screen", out.blendMode)
    assertEquals(0.3f, out.volume, 0f)
    assertEquals(1.4f, out.cropScale, 0f)
    assertEquals(90, out.rotationDegrees)
  }

  @Test fun pasteNeverTouchesTimingOrFootageBoundData() {
    val out = ClipAttributes.from(source).applyTo(target)
    assertEquals("dst", out.id)
    assertEquals(0.5f, out.speed, 0f)
    assertEquals(4000L, out.timelineStartMs)
    assertEquals(6000L, out.durationMs)
    assertEquals(1000L, out.sourceStartMs)
    assertEquals(13000L, out.sourceEndMs)
    assertEquals("track", out.motionTrackJson)
    assertEquals("stab", out.stabilize)
  }

  @Test fun onlySelectedGroupsAreApplied() {
    val out = ClipAttributes.from(source).applyTo(target, setOf(AttributeGroup.LOOK))
    assertEquals("{grade}", out.colorGradeJson)
    assertEquals(1f, out.opacity, 0f)
    assertEquals(1f, out.volume, 0f)
    assertEquals(0, out.rotationDegrees)
  }

  @Test fun pasteRemovesLookWhenSourceHadNone() {
    val styled = target.copy(colorGradeJson = "{old}", vfxStackJson = "{oldvfx}")
    val out = ClipAttributes.from(source.copy(colorGradeJson = null, vfxStackJson = null)).applyTo(styled)
    assertNull(out.colorGradeJson)
    assertNull(out.vfxStackJson)
  }
}
