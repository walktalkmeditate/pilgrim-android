#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Generate release notes from conventional-commit history since the last tag.
# Mirrors the pilgrim-ios `scripts/release.sh changelog` convention.
#
# Outputs:
#   build/changelog.md                                 -> GitHub Release body (developer-facing)
#   app/src/main/play/release-notes/en-US/default.txt  -> Play "What's new" (user-facing, <=500 chars)
#
# Play "What's new" resolution order:
#   1. If app/src/main/play/release-notes/en-US/whatsnew.txt exists -> use it verbatim
#      (explicit curated override for narrative releases; e.g. a launch note).
#   2. Else if a prior v* tag exists -> auto-generate the diff since that tag.
#   3. Else (no tag, no override) -> leave the committed default.txt untouched
#      (the first release keeps its hand-written note instead of dumping all history).
#
# Usage: scripts/release-notes.sh [versionName]
#   versionName defaults to the versionName in app/build.gradle.kts.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

PLAY_NOTES_DIR="app/src/main/play/release-notes/en-US"
WHATSNEW="$PLAY_NOTES_DIR/whatsnew.txt"
DEFAULT="$PLAY_NOTES_DIR/default.txt"
CHANGELOG="build/changelog.md"
PLAY_NOTES_LIMIT=500

VERSION="${1:-$(grep -oE 'versionName = "[^"]+"' app/build.gradle.kts | sed -E 's/.*"([^"]+)".*/\1/')}"

LAST_TAG="$(git describe --tags --abbrev=0 2>/dev/null || echo "")"
if [ -n "$LAST_TAG" ]; then
  RANGE="$LAST_TAG..HEAD"
  echo "Release notes range: $RANGE"
else
  RANGE="HEAD"
  echo "Release notes range: all history (no prior tag)"
fi

feats=""
fixes=""
styles=""
while IFS= read -r line; do
  msg="${line#* }"
  case "$msg" in
    feat:*|feat\(*)
      feats+="- $(printf '%s' "$msg" | sed 's/^feat([^)]*): *//; s/^feat: *//')"$'\n' ;;
    fix:*|fix\(*)
      fixes+="- $(printf '%s' "$msg" | sed 's/^fix([^)]*): *//; s/^fix: *//')"$'\n' ;;
    style:*|style\(*)
      styles+="- $(printf '%s' "$msg" | sed 's/^style([^)]*): *//; s/^style: *//')"$'\n' ;;
  esac
done < <(git log "$RANGE" --oneline --no-merges 2>/dev/null)

mkdir -p build "$PLAY_NOTES_DIR"

# --- Developer changelog (GitHub Release body) ---
{
  echo "# Pilgrim $VERSION"
  echo ""
  if [ -n "$feats" ]; then printf '## What'\''s New\n%s\n' "$feats"; fi
  if [ -n "$fixes" ]; then printf '## Bug Fixes\n%s\n' "$fixes"; fi
  if [ -n "$styles" ]; then printf '## Visual Polish\n%s\n' "$styles"; fi
  if [ -z "$feats$fixes$styles" ]; then echo "Maintenance release."; fi
} > "$CHANGELOG"

# --- Play "What's new" (user-facing) ---
if [ -f "$WHATSNEW" ]; then
  echo "Using curated $WHATSNEW for Play notes."
  cp "$WHATSNEW" "$DEFAULT"
elif [ -n "$LAST_TAG" ]; then
  echo "Auto-generating Play notes from $RANGE."
  {
    [ -n "$feats" ] && printf '%s' "$feats"
    [ -n "$fixes" ] && printf '%s' "$fixes"
  } | sed 's/^ *- */• /' \
    | sed 's/@[A-Za-z0-9_-]*//g' \
    | sed -E 's/[A-Z][A-Za-z]*(View|Card|Manager|Player|Controller|ViewModel)//g' \
    | sed 's/  */ /g; s/ ,/,/g; s/ \././g' \
    | sed '/^[•[:space:]]*$/d' \
    | head -8 > "$DEFAULT"
  # Enforce the Play 500-char limit on the whole field.
  if [ "$(wc -c < "$DEFAULT")" -gt "$PLAY_NOTES_LIMIT" ]; then
    head -c "$PLAY_NOTES_LIMIT" "$DEFAULT" > "$DEFAULT.tmp"
    mv "$DEFAULT.tmp" "$DEFAULT"
  fi
else
  echo "No prior tag and no whatsnew.txt — keeping committed $DEFAULT as-is."
fi

echo ""
echo "--- GitHub Release ($CHANGELOG) ---"
cat "$CHANGELOG"
echo ""
echo "--- Play What's new ($DEFAULT) ---"
cat "$DEFAULT"
echo ""
echo "Play notes length: $(wc -c < "$DEFAULT") / $PLAY_NOTES_LIMIT chars"
