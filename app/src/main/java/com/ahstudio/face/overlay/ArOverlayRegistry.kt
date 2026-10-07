package com.ahstudio.face.overlay

/** AR face filter attached to one clip; rendered by the GL compositor in preview and export alike. */
data class ArOverlayParams(
    val filterId: String,
    val scale: Float = 1f,
    val offsetY: Float = 0f,
    val opacity: Float = 1f,
)

/** Compact text form stored on the clip so the filter saves, loads, undoes and exports with the timeline. */
object ArOverlayCodec {
    fun encode(p: ArOverlayParams?): String? =
        if (p == null || p.filterId.isBlank()) null
        else "${p.filterId}|${p.scale}|${p.offsetY}|${p.opacity}"

    fun decode(s: String?): ArOverlayParams? {
        if (s.isNullOrBlank()) return null
        val v = s.split('|')
        val id = v.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
        return ArOverlayParams(
            filterId = id,
            scale = v.getOrNull(1)?.toFloatOrNull()?.coerceIn(0.2f, 4f) ?: 1f,
            offsetY = v.getOrNull(2)?.toFloatOrNull()?.coerceIn(-2f, 2f) ?: 0f,
            opacity = v.getOrNull(3)?.toFloatOrNull()?.coerceIn(0.1f, 1f) ?: 1f,
        )
    }
}

/**
 * Process-wide bridge between the editor UI and the GL compositor (same idea as FaceWarpRegistry).
 * Faces come from [com.ahstudio.face.deformation.FaceWarpRegistry.faceSource].
 */
object ArOverlayRegistry {
    @Volatile private var params: Map<String, ArOverlayParams> = emptyMap()

    fun update(newParams: Map<String, ArOverlayParams>) { params = newParams }
    fun paramsFor(clipId: String?): ArOverlayParams? = clipId?.let { params[it] }
    fun clear() { params = emptyMap() }
}
