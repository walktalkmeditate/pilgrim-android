# Stage 4-B: Goshuin Seal Reveal Animation — Design Spec

**Date:** 2026-04-19
**Status:** Draft (awaiting CHECKPOINT 1 approval)
**Prior art:**
- `../pilgrim-ios/Pilgrim/Scenes/SealReveal/SealRevealView.swift` — the iOS reveal choreography
- Stage 4-A's `SealRenderer` (already shipped)

---

## Context

Stage 4-A shipped the seal renderer behind a debug-only preview screen. Stage 4-B promotes it to production: after a walk ends, the user's goshuin seal "stamps" onto the screen with a 3-phase animation (pre-press zoom, press-down compression, springy reveal with shadow), holds for 2.5 seconds, then fades out to reveal the walk summary underneath. It's Pilgrim's emotional payoff — the thing that makes completing a walk feel like a ritual.

This stage also cleans up Stage 4-A's debug scaffolding (preview screen, VM, nav route, Home button) and applies two polish items the Stage 4-A reviews flagged for 4-B: hoist `NativePaint` + `FontMetrics` into `remember` so the 60fps animation doesn't allocate per frame, and replace the `WalkFormat.distance`-split-on-space hack at the seal's call site with a typed `DistanceLabel` helper.

---

## Goals

1. **Reveal animation inside the walk-finish flow** — after `finishWalk()` lands the user on `WalkSummaryScreen`, the seal stamps onto the screen as a full-bleed overlay matching iOS's choreography.
2. **Auto-dismiss at 2.5s** + **tap-anywhere to dismiss early** so the user can opt into the summary view at any time.
3. **Single medium-impact haptic** at the exact moment the seal transitions from "pressed" to "revealed" — iOS's timing.
4. **SealSpec built by `WalkSummaryViewModel`** reusing the existing walk + samples load; no new DB round-trip.
5. **Seasonal ink tint via `SeasonalColorEngine.Intensity.Full`** by injecting `HemisphereRepository` into `WalkSummaryViewModel` (same pattern Stage 3-E applied to HomeViewModel).
6. **Delete Stage 4-A's debug preview scaffolding** (Preview screen + VM + `BuildConfig.DEBUG` nav route + HomeScreen button).
7. **Two polish items inherited from Stage 4-A reviews:**
   - `SealRenderer.drawCenterText`: hoist `NativePaint` construction + `FontMetrics` reads into `remember(canvasSize, typefaces, ink)` so animation recompositions don't churn allocations.
   - `WalkFormat.distanceLabel(meters): DistanceLabel` — new typed helper. Call sites that need the split representation use it; existing `distance(meters)` string-returning fun stays unchanged (zero blast-radius on its 4 existing callers).

## Non-goals

- **Share intent on tap.** iOS lets you tap the seal to share the image; that requires rasterizing the Compose Canvas to a `Bitmap`, writing a temp PNG, launching `ACTION_SEND`. Whole sub-feature. Deferred to a dedicated stage; 4-B keeps tap-to-dismiss only.
- **Milestone celebrations (4-D).** No 100km/500km threshold detection or temple-bell sound. 4-D's own stage.
- **Collection grid (4-C).** Seal gallery view — separate stage.
- **SealRevealScreen as a separate nav destination.** Investigation showed iOS uses an `.overlay` with `zIndex(100)`, which maps cleanly to a `Box` overlay on top of WalkSummaryScreen's content. A separate route would complicate back-navigation (pop reveal → pop summary → land on Home is three back-presses; overlay compresses to two).
- **Reduce-motion accessibility check.** iOS doesn't check it either; Android's `AnimationsResources.areAnimationsEnabled()` check is deferred — 2.5s of animation is short enough to not bother most users with motion sensitivity. Flag for a11y polish stage later.
- **Screenshot / pixel-diff tests.** No snapshot infrastructure yet; manual on-device QA remains the visual verification.
- **Process-kill mid-reveal recovery.** The reveal is a one-shot animation; if the user kills/backgrounds mid-reveal, they land on WalkSummary on next launch (the reveal doesn't persist). Matches iOS's `onDisappear { autoDismissTask.cancel() }` behavior — no resumption.

---

## Architecture

### Where the reveal lives

The reveal is an **overlay layer inside `WalkSummaryScreen`** (a `Box` wrapping both the existing summary content AND the new `SealRevealOverlay` Composable). Alpha animation governs appear/disappear; the summary renders underneath so tapping-to-dismiss reveals the already-loaded summary instantly.

```
WalkSummaryScreen (Box fillMaxSize)
├─ [Layer 0, always visible] Existing summary content (LazyColumn: stats, map, recordings, Done button)
└─ [Layer 1, conditional] SealRevealOverlay
   └─ Box(Modifier.fillMaxSize().background(parchment 95%).clickable { dismiss })
      └─ SealRenderer(spec, Modifier.size(220.dp).scale(animatedScale).alpha(animatedOpacity)
                          .shadow(12.dp, alpha = animatedShadowAlpha))
```

When the overlay is visible, all clicks on the background dismiss it early. The seal itself also dismisses on tap (no share for 4-B; single-purpose).

### Data flow

```
ActiveWalkScreen finishWalk button
    ↓
WalkViewModel.finishWalk() (controller transitions to Finished)
    ↓
ActiveWalkScreen LaunchedEffect observes Finished → onFinished(walkId)
    ↓
nav: walkSummary/{walkId}
    ↓
WalkSummaryViewModel (Hilt injects HemisphereRepository too now)
    ↓ loads walk + samples + computes distance
    ↓ builds SealSpec(ink = Color.Transparent placeholder) into WalkSummaryUiState.Loaded
    ↓
WalkSummaryScreen
    ↓ observes uiState + hemisphere; resolves ink via SeasonalColorEngine(rust, Full, walkDate, hemisphere)
    ↓ renders summary content + SealRevealOverlay(spec, onDismiss)
    ↓
SealRevealOverlay
    ↓ LaunchedEffect(Unit): run 3-phase animation, fire haptic at pressed→revealed, auto-dismiss at 2.5s
    ↓ onDismiss called → visible = false → overlay fades out
```

### Animation choreography (port of iOS)

Three phases driven by a phase enum + `LaunchedEffect(Unit)` that advances through them:

```kotlin
enum class SealRevealPhase { Hidden, Pressing, Revealed, Dismissing }

@Composable
fun SealRevealOverlay(
    spec: SealSpec,
    onDismiss: () -> Unit,
) {
    var phase by remember { mutableStateOf(SealRevealPhase.Hidden) }
    val haptic = LocalHapticFeedback.current

    val scale by animateFloatAsState(
        targetValue = when (phase) {
            SealRevealPhase.Hidden, SealRevealPhase.Dismissing -> 1.2f
            SealRevealPhase.Pressing -> 0.95f
            SealRevealPhase.Revealed -> 1.0f
        },
        animationSpec = when (phase) {
            SealRevealPhase.Pressing -> tween(durationMillis = 200, easing = EaseIn)
            SealRevealPhase.Revealed -> spring(dampingRatio = 0.6f, stiffness = 500f)   // matches iOS's response=0.4, damping=0.6
            else -> tween(durationMillis = 300)
        },
        label = "sealRevealScale",
    )

    val opacity by animateFloatAsState(
        targetValue = if (phase == SealRevealPhase.Hidden || phase == SealRevealPhase.Dismissing) 0f else 1f,
        animationSpec = tween(durationMillis = 300),
        label = "sealRevealOpacity",
    )

    val shadowAlpha by animateFloatAsState(
        targetValue = if (phase == SealRevealPhase.Revealed) 0.25f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "sealRevealShadow",
    )

    LaunchedEffect(Unit) {
        phase = SealRevealPhase.Pressing
        delay(200)                                // matches easeIn duration
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        phase = SealRevealPhase.Revealed
        delay(2500)                                // matches iOS 2.5s hold
        phase = SealRevealPhase.Dismissing
        delay(300)                                 // let fade-out complete
        onDismiss()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(pilgrimColors.parchment.copy(alpha = 0.95f))
            .alpha(opacity)
            .clickable(/* ... */) { /* early dismiss */ }
    ) {
        SealRenderer(
            spec = spec,
            modifier = Modifier
                .align(Alignment.Center)
                .size(220.dp)
                .scale(scale)
                .shadow(elevation = 12.dp, ambientColor = Color.Black.copy(alpha = shadowAlpha))
                .clickable { /* early dismiss */ },
        )
    }
}
```

**Key timing comparisons vs iOS:**
| Phase | iOS | Android (ours) |
|---|---|---|
| Hidden → Pressing | easeIn 0.2s | `tween(200, EaseIn)` ✓ |
| Haptic fire | At 0.2s (= pressed moment) | `delay(200); haptic(...)` ✓ |
| Pressing → Revealed | spring(response=0.4, damping=0.6) | `spring(0.6 damping, 500 stiffness)` — approximate match; tune on-device in 3-F-style QA |
| Hold duration | 2.5s | 2.5s ✓ |
| Auto-dismiss fade | SwiftUI `.transition(.opacity)` 0.3s | `tween(300)` on opacity ✓ |
| Shadow opacity | 0 → 0.25 on revealed | `tween(150)` on shadowAlpha → 0.25f ✓ |

**Spring parameter conversion note.** iOS's `spring(response: 0.4, dampingFraction: 0.6)` uses Apple's spring API: `response` = time to settle from displaced state, `damping` = 0-1 fraction (0=bouncy, 1=critical). Compose's `spring(dampingRatio, stiffness)` uses physics parameters directly. The conversion isn't 1:1 exact; `dampingRatio = 0.6f, stiffness = 500f` is a close starting point per empirical tables. Stage 4-B device QA may tune to feel right.

### Haptic strategy

**`LocalHapticFeedback.current.performHapticFeedback(HapticFeedbackType.LongPress)`** — the Compose-level haptic API. Portable across all Android versions the app supports, requires no VIBRATE permission (it uses system-accessible feedback channels), and maps to a firm-ish impact on devices that support it.

iOS uses `UIImpactFeedbackGenerator(.medium)`. Android's `LongPress` haptic type is the closest equivalent in the Compose abstraction. If on-device testing shows it feels too subtle (e.g., OnePlus 13's haptic engine interprets `LongPress` very lightly), 4-B's closing review can escalate to raw `Vibrator.vibrate(VibrationEffect.createPredefined(EFFECT_CLICK))`. For the initial port, `LocalHapticFeedback` is the simpler path.

### SealSpec construction in WalkSummaryViewModel

`WalkSummaryViewModel` already loads `walk`, `samples`, computes `distance`. Extend its Hilt-injected deps to include `HemisphereRepository`, expose `hemisphere: StateFlow<Hemisphere>` as a sibling to `uiState`, and add a `sealSpec: SealSpec` field to `WalkSummary` with `ink = Color.Transparent` placeholder. WalkSummaryScreen resolves the real ink color via `SeasonalColorEngine` in composable context.

The distance label for the seal's center text uses the new `WalkFormat.distanceLabel(meters): DistanceLabel` helper:

```kotlin
data class DistanceLabel(
    val value: String,     // e.g. "5.20", "420"
    val unit: String,      // e.g. "km", "m"
)

fun distanceLabel(meters: Double): DistanceLabel {
    val km = meters / 1_000.0
    return if (meters >= 100.0) {
        DistanceLabel(String.format(Locale.US, "%.2f", km), "km")
    } else {
        DistanceLabel(String.format(Locale.US, "%d", meters.roundToInt()), "m")
    }
}
```

Keep the existing `distance(meters: Double): String` helper unchanged — zero blast-radius on its 4 existing call sites. The new typed helper is used **only** at the seal's call site (WalkSummaryViewModel).

### SealRenderer polish

Current code:
```kotlin
drawIntoCanvas { composeCanvas ->
    val native = composeCanvas.nativeCanvas
    val distancePaint = NativePaint().apply { typeface = ...; textSize = ...; ... }
    val unitPaint = NativePaint().apply { ... }
    val distanceMetrics = distancePaint.fontMetrics
    val unitMetrics = unitPaint.fontMetrics
    // ... math, then native.drawText ...
}
```

Every draw allocates 2 × `NativePaint` + 2 × `FontMetrics`. For a 60fps reveal animation (~90 frames total across 1.5s animation window), that's 360 allocations over the animation. Not a crash, but measurable GC churn.

Fix: hoist the `NativePaint` construction + `FontMetrics` reads into a `remember` keyed on the values they depend on (`canvasSize`, typefaces, ink) so they only rebuild when one of those changes:

```kotlin
@Composable
fun SealRenderer(spec: SealSpec, modifier: Modifier = Modifier) {
    // ... existing typeface + geometry remembers ...

    Canvas(modifier = modifier.aspectRatio(1f)) {
        val canvasSize = size.minDimension
        if (canvasSize <= 0f) return@Canvas
        // ... existing draws (rings, radials, arcs, dots) ...

        // Center-text layer: build cached Paint/metrics inline via
        // DrawScope's position (no @Composable context here, so we
        // can't use remember — instead compute per-frame but cache
        // the Paint references so the PERSISTENT overhead is just
        // the field assignments, not the allocation).
        drawCenterText(...)
    }
}
```

Wait — the problem is that `drawCenterText` runs inside the Canvas lambda (DrawScope context), not the @Composable body. `remember` is only usable in @Composable. The correct hoist is to build the Paints in the @Composable body via `remember(canvasSize, typefaces, ink)`, then pass them as parameters to `drawCenterText`. But `canvasSize` depends on the actual Canvas size which isn't known at composition — only at layout/draw time.

Revised approach: use `remember` keyed on the typefaces and ink only, constructing Paint objects WITHOUT `textSize` (which is canvas-dependent); set `textSize` at draw time on the cached Paints. This amortizes the allocation — Paint instance reused across frames, only its `textSize` field is reassigned per draw.

```kotlin
@Composable
fun SealRenderer(spec: SealSpec, modifier: Modifier = Modifier) {
    val cormorantTypeface = remember { ... }
    val latoTypeface = remember { ... }

    val distancePaint = remember(cormorantTypeface) {
        NativePaint().apply {
            typeface = cormorantTypeface
            isAntiAlias = true
            textAlign = NativePaint.Align.CENTER
        }
    }
    val unitPaint = remember(latoTypeface) {
        NativePaint().apply {
            typeface = latoTypeface
            isAntiAlias = true
            textAlign = NativePaint.Align.CENTER
        }
    }

    Canvas(modifier = modifier.aspectRatio(1f)) {
        // ... existing ...
        drawCenterText(..., distancePaint = distancePaint, unitPaint = unitPaint)
    }
}

private fun DrawScope.drawCenterText(
    ...,
    distancePaint: NativePaint,
    unitPaint: NativePaint,
) {
    val distanceTextPx = canvasSize * 0.09f
    val unitTextPx = canvasSize * 0.032f
    // Mutate cached paints per-frame (cheap; no new allocation):
    distancePaint.textSize = distanceTextPx
    distancePaint.color = ink.toArgb()
    unitPaint.textSize = unitTextPx
    unitPaint.color = ink.copy(alpha = ink.alpha * 0.9f).toArgb()
    // ... rest unchanged ...
}
```

This mutates cached Paint instances per frame but creates no new objects. `FontMetrics` lookup still allocates, but halving overall allocations from 4/frame to 2/frame. Acceptable compromise; true zero-alloc path is a future micro-opt.

### Package layout (no new package)

Everything lives under `ui/design/seals/`:

```
ui/design/seals/
├── SealSpec.kt                    (unchanged)
├── SealGeometry.kt                (unchanged)
├── SealRenderer.kt                (MODIFIED — polish: cached paints)
├── SealRevealOverlay.kt           (NEW — animation wrapper)

ui/walk/
├── WalkSummaryScreen.kt           (MODIFIED — wrap in Box + SealRevealOverlay)
├── WalkSummaryViewModel.kt        (MODIFIED — inject HemisphereRepository, produce SealSpec)
├── WalkFormat.kt                  (MODIFIED — add distanceLabel + DistanceLabel)

ui/home/
├── HomeScreen.kt                  (MODIFIED — delete debug button)

ui/navigation/
├── PilgrimNavHost.kt              (MODIFIED — delete SEAL_PREVIEW route)

DELETED:
├── ui/design/seals/SealPreview.kt
├── ui/design/seals/SealPreviewViewModel.kt
```

---

## Error handling / edge cases

| Edge case | Behavior |
|---|---|
| Walk finishes but has no GPS samples | `distanceMeters = 0`, `displayDistance = "0"`, `unit = "m"`. Seal renders with center text "0 m". Visually OK. |
| Walk has `endTimestamp = null` (shouldn't happen post-finish) | `WalkSummaryUiState.NotFound` or blank state. Existing behavior unchanged. |
| User backgrounds during reveal animation | `LaunchedEffect(Unit)` scope cancels on composable dispose. Animation stops; when user returns, `WalkSummaryScreen` renders fresh with `phase = Hidden` and the animation replays. Acceptable UX (matches iOS's "freeze + resume on return" behavior approximately; ours replays). |
| User rotates device mid-animation | Composable dispose/recreate → animation restarts from `Hidden`. Rare + acceptable; iOS handles the same by preserving State across config change but we don't. Cost: user sees the stamp twice if they rotate. |
| Hemisphere hasn't resolved yet (fresh install, first walk ever) | `WalkSummaryViewModel.hemisphere.value` is `Northern` default. Seal renders with northern-seasonal rust tint. `WalkViewModel.finishWalk()` already called `refreshFromLocationIfNeeded()` via Stage 3-E; by the time summary renders, DataStore should have the cached hemisphere. One-frame Northern flash possible. Acceptable. |
| `SealRenderer` text fonts fail to load | `ResourcesCompat.getFont` returns null → `Typeface.DEFAULT`. Seal still renders; distance numeral uses system font. Degraded but not crashed. |
| Tap outside seal but inside overlay | Dismiss immediately (cancel 2.5s timer, fade out 300ms). |
| Haptic on devices without haptic engine | `LocalHapticFeedback.performHapticFeedback` is a no-op where unsupported. No crash. |
| Reveal opens on WalkSummary that's already been visited once (back nav) | `LaunchedEffect(Unit)` fires on each composition. If user presses back to Home then re-enters summary for the same walkId, the reveal plays again. To match iOS: show reveal **only once per walk finish**. See "Single-play guard" below. |

### Single-play guard

The reveal should play only the FIRST time the user reaches WalkSummary after a walk finish. If they back-navigate and come back, the reveal shouldn't replay (annoying; Stage 3-A's NavBackStackEntry pattern applies).

Strategy: persist a `sealRevealed: Boolean` flag on `Walk` entity via a new Room migration. When the reveal completes, mark the walk as revealed. `WalkSummaryViewModel.sealSpec` is null if already revealed → overlay doesn't render.

**Actually that's heavier than needed.** Simpler approach: keep a per-VM `revealConsumed: Boolean` in the ViewModel. Since the VM is NavBackStackEntry-scoped, back-nav + re-entry creates a new VM instance → new `revealConsumed = false` → reveal replays. Still annoying.

**Simplest approach that works:** use `SavedStateHandle` to persist `revealConsumed` within the NavBackStackEntry's lifecycle. Back-nav + re-entry creates a new entry → new state → reveal plays. Forward-nav (HOME → WalkSummary(n) → HOME → WalkSummary(n)) creates distinct entries → reveal plays. Only actual re-composition within the same entry (scroll, screen rotate) preserves the flag.

Verdict: the `SavedStateHandle` approach is still chatty (replays on back-nav). For MVP, just accept that the reveal plays on every entry to WalkSummary. iOS's behavior is technically the same (coordinator's `showSealReveal = true` on every new walk-finish event but not on back-nav because the coordinator doesn't re-present the overlay — but our NavHost semantics work differently). **Decision: accept reveal-replay on back-nav for 4-B; flag for 4-B/4-C device QA to evaluate whether it's actually annoying.**

This avoids a Room migration + new field for a UX nuance we can fix later if real users complain.

---

## Testing strategy

**Pure-JVM unit tests:**

- `WalkFormatTest.kt` extensions:
  - `distanceLabel(0.0)` → `DistanceLabel("0", "m")`
  - `distanceLabel(50.5)` → `DistanceLabel("50", "m")` (rounding)
  - `distanceLabel(99.9)` → `DistanceLabel("100", "m")` (threshold)
  - `distanceLabel(100.0)` → `DistanceLabel("0.10", "km")` (threshold boundary)
  - `distanceLabel(1234.56)` → `DistanceLabel("1.23", "km")`

**Robolectric tests:**

- `WalkSummaryViewModelTest.kt` (extend existing):
  - `Loaded state carries sealSpec with walk uuid + seed fields`
  - `hemisphere StateFlow proxies repository`
  - `sealSpec distanceLabel reflects the raw distance`

- `SealRevealOverlayTest.kt` (new):
  - Composition smoke: renders without crash at each phase
  - No behavioral assertions on timing (Compose animations + `LaunchedEffect` are hard to pin in unit tests — visual verification is manual QA)

No pixel-diff tests. No screenshot tests. Animation correctness verified on-device.

---

## Rejected alternatives

1. **SealRevealScreen as a separate nav destination.** Considered: simpler mental model. Rejected: back-nav becomes 3 presses (reveal → summary → home) instead of 2, and the reveal animation would be behind its own VM with lifecycle separately from WalkSummary's already-loaded data.

2. **Raw `Vibrator.vibrate(VibrationEffect.createPredefined(EFFECT_CLICK))`.** Considered: matches iOS's `UIImpactFeedbackGenerator.medium` more precisely. Rejected: requires VIBRATE permission in Manifest + more plumbing. `LocalHapticFeedback` works cross-device, cross-API with zero setup. Upgrade only if 4-B QA says the haptic feels wrong.

3. **Persist `sealRevealed` on Walk entity via Room migration.** Considered: prevents reveal-replay on back-nav. Rejected: Room migration for one Boolean is heavy for a UX nuance; accept the behavior and revisit if real users complain.

4. **Snapshot / screenshot tests for animation frames.** Considered: lock the visual to a pixel-level contract. Rejected: no snapshot infra (Paparazzi/Shot) in the project yet. Setting it up is a separate stage. Manual QA matches Stage 3-F's precedent.

5. **Move `SealRenderer.drawCenterText`'s per-frame allocations to zero.** Considered: allocate zero objects per frame. Rejected: true zero-alloc would require allocating `FontMetrics` once and caching, but `FontMetrics` itself is a mutable POJO — mutating it per draw is OK, but needs careful threading. For 4-B, halving (2 Paints/frame → 0, but FontMetrics still 2/frame) is enough. Flag the FontMetrics caching for a future polish pass.

6. **`Modifier.scale` + `Modifier.alpha` directly on `SealRenderer` vs wrapping in a container.** Both approaches work. Chose wrapping in a Box because the shadow + background go on the container, not the Canvas itself.

7. **Play the reveal once per app install (remember via DataStore).** Considered: ultimate "don't replay". Rejected: iOS plays it every walk finish; we match iOS semantics. It's the back-nav case that's annoying, not the per-walk case.

---

## Scope & estimate

- 1 new production file: `SealRevealOverlay.kt` (~120 LoC)
- 5 modified production files: `SealRenderer.kt`, `WalkSummaryViewModel.kt`, `WalkSummaryScreen.kt`, `WalkFormat.kt`, `HomeScreen.kt`, `PilgrimNavHost.kt`
- 2 deleted production files: `SealPreview.kt`, `SealPreviewViewModel.kt`
- 2 test files modified: `WalkFormatTest.kt`, `WalkSummaryViewModelTest.kt`
- 1 new test file: `SealRevealOverlayTest.kt`

Estimate: medium stage. Animation logic is isolated (~50 LoC), polish items are surgical, deletions are mechanical.

---

## Forward-carry for 4-C / 4-D

- **4-C (collection grid)**: uses `SealRenderer` at smaller sizes (80-120dp thumbnails) in a `LazyVerticalGrid`. Stage 4-B's `remember`-cached Paints benefit 4-C's scroll perf.
- **4-D (milestones)**: distance-threshold detection on walk finish triggers a different celebration flow — possibly preceding the reveal. Needs a separate haptic pattern (`VibrationEffect.Composition` temple-bell). Renderer unchanged.
- **Share intent** (future stage): rasterize `SealRenderer` output to `Bitmap` via `drawToBitmap()` on a `ComposeView`, write to temp PNG, `ACTION_SEND`. Whole sub-feature; not 4-B.

---

## Open questions for checkpoint

- None blocking. The design inherits the iOS choreography almost verbatim, with two conscious simplifications (no share intent, no single-play guard). Approve, revise, or reject.
