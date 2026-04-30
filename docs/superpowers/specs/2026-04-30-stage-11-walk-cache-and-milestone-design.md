# Stage 11 — Walk-Table Cache Columns + Collective Milestone Overlay

**Date:** 2026-04-30
**Bundle:** Items #1 + #2 + #3 from the post-Phase-10 deferred-parity list. Combined into one PR.

## Context

Three iOS-parity gaps closed together because they share a checkpoint (Settings → Practice Summary Header) and a verification surface (cross-platform `.pilgrim` round-trip):

1. **Cross-platform round-trip QA** — manual iPhone↔Android paired-device test. Validates Stage 10-I (`.pilgrim` builder/importer, PR #68) end-to-end. No code; checklist-driven.
2. **Walk-table cache columns** — Add `distance_meters` (REAL) and `meditation_seconds` (INTEGER) to the `walks` table. Computed at finalize. Killing the N+1 in `SettingsViewModel.practiceSummary` and `AboutViewModel.stats` that scans every walk's RouteDataSamples + ActivityIntervals on every Settings/About open.
3. **Milestone overlay** — Sacred-numbers toast + bell at 0.4 volume + 8-second easeInOut auto-dismiss, surfaced on `PracticeSummaryHeader` after a successful collective fetch. iOS `[108, 1080, 2160, 10000, 33333, 88000, 108000]` with verbatim message strings (including the kanji 万 in the 10,000 message).

iOS reference triple-checked against actual files:
- `pilgrim-ios/Pilgrim/Models/Data/DataModels/Versions/PilgrimV7.swift:107-150` — Walk entity
- `pilgrim-ios/Pilgrim/Models/Data/NewWalk.swift:40-42, 63` — meditateDuration finalize compute (clamped)
- `pilgrim-ios/Pilgrim/Models/Walk/WalkBuilder/Components/LocationManagement.swift:236-249` — distance live-accumulation
- `pilgrim-ios/Pilgrim/Models/Collective/CollectiveCounterService.swift:163-174` — `checkMilestone(_:)`
- `pilgrim-ios/Pilgrim/Models/Collective/PilgrimageProgress.swift:45-71` — `CollectiveMilestone.forNumber(_:)`
- `pilgrim-ios/Pilgrim/Scenes/Settings/PracticeSummaryHeader.swift:60-93` — overlay UI + `playMilestoneBell()`
- `pilgrim-ios/Pilgrim/Models/Preferences/UserPreferences.swift:61` — `lastSeenCollectiveWalks` default 0

## Goals

- Eliminate N+1 walk-scan on every Settings/About open by reading cached aggregates from the row.
- Surface collective milestones with bell + 8s overlay, byte-faithful to iOS messaging and animation timing.
- Validate cross-platform `.pilgrim` round-trip end-to-end on real devices, not just unit tests.

## Non-goals

- **Per-walk talk duration cache.** iOS caches `talkDuration` too, but Android's recordings are already enumerated via DAO query (`recordingDao.observeAllVoiceRecordings`) without per-walk scanning, so the N+1 doesn't exist for talk. Defer.
- **Per-walk active/pause duration cache.** Android's `practiceSummary` doesn't read these — Walk's `endTimestamp - startTimestamp` is the only duration consumed today. Defer.
- **iOS-style `distance` sentinel of -1.** Android uses nullable Double; null means "not yet computed."
- **Milestone backend metadata.** iOS's milestone is purely client-side (sacred-numbers compare in-app). Don't add `lastSeenWalks` to `CollectiveStats` JSON.
- **Bell-asset selection.** iOS plays the user's `walkStartBellId` from a downloaded manifest. Android has no walk-start-bell selection (Stage 5-B used a single bundled `R.raw.bell`). Reuse `R.raw.bell` for milestone — accepted divergence; the audible result is "a temple bell at lower volume," which preserves the experiential intent. If Stage N adds bell selection later, route the milestone through the same selection.
- **Bell volume scaling vs iOS flat 0.4.** iOS hard-codes 0.4 on a bell asset that has no user-volume preference. Android multiplies 0.4 × user `bellVolume` so users who muted bells stay muted. Accepted divergence — preserves Android user expectations.
- **Migration backfill on Walk start/end timestamp anomalies.** If a walk has < 2 RouteDataSamples or no MEDITATING intervals, that's normal — the row stores `null` (not 0). Readers fall back to the existing on-the-fly compute when null.

---

## Item 2: Walk-Table Cache Columns

### Schema

`Walk.kt` gains two nullable columns:

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

Column types:
- `distance_meters REAL` (Double meters, nullable). Mirrors iOS `_distance` (Double). Null means "not yet computed."
- `meditation_seconds INTEGER` (Long seconds, nullable). Diverges from iOS `_meditateDuration` (Double seconds) to match Android's existing convention — `SettingsViewModel.practiceSummary.totalMeditationSeconds: Long` already uses Long seconds. The fractional precision is ≤ 1 second per walk, which is below display granularity ("X minutes meditated").

No new indices. These cols are aggregated, not searched.

### Migration

`MIGRATION_4_5` is **explicit** (not AutoMigration). Database version bumps from 4 → 5.

```kotlin
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Room's RoomOpenHelper auto-wraps migrate() in a transaction.
        // Don't add a manual one — it would deadlock the open helper.
        // Column-declaration order MUST match Walk.kt entity order
        // (distanceMeters first, then meditationSeconds) — Room hashes
        // include column order in the schema validation step.
        db.execSQL("ALTER TABLE walks ADD COLUMN distance_meters REAL")
        db.execSQL("ALTER TABLE walks ADD COLUMN meditation_seconds INTEGER")
        // Backfill: nulls left here. Lazy compute happens on next walk
        // finalize or via WalkMetricsBackfillCoordinator. See below.
    }
}
```

**Migration runs in fast-path mode** — only ALTER TABLE statements, no row scan. Backfill is **lazy** to keep migration time bounded (a user with 500 walks shouldn't pay a 5-second migration).

Rationale: Android has no shipped users yet (per CLAUDE.md "drop the OutRun→Pilgrim migration chain entirely"), so existing-data backfill is a self-test concern, not a user-impact concern. The lazy-compute path doubles as the production reader fallback.

### Finalize hook

`WalkFinalizationObserver.runFinalize()` gains a metric-cache step **after** the existing collective-POST + widget refresh. Wraps in `try { … } catch (CancellationException) { throw } catch (Throwable) { Log.w(…) }` so a cache miss never blocks finalize.

```kotlin
// New helper: WalkMetricsCache.kt
@Singleton
class WalkMetricsCache @Inject constructor(
    private val walkRepository: WalkRepository,
    private val walkDao: WalkDao,
    private val walkEventDao: WalkEventDao,
) {
    suspend fun computeAndPersist(walkId: Long) {
        val walk = walkDao.getById(walkId) ?: return
        // Skip in-progress walks — clamp ceiling is undefined while
        // endTimestamp is null. Finalize hook always passes finished
        // walks; coordinator-driven backfill filters in the producer
        // side, but this is a defense-in-depth guard.
        if (walk.endTimestamp == null) return

        val samples = walkRepository.locationSamplesFor(walkId)
        val intervals = walkRepository.activityIntervalsFor(walkId)
        val events = walkEventDao.eventsForWalk(walkId)

        val distance = WalkDistanceCalculator.computeDistanceMeters(samples)
        val meditation = computeMeditationSeconds(intervals, walk, events)
        walkDao.updateAggregates(walkId, distance, meditation)
    }

    private fun computeMeditationSeconds(
        intervals: List<ActivityInterval>,
        walk: Walk,
        events: List<WalkEvent>,
    ): Long {
        val rawMillis = intervals
            .filter { it.activityType == ActivityType.MEDITATING }
            .sumOf { it.endTimestamp - it.startTimestamp }
        val rawSeconds = rawMillis / 1_000L

        // iOS clamp (NewWalk.swift:42 — "min(rawMeditateDuration,
        // durations.activeDuration)"). Android computes activeDuration
        // = wallClock - sum(pause-resume gaps) so the clamp is identical
        // to iOS, not the looser wall-clock-only ceiling.
        val activeDurationSeconds = computeActiveDurationSeconds(walk, events)
        return rawSeconds.coerceAtMost(activeDurationSeconds).coerceAtLeast(0L)
    }

    /**
     * Wall-clock duration minus sum of (RESUMED.timestamp -
     * preceding-PAUSED.timestamp). Pairs PAUSED→RESUMED in timestamp
     * order; an unpaired trailing PAUSED is closed at endTimestamp.
     */
    private fun computeActiveDurationSeconds(
        walk: Walk,
        events: List<WalkEvent>,
    ): Long {
        val end = walk.endTimestamp ?: return 0L
        val wallClockMs = (end - walk.startTimestamp).coerceAtLeast(0L)
        val sortedEvents = events.sortedBy { it.timestamp }
        var pausedSinceMs: Long? = null
        var pausedTotalMs = 0L
        for (event in sortedEvents) {
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
        // Unpaired trailing PAUSED: walk ended while paused, count gap
        // through endTimestamp.
        pausedSinceMs?.let { pausedTotalMs += (end - it).coerceAtLeast(0L) }
        return ((wallClockMs - pausedTotalMs).coerceAtLeast(0L)) / 1_000L
    }
}
```

`WalkDao` gains `updateAggregates(id, distanceMeters, meditationSeconds)`:

```kotlin
@Query("UPDATE walks SET distance_meters = :distanceMeters, meditation_seconds = :meditationSeconds WHERE id = :id")
suspend fun updateAggregates(id: Long, distanceMeters: Double?, meditationSeconds: Long?)
```

`WalkFinalizationObserver` calls it after collective-POST returns (or fails — cache compute is independent):

```kotlin
// Existing finalize body…
postCollectiveCounter(state.walk)
refreshWidget()
// NEW:
try {
    walkMetricsCache.computeAndPersist(walkId)
} catch (cancel: CancellationException) {
    throw cancel
} catch (t: Throwable) {
    Log.w(TAG, "metric cache compute failed for walk $walkId", t)
}
```

### Reader migration

`SettingsViewModel.practiceSummary` and `AboutViewModel.stats` read cached cols directly:

```kotlin
val totalDistanceMeters = walks.sumOf { it.distanceMeters ?: 0.0 }
val totalMeditationSeconds = walks.sumOf { it.meditationSeconds ?: 0L }
```

Stale rows with null cache cols contribute 0 to the sum until the backfill coordinator (below) catches up. Acceptable: the partial sum is monotonically lower than the truth, never wrong-direction. Convergence is fast (one walk per coordinator tick on a debounced channel).

### Backfill coordinator

A new `@Singleton WalkMetricsBackfillCoordinator` runs at app start (Hilt-eager singleton). It owns the lazy backfill — VMs do NOT launch backfill coroutines themselves, eliminating the multi-VM thrash + race risk.

```kotlin
@Singleton
class WalkMetricsBackfillCoordinator @Inject constructor(
    private val walkRepository: WalkRepository,
    private val cache: WalkMetricsCache,
    @CollectiveRepoScope private val scope: CoroutineScope,
) {
    private val inflight = Collections.synchronizedSet(HashSet<Long>())

    fun start() {
        scope.launch(Dispatchers.IO) {
            walkRepository.observeAllWalks()
                .map { walks ->
                    walks.firstOrNull { walk ->
                        walk.endTimestamp != null && (
                            walk.distanceMeters == null || walk.meditationSeconds == null
                        )
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

Hilt-bind via `@Provides` in `WalkModule` and call `start()` from `PilgrimApplication.onCreate()` after Hilt graph initialization. Coordinator stays single-instance app-scoped; observes Room hot Flow; on each emission, picks the FIRST stale walk (deterministic), dedupes via inflight Set, kicks off compute. After UPDATE persists, Room re-emits → next stale walk picked → repeat. Converges to zero stale walks within ~N×IO time for N stale rows.

**Why coordinator vs VM-side launch:**
- Single producer eliminates the "VM scope cancels before backfill UPDATE persists" race.
- Inflight dedup prevents same-id double-launch on rapid Room re-emissions.
- `distinctUntilChanged()` on the next-stale-id collapses redundant emissions for the same id.
- Survives VM lifecycle (rotation, nav-away). One coordinator per app process.
- `@CollectiveRepoScope` is appropriate here: app-scoped, survives VM destruction, doesn't survive process death (which is fine — coordinator restarts on next launch and re-observes).

The coordinator serves three roles:
1. **Migration backfill** — pre-existing rows fill in over time post-launch.
2. **Defense against finalize failure** — if `WalkFinalizationObserver` crashed before `computeAndPersist`, the coordinator self-heals on next observation.
3. **Schema-evolution safety** — future stage adds another aggregate, pattern accommodates.

### `.pilgrim` builder integration

`PilgrimPackageConverter.convert()` currently calls `WalkDistanceCalculator.computeDistanceMeters()` at export time and uses `sumActivityDuration` (raw, unclamped) for meditation. Stage 11 aligns both paths:

**Distance:**
- Read `walk.distanceMeters` if non-null (cache hit).
- Fall back to `WalkDistanceCalculator.computeDistanceMeters(samples)` if null (cache miss).

The two paths produce IDENTICAL output for the same walk: cache write was computed by the same `WalkDistanceCalculator` at finalize. Cache hit eliminates the per-walk haversine pass at export time.

**Meditation:**
- Read `walk.meditationSeconds` if non-null (cache hit).
- Fall back to a NEW shared helper `WalkMetricsCache.computeMeditationSeconds(intervals, walk, events)` (the same private function used for cache writes, exposed as `internal`). This applies the iOS clamp.

The existing `sumActivityDuration` raw-sum path is REMOVED for the meditation column. Reason: cache-vs-live divergence on corrupt data (raw > activeDuration) breaks the byte-equivalence claim AND produces different export output than iOS for the same walk. Aligning both paths to clamp matches iOS exactly.

**Byte-equivalence test:** `PilgrimPackageConverterTest.exportEquivalentBetweenCacheStates` exports a clean walk twice — once with cache populated, once cleared — and asserts identical JSON output for both `distance` and `meditateDuration` fields.

**Corruption test:** `PilgrimPackageConverterTest.exportClampsCorruptMeditation` constructs a walk with `rawMeditation = 50min`, `activeDuration = 18min`, asserts the exported `meditateDuration = 1080.0` (clamped), regardless of cache state.

### Tests

1. `WalkMetricsCacheTest` — Robolectric, real Room. Insert walk + samples + intervals + events, call `computeAndPersist`, assert row has expected aggregates.
2. `WalkMetricsCacheTest.meditationClampToActiveDuration` — 30-minute walk with 50-minute MEDITATING interval (corruption case), 12-minute pause via PAUSED→RESUMED events. Assert clamped to 18 × 60 = 1080 seconds (active = wall - pause).
3. `WalkMetricsCacheTest.skipsInProgressWalks` — walk with `endTimestamp = null`, assert `computeAndPersist` returns without writing.
4. `WalkMetricsCacheTest.computeActiveDurationSeconds.unpairedTrailingPause` — walk paused at end (no RESUMED), assert pause closed at endTimestamp.
5. `MIGRATION_4_5` test — uses `MigrationTestHelper`, opens v4 schema with one walk, runs migration, asserts new cols exist + null + old data unchanged.
6. `WalkMetricsBackfillCoordinatorTest.dedupsConcurrentEmissionsForSameId` — emit walks list with one stale walk twice rapidly, assert `computeAndPersist` invoked once.
7. `WalkMetricsBackfillCoordinatorTest.skipsInProgressWalks` — coordinator filter excludes endTimestamp == null.
8. `SettingsViewModelTest.cacheHitUsesStoredAggregates` — VM reads from pre-populated cache cols, asserts no per-walk DAO scan.
9. `PilgrimPackageConverterTest.exportEquivalentBetweenCacheStates` — same clean walk, twice (cache populated vs cleared), assert identical JSON output bytes for `distance` AND `meditateDuration`.
10. `PilgrimPackageConverterTest.exportClampsCorruptMeditation` — corrupt walk (raw 50min, active 18min), assert exported `meditateDuration = 1080.0` regardless of cache state.

---

## Item 3: Milestone Overlay

### Sacred numbers + messages

New file `app/src/main/java/org/walktalkmeditate/pilgrim/data/collective/CollectiveMilestone.kt`:

```kotlin
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
                else -> "${"%,d".format(Locale.US, number)} walks. You were one of them."
            }
            return CollectiveMilestone(number, message)
        }
    }
}
```

Strings live in code (not `strings.xml`) for now. iOS doesn't localize these either — the kanji 万 is intentional UTF-8 in the English string. If localization comes to the app later, route through `pluralStringResource` per Stage 10-HI's pattern.

### DataStore key

`lastSeenCollectiveWalks` lives in the existing `CollectiveCacheStore` (datastore name `"collective_counter"`), not a new file. Reduces dispatcher contention and colocates collective-domain prefs.

New key + helpers added to `CollectiveCacheStore.kt`:

```kotlin
// Inside CollectiveCacheStore
val lastSeenCollectiveWalksFlow: Flow<Int> =
    dataStore.data
        .map { it[KEY_LAST_SEEN_COLLECTIVE_WALKS] ?: 0 }
        .distinctUntilChanged()

/**
 * Suspending read of the persisted value. Use this from the detector
 * to AVOID the StateFlow cold-start race (Eagerly seeds to 0 on first
 * subscription; if the detector reads .value before DataStore's first
 * read completes, it returns 0 even when 108 is stored, which would
 * re-fire the milestone).
 */
suspend fun firstReadyLastSeenCollectiveWalks(): Int =
    dataStore.data.map { it[KEY_LAST_SEEN_COLLECTIVE_WALKS] ?: 0 }.first()

suspend fun setLastSeenCollectiveWalks(value: Int) {
    dataStore.edit { it[KEY_LAST_SEEN_COLLECTIVE_WALKS] = value }
}

internal companion object {
    // … existing keys
    val KEY_LAST_SEEN_COLLECTIVE_WALKS = intPreferencesKey("lastSeenCollectiveWalks")
}
```

### MilestoneDetector

New class wires the sacred-numbers loop. Uses `MutableStateFlow` (not `SharedFlow`) so the milestone persists across navigation — matches iOS's `@Published var milestone: CollectiveMilestone?` semantics. SharedFlow with `replay=0` would lose the milestone if the user is on Home tab when the boot fetch fires it.

```kotlin
@Singleton
class CollectiveMilestoneDetector @Inject constructor(
    private val cacheStore: CollectiveCacheStore,
) {
    private val _milestone = MutableStateFlow<CollectiveMilestone?>(null)
    val milestone: StateFlow<CollectiveMilestone?> = _milestone.asStateFlow()

    suspend fun check(totalWalks: Int) {
        // Suspending read avoids StateFlow cold-start race — see
        // firstReadyLastSeenCollectiveWalks() doc.
        val lastSeen = cacheStore.firstReadyLastSeenCollectiveWalks()
        for (number in CollectiveMilestone.SACRED_NUMBERS) {
            if (totalWalks >= number && lastSeen < number) {
                cacheStore.setLastSeenCollectiveWalks(number)
                _milestone.value = CollectiveMilestone.forNumber(number)
                break
            }
        }
    }

    /** Called by VM after the 8s overlay timer expires or on user dismiss. */
    fun clear() { _milestone.value = null }
}
```

`break` after first match mirrors iOS — at most one overlay per fetch even if a user is way over multiple thresholds. Subsequent fetches catch the next one.

### CollectiveRepository hook

`CollectiveRepository.forceFetch()` and `fetchIfStale()` (only the success branch) call `detector.check(stats.totalWalks)` after a fresh fetch. Not after `recordWalk` directly — iOS pattern: post → clear `lastFetchedAt` → fetch → check.

```kotlin
// Inside CollectiveRepository.forceFetch():
val stats = service.fetch()
cache.writeStats(stats, fetchedAtMs = clock.nowMs())
try {
    detector.check(stats.totalWalks)
} catch (ce: CancellationException) {
    throw ce
} catch (t: Throwable) {
    Log.w(TAG, "milestone check failed", t)
}
return stats
```

Order matters: cache write FIRST so observers see fresh stats before the milestone event arrives. `try/catch` around `detector.check` so a DataStore IO failure on `setLastSeenCollectiveWalks` doesn't fail the entire fetch — the milestone is simply lost for this fetch and re-detected next time (lastSeen unchanged means the comparison still triggers). Mirror in `fetchIfStale()` only on cache-miss / TTL-expiry path.

### ViewModel surface

`SettingsViewModel` exposes the detector's milestone StateFlow directly. No mirroring layer — the detector is a `@Singleton` and survives VM lifecycle, so passthrough is correct (and matches Stage 5-G memory: prefer hot-Singleton passthrough for milestone-style nav-relevant flows over `WhileSubscribed` mirrors).

```kotlin
val milestone: StateFlow<CollectiveMilestone?> = milestoneDetector.milestone

fun dismissMilestone() { milestoneDetector.clear() }
```

Auto-dismiss timer lives in the composable — gives the timer the lifecycle of the visible overlay (auto-cancels on navigation-away, which is strictly safer than iOS's leaky `DispatchQueue.main.asyncAfter`). If the user navigates back to Settings within 8s, the StateFlow still holds the milestone (matching iOS @Published persistence) and the LaunchedEffect re-arms a fresh 8s timer — small UX divergence from iOS where the original timer ran in background.

### PracticeSummaryHeader UI

Updated composable signature adds milestone params:

```kotlin
@Composable
fun PracticeSummaryHeader(
    walkCount: Int,
    totalDistanceMeters: Double,
    totalMeditationSeconds: Long,
    firstWalkInstant: Instant?,
    distanceUnits: UnitSystem,
    collectiveStats: CollectiveStats?,
    milestone: CollectiveMilestone?,                // NEW
    onMilestoneShown: (CollectiveMilestone) -> Unit,// NEW — fires bell once per (number, sessionUuid)
    onMilestoneDismiss: () -> Unit,                 // NEW
    modifier: Modifier = Modifier,
) {
    Column(...) {
        // existing content (header, stats, pilgrimage progress, streak flame)

        AnimatedVisibility(
            visible = milestone != null,
            enter = fadeIn(animationSpec = tween(500, easing = FastOutSlowInEasing)),
            exit = fadeOut(animationSpec = tween(500, easing = FastOutSlowInEasing)),
        ) {
            milestone?.let { ms ->
                // Bell-fire latch: the bell rings ONCE per milestone-number
                // appearance per composable lifecycle. iOS PracticeSummaryHeader
                // fires playMilestoneBell() in .onAppear, which only runs when
                // the Text view is first laid out for a given milestone — it
                // does NOT re-fire when only the .number changes (the parent's
                // .animation(value:) drives the crossfade, not a remount). So
                // Android needs the same: bell once per first-visible milestone
                // number, no double-ring on rapid number transitions.
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
                        .padding(top = 8.dp)                          // outer top padding
                        .background(
                            color = pilgrimColors.moss.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 24.dp, vertical = 8.dp),  // inner padding
                )
            }
        }
    }
}
```

Padding values match iOS `Constants.UI.Padding.big` (24dp) + `Padding.small` (8dp). The bell-fire latch via `rememberSaveable` survives rotation: a milestone in flight when the user rotates the device doesn't ring the bell twice.

`Settings`-screen wiring:

```kotlin
PracticeSummaryHeader(
    // … existing params
    milestone = milestone,
    onMilestoneShown = { _ -> bellPlayer.play(volume = 0.4f) },
    onMilestoneDismiss = viewModel::dismissMilestone,
)
```

### BellPlayer.play(volume) — interface + multiplier semantics

`BellPlaying` interface gains a defaulted overload so test fakes don't need to grow:

```kotlin
interface BellPlaying {
    fun play()
    /**
     * Fire the bell at [scale] × user's bell-volume preference. Used
     * for milestone overlay (scale=0.4) so a user who muted bells
     * still hears no sound. Default scales to 1.0 = identical to play().
     */
    fun play(scale: Float) { play() } // default body for existing impls/fakes
}
```

`BellPlayer.kt` overrides the new method. Internally, multiply `scale` by the existing `bellVolume` preference read so user-mute is preserved:

```kotlin
override fun play(scale: Float) {
    // … same setup as play() up through MediaPlayer prepare …
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

Existing `play()` body extracted into a private helper consumed by both overloads, or restructured so `play()` calls `play(scale = 1.0f)`. Either pattern fine — implementer's choice.

**Divergence from iOS:** iOS `BellPlayer.shared.play(asset, volume: 0.4)` is a hard 0.4 (no user-volume multiplier — iOS doesn't have the equivalent `bellVolume` preference). Android multiplies because the user-volume control already exists; ignoring it would surprise users who muted bells. Document this divergence in non-goals.

### Tests

1. `CollectiveMilestoneDetectorTest.firstSacredNumberCrossingEmits` — lastSeen=0, totalWalks=108 → milestone StateFlow emits CollectiveMilestone(108).
2. `CollectiveMilestoneDetectorTest.alreadySeenNumberDoesNotEmit` — lastSeen=108, totalWalks=200 → no emit.
3. `CollectiveMilestoneDetectorTest.multipleNumbersCrossedEmitsLowestFirst` — lastSeen=0, totalWalks=2500 → emits 108 (then 1080 on next fetch, then 2160 on the one after).
4. `CollectiveMilestoneDetectorTest.checkUsesSuspendingFirstReadyAvoidsColdStartRace` — fresh detector + fresh CollectiveCacheStore with lastSeen=108 already in DataStore; immediately call `check(108)`; assert no emit (would falsely emit if detector read `.value.value` from a not-yet-warmed StateFlow).
5. `CollectiveMilestoneDetectorTest.setLastSeenThrowingDoesNotPropagate` — fake CacheStore that throws on setLastSeenCollectiveWalks; assert `check(108)` does NOT throw (caller in CollectiveRepository wraps; detector itself is best-effort).
6. `CollectiveMilestoneTest.forNumber.allMessagesVerbatim` — verbatim string match for each sacred number incl. 万.
7. `CollectiveCacheStoreTest.lastSeenCollectiveWalksRoundTrip` — Robolectric pattern as Stage 7-D's DataStore tests; write 108, read back 108 across instances.
8. `BellPlayerTest.playWithScaleMultipliesByUserBellVolume` — user bellVolume=0.5, scale=0.4 → effective 0.2 on `setVolume`. Use Robolectric ShadowMediaPlayer.
9. `BellPlayerTest.playWithScaleZeroBellVolumeStillSilent` — user bellVolume=0, scale=0.4 → effective 0.
10. Compose UI test — `PracticeSummaryHeaderMilestoneTest.bellFiresOncePerNumber`: render with milestone N=108, assert `onMilestoneShown` invoked once. Update milestone N=108 (same value), assert no second invocation. Update to N=1080, assert one more invocation.
11. Compose UI test — `PracticeSummaryHeaderMilestoneTest.eightSecondAutoDismiss`: render milestone, advance `composeRule.mainClock.advanceTimeBy(8500)`, assert `onMilestoneDismiss` invoked.
12. `CollectiveRepositoryTest.detectorThrowsDoesNotFailFetch` — fake detector that throws on check; force fetch returns stats successfully, milestone StateFlow stays null, no exception bubbles.

---

## Item 1: Cross-Platform `.pilgrim` Round-Trip QA

**No code.** Documented checklist for the user (or QA):

### Setup
- iOS device with Pilgrim 1.3+ (latest TestFlight/App Store).
- Android device with the Stage 11 build installed.
- At least 3 finished walks on each, ideally with: pinned photos (some), voice recordings (some), waypoints (some), an intention text on at least one walk.

### Procedure A — iOS → Android
1. iOS: Settings → Data → Export `.pilgrim` (with photos).
2. AirDrop / Drive / email the file to the Android device.
3. Android: Settings → Data → Import → pick the `.pilgrim`.
4. Verify on Android:
   - Walk count matches iOS (post-import).
   - Each walk's distance: within **5%** of iOS values. Drift is expected — Android's `Location.distanceTo` (Vincenty WGS84) and iOS's `CLLocation.distance(from:)` (haversine) are different geodesy implementations; over a 5km walk the two can differ by 5–20m. Additionally, Android currently sums all stored RouteDataSamples while iOS gates on `checkForAppropriateAccuracy` — bad-fix samples produce extra divergence on poor-GPS walks.
   - Each walk's duration: within **1 second** (wall-clock arithmetic; should match exactly).
   - Each walk's meditation: within **1 second** (both platforms now apply the same iOS-style clamp `min(rawMeditation, activeDuration)`).
   - Pinned photos appear in reliquary (or skip-count surfaced if iOS sourced from Photos that don't resolve on Android — expected).
   - Walk intention text round-trips.
   - Goshuin seal regenerates with the same hash → same visual.

### Procedure B — Android → iOS
1. Android: Settings → Data → Export Pilgrim Package.
2. Move file to iOS device.
3. iOS: import via Files share-sheet → Pilgrim.
4. Verify on iOS:
   - Walk count, distances, meditation match.
   - Photos come through (if iOS resolves the URI — likely fails since Android `MediaStore` URIs are app-private — expected; flag in QA notes).
   - JourneyViewer renders the imported walk with the route stroke + photo thumbnails.

### Acceptance
- Walk metadata (count, distance, durations, intentions, dayIdentifier) round-trips losslessly within stated tolerances.
- Photo bytes embed → render on receiving platform when MediaStore/PhotoKit resolves.
- No silent drops: if photos can't resolve, the post-import alert surfaces a count.

### Out-of-scope failures (noted, not blockers)
- Cross-platform photo URI resolution. Android `content://` URIs are not iOS-resolvable; iOS PHAsset ids are not Android-resolvable. Embedded photo bytes mitigate but in-app reliquary view of those photos is best-effort on the receiving side.
- Voice recording GPS. Android stores per-recording lat/lng; iOS does not. iOS export drops the field on read; Android import accepts the missing field.
- Distance drift up to ~5% due to geodesy-implementation difference + Android lacking accuracy gating. A future stage MAY port iOS's `checkForAppropriateAccuracy` filter to `WalkDistanceCalculator` (existing TODO in `domain/WalkDistance.kt:13`). Out of scope for Stage 11.

---

## Implementation checklist (call-site updates)

- `Walk.kt` — add `distanceMeters` + `meditationSeconds` fields.
- `PilgrimDatabase.kt` — bump version 4→5, add `MIGRATION_4_5` to migrations array.
- `WalkDao.kt` — add `updateAggregates(id, distanceMeters, meditationSeconds)`.
- New `WalkMetricsCache.kt`.
- New `WalkMetricsBackfillCoordinator.kt` + Hilt binding + `start()` from `PilgrimApplication.onCreate()`.
- `WalkFinalizationObserver.kt` — call `walkMetricsCache.computeAndPersist(walkId)` after collective POST + widget refresh.
- `SettingsViewModel.kt` — `practiceSummary` reads cache cols directly; remove per-walk DAO scan.
- `AboutViewModel.kt` — same pattern as SettingsViewModel.
- `PilgrimPackageConverter.kt` — convert reads cache cols (with fallback to `WalkMetricsCache.computeMeditationSeconds` + `WalkDistanceCalculator`); replace raw `sumActivityDuration` for meditation with clamp helper.
- `CollectiveCacheStore.kt` — add `KEY_LAST_SEEN_COLLECTIVE_WALKS` + helpers.
- New `CollectiveMilestone.kt` (data class + companion).
- New `CollectiveMilestoneDetector.kt`.
- `CollectiveRepository.kt` — wire detector.check() into `forceFetch()` + `fetchIfStale()` success branches.
- `CollectiveModule.kt` — provide MilestoneDetector singleton.
- `BellPlaying.kt` — add `play(scale: Float)` with default body.
- `BellPlayer.kt` — implement `play(scale)` with bellVolume multiplier.
- `PracticeSummaryHeader.kt` — add 3 milestone params, render AnimatedVisibility overlay with bell-fire latch.
- `SettingsScreen.kt:117` — pass `milestone`, `onMilestoneShown`, `onMilestoneDismiss` to `PracticeSummaryHeader`. **Required compile-fix call-site.**
- Tests per the lists above.

## Open questions

None. iOS reference exhaustively triple-checked via filesystem reads + code quotes. Android current state inventoried. Migration recipe + finalize hook + UI surface all map cleanly.

## Risks

1. **`MIGRATION_4_5` on existing dev devices with non-trivial data.** Risk: bug in migration corrupts walks. Mitigated by `MigrationTestHelper` test + ALTER-only migration (no data movement, no row rewrite). Verify column-declaration order matches Walk.kt entity order — Room hashes are order-sensitive.
2. **Backfill coordinator load on cold start with many stale walks.** Risk: user with 500 stale walks pays 500 × IO at app launch. Mitigated: coordinator runs on `Dispatchers.IO`, processes one walk at a time gated by Room hot-flow re-emission, not concurrently. UI never blocked. Worst case: 500 × ~20ms ≈ 10s background catch-up. Future stage could add a one-shot `WorkManager` backfill if needed.
3. **Milestone ordering with rapid post→fetch chain.** Break-on-first-match means concurrent multi-milestone (e.g., 109 → 1080 in one fetch — rare) shows only 108 first; 1080 catches on next fetch. Mirrors iOS.
4. **Volume 0.4 × user-bellVolume different than iOS's flat 0.4.** Android divergence per non-goals: iOS lacks user-volume preference, Android has one and respects it.
5. **Hilt scope choice for milestone state.** `@Singleton`-scoped detector survives VM destruction (correct — milestone persists across nav-away from Settings) but does NOT survive process death (correct — lastSeen is persisted, so a fresh process re-detects only if a new sacred number was crossed since the last seen). `WalkMetricsBackfillCoordinator` uses `@CollectiveRepoScope` because it's the existing app-scoped CoroutineScope in the codebase; if a future stage introduces an `@AppScope`, migrate.
6. **ProGuard/R8 for release builds.** No new `@Serializable` data classes added (CollectiveMilestone is plain data class, not serialized). No new keep rules required. Existing `kotlinx-serialization` rules cover the .pilgrim path.
7. **Compose UI test stability for `LaunchedEffect(8000ms)`.** Use `composeRule.mainClock.advanceTimeBy(8500)` (virtual time) — wall-clock `Thread.sleep` would make tests flaky and slow. Compose default mainClock is auto-advance off after `setContent`; set explicitly via `mainClock.autoAdvance = false` then call `advanceTimeBy`.

## Out of scope (deferred)

- Talk duration cache column.
- Active/pause duration cache columns.
- Bell asset selection (Stage 5-B parity to iOS `walkStartBellId`).
- `WorkManager` one-shot migration backfill (lazy reader-driven backfill is sufficient).
- Localizing milestone messages (English-only matches iOS).
