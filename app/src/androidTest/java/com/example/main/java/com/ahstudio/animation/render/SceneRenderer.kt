package com.ahstudio.animation.render

import com.ahstudio.animation.camera.*
import com.ahstudio.animation.keyframes.EvaluatedValue
import com.ahstudio.animation.math.Color4
import com.ahstudio.animation.math.Mat3
import com.ahstudio.animation.math.Vec2
import com.ahstudio.animation.math.Vec3
import com.ahstudio.animation.parenting.MapParentResolver
import com.ahstudio.animation.parenting.Transform2D
import com.ahstudio.animation.parenting.WorldTransformEvaluator
import com.ahstudio.animation.core.AnimationSnapshot
import com.ahstudio.animation.properties.BindingKey
import com.ahstudio.animation.properties.Props
import com.ahstudio.animation.rig.PuppetPin
import kotlin.math.*

/**
 * Deterministic software renderer: Scene + time -> Frame. The same code path serves preview and export, so frames are
 * bit-identical regardless of how the timeline was scrubbed (all inputs are pure functions of time).
 */
class SceneRenderer(private val scene: Scene) {
    private val w = scene.width
    private val h = scene.height
    private val layerById = scene.layers.associateBy { it.id }

    fun render(timeMs: Long, motionBlur: MotionBlurSettings? = null): Frame = renderCanvas(timeMs, motionBlur).toFrame()

    fun renderCanvas(timeMs: Long, motionBlur: MotionBlurSettings? = null): Canvas {
        val comp = Canvas(w, h)
        comp.fill(scene.background)
        val snapCache = HashMap<Long, AnimationSnapshot>()
        fun snap(t: Long) = snapCache.getOrPut(t) { scene.engine.evaluate(t) }
        val mb = motionBlur?.takeIf { it.samples > 1 }
        val frameMs = 1000.0 / scene.fps
        val times: List<Long> = if (mb == null) listOf(timeMs) else {
            val shutter = frameMs * mb.shutterAngleDeg / 360.0
            val start = timeMs + frameMs * mb.shutterPhaseDeg / 360.0
            List(mb.samples) { Math.round(start + shutter * (it + 0.5) / mb.samples) }
        }
        for (layer in scene.layers) {
            if (!layer.visible || timeMs < layer.inMs || timeMs >= layer.outMs) continue
            if (layer.content is LayerContent.Null) continue
            val ts = if (layer.motionBlur && mb != null) times else listOf(timeMs)
            val acc = if (ts.size > 1) Canvas(w, h) else null
            var opacity = 1.0
            for (t in ts) {
                val s = snap(t)
                val lc = Canvas(w, h)
                opacity = renderLayer(layer, t, s, lc)
                if (acc == null) { comp.composite(lc, opacity.toFloat(), layer.blend); }
                else for (i in acc.px.indices) acc.px[i] += lc.px[i] * opacity.toFloat() / ts.size
            }
            if (acc != null) comp.composite(acc, 1f, layer.blend)
        }
        return comp
    }

    // ---------------------------------------------------------------- property access
    private fun f(s: AnimationSnapshot, id: String, prop: String, def: Double) = s.floatValue(BindingKey(id, prop)) ?: def
    private fun v2(s: AnimationSnapshot, id: String, prop: String, def: Vec2) = s.vec2Value(BindingKey(id, prop)) ?: def

    private fun localTransform(l: Layer, s: AnimationSnapshot): Transform2D {
        val d = l.transform
        return Transform2D(
            anchor = v2(s, l.id, Props.ANCHOR, d.anchor), position = v2(s, l.id, Props.POSITION, d.position),
            scale = v2(s, l.id, Props.SCALE, d.scale * 100.0) / 100.0, rotationDeg = f(s, l.id, Props.ROTATION, d.rotationDeg),
            skew = v2(s, l.id, Props.SKEW, d.skew), opacity = f(s, l.id, Props.OPACITY, d.opacity), flipX = d.flipX, flipY = d.flipY)
    }

    private fun worldMatrix(l: Layer, s: AnimationSnapshot): Mat3 {
        val parents = HashMap<String, String>(); val locals = HashMap<String, Transform2D>()
        var cur: Layer? = l; var guard = 0
        while (cur != null && guard++ < 64) {
            locals[cur.id] = localTransform(cur, s)
            cur.parentId?.let { parents[cur!!.id] = it }
            cur = cur.parentId?.let { layerById[it] }
        }
        return WorldTransformEvaluator.worldMatrix(l.id, MapParentResolver(parents, locals))
    }

    // ---------------------------------------------------------------- layer
    /** Renders [l] into [out] (transparent canvas) and returns the layer opacity (0..1) to composite with. */
    private fun renderLayer(l: Layer, t: Long, s: AnimationSnapshot, out: Canvas): Double {
        val lt = localTransform(l, s)
        val opacity = (lt.opacity / 100.0).coerceIn(0.0, 1.0)
        if (opacity <= 0.0) return 0.0
        val blur = f(s, l.id, SceneProps.BLUR, l.blurPx)
        if (l.is3D && scene.useCamera) {
            renderLayer3D(l, t, s, out)
        } else {
            drawContent(l, l.content, t, s, worldMatrix(l, s), out)
            out.blur(blur)
        }
        return opacity
    }

    private fun drawContent(l: Layer, c: LayerContent, t: Long, snap: AnimationSnapshot, m: Mat3, out: Canvas) {
        when (c) {
            is LayerContent.Null -> {}
            is LayerContent.Solid -> {
                val poly = listOf(Vec2(0.0, 0.0), Vec2(c.width.toDouble(), 0.0), Vec2(c.width.toDouble(), c.height.toDouble()), Vec2(0.0, c.height.toDouble())).map { m.transform(it) }
                out.fillCoverage(Raster.coverage(listOf(poly), out.w, out.h), Paint.Solid(c.color), Mat3.IDENTITY, 1.0, RenderBlend.NORMAL)
            }
            is LayerContent.Shapes -> drawShapes(l.id, c, t, snap, m, out)
            is LayerContent.Image -> {
                val img = c.image
                val quad = listOf(Vec2(0.0, 0.0), Vec2(img.width.toDouble(), 0.0), Vec2(img.width.toDouble(), img.height.toDouble()), Vec2(0.0, img.height.toDouble()))
                val verts = quad.map { m.transform(it) }
                drawTextured(out, img, verts, quad, intArrayOf(0, 1, 2, 0, 2, 3))
            }
            is LayerContent.Puppet -> {
                val deformed = c.mesh.deform(c.pins(t))
                val verts = deformed.map { m.transform(it) }
                val uvPx = c.mesh.uv.map { Vec2(it.x * c.image.width, it.y * c.image.height) }
                drawTextured(out, c.image, verts, uvPx, c.mesh.triangles)
            }
            is LayerContent.Particles -> drawParticles(c, t, m, out)
        }
    }

    // ---------------------------------------------------------------- shapes
    private fun drawShapes(layerId: String, c: LayerContent.Shapes, t: Long, snap: AnimationSnapshot, m: Mat3, out: Canvas) {
        val inv = m.inverse()
        c.items.forEachIndexed { idx, item ->
            val path = item.path.at(t)
            val ts = trimOf(layerId, item, idx, snap)
            val pieces: List<com.ahstudio.animation.shape.BezierPath> =
                if (ts == null) listOf(path) else path.trim(ts.first, ts.second, ts.third)
            if (pieces.isEmpty()) return@forEachIndexed
            val fillPaint = item.fill?.let { p -> if (item.fillColor != null && p is Paint.Solid) Paint.Solid(item.fillColor.valueAt(t)) else p }
            if (fillPaint != null && ts == null) {
                val poly = path.flatten(0.1).map { m.transform(it) }
                if (poly.size >= 3) out.fillCoverage(Raster.coverage(listOf(poly), out.w, out.h, item.fillRule), fillPaint, inv, 1.0, RenderBlend.NORMAL)
            }
            val st = item.stroke
            if (st != null && st.width > 0.0) {
                val lines = pieces.map { p -> p.flatten(0.1).map { m.transform(it) } to (p.closed) }
                val scale = sqrt(abs(m.m00 * m.m11 - m.m01 * m.m10))
                val polys = Stroker.stroke(lines, st.width * scale, st.cap, st.join, st.miterLimit, st.dash.map { it * scale }, st.dashOffset * scale)
                val sp = st.color?.let { Paint.Solid(it.valueAt(t)) } ?: st.paint
                out.fillCoverage(Raster.coverage(polys, out.w, out.h, FillRule.NON_ZERO), sp, inv, 1.0, RenderBlend.NORMAL)
            }
        }
    }

    /** (start, end, offset) as fractions, or null when no trim is in effect. */
    private fun trimOf(layerId: String, item: ShapeItem, idx: Int, snap: AnimationSnapshot): Triple<Double, Double, Double>? {
        val s = snap.floatValue(BindingKey(layerId, SceneProps.trimStart(idx))) ?: item.trimStartPct
        val e = snap.floatValue(BindingKey(layerId, SceneProps.trimEnd(idx))) ?: item.trimEndPct
        val o = snap.floatValue(BindingKey(layerId, SceneProps.trimOffset(idx))) ?: item.trimOffsetPct
        if (s <= 0.0 && e >= 100.0 && o == 0.0) return null
        return Triple(s / 100.0, e / 100.0, o / 100.0)
    }

    // ---------------------------------------------------------------- textured triangles (images, puppets)
    /** Sums per-triangle coverage*colour then normalises, so shared mesh edges don't leave seams. */
    private fun drawTextured(out: Canvas, img: RasterImage, verts: List<Vec2>, uv: List<Vec2>, tris: IntArray) {
        val cw = out.w; val ch = out.h
        val sumC = FloatArray(cw * ch * 4); val sumA = FloatArray(cw * ch)
        val tmp = FloatArray(4)
        var k = 0
        while (k + 2 < tris.size) {
            val i0 = tris[k]; val i1 = tris[k + 1]; val i2 = tris[k + 2]; k += 3
            val a = verts[i0]; val b = verts[i1]; val c = verts[i2]
            val area = (b.x - a.x) * (c.y - a.y) - (c.x - a.x) * (b.y - a.y)
            if (abs(area) < 1e-9) continue
            val minX = max(0, floor(minOf(a.x, b.x, c.x)).toInt()); val maxX = min(cw - 1, ceil(maxOf(a.x, b.x, c.x)).toInt())
            val minY = max(0, floor(minOf(a.y, b.y, c.y)).toInt()); val maxY = min(ch - 1, ceil(maxOf(a.y, b.y, c.y)).toInt())
            val inv = 1.0 / area
            for (y in minY..maxY) for (x in minX..maxX) {
                var hits = 0
                for (sy in 0 until 4) for (sx in 0 until 4) {
                    val px = x + (sx + 0.5) / 4; val py = y + (sy + 0.5) / 4
                    val w0 = ((b.x - px) * (c.y - py) - (c.x - px) * (b.y - py)) * inv
                    val w1 = ((c.x - px) * (a.y - py) - (a.x - px) * (c.y - py)) * inv
                    val w2 = 1.0 - w0 - w1
                    if (w0 >= 0 && w1 >= 0 && w2 >= 0) hits++
                }
                if (hits == 0) continue
                val cov = hits / 16f
                // barycentric of the pixel centre (extrapolated is fine for edge pixels)
                val px = x + 0.5; val py = y + 0.5
                val w0 = ((b.x - px) * (c.y - py) - (c.x - px) * (b.y - py)) * inv
                val w1 = ((c.x - px) * (a.y - py) - (a.x - px) * (c.y - py)) * inv
                val w2 = 1.0 - w0 - w1
                val u = uv[i0].x * w0 + uv[i1].x * w1 + uv[i2].x * w2
                val v = uv[i0].y * w0 + uv[i1].y * w1 + uv[i2].y * w2
                img.sample(u.coerceIn(0.0, img.width.toDouble()), v.coerceIn(0.0, img.height.toDouble()), tmp)
                val o = y * cw + x
                // un-premultiply so normalisation across triangles is colour-correct
                val ta = tmp[3]
                if (ta > 1e-6f) { sumC[o * 4] += tmp[0] / ta * cov; sumC[o * 4 + 1] += tmp[1] / ta * cov; sumC[o * 4 + 2] += tmp[2] / ta * cov }
                sumC[o * 4 + 3] += ta * cov
                sumA[o] += cov
            }
        }
        for (i in 0 until cw * ch) {
            val cov = sumA[i]
            if (cov <= 0f) continue
            val a = sumC[i * 4 + 3] / cov                           // average source alpha across covering triangles
            val cc = min(cov, 1f)
            val r = sumC[i * 4] / cov; val g = sumC[i * 4 + 1] / cov; val b = sumC[i * 4 + 2] / cov
            val fa = a * cc
            out.blendPremul(i, r * fa, g * fa, b * fa, fa, RenderBlend.NORMAL)
        }
    }

    // ---------------------------------------------------------------- particles
    private fun drawParticles(c: LayerContent.Particles, t: Long, m: Mat3, out: Canvas) {
        val soft = c.softness.coerceIn(0.0, 1.0)
        val sc = sqrt(abs(m.m00 * m.m11 - m.m01 * m.m10))
        for (p in c.system.particlesAt(t)) {
            val pos = m.transform(p.pos)
            val rad = max(0.3, p.size * sc / 2)
            val x0 = max(0, floor(pos.x - rad - 1).toInt()); val x1 = min(out.w - 1, ceil(pos.x + rad + 1).toInt())
            val y0 = max(0, floor(pos.y - rad - 1).toInt()); val y1 = min(out.h - 1, ceil(pos.y + rad + 1).toInt())
            for (y in y0..y1) for (x in x0..x1) {
                val d = hypot(x + 0.5 - pos.x, y + 0.5 - pos.y)
                // hard disc with 1px AA edge, blended with a soft radial falloff according to `softness`
                val edge = ((rad + 0.5 - d)).coerceIn(0.0, 1.0)
                val fall = (1.0 - d / rad).coerceIn(0.0, 1.0)
                val cov = edge * ((1 - soft) + soft * fall)
                if (cov <= 0.0) continue
                val a = (p.color.a * cov).toFloat()
                val bm = if (c.additive) RenderBlend.ADD else RenderBlend.NORMAL
                out.blendPremul(y * out.w + x, p.color.r.toFloat() * a, p.color.g.toFloat() * a, p.color.b.toFloat() * a, a, bm)
            }
        }
    }

    // ---------------------------------------------------------------- 3D
    private fun camera(s: AnimationSnapshot): Camera3D {
        val id = SceneProps.CAMERA_ID
        val cx = w / 2.0; val cy = h / 2.0
        val zoom = f(s, id, SceneProps.CAM_ZOOM, scene.cameraZoom)
        val pos = Vec3(f(s, id, SceneProps.CAM_POS_X, cx), f(s, id, SceneProps.CAM_POS_Y, cy), f(s, id, SceneProps.CAM_POS_Z, -zoom))
        val poi = Vec3(f(s, id, SceneProps.CAM_POI_X, cx), f(s, id, SceneProps.CAM_POI_Y, cy), f(s, id, SceneProps.CAM_POI_Z, 0.0))
        return Camera3D(pos, poi, zoom, w.toDouble(), h.toDouble(), f(s, id, SceneProps.CAM_APERTURE, 0.0), f(s, id, SceneProps.CAM_FOCUS, zoom))
    }

    private fun renderLayer3D(l: Layer, t: Long, s: AnimationSnapshot, out: Canvas) {
        val lw = if (l.width > 0) l.width else w; val lh = if (l.height > 0) l.height else h
        val local = Canvas(lw, lh)
        drawContent(l, l.content, t, s, Mat3.IDENTITY, local)
        val lt = localTransform(l, s)
        val layer3 = Layer3D(
            position = Vec3(lt.position.x, lt.position.y, f(s, l.id, SceneProps.POSITION_Z, l.z)),
            anchor = Vec3(lt.anchor.x, lt.anchor.y, 0.0),
            scalePercent = Vec3(lt.scale.x * 100, lt.scale.y * 100, 100.0),
            rotationDeg = Vec3(f(s, l.id, SceneProps.ROTATION_X, 0.0), f(s, l.id, SceneProps.ROTATION_Y, 0.0), lt.rotationDeg))
        val cam = camera(s)
        val quad = cam.projectLayer(layer3, lw.toDouble(), lh.toDouble()) ?: return
        val q = quad.map { Vec2(it.x, it.y) }
        val hg = Homography.rectToQuad(lw.toDouble(), lh.toDouble(), q) ?: return
        val inv = hg.inverse() ?: return
        val mask = Raster.coverage(listOf(q), out.w, out.h)
        val minX = max(0, floor(q.minOf { it.x }).toInt()); val maxX = min(out.w - 1, ceil(q.maxOf { it.x }).toInt())
        val minY = max(0, floor(q.minOf { it.y }).toInt()); val maxY = min(out.h - 1, ceil(q.maxOf { it.y }).toInt())
        val tmp = FloatArray(4)
        val tex = RasterImage(lw, lh, local.toFrame().argb)
        for (y in minY..maxY) for (x in minX..maxX) {
            val cov = mask[y * out.w + x]
            if (cov <= 0f) continue
            val p = inv.apply(x + 0.5, y + 0.5)
            tex.sample(p.x.coerceIn(0.0, lw.toDouble()), p.y.coerceIn(0.0, lh.toDouble()), tmp)
            val a = tmp[3] * cov
            out.blendPremul(y * out.w + x, tmp[0] * cov, tmp[1] * cov, tmp[2] * cov, a, RenderBlend.NORMAL)
        }
        // depth of field: blur by the circle of confusion at the layer's anchor depth
        val centreDepth = quad.map { it.depth }.average()
        out.blur(cam.blurRadius(centreDepth) + f(s, l.id, SceneProps.BLUR, l.blurPx))
    }
}
