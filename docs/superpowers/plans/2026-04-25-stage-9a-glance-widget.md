# Stage 9-A Implementation Plan — Glance Widget

Spec: `docs/superpowers/specs/2026-04-25-stage-9a-glance-widget-design.md`

Sequence: Task 1 → 12. Each task ends compiling + passing tests. Branch already created: `feat/stage-9a-glance-widget` (off `origin/main`).

---

## Task 1 — Add Glance dependencies

Pull in Jetpack Glance + glance-material3 via the version catalog.

### Files

**Modify** `gradle/libs.versions.toml`:

Append a `glance = "1.1.1"` (or latest stable) version + libraries:
```toml
[versions]
glance = "1.1.1"

[libraries]
androidx-glance-appwidget = { module = "androidx.glance:glance-appwidget", version.ref = "glance" }
androidx-glance-material3 = { module = "androidx.glance:glance-material3", version.ref = "glance" }
```

**Modify** `app/build.gradle.kts`:

Add to `dependencies { ... }`:
```kotlin
implementation(libs.androidx.glance.appwidget)
implementation(libs.androidx.glance.material3)
```

### Verify
```bash
./gradlew :app:compileDebugKotlin
```

---

## Task 2 — `WidgetState` sealed type + JSON serialization

Pure Kotlin types, no I/O.

### Files

**Create** `app/src/main/java/org/walktalkmeditate/pilgrim/widget/WidgetState.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.widget

import kotlinx.serialization.Serializable

/**
 * Persisted widget state. Raw values only (no pre-formatted strings) so
 * render-time formatting picks up day-rollover for relative-date labels
 * AND mantra rotation correctly. The composable formats inline via
 * `LocalDate.now()` and the Worker only persists timestamps.
 *
 * Single `@Singleton WidgetStateRepository` shares this state across
 * all widget instances on home/lockscreen — Worker's `updateAll(context)`
 * triggers a synchronized re-render after writes.
 */
@Serializable
sealed interface WidgetState {
    @Serializable
    data class LastWalk(
        val walkId: Long,
        val endTimestampMs: Long,
        val distanceMeters: Double,
        val activeDurationMs: Long,
    ) : WidgetState

    @Serializable
    data object Empty : WidgetState
}
```

**Create** `app/src/test/java/org/walktalkmeditate/pilgrim/widget/WidgetStateTest.kt`:

JUnit unit tests (no Robolectric):
- LastWalk JSON round-trip preserves all fields exactly.
- Empty JSON round-trip stays Empty (data object).
- Polymorphic decode: `Json.decodeFromString<WidgetState>` correctly resolves the type discriminator.

### Verify
```bash
./gradlew :app:testDebugUnitTest --tests "*.WidgetStateTest"
```

---

## Task 3 — `MantraPool` + `DeepLinkTarget`

Two small pure types.

### Files

**Create** `app/src/main/java/org/walktalkmeditate/pilgrim/widget/MantraPool.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.widget

import java.time.LocalDate

/**
 * Daily-rotating walking mantras. iOS-parity (PilgrimHomeWidget.swift:84-96).
 * Indexed by day-of-year so the phrase changes at midnight.
 *
 * Phrases are loaded from a single delimited string resource
 * (`R.string.widget_mantras`) split on `|` so translators see all 10
 * entries together with semantic context, and the resource footprint
 * stays minimal.
 */
object MantraPool {
    const val DELIMITER = "|"

    fun phraseFor(date: LocalDate, allMantrasJoined: String): String {
        val phrases = allMantrasJoined.split(DELIMITER).filter { it.isNotBlank() }
        if (phrases.isEmpty()) return ""
        return phrases[date.dayOfYear % phrases.size]
    }
}
```

**Create** `app/src/main/java/org/walktalkmeditate/pilgrim/widget/DeepLinkTarget.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.widget

import android.content.Intent

/**
 * Sealed type for widget → MainActivity → NavHost deep-link dispatch.
 * `parse(intent)` keeps Intent extra parsing in one place (testable in
 * isolation, no Activity lifecycle needed).
 */
sealed interface DeepLinkTarget {
    data class WalkSummary(val walkId: Long) : DeepLinkTarget
    data object Home : DeepLinkTarget

    companion object {
        const val EXTRA_DEEP_LINK = "org.walktalkmeditate.pilgrim.widget.EXTRA_DEEP_LINK"
        const val EXTRA_WALK_ID = "org.walktalkmeditate.pilgrim.widget.EXTRA_WALK_ID"
        const val DEEP_LINK_WALK_SUMMARY = "walk_summary"
        const val DEEP_LINK_HOME = "home"

        fun parse(intent: Intent?): DeepLinkTarget? {
            if (intent == null) return null
            return when (intent.getStringExtra(EXTRA_DEEP_LINK)) {
                DEEP_LINK_WALK_SUMMARY -> {
                    val id = intent.getLongExtra(EXTRA_WALK_ID, -1L)
                    if (id > 0) WalkSummary(id) else null
                }
                DEEP_LINK_HOME -> Home
                else -> null
            }
        }
    }
}
```

**Create** `app/src/test/java/org/walktalkmeditate/pilgrim/widget/MantraPoolTest.kt`:

JUnit tests:
- `phraseFor(date, joined)` returns the same phrase for the same `dayOfYear`.
- 366 distinct dates produce phrases that wrap modulo 10.
- Empty `allMantrasJoined` returns empty string (defensive).
- Single-phrase input returns that phrase for every date.

**Create** `app/src/test/java/org/walktalkmeditate/pilgrim/widget/DeepLinkTargetTest.kt`:

Robolectric tests (`Intent` is an Android type):
- `parse(null)` → null.
- Empty Intent → null.
- Intent with `EXTRA_DEEP_LINK = "walk_summary"` + valid `EXTRA_WALK_ID` → `WalkSummary(id)`.
- Intent with `EXTRA_DEEP_LINK = "walk_summary"` + missing/-1 walk id → null.
- Intent with `EXTRA_DEEP_LINK = "home"` → `Home`.
- Intent with unknown deep-link string → null.

### Verify
```bash
./gradlew :app:testDebugUnitTest --tests "*.MantraPoolTest" --tests "*.DeepLinkTargetTest"
```

---

## Task 4 — `WidgetStateRepository` + DataStore module

DataStore-backed persistence for the widget state.

### Files

**Create** `app/src/main/java/org/walktalkmeditate/pilgrim/widget/WidgetStateRepository.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.widget

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

@Singleton
class WidgetStateRepository @Inject constructor(
    @WidgetDataStore private val dataStore: DataStore<Preferences>,
    private val json: Json,
) {
    val stateFlow: Flow<WidgetState> = dataStore.data
        .map { prefs -> prefs[KEY_STATE_JSON]?.let(::decode) ?: WidgetState.Empty }
        .distinctUntilChanged()

    suspend fun write(state: WidgetState) {
        val blob = json.encodeToString(WidgetState.serializer(), state)
        dataStore.edit { it[KEY_STATE_JSON] = blob }
    }

    private fun decode(blob: String): WidgetState? = try {
        json.decodeFromString(WidgetState.serializer(), blob)
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Throwable) {
        null
    }

    internal companion object {
        const val DATASTORE_NAME = "widget_state"
        val KEY_STATE_JSON = stringPreferencesKey("state_json")
    }
}
```

**Create** `app/src/main/java/org/walktalkmeditate/pilgrim/widget/WidgetQualifier.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.widget

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class WidgetDataStore
```

**Create** `app/src/main/java/org/walktalkmeditate/pilgrim/di/WidgetModule.kt`:

Hilt module providing `@WidgetDataStore DataStore<Preferences>` via `PreferenceDataStoreFactory.create(corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }, produceFile = { context.preferencesDataStoreFile(WidgetStateRepository.DATASTORE_NAME) })`.

**Create** `app/src/test/java/org/walktalkmeditate/pilgrim/widget/WidgetStateRepositoryTest.kt`:

Robolectric tests with explicit cancellable DataStore scope (Stage 8-B closing-review pattern):
- Initial `stateFlow.first()` → `Empty`.
- `write(LastWalk(...))` then `stateFlow.first()` round-trips identically.
- `write(Empty)` then `stateFlow.first()` → `Empty`.
- Corrupted JSON → flow emits `Empty` (fallback).
- Distinct emissions: writing the same state twice doesn't re-emit (distinctUntilChanged).

### Verify
```bash
./gradlew :app:testDebugUnitTest --tests "*.WidgetStateRepositoryTest"
```

---

## Task 5 — `WidgetEntryPoint` (Hilt bridge for the composable)

### Files

**Create** `app/src/main/java/org/walktalkmeditate/pilgrim/widget/WidgetEntryPoint.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.widget

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt EntryPoint bridge for the Glance composable. Glance widgets
 * are constructed reflectively by the system and can't be Hilt-
 * injected directly; consumers call
 * `EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)`
 * at compose time to fetch Singletons.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun widgetStateRepository(): WidgetStateRepository
}
```

### Verify
```bash
./gradlew :app:compileDebugKotlin
```

---

## Task 6 — `WidgetRefreshScheduler` + WorkManager impl

Mirror `TranscriptionScheduler` exactly.

### Files

**Create** `app/src/main/java/org/walktalkmeditate/pilgrim/widget/WidgetRefreshScheduler.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.widget

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

interface WidgetRefreshScheduler {
    fun scheduleRefresh()
}

@Singleton
class WorkManagerWidgetRefreshScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : WidgetRefreshScheduler {

    override fun scheduleRefresh() {
        // Stage 2-F lesson: pairing setExpedited with
        // setRequiresBatteryNotLow crashes at WorkRequest.build(). Keep
        // expedited (widget refresh should land within ~1 min of
        // finishWalk per success criteria) + storage-not-low (Worker
        // reads Room) only.
        val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
            .setConstraints(
                Constraints.Builder().setRequiresStorageNotLow(true).build(),
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        // KEEP + unique work name so back-to-back finishWalks
        // coalesce — the worker reads "most recent" itself, no per-walk
        // input data, so dedup is correct.
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "widget_refresh"
    }
}
```

**Create** `app/src/main/java/org/walktalkmeditate/pilgrim/di/WidgetSchedulerModule.kt`:

Hilt module binding `WidgetRefreshScheduler` → `WorkManagerWidgetRefreshScheduler` via `@Binds`.

**Create** `app/src/test/java/org/walktalkmeditate/pilgrim/widget/WorkManagerWidgetRefreshSchedulerTest.kt`:

Robolectric test that exercises `.build()` on the production class (Stage 2-F precedent — fakes hide builder crashes). Use `WorkManagerTestInitHelper.initializeTestWorkManager(context)` precedent. Tests:
- `scheduleRefresh()` enqueues a `OneTimeWorkRequest` under unique name `"widget_refresh"`.
- Subsequent `scheduleRefresh()` while one is pending coalesces (KEEP policy).
- Constraints: only StorageNotLow, NOT BatteryNotLow.

### Verify
```bash
./gradlew :app:testDebugUnitTest --tests "*.WorkManagerWidgetRefreshSchedulerTest"
```

---

## Task 7 — `WidgetRefreshWorker` (HiltWorker)

The worker that reads Room + writes state + triggers Glance re-render.

### Files

**Create** `app/src/main/java/org/walktalkmeditate/pilgrim/widget/WidgetRefreshWorker.kt`:

`@HiltWorker class WidgetRefreshWorker @AssistedInject constructor(@Assisted context, @Assisted params, walkRepository, widgetStateRepository) : CoroutineWorker(context, params)`.

`override suspend fun doWork(): Result`:
1. Read most-recent finished walk via `walkRepository.getMostRecentFinishedWalk()` (or equivalent — confirm DAO method name during implementation).
2. If non-null:
   - Read route samples via `walkRepository.observeLocationSamples(walkId).first()` (one-shot collect, no Flow ongoing). Sum via `GeoDistance.haversineMeters` between consecutive points → `distanceMeters`.
   - Compute `activeDurationMs` from the existing walk-stats helper if extractable, else from `endTimestampMs - startTimestamp - totalPaused - totalMeditated` (use whatever pattern WalkSummaryViewModel uses; if a helper exists in `domain/`, prefer that).
   - Write `WidgetState.LastWalk(walkId, endTimestampMs, distanceMeters, activeDurationMs)` to the repo.
3. If null: write `WidgetState.Empty`.
4. After repo write: `PilgrimWidget().updateAll(context)`. Suspend; runs on IO.
5. Return `Result.success()`.
6. CE re-throw discipline on every catch: `catch (ce: CancellationException) { throw ce } catch (t: Throwable) { Log.w(TAG, ..., t); Result.retry() OR Result.failure() }`. For network/transient errors: retry; for decode/null-pointer: failure (don't infinite-retry on logic bugs).
7. Implement `getForegroundInfo()` returning a minimal Notification (with the existing walk-tracking notification channel) so expedited-quota fallback path is satisfied — courtesy.

**Create** `app/src/test/java/org/walktalkmeditate/pilgrim/widget/WidgetRefreshWorkerTest.kt`:

Robolectric tests using `TestListenableWorkerBuilder<WidgetRefreshWorker>`:
- No finished walks → writes `Empty`, returns `Result.success()`.
- One finished walk with route samples → writes `LastWalk` with correct distance + duration. Avoid the Stage 8-B `viewModelScope+runBlocking` pattern: test the worker's `doWork()` directly, no VM in the loop.
- Fake `WalkRepository` (or real with in-memory Room) seeded with two walks → reads the more recent.
- DataStore corruption: pass a fake repo that throws on read → worker doesn't crash, returns `Result.failure()` and logs.

### Verify
```bash
./gradlew :app:testDebugUnitTest --tests "*.WidgetRefreshWorkerTest"
```

---

## Task 8 — Widget descriptor XML + preview drawable

Resources only — no Kotlin.

### Files

**Create** `app/src/main/res/xml/pilgrim_widget_info.xml`:

`<appwidget-provider>` with:
- `minWidth="110dp"`, `minHeight="110dp"` — supports systemSmall.
- `targetCellWidth="2"`, `targetCellHeight="2"` (Android 12+).
- `resizeMode="horizontal|vertical"`, `widgetCategory="home_screen"`.
- `previewLayout="@layout/pilgrim_widget_preview"` — minimal preview composable embedded in a layout.
- `initialLayout="@layout/pilgrim_widget_initial"` — required attribute, points to a static placeholder rendered before Glance attaches.
- **`updatePeriodMillis="86400000"`** — 24h declarative refresh for daily mantra rotation + relative-date label updates.

**Create** `app/src/main/res/drawable/pilgrim_widget_preview.xml`:

Vector drawable with parchment background + ink "図" or walking-figure placeholder text. Match iOS systemSmall preview aesthetic.

**Create** `app/src/main/res/layout/pilgrim_widget_initial.xml` + `app/src/main/res/layout/pilgrim_widget_preview.xml`:

Static FrameLayouts — single ImageView pointing at the drawable. Glance overrides immediately on attach.

### Verify
```bash
./gradlew :app:assembleDebug
```

---

## Task 9 — `PilgrimWidget` Glance composable + `PilgrimWidgetReceiver`

The widget Compose body itself + the receiver.

### Files

**Create** `app/src/main/java/org/walktalkmeditate/pilgrim/widget/PilgrimWidget.kt`:

`class PilgrimWidget : GlanceAppWidget()`.

`override suspend fun provideGlance(context: Context, id: GlanceId)`:
- Resolve `widgetStateRepository` via `EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java).widgetStateRepository()`.
- `provideContent { Body(stateFlow = repo.stateFlow, mantras = context.getString(R.string.widget_mantras), ...) }`.

`@Composable @GlanceComposable private fun Body(stateFlow, mantras)`:
- `val state by stateFlow.collectAsState(WidgetState.Empty)`.
- Compute today's date once via `remember { LocalDate.now() }`.
- Branch on state:
  - `LastWalk(...)`: render `LastWalkLayout(distance, duration, relativeDate(endTimestampMs, today))` + tap target = `actionStartActivity(intent for WalkSummary deep-link)`.
  - `Empty`: render `MantraLayout(MantraPool.phraseFor(today, mantras))` + tap target = `actionStartActivity(intent for Home deep-link)`.
- `LastWalkLayout` + `MantraLayout` are private composables using `GlanceModifier.fillMaxSize().background(parchment).padding(...)` + `Text` with `TextStyle(color = ColorProvider(ink))`.
- Two-size adaptation via `LocalSize.current` — small vs medium variants for `LastWalkLayout`.

`relativeDate(endMs, today): String`:
- Convert endMs to LocalDate at system zone.
- If same day → `R.string.widget_relative_today`.
- If yesterday → `R.string.widget_relative_yesterday`.
- Else → `R.string.widget_relative_n_days_ago` formatted with `String.format(Locale.ROOT, ..., daysBetween)`.

**Create** `app/src/main/java/org/walktalkmeditate/pilgrim/widget/PilgrimWidgetReceiver.kt`:

`class PilgrimWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = PilgrimWidget() }`. **NOT `@AndroidEntryPoint`** (receiver injects nothing).

### Verify
```bash
./gradlew :app:assembleDebug
```

---

## Task 10 — Strings + AndroidManifest registration

### Files

**Modify** `app/src/main/res/values/strings.xml` — append before `</resources>`:
```xml
<!-- Stage 9-A: Glance widget -->
<string name="widget_display_name">Pilgrim</string>
<string name="widget_description">A quiet mantra for your walk.</string>
<string name="widget_relative_today">Today</string>
<string name="widget_relative_yesterday">Yesterday</string>
<string name="widget_relative_n_days_ago">%1$d days ago</string>

<!-- TRANSLATORS: 10 short walking-meditation phrases joined by `|`.
     Translate the contemplative tone, keep the count, keep the
     delimiter. Used in the home-screen widget when the user has not
     yet completed any walks (one phrase per day, indexed by
     day-of-year). -->
<string name="widget_mantras">Walk well.|Every step is enough.|Begin where you are.|Slow is a speed.|Breathe with your feet.|Presence, step by step.|The path is the way.|Nowhere to arrive.|Solvitur ambulando.|One step is plenty.</string>
```

**Modify** `app/src/main/AndroidManifest.xml`:

1. Add `android:launchMode="singleTop"` to the existing `<activity android:name=".MainActivity">` element.
2. Inside `<application>`, register the receiver:
```xml
<receiver
    android:name=".widget.PilgrimWidgetReceiver"
    android:exported="false"
    android:label="@string/widget_display_name">
    <intent-filter>
        <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
    </intent-filter>
    <meta-data
        android:name="android.appwidget.provider"
        android:resource="@xml/pilgrim_widget_info" />
</receiver>
```

### Verify
```bash
./gradlew :app:assembleDebug
```

---

## Task 11 — `WalkViewModel.finishWalk` hook + test ctor sites

Wire the scheduler into the existing finish flow.

### Files

**Modify** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkViewModel.kt`:
1. Add ctor param `private val widgetRefreshScheduler: WidgetRefreshScheduler`.
2. After `transcriptionScheduler.scheduleForWalk(walkId)` and `collectiveRepository.recordWalk(...)` in `finishWalk()`, call `widgetRefreshScheduler.scheduleRefresh()`. No walkId argument; the worker reads "most recent" itself.

**Modify** `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkViewModelTest.kt`:
1. Add `private class FakeWidgetRefreshScheduler : WidgetRefreshScheduler { val callCount = AtomicInteger(0); override fun scheduleRefresh() { callCount.incrementAndGet() } }`.
2. Instantiate in @Before, pass to all 6 `WalkViewModel(...)` construction sites.
3. Add one regression test: `finishWalk schedules widget refresh` — calls finishWalk → asserts FakeWidgetRefreshScheduler.callCount == 1.

### Verify
```bash
./gradlew :app:testDebugUnitTest --tests "*.WalkViewModelTest"
```

---

## Task 12 — MainActivity intent extras + NavHost deep-link param

### Files

**Modify** `app/src/main/java/org/walktalkmeditate/pilgrim/MainActivity.kt`:
1. Hold `pendingDeepLink: MutableState<DeepLinkTarget?> = mutableStateOf(DeepLinkTarget.parse(intent))`.
2. Override `onNewIntent(intent: Intent)`:
```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    pendingDeepLink.value = DeepLinkTarget.parse(intent)
}
```
3. Pass `pendingDeepLink = pendingDeepLink.value` and `onDeepLinkConsumed = { pendingDeepLink.value = null }` to `PilgrimNavHost`.
4. Hilt-related: pendingDeepLink lives outside `setContent`; pass into composable via state hoisting.

**Modify** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHost.kt`:
1. Add params `pendingDeepLink: DeepLinkTarget? = null` and `onDeepLinkConsumed: () -> Unit = {}`.
2. After the existing PERMISSIONS-auto-nav-to-HOME logic, add a `LaunchedEffect(pendingDeepLink, onboardingComplete)`:
   - If onboardingComplete && pendingDeepLink == DeepLinkTarget.WalkSummary(id):
     - `navController.navigate(Routes.walkSummary(id)) { popUpTo(Routes.HOME) { saveState = false }; launchSingleTop = true }`.
     - This handles BOTH first-launch-with-deep-link AND already-running-with-onNewIntent: popUpTo HOME ensures back press lands on the journal scroll regardless of where the user was before the widget tap.
     - Call `onDeepLinkConsumed()`.
   - DeepLinkTarget.Home → `onDeepLinkConsumed()` only (we're already at HOME via the existing flow).
3. Both params have defaults so existing callers / tests are unaffected.

### Verify
```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest --tests "*.WalkViewModelTest" --tests "*.WidgetStateTest" --tests "*.WidgetStateRepositoryTest" --tests "*.MantraPoolTest" --tests "*.DeepLinkTargetTest" --tests "*.WidgetRefreshWorkerTest" --tests "*.WorkManagerWidgetRefreshSchedulerTest"
```

---

## Self-review

- **Spec coverage**: every Files-section entry maps to a task. 9 production files + 6 test files + 4 modified files all accounted for.
- **Task count**: 12 — within autopilot's 15-task threshold.
- **Type consistency**: `WidgetState`, `DeepLinkTarget`, `WidgetRefreshScheduler` consistent across layers.
- **No placeholders**.
- **Thread policy explicit per task**: Worker on Dispatchers.IO (CoroutineWorker default); composable on Glance's main; DataStore actor handles its own.
- **CE re-throw** in every catch (Throwable) wrapping suspend work: WidgetStateRepository.decode, WidgetRefreshWorker.doWork.
- **Test isolation**: per-test cancellable DataStore scope explicitly cancelled in @After (Stage 8-B closing-review pattern). No flow.first { predicate } patterns for state mutated by async coroutines.
- **WorkManager production-class .build() exercise** in WorkManagerWidgetRefreshSchedulerTest (Stage 2-F lesson).
- **No `viewModelScope.launch + Robolectric main-Looper deadlock`**: widget tests don't go through a VM. WalkViewModelTest's new test asserts ctor-wiring via call-count, not async state observation.
- **Locale.ROOT for digits** in relative-date "%d days ago" formatting (Stage 6-B / 8-A).
- **DataStore corruption handler** (`ReplaceFileCorruptionHandler { emptyPreferences() }`) on the WidgetStateRepository factory (Stage 8-B closing-review).
