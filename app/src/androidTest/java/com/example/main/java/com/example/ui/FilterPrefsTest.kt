package com.example.ui

import com.example.ui.components.filter.FilterPrefs
import org.junit.Assert.assertEquals
import org.junit.Test

class FilterPrefsTest {

  @Test
  fun encodeDecodeRoundTripsAndDropsBlanks() {
    val ids = listOf("CINEMATIC", "lut:teal_orange", "HDR")
    assertEquals(ids, FilterPrefs.decode(FilterPrefs.encode(ids)))
    assertEquals(emptyList<String>(), FilterPrefs.decode(null))
    assertEquals(listOf("A", "B"), FilterPrefs.decode("A,,B,"))
  }

  @Test
  fun toggledAddsThenRemoves() {
    val once = FilterPrefs.toggled(emptyList(), "WARM")
    assertEquals(listOf("WARM"), once)
    assertEquals(emptyList<String>(), FilterPrefs.toggled(once, "WARM"))
  }

  @Test
  fun recentMovesToFrontWithoutDuplicates() {
    val list = FilterPrefs.pushedRecent(listOf("A", "B", "C"), "B")
    assertEquals(listOf("B", "A", "C"), list)
  }

  @Test
  fun recentIsCapped() {
    var list = emptyList<String>()
    for (i in 1..20) list = FilterPrefs.pushedRecent(list, "id$i")
    assertEquals(FilterPrefs.MAX_RECENT, list.size)
    assertEquals("id20", list.first())
  }
}
