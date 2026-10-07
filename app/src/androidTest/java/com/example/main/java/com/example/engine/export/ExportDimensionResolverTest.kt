package com.example.engine.export

import com.example.domain.model.AspectRatio
import com.example.domain.model.Resolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportDimensionResolverTest {

  @Test
  fun uhd4kLandscapeIs3840x2160() {
    assertEquals(3840 to 2160, ExportDimensionResolver.resolve(Resolution.RES_4K, AspectRatio.RATIO_16_9))
  }

  @Test
  fun uhd4kPortraitIs2160x3840() {
    assertEquals(2160 to 3840, ExportDimensionResolver.resolve(Resolution.RES_VERTICAL_4K, AspectRatio.RATIO_9_16))
  }

  @Test
  fun uhd4kUltrawidePreservesAspectInsteadOfSquashing() {
    val (w, h) = ExportDimensionResolver.resolve(Resolution.RES_4K, AspectRatio.RATIO_21_9)
    assertTrue("width must not exceed 3840", w <= 3840)
    assertEquals(0, w % 2)
    assertEquals(0, h % 2)
    assertEquals(21.0 / 9.0, w.toDouble() / h, 0.05)
  }

  @Test
  fun fitEvenKeepsAspectWhenOverLimit() {
    val (w, h) = ExportDimensionResolver.fitEven(5040, 2160)
    assertEquals(3840, w)
    assertEquals(0, h % 2)
    assertEquals(5040.0 / 2160.0, w.toDouble() / h, 0.01)
  }

  @Test
  fun fitEvenLeavesSupportedSizesUntouched() {
    assertEquals(1920 to 1080, ExportDimensionResolver.fitEven(1920, 1080))
    assertEquals(3840 to 2160, ExportDimensionResolver.fitEven(3840, 2160))
  }

  @Test
  fun shortSideConventionForSquareAndFourThree() {
    assertEquals(720 to 720, ExportDimensionResolver.resolve(Resolution.RES_720P, AspectRatio.RATIO_1_1))
    assertEquals(960 to 720, ExportDimensionResolver.resolve(Resolution.RES_720P, AspectRatio.RATIO_4_3))
    assertEquals(1920 to 1080, ExportDimensionResolver.resolve(Resolution.RES_1080P, AspectRatio.RATIO_16_9))
  }

  @Test
  fun dialogAndExporterAgreeWithResolverForEveryAspect() {
    for (res in Resolution.values()) for (aspect in AspectRatio.values()) {
      val expected = ExportDimensionResolver.resolve(res, aspect)
      assertEquals("$res/$aspect", expected, com.example.ui.components.export.calculateExportDimensions(res, aspect))
    }
  }
}
