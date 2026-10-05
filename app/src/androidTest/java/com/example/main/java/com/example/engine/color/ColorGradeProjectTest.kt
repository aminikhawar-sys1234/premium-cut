package com.example.engine.color

import com.ahstudio.color.core.HslAdjust
import com.ahstudio.color.core.LutState
import com.ahstudio.color.core.WheelVec3
import com.ahstudio.color.core.ColorState
import com.example.data.local.TimelineSerializer
import com.example.domain.model.Timeline
import com.example.domain.model.VideoClip
import com.example.ui.components.band
import com.example.ui.components.wheel
import com.example.ui.components.withBand
import com.example.ui.components.withWheel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ColorGradeProjectTest {
  @Test
  fun gradeSurvivesProjectSaveAndLoad() {
    val grade = ColorState(
      exposure = 0.6f,
      lut = LutState(lutId = "teal_orange", intensity = 0.7f)
    )
    val clip = VideoClip(id = "proj_clip", name = "c", colorGradeJson = ColorEngineHost.encode(grade))
    val json = TimelineSerializer.toJson(Timeline(videoClips = listOf(clip)))
    val loaded = TimelineSerializer.fromJsonOrNull(json)
    assertNotNull(loaded)
    val restored = ColorEngineHost.decodeOrDefault(loaded!!.videoClips.single().colorGradeJson)
    assertEquals(0.6f, restored.exposure, 1e-6f)
    assertEquals("teal_orange", restored.lut?.lutId)
    assertEquals(0.7f, restored.lut?.intensity ?: 0f, 1e-6f)
  }

  @Test
  fun ungradedClipStaysNullAfterRoundTrip() {
    val json = TimelineSerializer.toJson(Timeline(videoClips = listOf(VideoClip(id = "x", name = "x"))))
    assertNull(TimelineSerializer.fromJsonOrNull(json)!!.videoClips.single().colorGradeJson)
  }

  @Test
  fun hslBandHelpersTouchOnlyTheChosenBand() {
    val base = ColorState().hsl
    for (i in 0 until 8) {
      val edited = base.withBand(i, HslAdjust(hueShift = 0.1f, sat = 0.2f, lum = -0.3f))
      for (j in 0 until 8) {
        val expected = if (j == i) HslAdjust(0.1f, 0.2f, -0.3f) else HslAdjust()
        assertEquals("edit $i, read $j", expected, edited.band(j))
      }
    }
  }

  @Test
  fun wheelHelpersTouchOnlyTheChosenWheel() {
    val base = ColorState().wheels
    for (i in 0 until 4) {
      val edited = base.withWheel(i, WheelVec3(0.1f, 0.2f, 0.3f, 0.4f))
      for (j in 0 until 4) {
        val expected = if (j == i) WheelVec3(0.1f, 0.2f, 0.3f, 0.4f) else WheelVec3()
        assertEquals("edit $i, read $j", expected, edited.wheel(j))
      }
    }
  }
}
