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
import com.example.engine.controller.PreviewMixPolicy
import com.example.engine.media.MediaRelinkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

data class DecodedPcm(
  val samples: ShortArray,
  val sampleRate: Int,
  val channels: Int
)

data class TimelineAudioFormat(
  val sampleRate: Int = 48000,
  val channelCount: Int = 2,
  val bitrate: Int = 192000
)

data class AudioTrackDescriptor(
  val uri: String,
  val title: String,
  val timelineStartMs: Long,
  val durationMs: Long,
  val sourceStartMs: Long,
  val sourceEndMs: Long,
  val speed: Float,
  val volume: Float,
  val gainDb: Float,
  val fadeInMs: Long,
  val fadeOutMs: Long,
  val isMuted: Boolean,
  val isReversed: Boolean = false,
  val keyframes: List<com.example.domain.model.ClipKeyframe> = emptyList(),
  val audioEffects: com.example.domain.model.AudioEffectsSettings = com.example.domain.model.AudioEffectsSettings()
)

class AudioExportProcessor(
    private val muxer: MediaMuxer? = null,
    private val muxerLock: Any = Any(),
    private val isMuxerStarted: () -> Boolean = { true },
    private val onTrackReady: (Int) -> Unit = {}
) {
    companion object {
        private const val TAG = "AudioExportProcessor"
        private const val TIMEOUT_US = 10_000L
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

            // Wait until VideoExporter starts the muxer safely
            while (!isMuxerStarted()) {
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

    suspend fun mixTimelineAudio(
        timeline: Timeline,
        totalDurationMs: Long,
        targetSampleRate: Int = 48000,
        targetChannelCount: Int = 2,
        isCancelled: () -> Boolean = { false }
    ): ShortArray = withContext(Dispatchers.IO) {
        this@AudioExportProcessor.sampleRate = targetSampleRate
        this@AudioExportProcessor.channelCount = targetChannelCount

        val totalFrames = ((totalDurationMs / 1000.0) * targetSampleRate).toInt()
        if (totalFrames <= 0) return@withContext ShortArray(0)

        val mixedPcm = FloatArray(totalFrames * targetChannelCount)

        val tracks = collectAudioTracks(timeline)
        for (track in tracks) {
            if (isCancelled()) return@withContext ShortArray(0)
            val decoded = decodeTrackOrFail(track)
            if (decoded.samples.isEmpty()) continue
            mixTrackIntoBuffer(decoded, track, mixedPcm, totalFrames, targetSampleRate, targetChannelCount)
        }

        val finalPcm = ShortArray(mixedPcm.size)
        for (i in mixedPcm.indices) {
            finalPcm[i] = softClipSample(mixedPcm[i])
        }
        finalPcm
    }

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
        this@AudioExportProcessor.sampleRate = targetSampleRate
        this@AudioExportProcessor.channelCount = targetChannelCount

        val totalFrames = ((totalDurationMs / 1000.0) * targetSampleRate).toInt()
        if (totalFrames <= 0) return@withContext false

        val mixedPcm = FloatArray(totalFrames * targetChannelCount)

        val tracks = collectAudioTracks(timeline)
        for (track in tracks) {
            val decoded = decodeTrackOrFail(track)
            if (decoded.samples.isEmpty()) continue
            mixTrackIntoBuffer(decoded, track, mixedPcm, totalFrames, targetSampleRate, targetChannelCount)
        }

        val finalPcm = ShortArray(mixedPcm.size)
        for (i in mixedPcm.indices) {
            finalPcm[i] = softClipSample(mixedPcm[i])
        }

        encodeAndMuxPcm(finalPcm, targetSampleRate, targetChannelCount, audioEncoder, muxer, muxerLock, getAudioTrackIndex, setAudioTrackIndex, isMuxerStarted, onMuxerReadyCheck)
        true
    }

    private fun collectAudioTracks(timeline: Timeline): List<AudioTrackDescriptor> {
        val list = mutableListOf<AudioTrackDescriptor>()
        val videoAudible = PreviewMixPolicy.isTrackAudible(timeline, TrackType.MAIN_VIDEO)
        val overlayAudible = PreviewMixPolicy.isTrackAudible(timeline, TrackType.OVERLAY)
        val audioAudible = PreviewMixPolicy.isTrackAudible(timeline, TrackType.AUDIO)

        if (videoAudible) {
            timeline.videoClips.filter {
                it.isVideo && it.hasAudio && it.volume > 0f && !it.isMuted && isExportableAudioUri(it.uri)
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
                it.isVideo && it.hasAudio && it.volume > 0f && !it.isMuted && isExportableAudioUri(it.uri)
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
                    audioEffects = it.audioEffects
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
    private fun decodePcmFromMedia(uriString: String, windowStartMs: Long = 0L, windowEndMs: Long = Long.MAX_VALUE): DecodedPcm? {
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

    private fun mixTrackIntoBuffer(
        decoded: DecodedPcm,
        track: AudioTrackDescriptor,
        outputMix: FloatArray,
        totalFrames: Int,
        targetSampleRate: Int,
        targetChannels: Int
    ) {
        val startFrame = ((track.timelineStartMs / 1000.0) * targetSampleRate).toInt()
        val durationFrames = ((track.durationMs / 1000.0) * targetSampleRate).toInt()
        val endFrame = (startFrame + durationFrames).coerceAtMost(totalFrames)

        val srcSamples = decoded.samples
        val srcRate = decoded.sampleRate
        val srcChannels = decoded.channels

        val speed = track.speed.coerceIn(0.25f, 4.0f)
        val rateRatio = (srcRate.toDouble() / targetSampleRate.toDouble()) * speed

        val linearGain = track.volume * 10f.pow(track.gainDb / 20f)
        val fadeInFrames = ((track.fadeInMs / 1000.0) * targetSampleRate).toInt()
        val fadeOutFrames = ((track.fadeOutMs / 1000.0) * targetSampleRate).toInt()

        for (frame in startFrame until endFrame) {
            val relativeFrame = frame - startFrame
            val srcIndex = (relativeFrame * rateRatio).toInt()

            var fadeVol = 1.0f
            if (fadeInFrames > 0 && relativeFrame < fadeInFrames) {
                fadeVol *= (relativeFrame.toFloat() / fadeInFrames)
            }
            if (fadeOutFrames > 0 && (durationFrames - relativeFrame) < fadeOutFrames) {
                fadeVol *= ((durationFrames - relativeFrame).toFloat() / fadeOutFrames).coerceIn(0f, 1f)
            }

            val gain = linearGain * fadeVol

            for (ch in 0 until targetChannels) {
                // Source index 0 is the clip in-point (decodePcmFromMedia returns exactly the used
                // window), so a trimmed clip's audio starts where its picture does.
                val sampleVal: Short = if (srcChannels == 1) {
                    if (srcIndex < srcSamples.size) srcSamples[srcIndex] else 0
                } else {
                    // Multi channel sources are indexed by their real channel count; stereo
                    // (2 ch) maps 1:1 to the output channels exactly as before.
                    val sIdx = srcIndex * srcChannels + (if (ch < srcChannels) ch else ch % srcChannels)
                    if (sIdx < srcSamples.size) srcSamples[sIdx] else 0
                }
                val outIdx = frame * targetChannels + ch
                if (outIdx < outputMix.size) {
                    outputMix[outIdx] += (sampleVal * gain)
                }
            }
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

            var outIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            while (outIndex >= 0) {
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    synchronized(muxerLock) {
                        val trackIdx = muxer.addTrack(encoder.outputFormat)
                        setAudioTrackIndex(trackIdx)
                        onMuxerReadyCheck()
                    }
                } else if (outIndex >= 0) {
                    val encoded = encoder.getOutputBuffer(outIndex)
                    val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    if (encoded != null && bufferInfo.size > 0) {
                        while (!isMuxerStarted()) {
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
                    if (isEos) break
                }
                outIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
            }
        }
    }

    private fun softClipSample(sample: Float): Short {
        val norm = sample / 32768f
        val clipped = when {
            norm > 1.0f -> 1.0f
            norm < -1.0f -> -1.0f
            norm > 0.75f -> 0.75f + (norm - 0.75f) * 0.5f
            norm < -0.75f -> -0.75f + (norm + 0.75f) * 0.5f
            else -> norm
        }
        return (clipped * 32767f).toInt().toShort()
    }
}

/** Built-in / catalog URIs that have no real media file. Never synthesized into fake PCM. */
fun isPlaceholderCatalogUri(uri: String): Boolean {
    if (uri.isBlank()) return true
    return uri.startsWith("internal://") ||
        uri.startsWith("built_in_sfx_") ||
        uri.startsWith("demo://")
}

/** True when [uri] points at real playable media rather than a catalog placeholder or bare id. */
fun isExportableAudioUri(uri: String): Boolean {
    if (isPlaceholderCatalogUri(uri)) return false
    return uri.contains("://") || uri.startsWith("/")
}

/**
 * Source window a track actually uses, in milliseconds: `[start, end)` of the source file that the
 * mix needs. Pure and unit tested ([AudioExportWindowTest]); the mixer uses it to bound both the
 * decode and the memory of every track.
 *
 * `sourceMs = timelineRelMs * speed` (see `AudioClip.timelineToSourceMs`), so a 2x clip consumes
 * twice its timeline length of source, a 0.5x clip half of it.
 */
fun audioSourceWindow(track: AudioTrackDescriptor): Pair<Long, Long> {
    val speed = track.speed.coerceIn(0.25f, 4.0f)
    val neededMs = (track.durationMs.coerceAtLeast(1L) * speed).toLong()
    val hasSourceRange = track.sourceEndMs > track.sourceStartMs
    if (track.isReversed && hasSourceRange) {
        // Reversed playback starts at the source end and walks backwards (sourceMs =
        // sourceEndMs - rel * speed), so the window is anchored to the end. The margin sits
        // *before* the window because the decoded buffer is reversed afterwards.
        val endMs = track.sourceEndMs
        return maxOf(0L, endMs - neededMs - AUDIO_WINDOW_MARGIN_MS) to endMs
    }
    val startMs = track.sourceStartMs.coerceAtLeast(0L)
    // A small margin keeps codec priming / frame rounding inside the window.
    val endMs = minOf(
        if (hasSourceRange) track.sourceEndMs else Long.MAX_VALUE,
        startMs + neededMs + AUDIO_WINDOW_MARGIN_MS
    )
    return startMs to maxOf(endMs, startMs + 1L)
}

/** Extra source material decoded past the used window (codec priming / rounding). */
private const val AUDIO_WINDOW_MARGIN_MS = 250L

/**
 * Grows a PCM byte stream without boxing every sample. The previous
 * `MutableList<Byte>.addAll(chunk.toList())` path allocated tens of millions of
 * boxed Bytes for a few minutes of audio and stalled export at 5% for minutes.
 */
internal object PcmByteSink {
  fun create(estimatedBytes: Int): ByteArrayOutputStream {
    val cap = estimatedBytes.coerceIn(8 * 1024, 256 * 1024 * 1024)
    return ByteArrayOutputStream(cap)
  }

  fun estimatedPcmBytes(durationUs: Long, sampleRate: Int, channels: Int): Int {
    if (durationUs <= 0L || sampleRate <= 0 || channels <= 0) return 1024 * 1024
    val bytes = (durationUs / 1_000_000.0) * sampleRate * channels * 2.0
    return bytes.toInt().coerceIn(64 * 1024, 256 * 1024 * 1024)
  }

  fun append(out: ByteArrayOutputStream, src: ByteBuffer, scratch: ByteArray) {
    while (src.hasRemaining()) {
      val n = minOf(scratch.size, src.remaining())
      src.get(scratch, 0, n)
      out.write(scratch, 0, n)
    }
  }

  fun toLittleEndianShorts(out: ByteArrayOutputStream): ShortArray {
    val byteArr = out.toByteArray()
    if (byteArr.isEmpty()) return ShortArray(0)
    val shortBuf = ByteBuffer.wrap(byteArr).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    val shorts = ShortArray(shortBuf.remaining())
    shortBuf.get(shorts)
    return shorts
  }
}
