package com.example.ui.components.filter

import com.example.domain.model.FilterSettings
import com.example.domain.model.FilterType
import com.example.domain.model.VideoAdjustments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Filters / Adjust / Video Quality sheet must always be able to answer the question
 * "what is applied right now, and where?". [appliedLookEntries] is the single source for
 * that answer: the panel's status bar and the on-preview HUD both render it.
 */
class LiveLookStatusBarTest {

  private fun entries(
    adjustments: VideoAdjustments = VideoAdjustments(),
    filter: FilterSettings = FilterSettings(),
    lutName: String? = null,
    onAdjustmentsChange: (VideoAdjustments) -> Unit = {},
    onFilterChange: (FilterSettings) -> Unit = {},
    onClearLut: () -> Unit = {}
  ) = appliedLookEntries(adjustments, filter, lutName, onAdjustmentsChange, onFilterChange, onClearLut)

  @Test
  fun aDefaultLookProducesNoEntries() {
    assertTrue(entries().isEmpty())
  }

  @Test
  fun everyNonDefaultValueIsListed() {
    val found = entries(
      adjustments = VideoAdjustments(brightness = 0.25f, autoEnhance = 0.4f),
      filter = FilterSettings(type = FilterType.GOLDEN_AUTUMN, intensity = 0.8f),
      lutName = "Teal & Orange"
    )
    assertEquals(setOf("filter", "lut", "brightness", "autoEnhance"), found.map { it.key }.toSet())
    assertEquals(4, found.size)
    assertEquals("80%", found.first { it.key == "filter" }.value)
    assertEquals("+25", found.first { it.key == "brightness" }.value)
    assertEquals("40%", found.first { it.key == "autoEnhance" }.value)
  }

  @Test
  fun clearingAnEntryResetsOnlyThatParameter() {
    var adj = VideoAdjustments(brightness = 0.25f, contrast = 1.4f)
    val found = entries(adjustments = adj, onAdjustmentsChange = { adj = it })
    found.first { it.key == "brightness" }.onClear()
    assertEquals(0f, adj.brightness, 0.0001f)
    // Untouched: clearing one chip must never wipe the rest of the look.
    assertEquals(1.4f, adj.contrast, 0.0001f)
  }

  @Test
  fun clearingTheFilterEntryRemovesThePreset() {
    var filter = FilterSettings(type = FilterType.HDR, intensity = 0.6f)
    val found = entries(filter = filter, onFilterChange = { filter = it })
    found.first { it.key == "filter" }.onClear()
    assertEquals(FilterType.NONE, filter.type)
  }

  @Test
  fun videoQualityValuesAreListedToo() {
    val found = entries(
      adjustments = VideoAdjustments(superClarity = 0.5f, denoise = 0.3f, antiFlicker = 0.2f)
    )
    assertEquals(setOf("superClarity", "denoise", "antiFlicker"), found.map { it.key }.toSet())
    assertEquals("50%", found.first { it.key == "superClarity" }.value)
  }
}
