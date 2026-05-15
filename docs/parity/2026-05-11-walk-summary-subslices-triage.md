> **SUPERSEDED** by `docs/parity/2026-05-15-parity-ledger.md` — findings predate the v1.6.0 port + bug fixes and are stale. Format precedent only.

# Walk Summary Sub-Slices Triage

| field | value |
|---|---|
| **iOS pin** | v1.5.0 = `db4196e` |
| **Android HEAD** | `ce11a87` |
| **Generated** | 2026-05-11 |
| **Type** | sub-slice triage (manual, parallel Explore-agent fan-out) |
| **Source** | `docs/parity/2026-05-10-walk-summary-audit.md` (67 "missing" findings) |

## Why this exists

The 2026-05-10 WalkSummary parity audit flagged 67 "missing" findings — but the slice resolver only matched 6 Android files under `ui/walk/summary/` against 13 iOS files. Many iOS sub-views live in different Android packages (`ui/walk/voice/`, separate `ui/walk/summary/` cards) and were marked "missing" because they fell outside the audit's matched Android file set, not because they're actually un-ported.

This document triages the real gap. Bucket key:
- **A** — fully ported. Skip.
- **B** — partially ported. Polish PR closes gaps.
- **C** — unported. Feature PR required.

## Summary

| Sub-slice | iOS LOC | Bucket | Effort |
|---|---|---|---|
| ActivityTimelineBar | 264 | **A** | skip |
| ActivityListView | 107 | **A** | skip |
| ActivityInsightsView | 68 | **A** | skip |
| PaceSparklineView | 101 | **A** | skip |
| ElevationProfileView | 102 | **A** | skip |
| FaviconSelectorView | 50 | **A** | skip |
| AudioPlayerModel | 136 | **B** | polish ~20 LOC |
| VoiceRecordingRow | 248 | **B** | polish ~60 LOC |
| PhotoReliquarySection | 313 | **B** | feature ~200–250 LOC |
| PhotoCarouselView | 219 | **C** | feature ~150–200 LOC |
| PhotoPreviewSheet | 147 | **C** | feature ~250–350 LOC |

**Real gap:** ~700 LOC across 3 reliquary feature PRs + ~80 LOC across 1 voice-row polish PR. Down from "67 missing" alarm to ~4 actionable PRs.

## Detail per sub-slice

### A — fully ported (skip)

**ActivityTimelineBar** → `WalkActivityTimelineCard.kt` + `TimelineSegments.kt`
- Z-order meditations (16dp) under talks (10dp) — matched at `WalkActivityTimelineCard.kt:189-194@ce11a87`
- Reverse-order hit-test, tap-toggle, empty-area deselect — matched
- Pace sparkline integration — matched
- Time label toggle (absolute ↔ relative) — matched

**ActivityListView** → `WalkActivityListCard.kt`
- Time-sorted talks+meditation merge — `WalkActivityListCard.kt:153-181@ce11a87`
- 12-hour HH:mm AM/PM range formatting — matched
- Avatar circles + duration formatting — matched

**ActivityInsightsView** → `WalkActivityInsightsCard.kt`
- Gating `talkDuration > 0 AND activeDuration > 0` — matched at `WalkActivityInsightsCard.kt:91-95@ce11a87`
- `Int((talk/active) * 100)` — matched
- 60s compact-duration unit switch — matched via stringResource layer

**PaceSparklineView** → `PaceSparkline.kt`
- `SPEED_THRESHOLD_MPS = 0.3f` matches iOS `> 0.3 m/s` filter — `PaceSparkline.kt:62@ce11a87`
- `TARGET_BUCKETS = 50` matches iOS downsample cap — `PaceSparkline.kt:63@ce11a87`
- 0.85 fill factor + gradient stroke — matched

**ElevationProfileView** → `ElevationProfile.kt`
- Gate `altitudes.count > 5 && maxAlt - minAlt > 1` — matched at `ElevationProfile.kt:92-95@ce11a87`
- Per-pixel bucket downsample — matched

**FaviconSelectorView** → `FaviconSelectorCard.kt`
- Tap-to-deselect, 44pt circular buttons, three toggles — matched at `FaviconSelectorCard.kt:73,127@ce11a87`
- 200ms easing on selection animation — matched

All six covered by existing unit tests (`TimelineSegmentsTest`, `WalkActivityListCardTest`, `WalkActivityInsightsCardTest`, `PaceSparklineTest`, `ElevationProfileTest`, `FaviconSelectorCardTest`).

### B — partially ported

#### AudioPlayerModel.swift → `ExoPlayerVoicePlaybackController.kt`

- **Speed cycle algorithm.** iOS uses `firstIndex(of:)` + modulo on `[1.0, 1.5, 2.0]` array. Android uses threshold-based cycling (`< 1.25 → 1.5`, `< 1.75 → 2.0`, else `1.0`). Both produce the same observable cycle. Cosmetic-only drift. Optional polish: migrate to array+modulo for line-for-line iOS parity (~20 LOC).
- Everything else (3-branch state machine, 100ms progress ticker, finish-handler main-thread marshal + focus deactivate) — fully matched at `ExoPlayerVoicePlaybackController.kt:64-140@ce11a87`.

#### VoiceRecordingRow.swift → `VoiceRecordingsSection.kt`

Real gaps (~60 LOC polish):

1. **100ms seek defer when togglePlay starts fresh-then-seeking** (`VoiceRecordingRow.swift:85-94@db4196e`). Android seeks immediately. Fix: add `delay(100)` before `onSeek` when transitioning from inactive to active+seeking.
2. **Transcription expand threshold `count > 280 OR newlines > 7`** (`VoiceRecordingRow.swift:27-36@db4196e`). Android `TranscriptionView` always renders full text in Walk Summary (no expand/collapse). `RecordingRow.kt:156-161@ce11a87` has the toggle but only for the standalone Recordings List screen, NOT the Walk Summary surface. Fix: backport the expand/collapse to `VoiceRecordingsSection.kt`.
3. **Done-button trim + empty-skip on persistence** (`VoiceRecordingRow.swift:139-154@db4196e`). Android `TranscriptionEditor` trims on commit but does NOT `.isEmpty()`-check before persisting; empty edits get saved. Fix: add empty-after-trim guard.

#### PhotoReliquarySection.swift → `PhotoReliquarySection.kt:74-152@ce11a87`

Real gaps (~200–250 LOC feature PR):

1. **4-state gate** (toggle / permission / loading / empty) — iOS `PhotoReliquarySection.swift:58-77@db4196e`. Android only checks `photos.isNotEmpty()` for grid render; no permission-revoked prompt or toggle-off invisibility.
2. **Deferred-skeleton 300ms `asyncAfter` with double-check** — iOS `PhotoReliquarySection.swift:80-91@db4196e`. Android has no skeleton loader.
3. **scenePhase observer re-fetch** — iOS `PhotoReliquarySection.swift:101-124@db4196e`. Android has no Lifecycle.Event.ON_START observer to re-check Photos permission after a backgrounded settings flip.
4. **`fetchGeneration` monotonic counter** — iOS `PhotoReliquarySection.swift:259-279@db4196e`. Android has no overlap-drop guard for in-flight async fetches.
5. **Permission-revoked prompt with Settings deep link** — iOS lines 167-188. Android omits; user sees empty reliquary on revoke.

### C — unported

#### PhotoCarouselView.swift (~150–200 LOC feature PR)

No Android carousel UX. Android shows a 3-column grid; iOS uses a horizontal scrolling carousel with:
- Activation state machine (long-press 400ms — `PhotoCarouselView.swift:126-128@db4196e`)
- Scroll-phase observer that clears activation on `.interacting` — `PhotoCarouselView.swift:42-50@db4196e`
- 88pt thumbnails with pinned badge + active scale (1.05× spring)
- Haptic on activate + commit

#### PhotoPreviewSheet.swift (~250–350 LOC feature PR)

No Android preview modal. Android has no fullscreen single-photo viewer. iOS provides:
- Drag-to-dismiss with 120pt threshold + spring snap-back — `PhotoPreviewSheet.swift:63-72@db4196e`
- `hasCommitted` one-shot latch on pin button — `PhotoPreviewSheet.swift:76-87@db4196e`
- High-res async fetch via PhotoKit `.highQualityFormat`
- "Open in Photos" via `photos-redirect://` URL scheme (Android equivalent: Intent.ACTION_VIEW with content://)

## Recommended PR sequence

1. **Voice row polish PR** (~80 LOC) — closes the 3 VoiceRecordingRow gaps + optional AudioPlayer speed-cycle alignment. Fast, low-risk, behavior-visible.
2. **Reliquary state polish PR** (~200–250 LOC) — fills out PhotoReliquarySection's 4-state gate, deferred skeleton, scenePhase observer, fetch-generation guard, permission-revoked prompt.
3. **Reliquary carousel feature PR** (~150–200 LOC) — implements horizontal carousel with activation state machine + scroll-phase observer. Depends on (2).
4. **Reliquary preview-sheet feature PR** (~250–350 LOC) — implements full-screen preview modal with drag-dismiss. Depends on (3) for the carousel→preview chain.

After (1)–(4): WalkSummary scene reaches near-complete iOS v1.5.0 parity. Remaining items become drift-cosmetic / token alignment only.

## Drift-critical perf item still pending (P1 in user's sequencing)

Not addressed by this triage:
- `WalkSummaryViewModel.kt:1365-1385@ce11a87` — `detectMilestoneFor` runs N+1 Room reads (`allWalks()` then `locationSamplesFor(walk.id)` per walk + re-haversine). iOS uses pre-computed `walk.distance` field. For users with hundreds+ walks: slow Walk Summary load. Single-PR fix using Room's existing `walk.totalDistanceMeters` column. Estimated ~30 LOC + 1 test.

> Triage written. Next step: pick a PR from the recommended sequence and invoke `/autopilot` with the scope. Recommended start: voice-row polish (smallest + visible + de-risks the pattern of multi-iOS-file polish PRs).
