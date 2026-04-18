# Stage 3-A Design — Home tab walk list

**Date:** 2026-04-18
**Stage:** 3-A (Phase 3 opener)
**Closes the gap:** Stage 2-F device test surfaced that finishing a walk left it permanently unreachable — no list, no nav target. This is the smallest change that unblocks "find my old walks."

## Key finding from Phase 1 UNDERSTAND

`HomeScreen.kt` already exists from Stage 1-E as a placeholder:
- Title + subtitle text (from `home_placeholder_title` / `home_placeholder_subtitle` string resources)
- "Start a walk" Button
- `BatteryExemptionCard`
- A one-shot `LaunchedEffect(Unit)` resume-check that routes to ActiveWalk if a walk is already in-progress or a Room row can be restored

The nav structure is already in place:
- `Routes.PERMISSIONS` (onboarding)
- `Routes.HOME` (the surface we're about to populate)
- `Routes.ACTIVE_WALK`
- `Routes.WALK_SUMMARY_PATTERN` — wired to take a walkId nav arg, already used by the Active → Summary transition in Stage 1

**Stage 3-A is therefore a content-swap, not a scaffolding build.** Replace the placeholder title+subtitle with a walk list. Keep the Start button, battery card, and resume-check exactly as they are.

## Recommended approach

Introduce a `HomeViewModel` that observes `WalkRepository.observeAllWalks().map { filter finished then map to rows }` and exposes a `HomeUiState` with Loading / Loaded(rows) / Empty states. Render in `HomeScreen` as a `LazyColumn` section replacing the current placeholder Text pair. Tap-to-open wires to `navController.navigate(Routes.walkSummary(row.walkId))`.

### Why not add rows inline into the existing Column?

`HomeScreen`'s current structure uses `verticalScroll(rememberScrollState())` at the root. Putting a `LazyColumn` inside `verticalScroll` would crash (nested scrolling with both vertical). Two options:

1. **Restructure the screen** — top-level is now `Scaffold` with the Start button in a `bottomBar`, main content is a `LazyColumn` for the walks section + a non-scrolling fixed header + the battery card as the first LazyColumn item.
2. **Keep `verticalScroll`** — render walks as a plain `Column`'s `forEach` (not LazyColumn). Acceptable at the current scale (tens of walks); pays off in simplicity.

Going with **option 2** for 3-A. The Pilgrim philosophy is "low volume of high-quality walks" — a user with 500 walks is Phase 6+ territory. `forEach` inside `verticalScroll` renders every row on first composition but Compose re-skips clean rows on subsequent recompositions. At 30 walks × ~120 dp per row = 3600 dp ≈ 4 screens of content; acceptable scroll inertia. If Stage 3-E's journal thread changes the layout sufficiently, we can switch to LazyColumn then.

### Why precompute formatted strings in the VM?

Each row has: relative date, duration, distance, recording-count caption. Computing `WalkFormat.duration()` / `WalkFormat.distance()` inside the composable would run on every recomposition, even when the underlying Walk didn't change. The VM maps Walk → `HomeWalkRow` (a data class with pre-formatted strings) so the row composable is just a pass-through of already-computed text. Matches the Stage 2-E pattern (VM maps Controller state → UI state).

## HomeWalkRow DTO

```kotlin
data class HomeWalkRow(
    val walkId: Long,
    val relativeDate: String,      // "Just now" / "2 hours ago" / "Apr 15"
    val durationText: String,      // from WalkFormat.duration(elapsed)
    val distanceText: String,      // from WalkFormat.distance(meters)
    val recordingCountText: String?, // "3 voice notes" or null if 0
    val intention: String?,        // walk.intention verbatim; null if empty
)
```

## HomeUiState

```kotlin
sealed class HomeUiState {
    data object Loading : HomeUiState()
    data class Loaded(val rows: List<HomeWalkRow>) : HomeUiState()
    data object Empty : HomeUiState()
}
```

Same three-state pattern as `WalkSummaryUiState` (Stage 2-E).

## HomeViewModel shape

```kotlin
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: WalkRepository,
    private val clock: Clock,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = repository.observeAllWalks()
        .map { walks -> walks.filter { it.endTimestamp != null } }
        .map { finished ->
            if (finished.isEmpty()) HomeUiState.Empty
            else HomeUiState.Loaded(finished.map { mapToRow(it) })
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS), HomeUiState.Loading)

    private suspend fun mapToRow(walk: Walk): HomeWalkRow { ... }
}
```

### Recording count per row: N+1 vs. batched

Each row needs `countVoiceRecordingsFor(walkId)`. Naive mapping does N queries per emission.

Options:
- A) **N+1 as-is.** At 30 walks, 30 small COUNT queries after each Flow emission. SQLite COUNT on indexed `walk_id` is ~microseconds each — total ~1 ms. Fine.
- B) **Batch DAO query** returning `Map<walkId, count>`. Single query. Cleaner but adds a new DAO method and test.

Going with A for 3-A — simplest, matches Stage 2-E's per-walk query pattern, scale doesn't demand optimization yet. If Stage 3-E's journal thread adds rendering cost, revisit then.

### Relative-date helper

`HomeFormat.relativeDate(timestampMs, nowMs)`:
- `< 1 min`: "Just now"
- `< 1 hour`: "12 minutes ago"
- `< 24 hours`: "5 hours ago"
- `< 7 days`: "Tuesday" (day name via `DateTimeFormatter.ofPattern("EEEE", Locale.US)`)
- `>= 7 days`: "Apr 15" (`DateTimeFormatter.ofPattern("MMM d", Locale.US)`)

Pure function; testable in isolation with `HomeFormatTest`. English-only for 3-A (consistent with Phase 2 string scope).

**Why not `DateUtils.getRelativeTimeSpanString`?** Locale-aware, bundled with Android, works on tests, BUT the output is fiddly to pin-test ("in 5 minutes" vs "5 mins ago" vs "5 min. ago" varies by API level + locale data). A hand-rolled helper is testable against fixed inputs + matches the iOS tone of "low-information date chrome."

## Row rendering

Plain Compose Row with:
- Left column (weight 1f): relativeDate (body), intention (caption italic if present), durationText · distanceText (caption)
- Right caption: recordingCountText (if present)
- No chevron, no icon, just tap target

Row is clickable. Visual: default Card container with a subtle elevation + `pilgrimColors.parchmentSecondary` background (matches the VoiceRecordingRow pattern from Stage 2-E).

## Navigation wiring

`HomeScreen` signature gains:
```kotlin
onEnterWalkSummary: (walkId: Long) -> Unit,
```

Passed from `PilgrimNavHost`:
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

The existing walk-summary route already accepts `walkId`, so no new Routes needed.

## Resume-check behavior on tap

When the user taps a row for a finished walk while on Home, we navigate to Summary. No resume-check because only finished walks are in the list. The existing resume-check only runs at Home's first composition for in-progress walks.

## Strings to add

```xml
<string name="home_title">Walks</string>
<string name="home_empty_message">No walks yet. Ready when you are.</string>
<string name="home_recording_count">%1$d voice notes</string>
<string name="home_recording_count_singular">1 voice note</string>
<string name="home_relative_just_now">Just now</string>
<string name="home_relative_minutes_ago">%1$d minutes ago</string>
<string name="home_relative_hours_ago">%1$d hours ago</string>
<!-- day-of-week + "MMM d" are raw DateTimeFormatter output; no string resource -->
```

Remove placeholder strings (`home_placeholder_title`, `home_placeholder_subtitle`) since the title becomes `home_title` and there's no subtitle — the empty state replaces the subtitle's role.

## Out of scope for 3-A

- Calligraphy thread between rows (3-E)
- Seasonal vignettes (3-D)
- Goshuin dots per walk (Phase 4)
- Cormorant Garamond header (3-B — pilgrimType hasn't loaded it yet)
- Walk detail screen beyond existing WalkSummaryScreen (3-F)
- Delete / search / filters (Phase 3 later or Phase 6+)
- LazyColumn switch (if Stage 3-E's journal thread demands it)

## Testing

- `HomeViewModelTest` (Robolectric + in-memory Room):
  - `Loading then Loaded when one finished walk exists`
  - `Empty when no finished walks exist`
  - `Loaded skips in-progress walks (endTimestamp null)`
  - `Loaded orders walks most-recent-first`
  - `Loaded row recording count reflects VoiceRecording rows`
  - `Loaded row intention passes through verbatim`
  - `Loaded row distance computed from route samples`

- `HomeFormatTest` (pure unit):
  - `Just now < 1 min`
  - `minutes-ago for 1-59 min`
  - `hours-ago for 1-23 hours`
  - `day-of-week for 1-6 days ago`
  - `MMM d for 7+ days ago`

No Compose UI tests (Phase N per port plan).

## Risks

1. **Breaking the existing resume-check.** HomeScreen's `LaunchedEffect(Unit)` is preserved verbatim. Tests don't exercise it at the VM level (it's Compose-scope); Stage 2-F's device test already validated the flow.

2. **Distance in the row requires route samples per walk.** Already in repository via `locationSamplesFor(walkId)`. Per-row distance calc runs on each Flow emission. At 30 walks with 100-1000 samples each that's a few ms. Acceptable.

3. **Clock injection for relative-date tests.** Pass the existing `Clock` into `HomeViewModel`; tests use `FakeClock` (the same one Stage 2-C uses in `WalkViewModelTest`).

## Quality gates

- All 7 HomeViewModelTest cases pass.
- All 5 HomeFormatTest cases pass.
- Lint + build clean.
- No placeholder strings remain unused (`home_placeholder_*` removed).
- Tests total: 160 → ~172.
