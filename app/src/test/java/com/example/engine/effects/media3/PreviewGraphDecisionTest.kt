package com.example.engine.effects.media3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live-preview Media3 graph decision: one `graphChanged` for both the rebuild gate and the
 * `setVideoEffects` install.
 *
 * ## The bug these tests pin down
 *
 * `VideoPlaybackEngine.applyActiveLookToPlayers` computed `graphChanged` twice with different
 * terms:
 *
 * ```
 * val missingResidentGrading = boundMainGrading == null
 * val graphChanged = shouldRebuildGraph(lastMedia3GraphSignature, graphSig, force) ||
 *   missingResidentGrading                                    // (1) rebuild gate
 * ...
 * shouldCallSetVideoEffects(graphChanged = lastMedia3GraphSignature != graphSig, force = force)  // (2) install
 * ```
 *
 * (2) dropped the missing-resident term, so whenever "no grading node is resident" was the only
 * reason to rebuild, the engine built a fresh effect list and then skipped the install. The
 * recovery path could never recover. `applyOverlayLooks` had the same shape.
 *
 * Pure Kotlin, so `toolchain/verify/run_logic_tests.sh` executes it on a plain JVM.
 */
class PreviewGraphDecisionTest {

  private val sig = "G||"

  /** The old install check, reproduced verbatim: signature only, no resident-node term. */
  private fun legacyInstallCheck(previousSignature: String?, nextSignature: String, force: Boolean) =
    previousSignature != nextSignature || force

  @Test
  fun aMissingResidentNodeAloneMustInstallTheGraph() {
    val state = PreviewGraphDecision.State(
      previousGraphSignature = sig,
      nextGraphSignature = sig,
      residentGradingNode = false,
      newEffectCount = 1,
      previousHadEffects = true,
      force = false
    )
    val outcome = PreviewGraphDecision.decide(state)
    assertTrue("an unchanged signature with no resident node is still a graph change", outcome.graphChanged)
    assertTrue(
      "the recovery path must actually call setVideoEffects, or no grading node ever becomes resident",
      outcome.callSetVideoEffects
    )
    assertEquals("resident-missing", outcome.reason)
    // Evidence that the old code disagreed with itself on exactly this input.
    assertFalse(
      "the legacy signature-only check would have skipped the install",
      legacyInstallCheck(sig, sig, force = false)
    )
  }

  @Test
  fun bothStagesOfOneUpdateAgreeOnGraphChanged() {
    val cases = listOf(
      Triple(sig, sig, true),
      Triple(sig, sig, false),
      Triple<String?, String, Boolean>(null, sig, true),
      Triple(null, sig, false),
      Triple(sig, "G|teal_orange|", true),
      Triple(sig, "G|teal_orange|", false)
    )
    for ((previous, next, resident) in cases) {
      for (force in listOf(false, true)) {
        val stage1 = PreviewGraphDecision.graphChanged(
          previousGraphSignature = previous,
          nextGraphSignature = next,
          residentGradingNode = resident,
          force = force
        )
        val whole = PreviewGraphDecision.decide(
          PreviewGraphDecision.State(
            previousGraphSignature = previous,
            nextGraphSignature = next,
            residentGradingNode = resident,
            newEffectCount = 1,
            previousHadEffects = true,
            force = force
          )
        )
        assertEquals(
          "stage 1 (rebuild gate) and the single-shot decision must agree " +
            "(previous=$previous next=$next resident=$resident force=$force)",
          stage1,
          whole.graphChanged
        )
        // Stage 2 is fed stage 1's value, so the install can never contradict the gate.
        val stage2 = PreviewGraphDecision.outcomeFor(
          graphChanged = stage1,
          signatureChanged = PreviewGraphDecision.shouldRebuildGraph(previous, next, force),
          residentGradingNode = resident,
          newEffectCount = 1,
          previousHadEffects = true,
          force = force
        )
        assertEquals("stage 2 must keep stage 1's graphChanged", stage1, stage2.graphChanged)
        assertTrue(
          "once the gate opened with a non-empty effect list, the install must follow " +
            "(previous=$previous next=$next resident=$resident force=$force)",
          !stage1 || stage2.callSetVideoEffects
        )
      }
    }
  }

  @Test
  fun sliderTicksWithAResidentNodeNeverRebuildOrInstall() {
    val outcome = PreviewGraphDecision.decide(
      PreviewGraphDecision.State(
        previousGraphSignature = sig,
        nextGraphSignature = sig,
        residentGradingNode = true,
        newEffectCount = 1,
        previousHadEffects = true,
        force = false
      )
    )
    assertFalse(outcome.graphChanged)
    assertFalse("value-only look edits publishLive onto the resident shader", outcome.callSetVideoEffects)
    assertEquals("unchanged", outcome.reason)
  }

  @Test
  fun identityFallbackStopsRetryingTheGraph() {
    // The device reported it can only run the identity passthrough. Retrying on every slider tick
    // only swings setVideoEffects and never restores the look, so the resident term must not fire.
    val outcome = PreviewGraphDecision.decide(
      PreviewGraphDecision.State(
        previousGraphSignature = sig,
        nextGraphSignature = sig,
        residentGradingNode = false,
        identityFallbackActive = true,
        newEffectCount = 1,
        previousHadEffects = true,
        force = false
      )
    )
    assertFalse(outcome.graphChanged)
    assertFalse(outcome.callSetVideoEffects)
    assertFalse(PreviewGraphDecision.needsResidentGradingInstall(residentGradingNode = false, identityFallbackActive = true))
    assertTrue(PreviewGraphDecision.needsResidentGradingInstall(residentGradingNode = false, identityFallbackActive = false))
    assertFalse(PreviewGraphDecision.needsResidentGradingInstall(residentGradingNode = true, identityFallbackActive = false))
  }

  @Test
  fun identityFallbackStillRecoversWhenTheGraphReallyChanges() {
    val outcome = PreviewGraphDecision.decide(
      PreviewGraphDecision.State(
        previousGraphSignature = sig,
        nextGraphSignature = "G|teal_orange|",
        residentGradingNode = false,
        identityFallbackActive = true,
        newEffectCount = 2,
        previousHadEffects = true,
        force = false
      )
    )
    assertTrue(outcome.graphChanged)
    assertTrue("a LUT / VFX change must still be allowed to try again", outcome.callSetVideoEffects)
    assertEquals("graph-changed", outcome.reason)
  }

  @Test
  fun anEmptyGraphIsNeverInstalledJustToApplyNothing() {
    val outcome = PreviewGraphDecision.decide(
      PreviewGraphDecision.State(
        previousGraphSignature = null,
        nextGraphSignature = sig,
        residentGradingNode = false,
        newEffectCount = 0,
        previousHadEffects = false,
        force = true
      )
    )
    assertTrue(outcome.graphChanged)
    assertFalse("enabling the effects renderer with an empty list killed the decoder surface and audio", outcome.callSetVideoEffects)
    assertEquals("empty-graph-nothing-to-clear", outcome.reason)
  }

  @Test
  fun clearingAnAppliedGraphIsStillAllowed() {
    val outcome = PreviewGraphDecision.decide(
      PreviewGraphDecision.State(
        previousGraphSignature = sig,
        nextGraphSignature = sig,
        residentGradingNode = true,
        newEffectCount = 0,
        previousHadEffects = true,
        force = true
      )
    )
    assertTrue("a look reset has to clear what was applied", outcome.callSetVideoEffects)
  }

  @Test
  fun forceRebindsAnUnchangedGraphExactlyAsTheOldPolicyDid() {
    val forced = PreviewGraphDecision.decide(
      PreviewGraphDecision.State(
        previousGraphSignature = sig,
        nextGraphSignature = sig,
        residentGradingNode = true,
        newEffectCount = 1,
        previousHadEffects = true,
        force = true
      )
    )
    assertTrue(forced.callSetVideoEffects)
    assertEquals("forced", forced.reason)
    // Guard order + the `graphChanged || force` tail must stay equivalent to the old
    // PreviewEffectApplyPolicy.shouldCallSetVideoEffects for every combination.
    for (count in listOf(0, 1, 2)) {
      for (previousHad in listOf(false, true)) {
        for (changed in listOf(false, true)) {
          for (force in listOf(false, true)) {
            val legacy = when {
              count == 0 && !previousHad -> false
              !changed && !force -> false
              else -> changed || force
            }
            val actual = PreviewGraphDecision.outcomeFor(
              graphChanged = changed,
              signatureChanged = changed,
              residentGradingNode = true,
              newEffectCount = count,
              previousHadEffects = previousHad,
              force = force
            ).callSetVideoEffects
            assertEquals(
              "install decision drifted from the old policy " +
                "(count=$count previousHad=$previousHad changed=$changed force=$force)",
              legacy,
              actual
            )
          }
        }
      }
    }
  }

  @Test
  fun firstApplyWithNoRecordedSignatureInstalls() {
    val outcome = PreviewGraphDecision.decide(
      PreviewGraphDecision.State(
        previousGraphSignature = null,
        nextGraphSignature = sig,
        residentGradingNode = false,
        newEffectCount = 1,
        previousHadEffects = false,
        force = false
      )
    )
    assertTrue(outcome.graphChanged)
    assertTrue(outcome.callSetVideoEffects)
    assertEquals("graph-changed+resident-missing", outcome.reason)
  }

  @Test
  fun shouldRebuildGraphIsUnchangedByTheRefactor() {
    assertTrue(PreviewGraphDecision.shouldRebuildGraph(null, sig, force = false))
    assertTrue(PreviewGraphDecision.shouldRebuildGraph(null, sig, force = true))
    assertFalse(PreviewGraphDecision.shouldRebuildGraph(sig, sig, force = false))
    assertFalse(PreviewGraphDecision.shouldRebuildGraph(sig, sig, force = true))
    assertTrue(PreviewGraphDecision.shouldRebuildGraph(sig, "G|moody_film|", force = false))
  }

  @Test
  fun theOverlayGateKeepsItsOldForceSemanticsExactly() {
    // applyOverlayLooks used to skip an overlay only when
    //   `!force && lastOverlayGraphSignatures[id] == graphSig && boundOverlayGrading[id] != null`
    // i.e. it proceeded whenever force was true, the signature differed, or no node was resident.
    // A forced call must still rebuild every overlay graph (flushDeferredLooks and
    // recreatePreviewPlayer rely on that), so this is checked exhaustively.
    val signatures = listOf<String?>(null, sig, "G|teal_orange|")
    for (previous in signatures) {
      for (next in listOf(sig, "G|teal_orange|")) {
        for (resident in listOf(true, false)) {
          for (force in listOf(true, false)) {
            val legacyProceeds = force || previous != next || !resident
            val actual = PreviewGraphDecision.graphChangedOrForced(
              previousGraphSignature = previous,
              nextGraphSignature = next,
              residentGradingNode = resident,
              force = force
            )
            assertEquals(
              "overlay rebuild gate drifted (previous=$previous next=$next resident=$resident force=$force)",
              legacyProceeds,
              actual
            )
          }
        }
      }
    }
  }

  @Test
  fun graphChangedAloneDoesNotRebindAnUnchangedGraphJustBecauseForceWasSet() {
    // The main path keeps the narrower historical meaning of `force` (shouldRebuildGraph only lets
    // it through while no signature has been recorded yet). Pinning both variants keeps the
    // difference between the two call sites deliberate instead of accidental.
    assertFalse(
      PreviewGraphDecision.graphChanged(sig, sig, residentGradingNode = true, force = true)
    )
    assertTrue(
      PreviewGraphDecision.graphChangedOrForced(sig, sig, residentGradingNode = true, force = true)
    )
    assertTrue(
      PreviewGraphDecision.graphChanged(null, sig, residentGradingNode = true, force = true)
    )
  }

  @Test
  fun anOverlayThatLostItsPlayerRetriesInsteadOfSkipping() {
    // applyOverlayLooks: the overlay player is released when the clip leaves the playhead window,
    // so the resident node dies with it. If the signature is still recorded and the node is treated
    // as resident, the recreated player never gets its effects and the overlay renders unfiltered.
    val staleButRecorded = PreviewGraphDecision.decide(
      PreviewGraphDecision.State(
        previousGraphSignature = sig,
        nextGraphSignature = sig,
        residentGradingNode = true, // stale: the player that owned it was released
        newEffectCount = 1,
        previousHadEffects = true,
        force = false
      )
    )
    assertFalse("a stale resident flag hides the problem", staleButRecorded.callSetVideoEffects)

    val clearedWithThePlayer = PreviewGraphDecision.decide(
      PreviewGraphDecision.State(
        previousGraphSignature = sig,
        nextGraphSignature = sig,
        residentGradingNode = false, // syncOverlayPlayers now clears it with the player
        newEffectCount = 1,
        previousHadEffects = true,
        force = false
      )
    )
    assertTrue("clearing the dead node makes the next look change install again", clearedWithThePlayer.callSetVideoEffects)
  }
}
