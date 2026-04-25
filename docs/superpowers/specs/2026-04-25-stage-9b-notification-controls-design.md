# Stage 9-B: Notification Media-Style Controls — Design

**Date:** 2026-04-25
**Stage:** 9-B (Phase 9 second half — closes Phase 9)
**Reference:** Port plan: "Foreground-service notification upgrade: media-style controls (pause/resume/stop/mark-waypoint). Success criteria: notification controls work from lock screen."

## Recommended approach

Extend the existing `WalkTrackingService`'s ongoing notification with state-aware action buttons (Pause | Mark Waypoint | Finish in Active; Resume | Mark Waypoint | Finish in Paused; End Meditation | Finish in Meditating) plus a tap-body deep-link to the `ACTIVE_WALK` route. Action delivery via Service-direct `Intent` (no `BroadcastReceiver` — avoids the Stage 9-A implicit-broadcast trap and stays in-process). Wire the missing `WalkController.recordWaypoint()` method that synthesizes a `Waypoint` from the current `WalkAccumulator.lastLocation` + `walkId` + `Clock.now()`. Add `DeepLinkTarget.ActiveWalk` (sealed-type variant) and update `PilgrimNavHost`'s deep-link gate to allow ACTIVE_WALK as a target when the source is the notification (always — the user explicitly tapped to return to their walk). Lock-screen visibility via `setVisibility(VISIBILITY_PUBLIC)`. Notification rebuilds on every `WalkController.state` emission so action set always reflects current state.

## Why this approach

- **Plain `NotificationCompat.Builder.addAction(...)` is the canonical Android pattern for session-control notifications** (Strava, Google Maps Drive, Spotify use it; MediaStyle is for actual media playback). System-default vertical layout with 3 action buttons stays Material You-themed and accessible without custom RemoteViews maintenance. iOS Live Activities / Dynamic Island deferred per port plan; we accept the loss.
- **Service-direct `Intent` action delivery** (`PendingIntent.getService(...)`) over `BroadcastReceiver` because: (a) `WalkTrackingService` is already running when actions are tapped (notification only exists during the foreground service); (b) avoids the API-26+ implicit-broadcast filter trap (Stage 9-A lesson); (c) one fewer manifest entry; (d) actions land in the same coroutine scope that owns the controller, no IPC hop.
- **Wiring `WalkController.recordWaypoint()` is unambiguous scope expansion but trivial** — `WalkRepository.addWaypoint` and the `Waypoint` entity already exist in the data layer. Synthesizing from `WalkAccumulator.lastLocation` + `Clock.now()` is ~10 lines. Skip silently if state is Finished/Idle or `lastLocation == null` (no GPS fix yet). Defensive: a notification button MUST be safe to tap in any state without crashing the app.
- **`DeepLinkTarget.ActiveWalk` reuses Stage 9-A's plumbing**. Stage 9-A's NavHost deep-link gate explicitly blocked ACTIVE_WALK to prevent widget taps from disrupting in-progress walks. Notification taps are the opposite intent: the user IS in the walk and wants to return. Solve by introducing the ActiveWalk variant that NavHost handles unconditionally (no source-based gating — the deep-link target IS the destination, no surprise navigation).
- **Single global state-driven rebuild** matches the existing service shape — every `controller.state` emission already triggers `updateNotification(state)`. Adding actions just means each rebuild includes the per-state action set. No new observer scaffolding.

## What was considered and rejected

- **MediaStyle + custom RemoteViews**: ~40 LOC XML + maintenance for marginal aesthetic gain; fights Material You theming; mismatched semantics (we're not playing media).
- **Drop waypoint from the notification**: would skip a port-plan-listed control and require a separate stage to wire it later. Wiring it now adds ~30 LOC.
- **BroadcastReceiver for action delivery**: extra registration; risks the implicit-broadcast trap if any of our actions ever overlap with a system action; no advantage over Service-direct intents for an in-process flow.
- **Adding waypoint UI button to ActiveWalkScreen in this stage**: explicit scope expansion. Defer to Phase 10 polish or a future stage. The notification action covers the use case for now.
- **Periodic notification updates beyond state changes** (e.g., refresh-every-second clock display): the existing `controller.state.collect { ... updateNotification(state) }` already fires on tick (the controller emits Active(walk) updates as accumulator changes). No change needed.
- **Confirmation dialog for Finish action**: tap protection is unnecessary on a foreground notification — tapping requires deliberate gesture (expand notification, tap action). Mis-tap recovery: the WalkSummary screen post-finish offers full review; a Stage 4-D milestone goes through finish anyway.

## Layer breakdown

### 1. Service-direct action intents

- `WalkTrackingService` companion: add ACTION_PAUSE, ACTION_RESUME, ACTION_END_MEDITATION, ACTION_MARK_WAYPOINT, ACTION_FINISH constants. Plus `pausePendingIntent(context)` etc. factories that build `PendingIntent.getService(context, requestCode, intent, FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT)` with unique `requestCode` per action (matches existing contentIntent pattern; UPDATE_CURRENT prevents stale-extra reuse if any extras are added later).
- **PendingIntent caching**: build the four action PendingIntents once in `onCreate` (or first promoteToForeground) as fields. `buildNotification(state)` references the cached intents instead of re-allocating per state emission. With ~1Hz state emissions over a 90-min walk, naive per-rebuild allocation = ~27,000 PendingIntent binder transactions; caching reduces to 4 per process lifetime.
- `onStartCommand` extends the existing `when (intent?.action)` branch:
  - **ACTION_START**: existing `startTracking()`.
  - **Action intents (ACTION_PAUSE etc.)**: defensive — if `locationJob?.isActive != true` (service was revived from a stale notification AFTER the walk ended), `stopSelf()` and bail. Otherwise wrap each in `scope.launch { runCatching { controller.<verb>() }.onFailure { ce -> if (ce is CancellationException) throw ce; Log.w(TAG, ...) } }` so a state-machine rejection (e.g., Pause from Idle if the controller hasn't received Start yet, or transient WalkReducer no-ops) doesn't crash the service scope. The supervisor parent kept siblings alive but the inner-launch lambda needs explicit catch so location/notification jobs survive.
- **Foreground-service revival risk**: API 31+ kills services started without `startForeground()` within 5s. If an ACTION_PAUSE intent reaches a fresh service (notification persisted past `stopSelf`), the new instance has no `locationJob` and bails immediately via the defensive guard above — `stopSelf()` returns before the 5s window expires. No `ForegroundServiceDidNotStartInTimeException`.
- **Re-entrancy during startTracking**: the brief window between `promoteToForeground(starting)` and `notificationJob`'s first state collection is sub-second. If a user spam-taps an action in that window, the action lands when controller state is `Idle` (the VM hasn't called controller.startWalk yet either). The runCatching above absorbs any `Pause from Idle` reducer rejection cleanly.

### 2. WalkController.recordWaypoint

- New `suspend fun recordWaypoint()` on `WalkController`. Acquires `dispatchMutex.withLock { ... }` (the existing controller-level mutex that serializes state-machine dispatches). Inside the lock: reads `state.value`, extracts `walk: WalkAccumulator` if state is Active/Paused/Meditating (return early otherwise — accept all three; **see iOS-divergence note below for Meditating rationale**), reads `walk.lastLocation` (return early if null), constructs `Waypoint(walkId = walk.walkId, timestamp = clock.now(), latitude = lastLocation.latitude, longitude = lastLocation.longitude, label = null)`, calls `repository.addWaypoint(waypoint)` (still inside the lock so a concurrent `finishWalk()` can't interleave and create a waypoint timestamp > walk.endTimestamp).
- CE re-throw discipline; non-CE failures logged + swallowed (waypoint is best-effort; must not crash the walk).
- **Meditating-state policy**: the controller method explicitly accepts Meditating. The notification UI hides the button (meditation is a stationary mode; user shouldn't expect to tag points). But the controller method allows the call so a future in-app waypoint button (Phase 10) can call it from any non-finished state without the controller second-guessing the UI.
- **No VM passthrough yet** — the only consumer is the Service-direct intent path. A `WalkViewModel.recordWaypoint()` would be dead code per YAGNI; defer to Phase 10 when the in-app button lands.

### 3. Notification builder rewrite

- `WalkTrackingService.buildNotification(state: WalkState): Notification` (signature change from `text: String` to full state — formatter inline).
- Per-state content text + action set:
  - `WalkState.Active` → distance-based content text (existing `walk_notification_active` string) + actions [Pause, Mark Waypoint, Finish].
  - `WalkState.Paused` → existing `walk_notification_paused` + actions [Resume, Mark Waypoint, Finish].
  - `WalkState.Meditating` → existing `walk_notification_meditating` + actions [End Meditation, Finish]. (Waypoint UI deliberately omitted: meditation is a stationary mode. Controller method still allows it for Phase 10's in-app button.)
  - `WalkState.Idle` → existing `walk_notification_idle` + no actions. (Defensive — should never render; service stops on Finished and ACTION_START enters Active immediately.)
  - `WalkState.Finished` → existing `walk_notification_finished` + no actions. (Defensive — visible only in the brief stopSelf-pending window.)
- Builder additions:
  - `setShowWhen(false)` — timestamp irrelevant for a live ongoing notification.
  - `setOnlyAlertOnce(true)` — defense-in-depth (channel is LOW so no alerts anyway).
  - `setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)` — API 31+ otherwise delays the notification up to 10s, leaving the user without lock-screen actions during that window. Critical for "controls work from lock screen" success criterion.
  - **Lock-screen visibility split**: `setVisibility(NotificationCompat.VISIBILITY_PRIVATE)` on the main notification (full distance + state visible only after unlock). `setPublicVersion(buildLockScreenVersion(state))` provides a redacted lock-screen variant: same icon + title ("Pilgrim — Walk in progress") + the action buttons but NO distance / state text. This balances the "actions on lock screen" success criterion with the privacy concern of exposing walking habits + distance to a casual onlooker.
  - `setCategory(NotificationCompat.CATEGORY_SERVICE)` — preserved from existing builder.
- Action icons: bundle 5 monochrome 24dp vector drawables in `app/src/main/res/drawable/` for play, pause, stop, waypoint, meditation-end. Match the parchment/ink palette. Avoid `android.R.drawable.ic_media_*` family — vendors (Samsung One UI, MIUI) replace those inconsistently.
- Tap target (contentIntent): MainActivity intent with `EXTRA_DEEP_LINK = DEEP_LINK_ACTIVE_WALK`. Reuses Stage 9-A's onNewIntent pipeline.

### 4. DeepLinkTarget.ActiveWalk + NavHost wiring

- Append `data object ActiveWalk : DeepLinkTarget` to the sealed type + `DEEP_LINK_ACTIVE_WALK = "active_walk"` constant.
- `parse(intent)`: add the new branch.
- **PilgrimNavHost LaunchedEffect refactor** (this is more invasive than "just add a branch" — restructure required):
  - Current shape: PERMISSIONS early-return → isActiveSession early-return (drops link) → `when (link)` for WalkSummary / Home.
  - New shape: PERMISSIONS early-return → **`when (link)` FIRST** (with ActiveWalk handled before any source-route gating, since the target IS an active-session route — there's nothing to "disrupt") → THEN isActiveSession early-return for non-ActiveWalk targets (preserves Stage 9-A's widget-protection guarantee).
  - ActiveWalk branch logic:
    - From any non-PERMISSIONS route (including currently-on-ACTIVE_WALK or currently-on-MEDITATION): navigate to `Routes.ACTIVE_WALK` with `launchSingleTop = true` + `popUpTo(Routes.HOME) { saveState = false }`. The popUpTo collapses any intermediate destinations (e.g., journal scroll → Settings → ACTIVE_WALK becomes [HOME, ACTIVE_WALK]).
    - launchSingleTop ensures currently-on-ACTIVE_WALK is a no-op (re-uses the existing destination instance).
    - **MEDITATION-source decision**: navigate to ACTIVE_WALK and back the user out of meditation. Rationale: notification body tap is an explicit "show me my walk view" gesture; if the user wanted to stay in meditation they wouldn't have tapped. The alternative (do-nothing-when-already-in-session) was Stage 9-A's pattern for widget taps, but notification taps are different intent.
    - Always call `onDeepLinkConsumed()` after the navigate.
  - WalkSummary / Home branches keep existing behavior (gated by isActiveSession early-return).

### 5. Strings

- Append: `walk_notification_action_pause`, `walk_notification_action_resume`, `walk_notification_action_finish`, `walk_notification_action_mark_waypoint`, `walk_notification_action_end_meditation`. Short imperative verbs (≤14 chars, system action button width).
- Add: `walk_notification_lock_screen_title` ("Walk in progress") for the lock-screen-redacted public version.
- **iOS-divergence verification needed**: previous draft claimed iOS Live Activity has separate "End Walk" + "Finish Walk" semantics. Verify against `pilgrim-ios` source during UNDERSTAND of any future related stage; if no separate semantics exist there, drop the comment. For now, Android uses single "Finish" since `controller.finishWalk()` is the only end-state.

## Scope decisions

- **No in-app waypoint button in this stage.** Wiring `recordWaypoint()` end-to-end means the data flow is complete; adding the in-app UI button is a polish item for Phase 10. Notification button covers the use case.
- **No confirmation dialog on Finish.** Notification action requires deliberate gesture (expand + tap); mis-tap recovery is the Walk Summary screen.
- **No notification update on every Tick beyond state changes.** Distance updates already arrive via state emissions on every LocationSampled; that's frequent enough.
- **No PendingIntent on the notification's `setDeleteIntent`** — service is dismissable only by completing the walk; system handles reverence to ongoing notifications.
- **No notification preview / mockup screenshot in this spec** — system-default styling renders consistently, device QA covers visual.

## Out of scope

- Live Activity / Dynamic Island parity (iOS-only).
- Wear OS quick-actions tile.
- Voice-record button on notification (Stage 9-C candidate if user wants).
- In-app waypoint UI button (Phase 10).
- Custom RemoteViews / branded notification skin.
- Battery saver / Doze special-case beyond what foreground service already handles.
- Migration of existing notification observers — the rewrite is additive (action additions + visibility + signature change to `buildNotification(state)`).

## Quality considerations

- **CE re-throw** in `WalkController.recordWaypoint`'s catch (Throwable) block AND in each Service-direct action's `scope.launch { runCatching { ... }.onFailure { ce -> if (ce is CancellationException) throw ce; ... } }` (Stage 8-A audit rule).
- **PendingIntent.FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT** on every action PendingIntent (matches existing contentIntent pattern). UPDATE_CURRENT prevents stale-extra reuse if any extras are added later.
- **Unique `requestCode` per action** so PendingIntent caching doesn't collapse them into a single intent (would route Pause to Resume, etc.).
- **PendingIntent caching in onCreate** (or first promoteToForeground), referenced from buildNotification — avoids ~27,000 binder transactions over a 90-min walk.
- **`dispatchMutex.withLock` around the whole `recordWaypoint` body** — prevents the race where a concurrent finishWalk() interleaves and produces a waypoint with timestamp > walk.endTimestamp.
- **Defensive action revival guard**: every action branch in `onStartCommand` checks `if (locationJob?.isActive != true) { stopSelf(); return }` before dispatching. Without this, an action intent reaching a fresh service instance (notification persisted past stopSelf) would skip startForeground and trip the API 31+ ForegroundServiceDidNotStartInTimeException.
- **Lock-screen visibility split**: `VISIBILITY_PRIVATE` on the main notification + `setPublicVersion(buildLockScreenVersion(state))` for a redacted lock-screen variant. Distance + state are visible only after unlock; lock-screen shows generic title + actions only.
- **`setForegroundServiceBehavior(FOREGROUND_SERVICE_IMMEDIATE)`** to skip the 10s API 31+ display delay — critical for "controls work from lock screen" success criterion.
- **No `viewModelScope.launch + Robolectric runBlocking deadlock`** patterns in tests — service tests instantiate the service directly with fakes for controller + locationSource; notification builder tested by reading `Notification.actions` array length + extras. Stage 2-F lesson generalized: at least one Robolectric test must call `buildNotification(state).actions.size` to exercise the builder path.
- **Action icons**: bundle 5 monochrome 24dp vector drawables (play, pause, stop, waypoint, meditation-end) matching the parchment/ink palette. Do NOT use `android.R.drawable.ic_media_*` family — Samsung One UI / MIUI replace those inconsistently.
- **Stage 9-A test fragility lesson**: VM-launch tests with Robolectric are brittle. New tests for `WalkController.recordWaypoint` test the controller directly with fakes; notification builder tests are pure JVM (no service lifecycle).
- **`Intent.parse(intent)` defensive on null/missing extras** — already present in DeepLinkTarget; extends naturally to ActiveWalk variant (no extras needed beyond the deep-link tag).

## Success criteria

1. User starts a walk → notification appears with 3 buttons: Pause, Mark Waypoint, Finish.
2. Tap Pause → state transitions to Paused; notification rebuilds with Resume, Mark Waypoint, Finish.
3. Tap Resume → state transitions back to Active; notification updates accordingly.
4. Tap Mark Waypoint while Active or Paused → a `Waypoint` row is inserted in Room with current `lastLocation` + walkId + timestamp. Notification stays visible.
5. From Active → meditation flow → notification shows End Meditation + Finish only (no Pause / Mark Waypoint).
6. Tap Finish from any non-Idle state → controller transitions to Finished, service stops, notification disappears.
7. Tap notification body → MainActivity opens (or re-enters via singleTop) → NavHost navigates to ACTIVE_WALK regardless of starting route (HOME, SETTINGS, journal scroll, etc.).
8. Notification controls work from the lock screen (system-default lock-screen visibility per `VISIBILITY_PUBLIC`).
9. Action taps require deliberate gesture (lock screen → expand → tap, or notification shade → tap action). No accidental tap from notification dismiss.
10. Existing tests continue passing; new tests cover: notification action set per state, recordWaypoint inserts a Waypoint with correct fields, ActiveWalk deep-link parse + nav.

## Files

**New (5 production + 2 test):**

Production:
- `app/src/main/res/drawable/ic_notification_pause.xml` (24dp monochrome vector)
- `app/src/main/res/drawable/ic_notification_resume.xml`
- `app/src/main/res/drawable/ic_notification_stop.xml`
- `app/src/main/res/drawable/ic_notification_waypoint.xml`
- `app/src/main/res/drawable/ic_notification_end_meditation.xml`

Test:
- `app/src/test/java/org/walktalkmeditate/pilgrim/walk/WalkControllerWaypointTest.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/service/WalkTrackingServiceNotificationTest.kt` (tests builder + action factory)

**Modified:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/widget/DeepLinkTarget.kt` (ActiveWalk variant + parse case)
- `app/src/main/java/org/walktalkmeditate/pilgrim/walk/WalkController.kt` (recordWaypoint suspend method, dispatchMutex-locked)
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHost.kt` (ActiveWalk deep-link branch firing BEFORE isActiveSession early-return; popUpTo(HOME) clamp)
- `app/src/main/java/org/walktalkmeditate/pilgrim/service/WalkTrackingService.kt` (action constants + cached PendingIntents in onCreate + defensive revival guard + onStartCommand action branches with runCatching/CE rethrow + buildNotification(state) with per-state action set + lock-screen visibility split + setForegroundServiceBehavior + tap-body deep-link)
- `app/src/main/res/values/strings.xml` (5 action labels + 1 lock-screen title)
- `app/src/test/java/org/walktalkmeditate/pilgrim/widget/DeepLinkTargetTest.kt` (ActiveWalk parse case)

**NOT modified (per YAGNI):**
- `WalkViewModel.kt` — no in-app waypoint button consumer in this stage; defer VM passthrough to Phase 10.
- `WalkViewModelTest.kt` — no new test surface in WalkViewModel.
