package com.example.engine.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Result of [BeatDetector.analyze]. Times are milliseconds from the start of the analysed buffer. */
data class BeatAnalysis(
  val bpm: Double,
  val beatsMs: List<Long>,
  val onsetsMs: List<Long>,
  /** 0..1, how much of the onset energy sits on the beat grid. Below ~0.15 the grid is a guess. */
  val confidence: Float
) {
  val hasBeat: Boolean get() = bpm > 0.0 && beatsMs.size >= 2
}

/**
 * Onset and tempo analysis of a user's own audio, so a cut can land on the beat without a music
 * catalog. Pure Kotlin: feed it mono samples, get beat times back.
 *
 * Pipeline: transient-emphasised log-energy flux (100 frames/s) -> adaptive onset picking ->
 * autocorrelation tempo with a mild prior around 120 BPM -> phase search for the best beat grid ->
 * each grid point snapped to the strongest nearby onset so a slightly drifting tempo is followed.
 */
object BeatDetector {

  private const val ENV_RATE = 100.0

  fun analyze(
    mono: FloatArray,
    sampleRate: Int,
    minBpm: Double = 70.0,
    maxBpm: Double = 180.0
  ): BeatAnalysis {
    if (mono.size < sampleRate * 2 || sampleRate <= 0) return empty()
    val env = onsetEnvelope(mono, sampleRate)
    if (env.size < 300) return empty()
    val peak = env.max()
    if (peak <= 1e-6f) return empty()

    val onsets = pickOnsets(env)
    val period = estimatePeriod(env, minBpm, maxBpm) ?: return BeatAnalysis(0.0, emptyList(), framesToMs(onsets), 0f)
    val (grid, confidence) = trackGrid(env, onsets, period)
    return BeatAnalysis(
      bpm = 60.0 * ENV_RATE / period,
      beatsMs = grid.map { (it * 1000.0 / ENV_RATE).roundToInt().toLong() },
      onsetsMs = framesToMs(onsets),
      confidence = confidence
    )
  }

  /** Interleaved 16-bit PCM to mono floats in -1..1, decimated by an integer factor to ~[targetRate]. */
  fun toMono(pcm: ShortArray, channels: Int, sampleRate: Int, targetRate: Int = 22_050): Pair<FloatArray, Int> {
    val ch = channels.coerceAtLeast(1)
    val factor = (sampleRate / targetRate.coerceAtLeast(1)).coerceAtLeast(1)
    val frames = pcm.size / ch
    val n = frames / factor
    val out = FloatArray(n)
    val scale = 1f / (32768f * ch * factor)
    for (i in 0 until n) {
      var s = 0f
      val base = i * factor
      for (j in 0 until factor) {
        val idx = (base + j) * ch
        for (c in 0 until ch) s += pcm[idx + c]
      }
      out[i] = s * scale
    }
    return out to (sampleRate / factor)
  }

  private fun empty() = BeatAnalysis(0.0, emptyList(), emptyList(), 0f)

  private fun framesToMs(frames: List<Int>): List<Long> =
    frames.map { (it * 1000.0 / ENV_RATE).roundToInt().toLong() }

  /** Onset strength per 10 ms frame: positive change of log energy of the first-difference signal. */
  internal fun onsetEnvelope(mono: FloatArray, sampleRate: Int): FloatArray {
    val hop = (sampleRate / ENV_RATE).toInt().coerceAtLeast(1)
    val win = hop * 2
    val frames = (mono.size - win) / hop
    if (frames <= 2) return FloatArray(0)
    val energy = DoubleArray(frames)
    for (f in 0 until frames) {
      val start = f * hop
      var e = 0.0
      for (i in 1 until win) {
        val d = (mono[start + i] - mono[start + i - 1]).toDouble()
        e += d * d
      }
      energy[f] = e / win
    }
    val floor = energy.sorted()[(frames * 0.1).toInt().coerceIn(0, frames - 1)] + 1e-12
    val logE = DoubleArray(frames) { ln(1.0 + 1000.0 * energy[it] / (floor * 1000.0 + 1e-9)) }
    val flux = FloatArray(frames)
    for (f in 1 until frames) flux[f] = max(0.0, logE[f] - logE[f - 1]).toFloat()
    return flux
  }

  /** Local-maximum onsets above an adaptive threshold, at least 60 ms apart. */
  internal fun pickOnsets(env: FloatArray): List<Int> {
    val half = 50
    val minGap = 6
    val result = ArrayList<Int>()
    val prefix = DoubleArray(env.size + 1)
    for (i in env.indices) prefix[i + 1] = prefix[i] + env[i]
    for (i in 1 until env.size - 1) {
      val lo = max(0, i - half)
      val hi = min(env.size, i + half + 1)
      val mean = (prefix[hi] - prefix[lo]) / (hi - lo)
      val threshold = mean * 1.6 + 0.05
      if (env[i] > threshold && env[i] >= env[i - 1] && env[i] > env[i + 1]) {
        if (result.isEmpty() || i - result.last() >= minGap) result.add(i)
        else if (env[i] > env[result.last()]) result[result.size - 1] = i
      }
    }
    return result
  }

  /** Beat period in envelope frames, or null when the envelope has no periodic structure. */
  internal fun estimatePeriod(env: FloatArray, minBpm: Double, maxBpm: Double): Double? {
    val minLag = (60.0 * ENV_RATE / maxBpm).toInt().coerceAtLeast(2)
    val maxLag = (60.0 * ENV_RATE / minBpm).toInt().coerceAtMost(env.size / 3)
    if (maxLag <= minLag + 1) return null
    val mean = env.average().toFloat()
    val x = FloatArray(env.size) { env[it] - mean }
    var zero = 0.0
    for (v in x) zero += v.toDouble() * v
    if (zero <= 1e-9) return null

    val ac = DoubleArray(maxLag + 2)
    for (lag in minLag - 1..maxLag + 1) {
      var s = 0.0
      for (i in lag until x.size) s += x[i].toDouble() * x[i - lag]
      ac[lag] = s / zero
    }
    var bestLag = -1
    var bestScore = 0.0
    for (lag in minLag..maxLag) {
      val bpm = 60.0 * ENV_RATE / lag
      val prior = exp(-0.5 * (ln(bpm / 120.0) / 0.6).let { it * it })
      // A real beat also correlates at twice the lag; reward that to avoid picking a half-time pulse.
      val doubled = if (lag * 2 < ac.size) ac[lag * 2].coerceAtLeast(0.0) else 0.0
      val score = (ac[lag] + 0.5 * doubled) * prior
      if (ac[lag] > ac[lag - 1] && ac[lag] >= ac[lag + 1] && score > bestScore) {
        bestScore = score; bestLag = lag
      }
    }
    if (bestLag < 0 || ac[bestLag] < 0.04) return null
    val a = ac[bestLag - 1]; val b = ac[bestLag]; val c = ac[bestLag + 1]
    val denom = a - 2 * b + c
    val shift = if (abs(denom) > 1e-12) 0.5 * (a - c) / denom else 0.0
    return bestLag + shift.coerceIn(-0.5, 0.5)
  }

  /** Beat frames (envelope index) and a 0..1 confidence. */
  private fun trackGrid(env: FloatArray, onsets: List<Int>, period: Double): Pair<List<Int>, Float> {
    val smooth = FloatArray(env.size)
    for (i in env.indices) {
      var s = 0f
      for (d in -1..1) s += env[(i + d).coerceIn(0, env.size - 1)]
      smooth[i] = s / 3f
    }
    var bestPhase = 0.0
    var bestScore = -1.0
    val steps = period.roundToInt().coerceAtLeast(1) * 2
    for (p in 0 until steps) {
      val phase = p * period / steps
      var s = 0.0
      var t = phase
      while (t < env.size) {
        s += smooth[t.roundToInt().coerceIn(0, env.size - 1)]
        t += period
      }
      if (s > bestScore) { bestScore = s; bestPhase = phase }
    }

    val beats = ArrayList<Int>()
    var t = bestPhase
    val snapRadius = (period * 0.15).toInt().coerceAtLeast(1)
    var previous = -1
    var snapped = 0
    while (t < env.size) {
      var frame = t.roundToInt().coerceIn(0, env.size - 1)
      val near = onsets.filter { abs(it - frame) <= snapRadius }.maxByOrNull { env[it] }
      if (near != null) { frame = near; snapped++ }
      if (previous < 0 || frame - previous >= period * 0.5) {
        beats.add(frame)
        previous = frame
      }
      // Follow a drifting tempo: re-anchor the grid on the snapped beat, but only part way.
      t = t + period + (frame - t) * 0.3
    }

    val confidence = if (beats.isEmpty()) 0f else (snapped.toFloat() / beats.size).coerceIn(0f, 1f)
    return beats to confidence
  }
}
