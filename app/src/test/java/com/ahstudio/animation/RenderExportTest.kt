package com.ahstudio.animation

import com.ahstudio.animation.core.AnimationEngine
import com.ahstudio.animation.easing.EasingType
import com.ahstudio.animation.export.*
import com.ahstudio.animation.keyframes.*
import com.ahstudio.animation.math.Color4
import com.ahstudio.animation.math.Vec2
import com.ahstudio.animation.particles.ParticleConfig
import com.ahstudio.animation.particles.ParticleSystem
import com.ahstudio.animation.parenting.Transform2D
import com.ahstudio.animation.properties.BindingKey
import com.ahstudio.animation.properties.PropertyType
import com.ahstudio.animation.properties.Props
import com.ahstudio.animation.render.*
import com.ahstudio.animation.rig.PuppetMesh
import com.ahstudio.animation.rig.PuppetPin
import com.ahstudio.animation.shape.Shapes
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.abs

class RenderExportTest {
    @get:Rule val tmp = TemporaryFolder()

    private val white = Color4(1.0, 1.0, 1.0, 1.0)
    private val red = Color4(1.0, 0.0, 0.0, 1.0)

    private fun rectPoly(x0: Double, y0: Double, x1: Double, y1: Double) = listOf(Vec2(x0, y0), Vec2(x1, y0), Vec2(x1, y1), Vec2(x0, y1))
    private fun area(c: FloatArray) = c.sumOf { it.toDouble() }

    // ------------------------------------------------------------ rasterizer
    @Test fun rasterRectExactArea() {
        assertEquals(6.0 * 4.0, area(Raster.coverage(listOf(rectPoly(2.0, 3.0, 8.0, 7.0)), 16, 16)), 1e-4)
    }
    @Test fun rasterFractionalEdgesAreAntialiased() {
        val c = Raster.coverage(listOf(rectPoly(1.5, 1.0, 4.0, 2.0)), 8, 8)
        assertEquals(0.5f, c[1 * 8 + 1], 1e-4f); assertEquals(1f, c[1 * 8 + 2], 1e-4f)
        assertEquals(2.5, area(c), 1e-4)
    }
    @Test fun rasterCircleAreaMatchesPiR2() {
        val pts = List(256) { Vec2(32 + 20 * Math.cos(2 * Math.PI * it / 256), 32 + 20 * Math.sin(2 * Math.PI * it / 256)) }
        assertEquals(Math.PI * 400, area(Raster.coverage(listOf(pts), 64, 64)), 1.0)
    }
    @Test fun rasterWindingDoesNotDependOnOrientation() {
        val a = area(Raster.coverage(listOf(rectPoly(2.0, 2.0, 9.0, 9.0)), 16, 16))
        val b = area(Raster.coverage(listOf(rectPoly(2.0, 2.0, 9.0, 9.0).reversed()), 16, 16))
        assertEquals(a, b, 1e-4)
    }
    @Test fun evenOddCutsAHole() {
        val polys = listOf(rectPoly(0.0, 0.0, 12.0, 12.0), rectPoly(4.0, 4.0, 8.0, 8.0))
        assertEquals(144.0 - 16.0, area(Raster.coverage(polys, 16, 16, FillRule.EVEN_ODD)), 1e-3)
        assertEquals(144.0, area(Raster.coverage(polys, 16, 16, FillRule.NON_ZERO)), 1e-3)
    }
    @Test fun polygonsOutsideViewportAreClippedWithCorrectWinding() {
        // covers x in [-50, 5): only columns 0..4 visible
        assertEquals(5.0 * 6.0, area(Raster.coverage(listOf(rectPoly(-50.0, 2.0, 5.0, 8.0)), 16, 16)), 1e-4)
        // extends beyond right and bottom
        assertEquals(6.0 * 6.0, area(Raster.coverage(listOf(rectPoly(10.0, 10.0, 100.0, 100.0)), 16, 16)), 1e-4)
        // spans whole viewport
        assertEquals(256.0, area(Raster.coverage(listOf(rectPoly(-10.0, -10.0, 30.0, 30.0)), 16, 16)), 1e-3)
        // fully outside
        assertEquals(0.0, area(Raster.coverage(listOf(rectPoly(20.0, 20.0, 30.0, 30.0)), 16, 16)), 1e-9)
    }
    @Test fun strokeAreaMatchesLengthTimesWidth() {
        val polys = Stroker.stroke(listOf(listOf(Vec2(4.0, 16.0), Vec2(28.0, 16.0)) to false), 4.0, LineCap.BUTT, LineJoin.MITER)
        assertEquals(24.0 * 4.0, area(Raster.coverage(polys, 32, 32)), 1e-3)
        val sq = Stroker.stroke(listOf(listOf(Vec2(4.0, 16.0), Vec2(28.0, 16.0)) to false), 4.0, LineCap.SQUARE, LineJoin.MITER)
        assertEquals(28.0 * 4.0, area(Raster.coverage(sq, 32, 32)), 1e-3)
        val rd = Stroker.stroke(listOf(listOf(Vec2(4.0, 16.0), Vec2(28.0, 16.0)) to false), 4.0, LineCap.ROUND, LineJoin.ROUND)
        assertEquals(24.0 * 4.0 + Math.PI * 4.0, area(Raster.coverage(rd, 32, 32)), 1.2)  // polygonal caps lose a little area
    }
    @Test fun miterJoinFillsTheCornerAndBevelDoesNot() {
        val corner = listOf(listOf(Vec2(4.0, 4.0), Vec2(20.0, 4.0), Vec2(20.0, 20.0)) to false)
        val miter = area(Raster.coverage(Stroker.stroke(corner, 6.0, LineCap.BUTT, LineJoin.MITER), 32, 32))
        val bevel = area(Raster.coverage(Stroker.stroke(corner, 6.0, LineCap.BUTT, LineJoin.BEVEL), 32, 32))
        assertEquals(19.0 * 6.0 + 13.0 * 6.0, miter, 0.01)   // union of two bars incl. mitred corner square
        assertTrue(bevel < miter)
    }
    @Test fun dashedStrokeCoversAboutHalf() {
        val polys = Stroker.stroke(listOf(listOf(Vec2(0.0, 8.0), Vec2(40.0, 8.0)) to false), 2.0, LineCap.BUTT, LineJoin.MITER, dash = listOf(4.0, 4.0))
        assertEquals(40.0 * 2.0 / 2, area(Raster.coverage(polys, 48, 16)), 0.01)
    }
    @Test fun closedStrokeHasNoGapAtSeam() {
        val sq = Shapes.rect(20.0, 20.0).flatten()
        val polys = Stroker.stroke(listOf(sq.map { Vec2(it.x + 16, it.y + 16) } to true), 4.0, LineCap.BUTT, LineJoin.MITER)
        val cov = Raster.coverage(polys, 32, 32)
        // outer 24x24 minus inner 16x16
        assertEquals(24.0 * 24 - 16 * 16, area(cov), 0.05)
    }

    // ------------------------------------------------------------ scene
    private fun scene(layers: List<Layer>, engine: AnimationEngine = AnimationEngine(), w: Int = 64, h: Int = 48, bg: Color4 = Color4(0.0, 0.0, 0.0, 1.0), useCam: Boolean = false) =
        Scene(w, h, 30.0, 2000, engine, layers, bg, useCamera = useCam)
    private fun square(id: String, size: Double = 10.0, color: Color4 = white, t: Transform2D = Transform2D(), extra: (Layer) -> Unit = {}) =
        Layer(id, LayerContent.Shapes(listOf(ShapeItem(Shapes.rect(size, size), Paint.Solid(color)))), transform = t)
    private fun px(f: Frame, x: Int, y: Int) = f.pixel(x, y)
    private fun red(p: Int) = (p shr 16) and 255
    private fun alpha(p: Int) = p ushr 24

    @Test fun backgroundAndSolidLayerComposite() {
        val s = scene(listOf(Layer("bg", LayerContent.Solid(Color4(0.0, 0.0, 1.0, 1.0), 64, 48))), bg = Color4(1.0, 0.0, 0.0, 1.0))
        val f = SceneRenderer(s).render(0)
        assertEquals(0xFF0000FF.toInt(), px(f, 10, 10))
    }
    @Test fun transparentBackgroundStaysTransparent() {
        val f = SceneRenderer(scene(emptyList(), bg = Color4(0.0, 0.0, 0.0, 0.0))).render(0)
        assertEquals(0, px(f, 3, 3))
    }
    @Test fun staticTransformPlacesShape() {
        val s = scene(listOf(square("a", t = Transform2D(position = Vec2(32.0, 24.0)))))
        val f = SceneRenderer(s).render(0)
        assertEquals(255, red(px(f, 32, 24))); assertEquals(0, red(px(f, 5, 5)))
        assertEquals(255, red(px(f, 28, 20))); assertEquals(0, red(px(f, 20, 24)))
    }
    @Test fun animatedPositionMovesShape() {
        val e = AnimationEngine()
        val key = BindingKey("a", Props.POSITION)
        e.addKeyframe(key, Keyframe(KeyframeId(1), 0, vecValue = Vec2(10.0, 24.0)))
        e.addKeyframe(key, Keyframe(KeyframeId(2), 1000, vecValue = Vec2(50.0, 24.0)))
        val r = SceneRenderer(scene(listOf(square("a")), e))
        assertEquals(255, red(px(r.render(0), 10, 24))); assertEquals(0, red(px(r.render(0), 50, 24)))
        assertEquals(255, red(px(r.render(1000), 50, 24))); assertEquals(0, red(px(r.render(1000), 10, 24)))
        assertEquals(255, red(px(r.render(500), 30, 24)))
    }
    @Test fun animatedOpacityBlends() {
        val e = AnimationEngine()
        val k = BindingKey("a", Props.OPACITY)
        e.addKeyframe(k, Keyframe(KeyframeId(1), 0, value = 0.0)); e.addKeyframe(k, Keyframe(KeyframeId(2), 1000, value = 100.0))
        val r = SceneRenderer(scene(listOf(square("a", 40.0, white, Transform2D(position = Vec2(32.0, 24.0)))), e))
        assertEquals(0, red(px(r.render(0), 32, 24)))
        assertEquals(128, red(px(r.render(500), 32, 24)), 1.0 * 0 + 0.0 + 1.0)       // 50% white over black
        assertEquals(255, red(px(r.render(1000), 32, 24)))
    }
    private fun assertEquals(expected: Int, actual: Int, tol: Double) = assertTrue("expected ~$expected got $actual", abs(expected - actual) <= tol)

    @Test fun parentTransformsChildren() {
        val e = AnimationEngine()
        val k = BindingKey("p", Props.POSITION)
        e.addKeyframe(k, Keyframe(KeyframeId(1), 0, vecValue = Vec2(0.0, 0.0))); e.addKeyframe(k, Keyframe(KeyframeId(2), 1000, vecValue = Vec2(20.0, 0.0)))
        val parent = Layer("p", LayerContent.Null)
        val child = Layer("c", LayerContent.Shapes(listOf(ShapeItem(Shapes.rect(6.0, 6.0), Paint.Solid(white)))), parentId = "p", transform = Transform2D(position = Vec2(10.0, 10.0)))
        val r = SceneRenderer(scene(listOf(parent, child), e))
        assertEquals(255, red(px(r.render(0), 10, 10))); assertEquals(255, red(px(r.render(1000), 30, 10))); assertEquals(0, red(px(r.render(1000), 10, 10)))
    }
    @Test fun parentCycleDoesNotHang() {
        val a = Layer("a", LayerContent.Null, parentId = "b"); val b = Layer("b", LayerContent.Null, parentId = "a")
        SceneRenderer(scene(listOf(a, b, square("c").let { Layer("c", it.content, parentId = "a") }))).render(0)
    }
    @Test fun layerTimeRangeIsHonoured() {
        val l = Layer("a", LayerContent.Solid(white, 64, 48), inMs = 500, outMs = 1000)
        val r = SceneRenderer(scene(listOf(l)))
        assertEquals(0, red(px(r.render(100), 5, 5))); assertEquals(255, red(px(r.render(700), 5, 5))); assertEquals(0, red(px(r.render(1000), 5, 5)))
    }
    @Test fun blendModesAddMultiplyScreen() {
        fun render(mode: RenderBlend): Int {
            val base = Layer("b", LayerContent.Solid(Color4(0.5, 0.5, 0.5, 1.0), 64, 48))
            val top = Layer("t", LayerContent.Solid(Color4(0.5, 0.5, 0.5, 1.0), 64, 48), blend = mode)
            return red(px(SceneRenderer(scene(listOf(base, top))).render(0), 1, 1))
        }
        assertEquals(255, render(RenderBlend.ADD), 1.0); assertEquals(64, render(RenderBlend.MULTIPLY), 1.0)
        assertEquals(191, render(RenderBlend.SCREEN), 1.0); assertEquals(128, render(RenderBlend.NORMAL), 1.0)
    }
    @Test fun gradientFillInterpolates() {
        val g = Paint.Linear(Vec2(0.0, 0.0), Vec2(64.0, 0.0), listOf(Paint.Stop(0.0, Color4(0.0, 0.0, 0.0, 1.0)), Paint.Stop(1.0, Color4(1.0, 1.0, 1.0, 1.0))))
        val l = Layer("g", LayerContent.Shapes(listOf(ShapeItem(Shapes.rect(64.0, 48.0, center = Vec2(32.0, 24.0)), g))))
        val f = SceneRenderer(scene(listOf(l))).render(0)
        assertTrue(red(px(f, 8, 24)) < red(px(f, 32, 24)) && red(px(f, 32, 24)) < red(px(f, 56, 24)))
        assertEquals(128, red(px(f, 32, 24)), 5.0)
    }
    @Test fun strokeIsDrawnAroundShape() {
        val item = ShapeItem(PathSource.Static(Shapes.rect(30.0, 20.0, center = Vec2(32.0, 24.0))), null,
            StrokeStyle(Paint.Solid(white), 4.0, LineCap.BUTT, LineJoin.MITER))
        val f = SceneRenderer(scene(listOf(Layer("s", LayerContent.Shapes(listOf(item)))))).render(0)
        assertEquals(255, red(px(f, 32, 14))); assertEquals(0, red(px(f, 32, 24)))
    }
    @Test fun trimPathRevealsHalfOfStroke() {
        val line = ShapeItem(PathSource.Static(Shapes.line(Vec2(4.0, 24.0), Vec2(60.0, 24.0))), null, StrokeStyle(Paint.Solid(white), 4.0, LineCap.BUTT))
        val e = AnimationEngine()
        val k = BindingKey("l", SceneProps.trimEnd(0))
        e.addKeyframe(k, Keyframe(KeyframeId(1), 0, value = 0.0)); e.addKeyframe(k, Keyframe(KeyframeId(2), 1000, value = 100.0))
        val r = SceneRenderer(scene(listOf(Layer("l", LayerContent.Shapes(listOf(line)))), e))
        val half = r.render(500)
        assertEquals(255, red(px(half, 20, 24))); assertEquals(0, red(px(half, 50, 24)))
        assertEquals(255, red(px(r.render(1000), 50, 24)))
    }
    @Test fun morphingShapeLayerChangesOverTime() {
        val track = com.ahstudio.animation.shape.PathTrack(listOf(
            com.ahstudio.animation.shape.PathTrack.Key(0, Shapes.rect(10.0, 10.0, center = Vec2(32.0, 24.0))),
            com.ahstudio.animation.shape.PathTrack.Key(1000, Shapes.rect(50.0, 10.0, center = Vec2(32.0, 24.0)))))
        val l = Layer("m", LayerContent.Shapes(listOf(ShapeItem(PathSource.Animated(track), Paint.Solid(white)))))
        val r = SceneRenderer(scene(listOf(l)))
        assertEquals(0, red(px(r.render(0), 50, 24))); assertEquals(255, red(px(r.render(1000), 50, 24)))
    }
    @Test fun renderingIsPureFunctionOfTime() {
        val e = AnimationEngine()
        val k = BindingKey("a", Props.ROTATION)
        e.addKeyframe(k, Keyframe(KeyframeId(1), 0, value = 0.0)); e.addKeyframe(k, Keyframe(KeyframeId(2), 1000, value = 90.0))
        e.setWiggle(BindingKey("a", Props.POSITION).also { e.ensureTrack(it, PropertyType.VEC2) }, com.ahstudio.animation.procedural.Wiggle(1, 2.0, 3.0))
        val r = SceneRenderer(scene(listOf(square("a", 20.0, white, Transform2D(position = Vec2(32.0, 24.0)))), e))
        val a = r.render(700); r.render(100); r.render(1900)
        val b = r.render(700)
        assertArrayEquals(a.argb, b.argb)
        assertArrayEquals(a.argb, SceneRenderer(scene(listOf(square("a", 20.0, white, Transform2D(position = Vec2(32.0, 24.0)))), e)).render(700).argb)
    }
    @Test fun motionBlurSmearsFastObjects() {
        val e = AnimationEngine()
        val k = BindingKey("a", Props.POSITION)
        e.addKeyframe(k, Keyframe(KeyframeId(1), 0, vecValue = Vec2(0.0, 24.0))); e.addKeyframe(k, Keyframe(KeyframeId(2), 1000, vecValue = Vec2(600.0, 24.0)))
        fun layer(mb: Boolean) = Layer("a", LayerContent.Shapes(listOf(ShapeItem(Shapes.rect(6.0, 6.0), Paint.Solid(white)))), motionBlur = mb)
        val sharp = SceneRenderer(scene(listOf(layer(false)), e, 64, 48)).render(50)
        val blurred = SceneRenderer(scene(listOf(layer(true)), e, 64, 48)).render(50, MotionBlurSettings(samples = 16, shutterAngleDeg = 360.0))
        fun lit(f: Frame) = (0 until 64).count { red(px(f, it, 24)) > 5 }
        assertTrue("blurred spans more columns (${lit(blurred)} vs ${lit(sharp)})", lit(blurred) > lit(sharp) * 2)
        assertTrue(red(px(blurred, 30, 24)) < 255)               // translucent smear, not solid
    }
    @Test fun blurEffectSpreadsEnergy() {
        val l = Layer("a", LayerContent.Shapes(listOf(ShapeItem(Shapes.rect(10.0, 10.0, center = Vec2(32.0, 24.0)), Paint.Solid(white)))), blurPx = 4.0)
        val sharp = SceneRenderer(scene(listOf(Layer("a", l.content)))).render(0)
        val soft = SceneRenderer(scene(listOf(l))).render(0)
        assertTrue(red(px(soft, 32, 24)) in 100..254 || red(px(soft, 26, 24)) > red(px(sharp, 26, 24)) || red(px(soft, 24, 24)) > 0)
        assertTrue(red(px(soft, 24, 24)) > red(px(sharp, 24, 24)))
    }
    @Test fun imageLayerIsSampledAndTransformed() {
        val img = RasterImage.solid(8, 8, Color4(0.0, 1.0, 0.0, 1.0))
        val l = Layer("i", LayerContent.Image(img), transform = Transform2D(position = Vec2(20.0, 10.0), scale = Vec2(2.0, 2.0)))
        val f = SceneRenderer(scene(listOf(l))).render(0)
        assertEquals(0xFF00FF00.toInt(), px(f, 25, 15)); assertEquals(0xFF000000.toInt(), px(f, 40, 15))
    }
    @Test fun puppetPinsDeformImageOverTime() {
        val img = RasterImage.solid(32, 32, Color4(1.0, 0.0, 1.0, 1.0))
        val mesh = PuppetMesh(32.0, 32.0, 6, 6)
        val l = Layer("p", LayerContent.Puppet(img, mesh) { t ->
            listOf(PuppetPin("a", Vec2(0.0, 0.0), Vec2(0.0, 0.0)), PuppetPin("b", Vec2(32.0, 0.0), Vec2(32.0 + t / 50.0, 0.0)))
        })
        val r = SceneRenderer(scene(listOf(l)))
        val a = r.render(0); val b = r.render(1000)
        assertEquals(0xFFFF00FF.toInt(), px(a, 10, 10)); assertEquals(0, red(px(a, 40, 10)))
        assertFalse(a.argb.contentEquals(b.argb))
    }
    @Test fun puppetMeshHasNoSeams() {
        val img = RasterImage.solid(32, 32, Color4(1.0, 1.0, 1.0, 1.0))
        val l = Layer("p", LayerContent.Puppet(img, PuppetMesh(32.0, 32.0, 8, 8)) { emptyList() })
        val f = SceneRenderer(scene(listOf(l))).render(0)
        for (y in 1 until 31) for (x in 1 until 31) assertEquals("seam at $x,$y", 255, red(px(f, x, y)))
    }
    @Test fun particlesAreDrawn() {
        val sys = ParticleSystem(ParticleConfig(seed = 3, position = Vec2(32.0, 40.0), ratePerSec = 60.0, sizeStart = 6.0, sizeEnd = 6.0,
            colorStart = Color4(1.0, 1.0, 1.0, 1.0), colorEnd = Color4(1.0, 1.0, 1.0, 1.0)))
        val f = SceneRenderer(scene(listOf(Layer("p", LayerContent.Particles(sys, additive = true))))).render(1500)
        assertTrue(f.argb.count { red(it) > 20 } > 20)
    }
    @Test fun threeDLayerShrinksWithDepthAndHonoursCamera() {
        val e = AnimationEngine()
        val z = BindingKey("c", SceneProps.POSITION_Z)
        e.addKeyframe(z, Keyframe(KeyframeId(1), 0, value = 0.0)); e.addKeyframe(z, Keyframe(KeyframeId(2), 1000, value = 1800.0))
        val l = Layer("c", LayerContent.Solid(white, 100, 100), is3D = true, width = 100, height = 100, transform = Transform2D(position = Vec2(14.0, 0.0)))
        val s = Scene(128, 96, 30.0, 2000, e, listOf(l), Color4(0.0, 0.0, 0.0, 1.0), useCamera = true, cameraZoom = 1800.0)
        val r = SceneRenderer(s)
        fun lit(f: Frame) = f.argb.count { red(it) > 128 }
        assertTrue("near ${lit(r.render(0))} far ${lit(r.render(1000))}", lit(r.render(0)) > lit(r.render(1000)) * 2)
    }
    @Test fun rotatedThreeDLayerIsTrapezoid() {
        val e = AnimationEngine()
        e.setBaseValue(BindingKey("c", SceneProps.ROTATION_Y), 50.0)
        e.setExpression(BindingKey("c", SceneProps.ROTATION_Y), "50")
        val l = Layer("c", LayerContent.Solid(white, 60, 60), is3D = true, width = 60, height = 60, transform = Transform2D(position = Vec2(64.0, 48.0), anchor = Vec2(30.0, 30.0)))
        val s = Scene(128, 96, 30.0, 1000, e, listOf(l), Color4(0.0, 0.0, 0.0, 1.0), useCamera = true, cameraZoom = 400.0)
        val f = SceneRenderer(s).render(0)
        fun colHeight(x: Int) = (0 until 96).count { red(px(f, x, it)) > 128 }
        val xs = (0 until 128).filter { colHeight(it) > 0 }
        assertTrue(colHeight(xs.first() + 2) != colHeight(xs.last() - 2))
    }

    // ------------------------------------------------------------ exporters
    private fun animatedScene(): Scene {
        val e = AnimationEngine()
        val k = BindingKey("a", Props.POSITION)
        e.addKeyframe(k, Keyframe(KeyframeId(1), 0, vecValue = Vec2(8.0, 24.0), interpolation = InterpolationType.EASE_IN_OUT))
        e.addKeyframe(k, Keyframe(KeyframeId(2), 1000, vecValue = Vec2(56.0, 24.0)))
        val rot = BindingKey("a", Props.ROTATION)
        e.addKeyframe(rot, Keyframe(KeyframeId(3), 0, value = 0.0, easing = EasingType.BOUNCE_OUT)); e.addKeyframe(rot, Keyframe(KeyframeId(4), 1000, value = 90.0))
        return Scene(64, 48, 10.0, 1000, e, listOf(
            Layer("bg", LayerContent.Solid(Color4(0.1, 0.2, 0.4, 1.0), 64, 48)),
            Layer("a", LayerContent.Shapes(listOf(ShapeItem(Shapes.rect(12.0, 12.0), Paint.Solid(red)))))), Color4(0.0, 0.0, 0.0, 1.0))
    }

    @Test fun pngRoundTripsThroughIndependentDecoder() {
        val f = SceneRenderer(animatedScene()).render(300)
        val img = ImageIO.read(ByteArrayInputStream(PngEncoder.encode(f)))
        assertEquals(f.width, img.width); assertEquals(f.height, img.height)
        for (y in 0 until f.height) for (x in 0 until f.width) assertEquals("pixel $x,$y", f.pixel(x, y), img.getRGB(x, y))
    }
    @Test fun pngKeepsAlpha() {
        val f = SceneRenderer(scene(listOf(square("a", 10.0, red, Transform2D(position = Vec2(32.0, 24.0)))), bg = Color4(0.0, 0.0, 0.0, 0.0))).render(0)
        val img = ImageIO.read(ByteArrayInputStream(PngEncoder.encode(f)))
        assertEquals(0, img.getRGB(2, 2) ushr 24); assertEquals(255, img.getRGB(32, 24) ushr 24)
    }
    @Test fun apngHasAnimationChunksAndFirstFrameDecodes() {
        val s = animatedScene(); val r = SceneRenderer(s)
        val frames = (0 until 4).map { r.render(it * 100L) }
        val bytes = PngEncoder.encodeApng(frames, List(4) { 100 })
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.contains("acTL")); assertEquals(4, Regex("fcTL").findAll(text).count()); assertEquals(3, Regex("fdAT").findAll(text).count())
        val first = ImageIO.read(ByteArrayInputStream(bytes))
        assertEquals(frames[0].pixel(5, 5), first.getRGB(5, 5))
    }
    @Test fun gifDecodesWithRightFrameCountSizeAndColours() {
        val s = animatedScene(); val r = SceneRenderer(s)
        val frames = (0 until 6).map { r.render(it * 100L) }
        val bytes = GifEncoder.encode(frames, List(6) { 100 })
        val reader = ImageIO.getImageReadersByFormatName("gif").next()
        reader.input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
        assertEquals(6, reader.getNumImages(true))
        val img = reader.read(0)
        assertEquals(64, img.width); assertEquals(48, img.height)
        val bg = img.getRGB(60, 3); val want = frames[0].pixel(60, 3)
        for (shift in listOf(16, 8, 0)) assertTrue(abs(((bg shr shift) and 255) - ((want shr shift) and 255)) <= 2)
    }
    @Test fun gifSupportsTransparencyAndManyColours() {
        val noise = IntArray(40 * 40) { val v = (it * 2654435761L).toInt(); 0xFF000000.toInt() or (v and 0xFFFFFF) }
        val bytes = GifEncoder.encode(listOf(Frame(40, 40, noise)), listOf(100))
        val img = ImageIO.read(ByteArrayInputStream(bytes)); assertEquals(40, img.width)
        val withHole = IntArray(16 * 16) { if (it % 16 < 8) 0 else 0xFFFF0000.toInt() }
        val g2 = ImageIO.read(ByteArrayInputStream(GifEncoder.encode(listOf(Frame(16, 16, withHole)), listOf(100))))
        assertEquals(0, g2.getRGB(2, 2) ushr 24); assertEquals(255, g2.getRGB(12, 2) ushr 24)
    }
    @Test fun gifDelaysKeepTotalDuration() {
        val f = Frame(4, 4, IntArray(16) { 0xFF123456.toInt() })
        val bytes = GifEncoder.encode(List(3) { f }, List(3) { 33 })        // 33ms -> 3.3 cs each
        val delays = ArrayList<Int>()
        var i = 0
        while (i < bytes.size - 5) { if (bytes[i] == 0x21.toByte() && bytes[i + 1] == 0xF9.toByte()) { delays.add((bytes[i + 4].toInt() and 255) or ((bytes[i + 5].toInt() and 255) shl 8)); i += 6 } else i++ }
        assertEquals(10, delays.sum())
    }
    @Test fun y4mHasValidHeaderAndFrameSizes() {
        val bos = ByteArrayOutputStream()
        val res = SceneExporter.export(animatedScene(), Y4mSink(bos))
        val header = String(bos.toByteArray(), 0, 60, Charsets.US_ASCII).substringBefore('\n')
        assertTrue(header, header.startsWith("YUV4MPEG2 W64 H48 F10:1"))
        assertEquals(10, res.framesWritten)
        assertEquals(header.length + 1 + 10 * (6 + 64 * 48 * 3 / 2), bos.size())
    }
    @Test fun exporterReportsProgressAndSupportsRangesAndCancel() {
        val sink = MemorySink(); val calls = ArrayList<Int>()
        val r = SceneExporter.export(animatedScene(), sink, ExportOptions(startFrame = 2, endFrame = 8, frameStep = 2)) { d, _ -> calls.add(d) }
        assertEquals(3, r.framesWritten); assertEquals(listOf(1, 2, 3), calls); assertEquals(listOf(200L, 400L, 600L), sink.times); assertTrue(sink.ended)
        val cancel = CancelToken(); val sink2 = MemorySink()
        val r2 = SceneExporter.export(animatedScene(), sink2, cancel = cancel) { d, _ -> if (d == 3) cancel.cancel() }
        assertTrue(r2.cancelled); assertEquals(3, r2.framesWritten); assertTrue(sink2.wasCancelled && sink2.ended)
    }
    @Test fun pngSequenceSinkWritesNumberedFiles() {
        val dir = tmp.newFolder("seq")
        val sink = PngSequenceSink(dir)
        SceneExporter.export(animatedScene(), sink, ExportOptions(endFrame = 3))
        assertEquals(listOf("frame_00001.png", "frame_00002.png", "frame_00003.png"), dir.list()!!.sorted())
        assertEquals(64, ImageIO.read(dir.resolve("frame_00002.png")).width)
    }
    @Test fun exportEqualsPreview() {
        val s = animatedScene(); val sink = MemorySink()
        SceneExporter.export(s, sink)
        val r = SceneRenderer(s)
        for (i in listOf(0, 4, 9)) assertArrayEquals(r.render(s.timeOfFrame(i)).argb, sink.frames[i].argb)
    }

    @Test fun lottieIsStructurallyValid() {
        val rep = LottieExporter.export(animatedScene())
        val j = JSONObject(rep.json)
        assertEquals(64, j.getInt("w")); assertEquals(10.0, j.getDouble("fr"), 0.0); assertEquals(10.0, j.getDouble("op"), 1e-9)
        val layers = j.getJSONArray("layers")
        assertEquals(2, layers.length())
        val shape = layers.getJSONObject(0)                           // top layer first
        assertEquals(4, shape.getInt("ty")); assertEquals("a", shape.getString("nm"))
        val p = shape.getJSONObject("ks").getJSONObject("p")
        assertEquals(1, p.getInt("a"))
        val k0 = p.getJSONArray("k").getJSONObject(0)
        assertEquals(0.42, k0.getJSONObject("o").getJSONArray("x").getDouble(0), 1e-9)
        assertEquals(3, k0.getJSONArray("s").length())
        assertTrue("bounce rotation must be baked", rep.warnings.any { it.contains("baked") })
        val rotK = shape.getJSONObject("ks").getJSONObject("r").getJSONArray("k")
        assertTrue(rotK.length() >= 10)
        assertEquals(1, layers.getJSONObject(1).getInt("ty")); assertEquals("#1a3366", layers.getJSONObject(1).getString("sc"))
    }
    @Test fun lottieSkipsUnsupportedLayersWithWarnings() {
        val sys = ParticleSystem(ParticleConfig())
        val s = Scene(32, 32, 30.0, 1000, AnimationEngine(), listOf(Layer("p", LayerContent.Particles(sys)), Layer("n", LayerContent.Null)))
        val rep = LottieExporter.export(s)
        assertEquals(1, rep.layersExported); assertTrue(rep.warnings.any { it.contains("particle") })
    }
    @Test fun lottieStrokeTrimAndGradient() {
        val e = AnimationEngine()
        val tk = BindingKey("l", SceneProps.trimEnd(0))
        e.addKeyframe(tk, Keyframe(KeyframeId(1), 0, value = 0.0)); e.addKeyframe(tk, Keyframe(KeyframeId(2), 1000, value = 100.0))
        val item = ShapeItem(PathSource.Static(Shapes.line(Vec2(0.0, 0.0), Vec2(10.0, 0.0))), Paint.Linear(Vec2.ZERO, Vec2(10.0, 0.0), listOf(Paint.Stop(0.0, white), Paint.Stop(1.0, red))),
            StrokeStyle(Paint.Solid(white), 2.0, LineCap.ROUND, LineJoin.MITER, dash = listOf(3.0, 2.0)))
        val s = Scene(32, 32, 30.0, 1000, e, listOf(Layer("l", LayerContent.Shapes(listOf(item)))))
        val txt = LottieExporter.export(s).json
        assertTrue(txt.contains("\"ty\":\"tm\"") && txt.contains("\"ty\":\"gf\"") && txt.contains("\"ty\":\"st\"") && txt.contains("\"n\":\"d\""))
    }
    @Test fun lottieEmbedsImageAssets() {
        val s = Scene(16, 16, 30.0, 1000, AnimationEngine(), listOf(Layer("i", LayerContent.Image(RasterImage.solid(4, 4, red)))))
        val j = JSONObject(LottieExporter.export(s).json)
        assertEquals(1, j.getJSONArray("assets").length())
        assertTrue(j.getJSONArray("assets").getJSONObject(0).getString("p").startsWith("data:image/png;base64,"))
    }
}
