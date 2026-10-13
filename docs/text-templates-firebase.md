# Text Templates — Firebase Integration

The editor's **Add Text → Templates** tab and **Text Tools → Text Templates** panel show one
merged catalog: packaged templates, installed plugins, and everything published on Firebase.
Cloud templates are pinned to the **top** of the grid with a small cloud badge, so uploads are
visible as soon as they download.

## When the download happens

1. **App launch** — `StudioApplication` calls `TextTemplateCatalog.ensureLoaded(...)`, which
   starts the Firestore live sync and the Storage catalog download in the background.
2. **Add Text / Text Templates panel open** — `rememberInstalledTextTemplates()` calls
   `ensureLoaded(...)` + `refreshRemoteTemplates(...)`: a fresh one-shot download starts
   immediately, so newly uploaded templates load without restarting the app.
3. **Live updates** — snapshot listeners on the Firestore collections keep the panel updated
   while it is open. Upload, edit, or delete a document and the grid follows.
4. **Status + Retry** — both panels show a compact sync bar: *Downloading templates from
   Firebase…* while loading, *N Firebase templates loaded* on success, and an error row with a
   **Retry** button when Firebase cannot be reached. Built-in templates keep working offline.

Parsed cloud templates live in `TextAssetRegistry` next to the local ones; tapping one in
Add Text applies its full style (font, color, gradient, stroke, shadow, glow, background,
2D/3D, animation) to the current text clip, and tapping one in the Text Templates panel
inserts it as a new text clip on the timeline.

## Source 1 — Firestore (primary)

Collection: **`text_templates`** (the alias **`textTemplates`** is synced too).
Each document is one template; the **document ID becomes the template ID**. Documents are
publicly readable (`firestore.rules`); writes stay disabled for the app.

Upload any of these shapes — they all show up in the panels:

### Flat fields

```json
{
  "name": "Neon Urdu",
  "text": "Aa",
  "category": "Urdu",
  "fontFamily": "Noto Nastaliq Urdu",
  "fontSizeSp": 48,
  "fontWeight": 900,
  "textColor": "#FFEFFFFF",
  "hasGlow": true,
  "glowColor": "#FF00E5FF",
  "glowRadius": 14,
  "animationIn": "neon",
  "is3D": false,
  "previewThumbnailUrl": "https://…/neon.jpg"
}
```

### Style-only (no sample text)

`text` is optional for cloud templates. Omit it and the card previews with `Aa`:

```json
{
  "name": "Gold 3D",
  "category": "3D",
  "fontFamily": "serif",
  "fontSize": 44,
  "color": "#FFF7E08A",
  "is3D": true,
  "depth3D": 14,
  "material3D": "metallic",
  "animation": "cinematic"
}
```

(A document with *only* a name and no style fields at all is still ignored.)

### Nested shapes

Nested `style` / `textStyle` / `properties` maps, a `clip` or `templateClip` object, and
aliases (`title`, `content`, `defaultText`, `sampleText`, `font`, `fontSize`, `color`,
`animation`, `thumbnailUrl`, …) are all understood:

```json
{
  "title": "Remote Quote",
  "content": "Make it memorable",
  "style": { "fontFamily": "serif", "fontSize": 38, "color": "#FF112233", "animation": "Fade" }
}
```

### Catalog document (many templates in one doc)

```json
{ "templates": [ { "id": "bulk.one", "name": "Bulk One", "text": "One" }, { "name": "Bulk Two", "content": "Two" } ] }
```

### JSON-string payload

```json
{ "templateJson": "{\"name\":\"Payload Style\",\"text\":\"Hello\",\"textColor\":\"#FFFF0000\"}" }
```

### Optional fields

| Field | Effect |
| --- | --- |
| `sortOrder` / `order` / `priority` / `displayOrder` (number) | Cloud templates are ordered by it in the grid. |
| `previewThumbnailUrl` / `thumbnailUrl` | Downloaded with Coil and shown on the card over the live style preview. |
| `enabled`, `published`, `visible`, `isActive` = `false`, or `status` = `draft`/`archived`/`hidden` | Template is **hidden** from the app until published. |

Colors accept `#RRGGBB` / `#AARRGGBB` strings or raw ARGB numbers; numbers accept numeric
strings too (`"fontSizeSp": "48"`).

## Source 2 — Firebase Storage catalog JSON

Upload a JSON **array** — the exact format of `app/src/main/assets/text/templates/catalog.json` —
to any of these paths and it is downloaded and merged on app launch / panel open:

- `text/templates/catalog.json`
- `text_templates/catalog.json`
- `text-templates/catalog.json`
- `text/templates.json`

`storage.rules` already allows public reads under those folders.

## Rules deploy

```bash
firebase deploy --only firestore:rules,storage:rules
```

## Troubleshooting

- **Template uploaded but not visible:** check it is in `text_templates` (or `textTemplates`),
  is not `enabled: false` / `status: draft`, and has a `name` (or `title`) plus either sample
  text or at least one style field. Then reopen the panel — the sync bar confirms the download,
  and Retry forces a fresh one.
- **Rules not deployed:** the listener fails and the panel shows the error row; deploy the rules
  above and tap Retry.
