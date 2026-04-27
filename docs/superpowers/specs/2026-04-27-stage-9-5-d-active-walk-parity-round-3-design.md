# Stage 9.5-D: Active Walk parity round 3 — design spec

**Status:** Draft → CHECKPOINT 1
**Date:** 2026-04-27
**Author:** autopilot (Claude Opus 4.7)
**Predecessor:** Stage 9.5-C (PR #53, merged 2026-04-26 as commit `43855f0`)

## Goal

Close the four iOS-parity gaps that surfaced during Stage 9.5-C device QA on OnePlus 13:

1. WalkStatsSheet caption renders the user's intention when set, falling back to "every step is enough" only when no intention.
2. Intention can ONLY be set BEFORE the user taps Start — not in the in-walk WalkOptionsSheet (per user direction; diverges from iOS which has it in both places).
3. WalkOptionsSheet drops its "Set Intention" row, leaving Drop Waypoint as the sole option (with future picker rows reserved for Stage 9.5-E).
4. Drop Waypoint becomes a real interaction — a Material-3 ModalBottomSheet with 6 preset chips (Peaceful / Beautiful / Grateful / Resting / Inspired / Arrived) plus a 50-character custom text + Mark button, mirroring iOS `WaypointMarkingSheet.swift`.

## Non-goals

- **No auto-prompt on entry to ActiveWalk.** iOS auto-shows the IntentionSettingView when `UserPreferences.beginWithIntention.value && intention == nil` on first .ready. Android Stage 9.5-D uses an explicit-tap pill above the pre-walk Start button instead. The user explicitly asked for "available before Start," not "auto-shown before Start"; an explicit pill keeps Pilgrim's wabi-sabi no-friction ethos.
- **No DataStore preferences plumbing** for `beginWithIntention` or `pendingPreWalkIntention`. Without an auto-prompt, the flag is unused; without persistence across process death, the draft can sit in `rememberSaveable` (survives rotation, loses on process death — same trade iOS makes when the user Cancels the sheet).
- **No voice transcription input** on IntentionSettingDialog (iOS `IntentionVoiceRecorder` not ported; Stage N+).
- **No celestial-aware intention suggestions** (depends on Stage 6-A celestial data, which is built; the suggestion phrasing is new copy and out of scope).
- **No intention history chips** (Stage N+; depends on a new DataStore-backed list).
- **No deletion of `WalkController.setIntention(text)`** even though no UI calls it post-9.5-D. The controller surface stays; it's a cheap method and future Settings / restoration paths may use it.

## Architecture

### Files to CREATE

1. **`app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WaypointMarkingSheet.kt`** — Material-3 ModalBottomSheet matching the visual pattern of `WalkOptionsSheet.kt` (parchment containerColor, parchmentSecondary alpha-0.4 row backgrounds). Exposes:
   ```kotlin
   @Composable
   fun WaypointMarkingSheet(
       onMark: (label: String, icon: String) -> Unit,
       onDismiss: () -> Unit,
   )
   ```
   Internal layout (matches iOS `WaypointMarkingSheet.swift`):
   - Header: `Text(stringResource(R.string.walk_waypoint_marking_title))` ("Drop a Waypoint") in `pilgrimType.heading`.
   - 3-column `LazyVerticalGrid(GridCells.Fixed(3))` of 6 chip composables. Chip = `Box` (parchmentSecondary alpha 0.4 background, RoundedCornerShape 12dp) wrapping a `Column` (`Icon` 24dp tinted `pilgrimColors.stone` ABOVE `Text` caption-style `pilgrimColors.ink.copy(alpha=0.8f)`). Tap on chip fires haptic via `LocalHapticFeedback.current.performHapticFeedback(HapticFeedbackType.LongPress)` THEN calls `onMark(chip.label, chip.iconKey)` synchronously. Parent dismisses via `showWaypointMarking = false` set inside its `onMark` lambda; the sheet itself does NOT call onDismiss in the chip branch.
   - Custom-text row: an OutlinedTextField (50-char clamp via `take(50)` in onValueChange) with a right-aligned "Mark" TextButton next to it. `enabled = customText.trim().isNotEmpty()`. Tap fires haptic + `onMark(customText.trim(), "mappin")` + parent dismissal (same pattern).
   - Character-count caption BELOW the row: right-aligned `Text(stringResource(R.string.walk_waypoint_count_chars, customText.length, MAX_WAYPOINT_CUSTOM_CHARS))` in `pilgrimType.caption` color `pilgrimColors.fog.copy(alpha = 0.5f)`. Direct iOS port (`WaypointMarkingSheet.swift:121-126`).
   - Centered "Cancel" TextButton at the bottom: `color = pilgrimColors.fog`. Calls `onDismiss()`.

   Constants: `internal const val MAX_WAYPOINT_CUSTOM_CHARS = 50`.

2. **`app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WaypointMarkingSheetTest.kt`** — Robolectric Compose tests covering: 6 preset chips render, custom-text 50-char clamp, Mark button disabled when text empty, Mark fires onMark with custom + "mappin" icon, chip tap fires onMark with chip values, Cancel calls onDismiss.

3. **`app/src/test/java/org/walktalkmeditate/pilgrim/walk/WalkControllerWaypointLabelIconTest.kt`** — Robolectric test that calls `controller.recordWaypoint(label, icon)` and verifies the inserted Waypoint row has matching `label` + `icon` fields. Plus a no-arg call test confirming both fields default to null (notification path parity).

### Files to MODIFY

1. **`app/src/main/java/org/walktalkmeditate/pilgrim/walk/WalkController.kt`** — change `recordWaypoint()` signature to `recordWaypoint(label: String? = null, icon: String? = null)`. The notification ACTION_MARK_WAYPOINT path keeps its no-arg call site (defaults preserve current behavior). Inside the method, pass both fields to the `Waypoint(...)` constructor.

2. **`app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkViewModel.kt`** — change `dropWaypoint()` to `dropWaypoint(label: String? = null, icon: String? = null)`, threading both to `controller.recordWaypoint(label, icon)`. Existing callers without args stay valid (default values).

3. **`app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkOptionsSheet.kt`** — drop `intention: String?` parameter, drop the first OptionRow (EditNote / Set Intention), drop `onSetIntention: () -> Unit` parameter. Keep the ModalBottomSheet shell + the Drop Waypoint row.

4. **`app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheet.kt`** — add `intention: String?` parameter to the public composable. Inside `statsSection`'s caption Text, replace the hardcoded `stringResource(R.string.walk_caption_every_step)` with a `Crossfade(targetState = intention, animationSpec = tween(600, easing = EaseInOut))` that renders either the trimmed intention or the default string. The Crossfade key uses `intention?.takeIf { it.isNotBlank() }` so blank/null both crossfade to the default.

5. **`app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/ActiveWalkScreen.kt`**:
   - Drop `onSetIntention` flow (no longer used by WalkOptionsSheet); drop `showIntention: Boolean` for the in-walk dialog; drop the IntentionSettingDialog call site for in-walk editing.
   - Add `preWalkIntention: rememberSaveable<MutableState<String?>>` (`mutableStateOf(null)`) to hold the user's typed-but-not-yet-saved pre-walk intention. Survives rotation; resets on process death (see Edge Cases for justification).
   - Add `showPreWalkIntention: rememberSaveable<MutableState<Boolean>>` for the dialog visibility.
   - Thread `intention` from the ViewModel to `WalkStatsSheet`.
   - Change `onStartWalk = { viewModel.startWalk() }` to `onStartWalk = { viewModel.startWalk(preWalkIntention.value); preWalkIntention.value = null }` so the typed intention is committed at row creation and the pill resets after the transition.
   - Pass `preWalkIntention.value` (the pill's preview text) and `{ showPreWalkIntention.value = true }` (the tap handler) to two new parameters on `WalkStatsSheet` — `preWalkIntention: String?` and `onSetPreWalkIntention: () -> Unit`. WalkStatsSheet routes them through to its private `ActionButtonRow` (current name at WalkStatsSheet.kt:504, NOT `actionsSection`); ActionButtonRow renders the pill as a sibling block ABOVE the Start button when `walkState == Idle` and ignores both params for in-progress states.
   - When `showPreWalkIntention` is true, render `IntentionSettingDialog(initial = preWalkIntention.value, onSave = { preWalkIntention.value = it.takeIf { it.isNotBlank() }; showPreWalkIntention.value = false }, onDismiss = { showPreWalkIntention.value = false })`. Dismiss WITHOUT save discards the typed text — same behavior as iOS Cancel.
   - Replace `WalkOptionsSheet` `onDropWaypoint` callback to set a new `showWaypointMarking: rememberSaveable<MutableState<Boolean>>` flag instead of immediately calling `viewModel.dropWaypoint()`. Render `WaypointMarkingSheet` when that flag is true; the sheet's `onMark(label, icon)` calls `viewModel.dropWaypoint(label, icon)` AND sets `showWaypointMarking = false` (explicit dismiss inside the chip-tap branch — the sheet's parent owns dismissal, not the sheet itself).
   - Auto-dismiss `showPreWalkIntention` and `showWaypointMarking` in the existing `LaunchedEffect(navWalkState::class)` polish block when the walk is no longer in an in-progress state. Idle is special-cased: `showPreWalkIntention` stays valid in Idle (it's the pre-walk surface) but `showWaypointMarking` dismisses on Idle (waypoint sheet only makes sense during a walk).

6. **`app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheet.kt` (additional)** — add two parameters to the public composable AND to the private `ActionButtonRow`: `preWalkIntention: String?` (the pill's preview text) and `onSetPreWalkIntention: () -> Unit` (tap handler). `ActionButtonRow`'s Idle branch (currently `if (walkState == WalkState.Idle) { Box { CircularActionButton(Start) } ; return }` at WalkStatsSheet.kt:525-539) becomes a Column { pill ; Spacer ; Start button }. The pill renders only in the Idle branch:
   - When `preWalkIntention == null`: pill text is `stringResource(R.string.walk_pre_intention_pill_unset)` ("Set an intention") in `pilgrimType.caption` color `pilgrimColors.fog`.
   - When set: pill text is the trimmed first 30 chars of the intention (truncate-with-ellipsis using `Text(maxLines = 1, overflow = TextOverflow.Ellipsis)`), color `pilgrimColors.ink`. Visual treatment: a `pilgrimColors.parchmentSecondary` background pill with `RoundedCornerShape(20.dp)` and 16.dp horizontal / 8.dp vertical padding. Tappable via the standard `clickable(interactionSource, indication = null, role = Role.Button)` pattern (matches Stage 9.5-B's no-ripple convention).
   - Spacer above and below the pill so the Start button stays visually anchored.

7. **`app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/IntentionSettingDialog.kt`** — three changes:
   - Rename the confirm button label string from "Save" to "Set" for iOS parity (`IntentionSettingView.swift:325` uses `"Set"`). Update `walk_options_intention_save = "Set"` in strings.xml. The string-key name `walk_options_intention_save` keeps to avoid a rename cascade through tests; only the visible value changes.
   - Add a character count caption beneath the OutlinedTextField that reads `stringResource(R.string.walk_waypoint_count_chars, text.length, MAX_INTENTION_CHARS)` (reuses the `%1$d/%2$d` template — the resource is generic). Color animates from `pilgrimColors.fog` → `pilgrimColors.moss` proportional to `text.length / MAX_INTENTION_CHARS` via `animateColorAsState`.
   - (Skipped from iOS port: the small "Voice" mic toggle to the LEFT of the count badge, which depends on `IntentionVoiceRecorder`.)

8. **`app/src/main/res/values/strings.xml`** — add new strings:
   - `walk_pre_intention_pill_unset = "Set an intention"`
   - `walk_waypoint_marking_title = "Drop a Waypoint"`
   - `walk_waypoint_marking_custom_placeholder = "Custom note"`
   - `walk_waypoint_marking_mark = "Mark"`
   - `walk_waypoint_marking_cancel = "Cancel"`
   - `walk_waypoint_chip_peaceful = "Peaceful"`
   - `walk_waypoint_chip_beautiful = "Beautiful"`
   - `walk_waypoint_chip_grateful = "Grateful"`
   - `walk_waypoint_chip_resting = "Resting"`
   - `walk_waypoint_chip_inspired = "Inspired"`
   - `walk_waypoint_chip_arrived = "Arrived"`
   - `walk_waypoint_count_chars = "%1$d/%2$d"` (generic count badge — reused by both intention dialog AND waypoint marking sheet; en-US uses Latin digits via the resource format directly).

   Modify existing strings:
   - `walk_options_intention_save = "Set"` (was "Save"; change for iOS parity per `IntentionSettingView.swift:325`).

   Delete dead strings (introduced by 9.5-C, now unused):
   - `walk_options_intention_unset = "No intention set"` — was the WalkOptionsSheet subtitle when intention was null. The row is removed; the string has no caller. Deleting prevents dead-code drift.

   Existing `walk_options_intention_dialog_title`, `walk_options_intention_placeholder`, `walk_options_intention_cancel` stay — used by `IntentionSettingDialog` from the pre-walk surface. (`walk_options_intention_title` "Set Intention" is also dropped during polish: only the dialog-title variant is referenced after the row removal.)

### Material icon mapping (chip glyphs)

| Chip | iOS SF Symbol | Android Material Icon | Icon key (stored in DB) |
|---|---|---|---|
| Peaceful | `leaf` | `Icons.Outlined.Spa` | `"leaf"` |
| Beautiful | `eye` | `Icons.Outlined.Visibility` | `"eye"` |
| Grateful | `heart` | `Icons.Outlined.FavoriteBorder` | `"heart"` |
| Resting | `figure.seated.side` | `Icons.Outlined.Chair` | `"figure.seated.side"` |
| Inspired | `sparkles` | `Icons.Outlined.AutoAwesome` | `"sparkles"` |
| Arrived | `flag.fill` | `Icons.Filled.Flag` | `"flag.fill"` |
| Custom (Mark) | `mappin` | `Icons.Filled.LocationOn` | `"mappin"` |

Storing the iOS SF-Symbol name as the icon key (rather than a Material name) future-proofs cross-platform export — a `.pilgrim` ZIP exported from Android imports into iOS without an icon-key translation step. The Android display layer holds a `iconKeyToVector(key: String): ImageVector` lookup function mapping iOS keys → Android icons; unrecognized keys fall back to `LocationOn`.

### Haptic on chip tap

Stage 4-D's milestone celebration uses `LocalHapticFeedback.current.performHapticFeedback(HapticFeedbackType.LongPress)` (the closest Android equivalent to iOS `.light` impact). Stage 9.5-D reuses this for the chip-tap moment: each preset chip and the Mark button fires the haptic on tap. iOS `HapticPattern.waypointDropped` (light) is matched.

### Caption Crossfade animation

`Crossfade(targetState = intention?.trim()?.takeIf { it.isNotEmpty() }, animationSpec = tween(600, easing = EaseInOut)) { resolved -> Text(resolved ?: stringResource(R.string.walk_caption_every_step), ...) }`. The trim+isNotEmpty key ensures: (a) whitespace-only intention crossfades to fallback (matches iOS), (b) the rendered Text uses the trimmed value not the raw, (c) Crossfade compares String? equality so unrelated parent recomposes (e.g. distance ticking) do not re-trigger the animation — `String?` `equals` returns true for the same value, Crossfade no-ops. The 600ms duration is the iOS Pilgrim aesthetic for "arrival" transitions (matches the seal reveal fade-in at Stage 4-B). This animation fires once per intention change — battery-irrelevant.

### IntentionSettingDialog character count delight

`val countColor by animateColorAsState(targetValue = lerp(pilgrimColors.fog, pilgrimColors.moss, text.length.toFloat() / MAX_INTENTION_CHARS))`. Subtle, helpful, no battery cost.

## Data flow

### Pre-walk intention path

```
Idle state
  ↓
ActiveWalkScreen renders WalkStatsSheet with intentionPillText = preWalkIntention.value
  ↓ (user taps pill)
showPreWalkIntention = true
  ↓
IntentionSettingDialog(initial = preWalkIntention.value)
  ↓ (user types + taps Save)
preWalkIntention.value = trimmed.takeIf { isNotBlank() }
showPreWalkIntention = false
  ↓ (user taps Start button)
viewModel.startWalk(preWalkIntention.value)
  ↓
WalkController.startWalk(intention) → repository.startWalk(intention) → walks row inserted with intention column populated
  ↓
WalkAccumulator initializes; viewModel.intention StateFlow emits new value via the existing 9.5-C combine flow
  ↓
WalkStatsSheet's Crossfade swaps "every step is enough" → user's intention (600ms)
```

### Drop Waypoint path

```
Active|Paused|Meditating state, user taps ellipsis
  ↓
showOptions = true → WalkOptionsSheet
  ↓ (user taps Drop Waypoint row)
showOptions = false; showWaypointMarking = true
  ↓
WaypointMarkingSheet
  ↓ (user taps a chip — say "Peaceful")
WaypointMarkingSheet's chip onClick: LocalHapticFeedback.performHapticFeedback(LongPress); onMark("Peaceful", "leaf")
  ↓
ActiveWalkScreen's onMark lambda: viewModel.dropWaypoint(label = "Peaceful", icon = "leaf"); showWaypointMarking = false
  ↓
WalkController.recordWaypoint(label, icon) under dispatchMutex; reads accumulator.lastLocation; inserts Waypoint row with both fields populated
  ↓
WalkViewModel.waypointCount StateFlow emits new value (existing 9.5-C wiring; ActiveWalkScreen and any future surface using waypointCount auto-update)
  ↓
ModalBottomSheet plays its slide-down animation as showWaypointMarking flips to false (Compose conditional render removes the sheet from the tree; ModalBottomSheet's onDismissRequest is NOT what dismissed it — the parent owns dismissal).
```

## Edge cases

- **Pre-walk intention typed, user taps X (no Save):** Dialog dismisses; `preWalkIntention` unchanged. The new `showPreWalkIntention = false` is set in `onDismiss`. The typed-but-unsaved text is discarded — same as iOS Cancel. ✓
- **Pre-walk intention typed + Saved, then user navigates away.** Behavior depends on the navigation type:
  - **Back-button pop** (back-stack pop of ACTIVE_WALK route): the NavBackStackEntry is destroyed, the rememberSaveable bundle is dropped, `preWalkIntention` is cleared. Reopening shows "Set an intention" (unset).
  - **Tab-switch** (Path → Home/Goshuin via bottom-nav): the Path subtree is popped with `saveState = true` (PilgrimNavHost.kt:86). The ACTIVE_WALK entry's saved state is preserved. On return to Path, the subtree restores; the user lands back on ActiveWalkScreen with the typed `preWalkIntention` still in the pill. **This is intentional** — preserves the user's draft across cross-tab activity (e.g., checking an old walk on Goshuin while composing intention).
  - **Successful Start**: `viewModel.startWalk(intention = preWalkIntention)` commits to the Walk row, then `preWalkIntention = null` clears the local draft.
  - **Process death + cold-start return**: rememberSaveable bundle round-trips; the draft survives. Same as tab-switch case.
  Result: the only paths that clear `preWalkIntention` are explicit back-button pop and successful Start. This is the deliberate trade for Pilgrim's "no-friction" framing — the user's act of EXPLICITLY leaving the surface (back) clears, but tab-switching or process kill does not. Sample audit-trail: spec was originally wrong here (claimed nav-away always clears); Gemini PR #54 review caught the discrepancy and corrected the doc.
- **Process death between Save and Start:** Both `preWalkIntention: rememberSaveable<String?>` AND `showPreWalkIntention: rememberSaveable<Boolean>` round-trip through Bundle via the default Saver, so the draft AND open-state survive a process kill. This contradicts an earlier (incorrect) draft of the spec which claimed `String?` rememberSaveable does not survive process death — Compose's default Saver handles String?, Boolean, Int and other Bundle-supportable scalars uniformly. The behavior is equivalent to the tab-switch case above. Acceptable per the no-friction framing; if we ever want session-scoped clearing we'd need a `DisposableEffect` keyed on the screen's NavBackStackEntry (deferred — current behavior is the lower-friction one for the user's perspective).
- **WaypointMarkingSheet open, walk transitions to Idle (discard from notification or Finished from finish action):** existing `LaunchedEffect(navWalkState::class)` from 9.5-C polish is extended to also dismiss `showWaypointMarking`. ✓
- **WaypointMarkingSheet open, user rotates phone while typing custom text:** `var customText by rememberSaveable { mutableStateOf("") }` survives rotation. ✓
- **Rapid double-tap on a chip:** Material 3 ModalBottomSheet absorbs taps once dismissal starts; the `onMark` lambda fires once. Even if it did fire twice, `controller.recordWaypoint` is best-effort dispatch — second waypoint at same location is a noisy but tolerable result. No debounce needed.
- **Mark button with empty custom text:** TextButton `enabled = customText.trim().isNotEmpty()`. Text+color both reflect disabled state via `LocalContentColor`.
- **Unknown icon key in DB (export from older iOS, import to newer Android):** `iconKeyToVector(key)` falls back to `Icons.Filled.LocationOn`. Loud-fail-then-graceful: log a warning so we know in telemetry.
- **WalkOptionsSheet open + walk transitions terminal:** existing 9.5-C auto-dismiss already covers this. ✓
- **Pre-walk intention pill ON-screen during meditation transition:** Idle is the only state that renders the pill; transitioning to Active hides it. ✓

## Tests

Per the spec, add:

1. `WaypointMarkingSheetTest.kt` (Robolectric Compose):
   - 6 preset chips render with correct labels.
   - Custom text field clamps at 50 chars.
   - Mark button disabled when text empty (assert via TalkBack semantics).
   - Tap chip → onMark fires with chip values + dismisses.
   - Tap Mark on custom text → onMark fires with trimmed custom text + "mappin" icon + dismisses.
   - Cancel calls onDismiss without firing onMark.

2. `WalkControllerWaypointLabelIconTest.kt` (Robolectric):
   - `controller.recordWaypoint("Peaceful", "leaf")` from Active inserts Waypoint with matching fields.
   - `controller.recordWaypoint()` from Active (no args) inserts Waypoint with null fields.
   - Idle/Finished states no-op (existing test preserved).

3. `WalkOptionsSheetTest.kt` modifications:
   - Remove the 3 intention-row tests.
   - Add a test asserting "Set Intention" text is NOT rendered.
   - Existing waypoint-row tests stay (the row visually unchanged).

4. `ActiveWalkScreenTest.kt` (if missing, create):
   - Idle state renders Set-an-intention pill.
   - Tapping pill opens IntentionSettingDialog.
   - Saving in dialog updates pill text.
   - Tapping Start with set intention calls viewModel.startWalk(intention).

5. `WalkStatsSheetTest.kt` modifications (or new):
   - Caption renders intention when set.
   - Caption renders fallback when intention is null.
   - Caption renders fallback when intention is blank (whitespace only).
   - Crossfade does NOT re-trigger on unrelated recompose (e.g., distance ticks) when intention value is unchanged. Approach: drive a recompose by changing `distanceMeters` from `100.0` → `200.0` while `intention` stays `"walk well"`; assert the rendered caption text is stable (Compose's `assertExists` after recompose) AND that the Crossfade `Animatable` was not stepped between frames. (Since direct Animatable inspection is awkward in Robolectric, the practical assertion is "intention text remains visible across the recompose boundary without flicker" — `composeRule.mainClock.autoAdvance = false` then drive recompose, assert.)

6. `IntentionSettingDialogTest.kt` modifications:
   - Confirm button label reads "Set" (was "Save"; iOS-parity).
   - Character count is rendered correctly at 0, 70, 140.
   - Color animates across length thresholds (test renders three states: 0, 70, 140 chars; assert the resolved text-color StateValue at each).

7. `WalkTrackingServiceNotificationTest.kt` (new, Robolectric or pure JVM): assert that the notification ACTION_MARK_WAYPOINT path still compiles + dispatches with `controller.recordWaypoint()` no-arg after the signature change. CLAUDE.md mandates platform-object builder tests for any boundary that constructs `WorkRequest`/`AudioFocusRequest`/`Intent`/etc. — the recordWaypoint signature change does not touch a builder, but the service routes a tap to a controller call, and Stage 2-F's lesson stands: fakes at the boundary hide build crashes. Either Robolectric-test the service intent dispatch OR call out a manual on-device QA step to tap the notification's "Mark Waypoint" action during Stage 5 QA. Pick one and execute.

## Dependencies / risks

- **Stage 9.5-A inset trap.** The new pre-walk intention pill renders inside WalkStatsSheet which is inside the NavHost Scaffold. Do NOT call `statusBarsPadding()`/`navigationBarsPadding()` on the pill or its parent — the Scaffold's innerPadding already accounts for system bars. Pattern from Stage 9.5-B's MapOverlayButtons fix.
- **Stage 5-G stale-cache.** The Crossfade caption reads `viewModel.intention` (Eagerly StateFlow from 9.5-C polish — already correctly seeded). No new stale-cache exposure.
- **Stage 5-A first-emission.** Pre-walk intention pill renders only on Idle state. The `LaunchedEffect(navWalkState::class)` from 9.5-C handles the auto-dismiss; the pill itself is purely conditional render via `when (walkState) { Idle -> pill+startButton; else -> 3-button row }` — no LaunchedEffect needed.
- **iOS-Android cross-platform export.** Storing iOS SF Symbol names in the `icon` column ensures `.pilgrim` ZIP export round-trips. Mapping table at the Android display layer; unknowns fall back gracefully.
- **Notification ACTION_MARK_WAYPOINT.** Path stays `controller.recordWaypoint()` (no-arg). Defaults to label=null, icon=null. Same Walk row as today.
- **Existing in-walk WalkOptionsSheet "Drop Waypoint" code path** today calls `viewModel.dropWaypoint()` directly. Stage 9.5-D changes this to open the WaypointMarkingSheet instead. Existing test for the immediate drop becomes a test for "tapping Drop Waypoint opens the sheet" — update the test, don't delete it.

## Approvable summary

3 user requirements addressed:
1. Caption swap (small, threaded through with Crossfade delight).
2. Pre-walk intention pill (explicit-tap, NOT auto-prompt; Pilgrim-ethos aligned).
3. WalkOptionsSheet trimmed (drops intention row).
4. WaypointMarkingSheet ported from iOS (6 preset chips + custom text + Mark + Cancel + light haptic on chip tap).

~9-12 tasks; ~6 new files (1 source + 5 tests adjusted/added) and 6 modified source files. Estimated ~6-8 hours of implementation + polish.
