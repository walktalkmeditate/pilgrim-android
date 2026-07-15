# Port spec — Goshuin seeking seals + journal walk-mode glyph (U12)

- **iOS anchor:** `pilgrim-ios` @ `c1745e88494d7677c4be8770ab6ceed1a61f3f6f` (c1745e8)
- **Source commits:** `d476bc3` (goshuin seals for seeking thresholds), `d7402d5`
  (journal quick-view glyph), `cee3a15` (fetch error handling), `3993b11`
  (primaryMilestone stable ordering + uuid tie-break). This spec ports the
  **end-state** at c1745e8, not the intermediate diffs.
- **Plan:** `docs/plans/2026-07-14-001-feat-seek-mode-journal-scenery-plan.md` § U12
- **Depends on:** U4 vocabulary (`SEEK_MODE` event type,
  `SeekPersistence.ARRIVAL_WAYPOINT_ICON = "sun.haze"`,
  `SeekPersistence.isArrivalWaypoint`) — see
  `docs/parity/2026-07-14-port-seek-persistence-u4.md`.
- **Scope boundary (parallel-unit fence):** no edits under `ui/walk/`,
  `domain/seek/SeekFog*`, or `PilgrimMap.kt` (U6/U11 own those). Consequence
  recorded in § 8.

## 1. New milestone cases

Swift (`Pilgrim/Scenes/Goshuin/GoshuinMilestones.swift:10-18@c1745e8`):

```swift
/// Seeking thresholds: the walk that carried the first found place,
/// and the walks whose arrivals crossed a lifetime count.
case firstUnknown
case unknownsFound(Int)
...
/// Lifetime found-place counts that earn a seal.
static let unknownThresholds = [10, 25, 50, 100]
```

Android: `GoshuinMilestone.FirstUnknown` (`data object`) and
`GoshuinMilestone.UnknownsFound(val count: Int)` (`data class`) in the existing
`@Immutable` sealed class; `GoshuinMilestones.unknownThresholds =
listOf(10, 25, 50, 100)`.

Labels (`GoshuinMilestones.swift:233-241@c1745e8`):

```swift
case .firstUnknown: return "First Unknown"
case .unknownsFound(let n): return "\(n) Unknowns"
```

Android `label()` follows the existing house pattern for goshuin captions
(hardcoded English baseline in `GoshuinMilestones.label`, not strings.xml —
same as "First Walk"/"Longest Walk"): `"First Unknown"`,
`"${count} Unknowns"`. Kotlin `Int` string templates emit ASCII digits in every
locale (no `String.format` locale trap).

## 2. `seekingMilestones` — the awarding math

Swift (`GoshuinMilestones.swift:75-89@c1745e8`):

```swift
/// Seeking milestones for a walk, from its own arrivals and the
/// lifetime count before it. Awarded to the walk that crosses the
/// threshold; a walk with no arrivals never earns one.
static func seekingMilestones(arrivalsInWalk: Int, arrivalsBefore: Int) -> Set<Milestone> {
    guard arrivalsInWalk > 0 else { return [] }
    var milestones: Set<Milestone> = []
    if arrivalsBefore == 0 {
        milestones.insert(.firstUnknown)
    }
    let total = arrivalsBefore + arrivalsInWalk
    for threshold in unknownThresholds where arrivalsBefore < threshold && total >= threshold {
        milestones.insert(.unknownsFound(threshold))
    }
    return milestones
}
```

Pinned semantics (ported verbatim):

- `arrivalsInWalk == 0` → empty set, **even when `arrivalsBefore == 0`** — a
  zero-arrival seek (or any wander walk) never earns FirstUnknown. This is the
  Stage 4-D equal-value-trap guard: detection must not fire on zero-arrival ties.
- `arrivalsBefore == 0` (and arrivals > 0) → `FirstUnknown`.
- Each threshold `t ∈ [10, 25, 50, 100]` with
  `before < t ≤ before + inWalk` → `UnknownsFound(t)`. A multi-arrival walk can
  cross several thresholds at once (0 → 26 earns FirstUnknown + 10 + 25);
  landing exactly on `t` still awards.

Android: `GoshuinMilestones.seekingMilestones(arrivalsInWalk: Int,
arrivalsBefore: Int): Set<GoshuinMilestone>` — public, pure; also the input
U14's `WalkThreshold.seeking` will reuse.

## 3. Counting source + bulk `arrivalCounts`

Swift (`GoshuinMilestones.swift:51-63@c1745e8`):

```swift
/// One waypoint-fault pass for the whole book: callers look up arrival
/// counts by uuid instead of re-faulting every prior walk's waypoint
/// relationship per seal cell (which was O(walks²) on the main thread).
static func arrivalCounts(for walks: [WalkInterface]) -> [UUID: Int] {
    var counts: [UUID: Int] = [:]
    for walk in walks {
        guard let uuid = walk.uuid else { continue }
        let count = walk.waypoints.filter(SeekPersistence.isArrivalWaypoint).count
        if count > 0 { counts[uuid] = count }
    }
    return counts
}
```

The counting source is **reserved-icon arrival waypoints** (`"sun.haze"`,
`SeekPersistence.isArrivalWaypoint`) — not `SEEK_ARRIVAL` events. Same source
feeds iOS's `SealInput.foundPlaceCount`
(`Pilgrim/Models/Seal/SealInput.swift:19-21,41@c1745e8`):

```swift
/// Seek arrivals recorded on this walk (reserved-icon waypoints), for
/// the seeking milestones.
let foundPlaceCount: Int
...
self.foundPlaceCount = walk.waypoints.filter(SeekPersistence.isArrivalWaypoint).count
```

Android split (Room has no in-memory waypoint relationships to walk over):

- `WaypointDao.iconsPerWalk()` — **one** query
  (`SELECT walk_id, icon FROM waypoints WHERE icon IS NOT NULL`), mirroring the
  `firstLatitudePerWalk` no-N+1 precedent. `WalkRepository.waypointIconsByWalk()`
  groups it into `Map<Long, List<String?>>`.
- `GoshuinMilestones.arrivalCounts(waypointIconsByWalk): Map<Long, Int>` — the
  pure counting pass: filters by `SeekPersistence.isArrivalWaypoint`, **omits
  zero counts** (`if count > 0` above). This is the single production counting
  path (no SQL-side duplicate of the predicate), keyed by Room walk id.
- `WalkMilestoneInput` gains `foundPlaceCount: Int = 0` — the direct analogue
  of `SealInput.foundPlaceCount` (`WalkMilestoneInput` is Android's SealInput
  DTO). Default 0 keeps every existing call site compiling and awarding nothing.

Key mapping divergence: iOS keys `arrivalCounts` by walk **UUID**; Android keys
by Room `walkId` (Long) because `WalkMilestoneInput`/`GoshuinSeal` already join
on `walkId` everywhere. The uuid still exists (`Walk.uuid: String`, non-null,
unique) and is used where iOS uses it — the ordering tie-break (§ 4).

## 4. `isOrderedBefore` — stable lifetime-prior ordering

Swift (`GoshuinMilestones.swift:65-73@c1745e8`):

```swift
/// Strictly-before ordering with a stable uuid tie-break, so two walks
/// sharing a startDate never both count as "before" each other (a
/// crossing seal would double-award) nor neither (it would vanish).
static func isOrderedBefore(
    _ lhsDate: Date, _ lhsID: String?,
    _ rhsDate: Date, _ rhsID: String?
) -> Bool {
    if lhsDate != rhsDate { return lhsDate < rhsDate }
    return (lhsID ?? "") < (rhsID ?? "")
}
```

Android: `GoshuinMilestones.isOrderedBefore(lhsStartMs: Long, lhsUuid: String,
rhsStartMs: Long, rhsUuid: String): Boolean`. Non-null `String` uuids because
`Walk.uuid` is non-null on Android (iOS CoreData uuid is optional, hence
`?? ""`). Identical-date + identical-uuid compares strictly false on both
platforms (strict ordering).

`arrivalsBefore` aggregation inside detect
(`GoshuinMilestones.swift:218-227@c1745e8`, SealInput overload — the end-state
Android mirrors):

```swift
if input.foundPlaceCount > 0 {
    let arrivalsBefore = allInputs
        .filter {
            $0.uuid != input.uuid
                && isOrderedBefore($0.startDate, $0.uuid, input.startDate, input.uuid)
        }
        .reduce(0) { $0 + $1.foundPlaceCount }
    milestones.formUnion(
        seekingMilestones(arrivalsInWalk: input.foundPlaceCount, arrivalsBefore: arrivalsBefore)
    )
}
```

Pinned: lifetime prior = **earlier-startDate walks excluding self** (never the
walk's own arrivals), ordered by `isOrderedBefore` on
(startTimestamp, uuid). Android filters on `it.walkId != walk.walkId` for the
self-exclusion (primary identity) and passes uuids to the tie-break.

## 5. `primaryMilestone` — deterministic caption selection (3993b11)

Swift (`GoshuinMilestones.swift:20-49@c1745e8`):

```swift
/// Caption order when a walk earns several milestones at once — Set
/// iteration is per-process, so without a stable priority the seal
/// caption and the share-image label shuffle between launches.
/// Once-ever moments outrank threshold crossings outrank recurring
/// and transient records; within threshold crossings the largest
/// count is the headline.
static func primaryMilestone(of milestones: Set<Milestone>) -> Milestone? {
    milestones.min { lhs, rhs in
        (displayPriority(lhs), -intraPriority(lhs)) < (displayPriority(rhs), -intraPriority(rhs))
    }
}

private static func displayPriority(_ milestone: Milestone) -> Int {
    switch milestone {
    case .firstWalk: return 0
    case .firstUnknown: return 1
    case .unknownsFound: return 2
    case .nthWalk: return 3
    case .firstOfSeason: return 4
    case .longestWalk: return 5
    case .longestMeditation: return 6
    }
}

private static func intraPriority(_ milestone: Milestone) -> Int {
    switch milestone {
    case .nthWalk(let n), .unknownsFound(let n): return n
    default: return 0
    }
}
```

Verified exact priority: FirstWalk > FirstUnknown > UnknownsFound >
NthWalk > FirstOfSeason > LongestWalk > LongestMeditation; within a
parameterized tier the **largest** count wins (`-intraPriority`). Selection is
independent of set-iteration order — this is the seal-caption nondeterminism
3993b11 fixed (iOS previously displayed `milestones.first` off Swift's
per-process hash order; `GoshuinPageView.swift:66@3993b11` and
`GoshuinShareRenderer.swift:99-100@3993b11` both switched to
`primaryMilestone`).

Android: `GoshuinMilestones.primaryMilestone(milestones: Set<GoshuinMilestone>):
GoshuinMilestone?` via `minWithOrNull(compareBy({ displayPriority(it) },
{ -intraPriority(it) }))`.

**Precedence realignment (intentional behavior change):** Android's Stage 4-D
`detect()` already returned a single deterministic milestone, but with its own
precedence (FirstWalk > LongestWalk > LongestMeditation > NthWalk >
FirstOfSeason) invented before iOS pinned one. The end-state port adopts iOS's
table above, so a walk that is both the 10th and the longest now captions
"10th Walk" (previously "Longest Walk"). The existing
`GoshuinMilestonesTest` precedence test is updated to the iOS table.

**Shape divergence:** iOS `detect(...)` returns `Set<Milestone>` and every
consumer at c1745e8 immediately reduces it — halo on `!isEmpty`
(`GoshuinPageView.swift:38@c1745e8`), caption + share label via
`primaryMilestone`. Android keeps `detect(...): GoshuinMilestone?` (build the
full set internally, return `primaryMilestone(set)`): same observable output on
every surface, zero churn for `GoshuinSeal.milestone` / summary-state
consumers. `primaryMilestone` stays public for direct testing and future
full-set callers.

## 6. Grid/share wiring (3993b11 view-layer)

iOS at c1745e8 computes `arrivalCounts` **once per book**
(`GoshuinView.swift:7-13@c1745e8` — "One waypoint-fault pass for the whole
book, computed at construction; seal cells read arrival counts by uuid") and
threads it into every `detect` call. Android equivalents:

- `GoshuinViewModel`: inside the existing `observeAllWalks().map { }` build,
  one `repository.waypointIconsByWalk()` read →
  `GoshuinMilestones.arrivalCounts(...)` → `foundPlaceCount` on each
  `WalkMilestoneInput`. Archived walks are already filtered out before
  detection (matches iOS `detect`'s `isArchivedWalk` early-return,
  `GoshuinMilestones.swift:168-171@c1745e8`).
- `GoshuinShareRenderer` (Android) consumes `GoshuinSeal.milestone`, which the
  VM already computed deterministically — iOS's
  `GoshuinShareRenderer.swift:99-100@3993b11` `primaryMilestone` switch is
  therefore structurally satisfied with no renderer change.

## 7. Journal quick-view glyph

### 7a. `WalkModeFootprints` (d7402d5)

Swift (`Pilgrim/Views/WalkModeFootprints.swift:3-31@c1745e8`):

```swift
/// Static miniature of the path screen's mode language, for compact rows
/// (the ink-scroll quick view). Wander: the grounded pair. Seek: one print
/// beside a trail of dots dissolving upward into the unknown. No animation —
/// these are glances, not scenes; the drifting versions live on the path
/// screen only.
struct WalkModeFootprints: View {
    let isSeek: Bool
    let color: Color

    var body: some View {
        HStack(spacing: 2) {
            FootprintShape()
                .fill(color)
                .frame(width: 10, height: 16)
                .scaleEffect(x: -1)
                .rotationEffect(.degrees(-12))
            if isSeek {
                dissolvingDots
                    .frame(width: 10, height: 18)
                    .rotationEffect(.degrees(12))
            } else {
                FootprintShape()
                    .fill(color.opacity(0.75))
                    .frame(width: 10, height: 16)
                    .rotationEffect(.degrees(12))
            }
        }
        .accessibilityHidden(true)
    }
```

Dot trail (`WalkModeFootprints.swift:33-55@c1745e8`) — 6 dots, x-jittered,
rising with shrinking radius and fading opacity:

```swift
let dots: [(x: CGFloat, y: CGFloat, r: CGFloat, a: Double)] = [
    (0.5, 0.85, 1.6, 1.0),
    (0.3, 0.65, 1.3, 0.85),
    (0.7, 0.55, 1.3, 0.7),
    (0.4, 0.38, 1.0, 0.5),
    (0.6, 0.20, 1.0, 0.35),
    (0.5, 0.05, 0.7, 0.22)
]
```

`x`/`y` are frame fractions; `r` is **points** (→ dp on Android); `a`
multiplies the passed color's opacity (`context.opacity`).

Android: `ui/home/WalkModeFootprints.kt` —
`WalkModeFootprints(isSeek: Boolean, color: Color, modifier)`:

- `Row(spacedBy(2.dp), CenterVertically)`; prints are 10×16 dp Canvas draws of
  the **shared** `ui/design/scenery/footprintPath` (same geometry the Path tab
  and ExpandCardSheet already use — no re-derived footprint); mirror via
  `scale(scaleX = -1f)`, rotations −12°/+12°.
- Wander second print: `color.copy(alpha = color.alpha * 0.75f)` (SwiftUI
  `.opacity(0.75)` multiplies).
- Seek trail: 10×18 dp Canvas rotated 12°, dots drawn with
  `alpha = dot.alpha` modulation (Compose `drawCircle(alpha=)` multiplies the
  color's own alpha, same as `context.opacity`), radius `r.dp.toPx()`.
- Static — no animation (explicit iOS doc: "these are glances, not scenes";
  the drifting versions live in `ui/path/PathFootprints.kt` only, which keeps
  its own 5-dot centered trail — that surface is `WalkStartView` parity, not
  this glyph).
- `Modifier.clearAndSetSemantics { }` = `.accessibilityHidden(true)` (house
  pattern: ActiveWalkScreen/ConstellationOverlay decorative canvases).
- Dot table lives in `internal fun walkModeTrailDots(): List<TrailDot>` (pure
  floats, JVM-testable — Stage 3-C rule: prove geometry via functions, not
  Robolectric draws).

### 7b. Call site

Swift (`Pilgrim/Scenes/Home/InkScrollView.swift:349-358@c1745e8`) — the
expanded quick-view card header; the seek/wander glyph replaced the single
static footprint for non-archived rows:

```swift
if isExpandedArchived {
    FootprintShape()
        .stroke(Color.fog, lineWidth: 1)
        .frame(width: 12, height: 18)
} else {
    WalkModeFootprints(
        isSeek: snapshot.isSeek,
        color: seasonColor.opacity(0.3)
    )
}
```

Android 1:1 surface: `ui/home/expand/ExpandCardSheet.kt` `HeaderRow` — its
existing `Canvas(12×18) { drawPath(footprintPath, seasonColor.copy(alpha =
0.3f)) }` is the exact analogue of the replaced iOS line. It becomes
`WalkModeFootprints(isSeek = snapshot.isSeek, color = footprintColor)`.
Pre-existing divergence, unchanged by U12: Android's ExpandCardSheet has no
archived (stroked-outline) header variant — archived styling lives on the
journal dot (`WalkDot`), so there is no `isExpandedArchived` branch to port.

### 7c. `WalkSnapshot.isSeek` + bulk fetch (d7402d5 + cee3a15)

Swift (`Pilgrim/Scenes/Home/HomeViewModel.swift:17,86,127,135-149@c1745e8`):

```swift
let isSeek: Bool
...
let seekWalkIDs = fetchSeekWalkIDs()
...
isSeek: walk.uuid.map(seekWalkIDs.contains) ?? false,
...
/// Seek walks are marked by their `.seekMode` event (origin R18). One
/// bulk fetch — the event count equals the seek-walk count — instead of
/// faulting every walk's event list while building snapshots.
private func fetchSeekWalkIDs() -> Set<UUID> {
    do {
        let events = try DataManager.dataStack.fetchAll(
            From<WalkEvent>().where(\._eventType == .seekMode)
        )
        return Set(events.compactMap { $0.workout?.uuid })
    } catch {
        print("[HomeViewModel] Failed to fetch seek events:", error.localizedDescription)
        return []
    }
}
```

Android:

- `WalkSnapshot` += `isSeek: Boolean = false` (primitive — `@Immutable`
  stability preserved).
- `WalkEventDao.walkIdsWithEvent(eventTypeName)` —
  `SELECT DISTINCT walk_id FROM walk_events WHERE event_type = :eventTypeName`
  (TEXT column stores the enum name per U4). One query for the whole journal;
  `WalkRepository.seekWalkIds(): Set<Long>` passes
  `WalkEventType.SEEK_MODE.name`. Keyed by Room walk id (iOS uses walk uuid;
  same identity join as § 3).
- `HomeViewModel.buildSnapshots` calls it once per emission;
  `isSeek = input.walk.id in seekWalkIds`. Note: `buildSnapshots` already
  faults each walk's events for pause math (`walkEventsFor`), but the seek
  flag deliberately rides the dedicated bulk query — iOS's own perf rule, and
  the same query U14's snapshot build reuses.
- Error handling (cee3a15 end-state, Android idiom): `try/catch` that
  **re-throws `CancellationException`**, logs via
  `android.util.Log.w("HomeViewModel", ...)` (the file's existing degrade
  idiom — seal-render catch blocks), and returns `emptySet()` so the journal
  still loads with wander glyphs. Surfaced (logged), not swallowed
  (`try?`-silent) — and not fatal to `journalState`, exactly matching iOS
  where a failed fetch degrades the glyph, never the journal.

## 8. Deliberate non-goals / divergences

- **`ui/walk/` untouched (U6 fence):** `WalkSummaryViewModel.detectMilestoneFor`
  keeps building `WalkMilestoneInput`s without `foundPlaceCount` (default 0),
  so the summary seal-reveal won't caption seeking milestones until U11/U13
  wire the counts there. The shared `detect()` signature already accepts them;
  the wiring is one map read. It does, however, inherit the iOS display
  precedence immediately (shared pure function).
- iOS `detect(walk:allWalks:arrivalCounts:)` WalkInterface overload
  (`GoshuinMilestones.swift:91-160@c1745e8`) is not ported — Android has a
  single DTO-based detect (its SealInput-overload analogue).
- Archived-walk lifetime priors: iOS's archived check only excludes the
  *detected* walk (`GoshuinMilestones.swift:168-171@c1745e8`); archived walks
  remain in `allInputs`, so their arrivals still count toward another walk's
  `arrivalsBefore`. Android's `GoshuinViewModel` filters archived walks out of
  the whole candidate list (pre-existing v1.6.0 structure — they're excluded
  from walkNumber/longest math too), so their arrivals don't count. Inherited,
  not introduced, by U12; a user who archives a seek walk sees the lifetime
  count drop on Android but not on iOS. Kept for internal consistency with
  every other milestone input.
- No new strings.xml entries: goshuin captions are hardcoded-English by house
  decision (localization re-route is Stage 10's).
- `PathFootprints.kt`'s 5-dot animated trail is intentionally NOT unified with
  the 6-dot static glyph trail — they are different iOS sources
  (`WalkStartView.footprintForMode` vs `WalkModeFootprints`).
- Journal **row dots** don't change; the glyph appears only in the expanded
  quick-view header, mirroring where iOS placed it in `InkScrollView`.
- **Trail-dot radii scaled 0.6× at draw time** (added 2026-07-15, device QA):
  the element layout, dot table, positions, and alphas were verified as a
  verbatim iOS port (SwiftUI `ImageRenderer` of the c1745e8 source vs the
  Android draw math render pixel-equivalently), yet on-device the verbatim
  1.6/1.3/1.0/0.7dp radii read as "a few circles" on Android's flat parchment
  card — iOS composites the same table over a blurred `.ultraThinMaterial`
  card, which softens the dots into the intended dissolving trail. Android
  keeps the table verbatim and applies `TRAIL_DOT_RADIUS_SCALE = 0.6f` to the
  drawn radius only (`WalkModeFootprints.kt`), chosen by A/B renders at
  1.0/0.75/0.6/0.5 — 0.6 is where the trail stops reading as discrete circles
  while the fade stays visible.

## 9. Test parity map

| iOS test (`UnitTests/GoshuinMilestonesTests.swift@c1745e8`) | Android |
|---|---|
| `testFirstUnknown_awardedToTheWalkWithTheFirstArrival` | `seekingMilestones - first arrival walk earns FirstUnknown not a crossing` |
| `testNoArrivals_earnsNothing` | `seekingMilestones - zero arrivals earn nothing` (+ zero/zero tie guard) |
| `testThresholdCrossing_awardedOnceToTheCrossingWalk` | `seekingMilestones - 8 to 12 crossing earns exactly UnknownsFound(10)` + after-crossing negative |
| `testExactLanding_onThreshold_stillAwards` | `seekingMilestones - exact landing on threshold awards` |
| *(0 → 26 multi-cross — implied by loop semantics)* | `seekingMilestones - 0 to 26 earns FirstUnknown plus 10 and 25` |
| `testSeekingLabels` | `label - seeking captions` |
| `testPrimaryMilestone_isStableAndRanked` | `primaryMilestone - ranked` + explicit shuffled-iteration-order determinism test |
| `testDetect_firstUnknown_goesToTheEarliestArrivalWalk` | `detect - FirstUnknown goes to the earliest arrival walk` |
| `testDetect_ownArrivalsNeverCountAsBefore` | `detect - own arrivals never count as before` |
| `testDetect_identicalStartDates_awardFirstUnknownExactlyOnce` | `detect - identical startDates award FirstUnknown exactly once` (uuid tie-break) |
| *(arrivalCounts one-pass, drop-zero)* | `arrivalCounts - counts reserved icon only, omits zero-count walks` |
| *(glyph geometry — untested on iOS)* | `WalkModeFootprintsTest` — 6 dots, rising y, shrinking radius, fading alpha, iOS value pins |
| *(isSeek bulk fetch — untested on iOS)* | `HomeViewModelJournalTest` — SEEK_MODE-only flagging via real Room; failure degrades to wander without killing the journal |
