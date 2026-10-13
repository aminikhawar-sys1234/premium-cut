#!/usr/bin/env node
/**
 * Upload the generated Elements asset library to Firebase.
 *
 *   Storage:   elements/files/{category}/{id}.svg
 *              elements/thumbnails/{category}/{id}.png
 *   Firestore: categories/{id}            (11 docs)
 *              assets/{id}                (220 docs, status=published)
 *
 * Usage (service-account key stays OUT of git — see .gitignore):
 *   GOOGLE_APPLICATION_CREDENTIALS=tools/assets/serviceAccountKey.json \
 *     node tools/assets/upload_firebase.js [--dry-run]
 *
 * Idempotent: re-running overwrites the same paths/doc IDs (version is bumped
 * only with --bump). A doc is written only AFTER both files uploaded, so a
 * failed upload never creates a broken published record.
 */
const fs = require("fs");
const path = require("path");

const ROOT = path.join(__dirname, "..", "..");
const ASSETS_DIR = path.join(ROOT, "firebase-assets");
const DRY = process.argv.includes("--dry-run");
const BUMP = process.argv.includes("--bump");

async function main() {
  const seed = JSON.parse(fs.readFileSync(path.join(ASSETS_DIR, "firestore-seed.json"), "utf8"));
  console.log(`Seed: ${seed.categories.length} categories, ${seed.assets.length} assets`);
  if (DRY) {
    for (const a of seed.assets) {
      for (const p of [`files/${a.categoryId}/${a.id}.svg`, `thumbnails/${a.categoryId}/${a.id}.png`]) {
        if (!fs.existsSync(path.join(ASSETS_DIR, p))) throw new Error(`Missing file: ${p}`);
      }
    }
    console.log("DRY RUN OK — all 440 files present, nothing uploaded.");
    return;
  }
  let admin;
  try { admin = require("firebase-admin"); } // tools/assets/node_modules (npm install there)
  catch { admin = require(path.join(ROOT, "functions", "node_modules", "firebase-admin")); }
  const keyPath = process.env.GOOGLE_APPLICATION_CREDENTIALS;
  if (!keyPath || !fs.existsSync(keyPath)) {
    throw new Error("Set GOOGLE_APPLICATION_CREDENTIALS to the service-account key JSON (not committed to git).");
  }
  const serviceAccount = JSON.parse(fs.readFileSync(keyPath, "utf8"));
  admin.initializeApp({
    credential: admin.credential.cert(serviceAccount),
    projectId: serviceAccount.project_id,
    storageBucket: `${serviceAccount.project_id}.firebasestorage.app`,
  });
  const db = admin.firestore();
  const bucket = admin.storage().bucket();
  const ts = admin.firestore.FieldValue.serverTimestamp();

  for (const c of seed.categories) {
    await db.collection("categories").doc(c.id).set({ ...c, createdAt: ts, updatedAt: ts }, { merge: true });
  }
  console.log("categories: 11 written");

  let done = 0;
  for (const a of seed.assets) {
    const { createdAt, updatedAt, ...rest } = a;
    // 1) Upload files first …
    await bucket.upload(path.join(ASSETS_DIR, "files", a.categoryId, `${a.id}.svg`),
      { destination: a.storagePath, metadata: { contentType: "image/svg+xml", cacheControl: "public, max-age=31536000, immutable" } });
    await bucket.upload(path.join(ASSETS_DIR, "thumbnails", a.categoryId, `${a.id}.png`),
      { destination: a.thumbnailPath, metadata: { contentType: "image/png", cacheControl: "public, max-age=31536000, immutable" } });
    // 2) … then publish the Firestore doc (never a broken published record).
    const doc = { ...rest, version: BUMP ? a.version + 1 : a.version, createdAt: ts, updatedAt: ts };
    await db.collection("assets").doc(a.id).set(doc, { merge: true });
    if (++done % 40 === 0) console.log(`assets: ${done}/${seed.assets.length}`);
  }
  console.log(`DONE — ${done} assets uploaded & published.`);
}

main().catch((e) => { console.error("FAILED:", e.message); process.exit(1); });
