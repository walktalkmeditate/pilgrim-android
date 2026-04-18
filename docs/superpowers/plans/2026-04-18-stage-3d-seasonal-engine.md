# Stage 3-D Implementation Plan — Seasonal Color Engine

Spec: `docs/superpowers/specs/2026-04-18-stage-3d-seasonal-engine-design.md`.

Ship a pure-JVM HSB-shift engine + DataStore-backed hemisphere
repository + wire the calligraphy preview through it.

---

## Task 1 — `Hemisphere.kt` enum + latitude helper

**New file:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/theme/seasonal/Hemisphere.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.theme.seasonal

/**
 * Which half of the globe this device is in. Drives the seasonal
 * color engine's day-of-year shift so southern-hemisphere users see
 * summer palette in January rather than winter.
 *
 * Resolved lazily by [HemisphereRepository] — see that class for the
 * "infer from first location, cache to DataStore" flow.
 */
enum class Hemisphere {
    Northern, Southern;

    companion object {
        /**
         * Negative latitude → Southern. Zero (equator) and positive
         * latitudes → Northern by convention. Matches the iOS engine.
         */
        fun fromLatitude(latitude: Double): Hemisphere =
            if (latitude < 0.0) Southern else Northern
    }
}
```

---

## Task 2 — `HemisphereRepository.kt` (DataStore-backed, location-aware)

**New file:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/theme/seasonal/HemisphereRepository.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.theme.seasonal

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.walktalkmeditate.pilgrim.location.LocationSource

/**
 * Lazily resolves + persists the device's hemisphere. Defaults to
 * [Hemisphere.Northern] until (a) a real location is observed and
 * [refreshFromLocationIfNeeded] is called, or (b) the user sets an
 * explicit override via [setOverride].
 *
 * Once cached, subsequent app launches read the value without
 * touching the location subsystem — the DataStore key survives
 * process restart. A user who travels across the equator can call
 * [setOverride] to flip the stored value; automatic re-inference
 * does not clobber an explicit override.
 *
 * Matches the iOS `UserPreferences.hemisphereOverride` + first-walk
 * inference behavior. See the Stage 3-D design spec for context.
 */
@Singleton
class HemisphereRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val locationSource: LocationSource,
    @HemisphereRepositoryScope private val scope: CoroutineScope,
) {
    /**
     * Current best-guess hemisphere. Backed by DataStore; emits
     * [Hemisphere.Northern] as the initial value before the first
     * DataStore read completes.
     */
    val hemisphere: StateFlow<Hemisphere> =
        dataStore.data
            .map { prefs ->
                when (prefs[KEY_HEMISPHERE]) {
                    INT_NORTHERN -> Hemisphere.Northern
                    INT_SOUTHERN -> Hemisphere.Southern
                    else -> Hemisphere.Northern
                }
            }
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = Hemisphere.Northern,
            )

    /** Explicit user override. Persists; survives re-inference. */
    suspend fun setOverride(hemisphere: Hemisphere) {
        dataStore.edit { it[KEY_HEMISPHERE] = hemisphere.toInt() }
    }

    /**
     * Try to infer the hemisphere from the last-known location. No-op
     * if a value is already cached OR no location is available. Safe
     * to call repeatedly — it'll only write on the transition from
     * "unknown" → "known".
     */
    suspend fun refreshFromLocationIfNeeded() {
        val alreadyCached = dataStore.data.map { it[KEY_HEMISPHERE] }
        // peek at the current cached value — `.map { ... }.first()` would
        // also work but DataStore's Flow is cold-cached, so the first
        // emission reflects current disk state.
        val current = alreadyCached.let {
            kotlinx.coroutines.flow.firstOrNull { _: Int? -> true }
        }
        // Simpler: call data.first() inline.
        val currentValue: Int? = kotlinx.coroutines.flow.first(dataStore.data) { true }[KEY_HEMISPHERE]
        if (currentValue != null) return

        val location = locationSource.lastKnownLocation() ?: return
        val inferred = Hemisphere.fromLatitude(location.latitude)
        dataStore.edit { it[KEY_HEMISPHERE] = inferred.toInt() }
    }

    private fun Hemisphere.toInt(): Int = when (this) {
        Hemisphere.Northern -> INT_NORTHERN
        Hemisphere.Southern -> INT_SOUTHERN
    }

    private companion object {
        val KEY_HEMISPHERE = intPreferencesKey("hemisphere")
        const val INT_NORTHERN = 0
        const val INT_SOUTHERN = 1
    }
}
```

> **Plan note — clean up `refreshFromLocationIfNeeded`.** The draft
> above is messy on purpose to flag that I haven't committed to a
> final suspend-read pattern. During implementation, rewrite as:
> ```kotlin
> suspend fun refreshFromLocationIfNeeded() {
>     val current = dataStore.data.first()[KEY_HEMISPHERE]
>     if (current != null) return
>     val location = locationSource.lastKnownLocation() ?: return
>     val inferred = Hemisphere.fromLatitude(location.latitude)
>     dataStore.edit { it[KEY_HEMISPHERE] = inferred.toInt() }
> }
> ```
> using `kotlinx.coroutines.flow.first` imported properly. Single read,
> single write, no nested `.map`. The final code in the PR uses THIS
> form, not the draft above.

**Hilt provisioning — new file:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/theme/seasonal/HemisphereRepositoryScope.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.theme.seasonal

import javax.inject.Qualifier

/**
 * Qualifier for the application-scoped [CoroutineScope] that backs
 * [HemisphereRepository.hemisphere]'s `stateIn`. Prefer wiring the
 * app's existing `ApplicationScope` via Hilt, if one exists — this
 * qualifier gives us a seam to either reuse or provide independently.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class HemisphereRepositoryScope
```

**Hilt module — new file:** `app/src/main/java/org/walktalkmeditate/pilgrim/di/SeasonalModule.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.HemisphereRepositoryScope

@Module
@InstallIn(SingletonComponent::class)
object SeasonalModule {
    @Provides
    @Singleton
    @HemisphereRepositoryScope
    fun provideHemisphereScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
```

---

## Task 3 — `SeasonalColorEngine.kt` (pure-JVM math)

**New file:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/theme/seasonal/SeasonalColorEngine.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.theme.seasonal

import androidx.compose.ui.graphics.Color
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSeasonal

/**
 * Date-and-hemisphere-driven HSB shift applied to a base
 * [PilgrimColors] token. Byte-for-byte port of the iOS
 * `SeasonalColorEngine`: cos² weights around four seasonal peaks
 * (day-of-year 105/196/288/15 northern, spread 91), linearly blended
 * across HSB channels, hue additive with [0,1) wrap, saturation and
 * brightness multiplicative with clamp.
 *
 * Pure object — no state, no DI. Callers provide date + hemisphere.
 * For the typical flow, [HemisphereRepository] resolves the
 * hemisphere once per process; the date is usually [LocalDate.now].
 */
object SeasonalColorEngine {

    enum class Intensity(val scale: Float) {
        Full(1.0f),
        Moderate(0.4f),
        Minimal(0.1f),
    }

    /**
     * @param base the base color to shift (a [PilgrimColors] token).
     * @param intensity how much of the seasonal signal to apply.
     *   `Full` for walk dots, `Moderate` for calligraphy-path
     *   segments, `Minimal` for map backgrounds.
     * @param date date to evaluate against. Defaults to today in the
     *   system zone.
     * @param hemisphere northern/southern. Defaults to Northern; real
     *   callers inject from [HemisphereRepository.hemisphere].
     */
    fun applySeasonalShift(
        base: Color,
        intensity: Intensity,
        date: LocalDate = LocalDate.now(),
        hemisphere: Hemisphere = Hemisphere.Northern,
    ): Color {
        val adjustment = seasonalTransform(date, hemisphere)
        val scale = intensity.scale

        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV(
            (base.red * 255f).toInt().coerceIn(0, 255),
            (base.green * 255f).toInt().coerceIn(0, 255),
            (base.blue * 255f).toInt().coerceIn(0, 255),
            hsv,
        )
        // iOS convention: hue in [0, 1]. android.graphics uses [0, 360].
        // Normalize to [0, 1] so the PilgrimSeasonal.*_HUE constants
        // (which come from iOS) keep their meaning.
        var h01 = hsv[0] / 360f
        var s = hsv[1]
        var v = hsv[2]

        h01 = (((h01 + adjustment.hueDelta * scale) % 1f) + 1f) % 1f
        s = (s * (1f + (adjustment.saturationMultiplier - 1f) * scale)).coerceIn(0f, 1f)
        v = (v * (1f + (adjustment.brightnessMultiplier - 1f) * scale)).coerceIn(0f, 1f)

        val argb = android.graphics.Color.HSVToColor(
            (base.alpha * 255f).toInt().coerceIn(0, 255),
            floatArrayOf(h01 * 360f, s, v),
        )
        return Color(argb)
    }

    internal fun seasonalTransform(date: LocalDate, hemisphere: Hemisphere): SeasonalAdjustment {
        val dayOfYear = adjustedDayOfYear(date, hemisphere)
        val spring = seasonalWeight(dayOfYear, PilgrimSeasonal.SPRING_PEAK_DAY, PilgrimSeasonal.SPREAD)
        val summer = seasonalWeight(dayOfYear, PilgrimSeasonal.SUMMER_PEAK_DAY, PilgrimSeasonal.SPREAD)
        val autumn = seasonalWeight(dayOfYear, PilgrimSeasonal.AUTUMN_PEAK_DAY, PilgrimSeasonal.SPREAD)
        val winter = seasonalWeight(dayOfYear, PilgrimSeasonal.WINTER_PEAK_DAY, PilgrimSeasonal.SPREAD)
        return SeasonalAdjustment(
            hueDelta =
                spring * PilgrimSeasonal.SPRING_HUE +
                    summer * PilgrimSeasonal.SUMMER_HUE +
                    autumn * PilgrimSeasonal.AUTUMN_HUE +
                    winter * PilgrimSeasonal.WINTER_HUE,
            saturationMultiplier = 1f +
                spring * PilgrimSeasonal.SPRING_SAT +
                summer * PilgrimSeasonal.SUMMER_SAT +
                autumn * PilgrimSeasonal.AUTUMN_SAT +
                winter * PilgrimSeasonal.WINTER_SAT,
            brightnessMultiplier = 1f +
                spring * PilgrimSeasonal.SPRING_BRIGHT +
                summer * PilgrimSeasonal.SUMMER_BRIGHT +
                autumn * PilgrimSeasonal.AUTUMN_BRIGHT +
                winter * PilgrimSeasonal.WINTER_BRIGHT,
        )
    }

    internal fun adjustedDayOfYear(date: LocalDate, hemisphere: Hemisphere): Int {
        val doy = date.dayOfYear
        return when (hemisphere) {
            Hemisphere.Northern -> doy
            Hemisphere.Southern -> ((doy + 182) % 365) + 1
        }
    }

    internal fun seasonalWeight(dayOfYear: Int, peakDay: Int, spread: Float): Float {
        val rawDiff = abs(dayOfYear - peakDay).toFloat()
        val distance = min(rawDiff, 365f - rawDiff)
        val normalized = distance / spread
        val base = max(0f, cos(normalized * PI.toFloat() / 2f))
        return base * base
    }
}

internal data class SeasonalAdjustment(
    val hueDelta: Float,
    val saturationMultiplier: Float,
    val brightnessMultiplier: Float,
)
```

---

## Task 4 — `SeasonalInkFlavor.kt` upgrade

**Edit:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/calligraphy/SeasonalInkFlavor.kt`

Replace the existing file with:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.calligraphy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.Hemisphere
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.SeasonalColorEngine

/**
 * Northern-hemisphere month → base color bucket. Stage 3-D wraps the
 * returned color with an HSB shift computed from the walk's date +
 * the device hemisphere; Stage 3-C shipped the flat version of this.
 */
enum class SeasonalInkFlavor {
    Ink, Moss, Rust, Dawn;

    companion object {
        fun forMonth(startMillis: Long, zone: ZoneId = ZoneId.systemDefault()): SeasonalInkFlavor {
            val month = Instant.ofEpochMilli(startMillis).atZone(zone).monthValue
            return when (month) {
                in 3..5 -> Moss
                in 6..8 -> Rust
                in 9..11 -> Dawn
                else -> Ink
            }
        }
    }
}

/**
 * Resolve this flavor into the base PilgrimColors token, without
 * seasonal shifting. Prefer [toSeasonalColor] for anything rendered
 * to the user; this is an escape hatch for code that intentionally
 * wants the flat token (e.g., a legend swatch).
 */
@Composable
@ReadOnlyComposable
fun SeasonalInkFlavor.toBaseColor(): Color = when (this) {
    SeasonalInkFlavor.Ink -> pilgrimColors.ink
    SeasonalInkFlavor.Moss -> pilgrimColors.moss
    SeasonalInkFlavor.Rust -> pilgrimColors.rust
    SeasonalInkFlavor.Dawn -> pilgrimColors.dawn
}

/**
 * Resolve this flavor into a seasonally-shifted color for rendering.
 * Callers supply the walk's [date] and the device [hemisphere]
 * (typically from [org.walktalkmeditate.pilgrim.ui.theme.seasonal.HemisphereRepository]).
 *
 * Default [intensity] is [SeasonalColorEngine.Intensity.Moderate],
 * matching iOS's `pathSegmentColor` call site. Walk-dot renderings
 * should pass `Full`; map backgrounds `Minimal`.
 */
@Composable
fun SeasonalInkFlavor.toSeasonalColor(
    date: LocalDate,
    hemisphere: Hemisphere,
    intensity: SeasonalColorEngine.Intensity = SeasonalColorEngine.Intensity.Moderate,
): Color {
    val base = toBaseColor()
    return SeasonalColorEngine.applySeasonalShift(base, intensity, date, hemisphere)
}

/**
 * Legacy flat accessor. Preserved as a deprecated shim so Stage 3-C's
 * in-tree caller keeps compiling; new code should use [toSeasonalColor].
 * Removed when Stage 3-E lands and the preview screen is deleted.
 */
@Deprecated(
    message = "Use toSeasonalColor(date, hemisphere) — Stage 3-D introduces date-driven HSB shifts.",
    replaceWith = ReplaceWith("toSeasonalColor(date, hemisphere)"),
    level = DeprecationLevel.WARNING,
)
@Composable
@ReadOnlyComposable
fun SeasonalInkFlavor.toColor(): Color = toBaseColor()
```

---

## Task 5 — Preview VM + screen wired through engine

**Edit:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/calligraphy/CalligraphyPathPreviewViewModel.kt`

Add a `hemisphere: StateFlow<Hemisphere>` property exposed by the repository. Minimal diff:

```kotlin
@HiltViewModel
class CalligraphyPathPreviewViewModel @Inject constructor(
    private val repository: WalkRepository,
    private val clock: Clock,
    hemisphereRepository: HemisphereRepository,
) : ViewModel() {
    val hemisphere: StateFlow<Hemisphere> = hemisphereRepository.hemisphere

    val state: StateFlow<List<PreviewStroke>> = repository.observeAllWalks()
        // ... unchanged
```

Also kick off hemisphere refresh once:

```kotlin
init {
    viewModelScope.launch {
        hemisphereRepository.refreshFromLocationIfNeeded()
    }
}
```

Import additions:
```kotlin
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.Hemisphere
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.HemisphereRepository
```

**Edit:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/calligraphy/CalligraphyPathPreview.kt`

Replace the body that resolves colors with one that routes through `toSeasonalColor(date, hemisphere)`:

```kotlin
@Composable
fun CalligraphyPathPreviewScreen(
    viewModel: CalligraphyPathPreviewViewModel = hiltViewModel(),
) {
    val previews by viewModel.state.collectAsStateWithLifecycle()
    val hemisphere by viewModel.hemisphere.collectAsStateWithLifecycle()

    val strokes = remember(previews, hemisphere) {
        previews.map { preview ->
            val walkDate = Instant.ofEpochMilli(preview.spec.startMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
            preview.spec.copy(
                ink = resolveSeasonalInk(preview.flavor, walkDate, hemisphere),
            )
        }
    }
    Column(/* unchanged */) {
        /* Text header unchanged */
        CalligraphyPath(strokes = strokes)
    }
}

// Helper lives in this file — needs a @Composable context to read
// pilgrimColors via toBaseColor().
@Composable
private fun resolveSeasonalInk(
    flavor: SeasonalInkFlavor,
    date: LocalDate,
    hemisphere: Hemisphere,
): Color = flavor.toSeasonalColor(date, hemisphere)
```

> **Snag to watch in implementation.** `remember { ... }` cannot call
> `@Composable` helpers — but the helper itself IS `@Composable`, and
> the composition is structured as: "read the four seasonal colors
> per flavor for the current theme + hemisphere, then build the map."
> The cleanest Compose-idiomatic path is to pre-compute a
> `Map<SeasonalInkFlavor, Color>` AT COMPOSITION using a plain
> `@Composable` for-loop (no `remember`), then use the map inside the
> `remember(previews, hemisphereState)` block. Specifically:
> ```kotlin
> val baseColors = mapOf(
>     SeasonalInkFlavor.Ink to SeasonalInkFlavor.Ink.toBaseColor(),
>     SeasonalInkFlavor.Moss to SeasonalInkFlavor.Moss.toBaseColor(),
>     SeasonalInkFlavor.Rust to SeasonalInkFlavor.Rust.toBaseColor(),
>     SeasonalInkFlavor.Dawn to SeasonalInkFlavor.Dawn.toBaseColor(),
> )
> val strokes = remember(previews, hemisphere, baseColors) {
>     previews.map { preview ->
>         val walkDate = Instant.ofEpochMilli(preview.spec.startMillis)
>             .atZone(ZoneId.systemDefault()).toLocalDate()
>         val shifted = SeasonalColorEngine.applySeasonalShift(
>             base = baseColors.getValue(preview.flavor),
>             intensity = SeasonalColorEngine.Intensity.Moderate,
>             date = walkDate,
>             hemisphere = hemisphere,
>         )
>         preview.spec.copy(ink = shifted)
>     }
> }
> ```
> Use THIS form in the PR — it cleanly separates theme reads (in the
> composable body) from pure math (inside `remember`).

Import additions (preview file):
```kotlin
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.Hemisphere
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.SeasonalColorEngine
```

---

## Task 6 — Pure-JVM engine tests

**New file:** `app/src/test/java/org/walktalkmeditate/pilgrim/ui/theme/seasonal/SeasonalWeightTest.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.theme.seasonal

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeasonalWeightTest {

    @Test fun `weight at peak day is 1`() {
        val w = SeasonalColorEngine.seasonalWeight(dayOfYear = 105, peakDay = 105, spread = 91f)
        assertEquals(1f, w, 0.0001f)
    }

    @Test fun `weight at peak plus spread is 0`() {
        val w = SeasonalColorEngine.seasonalWeight(dayOfYear = 105 + 91, peakDay = 105, spread = 91f)
        assertEquals(0f, w, 0.0001f)
    }

    @Test fun `weight at peak minus spread is 0`() {
        val w = SeasonalColorEngine.seasonalWeight(dayOfYear = 105 - 91, peakDay = 105, spread = 91f)
        assertEquals(0f, w, 0.0001f)
    }

    @Test fun `weight is symmetric around peak`() {
        val a = SeasonalColorEngine.seasonalWeight(dayOfYear = 95, peakDay = 105, spread = 91f)
        val b = SeasonalColorEngine.seasonalWeight(dayOfYear = 115, peakDay = 105, spread = 91f)
        assertTrue("a=$a b=$b", abs(a - b) < 0.0001f)
    }

    @Test fun `weight wraps across year boundary — winter peak Jan 15`() {
        // day 1 (Jan 1) → peak 15 : direct distance 14, wrap distance 351. min=14.
        // day 30 (Jan 30) → peak 15 : direct distance 15, wrap distance 350. min=15.
        // both should be high-weight, near-equal to each other.
        val jan1 = SeasonalColorEngine.seasonalWeight(dayOfYear = 1, peakDay = 15, spread = 91f)
        val jan30 = SeasonalColorEngine.seasonalWeight(dayOfYear = 30, peakDay = 15, spread = 91f)
        assertTrue("jan1=$jan1", jan1 > 0.9f)
        assertTrue("jan30=$jan30", jan30 > 0.9f)
    }

    @Test fun `weight wrap-across-year day 360 near winter peak`() {
        // day 360 (Dec 26) → peak 15 : direct distance 345, wrap distance 20. min=20 ≈ 0.78 weight.
        val w = SeasonalColorEngine.seasonalWeight(dayOfYear = 360, peakDay = 15, spread = 91f)
        assertTrue("w=$w should be > 0.8", w > 0.8f)
    }
}
```

**New file:** `app/src/test/java/org/walktalkmeditate/pilgrim/ui/theme/seasonal/SeasonalTransformTest.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.theme.seasonal

import java.time.LocalDate
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeasonalTransformTest {

    @Test fun `winter peak in northern produces winter deltas`() {
        val adj = SeasonalColorEngine.seasonalTransform(
            date = LocalDate.of(2026, 1, 15),
            hemisphere = Hemisphere.Northern,
        )
        // Winter only — other weights are near-zero 91 days away.
        // Check signs + approximate magnitudes:
        assertTrue("hue=${adj.hueDelta} should be near -0.02", abs(adj.hueDelta + 0.02f) < 0.003f)
        assertTrue("satMul=${adj.saturationMultiplier} should be near 0.85", abs(adj.saturationMultiplier - 0.85f) < 0.005f)
        assertTrue("briMul=${adj.brightnessMultiplier} should be near 0.95", abs(adj.brightnessMultiplier - 0.95f) < 0.005f)
    }

    @Test fun `summer peak in northern produces summer deltas`() {
        val adj = SeasonalColorEngine.seasonalTransform(
            date = LocalDate.of(2026, 7, 15),
            hemisphere = Hemisphere.Northern,
        )
        assertTrue("hue=${adj.hueDelta} should be near +0.01", abs(adj.hueDelta - 0.01f) < 0.003f)
        assertTrue("satMul=${adj.saturationMultiplier} should be near 1.15", abs(adj.saturationMultiplier - 1.15f) < 0.005f)
        assertTrue("briMul=${adj.brightnessMultiplier} should be near 1.03", abs(adj.brightnessMultiplier - 1.03f) < 0.005f)
    }

    @Test fun `southern in July matches northern in January`() {
        val jan15Northern = SeasonalColorEngine.seasonalTransform(
            LocalDate.of(2026, 1, 15), Hemisphere.Northern,
        )
        val jul15Southern = SeasonalColorEngine.seasonalTransform(
            LocalDate.of(2026, 7, 15), Hemisphere.Southern,
        )
        // Southern hem day 196 → adjustedDayOfYear (196+182)%365+1 = 14. Close to winter peak (15).
        assertEquals(jan15Northern.hueDelta, jul15Southern.hueDelta, 0.003f)
        assertEquals(jan15Northern.saturationMultiplier, jul15Southern.saturationMultiplier, 0.005f)
        assertEquals(jan15Northern.brightnessMultiplier, jul15Southern.brightnessMultiplier, 0.005f)
    }
}
```

**New file:** `app/src/test/java/org/walktalkmeditate/pilgrim/ui/theme/seasonal/ApplySeasonalShiftTest.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.theme.seasonal

import android.app.Application
import androidx.compose.ui.graphics.Color
import java.time.LocalDate
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `applySeasonalShift` calls through to android.graphics.Color which
 * needs an Android runtime. Robolectric provides it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ApplySeasonalShiftTest {

    private val mossBase = Color(red = 0.478f, green = 0.545f, blue = 0.435f, alpha = 1f)

    @Test fun `minimal intensity barely shifts`() {
        val summer = SeasonalColorEngine.applySeasonalShift(
            base = mossBase,
            intensity = SeasonalColorEngine.Intensity.Minimal,
            date = LocalDate.of(2026, 7, 15),
            hemisphere = Hemisphere.Northern,
        )
        assertTrue("R diff too large", abs(summer.red - mossBase.red) < 0.05f)
        assertTrue("G diff too large", abs(summer.green - mossBase.green) < 0.05f)
        assertTrue("B diff too large", abs(summer.blue - mossBase.blue) < 0.05f)
    }

    @Test fun `full summer intensity brightens and saturates moss`() {
        val summer = SeasonalColorEngine.applySeasonalShift(
            base = mossBase,
            intensity = SeasonalColorEngine.Intensity.Full,
            date = LocalDate.of(2026, 7, 15),
            hemisphere = Hemisphere.Northern,
        )
        // Brightness multiplier 1.03 + saturation 1.15 → at least one channel should grow.
        val mossLum = (mossBase.red + mossBase.green + mossBase.blue) / 3f
        val summerLum = (summer.red + summer.green + summer.blue) / 3f
        assertTrue("summer lum $summerLum should be >= moss lum $mossLum", summerLum >= mossLum - 0.001f)
    }

    @Test fun `moderate intensity is 40 percent of full`() {
        val full = SeasonalColorEngine.applySeasonalShift(
            mossBase, SeasonalColorEngine.Intensity.Full,
            LocalDate.of(2026, 7, 15), Hemisphere.Northern,
        )
        val moderate = SeasonalColorEngine.applySeasonalShift(
            mossBase, SeasonalColorEngine.Intensity.Moderate,
            LocalDate.of(2026, 7, 15), Hemisphere.Northern,
        )
        // Moderate delta should be ~40% of full delta per channel.
        listOf(
            Triple(mossBase.red, moderate.red, full.red),
            Triple(mossBase.green, moderate.green, full.green),
            Triple(mossBase.blue, moderate.blue, full.blue),
        ).forEach { (base, mod, ful) ->
            val fullDelta = ful - base
            val modDelta = mod - base
            if (abs(fullDelta) > 0.01f) {
                val ratio = modDelta / fullDelta
                assertTrue(
                    "ratio=$ratio should be near 0.4 (base=$base mod=$mod full=$ful)",
                    abs(ratio - 0.4f) < 0.15f,
                )
            }
        }
    }

    @Test fun `alpha is preserved`() {
        val halfTransparent = mossBase.copy(alpha = 0.5f)
        val shifted = SeasonalColorEngine.applySeasonalShift(
            halfTransparent, SeasonalColorEngine.Intensity.Full,
            LocalDate.of(2026, 7, 15), Hemisphere.Northern,
        )
        assertEquals(0.5f, shifted.alpha, 0.01f)
    }
}
```

**New file:** `app/src/test/java/org/walktalkmeditate/pilgrim/ui/theme/seasonal/HemisphereTest.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.theme.seasonal

import org.junit.Assert.assertEquals
import org.junit.Test

class HemisphereTest {
    @Test fun `sydney is southern`() {
        assertEquals(Hemisphere.Southern, Hemisphere.fromLatitude(-33.8688))
    }

    @Test fun `london is northern`() {
        assertEquals(Hemisphere.Northern, Hemisphere.fromLatitude(51.5074))
    }

    @Test fun `equator is northern by convention`() {
        assertEquals(Hemisphere.Northern, Hemisphere.fromLatitude(0.0))
    }

    @Test fun `just south of equator is southern`() {
        assertEquals(Hemisphere.Southern, Hemisphere.fromLatitude(-0.0001))
    }
}
```

**Verify:**
```
./gradlew testDebugUnitTest --tests '*ui.theme.seasonal.*'
```

---

## Task 7 — Robolectric test for `HemisphereRepository`

**New file:** `app/src/test/java/org/walktalkmeditate/pilgrim/ui/theme/seasonal/HemisphereRepositoryTest.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.theme.seasonal

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.location.FakeLocationSource

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HemisphereRepositoryTest {

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var locationSource: FakeLocationSource
    private lateinit var scope: CoroutineScope

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.preferencesDataStoreFile("hemisphere-test").delete()
        dataStore = PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile("hemisphere-test") },
        )
        locationSource = FakeLocationSource()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After fun tearDown() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test fun `defaults to northern when no override and no location`() = runTest {
        val repo = HemisphereRepository(dataStore, locationSource, scope)
        repo.hemisphere.test {
            assertEquals(Hemisphere.Northern, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `infers southern from negative latitude`() = runTest {
        locationSource.lastKnownResult = LocationPoint(
            timestamp = 0L, latitude = -33.8688, longitude = 151.2093,
        )
        val repo = HemisphereRepository(dataStore, locationSource, scope)
        repo.refreshFromLocationIfNeeded()
        repo.hemisphere.test {
            // Initial emission is Northern (default), then Southern after the DataStore write lands.
            val first = awaitItem()
            val next = if (first == Hemisphere.Southern) first else awaitItem()
            assertEquals(Hemisphere.Southern, next)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `override wins over auto-inference`() = runTest {
        locationSource.lastKnownResult = LocationPoint(
            timestamp = 0L, latitude = -33.8688, longitude = 151.2093,
        )
        val repo = HemisphereRepository(dataStore, locationSource, scope)
        repo.setOverride(Hemisphere.Northern)
        repo.refreshFromLocationIfNeeded()     // no-op because cached value exists
        repo.hemisphere.test {
            val first = awaitItem()
            val settled = if (first == Hemisphere.Northern) first else awaitItem()
            assertEquals(Hemisphere.Northern, settled)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

> **Assumption:** `FakeLocationSource` already exists at
> `app/src/test/.../location/FakeLocationSource.kt` (Stage 2-F + 3-C
> memory entries reference it) and has a mutable `lastKnownResult:
> LocationPoint?` property. Verify on entering this task — if the
> class exists but the property is named differently (e.g.,
> `lastKnown`), rename in the test. If the fake doesn't yet expose
> a mutable last-known-location hook, extend it in this task.

---

## Task 8 — CI gate

```
./gradlew assembleDebug lintDebug testDebugUnitTest
```

Expected: green. Nine new tests across four test files + the existing 27 from Stage 3-C still pass.

---

## Out-of-plan notes

- **No strings.xml changes** — nothing new is user-visible (the preview screen still says "Calligraphy preview").
- **No Manifest changes** — HemisphereRepository only reads from `LocationSource.lastKnownLocation()`, which already relies on the existing `ACCESS_COARSE_LOCATION` permission granted at onboarding.
- **No build.gradle.kts changes** — no new dependencies; `app.cash.turbine` already on testImplementation, DataStore + preferences already on main.
- **Hilt scope note**: `SeasonalModule.provideHemisphereScope()` returns a `@Singleton` `CoroutineScope` with `SupervisorJob + Dispatchers.Default`. This is fine for a DataStore-collection scope; it does NOT leak because `@Singleton` lives for the app's entire process lifetime. If the project later grows a shared `@ApplicationScope` coroutine scope (not yet), migrate this to use it.
- **Not adding a Settings UI** — the `setOverride` method exists for future use but has no caller in this PR.
