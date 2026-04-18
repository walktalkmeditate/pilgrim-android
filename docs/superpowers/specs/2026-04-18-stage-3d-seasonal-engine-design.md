# Stage 3-D: Seasonal Color Engine — Design Spec

**Date:** 2026-04-18
**Status:** Draft (awaiting CHECKPOINT 1 approval)
**Prior art:**
- `../pilgrim-ios/Pilgrim/Models/SeasonalColorEngine.swift`
- `../pilgrim-ios/Pilgrim/Models/Constants.swift` (Seasonal block)

---

## Context

Pilgrim's palette isn't flat. Across the year, the same base color drifts: moss is greener in May, rust is warmer in August, ink is bluer in January. The colors *breathe* with the season. Stage 3-C stubbed this with `SeasonalInkFlavor.toColor()` returning the flat base token; Stage 3-D replaces the stub with a real HSB shift engine so the calligraphy path (and every future seasonal surface) takes on its date-appropriate hue.

The iOS algorithm is short (~60 LoC of math) and has been stable for two years. Our constants are already copied into `Tokens.kt::PilgrimSeasonal` — exact numeric match with iOS. The port is mostly plumbing: pure math + Compose's Color + DataStore-backed hemisphere memory.

---

## Goals

1. **Pure-JVM `SeasonalColorEngine` object** with `applySeasonalShift(base, intensity, date, hemisphere) → Color`. Byte-for-byte matches iOS output for the same inputs.
2. **`HemisphereRepository`** (Hilt-singleton) that (a) exposes a `StateFlow<Hemisphere>`, (b) on first-known-location event caches the inferred hemisphere to DataStore, (c) survives process restart, (d) defaults to Northern if unknown.
3. **`SeasonalInkFlavor.toColor()` upgrade** — same `@Composable` API, now threads through the engine. Uses `Intensity.Moderate` to match iOS's `pathSegmentColor`.
4. **Unit-testable math.** Pure-JVM tests for: the cos² weight curve, hemisphere day-of-year shift, year-boundary continuity, hue wrap, saturation/brightness clamp, intensity scaling.

## Non-goals

- **Walk dot rendering at `.full` intensity** — Stage 3-E / Phase 4 concern. Engine supports it; no caller yet.
- **Map style tints (`.minimal`)** — Stage 10 / map polish.
- **Settings UI for manual hemisphere override.** Repository exposes a `setOverride()` method for future use, but no UI lands this stage.
- **Debug date override.** iOS has one via `UserPreferences.debugDateOverride`; we skip until a contributor asks for it.
- **Leap-year precision.** Day-of-year 366 is treated as winter-adjacent, same as iOS. Nobody has ever noticed the 1-day drift.

---

## Algorithm (verbatim port from iOS)

### Weighting — cos² falloff with year wrap

```kotlin
// distance along the calendar between today and the peak day of this season,
// handling the year boundary so Jan 2 is correctly 17 days from winter peak (Jan 15).
fun seasonalWeight(dayOfYear: Int, peakDay: Int, spread: Float): Float {
    val rawDiff = abs(dayOfYear - peakDay).toFloat()
    val distance = min(rawDiff, 365f - rawDiff)
    val normalized = distance / spread
    val base = max(0f, cos(normalized * PI.toFloat() / 2f))
    return base * base    // cos² — gentler falloff near the peak
}
```

The four seasonal weights sum to ~1.0 on any given day (empirically validated by the iOS tests Pilgrim never wrote — we'll write them). Out-of-spread dates drop to 0 for that season.

### Seasonal adjustment — linear blend of the four seasons' HSB deltas

```kotlin
data class SeasonalAdjustment(
    val hueDelta: Float,
    val saturationMultiplier: Float,   // = 1 + satDelta (so 0.10 → 1.10)
    val brightnessMultiplier: Float,   // = 1 + briDelta
)

fun seasonalTransform(date: LocalDate, hemisphere: Hemisphere): SeasonalAdjustment {
    val dayOfYear = adjustedDayOfYear(date, hemisphere)
    val spring = seasonalWeight(dayOfYear, SPRING_PEAK_DAY, SPREAD)
    val summer = seasonalWeight(dayOfYear, SUMMER_PEAK_DAY, SPREAD)
    val autumn = seasonalWeight(dayOfYear, AUTUMN_PEAK_DAY, SPREAD)
    val winter = seasonalWeight(dayOfYear, WINTER_PEAK_DAY, SPREAD)
    return SeasonalAdjustment(
        hueDelta =              spring*SPRING_HUE    + summer*SUMMER_HUE    + autumn*AUTUMN_HUE    + winter*WINTER_HUE,
        saturationMultiplier = 1f + spring*SPRING_SAT + summer*SUMMER_SAT + autumn*AUTUMN_SAT + winter*WINTER_SAT,
        brightnessMultiplier = 1f + spring*SPRING_BRIGHT + summer*SUMMER_BRIGHT + autumn*AUTUMN_BRIGHT + winter*WINTER_BRIGHT,
    )
}
```

### Hemisphere — southern shifts by 182 days

```kotlin
fun adjustedDayOfYear(date: LocalDate, hemisphere: Hemisphere): Int {
    val doy = date.dayOfYear    // 1..365 (or 366 on leap year)
    return when (hemisphere) {
        Hemisphere.Northern -> doy
        Hemisphere.Southern -> ((doy + 182) % 365) + 1
    }
}
```

Matches iOS exactly. Leap day 366 → `(366 + 182) % 365 + 1 = 184`, which is a summer day in the northern frame — the same subtle bug iOS has. Acceptable.

### Applying the shift — hue additive+wrap, sat/bri multiplicative+clamp

```kotlin
fun applySeasonalShift(
    base: Color,
    intensity: Intensity,
    date: LocalDate,
    hemisphere: Hemisphere,
): Color {
    val adjustment = seasonalTransform(date, hemisphere)
    val scale = intensity.scale

    // Decompose base into HSV. Compose's Color is RGB-native; drop down
    // to android.graphics.Color for HSV. Alpha preserved.
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV(
        (base.red   * 255f).toInt().coerceIn(0, 255),
        (base.green * 255f).toInt().coerceIn(0, 255),
        (base.blue  * 255f).toInt().coerceIn(0, 255),
        hsv,
    )
    // android.graphics hue is in [0, 360]; our deltas are in [0, 1] scale
    // matching iOS's normalized hue. Convert both sides into a common
    // unit — pick iOS's [0, 1] convention to match the constants.
    var h01 = hsv[0] / 360f
    var s   = hsv[1]
    var v   = hsv[2]

    h01 = ((h01 + adjustment.hueDelta * scale) % 1f + 1f) % 1f   // hue wraps, keep positive
    s   = (s * (1f + (adjustment.saturationMultiplier - 1f) * scale)).coerceIn(0f, 1f)
    v   = (v * (1f + (adjustment.brightnessMultiplier - 1f) * scale)).coerceIn(0f, 1f)

    val outRgb = android.graphics.Color.HSVToColor(
        (base.alpha * 255f).toInt().coerceIn(0, 255),
        floatArrayOf(h01 * 360f, s, v),
    )
    return Color(outRgb)
}
```

Two subtle points:

1. **iOS hue convention is 0-1, android.graphics uses 0-360.** We normalize to 0-1 for the arithmetic so the existing `PilgrimSeasonal.SPRING_HUE = 0.02f` constant (matching iOS) stays correct.
2. **Hue wrap uses `((x % 1 + 1) % 1)` because `%` on negative floats in Kotlin returns negative**, and iOS's `truncatingRemainder` does the same. We explicitly wrap back into `[0, 1)`.

### Constants — already in `PilgrimSeasonal` (Tokens.kt)

All 16 HSB deltas + 4 peak days + spread are already there with the exact iOS values. The engine consumes them unchanged.

---

## Architecture

### Package layout

New code lands under `ui/theme/seasonal/` — a new subpackage dedicated to the engine. Sits alongside Color.kt / Type.kt / Tokens.kt — this is theme infrastructure, not a feature package. Grouping under `seasonal/` keeps room for future additions (day/night curve, weather tints) without polluting the theme root.

```
ui/theme/seasonal/
├── Hemisphere.kt                    // enum + `Hemisphere.fromLatitude(Double): Hemisphere`
├── HemisphereRepository.kt          // Singleton, DataStore-backed, @Inject
├── SeasonalColorEngine.kt           // object with Intensity enum + applySeasonalShift()
└── SeasonalAdjustment.kt            // internal data class (the three deltas)
```

`SeasonalInkFlavor` stays in `ui/design/calligraphy/` (Stage 3-C's home) but gains a new `toColor(date, hemisphere)` overload that routes through the engine.

### Public API

```kotlin
// Hemisphere.kt
enum class Hemisphere {
    Northern, Southern;
    companion object {
        fun fromLatitude(latitude: Double): Hemisphere =
            if (latitude < 0.0) Southern else Northern
    }
}

// HemisphereRepository.kt
@Singleton
class HemisphereRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val locationSource: LocationSource,
) {
    /**
     * Observes the current best-guess hemisphere. Emits Northern
     * until an override has been set OR a real location was observed.
     * Subsequent app launches read straight from DataStore — no
     * location permission required to resolve a previously-cached
     * hemisphere.
     */
    val hemisphere: StateFlow<Hemisphere>

    /** Manual override — also persists. */
    suspend fun setOverride(hemisphere: Hemisphere)

    /**
     * Called on app start (PilgrimApp.onCreate via DI) and on walk
     * finish. Tries `locationSource.lastKnownLocation()`, and if non-
     * null AND no prior override exists, caches the inferred
     * hemisphere. No-op if an override is already stored.
     */
    suspend fun refreshFromLocationIfNeeded()
}

// SeasonalColorEngine.kt
object SeasonalColorEngine {
    enum class Intensity(val scale: Float) {
        Full(1.0f),
        Moderate(0.4f),
        Minimal(0.1f),
    }

    /**
     * @param base the PilgrimColors token to shift
     * @param intensity how much of the seasonal signal to apply
     * @param date date to evaluate against; defaults to today (system zone)
     * @param hemisphere northern/southern; callers typically inject from
     *        [HemisphereRepository].
     */
    fun applySeasonalShift(
        base: Color,
        intensity: Intensity,
        date: LocalDate = LocalDate.now(),
        hemisphere: Hemisphere = Hemisphere.Northern,
    ): Color
}
```

### SeasonalInkFlavor integration

Current:
```kotlin
@Composable @ReadOnlyComposable
fun SeasonalInkFlavor.toColor(): Color = when (this) {
    Ink -> pilgrimColors.ink
    Moss -> pilgrimColors.moss
    // ...
}
```

New (same signature, new body):
```kotlin
@Composable
fun SeasonalInkFlavor.toSeasonalColor(
    date: LocalDate,
    hemisphere: Hemisphere,
    intensity: Intensity = Intensity.Moderate,   // matches iOS path-segment default
): Color {
    val base = when (this) {
        Ink -> pilgrimColors.ink
        Moss -> pilgrimColors.moss
        Rust -> pilgrimColors.rust
        Dawn -> pilgrimColors.dawn
    }
    return SeasonalColorEngine.applySeasonalShift(base, intensity, date, hemisphere)
}
```

We **keep the old `toColor()`** as a deprecated shim delegating to the new one with today's date + Northern:
```kotlin
@Deprecated("Use toSeasonalColor(date, hemisphere) — Stage 3-D", …)
@Composable
fun SeasonalInkFlavor.toColor(): Color = toSeasonalColor(LocalDate.now(), Hemisphere.Northern)
```

Why deprecate instead of hard-remove? The only caller today is `CalligraphyPathPreview.kt`, which we'll update in the same PR. The deprecation is belt-and-braces for any in-flight branches or future contributors.

### Preview integration

`CalligraphyPathPreview` currently resolves the four colors then indexes by flavor. The new flow:

```kotlin
@Composable
fun CalligraphyPathPreviewScreen(vm: CalligraphyPathPreviewViewModel = hiltViewModel()) {
    val previews by vm.state.collectAsStateWithLifecycle()
    val hemisphere by vm.hemisphere.collectAsStateWithLifecycle()
    val strokes = remember(previews, hemisphere) {
        previews.map { preview ->
            preview.spec.copy(
                ink = seasonalColorFor(preview.spec.startMillis, preview.flavor, hemisphere)
            )
        }
    }
    // ...
}

// Helper that pulls the four base colors (needs @Composable for
// pilgrimColors reads) then routes through the engine:
@Composable
private fun seasonalColorFor(
    startMillis: Long,
    flavor: SeasonalInkFlavor,
    hemisphere: Hemisphere,
): Color { /* … */ }
```

The VM gains `hemisphere: StateFlow<Hemisphere>` plumbed from `HemisphereRepository`.

### Threading / DI

- `HemisphereRepository` is `@Singleton`, `@Inject`-able.
- `refreshFromLocationIfNeeded()` is suspend, runs on IO via `locationSource`.
- `SeasonalColorEngine` is a pure object — no state, no DI, pure functions. Testable as plain JVM code.

---

## Data flow

```
┌────────────────────┐   suspend                ┌────────────────┐
│ LocationSource     │ lastKnownLocation() ────▶ │ Hemisphere     │
└────────────────────┘                          │ Repository     │
                                                 │                │
                          DataStore              │ StateFlow<H>   │
                         (pilgrim_prefs,  ◀─────▶│                │
                          key "hemisphere")     └───────┬────────┘
                                                        │
                                                        │ collectAsStateWithLifecycle
                                                        ▼
                                             ┌──────────────────────┐
CalligraphyPreviewVM ──────────────▶ PreviewScreen  ────▶  Engine.applySeasonalShift()
                                             └──────────────────────┘
```

---

## Error handling / edge cases

| Edge case | Behavior |
|---|---|
| No location ever available | `HemisphereRepository.hemisphere` stays Northern. No DataStore write. |
| Location returned with latitude 0.0 (equator / mock) | `fromLatitude` returns Northern (convention: < 0 is southern). |
| User crosses the equator mid-use | Stored hemisphere does NOT auto-flip. iOS behaves the same — a one-time inference at first-walk. Manual override required for traveling pilgrims; doc-only concern. |
| DataStore read fails (corrupted file, disk full) | Emit Northern; exception propagates only on `setOverride` write. |
| Base color is black/white (sat=0) | HSV saturation=0 → hue is undefined but `RGBToHSV` returns hue=0. Shift is near-no-op; color stays roughly gray. Not a crash. |
| Date at year boundary (Dec 31 23:59 → Jan 1 00:00) | `dayOfYear` transitions 365/366 → 1; distance wrap in `seasonalWeight` handles continuity. |
| Leap year day 366 | Treated as "winter" (close to winter peak day 15 via wrap). 1-day drift vs non-leap; acceptable. |
| Hue delta causing hue to go negative (winter: -0.02) | `((x % 1) + 1) % 1` normalizes back to [0, 1). |
| Saturation pushed above 1 by summer (+15%) on already-saturated input | `.coerceIn(0f, 1f)` clamps. Same for brightness. |
| `LocalDate.now()` in non-UTC zone returning "next day" for user | Acceptable — 1-day drift is imperceptible. `LocalDate.now()` uses system zone, which is what the user expects. |

---

## Testing strategy

**Pure-JVM unit tests** (`app/src/test/.../ui/theme/seasonal/`):

- `SeasonalWeightTest`
  - At peak day → weight = 1.0
  - At peak ± spread → weight = 0.0 (cos(π/2) = 0)
  - At peak ± spread/2 → weight = cos²(π/4) ≈ 0.5
  - Symmetric around peak (weight(peak-10) == weight(peak+10))
  - Wraps across year boundary: weight(dayOfYear=1, peakDay=15, spread=91) == weight(dayOfYear=30, peakDay=15, spread=91)

- `SeasonalTransformTest`
  - On winter peak (Jan 15, northern), adjustment has the four winter deltas (hueDelta ≈ -0.02, satMul ≈ 0.85, briMul ≈ 0.95)
  - On summer peak (Jul 15, northern), adjustment matches summer deltas
  - Southern hemisphere on Jul 15 = northern on Jan 15 (shifted by 182 days)
  - Equinox-ish date (Apr 15 = spring peak in northern) → adjustment is clean spring values, zero cross-contamination within rounding

- `ApplySeasonalShiftTest`
  - Identity at `.full * zero-adjustment day`: pick a date where all four weights are ~0 (there isn't one, but pick the minimum; compare to base with small tolerance)
  - `.full` moss on summer peak saturates + brightens
  - `.moderate` is 40% of `.full` delta (pick a clear delta, assert halfway toward base)
  - `.minimal` is 10% — barely perceptible
  - Hue wrap: base hue 0.01 + winter delta -0.02 → wraps to ~0.99, not -0.01
  - Sat clamp: base sat 0.95 + summer delta +15% * full → clamps to 1.0 not 1.09
  - Alpha preserved through the round-trip

- `HemisphereTest`
  - `fromLatitude(-34.0)` (Sydney) → Southern
  - `fromLatitude(51.5)` (London) → Northern
  - `fromLatitude(0.0)` → Northern (by convention)

**Robolectric test** (`app/src/test/.../ui/theme/seasonal/HemisphereRepositoryTest.kt`):
- First start, no override, no location → emits Northern
- First start + location returns lat=-35 → after `refreshFromLocationIfNeeded()`, emits Southern
- App restart after caching → emits Southern without calling location again
- `setOverride(Northern)` flips a cached Southern back to Northern; location re-inference does NOT override it

Uses the existing `DataStoreModule` + an in-memory DataStore + a `FakeLocationSource` (one exists in test/ already, add a test-dispatched stub).

**No instrumented/device test.** Nothing here needs the real device — everything is pure math or Robolectric-shadowable DataStore.

---

## Rejected alternatives

1. **Make the engine a `@Composable` helper that reads `LocalDate.now()` + `LocalHemisphere` composition local.** Tempting — the call site would just be `SeasonalInkFlavor.toColor()` unchanged. **Rejected** because `LocalDate.now()` inside a `@Composable` recomputes on every recomposition; the engine would run 10x per second during animations. Keeping the engine a pure function + letting the VM/Composable caller provide a `remember`ed date means one computation per date boundary.

2. **Use Compose's `Color.hsl()` instead of `android.graphics.Color.HSVToColor`.** HSL ≠ HSV. iOS uses HSV (aka HSB). Different math, different results. **Rejected**.

3. **Store `Hemisphere` in DataStore as an enum name (String).** Works, but the int approach iOS uses (0/1) is marginally smaller + matches iOS's serialization. **Low-stakes**; going with Int for symmetry.

4. **Detect hemisphere from device locale (`Locale.getDefault().country`).** Coarse — country codes don't map cleanly to hemisphere for countries straddling the equator (Brazil, Indonesia, Kenya). Location is accurate. **Rejected**.

5. **Compute seasonal colors at app start + cache the four-season colorset in memory.** Avoids recomputing four times per render. **Rejected** for MVP — the engine call is ~50 float ops, negligible even at 60fps for 8 strokes. Premature optimization.

6. **Use Java 8 `ChronoUnit` for day-of-year arithmetic.** `LocalDate.dayOfYear` already does what we need. No ChronoUnit.

7. **Put `Hemisphere` in `domain/` instead of `ui/theme/seasonal/`.** `Hemisphere` is currently only used by the theme engine — it doesn't belong in domain until a second consumer appears (e.g., sunrise-calc in Stage 6). Keep it colocated for now; move when a second caller needs it.

---

## Scope & estimate

- **4 new production files** (~250 LoC combined)
- **1 file modified**: `SeasonalInkFlavor.kt` (swap toColor → toSeasonalColor, keep deprecated shim)
- **1 file modified**: `CalligraphyPathPreview.kt` (thread hemisphere + date)
- **1 file modified**: `CalligraphyPathPreviewViewModel.kt` (inject + expose `HemisphereRepository.hemisphere`)
- **4 new test files** (~250 LoC combined)
- **1 DataStore key** added to the existing `pilgrim_prefs` store

**No Gradle changes.** No new dependencies.

Estimate: one stage's worth of work. Touches no existing features, blocks nothing upstream. Opens the door for Stage 3-E (journal thread integration — now with date-driven color) and Stage 4 (goshuin seals using `Intensity.Full`).

---

## Forward-carry for 3-E / Phase 4

- **3-E (journal integration)** will call `SeasonalInkFlavor.forMonth(walk.startTimestamp).toSeasonalColor(walk.startDate, hemisphere, Intensity.Moderate)` for each stroke. No engine change needed.
- **Phase 4 (goshuin seals)** will want `Intensity.Full` on dot-like renderings. Already supported.
- **Stage 10 (map polish)** will want `Intensity.Minimal` on map background tints. Already supported.
- **Stage 6 (celestial / planetary hour)** may reuse `Hemisphere` for sunrise calculations — we'll migrate `Hemisphere.kt` from `ui/theme/seasonal/` to `domain/` when that happens.

---

## Open questions for checkpoint

- None. Algorithm is prescribed by the iOS port, constants already exist, the hemisphere-detection story mirrors iOS's "cache from first location + DataStore persist". Approve, revise, or reject.
