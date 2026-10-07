package com.ahstudio.face.ui

import com.ahstudio.face.overlay.FaceAnchor
import com.ahstudio.face.timeline.FaceEffectClipData
import java.util.UUID

data class SpecDefaults(
    val anchor: FaceAnchor,
    val offsetY: Float = 0f,
    val scale: Float = 1f,
    val followRotation: Float = 1f,
)

object FacePresets {
    val stickers = mapOf(
        "sunglasses" to SpecDefaults(FaceAnchor.EYES_CENTER, offsetY = 0.02f, scale = 1.25f),
        "crown" to SpecDefaults(FaceAnchor.FOREHEAD, offsetY = -0.30f, scale = 1.2f, followRotation = 0.35f),
        "mustache" to SpecDefaults(FaceAnchor.NOSE, offsetY = 0.35f, scale = 0.8f),
        "mask" to SpecDefaults(FaceAnchor.FACE_CENTER, scale = 1.1f),
        "earring_left" to SpecDefaults(FaceAnchor.LEFT_EAR, scale = 0.25f),
        "earring_right" to SpecDefaults(FaceAnchor.RIGHT_EAR, scale = 0.25f),
    )

    fun buildStickerData(clipId: String, stickerId: String,
                         effectId: String = UUID.randomUUID().toString()): FaceEffectClipData {
        val d = stickers[stickerId] ?: SpecDefaults(FaceAnchor.FACE_CENTER)
        return FaceEffectClipData(
            effectId = effectId, clipId = clipId, stickerId = stickerId,
            anchor = d.anchor.name, offsetY = d.offsetY, scale = d.scale,
            followRotation = d.followRotation,
        )
    }
}

/** One-tap Face Reshape looks; values are the same 0..1 sliders shown in the AR panel. */
object FaceReshapePresets {
    data class Preset(val name: String, val emoji: String, val params: com.ahstudio.face.deformation.DeformationParams)

    val all = listOf(
        Preset("Natural", "🌿", com.ahstudio.face.deformation.DeformationParams(eyeEnlarge = 0.25f, faceSlim = 0.20f, noseReshape = 0.15f)),
        Preset("Slim", "✨", com.ahstudio.face.deformation.DeformationParams(eyeEnlarge = 0.30f, faceSlim = 0.55f, jawSharp = 0.25f)),
        Preset("V-Shape", "💎", com.ahstudio.face.deformation.DeformationParams(faceSlim = 0.45f, jawSharp = 0.55f, chinAdjust = 0.35f)),
        Preset("Glam", "💫", com.ahstudio.face.deformation.DeformationParams(eyeEnlarge = 0.55f, faceSlim = 0.35f, noseReshape = 0.30f, smileAdjust = 0.30f)),
    )
}
