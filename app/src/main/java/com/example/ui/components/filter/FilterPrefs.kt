package com.example.ui.components.filter

import android.content.Context

/**
 * Favourite + recently-used look ids for the Filters panel, kept in SharedPreferences.
 * A look id is either a FilterType name ("CINEMATIC") or a LUT look ("lut:teal_orange").
 * The list logic is pure (no Android) so it is unit-testable.
 */
object FilterPrefs {
  private const val FILE = "filter_tools_prefs"
  private const val KEY_FAVS = "favourites"
  private const val KEY_RECENT = "recent"
  const val MAX_RECENT = 8

  fun encode(ids: List<String>): String = ids.joinToString(",")
  fun decode(raw: String?): List<String> = raw?.split(',')?.filter { it.isNotBlank() } ?: emptyList()

  /** Adds [id] if absent, removes it if present. */
  fun toggled(list: List<String>, id: String): List<String> =
    if (id in list) list - id else list + id

  /** Moves [id] to the front, de-duplicated and capped to [max]. */
  fun pushedRecent(list: List<String>, id: String, max: Int = MAX_RECENT): List<String> =
    (listOf(id) + list.filter { it != id }).take(max)

  fun favourites(context: Context): List<String> =
    decode(prefs(context).getString(KEY_FAVS, null))

  fun recent(context: Context): List<String> =
    decode(prefs(context).getString(KEY_RECENT, null))

  fun saveFavourites(context: Context, ids: List<String>) {
    prefs(context).edit().putString(KEY_FAVS, encode(ids)).apply()
  }

  fun saveRecent(context: Context, ids: List<String>) {
    prefs(context).edit().putString(KEY_RECENT, encode(ids)).apply()
  }

  private fun prefs(context: Context) =
    context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
