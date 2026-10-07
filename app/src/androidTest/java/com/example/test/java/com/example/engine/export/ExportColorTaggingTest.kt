package com.example.engine.export

import android.media.MediaFormat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExportColorTaggingTest {
  @Test
  fun tagsSdrRec709LimitedRange() {
    val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720)
    assertFalse(ExportColorTagging.isTaggedSdrRec709(f))
    ExportColorTagging.applySdrRec709(f)
    assertTrue(ExportColorTagging.isTaggedSdrRec709(f))
  }
}
