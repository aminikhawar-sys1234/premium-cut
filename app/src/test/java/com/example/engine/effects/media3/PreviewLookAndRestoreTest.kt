package com.example.engine.effects.media3

import com.example.data.local.TimelineSerializer
import com.example.domain.model.FilterSettings
import com.example.domain.model.FilterType
import com.example.domain.model.ProjectSettings
import com.example.domain.model.Timeline
import com.example.domain.model.VideoAdjustments
import com.example.domain.model.VideoClip
import com.example.engine.SelectedTrackElement
import com.example.engine.TimelineEngine
import com.example.engine.composition.ColorFilterGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreviewLookAndRestoreTest {

  @Test
  fun artisticFilterIsDisplayOnlyAndDoesNotChangeMedia3Signature() {
    val clip = VideoClip(
      id = "v1",
      name = "v1",
      uri = "file:///v1.mp4",
      durationMs = 4000L,
      filter = FilterSettings(type = FilterType.FOUR_K, intensity = 1f)
    )
    val timeline = Timeline(videoClips = listOf(clip))
    val none = clip.copy(filter = FilterSettings())
    val bw = clip.copy(filter = FilterSettings(type = FilterType.BLACK_AND_WHITE, intensity = 0.8f))

    assertEquals(
      PreviewFilterEffects.media3Signature(none, timeline),
      PreviewFilterEffects.media3Signature(bw, timeline)
    )
    assertTrue(PreviewFilterEffects.effectsFor(clip, timeline).none { it is ColorGradingGlEffect })
    assertNotNull(PreviewDisplayFilter.matrixFor(clip, timeline))
    assertNull(PreviewDisplayFilter.matrixFor(none, timeline))
    assertNotEquals(
      PreviewDisplayFilter.matrixFor(clip, timeline)!!.array.toList(),
      PreviewDisplayFilter.matrixFor(bw, timeline)!!.array.toList()
    )
  }

  @Test
  fun adjustmentsStillProduceMedia3ColorGrading() {
    val clip = VideoClip(
      id = "v1",
      name = "v1",
      uri = "file:///v1.mp4",
      durationMs = 4000L,
      adjustments = VideoAdjustments(brightness = 0.25f, vignette = 0.4f)
    )
    val timeline = Timeline(videoClips = listOf(clip))
    val effects = PreviewFilterEffects.effectsFor(clip, timeline)
    assertTrue(effects.any { it is ColorGradingGlEffect })
    assertFalse((effects.single { it is ColorGradingGlEffect } as ColorGradingGlEffect).isNoOp(1920, 1080))
    assertNotEquals(
      PreviewFilterEffects.media3Signature(clip, timeline),
      PreviewFilterEffects.media3Signature(clip.copy(adjustments = VideoAdjustments()), timeline)
    )
  }

  @Test
  fun disabledFilterIsNotAppliedInPreviewOrExportMatrix() {
    val disabled = FilterSettings(type = FilterType.CINEMATIC, intensity = 1f, enabled = false)
    assertFalse(PreviewFilterEffects.isActiveLook(disabled))
    assertNull(PreviewDisplayFilter.matrixFor(disabled))
    val combinedDisabled = ColorFilterGenerator.createCombinedMatrix(VideoAdjustments(), FilterSettings(), disabled)
    val combinedNone = ColorFilterGenerator.createCombinedMatrix(VideoAdjustments(), FilterSettings(), FilterSettings())
    assertEquals(combinedNone.array.toList(), combinedDisabled.array.toList())
  }

  @Test
  fun intensityChangeUpdatesDisplayMatrixWithoutMedia3SignatureChange() {
    val low = VideoClip(
      id = "v1", name = "v1", uri = "u", durationMs = 3000L,
      filter = FilterSettings(type = FilterType.GOLDEN_AUTUMN, intensity = 0.2f)
    )
    val high = low.copy(filter = FilterSettings(type = FilterType.GOLDEN_AUTUMN, intensity = 1f))
    val timeline = Timeline(videoClips = listOf(low))
    assertEquals(
      PreviewFilterEffects.media3Signature(low, timeline),
      PreviewFilterEffects.media3Signature(high, timeline)
    )
    val lowMat = PreviewDisplayFilter.matrixFor(low, timeline)
    val highMat = PreviewDisplayFilter.matrixFor(high, timeline)
    assertNotNull(lowMat)
    assertNotNull(highMat)
    assertNotEquals(lowMat!!.array.toList(), highMat!!.array.toList())
  }

  @Test
  fun timelineEnginePersistsFilterAndPlayheadSurvivesPackageRoundTrip() {
    val engine = TimelineEngine()
    engine.loadTimeline(
      Timeline(
        videoClips = listOf(
          VideoClip(id = "v1", name = "v1", uri = "file:///v1.mp4", durationMs = 6000L)
        )
      )
    )
    engine.selectElement(SelectedTrackElement.Video("v1"))
    engine.updateFilter(FilterSettings(type = FilterType.OCEANIC_VIEW, intensity = 0.55f), "v1")
    engine.setPosition(1_800L, snap = false)
    assertEquals(FilterType.OCEANIC_VIEW, engine.timeline.value.videoClips.single().filter?.type)
    assertEquals(0.55f, engine.timeline.value.videoClips.single().filter?.intensity ?: 0f, 0.001f)

    val pkg = TimelineSerializer.buildProjectPackage(
      projectId = "p",
      projectName = "P",
      settings = ProjectSettings(playheadMs = engine.currentPositionMs.value),
      timeline = engine.timeline.value
    )
    val json = TimelineSerializer.toPackageJson(pkg)
    val restored = TimelineSerializer.fromPackageJson(json)!!
    assertEquals(FilterType.OCEANIC_VIEW, restored.timeline.videoClips.single().filter?.type)
    assertEquals(0.55f, restored.timeline.videoClips.single().filter?.intensity ?: 0f, 0.001f)
    assertEquals(1_800L, restored.settings.playheadMs)

    val reopen = TimelineEngine()
    reopen.loadTimeline(restored.timeline)
    assertEquals(0L, reopen.currentPositionMs.value)
    reopen.setPosition(restored.settings.playheadMs, snap = false)
    assertEquals(1_800L, reopen.currentPositionMs.value)
    assertEquals(FilterType.OCEANIC_VIEW, reopen.timeline.value.videoClips.single().filter?.type)
  }

  @Test
  fun resetAndSwitchFiltersDoNotLeakOntoOtherClips() {
    val engine = TimelineEngine()
    engine.loadTimeline(
      Timeline(
        videoClips = listOf(
          VideoClip(id = "v1", name = "v1", uri = "a", durationMs = 3000L, timelineStartMs = 0L),
          VideoClip(id = "v2", name = "v2", uri = "b", durationMs = 3000L, timelineStartMs = 3000L)
        )
      )
    )
    engine.updateFilter(FilterSettings(type = FilterType.CINEMATIC, intensity = 1f), "v1")
    engine.updateFilter(FilterSettings(type = FilterType.WARM, intensity = 0.7f), "v2")
    engine.updateFilter(FilterSettings(type = FilterType.NONE), "v1")
    val tl = engine.timeline.value
    assertEquals(FilterType.NONE, tl.videoClips[0].filter?.type)
    assertEquals(FilterType.WARM, tl.videoClips[1].filter?.type)
    assertEquals(FilterType.NONE, tl.filter.type)
  }

  @Test
  fun exportAndPreviewShareColorFilterGeneratorMatrix() {
    val filter = FilterSettings(type = FilterType.BLACK_AND_WHITE, intensity = 1f)
    val preview = PreviewDisplayFilter.matrixFor(filter)
    val export = ColorFilterGenerator.getFilterMatrix(filter.type, filter.intensity)
    assertNotNull(preview)
    assertNotNull(export)
    assertEquals(export!!.array.toList(), preview!!.array.toList())
  }
}
