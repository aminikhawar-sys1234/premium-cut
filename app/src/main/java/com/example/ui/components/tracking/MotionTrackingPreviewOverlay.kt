package com.example.ui.components.tracking

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.example.engine.ai.LiveDetection
import com.example.engine.ai.MotionTrackingEvaluator
import com.example.engine.ai.MotionTrackingUiState
import com.example.engine.ai.NormalizedRect
import com.example.engine.ai.TrackingCategory
import com.example.engine.ai.TrackingCoordinateSpace
import com.example.engine.ai.TrackingEngineState
import com.example.engine.ai.tracking.BodyPoseLandmarks
import com.example.engine.ai.tracking.TrackingSampler
import kotlin.math.hypot

private val EmeraldAccent = Color(0xFF00D1B2)
private val CyanBlue = Color(0xFF00B4D8)
private val BoxBorder = Color(0xFF00E5FF)
private val LiveAmber = Color(0xFFFFC107)
private val FacePink = Color(0xFFFF8A80)
private val BodyLime = Color(0xFFB2FF59)

@Composable
fun MotionTrackingPreviewOverlay(
    uiState: MotionTrackingUiState,
    sourceTimeUs: Long,
    videoWidth: Int = 0,
    videoHeight: Int = 0,
    naturalRotation: Int = 0,
    canvasAspect: Float = 9f / 16f,
    onUpdateRegion: (NormalizedRect) -> Unit,
    onSelectDetection: (LiveDetection) -> Unit = {},
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val h = constraints.maxHeight.toFloat().coerceAtLeast(1f)

        fun mapPoint(nx: Float, ny: Float): Offset {
            if (videoWidth <= 0 || videoHeight <= 0) return Offset(nx * w, ny * h)
            val (px, py) = TrackingCoordinateSpace.videoNormToOverlayPx(
                nx, ny, w, h, videoWidth, videoHeight, naturalRotation, canvasAspect
            )
            return Offset(px, py)
        }

        val trackedKf = uiState.activeResult?.takeIf { it.keyframes.isNotEmpty() }?.let { result ->
            MotionTrackingEvaluator(result).evaluate(sourceTimeUs)
        }
        val followBox = if (trackedKf != null && trackedKf.confidence >= 0.12f) {
            TrackingSampler.boxFromCenter(
                trackedKf.centerX,
                trackedKf.centerY,
                (uiState.lockWidth * trackedKf.scaleX).coerceIn(0.04f, 0.95f),
                (uiState.lockHeight * trackedKf.scaleY).coerceIn(0.04f, 0.95f)
            )
        } else null

        val selectorBox = followBox ?: uiState.targetRegion
        val showSelector = uiState.isRegionSelectorActive || followBox == null

        Canvas(modifier = Modifier.fillMaxSize()) {
            fun drawMappedRect(rect: NormalizedRect, color: Color, stroke: Float, dashed: Boolean = false) {
                val tl = mapPoint(rect.left, rect.top)
                val br = mapPoint(rect.right, rect.bottom)
                val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f) else null
                drawRect(
                    color = color,
                    topLeft = Offset(minOf(tl.x, br.x), minOf(tl.y, br.y)),
                    size = Size(kotlin.math.abs(br.x - tl.x), kotlin.math.abs(br.y - tl.y)),
                    style = Stroke(width = stroke, pathEffect = effect)
                )
            }

            uiState.liveDetections.forEach { det ->
                val selected = det.id == uiState.selectedLiveId
                val color = when (det.category) {
                    TrackingCategory.FACE -> FacePink
                    TrackingCategory.BODY -> BodyLime
                    TrackingCategory.MOTION -> CyanBlue
                    else -> LiveAmber
                }
                drawMappedRect(det.box, if (selected) EmeraldAccent else color, if (selected) 3.dp.toPx() else 1.6.dp.toPx(), dashed = !selected)
                det.landmarks.forEach { p ->
                    if (p.first < 0f || p.second < 0f) return@forEach
                    drawCircle(color.copy(alpha = 0.9f), 3.dp.toPx(), mapPoint(p.first, p.second))
                }
                if (det.category == TrackingCategory.BODY) {
                    BodyPoseLandmarks.BONES.forEach { (a, b) ->
                        val pa = det.landmarks.getOrNull(a) ?: return@forEach
                        val pb = det.landmarks.getOrNull(b) ?: return@forEach
                        if (pa.first < 0f || pb.first < 0f) return@forEach
                        drawLine(color.copy(alpha = 0.75f), mapPoint(pa.first, pa.second), mapPoint(pb.first, pb.second), 1.6.dp.toPx())
                    }
                }
            }

            val activeResult = uiState.activeResult
            if (uiState.showMotionPath && activeResult != null && activeResult.keyframes.isNotEmpty()) {
                val path = Path()
                var first = true
                for (kf in activeResult.keyframes) {
                    if (kf.confidence < 0.15f) continue
                    val pt = mapPoint(kf.centerX, kf.centerY)
                    if (first) {
                        path.moveTo(pt.x, pt.y)
                        first = false
                    } else {
                        path.lineTo(pt.x, pt.y)
                    }
                }
                drawPath(path, EmeraldAccent.copy(alpha = 0.85f), style = Stroke(width = 2.dp.toPx()))
            }

            if (trackedKf != null) {
                val lost = trackedKf.confidence < 0.15f || uiState.engineState == TrackingEngineState.LOST
                if (!lost) {
                    val cur = mapPoint(trackedKf.centerX, trackedKf.centerY)
                    drawCircle(
                        color = if (uiState.engineState == TrackingEngineState.RECOVERING) LiveAmber else Color.Yellow,
                        radius = 6.dp.toPx(),
                        center = cur,
                        style = Stroke(width = 2.dp.toPx())
                    )
                    trackedKf.landmarkPoints.forEachIndexed { i, p ->
                        if (p.first < 0f || p.second < 0f) return@forEachIndexed
                        drawCircle(EmeraldAccent, 3.dp.toPx(), mapPoint(p.first, p.second))
                    }
                    if (trackedKf.landmarkPoints.size >= BodyPoseLandmarks.COUNT) {
                        BodyPoseLandmarks.BONES.forEach { (a, b) ->
                            val pa = trackedKf.landmarkPoints.getOrNull(a) ?: return@forEach
                            val pb = trackedKf.landmarkPoints.getOrNull(b) ?: return@forEach
                            if (pa.first < 0f || pb.first < 0f) return@forEach
                            drawLine(EmeraldAccent.copy(alpha = 0.8f), mapPoint(pa.first, pa.second), mapPoint(pb.first, pb.second), 1.8.dp.toPx())
                        }
                    }
                    trackedKf.cornerPin.takeIf { it.size == 4 }?.let { pin ->
                        val pts = pin.map { mapPoint(it.first, it.second) }
                        for (i in 0..3) {
                            drawLine(CyanBlue, pts[i], pts[(i + 1) % 4], 1.8.dp.toPx())
                        }
                    }
                }
            }

            if (showSelector) {
                val rect = selectorBox
                val tl = mapPoint(rect.left, rect.top)
                val tr = mapPoint(rect.right, rect.top)
                val br = mapPoint(rect.right, rect.bottom)
                val bl = mapPoint(rect.left, rect.bottom)
                val left = minOf(tl.x, bl.x, tr.x, br.x)
                val top = minOf(tl.y, tr.y, bl.y, br.y)
                val right = maxOf(tl.x, bl.x, tr.x, br.x)
                val bottom = maxOf(tl.y, tr.y, bl.y, br.y)
                val boxW = (right - left).coerceAtLeast(8f)
                val boxH = (bottom - top).coerceAtLeast(8f)
                drawRoundRect(
                    color = BoxBorder,
                    topLeft = Offset(left, top),
                    size = Size(boxW, boxH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx())
                )
                val cornerLen = 14.dp.toPx()
                val strokeW = 3.dp.toPx()
                drawLine(EmeraldAccent, Offset(left, top), Offset(left + cornerLen, top), strokeW)
                drawLine(EmeraldAccent, Offset(left, top), Offset(left, top + cornerLen), strokeW)
                drawLine(EmeraldAccent, Offset(right, top), Offset(right - cornerLen, top), strokeW)
                drawLine(EmeraldAccent, Offset(right, top), Offset(right, top + cornerLen), strokeW)
                drawLine(EmeraldAccent, Offset(left, bottom), Offset(left + cornerLen, bottom), strokeW)
                drawLine(EmeraldAccent, Offset(left, bottom), Offset(left, bottom - cornerLen), strokeW)
                drawLine(EmeraldAccent, Offset(right, bottom), Offset(right - cornerLen, bottom), strokeW)
                drawLine(EmeraldAccent, Offset(right, bottom), Offset(right, bottom - cornerLen), strokeW)
                val cx = left + boxW / 2f
                val cy = top + boxH / 2f
                val crossLen = 8.dp.toPx()
                drawLine(EmeraldAccent, Offset(cx - crossLen, cy), Offset(cx + crossLen, cy), 1.5.dp.toPx())
                drawLine(EmeraldAccent, Offset(cx, cy - crossLen), Offset(cx, cy + crossLen), 1.5.dp.toPx())
            }
        }

        val dragW = (selectorBox.width * w).coerceAtLeast(40f)
        val dragH = (selectorBox.height * h).coerceAtLeast(40f)
        var dragOffset by remember(selectorBox.left, selectorBox.top, w, h) {
            mutableStateOf(Offset(selectorBox.left * w, selectorBox.top * h))
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(w, h, uiState.liveDetections, uiState.targetRegion) {
                    detectTapGestures { tap ->
                        val hit = uiState.liveDetections.minByOrNull { det ->
                            val c = mapPoint(det.box.centerX, det.box.centerY)
                            hypot(c.x - tap.x, c.y - tap.y)
                        }
                        if (hit != null) {
                            val c = mapPoint(hit.box.centerX, hit.box.centerY)
                            val tl = mapPoint(hit.box.left, hit.box.top)
                            val br = mapPoint(hit.box.right, hit.box.bottom)
                            val inside = tap.x in minOf(tl.x, br.x)..maxOf(tl.x, br.x) &&
                                tap.y in minOf(tl.y, br.y)..maxOf(tl.y, br.y)
                            if (inside || hypot(c.x - tap.x, c.y - tap.y) < 48f) {
                                onSelectDetection(hit)
                                return@detectTapGestures
                            }
                        }
                        val halfW = uiState.targetRegion.width / 2f
                        val halfH = uiState.targetRegion.height / 2f
                        val nx = (tap.x / w).coerceIn(0f, 1f)
                        val ny = (tap.y / h).coerceIn(0f, 1f)
                        onUpdateRegion(
                            NormalizedRect(
                                left = (nx - halfW).coerceIn(0f, 1f - uiState.targetRegion.width),
                                top = (ny - halfH).coerceIn(0f, 1f - uiState.targetRegion.height),
                                right = (nx + halfW).coerceIn(uiState.targetRegion.width, 1f),
                                bottom = (ny + halfH).coerceIn(uiState.targetRegion.height, 1f)
                            )
                        )
                    }
                }
                .pointerInput(w, h, dragW, dragH, followBox == null) {
                    if (followBox != null) return@pointerInput
                    detectDragGestures(
                        onDrag = { change, amount ->
                            change.consume()
                            dragOffset = Offset(
                                (dragOffset.x + amount.x).coerceIn(0f, w - dragW),
                                (dragOffset.y + amount.y).coerceIn(0f, h - dragH)
                            )
                            onUpdateRegion(
                                NormalizedRect(
                                    left = (dragOffset.x / w).coerceIn(0f, 1f),
                                    top = (dragOffset.y / h).coerceIn(0f, 1f),
                                    right = ((dragOffset.x + dragW) / w).coerceIn(0f, 1f),
                                    bottom = ((dragOffset.y + dragH) / h).coerceIn(0f, 1f)
                                )
                            )
                        }
                    )
                }
        )
    }
}
