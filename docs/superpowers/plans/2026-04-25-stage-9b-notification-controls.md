# Stage 9-B Implementation Plan — Notification Media-Style Controls

Spec: `docs/superpowers/specs/2026-04-25-stage-9b-notification-controls-design.md`

Sequence: Task 1 → 9. Each task ends compiling + passing tests. Branch already created: `feat/stage-9b-notification-controls` (off `origin/main`).

---

## Task 1 — `DeepLinkTarget.ActiveWalk` variant + parse + test

Pure type addition. Foundation for every other deep-link-aware path.

### Files

**Modify** `app/src/main/java/org/walktalkmeditate/pilgrim/widget/DeepLinkTarget.kt`:

Add `ActiveWalk` data object next to `Home`. Add `DEEP_LINK_ACTIVE_WALK = "active_walk"` constant in companion. Add `DEEP_LINK_ACTIVE_WALK -> ActiveWalk` branch in `parse(intent)` after the existing branches.

**Modify** `app/src/test/java/org/walktalkmeditate/pilgrim/widget/DeepLinkTargetTest.kt`:

Add test: `parse returns ActiveWalk for active_walk deep link`.

### Verify
```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests "*.DeepLinkTargetTest"
```

---

## Task 2 — `WalkController.recordWaypoint()` + test

The missing controller method. Pure-data: no service dependency, no notification dependency. Test in isolation.

### Files

**Modify** `app/src/main/java/org/walktalkmeditate/pilgrim/walk/WalkController.kt`:

Add `suspend fun recordWaypoint()`:
```kotlin
suspend fun recordWaypoint() {
    dispatchMutex.withLock {
        val accumulator = when (val s = _state.value) {
            is WalkState.Active -> s.walk
            is WalkState.Paused -> s.walk
            is WalkState.Meditating -> s.walk
            else -> return@withLock // Idle / Finished — no walk in progress.
        }
        val location = accumulator.lastLocation ?: return@withLock // No GPS fix yet.
        try {
            repository.addWaypoint(
                Waypoint(
                    walkId = accumulator.walkId,
                    timestamp = clock.now(),
                    latitude = location.latitude,
                    longitude = location.longitude,
                    label = null,
                ),
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "recordWaypoint failed", t)
        }
    }
}
```

Add `Waypoint` import + `Log` import + `CancellationException` import. The `dispatchMutex` field already exists; `clock` is already injected; `repository` is already injected. `_state` is the existing private MutableStateFlow.

**Create** `app/src/test/java/org/walktalkmeditate/pilgrim/walk/WalkControllerWaypointTest.kt`:

JUnit + Robolectric (in-memory Room) tests:
- `recordWaypoint from Idle is a no-op` — no Waypoint row inserted.
- `recordWaypoint from Active inserts a Waypoint with current lastLocation` — start walk, inject location, call recordWaypoint, query waypointsFor(walkId).
- `recordWaypoint from Paused inserts a Waypoint`.
- `recordWaypoint from Meditating inserts a Waypoint` (controller allows it; UI hides button).
- `recordWaypoint with no GPS fix yet is a no-op` — start walk, immediately recordWaypoint (no location injected), verify zero waypoints.
- `recordWaypoint from Finished is a no-op`.

### Verify
```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests "*.WalkControllerWaypointTest"
```

---

## Task 3 — Notification action drawables (5 vectors)

Resources only — no Kotlin. Bundle 24dp monochrome vectors matching the parchment/ink palette.

### Files

**Create** `app/src/main/res/drawable/ic_notification_pause.xml` — twin vertical bars (Material's `pause` icon path, 24dp viewport, single path with `?attr/colorControlNormal` tint or `@android:color/white` for notification compatibility).

**Create** `app/src/main/res/drawable/ic_notification_resume.xml` — right-pointing triangle (Material's `play_arrow`).

**Create** `app/src/main/res/drawable/ic_notification_stop.xml` — square (Material's `stop`).

**Create** `app/src/main/res/drawable/ic_notification_waypoint.xml` — pin / location-marker shape (Material's `location_on` or `place`).

**Create** `app/src/main/res/drawable/ic_notification_end_meditation.xml` — circle with center dot, OR Material's `self_improvement` simplified.

All five: 24dp x 24dp viewport, monochrome (single path), tintable with `app:tint="@android:color/white"` so the notification framework renders them correctly on any Android theme.

### Verify
```bash
./gradlew --no-daemon :app:assembleDebug
```

---

## Task 4 — Strings (action labels + lock-screen title)

### Files

**Modify** `app/src/main/res/values/strings.xml` — append:
```xml
<!-- Stage 9-B: notification action labels (≤14 chars each) -->
<string name="walk_notification_action_pause">Pause</string>
<string name="walk_notification_action_resume">Resume</string>
<string name="walk_notification_action_finish">Finish</string>
<string name="walk_notification_action_mark_waypoint">Waypoint</string>
<string name="walk_notification_action_end_meditation">End</string>
<string name="walk_notification_lock_screen_title">Walk in progress</string>
```

### Verify
```bash
./gradlew --no-daemon :app:assembleDebug
```

---

## Task 5 — `WalkTrackingService` action constants + cached PendingIntents

Add the action surface but don't wire to notification yet (Task 6 does that).

### Files

**Modify** `app/src/main/java/org/walktalkmeditate/pilgrim/service/WalkTrackingService.kt`:

In the `companion object`, add:
```kotlin
const val ACTION_PAUSE = "org.walktalkmeditate.pilgrim.service.WalkTrackingService.PAUSE"
const val ACTION_RESUME = "org.walktalkmeditate.pilgrim.service.WalkTrackingService.RESUME"
const val ACTION_END_MEDITATION = "org.walktalkmeditate.pilgrim.service.WalkTrackingService.END_MEDITATION"
const val ACTION_MARK_WAYPOINT = "org.walktalkmeditate.pilgrim.service.WalkTrackingService.MARK_WAYPOINT"
const val ACTION_FINISH = "org.walktalkmeditate.pilgrim.service.WalkTrackingService.FINISH"
private const val REQUEST_CODE_CONTENT = 0
private const val REQUEST_CODE_PAUSE = 1
private const val REQUEST_CODE_RESUME = 2
private const val REQUEST_CODE_END_MEDITATION = 3
private const val REQUEST_CODE_MARK_WAYPOINT = 4
private const val REQUEST_CODE_FINISH = 5
```

Add a private extension that builds an action `PendingIntent`:
```kotlin
private fun actionPendingIntent(action: String, requestCode: Int): PendingIntent {
    val intent = Intent(this, WalkTrackingService::class.java).apply { this.action = action }
    return PendingIntent.getService(
        this,
        requestCode,
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
```

Cache the PendingIntents as private lateinit-var fields, initialized in `onCreate` after `createNotificationChannel`:
```kotlin
private lateinit var pausePendingIntent: PendingIntent
private lateinit var resumePendingIntent: PendingIntent
private lateinit var endMeditationPendingIntent: PendingIntent
private lateinit var markWaypointPendingIntent: PendingIntent
private lateinit var finishPendingIntent: PendingIntent

override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
    pausePendingIntent = actionPendingIntent(ACTION_PAUSE, REQUEST_CODE_PAUSE)
    resumePendingIntent = actionPendingIntent(ACTION_RESUME, REQUEST_CODE_RESUME)
    endMeditationPendingIntent = actionPendingIntent(ACTION_END_MEDITATION, REQUEST_CODE_END_MEDITATION)
    markWaypointPendingIntent = actionPendingIntent(ACTION_MARK_WAYPOINT, REQUEST_CODE_MARK_WAYPOINT)
    finishPendingIntent = actionPendingIntent(ACTION_FINISH, REQUEST_CODE_FINISH)
}
```

### Verify
```bash
./gradlew --no-daemon :app:compileDebugKotlin
```

---

## Task 6 — `buildNotification(state)` rewrite + lock-screen split + deep-link tap target

### Files

**Modify** `app/src/main/java/org/walktalkmeditate/pilgrim/service/WalkTrackingService.kt`:

Replace the existing `buildNotification(text: String)` and `updateNotification(state)` with:

```kotlin
private fun buildNotification(state: WalkState): Notification {
    val activityIntent = Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra(DeepLinkTarget.EXTRA_DEEP_LINK, DeepLinkTarget.DEEP_LINK_ACTIVE_WALK)
    }
    val contentPending = PendingIntent.getActivity(
        this,
        REQUEST_CODE_CONTENT,
        activityIntent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    val builder = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(notificationText(state))
        .setOngoing(true)
        .setShowWhen(false)
        .setOnlyAlertOnce(true)
        .setContentIntent(contentPending)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setPublicVersion(buildLockScreenNotification(state))
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

    addActionsForState(builder, state)
    return builder.build()
}

private fun buildLockScreenNotification(state: WalkState): Notification {
    val builder = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle(getString(R.string.walk_notification_lock_screen_title))
        .setOngoing(true)
        .setShowWhen(false)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
    addActionsForState(builder, state)
    return builder.build()
}

private fun addActionsForState(builder: NotificationCompat.Builder, state: WalkState) {
    when (state) {
        is WalkState.Active -> {
            builder.addAction(R.drawable.ic_notification_pause, getString(R.string.walk_notification_action_pause), pausePendingIntent)
            builder.addAction(R.drawable.ic_notification_waypoint, getString(R.string.walk_notification_action_mark_waypoint), markWaypointPendingIntent)
            builder.addAction(R.drawable.ic_notification_stop, getString(R.string.walk_notification_action_finish), finishPendingIntent)
        }
        is WalkState.Paused -> {
            builder.addAction(R.drawable.ic_notification_resume, getString(R.string.walk_notification_action_resume), resumePendingIntent)
            builder.addAction(R.drawable.ic_notification_waypoint, getString(R.string.walk_notification_action_mark_waypoint), markWaypointPendingIntent)
            builder.addAction(R.drawable.ic_notification_stop, getString(R.string.walk_notification_action_finish), finishPendingIntent)
        }
        is WalkState.Meditating -> {
            builder.addAction(R.drawable.ic_notification_end_meditation, getString(R.string.walk_notification_action_end_meditation), endMeditationPendingIntent)
            builder.addAction(R.drawable.ic_notification_stop, getString(R.string.walk_notification_action_finish), finishPendingIntent)
        }
        WalkState.Idle, is WalkState.Finished -> {
            // Defensive: should never render. Service stops on Finished;
            // ACTION_START enters Active immediately. No actions.
        }
    }
}

private fun notificationText(state: WalkState): String = when (state) {
    WalkState.Idle -> getString(R.string.walk_notification_idle)
    is WalkState.Active -> getString(R.string.walk_notification_active, state.walk.distanceMeters / 1_000.0)
    is WalkState.Paused -> getString(R.string.walk_notification_paused)
    is WalkState.Meditating -> getString(R.string.walk_notification_meditating)
    is WalkState.Finished -> getString(R.string.walk_notification_finished)
}

private fun updateNotification(state: WalkState) {
    val manager = getSystemService(NotificationManager::class.java)
    manager.notify(NOTIFICATION_ID, buildNotification(state))
}
```

Also update `startTracking()`'s initial promote — it currently passes a string-based notification; switch to:
```kotlin
promoteToForeground(buildNotification(WalkState.Idle))
```

Add `import org.walktalkmeditate.pilgrim.widget.DeepLinkTarget`.

### Verify
```bash
./gradlew --no-daemon :app:assembleDebug
```

---

## Task 7 — `onStartCommand` action branches with defensive guard + runCatching

### Files

**Modify** `app/src/main/java/org/walktalkmeditate/pilgrim/service/WalkTrackingService.kt`:

Replace `onStartCommand`:

```kotlin
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (val action = intent?.action) {
        ACTION_START -> startTracking()
        ACTION_PAUSE,
        ACTION_RESUME,
        ACTION_END_MEDITATION,
        ACTION_MARK_WAYPOINT,
        ACTION_FINISH -> handleControllerAction(action)
        null -> {
            // Service was revived by the system with a null intent
            // (we return START_NOT_STICKY, but defensive). Stop.
            stopSelf()
        }
    }
    return START_NOT_STICKY
}

private fun handleControllerAction(action: String) {
    // Defensive: action intents only make sense while tracking is
    // live. If the notification persisted past stopSelf and a stale
    // tap reached a fresh service instance, locationJob will be null
    // — we never called startForeground, so on API 31+ the system
    // would kill us with ForegroundServiceDidNotStartInTimeException
    // 5s later. Bail immediately.
    if (locationJob?.isActive != true) {
        Log.w(TAG, "ignoring action $action — tracking not active")
        stopSelf()
        return
    }
    scope.launch {
        try {
            when (action) {
                ACTION_PAUSE -> controller.pauseWalk()
                ACTION_RESUME -> controller.resumeWalk()
                ACTION_END_MEDITATION -> controller.endMeditation()
                ACTION_MARK_WAYPOINT -> controller.recordWaypoint()
                ACTION_FINISH -> controller.finishWalk()
            }
        } catch (ce: CancellationException) {
            // Stage 5-C / 8-A audit rule.
            throw ce
        } catch (t: Throwable) {
            // State-machine rejection (e.g., Pause from a transient
            // Idle window) or repository write failure must not
            // crash the service scope. Sibling jobs (location +
            // notification observer) survive.
            Log.w(TAG, "controller action $action failed", t)
        }
    }
}
```

Add `import kotlinx.coroutines.CancellationException`.

### Verify
```bash
./gradlew --no-daemon :app:assembleDebug
```

---

## Task 8 — `PilgrimNavHost` ActiveWalk deep-link branch

### Files

**Modify** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHost.kt`:

Restructure the deep-link `LaunchedEffect`:
- Keep PERMISSIONS early-return.
- **Move the `when (link)` dispatch BEFORE the `isActiveSession` early-return** so ActiveWalk target is handled unconditionally (the target IS an active-session route — there's nothing to "disrupt").
- ActiveWalk branch:
  ```kotlin
  is org.walktalkmeditate.pilgrim.widget.DeepLinkTarget.ActiveWalk -> {
      navController.navigate(Routes.ACTIVE_WALK) {
          popUpTo(Routes.HOME) { saveState = false }
          launchSingleTop = true
      }
      onDeepLinkConsumed()
      return@LaunchedEffect
  }
  ```
- WalkSummary + Home branches stay BELOW the isActiveSession early-return (preserves Stage 9-A's widget-protection guarantee for those targets).

### Verify
```bash
./gradlew --no-daemon :app:assembleDebug
```

---

## Task 9 — `WalkTrackingServiceNotificationTest`

Robolectric test that validates the notification builder + per-state action set.

### Files

**Create** `app/src/test/java/org/walktalkmeditate/pilgrim/service/WalkTrackingServiceNotificationTest.kt`:

Use Robolectric to instantiate `WalkTrackingService` (or extract `buildNotification(state)` + `addActionsForState(builder, state)` to be testable without service lifecycle — preferred). For testability, refactor: make `buildNotification` + `addActionsForState` accept a `Context` instead of being member functions, then move them to a top-level helper file (`NotificationFactory.kt`) the service delegates to. The service is then a thin wrapper.

Tests:
- `Active state notification has 3 actions: Pause, Mark Waypoint, Finish`.
- `Paused state notification has 3 actions: Resume, Mark Waypoint, Finish`.
- `Meditating state notification has 2 actions: End Meditation, Finish`.
- `Idle state notification has 0 actions`.
- `Finished state notification has 0 actions`.
- `notification has VISIBILITY_PRIVATE on main + setPublicVersion present`.
- `notification's contentIntent extras carry DEEP_LINK_ACTIVE_WALK`.

If the refactor to a top-level `NotificationFactory` is more scope than wanted, alternate approach: instantiate the service via `Robolectric.buildService(WalkTrackingService::class.java).create().get()`, then reflect or call `buildNotification(state)` directly — Robolectric handles the service lifecycle.

### Verify
```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests "*.WalkTrackingServiceNotificationTest"
```

---

## Self-review

- **Spec coverage**: all sections of the spec map to a task. The 5 production resources (drawables) + 1 modified-strings + 4 modified-Kotlin + 2 test-files in the Files section all accounted for.
- **Task count**: 9 — within autopilot's 15-task threshold.
- **Type consistency**: `DeepLinkTarget.ActiveWalk`, `WalkController.recordWaypoint()` signature, `buildNotification(state: WalkState)` consistent across layers.
- **No placeholders**.
- **CE re-throw** in every `catch (Throwable)` block wrapping suspend code: WalkController.recordWaypoint, WalkTrackingService.handleControllerAction.
- **No Robolectric+runBlocking deadlock** patterns: Service notification tests instantiate the service directly (no VM-launch), WalkController tests use in-memory Room with direct controller calls.
- **PendingIntent flags**: every action PendingIntent uses `FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT` matching existing contentIntent.
- **Action revival defense**: `handleControllerAction` short-circuits via `if (locationJob?.isActive != true) { stopSelf(); return }` before any controller dispatch.
- **dispatchMutex around recordWaypoint**: prevents waypoint-after-finishWalk race.
- **Deep-link gate refactor explicit**: ActiveWalk handled BEFORE isActiveSession early-return.
- **VM passthrough deliberately omitted** per YAGNI; documented in spec's "NOT modified" section.
