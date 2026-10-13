#!/usr/bin/env python3
"""Validate generated seed: 11 categories x 20 published assets, files exist, SVGs well-formed."""
import json, os, sys, xml.etree.ElementTree as ET
root = os.path.join(os.path.dirname(__file__), "..", "..")
seed = json.load(open(os.path.join(root, "firebase-assets/firestore-seed.json")))
cats, assets = seed["categories"], seed["assets"]
assert len(cats) == 11, cats
assert len(assets) == 220, len(assets)
ids = [a["id"] for a in assets]; assert len(set(ids)) == 220
from collections import Counter
c = Counter(a["categoryId"] for a in assets)
assert all(c[cat["id"]] == 20 for cat in cats), c
req = {"name","categoryId","format","storagePath","thumbnailPath","width","height","defaultProperties","version","status","createdAt","updatedAt"}
for a in assets:
    assert req <= set(a), (a["id"], req - set(a))
    assert a["status"] == "published" and a["format"] == "svg"
    svg = os.path.join(root, "firebase-assets/files", a["categoryId"], a["id"] + ".svg")
    png = os.path.join(root, "firebase-assets/thumbnails", a["categoryId"], a["id"] + ".png")
    assert os.path.getsize(svg) > 100 and ET.parse(svg) is not None
    assert open(png, "rb").read(8) == b"\x89PNG\r\n\x1a\n"
print("SEED OK: 11 categories, 220 assets, all SVG/PNG files valid")
