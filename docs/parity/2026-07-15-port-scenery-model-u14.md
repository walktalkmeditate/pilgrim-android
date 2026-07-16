# Port spec — Data-driven scenery model: gates, cairns, drift (U14)

- **iOS anchor:** `pilgrim-ios` @ `c1745e88494d7677c4be8770ab6ceed1a61f3f6f` (c1745e8)
- **Source commits:** `b1f7638` ("the scroll remembers — gates, cairns, real
  moons, lit lanterns"), `35dcea8` ("two kinds of gates, and drift"),
  `1d94f5d` (cairn growth). This spec ports the **end-state** of the model
  layer at c1745e8, not the intermediate diffs. Rendering (CairnStonesShape,
  drift faces, moon phase, gate tints/moss) is U15; parallax/age/haptics
  is U16.
- **Plan:** `docs/plans/2026-07-14-001-feat-seek-mode-journal-scenery-plan.md` § U14
- **Depends on:** U4 vocabulary (`SEEK_MODE`, `SeekPersistence.isArrivalWaypoint`
  — `docs/parity/2026-07-14-port-seek-persistence-u4.md`) and U12 math
  (`GoshuinMilestones.seekingMilestones` / `arrivalCounts` / `isOrderedBefore`,
  `WalkRepository.waypointIconsByWalk()`, `WalkRepository.seekWalkIds()` —
  `docs/parity/2026-07-14-port-seek-goshuin-journal-u12.md`).

## 1. Decision order — "meaning outranks the lottery"

Swift (`Pilgrim/Models/SceneryGenerator.swift:93-129@c1745e8`):

```swift
static func scenery(for snapshot: WalkSnapshot) -> SceneryPlacement? {
    let seed = deterministicSeed(for: snapshot)
    let roll3 = seededRandom(seed: seed, salt: 3)
    let side: ScenerySide = roll3 < 0.5 ? .left : .right
    let roll4 = seededRandom(seed: seed, salt: 4)
    let offset = CGFloat(roll4 * 15 - 7.5)

    // Meaning outranks the lottery: threshold walks stand at a gate,
    // and a seek that found places raises a cairn.
    if let threshold = snapshot.threshold {
        return SceneryPlacement(type: .torii, side: side, offset: offset, gateKind: threshold)
    }
    if snapshot.isSeek && snapshot.foundPlaces > 0 {
        return SceneryPlacement(
            type: .cairn,
            side: side,
            offset: offset,
            stones: min(2 + snapshot.foundPlaces, 5)
        )
    }

    let roll1 = seededRandom(seed: seed, salt: 1)
    ...
    guard forceScenery || roll1 < sceneryChance else { return nil }
    let roll2 = seededRandom(seed: seed, salt: 2)
    let type = pickType(roll: roll2)
    return SceneryPlacement(type: type, side: side, offset: offset)
}
```

Pinned semantics (ported verbatim to `SceneryGenerator.pick`):

1. `threshold != null` → **torii, always** (no 35% roll), `gateKind = threshold`.
2. else `isSeek && foundPlaces > 0` → **cairn, always**,
   `stones = min(2 + foundPlaces, 5)` (`SceneryGenerator.swift:110@c1745e8`,
   growth from 1d94f5d).
3. else the pre-existing 35% lottery (salt-1 gate, salt-2 type).

Side (salt 3) and offset (salt 4) are computed **before** the deterministic
branch so gates and cairns land with the same side/jitter their walk would
have rolled — and, because `seededRandom(seed, salt)` is a pure function of
its arguments, hoisting salts 3/4 above salts 1/2 does **not** change any
value for lottery walks. Android's current `pick` computes roll1→roll4 in
order with an early return; the reordered port yields bit-identical rolls.

## 2. The drift band — retired random torii

Swift (`SceneryGenerator.swift:79-91@c1745e8`):

```swift
// The random torii is retired: gates now mark real thresholds only
// (see the deterministic branch in `scenery(for:)`). Its old band
// belongs to drift — the season's breath — so every other walk's
// rolled item stays exactly what it has always been.
private static let weights: [(SceneryType, Double)] = [
    (.tree, 0.27),
    (.lantern, 0.18),
    (.grass, 0.22),
    (.butterfly, 0.14),
    (.mountain, 0.11),
    (.drift, 0.05),
    (.moon, 0.03),
]
```

Verified against iOS `v1.8.0`'s table (`SceneryGenerator.swift:46-54@v1.8.0`):
identical ordering and weights with `(.torii, 0.05)` in the drift slot.
Android's current `WEIGHTS` (`SceneryGenerator.kt:19-27` pre-U14) matches the
v1.8.0 table exactly — same order (tree, lantern, grass, butterfly, mountain,
torii, moon), same weights — so splicing `Drift` into the `Torii` slot
preserves every cumulative band boundary:

| band (salt-2 roll) | type |
|---|---|
| [0.00, 0.27) | tree |
| [0.27, 0.45) | lantern |
| [0.45, 0.67) | grass |
| [0.67, 0.81) | butterfly |
| [0.81, 0.92) | mountain |
| [0.92, 0.97) | **drift** (was torii) |
| [0.97, 1.00) | moon |

Backward-compat consequence, pinned by test: for every snapshot with
`threshold == null` and not (seek ∧ foundPlaces > 0), the new generator's
output equals the old generator's output **except** that an old lottery
`Torii` becomes `Drift`. Torii and cairn can never come from the lottery
(iOS `testRandomToriiIsRetired_everyGateIsAThreshold`).

## 3. `SceneryPlacement` — stones, gateKind, placement-level tint

Swift (`SceneryGenerator.swift:56-73@c1745e8`):

```swift
struct SceneryPlacement {
    let type: SceneryType
    let side: ScenerySide
    let offset: CGFloat
    /// Cairns only: stones in the stack — a two-stone base plus one per
    /// found place, capped at five.
    var stones: Int = 3
    /// Gates only: which kind of threshold the torii marks.
    var gateKind: WalkThreshold?
    var shape: AnyShape { type.shape }

    /// Practice gates stand vermilion (rust); everything else keeps its
    /// type's tint — seeking gates weathered stone among them.
    var tintColorName: String {
        if type == .torii, gateKind == .practice { return "rust" }
        return type.tintColorName
    }
}
```

Android: `SceneryPlacement` += `stones: Int = 3`, `gateKind: WalkThreshold?
= null` (defaults keep every existing call site compiling), plus the computed
`tintTokenName` that overrides `type.tintTokenName` with `"rust"` for
practice gates. `@Immutable` stability holds — `Int` + enum are stable.
`SceneryItem` switches its base-color lookup from `placement.type.tintTokenName`
to `placement.tintTokenName` (its color `when` already maps `"rust"`), the
exact analogue of iOS `SceneryItemView` reading the placement's tint; the
fuller gate treatment (seeking moss, shimenawa) is U15's.

New `SceneryType` cases (`SceneryGenerator.swift:8,33-34@c1745e8`): `cairn`
(tint `"stone"`), `drift` (tint `"fog"`). Android adds `Cairn`, `Drift` with
the same token names. iOS's `shape` mapping (`CairnStonesShape`, drift reusing
`ButterflyShape`) and `parallaxWeight` table
(`SceneryGenerator.swift:41-53@c1745e8`) are renderer concerns → U15/U16.

## 4. `WalkThreshold` + the chronological computation

Swift enum (`Pilgrim/Scenes/Home/HomeViewModel.swift:34-38@c1745e8`):

```swift
/// Which kind of gate a walk stands at.
enum WalkThreshold {
    case practice
    case seeking
}
```

Swift computation, inline in `buildSnapshots`
(`HomeViewModel.swift:85-113@c1745e8`):

```swift
let seekWalkIDs = fetchSeekWalkIDs()
let arrivalCounts = GoshuinMilestones.arrivalCounts(for: walks)
let reversed = walks.reversed()          // fetch is startDate-descending
var cumulative: Double = 0
var arrivalsBefore = 0
for (chronologicalIndex, walk) in reversed.enumerated() {
    ...
    let walkNumber = chronologicalIndex + 1
    let foundPlaces = walk.uuid.flatMap { arrivalCounts[$0] } ?? 0
    // Mystery outranks routine: a tenth walk that also found its
    // first unknown stands at a seeking gate.
    let threshold: WalkThreshold?
    if !GoshuinMilestones.seekingMilestones(
        arrivalsInWalk: foundPlaces, arrivalsBefore: arrivalsBefore
    ).isEmpty {
        threshold = .seeking
    } else if walkNumber == 1 || walkNumber % 10 == 0 {
        threshold = .practice
    } else {
        threshold = nil
    }
    arrivalsBefore += foundPlaces
    ...
}
```

Pinned semantics:

- `.seeking` when the walk's arrivals produce **any** seeking milestone —
  the exact U12 math (`GoshuinMilestones.seekingMilestones`), with
  `arrivalsBefore` accumulated chronologically over strictly-earlier walks,
  self excluded (running accumulator adds the walk's own count *after* its
  threshold is decided, `HomeViewModel.swift:113@c1745e8`). A zero-arrival
  seek never reads `.seeking` (`seekingMilestones` returns empty for
  `arrivalsInWalk == 0`).
- else `.practice` for walk #1 and every 10th (`walkNumber == 1 ||
  walkNumber % 10 == 0`), `walkNumber` = 1-based chronological position over
  the **whole** journal list (archived walks included — iOS fetches all
  walks; Android's journal likewise carries archived snapshots).
- Seeking outranks practice ("mystery outranks routine").
- The threshold is **recomputed from history on every snapshot build, never
  stored** — a new seek walk crossing a lifetime threshold retro-actively
  changes nothing (the gate lands on the crossing walk as it happens).

Android shape: `ui/home/scenery/WalkThreshold.kt` — the enum
(`Practice`, `Seeking`) plus a pure `WalkThresholds.compute(walks:
List<WalkRef>, foundPlacesByWalkId: Map<Long, Int>): Map<Long,
WalkThreshold>` (`WalkRef` = walkId + uuid + startMs), testable without Room.
It orders internally with a comparator built **from**
`GoshuinMilestones.isOrderedBefore` (consume, don't duplicate) and runs the
accumulator loop above; walks without a threshold are absent from the map
(the `nil` analogue).

**Ordering divergence (deliberate strengthening):** iOS's accumulator order
for two walks sharing a `startDate` is whatever CoreStore's descending-sort
fetch happened to produce; Android pins the tie with U12's uuid tie-break, so
the journal threshold agrees with `GoshuinMilestones.detect`'s
`arrivalsBefore` (which already uses `isOrderedBefore`) on same-start ties.
`HomeViewModel`'s chronological sort becomes
`compareBy({ startTimestamp }, { uuid })` — same total order.

## 5. HomeViewModel wiring

- `WalkSnapshot` += `foundPlaces: Int = 0`, `threshold: WalkThreshold? = null`
  (iOS `HomeViewModel.swift:17-24@c1745e8`; both stable types, `@Immutable`
  preserved; defaults keep existing constructions compiling).
- `buildSnapshots` adds **one** `repository.waypointIconsByWalk()` read →
  `GoshuinMilestones.arrivalCounts(...)` (the same single-query path
  GoshuinViewModel/WalkSummaryViewModel use — no second query shape, no
  per-walk faulting; iOS: `arrivalCounts(for: walks)` computed once,
  `HomeViewModel.swift:87@c1745e8`). Key mapping follows U12: Android keys by
  Room `walkId`, iOS by walk uuid.
- `foundPlaces = arrivalCounts[walk.id] ?: 0` (iOS `?? 0`,
  `HomeViewModel.swift:100@c1745e8`); `threshold = thresholds[walk.id]`.
- Error handling mirrors U12's `fetchSeekWalkIds` idiom: try/catch that
  re-throws `CancellationException`, logs via `Log.w("HomeViewModel", ...)`,
  and returns `emptyMap()` — cairns and seeking gates degrade, practice gates
  (which need no arrivals) and the journal itself still load. (iOS wraps the
  whole fetch in `loadWalks`'s do/catch; the Android per-fetch degrade is the
  established finer-grained equivalent.)
- The `HomeScreen` `remember` key for `SceneryGenerator.pick` widens from
  `(snap.uuid, snap.startMs)` to `snap` — the placement now also depends on
  `threshold`/`foundPlaces`/`isSeek`.

## 6. Determinism / seed structure (unchanged)

FNV-1a over uuid bytes + start-seconds + distance×100 + duration, then
SplitMix64 per salt (`SceneryGenerator.swift:142-160@c1745e8`) — byte-for-byte
what Android already implements (`SceneryGenerator.kt` FNV/salt block). U14
does not touch the seed, the salts, the 0.35 chance, or the 10000-bucket
modulus. Same snapshot → same placement, including `stones`/`gateKind`.

## 7. Deliberate non-goals / divergences

- **Rendering is U15**: `SceneryItem`'s dispatch maps `Cairn`/`Drift` to an
  empty branch (`// U15 renders these`) so the journal composes without
  crashing; `CairnStonesShape`, drift's seasonal faces, moon phase, lantern
  lighting, gate moss/shimenawa all land in U15. Parallax weights + age fade
  + haptics land in U16.
- **iOS DEBUG `--demo-journal-stress` force-scenery hook**
  (`SceneryGenerator.swift:115-122@c1745e8`) is not ported — it's the demo
  seeder family the plan excludes (U13 note: "minus demo seeder"), and
  Android has no CommandLine-argument diagnostics channel.
- **Same-start tie ordering pinned** where iOS leaves it fetch-order-defined
  (§ 4) — chosen so journal gates and goshuin seals can never disagree about
  `arrivalsBefore` on Android.
- **Archived walks count** toward `walkNumber` and `arrivalsBefore` in the
  journal threshold math (iOS `HomeViewModel` fetches all walks and filters
  nothing). This intentionally differs from Android's `GoshuinViewModel`,
  which filters archived walks out of seal detection (pre-existing v1.6.0
  divergence recorded in U12 § 8) — the journal shows archived dots, so their
  history stays real on the scroll even where the seal book releases it.
- `AnyShape` / `SceneryType.shape` has no Android analogue (Compose scenery
  composables dispatch directly) — pre-existing structure, unchanged.

## 8. Test parity map

| iOS test (`UnitTests/SceneryGeneratorTests.swift@c1745e8`) | Android (`SceneryGeneratorTest`) |
|---|---|
| `testThresholdWalk_alwaysStandsAtAGate` | `threshold walk always stands at a gate over many seeds` |
| `testGateKind_shapesTheGate` | `gateKind shapes the gate` (Practice→rust, Seeking→stone) |
| `testDrift_livesInTheRetiredGateBand` | `drift lives in the retired gate band` (+ ≈5%-of-placements bound) |
| `testSeekWithFoundPlaces_alwaysRaisesACairn` | `seek with found places always raises a cairn` |
| `testCairnStack_growsWithFoundPlaces_cappedAtFive` | `cairn stack grows with found places capped at five` (1/2/3/9 → 3/4/5/5) |
| `testThresholdOutranksCairn` | `threshold outranks cairn` |
| `testSeekWithoutArrivals_fallsBackToTheLottery` | `seek without arrivals never raises a cairn` |
| `testRandomToriiIsRetired_everyGateIsAThreshold` | `the lottery never mints a torii or a cairn` |
| `testLotteryStaysDeterministicPerWalk` | pre-existing `pick is deterministic…` + `gate and cairn placements are deterministic` |
| `testRoughlyAThirdOfWalksGetScenery` | pre-existing 35%-chance test survives unchanged + iOS-bounds `roughly a third of walks get scenery` (0.25–0.45) |
| *(band splice — untested on iOS)* | `lottery walks keep their exact prior rolled scenery except torii becomes drift` (legacy-generator fixture) |

(The plan's "11 cases" counts the file's 10 methods plus the band-splice
guarantee the iOS code comment pins; the c1745e8 file has 10 test funcs.)

New `WalkThresholdTest`: practice at #1/#10/#20; seeking on the
first-arrival walk; seeking outranks practice on a milestone-crossing 10th
walk; zero-arrival seek → no threshold; arrivalsBefore excludes self /
counts strictly-earlier only; same-startMs uuid tie-break (swapped uuids flip
the award); input-order independence.

`HomeViewModelJournalTest` additions: seeded Room history (arrival waypoints
`"sun.haze"` + decoy user icon) → snapshots carry expected
thresholds/foundPlaces with the waypoint-icons bulk query called once per
build (spy repo); waypoint-fetch failure degrades cairns/seeking gates but
keeps practice gates and the journal alive.

`JournalScreenIntegrationTest` addition: `SceneryItem` composes for a cairn
and a drift placement without crashing (the U15 passthrough guard).
