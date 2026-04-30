# Stage 11 Implementation Plan — Walk-Table Cache Cols + Milestone Overlay

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bundle three iOS-parity items: (a) `walks` table cache cols (`distance_meters`, `meditation_seconds`) computed at finalize + lazy backfill; (b) collective milestone overlay (sacred numbers, 8s auto-dismiss, bell at 0.4× user bellVolume); (c) cross-platform `.pilgrim` round-trip QA checklist.

**Architecture:** Cache cols added via explicit MIGRATION_4_5 (ALTER only, no row scan). New `WalkMetricsCache` computes from RouteDataSamples + ActivityIntervals + WalkEvents, mirroring iOS `NewWalk.init` clamp `min(rawMeditation, activeDuration)` exactly. `WalkMetricsBackfillCoordinator` (`@Singleton`, app-scoped) observes Room hot Flow + drains stale walks one at a time with inflight dedup. `CollectiveMilestoneDetector` exposes `StateFlow<CollectiveMilestone?>` (matches iOS `@Published` persistence across nav). `PracticeSummaryHeader` renders crossfade overlay with bell-fire latch. `BellPlaying.play(scale)` interface overload multiplies by user bellVolume.

**Tech Stack:** Kotlin 2.x, Jetpack Compose, Material 3, Hilt, Room 2.6+, DataStore Preferences, kotlinx-coroutines + Flow/StateFlow, JUnit 4 + Robolectric + Turbine + MigrationTestHelper.

---

### Task 1: Walk entity gains cache cols

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/data/entity/Walk.kt`
- Test: `app/src/androidTest/java/org/walktalkmeditate/pilgrim/data/PilgrimDatabaseMigrationTest.kt` (new)

- [ ] **Step 1: Write failing test**

```kotlin
// PilgrimDatabaseMigrationTest.kt — new file
@RunWith(AndroidJUnit4::class)
class PilgrimDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PilgrimDatabase::class.java,
    )

    @Test
    fun migrate4To5_addsNullableCacheColsPreservesExistingRow() {
        // Open v4 DB, insert a walk with id=42
        helper.createDatabase("pilgrim.db", 4).use { db ->
            db.execSQL(
                "INSERT INTO walks (id, uuid, start_timestamp, end_timestamp, intention, favicon, notes) " +
                    "VALUES (42, 'abc-uuid', 1000, 5000, NULL, NULL, NULL)"
            )
        }
        // Migrate to v5
        val migrated = helper.runMigrationsAndValidate(
            "pilgrim.db", 5, /* validateDroppedTables = */ true, MIGRATION_4_5,
        )
        migrated.query("SELECT id, distance_meters, meditation_seconds FROM walks WHERE id = 42").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(42L, c.getLong(0))
            assertTrue("distance_meters should be null after migration", c.isNull(1))
            assertTrue("meditation_seconds should be null after migration", c.isNull(2))
        }
    }
}
```

- [ ] **Step 2: Run test — should FAIL**

Run: `./gradlew :app:connectedAndroidTest --tests "*PilgrimDatabaseMigrationTest*"`
Expected: FAIL — `MIGRATION_4_5` symbol unresolved + `walks` table missing the new columns.

- [ ] **Step 3: Add fields to Walk entity**

Modify `Walk.kt`. Append two fields after `notes`:

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
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "start_timestamp")
    val startTimestamp: Long,
    @ColumnInfo(name = "end_timestamp")
    val endTimestamp: Long? = null,
    val intention: String? = null,
    val favicon: String? = null,
    val notes: String? = null,
    @ColumnInfo(name = "distance_meters")
    val distanceMeters: Double? = null,
    @ColumnInfo(name = "meditation_seconds")
    val meditationSeconds: Long? = null,
)
```

Order matters — Room hashes column order. Place `distanceMeters` before `meditationSeconds`.

- [ ] **Step 4: Skip — depends on Task 2 (DB version + migration)**

- [ ] **Step 5: Skip commit — bundle with Task 2**

---

### Task 2: PilgrimDatabase MIGRATION_4_5 + version bump

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/data/PilgrimDatabase.kt`
- Test: from Task 1

- [ ] **Step 1: Already failing — Task 1's test fails because MIGRATION_4_5 unresolved.**

- [ ] **Step 2: Bump DB version + add migration**

In `PilgrimDatabase.kt`:

```kotlin
@Database(
    entities = [
        Walk::class,
        RouteDataSample::class,
        ActivityInterval::class,
        WalkEvent::class,
        VoiceRecording::class,
        WalkPhoto::class,
        // … any others currently listed
    ],
    version = 5, // was 4
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
    ],
)
abstract class PilgrimDatabase : RoomDatabase() {
    // existing DAOs

    companion object {
        val MIGRATION_2_3 = /* existing */
        val MIGRATION_3_4 = /* existing */

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Room's RoomOpenHelper auto-wraps migrate() in a transaction.
                // Don't add a manual one — it would deadlock the open helper.
                // Column-declaration order MUST match Walk.kt entity order
                // (distanceMeters first, then meditationSeconds) — Room hashes
                // include column order in the schema validation step.
                db.execSQL("ALTER TABLE walks ADD COLUMN distance_meters REAL")
                db.execSQL("ALTER TABLE walks ADD COLUMN meditation_seconds INTEGER")
            }
        }
    }
}
```

Wherever `Room.databaseBuilder(...).addMigrations(MIGRATION_2_3, MIGRATION_3_4)` is wired (likely `DatabaseModule.kt`), append `MIGRATION_4_5`.

- [ ] **Step 3: Generate updated schema JSON**

Run: `./gradlew :app:assembleDebug` once. Confirm `app/schemas/org.walktalkmeditate.pilgrim.data.PilgrimDatabase/5.json` exists with `distance_meters` (REAL nullable) + `meditation_seconds` (INTEGER nullable) in the `walks` columns array.

- [ ] **Step 4: Run migration test — PASS**

Run: `./gradlew :app:connectedAndroidTest --tests "*PilgrimDatabaseMigrationTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/data/entity/Walk.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/data/PilgrimDatabase.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/data/DatabaseModule.kt \
        app/schemas/org.walktalkmeditate.pilgrim.data.PilgrimDatabase/5.json \
        app/src/androidTest/java/org/walktalkmeditate/pilgrim/data/PilgrimDatabaseMigrationTest.kt
git commit -m "feat(walks): add distance_meters + meditation_seconds cache cols (Stage 11-A)"
```

---

### Task 3: WalkDao.updateAggregates

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/data/dao/WalkDao.kt`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/data/dao/WalkDaoCacheColsTest.kt` (new)

- [ ] **Step 1: Write failing test**

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkDaoCacheColsTest {
    private lateinit var db: PilgrimDatabase
    private lateinit var walkDao: WalkDao

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(ctx, PilgrimDatabase::class.java).build()
        walkDao = db.walkDao()
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun updateAggregates_writesBothFields() = runTest {
        val id = walkDao.insert(Walk(startTimestamp = 1_000, endTimestamp = 60_000))
        walkDao.updateAggregates(id, distanceMeters = 1234.5, meditationSeconds = 600L)
        val updated = walkDao.getById(id)!!
        assertEquals(1234.5, updated.distanceMeters!!, 0.0001)
        assertEquals(600L, updated.meditationSeconds)
    }

    @Test
    fun updateAggregates_supportsNullValues() = runTest {
        val id = walkDao.insert(Walk(startTimestamp = 1_000, endTimestamp = 60_000))
        walkDao.updateAggregates(id, distanceMeters = null, meditationSeconds = null)
        val updated = walkDao.getById(id)!!
        assertNull(updated.distanceMeters)
        assertNull(updated.meditationSeconds)
    }
}
```

- [ ] **Step 2: Run — FAIL** (`updateAggregates` unresolved).

- [ ] **Step 3: Add DAO method**

In `WalkDao.kt`:

```kotlin
@Query(
    "UPDATE walks SET distance_meters = :distanceMeters, " +
        "meditation_seconds = :meditationSeconds WHERE id = :id"
)
suspend fun updateAggregates(id: Long, distanceMeters: Double?, meditationSeconds: Long?)
```

- [ ] **Step 4: Run — PASS.**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/data/dao/WalkDao.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/data/dao/WalkDaoCacheColsTest.kt
git commit -m "feat(walks): WalkDao.updateAggregates for cache cols (Stage 11-A)"
```

---

### Task 4: WalkMetricsCache compute helper

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/data/walk/WalkMetricsCache.kt`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/data/walk/WalkMetricsCacheTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkMetricsCacheTest {
    private lateinit var db: PilgrimDatabase
    private lateinit var walkRepository: WalkRepository
    private lateinit var walkDao: WalkDao
    private lateinit var walkEventDao: WalkEventDao
    private lateinit var cache: WalkMetricsCache

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(ctx, PilgrimDatabase::class.java).build()
        walkDao = db.walkDao()
        walkEventDao = db.walkEventDao()
        walkRepository = WalkRepository(
            walkDao,
            db.routeDataSampleDao(),
            db.activityIntervalDao(),
            walkEventDao,
            // any other deps with sensible test fakes
        )
        cache = WalkMetricsCache(walkRepository, walkDao, walkEventDao)
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun computeAndPersist_writesDistanceAndMeditation() = runTest {
        val id = walkDao.insert(Walk(startTimestamp = 0, endTimestamp = 30 * 60_000L))
        // 3 samples: 0m, ~111m east, ~222m east → ~222m total
        db.routeDataSampleDao().insert(routeSample(id, t = 0, lat = 0.0, lng = 0.0))
        db.routeDataSampleDao().insert(routeSample(id, t = 60_000, lat = 0.0, lng = 0.001))
        db.routeDataSampleDao().insert(routeSample(id, t = 120_000, lat = 0.0, lng = 0.002))
        // 5-minute meditation interval
        db.activityIntervalDao().insert(
            ActivityInterval(walkId = id, activityType = ActivityType.MEDITATING,
                startTimestamp = 60_000, endTimestamp = 360_000)
        )
        cache.computeAndPersist(id)
        val w = walkDao.getById(id)!!
        assertNotNull(w.distanceMeters)
        assertTrue("expected ~222m, got ${w.distanceMeters}", w.distanceMeters!! in 200.0..240.0)
        assertEquals(300L, w.meditationSeconds)
    }

    @Test
    fun computeAndPersist_skipsInProgressWalk() = runTest {
        val id = walkDao.insert(Walk(startTimestamp = 0, endTimestamp = null))
        cache.computeAndPersist(id)
        val w = walkDao.getById(id)!!
        assertNull(w.distanceMeters)
        assertNull(w.meditationSeconds)
    }

    @Test
    fun computeMeditation_clampsToActiveDurationFromPauseEvents() = runTest {
        // 30-minute walk; user paused at 10min, resumed at 22min (12-min pause).
        // Active duration = 18 minutes. Corruption: meditation interval claims 50 minutes.
        val id = walkDao.insert(Walk(startTimestamp = 0, endTimestamp = 30 * 60_000L))
        db.activityIntervalDao().insert(
            ActivityInterval(walkId = id, activityType = ActivityType.MEDITATING,
                startTimestamp = 0, endTimestamp = 50 * 60_000L)
        )
        walkEventDao.insert(WalkEvent(walkId = id, timestamp = 10 * 60_000L, eventType = WalkEventType.PAUSED))
        walkEventDao.insert(WalkEvent(walkId = id, timestamp = 22 * 60_000L, eventType = WalkEventType.RESUMED))
        cache.computeAndPersist(id)
        val w = walkDao.getById(id)!!
        assertEquals(18 * 60L, w.meditationSeconds)
    }

    @Test
    fun computeActiveDuration_unpairedTrailingPauseClosedAtEnd() = runTest {
        // Walk paused 10min in, never resumed; ended at 20min.
        // Active = 10 min; pause closed at endTimestamp.
        val id = walkDao.insert(Walk(startTimestamp = 0, endTimestamp = 20 * 60_000L))
        db.activityIntervalDao().insert(
            ActivityInterval(walkId = id, activityType = ActivityType.MEDITATING,
                startTimestamp = 0, endTimestamp = 30 * 60_000L) // bogus 30-min interval
        )
        walkEventDao.insert(WalkEvent(walkId = id, timestamp = 10 * 60_000L, eventType = WalkEventType.PAUSED))
        cache.computeAndPersist(id)
        assertEquals(10 * 60L, walkDao.getById(id)!!.meditationSeconds)
    }

    private fun routeSample(walkId: Long, t: Long, lat: Double, lng: Double) =
        RouteDataSample(walkId = walkId, timestamp = t, latitude = lat, longitude = lng,
            altitude = 0.0, horizontalAccuracy = 5.0, verticalAccuracy = 5.0, speed = 0.0)
}
```

- [ ] **Step 2: Run — FAIL** (class doesn't exist).

- [ ] **Step 3: Implement WalkMetricsCache**

```kotlin
// app/src/main/java/org/walktalkmeditate/pilgrim/data/walk/WalkMetricsCache.kt
@Singleton
class WalkMetricsCache @Inject constructor(
    private val walkRepository: WalkRepository,
    private val walkDao: WalkDao,
    private val walkEventDao: WalkEventDao,
) {
    suspend fun computeAndPersist(walkId: Long) {
        val walk = walkDao.getById(walkId) ?: return
        if (walk.endTimestamp == null) return

        val samples = walkRepository.locationSamplesFor(walkId)
        val intervals = walkRepository.activityIntervalsFor(walkId)
        val events = walkEventDao.eventsForWalk(walkId)

        val distance = WalkDistanceCalculator.computeDistanceMeters(samples)
        val meditation = computeMeditationSeconds(intervals, walk, events)
        walkDao.updateAggregates(walkId, distance, meditation)
    }

    /**
     * Sum of MEDITATING ActivityInterval durations, clamped to active
     * duration. Mirrors iOS NewWalk.swift:42 `min(rawMeditate, activeDuration)`.
     * `internal` so PilgrimPackageConverter can use the same clamp on the
     * fallback path when cache is null.
     */
    internal fun computeMeditationSeconds(
        intervals: List<ActivityInterval>,
        walk: Walk,
        events: List<WalkEvent>,
    ): Long {
        val rawMillis = intervals
            .filter { it.activityType == ActivityType.MEDITATING }
            .sumOf { (it.endTimestamp - it.startTimestamp).coerceAtLeast(0L) }
        val rawSeconds = rawMillis / 1_000L
        val activeDurationSeconds = computeActiveDurationSeconds(walk, events)
        return rawSeconds.coerceAtMost(activeDurationSeconds).coerceAtLeast(0L)
    }

    private fun computeActiveDurationSeconds(walk: Walk, events: List<WalkEvent>): Long {
        val end = walk.endTimestamp ?: return 0L
        val wallClockMs = (end - walk.startTimestamp).coerceAtLeast(0L)
        var pausedSinceMs: Long? = null
        var pausedTotalMs = 0L
        for (event in events.sortedBy { it.timestamp }) {
            when (event.eventType) {
                WalkEventType.PAUSED -> if (pausedSinceMs == null) pausedSinceMs = event.timestamp
                WalkEventType.RESUMED -> {
                    val pausedAt = pausedSinceMs ?: continue
                    pausedTotalMs += (event.timestamp - pausedAt).coerceAtLeast(0L)
                    pausedSinceMs = null
                }
                else -> Unit
            }
        }
        pausedSinceMs?.let { pausedTotalMs += (end - it).coerceAtLeast(0L) }
        return ((wallClockMs - pausedTotalMs).coerceAtLeast(0L)) / 1_000L
    }
}
```

If `WalkEventDao.eventsForWalk(walkId)` doesn't exist, add `@Query("SELECT * FROM walk_events WHERE walk_id = :walkId ORDER BY timestamp ASC") suspend fun eventsForWalk(walkId: Long): List<WalkEvent>` to the DAO. If the DAO already has an equivalent (e.g. `getForWalk`), reuse and rename in the cache.

- [ ] **Step 4: Run — PASS.**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/data/walk/WalkMetricsCache.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/data/dao/WalkEventDao.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/data/walk/WalkMetricsCacheTest.kt
git commit -m "feat(walks): WalkMetricsCache iOS-faithful clamp compute (Stage 11-A)"
```

---

### Task 5: WalkFinalizationObserver wires WalkMetricsCache

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/walk/WalkFinalizationObserver.kt`
- Modify: `app/src/test/java/org/walktalkmeditate/pilgrim/walk/WalkFinalizationObserverTest.kt`

- [ ] **Step 1: Write failing test**

Add to `WalkFinalizationObserverTest.kt`:

```kotlin
@Test
fun runFinalize_invokesWalkMetricsCacheAfterCollectivePost() = runTest {
    val cacheCalls = mutableListOf<Long>()
    val fakeCache = object : WalkMetricsCache(/* fakeRepo, fakeDao, fakeEventDao */) {
        override suspend fun computeAndPersist(walkId: Long) { cacheCalls += walkId }
    }
    val observer = WalkFinalizationObserver(/* …existing deps…, */ walkMetricsCache = fakeCache)
    observer.runFinalize(WalkState.Finished(walkWithId(42L)))
    assertEquals(listOf(42L), cacheCalls)
}

@Test
fun runFinalize_doesNotPropagateCacheException() = runTest {
    val fakeCache = object : WalkMetricsCache(/* … */) {
        override suspend fun computeAndPersist(walkId: Long) { error("boom") }
    }
    val observer = WalkFinalizationObserver(/* …, */ walkMetricsCache = fakeCache)
    // Must complete without throwing
    observer.runFinalize(WalkState.Finished(walkWithId(42L)))
}
```

`WalkMetricsCache` is a final class — to fake it for unit tests either (a) extract `interface WalkMetricsCaching { suspend fun computeAndPersist(walkId: Long) }` and switch the constructor param to the interface, or (b) make `computeAndPersist` `open`. Prefer (a) for testability.

- [ ] **Step 2: Run — FAIL.**

- [ ] **Step 3: Implement**

Extract interface:

```kotlin
// In WalkMetricsCache.kt
interface WalkMetricsCaching {
    suspend fun computeAndPersist(walkId: Long)
}

@Singleton
class WalkMetricsCache @Inject constructor(...) : WalkMetricsCaching {
    override suspend fun computeAndPersist(walkId: Long) { /* existing body */ }
    internal fun computeMeditationSeconds(...) { ... } // unchanged
}
```

Hilt binding in `WalkModule.kt` (or wherever cache lives):

```kotlin
@Binds @Singleton abstract fun bindWalkMetricsCaching(impl: WalkMetricsCache): WalkMetricsCaching
```

In `WalkFinalizationObserver.kt`, inject `WalkMetricsCaching`. After collective POST + widget refresh, add:

```kotlin
try {
    walkMetricsCache.computeAndPersist(walkId)
} catch (ce: CancellationException) {
    throw ce
} catch (t: Throwable) {
    Log.w(TAG, "metric cache compute failed for walk $walkId", t)
}
```

- [ ] **Step 4: Run — PASS.**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/data/walk/WalkMetricsCache.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/walk/WalkFinalizationObserver.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/di/WalkModule.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/walk/WalkFinalizationObserverTest.kt
git commit -m "feat(walks): finalize hook persists WalkMetricsCache (Stage 11-A)"
```

---

### Task 6: WalkMetricsBackfillCoordinator

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/data/walk/WalkMetricsBackfillCoordinator.kt`
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/PilgrimApplication.kt`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/data/walk/WalkMetricsBackfillCoordinatorTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
class WalkMetricsBackfillCoordinatorTest {
    @Test
    fun observesAndDrainsStaleWalks() = runTest {
        val cacheCalls = mutableListOf<Long>()
        val cache = object : WalkMetricsCaching {
            override suspend fun computeAndPersist(walkId: Long) { cacheCalls += walkId }
        }
        val walks = MutableStateFlow<List<Walk>>(listOf(
            Walk(id = 1, startTimestamp = 0, endTimestamp = 1000, distanceMeters = null),
            Walk(id = 2, startTimestamp = 0, endTimestamp = 2000, distanceMeters = 100.0, meditationSeconds = 0),
        ))
        val repo = object : WalksSource { override fun observeAllWalks() = walks }
        val coord = WalkMetricsBackfillCoordinator(repo, cache, this.backgroundScope)
        coord.start()
        runCurrent()
        assertEquals(listOf(1L), cacheCalls)
    }

    @Test
    fun dedupsRapidEmissionsForSameId() = runTest {
        val callCount = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val cache = object : WalkMetricsCaching {
            override suspend fun computeAndPersist(walkId: Long) {
                callCount.incrementAndGet()
                gate.await()
            }
        }
        val walks = MutableStateFlow<List<Walk>>(listOf(
            Walk(id = 1, startTimestamp = 0, endTimestamp = 1000, distanceMeters = null),
        ))
        val repo = object : WalksSource { override fun observeAllWalks() = walks }
        val coord = WalkMetricsBackfillCoordinator(repo, cache, this.backgroundScope)
        coord.start()
        runCurrent()
        // Same list re-emitted (Room hot flow re-emits on unrelated row update)
        walks.emit(walks.value.toList())
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, callCount.get())
    }

    @Test
    fun skipsInProgressWalks() = runTest {
        val cacheCalls = mutableListOf<Long>()
        val cache = object : WalkMetricsCaching {
            override suspend fun computeAndPersist(walkId: Long) { cacheCalls += walkId }
        }
        val walks = MutableStateFlow<List<Walk>>(listOf(
            Walk(id = 1, startTimestamp = 0, endTimestamp = null, distanceMeters = null),
        ))
        val repo = object : WalksSource { override fun observeAllWalks() = walks }
        val coord = WalkMetricsBackfillCoordinator(repo, cache, this.backgroundScope)
        coord.start()
        runCurrent()
        assertTrue(cacheCalls.isEmpty())
    }
}
```

`WalksSource` is the same lightweight interface used in `DataSettingsViewModelTest` for stubbing `walkRepository.observeAllWalks()` — reuse if compatible.

- [ ] **Step 2: Run — FAIL.**

- [ ] **Step 3: Implement coordinator**

```kotlin
// WalkMetricsBackfillCoordinator.kt
@Singleton
class WalkMetricsBackfillCoordinator @Inject constructor(
    private val walksSource: WalksSource,
    private val cache: WalkMetricsCaching,
    @CollectiveRepoScope private val scope: CoroutineScope,
) {
    private val inflight = Collections.synchronizedSet(HashSet<Long>())
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            walksSource.observeAllWalks()
                .map { walks ->
                    walks.firstOrNull { walk ->
                        walk.endTimestamp != null &&
                            (walk.distanceMeters == null || walk.meditationSeconds == null)
                    }?.id
                }
                .filterNotNull()
                .distinctUntilChanged()
                .collect { walkId ->
                    if (!inflight.add(walkId)) return@collect
                    try {
                        cache.computeAndPersist(walkId)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        Log.w(TAG, "backfill failed walk=$walkId", t)
                    } finally {
                        inflight.remove(walkId)
                    }
                }
        }
    }

    private companion object { const val TAG = "WalkMetricsBackfill" }
}
```

If `WalksSource` interface doesn't already exist as a public seam, extract one (it's already used in tests per memory). Either way, `WalkRepository` needs to implement or be wrapped by it.

In `PilgrimApplication.onCreate()`:

```kotlin
@Inject lateinit var walkMetricsBackfillCoordinator: WalkMetricsBackfillCoordinator

override fun onCreate() {
    super.onCreate()
    // … existing initialization (Hilt entry point)
    walkMetricsBackfillCoordinator.start()
}
```

Hilt-bind `@CollectiveRepoScope CoroutineScope` already exists (per spec inventory) — no new module needed.

- [ ] **Step 4: Run — PASS.**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/data/walk/WalkMetricsBackfillCoordinator.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/PilgrimApplication.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/data/walk/WalkMetricsBackfillCoordinatorTest.kt
git commit -m "feat(walks): WalkMetricsBackfillCoordinator drains stale rows (Stage 11-A)"
```

---

### Task 7: SettingsViewModel + AboutViewModel read cache cols

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsViewModel.kt`
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/AboutViewModel.kt`
- Modify: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsViewModelTest.kt`
- Modify: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/settings/about/AboutViewModelTest.kt`

- [x] **Step 1: Write failing test (SettingsViewModel)**

Add:

```kotlin
@Test
fun practiceSummary_readsCacheColsDirectlyNoIntervalScan() = runTest {
    val intervalDaoCallCount = AtomicInteger(0)
    val walks = listOf(
        Walk(id = 1, startTimestamp = 0, endTimestamp = 100_000,
            distanceMeters = 1500.0, meditationSeconds = 300L),
        Walk(id = 2, startTimestamp = 200_000, endTimestamp = 400_000,
            distanceMeters = 2200.0, meditationSeconds = 600L),
    )
    val vm = newViewModel(
        walksSource = FakeWalksSource(flowOf(walks)),
        activityIntervalDao = countingIntervalDao(intervalDaoCallCount),
    )
    vm.practiceSummary.test(timeout = 10.seconds) {
        var current = awaitItem()
        while (current.walkCount != 2) current = awaitItem()
        assertEquals(2, current.walkCount)
        assertEquals(3700.0, current.totalDistanceMeters, 0.001)
        assertEquals(900L, current.totalMeditationSeconds)
        cancelAndIgnoreRemainingEvents()
    }
    assertEquals("interval DAO must not be scanned per-walk", 0, intervalDaoCallCount.get())
}
```

- [x] **Step 2: Run — FAIL** (current implementation still scans).

- [x] **Step 3: Replace per-walk scan with sum over cache cols**

In `SettingsViewModel.kt` `practiceSummary` flow, replace the existing per-walk loop with:

```kotlin
val totalDistanceMeters = walks.sumOf { it.distanceMeters ?: 0.0 }
val totalMeditationSeconds = walks.sumOf { it.meditationSeconds ?: 0L }
```

Drop any `activityIntervalsFor(walkId)` calls in the practiceSummary path.

Same change for `AboutViewModel.stats`. Add equivalent test.

- [x] **Step 4: Run — PASS.**

- [x] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsViewModel.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/AboutViewModel.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsViewModelTest.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/settings/about/AboutViewModelTest.kt
git commit -m "perf(settings): read walks cache cols, kill per-walk N+1 (Stage 11-A)"
```

---

### Task 8: PilgrimPackageConverter aligns to clamped meditation + cache reads

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/data/pilgrim/builder/PilgrimPackageConverter.kt`
- Modify: `app/src/test/java/org/walktalkmeditate/pilgrim/data/pilgrim/builder/PilgrimPackageConverterTest.kt`

- [x] **Step 1: Write failing tests**

```kotlin
@Test
fun export_distanceCacheHitEqualsLiveCompute() = runTest {
    val walk = walkWithRoute(routeSamples = threeKmStraightLine())
    // Cache populated
    val cachedExport = converter.convert(bundleFor(walk.copy(distanceMeters = 3000.0, meditationSeconds = 0)))
    // Cache cleared
    val liveExport = converter.convert(bundleFor(walk.copy(distanceMeters = null, meditationSeconds = null)))
    assertEquals(cachedExport.walks[0].stats.distance, liveExport.walks[0].stats.distance, 0.0001)
    assertEquals(cachedExport.walks[0].stats.meditateDuration, liveExport.walks[0].stats.meditateDuration, 0.0001)
}

@Test
fun export_corruptMeditationClampedRegardlessOfCacheState() = runTest {
    // 30-min walk, 12-min pause via PAUSED/RESUMED → activeDuration = 18min = 1080s
    // Corrupt 50-min MEDITATING interval.
    val walk = walkWithCorruptMeditation()
    val cleared = converter.convert(bundleFor(walk.copy(meditationSeconds = null)))
    val cached = converter.convert(bundleFor(walk.copy(meditationSeconds = 1080)))
    assertEquals(1080.0, cleared.walks[0].stats.meditateDuration, 0.001)
    assertEquals(1080.0, cached.walks[0].stats.meditateDuration, 0.001)
}
```

- [x] **Step 2: Run — FAIL.**

- [x] **Step 3: Modify converter**

In `PilgrimPackageConverter.convert()`, replace the meditation/distance reads:

```kotlin
// Distance: cache-first, fallback to live compute
val distanceMeters = walk.distanceMeters
    ?: WalkDistanceCalculator.computeDistanceMeters(samples)

// Meditation: cache-first, fallback to clamped helper (was: raw sumActivityDuration)
val meditationSeconds = walk.meditationSeconds
    ?: walkMetricsCache.computeMeditationSeconds(intervals, walk, events)

PilgrimStats(
    distance = distanceMeters,
    // ... existing fields …
    meditateDuration = meditationSeconds.toDouble(),
)
```

`computeMeditationSeconds` is currently `internal` on `WalkMetricsCache` — keep `internal`, since converter is in the same module. Inject `WalkMetricsCache` (or its `WalkMetricsCaching` interface) into the converter; pass `events` from `walkEventDao.eventsForWalk(walkId)` at the converter's per-walk loop site.

If the converter doesn't currently take `WalkEventDao`, add it.

- [x] **Step 4: Run — PASS.**

- [x] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/data/pilgrim/builder/PilgrimPackageConverter.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/data/walk/WalkMetricsCache.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/data/walk/WalkMetricsMath.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/data/pilgrim/builder/PilgrimPackageConverterTest.kt
git commit -m "feat(pilgrim): converter cache-first read + iOS-faithful meditation clamp (Stage 11-A)"
```

**Implementation note:** `computeMeditationSeconds` was extracted from `WalkMetricsCache` to a new top-level `internal object WalkMetricsMath` (same package). `WalkMetricsCache` delegates to it; the converter calls it directly. Plain JUnit tests stay simple (no DAO mocks / Robolectric), and the spec's invariant — both cache-write and cache-fallback paths run the same iOS clamp — is preserved.

---

### Task 9: CollectiveCacheStore lastSeen key

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/data/collective/CollectiveCacheStore.kt`
- Modify: `app/src/test/java/org/walktalkmeditate/pilgrim/data/collective/CollectiveCacheStoreTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
@Test
fun lastSeenCollectiveWalks_roundTripsAcrossInstances() = runTest {
    val store1 = CollectiveCacheStore(dataStore, json)
    store1.setLastSeenCollectiveWalks(108)
    val store2 = CollectiveCacheStore(dataStore, json)
    assertEquals(108, store2.firstReadyLastSeenCollectiveWalks())
}

@Test
fun firstReadyLastSeenCollectiveWalks_defaultsToZero() = runTest {
    val store = CollectiveCacheStore(dataStore, json)
    assertEquals(0, store.firstReadyLastSeenCollectiveWalks())
}

@Test
fun lastSeenCollectiveWalksFlow_emitsUpdates() = runTest {
    val store = CollectiveCacheStore(dataStore, json)
    store.lastSeenCollectiveWalksFlow.test(timeout = 10.seconds) {
        assertEquals(0, awaitItem())
        store.setLastSeenCollectiveWalks(108)
        var current = awaitItem()
        while (current != 108) current = awaitItem()
        cancelAndIgnoreRemainingEvents()
    }
}
```

Use the existing test scaffolding (Robolectric + `PreferenceDataStoreFactory.create(scope, produceFile)` per the Stage 7-D fix).

- [ ] **Step 2: Run — FAIL.**

- [ ] **Step 3: Add key + helpers**

In `CollectiveCacheStore.kt`, append to companion object + class body:

```kotlin
val lastSeenCollectiveWalksFlow: Flow<Int> =
    dataStore.data
        .map { it[KEY_LAST_SEEN_COLLECTIVE_WALKS] ?: 0 }
        .distinctUntilChanged()

suspend fun firstReadyLastSeenCollectiveWalks(): Int =
    dataStore.data.map { it[KEY_LAST_SEEN_COLLECTIVE_WALKS] ?: 0 }.first()

suspend fun setLastSeenCollectiveWalks(value: Int) {
    dataStore.edit { it[KEY_LAST_SEEN_COLLECTIVE_WALKS] = value }
}

internal companion object {
    // … existing
    val KEY_LAST_SEEN_COLLECTIVE_WALKS = intPreferencesKey("lastSeenCollectiveWalks")
}
```

Add `import androidx.datastore.preferences.core.intPreferencesKey`.

- [ ] **Step 4: Run — PASS.**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/data/collective/CollectiveCacheStore.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/data/collective/CollectiveCacheStoreTest.kt
git commit -m "feat(collective): lastSeenCollectiveWalks key in CollectiveCacheStore (Stage 11-B)"
```

---

### Task 10: CollectiveMilestone data class

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/data/collective/CollectiveMilestone.kt`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/data/collective/CollectiveMilestoneTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
class CollectiveMilestoneTest {
    @Test
    fun forNumber_messageVerbatim_108() {
        assertEquals("108 walks. One for each bead on the mala.",
            CollectiveMilestone.forNumber(108).message)
    }
    @Test fun forNumber_messageVerbatim_1080() {
        assertEquals("1,080 walks. The mala, turned ten times.",
            CollectiveMilestone.forNumber(1_080).message)
    }
    @Test fun forNumber_messageVerbatim_2160() {
        assertEquals("2,160 walks. One full age of the zodiac.",
            CollectiveMilestone.forNumber(2_160).message)
    }
    @Test fun forNumber_messageVerbatim_10000IncludesKanji() {
        val msg = CollectiveMilestone.forNumber(10_000).message
        assertEquals("10,000 walks. 万 — all things.", msg)
        assertTrue("must contain U+4E07", msg.contains('万'))
    }
    @Test fun forNumber_messageVerbatim_33333() {
        assertEquals("33,333 walks. The Saigoku pilgrimage, a thousandfold.",
            CollectiveMilestone.forNumber(33_333).message)
    }
    @Test fun forNumber_messageVerbatim_88000() {
        assertEquals("88,000 walks. Shikoku's 88 temples, a thousand times over.",
            CollectiveMilestone.forNumber(88_000).message)
    }
    @Test fun forNumber_messageVerbatim_108000() {
        assertEquals("108,000 walks. The great mala, complete.",
            CollectiveMilestone.forNumber(108_000).message)
    }
    @Test fun forNumber_unknownNumberFormatsLocaleUS() {
        assertEquals("1,234 walks. You were one of them.",
            CollectiveMilestone.forNumber(1_234).message)
    }
    @Test fun sacredNumbers_orderedAscending() {
        assertEquals(listOf(108, 1_080, 2_160, 10_000, 33_333, 88_000, 108_000),
            CollectiveMilestone.SACRED_NUMBERS)
    }
}
```

- [ ] **Step 2: Run — FAIL.**

- [ ] **Step 3: Create class**

```kotlin
// CollectiveMilestone.kt
data class CollectiveMilestone(
    val number: Int,
    val message: String,
) {
    companion object {
        val SACRED_NUMBERS = listOf(108, 1_080, 2_160, 10_000, 33_333, 88_000, 108_000)

        fun forNumber(number: Int): CollectiveMilestone {
            val message = when (number) {
                108 -> "108 walks. One for each bead on the mala."
                1_080 -> "1,080 walks. The mala, turned ten times."
                2_160 -> "2,160 walks. One full age of the zodiac."
                10_000 -> "10,000 walks. 万 — all things."
                33_333 -> "33,333 walks. The Saigoku pilgrimage, a thousandfold."
                88_000 -> "88,000 walks. Shikoku's 88 temples, a thousand times over."
                108_000 -> "108,000 walks. The great mala, complete."
                else -> String.format(Locale.US, "%,d walks. You were one of them.", number)
            }
            return CollectiveMilestone(number, message)
        }
    }
}
```

Use Unicode escape `万` for the kanji to make UTF-8 source encoding non-load-bearing. The em-dash uses `—` for the same reason. Tests verify the rendered string equals the literal.

- [ ] **Step 4: Run — PASS.**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/data/collective/CollectiveMilestone.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/data/collective/CollectiveMilestoneTest.kt
git commit -m "feat(collective): CollectiveMilestone sacred-numbers + verbatim iOS messages (Stage 11-B)"
```

---

### Task 11: CollectiveMilestoneDetector

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/data/collective/CollectiveMilestoneDetector.kt`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/data/collective/CollectiveMilestoneDetectorTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
class CollectiveMilestoneDetectorTest {
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var json: Json
    private lateinit var cacheStore: CollectiveCacheStore
    private lateinit var detector: CollectiveMilestoneDetector
    private lateinit var scope: CoroutineScope
    private lateinit var file: File

    @Before
    fun setUp() {
        // Robolectric + DataStore using test scope (Stage 7-D fix)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        file = File.createTempFile("milestone-", ".pb")
        dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        json = Json { ignoreUnknownKeys = true }
        cacheStore = CollectiveCacheStore(dataStore, json)
        detector = CollectiveMilestoneDetector(cacheStore)
    }
    @After fun tearDown() { scope.cancel(); file.delete() }

    @Test
    fun firstSacredNumberCrossingEmits() = runTest {
        detector.milestone.test(timeout = 10.seconds) {
            assertNull(awaitItem())
            detector.check(108)
            assertEquals(108, awaitItem()?.number)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun alreadySeenNumberDoesNotEmit() = runTest {
        cacheStore.setLastSeenCollectiveWalks(108)
        detector.check(200)
        assertNull(detector.milestone.value)
    }

    @Test
    fun multipleNumbersCrossedEmitsLowestFirst() = runTest {
        detector.check(2500) // crosses 108, 1080, 2160
        assertEquals(108, detector.milestone.value?.number)
        assertEquals(108, cacheStore.firstReadyLastSeenCollectiveWalks())
        // Next fetch with same total should advance to 1080 (because lastSeen=108<1080)
        detector.clear()
        detector.check(2500)
        assertEquals(1080, detector.milestone.value?.number)
    }

    @Test
    fun checkUsesSuspendingFirstReadyAvoidsColdStartRace() = runTest {
        // Pre-seed lastSeen=108 BEFORE detector first reads
        cacheStore.setLastSeenCollectiveWalks(108)
        // Fresh detector instance — should not emit on totalWalks=108
        val freshDetector = CollectiveMilestoneDetector(cacheStore)
        freshDetector.check(108)
        assertNull(freshDetector.milestone.value)
    }

    @Test
    fun setLastSeenThrowingDoesNotPropagateToCheck() = runTest {
        val throwingStore = object : CollectiveCacheStoreFacade {
            override suspend fun firstReadyLastSeenCollectiveWalks() = 0
            override suspend fun setLastSeenCollectiveWalks(value: Int) { error("boom") }
        }
        // Note: requires extracting an interface CollectiveCacheStoreFacade
        // OR using a Mockito spy — implementer's choice. If interface, the
        // detector constructor must accept it.
    }

    @Test
    fun clearResetsMilestoneToNull() = runTest {
        detector.check(108)
        assertNotNull(detector.milestone.value)
        detector.clear()
        assertNull(detector.milestone.value)
    }
}
```

The "throwing-store" test is best implemented by wrapping the relevant subset of `CollectiveCacheStore` in a small interface (`MilestoneStore` or similar) that the detector consumes. Avoids spying on a large concrete class.

- [ ] **Step 2: Run — FAIL.**

- [ ] **Step 3: Implement**

```kotlin
// CollectiveMilestoneDetector.kt
@Singleton
class CollectiveMilestoneDetector @Inject constructor(
    private val cacheStore: CollectiveCacheStore,
) {
    private val _milestone = MutableStateFlow<CollectiveMilestone?>(null)
    val milestone: StateFlow<CollectiveMilestone?> = _milestone.asStateFlow()

    suspend fun check(totalWalks: Int) {
        try {
            val lastSeen = cacheStore.firstReadyLastSeenCollectiveWalks()
            for (number in CollectiveMilestone.SACRED_NUMBERS) {
                if (totalWalks >= number && lastSeen < number) {
                    cacheStore.setLastSeenCollectiveWalks(number)
                    _milestone.value = CollectiveMilestone.forNumber(number)
                    break
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "milestone check failed", t)
        }
    }

    fun clear() { _milestone.value = null }

    private companion object { const val TAG = "MilestoneDetector" }
}
```

- [ ] **Step 4: Run — PASS.**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/data/collective/CollectiveMilestoneDetector.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/data/collective/CollectiveMilestoneDetectorTest.kt
git commit -m "feat(collective): CollectiveMilestoneDetector StateFlow detection (Stage 11-B)"
```

---

### Task 12: CollectiveRepository wires detector

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/data/collective/CollectiveRepository.kt`
- Modify: `app/src/test/java/org/walktalkmeditate/pilgrim/data/collective/CollectiveRepositoryTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
@Test
fun forceFetch_callsDetectorCheckWithFreshTotalWalks() = runTest {
    val checked = mutableListOf<Int>()
    val detector = object : MilestoneChecking {
        override suspend fun check(totalWalks: Int) { checked += totalWalks }
    }
    val repo = newRepo(detector = detector, fetchedStats = stats(totalWalks = 108))
    repo.forceFetch()
    assertEquals(listOf(108), checked)
}

@Test
fun forceFetch_detectorThrowingDoesNotFailFetch() = runTest {
    val detector = object : MilestoneChecking {
        override suspend fun check(totalWalks: Int) { error("boom") }
    }
    val repo = newRepo(detector = detector, fetchedStats = stats(totalWalks = 108))
    val stats = repo.forceFetch()
    assertEquals(108, stats.totalWalks)
}
```

Extract `interface MilestoneChecking { suspend fun check(totalWalks: Int) }` for testability — `CollectiveMilestoneDetector` implements it.

- [ ] **Step 2: Run — FAIL.**

- [ ] **Step 3: Implement**

In `CollectiveMilestoneDetector.kt`:

```kotlin
interface MilestoneChecking { suspend fun check(totalWalks: Int) }

@Singleton
class CollectiveMilestoneDetector @Inject constructor(...) : MilestoneChecking { ... }
```

In `CollectiveRepository.kt`, inject `MilestoneChecking`. After successful `cacheStore.writeStats(...)`, before returning, add:

```kotlin
try {
    milestoneChecker.check(stats.totalWalks)
} catch (ce: CancellationException) {
    throw ce
} catch (t: Throwable) {
    Log.w(TAG, "milestone check threw — ignoring", t)
}
```

Mirror in both `forceFetch()` and `fetchIfStale()` (only on the cache-miss / TTL-bypass path that ACTUALLY fetched).

Hilt binding: `@Binds @Singleton abstract fun bindMilestoneChecking(impl: CollectiveMilestoneDetector): MilestoneChecking` in `CollectiveModule.kt`.

- [ ] **Step 4: Run — PASS.**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/data/collective/CollectiveMilestoneDetector.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/data/collective/CollectiveRepository.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/di/CollectiveModule.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/data/collective/CollectiveRepositoryTest.kt
git commit -m "feat(collective): repo wires milestone detector after fetch (Stage 11-B)"
```

---

### Task 13: BellPlaying interface + BellPlayer.play(scale)

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/audio/BellPlaying.kt`
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/audio/BellPlayer.kt`
- Modify: `app/src/test/java/org/walktalkmeditate/pilgrim/audio/BellPlayerTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
@Test
fun playWithScaleMultipliesByUserBellVolume() {
    val recorded = mutableListOf<Float>()
    Shadows.shadowOf(MediaPlayer::class.java).onSetVolume { l, _ -> recorded += l }
    soundsPrefs.bellVolume.value = 0.5f
    bellPlayer.play(scale = 0.4f)
    Robolectric.flushForegroundThreadScheduler()
    assertEquals(0.2f, recorded.last(), 0.001f) // 0.4 × 0.5
}

@Test
fun playWithScaleZero_volumeZero() {
    val recorded = mutableListOf<Float>()
    Shadows.shadowOf(MediaPlayer::class.java).onSetVolume { l, _ -> recorded += l }
    soundsPrefs.bellVolume.value = 0f
    bellPlayer.play(scale = 0.4f)
    Robolectric.flushForegroundThreadScheduler()
    assertEquals(0f, recorded.last(), 0.001f)
}

@Test
fun play_noScaleParam_unchanged() {
    val recorded = mutableListOf<Float>()
    Shadows.shadowOf(MediaPlayer::class.java).onSetVolume { l, _ -> recorded += l }
    soundsPrefs.bellVolume.value = 0.7f
    bellPlayer.play()
    Robolectric.flushForegroundThreadScheduler()
    assertEquals(0.7f, recorded.last(), 0.001f)
}
```

(Pseudo-API for ShadowMediaPlayer; use the actual Robolectric pattern already used in `BellPlayerTest`.)

- [ ] **Step 2: Run — FAIL.**

- [ ] **Step 3: Implement**

`BellPlaying.kt`:

```kotlin
interface BellPlaying {
    fun play()
    /**
     * Fire the bell at [scale] × user's bell-volume preference. Used
     * by the milestone overlay (scale=0.4f) so a user who muted bells
     * still hears no sound. Default body delegates to play() — existing
     * fakes continue to work.
     */
    fun play(scale: Float) { play() }
}
```

`BellPlayer.kt` — refactor `play()` body to share setup with new `play(scale)`:

```kotlin
override fun play() = playInternal(scale = 1.0f)
override fun play(scale: Float) = playInternal(scale = scale)

private fun playInternal(scale: Float) {
    // … existing setup down to player.prepare() …
    val userBellVolume = soundsPreferences.bellVolume.value.coerceIn(0f, 1f)
    val effective = (scale.coerceIn(0f, 1f) * userBellVolume).coerceIn(0f, 1f)
    try {
        player.setVolume(effective, effective)
    } catch (t: Throwable) {
        Log.w(TAG, "MediaPlayer setVolume failed", t)
    }
    // … start() …
}
```

- [ ] **Step 4: Run — PASS.**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/audio/BellPlaying.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/audio/BellPlayer.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/audio/BellPlayerTest.kt
git commit -m "feat(audio): BellPlayer.play(scale) for milestone overlay (Stage 11-B)"
```

---

### Task 14: PracticeSummaryHeader milestone overlay

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/PracticeSummaryHeader.kt`
- Test: `app/src/androidTest/java/org/walktalkmeditate/pilgrim/ui/settings/PracticeSummaryHeaderMilestoneTest.kt` (new — Compose UI test)

- [ ] **Step 1: Write failing tests**

```kotlin
@RunWith(AndroidJUnit4::class)
class PracticeSummaryHeaderMilestoneTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun bellFiresOncePerNumber_evenAcrossRecompose() {
        var bellCount = 0
        val milestone = mutableStateOf<CollectiveMilestone?>(CollectiveMilestone.forNumber(108))
        composeRule.setContent {
            PracticeSummaryHeader(
                walkCount = 10, totalDistanceMeters = 0.0,
                totalMeditationSeconds = 0L, firstWalkInstant = null,
                distanceUnits = UnitSystem.Metric, collectiveStats = null,
                milestone = milestone.value,
                onMilestoneShown = { bellCount++ },
                onMilestoneDismiss = {},
            )
        }
        composeRule.waitForIdle()
        assertEquals(1, bellCount)
        // Force recompose with same milestone — must not refire
        milestone.value = CollectiveMilestone.forNumber(108)
        composeRule.waitForIdle()
        assertEquals(1, bellCount)
        // New number — must fire
        milestone.value = CollectiveMilestone.forNumber(1_080)
        composeRule.waitForIdle()
        assertEquals(2, bellCount)
    }

    @Test
    fun eightSecondAutoDismiss() {
        var dismissCount = 0
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            PracticeSummaryHeader(
                walkCount = 10, totalDistanceMeters = 0.0,
                totalMeditationSeconds = 0L, firstWalkInstant = null,
                distanceUnits = UnitSystem.Metric, collectiveStats = null,
                milestone = CollectiveMilestone.forNumber(108),
                onMilestoneShown = {},
                onMilestoneDismiss = { dismissCount++ },
            )
        }
        composeRule.mainClock.advanceTimeBy(7_999L)
        assertEquals(0, dismissCount)
        composeRule.mainClock.advanceTimeBy(2L)
        assertEquals(1, dismissCount)
    }

    @Test
    fun milestoneTextRendersVerbatim() {
        composeRule.setContent {
            PracticeSummaryHeader(
                walkCount = 10, totalDistanceMeters = 0.0,
                totalMeditationSeconds = 0L, firstWalkInstant = null,
                distanceUnits = UnitSystem.Metric, collectiveStats = null,
                milestone = CollectiveMilestone.forNumber(108),
                onMilestoneShown = {}, onMilestoneDismiss = {},
            )
        }
        composeRule.onNodeWithText("108 walks. One for each bead on the mala.")
            .assertIsDisplayed()
    }
}
```

- [ ] **Step 2: Run — FAIL.**

- [ ] **Step 3: Update composable**

Modify `PracticeSummaryHeader.kt`:

```kotlin
@Composable
fun PracticeSummaryHeader(
    walkCount: Int,
    totalDistanceMeters: Double,
    totalMeditationSeconds: Long,
    firstWalkInstant: Instant?,
    distanceUnits: UnitSystem,
    collectiveStats: CollectiveStats?,
    milestone: CollectiveMilestone? = null,
    onMilestoneShown: (CollectiveMilestone) -> Unit = {},
    onMilestoneDismiss: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // existing content unchanged

        AnimatedVisibility(
            visible = milestone != null,
            enter = fadeIn(animationSpec = tween(500, easing = FastOutSlowInEasing)),
            exit = fadeOut(animationSpec = tween(500, easing = FastOutSlowInEasing)),
        ) {
            milestone?.let { ms ->
                val firedFor = rememberSaveable { mutableStateOf<Int?>(null) }
                LaunchedEffect(ms.number) {
                    if (firedFor.value != ms.number) {
                        firedFor.value = ms.number
                        onMilestoneShown(ms)
                    }
                    delay(8_000L)
                    onMilestoneDismiss()
                }
                Text(
                    text = ms.message,
                    style = pilgrimType.body.copy(fontStyle = FontStyle.Italic),
                    color = pilgrimColors.stone,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .background(
                            color = pilgrimColors.moss.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
        }
    }
}
```

If `pilgrimColors.moss` is not yet a defined token, fall back to the existing closest hue (`pilgrimColors.parchment` darkened, or check theme tokens). Spec required `moss` — verify it exists in `ui/theme/PilgrimColors.kt`; if not, add it as a follow-up token mapping iOS `Color.moss`.

- [ ] **Step 4: Run — PASS.**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/PracticeSummaryHeader.kt \
        app/src/androidTest/java/org/walktalkmeditate/pilgrim/ui/settings/PracticeSummaryHeaderMilestoneTest.kt
git commit -m "feat(settings): PracticeSummaryHeader milestone overlay (Stage 11-B)"
```

---

### Task 15: SettingsViewModel exposes milestone passthrough + SettingsScreen wires it

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsViewModel.kt`
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsScreen.kt`
- Modify: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsViewModelTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
@Test
fun milestone_passesThroughFromDetector() = runTest {
    val detectorMilestone = MutableStateFlow<CollectiveMilestone?>(null)
    val detector = object : CollectiveMilestoneDetector { /* fake exposing milestone */ }
    val vm = newViewModel(milestoneDetector = detector)
    vm.milestone.test {
        assertNull(awaitItem())
        detectorMilestone.value = CollectiveMilestone.forNumber(108)
        assertEquals(108, awaitItem()?.number)
        cancelAndIgnoreRemainingEvents()
    }
}

@Test
fun dismissMilestone_clearsDetector() = runTest {
    var cleared = false
    val detector = /* fake whose clear() flips cleared = true */
    val vm = newViewModel(milestoneDetector = detector)
    vm.dismissMilestone()
    assertTrue(cleared)
}
```

- [ ] **Step 2: Run — FAIL.**

- [ ] **Step 3: Wire VM + Screen**

`SettingsViewModel.kt` — inject `CollectiveMilestoneDetector`, expose:

```kotlin
val milestone: StateFlow<CollectiveMilestone?> = milestoneDetector.milestone

fun dismissMilestone() { milestoneDetector.clear() }
```

`SettingsScreen.kt` (line ~117):

```kotlin
val milestone by viewModel.milestone.collectAsStateWithLifecycle()

PracticeSummaryHeader(
    walkCount = practiceSummary.walkCount,
    totalDistanceMeters = practiceSummary.totalDistanceMeters,
    totalMeditationSeconds = practiceSummary.totalMeditationSeconds,
    firstWalkInstant = practiceSummary.firstWalkInstant,
    distanceUnits = distanceUnits,
    collectiveStats = stats,
    milestone = milestone,
    onMilestoneShown = { _ -> bellPlayer.play(scale = 0.4f) },
    onMilestoneDismiss = viewModel::dismissMilestone,
)
```

`bellPlayer` reference: either `hiltViewModel`-provided through the VM (cleaner) or injected in the screen via `LocalBellPlayer` Composition Local. Cleanest: have the VM own the bell-fire side effect — replace `onMilestoneShown` with `viewModel::onMilestoneShown` where the VM calls `bellPlayer.play(0.4f)`. The VM already has DI access; the Compose layer stays declarative.

Final wiring (preferred):

```kotlin
// SettingsViewModel.kt
fun onMilestoneShown(milestone: CollectiveMilestone) {
    bellPlayer.play(scale = 0.4f)
}

// SettingsScreen.kt
PracticeSummaryHeader(
    // …
    milestone = milestone,
    onMilestoneShown = viewModel::onMilestoneShown,
    onMilestoneDismiss = viewModel::dismissMilestone,
)
```

- [ ] **Step 4: Run — PASS. Also run full unit test suite + build:**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsViewModel.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsScreen.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsViewModelTest.kt
git commit -m "feat(settings): wire milestone overlay into SettingsScreen (Stage 11-B)"
```

---

### Task 16: Update QA checklist file

**Files:**
- Create: `docs/superpowers/qa/2026-04-30-stage-11-pilgrim-roundtrip.md`

- [ ] **Step 1: Copy the "Item 1: Cross-Platform Round-Trip QA" section from the spec into a standalone QA-checklist doc.**

This is documentation only — no code, no test. The doc is the deliverable for Item #1. Reference it from the PR description.

- [ ] **Step 2: Skip — doc only.**

- [ ] **Step 3: Skip — doc only.**

- [ ] **Step 4: Skip — doc only.**

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/qa/2026-04-30-stage-11-pilgrim-roundtrip.md
git commit -m "docs(qa): cross-platform .pilgrim round-trip checklist (Stage 11-C)"
```

---

### Task 17: Release build smoke + ProGuard verification

**Files:**
- (No edits expected; verifying only.)

- [ ] **Step 1: Run release assembly**

```bash
./gradlew :app:assembleRelease
```

Expected: PASS. No new ProGuard rules required since no new `@Serializable` classes were added (CollectiveMilestone is plain data class).

- [ ] **Step 2: Run all unit + instrumented tests**

```bash
./gradlew :app:testDebugUnitTest :app:connectedAndroidTest
```

Expected: PASS.

- [ ] **Step 3: Skip.**

- [ ] **Step 4: Skip.**

- [ ] **Step 5: No commit needed if clean.**

---

## Self-review checklist (controller, after all tasks done)

- [ ] Spec coverage: every spec section has at least one task implementing it.
- [ ] No placeholders in tasks.
- [ ] Type/method signatures match across tasks (e.g., `WalkMetricsCaching.computeAndPersist(walkId: Long)` consistent in Task 4 / 5 / 6 / 8).
- [ ] Migration column order matches Walk.kt entity order (distanceMeters first).
- [ ] All commit messages tagged with stage label (`Stage 11-A` / `Stage 11-B` / `Stage 11-C`).
- [ ] PR title: `feat(walks+collective): cache cols + milestone overlay (Stage 11)`.
- [ ] PR description references both spec AND QA checklist files.
