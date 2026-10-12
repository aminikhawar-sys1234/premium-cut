package com.example.engine.preview

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Opt-in switch for the single-clock GPU preview compositor, persisted across launches. */
object PreviewCompositorSettings {
  private const val FILE = "preview_compositor_prefs"
  private const val KEY_ENABLED = "gpu_compositor_enabled"

  private val _enabled = MutableStateFlow(false)
  val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

  @Volatile private var loaded = false

  fun load(context: Context) {
    if (loaded) return
    loaded = true
    _enabled.value = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)
  }

  fun setEnabled(context: Context, enabled: Boolean) {
    _enabled.value = enabled
    context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
  }
}
