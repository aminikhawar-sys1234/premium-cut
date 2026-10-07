package com.example.engine.ai.tracking

import com.example.engine.ai.MotionKeyframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Plain-JVM tests (no Robolectric): synthetic footage with known ground-truth motion. */
class TrackingCoreTest {

    // ---------- synthetic footage helpers ----------

    private fun blur(src: FloatArray, w: Int, h: Int, sigma: Double): FloatArray {
        val r = (3 * sigma).toInt().coerceAtLeast(1)
        val k = DoubleArray(2 * r + 1) { Math.exp(-((it - r) * (it - r)) / (2 * sigma * sigma)) }
        val sum = k.sum()
        for (i in k.indices) k[i] /= sum
        val tmp = FloatArray(w * h)
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0.0
            for (i in -r..r) s += src[y * w + (x + i).coerceIn(0, w - 1)] * k[i + r]
            tmp[y * w + x] = s.toFloat()
        }
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0.0
            for (i in -r..r) s += tmp[(y + i).coerceIn(0, h - 1) * w + x] * k[i + r]
            out[y * w + x] = s.toFloat()
        }
        return out
    }

    private fun texture(w: Int = 320, h: Int = 240, seed: Long = 7): GrayImage {
        val rnd = Random(seed)
        val n1 = FloatArray(w * h) { rnd.nextFloat() }
        val n2 = FloatArray(w * h) { rnd.nextFloat() }
        val a = blur(n1, w, h, 1.5)
        val b = blur(n2, w, h, 4.0)
        val v = FloatArray(w * h) { a[it] + 0.5f * b[it] }
        val mn = v.min()
        val mx = v.max()
        for (i in v.indices) v[i] = (v[i] - mn) / (mx - mn) * 255f
        return GrayImage(w, h, v)
    }

    /** output(x) = img(H^-1 x) */
    private fun warp(img: GrayImage, hm: Homography): GrayImage {
        val inv = hm.inverse()!!
        val out = FloatArray(img.width * img.height)
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val s = inv.apply(x.toDouble(), y.toDouble())
            out[y * img.width + x] = img.sample(s[0].toFloat(), s[1].toFloat())
        }
        return GrayImage(img.width, img.height, out)
    }

    private fun similarity(tx: Double, ty: Double, deg: Double, scale: Double, cx: Double = 160.0, cy: Double = 120.0): Homography {
        val a = scale * cos(Math.toRadians(deg))
        val b = scale * sin(Math.toRadians(deg))
        return Homography.fromSimilarity(
            Similarity(a, b, cx + tx - a * cx + b * cy, cy + ty - b * cx - a * cy)
        )
    }

    private val corners = listOf(
        doubleArrayOf(90.0, 60.0), doubleArrayOf(230.0, 60.0),
        doubleArrayOf(230.0, 180.0), doubleArrayOf(90.0, 180.0)
    )

    private fun maxCornerError(a: Homography, b: Homography): Double =
        corners.maxOf {
            val p = a.apply(it[0], it[1])
            val q = b.apply(it[0], it[1])
            hypot(p[0] - q[0], p[1] - q[1])
        }

    private fun regionPolygon() = corners.map { Point2(it[0].toFloat(), it[1].toFloat()) }

    // ---------- Lucas-Kanade ----------

    @Test
    fun lucasKanadeRecoversSubPixelTranslation() {
        val img = texture()
        val moved = warp(img, similarity(2.37, -1.61, 0.0, 1.0))
        val p1 = ImagePyramid(img, 4)
        val p2 = ImagePyramid(moved, 4)
        val lk = LucasKanade()
        for ((x, y) in listOf(100f to 80f, 160f to 120f, 220f to 150f, 60f to 60f)) {
            val r = lk.track(p1, p2, x, y)
            assertTrue("point ($x,$y) rejected", r.ok)
            assertEquals(x + 2.37f, r.x, 0.15f)
            assertEquals(y - 1.61f, r.y, 0.15f)
        }
    }

    @Test
    fun lucasKanadePyramidHandlesLargeMotion() {
        val img = texture()
        val moved = warp(img, similarity(10.0, -6.0, 0.0, 1.0))
        val p1 = ImagePyramid(img, 4)
        val p2 = ImagePyramid(moved, 4)
        val r = LucasKanade().track(p1, p2, 160f, 120f)
        assertTrue(r.ok)
        assertEquals(170f, r.x, 0.3f)
        assertEquals(114f, r.y, 0.3f)
    }

    @Test
    fun forwardBackwardCheckRejectsPointsLeavingTheFrame() {
        val img = texture()
        val moved = warp(img, similarity(400.0, 0.0, 0.0, 1.0))
        val r = LucasKanade().trackValidated(ImagePyramid(img, 4), ImagePyramid(moved, 4), 100f, 100f)
        assertFalse(r.ok)
    }

    @Test
    fun lucasKanadeRejectsMatchesOntoUnrelatedTexture() {
        val img = texture(seed = 7)
        val other = texture(seed = 1234)
        val p1 = ImagePyramid(img, 4)
        val p2 = ImagePyramid(other, 4)
        val poly = regionPolygon()
        val feats = FeatureDetector.detect(img, 60, mask = { x, y -> PlaneTracker.pointInPolygon(x, y, poly) })
        val lk = LucasKanade()
        val accepted = feats.count { lk.trackValidated(p1, p2, it.x, it.y).ok }
        assertTrue("accepted $accepted of ${feats.size}", accepted <= 2)
    }

    // ---------- features ----------

    @Test
    fun featureDetectorRespectsMaskAndSpacing() {
        val img = texture()
        val poly = regionPolygon()
        val feats = FeatureDetector.detect(img, 100, minDistance = 6f, mask = { x, y -> PlaneTracker.pointInPolygon(x, y, poly) })
        assertTrue("expected plenty of corners, got ${feats.size}", feats.size >= 20)
        for (f in feats) assertTrue(PlaneTracker.pointInPolygon(f.x, f.y, poly))
        for (i in feats.indices) for (j in i + 1 until feats.size) {
            assertTrue(hypot(feats[i].x - feats[j].x, feats[i].y - feats[j].y) >= 6f - 1e-3f)
        }
    }

    @Test
    fun featureDetectorFindsNothingOnFlatImage() {
        val flat = GrayImage(100, 100, FloatArray(100 * 100) { 128f })
        assertTrue(FeatureDetector.detect(flat, 50).isEmpty())
    }

    // ---------- robust estimation ----------

    private fun noisyPairs(n: Int, outliers: Int, map: (Double, Double) -> DoubleArray): Pair<DoubleArray, DoubleArray> {
        val rnd = Random(99)
        val src = DoubleArray(n * 2)
        val dst = DoubleArray(n * 2)
        for (i in 0 until n) {
            val x = 10 + rnd.nextDouble() * 300
            val y = 10 + rnd.nextDouble() * 220
            val p = map(x, y)
            src[2 * i] = x; src[2 * i + 1] = y
            dst[2 * i] = p[0] + rnd.nextGaussian() * 0.2
            dst[2 * i + 1] = p[1] + rnd.nextGaussian() * 0.2
        }
        for (i in 0 until outliers) {
            dst[2 * i] += (rnd.nextDouble() - 0.5) * 80
            dst[2 * i + 1] += (rnd.nextDouble() - 0.5) * 80
        }
        return src to dst
    }

    @Test
    fun similarityRansacIgnoresOutliers() {
        val truth = similarity(5.0, -3.0, 12.0, 1.2)
        val (src, dst) = noisyPairs(60, 18) { x, y -> truth.apply(x, y) }
        val r = RobustEstimator.ransacSimilarity(src, dst)!!
        val est = Homography.fromSimilarity(r.model)
        assertTrue(maxCornerError(est, truth) < 1.0)
        assertTrue(r.inlierCount >= 38)
        assertEquals(1.2, r.model.scale, 0.01)
        assertEquals(12.0, Math.toDegrees(r.model.rotationRad), 0.3)
    }

    @Test
    fun homographyRansacRecoversPerspective() {
        val truth = Homography(doubleArrayOf(1.05, 0.08, 12.0, -0.06, 0.97, -7.0, 0.0003, -0.0002, 1.0))
        val (src, dst) = noisyPairs(60, 18) { x, y -> truth.apply(x, y) }
        val r = RobustEstimator.ransacHomography(src, dst)!!
        assertTrue("corner error ${maxCornerError(r.model, truth)}", maxCornerError(r.model, truth) < 1.0)
        assertTrue(r.inlierCount >= 38)
    }

    @Test
    fun homographyInverseRoundTrips() {
        val h = Homography(doubleArrayOf(1.05, 0.08, 12.0, -0.06, 0.97, -7.0, 0.0003, -0.0002, 1.0))
        val p = h.apply(120.0, 90.0)
        val q = h.inverse()!!.apply(p[0], p[1])
        assertEquals(120.0, q[0], 1e-6)
        assertEquals(90.0, q[1], 1e-6)
    }

    // ---------- full planar tracker ----------

    @Test
    fun planeTrackerFollowsTranslationRotationScale() {
        val img = texture()
        val tracker = PlaneTracker(img, regionPolygon(), PlaneModel.SIMILARITY)
        assertTrue(tracker.isReady)
        var worst = 0.0
        for (t in 1 until 30) {
            val truth = similarity(4.0 * t, -2.0 * t, 0.8 * t, 1.004.pow(t))
            val f = tracker.process(warp(img, truth))
            assertFalse("lost at frame $t", f.lost)
            worst = maxOf(worst, maxCornerError(f.transform, truth))
        }
        assertTrue("worst corner error $worst px", worst < 1.5)
    }

    @Test
    fun planeTrackerFollowsPerspectiveChange() {
        val img = texture()
        val tracker = PlaneTracker(img, regionPolygon(), PlaneModel.HOMOGRAPHY)
        assertTrue(tracker.isReady)
        val c = Homography(doubleArrayOf(1.0, 0.0, 160.0, 0.0, 1.0, 120.0, 0.0, 0.0, 1.0))
        val ci = Homography(doubleArrayOf(1.0, 0.0, -160.0, 0.0, 1.0, -120.0, 0.0, 0.0, 1.0))
        var worst = 0.0
        for (t in 1 until 30) {
            val p = Homography(
                doubleArrayOf(
                    1 + 0.0015 * t, 0.003 * t, 3.0 * t,
                    -0.002 * t, 1 - 0.001 * t, -1.5 * t,
                    0.00002 * t, -0.00001 * t, 1.0
                )
            )
            val truth = c.compose(p).compose(ci)
            val f = tracker.process(warp(img, truth))
            assertFalse("lost at frame $t", f.lost)
            worst = maxOf(worst, maxCornerError(f.transform, truth))
        }
        assertTrue("worst corner error $worst px", worst < 1.5)
    }

    @Test
    fun planeTrackerReportsLostOnUnrelatedFootage() {
        val img = texture(seed = 7)
        val tracker = PlaneTracker(img, regionPolygon(), PlaneModel.SIMILARITY)
        val other = texture(seed = 1234)
        var lost = false
        repeat(3) { lost = tracker.process(other).lost }
        assertTrue(lost)
    }

    @Test
    fun planeTrackerNotReadyOnFlatRegion() {
        val flat = GrayImage(320, 240, FloatArray(320 * 240) { 90f })
        assertFalse(PlaneTracker(flat, regionPolygon(), PlaneModel.SIMILARITY).isReady)
    }

    // ---------- converter / smoothing / stabilizer ----------

    @Test
    fun converterIdentityGivesInitialCornerPinAndUnitScale() {
        val conv = TrackFrameConverter(regionPolygon(), 320, 240)
        val k = conv.toKeyframe(0L, PlaneTrackFrame(Homography.identity(), 1f, 50, false), true)
        assertEquals(160f / 320f, k.centerX, 1e-4f)
        assertEquals(120f / 240f, k.centerY, 1e-4f)
        assertEquals(1f, k.scaleX, 1e-4f)
        assertEquals(0f, k.rotationDeg, 1e-3f)
        assertEquals(4, k.cornerPin.size)
        assertEquals(90f / 320f, k.cornerPin[0].first, 1e-4f)
        assertEquals(180f / 240f, k.cornerPin[3].second, 1e-4f)
    }

    @Test
    fun converterReadsScaleAndRotationFromTransform() {
        val conv = TrackFrameConverter(regionPolygon(), 320, 240)
        val h = similarity(0.0, 0.0, 30.0, 1.5)
        val k = conv.toKeyframe(0L, PlaneTrackFrame(h, 1f, 50, false), false)
        assertEquals(1.5f, k.scaleX, 1e-3f)
        assertEquals(30f, k.rotationDeg, 0.01f)
    }

    private fun key(i: Int, x: Float, rot: Float = 0f, conf: Float = 1f) =
        MotionKeyframe(timestampUs = i * 33_333L, centerX = x, centerY = 0.5f, rotationDeg = rot, confidence = conf)

    @Test
    fun smootherRemovesSingleFrameSpike() {
        val raw = (0 until 15).map { key(it, 0.2f + 0.02f * it) }.toMutableList()
        raw[7] = raw[7].copy(centerX = 0.9f)
        val out = TrackSmoother.smooth(raw, 0.5f)
        assertEquals(0.2f + 0.02f * 7, out[7].centerX, 0.03f)
    }

    @Test
    fun smootherKeepsLinearMotionWithoutLag() {
        val raw = (0 until 21).map { key(it, 0.1f + 0.03f * it) }
        val out = TrackSmoother.smooth(raw, 0.6f)
        // Gaussian smoothing of a straight line is the line itself away from the ends.
        for (i in 5..15) assertEquals(raw[i].centerX, out[i].centerX, 1e-3f)
    }

    @Test
    fun smootherDoesNotSmearRotationAcrossWrap() {
        val rots = floatArrayOf(176f, 178f, 180f, -178f, -176f, -174f, -172f, -170f)
        val raw = rots.mapIndexed { i, r -> key(i, 0.5f, r) }
        val out = TrackSmoother.smooth(raw, 0.5f)
        // Unwrapped smoothing keeps magnitudes near 180, never collapsing towards 0.
        for (k in out) assertTrue("rotation ${k.rotationDeg}", abs(k.rotationDeg) > 150f)
    }

    @Test
    fun stabilizerReducesJitter() {
        val rnd = Random(5)
        val path = (0 until 60).map { i ->
            key(i, 0.3f + 0.004f * i + (rnd.nextFloat() - 0.5f) * 0.02f, (rnd.nextFloat() - 0.5f) * 2f)
        }
        val corr = Stabilizer.solve(path, 0.8f)
        fun roughness(v: List<Double>): Double {
            var s = 0.0
            for (i in 1 until v.size - 1) s += (v[i + 1] - 2 * v[i] + v[i - 1]).pow(2)
            return sqrt(s / (v.size - 2))
        }
        val before = roughness(path.map { it.centerX.toDouble() })
        val after = roughness(path.indices.map { path[it].centerX + corr[it].dx.toDouble() })
        assertTrue("before=$before after=$after", after < before * 0.35)
        val rotBefore = roughness(path.map { it.rotationDeg.toDouble() })
        val rotAfter = roughness(path.indices.map { path[it].rotationDeg + corr[it].rotationDeg.toDouble() })
        assertTrue(rotAfter < rotBefore * 0.35)
    }
}
