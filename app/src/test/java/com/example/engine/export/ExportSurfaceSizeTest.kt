package com.example.engine.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportSurfaceSizeTest {
  @Test fun `1080p landscape and portrait are vendor aligned`() {
    assertEquals(1920 to 1088, ExportSurfaceSize.align(1920, 1080))
    assertEquals(1088 to 1920, ExportSurfaceSize.align(1080, 1920))
  }

  @Test fun `aligned resolutions stay unchanged`() {
    for (size in listOf(1280 to 720, 2560 to 1440, 3840 to 2160, 2048 to 2048)) {
      assertEquals(size, ExportSurfaceSize.align(size.first, size.second))
    }
  }

  @Test fun `custom sizes round up by at most fifteen pixels and are idempotent`() {
    for (width in 1..4096) {
      val height = 4097 - width
      val aligned = ExportSurfaceSize.align(width, height)
      assertTrue(aligned.first % 16 == 0 && aligned.second % 16 == 0)
      assertTrue(aligned.first in width..width + 15)
      assertTrue(aligned.second in height..height + 15)
      assertEquals(aligned, ExportSurfaceSize.align(aligned.first, aligned.second))
    }
  }

  @Test fun `invalid dimensions and overflow are rejected`() {
    for (width in listOf(0, -1, Int.MAX_VALUE)) {
      var rejected = false
      try { ExportSurfaceSize.align(width, 1080) }
      catch (_: IllegalArgumentException) { rejected = true }
      assertTrue("dimension $width must be rejected", rejected)
    }
  }
}
