# Stage 3-E: Journal Thread Integration — Design Spec

**Date:** 2026-04-18
**Status:** Draft (awaiting CHECKPOINT 1 approval)
**Prior art:**
- Stage 3-C's `CalligraphyPath` (`ui/design/calligraphy/`)
- Stage 3-D's `SeasonalColorEngine` + `HemisphereRepository` (`ui/theme/seasonal/`)
- iOS's `InkScrollView.swift` (inspiration only — not pixel-match)

---

## Context

Stages 3-A → 3-D built all the pieces:
- 3-A: Home walk list with `HomeViewModel` and `HomeWalkRowCard`
- 3-C: `CalligraphyPath` Compose Canvas renderer + debug preview screen
- 3-D: `SeasonalColorEngine` + `HemisphereRepository` to tint by date + hemisphere

Stage 3-E is the payoff — the decorative ink thread ships on Home. The debug preview screen is deleted; the renderer lands under the walk list cards, colored by the walk's date + the device's inferred hemisphere. The thread ties walks together visually the way iOS's journal does, without the 1:1 dot-to-card alignment that iOS achieves via layout measurement.

---

## Goals

1. **Delete all Stage 3-C debug scaffolding.** `CalligraphyPathPreview.kt`, `CalligraphyPathPreviewViewModel.kt`, the debug button on Home, the `CALLIGRAPHY_PREVIEW` route, the `BuildConfig.DEBUG` gate around the route and its callback.
2. **Integrate `CalligraphyPath` into `HomeScreen`.** Ink thread paints as a full-width backdrop behind the card list; cards float on top.
3. **Upgrade `HomeViewModel` to feed the thread.** Inject `HemisphereRepository`, extend `HomeWalkRow` with the raw fields the thread needs (uuid, startTimestamp, distanceMeters, durationSeconds), expose hemisphere as a second StateFlow.
4. **Cache hemisphere on walk finish.** `WalkViewModel.finishWalk()` calls `hemisphereRepository.refreshFromLocationIfNeeded()` after the voice recorder settles but before the `Home` screen re-observes, so the first-walk path inherits the correct hemisphere without a second round-trip.
5. **Tests.** Update `HomeViewModelTest` to cover the new fields + hemisphere injection; update `WalkViewModelTest` to prove `refreshFromLocationIfNeeded` is called during finish.

## Non-goals

- **Pixel-perfect iOS parity.** iOS's `InkScrollView` measures walk-dot positions and aligns them to specific Y coordinates. Our MVP paints the thread as a backdrop with approximate card-row-stride spacing — the thread reads as "connected" without needing to hit each card's center.
- **LazyColumn migration.** HomeScreen still uses `Column + verticalScroll + forEach`. A LazyColumn upgrade is a separate story (probably Phase 4 or later).
- **Per-dot card alignment via `onGloballyPositioned`.** Tempting but adds layout-measurement complexity + recomposition loops. Defer unless on-device QA reveals the approximation looks bad.
- **Single-walk "lone dot" rendering.** Current `CalligraphyPath` collapses to 0.dp for <2 strokes (correct — the renderer is a connector). 3-E does not add a dot-only fallback; the card alone is enough visual content for a first-ever walk.
- **Lunar markers / moon-phase indicators.** iOS has these overlaid on the thread; reserved for Phase 4 (goshuin) or later.
- **Milestone markers** (torii gates at 100 km, 500 km). Phase 4+.
- **Breathing / idle animation on the thread.** Not this stage.

---

## Architecture

### Layout strategy: `Box` with Canvas backdrop + Column of cards

```
┌── Modifier.fillMaxSize() ──────────────────────────┐
│                                                     │
│  Column (outer scroll container)                    │
│    Text("Walks")                                    │
│    Spacer                                           │
│    ┌── Box (thread + card stack) ───────────────┐  │
│    │                                             │  │
│    │  [Layer 1, drawn first]                    │  │
│    │  CalligraphyPath(strokes, modifier =       │  │
│    │    Modifier.matchParentSize())             │  │
│    │                                             │  │
│    │  [Layer 2, drawn second → on top]          │  │
│    │  Column(spacedBy = PilgrimSpacing.normal)  │  │
│    │    HomeWalkRowCard(...)                    │  │
│    │    HomeWalkRowCard(...)                    │  │
│    │    HomeWalkRowCard(...)                    │  │
│    │                                             │  │
│    └─────────────────────────────────────────────┘  │
│    Spacer                                           │
│    Button("Start a walk")                           │
│    Spacer                                           │
│    BatteryExemptionCard                             │
│                                                     │
└─────────────────────────────────────────────────────┘
```

Compose `Box` stacks children z-order in declaration order. Canvas is the first child (bottom); Column of cards is the second (top). The Canvas gets `Modifier.matchParentSize()` so the Box's intrinsic size (sum of card heights + spacing) drives the Canvas's bounds.

**Trade-off:** `CalligraphyPath`'s `verticalSpacing` parameter controls its internal dot-Y placement, which we've been approximating at 90dp. To feel "connected" under the card stack, 3-E bumps the default to match the Android card cadence — roughly 132dp per row (card ~116dp + gap 16dp). If the thread doesn't visually hit card centers after on-device testing, 3-F can revisit.

**Why not `Modifier.drawBehind`?** `drawBehind` doesn't give us the `CalligraphyPath` Composable's built-in height calculation + internal `remember { Path() }` hygiene. Keeping `CalligraphyPath` as a first-class Composable sibling is simpler + preserves Stage 3-C's extracted testability.

### Data flow

```
WalkViewModel.finishWalk()
    ↓ (after voice settle)
hemisphereRepository.refreshFromLocationIfNeeded()
    ↓ (writes to DataStore if first time)
HemisphereRepository.hemisphere: StateFlow<Hemisphere>
    ↓
HomeViewModel (injects HemisphereRepository)
    ↓ (exposes hemisphere separately from uiState)
HomeScreen
    ↓
    - reads hemisphere via collectAsStateWithLifecycle
    - reads uiState (HomeUiState.Loaded with rows including raw fields)
    - synthesizes List<CalligraphyStrokeSpec> inside remember(...) block:
        for each HomeWalkRow:
            SeasonalInkFlavor.forMonth(row.startTimestamp)
                .toSeasonalColor(LocalDate(row.startTimestamp), hemisphere, Moderate)
            CalligraphyStrokeSpec(uuid, startMillis, distanceMeters, pace, ink)
    ↓
CalligraphyPath(strokes = ...)
```

### `HomeWalkRow` extension

Currently carries UI text only. 3-E adds the raw fields needed for stroke synthesis. Keeping them on the UI DTO (vs a parallel strokes list exposed from the VM) means the composable does one mapping step instead of the VM handling two parallel lists.

```kotlin
data class HomeWalkRow(
    val walkId: Long,
    // NEW in 3-E:
    val uuid: String,                // walk.uuid — FNV-1a hash seed
    val startTimestamp: Long,         // walk.startTimestamp — seasonal + seed
    val distanceMeters: Double,       // raw distance — pace + seed
    val durationSeconds: Double,      // raw duration — pace
    // Existing text fields (unchanged):
    val relativeDate: String,
    val durationText: String,
    val distanceText: String,
    val recordingCountText: String?,
    val intention: String?,
)
```

The `distanceMeters` / `durationSeconds` fields look redundant with `distanceText` / `durationText`, but the text forms carry locale + formatting that the stroke math can't consume. Cheap to carry both; the formatted versions are cached (no re-formatting per recomposition), the raw versions are cheap `Double`s. Total struct size grows by ~32 bytes per row.

### `HomeViewModel` shape

```kotlin
@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: WalkRepository,
    private val clock: Clock,
    hemisphereRepository: HemisphereRepository,  // NEW
) : ViewModel() {

    val hemisphere: StateFlow<Hemisphere> = hemisphereRepository.hemisphere  // NEW

    val uiState: StateFlow<HomeUiState> = /* existing, extended mapToRow returns raw fields */
}
```

Hemisphere lives as a sibling StateFlow rather than being nested in `HomeUiState.Loaded`. Reason: hemisphere changes are rare (manual override or first-walk location inference), and keeping it separate means the "rows changed" recomposition doesn't have to bundle a hemisphere value that didn't change. Composition-local semantics work cleanly: Home's `strokes = remember(rows, hemisphere) { ... }` re-keys on either signal.

### `WalkViewModel.finishWalk` hook

Place the refresh call right after the voice-recorder settle gate, before the transcription scheduler call:

```kotlin
fun finishWalk() {
    viewModelScope.launch {
        controller.finishWalk()
        val settled = withTimeoutOrNull(FINISH_STOP_TIMEOUT_MS) {
            _voiceRecorderState.first { it !is VoiceRecorderUiState.Recording }
        }
        if (settled == null) {
            Log.w(TAG, "voice recorder did not settle within ${FINISH_STOP_TIMEOUT_MS}ms; scheduling anyway")
        }
        // NEW in 3-E: cache hemisphere before Home re-observes.
        // Wrapped in try/catch to match the existing resilience pattern.
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
            } /* ... existing transcription path ... */
        }
    }
}
```

`HemisphereRepository` is `@Singleton`-bound via Hilt; simply add it as a constructor dependency on `WalkViewModel`.

**Why here?** The user just gave the walk permission to finish — location is freshest here. Running it in `init` is redundant (the repository's internal try/catch already handles pre-permission cases), and doing it in `HomeViewModel.init` loses the "location is live right now" advantage. `finishWalk` also runs once per walk, so we can't spam DataStore.

### Files changed (summary)

**Deleted:**
- `ui/design/calligraphy/CalligraphyPathPreview.kt`
- `ui/design/calligraphy/CalligraphyPathPreviewViewModel.kt`

**Modified:**
- `ui/home/HomeScreen.kt` — delete debug button, replace `HomeListContent` Loaded path with Box + CalligraphyPath + Column
- `ui/home/HomeViewModel.kt` — inject HemisphereRepository, expose hemisphere StateFlow, extend mapToRow to populate raw fields
- `ui/home/HomeUiState.kt` — extend `HomeWalkRow` with 4 new raw fields
- `ui/walk/WalkViewModel.kt` — inject HemisphereRepository, call refresh in finishWalk
- `ui/navigation/PilgrimNavHost.kt` — delete `CALLIGRAPHY_PREVIEW` route + BuildConfig.DEBUG block

**Tests modified:**
- `ui/home/HomeViewModelTest.kt` — inject a fake/stub HemisphereRepository, assert new raw fields on HomeWalkRow
- `ui/walk/WalkViewModelTest.kt` — add a test that finishWalk calls refreshFromLocationIfNeeded exactly once, with the same try/catch-Throwable resilience as the transcription scheduler call

**No new production files.** Just deletions and modifications.

---

## Error handling / edge cases

| Edge case | Behavior |
|---|---|
| Zero finished walks | `HomeUiState.Empty`. No CalligraphyPath rendered; existing empty-message branch handles it unchanged. |
| One finished walk | `HomeUiState.Loaded` with one row. `CalligraphyPath` collapses to 0.dp (Stage 3-C behavior unchanged). Card renders solo; thread is invisible. Acceptable for a first walk. |
| Hemisphere unknown at Home entry | Repository emits `Northern` as initial value. Thread tints using Northern-hemisphere seasons until `refreshFromLocationIfNeeded` (triggered by `WalkViewModel.finishWalk` or by the preview VM, which we're deleting) updates it. |
| Hemisphere updates mid-view | Composition re-reads via `collectAsStateWithLifecycle`; `remember(rows, hemisphere)` re-memoizes strokes; CalligraphyPath recomposes with new inks. Smooth transition — no reset of scroll position. |
| Walk list of 100+ finished walks | Column+forEach renders all cards + the single Canvas. Stage 3-C noted this non-lazy render is acceptable for "tens, maybe low hundreds" of walks. 3-E does NOT change this bound; LazyColumn upgrade is out of scope. |
| Card heights vary (intention + recording count optional) | Canvas's `verticalSpacing` param approximates card+gap stride at 132dp. If a card is taller (both intention + recordings shown), the thread visibly drifts from center. Acceptable for MVP; flagged for 3-F device test. |
| `refreshFromLocationIfNeeded` throws SecurityException or other | Already wrapped in try/catch inside the repository (Stage 3-D's final-review fix). The finishWalk caller adds a second try/catch layer for paranoia — same pattern as the transcription scheduler's try/catch. |
| HemisphereRepository is removed in tests (never injected) | Hilt `@BindInstance` or a `FakeHemisphereRepository` test double. Same approach as Stage 2-B's `FakeTranscriptionScheduler`. |

---

## Testing strategy

**`HomeViewModelTest.kt` updates:**
- Inject a simple stub `HemisphereRepository` (doesn't need to be a fake with behavior — `mockk` or a hand-rolled stub returning `MutableStateFlow(Hemisphere.Northern)` is fine).
- Existing "loaded emits rows" tests: assert that each `HomeWalkRow` now has non-zero `uuid`, `startTimestamp`, `distanceMeters`, `durationSeconds`.
- New test: `hemisphere flow proxied from repository` — seed the repo's StateFlow with Southern, assert the VM emits Southern after a subscription.

**`WalkViewModelTest.kt` updates:**
- Inject a stub `HemisphereRepository` with a `refreshFromLocationIfNeededCalled: Boolean` flag (or use `mockk.verify`).
- New test: `finishWalk calls refreshFromLocationIfNeeded after voice recorder settles`.
- New test: `finishWalk swallows refresh failure` — stub throws, finishWalk completes without propagating.
- Existing transcription tests should continue to pass (refresh is a prefix step, doesn't interfere).

**No new Compose UI test for the `Box`-layered HomeScreen.** Stage 3-C's `CalligraphyPathComposableTest` already exercises the Canvas's Path pipeline. Adding a HomeScreen-level Compose test would mostly prove Hilt wiring — not a high-value addition. Manual QA + the existing `HomeViewModelTest` suffice.

---

## Rejected alternatives

1. **LazyColumn upgrade in this PR.** Tempting given the "scale" concern, but it's a structural change that interacts with the resume-check `LaunchedEffect`, the outer verticalScroll, and the card rendering. Scope creep that doubles the PR's risk surface. Defer.

2. **Use `onGloballyPositioned` to measure each card and feed Y offsets into `CalligraphyPath`.** Would give perfect dot-to-card alignment. **Rejected** because (a) the callbacks fire after layout, introducing a one-frame delay where the thread has stale positions, (b) each recomposition re-invokes the callbacks, creating a measurement-loop risk, (c) the MVP doesn't need pixel-perfect alignment — approximate stride reads as "connected enough".

3. **Nest `HemisphereRepository.hemisphere` inside `HomeUiState.Loaded`.** Combining two StateFlows into one would mean `Loaded(rows, hemisphere)` recomposes on either change. Rejected — hemisphere changes are so rare that bundling them forces the HomeScreen composable to re-read all rows when only one flag flipped. Two StateFlows with parallel observers is cleaner.

4. **Delete `HomeWalkRow`'s text fields (`durationText`, `distanceText`, `relativeDate`) and let the composable format on the fly.** Would reduce the DTO size. **Rejected** because Stage 3-A explicitly pre-formatted for performance (formatting strings in Compose re-runs during animations) + testability (HomeFormat helpers are already unit-tested).

5. **Compute `CalligraphyStrokeSpec` list inside `HomeViewModel` alongside rows.** Would parallel the PreviewViewModel's approach. **Rejected** because the composable needs a `@Composable` context for seasonal color resolution anyway (reads `pilgrimColors` via `LocalPilgrimColors`). Computing strokes in the VM would split the work: math in VM + color resolution in composable. Doing it all in the composable keeps the data flow one-direction.

6. **Add a "lone dot" render for the single-walk case.** iOS does this for user retention purposes — your first walk should feel celebrated. **Deferred** to Stage 3-F; the CalligraphyPath file has a KDoc TODO hinting at this future direction.

---

## Scope & estimate

- **0 new production files** (all work is deletions + modifications)
- **2 files deleted** (~130 LoC gone)
- **5 files modified** (~50 LoC net added after deletions balance)
- **2 test files modified** (~60 LoC added)

Estimate: small-to-medium stage. The layout thinking is 80% of the risk; everything else is mechanical.

---

## Forward-carry

- **Stage 3-F (on-device polish):** Verify the 132dp verticalSpacing approximation looks right. If cards with intention+recording are visibly taller than cards without, consider card-aware Y alignment via `onGloballyPositioned` OR fix the card row to a constant height.
- **Stage 3-G (QA pass):** Verify dark mode. Verify API 28/29 renders the thread correctly. Verify long-scroll performance with 50+ walks.
- **Phase 4 (goshuin seals):** Will use `Intensity.Full` for dot colors. The seasonal engine is ready; just a new Composable.
- **Later:** Lunar markers + milestones overlayed on the thread. Likely in its own dedicated stage once Phase 4 lands.

---

## Open questions for checkpoint

- None blocking. The design favors the simple Box-layered backdrop over card-aware alignment, defers LazyColumn + lone-dot to later stages, and keeps the VM mapping clean. Approve, revise, or reject.
