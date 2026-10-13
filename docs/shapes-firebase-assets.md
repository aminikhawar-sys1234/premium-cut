# Shapes Panel — Firebase Asset Library

Bottom-navigation **Shapes/Elements** panel is backed by a Firebase asset
library: **11 categories × 20 original assets = 220 assets**, all drawn
procedurally by `tools/assets/generate_assets.py` (no third-party artwork).

## Data model (exactly as specified)

```
categories/{shapes|graphics|frames|tables|3d|icons|animals|birds|characters|vehicles|charts}
  name, description, order, assetCount, status

assets/{assetId}
  name, categoryId, format ("svg"), storagePath, thumbnailPath,
  width (512), height (512), defaultProperties, version (1),
  status (published|draft|archived), createdAt, updatedAt   (server timestamps on upload)
```

Only `status == "published"` assets are queried by the app.

## Files

| Path | What |
| --- | --- |
| `firebase-assets/files/{category}/{id}.svg` | 220 full assets (512×512 transparent SVG) |
| `firebase-assets/thumbnails/{category}/{id}.png` | 220 PNG thumbnails (256px, rendered via resvg) |
| `firebase-assets/firestore-seed.json` | categories + assets seed (source of truth) |
| `app/src/main/assets/elements/catalog.json` | Identical mirror bundled in the APK (offline fallback) |
| `tools/assets/generate_assets.py` | Asset generator (`python3 tools/assets/generate_assets.py`, needs `pip install resvg-py`) |
| `tools/assets/validate_seed.py` | Seed/file validator — asserts 11×20, required fields, valid SVG/PNG |
| `tools/assets/upload_firebase.js` | Admin upload + publish (Storage first, Firestore doc second — a failed upload never creates a broken published record; re-runs are idempotent) |
| `app/.../data/firebase/FirebaseElementsRepository.kt` | Loads categories + published assets, resolves thumbnail download URLs, StateFlow + sync state (loading/loaded/offline-cache/failed+retry) |
| `app/.../ui/components/elements/ElementsToolPanel.kt` | Firebase assets merged into the existing panel (remote first, ☁ thumbnails via Coil, counts, search, sync bar + Retry); local catalog is only a fallback |
| `firestore.rules` / `storage.rules` | `categories`, `assets`, `elements/**`: public read, **client write denied** — only admin tooling (service account) can upload/publish |

Formats per category: Shapes/Graphics/Frames/Icons/Animals/Birds/Characters/
Vehicles = transparent SVG sticker/overlay (move/scale/rotate/opacity via the
existing sticker-clip pipeline). Tables & Charts carry an **editable model**
in `defaultProperties` (`modelType`, rows/cols or chartType/labels/values/
colors). 3D assets are isometric **SVG renders** (`renderMode: svg_render`) —
honestly *not* GLB: Firebase only stores/delivers files, and this app has no
GLB renderer, so they behave as overlay renders rather than fake 3D.

Selection inserts a real editable clip at the playhead through the existing
`TimelineEngine.addElementClip(...)` with the **Firebase asset ID** and its
`storagePath` in `renderData` — timeline, undo/redo, save, playback and export
paths are unchanged.

## Upload to Firebase — recommended: GitHub Actions (one click)

The upload runs **outside** this dev sandbox (which has no route to Google
APIs), via `.github/workflows/upload-elements-assets.yml`:

1. Repo **Settings → Secrets and variables → Actions → New repository secret**
   - Name: `FIREBASE_SERVICE_ACCOUNT`
   - Value: poori service-account JSON (Firebase Console → Project settings →
     Service accounts → Generate new private key). Key na git mein jati hai,
     na chat mein — sirf GitHub Secret mein rehti hai.
2. **Actions** tab → **Upload Elements Assets to Firebase** → **Run workflow**.
3. Job pehle seed validate karta hai, phir 220 files Storage par aur
   11 categories + 220 assets Firestore mein upload karke **count verify**
   karta hai (`VERIFIED: 11 categories + 220 published assets are live.`),
   aur rules bhi deploy kar deta hai. Re-run idempotent hai.

### Alternative: local machine / Cloud Shell

```bash
cd tools/assets && npm install
GOOGLE_APPLICATION_CREDENTIALS=/path/to/serviceAccountKey.json \
  node tools/assets/upload_firebase.js            # add --dry-run to verify only
firebase deploy --only firestore:rules,storage:rules
```

Project: `gen-lang-client-0291066258` (from `.firebaserc` / `google-services.json`).

## Verified in this change

- `python3 tools/assets/generate_assets.py` → `categories=11 assets=220`, 20 per category
- `python3 tools/assets/validate_seed.py` → **SEED OK** (fields, status, valid SVG XML + PNG headers for all 440 files)
- `node tools/assets/upload_firebase.js --dry-run` → **DRY RUN OK**
- Android compile/tests could **not** be run here (no JDK/Android SDK in this
  environment), and the live upload could not be executed from here (the
  attached service-account file did not materialise in the workspace and
  Google API egress is blocked) — run the command above once to publish.
