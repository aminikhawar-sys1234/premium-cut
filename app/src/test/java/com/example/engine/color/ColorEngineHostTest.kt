package com.example.engine.color

import com.ahstudio.color.core.ColorState
import com.ahstudio.color.lut.LutFailureReason
import com.ahstudio.color.lut.LutImportResult
import com.ahstudio.color.lut.LutRepository
import com.example.domain.model.VideoClip
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ColorEngineHostTest {
  private val emptyRepo = object : LutRepository {
    override suspend fun load(lutId: String) = null
    override fun import(source: InputStream, originalName: String): LutImportResult =
      LutImportResult.Failure(LutFailureReason.UNSUPPORTED)
    override fun remove(lutId: String) {}
    override fun listIds(): List<String> = emptyList()
  }

  @Before
  fun setUp() { ColorEngineHost.initWithRepository(emptyRepo) }

  private fun clip(id: String, grade: ColorState?) =
    VideoClip(id = id, name = id, colorGradeJson = grade?.let { ColorEngineHost.encode(it) })

  @Test
  fun syncAppliesGradeFromTimelineJson() {
    ColorEngineHost.syncFromTimeline(listOf(clip("sync_a", ColorState(exposure = 0.5f))))
    assertTrue(ColorEngineHost.hasGrade("sync_a"))
    assertEquals(0.5f, ColorEngineHost.gradeOf("sync_a").exposure, 1e-6f)
  }

  @Test
  fun syncClearsGradeWhenJsonRemoved_asUndoWould() {
    ColorEngineHost.syncFromTimeline(listOf(clip("sync_b", ColorState(saturation = 0.3f))))
    assertTrue(ColorEngineHost.hasGrade("sync_b"))
    ColorEngineHost.syncFromTimeline(listOf(clip("sync_b", null)))
    assertFalse(ColorEngineHost.hasGrade("sync_b"))
  }

  @Test
  fun syncDropsGradeOfClipsRemovedFromTimeline() {
    ColorEngineHost.syncFromTimeline(listOf(clip("sync_c", ColorState(contrast = 0.2f))))
    ColorEngineHost.syncFromTimeline(emptyList())
    assertFalse(ColorEngineHost.hasGrade("sync_c"))
  }

  @Test
  fun gradesAreIsolatedPerClip() {
    ColorEngineHost.syncFromTimeline(listOf(clip("sync_d1", ColorState(exposure = 1f)), clip("sync_d2", null)))
    assertTrue(ColorEngineHost.hasGrade("sync_d1"))
    assertFalse(ColorEngineHost.hasGrade("sync_d2"))
  }

  @Test
  fun bypassIsRenderOnlyAndDoesNotTouchGrade() {
    ColorEngineHost.syncFromTimeline(listOf(clip("sync_e", ColorState(exposure = 0.4f))))
    ColorEngineHost.setBypass("sync_e", true)
    assertTrue(ColorEngineHost.isBypassed("sync_e"))
    assertTrue(ColorEngineHost.hasGrade("sync_e"))
    ColorEngineHost.clearAllBypass()
    assertFalse(ColorEngineHost.isBypassed("sync_e"))
  }

  @Test
  fun corruptJsonFallsBackToNeutralGradeInsteadOfCrashing() {
    assertTrue(ColorEngineHost.decodeOrDefault("{not json").isIdentity())
    assertTrue(ColorEngineHost.decodeOrDefault(null).isIdentity())
  }
}
