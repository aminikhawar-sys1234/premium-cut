package com.example.engine.composition

import com.example.domain.model.ChromaKeySettings
import com.example.domain.model.Timeline
import com.example.domain.model.VideoClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executable contract of the chroma keyer: the algorithm the GPU shader ports line for line.
 *
 * A green screen must be removed, the subject must keep its colours, blue screens must work exactly like
 * green ones, the sliders must change the result in the documented direction, and spill must be removed
 * without touching the subject's alpha.
 */
class ChromaKeyMathTest {

  private val greenScreen = argb(0x00, 0xB1, 0x40)
  private val blueScreen = argb(0x00, 0x47, 0xBB)
  private val skin = argb(0xD8, 0xA8, 0x8C)

  private val green = ChromaKeySettings(
    enabled = true,
    targetColor = 0xFF00B140,
    similarity = 0.4f,
    smoothness = 0.15f,
    spillSuppression = 0.5f
  )

  private val blue = ChromaKeySettings(
    enabled = true,
    targetColor = 0xFF0047BB,
    similarity = 0.4f,
    smoothness = 0.15f,
    spillSuppression = 0.6f
  )

  // --- keying ---------------------------------------------------------------------------------

  @Test
  fun greenScreenIsRemoved() {
    assertEquals(0, alpha(ChromaKeyMath.keyPixel(greenScreen, green)))
  }

  @Test
  fun subjectKeepsFullAlphaAndColour() {
    val keyed = ChromaKeyMath.keyPixel(skin, green)
    assertEquals(255, alpha(keyed))
    assertTrue(kotlin.math.abs(red(keyed) - red(skin)) <= 2)
    assertTrue(kotlin.math.abs(greenChannel(keyed) - greenChannel(skin)) <= 2)
    assertTrue(kotlin.math.abs(blueChannel(keyed) - blueChannel(skin)) <= 2)
  }

  @Test
  fun litAndShadowedScreenBothKey() {
    // A real screen is never one flat colour: chroma-space keying must catch both ends.
    assertTrue(alpha(ChromaKeyMath.keyPixel(argb(0x40, 0xE0, 0x60), green)) < 40)
    assertTrue(alpha(ChromaKeyMath.keyPixel(argb(0x00, 0x7A, 0x30), green)) < 40)
  }

  @Test
  fun darkNeutralObjectSurvives() {
    assertEquals(255, alpha(ChromaKeyMath.keyPixel(argb(0x38, 0x38, 0x3C), green)))
  }

  @Test
  fun blueScreenKeysAndGreenIsLeftAlone() {
    assertTrue(alpha(ChromaKeyMath.keyPixel(blueScreen, blue)) < 40)
    assertEquals(255, alpha(ChromaKeyMath.keyPixel(skin, blue)))
    assertTrue(alpha(ChromaKeyMath.keyPixel(greenScreen, blue)) > 200)
  }

  @Test
  fun blackAndWhiteKeysUseTheLumaTerm() {
    val blackKey = ChromaKeySettings(enabled = true, targetColor = 0xFF000000, similarity = 0.25f, smoothness = 0.1f)
    val whiteKey = ChromaKeySettings(enabled = true, targetColor = 0xFFFFFFFF, similarity = 0.25f, smoothness = 0.1f)
    assertTrue(alpha(ChromaKeyMath.keyPixel(argb(0, 0, 0), blackKey)) < 40)
    assertTrue(alpha(ChromaKeyMath.keyPixel(argb(128, 128, 128), blackKey)) > 200)
    assertTrue(alpha(ChromaKeyMath.keyPixel(argb(255, 255, 255), whiteKey)) < 40)
    assertTrue(alpha(ChromaKeyMath.keyPixel(argb(128, 128, 128), whiteKey)) > 200)
  }

  @Test
  fun alphaIsMonotonicInChromaDistanceAndTheRampIsReal() {
    val key = ChromaKeyMath.keyChromaOf(green)
    val samples = (0..120).map { i ->
      val t = i / 120f
      val px = argb(
        (red(skin) * t + red(greenScreen) * (1 - t)).toInt(),
        (greenChannel(skin) * t + greenChannel(greenScreen) * (1 - t)).toInt(),
        (blueChannel(skin) * t + blueChannel(greenScreen) * (1 - t)).toInt()
      )
      val chroma = ChromaKeyMath.chromaOfPixel(px)
      val d = kotlin.math.hypot((chroma[0] - key[0]).toDouble(), (chroma[1] - key[1]).toDouble()).toFloat()
      d to ChromaKeyMath.alphaFor(chroma, key, 0.4f, 0.15f)
    }.sortedBy { it.first }
    for (i in 1 until samples.size) {
      assertTrue("alpha must not fall as distance grows", samples[i].second >= samples[i - 1].second - 1e-4f)
    }
    assertTrue("a soft edge must exist", samples.any { it.second > 0.02f && it.second < 0.98f })
  }

  // --- sliders --------------------------------------------------------------------------------

  @Test
  fun similarityRemovesMoreAndSoftnessWidensTheEdge() {
    val edge = argb(0x40, 0xC0, 0x50)
    val tight = ChromaKeyMath.keyPixel(edge, green.copy(similarity = 0.05f, smoothness = 0.02f))
    val loose = ChromaKeyMath.keyPixel(edge, green.copy(similarity = 0.9f, smoothness = 0.02f))
    assertTrue("raising similarity must remove more", alpha(loose) < alpha(tight))

    fun partialAlphas(softness: Float): Int = (0..200).count { i ->
      val t = i / 200f
      val px = argb(
        (red(greenScreen) * t + red(skin) * (1 - t)).toInt(),
        (greenChannel(greenScreen) * t + greenChannel(skin) * (1 - t)).toInt(),
        (blueChannel(greenScreen) * t + blueChannel(skin) * (1 - t)).toInt()
      )
      val a = ChromaKeyMath.alphaFor(ChromaKeyMath.chromaOfPixel(px), ChromaKeyMath.keyChromaOf(green), 0.3f, softness)
      a > 0.02f && a < 0.98f
    }
    assertTrue("edge softness must widen the ramp", partialAlphas(0.6f) > partialAlphas(0.02f))
  }

  @Test
  fun spillSuppressionTargetsTheKeyColourDirection() {
    val greenishSkin = argb(0xB0, 0xC8, 0x6E)
    val noSpill = ChromaKeyMath.keyPixel(greenishSkin, green.copy(spillSuppression = 0f))
    val spilled = ChromaKeyMath.keyPixel(greenishSkin, green.copy(spillSuppression = 1f))
    assertTrue("green spill must come down", greenChannel(spilled) < greenChannel(noSpill))
    assertEquals("spill suppression must not remove the subject", 255, alpha(spilled))

    val blueishSkin = argb(0xAA, 0xA0, 0xC8)
    val blueNoSpill = ChromaKeyMath.keyPixel(blueishSkin, blue.copy(spillSuppression = 0f))
    val blueSpilled = ChromaKeyMath.keyPixel(blueishSkin, blue.copy(spillSuppression = 1f))
    assertTrue("blue spill must come down for blue keys too", blueChannel(blueSpilled) < blueChannel(blueNoSpill))
    assertEquals(255, alpha(blueSpilled))
  }

  @Test
  fun foregroundOpacityScalesTheSubjectOnly() {
    val half = ChromaKeyMath.keyPixel(skin, green.copy(foregroundOpacity = 0.5f))
    assertTrue(alpha(half) in 126..129)
    assertEquals(0, alpha(ChromaKeyMath.keyPixel(greenScreen, green.copy(foregroundOpacity = 0.5f))))
  }

  // --- background compositing (CPU reference used by the still-frame path) ---------------------

  @Test
  fun solidBackgroundReplacesKeyedPixelsAndKeepsTheSubject() {
    val pixels = intArrayOf(greenScreen, skin, argb(0x20, 0x30, 0x40))
    val white = ChromaKeyMath.apply(pixels, green, background = 0xFFFFFFFF.toInt())
    assertEquals(0xFFFFFFFF.toInt(), white[0])
    assertEquals(skin, white[1])
  }

  @Test
  fun transparentModeKeepsAlphaZeroInTheKeyAndOpaqueOnTheSubject() {
    val pixels = intArrayOf(greenScreen, skin)
    val out = ChromaKeyMath.apply(pixels, green, background = null)
    assertEquals(0, alpha(out[0]))
    assertEquals(255, alpha(out[1]))
  }

  @Test
  fun disabledSettingsAreAPassThrough() {
    val pixels = intArrayOf(greenScreen, skin)
    assertTrue(ChromaKeyMath.apply(pixels, green.copy(enabled = false)) === pixels)
  }

  // --- where settings apply -------------------------------------------------------------------

  @Test
  fun clipOwnSettingWinsOverTheProjectDefault() {
    val clipDefault = VideoClip(id = "a", uri = "u", name = "a")
    val clipDisabled = VideoClip(id = "b", uri = "u", name = "b", chromaKey = ChromaKeySettings(enabled = false))
    val clipBlue = VideoClip(
      id = "c",
      uri = "u",
      name = "c",
      chromaKey = ChromaKeySettings(enabled = true, targetColor = 0xFF0047BB)
    )
    val project = ChromaKeySettings(enabled = true, targetColor = 0xFF00FF00)

    assertEquals(0xFF00FF00, ChromaKeyResolver.settingsFor(clipDefault, project).targetColor)
    assertNull("an explicitly disabled clip must not be keyed by the project default", ChromaKeyResolver.activeSettingsFor(clipDisabled, project))
    assertEquals(0xFF0047BB, ChromaKeyResolver.settingsFor(clipBlue, project).targetColor)
    assertTrue(ChromaKeyResolver.activeSettingsFor(clipBlue, project)!!.enabled)
  }

  @Test
  fun requiresCompositionFollowsTheClipsAndProjectDefault() {
    val plain = VideoClip(id = "a", uri = "u", name = "a")
    assertFalse(ChromaKeyResolver.requiresComposition(Timeline(videoClips = listOf(plain))))
    assertTrue(
      ChromaKeyResolver.requiresComposition(
        Timeline(videoClips = listOf(plain.copy(chromaKey = ChromaKeySettings(enabled = true))))
      )
    )
    assertTrue(
      ChromaKeyResolver.requiresComposition(
        Timeline(videoClips = listOf(plain), chromaKey = ChromaKeySettings(enabled = true))
      )
    )
    assertFalse(
      "a clip with its own disabled setting must not force the compositor on",
      ChromaKeyResolver.requiresComposition(
        Timeline(
          videoClips = listOf(plain.copy(chromaKey = ChromaKeySettings(enabled = false))),
          chromaKey = ChromaKeySettings(enabled = true)
        )
      )
    )
    assertTrue(
      "background removal needs the compositor",
      ChromaKeyResolver.requiresComposition(Timeline(videoClips = listOf(plain.copy(isBackgroundRemoved = true))))
    )
    assertTrue(
      "overlay background removal needs the compositor",
      ChromaKeyResolver.requiresComposition(Timeline(overlayClips = listOf(plain.copy(isBackgroundRemoved = true))))
    )
  }

  @Test
  fun backgroundMediaResolutionAndLooping() {
    val image = ChromaKeySettings(enabled = true, backgroundType = "Image", backgroundUri = "file:///bg.jpg")
    val video = ChromaKeySettings(enabled = true, backgroundType = "Video", backgroundUri = "file:///bg.mp4")
    assertTrue(ChromaKeyResolver.usesBackgroundMedia(image))
    val clip = ChromaKeyResolver.backgroundClip(image)
    assertNotNull(clip)
    assertEquals(ChromaKeyResolver.BACKGROUND_CLIP_ID, clip!!.id)
    assertFalse(clip.isVideo)
    assertTrue(ChromaKeyResolver.backgroundClip(video)!!.isVideo)
    assertNull(ChromaKeyResolver.backgroundClip(ChromaKeySettings(enabled = true, backgroundType = "SolidColor")))
    assertEquals(5_000L, ChromaKeyResolver.backgroundSourceMs(video, 125_000L))
    assertEquals(2, ChromaKeyResolver.backgroundModeId(image))
    assertEquals(1, ChromaKeyResolver.backgroundModeId(ChromaKeySettings(enabled = true, backgroundType = "SolidColor")))
    assertEquals(0, ChromaKeyResolver.backgroundModeId(ChromaKeySettings(enabled = true, backgroundType = "Transparent")))
  }

  @Test
  fun imageBackgroundUrisCoverProjectDefaultAndEveryClip() {
    val clip = VideoClip(
      id = "a",
      uri = "u",
      name = "a",
      chromaKey = ChromaKeySettings(enabled = true, backgroundType = "Image", backgroundUri = "file:///clip.jpg")
    )
    val timeline = Timeline(
      videoClips = listOf(clip),
      chromaKey = ChromaKeySettings(enabled = true, backgroundType = "Image", backgroundUri = "file:///project.jpg")
    )
    assertEquals(
      setOf("file:///clip.jpg", "file:///project.jpg"),
      ChromaKeyResolver.imageBackgroundUris(timeline).toSet()
    )
    assertEquals(emptyList<String>(), ChromaKeyResolver.imageBackgroundUris(Timeline()))
  }

  @Test
  fun coverFitCropsTheLongerSideAndLeavesUnknownSizesAlone() {
    val wide = ChromaKeyResolver.coverFit(1920, 1080, 1080, 1920)
    assertTrue(wide[0] < 1f && wide[1] == 1f)
    val tall = ChromaKeyResolver.coverFit(1080, 1920, 1920, 1080)
    assertTrue(tall[0] == 1f && tall[1] < 1f)
    val same = ChromaKeyResolver.coverFit(1920, 1080, 1920, 1080)
    assertEquals(1f, same[0], 1e-6f)
    assertEquals(1f, same[1], 1e-6f)
    val unknown = ChromaKeyResolver.coverFit(0, 0, 1080, 1920)
    assertEquals(1f, unknown[0], 1e-6f)
    assertEquals(1f, unknown[1], 1e-6f)
  }

  @Test
  fun presetMatchingNamesThePickedColour() {
    assertEquals("Pure Green", ChromaKeyMath.nearestPreset(argb(0, 255, 0), ChromaKeyResolver.KEY_PRESETS))
    assertEquals("Blue Screen", ChromaKeyMath.nearestPreset(argb(0x00, 0x47, 0xBB), ChromaKeyResolver.KEY_PRESETS))
    assertNull(ChromaKeyMath.nearestPreset(argb(90, 40, 200), ChromaKeyResolver.KEY_PRESETS, 0.02f))
  }

  // --- helpers --------------------------------------------------------------------------------

  private fun argb(r: Int, g: Int, b: Int, a: Int = 255) = (a shl 24) or (r shl 16) or (g shl 8) or b
  private fun alpha(p: Int) = (p ushr 24) and 0xFF
  private fun red(p: Int) = (p ushr 16) and 0xFF
  private fun greenChannel(p: Int) = (p ushr 8) and 0xFF
  private fun blueChannel(p: Int) = p and 0xFF
}
