package com.example.engine.ai.tracking

import com.example.engine.ai.MotionKeyframe
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max

/** Converts a reference->current transform into normalised position / scale / rotation / corner-pin. */
class TrackFrameConverter(
    private val refCorners: List<Point2>,
    private val imageWidth: Int,
    private val imageHeight: Int,
    private val followPosition: Boolean = true,
    private val followScale: Boolean = true,
    private val followRotation: Boolean = true
) {
    private val refCx = refCorners.map { it.x.toDouble() }.average()
    private val refCy = refCorners.map { it.y.toDouble() }.average()

    fun toKeyframe(timestampUs: Long, frame: PlaneTrackFrame, emitCornerPin: Boolean): MotionKeyframe {
        val h = frame.transform
        val c = h.apply(refCx, refCy)
        val j = h.jacobianAt(refCx, refCy)
        val sx = hypot(j[0], j[2])
        val sy = hypot(j[1], j[3])
        val rot = Math.toDegrees(atan2(j[2], j[0]))

        val cx = if (followPosition) c[0] / imageWidth else refCx / imageWidth
        val cy = if (followPosition) c[1] / imageHeight else refCy / imageHeight
        val pin = if (emitCornerPin) {
            refCorners.map {
                val p = h.apply(it.x.toDouble(), it.y.toDouble())
                Pair((p[0] / imageWidth).toFloat(), (p[1] / imageHeight).toFloat())
            }
        } else emptyList()

        return MotionKeyframe(
            timestampUs = timestampUs,
            centerX = cx.toFloat(),
            centerY = cy.toFloat(),
            scaleX = if (followScale) sx.toFloat().coerceIn(0.05f, 20f) else 1f,
            scaleY = if (followScale) sy.toFloat().coerceIn(0.05f, 20f) else 1f,
            rotationDeg = if (followRotation) rot.toFloat() else 0f,
            confidence = frame.confidence,
            cornerPin = pin,
            bandMotion = frame.bandMotion.toList()
        )
    }
}

/**
 * Zero-phase track smoothing: Hampel outlier removal followed by a confidence-weighted
 * Gaussian kernel (no lag, unlike a trailing moving average). Rotation is unwrapped first so
 * the 179 -> -179 degree crossing does not smear.
 */
object TrackSmoother {

    fun smooth(raw: List<MotionKeyframe>, smoothing: Float): List<MotionKeyframe> {
        if (raw.size < 3 || smoothing <= 0.05f) return raw
        val n = raw.size
        val x = DoubleArray(n) { raw[it].centerX.toDouble() }
        val y = DoubleArray(n) { raw[it].centerY.toDouble() }
        val sx = DoubleArray(n) { raw[it].scaleX.toDouble() }
        val sy = DoubleArray(n) { raw[it].scaleY.toDouble() }
        val rot = unwrap(DoubleArray(n) { raw[it].rotationDeg.toDouble() })
        val w = DoubleArray(n) { max(raw[it].confidence.toDouble(), 0.05) }

        for (arr in listOf(x, y, sx, sy, rot)) hampel(arr, 2, 3.0)

        val sigma = (smoothing.coerceIn(0f, 1f) * 3.0).coerceAtLeast(0.3)
        val fx = gaussian(x, w, sigma)
        val fy = gaussian(y, w, sigma)
        val fsx = gaussian(sx, w, sigma)
        val fsy = gaussian(sy, w, sigma)
        val frot = gaussian(rot, w, sigma)

        return raw.mapIndexed { i, k ->
            k.copy(
                centerX = fx[i].toFloat(),
                centerY = fy[i].toFloat(),
                scaleX = fsx[i].toFloat(),
                scaleY = fsy[i].toFloat(),
                rotationDeg = frot[i].toFloat()
            )
        }
    }

    fun unwrap(deg: DoubleArray): DoubleArray {
        val out = deg.copyOf()
        for (i in 1 until out.size) {
            var d = out[i] - out[i - 1]
            while (d > 180.0) { out[i] -= 360.0; d -= 360.0 }
            while (d < -180.0) { out[i] += 360.0; d += 360.0 }
        }
        return out
    }

    fun gaussian(v: DoubleArray, weights: DoubleArray, sigma: Double): DoubleArray {
        val n = v.size
        val radius = (sigma * 3).toInt().coerceAtLeast(1)
        val out = DoubleArray(n)
        for (i in 0 until n) {
            var sum = 0.0
            var wsum = 0.0
            for (j in (i - radius).coerceAtLeast(0)..(i + radius).coerceAtMost(n - 1)) {
                val d = (j - i).toDouble()
                val g = exp(-(d * d) / (2 * sigma * sigma)) * weights[j]
                sum += g * v[j]
                wsum += g
            }
            out[i] = if (wsum > 0) sum / wsum else v[i]
        }
        return out
    }

    /** Replaces samples that deviate > nSigma robust deviations from the local median. */
    fun hampel(v: DoubleArray, halfWindow: Int, nSigma: Double) {
        val n = v.size
        val src = v.copyOf()
        for (i in 0 until n) {
            val lo = (i - halfWindow).coerceAtLeast(0)
            val hi = (i + halfWindow).coerceAtMost(n - 1)
            val win = src.copyOfRange(lo, hi + 1).also { it.sort() }
            val med = win[win.size / 2]
            val dev = DoubleArray(win.size) { abs(win[it] - med) }.also { it.sort() }
            val mad = dev[dev.size / 2]
            val limit = nSigma * 1.4826 * max(mad, 1e-3)
            if (abs(src[i] - med) > limit) v[i] = med
        }
    }
}

/** Per-frame correction that cancels camera shake (Warp-Stabilizer style, translation/rotation/scale). */
data class StabilizeCorrection(
    val timestampUs: Long,
    /** Normalised translation to apply to the footage. */
    val dx: Float,
    val dy: Float,
    val rotationDeg: Float,
    val scale: Float,
    /**
     * Rolling-shutter row skew, applied in screen NDC before the similarity: x' = x + rsX * y.
     * Rows are read out top to bottom, so camera motion shears the picture; this undoes it.
     */
    val rsX: Float = 0f,
    /** Rolling-shutter vertical stretch: y' = y * (1 + rsY) (vertical camera motion stretches/squashes the frame). */
    val rsY: Float = 0f,
    /** Perspective terms (bottom row of the homography in NDC): w = 1 + persX * x + persY * y. */
    val persX: Float = 0f,
    val persY: Float = 0f,
    /**
     * Rolling-shutter curvature: the row correction is not only linear in the row height but also quadratic,
     * x' = x + rsX*y + rsX2*y^2 and y' = y*(1 + rsY) + rsY2*y^2. This is what lets fast vibration (jelly),
     * where the camera speed changes inside one frame, be corrected instead of only a constant-speed skew.
     */
    val rsX2: Float = 0f,
    val rsY2: Float = 0f
)

object Stabilizer {

    /** Default full-frame sensor readout time of a phone camera (top row -> bottom row); only an estimate. */
    const val DEFAULT_READOUT_US = 20_000.0

    /** Kept for source compatibility; same as [DEFAULT_READOUT_US]. Pass `readoutUs` to [solve] to override. */
    const val ROLLING_SHUTTER_READOUT_US = DEFAULT_READOUT_US

    /** Accepted readout range: ~1 ms (fast stacked sensors) to 60 ms (slow/old sensors). */
    const val MIN_READOUT_US = 1_000.0
    const val MAX_READOUT_US = 60_000.0
    private const val MAX_RS = 0.08
    private const val MAX_RS_QUAD = 0.08
    /** High-pass width (frames) that separates intra-frame vibration from the slower velocity-based skew. */
    private const val BAND_HP_SIGMA = 3.0
    private const val MAX_PERSPECTIVE = 0.25

    /**
     * [path] is the tracked camera path (e.g. a SIMILARITY track of the whole frame).
     * [smoothness] 0..1 -> how aggressively the camera path is flattened.
     *
     * When [warp] is true and the path carries planar corner pins (PLANAR track), the solve also produces
     *  - perspective correction: the tracked quad is pulled towards its smoothed shape, and the resulting
     *    homography's projective part (persX/persY) is stored;
     *  - rolling-shutter correction: the camera velocity measured between frames times the sensor readout
     *    time gives the skew/stretch between the top and the bottom row (rsX/rsY).
     * [rollingShutter] scales the rolling-shutter part (0 = off, 1 = full).
     * [readoutUs] is this camera's sensor readout time in microseconds (clamped to MIN..MAX_READOUT_US).
     * The default (20 ms) is only an estimate; phones differ, so pass the real value when it is known.
     */
    fun solve(
        path: List<MotionKeyframe>,
        smoothness: Float,
        warp: Boolean = false,
        rollingShutter: Float = 1f,
        readoutUs: Double = DEFAULT_READOUT_US
    ): List<StabilizeCorrection> {
        if (path.isEmpty()) return emptyList()
        val n = path.size
        val sigma = (smoothness.coerceIn(0f, 1f) * 12.0).coerceAtLeast(0.5)
        val w = DoubleArray(n) { 1.0 }
        val x = DoubleArray(n) { path[it].centerX.toDouble() }
        val y = DoubleArray(n) { path[it].centerY.toDouble() }
        val rot = TrackSmoother.unwrap(DoubleArray(n) { path[it].rotationDeg.toDouble() })
        val sc = DoubleArray(n) { path[it].scaleX.toDouble().coerceAtLeast(1e-3) }

        val sxp = TrackSmoother.gaussian(x, w, sigma)
        val syp = TrackSmoother.gaussian(y, w, sigma)
        val srp = TrackSmoother.gaussian(rot, w, sigma)
        val ssp = TrackSmoother.gaussian(sc, w, sigma)

        val pers = if (warp) perspectiveTerms(path, w, sigma) else null
        val bandTerms = if (warp && rollingShutter > 0f) intraFrameTerms(path, rollingShutter, readoutUs) else null
        val rs = if (warp && rollingShutter > 0f) {
            rollingShutterTerms(path, rollingShutter, readoutUs, if (bandTerms != null) 3.0 else 0.8)
        } else null

        return List(n) { i ->
            StabilizeCorrection(
                timestampUs = path[i].timestampUs,
                // Band shift (NDC) -> normalised: x is 2 NDC per frame width; y is flipped (NDC up, frame y down).
                dx = (sxp[i] - x[i] + (bandTerms?.tx?.get(i) ?: 0.0) / 2.0).toFloat(),
                dy = (syp[i] - y[i] - (bandTerms?.ty?.get(i) ?: 0.0) / 2.0).toFloat(),
                rotationDeg = (srp[i] - rot[i]).toFloat(),
                scale = (ssp[i] / sc[i]).toFloat(),
                rsX = ((rs?.get(0)?.get(i) ?: 0.0) + (bandTerms?.lx?.get(i) ?: 0.0)).coerceIn(-MAX_RS, MAX_RS).toFloat(),
                rsY = ((rs?.get(1)?.get(i) ?: 0.0) + (bandTerms?.ly?.get(i) ?: 0.0)).coerceIn(-MAX_RS, MAX_RS).toFloat(),
                persX = pers?.get(0)?.get(i)?.toFloat() ?: 0f,
                persY = pers?.get(1)?.get(i)?.toFloat() ?: 0f,
                rsX2 = bandTerms?.qx?.get(i)?.toFloat() ?: 0f,
                rsY2 = bandTerms?.qy?.get(i)?.toFloat() ?: 0f
            )
        }
    }

    /** Corner pin (normalised, y down) -> NDC points [x0,y0,x1,y1,...]; null when the key has no pin. */
    private fun cornersNdc(k: MotionKeyframe): DoubleArray? {
        if (k.cornerPin.size < 4) return null
        val out = DoubleArray(8)
        for (c in 0 until 4) {
            out[2 * c] = k.cornerPin[c].first * 2.0 - 1.0
            out[2 * c + 1] = 1.0 - k.cornerPin[c].second * 2.0
        }
        return out
    }

    /**
     * Per-frame projective part of the homography that maps the measured tracked quad onto its
     * temporally smoothed counterpart. Returns [persX, persY] (null if the track has no corner pins).
     */
    private fun perspectiveTerms(path: List<MotionKeyframe>, w: DoubleArray, sigma: Double): List<DoubleArray>? {
        val n = path.size
        val raw = path.map { cornersNdc(it) ?: return null }
        val smooth = Array(8) { c ->
            TrackSmoother.gaussian(DoubleArray(n) { raw[it][c] }, w, sigma)
        }
        val px = DoubleArray(n)
        val py = DoubleArray(n)
        val idx = intArrayOf(0, 1, 2, 3)
        for (i in 0 until n) {
            val dst = DoubleArray(8) { smooth[it][i] }
            val h = RobustEstimator.fitHomography(raw[i], dst, idx) ?: continue
            // h is row-major with h[8] == 1; h[6], h[7] are the projective terms.
            px[i] = h.h[6].coerceIn(-MAX_PERSPECTIVE, MAX_PERSPECTIVE)
            py[i] = h.h[7].coerceIn(-MAX_PERSPECTIVE, MAX_PERSPECTIVE)
        }
        return listOf(px, py)
    }

    /**
     * Rolling shutter: the tracked content moves with velocity v (NDC / microsecond) while the sensor reads
     * rows top -> bottom over READOUT. Row at NDC height y is captured (-y/2 * READOUT) after the frame's
     * centre row, so it is displaced by -v * READOUT * y / 2; the correction shifts it back:
     *   x' = x + (vx * READOUT / 2) * y     (skew)
     *   y' = y * (1 + vy * READOUT / 2)     (vertical stretch)
     * Velocity uses a centred difference of the (lightly smoothed) tracked centroid.
     *
     * This constant-speed model handles slow pan / walking skew. Fast vibration (speed changing inside one
     * frame) is handled by [intraFrameTerms] when the track carries per-band motion (MotionKeyframe.bandMotion).
     *
     * Limitations:
     *  - Without band motion (older tracks) the model is linear and fast jelly is only partially corrected.
     *  - Vibration is only measurable up to about (bands per frame) x (frame rate) / 2 Hz.
     *  - The readout time defaults to an estimate of 20 ms. The real value differs per phone, so it is a
     *    parameter ([solve]'s `readoutUs`, set from the Stabilize dialog) instead of a hard-coded constant.
     */
    private fun rollingShutterTerms(path: List<MotionKeyframe>, strength: Float, readoutUs: Double, smoothSigma: Double): List<DoubleArray> {
        val n = path.size
        val kx = DoubleArray(n)
        val ky = DoubleArray(n)
        if (n < 3) return listOf(kx, ky)
        val w = DoubleArray(n) { 1.0 }
        val cx = TrackSmoother.gaussian(DoubleArray(n) { path[it].centerX * 2.0 - 1.0 }, w, smoothSigma)
        val cy = TrackSmoother.gaussian(DoubleArray(n) { 1.0 - path[it].centerY * 2.0 }, w, smoothSigma)
        val s = strength.coerceIn(0f, 1f).toDouble()
        val readout = if (readoutUs.isFinite()) readoutUs.coerceIn(MIN_READOUT_US, MAX_READOUT_US) else DEFAULT_READOUT_US
        for (i in 0 until n) {
            val a = (i - 1).coerceAtLeast(0)
            val b = (i + 1).coerceAtMost(n - 1)
            val dt = (path[b].timestampUs - path[a].timestampUs).toDouble()
            if (dt <= 0.0) continue
            val vx = (cx[b] - cx[a]) / dt
            val vy = (cy[b] - cy[a]) / dt
            kx[i] = (vx * readout / 2.0 * s).coerceIn(-MAX_RS, MAX_RS)
            ky[i] = (vy * readout / 2.0 * s).coerceIn(-MAX_RS, MAX_RS)
        }
        return listOf(kx, ky)
    }

    private class BandTerms(
        val lx: DoubleArray, val ly: DoubleArray, val qx: DoubleArray, val qy: DoubleArray,
        /** Whole-frame shift (NDC, y up) that keeps the mean of the row correction at zero. */
        val tx: DoubleArray, val ty: DoubleArray
    )

    /**
     * Rolling-shutter correction measured from the footage itself.
     *
     * Every row band of frame i is read out at a slightly different moment (top band first), so the
     * band-wise motion between frames i-1 and i is the camera path sampled at K moments per frame instead of one.
     * Per band the displacements are integrated to a path chain; the chains are lined up with a least squares
     * fit that makes the time-ordered interleaved samples as smooth as possible (a straight path leaves no
     * error, so a steady pan does not bias it). The remaining deviation of each band from its frame mean,
     * high-passed to drop integration drift, is the intra-frame wobble. Per frame, the negative of that
     * deviation is fitted over the row height with a + b*y + c*y^2 (y in NDC, up positive):
     * b -> rsX/rsY (added to the velocity-based skew), c -> rsX2/rsY2.
     * Returns null when the track has no usable band measurements.
     */
    private fun intraFrameTerms(path: List<MotionKeyframe>, strength: Float, readoutUs: Double): BandTerms? {
        val n = path.size
        val k = (path.firstOrNull { it.bandMotion.size >= 6 }?.bandMotion?.size ?: return null) / 2
        if (k < 3 || n < 8) return null
        val dts = (1 until n).map { path[it].timestampUs - path[it - 1].timestampUs }.filter { it > 0 }.sorted()
        if (dts.isEmpty()) return null
        val period = dts[dts.size / 2].toDouble()
        val readout = if (readoutUs.isFinite()) readoutUs.coerceIn(MIN_READOUT_US, MAX_READOUT_US) else DEFAULT_READOUT_US
        val frac = readout / period
        val s = strength.coerceIn(0f, 1f).toDouble()

        // Integrated displacement chain per axis (0 = x, 1 = y) and band.
        val cum = Array(2) { Array(k) { DoubleArray(n) } }
        var usable = 0
        for (i in 1 until n) {
            val b = path[i].bandMotion
            val dt = path[i].timestampUs - path[i - 1].timestampUs
            val ok = b.size == 2 * k && dt > 0 && dt <= 1.5 * period
            if (ok) usable++
            for (a in 0..1) for (j in 0 until k) {
                cum[a][j][i] = cum[a][j][i - 1] + if (ok) b[2 * j + a].toDouble() else 0.0
            }
        }
        if (usable < 6) return null

        // Time (in frame units) of band j of frame i; row j is read (j+0.5)/k of the way down the readout.
        val m = n * k
        val time = DoubleArray(m) { val i = it / k; val j = it % k; i + ((j + 0.5) / k - 0.5) * frac }
        val order = (0 until m).sortedBy { time[it] }

        val rowY = DoubleArray(k) { 1.0 - 2.0 * (it + 0.5) / k }
        val dev = Array(2) { Array(k) { DoubleArray(n) } }
        for (a in 0..1) {
            val off = alignChains(cum[a], k, n, time, order)
            val frameMean = DoubleArray(n)
            for (i in 0 until n) { var sum = 0.0; for (j in 0 until k) sum += cum[a][j][i] + off[j]; frameMean[i] = sum / k }
            val w = DoubleArray(n) { 1.0 }
            for (j in 0 until k) {
                val e = DoubleArray(n) { cum[a][j][it] + off[j] - frameMean[it] }
                val low = TrackSmoother.gaussian(e, w, BAND_HP_SIGMA)
                for (i in 0 until n) dev[a][j][i] = e[i] - low[i]
            }
        }

        val lx = DoubleArray(n); val ly = DoubleArray(n); val qx = DoubleArray(n); val qy = DoubleArray(n)
        val tx = DoubleArray(n); val ty = DoubleArray(n)
        val ata = Array(3) { DoubleArray(3) }
        for (j in 0 until k) {
            val v = doubleArrayOf(1.0, rowY[j], rowY[j] * rowY[j])
            for (r in 0..2) for (c in 0..2) ata[r][c] += v[r] * v[c]
        }
        for (i in 0 until n) {
            for (a in 0..1) {
                val atb = DoubleArray(3)
                for (j in 0 until k) {
                    val target = -dev[a][j][i]
                    atb[0] += target; atb[1] += rowY[j] * target; atb[2] += rowY[j] * rowY[j] * target
                }
                val coef = RobustEstimator.solveLinear(Array(3) { ata[it].copyOf() }, atb) ?: continue
                val lin = (coef[1] * s).coerceIn(-MAX_RS, MAX_RS)
                val quad = (coef[2] * s).coerceIn(-MAX_RS_QUAD, MAX_RS_QUAD)
                val shift = coef[0] * s
                if (a == 0) { lx[i] = lin; qx[i] = quad; tx[i] = shift } else { ly[i] = lin; qy[i] = quad; ty[i] = shift }
            }
        }
        return BandTerms(lx, ly, qx, qy, tx, ty)
    }

    /**
     * Offsets that line up the band chains (offset of band 0 is 0): minimises how far each time-ordered sample
     * sits from the straight line between its two neighbours. Exact for any straight path.
     */
    private fun alignChains(chain: Array<DoubleArray>, k: Int, n: Int, time: DoubleArray, order: List<Int>): DoubleArray {
        val ata = Array(k - 1) { DoubleArray(k - 1) }
        val atb = DoubleArray(k - 1)
        fun value(idx: Int) = chain[idx % k][idx / k]
        val bands = IntArray(3)
        val coef = DoubleArray(3)
        for (s in 1 until order.size - 1) {
            val ia = order[s - 1]; val ib = order[s]; val ic = order[s + 1]
            val h1 = time[ib] - time[ia]
            val h2 = time[ic] - time[ib]
            if (h1 <= 1e-9 || h2 <= 1e-9) continue
            coef[0] = 1.0; coef[1] = -h2 / (h1 + h2); coef[2] = -h1 / (h1 + h2)
            bands[0] = ib % k; bands[1] = ia % k; bands[2] = ic % k
            val base = value(ib) + coef[1] * value(ia) + coef[2] * value(ic)
            for (p in 0..2) {
                if (bands[p] == 0) continue
                atb[bands[p] - 1] -= coef[p] * base
                for (q in 0..2) {
                    if (bands[q] == 0) continue
                    ata[bands[p] - 1][bands[q] - 1] += coef[p] * coef[q]
                }
            }
        }
        for (d in 0 until k - 1) ata[d][d] += 1e-9
        val sol = RobustEstimator.solveLinear(ata, atb) ?: DoubleArray(k - 1)
        return DoubleArray(k) { if (it == 0) 0.0 else sol[it - 1] }
    }
}
