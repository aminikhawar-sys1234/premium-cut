package com.ahstudio.animation.export

import com.ahstudio.animation.render.Frame
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/** Minimal dependency-free PNG (8-bit RGBA) and APNG encoder -- works on Android and plain JVM. */
object PngEncoder {
    private val SIG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    fun encode(frame: Frame, level: Int = 6): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(SIG)
        chunk(out, "IHDR", ihdr(frame.width, frame.height))
        chunk(out, "IDAT", idat(frame, level))
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    /** Animated PNG. [delaysMs] per frame; [loops] 0 = forever. All frames must share the same size. */
    fun encodeApng(frames: List<Frame>, delaysMs: List<Int>, loops: Int = 0, level: Int = 6): ByteArray {
        require(frames.isNotEmpty() && frames.size == delaysMs.size) { "frames/delays mismatch" }
        val w = frames[0].width; val h = frames[0].height
        require(frames.all { it.width == w && it.height == h }) { "all frames must be the same size" }
        val out = ByteArrayOutputStream()
        out.write(SIG)
        chunk(out, "IHDR", ihdr(w, h))
        chunk(out, "acTL", be(frames.size) + be(loops))
        var seq = 0
        for ((i, f) in frames.withIndex()) {
            val delayNum = delaysMs[i].coerceIn(1, 65535)
            chunk(out, "fcTL", be(seq++) + be(w) + be(h) + be(0) + be(0) + be16(delayNum) + be16(1000) + byteArrayOf(0, 0))
            val data = idat(f, level)
            if (i == 0) chunk(out, "IDAT", data) else chunk(out, "fdAT", be(seq++) + data)
        }
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun ihdr(w: Int, h: Int) = be(w) + be(h) + byteArrayOf(8, 6, 0, 0, 0)
    private fun be(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())

    private fun idat(frame: Frame, level: Int): ByteArray {
        val w = frame.width; val h = frame.height
        val rgba = frame.toRgba()
        val stride = w * 4
        val raw = ByteArray((stride + 1) * h)
        // adaptive filter: choose Sub or Up per row by smallest sum of absolute differences
        for (y in 0 until h) {
            val row = y * stride; val o = y * (stride + 1)
            var costNone = 0L; var costSub = 0L; var costUp = 0L
            for (x in 0 until stride) {
                val cur = rgba[row + x].toInt() and 255
                val left = if (x >= 4) rgba[row + x - 4].toInt() and 255 else 0
                val up = if (y > 0) rgba[row - stride + x].toInt() and 255 else 0
                costNone += kotlin.math.abs(cur.toByte().toInt()); costSub += kotlin.math.abs(((cur - left) and 255).toByte().toInt()); costUp += kotlin.math.abs(((cur - up) and 255).toByte().toInt())
            }
            val ft = if (costSub <= costNone && costSub <= costUp) 1 else if (costUp < costNone) 2 else 0
            raw[o] = ft.toByte()
            for (x in 0 until stride) {
                val cur = rgba[row + x].toInt() and 255
                val left = if (x >= 4) rgba[row + x - 4].toInt() and 255 else 0
                val up = if (y > 0) rgba[row - stride + x].toInt() and 255 else 0
                raw[o + 1 + x] = (when (ft) { 1 -> cur - left; 2 -> cur - up; else -> cur } and 255).toByte()
            }
        }
        val d = Deflater(level)
        d.setInput(raw); d.finish()
        val buf = ByteArrayOutputStream(); val tmp = ByteArray(65536)
        while (!d.finished()) { val n = d.deflate(tmp); buf.write(tmp, 0, n) }
        d.end()
        return buf.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val dos = DataOutputStream(out)
        dos.writeInt(data.size)
        val t = type.toByteArray(Charsets.US_ASCII)
        dos.write(t); dos.write(data)
        val crc = CRC32(); crc.update(t); crc.update(data)
        dos.writeInt(crc.value.toInt())
    }
}
