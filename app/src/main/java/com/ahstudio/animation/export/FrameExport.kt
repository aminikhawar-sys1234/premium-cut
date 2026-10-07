package com.ahstudio.animation.export

import com.ahstudio.animation.render.Frame
import com.ahstudio.animation.render.MotionBlurSettings
import com.ahstudio.animation.render.Scene
import com.ahstudio.animation.render.SceneRenderer
import java.io.File
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Cooperative cancellation for long exports (same pattern as the app's export engine). */
class CancelToken { private val flag = AtomicBoolean(false); fun cancel() = flag.set(true); val isCancelled: Boolean get() = flag.get() }

data class ExportResult(val framesWritten: Int, val cancelled: Boolean, val warnings: List<String> = emptyList())

/** Destination for rendered frames. Implement this to plug in MediaCodec/Media3 on Android. */
interface FrameSink {
    fun begin(width: Int, height: Int, fps: Double, frameCount: Int)
    fun write(index: Int, timeMs: Long, frame: Frame)
    fun end(cancelled: Boolean)
}

data class ExportOptions(
    val motionBlur: MotionBlurSettings? = null,
    val startFrame: Int = 0,
    /** Exclusive. null = whole scene. */
    val endFrame: Int? = null,
    /** Render every n-th frame (previews / proxies). */
    val frameStep: Int = 1
)

object SceneExporter {
    /**
     * Renders [scene] frame by frame into [sink]. Rendering is a pure function of time, so exports are
     * reproducible and identical to on-screen preview. [onProgress] gets (framesDone, framesTotal).
     */
    fun export(
        scene: Scene, sink: FrameSink, options: ExportOptions = ExportOptions(),
        cancel: CancelToken = CancelToken(), onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): ExportResult {
        val start = options.startFrame.coerceIn(0, scene.frameCount)
        val end = (options.endFrame ?: scene.frameCount).coerceIn(start, scene.frameCount)
        val step = options.frameStep.coerceAtLeast(1)
        val indices = (start until end step step).toList()
        val renderer = SceneRenderer(scene)
        sink.begin(scene.width, scene.height, scene.fps / step, indices.size)
        var written = 0; var cancelled = false
        try {
            for (i in indices) {
                if (cancel.isCancelled) { cancelled = true; break }
                val t = scene.timeOfFrame(i)
                sink.write(written, t, renderer.render(t, options.motionBlur))
                written++
                onProgress(written, indices.size)
            }
        } finally { sink.end(cancelled) }
        return ExportResult(written, cancelled)
    }
}

class MemorySink : FrameSink {
    val frames = ArrayList<Frame>(); val times = ArrayList<Long>()
    var fps = 0.0; var ended = false; var wasCancelled = false
    override fun begin(width: Int, height: Int, fps: Double, frameCount: Int) { this.fps = fps }
    override fun write(index: Int, timeMs: Long, frame: Frame) { frames.add(frame); times.add(timeMs) }
    override fun end(cancelled: Boolean) { ended = true; wasCancelled = cancelled }
}

/** frame_00001.png style numbered PNG files in [dir]. [pattern] needs one %d-style placeholder (String.format). */
class PngSequenceSink(private val dir: File, private val pattern: String = "frame_%05d.png", private val firstNumber: Int = 1) : FrameSink {
    val files = ArrayList<File>()
    override fun begin(width: Int, height: Int, fps: Double, frameCount: Int) { dir.mkdirs() }
    override fun write(index: Int, timeMs: Long, frame: Frame) {
        val f = File(dir, String.format(pattern, firstNumber + index))
        f.writeBytes(PngEncoder.encode(frame)); files.add(f)
    }
    override fun end(cancelled: Boolean) {}
}

/** Buffers frames and writes one animated GIF on [end] (GIF needs all palettes/delays up front). */
class GifSink(private val out: OutputStream, private val loops: Int = 0, private val dither: Boolean = true) : FrameSink {
    private val frames = ArrayList<Frame>(); private var fps = 30.0
    override fun begin(width: Int, height: Int, fps: Double, frameCount: Int) { this.fps = fps }
    override fun write(index: Int, timeMs: Long, frame: Frame) { frames.add(frame) }
    override fun end(cancelled: Boolean) {
        if (frames.isEmpty()) return
        val delay = Math.round(1000.0 / fps).toInt()
        out.write(GifEncoder.encode(frames, List(frames.size) { delay }, loops, dither)); out.flush()
    }
}

class ApngSink(private val out: OutputStream, private val loops: Int = 0) : FrameSink {
    private val frames = ArrayList<Frame>(); private var fps = 30.0
    override fun begin(width: Int, height: Int, fps: Double, frameCount: Int) { this.fps = fps }
    override fun write(index: Int, timeMs: Long, frame: Frame) { frames.add(frame) }
    override fun end(cancelled: Boolean) {
        if (frames.isEmpty()) return
        val delay = Math.round(1000.0 / fps).toInt()
        out.write(PngEncoder.encodeApng(frames, List(frames.size) { delay }, loops)); out.flush()
    }
}

/**
 * YUV4MPEG2 (4:2:0, BT.709 limited range) streaming sink: `ffmpeg -i out.y4m -c:v libx264 out.mp4`.
 * Transparent pixels are flattened over [matte] (video has no alpha). Streams frame by frame (no buffering).
 */
class Y4mSink(private val out: OutputStream, private val matte: Int = 0x000000) : FrameSink {
    override fun begin(width: Int, height: Int, fps: Double, frameCount: Int) {
        val den = 1001; val num = Math.round(fps * den).toInt()
        val (n, d) = if (abs(fps - Math.round(fps)) < 1e-6) Math.round(fps).toInt() to 1 else num to den
        out.write("YUV4MPEG2 W${evenUp(width)} H${evenUp(height)} F$n:$d Ip A1:1 C420mpeg2 XYSCSS=420MPEG2\n".toByteArray(Charsets.US_ASCII))
    }
    private fun abs(v: Double) = if (v < 0) -v else v
    private fun evenUp(v: Int) = (v + 1) and 1.inv()

    override fun write(index: Int, timeMs: Long, frame: Frame) {
        val w = evenUp(frame.width); val h = evenUp(frame.height)
        val y = ByteArray(w * h); val u = ByteArray(w * h / 4); val v = ByteArray(w * h / 4)
        val rr = FloatArray(w * h); val gg = FloatArray(w * h); val bb = FloatArray(w * h)
        val mr = ((matte shr 16) and 255).toFloat(); val mg = ((matte shr 8) and 255).toFloat(); val mb = (matte and 255).toFloat()
        for (py in 0 until h) for (px in 0 until w) {
            val p = frame.argb[minOf(py, frame.height - 1) * frame.width + minOf(px, frame.width - 1)]
            val a = (p ushr 24) / 255f
            val i = py * w + px
            rr[i] = ((p shr 16) and 255) * a + mr * (1 - a); gg[i] = ((p shr 8) and 255) * a + mg * (1 - a); bb[i] = (p and 255) * a + mb * (1 - a)
            y[i] = (16f + 0.1826f * rr[i] + 0.6142f * gg[i] + 0.0620f * bb[i]).toInt().coerceIn(16, 235).toByte()
        }
        for (cy in 0 until h / 2) for (cx in 0 until w / 2) {
            var r = 0f; var g = 0f; var b = 0f
            for (dy in 0..1) for (dx in 0..1) { val i = (cy * 2 + dy) * w + cx * 2 + dx; r += rr[i]; g += gg[i]; b += bb[i] }
            r /= 4; g /= 4; b /= 4
            u[cy * (w / 2) + cx] = (128f - 0.1006f * r - 0.3386f * g + 0.4392f * b).toInt().coerceIn(16, 240).toByte()
            v[cy * (w / 2) + cx] = (128f + 0.4392f * r - 0.3989f * g - 0.0403f * b).toInt().coerceIn(16, 240).toByte()
        }
        out.write("FRAME\n".toByteArray(Charsets.US_ASCII)); out.write(y); out.write(u); out.write(v)
    }
    override fun end(cancelled: Boolean) { out.flush() }
}
