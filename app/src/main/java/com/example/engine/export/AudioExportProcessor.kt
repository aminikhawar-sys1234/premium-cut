package com.example.engine.export

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import com.example.domain.model.Timeline
import com.example.domain.model.TrackType
import com.example.engine.audio.PitchSafeStretch
import com.example.engine.controller.PreviewMixPolicy
import com.example.engine.media.MediaRelinkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

// `DecodedPcm`, `TimelineAudioFormat` and `AudioTrackDescriptor` live in the pure `AudioMixMath.kt`
// so the mixer's arithmetic can be unit tested on a plain JVM (same package, so every caller and
// test that used them here is unaffected).

class AudioExportProcessor(
    private val muxer: MediaMuxer? = null,
    private val muxerLock: Any = Any(),
    private val isMuxerStarted: () -> Boolean = { true },
    private val onTrackReady: (Int) -> Unit = {}
) {
    companion object {
        private const val TAG = "AudioExportProcessor"
        private const val TIMEOUT_US = 10_000L
        /**
         * Wall-clock budget of ONE window decode of the chunked mixer. The decode loop used to have
         * no deadline at all: a decoder that never produced output and never signalled EOS (a
         * broken/unusual file, a codec that lost its input) looped forever inside
         * `mixTimelineAudio`, and the export panel sat at 5 % until the process was killed - the
         * defect the 2026-10-16 report describes. A window is a few seconds of audio, so a codec
         * that needs longer than this is not slow, it is stuck.
         */
        const val DEFAULT_DECODE_DEADLINE_MS = 30_000L
        /**
         * How long an audio worker may wait for the video side to start the muxer before the export
         * is failed. `MediaMuxer.start()` is a platform call with no timeout of its own, so the two
         * waits for it used to be `while (!isMuxerStarted) Thread.sleep(..)` - a spin that never
         * ends if the video encoder never reaches EOS, i.e. exactly the frozen-at-5 %
         * symptom. Waits are bounded so the failure has a reason and a name.
         */
        const val MUXER_START_DEADLINE_MS = 60_000L
        /** PCM grows by doubling from here (samples, not frames). */
        private const val INITIAL_PCM_SAMPLES = 1 shl 16
        /** Upper bound for the pre-sized window buffer (8M samples = 16 MB); growth handles more. */
        private const val MAX_PREALLOC_SAMPLES = 8 shl 20
    }

    private var context: Context? = null

    constructor(context: Context) : this(null, Any(), { true }, {}) {
        this.context = context
    }

    private var audioTrackIndex = -1
    private var isAudioFormatAdded = false

    var sampleRate: Int = 48000
    var channelCount: Int = 2 // Stereo
    var bitrate: Int = 192000

    private val pcmCache = mutableMapOf<String, DecodedPcm>()

    fun clearCache() {
        pcmCache.clear()
    }

    /**
     * Extracts, decodes/encodes and writes audio samples synchronized to video baseline PTS.
     */
    fun processAudioTrack(
        sourceUriPath: String,
        clipStartOffsetUs: Long,
        clipDurationUs: Long
    ): Boolean {
        val targetMuxer = muxer ?: return false
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null

        try {
            extractor.setDataSource(sourceUriPath)
            val sourceTrackIndex = selectAudioTrack(extractor)
            if (sourceTrackIndex == -1) {
                Log.w(TAG, "No audio track found in source.")
                return false
            }

            extractor.selectTrack(sourceTrackIndex)
            val inputFormat = extractor.getTrackFormat(sourceTrackIndex)

            // Dynamic Sample Rate aur Channel count (No hardcoded 44100Hz)
            val sampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 44100

            val channelCount = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 2

            this.sampleRate = sampleRate
            this.channelCount = channelCount

            // Pass-through check ya AAC re-encoder setup
            synchronized(muxerLock) {
                audioTrackIndex = targetMuxer.addTrack(inputFormat)
                isAudioFormatAdded = true
                onTrackReady(audioTrackIndex)
            }

            // Wait until VideoExporter starts the muxer safely. Bounded: a muxer that never starts
            // means the video side is not going to finish, and reporting that is better than
            // spinning here forever (see MUXER_START_DEADLINE_MS).
            val muxerDeadlineMs = System.currentTimeMillis() + MUXER_START_DEADLINE_MS
            while (!isMuxerStarted()) {
                if (System.currentTimeMillis() > muxerDeadlineMs) {
                    Log.e(
                        TAG,
                        "Muxer was not started within ${MUXER_START_DEADLINE_MS}ms; abandoning audio " +
                            "track '${sourceUriPath}' so the export can fail with a reason."
                    )
                    return false
                }
                Thread.sleep(10)
            }

            // Seek extractor to clip start
            extractor.seekTo(clipStartOffsetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val maxBufferSize = if (inputFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                inputFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else 64 * 1024

            val buffer = ByteBuffer.allocateDirect(maxBufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            var firstSampleTimeUs = -1L
            val clipEndUs = clipStartOffsetUs + clipDurationUs

            while (true) {
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) {
                    break // EOS
                }

                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs > clipEndUs) {
                    break // Clip duration exceeded
                }

                if (sampleTimeUs >= clipStartOffsetUs) {
                    if (firstSampleTimeUs == -1L) {
                        firstSampleTimeUs = sampleTimeUs
                    }

                    // ZERO-POINT PTS NORMALIZATION:
                    val normalizedPtsUs = sampleTimeUs - firstSampleTimeUs

                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    bufferInfo.presentationTimeUs = normalizedPtsUs
                    bufferInfo.flags = extractor.sampleFlags

                    synchronized(muxerLock) {
                        if (isMuxerStarted()) {
                            targetMuxer.writeSampleData(audioTrackIndex, buffer, bufferInfo)
                        }
                    }
                }

                extractor.advance()
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Audio processing failed: ${e.message}", e)
            return false
        } finally {
            try {
                extractor.release()
                decoder?.release()
            } catch (ignored: Exception) {}
        }
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                return i
            }
        }
        return -1
    }

    /**
     * Detects the native audio format (sample rate, channel count, bitrate) from input media clips.
     */
    fun detectTimelineAudioFormat(timeline: Timeline): TimelineAudioFormat {
        val ctx = context
        val candidateUris = (timeline.videoClips.filter { it.isVideo && it.hasAudio && it.uri.isNotBlank() }.map { it.uri } +
            timeline.overlayClips.filter { it.isVideo && it.hasAudio && it.uri.isNotBlank() }.map { it.uri } +
            timeline.audioClips.filter { it.uri.isNotBlank() }.map { it.uri })

        for (uriString in candidateUris) {
            if (ctx != null && !MediaRelinkManager.isRealPlayableMedia(ctx, uriString)) continue
            val extractor = MediaExtractor()
            try {
                val uri = Uri.parse(uriString)
                if (ctx != null && (uri.scheme == "content" || uri.scheme == "file")) {
                    extractor.setDataSource(ctx, uri, null)
                } else {
                    val f = File(uriString)
                    if (f.exists()) extractor.setDataSource(f.absolutePath) else extractor.setDataSource(uriString)
                }

                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/")) {
                        val sr = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 48000
                        val ch = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
                        val br = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                            format.getInteger(MediaFormat.KEY_BIT_RATE).coerceIn(64000, 320000)
                        } else {
                            if (ch == 1) 128000 else 192000
                        }
                        this.sampleRate = sr.coerceIn(22050, 96000)
                        this.channelCount = ch.coerceIn(1, 2)
                        this.bitrate = br
                        Log.i(TAG, "Detected timeline audio format from $uriString: ${this.sampleRate} Hz, ${this.channelCount} ch, ${this.bitrate} bps")
                        return TimelineAudioFormat(this.sampleRate, this.channelCount, this.bitrate)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not extract format info for $uriString", e)
            } finally {
                try { extractor.release() } catch (ignored: Exception) {}
            }
        }
        this.sampleRate = 48000
        this.channelCount = 2
        this.bitrate = 192000
        return TimelineAudioFormat(48000, 2, 192000)
    }

    fun hasActiveAudio(timeline: Timeline): Boolean {
        val hasClipSolo = timeline.audioClips.any { it.isSolo }
        val hasAudibleVideoClips = timeline.videoClips.any {
            it.isVideo && it.hasAudio && isExportableAudioUri(it.uri) &&
                PreviewMixPolicy.clipGain(it, timeline, TrackType.MAIN_VIDEO) > 0f
        }
        val hasAudibleOverlays = timeline.overlayClips.any {
            it.isVideo && it.hasAudio && isExportableAudioUri(it.uri) &&
                PreviewMixPolicy.clipGain(it, timeline, TrackType.OVERLAY) > 0f
        }
        val hasAudibleAudioClips = timeline.audioClips.any {
            isExportableAudioUri(it.uri) && PreviewMixPolicy.audioClipGain(it, timeline, hasClipSolo) > 0f
        }
        return hasAudibleVideoClips || hasAudibleOverlays || hasAudibleAudioClips
    }

    /**
     * Mixes the timeline's audio into one interleaved 16-bit buffer.
     *
     * Chunked, bounded and observable: see [TimelineAudioMixer] and [AudioMixPlan]. The previous
     * implementation allocated the whole timeline twice (a float accumulator *and* a short output
     * buffer) and started the render loop only after all of it was done - which, on a ten minute
     * project, is both the memory peak of the export and the phase the progress panel sat in at 5 %
     * with nothing but "encoded 0 / N frames" on screen.
     *
     * @param onProgress `(0..1)` of the mix, reported once per chunk: the export panel advances
     *   during the mix, and the stall watchdog can see that the mix is still moving.
     * @param isCancelled checked before every chunk (and inside every decode), so cancelling a long
     *   project's mix returns in milliseconds instead of minutes.
     * @param decodeDeadlineMs per-window decode deadline. 0 disables it (used by analysis callers).
     * @throws ExportPipelineException when a track the user added cannot be decoded at all - the
     *   export never silently replaces a track with silence. Gaps past the end of a shorter source
     *   are not failures; they are mixed as silence and reported.
     */
    suspend fun mixTimelineAudio(
        timeline: Timeline,
        totalDurationMs: Long,
        targetSampleRate: Int = 48000,
        targetChannelCount: Int = 2,
        isCancelled: () -> Boolean = { false },
        onProgress: (Float) -> Unit = {},
        decodeDeadlineMs: Long = DEFAULT_DECODE_DEADLINE_MS
    ): ShortArray = withContext(Dispatchers.IO) {
        this@AudioExportProcessor.sampleRate = targetSampleRate
        this@AudioExportProcessor.channelCount = targetChannelCount

        val totalFrames = ((totalDurationMs / 1000.0) * targetSampleRate).toInt()
        if (totalFrames <= 0) return@withContext ShortArray(0)

        val mixedPcm = ShortArray(totalFrames * targetChannelCount)

        val tracks = collectAudioTracks(timeline)
        if (tracks.isEmpty()) {
            // Nothing exportable to decode (no source passed `isExportableAudioUri`, or every lane is
            // muted): the mix is silence, and that is the shape every caller expects - a full-length
            // buffer for a positive duration. The pipeline only calls this when `hasActiveAudio` is
            // true, i.e. when a real source does exist; a timeline that reaches this branch has no
            // audio to write, and [ExportPipelineException] is deliberately *not* thrown for it (the
            // old mixer returned the same silent buffer).
            Log.w(TAG, "Timeline has no exportable audio source; the mix is silence.")
            return@withContext mixedPcm
        }
        val chunkFrames = AudioMixPlan.chunkFrames(targetSampleRate)
        val chunks = AudioMixPlan.chunks(totalFrames, chunkFrames)
        val startedMs = System.currentTimeMillis()

        // End of the successfully decoded material of each track, so a window that lands entirely
        // past a shorter source is "the source ended" (silence, and honest reporting) rather than a
        // decode failure that aborts the export.
        val decodedUntilMs = HashMap<Int, Long>()

        val result = TimelineAudioMixer.mix(
            out = mixedPcm,
            totalFrames = totalFrames,
            channels = targetChannelCount,
            targetSampleRate = targetSampleRate,
            tracks = tracks,
            chunks = chunks,
            decodeWindow = { request ->
                // Cancelling must never look like a decode failure: a cancelled window comes back as
                // an empty window of the right shape (its frames are counted as silent, and the
                // export is being torn down anyway).
                val decoded = if (isCancelled()) {
                    null
                } else {
                    decodeWindowFor(tracks[request.trackIndex], request, isCancelled, decodeDeadlineMs, decodedUntilMs)
                }
                if (decoded == null && isCancelled()) {
                    MixPcmWindow(
                        ShortArray(0), targetSampleRate, targetChannelCount,
                        request.startMs, request.originSample
                    )
                } else {
                    decoded
                }
            },
            gainAt = { trackIndex, relativeFrame, durationFrames, rate ->
                exportMixLinearGain(tracks[trackIndex], relativeFrame, durationFrames, rate)
            },
            isCancelled = isCancelled,
            onChunkMixed = { done, total ->
                if (total > 0) onProgress((done.toFloat() / total.toFloat()).coerceIn(0f, 1f))
            }
        )
        clearCache()

        // A cancel anywhere (including inside the final decode) is not a failure: report it the way
        // the pipeline expects and let the render loop's own cancel path take over.
        if (result.cancelled || isCancelled()) return@withContext ShortArray(0)

        // A window that could not be decoded is an export error, not silence: the previous
        // implementation failed the export when a track's audio could not be decoded, and a silent
        // hole in the middle of a track is exactly the "successful export that lost the user's
        // audio" this pipeline is not allowed to produce. (A window that simply lies past the end of
        // a shorter source is handled in decodeWindowFor as end-of-source silence, not as a failure.)
        if (result.decodeFailuresPerTrack.isNotEmpty()) {
            val detail = result.decodeFailuresPerTrack.entries.joinToString(", ") { (index, failures) ->
                val track = tracks.getOrNull(index)
                "'${track?.title ?: "track $index"}' (${failures} window(s))"
            }
            throw ExportPipelineException(
                "Could not decode the audio of $detail. Export stopped instead of writing silence.",
                retryable = false
            )
        }
        val partial = tracks.indices.filter { index -> result.silentFrames(index) > 0L }
        if (partial.isNotEmpty()) {
            val detail = partial.joinToString(", ") { index ->
                "'${tracks[index].title}' ${result.silentFrames(index)} frame(s)"
            }
            Log.w(TAG, "Audio mix: no decoded samples for $detail; those frames are mixed as silence (source shorter than the clip).")
        }
        ExportDiagnostics.stage(
            "audio-mix-chunked",
            System.currentTimeMillis() - startedMs,
            "tracks=${tracks.size} chunks=${result.chunksMixed}/${chunks.size} frames=${result.framesMixed} " +
                "chunkMs=${AudioMixPlan.DEFAULT_CHUNK_MS} deadlineMs=$decodeDeadlineMs"
        )
        mixedPcm
    }

    /**
     * Decodes one window of one track for the mixer.
     *
     * Streaming tracks (forward, unity speed, no audio effects - see [AudioMixPlan.isStreamable])
     * are decoded window by window: that is what keeps the peak memory of the mix at "one window per
     * track" instead of "the whole timeline in floats and shorts plus every decoded track". Tracks
     * that carry processing are decoded once for their trim window with exactly the previous
     * pipeline (decode -> reverse -> DSP -> pitch-safe speed), because those steps are
     * whole-buffer operations and a chunked version would change the sound.
     */
    private fun decodeWindowFor(
        track: AudioTrackDescriptor,
        request: AudioMixWindowRequest,
        isCancelled: () -> Boolean,
        deadlineMs: Long,
        decodedUntilMs: HashMap<Int, Long>
    ): MixPcmWindow? {
        if (request.fullWindow) {
            val decoded = runCatching { decodeTrackOrFail(track) }
                .onFailure { Log.e(TAG, "Audio decode failed for '${track.title}': ${it.message}", it) }
                .getOrNull() ?: return null
            if (decoded.samples.isEmpty()) return null
            return MixPcmWindow(decoded.samples, decoded.sampleRate, decoded.channels, request.startMs, 0)
        }
        val decoded = decodePcmFromMedia(track.uri, request.startMs, request.endMs, deadlineMs, isCancelled)
        if (decoded == null || decoded.samples.isEmpty()) {
            val lastCovered = decodedUntilMs[request.trackIndex]
            if (lastCovered != null && request.startMs >= lastCovered) {
                // The source ended before this window: the clip is longer than its audio. Silence,
                // not a failure - exactly what the whole-window mixer produced before.
                return MixPcmWindow(ShortArray(0), decoded?.sampleRate ?: 48000, decoded?.channels ?: 2, request.startMs, request.originSample)
            }
            Log.w(
                TAG,
                "Audio window decode failed for '${track.title}' at ${request.startMs}..${request.endMs}ms " +
                    "(window #${request.chunk.index})"
            )
            return null
        }
        val coveredEndMs = request.startMs + (decoded.samples.size / decoded.channels.coerceAtLeast(1)).toLong() * 1000L /
            decoded.sampleRate.coerceAtLeast(1)
        decodedUntilMs[request.trackIndex] = maxOf(decodedUntilMs[request.trackIndex] ?: 0L, coveredEndMs)
        // The mixer indexes the window as `sampleIndex(relativeFrame) - originSample`, so the origin
        // must describe *this* buffer's first sample. It is derived from the window the decoder
        // actually used (and from the sample rate it actually reported), not from the request: the
        // request's window start is rounded to a millisecond, which is up to one sample away from the
        // index the mixer planned, and reading the buffer with the planned index would shift every
        // sample of the chunk by that much.
        return MixPcmWindow(
            samples = decoded.samples,
            sampleRate = decoded.sampleRate,
            channels = decoded.channels,
            windowStartMs = request.startMs,
            originSample = AudioMixPlan.windowOriginSample(track, request.startMs, decoded.sampleRate)
        )
    }

    /**
     * Mixes the timeline and writes it straight into [audioEncoder] / [muxer].
     *
     * Kept for callers that own the muxer themselves; the mix itself is the same chunked, bounded
     * [mixTimelineAudio] the export pipeline uses, so this path cannot drift from it (it used to
     * carry its own copy of the whole-timeline float buffer).
     */
    suspend fun renderMixedAudioToMuxer(
        timeline: Timeline,
        totalDurationMs: Long,
        audioEncoder: MediaCodec,
        muxer: MediaMuxer,
        muxerLock: Any,
        getAudioTrackIndex: () -> Int,
        setAudioTrackIndex: (Int) -> Unit,
        isMuxerStarted: () -> Boolean,
        onMuxerReadyCheck: () -> Unit,
        targetSampleRate: Int = 48000,
        targetChannelCount: Int = 2
    ): Boolean = withContext(Dispatchers.IO) {
        val finalPcm = mixTimelineAudio(
            timeline = timeline,
            totalDurationMs = totalDurationMs,
            targetSampleRate = targetSampleRate,
            targetChannelCount = targetChannelCount
        )
        if (finalPcm.isEmpty()) return@withContext false
        encodeAndMuxPcm(
            finalPcm, targetSampleRate, targetChannelCount, audioEncoder, muxer, muxerLock,
            getAudioTrackIndex, setAudioTrackIndex, isMuxerStarted, onMuxerReadyCheck
        )
        true
    }

    private fun collectAudioTracks(timeline: Timeline): List<AudioTrackDescriptor> {
        val list = mutableListOf<AudioTrackDescriptor>()
        val videoAudible = PreviewMixPolicy.isTrackAudible(timeline, TrackType.MAIN_VIDEO)
        val overlayAudible = PreviewMixPolicy.isTrackAudible(timeline, TrackType.OVERLAY)
        val audioAudible = PreviewMixPolicy.isTrackAudible(timeline, TrackType.AUDIO)

        if (videoAudible) {
            timeline.videoClips.filter {
                it.isVideo && it.hasAudio && isExportableAudioUri(it.uri) &&
                    PreviewMixPolicy.clipGain(it, timeline, TrackType.MAIN_VIDEO) > 0f
            }.forEach {
                list.add(AudioTrackDescriptor(
                    uri = it.uri,
                    title = it.name,
                    timelineStartMs = it.timelineStartMs,
                    durationMs = it.durationMs,
                    sourceStartMs = it.sourceStartMs,
                    sourceEndMs = it.sourceEndMs,
                    speed = it.speed,
                    volume = it.volume,
                    gainDb = 0f,
                    fadeInMs = 0L,
                    fadeOutMs = 0L,
                    isMuted = it.isMuted,
                    isReversed = it.isReversed,
                    keyframes = it.keyframes,
                    audioEffects = it.audioEffects
                ))
            }
        }
        if (overlayAudible) {
            timeline.overlayClips.filter {
                it.isVideo && it.hasAudio && isExportableAudioUri(it.uri) &&
                    PreviewMixPolicy.clipGain(it, timeline, TrackType.OVERLAY) > 0f
            }.forEach {
                list.add(AudioTrackDescriptor(
                    uri = it.uri,
                    title = it.name,
                    timelineStartMs = it.timelineStartMs,
                    durationMs = it.durationMs,
                    sourceStartMs = it.sourceStartMs,
                    sourceEndMs = it.sourceEndMs,
                    speed = it.speed,
                    volume = it.volume,
                    gainDb = 0f,
                    fadeInMs = 0L,
                    fadeOutMs = 0L,
                    isMuted = it.isMuted,
                    isReversed = it.isReversed,
                    keyframes = it.keyframes,
                    audioEffects = it.audioEffects
                ))
            }
        }
        if (audioAudible) {
            val hasClipSolo = timeline.audioClips.any { it.isSolo }
            val duckEnvelopes = com.example.engine.audio.AutoDucking.envelopesFor(timeline)
            timeline.audioClips.filter {
                PreviewMixPolicy.audioClipGain(it, timeline, hasClipSolo) > 0f && isExportableAudioUri(it.uri)
            }.forEach {
                list.add(AudioTrackDescriptor(
                    uri = it.uri,
                    title = it.title,
                    timelineStartMs = it.timelineStartMs,
                    durationMs = it.durationMs,
                    sourceStartMs = it.sourceStartMs,
                    sourceEndMs = it.sourceEndMs,
                    speed = it.speed,
                    volume = it.volume,
                    gainDb = it.gainDb,
                    fadeInMs = it.fadeInMs,
                    fadeOutMs = it.fadeOutMs,
                    isMuted = it.isMuted,
                    isReversed = it.isReversed,
                    keyframes = it.keyframes,
                    audioEffects = it.audioEffects,
                    duckEnvelope = duckEnvelopes[it.trackIndex]?.takeIf { env ->
                        env.affects(it.timelineStartMs, it.timelineStartMs + it.durationMs)
                    }
                ))
            }
        }
        return list
    }

    /**
     * Source window this descriptor actually uses, in milliseconds.
     *
     * The mixer only ever reads [AudioTrackDescriptor.timelineStartMs] +
     * [AudioTrackDescriptor.durationMs] of timeline time, which at [AudioTrackDescriptor.speed]
     * maps to this much source material. Decoding the whole file used to mean a 10 minute song
     * trimmed to 10 seconds was fully decoded (and kept in memory) for nothing.
     */
    private fun sourceWindowFor(track: AudioTrackDescriptor): Pair<Long, Long> = audioSourceWindow(track)

    private fun decodeTrackOrFail(track: AudioTrackDescriptor): DecodedPcm {
        val fx = track.audioEffects
        val (windowStartMs, windowEndMs) = sourceWindowFor(track)
        val cacheKey = "${track.uri}_${track.speed}_${track.isReversed}_$windowStartMs-$windowEndMs" +
            "_${fx.voiceEffect}_${fx.pitchShiftSemitones}_${fx.noiseReductionDb}_${fx.lowGainDb}_${fx.midGainDb}_${fx.highGainDb}_${fx.normalizeVolume}"
        pcmCache[cacheKey]?.let { return it }

        if (isPlaceholderCatalogUri(track.uri) || track.uri.isBlank()) {
            Log.w(TAG, "Skipping placeholder audio '${track.title}' (${track.uri}); no real media to decode.")
            val empty = DecodedPcm(ShortArray(0), sampleRate, 2)
            pcmCache[cacheKey] = empty
            return empty
        }
        if (context == null) {
            throw ExportPipelineException(
                "Audio '${track.title}' cannot be decoded without a context.",
                retryable = false
            )
        }
        var decoded = decodePcmFromMedia(track.uri, windowStartMs, windowEndMs)
            ?: throw ExportPipelineException(
                "Could not decode audio from '${track.title}' (${track.uri}). Export stopped instead of writing silence.",
                retryable = false
            )

        // Apply audio reversal if enabled
        if (track.isReversed && decoded.samples.isNotEmpty()) {
            val channels = decoded.channels.coerceAtLeast(1)
            val frames = decoded.samples.size / channels
            val revSamples = ShortArray(decoded.samples.size)
            for (f in 0 until frames) {
                val srcF = frames - 1 - f
                for (ch in 0 until channels) {
                    revSamples[f * channels + ch] = decoded.samples[srcF * channels + ch]
                }
            }
            decoded = decoded.copy(samples = revSamples)
        }

        // Apply real DSP Audio Effects (Noise Gate, Equalizer, Voice Effects, Pitch Shift, Volume Normalization)
        if (fx.voiceEffect != com.example.domain.model.VoiceEffect.NONE ||
            fx.pitchShiftSemitones != 0f ||
            fx.noiseReductionDb > 0f ||
            fx.lowGainDb != 0f ||
            fx.midGainDb != 0f ||
            fx.highGainDb != 0f ||
            fx.normalizeVolume
        ) {
            val processed = com.example.engine.audio.AdvancedAudioProcessor.processPcmBuffer(
                pcm = decoded.samples,
                sampleRate = decoded.sampleRate,
                channels = decoded.channels,
                effects = fx
            )
            decoded = decoded.copy(samples = processed)
        }

        // Speed changes duration but not pitch, like the Media3 preview (pitch fixed at 1.0).
        // The mixer then reads this buffer 1:1 on the timeline.
        val trackSpeed = track.speed.coerceIn(0.25f, 4.0f)
        if (!PitchSafeStretch.isUnity(trackSpeed) && decoded.samples.isNotEmpty()) {
            decoded = decoded.copy(
                samples = PitchSafeStretch.applySpeed(decoded.samples, decoded.channels, decoded.sampleRate, trackSpeed)
            )
        }

        pcmCache[cacheKey] = decoded
        return decoded
    }

    /**
     * Decodes the audio of [uriString] to 16 bit PCM, covering only the source window
     * [windowStartMs]..[windowEndMs].
     *
     * The returned buffer's index 0 is the first sample of the window, which is what makes the
     * clip in-point (trim) audible-correct in the mix and keeps a 10 minute song trimmed to 10
     * seconds from being decoded in full. The old implementation also accumulated the PCM in a
     * `MutableList<Byte>` (one boxed element per byte) — the single biggest CPU/GC cost of the
     * whole export for projects with audio.
     */
    /** Decodes [startMs, endMs) of a media file's audio for analysis (beats, speech); null when it has no decodable audio. */
    fun decodeForAnalysis(uriString: String, startMs: Long, endMs: Long): DecodedPcm? =
        decodePcmFromMedia(uriString, startMs, endMs, AudioExportProcessor.DEFAULT_DECODE_DEADLINE_MS, { false })

    /**
     * @param deadlineMs wall-clock budget of this decode (0 = unbounded). A decode that overruns it
     *   gives up and returns null with the counter state in the log, instead of looping forever.
     * @param isCancelled checked on every loop iteration, so cancelling an export does not wait for
     *   the remaining minutes of audio to be decoded.
     */
    private fun decodePcmFromMedia(
        uriString: String,
        windowStartMs: Long = 0L,
        windowEndMs: Long = Long.MAX_VALUE,
        deadlineMs: Long = DEFAULT_DECODE_DEADLINE_MS,
        isCancelled: () -> Boolean = { false }
    ): DecodedPcm? {
        val ctx = context ?: return null
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            val uri = Uri.parse(uriString)
            if (uri.scheme == "content" || uri.scheme == "file") {
                extractor.setDataSource(ctx, uri, null)
            } else {
                val f = File(uriString)
                if (f.exists()) extractor.setDataSource(f.absolutePath) else extractor.setDataSource(uriString)
            }

            val audioTrack = selectAudioTrack(extractor)
            if (audioTrack == -1) return null

            extractor.selectTrack(audioTrack)
            val format = extractor.getTrackFormat(audioTrack)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val srcSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()

            val windowStartUs = (windowStartMs.coerceAtLeast(0L)) * 1000L
            val windowEndUs = if (windowEndMs == Long.MAX_VALUE) Long.MAX_VALUE else windowEndMs.coerceAtLeast(windowStartMs) * 1000L
            if (windowStartUs > 0L) {
                // Skip straight to the window. A failed seek only costs speed, never audio: the
                // per-buffer skip below drops everything that lands before the window start.
                try {
                    extractor.seekTo(windowStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    decoder.flush()
                } catch (seekError: Exception) {
                    Log.w(TAG, "Audio seek to ${windowStartMs}ms failed, decoding from the start: ${seekError.message}")
                }
            }

            var shorts = ShortArray(
                if (windowEndUs == Long.MAX_VALUE) INITIAL_PCM_SAMPLES
                else (((windowEndUs - windowStartUs) * srcSampleRate / 1_000_000L) * srcChannels)
                    .coerceIn(INITIAL_PCM_SAMPLES.toLong(), MAX_PREALLOC_SAMPLES.toLong()).toInt()
            )
            var sampleCount = 0
            var reachedWindowEnd = false
            val bufferInfo = MediaCodec.BufferInfo()
            var isEos = false
            val deadlineAt = if (deadlineMs > 0L) android.os.SystemClock.elapsedRealtime() + deadlineMs else 0L
            var timedOut = false
            var cancelledEarly = false

            fun append(buffer: ByteBuffer, framesInBuffer: Int, skipFrames: Int) {
                val usableFrames = framesInBuffer - skipFrames
                if (usableFrames <= 0) return
                val needed = sampleCount + usableFrames * srcChannels
                if (needed > shorts.size) {
                    var newSize = shorts.size
                    while (newSize < needed) newSize = newSize shl 1
                    shorts = shorts.copyOf(newSize)
                }
                buffer.position(bufferInfo.offset + skipFrames * srcChannels * 2)
                buffer.limit(bufferInfo.offset + bufferInfo.size)
                buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts, sampleCount, usableFrames * srcChannels)
                sampleCount += usableFrames * srcChannels
            }

            while (!isEos && !reachedWindowEnd) {
                if (isCancelled()) {
                    cancelledEarly = true
                    break
                }
                if (deadlineAt > 0L && android.os.SystemClock.elapsedRealtime() > deadlineAt) {
                    timedOut = true
                    break
                }
                val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val inputBuf = decoder.getInputBuffer(inIndex) ?: continue
                    val sampleSize = extractor.readSampleData(inputBuf, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        isEos = true
                    } else {
                        decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }

                var outIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                while (outIndex >= 0) {
                    val outBuf = decoder.getOutputBuffer(outIndex)
                    if (outBuf != null && bufferInfo.size >= srcChannels * 2) {
                        val ptsUs = bufferInfo.presentationTimeUs
                        val framesInBuffer = bufferInfo.size / (srcChannels * 2)
                        val bufferEndUs = ptsUs + framesInBuffer * 1_000_000L / srcSampleRate
                        if (bufferEndUs > windowStartUs) {
                            // Drop the samples a seek landed before the window start.
                            val skipFrames = if (ptsUs >= windowStartUs) 0 else
                                (((windowStartUs - ptsUs) * srcSampleRate) / 1_000_000L).toInt().coerceIn(0, framesInBuffer)
                            append(outBuf, framesInBuffer, skipFrames)
                        }
                        if (windowEndUs != Long.MAX_VALUE && bufferEndUs >= windowEndUs) {
                            reachedWindowEnd = true
                        }
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isEos = true
                        break
                    }
                    if (reachedWindowEnd) break
                    outIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
                }
            }

            if (timedOut) {
                Log.e(
                    TAG,
                    "Audio decode deadline (${deadlineMs}ms) exceeded for $uriString " +
                        "[${windowStartMs}ms, ${windowEndMs}ms): decoded $sampleCount samples at ${srcSampleRate}Hz; " +
                        "the decoder produced no end of stream. Treating this window as undecodable."
                )
                return null
            }
            if (cancelledEarly) {
                Log.i(TAG, "Audio decode cancelled for $uriString [${windowStartMs}ms, ${windowEndMs}ms) after $sampleCount samples")
                return null
            }
            if (sampleCount == 0) return null
            return DecodedPcm(if (sampleCount == shorts.size) shorts else shorts.copyOf(sampleCount), srcSampleRate, srcChannels)
        } catch (e: Exception) {
            Log.e(TAG, "Failed decoding PCM from $uriString: ${e.message}")
            return null
        } finally {
            try { decoder?.stop() } catch (ignored: Exception) {}
            try { decoder?.release() } catch (ignored: Exception) {}
            try { extractor.release() } catch (ignored: Exception) {}
        }
    }

    private fun encodeAndMuxPcm(
        pcm: ShortArray,
        sampleRate: Int,
        channels: Int,
        encoder: MediaCodec,
        muxer: MediaMuxer,
        muxerLock: Any,
        getAudioTrackIndex: () -> Int,
        setAudioTrackIndex: (Int) -> Unit,
        isMuxerStarted: () -> Boolean,
        onMuxerReadyCheck: () -> Unit
    ) {
        val byteBuf = ByteBuffer.allocateDirect(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        val shortBuf = byteBuf.asShortBuffer()
        shortBuf.put(pcm)
        byteBuf.position(0)

        val bufferInfo = MediaCodec.BufferInfo()
        var inputOffset = 0
        val totalBytes = pcm.size * 2
        var isEos = false

        var presentationTimeUs = 0L
        val bytesPerFrame = channels * 2
        val usPerFrame = 1_000_000.0 / sampleRate

        while (!isEos) {
            val inIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
            if (inIndex >= 0) {
                val inBuf = encoder.getInputBuffer(inIndex)
                if (inBuf != null) {
                    inBuf.clear()
                    val bytesToCopy = inBuf.remaining().coerceAtMost(totalBytes - inputOffset)
                    if (bytesToCopy > 0) {
                        byteBuf.position(inputOffset)
                        byteBuf.limit(inputOffset + bytesToCopy)
                        inBuf.put(byteBuf)
                        inputOffset += bytesToCopy

                        val framesSent = bytesToCopy / bytesPerFrame
                        encoder.queueInputBuffer(inIndex, 0, bytesToCopy, presentationTimeUs, 0)
                        presentationTimeUs += (framesSent * usPerFrame).toLong()
                    } else {
                        encoder.queueInputBuffer(inIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        isEos = true
                    }
                }
            }

            // Drain loop: INFO_OUTPUT_FORMAT_CHANGED (-2) and INFO_TRY_AGAIN_LATER (-1) are
            // negative, so the old "while (outIndex >= 0)" loop could never run the format-change
            // branch and the audio track was never added to the muxer on this path.
            var outIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            while (true) {
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        synchronized(muxerLock) {
                            val trackIdx = muxer.addTrack(encoder.outputFormat)
                            setAudioTrackIndex(trackIdx)
                            onMuxerReadyCheck()
                        }
                        outIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
                    }
                    outIndex >= 0 -> {
                        val encoded = encoder.getOutputBuffer(outIndex)
                        val eosFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        if (encoded != null && bufferInfo.size > 0) {
                            // Same bound as processAudioTrack: the muxer belongs to the video side, and a
                            // sample that cannot be written because the muxer never started must fail the
                            // export instead of parking this thread (and with it the export) forever.
                            val muxerDeadlineMs = System.currentTimeMillis() + MUXER_START_DEADLINE_MS
                            while (!isMuxerStarted()) {
                                if (System.currentTimeMillis() > muxerDeadlineMs) {
                                    throw IllegalStateException(
                                        "The MP4 muxer was not started within ${MUXER_START_DEADLINE_MS}ms; " +
                                            "the encoded audio could not be written."
                                    )
                                }
                                Thread.sleep(5)
                            }
                            synchronized(muxerLock) {
                                val trackIdx = getAudioTrackIndex()
                                if (trackIdx >= 0 && isMuxerStarted()) {
                                    try {
                                        muxer.writeSampleData(trackIdx, encoded, bufferInfo)
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Safe catch during audio muxer writeSampleData: ${e.message}")
                                    }
                                }
                            }
                        }
                        encoder.releaseOutputBuffer(outIndex, false)
                        if (eosFrame) break
                        outIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
                    }
                    else -> break // INFO_TRY_AGAIN_LATER: nothing drained this round
                }
            }
        }
    }

}

// The audio-mix maths that used to live here - `softClipSample`, `isPlaceholderCatalogUri`,
// `isExportableAudioUri`, `audioSourceWindow`, `exportMixLinearGain` and `PcmByteSink` - moved to
// the pure `AudioMixMath.kt` (same package) so the values that decide what the exported audio
// sounds like are unit-testable on a plain JVM. `softClipSample` is now `AudioMixMath.softClip`.
