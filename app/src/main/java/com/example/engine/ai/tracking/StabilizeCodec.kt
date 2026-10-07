package com.example.engine.ai.tracking

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Compact, serialisable form of a stabilisation solve so it saves with the project, undoes with the
 * timeline and is evaluated identically by the preview and the exporter.
 *
 * Format v3: `v3|smoothness|zoom|ts,dx,dy,rot,scale,rsX,rsY,persX,persY,rsX2,rsY2;...`
 * (v2 frames stop after persY, v1 frames after scale; missing fields are 0).
 * (`ts` = source time in microseconds; dx/dy normalised to frame size; rot in degrees; scale = ratio).
 */
data class StabilizeData(
    val smoothness: Float,
    /** Uniform zoom that hides the borders exposed by the corrections. */
    val zoom: Float,
    val frames: List<StabilizeCorrection>
)

object StabilizeCodec {

    private const val VERSION = "v3"
    private const val VERSION_V2 = "v2"
    private const val VERSION_V1 = "v1"
    private const val MAX_ZOOM = 1.5f
    private const val CACHE_SIZE = 8
    private val cache = object : LinkedHashMap<String, StabilizeData?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StabilizeData?>?) = size > CACHE_SIZE
    }

    /** Builds the stored data from solver output, computing the zoom needed to keep edges covered. */
    fun build(smoothness: Float, frames: List<StabilizeCorrection>): StabilizeData {
        var maxShift = 0f
        var maxRot = 0f
        for (f in frames) {
            maxShift = max(maxShift, max(abs(f.dx), abs(f.dy)))
            maxRot = max(maxRot, abs(f.rotationDeg))
        }
        // A shift of d (fraction of the frame) exposes 2d of the border on one side; rotation adds roughly sin(r)/2.
        // Rolling-shutter skew/stretch and perspective pull the frame edges in by roughly their own magnitude.
        var maxWarp = 0f
        for (f in frames) {
            maxWarp = max(maxWarp, abs(f.rsX) + abs(f.rsY) + abs(f.rsX2) + abs(f.rsY2) + 1.5f * (abs(f.persX) + abs(f.persY)))
        }
        val zoom = (1f + 2f * maxShift + Math.toRadians(maxRot.toDouble()).toFloat() * 0.6f + maxWarp).coerceIn(1f, MAX_ZOOM)
        return StabilizeData(smoothness.coerceIn(0f, 1f), zoom, frames)
    }

    fun encode(data: StabilizeData): String {
        val sb = StringBuilder(32 + data.frames.size * 36)
        sb.append(VERSION).append('|')
            .append(String.format(Locale.US, "%.3f", data.smoothness)).append('|')
            .append(String.format(Locale.US, "%.4f", data.zoom)).append('|')
        data.frames.forEachIndexed { i, f ->
            if (i > 0) sb.append(';')
            sb.append(f.timestampUs).append(',')
                .append(String.format(Locale.US, "%.5f", f.dx)).append(',')
                .append(String.format(Locale.US, "%.5f", f.dy)).append(',')
                .append(String.format(Locale.US, "%.3f", f.rotationDeg)).append(',')
                .append(String.format(Locale.US, "%.5f", f.scale)).append(',')
                .append(String.format(Locale.US, "%.5f", f.rsX)).append(',')
                .append(String.format(Locale.US, "%.5f", f.rsY)).append(',')
                .append(String.format(Locale.US, "%.5f", f.persX)).append(',')
                .append(String.format(Locale.US, "%.5f", f.persY)).append(',')
                .append(String.format(Locale.US, "%.5f", f.rsX2)).append(',')
                .append(String.format(Locale.US, "%.5f", f.rsY2))
        }
        return sb.toString()
    }

    /** Decodes (and caches) the stored string; returns null for null / malformed input. */
    fun decode(encoded: String?): StabilizeData? {
        if (encoded.isNullOrBlank()) return null
        synchronized(cache) {
            if (cache.containsKey(encoded)) return cache[encoded]
        }
        val parsed = parse(encoded)
        synchronized(cache) { cache[encoded] = parsed }
        return parsed
    }

    private fun parse(encoded: String): StabilizeData? = try {
        val parts = encoded.split('|', limit = 4)
        if (parts.size < 4 || (parts[0] != VERSION && parts[0] != VERSION_V2 && parts[0] != VERSION_V1)) null
        else {
            val frames = parts[3].split(';').filter { it.isNotBlank() }.map {
                val v = it.split(',')
                if (v.size >= 9) {
                    StabilizeCorrection(
                        v[0].toLong(), v[1].toFloat(), v[2].toFloat(), v[3].toFloat(), v[4].toFloat(),
                        v[5].toFloat(), v[6].toFloat(), v[7].toFloat(), v[8].toFloat(),
                        if (v.size >= 11) v[9].toFloat() else 0f,
                        if (v.size >= 11) v[10].toFloat() else 0f
                    )
                } else {
                    StabilizeCorrection(v[0].toLong(), v[1].toFloat(), v[2].toFloat(), v[3].toFloat(), v[4].toFloat())
                }
            }
            if (frames.isEmpty()) null else StabilizeData(parts[1].toFloat(), parts[2].toFloat(), frames)
        }
    } catch (_: Exception) {
        null
    }

    /** Linearly interpolated correction at [sourceTimeUs]; clamps outside the analysed range. */
    fun sample(data: StabilizeData, sourceTimeUs: Long): StabilizeCorrection {
        val f = data.frames
        if (sourceTimeUs <= f.first().timestampUs) return f.first()
        if (sourceTimeUs >= f.last().timestampUs) return f.last()
        var lo = 0
        var hi = f.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (f[mid].timestampUs <= sourceTimeUs) lo = mid else hi = mid
        }
        val a = f[lo]
        val b = f[hi]
        val span = (b.timestampUs - a.timestampUs).coerceAtLeast(1L)
        val t = (sourceTimeUs - a.timestampUs).toFloat() / span
        return StabilizeCorrection(
            timestampUs = sourceTimeUs,
            dx = a.dx + (b.dx - a.dx) * t,
            dy = a.dy + (b.dy - a.dy) * t,
            rotationDeg = a.rotationDeg + (b.rotationDeg - a.rotationDeg) * t,
            scale = a.scale + (b.scale - a.scale) * t,
            rsX = a.rsX + (b.rsX - a.rsX) * t,
            rsY = a.rsY + (b.rsY - a.rsY) * t,
            persX = a.persX + (b.persX - a.persX) * t,
            persY = a.persY + (b.persY - a.persY) * t,
            rsX2 = a.rsX2 + (b.rsX2 - a.rsX2) * t,
            rsY2 = a.rsY2 + (b.rsY2 - a.rsY2) * t
        )
    }

    /**
     * Writes the 4x4 (column-major) warp that undoes rolling-shutter skew/stretch and perspective, in screen NDC:
     *   x' = x + rsX*y ; y' = y*(1+rsY) ; w = 1 + persX*x + persY*y.
     * Identity when all four terms are zero. Multiply it between the clip's own transforms and the
     * shift/rotate/zoom matrix (it must act on already-positioned screen coordinates).
     */
    fun warpMatrix(c: StabilizeCorrection, out: FloatArray) {
        java.util.Arrays.fill(out, 0f)
        out[0] = 1f; out[5] = 1f + c.rsY; out[10] = 1f; out[15] = 1f
        out[4] = c.rsX      // column 1, row 0: x += rsX * y
        out[3] = c.persX    // column 0, row 3
        out[7] = c.persY    // column 1, row 3
    }

    fun hasWarp(c: StabilizeCorrection): Boolean =
        c.rsX != 0f || c.rsY != 0f || c.persX != 0f || c.persY != 0f || hasRowWarp(c)

    /** True when the correction has a curved (y^2) rolling-shutter part, which a matrix cannot express. */
    fun hasRowWarp(c: StabilizeCorrection): Boolean = c.rsX2 != 0f || c.rsY2 != 0f
}
