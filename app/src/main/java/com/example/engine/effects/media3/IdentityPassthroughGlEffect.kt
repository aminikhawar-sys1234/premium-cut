package com.example.engine.effects.media3

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

/**
 * Guaranteed-safe copy shader used when a custom effect fails to compile, initialize,
 * or draw. Media3 must keep a [androidx.media3.effect.GlEffect] in the graph so the
 * decoder output surface stays valid; dropping to an empty list is what left the
 * preview on a black, unrecoverable VideoFrameProcessor.
 */
@OptIn(UnstableApi::class)
object IdentityPassthroughGlEffect : CustomShaderGlEffect(
  name = "IdentityPassthrough",
  fragmentShader = CustomShaderGlEffect.IDENTITY_FRAGMENT_SHADER
)
