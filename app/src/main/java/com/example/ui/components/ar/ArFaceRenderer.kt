package com.example.ui.components.ar

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.ahstudio.face.core.FaceLandmarkType
import com.ahstudio.face.core.TrackedFace
import com.ahstudio.face.core.Vec2
import com.ahstudio.face.deformation.FaceWarpMapper
import com.example.engine.composition.gpu.arFaceGeometry

/** Where the (upright) video is drawn inside the preview canvas, in pixels. */
data class VideoRect(val left: Float, val top: Float, val width: Float, val height: Float)

/**
 * Draws AR filters on faces that were really detected + tracked by the face engine
 * ([com.ahstudio.face.tracking.ClipFaceTracker]). Nothing is drawn when no face is found.
 */
object ArFaceRenderer {

    /** Letterbox-fit the video into the canvas (same as ContentScale.Fit). */
    fun fitVideoRect(canvasW: Float, canvasH: Float, videoAspect: Float): VideoRect {
        val aspect = if (videoAspect > 0.01f) videoAspect else canvasW / canvasH
        return if (aspect > canvasW / canvasH) {
            val h = canvasW / aspect
            VideoRect(0f, (canvasH - h) / 2f, canvasW, h)
        } else {
            val w = canvasH * aspect
            VideoRect((canvasW - w) / 2f, 0f, w, canvasH)
        }
    }

    fun draw(
        scope: DrawScope,
        faces: List<TrackedFace>,
        rect: VideoRect,
        flipHorizontal: Boolean,
        activeFilter: ArFilterItem?,
        showTrackingGrid: Boolean,
        primaryColor: Color,
        secondaryColor: Color,
        opacity: Float,
        scaleFactor: Float,
        offsetYFactor: Float
    ) {
        fun px(v: Vec2): Offset {
            val nx = if (flipHorizontal) 1f - v.x else v.x
            return Offset(rect.left + nx * rect.width, rect.top + v.y * rect.height)
        }

        for (face in faces) {
            val b = face.bounds
            val center = px(Vec2(b.centerX, b.centerY))
            val fw = b.width * rect.width * scaleFactor
            val fh = b.height * rect.height * scaleFactor
            val cy = center.y + offsetYFactor * rect.height
            // ML Kit roll is counter-clockwise positive; Compose rotates clockwise. A mirrored
            // video flips the direction again.
            val roll = if (flipHorizontal) face.rotation.eulerZ else -face.rotation.eulerZ

            scope.rotate(roll, pivot = Offset(center.x, cy)) {
                drawFaceArt(
                    this, face, flipHorizontal, activeFilter, showTrackingGrid,
                    center.x, cy, fw, fh, primaryColor, secondaryColor, opacity
                )
            }
        }
    }

    /**
     * Preview draw that uses the SAME placement maths as the GL export ([ArOverlayStage]): face
     * positions go through [placement] (container rotation, user rotation, flips, crop, keyframes),
     * so the pending preview lands where the exported filter will.
     */
    fun drawPlaced(
        scope: DrawScope,
        faces: List<TrackedFace>,
        placement: FaceWarpMapper.Placement,
        uprightW: Float,
        uprightH: Float,
        flipHorizontal: Boolean,
        activeFilter: ArFilterItem?,
        showTrackingGrid: Boolean,
        primaryColor: Color,
        secondaryColor: Color,
        opacity: Float,
        scaleFactor: Float,
        offsetYFactor: Float
    ) {
        for (face in faces) {
            val g = arFaceGeometry(face, scaleFactor, offsetYFactor, placement, uprightW, uprightH) ?: continue
            // GL quad rotation is counter-clockwise; Compose rotates clockwise.
            scope.rotate(-g.rotationDegCcw, pivot = Offset(g.centerX, g.centerY)) {
                drawFaceArt(
                    this, face, flipHorizontal, activeFilter, showTrackingGrid,
                    g.centerX, g.centerY, 2f * g.halfW, 2f * g.halfH,
                    primaryColor, secondaryColor, opacity
                )
            }
        }
    }

    /**
     * Draws one face's grid + filter art around ([cx], [cy]) in an upright [fw] x [fh] face box.
     * Rotation is the caller's job. The live preview and the GL export bake both call this, so
     * the exported art is the same drawing as the preview.
     */
    fun drawFaceArt(
        s: DrawScope,
        face: TrackedFace?,
        flipHorizontal: Boolean,
        activeFilter: ArFilterItem?,
        showTrackingGrid: Boolean,
        cx: Float, cy: Float, fw: Float, fh: Float,
        primaryColor: Color,
        secondaryColor: Color,
        opacity: Float,
    ) {
        val alpha = opacity.coerceIn(0.1f, 1.0f)
        val main = primaryColor.copy(alpha = alpha)
        val sec = secondaryColor.copy(alpha = alpha * 0.75f)
        if (face != null && (showTrackingGrid || activeFilter?.isMeshOverlay == true)) {
            drawTrackingGrid(s, face, flipHorizontal, cx, cy, fw, fh, main, sec)
        }
        if (activeFilter != null) {
            drawFilter(s, activeFilter, cx, cy, fw, fh, main, sec)
        }
    }

    /** Face outline + the landmarks the detector really reports (eyes, nose, mouth). */
    private fun drawTrackingGrid(
        s: DrawScope,
        face: TrackedFace,
        flipHorizontal: Boolean,
        cx: Float, cy: Float, w: Float, h: Float,
        main: Color, sec: Color
    ) {
        val hw = w / 2f
        val hh = h / 2f
        val outline = Path().apply {
            moveTo(cx, cy - hh)
            lineTo(cx + hw * 0.7f, cy - hh * 0.7f)
            lineTo(cx + hw, cy - hh * 0.1f)
            lineTo(cx + hw * 0.8f, cy + hh * 0.6f)
            lineTo(cx, cy + hh)
            lineTo(cx - hw * 0.8f, cy + hh * 0.6f)
            lineTo(cx - hw, cy - hh * 0.1f)
            lineTo(cx - hw * 0.7f, cy - hh * 0.7f)
            close()
        }
        s.drawPath(outline, color = main, style = Stroke(width = 1.8f))

        val lm = face.landmarks
        val b = face.bounds
        // Landmark positions are absolute; re-express them relative to the (possibly scaled /
        // offset) face box so the grid follows the Scale/Offset sliders too.
        fun rel(v: Vec2?, fx: Float, fy: Float): Offset {
            if (v == null) return Offset(cx + fx * hw, cy + fy * hh)
            val nx = (v.x - b.centerX) / b.width.coerceAtLeast(1e-4f)
            val ny = (v.y - b.centerY) / b.height.coerceAtLeast(1e-4f)
            val sx = if (flipHorizontal) -1f else 1f
            return Offset(cx + sx * nx * w, cy + ny * h)
        }
        val leftEye = rel(lm?.get(FaceLandmarkType.LEFT_EYE), -0.3f, -0.2f)
        val rightEye = rel(lm?.get(FaceLandmarkType.RIGHT_EYE), 0.3f, -0.2f)
        val nose = rel(lm?.get(FaceLandmarkType.NOSE_BASE), 0f, 0.1f)
        val mouth = rel(lm?.mouthCenter, 0f, 0.55f)

        s.drawLine(main, leftEye, nose, 1.5f)
        s.drawLine(main, rightEye, nose, 1.5f)
        s.drawLine(main, nose, mouth, 1.5f)
        s.drawLine(main, leftEye, rightEye, 1.5f)
        s.drawCircle(sec, radius = hw * 0.1f, center = leftEye, style = Stroke(1.5f))
        s.drawCircle(sec, radius = hw * 0.1f, center = rightEye, style = Stroke(1.5f))
        for (p in listOf(leftEye, rightEye, nose, mouth)) s.drawCircle(sec, radius = 3f, center = p)
    }

    private fun drawFilter(
        s: DrawScope, filter: ArFilterItem,
        cx: Float, cy: Float, w: Float, h: Float,
        primary: Color, secondary: Color
    ) {
        val halfW = w / 2f
        val halfH = h / 2f

        when (filter.id) {
            "ar_cyber_visor" -> {
                val visor = Path().apply {
                    moveTo(cx - halfW * 1.1f, cy - halfH * 0.3f)
                    lineTo(cx + halfW * 1.1f, cy - halfH * 0.3f)
                    lineTo(cx + halfW * 0.9f, cy + halfH * 0.25f)
                    lineTo(cx - halfW * 0.9f, cy + halfH * 0.25f)
                    close()
                }
                s.drawPath(visor, brush = Brush.verticalGradient(listOf(primary.copy(alpha = 0.5f), secondary)))
                s.drawPath(visor, color = primary, style = Stroke(width = 3f))
            }
            "ar_neon_crown" -> {
                val crown = Path().apply {
                    moveTo(cx - halfW, cy - halfH * 0.4f)
                    lineTo(cx - halfW * 0.8f, cy - halfH * 1.2f)
                    lineTo(cx - halfW * 0.4f, cy - halfH * 0.7f)
                    lineTo(cx, cy - halfH * 1.4f)
                    lineTo(cx + halfW * 0.4f, cy - halfH * 0.7f)
                    lineTo(cx + halfW * 0.8f, cy - halfH * 1.2f)
                    lineTo(cx + halfW, cy - halfH * 0.4f)
                    close()
                }
                s.drawPath(crown, brush = Brush.verticalGradient(listOf(primary, secondary)))
                s.drawPath(crown, color = Color.White, style = Stroke(width = 2.5f))
            }
            "ar_matrix_shades" -> {
                s.drawRoundRect(primary, Offset(cx - halfW, cy - halfH * 0.3f), Size(halfW * 0.85f, halfH * 0.6f), CornerRadius(8f, 8f))
                s.drawRoundRect(primary, Offset(cx + halfW * 0.15f, cy - halfH * 0.3f), Size(halfW * 0.85f, halfH * 0.6f), CornerRadius(8f, 8f))
                s.drawLine(secondary, Offset(cx - halfW * 0.15f, cy - halfH * 0.1f), Offset(cx + halfW * 0.15f, cy - halfH * 0.1f), 3f)
            }
            "ar_hologram_goggles" -> {
                val r = halfW * 0.4f
                val left = Offset(cx - halfW * 0.45f, cy - halfH * 0.15f)
                val right = Offset(cx + halfW * 0.45f, cy - halfH * 0.15f)
                s.drawCircle(primary.copy(alpha = 0.35f), radius = r, center = left)
                s.drawCircle(primary.copy(alpha = 0.35f), radius = r, center = right)
                s.drawCircle(secondary, radius = r, center = left, style = Stroke(2.5f))
                s.drawCircle(secondary, radius = r, center = right, style = Stroke(2.5f))
                s.drawLine(primary, Offset(cx - halfW * 0.1f, cy - halfH * 0.15f), Offset(cx + halfW * 0.1f, cy - halfH * 0.15f), 3.5f)
            }
            "ar_cat_whiskers" -> {
                val earL = Path().apply {
                    moveTo(cx - halfW * 0.8f, cy - halfH * 0.6f)
                    lineTo(cx - halfW * 0.95f, cy - halfH * 1.3f)
                    lineTo(cx - halfW * 0.35f, cy - halfH * 0.8f)
                    close()
                }
                val earR = Path().apply {
                    moveTo(cx + halfW * 0.8f, cy - halfH * 0.6f)
                    lineTo(cx + halfW * 0.95f, cy - halfH * 1.3f)
                    lineTo(cx + halfW * 0.35f, cy - halfH * 0.8f)
                    close()
                }
                s.drawPath(earL, primary)
                s.drawPath(earR, primary)
                s.drawLine(primary, Offset(cx - halfW * 0.3f, cy + halfH * 0.2f), Offset(cx - halfW * 1.1f, cy + halfH * 0.1f), 2.5f)
                s.drawLine(primary, Offset(cx - halfW * 0.3f, cy + halfH * 0.25f), Offset(cx - halfW * 1.15f, cy + halfH * 0.25f), 2.5f)
                s.drawLine(primary, Offset(cx + halfW * 0.3f, cy + halfH * 0.2f), Offset(cx + halfW * 1.1f, cy + halfH * 0.1f), 2.5f)
                s.drawLine(primary, Offset(cx + halfW * 0.3f, cy + halfH * 0.25f), Offset(cx + halfW * 1.15f, cy + halfH * 0.25f), 2.5f)
                s.drawCircle(secondary, radius = 6f, center = Offset(cx, cy + halfH * 0.15f))
            }
            "ar_bunny_ears" -> {
                val leftEar = Path().apply {
                    moveTo(cx - halfW * 0.5f, cy - halfH * 0.5f)
                    quadraticTo(cx - halfW * 0.8f, cy - halfH * 1.6f, cx - halfW * 0.35f, cy - halfH * 1.8f)
                    quadraticTo(cx - halfW * 0.1f, cy - halfH * 1.5f, cx - halfW * 0.2f, cy - halfH * 0.5f)
                    close()
                }
                val rightEar = Path().apply {
                    moveTo(cx + halfW * 0.2f, cy - halfH * 0.5f)
                    quadraticTo(cx + halfW * 0.1f, cy - halfH * 1.5f, cx + halfW * 0.35f, cy - halfH * 1.8f)
                    quadraticTo(cx + halfW * 0.8f, cy - halfH * 1.6f, cx + halfW * 0.5f, cy - halfH * 0.5f)
                    close()
                }
                s.drawPath(leftEar, primary)
                s.drawPath(rightEar, primary)
                s.drawCircle(secondary, radius = 7f, center = Offset(cx, cy + halfH * 0.15f))
            }
            "ar_tech_hud" -> {
                s.drawCircle(primary, radius = halfW * 1.1f, center = Offset(cx, cy), style = Stroke(2f))
                s.drawLine(secondary, Offset(cx - halfW * 1.2f, cy), Offset(cx + halfW * 1.2f, cy), 1.5f)
                s.drawLine(secondary, Offset(cx, cy - halfH * 1.2f), Offset(cx, cy + halfH * 1.2f), 1.5f)
            }
            "ar_angel_halo" -> {
                s.drawOval(primary, Offset(cx - halfW * 1.1f, cy - halfH * 1.3f), Size(halfW * 2.2f, halfH * 0.5f), style = Stroke(5f))
            }
            "ar_devil_horns" -> {
                val leftHorn = Path().apply {
                    moveTo(cx - halfW * 0.4f, cy - halfH * 0.5f)
                    quadraticTo(cx - halfW * 0.8f, cy - halfH * 1.2f, cx - halfW, cy - halfH * 1.4f)
                    quadraticTo(cx - halfW * 0.5f, cy - halfH * 0.9f, cx - halfW * 0.2f, cy - halfH * 0.5f)
                    close()
                }
                val rightHorn = Path().apply {
                    moveTo(cx + halfW * 0.4f, cy - halfH * 0.5f)
                    quadraticTo(cx + halfW * 0.8f, cy - halfH * 1.2f, cx + halfW, cy - halfH * 1.4f)
                    quadraticTo(cx + halfW * 0.5f, cy - halfH * 0.9f, cx + halfW * 0.2f, cy - halfH * 0.5f)
                    close()
                }
                s.drawPath(leftHorn, primary)
                s.drawPath(rightHorn, primary)
            }
            "ar_venice_mask" -> {
                val mask = Path().apply {
                    moveTo(cx - halfW * 1.05f, cy - halfH * 0.45f)
                    cubicTo(cx - halfW * 0.6f, cy - halfH * 0.6f, cx + halfW * 0.6f, cy - halfH * 0.6f, cx + halfW * 1.05f, cy - halfH * 0.45f)
                    quadraticTo(cx + halfW * 0.9f, cy + halfH * 0.25f, cx, cy + halfH * 0.15f)
                    quadraticTo(cx - halfW * 0.9f, cy + halfH * 0.25f, cx - halfW * 1.05f, cy - halfH * 0.45f)
                    close()
                }
                s.drawPath(mask, brush = Brush.verticalGradient(listOf(primary, secondary)))
                s.drawPath(mask, color = Color(0xFFFFD700), style = Stroke(2.5f))
            }
            "ar_privacy_mosaic" -> {
                // Block mosaic over the face box (cells alternate alpha, like pixelation).
                val cols = 8
                val rows = 8
                val boxW = halfW * 1.6f
                val boxH = halfH * 1.4f
                val cellW = boxW / cols
                val cellH = boxH / rows
                for (r in 0 until rows) for (c in 0 until cols) {
                    s.drawRect(
                        color = primary.copy(alpha = if ((r + c) % 2 == 0) 0.85f else 0.6f),
                        topLeft = Offset(cx - boxW / 2f + c * cellW, cy - boxH / 2f + r * cellH),
                        size = Size(cellW, cellH)
                    )
                }
            }
        }
    }
}
