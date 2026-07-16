# Port spec — Summary seek story + provenance + arrival halos (U11)

- **iOS anchor:** `pilgrim-ios` @ `c1745e88494d7677c4be8770ab6ceed1a61f3f6f` (c1745e8)
- **Source commits:** iOS U9 (seek summary section) plus follow-ups `e5c9735`
  (provenance line), `8dc2b1f` (arrival halos + found-under captions),
  `2b88455` (seeding). This spec ports the **end-state** at c1745e8, not the
  intermediate diffs.
- **Plan:** `docs/plans/2026-07-14-001-feat-seek-mode-journal-scenery-plan.md` § U11
- **Depends on:** U4 (`SEEK_MODE`/`SEEK_ARRIVAL` events,
  `SeekPersistence.ARRIVAL_WAYPOINT_ICON`, persisted ordinal labels), U7
  (`SunCalc.solarElevationDegrees`, `SeekSkyLight` daypart + hex tokens), U12
  (`GoshuinMilestones.seekingMilestones` / `arrivalCounts` /
  `WalkMilestoneInput.foundPlaceCount`).
- **Scope boundary (parallel-unit fence):** no edits under `ui/path/`,
  `ui/seek/`, `walk/` (controller/publisher), `service/`, or `domain/Walk*.kt`
  reducers. `ui/walk/summary/*`, `WalkSummaryScreen.kt`,
  `WalkSummaryViewModel.kt`, `PilgrimMap.kt`, `data/walk/MapAnnotations.kt`,
  and a commented seek-summary block in `strings.xml` are U11-owned.

## 1. Seek detection + the nil paths

Swift (`Pilgrim/Scenes/WalkSummary/SeekSummarySection.swift:71-73,87@c1745e8`):

```swift
static func isSeekWalk(events: [WalkEvent.EventType]) -> Bool {
    events.contains(.seekMode)
}
...
guard isSeekWalk(events: events), !arrivals.isEmpty else { return nil }
```

- Seek detection = a `SEEK_MODE` event present, nothing else.
- **Zero-arrival seeks return `null`** — the standard summary renders
  untouched (plan Key Decision; doc comment `SeekSummarySection.swift:3-6`:
  "nil when the walk is not a seek or no clearing was reached — zero-arrival
  seeks render the standard summary untouched").
- Wander walks (no `SEEK_MODE`) return `null` even if stray arrival-icon
  waypoints exist.

Android: `SeekSummaryModel.isSeekWalk(events: List<WalkEventType>)` and
`summaryData(...): SeekSummaryData?` in
`ui/walk/summary/SeekSummaryModel.kt` (pure, JVM-testable).

## 2. Grouping constants + rules

Swift (`SeekSummarySection.swift:45-52,75-78@c1745e8`):

```swift
/// Half the maximum region diameter (120 m) plus GPS slack, so a sign
/// marked anywhere inside a clearing groups to it even when the fix
/// wandered past the region edge.
static let groupingRadiusMeters = 80.0

/// Signs without any coordinate attribute to the preceding arrival only
/// when marked within this window of the arrival itself.
static let timestampFallbackWindow: TimeInterval = 5 * 60
...
/// A sign exactly on the radius boundary belongs to the clearing.
static func belongsToClearing(distanceMeters: Double) -> Bool {
    distanceMeters <= groupingRadiusMeters
}
```

Nearest-arrival selection (`SeekSummarySection.swift:147-164@c1745e8`):

```swift
private static func clearingIndex(for sign: Sign, in arrivals: [Arrival]) -> Int? {
    guard let coordinate = sign.coordinate else {
        return timestampFallbackIndex(for: sign.timestamp, in: arrivals)
    }
    guard let nearest = arrivals.enumerated().min(by: {
        SeekChainGenerator.distance(from: coordinate, to: $0.element.center)
            < SeekChainGenerator.distance(from: coordinate, to: $1.element.center)
    }) else { return nil }

    let distance = SeekChainGenerator.distance(from: coordinate, to: nearest.element.center)
    return belongsToClearing(distanceMeters: distance) ? nearest.offset : nil
}

private static func timestampFallbackIndex(for timestamp: Date, in arrivals: [Arrival]) -> Int? {
    guard let preceding = arrivals.lastIndex(where: { $0.arrivedAt <= timestamp }) else { return nil }
    let sinceArrival = timestamp.timeIntervalSince(arrivals[preceding].arrivedAt)
    return sinceArrival <= timestampFallbackWindow ? preceding : nil
}
```

Pinned semantics:

- A coordinate-bearing sign goes to its **nearest** arrival, but only when
  that distance is ≤ 80 m (boundary **inclusive** — iOS test
  `testBelongsToClearing_boundaryIsInclusive`,
  `UnitTests/Seek/SeekSummaryTests.swift:184-189@c1745e8`). Beyond 80 m →
  "Along the way".
- A coordinate-less sign attributes to the **latest arrival at or before its
  timestamp**, only when marked ≤ 5 minutes after that arrival. Before the
  first arrival, or later than 5 minutes → "Along the way".
- Arrivals are sorted by `arrivedAt` ascending; group `ordinal` = sorted
  position + 1 (`SeekSummarySection.swift:89,101-112@c1745e8`).
- Distance function = `SeekChainGenerator.distance` (haversine) — Android
  reuses `domain/seek/SeekChainGenerator.distance(SeekPoint, SeekPoint)`.

## 3. Sign sources (walk adapter)

Swift (`SeekSummarySection.swift:173-241@c1745e8`), doc comment verbatim:

```swift
/// Maps a stored walk onto plain model inputs. Coordinate support per
/// sign type: photos carry their own capture fix (the matcher drops
/// location-less photos), waypoints were marked at the walker's position,
/// and voice recordings store no location — their coordinate resolves to
/// the route sample nearest the recording start (the same rule that
/// places their map pin), falling back to timestamp grouping when the
/// walk has no route data.
```

- **Arrivals** = waypoints passing `SeekPersistence.isArrivalWaypoint`
  (`:186-194`); label/center/time come straight off the persisted waypoint
  row (labels were resolved + persisted at write time, U4 § 2).
- **Photos** → `Sign(kind: .photo, id: photo.localIdentifier,
  coordinate: SeekPoint(photo.capturedLat, photo.capturedLng),
  timestamp: photo.capturedAt)` (`:197-204`). iOS photo rows always carry a
  capture fix (the reliquary matcher drops location-less photos upstream).
  Android `WalkPhoto.capturedLat/Lng` are nullable → a photo with a null fix
  maps to `coordinate = null` (timestamp fallback), honest to the same
  intent; see Divergence table D3.
- **Voice recordings** → coordinate = route sample nearest (absolute time
  delta) the recording start (`:205-212,234-241`); when the walk has **no
  route samples**, coordinate = null → timestamp fallback (iOS
  `testAdapter_recordingWithoutRouteData_usesTimestampFallback`,
  `SeekSummaryTests.swift:393-420`).
- **Marks** = the non-arrival waypoints, at their own persisted position
  (`:213-223`).
- Sign ids: iOS uses `localIdentifier` / `uuid` strings. Android uses the
  entities' non-null `uuid` columns (identity only feeds counting/tests).

Provenance inputs (`SeekSummarySection.swift:229-230@c1745e8`):

```swift
seededAt: walk.workoutEvents.first { $0.eventType == .seekMode }?.timestamp,
intentionWasVoiced: !(walk.comment?.isEmpty ?? true)
```

- `seededAt` = the `SEEK_MODE` event's timestamp (written once at recording
  start; the "gateway moment", doc `:36-39`). Android: first
  `WalkEventType.SEEK_MODE` in `eventsFor(walkId)`.
- `intentionWasVoiced` = walk comment non-empty. iOS `walk.comment` is the
  intention text (`WalkSummaryView.swift:305@c1745e8` reads `walk.comment`
  for the intention card) — Android analogue is `Walk.intention`, so
  `intentionWasVoiced = !walk.intention.isNullOrEmpty()` (no trim, matching
  iOS's exact `isEmpty`).
- `seededAt == nil` → no provenance line at all
  (`SeekSummarySection.swift:268-274`; iOS test
  `testSummaryData_defaultsLeaveTheKeepsakeSilent`).

## 4. Found-under captions (the hour's light)

Swift (`SeekSummarySection.swift:127-136,346-352@c1745e8`):

```swift
/// The hour's light at an arrival, from the sun's real elevation at
/// that place and moment. Shared by the summary captions and the
/// summary map's halo tint.
static func foundUnderDaypart(center: SeekPoint, arrivedAt: Date) -> SeekSkyLight.Daypart {
    SeekSkyLight.daypart(
        solarElevationDegrees: CelestialCalculator.solarElevationDegrees(
            at: center.coordinate, on: arrivedAt
        )
    )
}
...
static func foundUnderText(_ daypart: SeekSkyLight.Daypart) -> String {
    switch daypart {
    case .golden: return LS.seekSummaryFoundGolden
    case .midday: return LS.seekSummaryFoundMidday
    case .night: return LS.seekSummaryFoundNight
    }
}
```

The daypart tokens are exactly the crescent-light thresholds
(`SeekFogModel.swift:202-208@c1745e8`, already ported as
`domain/seek/SeekSkyLight.daypart`): **night < −4°, golden < 8°, else
midday** (boundaries: −4.0 itself is golden, 8.0 itself is midday). Android:
`SeekSummaryModel.foundUnderDaypart(center, arrivedAtEpochMs)` calls
`SunCalc.solarElevationDegrees(lat, lon, Instant)` (U7 port,
`core/celestial/SunCalc.kt:179`) — real elevation at that place & moment, no
constellation/starlight input (the record keeps the sky palette).

## 5. Unknowns-found note (R19: never totals, never "X of Y")

Swift (`SeekSummarySection.swift:34-35,138-145@c1745e8`):

```swift
/// Counts only reached clearings — never totals, never "X of Y" (R19).
let unknownsFoundText: String
...
static func unknownsFoundText(arrivalCount: Int) -> String {
    switch arrivalCount {
    case 1: return LS.seekSummaryFoundOne
    case 2: return LS.seekSummaryFoundTwo
    case 3: return LS.seekSummaryFoundThree
    default: return String(format: LS.seekSummaryFoundManyFormat, arrivalCount)
    }
}
```

Unreached clearings stay hidden — the count is `groups.size`, sourced only
from persisted arrival waypoints. Android resolves the string with real
resources (`SeekSummaryModel.unknownsFoundText(resources, arrivalCount)`,
mirroring the U4 `arrivalWaypointLabel(resources, ordinal)` pattern) instead
of storing resolved text in the data class (Android VMs keep strings in
resources; the composable resolves at render time).

## 6. Cross-platform strings (pinned verbatim from `LS.swift@c1745e8`)

| iOS key (LS.swift line) | value | Android string id |
|---|---|---|
| `seek.summary.header` (:183) | `The Seeking` | `seek_summary_header` |
| `seek.summary.found.one` (:190) | `One unknown found` | `seek_summary_found_one` |
| `seek.summary.found.two` (:197) | `Two unknowns found` | `seek_summary_found_two` |
| `seek.summary.found.three` (:204) | `Three unknowns found` | `seek_summary_found_three` |
| `seek.summary.found.many` (:211) | `%d unknowns found` | `seek_summary_found_many` (`%1$d`) |
| `seek.summary.found_under.golden` (:218) | `Found in the golden hour` | `seek_summary_found_under_golden` |
| `seek.summary.found_under.midday` (:224) | `Found in broad daylight` | `seek_summary_found_under_midday` |
| `seek.summary.found_under.night` (:230) | `Found under the night sky` | `seek_summary_found_under_night` |
| `seek.summary.seeded` (:236) | `The way was shaped by your intention and the moment you set out — %@.` | `seek_summary_seeded` (`%1$s`) |
| `seek.summary.seeded.quiet` (:242) | `The way was shaped by the moment you set out — %@.` | `seek_summary_seeded_quiet` (`%1$s`) |
| `seek.summary.along_the_way` (:248) | `Along the way` | `seek_summary_along_the_way` |
| `seek.summary.sign.photo.one` (:255) | `a photo` | `seek_summary_sign_photo_one` |
| `seek.summary.sign.photo.many` (:262) | `%d photos` | `seek_summary_sign_photo_many` (`%1$d`) |
| `seek.summary.sign.voice.one` (:269) | `a voice note` | `seek_summary_sign_voice_one` |
| `seek.summary.sign.voice.many` (:276) | `%d voice notes` | `seek_summary_sign_voice_many` (`%1$d`) |
| `seek.summary.sign.mark.one` (:283) | `a mark` | `seek_summary_sign_mark_one` |
| `seek.summary.sign.mark.many` (:290) | `%d marks` | `seek_summary_sign_mark_many` (`%1$d`) |

**Note the singular forms**: the signs line reads "a photo · 2 voice notes ·
a mark" — indefinite article for singletons, NOT "1 photo". (The plan-file
shorthand "1 photo · 2 voices · 1 mark" was a sketch; the Swift strings
above govern.)

## 7. Signs line — separator + pluralization

Swift (`SeekSummarySection.swift:354-372@c1745e8`):

```swift
private func signsLine(photos: Int, voices: Int, marks: Int) -> String? {
    var parts: [String] = []
    if photos == 1 {
        parts.append(LS.seekSummarySignPhotoOne)
    } else if photos > 1 {
        parts.append(String(format: LS.seekSummarySignPhotosFormat, photos))
    }
    if voices == 1 { ... } else if voices > 1 { ... }
    if marks == 1 { ... } else if marks > 1 { ... }
    return parts.isEmpty ? nil : parts.joined(separator: " · ")
}
```

- Order fixed: photos, voices, marks. Zero-count categories are omitted.
- Separator is `" · "` (space, U+00B7 MIDDLE DOT, space).
- All-zero → no line at all (`nil`).
- Counts format via `%d` — Android uses resource placeholders (ASCII digits
  via `getString`; counts are small ints, no locale-digit trap through
  `Resources.getString`).

## 8. Provenance line

Swift (`SeekSummarySection.swift:268-274,337-344@c1745e8`):

```swift
if let seededAt = data.seededAt {
    Text(seededLine(at: seededAt))
        .font(Constants.Typography.caption)
        .italic()
        .foregroundColor(.fog)
        .padding(.top, Constants.UI.Padding.small)
}
...
private func seededLine(at date: Date) -> String {
    String(
        format: data.intentionWasVoiced
            ? LS.seekSummarySeededFormat
            : LS.seekSummarySeededQuietFormat,
        Self.arrivalTimeFormatter.string(from: date)
    )
}
```

- Intention voiced → "The way was shaped by your intention and the moment
  you set out — 9:00 AM."
- Quiet seek → "The way was shaped by the moment you set out — 9:00 AM."
- The time is the SEEK_MODE event timestamp formatted with the same
  short-time formatter as arrival times (§ 9).

## 9. The card view

Swift (`SeekSummarySection.swift:246-335@c1745e8`). Layout contract:

- Container: `parchmentSecondary` background, normal corner radius, normal
  padding, full width, leading-aligned, `spacing: Padding.small`.
- Header row: `Image(systemName: SeekPersistence.arrivalWaypointIcon)`
  ("sun.haze") tinted `.stone` + "The Seeking" in heading font, `.ink`.
- Unknowns-found note: body font, `.fog`.
- Per clearing row (`:292-317`, `spacing: 2`, `.top` padding xs):
  - Row: label (body, `.ink`) … spacer … arrival time (caption, `.fog`)
    via `DateFormatter` `timeStyle = .short` (`:250-254`).
  - Found-under caption (caption, `.fog`).
  - Signs line (caption, `.fog`) — omitted when nil.
- "Along the way" row (`:319-335`) only when non-empty (`:265-267`): label in
  body/`.ink` + its signs line, caption/`.fog`.
- Provenance line last, caption italic `.fog`, small top padding.

Placement in the summary (`WalkSummaryView.swift:73-77@c1745e8`):

```swift
intentionCard
if let seekSummary = cachedSeekSummary {
    SeekSummarySection(data: seekSummary)
}
elevationProfile
```

— i.e. **after the intention card, before the elevation profile**, with no
reveal fade (iOS applies `.opacity` reveal only to journey quote / hero /
stats / etc., not to this section — same tier as intentionCard). The model is
computed once per walk identity (`WalkSummaryView.swift:12-14,24@c1745e8`,
"computed once per walk identity like the route caches (AF17)") — Android
computes it in `WalkSummaryViewModel.buildState()` and stores it on
`WalkSummary.seekSummary`.

Android time formatting (house Stage 6-B/8-A rule): the neighbors format
times via `DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)`
(`WalkActivityListCard.kt:185`, `WalkActivityTimelineCard.kt:390`);
`SeekSummarySection` uses the same pattern plus
`.withDecimalStyle(DecimalStyle.STANDARD)`, zone = system default.

## 10. Arrival halos on the summary map

Annotation kind (`Pilgrim/Models/Walk/MapManagement/PilgrimAnnotation.swift:13-17@c1745e8`):

```swift
/// A seek arrival on the summary map: a dawn halo in the hour's
/// light it was found under (fixed hex — the record keeps the sky
/// palette), not a pin. Live walks keep `.waypoint` — their halo
/// comes from the fog layer.
case seekArrival(label: String, lightHex: String)
```

Mapping (`WalkSummaryView.swift:693-710@c1745e8`):

```swift
for waypoint in walk.waypoints {
    let coordinate = CLLocationCoordinate2D(latitude: waypoint.latitude, longitude: waypoint.longitude)
    if SeekPersistence.isArrivalWaypoint(waypoint) {
        let daypart = SeekSummaryModel.foundUnderDaypart(
            center: SeekPoint(latitude: waypoint.latitude, longitude: waypoint.longitude),
            arrivedAt: waypoint.timestamp
        )
        pins.append(PilgrimAnnotation(
            coordinate: coordinate,
            kind: .seekArrival(
                label: waypoint.label,
                lightHex: SeekSkyLight.hex(daypart: daypart, starlight: false)
            )
        ))
    } else {
        pins.append(PilgrimAnnotation(coordinate: coordinate, kind: .waypoint(label: waypoint.label, icon: waypoint.icon)))
    }
}
```

Two-part rendering (`Pilgrim/Views/PilgrimMapView.swift:394-400,426-432@c1745e8`):

```swift
case .seekArrival(_, let lightHex):
    // The bright core inside the found-place halo — the same
    // two-part reading as the web viewer's Book.
    circle.circleRadius = 4
    circle.circleColor = StyleColor(UIColor(hex: lightHex))
    circle.circleOpacity = 0.9
    circle.circleStrokeWidth = 0
...
case .seekArrival(_, let lightHex):        // glowCircle
    var glow = CircleAnnotation(centerCoordinate: pin.coordinate)
    glow.circleRadius = 26
    glow.circleColor = StyleColor(UIColor(hex: lightHex))
    glow.circleOpacity = 0.28
    glow.circleStrokeWidth = 0
    return glow
```

Pinned:

- **Glow**: radius 26, opacity 0.28, no stroke, hour-light hex.
- **Core**: radius 4, opacity 0.9, no stroke, same hex. Glow is created
  before the core so the core draws on top.
- Hex = `SeekSkyLight.hex(daypart, starlight: false)` — **always the dawn
  family**; the record keeps the sky palette even under the constellation
  appearance.
- **No point icon** — `buildCircles` skips `.waypoint` etc. for
  `seekArrival` and `buildPoints` has no `seekArrival` branch: the halo is
  the whole marker. Label is carried for identity/equality only, never drawn.
- **Summary-map-only**: iOS builds `.seekArrival` solely in
  `WalkSummaryView.computeAnnotations`. Live walks keep `.waypoint` — the
  live halo comes from the fog layer (U6).

Android: new `WalkMapAnnotationKind.SeekArrival(label, lightHex)` in
`data/walk/MapAnnotations.kt`; `computeWalkMapAnnotations` (whose only
production caller is `WalkSummaryViewModel.buildState` — summary-only by
construction) maps arrival-icon waypoints to it; `PilgrimMap.kt` renders both
circles through the existing summary `CircleAnnotationManager` (the
meditation-circle manager, same delete/rebuild bookkeeping) and excludes the
kind from the point-pin pass.

## 11. U12 handoff — `foundPlaceCount` into the summary's milestone detection

iOS wires the walk's own arrival count into seal detection via `SealInput`
(`Pilgrim/Models/Seal/SealInput.swift:19-21,41@c1745e8`):

```swift
/// Seek arrivals recorded on this walk (reserved-icon waypoints), for
/// the seeking milestones.
let foundPlaceCount: Int
...
self.foundPlaceCount = walk.waypoints.filter(SeekPersistence.isArrivalWaypoint).count
```

Android's `WalkSummaryViewModel.detectMilestoneFor` currently builds
`WalkMilestoneInput` rows without `foundPlaceCount` (defaults to 0), so
`GoshuinMilestones.detect`'s seeking branch
(`GoshuinMilestones.kt:148-159`) never fires on the summary reveal — the
U12-flagged gap. Fix: one `repository.waypointIconsByWalk()` read →
`GoshuinMilestones.arrivalCounts(...)` → `foundPlaceCount =
arrivalCounts[walk.id] ?: 0` per input (exactly the GoshuinViewModel wiring;
one query, no per-walk faulting — iOS's own perf rule). Counting keyed by
Room `walkId`; uuid is only the ordering tie-break inside
`isOrderedBefore` (U12 § ordering).

## 12. Test parity map (`SeekSummaryTests.swift@c1745e8` → Android)

| iOS test (line) | Android test (`SeekSummaryModelTest`) |
|---|---|
| `testFoundUnderDaypart_readsTheSkyAtThePlaceAndMoment` (:45) | `foundUnderDaypart reads the sky at the place and moment` (equator noon → MIDDAY, midnight → NIGHT, dawn → GOLDEN) |
| `testClearingGroups_carryTheirFoundUnderLight` (:53) | `clearing groups carry their found-under light` |
| `testComputeAnnotations_rendersArrivalsAsHourLitHalos` (:69) | `computeWalkMapAnnotations renders arrivals as hour-lit halos` (+ ordinary waypoint keeps pin) |
| `testSummaryData_carriesTheGatewayMomentAndIntentionPresence` (:106) | `summaryData carries the gateway moment and intention presence` |
| `testSummaryData_defaultsLeaveTheKeepsakeSilent` (:119) | `defaults leave the keepsake silent` |
| `testIsSeekWalk_*` (:130,:135) | `isSeekWalk detects the SEEK_MODE event` |
| `testSummaryData_wanderWalk_isNil` (:142) | `wander walk yields no model` |
| `testSummaryData_zeroArrivals_isNil` (:151) | `zero arrivals yields no model` |
| `testSummaryData_twoClearings_groupsSignsAndAlongTheWay` (:162) | `two clearings group signs and strays` |
| `testBelongsToClearing_boundaryIsInclusive` (:184) | `grouping boundary is inclusive at exactly 80m` |
| `testSummaryData_signNearRadiusBoundary_grouped_beyondNot` (:191) | `79m photo groups, 81m photo strays` (plan fixture: 79 in / 81 out) |
| `testSummaryData_signGroupsToNearestClearing` (:206) | `sign groups to the nearest clearing` |
| `testSummaryData_coordinatelessSign_withinWindow_...` (:225) | `coordinate-less voice within 5min groups to preceding arrival` |
| `testSummaryData_coordinatelessSign_outsideWindow_...` (:236) | `coordinate-less voice outside 5min strays` |
| `testSummaryData_coordinatelessSign_beforeFirstArrival_...` (:247) | `coordinate-less voice before first arrival strays` |
| `testSummaryData_groupsSortedByArrivalTime` (:259) | `groups sort by arrival time with ordinals reassigned` |
| `testUnknownsFoundText_spelledCounts` (:278) | `unknowns text spells one two three exactly` |
| `testUnknownsFoundText_neverPhrasesATotal` (:284) | `unknowns text never phrases a total` (+ 4 → "4 unknowns found") |
| `testSummaryData_textMatchesReachedCount_notChainSize` (:291) | folded into the arrival-count tests (count = groups.size) |
| `testAdapter_wanderWalk_isNil` / `_seekWalkWithoutArrivals_isNil` (:324,:329) | entity-adapter nil-path tests |
| `testAdapter_groupsPhotosRecordingsAndUserWaypoints` (:336) | `adapter groups photos recordings and user waypoints` |
| `testAdapter_recordingWithoutRouteData_usesTimestampFallback` (:393) | `adapter recording without route data uses timestamp fallback` |
| *(new, Android-only)* | `WalkSummaryViewModelTest`: seeded 2-arrival walk → seeking-seal milestone (foundPlaceCount reaches detect); `seekSummary` on Loaded state; provenance fields from the SEEK_MODE event |

## Divergence table

| # | iOS | Android | Why |
|---|---|---|---|
| D1 | `SeekSummaryData.unknownsFoundText` stores the resolved string | data class stores no text; composable resolves via `SeekSummaryModel.unknownsFoundText(resources, groups.size)` | house rule: strings live in resources, resolved at render; keeps the model context-free |
| D2 | `DateFormatter` `timeStyle = .short` (device locale) | `DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH).withDecimalStyle(DecimalStyle.STANDARD)` | matches the summary card neighbors (`WalkActivityListCard.kt:185`); Stage 6-B ASCII-digit rule |
| D3 | photo signs always carry a capture fix (framework guarantees) and `capturedAt` | `WalkPhoto.capturedLat/Lng` nullable → null fix maps to `coordinate = null` (timestamp fallback); sign timestamp = `takenAt ?: pinnedAt` (`takenAt` nullable) | Android pins photos without EXIF GPS; dropping them from the story entirely would hide real signs — the fallback window keeps attribution honest |
| D4 | header icon = SF symbol `sun.haze` | `Icons.Outlined.WbTwilight` (Material's low-sun-with-haze glyph) | no SF symbols on Android; same mapping table approach as `iconKeyToVector` |
| D5 | ids are `localIdentifier` / `uuid?.uuidString ?? fallback` | entity `uuid` columns (non-null on Android) | fallback branches drop out; ids feed counting only |
| D6 | `SealInput.foundPlaceCount` per-walk from CoreData waypoint relationship | `GoshuinMilestones.arrivalCounts(waypointIconsByWalk())` bulk read in `detectMilestoneFor` | one query for all walks (iOS's own perf rule via `arrivalCounts(for:)`), keyed by Room walkId |
| D7 | live-walk map renders arrival waypoints as `.waypoint` with the `sun.haze` glyph | live map renders them via the existing waypoint path (unknown icon key → pin glyph + log) | live-map glyph art is U8/U9 surface; U11 is summary-only per the fence. The live halo comes from the U6 fog layer on both platforms |

## Device smoke-check items (renderer surface not JVM-provable)

- Open the summary of a seeded walk with ≥1 arrival: each arrival renders as
  a **soft glow disc (~26 px radius at 0.28 opacity) with a small bright
  core (4 px at 0.9)** in the hour's light color — golden `#C4956A`, midday
  `#D2B283`, night `#A9AFBC` — with **no pin icon** at that coordinate.
- Ordinary user waypoints on the same walk keep their glyph pins.
- Glow draws beneath the core (core visibly brighter center).
- The seek card sits between the intention card and the elevation profile;
  zero-arrival seek walks show the standard summary with no card and no
  halos (arrival-less = no SeekArrival annotations by construction).
- Dark mode: halos keep their fixed hex (no theme inversion).
