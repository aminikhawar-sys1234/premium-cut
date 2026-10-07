package com.example.engine.ai.tracking

import kotlin.math.hypot

enum class PlaneModel { SIMILARITY, HOMOGRAPHY }

data class PlaneTrackFrame(
    /** Reference-frame -> current-frame transform in pixel coordinates. */
    val transform: Homography,
    val confidence: Float,
    val inliers: Int,
    val lost: Boolean,
    /** Per row band content motion since the previous frame, see MotionKeyframe.bandMotion. Empty if unmeasurable. */
    val bandMotion: FloatArray = FloatArray(0)
)

/** Number of horizontal row bands used to sample intra-frame (rolling-shutter) motion. */
const val TRACK_ROW_BANDS = 6
private const val MIN_BAND_POINTS = 3

private class TrackPoint(
    val plane: Point2,
    var x: Float,
    var y: Float,
    /** True when the point has a patch in the reference image and can be drift-corrected against it. */
    val anchored: Boolean
)

/**
 * Reference-anchored planar tracker (the technique behind After Effects' Mocha / Premiere
 * corner-pin tracking):
 *  1. Shi-Tomasi features are seeded inside the user region on the reference frame.
 *  2. Each new frame: frame-to-frame pyramidal LK with forward-backward validation.
 *  3. A RANSAC similarity / homography rejects outliers (moving foreground, bad matches).
 *  4. Anchored points are re-tracked straight from the reference frame, which removes
 *     accumulated drift.
 *  5. Lost points are replenished inside the warped region; if everything is lost the tracker
 *     tries to re-acquire from the reference (survives short occlusions).
 */
class PlaneTracker(
    referenceImage: GrayImage,
    private val region: List<Point2>,
    private val model: PlaneModel,
    private val maxFeatures: Int = 120,
    private val minInliers: Int = 5,
    private val pyramidLevels: Int = 4
) {
    private val lk = LucasKanade()
    private val refPyr = ImagePyramid(referenceImage, pyramidLevels)
    private var prevPyr: ImagePyramid = refPyr
    private val refPlanePoints: List<Point2>
    private var points = ArrayList<TrackPoint>()
    private var total: Homography = Homography.identity()
    private var lastVelocity = doubleArrayOf(0.0, 0.0)

    val initialFeatureCount: Int
    val isReady: Boolean get() = initialFeatureCount >= minInliers

    init {
        require(region.size >= 3) { "Region needs at least 3 corners" }
        val feats = FeatureDetector.detect(
            referenceImage, maxFeatures, mask = { x, y -> pointInPolygon(x, y, region) }
        )
        refPlanePoints = feats
        initialFeatureCount = feats.size
        for (p in feats) points.add(TrackPoint(p, p.x, p.y, anchored = true))
    }

    /** Region corners mapped through the current transform (pixel coords). */
    fun currentRegion(): List<Point2> = region.map {
        val p = total.apply(it.x.toDouble(), it.y.toDouble())
        Point2(p[0].toFloat(), p[1].toFloat())
    }

    fun process(frame: GrayImage): PlaneTrackFrame {
        val currPyr = ImagePyramid(frame, pyramidLevels)

        // 1. frame-to-frame LK
        val survivors = ArrayList<TrackPoint>()
        val oldPos = ArrayList<Point2>()
        for (tp in points) {
            val r = lk.trackValidated(
                prevPyr, currPyr, tp.x, tp.y,
                guessDx = lastVelocity[0].toFloat(), guessDy = lastVelocity[1].toFloat()
            )
            if (r.ok) {
                oldPos.add(Point2(tp.x, tp.y))
                tp.x = r.x
                tp.y = r.y
                survivors.add(tp)
            }
        }

        var step: Homography? = null
        var kept: List<TrackPoint> = emptyList()
        var bands = FloatArray(0)
        if (survivors.size >= minInliers) {
            val src = DoubleArray(survivors.size * 2)
            val dst = DoubleArray(survivors.size * 2)
            for (i in survivors.indices) {
                src[2 * i] = oldPos[i].x.toDouble(); src[2 * i + 1] = oldPos[i].y.toDouble()
                dst[2 * i] = survivors[i].x.toDouble(); dst[2 * i + 1] = survivors[i].y.toDouble()
            }
            val est = estimate(src, dst, 2.0)
            if (est != null) {
                step = est.first
                kept = survivors.filterIndexed { i, _ -> est.second[i] }
                bands = measureBands(oldPos, survivors, est.second, frame.width, frame.height)
            }
        }

        var lost = false
        var inliers = 0
        if (step != null && kept.size >= minInliers) {
            // 2. predicted transform + anchored refinement against the reference frame
            val predicted = step.compose(total)
            val refined = ArrayList<TrackPoint>(kept.size)
            for (tp in kept) {
                if (tp.anchored) {
                    val pp = predicted.apply(tp.plane.x.toDouble(), tp.plane.y.toDouble())
                    val r = lk.trackValidated(
                        refPyr, currPyr, tp.plane.x, tp.plane.y,
                        guessDx = (pp[0] - tp.plane.x).toFloat(),
                        guessDy = (pp[1] - tp.plane.y).toFloat(),
                        fbThreshold = 1.5f
                    )
                    if (r.ok && hypot(r.x - tp.x, r.y - tp.y) < 3f) {
                        tp.x = r.x
                        tp.y = r.y
                    }
                }
                refined.add(tp)
            }

            // 3. final plane -> current estimate straight from plane coordinates
            val src = DoubleArray(refined.size * 2)
            val dst = DoubleArray(refined.size * 2)
            for (i in refined.indices) {
                src[2 * i] = refined[i].plane.x.toDouble(); src[2 * i + 1] = refined[i].plane.y.toDouble()
                dst[2 * i] = refined[i].x.toDouble(); dst[2 * i + 1] = refined[i].y.toDouble()
            }
            val fin = estimate(src, dst, 2.0)
            if (fin != null) {
                val prevCenter = centerOf(currentRegionFor(total))
                total = fin.first
                points = ArrayList(refined.filterIndexed { i, _ -> fin.second[i] })
                inliers = points.size
                val newCenter = centerOf(currentRegionFor(total))
                lastVelocity = doubleArrayOf(newCenter[0] - prevCenter[0], newCenter[1] - prevCenter[1])
            } else {
                total = predicted
                points = ArrayList(refined)
                inliers = refined.size
            }
        } else {
            // Everything lost: try to re-acquire from the reference frame.
            val re = reacquire(currPyr)
            if (re != null) {
                total = re.first
                points = re.second
                inliers = points.size
            } else {
                lost = true
                points = ArrayList()
            }
        }

        // 4. replenish features inside the current region
        if (!lost && points.size < maxFeatures / 2) {
            replenish(frame)
        }

        prevPyr = currPyr
        val density = (inliers / 12f).coerceIn(0f, 1f)
        val ratio = if (survivors.isEmpty()) 0f else (inliers.toFloat() / maxOf(survivors.size, inliers)).coerceIn(0f, 1f)
        val conf = if (lost) 0f else (0.5f * density + 0.5f * ratio).coerceIn(0.05f, 1f)
        return PlaneTrackFrame(total, conf, inliers, lost, if (lost) FloatArray(0) else bands)
    }

    /**
     * Mean displacement of the inlier features per row band (by their previous row), normalised to NDC.
     * Bands without enough points copy the nearest measured band; fewer than 3 measured bands -> empty.
     */
    private fun measureBands(old: List<Point2>, now: List<TrackPoint>, inlier: BooleanArray, w: Int, h: Int): FloatArray {
        val k = TRACK_ROW_BANDS
        val sx = DoubleArray(k)
        val sy = DoubleArray(k)
        val cnt = IntArray(k)
        for (i in now.indices) {
            if (i >= inlier.size || !inlier[i]) continue
            val b = ((old[i].y / h) * k).toInt().coerceIn(0, k - 1)
            sx[b] += (now[i].x - old[i].x).toDouble()
            sy[b] += (now[i].y - old[i].y).toDouble()
            cnt[b]++
        }
        val valid = (0 until k).filter { cnt[it] >= MIN_BAND_POINTS }
        if (valid.size < 3) return FloatArray(0)
        val out = FloatArray(2 * k)
        for (b in 0 until k) {
            val src = if (cnt[b] >= MIN_BAND_POINTS) b else valid.minByOrNull { kotlin.math.abs(it - b) }!!
            out[2 * b] = (2.0 * sx[src] / cnt[src] / w).toFloat()
            out[2 * b + 1] = (-2.0 * sy[src] / cnt[src] / h).toFloat()
        }
        return out
    }

    private fun estimate(src: DoubleArray, dst: DoubleArray, threshold: Double): Pair<Homography, BooleanArray>? {
        return when (model) {
            PlaneModel.SIMILARITY -> {
                val r = RobustEstimator.ransacSimilarity(src, dst, threshold, minInliers = minInliers) ?: return null
                Pair(Homography.fromSimilarity(r.model), r.inliers)
            }
            PlaneModel.HOMOGRAPHY -> {
                val r = RobustEstimator.ransacHomography(src, dst, threshold, minInliers = maxOf(minInliers, 6)) ?: return null
                Pair(r.model, r.inliers)
            }
        }
    }

    private fun reacquire(currPyr: ImagePyramid): Pair<Homography, ArrayList<TrackPoint>>? {
        val cands = ArrayList<TrackPoint>()
        for (p in refPlanePoints) {
            val g = total.apply(p.x.toDouble(), p.y.toDouble())
            val r = lk.trackValidated(
                refPyr, currPyr, p.x, p.y,
                guessDx = (g[0] - p.x).toFloat(), guessDy = (g[1] - p.y).toFloat(), fbThreshold = 1.5f
            )
            if (r.ok) cands.add(TrackPoint(p, r.x, r.y, anchored = true))
        }
        if (cands.size < minInliers) return null
        val src = DoubleArray(cands.size * 2)
        val dst = DoubleArray(cands.size * 2)
        for (i in cands.indices) {
            src[2 * i] = cands[i].plane.x.toDouble(); src[2 * i + 1] = cands[i].plane.y.toDouble()
            dst[2 * i] = cands[i].x.toDouble(); dst[2 * i + 1] = cands[i].y.toDouble()
        }
        val est = estimate(src, dst, 2.0) ?: return null
        val kept = ArrayList(cands.filterIndexed { i, _ -> est.second[i] })
        return if (kept.size >= minInliers) Pair(est.first, kept) else null
    }

    private fun replenish(frame: GrayImage) {
        val inv = total.inverse() ?: return
        val poly = currentRegionFor(total)
        val existing = points
        val feats = FeatureDetector.detect(
            frame, maxFeatures - existing.size,
            mask = { x, y -> pointInPolygon(x, y, poly) && existing.none { hypot(it.x - x, it.y - y) < 6f } }
        )
        for (f in feats) {
            val pl = inv.apply(f.x.toDouble(), f.y.toDouble())
            points.add(TrackPoint(Point2(pl[0].toFloat(), pl[1].toFloat()), f.x, f.y, anchored = false))
        }
    }

    private fun currentRegionFor(h: Homography): List<Point2> = region.map {
        val p = h.apply(it.x.toDouble(), it.y.toDouble())
        Point2(p[0].toFloat(), p[1].toFloat())
    }

    private fun centerOf(poly: List<Point2>): DoubleArray {
        var cx = 0.0; var cy = 0.0
        for (p in poly) { cx += p.x; cy += p.y }
        return doubleArrayOf(cx / poly.size, cy / poly.size)
    }

    companion object {
        fun pointInPolygon(x: Float, y: Float, poly: List<Point2>): Boolean {
            var inside = false
            var j = poly.size - 1
            for (i in poly.indices) {
                val xi = poly[i].x; val yi = poly[i].y
                val xj = poly[j].x; val yj = poly[j].y
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
            return inside
        }
    }
}
