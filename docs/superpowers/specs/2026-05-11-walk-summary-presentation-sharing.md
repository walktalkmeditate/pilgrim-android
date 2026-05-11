# Walk Summary presentation + sharing bundle — design

**Date:** 2026-05-11
**Parity target:** pilgrim-ios v1.5.0 (`db4196e`)
**Discovery:** device QA on PR #89/#90/#91 (on-device walkthrough on OnePlus 13 CPH2655)
**Slug:** `walk-summary-presentation-sharing`

## Problem

| | |
|---|---|
| **Problem** | Walk Summary scene diverges from iOS in 6 structural ways the 2026-05-10 parity audit (slice-resolver scoped to file content) didn't catch. Discovered live during device QA after PR #91 merged. |
| **Who feels it** | Every user who finishes a walk. iOS users get a sheet/modal with a tidy 3-action share card; Android users get a full-screen page with scattered share affordances + a Light Reading card revealed before they earned it + an Etegami card that doesn't exist on iOS. |
| **When** | Every Walk Summary surface — post-walk completion (`MainTabView` equivalent), tap-walk-from-Home, tap-walk-from-Recordings. |
| **Today's workaround** | None — drift is invisible to anyone who hasn't seen the iOS app. |
| **Cost of doing nothing** | WalkSummary scene stays at ~80% parity. The 3 most-engaged-with surfaces (share flow, Light Reading reveal, post-walk presentation) all drift from iOS. Future iOS work past v1.5.0 compounds the drift. |

## In scope (Deltas A–G mapped from 11 drift items)

Maps to the device-QA drift list as follows:

| Drift | Status |
|---|---|
| #1 Walk Summary = sheet/modal | Delta A |
| #2 Photo section toggle gate | **Already shipped in PR #90** — `ReliquaryState.ToggleOff → empty Box`. Screenshot confirms PermissionDenied prompt renders correctly; toggle-off path also works. No work needed here. |
| #3 Light Reading hasShared gate | Delta B |
| #4 Etegami card not on iOS | Delta C |
| #5 Goshuin/etegami sharing = popup | Delta D |
| #6 Bottom 3-sharing UI layout | Delta D |
| #7 Map circular mask | Delta E |
| #8 Pace sparkline below map | **Out of scope (this bundle), explicit follow-up if drift confirms.** Sub-slice triage 2026-05-11 confirmed `PaceSparkline.kt` + integration in `WalkActivityTimelineCard.kt:77-132` ship correctly (Bucket A). The device-QA "missing" likely reflects the 4-second test-walk having insufficient GPS samples to render. **Device QA on this bundle MUST include a sparkline check on a real walk** — if the sparkline is genuinely absent on a multi-minute walk, file a separate PR. Tracked in the Non-goals table below + the PR test plan. |
| #9 Distance/Steps/Elevation stats | **Distance + Elevation** already in `WalkStatsRow`; Elevation gate verified in Delta G. **Steps** is Non-goal (requires StepCounter sensor + Room column + backfill — Phase N feature). |
| #10 Weather stats | Delta G (verify gate; weatherCondition + weatherTemperature non-null already gates correctly per code at `WalkSummaryScreen.kt:497-515`. Likely working — re-confirm in device QA on a walk that has weather data). |
| #11 Celestial line centering | Delta F |

### A. Walk Summary as sheet/modal (~80 LOC) — drift #1

iOS `MainTabView.swift:45`, `HomeView.swift:54`, `RecordingsListView.swift:55`: `.sheet(item: $...) { WalkSummaryView(walk: ...) }`. iOS body wraps in `NavigationStack` with a toolbar Done button. Android currently uses `composable(route = Routes.WALK_SUMMARY_PATTERN)` at `PilgrimNavHost.kt:354` — full-screen nav destination.

**Translation (lighter path — locked D1):** keep the existing `composable(route = Routes.WALK_SUMMARY_PATTERN)` NavComposable. WRAP its content in `Dialog(properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false))`. The Dialog overlay achieves iOS's "sheet on top of prior screen" visual without requiring sheet-state migration to all 3 host screens (post-walk completion, Home tab, Recordings tap, Goshuin tap). System back-gesture closes the dialog → `onDismissRequest` calls the existing `onDone()` which does `popBackStack`.

**Entry points covered by this single-file change:** all 4 host paths that currently `navController.navigate(Routes.walkSummary(walkId))` — (1) post-walk completion (Path tab's WalkStartScreen → ActiveWalk → completion → WalkSummary), (2) Home tab → tap walk row, (3) Recordings tab → tap walk row, (4) Goshuin tab → tap seal → WalkSummary. All 4 route through `Routes.WALK_SUMMARY_PATTERN`, so wrapping its NavComposable body in `Dialog` covers all entries with a single change site.

Rationale: iOS sheet pattern uses parent-owned state + `.sheet(item:)`. Compose equivalent (host-owned `if (showSheet) Dialog { content }`) requires migrating sheet state to all 4 host screens, each with its own navigation context. Wrapping the NavComposable's body in a Dialog preserves the existing nav-stack contract (popBackStack still works, deep-links still resolve) while presenting visually as a sheet. The existing top bar (`WalkSummaryTopBar` with Done button) stays inside the Dialog content.

Single file changes: `PilgrimNavHost.kt:354-396` — wrap `WalkSummaryScreen(...)` in `Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) { ... }`. `Dialog.onDismissRequest` handles system back gesture per Compose contract — no separate `BackHandler` needed.

### B. `WalkSharingTracker` + Light Reading hasShared gate (~150 LOC) — drift #3

iOS `WalkSharingTracker.swift`: `UserDefaults.standard` stringSet under key `sharedWalkUUIDs`. Methods `hasShared(walkUUID: String): Bool` and `markShared(walkUUID: String)`. iOS `WalkSummaryView.swift:86`: `if let reading = lightReading, hasRevealedLightReading { WalkLightReadingCard(reading: reading) }`. iOS `:132-134`: `hasRevealedLightReading = sharingTracker.hasShared(walkUUID: uuid)` on `onAppear`. iOS `:816-826`: `markSharedAndReveal()` → `sharingTracker.markShared(walkUUID: uuid)` then `hasRevealedLightReading = true` (with 1.2s easeInOut animation, instant under reduceMotion).

**Translation:** new `WalkSharingTracker` injected `@Singleton`, backed by DataStore Preferences `stringSetPreferencesKey("sharedWalkUUIDs")` (matching iOS's key string for forensic parity even though Android storage layer differs). Methods:
- `suspend fun hasShared(walkUuid: String): Boolean`
- `suspend fun markShared(walkUuid: String)`
- `fun hasSharedFlow(walkUuid: String): Flow<Boolean>` — observable. Implementation: `dataStore.data.map { (it[SHARED_UUIDS_KEY] ?: emptySet()).contains(walkUuid) }.distinctUntilChanged()`. Hot Flow per walkUuid; multiple subscribers share the upstream DataStore observation via `stateIn(scope, WhileSubscribed(5_000), initialValue = false)` at the VM seam (NOT at the tracker — keeps the tracker stateless).

Wire into `WalkSummaryViewModel`:
- New flow `val hasRevealedLightReading: StateFlow<Boolean>` keyed on the loaded walk's UUID.
- Existing `lightReadingDisplay: StateFlow<LightReading?>` stays; the composable gates on BOTH `lightReadingDisplay != null && hasRevealedLightReading`.
- `fun markCurrentWalkShared()` — non-suspend public method on the VM. Body: `viewModelScope.launch(Dispatchers.IO) { sharingTracker.markShared(walkUuid) }`. The IO dispatcher hop is mandatory per Stage 2-E memory lesson (`viewModelScope.launch` defaults to Main; DataStore writes are file I/O). Called from all 3 share-success callbacks (goshuin share, etegami share, walk-journey share).

### C. Delete `WalkEtegamiCard` + `WalkEtegamiShareRow` from scroll (-50 LOC) — drift #4

iOS body lines 55-91: NO etegami section. Etegami exists only as a SHARE action inside `WalkSharingButtons.imageShareRow`. Android `WalkSummaryScreen.kt:647` calls `WalkEtegamiCard(spec = etegami)` and the etegami share row below it — both delete.

**Discovery step (Stage 4):** `grep -rn "WalkEtegamiCard\|WalkEtegamiShareRow" app/src/main/java/` after the Walk-Summary deletion. If non-zero callers remain outside `WalkSummaryScreen.kt`, BLOCK Stage 4 — surface to user. Expected result: zero callers, files fully deleted.

The etegami bitmap generation infra (`EtegamiBitmapRenderer`, `EtegamiPngWriter`, `EtegamiGallerySaver`, `EtegamiCacheSweeper`) STAYS — invoked from the new `WalkSharingButtons` etegami button instead.

### D. New `WalkSharingButtons` composable (~300 LOC) — drift #5 + #6

iOS `WalkSharingButtons.swift:36-59`: parchmentSecondary card, gated on `walk.routeData.count >= 2`. Contents: `imageShareRow` (Goshuin + Etegami buttons side-by-side, each tapping → generate bitmap → write to temp → `.sheet(item: $shareURL) { ShareSheet(items: [url]) }`) + `divider` + `journeySection` (Walk Share button → `.sheet(isPresented:) { WalkShareView(walk:...) }`).

**Translation:** new composable at `app/src/main/java/.../ui/walk/summary/WalkSharingButtons.kt`. Three actions in one parchmentSecondary card:
1. **Goshuin button** — tap → render `SealSpec` → bitmap → write to cache → `Intent.ACTION_SEND` chooser with `image/png`
2. **Etegami button** — tap → existing `EtegamiBitmapRenderer.render(spec, context)` → bitmap → cache → `ACTION_SEND` chooser
3. **Walk Share Journey button** — opens existing `WalkShareScreen` (in a NESTED sheet OR navigates internally — pick nested sheet for iOS parity)

Each share success → calls `viewModel.markCurrentWalkShared()` which triggers Light Reading reveal (per Delta B).

Gating: render only when `walk.routePoints.size >= 2` (iOS `walk.routeData.count >= 2`).

Replaces the 3 scattered Android sections: `WalkLightReadingCard` (moves to its own slot per iOS body line 86, gated on hasShared), the deleted `WalkEtegamiCard`/`WalkEtegamiShareRow` (Delta C), and `WalkShareJourneyRow` (becomes a row inside `WalkSharingButtons`).

### E. Map circular mask regression fix (~10 LOC) — drift #7

`WalkSummaryScreen.kt:806-821` ships the `compositingStrategy = Offscreen + drawWithCache + radialGradient(0f→0.45f→1f) + BlendMode.DstIn` mask. Device QA on PR #91 shows the map rendering rectangular — mask not applied. Investigation needed:
- May be a `compositingStrategy.Offscreen` regression with newer Compose / Mapbox combos
- May be the radial gradient stops not matching iOS's start-radius/end-radius semantics
- May be a layering issue with the AndroidView (Mapbox) inside the masked Card

Open question Q1 (blocking — investigate first; fix may be a 1-line `Modifier.graphicsLayer { alpha = 0.999f }` workaround for the offscreen layer, or a real bug deeper).

### F. CelestialLineRow center alignment (~5 LOC) — drift #11

Current Android `CelestialLineRow.kt` line 39: `Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small))` — left-aligned by default. iOS centers the line.

**Fix:** wrap the Row in a `Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center)` OR change to `horizontalArrangement = Arrangement.Center`. Verify rendered horizontal centering via Compose test asserting the row's bounds.

### G. `WalkStatsRow` always show Elevation when present (~5 LOC) — drift #10

iOS statsRow: `if walk.ascend > 1 { miniStat(label: "Elevation", value: formatElevation(walk.ascend)) }`. Verify Android `WalkStatsRow` has the same `ascendMeters > 1` gate; surface Elevation when present (not just when distance > 0).

## Non-goals

- **Steps stat in WalkStatsRow.** iOS shows `miniStat(label: "Steps", value: "\(steps)")` when `walk.steps > 0`. Android has NO step-sensor integration AND no `Walk.steps` Room column. Adding requires `StepCounter` sensor wiring + new entity column + backfill — Phase N feature, not parity polish. Distance + Elevation only in this bundle.
- **iOS post-v1.5.0 work** — parity frozen at `db4196e`.
- **WidgetKit/Glance/Live-Activity** — out of scope.
- **Walk Summary reveal cinematic changes** — PR #89 shipped that. This bundle keeps it.
- **Voice-row polish / reliquary state polish / carousel / preview-sheet** — PR #90 shipped those. No regression intended.
- **Milestone N+1 perf** — PR #91 shipped. No regression intended.
- **Animation polish on Light Reading reveal** — iOS uses `withAnimation(.easeInOut(duration: 1.2))`. Android equivalent via `AnimatedVisibility(fadeIn(tween(1200)))` IS in scope of Delta B as the visible reveal trigger; matching the EXACT iOS curve at the 12-frame level is not required.
- **Backfilling `WalkSharingTracker` from historical shares.** Existing walks (pre-this-bundle) start with `hasShared = false` regardless of past behavior — iOS has the same property because the tracker is a forward-looking state, not derived. No migration needed.
- **Pace sparkline below map (drift #8).** Existing code at `WalkActivityTimelineCard.kt:77-132` is correct per sub-slice triage. If device QA on a real (multi-minute) walk confirms the sparkline is missing despite valid GPS samples, file a separate PR — out of this bundle's scope. Tracked in PR test plan as a device-QA check.
- **Goshuin share image bitmap layout polish.** Use the existing `SealRevealOverlay`'s SealSpec → bitmap rendering pipeline. Visual match with iOS goshuin share image NOT in scope (would require pixel-perfect SealGenerator port — Phase N).
- **Walk Summary opening as ModalBottomSheet** instead of `Dialog`. Locked to `Dialog` per D1 below.

## Acceptance criteria

### A. Presentation

- [ ] Walk Summary content rendered inside a `Dialog(properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false))` at `PilgrimNavHost.kt:354-396`. The NavComposable `route = Routes.WALK_SUMMARY_PATTERN` is **preserved** (NOT deleted) — all existing entry points + deep-links still resolve.
- [ ] System back gesture dismisses the dialog via `Dialog.onDismissRequest` → calls the existing `onDone()` handler which does `popBackStack(HOME/PATH)`. Per Compose `Dialog` contract, `onDismissRequest` covers system back gesture + back button + outside-content tap. A separate `BackHandler` inside the dialog content is NOT required — would be redundant. Skip the BackHandler addition; rely on `onDismissRequest`.
- [ ] Existing `WalkSummaryTopBar` (with date title + Done button) renders inside the Dialog.
- [ ] Rotating mid-summary doesn't replay the reveal cinematic (PR #89's `rememberSaveable` fix is preserved — verify via Robolectric test that explicitly wraps `WalkSummaryScreen` in a `Dialog` test host).

### B. Light Reading hasShared gate

- [ ] New `WalkSharingTracker` `@Singleton` injected at the VM seam. DataStore Preferences key `"sharedWalkUUIDs"` (string set).
- [ ] `WalkLightReadingCard` does NOT render when `hasShared(walkUuid) == false`, regardless of whether `lightReadingDisplay.value` is non-null. Verify via Robolectric test.
- [ ] After tapping ANY of the 3 share buttons (Goshuin / Etegami / Walk Journey) AND the share intent **successfully dispatches** via `startActivity` (per locked D4 — `ActivityNotFoundException` snackbar path does NOT mark shared, since the user couldn't reach the system share chooser), `WalkLightReadingCard` becomes visible with a 1200ms fade-in animation (`AnimatedVisibility(visible = hasShared, enter = fadeIn(tween(1200)))` or equivalent). Under reduce-motion, the card appears instantly.
- [ ] Returning to the same walk after a previous share session shows the Light Reading card immediately on open (no fade-in needed — `hasShared` returns true from DataStore).

### C. Etegami card removal

- [ ] `WalkEtegamiCard` is NOT called from `WalkSummaryScreen.kt` (`grep -c "WalkEtegamiCard(" app/src/main/java/.../WalkSummaryScreen.kt` returns 0).
- [ ] `WalkEtegamiShareRow` is NOT called from `WalkSummaryScreen.kt` (same grep).
- [ ] Existing etegami bitmap infra (`EtegamiBitmapRenderer`, `EtegamiPngWriter`, `EtegamiGallerySaver`, `EtegamiCacheSweeper`) is preserved and reused by Delta D's etegami button.
- [ ] Existing standalone `WalkEtegamiCard.kt` + `WalkEtegamiShareRow.kt` files are DELETED. The Stage 4 discovery grep MUST return zero callers outside the deleted-from `WalkSummaryScreen.kt`; if it doesn't, Stage 4 is BLOCKED until the spec is updated or the unexpected callers are reconciled.

### D. WalkSharingButtons composable

- [ ] New `WalkSharingButtons` composable at `app/src/main/java/.../ui/walk/summary/WalkSharingButtons.kt`.
- [ ] Renders inside a parchmentSecondary card with iOS-matching layout: imageShareRow (Goshuin + Etegami buttons side-by-side, centered, equal weight) + horizontal divider + journey section (Walk Share button at bottom).
- [ ] Gated on `walk.routePoints.size >= 2` (matches iOS `walk.routeData.count >= 2`). On routes with <2 GPS points, the card does NOT render and the 3 share actions are unavailable.
- [ ] Goshuin button tap → renders `SealSpec` bitmap via existing seal-rendering pipeline → writes to `context.cacheDir/seals/` → `Intent.ACTION_SEND` chooser with `image/png` + `EXTRA_STREAM` set to the cache URI via `FileProvider.getUriForFile`.
- [ ] Etegami button tap → same flow but via `EtegamiBitmapRenderer.render` + `EtegamiPngWriter.writeToCache`.
- [ ] Walk Share Journey button tap → opens the existing `WalkShareScreen` composable in a nested sheet/Dialog (per iOS `.sheet(isPresented:) { WalkShareView(...) }` pattern).
- [ ] Each share success → calls `viewModel.markCurrentWalkShared()` which writes the walk UUID to the tracker.
- [ ] **In-flight tap-debounce: each share button has a per-button `rememberSaveable<Boolean>` `isGenerating` latch.** While a render is in flight, subsequent taps on the SAME button are no-ops. Other buttons remain tappable. Latch clears on share intent dispatch success OR on render error.
- [ ] An inline `CircularProgressIndicator` overlays the tapped button while its `isGenerating` latch is true. iOS shows `SwiftUI.ProgressView().tint(.stone)`. The indicator appears+disappears in lockstep with the latch — fast renders may flash the indicator briefly; that's acceptable behavior, no anti-flicker timer.
- [ ] `ActivityNotFoundException` on share intent dispatch → snackbar fallback (no crash). Does NOT mark walk shared (per D4) — the user couldn't reach the share chooser, so the engagement signal didn't happen.
- [ ] The 3 scattered Android sections (`WalkLightReadingCard` separate slot, `WalkEtegamiCard`, `WalkShareJourneyRow`) are restructured: Light Reading stays in its iOS-correct position (line 86 in iOS body — between Details and shareCard, gated on hasShared), Etegami inline card is deleted, Walk Journey share moves INSIDE the new WalkSharingButtons.

### E. Map mask regression

- [ ] Map renders with circular edges (corners faded to background). Verify visually on OnePlus 13 during device QA. The expected visual: viewing a square map card, the corners outside the inscribed circle are the parchment background color (not the map content).
- [ ] No `setLayerType(LAYER_TYPE_HARDWARE, null)` hacks; preserve the `compositingStrategy = Offscreen + BlendMode.DstIn` approach if achievable.
- [ ] **Fallback path** (only if Stage 1 investigation determines the offscreen-compositing path is fundamentally broken on Android 16 × Compose 1.7+ × Mapbox 11.11): switch to a `Canvas`-overlay ring drawn ABOVE the map. Visual: opaque parchment-colored ring with a circular transparent hole in the center revealing the underlying square map. The corner regions outside the inscribed circle are painted parchment-colored, achieving the same "fade to background" effect via overlay rather than mask. Stage 1's investigation outcome is captured in the plan's Stage 1 commit message — the fallback ships ONLY if the offscreen path can't be salvaged. Reviewable artifact: the Stage 1 investigation note in the plan + the device-QA screenshot showing the resulting visual.

### F. CelestialLineRow centering

- [ ] `CelestialLineRow.kt:39` `Row` modifier uses `Modifier.fillMaxWidth()` + `horizontalArrangement = Arrangement.Center` (replacing the current `Arrangement.spacedBy(PilgrimSpacing.small)` left-aligned arrangement). Visual: text content (moon-in / hour-of / element) horizontally centered within the row's width. Current state verified: left-aligned via `spacedBy`. Drift confirmed.
- [ ] Centering verified programmatically via Compose test: `composeRule.onNodeWithTag("celestial-line-row").assertLeftPositionInRootIsEqualTo(...)` where the expected left X is `(parentWidth - rowWidth) / 2`. Approximation: parent uses `fillMaxWidth`; the row's intrinsic width is derived from its text content. The simpler test: assert the Row's `horizontalArrangement` is `Arrangement.Center` via the Composable's snapshot (read the param via reflection-test OR pin the `Modifier.fillMaxWidth` modifier explicitly). The latter is sufficient — visual centering is a property of the modifier set, not a runtime check.

### G. WalkStatsRow Elevation gate

- [ ] `WalkStatsRow` renders Elevation mini-stat when `ascendMeters > 1` (matches iOS `walk.ascend > 1`). Walks with ≤1m ascend hide the column.
- [ ] Distance mini-stat renders per iOS — iOS `statsRow.miniStat(label: "Distance", value: formatDistance(animatedDistance))` has NO conditional gate (always renders, even at 0). Acceptable for parity even though "0.00 km" on a no-movement walk feels odd. Match iOS exactly.

### Quality gates (apply to all 7 deltas)

- [ ] All existing tests pass.
- [ ] New tests added: each AC bullet has at least one corresponding test at the appropriate layer (unit / Robolectric / Compose).
- [ ] Device QA on OnePlus 13 — manual smoke per delta.
- [ ] No new lint warnings.
- [ ] No new OutRun references; SPDX headers on every new Kotlin file.
- [ ] PR squash-merges to main as one commit per project convention.
- [ ] `jutsu swarm pr-review --full --strict --personas claim-auditor-claude,cross-file-gemini,claim-auditor-deepseek --post-comment` before merge (mandatory per memory autopilot-run-2026-05-11-walk-summary-parity-cleanup-bundle lesson #1 — swarm catches what 14 per-task subagent reviews miss).
- [ ] **Dialog-scope regression tests for prior-PR features** — Stage 5 ships explicit Robolectric tests verifying (1) `rememberSaveable` `revealPhase` survives Dialog-content recomposition during a simulated config change (matches PR #89's rotation-replay fix), (2) `LocalConfiguration` reduceMotion read resolves inside the Dialog's CompositionLocal scope (matches PR #89's reduceMotion gate), (3) `LocalLifecycleOwner` ON_START observer in `PhotoReliquarySection` fires inside the Dialog (matches PR #90's permission re-fetch trigger).

## Locked decisions

- **D1: Walk Summary = full-screen `Dialog(usePlatformDefaultWidth = false)` rendered INSIDE the existing NavComposable.** Stage 13-XZ documented the `Modifier.imePadding()` pattern needed. ModalBottomSheet would cap height + add drag-handle UX that iOS doesn't have. The NavComposable route is preserved (NOT deleted) — wrapping content in Dialog achieves the visual sheet behavior without requiring sheet-state migration to all 4 host screens. iOS's `.sheet(item:)` pattern requires parent-owned state; Compose's `Dialog` inside a NavComposable is the lighter equivalent that preserves the nav-stack contract (popBackStack, deep-links).
- **D2: `WalkSharingTracker` backed by DataStore Preferences `stringSetPreferencesKey("sharedWalkUUIDs")`.** Match iOS key string for cross-platform forensic clarity; storage layer differs (UserDefaults on iOS, DataStore Preferences on Android), but the contract is identical.
- **D3: Walk Share Journey button inside `WalkSharingButtons` opens `WalkShareScreen` via `navController.navigate(Routes.walkShare(walkId))`** — using the existing nav route, NOT a nested Dialog. The Walk Summary Dialog is dismissed by the nav push (popped back when user dismisses Walk Share). This is the cross-version-safe path; iOS's `.sheet(isPresented:)` nested-sheet pattern doesn't transfer cleanly to Compose's `Dialog` (z-order is implementation-defined on Compose 1.7+ × Android 14-16). The visual gap vs iOS is minor (full-screen replace instead of stacked sheets); accept it. Future polish PR could revisit if Compose stabilizes nested-Dialog semantics.
- **D4: `markCurrentWalkShared()` fires on share-INTENT-DISPATCH success, not on whether the user actually completed the system share chooser.** iOS uses the same pattern: `markSharedAndReveal()` fires when `ShareSheet.onDismiss` is called regardless of what the user picked. The user PRESENTING the share intent IS the engagement signal.
- **D5: Light Reading reveal animation = 1200ms fade-in via `AnimatedVisibility(visible = hasShared, enter = fadeIn(tween(1200)))`** under non-reduce-motion. Reduce-motion path: instant visibility (no animation).
(D6 removed — Steps Non-goal is already explicit in the Non-goals section; no separate decision to lock.)

## Open questions

- **Q1 (blocking): Why is the map circular mask not visible on device?** Code at `WalkSummaryScreen.kt:806-821` is correct per Stage 13-B memory lessons (which verified the mask works on landscape too). Either: (a) regression introduced between Stage 13-B and current `309d818`, (b) device-specific behavior on OnePlus 13 / Android 16 × Compose × Mapbox 11.11 combo, (c) my reading misidentified the visual on the device screenshot. Investigation required BEFORE the bundle ships. Plan should include a 30-min investigation task: read the current `SummaryMap` composable + take a fresh device screenshot + run on Android 14 emulator for comparison. Fix may be: (1) revert to a previously-working Compose version, (2) `Modifier.graphicsLayer { alpha = 0.999f }` workaround to force the offscreen layer, (3) the Canvas-overlay ring fallback per Delta E's fallback AC.

- **Q2 (non-blocking): Light Reading position in scroll.** iOS body line 86 puts Light Reading after `detailsSection` (line 85). Verify the Android section order places it equivalently — between Details section and the new `WalkSharingButtons`.

## Risks

- **Map mask regression (Q1) may NOT be a 10-LOC fix.** Worst case: the offscreen-compositing path is fundamentally broken under Compose 1.7+ × Mapbox 11.11 on Android 16. Mitigation: fallback to a `Canvas` overlay drawn ABOVE the map with a transparent center + opaque parchment-colored ring (different visual but achieves the "fade to background" effect). Track as a contingency in the plan.
- **Dialog vs Bottom Sheet UX divergence.** Compose `Dialog(usePlatformDefaultWidth = false)` displays full-screen but transitions differ from `.sheet`. Device QA may flag the slide-in animation as feeling wrong vs iOS. Mitigation: customize Dialog enter transition via `AnimatedVisibility` wrapper, OR ship the default and address as polish if user complains.
- **WalkShare nav push (per D3) replaces Walk Summary visually.** iOS nests share sheets; Android pushes through NavController for cross-version safety. Visual divergence accepted. If device QA flags this as user-confusing (e.g. they expect to return to Walk Summary after dismissing Walk Share), the nav pop should land them on Walk Summary, not on Home — the existing `WalkShareScreen.onDone()` handler should `popBackStack(Routes.WALK_SUMMARY_PATTERN)` not `popBackStack(Routes.HOME)`. Verify in device QA.
- **Bitmap generation latency on low-end devices.** Goshuin + Etegami bitmap renders may take 500ms+ on older devices. The inline ProgressIndicator overlay matches iOS UX but the user might tap multiple times during generation. Defended via the `isGenerating` `rememberSaveable<Boolean>` latch per share button in AC D (similar pattern to PR #90 Stage 4 — promoted from prior-spec Risks to AC).
- **`markCurrentWalkShared()` race with Light Reading rendering.** If the user taps a share button rapidly twice, the second tap fires `markCurrentWalkShared` while the first share intent is still dispatching. Both calls write the same UUID to DataStore (idempotent via stringSet semantics). The `AnimatedVisibility` reveal happens once because `hasShared` transitions false → true exactly once per walk. No race.
- **Existing PR #89/90/91 regressions on the new presentation.** The reveal cinematic, photo carousel/preview-sheet, voice-row polish, and milestone perf fix all assume the current page-style presentation. Migrating to Dialog may break (a) the `rememberSaveable` patterns (Dialog content has its own SaveableStateRegistry — verify), (b) the `LocalConfiguration` reads for `reduceMotion` (Dialog content has a distinct CompositionLocal scope), (c) the `LocalLifecycleOwner` observer in the reliquary section (Dialog has a separate lifecycle). Mitigation: per-stage Robolectric tests in the plan verify each prior PR's behavior still holds.

## Architecture sketch

```
ActiveWalkScreen / HomeScreen / RecordingsListScreen (entry points)
└── Dialog(usePlatformDefaultWidth = false)  ← Delta A
    ├── WalkSummaryTopBar (existing)
    ├── WalkSummaryScreen content  ← unchanged from PR #90 minus the deletions:
    │   ├── SummaryMap                         ← Delta E fix
    │   ├── PhotoReliquarySection
    │   ├── WalkIntentionCard
    │   ├── ElevationProfile
    │   ├── WalkJourneyQuote
    │   ├── WalkDurationHero
    │   ├── MilestoneCalloutRow (conditional)
    │   ├── WalkStatsRow                       ← Delta G verify
    │   ├── WalkSummaryWeatherLine (conditional)
    │   ├── CelestialLineRow                   ← Delta F centering
    │   ├── WalkTimeBreakdownGrid
    │   ├── FaviconSelectorCard
    │   ├── WalkActivityTimelineCard
    │   ├── WalkActivityInsightsCard
    │   ├── WalkActivityListCard
    │   ├── VoiceRecordingsSection (conditional)
    │   ├── WalkAIPromptsRow
    │   ├── WalkSummaryDetailsCard (conditional)
    │   ├── WalkLightReadingCard               ← Delta B gate (was unconditional; gated on hasShared)
    │   └── WalkSharingButtons (NEW)           ← Delta C deletes etegami card + Delta D consolidates 3 actions
    │       ├── imageShareRow (Goshuin button | Etegami button)
    │       ├── divider
    │       └── journeySection (Walk Share button)
    └── (onDismissRequest = onDone — no separate BackHandler needed per Compose Dialog contract)
```

Files created: 2 (`WalkSharingButtons.kt`, `WalkSharingTracker.kt`)
Files modified: 4
- `app/src/main/java/.../ui/walk/WalkSummaryScreen.kt` — delete `WalkLightReadingCard` call site at line ~635, delete `WalkEtegamiCard` + `WalkEtegamiShareRow` references at lines ~641-660, add `WalkSharingButtons` call at bottom-of-scroll position (iOS body line 90 equivalent), re-add gated `WalkLightReadingCard` call at iOS body line 86 equivalent position
- `app/src/main/java/.../ui/walk/WalkSummaryViewModel.kt` — inject `WalkSharingTracker`, add `hasRevealedLightReading: StateFlow<Boolean>`, add `markCurrentWalkShared()` non-suspend method
- `app/src/main/java/.../ui/walk/summary/CelestialLineRow.kt` — `Arrangement.spacedBy` → `Arrangement.Center` + `fillMaxWidth` modifier
- `app/src/main/java/.../ui/navigation/PilgrimNavHost.kt` — wrap `WalkSummaryScreen(...)` call at lines 354-396 in `Dialog(onDismissRequest = onDone, ...) { ... }`

Files deleted: 2 (`WalkEtegamiCard.kt`, `WalkEtegamiShareRow.kt`)

`WalkStatsRow.kt` is referenced in Delta G's AC for verification only — if the existing `ascendMeters > 1` gate is already correct (verify via grep + read), Delta G is a no-op file-modification-count-wise. If the gate is wrong, that's a 5th modified file.

## Implementation phasing

1. **Stage 1 — Investigation (Q1 unblock).** 30-min spike: read current SummaryMap composable + take fresh device screenshot + identify the map-mask regression. Report findings; either fix in this bundle (~10 LOC, baseline) or escalate to user (worst-case Canvas overlay fallback).
2. **Stage 2 — WalkSharingTracker infra (B).** DataStore-backed tracker + VM flow + tests. Foundation for Delta D.
3. **Stage 3 — WalkSharingButtons composable (D).** New unified sharing card + Goshuin button + Etegami button + Walk Share Journey button. Wire `markCurrentWalkShared` on each success. Tests for each share action.
4. **Stage 4 — Etegami card deletion + Light Reading repositioning + Light Reading gate (C + B-finale).** Delete `WalkEtegamiCard.kt`, `WalkEtegamiShareRow.kt`. Move `WalkLightReadingCard` call site to its iOS-correct slot (after Details). Wire `hasShared` gate.
5. **Stage 5 — Walk Summary presentation rewrite (A).** Single-site Dialog wrap at `PilgrimNavHost.kt:354-396` covers all 4 host entry points (post-walk, Home, Recordings, Goshuin) — no nav-graph cleanup needed (route preserved per D1).
6. **Stage 6 — Polish (E + F + G).** Map mask fix (post-Stage 1 investigation), CelestialLineRow centering, WalkStatsRow elevation gate verify.

Each stage is one logical commit on the feature branch; all squash into one PR at merge.

## Downstream handoff

1. `superpowers:writing-plans <this-spec>` → 6-stage implementation plan (matches Implementation phasing above)
2. `superpowers:subagent-driven-development` per-task execution + 2-stage review
3. `jutsu swarm pr-review --full --strict --personas claim-auditor-claude,cross-file-gemini,claim-auditor-deepseek` PRE-MERGE (mandatory per memory autopilot-run-2026-05-11 lesson #1)
4. Device QA on OnePlus 13 — explicit test plan in PR body covering all 7 deltas
5. Squash-merge after user "merge" instruction (per `feedback_no_auto_merge_prs.md`)

## Existing-walk UX impact

`hasShared = false` for ALL pre-bundle walks regardless of prior share behavior. Users with months of walks lose Light Reading visibility on every prior walk until they re-share. **Accepted regression** — iOS shipped the tracker day-one so it has no equivalent transition; backfilling Android from historical analytics/share-events is out of scope and would create false-positive "shared" markings for walks the user never engaged with. The cost is bounded: re-sharing a walk is a 1-tap action; users who engage with Light Reading on legacy walks reach the new gate on the next share.

## Stage AC ownership (resolves the Stage 3 ↔ Stage 4 ambiguity)

Acceptance criteria split across stages:

- **Stage 2 AC:** Delta B bullets 1+2 (WalkSharingTracker infra exists; `hasShared(uuid)` returns true/false correctly from DataStore). NO UI-visible reveal yet.
- **Stage 3 AC:** Delta D bullets 1-6 (WalkSharingButtons composable exists, share intents dispatch correctly, `markCurrentWalkShared` called on intent success, in-flight latch works). NO Light Reading reveal verification yet (reveal gate doesn't ship until Stage 4).
- **Stage 4 AC:** Delta B bullets 3+4 (`WalkLightReadingCard` doesn't render when hasShared = false; renders with 1200ms fade after share success) + Delta C bullets (etegami deletion). This is the stage that ties share-success → reveal user-visibly.
- **Stage 5 AC:** Delta A bullets (Dialog wrapping via `onDismissRequest` + Robolectric regression tests for prior-PR features inside Dialog scope).
- **Stage 6 AC:** Delta E + F + G bullets.

**Sequence-gate enforcement:** Stages 3 and 4 squash-merge as ONE atomic PR (no intermediate releases between them). The plan's Stage 3 commit must NOT push to remote/PR before Stage 4 commit lands on the same feature branch. Documented in the plan as a stage-ordering invariant — implementer enforces via `git push` discipline.

Between Stages 3 and 4 ON THE FEATURE BRANCH, `markCurrentWalkShared` writes to DataStore but `WalkLightReadingCard` still renders unconditionally (pre-bundle behavior, equivalent to PR #91 state). This intentional intermediate state lives only on the feature branch; the squash to main hides it. No regression risk because no version of `main` ever has the in-between state.
