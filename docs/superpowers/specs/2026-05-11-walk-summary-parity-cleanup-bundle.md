# Walk Summary parity cleanup bundle — design

**Date:** 2026-05-11
**Parity target:** pilgrim-ios v1.5.0 (`db4196e`)
**Source:** `docs/parity/2026-05-11-walk-summary-subslices-triage.md` (4 actionable PRs from "67 missing" audit)
**Slug:** `walk-summary-parity-cleanup-bundle`

## Problem

| | |
|---|---|
| **Problem** | WalkSummary scene reached structural iOS v1.5.0 parity in PR #89 (reveal cinematic + count-up). The sub-slice triage identified ~880 LOC of remaining behavior gaps across four sub-surfaces (voice-row polish, reliquary state polish, carousel feature, preview-sheet feature) that would otherwise ship as 4 sequential PRs. |
| **Who feels it** | Users opening Walk Summary after a walk — voice-recording playback feels slightly off vs iOS, photo reliquary doesn't behave correctly under permission edge cases, no horizontal carousel UX, no full-screen photo preview. |
| **When** | Every walk that has voice recordings AND/OR pinned photos. Permission edge cases trigger when user revokes Photos access from system Settings while app is backgrounded. |
| **Today's workaround** | Voice-row gaps are minor — user adapts. Reliquary feature gaps are invisible (the grid renders SOMETHING for the empty/permission-denied case but doesn't match iOS UX). Carousel + preview-sheet have no Android equivalent. |
| **Cost of doing nothing** | WalkSummary stays at ~85% iOS v1.5.0 parity. Future iOS work past v1.5.0 (already shipped — currently OUT OF SCOPE per CLAUDE.md) compounds drift if these surfaces aren't aligned first. |

## In scope (4 PRs bundled into 1)

### A. Voice-row polish (~80 LOC + tests)

Closes 3 + 1 cosmetic gaps in `VoiceRecordingsSection.kt` / `ExoPlayerVoicePlaybackController.kt`:

1. **100ms seek defer** on togglePlay starting fresh-then-seeking. iOS `VoiceRecordingRow.swift:85-94@db4196e`.
2. **Transcription expand threshold** `count > 280 OR newlines > 7` on Walk Summary surface (backport from `RecordingRow.kt:156-161@ce11a87` which already has it for the standalone Recordings List). iOS `VoiceRecordingRow.swift:27-36@db4196e`.
3. **Done-button trim + empty-skip on persistence** — currently `TranscriptionEditor` trims on commit but persists empty edits. iOS `VoiceRecordingRow.swift:139-154@db4196e`.
4. **AudioPlayer speed-cycle algorithm alignment** — `firstIndex(of:) + modulo` on `[1.0, 1.5, 2.0]` array (matches iOS line-for-line; cosmetic but reduces future-pull diff). iOS `AudioPlayerModel.swift:11@db4196e`.

### B. Reliquary state polish (~250 LOC + tests)

Closes 5 gaps in `PhotoReliquarySection.kt`. The gate has four explicit states with **strict precedence** (top wins when multiple apply):

1. `ToggleOff` — `Settings.walkReliquaryEnabled == false` → render nothing.
2. `PermissionDenied` — toggle on, full deny on `READ_MEDIA_IMAGES` → permission-revoked prompt + Settings deep-link button (`Intent.ACTION_APPLICATION_DETAILS_SETTINGS` with `package:` URI).
3. `Loading` — toggle on, permission granted, fetch in flight ≥ 300ms with `candidates.isEmpty()` → deferred-skeleton shimmer.
4. `Populated` — toggle on, permission granted, fetch complete, `candidates.isNotEmpty()` → carousel.

The "empty" leaf (fetch complete + empty) renders nothing (collapses to height-zero) — distinct from `Loading` which renders the deferred skeleton.

Behaviors implemented:

1. **4-state gate with precedence** (above) — iOS `PhotoReliquarySection.swift:58-77@db4196e`.
2. **Deferred-skeleton 300ms with double-check** — iOS `PhotoReliquarySection.swift:80-91@db4196e`. On fetch start, schedule a 300ms `delay`; after the delay re-verify `(state == Loading && candidates.isEmpty())` before flipping `showSkeleton = true`. The double-check skips the skeleton flash for fetches that complete in <300ms.
3. **Lifecycle.Event.ON_START observer re-fetch on permission-state TRANSITION** — iOS `PhotoReliquarySection.swift:101-124@db4196e`. `LifecycleEventEffect(ON_START)` re-reads permission via `ContextCompat.checkSelfPermission`, compares to the VM's `previousPermissionGranted` snapshot (per D6), and triggers a fresh fetch ONLY on the `denied → granted` transition. No fetch on raw lifecycle resume; no fetch on empty-Populated walks (avoids infinite re-fetch loop).
4. **fetchGeneration monotonic counter** — iOS `PhotoReliquarySection.swift:259-279@db4196e`. ViewModel increments `fetchGeneration: Long` on every fetch; async completion drops its result if `generation != currentGeneration`. Guards against rapid permission re-grant / re-fetch races.
5. **Permission-revoked prompt + Settings deep link** — iOS lines 167-188. Composable card with body text + button that dispatches `Intent(ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)).addFlags(FLAG_ACTIVITY_NEW_TASK)`.

### C. Reliquary carousel feature (~200 LOC + tests)

Net-new `PhotoCarousel` composable that REPLACES the current 3-column grid (per locked decision D1 below):

- `LazyRow` with 88dp square thumbnails (iOS uses 88pt)
- Activation state machine: long-press 400ms → activated state (1.05× spring scale, response 0.3, damping 0.7)
- Scroll-phase observer: clear activation when the user **drags** the list (touch-driven), NOT on programmatic scroll. Wire via `LazyListState.interactionSource.collectIsDraggedAsState()` — this excludes `scrollToItem`/`animateScrollToItem` and matches iOS `.interacting` semantics. (Locked: `isScrollInProgress` would over-fire on programmatic scrolls and was explicitly rejected.)
- Pinned-badge overlay on photos already in walk's reliquary
- Haptic on **activate** (long-press → `HapticFeedbackType.LongPress`) and on **commit** (tap-on-activated-thumbnail-to-open-preview-sheet → `HapticFeedbackType.TextHandleMove` soft strength). These are the only two activation-state-machine actions; pin/unpin happens via the preview-sheet's pin button (Section D below) and the carousel's pinned-badge tap (which routes through the existing repository unpin path, no carousel-local haptic).
- Tap on activated thumbnail → opens preview-sheet (Section D below)

### D. Reliquary preview-sheet feature (~350 LOC + tests)

Net-new `PhotoPreviewSheet` full-screen `Dialog` (per locked decision D2 below):

- `Dialog(properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false))` + black-background `Surface(fillMaxSize)`
- High-res async fetch via Coil 3 — pass the photo's content URI as a **`String` model** (`"content://..."`) NOT `Uri.parse(...)`. Stage 7-A pattern: `StringMapper` → `AndroidContentUriFetcher`. Inline `Uri.parse` defeats Coil's cache-key equality and wastes allocations per Stage 7-A memory lesson.
- Drag-to-dismiss in **dp-space** (gesture API is px-native; convert at the boundary via `LocalDensity.current`). Threshold `120dp` derived from iOS `PhotoPreviewSheet.swift:63-72@db4196e` (`value.translation.height > 120` in iOS pt; pt ≈ dp at 1× density so the literal carries):
  - Capture `val density = LocalDensity.current` once per recomposition.
  - Accumulate `dragOffsetDp` via `pointerInput { detectVerticalDragGestures { _, dragAmount -> dragOffsetDp += with(density) { dragAmount.toDp() } } }`.
  - Threshold: `dragOffsetDp > 120.dp` on gesture end → dismiss via `spring(stiffness = StiffnessLow, dampingRatio = 0.8)`. `dragOffsetDp ≤ 120.dp` → snap back via same spring.
- Pin-button enabled state **derived from repository** (per D4): button enabled iff `photoId !in pinnedPhotoIds.collectAsState().value` AND `!pinningInFlight.collectAsState().value`. The VM owns the in-flight Mutex (Stage 7-A pattern). No separate `hasCommitted` latch — repository state is the durable source of truth across rotation, process death, back-nav, and unpin-via-badge-tap.
- "Open in Gallery" button → `Intent(Intent.ACTION_VIEW, contentUri).setDataAndType(contentUri, "image/*").addFlags(FLAG_GRANT_READ_URI_PERMISSION)` (Android equivalent of iOS `photos-redirect://`). Explicit `image/*` MIME type prevents OEM resolver ambiguity (some launchers route un-typed `content://` to file browsers instead of image viewers). Wrap dispatch in `try/catch (ActivityNotFoundException)` with snackbar fallback (Stage 7-D pattern).
- Back-handler: `BackHandler(enabled = true)` triggers the same dismiss path as drag-down (single exit contract).

## Non-goals

- **Milestone N+1 query fix** (`WalkSummaryViewModel.kt:1365-1385`) — P1 in user's mental model, separate PR. ~30 LOC + 1 test.
- **Android 14+ partial-permission flow** (`READ_MEDIA_VISUAL_USER_SELECTED`). Treat partial-grant as full-grant for this bundle — same code path as `READ_MEDIA_IMAGES`. The partial-grant "Manage" affordance is a future UX surface (Phase N). Only full deny triggers `PermissionDenied` in the 4-state gate above. **The Risks entry below about distinguishing partial-deny is superseded by this non-goal — only full-deny vs grant matters here.**
- **WalkSummary token/dimension drift-cosmetic** — 11 cosmetic findings from parity audit (top-bar 64dp magic, title padding 72dp, etc.). Separate polish PR.
- **iOS post-v1.5.0 work** — parity scope frozen at `db4196e` per CLAUDE.md.
- **Photo Reliquary toggle UX changes** — toggle stays default-off; Settings copy unchanged; no new permission flow.
- **AudioPlayer UX changes** — only algorithm alignment, not speed-set UI or new playback features.
- **WidgetKit/Glance/Live-Activity surfaces** — out of scope; future Phase N.
- **ML Kit photo analysis enhancements** — Stage 7-B+ already shipped.
- **Replacing Coil** — keep current image loader.
- **Carousel layout customization** — single fixed layout (88dp horizontal scroll); no user-configurable density / grid-toggle.
- **Animation tuning beyond what acceptance lists** — spring durations are iOS-port values; no perceptual tweaks.

## Acceptance criteria

### A. Voice-row

- [ ] **Seek defer is BEHAVIORAL, not implementation-coupled.** Under `runTest` virtual time: after `togglePlay+seek` fires, advancing the test clock 99ms produces zero `playback.seek(...)` calls; advancing to 100ms produces exactly one. The implementation may use `delay`, `withTimeout`, or any other primitive — the test gates only on the observable timing contract.
- [ ] Transcription with `text.length > 280` OR `text.count { it == '\n' } > 7` (Kotlin `String.length` = UTF-16 code units) collapses on Walk Summary with expand toggle visible — verify via Compose test. (Voice recordings are transcribed Latin/Han/Cyrillic prose for which grapheme-vs-code-unit drift is below 1% — out of test scope; user-typed emoji edge cases are sub-threshold for the 280-char gate.)
- [ ] Empty-after-trim edit does NOT persist — VM test asserting empty-string commit produces zero `repository.updateVoiceRecordingTranscription` calls.
- [ ] Non-empty-after-trim edit DOES persist with the trimmed value — VM test asserting `"  hello  \n"` commit produces exactly one `repository.updateVoiceRecordingTranscription(uuid, "hello")` call.
- [ ] AudioPlayer speed cycle output matches iOS `[1.0, 1.5, 2.0]` array+modulo — parametrized unit test for inputs `{1.0 → 1.5, 1.5 → 2.0, 2.0 → 1.0, anything-else → 1.0}`.

### B. Reliquary state

- [ ] `PhotoReliquarySection` renders the **named precedence-resolved state** for each of the 4 inputs:
  - toggle OFF (any permission, any candidates) → `ToggleOff` → empty tree (no nodes).
  - toggle ON, full deny → `PermissionDenied` → permission prompt card + Settings button visible.
  - toggle ON, granted, fetch in-flight ≥ 300ms, candidates empty → `Loading` → skeleton shimmer node visible.
  - toggle ON, granted, fetch complete, candidates non-empty → `Populated` → carousel node visible.
  - toggle ON, granted, fetch complete, candidates empty → `Populated`-empty leaf → empty tree.
  
  Verify each via Compose `setContent` + `onNodeWithTag(...)` assertions.
- [ ] **Settings deep link**: tapping the Settings button when `PermissionDenied` dispatches an `Intent` with `action == ACTION_APPLICATION_DETAILS_SETTINGS`, `data.scheme == "package"`, `data.schemeSpecificPart == applicationId`, and `flags & FLAG_ACTIVITY_NEW_TASK != 0`. Verify via injected `FakeIntentLauncher` capturing dispatched intents.
- [ ] Foregrounding the app after permission **revoke** triggers state transition to `PermissionDenied` (no fetch). Foregrounding after permission **re-grant** triggers exactly one re-fetch. Foregrounding with **no permission change** triggers zero re-fetches (even on empty-Populated walks — verifies no infinite re-fetch loop). Three Robolectric tests covering each transition.
- [ ] Rapid permission re-grant produces exactly one populated render (no flicker of stale empty state) — fetchGeneration counter test asserting only the last completion writes candidates (older completions are dropped at the `generation != currentGeneration` guard).
- [ ] **Skeleton double-check semantics**:
  - Fetch completes at 250ms → no skeleton ever shown (300ms `delay` hasn't fired).
  - Fetch completes at 350ms → skeleton briefly visible (300ms tick fires while still loading), then carousel replaces it on fetch completion.
  - Fetch starts, permission revoked at 200ms (state transitions to `PermissionDenied`) → 300ms tick fires but the double-check `(state == Loading)` returns false → no skeleton flash.

### C. Carousel

- [ ] `PhotoCarousel` renders horizontal LazyRow with 88dp thumbnails — `onNodeWithTag("photo-thumbnail").assertWidthIsEqualTo(88.dp)`.
- [ ] 400ms long-press activates — `composeRule.performTouchInput { longClick(durationMillis = 400) }` then `assertExists` on activated overlay.
- [ ] **Activation clears on user touch-drag of the carousel; programmatic scroll preserves activation.** Two cases:
  - User drag (`performTouchInput { swipeLeft() }`) after activating a thumbnail → activated state becomes null.
  - Programmatic scroll (`lazyListState.scrollToItem(N)`) after activating → activated state PERSISTS.
- [ ] Pinned badge renders on photos in walk's `WalkPhoto` set — VM test seeded with pinned photo IDs asserts the badge `onNodeWithTag` is visible only on those rows.
- [ ] Haptic fires on activate (`HapticFeedbackType.LongPress`) and on commit (`HapticFeedbackType.TextHandleMove`, the soft equivalent of iOS `.soft` impact) — verify via injected `FakeHapticFeedback` capturing strengths in order.

### D. Preview-sheet

- [ ] Tap on activated carousel thumbnail opens `PhotoPreviewSheet` — Compose test.
- [ ] **Drag-down at the dp boundary**: 119dp drag → snap-back to position (sheet remains open); 121dp drag → dismiss. Two parametrized gesture tests using `performTouchInput { swipeDown(startY = X.toPx(), endY = (X + 121.dp).toPx()) }` with `LocalDensity` conversion. **Velocity is NOT part of the contract** — dismiss decision is translation-only; tests use the default `swipeDown` duration (200ms). A fast-flick under 120dp still snaps back; a slow drag over 120dp still dismisses.
- [ ] **Pin button state is derived from repository** (per D4):
  - 3× rapid taps on opened sheet → exactly 1 `repository.pinPhotos` call (Mutex serializes).
  - Open sheet for photo P → commit → dismiss → reopen sheet for same P → pin button is **disabled** (P now in `pinnedPhotoIds`).
  - Open sheet for different photo Q after committing P → pin button is **enabled** (Q not in `pinnedPhotoIds`).
  - Unpin P via carousel badge tap → P removed from `pinnedPhotoIds` → reopening sheet for P shows enabled pin button.
  - Process death + restore: button state correctly reflects post-restore repository state (no stale-latch bugs).
- [ ] "Open in Gallery" launches `Intent.ACTION_VIEW` with `FLAG_GRANT_READ_URI_PERMISSION` AND uri string equals the photo's content URI — `FakeIntentLauncher` captures intent's action + data + flags.
- [ ] "Open in Gallery" with no resolving activity → snackbar shown via try/catch on `ActivityNotFoundException` — verify with stub `Activity.startActivity` that throws.
- [ ] BackHandler triggers same dismiss path as drag-down — Compose test.

### Quality gates (apply to all four)

- [ ] All existing tests pass.
- [ ] **Every acceptance-criteria bullet in sections A–D has at least one corresponding test at the appropriate layer** (unit for pure functions + algorithm; Robolectric for Android-platform APIs like Lifecycle, Intent, Settings; Compose for UI assertions). The bullet-to-test mapping is the gate; AC bullets do NOT need coverage at all three layers.
- [ ] **Existing grid Compose tests** in `app/src/test/java/.../ui/walk/reliquary/` are migrated to `PhotoCarousel` tests (rename + adapt to LazyRow assertions). Obsolete grid-only behavior tests (3-column layout, grid-tile sizing) are deleted with explanation in the commit message.
- [ ] **AudioPlayer Singleton divergence documented inline** at `ExoPlayerVoicePlaybackController.kt` class KDoc — explaining the iOS per-view vs Android `@Singleton` choice + Stage 2-D pattern rationale.
- [ ] Device QA on OnePlus 13 — manual smoke per surface (test plan in PR body).
- [ ] No new lint warnings (`./gradlew :app:lintDebug`).
- [ ] No new OutRun references, SPDX headers on every new file (`// SPDX-License-Identifier: GPL-3.0-or-later`).
- [ ] PR squash-merges to main as one commit per project convention.

## Locked decisions (resolved before plan)

- **D1: Carousel REPLACES the existing 3-column grid.** iOS only has the carousel; the Android grid was a Stage 7-A scope decision. Single layout = simpler review + test surface + no Settings toggle. Existing grid Compose tests migrate to carousel tests; obsolete grid-only behavior tests get deleted.
- **D2: Preview-sheet uses `Dialog(usePlatformDefaultWidth = false)`** — true full-screen matching iOS `.fullScreenCover`. Stage 13-XZ already documented the IME-padding pattern (`Modifier.imePadding()` on inner content); reuse here.
- **D3: AudioPlayer stays as `@Singleton ExoPlayerVoicePlaybackController`** (Android divergence from iOS per-view pattern). Survives nav stack changes per Stage 2-D pattern. Inline-documented at the class KDoc per the AudioPlayer-divergence quality gate above.
- **D4: Drop the `hasCommitted` latch entirely.** Pin-button enabled state is **derived from repository state**, not a separate latch:
  - Pin button **enabled** when the carousel-selected photo is NOT in the walk's `WalkPhoto` set.
  - Pin button **disabled** when the photo IS in the set (already pinned).
  - The existing `pinnedPhotos: StateFlow<List<WalkPhoto>>` in `WalkSummaryViewModel.kt:508-541` is the source of truth; the preview-sheet observes it and derives the button state.
  - Unpin via the carousel badge tap removes from the set → on next sheet open, button is re-enabled. No "latch" lives outside the repository.
  - **Rapid-double-tap defense**: an in-flight Mutex in the VM serializes `pinPhotos` calls (mirroring the Stage 7-A pattern already shipped at `WalkSummaryViewModel.kt:665-672`). The button is also disabled while the in-flight `_pinningInFlight: StateFlow<Boolean>` is true.
  
  Drops the entire `rememberSaveable(photoId)` latch + its process-death edge cases. Repository state is durable; deriving from it is simpler + correct under all reopen/back-nav/process-kill paths.
- **D5: Skeleton 300ms cancellation semantics.** The 300ms `delay` completes regardless of state transitions; the double-check `(state == Loading && candidates.isEmpty())` evaluated AFTER the delay decides whether to show the skeleton. Permission-revoke mid-delay → delay completes → double-check returns false (state is now `PermissionDenied`) → no skeleton flash. Simpler than explicit `Job.cancel()`; one fewer race surface.
- **D6: ReliquaryState sealed class shape + ON_START re-fetch contract.**
  - States: `ToggleOff`, `PermissionDenied`, `Loading`, `Populated(candidates: List<PhotoCandidate>)` (candidates ride INSIDE the state value).
  - ON_START re-fetch is gated on a **permission-state transition**, NOT raw lifecycle resume — otherwise an empty walk would infinite-re-fetch MediaStore on every backgrounding.
  - VM holds `previousPermissionGranted: Boolean` snapshot. ON_START handler reads current permission, compares to snapshot:
    - `previousPermissionGranted == false && currentPermissionGranted == true` → trigger fetch (user re-granted from Settings).
    - `previousPermissionGranted == true && currentPermissionGranted == false` → transition to `PermissionDenied` (user revoked; no fetch needed).
    - Otherwise (no permission change) → no-op.
  - The snapshot updates AFTER the comparison so subsequent ON_STARTs use the new baseline.
  - An empty-Populated walk does NOT re-fetch on ON_START. Re-fetch only happens via the carousel's pull-to-refresh affordance (Phase N, out of scope here) or explicit user toggle-off-then-on of the Reliquary preference.
- **D7: `BackHandler` is permanently `enabled = true`** but the dismiss path is idempotent — re-entry during the dismiss-spring animation is a no-op (`if (alreadyDismissing) return`). No need to disable the handler during animation.

## Non-blocking open questions

None remain — all prior open questions resolved as locked decisions D1–D7.

## Risks

- **Stage 13-XZ bundle precedent.** A 9.8K-LOC bundle needed 8 review cycles + 17 fixes. This bundle is roughly 5–15% that size — the `~80/250/200/350` per-sub-slice estimates are eyeballed from iOS file LOC + estimated Android delta (could land anywhere in `±50%` per-stage, so total bundle `~600–1400 LOC` production code). Expect 3–7 review cycles given the wide-ish LOC band. **Mitigation:** per-surface subagent dispatches during implementation + run `jutsu swarm pr-review --full --strict --personas claim-auditor-claude,cross-file-gemini,claim-auditor-deepseek` BEFORE merge. PR #89's swarm caught a real rotation-replay bug that 3 in-process review tiers missed — the swarm cost ($0.22) pays for itself.
- **Stage 7-A test-leak pattern (3rd time logged in memory).** VM tests with `WhileSubscribed`/`Eagerly` Room flows leak unless `viewModelScope.cancel()` precedes `db.close()` in tearDown. **Audit:** every new VM test added by this bundle against the pattern.
- **Photos permission cliff.** Android 13+ uses `READ_MEDIA_IMAGES`; 14+ adds `READ_MEDIA_VISUAL_USER_SELECTED` (partial grant). Per non-goal D above, partial-grant is treated as full-grant for this bundle. Only **full deny** triggers `PermissionDenied`. **Mitigation:** unit-test full-deny + grant paths via `ShadowApplication.grantPermissions` Robolectric helpers; partial-grant test deferred to the Phase N "Manage" affordance work.
- **Compose stability cascade.** New carousel + preview-sheet state classes (`CarouselActivationState`, `PreviewSheetState`, `ReliquaryState` sealed hierarchy) need `@Immutable` annotation per Stage 4-C / 4-D lesson — Compose can't infer stability across module boundaries. **Audit:** every new data class fed into LazyList items AND every TRANSITIVE field's declared type per Stage 4-D `WalkSummary`→`Walk` cascade (`@Immutable` on the outer class does NOT mark inner fields stable; each cross-module type must be stable on its own).
- **Spring animation cost on low-end devices.** Carousel scale spring + preview drag spring + image loading stack. Profile on OnePlus 13 baseline; if frame drops, scope down spring stiffness or drop scale animation. Stage 5-A `Modifier.alpha(value)` perf cliff is explicitly listed in Non-goals — a separate polish PR will migrate to `Modifier.graphicsLayer { alpha = ... }` lambda form.
- **Bundle scope creep.** Tempting to fold in P1 milestone fix or token cleanup. Hold the line on non-goals. Bundle ships when the 4 surfaces match acceptance criteria, not when the whole scene is "perfect."
- **Carousel-replaces-grid migration risk.** Existing pinning UX from the grid (long-press → confirm dialog) must be preserved or improved through the carousel + preview-sheet chain. Don't ship the carousel without the preview-sheet (preview-sheet is the new pin/unpin surface; without it users can't manage pins).
- **`RecordingRow.kt` Android-side anchor at `@ce11a87`.** A.2 cites this Android HEAD codepoint as the source of an existing expand/collapse impl (for the standalone Recordings List screen) that gets backported to Walk Summary. This is an Android-side anchor (NOT iOS parity drift). The 280/7 threshold itself sources from iOS `VoiceRecordingRow.swift:27-36@db4196e`. The Android codepoint is cited for backport implementation reference, not as a parity contract.

## Architecture sketch

```
WalkSummaryScreen.kt (existing)
├── PhotoReliquarySection.kt (modified — adds 4-state gate + lifecycle observer + fetch gen)
│   ├── PhotoCarousel.kt (NEW — replaces grid)
│   │   ├── PhotoThumbnail.kt (NEW — 88dp tile with badge + active state)
│   │   └── PhotoCarouselActivationState (NEW — @Immutable state holder)
│   └── PhotoReliquaryDeferredSkeleton.kt (NEW — 300ms-deferred shimmer)
└── PhotoPreviewSheet.kt (NEW — full-screen Dialog with drag-dismiss)
    └── PhotoPreviewState (NEW — @Immutable, includes hasCommitted)

VoiceRecordingsSection.kt (modified)
└── TranscriptionView.kt (modified — adds expand threshold)

ExoPlayerVoicePlaybackController.kt (modified — speed-cycle alignment)
```

New files: ~6. Modified files: ~4. Net ~880 LOC production code; test LOC determined by the plan, not pre-budgeted here.

## Implementation phasing (suggested)

Sub-divide work into 4 ordered stages so the implementation plan + subagent dispatches stay sane:

1. **Stage 1 — Voice-row polish (A).** Cheapest, lowest risk, validates the bundle's per-surface review pattern. ~80 LOC.
2. **Stage 2 — Reliquary state polish (B).** Foundation for the carousel; the 4-state gate + lifecycle observer + fetch gen are reused by the carousel. ~250 LOC.
3. **Stage 3 — Carousel (C).** Replaces grid, slots into PhotoReliquarySection's populated-state branch. Depends on Stage 2. ~200 LOC.
4. **Stage 4 — Preview-sheet (D).** The pin/unpin surface for the carousel. Depends on Stage 3 (tap → open). ~350 LOC.

Each stage = one logical commit (or 2-3 commits if subagent-driven-development splits per task). **All four squash into one PR at merge** — the 4-stage structure exists only on the feature branch; reviewers on `main` see a single squash commit. Call the per-stage structure out in the PR body so reviewers know where to look for staged commits while the branch is open.

## Downstream handoff

After this spec passes doc-review:

1. Invoke `superpowers:writing-plans <this-spec>` to produce the implementation plan.
2. Implementation via `superpowers:subagent-driven-development` (per-task subagent dispatch with 2-stage review) OR manual.
3. Pre-merge: `jutsu swarm pr-review --pr N --full --strict --personas claim-auditor-claude,cross-file-gemini,claim-auditor-deepseek`.
4. Manual device QA on OnePlus 13 — explicit test plan per surface in PR body.
5. Squash-merge after user "merge" instruction (per `feedback_no_auto_merge_prs.md`).
