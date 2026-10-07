package com.ahstudio.face.tracking

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.ahstudio.face.core.FaceTrackingConfig
import com.ahstudio.face.core.FaceTrackingEngine
import com.ahstudio.face.core.TrackedFace
import com.ahstudio.face.deformation.FaceWarpFaceSource
import com.ahstudio.face.detection.MlKitFaceDetector
import com.ahstudio.face.detection.RetrieverFrameProvider

/**
 * Production [FaceWarpFaceSource]: ML Kit detection on retriever frames, tracked + smoothed by
 * [FaceTrackingEngine]. Faces are reported in the upright source frame (container rotation applied,
 * user rotation/mirror NOT applied - the compositor maps those).
 */
class ClipFaceTracker(
    private val context: Context,
    private val uriFor: (clipId: String) -> String?,
    cfg: FaceTrackingConfig = FaceTrackingConfig(maxFaces = 3, useContours = false),
) : FaceWarpFaceSource {

    private val engine = FaceTrackingEngine(
        detector = MlKitFaceDetector(cfg),
        frames = RetrieverFrameProvider(::openRetriever),
        cfg = cfg,
    )

    private fun openRetriever(clipId: String): MediaMetadataRetriever {
        val uri = uriFor(clipId) ?: error("No media uri for clip $clipId")
        return MediaMetadataRetriever().apply { setDataSource(context, Uri.parse(uri)) }
    }

    override fun facesAt(clipId: String, sourceTimeUs: Long, blocking: Boolean): List<TrackedFace> {
        if (blocking) {
            // Export: wait for the real result of exactly this frame (no stale fallback); throws on timeout.
            return engine.resultAtStrict(clipId, sourceTimeUs, HASH, false, EXPORT_TIMEOUT_MS).faces
        }
        // Preview: never stall the GL thread. Use the exact result if cached, otherwise kick off
        // detection and reuse the nearest recent result so the warp does not flicker off between frames.
        val exact = engine.resultAtRenderTime(clipId, sourceTimeUs, HASH, false)
        if (exact.faces.isNotEmpty()) {
            lastSeen[clipId] = sourceTimeUs to exact.faces
            return exact.faces
        }
        val held = lastSeen[clipId] ?: return emptyList()
        return if (kotlin.math.abs(held.first - sourceTimeUs) <= PREVIEW_HOLD_US) held.second else emptyList()
    }

    private val lastSeen = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<TrackedFace>>>()

    /** Call when a clip is trimmed/replaced so stale tracks are dropped. */
    fun invalidate() { lastSeen.clear(); engine.onConfigChanged() }

    fun shutdown() = engine.shutdown()

    private companion object {
        const val HASH = 0
        const val EXPORT_TIMEOUT_MS = 20_000L
        const val PREVIEW_HOLD_US = 250_000L
    }
}
