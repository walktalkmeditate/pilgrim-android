# Stage 12 — Per-walk Weather + Distance Accuracy Filter + Localization Stub + Bell-Coupled Haptic

**Date:** 2026-04-30
**Bundle:** items 3-6 from the post-Stage-11 deferred-parity list. Combined into one PR.

## Context

Four iOS-parity gaps closed together:

1. **Per-walk weather logging** (Stage 12-A) — iOS fetches WeatherKit at walk-start (+2s delay, one retry +10s), stores `condition`/`temperature`/`humidity`/`windSpeed` on the Walk row. Android hasn't shipped. Open-Meteo is the Android port plan (free, no key, privacy-respecting).
2. **Distance accuracy filter** (Stage 12-B) — iOS rejects samples with `horizontalAccuracy >= 100m` or `> desiredAccuracy` (default 20m). Android stores all samples + sums all haversines, causing inflated distances on poor-fix walks + ~5% drift in cross-platform `.pilgrim` round-trip.
3. **Bell-coupled haptic** (Stage 12-C) — iOS `BellPlayer.play(withHaptic:)` fires `UIImpactFeedbackGenerator(.medium)` synchronously when `bellHapticEnabled`. Android has `LocalBellHapticEnabled` CompositionLocal but coupling is at UI layer scattered, not at BellPlayer layer.
4. **Localization scaffolding** (Stage 12-D) — Android has 391 strings in `values/strings.xml`. No second-locale stub to verify the `values-XX/` directory pattern works under config-change. iOS is also English-only (`Base.lproj` + `en.lproj`), so this is parity at the *infrastructure* level not the *content* level.

iOS reference triple-checked against actual files:

- `pilgrim-ios/Pilgrim/Models/Data/DataModels/Versions/PilgrimV7.swift:133-136` — Walk weather fields
- `pilgrim-ios/Pilgrim/Models/Weather/WeatherService.swift` — fetch + condition mapper + 10-case enum
- `pilgrim-ios/Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift:106-143` — fetch trigger + retry policy
- `pilgrim-ios/Pilgrim/Models/Walk/WalkBuilder/Components/LocationManagement.swift:46-49` — `checkForAppropriateAccuracy`
- `pilgrim-ios/Pilgrim/Models/Audio/BellPlayer.swift:29-31` — `UIImpactFeedbackGenerator(.medium)` paired with bell
- `pilgrim-ios/Pilgrim/Scenes/WalkSummary/WalkSummaryView.swift:491-507` — weather line display

## Goals

- Walk row carries weather snapshot from real walks. Display in `WalkSummaryScreen`.
- Distance computation gates on horizontal-accuracy. Walk distance no longer inflated by bad-fix samples.
- Bell rings + haptic fires together at the player layer (not scattered at call sites).
- Localization infrastructure validated by a stub second locale.

## Non-goals

- **iOS auto-tuning `desiredAccuracy`** — iOS adapts the filter floor toward a global running average, capped at 20m. Defer; ship with fixed 20m default.
- **User-tunable GPS accuracy preference** — iOS exposes Settings → GPS Accuracy with `Off / Standard / N m`. Defer; no Settings UI in scope.
- **Tier-based `locationManager.desiredAccuracy` modulation** — iOS scales the OS-side request via `WalkSessionGuard.PowerTier`. Android always requests `Priority.PRIORITY_HIGH_ACCURACY`. Defer.
- **`refineLocation` altitude-stitching** — iOS replaces GPS altitude with `firstSampleAltitude + relativeBarometric`. Android `LocationPoint` has no altitude field; barometric pipeline absent. Defer to a future altitude-aware stage.
- **Null-island and jump-distance filtering** — iOS does NOT have these. Strict parity = horizontal-accuracy gate only.
- **iOS waypoint-related haptic patterns** (`whisperProximity`, `cairnProximity`, `placementFailed`, `stonePlaced(tier)`) — these attach to iOS waypoint surfaces (whispers, cairns, stones) that haven't been ported to Android. Defer until the Android waypoint port lands. Only port the bell-coupled haptic in this stage.
- **`<plurals>` resource scaffolding** — iOS lacks plurals too (no `.stringsdict`). Both apps ship inline counts via formatted strings. Defer until either platform actually localizes.
- **Imperial wind-speed display** — iOS displays only `°C` / `°F` from the snapshot; wind speed isn't surfaced in WalkSummaryView. Defer matching display path.

---

## Item A: Per-walk Weather

### Schema additions

`Walk.kt` gains four nullable columns mirroring iOS field names + units exactly:

```kotlin
@Entity(
    tableName = "walks",
    indices = [
        Index("start_timestamp"),
        Index("end_timestamp"),
        Index("uuid", unique = true),
    ],
)
data class Walk(
    // ... existing 9 fields …
    @ColumnInfo(name = "weather_condition")
    val weatherCondition: String? = null,        // 10-case enum rawValue, e.g. "clear" / "lightRain"
    @ColumnInfo(name = "weather_temperature")
    val weatherTemperature: Double? = null,      // °C, raw IEEE-754 double
    @ColumnInfo(name = "weather_humidity")
    val weatherHumidity: Double? = null,         // 0.0–1.0 fractional (NOT 0–100)
    @ColumnInfo(name = "weather_wind_speed")
    val weatherWindSpeed: Double? = null,        // m/s
)
```

### Migration

`MIGRATION_5_6` is explicit (ALTER only, no row scan). Database version bumps from 5 → 6.

```kotlin
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE walks ADD COLUMN weather_condition TEXT")
        db.execSQL("ALTER TABLE walks ADD COLUMN weather_temperature REAL")
        db.execSQL("ALTER TABLE walks ADD COLUMN weather_humidity REAL")
        db.execSQL("ALTER TABLE walks ADD COLUMN weather_wind_speed REAL")
    }
}
```

### Weather domain

```kotlin
// data/weather/WeatherCondition.kt
enum class WeatherCondition(val rawValue: String, val labelRes: Int, val iconRes: Int) {
    CLEAR("clear", R.string.weather_clear, R.drawable.ic_weather_clear),
    PARTLY_CLOUDY("partlyCloudy", R.string.weather_partly_cloudy, R.drawable.ic_weather_partly_cloudy),
    OVERCAST("overcast", R.string.weather_overcast, R.drawable.ic_weather_overcast),
    LIGHT_RAIN("lightRain", R.string.weather_light_rain, R.drawable.ic_weather_light_rain),
    HEAVY_RAIN("heavyRain", R.string.weather_heavy_rain, R.drawable.ic_weather_heavy_rain),
    THUNDERSTORM("thunderstorm", R.string.weather_thunderstorm, R.drawable.ic_weather_thunderstorm),
    SNOW("snow", R.string.weather_snow, R.drawable.ic_weather_snow),
    FOG("fog", R.string.weather_fog, R.drawable.ic_weather_fog),
    WIND("wind", R.string.weather_wind, R.drawable.ic_weather_wind),
    HAZE("haze", R.string.weather_haze, R.drawable.ic_weather_haze);

    companion object {
        fun fromRawValue(raw: String?): WeatherCondition? =
            values().firstOrNull { it.rawValue == raw }
    }
}

// data/weather/WeatherSnapshot.kt
data class WeatherSnapshot(
    val condition: WeatherCondition,
    val temperatureCelsius: Double,           // °C — required (fetch fails if missing)
    val humidityFraction: Double?,            // 0.0–1.0 (NOT 0–100); nullable to preserve
                                              // API-omitted vs zero-humidity distinction
    val windSpeedMps: Double?,                // m/s; nullable for same reason
)
```

Strings (verbatim from iOS labels):
```xml
<string name="weather_clear">Clear</string>
<string name="weather_partly_cloudy">Partly cloudy</string>
<string name="weather_overcast">Overcast</string>
<string name="weather_light_rain">Light rain</string>
<string name="weather_heavy_rain">Heavy rain</string>
<string name="weather_thunderstorm">Thunderstorm</string>
<string name="weather_snow">Snow</string>
<string name="weather_fog">Foggy</string>
<string name="weather_wind">Windy</string>
<string name="weather_haze">Hazy</string>
```

Drawables: 10 vector assets sourced from Material Symbols (`sunny`, `partly_cloudy_day`, `cloud`, `rainy_light`, `rainy_heavy`, `thunderstorm`, `weather_snowy`, `foggy`, `air`, `mist`). Tinted via `pilgrimColors.fog` at draw time so they match the existing weatherLine fog color.

### Open-Meteo client

```kotlin
// data/weather/OpenMeteoClient.kt
@Singleton
class OpenMeteoClient @Inject constructor(
    @WeatherHttpClient private val client: OkHttpClient,
    private val json: Json,
) {
    suspend fun fetchCurrent(latitude: Double, longitude: Double): WeatherSnapshot? =
        withContext(Dispatchers.IO) {
            // CRITICAL #5: Defensive NaN/Infinity guard. Corrupted location
            // data would emit literal `latitude=NaN` URL params; Open-Meteo
            // 400s. Returning null avoids a noisy retry loop in logs.
            if (!latitude.isFinite() || !longitude.isFinite()) return@withContext null
            try {
                // String interpolation is safe — Kotlin/Java Double.toString
                // is locale-independent (always emits `.`, JLS §15.18.1.1).
                // Using HttpUrl.Builder is fine too; either works.
                val url = "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=$latitude&longitude=$longitude" +
                    "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m" +
                    "&temperature_unit=celsius&wind_speed_unit=ms"

                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@withContext null

                val body = response.body?.string() ?: return@withContext null
                val parsed = json.decodeFromString(OpenMeteoResponse.serializer(), body)

                val current = parsed.current ?: return@withContext null
                val windSpeedMs = current.windSpeed10m ?: 0.0
                // CRITICAL #4: Unknown WMO codes map to CLEAR, mirroring
                // iOS `WeatherService.swift:140-141 @unknown default → .clear`.
                // Drops to fail-open: an unhandled future code still writes
                // a snapshot rather than nuking the entire fetch.
                val condition = mapWmoCode(current.weatherCode, windSpeedMs)

                WeatherSnapshot(
                    condition = condition,
                    temperatureCelsius = current.temperature2m ?: return@withContext null,
                    // ISSUE #12: Humidity nullable propagates all the way
                    // through. Distinguishes API-omitted humidity from
                    // "0% humidity". Same for windSpeed.
                    humidityFraction = current.relativeHumidity2m?.let { it / 100.0 },
                    windSpeedMps = current.windSpeed10m,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "weather fetch failed", t)
                null
            }
        }

    /**
     * iOS `WeatherService.mapCondition` (`WeatherService.swift:114-143`):
     *   - if windSpeed > 10 m/s → .wind (overrides any condition)
     *   - case .clear, .mostlyClear, .hot → .clear
     *   - case .partlyCloudy, .mostlyCloudy → .partlyCloudy
     *   - case .cloudy → .overcast (note iOS naming inversion)
     *   - case .drizzle, .freezingDrizzle → .lightRain (NOT wind-checked)
     *   - case .rain, .heavyRain, .freezingRain → windSpeed > 5 ? .heavyRain : .lightRain
     *   - case .snow, .heavySnow, .blowingSnow, .flurries, .sleet → .snow
     *   - case .thunderstorms, .strongStorms, .isolatedThunderstorms → .thunderstorm
     *   - case .foggy → .fog
     *   - case .windy, .breezy → .wind
     *   - case .haze, .smoky → .haze
     *   - @unknown default → .clear
     *
     * WMO → 10-case mapping with iOS-faithful wind-checks and `mostlyClear`
     * → CLEAR (NOT partlyCloudy — see CRITICAL #2). Drizzle (51/53/55)
     * skips wind-check; ALL rain (61/63/65/80/81/82) uses iOS rain rule.
     */
    private fun mapWmoCode(code: Int?, windSpeedMs: Double): WeatherCondition {
        if (windSpeedMs > 10.0) return WeatherCondition.WIND
        return when (code) {
            // 0 = clear, 1 = mainly clear → both CLEAR per iOS .mostlyClear
            0, 1 -> WeatherCondition.CLEAR
            2 -> WeatherCondition.PARTLY_CLOUDY
            3 -> WeatherCondition.OVERCAST
            45, 48 -> WeatherCondition.FOG
            // Drizzle family — iOS `case .drizzle: .lightRain` (no wind check)
            51, 53, 55, 56, 57 -> WeatherCondition.LIGHT_RAIN
            // Rain family — iOS `case .rain: windSpeed > 5 ? .heavyRain : .lightRain`
            // Applied uniformly to slight (61, 80), moderate (63, 81), heavy (65, 82)
            61, 63, 65, 80, 81, 82 ->
                if (windSpeedMs > 5.0) WeatherCondition.HEAVY_RAIN
                else WeatherCondition.LIGHT_RAIN
            // Freezing rain — iOS lumps in snow per `case .freezingRain` mapping
            66, 67 -> WeatherCondition.SNOW
            71, 73, 75, 77, 85, 86 -> WeatherCondition.SNOW
            95, 96, 99 -> WeatherCondition.THUNDERSTORM
            // CRITICAL #4: Unknown / null code → CLEAR (iOS @unknown default)
            else -> WeatherCondition.CLEAR
        }
    }

    private companion object { const val TAG = "OpenMeteo" }
}

@Serializable
private data class OpenMeteoResponse(val current: Current? = null) {
    @Serializable
    data class Current(
        @SerialName("temperature_2m") val temperature2m: Double? = null,
        @SerialName("relative_humidity_2m") val relativeHumidity2m: Double? = null,
        @SerialName("weather_code") val weatherCode: Int? = null,
        @SerialName("wind_speed_10m") val windSpeed10m: Double? = null,
    )
}
```

`@WeatherHttpClient` qualifier wired in `NetworkModule` reusing the existing OkHttp setup pattern (timeouts ≤ 10s; retry-on-connection-failure).

ProGuard: `OpenMeteoResponse` has `@Serializable` — confirm existing keep rules cover it (Stage 10-HI added rules covering `app/**`).

### Fetch orchestration

iOS pattern (`ActiveWalkViewModel.fetchWeather`): +2s delay after walk-start, one retry +10s on no-snapshot OR no-location. NOT mid-walk. Mirror exactly via VM-side coroutine:

```kotlin
// Inside WalkViewModel (or wherever active-walk state lives)
private var weatherJob: Job? = null

fun onWalkStarted(walkId: Long) {
    weatherJob?.cancel()
    weatherJob = viewModelScope.launch {
        delay(2_000L)
        if (!fetchAndPersistWeather(walkId, retryOnFailure = true)) {
            // First attempt failed; one retry +10s.
            delay(10_000L)
            fetchAndPersistWeather(walkId, retryOnFailure = false)
        }
    }
}

private suspend fun fetchAndPersistWeather(
    walkId: Long,
    retryOnFailure: Boolean,
): Boolean {
    val location = currentLocation.value ?: return false
    val snapshot = openMeteoClient.fetchCurrent(location.latitude, location.longitude)
        ?: return false
    walkRepository.updateWeather(walkId, snapshot)
    return true
}
```

`WalkRepository.updateWeather`:
```kotlin
suspend fun updateWeather(walkId: Long, snapshot: WeatherSnapshot) {
    walkDao.updateWeather(
        id = walkId,
        condition = snapshot.condition.rawValue,
        temperature = snapshot.temperatureCelsius,
        humidity = snapshot.humidityFraction,            // Double? — nullable per ISSUE #12
        windSpeed = snapshot.windSpeedMps,               // Double? — nullable per ISSUE #12
    )
}
```

`WalkDao.updateWeather` is a new `@Query` UPDATE with nullable Double params for humidity and windSpeed:
```kotlin
@Query("""
    UPDATE walks SET
        weather_condition = :condition,
        weather_temperature = :temperature,
        weather_humidity = :humidity,
        weather_wind_speed = :windSpeed
    WHERE id = :id
""")
suspend fun updateWeather(
    id: Long,
    condition: String?,
    temperature: Double?,
    humidity: Double?,
    windSpeed: Double?,
)
```

**ISSUE #11 — exact cancel seams in `WalkViewModel`:**
- `discardWalk()` (existing fn that emits `WalkAction.Discard` to the controller): add `weatherJob?.cancel()` first thing.
- The terminal `Finished` state observer in `WalkViewModel` (where the VM transitions out of active-walk state, line ~570 of WalkViewModel.kt — implementer: search for `WalkState.Finished` consumer): add `weatherJob?.cancel()`. After finalize, the walk row is sealed; a stale weather write would land harmlessly but pollute the row. Better to cancel.
- VM destruction handles itself via `viewModelScope`'s cancellation contract — no explicit code.

Process death at +5s = lost fetch. Acceptable per iOS parity (iOS also doesn't retry post-kill).

### Display in WalkSummaryScreen

iOS line at `WalkSummaryView.swift:491-507`:
```swift
HStack {
    Image(systemName: cond.icon)
    Text("\(cond.label), \(WeatherSnapshot.formatTemperature(temp, imperial: imperial))")
}
```

Format: `"{label}, {N}°C"` or `"{N}°F"` (zero decimals).

Android equivalent in `WalkSummaryScreen.kt`:
```kotlin
walk.weatherCondition?.let { conditionRaw ->
    val condition = WeatherCondition.fromRawValue(conditionRaw) ?: return@let
    val temperature = walk.weatherTemperature ?: return@let
    val imperial = distanceUnits == UnitSystem.Imperial

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(condition.iconRes),
            contentDescription = null,
            tint = pilgrimColors.fog,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = "${stringResource(condition.labelRes)}, ${formatTemperature(temperature, imperial)}",
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
        )
    }
}

// helper
private fun formatTemperature(celsius: Double, imperial: Boolean): String {
    val rounded = if (imperial) celsius * 9.0 / 5.0 + 32.0 else celsius
    return String.format(Locale.US, "%.0f°%s", rounded, if (imperial) "F" else "C")
}
```

### Tests
1. `OpenMeteoClientTest.mapsCleanCodes` — MockWebServer responds with sample JSON for codes 0, 1, 3, 95; verify mapping CLEAR, CLEAR (per WMO 1 → mostlyClear → CLEAR per CRITICAL #2), OVERCAST, THUNDERSTORM.
2. `OpenMeteoClientTest.windSpeedOverridesCondition` — JSON with `weather_code = 0` (clear) + `wind_speed_10m = 12` → `WeatherCondition.WIND`.
3. `OpenMeteoClientTest.rainWindRuleAppliesToAllRainCodes` — codes 61/63/65/80/81/82 + wind 6 → HEAVY_RAIN; same codes + wind 4 → LIGHT_RAIN. Verifies CRITICAL #3 (wind-check uniform across rain family, not just moderate).
4. `OpenMeteoClientTest.drizzleSkipsWindCheck` — codes 51/53/55 + wind 6 → still LIGHT_RAIN (drizzle never wind-checked).
5. `OpenMeteoClientTest.unknownCodeMapsToClear` — code 999 returns CLEAR (per CRITICAL #4 / iOS @unknown default).
6. `OpenMeteoClientTest.nanLatitudeReturnsNull` — call with `Double.NaN` → null, no network call (CRITICAL #5).
7. `OpenMeteoClientTest.nullableHumidityPropagates` — JSON omits `relative_humidity_2m` → snapshot.humidityFraction == null (NOT 0.0). Same for windSpeed.
8. `OpenMeteoClientTest.networkFailureReturnsNull` — MockWebServer disconnects; assert null + no throw.
9. `MIGRATION_5_6` test — `MigrationTestHelper`, walks v5 → v6, verify 4 cols added + null + old data preserved.
10. `WalkDao.updateWeather` test — round-trip nullable + non-null values through Room.
11. `WalkViewModel.weatherJobScheduling` — virtual clock; advance 2s → fetch fires; first call returns null → advance 10s → second call fires; second null → no further calls. (Test the retry policy.)
12. `WalkViewModel.weatherJobCancelsOnDiscard` — start walk → fetch in flight → discard walk → assert weatherJob.isCancelled, no write happens after discard.
13. `WalkViewModel.weatherJobCancelsOnFinalize` — start walk → fetch in flight → finalize walk → assert weatherJob.isCancelled, no write after.
14. Compose UI test — `WalkSummaryWeatherLineTest`: render with snapshot, assert label + temperature. Render with null condition, assert no Row composed. Render with imperial units, assert °F.

---

## Item B: Distance Accuracy Filter

### Filter location

iOS gates BEFORE storing — failed-accuracy samples are dropped at the live-capture pipe, never reach the relay. Android storage path is `LocationSampled action → reducer adds delta → PersistLocation effect → DAO insert`. To match iOS "rejected = invisible to distance AND not stored", the gate must run UPSTREAM of the reducer.

Cleanest seam: filter in `FusedLocationSource.kt` before emitting `LocationPoint` to the controller. That way no `LocationSampled` action fires for a rejected sample.

**CRITICAL #1: per-collection-instance reset.** `FusedLocationSource` is a `@Singleton`. The `hasEmitted` AtomicBoolean MUST live inside the `callbackFlow { ... }` block so each new `locationFlow()` collection (i.e. each new walk) gets a fresh anchor. A class-level field would carry walk-1's `true` into walk-2, rejecting walk-2's first bad-accuracy sample instead of anchoring it.

```kotlin
// FusedLocationSource.kt
fun locationFlow(): Flow<LocationPoint> = callbackFlow {
    // Per-collection (per-walk) anchor flag. `callbackFlow`'s scope is
    // a single collection — a fresh walk = fresh callbackFlow body =
    // fresh hasEmitted. Singleton-scoped class would NOT work here
    // because subsequent walks would inherit the previous walk's
    // anchored state.
    val hasEmitted = AtomicBoolean(false)

    val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (location in result.locations) {
                val point = location.toLocationPoint() ?: continue
                // First sample is force-accepted, mirroring iOS
                // LocationManagement.swift:236 `guard isFirst ||
                // checkForAppropriateAccuracy(location)`. Sets the
                // walk's geographic anchor even with bad GPS so we
                // don't strand an empty walk.
                val isFirst = !hasEmitted.get()
                if (!isFirst && !meetsAccuracyGate(point)) continue
                hasEmitted.set(true)
                trySend(point)
            }
        }
    }
    // … existing requestLocationUpdates wiring …
    awaitClose { fusedLocationProviderClient.removeLocationUpdates(locationCallback) }
}

private fun meetsAccuracyGate(point: LocationPoint): Boolean {
    // ISSUE #13: null-accuracy = REJECT (defensive). iOS doesn't see
    // null because CLLocation.horizontalAccuracy is non-optional Double
    // (uses negative as sentinel). Android FLP can return no-accuracy
    // (LocationPoint wraps `if (location.hasAccuracy()) location.accuracy
    // else null`). A sample we can't quality-check shouldn't count
    // toward distance — the first-sample-anchor exception above still
    // gives a walk its starting position even if all subsequent
    // samples lack accuracy fields.
    val accuracy = point.horizontalAccuracyMeters ?: return false
    return accuracy < HARD_CEILING_METERS && accuracy <= DESIRED_ACCURACY_METERS
}

private companion object {
    /** iOS hard ceiling: any horizontalAccuracy >= 100m is rejected. */
    const val HARD_CEILING_METERS = 100f
    /** iOS default `desiredAccuracy` when user hasn't set GPS Accuracy preference. */
    const val DESIRED_ACCURACY_METERS = 20f
}
```

If the existing `locationFlow()` body uses a different shape (state machine outside `callbackFlow`), refactor so the AtomicBoolean is per-collection. Verify during implementation: confirm new walks get a fresh `hasEmitted=false` by reading `FusedLocationSource.kt` actual structure first.

### Tests
1. `FusedLocationSourceTest.firstSampleAcceptedRegardlessOfAccuracy` — first emission with accuracy=200m passes through.
2. `FusedLocationSourceTest.rejectedAccuracyDroppedSilently` — second emission with accuracy=120m does NOT reach the channel.
3. `FusedLocationSourceTest.acceptableAccuracyPasses` — second emission with accuracy=15m passes.
4. `FusedLocationSourceTest.borderlineEqualsDesiredAccuracyPasses` — accuracy=20m passes (`<= 20`).
5. `FusedLocationSourceTest.borderlineEqualsHardCeilingFails` — accuracy=100m fails (`< 100` is strict).
6. `FusedLocationSourceTest.nullAccuracyRejected` — when `horizontalAccuracyMeters == null`, gate REJECTS (defensive; a sample we can't quality-check shouldn't count). EXCEPT for the first sample which is anchored regardless. ISSUE #13 in spec review explicitly chose this over iOS-conflated "accept-when-no-floor" semantics.
7. `FusedLocationSourceTest.firstSampleAnchorsEvenWhenAccuracyNull` — walk's first sample with null accuracy still emits, sets the geographic anchor.
8. `FusedLocationSourceTest.newCollectionResetsAnchor` — collect locationFlow once with a first-sample bad-accuracy emission (anchored). Cancel collection. Re-collect. Bad-accuracy emission again — must be anchored again, NOT rejected (per-collection AtomicBoolean reset). Closes the singleton-leak risk.
9. Existing `WalkController` integration test: simulate a sequence of 5 samples, 2 with bad accuracy, assert distance reflects only the 3 good samples (vs current "distance reflects all 5").

---

## Item C: Bell-Coupled Haptic

iOS `BellPlayer.play` accepts `withHaptic: Bool` and fires `UIImpactFeedbackGenerator(style: .medium)` synchronously. Android currently has `LocalBellHapticEnabled` CompositionLocal but coupling is at UI layer call sites — scattered.

### BellPlaying surface change

ISSUE #7 fix: haptic is the DEFAULT, opt-out via param. Mirrors iOS `BellPlayer.play(_ asset, withHaptic: Bool = true)`. Sites that don't want haptic explicitly pass `withHaptic = false`. Eliminates the audit risk of a future `play()` call dropping haptic silently.

```kotlin
interface BellPlaying {
    fun play()
    fun play(scale: Float) { play() }
    /**
     * Fire bell + (optional) haptic. iOS-faithful default = haptic ON.
     * Haptic gates internally on `bellHapticEnabled` preference. Default
     * body delegates to play() so existing fakes compile unchanged.
     */
    fun play(scale: Float = 1.0f, withHaptic: Boolean = true) { play(scale) }
}
```

(Implementer's choice between Kotlin's default-arg overload semantics: pick whichever resolves cleanly without ambiguity. May need to remove the bare `play(scale: Float)` overload to avoid resolution conflicts — verify during implementation.)

### BellPlayer impl

ISSUE #8 fix: haptic fires AFTER bell audio starts, matching iOS `BellPlayer.swift:26,30` sequence.

ISSUE #9 fix: probe `vibrator.areAllPrimitivesSupported(PRIMITIVE_CLICK)` BEFORE attempting composition. Some API 30+ devices stub the primitive — explicit detection beats catch-and-fallback.

ISSUE #10 fix: Hilt module provides `Vibrator` via the API-aware path:

```kotlin
// di/HapticsModule.kt
@Module
@InstallIn(SingletonComponent::class)
object HapticsModule {
    @Provides
    @Singleton
    fun provideVibrator(@ApplicationContext context: Context): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // API 31+: VibratorManager replaces direct VIBRATOR_SERVICE access
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
}
```

```kotlin
// BellPlayer.kt
override fun play(scale: Float, withHaptic: Boolean) {
    playInternal(scale = scale)
    // iOS fires haptic AFTER bell.play() returns (BellPlayer.swift:26,30).
    // Order: audio starts first, haptic is the tactile companion.
    if (withHaptic && soundsPreferences.bellHapticEnabled.value) {
        fireMediumImpact()
    }
}

private fun fireMediumImpact() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {  // API 30+
        // ISSUE #9: Probe before attempting. Some manufacturers stub
        // PRIMITIVE_CLICK on API 30+ devices — fall through to waveform.
        if (vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)) {
            try {
                val composition = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f)
                    .compose()
                vibrator.vibrate(composition)
                return
            } catch (t: Throwable) {
                Log.w(TAG, "primitive composition failed; falling back", t)
                // fall through to waveform
            }
        }
        fireWaveformFallback()
    } else {
        fireWaveformFallback()
    }
}

private fun fireWaveformFallback() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        // 30ms pulse @ default amplitude — matches iOS .medium duration
        // empirically (iOS spec: ~30ms transient).
        val effect = VibrationEffect.createOneShot(30L, VibrationEffect.DEFAULT_AMPLITUDE)
        vibrator.vibrate(effect)
    } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(30L)
    }
}
```

### Call site updates

`MeditationBellObserver` currently calls `bellPlayer.play()`. Switch to `bellPlayer.play(scale = 1.0f, withHaptic = true)` (or just `play()` after the new default — verify Kotlin resolution). Audit other bell call sites:
- Walk-start bell — iOS fires withHaptic=true. Switch.
- Walk-end bell — iOS fires withHaptic=true. Switch.
- Milestone overlay — Stage 11 used `bellPlayer.play(scale = 0.4f)`. **CORRECTION (post-implementation closing review):** iOS DOES pair haptic on milestone. iOS `BellPlayer.swift:14` declares `func play(_ asset, volume: Float = 0.7, withHaptic: Bool = true)` — `withHaptic` defaults to **true**. iOS `PracticeSummaryHeader.swift:92` calls `BellPlayer.shared.play(asset, volume: 0.4)` without overriding `withHaptic`, so the default fires. Switch the Android milestone path to `bellPlayer.play(scale = MILESTONE_BELL_SCALE, withHaptic = true)` for iOS parity. (An earlier draft of this spec incorrectly claimed iOS milestone is haptic-silent — that claim was wrong.)

### Tests
1. `BellPlayerTest.play_firesPrimitiveOnApi30PlusWhenSupported` — Robolectric @Config sdk=30, ShadowVibrator reports `areAllPrimitivesSupported(PRIMITIVE_CLICK)=true`, assert composition fires.
2. `BellPlayerTest.play_fallsBackToWaveformWhenPrimitiveUnsupported` — @Config sdk=30 but `areAllPrimitivesSupported=false`, assert createOneShot fires (not composition).
3. `BellPlayerTest.play_firesWaveformOnApi28_29` — @Config sdk=28, assert createOneShot fires.
4. `BellPlayerTest.play_skipsHapticWhenBellHapticPrefDisabled` — set `bellHapticEnabled=false`, assert vibrator never called even when `withHaptic=true`.
5. `BellPlayerTest.play_skipsHapticWhenWithHapticFalse` — `withHaptic=false`, assert vibrator never called even when `bellHapticEnabled=true`.
6. `BellPlayerTest.play_stillPlaysBellRegardlessOfHapticDecisions` — bell audio fires across all 4 (haptic-pref × withHaptic) combinations.
7. `BellPlayerTest.play_hapticFiresAfterBellAudio` — record sequence; assert audio.start()-equivalent runs BEFORE vibrator.vibrate().

---

## Item D: Localization Stub

### Verify infrastructure

Android already has `app/src/main/res/values/strings.xml` (391 entries). Default `values/` IS the English source. Adding a `values-fr/strings.xml` stub with a divergent value validates the resource-resolution path under runtime config-change.

MINOR #14 fix: stub uses a new key with a fr-divergent value so the test ACTUALLY proves locale resolution worked (not just fell back to default).

```xml
<!-- values-fr/strings.xml -->
<resources>
    <!-- Stage 12-D: stub second locale verifying the values-XX/ resource
         resolution path. iOS is also English-only (Base.lproj + en.lproj
         only). When real localization arrives, both apps need translators
         + plural rules together. -->
    <string name="locale_resolution_marker">fr</string>
</resources>
```

Add the same key to `values/strings.xml` with value `"en"`:

```xml
<!-- values/strings.xml — append: -->
<string name="locale_resolution_marker">en</string>
```

Untranslated keys fall back to `values/` — Android's standard behavior. The marker key makes it explicit which locale resolved. Document the convention in CLAUDE.md.

### Test

```kotlin
@Test
fun stubFrenchLocaleResolvesToFrValue() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val frConfig = Configuration(context.resources.configuration)
    frConfig.setLocale(Locale.FRANCE)
    val frContext = context.createConfigurationContext(frConfig)
    // If the values-fr/ directory wasn't wired correctly, this would
    // fall back to "en" (default values/). Asserting "fr" proves the
    // resolver walked the locale-qualified directory.
    assertEquals("fr", frContext.getString(R.string.locale_resolution_marker))
}

@Test
fun defaultLocaleResolvesToDefaultValue() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    assertEquals("en", context.getString(R.string.locale_resolution_marker))
}
```

Two tests: the fr resolution proves the locale-qualified directory works; the default test pins the fallback behavior so a future bug renaming the marker key fails loudly.

---

## Open questions

None. All four items have unambiguous iOS reference + clean Android port path. iOS reference triple-checked at file:line level for each item.

## Risks

1. **Open-Meteo rate limiting / availability.** Open-Meteo is free, no key, but rate-limited (~10k requests/day). One walk-start per user × moderate user base fits well within. If we hit rate limits in the future, switch to a fallback provider behind the same `OpenMeteoClient` interface.
2. **`Vibrator` deprecation paths.** API 31+ requires `VibratorManager.getDefaultVibrator()` over `context.getSystemService(VIBRATOR_SERVICE)`. `HapticsModule.provideVibrator` API-forks at injection time. (Implementation snippet provided above per ISSUE #10.)
3. **MIGRATION_5_6 on dev devices already at v5.** The Stage 11 cache cols migration lands fresh; this builds on it. Both are nullable-only ALTERs — concurrent or sequential, both safe.
4. **`Locale` for temperature formatting.** Use `Locale.US` for numeric format consistency (per existing codebase convention from Stage 5-A / 6-B). Imperial conversion is a pure arithmetic; doesn't need locale.
5. **Distance filter regression on walks with consistently bad GPS.** A user walking through urban canyons or in a heavy backpack could see EVERY sample after the first rejected → no distance ever accumulates. iOS has the same issue; mitigated by the first-sample anchor + the auto-tuner (which we're not porting). For Android Stage 12, monitor user reports — if it surfaces, port the auto-tuner in a follow-up.
6. **`hasEmitted` per-collection scope.** CRITICAL #1 fix moves AtomicBoolean inside `callbackFlow { ... }`. Verify during implementation that the existing `FusedLocationSource.locationFlow()` shape allows inline scoping. If it uses a state-machine field outside `callbackFlow`, refactor — fixing the singleton-leak is non-negotiable.
7. **`PRIMITIVE_CLICK` device support.** Some API 30+ devices (notably budget Samsung phones) stub the primitive. ISSUE #9 fix adds `areAllPrimitivesSupported` probe before composition, falls through to waveform. The waveform path is a 30ms one-shot — perceptually similar enough to iOS .medium for our purposes.
8. **`bellHapticEnabled` preference initialization.** Verify the existing pref defaults to `true` (matching iOS `withHaptic: Bool = true` default). If pref defaults to `false`, fresh installs would feel less iOS-like — verify in implementation.

## Out of scope (deferred)

- iOS auto-tuned `desiredAccuracy` via running average.
- Settings → GPS Accuracy preference UI.
- Tier-based `locationManager.desiredAccuracy` modulation.
- Altitude-stitching `refineLocation`.
- iOS waypoint-related haptic patterns (whisperProximity, cairnProximity, placementFailed, stonePlaced(tier)).
- `<plurals>` resource scaffolding.
- WeatherOverlayView particle/tint animation during ActiveWalk.
- Imperial wind-speed display.
- Real translations for the French stub (untranslated keys correctly fall back to English).

## Implementation checklist

### Schema + DAO
- `Walk.kt` — append 4 weather fields AFTER existing `distanceMeters` + `meditationSeconds` cache cols. Order: `weatherCondition`, `weatherTemperature`, `weatherHumidity`, `weatherWindSpeed`. Room hashes column order — keep declaration order matching migration ALTER order.
- `PilgrimDatabase.kt` — version 5→6, add `MIGRATION_5_6` + wire into `DatabaseModule`.
- `WalkDao.kt` — add `updateWeather(id, condition, temperature, humidity, windSpeed)` with nullable params.
- **Verify schema export:** run `./gradlew :app:assembleDebug`, confirm `app/schemas/org.walktalkmeditate.pilgrim.data.PilgrimDatabase/6.json` is generated and committed. The `room.schemaLocation` annotation processor option is already configured per existing `1.json…5.json` files.

### Weather domain + client
- New `WeatherCondition.kt` enum + `R.string.weather_*` + `R.drawable.ic_weather_*`.
- New `WeatherSnapshot.kt`.
- New `OpenMeteoClient.kt` + `OpenMeteoResponse` Serializable model.
- `NetworkModule.kt` — provide `@WeatherHttpClient OkHttpClient`.
- `WalkRepository.updateWeather(walkId, snapshot)`.

### Walk lifecycle
- `WalkViewModel` — `weatherJob` + `onWalkStarted` + retry policy + cancel-on-discard / cancel-on-finalize.
- `WalkController` — pass `walkId` to VM at start so weather job has target.

### UI
- `WalkSummaryScreen.kt` — render weather line below stats, mirror iOS layout exactly.
- `formatTemperature` helper.

### Distance filter
- `FusedLocationSource.kt` — `meetsAccuracyGate` + `hasEmitted` AtomicBoolean + first-sample anchor.
- Reset `hasEmitted` on source re-start.

### Haptic
- `BellPlaying.kt` — add `playWithHaptic()` + `playWithHaptic(scale)` defaulted overloads.
- `BellPlayer.kt` — implement; gate haptic on `bellHapticEnabled` pref; use composition (API 30+) → waveform (API 28-29) fallback.
- `BellModule` (or `AudioModule`) — provide `Vibrator` via Hilt.
- `MeditationBellObserver.kt` — switch to `playWithHaptic()`.
- Audit other bell call sites; switch where appropriate.

### Localization stub
- New `app/src/main/res/values-fr/strings.xml` with `app_name` only.
- Test verifying resolution.

### Tests per item — see test lists above.

### Total scope
~20 new/modified production files + 12-15 new tests. Mid-large stage.
