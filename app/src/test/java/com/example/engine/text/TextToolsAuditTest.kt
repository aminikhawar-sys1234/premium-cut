package com.example.engine.text

import com.example.domain.model.TextClip
import com.example.util.FontManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextToolsAuditTest {

  @Test
  fun systemFontsAreRealAndroidFamilies_notBrandedPlaceholders() {
    assertTrue(FontManager.SYSTEM_FONTS.isNotEmpty())
    val fakeBrands = listOf("montserrat", "playfair", "bebas", "jameel", "lorem", "dummy", "mock")
    FontManager.SYSTEM_FONTS.forEach { font ->
      val haystack = "${font.id} ${font.name}".lowercase()
      fakeBrands.forEach { brand ->
        assertFalse("System font must not use fake brand '$brand': $haystack", haystack.contains(brand))
      }
      assertTrue(
        "System font id must be a platform family: ${font.id}",
        font.id.startsWith("sans-serif") || font.id == "serif" || font.id == "monospace" ||
          font.id == "cursive" || font.id.endsWith("-cjk")
      )
    }
  }

  @Test
  fun importedCategoryIsSeparateFromSystemFamilies() {
    assertTrue(FontManager.FONT_CATEGORIES.contains("Imported"))
    assertTrue(FontManager.SYSTEM_FONTS.none { it.isCustom })
    assertTrue(FontManager.SYSTEM_FONTS.none { it.category == "Imported" })
  }

  @Test
  fun sanitizeFontFileNameStripsPathsAndRejectsNonFonts() {
    assertEquals("Bold.ttf", FontManager.sanitizeFontFileName("/tmp/../Bold.ttf"))
    assertEquals("My_Font.otf", FontManager.sanitizeFontFileName("My Font.otf"))
    assertTrue(FontManager.isFontFileName("NotoSans.ttf"))
    assertTrue(FontManager.isFontFileName("Display.otf"))
    assertFalse(FontManager.isFontFileName("notes.txt"))
    assertFalse(FontManager.isFontFileName("image.png"))
  }

  @Test
  fun textClipDefaultsAreNeutral_noDummyCopyOrForcedMotion() {
    val clip = TextClip()
    assertEquals("", clip.text)
    assertEquals("None", clip.animationType)
    assertEquals("None", clip.animationIn)
    assertEquals("None", clip.animationOut)
    assertEquals("None", clip.subtitleStyle)
    assertEquals("None", clip.effectStyle)
  }

  @Test
  fun entranceFallsBackToAnimationInWhenTypeIsNone() {
    assertEquals("Pop", TextMotionCatalog.resolveEntrance("None", "Pop"))
    assertEquals("Fade", TextMotionCatalog.resolveEntrance("Fade", "Pop"))
    assertEquals("None", TextMotionCatalog.resolveEntrance("None", "None"))
  }

  @Test
  fun fadeUsesOpacityAsMultiplier_notSquaredWhenComplete() {
    val clip = TextClip(
      text = "Hello",
      animationType = "Fade",
      animDurationMs = 1000L,
      durationMs = 4000L,
      opacity = 0.5f,
      scale = 1.2f
    )
    val start = TextLayerRenderer.evaluateAnimation(clip, 0L)
    assertTrue(start.opacity <= 0.2f)

    val done = TextLayerRenderer.evaluateAnimation(clip, 1000L)
    assertEquals(0.5f, done.opacity, 0.02f)
    assertEquals(1.2f, done.scale, 0.02f)
  }

  @Test
  fun animationInPlaysWhenAnimationTypeIsNone() {
    val clip = TextClip(
      text = "Title",
      animationType = "None",
      animationIn = "Fade",
      animDurationMs = 1000L,
      durationMs = 4000L
    )
    val start = TextLayerRenderer.evaluateAnimation(clip, 0L)
    assertTrue("animationIn Fade must start below full opacity", start.opacity < 0.4f)
    val mid = TextLayerRenderer.evaluateAnimation(clip, 500L)
    assertTrue(mid.opacity > start.opacity)
  }

  @Test
  fun animationOutFadesNearClipEnd() {
    val clip = TextClip(
      text = "Out",
      animationType = "None",
      animationOut = "Fade Out",
      animDurationMs = 1000L,
      durationMs = 3000L,
      opacity = 1f
    )
    val before = TextLayerRenderer.evaluateAnimation(clip, 1500L)
    assertEquals(1f, before.opacity, 0.02f)
    val during = TextLayerRenderer.evaluateAnimation(clip, 2500L)
    assertTrue(during.opacity < 0.7f)
    val end = TextLayerRenderer.evaluateAnimation(clip, 3000L)
    assertEquals(0f, end.opacity, 0.02f)
  }
}
