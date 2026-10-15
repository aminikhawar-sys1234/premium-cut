package com.example.engine.export

import android.media.MediaMuxer
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MuxerFinalizationTest {
  @Test fun `never started muxer stays unsuccessful on repeated release`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val output = File.createTempFile("muxer-finalize", ".mp4", context.cacheDir)
    val muxer = MuxerCoordinator(
      MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4),
      hasAudio = true
    )
    try {
      assertFalse(muxer.stopAndRelease())
      assertTrue(muxer.isStopped)
      assertFalse(muxer.isStarted)
      assertFalse(muxer.stopAndRelease())
    } finally {
      muxer.stopAndRelease()
      output.delete()
    }
  }
}
