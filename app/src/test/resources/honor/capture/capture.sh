#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Regenerates ../own-walk-way.json with iOS's own OwnWalkWayBuilder and
# WayStore encoder at the parity pin. Needs macOS with swiftc and a
# pilgrim-ios checkout (default: a sibling of this repo; override IOS_REPO).
set -euo pipefail

PIN=7c200bf
here="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$here/../../../../../.." && pwd)"
IOS_REPO="${IOS_REPO:-$repo_root/../pilgrim-ios}"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

for f in Way WayGeometry OwnWalkWayBuilder; do
  git -C "$IOS_REPO" show "$PIN:Pilgrim/Models/Honor/$f.swift" > "$work/$f.swift"
done

swiftc -o "$work/harness" \
  "$work/Way.swift" "$work/WayGeometry.swift" "$work/OwnWalkWayBuilder.swift" \
  "$here/Stubs.swift" "$here/main.swift"

# The builder probes recording files under ~/Documents and stamps the
# phone's time zone, so both are pinned to a scratch home and Madrid.
mkdir -p "$work/home"
CFFIXED_USER_HOME="$work/home" TZ=Europe/Madrid "$work/harness" "$here/../own-walk-way.json"
