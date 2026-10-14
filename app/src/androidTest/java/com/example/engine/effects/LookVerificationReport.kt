package com.example.engine.effects

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes the device-side verification evidence to a file that `adb pull` can fetch.
 *
 * The CI job logs of this repository cannot be read from the reviewer's workstation, so every
 * device test appends the numbers it measured (GPU deltas, shader compile logs, export frame
 * comparisons) here. The verification workflow pulls the file and posts it into the pull
 * request, which makes the measurements reviewable instead of a claim.
 */
internal object LookVerificationReport {

  const val FILE_NAME = "look-verification.txt"

  private val lock = Any()

  fun section(title: String) = append("") {
    it.appendLine()
    it.appendLine("=== $title ===")
  }

  fun line(text: String) = append(text) { it.appendLine(text) }

  /** The copy `adb pull` fetches. */
  fun file(context: Context): File =
    File(context.getExternalFilesDir(null) ?: context.filesDir, FILE_NAME)

  /**
   * The copy `adb shell run-as <applicationId> cat files/look-verification.txt` fetches. Some
   * emulator images refuse `adb pull` of `/sdcard/Android/data/<applicationId>/files`, and a
   * verification run must not lose its measurements to a shell permission quirk.
   */
  fun internalFile(context: Context): File = File(context.filesDir, FILE_NAME)

  private fun append(@Suppress("UNUSED_PARAMETER") tag: String, block: (StringBuilder) -> Unit) {
    synchronized(lock) {
      val ctx = AndroidTestContextHolder.context
      val sb = StringBuilder()
      block(sb)
      val text = sb.toString()
      println(text)
      if (ctx != null) {
        for (target in listOf(file(ctx), internalFile(ctx))) {
          try {
            target.parentFile?.mkdirs()
            target.appendText(text)
          } catch (_: Throwable) {
            // A report file must never fail a test.
          }
        }
      }
    }
  }

  fun stamp(): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}

/** The instrumentation target context, captured by the first test that needs it. */
internal object AndroidTestContextHolder {
  @Volatile
  var context: Context? = null
}
