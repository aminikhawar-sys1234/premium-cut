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
  fun artisticFilterUsesOneGradingNodeAndDoesNotChangeGraphSignatureAcrossPresets() {
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

    assertNotEquals(
      "Turning a look on must add the Media3 grading node once",
      PreviewFilterEffects.media3GraphSignature(none, timeline),
      PreviewFilterEffects.media3GraphSignature(clip, timeline)
    )
    assertEquals(
      "Switching presets must not rebuild the ExoPlayer graph",
      PreviewFilterEffects.media3GraphSignature(clip, timeline),
      PreviewFilterEffects.media3GraphSignature(bw, timeline)
    )
    assertTrue(PreviewFilterEffects.effectsFor(clip, timeline).any { it is ColorGradingGlEffect })
    assertTrue(PreviewFilterEffects.effectsFor(none, timeline).none { it is ColorGradingGlEffect })
    assertNotNull(PreviewDisplayFilter.matrixFor(clip, timeline))
    assertNull(PreviewDisplayFilter.matrixFor(none, timeline))
    assertNotEquals(
      PreviewDisplayFilter.matrixFor(clip, timeline)!!.array.toList(),
      PreviewDisplayFilter.matrixFor(bw, timeline)!!.array.toList()
    )
  }

  @Test
  fun colorSlidersUpdateLiveUniformsAndDoNotChangeGraphSignature() {
    val clip = VideoClip(
      id = "v1",
      name = "v1",
      uri = "file:///v1.mp4",
      durationMs = 4000L,
      adjustments = VideoAdjustments(brightness = 0.25f, highlights = 0.3f, whites = 0.2f)
    )
    val brighter = clip.copy(adjustments = VideoAdjustments(brightness = 0.4f, highlights = 0.3f, whites = 0.2f))
    val timeline = Timeline(videoClips = listOf(clip))
    val effects = PreviewFilterEffects.effectsFor(clip, timeline)
    assertTrue(effects.any { it is ColorGradingGlEffect })
    assertEquals(
      PreviewFilterEffects.media3GraphSignature(clip, timeline),
      PreviewFilterEffects.media3GraphSignature(brighter, timeline)
    )
    assertNotEquals(
      PreviewFilterEffects.media3Signature(clip, timeline),
      PreviewFilterEffects.media3Signature(brighter, timeline)
    )
    val matrix = PreviewDisplayFilter.matrixFor(clip, timeline)
    assertNotNull(matrix)
    assertFalse(ColorFilterGenerator.isIdentityMatrix(matrix!!))
  }

  @Test
  fun spatialAdjustmentsStillProduceMedia3ColorGrading() {
    val clip = VideoClip(
      id = "v1",
      name = "v1",
      uri = "file:///v1.mp4",
      durationMs = 4000L,
      adjustments = VideoAdjustments(vignette = 0.4f, sharpness = 0.5f, clarity = 0.3f)
    )
    val timeline = Timeline(videoClips = listOf(clip))
    val effects = PreviewFilterEffects.effectsFor(clip, timeline)
    assertTrue(effects.any { it is ColorGradingGlEffect })
    assertFalse((effects.single { it is ColorGradingGlEffect } as ColorGradingGlEffect).isNoOp(1920, 1080))
    assertNotEquals(
      PreviewFilterEffects.media3Signature(clip, timeline),
      PreviewFilterEffects.media3Signature(clip.copy(adjustments = VideoAdjustments()), timeline)
    )
    assertTrue(PreviewLookPolicy.hasSpatialLook(clip.adjustments!!))
  }

  @Test
  fun lookOnlyTimelineChangeIgnoresFilterAndAdjustmentEdits() {
    val base = VideoClip(id = "v1", name = "v1", uri = "file:///v1.mp4", durationMs = 4000L)
    val prev = Timeline(videoClips = listOf(base))
    val lookNext = Timeline(
      videoClips = listOf(
        base.copy(
          filter = FilterSettings(type = FilterType.CINEMATIC, intensity = 0.8f),
          adjustments = VideoAdjustments(brightness = 0.2f, sharpness = 0.4f)
        )
      )
    )
    assertTrue(PreviewLookPolicy.isLookOnlyChange(prev, lookNext))
    val trimNext = Timeline(videoClips = listOf(base.copy(durationMs = 2000L)))
    assertFalse(PreviewLookPolicy.isLookOnlyChange(prev, trimNext))
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
  fun intensityChangeUpdatesDisplayMatrixWithoutGraphSignatureChange() {
    val low = VideoClip(
      id = "v1", name = "v1", uri = "u", durationMs = 3000L,
      filter = FilterSettings(type = FilterType.GOLDEN_AUTUMN, intensity = 0.2f)
    )
    val high = low.copy(filter = FilterSettings(type = FilterType.GOLDEN_AUTUMN, intensity = 1f))
    val timeline = Timeline(videoClips = listOf(low))
    assertEquals(
      PreviewFilterEffects.media3GraphSignature(low, timeline),
      PreviewFilterEffects.media3GraphSignature(high, timeline)
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
  fun videoQualityColorControlsReachTheDisplayMatrix() {
    val quality = VideoAdjustments(autoEnhance = 0.7f, hdrBoost = 0.5f, colorFix = 0.4f, colorCorrect = 0.3f)
    val clip = VideoClip(id = "v1", name = "v1", uri = "u", durationMs = 3000L, adjustments = quality)
    val timeline = Timeline(videoClips = listOf(clip))
    val matrix = PreviewDisplayFilter.matrixFor(clip, timeline)
    assertNotNull(matrix)
    assertFalse(ColorFilterGenerator.isIdentityMatrix(matrix!!))
    assertEquals(
      PreviewFilterEffects.media3GraphSignature(clip, timeline),
      PreviewFilterEffects.media3GraphSignature(
        clip.copy(adjustments = quality.copy(autoEnhance = 0.9f, hdrBoost = 0.8f)),
        timeline
      )
    )
  }

  @Test
  fun applyLookToTimelineWritesClipLocalValues() {
    val clip = VideoClip(id = "v1", name = "v1", uri = "u", durationMs = 3000L)
    val timeline = Timeline(videoClips = listOf(clip))
    val look = LivePreviewLook(
      clipId = "v1",
      adjustments = VideoAdjustments(brightness = 0.4f),
      filter = FilterSettings(type = FilterType.WARM, intensity = 0.6f)
    )
    val next = PreviewLookPolicy.applyLookToTimeline(timeline, look)
    assertEquals(0.4f, next.videoClips.single().adjustments?.brightness ?: 0f, 0.001f)
    assertEquals(FilterType.WARM, next.videoClips.single().filter?.type)
    assertTrue(PreviewLookPolicy.isLookOnlyChange(timeline, next))
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
