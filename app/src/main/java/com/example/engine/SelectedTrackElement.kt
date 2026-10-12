package com.example.engine

sealed class SelectedTrackElement {
  object None : SelectedTrackElement()
  data class Video(val clipId: String) : SelectedTrackElement()
  data class Overlay(val clipId: String) : SelectedTrackElement()
  data class Audio(val clipId: String) : SelectedTrackElement()
  data class Text(val clipId: String) : SelectedTrackElement()
  data class Sticker(val clipId: String) : SelectedTrackElement()
  data class Effect(val clipId: String) : SelectedTrackElement()

  val clipIdOrNull: String?
    get() = when (this) {
      is Video -> clipId
      is Overlay -> clipId
      is Audio -> clipId
      is Text -> clipId
      is Sticker -> clipId
      is Effect -> clipId
      None -> null
    }
}

enum class InsertionMode {
  RIPPLE,
  OVERWRITE,
  APPEND
}

data class SnapResult(
  val snappedPosMs: Long,
  val didSnap: Boolean = false,
  val snapLineMs: Long? = null
)
