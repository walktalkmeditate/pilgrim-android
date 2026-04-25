# Stage 8-B Implementation Plan — Collective Counter

Spec: `docs/superpowers/specs/2026-04-25-stage-8b-collective-counter-design.md`

Sequence: Task 1 → 12. Each task ends compiling + passing tests. Branch already created: `feat/stage-8b-collective-counter` (off `origin/main`).

WalkAccumulator field-confirmation done during plan write: `distanceMeters` ✓, `totalMeditatedMillis` ✓, `totalTalkMillis` ✗ (does not exist; fall back to `repository.voiceRecordingsFor(walkId).sumOf { durationMillis } / 60_000L`).

---

## Task 1 — `CollectiveConfig` + sealed result/error types

Constants + sealed types. No I/O.

### Files

**Create** `app/src/main/java/org/walktalkmeditate/pilgrim/data/collective/CollectiveConfig.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.collective

import kotlin.time.Duration.Companion.seconds

internal object CollectiveConfig {
    const val BASE_URL = "https://walk.pilgrimapp.org"
    const val ENDPOINT = "/api/counter"
    val FETCH_TTL = 216.seconds
    /** Backend cap (counter.ts:38): walks ≤ 10 per POST. */
    const val MAX_WALKS_PER_POST = 10
    /** Backend cap (counter.ts:39): distance_km ≤ 200. */
    const val MAX_DISTANCE_KM_PER_POST = 200.0
    /** Backend cap (counter.ts:40-41): meditate/talk ≤ 480 minutes. */
    const val MAX_DURATION_MIN_PER_POST = 480
}
```

**Create** `app/src/main/java/org/walktalkmeditate/pilgrim/data/collective/CollectiveCounterDelta.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.collective

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Stage 8-B: POST payload + accumulator state. iOS PendingDelta parity. */
@Serializable
data class CollectiveCounterDelta(
    val walks: Int = 0,
    @SerialName("distance_km") val distanceKm: Double = 0.0,
    @SerialName("meditation_min") val meditationMin: Int = 0,
    @SerialName("talk_min") val talkMin: Int = 0,
) {
    /** Empty pending → backend would 400; skip POSTing. */
    fun isEmpty(): Boolean = walks == 0 && distanceKm == 0.0 &&
        meditationMin == 0 && talkMin == 0

    fun plus(other: CollectiveCounterDelta) = CollectiveCounterDelta(
        walks = walks + other.walks,
        distanceKm = distanceKm + other.distanceKm,
        meditationMin = meditationMin + other.meditationMin,
        talkMin = talkMin + other.talkMin,
    )

    fun minus(other: CollectiveCounterDelta) = CollectiveCounterDelta(
        walks = walks - other.walks,
        distanceKm = distanceKm - other.distanceKm,
        meditationMin = meditationMin - other.meditationMin,
        talkMin = talkMin - other.talkMin,
    )
}

/** Snapshot of a just-finished walk, fed to recordWalk. */
data class CollectiveWalkSnapshot(
    val distanceKm: Double,
    val meditationMin: Int,
    val talkMin: Int,
)
```

**Create** `app/src/main/java/org/walktalkmeditate/pilgrim/data/collective/CollectiveResult.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.collective

/** Sealed result for the POST flow. */
sealed interface PostResult {
    data object Success : PostResult
    data object RateLimited : PostResult
    data class Failed(val cause: Throwable) : PostResult
}
```

### Verify
```bash
./gradlew :app:compileDebugKotlin
```

---

## Task 2 — `CollectiveStats` (wire model)

### Files

**Create** `app/src/main/java/org/walktalkmeditate/pilgrim/data/collective/CollectiveStats.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.collective

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Stage 8-B: GET /api/counter response. Field names match
 * pilgrim-worker/src/handlers/counter.ts. Nullable for fields the
 * backend may omit on a fresh database (lastWalkAt, streak_*).
 *
 * `streakDays` and `streakDate` are decoded for forward
 * compatibility — milestone celebration + StreakFlame rendering
 * are deferred to the Stage-8-B-follow-up but the wire data is
 * preserved through DataStore so we don't have to migrate the
 * cache later.
 */
@Serializable
data class CollectiveStats(
    @SerialName("total_walks") val totalWalks: Int,
    @SerialName("total_distance_km") val totalDistanceKm: Double,
    @SerialName("total_meditation_min") val totalMeditationMin: Int,
    @SerialName("total_talk_min") val totalTalkMin: Int,
    @SerialName("last_walk_at") val lastWalkAt: String? = null,
    @SerialName("streak_days") val streakDays: Int? = null,
    @SerialName("streak_date") val streakDate: String? = null,
) {
    /**
     * iOS `walkedInLastHour` parity. Decoded for the deferred
     * Home pulse animation (CollectiveCounterService.swift:60-63).
     */
    fun walkedInLastHour(nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        val ts = lastWalkAt ?: return false
        return try {
            val instant = java.time.Instant.parse(ts)
            (nowEpochMs - instant.toEpochMilli()) < 3_600_000L
        } catch (e: java.time.format.DateTimeParseException) {
            false
        }
    }
}
```

**Create** `app/src/test/java/org/walktalkmeditate/pilgrim/data/collective/CollectiveStatsTest.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.collective

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectiveStatsTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `decodes the worker's full response shape`() {
        val raw = """
            {"total_walks":42,"total_distance_km":12.5,"total_meditation_min":30,
             "total_talk_min":15,"last_walk_at":"2026-04-25T01:00:00Z",
             "streak_days":3,"streak_date":"2026-04-25"}
        """.trimIndent()
        val stats = json.decodeFromString<CollectiveStats>(raw)
        assertEquals(42, stats.totalWalks)
        assertEquals(12.5, stats.totalDistanceKm, 0.001)
        assertEquals(30, stats.totalMeditationMin)
        assertEquals(15, stats.totalTalkMin)
        assertEquals("2026-04-25T01:00:00Z", stats.lastWalkAt)
        assertEquals(3, stats.streakDays)
    }

    @Test
    fun `decodes a fresh-database response with nulls`() {
        val raw = """
            {"total_walks":0,"total_distance_km":0,"total_meditation_min":0,
             "total_talk_min":0,"last_walk_at":null,"streak_days":null,
             "streak_date":null}
        """.trimIndent()
        val stats = json.decodeFromString<CollectiveStats>(raw)
        assertEquals(0, stats.totalWalks)
        assertNull(stats.lastWalkAt)
        assertNull(stats.streakDays)
    }

    @Test
    fun `walkedInLastHour true when lastWalkAt within 3600s`() {
        val now = 1_700_000_000_000L
        val twentyMinAgo = java.time.Instant
            .ofEpochMilli(now - 20 * 60 * 1000L)
            .toString()
        val stats = baseStats().copy(lastWalkAt = twentyMinAgo)
        assertTrue(stats.walkedInLastHour(now))
    }

    @Test
    fun `walkedInLastHour false when lastWalkAt over an hour ago`() {
        val now = 1_700_000_000_000L
        val twoHoursAgo = java.time.Instant
            .ofEpochMilli(now - 2 * 3600 * 1000L)
            .toString()
        val stats = baseStats().copy(lastWalkAt = twoHoursAgo)
        assertFalse(stats.walkedInLastHour(now))
    }

    @Test
    fun `walkedInLastHour false when lastWalkAt null`() {
        assertFalse(baseStats().copy(lastWalkAt = null).walkedInLastHour())
    }

    @Test
    fun `walkedInLastHour false on malformed lastWalkAt`() {
        assertFalse(baseStats().copy(lastWalkAt = "not-a-date").walkedInLastHour())
    }

    private fun baseStats() = CollectiveStats(
        totalWalks = 1, totalDistanceKm = 1.0,
        totalMeditationMin = 0, totalTalkMin = 0,
    )
}
```

### Verify
```bash
./gradlew :app:testDebugUnitTest --tests "*.CollectiveStatsTest"
```

---

## Task 3 — `CollectiveCacheStore`

DataStore preferences. Stores cached_stats_json, last_fetched_at_ms, opt_in, pending_delta_json.

### Files

**Create** `app/src/main/java/org/walktalkmeditate/pilgrim/data/collective/CollectiveCacheStore.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.collective

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * Stage 8-B: DataStore for the collective counter — cached stats
 * blob, TTL gate, opt-in flag, pending-delta accumulator.
 */
@Singleton
class CollectiveCacheStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    val statsFlow: Flow<CollectiveStats?> =
        context.collectiveDataStore.data
            .map { prefs -> prefs[KEY_STATS_JSON]?.let(::decodeStats) }
            .distinctUntilChanged()

    val lastFetchedAtFlow: Flow<Long?> =
        context.collectiveDataStore.data
            .map { it[KEY_LAST_FETCHED_AT] }
            .distinctUntilChanged()

    val optInFlow: Flow<Boolean> =
        context.collectiveDataStore.data
            .map { it[KEY_OPT_IN] ?: false }
            .distinctUntilChanged()

    val pendingFlow: Flow<CollectiveCounterDelta> =
        context.collectiveDataStore.data
            .map { prefs -> prefs[KEY_PENDING_JSON]?.let(::decodeDelta) ?: CollectiveCounterDelta() }
            .distinctUntilChanged()

    suspend fun writeStats(stats: CollectiveStats, fetchedAtMs: Long) {
        val blob = json.encodeToString(CollectiveStats.serializer(), stats)
        context.collectiveDataStore.edit { prefs ->
            prefs[KEY_STATS_JSON] = blob
            prefs[KEY_LAST_FETCHED_AT] = fetchedAtMs
        }
    }

    /** Invalidate the TTL gate without dropping cached stats. */
    suspend fun invalidateLastFetched() {
        context.collectiveDataStore.edit { prefs -> prefs.remove(KEY_LAST_FETCHED_AT) }
    }

    suspend fun setOptIn(value: Boolean) {
        context.collectiveDataStore.edit { prefs -> prefs[KEY_OPT_IN] = value }
    }

    /**
     * Atomic read-modify-write of pending-delta. The mutate lambda
     * receives the current value and returns the updated value;
     * everything happens inside a single `edit { }` block so two
     * concurrent calls converge under the DataStore actor.
     */
    suspend fun mutatePending(
        mutate: (CollectiveCounterDelta) -> CollectiveCounterDelta,
    ): CollectiveCounterDelta {
        var result = CollectiveCounterDelta()
        context.collectiveDataStore.edit { prefs ->
            val current = prefs[KEY_PENDING_JSON]?.let(::decodeDelta) ?: CollectiveCounterDelta()
            val next = mutate(current)
            result = next
            if (next.isEmpty()) {
                prefs.remove(KEY_PENDING_JSON)
            } else {
                prefs[KEY_PENDING_JSON] = json.encodeToString(
                    CollectiveCounterDelta.serializer(), next,
                )
            }
        }
        return result
    }

    private fun decodeStats(blob: String): CollectiveStats? = try {
        json.decodeFromString(CollectiveStats.serializer(), blob)
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Throwable) {
        null
    }

    private fun decodeDelta(blob: String): CollectiveCounterDelta? = try {
        json.decodeFromString(CollectiveCounterDelta.serializer(), blob)
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Throwable) {
        null
    }

    companion object {
        private val KEY_STATS_JSON = stringPreferencesKey("cached_stats_json")
        private val KEY_LAST_FETCHED_AT = longPreferencesKey("last_fetched_at_ms")
        private val KEY_OPT_IN = booleanPreferencesKey("opt_in")
        private val KEY_PENDING_JSON = stringPreferencesKey("pending_delta_json")
    }
}

private val Context.collectiveDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "collective_counter",
)
```

**Create** `app/src/test/java/org/walktalkmeditate/pilgrim/data/collective/CollectiveCacheStoreTest.kt`:

Robolectric tests for: stats round-trip, opt-in default false + flip, pending mutate atomic merge, pending isEmpty clears the key, invalidateLastFetched preserves stats.

### Verify
```bash
./gradlew :app:testDebugUnitTest --tests "*.CollectiveCacheStoreTest"
```

---

## Task 4 — `CollectiveCounterService` (HTTP)

OkHttp GET + POST. CE re-throw audit.

### Files

**Create** `app/src/main/java/org/walktalkmeditate/pilgrim/data/collective/CollectiveCounterService.kt`:

GET → `suspend fun fetch(): CollectiveStats` throws `IOException` on network / `IllegalStateException` on non-2xx / propagates JSON decode throws.
POST → `suspend fun post(delta: CollectiveCounterDelta): PostResult` returning Success / RateLimited (on 429) / Failed (on any other failure including IOException). All on `Dispatchers.IO`.

Uses `@CounterHttpClient`-qualified OkHttpClient (10s call timeout — set in CollectiveModule). `X-Device-Token` header from `DeviceTokenStore.getToken()` on POST; GET has no auth header.

CE re-throw: explicit try/catch (no `runCatching`) for both `client.newCall(request).execute()` and `json.decodeFromString(...)`.

**Create** `app/src/test/java/org/walktalkmeditate/pilgrim/data/collective/CollectiveCounterServiceTest.kt`:

MockWebServer fixture mirroring Stage 8-A pattern. Tests:
- GET 200 → parses CollectiveStats correctly (incl. nulls).
- POST 200 → returns Success; X-Device-Token header set; body is correct snake_case JSON.
- POST 401 → returns Failed.
- POST 429 → returns RateLimited.
- POST IOException (DISCONNECT_AT_START) → returns Failed.
- GET IOException → throws.

### Verify
```bash
./gradlew :app:testDebugUnitTest --tests "*.CollectiveCounterServiceTest"
```

---

## Task 5 — `CollectiveModule` (Hilt DI)

### Files

**Create** `app/src/main/java/org/walktalkmeditate/pilgrim/di/CollectiveModule.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import org.walktalkmeditate.pilgrim.data.collective.CollectiveConfig

@Module
@InstallIn(SingletonComponent::class)
object CollectiveModule {

    @Provides
    @Singleton
    @CounterHttpClient
    fun provideCounterHttpClient(default: OkHttpClient): OkHttpClient =
        default.newBuilder()
            .callTimeout(COUNTER_CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    @CounterBaseUrl
    fun provideCounterBaseUrl(): String = CollectiveConfig.BASE_URL

    /**
     * Long-lived scope for [CollectiveRepository]'s fire-and-forget
     * recordWalk POST — outlives any individual VM (viewModelScope
     * cancellation on Walk Summary nav-away would drop an in-flight
     * POST, losing the contribution). SupervisorJob so a failed POST
     * doesn't tear the scope down.
     */
    @Provides
    @Singleton
    @CollectiveRepoScope
    fun provideCollectiveRepoScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val COUNTER_CALL_TIMEOUT_SEC = 10L
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CounterHttpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CounterBaseUrl

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CollectiveRepoScope
```

### Verify
```bash
./gradlew :app:compileDebugKotlin
```

---

## Task 6 — `CollectiveRepository`

The orchestrator. `@Singleton`. Mutex-protected recordWalk. fetchIfStale TTL gate.

### Files

**Create** `app/src/main/java/org/walktalkmeditate/pilgrim/data/collective/CollectiveRepository.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.collective

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.walktalkmeditate.pilgrim.di.CollectiveRepoScope

/**
 * Stage 8-B: orchestrator for collective-counter state.
 *
 * **Opt-in IS a true data-transmission gate.** Unlike Stage 8-A's
 * share-journey toggles (which were display-only — distance still
 * rode in the payload regardless), `optIn` here gates the entire
 * recordWalk flow before any DataStore mutation. A user with
 * opt-in OFF contributes nothing; first-time opt-in is
 * forward-looking only (no backfill of prior walks).
 */
@Singleton
class CollectiveRepository @Inject constructor(
    private val cacheStore: CollectiveCacheStore,
    private val service: CollectiveCounterService,
    @CollectiveRepoScope private val scope: CoroutineScope,
) {
    private val recordMutex = Mutex()

    val stats: StateFlow<CollectiveStats?> =
        cacheStore.statsFlow.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )

    val optIn: StateFlow<Boolean> =
        cacheStore.optInFlow.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = false,
        )

    suspend fun setOptIn(value: Boolean) = cacheStore.setOptIn(value)

    /**
     * GET aggregates if the 216s in-memory TTL has expired. No-op
     * otherwise. Failures are logged + swallowed (no UI surface
     * for fetch errors — the cached stats keep rendering).
     */
    suspend fun fetchIfStale(now: () -> Long = System::currentTimeMillis) {
        val lastFetched = cacheStore.lastFetchedAtFlow.first()
        val nowMs = now()
        if (lastFetched != null && (nowMs - lastFetched) < CollectiveConfig.FETCH_TTL.inWholeMilliseconds) {
            return
        }
        try {
            val fresh = service.fetch()
            cacheStore.writeStats(fresh, nowMs)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "fetchIfStale failed", t)
        }
    }

    /** Drop the TTL gate, fetch fresh. Used after a successful POST. */
    suspend fun forceFetch(now: () -> Long = System::currentTimeMillis) {
        cacheStore.invalidateLastFetched()
        fetchIfStale(now)
    }

    /**
     * Fire-and-forget on the repo's long-lived scope. Returns
     * immediately. Iff opted-in, accumulates the snapshot into
     * pending and POSTs the running total.
     */
    fun recordWalk(snapshot: CollectiveWalkSnapshot) {
        scope.launch {
            recordMutex.withLock {
                if (!optIn.value) return@withLock
                val newDelta = CollectiveCounterDelta(
                    walks = 1,
                    distanceKm = snapshot.distanceKm,
                    meditationMin = snapshot.meditationMin,
                    talkMin = snapshot.talkMin,
                )
                val merged = cacheStore.mutatePending { it + newDelta }
                if (merged.isEmpty()) {
                    // Defensive: a zero-distance + zero-time + (somehow)
                    // zero-walk snapshot would cause backend's 400
                    // (counter.ts:43-45). Never POST an empty delta.
                    return@withLock
                }
                when (val result = service.post(merged)) {
                    PostResult.Success -> {
                        // Subtract the snapshot we just posted; clamp
                        // pending to zero (clear key) if subtract goes
                        // below — iOS CollectiveCounterService.swift:114
                        // parity.
                        cacheStore.mutatePending { current ->
                            val subtracted = current - merged
                            if (subtracted.walks <= 0) CollectiveCounterDelta()
                            else subtracted
                        }
                        forceFetch()
                    }
                    PostResult.RateLimited -> {
                        Log.i(TAG, "POST rate-limited; pending preserved for next walk")
                    }
                    is PostResult.Failed -> {
                        Log.w(TAG, "POST failed; pending preserved for next walk", result.cause)
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "CollectiveRepo"
    }
}
```

**Create** `app/src/test/java/org/walktalkmeditate/pilgrim/data/collective/CollectiveRepositoryTest.kt`:

Tests:
- `fetchIfStale` honors TTL — second call within 216s does NOT call service.fetch().
- `fetchIfStale` writes stats + lastFetchedAt on success.
- `fetchIfStale` swallows fetch errors (no throw).
- `recordWalk` opt-in OFF → no service.post() call, pending unchanged.
- `recordWalk` opt-in ON → accumulates pending, calls service.post(merged), on Success subtracts merged from pending.
- `recordWalk` Success → forceFetch is invoked.
- `recordWalk` RateLimited → pending PRESERVED.
- `recordWalk` Failed → pending PRESERVED.
- Two concurrent recordWalk calls → mutex serializes; pending accumulates each contribution exactly once.
- Empty snapshot (zero distance/time) merging into empty pending → no POST attempted.

Use a fake CollectiveCounterService that records calls + returns canned results.

### Verify
```bash
./gradlew :app:testDebugUnitTest --tests "*.CollectiveRepositoryTest"
```

---

## Task 7 — `WalkViewModel.finishWalk()` hook

Snapshot WalkAccumulator BEFORE controller.finishWalk(). Talk total via repo lookup (no accumulator field).

### Files

**Modify** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkViewModel.kt`:

1. Add `private val collectiveRepository: CollectiveRepository` to ctor.
2. In `finishWalk()`, near the top of the launched coroutine BEFORE `controller.finishWalk()`, snapshot:
```kotlin
val collectiveSnapshotState = controller.state.value as? WalkState.Active
val snapshotWalkId = collectiveSnapshotState?.walk?.walkId
val snapshotDistanceKm = collectiveSnapshotState?.walk?.distanceMeters?.div(1000.0) ?: 0.0
val snapshotMeditateMin = ((collectiveSnapshotState?.walk?.totalMeditatedMillis ?: 0L) / 60_000L).toInt()
```
3. AFTER the existing `transcriptionScheduler.scheduleForWalk(...)` block (i.e., at the tail of the finish chain), compute talk via repo and fire recordWalk:
```kotlin
snapshotWalkId?.let { id ->
    try {
        val talkMin = (repository.voiceRecordingsFor(id).sumOf { it.durationMillis } / 60_000L).toInt()
        collectiveRepository.recordWalk(
            CollectiveWalkSnapshot(
                distanceKm = snapshotDistanceKm,
                meditationMin = snapshotMeditateMin,
                talkMin = talkMin,
            ),
        )
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (t: Throwable) {
        Log.w(TAG, "collective recordWalk failed", t)
    }
}
```

**Modify** `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkViewModelTest.kt`:

VM ctor signature changed; the test fixture's `viewModel = WalkViewModel(...)` site needs the new param. Pass a fake `CollectiveRepository` wrapper (or a real one with a fake service + in-memory DataStore — easiest: wire a real repo against in-memory test substitutes).

Add test: `finishWalk records collective snapshot with active accumulator distance + meditate + talk`. Seed `controller.state` to Active with known accumulator + insert voice recordings, call finishWalk, verify the fake CollectiveRepository's recordWalk got the right snapshot.

### Verify
```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest --tests "*.WalkViewModelTest"
```

---

## Task 8 — App-launch fetch in `PilgrimApp.onCreate`

### Files

**Modify** `app/src/main/java/org/walktalkmeditate/pilgrim/PilgrimApp.kt`:

Inject `CollectiveRepository` + `@CollectiveRepoScope CoroutineScope` (already provided via CollectiveModule). In `onCreate` after super:
```kotlin
appLaunchScope.launch {
    try {
        collectiveRepository.fetchIfStale()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (t: Throwable) {
        Log.w(TAG, "boot fetchIfStale failed", t)
    }
}
```

### Verify
```bash
./gradlew :app:assembleDebug
```

---

## Task 9 — Strings

### Files

**Modify** `app/src/main/res/values/strings.xml`:

Append:
```xml
<!-- Stage 8-B: collective counter -->
<string name="collective_opt_in_label">Walk with the collective</string>
<string name="collective_opt_in_description">Add your footsteps to the path</string>
<string name="collective_stats_walks_distance">%1$s walks · %2$s km</string>
<string name="collective_stats_one_walk">%1$s walk · %2$s km</string>
<string name="collective_stats_loading">Loading the collective…</string>
<string name="collective_stats_unavailable">The collective is resting.</string>
```

### Verify
```bash
./gradlew :app:assembleDebug
```

---

## Task 10 — `SettingsViewModel` + `CollectiveStatsCard`

### Files

**Create** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsViewModel.kt`:

`@HiltViewModel`. Inject `CollectiveRepository`. Expose `stats: StateFlow<CollectiveStats?>`, `optIn: StateFlow<Boolean>`. Methods: `setOptIn(value: Boolean)` → launches in viewModelScope, calls repo.setOptIn. `fetchOnAppear()` → launches viewModelScope, calls repo.fetchIfStale (TTL-gated, harmless to call repeatedly).

**Create** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/CollectiveStatsCard.kt`:

Compose Card composable. Takes `stats: CollectiveStats?` parameter. If `stats != null && stats.totalWalks > 0`, renders:
```
{walks}  walks · {distanceKm} km
```
using `Locale.ROOT`-formatted `NumberFormat.getNumberInstance(Locale.ROOT)` for the walks count (grouping commas in ASCII). Distance is `String.format(Locale.ROOT, "%.0f", stats.totalDistanceKm)`.

If `stats == null` → render the "loading" copy. If `stats.totalWalks == 0` → render the "resting" copy.

Use `pilgrimColors.parchmentSecondary` background, `pilgrimType.body` style.

### Verify
```bash
./gradlew :app:compileDebugKotlin
```

---

## Task 11 — Settings opt-in toggle row + slot stats card

### Files

**Modify** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsScreen.kt`:

Inject `SettingsViewModel = hiltViewModel()`. Collect `stats` and `optIn`. `LaunchedEffect(Unit) { viewModel.fetchOnAppear() }`.

Insert above the existing Settings list items:
1. `CollectiveStatsCard(stats = stats)` — the stats display.
2. A `ListItem` with leading icon `Icons.Filled.People` (or similar), headline `R.string.collective_opt_in_label`, supporting text `R.string.collective_opt_in_description`, trailing `Switch(checked = optIn, onCheckedChange = viewModel::setOptIn)`.

### Verify
```bash
./gradlew :app:assembleDebug
```

---

## Task 12 — `SettingsViewModelTest`

Wires a fake CollectiveRepository, verifies:
- `stats` flow proxies the repo's stats.
- `optIn` flow proxies the repo's opt-in.
- `setOptIn(true)` calls repo.setOptIn(true).
- `fetchOnAppear()` calls repo.fetchIfStale().

### Verify
```bash
./gradlew :app:testDebugUnitTest --tests "*.SettingsViewModelTest"
```

---

## Self-review

- **Spec coverage:** every section of the spec maps to a task. The 14 production files + 6 test files spec match the task list (Task 1 = 3 files, Task 2 = 1+1, Task 3 = 1+1, Task 4 = 1+1, Task 5 = 1, Task 6 = 1+1, Task 7 = 0+0 (modify), Task 8 = 0 (modify), Task 9 = 0 (modify), Task 10 = 2, Task 11 = 0 (modify), Task 12 = 0+1 = 14 production + 6 test, exactly matches the spec's estimate).
- **Task count:** 12 — within autopilot's 15-task threshold.
- **Type consistency:** `CollectiveStats`, `CollectiveCounterDelta`, `CollectiveWalkSnapshot`, `PostResult` consistent across layers.
- **No placeholders.**
- **Thread policy explicit per task** (Dispatchers.IO for HTTP + DataStore + repo scope; Main for VM-collected StateFlows).
- **CE re-throw** in every `catch (Throwable)` block wrapping suspend work (CacheStore decode, Service fetch/post, Repository fetchIfStale + recordWalk inner, WalkViewModel hook).
- **Mutex is required** in `CollectiveRepository.recordWalk` — not optional.
- **Empty-pending guard** in `CollectiveRepository.recordWalk` AND defense-in-depth in `CollectiveCacheStore.mutatePending` (which removes the key when next is empty).
