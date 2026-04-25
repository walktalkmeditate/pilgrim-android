# Stage 8-B Design — Collective Counter

## Context

Phase 8's second half. Stage 8-A shipped the Share Worker integration; 8-B closes Phase 8 by adding the lightweight collective-counter service. Users (opt-in) contribute a delta-of-1 to a global aggregate on each walk-finish; everyone (opt-in or not) can see the aggregate displayed on Settings.

**iOS canonical reference:** `pilgrim-ios/Pilgrim/Models/Collective/CollectiveCounterService.swift`. Read top-to-bottom; key behaviors:
- Singleton, `@Published var stats: CollectiveStats?`
- `fetch()` — GET with 216s in-memory TTL gate; bypasses URLCache so a post-walk fetch sees fresh data.
- `recordWalk(distanceKm, meditationMin, talkMin)` — opt-in-gated; accumulates pending delta in UserDefaults; POSTs snapshot; on success subtracts snapshot from pending and re-fetches; on failure leaves pending so next walk's call carries it forward.
- Pending-delta accumulator handles backend's 1-POST-per-token-per-hour rate limit naturally.
- Milestone notification on crossing sacred numbers (108 / 1080 / 2160 / 10000 / 33333 / 88000 / 108000) with persisted `lastSeenCollectiveWalks` to avoid re-firing.

**Backend contract** (`pilgrim-worker/src/handlers/counter.ts`):
- `GET /api/counter` → 200 with `{ total_walks, total_distance_km, total_meditation_min, total_talk_min, last_walk_at, streak_days, streak_date }`. Cache-Control `public, max-age=10800` (3 hours; iOS bypasses).
- `POST /api/counter` with `X-Device-Token` header (required, 401 without; min 8 chars) and JSON body `{ walks, distance_km, meditation_min, talk_min }`. Backend caps walks ≤ 10, distance ≤ 200 km, meditate/talk ≤ 480 min per call. Rate-limit: 1 successful POST per device per hour (429 otherwise). Rejects 400 if walks == 0 AND distance_km == 0.

**Android existing infra (Stage 8-A foundation):** `NetworkModule` provides singleton `OkHttpClient` + `Json`. `ShareModule` established the `@Qualifier annotation class XxxBaseUrl + String` pattern (avoids `@JvmInline value class` Hilt-factory-visibility trap). `DeviceTokenStore.getToken()` is the per-device UUID accessor (iOS `ShareService.deviceTokenForFeedback()` parity).

**iOS UI placements:**
- **Settings** — `PracticeSummaryHeader` shows the stats line (cycling phases: walks+distance / meditation+streak / etc.) with a `.task { fetch() }` on view appear.
- **Settings (PracticeCard)** — opt-in toggle "Walk with the collective" / "Add your footsteps to the path", default `false`.
- **Home (WalkStartView)** — observes `stats` for a "collective pulse" effect when `walkedInLastHour`.
- **AppDelegate** — fetch on app launch.
- **Walk-finish (MainCoordinatorView)** — calls `recordWalk(distanceKm, meditationMin, talkMin)` from the save callback.

## Recommended approach

Port iOS's `CollectiveCounterService` design wholesale to a `CollectiveRepository` with the same five concerns: opt-in gate, pending-delta accumulator, fetch with TTL, POST, success-subtract-and-refetch. Persistence moves from UserDefaults to DataStore Preferences (same pattern as `CachedShareStore` from 8-A). UI surfaces are minimal for 8-B MVP — Settings gets the toggle + a stats card showing "X walks · Y km" — and the Home pulse + per-app-launch fetch + milestone-celebration UI are deferred to follow-up stages.

The `recordWalk` hook lives in `WalkViewModel.finishWalk()`'s already-tail-of-finish chain (after hemisphere refresh + transcription scheduler), so it slots in naturally without restructuring the existing finish flow.

### Layer breakdown

1. **`data/collective/CollectiveConfig.kt`** — base URL `https://walk.pilgrimapp.org`, GET + POST endpoints `/api/counter`, 216s TTL constant, max-walks-per-POST cap (10, mirroring server cap so we slice oversized accumulator into multi-POST batches if needed — practically unreachable).

2. **`data/collective/CollectiveStats.kt`** — `@Serializable` data class with `@SerialName` matching backend snake_case fields. Nullable fields where backend may omit (`last_walk_at`, `streak_days`, `streak_date`). `walkedInLastHour(nowEpochMs)` extension for the future Home pulse.

3. **`data/collective/CollectiveCounterDelta.kt`** — `@Serializable` POST payload (walks, distance_km, meditation_min, talk_min). Internal-only.

4. **`data/collective/CollectiveCounterService.kt`** — `suspend fun fetch(): CollectiveStats` (throws on failure) + `suspend fun post(delta: CollectiveCounterDelta): PostResult` (sealed: Success / RateLimited / Failed). OkHttp on `Dispatchers.IO`. CE re-throw audit. Uses `@CounterHttpClient`-qualified OkHttpClient (10s call timeout — iOS uses 10s; counter calls are tiny). **No HTTP-cache bypass needed**: NetworkModule's default OkHttpClient does not configure a disk cache, so the worker's `Cache-Control: public, max-age=10800` response header is a no-op for our client. The 216s in-memory TTL gate in [CollectiveRepository] is the real (and only) rate limiter, matching iOS exactly.

5. **`data/collective/CollectiveCacheStore.kt`** — DataStore Preferences (file `collective_counter`):
   - `cached_stats_json: String?` — last-known stats blob.
   - `last_fetched_at_ms: Long?` — TTL gate seed.
   - `opt_in: Boolean` — default `false`.
   - `pending_delta_json: String?` — accumulator for failed/queued POSTs.
   - Exposes per-key Flows for reactive consumption + suspend setters via `dataStore.edit { }`. Atomic mutations for the pending-delta accumulator (read-modify-write inside one `edit` block).

6. **`data/collective/CollectiveRepository.kt`** — `@Singleton`. Orchestrates:
   - `val stats: StateFlow<CollectiveStats?>` — observes cached_stats Flow, decoded via Json.
   - `val optIn: StateFlow<Boolean>` — observes opt_in Flow, default false.
   - `suspend fun setOptIn(value: Boolean)` — flips DataStore.
   - `suspend fun fetchIfStale(now: () -> Long = System::currentTimeMillis)` — TTL gate against `last_fetched_at_ms`; if expired, calls service.fetch(), writes stats + lastFetchedAt to DataStore on success. On failure, no-op (next call retries).
   - `suspend fun forceFetch()` — invalidates lastFetchedAt then fetches (used after a successful POST).
   - `fun recordWalk(snapshot: CollectiveWalkSnapshot)` — fire-and-forget on the repo's own scope, wrapped in `mutex.withLock { }`:
     - Returns immediately if `optIn` is false.
     - Atomically merges new delta into pending in DataStore (single `edit { }` for read-modify-write).
     - Snapshots pending. **Empty-pending guard:** if the merged pending is `{0, 0, 0, 0}` (e.g., a zero-distance walk merged into already-empty pending), skip the POST entirely — backend rejects `walks == 0 && distance_km == 0` with a 400 (counter.ts:43-45), and looping on Failed → "leave pending" → re-attempt would be an infinite retry.
     - Calls `service.post(snapshot)`.
     - On Success: atomically subtract snapshot from pending; **if pending.walks <= 0 OR pending is otherwise effectively empty, clear the key** (iOS CollectiveCounterService.swift:114 parity); call `forceFetch()` to refresh stats.
     - On RateLimited: leave pending intact; next walk's recordWalk will accumulate further and try again on next finishWalk.
     - On Failed: same as RateLimited (leave pending; retry on next walk).

7. **`di/CollectiveModule.kt`** — `@Singleton @Provides @CounterHttpClient OkHttpClient` (rebuilds default with 10s callTimeout) + `@Singleton @Provides @CounterBaseUrl String` returning `CollectiveConfig.BASE_URL`. `@Provides` Repository (constructor-injected, no factory needed).

8. **UI:**
   - **`ui/settings/CollectiveStatsCard.kt`** — Compose card observing `CollectiveRepository.stats`; renders "X walks · Y km" line via `Locale.ROOT`-formatted NumberFormat (ASCII digits, locale-grouping). Triggers `LaunchedEffect { repository.fetchIfStale() }` on first composition.
   - **`ui/settings/SettingsScreen.kt`** — slot the stats card + add the opt-in toggle row.
   - **VM additions** — `SettingsViewModel.kt` (NEW) wires `CollectiveRepository`'s `stats` + `optIn` flows + `setOptIn` action. Keeps Compose layer free of direct repo access.

9. **Walk-finish hook** — In `WalkViewModel.finishWalk()`, **snapshot the Active walk's accumulator BEFORE `controller.finishWalk()` is called**, then fire `recordWalk` with the snapshot AFTER the finish chain settles. iOS fires from `MainCoordinatorView`'s save callback against an in-memory `TempWalk` — Android's analog is `WalkController.state.value as WalkState.Active` (pre-finish, when the in-memory accumulator is still populated). After `controller.finishWalk()`, state transitions away from Active and the in-memory accumulator is gone; doing a post-finish DAO recomputation would work but adds latency, while the pre-finish snapshot is instant + lossless.
   ```kotlin
   fun finishWalk() {
       viewModelScope.launch {
           // Snapshot for the collective counter BEFORE controller.finishWalk()
           // clears the in-memory Active accumulator. Captures distance
           // (meters → km), meditate (millis → minutes), talk (millis →
           // minutes from the active walk's voice-recordings sub-accumulator).
           // Returns null when finishWalk is called from a non-Active state
           // (defensive — caller usually only calls from Active, but the
           // null-safe path keeps the recordWalk site simple).
           val snapshot = (controller.state.value as? WalkState.Active)
               ?.let { active ->
                   CollectiveWalkSnapshot(
                       distanceKm = active.totalDistanceMeters / 1000.0,
                       meditationMin = (active.totalMeditatedMillis / 60_000L).toInt(),
                       talkMin = (active.totalTalkMillis / 60_000L).toInt(),
                   )
               }
           controller.finishWalk()
           // ... existing voice recorder + hemisphere + transcription chain ...
           snapshot?.let { collectiveRepository.recordWalk(it) }
       }
   }
   ```
   `recordWalk` is non-suspend (fire-and-forget on the repo's own scope, NOT `viewModelScope` — viewModelScope cancellation on Walk Summary nav-away would drop an in-flight POST), so the finishWalk coroutine doesn't block on the network call. **TODO during plan-writing:** verify `WalkState.Active` carries `totalDistanceMeters`, `totalMeditatedMillis`, and a `totalTalkMillis` (voice-recording duration) field. If the talk total isn't tracked on the accumulator, fall back to a single repo lookup `repository.voiceRecordingsFor(walkId).sumOf { it.durationMillis } / 60_000L`. Define `CollectiveWalkSnapshot` as an internal data class in the collective package.

### Scope decisions

**IN scope for 8-B:**
- Service + repo + DataStore cache.
- Settings opt-in toggle (matching iOS label/description verbatim).
- Settings stats card (simplified single line "X walks · Y km").
- WalkViewModel finishWalk recordWalk hook.
- App-launch fetch — call `repository.fetchIfStale()` from **`PilgrimApp.onCreate`** (the existing `Application` subclass), NOT `MainActivity.onCreate`. iOS fires from `AppDelegate.didFinishLaunchingWithOptions` which runs once per process; `MainActivity.onCreate` would re-fire on every config change (rotation, theme flip), which the 216s TTL gate would absorb but is wrong-shape vs iOS. `PilgrimApp.onCreate` runs exactly once per process, matches iOS placement, and uses Hilt's already-injected singletons. Launch on `Application`'s lifecycle scope (`@HiltAndroidApp` provides one via `coroutineScope` extension or use a `CoroutineScope(SupervisorJob() + Dispatchers.IO)` singleton from a Hilt module).
- Robolectric tests: stats decode, repo.fetchIfStale TTL gate, recordWalk opt-in gate, recordWalk pending accumulator, recordWalk success-subtract logic, recordWalk rate-limit retains pending.
- MockWebServer tests: GET 200 happy path, POST 200 happy path, POST 401, POST 429, GET network failure.

**OUT of scope (deferred):**
- **Home pulse animation** (`walkedInLastHour` reactive UI) — separate UI stage; the `walkedInLastHour` helper IS shipped on the model so the data is available.
- **Milestone celebration UI** (sacred-number bell + animation when crossing 108 / 1080 / etc.) — separate stage. **`last_seen_collective_walks` persistence is also deferred** to that follow-up stage along with the milestone-detection logic; 8-B's CacheStore does NOT include a key for it. Adding it without the consumer would be dead state.
- **Cycling stat phases** (Settings's PracticeSummaryHeader rotates through walks+km / meditation+streak / talk / etc.) — single line for 8-B, multi-phase rotation later.
- **Distance unit toggle for the display** — Stage 8-A hardcoded `units = "metric"` with a TODO; same here, display in km only. iOS reads `UserPreferences.distanceMeasurementType`.
- **Imperial conversion** — same as above.
- **`PilgrimageProgress` italic line** — iOS `PracticeSummaryHeader` renders `stats.pilgrimageProgress.message` (an italic line above the stats line) when `totalWalks > 0`, drawing on a domain model under iOS `Pilgrim/Models/Collective/PilgrimageProgress.swift`. The `total_distance_km` field IS decoded on Android so the data is available; rendering the message + porting `PilgrimageProgress` (well-known pilgrimage-distance milestones like Camino length, Shikoku, etc.) is deferred to the same follow-up stage as cycling stat phases.
- **Streak display** — `streak_days` and `streak_date` are decoded into `CollectiveStats` for forward compatibility, but the simplified single-line "X walks · Y km" card does NOT render them. iOS shows a `StreakFlameView(days: streak)` when `streak > 1` — defer with the rest of the multi-phase stat work.

## Why this approach

iOS's `CollectiveCounterService` is already the right shape for a pilgrim-aesthetic feature: quiet, opt-in, retry-friendly without alarming the user. Porting it directly preserves cross-platform feel — a user switching devices sees the same opt-in semantics + same accumulate-and-retry behavior + same backend contract. The persistence move (UserDefaults → DataStore Preferences) is mechanical; the same pattern landed in 8-A's `CachedShareStore` and `DeviceTokenStore`.

The `recordWalk` placement in `WalkViewModel.finishWalk()` (rather than `WalkSummaryViewModel.share()` or some other tail) matches iOS's flow exactly: iOS fires from `MainCoordinatorView`'s save callback, which corresponds to Android's `controller.finishWalk()` completion. Consistent placement = consistent edge-case behavior (e.g., pre-2-point walks: server validates `walks == 0 && distance_km == 0` → 400, which we'd hit on a zero-distance walk; `recordWalk` could pre-validate to avoid the call entirely, but the iOS code doesn't, so we stay parity-clean).

## What was considered and rejected

- **Synchronous POST inside `WalkViewModel.finishWalk()`'s coroutine** — would hold the user on the spinner waiting for a network round-trip. Reject; fire-and-forget on repo scope mirrors iOS.
- **WorkManager-backed POST queuing** — overkill for a 1-walk-per-hour-per-device rate limit. The DataStore-pending accumulator + retry-on-next-finishWalk pattern is sufficient AND naturally handles offline (no walks happening means no retry attempts means no wasted battery).
- **Combining the opt-in toggle with the share-journey toggle** — they're two different concerns: share-journey is per-walk display gating; opt-in is global data transmission gating (Stage 8-A's lesson #2 made this distinction explicit). Keeping them separate matches iOS.
- **In-memory cache instead of DataStore** — DataStore survives process death; the user expects "X walks" to be there even after killing the app between sessions.
- **Per-screen fetch invocation** — every screen calling `fetchIfStale()` would race the TTL gate harmlessly but waste edits. Single Settings screen + app-launch entry covers all paths.
- **Dropping the pending-delta accumulator** — would mean a 429 or network failure loses the walk count forever. iOS accumulates; we should too.
- **Pre-calling backend's `walks == 0 && distance_km == 0` check client-side** — adds branching for a path the backend handles with a 400; the 400 is logged + ignored anyway. Keep the surface minimal.
- **Bigger UI surface (Home pulse, milestone celebration, cycling stat phases)** — iOS-parity-driven feature creep on 8-B; ship the wire + opt-in toggle + simplest display, layer richer UI in follow-up stages.

## Quality considerations

### Stage lessons that apply
- **Stage 8-A `runCatching` swallows CE** — explicit try/catch + CE re-throw on every suspend block.
- **Stage 8-A `Locale.ROOT` for ASCII digits** — `NumberFormat.getNumberInstance(Locale.ROOT)` for the walks count (avoids Arabic/Persian/Hindi digit glyphs in the Latin "X walks · Y km" line).
- **Stage 8-A iOS toggle-semantics-are-display-not-data** — INVERTED here: opt-in IS a true data-transmission gate. Document this in the repo so future devs don't conflate the two patterns.
- **Stage 5-D atomic DataStore mutation** — pending-delta read-modify-write must be inside a single `edit { }` block.
- **Stage 7-D Mutex for double-fire prevention — REQUIRED, not optional**. Without a Mutex around the read-modify-write + POST + subtract sequence, two concurrent `recordWalk` calls reading `pending = 0` would both write `pending = 1` (the second clobbering the first's increment), both POST `walks=1`, both subtract → `pending = -1`. The backend cleanly receives 1+1=2 walks (correct), but Android's pending accumulator drifts. Wrap the entire `recordWalk` body in `mutex.withLock { }` so writes serialize. Additionally, port iOS's clamp at `if (current.walks <= 0) clearPending() else savePending(current)` (CollectiveCounterService.swift:114) — defensive against any pending arithmetic that ever goes negative.
- **Stage 8-A `@Qualifier annotation class String` over `@JvmInline value class String`** — avoids the Hilt factory visibility collision.
- **Stage 8-A 90s call timeout for share** — counter is much smaller; 10s is fine (matches iOS).
- **Stage 8-A Privacy comment explaining toggle behavior** — add an analogous one in CollectiveRepository explaining "opt-in IS a data-transmission gate, not a display gate."
- **Stage 7-D `WalkSummaryScreen` snackbar event SharedFlow** — not needed here; the collective POST is silent.

### Thread policy
- HTTP calls → `Dispatchers.IO` (OkHttp blocking + DataStore writes).
- DataStore observers → `viewModelScope` / repo scope.
- `recordWalk` fire-and-forget → repo's own `CoroutineScope(SupervisorJob() + Dispatchers.IO)` (similar to Stage 5-C `@VoiceGuideManifestScope`). Add `@CollectiveRepoScope` qualifier.

### Memory / performance
- Stats payload: ~250 bytes JSON. Negligible.
- DataStore reads: ~5 keys per file. Negligible.
- No bitmap, no list, no scrolling. Pure text + toggle.

### Failure modes
- App offline → `fetchIfStale` no-ops; cached stats from previous fetch render. ✓
- Backend down → `recordWalk` POST fails; pending accumulates. ✓
- Rate-limited (429) → pending preserved; next walk retries. ✓
- DataStore corrupted → `cached_stats_json = null` → stats StateFlow emits null → UI hides the stats card. ✓
- User toggles opt-in OFF mid-pending → pending sits in DataStore forever. Acceptable; iOS doesn't address it either. We'll add a comment.
- User toggles opt-in ON → OFF → ON over multiple sessions → pending from the first ON-period flushes on the next walk after the second ON. Acceptable; iOS same behavior.
- User has NEVER opted in, finishes walks → `recordWalk` is gated by `optIn` at call time and returns immediately; pending is never written. First-time opt-in does NOT backfill prior walks (intentional — opt-in is forward-looking only, per the spec's intent statement). No special handling required.
- Two concurrent recordWalk calls (unusual: requires two finishWalks within ms) → one's POST may 429 the other; pending accumulates safely. ✓

## Success criteria

1. Opt-in toggle on Settings; default off; persists across app restarts.
2. With opt-in OFF, finishing a walk does NOT POST. Network silent.
3. With opt-in ON, finishing a walk fires a POST within 1s; on success, the stats card refreshes with the bumped count.
4. With opt-in ON + airplane mode → POST fails → pending accumulates → next walk after reconnect POSTs the cumulative delta.
5. Settings open without network → cached stats render from DataStore (no spinner, no flicker).
6. Hitting 429 (two walks in same hour) → pending preserved; next-day walk POSTs the doubled walk count.
7. Locale = Arabic / Persian / Hindi → walk count digits remain ASCII (Locale.ROOT).
8. Build + test + lint clean on min SDK 28 / target 36.

## Files

**New under `app/src/main/java/org/walktalkmeditate/pilgrim/data/collective/`:**
- `CollectiveConfig.kt`
- `CollectiveStats.kt`
- `CollectiveCounterDelta.kt`
- `CollectiveCounterService.kt`
- `CollectiveCacheStore.kt`
- `CollectiveRepository.kt`

**New DI:**
- `di/CollectiveModule.kt` — `@CounterHttpClient`, `@CounterBaseUrl`, `@CollectiveRepoScope` qualifiers; OkHttpClient with 10s timeout.

**New UI:**
- `ui/settings/CollectiveStatsCard.kt`
- `ui/settings/SettingsViewModel.kt` (NEW — Settings doesn't have one yet)

**Modified:**
- `ui/settings/SettingsScreen.kt` — slot stats card + opt-in toggle.
- `ui/walk/WalkViewModel.kt` — inject `CollectiveRepository`; in `finishWalk()`, snapshot Active state BEFORE `controller.finishWalk()` clears it, then fire `recordWalk(snapshot)` after the existing finish chain (hemisphere refresh + transcription scheduler).
- `PilgrimApp.kt` — call `repository.fetchIfStale()` on `onCreate` (one-shot via a singleton `@AppLaunchScope` Hilt-provided scope).
- `app/src/main/res/values/strings.xml` — ~6 new strings (toggle label/desc, stats line format, error/empty states).

**Tests (~6 files):**
- `CollectiveStatsTest` — JSON decode round-trip, snake_case mapping, walkedInLastHour boundary.
- `CollectiveCounterServiceTest` — MockWebServer for GET 200, POST 200, POST 401, POST 429, IOException → Failed.
- `CollectiveCacheStoreTest` — Robolectric DataStore round-trip + opt-in default false + atomic pending-delta merge.
- `CollectiveRepositoryTest` — fetchIfStale TTL gate, recordWalk opt-in gate, recordWalk accumulator, success-subtract, rate-limit retains pending.
- `SettingsViewModelTest` — opt-in flow, stats flow, fetch on subscribe.
- VM test additions for WalkViewModel ctor change.

**No new dependencies.** OkHttp + kotlinx-serialization + DataStore Preferences + MockWebServer all already present (Stage 5-C / 8-A).

**Estimate:** ~14 production files + ~6 test files = ~20 total. Mid-sized stage. Smaller than 8-A (which had ~22). Dominated by HTTP + DataStore plumbing + a single-line stats display.
