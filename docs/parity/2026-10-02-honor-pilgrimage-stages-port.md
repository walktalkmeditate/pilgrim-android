# Parity Spec: Honor, the pilgrimage-stage slice

| field | value |
|---|---|
| **iOS pin** | `v2.0.0` = `7c200bf` (PRs #84 and #85, merged as `517c160` and `8018a55`) |
| **iOS PR #91** | open, head `534f170`, based on `7c200bf`; specified as a conditional annex |
| **Android HEAD** | `0defff85` |
| **Data** | `open-pilgrimages` v1.12.0 (local `675d4e3`, the same release as the live CDN index of 2026-10-02) |
| **Generated** | 2026-10-02 |
| **Type** | port |
| **Generator** | ios-parity skill: five topic readers and one annex reader in parallel, each applying all four lenses (behavior, UI and visual, data, edge cases) to one cluster |
| **Plan** | `docs/plans/2026-10-02-001-feat-honor-pilgrimage-stages-plan.md` (U30; feeds U31–U41) |
| **Companions** | `docs/parity/2026-09-29-honor-own-walk-port.md` ("own-walk spec A §n" and so on) and `docs/parity/2026-10-01-honor-shared-walk-port.md` ("S1 §n" and so on). This spec doesn't repeat them. |
| **Checked** | All 343 cited code blocks were machine-checked: every quoted line appears at its cited lines in the pinned tree (iOS at `7c200bf` and `534f170`, Android at `0defff85`). |

This spec outranks the plan wherever they disagree (the plan's authority order). Every iOS claim carries a Swift quote pinned to `7c200bf` (the annex's to `534f170`), and Android claims are pinned to `0defff85`. Inside a cluster, "§6" means that cluster's section 6; "P3 §6" means section 6 of cluster P3. Each cluster numbers its own corrections, additions and defect candidates (P1 C3, P2 A-1, P5-D8 and so on); the tables below gather them.

| Section | Covers | Feeds |
|---|---|---|
| [P1. Wire and catalog](#p1-wire-and-catalog-the-stage-importer-the-open-pilgrimages-index-and-the-catalog-service) | `PilgrimageError`, the URLs and identifier rules, the fetches, decoding strictness, stage file → `Way`, `route.json`, the index and grouping, the 24 h cache, the preview, the live dataset against iOS's rules | U31, U32 |
| [P2. Package, store and ledger](#p2-package-store-and-ledger-downloads-replaceupdateremove-the-ways-tree-the-ledger-finalize-and-recovery) | The Ways tree and `retireMany`, the package manager's state, download/commit/rollback, Replace and `replacing.txt`, Update and Remove, the kill table, the ledger, the record call sites, Android's split, the Ways footer, launch | U33, U34, U36, U40 |
| [P3. The stage in the walk engine](#p3-the-stage-in-the-walk-engine-water-ahead-the-stage-branches-the-outcome-the-reflection-reply-the-glance-and-the-lexicon) | The water watcher and its quiet hour, the engine's stage changes, the caption and haptic, no soft tap or companion, `HonorStageOutcome`, revival, schema 12's fields, the reflection reply, the glance, the lexicon, the water golden traces | U35, U36, U40 |
| [P4. Doors and pre-walk](#p4-doors-and-pre-walk-the-ways-sheets-third-door-the-catalog-the-route-page-the-overviews-stage-branches-and-the-morning-card) | Presentation, the Ways sheet's third section, the catalog, the route page, the numbers on these screens, the overview's stage branches, the morning card, the preview, About | U37, U38 |
| [P5. On the walk and after](#p5-on-the-walk-and-after-marks-and-the-camera-report-the-card-body-the-water-caption-the-day-the-arrival-reply-and-the-summary) | Mark selection on each screen, the camera report, drawing marks, the water caption's slot, the card body, the arrival card and its reply row, the arrival waypoint, "the day", the summary's stage block | U39, U40 |
| [Annex. iOS PR #91](#annex-ios-pr-91-a-temple-ahead-stamps-until-five-conditional) | `stampHours`, the temple-ahead watcher, the shared notice clock, the schema-12 recommendation, the golden traces, the fold-in unit's checklist | the fold-in unit, and U35's schema |

---

## Resolutions

### Corrections to the plan, by unit

The plan was written before these reads. Where it disagrees with iOS, iOS as shipped wins. Each item points to its evidence; the cluster sections carry the detail.

**Across units**
- **What iOS walks is the Way it captured at the door.** The view model holds `let way: Way?` from Begin, and iOS neither waits for a package commit nor refuses Start (P2 C-5, C-10; P2 §10). The plan's door file stamp and Start's wait are Android additions, not equivalents. Owner decision 2 settles which Android builds. The plan's Risks table ("Start refuses during a commit") also contradicts its Key Technical Decisions; it goes whichever way decision 2 lands.
- **The walk guard:** iOS checks on entry and before the commit, and never after. Replace's old-route removal and Update's tail sweep and reconcile run unguarded (P2 C-4). Android holds its guard from entry to the last post-commit step.
- **E-15 and E-16 are matched as shipped** (P5 C14): "they sat here 5 minutes" after a stage's Sit? is a "they" iOS ships, so R14 allows it; the card measures to `at`, not `pin` (up to 1,247 m apart in live data). Both are filed.

**U31 (the stage importer)**
- The importer checks only the request: `id`, `stage.routeId` and `stage.index`. The check against the catalog entry and the count/name check against the route row live in the route preview and the package manager (U32, U34). Move the plan's "count or name disagrees" test to U34 (P1 C3).
- Local names: sort by key, cut to the first 20 raw pairs, then drop invalid keys and blank values. Live Guernica loses ten names this way, `pt` among them (P1 C4).
- The build: `totalDistanceMeters` is the haversine length, not the file's; `t` isn't rebased; `marks` is an empty list, never null; `title` and `tzIdentifier` are cut to 120 and 80 (P1 C19, P1 §5.4).
- An empty `difficulty` is kept by the importer; skipping it is the facts formatter's job (U37, U38) (P1 C18).
- `schemaVersion` predates the pin; only `stampHours` arrived after it (P1 C14). The release rule is `\Av[0-9]+\.[0-9]+\.[0-9]+\z` (P1 C20).

**U32 (the catalog service)**
- iOS's ephemeral session keeps a memory `URLCache` that honours the CDN's 7-day `max-age` inside one process. Android's no-cache client is a recorded difference, and the iOS behaviour is filed (P1 C1, A1).
- Row rules: seven, not three. A decode failure in any one row fails the whole index (P1 C8). The name is `en` when the key exists, else the alphabetically first valid key (P1 C9).
- `load`: a forced load that fails with a cache returns the cache with no error; freshness is a signed `<` 24 h; a zero-route parse is cached (P1 C10). Caps: the declared length first (`<=`), then the streamed count (`>` throws) (P1 C11). File names come from a closed set (P1 C12).
- Follow `CollectiveRouteCatalogService`'s temp-then-rename write only: log nothing, cache the parsed catalog, fetch only when asked (P1 C13).
- Tests: 24 of iOS's 37 test the service; 12 belong to U37's models; one waits for Stage 21-3 (P1 C5, C7).
- The cache's home is owner decision 5.

**U33 (the store tree, `retireMany`, the ledger)**
- The ledger's `next` returns `(index, resumeFrac)`; the four next-row strings are the route page's (U37) (P2 C-1).
- `record`: `name`, `distanceKm` and `walkedAt` are last-wins; a completed stage is credited its whole `distanceKm`; the fraction is clamped to [0, 1]; a nil outcome writes nothing (P2 C-15). The order-safe branch must also keep `name` and `distanceKm` from an older replay (P2 C-8).
- The ledger write must throw on failure, or finalize never retries it (P2 C-7).
- `retireMany` counting live-session Way ids as walked is an Android addition (P2 C-13).
- The Ways footer counts every package-owned `way.json` of every route, named by the installed route: matched as shipped (P2 C-14, D-9).
- Ten ported assertions prove "nothing left" through `list()`. Once `list()` skips stage ids, nine would pass vacuously and one would fail; port them against the stage-id listing (P2 C-9). iOS's store round-trip test can't be verbatim for the same reason (P1 C16).

**U34 (the package manager)**
- Its own client: 30 s idle, 300 s for a whole fetch (P1 C2).
- The 50 MiB total is summed over received bytes after each file, `<=` passing (P1 C15, P2 C-3).
- Only a commit failure rolls back. It retires `0..<max(previous, new)` and leaves `ledger.json` alone; a failure while streaming or validating never touches the store (P2 C-2).
- Replace's busy race is iOS's as shipped: it writes the marker, the download is refused, the marker is deleted. The plan's "check busy first" is an Android-only fix. Port iOS's order and test that the refused Replace leaves no marker, unless owner decision 4 says otherwise (P2 C-6).
- Single-flight: a second download throws `incomplete` without touching the phase; nothing queues (P2 C-16).
- Launch: call `installed()` in the UI process after `finalizePending`. There's no separate marker cleanup (P2 C-12).
- The download must outlive the route page's ViewModel (P4 correction 2).

**U35 (water ahead, the stage session, Room schema 12)**
- The no-soft-tap-on-a-stage clause goes in `BeginHonorWalk`, which has the loaded Way, not `WalkStartRequest`, whose `atStart` runs before the Way loads (P3 correction 1, verified).
- Schema 12 also needs `arrival_walked_meters`, written in the arrival's compare-and-set: iOS freezes the walked distance into the stage arrival card (P3 correction 2, P5 C4).
- The caption needs a kind, and the quiet clock should be named `last_notice_seconds`. Recommended: a `honor_notices` table (Annex A.5.2), owner decision 1. Whatever holds notices joins `deleteLiveRows` (Annex A.9).
- "Pauses out" is Android's frozen pause, not iOS's (P3 correction 3). The quiet hour runs from the last caption, and the Begin fix can be the first one (P3 correction 4). Water reads no gate, the whisper's included (P3 correction 5). Water is evaluated only on fixes (P3 correction 12). The marks sort with Swift's `<`, stable, no tiebreak (P3 correction 13).

**U36 (finalize, the reflection reply)**
- Origin −1 lives in `voiceOriginIndex`, and `HonorSession.planReply` must accept the reserved `"stage-reflection"` id, which isn't among the Way's moments (P3 correction 6).
- "At `stage.end.at`" is the reflection moment's place, not a filing time (P3 correction 7). The recordings sink and the discard already exist from Stage 21-1 (P3 correction 8).
- The outcome mapping is answered: `progressFrac` = `progress_frac`; `arrived` = `phase == ARRIVED`; no outcome when `start_frac IS NULL OR anchored_by_fallback = 1`; nothing on a stage reads `progress_high_water` (P3 correction 9).
- Recovery records only when the stage Way loads, inside `finalizeRecovered`'s existing check (P2 C-17).
- There is no UI post-Finish finalize at `0defff85`. The `.pilgrim` importer's pre-finalize is a ledger writer the plan missed; it needs the locks too (P2 C-11).

**U37 (the third door)**
- Only the route page shows download progress; the catalog shows none, and neither reloads when the phase ends (P4 correction 1, D-1, D-2).
- The badges are 13 pt glyphs whose words only the screen reader speaks (P4 correction 3). "finish your walk first" is a rust footer line, not an alert (P4 correction 4); test it at the ViewModel, since no door reaches the catalog mid-walk (P4 correction 5).
- The unreachable view also covers a zero-route catalog, and only "try again" forces a fetch (P4 correction 6). `hasUpdate` is string inequality (P1 C17). The next-row rule's ordering needs its own fixtures (P4 correction 14).
- Files the plan missed: a nullable section header, the link-routing tests for the two new routes, and `WayStageFacts` with the formatter (P4 correction 10).

**U38 (the overview's stage branches and the morning card)**
- Numbers: iOS prints "24.2 km", "764 km", "1,419 m"; Android's `WalkFormat` would print "24.20 km". Owner decision 7 (P4 correction 7).
- The stage line can show only on the overview (P4 correction 8).
- Files the plan missed: the full-size header's local-name line, `placeCopy(isStage)`, the awaited once-ever DataStore key with a connectivity probe, and today's full weather for the morning card (P4 correction 9).
- Stage 21-2 passes no maps line; 21-3 wires it (P4 correction 11).
- "the day" reads the walk's weather (P4 correction 12), shows before Start too (P5 C12), and reopens through the options sheet's handoff, not the card layer (P5 C13). The card's button labels are "Begin walking this stage" / "Close the day's words" (P4 correction 13).
- Four stage moment icons (`house.lodge`, `seal`, `building.columns`, `book.closed`; 470 of 505 live stage moments) draw as a plain pin on Android today (P5 C6). Owner decision 10 covers the seal.

**U39 (on the walk)**
- On the walk, marks re-select when the walker moves 200 m (`>=`) or the zoom crosses a whole level; a pan never re-selects. Only the overview re-selects on the camera's 200 m move (P5 C1). Marks can draw before Start (P5 C2). Idle skips only the time throttle (P5 C3).
- iOS draws marks and moment pins in one layer, where overlaps sort by screen height. Owner decision 9 (P5 C5).
- "the whole stage" can never show (P5 C7). "record again" on the arrival card starts at once, with no confirmation and its own labels (P5 C8).
- The water caption follows the walker's unit (P5 C15) and shows only while `0 ≤ now − firedAt < 20 s` (P3 correction 11). Shape the caption slot as a model now, so #91 adds a case (Annex A.9 item 8).

**U40 (after the walk)**
- The summary prints "14.04 km of 24.2 km of the stage"; test against the formatter's output, not the plan's "14.1" (P5 C9).
- "your reply" isn't gated on arrival; only the closing is (P5 C10). The live summary flow needs the walk's events (P5 C11).
- The lexicon needs `PromptsCoordinator` and an installed-route lookup from U34 (P3 correction 10; owner decision 11). The glance needs only a pinning test (P3 correction 14).

**The PR #91 fold-in**
- The golden recapture changes no existing trace: the nine own-walk traces are byte-identical under iOS's engine at `534f170`. The work is one harness case, the pin bump, and new temple traces (Annex A.9 item 3). U38's part is only the `WayStampNotice` helper; the domain model files belong on the list (Annex A.9 item 4).

### The flow-analysis gaps

| Gap | Resolution | Where |
|---|---|---|
| 1. The walk guard | iOS's clause is `walkScreenUp`; the other clauses are Android additions, held through the post-commit steps. What `:tracker` walks is owner decision 2 | P2 §2, §10; P3 §11; P4 §11; P5 §13 |
| 2. Remove while a ledger write is pending | `retireMany` counts live-session Way ids as walked, and the guard's live-rows clause blocks package changes meanwhile; the record reads the row's identity | P2 §13 (A-1, A-7) |
| 3. Ledger writers in two processes | Two lock layers, end-time dates, the order-safe merge with `name` and `distanceKm` | P2 §8, §10 (A-3–A-5) |
| 4. Finalize ordering | Link, record, marker, live rows, inside the retry path; the ledger write throws | P2 §9 (C-7); P3 §12 |
| 5. A kill mid-commit; the temp set | Port the marker exactly and call `installed()` at launch; temp set under `noBackupFilesDir`; a repair only by owner decision 3 | P2 §7, §12 |
| 6. Water ahead in Room | Fired set, notice clock, caption fields, arrival metres; persisted before the haptic; no replay after a revival | P3 §6, §11, §12; Annex A.5 |
| 7. A never-anchored stage summary | No entry, no progress line; the block still says "the stage you walked" | P5 §11.2 |
| 8. Past summaries change | Matched as shipped and filed | P2 D-6; P5-D8 |
| 9. Launch work mid-walk | `installed()` runs in the UI process only, never in `:tracker` | P1 §13; P2 §12; P4 §11 |
| 10. A second download erases a Replace's marker | Matched as shipped (owner decision 4) | P2 §4, D-3; P4 D-4 |
| 11. The catalog | Cache home (owner decision 5); the signed clock test; `list()` skips stage ids | P1 §8, §13; P2 §1 |
| 12. Task restore | The route page restores from its id and re-resolves the entry; idle phase; the redraw notice cleared on display | P1 §13; P4 §4.11 |
| 13. A backgrounded download | "the download didn't finish", a device row | P1 §13; P2 §13 |
| 14. The release flag off | Schema 12 runs either way (8→12 chain test); no pilgrimage work or routes with the flag off | P2 §13; P3 §18; P4 §11 |
| 15. Device transfer | The ledger and packages stay behind, as on iOS; the catalog cache travels (owner decision 5) | P1 §13; P2 §13; P4 §11 |

### Android additions to record at the gate

Gathered from the clusters; the gate records each with its reason.

- **The process split:** the guard's extra clauses and the one-time `finalizePending()` (P2 A-1); a failed ledger record retried at launch (P2 A-2); the two lock layers (P2 A-3); records dated at the walk's end (P2 A-4); the order-safe merge (P2 A-5); the record's identity from the session row (P2 A-7, P3 addition 4); the water state, caption and arrival metres persisted and revived (P3 additions 1, 3, 5; P5 A2, A4; Annex A.11 item 1); the outcome from the continuously written row (P3 addition 6); the soft-tap clause applied in `BeginHonorWalk` (P3 addition 7); the marks' anchor on the walk's first recorded fix (P5 A1); a summary that can open before the Honor step lands (P5 A3).
- **Owner decision 2:** either a per-walk staged stage Way (recommended), or the door's file-stamp refusal and Start's wait (P2 A-8, A-9).
- **The water haptic in the pocket** (P3 addition 2, R6's existing addition).
- **Files and caches:** no HTTP cache on the CDN clients (P1 A1); redirects kept on the CDN host (P1 A2, owner decision 6); the temp set under `noBackupFilesDir`, swept at launch (P2 A-6); `list()` skipping stage ids (P2 A-11); `retireMany` bumping the deletion counter (P2 A-10).
- **Kotlin and Compose mechanics, no visible change:** cancellation rethrown (P1 A3); a single-flight `load` (P1 A4); millisecond `fetchedAt` (P1 A5); list keys by id and position (P1 A6); wire integers as `Long` (P1 A7); work on `Dispatchers.IO` (P1 A8); kotlinx's decoding differences, recorded and not emulated (P1 A9); NFC-normalized name comparisons (P1 A10); the camera subscriptions only where marks exist (P5 A5).
- **Screens:** the route page as a second sheet (P4 A-1, owner decision 8); the catalog plate in parchment (P4 A-2); process-death restore of the catalog, route page, morning card flag and redraw notice (P4 A-3); the offline note's connectivity reading (P4 A-4); `Locale.US` digits (P4 A-5); "the day"'s weather fallback after a UI restart (P4 A-6); spoken names for unlabelled glyphs (P4 A-7); no maps line until 21-3 (P4 A-8); the stage formatter (P4 A-9, owner decision 7); Material glyph stand-ins (P5 A6).
- **The lexicon's read-only route lookup** (P3 addition 8, owner decision 11).

---

## Matched as shipped, and filed upstream

Android ports every one of these exactly as shipped (the house rule: iOS defects go upstream first, and Android folds in iOS's fix). They are filed as five themed issues, #119–#123 (2026-10-02); items that extend an earlier issue say so inside the new one.

| Issue | What | Cluster refs |
|---|---|---|
| [pilgrim-ios #119](https://github.com/walktalkmeditate/pilgrim-ios/issues/119) | Pilgrimage packages: a kill mid-commit leaves a half install; a failed Update removes the route; a Replace can race a download and erase the first one's marker; the guard leaves the commit open; kept walked stages and previews pile up; three ceilings for one 50 MiB | P2-D1, P2-D2, P2-D3 = P4-D4, P2-D11, P2-D10, P1-D5, P1-D9 |
| [pilgrim-ios #120](https://github.com/walktalkmeditate/pilgrim-ios/issues/120) | The ledger records position rather than distance, and summaries read its best: arrival credits the whole stage; the last position rather than the farthest; past summaries change; the arrival card's km disagrees with the summary's; the checkpoint is deleted before the link and record; an unreadable ledger is replaced; the Ways footer miscounts | P2-D7, P3-D3, P2-D6 = P5-D8, P5-D6, P2-D5, P2-D8, P2-D9 |
| [pilgrim-ios #121](https://github.com/walktalkmeditate/pilgrim-ios/issues/121) | The catalog and route page: a 7-day memory cache; "update ready" for any different release; one bad row fails the index; a preview 404 reads "out of reach"; pages that don't catch up with a download; a stage the package can't produce asks for a download; a dropped route has no door; the redraw notice lingers; a summary fallback ends on a separator | P1-D2, P1-D4 = P4-D8, P1-D7, P1-D8, P4-D1, P4-D2, P4-D3, P4-D7, P1-D6 = P4-D13, P4-D12, P4-D10 |
| [pilgrim-ios #122](https://github.com/walktalkmeditate/pilgrim-ios/issues/122) | Stage copy speaks of other walkers, voices and clocks that aren't there: "Walked their way", "their way, walked", the shared-walk lexicon, "they sat here", the voices disk-full line, the synthesized clock and "a quiet way", "1 hours" and hundredths of a foot, a dead "the whole stage" | P3-D2 = P5-D1, P3-D1, P3-D4, E-15, P1-D1 = P2-D4 = P4-D5, P4-D11, P4-D6, P5-D5 |
| [pilgrim-ios #123](https://github.com/walktalkmeditate/pilgrim-ios/issues/123) | On a stage walk: "record again" replaces the reflection without asking; a non-arrived summary offers another walk's reply; water during a sitting; marks over pins; marks before Start by accident; the card measures to the trail; screen-reader gaps; `installed()` writes from a read path; local names cut before filtering; an empty kicker | P5-D2, P5-D3, P3-D5, P5-D9, P5-D10, P5-D4 (E-16), P4-D9, P5-D7, P3-D6, P1-D3, P1-D10 |

**iOS PR #91** gets no issue: its candidates (PR91-D1, D2, D6, and D3 as a note) belong in a review comment on the open PR (Annex A.12), which is owner decision 12.

---

## Owner decisions (proposed)

Each has a recommendation. The default for every defect above is parity plus the upstream issue.

| # | Decision | Recommendation | Refs |
|---|---|---|---|
| 1 | **Schema 12's notice state.** A `honor_notices` table (walk id, kind, ref id, metres, fired at) plus `last_notice_seconds` and `arrival_walked_meters` on `honor_sessions`; or the plan's columns with a kind and a reserved fired-stamps column added | The table: right for water alone, and iOS PR #91 then needs no schema change whenever it merges. Never ship the plan's three caption columns with no kind | Annex A.5, A.14 item 1; P3 §12, decision 1; P5 C4 |
| 2 | **What `:tracker` walks when the package changes after the door.** (a) The plan: read the package at Start and revival, refuse Start with the "Gone" copy when the door's file stamp no longer matches, and make Start wait for an operation in flight. (b) Stage the stage Way per walk at Start from the copy the door loaded, discarded at finalize, never promoted | **(b).** It is the platform equivalent of iOS's in-memory capture: nothing differs for the walker, there's no refusal or wait, and it costs one write of up to 2 MB per stage walk. The wide guard stays for gap 2. This revisits the plan's "stages read from the package" call-out (confirmed 2026-10-02) on new evidence: iOS's view model holds `let way` from Begin, and its finish path says an Update may redraw the stage while the walk is on | P2 O-2, C-5, C-10 |
| 3 | **A launch repair for a half-committed install** | Parity: port the marker exactly, call `installed()` at launch, file the gap. The window is about a second, and a repair still couldn't fix an Update's mixed stages | P2 O-1, D-1 |
| 4 | **Replace's busy race** | Parity: the refused Replace deletes the marker, as iOS does; fold in iOS's fix if it lands before the gate | P2 O-3, C-6 |
| 5 | **The catalog cache's home** | `filesDir/Pilgrimages/`: iOS's copy is backed up and moves to a new phone, as `filesDir` does on a device transfer | P1 O1, C6 |
| 6 | **The CDN clients' redirects** | Stay on `cdn.jsdelivr.net`, as the share importer does; jsDelivr doesn't redirect these paths, so nothing visible changes | P1 O2 |
| 7 | **Numbers on the stage surfaces** | A formatter that prints as iOS's `StatsHelper` does ("24.2 km", "764 km", "1,419 m"), probed on both runtimes, used on this stage's surfaces only; Stage 21-1's surfaces stay as they are | P4 O-1, §5.3 |
| 8 | **The route page's presentation** | A second sheet, like the own-walk picker: Back returns to the catalog, a swipe-down closes both | P4 O-2, A-1 |
| 9 | **Marks against moment pins** | Put the marks in the Way pin manager, before the moment pins: the single layer iOS draws, where overlaps sort by screen height | P5 D1, C5 |
| 10 | **The `seal` icon's stand-in** | `Icons.Outlined.Verified` now; a drawn scalloped seal if the owner prefers | P5 D2 |
| 11 | **The lexicon's route lookup** | A read-only lookup: the same words, without `installed()`'s side effect of deleting an abandoned package | P3 decision 2, addition 8 |
| 12 | **iOS PR #91** | Leave one review comment before it merges (PR91-D1, D2, D6, with D3 as a note), and say whether #91 is meant for the iOS build Android 2.0.0 claims parity with. R2 decides mechanically either way; decision 1 makes a late fold-in free | Annex A.12–A.14 |

---

## Open questions iOS leaves open (iPhone checks for the combined pass)

- Whether the catalog's and route page's section headers render uppercase ("A pilgrimage", the group headers, "Stages") (P4 §15, U41).
- What the route page's back button shows, and what VoiceOver reads for it and for the `ellipsis` and `exclamationmark.triangle` glyphs (P4 §15).
- How marks and moment pins overlap in a dense town at zoom 15 and up (P5-D9).
- Whether E-16 is intended: the card's distance and "Show this place on the map" aim at the trail point, up to 1,247 m from the place's pin (P5-D4).

---

## P1. Wire and catalog: the stage importer, the open-pilgrimages index, and the catalog service

| | |
|---|---|
| iOS pin | `7c200bf` (v2.0.0) |
| Android HEAD | `0defff85` (branch `docs/stage21-2-plan`) |
| Feeds | U31 (stage importer, `PilgrimageError`, fixtures), U32 (catalog service); data facts to U33/U34 (file names, release and id checks, caps), U37 (grouping, card-line data) |
| Lenses | Behavior, UI/visual (strings only; P4 owns the screens), Data, Edge cases |


**What matters most:**
- Every live route (8 listed, 115 stage files, `v1.12.0`) passes iOS's decoder, bounds and identity chain as shipped. Two sit at an edge: `camino-norte` stage 7 carries exactly 400 marks (the cap), and its `summary` is exactly 600 characters (§12).
- The importer checks only the stage against the request. The `route.json`-against-catalog check and the stage-against-`route.json` check (count, name) live in the catalog's preview and the package manager. The plan puts them all in U31 (C3).
- iOS's "ephemeral" session is not cache-free. It answers a repeat index request from memory under the CDN's 7-day `max-age` (probed). Android's no-cache client is the better behaviour; record it and file it (A1, D2).
- Local names are cut to 20 before invalid keys are filtered, alphabetically. Live Guernica drops 10 names, `pt` among them (§5.3, C4).
- Decode failures fail the whole index; range failures drop one row. Kotlin's 32-bit `Int` would turn a row drop into a whole-index failure, so wire integers decode as `Long` (§4.3).
- The package session is 30 s / 300 s, not the catalog's 15 s / 30 s (C2). The 50 MiB total counts bytes received; the index's `bytes` is never compared.
- A route download that fills the disk says "…to save these voices" (D1). "update ready" means "different release", not "newer" (D4).
- iOS's catalog cache is backed up and transferred. The plan's `noBackupFilesDir` isn't parity; `filesDir` is (O1).

**iOS read in full at `7c200bf`:** `Pilgrim/Models/Honor/PilgrimageWayImporter.swift` (370), `PilgrimageCatalogService.swift` (379), `Way.swift` (222, the stage parts added by `517c160`..`8018a55`), `PilgrimagePackageManager.swift` (455; read for the URL, file-name, cap, fetch and identity facts U34 needs, P2 owns its lifecycle), `Pilgrim/Scenes/Honor/PilgrimageCatalogView.swift` (235; the model half and the load, P4 owns the screen), the load and preview parts of `PilgrimageRouteView.swift`, `PilgrimageLedger.progressLine`, and the helpers these call: `WayStore.isValidId` / `isValidRouteId` / `stageWayId`, `WayImporter`'s constants and `isoDate`, `WayGeometry.distanceMeters`, `OwnWalkWayBuilder.minLengthMeters`, `WayMediaDownloader.isDiskFull`, `HonorImportCopy`. Tests and fixtures in full: `UnitTests/Honor/PilgrimageWayImporterTests.swift` (347), `UnitTests/Honor/PilgrimageCatalogServiceTests.swift` (467), `UnitTests/Fixtures/PilgrimageFixtures.swift`, `UnitTests/Helpers/StubURLProtocol.swift`, and the five files under `UnitTests/Fixtures/Pilgrimage/`. For intent only: the design spec `docs/superpowers/specs/2026-09-03-honor-slice-two-pilgrimage-stages-design.md` §1.5–§2.5 and `docs/honor-slice-two-device-pass.md`.

**Android compared at `0defff85`:** `P/domain/honor/Way.kt`, `WayJson.kt`, `SwiftText.kt`, `OwnWalkWayBuilder.kt` (`MIN_LENGTH_METERS`); `P/data/honor/WayImporter.kt`, `WayStore.kt` (the id allow-list, `list()`), `WayMediaDownloadWorker.isDiskFull`; `P/data/collective/routes/CollectiveRouteCatalogService.kt`, `P/data/collective/CollectiveRepository.kt`; `P/di/NetworkModule.kt`; `app/src/main/res/xml/backup_rules.xml` and `data_extraction_rules.xml`; `T/domain/honor/WayCodecTest.kt`, `T/data/honor/WayImporterTest.kt`, `T/data/honor/ConnectionCountingServer.kt`, `T/data/honor/WayStoreTest.kt`.

**Live data:** `../open-pilgrimages` at tag `v1.12.0` (= local `675d4e3`, clean), and the live CDN index fetched on 2026-10-02 (byte-identical in content to the repo's `index.json`). Probes: `decoder.swift` (a scratch probe, not committed) (Foundation decoding, regexes, `prefix`, `==`, ISO dates, the ephemeral session's cache config), `cache.swift` + `server.py` (does the ephemeral session answer a repeat from memory), `live_probe.py` → `live_probe.out` (every live route and stage through iOS's rules). Probes ran on macOS 26 Foundation (Swift 6.3.3); iOS's deployment target is 18.0, which uses the same rewritten `JSONDecoder` (as S1 §2.4 notes).

**Owned by other clusters, cited only:** the package lifecycle, marker, rollback, guard, `retireMany` and the ledger (P2); the catalog and route screens' layout (P4). Facts those units need from the wire are in §2, §3.4, §10 and §11 here.

### 1. `PilgrimageError` and its copy

```swift
enum PilgrimageError: Error, Equatable {
    case notWalkable
    case incomplete
    case diskFull
    case walkInProgress
    case catalogUnreachable
    /// The tile store's 750-unique-pack ceiling refused a region.
    case mapTooLarge
}

enum PilgrimageCopy {
    static func line(for error: PilgrimageError) -> String {
        switch error {
        case .notWalkable: return "this route isn't walkable yet"
        case .incomplete: return "the download didn't finish"
        case .diskFull: return HonorImportCopy.line(for: .failed(.diskFull)) ?? "not enough space on this phone"
        case .walkInProgress: return "finish your walk first"
        case .catalogUnreachable: return "the routes are out of reach right now"
        case .mapTooLarge: return "more map than can be saved at once"
        }
    }
}
```
> Pilgrim/Models/Honor/PilgrimageWayImporter.swift:5-26@7c200bf

```swift
        case .failed(.diskFull): return "not enough space on this phone to save these voices"
```
> Pilgrim/Models/Honor/HonorImportReducer.swift:34@7c200bf

- Six cases. `mapTooLarge` arrived with slice three (21-3); U31 declares it now so the `when` is exhaustive and U34's seam compiles, with its copy, but nothing in 21-2 throws it.
- `diskFull` reuses the share importer's line, and `HonorImportCopy.line(for: .failed(.diskFull))` is never nil, so the `?? "not enough space on this phone"` fallback is dead code. **A route download that fills the disk reads "not enough space on this phone to save these voices"**: a pilgrimage surface speaks of voices (defect D1). Android reuses `R.string.honor_import_disk_full` (`app/src/main/res/values/strings.xml:1284@0defff85`) for this case, verbatim, so the two can never drift; do not add the dead fallback as a string.
- The copy is lowercase, no final period, as shown. `PilgrimageCopy` is the only formatter; every screen (catalog, route page, morning card's maps line later) goes through it.

**Which failure is which case** (every throw site at the pin):

| Case | Thrown when | Where |
|---|---|---|
| `notWalkable` "this route isn't walkable yet" | route id fails the slug rule, or stage index outside `0..<200`, before the bytes are read | `PilgrimageWayImporter.way(from:)` :169-171 |
| | stage body over 2 MiB, or it fails to decode | :172-174 |
| | the stage's `id`, `stage.routeId` or `stage.index` isn't the one asked for | :177-180 |
| | any bound in `validate` (§5.2) | :181 |
| | `departedAt` doesn't parse | :182 |
| | geometry under 20 m | :186 |
| | `route.json` over 512 KiB, fails to decode, or fails a bound, or its indices aren't exactly `0..<count` | `route(from:)` :258-273 |
| | the route file disagrees with the catalog entry (`id`, `stageCount`, `stages.count`) | `routePreview` :188-189; `stageRouteFile` (package) :316-317 |
| | a stage disagrees with `route.json` on `count` or `name` | `stageOneStage` (package) :348-349 |
| | `packageURL` returns nil (bad release, bad id, or a file name outside the closed set) | `routePreview` :181-183; `download` (package) :169-171, :181-184 |
| | `store.pilgrimageDirectory` nil at commit (bad id) | `commit` (package) :409 |
| `incomplete` "the download didn't finish" | a package fetch: status not 200, declared length over the file's cap, streamed bytes over it, or any transport error that isn't disk full | `PilgrimagePackageManager.fetch` :355-376 |
| | the package total passes 50 MiB, counted on bytes received | `checkBudget` :306-308 |
| | a second `download` while one runs; `remove` while one runs | `download` :151; `remove` :279 |
| | `update`'s download finished but `installed()` doesn't name the route | `update` :259 |
| | any non-`PilgrimageError` reaching `download`'s catch (a `createDirectory` failure included, disk full or not) | `download` :205-208 |
| | a commit write that fails and isn't disk full | `commit` :420-424 |
| `diskFull` | a package transport error `isDiskFull` recognizes (`URLError.cannotWriteToFile`, `NSFileWriteOutOfSpaceError`, directly or as the underlying error); a temp write; a commit write | `fetch` :374; `write` :378-384; `commit` :423 |
| `walkInProgress` "finish your walk first" | `download`, `replace`, `update`, `remove` entered while `isWalkActive()`; and again in `download` just before the commit | `download` :150, :195; `replace` :227; `update` :256; `remove` :275 |
| `catalogUnreachable` "the routes are out of reach right now" | the index fetch: status not 200, declared length over 256 KiB, streamed bytes over it, any transport error, **cancellation included** | `PilgrimageCatalogService.fetch` :223-239 |
| | the index fails to decode, or its `release` fails the tag rule | `parse` :282-283 |
| | `load` failed and no cache is readable | `load` :170 |
| | a route preview's fetch failed for any of the fetch reasons above (a 404 included) | `routePreview` :184 via `fetch` |
| `mapTooLarge` | a tile region (21-3 only) | not in this stage |

Call sites fold any foreign error: the catalog view's `load` maps to `?? .catalogUnreachable` (`PilgrimageCatalogView.swift:231@7c200bf`), the route page's install to `?? .incomplete` (`PilgrimageRouteView.swift:348@7c200bf`), its preview load to `?? .catalogUnreachable` (`:397`).

Two consequences worth a test each:
- A route preview that 404s, or whose body passes 512 KiB, reads "the routes are out of reach right now" (the catalog's fetch), while the same `route.json` failing in a download reads "the download didn't finish" (the package's fetch). A preview body that arrives but doesn't decode reads "this route isn't walkable yet" in both.
- The index fetch maps cancellation to `catalogUnreachable`, and `load` then falls back to the cache. The package fetch rethrows cancellation (`:371-372`), and `download` turns it into `phase = .idle` with no error (`:200-204`). Android rethrows `CancellationException` in both (structured concurrency; S1 §9 and Stage 5-C), which changes nothing visible: a cancelled catalog load belongs to a screen that has gone.

**Android today:** nothing; `PilgrimageError` is new in U31 (`P/data/honor/pilgrimage/PilgrimageModels.kt` in the plan's output structure). Shape it like `WayError`/`WayImportException` (`P/data/honor/WayImporter.kt:57-61@0defff85`): an enum plus an exception that carries no message, since nothing from a package may reach a log.

```kotlin
/** iOS `WayError` (`WayImporter.swift:3@7c200bf`). The importer raises the first three; [DISK_FULL] is the media download's. */
enum class WayError { NOT_FOUND, RETURNED_TO_TRAIL, UNAVAILABLE, DISK_FULL }

/** A [WayError], thrown. It carries no message: nothing from a share may reach a log. */
class WayImportException(val error: WayError) : Exception()
```
> app/src/main/java/org/walktalkmeditate/pilgrim/data/honor/WayImporter.kt:57-61@0defff85

### 2. URLs, file names, and the two identifier rules

```swift
    static let indexURL = URL(string: "https://cdn.jsdelivr.net/gh/walktalkmeditate/open-pilgrimages@main/index.json")!
    private static let packageBase = "https://cdn.jsdelivr.net/gh/walktalkmeditate/open-pilgrimages"
```
> Pilgrim/Models/Honor/PilgrimageCatalogService.swift:86-87@7c200bf

```swift
    static func packageURL(release: String, routeId: String, file: String) -> URL? {
        guard isValidRelease(release), WayStore.isValidRouteId(routeId),
              file.range(of: "\\A(route\\.json|stage-[0-9]{2,3}\\.json)\\z", options: .regularExpression) != nil,
              let url = URL(string: "\(packageBase)@\(release)/routes/\(routeId)/ways/\(file)") else { return nil }
        return url
    }

    static func isValidRelease(_ release: String) -> Bool {
        release.range(of: "\\Av[0-9]+\\.[0-9]+\\.[0-9]+\\z", options: .regularExpression) != nil
    }
```
> Pilgrim/Models/Honor/PilgrimageCatalogService.swift:137-146@7c200bf

```swift
    static func stageFileName(_ index: Int) -> String {
        String(format: index < 100 ? "stage-%02d.json" : "stage-%03d.json", index)
    }
```
> Pilgrim/Models/Honor/PilgrimagePackageManager.swift:78-80@7c200bf

```swift
    static func isValidId(_ id: String) -> Bool {
        id.range(of: "\\A(share:[A-Za-z0-9_-]{10}|walk:[0-9A-Fa-f-]{36}|pilgrimage:[a-z0-9-]{1,64}:[0-9]{1,3})\\z",
                 options: .regularExpression) != nil
    }
// …
    static func isValidRouteId(_ id: String) -> Bool {
        id.range(of: "\\A[a-z0-9-]{1,64}\\z", options: .regularExpression) != nil
    }

    static func stageWayId(routeId: String, stageIndex: Int) -> String {
        "pilgrimage:\(routeId):\(stageIndex)"
    }
```
> Pilgrim/Models/Honor/WayStore.swift:56-69@7c200bf

**The shapes, exactly:**

| What | Shape | Example |
|---|---|---|
| Index | `https://cdn.jsdelivr.net/gh/walktalkmeditate/open-pilgrimages@main/index.json` (a constant; never `@v1`, never a tag) | as shown |
| Package file | `https://cdn.jsdelivr.net/gh/walktalkmeditate/open-pilgrimages@<release>/routes/<routeId>/ways/<file>` | `…@v1.7.0/routes/camino-frances/ways/stage-00.json` (the iOS test's string) |
| Stage file name | `stage-%02d.json` below 100, `stage-%03d.json` from 100 | `stage-07.json`, `stage-99.json`, `stage-100.json`, `stage-199.json` (probed) |
| Stage Way id | `pilgrimage:<routeId>:<index>`, index in plain decimal, no padding | `pilgrimage:camino-frances:7` |
| Route preview file | `<Application Support>/Pilgrimages/route-<routeId>-<release>.json` | `route-camino-frances-v1.12.0.json` (§9) |
| Catalog cache file | `<Application Support>/Pilgrimages/catalog.json` | (§8) |

**The rules** (all anchored `\A…\z`, so a trailing newline fails; all ASCII classes; probed in `decoder.swift`):
- Release: `\Av[0-9]+\.[0-9]+\.[0-9]+\z`. `v1.12.0` passes; `v01.2.3` passes (leading zeros allowed); `main`, `v1.7`, `1.7.0`, `V1.2.3`, `v1.2.3.4`, `v1.12.0\n` and Arabic-Indic digits fail. The survey's `v\d+.\d+.\d+` is wrong on both counts: the dots are escaped, and the class is `[0-9]`, not `\d`.
- Route id: `\A[a-z0-9-]{1,64}\z`. `-` and `a--b` pass (no kebab-case structure is enforced); 65 characters, uppercase, `ç`, `../etc/passwd` fail.
- File name: `\A(route\.json|stage-[0-9]{2,3}\.json)\z`. `stage-0.json` and `stage-1000.json` fail. This is a defence in depth: every caller builds the name from `stageFileName` or the literal `"route.json"`.
- Way id: the store's allow-list; a stage index of up to three digits, so `pilgrimage:x:999` is a valid id though no stage can have it (the importer refuses indices outside `0..<200`).

**Where each rule is checked before a URL or a path is built:**

| Check | Before | Site |
|---|---|---|
| release | any package URL | `packageURL` :138 |
| release | the preview's file path | `routePreviewURL` :197 |
| release | accepting the index at all (else `catalogUnreachable`) | `parse` :283 |
| release | trusting `release.txt` when deciding what is installed | `PilgrimagePackageManager.installed()` :95-96 (P2) |
| route id | any package URL; the preview path; the package folder; the marker | `packageURL` :138; `routePreviewURL` :197; `WayStore.pilgrimageDirectory` :81; `replacingMarker` :121 (P2) |
| route id | listing an index row | `parse` :285 |
| route id | accepting `route.json` (its own `id`) | `PilgrimageWayImporter.route(from:)` :261 |
| route id + stage index | decoding a stage file at all | `way(from:)` :169 |
| file name | any package URL | `packageURL` :139 |
| Way id | every store read and write | `WayStore.isValidId` (own-walk spec A) |

Android must use the literal classes `[0-9]` and `[a-z]`, never `\d` or `\w`: Android's `java.util.regex` is ICU-backed, and the meaning of `\d` there is not something to rely on. `Regex.matches` anchors the whole input as `\A…\z` does, as `WayStore.isValidId` already relies on (`P/data/honor/WayStore.kt:565,574-575@0defff85`). The plan's Stage 5-G lesson applies: U32 pins `packageURL`'s exact string in a unit test for `route.json`, `stage-07.json` and `stage-107.json` (the plan's own list), plus iOS's three nil cases (`../etc`, release `main`, file `../../secret`). Build the URL as the same plain string and parse it with `toHttpUrl()`; don't assemble it from `HttpUrl.Builder` path segments, where the `@` inside `open-pilgrimages@v1.12.0` is one segment and an encoder change would move it.

`release.txt` holds the bare tag, written as `Data(plan.release.utf8)` (`PilgrimagePackageManager.swift:419@7c200bf`): no newline, no BOM. Since the release rule is anchored, a trailing newline written by Android would make `installed()` treat the route as not installed (U34, P2).

### 3. The fetches: sessions, caps, status, redirects, caching

#### 3.1 The catalog's session and fetch

```swift
    nonisolated private static let defaultSession: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 15
        config.timeoutIntervalForResource = 30
        return URLSession(configuration: config)
    }()
```
> Pilgrim/Models/Honor/PilgrimageCatalogService.swift:110-115@7c200bf

```swift
    static let maxIndexBytes = 256 * 1024
    static let cacheLifetime: TimeInterval = 24 * 3600
    static let maxDistanceKm = 10_000.0
    static let maxStageCount = 200
    static let maxPackageBytes = 50 * 1024 * 1024
    /// A stage cannot plausibly carry fifty curated places; anything beyond
    /// this is a broken report, not a rich route.
    static let maxPlacesPerStage = 50.0
```
> Pilgrim/Models/Honor/PilgrimageCatalogService.swift:89-96@7c200bf

```swift
    nonisolated private static func fetch(_ url: URL, cap: Int, session: URLSession) async throws -> Data {
        do {
            let (bytes, response) = try await session.bytes(from: url)
            guard let http = response as? HTTPURLResponse, http.statusCode == 200,
                  http.expectedContentLength <= Int64(cap) else { throw PilgrimageError.catalogUnreachable }
            var buffer = Data()
            for try await byte in bytes {
                buffer.append(byte)
                if buffer.count > cap { throw PilgrimageError.catalogUnreachable }
            }
            return buffer
        } catch let error as PilgrimageError {
            throw error
        } catch {
            throw PilgrimageError.catalogUnreachable
        }
    }
```
> Pilgrim/Models/Honor/PilgrimageCatalogService.swift:223-239@7c200bf

- **One ephemeral session for the catalog and its previews**, 15 s request (an idle timeout, reset by each byte) and 30 s resource (the whole fetch), no retry, no delegate. The same numbers as the share importer (S1 §3.2), and the same OkHttp shape: connect 15 s, read 15 s, write 15 s, call 30 s, `retryOnConnectionFailure(false)`, no cache. Reuse `WayImporter.httpClient`'s builder chain, with its host rule pointed at the CDN (§3.3).
- **Status:** exactly 200. Anything else (a 304 included, a 206, a 404) is `catalogUnreachable`. No status is special-cased.
- **Declared length** (`expectedContentLength`, -1 when unknown) is checked before the body: `<= cap`. **Streamed bytes** are counted as they arrive, and the fetch throws as soon as the buffer passes the cap (`> cap`), so exactly `cap` bytes pass and `cap + 1` fail. Both caps apply to the **decoded** body (see compression below).
- **The index cap is 256 KiB** (262,144 bytes). The live index is 9,423 bytes.
- **Previews use the same fetch with the route cap**, 512 KiB (`routePreview` :184). The live `route.json` files run 1,359 to 8,284 bytes.
- Nothing is logged: no `print`, `os_log` or `Logger` call exists in `PilgrimageCatalogService.swift`, `PilgrimageWayImporter.swift` or `PilgrimagePackageManager.swift` at the pin (read in full). Confirmed: the plan's "nothing from the fetch is logged" is iOS's behaviour.

#### 3.2 Compression and the declared length (probed)

The CDN compresses. For the index, a plain request gets `content-length: 9423`; one sending `Accept-Encoding: gzip, br` gets `content-encoding: br` and `content-length: 1949` (curl, 2026-10-02). URLSession asks for `gzip, deflate` over plain HTTP and adds `br` over HTTPS, and decodes transparently. Probe (`gz2.swift`): a gzip body streamed in chunks reports **`expectedContentLength = -1`** although the `Content-Length` header (the compressed size) is present, and the stream yields the decoded bytes. So against the live CDN iOS's declared-length check is inert, and the streamed cap on decoded bytes is the guard that holds. OkHttp behaves the same way: it asks for `gzip` itself, decodes, and drops `Content-Length`, so `body.contentLength()` is -1. In the unit tests (no encoding) the declared length is real on both, and iOS's `testAnIndexBiggerThanTheCapIsNeverBuffered` (declared) and `testAnIndexThatNeverDeclaresItsLengthIsRefusedByTheCapCountedWhileStreaming` (undeclared) pin both paths. Port both.

#### 3.3 Redirects

No delegate, so URLSession follows any redirect with its default policy, to any host; App Transport Security (no exception declared at the pin, S1 §3.2) blocks a hop to plain `http`. jsDelivr doesn't redirect these URLs (a missing package file is a plain 404, checked 2026-10-02). Android's share importer refuses a redirect off its host, a dated R6 addition (`P/data/honor/WayImporter.kt:161-199@0defff85`). For the catalog and package clients, two choices are honest:
- follow OkHttp's default (`followRedirects(true)`, any host; cleartext is already refused by the platform's default network security for `targetSdk` 36, as ATS refuses it), which is iOS's behaviour; or
- stay on `cdn.jsdelivr.net`, mirroring the share importer's addition.

Recommendation: stay on `cdn.jsdelivr.net`, using the existing `StaysOnTheWalkHost` interceptor with the CDN as its base (rename it for its new job). It costs nothing on the live CDN, matches the share client's posture, and is one more line in the gate's additions list. Owner decision O2 if anyone prefers strict parity.

#### 3.4 The package's session and fetch (facts for U34; P2 owns the lifecycle)

```swift
    nonisolated private static let defaultSession: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 300
        return URLSession(configuration: config)
    }()
```
> Pilgrim/Models/Honor/PilgrimagePackageManager.swift:58-63@7c200bf

```swift
    nonisolated private static func fetch(url: URL, cap: Int, session: URLSession) async throws -> Data {
        do {
            let (bytes, response) = try await session.bytes(from: url)
            // Checked before draining: an oversized declared length must not
            // cost a full download first.
            guard let http = response as? HTTPURLResponse, http.statusCode == 200,
                  http.expectedContentLength <= Int64(cap) else { throw PilgrimageError.incomplete }
            var buffer = Data()
            buffer.reserveCapacity(min(cap, 256 * 1024))
            for try await byte in bytes {
                buffer.append(byte)
                if buffer.count > cap { throw PilgrimageError.incomplete }
            }
            return buffer
        } catch let error as PilgrimageError {
            throw error
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw WayMediaDownloader.isDiskFull(error) ? PilgrimageError.diskFull : PilgrimageError.incomplete
        }
    }
```
> Pilgrim/Models/Honor/PilgrimagePackageManager.swift:355-376@7c200bf

```swift
    nonisolated private static func checkBudget(_ bytes: Int, cap: Int) throws {
        guard bytes <= cap else { throw PilgrimageError.incomplete }
    }
```
> Pilgrim/Models/Honor/PilgrimagePackageManager.swift:306-308@7c200bf

- **Different session, different timeouts:** 30 s request (idle), **300 s** resource, per file. Not the catalog's 15/30. OkHttp: connect 30 s, read 30 s, write 30 s, call 300 s, no retry, no cache. The plan's U34 doesn't name the numbers; the spec must.
- **Caps, per file:** `route.json` 512 KiB, each stage 2 MiB, declared length first (`<= cap`), then streamed (`> cap` throws). The importer re-checks `data.count <= maxStageBytes` / `maxRouteBytes` on the bytes it is handed (`PilgrimageWayImporter.swift:172,258`), so a stage is capped twice; keep both.
- **The 50 MiB total** (52,428,800 bytes) is counted **only on bytes received** (`data.count` of each body, `route.json` included), summed across files, checked after `route.json` and after each stage (`<= cap` passes). The index's `ways.bytes` is never compared with what arrives. The index row check is `0..<maxPackageBytes`, so a row declaring exactly 50 MiB is dropped from the catalog while a download of exactly 50 MiB would pass: an asymmetry with no live effect (largest route: 1,385,811 bytes).
- **Error mapping** differs from the catalog's: `.incomplete` for status, caps and transport; `.diskFull` when `isDiskFull` recognizes the error; cancellation passes through. Android's `isDiskFull` is `WayMediaDownloadWorker.isDiskFull(error: Throwable)` (`P/data/honor/WayMediaDownloadWorker.kt:332@0defff85`), which reads `ENOSPC` through the cause chain; with the body buffered in memory, a disk-full can only come from the temp write or the commit, as on iOS.
- **What is fetched:** `route.json` first, then `stage-NN.json` for `index in 0..<route.stageCount`, in order, one at a time (`download` :176-190). `report.json` and `cover` are never fetched. Phase total is `entry.stageCount + 1`; `done` is 1 after `route.json`, `index + 2` after each stage.

#### 3.5 HTTP caching (probed): iOS's ephemeral session has a memory cache

The CDN's headers (2026-10-02):
- `index.json` on `@main`: `cache-control: public, max-age=604800, s-maxage=43200`, a weak `ETag`, `vary: Accept-Encoding`.
- a package file on `@v1.12.0`: `cache-control: public, max-age=31536000, s-maxage=31536000, immutable`.

`URLSessionConfiguration.ephemeral` is not cache-free. Probe (`decoder.swift`): its `urlCache` is a memory-only `URLCache` of 512,000 bytes (disk 0), and `requestCachePolicy` is `useProtocolCachePolicy` (raw 0). Probe (`cache.swift` against `server.py`, which sends the index's `Cache-Control`): three `bytes(from:)` calls on one ephemeral session reached the server **once**; the second and third were answered from memory, as was a redirect's target. This settles S1's open question 1 (the same holds for `tour.json`'s `max-age=3600`).

What that means on iOS, within one process:
- A catalog load after the 24 h app cache expires is answered from URLSession's memory for up to 7 days after the first fetch, with no network, so a long-lived process doesn't see a new release (defect D2). iOS apps are rarely alive 24 h, so this is rare.
- A forced Retry after a failure goes to the network, since failures aren't cached.
- Package files are immutable per tag, so a cached copy is always correct.

Android: OkHttp has no cache unless one is set (`WayImporter.httpClient` asserts `client.cache == null`, `T/data/honor/WayImporterTest.kt:624-633@0defff85`), so every catalog load past 24 h, and every Retry, goes to the network. That is the behaviour iOS's own comment intends ("A branch ref refreshes on jsDelivr's own 12 h cycle, and the 24 h cache below sits on top of it", :80-85). Record it at the gate as a platform difference: Android doesn't emulate URLSession's memory cache. No `CacheControl.FORCE_NETWORK` is needed (there is no cache to bypass), but adding it to the index request, as `CollectiveRouteCatalogService` does (`P/data/collective/routes/CollectiveRouteCatalogService.kt:137-144@0defff85`), keeps that true if someone later gives the client a cache.

### 4. Decoding strictness

All three files go through a default `JSONDecoder()` into synthesized `Decodable` structs: unknown keys are ignored, a `let x: T?` is nil when the key is absent or `null`, and a `let x: T` fails the whole decode when absent, `null`, or the wrong type. The index and `route.json` use no date or key strategies; the stage file's `departedAt` is a `String` parsed afterwards by `WayImporter.isoDate` (§5.4).

#### 4.1 Measured (`decoder.swift`, macOS 26 Foundation; kotlinx rows from S1 §2.4 and `WayImporterTest`'s pinned differences)

| JSON input | Foundation | kotlinx (house `Json`: `ignoreUnknownKeys`, `explicitNulls = false`) | Fields that meet it |
|---|---|---|---|
| unknown key | ignored | ignored | live: see §12 |
| optional absent or `null` | nil | null | every `T?` below |
| required absent or `null` | fails the decode | fails, **if the property has no default** (S1 trap 1) | every `T` below |
| `Int` given `1.0` | `1` | **fails** (`WayImporterTest` "kotlinx refuses an integer written with a fraction") | `ways.stageCount`, `ways.bytes`, `route.stageCount`, `stages[].index`, `stage.index`, `stage.count`, `sitMinutes` |
| `Int` given `1e2` | `100` | `100` (measured in U28) | same |
| `Int` given `1.5` | fails | fails | same |
| `Int` or `Double` given `"5"` | fails | **reads 5** (S1, measured) | every number |
| `Double` given `1e400` | **fails the decode** | reads `Infinity`; the range checks then refuse it | every `Double` |
| `Int` given `99999999999999999999` | fails | fails | every `Int` |
| `Int` given `3000000000` | `3000000000` (Swift `Int` is 64-bit) | **fails for a Kotlin `Int`**; reads for `Long` | see 4.3 |
| `String` given `5`, `true`, `null` | fails | fails | every `String` |
| `[String: String]` with a `null` or numeric value | fails | fails | `names`, index `name` |
| `[String]` with a `null` element | fails | fails | `warnings`, `sections` |
| repeated key | **first** value wins (measured: `{"a":1,"a":2}` → 1) | **last** wins (`WayImporterTest` "a repeated key keeps its last value") | any |
| leading BOM | accepted | expected to fail (the strict UTF-8 decode yields U+FEFF, which kotlinx's lexer doesn't skip; not measured) | whole file |
| `"\ud800"` (lone surrogate escape) | fails | expected to be accepted (not measured) | any string |
| trailing text after the object | fails | fails | whole file |
| `NaN` | fails | fails | any |

What reaches the app in practice: the dataset is written by `JSON.stringify`, so no integral number is written with a fraction, no number is quoted, no key repeats, no BOM. The probe confirms it for every live file: 0 float literals in `Int` fields, 0 repeated keys (§12). The differing rows matter only for hand-written fixtures and hostile files. Two of them change an outcome:
- **The index:** a decode failure anywhere fails the whole catalog (`catalogUnreachable`, then the cache), while a range failure drops one row. `"distanceKm": 1e400` fails iOS's whole index but only drops the row on Android (`Infinity` fails `isFinite`). `"stageCount": 33.0` passes iOS and fails Android's whole index. Hostile-only; pin both in tests as S1 did, don't emulate.
- **The stage and route files:** every failure is `notWalkable` either way, so the rows above change nothing there.

#### 4.2 Required and optional, per struct (the Kotlin wire models must match: no defaults on required fields)

| Struct | Required | Optional |
|---|---|---|
| Index file | `release`, `routes` | `pilgrimages` |
| Index route row | `id`, `name` (`[String: String]`), `distanceKm` | `region`, `country`, `tradition`, `ways` |
| Index `ways` | `stageCount`, `bytes` | `placesPerStage`, `sparse` |
| Index pilgrimage | `id`, `name` (map), `sections` (`[String]`) | none (`kind`, `circular`, `distanceKm`, `stageCount`, `stats` are ignored) |
| `route.json` | `id`, `name`, `distanceKm`, `stageCount`, `stages` | `names`, `country`, `region`, `tradition`, `summary` |
| `route.json` stage row | `index`, `name`, `distanceKm`, `gainMeters`, `hours`, `difficulty` | none |
| Stage file | `id`, `title`, `departedAt`, `route`, `totalDistanceMeters`, `theirActiveSeconds`, `moments`, **`marks`**, `stage` | `tzIdentifier` |
| Route point | `lat`, `lon`, `t` | `alt` |
| Moment | `id`, `frac`, `kind` (a `String`) | `label`, `icon`, `text`, `names`, `sitMinutes`, `at`, `pin` |
| Mark | `id`, `kind` (a `String`), `name`, `at`, `frac`, `offLineMeters` | none |
| Stage block | all 14: `routeId`, `index`, `count`, `name`, `theme`, `narrative`, `closing`, `warnings`, `distanceKm`, `gainMeters`, `hours`, `difficulty`, `start`, `end` | none |
| Hours | `min`, `max` | none |
| Place | `name`, `at` | none |
| Coordinate | `lat`, `lon` | none |

```swift
        struct Moment: Decodable {
            let id: String
            let frac: Double
            let kind: String
            let label: String?
            let icon: String?
            let text: String?
            let names: [String: String]?
            let sitMinutes: Int?
            let at: Coordinate?
            let pin: Coordinate?
        }
        struct Mark: Decodable {
            let id: String
            let kind: String
            let name: String
            let at: Coordinate
            let frac: Double
            let offLineMeters: Double
        }
```
> Pilgrim/Models/Honor/PilgrimageWayImporter.swift:89-108@7c200bf

`Moment.kind` and `Mark.kind` decode as `String`, so a new kind never fails the decode; it is skipped or dropped after validation (§5.3). The moment's `label` and `icon` are optional on the wire (the schema requires them; iOS doesn't). `marks` is required: a stage file without it is not walkable.

#### 4.3 Integer width: a trap for the index

Swift's `Int` is 64-bit. A Kotlin `Int` field fails the decode on a value past 2,147,483,647, which on the index fails the whole catalog where iOS would drop one row (`ways.bytes` of 3,000,000,000 is "bytes ≥ 50 MiB": a row drop on iOS). So the index's `stageCount` and `bytes` decode as `Long` and are range-checked before any narrowing. Do the same for the route and stage files' integers (`stageCount`, `index`, `count`, `sitMinutes`), as `TourManifest` already does for its own (S1 §2.4), and narrow only after the bound passes. The importer's rule "every number range-checked before any `Int(_:)` conversion" (`PilgrimageWayImporter.swift:52-53`) becomes "before any `toInt()`" on Android, where a missed bound saturates instead of trapping (S1 trap 6).

### 5. A stage file becomes a `Way` (`PilgrimageWayImporter.way(from:routeId:stageIndex:)`)

#### 5.1 The order of the checks

```swift
    static func way(from data: Data, routeId: String, stageIndex: Int) throws -> Way {
        guard WayStore.isValidRouteId(routeId), (0..<maxStageCount).contains(stageIndex) else {
            throw PilgrimageError.notWalkable
        }
        guard data.count <= maxStageBytes, let file = try? JSONDecoder().decode(StageFile.self, from: data) else {
            throw PilgrimageError.notWalkable
        }
        // The file must be the one that was asked for: a package is only ever
        // as trustworthy as the path it came from.
        let expectedId = WayStore.stageWayId(routeId: routeId, stageIndex: stageIndex)
        guard file.id == expectedId, file.stage.routeId == routeId, file.stage.index == stageIndex else {
            throw PilgrimageError.notWalkable
        }
        guard validate(file) else { throw PilgrimageError.notWalkable }
        guard let departed = WayImporter.isoDate(file.departedAt) else { throw PilgrimageError.notWalkable }

        let route = file.route.map { WayPoint(lat: $0.lat, lon: $0.lon, alt: $0.alt, t: $0.t) }
        let geometry = WayGeometry(route: route)
        guard geometry.totalMeters >= OwnWalkWayBuilder.minLengthMeters else { throw PilgrimageError.notWalkable }
```
> Pilgrim/Models/Honor/PilgrimageWayImporter.swift:168-186@7c200bf

In order, every one `notWalkable`:
1. The caller's route id passes the slug rule and the index is in `0..<200`, before the bytes are touched.
2. The body is at most 2 MiB (2,097,152 bytes; `<=`), and decodes.
3. **Identity against the request:** `file.id == "pilgrimage:<routeId>:<stageIndex>"`, `file.stage.routeId == routeId`, `file.stage.index == stageIndex`. Plain string and integer equality.
4. `validate` (5.2).
5. `departedAt` parses (5.4).
6. The route's haversine length (`WayGeometry`, r = 6,371,000 m) is at least `OwnWalkWayBuilder.minLengthMeters` = 20.0 (`>=`). Android: `OwnWalkWayBuilder.MIN_LENGTH_METERS` (`P/domain/honor/OwnWalkWayBuilder.kt:37@0defff85`) and `WayGeometry` (own-walk spec A), already ported.

The importer does **not** compare the stage with `route.json`. `stage.count == route.stageCount` and `stage.name == route.stages[i].name` are the package manager's (`stageOneStage`, :348-349, quoted in §11), as is `route.json` against the catalog entry. The plan's U31 puts "each stage against … count and name" in the importer; that is a placement correction, not a behaviour one (C3).

#### 5.2 `validate`: every bound, in iOS's order

```swift
    private static func validate(_ file: StageFile) -> Bool {
        func inLat(_ v: Double) -> Bool { v.isFinite && (-90...90).contains(v) }
        func inLon(_ v: Double) -> Bool { v.isFinite && (-180...180).contains(v) }
        func inFrac(_ v: Double) -> Bool { v.isFinite && (0...1).contains(v) }

        guard file.route.count >= 2, file.route.count <= WayImporter.maxRoutePoints,
              file.moments.count <= WayImporter.maxEncounters,
              file.marks.count <= maxMarks else { return false }
        guard file.route.allSatisfy({ point in
            inLat(point.lat) && inLon(point.lon) && point.t.isFinite && (0...WayImporter.maxActiveDurationSeconds).contains(point.t)
                && (point.alt.map { $0.isFinite && abs($0) < WayImporter.maxAltitudeMeters } ?? true)
        }) else { return false }
        for i in file.route.indices.dropFirst() where file.route[i].t < file.route[i - 1].t { return false }
        guard file.totalDistanceMeters.isFinite, file.totalDistanceMeters >= 0,
              file.theirActiveSeconds.isFinite,
              (0...WayImporter.maxActiveDurationSeconds).contains(file.theirActiveSeconds) else { return false }

        for moment in file.moments {
            guard inFrac(moment.frac) else { return false }
            if let at = moment.at, !(inLat(at.lat) && inLon(at.lon)) { return false }
            if let pin = moment.pin, !(inLat(pin.lat) && inLon(pin.lon)) { return false }
            if let minutes = moment.sitMinutes, !(0...WayImporter.maxRestMinutes).contains(minutes) { return false }
        }
        for mark in file.marks {
            guard inFrac(mark.frac), inLat(mark.at.lat), inLon(mark.at.lon),
                  mark.offLineMeters.isFinite, (0...100_000).contains(mark.offLineMeters) else { return false }
        }

        let stage = file.stage
        guard (0..<maxStageCount).contains(stage.index),
              (1...maxStageCount).contains(stage.count),
              stage.index < stage.count,
              isSaneDistance(stage.distanceKm), isSaneGain(stage.gainMeters), isSaneHours(stage.hours),
              stage.warnings.count <= maxWarnings,
              inLat(stage.start.at.lat), inLon(stage.start.at.lon),
              inLat(stage.end.at.lat), inLon(stage.end.at.lon) else { return false }
        return true
    }

    private static func isSaneDistance(_ km: Double) -> Bool { km.isFinite && (0...maxDistanceKm).contains(km) }
    private static func isSaneGain(_ meters: Double) -> Bool { meters.isFinite && (0...maxGainMeters).contains(meters) }
    private static func isSaneHours(_ hours: StageFile.Hours) -> Bool {
        hours.min.isFinite && hours.max.isFinite
            && (0...maxHours).contains(hours.min) && (0...maxHours).contains(hours.max) && hours.max >= hours.min
    }
```
> Pilgrim/Models/Honor/PilgrimageWayImporter.swift:304-348@7c200bf

```swift
    static let maxRoutePoints = 2000
    static let maxEncounters = 200
// …
    static let maxAltitudeMeters = 100_000.0
// …
    static let maxRestMinutes = 1440
// …
    static let maxActiveDurationSeconds: Double = 7 * 24 * 3600
// …
    static let maxLabelCharacters = 80
    static let maxIconCharacters = 64
```
> Pilgrim/Models/Honor/WayImporter.swift:7-22@7c200bf

| # | Field | Rule (refuse the stage unless) | Comparison | Constant |
|---|---|---|---|---|
| 1 | `route` | 2 ≤ count ≤ 2000 | inclusive both | `WayImporter.maxRoutePoints` |
| 2 | `moments` | count ≤ 200, **every kind counted** | inclusive | `WayImporter.maxEncounters` |
| 3 | `marks` | count ≤ 400, **every kind counted** | inclusive | `maxMarks` |
| 4 | each point | lat finite in [-90, 90]; lon finite in [-180, 180]; `t` finite in [0, 604,800] | inclusive | `maxActiveDurationSeconds` = 7 × 24 × 3600 |
| 5 | each point `alt` (if present) | finite and \|alt\| **<** 100,000 | strict | `maxAltitudeMeters` |
| 6 | point order | `t[i] >= t[i-1]` (equal allowed) | | |
| 7 | `totalDistanceMeters` | finite, ≥ 0 (no upper bound, and then ignored: §5.4) | | |
| 8 | `theirActiveSeconds` | finite in [0, 604,800] | inclusive | |
| 9 | each moment, **every kind** | `frac` finite in [0, 1]; `at` and `pin`, when present, on Earth; `sitMinutes`, when present, in [0, 1440] | inclusive | `maxRestMinutes` |
| 10 | each mark, **every kind** | `frac` in [0, 1]; `at` on Earth; `offLineMeters` finite in [0, 100,000] | inclusive | literal `100_000` |
| 11 | `stage.index` | in [0, 200) | half-open | `maxStageCount` |
| 12 | `stage.count` | in [1, 200] | inclusive | |
| 13 | | `stage.index < stage.count` | strict | |
| 14 | `stage.distanceKm` | finite in [0, 10,000] | inclusive | `maxDistanceKm` |
| 15 | `stage.gainMeters` | finite in [0, 30,000] | inclusive | `maxGainMeters` |
| 16 | `stage.hours` | both finite, both in [0, 100], `max >= min` | inclusive | `maxHours` |
| 17 | `stage.warnings` | count ≤ 20 (21 refuses the stage; nothing is cut) | inclusive | `maxWarnings` |
| 18 | `stage.start.at`, `stage.end.at` | on Earth | inclusive | |

Not validated, only capped (5.3): every string, the moment's `id`, the mark's `id` and `name`, `stage.routeId` (already equal to the slug), `difficulty` (any string, `""` included), `tzIdentifier`. Not validated at all: `stage.name` against anything (that is the package's check), mark or moment frac order, duplicate ids, a moment's `at` against the line.

Each row needs its own rejecting test on Android, since Kotlin saturates where Swift would trap (S1 trap 6). iOS's own test covers rows 4, 9, 10, 12, 14, 16 (`testOutOfRangeStageFieldsAreNotWalkable`, eleven cases) and 3 (`testTooManyMomentsOrMarksIsNotWalkable`); the plan's U31 adds gain over 30,000, `offLineMeters` over 100,000, and hours with `max < min`. Add: a 21st warning, a point `alt` of exactly 100,000 (refused) and 99,999.9 (kept), `t` going backwards, a `stage.index` equal to `stage.count`, a refused bound on a moment of an **unknown kind** (it is validated though it is skipped), and the 1,000 m-scale route under 20 m.

#### 5.3 Strings, local names, moments, marks, and the stage block

```swift
    private static func capped(_ value: String, _ max: Int) -> String { String(value.prefix(max)) }

    private static func trimmed(_ raw: String?, _ max: Int) -> String? {
        guard let trimmed = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else { return nil }
        return String(trimmed.prefix(max))
    }

    /// Language codes come from an untrusted file and end up as dictionary
    /// keys a card reads; both halves are bounded and the map itself capped.
    private static func localNames(_ raw: [String: String]?) -> [String: String]? {
        guard let raw, !raw.isEmpty else { return nil }
        let pairs = raw.sorted { $0.key < $1.key }.prefix(maxLocalNames).compactMap { key, value -> (String, String)? in
            guard key.range(of: "\\A[a-z]{2,3}\\z", options: .regularExpression) != nil else { return nil }
            guard let name = trimmed(value, maxStageNameCharacters) else { return nil }
            return (key, name)
        }
        return pairs.isEmpty ? nil : Dictionary(uniqueKeysWithValues: pairs)
    }
```
> Pilgrim/Models/Honor/PilgrimageWayImporter.swift:352-369@7c200bf

```swift
    static let maxStageBytes = 2 * 1024 * 1024
    static let maxRouteBytes = 512 * 1024
    /// A town stage can carry over a hundred service points; four hundred is
    /// far past anything the dataset produces and still bounds the parse.
    static let maxMarks = 400
    static let maxThemeCharacters = 80
    static let maxNarrativeCharacters = 2_000
    static let maxClosingCharacters = 400
    static let maxWarningCharacters = 300
    static let maxStageNameCharacters = 120
    static let maxMarkNameCharacters = 80
    static let maxSummaryCharacters = 600
    static let maxWarnings = 20
    static let maxLocalNames = 20
    static let maxDistanceKm = 10_000.0
    static let maxStageCount = 200
    static let maxGainMeters = 30_000.0
    static let maxHours = 100.0
```
> Pilgrim/Models/Honor/PilgrimageWayImporter.swift:56-73@7c200bf

- `capped` is `String.prefix(n)`: **grapheme clusters**, not UTF-16 units (probed: `prefix(2)` of three flag emoji is 8 UTF-16 units). Never trims. Android: `prefixCharacters` (`P/domain/honor/SwiftText.kt:46-57@0defff85`).
- `trimmed` trims Foundation's `whitespacesAndNewlines`, returns nil when empty, then cuts. Android: `trimmingWhitespacesAndNewlines()` then `prefixCharacters` (`SwiftText.kt:18-35`), as `WayMoment.trimmedTranscript` already does (`P/domain/honor/Way.kt:136-140@0defff85`).
- **`localNames`, step by step** (order matters): nil for an absent or empty map; sort the raw pairs by key with Swift's `<`; **take the first 20 raw pairs**; then drop a pair whose key fails `\A[a-z]{2,3}\z` (ASCII lowercase, 2–3 letters: `ast` passes, `zh-Hant`, `EN`, `é` fail) or whose value trims to empty; values trimmed and cut to 120; nil when nothing survives. The cap is applied **before** the filter, so invalid keys that sort first (uppercase sorts before lowercase) can push valid ones out. Live: Guernica (`camino-norte` stage 5, `wp-osm-town-node68651691`) carries 30 names; iOS keeps `ar ast ca cs cy da de eo es eu fi fr ga he it ja ko lv ms nl` and drops `nn no pl pt ro ru sr sv uk zh` (`live_probe.out`). The card's language order (eu, gl, es, fr, ja, pt, it, de; P5) then finds `eu` first. Android must sort, cut to 20, then filter, in that order; a filter-then-cut port keeps `pt` and differs.
- Sorting: keys are compared with Swift's `<`, Kotlin's `compareTo` agrees for ASCII. Every live key is ASCII (§12).

| Field | Treatment | Cap | Android today |
|---|---|---|---|
| `title` | `capped` | 120 (`maxStageNameCharacters`) | new |
| `tzIdentifier` | `capped` when present, not trimmed | 80 (`WayImporter.maxLabelCharacters`) | new; the share importer keeps it whole (#118 item 3) |
| moment `id` | `capped` | 80 | new |
| moment label | `capped(label ?? "", 80)`: absent becomes `""`; a blank label stays | 80 | as the share importer (S1 trap 8) |
| moment icon | `capped(icon ?? "mappin", 64)`: only an absent icon falls back; `""` stays `""` | 64 | as the share importer (S1 trap 7) |
| moment `text` | `trimmed`, nil when blank | 600 (`WayMoment.maxTranscriptCharacters`) | `WayMoment.trimmedTranscript` is the same function |
| moment `names` | `localNames` | 20 pairs; 120 per value | new |
| moment `sitMinutes` | verbatim (validated 0–1440; `0` is kept) | | |
| moment `at`, `pin` | verbatim coordinates | | |
| mark `id` | `capped` | 80 | new |
| mark `name` | `capped` | 80 (`maxMarkNameCharacters`) | new |
| `stage.name` | `capped` | 120 | |
| `stage.theme` | `capped` | 80 | |
| `stage.narrative` | `capped` | 2,000 | |
| `stage.closing` | `capped` | 400 | |
| `stage.warnings` | `prefix(20)`, each `capped` (an empty warning is kept) | 20 × 300 | |
| `stage.difficulty` | `capped`; `""` kept | 80 | |
| `stage.start.name`, `stage.end.name` | `capped` | 120 | |

No string is stripped of control or bidirectional characters (the dataset is trusted more than a share, but the same gap: #118 item 1 extends to it). The live dataset carries none (§12).

```swift
    private static func moments(from raw: [StageFile.Moment]) -> [WayMoment] {
        var built: [WayMoment] = []
        for entry in raw where entry.kind == "waypoint" {
            var moment = WayMoment(
                id: capped(entry.id, WayImporter.maxLabelCharacters),
                frac: entry.frac,
                at: entry.at.map { WayCoordinate(lat: $0.lat, lon: $0.lon) },
                kind: .waypoint(label: capped(entry.label ?? "", WayImporter.maxLabelCharacters),
                                icon: capped(entry.icon ?? "mappin", WayImporter.maxIconCharacters)))
            moment.text = trimmed(entry.text, WayMoment.maxTranscriptCharacters)
            moment.names = localNames(entry.names)
            moment.sitMinutes = entry.sitMinutes
            moment.pin = entry.pin.map { WayCoordinate(lat: $0.lat, lon: $0.lon) }
            built.append(moment)
        }
        // A tiebreak on id keeps ordering deterministic when two moments
        // share a frac — the rule `WayImporter` and the tracker both sort by.
        return built.sorted { $0.frac == $1.frac ? $0.id < $1.id : $0.frac < $1.frac }
    }

    private static func marks(from raw: [StageFile.Mark]) -> [WayMark] {
        raw.compactMap { entry in
            guard let kind = WayMarkKind(rawValue: entry.kind) else { return nil }
            return WayMark(id: capped(entry.id, WayImporter.maxLabelCharacters), kind: kind,
                           name: capped(entry.name, maxMarkNameCharacters),
                           at: WayCoordinate(lat: entry.at.lat, lon: entry.at.lon),
                           frac: entry.frac, offLineMeters: entry.offLineMeters)
        }
    }
```
> Pilgrim/Models/Honor/PilgrimageWayImporter.swift:208-236@7c200bf

- **Moments:** only `kind == "waypoint"` (exact, case-sensitive) is kept; every other kind is skipped after it was validated and counted. `place` and `transcript` stay nil. Sorted by `(frac, id)`, ties on frac broken by Swift's `<` on the (capped) id. Android has the comparator, private, in `WayImporter` (`BY_FRAC_THEN_ID` and `compareFracTo`, `P/data/honor/WayImporter.kt:518-528@0defff85`); lift it to a shared place rather than copy it. Swift's `sorted` isn't stable and Kotlin's is, which differs only for two moments with the same frac **and** the same id; live data has none (§12). 17 live stages carry frac ties (`wp-sjpp` before `wp-sjpp-pilgrim-office` at frac 0 on `camino-frances` stage 0), so the tiebreak is exercised by real data.
- **Marks:** **file order, not sorted** (the fixture's are 0.5, 0.7, 0.3, and iOS's test asserts that order). `kind` must be one of the six raw values exactly (`water food bed transport supply medical`); anything else drops the mark. Android's `WayMarkKind` has the six (`P/domain/honor/Way.kt:159-178@0defff85`); map by the serial names, not `valueOf` on the Kotlin names.

```swift
        var way = Way(
            id: expectedId,
            source: .pilgrimage(routeId: routeId, stageIndex: stageIndex),
            title: capped(file.title, maxStageNameCharacters),
            departedAt: departed,
            tzIdentifier: file.tzIdentifier.map { capped($0, WayImporter.maxLabelCharacters) },
            expires: nil,
            route: route,
            totalDistanceMeters: geometry.totalMeters,
            theirActiveSeconds: file.theirActiveSeconds,
            moments: moments(from: file.moments),
            weather: nil)
        way.marks = marks(from: file.marks)
        way.stage = stage(from: file.stage)
        return way
    }
```
> Pilgrim/Models/Honor/PilgrimageWayImporter.swift:188-203@7c200bf

```swift
    private static func stage(from raw: StageFile.Stage) -> WayStage {
        WayStage(
            routeId: raw.routeId, index: raw.index, count: raw.count,
            name: capped(raw.name, maxStageNameCharacters),
            theme: capped(raw.theme, maxThemeCharacters),
            narrative: capped(raw.narrative, maxNarrativeCharacters),
            closing: capped(raw.closing, maxClosingCharacters),
            warnings: raw.warnings.prefix(maxWarnings).map { capped($0, maxWarningCharacters) },
            distanceKm: raw.distanceKm, gainMeters: raw.gainMeters,
            hours: WayStageHours(min: raw.hours.min, max: raw.hours.max),
            difficulty: capped(raw.difficulty, WayImporter.maxLabelCharacters),
            start: WayStagePlace(name: capped(raw.start.name, maxStageNameCharacters),
                                 at: WayCoordinate(lat: raw.start.at.lat, lon: raw.start.at.lon)),
            end: WayStagePlace(name: capped(raw.end.name, maxStageNameCharacters),
                               at: WayCoordinate(lat: raw.end.at.lat, lon: raw.end.at.lon)))
    }
```
> Pilgrim/Models/Honor/PilgrimageWayImporter.swift:238-253@7c200bf

#### 5.4 The `Way`, field by field

| `Way` field | Value | Note |
|---|---|---|
| `id` | `"pilgrimage:<routeId>:<stageIndex>"` | decimal, no padding |
| `source` | `.pilgrimage(routeId:stageIndex:)` | wire `{"pilgrimage":{"routeId":…,"stageIndex":…}}`, already in `WaySourceSerializer` (`P/domain/honor/WayJson.kt:159-185@0defff85`); `isPackageOwned` true |
| `title` | `file.title`, capped 120 | not the stage name, though live they are equal on every stage (§12) |
| `departedAt` | `WayImporter.isoDate(file.departedAt)` | RFC 3339 with or without a fraction; offsets accepted; a bare local time or a date alone refused (probed). Live values are whole-second UTC (`2026-04-10T00:00:00Z` and three more), from `metadata.json`'s `lastUpdated`, never shown. Android: `WayImporter.isoDate` (`P/data/honor/WayImporter.kt:282-296@0defff85`, S1 §4.4's recorded edge differences). Store encoding then drops sub-seconds |
| `tzIdentifier` | `file.tzIdentifier`, capped 80 | absent on every live file, so nil |
| `expires` | nil | a route never returns to the trail |
| `route` | each point verbatim: `lat`, `lon`, `alt` (nil when absent), `t` | **not rebased** to the first point (the share importer subtracts `ts0`; this one doesn't). Live first `t` is 0 everywhere |
| `totalDistanceMeters` | `geometry.totalMeters` (haversine) | **not** the file's `totalDistanceMeters`, which is validated then ignored. Live they agree within 1 m |
| `theirActiveSeconds` | `file.theirActiveSeconds` | a synthesized walking time; P4 owns where it shows (survey defect 2) |
| `moments` | §5.3 | |
| `weather` | nil | |
| `spans` | nil (never set) | an all-walking Way |
| `marks` | `marks(from:)`, **`[]` when the file has none**, never nil | `stage-01.json` has `"marks": []`; Android sets `emptyList()`, not null, so `way.json` carries `"marks":[]` as iOS writes it |
| `stage` | `stage(from:)` | so `isPilgrimageStage` is true (`stage != nil`, `Way.swift:217`) |

The package manager encodes this `Way` with the store's encoder (`.iso8601`, `[.sortedKeys]`, `PilgrimagePackageManager.swift:443-448`) into the temp set, and the commit decodes it back and saves it (P2). Android's `WayJson` is that encoding already (own-walk spec A §2).

### 6. `route.json` becomes a `PilgrimageRoute` (`PilgrimageWayImporter.route(from:)`)

```swift
struct PilgrimageRouteStage: Equatable {
    let index: Int
    let name: String
    let distanceKm: Double
    let gainMeters: Double
    let hours: WayStageHours
    let difficulty: String
}

struct PilgrimageRoute: Equatable {
    let id: String
    let name: String
    let names: [String: String]
    let country: String?
    let region: String?
    let distanceKm: Double
    let stageCount: Int
    let tradition: String?
    let summary: String?
    let stages: [PilgrimageRouteStage]
}
```
> Pilgrim/Models/Honor/PilgrimageWayImporter.swift:28-48@7c200bf

```swift
    static func route(from data: Data) throws -> PilgrimageRoute {
        guard data.count <= maxRouteBytes, let file = try? JSONDecoder().decode(RouteFile.self, from: data) else {
            throw PilgrimageError.notWalkable
        }
        guard WayStore.isValidRouteId(file.id),
              isSaneDistance(file.distanceKm),
              (1...maxStageCount).contains(file.stageCount),
              file.stages.count <= maxStageCount,
              file.stages.allSatisfy(isSaneStageRow) else { throw PilgrimageError.notWalkable }
        // The manager saves each stage positionally — download loop index 0,
        // 1, 2… becomes the Way at that same stage index — and the route
        // screen keys a tapped row on this file's own `index`. Anything but
        // the exact run 0..<count (1-based, a duplicate, a gap) would hand a
        // tapped row a different stage than the one saved at that position.
        guard file.stages.map(\.index).sorted() == Array(0..<file.stages.count) else {
            throw PilgrimageError.notWalkable
        }
        return PilgrimageRoute(
            id: file.id,
            name: capped(file.name, maxStageNameCharacters),
            names: localNames(file.names) ?? [:],
            country: file.country.map { capped($0, WayImporter.maxLabelCharacters) },
            region: file.region.map { capped($0, WayImporter.maxLabelCharacters) },
            distanceKm: file.distanceKm,
            stageCount: file.stageCount,
            tradition: file.tradition.map { capped($0, WayImporter.maxLabelCharacters) },
            summary: trimmed(file.summary, maxSummaryCharacters),
            stages: file.stages
                .sorted { $0.index < $1.index }
                .map { PilgrimageRouteStage(index: $0.index, name: capped($0.name, maxStageNameCharacters),
                                            distanceKm: $0.distanceKm, gainMeters: $0.gainMeters,
                                            hours: WayStageHours(min: $0.hours.min, max: $0.hours.max),
                                            difficulty: capped($0.difficulty, WayImporter.maxLabelCharacters)) })
    }

    private static func isSaneStageRow(_ row: RouteFile.Stage) -> Bool {
        (0..<maxStageCount).contains(row.index)
            && isSaneDistance(row.distanceKm)
            && isSaneGain(row.gainMeters)
            && isSaneHours(row.hours)
    }
```
> Pilgrim/Models/Honor/PilgrimageWayImporter.swift:257-297@7c200bf

- Body ≤ 512 KiB (524,288 bytes, `<=`), decodes, then: id passes the slug rule; `distanceKm` finite in [0, 10,000]; `stageCount` in [1, 200]; `stages.count` ≤ 200; every row has `index` in [0, 200), distance and gain and hours sane (the stage file's own rules, §5.2 rows 14–16).
- **Indices must be exactly `0..<stages.count`** once sorted: a 1-based run, a duplicate, or a gap is refused. The rows may arrive in any order; the result is sorted by `index`.
- `route(from:)` does **not** require `stageCount == stages.count`. That, and `id`, are checked against the catalog entry by both callers (`routePreview` :188-189 and `stageRouteFile` :316-317: `route.id == entry.id`, `route.stageCount == entry.stageCount`, `route.stages.count == entry.stageCount`). `installed()` (P2) reads an installed `route.json` through `route(from:)` alone.
- `names` is `localNames(…) ?? [:]`: an **empty map, never nil**, unlike a moment's `names`.
- `summary` is trimmed (nil when blank) and cut to 600. `country`, `region`, `tradition` are cut to 80, not trimmed. `cover`, `schemaVersion` and `stampHours` are ignored (`stampHours` is post-pin, §12).
- The route's `name` is cut to 120; its rows' names likewise, before the package compares them with each stage's `stage.name` (also cut to 120).
- Live: `camino-norte`'s `summary` is exactly 600 characters (graphemes = UTF-16 units = 600), so it sits at the cap and is not cut; Android's `prefixCharacters` must leave a 600-grapheme string whole.

### 7. The catalog: the index, its rows, names, duplicates, and grouping

#### 7.1 The models

```swift
struct PilgrimageCatalogEntry: Codable, Equatable, Hashable, Identifiable {
    let id: String
    let name: String
    let names: [String: String]
    let country: String?
    let region: String?
    let distanceKm: Double
    let tradition: String?
    let stageCount: Int
    let bytes: Int
// …
    let placesPerStage: Double
// …
    let sparse: Bool
```
> Pilgrim/Models/Honor/PilgrimageCatalogService.swift:6-22@7c200bf

```swift
struct PilgrimageGroup: Codable, Equatable, Identifiable {
    let id: String
    /// Nil for routes the index files under no pilgrimage: they still belong
    /// in the list, but naming a pilgrimage for them would invent one.
    let name: String?
    let entries: [PilgrimageCatalogEntry]
}

struct PilgrimageCatalog: Codable, Equatable {
    /// The git tag every package file is then pinned to, so a route's stages
    /// are always from one build.
    let release: String
    let routes: [PilgrimageCatalogEntry]
    /// The same routes the picker draws, gathered under their pilgrimage.
    /// Every route appears exactly once, so this is a view of `routes` and
    /// never a filter on it.
    let groups: [PilgrimageGroup]

    init(release: String, routes: [PilgrimageCatalogEntry], groups: [PilgrimageGroup]? = nil) {
        self.release = release
        self.routes = routes
        self.groups = groups ?? [PilgrimageGroup(id: "", name: nil, entries: routes)]
    }
}
```
> Pilgrim/Models/Honor/PilgrimageCatalogService.swift:47-70@7c200bf

The entry's memberwise `init` defaults `placesPerStage: 0` and `sparse: false` (:27-29), which only the tests use; the synthesized `Codable` (the cache) requires every key. `PilgrimageCatalog(release:routes:)` with no groups means **one loose group holding every route**, and that is what an index without a `pilgrimages` key produces (7.4).

#### 7.2 The index file and a row's validation

```swift
    private struct IndexFile: Decodable {
        struct Ways: Decodable {
            let stageCount: Int
            let bytes: Int
            /// Both optional: an index written before the build measured
            /// coverage still parses, as a dense route with no figure.
            let placesPerStage: Double?
            let sparse: Bool?
        }
        struct Route: Decodable {
            let id: String
            let name: [String: String]
            let region: String?
            let country: String?
            let distanceKm: Double
            let tradition: String?
            let ways: Ways?
        }
        struct Pilgrimage: Decodable {
            let id: String
            let name: [String: String]
// …
            let sections: [String]
        }
        let release: String
        let routes: [Route]
        /// Absent from an index written before the pilgrimage layer.
        let pilgrimages: [Pilgrimage]?
    }
```
> Pilgrim/Models/Honor/PilgrimageCatalogService.swift:243-274@7c200bf

```swift
    static func parse(_ data: Data) throws -> PilgrimageCatalog {
        guard let file = try? JSONDecoder().decode(IndexFile.self, from: data),
              isValidRelease(file.release) else { throw PilgrimageError.catalogUnreachable }
        let routes = file.routes.compactMap { row -> PilgrimageCatalogEntry? in
            guard let ways = row.ways, WayStore.isValidRouteId(row.id),
                  row.distanceKm.isFinite, (0...maxDistanceKm).contains(row.distanceKm),
                  (1...maxStageCount).contains(ways.stageCount),
                  (0..<maxPackageBytes).contains(ways.bytes),
                  ways.placesPerStage.map({ $0.isFinite && (0...maxPlacesPerStage).contains($0) }) ?? true
            else { return nil }
            let names = localeNames(row.name)
            guard let display = displayName(names) else { return nil }
            return PilgrimageCatalogEntry(
                id: row.id,
                name: String(display.prefix(PilgrimageWayImporter.maxStageNameCharacters)),
                names: names.mapValues { String($0.prefix(PilgrimageWayImporter.maxStageNameCharacters)) },
                country: row.country.map { String($0.prefix(WayImporter.maxLabelCharacters)) },
                region: row.region.map { String($0.prefix(WayImporter.maxLabelCharacters)) },
                distanceKm: row.distanceKm,
                tradition: row.tradition.map { String($0.prefix(WayImporter.maxLabelCharacters)) },
                stageCount: ways.stageCount,
                bytes: ways.bytes,
                placesPerStage: ways.placesPerStage ?? 0,
                sparse: ways.sparse ?? false)
        }
        // First wins: a repeated id downstream would trap `Dictionary(uniqueKeysWithValues:)`
        // and hand `List` two rows with the same `Identifiable` id.
        var seenIds = Set<String>()
        let deduped = routes.filter { seenIds.insert($0.id).inserted }
        return PilgrimageCatalog(release: file.release, routes: deduped,
                                 groups: file.pilgrimages.map { grouped(deduped, under: $0) })
    }

    private static func localeNames(_ raw: [String: String]) -> [String: String] {
        raw.filter { $0.key.range(of: "\\A[a-z]{2,3}\\z", options: .regularExpression) != nil }
    }

    private static func displayName(_ names: [String: String]) -> String? {
        names["en"] ?? names.sorted(by: { $0.key < $1.key }).first?.value
    }
```
> Pilgrim/Models/Honor/PilgrimageCatalogService.swift:281-320@7c200bf

**The whole index is refused** (`catalogUnreachable`, so `load` falls back to the cache) when it doesn't decode, or its `release` fails the tag rule. **One row is dropped**, never the catalog, when, in this order:
1. it has no `ways` (the build's length gate failed);
2. its `id` fails the slug rule (the fixture's `../etc/passwd`);
3. `distanceKm` isn't finite or is outside [0, 10,000];
4. `ways.stageCount` is outside [1, 200];
5. `ways.bytes` is outside [0, 52,428,800) (half-open: 50 MiB exactly is dropped);
6. `ways.placesPerStage`, when present, isn't finite or is outside [0, 50];
7. no name survives (7.3).

`sparse` isn't validated (any `Bool`); absent is `false`, and absent `placesPerStage` is `0`. The row's `region`, `country` and `tradition` are cut to 80, not trimmed; `country` drives the card line (§10). The row's `topology`, `path`, `pilgrimage` and `variants` are ignored.

**Duplicates:** after validation, the first row with an id wins and later rows with that id are dropped (`Set.insert(_:).inserted`). So an invalid first row doesn't shadow a valid second one with the same id: the invalid row was already gone. iOS's test changes row 1's id to `camino-frances` with a valid `ways` and expects only row 0.

#### 7.3 Which name: `en`, else the alphabetically first key

- `localeNames` keeps only keys matching `\A[a-z]{2,3}\z`; values are **not trimmed** and the map is **not capped at 20** (unlike a moment's `localNames`). Each value is cut to 120.
- `displayName`: `names["en"]` when the key exists, **even if its value is empty**; otherwise the value of the key that sorts first under Swift's `<` on `String`. "First" is therefore **alphabetical by key, deterministic**, not Swift's dictionary order (which is randomized per process and would be unstable). Probe: for `{"ja", "ast", "es"}` the first sorted key is `ast`. Kotlin: `names["en"] ?: names.toSortedMap().values.firstOrNull()` (keys are ASCII after the filter, so `compareTo` agrees with Swift's `<`).
- A row whose name map has no valid key is dropped. A row whose `en` is `""` is listed with an empty name, and its initial plate (`entry.name.prefix(1)`, P4) is empty: no live row does this (the schema requires `en`; every live row and pilgrimage has a non-empty `en`).
- The entry's `name` is the display name cut to 120 graphemes; `names` keeps every valid-key value, each cut to 120.

#### 7.4 Grouping by `pilgrimages[].sections`

```swift
    private static func grouped(_ routes: [PilgrimageCatalogEntry],
                                under pilgrimages: [IndexFile.Pilgrimage]) -> [PilgrimageGroup] {
        let byId = Dictionary(routes.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        var claimed = Set<String>()
        // The name is resolved before any section is claimed: a pilgrimage
        // this build cannot name must leave its sections to the loose group
        // rather than swallow them into a group it will not return.
        var groups = pilgrimages.compactMap { pilgrimage -> PilgrimageGroup? in
            guard !pilgrimage.id.isEmpty,
                  let name = displayName(localeNames(pilgrimage.name)) else { return nil }
            let entries = pilgrimage.sections.compactMap { section -> PilgrimageCatalogEntry? in
                guard let entry = byId[section], claimed.insert(section).inserted else { return nil }
                return entry
            }
            guard !entries.isEmpty else { return nil }
            return PilgrimageGroup(
                id: pilgrimage.id,
                name: String(name.prefix(PilgrimageWayImporter.maxStageNameCharacters)),
                entries: entries)
        }
        let loose = routes.filter { !claimed.contains($0.id) }
        if !loose.isEmpty {
            groups.append(PilgrimageGroup(id: "", name: nil, entries: loose))
        }
        return groups
    }
```
> Pilgrim/Models/Honor/PilgrimageCatalogService.swift:328-353@7c200bf

- **Groups follow `pilgrimages` in index order.** Live: `camino-de-santiago`, `kumano-kodo`, `shikoku-88`.
- **A pilgrimage is skipped**, leaving its sections unclaimed, when its `id` is empty or no name survives `localeNames` + `displayName`. The name is resolved before any section is claimed.
- **Entries follow `sections` order**, not the index's route order: Shikoku's rows are listed awa, iyo, sanuki, tosa (id order) and grouped awa, tosa, iyo, sanuki (walking order, temples 1, 23, 39, 65).
- **A section is taken only if it names a listed route** (one that survived 7.2) **not yet claimed**: an unshipped section (`camino-primitivo`, `kumano-kodo-iseji`) is skipped, a section named twice or by two pilgrimages appears once, in the first place that claims it.
- **A pilgrimage left with no entries is dropped** (Kumano in the fixture lists only unshipped sections).
- **Unclaimed routes trail** in index order as one group with `id: ""` and `name: nil` (no header), appended only when non-empty. So every listed route appears in exactly one group.
- **`kind` (`legs` or `alternatives`) is not decoded:** both render alike. `circular`, `distanceKm`, `stageCount` and `stats` are ignored too.
- **No `pilgrimages` key:** `groups` is `nil` → `PilgrimageCatalog`'s default, one loose group of every route, **including when there are no routes** (one empty group). A `pilgrimages: []` gives only the loose group when routes exist, and `[]` when none do. The screen checks `routes` first (P4), so the empty group is never drawn, but `testAnIndexWithNoPilgrimageBlockKeepsEveryRouteUnderNoHeader` asserts `groups.count == 1`; port the default exactly.
- **Group ids aren't deduplicated.** Two pilgrimages with the same non-empty id both produce groups when each claims something. iOS's `ForEach` tolerates it; Compose's `LazyColumn` throws on a repeated key. Key Android's items by position (or by entry id, which is unique), never by group id alone (an Android addition A6, crash-avoidance only).
- Live result (`live_probe.out`): Camino de Santiago: `camino-frances`, `camino-norte`; Kumano Kodō: `kumano-kodo-nakahechi`, `kumano-kodo-kohechi`; Shikoku 88 Temple Pilgrimage: `shikoku-88-awa`, `shikoku-88-tosa`, `shikoku-88-iyo`, `shikoku-88-sanuki`. No loose group. Five rows (`camino-ingles`, `camino-portugues`, `camino-primitivo`, `kumano-kodo-iseji`, `kumano-kodo-ohechi`) have no `ways` and are unlisted.

### 8. Loading: the 24 h rule, the fallback, Retry, and the cache file

```swift
    @MainActor
final class PilgrimageCatalogService: ObservableObject {
// …
    @Published private(set) var catalog: PilgrimageCatalog?
```
> Pilgrim/Models/Honor/PilgrimageCatalogService.swift:75-98@7c200bf

```swift
    nonisolated private static var defaultDirectory: URL {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        return appSupport.appendingPathComponent("Pilgrimages", isDirectory: true)
    }

    init(session: URLSession = PilgrimageCatalogService.defaultSession,
         directory: URL = PilgrimageCatalogService.defaultDirectory,
         now: @escaping () -> Date = Date.init) {
        self.session = session
        self.directory = directory
        self.now = now
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }
```
> Pilgrim/Models/Honor/PilgrimageCatalogService.swift:117-129@7c200bf

```swift
    @discardableResult
    func load(force: Bool = false) async throws -> PilgrimageCatalog {
        let cached = readCache()
        if !force, let cached, now().timeIntervalSince(cached.fetchedAt) < Self.cacheLifetime {
            catalog = cached.catalog
            return cached.catalog
        }
        do {
            let fresh = try Self.parse(try await fetchIndex())
            writeCache(Cached(fetchedAt: now(), catalog: fresh))
            catalog = fresh
            return fresh
        } catch {
            if let cached {
                catalog = cached.catalog
                return cached.catalog
            }
            throw PilgrimageError.catalogUnreachable
        }
    }
```
> Pilgrim/Models/Honor/PilgrimageCatalogService.swift:153-172@7c200bf

```swift
    private struct Cached: Codable {
        let fetchedAt: Date
        let catalog: PilgrimageCatalog
    }

    private var cacheURL: URL { directory.appendingPathComponent("catalog.json") }

    /// A cache written by a build that did not know about the coverage
    /// fields fails to decode and is simply refetched — there is nothing in
    /// it worth a migration.
    private func readCache() -> Cached? {
        guard let data = try? Data(contentsOf: cacheURL) else { return nil }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return try? decoder.decode(Cached.self, from: data)
    }

    private func writeCache(_ cached: Cached) {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        try? encoder.encode(cached).write(to: cacheURL, options: .atomic)
    }
```
> Pilgrim/Models/Honor/PilgrimageCatalogService.swift:357-378@7c200bf

**The state machine of `load(force:)`:**
1. Read the cache from disk, every time, forced or not. Unreadable or undecodable is the same as absent.
2. **Fresh:** not forced, a cache exists, and `now − fetchedAt < 86,400 s` (strict: a cache exactly 24 h old is stale). Publish the cached catalog, return it, **no network**.
3. Otherwise fetch the index (§3.1) and `parse` it (§7). On success: `fetchedAt = now()` taken **after** the fetch returns, write the cache, publish, return. **A parse that keeps zero routes is a success**, cached and fresh for 24 h.
4. On any failure (fetch, cap, status, decode, release tag, cancellation): with a cache read in step 1, **at any age**, publish and return it **without an error** (a forced Retry included). With none, throw `catalogUnreachable` and leave `catalog` as it was.

Consequences for the units:
- **Retry (`force: true`) forces a network request but not a visible failure:** offline with a stale cache, Retry quietly returns the same cache. The screen's "try again" shows only when there is no catalog to list (P4), so in practice Retry is pressed with no cache, or with a cached catalog of zero routes.
- **"Reappearance does not force":** the catalog screen's pop-back reload calls `load()` unforced (`PilgrimageCatalogView.swift:137-143@7c200bf`), so within 24 h it is a disk read.
- **Freshness under a clock set backwards** (flow gap 11): the difference is signed, so a `fetchedAt` in the future reads as fresh until the clock passes `fetchedAt + 24 h`. Android must keep the signed comparison (`now - fetchedAt < DAY`, both wall clock), not an absolute value and not elapsed-realtime, and pin it with a test (clock moved back 48 h after a fetch: no request).
- **`load` throws only when no cache is readable.** So "the routes are out of reach right now" means: never fetched successfully (or the cache file is gone or undecodable), and this fetch failed.
- **`catalog` is published state** on a process-wide singleton (`static let shared`, :78): it is set on every successful return and never cleared, so a reopened catalog sheet lists the last catalog at once while `load` runs again (P4's spinner shows only while `catalog` is nil).
- **No single-flight:** two overlapping `load`s each fetch and each write the cache (iOS runs them interleaved on the main actor). The plan's mutex shared by the TTL check and Retry is an Android addition; it changes nothing visible (A4).
- **Threading:** `load`, `readCache`, `writeCache` and `parse` are main-actor code (the class is `@MainActor`; only `fetch` and the sessions are `nonisolated`), so iOS reads and writes the cache file on the main thread. Android: disk, decode and parse on `Dispatchers.IO`, the `StateFlow` updated after.

**The cache file:**
- Path: `<Application Support>/Pilgrimages/catalog.json`, the directory created at `init` with `try?`. **Not excluded from backup**: iOS excludes only `Ways/` (`WayStore.swift:44-47@7c200bf`), so the catalog cache and the route previews travel in an iCloud backup and a device transfer, while the installed package and the ledger don't.
- Content: `JSONEncoder` with `.iso8601` dates, no `sortedKeys`, compact. `{"fetchedAt":"2026-10-02T12:00:00Z","catalog":{"release":"v1.12.0","routes":[…],"groups":[{"id":"shikoku-88","name":"…","entries":[…]}, …]}}`. Each entry carries `id, name, names, country?, region?, distanceKm, tradition?, stageCount, bytes, placesPerStage, sparse`; nil optionals are omitted (a loose group has no `name` key). Entries are stored twice, under `routes` and under each group. **The parsed catalog is cached, not the served bytes** (unlike `CollectiveRouteCatalogService`, which caches bytes, `P/data/collective/routes/CollectiveRouteCatalogService.kt:214-235@0defff85`).
- `fetchedAt` goes through `.iso8601`, which writes whole seconds, so the 24 h window can close up to a second early. Android may store epoch milliseconds; the difference is under a second (A5).
- Written `.atomic` with `try?`: a failed write is silent, the fresh catalog is still published and returned, and the next load refetches.
- Read with `try?`: any missing key (an older build's cache, a format change) means "no cache". Android's format is its own (the file never crosses platforms); decode it strictly and treat any failure as absent, never as an error.

**Where Android puts it** (flow gap 11, the plan's Key Technical Decision): the plan says `noBackupFilesDir/Pilgrimages/`. iOS's location is backed up and transferred. Android's `filesDir` is the equivalent: `data_extraction_rules.xml` excludes all files from cloud backup but includes `filesDir` in a device transfer (`app/src/main/res/xml/data_extraction_rules.xml@0defff85`), and `CollectiveRouteCatalogService` already keeps its catalog cache in `filesDir` (`:58`). Recommendation: `filesDir/Pilgrimages/` for both `catalog.json` and the previews (owner decision O1; correction C6). Either way the cache heals itself; the difference is whether a new phone can list routes offline on its first open.

### 9. The route preview

```swift
    func routePreview(entry: PilgrimageCatalogEntry, release: String) async throws -> PilgrimageRoute {
        if let cached = readRoutePreview(routeId: entry.id, release: release) { return cached }
        guard let url = Self.packageURL(release: release, routeId: entry.id, file: "route.json") else {
            throw PilgrimageError.notWalkable
        }
        let data = try await Self.fetch(url, cap: PilgrimageWayImporter.maxRouteBytes, session: session)
        let route = try PilgrimageWayImporter.route(from: data)
        // The same identity check the download makes: a route file that
        // disagrees with the index is not the route being offered.
        guard route.id == entry.id, route.stageCount == entry.stageCount,
              route.stages.count == entry.stageCount else { throw PilgrimageError.notWalkable }
        writeRoutePreview(data, routeId: entry.id, release: release)
        return route
    }

    /// Keyed by release as well as route: a preview from an older build must
    /// never stand in for the stages the current index names.
    private func routePreviewURL(routeId: String, release: String) -> URL? {
        guard WayStore.isValidRouteId(routeId), Self.isValidRelease(release) else { return nil }
        return directory.appendingPathComponent("route-\(routeId)-\(release).json")
    }

    private func readRoutePreview(routeId: String, release: String) -> PilgrimageRoute? {
        guard let url = routePreviewURL(routeId: routeId, release: release),
              let data = try? Data(contentsOf: url) else { return nil }
        return try? PilgrimageWayImporter.route(from: data)
    }

    private func writeRoutePreview(_ data: Data, routeId: String, release: String) {
        guard let url = routePreviewURL(routeId: routeId, release: release) else { return }
        try? data.write(to: url, options: .atomic)
    }
```
> Pilgrim/Models/Honor/PilgrimageCatalogService.swift:179-210@7c200bf

- **Cached first:** a readable `route-<id>-<release>.json` that passes `route(from:)` is returned with no network and **without re-running the entry check** (it passed before it was written). Nothing expires it.
- **Then the network:** `packageURL(…, "route.json")` (nil → `notWalkable`), the catalog's fetch with the 512 KiB cap (any fetch failure → `catalogUnreachable`), `route(from:)` (→ `notWalkable`), the entry check on `id`, `stageCount`, `stages.count` (→ `notWalkable`).
- **Written as the raw bytes received**, atomically, `try?`. Not pretty-printed, not re-encoded.
- **Keyed by release:** a new release refetches; the old file stays. Nothing ever deletes a preview: they accumulate, one per route per release viewed (survey defect 8's second half, confirmed; D5). At ~2–8 KB each this is bytes, not megabytes.
- **Never written into the package folder**; only the package manager installs a route. The route page asks for a preview only when the route isn't installed (`route == nil`) and `release` is non-empty (`PilgrimageRouteView.swift:390-399@7c200bf`); an installed route's own `route.json` is authoritative.
- Preview failures read on the route page's stage section with "try again" (P4): "the routes are out of reach right now" for a network failure, "this route isn't walkable yet" for a bad file.

### 10. What the catalog exposes for the badge and the card line (data level; P4 draws them)

```swift
    static func card(entry: PilgrimageCatalogEntry, ledger: PilgrimageLedger?, isInstalled: Bool) -> String {
        var parts: [String] = []
        if let country = entry.country, !country.isEmpty { parts.append(country) }
        parts.append(StatsHelper.string(for: entry.distanceKm * 1000, unit: UnitLength.meters, type: .distance))
        if isInstalled {
            parts.append(PilgrimageLedger.progressLine(ledger: ledger, stageCount: entry.stageCount))
        } else {
            parts.append(entry.stageCount == 1 ? "1 stage" : "\(entry.stageCount) stages")
        }
        return parts.joined(separator: " · ")
    }
```
> Pilgrim/Scenes/Honor/PilgrimageCatalogView.swift:10-20@7c200bf

```swift
    static func installBadge(isInstalled: Bool, hasUpdate: Bool) -> InstallBadge? {
        guard isInstalled else { return nil }
        return hasUpdate
            ? InstallBadge(symbol: "arrow.down.circle.fill", label: "update ready", tint: .stone)
            : InstallBadge(symbol: "checkmark.circle.fill", label: "on your phone", tint: .moss)
    }
// …
    static func sparseNote(for entry: PilgrimageCatalogEntry) -> String? {
        entry.sparse ? "few places marked yet" : nil
    }
```
> Pilgrim/Scenes/Honor/PilgrimageCatalogView.swift:35-48@7c200bf

```swift
    private func hasUpdate(for entry: PilgrimageCatalogEntry) -> Bool {
        installed.map { $0.routeId == entry.id && $0.release != (catalogService.catalog?.release ?? "") } ?? false
    }
```
> Pilgrim/Scenes/Honor/PilgrimageCatalogView.swift:200-202@7c200bf

```swift
    private func load(force: Bool = false) async {
        isLoading = true
        failure = nil
        do {
            let catalog = try await catalogService.load(force: force)
            installed = packages.installed()
            // `uniquingKeysWith`, not `uniqueKeysWithValues:` — `parse` already
            // drops a repeated id, but this map must never be the thing that
            // traps if that guarantee is ever loosened.
            ledgers = Dictionary(catalog.routes.compactMap { entry in
                ledgerStore.load(routeId: entry.id).map { (entry.id, $0) }
            }, uniquingKeysWith: { first, _ in first })
        } catch {
            failure = (error as? PilgrimageError) ?? .catalogUnreachable
        }
        isLoading = false
    }
```
> Pilgrim/Scenes/Honor/PilgrimageCatalogView.swift:218-234@7c200bf

```swift
    static func progressLine(ledger: PilgrimageLedger?, stageCount: Int) -> String {
        guard let ledger, !ledger.stages.isEmpty || (ledger.carriedKm ?? 0) > 0 else {
            return stageCount == 1 ? "1 stage" : "\(stageCount) stages"
        }
        let walked = StatsHelper.string(for: ledger.totalKmWalked * 1000, unit: UnitLength.meters, type: .distance)
        guard let next = ledger.next(stageCount: stageCount) else {
            return "you have walked the whole way · \(walked)"
        }
        return "stage \(next.index + 1) of \(stageCount) · \(walked) walked"
    }
```
> Pilgrim/Models/Honor/PilgrimageLedger.swift:87-96@7c200bf

The inputs a catalog ViewModel needs, and only these:
- **The catalog** (`release`, `routes`, `groups`) from the service.
- **`installed`**: `PilgrimagePackageManager.installed()` (P2: the route whose `route.json` parses and whose `release.txt` passes the tag rule; it also finishes an interrupted Replace, so it writes, and must run in the UI process only, flow gap 9). Read once per load, after the catalog, and on the pop-back reload; never per row.
- **Ledgers for every listed route**, not only the installed one (`ledgerStore.load(routeId:)` per entry), first wins.
- Per row: `isInstalled = installed?.routeId == entry.id`; `hasUpdate = isInstalled && installed.release != catalog.release`.

Data rules:
- **`hasUpdate` is inequality, not "newer"**: an installed `v1.12.0` against a catalog naming `v1.11.0` (a stale cache, or a CDN edge serving an older `@main`) reads "update ready", and Update installs the older release. The design spec said "newer" (§2.3); shipped code wins. Android compares strings, not versions (D4, minor).
- **Badge:** nil when not installed; installed and `hasUpdate` → symbol `arrow.down.circle.fill`, label "update ready", tint `stone`; installed and current → `checkmark.circle.fill`, "on your phone", `moss`. The label is the badge's accessibility label (the glyph alone is drawn). "updated", the old card clause, is gone: iOS's device-pass doc still describes "on your phone · updated · …" (`docs/honor-slice-two-device-pass.md:60-62@7c200bf`); the shipped badge supersedes it (U41).
- **Card line:** `[country if non-empty] · <distance in the walker's unit> · <stages or progress>`, joined by " · ". Not installed: "1 stage" / "N stages" from the **index's** `stageCount`. Installed: `progressLine(ledger, entry.stageCount)`, also from the index's count, so during "update ready" the count is the new release's. Progress forms: no ledger, or an empty one with no carried km → "N stages"; every stage done → "you have walked the whole way · <km>"; else "stage <n> of <count> · <km> walked" (P2 owns `next` and `totalKmWalked`).
- **Sparse note:** `entry.sparse` → "few places marked yet", its own line, never inside the card line.
- **An installed route that the index no longer lists has no row** (the rows come only from `groups`), so it can be reached only by replacing it (survey defect 7, confirmed for the catalog half; D6). The same holds offline with no readable cache: the screen shows the unreachable copy, and the installed route has no door.

### 11. The package's identity cross-check (facts for U34; P2 owns the lifecycle)

```swift
    nonisolated private static func stageRouteFile(entry: PilgrimageCatalogEntry, routeURL: URL, into temp: URL, session: URLSession) async throws
        -> (route: PilgrimageRoute, bytes: Int) {
        let data = try await fetch(url: routeURL, cap: PilgrimageWayImporter.maxRouteBytes, session: session)
        let route = try PilgrimageWayImporter.route(from: data)
        guard route.id == entry.id, route.stageCount == entry.stageCount,
              route.stages.count == entry.stageCount else { throw PilgrimageError.notWalkable }
        try write(data, to: temp.appendingPathComponent("route.json"))
        return (route, data.count)
    }
```
> Pilgrim/Models/Honor/PilgrimagePackageManager.swift:312-320@7c200bf

```swift
    nonisolated private static func stageOneStage(_ plan: StagePlan, into temp: URL, session: URLSession) async throws -> Int {
        let index = plan.expected.index
        let data = try await fetch(url: plan.url, cap: PilgrimageWayImporter.maxStageBytes, session: session)
        let way = try PilgrimageWayImporter.way(from: data, routeId: plan.routeId, stageIndex: index)
        guard way.stage?.count == plan.stageCount, way.stage?.name == plan.expected.name else {
            throw PilgrimageError.notWalkable
        }
        try write(try Self.encoder.encode(way), to: temp.appendingPathComponent("\(index).way.json"))
        return data.count
    }
```
> Pilgrim/Models/Honor/PilgrimagePackageManager.swift:344-353@7c200bf

```swift
            for index in 0..<fetched.route.stageCount {
                guard let stageURL = PilgrimageCatalogService.packageURL(
                    release: release, routeId: entry.id, file: Self.stageFileName(index)) else {
                    throw PilgrimageError.notWalkable
                }
                let stagePlan = StagePlan(routeId: entry.id, url: stageURL, stageCount: fetched.route.stageCount,
                                          expected: fetched.route.stages[index])
                packageBytes += try await Self.stageOneStage(stagePlan, into: temp, session: session)
                try Self.checkBudget(packageBytes, cap: cap)
                phase = .downloading(done: index + 2, total: total)
            }
```
> Pilgrim/Models/Honor/PilgrimagePackageManager.swift:180-190@7c200bf

The full identity chain, all `notWalkable`, "this route isn't walkable yet":

| Pair | Compared | Where |
|---|---|---|
| `route.json` ↔ catalog entry | `route.id == entry.id`, `route.stageCount == entry.stageCount`, `route.stages.count == entry.stageCount` | `stageRouteFile`; the same three in `routePreview` |
| stage file ↔ request | `id == "pilgrimage:<route>:<i>"`, `stage.routeId == <route>`, `stage.index == i` | `way(from:)` (§5.1) |
| stage file ↔ `route.json` | `stage.count == route.stageCount`, `stage.name == route.stages[i].name` (both cut to 120) | `stageOneStage` |

- The loop runs `0..<route.stageCount`, looks the row up **positionally** (`route.stages[index]`, safe because the rows are exactly `0..<count` and `stages.count == stageCount`), builds the file name from the loop index, and passes `plan.expected.index` (equal to the loop index) as the stage index.
- The name check is Swift `String ==`, which is **canonical equivalence**: probe, `"Francés"` in NFC equals the same word in NFD on iOS. Kotlin's `==` compares UTF-16 units and would refuse a stage whose name differs only in normalization. Every live string is NFC (§12), so plain `==` gives the same answer on the live data; for exact parity compare `Normalizer.normalize(_, NFC)` of both sides. Recommendation: normalize (one line, documented as Swift's `==`); the same applies to the ledger's name match on Update (P2, U33).
- `stage.count` is checked here only; `way(from:)` checks it only against `stage.index` and the 200 ceiling.
- The temp file per stage is `<index>.way.json` in the store's own encoding (`[.sortedKeys]`, `.iso8601`), not the wire bytes, plus the wire `route.json` (P2: the temp set's location is flow gap 5).

### 12. The live dataset against iOS's rules (`v1.12.0`, probed 2026-10-02)

`live_probe.py` (a scratch probe, not committed) runs the live `@main` index (the live CDN index fetched on 2026-10-02) through `parse` and `grouped`, then every listed route's `route.json` and every `stage-NN.json` (names from `stageFileName`, read with `git show v1.12.0:` from `../open-pilgrimages`) through `route(from:)`, `way(from:)`, `validate`, and the package's three identity checks, with Foundation's first-wins rule for repeated keys and a record of float literals in integer fields. Output: `live_probe.out`.

**Every listed route installs on iOS as shipped.** No bound, cap, identity check or decode fails anywhere.

| Route | Stages | `route.json` (B) | Largest stage (B) | Total received (B) | Index `bytes` | Verdict |
|---|---|---|---|---|---|---|
| `camino-frances` | 33 | 7,972 | 81,538 | 1,050,535 | 1,050,535 | walkable |
| `camino-norte` | 34 | 8,284 | **121,017** (`stage-07.json`) | **1,385,811** | 1,385,811 | walkable; stage 7 carries **exactly 400 marks** (the cap, `<=` passes); stage 5's Guernica has 30 local names, cut to 20 |
| `kumano-kodo-kohechi` | 4 | 1,504 | 24,365 | 80,961 | 80,961 | walkable |
| `kumano-kodo-nakahechi` | 4 | 1,359 | 28,712 | 65,104 | 65,104 | walkable |
| `shikoku-88-awa` | 5 | 1,834 | 57,000 | 176,127 | 176,127 | walkable; `difficulty ""` |
| `shikoku-88-iyo` | 14 | 3,900 | 46,989 | 481,988 | 481,988 | walkable; `difficulty ""` |
| `shikoku-88-sanuki` | 6 | 2,249 | 85,875 | 261,012 | 261,012 | walkable; `difficulty ""` |
| `shikoku-88-tosa` | 15 | 4,138 | 32,768 | 388,834 | 388,834 | walkable; `difficulty ""` |

Unlisted (no `ways`): `camino-ingles`, `camino-portugues`, `camino-primitivo`, `kumano-kodo-iseji`, `kumano-kodo-ohechi`.

| Check | Live result | Against the cap |
|---|---|---|
| Index size | 9,423 B (1,949 B as Brotli) | 256 KiB: 3.6% |
| Largest `route.json` | 8,284 B | 512 KiB: 1.6% |
| Largest stage file | 121,017 B | 2 MiB: 5.8% |
| Largest route total | 1,385,811 B | 50 MiB: 2.6% |
| Index `bytes` vs bytes received | equal for every route (`route.json` + stages; `report.json` excluded) | never compared by iOS |
| Route points | max 552 | 2,000 |
| Moments per stage | max 17 | 200 |
| Marks per stage | **max 400** (`camino-norte` stage 7) | 400: at the cap |
| `offLineMeters` | max 300 (the build drops beyond 300) | 100,000 |
| `t`, `theirActiveSeconds` | max 59,400 s | 604,800 |
| `gainMeters` | max 1,419 | 30,000 |
| `hours.max` | max 19 | 100 |
| Warnings per stage | max 2 | 20 |
| Shortest stage line | 3,555.6 m | 20 m |
| `sitMinutes` | 218 moments, all `5` | 0–1,440 |
| Moment kinds | 505 `waypoint`, nothing else | others skipped |
| Mark kinds | `transport` 1,770, `food` 1,528, `water` 1,245 (613 within 60 m of the line), `bed` 820, `supply` 734, `medical` 409 | unknown dropped |
| Icons | `house.lodge` 237, `seal` 136, `building.columns` 81, `eye` 35, `book.closed` 16 | cap 64 |
| Local-name keys | 46 distinct, all `[a-z]{2,3}` (`ast` 8 times, the only three-letter key); 232 moments carry names; 170 names equal their label | `[a-z]{2,3}`, 20 pairs |
| `difficulty` | `hard` 21, `moderate` 42, `easy` 12, **`""` 40** (all four Shikoku sections), same on stage files and route rows | kept, cut to 80 |
| `tzIdentifier` | absent from every stage file | optional |
| `departedAt` | whole-second UTC: `2026-04-10T00:00:00Z`, `2026-08-19T00:00:00Z`, `2026-09-08T00:00:00Z`, `2026-09-09T00:00:00Z` | parses |
| First point `t` | 0 on every stage | not rebased anyway |
| `title` vs `stage.name` | equal on every stage | not compared |
| `stage.name` vs route row | equal on every stage (exact, not only canonically) | must match |
| File `totalDistanceMeters` vs haversine | within 1 m on every stage | file value ignored |
| `pin` vs `at` | 505 of 505 moments carry a `pin`, and every one differs from `at` | both kept |
| Moment frac ties | 17 stages (`wp-sjpp` / `wp-sjpp-pilgrim-office` at 0, …) | broken by id |
| Duplicate moment or mark ids within a stage | none | |
| Moment and mark ids | all printable ASCII | |
| Strings | 16,425 checked: no cap exceeded in graphemes or UTF-16 units; none outside the BMP; all NFC; no control or format characters. Closest: `camino-norte`'s `summary`, **exactly 600** (the cap), `shikoku-88-sanuki`'s 595 | every cap |
| Integers written as floats | none | (Android would refuse them) |
| Repeated keys | none | (first wins on iOS, last on Android) |

**Keys iOS ignores** (all post-dated or never decoded; Android's `ignoreUnknownKeys` covers them):
- index: `schemaVersion`, `generatedAt`;
- index route rows: `topology`, `path`, `pilgrimage`, `variants`;
- index pilgrimages: `kind`, `distanceKm`, `stageCount`, `stats` (and `circular` in the fixture);
- `route.json`: `schemaVersion`, `stampHours` (Shikoku; added after the pin, consumed only by iOS PR #91 if it lands, Scope Boundaries) and `cover` (fixture only);
- stage files: `schemaVersion`.

The schema (`../open-pilgrimages/schema/way.schema.json`, `way-route.schema.json`, `index.schema.json`) is tighter than iOS on almost every bound they share (route ≤ 1,000 points, `offLineMeters` ≤ 300, `sitMinutes` 1–60, `hours` ≤ 48, warnings ≤ 10, `difficulty` in `easy moderate hard expert ""`). One exception: the index schema allows `ways.bytes` up to 52,428,800 inclusive, which iOS drops (`0..<maxPackageBytes`). The schema says nothing about point order, line length or the identity chain; the build enforces those. iOS's bounds are the contract the app enforces; port iOS's, not the schema's.

### 13. The flow-analysis gaps that touch the wire and the catalog

| Gap | What iOS does | What Android must do |
|---|---|---|
| 5. Temp set location (U34) | `FileManager.default.temporaryDirectory/pilgrimage-<UUID>/`, removed by `defer` on every exit (`PilgrimagePackageManager.swift:159-161@7c200bf`); the system may purge `tmp/` when the app isn't running | As the plan says: under `noBackupFilesDir`, never `cacheDir`, swept at launch except for a download in flight (P2 owns the sweep). The bodies are buffered in memory and written per file, so the set is at most `route.json` plus one `<i>.way.json` per stage |
| 9. Launch work mid-walk (U34) | `installed()` writes (it finishes an interrupted Replace and clears the marker); the catalog screen calls it on every load (`PilgrimageCatalogView.swift:223`) | The catalog ViewModel calls it in the UI process only; never from `:tracker`, where the download-in-flight flag is always false (P2). The catalog service itself writes only its own cache and previews |
| 11. Cache location | `Application Support/Pilgrimages/`, backed up and transferred (§8) | `filesDir/Pilgrimages/` recommended (O1) |
| 11. Clock set backwards | Signed difference: a future `fetchedAt` is fresh until the clock passes it plus 24 h (§8) | The same signed wall-clock comparison, with a test |
| 11. `WayStore.list()` decoding stage Ways | iOS's `list()` decodes every Way, stages included, and the callers filter (P2) | P2/U33; nothing in the catalog lists the store |
| 12. Task restore | The route page is pushed with the `PilgrimageCatalogEntry` value and the catalog's `release` at push time (`PilgrimageCatalogView.swift:90-94`); iOS restores no navigation state | A restored route page has only what its nav arguments carry. It needs the whole entry (`stageCount` for the identity check, `name`, `country`, `distanceKm`, `sparse` for the header) and the release. Either serialize both into the route (the entry is small and `@Serializable`), or carry the route id and re-resolve from `PilgrimageCatalogService.load()` (unforced, so a cache read), going back to the catalog if the id is gone. Recommendation: carry the id and re-resolve, since the release must be the catalog's current one, not a stale saved string (P4/U37) |
| 13. Backgrounded download (U41) | A foreground `URLSession`; a suspended app's request idles past its 30 s request timeout, and the walker comes back to "the download didn't finish" | OkHttp's 30 s read timeout fires the same way once a frozen cached process resumes. A device-pass row: background the app mid-download for a minute, return, expect "the download didn't finish" and a clean phone |
| 14. Release flag off | The catalog is fetched only when its screen opens (`.task`), never at launch or in the background (the design spec's "background index refresh" is deferred, §8 of that spec) | The catalog service fetches only when asked. Unlike `CollectiveRouteCatalogService`, it must not load at construction (`P/data/collective/routes/CollectiveRouteCatalogService.kt:67-83@0defff85`); with the flag off nothing constructs or calls it |
| 15. Device transfer | The ledger and the package (under `Ways/`) stay behind; the catalog cache and previews travel | Ledger and package under `no_backup` (P2), as on iOS; the catalog cache per O1 |

### Strings table

Every user-visible string this cluster's code produces, verbatim. None says "they", "their" or "them" (R14): the cluster's copy is clean. Strings that only P4's screens draw are listed under P4; the catalog and route models' strings appear here because their tests live in this cluster's test file.

| String | Where it shows | Arguments | Source |
|---|---|---|---|
| `this route isn't walkable yet` | route page footer after a failed install; route page stage section after a failed preview | none | `PilgrimageCopy`, `PilgrimageWayImporter.swift:18@7c200bf` |
| `the download didn't finish` | route page footer | none | :19 |
| `not enough space on this phone to save these voices` | route page footer on disk full (the share importer's line, reused; D1) | none | `HonorImportReducer.swift:34@7c200bf`, via :20 |
| `not enough space on this phone` | never (dead fallback) | none | :20 |
| `finish your walk first` | route page footer (and P2's guard) | none | :21 |
| `the routes are out of reach right now` | catalog: the unreachable view (body, `fog`, centred) and the rust line over a list after a failed retry; route page: a failed preview | none | :22 |
| `more map than can be saved at once` | Stage 21-3 only | none | :23 |
| `update ready` | catalog row badge, as its accessibility label (glyph `arrow.down.circle.fill`, `stone`) | none | `PilgrimageCatalogView.swift:38@7c200bf` |
| `on your phone` | catalog row badge, accessibility label (`checkmark.circle.fill`, `moss`) | none | :39 |
| `few places marked yet` | catalog row, own line under the card line | none | :47 |
| `<country> · <distance> · 1 stage` / `… · N stages` | catalog card line, not installed | country (omitted when nil or empty), distance in the walker's unit, the index's stage count | :10-20 |
| `stage N of M · <distance> walked` / `you have walked the whole way · <distance>` | catalog card line, installed (and P2's route page) | next stage (1-based), stage count, total walked | `PilgrimageLedger.swift:87-96@7c200bf` (P2) |
| `Pilgrimages`, `Close`, `try again` | catalog screen title, toolbar, retry | none | `PilgrimageCatalogView.swift:76-87,156` (P4) |
| `reaching for the stages…` | route page while a preview loads | none | `PilgrimageRouteView.swift:271` (P4) |
| `Download`, `Update`, `On your phone` | route page button | none | `PilgrimageRouteView.swift:52-54` (P4); tested in this cluster's file |
| `start with stage 1`, `next: stage N`, `continue from where you stopped`, `you have walked the whole way` | route page next row | N | P2/P4; tested in this cluster's file |
| `the route's stages were redrawn; your kilometres are kept.` | route page, once after an Update | none | P2/P4; tested in this cluster's file |

### Test inventory

Fixtures (copy byte-for-byte to `app/src/test/resources/honor/pilgrimage/`; iOS reads them from the source tree by `#filePath`, `UnitTests/Fixtures/PilgrimageFixtures.swift:8-19@7c200bf`; Android reads them as classpath resources):

| File | What it carries |
|---|---|
| `UnitTests/Fixtures/Pilgrimage/index.json` | release `v1.7.0`; `camino-frances` (2 stages, 214,000 B, `placesPerStage` 0.4, `sparse` true, names `en` "Camino de Santiago (Frances)" and `es`); `camino-norte` with no `ways`; `../etc/passwd` with `ways` but no coverage fields (the "older index" row); no `pilgrimages` |
| `UnitTests/Fixtures/Pilgrimage/index-pilgrimages.json` | release `v1.9.1`; six rows in id order; `shikoku-88` (`legs`, `circular`) with sections awa, tosa, iyo, sanuki; `camino-de-santiago` listing `camino-primitivo` (absent); `kumano-kodo` listing only absent sections; `st-cuthberts-way` unclaimed |
| `UnitTests/Fixtures/Pilgrimage/route.json` | `camino-frances`, 2 stages, `names` es/gl, `summary`, `cover` |
| `UnitTests/Fixtures/Pilgrimage/stage-00.json` | 11 points on the equator (~1,000 m), three waypoints (`wp-saint-jean` 0.0, `wp-orisson` 0.3 with text, names eu/fr, `sitMinutes` 5, `at` ≠ `pin`, `wp-roncesvalles` 1.0), marks in file order 0.5 water 12 m, 0.7 water 250 m, 0.3 food 20 m, stage block 0 of 2, `tzIdentifier` Europe/Madrid |
| `UnitTests/Fixtures/Pilgrimage/stage-01.json` | 3 points, one waypoint, `"marks": []`, no warnings |

The string-replacement tests depend on the fixtures' exact whitespace (`"\"frac\": 0.3,\n      \"kind\": \"waypoint\""` and so on): a reformatted fixture breaks them silently into "the fixture no longer contains that text". Byte-identical copies, and keep each test's "the replacement changed something" assertion.

`UnitTests/Helpers/StubURLProtocol.swift` answers by exact URL string; an unknown URL fails with `URLError(.notConnectedToInternet)`; the default headers are `Content-Length: <body.count>` and `Content-Type: application/json`, and a test drops `Content-Length` by passing its own headers; it responds with `cacheStoragePolicy: .notAllowed`, so iOS's tests never exercise the session's memory cache. Android's equivalent is `MockWebServer` (or `ConnectionCountingServer`, `T/data/honor/ConnectionCountingServer.kt@0defff85`) with an injected base URL: `setBody` declares a length, `setChunkedBody` declares none; an unknown URL is a dispatcher returning a refused connection, or no server at all.

**`UnitTests/Honor/PilgrimageWayImporterTests.swift` (20) → U31** (two already ported, two belong to U33):

| # | Test | Asserts | Android |
|---|---|---|---|
| 1 | `testFixturePackageIsReadable` | the four fixtures load non-empty | U31 |
| 2 | `testTheFixtureIndexCarriesTheSparseFlag` | `index.json` row 0 carries `placesPerStage` 0.4 and `sparse` true; the last row has neither | U31 |
| 3 | `testAStageWayCarriesMarksAndAStageBlock` | a stage `Way` with marks, stage, text, names, `sitMinutes`, `pin` round-trips through the codec | ported: `WayCodecTest` "a stage way carries marks and a stage block" |
| 4 | `testAWayWrittenBeforeStagesStillDecodes` | an old share `way.json` decodes with nil marks, stage, text, pin | ported: `WayCodecTest` "a way written before stages still decodes" |
| 5 | `testTheStoreAcceptsStageIdsAndRefusesEverythingElse` | `isValidId` takes `:0`, `:199`; refuses `pilgrimage`, `pilgrimage:../etc:0`, `pilgrimage:Camino:0`, `:1000`; `isValidRouteId`; `stageWayId(camino-frances, 7)` | `isValidId` half in `WayStoreTest`; `isValidRouteId` and `stageWayId` with U33 |
| 6 | `testAStageWayRoundTripsThroughTheStore` | a stage Way saves and loads; `pilgrimageDirectory` ends `pilgrimage/camino-frances`, nil for `../etc`; `list()` contains the stage | U33 (note: Android's `list()` will skip stage ids per the plan, so this assertion changes; record it) |
| 7 | `testDecodesTheFixtureStage` | id, source, title, nil expires and weather, 11 points, total ≈ 1,000 m (±5), 28,800 s, `Europe/Madrid` | U31 |
| 8 | `testMomentsCarryTextLocalNamesSitMinutesAndAPin` | moment order; Orisson's label, icon, text, `names["eu"]`, `sitMinutes` 5, `at` (0, 0.002694), `pin` (0, 0.0027); Saint-Jean has no text | U31 |
| 9 | `testMarksAndTheStageBlockSurvive` | marks in file order, kinds, name, `offLineMeters` 12; every stage-block field | U31 |
| 10 | `testTheStageMustMatchTheRouteAndIndexItWasFetchedFor` | index 4, route `camino-norte`, route `../etc` → `notWalkable` | U31 |
| 11 | `testOutOfRangeStageFieldsAreNotWalkable` | eleven one-field mutations → `notWalkable`: moment frac 1.4, point lat 991, moment `at` lat 991, `pin` lon 999, mark `at` lat 991, mark frac 1.4, `sitMinutes` 999999999, `distanceKm` 1e300, `count` 900, mark frac -0.5, hours max 1e400 | U31 (1e400 fails Foundation's decode but passes kotlinx's, then the finiteness check; the outcome matches) |
| 12 | `testFreeTextIsCappedAtParseTime` | a 5,000-character theme → 80; text → 600 | U31 |
| 13 | `testTooManyMomentsOrMarksIsNotWalkable` | 403 marks → `notWalkable` | U31 |
| 14 | `testUnknownMarkKindsAndMomentKindsAreSkippedNotFatal` | `helipad` mark dropped; `shrine` moment skipped | U31 |
| 15 | `testDecodesAStageFileMissingTzIdentifierWithAnUnknownSchemaVersionKey` | no `tzIdentifier` → nil; `schemaVersion` ignored | U31 |
| 16 | `testDecodesTheRouteFile` | id, name, `names["gl"]`, country, count, distance, summary, indices, difficulty, hours | U31 |
| 17 | `testARouteFileWhoseNumbersAreOutOfRangeIsNotWalkable` | `stageCount` 0, `distanceKm` 99999, id `../etc` | U31 |
| 18 | `testOneBasedStageIndicesAreNotWalkable` | indices 1, 2 → refused | U31 |
| 19 | `testADuplicateStageIndexIsNotWalkable` | indices 0, 0 → refused | U31 |
| 20 | `testEveryErrorHasItsOwnLine` | the six copy lines; disk full equals the share importer's | U31 |

**`UnitTests/Honor/PilgrimageCatalogServiceTests.swift` (37 = 24 service + 13 model) → U32 and U37:**

| # | Test | Asserts | Android |
|---|---|---|---|
| 1 | `testTheIndexIsReadFromTheBranchNotAMovingTag` | the index URL string exactly; no `@v1` | U32 |
| 2 | `testPackageURLsArePinnedToTheExactRelease` | the `stage-00.json` URL string; nil for `../etc`, release `main`, file `../../secret` | U32 (+ the plan's `stage-07`, `stage-107`, `route.json`) |
| 3 | `testParsesOnlyRoutesThatCarryAWaysEntryAndALegalSlug` | release; only `camino-frances`; name, `names["es"]`, country, count, bytes | U32 |
| 4 | `testTheSparseFlagAndItsDensityCarryThrough` | `sparse` true, 0.4; without the fields: false, 0 | U32 |
| 5 | `testAnAbsurdPlaceDensityDropsTheRoute` | 51, -1, 1e300 drop the row | U32 |
| 6 | `testADuplicateRouteIdInTheIndexKeepsOnlyTheFirst` | a repeated valid id keeps the first row | U32 |
| 7 | `testARepairedReleaseTagIsRefused` | `main`, `v1.7`, `1.7.0` → `catalogUnreachable` | U32 |
| 8 | `testOutOfRangeRouteNumbersDropTheRoute` | distance 20000, `stageCount` 900, bytes 99,000,000 drop the row | U32 |
| 9 | `testFetchesOnceAndThenServesTheCacheForTwentyFourHours` | one request; at +23 h a new service makes none; at +25 h one more | U32 |
| 10 | `testAnIndexBiggerThanTheCapIsNeverBuffered` | a declared 256 KiB + 1 body → `catalogUnreachable` | U32 |
| 11 | `testAnIndexThatNeverDeclaresItsLengthIsRefusedByTheCapCountedWhileStreaming` | the same, undeclared → `catalogUnreachable`, `catalog` nil | U32 (`setChunkedBody`) |
| 12 | `testAnIndexServedAsNotFoundIsOutOfReach` | a good body behind 404 → `catalogUnreachable`, `catalog` nil | U32 |
| 13 | `testAFailedFetchWithACachedIndexDegradesSilently` | at +48 h, offline, the stale cache is returned | U32 |
| 14 | `testAFailedFetchWithNoCacheIsOutOfReach` | offline, no cache → `catalogUnreachable`, `catalog` nil | U32 |
| 15 | `testSectionsAreOrderedAsTheirPilgrimageWalksThemNotAsTheIndexListsThem` | routes in id order; Shikoku's group awa, tosa, iyo, sanuki; its name | U32 |
| 16 | `testTheShikokuSectionsReadAsAForwardRunOfTempleNumbers` | first temples 1, 23, 39, 65 | U32 |
| 17 | `testASectionWithNoDownloadablePackageIsSkippedAndItsPilgrimageSurvives` | Camino group is only `camino-frances` | U32 |
| 18 | `testAPilgrimageWithNothingToWalkIsNotListed` | no `kumano-kodo` group | U32 |
| 19 | `testARouteBelongingToNoPilgrimageIsStillOffered` | last group nameless, `st-cuthberts-way` | U32 |
| 20 | `testEveryRouteAppearsInExactlyOneGroup` | grouped ids equal route ids, no repeats | U32 |
| 21 | `testAnIndexWithNoPilgrimageBlockKeepsEveryRouteUnderNoHeader` | one nameless group holding every route | U32 |
| 22 | `testTheRoutePreviewArrivesBeforeAnythingIsDownloaded` | preview stages; one request; a second service's preview makes none | U32 |
| 23 | `testAPreviewThatDoesNotMatchTheEntryIsNotWalkable` | entry `stageCount` 5 vs file 2 → `notWalkable` | U32 |
| 24 | `testAPreviewWithNoNetworkIsOutOfReach` | no stub → `catalogUnreachable` | U32 |
| 25 | `testACardWithoutAPackageJustCountsTheStages` | "ES · <764 km in the unit> · 33 stages" | U37 (catalog model) |
| 26 | `testACardWithAPackageCarriesItsProgressAndLeavesInstallToTheBadge` | installed card has "stage 2 of 33", not "on your phone" | U37 |
| 27 | `testTheBadgeMarksTheInstalledRouteAndNothingElse` | nil unless installed; "on your phone" | U37 |
| 28 | `testAWaitingUpdateDoesNotClaimToBeAlreadyUpdated` | "update ready", a different glyph | U37 |
| 29 | `testEveryBadgeSpellsItselfOutForVoiceOver` | label and symbol non-empty | U37 |
| 30 | `testASparseRouteSaysSoWithoutHidingItself` | "few places marked yet" or nil; never in the card line | U37 |
| 31 | `testStageLineReadsDistanceClimbHoursAndDifficulty` | prefix distance, gain, "7 to 9 hours", suffix "hard" | U37 (P4's facts formatter) |
| 32 | `testAStageWithOneHourFigureDoesNotSayItTwice` | "4 hours", never "4 to 4" | U37 |
| 33 | `testTheMapsRowIsHeldOnlyWhileThePackageDownloads` | the maps row is held only while downloading | Stage 21-3 |
| 34 | `testTheStageFactsFormatterIsTheOneBothCallersUse` | one formatter; empty difficulty adds no separator; NaN/∞ hours clamp to "0 to 100 hours" | U37/U38 |
| 35 | `testTheNextRowOffersResumesAndFinallyCongratulates` | the four next-row forms | U37 (P2's ledger) |
| 36 | `testTheButtonSaysWhatItWillDo` | Download / Update / On your phone | U37 |
| 37 | `testTheRedrawNoticeIsTheSpecsWords` | the redraw notice string | U37 |

Android tests to add beyond iOS's (each pins a fact above that iOS's tests leave open): exactly-cap bodies pass and cap + 1 fail (index 262,144; route 524,288; stage 2,097,152); a cache exactly 24 h old refetches; a clock moved back 48 h after a fetch makes no request; a forced load with a stale cache, offline, returns the cache without an error; a parse with zero routes is cached; `"stageCount": 33.0` and `"distanceKm": 1e400` in one row (the pinned kotlinx differences); `ways.bytes` of 3,000,000,000 drops one row, not the catalog (the `Long` rule); two pilgrimages sharing an id; the Guernica 30-name cut (sort, cut to 20, filter); `marks` is `[]` for `stage-01.json`; the `Way`'s `totalDistanceMeters` is the haversine length, not the file's; a point list whose first `t` isn't 0 is kept as written; the preview file name and that a cached preview makes no request; no `Log` call anywhere under `data/honor/pilgrimage/` (a review check, or a source-scan test if the team wants it enforced).

### Corrections to the Android plan

| # | Plan says | iOS at `7c200bf` | Fix |
|---|---|---|---|
| C1 | Key Technical Decisions: "with the index fetched through a client that has no HTTP cache, since the CDN sends a 7-day max-age" | iOS's ephemeral session **has** a 512,000-byte memory `URLCache` with `useProtocolCachePolicy`, and answers a repeat index request from memory (probed, §3.5) | Keep Android's no-cache client, and record it at the gate as a platform difference (A1); file D2 upstream |
| C2 | U32: "on a client with no HTTP cache, 15 s and 30 s timeouts …" (right for the catalog); U34 names no timeouts | The **package** session is 30 s request / **300 s** resource (`PilgrimagePackageManager.swift:58-63`) | U34: its own client, connect/read/write 30 s, call 300 s, no retry, no cache |
| C3 | U31 Approach: "**The identity cross-check:** `route.json` against the catalog entry, and each stage against `pilgrimage:<route>:<i>`, its route, index, count and name." | The importer checks only the request (`id`, `stage.routeId`, `stage.index`). `route.json` vs the entry lives in `routePreview` and `stageRouteFile`; `stage.count` and `stage.name` vs the route row live in `stageOneStage` | Port each check where iOS has it: U31 the request identity; U32 the preview's entry check; U34 the entry check and the count/name check. Move U31's test "a stage whose … count or name disagrees with the route row" to U34 |
| C4 | U31: "local names limited to `[a-z]{2,3}` keys, at most 20 pairs" | Sort by key, cut to the first 20 **raw** pairs, then filter keys and blank values (§5.3); live Guernica loses `pt`, `ru`, `zh` and seven more | Spell the order out; add the Guernica-shaped test |
| C5 | U32: "Test: … (ported, 37)" | 24 of the 37 test the service; 13 test `PilgrimageCatalogModel` and `PilgrimageRouteModel` (U37's models), one of those the 21-3 maps row | U32 ports 24; U37 ports 12; the maps-row test waits for 21-3 |
| C6 | Key Technical Decisions: "**The catalog cache** lives under `noBackupFilesDir/Pilgrimages/`" | `Application Support/Pilgrimages/`, not excluded from backup; only `Ways/` is | Recommend `filesDir/Pilgrimages/` (O1) |
| C7 | U32 Test: "Edge case (ported): a sparse route carries 'few places marked yet'" | The service carries `sparse: Bool`; the copy is `PilgrimageCatalogModel.sparseNote` | Service test asserts `sparse`; the copy test goes to U37 |
| C8 | U32: "Index rows: a route is listed only with valid `ways` and a valid id and distance." | Also `stageCount` in [1, 200], `bytes` in [0, 50 MiB) half-open, `placesPerStage` in [0, 50] when present, and a surviving display name; and a **decode** failure in any row fails the whole index | List all seven row rules (§7.2) and the whole-index rule |
| C9 | U32: "The name is `en`, else the first locale." | `en` if the key exists (an empty value included), else the value of the **alphabetically first** key among those matching `[a-z]{2,3}` | Say "alphabetically first valid key" |
| C10 | U32: "Error path: offline with a cache past 24 h serves the cache …"; "Retry forces one" | Also: a forced load that fails with a cache returns the cache **with no error**; freshness is a signed `<` 24 h; a zero-route parse is cached | Add the three tests (Test inventory, Android additions) |
| C11 | U32: "a body over 256 KB is refused" | Declared length first (`<=`), streamed bytes counted on the decoded body (`> cap` throws); exactly the cap passes | Test both paths and the boundary |
| C12 | U32: "**Package URLs:** `@<release>/routes/<id>/ways/<file>`, with the release and id validated before any URL or path is built." | Also the file name from a closed set, `\A(route\.json\|stage-[0-9]{2,3}\.json)\z` | Add the file-name rule and its nil test (`../../secret`) |
| C13 | U32 Patterns: "`CollectiveRouteCatalogService.kt`" | That service logs every failure (`Log.w`), caches the served bytes, and loads at construction | Follow its temp-then-rename write only; log nothing, cache the parsed catalog, fetch only when asked |
| C14 | Context: "Live `route.json` carries keys added after the pin (`stampHours`, `schemaVersion`)" | `schemaVersion` predates the pin (in `route.json` since 2026-09-04; iOS's own test feeds a stage file one); only `stampHours` is post-pin (2026-09-22, `c090e9b`) | Reword; the decoder ignores unknown keys either way |
| C15 | U34: "enforce the 50 MB total on bytes received" | Summed over `route.json` and every stage, checked after each file, `<=` passes; the index's `bytes` is never compared | Spell out; test a total of exactly the cap passing and one byte more failing, with an injected cap as iOS's own test does (`manager.maxPackageBytes = 1_000`, `UnitTests/Honor/PilgrimagePackageManagerTests.swift:180@7c200bf`) |
| C16 | U33 (by way of R22's "ported verbatim") and the Key Technical Decision "`WayStore.list()` skips `pilgrimage:` ids before decoding" | iOS's `testAStageWayRoundTripsThroughTheStore` asserts `store.list()` **contains** the stage Way | The port can't be verbatim: U33 inverts that assertion (`list()` excludes the stage) and adds the stage-id listing's positive assertion, with a dated note |
| C17 | U37: "the 'on your phone' and 'update ready' badges" | `hasUpdate` is `installed.release != catalog.release`, inequality, not "newer" | Port the inequality (D4 filed upstream) |
| C18 | U31 Test: "an empty `difficulty` is kept and skipped by every line" | Kept by the importer (cut to 80); "skipped" is the facts formatter's (`testTheStageFactsFormatterIsTheOneBothCallersUse`, U37/U38) | Split the assertion between U31 and U37 |
| C19 | U31 Approach: "**The build:** the Way's `source`, `stage`, `marks`, moments with `pin` and `at`, and geometry" | Also: `totalDistanceMeters` is the haversine length, not the file's; `t` isn't rebased; `marks` is `[]`, never nil; `title` and `tzIdentifier` are cut (120, 80); `expires`, `weather`, `spans` nil | Use §5.4's table |
| C20 | Survey §2: "The release must match `v\d+.\d+.\d+`" (an input, not the plan) | `\Av[0-9]+\.[0-9]+\.[0-9]+\z` | Use iOS's pattern with literal classes |

### Notes by unit

**U31 (importer, errors, fixtures)**
- Files: `P/data/honor/pilgrimage/PilgrimageModels.kt` (wire models: `StageFile`, `RouteFile` and their nested types; `PilgrimageRoute`, `PilgrimageRouteStage`; `PilgrimageError` with all six cases and `PilgrimageException(error)` carrying no message), `PilgrimageWayImporter.kt` (`way(from:routeId:stageIndex:)`, `route(from:)`).
- Constants, verbatim: `MAX_STAGE_BYTES = 2 * 1024 * 1024`, `MAX_ROUTE_BYTES = 512 * 1024`, `MAX_MARKS = 400`, theme 80, narrative 2,000, closing 400, warning 300, stage name 120, mark name 80, summary 600, `MAX_WARNINGS = 20`, `MAX_LOCAL_NAMES = 20`, `MAX_DISTANCE_KM = 10_000.0`, `MAX_STAGE_COUNT = 200`, `MAX_GAIN_METERS = 30_000.0`, `MAX_HOURS = 100.0`; and from `WayImporter`: route points 2,000, encounters 200, altitude 100,000 (strict), rest minutes 1,440, active duration 604,800, label 80, icon 64.
- Decode from bytes: check the length first (`<=` cap), strict UTF-8 (as `WayImporter.manifest` does, `P/data/honor/WayImporter.kt:241-253@0defff85`), the house `Json` (`ignoreUnknownKeys`, `explicitNulls = false`), no defaults on required fields, wire integers as `Long`. Catch `IllegalArgumentException` (kotlinx's `SerializationException` is one) and `CharacterCodingException` → `NOT_WALKABLE`, never with the message.
- Check order: §5.1 steps 1–6, then `validate`'s rows 1–18 (§5.2), then the build (§5.4). Moments and marks of every kind are counted and validated before kinds are filtered.
- Strings: `prefixCharacters` for every cut, `trimmingWhitespacesAndNewlines` for `text`, `summary` and local-name values; `localNames` as sort → cut 20 → filter.
- Moment order: the share importer's `BY_FRAC_THEN_ID`, made shared.
- The `Way`: §5.4, with `marks = emptyList()` when the file has none.
- `route(from:)`: §6, `names` an empty map when none survive.
- Fixtures byte-identical under `app/src/test/resources/honor/pilgrimage/`.
- Nothing logged.

**U32 (catalog service)**
- A `@Singleton` with a `StateFlow<PilgrimageCatalog?>` (iOS's `@Published catalog`), set on every successful return, never cleared; no work at construction.
- `INDEX_URL` as the exact string; `packageUrl(release, routeId, file)` returning null on any failed rule, built as one string; `isValidRelease` with `[0-9]`. Pin the strings in a test.
- Its own client: connect/read/write 15 s, call 30 s, `retryOnConnectionFailure(false)`, no cache, redirects per O2. Streamed read in chunks (the share importer's `readCapped` shape, `P/data/honor/WayImporter.kt:147-159@0defff85`) with the declared length checked first; 200 only; every failure `CATALOG_UNREACHABLE`; cancellation rethrown.
- `parse`: §7.2–§7.4 exactly; `PilgrimageCatalog(release, routes, groups = null)` defaults to one loose group of every route.
- `load(force)`: §8's four steps; the plan's mutex around it; the signed 24 h test on a wall clock (`Clock`, as `WayImporter` takes one).
- The cache: `catalog.json` holding `fetchedAt` and the parsed catalog, written temp-then-rename, read strictly, any failure = absent. Location per O1.
- `routePreview(entry, release)`: §9; files `route-<id>-<release>.json` beside the cache; the raw bytes written.
- `PilgrimageCatalogEntry` is `@Serializable` (it may ride a nav argument, flow gap 12).
- DI: a binding for the client (a qualifier like `WeatherHttpClient`, `P/di/NetworkModule.kt:189-191@0defff85`) and for the base directory, so tests inject a MockWebServer URL and a temp folder.

**U33 (store tree)** facts from this cluster: `isValidRouteId` = `[a-z0-9-]{1,64}` whole-input; `stageWayId` = `"pilgrimage:$routeId:$stageIndex"`; `release.txt` is the bare tag with no newline (the anchored rule refuses a trailing one); the ledger's name match on Update is Swift `==`, so compare NFC-normalized strings for exact parity (§11). C16 on porting `testAStageWayRoundTripsThroughTheStore`.

**U34 (package manager)** facts from this cluster:
- Session: 30 s idle / 300 s whole fetch, its own client.
- Files in order: `route.json` (512 KiB cap), then `stage-%02d.json` / `stage-%03d.json` for `0 until route.stageCount` (2 MiB each), all via `packageUrl`; a null URL is `NOT_WALKABLE`.
- Total ≤ 50 MiB on bytes received, checked after each file (`<=`).
- Fetch errors: non-200, declared or streamed over the cap, transport → `INCOMPLETE`; `isDiskFull` → `DISK_FULL`; cancellation passes.
- Identity: §11's three pairs; the stage-name comparison NFC-normalized.
- Temp files: wire `route.json`, and `<index>.way.json` in `WayJson`'s encoding.
- Phase total `entry.stageCount + 1`; `done` 1 after `route.json`, `index + 2` after each stage.

**U37 (doors and catalog screens)** facts from this cluster:
- The catalog ViewModel's inputs and the per-row rules are §10's; `installed()` once per load (UI process only).
- Ledgers for every listed route.
- `hasUpdate` by string inequality.
- Card line from the index's `stageCount`.
- `LazyColumn` keys by entry id, group headers by position (A6).
- "try again" calls `load(force = true)`; the pop-back reload calls `load()`.
- The rust line shows only when a load throws while a catalog is already held.
- A zero-route catalog renders the unreachable copy (P4).
- The route page needs the entry and the current release (flow gap 12).

**U41 (device pass)** rows from this cluster: the catalog online; offline with a cache older than 24 h (listed, no error); offline with no cache ("the routes are out of reach right now", then "try again" once the radio is back); the badge reads "update ready" (not iOS's doc's "updated") when `release.txt` differs from the index; backgrounding mid-download ends in "the download didn't finish" and leaves nothing installed.

### Android additions to record at the gate

| # | Difference | Reason |
|---|---|---|
| A1 | No HTTP cache on the catalog and package clients; every load past 24 h and every Retry reaches the network | iOS's ephemeral session keeps a memory `URLCache` that honours the CDN's 7-day `max-age` within a process (§3.5, D2); OkHttp has none unless configured, and none is wanted |
| A2 | (If O2 is accepted) redirects followed only within `cdn.jsdelivr.net` | Same posture as the share importer's R6 addition; iOS follows any HTTPS host |
| A3 | Cancellation is rethrown by the catalog fetch, not mapped to `catalogUnreachable` | Structured concurrency; the cancelled load's screen has gone, so nothing visible changes |
| A4 | `load` runs under a mutex (single-flight with the TTL check inside) | Android's ViewModels can overlap loads across configuration changes; iOS interleaves two loads that both fetch. No visible change |
| A5 | `fetchedAt` stored to the millisecond | iOS's `.iso8601` writes whole seconds; the window differs by under a second |
| A6 | Catalog list items keyed by entry id and position, never by group id alone | Compose's `LazyColumn` throws on a repeated key where SwiftUI's `ForEach` tolerates a duplicate pilgrimage id |
| A7 | Wire integers decoded as `Long`, narrowed after the range check | Kotlin's `Int` is 32-bit; without this an out-of-range `bytes` would fail the whole index instead of one row (parity-preserving) |
| A8 | Disk reads, decodes and the parse run on `Dispatchers.IO` | iOS does them on the main actor; a platform equivalent |
| A9 | Decoding differences kept, not emulated: a quoted number reads, an integer written `1.0` fails, a repeated key keeps its last value, `1e400` in an index row drops the row instead of failing the index | kotlinx versus Foundation, as recorded in S1 §2.4; no live file triggers any (§12) |
| A10 | Stage-name comparisons NFC-normalized | Swift's `String ==` is canonical equivalence; this is its Kotlin equivalent, not a change |
| A11 | (If O1 goes the plan's way) the catalog cache under `noBackupFilesDir` | iOS's is backed up and transferred |

### iOS defects (matched as shipped)

| # | Defect | Evidence | What the walker sees | Severity |
|---|---|---|---|---|
| D1 | A route download that fills the disk says "not enough space on this phone to save these voices" | `PilgrimageWayImporter.swift:20@7c200bf` reuses `HonorImportReducer.swift:34`; the `??` fallback is dead | A pilgrimage screen speaks of voices that don't exist | Low. Extends pilgrim-ios #114 item 3 (which already suggests a line of its own for a non-voice save) |
| D2 | The catalog's ephemeral session serves the index from memory for up to 7 days within one process, under the CDN's `max-age=604800` | §3.5 probes; the code's comment expects the 24 h cache to sit on jsDelivr's 12 h cycle (`PilgrimageCatalogService.swift:80-85`) | In a long-lived process a new release, or a newly listed route, doesn't appear until the app restarts | Low. Settles S1's open question 1 for `tour.json` too (`max-age=3600`); new |
| D3 | `localNames` cuts to 20 pairs before filtering invalid keys, alphabetically | §5.3; live Guernica keeps 20 of 30, dropping `pt` | Today none (the card finds `eu` first); a place with many names can lose a preferred language by spelling | Note only; new |
| D4 | "update ready" means "different", not "newer" | `PilgrimageCatalogView.swift:200-202`, `PilgrimageRouteView.swift:107`; the design spec said "newer" | A stale cached index, or a CDN edge serving an older `@main`, offers an Update that downgrades the installed route | Low; new |
| D5 | Route previews are never deleted | §9; one `route-<id>-<release>.json` per route per release viewed | A few kilobytes accumulate in Application Support | Note only; survey defect 8's second half, confirmed |
| D6 | An installed route has no door when the index no longer lists it, or when there is no readable catalog cache and no network | rows come only from `catalog.groups` (`PilgrimageCatalogView.swift:119-129`); `load` throws without a cache | Can't open the route page to walk the next stage; only a Replace from another route's page lets it go | Low; survey defect 7, confirmed for the catalog half (P2 owns the rest) |
| D7 | One undecodable row (a `null` in a name map, a quoted number) fails the whole index, against the code's own promise ("one bad row must not cost the pilgrim every route", :276-280) | §4.1, §7.2 | "the routes are out of reach right now", or yesterday's cache, for every route | Low (malformed data only); new |
| D8 | A preview that 404s (an index naming a release whose tag lacks the route's files) reads "the routes are out of reach right now" with a "try again" that can never succeed | `routePreview` :184 maps every fetch failure to `catalogUnreachable` | A retry loop on the route page; the download of the same route says "the download didn't finish" | Low; new |
| D9 | Three ceilings for one 50 MiB: the index row drops `bytes == 52,428,800`, the download accepts exactly that many, and the dataset schema allows it | `PilgrimageCatalogService.swift:288`, `PilgrimagePackageManager.swift:306-308`, `index.schema.json` | Nothing today (largest route 1.4 MB) | Note only; new |
| D10 | A moment without `label` becomes `""`, an empty kicker on the card | `PilgrimageWayImporter.swift:215`; the schema requires `label`, iOS doesn't | An unlabelled place card | Note only; extends pilgrim-ios #118 item 4 |

Survey §5 candidates in this cluster: **7 confirmed** (D6, catalog half); **8 confirmed** for the previews (D5), its failed-Update half is P2's. Candidates 1–6 and 9 belong to P2–P5. No defect in this cluster says "they" or "their".

### Proposed owner decisions

| # | Decision | Options | Recommendation |
|---|---|---|---|
| O1 | Where the catalog cache and route previews live | `filesDir/Pilgrimages/` (iOS's `Application Support` equivalent: carried by a device transfer, as iOS's is by backup and migration; `CollectiveRouteCatalogService`'s precedent) or `noBackupFilesDir/Pilgrimages/` (the plan) | `filesDir`: parity, and a new phone can list routes offline on first open. The cache heals itself either way |
| O2 | Redirect policy for the CDN clients | Follow any HTTPS redirect (iOS) or stay on `cdn.jsdelivr.net` (the share importer's R6 posture) | Stay on the CDN host, recorded as A2; jsDelivr doesn't redirect these paths, so nothing visible changes |

---

## P2. Package, store and ledger: downloads, Replace/Update/Remove, the Ways tree, the ledger, finalize and recovery

| | |
|---|---|
| iOS pin | pilgrim-ios `7c200bf` (v2.0.0) |
| Android HEAD | pilgrim-android `0defff85` (branch `docs/stage21-2-plan`) |
| Feeds | U33 (the Ways store's pilgrimage tree, `retireMany`, the ledger), U34 (the package manager), U36 (finalize: the ledger record, recovery), U40 (the Settings → Ways footer) |
| Lenses | Behavior, UI/visual, Data, Edge cases |


**Read in full at `7c200bf`:** `Pilgrim/Models/Honor/PilgrimagePackageManager.swift` (455), `PilgrimageLedger.swift` (188), `WayStore.swift` (252), `HonorPersistence.swift` (45), `Pilgrim/Models/Walk/WalkCheckpoint.swift` (49), `WalkSessionGuard+Recovery.swift` (259), `Pilgrim/Scenes/Root/MainCoordinatorView.swift` (446), `Pilgrim/Scenes/Settings/WaysListView.swift` (114), `Pilgrim/Scenes/Honor/PilgrimageRouteView.swift` (401, for the package call sites and `isBusy`; reader P4 owns the screen). Read in part: `WayMediaDownloader.swift` (the slice-two delta, `isDiskFull`, `a048460`), `WalkSessionGuard.swift` (checkpoint cadence and the Honor fields), `ActiveWalkViewModel+Honor.swift` (`teardownHonor`, `honorCheckpointState`), `AppDelegate.swift` (launch: `reconcileTilesAtLaunch`, `sweepExpiredWays`), `MainTabView.swift` (the walk cover and the save-failed alert), `DataManager.swift` (`saveWalk` completes on main), `Way.swift` (`isPackageOwned`), `HonorImportReducer.swift` (the disk-full copy the route page borrows), `PilgrimageWayImporter.swift:1-71` (errors, copy, caps), `PilgrimageCatalogView.swift` (`card`, the `installed()` read), `HonorSummarySection.swift:46-53` (the summary's ledger read). Tests read in full: `PilgrimagePackageManagerTests.swift` (472), `+Fixtures` (140), `+Lifecycle` (206), `+Streaming` (46), `PilgrimageLedgerTests.swift` (192), `PilgrimageStageWalkTests+Recovery.swift` (135), `WaysListModelTests.swift` (42); the stage parts of `WayStoreTests.swift` and `PilgrimageStageWalkTests.swift` (`:115-192`, `:420-437`). Design intent: `docs/superpowers/specs/2026-09-03-honor-slice-two-pilgrimage-stages-design.md` §2 and §5, and `docs/honor-slice-two-device-pass.md`.

**Android compared at `0defff85`:** `P/data/honor/WayStore.kt` and `T/data/honor/WayStoreTest.kt`; `P/walk/honor/HonorFinalizer.kt`; `P/data/honor/HonorSessionEntity.kt`, `HonorDao.kt`; `P/data/WalkRepository.kt` (`finishWalkAtomic`, `runHonorFinalize`); `P/walk/WalkControllerImpl.kt` and `UiWalkController.kt` (the three finalize call sites); `P/ui/settings/data/WaysListViewModel.kt` (`WaysAvailability`, `WaysListModel`); `P/honor/WaySweeper.kt` (`HonorBeginsInFlight`); `P/honor/HonorLinkRouter.kt` and `PilgrimNavHost.kt` (`walkScreenUp`); `P/honor/BeginHonorWalk.kt`, `HonorStartRefusal.kt`; `P/honor/HonorWalkRecords.kt`; `P/PilgrimApp.kt` (launch); `P/domain/honor/WayJson.kt`; `app/src/main/res/xml/backup_rules.xml`, `data_extraction_rules.xml`, `T/data/honor/WaysBackupRulesTest.kt`; `P/data/honor/WayMediaDownloadWorker.kt` (`isDiskFull`); `P/audio/model/WhisperModelDownloadWorker.kt` (the `StatFs` precheck); `P/data/pilgrim/builder/` (the `.pilgrim` export and import). `P` = `app/src/main/java/org/walktalkmeditate/pilgrim`, `T` = `app/src/test/java/org/walktalkmeditate/pilgrim`.

**Where my cluster ends.** Reader P3 owns how `HonorStageOutcome` is computed (at teardown, and what the checkpoint carries; which Android live-row fields map to it). I own the struct's use from `record()` onward: the record call sites, the order of link and record, the ledger file, and everything a package operation does. Reader P4 owns the route page and catalog screens; I quote their package call sites and `isBusy` only to pin the state machine. Reader P1 owns the importer, the catalog, and the numeric caps; I pin where the caps are enforced in the download flow.

---

### 1. The Ways tree: the pilgrimage folder, stage ids, `list()`, and `retireMany`

**The ids and the folders.** A stage Way's id is `pilgrimage:<route>:<index>`, built only by `stageWayId`. A route id must match the dataset's slug rule before it becomes a path or a URL. The package folder sits beside the stage Ways, under `Ways/pilgrimage/<route>/`, and the swap marker sits in `Ways/pilgrimage/` itself.

```swift
    static func isValidId(_ id: String) -> Bool {
        id.range(of: "\\A(share:[A-Za-z0-9_-]{10}|walk:[0-9A-Fa-f-]{36}|pilgrimage:[a-z0-9-]{1,64}:[0-9]{1,3})\\z",
                 options: .regularExpression) != nil
    }

    /// The dataset's slug rule (spec 2.4). Checked before a route id is used
    /// in any path or URL, the way `WayImporter.isShareId` guards a share id.
    static func isValidRouteId(_ id: String) -> Bool {
        id.range(of: "\\A[a-z0-9-]{1,64}\\z", options: .regularExpression) != nil
    }

    static func stageWayId(routeId: String, stageIndex: Int) -> String {
        "pilgrimage:\(routeId):\(stageIndex)"
    }
```
> Pilgrim/Models/Honor/WayStore.swift:56-69@7c200bf

```swift
    var pilgrimageRoot: URL { base.appendingPathComponent("pilgrimage", isDirectory: true) }
// …
    func pilgrimageDirectory(for routeId: String) -> URL? {
        guard Self.isValidRouteId(routeId) else { return nil }
        return pilgrimageRoot.appendingPathComponent(routeId, isDirectory: true)
    }
// …
    func pilgrimageRouteIds() -> [String] {
        ((try? fileManager.contentsOfDirectory(atPath: pilgrimageRoot.path)) ?? []).filter(Self.isValidRouteId)
    }
```
> Pilgrim/Models/Honor/WayStore.swift:74-91@7c200bf

What this pins:
- `pilgrimageRouteIds()` lists every folder name under `pilgrimage/` that passes the slug rule. A folder left holding only `ledger.json` is still listed; `installed()` decides what counts as installed (by `route.json` and `release.txt`). `replacing.txt` has a dot, so it never passes the slug rule.
- **The order is the file system's.** `contentsOfDirectory` promises no order, and nothing sorts. With two valid packages and no marker, `installed()` returns whichever comes first (§4). Android's `File.list()` is also unordered; port it unsorted and never rely on the order in a test.
- The regex allows a stage index of 1 to 3 digits with leading zeros (`pilgrimage:x:007`), but `stageWayId` never writes one. The importer refuses an index of 200 or more (P1).
- "pilgrimage" alone is not a valid Way id, so `list()` and the expiry sweep step over the package folder.

**Save keeps the first acceptance.** A stage Way is saved through the same `save` as every Way: `way.json` is rewritten, `accepted.json` only when absent. So an Update that rewrites a stage keeps the stage's first download date, and a re-download of a walked stage whose `way.json` a Remove kept also keeps it.

```swift
    func save(_ way: Way) throws {
        guard Self.isValidId(way.id) else { throw CocoaError(.fileWriteInvalidFileName) }
        let dir = directory(for: way.id)
        try fileManager.createDirectory(at: dir, withIntermediateDirectories: true)
        try encoder.encode(way).write(to: dir.appendingPathComponent("way.json"), options: .atomic)
        let accepted = dir.appendingPathComponent("accepted.json")
        if !fileManager.fileExists(atPath: accepted.path) {
            try encoder.encode(Accepted(acceptedAt: now())).write(to: accepted, options: .atomic)
        }
    }
```
> Pilgrim/Models/Honor/WayStore.swift:93-102@7c200bf

Android's `save` already does the same (`WayStore.kt:118-127@0defff85`).

**`list()` decodes every Way, stages included.** iOS lists every valid id, decodes each `way.json`, and sorts by `accepted.json` (newest first). Stage Ways are in it. Every caller then filters: the Ways sheet keeps `.share` only, Settings → Ways keeps `!isPackageOwned`, the Data card counts the listable set, and the expiry sweep acts only on Ways with an `expires` (a stage has none: the importer builds it with `expires: nil`, `PilgrimageWayImporter.swift:194@7c200bf`).

```swift
    func list() -> [Way] {
        let ids = (try? fileManager.contentsOfDirectory(atPath: base.path)) ?? []
        return ids.filter(Self.isValidId).compactMap { load(id: $0) }
            .sorted { (acceptedAt(id: $0.id) ?? .distantPast) > (acceptedAt(id: $1.id) ?? .distantPast) }
    }
```
> Pilgrim/Models/Honor/WayStore.swift:117-121@7c200bf

The only reader that counts stage Ways is the Settings → Ways footer (§11), which counts `all.count - ways.count`: the package-owned Ways that **decode**. The plan's skip-before-decode is safe for every other caller (none can see a stage). For the footer, a new id listing that counts `pilgrimage:` folders holding a `way.json` differs from iOS in one corner: a stage folder whose `way.json` doesn't decode is counted on Android and not on iOS. That needs a corrupt file, so record it as a dated equivalent, or decode the stage files in the footer path only (it runs on screen open, not on every sweep).

**`retireMany` is the sweep's rule, batched.** A walked id loses only its media and keeps `way.json`, `accepted.json`, `replies.json`, and its link. An unwalked id goes whole. "Walked" means a link in `index.json` names the id. The index is read once and never written.

```swift
    func retireMany(ids: [String]) {
        let walked = walkedIds
        for id in ids { retire(id: id, walked: walked) }
    }

    // MARK: - Private

    private func retire(id: String, walked: Set<String>) {
        guard Self.isValidId(id) else { return }
        if walked.contains(id) {
            deleteMedia(id: id)
        } else {
            // An id no link names is absent from the index by definition, so
            // the folder is the whole of it.
            try? fileManager.removeItem(at: directory(for: id))
        }
    }
```
> Pilgrim/Models/Honor/WayStore.swift:219-235@7c200bf

```swift
    private var walkedIds: Set<String> { Set(loadIndex().values.map(\.wayId)) }
```
> Pilgrim/Models/Honor/WayStore.swift:194@7c200bf

What this pins:
- A stage Way has no `media/` folder, so for a walked stage `deleteMedia` removes nothing: the whole folder stays. Its `replies.json` holds the arrival reflection under key `"-1"` (`HonorPersistence.stageReflectionOrigin`, `HonorPersistence.swift:15@7c200bf`); Android's `replies()` parses that key with `toIntOrNull`, which accepts the minus sign.
- An invalid id is skipped silently.
- `retire` never touches the index, so no link is dropped, and a walked stage Way's link keeps pointing at a kept `way.json`.
- A walked stage keeps the `way.json` of whatever version was last written. An Update rewrites walked stages in place, so an older walk's summary later reads the redrawn stage (matched as shipped; §13 gap 8, defect D-6).
- **A failed `index.json` read makes every stage unwalked.** `loadIndex()` returns `[:]` on any read or decode failure (pilgrim-ios #107 item 4), so `retireMany` would then delete walked stages whole. Android's per-walk link files (`WayStore.kt:72-77@0defff85`) already avoid this; nothing to port.
- **Kept stages pile up.** Every Remove, Replace, rollback, and shrink keeps each walked stage it retires. Nothing ever deletes them: Settings → Ways never lists a package-owned Way, "Delete all Ways" walks only the listed array, and the route page reaches stages only through an installed `route.json`. A re-download of that route overwrites them in place (same ids). They count in Settings → Ways' footer (§11) but in no size total (the Data card sums the listable set only).

**Android today.** `WayStore.kt` has the id allow-list verbatim, `save`, `list()` (decodes every valid id, stages included), `sweepExpired(now, held)` with the same walked/unwalked rule inline, and per-walk link files. It has no `pilgrimageRoot`, `pilgrimageDirectory`, `pilgrimageRouteIds`, `isValidRouteId`, `stageWayId`, or `retireMany`.

```kotlin
    fun sweepExpired(now: Instant, held: Set<String>): List<String> {
        val walked = linkFiles().mapNotNullTo(HashSet()) { readLink(it)?.wayId }
        val touched = mutableListOf<String>()
        for (way in list()) {
            val expires = way.expires ?: continue
            if (expires.isAfter(now) || way.id in held) continue
            if (way.id in walked) {
                deleteMedia(way.id)
            } else {
                synchronized(mediaLock) { directory(way.id).deleteRecursively() }
                deletionCount.update { it + 1 }
            }
            touched += way.id
        }
        return touched
    }
```
> app/src/main/java/org/walktalkmeditate/pilgrim/data/honor/WayStore.kt:443-458@0defff85

```kotlin
    fun sweepTempFiles(olderThanMillis: Long): Int {
        val folders = listOf(linksDirectory) +
            baseDirectory.list().orEmpty().filter(::isValidId).map { File(baseDirectory, it) } +
            stagingRoot.list().orEmpty().filter(::isValidWalkUuid).map { File(stagingRoot, it) }
```
> app/src/main/java/org/walktalkmeditate/pilgrim/data/honor/WayStore.kt:416-419@0defff85

What U33 adds to `WayStore.kt`:
- `isValidRouteId` (`[a-z0-9-]{1,64}`, whole input), `stageWayId(routeId, index)` = `"pilgrimage:$routeId:$index"`, `pilgrimageRoot` = `<base>/pilgrimage`, `pilgrimageDirectory(routeId)` (null for an invalid id), and `pilgrimageRouteIds()` (unsorted, slug-filtered).
- `retireMany(ids)`: the walked set read once from the link files; walked → `deleteMedia`, else the folder deleted whole under `mediaLock` with `deletionCount` bumped (Android's own invalidation, as `sweepExpired` does: a surface holding a stage's line re-reads on it). The plan adds live-session Way ids to the walked set (gap 2): an Android addition, since iOS has no pending-finalize state (§10).
- `sweepTempFiles` reaching `pilgrimage/` (a killed `replacing.txt` write) and each `pilgrimage/<route>/` (killed `route.json`, `release.txt`, `ledger.json` writes). It must never delete the ledger's lock file (name it without the `.tmp` suffix).
- Every package file written through `writeAtomically`: `route.json`, `release.txt`, `ledger.json`, `replacing.txt`.

---

### 2. The package manager's state

```swift
@MainActor
final class PilgrimagePackageManager: ObservableObject {

    static let shared = PilgrimagePackageManager()
// …
    enum Phase: Equatable {
        case idle
        /// `total` counts `route.json` plus every stage file.
        case downloading(done: Int, total: Int)
        case failed(PilgrimageError)
    }

    @Published private(set) var phase: Phase = .idle

    /// Set by `MainCoordinatorView`. Downloading a second route, Replace,
    /// Update, and Remove are all refused while a walk is on.
    var isWalkActive: () -> Bool = { false }

    /// The commit's one write to the store, behind a seam so a spec can fail
    /// it mid-loop and prove the rollback below.
    var saveStage: (Way) throws -> Void

    /// The whole package's ceiling, counted on the bytes that actually land.
    /// Injectable so a spec need not serve 50 MB to prove it holds.
    var maxPackageBytes = PilgrimageCatalogService.maxPackageBytes
```
> Pilgrim/Models/Honor/PilgrimagePackageManager.swift:7-37@7c200bf

```swift
    private var isDownloading = false

    /// `nonisolated`: an init's default-argument expressions run in a
    /// generator function outside the type's actor, the same reasoning as
    /// `PilgrimageCatalogService.defaultSession`.
    nonisolated private static let defaultSession: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 300
        return URLSession(configuration: config)
    }()
```
> Pilgrim/Models/Honor/PilgrimagePackageManager.swift:53-63@7c200bf

The state, plainly:
- **One instance, main-actor.** Every operation's own steps run on the main actor; the fetches, the temp writes, and the commit are `nonisolated static` and run off it. All state (`phase`, `isDownloading`) is touched only on main, so no two operations interleave except at `await` points.
- **`phase`** is the only published state. `idle` at rest; `downloading(done, total)` from the first statement of a download through the end of its commit, with `total = stageCount + 1`; `failed(error)` after a failed download, kept until the next download sets it again. Replace's old-route removal and Update's tail sweep and reconcile run after the download has set `idle` again.
- **`isDownloading`** is the reentrancy flag. It is true from a download's entry guards to its end (a `defer`). It covers only `download`; `replace` and `update` reach it through their own `download` call.
- **`isBusy` is the route page's, not the manager's.** The page disables its button and its Remove menu while the phase is `downloading` or the tiles manager is saving (slice three). Stage rows and the "Download this route first?" alert's button are never disabled (§4, defect D-3).

```swift
    private var isBusy: Bool {
        if case .downloading = packages.phase { return true }
        if case .saving = tiles.phase { return true }
        return false
    }
```
> Pilgrim/Scenes/Honor/PilgrimageRouteView.swift:308-312@7c200bf

- **The session** is ephemeral (no URL cache, no cookies), with a 30 s request timeout (idle time between bytes) and a 300 s resource timeout (each file's whole transfer). Android's OkHttp equivalents: `readTimeout(30 s)` and `callTimeout(300 s)` per file, with no `Cache`. P1 owns the catalog's own client (15 s / 30 s); the package client is a separate one.

**The walk guard: what it checks, and when.** `isWalkActive` defaults to `{ false }`. `MainCoordinator.chooseWay()` installs it, which runs every time the Ways sheet opens (the only door to the catalog), so it is in place before any package operation can be asked for:

```swift
    func chooseWay() {
        // Downloading a second route, Replace, Update, and Remove are all
        // refused while a walk is on; this is where the manager learns what
        // "on" means.
        Task { @MainActor in
            PilgrimagePackageManager.shared.isWalkActive = { [weak self] in self?.activeWalkViewModel != nil }
            PilgrimageTilesManager.shared.isWalkActive = { [weak self] in self?.activeWalkViewModel != nil }
            PilgrimagePackageManager.shared.tiles = PilgrimageTilesManager.shared
        }
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:195-203@7c200bf

`activeWalkViewModel` is set in `startWalk` (`MainCoordinatorView.swift:137@7c200bf`): the overview's Begin parks the Way, the overview closes, and `handleOverviewDismiss` calls `startWalk(mode: .honor, way:)` (`:334-337`), which presents the walk screen in its pre-Start state. It is cleared after a successful save's link and record (`:123`), on cancel (`:142`), and when the walker dismisses "Save Failed" (`MainTabView.swift:107-110@7c200bf`). So "a walk is on" means: the walk screen is up, for any walk mode (a wander too), from Begin to the end of the save callback, and through a failed save until its alert is dismissed.

Where it is checked:

| Operation | On entry | Before the commit | After the commit |
|---|---|---|---|
| `download` | yes (`:150`), before `isDownloading` | yes (`:195`), after the last stage lands | never |
| `replace` | yes (`:227`), then again inside its `download` | inside `download` | never: the old route's removal runs unguarded |
| `update` | yes (`:256`), then again inside its `download` | inside `download` | never: the tail sweep and the ledger reconcile run unguarded |
| `remove` | yes (`:275`) | (no commit) | — |

The commit itself (`Self.commit`, nonisolated) runs off the main actor after the last check, so a walk that starts while the commit's saves run is not refused, and neither are the post-commit steps. iOS walks the Way it captured at the door in memory, so on iOS a package change under a live walk changes only what the summary and the ledger read later; on Android `:tracker` re-reads `way.json` (§10).

Refusals and the phase:
- The entry guards (`walkInProgress`, and `download`'s busy refusal `incomplete`) throw **before** `phase` changes, so a refused second download leaves the first download's progress on screen.
- The before-commit guard is inside the `do`, so it sets `phase = .failed(.walkInProgress)` and the temp set is swept by the `defer` (pinned by `testAWalkBegunWhileTheStagesStreamAbortsTheCommit`).
- `remove` refuses a download in flight with `incomplete`, "the download didn't finish", the same as a second download (pinned by `testRemoveIsRefusedWhileADownloadIsInFlight`).

**Android today.** No package manager. The nearest guard is `WaysAvailability` (flag on, no active walk row, no live session rows), and the Begin hold is `HonorBeginsInFlight`:

```kotlin
            combine(walkRepository.observeActiveWalk(), honorDao.observeLiveSessionCount()) { active, sessions ->
                active == null && sessions == 0
            }
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/WaysListViewModel.kt:130-132@0defff85

```kotlin
    /** The walk screen from the moment it opens, before Start included, with anything over it. */
    val walkScreenUp: Boolean,
```
> app/src/main/java/org/walktalkmeditate/pilgrim/honor/HonorLinkRouter.kt:32-33@0defff85

iOS's guard maps to `walkScreenUp` alone (the walk screen from Begin, pre-Start included, until the walk leaves). The other three clauses of the plan's wide guard (an active walk row, live session rows, a Begin in flight) are Android additions for the process split (§10, A-1).

---

### 3. `download`: temp set, validate, commit, roll back

```swift
    func download(entry: PilgrimageCatalogEntry, release: String) async throws {
        guard !isWalkActive() else { throw PilgrimageError.walkInProgress }
        guard !isDownloading else { throw PilgrimageError.incomplete }
        isDownloading = true
        defer { isDownloading = false }
        // The route.json fetch below is a full network round trip before
        // the first stage lands — without an early phase, isBusy stays
        // false and a second tap re-enters here and throws a false failure.
        phase = .downloading(done: 0, total: entry.stageCount + 1)

        let temp = FileManager.default.temporaryDirectory
            .appendingPathComponent("pilgrimage-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: temp) }
        let cap = maxPackageBytes
        let session = self.session
        let store = self.store
        let saveStage = self.saveStage
        let previousStageCount = installedStageCount(for: entry.id)
        do {
            try FileManager.default.createDirectory(at: temp, withIntermediateDirectories: true)
            guard let routeURL = PilgrimageCatalogService.packageURL(release: release, routeId: entry.id, file: "route.json") else {
                throw PilgrimageError.notWalkable
            }
            let total = entry.stageCount + 1
            // Counted across every file, not per file: 200 stages each just
            // under the 2 MB per-file cap would otherwise be 400 MB.
            var packageBytes = 0
            let fetched = try await Self.stageRouteFile(entry: entry, routeURL: routeURL, into: temp, session: session)
            packageBytes += fetched.bytes
            try Self.checkBudget(packageBytes, cap: cap)
            phase = .downloading(done: 1, total: total)
            for index in 0..<fetched.route.stageCount {
                guard let stageURL = PilgrimageCatalogService.packageURL(
                    release: release, routeId: entry.id, file: Self.stageFileName(index)) else {
                    throw PilgrimageError.notWalkable
                }
                let stagePlan = StagePlan(routeId: entry.id, url: stageURL, stageCount: fetched.route.stageCount,
                                          expected: fetched.route.stages[index])
                packageBytes += try await Self.stageOneStage(stagePlan, into: temp, session: session)
                try Self.checkBudget(packageBytes, cap: cap)
                phase = .downloading(done: index + 2, total: total)
            }
// …
            guard !isWalkActive() else { throw PilgrimageError.walkInProgress }
            let plan = CommitPlan(routeId: entry.id, release: release, stageCount: fetched.route.stageCount,
                                   previousStageCount: previousStageCount)
            try await Self.commit(plan, from: temp, store: store, saveStage: saveStage)
            phase = .idle
        } catch is CancellationError {
            // A cancelled task is not a failure the pilgrim needs to see:
            // the temp dir is still swept by the `defer` above.
            phase = .idle
            throw CancellationError()
        } catch {
            let failure = (error as? PilgrimageError) ?? .incomplete
            phase = .failed(failure)
            throw failure
        }
    }
```
> Pilgrim/Models/Honor/PilgrimagePackageManager.swift:149-210@7c200bf

**The order, step by step:**
1. Walk guard (`walkInProgress`), then the busy guard (`incomplete`). Neither touches `phase`.
2. `isDownloading = true`; `phase = downloading(0, stageCount + 1)` at once, before any network (a second tap during `route.json`'s round trip then sees `isBusy`).
3. The temp set: `FileManager.default.temporaryDirectory/pilgrimage-<UUID>/`, removed by a `defer` on every exit, success included.
4. `previousStageCount`: the stage count of this same route's current install (0 if another route or nothing is installed). It is read through `installed()` while `isDownloading` is already true, so the marker branch (§4) never runs here.
5. `route.json`: URL built (P1's validated builder; nil → `notWalkable`), fetched under the 512 KB cap, parsed by the importer, cross-checked against the catalog entry (`id`, `stageCount`, `stages.count`; any mismatch → `notWalkable`), and written raw to `temp/route.json`.
6. The package budget is checked after `route.json`, then `phase = downloading(1, total)`.
7. For each index `0..<stageCount`, in order, one at a time: the URL (`stage-NN.json`, two digits below 100, three from 100), the fetch under the 2 MB cap, the importer's full validation, the cross-check against `route.json`'s row (`stage.count == stageCount`, `stage.name == row.name`; else `notWalkable`), the Way re-encoded in the store's own encoding and written to `temp/<index>.way.json`, the budget, then `phase = downloading(index + 2, total)`.
8. The walk guard again, after the last stage lands. A walk begun meanwhile throws `walkInProgress` inside the `do`, so `phase = failed(walkInProgress)` and nothing reaches the store.
9. The commit (below). On success `phase = idle`.
10. Errors: a `CancellationError` sets `idle` and rethrows (nothing in production cancels: the route page starts every install in a bare `Task {}` that outlives the screen); any other error sets `failed(e)`, where an error that isn't a `PilgrimageError` becomes `incomplete`.

**Where the caps bite** (P1 owns the numbers):

```swift
    nonisolated private static func checkBudget(_ bytes: Int, cap: Int) throws {
        guard bytes <= cap else { throw PilgrimageError.incomplete }
    }
```
> Pilgrim/Models/Honor/PilgrimagePackageManager.swift:306-308@7c200bf

```swift
    nonisolated private static func fetch(url: URL, cap: Int, session: URLSession) async throws -> Data {
        do {
            let (bytes, response) = try await session.bytes(from: url)
            // Checked before draining: an oversized declared length must not
            // cost a full download first.
            guard let http = response as? HTTPURLResponse, http.statusCode == 200,
                  http.expectedContentLength <= Int64(cap) else { throw PilgrimageError.incomplete }
            var buffer = Data()
            buffer.reserveCapacity(min(cap, 256 * 1024))
            for try await byte in bytes {
                buffer.append(byte)
                if buffer.count > cap { throw PilgrimageError.incomplete }
            }
            return buffer
        } catch let error as PilgrimageError {
            throw error
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw WayMediaDownloader.isDiskFull(error) ? PilgrimageError.diskFull : PilgrimageError.incomplete
        }
    }

    nonisolated private static func write(_ data: Data, to url: URL) throws {
        do {
            try data.write(to: url, options: .atomic)
        } catch {
            throw WayMediaDownloader.isDiskFull(error) ? PilgrimageError.diskFull : PilgrimageError.incomplete
        }
    }
```
> Pilgrim/Models/Honor/PilgrimagePackageManager.swift:355-384@7c200bf

- **Per file:** HTTP status exactly 200 (a 404 with a valid body is `incomplete`); the declared `Content-Length` checked before any byte is read, `<= cap` passing (an absent length is −1 and passes); then a running count while streaming, refusing at `count > cap`. So a file of exactly the cap passes. The redirect policy is `URLSession`'s default (follows). Android: check `body.contentLength()` (−1 when absent) before reading, then count bytes as they stream.
- **Per package:** `packageBytes` sums the **received** bytes of every file and is checked **after** each whole file lands, `<= cap` passing. The cap is `50 * 1024 * 1024` = 52,428,800 bytes (MiB, not MB; `PilgrimageCatalogService.swift:93@7c200bf`). A package can overshoot by up to one file before it is refused. Pinned with an injected cap of 1,000 bytes (`testTheWholePackageIsBoundedByRealBytesNotTheIndexsClaim`).
- **Error mapping:** a cap, status, or length failure is `incomplete`; a network error is `incomplete` unless `isDiskFull`; a temp write failure is `diskFull` or `incomplete`; validation and cross-check failures are `notWalkable` (P1's importer).

**Disk full and free space.** iOS has no free-space precheck. A full disk is detected only from an error: a `URLError.cannotWriteToFile`, an `NSFileWriteOutOfSpaceError`, or either one wrapped as an underlying error:

```swift
    nonisolated static func isDiskFull(_ error: Error) -> Bool {
        if (error as? URLError)?.code == .cannotWriteToFile { return true }
        let nsError = error as NSError
        if nsError.domain == NSCocoaErrorDomain && nsError.code == NSFileWriteOutOfSpaceError { return true }
        if let underlying = nsError.userInfo[NSUnderlyingErrorKey] as? NSError { return isDiskFull(underlying) }
        return false
    }
```
> Pilgrim/Models/Honor/WayMediaDownloader.swift:242-248@7c200bf

This is the only slice-two change to `WayMediaDownloader.swift` (`private` dropped, `a048460`). Android's equivalent already exists and fits: `WayMediaDownloadWorker.isDiskFull` (ENOSPC by errno or message, through up to 8 causes, plus `SQLiteFullException`, `WayMediaDownloadWorker.kt:332-337@0defff85`). U34 reuses it. A `StatFs` precheck like `WhisperModelDownloadWorker`'s (`WhisperModelDownloadWorker.kt:174-177@0defff85`) would be an Android addition with its own refusal timing; don't add one.

**The commit and the rollback.**

```swift
    nonisolated private static func commit(_ plan: CommitPlan, from temp: URL, store: WayStore, saveStage: (Way) throws -> Void) async throws {
        guard let dir = store.pilgrimageDirectory(for: plan.routeId) else { throw PilgrimageError.notWalkable }
        do {
            for index in 0..<plan.stageCount {
                let data = try Data(contentsOf: temp.appendingPathComponent("\(index).way.json"))
                let way = try Self.decoder.decode(Way.self, from: data)
                try saveStage(way)
            }
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            try write(try Data(contentsOf: temp.appendingPathComponent("route.json")),
                      to: dir.appendingPathComponent("route.json"))
            try write(Data(plan.release.utf8), to: dir.appendingPathComponent("release.txt"))
        } catch {
            rollBack(routeId: plan.routeId, stageCount: max(plan.previousStageCount, plan.stageCount), store: store)
            if let failure = error as? PilgrimageError { throw failure }
            throw WayMediaDownloader.isDiskFull(error) ? PilgrimageError.diskFull : PilgrimageError.incomplete
        }
    }

    /// Undoes everything a commit could have put down for this route, up
    /// through `stageCount` — not only the indices this attempt itself
    /// wrote, so a shrinking or same-size Update leaves nothing behind
    /// either. The ledger is untouched, so re-downloading restores what was
    /// walked, and so is any stage a walk in the journal still names.
    nonisolated private static func rollBack(routeId: String, stageCount: Int, store: WayStore) {
        store.retireMany(ids: stageIds(routeId: routeId, range: 0..<stageCount))
        guard let dir = store.pilgrimageDirectory(for: routeId) else { return }
        try? FileManager.default.removeItem(at: dir.appendingPathComponent("route.json"))
        try? FileManager.default.removeItem(at: dir.appendingPathComponent("release.txt"))
    }
```
> Pilgrim/Models/Honor/PilgrimagePackageManager.swift:408-437@7c200bf

The commit's order: every stage Way saved in index order (`store.save`: `way.json`, then `accepted.json` if absent), then the package folder made, then `route.json` (the **raw downloaded bytes**, not a re-encode), then `release.txt` (the release string's UTF-8 bytes, no newline). `installed()` keys on both files, so a route counts as installed only once `release.txt` lands.

**The plan's belief about the rollback is correct, with one refinement.** A failed commit does not restore the prior install. It retires stages `0..<max(previous, new)` and deletes `route.json` and `release.txt`, so nothing of this route is installed afterwards, prior install included. What survives: `ledger.json`, and the `way.json` (with replies and link) of every walked stage in that range, in whichever version the loop had reached. The refinement: only a **commit** failure rolls back. A failure while streaming or validating (network, cap, status, `notWalkable`, the before-commit walk guard) never touches the store, so an Update that fails there leaves the old install exactly as it was (`testAFailedReplaceLeavesTheFirstRouteUntouched` covers the Replace case). The commit's realistic failure is a full disk, which then removes the walker's installed route with "not enough space on this phone to save these voices" (defects D-2 and D-4). The route page reloads after any install so it shows the rollback's result (`PilgrimageRouteView.swift:350-353@7c200bf`).

**The temp set: path and sweep.** iOS's temp set lives in the app's `tmp/`, removed by the `defer` on every in-process exit. A kill leaves it, and iOS has no sweep of its own: the OS purges `tmp/` when the app isn't running. Android's `noBackupFilesDir` is never purged, so the plan's launch sweep is an Android equivalent of the OS purge (A-6), not a port of iOS code. Pin it:
- Path: outside the Ways tree, so no Ways reader or sweep sees it, for example `noBackupFilesDir/pilgrimage-tmp/pilgrimage-<uuid>/`. Not `cacheDir` (the system may clear it while the app runs; iOS never purges a running app's `tmp/`).
- Swept at UI-process launch, only with the release flag on, and never the folder of a download in flight in this process. The launch maintenance runs asynchronously on the finalization scope (`PilgrimApp.kt:437-440@0defff85`), so a download the walker starts in the first seconds can exist when the sweep runs; the manager publishes its own temp folder and the sweep skips it.
- Never in `:tracker` (its `onCreate` returns early before any UI init, `PilgrimApp.kt:246-268@0defff85`).

---

### 4. Replace, Replace-with-self, `replacing.txt`, and `installed()`

```swift
    func replace(with entry: PilgrimageCatalogEntry, release: String) async throws {
        guard !isWalkActive() else { throw PilgrimageError.walkInProgress }
        // A replace of the route you already hold is an update by another
        // name: it needs the same shrink tail-sweep and ledger reconciliation,
        // not a bare download that leaves both behind.
        if installed()?.routeId == entry.id {
            try await update(entry: entry, release: release)
            return
        }
        let previous = installed()
        // Written before a byte lands, so a kill anywhere in the swap leaves
        // behind the name of the route being let go.
        if let previous { markReplacing(previous.routeId) }
        do {
            try await download(entry: entry, release: release)
        } catch {
            clearReplacingMarker()
            throw error
        }
        if let previous, previous.routeId != entry.id {
            // The ledger stays: a route that comes back finds its record.
            removeStagesAndPackage(routeId: previous.routeId, stageCount: previous.route.stageCount)
            tiles?.remove(routeId: previous.routeId)
        }
        clearReplacingMarker()
    }
```
> Pilgrim/Models/Honor/PilgrimagePackageManager.swift:226-251@7c200bf

Replace, in order:
1. Walk guard (`walkInProgress`).
2. `installed()` (with no download in flight this runs the marker branch, finishing any earlier interrupted swap). If the installed route **is** this entry, run `update(entry:release:)` and return: a Replace with self is an Update, with its shrink sweep and reconcile (`testReplaceWithTheRouteAlreadyInstalledBehavesLikeAnUpdate`). The route page never asks for one (its Replace path needs another route installed); the manager guards it anyway.
3. `previous = installed()` again.
4. If something is installed, write `replacing.txt` holding `previous.routeId` (before any byte lands). With nothing installed, no marker.
5. `download(entry:)`. If it throws, for any reason, delete the marker and rethrow. The old route is untouched.
6. On success: the old route's stages retired (`0..<previous.route.stageCount`; walked ones keep `way.json`), its `route.json` and `release.txt` deleted, `ledger.json` kept; then the tiles seam (slice three); then the marker deleted.

The marker file:

```swift
    func installed() -> Installed? {
        var found: [Installed] = []
        for routeId in store.pilgrimageRouteIds() {
            guard let dir = store.pilgrimageDirectory(for: routeId),
                  let routeData = try? Data(contentsOf: dir.appendingPathComponent("route.json")),
                  let route = try? PilgrimageWayImporter.route(from: routeData),
                  let release = try? String(contentsOf: dir.appendingPathComponent("release.txt"), encoding: .utf8),
                  PilgrimageCatalogService.isValidRelease(release) else { continue }
            found.append(Installed(routeId: routeId, release: release, route: route))
        }
        // A download in flight is the one time both packages are meant to be
        // there; the swap that wrote the marker is still the one to clear it.
        guard !isDownloading, let abandonedId = replacingMarker else { return found.first }
        if found.count > 1, let abandoned = found.first(where: { $0.routeId == abandonedId }) {
            removeStagesAndPackage(routeId: abandoned.routeId, stageCount: abandoned.route.stageCount)
            found.removeAll { $0.routeId == abandonedId }
        }
        clearReplacingMarker()
        return found.first
    }

    /// Names the route a cross-route Replace is letting go. Its own file
    /// rather than a field in `route.json`, so it survives the removal of
    /// either package.
    static let replacingMarkerName = "replacing.txt"

    private var replacingMarkerURL: URL {
        store.pilgrimageRoot.appendingPathComponent(Self.replacingMarkerName)
    }

    private var replacingMarker: String? {
        guard let routeId = try? String(contentsOf: replacingMarkerURL, encoding: .utf8),
              WayStore.isValidRouteId(routeId) else { return nil }
        return routeId
    }

    private func markReplacing(_ routeId: String) {
        try? FileManager.default.createDirectory(at: store.pilgrimageRoot, withIntermediateDirectories: true)
        try? Data(routeId.utf8).write(to: replacingMarkerURL, options: .atomic)
    }

    private func clearReplacingMarker() {
        try? FileManager.default.removeItem(at: replacingMarkerURL)
    }
```
> Pilgrim/Models/Honor/PilgrimagePackageManager.swift:89-132@7c200bf

**`installed()`: what it returns and from where.**
- For each slug folder under `pilgrimage/`: `route.json` must read and pass the importer's full `route(from:)` validation (every call re-parses it), and `release.txt` must read as UTF-8 and match P1's release rule **without trimming** (a trailing newline makes the route not installed). Either missing or invalid → that folder isn't installed. `Installed` = `{routeId (the folder name), release, route}`.
- Returns `found.first`, in the file system's order (§1).
- **The marker branch** runs when no download is in flight and the marker names a valid route id. With more than one package found and the marker's route among them, it removes that route's stages and package (the same `removeStagesAndPackage` as Replace, ledger kept). Then it deletes the marker **whatever it found**: one package, none, or a marker naming a route not installed.
- `installed()` therefore **writes files**. Its callers at the pin: the catalog's load (`PilgrimageCatalogView.swift:223`), the route page's `reload()`, the prompt's route name (`PromptListView.swift:243`), Settings → Ways' footer (`WaysListView.swift:100`), the offline-maps model (slice three), and launch (§12). Inside the manager: `replace` (twice), `update` (twice, through `installedStageCount` and `fresh`), `remove`, and `download`'s `installedStageCount` (marker branch skipped there).
- Write failures of the marker are swallowed (`try?`), as is the folder creation. A marker that can't be written means a kill mid-swap leaves both packages with nothing to say which won.

**The marker, written and removed:**

| Event | Marker |
|---|---|
| `replace` with another route installed | written before the download, holding the outgoing route's id |
| `replace` with nothing installed | never written |
| `replace`'s download throws (any error, including the busy refusal) | deleted |
| `replace` finishes | deleted after the old route is removed |
| any `installed()` with no download in flight | deleted, after finishing the swap if two packages remain |
| `update`, `download`, `remove` | never written |

**The busy race the flow analysis found (gap 10) is real, and is iOS's as shipped.** While route A is downloading as a Replace (marker = the outgoing route O), a stage tap on another route's page opens "Download this route first?", whose "Download" goes through `beginInstall` → "Replace?" → `replace(with: B)`. That call passes the walk guard, sees `isDownloading` (so `installed()` skips the marker branch and returns O), writes the marker as O again, calls `download`, is refused with `incomplete`, and **deletes the marker** in its `catch`. Route A's Replace continues with no marker. A kill after A's commit and before O's removal then leaves two valid packages and nothing to say which won; the next `installed()` returns `found.first`. The refused call also shows "the download didn't finish" on that page while A is still downloading. The page's `isBusy` disables only the button, not the stage rows or the alert's "Download".

```swift
    private func beginInstall() {
        if installed != nil && !isInstalled {
            confirmReplace = true
        } else {
            Task { await install(replacing: false) }
        }
    }

    private func install(replacing: Bool) async {
        failure = nil
        do {
            if hasUpdate {
                try await packages.update(entry: entry, release: release)
            } else if replacing {
                try await packages.replace(with: entry, release: release)
            } else {
                try await packages.download(entry: entry, release: release)
            }
            failure = nil
        } catch {
            failure = (error as? PilgrimageError) ?? .incomplete
        }
        // Reload on both branches: a failed update's rollback removed the
        // package, and only reload() picks that state back up so the screen
        // never keeps showing the pre-rollback "on your phone" state.
        reload()
    }
```
> Pilgrim/Scenes/Honor/PilgrimageRouteView.swift:328-354@7c200bf

The plan's U34 says Replace writes `replacing.txt` "checking for busy first, the flow-analysis gap". That is an Android-only fix of an iOS defect, which the house rule forbids (match as shipped, file upstream, fold in the iOS fix). Correction C-6, defect D-3, owner decision O-3.

---

### 5. Update: tail sweep, tiles seam, ledger reconcile

```swift
    func update(entry: PilgrimageCatalogEntry, release: String) async throws {
        guard !isWalkActive() else { throw PilgrimageError.walkInProgress }
        let previousStageCount = installedStageCount(for: entry.id)
        try await download(entry: entry, release: release)
        guard let fresh = installed(), fresh.routeId == entry.id else { throw PilgrimageError.incomplete }
        // A route that shrank leaves stage Ways above the new count behind;
        // nothing lists them and no next row reaches them, so they go.
        store.retireMany(ids: Self.stageIds(routeId: entry.id,
                                            range: fresh.route.stageCount..<max(previousStageCount, fresh.route.stageCount)))
        // The maps that were saved stay saved; only the regions at indices the
        // route no longer has go. A redrawn stage's region is reported as
        // stale by the tiles manager's own hash check and re-saved by the
        // walker's next tap — never downloaded here on their behalf.
        tiles?.removeRegions(routeId: entry.id, atOrAbove: fresh.route.stageCount)
        if let ledger = ledgers.load(routeId: entry.id) {
            ledgers.save(ledger.reconciled(against: fresh.route.stages))
        }
    }
```
> Pilgrim/Models/Honor/PilgrimagePackageManager.swift:255-272@7c200bf

Update, in order:
1. Walk guard.
2. `previousStageCount` from `installed()` (no download in flight, so this call can run the marker branch).
3. `download`: the whole of §3. The commit overwrites stages `0..<new` in place; a stage keeps its first `accepted.json`.
4. `fresh = installed()`; if it isn't this route, throw `incomplete`. (The download just installed it, so this throws only if something removed it between, or if two packages exist and the other comes first in the listing.)
5. The tail: stages `new..<max(previous, new)` retired (walked ones kept). An empty range for a route that grew or held its size.
6. The tiles seam (slice three): `removeRegions(routeId:atOrAbove: new)`.
7. The ledger: loaded; if it decodes, replaced by `reconciled(against: fresh.route.stages)` (§8). A missing or undecodable ledger is left alone. `save` swallows a failed write.

Steps 4 to 7 run after `phase` went `idle` and with no walk guard (§2). Update has no confirmation dialog (P4). After a successful Update the route page's `reload()` reads the reconciled ledger, shows the redraw notice if one is pending, and clears it at once (§8).

---

### 6. Remove

```swift
    func remove(routeId: String) throws {
        guard !isWalkActive() else { throw PilgrimageError.walkInProgress }
        // The same refusal a second download gets: a Remove taken between two
        // stages would be undone by the commit that lands after it, and the
        // route the pilgrim let go would be back on the phone.
        guard !isDownloading else { throw PilgrimageError.incomplete }
        let stageCount = installed().flatMap { $0.routeId == routeId ? $0.route.stageCount : nil }
            ?? PilgrimageWayImporter.maxStageCount
        removeStagesAndPackage(routeId: routeId, stageCount: stageCount)
        tiles?.remove(routeId: routeId)
    }

    /// Takes the stages, `route.json`, and `release.txt`. Never `ledger.json`
    /// — the record of having walked a route outlives the route — and never
    /// the whole of a stage a walk in the journal still names: `retireMany`
    /// keeps a walked stage's `way.json`, its reply, and its index link, so
    /// the summary and the prompt can still say which stage that walk was.
    /// `installed()` keys on `route.json`, so what is kept never reads as
    /// installed, and a re-download overwrites it in place.
    private func removeStagesAndPackage(routeId: String, stageCount: Int) {
        store.retireMany(ids: Self.stageIds(routeId: routeId, range: 0..<stageCount))
        guard let dir = store.pilgrimageDirectory(for: routeId) else { return }
        try? FileManager.default.removeItem(at: dir.appendingPathComponent("route.json"))
        try? FileManager.default.removeItem(at: dir.appendingPathComponent("release.txt"))
    }
```
> Pilgrim/Models/Honor/PilgrimagePackageManager.swift:274-298@7c200bf

- Synchronous (no `await`), on main. Walk guard, then the busy guard (`incomplete`).
- The range is the installed route's own stage count when `routeId` is the installed route; otherwise 200 (`maxStageCount`), so a Remove of a route that isn't installed still reaches any stage left on disk (for example a half-committed install). The route page offers Remove only for the installed route.
- Order: stages retired first, then `route.json`, then `release.txt`. `ledger.json` and the package folder stay. A Remove's kept stages and its ledger are what "what you've walked of it is remembered if it comes back" means.
- The Remove alert names the route with `entry.name` (the catalog's name), the Replace alert with `installed?.route.name ?? "route"` (the installed `route.json`'s name) (`PilgrimageRouteView.swift:155-166@7c200bf`, P4).
- A Remove of the installed route leaves a stage tail from an interrupted shrink (§7) behind, because its range is the installed count.

---

### 7. What a kill leaves at each step

iOS's writes are `.atomic` (temp then rename), so a kill never leaves a torn file under a final name; it leaves the state between whole files. Android's `writeAtomically` gives the same, plus a `.<name>.<uuid>.tmp` the temp sweep removes. "Next launch shows" assumes iOS's launch call to `installed()` (§12) and the route page's `reload()`. "Footer" is Settings → Ways' line (§11).

| Operation | Killed during or after | On disk | What the next launch shows |
|---|---|---|---|
| Download (first install) | streaming or validating | the temp set (iOS: purged by the OS later; Android: the launch sweep) | nothing installed; nothing in the store |
| Download (first install) | the commit's stage loop, or before `route.json`/`release.txt` land | stage Ways `0..k`, no `route.json` (or `route.json` without `release.txt`) | not installed. The stages are orphans: never listed, never reachable, invisible to the footer (no installed route to name). A later download of that route overwrites them; a Remove of it (200-wide) would retire them. iOS's device pass ("nothing half-installed") misses this window |
| Download | after `release.txt`, before `phase = idle` | a complete install | installed |
| Update | streaming or validating | old install intact, temp set | old install; "Update" still offered |
| Update | the stage loop | stages `0..k` new, the rest old, old `route.json` and `release.txt`; for a growing route, new indices above the old count as orphans | the old release reads as installed, over a mix of old and new stage geometry. "Update" is still offered (release mismatch); a second Update repairs it. The route page lists the old `route.json`'s stage names and distances over stage Ways that are partly new, so a stage tapped there can open a Way whose own stage block disagrees with its row. No reconcile, no notice |
| Update | after `route.json`, before `release.txt` | new `route.json`, old `release.txt` | the new route with the old release: "Update" offered again; a second Update is idempotent and then runs the tail and reconcile |
| Update | after the commit, before the tail sweep | new install complete; tail stages above the new count remain | installed. The tail is never swept again: a later Update's `previousStageCount` is the new count, and Remove's range is the installed count. The footer counts the tail as "its N stages" |
| Update | after the tail, before the reconcile | new install; ledger unreconciled | installed; the ledger keeps entries under old names and distances; nothing re-runs the reconcile, so no notice and no carried kilometres. A later Update reconciles against its own new stages only |
| Replace | after the marker, before or during the new route's download | marker = old route; old install intact; temp set | `installed()` finds one package, deletes the marker; the old route |
| Replace | the new route's commit loop | marker; old install; new route's stage orphans | as above: the old route; the new route's orphans counted in the footer under the old route's name |
| Replace | after the new commit, before the old route's removal ends | marker; two valid packages (the old one possibly with some stages already retired) | `installed()` finishes the removal (stages, `route.json`, `release.txt`) and deletes the marker: the new route. A stage of the old route already retired stays retired |
| Replace | after the old `route.json` is deleted, before its `release.txt` | marker; one valid package | marker deleted; the new route; the old `release.txt` left (harmless) |
| Replace | after the removal, before the marker delete | marker; the new route | marker deleted; the new route |
| Remove | during the stage retire | some stages retired, `route.json` and `release.txt` still there | still installed, with stages missing: the route page lists every stage from `route.json`, and a tap on a missing one opens "Download this route first?" (its `WayStore.load` fails, `PilgrimageRouteView.swift:316-323`), whose "Download" re-downloads the same release and repairs it. A second Remove completes the removal instead |
| Remove | after `route.json`, before `release.txt` | `release.txt` left | not installed |
| Ledger `save` (record, reconcile, notice clear) | any point | old or new `ledger.json`, whole | either the old or the new ledger |

The owner question (gap 5, O-1) is whether Android adds a launch reconciliation for the rows the marker can't repair: the first-install orphans, the Update mixes, the untouched tail, and the missed reconcile. iOS has the same windows and no repair. The plan's default is parity plus an upstream issue (D-1). If the owner wants a repair, the smallest one that stays close to iOS: at launch, after `installed()`, retire stage Ways of a route with no `route.json` and stage Ways at or above the installed route's count (walked ones kept, as `retireMany` does), as a dated addition. The Update mix can't be repaired without the old package; only the "Update" button can.

---

### 8. The ledger: model, file, record, next, progress line, reconcile, store

**The model.**

```swift
struct HonorStageOutcome: Equatable {
    let progressFrac: Double
    let arrived: Bool
}

/// The per-route record of stages walked. It outlives the package: Replace
/// and Remove take the stages, never this file, so a route that comes back
/// finds its record.
struct PilgrimageLedger: Codable, Equatable {

    struct Entry: Codable, Equatable {
        /// The stage's identity across an update, with `distanceKm`.
        let name: String
        let distanceKm: Double
        var walkedAt: Date
        var kmWalked: Double
        var completed: Bool
        var stoppedAtFrac: Double?
    }
// …
    let routeId: String
    /// Keyed by stage index as a string, the shape the file on disk carries.
    var stages: [String: Entry]
    /// Kilometres from entries a redraw dropped, so the total never shrinks
    /// under the walker.
    var carriedKm: Double?
    /// Set by `reconciled(against:)`; the route view says so once.
    var redrawNoticePending: Bool?
```
> Pilgrim/Models/Honor/PilgrimageLedger.swift:5-39@7c200bf

```swift
    var totalKmWalked: Double {
        let walked = stages.values.map(\.kmWalked).filter(\.isFinite).reduce(0, +)
        return walked + ((carriedKm?.isFinite ?? false) ? carriedKm! : 0)
    }

    var completedCount: Int { stages.values.filter(\.completed).count }
```
> Pilgrim/Models/Honor/PilgrimageLedger.swift:50-55@7c200bf

**The file.** `Ways/pilgrimage/<route-id>/ledger.json` (Android: `noBackupFilesDir/Ways/pilgrimage/<route>/ledger.json`). One per route id ever recorded, kept through Remove, Replace, and rollback; nothing ever deletes one. A ledger for a route id the dataset later renames stays readable and unlisted (`testARenamedRouteLeavesItsLedgerReadableAndUnlisted`).

**Encoding.** `JSONEncoder` with `.iso8601` dates and `[.sortedKeys]`, decoded with `.iso8601` (the same settings as the store's, `PilgrimageLedger.swift:144-154@7c200bf`). Probed on this Mac (a scratch probe, `ledger_probe.swift`, not committed):

```json
{"carriedKm":20.4,"redrawNoticePending":true,"routeId":"camino-frances","stages":{"0":{"completed":true,"distanceKm":24.2,"kmWalked":24.2,"name":"Saint-Jean \/ Roncesvalles","walkedAt":"2027-01-15T08:00:00Z"},"10":{"completed":false,"distanceKm":21.9,"kmWalked":12.701999999999998,"name":"b","stoppedAtFrac":0.58,"walkedAt":"2027-01-15T08:00:00Z"},"2":{"completed":false,"distanceKm":20,"kmWalked":0,"name":"c","stoppedAtFrac":0,"walkedAt":"2027-01-15T08:00:00Z"}}}
```

- Keys sorted as strings at every level: stage keys order `"0"`, `"10"`, `"2"`. Android's `WayJson` sorts the same way (`toSortedMap()` on `String`, `WayJson.kt:60-61@0defff85`).
- Optionals that are nil are omitted (`carriedKm`, `redrawNoticePending`, `stoppedAtFrac`); `clearRedrawNotice` removes the key. Android's `WayJson` has `explicitNulls = false`.
- Dates are whole seconds, **truncated** (`…:00.75` → `…:00Z`), in UTC with `Z`. Android's `WayDateSerializer` truncates to seconds too (`WayJson.kt:80-82@0defff85`).
- Byte-level differences that don't matter (only the platform that wrote a ledger ever reads it; the ledger never travels): Swift escapes `/` as `\/` and writes whole doubles as `20`; kotlinx writes `/` and `20.0`. Don't byte-compare against iOS output in tests.
- Decoding: unknown keys are ignored, a `null` optional decodes as nil, an integer decodes as a `Double`, and any missing required key or wrong type fails the whole ledger, which `load` then reports as nil. Android: `WayJson` (`ignoreUnknownKeys = true`) and a `decodeOrNull`.

**`record`: the keep-best merge, exactly.**

```swift
    mutating func record(stageIndex: Int, name: String, distanceKm: Double, outcome: HonorStageOutcome, at date: Date) {
        let frac = min(max(outcome.progressFrac.isFinite ? outcome.progressFrac : 0, 0), 1)
        let km = distanceKm.isFinite ? max(0, distanceKm) : 0
        let key = String(stageIndex)
        let existing = stages[key]
        // A second, shorter walk of the same stage never un-walks it: the
        // ledger keeps the best the pilgrim has done.
        let completed = outcome.arrived || (existing?.completed ?? false)
        let walkedKm = max(completed ? km : km * frac, existing?.kmWalked ?? 0)
        stages[key] = Entry(
            name: name, distanceKm: km, walkedAt: date, kmWalked: walkedKm,
            completed: completed, stoppedAtFrac: completed ? nil : frac)
    }
```
> Pilgrim/Models/Honor/PilgrimageLedger.swift:59-71@7c200bf

Field by field:
- `frac`: the outcome's `progressFrac`, NaN or infinite → 0, then clamped to `[0, 1]`.
- `km`: the stage's `distanceKm`, non-finite → 0, negative → 0.
- `completed`: **sticky**: `arrived || existing.completed`.
- `kmWalked`: `max(completed ? km : km × frac, existing.kmWalked)`. A completed stage is credited its whole `distanceKm`, whatever the walker covered; a partial one `km × frac`, which is position along the stage, not distance walked (defect D-7). A NaN `existing.kmWalked` read from disk: Swift's `max(x, y)` is `y >= x ? y : x`, so `max(fresh, NaN)` is `fresh` (probed: `max(5.0, .nan)` = 5, `max(.nan, 5.0)` = NaN). A NaN on disk is therefore replaced by this walk's figure. Kotlin's `maxOf` returns NaN in both orders, so port the expression as `if (existing >= fresh) existing else fresh` (with `existing` defaulting to 0). Only a hand-edited file holds a NaN, and `totalKmWalked` filters non-finite values either way.
- `name`, `distanceKm`: **last record wins**: the walked Way's stage block replaces what was there, even on an older or shorter walk.
- `walkedAt`: **last record wins**. Never read on iOS outside this file (a search of `Pilgrim/` finds no reader).
- `stoppedAtFrac`: **last record wins** for a partial stage: nil once completed, else this walk's `frac`, even when an earlier walk got further (the next row's copy only checks for non-nil, so no visible effect today; flow gap 8, defect D-6). A walk anchored and stopped at the very start records `stoppedAtFrac = 0`, which still reads "continue from where you stopped".

**The writer.** The one place that decides whether a walk earns an entry: only with an outcome, which the caller has made nil unless the engine anchored on the Way (P3). The stage identity comes from the Way's `stage` block.

```swift
enum PilgrimageLedgerWriter {

    /// Nil when the engine never anchored on the Way — Begin's frac-0
    /// fallback means the walker was still approaching, and an approach is
    /// not a stage walked.
    static func entry(stage: WayStage, outcome: HonorStageOutcome?)
        -> (index: Int, name: String, distanceKm: Double, outcome: HonorStageOutcome)? {
        guard let outcome else { return nil }
        return (stage.index, stage.name, stage.distanceKm, outcome)
    }
}
```
> Pilgrim/Models/Honor/PilgrimageLedger.swift:128-138@7c200bf

**The store.**

```swift
    func load(routeId: String) -> PilgrimageLedger? {
        guard let url = store.pilgrimageDirectory(for: routeId)?.appendingPathComponent("ledger.json"),
              let data = try? Data(contentsOf: url) else { return nil }
        return try? decoder.decode(PilgrimageLedger.self, from: data)
    }

    func save(_ ledger: PilgrimageLedger) {
        guard let dir = store.pilgrimageDirectory(for: ledger.routeId) else { return }
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try? encoder.encode(ledger).write(to: dir.appendingPathComponent("ledger.json"), options: .atomic)
    }

    /// The one place a stage walk reaches its route's ledger, reached from the
    /// walk's own save and from a crash recovery alike. Silent for a walk whose
    /// engine never anchored on the Way — an approach is not a stage walked.
    func record(stage: WayStage, outcome: HonorStageOutcome?, at date: Date) {
        guard let written = PilgrimageLedgerWriter.entry(stage: stage, outcome: outcome) else { return }
        var ledger = load(routeId: stage.routeId) ?? PilgrimageLedger(routeId: stage.routeId)
        ledger.record(stageIndex: written.index, name: written.name,
                      distanceKm: written.distanceKm, outcome: written.outcome, at: date)
        save(ledger)
    }

    func clearRedrawNotice(routeId: String) {
        guard var ledger = load(routeId: routeId), ledger.redrawNoticePending == true else { return }
        ledger.redrawNoticePending = nil
        save(ledger)
    }
```
> Pilgrim/Models/Honor/PilgrimageLedger.swift:160-187@7c200bf

- `record`: nil outcome → nothing written (no file created). Else load, or start empty **if the file is missing or doesn't decode**, merge, save. A ledger that fails to decode is therefore overwritten by the next record, losing every other entry and `carriedKm` (defect D-8, the ledger's form of pilgrim-ios #107 item 4).
- The route id used is `stage.routeId` (the Way's stage block), validated by `pilgrimageDirectory`; an invalid one writes nothing, silently.
- `save` creates the package folder if needed (a record for a removed route recreates nothing but the folder, which already exists since the ledger lives there).
- Every failure is swallowed: there is no retry anywhere on iOS.
- **Thread-safety on iOS:** `PilgrimageLedgerStore` is a plain class with no lock, and every call site makes its own instance. Every write happens on the main thread: the clean finish runs in `DataManager.saveWalk`'s completion (main, `DataManager.swift:142@7c200bf`), recovery's `rebindWay` in the same completion, `update`'s reconcile on the main actor, and `clearRedrawNotice` from the route page's `reload()`. So the read-modify-write never races on iOS. Android has writers in two processes (§10).

**`next`.**

```swift
    func next(stageCount: Int) -> Next? {
        guard stageCount > 0 else { return nil }
        for index in 0..<stageCount where stages[String(index)]?.completed != true {
            return Next(index: index, resumeFrac: stages[String(index)]?.stoppedAtFrac)
        }
        return nil
    }
```
> Pilgrim/Models/Honor/PilgrimageLedger.swift:78-84@7c200bf

The first index in `0..<stageCount` without a completed entry, with that entry's `stoppedAtFrac` (nil when never begun or no entry). Nil when every stage is completed **or `stageCount` is 0**. Entries at or above `stageCount` are ignored. The four next-row strings are not the ledger's: they live in `PilgrimageRouteModel.nextRow` (`PilgrimageRouteView.swift:44-50@7c200bf`, P4, U37):

```swift
    static func nextRow(ledger: PilgrimageLedger?, stageCount: Int) -> String {
        guard let next = (ledger ?? PilgrimageLedger(routeId: "")).next(stageCount: stageCount) else {
            return "you have walked the whole way"
        }
        if next.resumeFrac != nil { return "continue from where you stopped" }
        return next.index == 0 ? "start with stage 1" : "next: stage \(next.index + 1)"
    }
```
> Pilgrim/Scenes/Honor/PilgrimageRouteView.swift:44-50@7c200bf

A partial stage wins over the index rule, so a partial stage 1 (index 0) reads "continue from where you stopped". `resumeFrac` chooses copy only: the row's tap opens the stage by index (`:245-247`), and nothing seeds the camera or the engine from it.

**`progressLine`.**

```swift
    static func progressLine(ledger: PilgrimageLedger?, stageCount: Int) -> String {
        guard let ledger, !ledger.stages.isEmpty || (ledger.carriedKm ?? 0) > 0 else {
            return stageCount == 1 ? "1 stage" : "\(stageCount) stages"
        }
        let walked = StatsHelper.string(for: ledger.totalKmWalked * 1000, unit: UnitLength.meters, type: .distance)
        guard let next = ledger.next(stageCount: stageCount) else {
            return "you have walked the whole way · \(walked)"
        }
        return "stage \(next.index + 1) of \(stageCount) · \(walked) walked"
    }
```
> Pilgrim/Models/Honor/PilgrimageLedger.swift:87-96@7c200bf

- No ledger, or one with no entries and no positive `carriedKm` → `"1 stage"` / `"N stages"` (the same words the catalog card uses for a route not installed).
- `walked`: `StatsHelper` distance of the total (entries' finite `kmWalked` plus a finite `carriedKm`), in the walker's unit with two-digit rounding; Android's counterpart is `WalkFormat.distance` (own-walk spec §5.2's ruling: it switches to metres below 100 m, the app-wide convention).
- Every stage completed (or `stageCount` 0 with entries) → `"you have walked the whole way · <walked>"` (no "walked" suffix).
- Else `"stage <next+1> of <stageCount> · <walked> walked"`.
- Used on the catalog card for the installed route only (`PilgrimageCatalogView.swift:14-18@7c200bf`) and under the route page's next row. A removed route's ledger is kept but shown nowhere until the route is installed again.

**`reconciled(against:)`.**

```swift
    static let identityToleranceRatio = 0.05
// …
    func reconciled(against newStages: [PilgrimageRouteStage]) -> PilgrimageLedger {
        let byIndex = Dictionary(newStages.map { ($0.index, $0) }, uniquingKeysWith: { first, _ in first })
        var kept: [String: Entry] = [:]
        var dropped = 0.0
        for (key, entry) in stages {
            guard let index = Int(key), let fresh = byIndex[index], fresh.name == entry.name,
                  entry.distanceKm > 0, fresh.distanceKm.isFinite,
                  abs(fresh.distanceKm - entry.distanceKm) / entry.distanceKm <= Self.identityToleranceRatio else {
                dropped += entry.kmWalked.isFinite ? entry.kmWalked : 0
                continue
            }
            kept[key] = entry
        }
        guard dropped > 0 else {
            return PilgrimageLedger(routeId: routeId, stages: kept,
                                    carriedKm: carriedKm, redrawNoticePending: redrawNoticePending)
        }
        return PilgrimageLedger(routeId: routeId, stages: kept,
                                carriedKm: (carriedKm ?? 0) + dropped, redrawNoticePending: true)
    }
```
> Pilgrim/Models/Honor/PilgrimageLedger.swift:100-124@7c200bf

An entry survives only if all of these hold, else it is dropped and its finite `kmWalked` is added to `dropped`:
- its key parses as an `Int` (Swift's `Int("+3")` is 3 and `Int("03")` is 3, probed; Kotlin's `toIntOrNull` agrees on both; keys are always written by `String(index)`, so this never matters);
- the new package has a stage at that index (a shrink drops the tail's entries);
- the new stage's `name` equals the entry's `name` exactly (case and accents count);
- the entry's `distanceKm > 0` (a 0 or NaN distance always drops);
- the new distance is finite;
- `|new − old| / old <= 0.05`, relative to the **old** distance, inclusive (5.0% keeps).

A kept entry keeps its **old** `name` and `distanceKm` (the next record of that stage overwrites them with the new package's). If nothing with kilometres was dropped (`dropped == 0`), `carriedKm` and `redrawNoticePending` carry over unchanged, so a dropped entry with `kmWalked` 0 vanishes with no notice, and a notice still pending survives a quiet Update. Otherwise `carriedKm = (carriedKm ?? 0) + dropped` and `redrawNoticePending = true`. A completed entry dropped this way also stops counting as completed: `next` offers that stage again.

**The redraw notice.** Set only by a reconcile that carried kilometres. The route page's `reload()` (on appear, and after every install and Remove) reads it, shows "the route's stages were redrawn; your kilometres are kept." in the status footer, and clears it from the file at once; the notice stays on screen for that page's life (`PilgrimageRouteView.swift:365-382@7c200bf`). So after an Update on the route page itself, the notice appears right away, on that page, and never again.

**Android today.** Nothing: no ledger type, no file. U33 ports the model, record, writer, `next`, `progressLine`, `reconciled`, and the store with these exact rules, through `WayJson` and `writeAtomically`.

---

### 9. The record call sites: clean finish and recovery

**The boundary with P3.** P3 owns how `honorStageOutcome` and `honorCheckpointState` are computed. For my purposes they are: at teardown, a `HonorStageOutcome(progressFrac:arrived:)` only when the engine `isAnchoredOnWay`, else nil; and in each checkpoint, the same pair or nil, written from the same engine read.

```swift
    func teardownHonor() {
        guard honorEngine != nil || wayVoicePlayer != nil else { return }
        if let engine = honorEngine, engine.isAnchoredOnWay {
            honorStageOutcome = HonorStageOutcome(progressFrac: engine.progressFrac,
                                                  arrived: engine.phase == .arrived)
        }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:128-133@7c200bf

**Clean finish.** Everything after `teardownHonor` is mine:

```swift
        vm.onWalkCompleted = { [weak self, weak vm] snapshot in
            snapshot.comment = vm?.intention
            DataManager.saveWalk(object: snapshot) { success, _, walk in
                if success {
                    // The save transaction is confirmed — only now is the
                    // crash-recovery checkpoint safe to discard (AF1). On
                    // failure it stays on disk and recoverIfNeeded re-saves
                    // the walk at next launch.
                    WalkSessionGuard.deleteCheckpointFile()
                }
                guard let self else { return }
                if success {
                    snapshot.uuid = walk?.uuid
// …
                    if let way, let uuid = walk?.uuid {
                        if !way.source.isPackageOwned { try? WayStore.shared.save(way) }
                        let arrival = vm?.honorArrival.map { (theirSeconds: $0.theirSeconds, yourSeconds: $0.yourSeconds) }
                        try? WayStore.shared.link(walkUUID: uuid, to: way.id, arrival: arrival)
                        self.recordStageWalk(way: way, outcome: vm?.honorStageOutcome)
                    }
                    self.pendingSnapshot = snapshot
                    self.activeWalkViewModel = nil
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:95-123@7c200bf

```swift
    func recordStageWalk(way: Way?, outcome: HonorStageOutcome?, at date: Date = Date()) {
        guard let stage = way?.stage else { return }
        pilgrimageLedgers.record(stage: stage, outcome: outcome, at: date)
    }
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:292-295@7c200bf

The exact order, on the main thread, inside the save's completion:
1. The walk row saves. On failure: nothing below runs; the checkpoint stays for the next launch's recovery; "Save Failed" shows, and the walk screen (and the package guard) stay until it is dismissed.
2. On success, **the checkpoint is deleted first**. A kill from here to step 6 leaves the walk saved, unlinked, and unrecorded, with no checkpoint to recover it: no retry exists (defect D-5).
3. A package-owned Way is **never** re-saved (`way` was captured at Begin; an Update during the walk would be written back over).
4. `link(walkUUID:to:arrival:)` with the arrival numbers if arrival fired (a stage's `theirSeconds` is the package's synthesized clock; the summary ignores it, P5). `try?`: a failed link is silent.
5. `recordStageWalk(way:outcome:)`: the stage block comes from the **in-memory Way captured at Begin**, not from disk; the outcome is the one teardown captured; the date is `Date()` at this moment (seconds after Finish). Nil outcome (never anchored) or a Way with no stage block → nothing. Runs whether or not the link succeeded.
6. `activeWalkViewModel = nil`: the package guard lifts only now, after the record.

**Recovery.**

```swift
        DataManager.saveWalk(object: recovered) { success, error, saved in
            if success {
                try? FileManager.default.removeItem(at: url)
                rebindWay(checkpoint: checkpoint, walkUUID: saved?.uuid, store: wayStore)
```
> Pilgrim/Models/Walk/WalkSessionGuard+Recovery.swift:119-122@7c200bf

```swift
    private static func rebindWay(checkpoint: WalkCheckpoint, walkUUID: UUID?, store: WayStore) {
        guard let wayId = checkpoint.wayId, let walkUUID,
              let way = store.load(id: wayId) else { return }
        try? store.link(walkUUID: walkUUID, to: wayId, arrival: nil)
        guard let stage = way.stage else { return }
        PilgrimageLedgerStore(store: store).record(
            stage: stage, outcome: checkpoint.honorOutcome, at: checkpoint.checkpointDate)
    }
```
> Pilgrim/Models/Walk/WalkSessionGuard+Recovery.swift:138-145@7c200bf

```swift
    /// Both halves are written together from the same engine read, so either
    /// one missing means no stage was joined and no ledger entry was earned.
    var honorOutcome: HonorStageOutcome? {
        guard let honorProgressFrac, let honorArrived else { return nil }
        return HonorStageOutcome(progressFrac: honorProgressFrac, arrived: honorArrived)
    }
```
> Pilgrim/Models/Walk/WalkCheckpoint.swift:27-32@7c200bf

The exact order, at launch (`MainCoordinator.init` → `recoverIfNeeded`), in the recovered walk's save completion (main):
1. The checkpoint file is deleted (again before anything Honor).
2. `rebindWay`: needs a `wayId` in the checkpoint, the saved walk's uuid, **and the Way loading from disk** (`store.load`); else nothing, no link and no record.
3. `link` with **no** arrival numbers, `try?`.
4. If the **disk** Way has a stage block: `record` with the checkpoint's outcome (nil when the engine hadn't anchored, or a schema-1 checkpoint) at the checkpoint's own date (`checkpointDate = Date()` when that checkpoint was built, `WalkCheckpoint.swift:43@7c200bf`). The checkpoint is written every 30 s (60 s meditating, 15 s low battery, 10 s critical) and on entering the background (`WalkSessionGuard.swift:48-55`, `AppDelegate.swift:247-252@7c200bf`), so the outcome can be that stale.

Two differences from the clean path: recovery reads the stage identity from the **disk** Way (so an Update between crash and launch would record under the new name; it can't happen, since recovery runs before any screen), and it requires the Way to exist.

---

### 10. Android's split, step by step, and the plan's Key Technical Decisions

**Where Android finalizes.** The walk ends in `finishWalkAtomic` (one transaction: `end_timestamp`, and `finish_kind` on the session row), then `runHonorFinalize` → `HonorFinalizer.finalize`, from three places:

| Call site | Process | Kind | When |
|---|---|---|---|
| `WalkControllerImpl` `FinalizeWalk` effect (`:696-711`) | `:tracker` | `CLEAN` | Finish |
| `UiWalkController.recoverStaleWalks` (`:503-505`) and `WalkControllerImpl.recoverStaleWalks` (`:449-455`) | UI (and `:tracker`) | `RECOVERED` | launch, both processes dead |
| `HonorFinalizer.runAtLaunch` → `finalizePending` | UI | either | every UI launch, flag on |
| `PilgrimPackageImporter` (`:298-300`, `:412-414`) | UI | either | a `.pilgrim` import replacing or stripping a walk |

```kotlin
    suspend fun finalize(walkId: Long): HonorFinalizeOutcome = withContext(ioDispatcher) {
        val dao = database.honorDao()
        val session = dao.getSession(walkId) ?: return@withContext HonorFinalizeOutcome.DONE
        // A walk gone since the read took its live rows with it.
        val walk = database.walkDao().getById(walkId) ?: return@withContext HonorFinalizeOutcome.DONE
        val endedAt = walk.endTimestamp ?: return@withContext HonorFinalizeOutcome.NOT_FINISHED
        // Every finish path records a kind; a row without one had no clean finish to record.
        val kind = session.finishKind ?: HonorFinishKind.RECOVERED
        try {
            when (kind) {
                HonorFinishKind.CLEAN -> finalizeClean(walk.uuid, session)
                HonorFinishKind.RECOVERED -> finalizeRecovered(walk.uuid, session)
            }
        } catch (e: IOException) {
            Log.w(TAG, "walk $walkId: Honor step left for the next launch (${e::class.simpleName})")
            return@withContext HonorFinalizeOutcome.PENDING
        }
        dao.insertMarker(HonorWalkMarkerEntity(walkUuid = walk.uuid, finishedAt = endedAt, finishKind = kind))
        dao.deleteLiveRows(walkId)
        HonorFinalizeOutcome.DONE
    }
```
> app/src/main/java/org/walktalkmeditate/pilgrim/walk/honor/HonorFinalizer.kt:79-99@0defff85

```kotlin
    private fun finalizeClean(walkUuid: String, session: HonorSessionEntity) {
        val arrival = session.arrivalTheirSeconds?.let { theirs ->
            session.arrivalYourSeconds?.let { yours -> WayArrival(theirSeconds = theirs, yourSeconds = yours) }
        }
        link(walkUuid, session.wayId, arrival)
        if (session.sourceKind == HonorSourceKind.OWN_WALK) wayStore.promoteStaged(walkUuid)
    }

    private fun finalizeRecovered(walkUuid: String, session: HonorSessionEntity) {
        if (wayStore.load(session.wayId) != null) link(walkUuid, session.wayId, arrival = null)
    }
```
> app/src/main/java/org/walktalkmeditate/pilgrim/walk/honor/HonorFinalizer.kt:171-181@0defff85

**Mapping iOS's steps onto Android's finalize:**

| iOS step | Android at `0defff85` | What U36 adds |
|---|---|---|
| save the walk row | `finishWalkAtomic` | — |
| delete the checkpoint | (no checkpoint; the live rows persist until the end) | — |
| never re-save a package Way | `finalizeClean` promotes staging only for `OWN_WALK` | nothing: a `PILGRIMAGE` session is never staged or promoted |
| clean `link` with arrival | `finalizeClean`'s `link` | — |
| clean `record(stage, outcome, Date())` | — | after the link, inside the `try`: record from the session row's stage identity and outcome, at `walk.endTimestamp` |
| recovery: `load` the Way, `link` no arrival | `finalizeRecovered` | — |
| recovery: `record(diskWay.stage, checkpoint.outcome, checkpointDate)` only if the Way loads | — | inside the same `if`: record from the row at `walk.endTimestamp` (the last location sample, or start + 1 ms, `UiWalkController.kt:503@0defff85`) |
| `activeWalkViewModel = nil` (guard lifts) | the marker, then `deleteLiveRows` | the record lands before both |

**Each Key Technical Decision on the ledger, against iOS:**

1. **"Ledger storage: a file at `noBackupFilesDir/Ways/pilgrimage/<route>/ledger.json`, mirroring iOS's path."** Parity-exact (iOS: inside the backup-excluded `Ways` tree). A device transfer carries Room (walks, markers) but no Ways tree, so a new phone has walks with Honor markers and no ledger, links, or stage Ways (gap 15). iOS's tree is excluded from backup the same way (`WayStore.swift:44-47@7c200bf`). The `.pilgrim` export carries no Honor files on either platform (iOS's package code names only the two Honor event types, `PilgrimPackageConverter.swift:500-514@7c200bf`; Android's builder has no Honor reference), so an export and import also starts the ledger empty. State it in the spec; nothing to build.
2. **"Two lock layers."** An Android addition (A-3). iOS needs none: every write is on main (§8). Android's writers: `:tracker`'s Finish, the UI's launch retry and recovery, the `.pilgrim` importer's pre-finalize, Update's reconcile, and the route page's notice clear. The plan's shape is sound: a process-wide per-route `Mutex` or `ReentrantLock` (a JVM `FileChannel.lock` from a second thread throws `OverlappingFileLockException` instead of waiting), then `FileChannel.lock()` on a sibling lock file (`AtomicFile` isn't multi-process safe), then read, merge, `writeAtomically`, release in reverse. Two details to pin: `OverlappingFileLockException` is a `RuntimeException`, so it would escape `finalize`'s `catch (e: IOException)` and land in `runHonorFinalize`'s catch as `PENDING` (fine, but the in-process layer must make it impossible); and the lock file must survive `sweepTempFiles` (§1). Every read-modify-write takes the lock: `record`, the reconcile, and `clearRedrawNotice`. Plain reads (`load` for the catalog, route page, summary) need no lock, since writes are atomic renames.
3. **"Records carry the walk's end time, never the clock."** An Android addition with no visible effect (A-4). iOS dates a clean record at `Date()` after the save and a recovered one at the checkpoint's time. `walkedAt` is never read on iOS, so the change shows nowhere. It is what makes a replay write identical bytes, and what the order-safe merge compares.
4. **"Order-safe merge."** An Android addition (A-5), needed because Android, unlike iOS, can record walks out of order: walk 1's step fails (I/O), walk 2 finishes and records, the next launch's retry records walk 1. iOS's last-wins fields would then let the older walk overwrite `walkedAt`, `stoppedAtFrac`, `name` and `distanceKm`. The plan names `walkedAt` and `stoppedAtFrac`; **add `name` and `distanceKm`** to the fields an older record leaves alone (correction C-8). Exact rule for a record whose date is **earlier** than the existing entry's `walkedAt` (compare at whole seconds, as stored): `completed = existing.completed || arrived`; `kmWalked = max(completed ? km : km × frac, existing.kmWalked)` with this record's own `km`; `name`, `distanceKm`, `walkedAt` kept; `stoppedAtFrac = completed ? nil : existing.stoppedAtFrac`. An equal or later date applies iOS's rule unchanged (so a replay of the same walk is idempotent, and every in-order record is iOS's).
5. **"Finalize ordering: the ledger record lands before the Honor marker and before the live rows are deleted, inside the existing write-error retry path."** The ordering is the Android equivalent of iOS's "record before `activeWalkViewModel = nil`" (the session row is Android's only holder of the outcome, as the view model is iOS's). The retry is an Android addition (A-2): iOS swallows a failed record. Two pins: (a) the ledger `save` must **throw** `IOException` on a failed write (iOS's `try?` swallows), or the retry never sees it; (b) a record that can never be written (an invalid route id, which `pilgrimageDirectory` refuses) counts as done, as `link()` treats an invalid Way id. The link stays first: a record failure after a successful link re-runs both next time, which is idempotent.
6. **"The stage's identity (route id, index, name, kilometres) is copied into the session row at Start."** Equivalent to iOS's clean path, which records from the in-memory Way captured at Begin, not from disk. Pin the source: the Way `:tracker` loaded at Start (the same bytes the engine walks), not the overview's copy. For **recovery**, iOS reads the identity from the disk Way and records only if that Way loads. Recommend parity on both paths: the clean path records from the row whether or not the Way loads (iOS records from memory after a link that may point at nothing); the recovered path records only inside `finalizeRecovered`'s existing `load` check, using the row's identity. The row's copy equals the disk Way's stage block whenever the package hasn't changed under the walk, which the guard ensures; record the identity's source as a dated equivalent (A-7).
7. **"The walk guard for package changes: wider than a Room probe."** iOS's guard is "the walk screen is up" (§2). Android's `walkScreenUp` is that clause. The other three are additions (A-1): an active walk row (a walk on in `:tracker` with no UI walk screen, as after a UI reclaim), live session rows (a finished walk whose Honor step hasn't run, which iOS never has because its step is synchronous), and a Begin in flight (Start to the session row). The plan's "checked on entry and again before the commit" matches iOS's two checks for `download`; iOS **doesn't** re-check before Replace's old-route removal or Update's tail and reconcile, and the plan should say whether Android's guard also covers those post-commit steps (correction C-4). Recommend: hold "an operation in flight" from entry to the last post-commit step, and have Start wait on that, not only on the commit.
8. **"A pending finalize mustn't block forever: run `finalizePending()` once and check again."** An Android addition (part of A-1). Sound: iOS can never be blocked by a pending step. Run it in the UI process only.
9. **"The stage seen at the door is the stage walked: Begin carries the `WayFileStamp` … Start refuses with the existing 'Gone' copy if the stage's `way.json` has changed."** Not equivalent in what the walker sees. iOS **walks** the Way captured at the door whatever the package did, with no refusal; the commit that can change it is one already past its before-commit check when Begin opened the walk screen. Android's stamp check refuses that walk with "This walk isn't here to follow anymore. Try another." (owner decision 5's copy for a missing source walk, wrong for a stage that is still there, redrawn) and discards it. It's an Android addition (A-8) with a user-visible difference in a narrow window; see O-2 for the alternative (stage the stage Way per walk at Start, which reproduces iOS's in-memory capture exactly, revival included).
10. **"Stage Ways are read from the package, not staged per walk."** iOS never stages, but iOS also never re-reads: its walk holds the Way in memory. Reading from the package is parity only while nothing changes it, which is why the plan needs A-1 and A-8. O-2.
11. **"Package downloads run in the UI process, in an app-scoped coroutine with a `StateFlow` phase … The temp set lives under `noBackupFilesDir`."** Parity with iOS's foreground `URLSession` and `@Published` phase. The temp location and its launch sweep are A-6 (§3). A backgrounded app that Android freezes stalls the download into "the download didn't finish" (gap 13). iOS's package session is a foreground one by design ("an all-or-nothing set gains nothing from a background session it could not resume", design spec §2), so a suspended iOS app stalls too: parity, and a device-pass row (U41).
12. **"`WayStore.list()` skips `pilgrimage:` ids before decoding."** No behaviour change for any caller but the footer (§1, §11). Every ported iOS test that asserts `wayStore.list().isEmpty` (or a `list()` count) as proof that no stage survived must assert on the new stage-id listing instead, or it proves nothing on Android (correction C-9).
13. **"Kills mid-commit: port iOS's `replacing.txt` marker handling exactly."** Yes, and that includes calling `installed()` at UI launch (§12), which is what finishes an interrupted Replace on iOS at the pin.

---

### 11. The Settings → Ways footer

```swift
    static func listable(_ ways: [Way]) -> [Way] {
        ways.filter { !$0.source.isPackageOwned }
    }
// …
    static func packageFooter(routeName: String?, stageCount: Int) -> String? {
        guard let routeName, stageCount > 0 else { return nil }
        return "the \(routeName) keeps its \(stageCount) \(stageCount == 1 ? "stage" : "stages") on its route page"
    }
```
> Pilgrim/Scenes/Settings/WaysListView.swift:12-28@7c200bf

```swift
    private func reload() {
        for id in WayStore.shared.sweepExpired(now: Date()) { WayMediaDownloader.shared.cancel(wayId: id) }
        let all = WayStore.shared.list()
        ways = WaysListModel.listable(all)
        // The stages the list just hid, named by the route that owns them.
        packageFooter = WaysListModel.packageFooter(routeName: PilgrimagePackageManager.shared.installed()?.route.name,
                                                    stageCount: all.count - ways.count)
        details = Dictionary(uniqueKeysWithValues: ways.map { ($0.id, detail(for: $0)) })
    }
```
> Pilgrim/Scenes/Settings/WaysListView.swift:95-103@7c200bf

**The formula.** `all.count - ways.count` = every Way in the store that decodes and is package-owned (`source` is `.pilgrimage`). That is:
- the installed route's stage Ways;
- **plus** every walked stage Way a Remove, Replace, rollback, or shrink kept (§1), from any route, the installed one or not;
- **plus** any orphans a kill mid-commit left (§7): a first install's or a Replace's partial stages, an Update's untouched tail.

The name is `installed()?.route.name`: the installed `route.json`'s `name` (not the catalog's), which also runs the marker branch (§4). The line is nil when no route is installed (the WaysListModelTests comment: "stages with no installed route to name — a Replace cut short — say nothing") or the count is 0.

**Matched as shipped:** after walking three stages of the Camino Francés and replacing it with a four-stage route, the footer reads "the <four-stage route> keeps its 7 stages on its route page". The plan asks U30 to rule (U40): the footer counts every kept walked stage of every route, named by the installed route, as shipped (defect D-9). Android's count (the plan's stage-id listing) must include them too: count every `pilgrimage:` folder with a `way.json`, all routes, not only the installed route's ids.

Where it shows: the last row of the list, after the rows and the "Delete all Ways" button (which shows only when the list isn't empty), or after "no ways yet" when it is. Caption font, fog colour. Recomputed on every `reload()` (on appear and after each delete).

**Android today.** `WaysListViewModel.reload()` sweeps, then builds rows from `listable(store.list())`; there is no footer field (`WaysListViewModel.kt:250-255@0defff85`). U40 adds a nullable footer to `WaysListUiState.Loaded`, computed on the IO dispatcher in `reload()` from the stage-id count and `packageManager.installed()?.route.name` (UI process; the call can write, §4). The Data card's row (`WaysRowViewModel`) counts the listable set only, as iOS's does; no change.

---

### 12. Launch

**iOS at the pin.** After the store is readable, launch runs three things, all skipped under XCTest:

```swift
    private func reconcileTilesAtLaunch() {
        guard NSClassFromString("XCTestCase") == nil else { return }
        Task { @MainActor in
            PilgrimagePackageManager.shared.tiles = PilgrimageTilesManager.shared
            let installed = PilgrimagePackageManager.shared.installed()
            PilgrimageTilesManager.shared.reconcile(
                installed: installed.map { (routeId: $0.routeId, stageCount: $0.route.stageCount) })
        }
    }
```
> Pilgrim/AppDelegate.swift:184-192@7c200bf

- The expiry sweep (detached; stage Ways have no expiry, so it never touches them).
- `installed()` on the main actor (slice three's tiles reconcile is its caller; the comment above it says "a kill mid-Replace is finished by installed()'s marker branch, which no lifecycle hook sees", `AppDelegate.swift:176-179@7c200bf`). So at the pin, an interrupted Replace is finished at every launch, before any screen asks.
- Crash recovery (`MainCoordinator.init`), unordered with the two above.

iOS has no temp-set sweep and no package reconciliation at launch.

**Android.** `PilgrimApp.onCreate` runs `honorFinalizer.runAtLaunch()` on the finalization scope with the flag on, UI process only, after the blocking stale-walk recovery (`PilgrimApp.kt:434-440@0defff85`); `runAtLaunch` retries pending steps (now including ledger records), sweeps staging and Ways temp files, then the expiry sweep. U34 adds, flag on, UI process only: `packageManager.installed()` (the iOS launch call, so the marker branch runs at launch as on iOS; the tiles reconcile joins it in Stage 21-3) and the pilgrimage temp-set sweep (A-6). Put both after `finalizePending`, so a pending ledger record lands before anything retires stages.

**Mid-walk launches (gap 9).** On iOS a launch means no walk is live. Android's UI process can restart while `:tracker` walks. Neither launch step can hurt a live walk: the temp sweep touches only abandoned temp sets, and the marker branch acts only on a marker a killed Replace left, which cannot coexist with a walk begun after it (Replace is refused while a walk is on, and starting a walk needs a UI launch, which runs the marker branch first). `installed()` and the manager are UI-process only; `:tracker` reads stage Ways through `WayStore` and writes only the ledger.

---

### 13. The flow-analysis gaps this cluster touches

| Gap | What iOS does | What Android must do |
|---|---|---|
| 1. Walk guard narrower than iOS's | `activeWalkViewModel != nil`: the walk screen from Begin (pre-Start) to the end of the save callback, any walk mode; checked on entry and before the commit; never during the commit or for post-commit steps (§2) | `walkScreenUp` is iOS's clause. The other clauses are A-1. Hold the guard from entry through the last post-commit step (C-4). Start waiting on an operation in flight is an addition; iOS's walk simply holds its captured Way (O-2) |
| 2. Remove can delete a stage whose ledger write is pending | No such state: the record runs in the save callback, before the guard lifts | Count live-session Way ids as walked in `retireMany` (A-1). With the guard's live-rows clause too, no package operation can run while a finished walk's step is pending; the clean record reads the row's identity (A-7), so it lands even if the stage went |
| 3. Ledger writers in both processes | All writes on main; no lock (§8) | The two lock layers (A-3), end-time dates (A-4), the order-safe merge with `name` and `distanceKm` added (A-5, C-8) |
| 4. Finalize ordering | Checkpoint deleted, then link, then record, then the guard lifts; nothing retried (§9) | Link, then record, then marker, then live rows, inside the retry path; the ledger `save` must throw (C-7) |
| 5. Kill mid-commit; where the temp set lives | The kill table (§7); `tmp/`, purged by the OS; no repair | Port the marker exactly, `installed()` at launch (§12); temp set under `noBackupFilesDir`, swept at launch sparing the live one (A-6); a reconciliation only if the owner chooses (O-1) |
| 7. Summary of a never-anchored stage walk | No ledger entry, so `stageProgressLine` is nil and the "X of Y km" line is absent; the link is still written (`testACrashBeforeTheStageWasJoinedLinksTheWayAndWritesNoLedger` for recovery). With an earlier walk's entry, the line shows that walk's best | Same (P5 owns the summary). Android's summary must re-read the ledger when the marker lands (the marker promises the record), since it can open before `:tracker`'s step runs (`HonorWalkRecords.kt:43-55@0defff85` already re-reads on the marker for the link) |
| 8. Re-walks and redraws change past summaries; `stoppedAtFrac` keeps the latest | Confirmed: Update rewrites walked stages' `way.json` in place; the summary reads the ledger's best; `stoppedAtFrac` is last-wins (§8) | Match as shipped (D-6); the ported tests pin it |
| 9. Launch work runs mid-walk | Never happens on iOS | §12: safe as specified; `installed()` UI-only |
| 10. A second download erases a Replace's marker | Confirmed (§4) | Match as shipped (C-6, D-3, O-3) |
| 11. (part) `list()` decodes stage JSON | Every caller filters stages out afterwards; the footer counts them | Skip before decoding; footer counts by listing (§1, §11); fix the tests' `list().isEmpty` proofs (C-9) |
| 12. (part) The redraw notice | Shown once, cleared on display (§8) | Same; clear under the ledger lock |
| 13. Backgrounded download | A foreground `URLSession` stalls when suspended; the timeout or the network error gives "the download didn't finish" | Parity; a U41 device row |
| 14. (part) Flag off | — | No pilgrimage launch work, no manager, no ledger writes with the flag off; the finalize's record runs only for a `PILGRIMAGE` session, which can't exist with the flag off |
| 15. Device transfer | The Ways tree is backup-excluded | `no_backup` is never transferred: a new phone starts with no ledger, as on iOS (§10 item 1) |

Gap 6 (water ahead in Room) belongs to P3; gaps 11's catalog half and 12's task restore to P1 and P4.

---

### Strings

Every user-visible string in this cluster, verbatim. None of them says "they", "their", or "them" except the Delete-all alert's message (an own-walk and shared-walk surface, already ported in Stage 21-1, not a stage surface).

| String | Where | Arguments | Source |
|---|---|---|---|
| `Replace the <routeName>? Its stages leave your phone; what you've walked of it is remembered if it comes back. Walks in your journal stay.` | "Replace?" alert message (route page) | `routeName` = the installed `route.json`'s `name`, else `"route"` | `PilgrimagePackageManager.swift:214-216`, `PilgrimageRouteView.swift:159` |
| `Remove the <routeName>? Its stages leave your phone; what you've walked of it is remembered if it comes back. Walks in your journal stay.` | "Remove?" alert message | `routeName` = the catalog entry's `name` | `PilgrimagePackageManager.swift:220-222`, `PilgrimageRouteView.swift:165` |
| `this route isn't walkable yet` | route page status footer, rust (`notWalkable`) | — | `PilgrimageWayImporter.swift:18` |
| `the download didn't finish` | route page status footer, rust (`incomplete`: network, caps, status, busy refusals, Remove during a download, a missing fresh install after Update) | — | `PilgrimageWayImporter.swift:19` |
| `not enough space on this phone to save these voices` | route page status footer, rust (`diskFull`, borrowed from the shared-walk import copy) | — | `HonorImportReducer.swift:34`, via `PilgrimageWayImporter.swift:20` |
| `not enough space on this phone` | the `diskFull` fallback, unreachable (the borrowed line is never nil) | — | `PilgrimageWayImporter.swift:20` |
| `finish your walk first` | route page status footer, rust (`walkInProgress`) | — | `PilgrimageWayImporter.swift:21` |
| `stage <d> of <n>` | route page status footer while downloading (P4) | `d = max(done − 1, 0)`, `n = total − 1` | `PilgrimageRouteView.swift:232` |
| `the route's stages were redrawn; your kilometres are kept.` | route page status footer, fog, once per pending notice | — | `PilgrimageRouteView.swift:37` |
| `1 stage` / `<n> stages` | progress line with no ledger progress (also the catalog card for a route not installed) | `n` = catalog `stageCount` | `PilgrimageLedger.swift:89` |
| `stage <i> of <n> · <walked> walked` | progress line: catalog card (installed route), route page next row's subline | `i` = next index + 1; `walked` = `StatsHelper` distance of `totalKmWalked` | `PilgrimageLedger.swift:95` |
| `you have walked the whole way · <walked>` | progress line, every stage completed | as above | `PilgrimageLedger.swift:93` |
| `you have walked the whole way` / `continue from where you stopped` / `start with stage 1` / `next: stage <n>` | route page next row (P4; ledger-derived) | `n` = next index + 1 | `PilgrimageRouteView.swift:44-50` |
| `the <routeName> keeps its <n> stage` / `… <n> stages on its route page` | Settings → Ways footer, caption, fog | `routeName` = installed `route.json` name; `n` = package-owned Ways in the store | `WaysListView.swift:25-28` |

---

### Test inventory

Fixtures: `UnitTests/Fixtures/Pilgrimage/{index.json, index-pilgrimages.json, route.json, stage-00.json, stage-01.json}` and `UnitTests/Fixtures/PilgrimageFixtures.swift` (P1 ports them verbatim to `app/src/test/resources/honor/pilgrimage/`). Several package tests mutate the fixtures by **text replacement** (`"\"frac\": 1.0"`, `"\"stageCount\": 2"`, `"\"name\": \"Roncesvalles to Zubiri\""`, `"camino-frances"`), so the fixtures must stay byte-identical, spacing included. The others mutate by JSON object (`+Fixtures`): a three-stage route (third stage "Zubiri to Pamplona", 20.4 km, 128 m, 5–6 h, "easy", built from `stage-01.json`), a one-stage route, a route re-slugged to another id (`camino-norte`, or any id via `stubTwoStagePackage`), and a ledger seeded with both stages completed (24.2 and 21.9 km).

The stub transport is `StubURLProtocol` (per-URL body, status, headers, delay; an unstubbed URL answers `.notConnectedToInternet`). Android: MockWebServer with a URL-keyed dispatcher, `setBodyDelay`/`setHeadersDelay` for the holds, and a missing route answering a socket failure (or `SocketPolicy.DISCONNECT_AT_START`).

**`PilgrimagePackageManagerTests.swift`** (22):

| Test | Asserts |
|---|---|
| `testStageFilesAreZeroPaddedFromZero` | 0 → `stage-00.json`, 9 → `stage-09.json`, 32 → `stage-32.json`, 120 → `stage-120.json` |
| `testDownloadInstallsEveryStageAndRecordsTheRelease` | both stage Ways load with themes "Initiation"/"Descent"; `installed()` = camino-frances, v1.7.0, 2 stages; phase idle |
| `testProgressCountsTheRouteFileAndEveryStage` | phases seen include `(0,3)` and `(1,3)` and `(3,3)`; the last is idle |
| `testAStageThatFailsValidationLeavesNothingBehind` | stage-01's `frac` 1.0 → 9.0: `notWalkable`; stage 0 not in the store; nothing installed; phase `failed(notWalkable)` |
| `testANetworkFailureMidwayReportsAnUnfinishedDownload` | stage-01 unstubbed: `incomplete`; stage 0 absent; phase `failed(incomplete)` |
| `testAStageFileThatDeclaresMoreThanTheCapIsRefusedBeforeItIsBuffered` | stage-00 of `maxStageBytes + 1` with a true length: `incomplete` |
| `testARouteFileThatDoesNotMatchTheCatalogEntryIsRefused` | `stageCount` 2 → 5 in `route.json`: `notWalkable` |
| `testDownloadIsRefusedWhileAWalkIsOn` | guard true: `walkInProgress`; nothing saved |
| `testAFailedSaveMidCommitRollsBackEveryStageAlreadyWritten` | second save throws out-of-space: `diskFull`; both stages absent; store empty; nothing installed; no `route.json`/`release.txt`; phase `failed(diskFull)` |
| `testTheWholePackageIsBoundedByRealBytesNotTheIndexsClaim` | cap 1,000 bytes: `incomplete`; nothing installed; store empty |
| `testAFailedUpdateRollsBackTheWholePreviousInstallEvenAtTheSameStageCount` | install, then a re-download whose second save fails: `diskFull`; store empty; nothing installed; no package files |
| `testAFailedUpdateRollsBackStagesLeftoverFromALargerPreviousInstall` | three-stage install, then a two-stage re-download failing at save 2: the third stage is gone too |
| `testASecondDownloadIsRefusedWhileOneIsInFlight` | `route.json` held 0.3 s; a second call 50 ms in: `incomplete`; the first completes and installs |
| `testRemoveReplaceAndUpdateReachTheTilesManager` | slice three (Stage 21-3): Update to one stage removes region 1 and downloads no maps; Replace removes the outgoing route's regions; Remove of the new route touches none of the old route's |
| `testReplaceOnlyRemovesTheFirstRouteOnceTheSecondIsComplete` | Replace to camino-norte: the old stages gone, the new installed, the old ledger's completed count still 1, the old `route.json` gone |
| `testReplaceWithTheRouteAlreadyInstalledBehavesLikeAnUpdate` | Replace with self to a one-stage v1.8.0: stage 1 gone, stage 0 kept, release v1.8.0, ledger keys `["0"]`, notice pending |
| `testAFailedReplaceLeavesTheFirstRouteUntouched` | camino-norte unstubbed: `incomplete`; camino-frances still installed and loadable; no norte stage |
| `testUpdateReconcilesTheLedgerByStageIdentity` | v1.8.0 renames stage 1: ledger keys `["0"]`, `carriedKm` 21.9 (±0.01), notice pending |
| `testUpdateSweepsStageWaysTheNewPackageNoLongerCovers` | Update to one stage: stage 1 gone, stage 0 kept, installed count 1 |
| `testRemoveTakesTheStagesAndKeepsTheLedger` | Remove: both stages gone, nothing installed, ledger completed count 1 |
| `testReplaceUpdateAndRemoveAreAllRefusedMidWalk` | guard true: all three throw `walkInProgress`; the install unchanged |
| `testTheConfirmationsNameTheRouteAndTheirOwnVerb` | both confirmation strings verbatim; Remove's doesn't start with "Replace" |

**`+Lifecycle`** (8):

| Test | Asserts |
|---|---|
| `testARemovedRoutesWalkedStageKeepsItsLinkItsReplyAndItsStageIdentity` | a linked stage 0 with a reply at origin −1, after Remove: the link still names it; the summary model reads it as a stage with the Way's title and the reply; unwalked stage 1 gone; nothing installed |
| `testRemovingARouteReadsTheWalkIndexWithoutRewritingItPerStage` | a hand-pretty-printed `index.json` is byte-identical after Remove; the link survives. Android: no `index.json`; assert that no link file is rewritten (bytes and mtime) |
| `testAKillBetweenAReplacesTwoHalvesIsFinishedOnTheNextRead` | two plain downloads plus a hand-written marker naming camino-frances: `installed()` returns camino-norte, frances's stages and `route.json` are gone, the marker is deleted |
| `testAReplaceThatFinishesLeavesNoMarkerBehind` | after a Replace: no marker; norte installed |
| `testRemoveIsRefusedWhileADownloadIsInFlight` | an Update with `route.json` held 0.4 s; a Remove 100 ms in: `incomplete`; the Update lands (v1.8.0) and stage 0 loads |
| `testAWalkBegunWhileTheStagesStreamAbortsTheCommit` | stage-01 held 0.4 s; the guard flips true 100 ms in: `walkInProgress`; store empty; nothing installed; phase `failed(walkInProgress)` |
| `testAStageThatCountsADifferentNumberOfStagesThanItsRouteIsRefused` | stage-00's `count` 3: `notWalkable`; nothing installed; store empty |
| `testAStageCarryingANameItsRouteFileNeverUsedIsRefused` | stage-00's name "Somewhere else entirely": `notWalkable`; nothing installed; store empty |

**`+Streaming`** (2):

| Test | Asserts |
|---|---|
| `testAFileThatNeverDeclaresItsLengthIsRefusedByTheCapCountedWhileStreaming` | `route.json` of `maxRouteBytes + 1` with no `Content-Length`: `incomplete`; nothing kept |
| `testAStageServedAsNotFoundIsAnUnfinishedDownload` | stage-01 served 404 with a valid body: `incomplete`; nothing installed; store empty; phase `failed(incomplete)` |

**`PilgrimageLedgerTests.swift`** (11):

| Test | Asserts |
|---|---|
| `testACompletedStageAndAPartialOneAreBothRemembered` | stage 0 arrived: completed, 24.2 km, no `stoppedAtFrac`; stage 1 at 0.58: not completed, `stoppedAtFrac` 0.58, 21.9 × 0.58 km; total 24.2 + 12.70; completed count 1 |
| `testASecondWalkOfTheSameStageNeverLosesGround` | completed, then a 0.2 walk a day later: still completed, 24.2 km |
| `testNextOffersTheFirstUnwalkedStageAndResumesAPartialOne` | after stage 0: `Next(1, nil)`; stage 1 at 0.58: `Next(1, 0.58)`; all three completed: nil |
| `testAnEmptyLedgerOffersTheFirstStage` | empty ledger, 33 stages: `Next(0, nil)` |
| `testProgressLineReadsTheStageYouAreOn` | nil ledger: "33 stages"; four 28 km stages: "stage 5 of 33 · <112 km> walked"; all 33: starts with "you have walked the whole way" |
| `testReconcileKeepsStagesWhoseIdentityHeldAndCarriesTheRestsKilometres` | identical and 3.7% kept, renamed dropped: keys `["0","1"]`, `carriedKm` 20.4, total unchanged, notice pending |
| `testAStageWhoseDistanceMovedMoreThanFivePercentIsDropped` | 20 → 21.5 km (7.5%): dropped; `carriedKm` 20 |
| `testAnUnchangedRouteRaisesNoNotice` | same name and distance: kept; no notice; no `carriedKm` |
| `testNothingIsWrittenWithoutAnAnchor` | writer: nil outcome → nil; an outcome → index 3, 24.2 km |
| `testTheLedgerOutlivesTheStages` | save, then delete stage 0's Way: the ledger still loads with completed count 1; `load("../etc")` is nil |
| `testARenamedRouteLeavesItsLedgerReadableAndUnlisted` | a `shikoku-88` ledger: the catalog fixture lists no such route; the ledger still loads, 1 completed, 40 km |

**`PilgrimageStageWalkTests.swift`** (the record and list cases; the rest are P3's and P5's):

| Test | Asserts |
|---|---|
| `testTheCoordinatorWritesTheStageIntoTheRoutesLedger` | `recordStageWalk(stage 4, 0.58, not arrived)`: entry "4" with the stage's name, 0.58, not completed; `next(33)` = `Next(0, nil)` |
| `testNothingIsWrittenForAWayThatIsNotAStageOrAWalkThatNeverJoined` | nil outcome → no ledger file; a Way with no stage block → no ledger file |
| `testTheWaysListNeitherShowsAnInstalledStageNorTakesItWithTheRest` | a stage and a share in the store (`list()` count 2); `listable` keeps only the share; deleting the listed set leaves the stage loadable. Android: assert the store holds the stage through the stage-id listing, since `list()` will skip it (C-9) |

**`PilgrimageStageWalkTests+Recovery.swift`** (the three recovery cases are mine; the two checkpoint-state cases are P3's):

| Test | Asserts |
|---|---|
| `testACrashedStageWalkFindsItsWayAndItsLedgerAtNextLaunch` | a checkpoint naming stage 4 at 0.58: the recovered walk is linked to it; ledger entry "4" with its name, 0.58, not completed |
| `testACrashBeforeTheStageWasJoinedLinksTheWayAndWritesNoLedger` | checkpoint with the Way and no outcome: linked; no ledger file |
| `testACheckpointFromBeforeTheWayIdStillRecoversAsAPlainWalk` | a schema-1 checkpoint: recovered, not linked, no ledger |

**`WayStoreTests.swift`** (the stage part): `testRetireManyFollowsTheSweepsRuleAndLeavesTheIndexAlone` (an unwalked share goes whole, a walked one keeps `way.json` and its link and loses its media, an invalid id is skipped, and `index.json` is byte-identical). Android: assert no link file changes.

**`WaysListModelTests.swift`** (3): `testListableDropsPackageOwnedWaysAndNothingElse` (four stages dropped, the share and the own walk kept in order), `testTheRowCountsAndSizesTheWaysItIsGiven` ("1 way · 2.3 MB", "3 ways · 12.0 MB", "0 ways · 0.0 MB"; already ported in 21-1), `testTheFooterNamesTheRouteAndItsStagesOnlyWhenThereAreAny` ("the Nakahechi (Central Route) keeps its 4 stages on its route page", "the Kohechi keeps its 1 stage on its route page", nil for 0, nil for no route).

---

### Corrections to the Android plan

- **C-1. The next-row copy isn't the ledger's.** U33 Approach: "`next`, giving 'start with stage 1', 'next: stage N', 'continue from where you stopped', or 'you have walked the whole way'". The ledger's `next` returns only `(index, resumeFrac)`; the four strings are `PilgrimageRouteModel.nextRow` on the route page (§8), U37's. U33 ports `next` and `progressLine`; U37 ports `nextRow`.
- **C-2. The rollback, refined.** U34: "on a failed commit, remove the whole route, as iOS's `rollBack` does (it doesn't restore the prior install)". Correct, and add: only a **commit** failure rolls back; a failure while streaming or validating never touches the store, so the prior install survives it whole; the rollback retires `0..<max(previous, new)` (walked stages keep their `way.json`, in whichever version the loop reached) and leaves `ledger.json` alone (§3).
- **C-3. The package cap's unit and timing.** U34: "enforce the 50 MB total on bytes received". It is 50 MiB (`50 * 1024 * 1024`), on received bytes, checked after each whole file with `<=` passing, so a package can overshoot by up to one file before the refusal (§3).
- **C-4. The guard's reach after the commit.** KTD: "The guard is checked on entry and again before the commit". iOS checks exactly there and never after: Replace's old-route removal and Update's tail sweep and ledger reconcile run unguarded, with `phase` already `idle` (§2). Say whether Android's "operation in flight" (and Start's wait) spans those steps; recommended: from entry to the last post-commit step.
- **C-5. What iOS does at Start, and the Risks table.** KTD: "Start waits for an in-flight commit to finish rather than refusing (iOS has no copy to port for a refusal)". iOS neither waits nor refuses: its walk holds the Way it captured at the door. The wait is an Android addition (A-9). The Risks table says the opposite ("Start refuses during a commit", plan line 692); make it match the KTD. KTD: "the extra cases are Android's process split, recorded as a platform equivalent": they refuse operations iOS allows (an Update while a finished walk's step is pending), so record them as dated additions (A-1).
- **C-6. Replace's busy race is iOS's as shipped.** U34: "Replace writes `replacing.txt` (checking for busy first, the flow-analysis gap)", and its test "a second download while one runs is refused, and the in-flight Replace's marker survives". iOS writes the marker, is refused, and deletes it (§4). Checking busy first is an Android-only fix the house rule forbids. Port iOS's order, test that the refused Replace leaves **no** marker, and file the defect (D-3), unless the owner decides otherwise (O-3).
- **C-7. The ledger write must throw.** KTD: "the ledger record lands before the Honor marker … inside the existing write-error retry path". iOS's `save` swallows every failure (`try?`); Android's must throw `IOException` (as `WayStore.link` does) or `finalize` never sees a failure to retry. A record the store can never write (an invalid route id) returns quietly and counts as done, as an invalid link does.
- **C-8. The order-safe merge must also keep `name` and `distanceKm`.** KTD: "iOS lets the last record win for `walkedAt` and `stoppedAtFrac` … a record older than the entry's `walkedAt` applies only the sticky completion and the kilometre max". iOS's record also overwrites `name` and `distanceKm` (§8). An older replay must leave those alone too, or a pre-Update walk replayed after a post-Update one writes the old identity back, and the next reconcile drops the entry as a redraw. Exact rule in §10 item 4.
- **C-9. Ported tests that prove "nothing left" through `list()`.** KTD: "`WayStore.list()` skips `pilgrimage:` ids before decoding". Ten ported assertions use `wayStore.list()` to prove what the store holds: nine `isEmpty` checks that no stage Way survived (`PilgrimagePackageManagerTests.swift:168,188,216,247`, `+Lifecycle:153,175,192`, `+Streaming:27,43`) and one count of 2 that includes a stage (`PilgrimageStageWalkTests.swift:428`). On Android the nine would pass vacuously and the count would fail. Port them against the new stage-id listing (`PilgrimageStageWalkTests.swift:430`'s `listable(store.list())` stays as it is).
- **C-10. The door's file stamp isn't equivalent.** KTD: "iOS walks the Way it captured at the door; this keeps Android's re-read at Start equivalent." The walker sees a difference: iOS walks the captured Way; Android refuses with "This walk isn't here to follow anymore. Try another." and discards (the Gone copy, written for a missing source walk). Record it as an addition (A-8), and see O-2 for the per-walk staging that would be equivalent.
- **C-11. Finalize call sites.** KTD: "`:tracker`'s finalize, the UI's launch retry and post-Finish finalize, Update's reconcile, and the route page's redraw-notice clear can all write it". At `0defff85` there is no UI post-Finish finalize: the clean finish runs only in `:tracker` (`WalkControllerImpl.kt:696-711`). The UI's writers are recovery (`UiWalkController.kt:505`), `runAtLaunch`, and the `.pilgrim` importer's pre-finalize (`PilgrimPackageImporter.kt:300,414`), which the list misses. All need the lock.
- **C-12. The marker cleanup at launch is a call to `installed()`.** U34 Files: "the launch temp sweep and the marker cleanup, UI process only". iOS's launch finishes an interrupted Replace by calling `installed()` (`AppDelegate.swift:188@7c200bf`); there is no separate cleanup. Call `installed()` at UI launch, after `finalizePending` (§12).
- **C-13. `retireMany`'s live-session clause is an addition.** U33: "`retireMany` keeps a walked stage's `way.json`, replies and link, and counts live-session Way ids as walked". iOS's walked set is links only; the live-session clause is Android's (A-1), dated at the gate.
- **C-14. The footer's count, ruled.** U40: "U30 rules whether iOS also counting walked stages a Replace retired is matched as shipped". Ruled: matched as shipped. The count is every package-owned Way that decodes, of every route, kept walked stages and orphans included, named by the installed route (§11, D-9). U33's listing must not filter to the installed route's ids.
- **C-15. The record's merge, in full.** U33: "`record`, keeping the best: completed is sticky, kilometres take the max, and `stoppedAtFrac` follows iOS's rule exactly". Also: `name`, `distanceKm` and `walkedAt` are last-wins; a completed stage is credited its whole `distanceKm`; the outcome's fraction is clamped to `[0, 1]` (non-finite → 0); a nil outcome writes nothing, not even an empty file (§8).
- **C-16. Single-flight, exactly.** U34: "reentrancy is refused". iOS's refusals: a second download (also reached through Replace and Update) throws `incomplete` **without touching the phase**; Remove during a download throws `incomplete`; the walk guard throws `walkInProgress` before the phase changes on entry and sets `failed(walkInProgress)` before the commit. Nothing queues or waits (§2).
- **C-17. Recovery's record condition.** U36: "A recovered finish: link without arrival numbers, then record with the row's outcome at the walk's end time." iOS records only when the stage Way loads from disk (`rebindWay`'s `guard`). Record inside `finalizeRecovered`'s existing `load` check (§10 item 6).

---

### Notes by unit

**U33 (store tree, `retireMany`, ledger).**
- `WayStore`: `isValidRouteId` = `^[a-z0-9-]{1,64}$` (whole input); `stageWayId` = `"pilgrimage:$routeId:$index"`; `pilgrimageRoot` = `<base>/pilgrimage`; `pilgrimageDirectory(routeId)` null for an invalid id; `pilgrimageRouteIds()` = slug-named folders, unsorted.
- `retireMany(ids)`: walked set from link files plus live-session Way ids (A-1); walked → `deleteMedia`; else the folder whole under `mediaLock`, `deletionCount` bumped; invalid ids skipped; no link touched.
- `list()` skips `pilgrimage:` ids before decoding; a new `stageWayIds()` (or count) lists every `pilgrimage:` folder with a `way.json`, all routes (C-14).
- `sweepTempFiles` reaches `pilgrimage/` and each `pilgrimage/<route>/`; the lock file is spared.
- Ledger files: `pilgrimage/<route>/ledger.json` (and its lock file); encoded with `WayJson` (sorted keys, nil omitted, whole-second UTC dates); decode failures read as absent.
- The model: `HonorStageOutcome(progressFrac, arrived)`; `Entry(name, distanceKm, walkedAt, kmWalked, completed, stoppedAtFrac?)`; `Ledger(routeId, stages: Map<String, Entry>, carriedKm?, redrawNoticePending?)`; `totalKmWalked` (finite only); `completedCount`.
- `record`: §8's rules, plus the order-safe branch (§10 item 4). `next`, `progressLine` (copy in the strings table), `reconciled` (`identityToleranceRatio = 0.05`, inclusive, relative to the old distance), `PilgrimageLedgerWriter.entry`, the store's `load`, `save` (throws), `record` (stage, outcome?, at), `clearRedrawNotice`. Every read-modify-write under the two locks.
- Port `PilgrimageLedgerTests` (11), `testRetireManyFollowsTheSweepsRuleAndLeavesTheIndexAlone` (as "no link file rewritten"), plus the plan's threaded and second-JVM lock tests and the order-safe replay test.

**U34 (package manager).**
- An app-scoped singleton in the UI process; all state changes serialized (one dispatcher or a `Mutex`), with `phase: StateFlow<Phase>` (`Idle`, `Downloading(done, total)`, `Failed(error)`) and the `isDownloading` flag. Never constructed in `:tracker`.
- `download`: the ten steps of §3, in order; the package client (no cache, `readTimeout` 30 s, `callTimeout` 300 s per file, HTTP 200 only, declared length then running count, `<=` passes); temp `<index>.way.json` in `WayJson`; `route.json` committed as the raw bytes; `release.txt` with no newline; `isDiskFull` from `WayMediaDownloadWorker`; no `StatFs` precheck.
- `commit`/`rollBack`: §3, verbatim; the `saveStage` seam for the tests.
- `replace`, `update`, `remove`, `installed()`, the marker: §4–§6, verbatim, including the busy race (C-6) and the unordered `found.first`.
- The guard: `walkScreenUp || activeWalk || liveSessions > 0 || beginsInFlight` (A-1), on entry and before the commit, and held through the post-commit steps (C-4); a refusal only for live sessions runs `finalizePending()` once and re-checks.
- The temp set outside the Ways tree under `noBackupFilesDir`, published so the launch sweep spares it (A-6). Launch: `installed()` then the temp sweep, UI only, flag on, after `finalizePending` (§12).
- Slice-three seams (Stage 21-3): a nullable tiles hook called on Replace (`remove(routeId)` of the outgoing route), Remove, and Update (`removeRegions(routeId, atOrAbove: newCount)`); `isBusy` counting a tiles save on the route page.
- Port the 22 + 8 + 2 tests (§Tests), with `testRemoveReplaceAndUpdateReachTheTilesManager` deferred to Stage 21-3, the index-rewrite test adapted to link files, and C-9 applied.

**U36 (finalize).**
- In `HonorFinalizer.finalize`, for a `PILGRIMAGE` session: after `finalizeClean`'s link, record `(routeId, index, name, distanceKm)` from the row, the row's outcome (P3's mapping; nil when never anchored), at `walk.endTimestamp`; in `finalizeRecovered`, inside the `load` check, after the link, the same record. Both inside the `try`, before `insertMarker` and `deleteLiveRows`.
- A package Way is never saved or promoted at the end (no staging for a stage unless O-2 chooses staging, in which case the staging is discarded, never promoted).
- A failed ledger write throws `IOException` → `PENDING` (C-7). An invalid route id → done.
- The summary's record (`HonorWalkRecords`) gains the ledger, re-read when the marker lands, so "X of Y km of the stage" appears once the step has run (P5 renders it).
- Port `testTheCoordinatorWritesTheStageIntoTheRoutesLedger`, `testNothingIsWrittenForAWayThatIsNotAStageOrAWalkThatNeverJoined`, and the three recovery cases, against `HonorFinalizer` and the harness.

**U37 (route page; P4's, with this cluster's facts).**
- `isBusy` = phase `Downloading` (or a tiles save, Stage 21-3); it disables the button and the Remove menu only.
- `beginInstall`: another route installed and this one not → "Replace?"; else install. `install`: `hasUpdate` → `update`; `replacing` → `replace`; else `download`. Any error → its copy in the status footer; `reload()` after every install and Remove.
- `reload()`: `installed()`, the stage Ways for the installed route, the ledger, and the redraw notice shown and cleared at once (under the lock).

**U40 (Ways footer).** `the <installed route.json name> keeps its <n> stage|stages on its route page`, `n` = every `pilgrimage:` folder with a `way.json` (C-14), nil when no route is installed or `n` is 0; caption, fog, last row; computed in `reload()` on IO, calling `installed()` in the UI process. Port `testTheFooterNamesTheRouteAndItsStagesOnlyWhenThereAreAny` and `testListableDropsPackageOwnedWaysAndNothingElse`.

**U41 (device rows from this cluster).** Kill the app mid-download (nothing installed, the temp set swept at the next launch); kill during a long route's commit (record what the next launch shows, against §7); background the app mid-download (expect "the download didn't finish"); Download, Update, Replace and Remove refused with a walk screen up, pre-Start included; a `:tracker` kill after Finish (the ledger lands on the next launch, the route page's next row moves on); a UI kill mid-stage (the ledger lands from `:tracker`).

---

### Android additions to record at the gate

| # | Addition | Reason |
|---|---|---|
| A-1 | The package guard's extra clauses (an active walk row, live session rows, a Begin in flight), the one-time `finalizePending()` before a live-rows refusal, and `retireMany` counting live-session Way ids as walked | The walk and its Honor step outlive the walk screen in `:tracker`, and `:tracker` re-reads the stage's `way.json`; iOS's walk ends with its screen and holds its Way in memory |
| A-2 | A failed ledger record retried at the next launch | iOS swallows it; Android's finalize already retries a failed link (Stage 21-1's divergence, in the walker's favour) |
| A-3 | The ledger's two lock layers | Writers in two processes and several threads; iOS writes only on main |
| A-4 | Records dated at the walk's end time | Lets a retry in either process write identical bytes; `walkedAt` is never read on iOS |
| A-5 | The order-safe merge (`walkedAt`, `stoppedAtFrac`, `name`, `distanceKm` kept from an older replay) | Android's retry can record an older walk after a newer one; iOS records in end order |
| A-6 | The temp set under `noBackupFilesDir`, outside the Ways tree, swept at UI launch | iOS's `tmp/` is purged by the OS; `noBackupFilesDir` never is, and `cacheDir` can be cleared while the app runs |
| A-7 | The record's stage identity from the session row (copied at Start) | The finalize runs without the Way in memory; equal to iOS's in-memory (clean) or disk (recovered) identity while the guard holds the package still |
| A-8 | The door's `WayFileStamp` check refusing Start (if O-2 keeps the plan's choice) | `:tracker` reads `way.json` at Start; iOS walks its captured copy |
| A-9 | Start waiting for a package operation in flight | Same reason; iOS neither waits nor refuses |
| A-10 | `retireMany` bumping `WayStore.deletions` | Android's file-change invalidation (Stage 21-1); no iOS counterpart, no visible behaviour of its own |
| A-11 | `list()` skipping `pilgrimage:` ids before decoding; the footer counting stage folders by listing | Performance; the footer differs from iOS only for an undecodable stage `way.json` |

---

### iOS defects (matched as shipped)

None of these is filed yet (#98–#118 don't cover packages or the ledger). Android ports each exactly; group them into one or two themed issues ("pilgrimage packages and the ledger").

- **D-1. A kill mid-commit leaves a half install the marker can't repair.** Evidence: the commit's loop and file order (§3, `PilgrimagePackageManager.swift:408-425`), Update's post-commit steps (`:255-272`), and `remove`'s installed-count range (`:280-282`). What a walker sees: after a kill during a first download's commit, invisible orphan stages; during an Update's commit, the old release over partly new stage geometry (the route page's rows disagreeing with the stage opened); after it, a shrunk route's tail that no later Update or Remove reaches, a ledger never reconciled (no notice, no carried kilometres), and a Settings → Ways footer counting the extra stages. iOS's own device pass expects "nothing half-installed" (`docs/honor-slice-two-device-pass.md:47-48`). Severity: low to medium (a kill inside a commit of about a second, but the damage persists). Flow gap 5; O-1.
- **D-2. A failed Update commit removes the installed route, unexplained.** Evidence: `rollBack` (`:432-437`) and the `diskFull` copy. What a walker sees: on a nearly full phone, tapping Update makes the route vanish from the phone ("Download" again), with "not enough space on this phone to save these voices". Survey candidate 8 confirmed (its "route previews accumulate" half is P1's). Severity: low.
- **D-3. The route page's busy state doesn't cover stage taps, so a Replace can start during a download.** Evidence: `isBusy` (`PilgrimageRouteView.swift:308-312`), `open(index:)` (`:316-323`), `beginInstall`/`install` (`:328-354`), and `replace`'s marker handling (`PilgrimagePackageManager.swift:235-244`). What a walker sees: "the download didn't finish" while the first download carries on; and, if the app dies between the first Replace's commit and its old-route removal, two routes on the phone with `installed()` picking one by file-system order. Severity: low. Flow gap 10; C-6; O-3.
- **D-4. The package's disk-full line speaks of voices.** Evidence: `PilgrimageWayImporter.swift:20` borrowing `HonorImportReducer.swift:34`. What a walker sees: "not enough space on this phone to save these voices" for a route with no voices. Severity: low (copy). Also P1's copy table.
- **D-5. Both finish paths delete the checkpoint before linking and recording.** Evidence: `MainCoordinatorView.swift:97-103` then `:116-121`; `WalkSessionGuard+Recovery.swift:119-122`. What a walker sees: a kill in that window leaves the walk saved with no link (the summary loses its stage block) and no ledger entry, never retried. Severity: low (a narrow window). Related to pilgrim-ios #107 (crash-time links), not covered by it.
- **D-6. Past stage walks read today's package and the ledger's best.** Evidence: Update's in-place rewrite (`retireMany` keeps walked stages' `way.json`, the commit overwrites it), `record`'s last-wins `stoppedAtFrac` and max `kmWalked` (`PilgrimageLedger.swift:59-71`), and the summary's read (`HonorSummarySection.swift:48-53`). What a walker sees: an earlier summary's ghost line and "X of Y km" change after an Update or a longer re-walk; an unanchored re-walk's summary shows an earlier walk's kilometres. Survey candidate 3 confirmed from the store and ledger side (P5 owns the summary). Flow gap 8. Severity: low.
- **D-7. Ledger kilometres are position, and arrival credits the whole stage.** Evidence: `record`'s `completed ? km : km * frac` (`PilgrimageLedger.swift:67`). What a walker sees: joining mid-stage credits everything behind them; arriving credits the stage's full distance however little was walked; the catalog's "112 km walked" counts both. Survey candidate 4 confirmed. Severity: low.
- **D-8. An undecodable `ledger.json` is replaced by the next record.** Evidence: `load` (`:160-164`) returning nil on a decode failure and `record` (`:175-181`) starting fresh. What a walker sees: every recorded stage and the carried kilometres gone after one bad read (a format change between builds is the realistic cause). The ledger's form of pilgrim-ios #107 item 4. Severity: low.
- **D-9. The Settings → Ways footer miscounts.** Evidence: `all.count - ways.count` (`WaysListView.swift:97-101`) over every package-owned Way, named by the installed route. What a walker sees: "the <route> keeps its 7 stages on its route page" for a four-stage route, after walking three stages of a route they replaced. Severity: low (copy).
- **D-10. Kept walked stages pile up out of reach.** Evidence: `retireMany` keeps them (§1); `listable` hides them; nothing else deletes them. What a walker sees: nothing, and that is the problem: up to 2 MB per walked stage per replaced route, deletable by no screen and counted in no size total. Severity: low.
- **D-11. The walk guard leaves the commit and the post-commit steps open.** Evidence: the checks at `:150`, `:195`, `:227`, `:256`, `:275` and none after; the commit off the main actor. What a walker sees: a walk begun during a commit walks its captured Way while the package changes; its record then writes the old stage's name and distance into the ledger (last-wins), which the next Update can drop as a redraw. Also, the guard is "any walk screen", wanders included (survey candidate 9, note only). Severity: low.

Survey §5: candidates 3, 4, 8 and 9 confirmed above (as D-6, D-7, D-2, D-11). Candidate 7 (an installed route the index renames or drops has no page) is P4's and P1's; from this cluster's side, `testARenamedRouteLeavesItsLedgerReadableAndUnlisted` pins that its ledger stays readable and unlisted, and the Settings → Ways footer would still name the installed `route.json`.

---

### Proposed owner decisions

- **O-1. A launch reconciliation for a half-committed install (gap 5, D-1).** Options: (a) parity: port the marker exactly, call `installed()` at launch, repair nothing else, and file D-1 upstream; (b) add a launch step that retires stage Ways with no `route.json` and stage Ways at or above the installed count (walked ones kept), as a dated addition. **Recommend (a).** The window is about a second inside a commit, and (b) still can't repair an Update's mixed stages; if iOS fixes it, fold the fix in.
- **O-2. What `:tracker` walks when the package changes after the door (the plan's KTDs on the file stamp and on not staging).** Options: (a) the plan: read the package at Start and revival, refuse Start with the Gone copy when the door's `WayFileStamp` no longer matches, and make Start wait for an operation in flight (A-8, A-9); (b) stage the stage Way per walk at Start from the copy the door loaded, as own walks are staged (discarded at finalize, never promoted), so `:tracker` walks the captured Way exactly as iOS's view model does, revival included, with no refusal and no wait. **Recommend (b)**: it is the platform equivalent of iOS's in-memory capture, with no walker-visible difference, for one write of at most 2 MB per stage walk; the guard (A-1) is still needed for gap 2. If the owner keeps (a), the refusal needs its own copy: "This walk isn't here to follow anymore. Try another." is wrong for a stage that is still there, redrawn.
- **O-3. Replace's busy race (gap 10, D-3).** Options: (a) parity: write the marker, let the refused download delete it, test that, and file upstream; (b) the plan: check busy before writing the marker. **Recommend (a)**, per the house rule; fold in iOS's fix if it lands before the gate.

---

## P3. The stage in the walk engine: water ahead, the stage branches, the outcome, the reflection reply, the glance and the lexicon

| | |
|---|---|
| iOS pin | `7c200bf` (v2.0.0), PRs #84 (`517c160`) and #85 (`8018a55`) |
| Android HEAD | `0defff85` (branch `docs/stage21-2-plan`) |
| Feeds | U35 (water ahead, the stage session, Room schema 12, the water golden traces), U36 (the outcome the ledger records, origin −1, the reflection reply), U40 (the glance and the prompt lexicon) |
| Lenses | behavior, UI/visual, data, edge cases (all four on every owned file) |

**iOS read in full at `7c200bf`:** `Pilgrim/Models/Honor/HonorEngine.swift` (324), `HonorMomentTracker.swift` (146), `HonorTuning.swift` (33), `HonorPersistence.swift` (45), `Pilgrim/Models/Haptics/HapticManager.swift` (355), `Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift` (464), `ActiveWalkViewModel+Replies.swift` (89), `ActiveWalkViewModel+MarkPins.swift` (71, for `showMarkCaption` only; the pins are P5's), `Pilgrim/Models/Walk/WalkCheckpoint.swift` (49), `Pilgrim/Models/Walk/WalkMode.swift` (31), `PilgrimWidget/PilgrimWidgetLiveActivity.swift` (the Honor row, lines 186-264), and the tests `UnitTests/Honor/HonorMomentTrackerTests.swift` (210), `PilgrimageStageWalkTests.swift` (438), `PilgrimageStageWalkTests+Replies.swift` (112), `UnitTests/VoiceRecordingDiscardTests.swift` (48), `UnitTests/Honor/WalkModeTests.swift` (18), `UnitTests/PracticeLexiconTests.swift` (123). **Read in their stage parts:** `ActiveWalkViewModel.swift` (969: lines 88-200, 240-265, 380-440, 530-610), `VoiceRecordingManagement.swift` (lines 60-220), `UserPreferences.swift` (+1), `PromptAssembler.swift` (165-205), `ActivityContext.swift` (15-35), `PromptListView.swift` (226-247), `WayMomentHeader.swift` (`WayDistance`, 104-118), `PilgrimageLedger.swift` (the outcome and `record`, 1-188), `PilgrimagePackageManager.swift` (`installed()`, 84-140), `MainCoordinatorView.swift` (100-135, 285-295), `WalkSessionGuard.swift` and `+Recovery.swift` (the checkpoint's Honor half), `ActiveWalkView+Honor.swift` (the arrival card's reply wiring), `UnitTests/Honor/ActiveWalkHonorTests.swift` (the stage diff), `HonorJournalTests.swift` (the diff). Every shared file was also read as `git diff 517c160^1 8018a55`. iOS PR #91 (open) was read for its `HonorMomentTracker` diff only, to shape schema 12.

**Android compared at `0defff85`:** `P/domain/honor/HonorMomentTracker.kt`, `HonorEngine.kt`, `HonorTuning.kt`, `HonorPersistence.kt`, `Way.kt` (marks and stage); `P/walk/honor/HonorSession.kt`, `HonorSessionState.kt`, `HonorAudioPorts.kt`, `HonorFinalizer.kt`; `P/data/honor/HonorSessionEntity.kt`, `HonorDao.kt`, `WayStore.kt` (replies); `P/data/PilgrimDatabase.kt` and `app/schemas/` (1-11); `P/audio/honor/HonorHaptics.kt`, `P/audio/seek/SeekHaptics.kt`; `P/ui/walk/HonorWalkViewModel.kt`; `P/ui/honor/WayPlaceCardState.kt` (`WayRelation`, `HonorArrival`, `softTapCaptionMeters`); `P/walk/WalkStartRequest.kt`, `P/honor/BeginHonorWalk.kt`, `HonorWayChoice.kt`, `P/walk/WalkControllerImpl.kt` (the session row and `recordHonorArrival`); `P/honor/HonorReplies.kt`, `P/ui/walk/WalkViewModel.kt` (the reply sites), `P/walk/WalkLifecycleObserver.kt`; `P/core/prompt/ActivityContext.kt`, `PromptAssembler.kt`, `PromptsCoordinator.kt`, `P/honor/HonorWalkRecords.kt`; `P/service/WalkNotificationFactory.kt` (the glance); the golden harness `app/src/test/resources/honor/golden/` (README, `capture/`) and `T/domain/honor/HonorGoldenTraceTest.kt`.

**A probe** in an uncommitted scratch folder compiled iOS's engine at the pin with one stage trace and captured its water events (§17). The harness still works.

---

### 1. What a stage changes in the engine, in short

- **The engine gains one event, `.markAhead`, and one read, `isAnchoredOnWay`.** Nothing else in `HonorEngine` branches on a stage. The soft tap is switched off from outside (`softTapEnabled` false), and the companion is still computed but never drawn.
- **The tracker gains the water watcher.** It watches only `water` marks within 60 m of the line, fires each one once, at most one per 3,600 s of engine clock with the first free, and only within 300 m ahead along the line while the walker is on the Way. It ignores every gate.
- **The view model gains the outcome** (captured at teardown, nil if the engine never truly anchored), the water caption in the soft tap's slot for 20 s, the water haptic (foreground only on iOS), and the reflection reply under origin −1.
- **iOS persists none of the watcher's state.** The engine "Persists nothing"; a crash ends the walk, and recovery keeps only the outcome. Android's `:tracker` revives, so it must persist the fired set and the quiet-hour clock (an Android addition, like every other revived field).


---

### 2. The water watcher (`HonorMomentTracker`)

#### 2.1 Which marks are watched

```swift
        /// A water source `meters` ahead on the line.
        case markAhead(WayMark, meters: Double)
```
> Pilgrim/Models/Honor/HonorMomentTracker.swift:15-16@7c200bf

```swift
    private let marks: [WayMark]
    private var firedMarks: Set<String> = []
    /// Active seconds at the last water caption; nil means the first is free.
    private var lastMarkSeconds: TimeInterval?

    init(moments: [WayMoment], marks: [WayMark] = [], geometry: WayGeometry, voicesEnabled: Bool) {
// …
        // Only on-way water speaks: a fountain 250 m off the trail is a
        // detour, not a drink. Filtering once here keeps the per-fix scan to
        // the handful that could ever fire.
        self.marks = marks
            .filter { $0.kind == .water && $0.offLineMeters <= HonorTuning.onWayMeters }
            .sorted { $0.frac < $1.frac }
```
> Pilgrim/Models/Honor/HonorMomentTracker.swift:34-48@7c200bf

```swift
        self.moments = HonorMomentTracker(moments: way.moments, marks: way.marks ?? [],
                                          geometry: geometry, voicesEnabled: voicesEnabled)
```
> Pilgrim/Models/Honor/HonorEngine.swift:89-90@7c200bf

What iOS does:
- The engine hands the tracker `way.marks ?? []`. Own walks and shared Ways carry no marks, so their watched list is empty and the watcher returns at its first guard.
- Watched: `kind == .water` **and** `offLineMeters <= 60` (`HonorTuning.onWayMeters`, the same 60 m as the on-Way radius). `<=`: a mark exactly 60.0 m off the line is watched (the probe confirms: `w-after` at 60 m is in the watched list).
- Food, bed, transport, supply and medical marks are never watched. They are map pins only (P5).
- Sorted by `frac` with Swift's `<`, once, at init. No tiebreak. Swift's `sort` is stable in practice, so equal fracs keep the package's order.
- `voicesEnabled` has no effect on water: a walker who turned the Way's voices off still gets water.

**Edge cases for Android:**
- **Sort comparator.** Swift's `<` treats `-0.0` and `0.0` as equal. Kotlin's `sortedBy { it.frac }` uses `Double.compareTo`, which puts `-0.0` first. Use the comparator the moments already use (`HonorMomentTracker.kt:44-54@0defff85`), without the id tiebreak: `if (a.frac < b.frac) -1 else if (b.frac < a.frac) 1 else 0` in a stable sort. A `-0.0` frac passes the importer's `0…1` bound (P1), so this is reachable in principle, though only for two marks at the trailhead.
- **Duplicate mark ids.** `firedMarks` is keyed by id. Two watched marks with the same id: the first to fire silences the second for the walk. Android must key the same way. Whether the importer refuses duplicate ids is P1's to pin.
- **Live data.** The Shikoku stage at `../open-pilgrimages` has 24 marks and no water. The Camino Francés carries "a median of eight on-way sources per stage and 23 on its wettest day" (`HonorTuning.swift:29-31`). Mark ids look like `wp-osm-water-node<N>`, the same `wp-` prefix moment ids use, so mark ids must never share a key space with moment ids in Room (§12).

#### 2.2 The call, and where water sits in it

```swift
    mutating func update(
        location: CLLocationCoordinate2D,
        progressFrac: Double,
        gates: Gates,
        isStationary: Bool,
        activeSeconds: TimeInterval = 0,
        isOnWay: Bool = true
    ) -> [Action] {
```
> Pilgrim/Models/Honor/HonorMomentTracker.swift:53-60@7c200bf

```swift
        actions += waterAhead(progressFrac: progressFrac, activeSeconds: activeSeconds, isOnWay: isOnWay)
        actions += startNextIfPossible(gates: gates)
        return actions
```
> Pilgrim/Models/Honor/HonorMomentTracker.swift:91-93@7c200bf

```swift
        let stationary = location.speed >= 0 && location.speed < HonorTuning.stationarySpeed
        emit(moments.update(location: coordinate, progressFrac: progressFrac, gates: gates,
                            isStationary: stationary, activeSeconds: activeDuration, isOnWay: isOnWay))
```
> Pilgrim/Models/Honor/HonorEngine.swift:159-161@7c200bf

- The engine passes its own `activeDuration` (the last engine-clock value it was given, §3) and its own `isOnWay` (just set by `track` on this same fix).
- The defaults (`activeSeconds: 0`, `isOnWay: true`) exist for the tracker's older tests, which call `update` without them. Android keeps the same defaults, so the 12 ported tracker tests compile unchanged.
- Water runs **after** the reach loop and the voice drops, **before** the voice start. It never reads `gates`: water speaks while paused, sitting, recording, or under a whisper. Nothing in `gatesDidChange` or `voiceDidFinish` calls it, so water is evaluated **only on an accepted fix**, never on a tick or a gate change.

#### 2.3 The scan

```swift
    /// The nearest unfired water source the walker is about to reach. Never
    /// one already behind them, never off the way, and at most one an hour
    /// of walking — the marks skipped inside the quiet hour stay silent pins.
    private mutating func waterAhead(progressFrac: Double, activeSeconds: TimeInterval, isOnWay: Bool) -> [Action] {
        guard isOnWay, !marks.isEmpty, geometry.totalMeters > 0 else { return [] }
        if let last = lastMarkSeconds, activeSeconds - last < HonorTuning.markQuietSeconds { return [] }
        for mark in marks where !firedMarks.contains(mark.id) {
            let ahead = (mark.frac - progressFrac) * geometry.totalMeters
            guard ahead >= 0 else { continue }
            guard ahead <= HonorTuning.markAheadMeters else { break }
            firedMarks.insert(mark.id)
            lastMarkSeconds = activeSeconds
            return [.markAhead(mark, meters: ahead)]
        }
        return []
    }
```
> Pilgrim/Models/Honor/HonorMomentTracker.swift:124-139@7c200bf

```swift
    /// How far before an on-way water source the caption rises.
    static let markAheadMeters = 300.0
    /// The Camino Francés carries a median of eight on-way sources per stage
    /// and 23 on its wettest day; without this the caption would be the
    /// day's loudest voice.
    static let markQuietSeconds: TimeInterval = 3600
```
> Pilgrim/Models/Honor/HonorTuning.swift:27-32@7c200bf

The rules, exactly:
1. **On the Way only.** `isOnWay` false returns nothing. Off the Way, progress is stale; a mark is not announced from a guessed position. Before the anchor's first on-Way fix (the frac-0 fallback), `isOnWay` is false, so nothing fires while approaching.
2. **Degenerate Ways** (`totalMeters == 0`) and stages with no watched marks return nothing.
3. **The quiet hour.** If a caption has fired (`lastMarkSeconds != nil`) and `activeSeconds - last < 3600`, nothing fires. Strict `<`: exactly 3,600 s after the last caption a new one may fire. The first caption of the walk is free (`nil`).
4. **Unfired marks only,** in frac order. A fired mark is skipped by the `where`, wherever it lies, so a fired mark still ahead never blocks the next one.
5. **Ahead along the line:** `(mark.frac − progressFrac) × geometry.totalMeters`. This is the engine geometry's length (cumulative haversine, `WayGeometry.swift:15-28`) times the dataset's frac, not a distance to the mark's coordinate. Spec B lists it as distance call D10 (`docs/parity/2026-09-29-honor-own-walk-port.md`, line 3857), with no `CLLocation.distance` involved, so it has no cache allowance.
6. **Behind:** `ahead < 0` → `continue`. `ahead == 0` (standing exactly at the mark) fires "water in 0 m".
7. **Too far:** the first unfired mark with `ahead > 300` → `break`. `<=`: exactly 300 m fires. Since the list is sorted by frac, every later mark is farther still.
8. **One per call.** The first mark found fires and the function returns, so two marks inside 300 m never fire on one fix. The second then waits for the next quiet hour; if it is passed within that hour it never speaks.
9. **What persists:** `firedMarks.insert(id)` and `lastMarkSeconds = activeSeconds`, both before the action is returned. A mark is "fired" the moment its event exists; a caption that fails to render, or a haptic that doesn't play, never re-fires it.

**A mark skipped in the quiet hour.** It is not fired, so it stays eligible. Once the hour has passed, it speaks on the first on-Way fix where it is still between 0 and 300 m ahead, with the distance left at that fix (the probe's `w-late`: reached 300 m out inside the hour, spoke 133.85 m out once the hour ended, §17). If the walker passed it during the hour, it is behind and never speaks. This is the survey's "can speak later" rule, and it is the tests' "a skipped mark stays a silent pin" when it is passed.

#### 2.4 Android today

```kotlin
 * The stage-only water caption (`waterAhead`, the `marks` filter, and the
 * `markAhead` action) is not ported: only a pilgrimage stage carries
 * marks, so for own and shared Ways it always yields nothing. Its branch
 * point is between the drops and the start in [update]
 * (`HonorMomentTracker.swift:91@7c200bf`).
 */
class HonorMomentTracker(
    moments: List<WayMoment>,
    private val geometry: WayGeometry,
    private val voicesEnabled: Boolean,
    private val distance: HonorDistance = WGS84_HONOR_DISTANCE,
) {
```
> app/src/main/java/org/walktalkmeditate/pilgrim/domain/honor/HonorMomentTracker.kt:11-22@0defff85

```kotlin
    /** What a revival needs back: the moments reached, and the voices waiting, in queue order. */
    data class Snapshot(val reached: Set<String>, val queue: List<String>)
```
> app/src/main/java/org/walktalkmeditate/pilgrim/domain/honor/HonorMomentTracker.kt:41-42@0defff85

```kotlin
        actions += startNextIfPossible(gates)
        return actions
    }
```
> app/src/main/java/org/walktalkmeditate/pilgrim/domain/honor/HonorMomentTracker.kt:96-98@0defff85

The tunings are already ported (`HonorTuning.kt:31-38@0defff85`: `MARK_PIN_REFRESH_METERS`, `MARK_AHEAD_METERS`, `MARK_QUIET_SECONDS`). U35 adds:
- `marks: List<WayMark> = emptyList()` to the constructor, filtered and sorted as above.
- `activeSeconds: Double = 0.0, isOnWay: Boolean = true` to `update`, and `Action.MarkAhead(mark: WayMark, meters: Double)`.
- `waterAhead` between the drops and `startNextIfPossible`, line for line.
- `firedMarks` and `lastMarkSeconds` to `Snapshot`, and `restore` setting both. Restore keeps only ids among the watched marks, as it does for moments (`HonorMomentTracker.kt:123-136@0defff85`); an id the Way no longer watches can never fire anyway.
- Delete the KDoc paragraph that says water is not ported.

---

### 3. The quiet hour's clock

```swift
                let pauseDuration = pauseList.map { $0.duration }.reduce(0, +)
                let activeDuration = max(0, start.distance(to: Date()) - pauseDuration)
                self.activeDurationSeconds = activeDuration
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift:544-546@7c200bf

```swift
        activeDuration.receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.updateActiveDuration($0) }.store(in: &cancellables)
```
> Pilgrim/Models/Honor/HonorEngine.swift:106-107@7c200bf

The quiet hour counts **engine seconds**, the same clock as the companion and `yourSeconds`:
- **Sittings are in.** A meditation keeps the walk recording, and the clock subtracts only pauses. An hour that includes a 20-minute sitting is still an hour.
- **Pauses are out on Android only.** iOS has no reachable pause at the pin (spec B, `docs/parity/2026-09-29-honor-own-walk-port.md` line 201; pilgrim-ios #112 item 1, which also notes iOS's clock would keep counting through a pause in progress). Android's engine clock freezes during a pause (owner decision 1, `honorEngineSeconds`, `HonorSessionState.kt:82-96@0defff85`), so on Android a pause stretches the quiet hour in wall time. That follows from the existing decision; no new divergence.
- **The clock is a 1 Hz sample.** The timer ticks once a second (`ActiveWalkViewModel.swift:535`), and the engine reads its last tick at each fix. A fix can see a value up to about 1 s old. In the probe the hour ended at clock 3,649.4: the fix at t 4,240 s saw the 3,639.4 tick and stayed quiet, and the fix at t 4,250 s saw 3,649.4 and spoke (§17). Android's session already ticks at 1 Hz (`ENGINE_TICK_MILLIS`, `HonorSession.kt:808-809@0defff85`), and the golden harness drives ticks at a fixed phase, so traces can match exactly.
- **The Begin fix sees 0.** In iOS's first main-queue turn the replayed fix arrives before the clock's first value (the golden README's "The Begin fix is processed on the engine's default open gates"). So a water mark within 300 m of an on-Way Begin fires on the Begin fix with `lastMarkSeconds = 0`. Android's `begin(fix)` feeds the fix before `tick()` (`HonorSession.kt:448-457@0defff85`), which keeps this.
- **The clock survives a revival on Android.** It is derived from the walk's own start and pauses, not kept in the engine, so `lastMarkSeconds` stays comparable after a revival. Nothing needs re-basing.

---

### 4. The engine's other stage changes

```swift
    case softTap(offWayMeters: Double)
    case markAhead(mark: WayMark, meters: Double)
    case arrived(theirSeconds: Double, yourSeconds: Double)
```
> Pilgrim/Models/Honor/HonorEngine.swift:13-15@7c200bf

```swift
    /// Whether the walker ever actually joined the Way. Begin's frac-0
    /// fallback (nothing within 60 m) is an approach, not a joining, and a
    /// stage the walker never joined earns no ledger entry.
    var isAnchoredOnWay: Bool { startFrac != nil && !anchoredByFallback }
```
> Pilgrim/Models/Honor/HonorEngine.swift:42-45@7c200bf

```swift
    /// Internal, not private: tests confirm the view model computed the
    /// right value for a stage vs. an own walk without re-deriving it.
    let softTapEnabled: Bool
```
> Pilgrim/Models/Honor/HonorEngine.swift:55-57@7c200bf

```swift
            case .markAhead(let mark, let meters): subject.send(.markAhead(mark: mark, meters: meters))
```
> Pilgrim/Models/Honor/HonorEngine.swift:320@7c200bf

- `.markAhead` carries the whole `WayMark` and the unrounded metres ahead.
- `isAnchoredOnWay` is the only new read. Once true it stays true: `anchoredByFallback` is set only in `anchor(at:)`, which runs once (while `startFrac == nil`), and `reanchor` only clears it (`HonorEngine.swift:166-187`). So "anchored at teardown" and "ever anchored" are the same.
- `softTapEnabled` became `internal` for `testAStageWalksWithNoCompanionAndNoSoftTap`. Android's is already a public `val` (`HonorEngine.kt:56@0defff85`).
- Android has `isAnchoredOnWay` already (`HonorEngine.kt:87-88@0defff85`, same expression). U35 adds `HonorEngineEvent.MarkAhead(mark: WayMark, meters: Double)`, passes `way.marks ?: emptyList()` to the tracker, passes `activeSeconds = activeDuration, isOnWay = isOnWay` to `update`, maps `Action.MarkAhead` in `toEvent()`, and deletes the KDoc line "The stage-only water caption ... is not ported" (`HonorEngine.kt:51-52@0defff85`).

---

### 5. Event order within one fix

```swift
        if startFrac == nil { anchor(at: coordinate) }
        track(coordinate)
        distanceRemainingMeters = (1 - progressFrac) * geometry.totalMeters
        evaluateSoftTap()
        evaluateArrival(location)

        let stationary = location.speed >= 0 && location.speed < HonorTuning.stationarySpeed
        emit(moments.update(location: coordinate, progressFrac: progressFrac, gates: gates,
                            isStationary: stationary, activeSeconds: activeDuration, isOnWay: isOnWay))
```
> Pilgrim/Models/Honor/HonorEngine.swift:153-161@7c200bf

One accepted fix emits, in this order: `.softTap` (never on a stage), `.arrived`, every `.momentReached` (frac order, ties by id), the `.voiceDropped`s, at most one `.markAhead`, at most one `.voiceStart`. A stage has no voices, so in practice: arrival, places reached, then water. `.arrived` and `.markAhead` can share a fix only if a watched mark lies within 300 m of the end at arrival and the quiet hour allows it; both then run, arrival first.

iOS's sink handles each event as it is sent, synchronously, on main (`ActiveWalkViewModel+Honor.swift:84-86`). Android's engine returns the list in the same order (`HonorEngine.kt:210-227@0defff85`), and `planEvents` walks it in order (`HonorSession.kt:496-524@0defff85`). U35 adds one branch there.

---

### 6. What the walk does with `.markAhead`

#### 6.1 iOS

```swift
        case .markAhead(let mark, let meters):
            showMarkCaption(mark: mark, meters: meters)
            fireHonorHaptic(.honorWaterAhead)
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:204-206@7c200bf

```swift
    /// The water notice borrows the soft tap's slot — nothing new in the
    /// stats sheet — and retires itself the same way, generation-guarded so
    /// teardown makes this write a no-op.
    func showMarkCaption(mark: WayMark, meters: Double) {
        softTapCaption = "water in \(WayDistance.string(meters: max(0, meters.isFinite ? meters : 0)))"
        let generation = honorGeneration
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.softTapCaptionSeconds) { [weak self] in
            guard let self, self.honorGeneration == generation else { return }
            self.softTapCaption = nil
        }
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+MarkPins.swift:60-70@7c200bf

- The caption first, then the haptic. Nothing is written to the walk: no event, no waypoint, no card (`testWaterAheadBorrowsTheCaptionLineAndNothingElse` asserts `honorCards` stays empty).
- The `mark` argument is unused. Only the metres reach the screen; the mark's `name` is never shown.
- The fired set and the quiet clock were already updated inside the tracker before the event was sent (§2.3, rule 9). iOS has no other persistence.

#### 6.2 Android: persist, then ritual

Android's session commits every engine call's state and the rows its events change in one transaction, then performs the rituals (`HonorSession.kt:82-86,653-675@0defff85`). For water, U35 adds:

```kotlin
                    is HonorEngineEvent.SoftTap -> plan.rituals += Ritual.SoftTapHaptic
```
> app/src/main/java/org/walktalkmeditate/pilgrim/walk/honor/HonorSession.kt:517@0defff85

- a `HonorEngineEvent.MarkAhead` branch in `planEvents` that records the caption on the plan (mark id, metres, `plan.now`) and adds `Ritual.WaterHaptic`;
- the fired set and the quiet clock ride the engine snapshot (`snapshot.tracker`), so `toEngineState` and `HonorEngineState` gain them and `commitAndPerform`'s `writeEngine` comparison picks the change up;
- `perform` maps `Ritual.WaterHaptic` to `haptics.waterAhead()`.

The order is then: tracker state (fired set, quiet clock) and caption committed in the step's transaction, then the haptic. A refused commit ends the session before any haptic (`HonorSession.kt:666-669@0defff85`), so a haptic never plays for a caption Room doesn't hold. A `:tracker` death between the commit and the haptic loses the haptic but never repeats the caption, since the mark is already fired in Room. That is the plan's "Rows persist before the haptic".

The caption's metres are the engine's `ahead`, unrounded, as the event carries them; formatting happens in the UI (§7).

---

### 7. The water caption

#### 7.1 The words

```swift
    static func string(meters: Double, unit: UnitLength = UserPreferences.distanceMeasurementType.safeValue) -> String {
        let meters = max(0, meters)
        if unit == .miles {
            let miles = meters / 1609.344
            if miles < 0.1 { return "\(Int((meters * 3.28084).rounded())) ft" }
            return String(format: "%.1f mi", miles)
        }
        if meters < 1000 { return "\(Int(meters.rounded())) m" }
        return String(format: "%.1f km", meters / 1000)
    }
```
> Pilgrim/Scenes/Honor/WayMomentHeader.swift:108-117@7c200bf

"water in " + `WayDistance.string(meters:)`, in the walker's distance unit, read when the caption is built. The caption is a code literal, not a localized key. `ahead` is always 0…300 m, so:

| Unit | Range of `ahead` | Shown | Rounding |
|---|---|---|---|
| metric | 0 ≤ m < 1000 (all of 0…300) | "water in 280 m"; "water in 0 m" at the mark; "water in 300 m" from 299.5 | `Int(meters.rounded())`: half away from zero |
| imperial | m < 160.9344 (miles < 0.1) | "water in 528 ft" at most | `Int((m × 3.28084).rounded())` |
| imperial | 160.9344 ≤ m ≤ 300 | "water in 0.1 mi" or "water in 0.2 mi" | `%.1f`, C `printf` rounding of the exact binary value |
| km | never (needs 1,000 m) | n/a | n/a |

The `max(0, …)` and `isFinite` guards never act, since `ahead` is finite and ≥ 0 by the scan. A non-finite input would read "water in 0 m".

**Android:** `WayRelation.distance(meters, units)` (`WayPlaceCardState.kt:268-325@0defff85`) is the `WayDistance` port and is the formatter to use. One last-digit difference exists at an exact tie of the decimal shortest form: Java's `%.1f` rounds the shortest decimal string HALF_UP, C rounds the exact binary value. Measured in the probe (a scratch probe, `fmt.swift`, not committed, `Fmt.java`): at exactly 241.4016 m (0.15 mi as a double), iOS reads "0.1 mi" and Android "0.2 mi". It is one double in a continuum, so it can't matter in practice; if U39 wants it exact, `BigDecimal(miles).setScale(1, RoundingMode.HALF_EVEN)` on the exact binary value matches C. It affects the card subline equally (spec E).

#### 7.2 Its life

```swift
    /// Not private: the water notice borrows this slot, so it borrows this life.
    static let softTapCaptionSeconds: TimeInterval = 20
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:457-458@7c200bf

```swift
    /// The soft tap's only mark on screen, in place of the minimized bar's
    /// third stat. Nil whenever the walker is on the Way — or always, when
    /// the soft-tap preference is off.
    @Published var softTapCaption: String?
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift:114-117@7c200bf

- **20 s from firing**, on a main-queue `asyncAfter`. iOS's timer runs in the background too (the walk keeps the app running), so a walker who unlocks after 20 s sees no caption.
- **What replaces it:** `softTapCaption = nil`, and the minimized bar's third stat returns (P5 draws the slot).
- **Teardown** (`stop()` or `cancel()`) sets `softTapCaption = nil` and bumps the generation, so a pending timer writes nothing (`ActiveWalkViewModel+Honor.swift:134,157`).
- **Two close together.** Each timer checks only the generation, never which caption it set. If a second caption landed inside the first's 20 s, it would overwrite the first and then be cleared early by the first's timer (spec B §12.5 says the same). On a stage this can't happen at the pin: the soft tap is off on a stage (§9), and two water captions are at least 3,600 engine seconds apart. On an own or shared Way water never fires. So the shared slot holds one caption at a time, and Android needn't reproduce the early clear.

#### 7.3 Android: the caption from Room

The UI process draws from Room, so the caption must be a persisted fact, not an event:
- **State** (schema 12, §12): the firing mark's id, its metres, and the wall-clock time it fired. The session writes all three in the step that fires it, before the haptic (§6.2).
- **Show** "water in <WayRelation.distance(meters)>" while `0 ≤ now − firedAt < 20,000 ms`, then nothing. Clamp to that window so a wall clock set backwards can't stretch it past 20 s. `now` is the UI's `Clock`, the same wall clock the tracker stamps with.
- **A UI restart** within the 20 s shows what is left of it; after, nothing (the plan's integration test). **A `:tracker` revival** never replays it: the mark is in the restored fired set, so the engine can't fire it again, and the row's `firedAt` doesn't move.
- **Units** are the walker's at display time on Android; iOS reads them when the caption is built. A unit change inside 20 s is the only case where they differ. Recommend reading at display, a dated equivalent too small to matter, or snapshotting the formatted string's unit at firing if U39 prefers exactness.
- **The slot.** `HonorSheetStats.softTapMeters: Long?` (`HonorWalkViewModel.kt:191-199@0defff85`) carries only the soft tap's integer metres. U39 widens it to a caption that is either the soft tap's ("off the way · N m", always metres, `honor_soft_tap_caption`) or water's. They never coexist (§7.2), so "water when live, else the soft tap" is enough.
- **The 20 s constant** is iOS's `softTapCaptionSeconds`, already `SOFT_TAP_CAPTION_MILLIS = 20_000L` (`HonorWalkViewModel.kt:943-944@0defff85`). Reuse it, as iOS reuses its constant.
- Nothing about water reaches the notification or the lock-screen glance, on either platform (§14).

---

### 8. The water haptic

```swift
        case .honorWaterAhead:
            // One tap at the whisper's intensity: a notice, not an alert.
            if !Self.playHonorWaterAhead() {
                let generator = UIImpactFeedbackGenerator(style: .soft)
                generator.prepare()
                generator.impactOccurred()
            }
```
> Pilgrim/Models/Haptics/HapticManager.swift:217-223@7c200bf

```swift
    /// `playWhisperProximity`'s single event: same softness and roundness,
    /// once instead of three times.
    private static func playHonorWaterAhead() -> Bool {
        let soft = CHHapticEventParameter(parameterID: .hapticIntensity, value: 0.4)
        let round = CHHapticEventParameter(parameterID: .hapticSharpness, value: 0.2)
        return HapticEngineHost.shared.play(
            [CHHapticEvent(eventType: .hapticTransient, parameters: [soft, round], relativeTime: 0)])
    }
```
> Pilgrim/Models/Haptics/HapticManager.swift:241-248@7c200bf

```swift
    /// Haptics only render in the foreground; the gate lives here so event
    /// routing can stay in the view model.
    var isAppActive: () -> Bool = { UIApplication.shared.applicationState == .active }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:9-11@7c200bf

```swift
    private func fireHonorHaptic(_ pattern: HapticPattern) {
        guard honorSenses.isAppActive() else { return }
        pattern.fire()
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:460-463@7c200bf

- **The pattern:** one Core Haptics transient, intensity 0.4, sharpness 0.2, at time 0. If the engine can't play it, a UIKit soft impact.
- **The gate:** only while the app is `.active`. With the screen locked or the app in the background (the pocket), iOS drops it, along with every other Honor haptic. The caption still shows in that case, for anyone who looks within 20 s.
- **No sound.** Water makes no sound and doesn't duck anything.

**Android (R6's existing deliberate addition):** fire in the pocket, as every Honor and Seek haptic already does (`HonorHaptics.kt:21-23@0defff85`, `SeekHaptics.kt:42-48@0defff85`). The house mapping for an iOS soft transient is `PRIMITIVE_TICK` at scale = iOS intensity, with a 30 ms one-shot at `amplitudeFor(scale)` where primitives are missing. Seek's arrival already plays this exact tap as its first step:

```kotlin
        // iOS `playSeekArrival` @c1745e8: (0, 0.4), (0.16, 0.55), (0.34, 0.7).
        const val ARRIVAL_SCALE_FIRST = 0.4f
```
> app/src/main/java/org/walktalkmeditate/pilgrim/audio/seek/SeekHaptics.kt:180-181@0defff85

So U35 adds `HonorHapticsPort.waterAhead()` and `HonorHaptics.waterAhead()`: one `VibrationEffect.Composition.PRIMITIVE_TICK` at `0.4f` (a named `WATER_SCALE`, citing `HapticManager.swift:241-248`), through the existing `impact`-style path with its fallback (`HonorHaptics.kt:37-58@0defff85`). iOS's fallback (UIKit soft impact) maps to the same tick at 0.4 (`SOFT_SCALE`), so one Android path covers both of iOS's.

---

### 9. No soft tap and no companion on a stage

```swift
        let engine = HonorEngine(
            way: way,
            // A stage has no other walker to be off the way *from*; the soft
            // tap and the companion dot are both about someone else.
            softTapEnabled: UserPreferences.honorSoftTapEnabled.value && !way.isPilgrimageStage,
            voicesEnabled: UserPreferences.honorVoicesEnabled.value && UserPreferences.soundsEnabled.value
        )
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:53-59@7c200bf

```swift
    /// A stage has no companion — its clock is synthesized so the engine
    /// works unchanged, and nothing draws it.
    var companionCoordinate: CLLocationCoordinate2D? {
        guard way?.isPilgrimageStage != true else { return nil }
        return honorEngine.map { $0.geometry.coordinate(atFrac: $0.companionFrac) }
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:115-120@7c200bf

```swift
    /// A Way that came from a downloaded route. Honor speaks differently
    /// about one: no other walker, so no companion, no soft tap, no date.
    var isPilgrimageStage: Bool { stage != nil }
```
> Pilgrim/Models/Honor/Way.swift:215-217@7c200bf

The exact conditions:
- **Soft tap:** `honorSoftTapEnabled && !way.isPilgrimageStage`, read once when the engine is built at Start (`startRecording` → `startHonorEngineIfNeeded`). The preference has no switch in the app, so the soft tap is off for every walker anyway (pilgrim-ios #109 item 8); the stage clause still has to be ported, since iOS's test sets the preference.
- **Companion:** the engine still computes `companionFrac` on every tick (`HonorEngine.swift:124-131`), from the stage's synthesized `t` values. Only the coordinate the map reads is nil on a stage. The arrival event still carries `theirSeconds`/`yourSeconds` from that clock, and the link still stores them (`ActiveWalkViewModel+Honor.swift:20-26`); the summary ignores them on a stage (P5).
- `isPilgrimageStage` is `stage != nil`, not the source. Every package Way has a stage (the importer requires it, P1), and no other source carries one, so `HonorSourceKind.PILGRIMAGE` and `way.isPilgrimageStage` agree in practice. Android should still test `way.isPilgrimageStage` where it has the Way, as iOS does.

**Android at `0defff85`:**

```kotlin
        fun atStart(
            honorVoicesEnabled: Boolean,
            soundsEnabled: Boolean,
            honorSoftTapEnabled: Boolean = false,
        ) = HonorSettings(
            voicesEnabled = honorVoicesEnabled && soundsEnabled,
            softTapEnabled = honorSoftTapEnabled,
        )
```
> app/src/main/java/org/walktalkmeditate/pilgrim/walk/WalkStartRequest.kt:48-55@0defff85

```kotlin
        val walk = walkController.startWalk(
            WalkStartRequest(
                intention = request.intention,
                mode = WalkMode.Honor,
                walkUuid = walkUuid,
                honor = HonorStart(wayId = way.id, settings = request.settings),
            ),
        )
```
> app/src/main/java/org/walktalkmeditate/pilgrim/honor/BeginHonorWalk.kt:147-154@0defff85

```kotlin
            val session = current?.session?.takeIf { it.softTapEnabled && !current.loaded.way.isPilgrimageStage }
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/HonorWalkViewModel.kt:787@0defff85

```kotlin
            if (live.loaded.way.isPilgrimageStage) return@map null
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/HonorWalkViewModel.kt:633@0defff85

Every place Android must apply `&& !isPilgrimageStage`:
1. **The frozen setting (the one that matters).** `HonorSettings.atStart` runs in `WalkViewModel.startHonorWalk` before the Way is loaded (`WalkViewModel.kt:1492-1495@0defff85`), so it can't see the stage. Apply the clause in `BeginHonorWalk.begin`, where the loaded `way` is in hand: `HonorStart(wayId = way.id, settings = request.settings.copy(softTapEnabled = request.settings.softTapEnabled && !way.isPilgrimageStage))`, or give `atStart` an `isPilgrimageStage` parameter and call it there. The row then carries `soft_tap_enabled = 0` (`WalkControllerImpl.kt:425-431@0defff85`), and `HonorSession.prepare` builds the engine from it (`HonorSession.kt:239-244@0defff85`), so the tracker never emits `SoftTap` on a stage. This is what the plan's "`HonorSettings.atStart` lacks iOS's `&& !isPilgrimageStage`" names; the fix site is `BeginHonorWalk`, not `WalkStartRequest.kt`.
2. **The caption.** Already guarded (`HonorWalkViewModel.kt:787`). Keep it: it costs nothing and covers a row written before the fix.
3. **The companion.** Already null on a stage (`HonorWalkViewModel.kt:630-636`). The map's dot reads `companion`, so nothing else is needed.
4. **Not to change:** the engine keeps computing `companionFrac` on a stage, and `Arrived` keeps its numbers. The session row keeps writing `companion_t0_seconds` and `anchor_active_seconds`. They feed the link's arrival numbers, which iOS also stores for a stage.

Tests to port: `testAStageWalksWithNoCompanionAndNoSoftTap` becomes a `BeginHonorWalk` test (settings with `softTapEnabled = true` and a stage Way → the request's `HonorStart.settings.softTapEnabled == false`) plus a `HonorWalkViewModel` test that `companion` stays null on a stage. `testAnOwnWalkWayKeepsItsCompanion` is the positive control with `stage = null`.

---

### 10. `HonorStageOutcome`: what the ledger is told

#### 10.1 The type and where it is captured

```swift
/// What the engine had to say about a stage when the walk ended. Captured
/// before teardown, because the engine is gone by the time the walk is saved.
struct HonorStageOutcome: Equatable {
    let progressFrac: Double
    let arrived: Bool
}
```
> Pilgrim/Models/Honor/PilgrimageLedger.swift:3-8@7c200bf

```swift
    func teardownHonor() {
        guard honorEngine != nil || wayVoicePlayer != nil else { return }
        if let engine = honorEngine, engine.isAnchoredOnWay {
            honorStageOutcome = HonorStageOutcome(progressFrac: engine.progressFrac,
                                                  arrived: engine.phase == .arrived)
        }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:128-133@7c200bf

```swift
    /// The engine's last word on this stage, captured in `teardownHonor()`
    /// and deliberately surviving it — the engine is gone by the time the
    /// snapshot reaches `onWalkCompleted`, and the ledger is written there.
    @Published var honorStageOutcome: HonorStageOutcome?
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift:105-108@7c200bf

```swift
    var honorCheckpointState: (wayId: String, outcome: HonorStageOutcome?)? {
        guard mode == .honor, let way else { return nil }
        guard let engine = honorEngine, engine.isAnchoredOnWay else { return (way.id, nil) }
        return (way.id, HonorStageOutcome(progressFrac: engine.progressFrac,
                                          arrived: engine.phase == .arrived))
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:166-171@7c200bf

The two fields, and where each comes from:

| Field | iOS source | Notes |
|---|---|---|
| `progressFrac` | `engine.progressFrac` | The **current** position on the Way at the last processed fix. Not `progressHighWater`, and not `walkedFrac`. It can be lower than the best reached: walking back within 60 m moves it back up to 0.02 per fix, and a failed forward re-acquire's global search can move it to an earlier leg (spec B lines 2992, 3061). |
| `arrived` | `engine.phase == .arrived` | Set once by the arrival debounce; the same moment the `.arrived` event wrote `HONOR_ARRIVAL` and the arrival waypoint. Arrival needs `startFrac` and real progress, so `arrived` implies anchored. |

**The nil rule.** No outcome unless `engine.isAnchoredOnWay` (`startFrac != nil && !anchoredByFallback`):
- no accepted fix ever (`startFrac == nil`): nil;
- Begin found nothing within 60 m, and no later fix ever came within 60 m of the line inside the window, and no re-acquire found the Way (`anchoredByFallback` still true): nil;
- anchored at Begin by `lowestFrac(within: 60)`, or re-anchored by the first on-Way fix or a re-acquire: an outcome, whatever happened after.

**The 60 m anchor and the frac-0 fallback** (`HonorEngine.swift:166-187`, spec B §5-§6): the first accepted fix anchors at the lowest frac within 60 m of it; with none, the engine anchors at frac 0 with `anchoredByFallback = true`, and the first fix within 60 m of the line in the tracking window (or a successful re-acquire after 120 s off the Way) re-anchors there and clears the flag. "continue from where you stopped" is copy only: the engine always anchors wherever the walker first joins.

**Where it is captured.** `teardownHonor()` runs from both `stop()` and `cancel()`. On `stop()` the walk is saved and the outcome goes to the ledger (P2). On `cancel()` it is computed and never used. A checkpoint carries the same rule, read at the checkpoint's own cadence (§10.3).

#### 10.2 The answer to the plan's deferred question

Android's session row is the engine's persisted state, rewritten in every step that changes it (`HonorSession.kt:653-675@0defff85`):

```kotlin
    /** The anchor; null until Begin's first fix anchors the walker. */
    @ColumnInfo(name = "start_frac")
    val startFrac: Double? = null,
    @ColumnInfo(name = "anchored_by_fallback")
    val anchoredByFallback: Boolean = false,
```
> app/src/main/java/org/walktalkmeditate/pilgrim/data/honor/HonorSessionEntity.kt:58-62@0defff85

```kotlin
    @ColumnInfo(name = "progress_frac")
    val progressFrac: Double = 0.0,
    @ColumnInfo(name = "progress_high_water")
    val progressHighWater: Double = 0.0,
    @ColumnInfo(name = "walked_frac")
    val walkedFrac: Double = 0.0,
```
> app/src/main/java/org/walktalkmeditate/pilgrim/data/honor/HonorSessionEntity.kt:67-72@0defff85

```kotlin
    /** Begin's frac-0 fallback (nothing within 60 m) is an approach, not a joining. */
    val isAnchoredOnWay: Boolean get() = startFrac != null && !anchoredByFallback
```
> app/src/main/java/org/walktalkmeditate/pilgrim/domain/honor/HonorEngine.kt:87-88@0defff85

**The mapping:**

| `HonorStageOutcome` | Android live row | |
|---|---|---|
| exists at all | `start_frac IS NOT NULL AND anchored_by_fallback = 0` | the "never anchored" test, the same expression as `HonorEngine.isAnchoredOnWay` |
| `progressFrac` | `progress_frac` | the engine's current position, as committed after the last processed fix |
| `arrived` | `phase = ARRIVED` | written only by arrival's compare-and-set (`HonorSessionState.kt:144`), in the transaction that writes `HONOR_ARRIVAL` |
| (not read) | `progress_high_water` | no stage surface reads it |
| (not read by the outcome) | `walked_frac` | read only by the arrival card's walked kilometres, `distanceWalkedMeters = walkedFrac × totalMeters` (P5; see §12, `arrival_walked_meters`) |

So U36 adds one pure function next to `engineSnapshot`, e.g. `HonorSessionEntity.stageOutcome(): HonorStageOutcome? = if (startFrac == null || anchoredByFallback) null else HonorStageOutcome(progressFrac, phase == HonorPhase.ARRIVED)`, and the ledger record (clean and recovered) reads it from the row before the live rows are deleted (P2's ordering).

**Which test decides "never anchored":** port `testTheEngineReportsWhetherItEverAnchoredOnTheWay` (§19) at the engine, and add its row-level twin: a `HonorSessionTest` that drives a stage begun 1 km north of the line (`lat 0.01`) and asserts the row reads `anchored_by_fallback = 1` and `stageOutcome() == null`, then one fix on the line and `stageOutcome() != null`. `testTheOutcomeSurvivesTeardown` (`progressFrac ≈ 0.3 ± 0.02`, `arrived == false`) and `testNoOutcomeWithoutAnAnchor` become row tests the same way.

**Equivalence.** iOS reads the engine at teardown, after the last fix it processed. Android reads the row, committed after the last fix `:tracker` processed. A fix still queued in the session's channel when the walk leaves progress is dropped on Android (`followWalk`, `HonorSession.kt:414-423@0defff85`) as iOS drops fixes after teardown. A revived session restores `start_frac`, `anchored_by_fallback` and `progress_frac` exactly (`engineSnapshot`, `HonorSessionState.kt:123-142@0defff85`), so the outcome survives a revival.

#### 10.3 The checkpoint's outcome (recovery)

```swift
    /// Both halves are written together from the same engine read, so either
    /// one missing means no stage was joined and no ledger entry was earned.
    var honorOutcome: HonorStageOutcome? {
        guard let honorProgressFrac, let honorArrived else { return nil }
        return HonorStageOutcome(progressFrac: honorProgressFrac, arrived: honorArrived)
    }
```
> Pilgrim/Models/Walk/WalkCheckpoint.swift:27-32@7c200bf

- iOS writes the outcome into each checkpoint (schema version 2; version 1 still recovers, as a plain walk) at the checkpoint's tiered cadence. A recovered walk records the last checkpoint's outcome, dated `checkpointDate`. Anything after the last checkpoint is lost.
- Android has no checkpoint. The live row is written per fix, so a recovered walk's outcome is fresher than iOS's: a dated platform equivalent at the gate (the plan already says so). The recovered record's date and the ledger write itself are P2's.

---

### 11. Revival: what `:tracker` must restore

iOS never revives a walk. The engine "Persists nothing" (`HonorEngine.swift:18-20`), and a crash ends the walk at its last checkpoint. Everything below is Android's, an extension of Stage 21-1's revival (spec B line 2728 already lists `firedMarks` and `lastMarkSeconds` as part of "the complete engine state to persist for a revival").

```kotlin
        val revived = session.gateGeneration > 0
        val rows = dao.getMomentStates(walkId).associateByTo(mutableMapOf()) { it.momentId }
        val engine = HonorEngine(
            way = way,
            softTapEnabled = session.softTapEnabled,
            voicesEnabled = session.voicesEnabled,
            clock = clock,
        )
        if (revived) engine.restore(session.engineSnapshot(rows.values))
```
> app/src/main/java/org/walktalkmeditate/pilgrim/walk/honor/HonorSession.kt:237-245@0defff85

**A revived stage session must restore:**
1. **The fired set.** Without it, every on-Way water mark within 300 m ahead fires again: a repeated caption and haptic.
2. **The quiet clock** (`lastMarkSeconds`, nullable). Without it, the first fix after a revival gets a free caption inside the quiet hour: an extra caption. With `null` restored as `null`, a walk that has heard no water still gets its free first.
3. **The caption's fields stay as they are.** The session doesn't re-emit or re-stamp them; the UI decides from `firedAt` whether any of the 20 s is left (§7.3).
4. **Everything Stage 21-1 already restores** (anchor, progress, high water, credit, the arrival debounce, the tracker's reached set and queue), which the outcome reads (§10.2).

**Neither repeats nor loses:** a mark fired in the dead process is in Room before its haptic (§6.2), so the revival can't fire it again. A mark the dead process never reached is unfired, so the revived engine still finds it. The only loss is a haptic whose commit landed and whose `vibrate` the death pre-empted.

**The Way under a revival.** `prepare` re-reads the stage's `way.json` at every revival (`HonorSession.kt:233-235@0defff85`). Restored fracs and mark ids are only meaningful against the same geometry, so a revival depends on the package not changing under a live walk. The plan's wide walk guard (its "live Honor session rows exist" clause) gives that; U35 needs no file stamp of its own. If the Way fails to load, `prepare` refuses the session, as for any Way.

**A cached `:tracker`'s second walk.** `start` replaces the run and builds a new engine from the new walk's row (`HonorSession.kt:155-179@0defff85`), and `revived` keys on that row's `gate_generation`. So a second stage walk in the same process starts with an empty fired set and a free first caption, even on the same stage. Port this as the plan's second-walk test: walk A fires `w1`, finishes; walk B on the same Way in the same session object fires `w1` again.

---

### 12. Room schema 12: what the stage session must persist

```kotlin
 * The engine's `activeDuration` is not here: it is the walk's elapsed
 * time minus pauses, which the walk's own events already give. Nor are
 * the published `isOnWay`, `offWayMeters`, `distanceRemainingMeters`, and
 * `companionFrac`, which the next fix or tick recomputes. The stage-only
 * water marks (`firedMarks`, `lastMarkSeconds`) wait for Stage 21-2.
```
> app/src/main/java/org/walktalkmeditate/pilgrim/data/honor/HonorSessionEntity.kt:23-27@0defff85

Schema 11 is frozen (it has run on the owner's phone). Every field below is an **additive column on `honor_sessions`**, nullable or with a default, so `MIGRATION_11_12` is `ALTER TABLE ... ADD COLUMN` only and an 11 row reads as "no stage, nothing fired". U35 picks the names; these are suggestions.

| Field | Type | Written | Read by | Why |
|---|---|---|---|---|
| `fired_marks` | `TEXT NOT NULL DEFAULT '[]'` (a JSON array of mark ids, in firing order, through a small codec like `seek_sessions.chain`) | in the step that fires a mark | revival | §11, item 1. Not rows in `honor_moment_states`: mark ids share the `wp-` prefix with moment ids, and every reader of those rows treats them as moments (`planTracker`, `DrawnWay.of`, the arrival counts). A set of at most a few dozen ids fits a column. |
| `last_mark_seconds` | `REAL` (nullable) | same step | revival | §11, item 2. Engine seconds; `NULL` means the first caption is free. |
| `water_caption_mark_id` | `TEXT` (nullable) | same step | UI | Which mark spoke last. Lets the UI and tests tell two captions apart; not shown. |
| `water_caption_meters` | `REAL` (nullable) | same step | UI | The engine's `ahead`, unrounded (§7.1 formats it). |
| `water_caption_at` | `INTEGER` (nullable), epoch ms | same step (`plan.now`) | UI | The 20 s window (§7.3). |
| `stage_route_id` | `TEXT` (nullable) | session row insert at Start | ledger record | The ledger's file and key. |
| `stage_index` | `INTEGER` (nullable) | same | ledger record | Zero-based, as `WayStage.index`. |
| `stage_name` | `TEXT` (nullable) | same | ledger record | The entry's identity across an Update. |
| `stage_distance_km` | `REAL` (nullable) | same | ledger record | `WayStage.distanceKm`; the entry's km and identity. |
| `arrival_walked_meters` | `REAL` (nullable) | arrival's compare-and-set, beside `arrival_their_seconds` | the arrival card (P5) | iOS freezes `engine.distanceWalkedMeters` into the card at the arrival event (`ActiveWalkViewModel+Honor.swift:253-259`). The live `walked_frac` keeps growing if the walker goes on past 0.9 toward the end, so the card can't recompute it from the row later. |

Notes:
- **The stage identity at Start.** iOS's clean finish records the `WayStage` of the Way captured at Begin (`MainCoordinatorView.swift:113-121`: "this `way` was captured at Begin"), so a copy taken when the session row is written is the same thing. Write it in `WalkControllerImpl.honorSessionRow` (`WalkControllerImpl.kt:425-431@0defff85`) from the `HonorStart` (extend `HonorStart` with the stage's four fields, filled in `BeginHonorWalk` from the loaded Way), or in `HonorSession.prepare`'s first transaction from the loaded Way. The first is simpler and lands before `:tracker` reads the file. iOS's **recovery** reads the stage from the store's current `way.json` at recovery time (`WalkSessionGuard+Recovery.swift:138-144`); with the walk guard holding the package still under a live walk, the two give the same stage. Record that at the gate.
- `stage_count` isn't needed: the ledger record reads only route id, index, name and kilometres (`PilgrimageLedger.swift:133-137,175-181`).
- **The caption columns and the haptic** are written in the same transaction as `fired_marks` and `last_mark_seconds`, then the haptic plays (§6.2).
- **Defaults must match.** A `NOT NULL DEFAULT` column added by `ALTER TABLE` needs the same `@ColumnInfo(defaultValue = ...)` on the entity, or Room's schema check fails on open.
- **The migration.** `MIGRATION_11_12` appended to `MIGRATIONS`, `@Database(version = 12)`, and `app/schemas/.../12.json` as the only new schema file. Test 11→12 through Room's identity check and the full 8→12 chain (flow gap 14: the migration ships unflagged, so every user runs it). `git status app/schemas` after the build: `11.json` byte-identical.
- **A revival from an 11 row** can't happen: no device has a live stage session at schema 11. An own or shared walk revived across the upgrade reads `[]` and `NULL`, which is right.

**Shaping for iOS PR #91** (open, "a temple ahead says it stamps until five"). Its tracker diff renames `lastMarkSeconds` to `lastNoticeSeconds`, one clock shared by water and a new temple-stamp notice, and adds `firedStamps: Set<String>` and `case stampAhead(WayMoment, meters: Double)`. If #91 is still headed for merge when U35 starts, name the clock column for what it will be (`last_notice_seconds`), add `fired_stamps TEXT NOT NULL DEFAULT '[]'`, and make the caption columns kind-neutral (`notice_kind`, `notice_item_id`, `notice_meters`, `notice_at`), so #91 costs no schema 13. Its full annex is outside P3's files.

---

### 13. The reflection reply: origin −1

#### 13.1 The reserved origin and its moment

```swift
    /// Replies are keyed by the `n` in a `voice-n` id. A stage has no
    /// voices, so its arrival reflection is filed under a reserved index no
    /// `voice-n` can ever produce.
    static let stageReflectionOrigin = -1
    static let stageReflectionMomentID = "stage-reflection"

    /// The moment the reply path records against: not a moment of the Way,
    /// but the stage's end place wearing a moment's shape so `replyHere`,
    /// `originIndex(of:)`, and `existingReplyURL(for:)` all work unchanged.
    static func stageReflectionMoment(for stage: WayStage) -> WayMoment {
        WayMoment(id: stageReflectionMomentID, frac: 1, at: stage.end.at,
                  kind: .waypoint(label: stage.end.name, icon: arrivalWaypointIcon))
    }
```
> Pilgrim/Models/Honor/HonorPersistence.swift:12-24@7c200bf

```swift
    static func originIndex(of moment: WayMoment) -> Int? {
        if moment.id == HonorPersistence.stageReflectionMomentID { return HonorPersistence.stageReflectionOrigin }
        guard moment.id.hasPrefix(voiceIDPrefix) else { return nil }
        return Int(moment.id.dropFirst(voiceIDPrefix.count))
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Replies.swift:84-88@7c200bf

```swift
    /// Starts a reply to the stage's closing line, at the stage's end place.
    func replyToStageReflection() {
        guard let stage = way?.stage else { return }
        replyHere(to: HonorPersistence.stageReflectionMoment(for: stage))
    }

    /// The walker's reply to this stage's reflection, from this walk or an
    /// earlier one. Nil when the recording is gone.
    func stageReflectionReplyURL() -> URL? {
        guard let stage = way?.stage else { return nil }
        return existingReplyURL(for: HonorPersistence.stageReflectionMoment(for: stage))
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Replies.swift:52-63@7c200bf

- The reply is filed in the stage Way's `replies.json` under key `"-1"` (`setReply` writes `String(n)`; `replies(for:)` reads keys with `Int(key)`, which accepts `"-1"`), mapping to the recording's `fileRelativePath`. One reply per stage Way: a later reply replaces the earlier.
- The moment is synthetic. Its `at` (`stage.end.at`), `frac` 1, label (`stage.end.name`) and the arrival icon are never read on the reply path at the pin; only the id matters. `testTheReflectionIsFiledUnderTheReservedOrigin` pins `moment.at == stage.end.at`, so port the factory whole.
- `originIndex` maps `"stage-reflection"` to −1 **on any Way**, not only a stage. `replyToStageReflection` and `stageReflectionReplyURL` do the stage check (`way?.stage`).
- `"voice-03"` still parses to 3 and `"voice-x"` to nil, on both platforms (`Int(_:)`, `toIntOrNull`). Both also parse `"voice--1"` to −1, so a shared Way carrying a moment with that id would file its replies under the reflection's key. Own walks never mint such an id; it is a curiosity, not a defect to file.

#### 13.2 Starting it, and a reply still recording at stop

```swift
    func replyHere(to voice: WayMoment) {
        pendingReplyOrigin = voice
        if !isRecordingVoice { toggleVoiceRecording() }
// …
        if !voiceRecordingManagement.isRecording {
            pendingReplyOrigin = nil
        }
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Replies.swift:20-32@7c200bf

```swift
    func stop() {
        teardownSeek()
        // A reply still recording here keeps its origin through teardown, and
        // the recorder is deliberately left running: only the pre-snapshot
        // flush inside `builder.setStatus(.ready)` below finalizes an
        // in-flight recording synchronously. Stopping the recorder here would
        // hand that commit to AVAudioRecorder's asynchronous delegate, which
        // lands after the walk is snapshotted — losing the audio entirely.
        teardownHonor()
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift:395-403@7c200bf

```swift
    private func bindCompletedRecordings() {
        completedRecordingsCancellable = builder.voiceRecordingsPublisher
            .receive(on: DispatchQueue.main)
            .sink { [weak self] recordings in
                guard let self else { return }
                let isNewRecording = recordings.count > self.completedRecordings.count
                self.completedRecordings = recordings
                self.completedRecordingCount = recordings.count
                if isNewRecording, let latest = recordings.last {
                    self.recordReplyIfPending(latestRecording: latest)
                }
            }
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift:593-605@7c200bf

- PR #84 moved `pendingReplyOrigin = nil` out of `teardownHonor()` and moved the recordings subscription out of `cancellables` (which `stop()` empties) into its own `completedRecordingsCancellable`, which lives as long as the view model. So the reply the arrival card invites, still recording when the walker presses stop, is flushed by `setStatus(.ready)`, reaches the sink after teardown, and is filed under −1. The same fix covers voice replies on any Way (`testAVoiceReplyStillRecordingWhenTheWalkEndsIsStillFiled`).
- `isRecordingReply` on the arrival card is `isRecordingVoice && pendingReplyOrigin?.id == "stage-reflection"` (`ActiveWalkView+Honor.swift`, P5).

#### 13.3 Discard

```swift
    /// A discarded walk files no reply, so the origin goes before the recorder
    /// does: discarding takes the partial file and the audio session with it,
    /// and a late commit must not be read as an answer to a Way.
    func discardPendingReply() {
        pendingReplyOrigin = nil
        voiceRecordingManagement.discardRecording()
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Replies.swift:9-15@7c200bf

```swift
    public func discardRecording() {
        guard isRecording else { return }
        stopMetering()
        isRecording = false
        recordingStartDate = nil
        audioRecorder?.delegate = nil
        audioRecorder?.stop()
        audioRecorder = nil
        commitRecording(successfully: false)
    }
```
> Pilgrim/Models/Walk/WalkBuilder/Components/VoiceRecordingManagement.swift:151-160@7c200bf

`cancel()` calls `discardPendingReply()` first, before the Seek and Honor teardowns (`ActiveWalkViewModel.swift:417-420`). The origin goes, then the open recording is stopped with its delegate detached, its partial file removed, and the "voiceRecording" audio-session consumer released. With no recording open it does nothing, and it never releases another consumer's session (`VoiceRecordingDiscardTests`).

#### 13.4 Android today, and what U36 changes

```kotlin
/**
 * The `n` of a `voice-n` moment, the index a reply is filed under (iOS
 * `originIndex(of:)`, `ActiveWalkViewModel+Replies.swift:79-88@7c200bf`).
 * The stage reflection's reserved index is stage-only.
 */
internal fun voiceOriginIndex(momentId: String): Int? =
    momentId.takeIf { it.startsWith(VOICE_ID_PREFIX) }?.removePrefix(VOICE_ID_PREFIX)?.toIntOrNull()
```
> app/src/main/java/org/walktalkmeditate/pilgrim/walk/honor/HonorSessionState.kt:112-118@0defff85

```kotlin
        private fun planReply(plan: Plan, momentId: String) {
            val moment = momentsById[momentId] ?: return
            val n = voiceOriginIndex(moment.id) ?: return
            val relativePath = wayStore.replies(way.id)[n] ?: return
```
> app/src/main/java/org/walktalkmeditate/pilgrim/walk/honor/HonorSession.kt:642-645@0defff85

```kotlin
                    // A reply still recording at walk end is filed now, as
                    // iOS's pre-snapshot flush hands it to the same listener.
                    honorReplies.fileIfPending(recording)
```
> app/src/main/java/org/walktalkmeditate/pilgrim/walk/WalkLifecycleObserver.kt:130-132@0defff85

```kotlin
        // A discarded walk files no reply (iOS `cancel()`); its take is dropped, not saved.
        honorReplies.clear()
        viewModelScope.launch { controller.discardWalk() }
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkViewModel.kt:1814-1816@0defff85

What Stage 21-1 already has: the origin lives in the UI-process singleton `HonorReplies` (not torn down by the walk's end); the walk-end auto-stop in `WalkLifecycleObserver` saves the take and files it; discard clears the origin before the controller purges the walk, and the observer's Idle path deletes the take (`handleVoiceStop(commitRow = false)`). So the "sink outliving stop" and "discard drops it" behaviors exist; they only need the −1 origin.

U36 changes:
1. **`voiceOriginIndex`:** return −1 for `"stage-reflection"` before the prefix test, on any Way, as iOS does. Add `HonorPersistence.STAGE_REFLECTION_ORIGIN = -1`, `STAGE_REFLECTION_MOMENT_ID = "stage-reflection"` and `stageReflectionMoment(stage)` (id, frac 1, `at = stage.end.at`, waypoint label `stage.end.name`, icon `ARRIVAL_WAYPOINT_ICON`). Today `fileIfPending` returns before its compare-and-set when the index is null (`HonorReplies.kt:83-87@0defff85`), so without this the reply would be saved as an ordinary recording and the origin would stay armed for the walk's next take.
2. **"reply here" on the arrival card** arms `HonorReplies` with `momentId = "stage-reflection"` through `WalkViewModel.replyHere(walkId, wayId, momentId)` (`WalkViewModel.kt:1720-1726@0defff85`). `arm` computes `listed = wayStore.load(wayId) != null`, which is true for an installed stage, so the reply files into the stage Way's own folder. No staging is involved (stage Ways are never staged).
3. **Playing it back in `:tracker`:** `planReply` looks the id up in `way.moments` and returns for `"stage-reflection"`, which isn't a moment of the Way. Add: if `momentId == STAGE_REFLECTION_MOMENT_ID && way.stage != null`, use n = −1 (iOS's `stageReflectionReplyURL` requires `way?.stage`). The rest of `planReply` is iOS's `playReply(url:)` unchanged.
4. **Filing date.** iOS files at the moment the recording completes, into `replies.json`, with no date; nothing is "filed at `stage.end.at`" in time. The reflection *moment* sits at the stage's end place. The plan's "files under origin −1 at `stage.end.at`" means that place, not a time.
5. **No ledger or session link.** The reply is a Way-folder file, not a Room row; it survives the walk's finalize, Replace and Remove (`retireMany` keeps a walked stage's `replies.json`, P2).

---

### 14. The lock-screen glance: no stage branch

```swift
    func currentHonorGlance() -> HonorGlanceState? {
        guard let engine = honorEngine else { return nil }
        return HonorGlanceState(
            distanceRemainingBucketMeters: SeekGlanceModel.distanceBucket(forMeters: engine.distanceRemainingMeters),
            isOnWay: engine.isOnWay, isArrived: engine.phase == .arrived)
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:432-437@7c200bf

```swift
            if let honor = context.state.honor {
                HStack(spacing: 6) {
                    Image(systemName: "signpost.right").font(.caption).foregroundColor(Self.stone)
                    Text(honor.isArrived ? "their way, walked"
                         : honor.isOnWay ? honorDistanceText(bucket: honor.distanceRemainingBucketMeters, imperial: context.attributes.isImperial)
                         : "off the way")
                        .font(.system(.caption, design: .serif)).foregroundColor(Self.ink)
                    Spacer()
                }
            }
```
> PilgrimWidget/PilgrimWidgetLiveActivity.swift:190-199@7c200bf

- Neither PR touched the glance or the widget. A stage walk's lock screen reads exactly as a shared walk's: "off the way" until the first fix within 60 m (and whenever off it), "almost there" / "~400 m to go" / "2 km+ to go" while on the Way, and **"their way, walked"** after arrival. On a stage, "their way" has no referent: a "their" iOS ships on a stage surface (§20).
- No water line reaches the glance.
- `HonorJournalTests` changed 3 lines in these PRs, all to pass `ledger: nil` to `HonorSummaryModel.summaryData` (the summary, P5). It has no glance case.

**Android** already matches, with no stage branch:

```kotlin
internal fun honorGlanceLine(glance: HonorGlanceState, units: UnitSystem): String {
    if (glance.isArrived) return "their way, walked"
    if (!glance.isOnWay) return "off the way"
```
> app/src/main/java/org/walktalkmeditate/pilgrim/service/WalkNotificationFactory.kt:164-166@0defff85

U40 changes nothing here. Its only task is a test that a stage walk's glance reads "their way, walked" at arrival (matched as shipped), so nobody "fixes" it alone.

---

### 15. The prompt lexicon's stage form

```swift
        case .honor:
            guard let story = context.honorStory else {
                return sharedWalkBaseText
            }
            return story.routeName == nil ? sharedWalkLexicon(story) : stageLexicon(story)
```
> Pilgrim/Models/Prompt/PromptAssembler.swift:170-174@7c200bf

```swift
    private static func stageLexicon(_ story: HonorStoryContext) -> String {
        var text = "**About this practice:** This walk was an Honor on a pilgrimage route. The walker followed one day's stage of a route walked for centuries, guided by the route's own places rather than by a companion's voice. The line was traced, not raced."
        if let route = story.routeName { text += " The route: \(route)." }
        if let stage = story.stageLabel { text += " The stage: \(stage)." }
        if let title = story.wayTitle { text += " Named: \(title)." }
        text += story.arrived
            ? " The end of the stage was reached."
            : " The stage was left before its end, which the practice honors too."
        return text
    }
```
> Pilgrim/Models/Prompt/PromptAssembler.swift:185-194@7c200bf

```swift
        let way = WayStore.shared.way(forWalk: uuid)
        return (practice.mode, practice.seekStory,
                HonorStoryContext(wayTitle: way?.title, arrived: story.arrived,
                                  routeName: routeName(for: way),
                                  stageLabel: way?.stage.map { "stage \($0.index + 1) of \($0.count)" }))
    }

    /// The route's own name when its package is still on the phone, else the
    /// slug the stage carries — the journal must still name the route after
    /// the package has been removed.
    private func routeName(for way: Way?) -> String? {
        guard let stage = way?.stage else { return nil }
        return PilgrimagePackageManager.shared.installed().flatMap {
            $0.routeId == stage.routeId ? $0.route.name : nil
        } ?? stage.routeId
    }
```
> Pilgrim/Scenes/Prompts/PromptListView.swift:231-246@7c200bf

```swift
struct HonorStoryContext {
    let wayTitle: String?
    let arrived: Bool
    var routeName: String?
    var stageLabel: String?

    init(wayTitle: String?, arrived: Bool, routeName: String? = nil, stageLabel: String? = nil) {
```
> Pilgrim/Models/Prompt/ActivityContext.swift:22-28@7c200bf

What iOS builds:
- **The Way** is the one the walk's link names (`way(forWalk:)`), loaded from the store. A walked stage's `way.json` survives Replace and Remove (`retireMany`, P2), so it is normally there.
- **`routeName`:** nil unless the Way has a stage. Then the installed package's `route.name` if the installed route's id equals `stage.routeId`, else `stage.routeId` (the slug, e.g. `camino-frances`). It is never nil for a stage, so **a stage selects the stage form, always**; a Way with no stage gets the shared form.
- **`stageLabel`:** `"stage \(index + 1) of \(count)"`, from the stage's own `count`.
- **`wayTitle`:** the Way's `title` (the stage file's title, e.g. "Saint-Jean-Pied-de-Port to Roncesvalles").
- **`arrived`:** from the walk's events (`HONOR_ARRIVAL` present), not from the ledger.
- The full stage text, for an arrived walk with every part present: `**About this practice:** This walk was an Honor on a pilgrimage route. The walker followed one day's stage of a route walked for centuries, guided by the route's own places rather than by a companion's voice. The line was traced, not raced. The route: Camino de Santiago (Francés). The stage: stage 1 of 33. Named: Saint-Jean-Pied-de-Port to Roncesvalles. The end of the stage was reached.`
- If the Way is gone or the walk was never linked, `way` is nil, `routeName` is nil, and a stage walk gets the **shared-walk** form ("a Way another walker laid down, hearing their voices", §20).
- `installed()` is not a pure read. It decodes every installed `route.json` and, when no download is in flight and a `replacing.txt` marker is left, deletes the abandoned package and the marker (`PilgrimagePackageManager.swift:89-108`). iOS calls it on main while building the prompt. If two routes are installed (a Replace mid-download), `found.first` may be the other route, and the slug is used.

**Android at `0defff85`:**

```kotlin
@Immutable
data class HonorStoryContext(val wayTitle: String?, val arrived: Boolean)
```
> app/src/main/java/org/walktalkmeditate/pilgrim/core/prompt/ActivityContext.kt:34-35@0defff85

```kotlin
        val honorStory = practice.honorStory?.copy(
            wayTitle = honorWalkRecords.record(walkId, walk.uuid).way?.title,
        )
```
> app/src/main/java/org/walktalkmeditate/pilgrim/core/prompt/PromptsCoordinator.kt:282-284@0defff85

```kotlin
            PracticeMode.Honor -> context.honorStory?.let(::honorLexicon) ?: HONOR_BASE_TEXT
```
> app/src/main/java/org/walktalkmeditate/pilgrim/core/prompt/PromptAssembler.kt:313@0defff85

U40 changes:
1. `HonorStoryContext` gains `routeName: String? = null` and `stageLabel: String? = null` (defaults keep every caller compiling, as iOS's init does).
2. `PromptsCoordinator` reads the record's `way` once, and fills `routeName` (installed route's name when the ids match, else `stage.routeId`) and `stageLabel` (`"stage ${index + 1} of $count"`). `HonorWalkRecords.record` already resolves the Way from the live row before the Honor step and from the link after (`HonorWalkRecords.kt:71-73,122-136@0defff85`), the right Way in both windows.
3. **The installed-route lookup must be a read.** It runs in the UI process (PromptsCoordinator), which is safe for iOS's `installed()` side effects, but flow gap 9 asks that `installed()`'s marker cleanup never run from a background path that races a download. Prefer a read-only `installedRoute(): (routeId, name)?` from U34 for the lexicon; the cleanup is the route page's and the launch's to do. The visible result is identical.
4. `PromptAssembler`: `routeName == null` → the existing `honorLexicon` (shared form, unchanged); else a `stageLexicon` with the four sentences above, verbatim. Keep `HONOR_BASE_TEXT` the single source of the shared opening.
5. Port the three `PracticeLexiconTests` cases (§19) into `PracticeLexiconTest.kt`.

---

### 16. The two one-line deltas

```swift
    var buttonLabel: String {
        switch self {
        case .wander: return "Wander"
        case .honor: return "Honor"
        case .seek: return "Seek"
        }
    }
```
> Pilgrim/Models/Walk/WalkMode.swift:14-20@7c200bf

```swift
    static let pilgrimageOfflineNoteShown = UserPreference.Required<Bool>(key: "pilgrimageOfflineNoteShown", defaultValue: false)
```
> Pilgrim/Models/Preferences/UserPreferences.swift:79@7c200bf

- **`WalkMode.honor.buttonLabel`** went from "Choose a way" to "Honor" in PR #84. Android already shows "Honor" on the mode button (`path_button_honor`, `strings.xml:599`; `WalkStartModeTest.kt:48`). "Choose a way" survives on Android as the Ways sheet's title (`honor_ways_title`), which is iOS's sheet title too (P4 confirms). Nothing to do; port `WalkModeTests.testHonorIsTheThirdMode`'s label assertion if not already covered.
- **`pilgrimageOfflineNoteShown`** (default false) backs the overview's once-ever offline note (P4, U38). On Android it belongs in DataStore beside the Honor preferences; it is never read in `:tracker`.

---

### 17. The water golden traces (R22)

#### 17.1 The harness still works: one trace captured

The U16 harness (`app/src/test/resources/honor/golden/capture/`) fetches `Way.swift`, `WayGeometry.swift`, `HonorTuning.swift`, `HonorMomentTracker.swift`, `HonorEngine.swift` and `ArrivalDebounce.swift` verbatim at `7c200bf`, which already contain the water watcher. Its event sink refuses marks today:

```swift
        case .markAhead: fatalError("an own walk has no marks")
```
> app/src/test/resources/honor/golden/capture/main.swift:192@0defff85

The probe (a scratch probe, `main.swift`, not committed, built with `swiftc -O ios/*.swift Stubs.swift main.swift` from the same six files plus the harness's `Stubs.swift`) drives iOS's engine through the real `bind`, with U16's Begin order (fix, tick 0, `paused: true`, `paused: false`) and ticks 0.4 s past each second. It reads the tracker's private `marks`, `firedMarks` and `lastMarkSeconds` with `Mirror`.

The stage: 42.9°N, 2.0°W, 6,000 m straight east (61 points, 100 m apart; `geometry.totalMeters` = 5,993.259228 m by haversine), their pace 1 m/s. Marks: `w-free` water 250 m (12 m off), `w-quiet` water 1,500 m (5 m off), `w-far` water 2,000 m (250 m off), `f-food` food 2,200 m, `w-late` water 3,750 m (40 m off), `w-after` water 3,800 m (60 m off). The walk: Begin 100 m north of the trailhead, a diagonal join, a 10 min pause (t 600–1,200 s, engine clock frozen at 600), a 10 min sitting 104 m short of `w-quiet` (t 1,800–2,400 s, clock running), then 1.2 m/s on. Fixes every 10 s, accuracy 5 m, speed 1.2.

Output (a scratch probe, `probe-output.txt`, not committed):

| Input | Fix | t (s) | Engine clock (s) | Along (m) | Event | firedMarks after | lastMarkSeconds after |
|---|---|---|---|---|---|---|---|
| 0 | 0 | 0 | 0.0 | 0.0 (fallback, off the Way) | none | ∅ | nil |
| 58 | 5 | 50 | 49.4 | 41.6 (joined; re-anchored) | `markAhead w-free 208.096 m` | {w-free} | 49.4 |
| 4682 | 425 | 4,250 | 3,649.4 | 3,611.9 | `markAhead w-late 133.850 m` | {w-free, w-late} | 3,649.4 |

Watched marks at init: `w-free@0.0417, w-quiet@0.2500, w-late@0.6250, w-after@0.6333`. `w-far` (250 m off) and `f-food` (food) are filtered out; `w-after` at exactly 60 m is in.

What it proves, from iOS's own code:
- **The first caption is free** and fires on the first on-Way fix, at the join, not at Begin (Begin was 100 m off the line, `isOnWay` false).
- **The quiet hour runs from the first caption's clock** (49.4 s), not from the walk's start. It ends at 3,649.4 s: the fix at 4,240 s (clock 3,639.4) stays quiet, the fix at 4,250 s (clock 3,649.4, so `3649.4 − 49.4 = 3600`, not `< 3600`) fires.
- **The pause stretches it in wall time** (Android's clock): 600 s of pause moved the end from t 3,649 to t 4,249.
- **The sitting doesn't:** `w-quiet`, 104 m ahead for the whole sitting, stays silent because of the hour, and is passed before the hour ends, so it never speaks.
- **Skipped, then spoken:** `w-late` came within 300 m at about t 4,110 s (clock ≈ 3,510, inside the hour) and spoke once the hour ended, 133.85 m out.
- **One per fix, then silence:** `w-after` was 184 m ahead when `w-late` fired, inside 300 m, but the call had returned; the new hour then silenced it until it was passed.

A second run (`w1/main.swift`, `probe-w1-output.txt`) is the same walk begun **on** the line at the trailhead:

| Input | Fix | t (s) | Engine clock (s) | Along (m) | Event | lastMarkSeconds after |
|---|---|---|---|---|---|---|
| 0 | 0 | 0 | 0.0 | 0.0 (anchored at Begin) | `markAhead w-free 249.719 m` | 0.0 |
| 4638 | 421 | 4,210 | 3,609.4 | 3,564.0 | `markAhead w-late 181.794 m` | 3,609.4 |

It confirms that the Begin fix is processed with the clock still at 0 (before the first tick), so a water within 300 m of an on-Way Begin fires on input 0 and the quiet hour runs from 0: the fix at 4,200 s (clock 3,599.4) is quiet, the fix at 4,210 s speaks.

Reproduce: copy the six iOS files and the harness's `Stubs.swift` as `capture.sh` does, add a driver like the one described above, and run `swiftc -O -o probe ios/*.swift Stubs.swift main.swift && TZ=UTC ./probe` (about 10 s). The probe isn't committed; U35 builds these runs into the harness as water traces.

#### 17.2 What U35 must change in the harness

- **`main.swift`:** record `.markAhead` as `EventRecord(type: "markAhead", id: mark.id, meters: meters)` (add `meters: Double?` to `EventRecord`), and add `firedMarks: [String]` (sorted) and `lastMarkSeconds: Double?` to `StateRecord`, read with `field(tracker, "firedMarks", as: Set<String>.self)` and `field(tracker, "lastMarkSeconds", as: TimeInterval?.self)`.
- **`Corpus.swift`:** a stage builder: `Way(source: .pilgrimage(...))` with `marks` (and a `stage`, so `way.json` round-trips as a stage), each mark placed in local metres with its `frac` as along-metres / design length and its `offLineMeters` as drawn.
- **`HonorGoldenTraceTest.kt`:** map `MarkAhead` in `golden()`, compare `meters` with the existing 1e-9 m tolerance, and compare the two new state fields. `markAhead` involves no `CLLocation.distance` call (D10 is frac arithmetic), so the cache-bound check doesn't apply to it. Keep a margin check of its own: every `ahead` within 1e-6 m of 300 and every quiet-hour difference within 1e-6 s of 3,600 must be avoided by the corpus, as U16 does for the distance thresholds.
- **Recapture all traces.** The nine existing traces have no marks and must come out byte-identical; a diff there means the harness change leaked.

#### 17.3 The scenarios U35 must capture

Each is a Way with marks plus a fix sequence with times, ticks, gates; "expected" is what iOS's engine emits, captured, never hand-written. Use a different latitude band for each, as U16 does.

| # | Name | Inputs (Way, marks, walk) | What iOS must show |
|---|---|---|---|
| W1 | `water-first-free` | Begin on the line 250 m before water `a` (frac ~0.04 of a 6 km stage) | `markAhead a` on the **Begin fix** (input 0), `lastMarkSeconds = 0`: the Begin fix sees clock 0 before the first tick (§3) |
| W2 | `water-quiet-hour` | Three on-way waters 1.5 km apart on a 6 km stage, walked at 1.2 m/s with no pause | the first fires; the second (passed about 21 min later) never fires; the third fires on the first fix with clock ≥ first + 3,600 while it is 0–300 m ahead |
| W3 | `water-skipped-then-spoken` | The probe's trace (§17.1), or one like it: a mark reached 300 m out inside the hour and still ahead when it ends | that mark fires late, with the remaining metres; the next mark inside 300 m is silenced by the new hour |
| W4 | `water-off-line-and-kinds` | Water at 61 m off (excluded), 60 m (included), 250 m (excluded); food, bed, transport, supply and medical at 0 m off | only the 60 m water fires; the probe already shows the 60.0 inclusion |
| W5 | `water-once-per-mark` | One water mark; walk past it, turn back within the window (backward 0.02/fix), walk past again after the hour | one `markAhead`, ever |
| W6 | `water-off-way-gate` | After the hour, step 80 m off the line while a mark is 200 m ahead; stay off under 120 s; rejoin | nothing while off (`isOnWay` false, progress frozen); fires on the rejoining fix with that fix's metres |
| W7 | `water-pause-sitting` | A pause and a sitting inside the hour, with a mark that falls 0–300 m ahead around the hour's end | the hour ends on engine seconds: the pause delays it in wall time, the sitting doesn't. Capture with the gates set (`paused`, `meditating`) to show water ignores them; also a `recording` and an `externalAudio` gate open across a firing |
| W8 | `water-fallback-join` | Begin 100 m off the line; a mark 250 m in | no water until the join; the first fires at the join, free |
| W9 | `water-two-in-one-fix` | After the hour, a jump (re-acquire) that lands with two unfired marks inside 300 m | only the lower-frac one fires; the other waits for the next hour |
| W10 | `water-revival` | Any of W2/W3 | **Android-only check, no iOS capture needed:** the test snapshots the engine at a chosen input (after a firing, and again inside the quiet hour), builds a fresh engine, restores, and must emit exactly the remaining iOS events. iOS never revives, so the uninterrupted iOS trace is the expectation. Run it once through the real session (`HonorHarness`): kill and revive `:tracker` mid-trace and assert Room's `fired_marks`/`last_mark_seconds` and the event stream |

Arrival on a stage with water near the end (both on one fix) is optional; arrival itself is already covered by the nine traces.

---

### 18. The flow-analysis gaps that touch the engine, the session, revival or the reply

| Gap | What iOS does | What Android must do |
|---|---|---|
| **1. The walk guard / `:tracker` re-reads `way.json`** | iOS walks the `Way` value captured at Begin; no re-read. | `prepare` re-reads at Start and each revival (§11). The guard's "live session rows" clause keeps the package still under a revival; the door's file stamp (plan) covers Begin→Start. U35 adds nothing more. If `prepare` can't load the Way, it refuses the session (`HonorSessionStart.Refused`), as it does for any Way today. |
| **4. Finalize ordering; stage identity in the row** | The clean record reads the captured Way's stage; recovery reads the stored one (§12). | Copy route id, index, name and km into the row at Start (§12); the outcome comes from the row (§10.2) before the live rows are deleted (P2 orders it). |
| **6. Water ahead needs more in Room** | iOS keeps the fired set and clock in memory; the caption lives 20 s on a timer. | Schema 12's `fired_marks`, `last_mark_seconds`, and the three caption columns, written before the haptic; the UI shows what is left of 20 s and never replays it after a revival (§6.2, §7.3, §11). Water ignores the paused, sitting, recording and whisper gates; the quiet hour runs on the engine clock across a pause and a revival (§3; golden W7, W10). |
| **7. The summary of a stage walk that never anchored** | No outcome, so no ledger entry; the walk is still linked. | "Never anchored" is `start_frac IS NULL OR anchored_by_fallback = 1` on the row (§10.2). What the summary shows then is P5's. |
| **12. Task restore** | n/a (iOS's walk screen dies with the process). | A walk screen restored mid-walk reads the caption from Room like any other state; nothing in `:tracker` changes. |
| **14. Release flag off** | n/a. | The 11→12 migration runs flag on or off; with the flag off no stage session can start, so the new columns stay at their defaults. Test the full 8→12 chain. |
| **Cached `:tracker` (plan learnings)** | n/a. | A second stage walk in a cached process starts with an empty fired set (§11). |

Gaps 2, 3, 5, 8–11, 13 and 15 are P2's and P4's; nothing in them changes the engine or the session beyond the row fields above.

---

### 19. Test inventory (port verbatim, R22)

Fixtures are inline unless named. The tracker tests use a 1 km Way east along the equator, 11 points `0.000898°` apart with `t = i × 60`, and `water(id, frac, offLine = 10)` placed at `lon = frac × 1000 / 111_320`. `PilgrimageStageWalkTests.stageWay()` is a 1 km stage east along the equator (`camino-frances`, index 0, count 33, `distanceKm` 24.2, closing "You crossed a border on foot.", end "Roncesvalles" at lon 0.00898), one waypoint `wp-orisson` at frac 0.3 with text, names, `sitMinutes` 5 and a `pin`. The file fixture `UnitTests/Fixtures/Pilgrimage/stage-00.json` (two waters, 12 m and 250 m off the line, and one food; P1 ports it) is also a ready input for a tracker test.

**`UnitTests/Honor/HonorMomentTrackerTests.swift`** → `T/domain/honor/HonorMomentTrackerTest.kt` (U35). The first 12 are already ported (Stage 21-1); the 6 water tests are new:

| Test | Asserts |
|---|---|
| `testWaterFiresOnceInsideThreeHundredMetresBeforeIt` | water at 0.5: nothing at progress 0.1 (400 m short); `markAhead a` at 0.25 (clock 60) with metres 250 ± 5; nothing at 0.3 (clock 120) or at 0.3 after the hour (clock 4,000): a spoken mark never speaks again |
| `testWaterNeverFiresOnceItIsBehindYou` | water at 0.5, progress 0.6: nothing |
| `testAFountainOffTheTrailIsADetourNotADrink` | water 250 m off the line, 250 m ahead: nothing |
| `testOffWayWalkersGetNothing` | water 250 m ahead with `isOnWay: false`: nothing |
| `testTheFirstIsFreeThenOnePerHourOfWalking` | waters at 0.3, 0.5, 0.9: `a` at progress 0.1 (clock 0); nothing at 0.3 (clock 1,200, `b` 200 m ahead inside the hour); `c` at 0.7 (clock 5,400) |
| `testOnlyWaterSpeaks` | a bed mark 250 m ahead: nothing |

**`UnitTests/Honor/PilgrimageStageWalkTests.swift`** (19) → `T/walk/honor/PilgrimageStageWalkTest.kt` and the owners' test files. P3 owns the first three and 7, 8 and 15; the rest are listed for completeness:

| # | Test | Asserts | Owner, Android home |
|---|---|---|---|
| 1 | `testTheEngineReportsWhetherItEverAnchoredOnTheWay` | `isAnchoredOnWay` false before a fix; false after a fix 1 km north (fallback); true after a fix on the line | P3, U35/U36: engine test plus the row twin (§10.2) |
| 2 | `testTheOutcomeSurvivesTeardown` | a fix at lon 0.002694 (300 m) then teardown: outcome non-nil, `progressFrac` 0.3 ± 0.02, `arrived` false | P3, U36: from the row |
| 3 | `testNoOutcomeWithoutAnAnchor` | a fix 1 km north then teardown: outcome nil | P3, U36: from the row |
| 4 | `testTheCoordinatorWritesTheStageIntoTheRoutesLedger` | record stage 4 with (0.58, false): entry "4" has the name, `stoppedAtFrac` 0.58, `completed` false; `next(33)` is `(0, nil)` | P2, U36 |
| 5 | `testNothingIsWrittenForAWayThatIsNotAStageOrAWalkThatNeverJoined` | outcome nil → no ledger; a non-stage Way with an outcome → no ledger | P2, U36 |
| 6 | `testTheStageLineStandsWhereADateWould` | `WayStageLine` "stage 1 of 33 · <24,200 m> · hard"; nil for a non-stage; `isPilgrimageStage` both ways | P4, U38 |
| 7 | `testAStageWalksWithNoCompanionAndNoSoftTap` | with the soft-tap preference on: `companionCoordinate` nil, `engine.softTapEnabled` false | P3, U35: `BeginHonorWalk` + `HonorWalkViewModel` (§9) |
| 8 | `testAnOwnWalkWayKeepsItsCompanion` | `stage = nil`, preference on, one fix: `companionCoordinate` non-nil, `softTapEnabled` true | P3, U35: the positive control |
| 9 | `testTheArrivalCardForAStageNamesTheStageAndCarriesNoDelta` | stage card title "you walked the stage", line has "3 places passed" and the km; a shared card's title "you walked their way" | P5, U39 |
| 10 | `testTheSummaryForAStageReadsKilometresAndNoCompanionDelta` | no delta; progress line ends "of the stage" and contains 24.2 × 0.58 km | P5, U40 |
| 11 | `testTheSummaryKickerDropsTheirStepsForAStage` | kicker "the stage you walked" for a stage with no ledger; "in their steps" for a non-stage; a nil Way isn't a stage | P5, U40 |
| 12 | `testTheLocalNameFollowsAFixedOrderAndNeverEchoesTheLabel` | local-name order and the label/English exclusions | P5, U39 |
| 13 | `testThePlaceCopyChangesForAStage` | "A place on the way." / "A place they marked." / the text wins | P5, U39 |
| 14 | `testAPinDrawsAtItsOwnCoordinateWhileTheTriggerStaysOnTheLine` | the pin at `pin` (lat 0.0002), the trigger at `at` | P5, U39 |
| 15 | `testWaterAheadBorrowsTheCaptionLineAndNothingElse` | after `.markAhead(mark, 280)`: `softTapCaption == "water in " + WayDistance(280)`; no card | P3/P5, U35 (the row's caption fields) and U39 (the slot) |
| 16 | `testTheFactsLineReadsInTheWalkersOwnUnit` | the morning card facts line | P4, U38 |
| 17 | `testTheWeatherLineIsSilentWithoutASnapshot` | the weather line | P4, U38 |
| 18 | `testTheOfflineNoteIsSaidOnceAndOnlyForAStage` | the offline note's four cases | P4, U38 |
| 19 | `testTheWaysListNeitherShowsAnInstalledStageNorTakesItWithTheRest` | Settings → Ways never lists or deletes a stage Way | P2, U40 |

**`UnitTests/Honor/PilgrimageStageWalkTests+Replies.swift`** (5) → `PilgrimageStageWalkTest.kt` (U36):

| Test | Asserts |
|---|---|
| `testTheReflectionIsFiledUnderTheReservedOrigin` | the moment's id is "stage-reflection"; `originIndex` is −1; the origin constant is −1; `moment.at == stage.end.at`; "voice-3" → 3; a waypoint id → nil |
| `testAReplyToTheReflectionRoundTrips` | no reply URL at first; after `setReply(originN: -1)` the URL is the recording |
| `testAReplyStillRecordingWhenTheWalkEndsIsStillFiledUnderTheReflection` | a recording open, `replyToStageReflection()`, `stop()`, then the late flush: `replies == [-1: path]` |
| `testTheArrivalCardAppendsTheStagesClosingLine` | the card carries `closing`; title "you walked the stage" (P5) |
| `testTheSummaryCarriesTheClosingOnlyWhenArrivalFired` | no closing without `HONOR_ARRIVAL`; with it, the closing, the reply path from `replies[-1]`, `repliesMade` 1 (P5) |

On Android the third becomes a `WalkLifecycleObserver` + `HonorReplies` test: arm with `momentId = "stage-reflection"`, finish the walk with the take open, assert `replies.json` maps `-1`.

**`UnitTests/Honor/ActiveWalkHonorTests.swift`** (stage diff): `testAVoiceReplyStillRecordingWhenTheWalkEndsIsStillFiled` (a `voice-1` reply open at `stop()` files as `[1: path]`). Android has this behavior since Stage 21-1; port the case if no test pins it.

**`UnitTests/VoiceRecordingDiscardTests.swift`** (2) → the Android recorder/observer tests (U36):

| Test | Asserts |
|---|---|
| `test_discardRecording_releasesTheSessionAndTakesThePartialFile` | after discard: not recording, no start date, the "voiceRecording" session consumer released, the partial file gone |
| `test_discardRecording_doesNothingWithNoRecordingOpen` | with nothing open: no change, another consumer's session untouched |

Android's equivalent is `discardWalk()` → `honorReplies.clear()` and the observer's Idle path deleting the take (§13.4). Port as: discard with a take open leaves no file and no armed origin; discard with nothing open changes nothing.

**`UnitTests/Honor/WalkModeTests.swift`:** `testHonorIsTheThirdMode` (button label "Honor", subtitle "walk in their steps") and `testHonorQuotesAreLocalized` (unchanged). Android: `WalkStartModeTest.kt:48` already asserts "Honor".

**`UnitTests/Honor/HonorJournalTests.swift`:** the only change is `ledger: nil` in `testSummaryDataNeedsTheHonorEvent` (P5's summary signature).

**`UnitTests/PracticeLexiconTests.swift`** (3 new) → `T/core/prompt/PracticeLexiconTest.kt` (U40):

| Test | Asserts |
|---|---|
| `testTheLexiconForAStageNamesTheRouteAndNotAnotherWalker` | with route "Camino de Santiago (Francés)" and label "stage 1 of 33": contains both, not "another walker", not "their voices", contains "The end of the stage was reached." |
| `testTheLexiconForASharedWalkIsUnchanged` | title "Rúa do Franco → Obradoiro", not arrived: contains "a Way another walker laid down", the title, "The Way was left before its end"; no "stage", no "pilgrimage route" |
| `testTheLexiconForAnUnresolvedHonorStoryMatchesTheSharedWalkBase` | no story: contains "a Way another walker laid down" and "hearing their voices where they were spoken" |

**New Android tests the units need** (no iOS twin): the 11→12 migration and the 8→12 chain; revival keeps the fired set and clock (golden W10 and a `HonorSessionTest`); the cached second walk starts empty; the caption's 20 s from Room across a UI restart; a stage start never schedules a soft tap; the water haptic's `VibrationEffect` built in a Robolectric test (the platform-object rule in CLAUDE.md).

---

### 20. Strings

All are code literals on iOS (not localized keys) except the arrival label and the event names. "They/their" flags apply plan R14: stage surfaces never say "they" or "their" except where iOS ships it.

| String (verbatim) | Where and when | Format arguments | They/their on a stage? |
|---|---|---|---|
| `water in %@` | the walk sheet's caption slot, 20 s from a water mark's firing (§7) | `WayDistance.string(meters:)` of the metres ahead | no |
| `%d m` | inside the water caption, metric, `ahead` < 1,000 m | `Int(meters.rounded())` | no |
| `%d ft` | inside the water caption, imperial, under 0.1 mi | `Int((meters × 3.28084).rounded())` | no |
| `%.1f mi` | inside the water caption, imperial, 0.1 mi and over | miles, C rounding | no |
| `off the way · %d m` | the soft tap's caption; **never on a stage** (§9), shown here because the slot is shared | whole metres, truncated, ≤ 999,999 | no |
| `their way, walked` | lock-screen glance (Android: the notification line) after arrival, on every Way including a stage (§14) | none | **yes, iOS ships it** |
| `off the way` | the glance while `isOnWay` is false, including before the first on-Way fix | none | no |
| `almost there` / `~%d m to go` / `~%.1f km to go` / `~%.1f mi to go` / `2 km+ to go` / `1.2 mi+ to go` | the glance while on the Way, by Seek's distance bucket | the bucket | no |
| `Walked their way: %@` | the arrival waypoint's label in the walk's journal, written at arrival on a stage too (`HonorPersistence.swift:42-44`; Android `honor_arrival_label`) | `way.title`, which for a stage is the stage file's `title` (equal to `stage.name` in the live data) | **yes, iOS ships it** |
| `Honor` / `Way walked` | the walk event names (`honor.event.honor_mode`, `honor.event.arrival`), unchanged | none | no |
| `**About this practice:** This walk was an Honor on a pilgrimage route. The walker followed one day's stage of a route walked for centuries, guided by the route's own places rather than by a companion's voice. The line was traced, not raced.` | the prompt's practice lexicon for a stage walk (§15), seen by the walker in a copied prompt | none | no |
| ` The route: %@.` | appended, when `routeName` is set (always, for a stage) | the installed route's name, else the slug | no |
| ` The stage: %@.` | appended, when `stageLabel` is set | `stage %d of %d` | no |
| `stage %d of %d` | the stage label inside the lexicon | `index + 1`, `count` | no |
| ` Named: %@.` | appended, when the Way's title is known | `way.title` | no |
| ` The end of the stage was reached.` / ` The stage was left before its end, which the practice honors too.` | the lexicon's last sentence, by `HONOR_ARRIVAL` | none | no |
| `**About this practice:** This walk was an Honor. The walker followed a Way another walker laid down, hearing their voices where they were spoken. Two traveling together; the line was traced, not raced.` (+ ` The Way: %@.`, ` The end of the Way was reached.` / ` The Way was left before its end, which the practice honors too.`) | the shared form; a stage walk gets it only when its Way can't be found (§15) | the title | **yes, iOS ships it** (the edge case, §24 D4) |
| `Honor` | the walk-mode button (PR #84: was "Choose a way") | none | no |
| `walk in their steps` | the walk-mode subtitle, unchanged; not a stage surface | none | n/a |

The reflection moment's label (`stage.end.name`) and icon are never shown (§13.1).

---

### 21. Corrections to the Android plan

1. **The soft-tap fix site.** Plan, U35 Files: "`P/walk/WalkStartRequest.kt` (no soft tap on a stage)", and Context: "`P/walk/WalkStartRequest.kt` (`HonorSettings.atStart` lacks iOS's `&& !isPilgrimageStage` on the soft tap)". `atStart` runs in `WalkViewModel.startHonorWalk` before the Way is loaded, so it can't see the stage. **Fix:** apply the clause in `BeginHonorWalk.begin`, which has the loaded Way, by adjusting the settings put into `HonorStart` (§9). Add `P/honor/BeginHonorWalk.kt` to U35's Files.
2. **Schema 12 misses the arrival's walked distance.** Plan, KTD: "additive columns on `honor_sessions` for the fired marks, the quiet hour's last mark time, the water caption (mark id, metres, firing time), and the stage identity." **Add** `arrival_walked_meters`, written in arrival's compare-and-set: iOS freezes `distanceWalkedMeters` into the stage arrival card at the arrival event, and the live `walked_frac` moves on after it (§12).
3. **"Pauses out" is Android's clock, not iOS's.** Plan, U35: "a quiet hour of engine seconds, with pauses out and sittings in". iOS has no reachable pause (spec B; pilgrim-ios #112 item 1). The rule follows from owner decision 1, already recorded. **Reword:** "a quiet hour of the engine clock (sittings in; Android's frozen pause, owner decision 1)".
4. **The quiet hour runs from the last caption, and the Begin fix can be the first.** Not wrong in the plan, but missing from its test scenarios. **Add:** a water within 300 m of an on-Way Begin fires on the Begin fix with the clock at 0; the next can fire only when the clock reaches the first's value plus 3,600 (probe, §17.1).
5. **Every gate, including the whisper.** Plan, U35: "it ignores the recording, sitting and paused gates, as iOS does." **Add** `externalAudio` (a playing whisper): water reads no gate at all.
6. **The origin lives in `voiceOriginIndex`, and playback needs the synthetic moment.** Plan, U36 Files: "`P/honor/HonorReplies.kt` (origin −1, "stage-reflection", at `stage.end.at`)". The −1 mapping belongs in `voiceOriginIndex` (`HonorSessionState.kt`), which both `HonorReplies` and `HonorSession.planReply` use, and `planReply` must accept the reserved id, which isn't among `way.moments` (§13.4). **Add** `HonorSessionState.kt`, `HonorSession.kt` and `HonorPersistence.kt` to U36's Files.
7. **"At `stage.end.at`" is a place, not a time.** Plan, U36 Files and the survey: "files under origin −1 at `stage.end.at`". iOS files the reply when the recording completes; `stage.end.at` is only the synthetic moment's coordinate, which nothing reads (§13.1). **Reword** to "under origin −1, against the reflection moment (the stage's end place)".
8. **The sink and the discard already exist.** Plan, U36: "The recordings sink outlives Stop, and discard drops it." Stage 21-1 built both (`WalkLifecycleObserver`, `WalkViewModel.discardWalk`). U36 only adds the −1 origin and tests the stage case.
9. **The deferred question is answered.** Plan, Open Questions: "How the live-row fields map to iOS's `HonorStageOutcome`". **Answer:** `progressFrac` = `progress_frac`; `arrived` = `phase == ARRIVED`; no outcome when `start_frac IS NULL OR anchored_by_fallback = 1` (`isAnchoredOnWay`); `progress_high_water` is read by nothing; `walked_frac` only by the arrival card (§10.2). Move it to Resolved.
10. **The lexicon needs `PromptsCoordinator` and a read.** Plan, U40 Files list `ActivityContext.kt` and `PromptAssembler.kt` only. The route name and stage label are filled where the title is (`PromptsCoordinator.kt:282-284`), from `HonorWalkRecords`, and need U34's installed-route lookup, read-only (§15). **Add** `P/core/prompt/PromptsCoordinator.kt`, and a dependency on U34.
11. **The caption window.** Plan, KTD: "The UI shows the caption from Room for what's left of its 20 s". **Add:** shown only while `0 ≤ now − firedAt < 20 s`, so a wall clock set back can't stretch it (§7.3).
12. **Water is evaluated only on fixes.** The survey says "every accepted fix", which is right; the plan's tests should not expect water on a tick or a gate change (the hour can end on a tick, and the caption waits for the next fix, as the probe shows).
13. **The marks' sort.** Plan, U35: "only `water` marks within 60 m of the line, sorted by fraction". **Add:** with Swift's `<` (so `-0.0` ties `0.0`), stable, no tiebreak (§2.1).
14. **The glance needs a test, not code.** Plan, U40: "The glance: 'their way, walked' stays, matched as shipped." Right; U40 adds only a pinning test (§14), and no file under `P/service/` changes.

---

### 22. Notes by unit

**U35 (water, the stage session, schema 12, the goldens):**
- Constants: `ON_WAY_METERS` 60 (`<=`, the mark filter), `MARK_AHEAD_METERS` 300 (`<=`), `MARK_QUIET_SECONDS` 3,600 (strict `<` on the difference), the 20 s caption (`SOFT_TAP_CAPTION_MILLIS`), the haptic tick at 0.4.
- Order in `update`: reach loop → drops → water → start. Water reads `activeSeconds` = the engine's `activeDuration` and `isOnWay` = the engine's own, after `track`.
- `HonorMomentTracker`: `marks` param (default empty), the filter and Swift-`<` stable sort, `update(..., activeSeconds = 0.0, isOnWay = true)`, `Action.MarkAhead`, `waterAhead` verbatim, `Snapshot(reached, queue, firedMarks, lastMarkSeconds)`, `restore` filtering ids to the watched marks.
- `HonorEngine`: `HonorEngineEvent.MarkAhead(mark, meters)`; pass `way.marks ?: emptyList()`; map the action.
- `HonorSession.planEvents`: `MarkAhead` → plan the caption (`mark.id`, `meters`, `plan.now`), then `Ritual.WaterHaptic`; the fired set and clock ride `snapshot.tracker` into `HonorEngineState`; `perform` → `haptics.waterAhead()`.
- `HonorHapticsPort.waterAhead()`, `HonorHaptics.waterAhead()`: `PRIMITIVE_TICK` at 0.4, fallback 30 ms one-shot at `amplitudeFor(0.4f)`.
- Schema 12 columns (§12), `MIGRATION_11_12`, `12.json`, 11→12 and 8→12 tests.
- The stage identity on the row at Start; the soft-tap clause in `BeginHonorWalk` (§9).
- The golden harness changes and scenarios W1–W10 (§17).
- Device rows for U41: water's haptic with the screen off; a UI kill inside a caption's 20 s; a `:tracker` kill after a water caption (no repeat).

**U36 (finalize, origin −1, the reply):**
- `HonorSessionEntity.stageOutcome()` (§10.2) is the only outcome source, read before the live rows go.
- `voiceOriginIndex("stage-reflection") == -1` on any Way; `HonorPersistence.stageReflectionMoment(stage)`; `planReply` accepts the reserved id when `way.stage != null`.
- The arrival card's "reply here" arms `HonorReplies` with `"stage-reflection"`; `listed` is true for an installed stage.
- Port the five `+Replies` cases and `VoiceRecordingDiscardTests` (§19). The late-flush case maps to `WalkLifecycleObserver.handleVoiceStop(commitRow = true)`.

**U39 (the caption slot, for P5):** the caption state is the row's `water_caption_*` (§12); format with `WayRelation.distance`; one slot, water when live else the soft tap's; `arrival_walked_meters` for the stage card's km.

**U40 (the lexicon and the glance):** `HonorStoryContext(wayTitle, arrived, routeName = null, stageLabel = null)`; fill both in `PromptsCoordinator` from the record's Way; the stage form verbatim (§15); a glance test that a stage reads "their way, walked".

---

### 23. Android additions to record at the gate

1. **The water state is persisted and revived** (fired set, quiet clock, caption fields in schema 12). iOS keeps it in memory and never revives a walk. Reason: `:tracker` revival.
2. **The water haptic fires in the pocket.** iOS fires it only while the app is active. Reason: R6's existing deliberate addition, already taken for every Honor and Seek haptic.
3. **The caption's 20 s are measured from a wall-clock firing time in Room**, clamped to 0–20 s, rather than a monotonic in-process timer. Reason: the UI and `:tracker` are separate processes.
4. **The stage identity is copied into the session row at Start**, and both the clean and the recovered record read it. iOS's recovery reads the stage from the stored `way.json` at recovery time. With the walk guard holding the package still under a live session, the result is the same. Reason: the ledger record mustn't depend on `way.json` surviving.
5. **The stage arrival card's walked metres are frozen in Room at arrival** (`arrival_walked_meters`). iOS freezes them in its in-memory card. Reason: the UI draws from Room.
6. **The outcome is read from the continuously written row**, fresher than iOS's last checkpoint after a crash (already in the plan).
7. **The soft-tap stage clause is applied where `BeginHonorWalk` loads the Way**, not at engine construction. Same frozen value. Reason: Android freezes the settings into the session row before `:tracker` builds the engine.
8. **The lexicon's installed-route lookup is a read** (if the owner takes decision 2): iOS's `installed()` may also delete an abandoned package. The words are identical.

---

### 24. iOS defects (matched as shipped)

| # | Candidate | Evidence | What a user sees | Severity | Status |
|---|---|---|---|---|---|
| D1 | The lock screen says "their way, walked" when a stage ends | `PilgrimWidgetLiveActivity.swift:193` (§14) | a "their" with no one behind it, on every arrived stage | low | known (survey §5); extends pilgrim-ios #109 item 3 |
| D2 | The arrival waypoint on a stage reads "Walked their way: <title>" | `HonorPersistence.swift:42-44`, written by `recordHonorArrival` for every Way (`ActiveWalkViewModel+Honor.swift:251-252`) | the journal's waypoint for an arrived stage names another walker | low | **confirmed** survey candidate 1 (note: it is `way.title`, the stage file's title, not `stage.name`, though they match in the live data); extends #109 item 3 |
| D3 | The ledger is told the walker's last position, not the farthest they got | `HonorStageOutcome(progressFrac: engine.progressFrac, ...)` (`ActiveWalkViewModel+Honor.swift:131-132`, `:169-170`); progress follows a walker back within 60 m and can jump back on a global re-acquire, while `progressHighWater` keeps the best | a walker who turned back for the bus, or ended the walk back at a car park on the line, is credited less than they walked and resumes from the lower point | low-medium | **confirmed** as the outcome half of survey candidate 4 (P2 owns the ledger half: "position, not walked distance") |
| D4 | A stage walk whose Way is gone or unlinked gets the shared-walk lexicon | `PromptListView.swift:231-246`: `routeName` is nil without a Way, so `PromptAssembler.swift:174` picks `sharedWalkLexicon`, which asserts "a Way another walker laid down, hearing their voices" | a model told about a companion and voices that never existed, on a rare path (link write failed, then the stage removed) | low | new; extends #109 item 3 |
| D5 | Water speaks during a sitting | `waterAhead` reads no gate (`HonorMomentTracker.swift:91`, `:127`) | a caption, and on iOS a tap if the app is active, during meditation; on Android a tap in the pocket mid-sitting | low (a question, not a certain defect) | new candidate |
| D6 | `installed()` deletes an abandoned package from a read path | `PilgrimagePackageManager.swift:99-107`, reached from the prompt build (`PromptListView.swift:243`) | nothing visible; a write on main from a screen that only wanted a name | low | new; overlaps flow gap 9 (P2) |
| D7 | Latent: the shared caption slot's first timer clears a later caption | `ActiveWalkViewModel+MarkPins.swift:65-69` and `+Honor.swift:240-244` check only the generation | nothing at the pin: on a stage the soft tap is off, and water captions are an hour apart | none (latent) | already in spec B §12.5; no issue needed |

Survey §5, by my cluster: candidate 1 **confirmed** (D2); candidate 4 **confirmed in its outcome half** (D3); candidate 5 (the arrival card's km) confirmed at its source, the engine's `distanceWalkedMeters = walkedFrac × engine geometry` (`HonorEngine.swift:39-41`), the dataset's km being P5's half; candidates 2, 3, 6–9 are other clusters'. The "known" glance item is D1.

Not defects: the water haptic missing in an iPhone pocket (Core Haptics renders only in the foreground; iOS chose to drop it, `ActiveWalkViewModel+Honor.swift:9-11`); the 1 Hz clock's staleness; `ahead` measured as frac × the engine's haversine length rather than to the mark's coordinate.

---

### 25. Proposed owner decisions

1. **Shape schema 12 for iOS PR #91 now, whatever its fate.** Name the quiet clock `last_notice_seconds`, reserve `fired_stamps`, and make the caption columns kind-neutral (§12). **Recommend yes:** it costs nothing today, and a device that has run schema 12 can't take a rename later without a schema 13.
2. **The lexicon's installed-route lookup: iOS's `installed()` or a read-only lookup.** Both give the same words; only `installed()` can delete an abandoned package as a side effect. **Recommend the read-only lookup** (Android addition 8), leaving the marker cleanup to the route page and launch, where P2 places it.
3. **D3 (position, not the farthest point, reaches the ledger) and D1, D2, D4, D5.** **Recommend parity plus upstream issues**, grouped with P2's ledger findings and #109's copy items. Android ports `progress_frac`, not `progress_high_water`.

---

## P4. Doors and pre-walk: the Ways sheet's third door, the catalog, the route page, the overview's stage branches, and the morning card

| | |
|---|---|
| iOS pin | `7c200bf` (v2.0.0) |
| Android HEAD | `0defff85` (branch `docs/stage21-2-plan`) |
| Feeds | U37 (the third door: the Ways sheet, the catalog, the route page, navigation); U38 (the overview's stage branches, the offline note, the morning card, "the day" data, the preview's stage line) |
| Lenses | Behavior, UI/visual, Data, Edge cases |


**iOS files read in full at `7c200bf`:** `Pilgrim/Scenes/Honor/HonorWaysSheet.swift` (207), `PilgrimageCatalogView.swift` (235), `PilgrimageRouteView.swift` (401), `StageMorningCard.swift` (104), `HonorOverviewView.swift` (418), `WayMomentHeader.swift` (135), `WayMomentPreview.swift` (175), `Pilgrim/Scenes/Root/MainCoordinatorView.swift` (446), `Pilgrim/Scenes/Root/MainTabView.swift` (the presentation half, 1–140), `Pilgrim/Models/Walk/StatsHelper.swift`, `Pilgrim/Models/Formatting/CustomMeasurementFormatting.swift`, `Pilgrim/Models/Honor/PilgrimageLedger.swift` (188; the screens render `next` and `progressLine`). **Read in part:** `PilgrimagePackageManager.swift` (the phase, `installed()`, the confirmations, download/replace/update/remove entry points), `PilgrimageCatalogService.swift` (the published catalog, `load`, `routePreview`, grouping), `PilgrimageWayImporter.swift` (`PilgrimageError`, `PilgrimageCopy`, the stage Way build), `HonorImportReducer.swift` (`HonorImportCopy`), `Weather/WeatherService.swift` (`WeatherCondition.label`, `formatTemperature`), `Preferences/UserPreferences.swift`, `Settings/AboutView.swift` (the data sources section), `ActiveWalkView.swift` (297–320) and `WalkOptionsSheet.swift` (38–69) for "the day", `PilgrimMapView+HonorWay.swift` (the slice-two diff), `Settings/WaysListView.swift` (the stage line's other caller). **Diffs:** `git diff 517c160^1 8018a55` and `git diff 8018a55 7c200bf` (slice three's hooks) on every owned file. **Tests:** `UnitTests/Honor/PilgrimageCatalogServiceTests.swift` (`PilgrimageCatalogModelTests`, 330–467), `PilgrimageStageWalkTests.swift` (the stage line, local name, place copy, facts, weather and offline-note tests), `PilgrimageMapsRowTests.swift` (the maps line, 21-3). **Docs (intent only):** the slice-two design spec §2.1–2.2, §3.6, §4.1, §6 and `docs/honor-slice-two-device-pass.md`. There is no string catalog: `Localizable.strings` carries none of these strings, so every one is a Swift literal (checked: `git show 7c200bf:"Pilgrim/Support Files/Base.lproj/Localizable.strings"`).

**Android compared at `0defff85`:** `P/ui/honor/HonorWaysSheet.kt`, `HonorWaysViewModel.kt`, `HonorOverviewScreen.kt`, `HonorOverviewViewModel.kt`, `HonorOverviewModel.kt`, `WayMomentHeader.kt`, `WayMomentPreview.kt`, `OwnWalkPicker.kt` (the sheet pattern), `WayPlaceCard.kt` (the existing Replace/Keep alert), `P/ui/navigation/PilgrimNavHost.kt` (Honor routes, `honorSheet`, `honorLinkScreen`, `fetchedWayLanding`), `P/honor/BeginHonorWalk.kt`, `P/honor/HonorImportCoordinator.kt` (`gather`), `P/honor/HonorLinkRouter.kt` (`screenChanged`), `P/ui/path/WalkStartScreen.kt` (the in-progress redirect), `P/ui/settings/about/AboutScreen.kt`, `P/ui/walk/WalkFormat.kt`, `P/data/collective/routes/CollectiveRoute.kt` (the house `MeasurementFormatter` stand-in), `P/data/honor/HonorPreferencesRepository.kt`, `P/data/weather/*`, `P/audio/model/WhisperModelStore.kt` (the connectivity-probe pattern), `P/ui/theme/Tokens.kt`, `Color.kt`, `Type.kt`, `app/src/main/res/values/strings.xml`, and the tests `T/ui/navigation/PilgrimNavHostTest.kt`, `T/ui/honor/WayMomentCopyTest.kt`.

**Probes** (Swift on this Mac, an uncommitted scratch folder): `fmt.swift`, `fmt2.swift` (what `StatsHelper` prints), `temp.swift` (`%.0f` rounding). Results are quoted where used.

Live data checked in `../open-pilgrimages` at `675d4e3` (index v1.12.0): eight packaged routes, three pilgrimages; Shikoku's `difficulty` is `""` on every stage; no stage has `hours.min == hours.max`; traditions are `christian`, `mixed`, `buddhist`.

---

### 1. The doors and how each screen is presented

#### 1.1 iOS's chain, presentation by presentation

The Path tab's Honor button opens the Ways sheet through `chooseWay()`. The overview is a second sheet, presented only after the Ways sheet (and anything nested in it) has finished closing. The walk is a full-screen cover, presented only after the overview has finished closing.

```swift
        .sheet(isPresented: $coordinator.honorWaysPresented, onDismiss: coordinator.promotePendingHonorWay) {
            HonorWaysSheet(
                ownWalks: coordinator.homeViewModel.walks,
                importState: coordinator.honorImportState,
                onChoose: { coordinator.openOverview(for: $0) },
// …
        .sheet(item: $coordinator.honorOverviewWay, onDismiss: coordinator.handleOverviewDismiss) { way in
            NavigationStack {
                HonorOverviewView(
                    way: way,
                    importState: coordinator.honorImportState,
                    onBegin: { coordinator.startHonor(way: way) },
                    onClose: { coordinator.honorOverviewWay = nil },
```
> Pilgrim/Scenes/Root/MainTabView.swift:64-80@7c200bf

The catalog is a sheet nested inside the Ways sheet, with the same `onChoose` handed through. The route page is pushed inside the catalog's own `NavigationStack`:

```swift
            .sheet(isPresented: $showPilgrimages) {
                // Same handoff as the own-walk picker: choosing dismisses the
                // parent sheet, which takes this nested one with it.
                PilgrimageCatalogView(onChoose: onChoose)
            }
```
> Pilgrim/Scenes/Honor/HonorWaysSheet.swift:107-111@7c200bf

```swift
        NavigationStack {
            content
                .navigationTitle("Pilgrimages")
                .navigationBarTitleDisplayMode(.inline)
// …
                .navigationDestination(item: $opened) { entry in
                    PilgrimageRouteView(entry: entry,
                                        release: catalogService.catalog?.release ?? "",
                                        onChoose: onChoose)
                }
        }
        .task { await load() }
```
> Pilgrim/Scenes/Honor/PilgrimageCatalogView.swift:74-77,90-96@7c200bf

A stage row's tap ends in `onChoose(way)`, which is the coordinator's `openOverview(for:)`. With the Ways sheet presented it parks the Way and dismisses the Ways sheet; the catalog sheet and its pushed route page go with it. The park is promoted on the Ways sheet's `onDismiss`:

```swift
    func openOverview(for way: Way) {
// …
        if honorWaysPresented || completedSnapshot != nil {
            pendingHonorWay = way
            honorWaysPresented = false
        } else {
            honorOverviewWay = way
            gather(way)
        }
    }
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:246,251-258@7c200bf

```swift
    func promotePendingHonorWay() {
        if let way = pendingHonorWay {
            pendingHonorWay = nil
            honorOverviewWay = way
            gather(way)
        }
    }

    func startHonor(way: Way) {
        pendingStartWay = way
        honorOverviewWay = nil
    }
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:312-323@7c200bf

`gather` sets `.ready` at once for anything but a share, so a stage overview's import line is empty and Begin is enabled (unless a link's fetch is under way, §6.6):

```swift
            guard case .share = way.source else { self.honorImportState = .ready; return }
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:270@7c200bf

Begin on a stage opens the morning card (a third sheet, over the overview). Its "walk" closes the card and calls `onBegin`, which is `startHonor`: the overview closes, and its `onDismiss` starts the walk screen:

```swift
    func handleOverviewDismiss() {
// …
        if honorOverviewWay == nil {
            gatheringCancellable = nil
            honorImportState = .idle
        }
        if let way = pendingStartWay {
            pendingStartWay = nil
            startWalk(mode: .honor, way: way)
        }
    }
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:325,330-338@7c200bf

So the visible order is: Ways sheet → catalog sheet over it → route page pushed in the catalog sheet → (stage tap) both sheets slide away → overview sheet → (Begin) morning card sheet over the overview → ("walk") card and overview slide away → walk cover. The package manager learns what a walk is in `chooseWay()`:

```swift
    func chooseWay() {
        // Downloading a second route, Replace, Update, and Remove are all
        // refused while a walk is on; this is where the manager learns what
        // "on" means.
        Task { @MainActor in
            PilgrimagePackageManager.shared.isWalkActive = { [weak self] in self?.activeWalkViewModel != nil }
            PilgrimageTilesManager.shared.isWalkActive = { [weak self] in self?.activeWalkViewModel != nil }
            PilgrimagePackageManager.shared.tiles = PilgrimageTilesManager.shared
        }
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:195-203@7c200bf

`activeWalkViewModel` is set by `startWalk`, which runs as the overview closes, so the guard is on from the walk screen's first frame, before its Start (P2 owns the guard; flow gap 1).

**What dismisses what on iOS:**

| Action | Result |
|---|---|
| Ways sheet "Close" or swipe down | the Ways sheet (and anything nested) closes; Path shows |
| Catalog "Close" (`dismiss()`) or swipe down | the catalog sheet closes; the Ways sheet shows again |
| Route page's system back button | pops to the catalog list (no "Close" on the route page) |
| Swipe down on the route page | the whole catalog sheet closes (it is one sheet); the Ways sheet shows |
| Stage row tap (stage on the phone) | both sheets close; the stage's overview opens |
| Stage row tap (not on the phone) | "Download this route first?" alert over the route page |
| Overview "Close" | the overview closes; Path shows (the Ways sheet does not come back) |
| Overview Begin (stage) | the morning card opens over the overview |
| Morning card "walk" | card and overview close; the walk screen opens before its Start |
| Morning card swipe down | the card closes; the overview stays |

None of these sheets disables interactive dismissal, including the route page during a download (the download keeps running; §4.10).

#### 1.2 Android at `0defff85`

The Ways sheet and the picker are dialog routes over Path; the overview is a full-screen composable route; Begin replaces the overview with the walk screen before its Start:

```kotlin
private fun androidx.navigation.NavGraphBuilder.honorRoutes(navController: NavHostController) {
    honorSheet(Routes.HONOR_WAYS) {
        org.walktalkmeditate.pilgrim.ui.honor.HonorWaysSheetRoute(
            onClosed = { navController.popBackStack(Routes.HONOR_WAYS, inclusive = true) },
            onOpenOwnWalks = {
                navController.navigate(Routes.HONOR_OWN_WALKS) { launchSingleTop = true }
            },
            onOpenOverview = navController::openStoredWayOverview,
        )
    }
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHost.kt:1023-1032@0defff85

```kotlin
internal fun NavController.openStoredWayOverview(wayId: String) {
    val replaces = listOf(Routes.HONOR_WAYS, Routes.HONOR_OVERVIEW_PATTERN).firstOrNull(::hasBackStackEntry)
    navigate(Routes.honorOverview(HonorWayChoice.Stored(wayId))) {
        replaces?.let { popUpTo(it) { inclusive = true } }
        launchSingleTop = true
    }
}
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHost.kt:1086-1092@0defff85

```kotlin
internal fun NavController.beginHonorWalk(way: HonorWayChoice) {
    navigate(Routes.activeWalk(WalkMode.Honor, way)) {
        popUpTo(Routes.HONOR_OVERVIEW_PATTERN) { inclusive = true }
        launchSingleTop = true
    }
}
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHost.kt:1108-1113@0defff85

A Ways-sheet action slides the sheet down before it navigates (`hideThen`); the picker shows in place of the Ways sheet, not over it, and the Ways sheet slides back up when the picker closes (`HonorSheetHost`, `HonorWaysSheet.kt:315-357@0defff85`). That swap stands in for iOS's nested sheet; Stage 21-1 already ships it for the picker.

#### 1.3 What U37 builds

- **Two new dialog routes**, both through `honorSheet` and `HonorSheetHost`, stacked above `HONOR_WAYS` exactly as `HONOR_OWN_WALKS` is: a catalog route (say `honor_pilgrimages`) and a route-page route carrying the route id (say `honor_pilgrimage?routeId={routeId}`; the id passes `[a-z0-9-]{1,64}`, so `Uri.encode` is a no-op but keep it). The third section's row calls `hideThen(onOpenPilgrimages)`, as "Walk one of yours again" does.
- **Catalog "Close" and swipe-down** pop the catalog (`popBackStack(<catalog>, inclusive = true)`); the Ways sheet slides back up, as iOS's catalog dismiss reveals it.
- **The route page** is a second sheet over the catalog: the catalog slides down, the route page slides up (the picker's swap; iOS pushes within one sheet; a recorded platform equivalent, §16 A-1). Its leading control goes back to the catalog; **system Back goes back to the catalog** (iOS's back button); **a swipe-down closes both** (iOS's swipe dismisses the one sheet holding both), so the route page's `onDismissed` pops through the catalog route. ModalBottomSheet sends Back and a swipe through the same `onDismissRequest`, so the route page needs `shouldDismissOnBackPress = false` plus a `BackHandler`, or equivalent, to tell them apart.
- **A stage row** that has its Way calls `hideThen { openStoredWayOverview(stageWayId) }`. `openStoredWayOverview` pops `HONOR_WAYS` inclusive, which takes the catalog and route page above it, so Close on the stage's overview lands on Path, as iOS's does.
- **The morning card** is a sheet inside the overview route (local state, `rememberSaveable`), not a route of its own: "walk" slides the card down, then calls the existing `onBegin(overview.choice)` → `beginHonorWalk`. Begin's navigation is unchanged; the walk screen's Start still starts the walk (`BeginHonorWalk`, unchanged by doors). The stage arrives as `HonorWayChoice.Stored(stageWayId)`; the door-stamp check the plan describes (KTD "the stage seen at the door is the stage walked") rides that choice and is P2's to specify.
- **Links** (§11): add the two routes to the link routing exactly where the picker sits. `honorLinkScreen(...).waysSheetUp` must read true with either route on top, and `fetchedWayLanding` must answer `PRESENT` for `PATH, HONOR_WAYS, <catalog>` and `PATH, HONOR_WAYS, <catalog>, <route page>`, as it does for the picker (`PilgrimNavHostTest.kt:102-106@0defff85`). That matches iOS: a link tapped while the catalog is up shows no toast (the Ways sheet is "presented"), and when its import lands the Ways sheet, the catalog and the route page all close and the shared Way's overview opens.
- **The flag:** the two routes are registered inside `honorRoutes`, which runs only when `honorEnabled` (`PilgrimNavHost.kt:645-647@0defff85`), so with the flag off no door exists.
- **Orientation:** both apps are portrait-only (iOS `UISupportedInterfaceOrientations` on iPhone is portrait; Android's activity is `screenOrientation="portrait"`), so "rotation" means a configuration change (dark mode, font scale, locale). ViewModels and `rememberSaveable` state carry every screen through one; nothing in this cluster may reload or re-download on one.

---

### 2. The Ways sheet's third section

#### 2.1 iOS

The third of four sections, between "Your own walks" and "From a shared walk". One nav row, a caption header and a caption footer:

```swift
                Section {
                    Button { showPilgrimages = true } label: {
                        settingNavRow(label: "Walk a pilgrimage")
                    }
                } header: {
                    Text("A pilgrimage").font(Constants.Typography.caption)
                } footer: {
                    Text("A route from the open-pilgrimages dataset, walked one stage at a time.")
                        .font(Constants.Typography.caption)
                }
```
> Pilgrim/Scenes/Honor/HonorWaysSheet.swift:46-55@7c200bf

- The row is the same `settingNavRow` as "Walk one of yours again": body text with a trailing chevron, a plain `Button` with no hint. The section is always there; it has no flag of its own and no empty state.

```swift
func settingNavRow(label: String, detail: String? = nil) -> some View {
    HStack {
        Text(label)
            .font(Constants.Typography.body)
            .foregroundColor(.ink)
        Spacer()
// …
        Image(systemName: "chevron.right")
            .font(Constants.Typography.caption)
            .foregroundColor(.fog)
    }
```
> Pilgrim/Scenes/Settings/SettingsCards/SettingsCardStyle.swift:74-90@7c200bf

  No leading icon: body ink, a caption-sized fog chevron at the trailing edge. Android's `SettingNavRow` is Stage 21-1's stand-in for it.
- It opens the catalog as a nested sheet (§1.1). Nothing is fetched until the catalog appears.
- Neither header nor footer sets a colour, so both take the list's default secondary colour, as the other three sections' do. Whether iOS uppercases the header ("A PILGRIMAGE") is the open iPhone check Stage 21-1 already carries for this sheet (`docs/parity/2026-09-29-honor-own-walk-port.md:242`, `docs/qa/2026-10-01-honor-own-walk-vertical-qa.md:93`); the answer covers this header, the catalog's group headers and the route page's "Stages" alike.
- The same PR changed the paste section's footer to "A walk someone shared with you, from walk.pilgrimapp.org." Android already ships that line (`honor_ways_paste_footer`, `strings.xml:1292@0defff85`).
- Slice two also made the shared rows' date slot read `WayStageLine.line(for: way) ?? <medium date>` (`HonorWaysSheet.swift:132-133@7c200bf`). That branch is unreachable: the rows are filtered to `.share` Ways two lines earlier (`HonorWaysSheet.swift:114@7c200bf`), and a share never has a stage. Android's shared rows need no change.

#### 2.2 Android at `0defff85`

```kotlin
 *  3. "A pilgrimage" (Stage 21-2, not shown yet);
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/honor/HonorWaysSheet.kt:74@0defff85

```kotlin
        HonorSheetSection(header = stringResource(R.string.honor_ways_own_header)) {
            // iOS's Button with no label or hint of its own (F §17.2).
            SettingNavRow(
                label = stringResource(R.string.honor_ways_own_row),
                onClick = onWalkOneOfYours,
                modifier = Modifier.fillMaxWidth(),
                role = Role.Button,
                onClickLabel = null,
            )
        }
        HonorSheetSection(
            header = stringResource(R.string.honor_ways_paste_header),
            footer = stringResource(R.string.honor_ways_paste_footer),
        ) {
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/honor/HonorWaysSheet.kt:96-109@0defff85

**U37:** insert a third `HonorSheetSection(header = "A pilgrimage", footer = "A route from the open-pilgrimages dataset, walked one stage at a time.")` holding a `SettingNavRow("Walk a pilgrimage", role = Role.Button, onClickLabel = null)` between these two, and an `onWalkAPilgrimage` callback wired as `hideThen(onOpenPilgrimages)` in `HonorWaysSheetRoute`. TalkBack reads it as iOS's VoiceOver does: "Walk a pilgrimage, button", the header a heading (`HonorSheetSection` already marks it).

---

### 3. The catalog

#### 3.1 The row's words: `PilgrimageCatalogModel`

**The card line.** Country (when non-empty), the route's distance through `StatsHelper`, then either the stage count or, for the installed route, the ledger's progress line; joined by `" · "` (U+00B7 with a space each side):

```swift
    static func card(entry: PilgrimageCatalogEntry, ledger: PilgrimageLedger?, isInstalled: Bool) -> String {
        var parts: [String] = []
        if let country = entry.country, !country.isEmpty { parts.append(country) }
        parts.append(StatsHelper.string(for: entry.distanceKm * 1000, unit: UnitLength.meters, type: .distance))
        if isInstalled {
            parts.append(PilgrimageLedger.progressLine(ledger: ledger, stageCount: entry.stageCount))
        } else {
            parts.append(entry.stageCount == 1 ? "1 stage" : "\(entry.stageCount) stages")
        }
        return parts.joined(separator: " · ")
    }
```
> Pilgrim/Scenes/Honor/PilgrimageCatalogView.swift:10-20@7c200bf

- `country` is the index's raw code ("ES", "JP"), capped at `WayImporter.maxLabelCharacters` by the catalog parse; never mapped to a name.
- The distance is the index's `distanceKm` in the walker's distance unit (§5): "ES · 764 km · 33 stages", "JP · 154.5 km · 5 stages", "JP · 365.7 km · 14 stages" with the live index; miles read "ES · 474.73 mi · 33 stages".
- The stage count is a Swift interpolation of an `Int`: no grouping (≤ 200 anyway). "1 stage" is the only singular.
- For the installed route the count is replaced by `progressLine` (below), so an installed route never-walked still reads "… · 33 stages".

**The ledger's progress line** (also under the route page's next row, §4.4):

```swift
    static func progressLine(ledger: PilgrimageLedger?, stageCount: Int) -> String {
        guard let ledger, !ledger.stages.isEmpty || (ledger.carriedKm ?? 0) > 0 else {
            return stageCount == 1 ? "1 stage" : "\(stageCount) stages"
        }
        let walked = StatsHelper.string(for: ledger.totalKmWalked * 1000, unit: UnitLength.meters, type: .distance)
        guard let next = ledger.next(stageCount: stageCount) else {
            return "you have walked the whole way · \(walked)"
        }
        return "stage \(next.index + 1) of \(stageCount) · \(walked) walked"
    }
```
> Pilgrim/Models/Honor/PilgrimageLedger.swift:87-96@7c200bf

- "stage N" is the **next** stage (1-based), not the count walked: after stage 1 is completed it reads "stage 2 of 33 · 24.2 km walked" (the iOS test asserts "stage 2 of 33", §13).
- The kilometres are `totalKmWalked` (entries' `kmWalked`, finite only, plus a finite `carriedKm`) through `StatsHelper`: "112 km walked", or "0 km walked" if every entry is zero.
- The all-walked form has no "walked" suffix: "you have walked the whole way · 764 km".
- A ledger with no entries and no carried kilometres falls back to the bare count; a negative `carriedKm` also falls back (`(carriedKm ?? 0) > 0`).
- `stageCount` here is the **catalog entry's** count on both screens (`PilgrimageCatalogView.swift:184-185`, `PilgrimageRouteView.swift:193,250,253`), not the installed `route.json`'s; they differ only for an installed route at an older release whose count changed (§17 D-7).

```swift
    func next(stageCount: Int) -> Next? {
        guard stageCount > 0 else { return nil }
        for index in 0..<stageCount where stages[String(index)]?.completed != true {
            return Next(index: index, resumeFrac: stages[String(index)]?.stoppedAtFrac)
        }
        return nil
    }
```
> Pilgrim/Models/Honor/PilgrimageLedger.swift:78-84@7c200bf

**The badge** (PR #85 replaced the card's "updated" clause with it):

```swift
    static func installBadge(isInstalled: Bool, hasUpdate: Bool) -> InstallBadge? {
        guard isInstalled else { return nil }
        return hasUpdate
            ? InstallBadge(symbol: "arrow.down.circle.fill", label: "update ready", tint: .stone)
            : InstallBadge(symbol: "checkmark.circle.fill", label: "on your phone", tint: .moss)
    }
```
> Pilgrim/Scenes/Honor/PilgrimageCatalogView.swift:35-40@7c200bf

The words are never drawn: `label` is the glyph's VoiceOver label only. Android equivalents in the bundled extended icon set: `Icons.Filled.ArrowCircleDown` (stone) and `Icons.Filled.CheckCircle` (moss; already used in `InteractiveShareSection.kt:527@0defff85`), each with `contentDescription` = the label.

**The sparse note:**

```swift
    static func sparseNote(for entry: PilgrimageCatalogEntry) -> String? {
        entry.sparse ? "few places marked yet" : nil
    }
```
> Pilgrim/Scenes/Honor/PilgrimageCatalogView.swift:46-48@7c200bf

`sparse` is the index's flag, defaulting to false when absent; live, only the Camino Francés is sparse.

**`hasUpdate`** compares the cached `installed` state (read in `load()`) with the in-memory catalog's release. It is never the package manager's `hasUpdate`, which re-reads `route.json`:

```swift
    private func hasUpdate(for entry: PilgrimageCatalogEntry) -> Bool {
        installed.map { $0.routeId == entry.id && $0.release != (catalogService.catalog?.release ?? "") } ?? false
    }
```
> Pilgrim/Scenes/Honor/PilgrimageCatalogView.swift:200-202@7c200bf

A string inequality, not an ordering: a catalog served from an older cache (offline) whose release trails the installed one also reads "update ready", and Update then downloads the older release (§17 D-8).

#### 3.2 The screen's three states

```swift
    @ViewBuilder
    private var content: some View {
        if isLoading, catalogService.catalog == nil {
            // SwiftUI's, not the project's own ProgressView.
            SwiftUI.ProgressView()
                .tint(.stone)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if let routes = catalogService.catalog?.routes, !routes.isEmpty {
            VStack(alignment: .leading, spacing: 0) {
                // A retry can fail while the in-memory catalog still holds an
                // earlier success — the list branch above wins, so this is
                // the only place that failure would ever reach the pilgrim.
                if let failure {
                    Text(PilgrimageCopy.line(for: failure))
                        .font(Constants.Typography.caption)
                        .foregroundColor(.rust)
                        .padding(.horizontal, Constants.UI.Padding.normal)
                        .padding(.top, Constants.UI.Padding.small)
                }
                List {
                    ForEach(catalogService.catalog?.groups ?? []) { group in
                        Section {
                            ForEach(group.entries) { entry in
                                Button { opened = entry } label: { row(entry) }
                            }
                        } header: {
                            if let name = group.name {
                                Text(name).font(Constants.Typography.caption)
                            }
                        }
                    }
                }
```
> Pilgrim/Scenes/Honor/PilgrimageCatalogView.swift:99-130@7c200bf

1. **Spinner:** only while a load runs **and** the service holds no catalog in memory. A stone indeterminate spinner, centred in the whole sheet under the bar. Android: `CircularProgressIndicator(color = pilgrimColors.stone)` centred.
2. **List:** whenever the in-memory catalog has at least one route, during a reload too (no spinner over a list). Above the list, a rust caption line when the last load threw (§3.4). One section per group, in the catalog's `groups` order; a group with no name (the loose routes, or the single group of an index without `pilgrimages`) has no header. Rows are buttons.
3. **Unreachable:** everything else, which includes a parsed catalog with **zero** routes:

```swift
    private var unreachable: some View {
        VStack(spacing: Constants.UI.Padding.normal) {
            Text(PilgrimageCopy.line(for: failure ?? .catalogUnreachable))
                .font(Constants.Typography.body)
                .foregroundColor(.fog)
                .multilineTextAlignment(.center)
            Button("try again") { Task { await load(force: true) } }
                .font(Constants.Typography.button)
                .foregroundColor(.stone)
                .frame(minHeight: 44)
        }
        .padding(Constants.UI.Padding.big)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
```
> Pilgrim/Scenes/Honor/PilgrimageCatalogView.swift:150-163@7c200bf

The line is always "the routes are out of reach right now": `load` only ever throws `.catalogUnreachable` (§3.4), and a nil failure falls back to it. Body, fog, centred; "try again" in the button face (Lato Bold 17), stone, at least 44 pt tall; the pair 16 apart, 24 padding, centred in the sheet. A retry with no in-memory catalog shows the spinner while it runs, then the list or this view again.

**Android:** the sheet's content area must fill the sheet's height for the spinner and the unreachable view to centre; `HonorSheetScaffold`'s scrolling column doesn't, so the catalog uses `scrollable = false` (as the picker does) with a `LazyColumn` for the list and a `fillMaxSize` box for the other two states.

#### 3.3 The row

```swift
        HStack(alignment: .top, spacing: Constants.UI.Padding.normal) {
            coverPlate(entry)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: Constants.UI.Padding.xs) {
                    Text(entry.name)
                        .font(Constants.Typography.body)
                        .foregroundColor(.ink)
                    if let badge {
                        Image(systemName: badge.symbol)
                            .font(.system(size: 13))
                            .foregroundColor(badge.tint)
                            .accessibilityLabel(badge.label)
                    }
                }
                Text(PilgrimageCatalogModel.card(entry: entry, ledger: ledgers[entry.id],
                                                 isInstalled: isInstalled))
                    .font(Constants.Typography.caption)
                    .foregroundColor(.fog)
                if let sparseNote = PilgrimageCatalogModel.sparseNote(for: entry) {
                    Text(sparseNote)
                        .font(Constants.Typography.caption)
                        .foregroundColor(.fog.opacity(0.7))
                }
            }
        }
```
> Pilgrim/Scenes/Honor/PilgrimageCatalogView.swift:170-194@7c200bf

```swift
    private func coverPlate(_ entry: PilgrimageCatalogEntry) -> some View {
        RoundedRectangle(cornerRadius: Constants.UI.CornerRadius.small)
            .fill(Color.parchmentSecondary)
            .frame(width: 44, height: 44)
            .overlay(
                Text(entry.name.prefix(1))
                    .font(Constants.Typography.heading)
                    .foregroundColor(.stone)
            )
            .accessibilityHidden(true)
    }
```
> Pilgrim/Scenes/Honor/PilgrimageCatalogView.swift:206-216@7c200bf

Layout, in Android tokens:

| Element | iOS | Android |
|---|---|---|
| Row | `HStack(.top, spacing: 16)`, plate then text column | `Row(verticalAlignment = Top, spacedBy(PilgrimSpacing.normal))` |
| Plate | 44×44, corner 8 (`CornerRadius.small`), `parchmentSecondary`, the name's first **grapheme** in `heading` (Cormorant SemiBold 17), stone, centred; hidden from VoiceOver | 44.dp box, `PilgrimCornerRadius.small`, the initial in `pilgrimType.heading`/stone, `clearAndSetSemantics {}`. Fill: see A-2 below |
| Text column | `VStack(.leading, spacing: 2)` | `Column(spacedBy(2.dp))` |
| Name line | name (body, ink) then the badge glyph, 4 apart | `Row(spacedBy(PilgrimSpacing.xs), CenterVertically)`; the glyph 13.dp |
| Badge glyph | `.system(size: 13)`: a fixed size, **does not** scale with Dynamic Type | a fixed 13.dp icon (icons don't follow font scale either) |
| Card line | caption (Lato 12), fog | `pilgrimType.caption`, `pilgrimColors.fog` |
| Sparse note | caption, fog at 0.7 | `pilgrimColors.fog.copy(alpha = 0.7f)` |

- `entry.name.prefix(1)` is one extended grapheme cluster ("Ō", "é" with a combining accent, a flag). Kotlin's `take(1)` splits a surrogate pair or a combining sequence; use `BreakIterator.getCharacterInstance()` (first boundary) instead. Every live name starts with an ASCII letter, so this is an edge only.
- **A-2 (recorded difference):** on iOS the plate's `parchmentSecondary` stands on the list row's system background (white in light mode), so it shows as a beige square. Android's sections are `parchmentSecondary` groups (Stage 21-1's gate row "the Ways sheet and the picker wear the app's parchment"), on which a `parchmentSecondary` plate disappears. Fill the plate with `pilgrimColors.parchment` so it keeps the contrast iOS has.
- **The row as one element:** a SwiftUI `Button` whose label is a stack reads as one VoiceOver element: "Camino de Santiago (Frances), on your phone, ES · 764 km · stage 5 of 33 · 112 km walked, few places marked yet, button" (the plate is hidden; the badge contributes its label). Android: one `clickable(role = Role.Button, onClickLabel = null)` row; the children merge, the plate is cleared, the icon's `contentDescription` is the badge label. The "·" separators are read as they are on iOS (pilgrim-ios #108's family; not refiled).
- **Dynamic Type:** every text uses `Font.custom(_:size:)`, which scales relative to body; the 44×44 plate does not grow, so at the largest sizes the initial can overflow it. Android's `sp` text and `dp` plate behave the same way.

#### 3.4 Loading, reappearance, retry, row tap

```swift
    private func load(force: Bool = false) async {
        isLoading = true
        failure = nil
        do {
            let catalog = try await catalogService.load(force: force)
            installed = packages.installed()
            // `uniquingKeysWith`, not `uniqueKeysWithValues:` — `parse` already
            // drops a repeated id, but this map must never be the thing that
            // traps if that guarantee is ever loosened.
            ledgers = Dictionary(catalog.routes.compactMap { entry in
                ledgerStore.load(routeId: entry.id).map { (entry.id, $0) }
            }, uniquingKeysWith: { first, _ in first })
        } catch {
            failure = (error as? PilgrimageError) ?? .catalogUnreachable
        }
        isLoading = false
    }
```
> Pilgrim/Scenes/Honor/PilgrimageCatalogView.swift:218-234@7c200bf

- **First appearance:** the `NavigationStack`'s `.task` runs `load()` (not forced). With the 24 h cache fresh this is a disk read; otherwise a fetch (P1).
- **Reappearance** (popping back from a route page): the `List`'s `onAppear` runs `load()` again, **not forced**, but never on the list's first appearance:

```swift
                .onAppear {
                    if hasAppearedOnce {
                        Task { await load() }
                    } else {
                        hasAppearedOnce = true
                    }
                }
```
> Pilgrim/Scenes/Honor/PilgrimageCatalogView.swift:137-143@7c200bf

  That reload is what makes a just-downloaded or just-removed route read correctly on the list (`installed` and `ledgers` are re-read). Android: the catalog route's lifecycle returning to RESUMED after the route page pops is the trigger; the first RESUMED is not. A configuration change returning to RESUMED may reload too (a cheap cache read); that is harmless.
- **"try again"** is the only forced load (`load(force: true)`), and it exists only in the unreachable view.
- **`load` throws only when the fetch fails with no disk cache at all.** With any cache on disk, a failed fetch silently serves it:

```swift
        do {
            let fresh = try Self.parse(try await fetchIndex())
            writeCache(Cached(fetchedAt: now(), catalog: fresh))
            catalog = fresh
            return fresh
        } catch {
            if let cached {
                catalog = cached.catalog
                return cached.catalog
            }
            throw PilgrimageError.catalogUnreachable
        }
```
> Pilgrim/Models/Honor/PilgrimageCatalogService.swift:160-171@7c200bf

  So the rust line over the list appears only when the in-memory catalog has routes, the disk cache is missing or unreadable, and a reload's fetch fails (for example a cache write that failed on a full disk). Port it anyway: it is the one place such a failure would reach the walker.
- **The catalog is the service's, not the screen's.** `catalog` is `@Published` on the app-wide singleton (`PilgrimageCatalogService.swift:98@7c200bf`), so the second opening of the catalog in a process shows the list at once, with no spinner, while it reloads. Android's catalog service (U32) must hold the parsed catalog in an app-scoped `StateFlow`, and the catalog ViewModel reads it rather than keeping its own copy.
- **Row tap** sets `opened = entry`, which pushes the route page with the entry by value and the in-memory catalog's release at that moment (`catalogService.catalog?.release ?? ""`). Android passes the route id; the route page's ViewModel reads the entry and the release from the service's in-memory catalog when it starts (§4.11 for process death).
- `ledgers` holds every listed route's ledger, but only the installed route's is read (the card line ignores the ledger for any other route).
- The catalog draws nothing from the package manager's phase: a download in flight shows no progress here, and a download that ends while the list is showing leaves the badge stale until the next reload (§17 D-2).

**The bar:** "Pilgrimages" as the inline title, drawn by the app in `heading`/ink (slice three's `b215c92` made every Honor screen draw its own title), and "Close" leading in the button face, stone. Android: `HonorSheetScaffold(title = "Pilgrimages", onClose = ...)` as is.

---

### 4. The route page

#### 4.1 What it holds, and the page's tree

The page is built from the catalog entry (by value) and the release the catalog held when the row was tapped. Its own state: the installed snapshot, the route (the installed `route.json`, else the preview), the ledger, a failure, the preview's two states, three alert flags, and the redraw notice flag (slice three adds the maps row's three fields):

```swift
    @State private var route: PilgrimageRoute?
    @State private var ledger: PilgrimageLedger?
    @State private var installed: PilgrimagePackageManager.Installed?
    @State private var failure: PilgrimageError?
    /// The stage list is fetched separately when nothing is downloaded yet;
    /// its own two states, so a failed preview does not read as a failed
    /// download.
    @State private var isLoadingStages = false
    @State private var stagesFailure: PilgrimageError?
    @State private var confirmReplace = false
    @State private var confirmRemove = false
    @State private var showRedrawNotice = false
    @State private var promptDownload = false
```
> Pilgrim/Scenes/Honor/PilgrimageRouteView.swift:86-98@7c200bf

```swift
    private var isInstalled: Bool { installed?.routeId == entry.id }
// …
    private var hasUpdate: Bool { installed.map { $0.routeId == entry.id && $0.release != release } ?? false }
    private var stages: [PilgrimageRouteStage] { route?.stages ?? [] }

    var body: some View {
        List {
            Section { header } footer: { statusFooter }
            if isInstalled {
                Section { nextRow }
            }
            Section {
                stageSection
            } header: {
                Text("Stages").font(Constants.Typography.caption)
            }
        }
```
> Pilgrim/Scenes/Honor/PilgrimageRouteView.swift:102,107-121@7c200bf

Three sections, top to bottom:
1. **Header** (no section header): the summary, the sparse note, the card line, the download button, and (slice three) the maps row; its **section footer** carries the download progress, the failure and the redraw notice (§4.3).
2. **The next row**, only when this route is the installed one (§4.4).
3. **"Stages"** (caption header) and the stage list, which stands whether or not the route is on the phone (§4.5).

Android: one `LazyColumn` (a route can have 200 stages) with `HonorSheetSection`-styled groups: a header-less group with a caption footer, an optional header-less group, and "Stages". `HonorSheetSection` needs its `header` to become nullable for the first two.

#### 4.2 The header section

```swift
    private var header: some View {
        VStack(alignment: .leading, spacing: Constants.UI.Padding.small) {
            if let summary = route?.summary ?? entryFallbackSummary {
                Text(summary).font(Constants.Typography.body).foregroundColor(.ink)
            }
            // Directly under the summary: what the route can promise, before
            // the button that offers to download it.
            if let sparseNote = PilgrimageCatalogModel.sparseNote(for: entry) {
                Text(sparseNote)
                    .font(Constants.Typography.caption)
                    .foregroundColor(.fog.opacity(0.7))
            }
            // No install badge here: the button directly below already says
            // "On your phone" or "Update" in words, and the page has room.
            Text(PilgrimageCatalogModel.card(entry: entry, ledger: ledger, isInstalled: isInstalled))
                .font(Constants.Typography.caption)
                .foregroundColor(.fog)
            downloadButton
            if isInstalled && !stageWays.isEmpty {
                PilgrimageMapsRow(routeId: entry.id, stages: stageWays,
                                  estimateBytes: mapsEstimateBytes, status: mapsStatus, tiles: tiles)
                    .disabled(PilgrimageRouteModel.mapsRowIsHeld(packagePhase: packages.phase))
            }
        }
    }

    private var entryFallbackSummary: String? {
        entry.tradition.map { "\($0.capitalized) · \(entry.region ?? "")" }
    }
```
> Pilgrim/Scenes/Honor/PilgrimageRouteView.swift:179-207@7c200bf

- **Summary:** `route?.summary` (the installed `route.json`'s, else the preview's; trimmed and capped at 600 by the importer, P1), else the fallback; with neither, no line. Body, ink.
- **Fallback:** `"<Tradition> · <region>"` with Foundation's `capitalized` (every word's first letter upper-cased, **the rest lower-cased**; probe: `"christian"` → `"Christian"`, `"shinto-buddhist"` → `"Shinto-Buddhist"`, `"CAMINO"` → `"Camino"`, `"o'brien"` → `"O'brien"`). A nil region leaves `"Buddhist · "` with a trailing separator. With no tradition, no fallback at all. Live routes all carry a summary, so the fallback shows only until the preview lands, or when it fails. Kotlin needs a word-capitalizer matching Foundation (split on whitespace and on a hyphen; `replaceFirstChar` alone keeps the tail's case).
- **Sparse note:** the catalog's (§3.1), caption, fog at 0.7.
- **Card line:** the catalog's card (§3.1), caption, fog; for the installed route it carries the progress line. No badge on this page.
- **Download button** (§4.6).
- **Maps row** (Stage 21-3 seam, §9): drawn only when installed and the stage Ways loaded; held while the package downloads.
- Spacing 8 (`PilgrimSpacing.small`) between all of them.

#### 4.3 The status footer: progress, failure, redraw notice

```swift
    @ViewBuilder
    private var statusFooter: some View {
        VStack(alignment: .leading, spacing: Constants.UI.Padding.xs) {
            if case .downloading(let done, let total) = packages.phase {
                // `total` counts `route.json` plus every stage; the pilgrim
                // only cares about stages, so both sides drop the one file
                // that isn't a stage.
                Text("stage \(max(done - 1, 0)) of \(total - 1)").font(Constants.Typography.caption).foregroundColor(.fog)
            }
            if let failure {
                Text(PilgrimageCopy.line(for: failure)).font(Constants.Typography.caption).foregroundColor(.rust)
            }
            if showRedrawNotice {
                Text(PilgrimageRouteModel.redrawNotice).font(Constants.Typography.caption).foregroundColor(.fog)
            }
        }
    }
```
> Pilgrim/Scenes/Honor/PilgrimageRouteView.swift:225-241@7c200bf

**Progress** comes from the package manager's app-wide `phase`, never from the page's own state, so any route page shows any download's progress, including one started by an earlier page (§4.10):

```swift
    enum Phase: Equatable {
        case idle
        /// `total` counts `route.json` plus every stage file.
        case downloading(done: Int, total: Int)
        case failed(PilgrimageError)
    }
```
> Pilgrim/Models/Honor/PilgrimagePackageManager.swift:18-23@7c200bf

The manager's sequence for a 33-stage route, and what the footer reads:

| Moment | `phase` | Footer |
|---|---|---|
| tap, before `route.json` | `.downloading(done: 0, total: 34)` | "stage 0 of 33" |
| `route.json` landed | `(1, 34)` | "stage 0 of 33" |
| stage index *i* landed | `(i + 2, 34)` | "stage *i*+1 of 33" |
| last stage landed, commit running | `(34, 34)` | "stage 33 of 33" (held through the commit) |
| committed | `.idle` | nothing |
| failed | `.failed(e)` | nothing from the phase; the page's own `failure` line, if this page started it |
| cancelled | `.idle` | nothing |

```swift
        phase = .downloading(done: 0, total: entry.stageCount + 1)
```
> Pilgrim/Models/Honor/PilgrimagePackageManager.swift:157@7c200bf

```swift
            phase = .downloading(done: 1, total: total)
            for index in 0..<fetched.route.stageCount {
// …
                phase = .downloading(done: index + 2, total: total)
            }
```
> Pilgrim/Models/Honor/PilgrimagePackageManager.swift:179-180,189-190@7c200bf

- `.failed` is published but **no screen renders it**; only the page whose own `install()` threw shows a failure (§4.10).
- The numbers are `Int` interpolations in a `LocalizedStringKey` (`%lld`, no grouping), "stage" lower-case, caption, fog.

**Failure** is the page's own `failure`, set by its own install or remove, through `PilgrimageCopy`:

```swift
    static func line(for error: PilgrimageError) -> String {
        switch error {
        case .notWalkable: return "this route isn't walkable yet"
        case .incomplete: return "the download didn't finish"
        case .diskFull: return HonorImportCopy.line(for: .failed(.diskFull)) ?? "not enough space on this phone"
        case .walkInProgress: return "finish your walk first"
        case .catalogUnreachable: return "the routes are out of reach right now"
        case .mapTooLarge: return "more map than can be saved at once"
        }
    }
```
> Pilgrim/Models/Honor/PilgrimageWayImporter.swift:16-25@7c200bf

```swift
        case .failed(.diskFull): return "not enough space on this phone to save these voices"
```
> Pilgrim/Models/Honor/HonorImportReducer.swift:34@7c200bf

So a full disk on the route page reads "not enough space on this phone to save these voices", about a route that has no voices (§17 D-5); the `?? "not enough space on this phone"` fallback is unreachable. Android: reuse `honor_import_disk_full` (`strings.xml:1284@0defff85`) rather than a new string, so the two can never drift. `.mapTooLarge` is Stage 21-3's.

"finish your walk first" is the walk guard's copy (P2). On iOS a walker can't reach a route page mid-walk (the walk is a full-screen cover; the Ways sheet opens only from Path), so the line appears only if a walk somehow starts while this page is alive, at the commit's re-check. Android's Path also redirects into a walk in progress and disables its start button (`WalkStartScreen.kt:179-195,310@0defff85`), so the same holds; the guard's wider Android clauses (walk screen up, live rows, a Begin in flight) make it reachable at entry in a few more cases, and the copy is the same.

**The redraw notice:**

```swift
    static let redrawNotice = "the route's stages were redrawn; your kilometres are kept."
```
> Pilgrim/Scenes/Honor/PilgrimageRouteView.swift:37@7c200bf

Shown when the ledger's `redrawNoticePending` is true at a `reload()`, which then clears the flag in the ledger file at once:

```swift
    private func reload() {
        installed = packages.installed()
        if installed?.routeId == entry.id { route = installed?.route }
        stageWays = isInstalled
            ? (0..<(route?.stageCount ?? 0)).compactMap { WayStore.shared.load(id: WayStore.stageWayId(routeId: entry.id, stageIndex: $0)) }
            : []
        mapsEstimateBytes = isInstalled ? tiles.estimateBytes(for: entry.id, stages: stageWays) : 0
        refreshMapsStatus()
        ledger = ledgerStore.load(routeId: entry.id)
        if ledger?.redrawNoticePending == true {
            showRedrawNotice = true
            ledgerStore.clearRedrawNotice(routeId: entry.id)
        }
    }
```
> Pilgrim/Scenes/Honor/PilgrimageRouteView.swift:369-382@7c200bf

- `reload()` runs at the page's appearance and after each of its own installs and removes, so an Update that drops ledger entries shows the notice right after it lands, on the page that ran it. Any later opening of the page doesn't show it.
- `showRedrawNotice` is never set back to false: once shown it stays for the life of that page, through later actions on it (§17 D-12, cosmetic).
- `ledger` is re-read after the clear wrote, but the page's copy was loaded before the clear, so `ledger.redrawNoticePending` stays true in memory; nothing else reads it.
- Android: the clear is a ledger write and goes through U33's locked writer (P2). Keep `showRedrawNotice` in `SavedStateHandle`: the flag in the file is gone once shown, so a page restored after process death would otherwise lose a notice the walker may not have read.

#### 4.4 The next row

```swift
    static func nextRow(ledger: PilgrimageLedger?, stageCount: Int) -> String {
        guard let next = (ledger ?? PilgrimageLedger(routeId: "")).next(stageCount: stageCount) else {
            return "you have walked the whole way"
        }
        if next.resumeFrac != nil { return "continue from where you stopped" }
        return next.index == 0 ? "start with stage 1" : "next: stage \(next.index + 1)"
    }
```
> Pilgrim/Scenes/Honor/PilgrimageRouteView.swift:44-50@7c200bf

The rule, in order:
1. No next stage (every index `0..<stageCount` has `completed == true`, or `stageCount <= 0`): **"you have walked the whole way"**.
2. The first uncompleted stage has a `stoppedAtFrac` (any value, **0 included**): **"continue from where you stopped"**. That is stage 1 too, if it was begun and not finished.
3. It is index 0 and never begun: **"start with stage 1"**. No ledger at all lands here.
4. Otherwise: **"next: stage N"** (1-based).

```swift
    private var nextRow: some View {
        Button {
            guard let next = (ledger ?? PilgrimageLedger(routeId: entry.id)).next(stageCount: entry.stageCount)
                ?? stages.first.map({ PilgrimageLedger.Next(index: $0.index, resumeFrac: nil) }) else { return }
            open(index: next.index)
        } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text(PilgrimageRouteModel.nextRow(ledger: ledger, stageCount: entry.stageCount))
                    .font(Constants.Typography.body)
                    .foregroundColor(.ink)
                Text(PilgrimageLedger.progressLine(ledger: ledger, stageCount: entry.stageCount))
                    .font(Constants.Typography.caption)
                    .foregroundColor(.fog)
            }
        }
    }
```
> Pilgrim/Scenes/Honor/PilgrimageRouteView.swift:243-258@7c200bf

- Drawn only when this route is installed, in a section of its own between the header and "Stages".
- Two lines, 2 apart: the row's words (body, ink) over the progress line (caption, fog), so the installed route's progress shows twice on the page (in the card line too).
- The tap opens the next stage; with every stage walked it opens the **first** stage of the list. `resumeFrac` picks the copy and nothing else: the overview opens fit to the whole stage, as for any stage (the survey's "no resume camera seed", confirmed: nothing reads `resumeFrac` outside this copy).
- One VoiceOver element: "next: stage 2, stage 2 of 33 · 24.2 km walked, button".
- `stageCount` is the catalog entry's (§17 D-7).

#### 4.5 The stage list

```swift
    @ViewBuilder
    private var stageSection: some View {
        if !stages.isEmpty {
            ForEach(stages, id: \.index) { stage in
                Button { open(stage) } label: { stageRow(stage) }
            }
        } else if isLoadingStages {
            HStack {
                SwiftUI.ProgressView().tint(.stone)
                Text("reaching for the stages…")
                    .font(Constants.Typography.caption)
                    .foregroundColor(.fog)
            }
        } else if let stagesFailure {
            VStack(alignment: .leading, spacing: Constants.UI.Padding.xs) {
                Text(PilgrimageCopy.line(for: stagesFailure))
                    .font(Constants.Typography.caption)
                    .foregroundColor(.fog)
                Button("try again") { Task { await loadStagesIfNeeded(force: true) } }
                    .font(Constants.Typography.caption)
                    .foregroundColor(.stone)
                    .frame(minHeight: 44)
            }
        }
    }
```
> Pilgrim/Scenes/Honor/PilgrimageRouteView.swift:262-286@7c200bf

Four states under "Stages": the rows; "reaching for the stages…" (U+2026) after a small stone spinner, the system's default `HStack` spacing between them; the preview's failure line (caption, **fog**, not rust: "a failed preview does not read as a failed download") over a caption-sized "try again" in stone with a 44 pt minimum height; or nothing (an empty release skips the fetch, §4.9).

```swift
    private func stageRow(_ stage: PilgrimageRouteStage) -> some View {
        HStack(alignment: .top, spacing: Constants.UI.Padding.small) {
            Image(systemName: ledger?.stages[String(stage.index)]?.completed == true ? "circle.fill" : "circle")
                .font(Constants.Typography.caption)
                .foregroundColor(.stone)
                .padding(.top, 4)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text("\(stage.index + 1). \(stage.name)")
                    .font(Constants.Typography.body)
                    .foregroundColor(.ink)
                Text(PilgrimageRouteModel.stageLine(stage))
                    .font(Constants.Typography.caption)
                    .foregroundColor(.fog)
            }
        }
    }
```
> Pilgrim/Scenes/Honor/PilgrimageRouteView.swift:288-304@7c200bf

- **Circle:** filled when the ledger entry is `completed`, else hollow; a partly walked stage is hollow. The ledger is read whether or not the route is installed, so a removed route's walked stages stay filled. Caption-sized (scales with Dynamic Type), stone, 4 down from the row's top, hidden from VoiceOver (so the walked state is never spoken; §17 D-9). Android: `Icons.Filled.Circle` / `Icons.Outlined.Circle` (the latter already used, `InteractiveShareSection.kt:527@0defff85`) at the caption's size (12.sp → about 12.dp), `contentDescription = null`.
- **Title:** `"\(index + 1). \(name)"`, e.g. "1. Saint-Jean-Pied-de-Port to Roncesvalles"; body, ink. A `LocalizedStringKey` interpolation (`%lld. %@`); no grouping below 200.
- **Facts:** `WayStageFacts` (§5); caption, fog.
- Row: 8 between circle and text, 2 between the lines; one button element, "1. Saint-Jean-Pied-de-Port to Roncesvalles, 24.2 km · 1,419 m up · 7 to 9 hours · hard, button".
- Rows stay tappable while a download runs (no busy check in `open`; §4.8).

#### 4.6 The download button

```swift
    static func buttonLabel(isInstalled: Bool, hasUpdate: Bool) -> String {
        if !isInstalled { return "Download" }
        return hasUpdate ? "Update" : "On your phone"
    }
```
> Pilgrim/Scenes/Honor/PilgrimageRouteView.swift:52-55@7c200bf

```swift
    private var downloadButton: some View {
        Button {
            if isInstalled && !hasUpdate { return }
            beginInstall()
        } label: {
            Text(PilgrimageRouteModel.buttonLabel(isInstalled: isInstalled, hasUpdate: hasUpdate))
                .font(Constants.Typography.button)
                .foregroundColor(.parchment)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(isBusy || (isInstalled && !hasUpdate) ? Color.fog : Color.stone)
                .cornerRadius(Constants.UI.CornerRadius.normal)
        }
        .disabled(isBusy || (isInstalled && !hasUpdate))
    }
```
> Pilgrim/Scenes/Honor/PilgrimageRouteView.swift:209-223@7c200bf

```swift
    private var isBusy: Bool {
        if case .downloading = packages.phase { return true }
        if case .saving = tiles.phase { return true }
        return false
    }
```
> Pilgrim/Scenes/Honor/PilgrimageRouteView.swift:308-312@7c200bf

| State | Label | Fill | Enabled |
|---|---|---|---|
| not this route installed, idle | "Download" | stone | yes |
| installed, release differs from the catalog's (§17 D-8) | "Update" | stone | yes |
| installed, current | "On your phone" | fog | no |
| any download running (any route) | the label above | fog | no |
| (21-3) a maps save running | the label above | fog | no |

- The label never changes while a download runs: no "Downloading…"; the footer carries the progress.
- "Download" with another route installed asks "Replace?" first (§4.7); Update has no confirmation.
- Full width, the button face (Lato Bold 17) in parchment, 12 above and below, corner 12. The same shape as the overview's Begin, which Android already draws (`HonorOverviewScreen.kt:287-308@0defff85`: `Button`, `RoundedCornerShape(PilgrimCornerRadius.normal)`, `PaddingValues(vertical = 12.dp)`, stone/fog, parchment text). One VoiceOver element, "Download, button", "dimmed" when disabled.
- Android: in Stage 21-2, `isBusy` is the package phase only; the tiles clause is Stage 21-3's (§9).

#### 4.7 The bar, the overflow, and every alert

```swift
        .navigationTitle(entry.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            // The app draws its own titles so they follow the palette; the
            // system title would sit in the system font and label colour.
            ToolbarItem(placement: .principal) {
                Text(entry.name)
                    .font(Constants.Typography.heading)
                    .foregroundColor(.ink)
            }
            if isInstalled {
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button("Remove", role: .destructive) { confirmRemove = true }
                    } label: {
                        Image(systemName: "ellipsis")
                    }
                    // A Remove taken mid-download would be undone by the
                    // commit that lands after it; the manager refuses it too.
                    .disabled(isBusy)
                }
            }
        }
```
> Pilgrim/Scenes/Honor/PilgrimageRouteView.swift:122-144@7c200bf

- **Title:** the entry's name, `heading`, ink, centred, one line (the principal slot truncates; "Sanuki (Temples 65-88, and the return to Temple 1)" will truncate on a phone).
- **Leading:** the system back button, tinted stone by the app-wide tint (slice three `8cf8d40`), titled with the catalog's title "Pilgrimages" when it fits; UIKit shortens it to "Back" or a bare chevron when the centred title needs the room (UIKit behaviour, not Pilgrim code). Android: a leading back control in stone (`Icons.AutoMirrored.Filled.ArrowBack`, already used in `GoshuinScreen.kt:403@0defff85`) labelled "Pilgrimages" for TalkBack; an iPhone check of what VoiceOver reads is listed in §15 (U41).
- **Trailing:** only when this route is installed, an `ellipsis` menu whose one item is "Remove" (destructive, so red in the system menu); disabled while busy. The glyph has no label, so VoiceOver speaks the symbol's own name (the pilgrim-ios #108 family; Stage 21-1's precedent stands in with the name, dots read as spaces: "ellipsis"). Android: `Icons.Filled.MoreHoriz` (`ActiveWalkScreen.kt:1457@0defff85`) opening a `DropdownMenu` with "Remove" in rust.

**The alerts:**

```swift
        .alert("Replace?", isPresented: $confirmReplace) {
            Button("Replace", role: .destructive) { Task { await install(replacing: true) } }
            Button("Keep it", role: .cancel) {}
        } message: {
            Text(PilgrimagePackageManager.replaceConfirmation(routeName: installed?.route.name ?? "route"))
        }
        .alert("Remove?", isPresented: $confirmRemove) {
            Button("Remove", role: .destructive) { removeRoute() }
            Button("Keep it", role: .cancel) {}
        } message: {
            Text(PilgrimagePackageManager.removeConfirmation(routeName: entry.name))
        }
        .alert("Download this route first?", isPresented: $promptDownload) {
            // The same gate the download button uses: with another route
            // already on the phone, this is a Replace and must say so.
            Button("Download") { beginInstall() }
            Button("Not now", role: .cancel) {}
        } message: {
            Text("Its stages have to be on your phone before you can walk one.")
        }
```
> Pilgrim/Scenes/Honor/PilgrimageRouteView.swift:155-174@7c200bf

```swift
    static func replaceConfirmation(routeName: String) -> String {
        "Replace the \(routeName)? Its stages leave your phone; what you've walked of it is remembered if it comes back. Walks in your journal stay."
    }

    /// The same promise, asked about the route being let go rather than the
    /// one arriving — the Remove alert must not ask about replacing.
    static func removeConfirmation(routeName: String) -> String {
        "Remove the \(routeName)? Its stages leave your phone; what you've walked of it is remembered if it comes back. Walks in your journal stay."
    }
```
> Pilgrim/Models/Honor/PilgrimagePackageManager.swift:214-222@7c200bf

| Alert | Title | Message | Buttons |
|---|---|---|---|
| Replace | "Replace?" | "Replace the <installed route.json's name>? Its stages leave your phone; what you've walked of it is remembered if it comes back. Walks in your journal stay." (`"route"` if the name can't be read) | "Replace" (destructive) → `install(replacing: true)`; "Keep it" (cancel) |
| Remove | "Remove?" | "Remove the <this entry's name>? Its stages leave …" (same tail) | "Remove" (destructive) → `removeRoute()`; "Keep it" (cancel) |
| Download first | "Download this route first?" | "Its stages have to be on your phone before you can walk one." | "Download" → `beginInstall()` (so a Replace still asks "Replace?" next); "Not now" (cancel) |

- The Replace message names the route **being let go** (the installed `route.json`'s `name`), not the one arriving; the Remove message names this page's entry (the index's name). With the live data both read "the Camino de Santiago (Frances)", the index and `route.json` names agreeing.
- No alert for Update, and none for a first Download with nothing installed.
- All apostrophes are ASCII.
- Android: `AlertDialog` as the reply-replace alert already is (`WayPlaceCard.kt:428-442@0defff85`): the destructive button as `confirmButton` in rust, "Keep it" / "Not now" as `dismissButton`, parchment container, ink title. "Download" in the third alert is not destructive: stone.

#### 4.8 The actions

```swift
    private func open(index: Int) {
        guard isInstalled,
              let way = WayStore.shared.load(id: WayStore.stageWayId(routeId: entry.id, stageIndex: index)) else {
            promptDownload = true
            return
        }
        onChoose(way)
    }

    /// Every path that starts an install goes through here, so the Replace
    /// confirmation can never be skipped by tapping a stage instead of the
    /// button.
    private func beginInstall() {
        if installed != nil && !isInstalled {
            confirmReplace = true
        } else {
            Task { await install(replacing: false) }
        }
    }

    private func install(replacing: Bool) async {
        failure = nil
        do {
            if hasUpdate {
                try await packages.update(entry: entry, release: release)
            } else if replacing {
                try await packages.replace(with: entry, release: release)
            } else {
                try await packages.download(entry: entry, release: release)
            }
            failure = nil
        } catch {
            failure = (error as? PilgrimageError) ?? .incomplete
        }
        // Reload on both branches: a failed update's rollback removed the
        // package, and only reload() picks that state back up so the screen
        // never keeps showing the pre-rollback "on your phone" state.
        reload()
    }

    private func removeRoute() {
        do {
            try packages.remove(routeId: entry.id)
            reload()
        } catch {
            failure = (error as? PilgrimageError) ?? .incomplete
        }
    }
```
> Pilgrim/Scenes/Honor/PilgrimageRouteView.swift:316-363@7c200bf

- **A stage tap** (or the next row's tap) opens the overview only when this route is installed **and** that stage's `way.json` loads; otherwise "Download this route first?", even on an installed route whose stage file is missing or unreadable, or whose next index lies beyond the installed package (§17 D-7). No busy check: a tap mid-download (on an Update, say) opens the overview of the stage as it stands then.
- **`beginInstall`:** another route installed → "Replace?"; else install at once. "Replace" on the alert runs `install(replacing: true)`, which still prefers Update if `hasUpdate` (it can't be: `hasUpdate` needs this route installed).
- **`install`** clears the failure first (so a retry's line clears as it starts), runs one of update / replace / download, sets the failure on a throw, and always reloads.
- **`removeRoute`** is synchronous; on success it reloads (the button turns to "Download", the next row and overflow go, the stage list stays as the removed `route.json` drew it, the circles stay filled from the kept ledger); on a refusal it shows the failure ("finish your walk first", or "the download didn't finish" if a download is in flight).
- `install` runs in an unstructured `Task`: it is **not** cancelled when the page goes away, and neither is the download inside it. The download's own cancellation path (`phase = .idle`, no failure) is reachable only through a Task cancel nothing in the UI issues.

**Android:**
- The page's ViewModel calls into the package manager (U34), whose work runs in its own app-scoped coroutine (plan KTD "Package downloads run in the UI process, in an app-scoped coroutine"). A ViewModel cleared by a pop or a swipe-down must not cancel the download: if the ViewModel awaits the result, it awaits a `Deferred` the manager owns, so its own cancellation leaves the work alone.
- The result lands only on the page that started it, and only while that page lives, as on iOS: a page cleared mid-download drops the outcome (§4.10).
- `open(index)` reads the stage Way on `Dispatchers.IO` (a stage file is up to 2 MB of JSON); the overview reads it again by id, as it does for a shared Way.

#### 4.9 When the page loads what

```swift
        .task {
            reload()
            await loadStagesIfNeeded()
        }
```
> Pilgrim/Scenes/Honor/PilgrimageRouteView.swift:145-148@7c200bf

```swift
    private func loadStagesIfNeeded(force: Bool = false) async {
        guard force || route == nil, !release.isEmpty else { return }
        isLoadingStages = true
        stagesFailure = nil
        do {
            route = try await PilgrimageCatalogService.shared.routePreview(entry: entry, release: release)
        } catch {
            stagesFailure = (error as? PilgrimageError) ?? .catalogUnreachable
        }
        isLoadingStages = false
    }
```
> Pilgrim/Scenes/Honor/PilgrimageRouteView.swift:390-400@7c200bf

- At appearance: `reload()` (installed, the installed route, the ledger, the notice), then the preview **only if** no installed route was found for this entry. An installed route's stages are its own `route.json`'s, never the preview's.
- The preview is cached on disk per route and release (P1), so reopening a route costs nothing; offline with no cached preview it fails with "the routes are out of reach right now" (the service maps every fetch failure to `.catalogUnreachable`; a route file that fails validation or disagrees with the entry gives "this route isn't walkable yet").
- The preview's `.task` is cancelled when the page disappears; a cancelled preview fetch leaves nothing behind.
- After a Remove, `route` keeps the removed package's route (reload only sets it, never clears it); no preview is fetched.
- After a failed Update commit (whose rollback removes the package, P2), the page reads "Download" with the failure line, and keeps the old stage list.

#### 4.10 Leaving and coming back during a download (the plan's "live phase")

What iOS does, traced:
1. Page A starts a download; its `Task` and the manager run on.
2. The walker goes back to the list (or closes the catalog). Page A is gone: its state, including any later `failure` and its post-install `reload()`, writes to nothing.
3. They open the same route again: page B's `.task` runs `reload()` and reads `installed()` as it is **now**. Mid-download that is the pre-download state: nothing installed (first download), the old release (Update), or the other route (Replace).
4. Page B observes the manager's `phase`: the footer shows "stage d of n" live, the button is disabled and fog, the overflow (if any) is disabled.
5. The download commits: `phase` goes `.idle`. Page B **does not reload**: nothing observes the phase's end. The button re-enables with its stale label ("Download" or "Update"), the next row stays hidden (first download), and a stage tap asks "Download this route first?". A tap on "Download" downloads the whole route again (§17 D-1).
6. A download that fails while page B shows: the footer's progress goes, and no failure appears (B's own `failure` is nil; `.failed` is never rendered).
7. The catalog list never shows progress, and reloads only on its own reappearance (§3.4).

**What U37 builds:** the route page's ViewModel collects the manager's phase for as long as it lives, from its first frame, so steps 4–7 match: a page created mid-download shows the live progress and the disabled button, never an idle "Download". Steps 5 and 6 are matched as shipped (the default for an iOS defect; the upstream issue asks iOS to reload on the phase's end). A page that started the download and is still alive (a configuration change keeps its ViewModel) reloads when its own call returns, as iOS's page A would have.

#### 4.11 Process death and restore (Android only)

iOS restores none of this after a kill. Android's routes come back from their arguments:
- **The catalog** restores to the list (or the spinner, then the list) from the service's disk cache via a non-forced `load()`.
- **The route page** restores from its route id. The in-memory catalog is empty in a new process, so its ViewModel first runs the service's non-forced `load()` and finds the entry by id, and takes that catalog's release. If the entry is no longer listed (the index dropped it, or the cache is gone and the fetch fails), the page closes back to the catalog. The download died with the old process: the manager's phase starts `.idle`, so the page shows idle (flow gap 12), and the launch temp sweep (P2) takes the half-downloaded set.
- **The redraw notice** comes back from `SavedStateHandle` (§4.3).
- These are recorded as Android additions (§16 A-3): iOS has no process-death restore to match.

---

### 5. Numbers on these screens: `WayStageFacts`, `WayStageLine`, and what `StatsHelper` prints

#### 5.1 The facts line

One formatter for the route page's stage rows and the morning card (iOS's own comment: "Two copies of this drifted apart once already"):

```swift
enum WayStageFacts {

    static func line(distanceKm: Double, gainMeters: Double, hours: WayStageHours, difficulty: String) -> String {
        var parts = [
            StatsHelper.string(for: distanceKm * 1000, unit: UnitLength.meters, type: .distance),
            "\(StatsHelper.string(for: gainMeters, unit: UnitLength.meters, type: .altitude)) up",
            hoursText(hours)
        ]
        if !difficulty.isEmpty { parts.append(difficulty) }
        return parts.joined(separator: " · ")
    }

    /// `Int(_:)` traps on a non-finite Double; the importer already bounds
    /// these, and this is the last step before the number reaches the screen.
    private static func hoursText(_ hours: WayStageHours) -> String {
        let low = boundedHours(hours.min)
        let high = boundedHours(hours.max)
        return low == high ? "\(low) hours" : "\(low) to \(high) hours"
    }

    /// An infinite figure is an absurdly large one and clamps to the ceiling
    /// like any other; a NaN orders against nothing, so `min`/`max` would
    /// carry it straight through to the trap and it takes the floor instead.
    private static func boundedHours(_ value: Double) -> Int {
        guard !value.isNaN else { return 0 }
        return Int(min(max(value, 0), 100).rounded())
    }
}
```
> Pilgrim/Scenes/Honor/PilgrimageRouteView.swift:6-33@7c200bf

```swift
    static func stageLine(_ stage: PilgrimageRouteStage) -> String {
        WayStageFacts.line(distanceKm: stage.distanceKm, gainMeters: stage.gainMeters,
                           hours: stage.hours, difficulty: stage.difficulty)
    }
```
> Pilgrim/Scenes/Honor/PilgrimageRouteView.swift:39-42@7c200bf

```swift
    static func factsLine(for stage: WayStage) -> String {
        WayStageFacts.line(distanceKm: stage.distanceKm, gainMeters: stage.gainMeters,
                           hours: stage.hours, difficulty: stage.difficulty)
    }
```
> Pilgrim/Scenes/Honor/StageMorningCard.swift:8-11@7c200bf

Rules:
- **Distance:** `StatsHelper` `.distance` of `distanceKm × 1000` m (§5.3).
- **Gain:** `StatsHelper` `.altitude` of `gainMeters`, then `" up"`. The altitude unit is iOS's **altitude** preference (metres or feet), which `applyUnitSystem` sets together with the distance unit (`UserPreferences.swift:105,117-128@7c200bf`); Android has one `UnitSystem` for both, so imperial means feet here.
- **Hours:** each bound NaN → 0, then clamped to 0…100 and rounded half away from zero (Swift `rounded()`; Kotlin `roundToInt()` agrees for non-negative values, and the clamp makes every value non-negative). Equal after rounding → `"N hours"`, **never singular** ("1 hours", "0 hours"); else `"low to high hours"`. `+∞` clamps to 100, so `(NaN, ∞)` reads "0 to 100 hours" (an iOS test, §13). No live stage has equal bounds.
- **Difficulty:** the dataset's string, verbatim (not capitalised or translated); an empty string adds no part and no trailing separator. Shikoku's stages are all `""`.
- Joined by `" · "`.

Examples: "24.2 km · 1,419 m up · 7 to 9 hours · hard" (Francés stage 1); "28.3 km · 290 m up · 6 to 9 hours" (Shikoku Awa stage 1, no difficulty); imperial "15.04 mi · 4,655.51 ft up · 7 to 9 hours · hard".

#### 5.2 The stage line

```swift
enum WayStageLine {

    static func line(for way: Way) -> String? {
        way.stage.map(line(for:))
    }

    static func line(for stage: WayStage) -> String {
        var parts = ["stage \(stage.index + 1) of \(stage.count)",
                     StatsHelper.string(for: stage.distanceKm * 1000, unit: UnitLength.meters, type: .distance)]
        if !stage.difficulty.isEmpty { parts.append(stage.difficulty) }
        return parts.joined(separator: " · ")
    }
}
```
> Pilgrim/Scenes/Honor/WayMomentHeader.swift:123-135@7c200bf

"stage 1 of 33 · 24.2 km · hard"; a Shikoku stage reads "stage 1 of 5 · 28.3 km". `count` is the stage block's own count, which the package manager checks equals `route.json`'s (P1/P2). Its callers: the overview's date slot (live), the Ways sheet's shared rows and Settings → Ways' rows (both unreachable for a stage, §2.1 and `WaysListView.swift:97-107@7c200bf`). Android: one `WayStageLine` in `WayMomentHeader.kt` (the plan's file), used by the overview only.

#### 5.3 What `StatsHelper` prints (probed), and what Android prints today

```swift
        let formatter = MeasurementFormatter()
        formatter.unitOptions = .providedUnit

        switch rounding {
        case .wholeNumbers:
            formatter.numberFormatter.roundingIncrement = 1
        case .oneDigit:
            formatter.numberFormatter.roundingIncrement = 0.1
        case .twoDigits:
            formatter.numberFormatter.roundingIncrement = 0.01
```
> Pilgrim/Models/Formatting/CustomMeasurementFormatting.swift:28-37@7c200bf

```swift
        case .distance:
            return safeFormattedString(formatter, measurement: measurement, to: UserPreferences.distanceMeasurementType.safeValue)
        case .altitude:
            return safeFormattedString(formatter, measurement: measurement, to: UserPreferences.altitudeMeasurementType.safeValue)
```
> Pilgrim/Models/Formatting/CustomMeasurementFormatting.swift:54-57@7c200bf

`StatsHelper.string(for:unit:type:)` defaults to `.twoDigits`. A `MeasurementFormatter` rounds to 0.01 but shows **no trailing zeros**, groups thousands, and follows `Locale.current`. Probed on this Mac (en_US), `fmt.swift`/`fmt2.swift`:

| Input | km / m | mi / ft |
|---|---|---|
| 24,000 m (distance) | `24 km` | `14.91 mi` |
| 24,200 m | `24.2 km` | `15.04 mi` |
| 24,235 m | `24.24 km` (the shortest decimal, 24.235, rounded half-even) | `15.06 mi` |
| 1,005 m | `1 km` (1.005 → 1.00 half-even) | `0.62 mi` |
| 764,000 m | `764 km` | `474.73 mi` |
| 154,500 m | `154.5 km` | `96 mi` |
| 2,345,678 m | `2,345.68 km` | `1,457.54 mi` |
| 1,200 m | `1.2 km` | `0.75 mi` |
| 50 m | `0.05 km` (never metres) | `0.03 mi` |
| 0 m | `0 km` | `0 mi` |
| 1,419 m (altitude) | `1,419 m` | `4,655.51 ft` |
| 1,400 m | `1,400 m` | `4,593.18 ft` |
| 0 m | `0 m` | `0 ft` |
| 0.015 m | `0.02 m` | `0.05 ft` |
| de_DE 24,230 m / 1,400 m | `24,23 km` / `1.400 m` | |

Android's house formatter is `WalkFormat.distance` and `WalkFormat.altitude`:

```kotlin
    private fun metricDistanceLabel(meters: Double): DistanceLabel {
        val km = meters / 1_000.0
        return if (meters >= 100.0) {
            DistanceLabel(value = String.format(Locale.US, "%.2f", km), unit = "km")
        } else {
            DistanceLabel(value = String.format(Locale.US, "%d", meters.roundToInt()), unit = "m")
        }
    }
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkFormat.kt:70-77@0defff85

```kotlin
    fun altitude(meters: Double, units: UnitSystem): String = when (units) {
        UnitSystem.Metric -> String.format(Locale.US, "%d m", meters.roundToInt())
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkFormat.kt:128-129@0defff85

So Android today prints "764.00 km", "24.20 km", "1400 m": the new surfaces show the dataset's round figures, and every one of them would carry two trailing zeros. Stage 21-1 recorded `WalkFormat.distance` as the app-wide convention for geometry lengths (`docs/parity/2026-09-29-honor-own-walk-port.md:11711`), where round figures are rare. The dataset's figures are round by nature ("764", "24.2", "1419"), and iOS's own doc comments and tests spell them as `"24 km · 1,400 m up"`.

**Recommendation (owner decision O-1):** a `StatsHelper`-faithful formatter for this stage's surfaces: the card line, the progress line, the facts line, the stage line, the next row's caption, and the preview's "along the stage" (§8). It converts metres to km (`/1000`) or miles (`/1609.344`, Foundation's mile, as `CollectiveRoute` already pins), and altitude to m or ft (`/0.3048`). It rounds the value's shortest decimal form half-even to two places (`BigDecimal(value.toString()).setScale(2, RoundingMode.HALF_EVEN)`), strips trailing zeros, groups with `#,##0.##`, and appends ` km`/` mi`/` m`/` ft`. Use `DecimalFormatSymbols(Locale.US)`, the house numeric convention (`CollectiveRoute.kt:171-195@0defff85` already does this to mirror `MeasurementFormatter`). Port the table above as its tests. Probed on JDK 17 (`probe-P4/T.java`), this recipe reproduces every row of the table, the half-even cases (24,235 m, 1,005 m, 0.015 m) included. Whether Stage 21-1's surfaces (the overview's distance, the preview's "along their way", the walk's Remaining) move to it too is the second half of the decision; the default is to leave them as recorded.

```kotlin
        private fun decimalFormat(pattern: String): DecimalFormat =
            DecimalFormat(pattern, DecimalFormatSymbols(Locale.US))
```
> app/src/main/java/org/walktalkmeditate/pilgrim/data/collective/routes/CollectiveRoute.kt:194-195@0defff85

Locale: iOS follows `Locale.current` (German grouping above). Android's `Locale.US` symbols are the recorded house convention for numbers, so this is the existing difference, not a new one.

---

### 6. The overview's stage branches

Slice two added +113 lines to the overview; slice three added the maps-saved state (§9). Everything else in the overview (map fit, the card over the map, the moment preview, the import line, the status line) is Stage 21-1's spec F §8–§14 and S4 §8–§9, unchanged for a stage except as below.

#### 6.1 The card, line by line, for a stage

```swift
    private var card: some View {
        VStack(alignment: .leading, spacing: Constants.UI.Padding.small) {
            Text(way.title)
                .font(Constants.Typography.heading)
                .foregroundColor(.ink)
            Text(WayStageLine.line(for: way)
                 ?? DateFormatter.localizedString(from: way.departedAt, dateStyle: .long, timeStyle: .short))
                .font(Constants.Typography.caption)
                .foregroundColor(.fog)
            HStack {
                Text(StatsHelper.string(for: way.totalDistanceMeters, unit: UnitLength.meters, type: .distance))
                Text("·")
                Text(durationText(way.theirActiveSeconds))
                Text("·")
                Text(HonorOverviewModel.countsLine(way: way))
            }
            .font(Constants.Typography.body)
            .foregroundColor(.ink)
            importLine
            if let line = HonorOverviewModel.weatherLine(theirs: way.weather, today: todayCondition) {
                Text(line)
                    .font(Constants.Typography.caption)
                    .foregroundColor(.fog)
            }
            if let status = HonorOverviewModel.statusLine(distanceToStartMeters: distanceToStart) {
                Text(status)
                    .font(Constants.Typography.caption)
                    .foregroundColor(.fog)
            }
            if let offlineNote {
                Text(offlineNote)
                    .font(Constants.Typography.caption)
                    .foregroundColor(.fog)
            }
            // A stage carries no recordings, so "walk with their voice" would
            // be a switch over nothing — and would say "their" besides.
            if !way.isPilgrimageStage {
                Toggle(isOn: $voicesEnabled) {
```
> Pilgrim/Scenes/Honor/HonorOverviewView.swift:227-264@7c200bf

For the Camino Francés stage 1, top to bottom:

| Line | iOS for a stage | Android at `0defff85` | U38 |
|---|---|---|---|
| Title | `way.title`, the stage file's title (capped 120): "Saint-Jean-Pied-de-Port to Roncesvalles" | same | none |
| Date slot | the stage line: "stage 1 of 33 · 24.2 km · hard" | the departure date (`departureLine`) | branch on `way.stage` |
| Stats | `StatsHelper` of `totalDistanceMeters` (the **geometry's** length, recomputed from the points by the importer, `PilgrimageWayImporter.swift:184-196@7c200bf`, so the file's own `totalDistanceMeters`, 23,825.8 m, is only range-checked, line 317): about "23.83 km"; `durationText(theirActiveSeconds)`: the dataset's synthesized clock, "8h 0m" (28,800 s, the midpoint of 7–9 hours); `countsLine`: "a quiet way" (a stage has only waypoints) | same | none (matched as shipped, §17 D-11) |
| Import line | none (`gather` set `.ready`) | none (`gather` sets `Ready`) | none |
| Weather | none: a stage Way's `weather` is nil (`PilgrimageWayImporter.swift:199@7c200bf`), so "they walked this in…" never shows, and with it "Today is …" | same | none |
| Status | "you're on the way" / "2.3 km from the start" (the first route point) | same | none |
| Offline note | "map tiles need a connection; the way itself is on your phone.", once ever (§6.4) | absent | add |
| Voice toggle | **hidden** | always drawn | hide on a stage |
| Begin | "Begin", a11y "Walk this stage", opens the morning card | "Begin", a11y "Begin honoring this way", navigates | branch both |

So the card shows two different distances for one stage: the stage line's 24.2 km (the dataset's figure) and the stats row's 23.83 km (the downloaded line's length). Both are parity.

Android today:

```kotlin
        Text(text = way.title, style = pilgrimType.heading, color = pilgrimColors.ink)
        Text(
            text = HonorOverviewModel.departureLine(way, ZoneId.systemDefault(), locale),
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
        )
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/honor/HonorOverviewScreen.kt:251-256@0defff85

```kotlin
        VoicesToggle(
            checked = voicesEnabled,
            // A quiet way keeps showing its stored value, switched off from use.
            enabled = way.voiceCount > 0,
            onCheckedChange = onVoicesEnabledChange,
        )
        val beginLabel = stringResource(R.string.honor_overview_begin_a11y)
        Button(
            onClick = onBegin,
            enabled = !importState.holdsBegin,
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/honor/HonorOverviewScreen.kt:280-290@0defff85

**U38:** `WayStageLine.line(way) ?: HonorOverviewModel.departureLine(...)` in the date slot; the offline note after the status line; `if (!way.isPilgrimageStage) VoicesToggle(...)`; `beginLabel` = "Walk this stage" on a stage; `onClick` opens the morning card on a stage and calls `onBegin` otherwise. The Begin button's enabled rule (`!holdsBegin`) is unchanged and applies to a stage too.

#### 6.2 Begin and the morning card's presentation

```swift
            Button {
                if way.isPilgrimageStage { showMorningCard = true } else { onBegin() }
            } label: {
                Text("Begin")
                    .font(Constants.Typography.button)
                    .foregroundColor(.parchment)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(isGathering ? Color.fog : Color.stone)
                    .cornerRadius(Constants.UI.CornerRadius.normal)
            }
            .accessibilityLabel(way.isPilgrimageStage ? "Walk this stage" : "Begin honoring this way")
            .disabled(isGathering)
```
> Pilgrim/Scenes/Honor/HonorOverviewView.swift:274-286@7c200bf

```swift
        .sheet(isPresented: $showMorningCard) {
            if let stage = way.stage {
                StageMorningCard(stage: stage, weather: todayWeather,
                                 mapsLine: StageMorningCardModel.mapsLine(saved: stageMapsSaved),
                                 buttonTitle: "walk") {
                    showMorningCard = false
                    onBegin()
                }
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
            }
        }
```
> Pilgrim/Scenes/Honor/HonorOverviewView.swift:188-199@7c200bf

- The face stays "Begin"; only the label changes. The Begin button keeps the Stage 21-1 hold while a link's fetch or a share's gather could land (§6.6).
- The card is a full-height sheet **with** a drag indicator (unlike the Ways sheets, which show none). Swiping it down closes it and leaves the overview, with no other effect.
- "walk" closes the card and calls `onBegin` in the same action; the coordinator closes the overview, then opens the walk screen (§1.1).
- The weather is the overview's `todayWeather` (§6.5); the maps line is Stage 21-3's (§9).
- Android: a `ModalBottomSheet` inside the overview route with `skipPartiallyExpanded = true`, `containerColor = parchment` and the default drag handle (the moment preview's sheet already shows one, `WayMomentPreview.kt:93-97@0defff85`). Its open flag is `rememberSaveable`, so a configuration change keeps it open; after process death the overview restores with the card open, which is harmless. "walk": slide the card down, then `onBegin(overview.choice)`.

#### 6.3 What the overview's Begin does on Android, and the `:tracker` split

`onBegin` is navigation only (`beginHonorWalk`, §1.2). The walk starts at the walk screen's Start through `BeginHonorWalk`, which for a `Stored` choice reads the Way from the store and holds it with `HonorBeginsInFlight` until the session row exists (`BeginHonorWalk.kt:120-156@0defff85`). For a stage that is the stage's `way.json` under `Ways/pilgrimage:<route>:<n>/`, read again by `:tracker` at Start and at every revival (flow gap 1). The door adds nothing to `BeginHonorWalk` itself; the package guard (walk screen up, pre-Start included, via `HonorLinkScreen.walkScreenUp`) and the file stamp are P2's.

#### 6.4 The offline note, once ever

```swift
    static func offlineNote(isStage: Bool, isConnected: Bool, alreadyShown: Bool) -> String? {
        guard isStage, !isConnected, !alreadyShown else { return nil }
        return "map tiles need a connection; the way itself is on your phone."
    }
```
> Pilgrim/Scenes/Honor/HonorOverviewView.swift:48-51@7c200bf

```swift
    static let pilgrimageOfflineNoteShown = UserPreference.Required<Bool>(key: "pilgrimageOfflineNoteShown", defaultValue: false)
```
> Pilgrim/Models/Preferences/UserPreferences.swift:79@7c200bf

```swift
    private func checkConnectivity() {
        // The note is said once ever and only on a stage; a probe that could
        // not produce it is a monitor allocated for nothing.
        guard way.isPilgrimageStage, !UserPreferences.pilgrimageOfflineNoteShown.value else { return }
        let monitor = NWPathMonitor()
        connectivityMonitor = monitor
        monitor.pathUpdateHandler = { [weak monitor] path in
            let connected = path.status == .satisfied
            DispatchQueue.main.async {
                if let note = HonorOverviewModel.offlineNote(
                    isStage: way.isPilgrimageStage, isConnected: connected,
                    alreadyShown: UserPreferences.pilgrimageOfflineNoteShown.value) {
                    offlineNote = note
                    UserPreferences.pilgrimageOfflineNoteShown.value = true
                }
                releaseConnectivityMonitor()
            }
            monitor?.cancel()
        }
        monitor.start(queue: DispatchQueue(label: "honor-connectivity-check"))
    }
```
> Pilgrim/Scenes/Honor/HonorOverviewView.swift:382-402@7c200bf

```swift
        .onAppear { probeDistance(); checkConnectivity() }
        .onDisappear { releaseConnectivityMonitor() }
```
> Pilgrim/Scenes/Honor/HonorOverviewView.swift:166-167@7c200bf

- **When:** each appearance of a stage overview, while the preference is false. One connectivity reading (the path monitor's first report), then the monitor is released. Closing the overview first releases it unread.
- **What counts as offline:** `NWPath.status != .satisfied`: no usable network path (airplane mode, no signal). A Wi-Fi with no internet behind it still reads satisfied. Android: `ConnectivityManager.activeNetwork` and its `NET_CAPABILITY_INTERNET`; not `VALIDATED`, which would be stricter than `.satisfied`. Use a seam `fun interface` as `UnmeteredNetworkProbe` already does (`WhisperModelStore.kt:86-100@0defff85`).
- **Once ever:** the preference is set to true **at the moment the note is shown**, never at an online opening. So the walker sees it on the first stage overview they open offline, never again on this install. It stays on that overview for its life.
- **The key:** iOS `UserDefaults` key `"pilgrimageOfflineNoteShown"`, default false. Android: a DataStore boolean in `HonorPreferencesRepository` (same key name), read through an `await…()` that waits for the stored value, as `awaitVoicesEnabled()` already does (`HonorPreferencesRepository.kt:19-24@0defff85`): a `StateFlow.value` read before DataStore has loaded is the default `false`, which would show the note a second time.
- **Ordering on Android:** in `HonorOverviewViewModel.load()` for a stage: await the preference; if false, read the probe once; if offline, put the note in the overview state and write the preference. A configuration change keeps the ViewModel and its note; a restored overview after process death reads "already shown" and shows nothing. Neither case exists on iOS.
- **Placement:** after the status line, before the toggle; caption, fog.
- The note says the tiles need a connection even after Stage 21-3 saves them (known, survey §5; Stage 21-3's to match as shipped).

#### 6.5 Today's weather on the overview

```swift
    private func fetchToday() async {
        guard let here = CLLocationManager().location,
              let snapshot = await WeatherService.shared.fetchCurrent(for: here) else { return }
        todayCondition = snapshot.condition.rawValue
        todayWeather = snapshot
    }
```
> Pilgrim/Scenes/Honor/HonorOverviewView.swift:339-344@7c200bf

One fetch per overview (`.task`), on CoreLocation's cached fix, silent offline or without a fix. Slice two keeps the whole snapshot (`todayWeather`) for the morning card; before, only the condition was kept. The morning card's weather line reads whatever has landed when the card renders, and updates if the fetch lands while it is open.

Android keeps only the condition:

```kotlin
        val here = awaitLastKnownFix() ?: return
        updateOverview { it.copy(distanceToStartMeters = HonorOverviewModel.distanceToStartMeters(here, way)) }
        // "Today is …": the walk's own weather source, on the walker's
        // current fix; silent offline or without a fix (F §10.5).
        val today = weatherFetching.fetchCurrent(here.latitude, here.longitude) ?: return
        updateOverview { it.copy(todayCondition = today.condition.rawValue) }
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/honor/HonorOverviewViewModel.kt:206-211@0defff85

**U38:** add `todayWeather: WeatherSnapshot?` to `HonorOverview` beside `todayCondition`, set from the same fetch. The source is Open-Meteo where iOS uses WeatherKit (an existing platform equivalent, Stage 12).

#### 6.6 What else applies to a stage overview unchanged

- **The marks** (P5): the overview's map draws `markPins` under the moment pins, nothing until the map reports its camera (`HonorOverviewView.swift:125,134-138,356-362@7c200bf`). Plan U39 builds them, on the overview too.
- **Moment previews** open from a tapped pin as for any Way (§8).
- **A link's import** while a stage overview is up: iOS's global import state moves to `.fetching` (Begin held, "reaching for the walk…" under the stats) and then `.failed` (the rust line stays until the overview closes), as on an own-walk overview (pilgrim-ios #110 item 3, which covers stages too). Android already mirrors this through the one import state.
- **The debug "Export simulation GPX"** menu (`#if DEBUG`, `HonorOverviewView.swift:156-164@7c200bf`) is how iOS simulates a stage; Android's U19 harness is the equivalent for U41.

---

### 7. The morning card

#### 7.1 Its lines

```swift
    static func weatherLine(_ snapshot: WeatherSnapshot?) -> String? {
        guard let snapshot else { return nil }
        let imperial = UserPreferences.distanceMeasurementType.safeValue == .miles
        return "\(snapshot.condition.label.lowercased()), \(snapshot.formattedTemperature(imperial: imperial))"
    }
```
> Pilgrim/Scenes/Honor/StageMorningCard.swift:15-19@7c200bf

```swift
    var label: String {
        switch self {
        case .clear: return "Clear"
        case .partlyCloudy: return "Partly cloudy"
        case .overcast: return "Overcast"
        case .lightRain: return "Light rain"
        case .heavyRain: return "Heavy rain"
        case .thunderstorm: return "Thunderstorm"
        case .snow: return "Snow"
        case .fog: return "Foggy"
        case .wind: return "Windy"
        case .haze: return "Hazy"
        }
    }
```
> Pilgrim/Models/Weather/WeatherService.swift:25-38@7c200bf

```swift
    static func formatTemperature(_ celsius: Double, imperial: Bool) -> String {
        if imperial {
            return String(format: "%.0f°F", celsius * 9 / 5 + 32)
        }
        return String(format: "%.0f°C", celsius)
    }
```
> Pilgrim/Models/Weather/WeatherService.swift:61-66@7c200bf

- **Weather:** the condition's label lower-cased, a comma and a space, then the temperature: "clear, 9°C", "partly cloudy, 48°F", "foggy, 12°C". The unit follows the **distance** preference (miles → °F). Nothing at all with no snapshot (no "weather unavailable").
- `%.0f` rounds on the binary value, half to even at exact ties, and keeps a negative zero (probe `temp.swift`: 8.5 → "8°C", 9.5 → "10°C", 2.5 → "2°C", −0.4 → "-0°C"). Android's existing card formatters use `String.format(Locale.US, "%.0f°C", c)` (`ContextFormatter.kt:330-335@0defff85`, `WalkSummaryScreen.kt:1208-1211@0defff85`): Java rounds half up at a tie (8.5 → "9°C") and keeps "-0". Reuse that helper; the source already differs (Open-Meteo reports tenths, so ties happen), which is the recorded weather-source equivalent.
- Android's labels exist (`weather_clear` … `weather_haze`, `strings.xml:1031-1040@0defff85`); lower-case them with the display locale, as `HonorOverviewModel.spoken` does.
- **Facts:** `WayStageFacts` of the stage block (§5.1), the same line as the route page's row for that stage. The route page reads the `route.json` row and the card reads the stage file's block; the package manager checks they agree on the name and count only (P1/P2), so a dataset whose two files disagree on kilometres would show two figures.
- **Theme, narrative, warnings:** the dataset's words, unedited (capped at parse time: theme 80, narrative 2,000, each warning 300, at most 20 warnings; P1).
- **Maps line** (Stage 21-3): "maps saved for today" / "no offline maps for today — save on wifi" (U+2014), or nothing when the caller passes nil (§9).

#### 7.2 Its layout

```swift
    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: Constants.UI.Padding.normal) {
                    Text(stage.theme)
                        .font(Constants.Typography.displayMedium)
                        .foregroundColor(.ink)
                    Text(stage.narrative)
                        .font(Constants.Typography.body)
                        .foregroundColor(.ink)
                    Text(StageMorningCardModel.factsLine(for: stage))
                        .font(Constants.Typography.caption)
                        .foregroundColor(.fog)
                    warnings
                    if let weatherLine = StageMorningCardModel.weatherLine(weather) {
                        Text(weatherLine)
                            .font(Constants.Typography.caption)
                            .foregroundColor(.fog)
                    }
                    if let mapsLine {
                        Text(mapsLine)
                            .font(Constants.Typography.caption)
                            .foregroundColor(.fog)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(Constants.UI.Padding.normal)
            }
            Button(action: onAction) {
                Text(buttonTitle)
                    .font(Constants.Typography.button)
                    .foregroundColor(.parchment)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(Color.stone)
                    .cornerRadius(Constants.UI.CornerRadius.normal)
            }
            .accessibilityLabel(buttonTitle == "walk" ? "Begin walking this stage" : "Close the day's words")
            .padding(Constants.UI.Padding.normal)
        }
        .background(Color.parchment)
    }

    /// Each warning its own short paragraph: two crowded onto one line is
    /// how a warning stops being read.
    @ViewBuilder
    private var warnings: some View {
        if !stage.warnings.isEmpty {
            VStack(alignment: .leading, spacing: Constants.UI.Padding.small) {
                ForEach(Array(stage.warnings.enumerated()), id: \.offset) { _, warning in
                    HStack(alignment: .top, spacing: Constants.UI.Padding.small) {
                        Image(systemName: "exclamationmark.triangle")
                            .font(Constants.Typography.caption)
                            .foregroundColor(.rust)
                        Text(warning)
                            .font(Constants.Typography.caption)
                            .foregroundColor(.ink)
                    }
                }
            }
        }
    }
```
> Pilgrim/Scenes/Honor/StageMorningCard.swift:42-103@7c200bf

| Element | iOS | Android |
|---|---|---|
| Scroll area | everything but the button scrolls; 16 padding; items 16 apart, leading | `Column(Modifier.weight(1f).verticalScroll(...).padding(PilgrimSpacing.normal), spacedBy(PilgrimSpacing.normal))` |
| Theme | `displayMedium` (Cormorant Light 28), ink | `pilgrimType.displayMedium`, ink |
| Narrative | body, ink | `pilgrimType.body`, ink |
| Facts | caption, fog | caption, fog |
| Warnings | a column 8 apart; each a row, top-aligned, 8 apart: a caption-sized `exclamationmark.triangle` in **rust**, then the warning in caption **ink** (not fog) | `Icons.Outlined.WarningAmber` at the caption's size, rust; text caption ink |
| Weather, maps | caption, fog | caption, fog |
| Button | pinned under the scroll area, 16 padding round it; full width, the button face in parchment on stone, 12 above and below, corner 12; never disabled | the overview Begin's shape (`HonorOverviewScreen.kt:287-308@0defff85`), always stone |
| Background | parchment | the sheet's `containerColor = parchment` |

- **Accessibility:** the button's label is "Begin walking this stage" when its title is "walk", else "Close the day's words" (ASCII apostrophe); the visible title is "walk" / "close". The triangle is not hidden, so VoiceOver reaches it as its own element and speaks the symbol's name (the house stand-in, `WayMomentCopy.spokenSymbolName`: "exclamationmark triangle"); each warning's text is its own element after it.
- **Dynamic Type:** all text scales; the triangle is caption-sized and scales with it (an SF Symbol in a text font). Android's icon is fixed-size; size it from the caption's line height if parity at large font scales matters (minor).
- There is no title bar and no close control besides the button and the drag indicator.

#### 7.3 "the day" (data level; P5 owns the walk-side row)

```swift
                    if let stageDay, let onOpenStageDay {
                        optionRow(icon: "sun.horizon", title: "the day", subtitle: stageDay.theme) {
                            onOpenStageDay()
```
> Pilgrim/Scenes/ActiveWalk/WalkOptionsSheet.swift:67-69@7c200bf

```swift
                stageDay: viewModel.way?.stage,
                onOpenStageDay: {
                    showOptions = false
                    stageMapsLine = viewModel.way.map { StageMorningCardModel.mapsLine(saved: PilgrimageTilesManager.shared.isStageSaved($0)) }
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                        showStageDay = true
                    }
                }
            )
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
            .presentationBackground(Color.parchment.opacity(0.95))
        }
        .sheet(isPresented: $showStageDay) {
            if let stage = viewModel.way?.stage {
                StageMorningCard(stage: stage, weather: viewModel.weatherSnapshot,
                                 mapsLine: stageMapsLine,
                                 buttonTitle: "close") {
                    showStageDay = false
                }
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
            }
        }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkView.swift:297-320@7c200bf

Mid-walk the same card shows with:
- **the stage:** the walk's own `way.stage`, the Way captured at Begin (Android: the Way `HonorWalkViewModel` loads for the walk; the stage block is in it);
- **the weather:** the walk's own snapshot, fetched at the walk's start with retries, nil until it lands (`ActiveWalkViewModel.swift:67,280-296@7c200bf`), **not** the overview's. Android: `WalkViewModel.activeWeather` (`WalkViewModel.kt:169-172@0defff85`), else the walk row's stored `weather_condition` / `weather_temperature` (`Walk.kt:33-36@0defff85`) after a UI restart, when the in-memory snapshot is gone;
- **the button:** "close" (a11y "Close the day's words"), which only closes the card;
- **the maps line:** computed when the row is tapped (Stage 21-3);
- **the timing:** the options sheet closes first and the card opens 0.3 s later.
The row appears whenever the walk's Way has a stage (`stageDay` non-nil); P5 pins where and when the options sheet shows it.

---

### 8. The moment preview on a stage

#### 8.1 The subline: "1.2 km along the stage"

```swift
    private static func alongTheWay(way: Way, moment: WayMoment) -> String {
        let distance = StatsHelper.string(for: moment.frac * way.totalDistanceMeters, unit: UnitLength.meters, type: .distance)
        var parts: [String] = []
        if way.isPilgrimageStage {
            parts.append("\(distance) along the stage")
        } else {
            let elapsed = WayGeometry(route: way.route).elapsed(atFrac: moment.frac)
            let formatter = DateFormatter()
            formatter.timeStyle = .short
            formatter.timeZone = way.tzIdentifier.flatMap(TimeZone.init(identifier:)) ?? .current
            parts.append("\(distance) along their way")
            parts.append(formatter.string(from: way.departedAt.addingTimeInterval(elapsed)))
        }
        if let place = moment.place, !place.isEmpty { parts.append(place) }
        return parts.joined(separator: " · ")
    }
```
> Pilgrim/Scenes/Honor/WayMomentPreview.swift:49-64@7c200bf

- A stage drops the hour (its clock is synthesized) and says "along the stage" where any other Way says "along their way".
- The distance is `StatsHelper` `.distance` of `frac × totalDistanceMeters` (the geometry's length), in the walker's unit, always km or mi: "1.2 km along the stage", "0.05 km along the stage", "0.75 mi along the stage".
- Then the moment's `place` when non-empty. Stage moments carry no `place` (the importer sets none, `PilgrimageWayImporter.swift:209-222@7c200bf`), so a stage's subline is the distance clause alone.

Android today has only the own/shared form:

```kotlin
    fun subline(resources: Resources, way: Way, moment: WayMoment, units: UnitSystem, locale: Locale): String {
        val distance = WalkFormat.distance(moment.frac * way.totalDistanceMeters, units)
        val elapsedSeconds = WayGeometry(way.route).elapsed(atFrac = moment.frac)
        val hour = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            .withLocale(locale)
            .withZone(wayZone(way))
            .format(way.departedAt.plusMillis((elapsedSeconds * 1000).toLong()))
        val parts = buildList {
            add(resources.getString(R.string.honor_moment_along_their_way, distance))
            add(hour)
            moment.place?.takeIf { it.isNotEmpty() }?.let(::add)
        }
        return parts.joinToString(" · ")
    }
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/honor/WayMomentHeader.kt:82-95@0defff85

**U38:** branch on `way.isPilgrimageStage`: `"%1$s along the stage"` and no hour. With `WalkFormat.distance` it would read "1.20 km along the stage"; with the formatter O-1 proposes, "1.2 km along the stage". Note `WalkFormat.distance` also drops to metres under 100 m ("50 m along the stage") where iOS prints "0.05 km".

#### 8.2 The header in the preview shows the local name too

iOS's `WayMomentHeader` draws the local name under the kicker in **both** sizes; the preview uses the full size:

```swift
                Text(Self.kicker(for: moment))
                    .font(compact ? Constants.Typography.body : Constants.Typography.heading)
                    .foregroundColor(.ink)
                if let localName = Self.localName(for: moment) {
                    Text(localName)
                        .font(Constants.Typography.caption)
                        .foregroundColor(.fog)
                        .lineLimit(1)
                }
```
> Pilgrim/Scenes/Honor/WayMomentHeader.swift:22-30@7c200bf

```swift
    static let localNameOrder = ["eu", "gl", "es", "fr", "ja", "pt", "it", "de"]

    static func localName(for moment: WayMoment) -> String? {
        guard let names = moment.names else { return nil }
        let label = kicker(for: moment)
        for code in localNameOrder {
            guard let name = names[code], !name.isEmpty, name != label else { continue }
            return name
        }
        return nil
    }
```
> Pilgrim/Scenes/Honor/WayMomentHeader.swift:72-82@7c200bf

Android's full-size header (the preview's) has no local-name line; only the compact one does:

```kotlin
        Column(verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs)) {
            WayMomentCopy.kicker(resources, moment, keepsEmpty = way.source !is WaySource.OwnWalk)?.let {
                Text(text = it, style = pilgrimType.heading, color = pilgrimColors.ink)
            }
            Text(
                text = WayMomentCopy.subline(resources, way, moment, units, locale),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
        }
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/honor/WayMomentHeader.kt:182-191@0defff85

**U38 (a gap the plan doesn't list):** add `WayMomentCopy.localName(...)` between the kicker and the subline in `WayMomentHeader`: caption, fog, one line, 4 apart (`PilgrimSpacing.xs`). A stage waypoint previewed from the overview then reads, e.g., "Vierge d'Orisson" / "Orissongo Ama Birjina" / "0.3 km along the stage".

#### 8.3 The waypoint body on a stage

```swift
    static func placeCopy(for moment: WayMoment, isStage: Bool) -> String {
        if let text = moment.text, !text.isEmpty { return text }
        return isStage ? "A place on the way." : "A place they marked."
    }
```
> Pilgrim/Scenes/Honor/WayMomentHeader.swift:86-89@7c200bf

```swift
        case .waypoint:
            VStack(alignment: .leading, spacing: Constants.UI.Padding.small) {
                Text(WayMomentHeader.placeCopy(for: moment, isStage: way.isPilgrimageStage))
                    .font(Constants.Typography.body).foregroundColor(.ink)
                if let minutes = moment.sitMinutes, minutes > 0 {
                    Text("When you walk it, the way will offer you \(minutes) minutes of sitting here.")
                        .font(Constants.Typography.caption).foregroundColor(.fog)
                } else {
                    Text("When you walk it, it rises as a card as you reach it.")
                        .font(Constants.Typography.caption).foregroundColor(.fog)
                }
            }
```
> Pilgrim/Scenes/Honor/WayMomentPreview.swift:78-89@7c200bf

- The body is the dataset's `text` (up to 600 characters, P1), else "A place on the way." on a stage.
- The sit offer reads "… 5 minutes of sitting here." (every live `sitMinutes` is 5); a 1 would read "1 minutes" (pilgrim-ios #109 item 2's family; not refiled).
- Android already ports the two-line body and the sit offer (`WayMomentPreview.kt:144-152@0defff85`), with the own-walk `placeCopy` only:

```kotlin
    /** iOS `placeCopy(for:isStage:)` for an own walk, which is never a stage. */
    fun placeCopy(resources: Resources, moment: WayMoment): String =
        moment.text?.takeIf { it.isNotEmpty() } ?: resources.getString(R.string.honor_moment_place_marked)
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/honor/WayMomentHeader.kt:97-99@0defff85

**U38/U39:** give `placeCopy` an `isStage` parameter and a "A place on the way." string; the preview passes `way.isPilgrimageStage`. P5 covers the walk card's use of the same function.

---

### 9. Stage 21-3 seams (named, not specified)

Slice three (PR #86, before the pin) threaded the offline-tiles feature through these files. Stage 21-2 leaves each seam in place and draws nothing for it; Stage 21-3's plan specifies them.

| Seam | Where on iOS | Stage 21-2 on Android |
|---|---|---|
| `StageMorningCardModel.mapsLine(saved:)` → "maps saved for today" / "no offline maps for today — save on wifi" | `StageMorningCard.swift:21-26@7c200bf` | keep the card's `mapsLine: String?` parameter; pass `null` (no line), since without a save action the "save on wifi" line would point at nothing |
| `stageMapsSaved` from `PilgrimageTilesManager.isStageSaved(way)`, read in `.task(id:)` and on `regionsChanged` | `HonorOverviewView.swift:85,175-177,364-369@7c200bf` | none |
| `stageMapsLine` computed as "the day" opens | `ActiveWalkView.swift:300@7c200bf` | none (`null`) |
| `PilgrimageMapsRow` under the download button, held by `mapsRowIsHeld(packagePhase:)` while a package downloads | `PilgrimageRouteView.swift:61-64,197-201@7c200bf` | none; no row |
| `isBusy` counting a tiles save | `PilgrimageRouteView.swift:310@7c200bf` | the package phase only |
| `stageWays`, `mapsEstimateBytes`, `mapsStatus`, `refreshMapsStatus()`, `.onReceive(tiles.regionsChanged)`, `.onChange(of: tiles.phase)` | `PilgrimageRouteView.swift:79-85,149-154,372-376,384-386@7c200bf` | none (reload skips the stage-Way reads, which exist only for the row) |
| `PilgrimageError.mapTooLarge` → "more map than can be saved at once" | `PilgrimageWayImporter.swift:12,23@7c200bf` | declare it in the error type now (P1) or in 21-3; no screen shows it in 21-2 |

---

### 10. About

The two PRs added three rows to About → Data Sources, after the weather row: a body paragraph and two link rows.

```swift
            Text("The maps you walk on are drawn by Mapbox from OpenStreetMap, whose roads and paths are surveyed and kept current by people who walk them.")
                .font(Constants.Typography.body)
                .foregroundColor(.ink)

            linkRow(
                icon: "map",
                label: "© Mapbox",
                url: URL(string: "https://www.mapbox.com/about/maps/")!
            )

            // "© OpenStreetMap contributors" is the credit OSM's terms ask
            // for, word for word — the copyright is the contributors', not a
            // company's. Our own "Map data —" prefix was what wrapped the
            // line, so that went instead.
            linkRow(
                icon: "point.topleft.down.curvedto.point.bottomright.up",
                label: "© OpenStreetMap contributors",
                url: URL(string: "https://www.openstreetmap.org/copyright")!
            )
```
> Pilgrim/Scenes/Settings/AboutView.swift:324-342@7c200bf

Android already ships all three, in the same place and order:

```kotlin
        Text(
            text = stringResource(R.string.about_data_sources_maps_body),
            style = pilgrimType.body,
            color = pilgrimColors.ink,
        )
        Spacer(Modifier.height(8.dp))
        OpenSourceLinkRow(
            icon = Icons.Outlined.Map,
            label = stringResource(R.string.about_data_sources_mapbox_link),
            external = false,
            onClick = {
                CustomTabs.launch(context, Uri.parse("https://www.mapbox.com/about/maps/"))
            },
        )
        OpenSourceLinkRow(
            icon = Icons.Outlined.Route,
            label = stringResource(R.string.about_data_sources_osm_link),
            external = false,
            onClick = {
                CustomTabs.launch(context, Uri.parse("https://www.openstreetmap.org/copyright"))
            },
        )
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/AboutScreen.kt:376-397@0defff85

```xml
    <string name="about_data_sources_maps_body">The maps you walk on are drawn by Mapbox from OpenStreetMap, whose roads and paths are surveyed and kept current by people who walk them.</string>
    <string name="about_data_sources_mapbox_link" translatable="false">© Mapbox</string>
```
> app/src/main/res/values/strings.xml:1157-1158@0defff85

Nothing for U37–U41 to do. The open-pilgrimages and ODbL rows predate slice two on both platforms. (iOS's About section-appear index clash is pilgrim-ios #97, unrelated.)

---

### 11. Flow-analysis gaps that touch the doors, the catalog, the route page or the overview

| Gap | What iOS does at these doors | What Android must do here |
|---|---|---|
| **1. The walk guard** | The route page is unreachable mid-walk (the walk is a full-screen cover; the Ways sheet opens only from Path). The guard's copy, "finish your walk first", surfaces only as the route page's rust footer line, from the commit's re-check, on the page that started the download. Package changes are possible while the overview and its morning card are up (the route page is gone by then). | Path redirects into a walk in progress and disables its button (`WalkStartScreen.kt:179-195,310@0defff85`), so the same reachability holds. The wider guard (walk screen up pre-Start via `walkScreenUp`, live rows, a Begin in flight; P2) answers on entry to each action and shows the same footer line. No door-side change beyond reading the guard's refusal into `failure`. |
| **9. Launch work mid-walk; `installed()` writes** | `installed()` may finish an interrupted Replace (it deletes a package and the marker); the catalog's `load()` and the route page's `reload()` call it on the main actor. | Call `installed()` (U34) from the catalog and route page ViewModels on `Dispatchers.IO`, in the UI process only, never from `:tracker`. |
| **10. A second download erases a Replace's marker** | Reachable from this page: during a Replace's download, a stage tap → "Download this route first?" → "Download" → `beginInstall` → "Replace?" → "Replace" → `replace` writes the marker, `download` throws busy, the catch clears the marker (`PilgrimagePackageManager.swift:238-243@7c200bf`). The alerts stay live during a download (§4.8, §17 D-4). | Match the page (the alerts stay live); the marker fix itself is P2's (check busy before writing it, and file upstream). |
| **11. The catalog's cost and cache** | The Ways sheet lists Ways on every opening (`HonorWaysSheet.swift:113-115@7c200bf`). | The door benefits from U33's `list()` skipping `pilgrimage:` ids before decoding; nothing else at the door. Cache location is P1's. |
| **12. Task restore** | No restore. | §4.11 and §6.2: the catalog and route page restore from their routes (the route page from its id, closing if the entry is gone, showing idle); the morning card's open flag is `rememberSaveable`; the redraw notice is in `SavedStateHandle`. |
| **13. Backgrounded download** | A foreground `URLSession.bytes` stream; backgrounded, it may stall and fail ("the download didn't finish") on the page that started it. | Same, plus Android may freeze the cached process. A U41 row: start a download, background the app for a minute, return; expect "the download didn't finish" or a resumed count, never a half install. |
| **14. Release flag off** | n/a | The third section, both routes and the morning card exist only under `honorEnabled`; the Ways sheet itself is unreachable with it off. A test asserts no pilgrimage route is registered with the flag off. |
| **15. Device transfer** | `pilgrimageOfflineNoteShown` is a `UserDefaults` key, which iCloud backups carry; the ledger and packages are excluded. | The note's key lives in DataStore, which Android's backup carries likewise; check `WaysBackupRulesTest` still passes. On a new phone the note isn't said again, the route has to be downloaded again, and the ledger starts empty, as on iOS. |

Gaps 2–8 are P2's, P3's and P5's (finalize, the ledger writers, water, the summary).

---

### 12. Strings

Every user-visible string in this cluster, verbatim. "data" means the dataset's own text, drawn as is. Format arguments are positional (`%1$s`, `%1$d`). Android numbers use `Locale.US` digits.

| String | Where | Arguments / notes |
|---|---|---|
| `A pilgrimage` | Ways sheet, third section's header | caption |
| `Walk a pilgrimage` | Ways sheet, the row | body, with chevron |
| `A route from the open-pilgrimages dataset, walked one stage at a time.` | Ways sheet, third section's footer | caption |
| `Pilgrimages` | catalog title; the route page's back label | heading |
| `Close` | catalog bar | existing `honor_close` |
| `the routes are out of reach right now` | catalog unreachable view (body, fog); the rust line over the list; the route page's preview failure (caption, fog) | `PilgrimageCopy.catalogUnreachable` |
| `try again` | catalog unreachable view (button face, stone); the preview failure (caption, stone) | existing `honor_overview_try_again` |
| `%1$s · %2$s · %3$s` / `%1$s · %2$s` | the card line (catalog row, route page header) | country (omitted when empty), distance (§5.3), count or progress |
| `1 stage` / `%1$d stages` | card line, progress line fallback | singular only at 1 |
| `stage %1$d of %2$d · %3$s walked` | progress line (installed card line; under the next row) | next stage (1-based), entry's count, total km |
| `you have walked the whole way · %1$s` | progress line, all walked | total km |
| `on your phone` | the badge's label (VoiceOver/TalkBack only) | not drawn |
| `update ready` | the badge's label (VoiceOver/TalkBack only) | not drawn |
| `few places marked yet` | catalog row; route page header | caption, fog 0.7 |
| `%1$s · %2$s` | route page summary fallback | `capitalized` tradition, region (may be empty: "Buddhist · ") |
| `Download` / `Update` / `On your phone` | route page button | |
| `stage %1$d of %2$d` | route page footer, during a download | `max(done-1, 0)`, `total-1` |
| `this route isn't walkable yet` | route page footer (rust) | ASCII apostrophe |
| `the download didn't finish` | route page footer (rust) | |
| `not enough space on this phone to save these voices` | route page footer (rust), disk full | reuse `honor_import_disk_full` (§17 D-5) |
| `finish your walk first` | route page footer (rust) | |
| `the route's stages were redrawn; your kilometres are kept.` | route page footer (fog), once | |
| `start with stage 1` / `next: stage %1$d` / `continue from where you stopped` / `you have walked the whole way` | the next row | rule in §4.4 |
| `Stages` | route page section header | caption |
| `%1$d. %2$s` | stage row title | index+1, stage name (data) |
| `%1$s · %2$s up · %3$s[ · %4$s]` | the facts line (stage row; morning card) | distance, gain, hours, difficulty (data; omitted when empty) |
| `%1$d hours` / `%1$d to %2$d hours` | facts line | never singular (§17 D-6) |
| `reaching for the stages…` | route page, preview loading | U+2026 |
| `Remove` | overflow menu item; Remove alert's button | destructive |
| `ellipsis` | the overflow glyph's spoken name (Android `contentDescription`) | house stand-in (#108) |
| `Replace?` / `Replace` / `Keep it` | Replace alert | `Keep it` exists as `honor_card_keep` |
| `Replace the %1$s? Its stages leave your phone; what you've walked of it is remembered if it comes back. Walks in your journal stay.` | Replace alert message | the installed `route.json`'s name, else `route` |
| `Remove?` / `Remove the %1$s? Its stages leave your phone; what you've walked of it is remembered if it comes back. Walks in your journal stay.` | Remove alert | the entry's name |
| `Download this route first?` / `Download` / `Not now` / `Its stages have to be on your phone before you can walk one.` | Download-first alert | |
| `stage %1$d of %2$d · %3$s` / `stage %1$d of %2$d · %3$s · %4$s` | overview date slot | index+1, count, distance, difficulty |
| `map tiles need a connection; the way itself is on your phone.` | overview, once ever, offline | caption, fog |
| `Walk this stage` | overview Begin's accessibility label | face stays `Begin` |
| `a quiet way` | overview stats on a stage (existing) | §17 D-11 |
| `walk` / `close` | morning card button | |
| `Begin walking this stage` / `Close the day's words` | morning card button's accessibility label | ASCII apostrophe |
| `%1$s, %2$s` | morning card weather | lower-cased label, `%.0f°C`/`%.0f°F` |
| `exclamationmark triangle` | the warning glyph's spoken name (Android) | house stand-in |
| `maps saved for today` / `no offline maps for today — save on wifi` | morning card (Stage 21-3) | U+2014 |
| `%1$s along the stage` | moment preview subline on a stage | distance |
| `A place on the way.` | moment preview (and card) body on a stage | |
| `the day` | walk options row (P5) | subtitle = theme (data) |

**"they"/"their" on these stage surfaces:** none in iOS's copy. The overview hides "walk with their voice" on a stage; "they walked this in …" never shows (a stage's `weather` is nil); the import line's "gathering their voices" can't (a stage's gather is `.ready`); the preview's voice, rest and sitting strings can't (a stage carries only waypoints); "along their way" becomes "along the stage"; "A place they marked." becomes "A place on the way.". R14 holds on this cluster's surfaces with no exception. (The dataset's own words are not app copy; the arrival label "Walked their way: …" is P3's/P5's.)

---

### 13. Test inventory (iOS, to port verbatim, R22)

`UnitTests/Honor/PilgrimageCatalogServiceTests.swift`, class `PilgrimageCatalogModelTests` (the fixtures are built inline: the Francés entry at 332-334, the sparse entry at 336-341, `stage(_:)` at 343-347):

| Test | Lines | Asserts |
|---|---|---|
| `testACardWithoutAPackageJustCountsTheStages` | 349-352 | not installed: `"ES · <764 km> · 33 stages"` |
| `testACardWithAPackageCarriesItsProgressAndLeavesInstallToTheBadge` | 354-361 | installed with stage 0 arrived: contains "stage 2 of 33", not "on your phone" |
| `testTheBadgeMarksTheInstalledRouteAndNothingElse` | 365-370 | no badge when not installed (either `hasUpdate`); "on your phone" when installed and current |
| `testAWaitingUpdateDoesNotClaimToBeAlreadyUpdated` | 375-380 | "update ready"; its symbol differs from the current badge's |
| `testEveryBadgeSpellsItselfOutForVoiceOver` | 384-390 | both badges have a non-empty label and symbol |
| `testASparseRouteSaysSoWithoutHidingItself` | 392-398 | "few places marked yet"; nil when dense; never inside the card line |
| `testStageLineReadsDistanceClimbHoursAndDifficulty` | 400-406 | prefix `<24.2 km>`, contains `<1,419 m>`, "7 to 9 hours", suffix "hard" |
| `testAStageWithOneHourFigureDoesNotSayItTwice` | 408-413 | (4, 4) reads "4 hours", never "4 to 4" |
| `testTheMapsRowIsHeldOnlyWhileThePackageDownloads` | 419-423 | Stage 21-3 (`mapsRowIsHeld`) |
| `testTheStageFactsFormatterIsTheOneBothCallersUse` | 427-439 | the route row and `WayStageFacts` agree; (10 km, 0, 4–4, "") is `"<10 km> · <0 m> up · 4 hours"`; (NaN, ∞) contains "0 to 100 hours" |
| `testTheNextRowOffersResumesAndFinallyCongratulates` | 441-455 | nil → "start with stage 1"; 0 arrived → "next: stage 2"; 1 at 0.58 → "continue from where you stopped"; all 33 → "you have walked the whole way" |
| `testTheButtonSaysWhatItWillDo` | 457-461 | "Download", "Update", "On your phone" |
| `testTheRedrawNoticeIsTheSpecsWords` | 463-466 | the notice's exact words |

`UnitTests/Honor/PilgrimageStageWalkTests.swift` (the `stageWay(index:marks:)` fixture at 87-113: a 1 km stage along the equator, the Orisson waypoint with `names`, `sitMinutes: 5`, `pin`; the Francés stage block):

| Test | Lines | Asserts | Unit |
|---|---|---|---|
| `testTheStageLineStandsWhereADateWould` | 197-206 | `"stage 1 of 33 · <24.2 km> · hard"`; nil without a stage; `isPilgrimageStage` both ways | U38 |
| `testTheLocalNameFollowsAFixedOrderAndNeverEchoesTheLabel` | 316-333 | eu first; skip the label's own language; nil for label-only, nil names, `ru`, `en`-only | U38 (preview header) and U39 (card), shared with P5 |
| `testThePlaceCopyChangesForAStage` | 335-343 | "A place on the way." on a stage; "A place they marked." otherwise; the text wins | U38/U39 |
| `testTheFactsLineReadsInTheWalkersOwnUnit` | 372-381 | the morning card's facts are the four parts joined | U38 |
| `testTheWeatherLineIsSilentWithoutASnapshot` | 383-393 | nil → nil; clear, 9 → starts "clear, ", contains "9" (pins kilometres first) | U38 |
| `testTheOfflineNoteIsSaidOnceAndOnlyForAStage` | 398-407 | the note's words; nil when shown, online, or not a stage | U38 |

`UnitTests/Honor/PilgrimageMapsRowTests.swift`: `testTheMorningCardSaysWhetherTodayIsSaved` (43-46), Stage 21-3's.

iOS has **no** tests of these views' wiring: none for the third section, the catalog's states or reappearance reload, the route page's actions or alerts, the overview's Begin branch, the morning card's presentation, or the coordinator's handoff from a stage row. Android's own tests (plan U37/U38) cover them; add the formatter table (§5.3) and the progress-line forms (§3.1) as tests too.

---

### 14. Corrections to the Android plan

1. **The live phase is the route page's only.** Plan U37: "both the catalog and the route page collect the package manager's phase on every entry, so leaving a route page mid-download and coming back, or a configuration change, shows the download in progress rather than idle." iOS's catalog renders no progress at all; only the route page shows "stage d of n". And neither screen reloads when the phase ends: a route page re-entered mid-download keeps its stale "Download"/"Update" after the commit, and the catalog keeps a stale badge until its next reload (§4.10, §17 D-1, D-2; matched as shipped). **Fix:** the route page's ViewModel collects the phase from its first frame; the catalog doesn't render it; neither reloads on the phase's end; and add the test "a page re-entered mid-download still reads its entry-time state after the commit".
2. **The download must outlive the page's ViewModel.** Plan KTD: "Package downloads run in the UI process, in an app-scoped coroutine with a `StateFlow` phase". Add: the route page's ViewModel calls into that scope and awaits a `Deferred` the manager owns, so a pop or swipe-down (which clears the ViewModel) never cancels the download, as iOS's unstructured `Task` isn't (§4.8).
3. **The badges are glyphs, not words.** Plan U37: "the "on your phone" and "update ready" badges". The words are VoiceOver labels on a 13 pt glyph (`checkmark.circle.fill` in moss, `arrow.down.circle.fill` in stone) beside the name; nothing reads "on your phone" on screen in the catalog (§3.1). The route page has no badge; its button says "On your phone".
4. **"finish your walk first" is a footer line, not an alert.** Plan U37: "every alert with iOS's copy: "Replace?", "Remove?" / "Keep it", "Download this route first?" / "Not now", and "finish your walk first"." It is `PilgrimageCopy`'s line in the route page's status footer, in rust (§4.3). The alerts are three: "Replace?" ("Replace"/"Keep it"), "Remove?" ("Remove"/"Keep it"), "Download this route first?" ("Download"/"Not now"), with the messages in §4.7.
5. **The mid-walk test can't navigate there.** Plan U37 test: "Error path: "finish your walk first" mid-walk". Neither app can open the catalog during a walk (Path redirects into it, `WalkStartScreen.kt:179-195@0defff85`). Test the route page ViewModel with a guard that refuses, and the commit-time refusal with a guard that flips mid-download.
6. **The unreachable view covers an empty catalog too, and only "try again" forces.** Plan U37: "the unreachable copy with "try again"". It also shows for a parsed index with zero routes; the rust line shows only above a non-empty list, only when a load threw (no disk cache at all); reappearance reloads are never forced (§3.2, §3.4).
7. **Numbers need a formatter decision.** Plan U38 test: "the stage overview shows "stage 1 of 33 · 24 km · hard"". With the live data iOS prints "24.2 km" for that stage, and Android's `WalkFormat.distance` would print "24.20 km" (and "764.00 km", "1400 m up" elsewhere). **Fix:** settle O-1 first; test against the §5.3 table, and use a fixture whose distance is 24.0 if the test means "24 km".
8. **The stage line has one live caller.** Plan Context: "the stage-line sites (`HonorOverviewModel.kt`, `HonorWaysViewModel.kt`, `WaysListViewModel.kt`)". iOS calls `WayStageLine` in all three, but the Ways sheet's rows are shares only and Settings → Ways never lists a stage, so only the overview's date slot can show it (§2.1, §5.2). Don't add it to the other two.
9. **U38's file list misses four things.** Plan U38 Files: "`WayMomentHeader.kt` (`WayStageLine`, "N along the stage"), `WayMomentPreview.kt`, the walk options sheet." Add: the full-size `WayMomentHeader`'s local-name line (iOS draws it in the preview too; Android only in the compact header, §8.2); `WayMomentCopy.placeCopy(isStage)` (§8.3); `HonorPreferencesRepository` for the once-ever key with an awaited read (§6.4); a connectivity probe seam (§6.4); `HonorOverview.todayWeather` (§6.5).
10. **U37's file list misses three things.** Plan U37 Files: "Modify: `P/ui/honor/HonorWaysSheet.kt`, `P/ui/navigation/PilgrimNavHost.kt`." Add: `HonorSheetSection`'s header becoming nullable (the route page's first two sections and the catalog's loose group have none); the link-routing tests in `T/ui/navigation/PilgrimNavHostTest.kt` for the two new routes (`waysSheetUp`, `fetchedWayLanding` = `PRESENT`, §1.3); and `WayStageFacts` with the formatter, in a home both the route page and the morning card import.
11. **The tiles seams: say what 21-2 draws.** Plan U38: "The tiles seams stay (`mapsLine`, `isStageSaved`)." iOS 2.0.0's overview always passes a maps line ("no offline maps for today — save on wifi" until a save). Android 21-2 passes `null` (no line), since there is no save to point at; 21-3 wires it (§9). The route page's `PilgrimageMapsRow`, `mapsRowIsHeld` and the tiles clause of `isBusy` are 21-3's too.
12. **"the day" reads the walk's weather, not the overview's.** Plan U38: "**"the day":** the options row mid-walk reopens the card with "close"." The card then takes `viewModel.weatherSnapshot`, the walk's own fetch, and the maps line computed at the tap (§7.3). Android: `WalkViewModel.activeWeather`, else the walk row's stored weather after a UI restart.
13. **The morning card's Begin a11y wording.** Plan U38 test: "Begin opens the morning card; "walk" opens the walk screen pre-Start". Also assert the card button's labels, "Begin walking this stage" / "Close the day's words", and that a swipe-down leaves the overview in place.
14. **Next-row test data.** Plan U37 test: "the next row reads each of its four forms from ledger fixtures." The rule has an ordering the fixtures must pin (§4.4): a partly walked stage 1 reads "continue from where you stopped", not "start with stage 1"; a `stoppedAtFrac` of 0 counts as begun; the tap with every stage walked opens stage 1. Also port the progress line's three forms (§3.1).

---

### 15. Notes by unit

**U32 (catalog service), for the doors:**
- The parsed catalog lives in an app-scoped `StateFlow` the screens read; a second opening in a process shows the list at once (§3.4).
- `load(force)` throws only `catalogUnreachable`, and only with no disk cache; the screens rely on that (§3.2).
- The route page takes the release from the in-memory catalog when it starts (iOS captures `catalogService.catalog?.release ?? ""` at the tap).

**U33 (store, ledger), for the doors:**
- `clearRedrawNotice(routeId)` is called from the route page's reload in the UI process; it is a ledger write and takes U33's locks.
- The route page reads the ledger whether or not the route is installed (the circles of a removed route stay filled).

**U34 (package manager), for the doors:**
- `phase`: `Idle`, `Downloading(done, total)` with `total = stageCount + 1` and `(0, total)` published before `route.json` is fetched, `Failed(error)`. Nothing renders `Failed`.
- `installed()` from the screens' ViewModels on IO, UI process only.
- `download`/`update`/`replace` are callable from a ViewModel whose scope may die; their work runs in the manager's own scope.
- `replaceConfirmation(routeName)` and `removeConfirmation(routeName)` are the manager's copy on iOS; on Android they are string resources (§12) with the route name as the argument.

**U37 (the third door):**
- Third `HonorSheetSection` in `HonorWaysSheetContent`, between "Your own walks" and "From a shared walk"; `hideThen(onOpenPilgrimages)` (§2).
- Routes: a catalog dialog route and a route-page dialog route with `routeId`, both under `honorEnabled`, both stacked above `HONOR_WAYS` (§1.3).
- Catalog ViewModel: `isLoading`, `failure`, `installed`, `ledgers`; `load(force)`; reload on return to RESUMED after the first; the three states (§3.2).
- Catalog row: plate (44 dp, small radius, **parchment** fill on Android, grapheme initial in heading/stone, cleared semantics), name + badge glyph (13 dp), card line, sparse note; one merged button; `onClickLabel = null` (§3.3).
- Route page ViewModel: `installed`, `route`, `ledger`, `failure`, `isLoadingStages`, `stagesFailure`, `showRedrawNotice` (saved), the three alert flags; `reload()` at start and after each own action; the preview only when nothing for this route is installed and the release isn't empty (§4.9).
- Route page sections, button states, footer lines, next-row rule, stage rows, overflow and alerts: §4.2–§4.7, with the copy in §12.
- `open(index)`: installed and the stage Way loads → `hideThen { openStoredWayOverview(stageWayId) }`; else the Download-first alert. No busy check.
- `beginInstall`: another route installed → "Replace?"; else install. Update has no confirmation.
- `isBusy` = the phase is `Downloading` (Stage 21-2).
- Back: system Back → the catalog; swipe-down → the Ways sheet; catalog Close/swipe → the Ways sheet.
- Process-death restore of the route page from its id (§4.11).
- Tests to add beyond the ports: the formatter table (§5.3); the progress line's forms; the badge's semantics; the plate cleared from semantics; link routing with the new routes; the flag-off graph; a page re-entered mid-download (live progress, no reload at the end).

**U38 (the overview's stage branches and the morning card):**
- Date slot `WayStageLine.line(way) ?: departureLine(...)`; offline note after the status line; voice toggle hidden on a stage; Begin's label "Walk this stage"; Begin on a stage opens the card (§6.1, §6.2).
- `pilgrimageOfflineNoteShown` (DataStore key of that name, default false), awaited; a connectivity probe (`activeNetwork` with `NET_CAPABILITY_INTERNET`); shown at most once per install, written when shown (§6.4).
- `HonorOverview.todayWeather` from the existing fetch (§6.5).
- `StageMorningCard(stage, weather, mapsLine: String?, buttonTitle, onAction)` as a `ModalBottomSheet` (`skipPartiallyExpanded`, drag handle, parchment), opened with a `rememberSaveable` flag; "walk" → hide, then `onBegin(choice)` (§6.2, §7).
- Weather line: lower-cased label, ", ", `%.0f°C`/`%.0f°F` by the distance unit; nothing without a snapshot (§7.1).
- Warnings: one row each, rust triangle (`Icons.Outlined.WarningAmber`, spoken "exclamationmark triangle"), caption ink (§7.2).
- The preview: "along the stage" with no hour; the local-name line in the full header; "A place on the way." (§8).
- The morning card for "the day" takes the walk's weather and "close" (§7.3; P5 owns the row).
- Matched as shipped and tested: the synthesized clock ("8h 0m"), "a quiet way", and the geometry's km on a stage overview (§17 D-11).

**U39 (on the walk), from here:** the full and compact headers share `localName`; `placeCopy(isStage)` is the card's too; the overview's marks are U39's (§6.6).

**U41 (device checklist), from here:**
- the third section, catalog states (online, offline with cache, offline with none, "try again");
- a download's "stage d of n"; leave and re-enter mid-download (live progress, then the stale label, matched);
- Replace, Remove, Update (no confirmation), and the Download-first alert;
- the redraw notice once;
- the offline note once (airplane mode, first stage overview), then never;
- the morning card at Begin (weather when there is any), "walk" to the pre-Start walk screen, a swipe-down back to the overview;
- "the day" mid-walk;
- a backgrounded download;
- iPhone checks: whether section headers render uppercase (covers "A pilgrimage", the group headers, "Stages"); what the route page's back button shows and what VoiceOver reads for it; what VoiceOver says for the `ellipsis` and `exclamationmark.triangle` glyphs.

---

### 16. Android additions to record at the gate

- **A-1. The route page is a second sheet, not a push inside the catalog's sheet.** iOS pushes the route page within the catalog sheet's navigation stack; Android swaps sheets as the Ways sheet → picker already does (Stage 21-1). System Back returns to the catalog (iOS's back button); a swipe-down closes both (iOS's one sheet). Reason: one pattern for every Honor sheet, per-page ViewModel lifetimes, restore from a route argument. (Owner decision O-2.)
- **A-2. The catalog's cover plate is parchment, not parchment-secondary.** iOS's plate stands on the system's white row; Android's rows sit on parchment-secondary groups (the existing "wear the app's parchment" row), where the iOS colour would vanish.
- **A-3. Process-death restore** of the catalog, the route page (from its id; it closes when the index no longer lists the route; idle phase), the morning card's open flag, and the shown redraw notice. iOS restores none of these.
- **A-4. The offline note's connectivity reading** is `ConnectivityManager`'s active network with internet capability, standing in for `NWPathMonitor`'s `.satisfied`; its once-ever flag is a DataStore key read only after DataStore has loaded.
- **A-5. Numbers in `Locale.US` digits** (the existing house convention) for the `StatsHelper` stand-in; iOS follows the device's locale.
- **A-6. "the day"'s weather** falls back to the walk row's stored weather when the UI process has restarted and the in-memory snapshot is gone; iOS has one process.
- **A-7. Spoken names for unlabelled glyphs** (`ellipsis`, `exclamationmark triangle`): the Stage 21-1 stand-in for what VoiceOver says (#108's precedent).
- **A-8 (interim, closed by 21-3).** No maps line on the morning card in Stage 21-2.
- **A-9 (if O-1 is approved).** A `StatsHelper`-faithful formatter on the stage surfaces, beside `WalkFormat` on Stage 21-1's.

---

### 17. iOS defects (matched as shipped)

Android ports each exactly as shipped; they go upstream as themed issues later. Severity is what a walker meets.

**D-1. A route page reopened during a download never catches up when it ends (low–medium).** Evidence: §4.10; the page reloads only at appearance and after its own actions (`PilgrimageRouteView.swift:145-148,336-363@7c200bf`), and nothing observes the phase's return to idle. What a walker sees: after the download lands, the page they came back to still says "Download" (or "Update"); a stage tap asks "Download this route first?"; tapping "Download" fetches the whole route again. Leaving and reopening the page fixes it.

**D-2. The catalog shows no download progress and doesn't refresh when one ends (low).** Evidence: §3.4; nothing in the catalog reads `phase`. The installed badge appears only after the walker leaves and comes back to the list.

**D-3. A download's failure is shown only to the page that started it (low).** `.failed` is published (`PilgrimagePackageManager.swift:205-209@7c200bf`) and never drawn; a page opened after the failure, or reopened during the download, shows nothing.

**D-4. The stage rows and the Download-first alert stay live during a download (low; the marker half is medium, P2).** `open(index:)` and the alert's "Download" have no busy check (`PilgrimageRouteView.swift:170,316-334@7c200bf`). A second download reads "the download didn't finish" while the first carries on; through "Replace?" it erases the in-flight Replace's marker (flow gap 10).

**D-5. A full disk on the route page speaks of voices (low).** `PilgrimageCopy.diskFull` reuses the share import's line: "not enough space on this phone to save these voices" (`PilgrimageWayImporter.swift:20@7c200bf`, `HonorImportReducer.swift:34@7c200bf`), and a route has no voices.

**D-6. The facts line's units and plural (low).** "1 hours" and "0 hours" (no singular, `PilgrimageRouteView.swift:20-24@7c200bf`); imperial gain prints hundredths of a foot ("4,655.51 ft up") and distances two decimals of a mile, since `StatsHelper` defaults to `.twoDigits` for altitude too. No live stage has equal hours; every imperial walker sees the feet.

**D-7. A stage the installed package can't produce asks to download a route that is on the phone (low).** The next row and progress line use the catalog entry's `stageCount`, not the installed `route.json`'s (`PilgrimageRouteView.swift:245,250,253@7c200bf`); a stage whose `way.json` is missing or unreadable, or a next index beyond an older install's count, lands in `open(index:)`'s "Download this route first?", whose "Download" re-downloads the same release (`PilgrimageRouteView.swift:316-321,328-334@7c200bf`).

**D-8. "update ready" is any release that differs (low).** `hasUpdate` is `!=`, not "newer" (`PilgrimageCatalogView.swift:200-202@7c200bf`, `PilgrimageRouteView.swift:107@7c200bf`). A catalog served from an older cache offline (P1's fallback) marks the installed route "update ready", and Update installs the older release.

**D-9. Walked stages are invisible to VoiceOver (low; extends pilgrim-ios #108).** The filled circle is the only cue and it is `accessibilityHidden` (`PilgrimageRouteView.swift:290-294@7c200bf`).

**D-10. The summary fallback can end on a separator (very low).** `"\(tradition.capitalized) · \(region ?? "")"` reads "Buddhist · " with no region (`PilgrimageRouteView.swift:205-207@7c200bf`). Live routes all have summaries.

**D-11. A stage's overview shows a clock and counts no one walked (low; survey candidate 2, confirmed).** The stats row reads the dataset's synthesized `theirActiveSeconds` ("8h 0m"), "a quiet way", and the downloaded line's length (about "23.83 km") under the stage line's "24.2 km" (`HonorOverviewView.swift:236-244@7c200bf`). The morning card's "7 to 9 hours" is the dataset's real figure.

**D-12. The redraw notice never leaves the page that showed it (very low).** `showRedrawNotice` is never reset (`PilgrimageRouteView.swift:378-381@7c200bf`): after a later Remove or download on the same page it still says the stages were redrawn.

**D-13. An installed route the index drops has no door (low–medium; survey candidate 7, confirmed).** The catalog lists only the index's routes, and the route page opens only from a row (`PilgrimageCatalogView.swift:119-122@7c200bf`), and Settings → Ways hides stages. A route renamed out of the index can no longer be walked, updated or removed; only a Replace from another route's page takes its files.

**Survey §5, from this cluster's side:**
- Candidate 2 (synthesized clock and "a quiet way"): **confirmed**, plus the two kilometre figures (D-11).
- Candidate 7 (an installed route the index renames or drops has no page): **confirmed** (D-13).
- Candidate 8 (a failed Update commit leaves no route installed): the screen side **confirmed**: after the rollback the page reads "Download" with the failure line ("the download didn't finish" or the disk-full line) and nothing says the route left the phone (§4.9); the cause is P2's.
- Candidate 9 (the guard is "any walk view model"): noted. It is installed in `chooseWay()`, so it exists once the Ways sheet has opened, which every door requires (§1.1).
- Known: the tiles note staying after maps are saved is Stage 21-3's (§6.4). E-15, E-16, the glance, and candidates 1, 3–6 are other clusters'.
- Already filed and touching these screens, not refiled: #108 (separators read aloud, unlabelled glyphs), #109 items 2 and 6 ("N minutes" plurals; the overview's temperature unit), #110 item 3 (a failed link's line on a non-share overview, stages included), #111 item 4 (the card over the map's attribution).

---

### 18. Proposed owner decisions

**O-1. How the stage surfaces format distances and climbs.** iOS's `StatsHelper` prints "764 km", "24.2 km", "1,419 m"; Android's `WalkFormat` prints "764.00 km", "24.20 km", "1419 m". The dataset's figures are round, so every catalog line, facts line, stage line and progress line would differ visibly.
- *Recommendation:* add a `StatsHelper`-faithful formatter (§5.3, probed on both runtimes) and use it on this stage's surfaces; leave Stage 21-1's surfaces as recorded. A gate row records the split. Retrofitting 21-1's surfaces to it can be its own small change later.
- *Alternative:* `WalkFormat` everywhere (one convention, visibly unlike iOS on every stage screen).

**O-2. The route page: a second sheet, or a push inside the catalog's sheet.** iOS pushes it inside the catalog sheet.
- *Recommendation:* a second dialog route, as the picker (A-1): Back → the catalog, swipe-down → the Ways sheet. It reuses `HonorSheetHost`, gives the page its own ViewModel lifetime (iOS's page state dies on pop, which D-1's parity depends on), and restores from its route id.
- *Alternative:* one catalog route with an in-sheet back stack and a horizontal slide. It is closer to iOS visually, but it needs a nested back stack and per-page ViewModel keys to keep D-1's behaviour.

---

## P5. On the walk and after: marks and the camera report, the card body, the water caption, "the day", the arrival reply, and the summary

| | |
|---|---|
| iOS pin | `7c200bf` (v2.0.0) |
| Android HEAD | `0defff85` (branch `docs/stage21-2-plan`) |
| Feeds | U39 (on the walk: marks, camera report, the water caption's slot, the card body, the arrival card's stage form and reply row, "the day"); U40 (after the walk: the summary's stage block). P3 owns the caption's state and the prompt lexicon and glance; P2 the Ways footer and the ledger. |
| Lenses | Behavior, UI/visual, Data, Edge cases |



**What I read.** iOS at `7c200bf`, in full: `Pilgrim/Models/Honor/WayMarkPins.swift`, `Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+MarkPins.swift`, `Pilgrim/Views/PilgrimMapView+CameraReport.swift`, `Pilgrim/Views/PilgrimMapView.swift`, `Pilgrim/Views/PilgrimMapView+HonorWay.swift`, `Pilgrim/Models/Walk/MapManagement/PilgrimAnnotation.swift`, `Pilgrim/Views/MapGlyphImageBuilder.swift`, `Pilgrim/Scenes/ActiveWalk/ActiveWalkView+Honor.swift`, `ActiveWalkView+Map.swift`, `ActiveWalkViewModel+Honor.swift`, `ActiveWalkViewModel+Replies.swift`, `WayPlaceCard.swift`, `WalkOptionsSheet.swift`, `Pilgrim/Scenes/Honor/WayMomentHeader.swift`, `StageMorningCard.swift`, `Pilgrim/Scenes/WalkSummary/HonorSummarySection.swift`, `Pilgrim/Models/Honor/HonorPersistence.swift`, `PilgrimageLedger.swift`; the stage parts of `ActiveWalkView.swift` (state, the options sheet, the stage-day sheet), `ActiveWalkViewModel.swift` (Honor state, `stop`/`cancel`, `bindCompletedRecordings`), `WalkStatsSheet.swift` (the glance row and third stat), `HonorOverviewView.swift` (the mark pins), `WalkSummaryView.swift` (`computeHonorState`), `MainCoordinatorView.swift` (`onWalkCompleted`, `recordStageWalk`), `HonorEngine.swift` (`distanceWalkedMeters`), `PilgrimageWayImporter.swift` (moments, marks, strings); the two PRs' diffs to every shared file (`git diff 517c160^1 8018a55`); tests `UnitTests/Honor/WayMarkPinsTests.swift`, `ActiveWalkHonorTests+MarkPins.swift`, `PilgrimageStageWalkTests.swift`, `PilgrimageStageWalkTests+Replies.swift`, and the PR changes to `ActiveWalkHonorTests.swift` and `HonorJournalTests.swift`; `docs/honor-slice-two-device-pass.md`; fixture `UnitTests/Fixtures/Pilgrimage/stage-00.json`; open PR #91's diff to the three cluster files. Android at `0defff85`: `P/ui/walk/PilgrimMap.kt` (state, the camera subscriptions, the style effect, the Way pin block, release), `P/ui/walk/map/WayPins.kt`, `MapGlyphBitmaps.kt`, `SeekCrescentRenderer.kt` (throttle and camera reads), `HonorWayRenderer.kt` (the `styleHasLoaded` latch), `P/ui/walk/HonorWalkViewModel.kt` (in full), `P/ui/walk/WalkStatsSheet.kt` (the Honor glance row), `P/ui/honor/WayPlaceCard.kt`, `WayPlaceCardState.kt`, `WayMomentHeader.kt` (copy helpers), `HonorArrivalCard.kt`, `HonorOverviewScreen.kt` (the map call), `P/ui/walk/WalkOptionsSheet.kt`, `P/ui/walk/ActiveWalkScreen.kt` (map, options, card layer, reply wiring), `P/ui/walk/WalkViewModel.kt` (`replyHere`), `P/walk/honor/HonorSessionState.kt` (`voiceOriginIndex`), `P/domain/honor/Way.kt`, `HonorTuning.kt`, `HonorEngine.kt` (walked credit), `P/data/honor/HonorSessionEntity.kt` (columns), `P/ui/walk/summary/HonorSummaryModel.kt`, `HonorSummarySection.kt`, `P/honor/HonorWalkRecords.kt`, `P/ui/walk/WalkFormat.kt`, `P/ui/walk/WaypointMarkingSheet.kt` (icon keys), `res/values/strings.xml`. I also surveyed the live dataset (local `open-pilgrimages` at `675d4e3`, 115 stage files) for the facts the UI depends on, and probed iOS's distance formatter with `swift`.

**Live-data facts this cluster depends on** (open-pilgrimages `675d4e3`, every `routes/*/ways/stage-*.json`):
- The busiest stage carries 400 marks, the importer's cap. Mark kinds across the set: transport 1,770, food 1,528, water 1,245, bed 820, supply 734, medical 409. The nearest-40 cut matters.
- Stage moment icons: `house.lodge` 237, `seal` 136, `building.columns` 81, `eye` 35, `book.closed` 16. Only `eye` is one of Android's drawable waypoint keys today (§5.6).
- `pin` stands up to 1,247 m from `at` (E-16's reach, §7.4).
- Every `sitMinutes` is 5 (218 moments), so E-15's "they sat here 5 minutes" is common.
- Local-name keys include `ja` 176, `es` 51, `fr` 34, `zh` 31, `eu` 31, `ko` 29 and 40 others. Only the eight in iOS's order ever show.
- No stage has an empty closing, an empty label, or a `title` different from `stage.name`.

---

### 1. Which marks a screen carries: `WayMarkPins`

```swift
    static let drawFromZoom: CGFloat = 13
    /// Inside a town a stage can carry over a hundred at zoom 13.
    static let maxPerScreen = 40

    static func symbol(for kind: WayMarkKind) -> String {
        switch kind {
        case .water: return "drop.fill"
        case .food: return "fork.knife"
        case .bed: return "bed.double.fill"
        case .transport: return "bus.fill"
        case .supply: return "bag.fill"
        case .medical: return "cross.case.fill"
        }
    }
```
> Pilgrim/Models/Honor/WayMarkPins.swift:9-22@7c200bf

```swift
    static func pins(marks: [WayMark], zoom: CGFloat, near: CLLocationCoordinate2D?) -> [PilgrimAnnotation] {
        guard zoom >= drawFromZoom, !marks.isEmpty else { return [] }
        let chosen: [WayMark]
        if let near {
            let here = CLLocation(latitude: near.latitude, longitude: near.longitude)
// …
            let measured: [(mark: WayMark, meters: CLLocationDistance)] = marks.map { mark in
                (mark: mark, meters: here.distance(from: CLLocation(latitude: mark.at.lat, longitude: mark.at.lon)))
            }
            // A tiebreak on id keeps the selection stable between fixes.
            let nearest = measured.sorted { $0.meters == $1.meters ? $0.mark.id < $1.mark.id : $0.meters < $1.meters }
            chosen = nearest.prefix(maxPerScreen).map(\.mark)
        } else {
            chosen = Array(marks.prefix(maxPerScreen))
        }
        return chosen.map {
            PilgrimAnnotation(coordinate: CLLocationCoordinate2D(latitude: $0.at.lat, longitude: $0.at.lon),
                              kind: .wayMark(id: $0.id, kind: $0.kind))
        }
    }
```
> Pilgrim/Models/Honor/WayMarkPins.swift:27-47@7c200bf

The algorithm, exactly as the units port it:
1. If `zoom < 13` (inclusive gate: 13.0 draws, 12.9 doesn't) or there are no marks, return nothing.
2. With an anchor (`near`): measure each mark's `at` from the anchor with `CLLocation.distance` (Android: the house `wgs84MidLatitudeMeters`, which the map tap already uses for "as `CLLocation.distance` measures it"). Sort ascending by metres; on exactly equal metres, by `id` ascending (Swift `String <`, byte order for ASCII ids, which Kotlin's `compareTo` matches). Take the first 40.
3. Without an anchor: the first 40 in the Way's own mark order (the file's order; the importer doesn't sort marks, `PilgrimageWayImporter.swift:228-236`).
4. Each pin stands at the mark's own `at` (lat, lon not swapped: `testAPinLandsOnItsMarksOwnCoordinate`) and carries `.wayMark(id:kind:)`.

Every kind of mark is drawn, at any `offLineMeters` (a fountain 250 m off the line draws; only the water watcher filters on 60 m). A mark's `name` is never shown anywhere in iOS: the importer caps it and nothing reads it.

```swift
        case wayMark(id: String, kind: WayMarkKind)
    }
}
// …
    var wayMomentID: String? {
        switch self {
        case .wayVoice(let id, _), .wayPhoto(let id), .wayRest(let id, _),
             .waySit(let id, _), .wayWaypoint(let id, _, _):
            return id
        default:
            return nil
        }
    }
```
> Pilgrim/Models/Walk/MapManagement/PilgrimAnnotation.swift:31-49@7c200bf

A mark has no moment id, so no screen can open a card or preview from it.

**Android today.** Nothing: no `WayMarkPins.kt`. The `WayMark` and `WayMarkKind` model is ported (`P/domain/honor/Way.kt:160-190`), and so is the refresh distance:

```kotlin
    /** Stage-only: how far the walker moves before the mark pins are reselected. */
    const val MARK_PIN_REFRESH_METERS = 200.0
```
> app/src/main/java/org/walktalkmeditate/pilgrim/domain/honor/HonorTuning.kt:31-32@0defff85

**U39 builds** `P/ui/walk/map/WayMarkPins.kt`: `DRAW_FROM_ZOOM = 13.0`, `MAX_PER_SCREEN = 40`, `fun pins(marks: List<WayMark>, zoom: Double, near: WayCoordinate?): List<WayMarkPin>` with the rules above, and `fun symbol(kind): String` returning the six SF names (kept as strings so `testEveryKindHasItsOwnGlyph` ports verbatim) plus a `markGlyphVector(kind)` for the raster (§5.4). The `WayMarkPin` carries `markId`, `kind`, `at`; it never carries a moment id.

### 2. The walk screen's marks: anchor, camera, re-selection

```swift
    func bindMarkPins() {
        markPinAnchor = nil
        honorLocationFixes
            .sink { [weak self] location in
                self?.refreshMarkPinsIfWalkerMoved(to: location.coordinate)
            }
            .store(in: &honorCancellables)
    }

    func refreshMarkPinsIfWalkerMoved(to coordinate: CLLocationCoordinate2D) {
        if let anchor = markPinAnchor {
            let moved = CLLocation(latitude: anchor.latitude, longitude: anchor.longitude)
                .distance(from: CLLocation(latitude: coordinate.latitude, longitude: coordinate.longitude))
            guard moved >= HonorTuning.markPinRefreshMeters else { return }
        }
        markPinAnchor = coordinate
        applyMarkPins()
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+MarkPins.swift:13-30@7c200bf

```swift
    func mapCameraDidChange(center: CLLocationCoordinate2D, zoom: CGFloat) {
        mapCameraCenter = center
        // `Int(_:)` traps on a NaN, which no clamp catches.
        guard zoom.isFinite else { return }
        let wasLevel = Int(mapCameraZoom)
        mapCameraZoom = zoom
        guard Int(zoom) != wasLevel else { return }
        applyMarkPins()
    }
// …
    private func applyMarkPins() {
        guard let marks = way?.marks, !marks.isEmpty else {
            if !honorMarkPins.isEmpty { honorMarkPins = [] }
            return
        }
        let pins = WayMarkPins.pins(marks: marks, zoom: mapCameraZoom, near: markPinAnchor ?? mapCameraCenter)
        if pins != honorMarkPins { honorMarkPins = pins }
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+MarkPins.swift:36-58@7c200bf

```swift
    @Published var honorMarkPins: [PilgrimAnnotation] = []
    var markPinAnchor: CLLocationCoordinate2D?
// …
    var mapCameraZoom: CGFloat = PilgrimMapView.followPuckZoom
    var mapCameraCenter: CLLocationCoordinate2D?
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift:138-146@7c200bf

```swift
    static let followPuckZoom: CGFloat = 16
```
> Pilgrim/Views/PilgrimMapView.swift:14@7c200bf

What iOS does, step by step:
- **The zoom starts at 16**, the follow-puck zoom, before any camera report. So the first fix after Start draws marks at once (16 ≥ 13).
- **The anchor** is set by `bindMarkPins()` at Start (`startHonorEngineIfNeeded`, `ActiveWalkViewModel+Honor.swift:97`) to nil, then follows `honorLocationFixes`, which is `$currentLocation` mapped to `CLLocation`s (`ActiveWalkViewModel+Honor.swift:442-453`). Before Start, `currentLocation` already holds the device's last raw fix; once recording, each recorded sample (the builder's own accuracy check, the first sample exempt). The engine's 50 m filter doesn't apply. As a `@Published`, it replays the current value on subscribe, so **Start anchors at once on the pre-Start fix** and the marks appear the moment Start is tapped:

```swift
        guard status.isActiveStatus else {
            if let lastLocation = locations.last {
                currentLocationRelay.accept(lastLocation.asTemp)
            }
```
> Pilgrim/Models/Walk/WalkBuilder/Components/LocationManagement.swift:266-269@7c200bf
- **Re-selection on movement:** the first fix always anchors and applies. After that, only a fix at least 200 m from the anchor (`>=`, by `CLLocation.distance`) moves the anchor and applies. The anchor is the fix that triggered, not the walker's path: 150 m out and 150 m back never re-selects.
- **Re-selection on zoom:** each camera report stores the center unconditionally, then, only if the zoom is finite, stores the zoom and applies when the integer part (`Int(_:)`, truncation toward zero) changed against the stored zoom. A NaN zoom stores the center and returns before touching the zoom. On the walk screen a center move alone never re-selects, even a kilometre's pan: the marks follow the walker, not the camera.
- **The anchor, else the camera:** `near` is `markPinAnchor ?? mapCameraCenter`. The center only stands in between Start and the first fix, or before Start (below).
- **Publishing:** only on a real change (`!=` against the published list, kind and coordinate equality, `PilgrimAnnotation.swift:52-57`). A Way with no `marks` (own and shared walks) never publishes at all (`testASharedWayHasNoMarksToDraw` counts zero publishes).
- **Before Start.** `mapCameraDidChange` isn't gated on the walk's status, and `applyMarkPins` checks only `way?.marks`. So on the pre-walk screen a camera report whose integer zoom differs from the stored one draws marks around the camera's center. In practice: the walk map seeds its camera at zoom 16 from a cached location, else at zoom 14 from the last walk's end (`MapCameraSeed.swift:21-34`). A seed at 14 reports level 14, which differs from 16, so marks are applied at 14 around the camera. At 16, nothing changes until a pinch crosses a level. Then the follow-puck viewport takes the camera to 16 and re-applies.
- **Teardown** (`stop()` and `cancel()`, via `teardownHonor`) clears the published marks and the anchor, but not `mapCameraZoom` or `mapCameraCenter`:

```swift
        honorCards.removeAll()
        honorMarkPins.removeAll()
        markPinAnchor = nil
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:146-148@7c200bf

  A camera report after teardown that crosses a level re-applies marks around the camera (the Way is still set). The walk screen is leaving by then, so nothing visible follows.
- **"Without a fix, the first forty in file order"** (`near == nil`) is unreachable on both iOS screens: the walk applies only from a fix or from a report that also sets the center, and the overview sets center and zoom together. It is reachable only in `WayMarkPinsTests`. Port it anyway; the test pins it.

**Android today.** `HonorWalkViewModel` has no camera input and no marks. Its walker fix is `here()` (`controller.state.lastFix()`, the walk's `lastLocation`, `HonorWalkViewModel.kt:727-728`, `:1019-1028`), which exists only from the walk's first recorded point. So where iOS anchors at the instant of Start on the pre-Start fix, Android anchors a fix or two later; between the two the camera center stands in (the follow viewport centres on the puck at zoom 16, so the selection is nearly the same). Record this as a small dated difference (§17), or feed the anchor the map's last puck position at Start if the device pass shows a visible gap.

**U39 builds**, in `HonorWalkViewModel` (UI process; `:tracker` never sees marks):
- A `MarkSelection` state holder (pure, unit-testable): `anchor: WayCoordinate?`, `cameraZoom: Double = 16.0` (`FOLLOW_PUCK_ZOOM`; Android's follow viewport already uses 16, so name one constant and use it in both places), `cameraCenter: WayCoordinate?`. Its operations are `onFix(c)` (anchor-or-200 m rule, `>=`), `onCamera(center, zoom)` (store the center; NaN or infinite zoom returns; apply only on a `toInt()` change, with `Double.toInt()` truncating toward zero like `Int(_:)`), and `reset()` at Start and at the end of the walk.
- A `marks: StateFlow<List<WayMarkPin>>`, published with `distinctUntilChanged`, driven by the Way on screen (preview or session), the fix flow, and the camera reports. It doesn't depend on the release flag beyond the existing `enabled` gate.
- Start resets the anchor (iOS's `bindMarkPins()`); the camera state survives Start, as on iOS.
- A Way change (the preview's Way giving way to the session's, or a different Way) re-applies with the current anchor and camera, like the overview's `.task(id: way.id)`.
- The UI process can restart mid-walk. The view model then starts with no anchor and zoom 16, and the first fix re-anchors. That is iOS's Start behaviour and needs nothing in Room.

### 3. The overview's marks

```swift
    /// Where the map's camera actually is, as the map reports it. Nil until
    /// the first report: this screen opens fit to the whole Way, a zoom it
    /// never chose and cannot know, and marks must not be drawn on a guess.
    @State private var liveCenter: CLLocationCoordinate2D?
    @State private var liveZoom: CGFloat?
```
> Pilgrim/Scenes/Honor/HonorOverviewView.swift:72-76@7c200bf

```swift
                pinAnnotations: markPins + (rendering?.pins ?? []),
                onAnnotationTap: { pin in
                    guard let id = pin.kind.wayMomentID else { return }
                    previewMoment = way.moments.first { $0.id == id }
                },
// …
                onCameraChanged: { center, zoom in
                    liveCenter = center
                    liveZoom = zoom
                    refreshMarkPins()
                }
```
> Pilgrim/Scenes/Honor/HonorOverviewView.swift:125-138@7c200bf

```swift
    private func refreshMarkPins() {
        guard let zoom = liveZoom else {
            markPins = []
            return
        }
        markPins = WayMarkPins.pins(marks: way.marks ?? [], zoom: zoom, near: liveCenter)
    }
```
> Pilgrim/Scenes/Honor/HonorOverviewView.swift:356-362@7c200bf

The overview differs from the walk:
- There is no anchor and no fix: the selection is always around the camera center.
- It re-selects on every report the map lets through: an integer zoom change or a center more than 200 m from the last report (§4). So a pan at a fixed zoom re-selects here, where on the walk it doesn't.
- Nothing draws until the first report. The overview opens fit to the whole stage, almost always below 13, so a whole stage shows no marks until the walker zooms in (device pass, `docs/honor-slice-two-device-pass.md:74-78`).
- `.task(id: way.id)` also calls `refreshMarkPins()` (`HonorOverviewView.swift:168-176`). With no report yet that keeps the list empty.
- The camera state is `@State`: it survives a re-render, not a new screen.

**Android today.** `HonorOverviewScreen` passes `wayPins` only (`HonorOverviewScreen.kt:115-127`); there is no camera report. **U39 adds** `liveCenter`/`liveZoom` as nullable state (a `rememberSaveable` pair is fine, though iOS's `@State` doesn't survive a new screen either), fed by the map's camera report, and the marks list computed with `WayMarkPins.pins(way.marks.orEmpty(), zoom, liveCenter)` only when `liveZoom != null`. A preview tap on a mark does nothing (no moment id).

### 4. The camera report

```swift
    static func installCameraReport(on mapView: MBMapView, coordinator: Coordinator) {
        mapView.mapboxMap.onCameraChanged.observe { [weak coordinator, weak mapView] _ in
            guard let coordinator, let mapView else { return }
            reportCamera(on: mapView, coordinator: coordinator, throttled: true)
        }.store(in: &coordinator.cameraReportCancelables)
        // A gesture's last frame can land inside the throttle window and be
        // dropped; idle is the one report that must always get through.
        mapView.mapboxMap.onMapIdle.observe { [weak coordinator, weak mapView] _ in
            guard let coordinator, let mapView else { return }
            reportCamera(on: mapView, coordinator: coordinator, throttled: false)
        }.store(in: &coordinator.cameraReportCancelables)
    }

    /// Four reports a second at most.
    private static var cameraReportMinInterval: CFTimeInterval { 0.25 }
```
> Pilgrim/Views/PilgrimMapView+CameraReport.swift:14-28@7c200bf

```swift
    private static func reportCamera(on mapView: MBMapView, coordinator: Coordinator, throttled: Bool) {
        guard let report = coordinator.onCameraChanged else { return }
        let now = CACurrentMediaTime()
        if throttled, now - coordinator.lastCameraReportUptime < cameraReportMinInterval { return }
        let state = mapView.mapboxMap.cameraState
        // `Int(_:)` traps on a NaN, which no clamp catches.
        guard state.zoom.isFinite else { return }
        let level = Int(state.zoom)
        let movedFar = coordinator.lastReportedCenter.map { last in
            CLLocation(latitude: last.latitude, longitude: last.longitude)
                .distance(from: CLLocation(latitude: state.center.latitude, longitude: state.center.longitude))
                > HonorTuning.markPinRefreshMeters
        } ?? true
        guard level != coordinator.lastReportedZoomLevel || movedFar else { return }
        coordinator.lastCameraReportUptime = now
        coordinator.lastReportedZoomLevel = level
        coordinator.lastReportedCenter = state.center
        report(state.center, state.zoom)
    }
```
> Pilgrim/Views/PilgrimMapView+CameraReport.swift:34-52@7c200bf

```swift
        var onCameraChanged: ((CLLocationCoordinate2D, CGFloat) -> Void)?
        var cameraReportCancelables: [AnyCancelable] = []
        var lastReportedZoomLevel: Int?
        var lastReportedCenter: CLLocationCoordinate2D?
        var lastCameraReportUptime: CFTimeInterval = 0
```
> Pilgrim/Views/PilgrimMapView.swift:648-652@7c200bf

The rules, in the order iOS applies them:
1. **No consumer, no work.** Only a map given `onCameraChanged` reports (the walk and the overview; the summary, journal and share maps pass none). The callback is re-read on every `updateUIView` (`PilgrimMapView.swift:209`), so the latest closure always runs.
2. **The time throttle applies only to camera-changed events:** a camera-changed event less than 0.25 s (`<`) after the last *report* is dropped. Idle bypasses the time throttle.
3. **A NaN or infinite zoom is dropped** (and doesn't move the throttle clock).
4. **The change filter applies to both events, idle included.** A report goes out only when the integer zoom level (`Int`, truncating) differs from the last reported level, or the center is more than 200 m (`>`, strictly) from the last reported center. The first event always passes (both last values start nil).
5. On a report, the clock, level and center are stored, then the callback gets the camera's center and its raw (fractional) zoom.

So "always on idle" means only that idle is never time-throttled. An idle with no level change and no 200 m move sends nothing. The comment above the idle observer ("idle is the one report that must always get through") overstates the code; the plan inherited the overstatement (§15, correction C3). The rule's purpose holds: a gesture's last frame dropped by the 0.25 s window is caught at idle, if it moved the level or 200 m.

Two consequences an implementer can trip on:
- The 200 m test is against the last **reported** center, not the last event's. A slow pan of 150 m, then 100 m more, reports at the second step (250 m from the last report).
- A fractional zoom change inside one level, with no 200 m move, never reports. So the walk's stored `mapCameraZoom` can lag the real zoom within a level, which never matters, because both the gate (13.0) and the walk's re-select are integer-level decisions. One corner does matter: a pinch from 13.4 to 12.95 changes the level (13 to 12) and reports; from 13.0 to 13.9 doesn't.

Teardown cancels both observers and clears the callback at once (`dismantleUIView`, `PilgrimMapView+CameraReport.swift:57-61`). The throttle state lives on the coordinator, so it survives style reloads and is lost with the map view.

**Android today.** `PilgrimMap` already subscribes to camera changes and idle, but only for the seek crescent and only while a seek runs:

```kotlin
        val cameraSub = view.mapboxMap.subscribeCameraChanged {
            renderer.evaluateCrescentVisibility(
                throttled = true,
                reduceMotion = reduceMotionState.value,
            )
        }
        val idleSub = view.mapboxMap.subscribeMapIdle {
            renderer.evaluateCrescentVisibility(
                throttled = false,
                reduceMotion = reduceMotionState.value,
            )
        }
        onDispose {
            cameraSub.cancel()
            idleSub.cancel()
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/PilgrimMap.kt:477-491@0defff85

The crescent's throttle is a pure class with an injected `uptimeMillis` and its camera reads behind an interface (`SeekCrescentRenderer.kt:141`, `:248-258`, `:439`), which is the pattern to copy.

**U39 builds:**
- A pure `CameraReportThrottle` (`P/ui/walk/map/`): `fun report(center: WayCoordinate, zoom: Double, throttled: Boolean, nowUptimeMillis: Long): Boolean` with `MIN_INTERVAL_MILLIS = 250`, `lastLevel: Int? = null`, `lastCenter: WayCoordinate? = null`, `lastReportUptime = 0L`, and the five rules above in that order. The 200 m comparison is `> HonorTuning.MARK_PIN_REFRESH_METERS` with `wgs84MidLatitudeMeters`. It is JVM-tested with a fake clock: first event passes, a second within 250 ms is dropped, the same at idle passes only with a level change or more than 200 m, exactly 200 m doesn't pass, NaN is dropped without moving the clock.
- A new `PilgrimMap` parameter `onCameraChanged: ((center: WayCoordinate, zoom: Double) -> Unit)? = null`, held with `rememberUpdatedState`. One `DisposableEffect(mapView, hasCameraReport)` registers `subscribeCameraChanged` (throttled) and `subscribeMapIdle` (not throttled) only when the parameter is non-null, reading `view.mapboxMap.cameraState.center` and `.zoom`, and cancels both in `onDispose`. The throttle object is `remember(mapView)` so it survives recomposition and style reloads, as iOS's coordinator does. Keep it separate from the seek subscriptions: a stage walk is never a seek, and the seek's are gated on `seekFogActive`.
- The walk screen passes `honorWalkViewModel::onCameraChanged` only when the Way on screen has marks (no work on other walks, matching iOS's per-screen opt-in in spirit; iOS's walk map always passes a closure, but its consumer returns at once without marks). The overview passes its own handler.

### 5. Drawing the marks

#### 5.1 One pin per mark, 18 pt, stone, faded

```swift
        case .wayMark(_, let kind):
            return wayPoint(pin, symbol: WayMarkPins.symbol(for: kind), tint: .stone,
                            coordinator: coordinator, size: 18)
// …
    private static func wayPoint(_ pin: PilgrimAnnotation, symbol: String, tint: UIColor,
                                 coordinator: Coordinator, size: CGFloat = 22) -> PointAnnotation {
        var point = PointAnnotation(coordinate: pin.coordinate)
        let glyph = MapGlyph.wayMark(symbol: symbol, tint: tint)
        if let image = MapGlyphImageBuilder.image(for: glyph, size: size) {
            point.image = .init(image: image, name: "\(MapGlyphImageBuilder.cacheKey(for: glyph))-\(Int(size))")
        }
        point.iconSize = 1.0
        return point
    }
```
> Pilgrim/Views/PilgrimMapView+HonorWay.swift:311-334@7c200bf

```swift
        return UIGraphicsImageRenderer(size: target, format: format).image { _ in
            UIColor.parchment.resolvedColor(with: lightTraits).withAlphaComponent(0.9).setFill()
            UIBezierPath(ovalIn: CGRect(origin: .zero, size: target)).fill()
            let origin = CGPoint(
                x: (target.width - symbolImage.size.width) / 2,
                y: (target.height - symbolImage.size.height) / 2
            )
            symbolImage.draw(at: origin, blendMode: .normal, alpha: 0.55)
        }
```
> Pilgrim/Views/MapGlyphImageBuilder.swift:144-152@7c200bf

A mark is the same faded pin a Way moment wears, at 18 pt instead of 22: a light-parchment disc at 0.9, the SF symbol at `pointSize: size * 0.55` (9.9 pt) and 0.55 alpha, centred, tinted stone from the light palette in both appearances (`MapGlyphImageBuilder.swift:85-89`, `:135-139`). The image name gains `-18`, so an 18 pt and a 22 pt raster of the same glyph never share a Mapbox image slot. `iconSize` 1.0. No circle under it (`PilgrimMapView.swift:460-464`), no halo, no text.

#### 5.2 Never tappable

```swift
            for pin in currentPinAnnotations {
                switch pin.kind {
                case .whisper, .cairn, .photo, .wayVoice, .wayPhoto, .wayRest, .waySit, .wayWaypoint:
```
> Pilgrim/Views/PilgrimMapView.swift:778-780@7c200bf

`.wayMark` isn't a tap target, so a tap near a mark falls through to the nearest real target within 25 m, or to nothing. A mark also doesn't block a tap on a moment pin it overlaps. On Android the map tap builds its targets from `wayPins` and `proximityPins` only (`PilgrimMap.kt:509-522`); the marks must stay out of that list.

#### 5.3 Z-order: what iOS actually renders

```swift
            pinAnnotations: waypointPins + viewModel.proximityPins + viewModel.honorMarkPins + viewModel.honorPins,
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkView+Map.swift:26@7c200bf

```swift
        if let pointManager = coordinator.pointManager {
            pointManager.annotations = buildPoints(from: pinAnnotations, coordinator: coordinator)
            pointManager.iconAllowOverlap = true
        }
```
> Pilgrim/Views/PilgrimMapView.swift:398-401@7c200bf

On iOS every point pin (the walker's waypoints, whispers, cairns, marks, Way moments) is one feature in **one** `PointAnnotationManager`, one symbol layer, with icon overlap allowed. The marks come after the walker's own pins and before the Way's moments in the array (the overview: `markPins + rendering.pins`). The code comment says marks are "drawn under the moment pins" (`PilgrimAnnotation.swift:29-30`), and the device pass checks "Service pins draw under the moment pins" (`docs/honor-slice-two-device-pass.md:74`).

What Mapbox draws rests on its runtime, not Pilgrim source: in one symbol layer, `symbol-z-order` defaults to `auto`, which sorts overlapping symbols by `symbol-sort-key` if set, otherwise **by viewport y** whenever icon overlap is allowed (Mapbox style spec, `symbol-z-order`). Pilgrim sets no sort key. So where a mark and a moment pin overlap, the one lower on screen draws on top; the array order only breaks ties. On iOS a mark can draw over a moment pin. "Under the moment pins" is the intent, not what renders.

The circle manager (meditation and voice circles, end and photo halos) is created before the point manager (`PilgrimMapView.swift:380-397`), so marks are above every circle.

**Android today.** The Way pins have their own manager, created lazily on the first update pass that has pins, after every other manager:

```kotlin
    // Created on the first pass that has Way pins, after every other
    // manager, so the pins draw on top (manager creation order is z-order).
    // It takes no taps of its own: the map's tap finds the pin on the ground.
    var wayPinManager by remember { mutableStateOf<PointAnnotationManager?>(null) }
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/PilgrimMap.kt:372-375@0defff85

```kotlin
            if (wayPins.isNotEmpty() && wayPinManager == null) {
                wayPinManager = view.annotations.createPointAnnotationManager().also(::allowIconOverlap)
            }
            val wayMgr = wayPinManager
            if (wayMgr != null && renderedWayPins != wayPins) {
                wayMgr.deleteAll()
                wayPins.forEach { pin ->
                    wayMgr.create(
                        PointAnnotationOptions()
                            .withPoint(Point.fromLngLat(pin.longitude, pin.latitude))
                            .withIconImage(pin.image)
                            .withIconSize(1.0),
                    )
                }
                renderedWayPins = wayPins
            }
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/PilgrimMap.kt:1432-1447@0defff85

**Two ways to draw the marks on Android:**
- **(a) In the Way pin manager, before the moment pins** (recommended). The mark pins and the moment pins become one list, marks first, rebuilt with `deleteAll` and `create` when either changes (the existing `renderedWayPins` gate, keyed on both lists). With `allowIconOverlap` this is the same single symbol layer iOS draws, so the viewport-y interleave matches iOS exactly. It also removes the creation-order problem below. The cost is one rebuild of at most 40 marks plus the stage's moments per re-select (every 200 m or level change), which is what iOS does too: its whole point manager is rebuilt on any change (`PilgrimMapView.swift:365-401`).
- **(b) A marks manager of its own, below the Way pin manager** (the plan's decision). Marks then always draw under the moment pins, which is iOS's stated intent but not its rendering; record it at the gate. Creation order is z-order, so the marks manager must be created first, in the same update step as the Way pin manager, whenever the Way carries marks, or (order-independent) the Way pin manager gets a named `layerId` and the marks manager is created with `AnnotationConfig(belowLayerId = WAY_PIN_LAYER_ID)`. Without one of these, a stage whose marks first appear after its pins (the overview, always: marks wait for the camera report) draws its marks above the pins.

Either way, the marks sit above the proximity and waypoint managers, as on iOS (where everything shares one layer above the circles). See owner decision D1.

#### 5.4 The six glyphs on Android

iOS draws the six SF Symbols. Android needs Material stand-ins, rendered by the existing `renderWayPin` path in `WayPins.kt` (the disc, the 0.55 scale and alpha; it already takes a size via `WAY_PIN_SIZE_DP`, so a `sizeDp` parameter carries 18). All are in `material-icons-extended`, which the app already depends on (`app/build.gradle.kts:242`):

| Kind | iOS symbol | Material stand-in |
|---|---|---|
| water | `drop.fill` | `Icons.Filled.WaterDrop` |
| food | `fork.knife` | `Icons.Filled.Restaurant` |
| bed | `bed.double.fill` | `Icons.Filled.Bed` |
| transport | `bus.fill` | `Icons.Filled.DirectionsBus` |
| supply | `bag.fill` | `Icons.Filled.ShoppingBag` |
| medical | `cross.case.fill` | `Icons.Filled.MedicalServices` |

Filled, since all six SF names are `.fill` variants (the moment pins' outlined icons match their unfilled SF names). Tint `WayPinTint.STONE` (`0xFF8B7355`, light palette in both appearances). Cache one raster per kind per density, like `rememberWayMapPins`; six looks at most.

#### 5.5 When the Way changes, and when the style reloads

```swift
            coordinator.lastAppliedAnnotations = nil
            if let old = coordinator.circleManager { mapView.annotations.removeAnnotationManager(withId: old.id) }
            if let old = coordinator.pointManager { mapView.annotations.removeAnnotationManager(withId: old.id) }
            coordinator.circleManager = nil
            coordinator.pointManager = nil
            Self.applyRouteSource(coordinator.pendingSegments, walkingColor: coordinator.walkingColor, on: mapView, coordinator: coordinator)
            Self.applyAnnotations(coordinator.pendingAnnotations, activePhotoID: coordinator.pendingActivePhotoID, on: mapView, coordinator: coordinator)
```
> Pilgrim/Views/PilgrimMapView.swift:182-188@7c200bf

- **Style reload.** iOS tears down both managers and re-applies the pending pin list, marks included, inside the style-loaded callback. The marks come back exactly as selected; nothing re-selects. `applyAnnotations` is gated on `styleHasLoaded || isStyleLoaded` (`PilgrimMapView.swift:342`), the latch that keeps a never-re-rendering screen (the overview) from leaving its pins pending.
- **Android:** the style effect removes and nulls `wayPinManager` and `renderedWayPins` (`PilgrimMap.kt:656-658`); the update lambda then recreates it after the style's managers. Under option (a) the marks ride along with no extra code. Under (b) the marks manager needs the same remove, null and key reset, in that effect and in `onRelease` (`PilgrimMap.kt:1487-1489`), and must be recreated before the pins manager. The update lambda already bails until `polylineManager` exists (`PilgrimMap.kt:900`), the Android form of iOS's latch for annotation managers; the Honor layers' own latch is `HonorWayRenderer.styleHasLoaded` (`HonorWayRenderer.kt:197-218`, from `c66f19ae`), which the marks don't need.
- **The Way changes.** On the walk, the Way is fixed for the view model's life; Android swaps the preview's Way for the session's (§2) and should re-select. On the overview, `.task(id: way.id)` re-selects with the current camera.
- **Marks and the rasters don't change with appearance.** A theme flip redraws the same light-palette pins.

#### 5.6 Stage moment icons Android can't draw today

Not marks, but the same map and the card header. iOS draws a waypoint's dataset icon if the system knows it, else `mappin` (`WayMomentHeader.swift:54`; `MapGlyphImageBuilder.swift:94-99`). The dataset's icons are `house.lodge`, `seal`, `building.columns`, `eye` and `book.closed`, all real SF Symbols, so iOS draws each.

```kotlin
        fun resolvedWaypointIcon(icon: String): String =
            if (icon in DRAWABLE_WAYPOINT_ICONS) icon else WAYPOINT_CUSTOM_ICON_KEY

        private val DRAWABLE_WAYPOINT_ICONS: Set<String> =
            PRESET_CHIPS.map { it.iconKey }.toSet() + WAYPOINT_CUSTOM_ICON_KEY
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/map/WayPins.kt:71-75@0defff85

Android draws only the six preset chip icons plus `mappin`. So 470 of the dataset's 505 stage moments (every `house.lodge`, `seal`, `building.columns` and `book.closed`) would wear `mappin` on Android's overview pins, walk pins, card header and preview, where iOS shows a lodge, a seal, columns or a book. That's a parity break the plan doesn't name (correction C6). U38/U39 add four stand-ins to `iconKeyToVector` and the drawable set: `house.lodge` to `Icons.Outlined.Cottage`, `seal` to `Icons.Outlined.Verified`, `building.columns` to `Icons.Outlined.AccountBalance`, `book.closed` to `Icons.Outlined.Book` (outlined, like the other unfilled SF names). They join `PIN_GLYPHS` in `WayPins.kt` so the slot-table rule there holds. The `spokenSymbolName` fallback (`WayMomentHeader.kt`) keeps reading the SF name, as iOS's VoiceOver does.

### 6. Where the water caption shows

P3 owns the caption's state: when `.markAhead` fires, the metres it carries, its persistence and its haptic. This section is where and how it shows.

```swift
    func showMarkCaption(mark: WayMark, meters: Double) {
        softTapCaption = "water in \(WayDistance.string(meters: max(0, meters.isFinite ? meters : 0)))"
        let generation = honorGeneration
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.softTapCaptionSeconds) { [weak self] in
            guard let self, self.honorGeneration == generation else { return }
            self.softTapCaption = nil
        }
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+MarkPins.swift:63-70@7c200bf

```swift
    @ViewBuilder
    private var thirdStat: some View {
        if let caption = viewModel.softTapCaption {
            Text(caption)
                .font(Constants.Typography.caption)
                .foregroundColor(.fog)
                .lineLimit(2)
                .multilineTextAlignment(.center)
        } else if viewModel.mode == .honor {
            statColumn(value: distanceRemaining, label: "Remaining")
        } else {
            statColumn(value: viewModel.steps, label: "Steps")
        }
    }
```
> Pilgrim/Scenes/ActiveWalk/WalkStatsSheet.swift:409-422@7c200bf

```swift
    private var minimizedAccessibilityValue: String {
        let third: String
        if let caption = viewModel.softTapCaption {
            third = caption
        } else if viewModel.mode == .honor {
            third = "\(distanceRemaining) remaining"
        } else {
            third = "\(viewModel.steps) steps"
        }
        let stats = "\(viewModel.duration), \(viewModel.distance), \(third)"
```
> Pilgrim/Scenes/ActiveWalk/WalkStatsSheet.swift:435-444@7c200bf

- **The slot:** the minimized stats bar's third column, in place of "Remaining". Caption font, fog, up to 2 lines, centred. Only the minimized bar: the expanded sheet doesn't show it. Nothing else changes on screen: no card (`testWaterAheadBorrowsTheCaptionLineAndNothingElse` asserts `honorCards` stays empty), no pin highlight, no banner.
- **The text:** `"water in " + WayDistance.string(meters:)`, so `"water in 280 m"` and `"water in 1.2 km"` for a metric walker; `"water in 492 ft"` (150 m) and `"water in 0.2 mi"` (280 m) for a miles walker (metres below 1,000 m, rounded; feet below 0.1 mi, about 161 m; else one decimal). Negative or non-finite metres read 0. Android's `WayRelation.distance(meters, units)` is this formatter already (`WayPlaceCardState.kt:278-292`). The off-way caption beside it stays metres-only (pilgrim-ios #109 item 1), so the two captions in one slot follow different unit rules, as on iOS.
- **The life:** 20 s (`softTapCaptionSeconds`, `ActiveWalkViewModel+Honor.swift:458`), generation-guarded so teardown ends it. Overlap can't arise on a stage: the soft tap never fires there, and two water captions are at least an hour of engine time apart (the quiet hour). The first caption's timer would otherwise clear a second one early, since the generation only moves at Start and teardown.
- **TalkBack/VoiceOver:** the minimized bar is one element, "Walk stats", whose value reads `"<duration>, <distance>, water in 280 m"` while the caption shows (with the intention first when set: `"<intention>. <stats>"`). No announcement fires when the caption appears.

**Android today.** The slot is typed for the soft tap only, as a metre count:

```kotlin
data class HonorSheetStats(
    /** The Way's distance left; null before Begin, which reads "--". */
    val remainingMeters: Double?,
    /** The soft tap's caption, borrowing the stat's slot for 20 s; dark while nothing sets the preference (pilgrim-ios #109). */
    val softTapMeters: Long?,
    val listening: HonorListening?,
)
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/HonorWalkViewModel.kt:193-199@0defff85

```kotlin
    val caption = honor.softTapMeters?.let { stringResource(R.string.honor_soft_tap_caption, it.toString()) }
    val third = caption ?: stringResource(R.string.honor_stats_a11y_remaining, remaining)
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheet.kt:442-443@0defff85

The view and TalkBack value already follow iOS (`WalkStatsSheet.kt:486-498`). **U39 changes** the field to a sealed caption, for example `sealed interface HonorCaption { data class OffWay(val meters: Long); data class WaterAhead(val meters: Double) }`, rendered as `honor_soft_tap_caption` or a new `honor_water_caption` = `"water in %1$s"` with `WayRelation.distance(meters, units)`. The water branch reads P3's persisted caption columns (mark id, metres, firing time) and shows for `firedAt + 20_000 - now` ms, nothing once that is past, and nothing for a caption fired before a UI process restart that has already expired. It never replays after a `:tracker` revival (plan, Key Technical Decisions). If iOS PR #91 merges, its stamp notice joins the same sealed type (§14).

### 7. The card body on a stage

#### 7.1 The waypoint body

```swift
        case .waypoint:
            VStack(alignment: .leading, spacing: Constants.UI.Padding.small) {
                // The dataset may send 600 characters; the card does not
                // scroll and sits over the map, so it shows what fits.
                Text(WayMomentHeader.placeCopy(for: moment, isStage: isStage))
                    .font(Constants.Typography.caption).foregroundColor(.fog)
                    .lineLimit(4)
                if let minutes = moment.sitMinutes, minutes > 0 {
                    sitRow(minutes: minutes)
                }
            }
```
> Pilgrim/Scenes/ActiveWalk/WayPlaceCard.swift:116-126@7c200bf

```swift
    static func placeCopy(for moment: WayMoment, isStage: Bool) -> String {
        if let text = moment.text, !text.isEmpty { return text }
        return isStage ? "A place on the way." : "A place they marked."
    }
```
> Pilgrim/Scenes/Honor/WayMomentHeader.swift:86-89@7c200bf

```swift
                WayPlaceCard(
                    moment: moment,
                    isStage: viewModel.way?.isPilgrimageStage == true,
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkView+Honor.swift:78-80@7c200bf

- The dataset's `text` (trimmed and capped at 600 by the importer, `PilgrimageWayImporter.swift:217`) when non-empty, else `"A place on the way."` on a stage, `"A place they marked."` elsewhere. Caption font, fog, at most 4 lines, truncated with the system's tail ellipsis. Stage moments are always waypoints (the importer skips every other kind), so on a stage this is the only body a card has. `isStage` changes nothing else on the card.
- The sit row follows when `sitMinutes` is set and above 0 (§7.3).

#### 7.2 Local names, under the kicker

```swift
    static let localNameOrder = ["eu", "gl", "es", "fr", "ja", "pt", "it", "de"]

    static func localName(for moment: WayMoment) -> String? {
        guard let names = moment.names else { return nil }
        let label = kicker(for: moment)
        for code in localNameOrder {
            guard let name = names[code], !name.isEmpty, name != label else { continue }
            return name
        }
        return nil
    }
```
> Pilgrim/Scenes/Honor/WayMomentHeader.swift:72-82@7c200bf

```swift
                if let localName = Self.localName(for: moment) {
                    Text(localName)
                        .font(Constants.Typography.caption)
                        .foregroundColor(.fog)
                        .lineLimit(1)
                }
```
> Pilgrim/Scenes/Honor/WayMomentHeader.swift:25-30@7c200bf

The first name, in the order Basque, Galician, Spanish, French, Japanese, Portuguese, Italian, German, that is non-empty and not equal (exact, case-sensitive string equality) to the kicker, which for a waypoint is its label. One line, caption, fog, directly under the kicker and above the distance subline. English never shows (`en` isn't in the order), nor do the 40-odd other languages the dataset carries. The header is shared, so the overview's preview shows the same line. Android has `localName` ported with the same order and rule (`WayMomentHeader.kt:106-112`), but no test yet.

#### 7.3 "Sit?"

```swift
    private func sitRow(minutes: Int) -> some View {
        HStack(spacing: Constants.UI.Padding.normal) {
            Button { onTouch(); onSit(minutes) } label: {
                Text("Sit?").font(Constants.Typography.button).foregroundColor(.parchment)
                    .padding(.horizontal, Constants.UI.Padding.big).padding(.vertical, Constants.UI.Padding.small)
                    .background(Color.stone).cornerRadius(Constants.UI.CornerRadius.normal)
            }
            .accessibilityLabel("Sit here for \(minutes) minutes")
            Text("your soundscape holds while you sit")
                .font(Constants.Typography.caption).foregroundColor(.fog)
        }
    }
```
> Pilgrim/Scenes/ActiveWalk/WayPlaceCard.swift:131-142@7c200bf

The same row as a sitting moment's card: a stone pill "Sit?" (button font, parchment), then "your soundscape holds while you sit" (caption, fog). The tap touches the card and calls `onSit(minutes)`, which starts the meditation and its "they sat here N minutes" caption (E-15, matched as shipped; §19). The accessibility label is `"Sit here for 5 minutes"`, always "minutes". Android has this row already (`WayPlaceCard.kt:525-555`), wired to the waypoint body with the `> 0` guard (`:185`).

#### 7.4 E-16: distance, tick and fly-to aim at `at`

iOS stands a stage pin at `pin ?? at` (`PilgrimMapView+HonorWay.swift:126`) but measures the card's distance, turns its tick and flies its header to `at` (`ActiveWalkViewModel+Honor.swift:300-303`). In the live data `pin` is up to 1,247 m from `at`, so "Show this place on the map" can centre on trail up to 1.2 km from the pin, and "N m away" counts to the trail, not the place. Android matches (`HonorWalkViewModel.kt:592-596`, `:754`); keep it, and file the question upstream (§19, defect P5-D4).

**Android today and U39's change.**

```kotlin
    /** iOS `placeCopy(for:isStage:)` for an own walk, which is never a stage. */
    fun placeCopy(resources: Resources, moment: WayMoment): String =
        moment.text?.takeIf { it.isNotEmpty() } ?: resources.getString(R.string.honor_moment_place_marked)
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/honor/WayMomentHeader.kt:97-99@0defff85

```kotlin
            is WayMomentKind.Waypoint -> Column(verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small)) {
                Text(
                    text = WayMomentCopy.placeCopy(LocalResources.current, card.moment),
                    style = pilgrimType.caption,
                    color = pilgrimColors.fog,
                    maxLines = PLACE_COPY_LINES,
                )
                card.moment.sitMinutes?.takeIf { it > 0 }?.let { SitRow(it, actions) }
            }
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/honor/WayPlaceCard.kt:178-186@0defff85

`HonorPlaceCard.isStage` is already carried (`HonorWalkViewModel.kt:153`, set at `:760`) but unused. U39 adds the `isStage` parameter to `placeCopy`, a string `honor_moment_place_on_the_way` = `"A place on the way."`, and passes `card.isStage`. `PLACE_COPY_LINES` is 4 (`WayPlaceCard.kt:587`); add `overflow = TextOverflow.Ellipsis` to match iOS's tail truncation (Compose's default `Clip` cuts the fourth line mid-glyph). The preview (P4) calls the same helper.

### 8. The arrival card on a stage

#### 8.1 What it says

```swift
            Text(Self.title(for: card)).font(Constants.Typography.heading).foregroundColor(.ink)
            Text(card.stageName ?? card.wayTitle).font(Constants.Typography.body).foregroundColor(.fog)
            Text(Self.line(for: card)).font(Constants.Typography.caption).foregroundColor(.fog)
            if let closing = card.closing {
                Text(closing)
                    .font(Constants.Typography.displayMedium)
                    .foregroundColor(.ink)
                replyRow
            }
            Button("continue", action: onDismiss).font(Constants.Typography.button).foregroundColor(.stone)
```
> Pilgrim/Scenes/ActiveWalk/WayPlaceCard.swift:362-371@7c200bf

```swift
    static func title(for card: HonorArrivalCard) -> String {
        card.isStage ? "you walked the stage" : "you walked their way"
    }

    /// A stage counts places and kilometres; a shared walk counts voices and
    /// places, because a voice is what the other walker left.
    static func line(for card: HonorArrivalCard) -> String {
        var parts: [String] = []
        if !card.isStage, card.voicesHeard > 0 {
            parts.append(card.voicesHeard == 1 ? "one voice heard" : "\(card.voicesHeard) voices heard")
        }
        if card.placesPassed > 0 {
            parts.append(card.placesPassed == 1 ? "one place passed" : "\(card.placesPassed) places passed")
        }
        if card.isStage {
            parts.append(StatsHelper.string(for: card.distanceWalkedMeters, unit: UnitLength.meters, type: .distance))
        }
        if parts.isEmpty { return card.isStage ? "the whole stage" : "the whole way, in their steps" }
        return parts.joined(separator: " · ")
    }
```
> Pilgrim/Scenes/ActiveWalk/WayPlaceCard.swift:414-433@7c200bf

Top to bottom, on the place card's parchment-secondary shell (normal padding, small spacing, no ×, no swipe):
1. `"you walked the stage"`, heading, ink.
2. The stage's `name` (not the Way's `title`), body, fog.
3. The counting line, caption, fog: `"one place passed · 24.2 km"`, `"3 places passed · 24.2 km"`, or with no place reached just `"24.2 km"`. Voices never count on a stage. The kilometres are always appended for a stage, so **`"the whole stage"` can never show**: `parts` is never empty when `isStage` (dead branch; §19, P5-D5). The distance is `StatsHelper` (on Android, `WalkFormat.distance`, the house convention, §11.4).
4. The stage's `closing`, display-medium, ink, then the reply row (§8.3). Both only when `card.closing` is non-nil, which for a stage it always is (`closing` is a required field; it can be an empty string, which iOS renders as an empty `Text` plus the reply row; no live stage has one).
5. `"continue"`, button font, stone: dismisses the card for good.

#### 8.2 Where the numbers come from

```swift
        honorArrival = HonorArrivalCard(
            wayTitle: way.title, voicesHeard: heardVoiceIDs.count,
            placesPassed: reachedMomentIDs.count,
            theirSeconds: theirSeconds, yourSeconds: yourSeconds,
            stageName: way.stage?.name,
            distanceWalkedMeters: honorEngine?.distanceWalkedMeters ?? 0,
            closing: way.stage?.closing)
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:253-259@7c200bf

```swift
    var distanceWalkedMeters: Double {
        walkedFrac * geometry.totalMeters
    }
```
> Pilgrim/Models/Honor/HonorEngine.swift:39-41@7c200bf

- `placesPassed` is every moment reached (`.momentReached`) up to the arrival instant. Android's `HonorArrival.summary` already counts the same from the rows (`WayPlaceCardState.kt:255-265`).
- The kilometres are the engine's along-Way credit **at the instant arrival fired**: `walkedFrac` (credited high-water progress plus capped re-acquire jumps) times the route geometry's length. It is a snapshot. The engine keeps tracking after arrival (both platforms: `HonorEngine.kt:258-275`), so `walkedFrac` can still grow over the last ~30 m (arrival fires within 30 m of the end) or on a re-acquire. It is geometry metres, not the dataset's `distanceKm`, so the card's figure and the summary's (§11) differ by design (survey candidate 5, confirmed; §19 P5-D6).
- `stageName` and `closing` come from the stage block; `wayTitle` (the file's `title`) is used only for the waypoint label (§9) and the non-stage title. In the live data `title == stage.name` on every stage.

**Android today.** The arrival summary has none of the stage fields:

```kotlin
@Immutable
data class HonorArrivalSummary(
    val wayTitle: String,
    val voicesHeard: Int,
    val placesPassed: Int,
)
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/honor/WayPlaceCardState.kt:238-243@0defff85

```kotlin
    fun line(resources: Resources, summary: HonorArrivalSummary): String {
        val parts = buildList {
            when {
                summary.voicesHeard == 1 -> add(resources.getString(R.string.honor_arrival_one_voice))
                summary.voicesHeard > 1 -> add(resources.getString(R.string.honor_arrival_voices, count(summary.voicesHeard)))
            }
            when {
                summary.placesPassed == 1 -> add(resources.getString(R.string.honor_arrival_one_place))
                summary.placesPassed > 1 -> add(resources.getString(R.string.honor_arrival_places, count(summary.placesPassed)))
            }
        }
        if (parts.isEmpty()) return resources.getString(R.string.honor_arrival_whole_way)
        return parts.joinToString(" · ")
    }
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/honor/HonorArrivalCard.kt:33-46@0defff85

**U39 changes:**
- `HonorArrivalSummary` gains `stageName: String?`, `distanceWalkedMeters: Double`, `closing: String?` (and `isStage = stageName != null`), built in `HonorArrival.summary` from `way.stage` and the walked distance.
- **The walked distance needs a snapshot in Room.** The UI rebuilds the card from rows, and the session row's `walkedFrac` (`HonorSessionEntity.kt:72`) keeps moving after arrival. Add an `arrival_walked_meters` column to schema 12 (P3/U35), written in arrival's own transaction beside `arrival_their_seconds` and `arrival_your_seconds` (`HonorSessionEntity.kt:109-111`), and read it here. Reading the live `walkedFrac` instead would drift by up to the last 30 m and would not survive a later re-acquire. This is a correction to the plan's schema 12 list (C4).
- `HonorArrivalCopy.line` takes the stage branch: no voices, places as today, then `WalkFormat.distance(distanceWalkedMeters, units)`; keep the unreachable `"the whole stage"` fallback for parity (a test can reach it only through a stage with no `isStage`, so it stays untested, as on iOS).
- The title and the second line switch on `isStage`: `honor_arrival_stage_title` = `"you walked the stage"`, and the stage name in place of the Way's title.

#### 8.3 The reply row: four states

```swift
    @ViewBuilder
    private var replyRow: some View {
        HStack(spacing: Constants.UI.Padding.small) {
            if isRecordingReply {
                Text("recording your reply here").font(Constants.Typography.caption).foregroundColor(.ink)
                Spacer()
                if let onStopReply {
                    Button { onStopReply() } label: {
                        Image(systemName: "stop.circle.fill")
                            .font(Constants.Typography.displayMedium)
                            .foregroundColor(.rust)
                    }
                    .accessibilityLabel("Stop recording your reply")
                }
            } else if let onReply {
                Button { onReply() } label: {
                    Label(existingReply == nil ? "reply here" : "record again", systemImage: "mic")
                        .font(Constants.Typography.caption).foregroundColor(.stone)
                        .frame(minHeight: 44).contentShape(Rectangle())
                }
                .accessibilityLabel("Record a reply to this stage")
            }
            if let existingReply, let onPlayReply {
                Spacer()
                Button { onPlayReply(existingReply) } label: {
                    Label("your reply", systemImage: "play.circle")
                        .font(Constants.Typography.caption).foregroundColor(.stone)
                        .frame(minHeight: 44).contentShape(Rectangle())
                }
                .accessibilityLabel("Play your reply")
            }
        }
    }
```
> Pilgrim/Scenes/ActiveWalk/WayPlaceCard.swift:380-412@7c200bf

```swift
            if let card = viewModel.honorArrival, !viewModel.honorArrivalCardDismissed {
                HonorArrivalCardView(
                    card: card,
                    existingReply: stageReply,
                    isRecordingReply: viewModel.isRecordingVoice
                        && viewModel.pendingReplyOrigin?.id == HonorPersistence.stageReflectionMomentID,
                    onReply: { viewModel.replyToStageReflection() },
                    // The same toggle `WayPlaceCard`'s voice body stops with.
                    onStopReply: { viewModel.toggleVoiceRecording() },
                    onPlayReply: { url in viewModel.playReply(url: url) },
                    onDismiss: { viewModel.honorArrivalCardDismissed = true })
                    .task(id: viewModel.completedRecordingCount) {
                        stageReply = viewModel.stageReflectionReplyURL()
                    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkView+Honor.swift:62-75@7c200bf

```swift
    func replyToStageReflection() {
        guard let stage = way?.stage else { return }
        replyHere(to: HonorPersistence.stageReflectionMoment(for: stage))
    }

    /// The walker's reply to this stage's reflection, from this walk or an
    /// earlier one. Nil when the recording is gone.
    func stageReflectionReplyURL() -> URL? {
        guard let stage = way?.stage else { return nil }
        return existingReplyURL(for: HonorPersistence.stageReflectionMoment(for: stage))
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Replies.swift:53-63@7c200bf

| State | Trigger | Shows | Tap |
|---|---|---|---|
| No reply yet | not recording this reply, `stageReply == nil` | `mic` + `"reply here"` (caption, stone, 44 pt min height); label `"Record a reply to this stage"` | `replyToStageReflection()` |
| A reply exists | not recording this reply, `stageReply != nil` | `mic` + `"record again"`, then a spacer and `play.circle` + `"your reply"` (label `"Play your reply"`) | "record again" starts a new take **at once, with no confirmation** (the place card asks "Replace your earlier reply?"; the arrival card doesn't); "your reply" calls `playReply(url:)` |
| Recording this reply | `isRecordingVoice && pendingReplyOrigin.id == "stage-reflection"` | `"recording your reply here"` (caption, ink; no pulsing dot, unlike the place card), spacer, rust `stop.circle.fill` (display-medium), label `"Stop recording your reply"`; plus `"your reply"` at the end if an earlier one exists | stop calls `toggleVoiceRecording()`, the same toggle as the talk button |
| After the take | the recording completes and is filed | back to "A reply exists", once `completedRecordingCount` moves and `.task` re-reads the file | |

- **The lookup** is the Way's `replies.json` entry `-1`, resolved under Documents, and only if the file exists (`existingReplyURL`, `ActiveWalkViewModel+Replies.swift:46-50`). It is re-read when the arrival card appears and each time a recording completes (`.task(id: completedRecordingCount)`). The replies file is per Way, so on a re-walk of the same stage the card opens with "record again" and the earlier walk's "your reply".
- **A reply while a plain recording already runs** adopts that recording as the reply (`replyHere` only starts recording if none is running; pilgrim-ios #99 item 3, matched). The row then reads "recording your reply here" at once.
- **Mic permission denied or the recorder failing to open** leaves the origin cleared and the row on "reply here" (`replyHere`, `ActiveWalkViewModel+Replies.swift:29-31`).
- **"continue" while recording** hides the card; the take keeps recording and is still filed under −1 (stop on the talk button, or at walk end, P3's lifecycle).
- **Playing the reply** goes through the Way voice player, which drops the current Way voice and plays with no on-screen control (pilgrim-ios #99 item 5, matched). On a stage there are no voices, so nothing is interrupted.

P3 owns the origin (−1, `"stage-reflection"`, at `stage.end.at`), the filing, the late completion after `stop()`, and `cancel()` discarding the reply.

**Android today.** The card has neither the closing nor the row:

```kotlin
@Composable
fun HonorArrivalCard(
    summary: HonorArrivalSummary,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/honor/HonorArrivalCard.kt:59-64@0defff85

and the card layer passes it only the summary and "continue" (`WayPlaceCard.kt:198-221`; `ActiveWalkScreen.kt:1288-1311`).

**U39 builds:**
- `HonorArrivalCard(summary, units, stageReply: StageReplyUi?, onContinue)`, with `StageReplyUi(hasReply: Boolean, isRecording: Boolean, onReply, onStopReply, onPlayReply)` passed only for a stage. The closing line (`pilgrimType.displayMedium`, ink) then the row, in the table's four states, and "continue" last.
- `isRecording` = the recorder is `Recording` and `replyingToMomentId == "stage-reflection"` (`HonorWalkViewModel.replyingToMomentId`, `:377-382`, already compares moment ids).
- `onReply` reuses the screen's `replyHere(ReplyRequest(walkId, wayId, "stage-reflection"))` (`ActiveWalkScreen.kt:391-398`), so the mic permission flow is shared. P3's `voiceOriginIndex` must map `"stage-reflection"` to −1 (it returns null today, `HonorSessionState.kt:117-118`), or `HonorReplies.arm` files nothing.
- `hasReply` = `wayStore.replies(wayId)[-1]` resolves to an existing recording file, re-read on the same triggers `mediaFlow()` uses (each saved recording, each filed reply; `HonorWalkViewModel.kt:702-712`), and when the arrival card appears.
- `onPlayReply` needs a `:tracker` play-reply command for origin −1. The existing `playReply(moment)` takes a Way moment; the reflection is a synthesized moment, so either the command carries the origin index or `:tracker` builds the reflection moment from the stage (P3).
- No confirmation dialog on "record again" here. Do not reuse the place card's `ReplyRow`, which asks first; build the arrival row as its own composable.
- The card's "continue" keeps writing `HONOR_ARRIVAL_CARD_ID` (`"arrival"`, `WayPlaceCardState.kt:31`) as today; `"stage-reflection"` is not a card id and never needs a card row.

### 9. The arrival waypoint label: "Walked their way: <stage title>"

```swift
        addWaypoint(label: HonorPersistence.arrivalWaypointLabel(wayTitle: way.title),
                    icon: HonorPersistence.arrivalWaypointIcon)
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:251-252@7c200bf

```swift
    private static let arrivalLabelFormat = NSLocalizedString(
        "honor.arrival.label", value: "Walked their way: %@",
        comment: "Waypoint label at the end of an honored Way; %@ is the Way's title.")
```
> Pilgrim/Models/Honor/HonorPersistence.swift:42-44@7c200bf

No stage branch: a stage's arrival waypoint is labelled `"Walked their way: Saint-Jean-Pied-de-Port to Roncesvalles"`. The label is never drawn on a map, but it travels: it is a walk waypoint, so it lands in the AI prompt context (`PromptAssembler.swift:82`, `"[<time>, GPS: …] Walked their way: …"`), the `.pilgrim` export and the share payload (`WalkShareViewModel.swift:417`). Survey candidate 1 is **confirmed**. Plan R14 allows a "their" only where iOS ships it, and iOS ships this one, so Android writes the same label (`honor_arrival_label`, `strings.xml:1201`, already identical) from `:tracker`'s arrival transaction (P3). File it upstream with #109 item 3 (§19, P5-D1).

```xml
    <string name="honor_arrival_label">Walked their way: %1$s</string>
```
> app/src/main/res/values/strings.xml:1201@0defff85

### 10. "the day": the options row and the morning card mid-walk

```swift
                    if let stageDay, let onOpenStageDay {
                        optionRow(icon: "sun.horizon", title: "the day", subtitle: stageDay.theme) {
                            onOpenStageDay()
                        }
                    }
```
> Pilgrim/Scenes/ActiveWalk/WalkOptionsSheet.swift:67-71@7c200bf

```swift
                stageDay: viewModel.way?.stage,
                onOpenStageDay: {
                    showOptions = false
                    stageMapsLine = viewModel.way.map { StageMorningCardModel.mapsLine(saved: PilgrimageTilesManager.shared.isStageSaved($0)) }
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                        showStageDay = true
                    }
                }
            )
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
            .presentationBackground(Color.parchment.opacity(0.95))
        }
        .sheet(isPresented: $showStageDay) {
            if let stage = viewModel.way?.stage {
                StageMorningCard(stage: stage, weather: viewModel.weatherSnapshot,
                                 mapsLine: stageMapsLine,
                                 buttonTitle: "close") {
                    showStageDay = false
                }
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
            }
        }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkView.swift:297-320@7c200bf

```swift
            .accessibilityLabel(buttonTitle == "walk" ? "Begin walking this stage" : "Close the day's words")
```
> Pilgrim/Scenes/Honor/StageMorningCard.swift:79@7c200bf

- **When the row shows:** whenever the walk's Way has a stage block. Nothing gates it on the walk's status, so it shows on the pre-walk screen (after "Set Intention") and mid-walk (before "Drop Waypoint"). Its place in the list is fixed: after "Set Intention", before "Drop Waypoint", the seek section, "Traces" and "Audio".
- **The row:** `sun.horizon` in moss, title `"the day"` (body, ink at 0.9, one line, scales to 0.8), subtitle the stage's `theme` (caption, fog at 0.5, one line, hidden if empty), the trailing chevron. The same `optionRow` as every other.
- **The tap:** closes the options sheet, then 0.3 s later opens the morning card as a sheet: full height, drag indicator. (In slice two the maps line wasn't there; slice three computes `stageMapsLine` at the tap. Android leaves that line as the 21-3 seam, nil until then.)
- **The card:** the same `StageMorningCard` as Begin's (P4 owns its layout and copy): theme, narrative, facts, warnings, then the weather line from the **walk's** weather snapshot (`viewModel.weatherSnapshot`, nil before Start and until the walk's fetch lands, so the line can be missing pre-Start and early in the walk), then the maps line. One button, `"close"`, which only dismisses (`docs/honor-slice-two-device-pass.md:69-70`). Its accessibility label is `"Close the day's words"`. Swiping the sheet down closes it too.
- **What it doesn't do:** it never re-reads the stage from disk (it shows the walk's captured Way), never starts or pauses anything, and there's no "the day" anywhere else (not in the stats sheet, not in the cards).

**Android today.** `WalkOptionsSheet` has no stage parameter (`WalkOptionsSheet.kt:78-120`). Its `OptionRow` hides a blank subtitle (`!subtitle.isNullOrBlank()`, `:495`), where iOS hides only an empty one; a whitespace-only theme would show a blank line on iOS and nothing on Android. No live theme is blank, so leave the existing helper alone.

**U39 adds** (or U38, which owns the morning card composable; the plan lists "the day" under U38):
- `stageDay: WayStage? = null` and `onOpenStageDay: () -> Unit = {}` on `WalkOptionsSheet`, the row between the intention and waypoint rows, icon `Icons.Outlined.WbTwilight` (a sun on the horizon; it's also the seek arrival's stand-in for `sun.haze`, which is fine in a different place), title `walk_options_the_day` = `"the day"`, subtitle the theme.
- `ActiveWalkScreen` passes `honor?.way?.stage`, and on tap closes the sheet and, after `SHEET_HANDOFF_DELAY_MS` (the existing 300 ms handoff), shows P4's morning card with `buttonTitle = "close"`, the walk's `activeWeather` (`WalkViewModel.kt:170-171`), and a null maps line. The card's state is a `rememberSaveable` flag, like the other walk sheets.

### 11. The summary's stage block

#### 11.1 The model

```swift
    static func summaryData(for walk: WalkInterface, way: Way?, link: WayLink?,
                            replies: [Int: String], ledger: PilgrimageLedger?) -> HonorSummaryData? {
        let types = walk.workoutEvents.map(\.eventType)
        guard types.contains(.honorMode) else { return nil }
        let stage = way?.stage
        // The dot walked the companion's timeline; the summary reads the
        // numbers the engine recorded at arrival, never a recomputation.
        var delta: Double?
        if stage == nil, let theirs = link?.theirSeconds, let yours = link?.yourSeconds { delta = theirs - yours }
        let arrived = types.contains(.honorArrival)
        return HonorSummaryData(
            wayTitle: way?.title ?? "a way that has been removed",
            arrivedBeforeTheirsSeconds: delta,
            voicesAlongTheWay: way?.voiceCount ?? 0,
            repliesMade: replies.count,
            isPilgrimageStage: stage != nil,
            stageProgressLine: stage.flatMap { stageProgressLine(stage: $0, ledger: ledger) },
            closing: arrived ? stage?.closing : nil,
            replyRelativePath: replies[HonorPersistence.stageReflectionOrigin])
    }

    /// The kilometres the ledger recorded for this stage, against the stage's
    /// own length. Silent when the walk earned no entry.
    static func stageProgressLine(stage: WayStage, ledger: PilgrimageLedger?) -> String? {
        guard let entry = ledger?.stages[String(stage.index)] else { return nil }
        let walked = StatsHelper.string(for: entry.kmWalked * 1000, unit: UnitLength.meters, type: .distance)
        let whole = StatsHelper.string(for: stage.distanceKm * 1000, unit: UnitLength.meters, type: .distance)
        return "\(walked) of \(whole) of the stage"
    }
```
> Pilgrim/Scenes/WalkSummary/HonorSummarySection.swift:25-53@7c200bf

```swift
        let link = WayStore.shared.wayLink(forWalk: uuid)
        let way = link.flatMap { WayStore.shared.load(id: $0.wayId) }
        let replies = link.map { WayStore.shared.replies(for: $0.wayId) } ?? [:]
        let ledger = way?.stage.flatMap { PilgrimageLedgerStore().load(routeId: $0.routeId) }
```
> Pilgrim/Scenes/WalkSummary/WalkSummaryView.swift:746-749@7c200bf

```swift
        let completed = outcome.arrived || (existing?.completed ?? false)
        let walkedKm = max(completed ? km : km * frac, existing?.kmWalked ?? 0)
        stages[key] = Entry(
            name: name, distanceKm: km, walkedAt: date, kmWalked: walkedKm,
            completed: completed, stoppedAtFrac: completed ? nil : frac)
```
> Pilgrim/Models/Honor/PilgrimageLedger.swift:66-70@7c200bf

The block's data, field by field, for a stage (the Way still on disk, with `stage` set):
- **isPilgrimageStage:** true. Carried, never inferred from the progress line.
- **Kicker:** `"the stage you walked"`.
- **Title:** the Way's `title` (the stage file's `title`), not `stage.name`.
- **Progress line:** `"<kmWalked> of <stage.distanceKm> of the stage"`, from the route's ledger entry at key `String(stage.index)`. Null when there's no entry.
- **Delta:** always nil for a stage. The link still stores the arrival numbers (`theirSeconds` is the stage file's synthesized `theirActiveSeconds` timeline), and the summary ignores them. This is the dropped companion delta.
- **Counts line:** `voicesAlongTheWay` is 0 on a stage, which has no voices. `repliesMade` is the size of the Way's whole `replies.json`, so `"1 reply"` after a reflection reply, and every earlier walk's too (pilgrim-ios #99 item 4).
- **Closing:** the stage's `closing`, only if this walk's events hold `.honorArrival`.
- **Reply:** `replies[-1]`, the reflection reply of **any** walk of this stage, **not** gated on arrival (§11.3).

**The ledger's best, confirmed.** The line reads the ledger, and the ledger keeps the best: `completed` is sticky, and `kmWalked` is the maximum of every walk's `km × frac` (or the full `distanceKm` once completed). So the summary doesn't show this walk's distance. It shows the best the walker has ever done on this stage, as of when the summary is opened. Survey candidate 3, **confirmed**. iOS writes the link and the ledger before the summary first opens (`MainCoordinatorView.swift:116-121`, then the seal reveal, then the summary), so a first summary already counts this walk.

#### 11.2 What a summary shows, case by case

| Walk | Ledger entry for this stage | Progress line | Closing | Reply |
|---|---|---|---|---|
| Arrived, first walk | written by this walk, `completed`, `kmWalked = distanceKm` | `"24.2 km of 24.2 km of the stage"` | yes | this walk's reflection reply, if any |
| Left at 58 %, first walk | `24.2 × 0.58` | `"14.04 km of 24.2 km of the stage"` (iOS formatting, §11.4) | no | `replies[-1]` if any walk ever replied |
| Never anchored (started far off the line), no earlier walk | none (AE8) | **none**: the block is kicker, title, maybe a counts line | no | `replies[-1]` from an earlier walk, if any |
| Never anchored, after an earlier walk | the earlier walk's | the **earlier** walk's figure | no | the earlier walk's reply |
| A shorter re-walk after a longer one | the longer walk's `kmWalked` (max) | the **longer** figure, on the shorter walk's summary | only if this walk arrived | the latest reply filed under −1 |
| Any past summary, after a later, longer walk | updated | **changes**: the past summary now shows the later best | as that walk's events say | the latest reply |
| After an Update that **kept** the entry (same name, within 5 %) | unchanged, `distanceKm` the old one | `"<old kmWalked> of <new distanceKm> of the stage"`: the stage's length comes from the rewritten `way.json`, so a completed stage can read `"24.2 km of 25 km of the stage"` | unchanged rule | unchanged |
| After an Update that **dropped** the entry (renamed, or more than 5 % off) | gone (km carried to `carriedKm`) | **none** | unchanged rule | unchanged |
| After Remove (or Replace) of the route | `ledger.json` stays; a walked stage's `way.json` stays (`retireMany`) | unchanged | unchanged | unchanged |
| The Way is gone (`load` returns nil) | not read (`way?.stage` is nil) | none, and the block becomes a **non-stage** block: `"in their steps"`, `"a way that has been removed"`, and a **delta line from the link's stage numbers** (`"they arrived N minutes after you"`) if arrival fired | no | no (replies keyed by the link's Way id still read, but no reply button outside a stage) |

The last row is unreachable on iOS through the app (a linked stage keeps its `way.json`), but reachable on Android through flow-analysis gap 2 (§13). The empty-ledger rows are what U40's "unanchored stage walk" test pins.

#### 11.3 The view

```swift
            Text(Self.kicker(for: data)).font(Constants.Typography.caption).foregroundColor(.fog)
            Text(data.wayTitle).font(Constants.Typography.heading).foregroundColor(.ink)
            if let stageProgressLine = data.stageProgressLine {
                Text(stageProgressLine).font(Constants.Typography.caption).foregroundColor(.fog)
            }
            if let delta = data.arrivedBeforeTheirsSeconds {
                Text(deltaLine(delta)).font(Constants.Typography.caption).foregroundColor(.fog)
            }
            if let countsLine {
                Text(countsLine).font(Constants.Typography.caption).foregroundColor(.fog)
            }
            if let closing = data.closing {
                Text(closing)
                    .font(Constants.Typography.displayMedium)
                    .foregroundColor(.ink)
                    .padding(.top, Constants.UI.Padding.xs)
            }
            if let replyRelativePath = data.replyRelativePath, let url = replyURL(replyRelativePath) {
                Button { player.toggle(url: url) } label: {
                    Label(player.isPlaying ? "pause" : "your reply",
                          systemImage: player.isPlaying ? "pause.circle" : "play.circle")
                        .font(Constants.Typography.caption).foregroundColor(.stone)
                        .frame(minHeight: 44).contentShape(Rectangle())
                }
                .accessibilityLabel("Play your reply to this stage")
            }
```
> Pilgrim/Scenes/WalkSummary/HonorSummarySection.swift:62-87@7c200bf

```swift
    static func kicker(for data: HonorSummaryData) -> String {
        data.isPilgrimageStage ? "the stage you walked" : "in their steps"
    }
```
> Pilgrim/Scenes/WalkSummary/HonorSummarySection.swift:98-100@7c200bf

Order: kicker (caption, fog), title (heading, ink), progress line (caption, fog), delta (never on a stage), counts (caption, fog), closing (display-medium, ink, `xs` extra top padding), reply button. The button shows only if the relative path resolves to an existing file under Documents (`localMediaURL(.recording)`, `HonorSummarySection.swift:122-125`). It toggles its own `AudioPlayerModel`: `play.circle` + `"your reply"`, and while playing `pause.circle` + `"pause"`. Its accessibility label stays `"Play your reply to this stage"` in both states. The player stops when the section disappears (`HonorSummarySection.swift:92`).

The reply is ungated: `replies[-1]` is the Way's, so a walk that didn't arrive (and shows no closing) still offers an earlier walk's reflection reply, under no closing line (survey candidate 6, **confirmed**; extends #99 item 4; §19 P5-D3).

#### 11.4 Formatting

`StatsHelper.string(for:unit:type: .distance)` is `MeasurementFormatter` with `.providedUnit`, a 0.01 rounding increment, and the walker's distance unit (`CustomMeasurementFormatting.swift:26-55`). Probed with `swift`: 24,200 m → `"24.2 km"`, 14,036 m → `"14.04 km"`, 1,000 m → `"1 km"`, 50 m → `"0.05 km"`, 0 → `"0 km"`. So the survey's `"14.1 km of 24.2 km of the stage"` isn't what iOS prints: it prints `"14.04 km of 24.2 km of the stage"`. Android's house formatter `WalkFormat.distance` prints `"14.04 km of 24.20 km of the stage"` (two fixed decimals, and metres under 100 m). The own-walk spec already took `WalkFormat.distance` as the app-wide stand-in for `StatsHelper` (`docs/parity/2026-09-29-honor-own-walk-port.md:11711`). Keep it, and note in the strings table that trailing zeros differ.

#### 11.5 Android today and U40's change

```kotlin
data class HonorWalkRecord(
    val way: Way?,
    val arrival: WayArrival?,
    val replies: Map<Int, String>,
) {
```
> app/src/main/java/org/walktalkmeditate/pilgrim/honor/HonorWalkRecords.kt:32-36@0defff85

```kotlin
    fun summaryState(record: HonorWalkRecord): HonorSummaryState {
        val way = record.way
        return HonorSummaryState(
            data = HonorSummaryData(
                wayTitle = way?.title,
                arrivedBeforeTheirsSeconds = record.arrival?.let { it.theirSeconds - it.yourSeconds },
                voicesAlongTheWay = way?.voiceCount ?: 0,
                repliesMade = record.replies.size,
            ),
            ghost = way?.let(HonorWayLine::of),
        )
    }

    /** "in their steps", even when the Way is the walker's own earlier walk (pilgrim-ios #109). */
    fun kicker(resources: Resources): String = resources.getString(R.string.honor_summary_kicker)
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/summary/HonorSummaryModel.kt:62-76@0defff85

**U40 changes:**
- `HonorWalkRecord` gains `ledger: PilgrimageLedger?`, read by `HonorWalkRecords.read` through P2's ledger store for `way?.stage?.routeId`, in both branches (the live session's and the link's). Files raise no invalidation; `observe` re-reads on the session and marker changes (`HonorWalkRecords.kt:75-82`). Since the ledger record lands before the marker (plan, Finalize ordering), the re-read after the marker picks it up. Before the marker, the line shows whatever the ledger holds (an earlier walk's entry, or none), then moves to this walk's when the marker lands. iOS never shows the "before" state; record it beside the existing pre-link arrival numbers (§17, A3).
- `HonorSummaryData` gains `isPilgrimageStage`, `stageProgressLine`, `closing`, `replyRelativePath`. The delta becomes `if (way?.stage == null) record.arrival?.let { … } else null`. `closing` needs the walk's events: `summaryState(events, honorEnabled, record)` already has them, so pass `HONOR_ARRIVAL in events` down (the record-only overload, used by the live `honorSummary` flow at `WalkSummaryViewModel.kt:461-468`, must gain the events too, or the live flow drops the closing).
- `kicker(resources, data)` branches: `honor_summary_stage_kicker` = `"the stage you walked"`.
- `stageProgressLine` uses `WalkFormat.distance(entry.kmWalked * 1000, units)` and `WalkFormat.distance(stage.distanceKm * 1000, units)` with `honor_summary_stage_progress` = `"%1$s of %2$s of the stage"`. The model then needs the walker's units; the summary screen already has them.
- `HonorSummarySection` adds the progress line after the title, the closing after the counts (`pilgrimType.displayMedium`, ink, `PilgrimSpacing.xs` top padding), and the reply button. The reply needs a player. The summary already plays recordings through the singleton `VoicePlaybackController` (`WalkSummaryViewModel.kt:288`, `:1028`, which takes a `VoiceRecording` row); the reply's file may belong to an earlier walk, so the controller needs a play-by-file entry, or the section its own player as iOS's `AudioPlayerModel` is. Stop it when the section leaves composition; label `"your reply"` / `"pause"`, icons `PlayCircle` / `PauseCircle` outlined, TalkBack `"Play your reply to this stage"` in both states. Resolve the path the way the walk's replies resolve (`mediaFiles.recordingFile`, contained under the recordings root).

### 12. Accessibility on these surfaces

Every label below is iOS's, verbatim; TalkBack takes the same as `contentDescription` or merged text.

| Surface | Element | iOS label / reading | Note |
|---|---|---|---|
| Map | a mark | none: map annotations have no accessibility element | Same for moment pins (#108 item 2, matched). Android's Mapbox annotations aren't in the semantics tree either |
| Stats bar | the minimized bar | label `"Walk stats"`, value `"<duration>, <distance>, water in 280 m"` while the caption shows, `"<intention>. "` prefixed when set | No announcement when the caption appears |
| Place card | header button | `"Show this place on the map"`, or `"Back to where you are"` while any focus is set; replaces the kicker, local name and distance (#108 item 1, matched) | Unchanged on a stage |
| Place card | waypoint body | its text (the dataset's words or `"A place on the way."`), read as plain text | |
| Place card | Sit? | `"Sit here for 5 minutes"` (always "minutes") | |
| Place card | × | `"Dismiss"` | |
| Place card | queue dots | `"N more waiting"` | |
| Arrival card | title, name, line, closing | plain text, in order | |
| Arrival card | reply here / record again | `"Record a reply to this stage"`, both states | The label doesn't say "record again" replaces the earlier reply (§19, P5-D2) |
| Arrival card | stop | `"Stop recording your reply"` | |
| Arrival card | your reply | `"Play your reply"` | The place card's is `"Play your earlier reply"`; keep them distinct |
| Arrival card | continue | `"continue"` (the button's text) | |
| Options | the day | the row's text, `"the day"` and the theme; no explicit label | Android's `OptionRow` merges its texts already |
| Morning card | close | `"Close the day's words"` | `"Begin walking this stage"` when the button reads "walk" (P4) |
| Summary | kicker, title, progress line, counts, closing | plain text, in order | No grouping or header trait, as for the own-walk block (G §11) |
| Summary | reply button | `"Play your reply to this stage"`, **also while it shows "pause"** | (§19, P5-D7) |

### 13. Flow-analysis gaps that touch this cluster

- **Gap 1 (the walk guard).** On the walk screen the Way is loaded once per `(wayId, sourceKind)` (`HonorWalkViewModel.kt:600-605`, `:619-624`), so a package commit under a live walk would leave the UI drawing the old pins, marks, ghost and arrival closing while `:tracker` re-reads the new file at its next revival. The guard the plan adds makes that unreachable; the UI needs nothing more. The pre-walk screen counts as "the walk screen is up" for the guard, and so must "the day" sheet and its morning card.
- **Gap 2 (Remove while a link is pending).** If it ever happens, the summary of that walk finds no Way: iOS's model then builds a **non-stage** block, `"in their steps"`, `"a way that has been removed"`, and a `"they arrived N minutes …"` delta from the stage's synthesized seconds in the link (§11.2, last row). iOS can't reach it; Android can only through this gap. Close the gap (the plan's guard), and pin the iOS rule in a test (`removed.isPilgrimageStage == false`, from `testTheSummaryKickerDropsTheirStepsForAStage`).
- **Gap 4 (finalize ordering).** The summary's progress line reads the ledger; with the record before the marker, the marker re-read (`HonorWalkRecords.observe`) shows this walk's entry (§11.5).
- **Gap 6 (water ahead needs more in Room).** The caption slot reads the persisted mark id, metres and firing time, shows what's left of 20 s, and never replays after a revival (§6). The marks on the map need nothing in Room.
- **Gap 7 (the unanchored stage summary).** Quoted and tabled in §11.2: no entry means no progress line, but the block still says `"the stage you walked"`; an earlier walk's entry means the earlier figure.
- **Gap 8 (re-walks and redraws change past summaries).** Confirmed for the progress line (§11.2) and for the summary map's ghost, which redraws from the current `way.json` (`WalkSummaryView.swift:753`). Both are parity, filed upstream (§19, P5-D8).
- **Gap 12 (task restore).** The walk screen restores from ids and reloads the Way from Room and the store. The stage-day sheet's `rememberSaveable` flag must check that the stage still loads, as iOS's `if let stage = viewModel.way?.stage` does; a sheet with no stage shows nothing and closes. The arrival card and its reply row restore from the session row, the card rows and `replies.json`, with the recording state from P3's reply lifecycle.

### 14. If iOS PR #91 merges (conditional seam)

PR #91 (open, `feat/stamp-closing`) touches three cluster files. For this cluster it only generalizes the slot: `showHonorCaption(_:)` becomes the one writer of `softTapCaption`, `showMarkCaption` calls it, and a third caption, `WayStampNotice.caption`, `"temple 10 stamps until 5 · 3.2 km"`, shares the slot and its 20 s. Android's sealed `HonorCaption` (§6) takes a third case with no change to the view. P3's annex owns the rest.

---

### 15. Corrections to the Android plan

- **C1. Re-selection on the walk is fix-driven plus zoom levels; the 200 m camera move is the overview's.** The plan (U39) says the marks are "re-selected every 200 m or on an integer zoom change, from a camera report throttled to 4 per second". On the walk screen, the 200 m is the **walker's** movement (from the fix stream, `>=`), and the camera report re-selects only on an integer zoom change; a camera center move never re-selects there. On the overview, every report re-selects around the camera center (a level change or a center more than 200 m from the last report). Fix: state both screens' rules as §2 and §3 give them, and add the test "a pan of 1 km at the same zoom doesn't re-select on the walk".
- **C2. Marks can draw before Start.** Not in the plan. On the pre-walk screen a report that crosses an integer level applies marks around the camera (§2). Fix: the walk's mark state runs whenever the Way on screen has marks, pre-Start included.
- **C3. Idle is not "always" reported.** The survey (§3, "always on idle") and the plan's "from a camera report throttled to 4 per second" omit that idle bypasses only the time throttle; the level-or-200 m filter still applies (§4). Fix: port the five rules in order; test "idle with no level change and less than 200 m sends nothing".
- **C4. Schema 12 needs the arrival's walked distance.** The plan's Room schema 12 bullet lists the fired marks, the quiet hour, the water caption and the stage identity. The stage arrival card shows the engine's walked kilometres **at the arrival instant** (§8.2), which the UI can't rebuild from the live `walkedFrac`. Fix: add `arrival_walked_meters` (Double?, written in arrival's transaction with the arrival seconds) to `MIGRATION_11_12`.
- **C5. Z-order: iOS draws marks and moment pins in one layer.** The plan's "Z-order: `PilgrimMap` creates the moment pins' manager lazily … So the marks manager is created in the same step, before it" and its test "the marks manager exists below the moment pins' manager even when the pins' manager is created first" assume iOS layers marks under the pins. iOS puts every point pin in one `PointAnnotationManager` with icon overlap on, so Mapbox sorts overlaps by screen y (§5.3). Fix: owner decision D1; with (a), drop the separate-manager test and assert that the Way pin list carries marks first; with (b), the plan's test is satisfiable only with `belowLayerId` and a named Way pin layer, not by creation order, and the difference is recorded at the gate.
- **C6. Four of the five stage moment icons draw as `mappin` on Android.** Not in the plan. `house.lodge`, `seal`, `building.columns` and `book.closed` (470 of 505 stage moments) aren't drawable keys (§5.6). Fix: add the four stand-ins to `iconKeyToVector`, the drawable set and `PIN_GLYPHS` in U38 (overview pins and preview) or U39 (walk pins and card header); one unit, both surfaces.
- **C7. "the whole stage" never shows.** U39 says "the places passed with the walked kilometres or 'the whole stage'". On a stage the kilometres always append, so the fallback is dead (§8.1). Fix: test the three reachable forms (one place, N places, no places: kilometres alone), and keep the dead branch for parity without a test.
- **C8. The arrival reply row differs from the place card's.** The plan's "the reply row in its four states" should add: "record again" starts at once with **no** "Replace your earlier reply?" dialog; the recording text is caption with no pulsing dot; one accessibility label, `"Record a reply to this stage"`, covers both reply states; "your reply" is `"Play your reply"`; and "your reply" stays visible while a new take records (§8.3).
- **C9. The summary's line prints two decimals, not one.** The plan's (and survey's) `"14.1 km of 24.2 km of the stage"`: iOS prints `"14.04 km of 24.2 km of the stage"`; Android's house formatter prints `"14.04 km of 24.20 km of the stage"` (§11.4). Fix: write tests against `WalkFormat.distance` output, not the plan's example.
- **C10. "your reply" on the summary isn't gated on arrival.** U40 says "the closing line only when arrived, and 'your reply'", which reads as both gated. iOS gates only the closing; the reply is any walk's reflection reply (§11.1). Fix: test "a non-arrived walk with an earlier reflection reply shows 'your reply' and no closing" (matched as shipped).
- **C11. U40 needs the walk's events in the live summary flow.** The closing gate reads `HONOR_ARRIVAL`; `HonorSummaryModel.summaryState(record)`, which the live flow uses (`WalkSummaryViewModel.kt:461-468`), has no events. Fix: add `WalkSummaryViewModel.kt` to U40's files and pass the events (or an `arrived` flag) through.
- **C12. "the day" shows before Start too.** U38 says "the options row mid-walk reopens the card". iOS's row isn't gated on the walk's status: it shows on the pre-walk screen as well (§10). Fix: test both.
- **C13. "the day" reopens as a sheet, not through the card layer.** U38's "Patterns to follow: … `HonorCardLayer` for the reopen" points at the wrong pattern. The reopen is the options sheet's 0.3 s handoff to a full-height sheet (§10), the way `onSetIntention` hands off. Add `P/ui/walk/ActiveWalkScreen.kt` to U38's files.
- **C14. E-15 and E-16 resolution.** The plan leaves both to "follow the spec". Rule: match both as shipped. E-15 ("they sat here 5 minutes" after a stage's Sit?) is a "they" on a stage surface that iOS ships, so R14 allows it; file it. E-16 (distance, tick and fly-to at `at`, up to 1.2 km from the pin in live data) is matched and filed as a question (§19).
- **C15. The water caption's unit rule.** The plan's R14 wording `"water in 280 m"` is one example; the caption follows the walker's unit through `WayDistance` (m, ft, km, mi), unlike the off-way caption, which is always metres (§6). Test a miles walker: `"water in 0.2 mi"` for 280 m and `"water in 492 ft"` for 150 m.

### 16. Strings table

Every user-visible string in this cluster, verbatim. "Stage they/their" flags plan R14: stage surfaces never say "they" or "their" except where iOS ships it.

| String | Where | Format args | Android today | Stage they/their |
|---|---|---|---|---|
| `water in %@` | minimized stats bar, third slot, 20 s | `WayDistance.string(meters:)`: `280 m`, `1.2 km`, `492 ft`, `0.2 mi` | missing (new `honor_water_caption`) | no |
| `off the way · %d m` | same slot; never on a stage | whole metres, truncated, ≤ 999,999 | `honor_soft_tap_caption` | n/a (not on a stage) |
| `Remaining` | same slot when no caption | | `honor_stat_remaining` | no |
| `A place on the way.` | stage waypoint card body (and preview, P4) | | missing | no (this is the stage form) |
| `A place they marked.` | non-stage waypoint card body | | `honor_moment_place_marked` | "they", but never on a stage |
| *(the dataset's `text`)* | waypoint card body, 4 lines | | shown | dataset words, unedited |
| *(local name)* | under the kicker, 1 line | first of `eu, gl, es, fr, ja, pt, it, de` not equal to the label | ported | dataset words |
| `Sit?` | waypoint card, when `sitMinutes > 0` | | `honor_card_sit` | no |
| `your soundscape holds while you sit` | beside Sit? | | `honor_card_sit_caption` | no |
| `Sit here for %d minutes` | Sit? accessibility label | minutes, always plural | `honor_card_sit_a11y` | no |
| `they sat here %d minutes` / `… 1 minute` | meditation screen, after a stage card's Sit? | minutes | ported (own-walk) | **"they", iOS ships it (E-15)** |
| `you walked the stage` | arrival card title, stage | | missing | no |
| *(stage `name`)* | arrival card, second line | | missing (shows the Way's title) | no |
| `one place passed` / `%d places passed` | arrival line | count | `honor_arrival_one_place` / `honor_arrival_places` | no |
| *(walked distance)* | arrival line, last part, after ` · ` | `StatsHelper` (Android `WalkFormat.distance`) of the engine's walked metres at arrival | missing | no |
| `the whole stage` | arrival line fallback, unreachable | | missing | no |
| *(stage `closing`)* | arrival card, display-medium | | missing | dataset words |
| `reply here` | arrival reply row, no reply yet | | `honor_card_reply_here` (reuse) | no |
| `record again` | arrival reply row, a reply exists | | `honor_card_record_again` (reuse) | no |
| `recording your reply here` | arrival reply row while recording | | `honor_card_recording_reply` (reuse; caption, not body) | no |
| `your reply` | arrival reply row, a reply exists | | `honor_card_your_reply` (reuse) | no |
| `Record a reply to this stage` | arrival reply / record again accessibility label | | missing | no |
| `Stop recording your reply` | arrival stop button label | | `honor_card_stop_reply` (reuse) | no |
| `Play your reply` | arrival "your reply" label | | missing (the place card's is `Play your earlier reply`) | no |
| `continue` | arrival card button | | `honor_arrival_continue` | no |
| `Walked their way: %@` | the arrival waypoint's label (journal data, prompt context, share, export) | the Way's `title` | `honor_arrival_label` | **"their", iOS ships it (P5-D1)** |
| `the day` | walk options row, pre-walk and mid-walk | | missing | no |
| *(stage `theme`)* | the row's subtitle | | missing | dataset words |
| `close` | the morning card's button, opened from "the day" | | missing (P4's card) | no |
| `Close the day's words` | that button's accessibility label | | missing | no |
| `the stage you walked` | summary kicker, stage | | missing | no |
| *(the Way's `title`)* | summary title | | shown | no |
| `%@ of %@ of the stage` | summary progress line | ledger `kmWalked`, stage `distanceKm`, both `StatsHelper` (Android `WalkFormat.distance`): iOS `14.04 km of 24.2 km of the stage`, Android `14.04 km of 24.20 km of the stage` | missing | no |
| `%d reply` / `%d replies` | summary counts line (the reflection reply counts) | count of the Way's `replies.json` | `honor_summary_reply_one` / `honor_summary_replies` | no |
| *(stage `closing`)* | summary, only if arrived | | missing | dataset words |
| `your reply` / `pause` | summary reply button, idle / playing | | missing | no |
| `Play your reply to this stage` | summary reply button label, both states | | missing | no |
| `in their steps` + `a way that has been removed` + `they arrived %d minutes after you` | the summary of a stage walk whose Way is gone (unreachable on iOS; Android only through flow gap 2) | | ported (own-walk) | **"their"/"they", iOS ships the code; keep it unreachable** |
| `their way, walked` | the lock-screen glance at arrival (P3) | | ported | **"their", iOS ships it** |

### 17. Android additions to record at the gate

- **A1. The anchor at Start.** iOS anchors the marks on the pre-Start fix the instant Start is tapped; Android anchors on the walk's first recorded fix, with the camera center (the follow viewport, on the puck) standing in until then. Reason: the UI process's walker fix is the walk's `lastLocation`, which exists only once `:tracker` records. Visible only as marks around the camera rather than the walker for a second or two.
- **A2. The water caption across a UI restart.** iOS shows the caption for 20 s in one process. Android reads it from Room, shows what's left of the 20 s after a UI restart, and never replays it after a `:tracker` revival. Reason: the process split (gap 6).
- **A3. The summary before the Honor step lands.** iOS writes the link and the ledger before the summary opens. Android's summary can open first; until the marker lands its progress line shows the ledger as it stood (an earlier walk's entry, or none), then this walk's. Reason: the Honor step runs in `:tracker` or at the next launch (plan U17). Same family as the existing pre-link arrival numbers.
- **A4. The arrival's walked distance is a Room snapshot.** iOS reads the engine's value at the arrival event in memory; Android persists it in arrival's transaction (C4). Equivalent data; reason, the UI rebuilds the card from rows.
- **A5. The camera report's consumer.** iOS's walk map always passes a camera closure, whose consumer returns at once without marks. Android wires the subscriptions only for a Way with marks and for the overview. No visible difference; it saves per-frame callbacks on every other walk.
- **A6. Glyphs.** Material stand-ins for the six mark symbols and the four stage moment icons (§5.4, §5.6), a platform necessity.
- **A7 (only under D1 option b).** Marks in their own layer, strictly under the moment pins, where iOS interleaves them by screen y.
- **Inherited, not new:** Android's Way pins already sit in a manager of their own above the proximity and waypoint managers (Stage 21-1), where iOS shares one layer for all of them; under D1 option (a) the marks join that Way pin layer.

### 18. Test inventory

Every iOS test that asserts this cluster's behaviour, to port verbatim (R22). Fixture: `UnitTests/Fixtures/Pilgrimage/stage-00.json` (P1 ports it), plus the in-test builders named.

**`UnitTests/Honor/WayMarkPinsTests.swift`** (6; builder `marks(_:)`: `count` water marks east along the equator, `lon = index × 0.000898` (100 m apart), `frac = index / max(count − 1, 1)`, `offLineMeters: 10`, ids `m0…`). Port to `T/ui/walk/map/WayMarkPinsTest.kt`.
- `testEveryKindHasItsOwnGlyph`: the six SF names per kind.
- `testNothingIsDrawnBelowZoomThirteen`: zoom 12.9 gives none; zoom 13 gives all 5.
- `testTheScreenNeverCarriesMoreThanFortyNearestFirst`: 200 marks, anchor at the 150th, zoom 15: exactly 40, including `m150`, `m131`, `m169`, excluding `m0` and `m199`.
- `testWithoutAFixTheFirstFortyAlongTheStageAreDrawn`: 100 marks, no anchor: 40, the first `m0`.
- `testAMarkIsNeverAMomentAndNeverTappable`: a mark pin's `wayMomentID` is nil (Android: `WayMarkPin` has no moment id, and the map tap's target list excludes marks).
- `testAPinLandsOnItsMarksOwnCoordinate`: a mark at (42.881, −8.545) pins there to 1e-9.

**`UnitTests/Honor/ActiveWalkHonorTests+MarkPins.swift`** (3; builder `stageWayWithMarks()`: an 11-point equatorial km, id `pilgrimage:camino-frances:0`, three water marks `m0…m2` 100 m apart, a 33-stage `WayStage` "Stage one"). Port to `T/ui/walk/HonorWalkViewModelTest.kt` (stages).
- `testASharedWayHasNoMarksToDraw`: an own-walk Way with no `marks`: after Begin and two fixes, no marks and **zero publishes**.
- `testTheNearestMarksAreReselectedOnlyOnceTheWalkerHasMovedTwoHundredMetres`: first fix publishes once with `m0` first, and the caption reads `"water in …"` (this one needs P3's water event end to end); 50 m on, no publish; 250 m on, a second publish with `m2` first.
- `testPinchingBelowThirteenHidesTheMarksAndComingBackRestoresThem`: three marks at the seeded zoom; a report at 10.2 empties them; one at 14.1 restores three.

**`UnitTests/Honor/PilgrimageStageWalkTests.swift`** (cluster cases; builder `stageWay(index:marks:)`: the equatorial km, one waypoint `wp-orisson` at frac 0.3 with text, names `eu`/`fr`, `sitMinutes` 5 and a `pin` 0.0002° north; stage `"Saint-Jean-Pied-de-Port to Roncesvalles"`, 24.2 km, closing `"You crossed a border on foot."`).
- `testTheArrivalCardForAStageNamesTheStageAndCarriesNoDelta`: title `"you walked the stage"`; the line contains `"3 places passed"` and the formatted 24,200 m; a shared card's title is `"you walked their way"`.
- `testTheSummaryForAStageReadsKilometresAndNoCompanionDelta`: ledger recorded at frac 0.58: no delta; the line ends `"of the stage"` and contains the formatted `24.2 × 0.58` km.
- `testTheSummaryKickerDropsTheirStepsForAStage`: a stage with no ledger is still a stage (`"the stage you walked"`, no line); a non-stage reads `"in their steps"`; a removed Way isn't a stage.
- `testTheLocalNameFollowsAFixedOrderAndNeverEchoesTheLabel`: `eu` first; `es` when `fr` equals the label; nil when only the label; nil for no names; nil for `ru`; `es` over `en`; nil for `en` alone.
- `testThePlaceCopyChangesForAStage`: `"A place on the way."` / `"A place they marked."` / the text when present.
- `testAPinDrawsAtItsOwnCoordinateWhileTheTriggerStaysOnTheLine`: the moment pin's latitude is the `pin`'s 0.0002; `at` stays 0. Android covers the rule with its own fixture (`T/ui/walk/map/HonorWayRendererTest.kt:365-381`, "pins stand at the place itself, else its projection, else the line at its frac"); the verbatim port on the stage fixture is still owed under R22.
- `testWaterAheadBorrowsTheCaptionLineAndNothingElse`: a `.markAhead(280)` sets `"water in " + WayDistance(280)` and leaves the card queue empty.

**`UnitTests/Honor/PilgrimageStageWalkTests+Replies.swift`** (cluster cases):
- `testAReplyToTheReflectionRoundTrips`: no reflection reply, then one filed under −1 resolves to the recording (Android: the arrival row's `hasReply` lookup).
- `testTheArrivalCardAppendsTheStagesClosingLine`: the card carries the closing and the stage title.
- `testTheSummaryCarriesTheClosingOnlyWhenArrivalFired`: no arrival event, no closing; with one, the closing, `replyRelativePath` and `repliesMade == 1`.
- (P3 owns `testTheReflectionIsFiledUnderTheReservedOrigin` and `testAReplyStillRecordingWhenTheWalkEndsIsStillFiledUnderTheReflection`.)

**`UnitTests/Honor/HonorJournalTests.swift`**: the PR only adds `ledger: nil` to existing `summaryData` calls; the Android tests gain the parameter the same way.

**Android-only tests U39/U40 should add** (not ports): the camera throttle's five rules (§4); the walk ignores a 1 km pan at one zoom (C1); marks before Start on a level change (C2); the arrival row's four states and the absent confirmation (C8); the summary's ungated reply (C10); the miles caption (C15); the summary table rows in §11.2 that the iOS tests don't cover (the shorter re-walk shows the longer figure; an Update-kept entry against the new length).

### 19. iOS defects (matched as shipped)

Android ports each exactly; they're filed upstream later, grouped into themed issues.

- **P5-D1. The arrival waypoint says "their" on a stage** (survey candidate 1, **confirmed**). Evidence §9: `arrivalWaypointLabel(wayTitle: way.title)` has no stage branch, so `"Walked their way: Saint-Jean-Pied-de-Port to Roncesvalles"`. A walker sees it wherever waypoint labels surface, and the AI prompt context reads it, telling the model of another walker on a solo pilgrimage. Low to medium. Extend #109 item 3 (which asks the same of own walks).
- **P5-D2. "record again" on the arrival card replaces the reflection reply without asking, and its label doesn't say so.** Evidence §8.3: `onReply` fires directly, where the place card asks `"Replace your earlier reply?"` (`WayPlaceCard.swift:228-233`); the label is `"Record a reply to this stage"` in both states. A walker re-walking a stage taps "record again" and the earlier reflection is gone once the new take lands. Low to medium. Joins #99.
- **P5-D3. A non-arrived walk's summary offers another walk's reflection reply** (survey candidate 6, **confirmed**). Evidence §11.1: `replyRelativePath: replies[HonorPersistence.stageReflectionOrigin]` with no `arrived` gate, while the closing is gated. The walker sees "your reply" under no closing line, playing a reflection from an earlier walk. Low. Extends #99 item 4.
- **P5-D4. E-16: the card measures to the trail, not the place** (survey's known E-16, **confirmed** with live data). `coordinate(of:)` uses `at`; the dataset puts `pin` up to 1,247 m from `at`. "Show this place on the map" can centre on empty trail over a kilometre from the pin, and "N m away" counts to the trail. Possibly intended (the trail point is where the walker turns off). Low to medium. File as a question.
- **P5-D5. "the whole stage" is dead code.** Evidence §8.1: the stage branch always appends the distance, so `parts` is never empty. Nothing visible; note only (fold into a "smaller faults" issue, like #112).
- **P5-D6. The arrival card's kilometres disagree with the summary's** (survey candidate 5, **confirmed**). The card shows the engine's along-Way credit on the route geometry at arrival (§8.2); the summary shows the ledger's dataset kilometres, the full `distanceKm` once arrived (§11.1). For a 24.2 km stage whose geometry measures 23.9 km, the card says `"23.9 km"` and the summary `"24.2 km of 24.2 km of the stage"`. Low.
- **P5-D7. The summary's reply button keeps "Play your reply to this stage" while it shows "pause".** Evidence §11.3. A VoiceOver walker isn't told the button now pauses. Low. Joins #108.
- **P5-D8. The summary's progress line is the ledger's best, not this walk's** (survey candidate 3, **confirmed**). Evidence §11.1, §11.2: an unanchored or shorter re-walk shows an earlier, longer figure; a past summary changes after a later walk; after an Update a kept entry reads against the new length (`"24.2 km of 25 km of the stage"` on a completed stage) and a dropped one disappears. The summary map's ghost also redraws from the rewritten `way.json` (gap 8). Medium (a summary is a record; it shouldn't change). Pairs with P2's ledger issue.
- **P5-D9. Marks can draw over the moment pins they're meant to sit under.** Evidence §5.3: one symbol layer, overlap on, no sort key, so Mapbox orders by screen y. The device pass expects marks under the pins. Low, cosmetic; needs an iPhone check of a dense town at zoom 15+.
- **P5-D10. The walk screen draws marks before Start only by accident of the camera seed.** Evidence §2: `mapCameraDidChange` isn't gated on status; a seed at zoom 14 (no cached location) draws marks around the camera before Start, a seed at 16 doesn't until a pinch. Low; note-level.
- **Known and named, not re-reported:** E-15 (the meditation caption's "they", confirmed common: every live `sitMinutes` is 5); #99 item 4 (the counts line counts every walk's replies, now including the reflection); #108 item 1 and 2 (card header label; pins and marks unreachable); #109 item 1 (the off-way caption's metres, beside a unit-aware water caption).
- **Survey candidates outside this cluster:** 2 (the overview's synthesized clock, P4), 4 (ledger kilometres are position: confirmed in passing, `walkedKm = km × frac` with `frac = progressFrac`, `PilgrimageLedger.swift:60-67`; P2 owns), 7–9 (P1, P2).
- **A comment that overstates the code:** "idle is the one report that must always get through" (`PilgrimMapView+CameraReport.swift:19-20`): idle bypasses only the throttle (§4). Nothing visible; mention it with P5-D5.

### 20. Notes by unit

**U39 (on the walk)**
- New `P/ui/walk/map/WayMarkPins.kt`: `DRAW_FROM_ZOOM = 13.0` (`>=`), `MAX_PER_SCREEN = 40`, nearest by `wgs84MidLatitudeMeters` then id, stage order without an anchor, `symbol(kind)` with the six SF names, `markGlyphVector(kind)` with the Material stand-ins (§5.4).
- New `CameraReportThrottle` (250 ms on changes only; idle unthrottled; NaN dropped; level or `> 200 m` from the last *report*; first event passes).
- `PilgrimMap`: `onCameraChanged` parameter, subscriptions in their own `DisposableEffect` keyed on `mapView` and whether a consumer exists; a `wayMarks` parameter rendered per D1; marks out of the tap targets; teardown in the style effect and `onRelease`.
- `HonorWalkViewModel`: the mark state (§2): anchor reset at Start, `>=` 200 m from the anchor, camera zoom seeded 16 and applied on `toInt()` changes, center stored on every report, re-apply on a Way change, publish only on change; a `marks` flow; `onCameraChanged(center, zoom)`.
- The overview: `liveCenter`/`liveZoom` nullable, marks only after the first report, re-selected on every report.
- `WayMomentCopy.placeCopy(resources, moment, isStage)` and `"A place on the way."`; 4 lines with an ellipsis.
- The four stage icon stand-ins (C6) if U38 doesn't take them.
- `HonorArrivalSummary` + `HonorArrival.summary` + `HonorArrivalCopy.line` stage branch; `HonorArrivalCard` closing and reply row (four states, no confirmation, its own labels); `HonorCardLayer` passes the stage reply UI; `ActiveWalkScreen` wires `replyHere(…, "stage-reflection")`, stop via `viewModel::toggleRecording`, play via a new −1 play command.
- `HonorSheetStats.caption: HonorCaption?` (sealed), the water string, the 20 s remainder from Room.
- Tests per §18.

**U40 (after the walk)**
- `HonorWalkRecord.ledger`; `HonorWalkRecords.read` loads it by `way.stage.routeId` in both branches.
- `HonorSummaryData` stage fields; the delta nil for a stage; the closing gated on `HONOR_ARRIVAL`; the reply from `replies[-1]`, ungated, shown only if the file exists.
- `WalkSummaryViewModel`: pass the events (or `arrived`) to the live flow (C11); the reply player (play-by-file).
- `HonorSummarySection`: progress line after the title; closing after the counts; reply button with the toggled label and the fixed TalkBack label.
- Tests per §18 and §11.2.

**U35 (P3's, from this cluster)**
- Add `arrival_walked_meters` to schema 12 (C4).
- The caption columns must carry enough for the UI's 20 s remainder: mark id, metres at firing, firing time (wall clock, since the UI compares against `clock.now()`).

**U36 (P3's, from this cluster)**
- `voiceOriginIndex("stage-reflection") == -1` so `HonorReplies.arm` files the arrival card's reply; a play command that can play the −1 reply.

**U38 (P4's, from this cluster)**
- "the day" shows pre-Start too (C12); reopen through the sheet handoff (C13); the morning card takes `buttonTitle = "close"`, its label `"Close the day's words"`, the walk's `activeWeather`, a null maps line.
- The stage moment icon stand-ins on the overview pins and preview (C6).

**U41 (the device pass)**, rows this cluster adds:
- Marks hidden on the overview until a zoom past 13; never more than 40 in a town; none tappable; they stay after a light/dark flip.
- On the walk: marks from Start; a 1 km pan doesn't re-select; walking 200 m does; a pinch below 13 hides them.
- Marks against moment pins where they overlap (D1's outcome).
- The water caption in the stats bar for 20 s, in miles for a miles walker; after a UI kill within the 20 s, the rest of it; after a `:tracker` kill, not again.
- A stage card: the dataset's words or "A place on the way.", the local name, Sit?; the lodge, seal, columns and book icons (not pins).
- Arrival: name, places and kilometres, closing; reply here, record (no dialog on record again), stop, your reply; a re-walk opens with record again.
- Summary: "the stage you walked", the progress line in two decimals, the closing only if arrived, "your reply"; then a shorter re-walk's summary shows the longer figure (matched).

### 21. Proposed owner decisions

- **D1. How marks stack against the moment pins.** (a) Put the marks in the Way pin manager, before the moment pins: the same single layer iOS draws, so overlaps sort by screen y exactly as on iOS, and no creation-order trap. (b) The plan's own manager strictly below: iOS's stated intent, but not its rendering, recorded at the gate. **Recommendation: (a)**, parity plus P5-D9 filed upstream; if iOS answers by layering marks under the pins, Android moves to (b) then.
- **D2. The `seal` stand-in.** The dataset's second most common icon (136 moments, Shikoku's temples) has no close Material equivalent: `Icons.Outlined.Verified` is a scalloped seal with a check mark, which reads as "verified". **Recommendation:** use `Verified` now for parity of presence, and if the owner wants the plain scalloped seal, draw a vector asset (a scalloped circle) in U39, since `house.lodge`, `building.columns` and `book.closed` have good Material matches.

---

## Annex. iOS PR #91, a temple ahead stamps until five (conditional)

| | |
|---|---|
| iOS before | `7c200bf` (parity pin, v2.0.0) |
| iOS after | `534f170` (PR #91 head; based directly on `7c200bf`) |
| Android HEAD | `0defff85` (branch `docs/stage21-2-plan`) |
| Feeds | the named fold-in unit; and now, U35's schema-12 design. Touches U31, U34, U35, U38, U39 |
| Lenses | behavior, UI/visual, data, edge cases |
| Status | conditional: ports only if #91 merges before the parity gate (R2) |

**Read in full at `534f170`:** `Pilgrim/Models/Honor/HonorMomentTracker.swift`, and the whole diff `7c200bf..534f170` (14 files; the README hunks read for anything behavioral, none found). Read around every hunk at both shas: `HonorEngine.swift`, `HonorTuning.swift`, `PilgrimagePackageManager.swift`, `PilgrimageWayImporter.swift`, `Way.swift`, `ActiveWalkViewModel+Honor.swift`, `ActiveWalkViewModel+MarkPins.swift`, `WayMomentHeader.swift`; and the four test files (`HonorMomentTrackerTests.swift`, `HonorWayRenderingTests.swift`, `PilgrimageStageWalkTests.swift`, `PilgrimageWayImporterTests.swift`). Also the PR's description and commit messages, and the live data in `../open-pilgrimages` and the live CDN index fetched on 2026-10-02.

**Android compared at `0defff85`:**
- `P/domain/honor/{HonorMomentTracker,HonorEngine,HonorTuning,Way,WayJson}.kt`;
- `P/data/honor/{HonorSessionEntity,HonorMomentStateEntity,HonorDao}.kt`, `P/data/{PilgrimDatabase,Converters}.kt`;
- `P/walk/honor/{HonorSession,HonorSessionState,HonorAudioPorts}.kt`;
- `P/ui/walk/{HonorWalkViewModel,WalkStatsSheet}.kt`, `P/ui/honor/{WayMomentHeader,WayPlaceCardState}.kt`;
- the golden harness under `app/src/test/resources/honor/golden/`.

**Probes** (all in an uncommitted scratch folder):
- `src.diff`, `tests.diff`: the PR's diff.
- `golden/`: the nine golden traces recaptured with iOS's engine at `534f170`.
- `tracker/main.swift`: iOS's tracker at `534f170`, numbered scenarios:
  - probe 1, the nearest temple inside 1 km is skipped;
  - probe 2, water before the window;
  - probe 3, a close after midnight;
  - probe 4, the next day;
  - probe 5, `templeNumber` parsing;
  - probe 6, both watchers eligible on one fix;
  - probe 7, off the way.
- `decode.swift`: mis-shaped `stampHours`.
- `jprobe.swift`, `jall.swift`: the `"j"` hour pattern and `Int(_:)`.
- `p.jsh`: the JVM's integer parsing.

### A.0 What #91 is, in five lines

1. `route.json` gains an optional `stampHours: {opens, closes}` ("HH:mm"). The importer turns it into minutes since midnight (`WayStampHours`), and the package manager copies it onto every stage `Way` it stages (`Way.stampHours`, the last field, omitted when nil).
2. A stage whose Way carries hours gets a new tracker watcher, `stampAhead`. On each on-way fix, in the last 120 minutes before the office closes (by the engine's injected wall clock, in the Way's `tzIdentifier` zone or else the phone's), it names the nearest unfired numbered temple ("Temple N · …" in `text`) at least 1,000 m ahead, once per temple.
3. Water and temple notices now share one quiet clock (`lastMarkSeconds` becomes `lastNoticeSeconds`), and the temple is checked first in a tick. So at most one notice lands per update, and a notice of either kind holds both for 3,600 engine seconds.
4. The view model shows "temple 10 stamps until 5 · 3.2 km" in the existing caption slot for 20 s, with water's own soft haptic. The hour follows the walker's 12-hour or 24-hour clock, and the distance follows their unit. Nothing else on screen changes.
5. For Android, the session gains exactly two pieces of state over `7c200bf`: a **fired-stamps set** (temple moment ids), and a **kind** on the persisted caption (water or temple, which also says whether its id is a mark or a moment). The quiet-hour time is the same nullable engine-seconds number with a wider meaning. **Recommendation (A.5):** U35 puts all notice state in one new table, `honor_notices`, so #91 needs no schema change at all, whether it merges before U35 or after.

The existing nine golden traces recapture **byte for byte** under iOS's engine at `534f170` (probe in A.6). The fold-in's "recapture" is a verification step plus new temple traces.

### A.1 The wire: `stampHours` in `route.json`, and the Way it reaches

#### A.1.1 The dataset side (open-pilgrimages)

The field is the dataset's, merged as open-pilgrimages #19 on 2026-09-22 at 20:37 UTC (commits `c090e9b`, `1d9fabf`, `d066978`) and released as **v1.12.0**, the release the live CDN index fetched on 2026-10-02 names (`"generatedAt": "2026-09-22T20:38:01.952Z"`). The dataset's schema (`schema/way-route.schema.json`):

```json
"stampHours": {
  "type": "object",
  "required": ["opens", "closes"],
  "properties": {
    "opens": { "type": "string", "pattern": "^([01][0-9]|2[0-3]):[0-5][0-9]$" },
    "closes": { "type": "string", "pattern": "^([01][0-9]|2[0-3]):[0-5][0-9]$" }
  },
  "additionalProperties": false
}
```
> open-pilgrimages `schema/way-route.schema.json` (as of `675d4e3`, v1.12.0). Not machine-checked.

The build derives it from `pilgrimage.stats.infrastructure.templeHours.current` ("08:00-17:00"). A string it can't read is omitted, never emptied, and `build-ways` logs it. Placement: after `summary`, before `cover` and `stages`.

**Live data at v1.12.0 (verified with a script over `../open-pilgrimages`):**
- Exactly four routes carry it, all with `{"opens": "08:00", "closes": "17:00"}`: `shikoku-88-awa`, `shikoku-88-tosa`, `shikoku-88-iyo`, `shikoku-88-sanuki`. Every other route in the index (`camino-frances`, `camino-norte`, `kumano-kodo-kohechi`, `kumano-kodo-nakahechi`, and the six without ways) carries none.
- Key order in the Shikoku files: `schemaVersion, id, name, country, region, distanceKm, stageCount, tradition, summary, stampHours, stages` (no `cover` key in the live files).
- **No stage file carries a `tzIdentifier`** (0 of 115). The office's clock is therefore always the phone's zone in practice (A.3.2).
- Moments whose `text` starts with `"Temple "`: **88**, numbers 1 to 88, each once, all in the four Shikoku routes, all with `icon: "seal"`. Moments with `icon: "seal"`: 136. The 48 seals that aren't numbered temples are 42 on `camino-frances` and 6 on `kumano-kodo-nakahechi`. This matches the PR's figures ("`seal` hits **136** moments of which **48 are not temples**"; "`Temple ` hits **exactly 88**").
- Temples per Shikoku stage: 0 temples on 11 stages, 1 on 11, 2 on 6, 3 on 3, 4 on 1, 5 on 4, 6 on 1, 7 on 1, 9 on 1, 10 on 1. A sample text: `"Temple 1 · Koyasan Shingon · stamp available (¥500)"`. `temple-1` sits at frac 0 of Awa stage 0 (so it can never be "ahead"), and many stages end on a temple at frac 1.

#### A.1.2 The route file decode

```swift
        /// "HH:mm" on a 24-hour clock, as the dataset's schema writes it.
        struct StampHours: Decodable {
            let opens: String
            let closes: String
        }
        // …
        let summary: String?
        let stages: [Stage]
        let stampHours: StampHours?
```
> Pilgrim/Models/Honor/PilgrimageWayImporter.swift:157-172@534f170

```swift
    static func route(from data: Data) throws -> PilgrimageRoute {
        guard data.count <= maxRouteBytes, let file = try? JSONDecoder().decode(RouteFile.self, from: data) else {
            throw PilgrimageError.notWalkable
        }
        // …
            summary: trimmed(file.summary, maxSummaryCharacters),
            stampHours: stampHours(file.stampHours),
```
> Pilgrim/Models/Honor/PilgrimageWayImporter.swift:271-298@534f170

```swift
    private static func stampHours(_ raw: RouteFile.StampHours?) -> WayStampHours? {
        guard let raw, let opens = minutesSinceMidnight(raw.opens),
              let closes = minutesSinceMidnight(raw.closes) else { return nil }
        return WayStampHours(opensMinutes: opens, closesMinutes: closes)
    }

    private static func minutesSinceMidnight(_ clock: String) -> Int? {
        let parts = clock.split(separator: ":", omittingEmptySubsequences: false)
        guard parts.count == 2, parts[0].count == 2, parts[1].count == 2,
              let hour = Int(parts[0]), let minute = Int(parts[1]),
              (0...23).contains(hour), (0...59).contains(minute) else { return nil }
        return hour * 60 + minute
    }
```
> Pilgrim/Models/Honor/PilgrimageWayImporter.swift:362-374@534f170

What iOS does:
- `stampHours` is absent or `null`: nil, and the route decodes.
- Both members are strings: each must split on `:` into exactly two parts of exactly two characters, parse with Swift's `Int(_:)`, and fall in 0–23 and 0–59. If **either** fails, the pair is dropped whole (nil) and the route stays walkable. `opensMinutes` is parsed and kept but **never read** by any rule.
- No check that `closes > opens`.
- **A structurally wrong `stampHours` fails the whole route.** The decode is `try? JSONDecoder().decode(RouteFile.self, …)`, and an Optional property that is present but mis-shaped throws instead of decoding as nil. A probe (a scratch probe, `decode.swift`, not committed) confirms each of these makes `route(from:)` throw `.notWalkable`:
  - `{"opens":"08:00"}` (a missing key);
  - `{"opens":8,"closes":17}` (numbers);
  - `"08:00-17:00"` (a string);
  - `{"opens":null,"closes":"17:00"}` (a null member).

  An extra member (`"note"`) is ignored. See defect candidate PR91-D2.
- **`Int(_:)` edge cases** (probed on this Mac, `probe-pr91/jprobe.swift`):
  - `Int("+8")` = 8 and `Int("-0")` = 0, so `"+8:00"` reads as 480 and `"-0:00"` as 0;
  - `Int("٠٨")` (Arabic-Indic digits) = nil;
  - `Int(" 8")` = nil.

  On the JVM, `Integer.parseInt("٠٨")` = 8 (probed with `jshell`), and Kotlin's `toIntOrNull()` also accepts non-ASCII digits. So **Android must not use a bare `toIntOrNull()`.** For exact parity, each part must match `^[+-]?[0-9]{1,2}$` and be exactly two UTF-16 units long (the same as Swift's grapheme count for every string that can parse), then `toInt()`, then the range check.
- **The Android Json config matters here:** `isLenient = false` and `coerceInputValues = false`. Under lenient parsing, `"opens": 8` would decode as the string `"8"`, the pair would then drop, and the route would stay walkable, where iOS refuses it. kotlinx's defaults (strict quoting, no coercion) reproduce every case above, including `ignoreUnknownKeys = true` for the extra member.

The route model (where the field sits, between `summary` and `stages`):

```swift
    let summary: String?
    /// Only a route whose dataset declares them; nil everywhere else. Sits
    /// where `route.json` writes it, between the summary and the stages.
    let stampHours: WayStampHours?
    let stages: [PilgrimageRouteStage]
```
> Pilgrim/Models/Honor/PilgrimageWayImporter.swift:46-50@534f170

#### A.1.3 The Way model and `way.json`

```swift
struct WayStampHours: Codable, Equatable {
    let opensMinutes: Int
    let closesMinutes: Int
}
```
> Pilgrim/Models/Honor/Way.swift:159-162@534f170

```swift
    /// Present only for a pilgrimage stage.
    var stage: WayStage?
    /// The route's stamp hours, copied onto each of its stages at import so
    /// the walk reads one Way and nothing else. Optional and last, like
    /// `spans` and `marks`: a `way.json` written before stamp hours existed
    /// still decodes, and every route that declares no hours stays silent.
    var stampHours: WayStampHours?
```
> Pilgrim/Models/Honor/Way.swift:242-248@534f170

- **Encoding in `way.json`:** `"stampHours": {"closesMinutes": 1020, "opensMinutes": 480}` under `WayStore`'s `[.sortedKeys]`. Synthesized `Codable` uses `encodeIfPresent`, so the key is **absent** (never `null`) when nil.
- **Decoding:** a `way.json` without the key decodes as nil (the PR's test removes the key and decodes, A.8).
- **Android at `0defff85`:** `Way` ends at `val stage: WayStage? = null` (`P/domain/honor/Way.kt:287@0defff85`). `WayJson` already sorts keys, omits nulls (`explicitNulls = false`), and ignores unknown keys:

```kotlin
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
```
> app/src/main/java/org/walktalkmeditate/pilgrim/domain/honor/WayJson.kt:43-46@0defff85

  The fold-in adds `@Serializable data class WayStampHours(val opensMinutes: Int, val closesMinutes: Int)` and `val stampHours: WayStampHours? = null` as `Way`'s last property. `WayCodecTest` gains the round-trip and the older-file decode. Nothing else in the codec changes.
- **Cross-version reads (both platforms):** a pre-#91 build reading a post-#91 `way.json` ignores the key, since unknown keys are skipped. A post-#91 build reading a pre-#91 `way.json` gets nil, so **a stage installed before #91 stays silent until it is downloaded again** (defect candidate PR91-D1).

#### A.1.4 The stage importer takes the hours as a parameter

```swift
    /// `stampHours` comes from the route this stage belongs to: it is written
    /// once on `route.json`, and the manager hands it down as each stage is
    /// staged. A stage parsed without its route — a spec, a stage file read
    /// on its own — simply carries none.
    static func way(from data: Data, routeId: String, stageIndex: Int, stampHours: WayStampHours? = nil) throws -> Way {
```
> Pilgrim/Models/Honor/PilgrimageWayImporter.swift:177-181@534f170

```swift
        way.marks = marks(from: file.marks)
        way.stage = stage(from: file.stage)
        way.stampHours = stampHours
        return way
```
> Pilgrim/Models/Honor/PilgrimageWayImporter.swift:213-216@534f170

The stage file itself never carries the hours. The value is copied verbatim, with no validation of its own, so the only validation is the route file's (A.1.2).

### A.2 The package manager threads the hours onto each stage

```swift
                let stagePlan = StagePlan(routeId: entry.id, url: stageURL, stageCount: fetched.route.stageCount,
                                          expected: fetched.route.stages[index],
                                          stampHours: fetched.route.stampHours)
                packageBytes += try await Self.stageOneStage(stagePlan, into: temp, session: session)
```
> Pilgrim/Models/Honor/PilgrimagePackageManager.swift:185-188@534f170

```swift
        /// `route.json`'s, copied onto the stage Way here: the two files come
        /// down separately and only this loop holds both, while the walk that
        /// reads them later holds one Way and no route at all.
        let stampHours: WayStampHours?
```
> Pilgrim/Models/Honor/PilgrimagePackageManager.swift:333-336@534f170

```swift
        let way = try PilgrimageWayImporter.way(from: data, routeId: plan.routeId, stageIndex: index,
                                                stampHours: plan.stampHours)
```
> Pilgrim/Models/Honor/PilgrimagePackageManager.swift:352-353@534f170

What iOS does:
- `download` parses `route.json` once (`stageRouteFile`), then hands the same `stampHours` to every stage's `StagePlan`. Each stage's Way is built with it, encoded with the store's encoder into `temp/<index>.way.json`, and decoded and saved at commit. So the hours land in every stage's `way.json`.
- `route.json` itself is committed **as the raw bytes fetched** (`write(try Data(contentsOf: temp…route.json))`, `PilgrimagePackageManager.swift:423-424@534f170`). So an install made under v2.0.0 from dataset v1.12.0 already holds `stampHours` in `pilgrimage/<route>/route.json`, while its stage `way.json` files don't. #91 adds no backfill (PR91-D1).
- Update and Replace both go through `download` (Replace of the same route is an Update; Replace of another route downloads it in full first). So every path that writes stage Ways threads the hours. No other code path builds a stage Way.
- **Nothing else changes:** byte budget, single-flight, the walk guard, commit and rollback are untouched.
- `hasUpdate` compares the installed `release.txt` with the catalog's release (`PilgrimageCatalogView.swift:31-38@534f170`). A build upgrade alone never raises "update ready".

**Android (U34):** the `StagePlan` analogue gains `stampHours: WayStampHours?` from the parsed route, and the stage import call passes it. The ported U34 tests need no change, since the fixture `route.json` carries no `stampHours`. No new test is in iOS's package-manager suite. The importer tests cover the threading (A.8, `testAStageCarriesItsRoutesStampHoursAndSurvivesTheStoresEncoding`). An Android-side assertion in U34's download test, "a route with hours hands them to every staged stage", is cheap and worth adding as a dated Android addition (A.11).

### A.3 The engine and the tracker: the temple-ahead watcher

#### A.3.1 Tunings

```swift
    static let markQuietSeconds: TimeInterval = 3600
    /// How long before the stamp office shuts the notice is worth saying —
    /// from 15:00 for a 17:00 close. Earlier it is a fact about the
    /// afternoon rather than a choice anyone is making.
    static let stampNoticeMinutes = 120
    /// Nearer than this and the temple has stopped being a decision: the
    /// walker is already arriving.
    static let stampMinAheadMeters = 1000.0
```
> Pilgrim/Models/Honor/HonorTuning.swift:32-39@534f170

Android adds `const val STAMP_NOTICE_MINUTES = 120` (an `Int`) and `const val STAMP_MIN_AHEAD_METERS = 1000.0` next to `MARK_QUIET_SECONDS` in `P/domain/honor/HonorTuning.kt` (`:38@0defff85`). The quiet hour has no constant of its own: the temple reuses `MARK_QUIET_SECONDS`.

#### A.3.2 The office and its clock

```swift
    /// The office keeps the Way's own clock where it names one and the
    /// walker's phone otherwise — which on Shikoku is the same clock, since
    /// the dataset's stage files carry no time zone and the walker is
    /// standing in front of the temple.
    private static func stampOffice(for way: Way, now: @escaping () -> Date) -> HonorMomentTracker.StampOffice? {
        guard let hours = way.stampHours else { return nil }
        return HonorMomentTracker.StampOffice(
            closesMinutes: hours.closesMinutes,
            timeZone: way.tzIdentifier.flatMap(TimeZone.init(identifier:)) ?? .current,
            now: now)
    }
```
> Pilgrim/Models/Honor/HonorEngine.swift:95-105@534f170

```swift
    struct StampOffice {
        let closesMinutes: Int
        let timeZone: TimeZone
        let now: () -> Date

        /// True from two hours before the office shuts until it does.
        /// Earlier is noise; later there is no stamp left to be had, and a
        /// walker who has already lost it does not need telling.
        var isClosingSoon: Bool {
            var calendar = Calendar(identifier: .gregorian)
            calendar.timeZone = timeZone
            let clock = calendar.dateComponents([.hour, .minute], from: now())
            guard let hour = clock.hour, let minute = clock.minute else { return false }
            let minutesNow = hour * 60 + minute
            return minutesNow >= closesMinutes - HonorTuning.stampNoticeMinutes && minutesNow < closesMinutes
        }
    }
```
> Pilgrim/Models/Honor/HonorMomentTracker.swift:24-40@534f170

"Until five", exactly:
- **Whose clock:** the engine's `now`, the same injected wall clock behind the soft tap and the re-acquire (`HonorEngine.swift:81-93@534f170`). It is read once per call to `isClosingSoon`, which only runs on a fix that passed the earlier guards (A.3.4). It is not the engine clock (`activeSeconds`).
- **Whose zone:** the Way's `tzIdentifier` if `TimeZone(identifier:)` accepts it, else `TimeZone.current`. Both are captured **once**, when the engine is built. No live stage carries a `tzIdentifier` (A.1.1), so in practice it's the phone's zone at Begin. The iOS fixture `stage-00.json` does carry `"tzIdentifier": "Europe/Madrid"`, which no stamp test reads.
- **The window:** local `hour*60 + minute` (seconds truncated) in `[closes − 120, closes)`, so 15:00:00 through 16:59:59 for a 17:00 close. Both ends are pinned by `testTheNoticeOpensTwoHoursBeforeClosingAndShutsWithTheOffice` (14:59 silent, 15:00 fires, 17:00 silent).
- **After closing:** nothing fires, and no temple is marked fired. A temple still unfired speaks again in the **next day's** window if the walk is still going (probe 4: unfired at 17:00, it fires at 15:00 the next day).
- **No midnight wrap:** for a close at 00:30 the window is `[-90, 30)`, so 00:00–00:29 only. 23:30 stays silent (probe 3). This can't happen with the live 17:00.
- `opensMinutes` plays no part: an office opening after `closes − 120` would still be "closing soon" before it opens. This can't happen with the live data.

**Android mapping:** `HonorEngine` has `clock: Clock` (epoch millis, `P/domain/honor/HonorEngine.kt:58@0defff85`). The office is `StampOffice(closesMinutes: Int, zone: ZoneId, clock: Clock)` with `isClosingSoon` computed from `Instant.ofEpochMilli(clock.now()).atZone(zone)`, `hour * 60 + minute`. The zone is resolved at engine construction from `way.tzIdentifier` with the same try/fallback `WayMomentCopy.wayZone` uses (`P/ui/honor/WayMomentHeader.kt:135-142@0defff85`), falling back to `ZoneId.systemDefault()`. Java's `ZoneId.of` and iOS's `TimeZone(identifier:)` accept slightly different ids, which can't matter, since no stage carries one. For tests and golden traces, the engine should take the fallback zone as a constructor parameter defaulting to `ZoneId.systemDefault()`, so a test pins it without a process-wide `TZ`. In `:tracker` the engine is rebuilt on revival (`HonorSession.prepare`, `P/walk/honor/HonorSession.kt:239-244@0defff85`), so a revived engine re-reads the zone; iOS never revives (A.11).

#### A.3.3 Which moments are temples

```swift
    var templeNumber: Int? {
        guard let text, text.hasPrefix(Self.templePrefix) else { return nil }
        let digits = text.dropFirst(Self.templePrefix.count).prefix { $0.isASCII && $0.isNumber }
        guard (1...4).contains(digits.count) else { return nil }
        return Int(digits)
    }

    private static let templePrefix = "Temple "
```
> Pilgrim/Models/Honor/Way.swift:107-114@534f170

```swift
        self.stamp = stamp
        self.fudasho = stamp == nil ? [] : self.moments.filter { $0.templeNumber != nil }
```
> Pilgrim/Models/Honor/HonorMomentTracker.swift:84-85@534f170

- A temple is any moment whose `text` starts with the case-sensitive `"Temple "` followed by 1–4 ASCII digits. Only the digits are read. Probed outcomes (`probe-pr91/tracker/main.swift`):
  - `"Temple 0 · x"` → 0;
  - `"Temple 01 · x"` → 1;
  - `"Temple 10· x"` → 10;
  - `"Temple 12345 · x"` → nil;
  - `"temple 10 · x"` → nil;
  - `"Temple ١٠ · x"` → nil.
- `fudasho` is empty unless the Way has hours, so on every own walk, shared walk and non-Shikoku stage the watcher is a single guard. It is built from `self.moments`, already sorted by frac with ties by id, and includes every kind. Stage Ways hold only waypoints, and only stage Ways can have hours, so voices never qualify in practice.
- **Android:** `val templeNumber: Int?` on `WayMoment` (`P/domain/honor/Way.kt`): `text?.takeIf { it.startsWith("Temple ") }`, then `drop(7).takeWhile { it in '0'..'9' }`, then a length of 1–4, then `toInt()`. Use a `'0'..'9'` range, **not** `Char.isDigit()`, which accepts Arabic-Indic and other Unicode digits where Swift's `isASCII && isNumber` doesn't. Swift's `hasPrefix` compares canonically equivalent graphemes and Kotlin's `startsWith` compares UTF-16 units. They agree on every string whose first seven characters are this ASCII prefix.

#### A.3.4 The scan

```swift
    private mutating func stampAhead(progressFrac: Double, activeSeconds: TimeInterval, isOnWay: Bool) -> [Action] {
        guard isOnWay, !fudasho.isEmpty, geometry.totalMeters > 0, let stamp else { return [] }
        if let last = lastNoticeSeconds, activeSeconds - last < HonorTuning.markQuietSeconds { return [] }
        guard stamp.isClosingSoon else { return [] }
        for temple in fudasho where !firedStamps.contains(temple.id) {
            let ahead = (temple.frac - progressFrac) * geometry.totalMeters
            // Behind them, or close enough that the walk has already decided.
            guard ahead >= HonorTuning.stampMinAheadMeters else { continue }
            firedStamps.insert(temple.id)
            lastNoticeSeconds = activeSeconds
            return [.stampAhead(temple, meters: ahead)]
        }
        return []
    }
```
> Pilgrim/Models/Honor/HonorMomentTracker.swift:189-202@534f170

The guards and the scan, in order:
1. Return nothing off the way, with no temples, on a zero-length Way, or with no office.
2. The shared quiet hour: return nothing while `activeSeconds − lastNoticeSeconds < 3600`. With `lastNoticeSeconds` nil, the first notice is free.
3. The clock: return nothing outside the window.
4. Walk the temples in frac order, skipping fired ones. `ahead` is along-line metres from progress (`(frac − progressFrac) × totalMeters`), the same measure water uses, so there are no `CLLocation.distance` calls and the golden traces' distance-call lists don't change. A temple **behind or less than 1,000 m ahead is skipped with `continue`**, never `break`, and stays unfired. The first temple 1,000 m or more ahead fires (`>=`: exactly 1,000 m fires).
5. There is **no upper bound**: a temple 18 km ahead speaks (the PR: "No upper bound: a temple 18 km out still speaks").

Consequences the units must reproduce (each probed at `534f170`):
- **The nearest temple inside a kilometre is passed over for the next one.** At 15:00, with temple 10 at 500 m and temple 11 at 8 km, the line names **temple 11** (`"stamp temple-11 7988m"`, probe 1). The doc comment intends this ("The nearest stamp temple still far enough ahead to be a choice").
- A temple skipped inside 1 km never speaks later, because it only gets nearer.
- **Off the way the quiet clock doesn't move**, and the first on-way fix in the window speaks (probe 7).
- **There is no phase guard:** after `.arrived` the tracker's `update` still runs on every fix, as water's does at the pin. A temple left behind at a stage's end is behind, so this is benign.

#### A.3.5 One quiet clock for water and temples, temple first

```swift
    /// Active seconds at the last notice of any kind — water or temple; nil
    /// means the first is free. One clock for both, so the two can never
    /// land on the caption line together and the walk screen stays as quiet
    /// as one notice an hour.
    private var lastNoticeSeconds: TimeInterval?
    private let stamp: StampOffice?
    /// The stamp temples among the moments, in the order they are passed.
    /// Empty whenever the route states no hours, so the scan below has
    /// nothing to scan on every Way but Shikoku's.
    private let fudasho: [WayMoment]
    private var firedStamps: Set<String> = []
```
> Pilgrim/Models/Honor/HonorMomentTracker.swift:59-69@534f170

replacing, at the pin:

```swift
    private var firedMarks: Set<String> = []
    /// Active seconds at the last water caption; nil means the first is free.
    private var lastMarkSeconds: TimeInterval?
```
> Pilgrim/Models/Honor/HonorMomentTracker.swift:35-37@7c200bf

```swift
        // The temple before the fountain: both draw on the same quiet period,
        // and of the two an office about to shut is the one still worth a
        // walker's afternoon.
        actions += stampAhead(progressFrac: progressFrac, activeSeconds: activeSeconds, isOnWay: isOnWay)
        actions += waterAhead(progressFrac: progressFrac, activeSeconds: activeSeconds, isOnWay: isOnWay)
        actions += startNextIfPossible(gates: gates)
        return actions
```
> Pilgrim/Models/Honor/HonorMomentTracker.swift:126-132@534f170

```swift
        if let last = lastNoticeSeconds, activeSeconds - last < HonorTuning.markQuietSeconds { return [] }
        for mark in marks where !firedMarks.contains(mark.id) {
            let ahead = (mark.frac - progressFrac) * geometry.totalMeters
            guard ahead >= 0 else { continue }
            guard ahead <= HonorTuning.markAheadMeters else { break }
            firedMarks.insert(mark.id)
            lastNoticeSeconds = activeSeconds
            return [.markAhead(mark, meters: ahead)]
```
> Pilgrim/Models/Honor/HonorMomentTracker.swift:168-175@534f170

- **`lastMarkSeconds` is renamed to `lastNoticeSeconds`; nothing is added.** It keeps the type (`TimeInterval?`), the nil-means-free rule, and the comparison (`<` 3,600). Water's own logic is otherwise unchanged. `firedMarks` stays, and `firedStamps` is a **separate** set.
- **Order within one `update`:**
  1. moments reached;
  2. queued voices dropped, then a paused current voice dropped;
  3. `stampAhead`;
  4. `waterAhead`;
  5. `startNextIfPossible`.

  Since both watchers gate on the same clock and a fire sets it, **at most one of `stampAhead` and `markAhead` per update**, and the temple wins a tie (`testTheTempleTakesTheLineAheadOfTheFountain`). At the engine level, one fix's events are `softTap?`, `arrived?`, then the tracker's actions in the order above (`HonorEngine.swift:162-176@534f170`, unchanged).
- **The interplay, both ways:**
  - A temple notice holds every fountain for 3,600 engine seconds. A fountain passed inside that hour stays a silent pin, but can still speak later if it is still within 300 m ahead when the hour ends (water's rule at the pin).
  - **A water notice in the hour before the window holds the temple too.** Water at 14:30 (engine 0) silences the temple at 15:10. By 15:31 the walker is 900 m short, and temple 10 never speaks (probe 2: `[]`, `[]`). See PR91-D6.
  - During the window, the temple goes first on any fix where both are eligible.
- **No change for a Way without hours:** with `stamp == nil`, `stampAhead` returns at its first guard and touches no state. So `lastNoticeSeconds` is written exactly where `lastMarkSeconds` was, and every water-only behaviour is identical to the pin. This is why the golden traces don't move (A.6).

#### A.3.6 The event

```swift
    case markAhead(mark: WayMark, meters: Double)
    case stampAhead(temple: WayMoment, meters: Double)
    case arrived(theirSeconds: Double, yourSeconds: Double)
```
> Pilgrim/Models/Honor/HonorEngine.swift:14-16@534f170

```swift
            case .markAhead(let mark, let meters): subject.send(.markAhead(mark: mark, meters: meters))
            case .stampAhead(let temple, let meters): subject.send(.stampAhead(temple: temple, meters: meters))
```
> Pilgrim/Models/Honor/HonorEngine.swift:334-335@534f170

The tracker's `Action.stampAhead(WayMoment, meters:)` (`HonorMomentTracker.swift:17-18@534f170`) maps one-to-one onto `HonorEngineEvent.stampAhead(temple:meters:)`. `meters` is the along-line distance at the firing fix, unclamped (always at least 1,000 and at most the Way's length). Android adds `HonorMomentTracker.Action.StampAhead(val temple: WayMoment, val meters: Double)` and `HonorEngineEvent.StampAhead(val temple: WayMoment, val meters: Double)`, with the `toEvent()` arm (`P/domain/honor/HonorEngine.kt:369-375@0defff85`), placed after `MarkAhead` in both sealed classes.

#### A.3.7 Android's tracker today

Android's tracker has no water watcher yet (U35 adds it). Its revival snapshot carries only the reached set and the queue:

```kotlin
    /** What a revival needs back: the moments reached, and the voices waiting, in queue order. */
    data class Snapshot(val reached: Set<String>, val queue: List<String>)
```
> app/src/main/java/org/walktalkmeditate/pilgrim/domain/honor/HonorMomentTracker.kt:41-42@0defff85

U35 extends it with water's state. If #91 is coming, name the field `lastNoticeSeconds` from the start (A.5.4), and the fold-in adds `firedStamps: Set<String>`. `restore` filters `firedStamps` to `fudasho` ids, the way it filters reached ids to the Way's moments today (`HonorMomentTracker.kt:128-136@0defff85`). The constructor gains `stamp: StampOffice? = null`, defaulting so every own-walk and shared-walk call site keeps its shape, as iOS's `stamp: StampOffice? = nil` does (`HonorMomentTracker.swift:71-72@534f170`).

### A.4 On screen: the view model, the caption, the haptic

#### A.4.1 The event handler

```swift
        case .markAhead(let mark, let meters):
            showMarkCaption(mark: mark, meters: meters)
            fireHonorHaptic(.honorWaterAhead)

        case .stampAhead(let temple, let meters):
            showStampCaption(temple: temple, meters: meters)
            // The water source's tap, deliberately: one at the whisper's
            // intensity, a notice and not an alert. Both are the same kind
            // of passing word, so they feel the same in the pocket.
            fireHonorHaptic(.honorWaterAhead)
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:204-213@534f170

- Caption first, then the haptic: **the same `.honorWaterAhead` pattern** as water. `HapticManager` is unchanged by #91 (`HapticManager.swift:217-223@534f170`: "One tap at the whisper's intensity: a notice, not an alert", with a soft-impact fallback).
- The haptic fires **even if `showStampCaption` returns early** (no number, no hours). The tracker never emits the event in that state, so this can't happen today (PR91-D4).
- No card, no pin, no sound (`testTheStampNoticeBorrowsTheCaptionLineAndNothingElse` asserts `honorCards.isEmpty`).

#### A.4.2 One caption line, shared

```swift
    private func showSoftTapCaption(meters: Double) {
        // `Int(_:)` traps on an infinity; the engine already clamps, and this
        // is the last line of defence before the number reaches the screen.
        showHonorCaption("off the way · \(Int(min(meters.isFinite ? meters : 0, 999_999))) m")
    }

    /// The walk screen's one caption line, shared by every notice that may
    /// use it. It retires itself, so a walker who rejoins the Way is never
    /// left reading a distance they have already closed. Generation-guarded
    /// like every other honor `asyncAfter`: teardown bumps the generation and
    /// this write becomes a no-op.
    func showHonorCaption(_ text: String) {
        softTapCaption = text
        let generation = honorGeneration
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.softTapCaptionSeconds) { [weak self] in
            guard let self, self.honorGeneration == generation else { return }
            self.softTapCaption = nil
        }
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:239-257@534f170

```swift
    func showMarkCaption(mark: WayMark, meters: Double) {
        showHonorCaption("water in \(WayDistance.string(meters: max(0, meters.isFinite ? meters : 0)))")
    }

    /// The stamp office's one line, in that same borrowed slot. Silent
    /// without the route's hours, which is every Way but a Shikoku stage's.
    func showStampCaption(temple: WayMoment, meters: Double) {
        guard let number = temple.templeNumber, let closes = way?.stampHours?.closesMinutes else { return }
        showHonorCaption(WayStampNotice.caption(templeNumber: number, closesMinutes: closes, meters: meters))
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+MarkPins.swift:62-71@534f170

- **This is a pure refactor for soft tap and water:** the same text, the same 20 s (`softTapCaptionSeconds = 20`, `ActiveWalkViewModel+Honor.swift:470@534f170`), and the same generation guard as the pin's two copies (`ActiveWalkViewModel+MarkPins.swift:63-70@7c200bf`).
- The stamp caption reads the **view model's** `way` for the closing time and the moment's own `templeNumber`, not the tracker's office. It's the same Way, so the same value.
- The text is built once, at the event, in `Locale.current` and the unit preference at that moment, then held for 20 s.

**Android:** the slot today carries only the soft tap's metres, `softTapMeters: Long?` (`P/ui/walk/HonorWalkViewModel.kt:196-197@0defff85`), drawn with `R.string.honor_soft_tap_caption` in `WalkStatsSheet.kt:442@0defff85`. U39 must already widen it for "water in …". **If #91 is coming, U39 should widen it to a small sealed caption model now**: `OffWay(meters)`, `Water(meters)`, `Stamp(templeNumber, closesMinutes, meters)`, with the text resolved in the sheet. Then the fold-in adds one case and one string instead of reshaping the slot. The caption comes from Room (A.5). The UI resolves `Stamp` from the persisted temple moment id against the loaded Way (`moment.templeNumber`, `way.stampHours?.closesMinutes`), shows nothing if either is null (iOS's guard), and keeps it for what's left of 20 s from the firing time.

#### A.4.3 The words

```swift
enum WayStampNotice {

    static func caption(templeNumber: Int, closesMinutes: Int, meters: Double, locale: Locale = .current) -> String {
        let distance = WayDistance.string(meters: max(0, meters.isFinite ? meters : 0))
        return "temple \(templeNumber) stamps until \(hour(closesMinutes, locale: locale)) · \(distance)"
    }

    /// The same 17:00 as "5" where the walker's clock is 12-hour and "17"
    /// where it is 24-hour — their own numbers, asked of the locale rather
    /// than assumed. No meridiem marker: "until 5 PM" reads as a timetable,
    /// and this is a line passed on a walk.
    static func hour(_ minutesSinceMidnight: Int, locale: Locale = .current) -> String {
        let hour = minutesSinceMidnight / 60
        let minute = minutesSinceMidnight % 60
        let shown = isTwelveHourClock(locale) ? (hour % 12 == 0 ? 12 : hour % 12) : hour
        return minute == 0 ? "\(shown)" : String(format: "%d:%02d", shown, minute)
    }

    private static func isTwelveHourClock(_ locale: Locale) -> Bool {
        DateFormatter.dateFormat(fromTemplate: "j", options: 0, locale: locale)?.contains("a") ?? false
    }
}
```
> Pilgrim/Scenes/Honor/WayMomentHeader.swift:125-146@534f170

- **The format:** `temple <N> stamps until <hour> · <distance>`. All lowercase, a middle dot with a space either side, no meridiem marker.
  - `<N>` is the plain integer, with no grouping or locale digits.
  - `<hour>` is `5` / `17` / `12` / `5:30` (minutes zero-padded to two digits, hours never padded: 08:00 in 24-hour reads `8`).
  - `<distance>` is `WayDistance.string`: metres under 1 km, then `%.1f km`; feet under 0.1 mi, then `%.1f mi`. In practice always km or mi, since `meters >= 1000`.
- **12-hour or 24-hour:** iOS asks `Locale.current` for the `"j"` skeleton and looks for an `a`. Probed (`jprobe.swift`):
  - 12-hour: `en_US` → `h a`, `zh_TW` → `ah時`, `ko_KR` → `a h시`, and `ja_JP@hours=h12` → `ah時`;
  - 24-hour: `en_GB` → `HH`, `ja_JP` → `H時`, `de_DE` → `HH 'Uhr'`, and `en_US@hours=h23` → `HH`.

  Across all of Foundation's locales, none has a 12-hour pattern without an `a` (`jall.swift`: 0). On iOS, `Locale.current` carries the walker's 24-Hour Time switch, so the switch wins over the region.
- **Android:**
  - The test-facing form takes a `Locale` and checks `android.icu.text.DateTimePatternGenerator.getInstance(locale).getBestPattern("j")` for `'a'` outside quotes. `android.icu` exists from API 24 (min SDK is 28) and is real under Robolectric.
  - The production form must honour the system "Use 24-hour format" switch, as iOS's `Locale.current` honours its own: `android.text.format.DateFormat.is24HourFormat(context)` (A.11).
  - Digits: build the hour with `Int.toString()` and `String.format(Locale.US, "%d:%02d", …)`, never the default locale. Swift's `String(format:)` here uses no locale, so its digits are ASCII.
  - The distance is Android's existing `WayRelation.distance(meters, units)` (`P/ui/honor/WayPlaceCardState.kt:268-292@0defff85`, "iOS `WayDistance`").
  - Put `WayStampNotice` beside `WayStageLine` in `P/ui/honor/WayMomentHeader.kt`, mirroring iOS's file. The string resource is `honor_stamp_caption`: `temple %1$s stamps until %2$s · %3$s`, with all three arguments pre-formatted strings. `Resources.getString` formats in the configuration's locale, so a `%1$d` would print Arabic-Indic digits under an `ar` locale where iOS prints ASCII. The soft tap's resource already passes `toString()` for the same reason (`WalkStatsSheet.kt:442@0defff85`).

### A.5 The state #91 adds to the walk session, and the schema-12 recommendation

#### A.5.1 What iOS keeps, and what Android must persist

iOS persists none of the tracker's state. The engine "Persists nothing" (`HonorEngine.swift:19-21@534f170`), and a killed walk is never resumed. Android revives `:tracker` from Room (`HonorSessionEntity`'s header: "iOS persists none of it and never resumes a walk"), so every field below is an Android addition, as `firedMarks`/`lastMarkSeconds` already are. The Stage 21-1 entity names the deferral:

```kotlin
 * `companionFrac`, which the next fix or tick recomputes. The stage-only
 * water marks (`firedMarks`, `lastMarkSeconds`) wait for Stage 21-2.
```
> app/src/main/java/org/walktalkmeditate/pilgrim/data/honor/HonorSessionEntity.kt:26-27@0defff85

The tracker's mutable state at each sha, and what each piece needs in Room:

| State | `7c200bf` | `534f170` | Persisted on Android as |
|---|---|---|---|
| reached set, queue | yes | unchanged | `honor_moment_states` (today) |
| `firedMarks: Set<String>` (mark ids) | yes | unchanged | new in U35 |
| quiet clock (`TimeInterval?`, engine seconds, nil = first free) | `lastMarkSeconds` | **renamed** `lastNoticeSeconds`; also written by a temple fire | new in U35; **same column**, wider meaning |
| `firedStamps: Set<String>` (temple **moment** ids) | — | **new** | new with #91 |
| the caption the UI shows for 20 s | water only: mark id, metres, firing time (plan KTD) | water **or** temple | U35, plus a **kind** that also says whether the id is a mark or a moment |
| `stamp: StampOffice?`, `fudasho` | — | immutable, derived from the Way, the zone and the clock at construction | nothing (rebuilt on revival) |

So **#91 needs exactly two persisted things beyond `7c200bf`: the fired-stamps set, and a kind on the caption.** The quiet clock needs no new column, only a name that doesn't say "mark". Neither the closing time nor the temple number is persisted: the UI reads both from the loaded Way, as iOS's view model does (`way?.stampHours?.closesMinutes`, `temple.templeNumber`).

#### A.5.2 Recommended design for U35 (works whether or not #91 merges)

Two parts, both additive in `MIGRATION_11_12`, alongside U35's own stage-identity columns:

**(1) The quiet clock as an engine-state column**, written on the existing `updateEngineState` path (`HonorEngineState`, every commit, like `soft_tap_since`):

```
ALTER TABLE `honor_sessions` ADD COLUMN `last_notice_seconds` REAL
```

- Kotlin: `@ColumnInfo(name = "last_notice_seconds") val lastNoticeSeconds: Double? = null` on `HonorSessionEntity` and `HonorEngineState`, and `lastNoticeSeconds: Double?` on `HonorMomentTracker.Snapshot`.
- NULL means the first notice is free (iOS's nil).
- **A pre-#91 build** writes it only when water fires, which is exactly iOS's `lastMarkSeconds`. **A post-#91 build** also writes it when a temple fires, which is exactly `lastNoticeSeconds`. The value means the same thing in both builds; only the writers widen. No reserve is needed.

**(2) One row per notice spoken**, in a new table (the `honor_moment_states` pattern):

```
CREATE TABLE IF NOT EXISTS `honor_notices` (
  `walk_id` INTEGER NOT NULL, `kind` TEXT NOT NULL, `ref_id` TEXT NOT NULL,
  `meters` REAL NOT NULL, `fired_at` INTEGER NOT NULL,
  PRIMARY KEY(`walk_id`, `kind`, `ref_id`),
  FOREIGN KEY(`walk_id`) REFERENCES `walks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )
```

- `kind`: `WATER` (the only value a pre-#91 build writes) or `STAMP` (the fold-in's).
- `ref_id`: the `WayMark.id` for `WATER`, the `WayMoment.id` for `STAMP`.
- `meters`: the event's along-line metres, which feed the caption's distance.
- `fired_at`: wall-clock epoch millis at the firing commit (`Plan.now`), which feeds the caption's remaining 20 s.

Derived from the rows:
- **`firedMarks`** = the `ref_id`s with `kind = WATER`, filtered on `restore` to the Way's on-way water mark ids.
- **`firedStamps`** = the `ref_id`s with `kind = STAMP`, filtered to `fudasho` ids.
- **The caption** = the row with the greatest `fired_at`, shown while `fired_at + 20 000 > now` and the walk is live, so a revival never replays an expired one.
- **The text:** `WATER` → "water in …"; `STAMP` → "temple N stamps until H · …", from the loaded Way, or nothing if the moment has no `templeNumber` or the Way has no `stampHours` (iOS's guard, A.4.2).

How it's written and read:
- **Writes** go in the same commit as the engine state, before any ritual ("Persist before ritual", `HonorSession.kt:82-86@0defff85`): the event arm in `planEvents` records `(kind, refId, meters)`, and `commit` inserts with `OnConflictStrategy.IGNORE`, never `REPLACE`, per the DAO's rule (`HonorDao.kt:58@0defff85`).
- **The enum:** `HonorNoticeKind { WATER, UNKNOWN }` in U35, with a lenient converter mapping any unknown name to `UNKNOWN` (the `Converters` pattern, `P/data/Converters.kt:13-26@0defff85`). Every reader ignores `UNKNOWN` rows. The fold-in adds `STAMP`. A pre-#91 build that ever reads a post-#91 database (only possible on a debug phone) treats temple rows as unknown and ignores them, which is the pre-#91 behaviour.
- **Finalize:** `HonorDao.deleteLiveRows` gains `deleteNotices(walkId)` (`HonorDao.kt:206-212@0defff85` deletes card states, moment states and the session today). Without it the rows outlive the walk's live session until the walk itself is deleted.
- **Size:** at most one row per water mark (≤ 400 marks a stage, `maxMarks`, of which only on-way water count) plus one per temple (≤ 10 a stage in live data). Rows are written at most once per 3,600 engine seconds.

Why this shape:
1. **Zero schema change at the fold-in, with nothing reserved.** #91 adds a value to an enum and a writer to a column that already exists. That holds whether #91 merges before U35 starts, between U35 and the gate, or after the gate (into 2.0.1), and whether or not a device has run schema 12.
2. The caption and both fired sets come from the same rows in the same transaction, so after a kill they can't disagree. The quiet clock rides the engine-state write that already runs on every commit.
3. A cached `:tracker` starts the next walk empty, because rows are keyed by walk id (the second-walk lesson).
4. No `Set<String>` type converter is needed (`Converters.kt` has none today).

#### A.5.3 Fallback: columns only, if U35 keeps the plan's "additive columns on `honor_sessions`"

If U35 prefers columns (plan KTD: "additive columns on `honor_sessions` for the fired marks, the quiet hour's last mark time, the water caption (mark id, metres, firing time)"), the #91-safe set is:

```
ALTER TABLE `honor_sessions` ADD COLUMN `last_notice_seconds` REAL
ALTER TABLE `honor_sessions` ADD COLUMN `fired_mark_ids` TEXT NOT NULL DEFAULT '[]'
ALTER TABLE `honor_sessions` ADD COLUMN `fired_stamp_ids` TEXT NOT NULL DEFAULT '[]'
ALTER TABLE `honor_sessions` ADD COLUMN `notice_kind` TEXT
ALTER TABLE `honor_sessions` ADD COLUMN `notice_ref_id` TEXT
ALTER TABLE `honor_sessions` ADD COLUMN `notice_meters` REAL
ALTER TABLE `honor_sessions` ADD COLUMN `notice_fired_at` INTEGER
```

- `fired_stamp_ids` is the **reserved** column. A pre-#91 build declares it on the entity, writes its default, and never reads it. Room's identity hash and its migration validation need every column declared, including the `DEFAULT`: the entity carries `@ColumnInfo(defaultValue = "'[]'")` on both set columns, or `TableInfo` validation fails after the migration.
- The two id sets are JSON arrays of strings, written with `WayJson`'s rules (sorted, so equal sets compare equal).
- `notice_kind` follows A.5.2's lenient enum, and NULL means no notice yet.
- **The cost:** two new type converters, a reserved column that is dead in a pre-#91 build, and the caption and fired sets kept in step by hand.

Prefer A.5.2.

#### A.5.4 Names that make the fold-in a no-op on the data side

Whichever shape U35 picks, these names cost nothing now and remove a rename later:
- Kotlin `lastNoticeSeconds`, not `lastMarkSeconds`, on the tracker, its snapshot, and the entity. In a comment, cite iOS's `lastMarkSeconds` at `HonorMomentTracker.swift:37@7c200bf` so the parity trail stays.
- The caption type `HonorNotice*`, not `HonorWater*`.
- The golden-trace state key `lastNoticeSeconds` (A.6.2).

### A.6 Golden traces

#### A.6.1 The existing traces don't change (probed)

The corpus today has nine own-walk traces captured at `7c200bf` (`app/src/test/resources/honor/golden/README.md`). The probe (a scratch probe, `golden/`, not committed):
1. copied `capture/{Stubs,Corpus,main}.swift`;
2. added **one** switch arm to `main.swift`, `case .stampAhead: fatalError("an own walk has no stamp hours")`, which the exhaustive `switch event` needs at `534f170`, or the harness doesn't compile;
3. fetched the six engine files at `534f170`;
4. compiled and ran `corpus`, `capture` and `pairs` under `TZ=UTC`;
5. diffed against the repo.

Result: **`corpus/` and `expected/` byte-identical**, `cl-distance-pairs.txt` included. This is the empirical form of A.3.5's last bullet: without `stampHours`, #91 changes nothing the harness can see.

The same holds for U35's water traces, for the same reason, provided the harness doesn't read the private property by its old name (A.6.2).

#### A.6.2 What the fold-in changes in the harness

- `capture.sh`: `PIN=7c200bf` → the merge sha. #91's `Pilgrim/` and `UnitTests/` trees are those of `534f170`, since main's only post-pin commits are PR #90's docs (`docs/app-store/metadata.md`, `docs/screenshots/01_walk_start.png`). The README table's "Pin" and "Captured" rows move with it.
- `main.swift`:
  - The event switch gains a real `.stampAhead` arm: `EventRecord(type: "stampAhead", id: temple.id, …meters…)`, in whatever field U35 adds for `markAhead`'s metres.
  - The `.markAhead` arm stops being `fatalError` once U35 adds water traces.
- **The Mirror read:** if U35's state record reads the tracker's quiet clock with `field(tracker, "lastMarkSeconds")` (`main.swift:123-129` reads private state by label, and `fatalError`s on a missing label), it breaks at #91, where the label is `lastNoticeSeconds`. **Recommendation:** U35 writes the JSONL key as `lastNoticeSeconds` from the start, mapped from the Mirror label `lastMarkSeconds` at `7c200bf`. Then the fold-in changes one label string in `main.swift`, and `expected/*.jsonl` stays byte-identical. Any `firedMarks` read is unaffected. The fold-in adds `firedStamps` to the state record **only for the temple traces**, so the water traces' records keep their bytes. Alternatively, the key is added everywhere and every expected file moves once, mechanically.
- The Android `HonorGoldenTraceTest` reads the same private state by reflection. The field name it reflects on follows the Kotlin name (A.5.4).

#### A.6.3 Temple traces to add

Synthetic only (the corpus rule). Each trace has its own `t0` (`main.swift:333` writes it per trace), so a temple trace sets its `t0` near the window instead of 08:00Z.

- **Zones:** capture under `TZ=UTC` as today. Android's test pins `ZoneId.of("UTC")` through the engine's zone parameter (A.3.2), never the JVM's default.
- **Corpus `Way`s:** carry `stampHours` (`{"closesMinutes": 1020, "opensMinutes": 480}`) and waypoints whose `text` is `"Temple N · …"`. The `way.json` is encoded as `WayStore` does, so the key appears sorted, after `stage` in sort order.

| Proposed trace | What it pins |
|---|---|
| `temple-window-opens-34n` | `t0` = 14:58:00Z, temple 10 about 3 km ahead. Silent on every fix before 15:00:00; fires on the first on-way fix at or after it; never again. Proves the wall clock (not engine seconds) gates it, `>=` at the open, and once per temple. |
| `temple-skips-the-near-one-34n` | Temple 10 at 600 m and temple 11 at 6 km when the window opens: the notice names temple 11 (A.3.4 probe 1), and temple 10 is reached as a waypoint, never announced. |
| `temple-and-water-share-the-hour-34n` | A water mark inside 300 m at the same fix as an eligible temple: the temple fires and the water doesn't (`testTheTempleTakesTheLineAheadOfTheFountain` at engine level). Then a second fountain passed inside the hour stays silent, and a third within 300 m after 3,600 engine seconds speaks. Needs more than an hour of engine time: sparse fixes (every 10–15 s) keep it near 400 fixes. A mid-trace pause shows the hour runs on engine seconds, which hold through a pause. |
| `temple-closes-34n` | Temple 10 fires at 16:10; temple 11, still more than 1 km ahead, is held by the quiet hour through 16:59, and at 17:00 the window has shut, so it never fires that day. Pins `<` at the close, and shows a notice can starve the next temple. |
| `temple-off-way-34n` | The walker leaves the Way inside the window: no notice off the way, and the quiet clock untouched. The first on-way fix after the rejoin fires (probe 7). |
| `temple-way-zone-34n` | The Way's `tzIdentifier` = `"Asia/Tokyo"` with `t0` = 06:00Z (15:00 JST) under `TZ=UTC`: it fires. Proves the Way's zone beats the phone's. Live stages never carry a zone, but the code path exists. |

No temple trace adds `CLLocation.distance` calls (the scan is frac arithmetic), so the D1–D4 bookkeeping and the `Allowances` table are unaffected. Any `reached` events in these traces still go through D1 as usual.

### A.7 Strings

| String (verbatim) | Where and when | Arguments | "they/their"? |
|---|---|---|---|
| `temple %d stamps until %s · %s` (iOS: `"temple \(templeNumber) stamps until \(hour(…)) · \(distance)"`) | The walk screen's caption slot (`softTapCaption`), for 20 s from a `stampAhead` event on a stage whose Way carries hours | the temple number (ASCII integer); the closing hour from `WayStampNotice.hour` (`5`, `17`, `12`, `5:30`); `WayDistance.string(meters:)` (`3.2 km`, `11.3 mi`) | none |
| `off the way · %d m` | unchanged text; now routed through `showHonorCaption` | whole metres, truncated, capped at 999,999 | none |
| `water in %s` | unchanged text; now routed through `showHonorCaption` | `WayDistance.string` | none |

No other user-visible string changes. The README line "a temple ahead says once that its stamp office shuts at five" is iOS's repository README, not app copy, and isn't ported. No accessibility label or trait is added: the caption reaches VoiceOver however the slot already does. Android's `third` value in `WalkStatsSheet` already reads the caption in place of "remaining" (`WalkStatsSheet.kt:442-443@0defff85`).

### A.8 Test inventory (port verbatim)

16 new tests and 1 new assertion. The PR's gate: "1,879 tests passing, 0 failures, from a baseline of 1,863". No fixture changes (`UnitTests/Fixtures/Pilgrimage/` is identical at both shas).

#### A.8.1 `UnitTests/Honor/HonorMomentTrackerTests.swift` (+8): Android `T/domain/honor/HonorMomentTrackerTest.kt`

| Test | Asserts |
|---|---|
| `testARouteThatStatesNoStampHoursNeverSpeaksOfTemples` | no office (`stamp: nil`): no `stampAhead`, whatever the hour |
| `testTheNoticeOpensTwoHoursBeforeClosingAndShutsWithTheOffice` | 14:59 Tokyo silent; 15:00 fires `temple-10`; 17:00 silent |
| `testATempleInsideAKilometreHasStoppedBeingADecision` | 999 m short: silent; 1,001 m short: fires, with `meters` ≈ 1001 (±1) |
| `testEachTempleSpeaksOnce` | fires `temple-10`; 60 s later silent; at 5,400 s, progress 0.5, fires `temple-11` |
| `testATempleAlreadyBehindIsNeverAnnounced` | temple at 0.3, progress 0.6: silent |
| `testASealThatIsNotANumberedTempleStaysSilent` | a `seal` waypoint whose text is "Pilgrim office · …": silent |
| `testAWalkerOffTheWayIsToldNothing` | `isOnWay: false`: silent |
| `testTheTempleTakesTheLineAheadOfTheFountain` | an eligible temple and fountain on one fix: `stampAhead` only, no `markAhead` |

```swift
    /// 10 km straight east, so a frac is a kilometre: the stamp rule works in
    /// kilometres where the water rule works in metres.
    private var longGeometry: WayGeometry {
        WayGeometry(route: (0...10).map { i in
            WayPoint(lat: 0, lon: Double(i) * 0.00898, alt: nil, t: Double(i) * 600)
        })
    }

    private static let tokyo = TimeZone(identifier: "Asia/Tokyo")!

    /// A fixed wall clock in the temple's own time zone.
    private static func tokyoClock(_ hour: Int, _ minute: Int) -> Date {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = tokyo
        return calendar.date(from: DateComponents(year: 2026, month: 4, day: 12, hour: hour, minute: minute))!
    }

    private func office(_ hour: Int, _ minute: Int) -> HonorMomentTracker.StampOffice {
        HonorMomentTracker.StampOffice(closesMinutes: 17 * 60, timeZone: Self.tokyo,
                                       now: { Self.tokyoClock(hour, minute) })
    }

    /// A numbered fudasho as the dataset writes one: the number lives in the
    /// composed `text`, the romanized name in `label`.
    private func temple(_ number: Int, frac: Double) -> WayMoment {
        var moment = WayMoment(id: "temple-\(number)", frac: frac,
                               at: WayCoordinate(lat: 0, lon: frac * 10_000 / 111_320),
                               kind: .waypoint(label: "Kirihata-ji", icon: "seal"))
        moment.text = "Temple \(number) · Koyasan Shingon · stamp available (¥500)"
        return moment
    }

    private func stampTracker(_ moments: [WayMoment], marks: [WayMark] = [],
                              at clock: (hour: Int, minute: Int)? = (15, 30)) -> HonorMomentTracker {
        HonorMomentTracker(moments: moments, marks: marks, geometry: longGeometry, voicesEnabled: false,
                           stamp: clock.map { office($0.hour, $0.minute) })
    }

    private func stampAhead(_ actions: [HonorMomentTracker.Action]) -> [String] {
        actions.compactMap { if case .stampAhead(let temple, _) = $0 { return temple.id } else { return nil } }
    }

    /// Every route but Shikoku's. Nothing, ever — whatever the hour.
    func testARouteThatStatesNoStampHoursNeverSpeaksOfTemples() {
        var t = stampTracker([temple(10, frac: 0.5)], at: nil)
        XCTAssertEqual(stampAhead(t.update(location: coord(1000), progressFrac: 0.1, gates: .init(),
                                           isStationary: false, activeSeconds: 0, isOnWay: true)), [])
    }

    func testTheNoticeOpensTwoHoursBeforeClosingAndShutsWithTheOffice() {
        // 14:59: the afternoon is still young and the notice is noise.
        var early = stampTracker([temple(10, frac: 0.5)], at: (14, 59))
        XCTAssertEqual(stampAhead(early.update(location: coord(1000), progressFrac: 0.1, gates: .init(),
                                               isStationary: false, activeSeconds: 0, isOnWay: true)), [])
        // 15:00 exactly: two hours to closing.
        var onTheHour = stampTracker([temple(10, frac: 0.5)], at: (15, 0))
        XCTAssertEqual(stampAhead(onTheHour.update(location: coord(1000), progressFrac: 0.1, gates: .init(),
                                                   isStationary: false, activeSeconds: 0, isOnWay: true)),
                       ["temple-10"])
        // 17:00: there is no stamp left to be had, and a walker who has lost
        // it does not need telling.
        var closed = stampTracker([temple(10, frac: 0.5)], at: (17, 0))
        XCTAssertEqual(stampAhead(closed.update(location: coord(1000), progressFrac: 0.1, gates: .init(),
                                                isStationary: false, activeSeconds: 0, isOnWay: true)), [])
    }

    func testATempleInsideAKilometreHasStoppedBeingADecision() {
        // Fracs are taken off the line's true length, so the two cases sit a
        // metre either side of the kilometre rather than near it.
        let total = longGeometry.totalMeters
        var near = stampTracker([temple(10, frac: 0.5)])
        XCTAssertEqual(stampAhead(near.update(location: coord(4000), progressFrac: 0.5 - 999 / total,
                                              gates: .init(), isStationary: false,
                                              activeSeconds: 0, isOnWay: true)), [],
                       "999 m short: the walker can see it and is already arriving")

        var far = stampTracker([temple(10, frac: 0.5)])
        let hit = far.update(location: coord(4000), progressFrac: 0.5 - 1001 / total, gates: .init(),
                             isStationary: false, activeSeconds: 0, isOnWay: true)
        XCTAssertEqual(stampAhead(hit), ["temple-10"])
        guard case .stampAhead(_, let meters) = hit.first else { return XCTFail("action") }
        XCTAssertEqual(meters, 1001, accuracy: 1)
    }

    func testEachTempleSpeaksOnce() {
        var t = stampTracker([temple(10, frac: 0.5), temple(11, frac: 0.9)])
        XCTAssertEqual(stampAhead(t.update(location: coord(1000), progressFrac: 0.1, gates: .init(),
                                           isStationary: false, activeSeconds: 0, isOnWay: true)),
                       ["temple-10"])
        // Still 3 km ahead a minute later: inside the quiet hour, and spoken
        // for in any case.
        XCTAssertEqual(stampAhead(t.update(location: coord(2000), progressFrac: 0.2, gates: .init(),
                                           isStationary: false, activeSeconds: 60, isOnWay: true)), [])
        // An hour and a half of walking on, temple 10 has had its say and it
        // is temple 11 — 4 km ahead — that speaks.
        XCTAssertEqual(stampAhead(t.update(location: coord(5000), progressFrac: 0.5, gates: .init(),
                                           isStationary: false, activeSeconds: 5400, isOnWay: true)),
                       ["temple-11"])
    }

    func testATempleAlreadyBehindIsNeverAnnounced() {
        var t = stampTracker([temple(10, frac: 0.3)])
        XCTAssertEqual(stampAhead(t.update(location: coord(6000), progressFrac: 0.6, gates: .init(),
                                           isStationary: false, activeSeconds: 0, isOnWay: true)), [],
                       "a temple you have walked past cannot still be stamped today")
    }

    /// A seal means a stamp is available, which is equally true of Camino
    /// pilgrim offices and Kumano shrines. Only the numbered fudasho keep an
    /// office that shuts, and only the dataset's own "Temple N" says so.
    func testASealThatIsNotANumberedTempleStaysSilent() {
        var pilgrimOffice = WayMoment(id: "wp-sjpp-pilgrim-office", frac: 0.5,
                                      at: WayCoordinate(lat: 0, lon: 5000 / 111_320),
                                      kind: .waypoint(label: "Pilgrim Welcome Office", icon: "seal"))
        pilgrimOffice.text = "Pilgrim office · 172 m · stamp available"
        var t = stampTracker([pilgrimOffice])
        XCTAssertEqual(stampAhead(t.update(location: coord(1000), progressFrac: 0.1, gates: .init(),
                                           isStationary: false, activeSeconds: 0, isOnWay: true)), [])
    }

    func testAWalkerOffTheWayIsToldNothing() {
        var t = stampTracker([temple(10, frac: 0.5)])
        XCTAssertEqual(stampAhead(t.update(location: coord(1000), progressFrac: 0.1, gates: .init(),
                                           isStationary: false, activeSeconds: 0, isOnWay: false)), [],
                       "off the way the distance ahead would be a guess")
    }

    /// One caption line, one notice: the temple takes it, and the fountain
    /// keeps the quiet hour it would have started anyway.
    func testTheTempleTakesTheLineAheadOfTheFountain() {
        let fountain = WayMark(id: "fuente", kind: .water, name: "Fuente",
                               at: WayCoordinate(lat: 0, lon: 1200 / 111_320), frac: 0.12, offLineMeters: 10)
        var t = stampTracker([temple(10, frac: 0.5)], marks: [fountain])
        let actions = t.update(location: coord(1000), progressFrac: 0.1, gates: .init(),
                               isStationary: false, activeSeconds: 0, isOnWay: true)
        XCTAssertEqual(stampAhead(actions), ["temple-10"])
        XCTAssertEqual(markAhead(actions), [], "the fountain waits out the quiet hour the temple started")
    }
```
> UnitTests/Honor/HonorMomentTrackerTests.swift:217-354@534f170

Porting notes:
- `coord(_:)` and `markAhead(_:)` are the file's existing helpers (`HonorMomentTrackerTests.swift:11-13,147-149@534f170`). U35 ports `markAhead` with the water tests.
- The clock is a constant closure. On Android, use `StampOffice(closesMinutes = 17 * 60, zone = ZoneId.of("Asia/Tokyo"), clock = { tokyoMillis(h, m) })`, where `tokyoMillis` builds `LocalDateTime.of(2026, 4, 12, h, m).atZone(tokyo)`.
- In `testEachTempleSpeaksOnce`, the third update stands at `temple-10`'s own `at`, so it also yields `.reached(temple-10)`. The helper filters it out; the Kotlin helper must filter by type the same way.
- `hit.first` in the 1 km test is the stamp action, because `coord(4000)` is 1 km from the temple's `at` (outside the 60 m radius). Keep the coordinates as they are.

#### A.8.2 `UnitTests/Honor/HonorWayRenderingTests.swift` (+2): Android `T/ui/honor/WayMomentHeaderTest.kt` (or wherever U38 tests `WayStageLine`)

```swift
    /// The line a walker on the henro actually reads, in their own clock and
    /// their own unit.
    func testTheStampNoticeSpeaksTheWalkersClockAndUnit() {
        UserPreferences.distanceMeasurementType.value = .kilometers
        defer { UserPreferences.distanceMeasurementType.delete() }
        XCTAssertEqual(WayStampNotice.caption(templeNumber: 10, closesMinutes: 17 * 60, meters: 3200,
                                              locale: Locale(identifier: "en_US")),
                       "temple 10 stamps until 5 · 3.2 km")
        XCTAssertEqual(WayStampNotice.caption(templeNumber: 10, closesMinutes: 17 * 60, meters: 3200,
                                              locale: Locale(identifier: "ja_JP")),
                       "temple 10 stamps until 17 · 3.2 km")
        UserPreferences.distanceMeasurementType.value = .miles
        XCTAssertEqual(WayStampNotice.caption(templeNumber: 88, closesMinutes: 17 * 60, meters: 18_244,
                                              locale: Locale(identifier: "en_US")),
                       "temple 88 stamps until 5 · 11.3 mi")
    }

    /// The hour comes from the data, never a literal: the same 17:00 reads
    /// "5" where the clock is 12-hour and "17" where it is 24-hour, and an
    /// office that shut on the half hour would have to say so.
    func testTheClosingHourFollowsTheLocalesOwnClock() {
        let twelve = Locale(identifier: "en_US")
        for twentyFour in [Locale(identifier: "ja_JP"), Locale(identifier: "en_GB")] {
            XCTAssertEqual(WayStampNotice.hour(17 * 60, locale: twentyFour), "17", "\(twentyFour.identifier)")
            XCTAssertEqual(WayStampNotice.hour(8 * 60, locale: twentyFour), "8", "\(twentyFour.identifier)")
        }
        XCTAssertEqual(WayStampNotice.hour(17 * 60, locale: twelve), "5")
        XCTAssertEqual(WayStampNotice.hour(8 * 60, locale: twelve), "8")
        XCTAssertEqual(WayStampNotice.hour(12 * 60, locale: twelve), "12", "noon is not zero")
        XCTAssertEqual(WayStampNotice.hour(17 * 60 + 30, locale: twelve), "5:30")
    }
```
> UnitTests/Honor/HonorWayRenderingTests.swift:143-173@534f170

Porting notes:
- Android passes `UnitSystem` explicitly instead of setting a preference.
- The 12-hour check takes the `Locale` (ICU `"j"`), so these run under Robolectric with `android.icu`.
- Add one Android test for the production path: `is24HourFormat` true gives "17" and false gives "5", via a fake or `Settings.System.TIME_12_24` under Robolectric.

#### A.8.3 `UnitTests/Honor/PilgrimageStageWalkTests.swift` (+2): Android `T/ui/walk/HonorWalkViewModelTest.kt` (stages)

```swift
    /// The stamp office borrows the same line and adds nothing to the screen.
    func testTheStampNoticeBorrowsTheCaptionLineAndNothingElse() {
        var temple = WayMoment(id: "temple-10", frac: 0.9, at: WayCoordinate(lat: 0, lon: 900 / 111_320),
                               kind: .waypoint(label: "Kirihata-ji", icon: "seal"))
        temple.text = "Temple 10 · Koyasan Shingon · stamp available (¥500)"
        var way = stageWay()
        way.stampHours = WayStampHours(opensMinutes: 8 * 60, closesMinutes: 17 * 60)
        let vm = honorWalk(way: way)
        vm.builder.setStatus(.ready)
        vm.startRecording()

        vm.handleHonorEvent(.stampAhead(temple: temple, meters: 3200))

        XCTAssertEqual(vm.softTapCaption,
                       WayStampNotice.caption(templeNumber: 10, closesMinutes: 17 * 60, meters: 3200))
        XCTAssertTrue(vm.honorCards.isEmpty, "a notice is never a card")
    }

    /// Belt and braces behind the tracker's own gate: a Way with no hours
    /// can put nothing on the line even if an event somehow reached it.
    func testAWayWithoutStampHoursPutsNothingOnTheLine() {
        var temple = WayMoment(id: "temple-10", frac: 0.9, at: WayCoordinate(lat: 0, lon: 900 / 111_320),
                               kind: .waypoint(label: "Kirihata-ji", icon: "seal"))
        temple.text = "Temple 10 · Koyasan Shingon · stamp available (¥500)"
        let vm = honorWalk(way: stageWay())
        vm.builder.setStatus(.ready)
        vm.startRecording()

        vm.handleHonorEvent(.stampAhead(temple: temple, meters: 3200))

        XCTAssertNil(vm.softTapCaption)
    }
```
> UnitTests/Honor/PilgrimageStageWalkTests.swift:369-400@534f170

Porting notes, for the process split:
- On Android the event is handled in `:tracker` and the caption read from Room. The port inserts a `STAMP` notice row (`ref_id = "temple-10"`, `meters = 3200`, `fired_at` = now) and asserts the VM's caption model.
- Because Android resolves the temple by id against the loaded Way (A.4.2), the port **adds the temple moment to `stageWay()`'s moments**. iOS's VM reads the event's own moment, which isn't in the Way. That is a test-shape difference, not a behaviour one: in the app, the tracker's temples are always the Way's own moments.
- A session-side test asserts a `StampAhead` event commits its row before the water haptic runs (persist before ritual), and that the haptic is the water one.

#### A.8.4 `UnitTests/Honor/PilgrimageWayImporterTests.swift` (+4 and one assertion): Android `T/data/honor/pilgrimage/PilgrimageWayImporterTest.kt`

The assertion added to the existing `testDecodesTheRouteFile`:

```swift
        XCTAssertEqual(route.stages[1].hours, WayStageHours(min: 5, max: 7))
        XCTAssertNil(route.stampHours, "the Camino declares no stamp office hours")
```
> UnitTests/Honor/PilgrimageWayImporterTests.swift:290-291@534f170

```swift
    /// Shikoku's `route.json` writes `"08:00"`; the phone keeps minutes.
    func testARoutesStampHoursAreReadAsMinutesSinceMidnight() throws {
        let route = try PilgrimageWayImporter.route(from: routeFile(stampHours: """
            "stampHours": { "opens": "08:00", "closes": "17:00" },
            """))
        XCTAssertEqual(route.stampHours, WayStampHours(opensMinutes: 480, closesMinutes: 1020))
    }

    /// A walker told the wrong closing time is worse off than one told
    /// nothing, so an hour this build cannot read is dropped whole — and the
    /// route stays walkable regardless, since the stamp notice is the only
    /// thing that needed it.
    func testAnUnreadableStampHourLeavesTheRouteWalkableAndSilent() throws {
        let unreadable = [
            #""stampHours": { "opens": "08:00", "closes": "5pm" },"#,
            #""stampHours": { "opens": "8:00", "closes": "17:00" },"#,
            #""stampHours": { "opens": "08:00", "closes": "24:00" },"#,
            #""stampHours": { "opens": "08:00", "closes": "17:60" },"#,
            #""stampHours": { "opens": "08:00", "closes": "" },"#
        ]
        for block in unreadable {
            let route = try PilgrimageWayImporter.route(from: routeFile(stampHours: block))
            XCTAssertNil(route.stampHours, block)
            XCTAssertEqual(route.stageCount, 2, "the route is still walkable: \(block)")
        }
    }

    /// The route file and the stage files come down separately and only the
    /// package manager holds both, so the hours are copied onto each stage
    /// Way — the walk reads one Way and never the route.
    func testAStageCarriesItsRoutesStampHoursAndSurvivesTheStoresEncoding() throws {
        let hours = WayStampHours(opensMinutes: 480, closesMinutes: 1020)
        let way = try PilgrimageWayImporter.way(from: PilgrimageFixtures.data("stage-00.json"),
                                                routeId: "camino-frances", stageIndex: 0, stampHours: hours)
        XCTAssertEqual(way.stampHours, hours)

        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        XCTAssertEqual(try decoder.decode(Way.self, from: try encoder.encode(way)), way)
    }

    /// Every route but Shikoku's, and every `way.json` already on a phone
    /// from a build that had no such field.
    func testAStageWithoutStampHoursCarriesNoneAndAnOlderWayJsonStillDecodes() throws {
        let plain = try PilgrimageWayImporter.way(from: PilgrimageFixtures.data("stage-00.json"),
                                                  routeId: "camino-frances", stageIndex: 0)
        XCTAssertNil(plain.stampHours, "a route that declares no hours hands its stages none")

        let stamped = try PilgrimageWayImporter.way(from: PilgrimageFixtures.data("stage-00.json"),
                                                    routeId: "camino-frances", stageIndex: 0,
                                                    stampHours: WayStampHours(opensMinutes: 480, closesMinutes: 1020))
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: try encoder.encode(stamped)) as? [String: Any])
        XCTAssertNotNil(object["stampHours"], "written when the route has them")
        object.removeValue(forKey: "stampHours")

        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let older = try decoder.decode(Way.self, from: try JSONSerialization.data(withJSONObject: object))
        XCTAssertNil(older.stampHours, "a way.json written before the field existed still decodes")
    }

    /// The fixture route with a `stampHours` block spliced in where the
    /// dataset writes it: between the summary and the stages.
    private func routeFile(stampHours block: String) throws -> Data {
        let base = String(data: try PilgrimageFixtures.data("route.json"), encoding: .utf8)!
        let json = base.replacingOccurrences(of: "\"cover\": \"cover.jpg\",",
                                             with: "\(block)\n  \"cover\": \"cover.jpg\",")
        XCTAssertNotEqual(json, base)
        return Data(json.utf8)
    }
```
> UnitTests/Honor/PilgrimageWayImporterTests.swift:294-367@534f170

Porting notes:
- `routeFile` splices before the fixture's `"cover": "cover.jpg",` line (`UnitTests/Fixtures/Pilgrimage/route.json:11@7c200bf`). Port it as a string replace on the verbatim fixture, asserting the result differs, as iOS does.
- The two encode/decode tests go through `WayJson` (sorted keys and whole-second dates are already its rules).
- **Add as Android-only edge tests (they pin Swift's runtime, A.1.2):**
  - `"+8:00"` reads as 480;
  - `"٠٨:٠٠"` drops the pair;
  - a structurally wrong `stampHours` (`{"opens":"08:00"}`, numbers, a bare string, a null member) makes the route `NotWalkable`;
  - `"stampHours": null` decodes as no hours.

  Each states its probe as the source.

### A.9 Corrections to the Android plan

1. **KTD, "Shaped for PR #91 if it's coming"** says #91 "replaces water's quiet-hour clock with one shared by water and temple notices and adds a fired-stamps set", so "the quiet-hour column is designed as the shared notice clock and a fired-stamps column is reserved".
   - **Missed:** the persisted **caption** also needs a **kind**. The plan's caption is "(mark id, metres, firing time)". A temple caption's id is a *moment* id, and its text needs the temple's number and the Way's closing time. Without a kind, a revived UI can't tell "water in 3.2 km" from "temple 10 stamps until 5 · 3.2 km".
   - **Imprecise:** #91 doesn't replace the clock; it **renames** `lastMarkSeconds` → `lastNoticeSeconds`, with the same type, nil rule and comparison, and adds one writer.
   - **Fix:** adopt A.5.2, `last_notice_seconds REAL` plus a `honor_notices` table, which needs no reserved column. Or, if U35 keeps columns, A.5.3's set, which adds `notice_kind` to the plan's list.
2. **KTD, "Room schema 12: additive columns on `honor_sessions` for the fired marks, the quiet hour's last mark time, the water caption …"**: name the quiet-hour field `last_notice_seconds` / `lastNoticeSeconds` whether or not #91 merges (A.5.4). It is exact for water alone and saves a rename.
3. **Deferred to Follow-Up Work, "recaptures the water golden traces":** the recapture changes no existing expected file. The nine own-walk traces are byte-identical under iOS's engine at `534f170` (probed, A.6.1), and the water traces are by the same argument. The fold-in's golden work is:
   - one `.stampAhead` arm in `main.swift` (it doesn't compile without it);
   - the pin bump;
   - the Mirror label (A.6.2);
   - **new temple traces** (A.6.3).

   Reword: "re-pins the capture, confirms the existing traces reproduce byte for byte, and adds temple traces".
4. **Deferred to Follow-Up Work, the unit list "(U31, U34, U35, U38, U39)":** U38's touch is only the `WayStampNotice` helper living in `WayMomentHeader.kt`. #91 changes nothing on the overview or the morning card. The list also omits the shared domain model: `P/domain/honor/Way.kt` (`WayStampHours`, `Way.stampHours`, `WayMoment.templeNumber`), `WayCodecTest`, and `HonorTuning.kt`. Assign them in A.10.
5. **U31 test scenario "live-data keys (`stampHours`, `schemaVersion`) are ignored":** true before the fold-in. After it, `stampHours` is read and validated (A.1.2), and only `schemaVersion` stays ignored. The fold-in rewrites this scenario into the ported importer tests (A.8.4).
6. **Context, "Live `route.json` carries keys added after the pin (`stampHours`, …)":** add that the data is released (open-pilgrimages v1.12.0, 2026-09-22) on all four Shikoku routes. The PR's own "Ordering" note ("`stampHours` is not on open-pilgrimages `main` yet") is stale (PR91-D3). The notice is live the day #91's code ships.
7. **U35 test file "`HonorMomentTrackerTest.kt` (iOS's +6)":** with #91 it is +6 water and +8 stamp. `HonorSessionTest` also needs the revival of `firedStamps`, and a cached `:tracker`'s second walk starting with an empty fired-stamps set.
8. **U39, "The water caption: 'water in <distance>' in the soft-tap slot … read from Room":** shape the slot as a caption model now (A.4.2), so the fold-in adds a case rather than reshaping `softTapMeters: Long?`.
9. **U35/U36, the live-row delete:** whatever holds notices must join `HonorDao.deleteLiveRows` (A.5.2). The plan's finalize ordering lists the live-row delete but not new tables.

### A.10 The fold-in unit, as a checklist per Android unit

Each item is either **already done** if U35 adopted A.5 (marked ◦), or **fold-in work** (marked •).

**U31: importer, models, fixtures**
- • `PilgrimageModels.kt`: the route wire model gains `stampHours: StampHoursWire? = null` (`opens: String`, `closes: String`, both non-null). `PilgrimageRoute` gains `stampHours: WayStampHours?` between `summary` and `stages`.
- • `PilgrimageWayImporter.kt`: `stampHours(raw)` and `minutesSinceMidnight(clock)`, exactly as A.1.2: split on `:` keeping empties, two parts of length 2, each `^[+-]?[0-9]{1,2}$`, then the 0–23 and 0–59 ranges; drop the pair whole on any failure. The Json config stays strict (`isLenient = false`, `coerceInputValues = false`) so a mis-shaped `stampHours` fails the route as on iOS.
- • `way(from:routeId:stageIndex:stampHours:)` takes `stampHours: WayStampHours? = null` and sets it on the built Way, last.
- • `P/domain/honor/Way.kt`: `WayStampHours(opensMinutes: Int, closesMinutes: Int)`; `Way.stampHours: WayStampHours? = null` as the last property; `WayMoment.templeNumber` (A.3.3, ASCII-only digits).
- • `T/domain/honor/WayCodecTest.kt`: the stamp round-trip, and an older `way.json` without the key.
- • Tests: A.8.4's four tests and one assertion, plus the Android edge tests; and `templeNumber`'s cases (A.3.3 probe) in `WayMomentTest.kt`.
- ◦ Fixtures: unchanged.

**U34: package manager**
- • `StagePlan` gains `stampHours`, from the route parsed once, passed to every stage's import.
- • An Android assertion in the download test: a route with hours writes them into every staged `way.json` (dated addition, A.11).
- ◦ Budget, guard, commit, rollback, the marker: unchanged.

**U35: tracker, engine, session, Room**
- • `HonorTuning.kt`: `STAMP_NOTICE_MINUTES = 120`, `STAMP_MIN_AHEAD_METERS = 1000.0`.
- • `HonorMomentTracker.kt`:
  - `StampOffice(closesMinutes, zone, clock)` with `isClosingSoon` (A.3.2);
  - `stamp: StampOffice? = null` in the constructor;
  - `fudasho` = sorted moments with `templeNumber != null`, empty when `stamp == null`;
  - `firedStamps`;
  - `stampAhead` (A.3.4) called **before** `waterAhead` in `update` (A.3.5);
  - `Action.StampAhead`;
  - `Snapshot.firedStamps`, filtered on `restore`.
- ◦ The quiet clock is already `lastNoticeSeconds`, if U35 followed A.5.4. • Otherwise rename it everywhere, keeping its semantics.
- • `HonorEngine.kt`: builds the office from `way.stampHours`, `way.tzIdentifier` (try/fallback), the fallback zone parameter (default `ZoneId.systemDefault()`) and `clock`; `HonorEngineEvent.StampAhead`; the `toEvent()` arm.
- • `HonorSession.kt` `planEvents`:
  - `StampAhead` records a `STAMP` notice `(refId = temple.id, meters)` and plans `Ritual.WaterHaptic`, the same ritual `MarkAhead` plans (iOS fires `.honorWaterAhead` for both);
  - persist before ritual, as for water.
- ◦ Room: if A.5.2 was adopted, no migration; • add `STAMP` to `HonorNoticeKind` and its converter.
- • If A.5.3 was adopted instead: start writing `fired_stamp_ids` and `notice_kind = STAMP`.
- • If neither was adopted (schema 12 shipped without them): **schema 13** with `MIGRATION_12_13`. This is the outcome the KTD exists to avoid.
- • Tests: A.8.1's eight, ported verbatim. Session tests: a stamp notice persists before its haptic; a revival keeps `firedStamps` and the shared quiet clock; a cached `:tracker`'s second walk starts with no fired stamps; a non-stage Way never builds an office.
- • Golden harness: A.6.2 and A.6.3.

**U38: overview and header**
- • `P/ui/honor/WayMomentHeader.kt`: `WayStampNotice.caption(templeNumber, closesMinutes, meters, units, twelveHour)` and `hour(minutes, twelveHour)`, plus `isTwelveHourClock(locale)` via ICU `"j"` for the tests (A.4.3). The `honor_stamp_caption` string.
- • Tests: A.8.2's two, plus the production `is24HourFormat` case.
- ◦ Overview, morning card, "the day": unchanged by #91.

**U39: on the walk**
- ◦ The caption slot is a model (A.4.2), if U39 shaped it so. • Otherwise reshape `softTapMeters` into it.
- • `HonorWalkViewModel`: maps the latest notice row of kind `STAMP` to `Stamp(templeNumber, closesMinutes, meters)` from the loaded Way, or to nothing if either is missing. It shows for the rest of its 20 s and never replays after a revival.
- • `WalkStatsSheet.kt`: draws `Stamp` with `honor_stamp_caption`, with the hour from `DateFormat.is24HourFormat(context)` and the distance from `WayRelation.distance(meters, units)`.
- • Tests: A.8.3's two (the temple added to the stage Way's moments); a stamp row in Room shows its caption after a UI restart for its remaining life; an expired one shows nothing.

**Cross-cutting**
- • Re-diff `7c200bf..<merge sha>` at the fold-in's start. If #91 changed after `534f170`, this annex is re-read against the new head first.
- • Re-pin, per R2 ("fold into Phase 21 … then re-pin"): `CLAUDE.md`'s parity line, the golden `capture.sh` `PIN`, and every spec that cites `@7c200bf` for a file #91 touched move to the merge sha, as Phase 20's two fold-in hops did. `main` at the merge sha also carries PR #90 (docs only), which feeds R25.

### A.11 Android additions to record at the gate

1. **The temple state survives a `:tracker` death:** fired stamps, the shared quiet clock, and the last notice's kind, id, metres and time are in Room. iOS keeps them in memory and never resumes. This is the same addition as water's `firedMarks`/`lastMarkSeconds`.
2. **The 24-hour switch:** iOS reads `Locale.current`, which carries the 24-Hour Time setting. Android reads `android.text.format.DateFormat.is24HourFormat(context)`, which carries "Use 24-hour format". This is a platform equivalent. The tests' locale form uses ICU's `"j"` pattern, as iOS's does.
3. **The caption is rendered when drawn, not when fired:** iOS builds the string once at the event and holds it for 20 s. Android's UI composes it from the persisted row while it shows, so a units or 24-hour change inside those 20 s shows at once. It can't be observed in practice; recorded for completeness.
4. **The office's zone is re-read on a revival:** iOS captures it once per walk. A revived `:tracker` rebuilds the engine and takes the zone then. Only a phone whose zone changed mid-walk and whose `:tracker` died could tell.
5. **The engine takes its fallback zone as a parameter** (default `ZoneId.systemDefault()`), so tests and golden traces pin it without a process-wide `TZ`. This is a test seam with no behaviour change.
6. **The download test asserts the hours land in every staged stage.** iOS has no such test (A.2).
7. **Notice kinds are read leniently** (`UNKNOWN` ignored), so a debug phone that ran a later build reads safely. Room only.

### A.12 iOS defect candidates in #91 (for an upstream review comment)

Android matches each as shipped if #91 merges unchanged. None is filed yet: no `pilgrim-ios` issue mentions stamps or temples. Because #91 is still open, the natural channel is **a review comment on #91**, before it merges, rather than issues after.

**PR91-D1. An installed route stays silent after the app update, until the next dataset release and an Update.** *Medium.*
- Evidence:
  - The hours reach a stage only when `download` stages it (A.2). `way.json`s written by a pre-#91 build carry no `stampHours` (A.1.3).
  - `hasUpdate` compares only `release.txt` with the catalog's release (`PilgrimageCatalogView.swift:31-38@534f170`).
  - The installed raw `route.json` already holds `stampHours` for any install from v1.12.0 on (`PilgrimagePackageManager.swift:423-424@534f170`), but nothing reads it back.
  - The PR states "Persistence needed nothing".
- What a walker sees: someone who downloaded a Shikoku leg under iOS 2.0.0 (`7c200bf`, which builds Ways without the field) updates the app and never hears the notice, even if the download came from v1.12.0, whose `route.json` already carries the hours. No "update ready" appears to fix it until open-pilgrimages cuts v1.13.0. Remove plus Download works, but nothing tells them to do it.
- For Android: only if Android 2.0.0 ships before the fold-in and the fold-in rides 2.0.1. Otherwise there is no prior install to upgrade.
- Possible fixes: re-stamp stage Ways from the installed `route.json` once at launch; or count a build-side format change as an update.

**PR91-D2. A mis-shaped `stampHours` makes the whole route unwalkable, not silent.** *Low today, medium if the dataset's shape ever moves.*
- Evidence: the `try? JSONDecoder().decode(RouteFile.self, …)` at `PilgrimageWayImporter.swift:272@534f170` makes any present-but-mis-shaped Optional fail the whole decode. Probe: a missing `closes`, numbers, a bare string, or a null member each give `.notWalkable`.
- The PR and its test say "the route stays walkable regardless". That holds only for well-typed strings.
- The dataset's own build comment anticipates "a season's two windows". If a future dataset release ships `stampHours` in a new shape (an array of windows, say), every iOS build with #91 refuses all four Shikoku routes ("this route isn't walkable yet"), including Update of an installed one.
- Fix: decode the member leniently (`try? container.decodeIfPresent(StampHours.self, forKey: .stampHours)` in a custom `init(from:)`).

**PR91-D3. The PR description's ordering note is stale.** *Informational.*
- "`stampHours` is not on open-pilgrimages `main` yet … Until then this is inert by construction".
- In fact open-pilgrimages #19 merged 2026-09-22 20:37 UTC and v1.12.0 carries the hours on all four Shikoku routes, so the notice is live on merge for every new download.
- No code change; worth correcting so reviewers judge the PR as live.

**PR91-D4. The haptic fires without a caption when the view model's guard fails.** *Very low; unreachable today.*
- `fireHonorHaptic(.honorWaterAhead)` runs after `showStampCaption` even when it returned early (`ActiveWalkViewModel+Honor.swift:208-213@534f170`; guard at `ActiveWalkViewModel+MarkPins.swift:69@534f170`).
- `testAWayWithoutStampHoursPutsNothingOnTheLine` checks only the caption. The tracker can't emit the event in that state, so a walker can't feel it.

**PR91-D5. The window ignores `opens` and never wraps midnight.** *Very low; unreachable with the live 17:00.*
- `isClosingSoon` is `[closes − 120, closes)` on the local minute of day (`HonorMomentTracker.swift:32-39@534f170`). A close before 02:00 opens a window that starts "before midnight" and never matches the evening before (probe 3). An office opening after `closes − 120` would be "closing soon" before it opens. `opensMinutes` is otherwise unused.
- `templeNumber` also accepts "Temple 0" (probe), which would read "temple 0 stamps until …".

**PR91-D6. The shared quiet hour can swallow a temple's notice.** *Low; a consequence of the design, worth naming in review.*
- Water notices in the hour before 15:00 hold the temple: water at 14:30 keeps the temple silent until 15:30 of engine time. If by then the walker is within 1 km, that temple never speaks (probe 2).
- Inside the window, a first temple at 16:10 holds the next until after 17:00, so it never speaks that day.
- The PR intends "one notice an hour" and "the temple goes first", but the temple only goes first within a single fix, not across the hour. A walker approaching their last temple of the day can miss the one notice the feature exists for, because of a fountain.

**Not a defect, but say it in review:** the nearest temple inside 1 km is passed over for the next one. At 15:00, 500 m from temple 10 with temple 11 8 km on, the line is "temple 11 stamps until 5 · 8.0 km" (probe 1). The doc comment intends it ("The nearest stamp temple still far enough ahead to be a choice"). A walker may read it as the app skipping temple 10.

**Checked and refuted:** the 12-hour detection. No Foundation locale has a 12-hour `"j"` pattern without an `a` (`jall.swift`: 0 of all available identifiers), and the `@hours=h23`/`h12` overrides flip it as intended.

### A.13 PR status and merge outlook

| | |
|---|---|
| State | **open**, not draft; `mergeable: MERGEABLE` against `main` |
| Opened | 2026-09-22 14:56 UTC, four minutes after its feature commit `be764b5`; last update 2026-09-24 19:03 UTC (the second README commit) |
| Head | `534f170` (4 commits: `c5edc85` the wire, `be764b5` the notice, `6b36e7e` + `534f170` README) |
| Base | `main`, which has moved 4 commits past `7c200bf` (PR #90, docs only: `docs/app-store/metadata.md`, `docs/screenshots/01_walk_start.png`); #91 is 4 ahead and 4 behind, with no conflicts |
| Reviews | none; no review requests, no assignee, no milestone, no auto-merge; no comments (PR or inline) |
| CI | green on `534f170`: `lint` SUCCESS, `test` SUCCESS (2026-09-24 19:04 and 19:19 UTC); the PR body reports 1,879 tests passing |
| Data dependency | satisfied: open-pilgrimages #19 merged 2026-09-22 20:37 UTC, released as v1.12.0 |
| Neighbours | #92 ("Mapbox telemetry is off on iOS", opened 2026-09-24) is also open. #90, opened a day before #91, merged 2026-09-24 16:40. iOS's latest GitHub release is `v2.0.0` = `7c200bf`, published 2026-09-24 16:40 UTC, the same minute #90 merged |

Outlook. Nothing blocks a merge: green CI, mergeable, the data live. But the owner merged #90 and published the `v2.0.0` release two days after #91 opened, and left #91 out. The owner's PRs #85–#89 merged within 40 minutes of opening (#84, slice two, took nine days). Ten days untouched, with the 2.0.0 release cut around it, reads as **held on purpose**, plausibly for a later iOS release (2.0.1 or 2.1), rather than forgotten. The annex can't decide it. The deciding question for the owner is whether #91 is meant to ship in the iOS build Android 2.0.0 claims parity with. The R2 trigger is mechanical: a merge before the R24 gate starts folds in, and a merge after rides Android 2.0.1.

### A.14 Proposed owner decisions

1. **Schema 12's notice state (decide at U35's start).** *Recommendation:* A.5.2, `last_notice_seconds` plus a `honor_notices` table. It is right for water alone and needs no schema change for #91 at any merge time. Alternative: A.5.3's columns, with a reserved `fired_stamp_ids` and a `notice_kind`. Either way, don't ship schema 12 with the plan's three caption columns and no kind.
2. **Comment on #91 before it merges?** *Recommendation:* yes, one review comment with PR91-D1, D2 and D6 (D3 as a note). Fixes there fold in with the PR at no extra cost; after a merge they become issues and a second fold-in. Defaults otherwise: parity, and the issues filed with the themed batch.
3. **Is #91 in or out of Android 2.0.0?** *Recommendation:* let R2 decide mechanically (merge before the gate means in), but ask the owner now whether #91 is aimed at the iOS build 2.0.0 claims parity with. If it is held for a later iOS release, Android should still build U35 per decision 1, so the later fold-in into 2.0.1 needs no migration. That also makes PR91-D1 an Android 2.0.1 concern, since Android would then have prior installs too.
