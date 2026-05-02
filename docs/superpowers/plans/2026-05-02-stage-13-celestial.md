# Stage 13-Cel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement task-by-task.

**Goal:** Port iOS celestial-line UI + extended milestone callouts to Android Walk Summary. Reaches feature parity for sections 7 (callout extras) + 10 (celestial line) of Walk Summary.

**Architecture:** Modular calc objects in `core/celestial/` (extends Stage 6-A pattern). New `WalkSummaryCalloutProse` helper for the iOS `computeMilestone()` priority chain. `MilestoneCalloutRow` signature flips from enum to `prose: String`.

**Tech Stack:** Pure Kotlin calc + Compose. Context-injected string interpolation.

**Spec:** `docs/superpowers/specs/2026-05-02-stage-13-celestial-design.md`.

---

## Setup

Branch from `main`:

```bash
git checkout -b feat/stage-13-celestial
```

## Tasks

### Task 1: ZodiacSign + Element + Modality

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/core/celestial/ZodiacSign.kt`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/core/celestial/ZodiacSignTest.kt`

- [ ] **Step 1: Write enum**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

/**
 * Stage 13-Cel: 12-sign zodiac. Verbatim port of iOS
 * `AstrologyModels.swift` ZodiacSign + nested Element + Modality.
 * `name` and `symbol` are inline data (not strings.xml) — matches iOS
 * hardcoding pattern; localization deferred until Android adds locales.
 */
enum class ZodiacSign(val ordinal0: Int, val displayName: String, val symbol: String, val element: Element, val modality: Modality) {
    Aries(0, "Aries", "♈︎", Element.Fire, Modality.Cardinal),
    Taurus(1, "Taurus", "♉︎", Element.Earth, Modality.Fixed),
    Gemini(2, "Gemini", "♊︎", Element.Air, Modality.Mutable),
    Cancer(3, "Cancer", "♋︎", Element.Water, Modality.Cardinal),
    Leo(4, "Leo", "♌︎", Element.Fire, Modality.Fixed),
    Virgo(5, "Virgo", "♍︎", Element.Earth, Modality.Mutable),
    Libra(6, "Libra", "♎︎", Element.Air, Modality.Cardinal),
    Scorpio(7, "Scorpio", "♏︎", Element.Water, Modality.Fixed),
    Sagittarius(8, "Sagittarius", "♐︎", Element.Fire, Modality.Mutable),
    Capricorn(9, "Capricorn", "♑︎", Element.Earth, Modality.Cardinal),
    Aquarius(10, "Aquarius", "♒︎", Element.Air, Modality.Fixed),
    Pisces(11, "Pisces", "♓︎", Element.Water, Modality.Mutable),
    ;

    enum class Element(val displayName: String, val symbol: String) {
        Fire("Fire", "🜂"),
        Earth("Earth", "🜃"),
        Air("Air", "🜁"),
        Water("Water", "🜄"),
    }

    enum class Modality { Cardinal, Fixed, Mutable }

    companion object {
        fun fromIndex(idx: Int): ZodiacSign = entries[((idx % 12) + 12) % 12]
    }
}
```

- [ ] **Step 2: Tests**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

import org.junit.Assert.assertEquals
import org.junit.Test

class ZodiacSignTest {
    @Test fun fromIndex_wrapsPositive() = assertEquals(ZodiacSign.Aries, ZodiacSign.fromIndex(12))
    @Test fun fromIndex_wrapsNegative() = assertEquals(ZodiacSign.Pisces, ZodiacSign.fromIndex(-1))
    @Test fun aries_isFireCardinal() {
        assertEquals(ZodiacSign.Element.Fire, ZodiacSign.Aries.element)
        assertEquals(ZodiacSign.Modality.Cardinal, ZodiacSign.Aries.modality)
    }
    @Test fun all12_distinct() = assertEquals(12, ZodiacSign.entries.toSet().size)
}
```

- [ ] **Step 3: Verify**

```bash
./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.core.celestial.ZodiacSignTest"
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/core/celestial/ZodiacSign.kt app/src/test/java/org/walktalkmeditate/pilgrim/core/celestial/ZodiacSignTest.kt
git commit -m "feat(celestial): ZodiacSign enum + Element + Modality (Stage 13-Cel task 1)"
```

---

### Task 2: Planet enum extension (name + symbol)

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/core/celestial/Planet.kt`

- [ ] **Step 1: Read current Planet.kt to confirm shape + audit existing callers**

```bash
cat app/src/main/java/org/walktalkmeditate/pilgrim/core/celestial/Planet.kt
grep -rn "Planet\." app/src/main/java/ | grep -v "/celestial/" | head -20
grep -rn "Planet\." app/src/test/java/ | head -20
```

- [ ] **Step 2: Add name + symbol properties**

If existing Planet enum is e.g. `enum class Planet { Sun, Moon, Mercury, Venus, Mars, Jupiter, Saturn }`, modify to:

```kotlin
enum class Planet(val displayName: String, val symbol: String) {
    Sun("Sun", "☉"),
    Moon("Moon", "☽"),
    Mercury("Mercury", "☿"),
    Venus("Venus", "♀"),
    Mars("Mars", "♂"),
    Jupiter("Jupiter", "♃"),
    Saturn("Saturn", "♄"),
}
```

- [ ] **Step 3: Verify all callers compile**

```bash
./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin
```

If any caller broke (e.g., switch statement constructed from `entries`), fix the caller minimally.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/core/celestial/Planet.kt
git commit -m "feat(celestial): Planet enum gains displayName + symbol (Stage 13-Cel task 2)"
```

---

### Task 3: SeasonalMarker + ZodiacPosition + PlanetaryPosition + ElementBalance + CelestialSnapshot data classes

**Files:**
- Create: `app/src/main/java/.../core/celestial/SeasonalMarker.kt`
- Create: `app/src/main/java/.../core/celestial/ZodiacPosition.kt`
- Create: `app/src/main/java/.../core/celestial/PlanetaryPosition.kt`
- Create: `app/src/main/java/.../core/celestial/ElementBalance.kt`
- Create: `app/src/main/java/.../core/celestial/CelestialSnapshot.kt`

- [ ] **Step 1: SeasonalMarker.kt**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

/**
 * 8 turning points of the year — equinoxes/solstices + cross-quarter
 * days. Ports iOS `AstrologyModels.swift` SeasonalMarker. `displayName`
 * is hardcoded English (verbatim iOS); cross-quarter prose uses Gaelic
 * names (Imbolc/Beltane/Lughnasadh/Samhain), not English glosses.
 */
enum class SeasonalMarker(val displayName: String) {
    SpringEquinox("Spring Equinox"),
    SummerSolstice("Summer Solstice"),
    AutumnEquinox("Autumn Equinox"),
    WinterSolstice("Winter Solstice"),
    Imbolc("Imbolc"),
    Beltane("Beltane"),
    Lughnasadh("Lughnasadh"),
    Samhain("Samhain"),
}
```

- [ ] **Step 2: ZodiacPosition.kt + PlanetaryPosition.kt + ElementBalance.kt + CelestialSnapshot.kt**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

/** Position within a single zodiac sign — degree is `[0.0, 30.0)`. */
data class ZodiacPosition(val sign: ZodiacSign, val degree: Double)
```

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

/**
 * One planet's full state at a moment in time.
 *
 * `longitude` is geocentric ecliptic longitude in [0, 360) degrees.
 * `tropical` and `sidereal` are the same point projected into each
 * zodiac convention (sidereal = tropical - ayanamsa).
 * `isRetrograde` is computed from a 1-day ephemeris delta. Sun and
 * Moon always return `false`.
 * `isIngress` is true when within 1° of a sign boundary.
 */
data class PlanetaryPosition(
    val planet: Planet,
    val longitude: Double,
    val tropical: ZodiacPosition,
    val sidereal: ZodiacPosition,
    val isRetrograde: Boolean,
    val isIngress: Boolean,
)
```

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

/**
 * Tally of element counts across all 7 planetary positions.
 * `dominant` is null when there's a tie at the top — UI gracefully
 * skips the element line in that case (matches iOS behavior).
 */
data class ElementBalance(
    val counts: Map<ZodiacSign.Element, Int>,
    val dominant: ZodiacSign.Element?,
)
```

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

import org.walktalkmeditate.pilgrim.data.practice.ZodiacSystem

/**
 * Top-level celestial state for one moment in time. Time-only (no
 * location dependency). Consumed by Walk Summary celestial line +
 * SeasonalMarker callout.
 */
data class CelestialSnapshot(
    val positions: List<PlanetaryPosition>,
    val planetaryHour: PlanetaryHour,
    val elementBalance: ElementBalance,
    val system: ZodiacSystem,
    val seasonalMarker: SeasonalMarker?,
) {
    fun position(planet: Planet): PlanetaryPosition? = positions.firstOrNull { it.planet == planet }
}
```

- [ ] **Step 3: Verify compile**

```bash
./gradlew :app:compileDebugKotlin
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/core/celestial/SeasonalMarker.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/core/celestial/ZodiacPosition.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/core/celestial/PlanetaryPosition.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/core/celestial/ElementBalance.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/core/celestial/CelestialSnapshot.kt
git commit -m "feat(celestial): SeasonalMarker + ZodiacPosition + PlanetaryPosition + ElementBalance + CelestialSnapshot (Stage 13-Cel task 3)"
```

---

### Task 4: PlanetCalc — planetary longitudes + retrograde + ingress + ayanamsa + zodiacPosition

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/core/celestial/PlanetCalc.kt`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/core/celestial/PlanetCalcTest.kt`

- [ ] **Step 1: Read iOS source verbatim**

```bash
sed -n '1,400p' ../pilgrim-ios/Pilgrim/Models/Astrology/CelestialCalculator.swift
```

Port these Swift functions to Kotlin in `PlanetCalc.kt`: `lunarLongitude`, `mercuryLongitude`, `venusLongitude`, `marsLongitude`, `jupiterLongitude`, `saturnLongitude`, `geocentricForInnerPlanet` (private), `geocentricForOuterPlanet` (private), `isRetrograde`, `zodiacPosition`, `isIngress`, `ayanamsa`. Use `internal object PlanetCalc` pattern matching `MoonCalc`/`SunCalc`. All formulas verbatim from iOS — coefficients exact.

`isIngress` defensive-normalize:

```kotlin
internal fun isIngress(longitude: Double): Boolean {
    val degree = ((longitude % 30.0) + 30.0) % 30.0
    return degree < 1.0 || degree > 29.0
}
```

`zodiacPosition`:

```kotlin
internal fun zodiacPosition(longitude: Double): ZodiacPosition {
    val normalized = ((longitude % 360.0) + 360.0) % 360.0
    val signIndex = (normalized / 30.0).toInt() % 12
    val degree = normalized - signIndex * 30.0
    return ZodiacPosition(ZodiacSign.fromIndex(signIndex), degree)
}
```

`ayanamsa` (Lahiri linear):

```kotlin
internal fun ayanamsa(T: Double): Double {
    val julianYear = 2000.0 + T * 100.0
    return 23.85 + 0.01396 * (julianYear - 2000.0)
}
```

`isRetrograde`:

```kotlin
internal fun isRetrograde(planet: Planet, T: Double): Boolean {
    if (planet == Planet.Sun || planet == Planet.Moon) return false
    val deltaT = 1.0 / 36525.0
    val lonNow = planetaryLongitude(planet, T)
    val lonPrev = planetaryLongitude(planet, T - deltaT)
    var diff = lonNow - lonPrev
    if (diff > 180.0) diff -= 360.0
    if (diff < -180.0) diff += 360.0
    return diff < 0.0
}

private fun planetaryLongitude(planet: Planet, T: Double): Double = when (planet) {
    Planet.Sun -> SunCalc.solarLongitude(T)
    Planet.Moon -> lunarLongitude(T)
    Planet.Mercury -> mercuryLongitude(T)
    Planet.Venus -> venusLongitude(T)
    Planet.Mars -> marsLongitude(T)
    Planet.Jupiter -> jupiterLongitude(T)
    Planet.Saturn -> saturnLongitude(T)
}
```

For Mercury/Venus/Mars/Jupiter/Saturn longitudes + helper geocentric functions: paste iOS source verbatim, transliterate Swift → Kotlin (cos/sin/atan2/normalize). Constants exact.

- [ ] **Step 2: Tests** — port iOS `CelestialCalculatorTests.swift` cases for these functions verbatim. Minimum 12 test cases:
  - lunarLongitude at J2000.0 (T=0) within 5° of expected
  - solarLongitude at vernal equinox 2026 ≈ 0°±2°
  - mercury/venus/mars/jupiter/saturn at known reference dates
  - zodiacPosition: 0° → Aries 0; 29.999° → Aries 29.999; 30° → Taurus 0; 360° wraparound → Aries
  - isIngress: 0.5° true; 1.5° false; 29.5° true; 30.5° (handled by normalize) → 0.5° true; -0.5° → 29.5° true (defensive)
  - isRetrograde: Sun and Moon false; Mercury at known retrograde period true
  - ayanamsa increases linearly with T

- [ ] **Step 3: Run tests**

```bash
./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.core.celestial.PlanetCalcTest"
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/core/celestial/PlanetCalc.kt app/src/test/java/org/walktalkmeditate/pilgrim/core/celestial/PlanetCalcTest.kt
git commit -m "feat(celestial): PlanetCalc — 5 planetary longitudes + retrograde + ingress + ayanamsa (Stage 13-Cel task 4)"
```

---

### Task 5: SeasonalMarkerCalc

**Files:**
- Create: `app/src/main/java/.../core/celestial/SeasonalMarkerCalc.kt`
- Test: `app/src/test/java/.../core/celestial/SeasonalMarkerCalcTest.kt`

- [ ] **Step 1: Source**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

import kotlin.math.abs

/**
 * Maps the sun's ecliptic longitude to one of 8 turning points
 * (equinoxes, solstices, cross-quarter days) when within ±1.5° of
 * the canonical angle. Pure function; verbatim port of iOS
 * `CelestialCalculator.seasonalMarker(sunLongitude:)`.
 */
internal object SeasonalMarkerCalc {
    private const val THRESHOLD = 1.5

    private val anchors = listOf(
        0.0 to SeasonalMarker.SpringEquinox,
        45.0 to SeasonalMarker.Beltane,
        90.0 to SeasonalMarker.SummerSolstice,
        135.0 to SeasonalMarker.Lughnasadh,
        180.0 to SeasonalMarker.AutumnEquinox,
        225.0 to SeasonalMarker.Samhain,
        270.0 to SeasonalMarker.WinterSolstice,
        315.0 to SeasonalMarker.Imbolc,
    )

    fun seasonalMarker(sunLongitude: Double): SeasonalMarker? {
        val normalized = ((sunLongitude % 360.0) + 360.0) % 360.0
        for ((anchor, marker) in anchors) {
            val diff = abs(normalized - anchor).let { if (it > 180.0) 360.0 - it else it }
            if (diff <= THRESHOLD) return marker
        }
        return null
    }
}
```

(Cross-quarter Imbolc at 315°: sun crosses 315° around Feb 4th. Verify against iOS source — if iOS uses different anchor mapping for cross-quarters, match iOS exactly.)

- [ ] **Step 2: Tests**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeasonalMarkerCalcTest {
    @Test fun spring_equinox_at_zero() = assertEquals(SeasonalMarker.SpringEquinox, SeasonalMarkerCalc.seasonalMarker(0.0))
    @Test fun summer_solstice_at_90() = assertEquals(SeasonalMarker.SummerSolstice, SeasonalMarkerCalc.seasonalMarker(90.0))
    @Test fun autumn_equinox_at_180() = assertEquals(SeasonalMarker.AutumnEquinox, SeasonalMarkerCalc.seasonalMarker(180.0))
    @Test fun winter_solstice_at_270() = assertEquals(SeasonalMarker.WinterSolstice, SeasonalMarkerCalc.seasonalMarker(270.0))
    @Test fun beltane_at_45() = assertEquals(SeasonalMarker.Beltane, SeasonalMarkerCalc.seasonalMarker(45.0))
    @Test fun lughnasadh_at_135() = assertEquals(SeasonalMarker.Lughnasadh, SeasonalMarkerCalc.seasonalMarker(135.0))
    @Test fun samhain_at_225() = assertEquals(SeasonalMarker.Samhain, SeasonalMarkerCalc.seasonalMarker(225.0))
    @Test fun imbolc_at_315() = assertEquals(SeasonalMarker.Imbolc, SeasonalMarkerCalc.seasonalMarker(315.0))
    @Test fun threshold_edge_inside() = assertEquals(SeasonalMarker.SpringEquinox, SeasonalMarkerCalc.seasonalMarker(1.5))
    @Test fun outside_threshold_null() = assertNull(SeasonalMarkerCalc.seasonalMarker(2.0))
    @Test fun negative_normalized() = assertEquals(SeasonalMarker.SpringEquinox, SeasonalMarkerCalc.seasonalMarker(-0.5))
}
```

- [ ] **Step 3: Run + commit**

```bash
./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.core.celestial.SeasonalMarkerCalcTest"
git add app/src/main/java/org/walktalkmeditate/pilgrim/core/celestial/SeasonalMarkerCalc.kt app/src/test/java/org/walktalkmeditate/pilgrim/core/celestial/SeasonalMarkerCalcTest.kt
git commit -m "feat(celestial): SeasonalMarkerCalc — 8 turning-point detection (Stage 13-Cel task 5)"
```

---

### Task 6: CelestialSnapshotCalc — top-level snapshot + elementBalance

**Files:**
- Create: `app/src/main/java/.../core/celestial/CelestialSnapshotCalc.kt`
- Test: `app/src/test/java/.../core/celestial/CelestialSnapshotCalcTest.kt`

- [ ] **Step 1: Source**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

import java.time.Instant
import java.time.ZoneId
import org.walktalkmeditate.pilgrim.data.practice.ZodiacSystem

/**
 * Top-level calculator entry — composes PlanetCalc + PlanetaryHourCalc
 * + element-balance into a single immutable snapshot for one moment.
 * Verbatim port of iOS `CelestialCalculator.snapshot(for:system:)`.
 */
internal object CelestialSnapshotCalc {

    fun snapshot(
        atEpochMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        system: ZodiacSystem = ZodiacSystem.Tropical,
    ): CelestialSnapshot {
        val instant = Instant.ofEpochMilli(atEpochMillis)
        val jd = SunCalc.julianDayNumber(atEpochMillis)
        val T = (jd - 2451545.0) / 36525.0

        val positions = Planet.entries.map { planet ->
            val longitude = when (planet) {
                Planet.Sun -> SunCalc.solarLongitude(T)
                Planet.Moon -> PlanetCalc.lunarLongitude(T)
                Planet.Mercury -> PlanetCalc.mercuryLongitude(T)
                Planet.Venus -> PlanetCalc.venusLongitude(T)
                Planet.Mars -> PlanetCalc.marsLongitude(T)
                Planet.Jupiter -> PlanetCalc.jupiterLongitude(T)
                Planet.Saturn -> PlanetCalc.saturnLongitude(T)
            }
            val tropical = PlanetCalc.zodiacPosition(longitude)
            val sidereal = PlanetCalc.zodiacPosition(longitude - PlanetCalc.ayanamsa(T))
            PlanetaryPosition(
                planet = planet,
                longitude = longitude,
                tropical = tropical,
                sidereal = sidereal,
                isRetrograde = PlanetCalc.isRetrograde(planet, T),
                isIngress = PlanetCalc.isIngress(longitude),
            )
        }

        val sunLon = positions.first { it.planet == Planet.Sun }.longitude

        return CelestialSnapshot(
            positions = positions,
            planetaryHour = PlanetaryHourCalc.planetaryHour(atEpochMillis, zoneId),
            elementBalance = elementBalance(positions, system),
            system = system,
            seasonalMarker = SeasonalMarkerCalc.seasonalMarker(sunLon),
        )
    }

    fun elementBalance(positions: List<PlanetaryPosition>, system: ZodiacSystem): ElementBalance {
        val counts = ZodiacSign.Element.entries.associateWith { 0 }.toMutableMap()
        for (p in positions) {
            val zp = if (system == ZodiacSystem.Tropical) p.tropical else p.sidereal
            counts[zp.sign.element] = (counts[zp.sign.element] ?: 0) + 1
        }
        val maxCount = counts.values.max()
        val winners = counts.filterValues { it == maxCount }.keys
        val dominant = if (winners.size == 1) winners.first() else null
        return ElementBalance(counts = counts.toMap(), dominant = dominant)
    }
}
```

(Verify `SunCalc.julianDayNumber` + `SunCalc.solarLongitude` + `PlanetaryHourCalc.planetaryHour` signatures match what's already in Stage 6-A. Adapt if needed.)

- [ ] **Step 2: Tests** — match iOS `CelestialCalculatorTests` for snapshot. Minimum 8 cases:
  - Snapshot has 7 positions
  - Positions ordered Sun → Saturn
  - Tropical + sidereal differ by ayanamsa
  - elementBalance counts sum to 7
  - elementBalance returns dominant when one element wins
  - elementBalance returns null when 2 elements tie at top
  - planetaryHour present
  - seasonalMarker null on a non-marker day; non-null on equinox

- [ ] **Step 3: Run + commit**

```bash
./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.core.celestial.CelestialSnapshotCalcTest"
git add app/src/main/java/org/walktalkmeditate/pilgrim/core/celestial/CelestialSnapshotCalc.kt app/src/test/java/org/walktalkmeditate/pilgrim/core/celestial/CelestialSnapshotCalcTest.kt
git commit -m "feat(celestial): CelestialSnapshotCalc — top-level snapshot + element balance (Stage 13-Cel task 6)"
```

---

### Task 7: GoshuinMilestone.LongestMeditation + WalkMilestoneInput.meditateDurationMillis + detection

**Files:**
- Modify: `app/src/main/java/.../ui/goshuin/GoshuinMilestone.kt`
- Modify: `app/src/main/java/.../ui/goshuin/GoshuinMilestones.kt`
- Modify: `app/src/test/java/.../ui/goshuin/GoshuinMilestonesTest.kt`

- [ ] **Step 1: Add LongestMeditation case to sealed class**

```kotlin
// In GoshuinMilestone.kt, add:
data object LongestMeditation : GoshuinMilestone()
```

- [ ] **Step 2: Add `meditateDurationMillis: Long = 0L` to WalkMilestoneInput data class**

```kotlin
// In GoshuinMilestones.kt:
data class WalkMilestoneInput(
    // existing fields ...
    val meditateDurationMillis: Long = 0L,
)
```

Default `0L` keeps Stage 4-D's 26 existing fixtures passing.

- [ ] **Step 3: Add LongestMeditation detection + insert into precedence**

```kotlin
// In GoshuinMilestones.detect(), add detection after LongestWalk:
val isLongestMeditation = run {
    val candidates = allFinished.filter { it.meditateDurationMillis > 0L }
    if (candidates.isEmpty()) return@run false
    val maxMeditation = candidates.maxOf { it.meditateDurationMillis }
    walk.meditateDurationMillis == maxMeditation && walk.meditateDurationMillis > 0L
}

// Precedence: FirstWalk → LongestWalk → LongestMeditation → NthWalk → FirstOfSeason
return when {
    isFirstWalk -> GoshuinMilestone.FirstWalk
    isLongestWalk -> GoshuinMilestone.LongestWalk
    isLongestMeditation -> GoshuinMilestone.LongestMeditation
    isNthWalk(walkIndex + 1) -> GoshuinMilestone.NthWalk(walkIndex + 1)
    seasonFor(walk, allFinished, hemisphere)?.let { /* existing */ } != null -> ...
    else -> null
}
```

(Adapt to actual current shape of `detect()`; logic the same.)

- [ ] **Step 4: Tests — 5 new LongestMeditation cases**

```kotlin
@Test fun longest_meditation_fires_when_walk_is_max() { ... }
@Test fun longest_meditation_does_not_fire_when_other_walk_is_longer() { ... }
@Test fun longest_meditation_does_not_fire_when_no_walks_have_meditation() { ... }
@Test fun longest_meditation_fires_for_single_walk_with_any_meditation() { ... }
@Test fun longest_meditation_loses_to_longest_walk_when_both_apply() { ... }
```

- [ ] **Step 5: Verify Stage 4-D's existing 26 tests still pass with default 0L**

```bash
./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.goshuin.GoshuinMilestonesTest"
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/goshuin/GoshuinMilestone.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/ui/goshuin/GoshuinMilestones.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/goshuin/GoshuinMilestonesTest.kt
git commit -m "feat(goshuin): LongestMeditation milestone + WalkMilestoneInput.meditateDurationMillis (Stage 13-Cel task 7)"
```

---

### Task 8: GoshuinViewModel plumbing — pass meditation millis

**Files:**
- Modify: `app/src/main/java/.../ui/goshuin/GoshuinViewModel.kt`

- [ ] **Step 1: Find WalkMilestoneInput construction in GoshuinViewModel**

```bash
grep -n "WalkMilestoneInput" app/src/main/java/org/walktalkmeditate/pilgrim/ui/goshuin/GoshuinViewModel.kt
```

- [ ] **Step 2: Add `meditateDurationMillis = (walk.meditationSeconds ?: 0L) * 1000L`**

(Assumes `Walk.meditationSeconds: Long?` — verify; adjust conversion if it's already Long non-null or stored as millis.)

- [ ] **Step 3: Verify regression tests pass**

```bash
./gradlew :app:testDebugUnitTest --tests "*GoshuinViewModelTest*"
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/goshuin/GoshuinViewModel.kt
git commit -m "feat(goshuin): plumb walk.meditationSeconds into milestone detection (Stage 13-Cel task 8)"
```

---

### Task 9: WalkSummaryCalloutProse helper — priority chain + WalkSummaryCalloutInputs

**Files:**
- Create: `app/src/main/java/.../ui/walk/summary/WalkSummaryCalloutProse.kt`
- Test: `app/src/test/java/.../ui/walk/summary/WalkSummaryCalloutProseTest.kt`

- [ ] **Step 1: Source**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import android.content.Context
import java.util.Locale
import kotlin.math.floor
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.core.celestial.SeasonalMarker
import org.walktalkmeditate.pilgrim.data.units.UnitSystem

/**
 * Stage 13-Cel: pure prose helper for the Walk Summary milestone callout.
 * Mirrors iOS `WalkSummaryView.computeMilestone()` priority chain
 * (`WalkSummaryView.swift:421-461`):
 *   1. SeasonalMarker (only when celestialEnabled)
 *   2. LongestMeditation (strict-improvement-over-nonzero)
 *   3. LongestWalk (strict-improvement-over-nonzero)
 *   4. TotalDistance (threshold crossed on this walk)
 *   5. null  (NO fallthrough to FirstWalk/FirstOfSeason/NthWalk —
 *            those only appear on the Goshuin grid, not Walk Summary)
 *
 * Note: rules 2 & 3 differ from the Goshuin grid detector — the
 * grid fires on max-wins (no nonzero gate) so the FIRST walk with any
 * meditation still earns the seal, while the Walk Summary callout
 * requires strict improvement over a non-zero prior.
 */
data class WalkSummaryCalloutInputs(
    val currentDistanceMeters: Double,
    val currentMeditationSeconds: Long,
    val pastWalksMaxDistance: Double,
    val pastWalksMaxMeditation: Long,
    val pastWalksDistanceSum: Double,
    val units: UnitSystem,
    val seasonalMarker: SeasonalMarker?,
)

object WalkSummaryCalloutProse {
    private val THRESHOLDS = listOf(10, 25, 50, 100, 250, 500, 1000)

    fun compute(
        inputs: WalkSummaryCalloutInputs,
        celestialEnabled: Boolean,
        context: Context,
    ): String? {
        // 1. SeasonalMarker
        if (celestialEnabled && inputs.seasonalMarker != null) {
            return context.getString(seasonalMarkerStringRes(inputs.seasonalMarker))
        }
        // 2. LongestMeditation (strict over nonzero)
        if (inputs.currentMeditationSeconds > inputs.pastWalksMaxMeditation && inputs.pastWalksMaxMeditation > 0L) {
            return context.getString(R.string.summary_milestone_longest_meditation)
        }
        // 3. LongestWalk (strict over nonzero)
        if (inputs.currentDistanceMeters > inputs.pastWalksMaxDistance && inputs.pastWalksMaxDistance > 0.0) {
            return context.getString(R.string.summary_milestone_longest_walk)
        }
        // 4. TotalDistance
        val unitFactor = if (inputs.units == UnitSystem.Imperial) 1_609.344 else 1_000.0
        val totalDistance = inputs.pastWalksDistanceSum + inputs.currentDistanceMeters
        val pastUnits = floor((totalDistance - inputs.currentDistanceMeters) / unitFactor).toInt()
        val totalUnits = floor(totalDistance / unitFactor).toInt()
        for (m in THRESHOLDS) {
            if (totalUnits >= m && pastUnits < m) {
                val template = if (inputs.units == UnitSystem.Imperial) {
                    R.string.summary_milestone_total_distance_mi
                } else {
                    R.string.summary_milestone_total_distance_km
                }
                return context.getString(template, String.format(Locale.US, "%d", m))
            }
        }
        return null
    }

    private fun seasonalMarkerStringRes(marker: SeasonalMarker): Int = when (marker) {
        SeasonalMarker.SpringEquinox -> R.string.summary_milestone_seasonal_spring_equinox
        SeasonalMarker.SummerSolstice -> R.string.summary_milestone_seasonal_summer_solstice
        SeasonalMarker.AutumnEquinox -> R.string.summary_milestone_seasonal_autumn_equinox
        SeasonalMarker.WinterSolstice -> R.string.summary_milestone_seasonal_winter_solstice
        SeasonalMarker.Imbolc -> R.string.summary_milestone_seasonal_imbolc
        SeasonalMarker.Beltane -> R.string.summary_milestone_seasonal_beltane
        SeasonalMarker.Lughnasadh -> R.string.summary_milestone_seasonal_lughnasadh
        SeasonalMarker.Samhain -> R.string.summary_milestone_seasonal_samhain
    }
}
```

- [ ] **Step 2: Tests — 7 cases**

(Use `RuntimeEnvironment.getApplication()` for Context.)

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.core.celestial.SeasonalMarker
import org.walktalkmeditate.pilgrim.data.units.UnitSystem

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkSummaryCalloutProseTest {
    private val context: android.content.Context = ApplicationProvider.getApplicationContext()
    private fun base() = WalkSummaryCalloutInputs(
        currentDistanceMeters = 1000.0,
        currentMeditationSeconds = 0L,
        pastWalksMaxDistance = 0.0,
        pastWalksMaxMeditation = 0L,
        pastWalksDistanceSum = 0.0,
        units = UnitSystem.Metric,
        seasonalMarker = null,
    )

    @Test fun seasonalMarker_fires_when_celestialEnabled_true() {
        val inputs = base().copy(seasonalMarker = SeasonalMarker.SpringEquinox)
        assertEquals("You walked on the Spring Equinox", WalkSummaryCalloutProse.compute(inputs, celestialEnabled = true, context))
    }

    @Test fun seasonalMarker_suppressed_when_celestialEnabled_false() {
        val inputs = base().copy(seasonalMarker = SeasonalMarker.SpringEquinox)
        assertNull(WalkSummaryCalloutProse.compute(inputs, celestialEnabled = false, context))
    }

    @Test fun longestMeditation_fires_when_strictly_better_than_nonzero_past() {
        val inputs = base().copy(currentMeditationSeconds = 600L, pastWalksMaxMeditation = 300L)
        assertEquals("Your longest meditation yet", WalkSummaryCalloutProse.compute(inputs, celestialEnabled = false, context))
    }

    @Test fun longestMeditation_suppressed_when_past_was_zero() {
        val inputs = base().copy(currentMeditationSeconds = 600L, pastWalksMaxMeditation = 0L)
        assertNull(WalkSummaryCalloutProse.compute(inputs, celestialEnabled = false, context))
    }

    @Test fun longestWalk_fires_when_strictly_better_than_nonzero_past() {
        val inputs = base().copy(currentDistanceMeters = 5000.0, pastWalksMaxDistance = 3000.0)
        assertEquals("Your longest walk yet", WalkSummaryCalloutProse.compute(inputs, celestialEnabled = false, context))
    }

    @Test fun totalDistance_fires_at_10km_crossing() {
        val inputs = base().copy(
            currentDistanceMeters = 10_000.0,
            pastWalksDistanceSum = 0.0,
        )
        assertEquals("You've now walked 10 km total", WalkSummaryCalloutProse.compute(inputs, celestialEnabled = false, context))
    }

    @Test fun null_fallthrough_when_nothing_applies() {
        assertNull(WalkSummaryCalloutProse.compute(base(), celestialEnabled = false, context))
    }
}
```

- [ ] **Step 3: Run + commit**

```bash
./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.summary.WalkSummaryCalloutProseTest"
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/summary/WalkSummaryCalloutProse.kt app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/summary/WalkSummaryCalloutProseTest.kt
git commit -m "feat(walk-summary): WalkSummaryCalloutProse — iOS computeMilestone priority chain (Stage 13-Cel task 9)"
```

(Note: tests assume strings.xml entries exist — Task 16 ships them. Run order: do Task 16 BEFORE this test verification, OR commit code first then re-run after Task 16.)

---

### Task 10: Strings.xml — 14 entries

**Files:**
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add strings**

```xml
<!-- Stage 13-Cel celestial line -->
<string name="summary_celestial_moon_in">Moon in %1$s</string>
<string name="summary_celestial_hour_of">Hour of %1$s</string>
<string name="summary_celestial_predominates">%1$s predominates</string>

<!-- Stage 13-Cel milestone prose extras -->
<string name="summary_milestone_longest_meditation">Your longest meditation yet</string>
<string name="summary_milestone_seasonal_spring_equinox">You walked on the Spring Equinox</string>
<string name="summary_milestone_seasonal_summer_solstice">You walked on the Summer Solstice</string>
<string name="summary_milestone_seasonal_autumn_equinox">You walked on the Autumn Equinox</string>
<string name="summary_milestone_seasonal_winter_solstice">You walked on the Winter Solstice</string>
<string name="summary_milestone_seasonal_imbolc">You walked on the Imbolc</string>
<string name="summary_milestone_seasonal_beltane">You walked on the Beltane</string>
<string name="summary_milestone_seasonal_lughnasadh">You walked on the Lughnasadh</string>
<string name="summary_milestone_seasonal_samhain">You walked on the Samhain</string>
<string name="summary_milestone_total_distance_km">You\'ve now walked %1$s km total</string>
<string name="summary_milestone_total_distance_mi">You\'ve now walked %1$s mi total</string>
```

- [ ] **Step 2: Verify + commit**

```bash
./gradlew :app:lintDebug
git add app/src/main/res/values/strings.xml
git commit -m "feat(walk-summary): Stage 13-Cel strings (3 celestial + 11 callout extras)"
```

---

### Task 11: CelestialLineRow composable

**Files:**
- Create: `app/src/main/java/.../ui/walk/summary/CelestialLineRow.kt`
- Test: `app/src/test/java/.../ui/walk/summary/CelestialLineRowTest.kt`

- [ ] **Step 1: Source**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.core.celestial.CelestialSnapshot
import org.walktalkmeditate.pilgrim.core.celestial.Planet
import org.walktalkmeditate.pilgrim.data.practice.ZodiacSystem
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Stage 13-Cel: "Moon in {sign}, Hour of {planet}, {element} predominates"
 * inline row. iOS reference: `WalkSummaryView.celestialLine`
 * (`WalkSummaryView.swift:511-533`).
 *
 * Caller-side gate: render only when `snapshot != null` (the VM's
 * `celestialSnapshotDisplay` flow handles the celestialAwareness
 * preference toggle; this composable just renders what it's given).
 *
 * Three Texts; first and third are conditional on data availability
 * (no moon position → no moon Text; element tied → no element Text).
 */
@Composable
fun CelestialLineRow(
    snapshot: CelestialSnapshot,
    modifier: Modifier = Modifier,
) {
    val moonPos = snapshot.position(Planet.Moon)
    val moonZodiac = moonPos?.let { if (snapshot.system == ZodiacSystem.Tropical) it.tropical else it.sidereal }
    val dominant = snapshot.elementBalance.dominant
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
    ) {
        moonZodiac?.let { zp ->
            Text(
                text = stringResource(R.string.summary_celestial_moon_in, zp.sign.displayName),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = stringResource(R.string.summary_celestial_hour_of, snapshot.planetaryHour.planet.displayName),
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        dominant?.let { el ->
            Text(
                text = stringResource(R.string.summary_celestial_predominates, el.displayName),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
```

(Adjust `snapshot.planetaryHour.planet` to actual `PlanetaryHour` field name from Stage 6-A.)

- [ ] **Step 2: Tests — 4 cases (snapshot null wrap on caller; full snapshot; tied element; defensive missing zodiac)**

- [ ] **Step 3: Run + commit**

```bash
./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.summary.CelestialLineRowTest"
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/summary/CelestialLineRow.kt app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/summary/CelestialLineRowTest.kt
git commit -m "feat(walk-summary): CelestialLineRow composable (Stage 13-Cel task 11)"
```

---

### Task 12: MilestoneCalloutRow signature change — String prose

**Files:**
- Modify: `app/src/main/java/.../ui/walk/summary/MilestoneCalloutRow.kt`
- Modify: `app/src/test/java/.../ui/walk/summary/MilestoneCalloutRowTest.kt`

- [ ] **Step 1: Replace signature**

```kotlin
@Composable
fun MilestoneCalloutRow(
    prose: String,
    modifier: Modifier = Modifier,
) {
    // existing Row layout, just substitute Text(text = prose, ...)
}
```

Delete the `milestoneSummaryProse` `@Composable` switch helper.

- [ ] **Step 2: Update tests**

Replace 4 variant-specific tests with 1 test verifying prose renders + maxLines/ellipsis.

- [ ] **Step 3: Run + commit**

```bash
./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.summary.MilestoneCalloutRowTest"
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/summary/MilestoneCalloutRow.kt app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/summary/MilestoneCalloutRowTest.kt
git commit -m "feat(walk-summary): MilestoneCalloutRow takes String prose, drops enum switch (Stage 13-Cel task 12)"
```

---

### Task 13: WalkSummaryViewModel integration

**Files:**
- Modify: `app/src/main/java/.../ui/walk/WalkSummaryViewModel.kt`
- Modify: `app/src/test/java/.../ui/walk/WalkSummaryViewModelTest.kt`

- [ ] **Step 1: Add new fields to WalkSummary data class**

```kotlin
data class WalkSummary(
    // existing fields
    val celestialSnapshot: CelestialSnapshot? = null,
    val calloutInputs: WalkSummaryCalloutInputs,
    // milestone field stays for Goshuin grid
)
```

- [ ] **Step 2: Compute in buildState()**

```kotlin
// After existing logic, before constructing WalkSummary:
val celestialSnapshot = CelestialSnapshotCalc.snapshot(
    atEpochMillis = walk.startTimestamp,
    zoneId = ZoneId.systemDefault(),
    system = practicePreferences.zodiacSystem.value,
)

val pastFinished = repository.allWalks().asSequence()
    .filter { it.endTimestamp != null && it.id != walk.id }
    .sortedByDescending { it.endTimestamp ?: 0L }
    .take(100)
    .toList()

val calloutInputs = WalkSummaryCalloutInputs(
    currentDistanceMeters = distance,
    currentMeditationSeconds = walk.meditationSeconds ?: 0L,
    pastWalksMaxDistance = pastFinished.maxOfOrNull { it.distance ?: 0.0 } ?: 0.0,
    pastWalksMaxMeditation = pastFinished.maxOfOrNull { it.meditationSeconds ?: 0L } ?: 0L,
    pastWalksDistanceSum = pastFinished.sumOf { it.distance ?: 0.0 },
    units = unitsPreferences.distanceUnits.value,
    seasonalMarker = celestialSnapshot.seasonalMarker,
)
```

(Adjust based on actual `Walk.distance` / `Walk.meditationSeconds` types.)

- [ ] **Step 3: Add new live-combine flows**

```kotlin
val celestialSnapshotDisplay: StateFlow<CelestialSnapshot?> =
    combine(state, practicePreferences.celestialAwarenessEnabled) { s, enabled ->
        if (s is WalkSummaryUiState.Loaded && enabled) s.summary.celestialSnapshot else null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS), initialValue = null)

val walkSummaryCalloutProseDisplay: StateFlow<String?> =
    combine(state, practicePreferences.celestialAwarenessEnabled) { s, enabled ->
        if (s !is WalkSummaryUiState.Loaded) return@combine null
        WalkSummaryCalloutProse.compute(
            inputs = s.summary.calloutInputs,
            celestialEnabled = enabled,
            context = context,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS), initialValue = null)
```

- [ ] **Step 4: Update GoshuinMilestone.detect call to pass meditation millis**

In `detectMilestoneFor()`, populate `WalkMilestoneInput.meditateDurationMillis = (input.meditationSeconds ?: 0L) * 1000L`.

- [ ] **Step 5: Tests**

5 tests minimum:
- celestialSnapshot populated in buildState
- celestialSnapshotDisplay null when pref OFF, non-null when ON
- walkSummaryCalloutProseDisplay reflects pref toggle
- LongestMeditation strict-improvement-over-nonzero
- TotalDistance threshold-crossing

- [ ] **Step 6: Verify + commit**

```bash
./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.WalkSummaryViewModelTest"
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryViewModel.kt app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryViewModelTest.kt
git commit -m "feat(walk-summary): VM celestial snapshot + callout proseInputs + display flows (Stage 13-Cel task 13)"
```

---

### Task 14: WalkSummaryScreen wiring — section 10 + MilestoneCalloutRow

**Files:**
- Modify: `app/src/main/java/.../ui/walk/WalkSummaryScreen.kt`

- [ ] **Step 1: Collect new flows**

```kotlin
val celestialSnapshot by viewModel.celestialSnapshotDisplay.collectAsStateWithLifecycle()
val walkSummaryCalloutProse by viewModel.walkSummaryCalloutProseDisplay.collectAsStateWithLifecycle()
```

- [ ] **Step 2: Replace MilestoneCalloutRow call**

```kotlin
// Was:
// s.summary.milestone?.let { ms -> MilestoneCalloutRow(milestone = ms) }
// Now:
walkSummaryCalloutProse?.let { prose ->
    MilestoneCalloutRow(prose = prose)
}
```

- [ ] **Step 3: Replace section 10 placeholder with CelestialLineRow**

```kotlin
// 10. Celestial line (Stage 13-Cel)
celestialSnapshot?.let { snap ->
    CelestialLineRow(snapshot = snap)
}
```

- [ ] **Step 4: Verify build**

```bash
./gradlew :app:assembleDebug
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryScreen.kt
git commit -m "feat(walk-summary): wire CelestialLineRow + new MilestoneCalloutRow signature (Stage 13-Cel task 14)"
```

---

## Final verification

```bash
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

Manual on-device:
- Toggle celestialAwarenessEnabled → celestial line + SeasonalMarker callout flip live
- Set device clock to ≈ summer solstice → SeasonalMarker callout fires
- Walk with > previous max meditation (and prior walks had meditation) → "Your longest meditation yet"
- Walk crossing 10/25/50 km cumulative-over-recent-100-walks → "You've now walked X km total"

## Self-review

- [ ] Spec coverage: every section of the spec has a task. Cross-check against `docs/superpowers/specs/2026-05-02-stage-13-celestial-design.md`.
- [ ] No placeholders in plan tasks.
- [ ] Type consistency: `meditateDurationMillis` (Long) vs `meditationSeconds` (Long?) — conversion `* 1000L` consistent everywhere.
- [ ] All commits compile + tests pass at each step.
