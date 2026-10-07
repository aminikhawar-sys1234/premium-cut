package com.example.engine.export

import android.media.MediaFormat

/**
 * Tags encoder output with explicit SDR Rec.709 / limited-range colour metadata.
 *
 * Without these keys the encoded stream carries no colour description, so players
 * guess (often BT.601 on SD-sized or unspecified streams) and the exported video
 * looks different from the editor preview. Tagging makes preview, export and
 * external players agree. minSdk is 24, where all three keys exist.
 */
object ExportColorTagging {

  fun applySdrRec709(format: MediaFormat) {
    format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
    format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
    format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
  }

  fun isTaggedSdrRec709(format: MediaFormat): Boolean =
    format.containsKey(MediaFormat.KEY_COLOR_STANDARD) &&
      format.getInteger(MediaFormat.KEY_COLOR_STANDARD) == MediaFormat.COLOR_STANDARD_BT709 &&
      format.containsKey(MediaFormat.KEY_COLOR_TRANSFER) &&
      format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) == MediaFormat.COLOR_TRANSFER_SDR_VIDEO &&
      format.containsKey(MediaFormat.KEY_COLOR_RANGE) &&
      format.getInteger(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_LIMITED
}
