package com.ahstudio.animation.render

import com.ahstudio.animation.core.AnimationEngine
import com.ahstudio.animation.easing.Easing
import com.ahstudio.animation.easing.EasingType
import com.ahstudio.animation.math.Color4
import com.ahstudio.animation.math.Vec2
import com.ahstudio.animation.parenting.Transform2D
import com.ahstudio.animation.particles.ParticleSystem
import com.ahstudio.animation.rig.PuppetMesh
import com.ahstudio.animation.rig.PuppetPin
import com.ahstudio.animation.shape.BezierPath
import com.ahstudio.animation.shape.PathTrack

/** Standard property names for layer-level properties beyond [com.ahstudio.animation.properties.Props]. */
object SceneProps {
    const val POSITION_Z = "Transform.Position.Z"
    const val ROTATION_X = "Transform.Rotation.X"
    const val ROTATION_Y = "Transform.Rotation.Y"
    const val BLUR = "Effect.Blur"
    const val CAMERA_ID = "camera"
    const val CAM_POS_X = "Camera.Position.X"; const val CAM_POS_Y = "Camera.Position.Y"; const val CAM_POS_Z = "Camera.Position.Z"
    const val CAM_POI_X = "Camera.POI.X"; const val CAM_POI_Y = "Camera.POI.Y"; const val CAM_POI_Z = "Camera.POI.Z"
    const val CAM_ZOOM = "Camera.Zoom"; const val CAM_APERTURE = "Camera.Aperture"; const val CAM_FOCUS = "Camera.Focus"
    /** Shape trim paths, percent: "Shape.<index>.TrimStart" etc. */
    fun trimStart(i: Int) = "Shape.$i.TrimStart"
    fun trimEnd(i: Int) = "Shape.$i.TrimEnd"
    fun trimOffset(i: Int) = "Shape.$i.TrimOffset"
    fun fillOpacity(i: Int) = "Shape.$i.FillOpacity"
}

/** Keyframed colour (the engine tracks only scalars/vectors, so colour has its own tiny track). */
class ColorTrack(private val keys: List<Key>) {
    data class Key(val timeMs: Long, val color: Color4, val easing: EasingType = EasingType.LINEAR)
    private val sorted = keys.sortedBy { it.timeMs }
    init { require(sorted.isNotEmpty()) }
    fun valueAt(t: Long): Color4 {
        if (t <= sorted.first().timeMs) return sorted.first().color
        if (t >= sorted.last().timeMs) return sorted.last().color
        var i = 0
        while (i < sorted.size - 2 && sorted[i + 1].timeMs <= t) i++
        val a = sorted[i]; val b = sorted[i + 1]
        return a.color.lerp(b.color, Easing.apply(a.easing, (t - a.timeMs).toDouble() / (b.timeMs - a.timeMs)))
    }
}

class StrokeStyle(
    val paint: Paint, val width: Double, val cap: LineCap = LineCap.ROUND, val join: LineJoin = LineJoin.ROUND,
    val miterLimit: Double = 4.0, val dash: List<Double> = emptyList(), val dashOffset: Double = 0.0, val color: ColorTrack? = null
)

sealed class PathSource {
    class Static(val path: BezierPath) : PathSource()
    class Animated(val track: PathTrack) : PathSource()
    fun at(t: Long): BezierPath = when (this) { is Static -> path; is Animated -> track.valueAt(t) }
    fun trackKeys(): List<PathTrack.Key> = when (this) { is Static -> listOf(PathTrack.Key(0, path)); is Animated -> track.keyList() }
}

/** One path + fill/stroke (+ optional trim) inside a shape layer, like an AE shape group. */
class ShapeItem(
    val path: PathSource, val fill: Paint? = null, val stroke: StrokeStyle? = null, val fillRule: FillRule = FillRule.NON_ZERO,
    val fillColor: ColorTrack? = null,
    /** Trim defaults in percent (0..100); engine tracks "Shape.<i>.Trim*" override them. */
    val trimStartPct: Double = 0.0, val trimEndPct: Double = 100.0, val trimOffsetPct: Double = 0.0
) {
    constructor(path: BezierPath, fill: Paint? = null, stroke: StrokeStyle? = null) : this(PathSource.Static(path), fill, stroke)
}

class RasterImage(val width: Int, val height: Int, val argb: IntArray) {
    init { require(argb.size == width * height) }
    /** Bilinear sample at pixel-space (x,y); returns premultiplied r,g,b,a in [0,1]. Edge pixels clamp. */
    fun sample(x: Double, y: Double, out: FloatArray) {
        val fx = (x - 0.5).coerceIn(0.0, width - 1.0); val fy = (y - 0.5).coerceIn(0.0, height - 1.0)
        val x0 = fx.toInt(); val y0 = fy.toInt(); val x1 = minOf(x0 + 1, width - 1); val y1 = minOf(y0 + 1, height - 1)
        val tx = (fx - x0).toFloat(); val ty = (fy - y0).toFloat()
        out.fill(0f)
        fun acc(px: Int, py: Int, wgt: Float) {
            val p = argb[py * width + px]
            val a = (p ushr 24) / 255f
            out[0] += ((p shr 16) and 255) / 255f * a * wgt; out[1] += ((p shr 8) and 255) / 255f * a * wgt
            out[2] += (p and 255) / 255f * a * wgt; out[3] += a * wgt
        }
        acc(x0, y0, (1 - tx) * (1 - ty)); acc(x1, y0, tx * (1 - ty)); acc(x0, y1, (1 - tx) * ty); acc(x1, y1, tx * ty)
    }
    companion object {
        fun solid(w: Int, h: Int, c: Color4): RasterImage {
            val a = (c.a * 255 + 0.5).toInt(); val v = (a shl 24) or ((c.r * 255 + 0.5).toInt() shl 16) or ((c.g * 255 + 0.5).toInt() shl 8) or (c.b * 255 + 0.5).toInt()
            return RasterImage(w, h, IntArray(w * h) { v })
        }
        fun fromFrame(f: Frame) = RasterImage(f.width, f.height, f.argb.copyOf())
    }
}

sealed class LayerContent {
    class Shapes(val items: List<ShapeItem>) : LayerContent()
    class Solid(val color: Color4, val width: Int, val height: Int) : LayerContent()
    class Image(val image: RasterImage) : LayerContent()
    class Particles(val system: ParticleSystem, val additive: Boolean = false, val softness: Double = 0.6) : LayerContent()
    class Puppet(val image: RasterImage, val mesh: PuppetMesh, val pins: (Long) -> List<PuppetPin>) : LayerContent()
    object Null : LayerContent()
}

/**
 * A scene layer. Animated transform values come from the [AnimationEngine] (BindingKey(layer.id, Props.*));
 * [transform] holds the static fallback used when a property has no track.
 */
class Layer(
    val id: String, val content: LayerContent,
    val parentId: String? = null, val inMs: Long = 0L, val outMs: Long = Long.MAX_VALUE,
    val blend: RenderBlend = RenderBlend.NORMAL, val transform: Transform2D = Transform2D(),
    /** 3D layers are projected through the scene camera; they need an explicit local canvas size. */
    val is3D: Boolean = false, val width: Int = 0, val height: Int = 0, val z: Double = 0.0,
    val motionBlur: Boolean = false, val blurPx: Double = 0.0, val visible: Boolean = true
)

class Scene(
    val width: Int, val height: Int, val fps: Double, val durationMs: Long,
    val engine: AnimationEngine, val layers: List<Layer>,
    val background: Color4 = Color4(0.0, 0.0, 0.0, 0.0),
    val useCamera: Boolean = false,
    /** Static camera defaults (AE default camera) used when camera tracks are absent. */
    val cameraZoom: Double = 1777.78
) {
    init { require(width > 0 && height > 0 && fps > 0) { "invalid scene size/fps" }; engine.fps = fps; engine.durationMs = durationMs }
    val frameCount: Int get() = ((durationMs / 1000.0) * fps).toInt().coerceAtLeast(1)
    fun timeOfFrame(i: Int): Long = Math.round(i * 1000.0 / fps)
}

data class MotionBlurSettings(val samples: Int = 8, val shutterAngleDeg: Double = 180.0, val shutterPhaseDeg: Double = -90.0)
