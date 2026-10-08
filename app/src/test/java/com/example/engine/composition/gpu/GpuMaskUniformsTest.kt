package com.example.engine.composition.gpu

import com.example.domain.model.MaskSettings
import com.example.domain.model.MaskShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuMaskUniformsTest {
  @Test
  fun disabledOrNoneMapsToShaderOff() {
    assertFalse(GpuMaskUniforms.isEnabled(null))
    assertFalse(GpuMaskUniforms.isEnabled(MaskSettings(enabled = false, shape = MaskShape.CIRCLE)))
    assertFalse(GpuMaskUniforms.isEnabled(MaskSettings(enabled = true, shape = MaskShape.NONE)))
    assertEquals(0, GpuMaskUniforms.shapeId(null))
  }

  @Test
  fun enabledShapesMatchShaderOrdinals() {
    assertEquals(1, GpuMaskUniforms.shapeId(MaskSettings(enabled = true, shape = MaskShape.RECTANGLE)))
    assertEquals(2, GpuMaskUniforms.shapeId(MaskSettings(enabled = true, shape = MaskShape.CIRCLE)))
    assertEquals(3, GpuMaskUniforms.shapeId(MaskSettings(enabled = true, shape = MaskShape.LINEAR)))
    assertEquals(4, GpuMaskUniforms.shapeId(MaskSettings(enabled = true, shape = MaskShape.MIRROR)))
    assertTrue(GpuMaskUniforms.isEnabled(MaskSettings(enabled = true, shape = MaskShape.STAR)))
  }
}
