package com.ahstudio.animation.export

import com.ahstudio.animation.core.AnimationEngine
import com.ahstudio.animation.easing.EasingType
import com.ahstudio.animation.keyframes.EvaluatedValue
import com.ahstudio.animation.keyframes.InterpolationType
import com.ahstudio.animation.keyframes.Keyframe
import com.ahstudio.animation.math.Color4
import com.ahstudio.animation.math.Vec2
import com.ahstudio.animation.properties.BindingKey
import com.ahstudio.animation.properties.PropertyType
import com.ahstudio.animation.properties.Props
import com.ahstudio.animation.render.*
import com.ahstudio.animation.shape.BezierPath
import com.ahstudio.animation.shape.PathMorph
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Exports a [Scene] as Lottie (Bodymovin) JSON, the interchange format of After Effects / LottieFiles / CapCut-style
 * web players. Animated properties keep real keyframes + bezier easing when the curve is expressible in Lottie;
 * anything else (springs, wiggle, expressions, elastic/bounce, spatial handles) is baked at the scene frame rate.
 * Features Lottie cannot represent (particles, puppet meshes, 3D camera layers) are skipped and listed in [Report.warnings].
 */
object LottieExporter {
    data class Report(val json: String, val warnings: List<String>, val layersExported: Int)

    fun export(scene: Scene, name: String = "ah-studio-animation"): Report {
        val warnings = ArrayList<String>()
        val assets = JSONArray(); val layers = JSONArray()
        val frames = scene.durationMs / 1000.0 * scene.fps
        val indexOf = HashMap<String, Int>()
        scene.layers.forEachIndexed { i, l -> indexOf[l.id] = i + 1 }
        var exported = 0
        // Lottie draws the FIRST layer on top; our list is bottom -> top.
        for (l in scene.layers.reversed()) {
            val ctx = Ctx(scene, l, warnings)
            val ty = when (val c = l.content) {
                is LayerContent.Shapes -> 4
                is LayerContent.Solid -> 1
                is LayerContent.Image -> 2
                is LayerContent.Null -> 3
                is LayerContent.Particles -> { warnings.add("layer '${l.id}': particle systems are not representable in Lottie (skipped)"); continue }
                is LayerContent.Puppet -> { warnings.add("layer '${l.id}': puppet mesh deformation is not representable in Lottie (skipped)"); continue }
            }
            if (l.is3D) warnings.add("layer '${l.id}': 3D camera projection is not exported; the layer is written as a flat 2D layer")
            val o = JSONObject()
            o.put("ddd", 0).put("ind", indexOf.getValue(l.id)).put("ty", ty).put("nm", l.id).put("sr", 1)
            o.put("ks", ctx.transform())
            o.put("ao", 0)
            o.put("ip", frameOf(scene, l.inMs)).put("op", if (l.outMs == Long.MAX_VALUE) frames else frameOf(scene, l.outMs)).put("st", 0)
            o.put("bm", when (l.blend) { RenderBlend.NORMAL -> 0; RenderBlend.MULTIPLY -> 1; RenderBlend.SCREEN -> 3; RenderBlend.ADD -> 16 })
            l.parentId?.let { pid -> indexOf[pid]?.let { o.put("parent", it) } }
            when (val c = l.content) {
                is LayerContent.Shapes -> o.put("shapes", JSONArray().put(ctx.shapeGroup(c)))
                is LayerContent.Solid -> { o.put("sw", c.width).put("sh", c.height).put("sc", hex(c.color)) }
                is LayerContent.Image -> {
                    val id = "img_${l.id}"
                    val png = PngEncoder.encode(Frame(c.image.width, c.image.height, c.image.argb))
                    assets.put(JSONObject().put("id", id).put("w", c.image.width).put("h", c.image.height).put("u", "")
                        .put("p", "data:image/png;base64," + Base64.getEncoder().encodeToString(png)).put("e", 1))
                    o.put("refId", id)
                }
                else -> {}
            }
            layers.put(o); exported++
        }
        val root = JSONObject()
        root.put("v", "5.7.0").put("fr", scene.fps).put("ip", 0).put("op", frames).put("w", scene.width).put("h", scene.height)
        root.put("nm", name).put("ddd", 0).put("assets", assets).put("layers", layers)
        return Report(root.toString(), warnings, exported)
    }

    private fun frameOf(scene: Scene, ms: Long) = ms / 1000.0 * scene.fps
    private fun hex(c: Color4) = "#%02x%02x%02x".format((c.r * 255).roundToInt().coerceIn(0, 255), (c.g * 255).roundToInt().coerceIn(0, 255), (c.b * 255).roundToInt().coerceIn(0, 255))

    private class Ctx(val scene: Scene, val layer: Layer, val warnings: MutableList<String>) {
        val engine: AnimationEngine get() = scene.engine
        val endMs: Long get() = scene.durationMs

        fun bez(x1: Double, y1: Double, x2: Double, y2: Double) = JSONObject().put("o", JSONObject().put("x", JSONArray().put(x1)).put("y", JSONArray().put(y1)))
            .put("i", JSONObject().put("x", JSONArray().put(x2)).put("y", JSONArray().put(y2)))

        /** CSS-style control points (x1,y1,x2,y2) for a segment, or null if Lottie cannot express it exactly. */
        fun easingBezier(k: Keyframe, next: Keyframe): DoubleArray? {
            if (k.easing != EasingType.LINEAR) return when (k.easing) {
                EasingType.SINE_IN -> doubleArrayOf(0.12, 0.0, 0.39, 0.0); EasingType.SINE_OUT -> doubleArrayOf(0.61, 1.0, 0.88, 1.0)
                EasingType.SINE_INOUT -> doubleArrayOf(0.37, 0.0, 0.63, 1.0)
                EasingType.CUBIC_IN -> doubleArrayOf(0.32, 0.0, 0.67, 0.0); EasingType.CUBIC_OUT -> doubleArrayOf(0.33, 1.0, 0.68, 1.0)
                EasingType.CUBIC_INOUT -> doubleArrayOf(0.65, 0.0, 0.35, 1.0)
                EasingType.QUART_IN -> doubleArrayOf(0.5, 0.0, 0.75, 0.0); EasingType.QUART_OUT -> doubleArrayOf(0.25, 1.0, 0.5, 1.0)
                EasingType.QUART_INOUT -> doubleArrayOf(0.76, 0.0, 0.24, 1.0)
                EasingType.QUINT_IN -> doubleArrayOf(0.64, 0.0, 0.78, 0.0); EasingType.QUINT_OUT -> doubleArrayOf(0.22, 1.0, 0.36, 1.0)
                EasingType.QUINT_INOUT -> doubleArrayOf(0.83, 0.0, 0.17, 1.0)
                EasingType.EXPO_IN -> doubleArrayOf(0.7, 0.0, 0.84, 0.0); EasingType.EXPO_OUT -> doubleArrayOf(0.16, 1.0, 0.3, 1.0)
                EasingType.EXPO_INOUT -> doubleArrayOf(0.87, 0.0, 0.13, 1.0)
                EasingType.CIRC_IN -> doubleArrayOf(0.55, 0.0, 1.0, 0.45); EasingType.CIRC_OUT -> doubleArrayOf(0.0, 0.55, 0.45, 1.0)
                EasingType.CIRC_INOUT -> doubleArrayOf(0.85, 0.0, 0.15, 1.0)
                EasingType.BACK_IN -> doubleArrayOf(0.36, 0.0, 0.66, -0.56); EasingType.BACK_OUT -> doubleArrayOf(0.34, 1.56, 0.64, 1.0)
                EasingType.BACK_INOUT -> doubleArrayOf(0.68, -0.6, 0.32, 1.6)
                EasingType.CUSTOM -> k.bezier?.let { doubleArrayOf(it.x1, it.y1, it.x2, it.y2) }
                else -> null                               // elastic / bounce: bake
            }
            return when (k.interpolation) {
                InterpolationType.LINEAR -> doubleArrayOf(0.0, 0.0, 1.0, 1.0)
                InterpolationType.EASE_IN -> doubleArrayOf(0.42, 0.0, 1.0, 1.0)
                InterpolationType.EASE_OUT -> doubleArrayOf(0.0, 0.0, 0.58, 1.0)
                InterpolationType.EASE_IN_OUT -> doubleArrayOf(0.42, 0.0, 0.58, 1.0)
                InterpolationType.CUSTOM_CURVE -> k.bezier?.let { doubleArrayOf(it.x1, it.y1, it.x2, it.y2) } ?: doubleArrayOf(0.0, 0.0, 1.0, 1.0)
                InterpolationType.BEZIER -> {
                    val dv = next.value - k.value; val dt = (next.timeMs - k.timeMs) / 1000.0
                    if (next.vecValue != null || k.vecValue != null || abs(dv) < 1e-9) null
                    else doubleArrayOf(1.0 / 3.0, k.outTangent * dt / (3 * dv), 2.0 / 3.0, 1.0 - next.inTangent * dt / (3 * dv))
                }
                InterpolationType.HOLD -> doubleArrayOf(0.0, 0.0, 1.0, 1.0)
            }
        }

        fun values(v: EvaluatedValue, dims: Int): JSONArray {
            val a = JSONArray()
            when (v) {
                is EvaluatedValue.FloatV -> { a.put(v.value) }
                is EvaluatedValue.Vec2V -> { a.put(v.value.x).put(v.value.y) }
            }
            while (a.length() < dims) a.put(if (dims == 3 && a.length() == 2) zDefault else 0.0)
            return a
        }
        var zDefault = 0.0

        /** Animated-or-static Lottie property for BindingKey(layer.id, prop). [scalar] true => numeric, else array of [dims]. */
        fun prop(propName: String, def: DoubleArray, dims: Int, scalar: Boolean, scaleFrom: Double = 1.0): JSONObject {
            val key = BindingKey(layer.id, propName)
            fun staticVal(): JSONObject = JSONObject().put("a", 0).put("k", if (scalar) def[0] else JSONArray().also { for (d in def) it.put(d) })
            val tr = engine.trackFor(key)
            val dynamic = engine.activeSourceKeys().contains(key) || engine.expressionOf(key) != null
            if (tr == null && !dynamic) return staticVal()
            val kfs = tr?.get()?.data?.keyframes ?: emptyList()
            val simple = !dynamic && kfs.isNotEmpty() && tr!!.get().loop == com.ahstudio.animation.time.LoopMode.NONE && tr.get().speed == 1.0 &&
                kfs.zipWithNext().all { (a, b) -> a.interpolation == InterpolationType.HOLD || easingBezier(a, b) != null } &&
                (tr.get().spatial.not() || kfs.none { it.spatialOutHandle != null || it.spatialInHandle != null })
            val arr = JSONArray()
            fun conv(v: EvaluatedValue): Any {
                val vv = when (v) {
                    is EvaluatedValue.FloatV -> EvaluatedValue.FloatV(v.value * scaleFrom, 0.0)
                    is EvaluatedValue.Vec2V -> EvaluatedValue.Vec2V(Vec2(v.value.x, v.value.y), Vec2.ZERO)
                }
                return if (scalar) (vv as EvaluatedValue.FloatV).value else values(vv, dims)
            }
            fun valOf(k: Keyframe): EvaluatedValue = if (k.vecValue != null) EvaluatedValue.Vec2V(k.vecValue, Vec2.ZERO) else EvaluatedValue.FloatV(k.value, 0.0)
            if (simple) {
                for ((i, k) in kfs.withIndex()) {
                    val kk = JSONObject().put("t", k.timeMs / 1000.0 * scene.fps).put("s", wrap(conv(valOf(k))))
                    if (i < kfs.size - 1) {
                        if (k.interpolation == InterpolationType.HOLD) kk.put("h", 1)
                        else { val b = easingBezier(k, kfs[i + 1])!!; val j = bez(b[0], b[1], b[2], b[3]); kk.put("o", j.get("o")).put("i", j.get("i")) }
                    }
                    arr.put(kk)
                }
            } else {
                warnings.add("layer '${layer.id}': '$propName' baked per frame (procedural / expression / non-bezier easing)")
                val n = scene.frameCount
                for (f in 0..n) {
                    val t = Math.round(f * 1000.0 / scene.fps)
                    val v = engine.evaluateKey(key, t) ?: continue
                    val kk = JSONObject().put("t", f.toDouble()).put("s", wrap(conv(v)))
                    if (f < n) { val j = bez(0.0, 0.0, 1.0, 1.0); kk.put("o", j.get("o")).put("i", j.get("i")) }
                    arr.put(kk)
                }
            }
            if (arr.length() == 0) return staticVal()
            return JSONObject().put("a", 1).put("k", arr)
        }
        private fun wrap(v: Any): JSONArray = if (v is JSONArray) v else JSONArray().put(v)

        fun transform(): JSONObject {
            val d = layer.transform
            zDefault = 0.0
            val ks = JSONObject()
            ks.put("o", prop(Props.OPACITY, doubleArrayOf(d.opacity), 1, true))
            ks.put("r", prop(Props.ROTATION, doubleArrayOf(d.rotationDeg), 1, true))
            ks.put("p", prop(Props.POSITION, doubleArrayOf(d.position.x, d.position.y, 0.0), 3, false))
            zDefault = 0.0
            ks.put("a", prop(Props.ANCHOR, doubleArrayOf(d.anchor.x, d.anchor.y, 0.0), 3, false))
            zDefault = 100.0
            ks.put("s", prop(Props.SCALE, doubleArrayOf(d.scale.x * 100, d.scale.y * 100, 100.0), 3, false))
            return ks
        }

        fun colorProp(static: Color4, track: ColorTrack?, sampleKeys: List<Long>): JSONObject {
            if (track == null) return JSONObject().put("a", 0).put("k", JSONArray().put(static.r).put(static.g).put(static.b).put(1.0))
            val arr = JSONArray()
            val times = (sampleKeys + 0L + endMs).distinct().sorted()
            for ((i, t) in times.withIndex()) {
                val c = track.valueAt(t)
                val kk = JSONObject().put("t", t / 1000.0 * scene.fps).put("s", JSONArray().put(c.r).put(c.g).put(c.b).put(1.0))
                if (i < times.size - 1) { val j = bez(0.0, 0.0, 1.0, 1.0); kk.put("o", j.get("o")).put("i", j.get("i")) }
                arr.put(kk)
            }
            return JSONObject().put("a", 1).put("k", arr)
        }

        fun pathJson(p: BezierPath): JSONObject {
            val i = JSONArray(); val o = JSONArray(); val v = JSONArray()
            for (vx in p.vertices) {
                i.put(JSONArray().put(vx.inT.x).put(vx.inT.y)); o.put(JSONArray().put(vx.outT.x).put(vx.outT.y)); v.put(JSONArray().put(vx.p.x).put(vx.p.y))
            }
            return JSONObject().put("i", i).put("o", o).put("v", v).put("c", p.closed)
        }

        fun shapeGroup(c: LayerContent.Shapes): JSONObject {
            val items = JSONArray()
            c.items.forEachIndexed { idx, item ->
                items.put(JSONObject().put("ty", "gr").put("nm", "shape$idx").put("it", groupItems(item, idx)))
            }
            return JSONObject().put("ty", "gr").put("nm", "root").put("it", JSONArray().also { root ->
                // Lottie renders the first group on top; ours are painted first->last, so reverse.
                for (k in items.length() - 1 downTo 0) root.put(items.get(k))
                root.put(identityTr())
            })
        }
        private fun identityTr() = JSONObject().put("ty", "tr").put("p", JSONObject().put("a", 0).put("k", JSONArray().put(0).put(0)))
            .put("a", JSONObject().put("a", 0).put("k", JSONArray().put(0).put(0))).put("s", JSONObject().put("a", 0).put("k", JSONArray().put(100).put(100)))
            .put("r", JSONObject().put("a", 0).put("k", 0)).put("o", JSONObject().put("a", 0).put("k", 100))

        private fun groupItems(item: ShapeItem, idx: Int): JSONArray {
            val a = JSONArray()
            val src = item.path
            val shape = JSONObject().put("ty", "sh").put("nm", "path")
            when (src) {
                is PathSource.Static -> shape.put("ks", JSONObject().put("a", 0).put("k", pathJson(src.path)))
                is PathSource.Animated -> shape.put("ks", animatedPath(src))
            }
            a.put(shape)
            item.fill?.let { p ->
                val f = when (p) {
                    is Paint.Solid -> JSONObject().put("ty", "fl").put("c", colorProp(p.color, item.fillColor, emptyList()))
                    is Paint.Linear -> gradient(p.stops, 1, p.from, p.to)
                    is Paint.Radial -> gradient(p.stops, 2, p.center, Vec2(p.center.x + p.radius, p.center.y))
                }
                f.put("o", JSONObject().put("a", 0).put("k", 100)).put("r", if (item.fillRule == FillRule.EVEN_ODD) 2 else 1).put("nm", "fill")
                a.put(f)
            }
            item.stroke?.let { s ->
                val st: JSONObject = when (val p = s.paint) {
                    is Paint.Solid -> JSONObject().put("ty", "st").put("c", colorProp(p.color, s.color, emptyList()))
                    is Paint.Linear -> gradient(p.stops, 1, p.from, p.to).put("ty", "gs")
                    is Paint.Radial -> gradient(p.stops, 2, p.center, Vec2(p.center.x + p.radius, p.center.y)).put("ty", "gs")
                }
                st.put("o", JSONObject().put("a", 0).put("k", 100)).put("w", JSONObject().put("a", 0).put("k", s.width))
                    .put("lc", when (s.cap) { LineCap.BUTT -> 1; LineCap.ROUND -> 2; LineCap.SQUARE -> 3 })
                    .put("lj", when (s.join) { LineJoin.MITER -> 1; LineJoin.ROUND -> 2; LineJoin.BEVEL -> 3 }).put("ml", s.miterLimit).put("nm", "stroke")
                if (s.dash.size >= 2) {
                    val d = JSONArray()
                    d.put(JSONObject().put("n", "d").put("nm", "dash").put("v", JSONObject().put("a", 0).put("k", s.dash[0])))
                    d.put(JSONObject().put("n", "g").put("nm", "gap").put("v", JSONObject().put("a", 0).put("k", s.dash[1])))
                    d.put(JSONObject().put("n", "o").put("nm", "offset").put("v", JSONObject().put("a", 0).put("k", s.dashOffset)))
                    st.put("d", d)
                }
                a.put(st)
            }
            val tracked = listOf(SceneProps.trimStart(idx), SceneProps.trimEnd(idx), SceneProps.trimOffset(idx)).any { engine.hasTrack(BindingKey(layer.id, it)) }
            if (tracked || item.trimStartPct > 0.0 || item.trimEndPct < 100.0 || item.trimOffsetPct != 0.0) {
                val tm = JSONObject().put("ty", "tm").put("nm", "trim").put("m", 1)
                tm.put("s", prop(SceneProps.trimStart(idx), doubleArrayOf(item.trimStartPct), 1, true))
                tm.put("e", prop(SceneProps.trimEnd(idx), doubleArrayOf(item.trimEndPct), 1, true))
                tm.put("o", prop(SceneProps.trimOffset(idx), doubleArrayOf(item.trimOffsetPct * 3.6), 1, true, scaleFrom = 3.6))   // Lottie offset is in degrees
                a.put(tm)
            }
            a.put(identityTr())
            return a
        }

        private fun gradient(stops: List<Paint.Stop>, type: Int, s: Vec2, e: Vec2): JSONObject {
            val k = JSONArray()
            for (st in stops) k.put(st.pos)
            for (st in stops) k.put(st.color.r).put(st.color.g).put(st.color.b)
            return JSONObject().put("ty", "gf").put("t", type)
                .put("s", JSONObject().put("a", 0).put("k", JSONArray().put(s.x).put(s.y)))
                .put("e", JSONObject().put("a", 0).put("k", JSONArray().put(e.x).put(e.y)))
                .put("g", JSONObject().put("p", stops.size).put("k", JSONObject().put("a", 0).put("k", k)))
        }

        private fun animatedPath(src: PathSource.Animated): JSONObject {
            val keys = src.trackKeys()
            val arr = JSONArray()
            // equalise vertex counts across all keys so every Lottie keyframe has the same topology
            var matched = keys.map { it.path }
            val n = matched.maxOf { it.vertices.size }
            matched = matched.map { it.withVertexCount(n) }
            val aligned = ArrayList<BezierPath>(); aligned.add(matched[0])
            for (i in 1 until matched.size) aligned.add(PathMorph.match(aligned[i - 1], matched[i]).second)
            for ((i, k) in keys.withIndex()) {
                val kk = JSONObject().put("t", k.timeMs / 1000.0 * scene.fps).put("s", JSONArray().put(pathJson(aligned[i])))
                if (i < keys.size - 1) {
                    if (k.hold) kk.put("h", 1) else {
                        val b = pathBezier(k.easing)
                        if (b == null) warnings.add("layer '${layer.id}': path easing ${k.easing} approximated as linear")
                        val bb = b ?: doubleArrayOf(0.0, 0.0, 1.0, 1.0)
                        val j = bez(bb[0], bb[1], bb[2], bb[3]); kk.put("o", j.get("o")).put("i", j.get("i"))
                    }
                }
                arr.put(kk)
            }
            return JSONObject().put("a", 1).put("k", arr)
        }
        private fun pathBezier(e: EasingType): DoubleArray? = if (e == EasingType.LINEAR) doubleArrayOf(0.0, 0.0, 1.0, 1.0)
            else easingBezier(Keyframe(com.ahstudio.animation.keyframes.KeyframeId(0), 0, easing = e), Keyframe(com.ahstudio.animation.keyframes.KeyframeId(0), 1))
    }
}
