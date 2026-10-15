package com.example.engine.composition

import com.example.domain.model.FilterSettings
import com.example.domain.model.FilterType
import com.example.domain.model.Timeline
import com.example.domain.model.VideoAdjustments
import com.example.domain.model.VideoClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which look a composed layer draws, and what the preview and the export must hand the renderer.
 *
 * ## The bug these tests pin down
 *
 * `GpuCompositionRenderer.render`'s `timelineAdjustments` / `timelineFilter` are **project-level
 * fallbacks**: the renderer resolves the main picture, every transition side and every PIP / overlay
 * against them, per layer. `PreviewCompositor` passed the timeline values; `AsyncFramePipelineEngine`
 * (export) pre-resolved the *main* clip and passed that instead:
 *
 * ```
 * adjustments = frame.activeClip?.adjustments ?: timeline.adjustments   // export, before
 * adjustments = tl.adjustments                                          // preview
 * ```
 *
 * For the main layer the two agree (resolving twice is idempotent). For every other layer they do
 * not: an overlay or transition side with no clip-local look inherited the **main clip's** grade in
 * the export while the preview fell back to the timeline's. Because the Filters panel writes
 * clip-local looks and clears `Timeline.filter` to `NONE`, exported PIPs carried a grade the preview
 * never showed.
 *
 * Pure Kotlin (domain models only), so `toolchain/verify/run_logic_tests.sh` executes it.
 */
class CompositionLookResolverTest {

  private val timelineLook = VideoAdjustments(brightness = 0.10f, contrast = 1.05f)
  private val timelineFilter = FilterSettings(type = FilterType.NONE, intensity = 1f)

  private val mainLook = VideoAdjustments(brightness = 0.45f, saturation = 1.8f, temperature = 0.3f)
  private val mainFilter = FilterSettings(type = FilterType.CINEMATIC, intensity = 0.85f)

  private val overlayLook = VideoAdjustments(brightness = -0.2f)
  private val overlayFilter = FilterSettings(type = FilterType.WARM, intensity = 0.6f)

  private fun clip(
    id: String,
    adjustments: VideoAdjustments? = null,
    filter: FilterSettings? = null
  ) = VideoClip(id = id, uri = "file:///$id.mp4", name = id, adjustments = adjustments, filter = filter)

  private fun timeline(vararg clips: VideoClip) = Timeline(
    videoClips = clips.toList(),
    adjustments = timelineLook,
    filter = timelineFilter
  )

  /** What the export used to pass: the main clip's look, pre-resolved. */
  private fun legacyExportFallback(timeline: Timeline, activeClip: VideoClip?) =
    activeClip?.adjustments ?: timeline.adjustments

  private fun legacyExportFilterFallback(timeline: Timeline, activeClip: VideoClip?) =
    activeClip?.filter ?: timeline.filter

  @Test
  fun aLayersOwnLookWinsOverTheProjectDefault() {
    val graded = clip("v1", mainLook, mainFilter)
    assertEquals(mainLook, CompositionLookResolver.adjustmentsFor(graded, timelineLook))
    assertEquals(mainFilter, CompositionLookResolver.filterFor(graded, timelineFilter))
  }

  @Test
  fun aLayerWithoutALookFallsBackToTheProjectDefault() {
    val plain = clip("v1")
    assertEquals(timelineLook, CompositionLookResolver.adjustmentsFor(plain, timelineLook))
    assertEquals(timelineFilter, CompositionLookResolver.filterFor(plain, timelineFilter))
    // No clip at all (an empty gap on the main track) is the same fallback.
    assertEquals(timelineLook, CompositionLookResolver.adjustmentsFor(null, timelineLook))
    assertEquals(timelineFilter, CompositionLookResolver.filterFor(null, timelineFilter))
  }

  @Test
  fun anOverlayFallsBackToTheTimelineNotToTheMainClip() {
    val main = clip("main", mainLook, mainFilter)
    val pip = clip("pip") // no clip-local look
    val tl = timeline(main, pip)

    val correctFallback = CompositionLookResolver.timelineFallback(tl)
    assertEquals(
      "the exported PIP must fall back to the project look, exactly like the preview",
      timelineLook,
      CompositionLookResolver.adjustmentsFor(pip, correctFallback.adjustments)
    )
    assertEquals(
      timelineFilter,
      CompositionLookResolver.filterFor(pip, correctFallback.filter)
    )

    // Evidence of the old divergence: with the export's pre-resolved fallback the same overlay
    // inherited the MAIN clip's grade, which the preview never applied to it.
    val legacy = legacyExportFallback(tl, main)
    assertEquals(mainLook, legacy)
    assertEquals(
      "the legacy export fallback leaked the main clip's look onto the overlay",
      mainLook,
      CompositionLookResolver.adjustmentsFor(pip, legacy)
    )
    assertFalse(
      "preview and export disagreed for this overlay before the fix",
      CompositionLookResolver.adjustmentsFor(pip, correctFallback.adjustments) ==
        CompositionLookResolver.adjustmentsFor(pip, legacy)
    )
    assertEquals(
      mainFilter,
      CompositionLookResolver.filterFor(pip, legacyExportFilterFallback(tl, main))
    )
  }

  @Test
  fun anOverlayKeepsItsOwnLookUnderBothFallbacks() {
    val main = clip("main", mainLook, mainFilter)
    val pip = clip("pip", overlayLook, overlayFilter)
    val tl = timeline(main, pip)
    // A layer that does carry its own look was never affected - this is why the bug only showed up
    // on ungraded overlays.
    for (fallback in listOf(CompositionLookResolver.timelineFallback(tl).adjustments, legacyExportFallback(tl, main))) {
      assertEquals(overlayLook, CompositionLookResolver.adjustmentsFor(pip, fallback))
    }
    for (fallback in listOf(CompositionLookResolver.timelineFallback(tl).filter, legacyExportFilterFallback(tl, main))) {
      assertEquals(overlayFilter, CompositionLookResolver.filterFor(pip, fallback))
    }
  }

  @Test
  fun eachTransitionSideResolvesItsOwnLook() {
    val before = clip("a", mainLook, mainFilter)
    val after = clip("b") // ungraded side
    val tl = timeline(before, after)
    val fallback = CompositionLookResolver.timelineFallback(tl)

    assertEquals(mainLook, CompositionLookResolver.adjustmentsFor(before, fallback.adjustments))
    assertEquals(
      "the ungraded side of a transition takes the project look, not the other side's",
      timelineLook,
      CompositionLookResolver.adjustmentsFor(after, fallback.adjustments)
    )
    assertEquals(mainFilter, CompositionLookResolver.filterFor(before, fallback.filter))
    assertEquals(timelineFilter, CompositionLookResolver.filterFor(after, fallback.filter))

    // With the legacy export fallback the ungraded side picked up the main clip's grade mid-transition.
    assertEquals(
      mainLook,
      CompositionLookResolver.adjustmentsFor(after, legacyExportFallback(tl, before))
    )
  }

  @Test
  fun preResolvingTheMainClipIsIdempotentForTheMainLayerOnly() {
    val main = clip("main", mainLook, mainFilter)
    val tl = timeline(main)
    val fallback = CompositionLookResolver.timelineFallback(tl)

    // Why the export looked correct while still passing the wrong thing: resolving an already
    // clip-effective value against the same clip changes nothing.
    assertTrue(CompositionLookResolver.isIdempotentForMainLayer(main, fallback.adjustments))
    assertEquals(
      CompositionLookResolver.adjustmentsFor(main, fallback.adjustments),
      CompositionLookResolver.adjustmentsFor(main, legacyExportFallback(tl, main))
    )
    assertEquals(
      CompositionLookResolver.filterFor(main, fallback.filter),
      CompositionLookResolver.filterFor(main, legacyExportFilterFallback(tl, main))
    )
  }

  @Test
  fun timelineFallbackIsTheTimelineLookForEveryCaller() {
    val tl = timeline(clip("main", mainLook, mainFilter))
    val fallback = CompositionLookResolver.timelineFallback(tl)
    assertEquals(tl.adjustments, fallback.adjustments)
    assertEquals(tl.filter, fallback.filter)
    assertEquals(CompositionLookResolver.TimelineFallback.of(tl), fallback)
    // Neither caller may resolve a clip here - that is the renderer's job, per layer.
    assertFalse(fallback.adjustments == mainLook)
    assertFalse(fallback.filter == mainFilter)
  }

  @Test
  fun anEmptyProjectAndAProjectWithoutEffectsResolveToTheDefaults() {
    val empty = Timeline()
    val emptyFallback = CompositionLookResolver.timelineFallback(empty)
    assertEquals(VideoAdjustments(), emptyFallback.adjustments)
    assertEquals(FilterSettings(), emptyFallback.filter)
    assertEquals(VideoAdjustments(), CompositionLookResolver.adjustmentsFor(null, emptyFallback.adjustments))
    assertEquals(FilterSettings(), CompositionLookResolver.filterFor(null, emptyFallback.filter))

    // A project with clips but no look anywhere: every layer resolves to the neutral defaults, so
    // the composer draws the sources untouched.
    val plainClip = clip("v1")
    val plain = Timeline(videoClips = listOf(plainClip))
    val plainFallback = CompositionLookResolver.timelineFallback(plain)
    assertEquals(VideoAdjustments(), plainFallback.adjustments)
    assertEquals(VideoAdjustments(), CompositionLookResolver.adjustmentsFor(plainClip, plainFallback.adjustments))
    assertEquals(FilterType.NONE, CompositionLookResolver.filterFor(plainClip, plainFallback.filter).type)
  }

  @Test
  fun anUngradedClipInAGradedProjectInheritsTheProjectLook() {
    // The project-level look is a real fallback, not a neutral placeholder: this is what the
    // "apply to all clips" project default means, and it is identical in preview and export.
    val ungraded = clip("v1")
    val tl = timeline(ungraded)
    val fallback = CompositionLookResolver.timelineFallback(tl)
    assertEquals(timelineLook, CompositionLookResolver.adjustmentsFor(ungraded, fallback.adjustments))
    assertEquals(timelineFilter, CompositionLookResolver.filterFor(ungraded, fallback.filter))
    // ... and it must survive a null active clip (a gap on the main track) the same way.
    assertEquals(timelineLook, CompositionLookResolver.adjustmentsFor(null, fallback.adjustments))
  }

  @Test
  fun aDisabledFilterIsStillTheLayersOwnFilter() {
    // Enabled/disabled is the renderer's and ColorLookModel's business, not the resolver's: the
    // resolver must hand back the stored value untouched so disabling a filter cannot silently
    // promote the project default onto the clip.
    val disabled = FilterSettings(type = FilterType.CINEMATIC, intensity = 1f, enabled = false)
    val graded = clip("v1", filter = disabled)
    assertEquals(disabled, CompositionLookResolver.filterFor(graded, timelineFilter))
    assertFalse(CompositionLookResolver.filterFor(graded, timelineFilter).enabled)
  }

  @Test
  fun zeroIntensityIsStillTheLayersOwnLook() {
    val zero = FilterSettings(type = FilterType.WARM, intensity = 0f)
    val graded = clip("v1", filter = zero)
    assertEquals(zero, CompositionLookResolver.filterFor(graded, timelineFilter))
    assertEquals(0f, CompositionLookResolver.filterFor(graded, timelineFilter).intensity, 0.0001f)
  }
}
