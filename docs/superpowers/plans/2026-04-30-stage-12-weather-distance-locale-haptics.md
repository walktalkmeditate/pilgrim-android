# Stage 12 Implementation Plan — Weather + Distance Filter + Bell Haptic + Locale Stub

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Bundle four iOS-parity items: (a) per-walk weather via Open-Meteo + 4 nullable Walk cols + +2s/+10s retry policy + WalkSummaryScreen line; (b) distance accuracy filter at FusedLocationSource (per-collection anchor + iOS-faithful 100m/20m gate); (c) bell-coupled haptic at BellPlayer layer with API-aware Vibrator fallback; (d) localization stub with divergent fr value.

**Architecture:** New `OpenMeteoClient` over OkHttp with WMO→10-case enum mapping (iOS-faithful wind-overrides + drizzle/rain wind rules). `MIGRATION_5_6` ALTER-only. `FusedLocationSource` callbackFlow gains per-collection `AtomicBoolean` anchor + accuracy gate (REJECT null-accuracy except first sample). `BellPlaying.play(scale, withHaptic = true)` defaulted-arg overload; `BellPlayer` probes `PRIMITIVE_CLICK` support, falls back to `createOneShot(30ms)`. `HapticsModule.provideVibrator` API-31 fork. `values-fr/strings.xml` + `locale_resolution_marker` key.

**Tech Stack:** Kotlin 2.x, Jetpack Compose, Material 3, Hilt, Room 2.6+, OkHttp + kotlinx-serialization, kotlinx-coroutines + Flow/StateFlow, JUnit 4 + Robolectric + Turbine + MigrationTestHelper + MockWebServer.

---

### Task 1: Walk entity gains 4 weather cols + MIGRATION_5_6

**Files:**
- Modify: `app/src/main/java/.../data/entity/Walk.kt`
- Modify: `app/src/main/java/.../data/PilgrimDatabase.kt`
- Modify: `app/src/main/java/.../di/DatabaseModule.kt`
- Test: `app/src/test/java/.../data/PilgrimDatabaseMigrationTest.kt` (extend existing)

- [ ] **Step 1: Write failing migration test**

```kotlin
@Test
fun `migration 5 to 6 adds 4 weather cols nullable, preserves existing rows`() {
    helper.createDatabase(TEST_DB, 5).use { db ->
        db.execSQL(
            "INSERT INTO walks (id, uuid, start_timestamp, end_timestamp, " +
            "intention, favicon, notes, distance_meters, meditation_seconds) " +
            "VALUES (42, 'abc', 1000, 5000, NULL, NULL, NULL, 1500.0, 600)"
        )
    }
    val migrated = helper.runMigrationsAndValidate(
        TEST_DB, 6, true, MIGRATION_5_6,
    )
    migrated.query(
        "SELECT id, distance_meters, meditation_seconds, " +
        "weather_condition, weather_temperature, weather_humidity, weather_wind_speed " +
        "FROM walks WHERE id = 42"
    ).use { c ->
        assertTrue(c.moveToFirst())
        assertEquals(42L, c.getLong(0))
        assertEquals(1500.0, c.getDouble(1), 0.0001)
        assertEquals(600L, c.getLong(2))
        assertTrue(c.isNull(3))
        assertTrue(c.isNull(4))
        assertTrue(c.isNull(5))
        assertTrue(c.isNull(6))
    }
}
```

(Use the existing in-file Robolectric DataStore-style helper pattern, NOT MigrationTestHelper, if the project's existing migration tests follow that. Check `PilgrimDatabaseMigrationTest.kt` precedent first.)

- [ ] **Step 2: Run — FAIL** (`MIGRATION_5_6` unresolved + columns missing).

- [ ] **Step 3: Append fields to Walk entity (AFTER existing distanceMeters + meditationSeconds)**

```kotlin
data class Walk(
    // … existing 11 fields …
    @ColumnInfo(name = "distance_meters")
    val distanceMeters: Double? = null,
    @ColumnInfo(name = "meditation_seconds")
    val meditationSeconds: Long? = null,
    @ColumnInfo(name = "weather_condition")
    val weatherCondition: String? = null,
    @ColumnInfo(name = "weather_temperature")
    val weatherTemperature: Double? = null,
    @ColumnInfo(name = "weather_humidity")
    val weatherHumidity: Double? = null,
    @ColumnInfo(name = "weather_wind_speed")
    val weatherWindSpeed: Double? = null,
)
```

Order matters — column declaration order MUST match the migration ALTER order.

- [ ] **Step 4: Bump DB version + add migration**

In `PilgrimDatabase.kt`:

```kotlin
@Database(
    entities = [...],
    version = 6,  // was 5
    ...
)
abstract class PilgrimDatabase : RoomDatabase() {
    companion object {
        // ... MIGRATION_2_3, _3_4, _4_5 …

        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Room's RoomOpenHelper auto-wraps in transaction;
                // don't add manual one. ALTER ADD COLUMN is O(1) on
                // SQLite — appends nullable cols without row scan.
                db.execSQL("ALTER TABLE `walks` ADD COLUMN `weather_condition` TEXT")
                db.execSQL("ALTER TABLE `walks` ADD COLUMN `weather_temperature` REAL")
                db.execSQL("ALTER TABLE `walks` ADD COLUMN `weather_humidity` REAL")
                db.execSQL("ALTER TABLE `walks` ADD COLUMN `weather_wind_speed` REAL")
            }
        }
    }
}
```

In `DatabaseModule.kt` `addMigrations(...)` chain, append `PilgrimDatabase.MIGRATION_5_6`.

- [ ] **Step 5: Generate schema 6.json**

```bash
./gradlew :app:assembleDebug
ls app/schemas/org.walktalkmeditate.pilgrim.data.PilgrimDatabase/6.json
```

Confirm 6.json exists + walks columns include all 4 new entries with correct affinity (TEXT / REAL).

- [ ] **Step 6: Run test — PASS**

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/data/entity/Walk.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/data/PilgrimDatabase.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/di/DatabaseModule.kt \
        app/schemas/org.walktalkmeditate.pilgrim.data.PilgrimDatabase/6.json \
        app/src/test/java/org/walktalkmeditate/pilgrim/data/PilgrimDatabaseMigrationTest.kt
git commit -m "feat(walks): add weather columns + MIGRATION_5_6 (Stage 12-A)"
```

---

### Task 2: WalkDao.updateWeather

**Files:**
- Modify: `app/src/main/java/.../data/dao/WalkDao.kt`
- Test: `app/src/test/java/.../data/dao/WalkDaoWeatherTest.kt` (new)

- [ ] **Step 1: Write failing test**

```kotlin
@Test
fun updateWeather_writesAllFields() = runTest {
    val id = walkDao.insert(Walk(startTimestamp = 1_000L))
    walkDao.updateWeather(
        id = id, condition = "clear", temperature = 18.5,
        humidity = 0.62, windSpeed = 3.1,
    )
    val updated = walkDao.getById(id)!!
    assertEquals("clear", updated.weatherCondition)
    assertEquals(18.5, updated.weatherTemperature!!, 0.0001)
    assertEquals(0.62, updated.weatherHumidity!!, 0.0001)
    assertEquals(3.1, updated.weatherWindSpeed!!, 0.0001)
}

@Test
fun updateWeather_supportsNullableHumidityAndWindSpeed() = runTest {
    val id = walkDao.insert(Walk(startTimestamp = 1_000L))
    walkDao.updateWeather(
        id = id, condition = "fog", temperature = 5.0,
        humidity = null, windSpeed = null,
    )
    val updated = walkDao.getById(id)!!
    assertEquals("fog", updated.weatherCondition)
    assertNull(updated.weatherHumidity)
    assertNull(updated.weatherWindSpeed)
}
```

- [ ] **Step 2: Run — FAIL** (`updateWeather` unresolved).

- [ ] **Step 3: Add DAO method**

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

- [ ] **Step 4: Run — PASS**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/data/dao/WalkDao.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/data/dao/WalkDaoWeatherTest.kt
git commit -m "feat(walks): WalkDao.updateWeather for cache cols (Stage 12-A)"
```

---

### Task 3: WeatherCondition enum + strings + drawables

**Files:**
- Create: `app/src/main/java/.../data/weather/WeatherCondition.kt`
- Create: `app/src/main/res/drawable/ic_weather_*.xml` × 10
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/.../data/weather/WeatherConditionTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
class WeatherConditionTest {
    @Test
    fun fromRawValue_resolvesAll10Cases() {
        assertEquals(WeatherCondition.CLEAR, WeatherCondition.fromRawValue("clear"))
        assertEquals(WeatherCondition.PARTLY_CLOUDY, WeatherCondition.fromRawValue("partlyCloudy"))
        assertEquals(WeatherCondition.OVERCAST, WeatherCondition.fromRawValue("overcast"))
        assertEquals(WeatherCondition.LIGHT_RAIN, WeatherCondition.fromRawValue("lightRain"))
        assertEquals(WeatherCondition.HEAVY_RAIN, WeatherCondition.fromRawValue("heavyRain"))
        assertEquals(WeatherCondition.THUNDERSTORM, WeatherCondition.fromRawValue("thunderstorm"))
        assertEquals(WeatherCondition.SNOW, WeatherCondition.fromRawValue("snow"))
        assertEquals(WeatherCondition.FOG, WeatherCondition.fromRawValue("fog"))
        assertEquals(WeatherCondition.WIND, WeatherCondition.fromRawValue("wind"))
        assertEquals(WeatherCondition.HAZE, WeatherCondition.fromRawValue("haze"))
    }

    @Test
    fun fromRawValue_unknownReturnsNull() {
        assertNull(WeatherCondition.fromRawValue("rainOfFrogs"))
        assertNull(WeatherCondition.fromRawValue(null))
    }
}
```

- [ ] **Step 2: Run — FAIL**

- [ ] **Step 3: Create enum + strings + drawables**

```kotlin
// data/weather/WeatherCondition.kt
enum class WeatherCondition(
    val rawValue: String,
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int,
) {
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
```

Strings (append to `values/strings.xml`):
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

Drawables: 10 vector XMLs from Material Symbols. Use 24dp viewport. Tint via `android:tint="?attr/colorOnSurface"` so theme color flows through. List of resources to source:
- `ic_weather_clear.xml` ← Material `sunny`
- `ic_weather_partly_cloudy.xml` ← `partly_cloudy_day`
- `ic_weather_overcast.xml` ← `cloud`
- `ic_weather_light_rain.xml` ← `rainy_light`
- `ic_weather_heavy_rain.xml` ← `rainy_heavy`
- `ic_weather_thunderstorm.xml` ← `thunderstorm`
- `ic_weather_snow.xml` ← `weather_snowy`
- `ic_weather_fog.xml` ← `foggy`
- `ic_weather_wind.xml` ← `air`
- `ic_weather_haze.xml` ← `mist`

Sample vector XML format (one example, the rest follow same shape):
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24"
    android:tint="?attr/colorOnSurface">
    <path android:fillColor="@android:color/white" android:pathData="..." />
</vector>
```

Source SVG path data from https://fonts.google.com/icons?icon.set=Material+Symbols (filled style, 24px).

- [ ] **Step 4: Run — PASS**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/data/weather/WeatherCondition.kt \
        app/src/main/res/values/strings.xml \
        app/src/main/res/drawable/ic_weather_*.xml \
        app/src/test/java/org/walktalkmeditate/pilgrim/data/weather/WeatherConditionTest.kt
git commit -m "feat(weather): WeatherCondition enum + strings + drawables (Stage 12-A)"
```

---

### Task 4: WeatherSnapshot data class

**Files:**
- Create: `app/src/main/java/.../data/weather/WeatherSnapshot.kt`

Trivial; no dedicated test (covered by Task 5 OpenMeteoClient tests).

- [ ] **Step 1: Skip — model exercised via Task 5.**

- [ ] **Step 2: Skip.**

- [ ] **Step 3: Create class**

```kotlin
// data/weather/WeatherSnapshot.kt
data class WeatherSnapshot(
    val condition: WeatherCondition,
    val temperatureCelsius: Double,
    val humidityFraction: Double?,        // 0.0–1.0; null if API omitted
    val windSpeedMps: Double?,            // m/s; null if API omitted
)
```

- [ ] **Step 4: Skip — exercised in Task 5.**

- [ ] **Step 5: Bundle commit with Task 5.**

---

### Task 5: OpenMeteoClient with MockWebServer tests

**Files:**
- Create: `app/src/main/java/.../data/weather/OpenMeteoClient.kt`
- Modify: `app/src/main/java/.../di/NetworkModule.kt` (add `@WeatherHttpClient` qualifier + binding)
- Modify: `app/proguard-rules.pro` (verify existing kotlinx-serialization keep rules cover the new package)
- Test: `app/src/test/java/.../data/weather/OpenMeteoClientTest.kt`

- [ ] **Step 1: Write 8 failing tests**

```kotlin
class OpenMeteoClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OpenMeteoClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val okHttp = OkHttpClient.Builder()
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
        val json = Json { ignoreUnknownKeys = true }
        client = TestableOpenMeteoClient(okHttp, json, baseUrl = server.url("/").toString())
    }

    @After
    fun tearDown() { server.shutdown() }

    @Test
    fun mapsCleanCodes() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"current":{"temperature_2m":18.0,"relative_humidity_2m":50.0,"weather_code":0,"wind_speed_10m":2.0}}"""
        ))
        assertEquals(WeatherCondition.CLEAR, client.fetchCurrent(0.0, 0.0)?.condition)
    }

    @Test
    fun wmoCode1MapsToClearPerIosMostlyClear() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"current":{"temperature_2m":18.0,"weather_code":1,"wind_speed_10m":2.0}}"""
        ))
        assertEquals(WeatherCondition.CLEAR, client.fetchCurrent(0.0, 0.0)?.condition)
    }

    @Test
    fun windSpeedOverridesCondition() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"current":{"temperature_2m":15.0,"weather_code":0,"wind_speed_10m":12.0}}"""
        ))
        assertEquals(WeatherCondition.WIND, client.fetchCurrent(0.0, 0.0)?.condition)
    }

    @Test
    fun rainWindRuleAppliesUniformlyToAllRainCodes() = runTest {
        // wind > 5 m/s → HEAVY_RAIN for all rain codes 61/63/65/80/81/82
        listOf(61, 63, 65, 80, 81, 82).forEach { code ->
            server.enqueue(MockResponse().setBody(
                """{"current":{"temperature_2m":15.0,"weather_code":$code,"wind_speed_10m":6.0}}"""
            ))
            assertEquals(
                "code $code at wind 6 should be HEAVY_RAIN",
                WeatherCondition.HEAVY_RAIN, client.fetchCurrent(0.0, 0.0)?.condition,
            )
        }
        // wind < 5 m/s → LIGHT_RAIN
        listOf(61, 63, 65, 80, 81, 82).forEach { code ->
            server.enqueue(MockResponse().setBody(
                """{"current":{"temperature_2m":15.0,"weather_code":$code,"wind_speed_10m":4.0}}"""
            ))
            assertEquals(
                "code $code at wind 4 should be LIGHT_RAIN",
                WeatherCondition.LIGHT_RAIN, client.fetchCurrent(0.0, 0.0)?.condition,
            )
        }
    }

    @Test
    fun drizzleSkipsWindCheck() = runTest {
        listOf(51, 53, 55).forEach { code ->
            server.enqueue(MockResponse().setBody(
                """{"current":{"temperature_2m":15.0,"weather_code":$code,"wind_speed_10m":6.0}}"""
            ))
            assertEquals(
                "drizzle code $code never wind-checked",
                WeatherCondition.LIGHT_RAIN, client.fetchCurrent(0.0, 0.0)?.condition,
            )
        }
    }

    @Test
    fun unknownCodeMapsToClear() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"current":{"temperature_2m":15.0,"weather_code":999,"wind_speed_10m":2.0}}"""
        ))
        assertEquals(WeatherCondition.CLEAR, client.fetchCurrent(0.0, 0.0)?.condition)
    }

    @Test
    fun nanLatitudeReturnsNullWithoutNetworkCall() = runTest {
        val before = server.requestCount
        assertNull(client.fetchCurrent(Double.NaN, 0.0))
        assertEquals(before, server.requestCount) // no request issued
    }

    @Test
    fun nullableHumidityAndWindSpeedPropagate() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"current":{"temperature_2m":18.0,"weather_code":0}}"""
        ))
        val snapshot = client.fetchCurrent(0.0, 0.0)
        assertNull(snapshot?.humidityFraction)
        assertNull(snapshot?.windSpeedMps)
        assertEquals(0.0, /* not relevant for this test */ snapshot?.temperatureCelsius!!, 0.001)
    }

    @Test
    fun networkFailureReturnsNull() = runTest {
        server.shutdown()
        assertNull(client.fetchCurrent(0.0, 0.0))
    }
}
```

The TestableOpenMeteoClient takes a configurable `baseUrl` so tests can point at MockWebServer. Production singleton uses the hard-coded Open-Meteo URL.

- [ ] **Step 2: Run — FAIL** (class doesn't exist).

- [ ] **Step 3: Implement**

```kotlin
// data/weather/OpenMeteoClient.kt
@Singleton
class OpenMeteoClient @Inject constructor(
    @WeatherHttpClient private val client: OkHttpClient,
    private val json: Json,
) {
    suspend fun fetchCurrent(latitude: Double, longitude: Double): WeatherSnapshot? =
        fetchInternal(BASE_URL, latitude, longitude)

    internal suspend fun fetchInternal(
        baseUrl: String,
        latitude: Double,
        longitude: Double,
    ): WeatherSnapshot? = withContext(Dispatchers.IO) {
        if (!latitude.isFinite() || !longitude.isFinite()) return@withContext null
        try {
            val url = "${baseUrl}v1/forecast" +
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
            val condition = mapWmoCode(current.weatherCode, windSpeedMs)
            WeatherSnapshot(
                condition = condition,
                temperatureCelsius = current.temperature2m ?: return@withContext null,
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

    private fun mapWmoCode(code: Int?, windSpeedMs: Double): WeatherCondition {
        if (windSpeedMs > 10.0) return WeatherCondition.WIND
        return when (code) {
            0, 1 -> WeatherCondition.CLEAR
            2 -> WeatherCondition.PARTLY_CLOUDY
            3 -> WeatherCondition.OVERCAST
            45, 48 -> WeatherCondition.FOG
            51, 53, 55, 56, 57 -> WeatherCondition.LIGHT_RAIN
            61, 63, 65, 80, 81, 82 ->
                if (windSpeedMs > 5.0) WeatherCondition.HEAVY_RAIN
                else WeatherCondition.LIGHT_RAIN
            66, 67 -> WeatherCondition.SNOW
            71, 73, 75, 77, 85, 86 -> WeatherCondition.SNOW
            95, 96, 99 -> WeatherCondition.THUNDERSTORM
            else -> WeatherCondition.CLEAR
        }
    }

    private companion object {
        const val TAG = "OpenMeteo"
        const val BASE_URL = "https://api.open-meteo.com/"
    }
}

@Serializable
internal data class OpenMeteoResponse(val current: Current? = null) {
    @Serializable
    data class Current(
        @SerialName("temperature_2m") val temperature2m: Double? = null,
        @SerialName("relative_humidity_2m") val relativeHumidity2m: Double? = null,
        @SerialName("weather_code") val weatherCode: Int? = null,
        @SerialName("wind_speed_10m") val windSpeed10m: Double? = null,
    )
}
```

For testability, expose `internal suspend fun fetchInternal(baseUrl, lat, lng)` so the test can pass `server.url("/").toString()` while production uses the hard-coded BASE_URL via the public `fetchCurrent`.

In `NetworkModule.kt` add:
```kotlin
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class WeatherHttpClient

@Provides
@Singleton
@WeatherHttpClient
fun provideWeatherHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .callTimeout(10, TimeUnit.SECONDS)
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build()
```

Verify ProGuard kotlinx-serialization rules cover the new `data.weather` package — they should already be wildcarded per Stage 10-HI keep rules.

- [ ] **Step 4: Run — PASS**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/data/weather/OpenMeteoClient.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/data/weather/WeatherSnapshot.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/di/NetworkModule.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/data/weather/OpenMeteoClientTest.kt
git commit -m "feat(weather): OpenMeteoClient with iOS-faithful WMO mapping (Stage 12-A)"
```

---

### Task 6: WalkRepository.updateWeather

**Files:**
- Modify: `app/src/main/java/.../data/WalkRepository.kt`
- Modify: existing `WalkRepositoryTest.kt` (or create)

- [ ] **Step 1: Write failing test**

```kotlin
@Test
fun updateWeather_persistsSnapshot() = runTest {
    val walkId = walkDao.insert(Walk(startTimestamp = 1_000L))
    val snapshot = WeatherSnapshot(
        condition = WeatherCondition.LIGHT_RAIN,
        temperatureCelsius = 12.5,
        humidityFraction = 0.78,
        windSpeedMps = 4.2,
    )
    walkRepository.updateWeather(walkId, snapshot)
    val walk = walkDao.getById(walkId)!!
    assertEquals("lightRain", walk.weatherCondition)
    assertEquals(12.5, walk.weatherTemperature!!, 0.0001)
    assertEquals(0.78, walk.weatherHumidity!!, 0.0001)
    assertEquals(4.2, walk.weatherWindSpeed!!, 0.0001)
}
```

- [ ] **Step 2: Run — FAIL**

- [ ] **Step 3: Add method**

```kotlin
suspend fun updateWeather(walkId: Long, snapshot: WeatherSnapshot) {
    walkDao.updateWeather(
        id = walkId,
        condition = snapshot.condition.rawValue,
        temperature = snapshot.temperatureCelsius,
        humidity = snapshot.humidityFraction,
        windSpeed = snapshot.windSpeedMps,
    )
}
```

- [ ] **Step 4: Run — PASS**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/data/WalkRepository.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/data/WalkRepositoryTest.kt
git commit -m "feat(walks): WalkRepository.updateWeather (Stage 12-A)"
```

---

### Task 7: WalkViewModel weather job + retry policy + cancel seams

**Files:**
- Modify: `app/src/main/java/.../ui/walk/WalkViewModel.kt`
- Modify: `app/src/test/java/.../ui/walk/WalkViewModelTest.kt`

- [ ] **Step 1: Investigate existing structure first**

Read `WalkViewModel.kt` end-to-end. Find:
- Where walk-start / `WalkAction.Start` is dispatched. Hook `weatherJob` launch there.
- Where `discardWalk()` is implemented. Add `weatherJob?.cancel()` at top.
- Where `WalkState.Finished` arrives. Add `weatherJob?.cancel()` in that observer.
- What dependencies the VM already has — `WalkRepository`? add `OpenMeteoClient`. Already has location source for current GPS? confirm `currentLocation` StateFlow exists.

- [ ] **Step 2: Write failing tests**

```kotlin
@Test
fun weatherJobScheduling() = runTest {
    val fakeClient = FakeOpenMeteoClient(snapshot = stubSnapshot)
    val vm = newViewModel(weatherClient = fakeClient)
    val walkId = vm.startWalk()  // however the existing test seam fires walk-start
    runCurrent()
    advanceTimeBy(1_999L)
    assertEquals(0, fakeClient.callCount)
    advanceTimeBy(2L)  // cross +2s
    assertEquals(1, fakeClient.callCount)
    // First call returned successfully; no retry expected
    advanceTimeBy(15_000L)
    assertEquals(1, fakeClient.callCount)
}

@Test
fun weatherJobRetriesOnceAfterNullSnapshot() = runTest {
    val fakeClient = FakeOpenMeteoClient(snapshot = null)
    val vm = newViewModel(weatherClient = fakeClient)
    vm.startWalk()
    advanceTimeBy(2_001L)
    assertEquals(1, fakeClient.callCount)
    advanceTimeBy(10_000L)
    assertEquals(2, fakeClient.callCount)
    // Both attempts returned null; no third attempt
    advanceTimeBy(20_000L)
    assertEquals(2, fakeClient.callCount)
}

@Test
fun weatherJobCancelsOnDiscard() = runTest {
    val gate = CompletableDeferred<WeatherSnapshot?>()
    val fakeClient = FakeGatedOpenMeteoClient(gate)
    val vm = newViewModel(weatherClient = fakeClient)
    val walkId = vm.startWalk()
    advanceTimeBy(2_001L)
    assertEquals(1, fakeClient.callCount)
    vm.discardWalk()
    assertTrue(vm.weatherJob?.isCancelled == true)  // expose visible-for-test
    gate.complete(stubSnapshot)
    runCurrent()
    // No write should have happened post-cancel
    assertNull(walkDao.getById(walkId)?.weatherCondition)
}

@Test
fun weatherJobCancelsOnFinalize() = runTest { /* mirror discard test */ }
```

(`weatherJob` exposure is implementation detail — alternative: assert no further writes by counting `walkRepository.updateWeather` invocations through a counting fake.)

- [ ] **Step 3: Implement**

```kotlin
@HiltViewModel
class WalkViewModel @Inject constructor(
    // … existing deps …
    private val weatherClient: OpenMeteoClient,
    private val walkRepository: WalkRepository,
) : ViewModel() {

    private var weatherJob: Job? = null

    fun startWalk(...): Long {
        val id = /* existing walk-start code */
        scheduleWeatherFetch(id)
        return id
    }

    private fun scheduleWeatherFetch(walkId: Long) {
        weatherJob?.cancel()
        weatherJob = viewModelScope.launch {
            delay(2_000L)
            if (!fetchAndPersistWeather(walkId)) {
                delay(10_000L)
                fetchAndPersistWeather(walkId)
            }
        }
    }

    private suspend fun fetchAndPersistWeather(walkId: Long): Boolean {
        val location = currentLocation.value ?: return false
        val snapshot = weatherClient.fetchCurrent(location.latitude, location.longitude)
            ?: return false
        walkRepository.updateWeather(walkId, snapshot)
        return true
    }

    fun discardWalk() {
        weatherJob?.cancel()
        // … existing discard code …
    }

    // Wherever WalkState.Finished is observed:
    private fun onWalkFinalized() {
        weatherJob?.cancel()
        // … existing finalize code …
    }
}
```

If discardWalk and finalize seams are in different code paths from the existing VM (e.g., effect handlers), wire `weatherJob.cancel()` from the relevant terminal-state effect.

- [ ] **Step 4: Run — PASS**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkViewModel.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkViewModelTest.kt
git commit -m "feat(walks): WalkViewModel weather fetch + retry + cancel seams (Stage 12-A)"
```

---

### Task 8: WalkSummaryScreen weather line UI

**Files:**
- Modify: `app/src/main/java/.../ui/walk/WalkSummaryScreen.kt`
- Test: `app/src/test/java/.../ui/walk/WalkSummaryWeatherLineTest.kt`

- [ ] **Step 1: Write failing Compose UI tests**

```kotlin
@Test
fun rendersWeatherLineWithLabelAndCelsius() {
    composeRule.setContent {
        PilgrimTheme {
            WalkSummaryWeatherLine(
                condition = WeatherCondition.LIGHT_RAIN,
                temperatureCelsius = 12.5,
                imperial = false,
            )
        }
    }
    composeRule.onNodeWithText("Light rain, 13°C").assertIsDisplayed()
}

@Test
fun rendersImperialWhenRequested() {
    composeRule.setContent {
        PilgrimTheme {
            WalkSummaryWeatherLine(
                condition = WeatherCondition.CLEAR,
                temperatureCelsius = 0.0,
                imperial = true,
            )
        }
    }
    composeRule.onNodeWithText("Clear, 32°F").assertIsDisplayed()
}
```

- [ ] **Step 2: Run — FAIL**

- [ ] **Step 3: Add weather Row + helper**

```kotlin
@Composable
fun WalkSummaryWeatherLine(
    condition: WeatherCondition,
    temperatureCelsius: Double,
    imperial: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(condition.iconRes),
            contentDescription = null,
            tint = pilgrimColors.fog,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = "${stringResource(condition.labelRes)}, ${formatTemperature(temperatureCelsius, imperial)}",
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
        )
    }
}

private fun formatTemperature(celsius: Double, imperial: Boolean): String {
    val rounded = if (imperial) celsius * 9.0 / 5.0 + 32.0 else celsius
    return String.format(Locale.US, "%.0f°%s", rounded, if (imperial) "F" else "C")
}
```

In `WalkSummaryScreen.kt` body, render the weather line below the existing stats block when `walk.weatherCondition` resolves to a known enum:

```kotlin
walk.weatherCondition?.let { conditionRaw ->
    val condition = WeatherCondition.fromRawValue(conditionRaw) ?: return@let
    val temperature = walk.weatherTemperature ?: return@let
    val imperial = distanceUnits == UnitSystem.Imperial
    WalkSummaryWeatherLine(
        condition = condition,
        temperatureCelsius = temperature,
        imperial = imperial,
        modifier = Modifier.padding(top = 8.dp),
    )
}
```

- [ ] **Step 4: Run — PASS**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryScreen.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryWeatherLineTest.kt
git commit -m "feat(walks): WalkSummaryScreen weather line (Stage 12-A)"
```

---

### Task 9: FusedLocationSource accuracy gate

**Files:**
- Modify: `app/src/main/java/.../location/FusedLocationSource.kt`
- Test: `app/src/test/java/.../location/FusedLocationSourceTest.kt`

- [ ] **Step 1: Investigate existing FusedLocationSource shape**

Read the file end-to-end. The plan assumes `locationFlow()` returns `callbackFlow { ... }`. If the actual shape differs, adapt.

- [ ] **Step 2: Write failing tests**

```kotlin
@Test
fun firstSampleAcceptedRegardlessOfAccuracy() = runTest {
    val source = FusedLocationSource(/* deps with fake FLP */)
    val results = mutableListOf<LocationPoint>()
    val collector = launch { source.locationFlow().toList(results) }
    fakeFlp.emit(point(lat=0.0, lng=0.0, accuracy=200f))
    runCurrent()
    assertEquals(1, results.size)
    collector.cancel()
}

@Test
fun rejectedAccuracyDroppedSilently() = runTest { /* second emission accuracy=120 → not in results */ }

@Test
fun acceptableAccuracyPasses() = runTest { /* second emission accuracy=15 → results.size == 2 */ }

@Test
fun borderlineEqualsDesiredPasses() = runTest { /* accuracy=20 → in results */ }

@Test
fun borderlineEqualsHardCeilingFails() = runTest { /* accuracy=100 → NOT in results (strict <) */ }

@Test
fun nullAccuracyRejectedExceptForFirstSample() = runTest {
    /* first sample accuracy=null → emitted (anchor); second accuracy=null → not emitted */
}

@Test
fun newCollectionResetsAnchor() = runTest {
    val source = FusedLocationSource(/* singleton */)
    var results1 = mutableListOf<LocationPoint>()
    val coll1 = launch { source.locationFlow().toList(results1) }
    fakeFlp.emit(point(accuracy=200f))  // anchored
    runCurrent()
    coll1.cancel()
    var results2 = mutableListOf<LocationPoint>()
    val coll2 = launch { source.locationFlow().toList(results2) }
    fakeFlp.emit(point(accuracy=200f))  // must anchor again, NOT reject
    runCurrent()
    assertEquals(1, results2.size)
    coll2.cancel()
}
```

- [ ] **Step 3: Implement gate**

In `FusedLocationSource.locationFlow()`:

```kotlin
fun locationFlow(): Flow<LocationPoint> = callbackFlow {
    val hasEmitted = AtomicBoolean(false)  // per-collection (per-walk)

    val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (location in result.locations) {
                val point = location.toLocationPoint() ?: continue
                val isFirst = !hasEmitted.get()
                if (!isFirst && !meetsAccuracyGate(point)) continue
                hasEmitted.set(true)
                trySend(point)
            }
        }
    }
    /* … existing requestLocationUpdates wiring … */
    awaitClose { fusedLocationProviderClient.removeLocationUpdates(callback) }
}

private fun meetsAccuracyGate(point: LocationPoint): Boolean {
    val accuracy = point.horizontalAccuracyMeters ?: return false  // null = REJECT (defensive)
    return accuracy < HARD_CEILING_METERS && accuracy <= DESIRED_ACCURACY_METERS
}

private companion object {
    const val HARD_CEILING_METERS = 100f
    const val DESIRED_ACCURACY_METERS = 20f
}
```

- [ ] **Step 4: Run — PASS**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/location/FusedLocationSource.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/location/FusedLocationSourceTest.kt
git commit -m "feat(walks): distance-accuracy filter at FusedLocationSource (Stage 12-B)"
```

---

### Task 10: HapticsModule provideVibrator

**Files:**
- Create: `app/src/main/java/.../di/HapticsModule.kt`

- [ ] **Step 1: Skip — exercised in Task 11.**

- [ ] **Step 2: Skip.**

- [ ] **Step 3: Create module**

```kotlin
// di/HapticsModule.kt
@Module
@InstallIn(SingletonComponent::class)
object HapticsModule {
    @Provides
    @Singleton
    fun provideVibrator(@ApplicationContext context: Context): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
}
```

- [ ] **Step 4: Skip — verified by Task 11 tests.**

- [ ] **Step 5: Bundle commit with Task 11.**

---

### Task 11: BellPlaying.play(scale, withHaptic) + BellPlayer impl

**Files:**
- Modify: `app/src/main/java/.../audio/BellPlaying.kt`
- Modify: `app/src/main/java/.../audio/BellPlayer.kt`
- Modify: `app/src/test/java/.../audio/BellPlayerTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
@Test
fun play_firesPrimitiveOnApi30PlusWhenSupported() { /* Robolectric @Config sdk=30 */ }
@Test
fun play_fallsBackToWaveformWhenPrimitiveUnsupported() { /* sdk=30 + areAllPrimitivesSupported=false */ }
@Test
fun play_firesWaveformOnApi28_29() { /* sdk=28 */ }
@Test
fun play_skipsHapticWhenBellHapticPrefDisabled() { /* bellHapticEnabled=false, withHaptic=true → no vibrate */ }
@Test
fun play_skipsHapticWhenWithHapticFalse() { /* bellHapticEnabled=true, withHaptic=false → no vibrate */ }
@Test
fun play_stillPlaysBellRegardlessOfHapticDecisions() { /* 4 combinations × audio still fires */ }
@Test
fun play_hapticFiresAfterBellAudio() { /* record sequence; audio.start() before vibrator.vibrate() */ }
```

Use Robolectric ShadowVibrator to capture composition + waveform calls.

- [ ] **Step 2: Run — FAIL**

- [ ] **Step 3: Update interface + impl**

```kotlin
// BellPlaying.kt
interface BellPlaying {
    fun play()
    fun play(scale: Float) { play() }
    /**
     * Fire bell + (optional) haptic. iOS-faithful default: haptic ON,
     * gated internally by `bellHapticEnabled` preference. Sites that
     * don't want haptic explicitly pass `withHaptic = false`.
     */
    fun play(scale: Float = 1.0f, withHaptic: Boolean = true) {
        play(scale)
    }
}
```

(Verify Kotlin doesn't ambiguity-fail with both `play(scale)` and `play(scale, withHaptic)` overloads. May need to remove the bare 1-arg overload.)

```kotlin
// BellPlayer.kt — add `vibrator: Vibrator` to constructor injection
override fun play(scale: Float, withHaptic: Boolean) {
    playInternal(scale = scale)
    // iOS fires haptic AFTER bell audio (BellPlayer.swift:26,30).
    if (withHaptic && soundsPreferences.bellHapticEnabled.value) {
        fireMediumImpact()
    }
}

private fun fireMediumImpact() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        if (vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)) {
            try {
                val composition = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f)
                    .compose()
                vibrator.vibrate(composition)
                return
            } catch (t: Throwable) {
                Log.w(TAG, "primitive composition failed; falling back", t)
            }
        }
        fireWaveformFallback()
    } else {
        fireWaveformFallback()
    }
}

private fun fireWaveformFallback() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator.vibrate(VibrationEffect.createOneShot(30L, VibrationEffect.DEFAULT_AMPLITUDE))
    } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(30L)
    }
}
```

- [ ] **Step 4: Run — PASS**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/audio/BellPlaying.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/audio/BellPlayer.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/di/HapticsModule.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/audio/BellPlayerTest.kt
git commit -m "feat(audio): BellPlayer haptic coupling at player layer (Stage 12-C)"
```

---

### Task 12: Audit + update bell call sites

**Files:**
- Modify: `app/src/main/java/.../audio/MeditationBellObserver.kt` (and any other bell call sites)
- Modify: `app/src/main/java/.../ui/settings/SettingsViewModel.kt` (milestone path — must opt-out of haptic)

- [ ] **Step 1: Grep `bellPlayer.play(` in main src to find all call sites**

```bash
grep -rn "bellPlayer\.play\|BellPlaying" app/src/main/java/
```

- [ ] **Step 2: Audit each call site:**
- `MeditationBellObserver.kt` — meditation start/end bells. Switch to `play(withHaptic = true)` (iOS fires `.medium` haptic at meditation bells).
- Walk-start bell (find caller). Switch to `withHaptic = true`.
- Walk-end bell. Switch to `withHaptic = true`.
- `SettingsViewModel.onMilestoneShown` — currently calls `bellPlayer.play(scale = MILESTONE_BELL_SCALE)`. iOS DOES pair haptic on milestone (verified: `BellPlayer.swift:14`'s `withHaptic: Bool = true` default fires for the `PracticeSummaryHeader.swift:92` caller). Switch to `withHaptic = true` explicitly for iOS parity + audit visibility.

- [ ] **Step 3: Update tests for each affected caller**

Existing tests probably assert vibrator NOT called (because BellPlayer didn't fire haptic before this stage). Now relevant bell paths fire haptic — tests must update assertions.

For each affected caller, write or update one test verifying expected haptic behavior:
- `MeditationBellObserverTest.bell_firesWithHapticAtMeditationStart`
- `SettingsViewModelTest.milestoneBellSkipsHaptic` (the test renamed to verify withHaptic=false propagates)

- [ ] **Step 4: Run full test suite**

```bash
./gradlew :app:testDebugUnitTest
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/audio/MeditationBellObserver.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsViewModel.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/[other-callers].kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/[corresponding-tests].kt
git commit -m "feat(audio): switch bell call sites to haptic-coupled play() (Stage 12-C)"
```

---

### Task 13: Localization stub

**Files:**
- Create: `app/src/main/res/values-fr/strings.xml`
- Modify: `app/src/main/res/values/strings.xml` (append marker)
- Test: `app/src/test/java/.../LocaleResolutionTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocaleResolutionTest {
    @Test
    fun stubFrenchLocaleResolvesToFrValue() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val frConfig = Configuration(context.resources.configuration)
        frConfig.setLocale(Locale.FRANCE)
        val frContext = context.createConfigurationContext(frConfig)
        assertEquals("fr", frContext.getString(R.string.locale_resolution_marker))
    }

    @Test
    fun defaultLocaleResolvesToDefaultValue() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        assertEquals("en", context.getString(R.string.locale_resolution_marker))
    }
}
```

- [ ] **Step 2: Run — FAIL** (`R.string.locale_resolution_marker` unresolved).

- [ ] **Step 3: Add resources**

In `values/strings.xml` append:
```xml
<!-- Stage 12-D: locale-resolution marker. values/ is the English source;
     values-fr/ overrides for fr locale. Verifies the values-XX/ directory
     pattern works. -->
<string name="locale_resolution_marker">en</string>
```

Create `values-fr/strings.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="locale_resolution_marker">fr</string>
</resources>
```

- [ ] **Step 4: Run — PASS**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/values/strings.xml \
        app/src/main/res/values-fr/strings.xml \
        app/src/test/java/org/walktalkmeditate/pilgrim/LocaleResolutionTest.kt
git commit -m "chore(locale): values-fr stub + resolution test (Stage 12-D)"
```

---

### Task 14: Release build smoke + full test suite

**Files:** none (verification only).

- [ ] **Step 1: Run release assembly**

```bash
./gradlew :app:assembleRelease
```

Expected: PASS. No new ProGuard rules required — `kotlinx-serialization` rules from Stage 10-HI cover the new `data.weather` package via wildcards.

- [ ] **Step 2: Full test suite**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: PASS. Pre-existing flaky tests (`PracticeCardTest` / `ExoPlayerSoundscapePlayerTest`) may surface — re-run if so.

- [ ] **Step 3: Skip.**
- [ ] **Step 4: Skip.**
- [ ] **Step 5: No commit needed.**

---

## Self-review checklist (controller, after all tasks done)

- [ ] Spec coverage: every spec section has at least one task implementing it (weather schema → 1; DAO → 2; enum/strings → 3; snapshot → 4; client → 5; repo → 6; VM → 7; UI → 8; distance gate → 9; haptics → 10/11/12; locale → 13).
- [ ] No placeholders in tasks.
- [ ] Type signatures consistent: `WeatherSnapshot(condition, temperatureCelsius, humidityFraction: Double?, windSpeedMps: Double?)`.
- [ ] Migration column order matches Walk.kt entity order (4 weather cols AFTER existing 11 fields including Stage 11 cache cols).
- [ ] Commit messages tagged with stage label (`Stage 12-A` / `12-B` / `12-C` / `12-D`).
- [ ] PR title: `feat(walks+audio+locale): weather + distance-filter + bell-haptic + locale-stub (Stage 12)`.
- [ ] PR description references spec.
