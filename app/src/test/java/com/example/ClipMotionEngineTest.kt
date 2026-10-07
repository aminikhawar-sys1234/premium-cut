package com.example

import com.example.domain.model.*
import com.example.engine.KeyframeInterpolator
import com.example.engine.motion.ClipMotionEngine
import com.example.engine.motion.ClipMotionRuntime
import com.example.engine.motion.ExpressionStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipMotionEngineTest {
  private var tl = Timeline()

  private fun install(t: Timeline) { tl = t; ClipMotionRuntime.timelineProvider = { tl } }

  @After fun tearDown() { ClipMotionRuntime.timelineProvider = null }

  @Test fun expressionsDriveChannelsFromKeyframedValue() {
    val s = StickerClip(id = "s", durationMs = 4000L, posX = 0.1f, posY = 0.2f,
      motion = ClipMotionScript(expressions = mapOf("rotation" to "time * 90", "position" to "value + [0, 0.1]")))
    install(Timeline(stickerClips = listOf(s)))
    val r = KeyframeInterpolator.interpolate(s, 1000L)
    assertEquals(90f, r.rotation, 1e-3f)
    assertEquals(0.3f, r.posY, 1e-3f)
  }

  @Test fun brokenExpressionFallsBackToKeyframes() {
    val s = StickerClip(id = "s", rotation = 5f, motion = ClipMotionScript(expressions = mapOf("rotation" to "nope(")))
    install(Timeline(stickerClips = listOf(s)))
    assertEquals(5f, KeyframeInterpolator.interpolate(s, 0L).rotation, 1e-3f)
    assertTrue(ClipMotionEngine.check("s", MotionChannel.ROTATION, "nope(", 0L) is ExpressionStatus.SyntaxError)
  }

  @Test fun childBoneFollowsParentRotationWithoutJumpOnLink() {
    val parent = StickerClip(id = "P", durationMs = 4000L,
      keyframes = listOf(ClipKeyframe(timeMs = 0, rotation = 0f), ClipKeyframe(timeMs = 1000, rotation = 90f)),
      motion = ClipMotionScript(boneLength = 0.5f))
    val child0 = StickerClip(id = "C", durationMs = 4000L, posX = 0.3f, posY = 0.2f)
    install(Timeline(stickerClips = listOf(parent, child0)))
    val child = child0.copy(motion = ClipMotionScript(rig = ClipMotionEngine.bindKeepingPose(tl, "C", "P", 0L)))
    install(Timeline(stickerClips = listOf(parent, child)))
    val c0 = KeyframeInterpolator.interpolate(child, 0L)
    assertEquals(0.3f, c0.posX, 1e-3f); assertEquals(0.2f, c0.posY, 1e-3f)
    val c1 = KeyframeInterpolator.interpolate(child, 1000L)
    assertEquals(90f, c1.rotation, 1e-3f)
    assertEquals(-0.3556f, c1.posX, 2e-3f); assertEquals(0.169f, c1.posY, 2e-3f)
  }

  @Test fun loopsAreRejectedAndCyclicDataIsSafe() {
    val p = StickerClip(id = "P", motion = ClipMotionScript(rig = ClipRigBinding("C")))
    val c = StickerClip(id = "C", motion = ClipMotionScript(rig = ClipRigBinding("P")))
    install(Timeline(stickerClips = listOf(p, c)))
    assertTrue(ClipMotionEngine.wouldCycle(tl, "P", "C"))
    assertFalse(KeyframeInterpolator.interpolate(c, 0L).scaleX.isNaN())
  }
}
