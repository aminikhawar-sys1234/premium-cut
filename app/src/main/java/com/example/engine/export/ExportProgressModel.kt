package com.example.engine.export

import java.util.Locale

/**
 * Every stage a project export goes through, in the order it happens.
 *
 * Why this is an explicit model instead of a free-form status string: the reported failure was
 * *"the export panel opened at 5 % / 'encoded 0 / 18484 frames (0% · 0.0 fps)' and never moved for
 * twenty minutes"*. That text is produced by the frame phase alone, so it says nothing about the
 * seven stages that run **before frame 0** (encoder open, EGL/GPU init, PCM mix, audio encoder,
 * muxer, tracking prewarm). A panel that only knows about frames cannot tell "rendering the first
 * frame" from "stuck opening the encoder" from "stuck in the audio mix" - they all read 5 %.
 *
 * Every stage now has a name, a position in the pre-roll bar and its own watchdog budget
 * ([ExportStallWatchdog]), so the UI and the device log always say which stage the export is in and
 * how long it has been there.
 */
enum class ExportPhase(
  /** Short, user-facing name; shown in the panel and in every log line of the stage. */
  val label: String,
  /** What the stage does, for the "why is this taking so long" line in the panel. */
  val detail: String
) {
  DEVICE_CHECK(
    "Checking device encoders",
    "Reading the codec list to pick an encoder this device really has."
  ),
  PREPARING(
    "Preparing the export",
    "Resolving the output size and planning the exact frame timestamps."
  ),
  LOADING_ASSETS(
    "Loading project assets",
    "Decoding the images, stickers and shapes the timeline draws."
  ),
  OPENING_ENCODER(
    "Opening the video encoder",
    "Asking the device for a hardware encoder at the requested resolution."
  ),
  INITIALISING_GPU(
    "Starting the GPU compositor",
    "Creating the EGL context that composites into the encoder surface."
  ),
  MIXING_AUDIO(
    "Mixing audio",
    "Decoding and mixing every audio track of the timeline before the first frame."
  ),
  OPENING_AUDIO_ENCODER(
    "Opening the audio encoder",
    "Starting the AAC encoder for the mixed timeline audio."
  ),
  OPENING_MUXER(
    "Preparing the MP4 muxer",
    "Creating the output file and starting the encoder/muxer pair."
  ),
  WARMING_TRACKERS(
    "Warming tracked effects",
    "Pre-computing the face/body poses so the render loop does not stall per frame."
  ),
  RENDERING(
    "Rendering frames",
    "Compositing and encoding the timeline frames."
  ),
  FINALISING(
    "Finalising the MP4",
    "Waiting for the encoders to drain and writing the moov atom."
  ),
  VERIFYING(
    "Verifying the export",
    "Decoding sample frames of the finished file to prove it is not black or broken."
  );

  /** True for every stage that happens before the first frame reaches the encoder. */
  val isPreRoll: Boolean
    get() = this == DEVICE_CHECK || this == PREPARING || this == LOADING_ASSETS ||
      this == OPENING_ENCODER || this == INITIALISING_GPU || this == MIXING_AUDIO ||
      this == OPENING_AUDIO_ENCODER || this == OPENING_MUXER || this == WARMING_TRACKERS

  /** True while frames are being composited/encoded (the only stage a frame ETA applies to). */
  val isFrameStage: Boolean
    get() = this == RENDERING
}

/**
 * The single source of truth for what the progress ring shows.
 *
 * Two ranges, deliberately separated:
 *
 * * **pre-roll** `FLOOR_FRACTION .. PRE_ROLL_CEILING` (0.5 % .. 5 %): split between the nine
 *   preparation stages by fixed weights from [preRollFraction]. A stage reports its own internal
 *   fraction (a PCM mix knows how many chunks it has done), so this part of the ring *moves* even
 *   though not a single frame has been encoded yet. That is exactly what the reported panel did
 *   not do: it sat at the encoding floor for the whole pre-roll.
 * * **encoding** `PRE_ROLL_CEILING .. 0.95`: [encodingFraction], which is frame-count based -
 *   the formula the previous release already used (`0.05 + frames/total * 0.90`), kept bit-for-bit
 *   so the numbers users saw before do not shift.
 *
 * Pure Kotlin (no `android.*`), so the mapping and the monotonicity it promises are unit-tested.
 */
object ExportProgressModel {

  /** First value the ring shows once an export starts. */
  const val FLOOR_FRACTION = 0.005f

  /** The pre-roll (every stage before the first frame) never claims more than this. */
  const val PRE_ROLL_CEILING = 0.05f

  /** Frames carry the ring from [PRE_ROLL_CEILING] up to this; verification owns the rest. */
  const val ENCODING_CEILING = 0.95f

  /**
   * Cumulative weight of each pre-roll stage inside the pre-roll bar. The weights are proportions
   * of *how long the stage normally takes*, so the bar advances roughly with time even before any
   * frame exists: the PCM mix of a long, multi-track timeline is by far the slowest step, the codec
   * and EGL setup the next, and the device/codec checks are instant.
   */
  private val preRollWeights: Map<ExportPhase, Float> = mapOf(
    ExportPhase.DEVICE_CHECK to 0.03f,
    ExportPhase.PREPARING to 0.08f,
    ExportPhase.LOADING_ASSETS to 0.16f,
    ExportPhase.OPENING_ENCODER to 0.34f,
    ExportPhase.INITIALISING_GPU to 0.46f,
    ExportPhase.MIXING_AUDIO to 0.86f,
    ExportPhase.OPENING_AUDIO_ENCODER to 0.91f,
    ExportPhase.OPENING_MUXER to 0.96f,
    ExportPhase.WARMING_TRACKERS to 1.00f
  )

  /** The pre-roll stages in execution order. */
  private val orderedPreRoll: List<ExportPhase> = ExportPhase.entries.filter { it.isPreRoll }

  /** Start of [phase]'s slice inside the pre-roll bar (0 for the first stage). */
  fun preRollStart(phase: ExportPhase): Float {
    var start = 0f
    for (stage in orderedPreRoll) {
      if (stage == phase) return start
      start = preRollWeights.getValue(stage)
    }
    return 1f
  }

  /**
   * Position inside the pre-roll bar for [phase] at [phaseFraction] (0..1 of that stage's own work).
   * Never leaves `[FLOOR_FRACTION, PRE_ROLL_CEILING]` and never decreases as the phases advance.
   */
  fun preRollFraction(phase: ExportPhase, phaseFraction: Float = 0f): Float {
    if (!phase.isPreRoll) return PRE_ROLL_CEILING
    val start = preRollStart(phase)
    val end = preRollWeights[phase] ?: start
    val local = phaseFraction.coerceIn(0f, 1f)
    val span = (end - start).coerceAtLeast(0f)
    val weight = (start + span * local).coerceIn(0f, 1f)
    return FLOOR_FRACTION + (PRE_ROLL_CEILING - FLOOR_FRACTION) * weight
  }

  /**
   * Frame-phase mapping, unchanged from the previous release: 0 frames reports [PRE_ROLL_CEILING],
   * the last frame reports `0.05 + 0.95 * 0.90` (= 0.905), so the ring tops out just below the
   * verification step. Both bounds are asserted by existing tests.
   */
  fun encodingFraction(doneFrames: Long, totalFrames: Long): Float {
    if (totalFrames <= 0L) return PRE_ROLL_CEILING
    val frameFrac = (doneFrames.toFloat() / totalFrames).coerceIn(0f, 1f).coerceAtMost(0.95f)
    return PRE_ROLL_CEILING + frameFrac * 0.90f
  }

  /**
   * The single value the ring shows, for any phase. During the pre-roll it is the stage bar; during
   * rendering it is the frame count (which never reports less than the pre-roll ceiling), and
   * finalising/verifying report their own, higher, values.
   */
  fun overall(
    phase: ExportPhase,
    phaseFraction: Float = 0f,
    doneFrames: Long = 0L,
    totalFrames: Long = 0L
  ): Float = when (phase) {
    ExportPhase.FINALISING -> FINALISING_FRACTION
    ExportPhase.VERIFYING -> 0.96f
    ExportPhase.RENDERING -> encodingFraction(doneFrames, totalFrames)
    else -> preRollFraction(phase, phaseFraction)
  }

  /**
   * Own bracket for the encoder drain + muxer finalisation: above the highest frame value
   * (`0.05 + 0.95 * 0.90` = 0.905) and below verification, so the ring never walks backwards.
   */
  const val FINALISING_FRACTION = 0.93f

  /** Human status line: always names the phase, and the frames when there are frames to count. */
  fun message(
    phase: ExportPhase,
    phaseFraction: Float,
    doneFrames: Long,
    totalFrames: Long,
    encodeFps: Float = 0f
  ): String = when (phase) {
    ExportPhase.RENDERING -> {
      val percent = if (totalFrames > 0L) {
        ((doneFrames.toFloat() / totalFrames.toFloat()).coerceIn(0f, 1f) * 100f).toInt()
      } else 0
      // Locale.US on purpose: the default locale would print "3,3 fps" (or non-Latin digits in
      // locales that use them) in the very line the reporter reads off the phone.
      "Hardware GPU pipeline: encoded $doneFrames / $totalFrames frames ($percent% · " +
        "${String.format(Locale.US, "%.1f", encodeFps)} fps)"
    }
    ExportPhase.FINALISING -> "Finishing the MP4: draining encoders and writing the index..."
    ExportPhase.VERIFYING -> "Verifying exported video integrity..."
    else -> {
      val percent = (phaseFraction.coerceIn(0f, 1f) * 100f).toInt()
      if (phase == ExportPhase.MIXING_AUDIO && percent > 0) {
        "${phase.label} ($percent%)"
      } else {
        "${phase.label}..."
      }
    }
  }
}

/**
 * Legacy name of the encoding mapping, kept so every existing caller/test keeps working. New code
 * should use [ExportProgressModel], which also covers the stages before the first frame.
 */
internal object ExportProgressMapping {
  fun encodingFraction(doneFrames: Long, totalFrames: Long): Float =
    ExportProgressModel.encodingFraction(doneFrames, totalFrames)
}
