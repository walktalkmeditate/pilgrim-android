#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Regenerates ../corpus/ and ../expected/ with iOS's own HonorEngine at the
# parity pin, and ../expected/cl-distance-pairs.txt with CoreLocation's
# own CLLocation.distance. Needs macOS with swiftc and a pilgrim-ios
# checkout (default: a sibling of this repo; override IOS_REPO).
set -euo pipefail

PIN=7c200bf
here="$(cd "$(dirname "$0")" && pwd)"
golden="$(cd "$here/.." && pwd)"
repo_root="$(cd "$here/../../../../../../.." && pwd)"
IOS_REPO="${IOS_REPO:-$repo_root/../pilgrim-ios}"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

for f in Way WayGeometry HonorTuning HonorMomentTracker HonorEngine; do
  git -C "$IOS_REPO" show "$PIN:Pilgrim/Models/Honor/$f.swift" > "$work/$f.swift"
done
git -C "$IOS_REPO" show "$PIN:Pilgrim/Models/Walk/Seek/ArrivalDebounce.swift" > "$work/ArrivalDebounce.swift"

swiftc -O -o "$work/harness" \
  "$work/Way.swift" "$work/WayGeometry.swift" "$work/HonorTuning.swift" \
  "$work/HonorMomentTracker.swift" "$work/HonorEngine.swift" "$work/ArrivalDebounce.swift" \
  "$here/Stubs.swift" "$here/Corpus.swift" "$here/main.swift"

rm -rf "$golden/corpus" "$golden/expected"
TZ=UTC "$work/harness" corpus "$golden/corpus"
TZ=UTC "$work/harness" capture "$golden/corpus" "$golden/expected"
TZ=UTC "$work/harness" pairs "$golden/expected/cl-distance-pairs.txt"
TZ=UTC "$work/harness" cache
