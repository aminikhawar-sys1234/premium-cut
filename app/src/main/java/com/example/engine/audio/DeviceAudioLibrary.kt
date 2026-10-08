package com.example.engine.audio

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.util.Log

data class DeviceAudioTrack(
  val id: Long,
  val title: String,
  val artist: String,
  val durationMs: Long,
  val uri: String,
  val mimeType: String
)

/**
 * Reads real device audio from MediaStore. Returns an empty list when the user
 * has granted no access or the library is empty — never invents tracks.
 */
object DeviceAudioLibrary {
  private const val TAG = "DeviceAudioLibrary"

  fun query(context: Context, limit: Int = 200): List<DeviceAudioTrack> {
    val tracks = ArrayList<DeviceAudioTrack>()
    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(
      MediaStore.Audio.Media._ID,
      MediaStore.Audio.Media.TITLE,
      MediaStore.Audio.Media.ARTIST,
      MediaStore.Audio.Media.DURATION,
      MediaStore.Audio.Media.MIME_TYPE
    )
    val sort = "${MediaStore.Audio.Media.DATE_ADDED} DESC"
    return try {
      context.contentResolver.query(collection, projection, null, null, sort)?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
        while (cursor.moveToNext() && tracks.size < limit) {
          val duration = cursor.getLong(durCol)
          if (duration <= 0L) continue
          val id = cursor.getLong(idCol)
          val uri = ContentUris.withAppendedId(collection, id).toString()
          tracks.add(
            DeviceAudioTrack(
              id = id,
              title = cursor.getString(titleCol)?.ifBlank { "Untitled" } ?: "Untitled",
              artist = cursor.getString(artistCol)?.ifBlank { "Unknown artist" } ?: "Unknown artist",
              durationMs = duration,
              uri = uri,
              mimeType = cursor.getString(mimeCol) ?: "audio/*"
            )
          )
        }
      }
      tracks
    } catch (t: Throwable) {
      Log.w(TAG, "MediaStore audio query failed: ${t.message}")
      emptyList()
    }
  }
}
