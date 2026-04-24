# Stage 6-A implementation plan — celestial calculator + koan corpus

Branch: `stage-6a/celestial-koan`
Worktree: `/Users/rubberduck/GitHub/momentmaker/.worktrees/stage-6a-celestial-koan`
Spec: `docs/superpowers/specs/2026-04-21-stage-6a-celestial-koan-design.md`

Order: 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8. Each task produces one commit; tests pass at every step.

---

## Task 1 — Data classes + `Planet` enum (types only, no compute)

**New files:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/core/celestial/MoonPhase.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/core/celestial/SunTimes.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/core/celestial/Planet.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/core/celestial/PlanetaryHour.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/core/celestial/Koan.kt`

Each file is a single small data class or enum — see spec for exact field docs. No compute logic yet.

Test: `./gradlew :app:compileDebugKotlin` succeeds.

Commit: `feat(celestial): Stage 6-A data classes for Light Reading primitives`

---

## Task 2 — `MoonCalc` + test

**New files:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/core/celestial/MoonCalc.kt` — `internal object MoonCalc { fun moonPhase(instant: Instant): MoonPhase }`
- `app/src/test/java/org/walktalkmeditate/pilgrim/core/celestial/MoonCalcTest.kt`

Algorithm (matches iOS):
- `SYNODIC_DAYS = 29.530588770576`
- `EPOCH_JD = julianDay(Instant("2000-01-06T18:14:00Z"))` — known new moon.
- `julianDay(instant)` helper: standard formula (`(epochSeconds / 86400) + 2440587.5`).
- `age = ((jd - EPOCH_JD) mod SYNODIC_DAYS + SYNODIC_DAYS) mod SYNODIC_DAYS`.
- `illumination = 0.5 * (1 - cos(2π * age / SYNODIC_DAYS))`.
- Name bucketing: 8 buckets of width `SYNODIC_DAYS / 8`, labels "New Moon", "Waxing Crescent", "First Quarter", "Waxing Gibbous", "Full Moon", "Waning Gibbous", "Last Quarter", "Waning Crescent".

Tests (use `Instant.parse("...Z")`):
- `known new moon 2024-05-08` — illumination < 0.05, name == "New Moon". (Reference: timeanddate.com — 2024-05-08 03:22 UTC.)
- `known full moon 2024-06-22` — illumination > 0.95, name == "Full Moon". (Reference: 2024-06-22 01:08 UTC.)
- `age is non-negative and < synodic period` for 5 arbitrary instants.
- `age increases by ~1/24 day across a 1-hour step` (tolerance 1e-6).
- `phase name is one of the 8 canonical strings` for arbitrary instant.

Test: `./gradlew :app:testDebugUnitTest --tests "*MoonCalcTest"`

Commit: `feat(celestial): Stage 6-A moon phase calculator`

---

## Task 3 — `SunCalc` + test (NOAA algorithm)

**New files:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/core/celestial/SunCalc.kt` — `internal object SunCalc { fun sunTimes(instant: Instant, lat: Double, lon: Double): SunTimes }`
- `app/src/test/java/org/walktalkmeditate/pilgrim/core/celestial/SunCalcTest.kt`

Algorithm (NOAA simplified):
1. JD → T (Julian centuries).
2. `geometricMeanLongSun(T)` — polynomial in T.
3. `geometricMeanAnomalySun(T)` — polynomial.
4. `eccentricityEarthOrbit(T)`.
5. Sun equation of center, true longitude, apparent longitude.
6. Obliquity of ecliptic (corrected).
7. Declination: `asin(sin(obliquity) * sin(appLong))`.
8. Equation of time: function of obliquity, mean long, eccentricity, anomaly.
9. Hour angle at horizon (−0.833° refraction): `acos((cos(90.833°) − sin(lat)·sin(decl)) / (cos(lat)·cos(decl)))`.
10. **Clamp the `acos` argument to `[−1, 1]`**; if the pre-clamp value was out of bounds, polar → sunrise/sunset = null.
11. Solar noon in UTC minutes from midnight: `720 − 4*lon − eqTime`.
12. Sunrise: `solarNoonMinutes − 4 * ha`, sunset: `solarNoonMinutes + 4 * ha`. Convert to `Instant`.

Helper: normalize angles to radians/degrees using `Math.toRadians` / `Math.toDegrees`.

Tests (tolerance ±120 s where noted):
- `Paris summer solstice 2024-06-21` (48.8566, 2.3522) → sunrise ≈ 2024-06-21T03:47:00Z, sunset ≈ 2024-06-21T19:58:00Z.
- `Paris winter solstice 2024-12-21` → sunrise ≈ 08:13Z, sunset ≈ 16:02Z.
- `Sydney summer solstice 2024-12-21` (−33.8688, 151.2093) → sunrise ≈ 2024-12-20T19:34:00Z, sunset ≈ 2024-12-21T09:08:00Z.
- `Equator at equinox 2024-03-20` (0, 0) → sunrise ≈ 06:07Z, sunset ≈ 18:13Z.
- `Polar day 2024-06-21 at 80°N` → `sunrise == null && sunset == null && solarNoon != null`.
- `Polar night 2024-12-21 at 80°N` → same nulls, noon non-null.
- `Numerical guard does not throw at 89.9999°N` on solstice.
- `Solar noon approximately at apparent local noon` — offset of eqTime is within ±20 min of 12:00 local.

Test: `./gradlew :app:testDebugUnitTest --tests "*SunCalcTest"`

Commit: `feat(celestial): Stage 6-A NOAA sun-times calculator with polar-null handling`

---

## Task 4 — `PlanetaryHourCalc` + test

**New files:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/core/celestial/PlanetaryHourCalc.kt` — `internal object PlanetaryHourCalc { fun planetaryHour(instant: Instant, zoneId: ZoneId, sunTimes: SunTimes?): PlanetaryHour }`
- `app/src/test/java/org/walktalkmeditate/pilgrim/core/celestial/PlanetaryHourCalcTest.kt`

Algorithm:
- `localDateTime = instant.atZone(zoneId).toLocalDateTime()`.
- Day ruler from `localDateTime.dayOfWeek`: SUNDAY=Sun, MONDAY=Moon, TUESDAY=Mars, WEDNESDAY=Mercury, THURSDAY=Jupiter, FRIDAY=Venus, SATURDAY=Saturn.
- Chaldean order: `listOf(Saturn, Jupiter, Mars, Sun, Venus, Mercury, Moon)`.
- Hour index:
  - If `sunTimes` non-null AND both `sunrise` and `sunset` non-null:
    - Convert sunrise/sunset to local via `zoneId`.
    - If `localTime` within `[sunrise, sunset)`: `hourIndex = ((t - sunrise) / ((sunset - sunrise) / 12)).toInt().coerceIn(0, 11)`.
    - Else (night — before sunrise or after sunset):
      - Compute next-day sunrise by adding 1 day to date + recomputing; OR simpler for 6-A: night span = 24h − (sunset − sunrise). Hour = 12 + `((t - sunset) mod (nightSpan)) / (nightSpan / 12)`.coerceIn(12, 23). Handles across-midnight by modding.
  - Else (no sunTimes or polar region): **fallback** — 6am-6pm buckets.
    - Daytime 06:00 ≤ local < 18:00: `hourIndex = (local.hour - 6).coerceIn(0, 11)`.
    - Else: `hourIndex = 12 + ((local.hour - 18 + 24) mod 24).coerceIn(0, 11)`.
- `planetIndex = (chaldeanOrder.indexOf(dayRuler) + hourIndex) mod 7`.
- `planet = chaldeanOrder[planetIndex]`.

Tests:
- `Sunday 07:00 local with 6am-6pm fallback` (sunTimes = null) → dayRuler Sun, hourIndex 1, planet = Chaldean[index(Sun)+1 mod 7] = Venus.
- `Monday noon local with 6am-6pm fallback` → dayRuler Moon, hourIndex 6, planet = Jupiter. (Moon index=6 in Chaldean; (6+6) mod 7 = 5 → Mercury. Double-check hand calc during implementation.)
- `Paris Fri 2024-06-21 20:00 local with real sunTimes` (sunset ≈ 21:58 local, so this is still daytime hour ~11) → dayRuler Venus, planet derivable from (index(Venus)+11) mod 7.
- `Exact sunrise moment` → hourIndex 0, planet = dayRuler.
- `Exact sunset moment` → hourIndex 12, planet = chaldean[(dayRulerIdx + 12) mod 7].
- `Polar region (sunTimes with null sunrise/sunset)` → falls back to 6am-6pm.
- `Around midnight nighttime` → hourIndex in `[12, 23]`.

Reference for hand-calc verification: planetaryhours.net or the standard "calculate planetary hour" algorithm.

Test: `./gradlew :app:testDebugUnitTest --tests "*PlanetaryHourCalcTest"`

Commit: `feat(celestial): Stage 6-A Chaldean planetary-hour calculator`

---

## Task 5 — `Koans` corpus (40 entries)

**New file:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/core/celestial/Koans.kt` — `internal object Koans { val all: List<Koan> = listOf(...) }`

Source the 40 from the design spec's "delightful" exploration (Agent 3's list), audited for:
- ≤ 120 chars each.
- No exclamation marks.
- ASCII + `—` (em dash), `–` (en dash), `'` (smart apostrophe), `"` `"` (smart quotes), `…` (ellipsis) — match iOS's allowed Unicode set.
- Attributions: Rumi, Zen, Thich Nhat Hanh, Basho, Rilke, Kierkegaard where genuinely attributed; `null` for originals/unsourced proverbs.

Commit: `feat(celestial): Stage 6-A koan corpus (40 entries)`

No test file for this — corpus is exercised by `KoanPickerTest` (task 6) and `LightReadingTest` (task 8). One smoke assertion in `KoanPickerTest` will check `Koans.all.isNotEmpty()` and the forbidden-character ban.

---

## Task 6 — `KoanPicker` + test

**New files:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/core/celestial/KoanPicker.kt` — `internal object KoanPicker { fun pick(walkId: Long, startedAtEpochMs: Long): Koan }`
- `app/src/test/java/org/walktalkmeditate/pilgrim/core/celestial/KoanPickerTest.kt`

Algorithm:
```kotlin
fun pick(walkId: Long, startedAtEpochMs: Long): Koan {
    val corpus = Koans.all
    check(corpus.isNotEmpty()) { "Koan corpus must not be empty" }
    val seed = (walkId.toULong() * 6364136223846793005uL) xor startedAtEpochMs.toULong()
    val index = (seed % corpus.size.toULong()).toInt()
    return corpus[index]
}
```

Tests:
- `same inputs pick same koan` — call twice with (42L, 1_700_000_000_000L), assert equal.
- `different walks can pick different koans` — enumerate (walkId=1..200, startedAt=1_700_000_000_000) and assert ≥ 30 unique koans (out of 40) are hit, confirming reasonable distribution.
- `every koan is reachable` — across 1000 synthetic inputs, every koan in the corpus is picked at least once.
- `corpus non-empty + forbidden-char-free`:
  ```kotlin
  assertTrue(Koans.all.isNotEmpty())
  Koans.all.forEach { koan ->
      assertFalse("koan has exclamation: ${koan.text}", koan.text.contains('!'))
      assertTrue("koan too long: ${koan.text}", koan.text.length <= 120)
  }
  ```

Test: `./gradlew :app:testDebugUnitTest --tests "*KoanPickerTest"`

Commit: `feat(celestial): Stage 6-A deterministic koan picker`

---

## Task 7 — `LightReading` aggregate + factory

**New file:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/core/celestial/LightReading.kt` — data class + companion `from(...)`.

```kotlin
data class LightReading(
    val moon: MoonPhase,
    val sun: SunTimes?,
    val planetaryHour: PlanetaryHour,
    val koan: Koan,
) {
    companion object {
        fun from(
            walkId: Long,
            startedAtEpochMs: Long,
            location: LocationPoint?,
            zoneId: ZoneId = ZoneId.systemDefault(),
        ): LightReading {
            val instant = Instant.ofEpochMilli(startedAtEpochMs)
            val moon = MoonCalc.moonPhase(instant)
            val sun = location?.let { SunCalc.sunTimes(instant, it.latitude, it.longitude) }
            val planetaryHour = PlanetaryHourCalc.planetaryHour(instant, zoneId, sun)
            val koan = KoanPicker.pick(walkId, startedAtEpochMs)
            return LightReading(moon, sun, planetaryHour, koan)
        }
    }
}
```

No separate `LightReadingFactory.kt` — the companion is fine.

Test (next task).

Commit: `feat(celestial): Stage 6-A LightReading aggregate + factory`

---

## Task 8 — `LightReadingTest` integration + final verification

**New file:**
- `app/src/test/java/org/walktalkmeditate/pilgrim/core/celestial/LightReadingTest.kt`

Tests:
- `with location produces all four primitives`:
  - `LightReading.from(walkId=42L, startedAt=Instant("2024-06-21T10:00:00Z").epochMillis, location=LocationPoint(48.8566, 2.3522), zoneId=ZoneId.of("Europe/Paris"))`.
  - Assert `moon.name` one of the canonical 8, `sun != null`, `sun!!.sunrise != null`, `sun!!.sunset != null`, `planetaryHour.dayRuler` correct for a Friday (Venus), `koan.text.isNotBlank()`.
- `without location: sun is null, moon + planetaryHour + koan still computed`:
  - Same walk inputs, `location = null`.
  - Assert `sun == null`, `moon` non-null, `planetaryHour` non-null (6am-6pm fallback), `koan` non-null.
- `deterministic`: two invocations with identical inputs produce equal aggregates.
- `polar-day walk`: (location = 80°N, 0°E, 2024-06-21) — assert `sun!!.sunrise == null && sun!!.sunset == null && sun!!.solarNoon != null`, planetaryHour falls through to 6am-6pm fallback.

Run full suite to confirm no regressions:
```
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Commit: `test(celestial): Stage 6-A LightReading integration + final verification`

---

## Implementation notes

- Use `kotlin.math.*` (cos, sin, acos, PI, floor) — already in the stdlib.
- Use `java.time.Instant`, `ZoneId`, `ZonedDateTime`, `LocalTime`, `Duration`, `DayOfWeek` — standard desugared on minSdk 28.
- No new Gradle dependencies.
- Keep every file under 200 lines where possible; split helper fns into private top-level if needed.
- Verify no file imports `android.*` or `androidx.*` — grep after task 8.

## Post-implementation

- `/polish` loop until clean.
- Initial + final code review agents.
- CHECKPOINT 2 with PR + ship.
