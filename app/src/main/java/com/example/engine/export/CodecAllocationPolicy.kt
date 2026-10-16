package com.example.engine.export

/** One codec the export may open for a clip, and the order in which the export must try them. */
data class CodecCandidate(
  /** `MediaCodec.createByCodecName(name)`, or null to let the platform pick a codec for the MIME. */
  val name: String?,
  /** Human-readable name used in the failure reasons the user sees. */
  val label: String,
  val hardware: Boolean
)

/**
 * Which codec to try for a clip, and what to do when the device refuses it.
 *
 * A real device (a four-minute project: several video tracks, overlays, stickers, an audio track,
 * filters and a colour grade) failed the export at 56% with
 *
 *     MediaCodec.CodecException: (errorCode=1100 recoverable=false transient=false
 *     diagnostic=android.media.MediaCodec.error_1100)
 *
 * while opening the decoder for the clip the frame needed. `1100` is
 * `MediaCodec.CodecException.ERROR_INSUFFICIENT_RESOURCE` - "required resource was not able to be
 * allocated": the device refused to hand out one more codec instance because the export was already
 * holding as many as it can give, and the frame genuinely needed every decoder it had open (the
 * main clip at a transition plus the overlays), so there was nothing left to release.
 *
 * Three consequences, and all three are decisions rather than plumbing, so all three live here and
 * are unit tested without a device:
 *
 *  1. **A refusal must be recognisable by itself.** [isResourceRefusal] matches the codec's own
 *     text. A corrupt or unsupported file must still fail as a corrupt file; only a resource
 *     refusal is answered by freeing resources and trying a different codec.
 *  2. **The next thing to try is the platform's software decoder for the same MIME type.** It
 *     decodes the same pictures, only slower, so a layer the user added is never dropped and
 *     nothing is traded for it. [candidates] is the whole ladder and never contains more than two
 *     entries: hardware first, software second (or software only when the caller already knows the
 *     hardware instances are used up).
 *  3. **A refusal is information.** The number of decoders that were open when the device refused
 *     is the practical limit of that device, so later clips can go straight to the software ladder
 *     step instead of walking into the same wall. See [hardwareLimit] / [needsSoftwareFallback].
 */
object CodecAllocationPolicy {

  /** `MediaCodec.CodecException.ERROR_INSUFFICIENT_RESOURCE` (API 23+). */
  const val ERROR_INSUFFICIENT_RESOURCE = 1100

  /**
   * Substrings that identify "the device could not allocate the codec" in a codec error message.
   *
   * Deliberately narrow: a codec that cannot decode this file at all ("no video track", "decoder
   * produced no output", "corrupt") must not be retried on another codec, because that would turn a
   * genuine "this clip cannot be decoded" into a slow failure with a misleading reason.
   */
  private val RESOURCE_MARKERS = listOf(
    "errorcode=1100",
    "insufficient_resource",
    "insufficient resource",
    "failed to allocate component instance",
    "no_memory",
    "nomemory",
    "resource_busy",
    "resourcebusy",
    "0xfffffc0e", // MEDIA_CODEC_NO_MEMORY as reported by several vendor codec services
    "cannot allocate",
    "could not allocate"
  )

  /**
   * The codecs to try for [mime], in order. [hardwareName] / [softwareName] come from
   * `MediaCodecList` (see `DecoderManager.findHardwareDecoderName` / `findSoftwareDecoderName`);
   * null means "this device does not list one".
   *
   * `preferHardware = false` is used when the caller already knows the hardware instances are
   * exhausted, so it must not waste an attempt on a codec the device has just refused.
   */
  fun candidates(
    mime: String,
    hardwareName: String?,
    softwareName: String?,
    preferHardware: Boolean
  ): List<CodecCandidate> {
    val software = softwareName?.let { CodecCandidate(it, "software codec $it", hardware = false) }
    if (!preferHardware) {
      // Software only: the export is here *because* a hardware instance was refused. A named
      // software codec is the answer when the device lists one; without one the platform default is
      // still worth exactly one attempt, because on such a device the default *is* the only decoder
      // there is (there is no vendor codec to resolve to).
      return listOfNotNull(software ?: CodecCandidate(null, "platform decoder for $mime", hardware = false))
    }
    val hardware = hardwareName?.let { CodecCandidate(it, "hardware codec $it", hardware = true) }
      ?: CodecCandidate(null, "platform decoder for $mime", hardware = true)
    return listOfNotNull(
      hardware,
      software?.takeIf { it.name != hardware.name }
    )
  }

  /**
   * Whether a resolved codec name is one of the platform's software codecs. Used to keep the
   * "hardware decoders in use" count honest on devices whose platform default *is* a software codec
   * (emulators, and hardware without a vendor decoder for the profile): such a decoder must not
   * count against the hardware instance budget, or the export would move clips to software decoding
   * long before the device ever complained.
   */
  fun looksSoftware(codecName: String): Boolean {
    val name = codecName.lowercase()
    return name.startsWith("omx.google.") ||
      name.startsWith("c2.android.") ||
      name.contains(".sw.") ||
      name.contains("software") ||
      name.contains("ffmpeg") ||
      name.contains("google")
  }

  /** True when [errorText] is the platform saying it could not allocate the codec instance. */
  fun isResourceRefusal(errorText: String?): Boolean {
    if (errorText.isNullOrBlank()) return false
    val lower = errorText.lowercase()
    return RESOURCE_MARKERS.any { lower.contains(it) }
  }

  /**
   * The number of hardware decoders this export may keep open, or null when nothing is known yet.
   *
   * [deviceMaxInstances] is what the device's codec declares (`maxSupportedInstances`), which is an
   * upper bound; [learnedLimit] is what actually happened, which wins when it is smaller because it
   * is the device's real behaviour under this export's load.
   */
  fun hardwareLimit(deviceMaxInstances: Int?, learnedLimit: Int?): Int? {
    val declared = deviceMaxInstances?.takeIf { it > 0 }
    val learned = learnedLimit?.takeIf { it >= 0 }
    return when {
      declared == null -> learned
      learned == null -> declared
      else -> minOf(declared, learned)
    }
  }

  /**
   * Whether the next clip must be opened on the software ladder step straight away because
   * [hardwareOpen] decoders already use up the hardware [limit]. `limit == 0` (the device refused
   * even the first hardware decoder) means every later clip is software from now on - slower, but
   * the same pictures.
   */
  fun needsSoftwareFallback(hardwareOpen: Int, limit: Int?): Boolean =
    limit != null && hardwareOpen >= limit

  /**
   * Added to a failed export's reason when the last codec error was a resource refusal: the user
   * cannot fix it by re-importing the clip, so the message must say what the device actually did.
   */
  fun hintFor(errorText: String?): String? =
    if (isResourceRefusal(errorText)) {
      "the device ran out of codec instances (errorCode=$ERROR_INSUFFICIENT_RESOURCE, " +
        "MediaCodec.ERROR_INSUFFICIENT_RESOURCE); closing background apps and retrying can free one"
    } else {
      null
    }
}
