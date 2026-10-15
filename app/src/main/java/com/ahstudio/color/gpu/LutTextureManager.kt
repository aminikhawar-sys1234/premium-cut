package com.ahstudio.color.gpu

import android.opengl.GLES30
import com.ahstudio.color.lut.Lut3D
import com.ahstudio.color.lut.LutAtlasLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Uploads colour LUTs to GPU textures ONCE per LUT (§34) and releases them deterministically.
 *
 * Two encodings of the same LUT data, both uploaded here so the shader variant and the texture
 * can never disagree:
 *
 *  - ES3: a `sampler3D` texture (`getOrCreate3D`), the cheapest addressing.
 *  - ES2-class contexts: the tiled `sampler2D` atlas of [LutAtlasLayout]
 *    (`getOrCreate2DAtlas`), which is the layout `#if HAS_LUT2D` in
 *    [com.ahstudio.color.gpu.ColorShaderSource] samples.
 *
 * The upload helpers bind on the unit that is already active and unbind again before returning,
 * so they never move the active texture unit and can never leave a texture behind on unit 0 —
 * the unit the clip sampler reads. The caller activates the unit a sampler reads
 * ([ColorTextureUnits]) and binds the returned id there, which is what keeps every sampler type
 * on its own unit (see [ColorTextureUnits] for why that is what stops GL_INVALID_OPERATION).
 */
class LutTextureManager {

    private data class Entry(
        val texId: Int,
        val size: Int,
        val internalFormat: Int,
        /** True for the tiled 2D atlas, false for the 3D texture. */
        val is2D: Boolean
    )

    private val cache = HashMap<String, Entry>()

    /** 1x1 opaque white 2D texture, bound to a sampler slot that has no texture this frame. */
    private var whiteTex = 0

    /** 2x2x2 black 3D texture, bound to `uLut3d` when the LUT data is missing (with intensity 0). */
    private var black3d = 0

    /**
     * Returns the GL texture id of the 3D texture holding [lut], uploading it into [unit] if this
     * LUT has not been uploaded for this context yet.
     */
    fun getOrCreate3D(lut: Lut3D, lutId: String, floatCapable: Boolean): Int {
        val key = "3d|$lutId|${lut.size}"
        cache[key]?.let { return it.texId }
        val domainWithinUnit = lut.domainMin.all { it >= 0f } && lut.domainMax.all { it <= 1f }
        val internalFormat = if (domainWithinUnit || !floatCapable) GLES30.GL_RGBA8 else GLES30.GL_RGBA16F
        val type = if (internalFormat == GLES30.GL_RGBA16F) GLES30.GL_HALF_FLOAT else GLES30.GL_UNSIGNED_BYTE

        val n = lut.size
        val rgba = FloatArray(n * n * n * 4)
        for (i in 0 until n * n * n) {
            rgba[i * 4 + 0] = lut.data[i * 3 + 0]
            rgba[i * 4 + 1] = lut.data[i * 3 + 1]
            rgba[i * 4 + 2] = lut.data[i * 3 + 2]
            rgba[i * 4 + 3] = 1f
        }
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        // Uploads bind on the unit that is already active and unbind again at the end: the caller
        // activates the unit the sampler reads and binds this id there. Moving the active unit in
        // here would make the caller's bind land on the wrong unit.
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, tex[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)

        if (type == GLES30.GL_HALF_FLOAT) {
            GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, internalFormat, n, n, n, 0,
                GLES30.GL_RGBA, type, halfFloatByteBuffer(rgba))
        } else {
            val bytes = ByteBuffer.allocateDirect(rgba.size).order(ByteOrder.nativeOrder())
            for (v in rgba) bytes.put((v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255).toByte())
            bytes.position(0)
            GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, internalFormat, n, n, n, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bytes)
        }
        cache[key] = Entry(tex[0], n, internalFormat, is2D = false)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, 0)
        return tex[0]
    }

    /**
     * Returns the GL texture id of the tiled 2D atlas holding [lut], uploading it into [unit] if
     * this LUT has not been uploaded for this context yet. Layout: [LutAtlasLayout] —
     * the shader samples it with `uv = vec2(z * n + x, y) * vec2(1/(n*n), 1/n) + half texel`.
     */
    fun getOrCreate2DAtlas(lut: Lut3D, lutId: String): Int {
        val key = "2d|$lutId|${lut.size}"
        cache[key]?.let { return it.texId }
        val n = if (LutAtlasLayout.isSupportedSize(lut.size)) lut.size else 2
        val width = LutAtlasLayout.width(n)
        val height = LutAtlasLayout.height(n)
        val bytes = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        for (column in 0 until width) {
            for (row in 0 until height) {
                val offset = LutAtlasLayout.dataOffset(n, column, row)
                if (offset + 2 < lut.data.size) {
                    // Same RGBA8 quantization as the 3D upload, so both variants agree bit for bit.
                    bytes.put((lut.data[offset].coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255).toByte())
                    bytes.put((lut.data[offset + 1].coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255).toByte())
                    bytes.put((lut.data[offset + 2].coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255).toByte())
                    bytes.put(255.toByte())
                } else {
                    bytes.put(0.toByte()); bytes.put(0.toByte()); bytes.put(0.toByte()); bytes.put(255.toByte())
                }
            }
        }
        bytes.position(0)

        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, width, height, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bytes)
        cache[key] = Entry(tex[0], n, GLES30.GL_RGBA8, is2D = true)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        return tex[0]
    }

    /** Size (`uLutSize`) that belongs to the atlas of [lut]. */
    fun atlasSize(lut: Lut3D?): Float = LutAtlasLayout.uniformSize(lut?.size ?: 0)

    fun lutParams(lut: Lut3D): FloatArray {
        val n = lut.size.toFloat()
        val scale = (n - 1f) / n
        val offset = 0.5f / n
        return floatArrayOf(scale, scale, scale, offset, offset, offset)
    }

    /**
     * 1x1 opaque white 2D texture. Bound to a sampler slot that has no texture this frame, so the
     * slot is never left pointing at unit 0 (a sampler on unit 0 next to the OES input sampler is
     * the sampler-type clash that raises GL_INVALID_OPERATION / 0x502 on the draw).
     */
    fun white2D(): Int {
        var tex = whiteTex
        if (tex == 0) {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            tex = ids[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            val px = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
            px.put(255.toByte()); px.put(255.toByte()); px.put(255.toByte()); px.put(255.toByte())
            px.position(0)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 1, 1, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, px)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            whiteTex = tex
        }
        return tex
    }

    /**
     * 2x2x2 black 3D texture, bound when the 3D variant is selected but the LUT data is not
     * available: the sampler still needs a texture of its own target on its unit (a 2D texture
     * there would be a sampler/target mismatch -> 0x502), and the caller drops `uLutIntensity`
     * to 0 so the pass-through is exact.
     */
    fun black3D(): Int {
        var tex = black3d
        if (tex == 0) {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            tex = ids[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, tex)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
            val px = ByteBuffer.allocateDirect(2 * 2 * 2 * 4).order(ByteOrder.nativeOrder())
            repeat(2 * 2 * 2) {
                px.put(0.toByte()); px.put(0.toByte()); px.put(0.toByte()); px.put(255.toByte())
            }
            px.position(0)
            GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGBA8, 2, 2, 2, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, px)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, 0)
            black3d = tex
        }
        return tex
    }

    fun remove(lutId: String) {
        for (prefix in listOf("3d|$lutId|", "2d|$lutId|")) {
            val keys = cache.keys.filter { it.startsWith(prefix) }
            for (k in keys) cache.remove(k)?.let { GLES30.glDeleteTextures(1, intArrayOf(it.texId), 0) }
        }
    }

    /** Deterministic GPU resource release (§34, §67). */
    fun releaseAll() {
        cache.values.forEach { GLES30.glDeleteTextures(1, intArrayOf(it.texId), 0) }
        cache.clear()
        if (whiteTex != 0) { GLES30.glDeleteTextures(1, intArrayOf(whiteTex), 0); whiteTex = 0 }
        if (black3d != 0) { GLES30.glDeleteTextures(1, intArrayOf(black3d), 0); black3d = 0 }
    }

    private fun halfFloatByteBuffer(floats: FloatArray): ByteBuffer {
        val bb = ByteBuffer.allocateDirect(floats.size * 2).order(ByteOrder.nativeOrder())
        for (f in floats) bb.putShort(floatToHalf(f))
        bb.position(0)
        return bb
    }

    companion object {
        fun floatToHalf(f: Float): Short {
            val bits = java.lang.Float.floatToIntBits(f)
            val sign = (bits ushr 16) and 0x8000
            val exp = ((bits ushr 23) and 0xff) - 127 + 15
            val mant = bits and 0x7fffff
            if (exp >= 0x1f) return (sign or 0x7c00).toShort()
            if (exp <= 0) {
                if (exp < -10) return sign.toShort()
                val m = (mant or 0x800000) shr (1 - exp)
                return (sign or (m shr 13)).toShort()
            }
            return (sign or (exp shl 10) or (mant shr 13)).toShort()
        }
    }
}
