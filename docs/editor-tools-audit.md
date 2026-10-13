# Editor tools — audit & fixes

Scope: the editor tool registry (`EditorToolsRepository`, `EditorToolItem`), the bottom toolbar,
the "More" dialog and the routing of a tool tap to its panel in `EditorScreen`.

```
Firestore editor_tools / defaults ─ EditorToolsRepository.withRequiredTools
   └─ StudioViewModel.editorTools
        └─ EditorToolItem.navigationTools()   (visible, active, de-duplicated, ordered)
             ├─ EditorBottomToolbar / MoreToolsDialog
             └─ handleToolClick → EditorToolItem.resolveRoute() → EditorToolRoute
                  ├─ Split / Export / EffectsMenu / TextMenu / EditMenu
                  └─ Panel(EditorToolbarTab) → panel switch in EditorScreen
```

## Findings and fixes

1. **Remote tools with a string `order` were silently dropped.** `getLong("order")` throws on a
   string field and the surrounding `try` discarded the whole tool. The order is now read as a
   `Number` or numeric `String`.
2. **Tools without an action key never reached the toolbar.** The Firestore de-duplication required a
   non-blank key. Blank-key tools are now kept (de-duplicated by name; they route by category).
3. **A disabled duplicate could shadow a live tool.** De-duplication now prefers active entries.
4. **Click routing was name-based.** `name.contains("Text")` (and name/id checks for Effects and
   Overlay) ran before the action key, so e.g. a `TOOL_FILTERS` tool called "Text Filters" opened the
   Text menu. Routing is now `EditorToolItem.resolveRoute()`: action key first, then id/name, then
   category. Panel taps also close any open sub-menu.
5. **The Smart tools panel had no tool mapping**, so it could only be opened from Edit Tools.
   `TOOL_SMART` / `TOOL_SMART_TOOLS` now map to it, so a remote tool list can place it on the toolbar.
6. **Missing icons.** `TOOL_BACKGROUND`, `TOOL_ADVANCED_ANIMATIONS`, `TOOL_RETOUCH`,
   `TOOL_VIDEO_QUALITY`, `TOOL_CANVAS`, `TOOL_ADJUST` and `TOOL_SMART` fell through to a generic icon.
7. **Three copies of the hide/de-duplicate rules** (repository, toolbar, More dialog) had drifted
   apart (different spellings, matching case-sensitivity). They are now `isHiddenFromNavigation` and
   `navigationTools()`; the toolbar is also ordered before de-duplicating, so the lowest-order tool wins.

## Verification

- `EditorToolsReachabilityTest` covers tab reachability (every `EditorToolbarTab` has an action key),
  icons, routing precedence, fallbacks, hidden tools, de-duplication and default toolbar content.
- Every `EditorToolbarTab` has a branch in `EditorScreen`'s panel switch (checked by hand).
- Full run: `./gradlew :app:testDebugUnitTest` — 1421 tests, 0 failures.
