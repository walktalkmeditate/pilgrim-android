# Stage 6-A — Celestial calculator + koan corpus (data/domain only)

Status: design
Author: autopilot run 2026-04-21
Scope: Phase 6 of the Pilgrim Android port, sub-stage A (primitives). 6-B ships the Light Reading card UI and Walk Summary integration.

## Intent

Ship the pure-data + domain layer for Pilgrim's post-walk "Light Reading": moon phase, sun times, planetary hour, and a deterministic koan selection. No UI, no VM wiring, no Walk Summary integration — those belong to 6-B.

The port plan's Phase 6 MVP is intentionally simpler than iOS's shipped Light Reading, which has grown into a 12-tier priority ladder (lunar eclipse → supermoon → seasonal marker → meteor shower → full/new moon → deep night → sunrise/sunset → golden hour → twilight → moon phase → daylight) with 63 placeholder-interpolated templates and astronomical-event lookup tables. For 6-A we ship just the four primitives + a deterministic koan pick. The richer tier system is a candidate for a later sub-stage (6-C or Phase N) once the primitives are battle-tested on device.

## Goals

- A `LightReading` aggregate that can be computed offline, deterministically, from a walk's id + timestamp + optional first-GPS-location.
- Each primitive is individually testable against published reference values (USNO almanac, SunCalc, known new-moon/full-moon dates).
- Polar-region correctness: sunrise/sunset are nullable. `acos` inputs are clamped.
- Pure Kotlin module (no Android `Context`, no Hilt), so unit tests run without Robolectric.
- Koan corpus is 40 curated wabi-sabi sayings with optional attribution, bundled as a Kotlin `const` list. English-only for MVP.

## Non-goals

- **UI rendering.** `WalkSummaryScreen` integration, card composables, emoji, planetary colors, long-press-to-copy — all 6-B territory. The domain data shape must NOT leak UI concerns (emoji strings, color ints, boolean "threshold moment" flags). UI layers can derive those freely.
- **The iOS 12-tier priority ladder.** Priority evaluators for eclipses, supermoons, meteor showers, seasonal markers — deferred. The data model can accommodate them later without breaking 6-A's shape.
- **Placeholder template interpolation.** iOS's 63 templates with `{N}`, `{unit}`, `{phaseName}` etc. substitution — deferred. 6-A koans are plain text.
- **Localization of koans.** English only. Phase 10 can address this.
- **Precise time-zone resolution from lat/lng.** The `java.time` ecosystem doesn't ship a coordinate→IANA-zone resolver; adding one (e.g. `timezoneboundarybuilder` data) is ~500KB. For MVP, planetary-hour day-of-week + local hour resolve via device `ZoneId.systemDefault()`. iOS has the same limitation. Document as a known scope.
- **Tests for extreme latitudes (> 80°).** Polar-day/night paths are tested; the exact boundary behavior near ±66.5° on solstice days is a known numerical edge. Document tolerance.

## Architecture

### Package layout

```
org.walktalkmeditate.pilgrim.core.celestial/
├── LightReading.kt         # data class + companion factory
├── MoonPhase.kt            # data class
├── SunTimes.kt             # data class (sunrise?, sunset?, solarNoon)
├── PlanetaryHour.kt        # data class
├── Planet.kt               # enum
├── Koan.kt                 # data class
├── MoonCalc.kt             # internal — Meeus synodic-period math
├── SunCalc.kt              # internal — NOAA solar algorithm
├── PlanetaryHourCalc.kt    # internal — Chaldean sequence
├── KoanPicker.kt           # internal — seeded index into Koans
└── Koans.kt                # internal — 40-entry corpus const
```

All files under `app/src/main/java/org/walktalkmeditate/pilgrim/core/celestial/`. `internal` visibility for the calculators and corpus — only the data classes + `LightReading.from(...)` factory are public.

### Public API

```kotlin
data class LightReading(
    val moon: MoonPhase,
    val sun: SunTimes?,              // null iff no location provided
    val planetaryHour: PlanetaryHour,
    val koan: Koan,
) {
    companion object {
        /**
         * Compute a LightReading for a walk. Pure function — no I/O.
         *
         * [location] is the first GPS sample from the walk (nullable; if the
         * walker never got a fix, we still render moon phase + planetary hour
         * + koan, but sun times will be null).
         *
         * [zoneId] defaults to the device's system zone. Used only for
         * planetary-hour day-of-week + local-hour derivation. Does NOT
         * affect moon or sun calculations (those are in UTC).
         */
        fun from(
            walkId: Long,
            startedAtEpochMs: Long,
            location: LocationPoint?,
            zoneId: ZoneId = ZoneId.systemDefault(),
        ): LightReading
    }
}

data class MoonPhase(
    /** English name: "New Moon", "Waxing Crescent", "First Quarter",
     *  "Waxing Gibbous", "Full Moon", "Waning Gibbous", "Last Quarter",
     *  "Waning Crescent". 8-way bucketing, matches iOS. */
    val name: String,
    /** [0.0, 1.0] — fraction of the Moon's disc illuminated. */
    val illumination: Double,
    /** [0.0, 29.530588770576) — days since last new moon. */
    val ageInDays: Double,
)

data class SunTimes(
    /** UTC. Null on polar night (sun never rises) AND polar day
     *  (sun never sets). */
    val sunrise: Instant?,
    /** UTC. Null on polar night AND polar day. */
    val sunset: Instant?,
    /** UTC. Always non-null — mid-day is always computable even if
     *  the sun is below the horizon. */
    val solarNoon: Instant,
)

enum class Planet { Saturn, Jupiter, Mars, Sun, Venus, Mercury, Moon }

data class PlanetaryHour(
    /** Ruling planet of the hour [walk's local time] was in. */
    val planet: Planet,
    /** Ruling planet of the day (day-of-week in Chaldean order). */
    val dayRuler: Planet,
)

data class Koan(
    val text: String,
    /** null for unattributed koans, else the attribution string.
     *  Stored without a leading em-dash; UI adds it if desired. */
    val attribution: String?,
)
```

### Internal algorithms

**`MoonCalc`** — simplified synodic-period method (matches iOS):
- Reference epoch: 2000-01-06 18:14 UTC (known new moon).
- Synodic month: 29.530588770576 days.
- `age(instant) = ((jd - epochJd) mod synodic + synodic) mod synodic`.
- `illumination(age) = 0.5 * (1 - cos(2π * age / synodic))`.
- Phase name: 8-way bucketing by `age / (synodic / 8)`.

**`SunCalc`** — NOAA Simplified Solar Position Algorithm:
- Julian Day Number → Julian centuries T.
- Solar declination + equation of time (standard polynomials).
- `cosHourAngle = (cos(90.833°) - sin(lat) * sin(decl)) / (cos(lat) * cos(decl))`.
  - **Guard**: clamp to `[-1, 1]` before `acos`. Floating-point noise at poles produces OOB values.
- If `cosHourAngle` clamped DID change (i.e., the real value was outside `[-1, 1]`): polar day (lat/decl same sign) or polar night (lat/decl opposite sign) → sunrise & sunset both null.
- Otherwise: compute both in UTC.
- Solar noon: always computable from EOT + longitude.
- Accuracy: ±1 min for latitudes up to ~70° (NOAA's stated range).

**`PlanetaryHourCalc`** — Chaldean sequence:
- Day ruler from day-of-week in `zoneId`:
  - SUNDAY → Sun, MONDAY → Moon, TUESDAY → Mars, WEDNESDAY → Mercury,
  - THURSDAY → Jupiter, FRIDAY → Venus, SATURDAY → Saturn.
- Chaldean order: `[Saturn, Jupiter, Mars, Sun, Venus, Mercury, Moon]`.
- Hour index within the day:
  - If `sunTimes` is available AND both sunrise/sunset are non-null:
    - Between sunrise and sunset: split into 12 daylight hours. `hourIndex` = `(now - sunrise) / ((sunset - sunrise) / 12)`, clamped to `[0, 11]`.
    - Otherwise (night): split sunset → (next day's sunrise) into 12 night hours. `hourIndex` = `12 + ...`, clamped to `[12, 23]`.
  - Else (polar region or no location): **fallback** — treat 06:00 as sunrise, 18:00 as sunset (iOS's shortcut). Daytime 06:00–18:00 = `hourIndex` 0–11; nighttime = 12–23.
- `planet` = Chaldean[(dayRulerIndex + hourIndex) mod 7].

**`KoanPicker`** — deterministic:
- Seed = mix of `walkId` and `startedAtEpochMs`:
  ```
  val seed = (walkId.toULong() * 6364136223846793005uL) xor startedAtEpochMs.toULong()
  ```
- `index = (seed mod corpus.size.toUInt()).toInt()`.
- Matches the Knuth MMIX multiplier iOS uses for its LCG, but simpler (one-shot mix, no PRNG state). 6-A doesn't need multi-draw; one koan per walk is enough.

### Koan corpus

40 sayings, contemplative voice, wabi-sabi tone, no exclamation marks, ASCII + the punctuation set iOS uses (en-dash, em-dash, smart quotes, ellipsis). Mix of unattributed, Rumi/Zen/Thich Nhat Hanh/Basho paraphrases. Each ≤ 120 chars.

Stored as:
```kotlin
internal object Koans {
    val all: List<Koan> = listOf(
        Koan("The moon does not fight the darkness. It offers light.", null),
        Koan("What is not said often speaks loudest.", null),
        Koan("The destination is never the point. The walking is.", null),
        Koan("To wander is not to be lost.", "Rumi"),
        // ... 40 total
    )
}
```

Exact corpus will be finalized during implementation — the design commits to 40 entries, ASCII + approved punctuation, attribution-optional.

## Test plan

### `MoonCalcTest`
- **Known new moon** (2024-05-08 03:22 UTC): illumination < 0.02, name = "New Moon".
- **Known full moon** (2024-06-22 01:08 UTC): illumination > 0.98, name = "Full Moon".
- **Age monotonic**: for instant and instant + 1 hour, `age` differs by `1/24` day (within float tolerance).
- **Phase name bucketing**: synthetic ages at boundaries (1 day, 5 days, 10 days, etc.) → correct name.

### `SunCalcTest`
- **Paris summer solstice** (2024-06-21, 48.8566°N, 2.3522°E): sunrise 03:47 UTC ±2 min, sunset 19:58 UTC ±2 min.
- **Paris winter solstice** (2024-12-21, 48.8566°N, 2.3522°E): sunrise 08:13 UTC ±2 min, sunset 16:02 UTC ±2 min.
- **Sydney summer solstice** (2024-12-21, −33.8688°S, 151.2093°E): sunrise 19:34 UTC 2024-12-20 ±2 min, sunset 09:08 UTC 2024-12-21 ±2 min.
- **Equator** (2024-03-20, 0°N, 0°E): sunrise ≈ 06:09 UTC, sunset ≈ 18:15 UTC, ±2 min.
- **Polar day** (2024-06-21, 80°N, 0°E): sunrise = null, sunset = null, solarNoon non-null.
- **Polar night** (2024-12-21, 80°N, 0°E): sunrise = null, sunset = null, solarNoon non-null.
- **Numerical guard**: synthetic call with `lat = 89.9999°` on solstice doesn't throw.

### `PlanetaryHourCalcTest`
- **Monday noon in UTC** (a date that's local Monday): day ruler = Moon, hour index 5-6 → specific planet (computable by hand).
- **Friday sunset in Paris** (2024-06-21 19:58 UTC-ish): day ruler = Venus, final day hour → planet in Chaldean order.
- **No-location fallback**: 6am-6pm split. 07:00 Sunday → day ruler Sun, hour index 1 → Venus.
- **Sunrise moment**: `instant` == `sunrise` to the millisecond → hour index 0 (first day hour).
- **Edge**: sunset at 00:00 next day (polar latitude, sunrise/sunset null) → falls through to 6am-6pm path.

### `KoanPickerTest`
- **Determinism**: `(walkId=1L, startedAt=100L)` picked twice → same koan.
- **Distribution sanity**: 200 synthetic `(walkId, startedAt)` pairs → every koan in the corpus is picked at least once (ensures modulo doesn't bias heavily). Not a strict uniform-distribution test; just a smoke check.
- **Non-empty corpus**: `Koans.all.isNotEmpty()`.

### `LightReadingTest` (integration)
- **Full assembly with location**: Paris 2024-06-21 10:00 UTC → non-null moon (waning gibbous region), non-null sun (sunrise/sunset both populated), planetary hour = some concrete Planet, koan from corpus.
- **Full assembly without location**: same walkId/time, `location = null` → moon + planetary hour (6am-6pm fallback) + koan, but `sun == null`.
- **Deterministic**: same inputs twice → equal `LightReading` instances (data classes compare by value).

## Risks + open items

- **Planetary hour accuracy on timezone-crossing walks.** A walk started in Tokyo but computed on a New York device yields a wrong day-of-week + hour. iOS has the same bug. Acceptable for MVP; revisit with a lat/lng→tz resolver in a later stage if users report.
- **Edge near ±66.5° on solstice days.** The exact transition between "polar day returns null" and "the sun rises for 30 seconds" is numerically sensitive. NOAA's algorithm handles it by returning null when `cosHourAngle` is out of bounds, which matches our guard. A walk started exactly on the solstice at 66.5°N would straddle the boundary; we don't guarantee the correct answer for that case. Tests avoid the boundary.
- **No leap-second handling.** `java.time.Instant` doesn't model leap seconds. NOAA's 1-minute accuracy claim is ±60 s, so leap seconds don't matter for our precision goal.

## Out of scope — explicit deferrals

- Twilight phases (civil, nautical, astronomical).
- Astronomical events (eclipses, supermoons, meteor shower peaks, seasonal markers).
- Placeholder template interpolation.
- Priority tier ladder.
- UI emoji / planetary colors / threshold-moment flags.
- Localization.
- Lat/lng → IANA time zone resolution.
- Walk Summary integration (6-B).

## Success criteria

- All tests pass under `./gradlew :app:testDebugUnitTest`.
- Reference-vector tests match published values within the stated tolerances.
- `LightReading.from(...)` is a pure function: same inputs always produce equal output.
- No Android framework imports in `core.celestial.*` (verifiable by grep).
- `Koans.all.size == 40` (or whatever final count we commit to).

## References

- iOS Light Reading: `/Users/rubberduck/GitHub/momentmaker/pilgrim-ios/Pilgrim/Models/LightReading/` and `Pilgrim/Models/Astrology/`.
- NOAA Solar Position Algorithm: https://gml.noaa.gov/grad/solcalc/calcdetails.html.
- Jean Meeus, *Astronomical Algorithms* (2nd ed., 1998) — source of the JDN + synodic-period formulas.
- Stage 4-A Goshuin seal for the deterministic-per-walk seeding precedent.
