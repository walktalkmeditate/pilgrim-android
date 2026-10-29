# Parity Spec: stamp-closing

| field | value |
|---|---|
| **iOS pin** | `e551b11` (main, per CLAUDE.md) |
| **Android HEAD** | `0ee2872` |
| **Generated** | 2026-10-28 |
| **Type** | port |
| **Generator** | ios-parity skill |


> **Pin.** iOS is pinned at `e551b11`, the merge of pilgrim-ios PR #91 (`feat/stamp-closing`). `git diff 10540d0 e551b11` holds this PR and nothing else. `10540d0` is PR #92's merge, the merge's first parent. The diff covers 16 files: 10 source files under `Pilgrim/`, the README, and 5 test files. None of this slice's files changes between `e551b11` and `3b78728`, the stage-links pin: `git log e551b11..3b78728` over them is empty. So this spec reads the same at either pin.
>
> **This spec supersedes the annex.** The annex is `docs/parity/2026-10-02-honor-pilgrimage-stages-port.md`, lines 8100–9316 ("Annex. iOS PR #91"). It was written at the PR's open head, `534f170`. Nine commits landed after it, and five of them change behaviour:
> - `7af5adc`: a mis-shaped `stampHours` is now read leniently.
> - `9b25093`: the launch hand-down.
> - `c8e2d35`: the quiet clocks are split.
> - `24250f6`: the haptic now waits on its caption.
> - `46c362a`: `Temple 0` no longer names a temple.
> - `0e73fa1`: temples keep ten minutes between notices.
>
> The other three commits (`16cbc44`, `d768955`, `e82b11e`) are docs and test only. The table below records what still holds.
>
> **Prior installs exist on Android.** Android 2.0.0 (versionCode 912) staged Shikoku stage Ways with no hours. `Way` has no such field (`P/domain/honor/Way.kt:268-284@0ee2872`), and the wire model ignores the key (`P/data/honor/pilgrimage/PilgrimageModels.kt:127-138@0ee2872`). The launch hand-down (annex PR91-D1, fixed upstream in `9b25093`) is therefore required on Android.
>
> **Paths and pins.** `P/` is `app/src/main/java/org/walktalkmeditate/pilgrim/` and `T/` is `app/src/test/java/org/walktalkmeditate/pilgrim/`. Every Android anchor is `@0ee2872`, and every iOS anchor is `@e551b11` unless it says otherwise.

**Annex sections, against `e551b11`**

| Annex section | Status at `e551b11` | Where this spec covers it |
|---|---|---|
| A.0 items 1, 2, 4 | Holds, except for one change to item 2: only the nearest eligible temple may speak, and once it has been named it holds every later temple (`c8e2d35`) | Behavior → tracker |
| A.0 item 3; A.3.5 ("one quiet clock", "one notice an hour", "renamed, nothing added") | **Void.** There are three clocks now: water's own hour, the temples' ten minutes, and a shared 60 s gap | Behavior → tracker |
| A.0 item 5; A.5.1 table; A.5.4 names | **Corrected.** #91 needs four persisted things, not two. Android's existing `last_notice_seconds` holds the value that iOS now calls `lastWaterSeconds` | Data → tracker state |
| A.1.1 dataset | Holds. Re-checked against the local `../open-pilgrimages` checkout at `v1.13.0`: four Shikoku routes carry `{"opens":"08:00","closes":"17:00"}`; there are 88 `"Temple N"` texts and no `"Temple 0"`; no way file carries `tzIdentifier` | — |
| A.1.2: "a structurally wrong `stampHours` fails the whole route", the Json-config bullet, the A.8.4 Android edge test that expects `NotWalkable`, and A.10's "Json config stays strict" | **Reversed** (`7af5adc`). A wrong shape now costs only the hours; the route still decodes | Data → decode |
| A.1.2 `minutesSinceMidnight` and the `Int(_:)` quirks (`"+8"` reads as 8; non-ASCII digits are refused) | Holds; the function is unchanged | Edge cases |
| A.1.3, A.1.4, A.2 | Hold | Data |
| A.3.1 tunings | **Corrected.** Two constants are added: `noticeGapSeconds = 60` and `stampQuietSeconds = 600`. The temple no longer reuses `markQuietSeconds` | Behavior → tunings |
| A.3.2 office and clock | Holds. The window still ignores `opens` and never wraps past midnight; both are now documented as deliberate (`d768955`) | — |
| A.3.3 `templeNumber` | **Corrected** (`46c362a`): `Temple 0` and `Temple 0000` now give nil | Behavior → Way |
| A.3.4 scan | **Corrected**: the guard order changed, and an already-named temple now ends the scan with `return []`. "The nearest temple inside 1 km is passed over" still holds | Behavior → tracker |
| A.3.6 event; A.3.7 constructor default | Hold (the Snapshot gains more fields; see Data) | — |
| A.4.1 "the haptic fires even if `showStampCaption` returns early" | **Fixed** (`24250f6`): with no caption there is no tap | Behavior → view model |
| A.4.2, A.4.3 | Hold | UI |
| A.5.2 shape | Holds, and was built in schema 12. `STAMP` needs no migration. The claim "rows are written at most once per 3,600 s" is void | Data |
| A.6.1 "existing traces byte-identical" | Holds by argument at `e551b11`; not re-probed | Android notes → golden |
| A.6.2 | Holds; the Mirror label becomes `lastNoticeSeconds` | Android notes → golden |
| A.6.3 temple traces | **Two of the six are void** (`share-the-hour`, `closes`); replacements are listed below | Android notes → golden |
| A.7 strings | Holds. Use A.4.3's `%1$s` resource form, not A.7's `%d` | UI → strings |
| A.8 tests | **Grown** from 16 tests plus 1 assertion to 31 tests plus 1 assertion | Android notes → tests |
| A.9, A.13, A.14 | Historical. The plan A.9 corrected has shipped | — |
| A.10 checklist | Superseded | Android notes → checklist |
| A.11 items 1–7 | Hold; items 8–10 are added below | Android notes |
| A.12 D1–D6 | D1, D2, D4, D6 and the "Temple 0" half of D5 are fixed upstream; port the fixes. D3 is moot. D5's window half is kept upstream as deliberate | — |

## iOS source map

- `Pilgrim/AppDelegate.swift` — 339 LOC — app launch. `reconcileTilesAtLaunch` is renamed `reconcilePilgrimageAtLaunch`; after the tiles reconcile it hands the installed route's stamp hours down to its stages.
- `Pilgrim/Models/Honor/HonorEngine.swift` — 339 LOC — session engine. Adds the `stampAhead` event, builds the `StampOffice` from the Way at init, and maps the tracker's action to the event.
- `Pilgrim/Models/Honor/HonorMomentTracker.swift` — 236 LOC — pure moment bookkeeping. Adds the `StampOffice` window, the `fudasho` list, `firedStamps`, three notice clocks, `stampAhead`, and `isLineFree`.
- `Pilgrim/Models/Honor/HonorTuning.swift` — 51 LOC — constants. Adds `noticeGapSeconds`, `stampNoticeMinutes`, `stampMinAheadMeters` and `stampQuietSeconds`.
- `Pilgrim/Models/Honor/PilgrimagePackageManager.swift` — 491 LOC — package install. Threads the route's hours onto each staged stage, adds `restampStageHours(of:)`, and widens the `saveStage` seam to that launch pass.
- `Pilgrim/Models/Honor/PilgrimageWayImporter.swift` — 429 LOC — wire decode. Adds `RouteFile.StampHours` behind a custom lenient `init(from:)`, plus the "HH:mm" parse and the `stampHours:` parameter on `way(from:)`.
- `Pilgrim/Models/Honor/Way.swift` — 263 LOC — the Way model. Adds `WayStampHours`, `Way.stampHours` (last, optional) and `WayMoment.templeNumber`.
- `Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift` — 478 LOC — the walk's event handler. The `.stampAhead` case shows the caption and taps only if the caption showed; all captions go through one helper, `showHonorCaption`.
- `Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+MarkPins.swift` — 74 LOC — the mark captions. `showStampCaption` now returns whether it showed.
- `Pilgrim/Scenes/Honor/WayMomentHeader.swift` — 163 LOC — moment header copy. Adds `WayStampNotice.caption` and `hour`, which detect a 12-hour clock with the ICU `"j"` pattern.
- `README.md` — 325 LOC — repository README. One user-facing line ("a temple ahead says once that its stamp office shuts at five"), plus the 2.0.0 catch-up. Not app copy; not ported.
- `UnitTests/Honor/HonorMomentTrackerTests.swift` — 491 LOC — 14 stamp tests (`:274-490`).
- `UnitTests/Honor/HonorWayRenderingTests.swift` — 208 LOC — 3 tests: the caption, the hour, and Temple 0 (`:145-187`).
- `UnitTests/Honor/PilgrimagePackageManagerTests+Lifecycle.swift` — 366 LOC — 6 tests: the lenient installed read and the launch pass (`:164-316`).
- `UnitTests/Honor/PilgrimageStageWalkTests.swift` — 483 LOC — 2 view-model tests with a haptic-attempt counter (`:376-412`).
- `UnitTests/Honor/PilgrimageWayImporterTests.swift` — 455 LOC — 6 tests and 1 assertion on the route and stage hours (`:291-389`).

## Behavior

### `Pilgrim/Models/Honor/HonorMomentTracker.swift`

**State machine.** The tracker keeps three clocks and two fired sets. All of them are mutable and in memory only; iOS never resumes a walk.

```swift
    private let marks: [WayMark]
    private var firedMarks: Set<String> = []
    /// Active seconds at the last water caption; nil means the first is
    /// free. Water's own, so a fountain no longer holds a temple for an
    /// hour, nor a temple a fountain.
    private var lastWaterSeconds: TimeInterval?
    /// Active seconds at the last notice of either kind, read only for the
    /// short gap that keeps two notices off the caption line at once. That
    /// minute can still cost one of them: a fountain under about 80 m ahead
    /// when the other notice speaks is passed before the minute is up, and a
    /// temple 1.00–1.08 km ahead is inside the kilometre by then.
    private var lastNoticeSeconds: TimeInterval?
    private let stamp: StampOffice?
    /// The stamp temples among the moments, in the order they are passed.
    /// Empty whenever the route states no hours, so the scan below has
    /// nothing to scan on every Way but Shikoku's.
    private let fudasho: [WayMoment]
    private var firedStamps: Set<String> = []
    /// Active seconds at the last temple notice; nil means the first is free.
    private var lastStampSeconds: TimeInterval?
```
> `Pilgrim/Models/Honor/HonorMomentTracker.swift:62-81@e551b11`

`fudasho` is built once, from the already-sorted moments (by frac, with ties broken by id), and is empty when there is no office:

```swift
        self.fudasho = stamp == nil ? [] : self.moments.filter { $0.templeNumber != nil }
```
> `Pilgrim/Models/Honor/HonorMomentTracker.swift:97@e551b11`

**Order within one `update`.** The order is: moments reached, then voices dropped, then `stampAhead`, then `waterAhead`, then `startNextIfPossible`. On a tie the temple goes first, and the shared gap then silences the fountain for that fix.

```swift
        // The temple before the fountain: when both are due on one fix the
        // gap lets only the first speak, and of the two an office about to
        // shut is the one still worth a walker's afternoon.
        actions += stampAhead(progressFrac: progressFrac, activeSeconds: activeSeconds, isOnWay: isOnWay)
        actions += waterAhead(progressFrac: progressFrac, activeSeconds: activeSeconds, isOnWay: isOnWay)
        actions += startNextIfPossible(gates: gates)
```
> `Pilgrim/Models/Honor/HonorMomentTracker.swift:138-143@e551b11`

**Water.** Water now checks the shared gap first, then its own hour, and a water notice writes both clocks:

```swift
        guard isOnWay, !marks.isEmpty, geometry.totalMeters > 0, isLineFree(at: activeSeconds) else { return [] }
        if let last = lastWaterSeconds, activeSeconds - last < HonorTuning.markQuietSeconds { return [] }
        for mark in marks where !firedMarks.contains(mark.id) {
            let ahead = (mark.frac - progressFrac) * geometry.totalMeters
            guard ahead >= 0 else { continue }
            guard ahead <= HonorTuning.markAheadMeters else { break }
            firedMarks.insert(mark.id)
            lastWaterSeconds = activeSeconds
            lastNoticeSeconds = activeSeconds
            return [.markAhead(mark, meters: ahead)]
        }
```
> `Pilgrim/Models/Honor/HonorMomentTracker.swift:179-189@e551b11`

**Temple.** In the first guard, the gap check and the window check come after the office check. A temple that is behind or nearer than 1 km is skipped with `continue`. The first remaining temple either fires, or, if it has already been named, ends the scan with nothing:

```swift
        guard isOnWay, !fudasho.isEmpty, geometry.totalMeters > 0, let stamp,
              isLineFree(at: activeSeconds), stamp.isClosingSoon else { return [] }
        if let last = lastStampSeconds, activeSeconds - last < HonorTuning.stampQuietSeconds { return [] }
        for temple in fudasho {
            let ahead = (temple.frac - progressFrac) * geometry.totalMeters
            // Behind them, or close enough that the walk has already decided.
            guard ahead >= HonorTuning.stampMinAheadMeters else { continue }
            // The nearest choice, already named: the next is not news yet.
            guard !firedStamps.contains(temple.id) else { return [] }
            firedStamps.insert(temple.id)
            lastStampSeconds = activeSeconds
            lastNoticeSeconds = activeSeconds
            return [.stampAhead(temple, meters: ahead)]
        }
        return []
```
> `Pilgrim/Models/Honor/HonorMomentTracker.swift:208-222@e551b11`

**The shared gap.** Nil means the line is free, and the comparison is `>=`:

```swift
    private func isLineFree(at activeSeconds: TimeInterval) -> Bool {
        guard let last = lastNoticeSeconds else { return true }
        return activeSeconds - last >= HonorTuning.noticeGapSeconds
    }
```
> `Pilgrim/Models/Honor/HonorMomentTracker.swift:226-229@e551b11`

**The window.** `StampOffice.isClosingSoon` is unchanged since the annex (A.3.2 holds verbatim). It gained one doc paragraph:

```swift
        /// Deliberately reads the closing time alone, on the same day: every
        /// value the dataset carries is 08:00–17:00, so `opens` never falls
        /// inside the window and no close comes early enough to wrap past
        /// midnight. An office that did either would need both handled here.
        var isClosingSoon: Bool {
            var calendar = Calendar(identifier: .gregorian)
            calendar.timeZone = timeZone
            let clock = calendar.dateComponents([.hour, .minute], from: now())
            guard let hour = clock.hour, let minute = clock.minute else { return false }
            let minutesNow = hour * 60 + minute
            return minutesNow >= closesMinutes - HonorTuning.stampNoticeMinutes && minutesNow < closesMinutes
        }
```
> `Pilgrim/Models/Honor/HonorMomentTracker.swift:33-44@e551b11`

**What the Android units must reproduce.** Each item is pinned by an iOS test at `e551b11`, or by an annex probe where noted.

| # | Behaviour | Pinned by |
|---|---|---|
| 1 | At most one notice per fix; on a tie the temple wins. The fountain is silent 30 s later and speaks at 60 s, because it keeps its own hour, not the temple's | `testTheTempleTakesTheLineAheadOfTheFountain` (`HonorMomentTrackerTests.swift:361-376`) |
| 2 | A fountain spoken at 14:59 does not hold the temple: 60 s later, at 15:00, the temple speaks | `testAFountainDoesNotSilenceATemple` (`:381-394`) |
| 3 | A temple does not hold a fountain: 1,200 s after the temple, a fountain speaks | `testATempleDoesNotSilenceAFountain` (`:396-404`) |
| 4 | Temple 10 at 16:10; at 16:20 (600 s), 600 m short of it, temple 11 speaks; at 16:50 neither speaks again | `testTwoTemplesInTheLastHoursBothSpeakOnceEach` (`:409-429`) |
| 5 | A named temple more than 1 km ahead holds every later temple. Temples 11 and 12 stay silent at 600 s while temple 10 is 3 km on, and temple 11 speaks once the walker is 500 m short of temple 10 | `testATempleAlreadyNamedHoldsTheNextUntilTheWalkerIsNearlyThere` (`:434-445`) |
| 6 | Ten minutes between temples. Temple 10 is named at 1,001 m; inside the 60 s gap: silent; at 60 s with the line free: silent; at 599 s: silent; at 600 s: temple 11 | `testTheNextTempleWaitsTenMinutesAfterTheLast` (`:451-470`) |
| 7 | Real Awa stage-00 spacing: temple 6 fires at 1,100 m; temple 7 is silent at 120 s and fires at 600 s, with `meters` 1563 ± 1 | `testTwoTemplesAKilometreApartDoNotSpeakMinutesApart` (`:476-490`) |
| 8 | Each temple speaks once; temple 10 still holds at 600 s; temple 11 speaks at 5,400 s | `testEachTempleSpeaksOnce` (`:315-329`; its second fix moved from 60 s to 600 s) |
| 9 | The window is `[closes − 120, closes)`: 14:59 is silent, 15:00 fires, 17:00 is silent | `testTheNoticeOpensTwoHoursBeforeClosingAndShutsWithTheOffice` (`:280-295`) |
| 10 | 999 m short is silent; 1,001 m short fires, with `meters` ≈ 1001 | `testATempleInsideAKilometreHasStoppedBeingADecision` (`:297-313`) |
| 11 | The nearest temple inside 1 km is passed over for the next one; a skipped temple never speaks later | annex A.3.4 probe 1, unchanged by `c8e2d35` (the `continue` still precedes the fired check) |
| 12 | Off the way, or with no office, nothing fires and no clock moves | `testAWalkerOffTheWayIsToldNothing` (`:351-356`), `testARouteThatStatesNoStampHoursNeverSpeaksOfTemples` (`:274-278`) |
| 13 | A `seal` waypoint without the "Temple N" text stays silent | `testASealThatIsNotANumberedTempleStaysSilent` (`:341-349`) |
| 14 | After the close nothing fires and nothing is marked fired, so an unfired temple can speak in the next day's window | annex A.3.2 probe 4; nothing in `c8e2d35` changes this path |
| 15 | The shared minute's documented cost: a fountain under about 80 m ahead when another notice speaks, or a temple 1.00–1.08 km ahead, is passed before the gap ends and never speaks. This is intended upstream | `HonorMomentTracker.swift:68-72` (doc, `16cbc44`) |

### `Pilgrim/Models/Honor/HonorTuning.swift`

```swift
    static let markQuietSeconds: TimeInterval = 3600
    /// The least walking between any two notices on the caption line, water
    /// or temple. The line holds a notice for 20 s; a minute keeps one from
    /// replacing another still being read, or two taps landing together.
    static let noticeGapSeconds: TimeInterval = 60
    /// How long before the stamp office shuts the notice is worth saying —
    /// from 15:00 for a 17:00 close. Earlier it is a fact about the
    /// afternoon rather than a choice anyone is making.
    static let stampNoticeMinutes = 120
    /// Nearer than this and the temple has stopped being a decision: the
    /// walker is already arriving.
    static let stampMinAheadMeters = 1000.0
    /// The least walking between two temple notices. Shikoku's temples can
    /// stand barely a kilometre apart: on Awa stage-00 from 15.0 km, temple 6
    /// spoke at 1.1 km and temple 7 two minutes later. Across all 40 stages
    /// at 4 km/h, ten minutes removed every pair under ten minutes apart and
    /// cost 1 notice in 3,979; water's hour would hold a 16:10 notice's
    /// successor past 17:00.
    static let stampQuietSeconds: TimeInterval = 600
```
> `Pilgrim/Models/Honor/HonorTuning.swift:32-50@e551b11`

All the seconds here are engine seconds (`activeSeconds`, the walk's active duration), never wall-clock seconds. Only the window reads the wall clock. Android's `P/domain/honor/HonorTuning.kt:34-38` has `MARK_AHEAD_METERS` and `MARK_QUIET_SECONDS` today; the four stamp constants go beside them.

### `Pilgrim/Models/Honor/HonorEngine.swift`

```swift
    private static func stampOffice(for way: Way, now: @escaping () -> Date) -> HonorMomentTracker.StampOffice? {
        guard let hours = way.stampHours else { return nil }
        return HonorMomentTracker.StampOffice(
            closesMinutes: hours.closesMinutes,
            timeZone: way.tzIdentifier.flatMap(TimeZone.init(identifier:)) ?? .current,
            now: now)
    }
```
> `Pilgrim/Models/Honor/HonorEngine.swift:99-105@e551b11`

- The office is built once, inside `init` (`:90-92`). The zone is captured then, and the clock is the engine's injected `now`.
- The tracker is fed `activeDuration` and `isOnWay` on every accepted fix (`:174-175`). There is no first-emission latch: every clock starts nil, so the first notice of each kind is free.
- The action maps one-to-one onto the event: `case .stampAhead(let temple, let meters): subject.send(.stampAhead(temple: temple, meters: meters))` (`:335`). Android's exhaustive `toEvent()` (`P/domain/honor/HonorEngine.kt:376-383`) and the `planEvents` `when` (`P/walk/honor/HonorSession.kt:524-562`) both gain an arm. So does the test mapping in `T/domain/honor/HonorGoldenTraceTest.kt:618`.

### `Pilgrim/Models/Honor/Way.swift`

```swift
    var templeNumber: Int? {
        guard let text, text.hasPrefix(Self.templePrefix) else { return nil }
        let digits = text.dropFirst(Self.templePrefix.count).prefix { $0.isASCII && $0.isNumber }
        guard (1...4).contains(digits.count), let number = Int(digits), number > 0 else { return nil }
        return number
    }

    private static let templePrefix = "Temple "
```
> `Pilgrim/Models/Honor/Way.swift:108-115@e551b11`

The annex's probed outcomes (A.3.3) still hold, except one: `"Temple 0 · x"` now gives nil, not 0. `"Temple 01"` still gives 1, because `Int("01") == 1 > 0`. Android takes the digits with a `'0'..'9'` range, never `Char.isDigit()`, as A.3.3 says, and adds the `> 0` check.

### `Pilgrim/Models/Honor/PilgrimageWayImporter.swift`

**Only `stampHours` is lenient.** Every other key decodes exactly as the synthesized decode would:

```swift
        init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            id = try container.decode(String.self, forKey: .id)
            name = try container.decode(String.self, forKey: .name)
            names = try container.decodeIfPresent([String: String].self, forKey: .names)
            country = try container.decodeIfPresent(String.self, forKey: .country)
            region = try container.decodeIfPresent(String.self, forKey: .region)
            distanceKm = try container.decode(Double.self, forKey: .distanceKm)
            stageCount = try container.decode(Int.self, forKey: .stageCount)
            tradition = try container.decodeIfPresent(String.self, forKey: .tradition)
            summary = try container.decodeIfPresent(String.self, forKey: .summary)
            stages = try container.decode([Stage].self, forKey: .stages)
            stampHours = try? container.decodeIfPresent(StampHours.self, forKey: .stampHours)
        }
```
> `Pilgrim/Models/Honor/PilgrimageWayImporter.swift:184-197@e551b11`

What iOS now accepts and refuses:
- Any `stampHours` that is not an object with string members `opens` and `closes` becomes nil, and the route decodes. Six shapes are pinned: a missing `closes`, numbers, a bare string, a null member, `null`, and an array of two windows (`PilgrimageWayImporterTests.swift:325-340`).
- A required member of the wrong type, such as `"stageCount": "2"`, still refuses the whole route (`:344-351`).
- The same decode backs `installed()`, so a reshaped value never hides an installed route (`PilgrimagePackageManagerTests+Lifecycle.swift:164-174`).
- Strings that decode but don't parse as "HH:mm" drop the pair whole (`:388-400`, unchanged; annex A.1.2's `Int(_:)` notes hold).
- The stage file never carries hours. They arrive as a parameter, `way(from:routeId:stageIndex:stampHours:)` (`:206`), and are assigned last: `way.stampHours = stampHours` (`:240`).

### `Pilgrim/Models/Honor/PilgrimagePackageManager.swift`

**The download threads the route's hours onto every stage.** This is unchanged since annex A.2:

```swift
                let stagePlan = StagePlan(routeId: entry.id, url: stageURL, stageCount: fetched.route.stageCount,
                                          expected: fetched.route.stages[index],
                                          stampHours: fetched.route.stampHours)
```
> `Pilgrim/Models/Honor/PilgrimagePackageManager.swift:215-217@e551b11`

**The launch pass is new** (`9b25093`):

```swift
    func restampStageHours(of installed: Installed) {
        guard !isDownloading, let hours = installed.route.stampHours else { return }
        for index in 0..<installed.route.stageCount {
            let id = WayStore.stageWayId(routeId: installed.routeId, stageIndex: index)
            guard var way = store.load(id: id), way.stampHours != hours else { continue }
            way.stampHours = hours
            do {
                try saveStage(way)
            } catch {
                // That stage stays silent and the next launch tries again;
                // a notice is never a reason to fail a launch.
                print("[PilgrimagePackageManager] stamp hours not written to \(id): \(error)")
            }
        }
    }
```
> `Pilgrim/Models/Honor/PilgrimagePackageManager.swift:156-170@e551b11`

What the pass does:
- **Guards.** It stands aside while a download is in flight. `isDownloading` is set at a download's entry and cleared by `defer` (`:181-183`), so the flag covers the off-main-actor commit too. A route that states no hours is never touched.
- **Idempotent.** It compares, then saves. A second launch writes nothing, so no flag is needed (`testASecondLaunchPassWritesNothing`, `PilgrimagePackageManagerTests+Lifecycle.swift:229-240`).
- **Per-stage failure.** A stage that fails to save is logged and skipped, and later stages still get their hours (`:258-273`).
- **One-way.** The pass never clears hours from a stage. A route that later drops its hours loses them on its stages only at the next Update, which re-stages every stage.
- **The write.** It goes through the `saveStage` seam (`:31-34`), now shared with the commit. Behind the seam, `WayStore.save` rewrites `way.json` atomically and writes `accepted.json` only if it is absent (`WayStore.swift:93-101@e551b11`). The iOS test asserts that after the pass, nothing but the hours differs. That covers the rest of the Way, the corridor hash, `acceptedAt`, the replies, the walk link, and the installed release (`:198-225`).

### `Pilgrim/AppDelegate.swift`

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
> `Pilgrim/AppDelegate.swift:188-199@e551b11`

- It runs once per process launch, from `runPostDoneLaunchTasks` (`:176`), after the expiry sweep.
- One `installed()` read feeds both the tiles reconcile and the restamp; the restamp comes second.
- The pass is synchronous on the main actor. It reads `route.json` and writes up to `stageCount` `way.json` files there. That is acceptable on iOS for a 40-stage route, but it must not move to Android's main thread.
- Android already has the hook, with a placeholder at `P/walk/honor/HonorFinalizer.kt:272-278`:
  - `launchPilgrimage` warms the tiles, takes `runAtLaunch()`'s read (`P/data/honor/pilgrimage/PilgrimagePackageManager.kt:231-235`), reconciles, and carries the comment "pilgrim-ios #91's launch step goes here".
  - The XCTest guard's Android counterpart is already in place. `HonorFinalizer` takes `packageLaunchWork` as an injected lambda gated on `releaseFlags.honor` (`HonorFinalizer.kt:117`), so tests never run the real pass.

### `Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift`, `ActiveWalkViewModel+MarkPins.swift`

```swift
        case .stampAhead(let temple, let meters):
            // The water source's tap, deliberately: one at the whisper's
            // intensity, a notice and not an alert. Both are the same kind
            // of passing word, so they feel the same in the pocket — and
            // only with a line to read, never a tap about nothing.
            if showStampCaption(temple: temple, meters: meters) {
                fireHonorHaptic(.honorWaterAhead)
            }
```
> `Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:208-215@e551b11`

```swift
    func showStampCaption(temple: WayMoment, meters: Double) -> Bool {
        guard let number = temple.templeNumber, let closes = way?.stampHours?.closesMinutes else { return false }
        showHonorCaption(WayStampNotice.caption(templeNumber: number, closesMinutes: closes, meters: meters))
        return true
    }
```
> `Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+MarkPins.swift:69-73@e551b11`

- **Caption slot.** Every caption goes through `showHonorCaption` (`ActiveWalkViewModel+Honor.swift:252-259`). It writes `softTapCaption`, then clears it after `softTapCaptionSeconds = 20` (`:472`) with an `asyncAfter` guarded by `honorGeneration`.
- **Early clear.** A newer caption doesn't cancel the older clear, so in principle an older clear can blank a newer caption early. On a stage this can't happen. The soft tap is off for any stage Way: `softTapEnabled: UserPreferences.honorSoftTapEnabled.value && !way.isPilgrimageStage` (`:57`). Only stage Ways carry marks or hours, and the 60 s gap outlasts the 20 s caption. Android's `flatMapLatest` over the latest notice row (`P/ui/walk/HonorWalkViewModel.kt:988-1011`) cancels the old clear instead. Nothing differs on screen.
- **No card.** A notice never adds a card (`PilgrimageStageWalkTests.swift:376-396`).

**Android split.** On Android the haptic is planned in `:tracker` (`Ritual.WaterHaptic`, `P/walk/honor/HonorSession.kt:545-554`, played at `:777`), while the caption is drawn in the UI from Room. The rule "no caption, no tap" therefore has to be checked in `planEvents`:
- Write a `STAMP` row and plan `Ritual.WaterHaptic` only when `event.temple.templeNumber` is non-null and the session's `way.stampHours` is non-null.
- Otherwise write and plan nothing.
- The UI's resolver repeats the same check as a second guard.
- Both checks read the same Way, the walk's staged copy (`HonorSession.kt:251-258`), so they can't disagree.

## UI / Visual

### Layout

| Container | Children | Alignment | Spacing |
|---|---|---|---|
| Minimized stats bar, third stat (`WalkStatsSheet.swift:409-422@e551b11`) | the caption `Text` while `softTapCaption` is set, else "Remaining" on an Honor walk | centred, `lineLimit(2)` | unchanged |
| Android `HonorMinimizedBar` third column (`P/ui/walk/WalkStatsSheet.kt:508-516`) | `Text(caption)` when `honor.caption` is non-null, else `StatColumn(remaining)` | `TextAlign.Center`, `maxLines = 2` | unchanged — no layout work |

Nothing else on screen changes: no new row, pin, card, banner or live readout.

### Dimensions

| Element | iOS value | Token | Notes |
|---|---|---|---|
| Caption line | inherits the third stat column | — | no new dimension |
| Distance thresholds in the caption | `1000` m; `0.1` mi; `3.28084` ft/m (`WayMomentHeader.swift:111-116@e551b11`) | magic numbers in `WayDistance`, unchanged | Android `WayRelation.distance` (`P/ui/honor/WayPlaceCardState.kt:299-313`) already matches, with `Locale.US` formatting |

### Colors

| Role | iOS source | Android Theme.kt mapping |
|---|---|---|
| Caption text | `.foregroundColor(.fog)` (`WalkStatsSheet.swift:414@e551b11`) | `pilgrimColors.fog` (`WalkStatsSheet.kt:512`), already in place |

### Typography

| Role | iOS | Android |
|---|---|---|
| Caption | `Constants.Typography.caption` (`WalkStatsSheet.swift:413@e551b11`) | `pilgrimType.caption` (`WalkStatsSheet.kt:511`), already in place |

### Motion

| Animation | iOS duration | Curve | Android equivalent |
|---|---|---|---|
| Caption life | `softTapCaptionSeconds = 20` (`ActiveWalkViewModel+Honor.swift:472@e551b11`) | none (set, then cleared) | `SOFT_TAP_CAPTION_MILLIS = 20_000L` (`P/ui/walk/HonorWalkViewModel.kt:1195`), measured from the row's `fired_at` |
| Haptic | `.honorWaterAhead`: one transient, intensity 0.4, sharpness 0.2, with a fallback to `UIImpactFeedbackGenerator(.soft)` (`HapticManager.swift:217-223,243-248@e551b11`) | — | the existing `Ritual.WaterHaptic` → `haptics.waterAhead()` → `impact(Impact.WATER)` (`P/audio/honor/HonorHaptics.kt:38`). No new pattern |

### Conditional render rules

- **Caption plus tap only with hours and a number.** The rule is at `ActiveWalkViewModel+MarkPins.swift:70@e551b11`: `guard let number = temple.templeNumber, let closes = way?.stampHours?.closesMinutes else { return false }`. It keeps the line and the haptic silent on every Way except a Shikoku stage.
- **The words.** They come from `WayMomentHeader.swift:127-130@e551b11`:

  ```swift
      static func caption(templeNumber: Int, closesMinutes: Int, meters: Double, locale: Locale = .current) -> String {
          let distance = WayDistance.string(meters: max(0, meters.isFinite ? meters : 0))
          return "temple \(templeNumber) stamps until \(hour(closesMinutes, locale: locale)) · \(distance)"
      }
  ```

- **The hour.** It comes from `WayMomentHeader.swift:136-145@e551b11`, unchanged since annex A.4.3:
  - A 12-hour clock shows `hour % 12`, and 0 shows as 12.
  - A 24-hour clock shows the hour unpadded, so 08:00 reads "8".
  - Minutes appear as `:%02d` only when they are non-zero. There is no meridiem marker.
  - The 12-hour test is whether the locale's `"j"` pattern contains `a`.
  - Android's production path reads `DateFormat.is24HourFormat(context)`; its tests use the ICU `"j"` pattern (annex A.4.3, A.11 item 2).
- **Caption precedence on Android.** The latest notice row wins: `caption = water ?: softTap?.let(HonorCaption::OffWay)` (`P/ui/walk/HonorWalkViewModel.kt:415`). The water flow generalises to a notice flow, and its `kind != WATER` early return (`:1008`) becomes a resolve by kind. `HonorCaption` (`:210-218`) gains the case its own comment foresees: "iOS PR #91's temple notice adds a case (Annex A.4.2)".

### Strings

| String (verbatim) | Android resource | Arguments |
|---|---|---|
| `temple %1$s stamps until %2$s · %3$s` | new `honor_stamp_caption`, beside `honor_water_caption` (`app/src/main/res/values/strings.xml:1542`) | Three strings formatted beforehand: the number as `Int.toString()`, the hour built with `Locale.US`, and the distance from `WayRelation.distance`. Strings, so an `ar` configuration can't print Arabic-Indic digits (annex A.4.3) |

`honor_soft_tap_caption` (`:1400`) and `honor_water_caption` (`:1542`) are unchanged. English is the only locale (`values/` alone carries these keys).

### Accessibility

The caption is read as the bar's third value. iOS sets `third = caption` (`WalkStatsSheet.swift:437-438@e551b11`). Android already does the same: `val third = caption ?: stringResource(R.string.honor_stats_a11y_remaining, remaining)` (`P/ui/walk/WalkStatsSheet.kt:462-463`). Neither platform makes a separate announcement or a live region.

## Data

### Entities

#### `route.json` → `RouteFile.StampHours` (wire)

| field | type | nullable? | default | source |
|---|---|---|---|---|
| `stampHours` | object `{opens, closes}` | yes; a wrong shape also reads as nil | nil | `PilgrimageWayImporter.swift:157-172,196@e551b11` |
| `stampHours.opens` | `"HH:mm"` string | no (when present) | — | `:158-161` |
| `stampHours.closes` | `"HH:mm"` string | no (when present) | — | `:158-161` |

**Android.** The wire model today, `P/data/honor/pilgrimage/PilgrimageModels.kt:224-246`, has no field, and the comment at `:127-138` says `stampHours` "is ignored". The comment and the model both change. A typed `StampHoursWire? = null` would throw on a mis-shaped value under `WIRE_JSON` (`P/data/honor/WayImporter.kt:307-310`, which leaves `isLenient` and `coerceInputValues` off). The field must instead be read in a way that turns any shape other than an object with two string members into null, while every other key stays strict.

#### `PilgrimageRoute.stampHours`

| field | type | nullable? | default | source |
|---|---|---|---|---|
| `stampHours` | `WayStampHours` | yes | nil | `PilgrimageWayImporter.swift:47-49@e551b11`, between `summary` and `stages` |

Android's `PilgrimageRoute` (`P/data/honor/pilgrimage/PilgrimageModels.kt:54-73`) gains it in the same position.

#### `WayStampHours`

| field | type | nullable? | default | source |
|---|---|---|---|---|
| `opensMinutes` | Int, minutes since midnight | no | — | `Way.swift:160-163@e551b11` |
| `closesMinutes` | Int, minutes since midnight | no | — | `Way.swift:160-163@e551b11` |

Only `closesMinutes` is read, by the engine and by the caption. `opensMinutes` is parsed, validated and stored, but nothing reads it.

#### `Way.stampHours`

| field | type | nullable? | default | source |
|---|---|---|---|---|
| `stampHours` | `WayStampHours` | yes | nil (key omitted) | `Way.swift:245-249@e551b11`, last property |

It encodes as `"stampHours": {"closesMinutes": 1020, "opensMinutes": 480}` under sorted keys. An older `way.json` decodes as nil (`PilgrimageWayImporterTests.swift:371-389`). Android's `Way` (`P/domain/honor/Way.kt:268-284`) ends at `val stage: WayStage? = null`; the new property goes after it, defaulting to null. `WayJson` already omits nulls and ignores unknown keys.

#### `WayMoment.templeNumber` (derived, never persisted)

| field | type | nullable? | default | source |
|---|---|---|---|---|
| `templeNumber` | Int | yes | nil | `Way.swift:108-113@e551b11`, read from `text` (`P/domain/honor/Way.kt:107` holds `val text: String? = null`) |

#### Tracker state: iOS in memory, Android persisted

| State | iOS `e551b11` | Android `0ee2872` (schema 12) | What 2.1.0 needs |
|---|---|---|---|
| fired water marks | `firedMarks` (`HonorMomentTracker.swift:63`) | `WATER` rows in `honor_notices` (`P/walk/honor/HonorSessionState.kt:191`) | unchanged |
| fired temples | `firedStamps` (`:79`) | — | `STAMP` rows: `ref_id` = the moment id. `kind` is stored as its name (`P/data/Converters.kt:73-77`), so adding the enum value needs **no migration** |
| water's hour clock | `lastWaterSeconds` (`:67`) | `last_notice_seconds` (`P/data/honor/HonorSessionEntity.kt:127-128`). Only water writes it today, so its value **is** `lastWaterSeconds` | see Open question 1 |
| temples' ten-minute clock | `lastStampSeconds` (`:81`) | — | see Open question 1 |
| shared 60 s gap clock | `lastNoticeSeconds` (`:73`) | same column, same name, but a different meaning from iOS's | see Open question 1 |
| the caption | `softTapCaption`, 20 s | the latest row (`P/data/honor/HonorDao.kt:213-215`), while `0 ≤ now − fired_at < 20 s` | a `STAMP` row resolved against the loaded Way |
| office, `fudasho` | immutable, from the Way at init | — | rebuilt on construction and on revival |

The tracker's `Snapshot` (`P/domain/honor/HonorMomentTracker.kt:51-56`) gains `firedStamps`, `lastWaterSeconds` and `lastStampSeconds`. `restore` (`:182-194`) filters `firedStamps` to `fudasho` ids. A restored null stays null and must never become 0, because nil means "free".

### Persistence ops

- **Launch restamp.** `restampStageHours` runs at `PilgrimagePackageManager.swift:156-170@e551b11`. On iOS the dispatcher is Main (`AppDelegate.swift:190@e551b11`). On Android the pass runs on IO under the package manager's actor (`onActor`, `P/data/honor/pilgrimage/PilgrimagePackageManager.kt:240`), with no `offActor` inside. The actor-held `isDownloading` (`:174`) is the stand-aside check. `inFlightTemp` is set from a download's entry until its `finally` (`:266-271`), so it covers the commit's `offActor` window too.
- **Download staging.** `StagePlan` carries `stampHours` (`PilgrimagePackageManager.swift:358-367@e551b11`). Android's `StagePlan` (`P/data/honor/pilgrimage/PilgrimagePackageManager.kt:546`) and its construction (`:293`) gain the field, and `stageOneStage`'s import call (`:341`) passes it. The commit lands the staged text unchanged through `saveStage(id, wayJson)` (`:158`, `:376`), so nothing else changes.
- **Restamp writes.** They go through the same `saveStage` seam, encoded with `WayJson.encode(way)`. `WayStore.saveEncoded` (`P/data/honor/WayStore.kt:133-142`) keeps an existing `accepted.json`. The tiles' corridor hash covers geometry only (`P/data/honor/pilgrimage/PilgrimageTilesCorridor.kt:48-62`), so a restamped stage still matches its saved map region.
- **Notice write.** It happens in `:tracker` (`P/walk/honor/HonorSession.kt:545-554`), in the same commit as the engine state, before the haptic ("persist before ritual"). Insert with `IGNORE` (`P/data/honor/HonorDao.kt:206-208`). Finalize already deletes every notice row (`:242-243`).

### Network endpoints

| Endpoint | Method | Source | In CLAUDE.md ecosystem? |
|---|---|---|---|
| — (none new; `stampHours` rides the existing `route.json` package fetch) | — | `PilgrimagePackageManager.swift:206@e551b11` | yes |

### File I/O paths

| Path | Op | Source |
|---|---|---|
| `pilgrimage/<route>/route.json` | read, by `installed()`, and since `7af5adc` read leniently | `PilgrimagePackageManager.swift:90-98@e551b11` via `AppDelegate.swift:192` |
| `<stageWayId>/way.json` | write (atomic) by the launch pass, only when the hours differ | `PilgrimagePackageManager.swift:160-163@e551b11`, `WayStore.swift:97@e551b11` |
| `<stageWayId>/accepted.json` | untouched when present | `WayStore.swift:98-101@e551b11` |
| `temp/<index>.way.json` | write, at staging, carrying the hours | `PilgrimagePackageManager.swift:387@e551b11` |

### DataStore / UserDefaults keys

| key | type | source |
|---|---|---|
| — (none; the stamp state lives in the Way and, on Android, in Room) | — | `HonorTuning.swift:32-50@e551b11` (constants only) |

## Edge cases & invariants

| citation | quote | invariant | why-could-Android-miss |
|---|---|---|---|
| **[consensus]** `Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:213-215@e551b11` | `if showStampCaption(temple: temple, meters: meters) {` | The tap comes only with its caption | Android plans the haptic in `:tracker` while the caption renders in the UI; the check must move into `planEvents` |
| **[consensus]** `Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:252-258@e551b11` | `DispatchQueue.main.asyncAfter(deadline: .now() + Self.softTapCaptionSeconds)` | There is one caption slot, with a 20 s life, and teardown makes the clear a no-op | Android derives it from `fired_at`; a revival must never replay an expired caption |
| **[consensus]** `Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+MarkPins.swift:70@e551b11` | `guard let number = temple.templeNumber, let closes = way?.stampHours?.closesMinutes else { return false }` | The caption re-reads the number and the closing time from the Way, not from the event | An Android caption built from the row alone would have no closing time |
| **[consensus]** `Pilgrim/Scenes/Honor/WayMomentHeader.swift:128-129@e551b11` | `max(0, meters.isFinite ? meters : 0)` · `"temple \(templeNumber) stamps until … · \(distance)"` | Lowercase, a U+00B7 middle dot with a space either side, the distance clamped at 0 | A capital letter, a bullet, or "closes at" wording would drift |
| **[consensus]** `Pilgrim/Scenes/Honor/WayMomentHeader.swift:139-144@e551b11` | `hour % 12 == 0 ? 12 : hour % 12` · `fromTemplate: "j"` | Noon reads 12, the hour is never padded, and there is no AM/PM | A naive formatter prints "5:00 PM" or "17:00" |
| **[consensus]** `Pilgrim/Models/Honor/HonorEngine.swift:100-103@e551b11` | `guard let hours = way.stampHours else { return nil }` · `?? .current` | With no hours there is no office; an invalid or missing zone falls back to the phone's | `ZoneId.of` throws; wrap it as `WayMomentCopy.wayZone` does (`P/ui/honor/WayMomentHeader.kt:155-162`) |
| **[consensus]** `Pilgrim/Models/Honor/HonorMomentTracker.swift:43@e551b11` | `minutesNow >= closesMinutes - … && minutesNow < closesMinutes` | A half-open window `[closes−120, closes)`, with `opens` ignored | "Fixing" it with an `opens` check or a midnight wrap would diverge |
| **[consensus]** `Pilgrim/Models/Honor/HonorTuning.swift:36-50@e551b11` | `noticeGapSeconds … 60` · `stampNoticeMinutes = 120` · `stampMinAheadMeters = 1000.0` · `stampQuietSeconds … 600` | The four literals; every seconds value is engine time | The annex had only two; a port from it misses the gap and the ten minutes |
| **[consensus]** `Pilgrim/Models/Honor/PilgrimageWayImporter.swift:196@e551b11` | `stampHours = try? container.decodeIfPresent(StampHours.self, forKey: .stampHours)` | A mis-shaped value costs the hours, never the route or `installed()` | A typed kotlinx field throws, `installed()` reads null, and the launch tile sweep then removes every saved map ("With nothing installed, every saved map goes", `P/data/honor/pilgrimage/PilgrimageTilesManager.kt:422-428`) |
| **[consensus]** `Pilgrim/Models/Honor/PilgrimageWayImporter.swift:389-399@e551b11` | `parts.count == 2, parts[0].count == 2, parts[1].count == 2` | Strict "HH:mm"; either side unreadable drops the pair whole | A `toIntOrNull` accepts non-ASCII digits that Swift refuses (annex A.1.2) |
| **[consensus]** `Pilgrim/Models/Honor/Way.swift:249@e551b11` | `var stampHours: WayStampHours?` | Optional and last, so an older `way.json` decodes as nil | A non-defaulted Kotlin property fails on 2.0.0's stage files |
| **[consensus]** `Pilgrim/Models/Honor/PilgrimagePackageManager.swift:157-168@e551b11` | `guard !isDownloading, let hours = installed.route.stampHours else { return }` · `way.stampHours != hours else { continue }` | Stand aside while downloading; compare, then save; per-stage failure tolerated; never clear | An `offActor` inside the pass, or saving every stage, or aborting the loop on the first error, each breaks one of these |
| **[consensus]** `Pilgrim/Models/Honor/HonorMomentTracker.swift:141-142@e551b11` | `actions += stampAhead(…)` then `actions += waterAhead(…)` | The temple wins a tie | Reordering lets the fountain take the line |
| **[consensus]** `Pilgrim/Models/Honor/HonorMomentTracker.swift:179-187@e551b11` | `isLineFree(at: activeSeconds)` · `if let last = lastWaterSeconds` | Water reads the gap, then its own hour, and writes both | Android's water watcher reads `lastNoticeSeconds` as its hour today (`P/domain/honor/HonorMomentTracker.kt:211-212`) |
| **[consensus]** `Pilgrim/Models/Honor/HonorMomentTracker.swift:208-219@e551b11` | `if let last = lastStampSeconds, … < HonorTuning.stampQuietSeconds` · `lastStampSeconds = …` · `lastNoticeSeconds = …` | The temple reads the gap, then its ten minutes, and writes both | One shared clock brings annex D6 back |
| **[consensus]** `Pilgrim/Models/Honor/HonorMomentTracker.swift:97@e551b11` | `stamp == nil ? [] : self.moments.filter { $0.templeNumber != nil }` | `fudasho` is in frac-then-id order, and empty without an office | An unsorted list changes which temple is "nearest" |
| **[consensus]** `Pilgrim/Models/Honor/HonorMomentTracker.swift:214-216@e551b11` | `guard ahead >= … else { continue }` · `guard !firedStamps.contains(temple.id) else { return [] }` | Skip temples that are near or behind; a named temple ends the scan | Writing `continue` for the fired check would name the next temple at once |
| **[consensus]** `Pilgrim/Models/Honor/Way.swift:109-111@e551b11` | `prefix { $0.isASCII && $0.isNumber }` · `number > 0` | `"Temple "` followed by 1–4 ASCII digits, greater than 0; never the `seal` icon | `Char.isDigit()` accepts Unicode digits, and an icon check would fire on 48 non-temple seals |
| `Pilgrim/Scenes/ActiveWalk/WalkStatsSheet.swift:437-438@e551b11` | `third = caption` | The caption is read in the bar's value, with no live region | Android already matches; a "helpful" `liveRegion` would diverge |
| `Pilgrim/Models/Honor/HonorMomentTracker.swift:33-36@e551b11` | `Deliberately reads the closing time alone, on the same day` | Matches the data (every value is 08:00–17:00) | A port must not "fix" it |
| `Pilgrim/AppDelegate.swift:189@e551b11` | `guard NSClassFromString("XCTestCase") == nil else { return }` | The launch pass never runs in tests | Android's injected `packageLaunchWork` (`P/walk/honor/HonorFinalizer.kt:117`) is the equivalent; keep the pass behind it |
| `Pilgrim/AppDelegate.swift:190-197@e551b11` | `Task { @MainActor in` | One launch read feeds the reconcile, then the restamp | Ported verbatim it would do disk I/O on the main thread |
| `Pilgrim/Models/Honor/WayStore.swift:98-101@e551b11` | `if !fileManager.fileExists(atPath: accepted.path) {` | A restamp keeps `acceptedAt` | `saveEncoded` already keeps it (`P/data/honor/WayStore.kt:137-141`); a new writer must not bypass it |
| `Pilgrim/Models/Honor/HonorMomentTracker.swift:226-229@e551b11` | `guard let last = lastNoticeSeconds else { return true }` · `>= HonorTuning.noticeGapSeconds` | The first notice is free, and exactly 60 s is free | Defaulting a clock to 0 imposes a quiet period at Begin |
| `UnitTests/Honor/PilgrimagePackageManagerTests+Lifecycle.swift:291-296@e551b11` | `guard case .downloading = manager.phase else { XCTFail(…) }` | The in-flight test proves its precondition, or it fails | Android should hold the download open with a latch rather than a timing guess |

## Android implementation notes

**Reuse rather than invent:**

- **Notices table.** `P/data/honor/HonorNoticeEntity.kt:14` holds `enum class HonorNoticeKind { WATER, UNKNOWN }`. Add `STAMP`; the lenient name converter (`P/data/Converters.kt:73-77`) already maps unknown names to `UNKNOWN`. The header comment (`:9-24`) is rewritten for two kinds.
- **Fired-set rebuild.** `P/walk/honor/HonorSessionState.kt:165-193` (`engineSnapshot`) rebuilds `firedMarks` from `WATER` rows; `firedStamps` rebuilds the same way from `STAMP` rows. `toEngineState` (`:196-215`) writes the clocks.
- **Notice write.** `P/walk/honor/HonorSession.kt:545-554`, the `MarkAhead` arm, is the template for the `StampAhead` arm: the same `plan.notices`, the same `Ritual.WaterHaptic`, plus the guard from the Behavior section.
- **Caption plumbing.** In `P/ui/walk/HonorWalkViewModel.kt`, `HonorCaption` (`:210-218`), the combine (`:411-415`) and `waterCaption` / `noticeCaption` / `waterCaptionAt` (`:988-1011`) generalise to notices. `HonorCaptionCopy.text` (`P/ui/walk/WalkStatsSheet.kt:422-436`) gains the `Stamp` arm.
- **Copy helpers.** `P/ui/honor/WayMomentHeader.kt` holds `WayStageLine` (`:173`) and `WayMomentCopy.wayZone` (`:155-162`); `WayStampNotice` goes beside them. The distance comes from `WayRelation.distance` (`P/ui/honor/WayPlaceCardState.kt:299-313`).
- **Package manager.** `P/data/honor/pilgrimage/PilgrimagePackageManager.kt` provides `runAtLaunch` (`:231-235`), the `saveStage` seam (`:158`), `isDownloading` (`:174`) and `onActor` (`:240`). Its doc comment at `:227-229` already names "#91's restamp".
- **Launch hook.** `P/walk/honor/HonorFinalizer.kt:272-278`. Its `deferringFailure` (`:165-173`) already rethrows `CancellationException`; the per-stage catch inside the pass must rethrow it too.
- **Engine.** `P/domain/honor/HonorEngine.kt:54-60` takes `clock: Clock`. Add the fallback zone as a constructor parameter defaulting to `ZoneId.systemDefault()` (annex A.11 item 5), and add `stamp` to the tracker construction (`:96-102`).

**Fold-in checklist, by layer.** This replaces annex A.10.

1. **Domain** (`P/domain/honor/`):
   - `HonorTuning.kt` gains the four constants.
   - `Way.kt` gains `WayStampHours`, `Way.stampHours` (last, defaulting to null) and `WayMoment.templeNumber` (ASCII digits, `> 0`).
   - `HonorMomentTracker.kt` gains the following:
     - `StampOffice(closesMinutes, zone, clock)`;
     - `stamp: StampOffice? = null`;
     - `fudasho` and `firedStamps`;
     - `lastWaterSeconds` and `lastStampSeconds` beside the existing `lastNoticeSeconds`;
     - `isLineFree`, `stampAhead` before `waterAhead`, and `Action.StampAhead`;
     - the `Snapshot` and `restore` fields.

     The existing water watcher (`:209-223`) switches its hour to `lastWaterSeconds`, adds the gap guard, and writes both clocks. Its doc comments (`:88-93`, `:203-208`) stop calling `lastNoticeSeconds` water's clock.
   - `HonorEngine.kt` gains the office, `HonorEngineEvent.StampAhead` after `MarkAhead`, and the `toEvent` arm.
2. **Import** (`P/data/honor/pilgrimage/`):
   - `PilgrimageModels.kt`: the lenient `stampHours` read on `RouteFile`, and `PilgrimageRoute.stampHours`. The comment at `:127-138` drops `stampHours` from the ignored keys.
   - `PilgrimageWayImporter.kt`: `route(from:)` (`:120-150`) parses the hours with `minutesSinceMidnight` as annex A.1.2 specifies: split on `:` keeping empty parts, two parts of length 2, `^[+-]?[0-9]{1,2}$`, then the ranges. `way(from:…)` (`:80-111`) takes `stampHours: WayStampHours? = null` and sets it last.
3. **Package manager and launch:**
   - `StagePlan` gains `stampHours`, set from `fetched.route.stampHours`.
   - Add a `restampStageHours(installed)` on the actor, with the four rules from Behavior.
   - `HonorFinalizer.launchPilgrimage` calls it after `tiles.reconcile`, when the read found a route. It reuses the same `Installed` from `runAtLaunch`, as iOS reuses its one read.
4. **Session and Room:**
   - `HonorSession.planEvents` gains the `StampAhead` arm.
   - The clock persistence follows Open question 1.
   - The `HonorSessionEntity` and `PilgrimDatabase` docs (`HonorSessionEntity.kt:122-128`, `PilgrimDatabase.kt:383-388`) are rewritten, because `last_notice_seconds` stops being water's hour.
5. **UI:**
   - `HonorCaption.Stamp(templeNumber, closesMinutes, meters)` is resolved from the latest `STAMP` row against the loaded Way, or is null if either value is missing.
   - `honor_stamp_caption`.
   - `DateFormat.is24HourFormat(context)` is read at draw time.
6. **Golden harness** (`app/src/test/resources/honor/golden/`):
   - `capture/capture.sh:10`: `PIN=7c200bf` moves to 2.1.0's pin. The six copied files are identical at `e551b11` and `3b78728`.
   - `capture/main.swift:243` reads `field(tracker, "lastMarkSeconds", …)`, which `fatalError`s at `e551b11`. Change the label to `"lastNoticeSeconds"`, which exists at `HonorMomentTracker.swift:73@e551b11`. In every water-only trace it equals `lastWaterSeconds`, since only water writes either clock. The JSONL key stays `lastNoticeSeconds`, as `main.swift:115-120` planned, so `expected/*.jsonl` keeps its bytes.
   - The event switch (`main.swift:202`) needs a `.stampAhead` arm before it compiles.
   - Record `firedStamps`, `lastWaterSeconds` and `lastStampSeconds` only when the Way has `stampHours`. Then the water traces' records keep their bytes.
   - Android's `T/domain/honor/HonorGoldenTraceTest.kt:506-507` keeps reflecting `lastNoticeSeconds` and adds the same three for temple traces.
   - The README's Pin and Captured rows move with the pin.

**Why the existing traces stay byte-identical** (by argument, unverified):
- With no `stampHours`, `stampAhead` returns at its first guard. With no temples, `lastWaterSeconds` always equals `lastNoticeSeconds`.
- Water's hour check passing (a gap of at least 3,600 s) implies the line is free (at least 60 s).
- So every water and own-walk trace behaves exactly as at `7c200bf`. The recapture confirms this.

**Temple traces.** These replace annex A.6.3. All are synthetic, under `TZ=UTC`, with Android pinning `ZoneId.of("UTC")` through the engine's zone parameter.

| Trace | What it pins | vs A.6.3 |
|---|---|---|
| `temple-window-opens-34n` | silent before 15:00:00, fires on the first on-way fix at or after it, and never again | unchanged |
| `temple-skips-the-near-one-34n` | temple 10 at 600 m and temple 11 at 6 km: temple 11 is named, and temple 10 is reached and never announced | unchanged |
| `temple-and-water-share-the-minute-34n` | A temple and a fountain are both due on one fix: the temple fires. The fountain is silent at +30 s and speaks at +60 s. A second fountain inside water's hour stays silent; a fountain spoken just before 15:00 doesn't hold the temple past its minute | **replaces** `…-share-the-hour-34n` |
| `temple-named-holds-the-next-34n` | Temple 10 is named 3 km out. Temple 11 is held while temple 10 is more than 1 km ahead, even after 600 s, and speaks once the walker is inside 1 km of temple 10 | **new** (from `c8e2d35`) |
| `temple-last-hours-34n` | Temple 10 at 16:10; temple 11 at 16:20, once temple 10 is decided; at 17:00 an unfired temple 12 is silent (the `<` at the close) | **replaces** `temple-closes-34n`, whose "held through 16:59" is void |
| `temple-off-way-34n` | No notice off the way, and none of the three clocks moves; the first on-way fix after the rejoin fires | unchanged, plus three clocks |
| `temple-way-zone-34n` | The Way's `tzIdentifier` `"Asia/Tokyo"`, with `t0` 06:00Z: it fires | unchanged |

**Test inventory.** The annex's A.8 code blocks still apply, with these deltas. iOS adds 31 tests and 1 assertion in total.

- **A.8.1 tracker**: 8 tests become 14 (`HonorMomentTrackerTests.swift:274-490@e551b11`).
  - Two existing tests changed. `testEachTempleSpeaksOnce`'s second fix moves from 60 s to 600 s. `testTheTempleTakesTheLineAheadOfTheFountain` now uses `fountain(atKm: 1.25)` and adds the +30 s silent and +60 s fountain fixes.
  - Six tests are new (rows 2–7 of the Behavior table).
  - There are two new helpers: `stampTracker(…, clock:)` (`:256-262`) and `fountain(atKm:)` (`:264-267`).
  - The Android target is `T/domain/honor/HonorMomentTrackerTest.kt`.
- **A.8.2 rendering**: one test added, `testTempleZeroIsNotANumberedTemple` (`HonorWayRenderingTests.swift:177-187`). The Android target is `T/domain/honor/WayMomentTest.kt`, next to the other `templeNumber` cases.
- **A.8.3 stage walk**:
  - `hapticAttempts` counts every tap the walk tried to give (`PilgrimageStageWalkTests.swift:39,60-65`).
  - The first test asserts exactly 1; the renamed `testAWayWithoutStampHoursPutsNothingOnTheLineAndTapsNothing` asserts 0 (`:376-412`).
  - On Android, the haptic assertions belong in `T/walk/honor/HonorSessionTest.kt`: one `Ritual.WaterHaptic` and one `STAMP` row, or none and none. The caption assertions belong in `T/ui/walk/HonorWalkViewModelTest.kt`.
- **A.8.4 importer**: two tests added. `testAMisShapedStampHoursLeavesTheRouteWalkableAndSilent` (`PilgrimageWayImporterTests.swift:325-340`) has six blocks. `testARequiredMemberMisShapedIsStillNotWalkable` (`:344-351`) checks that `"stageCount": "2"` still throws.
  - **Drop** the Android-only edge test the annex proposed, which expected a structurally wrong `stampHours` to make the route `NotWalkable`; iOS's own test now asserts the opposite. It was never written on Android.
  - Keep the `"+8:00"` → 480 and `"٠٨:٠٠"` → nil edge tests.
  - Rewrite `T/data/honor/pilgrimage/PilgrimageWayImporterTest.kt:866-880`, "…stampHours decode, the keys ignored". For the stage file the key stays ignored; for the route file it is now read.
- **A.8.5 package manager lifecycle (new)**: six tests (`PilgrimagePackageManagerTests+Lifecycle.swift:164-316`), going to `T/data/honor/pilgrimage/PilgrimagePackageManagerLifecycleTest.kt`:
  - a mis-shaped `stampHours` still reads as installed;
  - a 2.0.0-shaped install learns the hours at launch, with nothing else changed: the Way minus its hours, the corridor hash, `acceptedAt`, the replies, the walk link, and the release;
  - a second pass writes nothing;
  - a route with no hours is left alone;
  - one refused stage leaves the rest stamped;
  - the pass stands aside while a download is in flight (hold the Update open with a latch).
- **Android-only additions:**
  - `HonorFinalizerTest`: the restamp runs after the reconcile, from the same read, and not when nothing is installed.
  - The download test: every staged stage carries the route's hours (annex A.11 item 6).
  - Session revival restores `firedStamps` and all three clocks.
  - A cached `:tracker`'s second walk starts with no fired stamps.
  - A non-stage Way never builds an office.
  - If Open question 1 goes to schema 13, a `PilgrimDatabaseMigrationTest` case for 12→13.

**Android additions to record at the gate.** These extend annex A.11:

8. The launch hand-down runs on IO, under the package actor, not on the main thread.
9. **A live walk is unaffected by the restamp.** A stage walk reads the copy staged under the walk at Begin (`P/walk/honor/HonorSession.kt:240-258`), so a restamp at UI launch while `:tracker` is walking changes nothing for that walk. iOS can't be in this state, because it never resumes a walk.
10. **The tap can lead the caption on screen.** The caption appears when Room emits the `STAMP` row in the UI process, a beat after `:tracker`'s haptic. This is the same ordering water already has.

**Housekeeping.** `CLAUDE.md:15` and `:89` still say #91 rides Android 2.0.1. The 2.1.0 plan updates both, along with the parity line.

## Lens disagreements

- **`locale-trap` at `WayMomentHeader.swift:144@e551b11`** (edge-cases only). The lens says iOS "uses locale, not the user toggle". Annex A.4.3 probed the opposite: `Locale.current` carries the 24-Hour Time switch, and `en_US@hours=h23` gives `HH`. So the switch wins over the region on iOS. Android's production mapping to `DateFormat.is24HourFormat(context)` stands. Why it matters: following the lens would ignore the walker's own setting.
- **`async-delay` at `ActiveWalkViewModel+Honor.swift:249-259@e551b11`** (edge-cases; behavior and ui-visual note it softly). The lens says "a soft tap within 20 s of a notice can still be blanked early; Android must replicate (or consciously fix)". That can't happen: the soft tap is off on every stage (`:57`), and only stages carry notices. No Android work follows from it.
- **`magic-number` at `PilgrimageWayImporter.swift:394-399@e551b11`** (data and edge-cases). Both mention Swift's `Int("+5")`, and edge-cases says Android's `toIntOrNull` "also shares" it. That is true of the sign. It misses the divergence that matters: Kotlin accepts non-ASCII digits and Swift refuses them (annex A.1.2, probed). Why it matters: a bare `toIntOrNull` passes the `+` test and fails the parity one.
- **`dispatcher-on-disk-op` at `AppDelegate.swift:190-197@e551b11`** (data only). iOS does the restamp's disk I/O on the main actor. Why it matters: it is the one place where a verbatim port would be an ANR risk on Android.
- **`accessibility` at `WalkStatsSheet.swift:436-441@e551b11`** (ui-visual only). The caption is read as the third value, with no announcement of its own. Android already matches, so there is nothing to do.
- **`motion` at `HapticManager.swift:217-223,243-248@e551b11`** (ui-visual only). The stamp tap is the water tap. Android already routes both through `Ritual.WaterHaptic`. Why it matters: a new pattern would be the drift.
- **`idiomatic-trick` at `AppDelegate.swift:189@e551b11`** (edge-cases only). The lens says Android "needs an equivalent guard". Android's injected `packageLaunchWork` already is that guard (`P/walk/honor/HonorFinalizer.kt:117`), so the code settles it.
- **`async-delay` at `PilgrimagePackageManagerTests+Lifecycle.swift:276-300@e551b11`** (edge-cases only). The iOS test proves its in-flight precondition by timing (0.4 s stub, 100 ms sleep) and fails loudly if the Update wins. Why it matters: an Android port should hold the Update open with a latch, so the test can't pass vacuously.

## Open questions

1. **How do the three clocks survive a `:tracker` death?** iOS keeps `lastWaterSeconds`, `lastStampSeconds` and `lastNoticeSeconds` (`HonorMomentTracker.swift:67,73,81@e551b11`). Schema 12 persists one REAL, `last_notice_seconds`, which today holds water's hour. The rows can't rebuild engine seconds, because `fired_at` is wall-clock time.
   - (a) **Schema 13**, additive. Add `last_water_seconds REAL` and `last_stamp_seconds REAL` to `honor_sessions`. `MIGRATION_12_13` copies `last_notice_seconds` into `last_water_seconds`; this is exact, since only water wrote it. `last_notice_seconds` keeps its name and becomes iOS's shared gap clock.
   - (b) **No migration.** Keep the one column as the shared gap. On revival, set the clock of the latest row's kind from it exactly, and set the other kind's clock to the same value. This is conservative: after a death, a fountain can be held up to an hour after a temple, or a temple ten minutes after a fountain. That is annex D6 again, but only after a death.

   Stage links need no schema change, so (a) would be 2.1.0's only migration. *Recommendation: (a).*
2. **Capture the temple golden traces for 2.1.0, or ship on the unit tests alone?** The capture needs a macOS `swiftc` run of `capture.sh` at 2.1.0's pin, plus the harness changes in the checklist. The unit tests pin each rule. The traces pin the three clocks together with real fix cadence, pauses and engine time, which is where a revival or reorder bug would show. *Recommendation: capture them, the seven listed above.*

> Spec written. Run `superpowers:writing-plans <path>` to break into tasks, or `jutsu swarm doc-review <path>` for a remote QA gate first.

## Owner decisions (2026-10-29)

1. **The clocks:** option (a), schema 13. It is additive.
   - Add `last_water_seconds` and `last_stamp_seconds` to `honor_sessions`.
   - `MIGRATION_12_13` copies `last_notice_seconds` into `last_water_seconds`.
   - `last_notice_seconds` becomes the shared gap clock.
2. **Golden traces:** none recorded. Unit tests only: the ported iOS tests plus Android's own. The existing golden traces must still pass unchanged.
3. **Release:** ships in **Android 2.1.0** with the stage-links port, at parity with iOS `main` at `3b78728`. The 2.0.1 target in `CLAUDE.md` is retired.
