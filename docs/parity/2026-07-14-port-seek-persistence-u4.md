# Port spec — Seek persistence vocabulary (U4)

- **iOS anchor:** `pilgrim-ios` @ `c1745e88494d7677c4be8770ab6ceed1a61f3f6f` (c1745e8)
- **Plan:** `docs/plans/2026-07-14-001-feat-seek-mode-journal-scenery-plan.md` § U4
- **Scope boundary:** vocabulary + converter plumbing + predicates + tests only.
  Live call sites (seek marker at recording start, arrival writes) land in U8/U9.

## Cross-platform strings (pinned verbatim — byte-for-byte contract)

| String | Where it travels | Swift source |
|---|---|---|
| `"sun.haze"` | `waypoints.icon` column → GeoJSON `Point` properties → `.pilgrim` route → share payload waypoints | `SeekPersistence.swift:14@c1745e8` |
| `"seekMode"` | `.pilgrim` `walks/<uuid>.json` → `workoutEvents[].type` | `PilgrimPackageConverter.swift:498@c1745e8` |
| `"seekArrival"` | same | `PilgrimPackageConverter.swift:499@c1745e8` |
| `"unknown"` | same (forward-compat placeholder) | `PilgrimPackageConverter.swift:500@c1745e8` |

An Android export that spells any of these differently makes iOS imports lose
seek-ness (iOS maps unrecognized ids to `.unknown`), and vice versa.

## 1. Reserved arrival-waypoint icon + predicate

Swift (`Pilgrim/Models/Walk/Seek/SeekPersistence.swift:10-18@c1745e8`):

```swift
/// Reserved SF symbol for arrival waypoints. Must never collide with the
/// user-pickable icons in `WaypointMarkingSheet` (presets plus the
/// custom-note "mappin") — summary grouping tells arrivals apart by
/// exactly this icon string.
static let arrivalWaypointIcon = "sun.haze"

static func isArrivalWaypoint(_ waypoint: WaypointInterface) -> Bool {
    waypoint.icon == arrivalWaypointIcon
}
```

Android: `domain/seek/SeekPersistence.kt` — `ARRIVAL_WAYPOINT_ICON = "sun.haze"`
and `isArrivalWaypoint(icon: String?)`. The predicate takes the icon string
rather than a waypoint object because the Android `domain` package has no
compile-time dependency on `data.entity` (same reason `WalkEventReplay` uses
`WalkEventLike`); iOS's own test (`SeekPersistenceTests.swift:193-205@c1745e8`,
`testIsArrivalWaypoint_matchesByIconOnly`) pins that the predicate reads *only*
the icon, so the narrower signature is behavior-identical.

**Icon-collision audit (Android):** user-pickable icon keys in
`ui/walk/WaypointMarkingSheet.kt` are `"leaf"`, `"eye"`, `"heart"`,
`"figure.seated.side"`, `"sparkles"`, `"flag.fill"` (PRESET_CHIPS) plus the
custom-note `WAYPOINT_CUSTOM_ICON_KEY = "mappin"`. `"sun.haze"` collides with
none — mirrors iOS's `testArrivalIcon_isDistinctFromUserPickableIcons`
(`SeekPersistenceTests.swift:185-191@c1745e8`). A collision test is ported.
Note: `iconKeyToVector` currently renders unknown keys (including
`"sun.haze"`) as `LocationOn` with a log warning — map rendering of arrival
waypoints is a later unit's concern, not U4.

## 2. Ordinal labels

Swift (`SeekPersistence.swift:21-28@c1745e8`):

```swift
static func arrivalWaypointLabel(clearingOrdinal ordinal: Int) -> String {
    switch ordinal {
    case 1: return firstClearingLabel
    case 2: return secondClearingLabel
    case 3: return thirdClearingLabel
    default: return String(format: nthClearingLabelFormat, ordinal)
    }
}
```

iOS localization keys and default values (`SeekPersistence.swift:32-66@c1745e8`):

| iOS key | value |
|---|---|
| `seek.event.seek_mode` | `"Seek"` |
| `seek.event.arrival` | `"Clearing reached"` |
| `seek.arrival.label.first` | `"First clearing"` |
| `seek.arrival.label.second` | `"Second clearing"` |
| `seek.arrival.label.third` | `"Third clearing"` |
| `seek.arrival.label.nth` | `"Clearing %d"` |

Android strings (in `res/values/strings.xml`, `seek_` prefix following the
`path_`/`walk_` sectioning convention): `seek_event_seek_mode`,
`seek_event_arrival`, `seek_arrival_label_first`, `seek_arrival_label_second`,
`seek_arrival_label_third`, `seek_arrival_label_nth` (`Clearing %1$d`).
`arrivalWaypointLabel(resources, clearingOrdinal)` resolves them (the
`Resources`-taking function mirrors the `WeatherCondition.labelRes` /
`SeasonalMarkerTurnings` pattern of keeping strings in resources). Labels are
*display defaults persisted at write time* (the DB stores the resolved string),
exactly like iOS's `NSLocalizedString` values.

Ordinal semantics (write-site contract for U8/U9, quoted here because the label
function's input is defined by it — `ActiveWalkViewModel+Seek.swift:179-190@c1745e8`):

```swift
/// The ordinal counts arrivals already persisted this walk rather than
/// echoing the engine's clearing index: after "Seek anew" from inside an
/// unrevealed clearing, the replacement clearing replays the same index,
/// which would duplicate labels and inflate the unknowns-found count.
private func recordSeekArrival() {
    builder.addWorkoutEvent(TempWalkEvent(uuid: nil, eventType: .seekArrival, timestamp: Date()))
    let ordinal = waypoints.filter(SeekPersistence.isArrivalWaypoint).count + 1
```

Android exposes `arrivalOrdinal(icons: List<String?>)` = persisted-arrival-count
+ 1, so U8/U9 cannot re-derive it from an engine index.

## 3. Event-type vocabulary

Swift (`Pilgrim/Models/Data/DataModels/WalkEvent.swift:30-47@c1745e8`):

```swift
case lap, marker, segment, seekMode, seekArrival, unknown

public init(rawValue: Int) {
    switch rawValue {
    case 0: self = .lap
    case 1: self = .marker
    case 2: self = .segment
    case 3: self = .seekMode
    case 4: self = .seekArrival
    default: self = .unknown          // rawValue -1 on write-back
    }
}
```

Android `WalkEventType` is a *different domain vocabulary* (PAUSED / RESUMED /
MEDITATION_START / MEDITATION_END / WAYPOINT_MARKED — Android event-sources its
walk lifecycle; iOS stores pauses/activities as separate aggregates and uses
events only for lap/marker/segment/seek). Android stores the enum **name
string** in `walk_events.event_type` (TEXT), not iOS's int raw value, so:

- Add `SEEK_MODE`, `SEEK_ARRIVAL`, `UNKNOWN` to the enum. No LAP/MARKER/SEGMENT:
  iOS has **no production write site** for them at c1745e8 (repo grep: the only
  `addWorkoutEvent` callers are the two seek sites in
  `ActiveWalkViewModel+Seek.swift:176,184@c1745e8`) — they are legacy vocabulary.
- `Converters.stringToWalkEventType` fallback changes `PAUSED` → `UNKNOWN`
  (mirrors `default: self = .unknown`). Honest claim scope: protects v1.2.0+
  readers of future vocabulary; shipped v1.1.x maps unknown names → PAUSED and
  that stands for old binaries (in-place downgrades unsupported).
- **No Room migration**: TEXT column + nullable `waypoints.label`/`icon`
  already round-trip everything U4 needs.

## 4. `.pilgrim` manifest events export/import

Swift export (`PilgrimPackageConverter.swift:81-86@c1745e8`):

```swift
let workoutEvents = walk.workoutEvents.map { event in
    PilgrimWorkoutEvent(
        timestamp: event.timestamp,
        type: workoutEventTypeString(event.eventType)
    )
}
```

Swift import (`PilgrimPackageConverter.swift:467-473,504-513@c1745e8`):

```swift
let workoutEvents = walk.workoutEvents.map { event in
    TempWalkEvent(uuid: UUID(), eventType: walkEventType(from: event.type), timestamp: event.timestamp)
}
...
private static func walkEventType(from string: String) -> WalkEvent.EventType {
    switch string {
    case "lap": return .lap
    case "marker": return .marker
    case "segment": return .segment
    case "seekMode": return .seekMode
    case "seekArrival": return .seekArrival
    default: return .unknown
    }
}
```

**Verified iOS unknown-id semantics: unrecognized identifiers are KEPT as
`.unknown`, not dropped.** Android mirrors this: unrecognized `type` strings
import as `WalkEventType.UNKNOWN` rows (no crash, no drop), and `UNKNOWN`
re-exports as `"unknown"` exactly like iOS (`workoutEventTypeString`
`.unknown → "unknown"`, line 500). This intentionally supersedes the plan-file
shorthand "unknown identifiers are dropped" — the governing rule was "verify
what iOS does and mirror it".

Android export mapping (`PilgrimPackageConverter.convert`):

| Android `WalkEventType` | wire `type` |
|---|---|
| `SEEK_MODE` | `"seekMode"` |
| `SEEK_ARRIVAL` | `"seekArrival"` |
| `UNKNOWN` | `"unknown"` |
| `PAUSED` / `RESUMED` | *(omitted — already exported as `pauses`)* |
| `MEDITATION_START` / `MEDITATION_END` | *(omitted — already exported as `activities`)* |
| `WAYPOINT_MARKED` | *(omitted — the waypoint itself rides in the route GeoJSON)* |

The omissions are a **conscious divergence forced by the vocabulary mismatch**:
Android's lifecycle events have first-class wire representations elsewhere in
the schema; exporting them under `workoutEvents` would double-represent pauses
and pollute iOS imports with `.unknown` junk. iOS's own exports contain only
seek events in practice (see § 3), so wander-walk exports remain byte-identical
(`workoutEvents: []`).

Android import mapping (`convertToImport`): `"seekMode"` → `SEEK_MODE`,
`"seekArrival"` → `SEEK_ARRIVAL`, anything else (including legacy
`"lap"`/`"marker"`/`"segment"`, which Android does not model) → `UNKNOWN`, kept.
Imported walk events = pause-derived PAUSED/RESUMED pairs + workout events,
sorted by timestamp (replay expects chronological order).

## 5. Exhaustive-`when` ripples (compiler-forced, all no-op)

Seek events are **point markers, not spans** — they never open/close an
interval, so every replay/aggregation path passes through:

- `domain/WalkEventReplay.kt` `replayWalkEventTotals` — explicit branches → `Unit`.
- `data/walk/WalkMetricsMath.kt` `computeActiveDurationSeconds` — has `else -> Unit`.
- `data/pilgrim/builder/PilgrimPackageConverter.kt` `computePauses` — has `else`.
- `walk/UiWalkController.kt` bell observer — has `else -> Unit`.
- `data/share/SharePayloadBuilder.kt` — takes no events at all; wander share
  payloads are structurally unchanged (regression-guarded by golden-JSON test).

## 6. Test parity map (`SeekPersistenceTests.swift@c1745e8` → Android)

| iOS test | Android test |
|---|---|
| `testEventType_seekRawValues_roundTrip` / `legacyRawValues` | `ConvertersTest` round-trip over all entries (names, not ints — storage differs) |
| `testEventType_unknownRawValues_fallBackToUnknown` | `ConvertersTest.unknown name falls back to UNKNOWN` |
| `testPilgrimPackage_roundTripsSeekEventsAndArrivalIcon` (`:151-181`) | `PilgrimPackageConverterTest` seek round-trip (SEEK_MODE + 2 SEEK_ARRIVAL + 2 marked waypoints, through real JSON encode/decode) |
| `testArrivalIcon_isDistinctFromUserPickableIcons` (`:185-191`) | `SeekPersistenceTest.reserved icon collides with no user-pickable icon` |
| `testIsArrivalWaypoint_matchesByIconOnly` (`:193-205`) | `SeekPersistenceTest` predicate tests |
| `testArrivalWaypointLabel_ordinals` (`:209-214`) | `SeekPersistenceTest.ordinal labels` (Robolectric, real resources) |
| *(builder/checkpoint channel tests, `:35-147`)* | **out of U4 scope** — Android's builder analogue is the reducer/controller path, wired in U8/U9 |

## 7. Deliberate non-goals

- No writes from `WalkControllerImpl`/service (U8/U9).
- No summary grouping, map rendering, or milestone counting (U12+).
- No schema migration (confirmed unnecessary).
- `seek_event_seek_mode` / `seek_event_arrival` display names are added to
  strings.xml for parity with iOS's `EventType.description`
  (`WalkEvent.swift:66-81@c1745e8`) but have no Android consumer yet; the
  summary UI unit picks them up.
