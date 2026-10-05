package com.example.engine.color

import com.ahstudio.color.core.ColorState
import com.ahstudio.color.preset.ColorProperties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ColorGradePanelPathsTest {
  private val panelPaths = listOf(
    "exposure", "contrast", "highlights", "shadows", "whites", "blacks",
    "temperature", "tint", "saturation", "vibrance",
    "splitTone.shadowHue", "splitTone.shadowSat", "splitTone.highlightHue",
    "splitTone.highlightSat", "splitTone.balance", "splitTone.strength"
  )

  @Test
  fun everyPanelSliderMapsToAnEngineProperty() {
    panelPaths.forEach { assertNotNull("missing property $it", ColorProperties.ALL[it]) }
  }

  @Test
  fun setThenGetRoundTripsAndDefaultsAreInRange() {
    panelPaths.forEach { path ->
      val p = ColorProperties.ALL.getValue(path)
      val neutral = p.get(ColorState())
      assertEquals(neutral, neutral.coerceIn(p.min, p.max), 0f)
      val mid = (p.min + p.max) / 2f
      assertEquals(mid, p.get(p.set(ColorState(), mid)), 1e-6f)
    }
  }
}
