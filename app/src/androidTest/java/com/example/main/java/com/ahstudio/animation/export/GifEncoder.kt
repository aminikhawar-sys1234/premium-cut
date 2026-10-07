package com.ahstudio.animation.export

import com.ahstudio.animation.render.Frame
import java.io.ByteArrayOutputStream

/**
 * Animated GIF89a encoder: median-cut palette per frame (local colour tables), optional Floyd-Steinberg dithering,
 * 1-bit transparency, centisecond delays with accumulated rounding so total duration stays exact.
 */
object GifEncoder {
    fun encode(frames: List<Frame>, delaysMs: List<Int>, loops: Int = 0, dither: Boolean = true, alphaThreshold: Int = 128): ByteArray {
        require(frames.isNotEmpty() && frames.size == delaysMs.size) { "frames/delays mismatch" }
        val w = frames[0].width; val h = frames[0].height
        require(frames.all { it.width == w && it.height == h }) { "all frames must be the same size" }
        require(w in 1..65535 && h in 1..65535)
        val o = ByteArrayOutputStream()
        o.write("GIF89a".toByteArray(Charsets.US_ASCII))
        le16(o, w); le16(o, h); o.write(0x70); o.write(0); o.write(0)          // no global colour table
        if (loops >= 0) {                                                        // NETSCAPE2.0 loop extension (0 = forever)
            o.write(0x21); o.write(0xFF); o.write(11); o.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
            o.write(3); o.write(1); le16(o, loops); o.write(0)
        }
        var carry = 0.0
        for ((i, f) in frames.withIndex()) {
            val exact = delaysMs[i] / 10.0 + carry
            val cs = Math.round(exact).toInt().coerceAtLeast(1); carry = exact - cs
            writeFrame(o, f, cs, dither, alphaThreshold)
        }
        o.write(0x3B)
        return o.toByteArray()
    }

    private fun writeFrame(o: ByteArrayOutputStream, f: Frame, delayCs: Int, dither: Boolean, alphaT: Int) {
        val w = f.width; val h = f.height
        val hasTransparent = f.argb.any { (it ushr 24) < alphaT }
        val opaque = ArrayList<Int>(f.argb.size)
        for (p in f.argb) if ((p ushr 24) >= alphaT) opaque.add(p and 0xFFFFFF)
        val maxColors = if (hasTransparent) 255 else 256
        val palette = medianCut(opaque, maxColors)
        val transparentIdx = if (hasTransparent) palette.size else -1
        val total = palette.size + (if (hasTransparent) 1 else 0)
        var bits = 1; while ((1 shl bits) < total) bits++
        bits = bits.coerceIn(1, 8)
        val indices = quantize(f, palette, transparentIdx, dither, alphaT)
        // graphic control extension
        o.write(0x21); o.write(0xF9); o.write(4)
        o.write((if (hasTransparent) 1 else 0) or (if (hasTransparent) (2 shl 2) else (1 shl 2)))   // disposal: 2 restore-bg for alpha, 1 keep otherwise
        le16(o, delayCs); o.write(if (hasTransparent) transparentIdx else 0); o.write(0)
        // image descriptor with local colour table
        o.write(0x2C); le16(o, 0); le16(o, 0); le16(o, w); le16(o, h); o.write(0x80 or (bits - 1))
        for (i in 0 until (1 shl bits)) {
            val c = if (i < palette.size) palette[i] else 0
            o.write((c shr 16) and 255); o.write((c shr 8) and 255); o.write(c and 255)
        }
        val minCode = maxOf(2, bits)
        o.write(minCode)
        val data = lzw(indices, minCode)
        var p = 0
        while (p < data.size) { val n = minOf(255, data.size - p); o.write(n); o.write(data, p, n); p += n }
        o.write(0)
    }

    private fun le16(o: ByteArrayOutputStream, v: Int) { o.write(v and 255); o.write((v shr 8) and 255) }

    // ----- median cut -----
    private fun medianCut(colors: List<Int>, maxColors: Int): List<Int> {
        if (colors.isEmpty()) return listOf(0)
        // histogram first so big flat areas don't dominate the split sizes
        val hist = HashMap<Int, Int>()
        for (c in colors) hist.merge(c, 1, Int::plus)
        if (hist.size <= maxColors) return hist.keys.sorted()
        class Box(val items: MutableList<Int>) {
            fun range(shift: Int): Int { var lo = 255; var hi = 0; for (c in items) { val v = (c shr shift) and 255; if (v < lo) lo = v; if (v > hi) hi = v }; return hi - lo }
            fun widest(): Int { val r = range(16); val g = range(8); val b = range(0); return if (r >= g && r >= b) 16 else if (g >= b) 8 else 0 }
            fun weight(): Long = items.sumOf { hist.getValue(it).toLong() }
        }
        val boxes = arrayListOf(Box(hist.keys.toMutableList()))
        while (boxes.size < maxColors) {
            val target = boxes.filter { it.items.size > 1 }.maxByOrNull { it.weight() * maxOf(1, maxOf(it.range(16), it.range(8), it.range(0))) } ?: break
            val shift = target.widest()
            target.items.sortBy { (it shr shift) and 255 }
            val half = target.weight() / 2; var acc = 0L; var cut = 0
            for ((i, c) in target.items.withIndex()) { acc += hist.getValue(c); if (acc >= half) { cut = i + 1; break } }
            cut = cut.coerceIn(1, target.items.size - 1)
            val right = Box(target.items.subList(cut, target.items.size).toMutableList())
            val left = Box(target.items.subList(0, cut).toMutableList())
            boxes.remove(target); boxes.add(left); boxes.add(right)
        }
        return boxes.map { b ->
            var r = 0L; var g = 0L; var bl = 0L; var n = 0L
            for (c in b.items) { val wgt = hist.getValue(c).toLong(); r += ((c shr 16) and 255) * wgt; g += ((c shr 8) and 255) * wgt; bl += (c and 255) * wgt; n += wgt }
            ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (bl / n).toInt()
        }
    }

    private fun quantize(f: Frame, pal: List<Int>, transparentIdx: Int, dither: Boolean, alphaT: Int): IntArray {
        val w = f.width; val h = f.height
        val out = IntArray(w * h)
        val cache = HashMap<Int, Int>()
        fun nearest(r: Int, g: Int, b: Int): Int {
            val key = (r shl 16) or (g shl 8) or b
            return cache.getOrPut(key) {
                var best = 0; var bd = Int.MAX_VALUE
                for (i in pal.indices) {
                    val c = pal[i]; val dr = r - ((c shr 16) and 255); val dg = g - ((c shr 8) and 255); val db = b - (c and 255)
                    val d = dr * dr * 3 + dg * dg * 4 + db * db * 2
                    if (d < bd) { bd = d; best = i; if (d == 0) break }
                }
                best
            }
        }
        if (!dither || pal.size <= 1) {
            for (i in out.indices) { val p = f.argb[i]; out[i] = if ((p ushr 24) < alphaT) transparentIdx else nearest((p shr 16) and 255, (p shr 8) and 255, p and 255) }
            return out
        }
        var cur = Array(3) { FloatArray(w + 2) }; var nxt = Array(3) { FloatArray(w + 2) }
        for (y in 0 until h) {
            for (c in 0..2) nxt[c].fill(0f)
            for (x in 0 until w) {
                val p = f.argb[y * w + x]
                if ((p ushr 24) < alphaT) { out[y * w + x] = transparentIdx; continue }
                val r = (((p shr 16) and 255) + cur[0][x + 1]).toInt().coerceIn(0, 255)
                val g = (((p shr 8) and 255) + cur[1][x + 1]).toInt().coerceIn(0, 255)
                val b = ((p and 255) + cur[2][x + 1]).toInt().coerceIn(0, 255)
                val idx = nearest(r, g, b); out[y * w + x] = idx
                val c = pal[idx]
                val er = r - ((c shr 16) and 255); val eg = g - ((c shr 8) and 255); val eb = b - (c and 255)
                for ((ch, e) in listOf(0 to er, 1 to eg, 2 to eb)) {
                    cur[ch][x + 2] += e * 7f / 16; nxt[ch][x] += e * 3f / 16; nxt[ch][x + 1] += e * 5f / 16; nxt[ch][x + 2] += e * 1f / 16
                }
            }
            val t = cur; cur = nxt; nxt = t
        }
        return out
    }

    // ----- LZW (variable code size, 12-bit max, clear on table full) -----
    private fun lzw(idx: IntArray, minCode: Int): ByteArray {
        val clear = 1 shl minCode; val eoi = clear + 1
        val out = ByteArrayOutputStream()
        var cur = 0; var nbits = 0
        fun emit(code: Int, size: Int) {
            cur = cur or (code shl nbits); nbits += size
            while (nbits >= 8) { out.write(cur and 255); cur = cur ushr 8; nbits -= 8 }
        }
        var codeSize = minCode + 1; var next = eoi + 1
        var dict = HashMap<Int, Int>()
        emit(clear, codeSize)
        var prefix = idx[0]
        for (i in 1 until idx.size) {
            val k = idx[i]; val key = (prefix shl 8) or k
            val hit = dict[key]
            if (hit != null) { prefix = hit; continue }
            emit(prefix, codeSize)
            if (next < 4096) {
                dict[key] = next++
                if (next - 1 == (1 shl codeSize) && codeSize < 12) codeSize++
            } else {
                emit(clear, codeSize); dict = HashMap(); codeSize = minCode + 1; next = eoi + 1
            }
            prefix = k
        }
        emit(prefix, codeSize)
        emit(eoi, codeSize)
        if (nbits > 0) out.write(cur and 255)
        return out.toByteArray()
    }
}
