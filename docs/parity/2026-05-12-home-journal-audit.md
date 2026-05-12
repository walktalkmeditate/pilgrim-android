# Parity Audit: Home / Journal

| field | value |
|---|---|
| **iOS pin** | `v1.5.0` = `db4196e` |
| **Android HEAD** | `fabc8a0` |
| **Generated** | 2026-05-12 |
| **Type** | audit |
| **Generator** | ios-parity skill (8 lens dispatches: 4 iOS + 4 Android, single synthesis) |

---

## Scope

User-flagged: "ios-parity review on the whole journey screen including the popup modal after you tap the dot on the screen. let's triple check that everything is in sync with the ios app. i know one thing that looks off is the footprint icon - we need to fix that. but i'm sure there are others we are not catching at the moment."

Slice: iOS `Pilgrim/Scenes/Home/*` ↔ Android `app/src/main/java/.../ui/home/*`.

## iOS source map

- `Pilgrim/Scenes/Home/HomeView.swift` — `~95 LOC` — NavigationStack + InkScrollView + GoshuinFAB + sheet bindings (selectedWalk, showGoshuin)
- `Pilgrim/Scenes/Home/HomeViewModel.swift` — `~110 LOC` — `@Published walks` / `walkSnapshots`, sync CoreData fetch
- `Pilgrim/Scenes/Home/InkScrollView.swift` — `~800 LOC` — calligraphy thread + dots + lunar markers + date dividers + expand card
- `Pilgrim/Scenes/Home/WalkDotView.swift` — `~120 LOC` — per-dot halo + core + favicon + ripple
- `Pilgrim/Scenes/Home/CalligraphyPathRenderer.swift` — `~100 LOC` — meander geometry
- `Pilgrim/Scenes/Home/MilestoneMarkerView.swift` — `~50 LOC` — km / mi distance markers
- `Pilgrim/Scenes/Home/WalkRowView.swift` — legacy (not used by live tree)
- `Pilgrim/Scenes/Home/WalkStartView.swift` — collective counter

## Android source map

- `ui/home/HomeScreen.kt` — `~700 LOC` — main composable
- `ui/home/HomeViewModel.kt` — `~300 LOC` — `JournalUiState` reactive pipeline
- `ui/home/HomeWalkRowComposable.kt` — legacy row
- `ui/home/HomeUiState.kt` — legacy parallel UiState (DEAD)
- `ui/home/JournalUiState.kt` — live UiState
- `ui/home/JourneySummary.kt` + `WalkSnapshot.kt` — DTOs
- `ui/home/expand/ExpandCardSheet.kt` — bottom-sheet popup on dot tap
- `ui/home/expand/ActivityPills.kt` + `MiniActivityBar.kt` — in-modal segments
- `ui/home/header/JourneySummaryHeader.kt` — top stat header
- `ui/home/dot/WalkDot.kt` + `WalkDotColor.kt` + `WalkDotMath.kt` — dot rendering
- `ui/home/markers/*` — lunar, date dividers, milestones
- `ui/home/empty/EmptyJournalState.kt` — empty state
- `ui/home/HomeFormat.kt` — formatters

---

## Drift findings

Severity legend: **🔴 drift-critical** (visible regression) · **🟡 drift-cosmetic** (subtle visible mismatch) · **🔵 missing** (iOS feature absent on Android) · **⚪ extra** (Android-only addition diverging from iOS) · **🟢 matches** (verified parity, recorded for the audit trail).

---

### 🔴 D1 — Footprint glyph geometry (user-flagged)

**iOS:** `Pilgrim/Design/Scenery/FootprintShape.swift@db4196e` defines an 8-element foot shape: heel oval + outer-edge oval + ball-of-foot oval + big-toe + 4 small toes. Anatomically accurate.

**Android:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/scenery/FootprintShape.kt:19-43@fabc8a0` defines 6 elements: 1 body oval + 1 big-toe + 4 small toes. The single body oval replaces iOS's three-part heel/edge/ball structure.

```kotlin
// Android (drifted)
val bodyRect = Rect(
    offset = Offset(w * 0.20f, h * 0.30f),
    size = Size(w * 0.60f, h * 0.65f),
)
p.addOval(bodyRect)  // ← one oval where iOS has three
```

Big-toe rect also diverges: Android `(w * 0.05, h * 0.05, w * 0.30, h * 0.28)`; iOS `(w * 0.10, h * 0.18, w * 0.24, h * 0.24)` — different anchor + size.

**Visible at:** ExpandCardSheet header (`expand/ExpandCardSheet.kt:176`), 12×18dp Canvas — the glyph looks like a 5-bubble blob instead of an anatomical print.

**Fix:** Port iOS FootprintShape.swift verbatim. Add iOS rect anchors as KDoc.

---

### 🔴 D2 — ExpandCardSheet uses ModalBottomSheet instead of inline overlay card

**iOS:** `InkScrollView.swift:301-401@db4196e` — the expand card is an inline `VStack` overlaid on the scroll view with `.ultraThinMaterial` background, RoundedRectangle corner 16, `seasonColor.opacity(0.10)` tint, ink-shadow, and slide-from-bottom `.transition(.move(edge: .bottom).combined(with: .opacity))` with `withAnimation(.spring(duration: 0.25))`.

**Android:** `expand/ExpandCardSheet.kt:103-108@fabc8a0` uses Material 3 `ModalBottomSheet`:

```kotlin
ModalBottomSheet(
    onDismissRequest = onDismissRequest,
    sheetState = sheetState,
    containerColor = containerColor,
    modifier = modifier,
) {
```

**Visible drift:**
- M3 sheet has a drag handle, system scrim, top-corner-only shape — iOS card is floating, all-corners 16dp, no scrim
- M3 transition is standard tween, not `spring(0.25)`
- Container color is flat `parchmentSecondary` (line 97) — iOS overlays `seasonColor.opacity(0.10)` so each walk's modal is seasonally tinted
- BackHandler unconditional dismiss has no iOS equivalent (iOS uses sheet binding)

**Fix:** Replace ModalBottomSheet with a custom `AnimatedVisibility` + `Surface(color = parchmentSecondary)` overlay box with the seasonColor tint composited on top. Spring animation via `spring(stiffness = Spring.StiffnessMedium)` to approximate iOS 0.25s spring.

---

### 🔴 D3 — WalkDot halo gradient (filled vs hollow)

**iOS:** `WalkDotView.swift:30-37@db4196e` — `RadialGradient(colors: [dotColor.opacity(0.15), .clear], startRadius: size * 0.5, endRadius: size * 1.8)` — hollow center, donut-shaped soft halo.

**Android:** `ui/home/dot/WalkDot.kt:79-93@fabc8a0` uses Compose `Brush.radialGradient` which has **no startRadius parameter**. The center of the gradient is at full peak alpha:

```kotlin
Brush.radialGradient(
    colors = listOf(
        color.copy(alpha = HALO_PEAK_ALPHA),
        Color.Transparent,
    ),
    center = Offset(size.width / 2f, size.height / 2f),
    radius = r,
)
```

**Visible drift:** Android halos are filled balls that overlap and over-saturate the core dot; iOS halos are soft donuts that frame the core.

**Fix:** Composite two circles — inner transparent circle (radius `size * 0.5`) drawn after the gradient with `BlendMode.Clear` (or `BlendMode.DstOut`). Alternatively, draw an `ArcSweep` of bands with computed alpha.

---

### 🔴 D4 — WalkDot core lacks drop shadow

**iOS:** `WalkDotView.swift:48@db4196e` — `.shadow(color: .ink.opacity(0.15), radius: 2, x: 1, y: 2)` on the core circle gives the dot a soft 3D lift.

**Android:** No equivalent. The core radial gradient renders without `Modifier.shadow` or `graphicsLayer { shadowElevation }`.

**Visible drift:** Android dots look flat against parchment; iOS dots have a subtle drop shadow.

**Fix:** Add `Modifier.shadow(elevation = 1.dp, shape = CircleShape, ambientColor = ink.copy(alpha = 0.15f), spotColor = ink.copy(alpha = 0.15f))` to the core Box, OR draw a second `drawCircle` with the same shape offset by (1, 2) with blur (API 31+) or alpha falloff.

---

### 🔴 D5 — WalkDot shared-walk ring uses seasonal color instead of stone

**iOS:** `WalkDotView.swift:55-58@db4196e` — `Circle().stroke(Color.stone.opacity(0.5), lineWidth: 1)` — fixed stone token.

**Android:** `dot/WalkDot.kt:185-194@fabc8a0`:

```kotlin
drawCircle(
    color = color.copy(alpha = 0.5f),  // ← per-dot seasonal color
    ...
    style = Stroke(width = 1.dp.toPx()),
)
```

**Visible drift:** Shared walks on Android get a season-tinted outer ring; iOS always shows a stone-colored ring.

**Fix:** `color = pilgrimColors.stone.copy(alpha = 0.5f)` instead of `color.copy(alpha = 0.5f)`.

---

### 🔴 D6 — WalkDot favicon size ratio 0.5 vs iOS 0.4

**iOS:** `WalkDotView.swift:87@db4196e` — `.font(.system(size: size * 0.4)).bold()`.

**Android:** `dot/WalkDot.kt:120@fabc8a0` — `Modifier.size((sizeDp * 0.5f).dp)` — **25% larger** relative to the dot than iOS.

**Visible drift:** Favicon glyph crowds the dot interior.

**Fix:** `sizeDp * 0.4f`.

---

### 🔴 D7 — WalkDot favicon tint diverges

**iOS:** `WalkDotView.swift:84@db4196e` — `.foregroundColor(.parchment).shadow(color: .ink.opacity(0.4), radius: 0.5)` — literal parchment color, optional shadow.

**Android:** `dot/WalkDot.kt:118@fabc8a0` — `tint = MaterialTheme.colorScheme.onPrimary` — resolves to whatever M3 onPrimary is mapped to (which may not be parchment, especially under dynamic color).

**Fix:** `tint = pilgrimColors.parchment` to match.

---

### 🔴 D8 — Goshuin FAB sized 48/38 instead of 64/52 (comment lies)

**iOS:** `HomeView.swift:39-56@db4196e` overlay — `GoshuinFAB` 64dp parchmentTertiary disc + stone stroke + 52dp seal thumbnail.

**Android:** `HomeScreen.kt:631-664@fabc8a0` — comment says 64/52, code uses:

```kotlin
.size(48.dp)  // disc
// later:
.size(38.dp)  // seal image
```

Either the comment is stale or the implementation is undersized — most likely undersized. Visible on-device as FAB ~25% smaller than iOS.

**Fix:** `.size(64.dp)` for the disc, `.size(52.dp)` for the seal image. Update offset math if necessary.

---

### 🔴 D9 — MiniActivityBar 6dp tall vs iOS 4pt

**iOS:** `InkScrollView.swift:428@db4196e` — `.frame(height: 4)`.

**Android:** `expand/MiniActivityBar.kt:36@fabc8a0` — `.height(6.dp)` — **50% thicker**.

**Fix:** `.height(4.dp)`.

---

### 🔴 D10 — MiniActivityBar segment alphas are 0.5/0.6/0.6 vs iOS 0.6/0.7/0.7

**iOS:** `InkScrollView.swift:431-439@db4196e` — moss 0.6 / rust 0.7 / dawn 0.7.

**Android:** `expand/MiniActivityBar.kt:30-32@fabc8a0`:

```kotlin
val mossColor = pilgrimColors.moss.copy(alpha = 0.5f)
val rustColor = pilgrimColors.rust.copy(alpha = 0.6f)
val dawnColor = pilgrimColors.dawn.copy(alpha = 0.6f)
```

**Visible drift:** Pill row reads as washed-out on Android.

**Fix:** Bump each alpha by +0.1.

---

### 🟡 D11 — Activity-pill dot 5dp vs iOS 4pt

**iOS:** `InkScrollView.swift:457@db4196e` — `.frame(width: 4, height: 4)`.

**Android:** `expand/ActivityPills.kt:62@fabc8a0` — `.size(5.dp)` — 25% larger.

**Fix:** `.size(4.dp)`.

---

### 🟡 D12 — View-details button typography body vs iOS annotation

**iOS:** In-card CTA uses `Constants.Typography.annotation` (caption-tier, ~11pt).

**Android:** `expand/ExpandCardSheet.kt:101@fabc8a0` — `val buttonStyle = pilgrimType.body` (~17sp). Android CTA reads chunkier than iOS.

**Fix:** Switch to `pilgrimType.annotation` (or `pilgrimType.caption` if no annotation token).

---

### 🟡 D13 — Modal column spacing 12dp vs iOS 10pt

**iOS:** `VStack(spacing: 10)` inside the expand card.

**Android:** `expand/ExpandCardSheet.kt:114@fabc8a0` — `verticalArrangement = Arrangement.spacedBy(12.dp)`. Over six rows the card grows ~12dp taller.

**Fix:** `Arrangement.spacedBy(10.dp)`.

---

### 🟡 D14 — Specular highlight constant alpha vs iOS opacity-multiplied

**iOS:** `WalkDotView.swift:62@db4196e` — `.opacity(opacity * 0.5)` — older (lower-opacity) dots fade their specular highlight together with the core.

**Android:** `dot/WalkDot.kt:167-182@fabc8a0` — `graphicsLayer { alpha = 0.5f }` — constant. Dim dots show a bright catchlight.

**Fix:** Multiply by per-dot opacity (the same `perDotAlpha` value used elsewhere).

---

### 🟡 D15 — monthsSince uses 30-day approximation

**iOS:** `Calendar.dateComponents([.month])` — actual calendar months.

**Android:** `header/JourneySummaryHeader.kt:166-171@fabc8a0`:

```kotlin
val months = (deltaMs / (30L * 24L * 60L * 60L * 1000L)).toInt()
```

Drifts ~5 days/year vs iOS.

**Fix:** Use `java.time.Period.between(firstDate, nowDate).months + (12 * years)` or `ChronoUnit.MONTHS.between(firstDate, nowDate)`.

---

### 🟡 D16 — Lunar marker offset magic 5f raw px

**iOS:** `InkScrollView+LunarMarkers.swift@db4196e` — center the 10pt marker at the meander point with -5pt offset.

**Android:** `HomeScreen.kt:534-538@fabc8a0`:

```kotlin
IntOffset(
    (marker.xPx - 5f).toInt(),
    (marker.yPx - 5f).toInt(),
)
```

The marker size is `10.dp` but the offset literal `5f` is raw pixels, NOT half of 10.dp converted. On high-density displays (3x) the marker mis-centers by `(half-dp-in-px - 5)` ≈ 10px off.

**Fix:** Compute `val half = with(density) { 5.dp.toPx() }` outside the offset lambda; use `(marker.xPx - half).toInt()`.

---

### 🟡 D17 — Outer ring offset constant 1.75 hides HALO_SCALE/2

**Android:** `HomeScreen.kt:473-481@fabc8a0`:

```kotlin
IntOffset(
    (xPx - dotSizePx * 1.75f).toInt(),
    (yPx - dotSizePx * 1.75f).toInt(),
)
```

`1.75` is `HALO_SCALE / 2` (HALO_SCALE = 3.5 in WalkDot.kt). Magic literal hides the derivation — changing HALO_SCALE silently breaks centering.

**Fix:** Replace `1.75f` with `HALO_SCALE / 2f` referencing the constant.

---

### 🟡 D18 — scheduleSealRender hard-codes light palette

**Android:** `HomeViewModel.kt:267@fabc8a0`:

```kotlin
val ink = pilgrimLightColors().stone
```

Bypasses dark-mode theming — the FAB seal ink stays light-mode-tuned even in dark mode.

**Fix:** Plumb the active palette via `@Composable` reader pattern OR have HomeScreen pass the resolved color into a VM-callable function rather than constructing in the VM.

---

### 🔵 D19 — WalkSnapshot lacks recordingZone

**iOS:** iOS may or may not store the walk's recording zone (need to verify), but in `ExpandCardSheet.kt:89-95@fabc8a0` Android uses `ZoneId.systemDefault()` — display zone = current device zone, NOT the zone the walk was originally recorded in. Cross-tz travel splits a single walk's date label across two months. Stage 6-B autopilot memory flagged this exact pattern.

**Fix:** Add `recordingZoneId: String?` to `WalkSnapshot` + `Walk` entity (migration); persist on walk finish; use when formatting dateText. Defer to a separate parity ticket if iOS doesn't carry it either.

---

### 🔵 D20 — CelestialSnapshot moonSymbol fragile chained traversal

**Android:** `expand/ExpandCardSheet.kt:210@fabc8a0`:

```kotlin
val moonSymbol = celestial.position(Planet.Moon)?.tropical?.sign?.symbol.orEmpty()
```

Comment admits: "plan referenced celestial.moonSign.symbol which does not exist." Any rename in the chain silently null-coalesces to empty string with no signal.

**Fix:** Add `CelestialSnapshot.moonSignSymbol(): String?` extension to centralize the lookup; failing-to-resolve emits a Log.w breadcrumb.

---

### 🔵 D21 — Dead HomeUiState + HomeWalkRow parallel model

**Android:** `HomeUiState.kt:10-35@fabc8a0` defines `HomeWalkRow` + `HomeUiState` (Loading/Loaded/Empty). `JournalUiState.kt` defines a parallel `JournalUiState` triple. The live Journal path uses `JournalUiState`; `HomeUiState` appears to be dead.

**Fix:** Grep for `HomeUiState` / `HomeWalkRow` references. If unreferenced, delete. If still referenced by `HomeWalkRowComposable.kt`, decide whether HomeWalkRowComposable is also dead — Phase 3 launched it before InkScrollView landed.

---

### 🔵 D22 — Eagerly StateFlow re-runs buildSnapshots on every pref toggle

**Android:** `HomeViewModel.kt:111-126@fabc8a0` combines `observeAllWalks() + distanceUnits + cachedShareStore + celestialAwarenessEnabled + hemisphere` with `flowOn(io) + stateIn(WhileSubscribed Eagerly)`. Toggling `celestialAwarenessEnabled` re-runs the entire per-walk DAO fan-out (`coroutineScope { walks.map { async { repo.locationSamplesFor(it.id); ... } } }`).

**Fix:** Lift `celestialAwarenessEnabled` out of the combine to a separate StateFlow consumed only by ExpandCardSheet (or by `setExpandedSnapshotId`). buildSnapshots doesn't need to react to it.

---

### 🟢 D23 — Dimensions confirmed matching

| Element | iOS | Android | Status |
|---|---|---|---|
| `WalkDot SHARED_RING_OFFSET_DP` | `size + 12` | `12f` (line 35) | ✅ matches |
| `WalkDot.HALO_SCALE` | `3.5x` | `3.5f` | ✅ matches |
| `expand card header footprint Canvas` | `12 × 18` | `Modifier.size(width = 12.dp, height = 18.dp)` | ✅ matches dim, glyph differs (see D1) |
| `WalkDot core gradient endRadius` | `size * 0.6` = `coreR * 1.2` | `coreR * 1.2f` | ✅ matches |
| `View-details button container` | `Color.stone.opacity(0.8)` | `pilgrimColors.stone.copy(alpha = 0.8f)` | ✅ matches |
| `Footprint glyph alpha` | `0.3` | `0.3f` | ✅ matches |

---

### 🟢 D24 — Behavior confirmed matching

| Aspect | Status |
|---|---|
| Stale-id reconciliation when expandedId no longer in snapshots → setExpandedSnapshotId(null) | ✅ via `LaunchedEffect(snapshots, expandedId)` |
| CancellationException re-thrown in celestialJob catch | ✅ explicit `catch (ce: CancellationException) { throw ce }` |
| ConcurrentHashMap-safe in-flight markers | N/A — `sealCache` is LinkedHashMap (see D25) |

---

### 🟡 D25 — sealCache LinkedHashMap is NOT thread-safe

**Android:** `HomeViewModel.kt:108@fabc8a0`:

```kotlin
private val sealCache = LinkedHashMap<Pair<SealSpec, Int>, ImageBitmap>(8, 0.75f, true)
```

Read on `defaultDispatcher` inside `scheduleSealRender` coroutine; if a second emission cancels and restarts mid-render, two coroutines could touch the map concurrently. `ConcurrentModificationException` risk on rapid combined-flow emissions.

**Fix:** Wrap in `Collections.synchronizedMap()` OR (cleaner) move to `ConcurrentHashMap` with manual LRU eviction.

---

## Top-line summary

| Bucket | Count |
|---|---|
| 🔴 drift-critical | 10 (D1-D10) |
| 🟡 drift-cosmetic | 8 (D11-D18, D25) |
| 🔵 missing / behavior | 4 (D19-D22) |
| 🟢 matches | 2 (D23, D24) |

**User's flagged item:** D1 (footprint) confirmed + characterized.

**Highest-leverage fix bundle** (visual parity in one PR):
1. D1 footprint geometry verbatim port
2. D2 ExpandCardSheet → AnimatedVisibility overlay (large refactor, may split)
3. D3-D7 dot rendering (halo, shadow, ring color, favicon size + tint)
4. D8 FAB sizing
5. D9-D14 modal spacing + alphas + typography

**Defer** D19-D22 (data-model migration) and D25 (concurrent-cache hardening) to follow-up tickets.

---

## Downstream handoff

Spec ready for `superpowers:writing-plans`:

```
spec: docs/parity/2026-05-12-home-journal-audit.md
next: /writing-plans docs/parity/2026-05-12-home-journal-audit.md  (or /ios-parity port Home if regen is wanted)
optional QA: jutsu swarm doc-review docs/parity/2026-05-12-home-journal-audit.md
```
