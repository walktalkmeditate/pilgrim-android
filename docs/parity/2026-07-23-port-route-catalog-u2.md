# Collective Route Catalog / Daily Selection (U2) — iOS → Android Port Spec

> **Plan:** `docs/plans/2026-07-23-001-feat-ios-v190-parity-port-plan.md` (U2) · **Requirements:** R3, R5a
> **iOS pin:** `pilgrim-ios` @ `9a418e4` (HEAD of `main`, 2026-07-21). All Swift quotes cite `file:line@9a418e4`.
> **Canonical algorithm:** `../pilgrim-landing/js/collective-routes.js` (the web module iOS itself ported from), vectors in `js/collective-routes.test.js`. The fmix32 constants, both webPicks months, all webLines, the two-route fixture pick, and both in-season distribution counts were re-derived by running the JS module directly during spec authoring — every number below is hand-verified, not transcribed.
> **Authority:** SHIPPED Swift over plan docs. One plan-vs-Swift correction: the plan says "only the sub-one-percent horizon branch renders a distance" — true for `dailyLine`, but `contributionLine` (also U2 scope) renders TWO distances (walk + horizon magnitude) on every call. Both routes through the same formatting helper (B5).
> **Android files:** `app/src/main/java/org/walktalkmeditate/pilgrim/data/collective/routes/{CollectiveRoute,CollectiveRouteCatalog}.kt` + `app/src/test/.../CollectiveRouteCatalogTest.kt` + fixtures under `app/src/test/resources/collective/`. Pure Kotlin + `java.time`/`java.text` only — no Android framework imports.

## B1. Entry model — CollectiveRoute + Kind

**iOS** (`Pilgrim/Models/Collective/CollectiveRoute.swift:6-41@9a418e4`):

```swift
struct CollectiveRoute: Equatable {
    /// A horizon has no name a pilgrim would recognise, only a preposition and
    /// an object: "around the Earth", "to the Sun".
    enum Kind: Equatable {
        case route(nameEn: String)
        case cosmic(preposition: String, body: String)
    }
    let id: String
    let kind: Kind
    /// Length in kilometres — the artifact is metric throughout, converted to the pilgrim's unit at render time.
    let km: Double
    /// A complete, unit-free sentence naming who has walked this entry, baked upstream so a curator can edit it without an app release.
    let companyLine: String
    let bestMonths: [Int]
    let peakMonths: [Int]
    ...
    var isCosmic: Bool {
        if case .cosmic = kind { return true }
        return false
    }
```

**Android:** `data class CollectiveRoute(id: String, kind: Kind, km: Double, companyLine: String, bestMonths: List<Int> = emptyList(), peakMonths: List<Int> = emptyList())` with `sealed interface Kind { data class Route(nameEn: String); data class Cosmic(preposition: String, body: String) }` and `val isCosmic get() = kind is Kind.Cosmic`. `data class` mirrors `Equatable`. Default-empty month lists mirror the iOS memberwise init defaults (`bestMonths: [Int] = []`).

## B2. Seasonal weighting — peak gated behind best

**iOS** (`CollectiveRoute.swift:45-65@9a418e4`):

```swift
static let baseWeight = 1
static let inSeasonBonus = 2
static let peakBonus = 3

/// Peak is an intensifier on being in season, never a boost of its own: a
/// route whose peak months fall outside its best months stays at base weight.
func weight(inMonth month: Int) -> Int {
    guard !isCosmic else { return Self.baseWeight }
    guard bestMonths.contains(month) else { return Self.baseWeight }
    let inSeasonWeight = Self.baseWeight + Self.inSeasonBonus
    guard peakMonths.contains(month) else { return inSeasonWeight }
    return inSeasonWeight + Self.peakBonus
}
```

**Web** (`collective-routes.js` `weightFor`): `if (entry.kind === 'cosmic') return WEIGHT_BASE; var w = WEIGHT_BASE; if (inList(entry.bestMonths, month)) { w += WEIGHT_BEST; if (inList(entry.peakMonths, month)) w += WEIGHT_PEAK; } return w;` — same gate, `1/2/3` constants.

**Android claims:** constants `BASE_WEIGHT = 1`, `IN_SEASON_BONUS = 2`, `PEAK_BONUS = 3`; cosmic → always 1; month not in `bestMonths` → 1 (even if in `peakMonths` — the gate); best-not-peak → `inSeasonWeight = 3` as a **named intermediate** (plan requirement); best-and-peak → 6. Hand-verified against the JS vectors: kumano Oct = 6, kumano Jul = 1, camino-shaped Jul (peak-not-best) = 1, camino-shaped May = 3, sparse = 1, cosmic = 1.

## B3. Daily-line phrasing — the eight branches

**iOS** (`CollectiveRoute.swift:69-152@9a418e4`):

```swift
static let beginningLine = "The path is beginning."
/// Below this a horizon's percentage rounds to something meaningless, so the remaining distance is stated instead.
private static let horizonPercentFloor = 1.0
/// `Int(_:)` traps above `Int.max`; a nonsense total from a bad API response should misprint, not crash a walk summary.
private static let completionsCeiling = 1_000_000_000_000.0

/// Nil when the total is merely unknown (no counter fetch has landed), because
/// the beginning-of-path line would claim the collective has walked nothing
/// while it is hundreds of kilometres in. A genuinely zero total does get it.
func dailyLine(collectiveKm: Double?) -> String? {
    guard let collectiveKm else { return nil }
    // Deliberate divergence: the web guards only `> 0` and prints "Infinity
    // times", where Swift would trap converting that total to Int.
    guard collectiveKm > 0, collectiveKm.isFinite else { return Self.beginningLine }
    let times = collectiveKm / km
    switch kind {
    case .route(let nameEn):
        return routeLine(times: times, nameEn: nameEn)
    case .cosmic(let preposition, let body):
        return horizonLine(times: times, remainingKm: km - collectiveKm, ...)
    }
}
private func routeLine(times: Double, nameEn: String) -> String {
    let completed = Self.wholeCompletions(times)
    if completed >= 2 { return "Together, we've walked the \(nameEn) \(completed) times." }
    if completed == 1 { return "Together, one \(nameEn) complete." }
    let rawPercent = times * 100
    let roundedPercent = Int(rawPercent.rounded())
    // Reading 100% before the route is actually complete would be a lie.
    let percent = min(99, roundedPercent)
    return "We are \(percent)% of the way to one \(nameEn)."
}
private func horizonLine(times: Double, remainingKm: Double, preposition: String, body: String) -> String {
    if times >= 1 {
        let completed = Self.wholeCompletions(times)
        if completed >= 2 { return "Together, \(completed) times \(preposition) \(body)." }
        return "Together, once \(preposition) \(body)."
    }
    let percent = times * 100
    if percent >= Self.horizonPercentFloor {
        let formattedPercent = String(format: "%.1f", percent)
        return "We are \(formattedPercent)% of the way \(preposition) \(body)."
    }
    // The one branch that states a raw distance, so the one that must honour the pilgrim's unit.
    let remaining = Self.formatted(km: remainingKm, rounding: .wholeNumbers)
    return "\(remaining) \(preposition) \(body)."
}
private static func wholeCompletions(_ times: Double) -> Int {
    Int(min(times.rounded(.down), completionsCeiling))
}
```

**Android claims, branch-for-branch (the eight):**

1. `collectiveKm == null` → `null` (unknown total renders nothing — NOT the beginning line).
2. `collectiveKm <= 0 || !collectiveKm.isFinite()` → `"The path is beginning."` (NaN falls here through the finiteness check; iOS's Swift-over-web divergence — the web would print "Infinity times" — is ported as shipped).
3. Route, `completed >= 2` → `"Together, we've walked the {nameEn} {completed} times."` — `completed` interpolated plain (NO thousands grouping; Swift `\(Int)`).
4. Route, `completed == 1` → `"Together, one {nameEn} complete."`
5. Route, `completed == 0` → `"We are {percent}% of the way to one {nameEn}."` with `percent = min(99, round(times * 100))` — the 99 clamp; `Double.roundToInt()` matches Swift `.rounded()` (half away from zero) for all positive inputs reachable here (`times < 1` ⇒ `rawPercent < 100`).
6. Cosmic, `times >= 1`, `completed >= 2` → `"Together, {completed} times {preposition} {body}."`
7. Cosmic, `times >= 1`, `completed < 2` → `"Together, once {preposition} {body}."` (note: reachable with `completed == 1` only; `times >= 1` ⇒ `floor >= 1`).
8. Cosmic, `percent >= 1.0` → `"We are {percent:0.0}% of the way {preposition} {body}."`; below the floor → `"{remaining} {preposition} {body}."` where `remaining = formatted(km - collectiveKm, wholeNumbers, units)` — the only `dailyLine` branch that honours the unit preference (B5).

`wholeCompletions` on Android: `min(floor(times), 1_000_000_000_000.0).toLong()` — `Long` stands in for Swift's 64-bit `Int`; the `1e12` ceiling keeps the conversion in safe range exactly as iOS pins it.

## B4. Contribution phrasing — second function, same type

**iOS** (`CollectiveRoute.swift:105-121@9a418e4`):

```swift
/// The walk-summary phrasing: this walk's distance against the day's entry,
/// then the entry's own sentence about who has walked it. Needs no collective
/// total, so it renders on a fresh offline install.
func contributionLine(walkKm: Double) -> String {
    let walk = Self.formatted(km: walkKm, rounding: .oneDigit)
    switch kind {
    case .route(let nameEn):
        return "Your \(walk) against the \(nameEn). \(companyLine)"
    case .cosmic(let preposition, let body):
        // Nameless, so its magnitude carries the contrast instead.
        let magnitude = Self.formatted(km: km, rounding: .wholeNumbers)
        return "Your \(walk) against \(magnitude) \(preposition) \(body). \(companyLine)"
    }
}
```

**Android:** `fun contributionLine(walkKm: Double, units: UnitSystem): String` on `CollectiveRoute`, non-null (needs no collective total); route branch = walk distance (one-digit) + `nameEn` + `companyLine`; cosmic branch = walk distance + horizon magnitude (whole numbers) + preposition + body + `companyLine`. The catalog-level wrapper (`contributionLine(epochMillis, walkKm, units)`) maps through `entry(...)` and is null only when no entry resolves (empty catalog) — mirroring iOS's `entry(for: date).map { ... }`.

## B5. Distance formatting — the unit-preference seam

**iOS** (`CollectiveRoute.swift:154-157@9a418e4`):

```swift
private static func formatted(km: Double,
                              rounding: CustomMeasurementFormatting.FormattingRoundingType) -> String {
    StatsHelper.string(for: km, unit: UnitLength.kilometers, type: .distance, rounding: rounding)
}
```

`StatsHelper.string` → `CustomMeasurementFormatting.string(forMeasurement:type:rounding:)` (`CustomMeasurementFormatting.swift:26-67@9a418e4`): a `MeasurementFormatter` with `unitOptions = .providedUnit`, `roundingIncrement = 1` (`.wholeNumbers`) or `0.1` (`.oneDigit`), converting to `UserPreferences.distanceMeasurementType.safeValue` (`UnitLength.kilometers` or `.miles`). Observable output shape (pinned by iOS's own tests): decimal style with en-US grouping — `"383,706 km"`, `"238,424 mi"`, `"40,075 km"`, `"4.2 km"`, `"2.6 mi"`.

**Android:** the phrasing functions take `units: UnitSystem` (`data/units/UnitSystem.kt` — a pure-Kotlin enum already storing iOS's `"kilometers"/"miles"` symbols) as an explicit parameter, replacing iOS's global `UserPreferences` read — same pattern as `WalkFormat` ("required (not defaulted) so a missing caller surfaces as a compile error"). Formatting itself is a private helper on `CollectiveRoute`:

- **Conversion:** miles = `km / 1.609344` (the exact statute-mile definition `MeasurementFormatter` uses). **Deliberately NOT `WalkFormat`'s `KM_PER_MI = 0.621371` multiplier** — hand-verified: `383_705.5 / 1.609344 = 238_423.544 → "238,424"` (matches iOS's pinned `"238,424 mi"`), while `383_705.5 × 0.621371 = 238_423.470 → "238,423"` (fails the vector). `WalkFormat` is also the wrong seam structurally: it lives in `ui.walk` (data → ui dependency inversion) and formats meters as `%.2f` with no grouping — neither shape exists in this surface.
- **Whole numbers:** `DecimalFormat("#,##0", DecimalFormatSymbols(Locale.US))` — grouping + HALF_EVEN (DecimalFormat's default), matching `NumberFormatter`'s default rounding mode. All pinned vectors agree with JS `Math.round` too (the one half-value any test reaches, `383_705.5`, rounds to the even `383_706` under both modes).
- **One digit:** `DecimalFormat("#,##0.#", DecimalFormatSymbols(Locale.US))` — max one fraction digit, trailing `.0` dropped (`4.2 → "4.2"`, `12.0 → "12"`), matching `NumberFormatter` decimal style with `roundingIncrement 0.1` (minimumFractionDigits defaults to 0 on both platforms).
- **Horizon percent (branch 8):** `DecimalFormat("0.0", DecimalFormatSymbols(Locale.US))` — always one digit (`2.0 → "2.0"`), HALF_EVEN, matching Swift `String(format: "%.1f", _)` (IEEE round-half-even) rather than Java's `String.format("%.1f")` (HALF_UP). No pinned vector sits on a tie, but the closer mode costs nothing.
- Unit suffix `"km"`/`"mi"` — `MeasurementFormatter`'s default `.medium` unit style abbreviations, pinned by iOS's own test strings.

`Locale.US` throughout per the house numeric-formatting convention; nothing locale-sensitive enters the seed (B8 — pure integer arithmetic) or the ordering (B7 — raw code-unit comparison, no `Collator`).

## B6. Lossy decode — entry level

**iOS** (`CollectiveRoute.swift:161-210@9a418e4`):

```swift
private enum CodingKeys: String, CodingKey {
    case id, kind, km, companyLine, nameEn, preposition, body, bestMonths, peakMonths
}
/// Decoding as an enum is what drops entries the app does not understand: an
/// unrecognised value throws, and the catalog's lossy array decode absorbs it.
private enum KindMarker: String, Decodable {
    case route
    case cosmic
}
init(from decoder: Decoder) throws {
    ...
    id = try container.decode(String.self, forKey: .id)
    // Required: an entry with nobody to name cannot render the walk-summary line.
    companyLine = try container.decode(String.self, forKey: .companyLine)
    bestMonths = try container.decodeIfPresent([Int].self, forKey: .bestMonths) ?? []
    peakMonths = try container.decodeIfPresent([Int].self, forKey: .peakMonths) ?? []
    let distance = try container.decode(Double.self, forKey: .km)
    // A zero or non-finite length divides by zero in the phrasing and then
    // traps converting the ratio to Int. Rejected here so no call site guards.
    guard distance.isFinite, distance > 0 else {
        throw DecodingError.dataCorruptedError(...)
    }
    km = distance
    switch try container.decode(KindMarker.self, forKey: .kind) {
    case .route:
        let nameEn = try container.decode(String.self, forKey: .nameEn)
        kind = .route(nameEn: nameEn)
    case .cosmic:
        let preposition = try container.decode(String.self, forKey: .preposition)
        let body = try container.decode(String.self, forKey: .body)
        kind = .cosmic(preposition: preposition, body: body)
    }
}
```

**Android:** a private `@Serializable` DTO (`id`, `kind: String`, `km: Double`, `companyLine` required; `nameEn`, `preposition`, `body` nullable; `bestMonths`/`peakMonths` default `emptyList()`) decoded per-element with `runCatching { json.decodeFromJsonElement(...) }` — a failed element yields `null` and is dropped, mirroring `LossyDecodable` (B7). Post-decode validation drops (returns null for): non-finite or non-positive `km`; `kind` not in `{"route", "cosmic"}`; `"route"` missing `nameEn`; `"cosmic"` missing `preposition` or `body`. Unknown JSON keys (the artifact's `reflections`/`annual`) are ignored, matching Codable's inherent tolerance.

## B7. Catalog decode, canonical pool order, provenance

**iOS** (`Pilgrim/Models/Collective/CollectiveRouteCatalog.swift:6-46,86-121@9a418e4`):

```swift
/// Content-derived, so it carries no ordering — compared for inequality
/// rather than `>` so a rollback to an earlier artifact also reaches devices.
let version: String
/// The selection pool in canonical order: routes by identifier ascending, then
/// horizons as the artifact lists them — re-derived, never trusted from the wire.
let entries: [CollectiveRoute]
static let empty = CollectiveRouteCatalog(version: "", entries: [])
init(version: String, entries: [CollectiveRoute]) {
    self.version = version
    self.entries = Self.canonicallyOrdered(entries)
}
/// The decode path, which keeps the artifact's two arrays apart. Which array an
/// entry arrived in is the contract, not its decoded `kind`: the web sorts
/// `pilgrimages` and appends `horizons` untouched, so a mis-filed cosmic entry
/// among the pilgrimages would sort here and not there, desyncing every date.
private init(version: String, pilgrimages: [CollectiveRoute], horizons: [CollectiveRoute]) {
    self.version = version
    self.entries = Self.sortedById(pilgrimages) + horizons
}
/// Kind stands in for provenance where a caller holds one flat list and the arrays are gone.
static func canonicallyOrdered(_ entries: [CollectiveRoute]) -> [CollectiveRoute] {
    sortedById(entries.filter { !$0.isCosmic }) + entries.filter(\.isCosmic)
}
/// UTF-16 code units, because that is what JavaScript's `<` compares. Swift's
/// native `<` agrees on today's ASCII ids and diverges on the first accented one.
private static func sortedById(_ entries: [CollectiveRoute]) -> [CollectiveRoute] {
    entries.sorted { $0.id.utf16.lexicographicallyPrecedes($1.id.utf16) }
}
...
/// A dropped entry is damage limitation, not graceful degradation: every entry
/// feeds the day's total weight and the seed is taken modulo it, so losing one
/// silently re-resolves *every* date. A new `kind` is therefore the worst case,
/// not the safe one, and has to reach the app before it reaches the artifact.
init(from decoder: Decoder) throws {
    ...
    let routes = try container.decodeIfPresent([LossyDecodable<CollectiveRoute>].self, forKey: .pilgrimages) ?? []
    let horizons = try container.decodeIfPresent([LossyDecodable<CollectiveRoute>].self, forKey: .horizons) ?? []
    self.init(version: version, pilgrimages: routes.compactMap(\.value), horizons: horizons.compactMap(\.value))
}
```

**Web** (`collective-routes.js` `orderedEntries`): `asset.pilgrimages.slice().sort(function(a,b){ return a.id < b.id ? -1 : a.id > b.id ? 1 : 0; })` then `.concat(asset.horizons.slice())` — `// horizons keep asset order: Earth, Moon, Sun`.

**Android claims, one-for-one:**

- `class CollectiveRouteCatalog` with a public `(version, entries)` constructor that re-derives canonical order via `canonicallyOrdered` (kind stands in for provenance), and a private `(version, pilgrimages, horizons)` constructor used by decode that preserves array provenance (`sortedById(pilgrimages) + horizons` — no kind filter). Kotlin allows both because the arities differ. `equals`/`hashCode` over `(version, entries)` mirror `Equatable`.
- `sortedById` = `sortedBy { it.id }` — Kotlin `String.compareTo` compares `Char`s, i.e. UTF-16 code units, exactly what iOS's `utf16.lexicographicallyPrecedes` and JS `<` compare. No `Collator`, no locale.
- Horizons keep artifact order — never sorted.
- `version` compared by callers with `!=`, never `>` (U3 contract; quoted here so U3 doesn't re-derive it).
- Envelope: `version` required (envelope failure throws out of `decode`); `pilgrimages`/`horizons` each optional, default empty; each element decoded lossily per B6.
- `EMPTY = CollectiveRouteCatalog("", emptyList())`.
- `decode(text: String)` owns a private `Json { ignoreUnknownKeys = true }` rather than taking the injected project `Json` — iOS's parallel is the service holding "a stock `JSONDecoder` with no configuration" (`UnitTests/Helpers/CollectiveArtifactFixtures.swift:18-21@9a418e4`). Codable ignores unknown keys by nature; kotlinx's default `Json` does not, and a strict `Json` here would lossily drop every artifact entry (they all carry `reflections`/`annual` in the shipped bootstrap) — an empty catalog wearing a successful decode. Owning the decoder makes that misconfiguration unrepresentable (D3).

## B8. UTC-day seed

**iOS** (`CollectiveRouteCatalog.swift:125-151@9a418e4`):

```swift
private static let utcCalendar: Calendar = {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = TimeZone(identifier: "UTC") ?? .gmt
    return calendar
}()
/// Seed and month from one calendar lookup — selection needs both, and these are ICU calls, not arithmetic.
static func utcDay(of date: Date) -> (seed: UInt32, month: Int) {
    let components = utcCalendar.dateComponents([.year, .month, .day], from: date)
    let year = components.year ?? 0
    let month = components.month ?? 1
    let day = components.day ?? 0
    let packed = year * 10_000 + month * 100 + day
    return (UInt32(truncatingIfNeeded: packed), month)
}
```

**Web** (`collective-routes.js` `utcSeed`): `utcDate.getUTCFullYear() * 10000 + (utcDate.getUTCMonth() + 1) * 100 + utcDate.getUTCDate()`.

**Android:** `CollectiveRouteSeed.utcDay(epochMillis: Long): UtcDay(seed: UInt, month: Int)` — `Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC).toLocalDate()`, then `(year * 10_000 + monthValue * 100 + dayOfMonth).toUInt()` (Kotlin `Int.toUInt()` reinterprets bits = `UInt32(truncatingIfNeeded:)` = JS `>>> 0`). Gregorian/ISO agreement holds for every reachable date. Pinned: 2026-10-07 → `20_261_007u`; time-of-day 23:59:59 UTC → same seed. `Date` → epoch-millis `Long` is the house time representation (seek spec D3).

## B9. fmix32 scramble — hand-verified constants

**iOS** (`CollectiveRouteCatalog.swift:153-163@9a418e4`):

```swift
/// The fmix32 finalizer the web scrambles its date seed with. Both multiplies
/// must use `&*`: plain `UInt32` multiplication traps for essentially every
/// input here, where JavaScript's `Math.imul` keeps the low 32 bits.
static func hash(_ seed: UInt32) -> UInt32 {
    let multiplier: UInt32 = 0x45d9_f3b
    var hashed = seed
    hashed = (hashed ^ (hashed >> 16)) &* multiplier
    hashed = (hashed ^ (hashed >> 16)) &* multiplier
    return hashed ^ (hashed >> 16)
}
```

**Web** (`collective-routes.js`): `function hashSeed(n){ var h = n >>> 0; h = Math.imul(h ^ (h >>> 16), 0x45d9f3b); h = Math.imul(h ^ (h >>> 16), 0x45d9f3b); return (h ^ (h >>> 16)) >>> 0; }`

**Android:** `fun hash(seed: UInt): UInt` — `val multiplier = 0x45d9f3bu` (`0x45d9_f3b` == `0x45d9f3b` == 73_244_475 — same constant three ways, hand-checked), two rounds of `xor(shr 16)` then wrapping `UInt` multiply (Kotlin `UInt.times` wraps like `&*`/`Math.imul`), final `xor(shr 16)`. `shr` on `UInt` is logical, matching `>>>`/UInt32 `>>`. Pinned vectors (recomputed via the JS module during authoring, byte-identical to the iOS test pins):

| seed | hash |
|---|---|
| 20_261_007 | 3_837_869_072 |
| 20_260_101 | 1_575_279_303 |
| 1 | 824_515_495 |
| 0 | 0 |
| 4_294_967_295 | 539_527_247 |

## B10. Selection — weighted walk over the canonical pool

**iOS** (`CollectiveRouteCatalog.swift:52-80@9a418e4`):

```swift
/// The single entry every pilgrim on earth sees for this UTC day, weighted by
/// season. Without the scramble, consecutive dates walk runs of the same entry.
func entry(for date: Date) -> CollectiveRoute? {
    let day = CollectiveRouteSeed.utcDay(of: date)
    var totalWeight = 0
    for entry in entries {
        totalWeight += entry.weight(inMonth: day.month)
    }
    guard totalWeight > 0 else { return nil }
    let scrambled = CollectiveRouteSeed.hash(day.seed)
    var remaining = Int(scrambled % UInt32(totalWeight))
    for entry in entries {
        remaining -= entry.weight(inMonth: day.month)
        if remaining < 0 { return entry }
    }
    return entries.last
}
func dailyLine(for date: Date, collectiveKm: Double?) -> String? {
    entry(for: date).flatMap { $0.dailyLine(collectiveKm: collectiveKm) }
}
/// Anchored to the walk's own date, so reopening an old walk shows what it showed the day it ended.
func contributionLine(for date: Date, walkKm: Double) -> String? {
    entry(for: date).map { $0.contributionLine(walkKm: walkKm) }
}
```

(The web builds a literal `weighted[]` expansion and indexes `hashSeed(utcSeed) % weighted.length` — iOS's remaining-walk is arithmetically identical: same order, same modulus, same landing slot. Hand-checked on 2026-10-07 against the two-route fixture: total 12, `3_837_869_072 % 12 = 8`, frances occupies 0–2, kumano 3–8 → kumano, both formulations.)

**Android:** `fun entry(epochMillis: Long): CollectiveRoute?` — sum weights over `entries` in order; `totalWeight <= 0` (empty catalog) → null; `remaining = (hash(day.seed) % totalWeight.toUInt()).toInt()`; subtract weights in order, return the entry that drives `remaining` negative; `entries.last()` fallback (unreachable in practice, ported anyway). `dailyLine(epochMillis, collectiveKm, units)` and `contributionLine(epochMillis, walkKm, units)` delegate exactly as iOS's `flatMap`/`map`.

## Divergences (conscious) and resolved ambiguities

| # | Divergence | Reason |
|---|---|---|
| D1 | `Date` → `epochMillis: Long` on `entry`/`dailyLine`/`contributionLine`; `CollectiveRouteSeed` takes millis. | House time representation (seek spec D3; Room stores walk timestamps as millis). UTC-day derivation via `java.time` at `ZoneOffset.UTC` is the type-encoded UTC guarantee (Stage 6-A lesson). |
| D2 | Unit preference is an explicit `units: UnitSystem` parameter on both phrasing functions; iOS reads the `UserPreferences.distanceMeasurementType` global inside `formatted`. | Android has no ambient preference global by design (`WalkFormat` precedent: "required (not defaulted) so a missing caller surfaces as a compile error"). U5/U6 own the reactive preference read and re-call on change. |
| D3 | Formatting via `java.text.DecimalFormat` with `Locale.US` symbols inside the model, not via `ui.walk.WalkFormat` and not via `MeasurementFormatter`. | `WalkFormat` is UI-layer (dependency direction), formats meters not km, has neither grouping nor the one-digit/whole shapes, and its `0.621371` factor **fails the pinned miles vector** (`238,423` vs iOS's `238,424` — hand-verified, B5). DecimalFormat's HALF_EVEN default matches `NumberFormatter`'s. |
| D4 | Miles conversion is `km / 1.609344` (exact statute mile), duplicated from the measurement definition rather than reusing `WalkFormat.KM_PER_MI = 0.621371`. | The reciprocal rounding error is user-visible at horizon magnitudes (B5). Reconciling `WalkFormat`'s constant is out of U2 scope (would ripple every existing surface + test). |
| D5 | `wholeCompletions` returns `Long` (Swift `Int` is 64-bit on all Apple targets). Interpolates without grouping on both platforms. | Same numeric range, same rendered text. |
| D6 | `decode(text)` owns a private `Json { ignoreUnknownKeys = true }` instead of the injected project `Json`. | Codable ignores unknown keys inherently; kotlinx does not. A strict decoder here would silently drop **every** shipped entry (all carry `reflections`/`annual`) — the exact "empty catalog wearing a successful decode" failure the lossy design exists to prevent. iOS's own fixture helper pins "a stock `JSONDecoder` with no configuration ... which is what the service holds". U3's service must call this `decode`, not roll its own. |
| D7 | `LossyDecodable<T>` wrapper → per-element `runCatching { decodeFromJsonElement }` + explicit validation nulls. | kotlinx has no lossy-list primitive; `runCatching` at element granularity produces the identical drop semantics (envelope errors still throw). Synchronous code — no CancellationException hazard. |
| D8 | No `@Immutable`/Compose annotations on U2 models. | Plan directive: "Pure Kotlin, no Android deps in the model." U5/U6 must add stability handling at their seam if these cross into a LazyList (Stage 4-C lesson) — flagged in the U2 report. |
| D9 | Selection is on the class's instance (`entries` already canonical); no re-sort per call. | Identical to iOS — canonical order is established once at construction on both platforms. |

## Fixture provenance (the documented most-likely first failure)

iOS pins vectors against **two different fixtures** and the pairing is load-bearing (`UnitTests/CollectiveRouteCatalogTests.swift:7-16@9a418e4`: "Every vector pinned against this fixture is a property of THESE two routes ... Asserting the web's published numbers against the bundled artifact fails in a way that looks exactly like a broken port."):

- **Two-route fixture** (`fixtureJSON`, 2 routes + 3 horizons) owns: the web's published pick `2026-10-07 → kumano-kodo`, and the October in-season count **26 over days 1–30**. → Android `app/src/test/resources/collective/collective-routes-two-route-fixture.json`, transcribed from the iOS test file verbatim (including its two documented non-behavioural adaptations from the JS source: explicit `"kind": "route"` and `companyLine`s).
- **Production-artifact parity fixture** (`collectiveParityFixtureJSON`, `UnitTests/Helpers/CollectiveArtifactFixtures.swift:47-77@9a418e4` — the shipped `pilgrim-landing/assets/collective-routes.json` with `reflections`/`annual` stripped, version `"0faeb638520c"`) owns: all 62 `webPicks` (Oct 2026 + Jan 2027), all 10 `webLines` at `collectiveKm = 696.98`, `2026-10-07 → camino-primitivo` (the divergence guard), October in-season **21 over days 1–31**, the consecutive-day scatter bounds (≥20 changes, ≥5 distinct), and the horizon-day `2026-10-12 → around-earth`. → Android `app/src/test/resources/collective/collective-routes-parity-fixture.json`, transcribed verbatim. (The full bundled artifact with `reflections`/`annual` intact becomes U3's bootstrap asset; iOS guards fixture↔bundle agreement in `CollectiveRouteBundledArtifactTests`, which is U3 scope.)

Both months of `webPicks`, all `webLines`, both distribution counts, and both named picks were regenerated from `collective-routes.js` over these exact entry sets during spec authoring — all byte-identical to the iOS pins.

## Test parity map

| iOS test (`UnitTests/CollectiveRouteCatalogTests.swift@9a418e4`) | Android test (`CollectiveRouteCatalogTest.kt`) |
|---|---|
| `testDecode_readsBothArraysIntoOneEntryList` | `decode reads both arrays into one entry list` |
| `testDecode_ordersRoutesByIdThenAppendsHorizonsInArtifactOrder` | `decode orders routes by id then appends horizons in artifact order` |
| `testDecode_bindsRouteAndCosmicPayloads` | `decode binds route and cosmic payloads` |
| `testDecode_dropsEntryWithUnrecognisedKind` (+ still selects) | `decode drops entry with unrecognised kind and survivors still select` |
| `testDecode_dropsEntryMissingItsDistance` | `decode drops entry missing its distance` |
| `testDecode_dropsEntryWithNonPositiveDistance` | `decode drops entry with non positive distance` |
| `testDecode_dropsRouteMissingItsName` | `decode drops route missing its name` |
| `testDecode_dropsEntryMissingItsCompanyLine` | `decode drops entry missing its company line` |
| `testDecode_treatsAbsentSeasonArraysAsNoSeasonality` | `decode treats absent season arrays as no seasonality` |
| `testDecode_survivesAMissingHorizonsArray` | `decode survives a missing horizons array` |
| `testEmpty_hasNoEntries` | `empty catalog has no entries` |
| `testUtcSeed_packsTheUtcCalendarDate` | `utc seed packs the utc calendar date` |
| `testUtcSeed_ignoresTheTimeOfDay` | `utc seed ignores the time of day` |
| `testHash_matchesTheWebScramble` (5 vectors) | `hash matches the web scramble` |
| `testWeight_bestAndPeakMonth_takesBothBonuses` | `weight best and peak month takes both bonuses` |
| `testWeight_offSeasonMonth_takesNeitherBonus` | `weight off season month takes neither bonus` |
| `testWeight_bestButNotPeakMonth_takesOnlyTheSeasonBonus` | `weight best but not peak month takes only the season bonus` |
| `testWeight_peakButNotBestMonth_takesNoBonusAtAll` | `weight peak but not best month takes no bonus at all` |
| `testWeight_entryWithNoSeasonality_staysAtBase` | `weight entry with no seasonality stays at base` |
| `testWeight_cosmicHorizon_isConstantAcrossTheYear` | `weight cosmic horizon is constant across the year` |
| `testEntryForDate_reproducesTheWebsPinnedFixtureVector` | `two route fixture reproduces the webs pinned fixture vector` |
| `testEntryForDate_octoberFavoursInSeasonRoutes` (fixture, 26/30) | `two route fixture october favours in season routes` |
| `testEntryForDate_isStableAcrossRepeatedCalls` | `entry is stable across repeated calls` |
| `testEntryForDate_agreesAcrossTheWholeUtcDay` | `entry agrees across the whole utc day` + `entry agrees across zones on the same utc day` (Android-only plan scenario, zone-constructed timestamps) |
| `testEntryForDate_reorderingTheRoutesDoesNotChangeTheSelection` | `reordering the routes does not change the selection` |
| `testCanonicallyOrdered_keepsHorizonsInTheOrderGiven` | `canonically ordered keeps horizons in the order given` |
| `testEntryForDate_emptyCatalog_returnsNothing` | `empty catalog selects nothing` |
| `testEntryForDate_horizonsOnlyCatalog_stillSelects` | `horizons only catalog still selects` |
| `testDailyLine_unknownTotal_saysNothing` | `daily line unknown total says nothing` |
| `testDailyLine_zeroTotal_saysThePathIsBeginning` | `daily line zero total says the path is beginning` |
| `testDailyLine_routeWalkedManyTimes_countsTheCompletions` | `daily line route walked many times counts the completions` |
| `testDailyLine_routeWalkedOnce_saysOneComplete` | `daily line route walked once says one complete` |
| `testDailyLine_routeNotYetReached_statesAPercentage` | `daily line route not yet reached states a percentage` |
| `testDailyLine_routeAlmostReached_clampsBelowOneHundredPercent` | `daily line route almost reached clamps below one hundred percent` |
| `testDailyLine_horizonReachedTwice_countsTheCircuits` | `daily line horizon reached twice counts the circuits` |
| `testDailyLine_horizonReachedExactlyOnce_saysOnce` | `daily line horizon reached exactly once says once` |
| `testDailyLine_horizonAtOrAboveOnePercent_statesOneDecimal` | `daily line horizon at or above one percent states one decimal` |
| `testDailyLine_horizonBelowOnePercent_statesTheRemainingDistance` | `daily line horizon below one percent states the remaining distance` |
| `testDailyLine_horizonBelowOnePercent_rendersInMilesWhenPreferred` | `daily line horizon below one percent renders in miles when preferred` |
| `testDailyLine_nonFiniteTotal_saysThePathIsBeginning` | `daily line non finite total says the path is beginning` |
| `testContributionLine_route_placesTheWalkAgainstItAndNamesItsCompany` | `contribution line route places the walk against it and names its company` |
| `testContributionLine_horizon_statesTheHorizonsMagnitude` | `contribution line horizon states the horizons magnitude` |
| `testContributionLine_horizonDay_isNeverSkipped` | `contribution line horizon day is never skipped` |
| `testContributionLine_respectsThePilgrimsUnit` | `contribution line respects the pilgrims unit` |
| `testContributionLine_emptyCatalog_saysNothing` | `contribution line empty catalog says nothing` |
| `testEntryForDate_agreesWithTheWebEveryDayOfTwoSampleMonths` (62 vectors) | `entry agrees with the web every day of two sample months` |
| `testDailyLine_agreesWithTheWebEveryDayOfTwoSampleMonths` | `daily line agrees with the web every day of two sample months` |
| `testEntryForDate_productionCatalogDivergesFromTheWebsFixtureVector` | `production catalog diverges from the webs fixture vector` |
| `testEntryForDate_octoberFavoursInSeasonRoutes` (production, 21/31) | `production october favours in season routes` |
| `testEntryForDate_consecutiveDaysScatter` | `consecutive days scatter` |
| — (Android-only, plan scenario) | `zero distance walk contribution renders whole kilometres` (one-digit format drops the trailing zero — pins the DecimalFormat shape D3) |
