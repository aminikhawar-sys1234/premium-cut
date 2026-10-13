package com.example.util

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.ui.text.font.FontFamily
import java.io.File
import java.io.FileOutputStream

data class FontOption(
  val id: String,
  val name: String,
  val category: String = "English",
  val nativeSample: String = "",
  val isCustom: Boolean = false,
  val filePath: String? = null
)

object FontManager {
  private const val TAG = "FontManager"
  private const val FONTS_DIR = "custom_fonts"
  private const val TYPEFACE_CACHE_SIZE = 24

  val FONT_CATEGORIES = listOf(
    "Imported",
    "English",
    "Arabic",
    "Urdu",
    "Hindi",
    "Chinese"
  )

  /**
   * Real Android system families only. Names match the platform typeface family,
   * not invented foundry brands that secretly map to sans-serif.
   */
  val SYSTEM_FONTS = listOf(
    FontOption("sans-serif", "Sans", "English", "Aa"),
    FontOption("sans-serif-medium", "Sans Medium", "English", "Aa"),
    FontOption("sans-serif-condensed", "Condensed", "English", "Aa"),
    FontOption("sans-serif-black", "Sans Black", "English", "Aa"),
    FontOption("serif", "Serif", "English", "Aa"),
    FontOption("monospace", "Mono", "English", "Aa"),
    FontOption("cursive", "Cursive", "English", "Aa"),
    FontOption("sans-serif-arabic", "Arabic", "Arabic", "عرب"),
    FontOption("sans-serif-arabic", "Arabic", "Urdu", "اردو"),
    FontOption("sans-serif-devanagari", "Devanagari", "Hindi", "अ"),
    FontOption("sans-serif-cjk", "CJK Sans", "Chinese", "文"),
    FontOption("serif-cjk", "CJK Serif", "Chinese", "文")
  )

  private val SYSTEM_FAMILIES = SYSTEM_FONTS.map { it.id }.toSet() + setOf(
    "sans-serif-light",
    "sans-serif-thin",
    "sans-serif-condensed-light",
    "casual",
    "sans-serif-smallcaps"
  )

  val FONT_PICKER_MIME_TYPES = arrayOf(
    "font/ttf",
    "font/otf",
    "font/sfnt",
    "font/collection",
    "application/x-font-ttf",
    "application/x-font-otf",
    "application/font-sfnt",
    "application/octet-stream"
  )

  private val typefaceCache = object : LinkedHashMap<String, Typeface>(32, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Typeface>?) =
      size > TYPEFACE_CACHE_SIZE
  }

  private fun cachedTypeface(key: String, create: () -> Typeface): Typeface {
    synchronized(typefaceCache) {
      typefaceCache[key]?.let { return it }
      val created = create()
      typefaceCache[key] = created
      return created
    }
  }

  fun evictTypeface(pathOrFamily: String) {
    synchronized(typefaceCache) {
      val keys = typefaceCache.keys.filter { it.contains(pathOrFamily) }
      keys.forEach { typefaceCache.remove(it) }
    }
  }

  fun getAvailableFonts(context: Context): List<FontOption> {
    return importedFonts(context)
  }

  fun fontsForCategory(context: Context, category: String): List<FontOption> {
    return if (category.equals("Imported", ignoreCase = true) ||
      category.equals("My Fonts", ignoreCase = true) ||
      category.equals("Custom", ignoreCase = true)
    ) {
      importedFonts(context)
    } else {
      SYSTEM_FONTS.filter { it.category.equals(category, ignoreCase = true) }
    }
  }

  fun importedFonts(context: Context): List<FontOption> {
    val dir = File(context.filesDir, FONTS_DIR)
    if (!dir.exists() || !dir.isDirectory) return emptyList()
    val files = dir.listFiles { f -> isFontFileName(f.name) } ?: return emptyList()
    return files.sortedBy { it.name.lowercase() }.map { f ->
      FontOption(
        id = f.name,
        name = displayNameForFile(f.nameWithoutExtension),
        category = "Imported",
        nativeSample = "Aa",
        isCustom = true,
        filePath = f.absolutePath
      )
    }
  }

  fun importFont(context: Context, uri: Uri): FontOption? {
    try {
      var rawName = queryDisplayName(context, uri) ?: "imported_font_${System.currentTimeMillis()}.ttf"
      val sanitized = sanitizeFontFileName(rawName)
      if (!isFontFileName(sanitized)) {
        Log.w(TAG, "Rejected font import with unsupported name: $rawName")
        return null
      }

      val dir = File(context.filesDir, FONTS_DIR)
      if (!dir.exists() && !dir.mkdirs()) {
        Log.e(TAG, "Could not create fonts directory")
        return null
      }

      val destination = uniqueDestination(dir, sanitized)
      val copied = context.contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(destination).use { output ->
          input.copyTo(output)
        }
      } ?: 0L

      if (copied <= 0L || !destination.exists() || destination.length() <= 0L) {
        destination.delete()
        Log.e(TAG, "Font import produced an empty file")
        return null
      }

      runCatching {
        context.contentResolver.takePersistableUriPermission(
          uri,
          android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
      }

      evictTypeface(destination.absolutePath)
      return FontOption(
        id = destination.name,
        name = displayNameForFile(destination.nameWithoutExtension),
        category = "Imported",
        nativeSample = "Aa",
        isCustom = true,
        filePath = destination.absolutePath
      )
    } catch (e: Exception) {
      Log.e(TAG, "Failed to import font: ${e.message}", e)
      return null
    }
  }

  fun deleteImportedFont(context: Context, filePath: String): Boolean {
    val dir = File(context.filesDir, FONTS_DIR).canonicalFile
    val file = File(filePath).canonicalFile
    if (!file.path.startsWith(dir.path + File.separator)) return false
    evictTypeface(file.absolutePath)
    return file.delete()
  }

  fun loadTypeface(
    context: Context,
    fontFamily: String,
    customFontPath: String?,
    fontWeight: Int = 700,
    isItalic: Boolean = false
  ): Typeface {
    val style = when {
      fontWeight >= 700 && isItalic -> Typeface.BOLD_ITALIC
      fontWeight >= 700 -> Typeface.BOLD
      isItalic -> Typeface.ITALIC
      else -> Typeface.NORMAL
    }

    if (!customFontPath.isNullOrBlank()) {
      val file = File(customFontPath)
      if (file.exists()) {
        val custom = try {
          cachedTypeface("file:$customFontPath") { Typeface.createFromFile(file) }
        } catch (e: Exception) {
          Log.w(TAG, "Could not load custom font at $customFontPath: ${e.message}")
          null
        }
        if (custom != null) {
          return cachedTypeface("file:$customFontPath#$style") { Typeface.create(custom, style) }
        }
      }
    }

    val familyKey = "builtin:${fontFamily.lowercase()}#$style"
    return cachedTypeface(familyKey) { createBuiltInTypeface(fontFamily, style) }
  }

  private fun createBuiltInTypeface(fontFamily: String, style: Int): Typeface {
    val family = fontFamily.trim().ifBlank { "sans-serif" }
    if (family.equals("Default", ignoreCase = true)) {
      return Typeface.create(Typeface.SANS_SERIF, style)
    }
    if (family in SYSTEM_FAMILIES || SYSTEM_FAMILIES.any { it.equals(family, ignoreCase = true) }) {
      return Typeface.create(family, style)
    }

    val lower = family.lowercase()
    val baseTypeface = when {
      lower.contains("nastaliq") || lower.contains("nastaleeq") || lower.contains("jameel") ||
        lower.contains("alvi") || lower.contains("kasheeda") || lower.contains("naskh") ||
        lower.contains("scheherazade") || lower.contains("lateef") || lower.contains("gulzar") ||
        lower.contains("arabic") || lower.contains("urdu") ->
        Typeface.create("sans-serif-arabic", style)
      lower.contains("hindi") || lower.contains("devanagari") || lower.contains("yatra") ||
        lower.contains("akshar") ->
        Typeface.create("sans-serif-devanagari", style)
      lower.contains("chinese") || lower.contains("cjk") || lower.contains("songti") ||
        lower.contains("kaiti") ->
        Typeface.create(if (lower.contains("serif") || lower.contains("songti")) "serif-cjk" else "sans-serif-cjk", style)
      lower.contains("monospace") || lower.contains("code") -> Typeface.MONOSPACE
      lower.contains("serif") -> Typeface.SERIF
      lower.contains("cursive") || lower.contains("script") -> Typeface.create("cursive", style)
      else -> Typeface.SANS_SERIF
    }
    return Typeface.create(baseTypeface, style)
  }

  fun getComposeFontFamily(
    fontFamily: String,
    customFontPath: String?
  ): FontFamily {
    if (!customFontPath.isNullOrBlank()) {
      val file = File(customFontPath)
      if (file.exists()) {
        try {
          val tf = Typeface.createFromFile(file)
          return FontFamily(androidx.compose.ui.text.font.Typeface(tf))
        } catch (_: Exception) {
        }
      }
    }

    val lower = fontFamily.lowercase()
    return when {
      lower == "serif" || lower.contains("serif-cjk") -> FontFamily.Serif
      lower.contains("monospace") -> FontFamily.Monospace
      lower.contains("cursive") || lower.contains("script") -> FontFamily.Cursive
      else -> FontFamily.SansSerif
    }
  }

  fun sanitizeFontFileName(raw: String): String {
    val trimmed = raw.substringAfterLast('/').substringAfterLast('\\').trim()
    val cleaned = trimmed.replace(Regex("[^A-Za-z0-9._\\- ]"), "_").replace(Regex("\\s+"), "_")
    if (cleaned.isBlank()) return "imported_font_${System.currentTimeMillis()}.ttf"
    return if (isFontFileName(cleaned)) cleaned else "$cleaned.ttf"
  }

  fun isFontFileName(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext == "ttf" || ext == "otf" || ext == "ttc"
  }

  private fun displayNameForFile(nameWithoutExtension: String): String {
    return nameWithoutExtension.replace('_', ' ').trim().ifBlank { "Imported font" }
  }

  private fun uniqueDestination(dir: File, fileName: String): File {
    val base = File(dir, fileName)
    if (!base.exists()) return base
    val stem = fileName.substringBeforeLast('.')
    val ext = fileName.substringAfterLast('.', "ttf")
    var i = 2
    while (true) {
      val candidate = File(dir, "${stem}_$i.$ext")
      if (!candidate.exists()) return candidate
      i++
    }
  }

  private fun queryDisplayName(context: Context, uri: Uri): String? {
    return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
      if (!cursor.moveToFirst()) return@use null
      val colIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
      if (colIndex == -1) return@use null
      cursor.getString(colIndex)
    }
  }
}
