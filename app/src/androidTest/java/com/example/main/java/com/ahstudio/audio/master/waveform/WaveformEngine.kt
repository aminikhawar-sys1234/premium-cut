package com.ahstudio.audio.master.waveform

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.ahstudio.audio.master.cache.DecodedAudioCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.util.LruCache
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class WaveformData(
    val sourceId: String, val sampleRate: Int, val bucketsPerSecond: Int,
    val min: FloatArray, val max: FloatArray, val rms: FloatArray,
) {
    val bucketCount: Int get() = min.size
    val durationSec: Double get() = bucketCount.toDouble() / bucketsPerSecond
    fun bucketAt(sec: Double): Int = (sec * bucketsPerSecond).toInt().coerceIn(0, bucketCount - 1)
    fun peakBetween(startSec: Double, endSec: Double): Pair<Float, Float> {
        var lo = 0f; var hi = 0f
        val a = bucketAt(startSec); val b = bucketAt(endSec)
        for (i in a..b) { if (min[i] < lo) lo = min[i]; if (max[i] > hi) hi = max[i] }
        return lo to hi
    }
    fun downsample(factor: Int, newSourceId: String = sourceId): WaveformData {
        val f = factor.coerceAtLeast(1)
        val n = (bucketCount / f).coerceAtLeast(1)
        val mn = FloatArray(n); val mx = FloatArray(n); val rm = FloatArray(n)
        for (i in 0 until n) {
            var lo = 0f; var hi = 0f; var r = 0f
            for (j in 0 until f) {
                val k = i * f + j; if (k >= bucketCount) break
                if (min[k] < lo) lo = min[k]; if (max[k] > hi) hi = max[k]; r += rms[k] * rms[k]
            }
            mn[i] = lo; mx[i] = hi; rm[i] = sqrt(r / f)
        }
        val newBps = (bucketsPerSecond / f).coerceAtLeast(1)
        return WaveformData(newSourceId, sampleRate, newBps, mn, mx, rm)
    }
}

object WaveformGenerator {
    fun generate(sourceId: String, pcm: Array<FloatArray>, sampleRate: Int, bucketsPerSecond: Int): WaveformData {
        val n = pcm[0].size
        val bucketSamples = max(1, sampleRate / bucketsPerSecond)
        val count = (n + bucketSamples - 1) / bucketSamples
        val mn = FloatArray(count); val mx = FloatArray(count); val rm = FloatArray(count)
        for (b in 0 until count) {
            val s = b * bucketSamples; val e = min(n, s + bucketSamples)
            var lo = 0f; var hi = 0f; var sum = 0.0
            for (i in s until e) {
                var v = 0f
                for (c in pcm) { val x = c[i]; if (x > v) v = x }
                var loC = 0f
                for (c in pcm) { val x = c[i]; if (x < loC) loC = x }
                if (loC < lo) lo = loC
                if (v > hi) hi = v
                sum += v.toDouble() * v
            }
            mn[b] = lo; mx[b] = hi; rm[b] = sqrt((sum / (e - s).coerceAtLeast(1)).toFloat())
        }
        return WaveformData(sourceId, sampleRate, sampleRate / bucketSamples, mn, mx, rm)
    }
}

object WaveformCacheStore {
    fun save(dir: File, d: WaveformData): File? = try {
        val f = File(dir, "wf_" + d.sourceId.hashCode().toUInt() + ".ahwf")
        java.io.DataOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(f))).use { o ->
            o.writeInt(0x41485746); o.writeInt(1); o.writeInt(d.sampleRate); o.writeInt(d.bucketsPerSecond); o.writeInt(d.bucketCount)
            for (i in 0 until d.bucketCount) { o.writeFloat(d.min[i]); o.writeFloat(d.max[i]); o.writeFloat(d.rms[i]) }
        }
        f
    } catch (_: Exception) { null }

    fun load(dir: File, sourceId: String): WaveformData? = try {
        val f = File(dir, "wf_" + sourceId.hashCode().toUInt() + ".ahwf")
        if (!f.exists()) null else java.io.DataInputStream(java.io.BufferedInputStream(java.io.FileInputStream(f))).use { i ->
            if (i.readInt() != 0x41485746) return null
            i.readInt(); val sr = i.readInt(); val bps = i.readInt(); val n = i.readInt()
            val mn = FloatArray(n); val mx = FloatArray(n); val rm = FloatArray(n)
            for (k in 0 until n) { mn[k] = i.readFloat(); mx[k] = i.readFloat(); rm[k] = i.readFloat() }
            WaveformData(sourceId, sr, bps, mn, mx, rm)
        }
    } catch (_: Exception) { null }
}

class WaveformEngine(private val cache: DecodedAudioCache, cacheDir: File, private val scope: CoroutineScope) {
    private val memory = LruCache<String, WaveformData>(32)
    private val dir = File(cacheDir, "waveforms").apply { mkdirs() }
    private val _updates = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val updates: SharedFlow<String> get() = _updates

    suspend fun get(sourceId: String, bucketsPerSecond: Int = 100): WaveformData = withContext(Dispatchers.Default) {
        val key = "$sourceId@$bucketsPerSecond"
        memory.get(key)?.let { return@withContext it }
        WaveformCacheStore.load(dir, sourceId)?.let {
            memory.put(key, it); return@withContext it
        }
        val entry = cache.get(sourceId) ?: throw IllegalStateException("Source not decoded: $sourceId")
        val data = WaveformGenerator.generate(sourceId, entry.data, entry.sampleRate, bucketsPerSecond)
        WaveformCacheStore.save(dir, data)
        memory.put(key, data)
        _updates.tryEmit(sourceId)
        data
    }

    fun requestAsync(sourceId: String, bucketsPerSecond: Int = 100) {
        scope.launch { runCatching { get(sourceId, bucketsPerSecond) } }
    }

    fun invalidate(sourceId: String) {
        val keysToRemove = memory.snapshot().keys.filter { it.startsWith("$sourceId@") }
        for (k in keysToRemove) memory.remove(k)
        File(dir, "wf_" + sourceId.hashCode().toUInt() + ".ahwf").delete()
    }
}

class WaveformRenderer {
    fun draw(canvas: Canvas, data: WaveformData, viewStartSec: Double, secPerPx: Double, widthPx: Int, heightPx: Int, paint: Paint) {
        var d = data
        while (d.bucketCount / (d.durationSec / secPerPx) > 4.0 && d.bucketsPerSecond > 4) d = d.downsample(4)
        val mid = heightPx / 2f
        val path = Path()
        for (x in 0 until widthPx) {
            val t = viewStartSec + x * secPerPx
            val idx = d.bucketAt(t)
            val lo = mid - d.max[idx] * mid
            val hi = mid - d.min[idx] * mid
            path.moveTo(x + 0.5f, lo); path.lineTo(x + 0.5f, hi)
        }
        canvas.drawPath(path, paint)
    }
}
