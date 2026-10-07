package com.example.data

import com.example.data.local.TimelineSerializer
import com.example.domain.model.TextClip
import com.example.domain.model.Timeline
import com.example.domain.model.TimelineSpeaker
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Speaker tags on captions and the speaker list must survive a project save / reload. */
class TimelineSpeakerPersistenceTest {

  private fun sample() = Timeline(
    textClips = listOf(
      TextClip(id = "c1", text = "Ali: hello", speakerId = "spk_1", textColor = 0xFF4FC3F7),
      TextClip(id = "c2", text = "hi there", speakerId = "spk_2", textColor = 0xFFFFB74D),
      TextClip(id = "c3", text = "plain caption"),
    ),
    captionSpeakers = listOf(
      TimelineSpeaker("spk_1", "Ali", 0xFF4FC3F7),
      TimelineSpeaker("spk_2", "Speaker 2", 0xFFFFB74D),
    ),
  )

  @Test
  fun `speaker ids and speaker list round-trip through the serializer`() {
    val json = TimelineSerializer.serializeTimeline(sample())
    val back = TimelineSerializer.fromJsonOrNull(json)!!
    assertEquals(listOf("spk_1", "spk_2", null), back.textClips.map { it.speakerId })
    assertEquals(sample().captionSpeakers, back.captionSpeakers)
    assertEquals("Ali: hello", back.textClips[0].text)
  }

  @Test
  fun `projects saved before speaker support still load with defaults`() {
    val obj = JSONObject(TimelineSerializer.serializeTimeline(sample()))
    obj.remove("captionSpeakers")
    val clips = obj.getJSONArray("textClips")
    for (i in 0 until clips.length()) clips.getJSONObject(i).remove("speakerId")
    val back = TimelineSerializer.fromJsonOrNull(obj.toString())!!
    assertTrue(back.captionSpeakers.isEmpty())
    assertTrue(back.textClips.all { it.speakerId == null })
    assertEquals(3, back.textClips.size)
  }
}
