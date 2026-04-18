# Stage 3-A Implementation Plan — Home tab walk list

**Spec:** [2026-04-18-stage-3a-home-tab-walk-list-design.md](../specs/2026-04-18-stage-3a-home-tab-walk-list-design.md)

**Test command:**
```bash
JAVA_HOME=/Users/rubberduck/.asdf/installs/java/temurin-17.0.18+8 ANDROID_HOME=/Users/rubberduck/Library/Android/sdk PATH=/Users/rubberduck/.asdf/installs/java/temurin-17.0.18+8/bin:$PATH ./gradlew assembleDebug lintDebug testDebugUnitTest
```

Task order: 1 → 10. Full CI gate after each task that compiles.

---

## Task 1 — HomeFormat utility + tests

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeFormat.kt` (new)

Pure-function helpers used by the VM:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import org.walktalkmeditate.pilgrim.R

object HomeFormat {
    private val dayOfWeekFormatter = DateTimeFormatter.ofPattern("EEEE", Locale.US)
    private val shortDateFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)

    /**
     * Relative-date label for the home list. Defaults to absolute ("MMM d")
     * for anything 7+ days old so the list doesn't fill with "23 days ago".
     *
     * Thresholds (nowMs − timestampMs):
     *  - < 1 min:      "Just now"
     *  - < 60 min:     "%d minutes ago"
     *  - < 24 hours:   "%d hours ago"
     *  - < 7 days:     day name ("Tuesday")
     *  - otherwise:    "MMM d"
     */
    fun relativeDate(context: Context, timestampMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val deltaMs = (nowMs - timestampMs).coerceAtLeast(0L)
        val minutes = deltaMs / 60_000L
        if (minutes < 1) return context.getString(R.string.home_relative_just_now)
        if (minutes < 60) return context.getString(R.string.home_relative_minutes_ago, minutes.toInt())
        val hours = minutes / 60L
        if (hours < 24) return context.getString(R.string.home_relative_hours_ago, hours.toInt())
        val instant = Instant.ofEpochMilli(timestampMs)
        val localDate = instant.atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val daysBetween = ChronoUnit.DAYS.between(localDate, today)
        return if (daysBetween < 7) dayOfWeekFormatter.format(localDate)
        else shortDateFormatter.format(localDate)
    }

    /** "3 voice notes" / "1 voice note" / null if count == 0. */
    fun recordingCountLabel(context: Context, count: Int): String? = when {
        count <= 0 -> null
        count == 1 -> context.getString(R.string.home_recording_count_singular)
        else -> context.getString(R.string.home_recording_count, count)
    }
}
```

**File:** `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/HomeFormatTest.kt` (new)

Robolectric test (needs Context for string resources):

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeFormatTest {
    private lateinit var context: Context
    private val zone = ZoneId.of("UTC")

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test fun `just now when under 1 minute`() { ... }
    @Test fun `minutes ago for 1-59 min`() { ... }
    @Test fun `hours ago for 1-23 hours`() { ... }
    @Test fun `day of week for 1-6 days ago`() { ... }
    @Test fun `MMM d for 7+ days ago`() { ... }
    @Test fun `recording count null when zero`() { ... }
    @Test fun `recording count singular when one`() { ... }
    @Test fun `recording count plural when many`() { ... }
}
```

---

## Task 2 — Add strings + remove placeholders

**File:** `app/src/main/res/values/strings.xml` (modify)

Remove: `home_placeholder_title`, `home_placeholder_subtitle`.

Add:
```xml
<string name="home_title">Walks</string>
<string name="home_empty_message">No walks yet. Ready when you are.</string>
<string name="home_recording_count">%1$d voice notes</string>
<string name="home_recording_count_singular">1 voice note</string>
<string name="home_relative_just_now">Just now</string>
<string name="home_relative_minutes_ago">%1$d minutes ago</string>
<string name="home_relative_hours_ago">%1$d hours ago</string>
```

Keep: `home_action_start_walk`, `home_action_resume_walk`.

---

## Task 3 — HomeWalkRow + HomeUiState

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeUiState.kt` (new)

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home

data class HomeWalkRow(
    val walkId: Long,
    val relativeDate: String,
    val durationText: String,
    val distanceText: String,
    val recordingCountText: String?,
    val intention: String?,
)

sealed class HomeUiState {
    data object Loading : HomeUiState()
    data class Loaded(val rows: List<HomeWalkRow>) : HomeUiState()
    data object Empty : HomeUiState()
}
```

---

## Task 4 — HomeViewModel

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeViewModel.kt` (new)

```kotlin
@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: WalkRepository,
    private val clock: Clock,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = repository.observeAllWalks()
        .map { walks ->
            val finished = walks.filter { it.endTimestamp != null }
            if (finished.isEmpty()) HomeUiState.Empty
            else HomeUiState.Loaded(finished.map { mapToRow(it) })
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS),
            initialValue = HomeUiState.Loading,
        )

    private suspend fun mapToRow(walk: Walk): HomeWalkRow {
        val startMs = walk.startTimestamp
        val endMs = walk.endTimestamp ?: clock.now()
        val elapsed = endMs - startMs
        val samples = repository.locationSamplesFor(walk.id)
        val distance = walkDistanceMeters(samples.map {
            LocationPoint(it.timestamp, it.latitude, it.longitude)
        })
        val recordingCount = repository.countVoiceRecordingsFor(walk.id)
        return HomeWalkRow(
            walkId = walk.id,
            relativeDate = HomeFormat.relativeDate(context, startMs, clock.now()),
            durationText = WalkFormat.duration(elapsed),
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

Note: `mapToRow` is suspend because `locationSamplesFor` + `countVoiceRecordingsFor` are suspend. Since it's called inside `.map { }` on a Flow, the block is suspend-capable (Flow.map's transform is suspend).

---

## Task 5 — HomeViewModelTest

**File:** `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/HomeViewModelTest.kt` (new)

Robolectric + in-memory Room + FakeClock. Seven test cases per the spec. Use `Turbine` for Flow collection (`vm.uiState.test { ... }`).

Cases:
1. `Loading then Loaded when one finished walk exists`
2. `Empty when no finished walks exist`
3. `Loaded skips in-progress walks (endTimestamp null)`
4. `Loaded orders walks most-recent-first`
5. `Loaded row recording count reflects VoiceRecording rows`
6. `Loaded row intention passes through verbatim`
7. `Loaded row distance computed from route samples`

Pattern mirrors `WalkSummaryViewModelTest` setUp (in-memory DB, repository construction, FakeClock).

---

## Task 6 — Plumb onEnterWalkSummary through PilgrimNavHost

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHost.kt` (modify)

```kotlin
composable(Routes.HOME) {
    HomeScreen(
        permissionsViewModel = permissionsViewModel,
        onEnterActiveWalk = { navController.navigate(Routes.ACTIVE_WALK) },
        onEnterWalkSummary = { walkId ->
            navController.navigate(Routes.walkSummary(walkId))
        },
    )
}
```

---

## Task 7 — HomeScreen content swap

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeScreen.kt` (modify)

Replace the placeholder Text pair with:

- `Text(stringResource(R.string.home_title))` using `pilgrimType.displayMedium` + `pilgrimColors.ink`
- Dispatch on `uiState`:
  - `Loading` → small CircularProgressIndicator (same pattern as WalkSummaryScreen's LoadingRow)
  - `Empty` → single centered muted `Text(home_empty_message)`
  - `Loaded` → forEach row, render a `HomeWalkRow` composable

Add signature: `onEnterWalkSummary: (walkId: Long) -> Unit`.

Add `viewModel: HomeViewModel = hiltViewModel()` and collect `uiState` with `collectAsStateWithLifecycle()`.

Keep:
- The `LaunchedEffect(Unit)` resume-check
- The "Start a walk" Button
- `BatteryExemptionCard`
- Scaffold structure (verticalScroll + Column + padding)

Rows use the parchmentSecondary-backed Card pattern from `VoiceRecordingRow` (Stage 2-E) for visual consistency.

---

## Task 8 — HomeWalkRow composable

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeWalkRowComposable.kt` (new)

```kotlin
@Composable
fun HomeWalkRowCard(
    row: HomeWalkRow,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = pilgrimColors.parchmentSecondary),
    ) {
        Column(modifier = Modifier.padding(PilgrimSpacing.normal), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(row.relativeDate, style = pilgrimType.body, color = pilgrimColors.ink)
            if (row.intention != null) {
                Text(row.intention, style = pilgrimType.caption, color = pilgrimColors.fog, fontStyle = FontStyle.Italic)
            }
            Text("${row.durationText} · ${row.distanceText}", style = pilgrimType.caption, color = pilgrimColors.fog)
            if (row.recordingCountText != null) {
                Text(row.recordingCountText, style = pilgrimType.caption, color = pilgrimColors.fog)
            }
        }
    }
}
```

---

## Task 9 — Full CI gate

```bash
./gradlew assembleDebug lintDebug testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, 160 → ~168 tests passing (8 HomeFormatTest + 7 HomeViewModelTest minus some overlap with existing), no new lint.

---

## Task 10 — Commit

```
feat(home): Stage 3-A — walk list on Home screen

Replaces the placeholder title+subtitle with a list of finished walks
in recency order. Tapping a row opens the existing WalkSummaryScreen.
Closes the UX gap surfaced by Stage 2-F device test (a finished walk
had no nav target once the user left its summary).

- HomeViewModel observes observeAllWalks(), filters to finished,
  maps each to a HomeWalkRow DTO with pre-formatted relative-date +
  duration + distance + voice-note count + optional intention.
  VM-side formatting keeps row recomposition cheap.
- HomeUiState: Loading / Loaded(rows) / Empty. Same pattern as Stage
  2-E's WalkSummaryUiState.
- HomeScreen: content-swap. Placeholder strings removed. Column +
  verticalScroll preserved (no LazyColumn to avoid nested-scroll
  crash). Start button + BatteryExemptionCard + resume-check kept.
- Navigation: onEnterWalkSummary callback wired through
  PilgrimNavHost to Routes.walkSummary(walkId).
- HomeFormat: hand-rolled relative-date helper (Just now / N min ago
  / N hours ago / day-of-week / MMM d). Pure-function, Robolectric-
  tested. English-only for 3-A.
- Voice-note count: N+1 per-walk countVoiceRecordingsFor queries at
  SQLite scale is ~microseconds each. Acceptable for expected scale
  (tens of walks). Batched query if Stage 3-E needs it.

Tests: 160 → NN (+8 HomeFormatTest, +7 HomeViewModelTest).
```
