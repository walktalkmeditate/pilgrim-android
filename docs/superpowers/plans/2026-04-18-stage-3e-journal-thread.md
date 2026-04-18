# Stage 3-E Implementation Plan — Journal Thread Integration

Spec: `docs/superpowers/specs/2026-04-18-stage-3e-journal-thread-design.md`

Delete Stage 3-C's debug preview scaffolding, integrate `CalligraphyPath` into HomeScreen, wire hemisphere into both `HomeViewModel` and `WalkViewModel.finishWalk`.

---

## Task 1 — Extend `HomeWalkRow` with raw fields

**Edit:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeUiState.kt`

Add 4 new fields to the data class. Preserve all existing fields.

```kotlin
data class HomeWalkRow(
    val walkId: Long,
    // Raw fields (Stage 3-E): calligraphy path uses these to synthesize
    // a CalligraphyStrokeSpec per row. The formatted text fields below
    // are still cached for pass-through recomposition.
    val uuid: String,
    val startTimestamp: Long,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val relativeDate: String,
    val durationText: String,
    val distanceText: String,
    val recordingCountText: String?,
    val intention: String?,
)
```

Keep the `HomeUiState` sealed class unchanged.

---

## Task 2 — Update `HomeViewModel` to populate raw fields + inject HemisphereRepository

**Edit:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeViewModel.kt`

Add constructor parameter + new public property. Extend `mapToRow` to populate the 4 new raw fields.

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.walkDistanceMeters
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.Hemisphere
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.HemisphereRepository
import org.walktalkmeditate.pilgrim.ui.walk.WalkFormat

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: WalkRepository,
    private val clock: Clock,
    hemisphereRepository: HemisphereRepository,
) : ViewModel() {

    /**
     * Proxied from [HemisphereRepository] so the Home composable can
     * observe hemisphere flips independently of walk-list changes.
     * Separate StateFlow (not nested inside [HomeUiState]) because
     * hemisphere changes are rare and bundling would force row-list
     * recomposition on each flip.
     */
    val hemisphere: StateFlow<Hemisphere> = hemisphereRepository.hemisphere

    val uiState: StateFlow<HomeUiState> = repository.observeAllWalks()
        .map { walks ->
            val finished = walks.filter { it.endTimestamp != null }
            if (finished.isEmpty()) {
                HomeUiState.Empty
            } else {
                HomeUiState.Loaded(finished.map { mapToRow(it) })
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS),
            initialValue = HomeUiState.Loading,
        )

    private suspend fun mapToRow(walk: Walk): HomeWalkRow {
        val endMs = walk.endTimestamp ?: clock.now()
        val elapsedMs = endMs - walk.startTimestamp
        val samples = repository.locationSamplesFor(walk.id).map {
            LocationPoint(timestamp = it.timestamp, latitude = it.latitude, longitude = it.longitude)
        }
        val distance = walkDistanceMeters(samples)
        val recordingCount = repository.countVoiceRecordingsFor(walk.id)
        return HomeWalkRow(
            walkId = walk.id,
            uuid = walk.uuid,
            startTimestamp = walk.startTimestamp,
            distanceMeters = distance,
            durationSeconds = elapsedMs / 1000.0,
            relativeDate = HomeFormat.relativeDate(
                context = context,
                timestampMs = walk.startTimestamp,
                nowMs = clock.now(),
            ),
            durationText = WalkFormat.duration(elapsedMs),
            distanceText = WalkFormat.distance(distance),
            recordingCountText = HomeFormat.recordingCountLabel(context, recordingCount),
            intention = walk.intention?.takeIf { it.isNotBlank() },
        )
    }

    private companion object {
        const val SUBSCRIBER_GRACE_MS = 5_000L
    }
}
```

**Verify:**
```
./gradlew assembleDebug
```

---

## Task 3 — Hook `refreshFromLocationIfNeeded` into `WalkViewModel.finishWalk`

**Edit:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkViewModel.kt`

Add constructor parameter. Insert the refresh call inside `finishWalk()` after the voice-recorder settle gate, before the transcription-scheduler call.

Add import:
```kotlin
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.HemisphereRepository
```

Add to constructor:
```kotlin
@HiltViewModel
class WalkViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: WalkController,
    private val repository: WalkRepository,
    private val clock: Clock,
    private val voiceRecorder: VoiceRecorder,
    private val transcriptionScheduler: TranscriptionScheduler,
    private val locationSource: LocationSource,
    private val hemisphereRepository: HemisphereRepository,   // NEW
) : ViewModel() {
```

Edit `finishWalk` to insert the refresh call:

```kotlin
fun finishWalk() {
    viewModelScope.launch {
        controller.finishWalk()
        // [existing voice-recorder settle gate — unchanged]
        val settled = withTimeoutOrNull(FINISH_STOP_TIMEOUT_MS) {
            _voiceRecorderState.first { it !is VoiceRecorderUiState.Recording }
        }
        if (settled == null) {
            Log.w(TAG, "voice recorder did not settle within ${FINISH_STOP_TIMEOUT_MS}ms; scheduling anyway")
        }

        // Stage 3-E: cache hemisphere from the fresh-off-walk location
        // before Home reflows. Second try/catch layer is paranoia over
        // the repository's own internal resilience.
        try {
            hemisphereRepository.refreshFromLocationIfNeeded()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            Log.w(TAG, "hemisphere refresh on finishWalk failed", t)
        }

        walkIdOrNull(controller.state.value)?.let { walkId ->
            try {
                transcriptionScheduler.scheduleForWalk(walkId)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                Log.w(TAG, "scheduleForWalk($walkId) failed", t)
            }
        }
    }
}
```

---

## Task 4 — Delete Stage 3-C debug scaffolding

**Delete:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/calligraphy/CalligraphyPathPreview.kt`
**Delete:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/calligraphy/CalligraphyPathPreviewViewModel.kt`

**Edit:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHost.kt`

- Remove `import org.walktalkmeditate.pilgrim.BuildConfig`
- Remove `import org.walktalkmeditate.pilgrim.ui.design.calligraphy.CalligraphyPathPreviewScreen`
- Remove `const val CALLIGRAPHY_PREVIEW = "calligraphy_preview"` from `Routes`
- Remove the entire `if (BuildConfig.DEBUG) { composable(Routes.CALLIGRAPHY_PREVIEW) { ... } }` block
- Remove the `onEnterCalligraphyPreview = { ... }` lambda argument from the HomeScreen call

---

## Task 5 — Delete debug button + param from HomeScreen

**Edit:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeScreen.kt`

Remove:
- `import org.walktalkmeditate.pilgrim.BuildConfig`
- `import androidx.compose.material3.TextButton`
- `onEnterCalligraphyPreview: () -> Unit` parameter from `HomeScreen`
- The entire `if (BuildConfig.DEBUG) { ... TextButton(...) }` block below `BatteryExemptionCard`

---

## Task 6 — Integrate `CalligraphyPath` into HomeScreen's Loaded branch

**Edit:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeScreen.kt`

Full replacement of the file body. Threads hemisphere through from the VM; the Loaded branch wraps the Column in a Box with CalligraphyPath as a backdrop.

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.domain.isInProgress
import org.walktalkmeditate.pilgrim.permissions.PermissionsViewModel
import org.walktalkmeditate.pilgrim.ui.design.calligraphy.CalligraphyPath
import org.walktalkmeditate.pilgrim.ui.design.calligraphy.CalligraphyStrokeSpec
import org.walktalkmeditate.pilgrim.ui.design.calligraphy.SeasonalInkFlavor
import org.walktalkmeditate.pilgrim.ui.design.calligraphy.toSeasonalColor
import org.walktalkmeditate.pilgrim.ui.onboarding.BatteryExemptionCard
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.Hemisphere
import org.walktalkmeditate.pilgrim.ui.walk.WalkViewModel

private const val TAG = "HomeScreen"

// Approximate card-row stride (card ~116dp + PilgrimSpacing.normal 16dp
// gap). Drives CalligraphyPath's dot-Y placement so the thread feels
// connected to the card list without requiring per-card measurement.
// 3-F will verify on-device and may tune.
private val JOURNAL_ROW_STRIDE = 132.dp
private val JOURNAL_TOP_INSET = 24.dp

@Composable
fun HomeScreen(
    permissionsViewModel: PermissionsViewModel,
    onEnterActiveWalk: () -> Unit,
    onEnterWalkSummary: (Long) -> Unit,
    walkViewModel: WalkViewModel = hiltViewModel(),
    homeViewModel: HomeViewModel = hiltViewModel(),
) {
    val didResumeCheck = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (didResumeCheck.value) return@LaunchedEffect
        didResumeCheck.value = true
        val current = walkViewModel.uiState.value.walkState
        Log.i(TAG, "resume-check entry state=${current::class.simpleName}")
        if (current.isInProgress) {
            Log.i(TAG, "resume-check: already in progress, navigating to ActiveWalk")
            onEnterActiveWalk()
            return@LaunchedEffect
        }
        val restored = walkViewModel.restoreActiveWalk()
        if (restored != null) {
            Log.i(TAG, "resume-check: restored walk id=${restored.id}, navigating to ActiveWalk")
            onEnterActiveWalk()
        } else {
            Log.i(TAG, "resume-check: nothing to restore, staying on Home")
        }
    }

    val uiState by homeViewModel.uiState.collectAsStateWithLifecycle()
    val hemisphere by homeViewModel.hemisphere.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PilgrimSpacing.big),
    ) {
        Text(
            text = stringResource(R.string.home_title),
            style = pilgrimType.displayMedium,
            color = pilgrimColors.ink,
        )
        Spacer(Modifier.height(PilgrimSpacing.big))

        HomeListContent(
            uiState = uiState,
            hemisphere = hemisphere,
            onRowClick = onEnterWalkSummary,
        )

        Spacer(Modifier.height(PilgrimSpacing.big))

        Button(
            onClick = {
                walkViewModel.startWalk()
                onEnterActiveWalk()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.home_action_start_walk))
        }

        Spacer(Modifier.height(PilgrimSpacing.big))
        BatteryExemptionCard(viewModel = permissionsViewModel)
    }
}

@Composable
private fun HomeListContent(
    uiState: HomeUiState,
    hemisphere: Hemisphere,
    onRowClick: (Long) -> Unit,
) {
    when (uiState) {
        is HomeUiState.Loading -> {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = pilgrimColors.stone,
                )
            }
        }
        is HomeUiState.Empty -> {
            Text(
                text = stringResource(R.string.home_empty_message),
                style = pilgrimType.body,
                color = pilgrimColors.fog,
            )
        }
        is HomeUiState.Loaded -> {
            JournalThread(
                rows = uiState.rows,
                hemisphere = hemisphere,
                onRowClick = onRowClick,
            )
        }
    }
}

/**
 * The calligraphy-threaded walk list. Layers:
 *   1. [CalligraphyPath] canvas backdrop, sized to match the card stack.
 *   2. Column of [HomeWalkRowCard]s on top.
 *
 * Cards are opaque `parchmentSecondary`; the thread shows through the
 * 16dp gaps between them.
 */
@Composable
private fun JournalThread(
    rows: List<HomeWalkRow>,
    hemisphere: Hemisphere,
    onRowClick: (Long) -> Unit,
) {
    // Resolve the 4 base colors once per theme change.
    val inkBase = SeasonalInkFlavor.Ink
    val mossBase = SeasonalInkFlavor.Moss
    val rustBase = SeasonalInkFlavor.Rust
    val dawnBase = SeasonalInkFlavor.Dawn
    val inkColor = inkBase.toSeasonalColor(
        date = Instant.ofEpochMilli(rows.first().startTimestamp)
            .atZone(ZoneId.systemDefault())
            .toLocalDate(),
        hemisphere = hemisphere,
    )
    // (Per-walk color resolution needs the walk's date — we can't
    // precompute the four flavors' colors once because the date varies
    // per row. Do the seasonal resolve inline below.)

    val strokes = rows.map { row ->
        val walkDate = Instant.ofEpochMilli(row.startTimestamp)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        val flavor = SeasonalInkFlavor.forMonth(row.startTimestamp)
        val tint = flavor.toSeasonalColor(walkDate, hemisphere)
        val pace = if (row.distanceMeters > 0.0 && row.durationSeconds > 0.0) {
            row.durationSeconds / (row.distanceMeters / 1000.0)
        } else {
            0.0
        }
        CalligraphyStrokeSpec(
            uuid = row.uuid,
            startMillis = row.startTimestamp,
            distanceMeters = row.distanceMeters,
            averagePaceSecPerKm = pace,
            ink = tint,
        )
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        // Layer 1: calligraphy thread backdrop.
        CalligraphyPath(
            strokes = strokes,
            modifier = Modifier.fillMaxWidth(),
            verticalSpacing = JOURNAL_ROW_STRIDE,
            topInset = JOURNAL_TOP_INSET,
        )
        // Layer 2: cards on top.
        Column(verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal)) {
            rows.forEach { row ->
                HomeWalkRowCard(
                    row = row,
                    onClick = { onRowClick(row.walkId) },
                )
            }
        }
    }

    // Touch unused refs so they don't trigger "unused" warnings during
    // iterative development. Delete these lines during polish.
    @Suppress("UNUSED_VARIABLE")
    val _touch = listOf(inkColor, mossBase, rustBase, dawnBase)
}
```

> **Self-note for implementation.** The `inkColor` + `mossBase`/etc.
> references at the top of `JournalThread` above were scratch space
> during design. The final code DOES NOT need them — remove the
> placeholder block when writing the real file. The only body needed
> inside `JournalThread` is (1) `val strokes = rows.map { ... }` and
> (2) the `Box { CalligraphyPath(...); Column { rows.forEach { ... } } }`.

**Clean final body for Task 6's `JournalThread` (use this, not the draft above):**

```kotlin
@Composable
private fun JournalThread(
    rows: List<HomeWalkRow>,
    hemisphere: Hemisphere,
    onRowClick: (Long) -> Unit,
) {
    val strokes: List<CalligraphyStrokeSpec> = rows.map { row ->
        val walkDate = Instant.ofEpochMilli(row.startTimestamp)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        val tint = SeasonalInkFlavor.forMonth(row.startTimestamp)
            .toSeasonalColor(walkDate, hemisphere)
        val pace = if (row.distanceMeters > 0.0 && row.durationSeconds > 0.0) {
            row.durationSeconds / (row.distanceMeters / 1000.0)
        } else {
            0.0
        }
        CalligraphyStrokeSpec(
            uuid = row.uuid,
            startMillis = row.startTimestamp,
            distanceMeters = row.distanceMeters,
            averagePaceSecPerKm = pace,
            ink = tint,
        )
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        CalligraphyPath(
            strokes = strokes,
            modifier = Modifier.fillMaxWidth(),
            verticalSpacing = JOURNAL_ROW_STRIDE,
            topInset = JOURNAL_TOP_INSET,
        )
        Column(verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal)) {
            rows.forEach { row ->
                HomeWalkRowCard(
                    row = row,
                    onClick = { onRowClick(row.walkId) },
                )
            }
        }
    }
}
```

---

## Task 7 — Update `HomeViewModelTest`

**Edit:** `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/HomeViewModelTest.kt`

1. Add a `FakeHemisphereRepository` at the top (test-internal class):

```kotlin
private class FakeHemisphereRepository(
    initial: Hemisphere = Hemisphere.Northern,
) {
    val state = MutableStateFlow(initial)
    // The real repository has non-trivial constructor deps. The VM
    // only reads `.hemisphere`; subclass it via composition so the
    // test doesn't need to construct DataStore/LocationSource.
}
```

Hmm — `HemisphereRepository` is a concrete class, not an interface. The cleanest path for the test is to construct a real `HemisphereRepository` using `PreferenceDataStoreFactory.create(...)` + a `FakeLocationSource`, just like `HemisphereRepositoryTest` already does. Reuse that setup.

2. In each existing test body, inject the repo:

```kotlin
val hemisphereRepo = HemisphereRepository(
    dataStore = PreferenceDataStoreFactory.create(
        produceFile = { context.preferencesDataStoreFile("home-vm-test") },
    ),
    locationSource = FakeLocationSource(),
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
)
val vm = HomeViewModel(context, repository, clock, hemisphereRepo)
```

3. Assert new fields populated:

```kotlin
@Test
fun `loaded rows include raw fields for journal thread`() = runTest {
    // insert one finished walk...
    val state = vm.uiState.first { it is HomeUiState.Loaded } as HomeUiState.Loaded
    val row = state.rows.single()
    assertEquals(walk.uuid, row.uuid)
    assertEquals(walk.startTimestamp, row.startTimestamp)
    assertTrue(row.distanceMeters >= 0.0)
    assertTrue(row.durationSeconds >= 0.0)
}
```

4. New test for hemisphere proxy:

```kotlin
@Test
fun `hemisphere proxies repository`() = runTest {
    assertEquals(Hemisphere.Northern, vm.hemisphere.value)
    hemisphereRepo.setOverride(Hemisphere.Southern)
    val observed = withContext(Dispatchers.Default.limitedParallelism(1)) {
        withTimeout(3_000L) {
            vm.hemisphere.first { it == Hemisphere.Southern }
        }
    }
    assertEquals(Hemisphere.Southern, observed)
}
```

**Verify:**
```
./gradlew testDebugUnitTest --tests '*HomeViewModelTest*'
```

---

## Task 8 — Update `WalkViewModelTest` to cover the new finishWalk hook

**Edit:** `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkViewModelTest.kt`

1. Add `FakeHemisphereRepository` or reuse the real one with a `FakeLocationSource`. Track call count:

```kotlin
// Track whether refreshFromLocationIfNeeded was invoked at least once.
val hemisphereRepo = HemisphereRepository(
    dataStore = PreferenceDataStoreFactory.create(
        produceFile = { context.preferencesDataStoreFile("walk-vm-test") },
    ),
    locationSource = locationSource,  // existing FakeLocationSource
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
)
```

2. New test: finish → repo's DataStore reflects the inferred hemisphere.

```kotlin
@Test
fun `finishWalk infers hemisphere from current location`() = runTest {
    locationSource.lastKnown = LocationPoint(
        timestamp = 0L, latitude = -33.8688, longitude = 151.2093,   // Sydney
    )
    val vm = WalkViewModel(context, controller, repository, clock,
        voiceRecorder, transcriptionScheduler, locationSource, hemisphereRepo)
    vm.startWalk()
    // ... advance the controller so walkId is present ...
    vm.finishWalk()
    val observed = withContext(Dispatchers.Default.limitedParallelism(1)) {
        withTimeout(3_000L) {
            hemisphereRepo.hemisphere.first { it == Hemisphere.Southern }
        }
    }
    assertEquals(Hemisphere.Southern, observed)
}
```

3. New test: refresh failure is silently logged, doesn't break finish:

```kotlin
@Test
fun `finishWalk swallows hemisphere refresh failures`() = runTest {
    val throwingSource = object : LocationSource {
        override fun locationFlow() = emptyFlow<LocationPoint>()
        override suspend fun lastKnownLocation(): LocationPoint? {
            throw SecurityException("permission denied")
        }
    }
    val throwingRepo = HemisphereRepository(
        dataStore = PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile("walk-vm-throw-test") },
        ),
        locationSource = throwingSource,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )
    val vm = WalkViewModel(context, controller, repository, clock,
        voiceRecorder, transcriptionScheduler, locationSource, throwingRepo)
    vm.startWalk()
    vm.finishWalk()
    // Should reach here without throwing. Transcription scheduling
    // still happens — the refresh is a best-effort prefix.
}
```

**Verify:**
```
./gradlew testDebugUnitTest --tests '*WalkViewModelTest*'
```

---

## Task 9 — Full CI gate

```
./gradlew assembleDebug lintDebug testDebugUnitTest
```

Expected: green. The existing 57 tests (28 from 3-C + 29 from 3-D) plus the new/updated HomeViewModelTest + WalkViewModelTest pass.

---

## Out-of-plan notes

- **No strings.xml changes.** The debug button used a hardcoded string; its deletion means no strings need to go.
- **No Manifest changes.** The preview route isn't declared in the manifest.
- **No Gradle changes.** All dependencies are already on main.
- **Hilt wiring** — `HemisphereRepository` is a `@Singleton @Inject` class; adding it as a constructor dep on `HomeViewModel` + `WalkViewModel` requires no Hilt module changes (the graph already binds it from Stage 3-D).
- **`CalligraphyStrokeSpec` import path:** `org.walktalkmeditate.pilgrim.ui.design.calligraphy.CalligraphyStrokeSpec`.
- **`toSeasonalColor` import path:** `org.walktalkmeditate.pilgrim.ui.design.calligraphy.toSeasonalColor` (extension function).
- **`pilgrimColors` read inside `JournalThread`**: not needed directly — `toSeasonalColor` internally reads `pilgrimColors` via `@ReadOnlyComposable toBaseColor()`.
- **The `bulk fetch` perf concern flagged in the 3-C memory:** `HomeViewModel.mapToRow` still does N separate `locationSamplesFor(walk.id)` calls per emission. Stage 3-E does NOT address this; it's flagged for a future stage. For tens of walks it's fine.
