# Transition tools — audit & fixes

Scope: the Transitions sheet, the live preview player, and the export compositor
(shader blend when both clips are decoded, single-texture fallback otherwise).

```
TransitionsPanel (cut picker, catalog, duration)
   └─ TimelineEngine.setTransition / setTransitionDuration
        ├─ VideoPreviewSurface  → TransitionPreviewMotion (the visible clip)
        └─ GpuCompositionRenderer
             ├─ ShaderTransitionCompositor  (clip A + clip B, export)
             └─ TransitionPreviewMotion     (fallback when a side is missing)
```

## Bugs fixed

1. **Fade and Dissolve were the same transition.**  
   `TransitionAppBridge` sent both to the cross-dissolve shader, so Fade never dipped
   through black. Fade now uses the fade shader. Dissolve keeps a short softness ramp.

2. **“Wipe Right” applied a glitch wipe.**  
   The card was bound to `GLITCH_WIPE`. Wipe Right is now a real wipe: the same wipe
   shader with direction `(-1, 0)`. Glitch Wipe is labeled and filed under Effect.
   Radial wipe, which already had a shader, is in the Wipe category.

3. **The second half of a transition played the outgoing move on the incoming clip.**  
   Preview and the single-texture fallback faded, slid, or wiped using progress only,
   so after the cut the new clip kept sliding off or fading out. Motion is now chosen
   from which side is on screen (`TransitionPreviewMotion`). The live preview uses that
   pose, including a white flash and a horizontal clip for wipes.

4. **A failed shader render was reported as success.**  
   The compositor returned true after `TransitionResult.Err`, so export kept a passthrough
   of clip B instead of the fallback. It now returns false and the fallback runs.
   Wipe direction and dissolve softness are passed into the shader instance.

5. **The sheet could not choose a cut, and the grid had no height.**  
   Every apply landed on cut 0 unless a previous apply had moved the index. The sheet
   opens on the cut nearest the playhead and steps between cuts. It is given the same
   45% editor height as Filters so the 4-column grid can scroll. Remove clears the
   current cut.

6. **Duration could swallow a short clip and flooded undo.**  
   Duration is clamped to the shorter neighbouring clip, and to 2 seconds. A drag of
   the duration slider records one undo step per burst.

## Preview and export

| Transition | Live preview | Export, both frames | Export, one frame missing |
|------------|--------------|---------------------|---------------------------|
| Dissolve / Fade / slides / zoom / spin / flash / glitch | `TransitionPreviewMotion` on the visible clip | Matching builtin shader, both clips | Same pose as preview |
| Wipe left / right | Horizontal clip of the visible side | Wipe shader, direction sets the edge | Same clip |
| Radial | Crossfade of the visible side | Radial wipe shader | Crossfade |

The live player has one decoder, so it cannot show both pictures at once. Export does,
through `ShaderTransitionCompositor`, whenever both sources decode.

## Tests

`TransitionToolsAuditTest` covers catalog identity (Wipe Right is not Glitch Wipe),
fade-to-black versus dissolve, incoming slide direction, wipe sides, duration clamp,
and coalesced undo. `BuiltinTransitionDistinctTest` checks Fade, Radial, and wipe direction.
