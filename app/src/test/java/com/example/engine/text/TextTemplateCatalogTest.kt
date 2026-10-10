package com.example.engine.text

import androidx.test.core.app.ApplicationProvider
import com.example.domain.model.TextClip
import com.example.engine.text.registry.TextAssetRegistry
import com.example.engine.text.registry.TextTemplateCatalog
import com.ute.templates.TemplateEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextTemplateCatalogTest {

  @Before
  fun setUp() {
    TextAssetRegistry.clearAll()
    TextTemplateCatalog.reload(ApplicationProvider.getApplicationContext())
  }

  @Test
  fun catalogLoadsUniqueReal3dAnd2dTemplatesFromAssets() {
    val templates = TextAssetRegistry.getTemplates()
    val threeD = templates.filter { it.templateClip.is3D }
    val twoD = templates.filter { !it.templateClip.is3D }
    assertTrue("Packaged 3D templates must load from assets", threeD.size >= 16)
    assertTrue("Packaged 2D live-animate templates must load from assets", twoD.size >= 10)

    val ids = templates.map { it.id }
    assertEquals("Duplicate template ids are not allowed", ids.size, ids.toSet().size)

    templates.forEach { template ->
      assertTrue(template.id.isNotBlank())
      assertTrue(template.name.isNotBlank())
      assertFalse(template.name.contains("dummy", ignoreCase = true))
      assertFalse(template.name.contains("fake", ignoreCase = true))
      assertFalse(template.name.contains("placeholder", ignoreCase = true))
      assertFalse(template.name.matches(Regex("(?i)template\\s*\\d+")))
      assertTrue(template.templateClip.text.isNotBlank())
      assertTrue(
        "${template.id} preview text must be large enough to read",
        template.templateClip.fontSizeSp >= 36f
      )
    }

    threeD.forEach { template ->
      assertTrue("${template.id} must be a 3D text style", template.templateClip.is3D)
      assertTrue(template.templateClip.depth3D > 0f)
      assertTrue(template.templateClip.material3D.isNotBlank())
    }

    val liveKeys = twoD.map { it.templateClip.animationIn.trim().lowercase() }.toSet()
    assertEquals("Each 2D template should use a distinct live animation", twoD.size, liveKeys.size)
    twoD.forEach { template ->
      assertFalse("${template.id} must stay 2D", template.templateClip.is3D)
      assertEquals(0f, template.templateClip.depth3D, 0.01f)
      assertFalse(TextMotionCatalog.isNone(template.templateClip.animationIn))
      assertTrue(template.templateClip.animDurationMs >= 1200L)
    }
  }

  @Test
  fun templatePreviewFillsSmallThumbnail() {
    val clip = TextClip(text = "Aa", fontSizeSp = 28f, is3D = true)
    val previewSp = TextLayerRenderer.previewFillFontSizeSp(80, 80, clip)
    assertTrue("Live-animate thumbnail text must be larger than the canvas title size", previewSp > clip.fontSizeSp * 2f)
  }

  @Test
  fun registerTemplateSkipsDuplicateIds() {
    val first = TextAssetRegistry.getTemplates().first()
    val before = TextAssetRegistry.getTemplates().size
    TextAssetRegistry.registerTemplate(first.copy(name = "Duplicate Copy"))
    assertEquals(before, TextAssetRegistry.getTemplates().size)
  }

  @Test
  fun parseRejectsBlankOrNamelessEntries() {
    assertNull(TextTemplateCatalog.parseArray("""[{"id":"","name":"Chrome","text":"Aa"}]""").singleOrNull())
    assertTrue(TextTemplateCatalog.parseArray("""{"id":"x","name":"","text":"Aa"}""").isEmpty())
    assertTrue(TextTemplateCatalog.parseArray("""{"id":"x","name":"Chrome","text":"  "}""").isEmpty())
  }

  @Test
  fun parseColorAcceptsRgbAndArgb() {
    assertEquals(0xFFFF0000, TextTemplateCatalog.parseColor("#FF0000", 0L))
    assertEquals(0x80AABBCC, TextTemplateCatalog.parseColor("#80AABBCC", 0L))
    assertEquals(0xFFFFFFFF, TextTemplateCatalog.parseColor("nope", 0xFFFFFFFF))
  }

  @Test
  fun parseKeeps2dTemplatesFlatAndAnimated() {
    val parsed = TextTemplateCatalog.parseArray(
      """
      {
        "id": "2d.demo",
        "name": "Demo",
        "category": "2D",
        "text": "Aa",
        "fontSizeSp": 48,
        "is3D": false,
        "depth3D": 0,
        "animationIn": "Pop",
        "animDurationMs": 1600
      }
      """.trimIndent()
    ).single()
    assertFalse(parsed.templateClip.is3D)
    assertEquals(0f, parsed.templateClip.depth3D, 0.01f)
    assertEquals("Pop", parsed.templateClip.animationIn)
    assertEquals(1600L, parsed.templateClip.animDurationMs)
    assertEquals(48f, parsed.templateClip.fontSizeSp, 0.01f)
  }

  @Test
  fun applyStyleKeepsUserTextAndCopies3dFields() {
    val template = TextAssetRegistry.getTemplates().first { it.id == "3d.chrome" }
    val edited = TextClip(id = "live", text = "My Title", fontSizeSp = 18f)
    val applied = TextTemplateCatalog.applyStyle(edited, template.templateClip, keepText = true)
    assertEquals("My Title", applied.text)
    assertEquals(template.templateClip.material3D, applied.material3D)
    assertEquals(template.templateClip.animation3D, applied.animation3D)
    assertTrue(applied.is3D)
    assertEquals(template.templateClip.depth3D, applied.depth3D, 0.01f)

    val reset = TextTemplateCatalog.resetStyle(applied)
    assertEquals("My Title", reset.text)
    assertFalse(reset.is3D)
    assertEquals("None", reset.animation3D)
  }

  @Test
  fun previewRendererDrawsPackagedTemplate() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val clip = TextAssetRegistry.getTemplates().first { it.id == "3d.gold-metal" }.templateClip
    val bmp = TextLayerRenderer.renderToBitmap(clip, 400L, 160, 160, context)
    assertEquals(160, bmp.width)
    assertNotEquals(0, bmp.byteCount)
    bmp.recycle()
  }

  @Test
  fun uteParserReadsThreeDBlock() {
    val engine = TemplateEngine(ApplicationProvider.getApplicationContext())
    val parsed = engine.parse(
      """
      {
        "id": "ute.chrome",
        "category": "CINEMATIC",
        "name": "UTE Chrome",
        "defaultText": "Aa",
        "threeD": { "depth": 18, "bevel": 3, "material": "chrome", "tint": "#FFE8EEF4" }
      }
      """.trimIndent()
    )
    assertEquals("ute.chrome", parsed.id)
    assertNotNull(parsed.threeD)
    assertEquals("chrome", parsed.threeD?.materialId)
    assertEquals(18f, parsed.threeD?.extrusionDepthPx ?: 0f, 0.01f)
  }
}
