package com.example.engine.export

/**
 * Annex-B (start-code delimited) H.264 / HEVC helpers for encoder output buffers.
 *
 * Several hardware encoders deliver the very first IDR picture in ONE output buffer flagged
 * `BUFFER_FLAG_CODEC_CONFIG | BUFFER_FLAG_KEY_FRAME` (SPS/PPS[/VPS] followed by the IDR slices), and some
 * report an output format without csd-0/csd-1 and only emit the parameter sets as a CODEC_CONFIG buffer.
 * Dropping such a buffer as "config" removes the only decodable start point of the file, so the first
 * sample becomes a P-frame and the first video frame cannot be decoded.
 *
 * Pure Kotlin (no Android types) so it can be unit-tested on the JVM.
 */
object ExportVideoBitstream {

  /** One NAL unit inside [data]: [start] is the start-code offset, [payload] the first header byte. */
  data class Nal(val start: Int, val payload: Int, val end: Int, val type: Int)

  /** Leading parameter sets (start codes included) and the picture data that follows them. */
  class Split(val parameterSets: ByteArray, val picture: ByteArray)

  class AvcCsd(val sps: ByteArray, val pps: ByteArray)

  fun isHevc(mime: String?): Boolean = mime.equals("video/hevc", ignoreCase = true)

  fun nalType(header: Int, hevc: Boolean): Int =
    if (hevc) (header shr 1) and 0x3F else header and 0x1F

  fun isParameterSet(type: Int, hevc: Boolean): Boolean =
    if (hevc) type == 32 || type == 33 || type == 34 else type == 7 || type == 8

  /** IDR (AVC 5) or IRAP (HEVC 16..23). */
  fun isRandomAccess(type: Int, hevc: Boolean): Boolean =
    if (hevc) type in 16..23 else type == 5

  /** Splits [data] into NAL units at 3- or 4-byte start codes. Empty when there is no start code at all. */
  fun nals(data: ByteArray, hevc: Boolean): List<Nal> {
    val starts = ArrayList<IntArray>() // [startCodeOffset, payloadOffset]
    var i = 0
    while (i + 2 < data.size) {
      if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
        if (data[i + 2].toInt() == 1) {
          val sc = if (i > 0 && data[i - 1].toInt() == 0) i - 1 else i
          starts += intArrayOf(sc, i + 3)
          i += 3
          continue
        }
      }
      i++
    }
    val out = ArrayList<Nal>(starts.size)
    for ((k, s) in starts.withIndex()) {
      val end = if (k + 1 < starts.size) starts[k + 1][0] else data.size
      if (s[1] >= end) continue
      out += Nal(s[0], s[1], end, nalType(data[s[1]].toInt() and 0xFF, hevc))
    }
    return out
  }

  fun containsRandomAccess(data: ByteArray, hevc: Boolean): Boolean =
    nals(data, hevc).any { isRandomAccess(it.type, hevc) }

  /**
   * Separates the leading parameter-set NAL units from the picture NAL units. When [data] has no start
   * codes it is returned unchanged as picture data.
   */
  fun splitParameterSets(data: ByteArray, hevc: Boolean): Split {
    val units = nals(data, hevc)
    if (units.isEmpty()) return Split(ByteArray(0), data)
    val firstPicture = units.firstOrNull { !isParameterSet(it.type, hevc) }
    val paramEnd = firstPicture?.start ?: data.size
    val params = units.filter { it.end <= paramEnd && isParameterSet(it.type, hevc) }
    val paramBytes = concat(data, params)
    val picture = if (firstPicture == null) ByteArray(0) else data.copyOfRange(firstPicture.start, data.size)
    return Split(paramBytes, picture)
  }

  /** AVC: csd-0 = SPS units, csd-1 = PPS units (each with its start code). Null if either is missing. */
  fun avcCsd(parameterSets: ByteArray): AvcCsd? {
    val units = nals(parameterSets, hevc = false)
    val sps = units.filter { it.type == 7 }
    val pps = units.filter { it.type == 8 }
    if (sps.isEmpty() || pps.isEmpty()) return null
    return AvcCsd(concat(parameterSets, sps), concat(parameterSets, pps))
  }

  /** HEVC: csd-0 = VPS + SPS + PPS. Null unless all three are present. */
  fun hevcCsd(parameterSets: ByteArray): ByteArray? {
    val units = nals(parameterSets, hevc = true)
    val types = units.map { it.type }.toSet()
    if (!types.containsAll(listOf(32, 33, 34))) return null
    return concat(parameterSets, units.filter { isParameterSet(it.type, hevc = true) })
  }

  private fun concat(data: ByteArray, units: List<Nal>): ByteArray {
    val total = units.sumOf { it.end - it.start }
    val out = ByteArray(total)
    var pos = 0
    for (u in units) {
      System.arraycopy(data, u.start, out, pos, u.end - u.start)
      pos += u.end - u.start
    }
    return out
  }
}
