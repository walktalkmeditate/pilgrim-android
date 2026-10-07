# Parity Spec: Honor, offline maps per stage

| field | value |
|---|---|
| **iOS pin** | `v2.0.0` = `7c200bf` (PR #86 "slice three", merged as `905996e`, plus `d331b61`, which removed the debug offline toggle before the pin) |
| **iOS past the pin** | `e551b11` (PR #91's merge), cited only for where its launch step sits beside the tiles reconcile; its behaviour belongs to the #91 fold-in plan |
| **Android HEAD** | `ca6424db` (main, with Stage 21-2 merged) |
| **Mapbox** | Android 11.23.1 / common 24.23.1 (checked with `javap` on the cached jars); iOS 11.20 / common 24.20 per the pinned lockfile (pilgrim-ios #93) |
| **Generated** | 2026-10-06 (owner decisions recorded the same day; defects filed as pilgrim-ios #124) |
| **Type** | port |
| **Generator** | ios-parity skill: four topic readers in parallel, each applying all four lenses (behavior, UI and visual, data, edge cases) to one cluster |
| **Plan** | `docs/plans/2026-10-06-001-feat-honor-offline-maps-plan.md` (U42; feeds U43–U48) |
| **Companions** | `docs/parity/2026-10-02-honor-pilgrimage-stages-port.md` ("spec C"), whose "Stage 21-3 seams" this spec fills; the own-walk and shared-walk specs of 2026-09-29 and 2026-10-01. This spec doesn't repeat them. |
| **Checked** | All 179 cited code blocks were machine-checked: every quoted line appears at its cited lines in the pinned tree (iOS at `7c200bf`, and `e551b11` where named; Android at `ca6424db`). |

This spec outranks the plan wherever they disagree (the plan's authority order). Every iOS claim carries a Swift quote pinned to `7c200bf`, and Android claims are pinned to `ca6424db`. Inside a cluster, "§6" means that cluster's section 6; "C3 §6" means section 6 of cluster C3. Each cluster numbers its own corrections, additions and defect candidates (C2 correction 3, A-C3-2, C4-1 and so on); the tables below gather them.

| Section | Covers | Feeds |
|---|---|---|
| [C1. Geometry and estimate](#c1-geometry-and-estimate-the-corridor-the-descriptors-the-hash-the-pack-count-and-calibration) | The constants, Douglas–Peucker, the convex-part corridor, ring tests, `tileCount` and `tileTouches`, the corridor hash's byte layout (with Swift and JVM vectors), region ids, the pack count, bytes per pack and calibration, `megabytes`, the per-stage value, the estimate off Main | U43, U44, U46 |
| [C2. The tiles engine](#c2-the-tiles-engine-the-seam-status-the-save-loop-cancel-removal-reconciliation-and-the-package-hooks) | The seam and its value types, the manager's shape and signals, status / `isStageSaved` / footprint, the save loop step by step, continuations and cancel in Kotlin form, remove / `removeRegions` / reconcile and the sweep, the fake loader, the package hooks and their orphans, the launch and #91's slot, the walk guard, the concurrency the plan decided | U44, U45 |
| [C3. The Mapbox platform](#c3-the-mapbox-platform-the-loader-the-store-descriptors-and-style-packs-threading-errors-the-map-backup-and-transfer) | Which SDK each platform runs, the tile store on both, the map's store and style URIs, descriptors and style packs, the region request, the loader's cache, refresh and signals, the first answer after a process start, threading and cancellation, errors, backup and transfer (the XML lines), the one-process rule, telemetry, builder tests, the debug harness and D2's measurement | U45, U48 |
| [C4. Surfaces and copy](#c4-surfaces-and-copy-the-route-pages-maps-row-the-morning-cards-line-settings--data--maps-and-the-flag) | The route page's maps row (placement, reads, states, holds, the tap, refreshes, #121 item 5), the morning card's line from the overview and from "the day", Settings → Data → Maps, the failure lines, the flag, AE10 from the surfaces, the unchanged offline note | U46, U47, U48 |

---

## Resolutions

### Corrections to the plan, by unit

The plan was written before these reads. Where it disagrees with iOS, iOS as shipped wins. Each item points to its evidence; the cluster sections carry the detail.

**Across units**
- **The per-stage value is `(id, index, rings, corridorHash)`**, not `(index, rings, hash)`. Every lookup and request is keyed by the stage Way's id, and `isStageSaved` has no route id to rebuild it from. The list holds only the stage Ways that load (iOS's `compactMap`); `status`'s "of" and the Maps screen's T are that list's size (C1 correction 1, C2 correction 13, C4 correction 10). Call the type `TileStage`.
- **Byte counts are `Long` everywhere** (status, footprint, the estimate, calibration, `megabytes`' input). Swift's `Int` is 64-bit; a Kotlin `Int` estimate overflows at 537 packs and would read "~1 MB" (C1 correction 9, C2 correction 12).
- **The cold-cache wait is the surfaces' only** (owner decision 2). The route row, the overview, "the day", the Data card row and the Maps screen wait, bounded at 5 s, for the store's first answer after a process start. `remove`, `removeRegions`, the reconcile and the save don't wait; they read the cache as iOS's do (D6, filed). A failed first answer ends the wait as "unknown"; a stale one doesn't (the loader issues a fresh read). The wait covers the style-pack answer too (C2 corrections 3–4, C3 corrections C3-7, C3-8). The plan's deferred items "what the hooks do past the bound" and "one order for hooks, reconcile and a save's start" dissolve with it.
- **Past the bound:** the route row and Settings draw iOS's cold face (`none`, "~N MB"; "none saved") and correct on regions-changed; the morning card draws no maps line (owner decision 3, a deliberate addition, not an equivalent; C4 correction 14).
- **Disk full maps by type only** (owner decision 5). iOS's tile and pack errors are Swift enums, and its `isDiskFull` clause can never match one (probed). Drop the plan's message-based helper and its gate row (C3 correction C3-1, C3-D3).

**U43 (the corridor, the descriptors, the hash)**
- **Rounding:** Swift's `.rounded()` is half away from zero and keeps the sign of `-0.0`. `Math.round` / `roundToLong` round ties toward positive infinity and return integers, so `-0.4` would hash as `+0.0`; every Francés longitude is negative. Use a `swiftRounded(Double): Double` helper (truncate, then ±1 when the remainder's magnitude is at least 0.5). Don't reuse `HonorOverviewModel.roundedHalfAwayFromZero`, which rounds `0.49999999999999994` to 1 (C1 §6, correction 3). For `megabytes`' non-negative input, `Math.round` is exact.
- **The hash writes iOS's exact bytes** (owner decision 1): an 8-byte little-endian `regionVersion`, then each rounded value's raw IEEE-754 bits, little-endian, latitude before longitude, in emission order; lowercase hex. One `ByteBuffer`; JDK 17 matched Swift on seven vectors, which U43 asserts as literal hex (C1 §6, correction 5).
- **The sensitivity test** works only on hand-built rings at an exact microdegree (`42.0 + 4e-7` keeps the hash; `+6e-7` changes it). Through `corridor`, a moved point that simplification drops changes nothing (C1 correction 4).
- **Where things sit:** `corridor` / `simplified` / `ringContains` / `corridorContains` on `WayGeometry`'s companion; the constants and `tileCount` on `PilgrimageTilesDescriptors`; the hash and its builders (`HALF_WIDTH_METERS`, `rings`, `corridorHash`, `stage(way)`, `packCount`, `regionPrefix`, `stageIndex`) in a pure `P/data/honor/pilgrimage/PilgrimageTilesCorridor.kt`. Add it to the plan's Output Structure (C1 correction 6).
- **Tests:** iOS's one hash test splits (its two equalities here; its `isStageSaved` half in U44). The two pack-count tests move here with a pure `packCount`. iOS has no empty-corridor test; it's an Android addition (C1 corrections 7–8).

**U44 (the seam, the fake, the engine)**
- **A save doesn't bump `generation`.** It bumps `sweepGeneration` (cancelling a pending launch sweep) and captures the current `generation`; only `cancel()` moves `generation` (C2 correction 1). The reconcile also bumps the sweep generation, and sweeps on the answer to its own `refreshRegions` (C2 correction 9).
- **Where the save checks the walk** (owner decision 4): iOS checks at the door and at every step, skipped steps included. Android checks `walkScreenUp()` wherever iOS checks, and `walkActive()` (a Room read) at the door and before each real load, each read followed by a generation re-check. The door's generation is captured at the claim, before its read; the claim is released when the read returns; past the door the `Saving` phase is the slot (C2 corrections 2, 8, 16).
- **The scope's exception handler can't end a save:** `save` returns a `Deferred`, whose failure never reaches a handler. The loop's own catch ends the save as failed for any non-cancellation `Throwable`; the handler logs what escapes the posted hooks and reconcile; the loader's SDK hops run inside the scope (C2 correction 7).
- **`cancel()` is main-only and synchronous**, as iOS's: it also clears a `Failed` phase to `Idle` with nothing in flight, and never touches the sweep generation. `remove`, `removeRegions` and `reconcile` post to Main and return (C2 correction 10, C2 §N).
- **The calibration write isn't awaited before `Idle`** (iOS's `UserDefaults.set` is synchronous; C2 correction 15). The key is a `longPreferencesKey`; a missing or non-positive value reads as the seed; `bytes / packs` can be 0, which is written and reads as the seed (C1 correction 10).
- **The seam** carries `firstAnswer` (`READ` / `FAILED`) beyond iOS's members; the error is an enum in a result type, not thrown (C2 correction 12, C3 §7).
- **Robolectric:** the hooks' posts wait on the paused main looper; idle it before asserting (C2 correction 14).
- **Ported counts:** 4 fake + 22 of 24 manager tests (two move to U43) + the hash test's second half + 10 lifecycle, with the adaptations C2 §T lists (C1 correction 7).

**U45 (the loader, the rules, the wiring)**
- **`runAtLaunch()` returns `Installed?`**, not `(id, stageCount)`: #91's `restampStageHours` takes the whole value from the same read (`@e551b11`). The launch work resolves the tiles manager and starts its first store read before `runAtLaunch()` (iOS wires `packages.tiles` first), then posts the reconcile and returns, so the expiry sweep after it doesn't wait on the tile store (C2 corrections 5–6).
- **The loader's constructor creates no Mapbox object.** `TileStore.create()`, `OfflineManager()` and both descriptors are made on first use, on Main, and held. The package manager gets the tiles manager through a `Provider`/`Lazy` (C3 correction C3-10, C3-11).
- **Errors:** `TileRegionErrorType.CANCELED` → cancelled; `StylePackErrorType.CANCELED` → failed, as on iOS (C3 correction C3-2). Type only, no message test (above).
- **A failed refresh drains its waiters only when it is still the current read**; signals fire per C3 §6.7's table; a pack load's success marks the pack present without the completeness rule (matched, C3-D4) (C3 corrections C3-4 to C3-6).
- **Pixel ratio:** iOS passes none; its SDK fills the screen scale. Pass the screen density; it changes no download (C3 correction C3-3).
- **The debug clear-cache command clears both caches** (`MapboxMap.clearData` and `TileStore.clearAmbientCache`), or a deleted route can still draw offline (C3 correction C3-9).
- **Builder tests go further than the plan says:** a peer-0 `TilesetDescriptor` subclass is constructible on the JVM, and `TileRegion`, `TileRegionError`, `StylePack`, `StylePackError` have public constructors; the common initializer no-ops under Robolectric. Port iOS's loader tests 3–7 (adapted); tests 1 and 2 (the custom store and map option) become the backup-rules assertions and U48 rows (C3 corrections C3-12, C3-13; the eight tests in C3 §13.3).
- **The XML lines** for both rules files are in C3 §10.3; the test checks each section (C3 §10.4).

**U46 (the route row)**
- **Hold the row** while `phase is Downloading || page.isHeld`: Android's package phase goes idle at the commit while the post-commit steps (and Update's `removeRegions`) still run; the page's holds stand in for iOS's synchronous early phase (C4 correction 3, C2 §N).
- **The failed face is the idle face for the current status**, the saved face included, with the rust line under it. A refused save's line stays until the next save starts, or a cancel or `remove`, on any route page opened meanwhile (C4 corrections 4, 6).
- **Refreshes:** each uses the latest per-stage values, latest result wins, or AE10 can show "30 of 33". The values are keyed on the page's own `installed`, re-derived only in `reload()`, so #121 item 5 stays matched. The per-stage values and pack count are cached per `(routeId, release)`; `bytesPerPack` is re-read on every reload (calibration changes it without a release change) (C4 correction 5, C1 correction 2).
- **`megabytes` tests** are iOS's: 26,400,000 → "~26 MB", 1,900,000 → "~2 MB", 400,000 → "~1 MB", 26,100,000 → "26 MB". A 2,500,000 → "3 MB" case is an Android addition. `testTheRowsBodyReadsNoStore` is a Robolectric Compose test, not a model test. Format with `toString()` through a `%1$s` resource (C4 corrections 1–2, C1 correction 11).
- **Files to add:** `PilgrimageRouteActions.onSaveMaps` / `onCancelMaps`; `PilgrimageRouteContent` takes the tiles phase and the row state; update `PilgrimageRouteViewModelTest`'s `isBusy` test and `PilgrimageScreensSemanticsTest`. The route VM reads the manager's phase flow, not the seam's `isSaving` (which has no iOS caller) (C4 corrections 16–17).

**U47 (the morning card and Settings)**
- **"The day":** start the read at the tap, before the 300 ms hand-off, as iOS does; a sheet that appears with no read for its opening (restored after process death) starts one; once per opening, no live updates (C4 correction 8).
- **The overview's line** is absent while the first read is pending, then fills in, and changes live on regions-changed while the card is open (C4 correction 13).
- **Delete cancels a running save** (iOS's `remove` calls `cancel()` first) and reloads on regions-changed, not right after the call (C4 correction 9, C2 §N). Don't restore the "Delete maps?" dialog after process death.
- **"Saved"** on the Maps screen means the route's regions hold more than 0 bytes, partial and stale included; "stages" is always plural (C4 correction 10, C4-3).
- **The Maps row and screen follow `WaysAvailability`** (owner decision 6): hidden while a walk is on or its Honor step is pending; the screen leaves when it turns false (C4 correction 12).
- **Settings reads `installed()`**, as iOS's `OfflineMapsModel.loadInstalled` and Android's Ways footer do, a throw read as nothing installed (C2 §N, C4 A9).
- **Files to add:** `SettingsAction.OpenMaps`, `Routes.OFFLINE_MAPS` inside the `honorEnabled` gate and its `handleSettingsAction` branch, `HonorWalkViewModel` ("the day"'s line), `StageMorningCardModel.mapsLine`, a `MapsRowViewModel` beside `WaysRowViewModel`; `StageDaySheet`'s signature change touches `StageMorningCardTest` (C4 correction 11).

**U48 (the device checklist)**
- Add C3's rows (store path, `du -sk` for D2 and Delete, the clear-cache step before every airplane row, both appearances, z15–16 online over a saved corridor, a cold-start save for the #92 note, background starts), C2's (the first-answer time after a UI kill, "the day" restored open, an all-current re-tap, a walk started mid-save, a kill mid-Replace, a failed Update commit), and C4's (every row face with TalkBack, the held row during an Update, Settings during a save, #121 item 5 after an Update, D1, the flag off) (C3 notes, C2 notes, C4 notes).
- The transfer row: owner decision 7.
- Expected seed estimates: Francés 62 packs ≈ "~248 MB", Nakahechi 3 ≈ "~12 MB" (C1 §17).

### The flow-analysis items

| # | Item | Resolution |
|---|---|---|
| 1 | Store location and transfer | Owner decision (2026-10-06): the default store; the exact XML in C3 §10.3; U48 confirms the path |
| 2 | Threading | The engine on Main; every SDK callback hopped there; hooks post and return (C2 §12, C3 §8) |
| 3 | The sweep wiping maps | Matched as shipped (D3) for read and decode failures; a thrown launch read skips the sweep (C2 §10) |
| 4 | Mapbox out of `:tracker` | `Provider`s resolved only in UI launch work and screens; constructors touch no Mapbox; a test (C3 §11) |
| 5 | "Mid-walk" for a save | Owner decision 4 (C2 §11) |
| 6 | Backgrounded saves | The plan's rule stands; U48's rows (C2 notes) |
| 7 | Partial success | Matched and filed; counts decide completeness (C2 D-7, C3 §6.3) |
| 8 | Cold cache after a restart | The surfaces wait; the hooks don't (owner decisions 2–3) |
| 9 | Delete doesn't free disk | Matched and filed (C3-D6, with D5); U48 measures |
| 10 | Estimate and calibration | Off Main from `TileStage` values; `Long`; bytes-per-pack re-read per reload (C1 §8–§12) |
| 11 | Orphans the seam never reaches | A killed Replace is swept in the same launch (not a defect); a failed Update's rollback and Android's throwing retire wait for the next launch (C2 §9) |
| 12 | The Maps row's gating | Owner decision 6 |

### Android additions to record at the gate

| Addition | Reason | Source |
|---|---|---|
| The default tile store (`files/.mapbox/`) instead of a dedicated folder; no `MapboxMapsOptions` written; no `tmp/` fallback | Owner decision 2026-10-06; the defaults are the default store and READ_ONLY; 11.24's one store per process | A-C3-1, A-C3-3, A-C3-4 |
| `.mapbox/` excluded from device transfer and, explicitly, from cloud backup; unflagged | Equivalent of iOS's iCloud exclusion; also stops the ambient map cache travelling | A-C3-2 |
| The loader's Mapbox objects made on first use, on Main, with a Main check at each entry | Keeps `:tracker` and flag-off builds Mapbox-free; `@MainThread` misuse is undefined, not an exception | A-C3-5, A-C3-6 |
| A Mapbox call that throws is a failed answer, posted, and logged by type, a repeat of the last once; a store that can't open answers as an empty, unread one, the first answer `FAILED` | Java natives can throw where iOS's calls can't, and a failed Mapbox init throws an `Error`; a waiter is never stranded, and no reader throws into a screen | A-C3-11 |
| The engine on Main, its mutators posted; the package hooks return before they run | iOS's `@MainActor`; the package manager calls the seam on IO under its actor | C2 A |
| The door's claim before its suspending read; the generation re-checked after every guard read; `walkActive()` at the door and before each real load | Android's walk check is a Room read, and its walk outlives the UI process in `:tracker` | C2 A |
| A thrown guard read ends the save as `INCOMPLETE`; the loop's catch takes any non-cancellation `Throwable` | iOS's guard can't fail | C2 A |
| The surfaces wait up to 5 s for the store's first answer (`firstAnswer`, with a fresh read on a stale first answer, a failed packs read reported, and a failed answer asked again on the next call) | Android restores the overview, "the day", the route page and Settings in a fresh process; iOS's cold window is under a second | C2 A, A-C3-7, C4 A3 |
| Past the bound, the morning card draws no maps line | Not to tell a walker with no signal that saved maps are missing; deliberate addition (owner decision 3) | C4 A4 |
| `runAtLaunch()` returns `Installed?`; a thrown launch read skips the reconcile (and later #91's restamp) | iOS's `installed()` can't throw | C2 A |
| A retire that throws before `tiles?.remove` leaves the route's regions until the next launch | Android's retire reads Room | C2 A |
| The estimate and status computed off Main from `TileStage` values, cached per `(routeId, release)`; the row drawn once both are in | iOS computes them on the main thread (D8) | C1 A1–A3, C4 A1 |
| The route row also held while the page holds itself | The package phase goes idle at the commit while the post-commit steps still run (extends spec C's A-10) | C2 A, C4 A2 |
| "The day"'s line read at the tap, or on first appearance for a restored sheet | Android restores `showStageDay` (extends spec C's A-3) | C4 A5 |
| The Maps row and screen follow `WaysAvailability` | iOS's Settings is unreachable mid-walk; Android's may not be (owner decision 6) | C4 A6 |
| The calibration write isn't awaited before `Idle` | iOS's `UserDefaults.set` is synchronous | C2 A |
| The per-route bytes-per-pack rides a device transfer, not a cloud backup | The app's backup rules exclude every file from cloud backup; a lost figure falls back to the seed | C1 A5 |
| The load callback also checks `pending === cont` | Hardening against a doubled SDK callback; no behaviour change | C2 A |
| `pixelRatio` and `networkRestriction(NONE)` set explicitly | The values iOS's SDK fills or defaults, written so the tests pin them | A-C3-8, A-C3-9 |
| Settings reads a thrown `installed()` as nothing installed; the Maps screen has a `Loading` state | iOS's `installed()` can't throw; its first load lands before the first frame | C4 A9, A10 |
| Debug commands: clear both caches; a tiles report with on-disk sizes | U48's airplane rows and D2; debug only | A-C3-10 |
| A fully current re-save may show its progress line for a frame | The door's walk read suspends once before the loop; iOS's never suspends (owner decision 4 keeps it to one read) | C4 A8 |

---

## Matched as shipped, and filed upstream

Android ports each of these exactly as iOS ships it. They're filed as [pilgrim-ios #124](https://github.com/walktalkmeditate/pilgrim-ios/issues/124) and an [addition to #121 item 5](https://github.com/walktalkmeditate/pilgrim-ios/issues/121#issuecomment-6027696523) (2026-10-06). #124's items: 1 D3, 2 D6, 3 D5 + C3-D6, 4 the partial load + C3-D4, 5 C3-D5, 6 C3-D2, 7 11a, 8 D4, 9 D7, 10 C4-1, 11 C4-2, C4-3 and D1, 12 C1-D1 and the notes.

| # | Defect | Severity | Source |
|---|---|---|---|
| D3 | A launch that can't read or decode the installed route sweeps every saved map (up to ~240 MB, silently) | Low–medium | C2 D |
| D6 | The engine reads a cold cache before the store's first answer: a Remove that early leaves the maps until the next launch; a save that early costs a round trip per stage | Low | C2 D, C3 |
| D5 + C3-D6 | "Delete maps" never removes the style packs or clears the ambient cache, where a removed region's packs go (no quota on 11.20 / 11.23), so it frees less disk than Settings implies | Low–medium (disk) | C2 D, C3-D6 |
| Flow 7 + C3-D4 | A region load that succeeds with resources missing counts as done ("30 of 33 saved" with no error line); a style pack's load success marks it present without the completeness rule | Low | C2 D, C3-D4 |
| C3-D5 | A failed style-pack read is dropped silently, leaving "n of n saved" until a later read | Low | C3-D5 |
| C3-D2 | A removal or a load success makes an in-flight read stale without starting a new one, so the launch reconcile waits for the next reader's refresh | Low | C3-D2 |
| 11a | A failed Update commit's rollback leaves the route's saved maps on the phone, invisible to Settings, until the next launch | Low | C2 D |
| D4 | "Save maps for the way · 33 of 33 saved" when a style pack is missing; the morning card then says "maps saved for today"; a cold-start packs race shows it with both packs present, and a tap doesn't clear it | Low (copy) | C2 D, C4 |
| D7 | "maps saved, N MB. Tap to save again" saves nothing new and refreshes no expired tile | Low (copy, a11y) | C4 |
| C4-1 | A save's failure line outlives its cause and its page ("finish your walk first" after the walk, on every route page) | Low (copy) | C4 |
| C4-2 | Remove's and Replace's confirmations don't say the saved maps go too | Low (copy) | C4 |
| C4-3 | "1 of 1 stages" on the Maps screen | Low (copy) | C4 |
| D1 | The overview's offline note ("map tiles need a connection…") still shows on a stage whose maps are saved | Low | C4 |
| C1-D1 | `tileTouches` misses z11 cells a long straight segment crosses, though its comment says the estimate "errs high"; no effect on today's routes | Low | C1 |
| Notes | C3-D1 (a comment says a cancelled load never reports back), C3-D3 (the loader's `isDiskFull` clause can't match an SDK error), C2 N1 (`shared`'s comment names the wrong wiring site) | Comment only | C2, C3 |
| #121 item 5, addition | A route page reopened during an Update keeps the old stage lines: after the commit it reads "30 of 33 saved" in AE10's terms, and a save from it re-adds the retired stages' maps and skips the redrawn stage | Low | C4 |

Not filed yet:
- **D2** (summed region bytes may double-count packs adjacent stages share; per-stage pack sums run 1.5–2.3× the union, and the Nakahechi's 7/3 matches iOS's "~8 MB estimated, 18 MB saved"): U48 measures the summed figure against the store directory's size first (C1 §15, C3 §14.4).
- **C4-4** (one tap may fire every default-style button in the route page's header `List` row, so "Save maps" with an update ready could also start the Update): verify on an iPhone first (iPhone check I1).
- **D8** (the estimate and status on the main thread) and **D9** ("save on wifi" though cellular is allowed, intended copy): notes, not defects.
- Already filed and confirmed: #122 item 5 (the disk-full line names voices), #93 (the lockfile drift).

---

## Owner decisions (2026-10-06)

The owner accepted all seven recommendations on 2026-10-06. The units build them as written below.

| # | Decision | Recommendation | Evidence |
|---|---|---|---|
| 1 | **The corridor hash's bytes:** iOS's exact layout, or any deterministic one | iOS's exact layout. It costs nothing, Swift's vectors become literal fixtures, and no gate row is needed | C1 §6, §19 |
| 2 | **Do the package hooks wait for the store's first answer?** | No: match iOS and file D6. The surfaces still wait. A waiting hook is an Android-only fix, and it creates the ordering problem the plan then had to solve | C2 §12, C2 O-1 |
| 3 | **Past the wait's bound, the morning card:** no maps line, or iOS's "no offline maps for today — save on wifi" | No line, recorded at the gate as a deliberate addition, with D6 filed. In every settled state the card says what iOS's says | C4 O-2 |
| 4 | **Where the save re-checks the walk** | iOS's clause (`walkScreenUp()`) wherever iOS checks; Android's `walkActive()` at the door and before each real load. Both at every step would cost about 35 Room reads per re-tap and a progress flash iOS doesn't have | C2 §11, C2 O-2 |
| 5 | **Disk full:** by type only, or also by an ENOSPC message | By type only (parity): iOS never reads the message. If the message test is wanted, file it on iOS first | C3 §9, O-C3-1 |
| 6 | **Does Settings' Maps row follow the Ways row's walk gating?** | Yes: reuse `WaysAvailability`. iOS's Settings can't be reached mid-walk; a Delete mid-walk would blank the walker's offline basemap | C4 O-1 |
| 7 | **The device-transfer row for `.mapbox/`** | The one-phone local-transport check in device-transfer mode if it works on the owner's phone, else waive with the XML test as the proof (as spec C's G2 was) | C3 §10.4, O-C3-2 |

## Open questions iOS leaves open (iPhone checks for the combined pass)

| # | Check |
|---|---|
| I1 | On iOS 2.0.0, with an update ready, does one tap on "Save maps for the way" also start the Update (and one on "Update" also start a save)? Files C4-4 if so (C4). |
| I2 | What VoiceOver reads for the saved row ("maps saved, N MB. Tap to save again") and the hidden check, and whether the held row looks dimmed during an Update (C4 §1.4). |
| I3 | After "Delete maps" on iOS, how much the app's storage figure drops against the saved figure (C3-D6). |

---

## C1. Geometry and estimate: the corridor, the descriptors, the hash, the pack count and calibration

| Field | Value |
|---|---|
| iOS pin | `pilgrim-ios` `7c200bf` (v2.0.0). PR #86 merged as `905996e`; none of this cluster's files changed between `905996e` and `7c200bf` (`git diff 905996e 7c200bf --stat` is empty for them), and `d331b61` touches only `DataCard.swift` and the design doc. |
| Android | `pilgrim-android` `ca6424db` (main) |
| Feeds | U43 (geometry, descriptors, hash, pack count, calibration maths); U44 (estimate, calibration storage, the per-stage value); U46 (`megabytes`) |
| Lenses | Behavior, UI/visual (one formatter, no other strings), Data, Edge cases |

**Read in full at `7c200bf`:** `Pilgrim/Models/Honor/WayGeometry.swift` (and PR #86's diff of it: the corridor block, `corridorContains`, `simplified` and `ringContains` are all new in `905996e`; the corridor uses **none** of the file's existing helpers, not `distanceMeters`, not the `nearest` projection, but its own local-metre projection), `Pilgrim/Models/Honor/PilgrimageTilesDescriptors.swift`, `Pilgrim/Models/Honor/PilgrimageTilesManager.swift` (whole file for context; this section writes up only the constants, `rings`, `corridorHash`, `regionPrefix`, `stageIndex`, `bytesPerPack`, `packCount`, `estimateBytes`, `calibrate`), `Pilgrim/Models/Honor/TileRegionLoading.swift` (context), `Pilgrim/Scenes/Honor/PilgrimageMapsRow.swift` (`megabytes` only), `UnitTests/Honor/WayGeometryCorridorTests.swift`, `UnitTests/Honor/PilgrimageTilesDescriptorsTests.swift`, `UnitTests/Honor/PilgrimageTilesManagerTests.swift`, `UnitTests/Honor/FakeTileRegionLoader.swift` (fixture defaults), `UnitTests/Fixtures/Pilgrimage/stage-00.json`. Read for callers: `PilgrimageRouteView.swift:76-85,145-154,370-386`, `OfflineMapsView.swift:1-46`, `PilgrimageWayImporter.swift:168-190`, `WayStore.swift:67-69`, `MapboxTileRegionLoader.swift:41,83`. The iOS design spec §2.1–2.2 and §3.1 were read for intent; they agree with the shipped code on every C1 point.

**Compared on Android at `ca6424db`:** `P/domain/honor/WayGeometry.kt`, `T/domain/honor/WayGeometryTest.kt`, `P/domain/honor/Way.kt`, `P/data/honor/WayStore.kt` (`stageWayId`, the id regex), `P/domain/seek/SeekSeed.kt` (the house little-endian SHA-256 pattern), `P/core/threads/TranscriptContext.kt` (the house hex pattern), `P/ui/honor/HonorOverviewModel.kt` (an existing Swift-rounding helper), `P/ui/settings/data/WaysListViewModel.kt` (the existing MB formatter), `P/data/honor/pilgrimage/PilgrimageWayImporter.kt`, `T/data/honor/pilgrimage/PilgrimagePackageHarness.kt` (fixture loader), `app/src/test/resources/honor/pilgrimage/stage-00.json` (byte-identical to iOS's: both `shasum` to `c380d87e…`), and Mapbox common 24.23.1's `TileRegion` (`javap`: every size and count is `long`).

**Probes** (`docs/parity/probes/2026-10-06-offline-maps/C1/`; the dataset probes read `$OPEN_PILGRIMAGES`, default `../open-pilgrimages`): `probe.swift` runs verbatim copies of the iOS functions on every test fixture; `Probe.java` writes the Kotlin byte layout on JDK 17 and reproduces Swift's hex exactly on all seven vectors in §6; `probe3.swift`/`probe4.swift` run the shipped functions over the real `open-pilgrimages` dataset (local clone at `675d4e3`, 2026-09-22); `probe2.swift` tests `tileTouches` against dense sampling.

### 1. The constants

```swift
    static let halfWidthMeters = 500.0
    /// One z11 cell of the corridor, both tilesets together, measured against
    /// the tile API on 2026-09-15: 3.8 MB on the Francés, 2.7 MB on the
    /// Nakahechi. The default for every route until its first save
    /// calibrates it. The 10 KB it replaced was a streets tile, and tiles
    /// are not what the store downloads.
    static let seedBytesPerPack = 4_000_000
```
> Pilgrim/Models/Honor/PilgrimageTilesManager.swift:31-37@7c200bf

```swift
    static let streetsZoom: ClosedRange<Int> = 11...14
// …
    static let packRootZoom = 11
// …
    static let regionVersion = 2
    /// Shikoku and Kumano labels are CJK; rasterizing ideographs on the
    /// device keeps the style pack from carrying every glyph range.
    static let rasterizesIdeographsLocally = true
```
> Pilgrim/Models/Honor/PilgrimageTilesDescriptors.swift:19-34@7c200bf

```swift
        let line = simplified(points, toleranceMeters: 25)
        guard let first = line.first else { return [] }
        let latScale = 111_320.0
        let lonScale = 111_320.0 * cos(first.latitude * .pi / 180)
```
> Pilgrim/Models/Honor/WayGeometry.swift:230-233@7c200bf

| Constant | Value | Where it's used | Android name (suggested) |
|---|---|---|---|
| half width | `500.0` m | `rings(for:)` only (the manager's, not a `corridor` default) | `PilgrimageTilesCorridor.HALF_WIDTH_METERS` |
| simplify tolerance | `25` m, hard-coded inside `corridor` | `corridor` → `simplified` | a literal inside `corridor`, as iOS (`simplified` keeps its `toleranceMeters` parameter for the test) |
| metres per degree | `111_320.0` for latitude; `111_320.0 × cos(firstLat)` for longitude | `corridor`, `simplified` | reuse `WayGeometry`'s private `METERS_PER_DEGREE = 111_320.0` (`WayGeometry.kt:212@ca6424db`) |
| seed bytes per pack | `4_000_000` (decimal, not 4 MiB) | `bytesPerPack` fallback | `SEED_BYTES_PER_PACK = 4_000_000L` |
| Streets zoom | `11...14` (closed) | the loader's two descriptors (C3) | `STREETS_ZOOM = 11..14` |
| pack root zoom | `11` | `packCount` (`root...root`) | `PACK_ROOT_ZOOM = 11` |
| region version | `2` | first 8 bytes of every corridor hash | `REGION_VERSION = 2` |
| glyph mode | `true` → `.ideographsRasterizedLocally` | the loader's style packs (C3) | `RASTERIZES_IDEOGRAPHS_LOCALLY = true` |

`regionVersion` stays `2` on Android, though no Android phone ever held a version-1 region. A different number would only make the hash differ from iOS's for no reason, and the descriptors test pins `2` (R5).

### 2. Douglas–Peucker (`simplified`)

```swift
    static func simplified(_ points: [CLLocationCoordinate2D], toleranceMeters: Double) -> [CLLocationCoordinate2D] {
        guard points.count > 2, let first = points.first else { return points }
        let latScale = 111_320.0
        let lonScale = 111_320.0 * cos(first.latitude * .pi / 180)
        let local = points.map { (x: ($0.longitude - first.longitude) * lonScale, y: ($0.latitude - first.latitude) * latScale) }
        var keep = [Bool](repeating: false, count: points.count)
        keep[0] = true
        keep[points.count - 1] = true
        var stack: [(Int, Int)] = [(0, points.count - 1)]
        while let (a, b) = stack.popLast() {
            guard b - a > 1 else { continue }
            let ax = local[a].x, ay = local[a].y, bx = local[b].x, by = local[b].y
            let dx = bx - ax, dy = by - ay
            let lenSq = dx * dx + dy * dy
            var farthest = -1.0, index = a
            for i in (a + 1)..<b {
                let px = local[i].x - ax, py = local[i].y - ay
                let distance: Double
                if lenSq > 0 {
                    let u = max(0, min(1, (px * dx + py * dy) / lenSq))
                    let cx = px - u * dx, cy = py - u * dy
                    distance = (cx * cx + cy * cy).squareRoot()
                } else {
                    distance = (px * px + py * py).squareRoot()
                }
                if distance > farthest { farthest = distance; index = i }
            }
            if farthest > toleranceMeters {
                keep[index] = true
                stack.append((a, index))
                stack.append((index, b))
            }
        }
        return zip(points, keep).compactMap { $1 ? $0 : nil }
    }
```
> Pilgrim/Models/Honor/WayGeometry.swift:271-305@7c200bf

What it does, exactly:
- **Pass-through:** fewer than 3 points (0, 1 or 2) return the input list unchanged, duplicates included. Three identical points simplify to 2 (probe: `[p, p, p]` → 2 points, corridor → 2 squares).
- **Projection:** local metres from the **first input point**: `x = (lon − lon₀) × 111_320 × cos(lat₀ × π/180)`, `y = (lat − lat₀) × 111_320`. Raw degree differences, no antimeridian handling (no route crosses ±180°).
- **Endpoints:** index 0 and the last index are always kept.
- **Stack:** an explicit LIFO, seeded with `(0, n−1)`. `popLast` takes the most recently pushed pair; after a split it pushes `(a, index)` then `(index, b)`, so `(index, b)` is processed first. The order changes nothing about the result (each sub-range's decision depends only on its own endpoints), so a Kotlin `ArrayDeque.removeLast()` port with the same push order is exact and any order would be too. Keep iOS's order anyway.
- **Distance:** to the **clamped segment** `a→b` (`u` clamped to `[0, 1]`), not to the infinite line. A degenerate segment (`lenSq == 0`, as when the route is a closed loop whose first and last points coincide) uses the distance to `a`.
- **Farthest point:** the scan starts at `farthest = −1.0` and replaces only on a strictly greater distance (`>`), so the **first** of equal-distance points wins.
- **Keep rule: strict.** A point is kept only when `farthest > toleranceMeters`. At exactly 25 m it is dropped (probe: a midpoint 25 m off a 200 m line is dropped, at 26 m kept).
- **Output keeps the originals:** `zip(points, keep).compactMap` returns the input coordinates themselves, in input order, never projected copies (probe: a vertex `0.123456789123, 0.987654321987` survives bit for bit). The corridor's rings, by contrast, are computed copies (§3).
- Real data (probe3, Swift `-O`): the Francés' 5,138 route points simplify to 2,451; the Nakahechi's 504 to 197; Shikoku Awa's 1,434 to 705.

Android has no `simplified` today (`WayGeometry.kt:21@ca6424db` says "The stage-only corridor, simplify, and ring functions are not ported."). U43 adds it to `WayGeometry`'s companion as `fun simplified(points: List<WayCoordinate>, toleranceMeters: Double): List<WayCoordinate>`, returning the same objects.

### 3. The corridor (`corridor(around:halfWidthMeters:)`)

```swift
    static func corridor(around points: [CLLocationCoordinate2D], halfWidthMeters h: Double) -> [[CLLocationCoordinate2D]] {
        let line = simplified(points, toleranceMeters: 25)
        guard let first = line.first else { return [] }
        let latScale = 111_320.0
        let lonScale = 111_320.0 * cos(first.latitude * .pi / 180)
        // Work in local metres, then back to degrees at the end.
        let local = line.map { (x: ($0.longitude - first.longitude) * lonScale, y: ($0.latitude - first.latitude) * latScale) }
        func geo(_ p: (x: Double, y: Double)) -> CLLocationCoordinate2D {
            CLLocationCoordinate2D(latitude: first.latitude + p.y / latScale, longitude: first.longitude + p.x / lonScale)
        }
        func square(_ v: (x: Double, y: Double)) -> [CLLocationCoordinate2D] {
            [geo((v.x - h, v.y - h)), geo((v.x + h, v.y - h)), geo((v.x + h, v.y + h)), geo((v.x - h, v.y + h)), geo((v.x - h, v.y - h))]
        }
        var parts: [[CLLocationCoordinate2D]] = []
        for i in 0..<local.count {
            if i + 1 < local.count {
                let a = local[i], b = local[i + 1]
                var dx = b.x - a.x, dy = b.y - a.y
                let len = (dx * dx + dy * dy).squareRoot()
                // A zero-length segment has no perpendicular and so no
                // rectangle; its endpoints' squares still cover it.
                if len > 0 {
                    dx /= len; dy /= len
                    let nx = -dy * h, ny = dx * h
                    // Right side forward, left side back: counterclockwise
                    // like the squares, as RFC 7946 asks of an exterior ring.
                    parts.append([geo((a.x - nx, a.y - ny)), geo((b.x - nx, b.y - ny)),
                                  geo((b.x + nx, b.y + ny)), geo((a.x + nx, a.y + ny)), geo((a.x - nx, a.y - ny))])
                }
            }
            parts.append(square(local[i]))
        }
        return parts
    }
```
> Pilgrim/Models/Honor/WayGeometry.swift:229-262@7c200bf

```swift
    /// Parts are emitted quad-then-square per vertex, in line order, because
    /// `PilgrimageTilesManager.corridorHash` hashes them in that order: a
    /// different order would read as a redrawn stage and reload every region.
```
> Pilgrim/Models/Honor/WayGeometry.swift:226-228@7c200bf

```swift
    static func rings(for way: Way) -> [[CLLocationCoordinate2D]] {
        WayGeometry.corridor(around: way.route.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) },
                             halfWidthMeters: halfWidthMeters)
    }
```
> Pilgrim/Models/Honor/PilgrimageTilesManager.swift:71-74@7c200bf

What it does:
- **Input:** the stage Way's whole `route`, every point, in route order, as `(lat, lon)`; altitude and time are dropped. Half width `500` m from the manager.
- **Step 1:** simplify at 25 m (§2). An empty line returns `[]`.
- **Step 2:** re-project the **simplified** line from **its** first point (the same point as the route's first, since `simplified` keeps index 0, so the origin and both scales equal `simplified`'s). Back-projection is `geo`: `lat = lat₀ + y / 111_320`, `lon = lon₀ + x / lonScale`. Ring coordinates are computed copies: even a square's centre isn't stored, and the corners are `geo` of offsets, so none of them is a route point.
- **Step 3, emission order:** for each simplified vertex `i` in order: first the quad for segment `i → i+1` (if `i` isn't last **and** the segment has length `> 0`), then vertex `i`'s square. So the list reads `quad₀, sq₀, quad₁, sq₁, …, quadₙ₋₂, sqₙ₋₂, sqₙ₋₁`: `2n − 1` parts for `n` vertices when no segment is zero-length.
- **The quad:** `d = (b − a) / |b − a|` (unit direction), `n = (−d.y · h, d.x · h)` (the left normal scaled to `h`). The ring is `[a − n, b − n, b + n, a + n, a − n]`: right side forward, left side back, so counterclockwise in an x-east, y-north frame. Width `2h`, no end caps beyond the vertex squares.
- **The square:** axis-aligned (east/north in local metres), side `2h`, centred on the vertex: `[(x−h, y−h), (x+h, y−h), (x+h, y+h), (x−h, y+h), (x−h, y−h)]`, south-west first, counterclockwise. Its corner reaches `h√2 ≈ 707` m on the diagonal.
- **Closure:** every part is 5 coordinates with the first **recomputed** as the fifth by the same `geo` expression, so first and last are bit-identical (the test compares them with `XCTAssertEqual`). Kotlin must build the closing point the same way (or reuse the first object); either gives exact equality.
- **Zero-length segments** (consecutive identical simplified vertices) get no quad; both vertices still get squares. `len > 0` is strict.
- **Edge inputs (probe):** `[]` → `[]`; one point → one square; two identical points → two identical squares, no quad; three identical points → simplified to 2 → two squares.
- **The fixture stage** (31 points along lat 42 from lon 0 to 0.03627) simplifies to its 2 ends and gives 3 parts. Real data: the Francés gives 4,869 parts over 33 stages, the Nakahechi 390, Awa 1,405. Swift `-O` decodes the 33 Francés stage files and builds their corridors in 0.04 s.
- **Several stages:** each stage's corridor is its own list. `packCount` concatenates every stage's rings into one list (§8); the loader puts one stage's rings into one MultiPolygon (C3).

Android at `ca6424db`: no `corridor` (`WayGeometry.kt:21@ca6424db`). Android's coordinate type is `WayCoordinate(lat, lon)` and the route is `List<WayPoint>` in route order:

```kotlin
@Serializable
data class WayCoordinate(
    val lat: Double,
    val lon: Double,
)
```
> app/src/main/java/org/walktalkmeditate/pilgrim/domain/honor/Way.kt:16-20@ca6424db

```kotlin
    val route: List<WayPoint>,
```
> app/src/main/java/org/walktalkmeditate/pilgrim/domain/honor/Way.kt:275@ca6424db

U43 adds `fun corridor(around: List<WayCoordinate>, halfWidthMeters: Double): List<List<WayCoordinate>>` to `WayGeometry`'s companion. The ring type is `List<List<WayCoordinate>>`, latitude first in each value, the same order the hash reads (§6). The loader (C3) turns each into `Point.fromLngLat(lon, lat)`; nothing else swaps the order. Use the same operation order as Swift (`(lon − lon₀) * lonScale`, `lat₀ + y / latScale`, `-dy * h`, `dx * h`, `dx /= len` before scaling), so the rings and the hash (§6) are bit-identical to iOS's on the JVM (verified: `Probe.java` reproduces Swift's stage-0 corridor to the last digit and its hash exactly).

### 4. `ringContains` and `corridorContains`

```swift
    static func corridorContains(_ rings: [[CLLocationCoordinate2D]], _ point: CLLocationCoordinate2D) -> Bool {
        rings.contains { ringContains($0, point) }
    }
```
> Pilgrim/Models/Honor/WayGeometry.swift:264-266@7c200bf

```swift
    /// Ray casting, in degrees — good enough for "is this tile centre inside".
    static func ringContains(_ ring: [CLLocationCoordinate2D], _ point: CLLocationCoordinate2D) -> Bool {
        guard ring.count > 3 else { return false }
        var inside = false
        var j = ring.count - 1
        for i in 0..<ring.count {
            let yi = ring[i].latitude, xi = ring[i].longitude
            let yj = ring[j].latitude, xj = ring[j].longitude
            if (yi > point.latitude) != (yj > point.latitude) {
                let x = (xj - xi) * (point.latitude - yi) / (yj - yi) + xi
                if point.longitude < x { inside.toggle() }
            }
            j = i
        }
        return inside
    }
```
> Pilgrim/Models/Honor/WayGeometry.swift:307-322@7c200bf

- Even-odd ray cast to the east, in raw degrees (no projection), over every edge including the closing one (`j` starts at the last index; for a closed ring that edge is zero-length and never satisfies the straddle test).
- A ring needs **more than 3** coordinates; 3 or fewer is never inside.
- Boundary behaviour is the classic half-open rule: a vertex exactly at the query's latitude counts on the `>` side; a point exactly on an edge's longitude (`point.longitude == x`) is outside that crossing. Port the comparisons exactly (`>` for latitude, `<` for longitude).
- `corridorContains` is true when any part contains the point (`any` in Kotlin).
- Used by `tileTouches` (§5) and by the corridor tests. Nothing in the app's UI calls it.

### 5. `tileCount`, the slippy-tile maths and `tileTouches`

```swift
    static func tileCount(rings: [[CLLocationCoordinate2D]], zooms: ClosedRange<Int>) -> Int {
        let parts = rings.filter { $0.count > 3 }
        guard !parts.isEmpty else { return 0 }
// …
        let boxes = parts.map { part -> (minLat: Double, maxLat: Double, minLon: Double, maxLon: Double) in
            let lats = part.map(\.latitude), lons = part.map(\.longitude)
            return (lats.min()!, lats.max()!, lons.min()!, lons.max()!)
        }
        var total = 0
        for z in zooms {
            let n = Double(1 << z)
            let (xMin, yMax) = tile(lat: boxes.map(\.minLat).min()!, lon: boxes.map(\.minLon).min()!, n: n)
            let (xMax, yMin) = tile(lat: boxes.map(\.maxLat).max()!, lon: boxes.map(\.maxLon).max()!, n: n)
            // The sweep visits each (z, x, y) once and stops at the first
            // part that touches it, so overlapping parts cannot double-count.
            for x in xMin...xMax {
                for y in yMin...yMax {
                    let southWest = coordinate(x: Double(x), y: Double(y + 1), n: n)
                    let northEast = coordinate(x: Double(x + 1), y: Double(y), n: n)
                    let touched = parts.indices.contains { index in
                        let box = boxes[index]
                        return box.maxLat >= southWest.latitude && box.minLat <= northEast.latitude
                            && box.maxLon >= southWest.longitude && box.minLon <= northEast.longitude
                            && tileTouches(parts[index], x: x, y: y, n: n)
                    }
                    if touched { total += 1 }
                }
            }
        }
        return total
    }

    private static func tile(lat: Double, lon: Double, n: Double) -> (x: Int, y: Int) {
        let x = Int(floor((lon + 180) / 360 * n))
        let latRad = lat * .pi / 180
        let y = Int(floor((1 - log(tan(latRad) + 1 / cos(latRad)) / .pi) / 2 * n))
        return (min(max(x, 0), Int(n) - 1), min(max(y, 0), Int(n) - 1))
    }

    private static func coordinate(x: Double, y: Double, n: Double) -> CLLocationCoordinate2D {
        let lon = x / n * 360 - 180
        let lat = atan(sinh(.pi * (1 - 2 * y / n))) * 180 / .pi
        return CLLocationCoordinate2D(latitude: lat, longitude: lon)
    }

    /// A tile counts when any of its corners or its centre is inside the
    /// ring, or when a ring vertex is inside the tile. Cheap and slightly
    /// generous; the estimate errs high rather than low.
    private static func tileTouches(_ ring: [CLLocationCoordinate2D], x: Int, y: Int, n: Double) -> Bool {
        let probes = [
            coordinate(x: Double(x), y: Double(y), n: n),
            coordinate(x: Double(x + 1), y: Double(y), n: n),
            coordinate(x: Double(x), y: Double(y + 1), n: n),
            coordinate(x: Double(x + 1), y: Double(y + 1), n: n),
            coordinate(x: Double(x) + 0.5, y: Double(y) + 0.5, n: n)
        ]
        if probes.contains(where: { WayGeometry.ringContains(ring, $0) }) { return true }
        let west = coordinate(x: Double(x), y: Double(y + 1), n: n), east = coordinate(x: Double(x + 1), y: Double(y), n: n)
        return ring.contains { $0.longitude >= west.longitude && $0.longitude <= east.longitude
            && $0.latitude >= west.latitude && $0.latitude <= east.latitude }
    }
```
> Pilgrim/Models/Honor/PilgrimageTilesDescriptors.swift:42-44,50-106@7c200bf

What it does:
- **Parts:** rings with **more than 3** coordinates; none → `0`. Every corridor part has 5.
- **Boxes:** each part's own min/max lat and lon.
- **Per zoom** in the closed range (`for z in zooms`): `n = 2^z`. The sweep's extent is the union of all part boxes: `(xMin, yMax)` from the minimum lat and lon, `(xMax, yMin)` from the maxima (y grows southward). Each `(x, y)` in that rectangle is visited once and counted once if **any** part passes both its box test and `tileTouches`. Totals are summed across zooms: a z11 tile and a z12 tile both count. Within a zoom a tile counts once however many parts or stages touch it.
- **Box rejection:** `box.maxLat >= sw.lat && box.minLat <= ne.lat && box.maxLon >= sw.lon && box.minLon <= ne.lon`, all inclusive, so a part box touching a tile edge passes. It's a pure speed-up: every way `tileTouches` can answer true puts a point inside both boxes, so the result is identical with or without it (the comment at :45-49 says so, and it holds; the corner probes and the box edges come from the same `coordinate` expressions).
- **Slippy maths:** the standard Web Mercator XYZ formulas, `x = ⌊(lon + 180) / 360 · n⌋`, `y = ⌊(1 − ln(tan φ + sec φ) / π) / 2 · n⌋`, then clamped to `[0, n − 1]` (`Int(n) − 1`). There is **no latitude clamp** to ±85.0511°; the y clamp covers the poles (probe: lat 85.1 → y 0, lat 90 → y 0, lat −89.9 → y 2047). At lat −90 Swift's `Int(floor(NaN))` would trap where Kotlin's `toInt()` gives 0; no route reaches it. Inverse: `lon = x / n · 360 − 180`, `lat = atan(sinh(π (1 − 2y / n))) · 180 / π`. Kotlin: `kotlin.math.floor`, `ln`, `tan`, `cos`, `atan`, `sinh`, and `floor(x).toInt()` (`Int(Double)` truncates toward zero, but it is always applied to a `floor` result here, so either is exact).
- **`tileTouches` is not a true polygon–tile intersection.** It is true when one of five probes (the four corners and the centre) is inside the part by `ringContains`, or when any part vertex lies inside the tile's box, inclusive on all four sides (a vertex on a shared edge counts for both tiles). A long, thin part that crosses a tile without containing a corner or the centre and without a vertex in the tile is **missed**. See §15, defect candidate C1-D1: the comment's "errs high rather than low" is wrong in that case, though the shipped dataset never hits it.
- **Real data** (probe3/probe4, the shipped functions over `open-pilgrimages` `675d4e3`): z11 packs equal a dense-sampling truth on every route checked (Francés 62, Nakahechi 3, Awa 6). Whole-route counts at z11 are below; the sweep takes under 10 ms.

| Route | Stages | z11 packs (union) | Σ per stage | Ratio | Estimate at the seed |
|---|---|---|---|---|---|
| camino-frances | 33 | 62 | 98 | 1.58 | ~248 MB |
| camino-norte | 34 | 53 | 90 | 1.70 | ~212 MB |
| kumano-kodo-kohechi | 4 | 5 | 8 | 1.60 | ~20 MB |
| kumano-kodo-nakahechi | 4 | 3 | 7 | 2.33 | ~12 MB |
| shikoku-88-awa | 5 | 6 | 12 | 2.00 | ~24 MB |
| shikoku-88-iyo | 14 | 24 | 40 | 1.67 | ~96 MB |
| shikoku-88-sanuki | 6 | 13 | 20 | 1.54 | ~52 MB |
| shikoku-88-tosa | 15 | 24 | 45 | 1.88 | ~96 MB |

The "Σ per stage" column is what a per-region byte sum would count if each region reports its shared packs (D2, §15); the 62-pack Francés and ~248 MB match the iOS design's own figures (design spec §2.2: "62 cells … ~248 MB").

- **The descriptors test fixture** (301 points along lat 33.8 from lon 135.5, step 0.001; simplified to 2 points, 3 parts) counts z11 3, z12 10, z13 16, z14 30, z15 58; `58 / 16 = 3.625` passes `4 ± 1.5`.

Android: all new. `tileCount(rings: List<List<WayCoordinate>>, zooms: IntRange): Int` in `P/data/honor/pilgrimage/PilgrimageTilesDescriptors.kt` (an `object`, like iOS's `enum`), with `tile`, `coordinate` and `tileTouches` private. `n = (1 shl z).toDouble()`.

### 6. The corridor hash

```swift
    static func corridorHash(for way: Way) -> String {
        corridorHash(rings(for: way))
    }
// …
    static func corridorHash(_ rings: [[CLLocationCoordinate2D]],
                             version: Int = PilgrimageTilesDescriptors.regionVersion) -> String {
        var data = Data()
        data.append(contentsOf: withUnsafeBytes(of: version) { Array($0) })
        for ring in rings {
            for point in ring {
                data.append(contentsOf: withUnsafeBytes(of: (point.latitude * 1_000_000).rounded()) { Array($0) })
                data.append(contentsOf: withUnsafeBytes(of: (point.longitude * 1_000_000).rounded()) { Array($0) })
            }
        }
        return SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
```
> Pilgrim/Models/Honor/PilgrimageTilesManager.swift:80-82,90-101@7c200bf

The exact byte layout (probed with Swift 6.3.3 on arm64; `MemoryLayout<Int>.size == 8`):

| Offset | Bytes | Content |
|---|---|---|
| 0 | 8 | `regionVersion` as a 64-bit **little-endian two's-complement** integer: `02 00 00 00 00 00 00 00` for version 2 (`withUnsafeBytes(of: Int)` is the machine's native layout, 8 bytes LE on every iOS device) |
| 8 + 16k | 8 | point k's `(lat × 1_000_000).rounded()` as the Double's raw IEEE-754 bits, **little-endian** (`42.0 → 00 00 00 00 f4 06 84 41`) |
| 16 + 16k | 8 | point k's `(lon × 1_000_000).rounded()`, same encoding |

- Points run ring by ring in emission order (§3), and within a ring in ring order, closing point included (5 points per part, so 80 bytes a part).
- **Latitude before longitude** for every point.
- `.rounded()` is Swift's default `toNearestOrAwayFromZero` (C `round`): ties go away from zero (`2.5 → 3`, `−2.5 → −3`), and a value in `(−0.5, 0)` rounds to **negative zero**, whose bytes (`00 … 00 80`) differ from `+0.0`'s. The rounded values stay Doubles; they are never converted to integers.
- The digest is the 32-byte SHA-256 (CryptoKit), written as 64 **lowercase** hex characters, two per byte.
- An empty corridor hashes the 8 version bytes alone.
- `corridorHash(for: way)` is `corridorHash(rings(for: way))` at version 2. The save builds its request from the same rings with `Self.corridorHash(rings)` (`PilgrimageTilesManager.swift:241-243@7c200bf`), so the stored hash and the compared hash come from one function.

**Probe vectors** (identical from `probe.swift` on Swift and `Probe.java` on JDK 17, so a Kotlin port that writes this layout reproduces iOS's hex exactly):

| Input rings (lat, lon) | Version | SHA-256 |
|---|---|---|
| `[[(0,0), (1,0), (1,1), (0,1), (0,0)]]` (the corridor test's hand-built clockwise square) | 2 | `295e29cc256224c652f256f17ff1d31c07d7918b9df0f46c8084677e8e1ef101` |
| same | 1 | `7109e14a5ad5907f733bc696e99c4daebf415e636ddaf99878311e49b5d3d436` |
| `[]` (no rings) | 2 | `d86e8112f3c4c4442126f8e9f44f16867da487f29052bf91b810457db34209a4` (also `printf '\002\0\0\0\0\0\0\0' \| shasum -a 256`) |
| `[[(0.0000005, −0.0000005)]]` (ties: `+1.0` and `−1.0`) | 2 | `859cbda43b8da9ab9f567720cc193ca28792108bdc4042cddaaa8c4335085dd1` |
| `[[(−0.0000004, 0.0000004)]]` (`−0.0` and `+0.0`) | 2 | `540efeba500e7dada9dedac0a1d2f2fbc0648077d188f2f4d03347b38d75a40b` |
| `corridor(stage(0).route, 500)` from the manager tests (§13) | 2 | `4bcadd51358decf228c35590cf597823a2da545a0632af3e2b4b743d4822effc` |
| same | 1 | `ef15543381b21f8900115f86e72ed6b42fc9c621065bc7decbedf1f5de1704ec` |

The square's input bytes, for a test that wants to check the layout itself: `0200000000000000` then, per point, lat and lon: `0000000000000000 0000000000000000`, `0000000080842e41 0000000000000000`, `0000000080842e41 0000000080842e41`, `0000000000000000 0000000080842e41`, `0000000000000000 0000000000000000` (`80842e41` reversed is `0x412E848000000000 = 1_000_000.0`).

**Recommendation: match iOS's bytes exactly.** The hash is device-local (it's written into the region's metadata on this phone and read back on this phone), so equality with iOS isn't required for correctness. But writing iOS's layout costs one `ByteBuffer`, makes the Swift vectors above portable test fixtures, and removes a gate row. Kotlin:

```text
fun corridorHash(rings: List<List<WayCoordinate>>, version: Int = PilgrimageTilesDescriptors.REGION_VERSION): String {
    val buffer = ByteBuffer.allocate(Long.SIZE_BYTES * (1 + 2 * rings.sumOf { it.size })).order(ByteOrder.LITTLE_ENDIAN)
    buffer.putLong(version.toLong())
    for (ring in rings) for (point in ring) {
        buffer.putLong(swiftRounded(point.lat * 1_000_000).toRawBits())
        buffer.putLong(swiftRounded(point.lon * 1_000_000).toRawBits())
    }
    return MessageDigest.getInstance("SHA-256").digest(buffer.array()).joinToString("") { "%02x".format(it) }
}

/** Swift's `rounded()`: ties away from zero, and `(-0.5, 0)` gives `-0.0`. */
private fun swiftRounded(x: Double): Double {
    val whole = truncate(x)
    return if (abs(x - whole) >= 0.5) whole + sign(x) else whole
}
```
(Proposed code, not a quote.) Points the implementer must keep:
- `putLong(version.toLong())`, not `putInt`: Swift's `Int` is 8 bytes.
- `ByteOrder.LITTLE_ENDIAN` explicitly; `ByteBuffer`'s default is big-endian.
- `toRawBits()` (as `SeekSeed` does), so `−0.0` keeps its sign bit; `putDouble` also writes raw bits, but the explicit form leaves no doubt.
- **Not** `Math.round`, `roundToLong()` or `roundToInt()`: they round ties toward **positive** infinity (`Math.round(−2.5) == −2`, Swift gives `−3`), and they return integers, so `−0.4` becomes `+0.0` (probe: raw bits `0` against Swift's `8000000000000000`). Every Francés longitude is negative, so negative values are the common case there. **Not** `kotlin.math.round` either (half-even: `2.5 → 2`).
- **Not** the existing `HonorOverviewModel.roundedHalfAwayFromZero`: `sign(v) * floor(abs(v) + 0.5)` keeps `−0.0` but rounds `0.49999999999999994` to `1.0` where C `round` gives `0.0` (probe), because `abs(v) + 0.5` rounds up in floating point. The trap only bites when `|v| < 0.5`, which here means a coordinate within 5 × 10⁻⁷° of zero, but the correct helper is no longer:

```kotlin
    /** Swift's `rounded()`. */
    private fun roundedHalfAwayFromZero(value: Double): Double = sign(value) * floor(abs(value) + 0.5)
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/honor/HonorOverviewModel.kt:164-165@ca6424db

- `"%02x".format(byte)` prints a negative `Byte` as two hex digits (Java's `Formatter` adds 2⁸ for a `Byte` argument) and applies no locale to `x`; the app already does exactly this:

```kotlin
            val digest = MessageDigest.getInstance("SHA-256").digest(transcript.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
```
> app/src/main/java/org/walktalkmeditate/pilgrim/core/threads/TranscriptContext.kt:95-96@ca6424db

```kotlin
    private fun MessageDigest.update(value: Double) {
        update(littleEndianBytes(value.toRawBits()))
    }

    private fun littleEndianBytes(value: Long): ByteArray =
        ByteBuffer.allocate(Long.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()
```
> app/src/main/java/org/walktalkmeditate/pilgrim/domain/seek/SeekSeed.kt:55-60@ca6424db

**Where it sits on Android:** a pure `object PilgrimageTilesCorridor` in `P/data/honor/pilgrimage/PilgrimageTilesCorridor.kt`, holding `HALF_WIDTH_METERS`, `rings(route)`, `corridorHash(rings, version)`, and the per-stage value's builder (§11). Not on the tiles manager: the callers build the per-stage values on IO before they reach the manager, and the manager (U44) and its tests use the same functions. iOS keeps them static on the manager; the move is file placement only, not behaviour.

**What the hash does and doesn't see:**
- It reads the corridor, not the route. Moving a route point that simplification drops changes nothing; moving an endpoint shifts the projection origin, and with it every ring coordinate. A redrawn stage whose simplified line is unchanged reads as saved, correctly, since its region covers the same ground.
- A ring coordinate moving by 10⁻⁶° changes the hash. A move of less than half that changes it only if it crosses a rounding boundary: from an exact microdegree (`42.0`), `+4 × 10⁻⁷` keeps the hash and `+6 × 10⁻⁷` changes it.
- The plan's test idea ("the hash changes when one point moves by 1e-6° and not when it moves by less than half of that") holds only on hand-built rings at exact microdegrees, never through `corridor` (§16, correction 4).

### 7. Region ids: `stageWayId`, `regionPrefix`, `stageIndex`

```swift
    static func regionPrefix(routeId: String) -> String { "pilgrimage:\(routeId):" }
```
> Pilgrim/Models/Honor/PilgrimageTilesManager.swift:103@7c200bf

```swift
    private static func stageIndex(of regionId: String, prefix: String) -> Int? {
        guard regionId.hasPrefix(prefix) else { return nil }
        return Int(regionId.dropFirst(prefix.count))
    }
```
> Pilgrim/Models/Honor/PilgrimageTilesManager.swift:385-388@7c200bf

```swift
    static func stageWayId(routeId: String, stageIndex: Int) -> String {
        "pilgrimage:\(routeId):\(stageIndex)"
    }
```
> Pilgrim/Models/Honor/WayStore.swift:67-69@7c200bf

- A stage's region id **is its Way's id**: the save requests `TileRegionRequest(id: way.id, …)` (`PilgrimageTilesManager.swift:242@7c200bf`), and status reads `byId[$0.id]`. For package stages that id is always `stageWayId(routeId, index)`: the importer refuses a stage file whose `id`, `stage.routeId` or `stage.index` disagree (`PilgrimageWayImporter.swift:177-179@7c200bf`).
- The prefix ends in `:`, so `camino` never matches `camino-frances`'s regions.
- `stageIndex` is Swift's `Int(String)` on the rest of the id: ASCII digits with an optional leading `+` or `-`, no spaces, nil on overflow. `"…:3"` → 3, `"…:03"` → 3, `"…:3:x"` → nil (the sweep then treats it as foreign and removes it; C2). Kotlin's `toIntOrNull()` matches on every id the app writes. It also accepts non-ASCII Unicode digits where Swift doesn't; no writer produces them, so this needs no code.
- Android already has the id, with the same format:

```kotlin
        /** A stage Way's id, the index in plain decimal (`WayStore.swift:67-69@7c200bf`). */
        fun stageWayId(routeId: String, stageIndex: Int): String = "$STAGE_ID_PREFIX$routeId:$stageIndex"
```
> app/src/main/java/org/walktalkmeditate/pilgrim/data/honor/WayStore.kt:719-720@ca6424db

`regionPrefix` should be built from the same constant, `"pilgrimage:$routeId:"`, so the two can't drift. `STAGE_ID_PREFIX` is private (`WayStore.kt:736@ca6424db`); U44 either widens it to `internal` or writes the literal once in the tiles code with a test that `stageWayId(r, 3).startsWith(regionPrefix(r))`.

### 8. The pack count and the estimate

```swift
    func packCount(for stages: [Way]) -> Int {
        let root = PilgrimageTilesDescriptors.packRootZoom
        return PilgrimageTilesDescriptors.tileCount(rings: stages.flatMap { Self.rings(for: $0) }, zooms: root...root)
    }

    func estimateBytes(for routeId: String, stages: [Way]) -> Int {
        packCount(for: stages) * bytesPerPack(routeId: routeId)
    }
```
> Pilgrim/Models/Honor/PilgrimageTilesManager.swift:171-178@7c200bf

- `packCount` is the number of distinct z11 tiles that any part of any stage's corridor touches, in **one sweep** over every stage's rings concatenated, so a cell two stages share counts once. It reads no store and no defaults; it's pure geometry over the stages' rings. (It's an instance method on iOS only by placement.)
- `estimateBytes` is `packCount × bytesPerPack(routeId)` in 64-bit `Int`. Android must use `Long`: at the seed, 537 packs would overflow an `Int` (537 × 4,000,000 > 2³¹), and an overflowed estimate would read "~1 MB" through `megabytes`' floor. The store's 750-unique-pack ceiling (375 z11 cells at two tilesets each, per the design's "124 packs (62 z11 cells × 2 tilesets)") means a route that large could never be saved, but its estimate is drawn before any save is tried, so the label must still be right.
- **The two-stage fixture:** `stages(2)` alone gives `[2, 1]`, together `2` (probe confirms; `testAZ11CellTwoStagesShareIsCountedOnce`). Why: stage 0 starts at exactly lon 0.0, the z11 boundary between x = 1023 and x = 1024 (z11 cells are 360/2048 = 0.17578° wide), and its start square reaches 500 m west to lon −0.00604, into x = 1023. Stage 1 runs from lon 0.04 to 0.07627, wholly inside x = 1024. All of it sits in row y = 760 (lat 41.9955–42.0045). So stage 0 is cells {1023, 1024}, stage 1 is {1024}, and the union is 2. `stages(3)` and `stages(4)` are also 2 together; `stages(7)` reaches x = 1025 and gives 3.
- The estimate is computed from the stages passed, which on iOS are the stage Ways that loaded (`compactMap` over `0..<stageCount`, `PilgrimageRouteView.swift:372-375@7c200bf`), and is 0 when the route isn't installed.

### 9. Bytes per pack and calibration

```swift
    static func bytesPerPackKey(routeId: String) -> String { "pilgrimage.tiles.bytesPerPack.\(routeId)" }

    func bytesPerPack(routeId: String) -> Int {
        let stored = defaults.integer(forKey: Self.bytesPerPackKey(routeId: routeId))
        return stored > 0 ? stored : Self.seedBytesPerPack
    }
```
> Pilgrim/Models/Honor/PilgrimageTilesManager.swift:158-163@7c200bf

```swift
    /// After a save of this route lands: its real bytes over its pack count
    /// replace the seed. Another route's key is never touched.
    func calibrate(routeId: String, stages: [Way]) {
        let byId = regionsById()
        let bytes = stages.compactMap { byId[$0.id] }.reduce(0) { $0 + $1.completedResourceSize }
        let packs = packCount(for: stages)
        guard bytes >= 1, packs >= 1 else { return }
        defaults.set(bytes / packs, forKey: Self.bytesPerPackKey(routeId: routeId))
    }
```
> Pilgrim/Models/Honor/PilgrimageTilesManager.swift:180-188@7c200bf

```swift
            calibrate(routeId: routeId, stages: stages)
            phase = .idle
```
> Pilgrim/Models/Honor/PilgrimageTilesManager.swift:254-255@7c200bf

- **Key:** `pilgrimage.tiles.bytesPerPack.<routeId>`, one per route; no other route's key is ever written. Android uses the same string in a Preferences DataStore (plan, Key Technical Decisions). Use `longPreferencesKey`, and never change the type later: an `Int` key and a `Long` key of the same name can't read each other's value.
- **Read:** `UserDefaults.integer(forKey:)` returns `0` for a missing key (and for a non-number). Any stored value `<= 0` reads as the seed `4_000_000`. Android: `prefs[key]?.takeIf { it > 0 } ?: SEED_BYTES_PER_PACK`.
- **When:** only at the end of a save loop that finished every stage, after the last stage's step and before `phase = .idle`. A cancelled, failed or refused save never calibrates. A save where every stage was already current (no region loaded) still calibrates, from what's on disk.
- **Which bytes:** `completedResourceSize` of every region whose id is one of the passed stages' ids, **whatever its completeness or hash**, summed; it's one `regionsById()` read (the save's second store read; `testASaveReadsTheStoreOnceBeforeTheLoopAndOnceToCalibrate` pins 2). Regions with the route's prefix at retired indices aren't in `stages`, so they don't count (the footprint's sum does; C2).
- **Division:** integer `bytes / packs`, truncating (both positive, so it floors). `packs` is the same union count the estimate uses, so after a save `estimate = packs × ⌊bytes / packs⌋ ≈ bytes`, a few bytes low.
- **Write guard:** only when `bytes >= 1` **and** `packs >= 1`. A route whose stages have no line (`route: []` → no rings → 0 packs) writes nothing, and so does a store with no bytes yet. If `bytes < packs` the quotient is `0`, which **is** written and reads back as the seed.
- **Backup:** on iOS the figure lives in `UserDefaults`, which iCloud backup and a device transfer both carry. On Android a DataStore file under `filesDir/datastore/` rides a device transfer (`data_extraction_rules.xml` includes `file "."`) but not cloud backup, which excludes every file (`backup_rules.xml@ca6424db`). After a cloud restore the route reads the seed again until its next save. That's harmless and follows the app's existing rules (gate row A5). Give it its own small DataStore with an explicit scope (Stage 8-B), not the device-token file the transfer excludes.
- **Android:** the read is a suspending DataStore read, so `bytesPerPack` and `estimateBytes` become `suspend` (or take the value as a parameter). The write is a suspending `edit`; call it from the save's coroutine before publishing idle, as iOS orders it. The plan's injected calibration-store interface (U44) fits: `suspend fun bytesPerPack(routeId): Long` and `suspend fun setBytesPerPack(routeId, value: Long)`.
- **Precondition the loader must keep (C2, C3):** calibration reads the store right after the last region's load completes, so the loader's cache must hold that region by then. The fake does (`FakeTileRegionLoader.swift:123-130@7c200bf` stores the summary before it calls the completion). On Android, the real loader's success callback must update its cache before the manager's continuation resumes, or calibration silently drops the last stage's bytes.
- **D2 interacts here.** If Mapbox reports each region's own total, packs shared with a neighbour included, then `bytes` is the per-region sum and calibration inflates bytes-per-pack by the "Ratio" in §5's table (1.5–2.3× on the shipped dataset). PR #86's device pass fits that: the Nakahechi "estimate ~8 MB, saved **18 MB**", and §5's Nakahechi ratio is 7 / 3 = 2.33. U48 measures it (§15).

### 10. `megabytes`

```swift
    static func megabytes(_ bytes: Int) -> String {
        "\(max(1, Int((Double(bytes) / 1_000_000).rounded()))) MB"
    }
```
> Pilgrim/Scenes/Honor/PilgrimageMapsRow.swift:5-7@7c200bf

- **Decimal** megabytes (`/ 1_000_000`, not 1,048,576), rounded half away from zero to a whole number, then floored at **1**: nothing ever reads "0 MB". Then a plain ASCII space and `MB` (bytes checked with `od`: U+0020).
- Swift's `"\(Int)"` is always ASCII digits with no grouping, whatever the locale.
- Probed outputs:

| bytes | output |
|---|---|
| 0 | `1 MB` |
| 400_000 | `1 MB` |
| 499_999 / 500_000 | `1 MB` / `1 MB` (0.5 rounds to 1; the floor gives 1 anyway) |
| 1_499_999 | `1 MB` |
| 1_500_000 | `2 MB` (tie away from zero) |
| 1_900_000 | `2 MB` |
| 2_500_000 | `3 MB` (tie away from zero; Kotlin's half-even `round` would give 2) |
| 26_100_000 / 26_400_000 | `26 MB` |
| 999_999_999 | `1000 MB` (no thousands separator, never "GB") |
| −3_000_000 | `1 MB` (unreachable; the floor) |

- Callers: the route row's estimate label (`~` prefix) and saved line, its accessibility label, the Settings Data row detail and the Maps screen's byte line (C4). U47 reuses U46's function.
- **Android:** bytes are `Long` (§8). For non-negative input, `Math.round(bytes / 1_000_000.0)` (half-up) equals Swift's rounding, so `"${max(1L, Math.round(bytes / 1_000_000.0))} MB"` is exact; `swiftRounded` (§6) also works. Never `kotlin.math.round`. Format the number with `toString()` (ASCII, no grouping) and, if the text goes through a string resource, pass it as `%1$s` as the Ways row does (`settings_ways_megabytes` is `%1$s MB`, `strings.xml:1303@ca6424db`), never as `%1$d`, which would localize the digits.

### 11. The per-stage value U44 takes (and why it needs the id)

The plan has the manager take "one value per stage (index, convex rings, corridor hash)". Here is everything iOS's manager reads from a stage `Way`, by entry point:

```swift
    private func region(for way: Way) -> TileRegionSummary? {
        loader.regions().first { $0.id == way.id }
    }

    /// Complete by resource count and loaded for the stage's current line.
    private func isSaved(_ way: Way, region: TileRegionSummary?) -> Bool {
        guard let region, region.isComplete else { return false }
        return region.corridorHash == Self.corridorHash(for: way)
    }
```
> Pilgrim/Models/Honor/PilgrimageTilesManager.swift:113-121@7c200bf

```swift
            for way in stages.sorted(by: { ($0.stage?.index ?? 0) < ($1.stage?.index ?? 0) }) {
                guard !isWalkActive() else { throw PilgrimageError.walkInProgress }
                if !isSaved(way, region: byId[way.id]) {
                    let rings = Self.rings(for: way)
                    let request = TileRegionRequest(id: way.id, rings: rings,
                                                    corridorHash: Self.corridorHash(rings), acceptExpired: true)
```
> Pilgrim/Models/Honor/PilgrimageTilesManager.swift:238-243@7c200bf

| Entry point | Reads from each stage |
|---|---|
| `isStageSaved(way)` | `way.id`, the corridor hash |
| `status(for:stages:)` | `id`, hash, and `stages.count` (the `of:` figure) |
| `footprint(routeId:stages:)` | `id` and hash, for the saved count; its bytes come from the route prefix, not the stages. The Maps screen's "S of T stages" takes `T` from the list's size (`OfflineMapsView.swift:34@7c200bf`, C4) |
| `packCount` / `estimateBytes` | rings |
| `calibrate` | `id`, rings |
| `save` | `stage?.index ?? 0` (the sort key), `id` (the region id), rings (the request's geometry), hash (computed from those rings) |

Nothing else: no title, no moments, no route points beyond the rings, no `source`. So the value is:

```text
data class TileStage(
    val id: String,                          // the stage Way's id = the region id, "pilgrimage:<routeId>:<index>"
    val index: Int,                          // way.stage?.index ?: 0, the save's sort key
    val rings: List<List<WayCoordinate>>,    // PilgrimageTilesCorridor.rings(way.route)
    val corridorHash: String,                // PilgrimageTilesCorridor.corridorHash(rings)
)
```
(Proposed, not a quote. The name is a suggestion; C2's write-up of U44 may choose another.)

- **The plan omits the id.** `isStageSaved(way)` has no `routeId` parameter, so the value has to carry its region id, or the manager can't find the region. Deriving it as `stageWayId(routeId, index)` would work for package stages only because the importer forces `id == stageWayId(routeId, stage.index)`; carrying `way.id` is simpler and is literally what iOS reads. Correction 1 (§16).
- **Build it from a decoded Way in one place**, `PilgrimageTilesCorridor.stage(way: Way): TileStage`, on IO, and drop the Way. The `rings` and `corridorHash` must come from one call to `corridor` so they agree; the save then sends `TileStage.corridorHash` as the request's hash, which equals iOS's `Self.corridorHash(rings)` by construction.
- **The list:** one value per stage Way that **loads**, skipping a stage whose Way can't be read, as iOS's `compactMap` does (`PilgrimageRouteView.swift:373-375@7c200bf`, `OfflineMapsView.swift:41-43@7c200bf`). `status`'s `of:` is that list's size, not `route.stageCount`. Every surface (route page, overview, "the day", Settings) builds its list the same way, through one helper.
- `index` is only a sort key. iOS's `sorted(by:)` on unique indices gives one order; Kotlin's stable `sortedBy { it.index }` gives the same.
- Size: the Francés' 33 values hold 4,869 parts × 5 = 24,345 coordinates, about 1 MB on the heap, against the 1.1 MB of stage JSON they replace (`open-pilgrimages` `675d4e3`). Decoding dominates the cost; corridors, hashes and the z11 sweep take tens of milliseconds.
- **Test adaptation:** iOS's tests pass `[Way]`. Android's ported tests build the same fixture Ways (§13) and map them through `PilgrimageTilesCorridor.stage(...)` before calling the manager, so the fixtures and the expected values stay verbatim.

### 12. Flow-analysis item 10: the estimate off Main, from corridors

What iOS does: `reload()` runs on the main actor from the page's `.task` and after every download, update, replace (success or failure) and remove (`PilgrimageRouteView.swift:146,353,359@7c200bf`). It decodes every stage Way, then computes the estimate (33 corridors and a z11 sweep) and the status (33 corridors, 33 SHA-256s, one store read), all on Main (survey D8; not filed). The estimate is recomputed **only** in `reload()`. The status is also refreshed on `regionsChanged` and when the phase returns to idle:

```swift
        mapsEstimateBytes = isInstalled ? tiles.estimateBytes(for: entry.id, stages: stageWays) : 0
        refreshMapsStatus()
```
> Pilgrim/Scenes/Honor/PilgrimageRouteView.swift:375-376@7c200bf

```swift
        .onReceive(tiles.regionsChanged) { _ in refreshMapsStatus() }
        // A save whose regions were all present loads only packs and ends
        // with no regions signal, and a cancel ends with none either.
        .onChange(of: tiles.phase) { _, phase in
            if phase == .idle { refreshMapsStatus() }
        }
```
> Pilgrim/Scenes/Honor/PilgrimageRouteView.swift:149-154@7c200bf

What Android must do (U46, with U44's API):
1. Build the `TileStage` list on the injected IO dispatcher from the installed route's stage Ways (§11), once per installed `(routeId, release)`, and keep only the values. An Update or Replace changes the release or route id, so the cache can't outlive a redraw (AE10). Remove empties it.
2. Cache `packCount(values)` with the values: it's pure geometry, so the release key is a sound key for it.
3. **Re-read `bytesPerPack(routeId)` on every reload**, never cache it by release. The calibrated figure changes after a save while the release doesn't, and iOS's `reload()` re-reads it each time. After a save and a "Delete maps" in Settings, the route page's `.task` runs `reload()` again when the page re-appears (SwiftUI runs `.task` on each appearance; framework behaviour, not Pilgrim source), so the re-shown "Save maps for the way · ~N MB" uses the calibrated figure. The plan's "Both are keyed on the installed release" is right for the values and the pack count and wrong for this factor (correction 2).
4. Compute the status from the cached values on each of iOS's three triggers. With precomputed hashes it's one store read and string compares, cheap on the manager's thread.
5. Not installed: estimate 0 and status none, as iOS (`isInstalled ? … : 0`).
6. The estimate is only ever shown in the `.none` label, since `.saved` shows the real bytes and `.partial` shows the count (C4). A stale estimate is visible only after maps go from saved or partial back to none without a reload.

### 13. Test inventory and fixtures

#### 13.1 Fixtures (port verbatim)

The corridor tests' own helpers. The coordinates are generated, not literal: `straight(km:lat:)` is `Int(km × 10) + 1` points along latitude `lat` (default 42) from longitude 0, 100 m apart; `offset` moves by metres on the same 111,320 m/degree scale as the corridor:

```swift
    private func straight(km: Double, lat: Double = 42) -> [CLLocationCoordinate2D] {
        let metersPerDegreeLon = 111_320 * cos(lat * .pi / 180)
        let steps = Int(km * 10)
        return (0...steps).map { i in
            CLLocationCoordinate2D(latitude: lat, longitude: Double(i) * 100 / metersPerDegreeLon)
        }
    }

    private func offset(_ from: CLLocationCoordinate2D, northMeters: Double, eastMeters: Double) -> CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: from.latitude + northMeters / 111_320,
                               longitude: from.longitude + eastMeters / (111_320 * cos(from.latitude * .pi / 180)))
    }
```
> UnitTests/Honor/WayGeometryCorridorTests.swift:7-18@7c200bf

```swift
    private func isConvex(_ ring: [CLLocationCoordinate2D]) -> Bool {
        guard let first = ring.first, ring.count > 3 else { return false }
        let lonScale = cos(first.latitude * .pi / 180)
        let vertices = ring.dropLast().map { (x: $0.longitude * lonScale, y: $0.latitude) }
        var edges: [(x: Double, y: Double)] = []
        for i in vertices.indices {
            let a = vertices[i], b = vertices[(i + 1) % vertices.count]
            let edge = (x: b.x - a.x, y: b.y - a.y)
            // A zero-length edge has no direction to turn from.
            if edge.x != 0 || edge.y != 0 { edges.append(edge) }
        }
        guard edges.count > 2 else { return false }
        var sign = 0.0
        for i in edges.indices {
            let current = edges[i], next = edges[(i + 1) % edges.count]
            let cross = current.x * next.y - current.y * next.x
            if cross == 0 { continue }
            if sign == 0 { sign = cross } else if (cross > 0) != (sign > 0) { return false }
        }
        return sign != 0
    }
// …
    private func signedArea(_ ring: [CLLocationCoordinate2D]) -> Double {
        guard let first = ring.first else { return 0 }
        let lonScale = cos(first.latitude * .pi / 180)
        let points = ring.map { (x: $0.longitude * lonScale, y: $0.latitude) }
        var sum = 0.0
        for i in 0..<(points.count - 1) {
            let a = points[i], b = points[i + 1]
            sum += a.x * b.y - b.x * a.y
        }
        return sum / 2
    }
```
> UnitTests/Honor/WayGeometryCorridorTests.swift:24-44,50-60@7c200bf

The manager tests' stage fixture (also used by the lifecycle tests): stage `i` of `stages(n)` is 31 points along latitude 42 from longitude `0.04·i` in steps of `0.001209` (about 100 m), `t = 60·k`, id `pilgrimage:<routeId>:<i>`:

```swift
    /// A 3 km straight stage east along latitude 42, 31 points 100 m apart.
    func stage(_ index: Int, count: Int = 3, routeId: String = "camino-frances", lonOffset: Double = 0) -> Way {
        let points = (0...30).map { i in
            WayPoint(lat: 42, lon: lonOffset + Double(i) * 0.001209, alt: nil, t: Double(i) * 60)
        }
        let stage = WayStage(routeId: routeId, index: index, count: count, name: "stage \(index)", theme: "t",
                             narrative: "n", closing: "c", warnings: [], distanceKm: 3, gainMeters: 50,
                             hours: WayStageHours(min: 1, max: 2), difficulty: "easy",
                             start: WayStagePlace(name: "a", at: WayCoordinate(lat: 42, lon: lonOffset)),
                             end: WayStagePlace(name: "b", at: WayCoordinate(lat: 42, lon: lonOffset + 0.03627)))
        return Way(id: WayStore.stageWayId(routeId: routeId, stageIndex: index),
                   source: .pilgrimage(routeId: routeId, stageIndex: index),
                   title: "stage \(index)", departedAt: Date(timeIntervalSince1970: 0), tzIdentifier: nil,
                   expires: nil, route: points, totalDistanceMeters: 3000, theirActiveSeconds: 1800,
                   moments: [], weather: nil, spans: nil, marks: nil, stage: stage)
    }

    func stages(_ count: Int, routeId: String = "camino-frances") -> [Way] {
        (0..<count).map { stage($0, count: count, routeId: routeId, lonOffset: Double($0) * 0.04) }
    }
```
> UnitTests/Honor/PilgrimageTilesManagerTests.swift:26-45@7c200bf

Android's `Way` and `WayStage` take the same fields in the same order (`Way.kt:201-216,268-283@ca6424db`); `departedAt` is `Instant.EPOCH`. The fake loader's defaults that the calibration tests rely on: a completed region reports `bytesPerRegion = 100_000` unless set, a seeded region `bytes: 100_000`, both `10` of `10` resources (`FakeTileRegionLoader.swift:51-52,153-159@7c200bf`; C2 ports the fake).

`stage-00.json`: Android's copy is byte-identical to iOS's (`app/src/test/resources/honor/pilgrimage/stage-00.json`). Load it with `PilgrimagePackageHarness.fixture("stage-00.json")` and decode with `PilgrimageWayImporter.way(from = …, routeId = "camino-frances", stageIndex = 0)` (`PilgrimageWayImporter.kt:80@ca6424db`). Its route is 11 points on the equator from lon 0 to 0.00898.

#### 13.2 `UnitTests/Honor/WayGeometryCorridorTests.swift` (9) → `T/domain/honor/WayGeometryCorridorTest.kt` (U43)

| Test | Asserts |
|---|---|
| `testEveryPartIsAClosedConvexRing` | `straight(km: 3)` (31 points) gives 3 parts ("one quad for the simplified two-point line, one square per end"); each has 5 points, first == last (lat and lon, exact), is convex, and has positive signed area |
| `testSignedAreaIsNegativeForAHandBuiltClockwiseSquare` | the test's own `signedArea` is negative for `[(0,0), (1,0), (1,1), (0,1), (0,0)]`: pins the helper, not the app |
| `testAStraightLineIsCoveredToHalfWidthAndNotBeyond` | on `straight(km: 3)`, `line[15]` is inside, 480 m north of it inside, 520 m north outside; 480 m east of the last point inside, 1,020 m east outside |
| `testARightAngleBendKeepsItsOuterCornerAndEveryPointOnTheLine` | `straight(km: 2)` then 20 points 100 m apart going north from its end: from the corner, (−212 m N, +212 m E) inside, (−495, +495) inside (the square's 707 m diagonal), (−520, +520) outside; every route point inside |
| `testAHairpinCoversItsOwnPointsWithNoSelfIntersectingPart` | `straight(km: 1)` then 10 points 60 m north of its end going west 100 m at a time: every point inside; every part 5 points and convex |
| `testSimplificationDropsWigglesUnderTolerance` | `straight(km: 1)` with every odd index moved 10 m north simplifies at 25 m to 2 points |
| `testADecodedStageCorridorCoversItsWholeLineIncludingTheEnds` | `stage-00.json` through the importer: every route point inside its corridor |
| `testOnePointBecomesOneSquare` | `[(42, 0)]` → 1 part of 5 points |
| `testTwoIdenticalPointsYieldSquaresAndNoRectangle` | `[(42,0), (42,0)]` → 2 parts of 5 points; the point is inside |

The test file passes coordinate lists, so on Android the inputs become `List<WayCoordinate>`; nothing else changes. Remove the "not ported" notes at `WayGeometry.kt:21@ca6424db` and `WayGeometryTest.kt:14-16@ca6424db`.

#### 13.3 `UnitTests/Honor/PilgrimageTilesDescriptorsTests.swift` (5) → `T/data/honor/pilgrimage/PilgrimageTilesDescriptorsTest.kt` (U43)

| Test | Asserts |
|---|---|
| `testTheRangeIsTheOneBandWhosePacksFollowTheCorridor` | `streetsZoom == 11...14` |
| `testThePackRootIsTheRangesFloor` | `packRootZoom == 11` and `streetsZoom.lowerBound == packRootZoom` |
| `testTheRegionVersionMovedPastTheDescriptorsThatShippedFirst` | `regionVersion == 2` |
| `testGlyphsRasterizeIdeographsLocally` | `rasterizesIdeographsLocally == true` |
| `testTileCountGrowsFourfoldPerZoomOnALongCorridor` | 301 points `(33.8, 135.5 + 0.001·i)`; z13 count `> 5` and `z15 / z13 == 4 ± 1.5` (probe: 16 and 58) |

#### 13.4 C1's tests in `UnitTests/Honor/PilgrimageTilesManagerTests.swift`

| Test (lines) | Asserts | Android home and adaptation |
|---|---|---|
| `testThePackCountIsTheZ11CellsOfTheWholeCorridor` (:79-87) | `packCount(stages(3)) > 0` and equals `tileCount(rings: every stage's corridor flattened, zooms: 11...11)` (probe: 2) | U43, if `packCount` is the pure function over `TileStage` values (§17); the inputs are `stages(3)` mapped to values |
| `testAZ11CellTwoStagesShareIsCountedOnce` (:93-97) | `[packCount([s0]), packCount([s1])] == [2, 1]` and `packCount(stages(2)) == 2` (§8 explains why) | U43, same adaptation |
| `testTheEstimateIsPacksTimesTheRoutesOwnBytesPerPackAndTheSeedByDefault` (:99-109) | the estimate is `packs × 4_000_000` with nothing stored; `packs × 2_700_000` after `pilgrimage.tiles.bytesPerPack.camino-frances = 2_700_000`; the Nakahechi (same geometry, other route id) still `packs × seed` | U44 (needs the calibration store); set the key in the in-memory store |
| `testTheCorridorHashCarriesTheRegionVersion` (:114-125) | `corridorHash(for: way) == corridorHash(rings, version: 2)`, `!= corridorHash(rings, version: 1)`; a complete region stored with the version-1 hash reads `isStageSaved == false` | split: the two hash equalities in U43 (`stage(0)` through `PilgrimageTilesCorridor`); the `isStageSaved` half in U44 against the fake |
| `testACompletedSaveCalibratesThisRouteOnly` (:387-399) | a save of `stages(2)` with `bytesPerRegion = 400_000` stores `800_000 / packCount(two)` (= 400,000) under this route's key; the Nakahechi key stays absent (`0`) | U44 |
| `testCalibrateWritesNothingWithoutBytesAndPacks` (:404-415) | calibrate with no regions writes nothing; with a 5 MB region under stage 0's id but a stage whose `route` is `[]` (no rings, 0 packs) writes nothing | U44; the lineless stage is `TileStage(id = two[0].id, index = 0, rings = emptyList(), corridorHash = <hash of []>)`, or `stage(Way(route = emptyList(), …))` |
| `testASaveReadsTheStoreOnceBeforeTheLoopAndOnceToCalibrate` (:419-428) | a 3-stage save reads the store exactly twice | U44 (C2 owns; listed because calibration's read is one of the two) |

`testARedrawnStageIsReloadedAndAnUnchangedOneIsNot` (:250-264) and `testStatusCountsOnlyCompleteRegionsWhoseCorridorStillMatches` (:129-139) seed regions with `corridorHash(for:)` values; they're C2's, and use the hash through the same `TileStage` builder.

#### 13.5 Tests elsewhere that exercise C1's code

- `PilgrimageMapsRowTests.testTheEstimateIsRoundedAndTilded` (26,400,000 → `~26 MB`, 1,900,000 → `~2 MB`, 400,000 → `~1 MB`) and `testSavedShowsRealBytesWithNoTilde` (26,100,000 → `26 MB`) run through `megabytes`; `OfflineMapsViewModelTests.testTheRowSaysNoneSavedOrTheRouteAndItsBytes` too (`… · 26 MB`). C4 ports them (U46, U47). iOS has no test of a tie; U46's planned "rounds 2.5 MB up" (→ `3 MB`) is an Android addition that matches Swift.
- `MapboxTileRegionLoaderTests.testDescriptorOptionsAreTheTwoStylesAtTheSpecsRangeAndNameNoTileset` and `testTheGlyphsModeComesFromThePinnedConstant` read `streetsZoom` and `rasterizesIdeographsLocally` (C3, U45).

#### 13.6 Android additions worth adding in U43 (none on iOS)

- The hash vectors in §6, asserted as literal hex (layout parity, ties, `−0.0`, the empty corridor, both versions).
- `corridor([])` is `[]` (the plan lists it as ported; iOS has no such test).
- A closed loop (first point == last) simplifies through the degenerate-segment branch and still covers its points.
- `stageIndex`/`regionPrefix` round-trip with `stageWayId`.
- An exact-geometry check on `corridor(stage(0).route, 500)`, from the Swift probe (`%.17g`; the JVM's values are bit-identical, since its hash of them matches): part 0 (the quad) has latitudes `41.995508444125043` / `42.004491555874957` and longitudes `0` / `0.036269999999999997`; part 1 (the start square) longitudes `±0.0060439845921953653`; part 2 (the end square) longitudes `0.030226015407804632` / `0.042313984592195361`; all three share those two latitudes. Compare with `assertEquals(expected, actual, 0.0)`, or through the hash vector, which pins every bit.

### 14. Strings

C1 owns one formatter and no other user-visible text.

| String | Where it shows | Format |
|---|---|---|
| `<N> MB` | inside the route row's `Save maps for the way · ~<N> MB` and `maps saved · <N> MB`, the saved row's accessibility label, the Data card's Maps detail `<route> · <N> MB`, and the Maps screen's byte line (all C4) | `N = max(1, round-half-away(bytes / 1_000_000))`, ASCII digits, no grouping, one U+0020 space, `MB` (§10) |

### 15. iOS defects (matched as shipped)

| # | Candidate | Evidence | What a user sees | Severity | Verdict |
|---|---|---|---|---|---|
| **C1-D1** (new) | `tileTouches` misses tiles that a long, thin corridor part crosses without containing a corner or the centre and without a vertex inside the tile; its comment says "the estimate errs high rather than low" | §5 quote (`PilgrimageTilesDescriptors.swift:91-106@7c200bf`). `probe2.swift`: a 100 km straight diagonal stage (simplified to 2 points, 3 parts) counts 3–7 z11 cells where dense sampling finds 10–12, in 40 of 40 placements. On the shipped dataset the count equals the sampled truth on every route checked (Francés 62, Nakahechi 3, Awa 6), because simplified vertices are far closer together than a 14 km z11 cell | Nothing on today's routes. A route with a long straight, simplified segment would get a low pre-save estimate. Calibration divides by the same count, so the route's own figure absorbs the error after its first save | Low | Match; file as a low item (the comment is wrong; a real polygon–rectangle test or the store's own `estimateTileRegion` would fix it) |
| **D2** (survey) | `calibrate`, `status` and `footprint` sum `completedResourceSize` per region; adjacent stages share z11 packs | §9 quote. §5 table: the per-stage pack sum runs 1.54–2.33× the union on every shipped route. PR #86's device pass: the Nakahechi "estimate ~8 MB, saved **18 MB**"; its ratio is 7 / 3 = 2.33. Mapbox's `TileRegion.completedResourceSize` is per region (`long`, common 24.23.1); whether it includes shared packs is runtime behaviour, unproven from source | "maps saved · N MB" and Settings overstate disk by up to ~2×; after the first save, calibration bakes the same factor into that route's estimate, so a re-shown estimate (after Delete) reads high | Medium (copy and estimate only) | **Confirmed as plausible, not proven.** Match. U48 compares the summed figure with the store directory's size (`du` on `files/.mapbox/`) before filing, as the plan says. Expected if D2 holds: Francés summed ≈ 98/62 × disk |
| **D8** (survey) | The estimate and status run on the main actor in `reload()` | §12 quote (`PilgrimageRouteView.swift:375-376@7c200bf`); `PilgrimageRouteView` and `PilgrimageTilesManager` are `@MainActor` | A hitch opening the route page (decode 33 Ways, 33 corridors and hashes, one sweep) | Perf note | Confirmed; not filed (as the survey says). Android computes off Main (§12; gate row A1) |

Survey D1, D3–D7 and D9 and flow items other than 10 aren't in C1's files; C2 and C4 cover them. The survey's §2.1–§2.2 and §3 "Estimate and calibration" check out against the code, with one slip: §2.1's "An empty input gives `[]` (tests :160-176)" is true of the code, but no iOS test covers it. Not defects, recorded so nobody files them: the corridor's longitude scale uses the stage's first latitude for its whole length (a 1° latitude span skews the east-west half width by under 2%); `calibrate` writes `0` when `bytes < packs`, which reads back as the seed; the hash carries only `regionVersion`, so a descriptor change needs a manual bump (the design says so, `PilgrimageTilesDescriptors.swift:24-31@7c200bf`).

### 16. Corrections to the Android plan

1. **The per-stage value needs the region id.** Plan: "take one value per stage (index, convex rings, corridor hash)" (Key Technical Decisions, :163), "one precomputed value per stage (index, convex rings, corridor hash)" (U44, :368), "(index, rings, hash)" (U47, :533). **Fix:** `(id, index, rings, corridorHash)`, where `id` is the stage Way's id. iOS's `isStageSaved(way)`, `status`, `footprint`, `calibrate` and `save` all look regions up by `way.id`, and `isStageSaved` has no route id to rebuild it from (§11). Also say that the list holds only the stage Ways that load (iOS's `compactMap`), and that `status`'s `of:` is its size.
2. **The estimate's bytes-per-pack is not keyed on the release.** Plan: "Both are keyed on the installed release, so an Update re-reads them (AE10)." (:188). **Fix:** the per-stage values and `packCount` are keyed on the installed `(routeId, release)`; `bytesPerPack(routeId)` is re-read on every reload, as iOS's `reload()` does, because calibration changes it without a release change; the status is recomputed on every reload, on `regionsChanged` and on the phase returning to idle (§12).
3. **Rounding.** Plan: "use `Math.round` / `roundToLong` semantics for Swift's `.rounded()` (half away from zero), never `kotlin.math.round` (half-even)." (U43, :338). **Fix:** `Math.round` and `roundToLong` round ties toward positive infinity (`Math.round(-2.5) == -2`; Swift gives `-3`) and return integers, so `-0.4` hashes as `+0.0` where Swift writes `-0.0`'s bytes. The Francés is all negative longitudes. Use a `swiftRounded` helper (`truncate`, then `± 1` when the remainder is at least 0.5), kept as a `Double` (§6). Don't reuse `HonorOverviewModel.roundedHalfAwayFromZero`, which misrounds `0.49999999999999994`. (For `megabytes`' non-negative input, `Math.round` is exact.)
4. **The hash-sensitivity test.** Plan: "the hash changes when one point moves by 1e-6° and not when it moves by less than half of that." (U43, :348). **Fix:** true only for `corridorHash` on hand-built rings at an exact microdegree (`42.0 + 4e-7` keeps the hash, `+6e-7` or `+1e-6` changes it). Through `corridor`, moving a route point that simplification drops changes nothing, and moving the first point changes every coordinate. Write the test on hand-built rings, and add the literal Swift vectors from §6.
5. **Byte equality.** Plan: "It's device-local, so byte equality with iOS isn't required, but the inputs and their order are." (U43, :337). **Fix (recommendation):** write iOS's exact layout: an 8-byte little-endian version, then each rounded value's raw IEEE-754 bits, little-endian, latitude first (§6). It's one `ByteBuffer`, it was verified on JDK 17 against seven Swift vectors, and it turns those vectors into portable fixtures. If the owner prefers not to (§19), record a gate row.
6. **Where the hash sits.** Plan: "Create: the corridor hash, a pure function U44 calls. U42 decides where it sits." (U43, :331). **Decision:** `P/data/honor/pilgrimage/PilgrimageTilesCorridor.kt`, an `object` with `HALF_WIDTH_METERS`, `rings(route)`, `corridorHash(rings, version)`, `stage(way): TileStage` and `packCount(stages)`. `corridor`/`simplified`/`ringContains`/`corridorContains` go on `WayGeometry`'s companion, `tileCount` and the constants on `PilgrimageTilesDescriptors`, as the plan has them. Add `PilgrimageTilesCorridor.kt` to the Output Structure.
7. **Which tests U43 ports from the manager file.** Plan: "the hash tests ported from `PilgrimageTilesManagerTests.swift`." (U43, :332). **Fix:** there's one hash test, `testTheCorridorHashCarriesTheRegionVersion`, and its last assertion (`isStageSaved` false for a version-1 region) needs the fake loader, so it splits: the two equalities in U43, the `isStageSaved` half in U44. The two pack-count tests (`testThePackCountIsTheZ11CellsOfTheWholeCorridor`, `testAZ11CellTwoStagesShareIsCountedOnce`) move to U43 with a pure `packCount`, which the plan's U43 scenario already assumes. The estimate and calibration tests stay in U44 (§13.4). U44's ported count then reads 22 of iOS's 24 manager tests, plus the hash test's second half.
8. **"an empty input giving none"** (U43, :345) is listed as ported, but iOS has no empty-corridor test. Keep it as an Android addition.
9. **Bytes are `Long`.** The plan doesn't say. iOS's `Int` is 64-bit, Mapbox Android's sizes are `long`, and a Kotlin `Int` estimate overflows at 537 packs at the seed (§8). Status bytes, footprint bytes, the estimate, the calibration value and `megabytes`' input are all `Long` (U44, U46, U47).
10. **Calibration details the plan's U44 line leaves out** (:373): a missing or non-positive stored value reads as the seed; the sum covers every region whose id is a passed stage's, whatever its completeness or hash; it runs after a save in which every stage was already current too; `bytes / packs` can be `0`, which is written and reads as the seed; it runs before the phase goes idle. The key is a `longPreferencesKey` (:174).
11. **`megabytes`** (U46, :493) is right. Add: ASCII digits with no grouping (format with `toString()`, pass through a `%1$s` resource as `settings_ways_megabytes` does, never `%1$d`), and a plain space before `MB`.

### 17. Notes by unit

**U43**
- `WayGeometry` companion gains `corridor(around: List<WayCoordinate>, halfWidthMeters: Double): List<List<WayCoordinate>>`, `corridorContains(rings, point)`, `simplified(points, toleranceMeters)`, `ringContains(ring, point)`. The corridor's 25 m tolerance is a literal inside it. Reuse `METERS_PER_DEGREE`.
- Keep Swift's operation order in `simplified` and `corridor` (§3) so rings, and through them the hash, match iOS to the bit on the JVM.
- `PilgrimageTilesDescriptors` (`object`): `STREETS_ZOOM = 11..14`, `PACK_ROOT_ZOOM = 11`, `REGION_VERSION = 2`, `RASTERIZES_IDEOGRAPHS_LOCALLY = true`, `tileCount(rings, zooms: IntRange): Int` with private `tile`, `coordinate`, `tileTouches`. Filter parts `size > 3`; per-part boxes; the inclusive box test, then `tileTouches`; clamp x and y to `[0, n − 1]`; `floor(...).toInt()`.
- `PilgrimageTilesCorridor` (`object`): `HALF_WIDTH_METERS = 500.0`; `rings(route: List<WayPoint>) = WayGeometry.corridor(route.map { WayCoordinate(it.lat, it.lon) }, HALF_WIDTH_METERS)`; `corridorHash(rings, version = REGION_VERSION)` with the §6 layout and `swiftRounded`; `stage(way) = TileStage(way.id, way.stage?.index ?: 0, rings, corridorHash(rings))`; `packCount(stages: List<TileStage>) = tileCount(stages.flatMap { it.rings }, PACK_ROOT_ZOOM..PACK_ROOT_ZOOM)`; `regionPrefix(routeId) = "pilgrimage:$routeId:"`; `stageIndex(regionId, prefix)`.
- Tests: 9 corridor, 5 descriptors, the hash test's pure half, the two pack-count tests, plus the §13.6 additions and the §6 vectors as literal hex.

**U44**
- Every entry point takes `List<TileStage>` (`id`, `index`, `rings`, `corridorHash`). `isStageSaved(stage)` looks up `stage.id`; the save sorts by `index`, requests `id` with `rings` and `corridorHash`.
- Estimate: `packCount(stages) * bytesPerPack(routeId)`, both `Long`; `bytesPerPack` reads `longPreferencesKey("pilgrimage.tiles.bytesPerPack.$routeId")` and falls back to `4_000_000L` when missing or `<= 0`.
- Calibrate: one store read; sum `completedResourceSize` over `stages.mapNotNull { byId[it.id] }`; `packs = packCount(stages)`; write `bytes / packs` only when both `>= 1`; called after the loop, before idle; never on cancel, failure or refusal.
- The loader's load-success path must cache the region before the save resumes, or calibration misses the last stage.
- `calibrate` and `packCount` must be reachable from tests (`internal`): iOS's `testCalibrateWritesNothingWithoutBytesAndPacks` calls `manager.calibrate` directly, and the estimate tests call `packCount`.
- Tests: §13.4's estimate, hash-version (second half), calibrate ×2 and read-count tests, with `stages(n)` mapped through `PilgrimageTilesCorridor.stage`.

**U45**
- The descriptors use `STREETS_ZOOM.first`/`.last` as `minZoom`/`maxZoom` (bytes) and `RASTERIZES_IDEOGRAPHS_LOCALLY` for the glyph mode (C3).
- Each `TileStage.rings` becomes one `MultiPolygon`, every ring one polygon's exterior, each coordinate `Point.fromLngLat(lon, lat)`. The metadata is `{"corridorHash": stage.corridorHash}`.

**U46**
- `megabytes(bytes: Long): String` per §10.
- Build the `TileStage` list on IO once per installed `(routeId, release)`; cache it and its `packCount`. Re-read `bytesPerPack` on every reload. Recompute status on reload, `regionsChanged` and idle. Not installed: estimate 0, status none.

**U47**
- The overview, "the day" and the Maps screen build `TileStage` values through the same helper as U46, one per stage Way that loads. "The day" needs one value (the walked stage's). `megabytes` is U46's.

**U48**
- Expected seed estimates on today's dataset: Francés 62 packs ≈ "~248 MB", Nakahechi 3 ≈ "~12 MB" (§5 table; release differences may move them).
- D2: record the summed "maps saved" figure, the store directory's size and the calibrated `bytesPerPack`; the ratio of summed to disk should sit near §5's per-stage/union ratio if D2 holds.

### 18. Android additions to record at the gate

| # | Addition | Reason |
|---|---|---|
| A1 | The per-stage inputs (rings, hash) are built on IO from decoded Ways, which are then dropped; the estimate and status run off Main | iOS does this work on the main actor (D8); Android ANRs on Main CPU work (Stage 2-E). Same numbers, same order |
| A2 | The per-stage values and their pack count are cached per installed `(routeId, release)` instead of rebuilt on every reload | Rebuilding means re-decoding up to 33 Ways; a stage Way changes only with a release or an install, so the cache, cleared after every successful install (a same-release re-download can restore a missing stage), gives iOS's answers. `bytesPerPack` is still re-read per reload |
| A3 | The corridor hash and its builders live in a pure `PilgrimageTilesCorridor`, not on the manager | Callers build values before reaching the manager (A1). Placement only |
| A4 | (Only if the owner declines §19's recommendation) the hash's byte layout differs from iOS's | Device-local value; no cross-device effect |
| A5 | The per-route bytes-per-pack rides a device transfer but not a cloud backup (iOS's `UserDefaults` rides both) | The app's existing backup rules exclude every file from cloud backup; a lost figure falls back to the seed |

### 19. Proposed owner decisions

1. **Write iOS's exact hash bytes?** The plan says byte equality "isn't required". Recommendation: **yes, match exactly** (§6, correction 5). It costs nothing, Swift's vectors become literal fixtures, and A4 never exists. The alternative (any deterministic layout) is equally correct on one device, but needs its own vectors and a gate row.

No other real forks in C1: every other point is parity.

---

## C2. The tiles engine: the seam, status, the save loop, cancel, removal, reconciliation, and the package hooks

| | |
|---|---|
| iOS pin | pilgrim-ios `7c200bf` (v2.0.0). PR #86's own diff `905996e^1..905996e`. Post-pin `e551b11` cited only for where #91's launch step sits. |
| Android | pilgrim-android `ca6424db` (main) |
| Feeds | **U44** (the seam, the fake, the manager, its tests); **U45** (the package hooks' ported test, `runAtLaunch()`'s return, the launch reconcile, the `:tracker` and flag-off proofs). U46/U47 get notes where the engine's signals decide their refresh rules. |
| Lenses | behaviour (ordering, generations, continuations, threads), data (keys, ids, what persists), edge cases (parse rules, empty inputs, races), UI (the four error lines the phase can carry; no strings of its own) |

**iOS files read in full at `7c200bf`:** `Pilgrim/Models/Honor/TileRegionLoading.swift` (88 lines), `Pilgrim/Models/Honor/PilgrimageTilesManager.swift` (389), `UnitTests/Honor/FakeTileRegionLoader.swift` (162), `UnitTests/Honor/FakeTileRegionLoaderTests.swift` (66), `UnitTests/Honor/PilgrimageTilesManagerTests.swift` (449), `UnitTests/Honor/PilgrimageTilesManagerTests+Lifecycle.swift` (130), `Pilgrim/Models/Honor/PilgrimagePackageManager.swift` (455; PR #86's three hooks plus the surrounding Replace, Update, Remove, `installed()`, commit and rollback), and PR #86's additions to `UnitTests/Honor/PilgrimagePackageManagerTests.swift` and `+Fixtures.swift`. Also read in full because the engine's cache semantics live there: `Pilgrim/Models/Honor/MapboxTileRegionLoader.swift` (286; C3 owns it, cited here only for what the manager relies on). Read in part: `Pilgrim/AppDelegate.swift` (`runPostDoneLaunchTasks`, `reconcileTilesAtLaunch`, and `@e551b11`'s renamed `reconcilePilgrimageAtLaunch`), `Pilgrim/Scenes/Root/MainCoordinatorView.swift` (`startWalk`, `cancelWalk`, `chooseWay`), `Pilgrim/Scenes/Root/MainTabView.swift` (the Save Failed alert), `Pilgrim/Scenes/Honor/PilgrimageRouteView.swift` and `PilgrimageMapsRow.swift` (the engine's callers and refresh triggers), `HonorOverviewView.swift` and `ActiveWalkView.swift` (the `isStageSaved` callers), `PilgrimageWayImporter.swift:1-26` (the error type and its lines), and the iOS design spec and plan for intent.

**Android files compared at `ca6424db`:** `P/data/honor/pilgrimage/PilgrimagePackageManager.kt` (the `PilgrimageTiles` seam, the actor, every `tiles?.` call, `installed()`, `installedRoute()`, `runAtLaunch()`, `retireStagesAndPackage`, the commit's rollback), `P/data/honor/pilgrimage/PilgrimageWalkGuard.kt`, `P/honor/HonorLinkRouter.kt` (`walkScreenUp`), `P/ui/navigation/PilgrimNavHost.kt` (`honorLinkScreen`), `P/walk/honor/HonorFinalizer.kt`, `P/PilgrimApp.kt`, `P/di/WalkModule.kt` (the finalization scope), `P/data/honor/WayStore.kt` (`stageWayId`, `pilgrimageRouteIds`, `retireMany`), `P/data/honor/pilgrimage/PilgrimageModels.kt` (the error enum), `P/data/honor/pilgrimage/PilgrimageWayImporter.kt` (`route(from:)`'s error mapping), `P/ui/honor/pilgrimage/PilgrimageRouteViewModel.kt` (`isBusy`, `reload`, the alerts' state), `T/data/honor/pilgrimage/PilgrimagePackageManagerTest.kt` (`FakeTiles`, the deferred-test note), `T/data/honor/pilgrimage/PilgrimagePackageWalkGuardTest.kt`, `T/data/honor/pilgrimage/PilgrimagePackageHarness.kt` (`FakeWalkSignals`, `makeManager(tiles)`, the fixtures), and `T/walk/honor/HonorFinalizerTest.kt` (the launch-work tests).

Cross-cluster: C1 owns the corridor, `corridorHash`, `packCount`, `estimateBytes`, `bytesPerPack`, `calibrate`'s arithmetic and the constants (`halfWidthMeters`, `seedBytesPerPack`, `regionVersion`); this file cites them only where the loop calls them. C3 owns the production loader (store, descriptors, refresh, settled projection, error mapping from the SDK, threading of SDK callbacks). C4 owns the surfaces and copy.

---

### C2.1 The seam: `TileRegionLoading` and its value types

The manager never names a Mapbox type. Everything it asks for and reads back is one of six value types, and every store call goes through one protocol.

```swift
struct TileRegionRequest: Equatable {
    let id: String
    /// Closed rings, one per convex part of the corridor, each with its
    /// first coordinate repeated last. WGS84.
    let rings: [[CLLocationCoordinate2D]]
    /// Stable hash of `rings` so a resumed save can tell a redrawn stage
    /// from an unchanged one without re-deriving the geometry.
    let corridorHash: String
    let acceptExpired: Bool

    static func == (lhs: TileRegionRequest, rhs: TileRegionRequest) -> Bool {
        lhs.id == rhs.id && lhs.corridorHash == rhs.corridorHash && lhs.acceptExpired == rhs.acceptExpired
    }
}

enum StylePackRequest: String, CaseIterable {
    case light, dark
}
```
> Pilgrim/Models/Honor/TileRegionLoading.swift:7-24@7c200bf

- A request's equality ignores `rings` (the hand-written `==` compares only `id`, `corridorHash`, `acceptExpired`). The fake's tests compare whole requests, so the Kotlin type needs the same rule: either a class with a hand-written `equals`/`hashCode` over `id`, `corridorHash`, `acceptExpired`, or a data class whose rings sit outside the constructor. A plain data class with a `List<List<LatLon>>` would compare rings too, which iOS doesn't.
- `StylePackRequest.allCases` is `[light, dark]` in declaration order. The loop loads light first, then dark, and `total` counts `allCases.count`, which is 2. A Kotlin `enum class StylePackRequest { LIGHT, DARK }` with `entries` keeps both the order and the count.

```swift
struct TileRegionSummary: Equatable {
    let id: String
    let completedResourceCount: Int
    let requiredResourceCount: Int
    let completedResourceSize: Int
    let metadata: [String: String]

    var isComplete: Bool { requiredResourceCount > 0 && completedResourceCount >= requiredResourceCount }
    var corridorHash: String? { metadata["corridorHash"] }
}

enum TileRegionLoadingError: Error, Equatable {
    case failed
    case diskFull
    case cancelled
    /// The store's 750-unique-pack ceiling: the SDK refuses the region
    /// before downloading anything, so a retry would refuse the same way.
    case tileCountExceeded
}

/// A handle the manager can cancel. The production loader wraps Mapbox's
/// `Cancelable`; the fake flips a flag.
protocol TileLoadHandle: AnyObject {
    func cancel()
}
```
> Pilgrim/Models/Honor/TileRegionLoading.swift:28-52@7c200bf

- `isComplete` is `required > 0 && completed >= required`: a region with zero required resources is never complete, and `>=` (not `==`) admits an over-count.
- `corridorHash` is optional: a region whose metadata lacks the key has `nil`, and `nil` never equals a computed hash, so it reads as unsaved. (The production loader writes `""` when the metadata read fails, C3; `""` never equals a SHA-256 hex either.)
- Swift `Int` is 64-bit. Use `Long` on Android for `completedResourceSize` and every byte sum built from it (status's `saved(bytes)`, the footprint, calibration's numerator). Mapbox Android reports sizes as `long`. The counts can stay `Long` too, since the SDK reports them as `long`; `isComplete` is the same expression.
- `TileLoadHandle` is a class-bound protocol with one method. Kotlin: `fun interface TileLoadHandle { fun cancel() }` or a plain interface.

```swift
/// Which of the store's two answers arrived. They are separate round trips
/// that land at different times — the style packs come back from one call,
/// the regions from `allTileRegions` plus a metadata read each — and on a
/// phone that has saved maps the packs answer is reliably first. A reader
/// waiting for what is on disk must not be woken by the packs.
enum TileStoreChange: Equatable {
    case regions
    case packs
}

/// The one seam between the manager and Mapbox. Every method is
/// synchronous to call and reports through closures on the main queue.
protocol TileRegionLoading: AnyObject {
    /// The store answers asynchronously, so a `regions()` read taken before
    /// the first answer lands sees nothing. This is how that synchronous
    /// reader learns an answer arrived and is worth asking again — and which
    /// answer it was.
    var onChange: ((TileStoreChange) -> Void)? { get set }

    func hasStylePack(_ pack: StylePackRequest) -> Bool
    func loadStylePack(_ pack: StylePackRequest,
                       completion: @escaping (Result<Void, TileRegionLoadingError>) -> Void) -> TileLoadHandle
    func loadRegion(_ request: TileRegionRequest,
                    progress: @escaping (_ completed: Int, _ required: Int) -> Void,
                    completion: @escaping (Result<TileRegionSummary, TileRegionLoadingError>) -> Void) -> TileLoadHandle
    func regions() -> [TileRegionSummary]
    /// Asks the store for its regions and calls back when that answer has
    /// landed — even when the answer is "nothing changed" — so a launch-time
    /// reader can act on a real snapshot rather than on an empty cache.
    /// Unlike `onChange`, this fires on the answer, not on a difference: an
    /// empty store answers "no regions", which equals the empty cache and
    /// signals nothing at all.
    func refreshRegions(completion: @escaping () -> Void)
    func removeRegion(id: String)
}
```
> Pilgrim/Models/Honor/TileRegionLoading.swift:54-88@7c200bf

What the contract promises, and what the Kotlin interface must promise in the same words:

1. **Synchronous to call; answers on the manager's thread.** No method blocks. Every closure (`onChange`, both completions, `progress`, `refreshRegions`' completion) runs on the main queue. On Android: on the manager's confined thread (§C2.12). The production loader hops every SDK callback there (C3); the fake calls them on the test's thread when the test drives it.
2. **`regions()` is a cache read.** It returns what the loader last learned, and on the real loader also starts a refresh (`MapboxTileRegionLoader.swift:157-160@7c200bf`). Before the store's first answer it returns `[]` (D6, §C2.7).
3. **`refreshRegions` fires on an answer, not on a difference**, and fires its completion once. `onChange(.regions)` fires on a difference only.
4. **`removeRegion` is fire-and-forget.** It returns nothing and never fails to the caller; the real loader drops the id from its cache at once and signals `.regions` (`MapboxTileRegionLoader.swift:167-174@7c200bf`).
5. **`hasStylePack` is a cache read** of complete packs only (C3).

The Kotlin translation for U44's `TileRegionLoading.kt` (shape, not code to paste): `var onChange: ((TileStoreChange) -> Unit)?`; `fun hasStylePack(pack): Boolean`; `fun loadStylePack(pack, completion: (Result<Unit, TileRegionLoadingError>) -> Unit): TileLoadHandle`; `fun loadRegion(request, progress: (Long, Long) -> Unit, completion: (Result<TileRegionSummary, TileRegionLoadingError>) -> Unit): TileLoadHandle`; `fun regions(): List<TileRegionSummary>`; `fun refreshRegions(completion: () -> Unit)`; `fun removeRegion(id: String)`. Kotlin's `kotlin.Result` can't carry a typed failure; use a small sealed `TileLoadResult` (`Success(value)` / `Failure(TileRegionLoadingError)`) or an `Either`-style pair. `TileRegionLoadingError` becomes an `enum class` (`FAILED, DISK_FULL, CANCELLED, TILE_COUNT_EXCEEDED`), not an exception: nothing throws it, it's only delivered.

### C2.2 The manager's shape: `Status`, `Phase`, the walk closure, the two signals

```swift
@MainActor
final class PilgrimageTilesManager: ObservableObject {

    enum Status: Equatable {
        case none
        case partial(saved: Int, of: Int)
        case saved(bytes: Int)
    }

    enum Phase: Equatable {
        case idle
        /// `total` counts the two style packs plus one region per stage.
        case saving(done: Int, total: Int)
        case failed(PilgrimageError)
    }

    @Published private(set) var phase: Phase = .idle

    /// Set by `MainCoordinatorView`, like the package manager's.
    var isWalkActive: () -> Bool = { false }
```
> Pilgrim/Models/Honor/PilgrimageTilesManager.swift:10-29@7c200bf

- One manager per process (`static let shared`, :41), confined to the main actor. Every property below is read and written on main only.
- `phase` starts `.idle`. Only `save` and `cancel` write it (§C2.5, §C2.6). `.failed` stays until the next `save` that passes the one-at-a-time check, or the next `cancel` (which `remove` calls).
- `isWalkActive` defaults to `{ false }` and is wired in `chooseWay()` (§C2.11).

```swift
    /// Shared like the package manager's; the production loader is attached
    /// in `MainCoordinatorView` so this file never imports Mapbox.
    static let shared = PilgrimageTilesManager(loader: MapboxTileRegionLoader())

    private let loader: TileRegionLoading
    private let defaults: UserDefaults

    /// For readers that care only about what is on disk, not about a save's
    /// progress: `objectWillChange` also fires on every `phase` step, and a
    /// reader that re-reads the whole route would do so once per stage of a
    /// save it does not even display.
    let regionsChanged = PassthroughSubject<Void, Never>()

    init(loader: TileRegionLoading, defaults: UserDefaults = .standard) {
        self.loader = loader
        self.defaults = defaults
        // A view that read `status` before the store answered has nothing
        // else to tell it the saved answer has arrived.
        loader.onChange = { [weak self] change in
            guard let self else { return }
            self.objectWillChange.send()
            // Only the regions answer speaks for what is on disk; the packs
            // answer is a round trip ahead of it and says nothing about the
            // store's contents.
            if change == .regions {
                self.regionsChanged.send()
            }
        }
    }
```
> Pilgrim/Models/Honor/PilgrimageTilesManager.swift:39-67@7c200bf

- The doc comment at :39-40 is stale: `shared` builds its own `MapboxTileRegionLoader()` right here; nothing attaches a loader in `MainCoordinatorView` (that file wires only `isWalkActive` and `packages.tiles`, §C2.11). Shipped code wins: the production loader is built the first time `shared` is touched, which at launch is `reconcileTilesAtLaunch`'s Task (§C2.10), and its `init` starts the first store refresh (`MapboxTileRegionLoader.swift:68-73@7c200bf`).
- **The two signals.**
  - `regionsChanged` (a `PassthroughSubject`: no replay, no current value) fires on `onChange(.regions)` only.
  - `objectWillChange` fires on `onChange(.regions)`, on `onChange(.packs)`, and (through `@Published`) on every `phase` write.
  - Who listens to which: the route page, the overview, the Data card and the Maps screen reload their data on `regionsChanged` only (`PilgrimageRouteView.swift:149`, `HonorOverviewView.swift:177`, `DataCard.swift:44`, `OfflineMapsView.swift:86@7c200bf`). `objectWillChange` only re-renders views whose own `@State` didn't change, so a packs-only change re-reads nothing anywhere at the pin. The route page also re-reads status when `phase` changes to `.idle` (`PilgrimageRouteView.swift:150-154@7c200bf`).
- **Android:**
  - `phase` is a `StateFlow<Phase>` (current value replayed to each new collector, equal values conflated, as SwiftUI's `onChange` compares old and new). A route page reopened mid-save reads the live phase at once.
  - `regionsChanged` is a `SharedFlow<Unit>` with **no replay**, as `PassthroughSubject`. Emitted from the manager's thread with `tryEmit`; give it `extraBufferCapacity = 1` and `onBufferOverflow = DROP_OLDEST`, so the emitter never suspends and a slow collector gets at least one "re-read" (it carries no payload, so conflating is harmless).
  - No `objectWillChange` analogue is needed: nothing at the pin re-reads on it, and the phase is already its own flow. No packs signal is needed either. The one iOS test that observes `objectWillChange` (`testTheManagerPublishesWhenTheLoaderAnnouncesAChange`) ports as "`regionsChanged` emits when the loader announces `.regions`" (§Test inventory).
  - The `[weak self]` in the `onChange` closure has no Android counterpart: the manager is a process singleton and holds the loader; the loader's `onChange` holding the manager is a cycle between two singletons, harmless.
  - Setting `loader.onChange` in the constructor is plain Kotlin and touches no Mapbox class, so it keeps the plan's "building the manager touches no Mapbox class until its first store call".

### C2.3 Keys, ids, and the per-stage input

```swift
    static func regionPrefix(routeId: String) -> String { "pilgrimage:\(routeId):" }
```
> Pilgrim/Models/Honor/PilgrimageTilesManager.swift:103@7c200bf

```swift
    private static func stageIndex(of regionId: String, prefix: String) -> Int? {
        guard regionId.hasPrefix(prefix) else { return nil }
        return Int(regionId.dropFirst(prefix.count))
    }
```
> Pilgrim/Models/Honor/PilgrimageTilesManager.swift:385-388@7c200bf

- **Region id = the stage Way's id**, `pilgrimage:<routeId>:<index>` (`WayStore.stageWayId`). The manager never builds an id itself: the save's request takes `way.id` (:242), and `isSaved` looks up `byId[way.id]`. Android's `WayStore.stageWayId` is `"$STAGE_ID_PREFIX$routeId:$stageIndex"` (`P/data/honor/WayStore.kt:720@ca6424db`), the index in plain decimal, so ids match iOS's byte for byte.
- **The trailing colon** makes prefixes unambiguous: `pilgrimage:camino:` never matches `pilgrimage:camino-frances:0`.
- **`stageIndex`** is nil for another route's region and for a suffix that isn't an integer. Swift's `Int(String)` accepts a leading `+` or `-` and leading zeros, rejects spaces, an empty string and overflow, and accepts **ASCII digits only**. Probed on this Mac: `"+3"`→3, `"-1"`→-1, `"03"`→3, `" 3"`→nil, `""`→nil, `"1:x"`→nil, `"99999999999999999999"`→nil, Arabic-Indic `"٣"`→nil. The JVM's `Integer.parseInt` (and Kotlin's `toIntOrNull`, which uses `Character.digit`) agrees on every case but the last, where it returns 3. Unreachable: every region id is written by this app from `stageWayId`. U44 may use `toIntOrNull()` and note it, or guard with an ASCII check for exactness.
- **Swift's `hasPrefix` is a Unicode-aware comparison; Kotlin's `startsWith` compares UTF-16 units.** Route ids are `[a-z0-9-]{1,64}` on both platforms (`WayStore.kt:725@ca6424db`), so they agree.

**The per-stage input (plan Key Technical Decisions).** iOS's status, footprint, estimate, save and `isStageSaved` take decoded `Way`s and derive the rings and hash each time (`rings(for:)` and `corridorHash(for:)`, :71-82, C1). Android takes one precomputed value per stage. What the engine actually reads from a `Way`, so the value must carry exactly this:

| iOS reads | where | Android field |
|---|---|---|
| `way.id` | the region id, the cache lookup, the request id (:113-121, :130, :150, :184, :240-243) | `wayId` (or derive `stageWayId(routeId, index)`; carrying it keeps `isStageSaved(stage)` one-argument, as iOS's is) |
| `way.stage?.index ?? 0` | the loop's sort key (:238) | `index` |
| `rings(for: way)` | the request's rings (:241), `packCount` (C1) | `rings` |
| `corridorHash(for: way)` | `isSaved`'s comparison (:120), the request's hash (:243) | `corridorHash`, computed by U43 with `regionVersion` 2 |

Nothing else of a `Way` is read. `status(for:stages:)` takes a `routeId` and never uses it (:128-136); keep the parameter for the call sites' symmetry or drop it, U44's choice. `footprint`, `estimateBytes`, `calibrate` and `save` do use theirs (the prefix and the calibration key).

### C2.4 Status, `isStageSaved`, footprint

```swift
    /// One store read for a whole route: `regions()` refreshes the loader's
    /// cache, so asking it per stage re-reads once per stage.
    private func regionsById() -> [String: TileRegionSummary] {
        Dictionary(loader.regions().map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
    }

    private func region(for way: Way) -> TileRegionSummary? {
        loader.regions().first { $0.id == way.id }
    }

    /// Complete by resource count and loaded for the stage's current line.
    private func isSaved(_ way: Way, region: TileRegionSummary?) -> Bool {
        guard let region, region.isComplete else { return false }
        return region.corridorHash == Self.corridorHash(for: way)
    }

    /// The single-stage entry point, for the morning card.
    func isStageSaved(_ way: Way) -> Bool {
        isSaved(way, region: region(for: way))
    }

    func status(for routeId: String, stages: [Way]) -> Status {
        let byId = regionsById()
        let saved = stages.filter { isSaved($0, region: byId[$0.id]) }
        guard !saved.isEmpty else { return .none }
        let packsPresent = StylePackRequest.allCases.allSatisfy(loader.hasStylePack)
        guard saved.count == stages.count, packsPresent else { return .partial(saved: saved.count, of: stages.count) }
        let bytes = saved.compactMap { byId[$0.id] }.reduce(0) { $0 + $1.completedResourceSize }
        return .saved(bytes: bytes)
    }
```
> Pilgrim/Models/Honor/PilgrimageTilesManager.swift:107-136@7c200bf

**Saved, for one stage** (`isSaved`): a region with the stage's id exists, `isComplete` is true, and its stored `corridorHash` equals the stage's current hash. Missing, incomplete (any `completed < required`, or `required == 0`), a missing hash, or any other hash all read as not saved.

**`isStageSaved(stage)`**: one `loader.regions()` read, `first` region with that id. With unique ids (Mapbox keys regions by id) `first` and the dictionary's "keep first" agree.

**`status(routeId, stages)`**, from one `regionsById()` snapshot, in this order:
1. `saved` = the stages that pass `isSaved` against the snapshot.
2. No stage saved → `.none`. This holds even when both packs are present and every region exists but is stale: a route whose every stage was redrawn reads `.none`, so the row offers "~N MB", not "0 of 33 saved".
3. Some saved, or all saved with a pack missing → `.partial(saved: count, of: stages.count)`. `packsPresent` is `hasStylePack(.light) && hasStylePack(.dark)` (`allSatisfy` short-circuits; no side effects either way).
4. All saved and both packs → `.saved(bytes:)`, the sum of `completedResourceSize` over the stages' own regions only (not stale or foreign ones). Every stage is saved here, so the `compactMap` drops nothing.

- **D4, confirmed.** Every region saved but a pack missing is `.partial(saved: n, of: n)`, and the row's label for it is "Save maps for the way · n of n saved" (C4). `testStatusIsSavedOnlyWhenEveryStageAndBothPacksArePresent` pins it (`PilgrimageTilesManagerTests.swift:141-148@7c200bf`). Match as shipped.
- **Empty stage list**: `saved` is empty → `.none`. The route page shows the row only with stages, so unreachable there.
- **D2 lives here** (C1/C3 measure it): `saved(bytes:)` sums each region's `completedResourceSize`. If adjacent stages share z11 packs and each region reports its packs' bytes, the sum over-counts disk. U48 measures.

```swift
    struct Footprint: Equatable {
        let savedStages: Int
        let bytes: Int
    }

    func footprint(routeId: String, stages: [Way]) -> Footprint {
        let byId = regionsById()
        let savedStages = stages.filter { isSaved($0, region: byId[$0.id]) }.count
        let prefix = Self.regionPrefix(routeId: routeId)
        let bytes = byId.values.filter { $0.id.hasPrefix(prefix) }.reduce(0) { $0 + $1.completedResourceSize }
        return Footprint(savedStages: savedStages, bytes: bytes)
    }
```
> Pilgrim/Models/Honor/PilgrimageTilesManager.swift:143-154@7c200bf

- **Footprint**, from one snapshot: `savedStages` counts stages passing `isSaved`; `bytes` sums `completedResourceSize` over **every** region carrying the route's prefix: stale ones (a redrawn stage), incomplete ones (an interrupted load), and ones past the stage count (before the next launch sweep). Packs aren't consulted. Foreign routes' regions are excluded.
- The comment (:138-142) gives the reason: "after an Update redraws the way each hash goes stale while the bytes stay on the phone, and Delete has to reach them". `remove(routeId)` takes every prefixed region (§C2.7), so the bytes Settings shows are the bytes Delete removes from the store's index (the SDK then moves their resources to the ambient cache; C3, flow-analysis item 9).
- Sum order doesn't matter (integer sum over a dictionary's values).
- Test: `testTheFootprintCountsStaleBytesOfTheRouteFromOneRead` (5 MB current + 2 MB stale counted, 9 MB foreign excluded, one store read).

**Which store state these read.** All three read only the loader's cache (`regions()`, `hasStylePack`), synchronously. None waits for the store. On the real loader each `regions()` call also starts a refresh, so a reader that re-reads on `regionsChanged` re-asks the store each time; the settled projection keeps that from looping (C3). Before the first answer the cache is empty: `isStageSaved` is false, `status` is `.none`, the footprint is `(0, 0)` (D6, §C2.7; Android's wait, §C2.12).

**Android.** Port all three as synchronous functions over the cache, on the manager's thread, with the per-stage value in place of a `Way` (the ported tests call them synchronously and count store reads: `regionsReadCount == 1` for the footprint, 2 for a whole save). The cold-cache wait is a separate call the surfaces make first (§C2.12), so it doesn't change these functions or their read counts.

### C2.5 The save loop, step by step

```swift
    private var inFlight: TileLoadHandle?
    /// The continuation the in-flight load will resume. `cancel()` resumes it
    /// itself, because a cancelled load never reports back.
    private var pending: CheckedContinuation<Void, Error>?
    /// Bumped on every `cancel()`; a completion that lands after its
    /// generation was cancelled is a no-op rather than a state change.
    private var generation = 0
```
> Pilgrim/Models/Honor/PilgrimageTilesManager.swift:192-198@7c200bf

Two counters, never confused (the plan's "bump the generation" at save start is wrong; see Corrections):

| Counter | Bumped by | Read by | Purpose |
|---|---|---|---|
| `generation` | `cancel()` only (:316) | each load's completion (:279, :297), the loop's post-step checks (:230, :250), the catch (:260) | a cancelled save's late callbacks and its own resumed loop do nothing |
| `sweepGeneration` | `save` past the door (:216), `reconcile` (:362) | the reconcile's refresh completion (:365) | a launch sweep never fires after a save started writing, and only the newest reconcile sweeps |

A new save does **not** bump `generation`; two successive saves with no cancel between them share a generation value. That's safe on iOS because a save never leaves a load outstanding when it ends without a cancel (each load's completion runs before the loop continues, and a failed save's load has already completed).

```swift
    func save(routeId: String, stages: [Way]) async throws {
        if case .saving = phase { return }
        guard !isWalkActive() else {
            // The row's catch relies on phase carrying every failure; a
            // refusal at the door has to land there like a refusal mid-loop.
            phase = .failed(.walkInProgress)
            throw PilgrimageError.walkInProgress
        }
        // Once the walker is writing regions a launch sweep has no business
        // firing, whatever it was told was installed when it was asked. A
        // save refused at the door writes nothing, so the sweep stays.
        sweepGeneration += 1
        phase = .saving(done: 0, total: StylePackRequest.allCases.count + stages.count)
        let myGeneration = generation
        var done = 0
```
> Pilgrim/Models/Honor/PilgrimageTilesManager.swift:205-219@7c200bf

1. **One at a time.** If `phase` is `.saving`, return normally: no throw, no call, no state change. `.idle` and `.failed` both let a save in. (The design spec's order, `isWalkActive` before `phase == .idle` and refusing a failed phase, is not what shipped: spec §3.1 lines 131-132. Code wins: a second tap while saving returns silently even mid-walk, and a failed phase never blocks a retry.)
2. **The door.** If `isWalkActive()`: `phase = .failed(.walkInProgress)`, throw `.walkInProgress`. Nothing else changes: `generation`, `sweepGeneration`, `inFlight`, `pending` untouched, no store read, no load. A pending launch sweep therefore survives a refused save (`testASaveRefusedWhileWalkingLeavesThePendingLaunchSweep`).
3. **Past the door**, in this order: `sweepGeneration += 1` (cancels any pending launch sweep; §C2.7), then `phase = .saving(done: 0, total: 2 + stages.count)`, then capture `myGeneration = generation` (the current value, not a fresh one), `done = 0`.

```swift
        do {
            for pack in StylePackRequest.allCases {
                guard !isWalkActive() else { throw PilgrimageError.walkInProgress }
                if !loader.hasStylePack(pack) {
                    try await loadPack(pack, generation: myGeneration)
                }
                // A cancel can land between a load's completion and this
                // hop; without this check the loop would start the next
                // load under a stale generation, whose completion would
                // never resume the save's continuation.
                guard generation == myGeneration else { throw PilgrimageError.incomplete }
                done += 1
                phase = .saving(done: done, total: StylePackRequest.allCases.count + stages.count)
            }
```
> Pilgrim/Models/Honor/PilgrimageTilesManager.swift:220-233@7c200bf

4. **Packs, light then dark.** For each: the walk check (throws `.walkInProgress` into the catch); skip if `hasStylePack(pack)` (the cache's complete packs); else load and await. Then, loaded or skipped, the generation check (throws `.incomplete`), `done += 1`, publish `.saving(done, total)`. So `done` reaches 2 after the packs whether they loaded or were present.

```swift
            // One snapshot for the whole loop. Every region the loop goes on
            // to load is one this snapshot said was missing, so nothing it
            // learns later could change a skip decision.
            let byId = regionsById()
            for way in stages.sorted(by: { ($0.stage?.index ?? 0) < ($1.stage?.index ?? 0) }) {
                guard !isWalkActive() else { throw PilgrimageError.walkInProgress }
                if !isSaved(way, region: byId[way.id]) {
                    let rings = Self.rings(for: way)
                    let request = TileRegionRequest(id: way.id, rings: rings,
                                                    corridorHash: Self.corridorHash(rings), acceptExpired: true)
                    try await loadRegion(request, generation: myGeneration)
                }
                // A cancel can land between a load's completion and this
                // hop; without this check the loop would start the next
                // load under a stale generation, whose completion would
                // never resume the save's continuation.
                guard generation == myGeneration else { throw PilgrimageError.incomplete }
                done += 1
                phase = .saving(done: done, total: StylePackRequest.allCases.count + stages.count)
            }
            calibrate(routeId: routeId, stages: stages)
            phase = .idle
```
> Pilgrim/Models/Honor/PilgrimageTilesManager.swift:234-255@7c200bf

5. **The snapshot.** One `regionsById()` after the packs: one store read for the whole stage loop (`testASaveReadsTheStoreOnceBeforeTheLoopAndOnceToCalibrate`: exactly 2 `regions()` calls per save, this one and calibrate's; `hasStylePack` isn't counted). Taken after the packs, so a pack load's time doesn't age it.
6. **Stage order:** ascending by stage index (`stage?.index ?? 0`), whatever order the caller passed. Ties are impossible with distinct indices.
7. **Per stage:** the walk check first (so a walk that starts mid-save fails the loop even at a stage that would have been skipped); then the skip test, `isSaved(way, region: byId[way.id])` against the snapshot (complete and hash-current); otherwise build the request and await its load.
8. **The request:** `id = way.id`, `rings = rings(for: way)`, `corridorHash = corridorHash(rings)` (version 2, C1), `acceptExpired = true` always. No progress is reported upward: `progress: { _, _ in }` (:290).
9. **After each stage**, loaded or skipped: the generation check, `done += 1`, publish. The `total` is recomputed from `stages.count`, unchanged through the loop.
10. **Calibrate** (C1: the stage regions' bytes ÷ the route's pack count, integer division, written only when both are at least 1), then `phase = .idle`. Calibration runs only on a loop that reached its end, which includes a loop where every step was a skip.

```swift
        } catch let error as PilgrimageError {
            // A cancel already put the phase back and cleaned up, and a save
            // started since then owns `inFlight`. A genuine failure is shown
            // until the next save or cancel clears it.
            if generation == myGeneration {
                inFlight?.cancel()
                inFlight = nil
                phase = .failed(error)
            }
            throw error
        }
    }
```
> Pilgrim/Models/Honor/PilgrimageTilesManager.swift:256-267@7c200bf

11. **The error path.** What can arrive: `.walkInProgress` (a per-step walk check), `.incomplete` (a generation check, a load mapped from `.failed`/`.cancelled`, `cancel()` resuming the continuation, or a completion arriving after the manager is gone), `.diskFull`, `.mapTooLarge`. Nothing else is thrown inside the `do` (every continuation resumes with a `PilgrimageError`; `withCheckedThrowingContinuation` ignores Task cancellation), so the typed catch is exhaustive in practice. If the generation is still current: cancel and clear `inFlight`, set `phase = .failed(error)`. If a cancel moved it: touch nothing, because `cancel()` already set `.idle` and a newer save may own `inFlight`. **Rethrow either way.** The row's caller swallows it (`PilgrimageMapsRow.swift:93-98@7c200bf`); the phase carries the failure.
    - On the current-generation path `inFlight` is already nil in every case but one: each load's completion clears `inFlight` before resuming, and the walk and generation checks run between loads. The exception is an inline failure in the production loader (it calls `completion(.failure(.failed))` before returning a no-op handle when an options object can't be built, `MapboxTileRegionLoader.swift:92-95,118-124@7c200bf`), which leaves that no-op handle in `inFlight` until this catch clears it. Harmless; port the line as is.
    - `pending` isn't touched here: every path that reaches the catch has already cleared it or never set it.

**What persists.** Nothing the loop holds survives the process: regions already loaded are in the store, the calibration is in `UserDefaults` only after a whole loop, and the phase is memory only. A killed save resumes at the first gap on the next tap, because skips are judged from the store.

### C2.6 Loads, continuations, cancel, `deinit`, and their Kotlin form

```swift
    private func loadPack(_ pack: StylePackRequest, generation myGeneration: Int) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            pending = continuation
            inFlight = loader.loadStylePack(pack) { [weak self] result in
                guard let self else {
                    continuation.resume(throwing: PilgrimageError.incomplete)
                    return
                }
                // `cancel()` resumed this continuation already; a late
                // completion resuming it a second time would trap.
                guard self.generation == myGeneration else { return }
                self.pending = nil
                self.inFlight = nil
                continuation.resume(with: result.mapError(Self.mapped))
            }
        }
    }
```
> Pilgrim/Models/Honor/PilgrimageTilesManager.swift:269-285@7c200bf

`loadRegion` (:287-303) is the same with `loader.loadRegion(request, progress: { _, _ in })` and `result.map { _ in () }` (the summary is dropped; the loader has already put it in its cache).

Per load, in order:
1. `pending = continuation` (the one the loop awaits).
2. `inFlight = loader.load…(…)`, the handle.
3. On the completion: if the manager is gone, resume with `.incomplete` (the only path that resumes without the generation check). If `generation != myGeneration`, return without resuming: `cancel()` already did. Otherwise clear `pending` and `inFlight`, then resume with the mapped result.

```swift
    private static func mapped(_ error: TileRegionLoadingError) -> PilgrimageError {
        switch error {
        case .diskFull: return .diskFull
        case .tileCountExceeded: return .mapTooLarge
        case .failed, .cancelled: return .incomplete
        }
    }

    /// Regions already complete stay. The in-flight load is cancelled and
    /// its late completion ignored by generation.
    func cancel() {
        generation += 1
        inFlight?.cancel()
        inFlight = nil
        if let pending {
            self.pending = nil
            pending.resume(throwing: PilgrimageError.incomplete)
        }
        phase = .idle
    }

    deinit {
        inFlight?.cancel()
    }
```
> Pilgrim/Models/Honor/PilgrimageTilesManager.swift:305-328@7c200bf

**The error map** (the manager's half; the SDK-to-`TileRegionLoadingError` half is C3's, `MapboxTileRegionLoader.swift:278-285@7c200bf`): `diskFull`→`DISK_FULL`, `tileCountExceeded`→`MAP_TOO_LARGE`, `failed` and `cancelled`→`INCOMPLETE`. A refusal at the door or mid-loop is `WALK_IN_PROGRESS` (thrown directly, not mapped). Android's enum names are confirmed: `enum class PilgrimageError { NOT_WALKABLE, INCOMPLETE, DISK_FULL, WALK_IN_PROGRESS, CATALOG_UNREACHABLE, MAP_TOO_LARGE }` (`P/data/honor/pilgrimage/PilgrimageModels.kt:15@ca6424db`), thrown as `PilgrimageException(error)` (:18). The save never raises `NOT_WALKABLE` or `CATALOG_UNREACHABLE`.

**Cancel**, in order: bump `generation`; cancel the handle and clear it; if a continuation is pending, clear it and resume it with `.incomplete`; `phase = .idle`. It runs whether or not a save is going: with nothing in flight it still bumps `generation` and sets `.idle`, which **clears a `.failed` phase** (the ported `testAFailedLoadLandsInFailedKeepsEarlierRegionsAndClears` ends with exactly that). It never touches `sweepGeneration` and never removes a region: "Regions already complete stay."
- Mapbox's own cancel then reports `CANCELED` through the load's completion (Mapbox Android: "Invoked only once upon success, failure, or cancelation"; the iOS comment at :193-194 says a cancelled load "never reports back"). Either way the generation guard makes the late completion a no-op, so the port doesn't depend on which.
- The fake's handle just flips `isCancelled` and the queued completion stays drivable; `testCancelKeepsWhatIsDoneAndResumeStartsAtTheGap` drives that late completion into a resumed save and proves it's ignored.

**`deinit`** cancels the handle. The production manager is a process singleton and never deinits; only tests release one.

**The Kotlin form.** The engine's state lives on one thread (§C2.12), so the iOS fields port as plain fields:

| iOS | Android |
|---|---|
| `CheckedContinuation<Void, Error>` resumed once | `CancellableContinuation<Unit>` from `suspendCancellableCoroutine`, stored in `pending`. "Resumed once" is guaranteed by clearing `pending` before every resume, on the one thread, exactly as iOS does. A second `resume` on a `CancellableContinuation` throws `IllegalStateException`, the analogue of the checked continuation's trap. |
| the completion's `guard self.generation == myGeneration else { return }` | the same check. Add `pending === cont` to it: it costs nothing and also covers a callback delivered twice, which iOS's check doesn't (Mapbox promises once, but a second delivery would crash both). Not a behaviour change. |
| `cancel()` resuming `pending` with `.incomplete` | `pending?.let { pending = null; it.resumeWithException(PilgrimageException(INCOMPLETE)) }` |
| `guard let self else { resume(throwing: .incomplete) }` (`[weak self]`) | no analogue: the manager is a singleton and the callback holds it strongly |
| `deinit { inFlight?.cancel() }` | the coroutine's own cancellation: `invokeOnCancellation { inFlight?.cancel() }` on the continuation, so a test that cancels the manager's scope at teardown (`cancelAndJoin`) doesn't leave a fake load running. Production never cancels the scope. |
| Task cancellation ignored by the checked continuation | a `CancellationException` from the scope must not be caught as a failure: the loop's catch takes `PilgrimageException` (and, Android only, other non-cancellation `Throwable`s, §C2.12), never `CancellationException` |
| an inline completion before `inFlight` is assigned | the same: `inFlight = loader.load…{…}` assigns after an inline callback; the catch clears it. Resuming inside the `suspendCancellableCoroutine` block is allowed. |

### C2.7 `remove`, `removeRegions`, `reconcile`, and the sweep

```swift
    /// Every region with the route's prefix. Packs no other region references
    /// are freed by the store; the style packs are shared and stay.
    func remove(routeId: String) {
        cancel()
        let prefix = Self.regionPrefix(routeId: routeId)
        for region in loader.regions() where region.id.hasPrefix(prefix) {
            loader.removeRegion(id: region.id)
        }
    }

    /// Update's retired indices: the same range `PilgrimagePackageManager`
    /// hands to `retireMany`. Nothing is downloaded on the walker's behalf.
    func removeRegions(routeId: String, atOrAbove index: Int) {
        let prefix = Self.regionPrefix(routeId: routeId)
        for region in loader.regions() where region.id.hasPrefix(prefix) {
            if let stageIndex = Self.stageIndex(of: region.id, prefix: prefix), stageIndex >= index {
                loader.removeRegion(id: region.id)
            }
        }
    }
```
> Pilgrim/Models/Honor/PilgrimageTilesManager.swift:332-351@7c200bf

- **`remove(routeId)`**: `cancel()` first, always (any save, of any route, stops; the phase goes `.idle`, clearing a `.failed` line), then one cache read and a `removeRegion` for every region with the prefix: complete, partial, stale, or past the stage count. Style packs stay (D5, confirmed: no code path anywhere removes a style pack). `testRemoveCancelsAnInFlightSave`, `testRemoveClearsExactlyThePrefixAndLeavesPacks`.
- **`removeRegions(routeId, atOrAbove: n)`**: no cancel. One cache read; removes prefixed regions whose index parses and is `>= n`. A prefixed region with an unparsable suffix stays (`remove` and the sweep would take it). Downloads nothing (`testRetiredIndicesAreRemovedAndNothingIsDownloaded`).
- Both iterate a snapshot array (`loader.regions()` returns a copy), so removing while iterating is safe; Kotlin must iterate a copy too (`regions()` returning a fresh `List`).
- Both read **the cache** and act at once. Neither waits for the store (D6, below).

```swift
    func reconcile(installed: (routeId: String, stageCount: Int)?) {
        // The sweep must run on a real snapshot, and never on a save's
        // behalf: a request from a launch with nothing installed would
        // otherwise delete the first region a later save writes.
        sweepGeneration += 1
        let generation = sweepGeneration
        loader.refreshRegions { [weak self] in
            guard let self, self.sweepGeneration == generation else { return }
            self.sweep(installed)
        }
    }

    private var sweepGeneration = 0

    private func sweep(_ installed: (routeId: String, stageCount: Int)?) {
        for region in loader.regions() where region.id.hasPrefix("pilgrimage:") {
            if let installed {
                let prefix = Self.regionPrefix(routeId: installed.routeId)
                // `stageIndex` is nil for a region of another route, so one
                // condition covers a foreign prefix and an unreadable index.
                let index = Self.stageIndex(of: region.id, prefix: prefix)
                if let index, index < installed.stageCount { continue }
            }
            loader.removeRegion(id: region.id)
        }
    }
```
> Pilgrim/Models/Honor/PilgrimageTilesManager.swift:358-383@7c200bf

- **`reconcile(installed:)`**: bump `sweepGeneration`, capture it, ask the store (`refreshRegions`). It doesn't wait; it returns at once. When the store's answer lands, the completion sweeps only if no later `reconcile` and no save past the door has bumped `sweepGeneration` since.
- **The sweep** reads the cache (which the answer has just written: the loader runs completions after the cache, `MapboxTileRegionLoader.swift:236-238@7c200bf`). It keeps a `pilgrimage:` region only when something is installed, the region has the installed prefix, its index parses, and the index is `< stageCount`. Everything else with the `pilgrimage:` prefix goes. Regions without that prefix are never touched (none exist; Mapbox's ambient cache isn't a region).
- **`installed == nil`** removes every `pilgrimage:` region (`testReconcileWithNothingInstalledRemovesEveryRegion`). This is D3's mechanism (§C2.10; item 3 in §C2.13).
- **Idempotent**: a second reconcile against the same store removes nothing (`testReconcileRemovesForeignAndOutOfRangeRegionsAndIsIdempotent`).
- **Rides on the store's answer, not on a difference**: `testReconcileSweepsOnceTheStoreAnswers` (nothing removed before the fake answers; exactly once after; a second answer doesn't sweep again, since the completion is delivered once).
- **Packs-only changes don't run it**: the sweep hangs off `refreshRegions`' completion, never off `onChange`, so `.packs` can't trigger it (`testAPacksOnlyChangeDoesNotRunThePendingSweep`).
- **A save past the door cancels it; a refused save doesn't** (`testASaveCancelsAPendingLaunchSweep`, `testASaveRefusedWhileWalkingLeavesThePendingLaunchSweep`). A save that has already run to its end before the store answers still cancels it (the bump stays), so a launch reconcile with nothing installed never deletes a later save's regions (`testAReconcileFromAnEmptyLaunchNeverSweepsALaterSave`).
- `cancel()` does **not** bump `sweepGeneration`, so `remove()` doesn't cancel a pending sweep.

**How the store answers (what the reconcile rides on, from C3's file).**

```swift
    func regions() -> [TileRegionSummary] {
        refresh()
        return cached
    }

    func refreshRegions(completion: @escaping () -> Void) {
        pendingRegionsCompletions.append(completion)
        refresh()
    }

    func removeRegion(id: String) {
        tileStore.removeTileRegion(forId: id)
        // A refresh already in flight is older than this removal; its
        // snapshot would put the region back.
        refreshGeneration += 1
        cached.removeAll { $0.id == id }
        onChange?(.regions)
    }
```
> Pilgrim/Models/Honor/MapboxTileRegionLoader.swift:157-174@7c200bf

```swift
            group.notify(queue: .main) { [weak self] in
                guard let self, token == self.refreshGeneration else { return }
                // Taken out from under the equality guard below, which is
                // silent when the answer matches the cache — and an empty
                // store answering an empty cache is exactly the launch case
                // that has to be heard. A stale token returns above and
                // leaves these for the refresh that overtook it.
                let waiting = self.pendingRegionsCompletions
                self.pendingRegionsCompletions = []
```
> Pilgrim/Models/Honor/MapboxTileRegionLoader.swift:221-229@7c200bf

- Every `refresh()` bumps `refreshGeneration`; only the newest answer writes the cache and drains the waiting completions. A failed `allTileRegions` answer drains them too, leaving the cache as it was (:191-203). So a `refreshRegions` completion runs on the first **current** answer, success or failure.
- `removeRegion` and a region load's success also bump `refreshGeneration` (:140, :171) **without starting a refresh**. A refresh in flight at that moment is then dropped, and its waiting completions wait for the next refresh, which the next `regions()` read starts. For the launch sweep this means: a `removeRegion` or a load success landing while the reconcile's refresh is in flight defers the sweep to the next reader's refresh. A save's load success can't matter (the save already cancelled the sweep). A hook's removal before the first answer can't happen (the cache is empty, so `remove` and `removeRegions` call `removeRegion` for nothing). So the deferral needs a hook with a warm cache while a reconcile is still waiting. On iOS that can't happen at launch: the loader is built in the same main-actor run that calls `reconcile`, so the cache is cold until the reconcile's own answer. On Android it can, because the launch work posts the reconcile after slower steps (§C2.10) and a surface may have warmed the cache first; the removal's own `.regions` signal makes the surfaces re-read, which starts the next refresh, so the sweep runs a moment later. Not a defect; nothing to file. U44's fake can model it only by adding a "refresh made stale" switch, which iOS's fake lacks; not needed for parity.

**Which operations read the cache, which wait for the store (D6, confirmed).**

| Operation | Reads | Before the store's first answer |
|---|---|---|
| `isStageSaved`, `status`, `footprint` | the cache | not saved / `.none` / `(0, 0)`; the overview, route page, Data card and Maps screen correct themselves on the `regionsChanged` that the answer fires when it differs from the empty cache. "The day" doesn't: it reads once at the tap. |
| `save`'s skip snapshot, `hasStylePack` | the cache | every pack and every stage is loaded again (the store then fetches only what's missing, but every load still runs) |
| `remove`, `removeRegions` | the cache | **nothing is removed**. A Remove right after launch, with the launch sweep captured while the route was still installed, leaves the route's regions until the next launch's sweep. |
| `calibrate` | the cache | (only after a whole loop) |
| `reconcile`'s sweep | the store's answer, then the cache it wrote | waits, by design |

On iOS the cold window is the time from the loader's `init` (at launch, §C2.10) to the first current answer, under a second on a phone with saved maps (survey D6). The UI paths that could hit it (a Remove, a save, "the day") all need the walker to reach a screen first. D6 is matched as shipped and filed; Android's surfaces add a bounded wait (§C2.12), and its hooks don't.

### C2.8 The fake loader (U44 ports it)

```swift
final class FakeTileRegionLoader: TileRegionLoading {

    final class Handle: TileLoadHandle {
        private(set) var isCancelled = false
        func cancel() { isCancelled = true }
    }
```
> UnitTests/Honor/FakeTileRegionLoader.swift:8-13@7c200bf

```swift
    var onChange: ((TileStoreChange) -> Void)?

    var stylePacks: Set<StylePackRequest> = []
    private(set) var stored: [String: TileRegionSummary] = [:]
    private(set) var regionsReadCount = 0
    var regionRequests: [TileRegionRequest] = []
    private(set) var packRequests: [StylePackRequest] = []
    private(set) var removedIds: [String] = []
    private(set) var pendingRegions: [PendingRegion] = []
    private(set) var pendingPacks: [PendingPack] = []

    /// Whether there is a load for a test to complete. A test that drives the
    /// fake before the save has reached its next load parks the save on a
    /// continuation nobody will resume.
    var hasPendingWork: Bool { !pendingRegions.isEmpty || !pendingPacks.isEmpty }

    /// When set, `completeNextRegion()` fails with it instead of storing.
    var nextRegionFailure: TileRegionLoadingError?
    /// Resource count a completed region reports; tests that care set it.
    var requiredResourcesPerRegion = 10
    var bytesPerRegion = 100_000

    func hasStylePack(_ pack: StylePackRequest) -> Bool { stylePacks.contains(pack) }
```
> UnitTests/Honor/FakeTileRegionLoader.swift:32-54@7c200bf

```swift
    func regions() -> [TileRegionSummary] {
        regionsReadCount += 1
        return Array(stored.values)
    }

    private var pendingRegionsCompletions: [() -> Void] = []

    /// Never answered inline: production always answers asynchronously, and
    /// a fake that answered on the spot would let a test prove a launch
    /// sweep ran before the store had spoken. `releaseRegions()` is the
    /// store speaking.
    func refreshRegions(completion: @escaping () -> Void) {
        regionsReadCount += 1
        pendingRegionsCompletions.append(completion)
    }

    func removeRegion(id: String) {
        removedIds.append(id)
        stored[id] = nil
        onChange?(.regions)
    }
```
> UnitTests/Honor/FakeTileRegionLoader.swift:73-93@7c200bf

```swift
    func completeNextPack() {
        guard !pendingPacks.isEmpty else {
            XCTFail("completeNextPack called with nothing pending")
            return
        }
        let pending = pendingPacks.removeFirst()
        stylePacks.insert(pending.pack)
        onChange?(.packs)
        pending.completion(.success(()))
    }

    func completeNextRegion() {
        guard !pendingRegions.isEmpty else {
            XCTFail("completeNextRegion called with nothing pending")
            return
        }
        let pending = pendingRegions.removeFirst()
        if let failure = nextRegionFailure {
            nextRegionFailure = nil
            pending.completion(.failure(failure))
            return
        }
        let summary = TileRegionSummary(id: pending.request.id,
                                        completedResourceCount: requiredResourcesPerRegion,
                                        requiredResourceCount: requiredResourcesPerRegion,
                                        completedResourceSize: bytesPerRegion,
                                        metadata: ["corridorHash": pending.request.corridorHash])
        stored[summary.id] = summary
        onChange?(.regions)
        pending.completion(.success(summary))
    }

    /// The store answering: what the real loader does when a regions read
    /// lands — every completion waiting on it runs, once, after the cache.
    /// The real loader is silent when the answer matches its cache, and an
    /// empty store answering an empty cache is the launch case; a fake that
    /// signalled there would let a test pass on a signal production never
    /// sends.
    func releaseRegions() {
        let waiting = pendingRegionsCompletions
        pendingRegionsCompletions = []
        for completion in waiting { completion() }
        if !stored.isEmpty { onChange?(.regions) }
    }

    /// The style-pack answer on its own. On a phone that has saved maps this
    /// is the signal that arrives first, one round trip ahead of the regions.
    func firePacksChange() {
        onChange?(.packs)
    }

    /// Seeds a region as though a previous save stored it.
    func seed(id: String, corridorHash: String, complete: Bool = true, bytes: Int = 100_000) {
        stored[id] = TileRegionSummary(id: id,
                                       completedResourceCount: complete ? 10 : 4,
                                       requiredResourceCount: 10,
                                       completedResourceSize: bytes,
                                       metadata: ["corridorHash": corridorHash])
    }

    func seedStylePacks() { stylePacks = Set(StylePackRequest.allCases) }
}
```
> UnitTests/Honor/FakeTileRegionLoader.swift:101-162@7c200bf

The fake's behaviour, which the Kotlin `FakeTileRegionLoader` must keep exactly (the ported tests depend on each point):

- **Loads queue; nothing completes on its own.** `loadStylePack` and `loadRegion` record the request (`packRequests`, `regionRequests`), queue a pending entry with a fresh `Handle`, and return the handle. A test completes them one at a time, FIFO (`removeFirst`).
- **A cancelled load stays queued.** `Handle.cancel()` only flips `isCancelled`; the pending entry isn't removed. `testCancelKeepsWhatIsDoneAndResumeStartsAtTheGap` relies on this: after a cancel and a resumed save, the first `completeNextRegion()` delivers the cancelled load's late completion (which the manager must ignore), so finishing two stages takes three completions.
- **`completeNextPack`**: inserts the pack into `stylePacks`, fires `onChange(.packs)`, **then** calls the completion.
- **`completeNextRegion`**: with `nextRegionFailure` set, fails with it once (clearing it), stores nothing, fires nothing. Otherwise stores a complete summary (`required = completed = requiredResourcesPerRegion`, default 10; size `bytesPerRegion`, default 100,000; metadata `corridorHash` = the request's), fires `onChange(.regions)`, **then** calls the completion. The signal-before-completion order is what `testTheManagerPublishesWhenTheLoaderAnnouncesAChange` reads synchronously.
- **Driving with nothing pending fails the test loudly** (`XCTFail`) instead of hanging. Kotlin: `fail("completeNextRegion called with nothing pending")` (JUnit's `Assert.fail` throws, which is louder than XCTest's and fine).
- **`regions()` counts reads** and returns `stored.values` (a `Dictionary`'s values: **unordered**). Tests compare with `Set`s where order could vary (`Set(loader.removedIds)`), and the manager sorts by stage index, so nothing depends on the order. A Kotlin `HashMap` matches; a `LinkedHashMap` is also fine since no test can depend on order.
- **`refreshRegions` never answers inline**; it counts as a read (`regionsReadCount += 1`) and queues the completion. `releaseRegions()` is the store speaking: it runs every waiting completion once, then fires `.regions` only if anything is stored (the real loader is silent when an empty store answers an empty cache).
- **`removeRegion`** records the id, drops it from `stored`, fires `.regions`, all synchronously.
- **`firePacksChange()`** fires `.packs` alone. **`seed`** stores a region without signalling (complete: 10 of 10; incomplete: 4 of 10; default bytes 100,000). **`seedStylePacks()`** sets both packs.
- `stylePacks` and `regionRequests` are settable by tests (`loader.stylePacks = [.light]`; `loader.regionRequests.removeAll()`).

Android additions to the fake, test-only and with no production counterpart in iOS's fake:
- **`failRegions()`**: drains the waiting `refreshRegions` completions without touching `stored` and fires nothing, which is what the real loader does on a failed `allTileRegions` answer (`MapboxTileRegionLoader.swift:191-203@7c200bf`; iOS's comment there: "the fake has no failing `allTileRegions`"). Needed for the cold-cache test "a failed first refresh also ends the wait" (§C2.12).
- Optionally **a partial completion** (`completeNextRegion(partial = true)`: success with `completed < required`), for flow-analysis item 7's test that a partial success counts as done and reads as unsaved. iOS doesn't test it; port behaviour, not a new rule.
- Thread: the fake is driven on the test's thread, which must be the manager's dispatcher's thread (a `StandardTestDispatcher` under `runTest`, or Robolectric's main looper), so its synchronous callbacks are on-thread as production's hopped ones are.

### C2.9 The package hooks, and the orphans they never reach (flow-analysis item 11)

**iOS.** PR #86 adds one optional property and three calls to the package manager:

```swift
    /// The saved basemap follows the package: Remove and Replace take a
    /// route's regions, Update takes the retired indices. Optional so the
    /// manager's own tests construct it without a tiles manager.
    var tiles: PilgrimageTilesManager?
```
> Pilgrim/Models/Honor/PilgrimagePackageManager.swift:42-45@7c200bf

```swift
        if let previous, previous.routeId != entry.id {
            // The ledger stays: a route that comes back finds its record.
            removeStagesAndPackage(routeId: previous.routeId, stageCount: previous.route.stageCount)
            tiles?.remove(routeId: previous.routeId)
        }
        clearReplacingMarker()
    }
```
> Pilgrim/Models/Honor/PilgrimagePackageManager.swift:245-251@7c200bf

```swift
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
```
> Pilgrim/Models/Honor/PilgrimagePackageManager.swift:262-271@7c200bf

```swift
        let stageCount = installed().flatMap { $0.routeId == routeId ? $0.route.stageCount : nil }
            ?? PilgrimageWayImporter.maxStageCount
        removeStagesAndPackage(routeId: routeId, stageCount: stageCount)
        tiles?.remove(routeId: routeId)
    }
```
> Pilgrim/Models/Honor/PilgrimagePackageManager.swift:280-284@7c200bf

- **Replace** (cross-route, after the new route's download succeeded): retire the old route's stages and package, then `tiles?.remove(previous)`, then clear the marker. A Replace of the route already installed is an Update and never calls `remove`.
- **Update** (after the download, the `installed()` re-read and the tail sweep): `tiles?.removeRegions(entry.id, atOrAbove: newCount)`, then the ledger. Even when the route didn't shrink (`atOrAbove` the same count removes only regions past it, normally none). Nothing downloads; a redrawn stage reads stale by hash.
- **Remove**: retire, then `tiles?.remove(routeId)`. `remove` cancels any save first.
- All three are synchronous steps inside one main-actor run with no `await` between the commit's return and the hook (download's `phase = .idle` is its last statement after the commit's await; `update` and `replace` continue synchronously after `download` returns). So no save can start between a commit and its hook on iOS.
- `tiles` is nil until the launch task or `chooseWay()` sets it (§C2.10, §C2.11); both run before the walker can reach a route page, so the hooks always reach the manager in production.

**Android at `ca6424db`.** The seam and its three calls are in place, inside the actor:

```kotlin
interface PilgrimageTiles {

    /** A route's saved regions, all of them: Remove, and the route a Replace lets go. */
    fun remove(routeId: String)

    /** The regions at stage indices an Update's route no longer has. */
    fun removeRegions(routeId: String, atOrAbove: Int)

    /** A map save in flight, which joins the route page's busy state in Stage 21-3. */
    val isSaving: Boolean
}
```
> app/src/main/java/org/walktalkmeditate/pilgrim/data/honor/pilgrimage/PilgrimagePackageManager.kt:51-61@ca6424db

```kotlin
        if (previous != null && previous.routeId != entry.id) {
            retireStagesAndPackage(previous.routeId, previous.route.stageCount)
            tiles?.remove(previous.routeId)
        }
        clearReplacingMarker()
    }
```
> app/src/main/java/org/walktalkmeditate/pilgrim/data/honor/pilgrimage/PilgrimagePackageManager.kt:422-427@ca6424db

```kotlin
        store.retireMany(stageIds(entry.id, newCount until maxOf(previousStageCount, newCount)), signals.liveSessionWayIds())
        tiles?.removeRegions(entry.id, atOrAbove = newCount)
```
> app/src/main/java/org/walktalkmeditate/pilgrim/data/honor/pilgrimage/PilgrimagePackageManager.kt:436-437@ca6424db

```kotlin
        retireStagesAndPackage(routeId, stageCount)
        tiles?.remove(routeId)
    }
```
> app/src/main/java/org/walktalkmeditate/pilgrim/data/honor/pilgrimage/PilgrimagePackageManager.kt:451-453@ca6424db

- The order of each step matches iOS. The seam has the three members it needs; U44 adds none. `isSaving` has no reader in the package manager today (only `FakeTiles` implements it); the route page reads the tiles manager's phase directly (plan). Keep `isSaving` as `phase.value is Phase.Saving`, a thread-safe `StateFlow` read, so the seam stays as Stage 21-2 left it.
- The calls run on `Dispatchers.IO` holding the actor, and "must not call back into it" (KDoc :45-50). The walk-guard tests prove a package operation asked from inside a tiles call waits for the actor (`PilgrimagePackageWalkGuardTest.kt:258-315@ca6424db`). The tiles manager's `remove`/`removeRegions` therefore post to the manager's thread and return at once (§C2.12); they never touch the package manager.
- `@Inject constructor` passes no tiles (:110-126); U45 wires it.

**Android-only window between a commit and its hook.** On Android `transfer` publishes `Phase.Idle` right after the commit (:291-292), while the operation still holds the actor for its post-commit steps (the Update's tail sweep, `removeRegions`, the ledger; the Replace's retire and `remove`). The route page's maps row is held only by the package phase (`mapsRowIsHeld`, C4), so for that short stretch it's live while the page still holds the old stage values. A save tapped then would load the old stage list and can reload regions `removeRegions` is about to retire, or has just retired, leaving them until the next launch's sweep. iOS can't reach this (no gap). Fix in U46, not in the engine: hold the maps row while the page itself is held (Android's `held`, which already spans an install through its reload, `PilgrimageRouteViewModel.kt:349-374@ca6424db`). The page's `held` never spans a save, so the row's own cancel stays reachable as iOS requires. Record it as the Android equivalent of iOS's no-gap run.

**The three orphan paths the hooks never reach (item 11), traced on both platforms.**

1. **A failed Update's rollback.** iOS: the commit throws (a full disk is the likely cause), `rollBack` retires every stage up to the larger count and deletes `route.json` and `release.txt` (`PilgrimagePackageManager.swift:420-437@7c200bf`); `update` rethrows from `download`, so `tiles?.removeRegions` never runs, and nothing calls `tiles?.remove`. The route is no longer installed, but its regions stay. Android: identical: the commit's rollback (`retireStagesAndPackage` in `NonCancellable`, `PilgrimagePackageManager.kt:370-381@ca6424db`) and `updateOnActor` rethrows from `downloadOnActor` before `tiles?.removeRegions`. Both heal at the next launch, whose `installed()` reads nil (or another route) and whose sweep takes the regions. Meanwhile Settings → Data → Maps reads "no maps saved", because its model asks `installed()` first (C4), so the bytes are invisible and undeletable until then. **Android matches.** (pilgrim-ios #119 item 2 files the rollback; the maps consequence isn't in it.)
2. **`installed()` finishing a killed Replace.** iOS: a kill between the new route's commit and `tiles?.remove(previous)` leaves the marker; the next `installed()` (the launch's, or any screen's) removes the abandoned route's stages and package (:101-106) but never calls `tiles?.remove`. The regions survive until the launch sweep. At the pin the launch's own `installed()` is the reconcile's input (§C2.10), so the abandoned route's regions go in **that same launch**, once the store answers, whichever `installed()` call actually finished the swap. Android: `installedOnActor()` retires the abandoned route the same way (`:458-463@ca6424db`) with no tiles call, and `runAtLaunch()`'s read feeds the reconcile (U45), so the same launch's sweep takes them. One Android difference: if the abandoned route's retire throws (below), `runAtLaunch()` throws, the plan skips the sweep, and the marker stays (it's cleared only after the retire), so the next `installed()` retries and the next clean launch sweeps. **Android matches**, with the thrown-read skip as a recorded addition.
3. **A retire that throws before the hook.** iOS: `removeStagesAndPackage` can't throw (`try?` throughout, :293-298), so Replace and Remove always reach their `tiles?.remove`. Android: `retireStagesAndPackage` can throw (`store.retireMany` reads `signals.liveSessionWayIds()`, a Room query; its `finally` still deletes the two package files, `:391-401@ca6424db`). In Replace (`:422-425`) and Remove (`:451-452`) the throw skips `tiles?.remove`, so the route is gone but its regions stay; in Replace the marker also stays. The operation fails to the page; the next launch's sweep takes the regions (the route is no longer installed). **Android-only path, heals the same way.** No iOS defect to file; record it with the other Android additions. U45 could move `tiles?.remove` into the `finally` beside the package files, but that changes a Stage 21-2 ordering for a failure iOS can't have; the default is to leave it and let the launch heal it.

### C2.10 The launch: wiring, the reconcile, and where #91's step sits

**iOS at the pin.**

```swift
    private func runPostDoneLaunchTasks() {
        #if DEBUG
        // Ship-gate harness (Task 10): only ever produces output under
        // `--senses-field-report` — `runIfRequested` carries that guard
        // itself. The Task hop keeps the @MainActor call clean from this
        // non-actor-annotated completion closure.
        Task { @MainActor in DossierSensesFieldReport.runIfRequested() }
        #endif
        startLaunchRecordingCleanup()
        sweepExpiredWays()
        reconcileTilesAtLaunch()
    }

    /// Once per process launch, after the store is readable: a kill
    /// mid-Replace is finished by installed()'s marker branch, which no
    /// lifecycle hook sees, so the tiles manager sweeps whatever the
    /// installed route does not account for.
    ///
    /// Skipped under XCTest like its neighbours: the sweep runs against the
    /// shared tile store, which would race a unit test writing its own
    /// fixtures into that same process-global tree.
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
> Pilgrim/AppDelegate.swift:163-192@7c200bf

- Runs once per process launch, from `DataManager.setup`'s completion, after `appLaunchState = .done` (:98-101). Order: recording cleanup, then `sweepExpiredWays()` (a **detached** task, :198-210), then `reconcileTilesAtLaunch()` (a main-actor Task). The expiry sweep and the reconcile are therefore **unordered** with each other; neither waits for the other.
- Inside the Task, in order: (a) wire `packages.tiles`, whose first touch of `PilgrimageTilesManager.shared` builds the production loader, whose `init` starts the first store refresh; (b) `installed()` (at the pin this is the launch's only `installed()` call, so it is the one that finishes a killed Replace before any screen asks, Stage 21-2 spec §12); (c) `reconcile` with `(routeId, stageCount)` or nil. `reconcile` returns at once; the sweep happens when the store answers.
- `installed()` can't throw; its read failures become "not this route" (`try?`, :92-96), so nothing installed reads as nil and sweeps everything (D3).
- Skipped under XCTest.

**Post-pin, placement only (`@e551b11`, iOS PR #91).** The function is renamed and gains one step after the reconcile, inside the same Task, fed by the same `installed` read:

```swift
    private func reconcilePilgrimageAtLaunch() {
        guard NSClassFromString("XCTestCase") == nil else { return }
        Task { @MainActor in
            PilgrimagePackageManager.shared.tiles = PilgrimageTilesManager.shared
            let installed = PilgrimagePackageManager.shared.installed()
            PilgrimageTilesManager.shared.reconcile(
                installed: installed.map { (routeId: $0.routeId, stageCount: $0.route.stageCount) })
            if let installed {
                PilgrimagePackageManager.shared.restampStageHours(of: installed)
            }
        }
    }
```
> Pilgrim/AppDelegate.swift:188-199@e551b11

So #91's restamp takes the whole `Installed` value (not just its id and count), runs only when something is installed, and comes after the reconcile call (which has returned at once, so the restamp doesn't wait for the sweep). The same diff (`7c200bf..e551b11`) also adds `MapboxTelemetry.optOut()` after the tile-store line (:55-57@e551b11); that's PR #92's, for the gate's telemetry check, not this stage.

**Android at `ca6424db`.**

```kotlin
        if (!isMainProcess()) {
            Log.i(TAG, "onCreate: skipping UI inits in non-main process ${getProcessName()}")
            return
        }
```
> app/src/main/java/org/walktalkmeditate/pilgrim/PilgrimApp.kt:266-269@ca6424db

```kotlin
        if (releaseFlagsProvider.get().honor) {
            val honorFinalizer = honorFinalizerProvider.get()
            walkFinalizationScopeProvider.get().launch { honorFinalizer.runAtLaunch() }
        }
```
> app/src/main/java/org/walktalkmeditate/pilgrim/PilgrimApp.kt:437-440@ca6424db

```kotlin
    suspend fun runAtLaunch() {
        deferringFailure("launch Honor maintenance") {
            finalizePending()
            sweepStaging()
        }
        deferringFailure("launch pilgrimage packages", packageLaunchWork)
        deferringFailure("launch expiry sweep", expirySweep)
    }

    private suspend fun deferringFailure(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            Log.w(TAG, "$what deferred (${e::class.simpleName})")
        }
    }
```
> app/src/main/java/org/walktalkmeditate/pilgrim/walk/honor/HonorFinalizer.kt:153-170@ca6424db

```kotlin
        packageLaunchWork = { if (releaseFlags.honor) packageManager.get().runAtLaunch() },
```
> app/src/main/java/org/walktalkmeditate/pilgrim/walk/honor/HonorFinalizer.kt:114@ca6424db

```kotlin
    suspend fun runAtLaunch() {
        onActor {
            installedOnActor()
            sweepTempSets()
        }
    }
```
> app/src/main/java/org/walktalkmeditate/pilgrim/data/honor/pilgrimage/PilgrimagePackageManager.kt:220-225@ca6424db

- UI process only (non-main processes return before any UI init, and before `MapboxOptions.accessToken` at :276), flag on only, launched on the walk-finalization scope (`SupervisorJob() + Dispatchers.IO` + a logging `CoroutineExceptionHandler`, `P/di/WalkModule.kt:73-79@ca6424db`), after the blocking stale-walk recovery. Every UI process start runs it, including one while `:tracker` walks.
- The order inside: pending Honor steps and the staging sweep, then the package work, then the expiry sweep, each in `deferringFailure` (catches `Exception`, not `Error`; rethrows cancellation). Unlike iOS, the expiry sweep runs **after** the package work and waits for it.

**What U45 builds.**
1. **`runAtLaunch()` returns `Installed?`**, the value `installedOnActor()` returned, after the temp sweep. Not `(routeId, stageCount)` as the plan says: #91's restamp takes the whole `Installed` (`restampStageHours(of: installed)` above), and the #91 plan will need it from the same read. The reconcile maps it to `(routeId, route.stageCount)`.
2. **`packageLaunchWork`** becomes, in iOS's order: resolve the tiles manager and start its first store read (iOS's wiring step, whose first touch of `shared` builds the loader and its `init` refresh; on Android, request the first-answer latch's `refreshRegions` without waiting on it, §C2.12 point 7), so the cache warms at launch even when the read below throws; `val installed = packageManager.get().runAtLaunch()`; `tiles.get().reconcile(installed?.let { InstalledRoute(it.routeId, it.route.stageCount) })`. The #91 plan later adds `if (installed != null) restamp(installed)` after the reconcile. All inside the one `deferringFailure` block, so a throw from `runAtLaunch()` skips the reconcile (and, later, the restamp) and the expiry sweep still runs.
3. **`reconcile` must not suspend for the store.** It posts to the manager's thread (bump, `refreshRegions`) and returns, as iOS's returns at once. Otherwise the expiry sweep, which on Android runs after the package work, would wait for the tile store (flow-analysis residual). A short suspend that only waits for the post to run is acceptable; waiting for the sweep is not.
4. **A thrown read skips the sweep this launch** (plan; an Android addition, since iOS's read can't throw). The throw sources at `ca6424db`: a failed retire of a killed Replace's abandoned route inside `installedOnActor()` (the `liveSessionWayIds()` Room read, or the store's I/O), or a Ways store root that can't be resolved. In production the root is `File(context.noBackupFilesDir, "Ways")` (`P/di/WayStoreModule.kt:26-27@ca6424db`), which doesn't throw in practice, so the retire is the real source; the root case is what the existing tests inject (`WayStore({ throw IOException(…) })`, `HonorFinalizerTest.kt:671@ca6424db`). A decode or read failure of `route.json`/`release.txt` doesn't throw: `readInstalled` maps `IOException` and `PilgrimageException` (every importer refusal, including any serialization failure, `PilgrimageWayImporter.kt:159-165@ca6424db`) to null, so it reads as nothing installed and sweeps every saved map, as on iOS (D3, matched).
5. **The XCTest skip's analogue.** iOS skips the launch reconcile under XCTest because the sweep would hit the shared tile store. On Android: (a) the production loader is device-only and never built in a JVM test (Robolectric can't load the natives, plan Risks); (b) `HonorFinalizer`'s internal constructor defaults `packageLaunchWork` to `{}` (`HonorFinalizer.kt:96@ca6424db`), so every test that builds a finalizer directly runs no package or tiles launch work unless it passes one; (c) the package tests run under a plain `Application` (`@Config(application = Application::class)`), so `PilgrimApp.onCreate` never launches anything. U45's launch-work test passes a recording `packageLaunchWork` or a fake tiles provider, as the existing `the package manager is built only by a launch with the release flag on, never by the tracker's finalize` does (`HonorFinalizerTest.kt:751-786@ca6424db`).
6. **`:tracker` and flag off never resolve the tiles provider**: extend that same test with a counting `Provider<PilgrimageTilesManager>`: 0 with the flag off, 0 after `finalize`/`finalizePending` (the tracker's path), 1 after a flag-on `runAtLaunch()`.

**Where the reconcile sits, side by side.**

| Step | iOS (`runPostDoneLaunchTasks`) | Android (`HonorFinalizer.runAtLaunch`) |
|---|---|---|
| Expiry sweep | started first, detached; unordered with the rest | last, after the package work |
| Wire tiles / build the loader | first step of the main-actor Task | first step of `packageLaunchWork` (U45) |
| `installed()` (finishes a killed Replace) | second step | `runAtLaunch()`'s `installedOnActor()`, then the temp-set sweep |
| `reconcile(installed)` | third step; returns at once | right after a clean `runAtLaunch()`; posts and returns |
| #91 restamp (`@e551b11`) | fourth step, only when installed | after the reconcile, same condition (the #91 plan) |
| A thrown read | impossible | reconcile and restamp skipped; expiry sweep still runs |

**Android ordering note (no change needed).** The launch work runs on IO after `finalizePending` and the staging sweep, so the UI is live before the reconcile is posted, and a save could pass its door first. Then the reconcile's later bump isn't cancelled by that save, and its sweep runs on the next answer. It's safe: the `installed` it carries was read under the package actor, so it's the route the save is for (a save needs the route page to show it installed), and the sweep keeps the installed route's regions below its count. Record it with the gate rows as a consequence of Android's slower launch work, not a behaviour change.

### C2.11 The walk guard: iOS's closure against Android's two clauses (flow-analysis item 5)

**iOS.**

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

```swift
        let vm = ActiveWalkViewModel(mode: mode, way: way)
// …
        activeWalkViewModel = vm
    }

    func cancelWalk() {
        activeWalkViewModel?.cancel()
        activeWalkViewModel = nil
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:94,137-142@7c200bf

- `activeWalkViewModel != nil` is true from `startWalk` (the walk screen opening, **before the walker taps Start**, any walk mode, after the location-permission check at :88-92) until the walk's save succeeds (`self.activeWalkViewModel = nil`, :123) or the walk is cancelled (:141-142). After a failed walk save it stays set until the walker dismisses "Save Failed" (`MainTabView.swift:107-110@7c200bf`, whose "Dismiss" sets it nil).
- The closure is wired only in `chooseWay()` (the Ways sheet opening), asynchronously. Before that the tiles manager's `isWalkActive` is `{ false }`. The route page is reachable only through the Ways sheet's catalog (`HonorWaysSheet.swift:110` → `PilgrimageCatalogView.swift:91@7c200bf`), so the closure is always wired before a save can start.
- It's checked synchronously at the door and at **every step** of the loop: before each pack and each stage, whether that step then loads or skips (:207, :222, :239). It's never checked after a load completes.
- On iOS the walk screen is a full-screen cover over the tabs, so a save can't be *tapped* mid-walk; the door matters for a save already running when the walker opens the walk screen, which the next step refuses.

```swift
        .fullScreenCover(item: $coordinator.activeWalkViewModel, onDismiss: {
// …
        .alert("Save Failed", isPresented: $coordinator.showSaveError) {
            Button("Dismiss") {
                coordinator.activeWalkViewModel = nil
            }
```
> Pilgrim/Scenes/Root/MainTabView.swift:42,107-110@7c200bf

**Android at `ca6424db`.**

```kotlin
    /** iOS's own clause: the walk screen up, pre-Start included, for any walk mode. */
    fun walkScreenUp(): Boolean

    /** A walk row still open: `:tracker` walking, as after the UI process was reclaimed. */
    suspend fun walkActive(): Boolean
```
> app/src/main/java/org/walktalkmeditate/pilgrim/data/honor/pilgrimage/PilgrimageWalkGuard.kt:22-26@ca6424db

```kotlin
    override fun walkScreenUp(): Boolean = router.get().walkScreenUp

    override suspend fun walkActive(): Boolean = walks.getActiveWalk() != null
```
> app/src/main/java/org/walktalkmeditate/pilgrim/data/honor/pilgrimage/PilgrimageWalkGuard.kt:72-74@ca6424db

```kotlin
        walkScreenUp = Routes.ACTIVE_WALK in backStack,
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHost.kt:896@ca6424db

- `walkScreenUp()` is iOS's clause: any live nav host with `ACTIVE_WALK` in its back stack, pre-Start included, anything over it included; a `@Volatile` flag, synchronous, safe from any thread (`HonorLinkRouter.kt:108-116@ca6424db`). Always false with the release flag off.
- `walkActive()` is Android's: a walk row with no end time, which covers `:tracker` walking while the UI process restarted and before its nav host shows the walk screen again. A suspending Room query.
- The package guard's other clauses (live Honor session rows, a Begin in flight, the `finalizePending()` retry) exist to keep `:tracker` from reading a package that changes under it. Tiles never reach `:tracker`, and a Begin in flight implies the walk screen is up. So the plan's two clauses are the right set. Confirmed.

**Where to check them (a correction to the plan's "at the door and again before each load").** iOS checks at every step, loads and skips alike. Two ways to port that:
- (a) Both clauses at every step. Faithful in coverage, but every step then suspends on a Room read, even a skip. A re-tap of "maps saved · N MB" with everything current would then publish `saving(0, n)` … `saving(n, n)` across about 35 Room reads (tens of milliseconds) and flash "maps · stage k of n" with a cancel, and the route page's `isBusy` would flash the download button and Remove off. On iOS the same re-tap runs to its end in one main-actor run with no `await`, so SwiftUI sees `.idle` before and after and draws nothing (D7's "nothing visible").
- (b) **Recommended:** iOS's clause, `walkScreenUp()`, synchronously at the door and at every step, exactly where iOS checks; Android's `walkActive()` at the door and immediately before each actual load (pack or region), each read followed by the generation re-check (§C2.12). A skip step then never suspends, so an all-current re-tap runs in one dispatch and the phase flow conflates back to `Idle` (no flash, as iOS), while no load can start under a walk on either clause. The only case (b) treats differently from (a) is a `:tracker` walk whose walk screen isn't up, revived during a stretch of skips: (a) would stop at the first skip, (b) at the next load or not at all if only skips remain. Since that clause is Android's own, (b) is within Android's discretion.
- Gate row: "iOS's clause where iOS checks, plus Android's revived walk at the door and before each load".

**The door's suspending read** needs the claim and the re-check (§C2.12). The door in (b): `walkScreenUp()` first (synchronous; refuse at once, as iOS), then `walkActive()` (suspends; claim held).

### C2.12 The Android concurrency the plan decided, checked against iOS

iOS gets every guarantee below from `@MainActor` plus synchronous closures: no two of the manager's steps interleave except at its `await`s, which are only the loads. Android has to rebuild that. Each Key Technical Decision, checked:

**1. Main-thread confinement: faithful; settle it as the main thread.** The manager's mutable state (`phase`, `generation`, `sweepGeneration`, `pending`, `inFlight`, the door claim, the first-answer latch) is read and written on one thread. It must be **the main thread**, not a private single-thread dispatcher: the manager calls the loader synchronously (`hasStylePack`, `loadStylePack`, `loadRegion`, `regions`, `removeRegion`), and the loader calls `OfflineManager`, which is `@MainThread` throughout in 11.23.1 (Mapbox research §1). Inject a `mainDispatcher: CoroutineDispatcher` (production `Dispatchers.Main.immediate`; tests a `StandardTestDispatcher` on `runTest`'s scheduler) and an app-lifetime `scope` (`SupervisorJob()` + the handler in point 6). This settles the plan's deferred "Dispatchers.Main.immediate or a single-thread dispatcher".

**2. Seam calls posted from the package manager's IO actor, returning without waiting: faithful, and required.** `remove(routeId)` and `removeRegions(routeId, atOrAbove)` do `scope.launch(mainDispatcher) { … the iOS body … }` and return. Why it's equivalent: on iOS the hook's effect on the manager (cancel, cache removals, `removeTileRegion` calls) is synchronous, but the store's removal itself is asynchronous; no caller ever observes the store between the hook and its return. Why it's required: the package manager holds its actor while it calls the seam, and a test (or any code) blocking the main thread on a package operation (`installedBlocking()` is `runBlocking` on the test's main thread) would deadlock against a hook that waited for main. Posts from one thread run in post order, and the actor serializes the package manager's posts, so hooks keep their iOS order. Settings → Maps' Delete calls the same `remove` (iOS calls `tiles.remove` directly, `OfflineMapsView.swift:89@7c200bf`). `cancel()` is not posted: every caller is on the main thread, so it runs inline as iOS's does (main-only).

**3. `walkActive()` suspends where iOS's closure doesn't: the claim and the re-checks are needed, and sufficient.** The suspension points in an Android save are the door's `walkActive()` read, each per-load `walkActive()` read, and each load. iOS's only ones are the loads. A cancel (from the row, `remove`, or Settings' Delete) and a second tap can land at any of them. The rules:
- **The claim.** On entry (on the manager's thread): if `phase` is `Saving` or the door is claimed, return (iOS's one-at-a-time return). If `walkScreenUp()`, refuse at once as iOS (`phase = Failed(WALK_IN_PROGRESS)`, throw). Otherwise claim the door, capture `myGeneration = generation`, and read `walkActive()`. Release the claim when the read returns, however it returns. From the release to `phase = Saving(…)` the code doesn't suspend, so no second save can enter between them.
- **After the door's read:** if `generation != myGeneration` (a cancel landed during the read), end without touching the phase or `sweepGeneration` and throw `INCOMPLETE`, as a save cancelled mid-loop ends. If walking, `phase = Failed(WALK_IN_PROGRESS)` and throw, with `sweepGeneration` untouched (iOS's door). Else iOS's step 3: `sweepGeneration += 1`, `phase = Saving(0, 2 + n)`, `done = 0`.
- **After every per-load guard read:** `if (generation != myGeneration) throw INCOMPLETE`, then the walk result, then start the load. Without the first check, a cancel landing during the read would let the loop start a load under a stale generation; `cancel()` had no pending continuation to resume, and the new load's completion is ignored by generation, so the save would hang in `Saving` forever. This is exactly iOS's "a cancel can land between a load's completion and this hop" (:226-230) one suspension earlier.
- **The checks after each step** stay as iOS's (:230, :250).
- **Where the clauses are read**: iOS's `walkScreenUp()` synchronously at the door and every step; `walkActive()` at the door and before each actual load (§C2.11, option b).
- With these, the plan's two edge tests hold: "a cancel during the guard's read starts no further load and the save ends" and "a second save during the door's read runs no second loop".
- One window remains: from the tap to `phase = Saving` (one Room read) the route page's `isBusy` is false, so the download button and Remove are live for a few milliseconds. iOS sets `.saving` within the tap. Optional hardening, U44/U46's call: let the page's busy check also read the claim (expose it with the phase, e.g. a `busy: StateFlow<Boolean>`). Not required for correctness: an Update or Remove started in that window reaches the hooks, and `remove` cancels the save.

**4. A thrown guard read (Android only).** iOS's closure can't fail; a Room read can. A door read that throws: release the claim, `phase = Failed(INCOMPLETE)` (only if the generation is unchanged), throw `INCOMPLETE`. A per-load read that throws lands in the loop's catch as `INCOMPLETE` under iOS's generation rule. That follows iOS's comment "the row's catch relies on phase carrying every failure" (:208-209) and the package manager's own `(error as? PilgrimageError) ?? .incomplete`. Record it as an Android addition.

**5. The loop's catch.** Catch `CancellationException` first and rethrow it untouched (kotlinx's is an `IllegalStateException`). Then take any other `Throwable`: the error is `(t as? PilgrimageException)?.error ?: INCOMPLETE`; if `generation == myGeneration`, cancel and clear `inFlight`, `phase = Failed(error)`; rethrow `t` (a `PilgrimageException` as is). Catching every `Throwable` there, not only `Exception`, is what makes "the save ends as failed" true even for an `Error` from a Mapbox native, since the phase is set before the rethrow.

> **Superseded at U44's review (2026-10-07):** `save` runs its loop in `scope.launch(mainDispatcher)` and returns its own `CompletableDeferred`, so a caller cancelling what it holds never stops the save (iOS: cancelling the waiting task never reaches it); the deferred ends cancelled only when the scope is. The handler point below still holds for the posted work.

**6. The exception handler on the scope: keep it, but know what it covers.** `save` should return a `Deferred<Unit>` from `scope.async(mainDispatcher)` (the ported tests await it and assert what it throws; the route page ignores it, as iOS's row swallows), and a `Deferred`'s failure never reaches a `CoroutineExceptionHandler`. The handler catches only what escapes `launch`ed work: the hook posts, the reconcile post, and the sweep. Those bodies are iOS's and contain no catch; a loader bug there would otherwise crash the UI process. The handler logs (no route ids or region ids in the message, per the house logging rule the package manager follows). It can't end a save; point 5 does that. What it can't cover at all: SDK callbacks the loader hops to main with a bare `Handler.post`, which run outside any coroutine. C3: hop them through `scope.launch(mainDispatcher)` (or catch inside the posted block), so a throw there is logged, not fatal.

**7. The cold-cache wait: a platform equivalent for the surfaces; not for the hooks.**
- *Why iOS doesn't need it.* iOS builds the loader at launch (§C2.10), and every surface that reads the manager sits behind navigation that takes the walker longer than the store's first answer. Its walk screen, which hosts "the day", exists only in a process that started the walk, minutes earlier. The overview, route page and Settings that do read cold correct themselves on `regionsChanged`.
- *Why Android does.* The UI process restarts mid-walk, and restores "the day" open (`showStageDay` is `rememberSaveable`, C4), the overview, the route page and Settings within milliseconds of a fresh process, whose loader hasn't answered (and on Android is built by the launch work only after `finalizePending` and the staging sweep). "The day" reads once and never re-reads, so it would tell a walker with no signal that today's saved maps are missing. Waiting for the first answer restores iOS's practical invariant (the store has answered before a surface reads). **Platform equivalent.** Gate row: "readers wait, bounded, for the store's first answer after a process start".
- *What counts as the first answer.* The completion of `loader.refreshRegions` (iOS's own seam primitive, no new loader member): the first **current** regions answer, success or failure. A failed `getAllTileRegions` drains the completions (`MapboxTileRegionLoader.swift:191-203@7c200bf`), so a failed first refresh ends the wait (the plan's requirement holds). A **stale** answer does not end it, and shouldn't: it writes nothing, so a reader released by it would read the cold cache; the newer refresh that overtook it ends the wait instead. The one way a stale answer could strand the waiter (a `removeRegion` or a load success bumping the token with no new refresh) can't happen before the first answer: the cache is empty, so no hook or sweep calls `removeRegion`, and no load runs (the save's row appears only after the route page has waited). The packs answer is not waited for (`TileStoreChange`'s comment: "A reader waiting for what is on disk must not be woken by the packs").
- > **Superseded at U44's and U45's reviews (2026-10-07):** the latch rides on the seam's `firstAnswer` (C3 §7), not on `refreshRegions`. A `READ` stays settled for the process; a `FAILED` answer, or a request that throws, ends that wait as "unknown" and the next `awaitStore()` asks again. Every surface keeps the answer it got, so one that was told "unknown" asks again on its next read (the route page on its next reload, Settings on its next refresh).
- *Mechanics (U44).* A latch on the manager's thread: on the first call, `loader.refreshRegions { answered = true; firstAnswer.complete(Unit) }`; afterwards `answered` stays true for the process. `suspend fun awaitStore(): Boolean` (name U44's) = `withContext(mainDispatcher) { request-once; withTimeoutOrNull(BOUND) { firstAnswer.await() } != null }`. The synchronous readers (§C2.4) stay as iOS's, and a surface calls `awaitStore()` once before its first read. This keeps the ported read-count tests exact (`regionsReadCount` 1 for the footprint, 2 for a save): the latch's one `refreshRegions` happens only when a surface asks or the launch work warms it (U45, §C2.10 point 2), never inside a save or a reader.
- *The bound.* **5 seconds from when the surface starts waiting.** The first answer is a local database read plus one metadata read per region, normally well under a second (survey D6); 5 s covers a slow cold start and still fills "the day"'s line while the walker looks at it. U48 measures the real first-answer time after a UI kill on the owner's phone and records it.
- *Past the bound* `awaitStore()` returns false ("unknown"). The morning card then gets `mapsLine = null` (no line), from the overview and from "the day" (Android addition: on iOS a cold read would draw "no offline maps for today — save on wifi"; recorded at the gate). The route page and Settings read the cache anyway, which is iOS's cold answer, and correct themselves on `regionsChanged` when the store does answer, as iOS's do. After an "unknown", both ask the store again on their next read (the route page's next reload; Settings' next entry or regions-changed) until one has its answer, since after a failed one nothing may be left in flight to signal (U46's and U47's fixes).
- *The hooks don't wait* (a correction to the plan's "Readers and the package hooks therefore wait"). `remove` and `removeRegions` read the cache at once, as iOS's do; cold, they remove nothing, and the regions stay until the next launch's sweep (D6, matched and filed). Why: the cold window for a hook is the same on both platforms (a hook needs a walker tap on a route page, or Settings' Delete, after the page has itself waited for the store; the route page's alerts aren't restored after process death, `_alert` is a plain `MutableStateFlow` in `PilgrimageRouteViewModel.kt:258@ca6424db`), so waiting would be an Android-only fix of D6. And a waiting hook is what creates the plan's ordering problem: a remove parked on the latch, then a save started, then the remove's `cancel()` stopping the newer save.
- *The reconcile doesn't wait on the latch*: it rides on its own `refreshRegions` (iOS). *The save doesn't wait*: its row is drawn only after the route page waited, and a save started past the bound reloads everything, iOS's cold behaviour.

**8. One FIFO order for hooks, reconcile and save start: satisfied by construction.** With no waiting hook, each hook, the reconcile's bump-and-ask, and a save's start are atomic steps on the main thread, run in the order they're posted (the package manager's posts are serialized by its actor; the launch work's by its coroutine; a tap is a main-thread event). A save that starts before a hook runs is cancelled by it (`remove`), which is iOS's own outcome when the save's tap precedes the package step on the main actor; a save that starts after isn't touched. The only waits are the reconcile's sweep (by design, cancelled by a later save's bump) and a surface's read (which changes nothing).

**9. Calibration must not suspend the loop before `Idle`.** iOS's `defaults.set` is synchronous, so `calibrate` and `phase = .idle` happen in the loop's last run. If Android awaited a DataStore `edit` there, the phase flow would expose `Saving(n + 2, n + 2)` for the write's duration, including on an all-current re-tap that otherwise draws nothing. Compute the value on the manager's thread from the cache, hand the write to the calibration store without awaiting it (the store persists in its own scope and serves reads from memory so a read after the write sees it), then `phase = Idle`. (C1 owns the arithmetic; U44 owns this store's interface, which the plan already makes injectable.)

**10. `isSaving`** is `phase.value is Phase.Saving`, readable from any thread.

### C2.13 Flow-analysis items in this cluster

| Item | What iOS does (quoted above) | What Android must do |
|---|---|---|
| 2. Threading | `@MainActor` manager; the loader hops callbacks to main; the hooks run synchronously inside the package manager's main-actor steps (§C2.2, §C2.9) | the main thread for the engine; hooks posted from IO and returning at once; `remove` returns before Mapbox removes anything (on both platforms the store's removal is asynchronous) (§C2.12 points 1–2) |
| 3. The launch sweep can wipe every saved map | `installed()` swallows read and decode failures (`try?`, `PilgrimagePackageManager.swift:92-96@7c200bf`) and returns nil, and `sweep(nil)` removes every `pilgrimage:` region (:372-383). D3, confirmed. | match: `readInstalled` maps `IOException`/`PilgrimageException` to null (`PilgrimagePackageManager.kt:485-497@ca6424db`), so a `route.json` that no longer decodes sweeps everything, as on iOS (filed). Android-only: `runAtLaunch()` returns `Installed?` and a **thrown** read skips the reconcile (§C2.10). |
| 4. Mapbox out of `:tracker` | n/a (one process) | the tiles manager reached only through a `Provider` in the UI launch work and UI surfaces; the package manager is UI-only already; a test that the tracker's finalize and the flag-off launch never resolve it (§C2.10 point 6) |
| 5. "Mid-walk" for a save | `activeWalkViewModel != nil` (walk screen from open, pre-Start, to a saved finish or a cancel), synchronous, at the door and every step (§C2.11) | `walkScreenUp()` where iOS checks; `walkActive()` at the door and before each actual load; the claim before the door's read; the generation re-checked after every guard read (§C2.11, §C2.12 point 3). The package guard's other clauses don't apply. |
| 7. A partial load that reports success | the region load's `.success` resumes the loop (`result.map { _ in () }`, :300), `done += 1`, nothing checks the counts; the loader caches the summary with its real counts, so `isComplete` is false, status reads `.partial`, the row reads "s of m saved" with no error line, and `calibrate` sums the partial region's bytes. A re-tap reloads that stage (it isn't `isSaved`). | match exactly (11.23.1 behaves as iOS's 11.20/11.23; C3 confirms the SDK side). Filed with the themed issue. A test can model it with the fake's optional partial completion (§C2.8). |
| 8. Cold cache after a UI restart | the cache reads as empty until the first current answer; surfaces correct on `regionsChanged`; "the day" reads once; hooks remove nothing; a cold save reloads all (D6, §C2.7) | the surfaces wait (bounded, 5 s) for the first answer; past it the morning card draws no line; hooks and the save don't wait (D6 matched for them) (§C2.12 point 7) |
| 11. Orphans the seam never reaches | a failed Update's rollback and a killed Replace's `installed()` leave regions that the next (or, for the Replace, the same) launch's sweep takes (§C2.9) | the same two, healing the same way; plus Android's own third path (a retire that throws before `tiles?.remove`), healing at the next launch; Settings reads "no maps saved" meanwhile on both |

Residuals from the doc review that touch this cluster:
- *"What counts as the first answer"*: settled in §C2.12 point 7 (a current answer, success or failure; not a stale one; not the packs).
- *"What the package hooks do past the bound"*: they don't wait at all (point 7).
- *"One FIFO order for hooks, reconcile and save start"*: satisfied by construction (point 8).
- *"The reconcile inside `packageLaunchWork` delays the expiry sweep if it suspends"*: it mustn't suspend for the store (§C2.10 point 3).
- *"`deferringFailure` catches `Exception`, not `Error`"*: an `Error` from `runAtLaunch()` or the reconcile's post would escape to the finalization scope's handler, which logs it (`WalkModule.kt:75-78@ca6424db`), and the expiry sweep after it wouldn't run that launch. Same as today for the package work; no change.
- *"The flag-on reconcile opens TileStore/OfflineManager in background-only main-process starts (WorkManager, widget)"*: true, since `PilgrimApp.onCreate` launches `runAtLaunch()` on every main-process start, and the reconcile asks the store. Cost, not correctness. iOS asks the store at every launch too (its loader's `init` and the reconcile). No change.
- *"A packs-only change fires no regions-changed; on a cold start the row may read 'n of n saved' until something reloads"*: confirmed. The packs answer and the regions answer come from one refresh but land separately, and `.packs` re-reads nothing. If the regions answer lands first, the route page reads `.partial(n, of: n)` until its next re-read (a `regionsChanged`, a phase change to idle, or a reload). Matched (part of D4's theme).

---

### C2.S Strings

The engine draws nothing and owns no string. Its `phase` carries a `PilgrimageError`, which the maps row renders through `PilgrimageCopy.line` (C4 owns where and how). The four the engine can produce:

```swift
        case .incomplete: return "the download didn't finish"
        case .diskFull: return HonorImportCopy.line(for: .failed(.diskFull)) ?? "not enough space on this phone"
        case .walkInProgress: return "finish your walk first"
// …
        case .mapTooLarge: return "more map than can be saved at once"
```
> Pilgrim/Models/Honor/PilgrimageWayImporter.swift:19-23@7c200bf

```swift
        case .failed(.diskFull): return "not enough space on this phone to save these voices"
```
> Pilgrim/Models/Honor/HonorImportReducer.swift:34@7c200bf

| Error the engine sets | When (this cluster) | Line (verbatim) | Android resource at `ca6424db` |
|---|---|---|---|
| `incomplete` | a load mapped from `failed`/`cancelled` with the generation current; Android also a thrown guard read | "the download didn't finish" | `R.string.pilgrimage_error_incomplete` (exists) |
| `diskFull` | a load mapped from `diskFull` | the share import's disk-full line, "not enough space on this phone to save these voices" (#122 item 5, matched; the `??` fallback is dead) | `R.string.honor_import_disk_full` (exists) |
| `walkInProgress` | the door, or any step's walk check | "finish your walk first" | `R.string.pilgrimage_error_walk_in_progress` (exists) |
| `mapTooLarge` | a region load mapped from `tileCountExceeded` | "more map than can be saved at once" | `R.string.pilgrimage_error_map_too_large` (exists; Stage 21-2 added it) |

A cancel (`incomplete` thrown with the generation moved) sets no line: `cancel()` already set `.idle`. No format arguments.

### C2.T Test inventory

All iOS tests in this cluster, at `7c200bf`. Fixtures first, then each test: what it asserts, its Android unit, and how it adapts.

**Fixtures** (`PilgrimageTilesManagerTests.swift:26-67@7c200bf`):
- `stage(index, count: 3, routeId: "camino-frances", lonOffset: 0)`: a 3 km straight stage east along latitude 42, 31 points `lon = lonOffset + i × 0.001209`, `t = i × 60`; its Way id is `stageWayId(routeId, index)`.
- `stages(n, routeId)`: `stage(i, count: n, routeId, lonOffset: i × 0.04)` for `i` in `0 until n`.
- `untilPending()` / `until(what, condition)`: up to 2,000 `Task.yield()`s, then `XCTFail("timed out waiting for …")`.
- A fresh `FakeTileRegionLoader`, a throwaway `UserDefaults` suite, `PilgrimageTilesManager(loader:defaults:)` per test.
- **Android shape:** each stage becomes U43's per-stage value from the same 31 points: `wayId = stageWayId(routeId, index)`, `index`, `rings = corridor(points, 500.0)`, `corridorHash = corridorHash(rings)`. A "redrawn" stage is the same index from `lonOffset + 0.01`. The "lineless" Way of the calibrate test is a value with `rings = []` and any hash. Keep the coordinates verbatim. `untilPending()` becomes `runCurrent()` on the test dispatcher followed by `assertTrue(loader.hasPendingWork)` (deterministic, so no yield loop); `until { regionRequests.isNotEmpty() }` the same. The `UserDefaults` suite becomes an in-memory calibration store. `manager.isWalkActive = { walking }` becomes the fake signals' `screenUp` flag (iOS's clause). `Task { try await manager.save(…) }` becomes the returned `Deferred` (`task.await()`, or `assertThrows` on `PilgrimageException` with the error). A test that calls `remove`/`removeRegions`/`reconcile` then asserts immediately must `runCurrent()` first, since those post.

`UnitTests/Honor/FakeTileRegionLoaderTests.swift` (4; U44 `FakeTileRegionLoaderTest.kt`):

| Test | Asserts |
|---|---|
| `testALoadIsRecordedAndCompletesIntoTheStore` | a region request is recorded; nothing stored until completed; completion delivers the summary; stored with the hash, complete |
| `testAFailureIsDeliveredOnceAndStoresNothing` | `nextRegionFailure = .diskFull` fails the next completion with it, stores nothing, and clears itself |
| `testRefreshRegionsAnswersOnlyWhenReleasedAndOnce` | `refreshRegions` doesn't answer until `releaseRegions()`, then once; a second release delivers nothing |
| `testSeedAndRemoveAndPacks` | an incomplete seed reads incomplete; `removeRegion` empties and records; a pack load completes into `hasStylePack` |

Fixture: one 5-point ring around (42, 0)–(42.01, 0.01). Adapt only the types.

`UnitTests/Honor/PilgrimageTilesManagerTests.swift` (24):

| # | Test | Asserts | Unit |
|---|---|---|---|
| 1 | `testAFreshManagerReportsNothingSaved` | status of 3 stages on an empty fake is `.none` | U44 |
| 2 | `testThePackCountIsTheZ11CellsOfTheWholeCorridor` | `packCount(3 stages) > 0` and equals `tileCount(all rings, 11...11)` | U44 (C1's maths) |
| 3 | `testAZ11CellTwoStagesShareIsCountedOnce` | per stage `[2, 1]`, together `2` | U44 (C1) |
| 4 | `testTheEstimateIsPacksTimesTheRoutesOwnBytesPerPackAndTheSeedByDefault` | seed by default; a stored 2,700,000 for the route; another route keeps the seed | U44 (C1) |
| 5 | `testTheCorridorHashCarriesTheRegionVersion` | `corridorHash(for:)` = `corridorHash(rings, version: current)` ≠ version − 1; a region seeded with the old version's hash reads unsaved | U43 (hash halves), U44 (`isStageSaved` half) |
| 6 | `testStatusCountsOnlyCompleteRegionsWhoseCorridorStillMatches` | current + stale + incomplete → `.partial(1, of: 3)`; `isStageSaved` true/false/false | U44 |
| 7 | `testStatusIsSavedOnlyWhenEveryStageAndBothPacksArePresent` | all regions, no packs → `.partial(2, of: 2)` (D4); packs → `.saved(100_000)` | U44 |
| 8 | `testTheFootprintCountsStaleBytesOfTheRouteFromOneRead` | `Footprint(1, 7_000_000)` (stale counted, foreign excluded); `regionsReadCount == 1` | U44 |
| 9 | `testASaveLoadsPacksThenRegionsInOrderAndSkipsWhatIsThere` | light present → only dark requested; stage 0, then stage 2 (stage 1 current); idle; `.saved(300_000)` | U44 |
| 10 | `testProgressCountsPacksAndStages` | `saving(0, 4)` → after two packs `saving(2, 4)` → after a region `saving(3, 4)` | U44 |
| 11 | `testCancelKeepsWhatIsDoneAndResumeStartsAtTheGap` | after 2 of 4 and a cancel: idle, `.partial(2, of: 4)`, the in-flight handle cancelled; a resumed save's first request is stage 2; the stale completion is ignored (three completions finish two stages) | U44 |
| 12 | `testACancelLandingBetweenALoadsCompletionAndTheNextHopEndsTheSave` | complete a region, cancel before the loop resumes: throws `.incomplete`, idle, no further request | U44. On Android "before the loop resumes" is real: the fake's completion resumes the continuation, which a `StandardTestDispatcher` only schedules; then the test calls `cancel()`, which runs inline (main-only, not posted), then `runCurrent()`. If `cancel()` were posted it would queue behind the resumed loop and the test would fail. |
| 13 | `testARedrawnStageIsReloadedAndAnUnchangedOneIsNot` | only the redrawn stage 1 loads, with its new hash and `acceptExpired == true` | U44 |
| 14 | `testAWalkStartingMidSaveStopsItAndKeepsWhatIsDone` | 7 stages; the walk starts while the 6th loads: throws `.walkInProgress`, 6 requests, `.partial(6, of: 7)` | U44 (iOS's clause; add an Android twin with `active` flipping, which with option (b) stops before the 7th load too) |
| 15 | `testRefusedWhileWalkingBeforeAnythingIsRequested` | refused at the door: `.walkInProgress`, no pack or region request, `.failed(.walkInProgress)` | U44 (both clauses on Android) |
| 16 | `testASecondSaveWhileSavingMakesNoCalls` | a second save during the first returns normally, one pack request | U44 |
| 17 | `testAFailedLoadLandsInFailedKeepsEarlierRegionsAndClears` | a `.failed` load: throws `.incomplete`, `.failed(.incomplete)`, `.partial(1, of: 3)`; `cancel()` → idle | U44 |
| 18 | `testAFailedPhaseClearsOnTheNextSave` | after `.failed(.incomplete)`, a new save runs: `saving(2, 3)` (packs present, the region loading again) | U44 |
| 19 | `testDiskFullSurfacesAsDiskFull` | `.diskFull` load → throws `.diskFull` | U44 |
| 20 | `testAPackCeilingRefusalSurfacesAsMapTooLarge` | `.tileCountExceeded` → throws `.mapTooLarge`, `.failed(.mapTooLarge)` | U44 |
| 21 | `testACompletedSaveCalibratesThisRouteOnly` | 2 regions × 400,000 → stored `800_000 / packCount`; the other route's key stays 0 | U44 (C1) |
| 22 | `testCalibrateWritesNothingWithoutBytesAndPacks` | no bytes → nothing written; bytes but a lineless stage (0 packs) → nothing written | U44 (C1) |
| 23 | `testASaveReadsTheStoreOnceBeforeTheLoopAndOnceToCalibrate` | `regionsReadCount == 2` after a 3-stage save | U44 (the cold-cache latch must not run inside a save) |
| 24 | `testTheManagerPublishesWhenTheLoaderAnnouncesAChange` | `objectWillChange` fires when the fake stores a region | U44, adapted: `regionsChanged` emits on the loader's `.regions` (and, an added check, not on `.packs`) |

`UnitTests/Honor/PilgrimageTilesManagerTests+Lifecycle.swift` (10; U44 `PilgrimageTilesManagerLifecycleTest.kt`):

| Test | Asserts |
|---|---|
| `testRemoveClearsExactlyThePrefixAndLeavesPacks` | the route's 3 regions removed; another route's stays; both packs stay (D5) |
| `testRemoveCancelsAnInFlightSave` | `remove` during a save: the save ends, idle |
| `testRetiredIndicesAreRemovedAndNothingIsDownloaded` | `removeRegions(atOrAbove: 3)` of 5 removes 3 and 4; no requests |
| `testReconcileRemovesForeignAndOutOfRangeRegionsAndIsIdempotent` | sweeps `:7` and the foreign route; a second run removes nothing |
| `testReconcileSweepsOnceTheStoreAnswers` | nothing before the answer; exactly once after; not again on a second answer |
| `testAPacksOnlyChangeDoesNotRunThePendingSweep` | a `.packs` signal doesn't sweep; the regions answer does |
| `testReconcileWithNothingInstalledRemovesEveryRegion` | `nil` removes every region |
| `testAReconcileFromAnEmptyLaunchNeverSweepsALaterSave` | reconcile(nil) answered on an empty store, then a full save: nothing removed, `.saved(200_000)` |
| `testASaveCancelsAPendingLaunchSweep` | reconcile(nil) pending; a save completes; the late answer removes nothing |
| `testASaveRefusedWhileWalkingLeavesThePendingLaunchSweep` | a door refusal leaves the sweep; the answer removes the foreign region |

All adapt only in shape (post-and-drain for the hooks and the reconcile).

`UnitTests/Honor/PilgrimagePackageManagerTests.swift`, PR #86's one test (U45, `PilgrimagePackageManagerTest.kt`; Stage 21-2 deferred it at `PilgrimagePackageManagerTest.kt:60-66@ca6424db`):

| Test | Asserts |
|---|---|
| `testRemoveReplaceAndUpdateReachTheTilesManager` | a real tiles manager on a fake loader: after a 2-stage download with both regions seeded, an Update to one stage removes exactly `pilgrimage:camino-frances:1` and requests no maps; re-seeding `:0`, a Replace with `camino-norte` removes `:0` and installs norte; Remove of `camino-norte` removes no further `camino-frances` region |

Fixtures: `stubOneStagePackage(release: "v1.8.0")`, `entryWithOneStage`, `stubTwoStagePackage(routeId: "camino-norte")`, `entry(routeId:)`. All four exist in Android's harness already (`PilgrimagePackageHarness.kt:79,105,142-158@ca6424db`). Adapt: `h.makeManager(tiles)` with a real `PilgrimageTilesManager` (fake loader, in-memory calibration store, the main dispatcher). The harness runs operations on real IO threads and blocks the Robolectric main thread in `awaitBlocking()`, so the hooks' posts wait on the paused main looper: after each `awaitBlocking()`, idle the main looper (`shadowOf(Looper.getMainLooper()).idle()`) before asserting `removedIds`. Seed the fake on the main thread too.

**Android tests with no iOS counterpart** (U44 unless noted; each pins a decision in this file):
- The door's suspending read: a cancel while `walkActive()` is suspended (a gated fake) starts no load and ends the save with `INCOMPLETE`, phase untouched; a second `save` during that read makes no calls; a door read that throws ends as `Failed(INCOMPLETE)`.
- A per-load read: a cancel while it's suspended starts no load and the save ends (not hangs).
- Option (b): an all-current re-tap never publishes `Saving` to a conflating collector (collect the phase with a `StandardTestDispatcher` collector and assert it saw only the start value), and runs no `walkActive()` read but the door's.
- The cold-cache wait: a reader waiting before the first answer is released by `releaseRegions()` and then reads the stored state; a failed first refresh (`failRegions()`) releases it; past the bound (virtual time) it returns false; after the first answer it returns true at once with no new store read.
- Hooks from another thread: `remove` called on a background thread while a save runs returns at once, and after draining the manager's thread the save is cancelled and the route's regions removed.
- `regionsChanged` doesn't replay to a late collector.
- U45: the launch work reconciles with the `Installed?` `runAtLaunch()` returned (including `null` → sweep all); a thrown `runAtLaunch()` skips the reconcile and the expiry sweep still runs; the tiles provider is never resolved by the flag-off launch or by the tracker's `finalize`/`finalizePending`.

---

### C2.C Corrections to the Android plan

1. **U44 Approach, the save loop: "bump the generation, then the phase `saving(0, 2 + n)`".** iOS bumps `sweepGeneration` there, not `generation`, and captures the current `generation` without changing it (:216-218). `generation` moves only on `cancel()`. Fix: "bump the **sweep** generation (cancelling a pending launch sweep), set `saving(0, 2 + n)`, capture the current load generation".
2. **Key Technical Decisions, the walk guard: "at the door and again before each load, as iOS checks before each load"**; U44: "each load preceded by the guard". iOS checks at the door and at **every step**, before the skip test, so a skipped pack or stage is checked too (:222, :239). Fix: "iOS's clause (`walkScreenUp()`) synchronously at the door and at every step; Android's `walkActive()` at the door and before each actual load, each followed by a generation re-check" (§C2.11 option b, which also avoids a progress flash iOS never shows on an all-current re-tap).
3. **Key Technical Decisions, the cold cache: "Readers and the package hooks therefore wait, with a bound".** The hooks shouldn't wait. iOS's hooks read the cache at once (D6), the cold window for a hook is the same on both platforms, and a waiting hook is what creates the plan's own ordering problem (a parked remove cancelling a later save). Fix: "the surfaces wait; `remove`, `removeRegions`, the reconcile and the save don't" (§C2.12 point 7). The Deferred-to-Implementation items "what the package hooks do past the bound" and "one order for hooks, reconcile and a save's start" then dissolve (point 8).
4. **Deferred to Implementation, "a failed or stale refresh should end the wait too".** A failed one should, and does through `refreshRegions`. A stale one shouldn't: it writes nothing, so a reader it released would read the cold cache; the refresh that overtook it ends the wait (§C2.12 point 7).
5. **Key Technical Decisions, the launch reconcile: "`runAtLaunch()` returns the installed route's id and stage count".** Return `Installed?`. iOS #91's step (`@e551b11`) takes the whole value from the same read (`restampStageHours(of: installed)`), and the reconcile maps it to `(routeId, stageCount)` itself (§C2.10).
6. **U45 Approach: the launch order.** Add: resolve the tiles manager before `runAtLaunch()` (iOS wires `packages.tiles`, and so builds the loader and starts its first refresh, before `installed()`), and `reconcile` must post and return, not wait for the store, or the expiry sweep after it waits on the tile store (§C2.10 points 2–3).
7. **Key Technical Decisions, confinement: "an exception handler that logs and ends the save as failed".** A handler can't end the save: `save` returns a `Deferred` (the ported tests await it), and a `Deferred`'s failure never reaches a `CoroutineExceptionHandler`. Fix: the loop's own catch ends the save as failed for any non-cancellation `Throwable`; the handler logs what escapes `launch`ed work (the hook and reconcile posts); the loader's SDK hops run inside the scope so they're covered too (§C2.12 points 5–6).
8. **Key Technical Decisions, "the generation is re-checked after every guard read".** Right, and add where the door's generation is captured: **before** the door's read (at the claim), so a cancel during that read is seen. And the claim is released when the read returns, refusal or not (the plan says "a refusal releases it"); past the door the `Saving` phase takes over as the slot (§C2.12 point 3).
9. **U44 Hooks: "`reconcile(installed: (routeId, stageCount)?)` bumps the generation and sweeps on the store's next answer".** It bumps the **sweep** generation, and sweeps on the answer to its **own** `refreshRegions` call, only if no later reconcile and no save past the door has bumped the sweep generation since (:358-368).
10. **U44 Approach, cancel.** Add two iOS facts the tests pin: `cancel()` also clears a `failed` phase to `idle` with nothing in flight, and it never touches the sweep generation (so `remove` doesn't cancel a pending sweep).
11. **Key Technical Decisions, matched as shipped: "the orphan paths a failed Update rollback or a killed Replace leaves until the next launch".** A killed Replace doesn't wait for a later launch: at the pin the launch's own `installed()` read is the one the reconcile carries, so the abandoned route's regions are swept in **that same launch**, once the store answers (unless a save passes its door first). Only the failed Update's rollback (and Android's throwing retire, §C2.9 path 3) wait for the next launch. Drop the killed Replace from the list to file.
12. **U44 Files, `TileRegionLoading.kt`.** Add: byte sizes are `Long` (Swift `Int` is 64-bit; a full store can pass 2 GiB); the error is an enum delivered in a result type, not thrown; and the contract's five promises (§C2.1).
13. **U44 Approach, inputs.** The per-stage value should also carry the stage Way's id (or the manager must derive it with `stageWayId`), since every lookup and request is keyed by it (§C2.3).
14. **U44 Test scenarios, "Integration: remove called from another thread … returns without waiting".** Add the companion fact the ported package-manager test needs: under Robolectric the hooks' posts wait on the paused main looper, so idle it before asserting (§C2.T).
15. **U44 Approach, calibrate.** Add: the calibration write mustn't suspend the loop before `Idle` (§C2.12 point 9).
16. **Risks table, "A cancel during the guard's suspending walk read hangs the save, or a double tap starts two."** Also a cancel during a **per-load** read (not only the door's) would hang the save without the re-check; the mitigation already covers it, so name both reads.
17. **System-Wide Impact: "A failed removal heals at the next launch."** True for the store's removal; add the Android-only retire that throws before `tiles?.remove` (§C2.9 path 3), which heals the same way.

### C2.N Notes by unit

**U43** (the per-stage value): carry `wayId`, `index`, `rings`, `corridorHash` (version 2). The engine reads nothing else of a stage.

**U44** (the engine):
- Files: `TileRegionLoading.kt` (§C2.1), `PilgrimageTilesManager.kt` (implements `PilgrimageTiles`, no seam change), `T/…/FakeTileRegionLoader.kt` (§C2.8, plus `failRegions()` and an optional partial completion).
- Constructor: `loader`, a calibration store interface, `signals: PilgrimageWalkSignals` (only `walkScreenUp()` and `walkActive()` are read), `mainDispatcher`, `scope`. Set `loader.onChange` in the constructor (§C2.2). No Mapbox class touched until the first store call.
- State on the main thread: `phase: StateFlow<Phase>` (`Idle`, `Saving(done, total)`, `Failed(PilgrimageError)`), `regionsChanged: SharedFlow<Unit>` (no replay, buffer 1, drop oldest; emitted on `.regions` only), `generation`, `sweepGeneration`, `pending`, `inFlight`, the door claim, the first-answer latch.
- Readers, synchronous on the main thread: `isStageSaved(stage)`, `status(routeId, stages)`, `footprint(routeId, stages)` (§C2.4), plus C1's `packCount`/`estimateBytes`. One `regions()` read per call; status and footprint from one snapshot.
- `suspend fun awaitStore(): Boolean`: one `refreshRegions` latch per process, bound 5 s from the call (§C2.12 point 7). Plus a non-suspending `warm()` (posted) that requests the latch's refresh without waiting, for the launch work (U45).
- `fun save(routeId, stages): Deferred<Unit>`: the loop of §C2.5 with Android's door (§C2.12 point 3) and option (b) guard placement (§C2.11). Order: one-at-a-time return; `walkScreenUp()` refusal; claim, capture generation, `walkActive()`, release; generation check; `walkActive()` refusal; `sweepGeneration += 1`; `Saving(0, 2 + n)`; light then dark (`walkScreenUp()` → skip if present, else `walkActive()` → generation → load → …); generation check, `done += 1`, publish; one `regions()` snapshot; stages by ascending index (same step shape; request `{wayId, rings, corridorHash, acceptExpired = true}`, no-op progress); calibrate without suspending; `Idle`. Catch: rethrow cancellation; otherwise map, and if the generation is current cancel and clear `inFlight` and publish `Failed`; always rethrow.
- `fun remove(routeId)`, `fun removeRegions(routeId, atOrAbove)` (the seam, called from IO) and `fun reconcile(installed: InstalledRoute?)` (called from the launch work on IO): each posts its iOS body to the main thread and returns. `fun cancel()` is different: its callers are all on the main thread (the row's cancel button, and `remove`'s own first step), so it runs inline and synchronously, as iOS's does; mark it main-only. That's what lets test 12 land a cancel between a load's completion and the loop's resumption.
- Loads: `suspendCancellableCoroutine`; `pending = cont`; `inFlight = loader.load…`; the completion returns unless `generation == myGeneration && pending === cont`; clear both; resume mapped. `invokeOnCancellation` cancels the handle.
- Error map: `DISK_FULL`, `MAP_TOO_LARGE`, `INCOMPLETE` (failed, cancelled, a moved generation, a thrown guard read), `WALK_IN_PROGRESS`.
- Tests: §C2.T (4 + 24 + 10 ported, with the adaptations listed, plus the Android-only ones).

**U45** (wiring and launch):
- `PilgrimagePackageManager`'s `@Inject` constructor passes the tiles manager (UI process only; the manager is never built in `:tracker`). The three hooks are already in place and in iOS's order (§C2.9).
- `runAtLaunch(): Installed?` (§C2.10 point 1).
- `HonorFinalizer`'s `@Inject` constructor gains a `Provider` for the tiles manager; `packageLaunchWork` = flag on: resolve tiles and start its first store read (posted, not awaited), `runAtLaunch()`, `reconcile(…)`, in one `deferringFailure` block (§C2.10 point 2). Leave room after the reconcile for the #91 restamp, `if (installed != null)`.
- The production loader must hop every SDK callback through the manager's scope on the main dispatcher (or catch inside a bare post), so the scope's handler covers it (C3).
- Tests: the ported `testRemoveReplaceAndUpdateReachTheTilesManager` (idle the main looper after each operation); the launch-work tests (reconcile with `runAtLaunch()`'s value, `null` included; skipped on a throw; expiry sweep still runs); the provider never resolved by the flag-off launch or the tracker's path.

**U46** (route page; C4 owns the row):
- Re-read status on `regionsChanged` and when `phase` becomes `Idle` (iOS's two triggers, `PilgrimageRouteView.swift:149-154@7c200bf`); collect the phase `StateFlow` so a reopened page shows a live save.
- Call `awaitStore()` before the first status read; past the bound read the cache anyway (iOS's cold answer) and let `regionsChanged` correct it.
- Hold the maps row while the page's own `held` is set, not only while the package downloads (§C2.9, the commit-to-hook window). The row stays live during a save, so its cancel works.
- `isBusy` adds `phase is Saving` (and optionally the door claim, §C2.12 point 3).
- The tap: `tiles.save(routeId, stages)` and ignore the `Deferred`; the phase carries the outcome.

**U47** (overview, "the day", Settings):
- The overview and "the day": `awaitStore()` first; `false` → `mapsLine = null`; `true` → `isStageSaved(stage)`. The overview re-reads on `regionsChanged`; "the day" reads once (iOS), including when restored open.
- Settings' Delete calls `tiles.remove(routeId)` (posted; it cancels any save first, as iOS). Reload on `regionsChanged`, which the removal fires, rather than right after the call, since the call returns before it runs.
- The footprint counts stale and out-of-range prefixed regions (§C2.4), which is what Delete removes.
- The Maps screen and the Data card row read the route through `packages.installed()`, as iOS's `OfflineMapsModel.loadInstalled` does (`OfflineMapsView.swift:40@7c200bf`) and as Android's Ways footer already does (`WaysListViewModel.kt:238@ca6424db`), not `installedRoute()` (Stage 21-2's no-write read is for the prompts screen only). Either way no tiles hook runs from it.
- The engine puts no walk check on `remove` (iOS's has none, :334-340), so a Delete mid-walk is allowed engine-side and can't reach `:tracker`; whether the row follows the Ways row's walk gating is C4's call (plan Open Question).
- Don't restore the "Delete maps?" confirmation after process death (keep it in plain VM state, as the route page's alerts are), so a restored dialog can't send a cold-cache Delete that removes nothing.

**U48** (device checklist), from this cluster:
- The first-answer time after a UI kill (logcat timing around `awaitStore()`), against the 5 s bound.
- "The day" restored open after a UI kill mid-walk, with saved maps, reads "maps saved for today".
- A re-tap of "maps saved · N MB" with everything current shows no progress flash and no busy flash.
- A walk started mid-save stops the save at its next step; the regions already done stay.
- A kill mid-Replace, then the relaunch: the old route's regions go in that same launch (Settings' footprint, the debug tiles report).
- An Update whose commit fails (a nearly full disk, if reproducible): the route leaves, its regions stay until the next launch, Settings reads "no maps saved" meanwhile.

### C2.A Android additions to record at the gate

| Addition | Reason |
|---|---|
| The engine on the main thread, its public mutators posted there; the package hooks return before they run | iOS's `@MainActor` and synchronous hooks; the package manager calls the seam on IO under its actor, and blocking on main would deadlock |
| The door's claim before its suspending read; the load generation captured at the claim and re-checked after every guard read | Android's `walkActive()` is a Room read; without it a cancel during a read hangs the save and a double tap starts two loops |
| `walkActive()` (a walk row open) at the door and before each actual load; iOS's clause `walkScreenUp()` where iOS checks | Android's walk outlives the UI process in `:tracker`; skipping the read on skip steps keeps iOS's "an all-current re-tap shows nothing" |
| A thrown guard read ends the save as `INCOMPLETE`; the loop's catch takes any non-cancellation `Throwable` | iOS's guard can't fail and its loop throws only `PilgrimageError`; the phase must carry every failure |
| Surfaces wait up to 5 s for the store's first answer after a process start; past it the morning card draws no maps line | Android restores "the day", the overview, the route page and Settings in a fresh process before the store answers; iOS's walk screen can't exist in a fresh process |
| `runAtLaunch()` returns `Installed?`; a thrown launch read skips the reconcile (and later the #91 restamp) for that launch | iOS's `installed()` can't throw; Android's can (a retire's Room read, an unresolvable store root) |
| The reconcile may be posted after a save passed its door, and then isn't cancelled by it | Android's launch work runs after the pending Honor steps; safe because its `installed` read is serialized with the package actor |
| A retire that throws before `tiles?.remove` (Replace, Remove) leaves the route's regions until the next launch | Android's retire reads Room and can throw; iOS's can't |
| The maps row also held while the route page itself is held (U46) | Android's package phase goes `Idle` at the commit while the post-commit steps (and the hooks) still run; iOS has no such gap |
| The calibration write isn't awaited before `Idle` | iOS's `UserDefaults.set` is synchronous; awaiting DataStore would expose a last `Saving` frame |
| The load callback also checks `pending === cont` | hardening against a doubled SDK callback; no behaviour change |

### C2.D iOS defects in this cluster (matched as shipped; file in the themed issue)

| # | Defect | Evidence | What a walker sees | Severity | Status |
|---|---|---|---|---|---|
| D3 | A launch with nothing readable installed sweeps every saved map | `installed()`'s `try?` reads (`PilgrimagePackageManager.swift:92-96@7c200bf`) → nil; `sweep(nil)` removes every `pilgrimage:` region (`PilgrimageTilesManager.swift:372-383@7c200bf`) | after an app update whose importer rejects the installed `route.json`, up to ~240 MB of saved maps silently go; the route also reads as not installed | low–medium (rare, silent, large) | **confirmed**; Android matches for read/decode failures and skips the sweep only on a thrown read |
| D4 | "n of n saved" when every region is saved but a style pack is missing | `status` (:131-133); pinned by `testStatusIsSavedOnlyWhenEveryStageAndBothPacksArePresent` | the button reads "Save maps for the way · 33 of 33 saved"; also briefly on a cold start when the regions answer lands before the packs' | low (copy) | **confirmed** |
| D5 | Style packs are never removed | `remove` (:332-340) and the sweep touch regions only; no code path removes a pack | after "Delete maps" Settings reads "none saved" while both styles' packs stay on disk | low | **confirmed** |
| D6 | The engine reads a cold cache before the store's first answer | `regions()` returns the cache (`MapboxTileRegionLoader.swift:157-160@7c200bf`); `remove`, `removeRegions`, the save's snapshot and the readers all read it | in the first moment after launch: a Remove leaves the route's maps until the next launch; a save re-runs every load; a surface read then shows nothing saved until the store's answer corrects it ("the day", read once, would keep it, but on iOS its walk screen can't exist that early) | low (sub-second on iOS) | **confirmed**; Android's surfaces wait (an addition), its hooks and save match |
| 7 (flow) | A region load that succeeds partially counts as done | the loop resumes on `.success` without checking counts (:300, :250-252); status then reads it unsaved | "Save maps for the way · 30 of 33 saved" right after a save that showed no error; calibration counts the partial bytes | low | **confirmed** (engine side; C3 confirms 11.23.1) |
| 11a (flow) | A failed Update commit's rollback leaves the route's regions with no route | `rollBack` (:432-437) runs; `update` rethrows before `tiles?.removeRegions`; nothing calls `tiles?.remove` | the route leaves the phone (pilgrim-ios #119 item 2) and its saved maps stay, invisible to Settings ("no maps saved"), until the next launch's sweep | low | **new**; add to the themed issue, or as a maps note on #119 item 2 |
| 11b (flow) | A killed Replace's abandoned route keeps its regions | `installed()`'s marker branch (:101-106) has no tiles call | none beyond a moment: the same launch's sweep takes them (§C2.9 path 2) | — | **refuted** as a defect; don't file |
| N1 | `shared`'s doc comment says the loader "is attached in `MainCoordinatorView`" | :39-41; `shared` builds its own loader | nothing | trivial (comment) | note in the themed issue only if convenient |

Not this cluster's to verdict: D1 (the offline note, C4), D2 (summed bytes; the sums are at :134, :152, :184, the measurement is U48's), D7 (copy, C4; the engine side is confirmed: a re-tap skips every current stage and nothing refreshes expired tiles, `acceptExpired: true` at :243), D8 and D9 (C4/C1).

### C2.O Proposed owner decisions

1. **The package hooks and the cold cache.** The plan has the hooks wait (bounded) for the store's first answer. **Recommendation: they don't wait; match iOS and file D6.** The cold window for a hook is the same on both platforms (it needs a walker tap on a screen that has itself waited), so waiting would be an Android-only fix, and it's what creates the ordering problem the plan then has to solve (a parked remove cancelling a later save). The surfaces' wait stays, as a platform equivalent.
2. **Where the save re-checks the walk.** The plan says "before each load"; iOS checks at every step, skips included. **Recommendation: option (b)**: iOS's clause at every step, Android's `walkActive()` at the door and before each actual load. It keeps iOS's coverage for iOS's clause and iOS's "nothing visible" on an all-current re-tap; the only difference is a revived `:tracker` walk during a run of skips, which is Android's own clause. The alternative (both clauses at every step) costs about 35 Room reads per re-tap and a visible progress flash iOS doesn't have.

---

## C3. The Mapbox platform: the loader, the store, descriptors and style packs, threading, errors, the map, backup and transfer

| Field | Value |
|---|---|
| iOS pin | `7c200bf` (v2.0.0); PR #86 = `905996e`; `d331b61` (debug toggle removed) |
| Android | `ca6424db` (main) |
| Feeds | U45 (mainly); U44 (the seam's cache contract and the first-answer wait, D6); U48 (device rows) |
| Lenses | behavior, UI/visual, data, edge cases |
| SDKs | iOS builds mapbox-maps-ios **11.20.0** / MapboxCommon **24.20.0** (§1, #93); Android `com.mapbox.maps:android-ndk27:11.23.1` with `com.mapbox.common:common-ndk27:24.23.1`, checked with `javap` |

**iOS read in full at `7c200bf`:** `Pilgrim/Models/Honor/MapboxTileRegionLoader.swift` (286 lines), `UnitTests/Honor/MapboxTileRegionLoaderTests.swift` (all 7 tests), `Pilgrim/AppDelegate.swift`, `Pilgrim/Models/Honor/TileRegionLoading.swift`, `Pilgrim/Models/Walk/MapManagement/PilgrimMapStyle.swift`. Read in part: `PilgrimageTilesManager.swift` (the loader's callers: `:41`, `:57-66`, `:192-328`, `:334-383`), `WayMediaDownloader.swift:242-248` (`isDiskFull`), `PilgrimMapView.swift` (style and init options), both `Package.resolved` files, `project.pbxproj` (the SwiftPM requirement), the slice-three design spec and plan (intent only), `d331b61`, and PR #86's body. SDK sources: mapbox-maps-ios 11.20.0 from the DerivedData checkouts (every Pilgrim DerivedData folder on this Mac resolved `v11.20.0`, `5d1fb74`): `Offline/TileStore+MapboxMaps.swift`, `OfflineManager+MapboxMaps.swift`, `TilesetDescriptorOptions+MapboxMaps.swift`, `StylePackLoadOptions+MapboxMaps.swift`, `TileRegionLoadOptions+MapboxMaps.swift`, `OfflineErrors.swift`, `OfflineCallbacks.swift`, `Foundation/MapboxMapsOptions.swift`; MapboxCoreMaps 11.20.0 and MapboxCommon 24.20.0 headers from the SwiftPM artifact cache.

**Android compared at `ca6424db`:** `P/PilgrimApp.kt`, `P/ui/walk/PilgrimMap.kt` (style URI, `MapView` init options, `loadStyle`, telemetry), `app/src/main/AndroidManifest.xml` (backup attributes, the startup provider, `:tracker`), `R/xml/data_extraction_rules.xml`, `R/xml/backup_rules.xml`, `T/data/honor/WaysBackupRulesTest.kt`, `T/ui/walk/PilgrimMapCameraBuildersTest.kt`, `P/data/honor/WayMediaDownloadWorker.kt` (`isDiskFull`), `P/data/honor/pilgrimage/PilgrimagePackageManager.kt` (the seam), `gradle/libs.versions.toml`, `app/build.gradle.kts` (Mapbox artifact, `abiFilters`), `app/src/debug/kotlin/.../debug/honor/HonorDebugReceiver.kt` and `app/src/debug/AndroidManifest.xml`. The three Mapbox AARs were unpacked locally from the Gradle cache (`common`, `core`, `maps`) and read with `javap -c -v -p`; the native libraries with `strings`. `R` = `app/src/main/res`.

---

### 1. Evidence base: which SDK each platform runs

iOS ships 11.20.0, not 11.23.1. The workspace lockfile, which CI and release build, pins 11.20.0 / common 24.20.0; the project-embedded lockfile pins 11.23.1 / 24.23.1. This is pilgrim-ios #93, already filed; don't re-report it.

```json
"identity" : "mapbox-maps-ios",
"version" : "11.20.0"
```
> `Pilgrim.xcworkspace/xcshareddata/swiftpm/Package.resolved:23-29@7c200bf` (unchecked JSON). The project file's copy at `Pilgrim.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved:23-29@7c200bf` says `11.23.1`. The pbxproj requirement is `upToNextMajorVersion` from `11.0.0` (`Pilgrim.xcodeproj/project.pbxproj:4336-4343@7c200bf`).

iOS's own comment and PR #86's body agree that 11.20.0 is what runs ("the workspace's `Package.resolved` is the one that builds; the project-level one says 11.23.1 and is stale"):

```swift
        // 2026-09-14, Honor slice three: .readOnly is what a saved tile
        // region needs — the store is checked first and a covering pack is
        // used. The whole TileStoreUsageMode enum is marked deprecated in
        // the 11.20.0 CoreMaps headers with no replacement named; re-read
        // decision 7 of the slice-three spec before any bump past 11.x.
```
> Pilgrim/AppDelegate.swift:44-48@7c200bf

Android runs 11.23.1 / common 24.23.1:

```kotlin
mapbox = "11.23.1"
```
> gradle/libs.versions.toml:27@ca6424db

```kotlin
mapbox-maps-android = { group = "com.mapbox.maps", name = "android-ndk27", version.ref = "mapbox" }
```
> gradle/libs.versions.toml:98@ca6424db

What this means for the port:
- Every behavior the loader relies on is the same on 11.20 and 11.23: callbacks on worker threads, "invoked only once" completions, success for a partially loaded region (the `PARTIAL_LOAD` failure arrives only in 11.32), removal moving resources to the ambient cache, the deprecated `TileStoreUsageMode`. Where this spec cites an iOS SDK header it is the 11.20.0 / 24.20.0 header iOS actually builds; Android facts come from the 11.23.1 / 24.23.1 binaries.
- The Mapbox debug builds are arm64 only (`app/build.gradle.kts:103-105@ca6424db`, `abiFilters += "arm64-v8a"`); release adds `armeabi-v7a` and `x86_64` (`:121-123`). The offline API lives in the same `libmapbox-common.so` / `libmapbox-maps.so` on every ABI, so the ABI list changes nothing for this stage.

---

### 2. The tile store

#### 2.1 iOS: a dedicated folder under Application Support

```swift
    /// Under Application Support, never Caches: Caches is purgeable under
    /// storage pressure and a walker on day 20 could lose day 21's maps.
    /// `TileStore.shared(for:)` excludes its path from iCloud backup.
    static let storeURL: URL = {
        guard let support = try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                                          appropriateFor: nil, create: true) else {
            // Latched for the life of the process and otherwise invisible:
            // every save would appear to work and could vanish overnight.
            print("[MapboxTileRegionLoader] Application Support unavailable; saved maps will land in a purgeable directory")
            return FileManager.default.temporaryDirectory.appendingPathComponent("pilgrimage-tiles", isDirectory: true)
        }
        return support.appendingPathComponent("pilgrimage-tiles", isDirectory: true)
    }()
```
> Pilgrim/Models/Honor/MapboxTileRegionLoader.swift:17-29@7c200bf

- Path: `Library/Application Support/pilgrimage-tiles/`. If Application Support can't be resolved (it creates it with `create: true`), the store falls back to `tmp/pilgrimage-tiles/` and prints one console line. The `static let` latches the choice for the process.
- The loader opens it once, in `init`:

```swift
    init() {
        tileStore = TileStore.shared(for: Self.storeURL)
        offlineManager = OfflineManager()
        descriptors = Self.descriptorOptions().map(offlineManager.createTilesetDescriptor(for:))
        refresh()
    }
```
> Pilgrim/Models/Honor/MapboxTileRegionLoader.swift:68-73@7c200bf

- The iCloud exclusion is the SDK's, not Pilgrim's. `shared(for:)` wraps the deprecated native `createForPath:` and documents the exclusion; it traps on a non-file URL (which is why test 1 asserts `isFileURL`):

```text
    /// On iOS, this storage path is excluded from automatic cloud backup.
    ///
    /// - Parameter filePathURL: The path on disk where tiles and metadata will be stored
    /// - Returns: TileStore instance.
    public static func shared(for filePathURL: URL) -> TileStore {
        guard filePathURL.isFileURL else {
            fatalError("You must provide a file URL")
        }
        return TileStore.__create(forPath: filePathURL.path)
    }
```
> mapbox-maps-ios 11.20.0 `Sources/MapboxMaps/Offline/TileStore+MapboxMaps.swift:25-34` (SDK source, not Pilgrim; unchecked). MapboxCommon 24.20.0 `MBXTileStore.h:57` marks `createForPath:` `__attribute__((deprecated))`.

- The design spec planned `TileStore.default` with two "load-bearing" properties, Application Support and a backup exclusion (design §6, `docs/superpowers/specs/2026-09-14-honor-slice-three-offline-tiles-design.md:245-248@7c200bf`). The shipped code chose an explicit path instead. Intent: never purgeable, never backed up.

#### 2.2 iOS: the map is pointed at the same store, before any map exists

```swift
        MapboxMapsOptions.tileStoreUsageMode = .readOnly
        // Maps objects read these options at construction, so the store the
        // regions are saved into has to be named here, before any map exists;
        // otherwise the map reads the SDK's default store and every saved
        // region is invisible to it.
        MapboxMapsOptions.tileStore = TileStore.shared(for: MapboxTileRegionLoader.storeURL)
        mark("after Mapbox init")
```
> Pilgrim/AppDelegate.swift:49-55@7c200bf

- It runs first thing in `didFinishLaunchingWithOptions`, before `DataManager.setup` and before any SwiftUI view, so before the first `MapView`. It is unconditional: no flag, no XCTest guard (test 2 relies on that).
- `TileStore.shared(for:)` with the same path returns the same instance the loader later gets.
- Both options are needed on iOS only because the store is not the default one. The SDK's own docs say `.readOnly` is already the default and a `nil` store means the default location:

```text
    /// The tile store usage mode for the Maps API objects. Default is `readOnly`.
    public static var tileStoreUsageMode: TileStoreUsageMode {
// …
    /// If `nil` is set, but``tileStoreUsageMode`` is enabled, a tile store at the default location will be created and used.
    public static var tileStore: TileStore? {
```
> mapbox-maps-ios 11.20.0 `Sources/MapboxMaps/Foundation/MapboxMapsOptions.swift:43-53` (SDK source; unchecked)

#### 2.3 Android: the default store (owner decision, 2026-10-06)

The loader gets the store from `TileStore.create()` and holds it for the life of the process. Never `TileStore.create(path)`, never `setRootPath`.

From the 24.23.1 binary:
- `public static native TileStore create()`, not deprecated.
- `public static native TileStore create(String)`, carries the `Deprecated` attribute.
- `public static native Expected<String, None> setRootPath(String)`.
- No method on `TileStore` carries `@MainThread`. Every TileStore callback runs on a TileStore worker thread (§8).

Where the default store lives. Static evidence only; no device was attached this session (`adb devices` empty), so U48 confirms:
- `CoreInitializer$Companion.createSystemInformation()` passes `context.getFilesDir().getAbsolutePath()` as `SystemInformation.applicationDataPath` and `getCacheDir()` as `applicationCachePath` (javap, `common/classes.jar`).
- `libmapbox-common.so` holds the folder names `.mapbox`, `tile_store`, `metadata.db`, `data.db`, `tiles`, and the persisted-setting key `com.mapbox.common.tilestore.location` with "Stored TileStore path {} does not match {}".
- `libmapbox-maps.so` holds `map_data` and `map_data.db`: the map's disk (ambient) cache.
- The 24.20 header for `create()` (same text as Android's API doc): "If TileStore was not initialized by application before and no path was explicitly configured, creates a new directory inside application data path."
- Mapbox's docs and their navigation instrumentation tests use `filesDir/.mapbox/tile_store` for the store and `filesDir/.mapbox/map_data/map_data.db` for the map cache (Mapbox research §1, §5).

So both the store and the map cache sit under `filesDir/.mapbox/`, and one folder exclude covers both. In v11 style packs live in the tile store too:

> MapboxCoreMaps 11.20.0 `MBMStylePackLoadOptions.h:63`: "Prior to Maps v11 the style packages were stored in the Disk cache, and starting from v11 they are stored in the Tile Store." (SDK header; unchecked)

What survives what:
- `filesDir` is never purged by the system (unlike `cacheDir`), so iOS's "never Caches" intent holds.
- Uninstall or Clear data removes it, as an iOS uninstall removes Application Support.
- Mapbox remembers the store path in its persisted settings: `SettingsServiceHelper` opens `getSharedPreferences("mapbox_settings", …)` (javap, `common/classes.jar`), so `shared_prefs/mapbox_settings.xml`. Neither backup document carries shared prefs to a new phone (§10), so a transferred install never inherits a stale path.
- There is no fallback branch on Android. `filesDir` always resolves; iOS's `tmp/` fallback and its print have no Android counterpart. Record as a platform equivalent.

Why the default store and not iOS's folder (owner decision; restated so U45 doesn't reopen it):
- A dedicated folder needs `create(path)` (deprecated, and a second store instance beside the map's) or `setRootPath`, which must run before anything touches Mapbox in the process, can't sit behind the flag, and strands today's store and cache.
- The map already reads the default store (§3).
- 11.24 allows one store per process (§15). The default store is that one store.

#### 2.4 Android: what U48 must confirm on the device

- The path, before any transfer row: `adb shell run-as org.walktalkmeditate.pilgrim.debug ls -la files/.mapbox/` lists `tile_store/` (with `metadata.db`, `data.db` and a `tiles` tree) and `map_data/` (with `map_data.db`). Confirm nothing Mapbox writes lands elsewhere under `files/`, `databases/` or `no_backup/` (a `find files databases no_backup -newer` after a save works).
- Sizes for D2 (§14): `run-as … du -sk files/.mapbox files/.mapbox/tile_store files/.mapbox/map_data` before a save, after it, after Delete, and after the debug clear-cache command.
- The transfer row itself (§10.4).

---

### 3. The map side: which store the map reads, and its style URIs

#### 3.1 Android writes no map option, and needs none

Nothing in `app/src` names `MapboxMapsOptions`, `TileStore`, `OfflineManager` or `tileStoreUsageMode` today (`git grep` at `ca6424db`), and `PilgrimMap.kt` is the only map surface (`MapView(context, pilgrimMapInitOptions(...))` at `PilgrimMap.kt:940@ca6424db`). Its init options carry no store:

```kotlin
internal fun pilgrimMapInitOptions(context: Context, textureView: Boolean): MapInitOptions =
    MapInitOptions(context = context, textureView = textureView, styleUri = null)
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/PilgrimMap.kt:1554-1555@ca6424db

So every map reads the defaults: usage mode READ_ONLY and, with no store named, "a tile store at the default location", which is the instance `TileStore.create()` returns ("If the tile store instance already exists for the default location this method will return it without creating a new instance", 24.20 `MBXTileStore.h:59-66`, same text in the 11.23.1 Android API doc). Saving regions into `TileStore.create()` therefore makes them visible to the map with no option written. This is what iOS's two `AppDelegate` lines achieve for its custom folder (§2.2).

From the 11.23.1 binaries:
- `com.mapbox.maps.MapsResourceOptions` (core): `setTileStoreUsageMode`, `getTileStoreUsageMode`, `setTileStore`, `getTileStore` carry `java.lang.Deprecated` and `@MainThread`. `clearData(AsyncOperationResultCallback)` is `@MainThread`, not deprecated.
- `com.mapbox.maps.TileStoreUsageMode` (`DISABLED`, `READ_ONLY`, `READ_AND_UPDATE`): the whole enum is `Deprecated`, as iOS's 11.20 header marks it.
- `com.mapbox.maps.MapboxMapsOptions` (maps sdk, the Kotlin facade reached as `MapboxOptions.mapsOptions`): `tileStoreUsageMode` and `tileStore` call straight into the deprecated natives. The facade's own `Deprecated` attributes sit only on Kotlin's synthetic `get…$annotations` markers; there is no `kotlin.Deprecated` on the properties.
- The defaults themselves are native and can't be read by `javap`. The evidence for READ_ONLY and the default-location store is the docs (iOS SDK source above; Android API reference, Mapbox research §5). U48's airplane-mode row is the proof.

Rules for U45:
- **Write no `MapboxMapsOptions`.** iOS's `.readOnly` line restates the default; its `tileStore` line exists only because iOS's store isn't the default one. Android has neither reason, and both setters are deprecated natives.
- If a later change ever names a store for the map, it must be set before the first `MapView` is constructed (options are read at construction, iOS `AppDelegate.swift:50-53`) and must be the same instance the loader uses.
- Gate row: iOS's custom store plus two map options; Android's default store plus none. Same effect: the map reads the store the regions are saved into.

#### 3.2 Style URIs: Android's strings equal the ones iOS loads and saves

A saved stage renders offline only when the map loads the same style URI string the style pack and the region's descriptors were created with. Both platforms use the same two strings.

iOS loads `.light` / `.dark` (`PilgrimMapView.swift:120` builds `let styleURI: StyleURI = isDark ? .dark : .light`), which are `mapbox://styles/mapbox/light-v11` and `mapbox://styles/mapbox/dark-v11` (mapbox-maps-ios 11.20.0 `Style/StyleURI.swift:43,46`). The loader maps its pack enum to the same constants:

```swift
    private static func styleURI(_ pack: StylePackRequest) -> StyleURI {
        pack == .light ? .light : .dark
    }
```
> Pilgrim/Models/Honor/MapboxTileRegionLoader.swift:75-77@7c200bf

Android's map loads `Style.LIGHT` / `Style.DARK`:

```kotlin
    val styleUri = if (darkMode) Style.DARK else Style.LIGHT
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/PilgrimMap.kt:258@ca6424db

```kotlin
        view.mapboxMap.loadStyle(styleUri) {
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/PilgrimMap.kt:710@ca6424db

`javap -constants` on `com.mapbox.maps.Style`: `LIGHT = "mapbox://styles/mapbox/light-v11"`, `DARK = "mapbox://styles/mapbox/dark-v11"`. Equal to iOS's. The loader must use these two constants, never a literal, for the descriptors, the pack loads and the pack-presence filter (§6.5), so a future style change moves all of them together.

#### 3.3 The runtime DEM is left out on both platforms

iOS adds a raster DEM and a hillshade layer at runtime, after the style loads, so it is not part of the `light-v11` / `dark-v11` style a descriptor resolves:

```swift
            var source = RasterDemSource(id: "pilgrim-terrain")
            source.url = "mapbox://mapbox.mapbox-terrain-dem-v1"
            source.tileSize = 514
            try map.addSource(source)
```
> Pilgrim/Models/Walk/MapManagement/PilgrimMapStyle.swift:127-130@7c200bf

The loader names no `tilesets`, so the DEM is never saved, on purpose (§4.1). Offline, iOS's map still adds the DEM source; its hillshade then has no tiles to draw (inferred; PR #86's body: "hillshade renders only with signal"). Android has no hillshade and no runtime DEM at all (`git grep -i -E 'rasterDem|hillshade|terrain-dem|setTerrain'` over `app/src/main` at `ca6424db`: no hits). So the saved content is identical on both platforms (Streets, terrain-v2 contours and landcover, bathymetry, all in the style's `composite` source), and what each map draws offline is what it draws online minus the DEM, which Android never draws anyway.

---

### 4. Tileset descriptors and style packs

#### 4.1 Two descriptors, z11–14, no tilesets

```swift
    /// The two descriptors every region is loaded with, as value types so
    /// a test can read what the SDK is given without opening a `TileStore`.
    /// Nothing is named in `tilesets`: the style's composite source already
    /// carries Streets and terrain-v2 — contours and landcover — and the DEM
    /// the wabi-sabi pass adds at runtime is left out on purpose. Its z11
    /// packs are 8–9 MB each, 511 MB across the Francés, for a hillshade
    /// that renders whenever there is signal.
    static func descriptorOptions() -> [TilesetDescriptorOptions] {
        // The SDK takes the zoom band as `UInt8`; the spec constant is
        // `Int` so a test can pin it without linking Mapbox.
        let zoom = UInt8(PilgrimageTilesDescriptors.streetsZoom.lowerBound)...UInt8(PilgrimageTilesDescriptors.streetsZoom.upperBound)
        return [
            TilesetDescriptorOptions(styleURI: .light, zoomRange: zoom, tilesets: nil),
            TilesetDescriptorOptions(styleURI: .dark, zoomRange: zoom, tilesets: nil)
        ]
    }
```
> Pilgrim/Models/Honor/MapboxTileRegionLoader.swift:31-46@7c200bf

- Order: light, then dark. The zoom band comes from `PilgrimageTilesDescriptors.streetsZoom` (11…14, C1's constant), not a literal.
- `tilesets: nil`: only the style's own sources.
- No `stylePackOptions` in the descriptor, so resolving it creates no style pack; the packs are loaded on their own (§4.3).
- The descriptors are created once, in `init`, through `offlineManager.createTilesetDescriptor(for:)`, and reused for every region (`:52`, `:71`).
- Both styles' single `composite` source names the same three vector tilesets (streets-v8, terrain-v2, bathymetry-v2; Styles API check in the Mapbox research §3), so the two descriptors resolve to the same tile packs, which the store keeps once. Two descriptors don't double the download.

The convenience init iOS calls fills the two arguments iOS leaves out:

```text
        self.init(
            styleURI: styleURI.rawValue,
            minZoom: zoomRange.lowerBound,
            maxZoom: zoomRange.upperBound,
            pixelRatio: pixelRatio ?? Float(ScreenShim.scale),
            tilesets: tilesets,
            stylePack: stylePackOptions,
            extraOptions: extraOptions)
```
> mapbox-maps-ios 11.20.0 `Sources/MapboxMaps/Offline/TilesetDescriptorOptions+MapboxMaps.swift:55-62` (SDK source; unchecked). Its doc: "Pixel ratio to be accounted for when downloading raster tiles. Typically this should match the scale used by the `MapView`, most likely `UIScreen.main.scale`, which is the default value."

So iOS's effective pixel ratio is the screen scale (2 or 3), and `stylePackOptions` and `extraOptions` are `nil`.

Android, from the 11.23.1 binary (`com.mapbox.maps.TilesetDescriptorOptions$Builder`):
- Setters: `styleURI(String)`, `minZoom(byte)`, `maxZoom(byte)`, `pixelRatio(float)`, `tilesets(List<String>)`, `stylePackOptions(StylePackLoadOptions)`, `extraOptions(Value)`.
- The no-arg constructor sets `pixelRatio = 1.0f` and nothing else. `minZoom` and `maxZoom` default to `0`, so both must be set.
- `build()` throws `NullPointerException("styleURI shouldn't be null")` when no style is set, and is otherwise pure Java (no native call).
- `OfflineManager.createTilesetDescriptor(TilesetDescriptorOptions)` is `native` and `@MainThread`, and returns `com.mapbox.common.TilesetDescriptor`.

What the Android loader builds, for each of `Style.LIGHT` then `Style.DARK`:
- `minZoom(11.toByte())`, `maxZoom(14.toByte())`, from C1's `PilgrimageTilesDescriptors` zoom constant;
- no `tilesets(...)` call (the field stays `null`); no `stylePackOptions(...)`;
- `pixelRatio(density)`, where `density` is `Resources.getSystem().displayMetrics.density`, passed in as a parameter of the internal builder function so the test can pin it. This mirrors iOS's effective value (the screen scale). It changes nothing that is downloaded today: the pixel ratio applies to raster tiles only, and with no `tilesets` and no DEM both styles are all-vector (the `composite` source is vector; Mapbox research §3). Leaving Android's builder default of `1.0f` would download the same bytes; the density is chosen so a future raster tileset behaves as on iOS. Record as parity of effective value, not a difference.

#### 4.2 The glyph mode comes from the pinned constant

```swift
    /// Derived from the pinned constant rather than written here: the
    /// SDK's default happens to agree today, and a default is exactly the
    /// kind of thing that gets "fixed".
    static var glyphsRasterizationMode: GlyphsRasterizationMode {
        PilgrimageTilesDescriptors.rasterizesIdeographsLocally ? .ideographsRasterizedLocally : .noGlyphsRasterizedLocally
    }
```
> Pilgrim/Models/Honor/MapboxTileRegionLoader.swift:79-84@7c200bf

Android: `GlyphsRasterizationMode` has `NO_GLYPHS_RASTERIZED_LOCALLY`, `IDEOGRAPHS_RASTERIZED_LOCALLY`, `ALL_GLYPHS_RASTERIZED_LOCALLY`. Port the expression exactly: `if (PilgrimageTilesDescriptors.RASTERIZES_IDEOGRAPHS_LOCALLY) IDEOGRAPHS_RASTERIZED_LOCALLY else NO_GLYPHS_RASTERIZED_LOCALLY` (C1 owns the constant's name; its value is `true`). `StylePackLoadOptions.Builder`'s constructor sets only `acceptExpired = false`; `glyphsRasterizationMode` stays `null` unless set, and the SDK then applies its own default (IDEOGRAPHS, per the 11.20 Swift doc at `StylePackLoadOptions+MapboxMaps.swift:40-41`). So the explicit set is what pins it, as on iOS.

#### 4.3 Style packs: loaded separately, keyed by style URI

```swift
    func loadStylePack(_ pack: StylePackRequest,
                       completion: @escaping (Result<Void, TileRegionLoadingError>) -> Void) -> TileLoadHandle {
        guard let options = StylePackLoadOptions(glyphsRasterizationMode: Self.glyphsRasterizationMode) else {
            completion(.failure(.failed))
            return Handle(AnyCancelable {})
        }
        let cancelable = offlineManager.loadStylePack(for: Self.styleURI(pack), loadOptions: options) { [weak self] result in
            DispatchQueue.main.async {
                switch result {
                case .success:
                    self?.cachedPacks.insert(pack)
                    self?.onChange?(.packs)
                    completion(.success(()))
                case .failure(let error):
                    completion(.failure(Self.mapped(error)))
                }
            }
        }
        return Handle(cancelable)
    }
```
> Pilgrim/Models/Honor/MapboxTileRegionLoader.swift:90-109@7c200bf

- One pack per style URI, two in all: `StylePackRequest.allCases` is `[light, dark]` (`TileRegionLoading.swift:22-24@7c200bf`). The manager loads light then dark.
- Options: the glyph mode only. `metadata` nil, `acceptExpired` false (the Swift init's default), no `extraOptions`.
- The failable init returns `nil` only when `metadata` is not a valid JSON object (`StylePackLoadOptions+MapboxMaps.swift:25`, SDK). No metadata is passed, so the `guard … else { completion(.failure(.failed)) }` branch is unreachable. Android's `StylePackLoadOptions.Builder.build()` never fails (no null check in its bytecode), so the branch has no Android counterpart.
- No progress callback is passed (the two-argument overload).
- On success: insert the pack into `cachedPacks`, fire `.packs`, then complete. **The insert does not check completeness**, unlike `refresh()`'s filter (§6.5): a style pack load can succeed with resources missing ("If the style is fetched but loading some of the style package resources fails, the load request proceeds trying to load the remaining", `OfflineManager+MapboxMaps.swift:28-31`, SDK). Matched as shipped; defect candidate C3-D4.
- On failure: map the error (§9) and complete. The cache is untouched.
- `.packs` fires on every successful pack load, even when the pack was already in `cachedPacks` (a `Set` insert of a present member still reaches the `onChange` line).

Android, from the binary: `OfflineManager.loadStylePack(String, StylePackLoadOptions, StylePackCallback)` and the overload with a `StylePackLoadProgressCallback`, both `native`, `@MainThread`, returning `com.mapbox.common.Cancelable`. `StylePackCallback.run(Expected<StylePackError, StylePack>)`. Use the overload without progress, as iOS does.

---

### 5. The region request

```swift
    func loadRegion(_ request: TileRegionRequest,
                    progress: @escaping (Int, Int) -> Void,
                    completion: @escaping (Result<TileRegionSummary, TileRegionLoadingError>) -> Void) -> TileLoadHandle {
        // One polygon per convex part; the store unions them when it tiles.
        let polygons = request.rings.map { ring in
            [ring.map { LocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude) }]
        }
        guard let options = TileRegionLoadOptions(geometry: .multiPolygon(MultiPolygon(polygons)),
                                                  descriptors: descriptors,
                                                  metadata: ["corridorHash": request.corridorHash],
                                                  acceptExpired: request.acceptExpired) else {
            completion(.failure(.failed))
            return Handle(AnyCancelable {})
        }
        let cancelable = tileStore.loadTileRegion(forId: request.id, loadOptions: options, progress: { loadProgress in
            DispatchQueue.main.async {
                progress(Int(loadProgress.completedResourceCount), Int(loadProgress.requiredResourceCount))
            }
        }, completion: { [weak self] result in
```
> Pilgrim/Models/Honor/MapboxTileRegionLoader.swift:111-129@7c200bf

- **Geometry:** one MultiPolygon. Each convex ring becomes one polygon with one outer ring and no holes, in the order the rings come (C1's emission order). Coordinates are passed as given; C1's rings are already closed (first point repeated last) and counterclockwise. The store unions overlapping parts (PR #86: "verified on device, not by any test").
- **Descriptors:** the two from `init`, light then dark, for every region.
- **Metadata:** exactly `{"corridorHash": "<hex>"}`, one key, a string value.
- **`acceptExpired`:** from the request. The manager always sends `true` (`PilgrimageTilesManager.swift:242-243@7c200bf`), so a resumed save never refreshes tiles it already holds (D7).
- **Network:** no `networkRestriction` argument, so the Swift init's default `.none` (`TileRegionLoadOptions+MapboxMaps.swift:28`, SDK). Cellular is allowed. `averageBytesPerSecond`, `extraOptions`, `startLocation`: none.
- **The failable init** returns `nil` only for invalid-JSON metadata (`TileRegionLoadOptions+MapboxMaps.swift:33`, SDK). A `[String: String]` always passes, so the `.failed` branch is unreachable.
- **Progress:** forwarded to the main queue as `(completed, required)` resource counts. The manager passes `progress: { _, _ in }` (`PilgrimageTilesManager.swift:290@7c200bf`), so progress is ignored end to end and no byte or count progress reaches any UI. Whether Android's seam keeps the parameter is C2's call (iOS's fake records it); it carries no behavior.

Android, from the 24.23.1 binary (`com.mapbox.common.TileRegionLoadOptions$Builder`):
- Setters: `geometry(com.mapbox.geojson.Geometry)`, `descriptors(List<TilesetDescriptor>)`, `metadata(com.mapbox.bindgen.Value)`, `acceptExpired(boolean)`, `networkRestriction(NetworkRestriction)`, `startLocation(Point)`, `averageBytesPerSecond(Integer)`, `extraOptions(Value)`, `build()`.
- The constructor sets `acceptExpired = false` and `networkRestriction = NetworkRestriction.NONE`.
- `build()` throws `NullPointerException("networkRestriction shouldn't be null")` only if `networkRestriction` was set to `null`; it never checks geometry or descriptors. Pure Java.
- `NetworkRestriction`: `NONE`, `DISALLOW_EXPENSIVE`, `DISALLOW_ALL`.

The Android request, as the internal builder function returns it:

```text
TileRegionLoadOptions.Builder()
    .geometry(MultiPolygon.fromLngLats(rings.map { ring -> listOf(ring.map { Point.fromLngLat(it.longitude, it.latitude) }) }))
    .descriptors(descriptors)                       // the two, light then dark
    .metadata(Value.valueOf(hashMapOf("corridorHash" to Value.valueOf(request.corridorHash))))
    .acceptExpired(request.acceptExpired)
    .networkRestriction(NetworkRestriction.NONE)    // the default, set explicitly so the test can pin it
    .build()
```
(Proposed Kotlin, not a quote.)

- **Longitude first.** `com.mapbox.geojson.Point.fromLngLat(double longitude, double latitude)` (mapbox-sdk-geojson 7.10.0, javap). iOS's `LocationCoordinate2D(latitude:longitude:)` is labelled; Android's is positional. The `.build()` test must assert `point.longitude()` and `point.latitude()` of a known ring point.
- `MultiPolygon.fromLngLats(List<List<List<Point>>>)`: outer list = polygons, middle = rings of a polygon (one here), inner = points. `Polygon.fromLngLats` / `MultiPolygon.fromLngLats` do no ring validation, so a degenerate ring reaches the SDK as iOS's does.
- `Value.valueOf(HashMap<String, Value>)` and `Value.valueOf(String)` are pure Java; reading back, `value.contents` is a `HashMap<String, Value>` and each entry's `contents` a `String` (§6.4).
- `NetworkRestriction.NONE`: plan text says "and `NetworkRestriction.NONE`". Setting the default explicitly is fine and testable. Do not use `DISALLOW_EXPENSIVE`: iOS allows cellular, and 11.23.1 doesn't honor that option anyway (fixed in 11.24.1, Mapbox research §2).
- `TileStore.loadTileRegion(String, TileRegionLoadOptions, TileRegionLoadProgressCallback, TileRegionCallback)` (or the three-argument overload without progress): `native`, no thread annotation, returns `Cancelable`. `TileRegionCallback.run(Expected<TileRegionError, TileRegion>)`; `TileRegionLoadProgressCallback.run(TileRegionLoadProgress)`.

---

### 6. The loader's cache, its refresh, and its two signals

#### 6.1 The state

```swift
    var onChange: ((TileStoreChange) -> Void)?

    private let tileStore: TileStore
    private let offlineManager: OfflineManager
    private let descriptors: [TilesetDescriptor]
    /// Empty until the first asynchronous store read lands, then refreshed
    /// on every `regions()` call. `onChange` fires when what `settled`
    /// reads of it changes, which is how a synchronous reader learns the
    /// answer arrived.
    private var cached: [TileRegionSummary] = []
    private var cachedPacks: Set<StylePackRequest> = []
    /// `regions()` refreshes on every read, so several store reads can be in
    /// flight at once and they answer in arbitrary order. Only the newest
    /// may write the cache: an older snapshot landing last would put back
    /// regions a removal took out, or byte counts a save has already
    /// overtaken — and `calibrate` divides by those bytes.
    private var refreshGeneration = 0
    /// Callers waiting for the store's next regions answer, whatever it says.
    private var pendingRegionsCompletions: [() -> Void] = []
```
> Pilgrim/Models/Honor/MapboxTileRegionLoader.swift:48-66@7c200bf

All of it is touched only on the main queue (§8). At process start: `cached = []`, `cachedPacks = []`, `refreshGeneration = 0`, no waiters; `init` then calls `refresh()` (generation 1). The SDK's answers and the region signal are separate by design:

```swift
/// Which of the store's two answers arrived. They are separate round trips
/// that land at different times — the style packs come back from one call,
/// the regions from `allTileRegions` plus a metadata read each — and on a
/// phone that has saved maps the packs answer is reliably first. A reader
/// waiting for what is on disk must not be woken by the packs.
enum TileStoreChange: Equatable {
    case regions
    case packs
}
```
> Pilgrim/Models/Honor/TileRegionLoading.swift:54-62@7c200bf

#### 6.2 Reads, waits, and removal

```swift
    func regions() -> [TileRegionSummary] {
        refresh()
        return cached
    }

    func refreshRegions(completion: @escaping () -> Void) {
        pendingRegionsCompletions.append(completion)
        refresh()
    }

    func removeRegion(id: String) {
        tileStore.removeTileRegion(forId: id)
        // A refresh already in flight is older than this removal; its
        // snapshot would put the region back.
        refreshGeneration += 1
        cached.removeAll { $0.id == id }
        onChange?(.regions)
    }
```
> Pilgrim/Models/Honor/MapboxTileRegionLoader.swift:157-174@7c200bf

- `regions()` returns the cache **as it is now**, and starts a store read whose answer lands later. Every call starts one; there is no coalescing. A reader that calls `regions()` twice in one pass starts two reads.
- `hasStylePack(pack)` is `cachedPacks.contains(pack)` (`:88`), and starts no read.
- `refreshRegions` queues the completion and starts a read. The completion fires on the next **current** regions answer, success or failure, after the cache is written (§6.4).
- `removeRegion` calls the fire-and-forget `removeTileRegion(forId:)` (no callback; the SDK's eviction "might be deferred", and pending loads for that id fail with `Canceled`). It then bumps the generation, drops the id from the cache, and fires `.regions` **unconditionally**, even when the id wasn't cached. It starts no new read.

Android: `TileStore.removeTileRegion(String)` (one argument, no callback) is the same call. Port all three methods line for line.

#### 6.3 A load's success writes the cache

```swift
        }, completion: { [weak self] result in
            DispatchQueue.main.async {
                switch result {
                case .success(let region):
                    let summary = TileRegionSummary(id: region.id,
                                                    completedResourceCount: Int(region.completedResourceCount),
                                                    requiredResourceCount: Int(region.requiredResourceCount),
                                                    completedResourceSize: Int(region.completedResourceSize),
                                                    metadata: ["corridorHash": request.corridorHash])
                    // A refresh already in flight is older than this write;
                    // its snapshot would drop the region again.
                    self?.refreshGeneration += 1
                    self?.cached.removeAll { $0.id == summary.id }
                    self?.cached.append(summary)
                    // `refresh()` compares against a sorted array; leaving
                    // this one appended would read as a change on the next
                    // pass and signal a second time for the same save.
                    self?.cached.sort { $0.id < $1.id }
                    self?.onChange?(.regions)
                    completion(.success(summary))
                case .failure(let error):
                    completion(.failure(Self.mapped(error)))
                }
            }
        })
        return Handle(cancelable)
    }
```
> Pilgrim/Models/Honor/MapboxTileRegionLoader.swift:129-155@7c200bf

- The summary's counts and bytes are the SDK's `TileRegion` at completion; its hash is the **request's**, not a metadata read-back.
- On 11.20 and 11.23.1 a load can succeed with `completedResourceCount < requiredResourceCount` (the `PARTIAL_LOAD` failure is 11.32's). The summary then reads incomplete, the cache holds it, `.regions` fires, and the completion reports success. The manager counts the stage done and moves on (C2). Matched as shipped; it is in the plan's filed list.
- Order: bump generation, replace by id, sort by id, fire `.regions` (always, even if nothing settled changed), then complete. Port the order exactly: the completion's caller (the manager) reads the cache the loader just wrote.
- On failure: the cache is untouched, no signal; the error is mapped (§9).
- Sort order: Swift `String <` on ASCII ids equals Kotlin's `String.compareTo` (UTF-16 code units). Region ids are `pilgrimage:<routeId>:<n>`, so `…:10` sorts before `…:2` on both. The sort exists only so `refresh()`'s comparison is stable; any total order used in both places works. Use `sortedBy { it.id }` in both places.
- Types: iOS's counts and sizes are 64-bit `Int` from `UInt64`. On Android keep `Long` for `completedResourceSize` and the counts (`TileRegion` getters return `long`); an `Int` byte sum overflows at 2.1 GB.

#### 6.4 The refresh

```swift
    private func refresh() {
        refreshGeneration += 1
        let token = refreshGeneration
        tileStore.allTileRegions { [weak self] result in
            guard let self else { return }
            guard case .success(let regions) = result else {
                // The launch reconcile waits on this answer through
                // `refreshRegions`; returning past the drain would leave its
                // sweep pending for the life of the process. The cache is
                // left as it was. Not unit-testable: only a real store can
                // fail here, and the fake has no failing `allTileRegions`.
                DispatchQueue.main.async { [weak self] in
                    guard let self, token == self.refreshGeneration else { return }
                    let waiting = self.pendingRegionsCompletions
                    self.pendingRegionsCompletions = []
                    for completion in waiting { completion() }
                }
                return
            }
            let group = DispatchGroup()
            var summaries: [TileRegionSummary] = []
            let lock = NSLock()
            for region in regions {
                group.enter()
                self.tileStore.tileRegionMetadata(forId: region.id) { metadataResult in
                    let hash = ((try? metadataResult.get()) as? [String: String])?["corridorHash"] ?? ""
                    let summary = TileRegionSummary(id: region.id,
                                                    completedResourceCount: Int(region.completedResourceCount),
                                                    requiredResourceCount: Int(region.requiredResourceCount),
                                                    completedResourceSize: Int(region.completedResourceSize),
                                                    metadata: ["corridorHash": hash])
                    lock.lock(); summaries.append(summary); lock.unlock()
                    group.leave()
                }
            }
            group.notify(queue: .main) { [weak self] in
                guard let self, token == self.refreshGeneration else { return }
                // Taken out from under the equality guard below, which is
                // silent when the answer matches the cache — and an empty
                // store answering an empty cache is exactly the launch case
                // that has to be heard. A stale token returns above and
                // leaves these for the refresh that overtook it.
                let waiting = self.pendingRegionsCompletions
                self.pendingRegionsCompletions = []
                let sorted = summaries.sorted { $0.id < $1.id }
                if self.cached != sorted {
                    let settledChanged = Self.settled(self.cached) != Self.settled(sorted)
                    self.cached = sorted
                    if settledChanged { self.onChange?(.regions) }
                }
                // After the cache is written, never before: a completion reads
                // `regions()` and must see the answer it waited for.
                for completion in waiting { completion() }
            }
        }
```
> Pilgrim/Models/Honor/MapboxTileRegionLoader.swift:186-240@7c200bf

Step by step, the regions half:
1. Bump the generation and take it as this read's token.
2. `allTileRegions` answers on a worker thread.
3. **Failure:** hop to main. If the token is still current, drain every waiter (the cache is left as it was, no signal). If stale, do nothing: the waiters stay queued for whichever read overtook this one.
4. **Success:** one metadata read per region, on the worker threads. A region's hash is the metadata's `corridorHash` string; a failed read, a non-dictionary value, or a missing key all read as `""` (never `nil`). Its counts and bytes are the `allTileRegions` snapshot's, not the metadata answer's.
5. When every metadata read has answered (immediately when there are no regions: a `DispatchGroup` with no `enter` notifies at once), hop to main. If the token is stale, return: the cache is not written and the waiters stay queued.
6. Current: take the waiters, sort the summaries by id. If they differ from the cache in any field, write the cache, and fire `.regions` only if the **settled projection** changed (§6.6). Then run the waiters, after the write.

Android port:
- `tileStore.getAllTileRegions { expected -> }` (`TileRegionsCallback.run(Expected<TileRegionError, List<TileRegion>>)`), then `getTileRegionMetadata(id) { expected -> }` (`Expected<TileRegionError, Value>`).
- `com.mapbox.bindgen.Expected<E, V>`: `isValue()`, `isError()`, `getValue()`, `getError()` (both nullable; Kotlin reads them as `expected.value` / `expected.error`), plus `fold`, `onValue`, `onError`. Its constructor is package-private; tests build one with `ExpectedFactory.createValue` / `createError`.
- `TileRegion` getters: `getId()`, `getRequiredResourceCount()`, `getCompletedResourceCount()`, `getCompletedResourceSize()` (all `long`), `getExpires()` (`Date`, nullable), `getExtraData()` (`Value`, nullable). The loader reads the first four, as iOS does.
- Read the hash as `((expected.value?.contents as? Map<*, *>)?.get("corridorHash") as? Value)?.contents as? String ?: ""`. Every failure shape (error, null, wrong type) gives `""`, as iOS's `try?` and `as?` chain does.
- The fan-in: hop the list to Main first, issue the metadata reads from Main, hop each answer to Main and count down there; run step 6 when the count reaches zero, or at once for an empty list. This keeps every mutable field on Main with no lock, and is equivalent to iOS's lock plus `group.notify(queue: .main)`: the token is still checked only at the end.
- `TileStore` methods carry no thread annotation, so calling `getTileRegionMetadata` from Main is allowed.

#### 6.5 The packs half of the same refresh

```swift
        offlineManager.allStylePacks { [weak self] result in
            guard case .success(let packs) = result else { return }
            // A pack whose load was interrupted persists partially and still
            // reports its style URI here; reading it as present would skip
            // it on every later save and leave the offline map without a
            // style.
            let complete = packs.filter { Self.isComplete(completed: Int($0.completedResourceCount), required: Int($0.requiredResourceCount)) }
            let uris = Set(complete.map(\.styleURI))
            DispatchQueue.main.async {
                let present = Set(StylePackRequest.allCases.filter { uris.contains(Self.styleURI($0).rawValue) })
                guard let self, token == self.refreshGeneration, self.cachedPacks != present else { return }
                self.cachedPacks = present
                self.onChange?(.packs)
            }
        }
    }
```
> Pilgrim/Models/Honor/MapboxTileRegionLoader.swift:241-256@7c200bf

- Called from the same `refresh()`, on main, right after `allTileRegions` is issued; it shares the token.
- A **failed** packs read returns on the worker thread: no hop, no signal, nothing changes, nobody is told.
- A pack counts as present only when complete by the same rule as a region (§6.6). Presence is by exact style-URI string.
- The answer is dropped when the token is stale (a load success, a removal or a newer read came first), or when the set is unchanged. Otherwise the set is written and `.packs` fires.
- There is no "packs waiter": `refreshRegions` completions ride the regions half only.

Android: `OfflineManager.getAllStylePacks(StylePacksCallback)` (`@MainThread`; `run(Expected<StylePackError, List<StylePack>>)`), `StylePack.getStyleURI()`, `getCompletedResourceCount()`, `getRequiredResourceCount()` (`long`). Compare against `Style.LIGHT` / `Style.DARK`.

#### 6.6 The settled projection and completeness

```swift
    /// What `onChange(.regions)` speaks for: which regions exist, whether
    /// each is done, and which corridor it was loaded for. Counts and bytes
    /// move on every progress tick of a save and are left out.
    struct SettledRegion: Equatable {
        let id: String
        let isComplete: Bool
        let corridorHash: String?
    }

    static func settled(_ regions: [TileRegionSummary]) -> [SettledRegion] {
        regions.map { SettledRegion(id: $0.id, isComplete: $0.isComplete, corridorHash: $0.corridorHash) }
    }

    /// Mirrors `TileRegionSummary.isComplete`: a `StylePack` reports the same
    /// two counts under different names, so the completeness rule for "is
    /// this style present" has to be the same rule as "is this region done".
    static func isComplete(completed: Int, required: Int) -> Bool {
        required > 0 && completed >= required
    }
```
> Pilgrim/Models/Honor/MapboxTileRegionLoader.swift:258-276@7c200bf

- The projection is an **ordered list** of `(id, isComplete, corridorHash)`, compared elementwise. Since the cache is always sorted by id, order is stable.
- `corridorHash` is `String?` from `metadata["corridorHash"]`; the loader always writes the key, so in practice it is a string, `""` when unreadable.
- Completeness: `required > 0 && completed >= required` (strict `>` on required; `>=` on completed). `0 of 0` is incomplete.
- Port both as pure functions on the Android loader's companion (or a `MapboxTileRegionLoader` object), testable without Mapbox: `settled(List<TileRegionSummary>): List<SettledRegion>` with `data class SettledRegion(val id: String, val isComplete: Boolean, val corridorHash: String?)`, and `isComplete(completed: Long, required: Long)`. C2's `TileRegionSummary.isComplete` must use the same expression.

#### 6.7 When each signal fires

| Event | Cache change | `.regions` | `.packs` | Waiters drained |
|---|---|---|---|---|
| Region load succeeds (complete or partial) | replace by id, sort | always | no | no |
| Region load fails | none | no | no | no |
| `removeRegion(id)` | drop id | always (even for an unknown id) | no | no |
| Pack load succeeds | insert (no completeness check) | no | always | no |
| Pack load fails | none | no | no | no |
| Current regions answer, settled projection differs | write | yes | no | yes, after the write |
| Current regions answer, only counts or bytes differ | write | no | no | yes |
| Current regions answer, identical | none | no | no | yes |
| Current regions read **fails** | none | no | no | yes |
| Stale regions answer or stale failure | none | no | no | no (they wait for a later current answer) |
| Current packs answer, complete-pack set differs | write | no | yes | n/a |
| Packs answer stale, unchanged, or failed | none | no | no | n/a |

Two consequences for C2 and the readers:
- A save's per-stage loads fire `.regions` once each; the refreshes that follow are silent unless something settled changed. Progress ticks never signal, which is why the manager's readers can reload on `.regions` without spinning (iOS test 6).
- A packs-only change fires no `.regions`, so the manager's `regionsChanged` stays quiet (C2 owns the mapping; `PilgrimageTilesManager.swift:57-66@7c200bf`).

#### 6.8 Stranded waiters (matched; candidate C3-D2)

A removal or a load success bumps the generation without starting a read. Any read already in flight then answers stale, and its waiters stay queued until some later read answers current, which happens only when someone next calls `regions()` or `refreshRegions`. The launch reconcile is such a waiter (`PilgrimageTilesManager.swift:358-368@7c200bf`): a hook removal or a finished load landing between the reconcile's request and the store's answer delays the sweep to the next reader. On iOS a reader usually comes soon (any status read refreshes), so the sweep is late, not lost. Android matches it, except for the first answer after process start (§7).

---

### 7. The first answer after process start (D6): what the loader must expose

iOS readers read the cache synchronously, and the cache is empty until the first answer lands (well under a second on iOS). The plan's Android addition makes readers and the package hooks wait, with a bound, for that answer, because Android restarts the UI process far more often. The loader is where "the first answer" is defined, so this section pins the loader's half; C2 builds the bound, the readers and the hooks.

Facts that shape it:
1. An empty store answering an empty cache fires **no** `.regions` (§6.7). Waiting on `onChange` would hang a reader on a phone with no saved maps; the wait must ride on the answer, not on a difference (iOS's own reason for `refreshRegions`, `TileRegionLoading.swift:80-85@7c200bf`).
2. A failed regions read answers its waiters with the cache unchanged. At process start that cache is empty, so "answered" is not "known".
3. A stale answer drains nothing. At cold start, if the first read is overtaken by a removal (a package hook) or a load success, its answer is dropped and no new read is in flight. The cache then reflects only that one event, not the store, and a waiter would sit out its whole bound.
4. The packs answer is separate. `status` needs both packs for `saved`; reading regions without packs gives `partial(n, of: n)` ("n of n saved") for a fully saved route. A failed packs read is silent on iOS.

Contract (Android addition; gate row with the cold-cache wait):
- **`firstAnswer`**: a result the loader completes on Main when the first **current** regions answer and the first **current** packs answer have both landed. Value `READ` when both reads succeeded and the cache and pack set were written from them; `FAILED` when either read failed (the cache is not a snapshot). `READ` holds for the process and is answered at once on every later call. A call after `FAILED` starts a fresh attempt with a new read and answers on that, never replaying the failure (re-askable, decided at U44's review, so one failed read isn't the answer for the rest of the process); until that call, a `FAILED` answer stands and stale answers are dropped. Expose it as a `Deferred<StoreRead>` or a suspend `awaitFirstAnswer(): StoreRead`; C2 wraps it in `withTimeoutOrNull(bound)`.
- **Readers** (status, `isStageSaved`, footprint) treat `FAILED` exactly like the timeout: "unknown" (the morning card gets no line). They never treat an empty pre-answer cache as "nothing saved". What "unknown" means for the hooks' prefix scan and a save's skip snapshot is C2's (the plan's open question on the hooks past the bound); the loader only reports `READ` or `FAILED`.
- **The stale case:** while `firstAnswer` is pending, a regions or packs answer that arrives stale makes the loader start a new `refresh()` at once (on Main), unless a newer read is already in flight (a later `regions()` call overtook it; that read will answer). Track the token of the newest read started to tell the two apart. This guarantees the wait ends with a real snapshot or a real failure in about two store round trips. After `firstAnswer` completes, stale answers are dropped exactly as iOS drops them (§6.8, matched); an attempt asked again after `FAILED` is pending, so it reads again on a stale answer until it settles. With C2's hooks and save waiting for `firstAnswer` before they remove or load, the re-read is an edge case (a Delete or a direct removal racing the first read), but it is what makes the bound a backstop rather than the normal path.
- **The packs failure:** Android must hop a failed packs read to Main (iOS returns on the worker thread) so it can complete `firstAnswer` with `FAILED`. Nothing else about the packs path changes: no signal, the set is untouched.
- **Waiters:** `refreshRegions` keeps iOS's semantics exactly (drained on the next current regions answer, success or failure, after the write). It is not the same thing as `firstAnswer`, and the reconcile keeps riding on `refreshRegions`, as on iOS.
- **After `firstAnswer`:** `regions()` and `hasStylePack` behave as on iOS, answering from the cache and refreshing.
- The fake (C2) needs a matching `firstAnswer` it completes when a test drives the first refresh, and a way to fail it.

Ordering note for C2's "one FIFO order" question: every loader mutation and callback runs on the main looper in post order, so if the manager also runs on Main, hooks, the reconcile and a save's start are ordered by when they reach Main. A hook arriving from the package manager's IO actor posts to Main; a save started after that post can't be cancelled by it, provided the hook does its cancel synchronously when its turn on Main comes (not after an await). If the hook first awaits `firstAnswer`, its `cancel()` must still run at its turn, before the await, or a save that starts during the await would be cancelled by a hook that predates it. Recommend: the hook's `cancel()` runs immediately on Main; only its prefix scan waits for `firstAnswer`.

---

### 8. Threading and cancellation

#### 8.1 iOS

- The manager is `@MainActor` (`PilgrimageTilesManager.swift:10@7c200bf`), so every loader method is called on main. The seam promises "Every method is synchronous to call and reports through closures on the main queue" (`TileRegionLoading.swift:64-65@7c200bf`).
- Every SDK callback hops with `DispatchQueue.main.async` before touching state or calling back: pack completion (`:97`), region progress (`:126`), region completion (`:130`), the failed regions read (`:197`), the packs answer (`:249`). The metadata fan-in collects under an `NSLock` on worker threads and lands on main through `group.notify(queue: .main)` (`:205-221`).
- The SDK documents the worker threads: TileStore's "user-provided callbacks will be executed on a TileStore-controlled worker thread; it is the responsibility of the user to dispatch to a user-controlled thread" (`TileStore+MapboxMaps.swift:67-70`, SDK), and `allStylePacks`'s "will be executed on a worker thread" (`OfflineManager+MapboxMaps.swift:63-66`, SDK).
- The production loader is built by `PilgrimageTilesManager.shared` (`PilgrimageTilesManager.swift:41@7c200bf`), first touched on the main actor in `reconcileTilesAtLaunch` (`AppDelegate.swift:186-191@7c200bf`). Its `init` creates the store, the offline manager and both descriptors, and starts the first read, all on main.

#### 8.2 Android (from the 11.23.1 binaries)

- `com.mapbox.maps.OfflineManager`: every method carries `androidx.annotation.MainThread` (`createTilesetDescriptor`, both `loadStylePack`, `getAllStylePacks`, `getStylePack`, `getStylePackMetadata`, both `removeStylePack`). The public constructor isn't annotated but calls the native `initialize()`; construct it on Main too.
- `com.mapbox.common.TileStore`: no thread annotations; its callbacks run on TileStore worker threads (same core, same doc).
- Style-pack callbacks also arrive off Main (iOS SDK doc above; Mapbox's Android offline guide: "called on a background thread").
- `MapsResourceOptions.clearData` (behind `MapboxMap.clearData`) and every `OfflineSwitch` method are `@MainThread`.

The loader's rules (U45):
1. Every public loader method is called on Main, by the Main-confined manager (plan Key Technical Decisions). Add a fail-fast check (`check(Looper.myLooper() == Looper.getMainLooper())`) at each entry: a Mapbox `@MainThread` call made off Main is undefined behavior, not an exception, so the check is the only early warning. Android addition (no iOS counterpart; iOS's actor isolation is compile-time).
2. The loader creates no Mapbox object in its constructor. `TileStore.create()`, `OfflineManager()` and the two `createTilesetDescriptor` calls happen on the first loader call, on Main, then are held for the process. Constructing the class (Hilt, a `Provider`, `:tracker`'s graph) then runs no Mapbox static initializer (they run on a class's first active use, and each calls `BaseMapboxInitializer.init`, §13.1) and opens no store. iOS builds them in `init`; the difference is only when, and it is what keeps `:tracker` and flag-off builds Mapbox-free (§11).
3. Every SDK callback does one thing on its worker thread: post to Main (`Handler(Looper.getMainLooper()).post { … }`, the equivalent of `DispatchQueue.main.async`: always enqueued, FIFO). All state reads and writes, signals and completions happen in the posted block. Nothing that can throw runs on a Mapbox worker thread.
4. The first refresh starts on that first call (iOS starts it in `init`, which runs at launch through `reconcileTilesAtLaunch`). Since `firstAnswer` (§7) is what readers wait on, the first touch must come early: U45's launch work (flag on, UI process) starts the first read, normally through the reconcile's `refreshRegions`. When a thrown `runAtLaunch()` skips the reconcile, the launch work still starts the first read (a plain `refresh`), so a reader that opens "the day" right after a restart finds the answer in flight. Flag off, nothing touches the loader.

#### 8.3 Cancellation

```swift
    final class Handle: TileLoadHandle {
        private let cancelable: Cancelable
        init(_ cancelable: Cancelable) { self.cancelable = cancelable }
        func cancel() { cancelable.cancel() }
    }
```
> Pilgrim/Models/Honor/MapboxTileRegionLoader.swift:11-15@7c200bf

- The handle forwards to Mapbox's `Cancelable.cancel()`. Android: `com.mapbox.common.Cancelable` is a one-method interface (`void cancel()`), returned by both `loadTileRegion` and `loadStylePack`. Wrap it the same way; call it on Main.
- Who cancels: only the manager, in `cancel()`, `remove(routeId)` (which calls `cancel()`), the save's error path, and `deinit` (`PilgrimageTilesManager.swift:256-266,315-328@7c200bf`). The loader never cancels on its own. A `removeTileRegion(id)` also fails a pending load of that id with `Canceled` (SDK doc).
- Does a cancelled load report back? iOS's manager says no:

```swift
    /// The continuation the in-flight load will resume. `cancel()` resumes it
    /// itself, because a cancelled load never reports back.
```
> Pilgrim/Models/Honor/PilgrimageTilesManager.swift:193-194@7c200bf

  The SDK says yes, once: "completion: Invoked only once upon success, failure, or cancelation of the loading operation" (`TileStore+MapboxMaps.swift:43-45` and `OfflineManager+MapboxMaps.swift:13-15`, 11.20 SDK; the Android 11.23.1 API reference says the same of `loadTileRegion`'s callback, Mapbox research §2). iOS's comment is wrong about the SDK, but harmless: the manager resumes the continuation itself in `cancel()`, and the late `CANCELED` completion meets `guard self.generation == myGeneration else { return }` (`:279`, `:297`). The fake keeps a cancelled load queued so tests can deliver that late completion (`PilgrimageTilesManagerTests.swift:214-218@7c200bf`).
- On Android the late completion (a `CANCELED` error, or a success that raced the cancel) arrives on a worker thread and is posted to Main like any other. In the loader: a late `CANCELED` maps to `cancelled` (or `failed` for a pack, §9) and reaches the manager's closure, which drops it by generation. A late success still writes the cache, bumps the generation and fires `.regions`, which is correct: the region really is in the store. The generation guard makes both "reports back" and "never reports back" safe, so U44 must not depend on either (C2's test: a late completion after cancel never resumes twice).
- Nothing on the loader side needs cancelling at teardown: the loader lives for the process.

---

### 9. Errors

#### 9.1 iOS's map

```swift
    static func mapped(_ error: Error) -> TileRegionLoadingError {
        if let tileError = error as? TileRegionError, case .diskFull = tileError { return .diskFull }
        if let packError = error as? StylePackError, case .diskFull = packError { return .diskFull }
        if WayMediaDownloader.isDiskFull(error) { return .diskFull }
        if let tileError = error as? TileRegionError, case .tileCountExceeded = tileError { return .tileCountExceeded }
        if let tileError = error as? TileRegionError, case .canceled = tileError { return .cancelled }
        return .failed
    }
```
> Pilgrim/Models/Honor/MapboxTileRegionLoader.swift:278-285@7c200bf

In order, first match wins:

| SDK error | iOS result |
|---|---|
| `TileRegionError.diskFull` | `diskFull` |
| `StylePackError.diskFull` | `diskFull` |
| anything `WayMediaDownloader.isDiskFull` accepts | `diskFull` |
| `TileRegionError.tileCountExceeded` | `tileCountExceeded` |
| `TileRegionError.canceled` | `cancelled` |
| `TileRegionError.doesNotExist`, `.tilesetDescriptor`, `.other` | `failed` |
| **`StylePackError.canceled`**, `.doesNotExist`, `.other` | `failed` (a cancelled pack is `failed`, not `cancelled`) |
| anything else (the SDK's `TypeConversionError`) | `failed` |

The manager then maps `diskFull` → `PilgrimageError.diskFull`, `tileCountExceeded` → `.mapTooLarge`, `failed` and `cancelled` → `.incomplete` (`PilgrimageTilesManager.swift:305-311@7c200bf`). So the pack `canceled` → `failed` quirk is invisible to the walker; port it as is.

**The third clause never matches an SDK error on iOS.** The SDK hands `mapped` Swift enums: `coreAPIClosureAdapter` turns every core error into `TileRegionError(coreError:)` or `StylePackError(coreError:)` (`OfflineCallbacks.swift:40-41`, SDK), enums that conform to `LocalizedError` and carry the core message as an associated value (`OfflineErrors.swift:9-61,64-107`, SDK). `isDiskFull` looks only at `URLError`, the Cocoa domain and an underlying error:

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

A Swift enum bridged to `NSError` gets its own type name as the domain and no underlying error. Probed with the same enum shape and this exact function (`docs/parity/probes/2026-10-06-offline-maps/C3/bridge.swift`): `other("No space left on device (ENOSPC)")` bridges to domain `bridge.TileRegionError`, code 4, empty `userInfo`, and `isDiskFull` returns `false`. So on iOS an SDK error whose type is `other` but whose message speaks of a full disk reads `failed` → "the download didn't finish". Only test 5's hand-built `URLError(.cannotWriteToFile)` reaches the third clause. It is dead code for real SDK errors; not a user-visible defect, so not filed (note it in the themed issue only if the owner wants).

#### 9.2 Android: the same map, by type only (correction to the plan)

From the binaries:
- `com.mapbox.common.TileRegionError(TileRegionErrorType type, String message)`, public constructor, `getType()`, `getMessage()`; `TileRegionErrorType`: `CANCELED`, `DOES_NOT_EXIST`, `TILESET_DESCRIPTOR`, `DISK_FULL`, `OTHER`, `TILE_COUNT_EXCEEDED`.
- `com.mapbox.maps.StylePackError(StylePackErrorType type, String message)`, public constructor; `StylePackErrorType`: `CANCELED`, `DOES_NOT_EXIST`, `DISK_FULL`, `OTHER`.
- Both are plain `Serializable` records, not `Throwable`s, and arrive inside `Expected.getError()`.

Android's current disk-full test:

```kotlin
        internal fun isDiskFull(error: Throwable): Boolean =
            generateSequence(error) { it.cause }.take(MAX_CAUSES).any { cause ->
                val message = cause.message.orEmpty()
                cause is SQLiteFullException ||
                    message.contains(ENOSPC_NAME) || message.contains(ENOSPC_TEXT) || isErrnoNoSpace(cause)
            }
```
> app/src/main/java/org/walktalkmeditate/pilgrim/data/honor/WayMediaDownloadWorker.kt:332-337@ca6424db

with `ENOSPC_NAME = "ENOSPC"` and `ENOSPC_TEXT = "No space left on device"` (`:346-347`). It takes a `Throwable` and walks up to 8 causes.

The plan says the Android loader should also map to disk full "an error whose message passes `WayMediaDownloadWorker.isDiskFull`'s ENOSPC test, applied through a message-taking helper", because "iOS's are NSErrors". That premise is wrong (§9.1): iOS never reads the message, so a message test would turn an `OTHER` error that mentions ENOSPC (or SQLite's "database or disk is full", which the helper wouldn't even catch) into Android's disk-full line where iOS shows "the download didn't finish". That is an Android-only divergence. **Port the map by type only:**

```text
fun mapped(error: TileRegionError): TileRegionLoadingError = when (error.type) {
    DISK_FULL -> DISK_FULL
    TILE_COUNT_EXCEEDED -> TILE_COUNT_EXCEEDED
    CANCELED -> CANCELLED
    else -> FAILED                      // DOES_NOT_EXIST, TILESET_DESCRIPTOR, OTHER
}
fun mapped(error: StylePackError): TileRegionLoadingError = when (error.type) {
    DISK_FULL -> DISK_FULL
    else -> FAILED                      // CANCELED included, as iOS
}
```
(Proposed Kotlin, not a quote. C2 owns `TileRegionLoadingError`'s names.)

- Two overloads instead of one `Error`-typed function: Android's loader always knows which kind it holds. If a single entry point is wanted, a sealed input works too; the table above is what must hold.
- `isDiskFull` is not called and no message helper is added. Record at the gate as "same map; iOS's third clause has no reachable Android counterpart".
- If the owner prefers the message test anyway, it is a new behavior on both platforms: file it on iOS first (house rule), then fold it in. Proposed owner decision O-C3-1.
- Android's `Expected` can in principle carry a `null` error and a `null` value; treat that as `FAILED`, as iOS's adapter does with `TypeConversionError.unexpectedType` (`OfflineCallbacks.swift:30-33`, SDK).

#### 9.3 What each loader error path does

- `loadStylePack` failure: completion `(failure(mapped))`. No cache change, no signal.
- `loadRegion` failure: the same.
- The two unreachable `nil`-options branches (§4.3, §5): no Android counterpart.
- A failed `allTileRegions`: drains current waiters, cache untouched (§6.4). A failed `getTileRegionMetadata`: that region's hash reads `""`, so it can never match a stage's hash and reads as stale (the stage re-downloads on the next save; Settings still counts its bytes).
- A failed `allStylePacks`: silent on iOS; on Android, also completes `firstAnswer` as `FAILED` when it is the first (§7).
- 11.23.1 partial success: not an error at all (§6.3).

---

### 10. Backup and device transfer

#### 10.1 iOS

The store's folder is excluded from iCloud backup by the SDK's `shared(for:)` (§2.1). Nothing in Pilgrim sets `isExcludedFromBackup` on it; the design spec's fallback ("If the SDK does not set `isExcludedFromBackup`, the app does", design §6) was not needed. The per-route calibration lives in `UserDefaults` (C1/C2), which iCloud backup carries. iOS has no separate device-transfer rule; R23's "stay out of device-to-device transfer" is Android's counterpart of that backup exclusion.

#### 10.2 Android today

```xml
    <application
        android:name=".PilgrimApp"
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
```
> app/src/main/AndroidManifest.xml:77-81@ca6424db

```xml
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="file" path="."/>
        <exclude domain="database" path="."/>
        <exclude domain="sharedpref" path="."/>
        <!-- Redundant with the blanket file-domain exclude above, but
             explicit on purpose: derived psychological/linguistic data
             (Thought Threads) must never leave the device even if a
             future edit narrows the blanket exclude to specific paths. -->
        <exclude domain="file" path="transcript_contexts/"/>
    </cloud-backup>
    <device-transfer>
        <include domain="database" path="."/>
        <include domain="file" path="."/>
// …
        <exclude domain="file" path="datastore/share_device_token.preferences_pb"/>
// …
        <exclude domain="file" path="transcript_contexts/"/>
    </device-transfer>
</data-extraction-rules>
```
> app/src/main/res/xml/data_extraction_rules.xml:2-29@ca6424db

```xml
<full-backup-content>
    <exclude domain="file" path="."/>
    <exclude domain="database" path="."/>
    <exclude domain="sharedpref" path="."/>
    <!-- See data_extraction_rules.xml's cloud-backup domain for why this
         is explicit despite the blanket file-domain exclude above. -->
    <exclude domain="file" path="transcript_contexts/"/>
</full-backup-content>
```
> app/src/main/res/xml/backup_rules.xml:2-9@ca6424db

- Cloud backup (API 31+): every file, database and shared-pref excluded. `.mapbox/` is already out.
- `backup_rules.xml` (`fullBackupContent`) governs both cloud backup and device-to-device transfer on API 28–30 (minSdk 28). It excludes the whole file domain, so `.mapbox/` is already out there too.
- **Device transfer (API 31+): includes all of `filesDir`.** So today `files/.mapbox/` (the store and the ambient map cache) travels to a new phone. With saved maps that can be hundreds of MB (the Francés is about 240 MB).
- Shared prefs are not in the device-transfer includes, so Mapbox's persisted settings (the store path key, §2.3) never travel. DataStore files live under `files/datastore/` and do travel; the calibration figure rides along, which is harmless and matches iOS's `UserDefaults` in a backup (plan Key Technical Decisions).

#### 10.3 The exact lines to add (U45)

`R/xml/data_extraction_rules.xml`, inside `<cloud-backup>` after the `transcript_contexts/` exclude:

```text
        <!-- Mapbox's folder: the tile store with any saved maps, and the
             map's own cache. Re-downloadable and up to hundreds of MB.
             Redundant with the blanket exclude above, explicit like
             transcript_contexts/. -->
        <exclude domain="file" path=".mapbox/"/>
```

and inside `<device-transfer>`, after the `transcript_contexts/` exclude:

```text
        <!-- Mapbox's folder (files/.mapbox/): the tile store with any saved
             maps, and the map's cache. A new phone saves its maps again,
             as iOS does after a restore (exclude takes precedence over the
             include above). -->
        <exclude domain="file" path=".mapbox/"/>
```

`R/xml/backup_rules.xml`, after the `transcript_contexts/` exclude:

```text
    <!-- See data_extraction_rules.xml: Mapbox's tile store and map cache. -->
    <exclude domain="file" path=".mapbox/"/>
```
(Proposed XML, not quotes.)

Checked against Android's backup-rule semantics (Auto Backup / data-extraction docs, and the existing file's own precedent):
- **An exclude inside an included domain wins.** The platform's full-backup walk goes through each included tree and skips any file or directory whose canonical path is in the exclude set; the file already relies on this for `datastore/share_device_token.preferences_pb` and `transcript_contexts/` ("exclude takes precedence over the include above", `data_extraction_rules.xml:21@ca6424db`).
- **A directory path applies recursively** ("If you specify a directory, then the rule applies to all files in the directory and recursive sub-directories"). The walk never descends into an excluded directory.
- `path` is resolved relative to the domain root (`filesDir` for `file`) and canonicalized, so the trailing slash is cosmetic, as in `transcript_contexts/`. A leading dot is an ordinary name; only `..` (parent traversal) and wildcards are refused. `.mapbox/` is valid.
- One line covers both the store (`.mapbox/tile_store/`) and the map cache (`.mapbox/map_data/`), and any other folder Mapbox adds under `.mapbox/`.
- `backup_rules.xml` covers API ≤ 30 for both cloud and device transfer; `data_extraction_rules.xml` covers API 31+.
- The change is static XML, so it ships unflagged (plan). It also stops today's ambient map cache travelling, which is a pre-existing leak, not a parity question.

#### 10.4 The test (U45) and the device row (U48)

`WaysBackupRulesTest`'s helper gathers `<include>` domains across the whole document and can't tell `<cloud-backup>` from `<device-transfer>` (`T/data/honor/WaysBackupRulesTest.kt:29-44@ca6424db`). Add a section-aware parse (track the enclosing `cloud-backup` / `device-transfer` start tag) and assert:
- `<device-transfer>` holds `exclude domain="file" path=".mapbox/"`;
- `<cloud-backup>` holds the same;
- `backup_rules.xml` holds the same;
- the existing assertion still passes (includes are exactly `database` and `file`; nothing reaches `no_backup`).

U48: confirm the store path first (§2.4). For the transfer row itself, either a real transfer between two phones (Stage 21-2's G2 was waived, 2026-10-06), or a one-phone check with the platform's local backup transport set to device-transfer mode. Suggested, not verified on this device: `adb shell bmgr transport com.android.localtransport/.LocalTransport`, `adb shell settings put secure backup_local_transport_parameters is_device_transfer=true`, `adb shell bmgr backupnow org.walktalkmeditate.pilgrim.debug`, clear the app, restore with `bmgr restore`, then `run-as … ls -la files/`: the app's other files are back and `.mapbox/` is not. Reset the transport and the setting afterwards.

---

### 11. Mapbox stays in the UI process

- `PilgrimApp.onCreate` returns before touching Mapbox in any non-main process:

```kotlin
        if (!isMainProcess()) {
            Log.i(TAG, "onCreate: skipping UI inits in non-main process ${getProcessName()}")
            return
        }
// …
        MapboxOptions.accessToken = BuildConfig.MAPBOX_ACCESS_TOKEN
```
> app/src/main/java/org/walktalkmeditate/pilgrim/PilgrimApp.kt:266-276@ca6424db

- Mapbox's own startup initializers (`com.mapbox.common.MapboxSDKCommonInitializer`, `com.mapbox.maps.loader.MapboxMapsInitializer`) are `androidx.startup` entries on the merged `InitializationProvider`, which has no `android:process`, so they run in the main process only (the AAR manifests; the app's manifest merges the provider and removes only WorkManager's entry, `AndroidManifest.xml:319-328@ca6424db`). `:tracker` is `WalkTrackingService`'s process (`:307-312`).
- So two processes never open the store. Mapbox documents one instance per path within a process and nothing about sharing across processes; the store is SQLite plus files with startup cleanup, so a second process on the same folder is unsupported.
- U45's rules, which keep it that way:
  - only the UI process resolves the tiles manager and the loader, through `Provider`s in UI launch work (plan);
  - the loader's constructor touches no Mapbox class (§8.2 rule 2), so even an accidental construction in `:tracker` opens nothing;
  - the package manager gets the tiles manager lazily too (a `Provider` or `Lazy` in its `@Inject` constructor), because `HonorFinalizer` and other graphs are built in both processes. `PilgrimagePackageManager` itself is "UI process only: `:tracker` never builds it" (`PilgrimagePackageManager.kt:88-89@ca6424db`); keep that true;
  - the debug harness's new tiles commands go on `HonorDebugReceiver` (UI process), never on `HonorReplayReceiver` (`android:process=":tracker"`, `app/src/debug/AndroidManifest.xml:73-77@ca6424db`).
- The loader needs `MapboxOptions.accessToken` set before its first load; `PilgrimApp.onCreate` sets it before anything else in the main process, so any later first touch is safe. A blank token (contributor builds) makes loads fail with an SDK error (`failed` → "the download didn't finish"); nothing crashes, since the loader builds no `MapView` (the `MapboxConfigurationException` path is the map's, `PilgrimMap.kt:232-256@ca6424db`). U45 should keep the maps row's flag gate and not special-case the token.

---

### 12. Telemetry (note for the gate's #92 check)

Android turns Mapbox's telemetry off only once a style has loaded, per `MapView`:

```kotlin
            if (!telemetryOptedOut) {
                try {
                    view.attribution.getMapAttributionDelegate()
                        .telemetry()
                        .setUserTelemetryRequestState(false)
                    telemetryOptedOut = true
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/PilgrimMap.kt:797-802@ca6424db

So a save started before any map has loaded in the process, or on an install where no map ever has, runs under whatever Mapbox's persisted telemetry state is. Whether the map's opt-out persists across processes, and whether the offline loads emit telemetry at all, is not visible statically (the telemetry module isn't in these jars; `com.mapbox.common.TelemetryUtils.setEventsCollectionState(boolean, TelemetryUtilsResponseCallback)` is the common, map-independent switch). Note only (post-pin): iOS PR #92 (`10540d0`) writes iOS's opt-out on every launch, in `AppDelegate`, right after the two tile-store lines and before the first map (`git diff 10540d0^1 10540d0 -- Pilgrim/AppDelegate.swift`). If the gate folds that in, Android's counterpart belongs in `PilgrimApp.onCreate` beside `MapboxOptions.accessToken` (main process only), which would also cover a save made before any map. Nothing for this stage to build; the gate's #92 row should include the tiles path (a save with no map loaded yet in the process).

---

### 13. Platform-object builder tests (CLAUDE.md rule)

#### 13.1 What runs under Robolectric

Static initializers (javap `-c`, `static {}`):
- `com.mapbox.common.TileRegionLoadOptions`, `TileRegion`, `TileRegionError`, `TilesetDescriptor`, `TileStore` call `BaseMapboxInitializer.init(MapboxSDKCommonInitializerImpl.class)`.
- `com.mapbox.maps.TilesetDescriptorOptions`, `StylePackLoadOptions`, `StylePack`, `StylePackError`, `OfflineManager`, and the classes the existing tests already build (`CameraOptions`, `MapOptions`, `EdgeInsets`) call `BaseMapboxInitializer.init(MapboxMapsInitializerImpl.class)`.
- `BaseMapboxInitializer$Companion.init(Class, boolean)`: returns at once if that initializer already succeeded or is in progress. Otherwise, when `appContext` is `null` (no `create()` has run), it logs a warning and calls `getApplicationContextFromActivityThread()`. That returns `null` when `Build.FINGERPRINT` equals `"robolectric"`, and `init` returns without loading any native library. Under Robolectric `appContext` is `null`: had Mapbox's `androidx.startup` initializer run, it would have tried to load the arm64 `.so` on the host JVM and the existing Mapbox builder tests could not pass.
- The path doesn't depend on which initializer class is passed. `PilgrimMapCameraBuildersTest` (`@RunWith(RobolectricTestRunner::class)`, `@Config(sdk = [34], application = Application::class)`, `T/ui/walk/PilgrimMapCameraBuildersTest.kt:26-28@ca6424db`) proves the maps-initializer classes; the common-initializer classes take the same branch. So U45's `.build()` tests are viable with the same runner and config.

Pure Java, no native call:
- `TileRegionLoadOptions.Builder.build()`, `TilesetDescriptorOptions.Builder.build()`, `StylePackLoadOptions.Builder.build()` (§4, §5);
- `com.mapbox.bindgen.Value` (no static initializer at all), `ExpectedFactory.createValue` / `createError`;
- `com.mapbox.geojson.MultiPolygon`, `Polygon`, `Point`;
- the public constructors of `TileRegion(String, long, long, long, Date, Value)`, `TileRegionError(TileRegionErrorType, String)`, `StylePack(String, GlyphsRasterizationMode, long, long, long, Date, Value)`, `StylePackError(StylePackErrorType, String)`, so the error map, the summary conversion and the pack filter can be tested with real SDK objects.

`TilesetDescriptor` has only `protected TilesetDescriptor(long peer)`; the class is not final, and `setPeer(0L)` returns before registering a native cleaner. A test can therefore pass `object : TilesetDescriptor(0L) {}` into the region builder and assert the list is handed through by identity. Never call its `toValue` (native).

Native, device only: `TileStore.create`, every `TileStore` instance method, `OfflineManager()` (its constructor calls native `initialize()`), `createTilesetDescriptor`, `loadStylePack`, `getAllStylePacks`, `OfflineSwitch`, `MapboxMap.clearData`, and the `MapboxMapsOptions` accessors.

#### 13.2 What the loader exposes for the tests

Internal functions on the production loader (its companion or a file-level `internal object`), each called by the real load path, so the test builds exactly what ships:
- `descriptorOptions(pixelRatio: Float): List<TilesetDescriptorOptions>`;
- `stylePackLoadOptions(): StylePackLoadOptions`;
- `regionLoadOptions(request: TileRegionRequest, descriptors: List<TilesetDescriptor>): TileRegionLoadOptions`;
- `glyphsRasterizationMode: GlyphsRasterizationMode`;
- `mapped(TileRegionError)`, `mapped(StylePackError)`;
- `summary(region: TileRegion, metadata: Expected<TileRegionError, Value>): TileRegionSummary` (the refresh's per-region conversion, including the `""` fallbacks);
- `presentPacks(packs: List<StylePack>): Set<StylePackRequest>` (the completeness filter and the URI match);
- `settled(...)`, `isComplete(...)`.

#### 13.3 The tests U45 writes (`T/data/honor/pilgrimage/MapboxTileRegionLoaderTest.kt`, Robolectric)

1. **Descriptors build** (port of iOS test 3): two options, `styleURI` `[Style.LIGHT, Style.DARK]` in that order; `minZoom` 11 and `maxZoom` 14 for both; `tilesets` `null`; `stylePackOptions` `null`; `pixelRatio` equal to the value passed in.
2. **Style pack options build** (port of iOS test 4, extended): `glyphsRasterizationMode == IDEOGRAPHS_RASTERIZED_LOCALLY`, read from the built object; `acceptExpired` false; `metadata` null. A second assertion that the property derives from `PilgrimageTilesDescriptors`' constant, as iOS's does.
3. **Region options build**: a request with two 5-point rings and a known hash, `acceptExpired = true`. Assert: `geometry` is a `MultiPolygon` with 2 polygons of 1 ring each; ring 0's first point has `longitude()` and `latitude()` equal to the input's (longitude first); `metadata.contents` is a map whose `corridorHash` entry's `contents` is the hash; `acceptExpired` true; `networkRestriction == NONE`; `descriptors` is the passed list (peer-0 stubs). Repeat with `acceptExpired = false`.
4. **Errors** (port of iOS test 5, adapted): `TileRegionError(DISK_FULL)` → disk full; `StylePackError(DISK_FULL)` → disk full; `TILE_COUNT_EXCEEDED` → too many tiles; `TileRegionError(CANCELED)` → cancelled; `StylePackError(CANCELED)` → failed; `OTHER`, `DOES_NOT_EXIST`, `TILESET_DESCRIPTOR` → failed; and `TileRegionError(OTHER, "No space left on device")` → **failed** (iOS's `URLError(.cannotWriteToFile)` row has no Android input; this row pins that the message is not read, §9).
5. **Settled projection** (port of iOS test 6, verbatim fixtures).
6. **Completeness** (port of iOS test 7, verbatim fixtures).
7. **Summary conversion** (Android): a `TileRegion` plus metadata `Value` `{corridorHash: "h"}` gives counts, bytes and `"h"`; a metadata error, a `null` value, a non-map value, and a map without the key each give `""`.
8. **Pack presence** (Android, pins the refresh filter iOS can't unit-test): `StylePack(Style.LIGHT, …, required 10, completed 10, …)` present; `(Style.DARK, required 10, completed 4)` absent; `(0, 0)` absent; an unknown URI ignored.

The real loader (store, offline manager, callbacks, threading) stays device-only, as iOS's does ("no test loads a region or reads the store", PR #86); the engine runs against C2's fake.

---

### 14. The debug harness, and D2's measurement

#### 14.1 Where

`HonorDebugReceiver` runs in the UI process (no `android:process` on its manifest entry, `app/src/debug/AndroidManifest.xml:60-71@ca6424db`), DUMP-protected, addressed with `-p`. Its `runCommand` runs each command on `Dispatchers.Default` under `goAsync()` (`HonorDebugReceiver.kt:445-457@ca6424db`), so the tiles commands must hop to Main for every `@MainThread` call. Add both actions to the receiver's `when` and to its intent filter. A broadcast that cold-starts the app runs `PilgrimApp.onCreate` first, so the token is set. iOS has nothing to match: its only offline debug aid, the "simulate no signal for maps" toggle, was removed by `d331b61` before the pin. These are debug-only Android additions.

#### 14.2 Clear the map cache (`…debug.HONOR_TILES_CLEAR_CACHE`, name is U45's)

Two caches can make a stage render offline without a saved region:
- the map's disk cache, `files/.mapbox/map_data/map_data.db`, filled by online viewing in READ_ONLY mode ("falls back to requesting the individual tile and storing it in the disk cache", 11.20 `MBMTileStoreUsageMode.h:15-21`, SDK);
- the tile store's ambient cache, where a removed region's packs go ("When a tile region is removed associated resources will move to the ambient cache", 24.20 `MBXTileStore.h:86-87`, SDK).

So the command calls both, on Main, and logs both results:
- `MapboxMap.clearData(AsyncOperationResultCallback)`: static (`@JvmStatic` on the companion), delegates to `MapsResourceOptions.clearData`, `@MainThread`; callback `run(Expected<String, None>)`. Clears temporary map data; "does not affect persistent map data like offline style packages" (iOS SDK `MapboxMapsOptions.swift:58-66`); the Android doc adds that it doesn't touch data stored in the tile store (Mapbox research §5).
- `TileStore.create().clearAmbientCache(AmbientCacheClearingCallback)`: callback `run(Expected<CacheClearingError, Long>)`, the bytes cleared. "Ambient cache data is anything not associated with an offline region or a stylepack"; it blocks the store until done. Don't run it during a save.

Clearing only `clearData` (the plan's wording) would leave a deleted route's packs in the ambient cache, and a "Delete, then airplane mode" row could still render. U48 runs this command before every airplane-mode row.

#### 14.3 The tiles report (`…debug.HONOR_TILES_REPORT`)

On Main through the loader's store (or `TileStore.create()`, the same default instance):
- per region: id, `completedResourceCount` / `requiredResourceCount`, `completedResourceSize`, `expires` present or not, and the first 8 characters of its `corridorHash`;
- per style pack: style URI, the two counts, `completedResourceSize`;
- totals: the summed region bytes (what `status`, `footprint` and `calibrate` sum), the summed pack bytes;
- the calibration figure for the installed route (`pilgrimage.tiles.bytesPerPack.<routeId>`, C1/C2) if any;
- the on-disk size of `files/.mapbox/` and of each first-level child (`tile_store`, `map_data`), walked in-process. This is the figure D2 compares against.

Ids, counts and bytes only; never coordinates, as the receiver's KDoc requires (`HonorDebugReceiver.kt:40-46@ca6424db`).

#### 14.4 D2: do summed region bytes double-count shared packs?

What the SDK says a region's figure is: "The cumulative size, in bytes, of all resources (inclusive of tiles) that have been fully downloaded" (24.20 `MBXTileRegion.h:31-35`, SDK; the Android `TileRegion.getCompletedResourceSize()` is the same core field). Each region references every z11 pack its corridor touches, and adjacent stages share packs, while the store keeps a pack once. Inference, not documented: a shared pack is counted in every region that references it, so the sum overstates disk, and `calibrate` (sum ÷ distinct packs) inflates the per-route figure. iOS's device pass fits it (Nakahechi estimate ~8 MB, saved 18 MB, PR #86 body), but the seed itself may simply be low for that route (2.7 MB per cell measured, against the 4 MB seed). Style packs live in the tile store too, so the directory's size includes them.

U48's measurement, on the Nakahechi and on one long route, after the debug clear-cache command so the ambient cache is empty:
1. the estimate on the row before the tap;
2. after the save, the tiles report's summed region bytes, summed pack bytes, and the `tile_store` directory size;
3. cross-check with `adb shell run-as org.walktalkmeditate.pilgrim.debug du -sk files/.mapbox/tile_store files/.mapbox/map_data`.

Verdict: if the summed region bytes exceed (`tile_store` size − pack bytes) by clearly more than the store's own overhead (its two SQLite files), shared packs are double-counted and D2 is filed. If they match within a few percent, D2 is refuted and only the seed's accuracy is in question.

---

### 15. Mapbox versions past 11.23.1 (out of scope; why the choices survive)

- **11.24 (one tile store per process):** `TileStore.create(path)` is no longer supported; only one instance per process. Android uses only `TileStore.create()`, the default instance the map also uses, so nothing changes. (iOS's `shared(for:)` wraps `create(path)` and will need iOS's own migration to `setRootPath` when iOS bumps; that's iOS's, and not a defect at the pin.) 11.24 also sets a default 1 GB ambient-cache quota, which would bound what Delete leaves behind.
- **11.29 (overzoom fixes):** tiles above a pack's max zoom could render blank instead of falling back to cache or network, and a stretched coarser pack tile could win over the ambient cache. On 11.20 and 11.23.1 online z15–16 over a saved corridor may look coarser. Parity-neutral (same core on both); a U48 observation, not a fix.
- **11.32 (`PARTIAL_LOAD`):** a load with failed resources completes as a failure instead of success. When Android bumps, the loader's map must decide where `PARTIAL_LOAD` goes (today it would fall to `failed`), and the partial-success matching in §6.3 changes. Not this stage.

---

### C3 strings

No user-visible string lives in this cluster. The loader's one console line is not shown to anyone and has no Android counterpart (Android has no fallback branch, §2.3):

| String | Where | Android |
|---|---|---|
| `[MapboxTileRegionLoader] Application Support unavailable; saved maps will land in a purgeable directory` | Xcode console only, once per process, when Application Support can't be resolved (`MapboxTileRegionLoader.swift:25@7c200bf`) | none |

The loader's errors become user lines in C2/C4 (`PilgrimageError` → "the download didn't finish", "more map than can be saved at once", the disk-full voices line). The debug harness's log lines (§14) are debug-only, not user-visible, and follow `HonorDebugReceiver`'s logging rules.

---

### C3 test inventory

`UnitTests/Honor/MapboxTileRegionLoaderTests.swift@7c200bf`, 7 tests. None opens a store or loads a region; tests 1 and 2 read process-global state the test host's `AppDelegate` set.

| # | Test (lines) | Asserts | Android |
|---|---|---|---|
| 1 | `testTheStoreLivesUnderApplicationSupport` (13-20) | `storeURL` is under Application Support, not under `/Caches/`, ends in `pilgrimage-tiles`, and is a file URL | **Not ported.** Android has no store path of its own (default store, owner decision). Replaced by the backup-rules assertions (§10.4) and U48's path row (§2.4). |
| 2 | `testTheMapIsWiredToTheStoreTheRegionsAreSavedInto` (26-28) | `MapboxMapsOptions.tileStore` is non-nil | **Not ported.** Android writes no map option (§3.1), and the getter is a native call Robolectric can't run. The proof is U48's airplane-mode row. |
| 3 | `testDescriptorOptionsAreTheTwoStylesAtTheSpecsRangeAndNameNoTileset` (35-43) | style URIs `[light, dark]`; min 11, max 14; `tilesets` nil | Ported as §13.3 test 1, plus `pixelRatio` and `stylePackOptions`. |
| 4 | `testTheGlyphsModeComesFromThePinnedConstant` (47-49) | the mode is `.ideographsRasterizedLocally` | Ported as §13.3 test 2, read from the built options too. |
| 5 | `testMappedErrorsNameAFullDiskAPackCeilingAndACancel` (54-61) | the six mappings below | Ported adapted as §13.3 test 4: Android has no `URLError`/`NSError` input; the ENOSPC-message row pins `failed`. |
| 6 | `testTheSettledProjectionIgnoresCountsAndSizesButNotCompletionOrHash` (67-83) | counts and bytes don't change the projection; completion, hash and presence do | Ported verbatim (§13.3 test 5). |
| 7 | `testIsCompleteMirrorsTileRegionSummarysIsComplete` (88-98) | the pack rule equals `TileRegionSummary.isComplete` on five pairs | Ported verbatim (§13.3 test 6). |

Fixtures to port verbatim:

```swift
        XCTAssertEqual(MapboxTileRegionLoader.mapped(TileRegionError.diskFull("x")), .diskFull)
        XCTAssertEqual(MapboxTileRegionLoader.mapped(StylePackError.diskFull("x")), .diskFull)
        XCTAssertEqual(MapboxTileRegionLoader.mapped(TileRegionError.tileCountExceeded("x")), .tileCountExceeded)
        XCTAssertEqual(MapboxTileRegionLoader.mapped(TileRegionError.canceled("x")), .cancelled)
        XCTAssertEqual(MapboxTileRegionLoader.mapped(URLError(.cannotWriteToFile)), .diskFull)
        XCTAssertEqual(MapboxTileRegionLoader.mapped(NSError(domain: "t", code: 1)), .failed)
```
> UnitTests/Honor/MapboxTileRegionLoaderTests.swift:55-60@7c200bf

Android rows: the first four with `TileRegionError(TileRegionErrorType.X, "x")` / `StylePackError(StylePackErrorType.DISK_FULL, "x")`; the `URLError` row becomes `TileRegionError(OTHER, "No space left on device")` → `failed`; the `NSError` row becomes `TileRegionError(OTHER, "x")` → `failed`; add `StylePackError(CANCELED, "x")` → `failed`.

```swift
        func region(completed: Int, size: Int, hash: String) -> TileRegionSummary {
            TileRegionSummary(id: "r", completedResourceCount: completed, requiredResourceCount: 10,
                              completedResourceSize: size, metadata: ["corridorHash": hash])
        }
        let downloading = region(completed: 3, size: 300, hash: "h")
        let further = region(completed: 7, size: 700, hash: "h")
        let done = region(completed: 10, size: 1_000, hash: "h")
        let redrawn = region(completed: 3, size: 300, hash: "h2")

        XCTAssertNotEqual(downloading, further, "the snapshots differ, so the cache is rewritten")
        XCTAssertEqual(MapboxTileRegionLoader.settled([downloading]), MapboxTileRegionLoader.settled([further]))
        XCTAssertNotEqual(MapboxTileRegionLoader.settled([downloading]), MapboxTileRegionLoader.settled([done]))
        XCTAssertNotEqual(MapboxTileRegionLoader.settled([downloading]), MapboxTileRegionLoader.settled([redrawn]))
        XCTAssertNotEqual(MapboxTileRegionLoader.settled([downloading]), MapboxTileRegionLoader.settled([]),
                          "a region appearing or vanishing is a settled change")
```
> UnitTests/Honor/MapboxTileRegionLoaderTests.swift:68-82@7c200bf

```swift
        XCTAssertTrue(MapboxTileRegionLoader.isComplete(completed: 10, required: 10))
        XCTAssertFalse(MapboxTileRegionLoader.isComplete(completed: 4, required: 10))
        XCTAssertFalse(MapboxTileRegionLoader.isComplete(completed: 0, required: 0))

        for (completed, required) in [(10, 10), (4, 10), (0, 0), (0, 5), (5, 0)] {
            let summary = TileRegionSummary(id: "x", completedResourceCount: completed, requiredResourceCount: required,
                                            completedResourceSize: 0, metadata: [:])
            XCTAssertEqual(MapboxTileRegionLoader.isComplete(completed: completed, required: required), summary.isComplete)
        }
```
> UnitTests/Honor/MapboxTileRegionLoaderTests.swift:89-97@7c200bf

Adapting to Android's input shapes: the loader's request is iOS's `TileRegionRequest` (id, rings, hash, `acceptExpired`), unchanged by the plan's per-stage values (those are the manager's input, C2). The only adaptation is the summary's numeric types (`Long`, §6.3). The Android-only tests are §13.3 tests 3, 7 and 8, plus the backup-rules assertions (§10.4).

Related iOS tests owned elsewhere that exercise this cluster's behavior through the fake: the lifecycle tests on the reconcile riding `refreshRegions` and a packs-only change not running it (C2), and `FakeTileRegionLoader`'s "never answers a refresh inline" (`UnitTests/Honor/FakeTileRegionLoader.swift:80-87@7c200bf`, C2).

---

### C3 corrections to the Android plan

| # | Plan says | iOS / the SDK | Fix |
|---|---|---|---|
| C3-1 | "So does an error whose message passes `WayMediaDownloadWorker.isDiskFull`'s ENOSPC test, applied through a message-taking helper beside it. Mapbox's errors here are plain records with a type and a message, not Throwables, where iOS's are NSErrors. U42 records this as a platform equivalent." (plan:451); also "the error map covers every SDK type and the disk-full message test" (:462) and "the message-based disk-full check" among the gate rows (:659) | iOS's errors here are Swift enums, not Cocoa errors; `isDiskFull` never matches one, whatever its message (§9.1, probed) | Map by type only; no message helper; `TileRegionError(OTHER, "No space left on device")` → `failed`. Drop the gate row. Owner decision O-C3-1 if the message test is wanted on both platforms |
| C3-2 | "`CANCELED` becomes cancelled." (:453) | Only `TileRegionError.canceled` maps to `cancelled`; `StylePackError.canceled` falls through to `failed` (`MapboxTileRegionLoader.swift:278-285@7c200bf`) | `TileRegionErrorType.CANCELED` → cancelled; `StylePackErrorType.CANCELED` → failed. Invisible to the walker (both become `incomplete`), but port it as is |
| C3-3 | "`pixelRatio` set to the screen density, as iOS passes its screen scale. U42 confirms." (:439) | iOS passes no pixel ratio; the SDK's convenience init fills `ScreenShim.scale` (§4.1). It affects raster tiles only; both styles are all-vector here | Confirmed: pass `Resources.getSystem().displayMetrics.density` through the internal builder's parameter. Reword "iOS passes" to "iOS's SDK fills". It changes no download |
| C3-4 | "A failed refresh still drains its waiters." (:445) | Only when its token is still current; a stale failure, like a stale success, drains nothing (`:197-202`, `:221-222`) | State the token condition; port it exactly |
| C3-5 | "The settled projection `(id, isComplete, hash)` fires regions-changed only on a real change, a load success, or a removal." (:446) | Right, and: a removal fires even for an id the cache doesn't hold; a load success fires even when nothing settled changed; a failed load and a failed or stale read fire nothing (§6.7) | Use §6.7's table as the loader's signal contract |
| C3-6 | "A partially loaded pack doesn't count as present." (:447) | True of the refresh's filter. A pack **load success** inserts the pack with no completeness check (`:99-101`), so a partial pack reads present until a current packs answer replaces the set | Port both as shipped; matched as C3-D4 |
| C3-7 | "what counts as the store's first answer: a failed or stale refresh should end the wait too, or a waiter could sit out the whole bound" (:221) | A stale answer writes nothing and drains nothing (§6.4); at cold start the cache isn't a snapshot after one. A failed one leaves the cache empty | A **stale** answer doesn't end the wait; the loader starts a new read while the first answer is pending. A **failed** answer ends it as `FAILED`, which readers treat as "unknown", like the timeout. The wait covers the packs answer too (§7) |
| C3-8 | "keeping the hooks, the reconcile and a save's start in one order on the manager's thread, so a waiting remove's cancel never stops a save that started after it" (:223) | iOS's `remove` cancels first, then reads the cache (`PilgrimageTilesManager.swift:334-340@7c200bf`) | The hook's `cancel()` runs at its turn on Main, before any wait; only its prefix scan awaits `firstAnswer` (§7) |
| C3-9 | "a clear-map-cache command, so a region is proved rather than the ambient cache" (:202, :430) | Two caches can render a stage offline: the map's disk cache and the tile store's ambient cache, where a removed region's packs go (§14.2) | The command calls `MapboxMap.clearData` **and** `TileStore.clearAmbientCache`, on Main, logging both |
| C3-10 | "Building the manager touches no Mapbox class until its first store call." (:156) | iOS's loader builds the store, the offline manager and the descriptors in `init` (`:68-73`) | Extend the rule to the loader: its constructor creates nothing; the Mapbox objects are made on the first call, on Main, and held (§8.2). The package manager gets the tiles manager through a `Provider`/`Lazy` |
| C3-11 | "`OfflineManager` is `@MainThread` throughout." (:138) | Every method is; the constructor isn't annotated but calls native `initialize()` | Construct it on Main too |
| C3-12 | "`TilesetDescriptor` is native-only, so the region-options test builds without real descriptors." (:461); "Natives (`TileStore`, `OfflineManager`) stay device-only." (:456) | A peer-0 subclass of `TilesetDescriptor` is constructible on the JVM; `TileRegion`, `TileRegionError`, `StylePack`, `StylePackError` have public constructors; the common initializer no-ops under Robolectric like the maps one (§13.1) | Test the region builder with peer-0 stubs (identity pass-through), and the error map, the summary conversion and the pack filter with real SDK objects (§13.3) |
| C3-13 | "`T/data/honor/pilgrimage/MapboxTileRegionLoaderTest.kt` (the pure parts of iOS's 7, plus the Robolectric `.build()` tests)" (:432) | iOS tests 1 and 2 read the custom store and the map option, which Android doesn't have | Port 3–7 (5 adapted); tests 1 and 2 are replaced by the backup-rules assertions and U48 rows; add §13.3 tests 3, 7, 8 |
| C3-14 | "The default store is `filesDir/.mapbox/tile_store`, from the SDK's initializer and Mapbox's own instrumentation tests." (:143) | The initializer hands native `filesDir` as the data path; the folder names `.mapbox`, `tile_store`, `map_data` are in the `.so` files (§2.3) | Keep; cite the evidence. Still unconfirmed on a device; U48 checks before the transfer row |
| C3-15 | "Confirm the path: `run-as … ls files/.mapbox/`." (:574) | — | Add the `du -sk` reads for D2 and Delete, the debug clear-cache step before airplane rows, and the one-phone transfer-mode check or an explicit waiver (§10.4, §14) |

---

### C3 notes by unit

**U44 (the seam, the fake, the engine):**
- The seam must carry, beyond iOS's members: `firstAnswer` (§7), with `READ` / `FAILED`. The fake completes it when a test drives the first refresh and can fail it.
- `TileRegionSummary`: `completedResourceCount`, `requiredResourceCount`, `completedResourceSize` as `Long`; `metadata: Map<String, String>`; `isComplete = requiredResourceCount > 0 && completedResourceCount >= requiredResourceCount`; `corridorHash = metadata["corridorHash"]` (nullable, `""` when the store couldn't say).
- `TileRegionLoadingError`: four cases matching iOS's `failed`, `diskFull`, `cancelled`, `tileCountExceeded` (C2 names them).
- The loader's signals: `.regions` and `.packs` exactly per §6.7. The manager's `regionsChanged` fires on `.regions` only.
- `refreshRegions(completion)`: drained on the next current regions answer, success or failure, after the cache write. The reconcile rides on it, not on `firstAnswer`.
- Load completions can arrive after a cancel (a `CANCELED` failure or a racing success). The manager's generation guard handles both; no test may assume either.

**U45 (the loader, the rules, the wiring):**
- File: `P/data/honor/pilgrimage/MapboxTileRegionLoader.kt`. The only file importing `com.mapbox.common.TileStore` / `com.mapbox.maps.OfflineManager`.
- Store: `TileStore.create()`, held strongly. Never `create(path)` or `setRootPath`.
- Offline manager: `OfflineManager()`, on Main, held. Descriptors: `createTilesetDescriptor` ×2 (light, dark), on Main, at first use, held.
- Descriptor options: `styleURI(Style.LIGHT|Style.DARK)`, `minZoom(11)`, `maxZoom(14)` (as `Byte`, from C1's constant), `pixelRatio(density)`, nothing else.
- Pack options: `glyphsRasterizationMode(IDEOGRAPHS_RASTERIZED_LOCALLY)` derived from C1's constant; nothing else. Load with `loadStylePack(uri, options, callback)` (no progress).
- Region options: §5's builder; `loadTileRegion(id, options, progress, callback)` or the three-argument overload.
- Removal: `removeTileRegion(id)` (one argument).
- Refresh: §6.4–6.5, with the metadata fan-in on Main; hash read-back with every failure as `""`.
- Every SDK callback: one `post` to Main; all state on Main; `check` Main at each public entry.
- First answer: §7, including the stale re-issue and the hopped packs failure.
- Error map: by type only (§9.2).
- The map: no `MapboxMapsOptions` write anywhere.
- Rules: the three XML lines (§10.3) and the section-aware test (§10.4). Unflagged.
- Process: loader and manager resolved only in UI launch work and UI screens; constructors touch no Mapbox (§11).
- Launch: flag on, UI process: start the first read (through the reconcile's `refreshRegions` after a clean `runAtLaunch()`, or a plain refresh when the reconcile is skipped).
- Debug: `HONOR_TILES_CLEAR_CACHE` and `HONOR_TILES_REPORT` (names are U45's) on `HonorDebugReceiver`, hopping to Main (§14).
- Tests: §13.3 (eight), the rules test, plus the plan's wiring tests.

**U48 (device rows from this cluster):**
1. Store path: `run-as org.walktalkmeditate.pilgrim.debug ls -la files/.mapbox/` shows `tile_store/` and `map_data/`; nothing Mapbox elsewhere.
2. D2: estimate, tiles report sums, `du -sk files/.mapbox/tile_store` (§14.4), Nakahechi and one long route.
3. Delete: footprint reads "none saved"; `du` before and after shows how much stays (ambient cache, style packs; C3-D6); then the clear-cache command and `du` again.
4. Airplane rows: run the clear-cache command first, every time. Light and dark: each style needs its own pack, so test both appearances with packs saved.
5. Online over a saved corridor at z15–16: note whether the map looks coarser than off-corridor (11.23.1 overzoom, §15). Below z11: a bare line offline.
6. Device transfer: `.mapbox/` stays behind (§10.4), or an explicit waiver.
7. A save started right after a cold start with no map yet shown (telemetry state, §12): note only, for the gate's #92 row.
8. Background starts (WorkManager, the widget) with the flag on open the store through the launch reconcile: cost only; check `adb logcat` for no Mapbox errors in those starts.

---

### C3 Android additions to record at the gate

| # | Addition | Reason |
|---|---|---|
| A-C3-1 | The default tile store (`TileStore.create()`, `files/.mapbox/`) instead of iOS's `Application Support/pilgrimage-tiles` | Owner decision 2026-10-06: one store per process (11.24), no unflaggable `setRootPath`, the map already reads it. Platform equivalent of iOS's dedicated folder |
| A-C3-2 | `.mapbox/` excluded from device transfer and, explicitly, from cloud backup (both rules files), unflagged | Equivalent of the SDK's iCloud exclusion on iOS; Android's transfer otherwise copies `filesDir`. Also stops the ambient map cache travelling |
| A-C3-3 | No `MapboxMapsOptions` written (iOS writes `.readOnly` and `tileStore`) | Both are the defaults on Android; iOS needs them only for its custom store; the setters are deprecated natives |
| A-C3-4 | No `tmp/` fallback for the store | `filesDir` always resolves |
| A-C3-5 | The loader creates its Mapbox objects on first use, on Main, not in its constructor | Keeps `:tracker` and flag-off builds Mapbox-free; `OfflineManager` is `@MainThread` |
| A-C3-6 | A Main-thread check at each loader entry | `@MainThread` misuse is undefined behavior, not an exception; iOS's isolation is compile-time |
| A-C3-7 | `firstAnswer`, with a fresh read on a stale first answer, a failed packs read reported, and a fresh attempt on a call after a failed answer (part of the cold-cache wait) | Android restarts the UI process often; readers must not take an empty pre-answer cache for "nothing saved" (D6) |
| A-C3-8 | `pixelRatio` set explicitly to the screen density | Mirrors the value iOS's SDK fills in; no download changes |
| A-C3-9 | `networkRestriction(NONE)` set explicitly | The builder's default, written so the test pins it; iOS's default is the same |
| A-C3-10 | Debug commands: clear both caches; a tiles report with on-disk sizes | U48's airplane rows and D2's measurement; iOS removed its only offline debug aid before the pin. Debug only |
| A-C3-11 | A Mapbox call that throws (any non-cancellation `Throwable`) is a failed answer, posted, and what it threw goes to the tiles scope's handler, which logs its type: that half of a read; the whole regions read when a metadata read throws partway; a load's `failed`; a removal that does nothing. A store that can't open answers as an empty, unread one: `regions()` `[]`, `hasStylePack` false, a refresh's waiter drained, a load `failed`, a removal nothing, the first answer `FAILED`; each call tries the open again. An entry's failure that repeats the last one the handler heard isn't sent again | Java natives can refuse a call with a throw where iOS's calls can't, and a device whose Mapbox init failed throws an `Error` from the first Mapbox class a call loads. Posted, a waiter is never stranded nor answered before its call returns, and a completion still comes last. A reader never throws into a screen's scope, which has no handler. Logged once, a store failing on every call doesn't flood the log. A refused metadata read fails the read rather than reading that region's hash as `""` (§9.3) |

Not an addition: the error map by type only is parity (§9). If the owner chooses the message test (O-C3-1), it becomes an addition and needs an iOS issue first.

---

### C3 iOS defects (matched as shipped)

| # | Defect | Evidence | What a user sees | Severity |
|---|---|---|---|---|
| C3-D1 | The manager's comment says "a cancelled load never reports back"; the SDK says the completion is "Invoked only once upon success, failure, or cancelation" | `PilgrimageTilesManager.swift:193-194@7c200bf`; SDK `TileStore+MapboxMaps.swift:43-45` | Nothing; the generation guard makes the code safe either way (§8.3) | Comment only |
| C3-D2 | A removal or a load success makes an in-flight read stale without starting a new one, so `refreshRegions` waiters (the launch reconcile) wait for the next reader's refresh | `MapboxTileRegionLoader.swift:140,171,221-222@7c200bf` | A launch sweep that runs later than launch; orphans live a little longer | Low |
| C3-D3 | The loader's `WayMediaDownloader.isDiskFull(error)` clause can't match an SDK error (Swift enums, no underlying error) | `MapboxTileRegionLoader.swift:281@7c200bf`; `WayMediaDownloader.swift:242-248@7c200bf`; probe (§9.1) | A full disk reported by the SDK as `other` reads "the download didn't finish" | Low (dead code; mention only) |
| C3-D4 | A style pack's load success marks it present without the completeness rule the refresh applies | `MapboxTileRegionLoader.swift:99-101` vs `:243-247@7c200bf` | Right after a save whose pack load succeeded with resources missing: status can read "maps saved · N MB" though a style is incomplete, and a save in that window skips the pack. It lasts until a current packs answer replaces the set and a reader re-reads status | Low |
| C3-D5 | A failed packs read is dropped silently on the worker thread | `MapboxTileRegionLoader.swift:241-242@7c200bf` | After such a failure at launch, a fully saved route reads "Save maps for the way · n of n saved" (with D4) until a later read succeeds | Low |
| C3-D6 | Nothing ever removes the style packs or clears the ambient cache; a region's removal moves its packs to the ambient cache, which has no quota on 11.20 / 11.23 | the loader has no `removeStylePack` or `clearAmbientCache` call; `MBXTileStore.h:83-87` (SDK) | After "Delete maps", Settings says "none saved" while the disk is not freed by the same amount (PR #86's device pass says Delete "freed the store"; how that was measured isn't stated). U48 measures | Low/medium (disk) |

Survey candidates and flow items, from this cluster's side:
- **D2** (summed region bytes vs shared packs): not decidable statically. The header defines a region's size as all its fully downloaded resources; shared packs are probably counted per region. U48 measures (§14.4) before filing.
- **D4** ("33 of 33 saved"): confirmed, and C3-D5 is a second path to it (a failed packs read), with C3-D4 its mirror (a partial pack reading present).
- **D5** (style packs never removed): confirmed at the loader (no `removeStylePack` anywhere); filed together with the ambient-cache half as C3-D6.
- **D6** (cold cache): confirmed (`cached = []` until the first current answer). Refinement: a save tapped that early doesn't re-download the bytes of saved stages. With an empty skip snapshot every stage gets a `loadTileRegion`, but the store "loads the missing resources" only, and `acceptExpired: true` refreshes nothing, so the cost is a round trip per stage, not data (inferred from the SDK docs). Its Remove-finds-nothing half stands.
- **D7** ("Tap to save again" refreshes nothing): confirmed at the loader: every region request carries `acceptExpired: true`.
- **A 11.23.1 partial load counted as done:** confirmed at the loader: a success with `completed < required` is reported as success (§6.3). iOS ships 11.20, which behaves the same.
- **Delete leaving bytes in the ambient cache:** confirmed by the SDK header; C3-D6.
- #93 (lockfile drift): already filed; this spec relies on it (§1). #121 item 5 and #122 item 5 are C4's.
- D1, D3, D8 and D9 belong to other clusters (C4, C2, C1/C4, C4); nothing in the loader bears on them.
- Flow items 1 (store and transfer), 2 (threading), 4 (`:tracker`), 7 (partial load), 8 (cold cache) and 9 (Delete and disk) are answered in §2/§10, §8, §11, §6.3, §7 and C3-D6; item 10's D2 half in §14.4. Items 3, 5, 6, 11 are C2's; item 12 is C4's. The residuals on `isDiskFull` (§9, refuted as stated), telemetry (§12), `TilesetDescriptor` (§13.1) and background starts (U48 row 8) are answered here.

---

### C3 proposed owner decisions

| # | Decision | Options | Recommendation |
|---|---|---|---|
| O-C3-1 | The disk-full message test the plan proposes | (a) parity: map by type only, as iOS does in effect; (b) also map an error whose message says ENOSPC to disk full, on Android only | **(a)**. iOS never reads the message (§9.1). If the owner wants (b), file it on iOS first and fold it in on both platforms |
| O-C3-2 | The device-transfer row for `.mapbox/` | (a) a real two-phone transfer; (b) the one-phone local-transport check in device-transfer mode (§10.4); (c) waive, as Stage 21-2's G2 was | **(b)** if the transport works on the owner's phone, else (c) with the XML test as the only proof. This row is the only end-to-end proof that the exclusion keeps hundreds of MB off a new phone |

---

## C4. Surfaces and copy: the route page's maps row, the morning card's line, Settings → Data → Maps, and the flag

| Field | Value |
|---|---|
| iOS pin | `pilgrim-ios` `7c200bf` (v2.0.0). PR #86 merged as `905996e`; `d331b61` (DataCard, the debug toggle removed) |
| Android | `pilgrim-android` `ca6424db` (main) |
| Feeds | U46 (the route page's maps row), U47 (the morning card's line, Settings → Data → Maps), U48 (the surface rows) |
| Lenses | Behavior, UI/visual (every string verbatim), Data, Edge cases |

**iOS read in full at `7c200bf`:** `Pilgrim/Scenes/Honor/PilgrimageMapsRow.swift` (100), `PilgrimageRouteView.swift` (401), `StageMorningCard.swift` (104), `HonorOverviewView.swift` (418), `Pilgrim/Scenes/Settings/OfflineMapsView.swift` (103), `SettingsCards/DataCard.swift` (51), `WaysListView.swift` (114), `Pilgrim/Models/Honor/PilgrimageTilesManager.swift` (389, for what the surfaces read), `MapboxTileRegionLoader.swift` (286, for when the signals fire), and the tests `UnitTests/Honor/PilgrimageMapsRowTests.swift` (47), `OfflineMapsViewModelTests.swift` (63), `WaysListModelTests.swift` (42). **Read in part:** `ActiveWalkView.swift` (1–40, 280–330), `PilgrimageWayImporter.swift` (1–60), `HonorImportReducer.swift` (20–37), `PilgrimagePackageManager.swift` (140–290), `MainCoordinatorView.swift` (180–210), `AppDelegate.swift` (165–195), `Constants.swift` (5–25), `PilgrimageCatalogServiceTests.swift` (412–424), `PilgrimageWayImporterTests.swift` (334–346), `FakeTileRegionLoader.swift` (its counters and seeds). **Diffs:** `git diff 905996e^1 905996e` for every file above plus `HonorWaysSheet.swift`, `PilgrimageCatalogView.swift`, `MainTabView.swift`, `MainCoordinatorView.swift`; `git show d331b61` for `DataCard.swift`. **Intent only:** the slice-three design spec §5 and plan Task 9; PR #86's body (device pass). **Issues:** pilgrim-ios #121 (item 5) and #122 (item 5).

**Android compared at `ca6424db`:** `P/ui/honor/pilgrimage/PilgrimageRouteViewModel.kt`, `PilgrimageRouteScreen.kt`, `StageMorningCard.kt`, `PilgrimageCatalogScreen.kt` / `PilgrimageCatalogViewModel.kt` (the check glyph), `P/ui/honor/HonorOverviewViewModel.kt`, `HonorOverviewScreen.kt`, `HonorWaysSheet.kt` (`DISABLED_ALPHA`), `P/ui/walk/ActiveWalkScreen.kt` (`showStageDay`, `SheetHandoff`, `StageDaySheet`), `HonorWalkViewModel.kt` (which Way "the day" holds), `P/ui/settings/data/DataCard.kt`, `WaysListScreen.kt`, `WaysListViewModel.kt` (`WaysAvailability`, `WaysRowViewModel`), `P/ui/settings/SettingsScreen.kt`, `SettingsAction.kt`, `P/ui/navigation/PilgrimNavHost.kt`, `P/ui/design/PilgrimDetailScaffold.kt`, `P/data/honor/pilgrimage/PilgrimageModels.kt`, `PilgrimagePackageManager.kt` (the seam, the phase), `P/core/flags/ReleaseFlags.kt`, `P/domain/honor/CountText.kt`, `P/ui/theme/Tokens.kt`, `Color.kt`, `Theme.kt`, `app/src/main/res/values/strings.xml`, and the tests `T/ui/honor/pilgrimage/StageMorningCardTest.kt`, `PilgrimageRouteViewModelTest.kt`, `PilgrimageScreensSemanticsTest.kt`, `T/ui/honor/HonorOverviewViewModelTest.kt`, `T/ui/settings/data/WaysListViewModelTest.kt`, `T/data/honor/pilgrimage/PilgrimageWayImporterTest.kt`. The Stage 21-2 spec was read by line range only (§9 seams 6400–6412, the offline note 6030–6042, A-8 6677, correction 11 6604, `isBusy` 5470–5487).

---

### 1. The route page's maps row

#### 1.1 Where it sits and when it's drawn

```swift
    private var header: some View {
        VStack(alignment: .leading, spacing: Constants.UI.Padding.small) {
// …
            downloadButton
            if isInstalled && !stageWays.isEmpty {
                PilgrimageMapsRow(routeId: entry.id, stages: stageWays,
                                  estimateBytes: mapsEstimateBytes, status: mapsStatus, tiles: tiles)
                    .disabled(PilgrimageRouteModel.mapsRowIsHeld(packagePhase: packages.phase))
            }
        }
    }
```
> Pilgrim/Scenes/Honor/PilgrimageRouteView.swift:179-180,196-203@7c200bf

- The row is the last child of the page's first section (`Section { header } footer: { statusFooter }`, :112), directly under the download button, in the header's leading `VStack` with 8 (`Padding.small`) between children.
- Drawn only when this page's entry is the installed route **and** at least one of its stage Ways loaded. Not installed, another route installed, or every stage Way missing: no row.
- The package's own status lines (progress, the page's `failure`, the redraw notice) are the section's **footer**, below the section. The maps row's failure line is inside the row, inside the section, never in that footer.

Android today: the button is the last child of the same column, and the status lines sit outside the section, as on iOS. The row goes after `DownloadButton(...)`, inside the inner `Column`.

```kotlin
    Column(verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs)) {
        HonorSheetSection(header = null) {
            Column(
                modifier = Modifier.padding(vertical = PilgrimSpacing.small),
                verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
            ) {
// …
                DownloadButton(page, busy, onDownload)
            }
        }
        StatusLines(page, phase)
    }
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/honor/pilgrimage/PilgrimageRouteScreen.kt:192-197,209-213@ca6424db

#### 1.2 What the page reads, and what iOS shows before it has

```swift
        .task {
            reload()
            await loadStagesIfNeeded()
        }
```
> Pilgrim/Scenes/Honor/PilgrimageRouteView.swift:145-148@7c200bf

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
// …
    }

    private func refreshMapsStatus() {
        mapsStatus = isInstalled ? tiles.status(for: entry.id, stages: stageWays) : .none
    }
```
> Pilgrim/Scenes/Honor/PilgrimageRouteView.swift:369-377,382-386@7c200bf

```swift
    /// The installed route's stage Ways, read once in `reload()`: the maps
    /// row needs their lines for its estimate and its status.
    @State private var stageWays: [Way] = []
    /// Computed beside `stageWays` rather than in the row's body: the
    /// estimate walks every stage's corridor tile by tile.
    @State private var mapsEstimateBytes = 0
    /// Read beside `mapsEstimateBytes`, never in the row's body: `status`
    /// hashes every stage's corridor and starts a store round trip.
    @State private var mapsStatus: PilgrimageTilesManager.Status = .none
```
> Pilgrim/Scenes/Honor/PilgrimageRouteView.swift:77-85@7c200bf

What iOS does:
- `reload()` is synchronous and runs first in `.task`. Before it runs, `installed` is nil, so `isInstalled` is false and **no row is drawn**. When it has run, the row appears with its estimate and its status already computed, in the same frame.
- The stage list is `0..<route.stageCount` of the **installed** `route.json`, each stage Way read from the store, missing ones skipped (`compactMap`). So `of:` in the status and the estimate cover the stage Ways that loaded, which can be fewer than the route's count.
- The estimate is computed **only in `reload()`**: on opening, and after this page's own install or Remove. It's never recomputed on a store signal, so a calibration written by a save on a live page shows only on the next reload. (The row then shows "maps saved · N MB", which doesn't use the estimate.)
- The status reads the loader's cache. Every `regions()` call starts a store refresh, and the answer is signalled only when what's settled changes:

```swift
    func regions() -> [TileRegionSummary] {
        refresh()
        return cached
    }
```
> Pilgrim/Models/Honor/MapboxTileRegionLoader.swift:157-160@7c200bf

```swift
                let sorted = summaries.sorted { $0.id < $1.id }
                if self.cached != sorted {
                    let settledChanged = Self.settled(self.cached) != Self.settled(sorted)
                    self.cached = sorted
                    if settledChanged { self.onChange?(.regions) }
                }
```
> Pilgrim/Models/Honor/MapboxTileRegionLoader.swift:230-235@7c200bf

So on a warm cache iOS's first row is right. On a cold cache (D6, under a second after launch) it reads `.none`, "Save maps for the way · ~N MB", until the store's first answer fires `.regions` and the page refreshes (§1.6).

**Android (U46).** The plan's rule, "hidden until the first off-Main estimate and status have landed", is a **faithful equivalent** of the synchronous reload: iOS never draws the row without both. Pin the details:
- Publish the row's state as one value (estimate and status together, plus the per-stage values they came from), `null` until both are in for the current installed release. A later status refresh replaces the status in place; the row never hides again while the release is the same.
- The per-stage values (index, rings, corridor hash) are derived from the installed release at each reload, on IO: `0 until installed.route.stageCount`, `wayStore.load(WayStore.stageWayId(entry.id, i))`, skipping a stage that doesn't load, then the Way dropped. The index is `way.stage?.index ?: 0`, iOS's sort key in the save loop (`PilgrimageTilesManager.swift:238`).
- The estimate is computed with the values and not again until the next reload, as iOS.
- With the cold-cache wait (C2), the first status waits for the store. Past the bound, draw iOS's own cold face: the status from the empty cache, `none` ("~N MB"), corrected when the store's answer fires regions-changed. That's exactly what iOS draws in that window.

#### 1.3 The row's states, layout and words

```swift
enum PilgrimageMapsRowModel {

    static func megabytes(_ bytes: Int) -> String {
        "\(max(1, Int((Double(bytes) / 1_000_000).rounded()))) MB"
    }

    static func label(status: PilgrimageTilesManager.Status, estimateBytes: Int) -> String {
        switch status {
        case .partial(let saved, let of): return "Save maps for the way · \(saved) of \(of) saved"
        case .none, .saved: return "Save maps for the way · ~\(megabytes(estimateBytes))"
        }
    }

    static func savedLine(bytes: Int) -> String { "maps saved · \(megabytes(bytes))" }

    /// `done` and `total` both count the style packs ahead of the stages;
    /// the walker only cares about stages, so both sides drop them.
    static func savingLine(done: Int, total: Int) -> String {
        let packs = StylePackRequest.allCases.count
        return "maps · stage \(max(done - packs, 0)) of \(max(total - packs, 0))"
    }
}
```
> Pilgrim/Scenes/Honor/PilgrimageMapsRow.swift:3-24@7c200bf

```swift
    var body: some View {
        VStack(alignment: .leading, spacing: Constants.UI.Padding.xs) {
            switch tiles.phase {
            case .saving(let done, let total):
                HStack(spacing: Constants.UI.Padding.small) {
                    Text(PilgrimageMapsRowModel.savingLine(done: done, total: total))
                        .font(Constants.Typography.caption)
                        .foregroundColor(.fog)
                    Button("cancel") { tiles.cancel() }
                        .font(Constants.Typography.caption)
                        .foregroundColor(.stone)
                }
            case .idle, .failed:
                idleRow
                if case .failed(let error) = tiles.phase {
                    Text(PilgrimageCopy.line(for: error))
                        .font(Constants.Typography.caption)
                        .foregroundColor(.rust)
                }
            }
        }
    }
```
> Pilgrim/Scenes/Honor/PilgrimageMapsRow.swift:42-63@7c200bf

```swift
    @ViewBuilder
    private var idleRow: some View {
        if case .saved(let bytes) = status {
            Button { Task { await save() } } label: {
                HStack(spacing: Constants.UI.Padding.xs) {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 13))
                        .foregroundColor(.moss)
                        .accessibilityHidden(true)
                    Text(PilgrimageMapsRowModel.savedLine(bytes: bytes))
                        .font(Constants.Typography.caption)
                        .foregroundColor(.fog)
                }
            }
            .accessibilityLabel("maps saved, \(PilgrimageMapsRowModel.megabytes(bytes)). Tap to save again")
        } else {
            Button { Task { await save() } } label: {
                Text(PilgrimageMapsRowModel.label(status: status, estimateBytes: estimateBytes))
                    .font(Constants.Typography.button)
                    .foregroundColor(.stone)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(Color.stone.opacity(0.12))
                    .cornerRadius(Constants.UI.CornerRadius.normal)
            }
        }
    }
```
> Pilgrim/Scenes/Honor/PilgrimageMapsRow.swift:65-91@7c200bf

The face is chosen by the **phase first**, then the status:

| Phase | Status | What's drawn |
|---|---|---|
| `saving(done, total)` | any | one line: "maps · stage {max(done−2, 0)} of {max(total−2, 0)}" (caption, fog), 8, then the button "cancel" (caption, stone) |
| `idle` | `saved(bytes)` | a button: the check (13 pt, moss, hidden from VoiceOver), 4, "maps saved · {N MB}" (caption, fog). Not full width. VoiceOver: "maps saved, {N MB}. Tap to save again" |
| `idle` | `partial(s, of: m)` | the full-width button "Save maps for the way · {s} of {m} saved" |
| `idle` | `none` | the full-width button "Save maps for the way · ~{N MB}" from the estimate |
| `failed(e)` | any | the idle face for the status (the saved face included), then 4, then `PilgrimageCopy.line(for: e)` in caption, rust |

- The full-width button: the button face (`Typography.button`) in stone, 12 above and below, on stone at 0.12 opacity, corner 12 (`CornerRadius.normal`). Android has these tokens already: `pilgrimType.button`, `pilgrimColors.stone`, `PilgrimOpacity.LIGHT` (0.12), `PilgrimCornerRadius.normal` (12 dp), `PaddingValues(vertical = 12.dp)`, `PilgrimSpacing.xs` (4) and `small` (8). The colours `moss`, `fog`, `stone` and `rust` are `pilgrimColors` members in both palettes (`Color.kt:22-31,40-49`).

```swift
        public enum Padding {
            public static let xs: CGFloat = 4
            public static let small: CGFloat = 8
            public static let normal: CGFloat = 16
// …
        public enum CornerRadius {
            public static let small: CGFloat = 8
            public static let normal: CGFloat = 12
```
> Pilgrim/Models/Constants.swift:9-12,17-19@7c200bf

```kotlin
object PilgrimSpacing {
    val xs = 4.dp
    val small = 8.dp
    val normal = 16.dp
// …
object PilgrimCornerRadius {
    val small = 8.dp
    val normal = 12.dp
// …
object PilgrimOpacity {
    const val SUBTLE = 0.06f
    const val LIGHT = 0.12f
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/theme/Tokens.kt:8-11,17-19,31-33@ca6424db
- The `−2` is `StylePackRequest.allCases.count` (light and dark). The total counts 2 + stages, so a 33-stage save reads "maps · stage 0 of 33" through both pack loads, then 1…33. Each `max(…, 0)` clamps; with no stages the total reads 0.
- `.saved` can't reach `label` in practice (the saved face takes it), but the model maps it to the estimate form, as `.none`.
- The `failed` face can be the **saved** face: a re-tap of a saved route refused by a walk reads "maps saved · N MB" over "finish your walk first". The plan's U46 "Failed: the error's line in rust underneath" is right; its "Otherwise: the full-width …" must not exclude the saved face from the failed state.
- Numbers are Swift `Int` interpolations: ASCII digits, no grouping. Android: `digits(...)` (`CountText.kt:9`, `Locale.US`).
- `megabytes` is decimal megabytes, `.rounded()` (half away from zero), floor 1: 400 KB reads "1 MB". C1 pins the Kotlin (`roundToLong`/`Math.round`, never `kotlin.math.round`).
- The check is the same glyph and size the catalog's installed badge uses; Android already draws that badge as `Icons.Filled.CheckCircle` at 13 dp:

```kotlin
    /** Moss: installed and current. */
    ON_YOUR_PHONE(Icons.Filled.CheckCircle, R.string.pilgrimage_badge_on_your_phone),
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/honor/pilgrimage/PilgrimageCatalogViewModel.kt:45-46@ca6424db

```kotlin
private val BADGE_SIZE = 13.dp
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/honor/pilgrimage/PilgrimageCatalogScreen.kt:292@ca6424db

**Accessibility (U46).**
- The saved face is one button whose children are replaced by "maps saved, {N MB}. Tap to save again" (role Button). The check has `contentDescription = null`. Follow the house pattern the morning card's button uses: `semantics { contentDescription = label }` on the clickable, `clearAndSetSemantics {}` on the drawn text (`StageMorningCard.kt:166-185`).
- The other faces read their drawn words: the full-width button its label; the saving line as text, then "cancel" as a button; the failure line as text.
- "cancel" has no minimum height on iOS. Android's `TextButton` gets Material's 48 dp touch target anyway; that's the house norm, not a divergence worth recording.

#### 1.4 Held and busy: two different rules

```swift
    /// A save started mid-Update hashes stage lines that are being
    /// rewritten and can re-add a region the update hook removed. Only a
    /// package download holds the row: a save in flight is not one, and
    /// the row's own cancel has to stay reachable.
    static func mapsRowIsHeld(packagePhase: PilgrimagePackageManager.Phase) -> Bool {
        if case .downloading = packagePhase { return true }
        return false
    }
```
> Pilgrim/Scenes/Honor/PilgrimageRouteView.swift:57-64@7c200bf

```swift
    private var isBusy: Bool {
        if case .downloading = packages.phase { return true }
        if case .saving = tiles.phase { return true }
        return false
    }
```
> Pilgrim/Scenes/Honor/PilgrimageRouteView.swift:308-312@7c200bf

```swift
                    // A Remove taken mid-download would be undone by the
                    // commit that lands after it; the manager refuses it too.
                    .disabled(isBusy)
```
> Pilgrim/Scenes/Honor/PilgrimageRouteView.swift:139-141@7c200bf

```swift
                .background(isBusy || (isInstalled && !hasUpdate) ? Color.fog : Color.stone)
                .cornerRadius(Constants.UI.CornerRadius.normal)
        }
        .disabled(isBusy || (isInstalled && !hasUpdate))
```
> Pilgrim/Scenes/Honor/PilgrimageRouteView.swift:219-222@7c200bf

- **`isBusy`** (the package downloading, or a save running, any route's) holds two controls: the download button (fill fog, disabled) and the overflow's Remove. Never the stage rows, the alerts, or the maps row.
- **`mapsRowIsHeld`** holds the maps row only while the package phase is `downloading`. A save doesn't hold it, so "cancel" stays live. `failed` and `idle` don't hold it.
- So the two can't overlap: while a save runs, the download button is held; while a download runs, the row is held.
- iOS gives the held row no look of its own. Every label in it sets an explicit foreground colour, so SwiftUI's disabled state likely leaves it drawn as is (not verified on a device). Android: disable the clicks and mark the semantics disabled; keep the colours. U48 compares with an iPhone if one is at hand.
- The iOS package manager does **not** refuse an operation while a save runs: only the page's `isBusy` holds them. The iOS plan's "since the manager refuses them anyway" (plan Task 9, Step 4) didn't ship. Android's seam's `isSaving` therefore has no iOS caller: the page reads the manager's phase flow directly.

Android today:

```kotlin
    fun isBusy(phase: PilgrimagePackageManager.Phase, held: Boolean): Boolean =
        phase is PilgrimagePackageManager.Phase.Downloading || held
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/honor/pilgrimage/PilgrimageRouteViewModel.kt:148-149@ca6424db

U46:
- `isBusy(phase, tilesPhase, held) = phase is Downloading || tilesPhase is Saving || held`. Used by the button, the overflow, `onDownloadTapped` and `onRemoveTapped`, which already gate on it.
- `mapsRowIsHeld(phase) = phase is Downloading`, the ported test's exact shape. Then, on the page, hold the row also while `page.isHeld`. The page's holds stand in for iOS's synchronous early phase and synchronous reload (A-10 in the Stage 21-2 spec): from an Update tap until the guard's reads finish, Android's phase is still `Idle`, and from the commit until the reload lands the row still carries the old release's stage values. A tap in either window is the save iOS's comment forbids. The opening hold is harmless, since no row is drawn before the first reload. Record it at the gate (§Additions).

#### 1.5 The tap, cancel, and a save that outlives the page

```swift
    private func save() async {
        do {
            try await tiles.save(routeId: routeId, stages: stages)
        } catch {
            // The manager's phase carries the failure; nothing else to keep.
        }
    }
```
> Pilgrim/Scenes/Honor/PilgrimageMapsRow.swift:93-99@7c200bf

```swift
    func save(routeId: String, stages: [Way]) async throws {
        if case .saving = phase { return }
        guard !isWalkActive() else {
            // The row's catch relies on phase carrying every failure; a
            // refusal at the door has to land there like a refusal mid-loop.
            phase = .failed(.walkInProgress)
            throw PilgrimageError.walkInProgress
        }
```
> Pilgrim/Models/Honor/PilgrimageTilesManager.swift:205-212@7c200bf

```swift
        } catch let error as PilgrimageError {
            // A cancel already put the phase back and cleaned up, and a save
            // started since then owns `inFlight`. A genuine failure is shown
            // until the next save or cancel clears it.
            if generation == myGeneration {
                inFlight?.cancel()
                inFlight = nil
                phase = .failed(error)
            }
            throw error
        }
```
> Pilgrim/Models/Honor/PilgrimageTilesManager.swift:256-266@7c200bf

```swift
    func cancel() {
        generation += 1
        inFlight?.cancel()
        inFlight = nil
        if let pending {
            self.pending = nil
            pending.resume(throwing: PilgrimageError.incomplete)
        }
        phase = .idle
    }
```
> Pilgrim/Models/Honor/PilgrimageTilesManager.swift:315-324@7c200bf

- The tap starts an unstructured `Task` on the shared manager and drops the error; the phase carries it. The task isn't tied to the page, so leaving the page never stops the save.
- Both faces' buttons call the same `save()` with the page's `stageWays` as read at the last reload. "Tap to save again" runs the same loop.
- "cancel" calls `tiles.cancel()` directly: the generation moves, the in-flight load is cancelled, and the phase goes to `idle` at once.
- A failure stays drawn until the next save, a cancel, or a `remove` (Remove, Replace, Settings' Delete), which calls `cancel()` first (§3.3). It lives on the shared manager, so a page opened later shows it too.

The row's transitions, as the phase drives them:

| From | Event | To | The row |
|---|---|---|---|
| `idle` / `failed` | tap, a walk on | `failed(walkInProgress)` | the idle face, "finish your walk first" under it; no progress ever drawn |
| `idle` / `failed` | tap, past the door | `saving(0, 2 + n)` | "maps · stage 0 of n", "cancel" |
| `saving` | a pack or a stage done or skipped | `saving(done + 1, total)` | the count moves only once both packs are done |
| `saving` | "cancel", or a `remove` | `idle` | the idle face; the status re-read on `idle` (done regions stay, so typically "s of m saved") |
| `saving` | the last stage done | `idle` | re-read on `idle` and on the regions signals: "maps saved · N MB" once both packs are in |
| `saving` | a walk starts, a full disk, a pack ceiling, a failed load | `failed(e)` | the idle face for the status, `e`'s line under it |
| `failed` | a `remove` | `idle` | the line goes |

A second tap while `saving` returns without a word (`if case .saving = phase { return }`), and the button isn't drawn then anyway.

**Android (U46).** The row's two actions go to the VM: `onSaveMaps()` hands the page's current per-stage values to the manager's own scope and never awaits on the page's behalf. `onCancelMaps()` calls the manager's `cancel()`. `PilgrimageRouteActions` gains both. The one-at-a-time rule, the door's slot claimed before the guard read, and the failure on the phase are U44's (C2).

#### 1.6 When the status is read again

```swift
        .onReceive(tiles.regionsChanged) { _ in refreshMapsStatus() }
        // A save whose regions were all present loads only packs and ends
        // with no regions signal, and a cancel ends with none either.
        .onChange(of: tiles.phase) { _, phase in
            if phase == .idle { refreshMapsStatus() }
        }
```
> Pilgrim/Scenes/Honor/PilgrimageRouteView.swift:149-154@7c200bf

```swift
        loader.onChange = { [weak self] change in
            guard let self else { return }
            self.objectWillChange.send()
            // Only the regions answer speaks for what is on disk; the packs
            // answer is a round trip ahead of it and says nothing about the
            // store's contents.
            if change == .regions {
                self.regionsChanged.send()
            }
        }
```
> Pilgrim/Models/Honor/PilgrimageTilesManager.swift:57-66@7c200bf

```swift
            DispatchQueue.main.async {
                let present = Set(StylePackRequest.allCases.filter { uris.contains(Self.styleURI($0).rawValue) })
                guard let self, token == self.refreshGeneration, self.cachedPacks != present else { return }
                self.cachedPacks = present
                self.onChange?(.packs)
            }
```
> Pilgrim/Models/Honor/MapboxTileRegionLoader.swift:249-254@7c200bf

Three triggers, and only these:
1. **`reload()`** (opening; after this page's install and Remove): re-reads the stage Ways, the estimate and the status.
2. **`regionsChanged`**: a region loaded, removed, or a store answer whose settled projection changed. Progress ticks never fire it. The status is recomputed with the stage Ways the page already holds.
3. **The phase becoming `idle`**: a save that loaded only packs, or a cancel, sends no regions signal, so the page re-reads on idle. `failed` doesn't trigger it; the regions a failed save finished already signalled.

Not a trigger: a packs-only answer. It fires `.packs`, which reaches `objectWillChange` but not `mapsStatus`. So on a cold start, if the regions answer lands before the packs answer, the row reads "Save maps for the way · 33 of 33 saved" (packs missing) until the next reload, though both packs are on the phone (part of D4, §Defects).

SwiftUI compares the phase across renders. A save with every pack and region current never suspends (no load is awaited), so it runs from `saving(0, 35)` to `idle` within one main-actor turn: no saving line is drawn, `onChange` sees `idle` → `idle`, and nothing is refreshed (D7, §Defects).

**Android (U46).**
- The VM collects regions-changed and the tiles phase while it lives. On each regions-changed, and on each change of the phase **to** `Idle`, it recomputes the status from the **latest** per-stage values, off Main.
- **Latest wins.** Run each refresh under `collectLatest`/`mapLatest` or a generation, so a refresh begun with the old release's values can never land after the reload's. In AE10 the Update's posted removals fire regions-changed around the reload. A stale "30 of 33" computed from the old 33 values must never overwrite "29 of 30" (§6).
- `StateFlow` conflates equal values, so a save that ends before the collector runs is seen as `Idle` → `Idle` and refreshes nothing, as iOS. But Android's save suspends at every guard read (`walkActive()` is a Room query, C2), so a fully current re-save shows "maps · stage k of 33" ticking for a moment and then refreshes on `Idle`, where iOS shows nothing. It's a consequence of the suspending guard: record it under that gate row; nothing to build.

#### 1.7 A page opened mid-download (pilgrim-ios #121 item 5)

```swift
        // Reload on both branches: a failed update's rollback removed the
        // package, and only reload() picks that state back up so the screen
        // never keeps showing the pre-rollback "on your phone" state.
        reload()
    }
```
> Pilgrim/Scenes/Honor/PilgrimageRouteView.swift:350-354@7c200bf

The page reloads only on opening and after its **own** install and Remove. A page opened while a download runs never reloads when that download lands; only `tiles.phase` is watched, never `packages.phase`. For the maps row:
- **First download, page reopened:** `isInstalled` was false at opening, so the row stays hidden after the download lands, with "Download" still on the button, until the page is reopened. #121 item 5 names "the next row stays hidden"; the maps row hides with it.
- **Update, page reopened:** the row shows (installed, old release) and is held while the download runs. After the commit it's live again with the **old release's** stage Ways. The Update's `removeRegions` fires `regionsChanged`, and the status is recomputed against the old lines. In AE10 that reads "Save maps for the way · 30 of 33 saved". A tap then saves the old lines: it re-adds regions 30–32, which the installed route doesn't own and the next launch's sweep removes. It skips stage 12, whose stored hash still matches the old line. The row ends at "maps saved · N MB" on that page, while a freshly opened page reads "29 of 30 saved". This is the race `mapsRowIsHeld`'s comment guards against, reached through #121 item 5. Add it to #121 item 5 when the themed issue is filed (§Defects).

Android matches it today and must keep doing so. Its page doesn't reload when the phase ends:

```kotlin
 * The manager's phase is the page's progress from its first frame, so a
 * page opened while any download runs shows it. Nothing reloads when the
 * phase ends: a page opened mid-download keeps what it read on opening
 * after the commit, as iOS's does (pilgrim-ios #121, matched). Every
 * package action runs in the manager's own scope, so leaving the page
 * never cancels one; its outcome lands only on a page still here.
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/honor/pilgrimage/PilgrimageRouteViewModel.kt:221-226@ca6424db

So the per-stage values must be keyed on the page's own `installed` (re-derived in `reload()` only), never on a live read of what's installed. The plan's "keyed on the installed release" means the page's `installed`; U46 states it that way.

---

### 2. The morning card's line

#### 2.1 The two lines and where they sit

```swift
    /// The moment before the day starts is where a walker needs to know
    /// whether the map will be there. Not on the walk screen — the
    /// minimalism rule holds there — but here, with the weather.
    static func mapsLine(saved: Bool) -> String {
        saved ? "maps saved for today" : "no offline maps for today — save on wifi"
    }
```
> Pilgrim/Scenes/Honor/StageMorningCard.swift:21-26@7c200bf

```swift
    /// Nil for a card shown outside a pilgrimage's context; the callers
    /// that have a tiles manager compute it.
    let mapsLine: String?
```
> Pilgrim/Scenes/Honor/StageMorningCard.swift:35-37@7c200bf

```swift
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
```
> Pilgrim/Scenes/Honor/StageMorningCard.swift:55-66@7c200bf

- "maps saved for today", or "no offline maps for today — save on wifi". The dash is U+2014 (checked byte by byte at the pin), with a space either side.
- Caption, fog. The last item of the scrolling column, after the warnings and the weather line, 16 (`Padding.normal`) below whatever precedes it; the button stays pinned under the scroll. `nil` draws nothing, and nothing takes its place.
- Android already has the parameter and its place: `mapsLine?.let { Caption(it) }` after the weather line (`StageMorningCard.kt:163-164`). `StageMorningCardModel` lacks `mapsLine(saved)`; U47 adds it with two strings (Strings table).

#### 2.2 From the overview: every stage, read on load and on the store's signal

```swift
    /// Read once per Way and again on the store's own signal, never in the
    /// sheet's body: `isStageSaved` hashes the corridor and starts a
    /// tile-store round trip.
    @State private var stageMapsSaved = false
```
> Pilgrim/Scenes/Honor/HonorOverviewView.swift:82-85@7c200bf

```swift
        .task(id: way.id) {
            rendering = WayRendering(
                pins: PilgrimMapView.wayPins(for: way, heardVoiceIDs: []),
                bounds: HonorOverviewModel.bounds(of: way),
                state: HonorWayState(way: way)
            )
            refreshMarkPins()
            refreshStageMapsSaved()
        }
        .onReceive(PilgrimageTilesManager.shared.regionsChanged) { _ in refreshStageMapsSaved() }
```
> Pilgrim/Scenes/Honor/HonorOverviewView.swift:168-177@7c200bf

```swift
        .sheet(isPresented: $showMorningCard) {
            if let stage = way.stage {
                StageMorningCard(stage: stage, weather: todayWeather,
                                 mapsLine: StageMorningCardModel.mapsLine(saved: stageMapsSaved),
                                 buttonTitle: "walk") {
```
> Pilgrim/Scenes/Honor/HonorOverviewView.swift:188-192@7c200bf

```swift
    /// Only a stage has a region to look for, and only a stage opens the
    /// morning card, so a shared walk never asks the store.
    private func refreshStageMapsSaved() {
        guard way.isPilgrimageStage else { return }
        stageMapsSaved = PilgrimageTilesManager.shared.isStageSaved(way)
    }
```
> Pilgrim/Scenes/Honor/HonorOverviewView.swift:364-369@7c200bf

```swift
    /// Complete by resource count and loaded for the stage's current line.
    private func isSaved(_ way: Way, region: TileRegionSummary?) -> Bool {
        guard let region, region.isComplete else { return false }
        return region.corridorHash == Self.corridorHash(for: way)
    }

    /// The single-stage entry point, for the morning card.
    func isStageSaved(_ way: Way) -> Bool {
        isSaved(way, region: region(for: way))
    }
```
> Pilgrim/Models/Honor/PilgrimageTilesManager.swift:117-126@7c200bf

What iOS does:
- Only a stage asks the store (`isPilgrimageStage` is `stage != nil`, `Way.swift:217`). An own walk or a shared walk never does.
- The flag is read once per Way in `.task(id:)`, which runs on the overview's appearance, before a Begin tap can open the card. It's read again on every `regionsChanged`, for the overview's life, whether the card is open or not.
- The card reads the overview's state, so an open card's line changes live when the flag does: a save running elsewhere that lands this stage turns "no offline maps…" into "maps saved for today" under the walker's eyes.
- From the overview the line is never nil: `false` (the initial value, or a cold cache) reads "no offline maps for today — save on wifi".
- "Saved" means the region is complete and its stored hash matches this Way's line. **Style packs aren't consulted**, so in D4's state (every region saved, a pack missing) the card says "maps saved for today" (§Defects).

Android today: the overview passes `null`:

```kotlin
    if (showMorningCard && stage != null) {
        StageMorningCard(
            stage = stage,
            weather = overview.todayWeather,
            units = units,
            mapsLine = null,
            action = StageMorningCardAction.WALK,
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/honor/HonorOverviewScreen.kt:339-345@ca6424db

**Android (U47).**
- `HonorOverview` gains the stage's saved flag as `Boolean?`: `null` for a non-stage, while the first read is pending, or past the cold-cache bound ("unknown"). The screen passes `overview.mapsSaved?.let { StageMorningCardModel.mapsLine(resources, it) }`.
- The read is its own coroutine in `load()`, started once the overview is `Ready` and the Way is a stage, so it never waits behind `awaitLastKnownFix()` or the weather fetch (`HonorOverviewViewModel.kt:238-247`). Build the stage's value (index, rings, hash) on IO, then ask the tiles manager.
- Subscribe to regions-changed before the first read, so a change in between isn't lost, and re-read on each signal for the VM's life. A shared walk or an own walk never touches the manager or its `Provider`.
- A morning card restored open after process death (`showMorningCard` is `rememberSaveable(way.id)`, `HonorOverviewScreen.kt:262`) gets its line when the new VM's read lands; it has none until then (Stage 21-2's A-3 covers the restore).

#### 2.3 From "the day": computed once, at the tap

```swift
    /// Computed when the day sheet is asked for, never in its content
    /// closure: that closure re-runs on every 1 Hz tick of the view model
    /// while the sheet is up, and `isStageSaved` hashes the corridor and
    /// starts a store round trip each time — on the walk screen.
    @State private var stageMapsLine: String?
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkView.swift:19-23@7c200bf

```swift
                stageDay: viewModel.way?.stage,
                onOpenStageDay: {
                    showOptions = false
                    stageMapsLine = viewModel.way.map { StageMorningCardModel.mapsLine(saved: PilgrimageTilesManager.shared.isStageSaved($0)) }
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                        showStageDay = true
                    }
                }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkView.swift:297-304@7c200bf

```swift
        .sheet(isPresented: $showStageDay) {
            if let stage = viewModel.way?.stage {
                StageMorningCard(stage: stage, weather: viewModel.weatherSnapshot,
                                 mapsLine: stageMapsLine,
                                 buttonTitle: "close") {
                    showStageDay = false
                }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkView.swift:310-316@7c200bf

What iOS does:
- The line is computed **at the tap**, synchronously, 0.3 s before the sheet opens. It's never recomputed while the sheet is up: no `regionsChanged` subscription on the walk screen.
- Each later tap of "the day" computes it again.
- It reads the Way the walk was given (`viewModel.way`). On a cold cache (D6) it reads "no offline maps for today — save on wifi" for that whole opening.
- Nothing else on the walk screen changes (the comment's minimalism rule).

Android today: `StageDaySheet` passes `null`, opened through the 300 ms `SheetHandoff`, and `showStageDay` survives process death:

```kotlin
    // iOS `showStageDay` (`ActiveWalkView.swift:297-320@7c200bf`): a
    // stage's morning card again, from the options sheet's "the day".
    var showStageDay by rememberSaveable { mutableStateOf(false) }
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/ActiveWalkScreen.kt:448-450@ca6424db

```kotlin
                stageDay = honor?.way?.stage,
                onOpenStageDay = {
                    showOptions = false
                    sheetHandoff.open { showStageDay = true }
                },
            )
        }
        if (showStageDay) {
            val stageDayWeather by viewModel.stageDayWeather.collectAsStateWithLifecycle()
            StageDaySheet(
                honor = honor,
                weather = stageDayWeather,
                units = distanceUnits,
                onClose = { showStageDay = false },
            )
        }
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/ActiveWalkScreen.kt:1103-1118@ca6424db

```kotlin
    stage ?: return
    StageMorningCard(
        stage = stage,
        weather = weather,
        units = units,
        mapsLine = null,
        action = StageMorningCardAction.CLOSE,
        onAction = onClose,
        onDismiss = onClose,
    )
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/ActiveWalkScreen.kt:1453-1462@ca6424db

The Way "the day" reads on Android is `honor.way`. Before Start it's the Way the overview handed over (`stageHandoff.storedWay`, `HonorWalkViewModel.kt:464-467`). After Start it's the session's copy (`wayStore.sessionWay`, `:710-713`), which keeps the stage Way's id and its line, so its region and hash are the same ones iOS's captured `viewModel.way` reads.

**Checking the plan's decided differences against iOS:**
1. **The cold-cache wait** (readers wait, bounded, for the store's first answer): **a faithful equivalent.** In every settled state Android and iOS read the same store answer. Only the race differs: iOS's sub-second D6 window, which Android's frequent UI restarts would turn into a routine false "no offline maps for today". D6 is filed either way.
2. **"Unknown" past the bound draws no line:** **a small deliberate divergence, not an equivalent.** In that state iOS has a defined output: the empty cache's `false`, "no offline maps for today — save on wifi". The overview then corrects itself on `regionsChanged`, as iOS's does. "The day" never corrects (as iOS). Android's choice avoids telling a walker with no signal that their saved maps are missing. Recommended, recorded at the gate, and raised as an owner decision because it's a change of visible output (§Owner decisions, 2).
3. **"The day" computed when the sheet first appears, a restored sheet included, filling in when the store answers:** **equivalent, with one adjustment.** Start the read at the tap, as iOS does (in `onOpenStageDay`, before `sheetHandoff.open`), so on a warm cache the line is ready when the sheet rises 300 ms later. A sheet that appears with no read for this opening (one restored after process death, which iOS can't do; A-3) starts its read on first appearance. Both cases:
   - Hold the result in the VM that owns `honor` (`HonorWalkViewModel`), so a rotation keeps it and process death recomputes it.
   - Read it **once per opening**: no regions-changed subscription, so an open sheet's line never changes after it lands, as iOS's.
   - A new tap resets it and reads again.
   - While pending the card has no maps line. The sheet itself never waits (R16).

---

### 3. Settings → Data → Maps

#### 3.1 The Data card's row

```swift
struct DataCard: View {

    @ObservedObject private var tiles = PilgrimageTilesManager.shared
    @State private var waysDetail: String = ""
    @State private var mapsDetail: String = ""
// …
            NavigationLink {
                WaysListView()
            } label: {
                settingNavRow(label: "Ways", detail: waysDetail)
            }

            NavigationLink {
                OfflineMapsView()
            } label: {
                settingNavRow(label: "Maps", detail: mapsDetail)
            }
        }
        .settingsCard()
        .onAppear {
// …
            reloadMapsDetail()
        }
        // A card drawn before the store answered would say "none saved"
        // until Settings was left and reopened. Regions only: every step of
        // a save publishes too, and this reload decodes every stage Way of
        // the route off disk.
        .onReceive(tiles.regionsChanged) { _ in reloadMapsDetail() }
    }

    private func reloadMapsDetail() {
        mapsDetail = OfflineMapsModel.rowDetail(
            OfflineMapsModel.loadInstalled(packages: PilgrimagePackageManager.shared, tiles: tiles).saved)
    }
}
```
> Pilgrim/Scenes/Settings/SettingsCards/DataCard.swift:3-7,19-32,38-51@7c200bf

- Third row of the Data card, after "Export & Import" and "Ways": label "Maps", detail `mapsDetail`.
- The detail starts as `""`. It's computed on each appearance of the card and on each `regionsChanged`, never on `objectWillChange`, so a save's progress steps don't reload it. It does change once per region a save lands.
- The detail is "none saved" when `loadInstalled(...).saved` is nil, else "{route name} · {N MB}" (§3.2). It's never empty after the first load.
- iOS shows the row always: no flag, and no walk gating. Settings can't be reached during an iOS walk (the walk is a full-screen cover over the tab view; Stage 21-1's shared-walk spec, line 4636).
- `d331b61` removed only the `#if DEBUG` toggle "simulate no signal for maps" from this card. Nothing of it is at the pin.

Android today:

```kotlin
        if (showsWays) {
            SettingNavRow(
                label = stringResource(R.string.settings_data_ways),
                modifier = Modifier.fillMaxWidth(),
                detail = waysTotals?.let { WaysListModel.rowDetail(LocalResources.current, it) }.orEmpty(),
                onClick = { onAction(SettingsAction.OpenWays) },
            )
        }
    }
}
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/DataCard.kt:40-49@ca6424db

```kotlin
    waysRowViewModel: WaysRowViewModel = hiltViewModel(),
) {
    val showsWays by waysRowViewModel.shown.collectAsStateWithLifecycle()
    val waysTotals by waysRowViewModel.totals.collectAsStateWithLifecycle()
    // iOS counts on each appearance of the card; returning from the list re-enters here.
    LaunchedEffect(Unit) { waysRowViewModel.refresh() }
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsScreen.kt:82-87@ca6424db

```kotlin
class WaysAvailability internal constructor(
    val shown: Flow<Boolean>,
    /**
     * What to show before [shown]'s first answer: the release flag, so the
     * Data card's row is there on its first frame, as iOS's unconditional
     * row is, and goes only if a walk turns out to be on.
     */
    val shownAtFirst: Boolean,
) {
    @Inject
    constructor(releaseFlags: ReleaseFlags, walkRepository: WalkRepository, honorDao: HonorDao) : this(
        shown = if (!releaseFlags.honor) {
            flowOf(false)
        } else {
            combine(walkRepository.observeActiveWalk(), honorDao.observeLiveSessionCount()) { active, sessions ->
                active == null && sessions == 0
            }
        },
        shownAtFirst = releaseFlags.honor,
    )
}
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/WaysListViewModel.kt:141-161@ca6424db

**Android (U47).**
- A `MapsRowViewModel` beside `WaysRowViewModel`: `shown`, `detail: StateFlow<OfflineMapsSaved?>` plus a "loaded" state, so the detail is `""` until the first load lands (iOS's `""`), then "none saved" or "{name} · {N MB}". `refresh()` runs from the same `LaunchedEffect(Unit)` as the Ways row (each entry to Settings). It collects regions-changed for its life and reloads on each signal, off Main.
- `DataCard` gains `showsMaps` and the detail, and draws "Maps" right after "Ways", with `SettingsAction.OpenMaps`.
- **Gating:** R21 forbids the row with the flag off. Whether it also follows the Ways row's walk gating is owner decision 1 (§Owner decisions). The recommendation is yes, reusing `WaysAvailability`.
- **The cold store:** the row's first load waits (bounded) for the store's first answer, so it doesn't open on "none saved" right after launch. iOS shows "none saved" in that window and corrects on the signal (its comment above). Past the bound, show iOS's cold face, "none saved", and let regions-changed correct it.

#### 3.2 The model: what's "saved", and what T counts

```swift
    struct Saved: Equatable {
        let routeName: String
        let bytes: Int
        let savedStages: Int
        let totalStages: Int
    }

    static let emptyCaption = "no maps saved"
    static let deleteTitle = "Delete maps?"
    static let deleteMessage = "Removes the saved basemap. The route's stages stay on your phone."

    static func rowDetail(_ saved: Saved?) -> String {
        guard let saved else { return "none saved" }
        return "\(saved.routeName) · \(PilgrimageMapsRowModel.megabytes(saved.bytes))"
    }

    /// Nil when the installed route has no bytes in the store. A partial
    /// save is still bytes on the phone, so it is reported.
    static func load(routeName: String, routeId: String, stages: [Way], tiles: PilgrimageTilesManager) -> Saved? {
        // No stages is no route to report on; the launch reconcile is what
        // clears regions nothing references.
        guard !stages.isEmpty else { return nil }
        let footprint = tiles.footprint(routeId: routeId, stages: stages)
        guard footprint.bytes > 0 else { return nil }
        return Saved(routeName: routeName, bytes: footprint.bytes, savedStages: footprint.savedStages, totalStages: stages.count)
    }

    /// What the Data card and this view both read: the installed route's
    /// stages through the tiles manager.
    static func loadInstalled(packages: PilgrimagePackageManager, tiles: PilgrimageTilesManager) -> (saved: Saved?, routeId: String?) {
        guard let installed = packages.installed() else { return (nil, nil) }
        let stages = (0..<installed.route.stageCount).compactMap {
            packages.store.load(id: WayStore.stageWayId(routeId: installed.routeId, stageIndex: $0))
        }
        return (load(routeName: installed.route.name, routeId: installed.routeId, stages: stages, tiles: tiles), installed.routeId)
    }
```
> Pilgrim/Scenes/Settings/OfflineMapsView.swift:10-45@7c200bf

```swift
    func footprint(routeId: String, stages: [Way]) -> Footprint {
        let byId = regionsById()
        let savedStages = stages.filter { isSaved($0, region: byId[$0.id]) }.count
        let prefix = Self.regionPrefix(routeId: routeId)
        let bytes = byId.values.filter { $0.id.hasPrefix(prefix) }.reduce(0) { $0 + $1.completedResourceSize }
        return Footprint(savedStages: savedStages, bytes: bytes)
    }
```
> Pilgrim/Models/Honor/PilgrimageTilesManager.swift:148-154@7c200bf

- `saved` is nil (so "none saved" and "no maps saved") when nothing is installed, when no stage Way of the installed route loads, or when the route's regions hold 0 completed bytes.
- `bytes` sums `completedResourceSize` over **every** region with the route's prefix: stale ones (after an Update redrew them), and ones at indices the route no longer has, until the launch sweep or the Update hook removes them. So Delete stays reachable whenever bytes are on the phone.
- `savedStages` (S) counts stages complete **and** hash-matching. `totalStages` (T) is `stages.count`: the installed route's stage Ways that **load**, not `route.stageCount`. A missing stage file makes T smaller.
- The route name is the installed `route.json`'s `name`, the same name the Ways footer uses (`WaysListViewModel.kt:238`).
- `loadInstalled` calls `installed()`, which on iOS can finish a Replace a kill interrupted. Android: call `installed()` too (UI process), and read a throw as nothing installed, as `WaysListViewModel.routeNameOrNone()` does. A throw then shows "none saved" and no Delete, never a destructive path.
- The model is `@MainActor`: iOS decodes every stage Way and hashes every corridor on the main thread at each reload, one per `regionsChanged` during a save (D8). Android builds the per-stage values on IO and asks the manager once per load: one store read for the whole route, which the ported test pins (`regionsReadCount == 1`).

#### 3.3 The Maps screen

```swift
/// Settings → Data → Maps. Written for one pilgrimage, because that is
/// all the phone ever holds. It never starts a save: the route page's
/// button is the one door to that tap.
struct OfflineMapsView: View {

    @ObservedObject private var tiles = PilgrimageTilesManager.shared
    @State private var saved: OfflineMapsModel.Saved?
    @State private var routeId: String?
    @State private var confirmDelete = false

    var body: some View {
        List {
            if let saved {
                VStack(alignment: .leading, spacing: 2) {
                    Text(saved.routeName).font(Constants.Typography.body).foregroundColor(.ink)
                    Text("\(PilgrimageMapsRowModel.megabytes(saved.bytes)) · \(saved.savedStages) of \(saved.totalStages) stages")
                        .font(Constants.Typography.caption).foregroundColor(.fog)
                }
                Button("Delete maps", role: .destructive) { confirmDelete = true }
                    .font(Constants.Typography.button)
            } else {
                Text(OfflineMapsModel.emptyCaption).font(Constants.Typography.caption).foregroundColor(.fog)
            }
        }
        .navigationTitle("Maps")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Text("Maps")
                    .font(Constants.Typography.heading)
                    .foregroundColor(.ink)
            }
        }
        .onAppear(perform: reload)
        // A screen opened before the store answered would otherwise say "no
        // maps saved" until it was left and reopened. Regions only, not
        // `objectWillChange`: that also fires on every step of a save, and
        // this reload decodes every stage Way of the route off disk.
        .onReceive(tiles.regionsChanged) { _ in reload() }
        .alert(OfflineMapsModel.deleteTitle, isPresented: $confirmDelete) {
            Button("Delete", role: .destructive) {
                if let routeId { tiles.remove(routeId: routeId) }
                reload()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(OfflineMapsModel.deleteMessage)
        }
    }

    private func reload() {
        let loaded = OfflineMapsModel.loadInstalled(packages: PilgrimagePackageManager.shared, tiles: tiles)
        saved = loaded.saved
        routeId = loaded.routeId
    }
}
```
> Pilgrim/Scenes/Settings/OfflineMapsView.swift:48-103@7c200bf

The layout tree, as shipped:
- **Title** "Maps", inline, drawn in the heading type in ink. Android's `PilgrimDetailScaffold` already draws exactly this (`PilgrimDetailScaffold.kt:53-60`: `pilgrimType.heading`, `pilgrimColors.ink`, centred, parchment). Nothing else is needed for the title. The PR's other title changes ("Choose a way" and "Walk again" in `HonorWaysSheet.swift`, "Pilgrimages" in `PilgrimageCatalogView.swift`, the entry name and "Ways") are the same principal-title move, which Android's `HonorSheetFrame` (heading type) and `PilgrimDetailScaffold` already draw. The `MainTabView` tint move is iOS-only theming (system back buttons in sheets); Android sets its colours explicitly. None of the four needs Android work.
- **Saved** (`saved` non-nil, partial and stale included), two rows in one list:
  1. The route name (body, ink), 2 apart from "{N MB} · {S} of {T} stages" (caption, fog). "stages" is never singular: "1 of 1 stages" (§Defects).
  2. "Delete maps" (button face, destructive).
- **Empty**: one caption, "no maps saved" (fog), in the row where the Ways list puts "no ways yet". No Delete, and no link to the route page.
- **Delete** opens "Delete maps?" with the message "Removes the saved basemap. The route's stages stay on your phone." and two buttons, "Delete" (destructive) and "Cancel" (cancel, the stone tint).
- "Delete" calls `tiles.remove(routeId:)` for the route the last reload read, then reloads at once.
- **Reloads** on every appearance and on every `regionsChanged`. During a save running elsewhere, the bytes and S tick up one region at a time.
- It **never starts a save**.

What Delete does (C2 owns the engine; these are the parts the screen depends on):

```swift
    func remove(routeId: String) {
        cancel()
        let prefix = Self.regionPrefix(routeId: routeId)
        for region in loader.regions() where region.id.hasPrefix(prefix) {
            loader.removeRegion(id: region.id)
        }
    }
```
> Pilgrim/Models/Honor/PilgrimageTilesManager.swift:334-340@7c200bf

```swift
    func removeRegion(id: String) {
        tileStore.removeTileRegion(forId: id)
        // A refresh already in flight is older than this removal; its
        // snapshot would put the region back.
        refreshGeneration += 1
        cached.removeAll { $0.id == id }
        onChange?(.regions)
    }
```
> Pilgrim/Models/Honor/MapboxTileRegionLoader.swift:167-174@7c200bf

- Delete **cancels a running save** first (`cancel()`, phase `idle`), wherever it was started. The plan's U47 doesn't say so; it must (§Corrections).
- Every region with the prefix goes, stale ones included. The style packs stay (D5, C2's).
- On iOS the cache drops each region synchronously and fires `.regions` per region, so the screen's own `reload()` right after `remove` already reads "no maps saved", and each signal reloads it again.
- On Android `remove` posts to the manager's thread and returns before anything is removed (plan, Key Technical Decisions). An immediate reload would read the old figure, so the screen's real reload is the one regions-changed triggers. Keep the immediate reload too only if the manager's posted removal is guaranteed to run first; otherwise drop it. Either way the visible end state is iOS's.
- The iOS device pass says "Delete confirmed and freed the store". Mapbox 11.23.1 on Android may keep removed tiles in the ambient cache (flow-analysis item 9). Settings reads "no maps saved" regardless: U48 measures the directory.

**Android (U47).**
- `OfflineMapsScreen` on `PilgrimDetailScaffold(title = "Maps")`, laid out like `WaysListContent`: one `settingsCard` column, the info row, then a `SettingsDivider`, then a "Delete maps" row (`pilgrimType.button`, `MaterialTheme.colorScheme.error`, which is rust; `heightIn(min = 48.dp)`, `Role.Button`).
- The empty caption gets the Ways list's `padding(vertical = 12.dp)`.
- The dialog follows the Ways list's: title, text, "Delete" in `colorScheme.error`, "Cancel" in the default `TextButton` colour (`primary`, stone; `Theme.kt:77,85`).
- The confirmation flag is `rememberSaveable`, as the Ways list's is.
- A `Loading` state draws nothing until the first load lands, so the screen never opens on "no maps saved" right after launch. Past the cold-cache bound, iOS's cold face ("no maps saved") is drawn and corrected on regions-changed.
- The route: `Routes.OFFLINE_MAPS = "offline_maps"` and `SettingsAction.OpenMaps`, handled in `handleSettingsAction` with `launchSingleTop`, as `OpenWays` is (`PilgrimNavHost.kt:1213-1214`).
- Register the destination only with the flag on. The nav host already gates Honor destinations that way:

```kotlin
internal fun androidx.navigation.NavGraphBuilder.honorRoutes(navController: NavHostController, honorEnabled: Boolean) {
    if (!honorEnabled) return
```
> app/src/main/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHost.kt:1033-1034@ca6424db

- If the row follows `WaysAvailability` (owner decision 1), the screen also leaves on its own when `shown` turns false, as `WaysListScreen` does (`WaysListScreen.kt:64-69`), popped by name (`PilgrimNavHost.kt:378-383`).

---

### 4. The failure lines the row can show

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

The tiles manager's phase can carry four of the six (C2 pins the mapping): `walkInProgress` ("finish your walk first"), `incomplete` ("the download didn't finish"), `diskFull` ("not enough space on this phone to save these voices", pilgrim-ios #122 item 5), and `mapTooLarge` ("more map than can be saved at once"). Android has every one already, with `DISK_FULL` mapped to the share importer's line:

```kotlin
object PilgrimageCopy {
    @StringRes
    fun line(error: PilgrimageError): Int = when (error) {
        PilgrimageError.NOT_WALKABLE -> R.string.pilgrimage_error_not_walkable
        PilgrimageError.INCOMPLETE -> R.string.pilgrimage_error_incomplete
        PilgrimageError.DISK_FULL -> R.string.honor_import_disk_full
        PilgrimageError.WALK_IN_PROGRESS -> R.string.pilgrimage_error_walk_in_progress
        PilgrimageError.CATALOG_UNREACHABLE -> R.string.pilgrimage_error_catalog_unreachable
        PilgrimageError.MAP_TOO_LARGE -> R.string.pilgrimage_error_map_too_large
    }
}
```
> app/src/main/java/org/walktalkmeditate/pilgrim/data/honor/pilgrimage/PilgrimageModels.kt:27-37@ca6424db

U46 adds no failure string. The plan's enum names (`DISK_FULL`, `MAP_TOO_LARGE`, `INCOMPLETE`, `WALK_IN_PROGRESS`) are the existing ones.

---

### 5. The flag (R21) and the processes

- **The route row:** reachable only through the Honor sheets, which exist only with the flag on (`honorRoutes` returns at once with it off, `PilgrimNavHost.kt:1033-1034`). No extra check is needed on the row. The VM must still never resolve the tiles `Provider` with the flag off. With it on, every page resolves it at its opening; a page that isn't installed follows only its phase, so another route's save holds its button and overflow (§1.4's `isBusy`), and reads no store.
- **The morning card:** only a stage opens it, and stages need the flag. The overview resolves the manager only for a stage Way. "The day" resolves it only for a Way with a stage block.
- **Settings:** `showsMaps` is false with the flag off (from `WaysAvailability`, whose `shown` is `flowOf(false)` then, or from `ReleaseFlags.honor` if the owner keeps the row ungated by walks). With it false, `MapsRowViewModel` never resolves the tiles `Provider` either, so a flag-off build touches no tiles code from Settings.
- **The destination:** `Routes.OFFLINE_MAPS` is registered only with the flag on (§3.3).
- Every surface here lives in the UI process. None of them is built in `:tracker`, so none needs a process check of its own beyond C2's/C3's `Provider` rule.

---

### 6. AE10 from the surfaces: which read produces each line

All 33 stages saved. The walker taps "Update" on the route page. The new release redraws stage 12 and has 30 stages.

**iOS, the route row: "Save maps for the way · 29 of 30 saved".**

```swift
    func update(entry: PilgrimageCatalogEntry, release: String) async throws {
        guard !isWalkActive() else { throw PilgrimageError.walkInProgress }
        let previousStageCount = installedStageCount(for: entry.id)
        try await download(entry: entry, release: release)
        guard let fresh = installed(), fresh.routeId == entry.id else { throw PilgrimageError.incomplete }
// …
        tiles?.removeRegions(routeId: entry.id, atOrAbove: fresh.route.stageCount)
```
> Pilgrim/Models/Honor/PilgrimagePackageManager.swift:255-259,268@7c200bf

1. The tap runs `install()` → `packages.update(...)`. `download` sets `downloading` at once, so the row is held and `isBusy` holds the button and Remove. It sets `idle` after the commit (`PilgrimagePackageManager.swift:157,199`).
2. Still on the main actor, `update` calls `removeRegions(atOrAbove: 30)`. Each `removeRegion` fires `.regions` synchronously, so the page recomputes the status with its **old** 33 stage Ways: 30 saved, "30 of 33". That value is never drawn: no suspension separates it from step 3.
3. `update` returns, and `install()` calls `reload()` (§1.7's quote). This re-reads the 30 new stage Ways, recomputes the estimate, and reads the status: stages 0–29 have complete regions; stage 12's stored hash is the old line's, and its Way is the new line. So 29 saved; both packs present → `.partial(29, of: 30)` → **"Save maps for the way · 29 of 30 saved"**. This read is the one drawn.

**iOS, stage 12's morning card: "no offline maps for today — save on wifi".** The walker taps stage 12. `open(index: 12)` loads the new stage Way from the store and opens the overview. Its `.task(id: way.id)` runs `refreshStageMapsSaved()`: region 12 is complete, its hash ≠ `corridorHash(new Way)` → `false` → the card's line. If "the day" is opened on that walk, the tap computes the same `false` from `viewModel.way`, the Way Begin handed over.

**iOS, Settings:** the Data card reads "{route} · {N MB}", where N counts regions 0–29 (stage 12's stale region included). The Maps screen reads "{N MB} · 29 of 30 stages".

**Android must produce the same from these reads:**
- **Route row:** the reload after the Update (`install()` → `reload()`, `PilgrimageRouteViewModel.kt:365-372`) changes `page.installed`. The per-stage values are re-derived from the 30 new stage Ways on IO, then the estimate and the status: `partial(29, of 30)` → "Save maps for the way · 29 of 30 saved". The page holds the row from the tap until this reload has landed (§1.4). Any regions-changed refresh in between must not overwrite it with an "of 33" value (latest wins, §1.6). The Update's removals are posted, so their regions-changed may land after the reload; the refresh they trigger uses the new values and gives the same answer.
- **Stage 12's card:** the overview VM's load-time read over the stored new Way: `false` → "no offline maps for today — save on wifi". Not "unknown": by then the store has answered on any warm process. On a cold one the wait covers it.
- **Settings:** the footprint over the prefix, once the posted removals of 30–32 have landed.
- **Test (U46, U47):** drive it through the real package manager with the fake tiles manager C2 builds, or with a fake manager whose regions are seeded as above. Assert the row's text after the reload, and that no stale "30 of 33" can land after it (a delayed regions-changed refresh started before the reload).

---

### 7. The overview's offline note: unchanged (D1)

```swift
    /// Offline tiles are slice three. Until then a stage walked without a
    /// connection draws over the basemap's empty grey, and the overview says
    /// so once — the ghost line, the pins, the marks, the cards, the water,
    /// and the ledger all work without a network.
    static func offlineNote(isStage: Bool, isConnected: Bool, alreadyShown: Bool) -> String? {
        guard isStage, !isConnected, !alreadyShown else { return nil }
        return "map tiles need a connection; the way itself is on your phone."
    }
```
> Pilgrim/Scenes/Honor/HonorOverviewView.swift:44-51@7c200bf

PR #86 left this untouched. Its comment still says "Offline tiles are slice three". It never asks the tiles manager, so a stage whose maps are saved, opened offline for the first time, still says "map tiles need a connection; the way itself is on your phone." (once ever). Android has the same note and must leave it as is: `honor_overview_offline_note` (`strings.xml:1490`) and `sayOfflineNoteOnce()` (`HonorOverviewViewModel.kt:264-277`). So the overview can say "map tiles need a connection…" while the morning card it opens says "maps saved for today".

---

### Strings

Every string is an English Swift literal at the pin; `Localizable.strings` carries none of them (`grep -c maps` on it gives 0). `·` is U+00B7 and `—` is U+2014, both checked byte by byte in the Swift; the apostrophe in "route's" is ASCII. Numbers are Swift `Int` interpolations: ASCII digits, no grouping, so Android passes `digits(n)` (`CountText.kt:9`). `{N MB}` is `megabytes(bytes)`: decimal megabytes, half away from zero, floor 1 (C1). No string here has a singular form; "stages" stays plural at 1, so the house's `tools:ignore="PluralsCandidate"` applies.

| # | Verbatim | Where and when | Arguments | Android at `ca6424db` | Added by (proposed name) |
|---|---|---|---|---|---|
| 1 | `Save maps for the way · ~{N MB}` | route row, idle or failed, status `none` (and `saved`, unreachable there) | the estimate's `{N MB}` | absent | U46 `pilgrimage_maps_save_estimate` = `Save maps for the way · ~%1$s` |
| 2 | `Save maps for the way · {s} of {m} saved` | route row, idle or failed, status `partial` | s, m: digits | absent | U46 `pilgrimage_maps_save_partial` = `Save maps for the way · %1$s of %2$s saved` |
| 3 | `maps saved · {N MB}` | route row, idle or failed, status `saved` (beside the moss check) | the saved bytes' `{N MB}` | absent | U46 `pilgrimage_maps_saved` = `maps saved · %1$s` |
| 4 | `maps saved, {N MB}. Tap to save again` | TalkBack label of #3's button | `{N MB}` | absent | U46 `pilgrimage_maps_saved_a11y` = `maps saved, %1$s. Tap to save again` |
| 5 | `maps · stage {d} of {n}` | route row while saving | d = max(done−2, 0), n = max(total−2, 0): digits | absent | U46 `pilgrimage_maps_saving` = `maps · stage %1$s of %2$s` |
| 6 | `cancel` | the button beside #5 | none | absent | U46 `pilgrimage_maps_cancel` |
| 7 | `{n} MB` | inside #1, #3, #4, #13, #15 | n: digits | `settings_ways_megabytes` has the same template, for the Ways list's own `%.1f` | U46 `pilgrimage_maps_megabytes` = `%1$s MB` (iOS has two separate literals; keep two names) |
| 8 | `finish your walk first` / `the download didn't finish` / `not enough space on this phone to save these voices` / `more map than can be saved at once` | under the route row, rust, phase `failed` | none | present (below) | nothing |
| 9 | `maps saved for today` | morning card, last line of the scroll, caption fog | none | absent | U47 `pilgrimage_morning_maps_saved` |
| 10 | `no offline maps for today — save on wifi` | the same, when not saved | none | absent | U47 `pilgrimage_morning_maps_unsaved` |
| 11 | `Maps` | Data card row label, after "Ways" | none | absent | U47 `settings_data_maps` |
| 12 | `none saved` | Data card row detail, nothing saved | none | absent | U47 `settings_maps_none_saved` |
| 13 | `{route name} · {N MB}` | Data card row detail, something saved | the installed `route.json` name; the footprint's `{N MB}` | `settings_ways_detail` has the same template | U47 `settings_maps_detail` = `%1$s · %2$s` |
| 14 | `Maps` | the Maps screen's title (heading, ink) | none | absent | U47 `settings_maps_title` |
| 15 | `{N MB} · {S} of {T} stages` | Maps screen, under the route name, caption fog | footprint `{N MB}`; S, T: digits | absent | U47 `settings_maps_saved_line` = `%1$s · %2$s of %3$s stages` |
| 16 | `no maps saved` | Maps screen, empty, caption fog | none | absent | U47 `settings_maps_empty` |
| 17 | `Delete maps` | Maps screen's destructive row (button face) | none | absent | U47 `settings_maps_delete` |
| 18 | `Delete maps?` | the confirmation's title | none | absent | U47 `settings_maps_delete_title` |
| 19 | `Removes the saved basemap. The route's stages stay on your phone.` | the confirmation's message | none | absent | U47 `settings_maps_delete_message` (`route\'s` escaped) |
| 20 | `Delete` | the confirmation's destructive button | none | `settings_ways_delete_all_confirm` has the word | U47 `settings_maps_delete_confirm` |
| 21 | `Cancel` | the confirmation's cancel button | none | `settings_ways_delete_all_cancel` has the word | U47 `settings_maps_delete_cancel` |
| 22 | `map tiles need a connection; the way itself is on your phone.` | the overview, once ever, offline (unchanged, D1) | none | present | nothing |

The existing failure lines and the note:

```xml
    <string name="honor_import_disk_full">not enough space on this phone to save these voices</string>
```
> app/src/main/res/values/strings.xml:1284@ca6424db

```xml
    <string name="pilgrimage_error_incomplete">the download didn\'t finish</string>
    <string name="pilgrimage_error_walk_in_progress">finish your walk first</string>
    <string name="pilgrimage_error_catalog_unreachable">the routes are out of reach right now</string>
    <string name="pilgrimage_error_map_too_large">more map than can be saved at once</string>
```
> app/src/main/res/values/strings.xml:1425-1428@ca6424db

```xml
    <string name="honor_overview_offline_note">map tiles need a connection; the way itself is on your phone.</string>
```
> app/src/main/res/values/strings.xml:1490@ca6424db

Put the new route-row strings after the U37 block (`strings.xml:1444-1482`) and the morning card's after `pilgrimage_morning_weather` (`:1498`), each block under a dated "Phase 21 U46/U47" comment naming its Swift source, as the file does. The Settings strings go after the Ways block (`:1296-1317`).

---

### Test inventory

| # | iOS test | File:lines @7c200bf | What it asserts | Android port |
|---|---|---|---|---|
| 1 | `testTheRowsBodyReadsNoStore` | `UnitTests/Honor/PilgrimageMapsRowTests.swift:11-20` | rendering the row's body for `.none`, `.partial(1, of: 3)`, `.saved(100)` reads the store 0 times (`regionsReadCount == 0`) | U46, a Robolectric Compose test (not the model test): render `PilgrimageMapsRow` in the three statuses against a manager on C2's fake; its read count stays 0 |
| 2 | `testTheEstimateIsRoundedAndTilded` | `:22-26` | `.none` with 26,400,000 → "Save maps for the way · ~26 MB"; 1,900,000 → "~2 MB"; 400,000 → "~1 MB" | U46 model test, fixtures verbatim |
| 3 | `testAPartialSaveSaysHowFarItGot` | `:28-31` | `.partial(12, of: 33)`, estimate 0 → "Save maps for the way · 12 of 33 saved" | U46 model test |
| 4 | `testSavedShowsRealBytesWithNoTilde` | `:33-35` | `savedLine(26,100,000)` → "maps saved · 26 MB" | U46 model test |
| 5 | `testSavingCountsStagesNotPacks` | `:37-41` | `savingLine(14, 35)` → "maps · stage 12 of 33"; `(0, 35)` → "maps · stage 0 of 33" | U46 model test |
| 6 | `testTheMorningCardSaysWhetherTodayIsSaved` | `:43-46` | `mapsLine(true)` → "maps saved for today"; `false` → "no offline maps for today — save on wifi" | U47 (`StageMorningCardModel`) |
| 7 | `testTheRowSaysNoneSavedOrTheRouteAndItsBytes` | `UnitTests/Honor/OfflineMapsViewModelTests.swift:8-12` | `rowDetail(nil)` → "none saved"; `Saved("Camino de Santiago (Frances)", 26,100,000, 33, 33)` → "Camino de Santiago (Frances) · 26 MB" | U47 |
| 8 | `testTheCopyIsTheSpecs` | `:14-18` | "no maps saved", "Delete maps?", "Removes the saved basemap. The route's stages stay on your phone." | U47 (resources) |
| 9 | `testLoadReadsTheInstalledRouteThroughTheTilesManager` | `:36-47` | stage 0 seeded complete with its own hash and 5,000,000 bytes → `Saved(name, 5,000,000, 1, 3)`; one store read for the route; no stages → nil | U47, adapted: the input is three per-stage values built by U43's corridor and hash from the fixture lines, and the read count is the fake's |
| 10 | `testStaleRegionsStillCountTowardBytesSoDeleteIsReachable` | `:49-62` | stage 0 seeded with hash `"stale"`, 5,000,000 bytes → `Saved(name, 5,000,000, 0, 3)` | U47, adapted as #9 |
| 11 | `testTheMapsRowIsHeldOnlyWhileThePackageDownloads` | `UnitTests/Honor/PilgrimageCatalogServiceTests.swift:415-423` (class `PilgrimageCatalogModelTests`) | `downloading(1, 34)` → held; `idle` → not; `failed(.incomplete)` → not | U46, in `PilgrimageRouteViewModelTest` beside `busy is any download running, or this page's own hold` |
| — | `testEveryErrorHasItsOwnLine` (+1 line, `mapTooLarge`) | `UnitTests/Honor/PilgrimageWayImporterTests.swift:342` | "more map than can be saved at once" | **already ported** (`PilgrimageWayImporterTest.kt:328@ca6424db`) |
| — | `WaysListModelTests` (3) | `UnitTests/Honor/WaysListModelTests.swift:15-41` | `listable`, `rowDetail`, `packageFooter` | **already ported in 21-2**: `WaysListViewModelTest.kt:156` (listable), `:165` (row detail), `:184` (footer) |

Fixtures for #9 and #10 (`OfflineMapsViewModelTests.swift:20-30`): three stages of `camino-frances`, ids `pilgrimage:camino-frances:{i}`, each Way's route `(42, i·0.05, t 0)` → `(42, i·0.05 + 0.01, t 60)`, stage `index i, count 3`. The fake's `seed(id:corridorHash:complete:bytes:)` defaults to complete with 100,000 bytes. The route name in both is `Camino de Santiago (Frances)`.

The survey's "`PilgrimageCatalogServiceTests` (+1 assertion inside an existing test)" for `mapTooLarge` is #— above, in `PilgrimageWayImporterTests`; the catalog-service file's +10 lines are test #11.

Android tests beyond the ports (U46, U47): AE10 (§6); latest-wins (a delayed refresh begun before the reload never lands after it); the row held through an install's hold; no row before the first estimate and status; past the bound, the cold face; the saved button's TalkBack label; a save outliving the page (leave, return, live progress; done off-page reads "maps saved"); the overview's line changing live on regions-changed; "the day" read once per opening, restored sheet included, never changing while open; Delete cancelling a running save; Delete's confirm and cancel; the Data card's detail `""` until first load; the flag-off Data card with no Maps row and no destination. Existing tests to update for new signatures: `PilgrimageRouteViewModelTest` (`isBusy`), `PilgrimageScreensSemanticsTest` (`PilgrimageRouteContent`), `StageMorningCardTest` (`StageDaySheet`'s new `mapsLine`, plus the line's place after the weather line).

---

### Corrections to the Android plan

1. **U46 test, "`megabytes` gives "1 MB" for 400 KB and rounds 2.5 MB up".** iOS has no 2.5 MB case. Its fixtures are 26,400,000 → "~26 MB", 1,900,000 → "~2 MB", 400,000 → "~1 MB" (label) and 26,100,000 → "26 MB" (saved line). **Fix:** port those verbatim. A 2,500,000 → "3 MB" case is an Android addition that pins half-away-from-zero (C1).
2. **U46 test file, "`PilgrimageMapsRowModelTest.kt` (ported …, 5 of its 6)".** `testTheRowsBodyReadsNoStore` renders the row's body; it isn't a model test. **Fix:** four model tests in the model test file, and the body test as a Robolectric Compose test with the fake's read count (inventory #1).
3. **U46, "Disabled only while a package download runs."** That's `mapsRowIsHeld`, and the ported test pins it. On Android the page's holds cover the window from an install tap to the phase going `Downloading`, and from the commit to the reload: iOS's synchronous early phase and reload. **Fix:** hold the row while `phase is Downloading || page.isHeld`, and record it at the gate (§1.4).
4. **U46, "Otherwise: the full-width …" and "Failed: the error's line in rust underneath".** **Fix:** the failed face is the idle face for the current status, the saved face included, with the line under it (§1.3).
5. **U46, "refreshing on regions-changed and on the phase returning to idle".** It's missing two rules. **Fix:**
   - each refresh uses the latest per-stage values and the latest result wins, or AE10 can show "30 of 33";
   - the values are keyed on the page's own `installed`, re-derived only in `reload()`, so #121 item 5 stays matched (§1.6, §1.7).
6. **U46 test, "a refused save shows "finish your walk first" until the next tap".** **Fix:** until the next save starts, or a cancel or `remove` (Remove, Replace, Settings' Delete). It shows on any route page opened meanwhile, since the phase is the manager's (§1.5).
7. **U46, "stays hidden until the first off-Main estimate and status have landed".** Correct, and a faithful equivalent. **Add:** past the cold-cache bound the row draws iOS's cold face (`none`, "~N MB") and corrects on regions-changed. Also: "has stage Ways" means the stage Ways that load. A missing one is skipped, and `of:` counts the loaded ones.
8. **U47, "pass the line; "the day" computes it once at the tap"** (Files) against Approach, **"read once, when the sheet first appears"**. They disagree. **Fix:**
   - start the read at the tap, before the 300 ms handoff, as iOS does;
   - a sheet that appears with no read for its opening (restored after process death) starts one;
   - once per opening, with no regions-changed subscription (§2.3).
9. **U47, the Maps screen's Delete.** The plan doesn't say Delete cancels a running save. iOS's `remove` calls `cancel()` first. **Fix:** state it and test it. A save started on the route page and still running is cancelled by Settings' Delete, and its phase goes to idle.
10. **U47, "when saved: the route name, "N MB · S of T stages", and "Delete maps"".** **Fix:**
    - "saved" means the route's regions hold more than 0 bytes, partial and stale included;
    - T is the number of stage Ways that load, not `route.stageCount`;
    - "stages" is always plural (§3.2).
11. **U47 Files.** **Add:**
    - `P/ui/settings/SettingsAction.kt` (`OpenMaps`);
    - the flag-gated destination in `PilgrimNavHost.kt` (`Routes.OFFLINE_MAPS`, inside the `honorEnabled` gate) and its `handleSettingsAction` branch;
    - `P/ui/walk/HonorWalkViewModel.kt` ("the day"'s line);
    - `StageMorningCardModel.mapsLine`;
    - a `MapsRowViewModel` beside `WaysRowViewModel`.
12. **U47, "It's shown whenever the flag is on (R21), as on iOS, unless U42 rules otherwise."** iOS's row is never reachable during a walk, because Settings isn't. **Fix:** owner decision 1, with the recommendation to follow `WaysAvailability`.
13. **U47, "From the overview it's always present for a stage, unless the cold-cache read timed out".** **Fix:** also absent while the first read is pending. On a later regions-changed it fills in or changes, live, even while the card is open (iOS's `onReceive`). Add a test for the live change.
14. **Key Technical Decisions, "the morning card gets no maps line … rather than telling a walker with no signal that saved maps are missing".** It isn't a platform equivalent. In that state iOS draws "no offline maps for today — save on wifi". **Fix:** keep it as a recorded deliberate addition (owner decision 2). The wait itself is the equivalent.
15. **Key Technical Decisions' matched list, "#121 item 5 (a route page reopened mid-download hides the row until it's reopened)".** **Fix:** that's the first-download case. Add the Update case: the reopened page keeps the old release's stage lines, so after the commit it reads "30 of 33 saved" in AE10's terms, and a save from it re-adds the retired stages' regions and skips the redrawn one (§1.7).
16. **U46 Files.** **Add:**
    - `PilgrimageRouteActions` gains `onSaveMaps` and `onCancelMaps`;
    - `PilgrimageRouteContent` takes the tiles phase and the row's state;
    - the existing tests to update: `PilgrimageRouteViewModelTest`'s `isBusy` test and `PilgrimageScreensSemanticsTest`.
    U47's `StageDaySheet` signature change touches `StageMorningCardTest`.
17. **Problem Frame / Context: "the route model's `isBusy`, whose comment names Stage 21-3".** Correct. **Note:** the seam's `PilgrimageTiles.isSaving` has no iOS caller. iOS's package manager doesn't refuse during a save; only the page's `isBusy` holds its controls. The route VM reads the manager's phase flow, not `isSaving` (§1.4).
18. **U47 test, "Settings opened right after launch shows the saved figure, not a flash of "none saved"".** Right for Android, but a deliberate improvement on iOS: iOS's own comment says its card shows "none saved" until the store answers. **Fix:** record it under the cold-cache gate row.

---

### Notes by unit

**U46, the route row.**
- **Files:** `P/ui/honor/pilgrimage/PilgrimageMapsRow.kt` (the model `PilgrimageMapsRowModel` with `megabytes` (C1), `label`, `savedLine`, `savingLine`, `savedA11y`, and the composable), `PilgrimageRouteViewModel.kt`, `PilgrimageRouteScreen.kt`, `R/values/strings.xml` (#1–#7).
- **Where:** inside `HeaderSection`'s inner `Column` (`spacedBy(PilgrimSpacing.small)`), right after `DownloadButton(page, busy, onDownload)` (`PilgrimageRouteScreen.kt:209`). Never in `StatusLines`.
- **The row's column:** `Column(verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs))`.
  - Saving: `Row(spacedBy(PilgrimSpacing.small))` holding the caption-fog line and a `TextButton` "cancel" in caption stone.
  - Saved: a clickable `Row(spacedBy(PilgrimSpacing.xs))` holding `Icons.Filled.CheckCircle` (13 dp, moss, no description) and the caption-fog line, with TalkBack's #4 label and `Role.Button`. Not full width.
  - Otherwise: a full-width `Button` in stone on `stone.copy(alpha = PilgrimOpacity.LIGHT)`, `RoundedCornerShape(PilgrimCornerRadius.normal)`, `PaddingValues(vertical = 12.dp)`, no elevation, `pilgrimType.button`.
  - Failed: the idle face, then a caption-rust line from `PilgrimageCopy.line(error)`.
- **Two packs** (`StylePackRequest` light and dark) come off both saving counts, clamped at 0.
- **VM state:** one nullable row state (per-stage values, estimate, status). It's derived from `page.installed` in `reload()` on IO, published only when both the estimate and the status are in, and `null` when not installed or no stage Way loads.
  - Refresh the status on regions-changed and on the tiles phase becoming `Idle`, latest wins.
  - Never recompute the estimate outside `reload()`.
  - Never hold decoded Ways.
- **`isBusy(phase, tilesPhase, held)`:** call sites `PilgrimageRouteContent` (`busy`), `onDownloadTapped`, `onRemoveTapped`.
- **`mapsRowIsHeld(phase)`** is the ported pure function; the page holds the row on `mapsRowIsHeld(phase) || page.isHeld`.
- **Actions:** `onSaveMaps()` hands the current per-stage values to the manager's save in the manager's scope and drops its result. `onCancelMaps()` calls the manager's `cancel()`.
- **Gating:** with the flag on, every route page resolves the tiles `Provider` at its opening and follows its phase, so another route's save holds its button and overflow (§1.4's `isBusy`); only the installed page reads the store (§5). With the flag off it's never resolved.

**U47, the morning card and Settings.**
- **Card:** `StageMorningCardModel.mapsLine(resources, saved: Boolean): String` (#9 and #10).
- **Overview:** `HonorOverview.mapsSaved: Boolean?`. The read runs in its own coroutine, after `Ready`, stages only, and again on each regions-changed, subscribed first. The screen passes `overview.mapsSaved?.let { mapsLine(it) }` at `HonorOverviewScreen.kt:344`.
- **"The day":**
  - `HonorWalkViewModel` holds `stageDayMapsSaved: StateFlow<Boolean?>` and a "read requested for this opening" latch. `openStageDay()` resets it to `null` and reads `honor.way` once.
  - `ActiveWalkScreen`'s `onOpenStageDay` (`:1104-1107`) calls it before `sheetHandoff.open`.
  - A `LaunchedEffect` in the sheet starts the read when it shows with no read requested: the restored-after-process-death case.
  - `StageDaySheet` gains `mapsLine: String?` and passes it to the card at `:1458`.
- **Settings:**
  - `MapsRowViewModel` (`shown`, the detail, `refresh()` from `SettingsScreen`'s `LaunchedEffect(Unit)`, regions-changed while it lives);
  - `DataCard(showsMaps, mapsDetail)`, with "Maps" after "Ways";
  - `SettingsAction.OpenMaps`;
  - `Routes.OFFLINE_MAPS`, flag-gated;
  - `OfflineMapsScreen` / `OfflineMapsViewModel`: `Loading`, `Saved(routeName, bytes, savedStages, totalStages)`, `Empty`; Delete with its dialog; reload on regions-changed.
- **Ported model:** `rowDetail(saved)`, `load(routeName, routeId, stages, tiles)` (nil on no stages or 0 bytes) and `loadInstalled` (`installed()`, a throw read as nothing installed; stage Ways read on IO and turned into per-stage values; the manager asked once).

**U48, the surface rows.**
- **The route row, every face, with TalkBack on each:**
  - the estimate before a first save;
  - partial after a cancel;
  - saving with "cancel";
  - saved, whose TalkBack label is #4;
  - each failure line: a walk started mid-save, airplane mode mid-save, a full disk if one can be arranged.
- **The held row during an Update:** compare its look with an iPhone if one is at hand (§1.4).
- **A fully current re-save:** Android shows "maps · stage k of n" briefly; iOS shows nothing. Record it.
- **Right after a UI kill:**
  - the route page opens with no "~N MB" flash on a saved route;
  - the overview's card says "maps saved for today";
  - "the day" opened at once, and a "the day" sheet restored open, both say it too;
  - Settings shows the figure, never a flash of "none saved".
- **The cold-start packs race:** open the route page right after several cold starts. Any "Save maps for the way · n of n saved" with both packs on the phone is D4's race, matched; record whether it shows.
- **Settings:**
  - the row's detail and the screen's "N MB · S of T stages" tick up during a save;
  - Delete during a save cancels it (the route page then reads "~N MB" or "s of m saved");
  - Delete's "Cancel" keeps the maps;
  - TalkBack on the dialog;
  - after an Update, the stale bytes stay deletable.
- **A route page reopened mid-download** (#121 item 5, matched): after a first download the row stays hidden; after an Update it reads the old counts.
- **The offline note** still shows on a stage opened offline after its maps are saved (D1, matched).
- **The flag off:** no Data card row, no Maps destination, and no route row (no Honor sheets).

---

### Android additions to record at the gate

| # | Addition | Reason | Kind |
|---|---|---|---|
| A1 | The route row's estimate and status are computed off Main from per-stage values, and the row is drawn only once both are in for the page's installed release | iOS computes both synchronously on the main thread in `reload()` (D8); its row never shows without them, so hiding until they land is the same visible rule | platform equivalent |
| A2 | The route row is also held while the page holds itself (an install's tap through its reload) | Android's package phase turns `Downloading` only after the guard's suspending reads, and its reload runs off Main; the holds stand in for iOS's synchronous early phase and reload (Stage 21-2's A-10), so the row can't start the mid-Update save `mapsRowIsHeld` forbids | platform equivalent (extends A-10) |
| A3 | Every surface waits, bounded, for the store's first answer after process start: the route row, the overview's flag, "the day", the Data card row, the Maps screen. Past the bound the route row and Settings draw iOS's cold face, corrected on regions-changed | iOS's cache is cold for under a second (D6); Android's UI process restarts far more often, mid-walk included. In settled states both read the same store answer | platform equivalent (C2 owns the mechanism) |
| A4 | Past the bound, the morning card draws no maps line where iOS would draw "no offline maps for today — save on wifi" | not to tell a walker with no signal that saved maps are missing because the store hasn't answered | deliberate addition (owner decision 2) |
| A5 | "The day"'s line is read at the tap and, for a sheet restored open after process death, when it first appears; it fills in when the store answers | iOS computes it synchronously at the tap and can't restore a sheet; Android restores `showStageDay` (Stage 21-2's A-3) and reads the store asynchronously | platform equivalent (extends A-3) |
| A6 | Settings' Maps row and screen hidden while a walk is on or its Honor step is pending (if owner decision 1 is accepted) | iOS's Settings is unreachable during a walk; Android's may not be (shared-walk spec, open question 2) | R6 hardening (extends correction 14) |
| A7 | The Maps destination is registered only with the flag on; the Data card row is absent with it off | R21 | flag |
| A8 | A fully current re-save visibly shows "maps · stage k of n" for a moment, where iOS draws nothing | Android's walk guard is a suspending Room read before each load (C2), so the save yields between steps | consequence of the walk-guard row |
| A9 | Settings reads a thrown `installed()` as nothing installed ("none saved", no Delete) | iOS's `installed()` can't throw; Android's can (`WaysListViewModel.routeNameOrNone()` precedent) | platform equivalent |
| A10 | The Maps screen has a `Loading` state that draws nothing until its first load | iOS's `onAppear` reload lands before the first frame; Android's runs off Main | platform equivalent |

---

### iOS defects (matched as shipped)

Survey candidates in this cluster: **D1 confirmed**, **D4 confirmed and extended**, **D7 confirmed**, **D9 refuted as a defect** (a note). Filed items: **#122 item 5 confirmed**, **#121 item 5 confirmed, with a maps consequence to add**. D8 stays a performance note, not filed (survey). D2, D3, D5 and D6 are C2's and C3's. Four new candidates, C4-1 to C4-4.

**D1. The offline note stays after maps are saved.** Low.
- Evidence: §7 (`HonorOverviewView.swift:44-51`; PR #86 left it untouched; it never consults `isStageSaved`).
- What a user sees: the first stage they open offline after saving its maps says "map tiles need a connection; the way itself is on your phone." The map then draws from the saved region, and the morning card can say "maps saved for today".
- Android: unchanged, matched.

**D4. "33 of 33 saved", and its two companions.** Low (copy).

```swift
    func status(for routeId: String, stages: [Way]) -> Status {
        let byId = regionsById()
        let saved = stages.filter { isSaved($0, region: byId[$0.id]) }
        guard !saved.isEmpty else { return .none }
        let packsPresent = StylePackRequest.allCases.allSatisfy(loader.hasStylePack)
        guard saved.count == stages.count, packsPresent else { return .partial(saved: saved.count, of: stages.count) }
```
> Pilgrim/Models/Honor/PilgrimageTilesManager.swift:128-133@7c200bf

- **The headline:** every region saved and a pack missing reads "Save maps for the way · 33 of 33 saved" (C2 pins the test).
- **Companion (a):** in that same state the morning card says "maps saved for today". `isStageSaved` checks only the stage's region, not the packs (§2.2 quote). Offline, a missing style pack can leave the map without its style.
- **Companion (b):** on a cold start, a regions answer that lands before the packs answer reads "33 of 33 saved" with both packs on the phone. The packs answer fires only `.packs`, which the page doesn't refresh on (§1.6). A tap doesn't clear it: every pack and region is current, so the save loads nothing, fires no regions signal, and never suspends, so no `idle` transition is seen (D7). It lasts until the page is reopened.
- Android: match all three; U48 records whether (b) shows.

**D7. "Tap to save again" saves nothing new and shows nothing.** Low (copy and accessibility).
- Evidence: the label (§1.3, `PilgrimageMapsRow.swift:79`); the save skips every complete, current region and loads with `acceptExpired: true` (`PilgrimageTilesManager.swift:240-243`); a fully current save never suspends, so neither the saving line nor a refresh happens (§1.6).
- What a user sees: TalkBack or VoiceOver promises a re-save. A tap does nothing visible, and expired tiles aren't refreshed.
- Android: match the label. Its save flashes the saving line briefly (A8).

**D9. "save on wifi" though cellular is allowed: not a defect.** The design spec's decision 4 allows cellular on purpose ("the size is the guardrail"), and the line is advice, not a rule. The loader sets no network restriction (C3). A note only.

**pilgrim-ios #122 item 5 (filed). The disk-full line names voices.**
- Evidence: §4 (`PilgrimageWayImporter.swift:20`, `HonorImportReducer.swift:34`).
- What a user sees: "not enough space on this phone to save these voices" under "Save maps for the way".
- Android: matched through `honor_import_disk_full`. Nothing new to file.

**pilgrim-ios #121 item 5 (filed), with a maps consequence.** Low.
- Evidence: §1.7 (`PilgrimageRouteView.swift:145-148,350-354`).
- Already in the issue: a page opened during a first download keeps the row hidden after it lands.
- Not yet in the issue: a page opened during an **Update** keeps the old release's stage lines. After the commit the row reads, in AE10's terms, "Save maps for the way · 30 of 33 saved". A tap then re-adds regions 30–32, orphaned until the next launch's sweep, skips the redrawn stage 12, and ends at "maps saved · N MB" while the installed route is the new one.
- File as an addition to item 5 when the themed issue goes up. Android: matched.

**C4-1. A save's failure line outlives its cause and its page.** Low (copy).
- Evidence: §1.5. `.failed` stays on the shared manager "until the next save or cancel clears it" (`PilgrimageTilesManager.swift:256-266`). Every route page draws it from the phase (`PilgrimageMapsRow.swift:56-60`).
- What a user sees: a walker whose save was stopped by starting a walk opens the route page after the walk and still reads "finish your walk first". A failed "the download didn't finish" greets every later opening, days later included, until a tap.
- Suggestion upstream: clear the failure when the page appears, or keep it only for the page that started the save.
- Android: match.

**C4-2. Remove's and Replace's confirmations don't say the saved maps go too.** Low (copy).

```swift
    static func replaceConfirmation(routeName: String) -> String {
        "Replace the \(routeName)? Its stages leave your phone; what you've walked of it is remembered if it comes back. Walks in your journal stay."
    }
// …
    static func removeConfirmation(routeName: String) -> String {
        "Remove the \(routeName)? Its stages leave your phone; what you've walked of it is remembered if it comes back. Walks in your journal stay."
    }
```
> Pilgrim/Models/Honor/PilgrimagePackageManager.swift:214-216,220-222@7c200bf

- Slice three made both operations take the route's saved maps (`tiles?.remove`, `PilgrimagePackageManager.swift:248,283`), up to ~240 MB fetched on purpose. The confirmation names only the stages.
- Android: match (the strings exist as `pilgrimage_replace_confirmation` / `pilgrimage_remove_confirmation`).

**C4-3. "1 of 1 stages".** Low (copy).
- Evidence: §3.3 (`OfflineMapsView.swift:63`). The line is always plural, where the Ways footer picks "stage" at 1 (`WaysListView.swift:22-28`).
- Fits #122's copy theme, or the maps theme. Android: match.

**C4-4 (unverified). One tap may fire every button in the route page's header row.** Medium if confirmed.

```swift
        List {
            Section { header } footer: { statusFooter }
```
> Pilgrim/Scenes/Honor/PilgrimageRouteView.swift:111-112@7c200bf

```swift
        Button {
            if isInstalled && !hasUpdate { return }
            beginInstall()
        } label: {
```
> Pilgrim/Scenes/Honor/PilgrimageRouteView.swift:210-213@7c200bf

- The setup: the header is one `List` row, holding the download button and, since PR #86, the maps row's button. Neither sets a `.buttonStyle`, and no Honor scene does (`git grep buttonStyle` finds none under `Scenes/Honor`).
- SwiftUI is widely reported to make a `List` row with several default-style buttons fire all of them on one tap.
- If that holds here: with an update ready, a tap on "Save maps for the way" also starts the Update, and a tap on "Update" also starts a save. That's the mid-Update save `mapsRowIsHeld` exists to prevent. The device pass couldn't have seen it: its route was current, so the download button's action returned at once.
- Verify on an iPhone before filing; if it doesn't reproduce, drop it.
- Android: Compose has no such behaviour; nothing to port either way.

---

### Proposed owner decisions

1. **Does Settings' Maps row follow the Ways row's walk gating?** (flow-analysis item 12)
   - iOS shows the row always, but Settings can't be reached during an iOS walk. Android hides "Ways" while a walk is on or its Honor step is pending (correction 14, R6), because Android's Settings may be reachable then.
   - A Delete taken mid-walk would remove the regions the walk's map is reading. It can't reach `:tracker`, but it can blank the walker's basemap offline.
   - **Recommendation:** reuse `WaysAvailability` for the Maps row and screen. The screen leaves when it turns false, as Ways' does. Record it under correction 14's R6 row. Mid-walk this matches what iOS's walker can reach (nothing). The Honor-step clause over-hides for the few seconds after a walk, in exchange for one rule.
   - **Alternative:** the plan's default, shown whenever the flag is on.
2. **Past the cold-cache bound, does the morning card draw no maps line, or iOS's empty-cache line?**
   - iOS draws "no offline maps for today — save on wifi" in that state. The overview corrects itself when the store answers, and "the day" never does.
   - **Recommendation:** no line, recorded as A4, with D6 filed upstream. It removes a false negative a walker with no signal would otherwise act on, and in every settled state the card says what iOS's says.
   - **Alternative:** iOS's line. That keeps the visible output parity-exact at the cost of the false negative after a slow store.

---
