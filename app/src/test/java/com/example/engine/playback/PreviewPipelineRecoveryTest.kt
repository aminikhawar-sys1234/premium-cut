package com.example.engine.playback

import androidx.media3.common.PlaybackException
import com.example.engine.effects.media3.CustomShaderGlEffect
import com.example.engine.effects.media3.IdentityPassthroughGlEffect
import com.example.engine.effects.media3.Media3EffectPipeline
import com.example.engine.effects.media3.PreviewEffectApplyPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreviewPipelineRecoveryTest {

  @Test
  fun identityPassthroughIsARealGraphNode() {
    val identity = Media3EffectPipeline.identityEffects()
    assertEquals(1, identity.size)
    assertSame(IdentityPassthroughGlEffect, identity.single())
    assertFalse(IdentityPassthroughGlEffect.isNoOp(1920, 1080))
    assertTrue(IdentityPassthroughGlEffect.fragmentShader.contains("gl_FragColor"))
    assertEquals(CustomShaderGlEffect.IDENTITY_FRAGMENT_SHADER, IdentityPassthroughGlEffect.fragmentShader)
  }

  @Test
  fun recoveryUsesLastGoodGraphWhenAvailable() {
    val lastGood = Media3EffectPipeline.identityEffects()
    val recovered = PreviewEffectApplyPolicy.recoveryEffects(lastGood)
    assertSame(lastGood.single(), recovered.single())
  }

  @Test
  fun recoveryFallsBackToIdentityWhenNoLastGoodGraph() {
    val recovered = PreviewEffectApplyPolicy.recoveryEffects(emptyList())
    assertEquals(1, recovered.size)
    assertSame(IdentityPassthroughGlEffect, recovered.single())
  }

  @Test
  fun failedApplyOrPlayerErrorRecreatesThePlayer() {
    assertTrue(PreviewEffectApplyPolicy.shouldRecreatePlayer(applyFailed = true, playerHasError = false))
    assertTrue(PreviewEffectApplyPolicy.shouldRecreatePlayer(applyFailed = false, playerHasError = true))
    assertFalse(PreviewEffectApplyPolicy.shouldRecreatePlayer(applyFailed = false, playerHasError = false))
  }

  @Test
  fun videoProcessingFailureRecreatesThePlayer() {
    val processing = PlaybackException("gpu", null, PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED)
    val init = PlaybackException("init", null, PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED)
    val io = PlaybackException("io", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
    assertTrue(PreviewPipelinePolicy.shouldRecreatePlayer(processing, applyFailed = false))
    assertTrue(PreviewPipelinePolicy.shouldRecreatePlayer(init, applyFailed = false))
    assertFalse(PreviewPipelinePolicy.shouldRecreatePlayer(io, applyFailed = false))
    assertTrue(PreviewPipelinePolicy.shouldRecreatePlayer(io, applyFailed = true))
  }

  @Test
  fun recoveryOrderIsLastGoodThenIdentityThenRecreate() {
    assertEquals(
      PreviewPipelinePolicy.RecoveryStep.RESTORE_LAST_GOOD,
      PreviewPipelinePolicy.nextRecoveryStep(
        lastGoodEffectCount = 2,
        lastRestoreSucceeded = false,
        identityApplySucceeded = false,
        playerHasError = false
      )
    )
    assertEquals(
      PreviewPipelinePolicy.RecoveryStep.APPLY_IDENTITY,
      PreviewPipelinePolicy.nextRecoveryStep(
        lastGoodEffectCount = 0,
        lastRestoreSucceeded = false,
        identityApplySucceeded = false,
        playerHasError = false
      )
    )
    assertEquals(
      PreviewPipelinePolicy.RecoveryStep.RECREATE_PLAYER,
      PreviewPipelinePolicy.nextRecoveryStep(
        lastGoodEffectCount = 1,
        lastRestoreSucceeded = false,
        identityApplySucceeded = false,
        playerHasError = true
      )
    )
  }

  @Test
  fun openingANewProjectMustResetThePipeline() {
    assertTrue(PreviewPipelinePolicy.shouldResetOnProjectChange())
  }

  @Test
  fun shaderProgramKeepsATwoSlotTexturePool() {
    assertEquals(2, com.example.engine.effects.media3.CustomShaderGlShaderProgram.TEXTURE_POOL_CAPACITY)
  }
}
