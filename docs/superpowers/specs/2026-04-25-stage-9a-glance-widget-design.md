# Stage 9-A: Glance Widget — Last Walk + Quiet Mantra

**Date:** 2026-04-25
**Stage:** 9-A (Phase 9 first half)
**Reference:** Port plan: "Jetpack Glance widget. Worker refresh on walk completion. Success criteria: Widget updates within 1 minute of finishing a walk."
**iOS reference:** `pilgrim-ios/PilgrimWidget/PilgrimHomeWidget.swift` (daily-rotating mantra, NO walk data)

## Recommended approach

A single Glance widget — `PilgrimWidget` — with a **hybrid content model**:

- **When the user has at least one finished walk:** render the most-recent walk's distance + active duration + a relative date label ("Today", "Yesterday", "3 days ago") + a small calligraphy stroke as a visual cue. Tap deep-links into `WalkSummary` for that walk via an intent extra read by `MainActivity`.
- **When the user has no finished walks (fresh install):** render iOS-parity daily-rotating mantra (10 phrases indexed by day-of-year). Tap opens to `Home`.

The widget refreshes via a `WidgetRefreshScheduler` interface + `WorkManagerWidgetRefreshScheduler` implementation, mirroring `TranscriptionScheduler`. `WalkViewModel.finishWalk` calls `widgetRefreshScheduler.scheduleRefresh()` at the same call site as `transcriptionScheduler.scheduleForWalk()` and `collectiveRepository.recordWalk()`. The Worker reads `WalkRepository.getMostRecentFinished()` + sums route samples via existing `GeoDistance.haversineMeters` and persists to `WidgetStateRepository` — a `@Singleton` Hilt-injected wrapper around our own `DataStore<Preferences>`. The widget composable reads via `repository.stateFlow.collectAsState(initial = WidgetState.Empty)` directly inside `provideContent`. After writing, the Worker calls `PilgrimWidget().updateAll(context)` to trigger recomposition. **No `GlanceStateDefinition`** — that path has its own per-instance state file managed by Glance and would conflict with our app-owned DataStore. The "single global last walk" use case wants ALL widget instances to show the same state, so a Singleton repo + `updateAll` is the correct shape.

## Why this approach

- **Honors iOS's "quiet daily presence" aesthetic** without throwing away the user's explicit port-plan intent for richer Android-native surface. The empty-state mantra becomes a graceful fallback rather than a deliberate iOS deviation.
- **iOS chose mantra-only to satisfy App Store Guideline 2.1(a)** (the Widget Extension target must provide at least one home-screen widget for "Add Widget" to surface). Cross-process App Group avoidance was a secondary benefit, not the primary motivation. Android has no analogous gallery-completeness review, so we're free to add richer content without the iOS author's review-pressure shortcut.
- **No Room schema migration.** Distance + duration are already computed on-read in `WalkSummaryViewModel`, `GoshuinViewModel`, and milestone detection — re-haversining route samples in the Worker matches the existing codebase pattern. Worker runs in IO, total budget for the user-facing "1 minute refresh" success criterion is generous; haversine + Room IO will land well within it.
- **App-owned DataStore + `collectAsState` (not `GlanceStateDefinition` + `currentState`)** is the right shape for global state shared across widget instances. A single Singleton repo means lockscreen + home-screen widget instances stay synchronized for free.
- **Single widget, two sizes (`systemSmall` + `systemMedium` parity)** keeps the receiver/manifest/preview surface narrow and matches iOS's `supportedFamilies`.

## What was considered and rejected

- **Match iOS exactly (daily mantra only):** ignores the port plan's explicit intent and leaves Android users without a rich home-screen surface despite the Glance API enabling it cleanly.
- **Denormalize `Walk.distanceMeters` via Room migration:** adds schema-migration risk + a one-off field that no other consumer needs. The aggregate cost is fine in a Worker.
- **Live active-walk widget (Stage 8-B's accumulator):** out of scope per the user's explicit "NOT in scope" list. Phase 9-B's notification surface covers active-walk feedback better.
- **Configuration screen (which walk to show):** YAGNI. "Most recent finished" is the only configuration anyone would pick.
- **Periodic refresh worker (every N hours):** the only events that change widget content are walk completion (handled by the explicit refresh hook) and day rollover (matters for the empty-state mantra AND for the relative-date label "Today" → "Yesterday"). Daily refresh is solved declaratively via `android:updatePeriodMillis="86400000"` (24h, throttled to ~once-per-day on most OEMs) in the widget descriptor. The Worker also re-runs when the system fires this update, picking up day-rollover for both mantra rotation and relative-date labels.
- **Multiple widget kinds (one per content type):** widget galleries get cluttered fast. One widget that adapts to state is simpler from the user's perspective.

## Layer breakdown

### 1. Widget descriptor + manifest

- `app/src/main/res/xml/pilgrim_widget_info.xml` — `<appwidget-provider>` declaring min/max width/height (matching `systemSmall` ≈ 110dp×110dp and `systemMedium` ≈ 250dp×110dp), `widgetCategory="home_screen"`, `previewLayout` reference, `targetCellWidth/Height` (Android 12+), `resizeMode="horizontal|vertical"`, and `android:updatePeriodMillis="86400000"` (24h declarative refresh — system schedules at most once per day, picks up day-rollover for relative-date labels + mantra rotation without an explicit PeriodicWorkRequest).
- `app/src/main/res/drawable/pilgrim_widget_preview.xml` — minimal vector preview matching the parchment palette so the system widget picker shows our brand.
- `AndroidManifest.xml` — register `PilgrimWidgetReceiver` (extends `GlanceAppWidgetReceiver`) with `<intent-filter android:name="android.appwidget.action.APPWIDGET_UPDATE">` + `<meta-data android:name="android.appwidget.provider" android:resource="@xml/pilgrim_widget_info">`. **Also add `android:launchMode="singleTop"` to the existing `<activity android:name=".MainActivity">` entry** so a widget tap on a running app re-enters via `onNewIntent` instead of stacking a fresh MainActivity instance.

### 2. Widget Compose body

- `app/src/main/java/org/walktalkmeditate/pilgrim/widget/PilgrimWidget.kt` — `class PilgrimWidget : GlanceAppWidget()`. Overrides `provideGlance(context, id)` which calls `provideContent { Body() }`. The `Body()` composable reads `widgetStateRepository.stateFlow.collectAsState(initial = WidgetState.Empty)`.
- The repository handle inside the widget is resolved via Hilt's `EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)` — Glance widgets aren't Hilt-injected directly, but they can fetch Singletons through an EntryPoint pattern at compose time. Same shape Glance's official sample uses.
- The `relativeDate` label ("Today" / "Yesterday" / "%d days ago") is computed AT RENDER TIME inside the composable from the persisted `endTimestampMs: Long` + `LocalDate.now()`. The Worker only persists raw timestamps; the label refreshes whenever Glance re-renders (which happens on `updateAll()` calls AND on the daily declarative `updatePeriodMillis` tick), so day-rollover from "Today" → "Yesterday" automatically lands the next time the system re-renders.
- The mantra phrase for the empty state is computed at render time too, indexed by `LocalDate.now().dayOfYear`. Daily rotation is honored automatically via the `updatePeriodMillis` tick.
- Two layouts based on `LocalSize.current` — small (vertical stack) vs medium (horizontal split with the calligraphy stroke on the left).
- Tap target: `actionStartActivity(MainActivity::class.java)` with extras `EXTRA_DEEP_LINK = "walk_summary"` + `EXTRA_WALK_ID = <walkId>` for the LastWalk variant; `EXTRA_DEEP_LINK = "home"` for Mantra/Empty.

### 3. Widget state + receiver

- `app/src/main/java/org/walktalkmeditate/pilgrim/widget/PilgrimWidgetReceiver.kt` — `class PilgrimWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = PilgrimWidget() }`. **NOT `@AndroidEntryPoint`** — the receiver injects nothing; the widget composable resolves dependencies via `EntryPointAccessors` at compose time.
- `app/src/main/java/org/walktalkmeditate/pilgrim/widget/WidgetState.kt` — `sealed interface WidgetState`:
  - `data class LastWalk(val walkId: Long, val endTimestampMs: Long, val distanceMeters: Double, val activeDurationMs: Long)` — raw values, no pre-formatted strings. Render-time formatting picks up day-rollover correctly.
  - `data object Empty` — used for both "no walks ever" AND as the initial value before the first DataStore read lands. The composable renders the daily mantra for either case.
  - `@Serializable` for JSON persistence.
- `app/src/main/java/org/walktalkmeditate/pilgrim/widget/WidgetStateRepository.kt` — `@Singleton` wrapping its own `DataStore<Preferences>` (separate file, separate qualifier — no overlap with `pilgrim_prefs` or any other DataStore). Exposes `val stateFlow: Flow<WidgetState>` (decoded from the persisted JSON, defaults to `Empty` on absent / malformed) and `suspend fun write(state: WidgetState)`.
- `app/src/main/java/org/walktalkmeditate/pilgrim/widget/WidgetEntryPoint.kt` — `@EntryPoint @InstallIn(SingletonComponent::class) interface WidgetEntryPoint { fun widgetStateRepository(): WidgetStateRepository }`. The bridge from Glance composable (no Hilt context) to the Singleton repo.

### 4. Refresh Worker

- `app/src/main/java/org/walktalkmeditate/pilgrim/widget/WidgetRefreshScheduler.kt` — `interface WidgetRefreshScheduler { fun scheduleRefresh() }` + `@Singleton class WorkManagerWidgetRefreshScheduler @Inject constructor(@ApplicationContext context) : WidgetRefreshScheduler`. Mirrors `TranscriptionScheduler` exactly.
- `app/src/main/java/org/walktalkmeditate/pilgrim/widget/WidgetRefreshWorker.kt` — `@HiltWorker class WidgetRefreshWorker @AssistedInject constructor(@Assisted context, @Assisted params, walkRepository, widgetStateRepository) : CoroutineWorker`. `doWork()`:
  1. Read most-recent finished walk via `walkRepository.getMostRecentFinishedWalk()`.
  2. If non-null: sum route samples via `GeoDistance.haversineMeters` to get `distanceMeters`; compute `activeDurationMs` from accumulator's `totalElapsed - totalPaused - totalMeditated` (same shape as `WalkSummaryViewModel`'s computation, possibly extracted into a shared helper); write `WidgetState.LastWalk(walkId, endTimestampMs, distanceMeters, activeDurationMs)` to the repo.
  3. If null (no walks yet): write `WidgetState.Empty`.
  4. After repo write, call `PilgrimWidget().updateAll(context)` — the canonical Glance API for "force re-render all instances of this widget kind". Don't enumerate glance IDs manually.
  5. CE re-throw discipline on every catch: `catch (ce: CancellationException) { throw ce } catch (t: Throwable) { Log.w(...); Result.failure() }`.
- `OneTimeWorkRequest.Builder<WidgetRefreshWorker>().setExpedited(RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()` with KEEP policy + a unique work name (`"widget_refresh"`) so back-to-back finishWalks coalesce. Note: `setExpedited` + API 31+ may require a `getForegroundInfo()` override on the Worker; using `RUN_AS_NON_EXPEDITED_WORK_REQUEST` falls back gracefully when expedited quota is unavailable, so `getForegroundInfo()` is only required if the Worker actually lands in the expedited queue. Implement it as a courtesy to satisfy the lint rule. **Constraints: only `setRequiresStorageNotLow(true)`** — same minimal surface as Stage 2-F's TranscriptionScheduler (must NOT pair with `setRequiresBatteryNotLow` per that stage's lesson, since expedited+battery crashes).

### 5. Wire-up at finishWalk

- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkViewModel.kt` — inject `WidgetRefreshScheduler`. In `finishWalk()`, after `transcriptionScheduler.scheduleForWalk(walkId)` and `collectiveRepository.recordWalk(...)`, add `widgetRefreshScheduler.scheduleRefresh()` (no walkId argument; the Worker just reads "most recent" itself).

### 6. Deep-link handling in MainActivity

- `app/src/main/java/org/walktalkmeditate/pilgrim/MainActivity.kt`:
  - Manifest declares `android:launchMode="singleTop"` (added per §1).
  - In `onCreate`, parse `intent.getStringExtra(EXTRA_DEEP_LINK) + intent.getLongExtra(EXTRA_WALK_ID, -1L)` into a `DeepLinkTarget?`. Hold in a `mutableStateOf<DeepLinkTarget?>` hoisted around `setContent`. Pass to `PilgrimNavHost(pendingDeepLink = ..., onDeepLinkConsumed = { state.value = null })`.
  - Override `onNewIntent(intent)` and call `setIntent(intent)` first (Android-doc requirement so subsequent `getIntent()` reads the fresh intent), then re-parse and update the hoisted state.
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHost.kt`:
  - Accept `pendingDeepLink: DeepLinkTarget?` + `onDeepLinkConsumed: () -> Unit` params (both default no-op for callers without deep links).
  - The existing PERMISSIONS-auto-nav-to-HOME `LaunchedEffect` is the right insertion point. The deep-link landing logic runs AFTER the existing nav-to-HOME, NOT instead of it. Sequence:
    1. Permissions clear → `navController.navigate(HOME)` (existing behavior, unchanged).
    2. If `pendingDeepLink == DeepLinkTarget.WalkSummary(id)`: `navController.navigate(Routes.walkSummary(id))` — additive, leaves HOME on the back stack so back press returns to journal scroll.
    3. Call `onDeepLinkConsumed()` to clear the pending state.
  - For the case where deep-link arrives via `onNewIntent` while the user is already at, e.g., ACTIVE_WALK: the same `LaunchedEffect(pendingDeepLink)` trigger fires; the navigate uses `popUpTo(Routes.HOME) { saveState = false }` then push the summary, ensuring back press ALWAYS lands on HOME regardless of where the user was.
  - Mantra/Empty deep-link target (`EXTRA_DEEP_LINK == "home"`) is a no-op — once permissions are clear we're already at HOME.
- `app/src/main/java/org/walktalkmeditate/pilgrim/widget/DeepLinkTarget.kt` — `sealed interface DeepLinkTarget { data class WalkSummary(val walkId: Long) : DeepLinkTarget; data object Home : DeepLinkTarget }`. Plus a `parse(intent: Intent): DeepLinkTarget?` companion function so the parsing logic stays in one place + is unit-testable.

### 7. Mantra phrase pool

- `app/src/main/java/org/walktalkmeditate/pilgrim/widget/MantraPool.kt` — port iOS's 10 phrases verbatim. Indexed by day-of-year via `LocalDate.now().dayOfYear`. Pure function `phraseFor(date: LocalDate): String`. Can be unit-tested.

### 8. Strings

- `app/src/main/res/values/strings.xml` append:
  - `widget_display_name` ("Pilgrim"), `widget_description` ("A quiet mantra for your walk.")
  - Relative-date strings: `widget_relative_today` ("Today"), `widget_relative_yesterday` ("Yesterday"), `widget_relative_n_days_ago` ("%1$d days ago"). `Locale.ROOT` formatting at render time for the digit.
  - Mantras: a SINGLE `widget_mantras` resource containing all 10 phrases joined with `|` (pipe) as delimiter, with a `<!-- TRANSLATORS: -->` comment explaining the contemplative tone + that translators should preserve 10 entries. Single key keeps semantic context together; `MantraPool.kt` splits on the delimiter at runtime. Smaller resource footprint, easier to translate as a unit.

### 9. Glance dependencies

- `gradle/libs.versions.toml` — add `androidx.glance:glance-appwidget` (latest stable, currently 1.1.x), `androidx.glance:glance-material3` for theming consistency. Add to `app/build.gradle.kts`.

## Scope decisions

- **Hilt + Glance**: `PilgrimWidgetReceiver` is NOT `@AndroidEntryPoint` (it injects nothing). The Worker is `@HiltWorker` (canonical pattern). The widget composable resolves the Singleton `WidgetStateRepository` via `EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)` at compose time — Glance widgets are constructed reflectively by the system and can't be Hilt-injected directly.
- **Widget state shape**: persisted as JSON in DataStore. Only raw values (timestamps, doubles, longs) — no pre-formatted strings — so render-time formatting picks up day-rollover for relative-date labels and mantra rotation correctly.
- **Single state shared across instances**: `@Singleton WidgetStateRepository` means lockscreen + home-screen widgets stay synchronized for free. `PilgrimWidget().updateAll(context)` after a write triggers re-render of all instances.
- **No `GlanceStateDefinition`**: Glance's per-instance state file mechanism (`GlanceStateDefinition` + `currentState<T>()`) is the wrong shape for "single global last walk". App-owned DataStore + `collectAsState` is the canonical alternative for this use case.
- **Deep-link arg shape**: pass walkId as Long extra. Android Glance widgets compose Intents directly, no URI encoding needed.

## Out of scope (deferred)

- Active-walk widget (would need ongoing-update Worker + Wear OS feels). Stage 9-B's notification covers this better.
- Per-widget configuration (Configuration Activity).
- Multiple widget classes / kinds.
- Wear OS tile / complication.
- Achievement / streak widgets.
- Seal-preview thumbnail (iOS doesn't render seals on the widget either; calligraphy stroke is enough).
- Etegami PNG widget background.

## Quality considerations

- **CE re-throw discipline** in the Worker's `doWork()` — wrap WalkRepository reads + DataStore writes in try/catch with explicit CancellationException re-throw (Stage 8-A audit rule).
- **`Locale.ROOT` for digits** in relative-date "%d days ago" formatting (Stage 6-B / 8-A lesson).
- **Worker dedup via `ExistingWorkPolicy.KEEP` + unique work name** so back-to-back finishWalks don't queue 5 redundant refreshes.
- **DataStore corruption handler** (`ReplaceFileCorruptionHandler { emptyPreferences() }`) on the widget DataStore factory — Stage 8-B closing-review lesson. Widget renders the empty state if state corrupts.
- **Test isolation**: per-test DataStore scope explicitly cancelled in `@After` (Stage 8-B closing-review lesson). For Worker tests, use `WorkManagerTestInitHelper` precedent from `WorkManagerTranscriptionSchedulerTest` — must call `.build()` on the production class via Robolectric (Stage 2-F lesson: faking the scheduler hides WorkRequest builder crashes).
- **No `viewModelScope.launch + Robolectric main-Looper deadlock`** — widget tests don't go through a VM. Worker tests are direct CoroutineWorker invocations.
- **Multi-class run sanity**: don't add `flow.first { predicate }` patterns for state mutated by async coroutines (Stage 8-B closing-review).
- **Device QA** (mandatory): Glance widget rendering can't be unit-tested meaningfully. Test on OnePlus 13 by adding the widget to home screen, finishing a walk, observing the within-1-min refresh.
- **iOS-divergence note in code comments**: explain that the empty-state mantra is iOS-parity and that the LastWalk state is the Android-only enrichment. Future maintainers reading both repos won't be surprised.

## Success criteria

1. User can add the Pilgrim widget from the system widget picker; preview renders correctly in the picker.
2. With no walks yet, widget shows a daily mantra (one of 10, indexed by day-of-year). The phrase rotates across the system's `updatePeriodMillis` daily tick (within the OEM's typical 30min-24h jitter for that mechanism).
3. After completing the first walk, widget refreshes within 1 minute and shows distance + duration + "Today".
4. After day rollover, the widget's relative-date label transitions from "Today" → "Yesterday" → "%d days ago" automatically (next time Glance re-renders, which happens at the daily `updatePeriodMillis` tick).
5. Tapping the widget when in last-walk state opens MainActivity → navigates to WalkSummary for that walk; back press lands on HOME (journal scroll), not the widget origin.
6. Tapping the widget when in mantra state opens MainActivity → navigates to Home.
7. Widget tap with MainActivity already running re-enters via `onNewIntent` (not a fresh stack) thanks to `singleTop` launch mode.
8. Widget survives process death + battery saver — refresh is via WorkManager, persistent.
9. Widget renders correctly on light + dark mode (parchment/ink adapts via Glance's MaterialColorScheme).
10. No regressions in existing tests; new tests cover Mantra phrase rotation, state serialization, deep-link parsing, and Worker scheduling (builder must be exercised on the production class).

## Files

**New (9 production + 6 test):**

Production:
- `app/src/main/java/org/walktalkmeditate/pilgrim/widget/PilgrimWidget.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/widget/PilgrimWidgetReceiver.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/widget/WidgetState.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/widget/WidgetStateRepository.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/widget/WidgetEntryPoint.kt` (Hilt EntryPoint bridge for the composable)
- `app/src/main/java/org/walktalkmeditate/pilgrim/widget/WidgetRefreshScheduler.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/widget/WidgetRefreshWorker.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/widget/MantraPool.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/widget/DeepLinkTarget.kt` (sealed type + `parse(intent)` companion for MainActivity ↔ NavHost)
- `app/src/main/res/xml/pilgrim_widget_info.xml`
- `app/src/main/res/drawable/pilgrim_widget_preview.xml`

Test:
- `app/src/test/java/org/walktalkmeditate/pilgrim/widget/MantraPoolTest.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/widget/DeepLinkTargetTest.kt` (parse + extra round-trip)
- `app/src/test/java/org/walktalkmeditate/pilgrim/widget/WidgetStateTest.kt` (JSON round-trip + Empty default + relative-date helper)
- `app/src/test/java/org/walktalkmeditate/pilgrim/widget/WidgetStateRepositoryTest.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/widget/WidgetRefreshWorkerTest.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/widget/WorkManagerWidgetRefreshSchedulerTest.kt`

**Modified:**
- `gradle/libs.versions.toml` (Glance deps)
- `app/build.gradle.kts` (deps)
- `app/src/main/AndroidManifest.xml` (receiver registration + `android:launchMode="singleTop"` on MainActivity)
- `app/src/main/res/values/strings.xml` (widget + mantra strings)
- `app/src/main/java/org/walktalkmeditate/pilgrim/MainActivity.kt` (intent extras + onNewIntent + setIntent)
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHost.kt` (pendingDeepLink + onDeepLinkConsumed params)
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkViewModel.kt` (one-line inject + scheduleRefresh)
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkViewModelTest.kt` (FakeWidgetRefreshScheduler + new ctor param at all 6 sites)
