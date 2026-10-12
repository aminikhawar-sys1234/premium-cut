package com.example.engine.media

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.domain.model.AudioClip
import com.example.domain.model.Timeline
import com.example.domain.model.VideoClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaPlayableUriTest {

  private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

  @Test
  fun absolutePathBecomesFileUriWhenFileExists() {
    val file = File(context.filesDir, "clip_playable.mp4").apply { writeText("fake-video") }
    val stored = MediaPlayableUri.toStoredUri(file.absolutePath)
    assertTrue(stored.startsWith("file:"))
    assertEquals(file.absolutePath, Uri.parse(stored).path)

    val exo = MediaPlayableUri.toExoPlayerUri(file.absolutePath)
    assertEquals("file", exo.scheme)
    assertEquals(file.absolutePath, exo.path)
    assertTrue(MediaRelinkManager.isRealPlayableMedia(context, file.absolutePath))
    assertTrue(MediaRelinkManager.isRealPlayableMedia(context, stored))
  }

  @Test
  fun missingAbsolutePathIsNotPlayable() {
    val missing = File(context.filesDir, "does_not_exist.mp4").absolutePath
    assertFalse(MediaRelinkManager.isRealPlayableMedia(context, missing))
    assertEquals(missing, MediaPlayableUri.toStoredUri(missing))
  }

  @Test
  fun contentAndSyntheticUrisPassThrough() {
    assertEquals("content://media/picker/1", MediaPlayableUri.toStoredUri("content://media/picker/1"))
    assertEquals("stock://demo", MediaPlayableUri.resolvePlayableString(context, "stock://demo"))
    assertTrue(MediaPlayableUri.isSynthetic("asset://clips/a.mp4"))
    assertNull(MediaPlayableUri.resolveLocalFile("content://media/picker/1"))
  }

  @Test
  fun hydrateTimelineRewritesExistingLocalFiles() {
    val video = File(context.filesDir, "main.mp4").apply { writeText("video") }
    val audio = File(context.filesDir, "voice.m4a").apply { writeText("audio") }
    val timeline = Timeline(
      videoClips = listOf(
        VideoClip(id = "v1", uri = video.absolutePath, name = "Main", durationMs = 4000L)
      ),
      audioClips = listOf(
        AudioClip(id = "a1", uri = audio.absolutePath, title = "Voice", durationMs = 4000L)
      )
    )
    val hydrated = MediaPlayableUri.hydrateTimeline(context, timeline)
    assertTrue(hydrated.videoClips.single().uri.startsWith("file:"))
    assertTrue(hydrated.audioClips.single().uri.startsWith("file:"))
    assertTrue(MediaPlayableUri.hasUriChanges(timeline, hydrated))
    assertFalse(MediaPlayableUri.hasUriChanges(hydrated, hydrated))
    assertNotNull(MediaPlayableUri.resolveLocalFile(hydrated.videoClips.single().uri))
  }
}
