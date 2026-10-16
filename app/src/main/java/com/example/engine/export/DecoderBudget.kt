package com.example.engine.export

/**
 * Who the export is allowed to release when a device refuses another codec instance.
 *
 * Android limits how many codec instances one process may hold (commonly four hardware decoders,
 * and the number is lower on mid-range SoCs). A project with more simultaneous video layers than
 * that budget - several overlays over the main clip plus a transition side plus a chroma-key
 * background video - therefore has to release something before it can open the next decoder. What
 * it must *not* do is release a clip the frame is about to draw: that would silently drop a layer
 * the user added, which is exactly what the export must never do to "make the export work".
 *
 * So the rule is one line long and lives here, pure and unit-tested: only decoders whose clip is
 * not needed for the current frame may be released. When every open decoder is needed, the caller
 * has nothing it may free and must report the device's refusal instead of quietly changing the
 * picture.
 */
object DecoderBudget {

  /**
   * Ids that may be released now: the decoders in [open] whose clip is not in [needed], in the
   * order they were opened, so the caller's log and eviction order stay deterministic.
   */
  fun evictions(open: Collection<String>, needed: Set<String>): List<String> =
    evictions(open, needed, emptySet())

  /**
   * The same rule with a second, weaker class of decoder.
   *
   * [pinned] clips are *not* drawn by the current frame but are open on purpose - the "before" side
   * of a NONE transition is the case that exists in this pipeline: nothing of it is on screen, it is
   * merely held so a following real transition does not have to reopen it. Pinned decoders are
   * released last and only when the frame needs the instance for a picture it really draws, so the
   * optimisation costs a reopen at worst and never a layer.
   */
  fun evictions(open: Collection<String>, needed: Set<String>, pinned: Set<String>): List<String> {
    val optional = open.filter { it !in needed && it !in pinned }
    val pinnedOnly = open.filter { it in pinned && it !in needed }
    return optional + pinnedOnly
  }

  /**
   * Whether the export may release anything at all while [needed] clips are on screen. False means
   * the decoder budget is exhausted by the frame itself - the caller may not trade a visible layer
   * for another one, and must fall back to a software decoder (or report the device's refusal)
   * instead.
   */
  fun canFree(open: Collection<String>, needed: Set<String>): Boolean =
    evictions(open, needed).isNotEmpty()
}
