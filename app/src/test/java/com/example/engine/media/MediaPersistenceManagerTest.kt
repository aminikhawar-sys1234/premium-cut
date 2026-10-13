package com.example.engine.media

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.domain.model.Timeline
import com.example.domain.model.VideoClip
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaPersistenceManagerTest {

  private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

  @Test
  fun persistMediaReturnsPlayableFileUriAndReusesHashCopy() = runBlocking {
    val source = File(context.cacheDir, "import_source.mp4").apply { writeText("imported-bytes-123") }
    val first = MediaPersistenceManager.persistMedia(context, source.absolutePath, "import_source.mp4")
    assertTrue(first.startsWith("file:"))
    val persistedFile = File(Uri.parse(first).path!!)
    assertTrue(persistedFile.exists())
    assertTrue(persistedFile.path.contains("project_media"))
    assertEquals("imported-bytes-123", persistedFile.readText())

    val second = MediaPersistenceManager.persistMedia(context, source.absolutePath, "import_source.mp4")
    assertEquals(first, second)
  }

  @Test
  fun persistTimelineMediaRewritesClipsToInternalStore() = runBlocking {
    val source = File(context.cacheDir, "timeline_clip.mp4").apply { writeText("clip-bytes") }
    val timeline = Timeline(
      videoClips = listOf(
        VideoClip(id = "v1", uri = source.absolutePath, name = "Clip", durationMs = 2000L)
      )
    )
    val persisted = MediaPersistenceManager.persistTimelineMedia(context, timeline)
    val uri = persisted.videoClips.single().uri
    assertTrue(uri.startsWith("file:"))
    assertTrue(MediaRelinkManager.isRealPlayableMedia(context, uri))
    assertTrue(File(Uri.parse(uri).path!!).path.contains("project_media"))
  }
}
