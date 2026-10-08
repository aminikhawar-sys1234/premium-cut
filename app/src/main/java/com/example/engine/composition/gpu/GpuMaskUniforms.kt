package com.example.engine.composition.gpu

import com.example.domain.model.MaskSettings
import com.example.domain.model.MaskShape

/**
 * Maps [MaskSettings] onto the compositor fragment-shader uniforms
 * (`uMaskEnabled`, `uMaskShape`, …). Preview and export share this mapping so a
 * mask applied in the tool panel is the same mask encoded into the file.
 */
object GpuMaskUniforms {
  fun isEnabled(mask: MaskSettings?): Boolean =
    mask != null && mask.enabled && mask.shape != MaskShape.NONE

  /** Shader `uMaskShape`: 0=None, 1=Rectangle, 2=Circle, 3=Linear, 4=Mirror, 5=Star, 6=Heart. */
  fun shapeId(mask: MaskSettings?): Int {
    if (!isEnabled(mask)) return 0
    return mask!!.shape.ordinal
  }
}
