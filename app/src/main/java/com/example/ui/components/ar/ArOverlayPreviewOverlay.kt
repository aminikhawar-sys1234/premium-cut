package com.example.ui.components.ar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ahstudio.face.core.TrackedFace
import com.example.domain.model.VideoClip
import com.example.engine.composition.gpu.faceClipPlacement
import com.example.engine.composition.gpu.faceUprightSize
import kotlinx.coroutines.delay

/**
 * Live AR preview. Faces come from the real face engine ([facesProvider], backed by
 * ClipFaceTracker), so filters follow the actual face. If no face is found nothing is drawn.
 */
@Composable
fun ArOverlayPreviewOverlay(
    activeFilter: ArFilterItem?,
    showTrackingGrid: Boolean,
    scaleFactor: Float,
    offsetYFactor: Float,
    opacity: Float,
    positionMs: Long,
    videoAspect: Float,
    flipHorizontal: Boolean,
    facesProvider: () -> List<TrackedFace>,
    /** When set, placement uses the export's clip maths (rotation, flips, crop, keyframes). */
    clip: VideoClip? = null,
    modifier: Modifier = Modifier
) {
    if (activeFilter == null && !showTrackingGrid) return

    var faces by remember { mutableStateOf<List<TrackedFace>>(emptyList()) }
    var searching by remember { mutableStateOf(true) }
    val currentProvider by rememberUpdatedState(facesProvider)

    // Detection is async: poll a few times until the tracker has a result for this position.
    LaunchedEffect(positionMs / 50L, activeFilter?.id, showTrackingGrid) {
        var attempts = 0
        while (true) {
            val found = currentProvider()
            faces = found
            if (found.isNotEmpty()) { searching = false; break }
            if (attempts >= 16) { searching = false; break }
            attempts++
            searching = true
            delay(120)
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val height = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val primary = activeFilter?.primaryColor ?: Color(0xFF00E5FF)
        val secondary = activeFilter?.secondaryColor ?: Color(0xFF00D1B2)
        val rect = remember(width, height, videoAspect) {
            ArFaceRenderer.fitVideoRect(width, height, videoAspect)
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            if (clip != null) {
                val w = size.width.toInt().coerceAtLeast(1)
                val h = size.height.toInt().coerceAtLeast(1)
                val placement = faceClipPlacement(clip, positionMs, w, h, null)
                val (uw, uh) = faceUprightSize(clip, w, h)
                ArFaceRenderer.drawPlaced(
                    scope = this,
                    faces = faces,
                    placement = placement,
                    uprightW = uw,
                    uprightH = uh,
                    flipHorizontal = clip.flipHorizontal,
                    activeFilter = activeFilter,
                    showTrackingGrid = showTrackingGrid,
                    primaryColor = primary,
                    secondaryColor = secondary,
                    opacity = opacity,
                    scaleFactor = scaleFactor,
                    offsetYFactor = offsetYFactor
                )
            } else {
                ArFaceRenderer.draw(
                    scope = this,
                    faces = faces,
                    rect = rect,
                    flipHorizontal = flipHorizontal,
                    activeFilter = activeFilter,
                    showTrackingGrid = showTrackingGrid,
                    primaryColor = primary,
                    secondaryColor = secondary,
                    opacity = opacity,
                    scaleFactor = scaleFactor,
                    offsetYFactor = offsetYFactor
                )
            }
        }

        if (faces.isEmpty() && !searching) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("No face detected at this frame", color = Color.White, fontSize = 11.sp)
            }
        }
    }
}
