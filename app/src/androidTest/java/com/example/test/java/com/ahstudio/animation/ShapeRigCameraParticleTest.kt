package com.ahstudio.animation

import com.ahstudio.animation.camera.*
import com.ahstudio.animation.easing.EasingType
import com.ahstudio.animation.math.Mat3
import com.ahstudio.animation.math.Vec2
import com.ahstudio.animation.math.Vec3
import com.ahstudio.animation.particles.*
import com.ahstudio.animation.rig.*
import com.ahstudio.animation.shape.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class ShapeRigCameraParticleTest {
    // ---------------- shapes ----------------
    @Test fun ellipseLengthMatchesCircumference() {
        val c = Shapes.ellipse(50.0, 50.0)
        assertEquals(2 * PI * 50, c.length, 0.2)
    }
    @Test fun rectLengthAndBounds() {
        val r = Shapes.rect(100.0, 40.0)
        assertEquals(280.0, r.length, 1e-6)
        val b = r.bounds(); assertArrayEquals(doubleArrayOf(-50.0, -20.0, 50.0, 20.0), b, 1e-9)
    }
    @Test fun roundedRectIsShorterThanSharp() {
        assertTrue(Shapes.rect(100.0, 40.0, 10.0).length < 280.0)
        assertEquals(8, Shapes.rect(100.0, 40.0, 10.0).vertices.size)
    }
    @Test fun starAndPolygonVertexCounts() {
        assertEquals(10, Shapes.star(5, 50.0, 20.0).vertices.size)
        assertEquals(6, Shapes.polygon(6, 30.0).vertices.size)
    }
    @Test fun splitPreservesShape() {
        val c = Shapes.ellipse(40.0, 30.0)
        val s = c.withVertexCount(16)
        assertEquals(16, s.vertices.size)
        assertEquals(c.length, s.length, 0.05)
        val bb = s.bounds(); val ba = c.bounds()
        for (i in 0..3) assertEquals(ba[i], bb[i], 0.05)
    }
    @Test fun splitClosingSegmentKeepsClosedLength() {
        val t = Shapes.polygon(3, 50.0)
        val s = t.splitSegment(2, 0.5)
        assertEquals(4, s.vertices.size); assertEquals(t.length, s.length, 1e-6)
    }
    @Test fun morphEndpointsAndMidpoint() {
        val a = Shapes.rect(100.0, 100.0); val b = Shapes.ellipse(50.0, 50.0)
        val m0 = PathMorph.morph(a, b, 0.0); val m1 = PathMorph.morph(a, b, 1.0)
        assertEquals(a.length, m0.length, 0.5); assertEquals(b.length, m1.length, 0.5)
        val mid = PathMorph.morph(a, b, 0.5)
        assertTrue(mid.length < a.length && mid.length > b.length)
    }
    @Test fun morphAlignsStartVertexToAvoidTwisting() {
        val a = Shapes.rect(100.0, 100.0)
        val b = a.rotated(2)                                      // same shape, different start vertex
        val m = PathMorph.morph(a, b, 0.5)
        assertEquals(a.length, m.length, 1e-6)                    // no collapsing "twist" through the centre
    }
    @Test fun trimMiddleHalfOfLine() {
        val l = Shapes.line(Vec2(0.0, 0.0), Vec2(100.0, 0.0))
        val t = l.trim(0.25, 0.75).single()
        assertEquals(50.0, t.length, 0.01)
        assertEquals(25.0, t.vertices.first().p.x, 0.01); assertEquals(75.0, t.vertices.last().p.x, 0.01)
    }
    @Test fun trimClosedPathWithOffsetWrapsIntoTwoPieces() {
        val r = Shapes.rect(100.0, 100.0)
        val pieces = r.trim(0.0, 0.5, offset = 0.75)
        assertEquals(2, pieces.size)
        assertEquals(200.0, pieces.sumOf { it.length }, 0.5)
    }
    @Test fun trimFullAndEmpty() {
        val e = Shapes.ellipse(10.0, 10.0)
        assertEquals(1, e.trim(0.0, 1.0).size)
        assertTrue(e.trim(0.5, 0.5).isEmpty())
    }
    @Test fun pointAndTangentAtDistance() {
        val l = Shapes.line(Vec2(0.0, 0.0), Vec2(100.0, 0.0))
        assertEquals(30.0, l.pointAt(30.0).x, 0.01)
        assertEquals(1.0, l.tangentAt(10.0).x, 1e-9)
    }
    @Test fun pathTrackMorphsWithEasing() {
        val a = Shapes.rect(100.0, 100.0); val b = Shapes.rect(200.0, 100.0)
        val tr = PathTrack(listOf(PathTrack.Key(0, a, EasingType.LINEAR), PathTrack.Key(1000, b)))
        assertEquals(300.0 * 0.0 + 400.0, tr.valueAt(0).length, 1e-6)
        assertEquals(500.0, tr.valueAt(500).length, 1e-6)
        assertEquals(600.0, tr.valueAt(2000).length, 1e-6)
    }
    @Test fun transformScalesPath() {
        val r = Shapes.rect(10.0, 10.0).transform(Mat3.scaling(3.0, 3.0))
        assertEquals(120.0, r.length, 1e-6)
    }

    // ---------------- rig ----------------
    @Test fun forwardKinematicsChain() {
        val sk = Skeleton(listOf(Bone("a", null, 100.0), Bone("b", "a", 50.0, restAngleDeg = 90.0)))
        val p = sk.pose()
        assertEquals(100.0, p.getValue("a").tail.x, 1e-9)
        assertEquals(100.0, p.getValue("b").head.x, 1e-9)
        assertEquals(50.0, p.getValue("b").tail.y, 1e-9)           // b points +Y (rest 90deg)
        val q = sk.pose(mapOf("a" to 90.0))
        assertEquals(100.0, q.getValue("a").tail.y, 1e-9)
        assertEquals(-50.0, q.getValue("b").tail.x, 1e-9)          // rotated with parent
    }
    @Test fun skeletonDropsCyclesAndOrphans() {
        val sk = Skeleton(listOf(Bone("a", "b", 1.0), Bone("b", "a", 1.0), Bone("c", "zzz", 1.0), Bone("d", null, 1.0)))
        assertEquals(listOf("d"), sk.bones.map { it.id })
    }
    @Test fun jointLimitsClamp() {
        val sk = Skeleton(listOf(Bone("a", null, 10.0, minAngleDeg = -10.0, maxAngleDeg = 10.0)))
        assertEquals(10.0, sk.pose(mapOf("a" to 90.0)).getValue("a").worldAngleDeg, 1e-9)
    }
    @Test fun twoBoneIkReachesTarget() {
        val root = Vec2(0.0, 0.0); val target = Vec2(60.0, 40.0)
        for (bend in listOf(true, false)) {
            val (a1, a2) = IK.twoBone(root, target, 50.0, 50.0, bend)
            val sk = Skeleton(listOf(Bone("u", null, 50.0), Bone("l", "u", 50.0)))
            val p = sk.pose(mapOf("u" to a1, "l" to a2))
            assertEquals(target.x, p.getValue("l").tail.x, 1e-6); assertEquals(target.y, p.getValue("l").tail.y, 1e-6)
        }
    }
    @Test fun twoBoneIkOppositeBendsDiffer() {
        val a = IK.twoBone(Vec2.ZERO, Vec2(60.0, 40.0), 50.0, 50.0, true)
        val b = IK.twoBone(Vec2.ZERO, Vec2(60.0, 40.0), 50.0, 50.0, false)
        assertTrue(a.second * b.second < 0)
    }
    @Test fun twoBoneIkOutOfReachStretches() {
        val (a1, a2) = IK.twoBone(Vec2.ZERO, Vec2(500.0, 0.0), 50.0, 50.0)
        assertEquals(0.0, a1, 1e-3); assertEquals(0.0, a2, 1e-3)
    }
    @Test fun fabrikConvergesAndKeepsLengths() {
        val joints = listOf(Vec2(0.0, 0.0), Vec2(40.0, 0.0), Vec2(80.0, 0.0), Vec2(120.0, 0.0))
        val target = Vec2(50.0, 60.0)
        val s = IK.fabrik(joints, target, 64)
        assertEquals(0.0, (s.last() - target).length(), 0.01)
        assertEquals(Vec2.ZERO, s.first())
        for (i in 0 until 3) assertEquals(40.0, (s[i + 1] - s[i]).length(), 1e-6)
    }
    @Test fun fabrikOutOfReachPointsAtTarget() {
        val s = IK.fabrik(listOf(Vec2.ZERO, Vec2(10.0, 0.0), Vec2(20.0, 0.0)), Vec2(0.0, 100.0))
        assertEquals(20.0, s.last().y, 1e-9)
    }
    @Test fun fabrikAnglesDriveSkeletonToSameTip() {
        val sk = Skeleton(listOf(Bone("a", null, 40.0), Bone("b", "a", 40.0), Bone("c", "b", 40.0)))
        val rest = sk.pose()
        val joints = IK.chainJoints(rest, listOf("a", "b", "c"))
        val solved = IK.fabrik(joints, Vec2(50.0, 60.0), 64)
        val angles = IK.anglesFromJoints(sk, listOf("a", "b", "c"), solved)
        val tip = sk.pose(angles).getValue("c").tail
        assertEquals(50.0, tip.x, 0.02); assertEquals(60.0, tip.y, 0.02)
    }
    @Test fun lookAtAndDamping() {
        assertEquals(90.0, Constraints.lookAt(Vec2.ZERO, Vec2(0.0, 5.0)), 1e-9)
        assertEquals(10.0, Constraints.dampedAngle(350.0, 30.0, 0.25) - 350.0 + 0.0 * 1, 1e-9)   // goes the short way (+10), not -80
    }
    @Test fun puppetNoPinsIsRest() {
        val m = PuppetMesh(100.0, 100.0, 4, 4)
        val d = m.deform(emptyList())
        for (i in d.indices) assertEquals(m.rest[i], d[i])
        assertEquals(4 * 4 * 6, m.triangles.size)
    }
    @Test fun puppetSinglePinTranslates() {
        val m = PuppetMesh(100.0, 100.0, 4, 4)
        val d = m.deform(listOf(PuppetPin("p", Vec2(50.0, 50.0), Vec2(60.0, 55.0))))
        for (i in d.indices) { assertEquals(m.rest[i].x + 10, d[i].x, 1e-9); assertEquals(m.rest[i].y + 5, d[i].y, 1e-9) }
    }
    @Test fun puppetPinsAreInterpolatedExactly() {
        val m = PuppetMesh(100.0, 100.0, 4, 4)
        val pins = listOf(PuppetPin("a", Vec2(0.0, 0.0), Vec2(5.0, 5.0)), PuppetPin("b", Vec2(100.0, 100.0), Vec2(90.0, 120.0)))
        val d = m.deform(pins)
        assertEquals(5.0, d[0].x, 1e-9); assertEquals(120.0, d.last().y, 1e-9)
    }
    @Test fun puppetTwoPinRotationRotatesWholeMesh() {
        val m = PuppetMesh(100.0, 100.0, 4, 4)
        // rotate 90 degrees about the origin: (x,y)->(-y,x)
        val pins = listOf(PuppetPin("a", Vec2(0.0, 0.0), Vec2(0.0, 0.0)), PuppetPin("b", Vec2(100.0, 0.0), Vec2(0.0, 100.0)))
        val d = m.deform(pins)
        val i = 2 * 5 + 3                                         // some interior vertex
        assertEquals(-m.rest[i].y, d[i].x, 1e-6); assertEquals(m.rest[i].x, d[i].y, 1e-6)
    }

    // ---------------- camera ----------------
    @Test fun defaultCameraShows100PercentAtZ0() {
        val cam = Camera3D.aeDefault(1920.0, 1080.0)
        val q = cam.projectLayer(Layer3D(position = Vec3(0.0, 0.0, 0.0)), 1920.0, 1080.0)!!
        assertEquals(0.0, q[0].x, 1e-6); assertEquals(1920.0, q[1].x, 1e-6); assertEquals(1080.0, q[2].y, 1e-6)
    }
    @Test fun fartherLayersShrinkTowardCentre() {
        val cam = Camera3D.aeDefault(1920.0, 1080.0)
        val q = cam.projectLayer(Layer3D(position = Vec3(0.0, 0.0, 1777.78)), 1920.0, 1080.0)!!
        assertEquals(960.0 - 960.0 / 2, q[0].x, 0.05)               // twice the distance -> half size
    }
    @Test fun layerBehindCameraIsCulled() {
        val cam = Camera3D.aeDefault(1920.0, 1080.0)
        assertNull(cam.projectLayer(Layer3D(position = Vec3(0.0, 0.0, -5000.0)), 100.0, 100.0))
    }
    @Test fun yRotationFoldsLayerInDepth() {
        val cam = Camera3D.aeDefault(1000.0, 1000.0)
        val flat = Layer3D(position = Vec3(500.0, 500.0, 0.0), anchor = Vec3(100.0, 100.0, 0.0))
        val rot = flat.copy(rotationDeg = Vec3(0.0, 45.0, 0.0))
        val a = cam.projectLayer(flat, 200.0, 200.0)!!; val b = cam.projectLayer(rot, 200.0, 200.0)!!
        assertTrue((b[1].x - b[0].x) < (a[1].x - a[0].x))
        assertNotEquals(b[0].y - b[3].y, b[1].y - b[2].y, 1e-3)    // perspective: left/right edges differ in height
    }
    @Test fun quaternionSlerpHalfwayIsHalfAngle() {
        val a = Quat.IDENTITY; val b = Quat.axisAngle(Vec3(0.0, 0.0, 1.0), 90.0)
        val m = Quat.slerp(a, b, 0.5)
        val v = m.rotate(Vec3(1.0, 0.0, 0.0))
        assertEquals(cos(PI / 4), v.x, 1e-9); assertEquals(sin(PI / 4), v.y, 1e-9)
    }
    @Test fun slerpTakesShortestPath() {
        val a = Quat.axisAngle(Vec3(0.0, 0.0, 1.0), 10.0); val b = Quat.axisAngle(Vec3(0.0, 0.0, 1.0), 350.0)
        val v = Quat.slerp(a, b, 0.5).rotate(Vec3(1.0, 0.0, 0.0))
        assertEquals(1.0, v.x, 1e-6)                                // goes through 0deg, not 180deg
    }
    @Test fun mat4InverseViaQuatRoundTrip() {
        val q = Quat.fromEulerDeg(10.0, 20.0, 30.0)
        val p = Vec3(1.0, 2.0, 3.0)
        val r = q.conjugate().rotate(q.rotate(p))
        assertEquals(1.0, r.x, 1e-9); assertEquals(2.0, r.y, 1e-9); assertEquals(3.0, r.z, 1e-9)
        val viaMat = q.toMat4().transformPoint(p); val direct = q.rotate(p)
        assertEquals(direct.x, viaMat.x, 1e-9); assertEquals(direct.z, viaMat.z, 1e-9)
    }
    @Test fun depthOfFieldGrowsAwayFromFocus() {
        val cam = Camera3D(Vec3(0.0, 0.0, -1000.0), Vec3.ZERO, 1000.0, 1000.0, 1000.0, apertureRadiusPx = 20.0, focusDistance = 1000.0)
        assertEquals(0.0, cam.blurRadius(1000.0), 1e-9)
        assertTrue(cam.blurRadius(3000.0) > cam.blurRadius(1500.0))
        assertTrue(cam.blurRadius(500.0) > 0)
    }
    @Test fun homographyMapsCornersAndInverts() {
        val quad = listOf(Vec2(10.0, 10.0), Vec2(200.0, 30.0), Vec2(180.0, 160.0), Vec2(0.0, 120.0))
        val h = Homography.rectToQuad(100.0, 50.0, quad)!!
        assertEquals(quad[2].x, h.apply(100.0, 50.0).x, 1e-6)
        assertEquals(quad[3].y, h.apply(0.0, 50.0).y, 1e-6)
        val back = h.inverse()!!.apply(quad[1].x, quad[1].y)
        assertEquals(100.0, back.x, 1e-6); assertEquals(0.0, back.y, 1e-6)
    }
    @Test fun homographyDegenerateQuadIsRejected() {
        val q = listOf(Vec2(0.0, 0.0), Vec2(0.0, 0.0), Vec2(0.0, 0.0), Vec2(0.0, 0.0))
        assertNull(Homography.rectToQuad(10.0, 10.0, q))
    }

    // ---------------- particles ----------------
    private val cfg = ParticleConfig(seed = 9, ratePerSec = 100.0, lifeMs = 500.0..1000.0, drag = 0.0)
    @Test fun particlesArePureFunctionsOfTime() {
        val s = ParticleSystem(cfg)
        val a = s.particlesAt(2000); val b = s.particlesAt(2000)
        assertEquals(a, b)
        val viaOtherOrder = ParticleSystem(cfg).also { it.particlesAt(5000); it.particlesAt(100) }.particlesAt(2000)
        assertEquals(a, viaOtherOrder)
    }
    @Test fun steadyStateCountMatchesRateTimesLife() {
        val n = ParticleSystem(cfg).particlesAt(10_000).size
        assertTrue("count $n", n in 60..90)                           // 100/s * mean life 0.75s
    }
    @Test fun burstEmitsExactlyOnceAndDies() {
        val s = ParticleSystem(cfg.copy(burstCount = 40, startMs = 100))
        assertEquals(40, s.particlesAt(101).size)
        assertTrue(s.particlesAt(50).isEmpty())
        assertTrue(s.particlesAt(5000).isEmpty())
    }
    @Test fun emitDurationStopsSpawning() {
        val s = ParticleSystem(cfg.copy(emitDurationMs = 1000L))
        assertTrue(s.particlesAt(500).isNotEmpty())
        assertTrue(s.particlesAt(3000).isEmpty())
    }
    @Test fun gravityPullsDownAndMatchesClosedForm() {
        val c = cfg.copy(burstCount = 1, speed = 0.0..0.0, gravity = Vec2(0.0, 100.0), lifeMs = 4000.0..4000.0)
        val p = ParticleSystem(c).particlesAt(1000).single()
        assertEquals(50.0, p.pos.y, 1e-6)                             // 0.5*g*t^2
        assertEquals(100.0, p.vel.y, 1e-6)
    }
    @Test fun dragSlowsParticlesAndConvergesToTerminalVelocity() {
        val c = cfg.copy(burstCount = 1, speed = 200.0..200.0, spreadDeg = 0.0, directionDeg = 0.0, gravity = Vec2.ZERO, drag = 2.0, lifeMs = 10000.0..10000.0)
        val p = ParticleSystem(c).particlesAt(5000).single()
        assertEquals(100.0, p.pos.x, 0.01)                            // v0/k
        assertTrue(abs(p.vel.x) < 0.01)
    }
    @Test fun sizeAndColorInterpolateOverLife() {
        val c = cfg.copy(burstCount = 1, lifeMs = 1000.0..1000.0, sizeStart = 10.0, sizeEnd = 0.0)
        val p = ParticleSystem(c).particlesAt(500).single()
        assertEquals(5.0, p.size, 1e-9); assertEquals(0.5, p.age01, 1e-9)
    }
    @Test fun shapesSpawnInsideExtent() {
        val circle = ParticleSystem(cfg.copy(shape = EmitterShape.CIRCLE, extent = Vec2(50.0, 0.0), speed = 0.0..0.0, gravity = Vec2.ZERO, position = Vec2(100.0, 100.0)))
        for (p in circle.particlesAt(3000)) assertTrue((p.pos - Vec2(100.0, 100.0)).length() <= 50.0 + 1e-6)
        val rect = ParticleSystem(cfg.copy(shape = EmitterShape.RECT, extent = Vec2(20.0, 10.0), speed = 0.0..0.0, gravity = Vec2.ZERO))
        for (p in rect.particlesAt(3000)) assertTrue(abs(p.pos.x) <= 10.0 && abs(p.pos.y) <= 5.0)
    }
    @Test fun maxParticlesCapsWork() {
        assertTrue(ParticleSystem(cfg.copy(ratePerSec = 100000.0, maxParticles = 50)).particlesAt(5000).size <= 50)
    }
}
