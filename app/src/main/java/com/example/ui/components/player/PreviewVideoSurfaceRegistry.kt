package com.example.ui.components.player

import android.os.Handler
import android.os.Looper
import android.view.TextureView
import androidx.media3.exoplayer.ExoPlayer
import java.lang.ref.WeakReference

/**
 * Keeps track of which [TextureView] currently owns an [ExoPlayer]'s video output.
 *
 * ExoPlayer renders into exactly one surface at a time, and the editor preview has more than
 * one surface for the same player: the inline preview and the fullscreen preview dialog.
 * The old code attached a view once (`tv.tag = player`) and never re-attached it, so closing
 * the fullscreen dialog left the player without any output: the inline preview stayed frozen
 * on its last frame (or black) while the playhead kept moving.
 *
 * Every attach/detach goes through this registry:
 *  - [bind] attaches a view only when it is not already the bound one, and never steals the
 *    output from a surface of higher priority (the inline preview recomposing must not take
 *    the surface away from an open fullscreen dialog),
 *  - [bindPrimary] additionally registers the inline view as the one that gets the output
 *    back once the dialog closes,
 *  - [release] detaches a view and, when it owned the output, hands it back to the primary
 *    view if that view is still attached to its window.
 *
 * The player is only ever touched from the main thread, which is where ExoPlayer requires its
 * surface calls to happen. Views are held weakly so a disposed editor cannot be leaked.
 */
object PreviewVideoSurfaceRegistry {

  /** Priority of the inline preview: it is always composed, but a dialog surface wins over it. */
  private const val PRIORITY_PRIMARY = 0

  /** Priority of a fullscreen / popup preview surface. */
  private const val PRIORITY_SECONDARY = 1

  private val lock = Any()
  private val mainHandler = Handler(Looper.getMainLooper())
  private val boundView = java.util.WeakHashMap<ExoPlayer, WeakReference<TextureView>>()
  private val boundPriority = java.util.WeakHashMap<ExoPlayer, Int>()
  private val primaryView = java.util.WeakHashMap<ExoPlayer, WeakReference<TextureView>>()
  private var lastPrimaryView: WeakReference<TextureView>? = null

  /**
   * Invoked on the main thread after a TextureView is (re)bound to a player.
   * Used to present the paused frame: ExoPlayer does not redraw after setVideoTextureView
   * while paused, which left reopened projects on a black preview.
   */
  @Volatile
  var onPlayerSurfaceBound: ((ExoPlayer) -> Unit)? = null

  private fun onMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block()
    else mainHandler.post(block)
  }

  /** Binds [view] and remembers it as the surface the player must fall back to. */
  fun bindPrimary(player: ExoPlayer, view: TextureView) {
    synchronized(lock) {
      primaryView[player] = WeakReference(view)
      lastPrimaryView = WeakReference(view)
    }
    bind(player, view, PRIORITY_PRIMARY)
  }

  /** Binds [view] as the player's video output (no-op when it is already bound). */
  fun bind(player: ExoPlayer, view: TextureView) {
    bind(player, view, PRIORITY_SECONDARY)
  }

  /** Compose friendly wrapper: does nothing while the preview has no player yet. */
  fun attach(player: ExoPlayer?, view: TextureView, primary: Boolean) {
    val exoPlayer = player ?: return
    if (primary) bindPrimary(exoPlayer, view) else bind(exoPlayer, view)
  }

  /** Compose friendly wrapper for [release]. */
  fun detach(player: ExoPlayer?, view: TextureView) {
    val exoPlayer = player ?: return
    release(exoPlayer, view)
  }

  private fun bind(player: ExoPlayer, view: TextureView, priority: Int) {
    onMain { bindOnMain(player, view, priority) }
  }

  private fun bindOnMain(player: ExoPlayer, view: TextureView, priority: Int) {
    synchronized(lock) {
      val owner = boundView[player]?.get()
      if (owner === view) {
        boundPriority[player] = priority
        return
      }
      if (owner != null) {
        // Never steal the output from a higher priority surface that is still on screen: a
        // fullscreen dialog keeps rendering even while the inline preview behind it recomposes.
        // A view that is no longer attached to its window never blocks a rebind.
        val ownerIsOnScreen = try { owner.isAvailable } catch (_: Throwable) { false }
        val currentPriority = boundPriority[player] ?: -1
        if (ownerIsOnScreen && currentPriority > priority) return
      }
      try {
        player.setVideoTextureView(view)
        boundView[player] = WeakReference(view)
        boundPriority[player] = priority
      } catch (_: Throwable) {
        // A released player throws here: ignore, its surface is gone anyway.
        return
      }
    }
    onPlayerSurfaceBound?.invoke(player)
  }

  /**
   * Re-attaches the currently bound TextureView. `setVideoEffects` rebuilds the video graph
   * and silently drops the output surface; calling this restores live frames without creating
   * a new TextureView.
   */
  /** Drops every binding for [player] so a released instance cannot keep a TextureView. */
  fun forget(player: ExoPlayer) {
    onMain {
      synchronized(lock) {
        boundView.remove(player)
        boundPriority.remove(player)
        primaryView.remove(player)
      }
      try {
        player.clearVideoSurface()
      } catch (_: Throwable) {
      }
    }
  }

  /** Attaches the last inline TextureView to a newly created ExoPlayer. */
  fun attachRememberedPrimary(player: ExoPlayer) {
    val view = synchronized(lock) { lastPrimaryView?.get() } ?: return
    if (!view.isAvailable) return
    bindPrimary(player, view)
  }

  fun rebindCurrent(player: ExoPlayer, afterMs: Long = 0L) {
    val bind: () -> Unit = bind@{
      val view = synchronized(lock) { boundView[player]?.get() } ?: return@bind
      if (!view.isAvailable) return@bind
      try {
        player.setVideoTextureView(view)
      } catch (t: Throwable) {
        android.util.Log.e("PreviewVideoSurfaceRegistry", "Failed to rebind preview TextureView", t)
      }
    }
    if (afterMs <= 0L) {
      onMain(bind)
    } else {
      mainHandler.postDelayed({ onMain(bind) }, afterMs)
    }
  }

  /**
   * Detaches [view]. When [view] owned the player's output (a fullscreen dialog closing), the
   * registered primary view gets the output back so the inline preview keeps showing live frames.
   */
  fun release(player: ExoPlayer, view: TextureView) {
    onMain { releaseOnMain(player, view) }
  }

  private fun releaseOnMain(player: ExoPlayer, view: TextureView) {
    synchronized(lock) {
      val wasPrimary = primaryView[player]?.get() === view
      if (wasPrimary) primaryView.remove(player)

      if (boundView[player]?.get() !== view) return
      try {
        player.clearVideoTextureView(view)
      } catch (_: Throwable) {
      }
      boundView.remove(player)
      boundPriority.remove(player)

      val fallback = primaryView[player]?.get()
      if (fallback != null && fallback !== view && fallback.isAvailable) {
        try {
          player.setVideoTextureView(fallback)
          boundView[player] = WeakReference(fallback)
          boundPriority[player] = PRIORITY_PRIMARY
        } catch (_: Throwable) {
        }
      }
    }
  }
}
