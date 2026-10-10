package com.example.data.presets

data class StockMediaItem(
  val id: String,
  val title: String,
  val category: String,
  val isVideo: Boolean,
  val durationMs: Long,
  val gradientStart: Long,
  val gradientEnd: Long,
  val iconEmoji: String,
  val description: String,
  val resolution: String = "1080p",
  val uri: String = ""
)

/**
 * Bundled stock library. Starts empty — only real downloaded or user-imported
 * media is shown. Fake gradient/emoji sample clips are not seeded.
 */
object StockMediaCatalog {
  val stockItems: List<StockMediaItem> = emptyList()
}
