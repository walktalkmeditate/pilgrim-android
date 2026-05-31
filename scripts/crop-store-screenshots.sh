#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Center-crop every PNG in a directory to a strict 9:16 portrait aspect for
# Google Play Store screenshot uploads (Play rejects anything outside 16:9 / 9:16).
#
# Usage: scripts/crop-store-screenshots.sh <dir>
#   <dir> defaults to docs/screenshots/phone

set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIR="${1:-$ROOT/docs/screenshots/phone}"
OUT="${DIR%/}/9x16"
mkdir -p "$OUT"

python3 - "$DIR" "$OUT" <<'PY'
import sys, os, pathlib
from PIL import Image
src_dir, out_dir = sys.argv[1], sys.argv[2]
TARGET_R = 9 / 16
PARCHMENT = (246, 241, 226)

# Per-file top-bias when trimming height (phone path). 0.0 = preserve top; 1.0 = preserve bottom.
BIAS = {
    "01_walk_start.png": 0.75,
    "02_active_walk.png": 0.85,
    "03_meditation.png": 0.50,
    "04_walk_summary.png": 0.35,
    "05_walk_stats.png": 0.50,
    "06_walk_activity.png": 0.55,
    "07_journal.png": 0.85,
    "08_goshuin.png": 0.20,
    "09_settings.png": 0.20,
}
DEFAULT_BIAS = 0.50

for path in sorted(pathlib.Path(src_dir).glob("*.png")):
    im = Image.open(path).convert("RGB")
    w, h = im.size
    r = w / h
    if r > TARGET_R:
        new_h = round(w / TARGET_R)
        pad = new_h - h
        top = pad // 2
        out = Image.new("RGB", (w, new_h), PARCHMENT)
        out.paste(im, (0, top))
        mode = f"pad +{pad}h"
    elif r < TARGET_R:
        new_h = round(w / TARGET_R)
        bias = BIAS.get(path.name, DEFAULT_BIAS)
        off = round((h - new_h) * bias)
        out = im.crop((0, off, w, off + new_h))
        mode = f"trim bias={bias}"
    else:
        out = im
        mode = "exact"
    out_path = os.path.join(out_dir, path.name)
    out.save(out_path, format="PNG")
    print(f"{path.name}: {w}x{h} -> {out.size[0]}x{out.size[1]}  {mode}")
PY

echo ""
echo "Cropped 9:16 outputs in: $OUT"
