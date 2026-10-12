package com.example.engine.effects

import com.example.domain.model.FilterSettings
import com.example.domain.model.FilterType

/**
 * Single source of truth for the Filters panel and the GPU look extras that
 * ColorGradingGlEffect (preview) and GpuCompositionRenderer (export) share.
 *
 * Legacy [FilterType] values remain in the enum so saved projects deserialize and
 * still render. They are not offered as new selectable presets.
 */
object FilterCatalog {

  val SELECTABLE: List<FilterType> = listOf(
    FilterType.FOUR_K,
    FilterType.BLACKLIGHT_FIX,
    FilterType.GOLDEN_AUTUMN,
    FilterType.HDR,
    FilterType.ENHANCE,
    FilterType.GLOW
  )

  fun isSelectable(type: FilterType): Boolean =
    type == FilterType.NONE || type in SELECTABLE

  fun definition(type: FilterType): FilterLookDefinition =
    DEFINITIONS[type] ?: FilterLookDefinition(
      type = type,
      displayName = type.displayName,
      processing = FilterProcessing.COLOR_MATRIX,
      capability = "Legacy look kept so existing projects still render. It is no longer offered as a new preset."
    )

  fun spatialExtras(filter: FilterSettings): FilterSpatialExtras {
    if (!filter.enabled || filter.type == FilterType.NONE || filter.intensity <= 0.01f) {
      return FilterSpatialExtras.NONE
    }
    val i = filter.intensity.coerceIn(0f, 1f)
    return when (filter.type) {
      FilterType.FOUR_K -> FilterSpatialExtras(
        sharpness = 0.55f * i,
        clarity = 0.40f * i
      )
      FilterType.ENHANCE -> FilterSpatialExtras(
        sharpness = 0.38f * i,
        clarity = 0.32f * i,
        enhance = 0.45f * i
      )
      FilterType.HDR -> FilterSpatialExtras(
        hdrLook = 0.70f * i,
        clarity = 0.22f * i
      )
      FilterType.GLOW -> FilterSpatialExtras(
        glow = 0.85f * i
      )
      else -> FilterSpatialExtras.NONE
    }
  }

  fun capabilityNote(type: FilterType): String? = definition(type).capability

  private val DEFINITIONS: Map<FilterType, FilterLookDefinition> = mapOf(
    FilterType.NONE to FilterLookDefinition(
      type = FilterType.NONE,
      displayName = "Original",
      processing = FilterProcessing.NONE
    ),
    FilterType.FOUR_K to FilterLookDefinition(
      type = FilterType.FOUR_K,
      displayName = "4K",
      processing = FilterProcessing.DETAIL_ENHANCE,
      capability = "4K is a micro-contrast and sharpening look. It does not upsample the frame. " +
        "True 4K output is selected in Export (resolution). The encoder falls back if the device cannot encode 4K."
    ),
    FilterType.BLACKLIGHT_FIX to FilterLookDefinition(
      type = FilterType.BLACKLIGHT_FIX,
      displayName = "Black Light",
      processing = FilterProcessing.COLOR_MATRIX
    ),
    FilterType.GOLDEN_AUTUMN to FilterLookDefinition(
      type = FilterType.GOLDEN_AUTUMN,
      displayName = "Golden",
      processing = FilterProcessing.COLOR_MATRIX
    ),
    FilterType.HDR to FilterLookDefinition(
      type = FilterType.HDR,
      displayName = "HDR",
      processing = FilterProcessing.HDR_LOOK,
      capability = "HDR is a local-contrast look (shadow lift, highlight punch). " +
        "Export is SDR Rec.709 / limited range — not HDR10, HLG, or Dolby Vision."
    ),
    FilterType.ENHANCE to FilterLookDefinition(
      type = FilterType.ENHANCE,
      displayName = "Enhance",
      processing = FilterProcessing.DETAIL_ENHANCE
    ),
    FilterType.GLOW to FilterLookDefinition(
      type = FilterType.GLOW,
      displayName = "Glow",
      processing = FilterProcessing.BLOOM
    )
  )
}

data class FilterLookDefinition(
  val type: FilterType,
  val displayName: String,
  val processing: FilterProcessing,
  val capability: String? = null
)

enum class FilterProcessing {
  NONE,
  COLOR_MATRIX,
  DETAIL_ENHANCE,
  HDR_LOOK,
  BLOOM
}

data class FilterSpatialExtras(
  val sharpness: Float = 0f,
  val clarity: Float = 0f,
  val glow: Float = 0f,
  val hdrLook: Float = 0f,
  val enhance: Float = 0f
) {
  companion object {
    val NONE = FilterSpatialExtras()
  }

  fun isActive(): Boolean =
    sharpness > 0.01f || clarity > 0.01f || glow > 0.01f || hdrLook > 0.01f || enhance > 0.01f
}
