# Preview playback player — audit & fixes

Scope: the editor preview player, i.e. everything between the timeline CTI and the video
surface the user watches while editing:

```
TimelineEngine (CTI/play state)
   └─ VideoPlaybackEngine            (facade: main player, overlay players, audio players, trim preview)
        └─ CustomVideoEngineController
             ├─ PlaybackController        (single owner of player commands + TimelineState)
             │    └─ PlaybackManager      (the one ExoPlayer instance)
             └─ TimelineSyncManager       (MasterPlaybackClock + clip transitions)
UI surfaces: EditorScreen.VideoPreviewSurface (inline + fullscreen dialog), InteractiveTransformOverlay (PIP), VideoTrimmingToolPanel
```

The clock is the CTI (VSYNC driven `MasterPlaybackClock`); ExoPlayer is a slave renderer and
`PlaybackSyncPolicy` only allows one corrective seek per drift window. That design is kept.

## Fixed in this change

### 1. Track mute / solo was ignored by the player (preview ≠ export)

* **Symptom:** the timeline's "Mute All Video", per-track mute and solo buttons changed the
  exported file but not the live preview.
* **Cause:** the player computed its gain from `clip.isMuted`/`clip.volume` only, while the
  export mix (`AudioExportProcessor`, `TimelineEvaluator`) also applies the `TrackSettings`
  mute/solo gate for `MAIN_VIDEO`, `OVERLAY` and `AUDIO`.
* **Fix:** new `engine/controller/PreviewMixPolicy.kt` is the single rule both paths now
  share (`isTrackVisible`, `isTrackAudible`, `clipGain`, `audioClipGain`, `mixSignature`).
  `PlaybackController`, `CustomVideoEngineController` and `VideoPlaybackEngine` use it.
* **While playing:** `PlaybackController.updateTimeline` compares `mixSignature`; when only the mix
  changed it re-applies the gain (`applyMainLaneGain()`) instead of re-syncing the player, so
  toggling mute during playback takes effect immediately and without snapping to a sync frame.
* **Hide vs. mute:** hiding a track only switches the picture off, it does not silence it — the
  rule `AudioExportProcessor.collectAudioTracks()` uses for the exported mix. Silence always comes
  from mute/solo, for all three lanes (main video, overlay, audio).

### 2. Timeline toolbar "hide track" did not hide the preview picture

* **Fix:** `VideoPreviewSurface` skips the player texture view when `MAIN_VIDEO` is hidden and
  drops overlay layers when `OVERLAY` is hidden (canvas background shows through), while the
  players keep running for audio.

### 3. Trimming tool left the player stuck in the trimmed window

* **Symptom:** after closing the trim dialog, the preview kept playing only the trim range; the
  rest of the clip could not be scrubbed until another clip was selected.
* **Cause:** `loadTrimPreview` installs a **clipped** `MediaItem` into the shared player and sets
  `PlaybackController.currentLoadedUri`, but `exitTrimPreview()` only reset the repeat mode.
  The next `ensureClipLoaded()` saw the same uri and returned early, so the clipped item stayed.
* **Fix:** `PlaybackController.endTrimPreview()` drops the item and resets the uri/clip
  bookkeeping; `VideoPlaybackEngine.exitTrimPreview()` calls it before re-binding the timeline
  clip. `PlaybackManager.clearMediaItems()` clears the manager's own uri cache.

### 4. Trim preview play/pause hijacked the timeline seek

* **Symptom:** pressing play/pause (or frame-step) in the trim dialog jumped the picture out of
  the trim range.
* **Cause:** `pauseTrimPreview()`/`playTrimPreview()` called the *timeline* transport
  (`pauseTimeline`/`playTimeline`), which performs an exact seek to the timeline CTI. For a
  clipped item, that source position falls outside the clip window and the decoder clamps it to
  the window edge.
* **Fix:** new player-level transport `PlaybackController.playMediaPreview()` /
  `pauseMediaPreview()`; the trim transport and `toggleTrimPlayPause` use it, and
  `isTrimPreviewActive` (`trimPreviewActive`) blocks `updateTimeline`,
  `updateTimelinePosition`, `handleClipTransition` and `syncExoPlayerToTimelineState` so the
  master clock cannot re-bind the player while the tool owns it.

### 5. Secondary players stayed dead after reaching the end of a source

* **Symptom:** background music / voice-over / PIP audio was silent on the second playback pass
  (or after seeking back into the clip) — the first play was fine.
* **Cause:** in `STATE_ENDED` ExoPlayer keeps `playWhenReady = true` and `isPlaying = false`, so
  `!isPlayWhenReady` was false and `needsDriftReseek` refused to correct a player that is not
  playing. Nothing ever seeked it back.
* **Fix:** `needsRestartFromEnd(player)` (`STATE_ENDED`) forces a `seekTo` + `playWhenReady = true`
  for overlay and audio players. Also fixed the cooldown sentinel: an unset correction time was
  `Long.MIN_VALUE`, and `elapsedRealtime() - Long.MIN_VALUE` overflows to a negative "since",
  which silently defeated the cooldown check.

### 6. Fullscreen preview stole the surface permanently

* **Symptom:** after closing the fullscreen preview the inline preview stayed frozen on its last
  frame (or black) while the playhead kept moving.
* **Cause:** ExoPlayer renders into one surface. The inline view attached once (`tv.tag = player`)
  and the `update` lambda never re-attached; the dialog's `clearVideoTextureView` (or the
  destroyed `SurfaceTexture`) left the player without any output.
* **Fix:** new `ui/components/player/PreviewVideoSurfaceRegistry.kt` owns attach/detach for every
  preview surface of a player: the inline preview registers as the *primary* surface, dialog /
  trim surfaces register as higher priority (they may take the surface, the inline view may not
  steal it back), and `release()` hands the output back to the primary view when it is still
  attached. Used by `VideoPreviewSurface` (inline + fullscreen), `InteractiveTransformOverlay`
  and `VideoTrimmingToolPanel`.

### 7. A freshly added PIP video kept showing the placeholder

* **Symptom:** a newly added overlay video only appeared after an unrelated recomposition.
* **Cause:** `getOverlayPlayer(id)` is a plain map lookup — invisible to Compose — and the
  `AndroidView` branch was chosen during a composition that ran before the player existed.
* **Fix:** `VideoPlaybackEngine.overlayPlayerIds: StateFlow<Set<String>>` is published whenever
  the overlay player set changes; `EditorScreen` collects it and both `VideoPreviewSurface` and
  `InteractiveTransformOverlay` use it to pick the video layer.

### 8. Playback kept running in the background / over other apps

* **Fix:** `PlaybackManager` now builds ExoPlayer with
  `setAudioAttributes(AudioAttributes.DEFAULT, handleAudioFocus = true)` and
  `setHandleAudioBecomingNoisy(true)`; `VideoPlaybackEngine` listens to
  `onPlayWhenReadyChanged(..., PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS / _AUDIO_BECOMING_NOISY)`
  and stops the master transport too (new `onPlaybackInterrupted` hook → `timelineEngine.pause()`),
  so the clock never keeps counting while the player is paused by the system.
  `MainActivity` pauses on `ON_STOP` via `StudioViewModel.onAppBackgrounded()`.
  (Constants/APIs verified against media3 1.5.1 sources.)

### 9. Smaller fixes

* User seek no longer issues the "exact seek on pause" first: `pauseTimeline(resyncFrame = false)`
  removes one decoder flush per seek (the seek that follows supersedes it).
* `PlaybackManager.loadMedia` keys its cache on the *normalized* uri, so `file:///x` and `/x`
  no longer reload the same file twice.
* A successful load (`STATE_READY`) clears `VideoPlaybackEngine.playerError`, instead of leaving
  a stale error on screen for the session.
* `applyVideoFilter(colorMatrix)` marked the ignored argument explicitly and documented why the
  look is rebuilt from the clip (it previously looked like a broken no-op).
* Removed the unused `PlayerView` import in `EditorScreen`.

## Not changed (documented findings)

1. **Master clock is wall-clock, not PTS.** `PlaybackSyncPolicy.shouldRebaseMasterClock()` always
   returns false, so `TimelineSyncManager`'s audio-clock provider is dead code. Video timing is
   "trust the clock, correct the decoder" — on a slow device the preview pays one exact seek per
   drift window instead of following the decoder. Working as designed; changing it is a redesign,
   not a fix.
2. **Double position callback per tick.** `TimelineSyncManager.publishPosition` calls
   `playbackController.updateTimelinePosition` (which fires `onTimelinePositionChanged`) and then
   its own `onTimelinePositionUpdated` fires the same callback again. Harmless duplication, same
   value; left alone to keep the diff small.
3. **Gap preview keeps the last clip's frame.** `VideoPreviewSurface` falls back to
   `videoClips.lastOrNull()` when the CTI is in a gap, so a gap shows a stale frame instead of the
   canvas colour. UI-level behaviour, unrelated to player state.
4. **Dead/unused playback code:** `PlaybackStateMachine`, `SurfaceManager` (nothing calls
   `attachTextureView`/`attachSurfaceView` — the UI attaches its own texture views),
   `ProductionVideoEditorPlayer` / `ProductionLifecycleVideoPlayer` (unreferenced composables with
   their own ExoPlayer + `PlayerView`), and `ProxyMediaEngine.getProxyUri` always returns the
   original uri because nothing ever requests proxy generation. Candidates for deletion, not for
   this fix.
5. **`MediaRelinkManager.isRealPlayableMedia` rejects `android.resource://`**, so a clip stored as
   a raw resource would never reach the player. No current importer produces such uris.
6. **`PlaybackManager.setMuted(false)`** forces volume to 1f (clobbering per-clip volume); no
   caller uses it today.
7. **Export side inconsistency (out of scope):** `TimelineEvaluator.evaluate` builds its
   "active audio clips" list from `isAudioHidden`, i.e. it treats a hidden audio *track* as
   silence, while its own comment ("respecting track mute/solo"), `AudioExportProcessor` and the
   preview all gate on mute/solo only. Two different export paths therefore disagree about a
   hidden audio track; left unchanged because it can affect rendered output and needs its own
   round of regression testing.

## Tests

`app/src/test/java/com/example/engine/controller/PreviewMixPolicyTest.kt` pins the new mix rule
(track mute/solo gating, clip mute, gain clamp, hidden ≠ silent, audio-lane solo, mix signature).
Existing playback tests (`PlaybackControllerTimelineStateTest`, `PlaybackSyncPolicyTest`,
`Phase8StabilityGuardsTest`) are unchanged and must keep passing.
