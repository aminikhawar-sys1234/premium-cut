package com.example.engine.effects.media3

/**
 * The ONE decision that governs a live-preview Media3 graph update, for the main player and for
 * every overlay player.
 *
 * ## Why this exists
 *
 * `VideoPlaybackEngine.applyActiveLookToPlayers` used to compute "did the graph change?" twice,
 * with two *different* definitions:
 *
 * ```
 * // (1) decides whether to build a fresh effect list at all
 * val missingResidentGrading = boundMainGrading == null
 * val graphChanged = shouldRebuildGraph(lastMedia3GraphSignature, graphSig, force) ||
 *   missingResidentGrading
 * ...
 * // (2) decides whether ExoPlayer.setVideoEffects is actually called
 * shouldCallSetVideoEffects(graphChanged = lastMedia3GraphSignature != graphSig, force = force)
 * ```
 *
 * Definition (2) drops the `missingResidentGrading` term that (1) added. So whenever "no grading
 * node is resident" was the ONLY reason to rebuild, the engine built a brand-new effect list and
 * then skipped the install — the recovery path could never recover. `applyOverlayLooks` had the
 * same shape (its skip-check tested `boundOverlayGrading[id] != null`, its install-check tested
 * only the signature).
 *
 * [decide] computes `graphChanged` **once** and feeds that single value into both the
 * "rebuild" and the "install" decision, so the two can no longer disagree.
 *
 * Pure Kotlin (no `android.*` / `androidx.*`) so `toolchain/verify/run_logic_tests.sh` can
 * execute it on a plain JVM, the same way it executes `ChromaKeyResolver`.
 */
object PreviewGraphDecision {

  /**
   * Everything the decision needs. Names mirror the `VideoPlaybackEngine` fields they come from so
   * a caller cannot silently pass the wrong one.
   *
   * @param previousGraphSignature last structural graph id that was recorded (`lastMedia3GraphSignature`).
   * @param nextGraphSignature structural graph id of the look that is now wanted.
   * @param residentGradingNode true when the engine holds the `ColorGradingGlEffect` that is
   *   **actually installed** in the player's video graph. False when there is none, or when the
   *   held instance belongs to a player / graph that no longer exists.
   * @param identityFallbackActive true when the device could only run the identity passthrough.
   *   Retrying the graph on every slider tick then only swings `setVideoEffects` and never
   *   restores the look, so the missing-resident term must not fire.
   * @param newEffectCount size of the freshly built effect list.
   * @param previousHadEffects true when an effect list was applied (or is pending) already.
   * @param force rebind even when nothing structural changed (failed apply, paused repaint).
   */
  data class State(
    val previousGraphSignature: String?,
    val nextGraphSignature: String,
    val residentGradingNode: Boolean,
    val identityFallbackActive: Boolean = false,
    val newEffectCount: Int,
    val previousHadEffects: Boolean,
    val force: Boolean = false
  )

  /**
   * @param graphChanged the single authoritative answer: a fresh effect list must be built and
   *   considered for install.
   * @param callSetVideoEffects `ExoPlayer.setVideoEffects` must actually be called.
   * @param reason short machine-readable label, for the log line that replaces the old
   *   "Skipped empty setVideoEffects".
   */
  data class Outcome(
    val graphChanged: Boolean,
    val callSetVideoEffects: Boolean,
    val reason: String
  )

  /** Structural graph identity changed. Kept identical to the previous policy so nothing drifts. */
  fun shouldRebuildGraph(previousGraphSignature: String?, nextGraphSignature: String, force: Boolean): Boolean {
    if (force && previousGraphSignature == null) return true
    return previousGraphSignature != nextGraphSignature
  }

  /**
   * A look is published onto the resident `ColorGradingGlEffect` by writing uniforms
   * ([ColorGradingGlEffect.publishLive]). With no resident node there is nothing to publish onto,
   * so the graph has to be installed even when its signature is unchanged.
   *
   * Suppressed while [identityFallbackActive]: that state already reported the capability problem
   * to the user and deliberately stops retrying.
   */
  fun needsResidentGradingInstall(residentGradingNode: Boolean, identityFallbackActive: Boolean): Boolean =
    !residentGradingNode && !identityFallbackActive

  /**
   * The ONE `graphChanged`. Both stages of an update must ask this function: the stage that decides
   * whether to build a fresh effect list (before the list, and therefore its size, exists) and the
   * stage that decides whether to install it (afterwards). Computing it twice from different terms
   * is exactly the bug this object removes.
   */
  fun graphChanged(
    previousGraphSignature: String?,
    nextGraphSignature: String,
    residentGradingNode: Boolean,
    identityFallbackActive: Boolean = false,
    force: Boolean = false
  ): Boolean =
    shouldRebuildGraph(previousGraphSignature, nextGraphSignature, force) ||
      needsResidentGradingInstall(residentGradingNode, identityFallbackActive)

  /**
   * [graphChanged] with `force` honoured as "rebind even when nothing changed".
   *
   * The overlay path has always had these stronger force semantics — `applyOverlayLooks` skipped an
   * overlay only when `!force && signatureEqual && resident`, so a forced call rebuilt every overlay
   * graph. [graphChanged] on its own would not: it treats `force` the way `shouldRebuildGraph` always
   * has, i.e. as a nudge only while no signature has been recorded yet. Callers that must keep the
   * overlay behaviour (`flushDeferredLooks`, `recreatePreviewPlayer`) use this.
   *
   * Equivalent to the old overlay gate: `force || (previous != next) || !resident`.
   */
  fun graphChangedOrForced(
    previousGraphSignature: String?,
    nextGraphSignature: String,
    residentGradingNode: Boolean,
    identityFallbackActive: Boolean = false,
    force: Boolean = false
  ): Boolean =
    force || graphChanged(previousGraphSignature, nextGraphSignature, residentGradingNode, identityFallbackActive, force = false)

  /**
   * Never enable Media3's effects renderer with an empty list just to "apply nothing" — that swap
   * invalidated the decoder surface and silenced source audio. Clearing an applied graph is still
   * required when the look is reset, hence `previousHadEffects`.
   */
  fun mayInstallEmptyGraph(newEffectCount: Int, previousHadEffects: Boolean): Boolean =
    !(newEffectCount == 0 && !previousHadEffects)

  fun decide(state: State): Outcome = outcomeFor(
    graphChanged = graphChanged(
      previousGraphSignature = state.previousGraphSignature,
      nextGraphSignature = state.nextGraphSignature,
      residentGradingNode = state.residentGradingNode,
      identityFallbackActive = state.identityFallbackActive,
      force = state.force
    ),
    signatureChanged = shouldRebuildGraph(state.previousGraphSignature, state.nextGraphSignature, state.force),
    residentGradingNode = state.residentGradingNode,
    identityFallbackActive = state.identityFallbackActive,
    newEffectCount = state.newEffectCount,
    previousHadEffects = state.previousHadEffects,
    force = state.force
  )

  /**
   * Stage 2 of an update: the caller already asked [graphChanged] in stage 1 (before it could know
   * the effect-list size) and passes that **same** value here. Nothing is recalculated, so the two
   * stages cannot disagree.
   */
  fun outcomeFor(
    graphChanged: Boolean,
    signatureChanged: Boolean,
    residentGradingNode: Boolean,
    identityFallbackActive: Boolean = false,
    newEffectCount: Int,
    previousHadEffects: Boolean,
    force: Boolean = false
  ): Outcome {
    // Guard order and the `graphChanged || force` tail are byte-for-byte the old
    // PreviewEffectApplyPolicy.shouldCallSetVideoEffects, so this is a refactor of *where* the
    // value comes from, not of what it means.
    if (!mayInstallEmptyGraph(newEffectCount, previousHadEffects)) {
      return Outcome(
        graphChanged = graphChanged,
        callSetVideoEffects = false,
        reason = "empty-graph-nothing-to-clear"
      )
    }

    val install = graphChanged || force
    if (!install) {
      return Outcome(graphChanged = graphChanged, callSetVideoEffects = false, reason = "unchanged")
    }

    val missingResident = needsResidentGradingInstall(residentGradingNode, identityFallbackActive)
    val reason = when {
      signatureChanged && missingResident -> "graph-changed+resident-missing"
      signatureChanged -> "graph-changed"
      missingResident && force -> "forced-resident-missing"
      missingResident -> "resident-missing"
      else -> "forced"
    }
    // `graphChanged` is reported exactly as it was passed in: a forced rebind of an unchanged graph
    // is `graphChanged = false` + `callSetVideoEffects = true`, not a rewritten true. Overwriting it
    // here would re-create the two-definitions-of-graphChanged problem this object exists to remove.
    return Outcome(graphChanged = graphChanged, callSetVideoEffects = true, reason = reason)
  }
}
