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

  // Phase 1 — Storage bucket must exist. Projects that never opened Firebase
  // Storage in the console have no default bucket ("The specified bucket does
  // not exist"). Try to create it; if Google reserves the *.firebasestorage.app
  // name for console provisioning, fail with the exact one-click fix.
  phase = `Storage bucket '${bucket.name}' check/create`;
  const [bucketExists] = await bucket.exists();
  if (!bucketExists) {
    console.log(`Bucket ${bucket.name} does not exist yet - creating it...`);
    try {
      await bucket.create({ location: "US", storageClass: "STANDARD" });
      console.log("Bucket created.");
    } catch (createErr) {
      throw new Error(
        `Storage bucket '${bucket.name}' mojood nahi hai aur auto-create nahi ho saka (${createErr.message}). ` +
        `FIX: Firebase Console -> Storage -> 'Get started' (defaults) ek dafa click karen, phir workflow dobara chalayen.`
      );
    }
  }

  phase = "Firestore write (categories)";
  for (const c of seed.categories) {
    await db.collection("categories").doc(c.id).set({ ...c, createdAt: ts, updatedAt: ts }, { merge: true });
  }
  console.log("categories: 11 written");

  phase = "Storage upload + Firestore publish (assets)";
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

let phase = "init";
main().catch((e) => {
  // ::error:: makes the real reason visible in the Actions annotations,
  // without opening the log.
  const msg = String(e.message || e).replace(/[\r\n]+/g, " ");
  console.error(`::error title=Upload failed::Phase '${phase}': [${e.code || "no-code"}] ${msg}`);
  if (/NOT_FOUND|database.*does not exist|Firestore.*not.*enabled/i.test(msg)) {
    console.error("::error::Firestore database abhi bana nahi hai. Firebase Console -> Firestore Database -> 'Create database' (production mode) kar ke workflow dobara chalayen.");
  }
  if (/SERVICE_DISABLED|API has not been used|PERMISSION_DENIED/i.test(msg)) {
    console.error("::error::Google Cloud API disabled ya permission nahi hai. Firebase Console se Firestore + Storage enable kar ke dobara chalayen.");
  }
  console.error("FAILED:", e);
  process.exit(1);
});
