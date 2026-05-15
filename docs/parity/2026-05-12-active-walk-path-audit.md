> **SUPERSEDED** by `docs/parity/2026-05-15-parity-ledger.md` — findings predate the v1.6.0 port + bug fixes and are stale. Format precedent only.

# Parity Audit: Path → Pre-walk → Walking flow

| field | value |
|---|---|
| **iOS pin** | `v1.5.0` = `db4196e` |
| **Android HEAD** | `10f9559` |
| **Generated** | 2026-05-12 |
| **Type** | audit |
| **Generator** | ios-parity skill (8 lens dispatches: 4 iOS + 4 Android, single synthesis) |

---

## Scope

User-flagged: "ios-parity review on the path screen as well as the pre-walk screen after you tap wander and the walking screen too. let's make sure everything in sync with the ios version. let's triple check the code and match the design too. we want the exact functionality as well as the asethetics of the app to match exactly the ios."

Three slices in one audit:
1. **Path** — iOS `Pilgrim/Scenes/Home/WalkStartView.swift` ↔ Android `ui/path/WalkStartScreen.kt` + `PathFootprints.kt` + `RecoveryBanner.kt`
2. **Pre-walk modal** — iOS `Pilgrim/Scenes/ActiveWalk/WalkOptionsSheet.swift` + `IntentionSettingView.swift` ↔ Android `ui/walk/WalkOptionsSheet.kt` + `IntentionSettingDialog.kt`
3. **Walking** — iOS `Pilgrim/Scenes/ActiveWalk/ActiveWalkView.swift` + `ActiveWalkSubviews.swift` + `ActiveWalkViewModel.swift` + `MeditationView.swift` + `WaypointMarkingSheet.swift` + `WalkStatsSheet.swift` + `TurningRitualCard.swift` ↔ Android `ui/walk/ActiveWalkScreen.kt` + `WalkViewModel.kt` + `WalkStatsSheet.kt` + `WaypointMarkingSheet.kt`

---

## Drift findings

Severity legend: **🔴 drift-critical** (visible regression OR latent crash) · **🟡 drift-cosmetic** · **🔵 missing-feature** (iOS feature not yet on Android) · **⚪ extra** · **🟢 matches**.

---

### 🔴 D1 — PathFootprints conditional `rememberInfiniteTransition` (latent crash)

**Android:** `ui/path/PathFootprints.kt:106-118@10f9559` + `:160-172@10f9559`:

```kotlin
val drift by if (reduceMotion) {
    animateFloatAsState(targetValue = 0f, label = "together-static")
} else {
    rememberInfiniteTransition(label = "together-drift").animateFloat(...)
}
```

Compose forbids conditional `remember*` calls — toggling `LocalReduceMotion` at runtime changes the slot-table identity and **throws** at recompose time. Same pattern in `SeekFootprints`.

**Fix:** Call `rememberInfiniteTransition` unconditionally, then choose between `animateFloat` and `0f` inside the State derivation:

```kotlin
val transition = rememberInfiniteTransition(label = "together-drift")
val drift = if (reduceMotion) 0f else transition.animateFloat(...).value
```

---

### 🔴 D2 — Path screen missing CollectiveCounter pulse animation

**iOS:** `WalkStartView.swift:153-160@db4196e`:

```swift
.scaleEffect(collectivePulse ? 1.03 : 1.0)
.shadow(color: .stone.opacity(collectivePulse ? 0.3 : 0), radius: collectivePulse ? 12 : 0)
.animation(
    collectivePulse
        ? .easeInOut(duration: 1.2).repeatForever(autoreverses: true)
        : .default,
)
```

The logo gets a soft 1.2s breathe-with-shadow pulse when `CollectiveCounterService.stats.walkedInLastHour` is true, indicating someone else is walking right now. `.onReceive(counterService.$stats)` arms the pulse once (`!collectivePulse` guard).

**Android:** `WalkStartScreen.kt` + `BreathingLogo.kt` — no equivalent. Android shows the same logo regardless of collective counter state.

**Fix:** Add a `pulseActive: Boolean` param to `BreathingLogo` driven by `CollectiveCounterService.activeNowFlow.collectAsStateWithLifecycle()`. When true, layer a 1.2s scale + shadow `animateFloat` infinite transition on top of the existing breath.

---

### 🔴 D3 — Path screen missing time-of-day tint

**iOS:** `WalkStartView.swift:88-91@db4196e`:

```swift
case 5...7:   return (.orange, 0.03)
case 8...15:  return (.yellow, 0.02)
case 16...19: return (.orange, 0.04)
default:      (.indigo, 0.05)
```

Soft hour-of-day color cast layered over parchment — dawn/midday/sunset/night. Tied to the new seasonal palette but tuned per-hour rather than per-season.

**Android:** Not implemented. Path screen background is flat `parchment` regardless of time.

**Fix:** Compose a `timeOfDayTint(hour: Int): Pair<Color, Float>` helper, layer a low-alpha Box behind the scroll content. Wire to a `currentHour: State<Int>` that refreshes on ON_RESUME (similar pattern to `rememberCurrentDate`).

---

### 🔴 D4 — Path screen ambient radial gradient missing

**iOS:** `WalkStartView.swift:107-117@db4196e` — RadialGradient with startRadius 50 / endRadius 300, animated `ambientOffset` over 15s easeInOut autoreverse loop.

**Android:** Not implemented.

**Fix:** Add a Canvas behind the scrollable hero column drawing a Brush.radialGradient with the same start/end stops and an `animateOffset` infinite transition.

---

### 🔴 D5 — Path screen mode-tap transition cadence diverges

**iOS:** `WalkStartView.swift:46-65@db4196e` — on mode tap:
1. 0.3s easeIn fade-out of footprint
2. 0.45s asyncAfter (gen-guarded)
3. activeMode swap + haptic
4. 0.3s easeOut fade-in

Reduce-motion shortcuts to a 0.2s linear crossfade without haptic.

**Android:** `WalkStartScreen.kt` does a haptic-on-mode-change LaunchedEffect but no 0.45s footprint dissolve/reassemble. Tap feels instant where iOS feels deliberate.

**Fix:** Add a `footprintVisible: MutableState<Boolean>` + `generationCounter` pattern keyed on selectedMode; LaunchedEffect schedules the dissolve/swap/reassemble with a cancellable generation guard.

---

### 🔴 D6 — Active walk: no weather greeting

**iOS:** `ActiveWalkView.swift:711-732@db4196e` — when status enters `.recording` AND a weatherSnapshot.condition exists, fade in a greeting overlay (0.8s easeIn) then auto-dismiss 3.5s later (1.0s easeOut), gen-guarded.

**Android:** Not implemented. No greeting overlay anywhere on the active walking screen.

**Fix:** New composable `WeatherGreetingOverlay(condition: WeatherCondition?, status: WalkState)` that uses `AnimatedVisibility` with a 0.8s fadeIn enter + 1.0s fadeOut exit. LaunchedEffect schedules a 3500ms delay then sets to null. Trigger only on status entering Active. Add a `triggerGenerationCounter` (Int) so back-to-back condition flips don't reset the timer.

---

### 🔴 D7 — Active walk: no celestial greeting

**iOS:** `ActiveWalkView.swift:754-763@db4196e` — celestialSnapshot + status `.recording` → 5.0s delay → 0.8s fadeIn → 3.5s hold → 1.0s fadeOut. Gen-guarded.

**Android:** Not implemented.

**Fix:** Same pattern as D6 but with the 5s pre-delay so the weather greeting can finish first.

---

### 🔴 D8 — Sheet state machine missing pause-auto-expand debounce

**iOS:** `ActiveWalkView.swift:683-691@db4196e`:

```swift
case .paused, .autoPaused:
    pauseExpandGeneration += 1
    let gen = pauseExpandGeneration
    DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) {
        guard pauseExpandGeneration == gen else { return }
        guard viewModel.status == .paused || viewModel.status == .autoPaused else { return }
        sheetState = .expanded
    }
```

When the walk transitions to paused/autoPaused, wait 800ms and double-check status hasn't flipped back to recording (GPS-flap recovery) — only then auto-expand the sheet.

**Android:** `ActiveWalkScreen.kt:205-236@10f9559` updates sheet state immediately on state-class change. No 800ms pause-debounce. GPS-flap auto-pause cycles can fight a manually-collapsed sheet.

**Fix:** Add a `pauseExpandJob: Job?` field; cancel on every status transition; on entering Paused/Meditating spawn a `delay(800ms)` job that re-checks state and sets sheetState=Expanded only if still paused.

---

### 🔴 D9 — Sheet handoff missing 300ms delay between options sheets

**iOS:** `ActiveWalkView.swift:206-235@db4196e` — when WalkOptionsSheet dismisses to present the next sheet (intention, waypoint, whisper, stone), wait 300ms so the sheet animations don't collide.

**Android:** No equivalent. Options sheet dismissal + next sheet present happen on the same frame, can cause animation conflicts on slower devices.

**Fix:** When dismissing options sheet to open the next, schedule the next via `LaunchedEffect(key)` with `delay(300)`.

---

### 🔴 D10 — Sheet peek hint missing

**iOS:** `WalkStatsSheet.swift:265-289@db4196e` — when the walk first starts, perform a "peek wink" animation: 0.7s delay → rise sheet 6pt over 0.28s → hold 0.42s → spring settle. Teaches the swipe-to-expand affordance once per walk start.

**Android:** Not implemented.

**Fix:** Add a `peekHintTrigger: Int` state bumped when transitioning ready/waiting → recording. A LaunchedEffect keyed on the trigger plays the wink animation (`offsetAnimatable.animateTo(-6.dp.toPx(), tween(280))` → delay 420ms → `animateTo(0f, spring(0.5, 0.8))`). Gen-guard via a cancellable Job.

---

### 🔴 D11 — Meditation closing ceremony missing

**iOS:** `MeditationView.swift:609-650@db4196e` — Done tap triggers a 6.5s closing sequence:
- t=0: dissolve phase
- t=2.0s: summary phase
- t=5.0s: fadeOut phase with 1.5s overlay tween
- t=6.5s: onDismiss(endDate) — endDate captured AT Done-tap, not at dismiss time

Plus `closingEndDate` mirror so an early teardown still reports the correct meditation interval.

**Android:** WalkViewModel has `endMeditation()` that fires immediately. No closing ceremony, no 6.5s ritual, no endDate capture.

**Fix:** New `ClosingCeremony` state machine inside the meditation route (or a sealed result class returned by endMeditation). Pre-capture `endMillis = System.currentTimeMillis()` at Done-tap before launching the ceremony. Sequence: `delay(0)` → dissolve; `delay(2000)` → summary; `delay(5000)` → fadeOut + 1500ms alpha tween; `delay(6500)` → controller.endMeditation(endMillis). Belt-and-suspenders: DisposableEffect onDispose also fires onEnd with the captured endMillis if the user backs out mid-ceremony.

---

### 🔴 D12 — Turning ritual card missing

**iOS:** `TurningRitualCard.swift@db4196e` — a turning-day card that appears on the active walk screen when the day is one of the four seasonal turning days. Custom layout with the day's symbol + ritual prompt + accept/dismiss actions.

**Android:** Not implemented. No equivalent card on ActiveWalkScreen.

**Fix:** Port verbatim as a new composable `TurningRitualCard(turning: SeasonalMarker, onAccept: () -> Unit, onDismiss: () -> Unit)`. Trigger from ActiveWalkScreen when `TurningDayService.turning(for: today)` is non-null AND the user hasn't dismissed it this session.

---

### 🔴 D13 — Whisper + Stone placement missing

**iOS:** `ActiveWalkViewModel.swift:50-53@db4196e` — `isWhisperUnlocked` at activeDurationSeconds ≥ 7×60, `isStoneUnlocked` at ≥ 12×60. Max 7 whispers + 1 stone per walk. `WhisperPlacementSheet` + `StonePlacementSheet` ported as actual modals with server confirmation BEFORE haptic.

**Android:** Not implemented. WalkOptionsSheet has no whisper/stone options.

**Fix:** This is the biggest missing-feature item. Needs:
- WhisperService HTTP client to `pilgrimapp.org/api/whisper`
- WhisperPlacementSheet + StonePlacementSheet composables
- VM state: whispersPlacedThisWalk, stonePlacedThisWalk, encounteredWhisperIDs
- Unlock derivation from active duration
- Server-confirmation-then-haptic ordering (so failed placements don't play success-then-fail)

Scope this as a separate epic.

---

### 🔴 D14 — Pause manual button missing (documented Android divergence)

**iOS:** WalkStatsSheet has manual Pause button on the action row.

**Android:** `WalkStatsSheet.kt:560-619@10f9559` action row renders Start (Idle) OR 3-button {Meditate, Mic, End} (Active). KDoc claims "Pause is dropped — Android uses motion-based auto-pause TBD." iOS-parity contract says Pause should exist.

**Fix:** Add a 4th icon to the active-state action row: Pause when Active, Resume when Paused. Wire to `viewModel.pauseWalk()` / `viewModel.resumeWalk()`.

---

### 🔴 D15 — Meditation breath rhythm change 100ms re-arm missing

**iOS:** `MeditationView.swift:559-568@db4196e` — when user picks a new rhythm, set `isActive=false`, wait 100ms, `isActive=true`, restart cycle. Lets the in-flight phase fall through its gen-guard cleanly.

**Android:** Not yet implemented (no inline rhythm picker on Meditation screen).

**Fix:** Part of the meditation port. When rhythm flips, cancel `breathJob`, `delay(100)`, restart with new rhythm.

---

### 🔴 D16 — Meditation milestone flash missing

**iOS:** `MeditationView.swift:737-750@db4196e` — every breath cycle's `checkMilestone()` flashes `milestoneFlash` over 1.5s rise + 1.5s fade for elapsed seconds ∈ {300, 600, 900, 1200, 1800} within a 20s forgiveness window.

**Android:** Not implemented.

**Fix:** Add to MeditationViewModel. `milestoneSecondsList = setOf(300, 600, 900, 1200, 1800)`. Inside the breath observer, check `elapsed in m..(m+20)` and emit a `milestoneFlash: Float` to UI.

---

### 🟡 D17 — Ambient gradient drift 15s loop must match iOS

(See D4 above for the gradient itself; if implementing, the 15s easeInOut autoreverse period is load-bearing.)

---

### 🟡 D18 — Path screen logo size 100dp (✅ matches iOS default), no large-text branch

**iOS:** `WalkStartView.swift:152@db4196e` — `size: isHomeLargeText ? 60 : 100` based on Dynamic Type.

**Android:** Hardcoded `BreathingLogo(size = 100.dp)`. No Dynamic Type branch.

**Fix:** Add a `LocalLargeText: ProvidableCompositionLocal<Boolean>` (resolved from `configuration.fontScale > 1.3f`). Switch to 60.dp when true.

---

### 🟡 D19 — Path screen quote font size override (22sp magic)

**Android:** `WalkStartScreen.kt:194@10f9559` — `style = pilgrimType.displayMedium.copy(fontSize = 22.sp)`. Manual override bypasses the typography token system.

**Fix:** Either add a `pilgrimType.quote` token or document the 22sp anchor with an iOS citation. iOS uses `Constants.Typography.body` (17pt) — 22sp Android is already ~28% larger; verify visually whether this matches iOS hand-feel or should be tightened.

---

### 🟡 D20 — Path footprint magic numbers (16/26 dimensions, -12 rotation, 0.08 alpha)

`PathFootprints.kt:90-99` — multiple unanchored literals. iOS source has equivalent literals; flag them as a parity-critical "do not change without updating iOS too" cluster.

**Fix:** Add inline comments referencing iOS file:line; consider extracting to a shared `PathFootprintTokens` object.

---

### 🟡 D21 — WalkStatsSheet drag thresholds 40dp / 300dp/s / 100dp clamp (match iOS pt values)

iOS uses `40 / 300 / 100` raw points. Android uses dp. Numeric values match (40dp ≈ 40pt assuming standard density), but the velocity threshold semantics differ — iOS DragGesture's `predictedEndLocation` velocity is in points/second, Compose's `onDragStopped` velocity is in pixels/second. Android converts dp → px at use, but a 300pt/s flick on iOS at @3x equals 900px/s — Android's "300dp/s" is also density-converted, but the iOS flick velocity is NOT density-relative.

**Fix:** Verify on-device that the flick feels right at both 1x and 3x densities. Possibly use `300f` raw px-per-second instead of `300.dp` so behavior is density-invariant.

---

### 🟡 D22 — Meditation ripple ring cap >3 with 3.0s removal missing

(Part of the meditation port; flagged separately so the memory cap is preserved when implementing.)

---

### 🟡 D23 — WalkStatsSheet `weatherJob` never cleared on completion

**Android:** `WalkViewModel.kt:700@10f9559` — `private var weatherJob: Job? = null`. KDoc says "Cleared back to null once the job completes" but no `weatherJob.invokeOnCompletion { weatherJob = null }`. The Job reference pins for VM lifetime after each completion.

**Fix:** `weatherJob = viewModelScope.launch { ... }.also { it.invokeOnCompletion { _ -> weatherJob = null } }`.

---

### 🟡 D24 — `finishInFlight` AtomicBoolean never reset

**Android:** `WalkViewModel.kt:683@10f9559` — `finishInFlight` set true, never reset to false. KDoc acknowledges this is intentional (one-shot per VM lifetime), but if process death restores the same VM via `SavedStateHandle` path, finish becomes permanently no-op.

**Fix:** Either reset to false after controller.finishWalk completes (release the guard for the lifetime of the VM only if the controller is still Idle after), or document the precise re-entry semantics expected.

---

### 🟡 D25 — `LocalDate.now()` + `Instant.now()` mismatch in WalkStartScreen lunar phase

**Android:** `WalkStartScreen.kt:110-111@10f9559`:

```kotlin
val today = LocalDate.now()
val lunarPhase = remember(today) { MoonCalc.moonPhase(Instant.now()) }
```

Key is local-date but compute uses UTC instant. Crossing UTC midnight when local hasn't ticked over yields a moonPhase computed for the OLD local-day but the NEW UTC day — silent drift around midnight.

**Fix:** `MoonCalc.moonPhase(today.atStartOfDay(ZoneId.systemDefault()).toInstant())` so day-and-instant always agree.

---

### 🟡 D26 — WaypointMarkingSheet PRESET_CHIPS chunked(3) breaks if list grows

`WaypointMarkingSheet.kt:153@10f9559` — `PRESET_CHIPS.chunked(3).forEach { rowChips -> ... }`. Comment warns: if PRESET_CHIPS grows to a non-multiple-of-3 count, the last row layout breaks.

**Fix:** Use a `LazyVerticalGrid(GridCells.Fixed(3))` instead of manual chunking; growth-safe.

---

### 🟡 D27 — IntentionSettingDialog locale-default digit rendering

`IntentionSettingDialog.kt:58-62@10f9559` — `stringResource(R.string.walk_waypoint_count_chars, text.length, MAX...)` uses `%d/%d` which renders via default-locale `DecimalStyle`. Arabic/Persian/Hindi locales would show non-ASCII digits — Stage 5-A regression pattern.

**Fix:** Pass pre-formatted strings via `String.format(Locale.US, "%d", n)` then drop into the resource as `%1$s/%2$s`.

---

### 🟡 D28 — WaypointMarkingSheet character count locale-default same trap

Same fix as D27 (different call site).

---

### 🟡 D29 — `WalkUiState` data class lacks `@Immutable`

`WalkViewModel.kt:58-66@10f9559`. `walkState: WalkState` is a sealed type containing reference-typed `Walk` — Compose treats it as Unstable. Stage 4-D precedent.

**Fix:** `@Immutable data class WalkUiState(...)`.

---

### 🔵 D30 — Many missing features (whisper, stone, turning ritual, meditation closing ceremony)

Summary list of features that exist on iOS but not Android:

- Whisper placement (D13)
- Stone placement (D13)
- TurningRitualCard (D12)
- Meditation closing ceremony (D11) + milestone flash (D16) + rhythm-change 100ms gap (D15) + ripple ring cap (D22)
- Voice ring fade-out + cleanup (part of meditation port)
- Weather greeting (D6)
- Celestial greeting (D7)
- Sheet peek hint (D10)
- Active-walk sheet auto-expand pause-debounce (D8)
- Time-of-day tint on Path (D3)
- Ambient gradient + 15s drift on Path (D4)
- Collective pulse on Path logo (D2)
- Path mode-tap dissolve/reassemble cadence (D5)
- Dynamic Type large-text branch on Path (D18)

---

### 🟢 D31 — Verified matching

| Aspect | Status |
|---|---|
| `AUTO_INTENTION_DELAY_MS = 500L` mirroring iOS | ✅ matches |
| Weather fetch +2s / +10s retry literals | ✅ matches |
| `tickerFlow(1000ms)` for per-second timer | ✅ matches |
| `shouldAutoPromptIntention` predicate fields | ✅ matches iOS gate |
| `isRecoveryComposition` first-composition latch | ✅ Android-only safety; iOS uses .onAppear gates |
| `hasCheckedAutoIntention` one-shot guard | ✅ matches |
| `hasSeenInProgress` Idle-discard suppression | ✅ Android-only safety against first-composition spurious Idle |
| BackHandler `moveTaskToBack(true)` | Android-only; no iOS equivalent needed |
| `controller.state` hot passthrough for nav observers | ✅ Stage 5G trap correctly mitigated |
| Drag-gesture thresholds 40dp / 300dp/s / 100dp clamp (numeric) | ✅ matches iOS values; semantics flagged in D21 |

---

## Top-line summary

| Bucket | Count |
|---|---|
| 🔴 drift-critical (visible / latent crash) | 16 (D1-D16) |
| 🟡 drift-cosmetic (tunables, locale, minor leaks) | 13 (D17-D29) |
| 🔵 missing-feature umbrella | D30 (≈11 sub-features) |
| 🟢 matches | 1 cluster (D31) |

**Highest-impact fixes for an immediate PR** (bundle 1):
1. D1 PathFootprints conditional `rememberInfiniteTransition` — real crash risk on motion-pref toggle
2. D23 weatherJob never reset — minor leak, single-line fix
3. D24 finishInFlight semantics — clarify or fix
4. D25 lunar phase day/instant mismatch — single-line fix
5. D29 `@Immutable` on WalkUiState — single-line fix
6. D27 + D28 locale digit traps — small file edits

**Bundle 2 (visual parity, medium effort):**
7. D2 collective pulse on logo
8. D3 + D4 + D5 Path screen ambient layers + mode-tap cadence
9. D14 Pause manual button on action row
10. D18 Dynamic Type branch on Path

**Bundle 3 (behavior parity, medium-large effort):**
11. D6 + D7 greeting overlays
12. D8 sheet auto-expand pause debounce
13. D9 sheet handoff delay
14. D10 sheet peek hint

**Bundle 4 (feature ports, separate epic):**
- D11 + D15 + D16 + D22 — meditation closing ceremony cluster
- D12 — turning ritual card
- D13 — whisper + stone placement

---

## Downstream handoff

```
spec:   docs/parity/2026-05-12-active-walk-path-audit.md
ports:  feat/walk-path-parity-bundle-1 (D1, D23-D29 — quick fixes)
        feat/walk-path-parity-bundle-2 (D2-D5, D14, D18 — Path visuals)
        feat/walk-path-parity-bundle-3 (D6-D10 — sheet/greeting behavior)
        feat/walk-path-parity-bundle-4 (D11-D13, D15-D16, D22 — feature ports)
```
