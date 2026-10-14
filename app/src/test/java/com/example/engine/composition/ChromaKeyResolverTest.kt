package com.example.engine.composition

import com.example.domain.model.ChromaKeySettings
import com.example.domain.model.Timeline
import com.example.domain.model.VideoClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a chroma key applies and what it draws behind the subject.
 *
 * The reported bug was that keying one clip keyed the whole project (one global setting), and that the
 * "image / video background" choices were stored but never rendered. These tests pin both down, plus the
 * edge-cleanup spill rule the shader shares.
 */
class ChromaKeyResolverTest {

  private fun clip(id: String, chroma: ChromaKeySettings? = null) =
    VideoClip(id = id, uri = "file:///$id.mp4", name = id, chromaKey = chroma)

  private val projectGreen = ChromaKeySettings(enabled = true, targetColor = 0xFF00FF00)

  @Test
  fun eachClipKeepsItsOwnKeyAndUntouchedClipsInheritTheDefault() {
    val own = ChromaKeySettings(enabled = true, targetColor = 0xFF0047BB)
    val blueClip = clip("blue", own)
    val plain = clip("plain")

    assertEquals(0xFF0047BB, ChromaKeyResolver.settingsFor(blueClip, projectGreen).targetColor)
    assertEquals(0xFF00FF00, ChromaKeyResolver.settingsFor(plain, projectGreen).targetColor)
    // Keying a clip with its own setting must not switch keying on for a clip that has none ... it inherits
    // the project default, which is the documented behaviour; a project-wide key is explicit.
    assertNull(ChromaKeyResolver.activeSettingsFor(clip("off", ChromaKeySettings(enabled = false)), projectGreen))
  }

  @Test
  fun aDisabledClipSettingBeatsAnEnabledProjectDefault() {
    val disabled = clip("off", ChromaKeySettings(enabled = false))
    assertFalse(ChromaKeyResolver.requiresComposition(Timeline(videoClips = listOf(disabled), chromaKey = projectGreen)))
    assertTrue(
      ChromaKeyResolver.requiresComposition(
        Timeline(videoClips = listOf(clip("on", ChromaKeySettings(enabled = true))))
      )
    )
  }

  @Test
  fun backgroundModesFollowTheBackgroundType() {
    val image = ChromaKeySettings(enabled = true, backgroundType = "Image", backgroundUri = "file:///bg.jpg")
    val video = ChromaKeySettings(enabled = true, backgroundType = "Video", backgroundUri = "file:///bg.mp4")
    val solid = ChromaKeySettings(enabled = true, backgroundType = "SolidColor", backgroundColor = 0xFF102030)
    val transparent = ChromaKeySettings(enabled = true, backgroundType = "Transparent")

    assertEquals(ChromaKeyResolver.BACKGROUND_MODE_TEXTURE, ChromaKeyResolver.backgroundModeId(image))
    assertEquals(ChromaKeyResolver.BACKGROUND_MODE_TEXTURE, ChromaKeyResolver.backgroundModeId(video))
    assertEquals(ChromaKeyResolver.BACKGROUND_MODE_SOLID, ChromaKeyResolver.backgroundModeId(solid))
    assertEquals(ChromaKeyResolver.BACKGROUND_MODE_TRANSPARENT, ChromaKeyResolver.backgroundModeId(transparent))

    assertTrue(ChromaKeyResolver.usesBackgroundMedia(image))
    assertTrue(ChromaKeyResolver.usesBackgroundMedia(video))
    assertFalse(ChromaKeyResolver.usesBackgroundMedia(solid))
    // A background type without a picked file is not a background.
    assertFalse(ChromaKeyResolver.usesBackgroundMedia(image.copy(backgroundUri = " ")))
    assertFalse(ChromaKeyResolver.usesBackgroundMedia(null))
  }

  @Test
  fun backgroundClipDescribesThePickedMediaWithoutTouchingTheTimeline() {
    val image = ChromaKeySettings(enabled = true, backgroundType = "Image", backgroundUri = "file:///bg.jpg")
    val video = ChromaKeySettings(enabled = true, backgroundType = "Video", backgroundUri = "file:///bg.mp4")
    val imageClip = ChromaKeyResolver.backgroundClip(image)!!
    assertEquals(ChromaKeyResolver.BACKGROUND_CLIP_ID, imageClip.id)
    assertFalse(imageClip.isVideo)
    assertTrue(ChromaKeyResolver.backgroundClip(video)!!.isVideo)
    assertNull(ChromaKeyResolver.backgroundClip(ChromaKeySettings(enabled = true, backgroundType = "Image")))
  }

  @Test
  fun backgroundVideoLoopsOverItsOwnDurationWhenKnown() {
    val video = ChromaKeySettings(enabled = true, backgroundType = "Video", backgroundUri = "file:///bg.mp4")
    // Known duration (4s): the background loops instead of playing once and freezing.
    assertEquals(500L, ChromaKeyResolver.backgroundSourceMs(video, 4_500L, 4_000L))
    assertEquals(0L, ChromaKeyResolver.backgroundSourceMs(video, 4_000L, 4_000L))
    // Unknown duration: the documented nominal loop keeps working.
    assertEquals(5_000L, ChromaKeyResolver.backgroundSourceMs(video, 125_000L))
    // A negative playhead cannot produce a negative source time.
    assertEquals(0L, ChromaKeyResolver.backgroundSourceMs(video, -10L, 4_000L))
  }

  @Test
  fun imageBackgroundsAreCollectedForTheExportPreload() {
    val timeline = Timeline(
      videoClips = listOf(
        clip("a", ChromaKeySettings(enabled = true, backgroundType = "Image", backgroundUri = "file:///a.png")),
        clip("b", ChromaKeySettings(enabled = false, backgroundType = "Image", backgroundUri = "file:///off.png")),
        clip("c")
      ),
      chromaKey = ChromaKeySettings(enabled = true, backgroundType = "Image", backgroundUri = "file:///project.png")
    )
    assertEquals(
      listOf("file:///project.png", "file:///a.png"),
      ChromaKeyResolver.imageBackgroundUris(timeline)
    )
  }

  @Test
  fun coverFitCropsInsteadOfStretching() {
    val landscapeBgInPortraitFrame = ChromaKeyResolver.coverFit(1920, 1080, 1080, 1920)
    assertTrue(landscapeBgInPortraitFrame[0] < 1f && landscapeBgInPortraitFrame[1] == 1f)
    val portraitBgInLandscapeFrame = ChromaKeyResolver.coverFit(1080, 1920, 1920, 1080)
    assertTrue(portraitBgInFrame(portraitBgInLandscapeFrame))
    val identical = ChromaKeyResolver.coverFit(1080, 1920, 1080, 1920)
    assertEquals(1f, identical[0], 1e-6f)
    assertEquals(1f, identical[1], 1e-6f)
    // Unknown size: leave the picture alone rather than cropping it blindly.
    val unknown = ChromaKeyResolver.coverFit(0, 0, 1080, 1920)
    assertEquals(1f, unknown[0], 1e-6f)
    assertEquals(1f, unknown[1], 1e-6f)
  }

  private fun portraitBgInFrame(fit: FloatArray) = fit[0] == 1f && fit[1] < 1f

  @Test
  fun edgeCleanupOnlyActsOnTheSoftEdge() {
    val settings = ChromaKeySettings(enabled = true, spillSuppression = 0.2f, edgeControl = 0.8f)
    // A solid subject pixel (alpha 1) is untouched by the edge slider: skin tones must not be flattened.
    assertEquals(0.2f, ChromaKeyMath.spillStrength(1f, settings), 1e-6f)
    // A half-transparent edge pixel gets the extra de-spill that removes the green fringe.
    assertEquals(0.6f, ChromaKeyMath.spillStrength(0.5f, settings), 1e-6f)
    assertEquals(1f, ChromaKeyMath.spillStrength(0f, settings), 1e-6f)
    // Never above 1 even with both sliders at maximum.
    assertEquals(1f, ChromaKeyMath.spillStrength(0.5f, settings.copy(spillSuppression = 1f)), 1e-6f)
  }

  @Test
  fun aKeyThatPaintsItsOwnBackgroundIsNotMaskedAwayAgain() {
    // Background removal and a background-replacing key would cancel each other out, so the renderer skips
    // the cutout mask for exactly these settings and runs both for a transparent key.
    assertTrue(ChromaKeyResolver.replacesBackground(ChromaKeySettings(enabled = true, backgroundType = "SolidColor")))
    assertTrue(
      ChromaKeyResolver.replacesBackground(
        ChromaKeySettings(enabled = true, backgroundType = "Image", backgroundUri = "file:///bg.jpg")
      )
    )
    assertTrue(
      ChromaKeyResolver.replacesBackground(
        ChromaKeySettings(enabled = true, backgroundType = "Video", backgroundUri = "file:///bg.mp4")
      )
    )
    assertFalse(ChromaKeyResolver.replacesBackground(ChromaKeySettings(enabled = true, backgroundType = "Transparent")))
    // A disabled key is not a background either.
    assertFalse(
      ChromaKeyResolver.replacesBackground(
        ChromaKeySettings(enabled = false, backgroundType = "SolidColor")
      )
    )
    assertFalse(ChromaKeyResolver.replacesBackground(null))
  }

  @Test
  fun keyPresetsCoverGreenAndBlueScreens() {
    val labels = ChromaKeyResolver.KEY_PRESETS.map { it.first }
    assertTrue(labels.contains("Green Screen"))
    assertTrue(labels.contains("Blue Screen"))
    assertTrue(ChromaKeyResolver.KEY_PRESETS.any { it.second == 0xFF00B140L })
    assertTrue(ChromaKeyResolver.KEY_PRESETS.any { it.second == 0xFF0047BBL })
  }
}
