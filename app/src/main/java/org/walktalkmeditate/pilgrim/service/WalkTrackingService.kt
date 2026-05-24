// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.MainActivity
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.units.UnitsPreferencesRepository
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.location.LocationSource
import org.walktalkmeditate.pilgrim.walk.WalkController
import org.walktalkmeditate.pilgrim.widget.DeepLinkTarget

/**
 * Foreground service that binds the physical location stream to the
 * [WalkController] and surfaces the walk as an ongoing notification with
 * media-style action buttons.
 *
 * Starts via [startIntent]. Stopping is state-driven only: the service
 * observes [WalkController.state] and calls `stopSelf()` once the
 * controller reaches [WalkState.Finished].
 *
 * Action buttons (per state) deliver via `PendingIntent.getService(...)`
 * directly back to this service — no BroadcastReceiver hop. Direct service
 * delivery sidesteps the API 26+ implicit-broadcast filter and shaves the
 * latency that an extra IPC would add to a tap from the lock screen.
 */
@AndroidEntryPoint
class WalkTrackingService : Service() {

    @Inject lateinit var controller: WalkController

    @Inject lateinit var locationSource: LocationSource

    @Inject lateinit var unitsPreferences: UnitsPreferencesRepository

    @Inject lateinit var repository: org.walktalkmeditate.pilgrim.data.WalkRepository

    @Inject lateinit var backgroundWhisperAutoPlayer: BackgroundWhisperAutoPlayer

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var locationJob: Job? = null
    private var notificationJob: Job? = null
    private var stepFlushJob: Job? = null

    /**
     * Latch: true once the controller has emitted any in-progress state
     * (Active|Paused|Meditating) since the service started observing.
     * Required to distinguish the cold-start initial Idle (do NOT
     * self-stop — service is freshly promoted to FGS, controller hasn't
     * dispatched anything yet) from the Stage 9.5-C discardWalk
     * Active→Idle transition (DO self-stop — walk row was just
     * cascade-deleted, service has nothing left to track). Reset is
     * unnecessary because the service is destroyed between walks.
     */
    private var hasBeenActive = false

    private lateinit var notificationActions: WalkNotificationActions

    override fun onCreate() {
        super.onCreate()
        isRunning.set(true)
        createNotificationChannel()
        notificationActions = WalkNotificationActions(
            pause = actionPendingIntent(ACTION_PAUSE, REQUEST_CODE_PAUSE),
            resume = actionPendingIntent(ACTION_RESUME, REQUEST_CODE_RESUME),
            endMeditation = actionPendingIntent(ACTION_END_MEDITATION, REQUEST_CODE_END_MEDITATION),
            markWaypoint = actionPendingIntent(ACTION_MARK_WAYPOINT, REQUEST_CODE_MARK_WAYPOINT),
            finish = actionPendingIntent(ACTION_FINISH, REQUEST_CODE_FINISH),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (val action = intent?.action) {
            ACTION_START -> startTracking(intent)
            ACTION_PAUSE,
            ACTION_RESUME,
            ACTION_START_MEDITATION,
            ACTION_END_MEDITATION,
            ACTION_MARK_WAYPOINT,
            ACTION_FINISH,
            ACTION_DISCARD,
            ACTION_SET_INTENTION -> handleControllerAction(action, intent)
            null -> {
                // START_REDELIVER_INTENT redelivers the LAST delivered
                // intent (the original ACTION_START), so a null intent
                // here is not the revival path — it only happens on a
                // genuinely malformed start. We have no tracking pipeline
                // and would crash on the API 31+
                // ForegroundServiceDidNotStartInTimeException timer. Bail.
                stopSelf()
            }
        }
        // START_REDELIVER_INTENT: if the OS kills the service mid-walk
        // (OEM power manager force-kill after the screen has been off for
        // ~30-40 min — the OnePlus/OxygenOS failure that ended long
        // backgrounded walks), the system revives the service AND
        // redelivers the last ACTION_START intent. onStartCommand then
        // receives ACTION_START again → startTracking() rebuilds the
        // location pipeline against the still-unfinished Room walk. The
        // old START_NOT_STICKY rejected START_STICKY because that revives
        // with a NULL intent (no pipeline + API 31+ FGS-start-timeout
        // crash); START_REDELIVER_INTENT sidesteps both — the redelivered
        // intent is the real ACTION_START, and startTracking() restores
        // the controller from Room (restoreActiveWalk) before promoting,
        // so a bare revived process re-establishes a live walk instead of
        // silently dropping GPS into an Idle controller.
        return START_MODE
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning.set(false)
        // Tear down the whisper auto-player before cancelling our scope.
        // stop() cancelAndJoins its own collectors FIRST (so a buffered
        // Entered event can't drive a play() mid-teardown), then stops the
        // detector + clears its dedup. Quick job-cancel + suspend cleanup,
        // so blocking briefly in onDestroy is acceptable.
        if (this::backgroundWhisperAutoPlayer.isInitialized) {
            kotlinx.coroutines.runBlocking {
                runCatching { backgroundWhisperAutoPlayer.stop() }
            }
        }
        scope.cancel()
        // Explicit teardown so the FGS notification is gone the moment
        // the service stops, not whenever the OS gets around to clearing
        // it. Closes the window where a finishWalk emission posts the
        // "Walk complete." render and stopSelf() schedules teardown,
        // leaving a tappable-but-dead notification visible for the
        // milliseconds before destroy lands.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    private fun startTracking(startIntent: Intent?) {
        // Re-entrant START intents are a no-op: if the pipeline is already
        // live, cancelling and relaunching would race the old subscription's
        // awaitClose cleanup against the new one's subscribe and produce
        // duplicate route samples in the window between.
        if (locationJob?.isActive == true) return

        val intentionExtra = startIntent?.getStringExtra(EXTRA_INTENTION)
        val isFreshStart = startIntent?.getBooleanExtra(EXTRA_FRESH_START, false) == true

        // API 34+ rejects startForeground(type=location) with SecurityException
        // if FINE location isn't granted at that moment; API 33+ silently
        // suppresses the notification without POST_NOTIFICATIONS. Bail loud
        // rather than limp along — UI must gate this intent on permissions.
        if (!hasRequiredPermissions()) {
            Log.w(TAG, "startTracking aborted: required permissions not granted")
            stopSelf()
            return
        }

        // Read the controller's current state synchronously so the
        // initial promote matches reality. If the user resumed an
        // already-Active walk (restoreActiveWalk ran on the way in),
        // hard-coding Idle here would flash a zero-action "Preparing
        // your walk…" notification for the sub-second window before
        // the state collector delivers the first real emission.
        //
        // promoteToForeground MUST run synchronously here (not inside the
        // launch below): the API 31+ FGS-start timeout requires
        // startForeground() promptly after the OS hands us the
        // (redelivered) start intent. The Idle notification is acceptable
        // for the sub-second window before restore + the first real
        // state emission re-render it.
        promoteToForeground(buildNotification(controller.state.value))

        locationJob = scope.launch {
            // Three paths land here, all distinguished by Room state +
            // the [EXTRA_FRESH_START] flag:
            //
            //  1. UI fresh start (`isFreshStart=true`, no active walk in
            //     Room): insert the walk row + transition to Active here.
            //     UI's [UiWalkController.startWalk] awaits the new row
            //     via [WalkRepository.observeActiveWalk] before returning
            //     a [Walk] to the caller.
            //  2. START_REDELIVER_INTENT revival (the OS killed the
            //     service mid-walk and re-delivered the last intent into
            //     a fresh process): controller is Idle, walk row already
            //     in Room, nothing to insert. `restoreActiveWalk` rebuilds
            //     the in-memory state from the persisted row + events +
            //     samples. The redelivered intent may carry the original
            //     `isFreshStart=true` flag — the restored-walk check
            //     short-circuits the insert before we'd double-create.
            //  3. UI fresh start raced with a prior in-flight walk row
            //     (defensive — shouldn't happen via UiWalkController
            //     because UI's flow only fires ACTION_START when no
            //     active walk is observed, but a stale process or
            //     test-time corner can land here). `restoreActiveWalk`
            //     adopts the existing walk; the `isFreshStart` insert is
            //     skipped.
            if (controller.state.value is WalkState.Idle) {
                val restored = runCatching { controller.restoreActiveWalk() }
                    .onFailure { Log.w(TAG, "restoreActiveWalk on revival failed", it) }
                    .getOrNull()
                if (restored == null) {
                    if (isFreshStart) {
                        // No walk in Room yet — UI is asking us to
                        // create one. Failure here (e.g. controller
                        // already non-Idle from a fast race) is logged
                        // and swallowed so the pipeline still proceeds
                        // against whatever state the controller landed in.
                        try {
                            controller.startWalk(intentionExtra)
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: IllegalStateException) {
                            Log.w(TAG, "fresh ACTION_START rejected: ${e.message}")
                        }
                    } else {
                        Log.w(TAG, "revived ACTION_START with no unfinished walk — stopping")
                        stopSelf()
                        return@launch
                    }
                }
            }
            try {
                locationSource.locationFlow().collect { point ->
                    controller.recordLocation(point)
                }
            } catch (e: SecurityException) {
                // Permission revoked mid-walk via Settings. Finish the walk
                // through the controller so in-memory state and DB row stay
                // consistent, then let the Finished observer stop us.
                Log.w(TAG, "location permission revoked mid-walk", e)
                runCatching { controller.finishWalk() }
            }
        }

        notificationJob = scope.launch {
            // Observe controller state AND units preference: a Settings
            // toggle from Metric→Imperial mid-walk must re-render the
            // notification text immediately, not wait for the next GPS
            // fix to push a fresh `controller.state` emission. The
            // fingerprint already includes the units ordinal — combining
            // here ensures the collector actually fires when units flip.
            // Combining a `_` for units (we don't use the value here;
            // `notificationFingerprint` reads `unitsPreferences.distanceUnits.value`
            // synchronously) keeps the existing decideStateAction path
            // untouched.
            combine(controller.state, unitsPreferences.distanceUnits) { state, _ -> state }
                .collect { state ->
                    val (nextLatch, action) = decideStateAction(state, hasBeenActive)
                    hasBeenActive = nextLatch
                    when (action) {
                        StateAction.SelfStop -> {
                            // Skip the Finished render — onDestroy's
                            // stopForeground(REMOVE) is about to clear the
                            // notification anyway, and posting a "Walk
                            // complete." rebuild here just lets the user
                            // briefly see it flash on slower devices.
                            // For Idle-after-in-progress (Stage 9.5-C
                            // discard), same reasoning: the walk row was
                            // just cascade-deleted, no point re-rendering.
                            stopSelf()
                        }
                        StateAction.UpdateNotification -> updateNotification(state)
                    }
                }
        }

        // Persist the step count every 30s while Active so an OEM
        // mid-walk kill (e.g. OnePlus o-kill at 24min in walk 16) can
        // be recovered with the last live counter. Previously
        // `updateSteps` fired only on the Finish path
        // (WalkEffect.PersistWalk), so any kill-then-recoverStaleWalks
        // path produced a NULL `walk.steps` and the Steps row hid on
        // the summary. `collectLatest` rotates the inner block on
        // state changes so the ticker auto-cancels when leaving
        // Active.
        stepFlushJob = scope.launch {
            controller.state.collectLatest { state ->
                if (state is org.walktalkmeditate.pilgrim.domain.WalkState.Active) {
                    val walkId = state.walk.walkId
                    while (isActive) {
                        delay(STEP_FLUSH_INTERVAL_MS)
                        val steps = controller.liveSteps.value ?: continue
                        try {
                            repository.updateSteps(walkId, steps)
                        } catch (ce: kotlinx.coroutines.CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            Log.w(TAG, "step flush failed for walk $walkId", t)
                        }
                    }
                }
            }
        }

        // Whisper proximity auto-play runs HERE (in :tracker) rather than
        // in the UI so a nearby whisper plays even when the screen is
        // locked / the UI process is gone. Fed by the controller's live
        // state; tears down in onDestroy.
        backgroundWhisperAutoPlayer.start(scope, controller.state)
    }

    private fun handleControllerAction(action: String, intent: Intent?) {
        scope.launch {
            // Robustness path: if service was destroyed
            // (locationJob inactive) but a UI action or
            // notification tap arrives, restore the walk from Room
            // first so the action targets the in-progress walk
            // instead of bailing silently. Pre-:tracker-split this
            // path was rare; under the split the tracker service can
            // be torn down (FGS timeout, OEM cleanup) while the
            // process remains and Room still holds the active walk —
            // ACTION_FINISH on such a recreated service used to no-
            // op and the walk would never get its end_timestamp set.
            if (locationJob?.isActive != true) {
                val restored = runCatching { controller.restoreActiveWalk() }
                    .onFailure { Log.w(TAG, "restoreActiveWalk in action handler failed", it) }
                    .getOrNull()
                if (restored == null && controller.state.value is WalkState.Idle) {
                    Log.w(TAG, "no active walk to apply $action to — bailing")
                    // Clear any orphan notification posted by a prior
                    // process instance (FGS notifications are normally
                    // cleared on service-destroy, but a stale
                    // notification can outlive abnormal process
                    // termination).
                    getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                    stopSelf()
                    return@launch
                }
                Log.i(TAG, "applying $action on restored walk (service was inactive)")
            }
            try {
                when (action) {
                    ACTION_PAUSE -> controller.pauseWalk()
                    ACTION_RESUME -> controller.resumeWalk()
                    ACTION_START_MEDITATION -> controller.startMeditation()
                    ACTION_END_MEDITATION -> {
                        // EXTRA_END_MILLIS carries the captured Done-tap
                        // timestamp from the meditation screen so the
                        // closing 6.5s ceremony doesn't inflate the
                        // recorded interval. Notification-tap path
                        // omits it → controller falls back to its
                        // injected clock.
                        val endMillis = intent
                            ?.takeIf { it.hasExtra(EXTRA_END_MILLIS) }
                            ?.getLongExtra(EXTRA_END_MILLIS, -1L)
                            ?.takeIf { it > 0 }
                        controller.endMeditation(endMillis)
                    }
                    ACTION_MARK_WAYPOINT -> {
                        val label = intent?.getStringExtra(EXTRA_WAYPOINT_LABEL)
                        val icon = intent?.getStringExtra(EXTRA_WAYPOINT_ICON)
                        controller.recordWaypoint(label = label, icon = icon)
                    }
                    ACTION_FINISH -> controller.finishWalk()
                    ACTION_DISCARD -> controller.discardWalk()
                    ACTION_SET_INTENTION -> {
                        val text = intent?.getStringExtra(EXTRA_INTENTION) ?: ""
                        controller.setIntention(text)
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                // State-machine rejection (e.g. Pause from a transient Idle
                // window after a stale tap) or a repository write failure
                // must not crash the service scope. Sibling jobs (location
                // collector + notification observer) survive.
                Log.w(TAG, "controller action $action failed", t)
            }
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val ctx = applicationContext
        val fineGranted = ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val notifyGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                ctx,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return fineGranted && notifyGranted
    }

    private fun promoteToForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // location keeps the process alive; mediaPlayback covers the
            // background whisper / soundscape / voice-guide audio that
            // plays with the screen locked (see AndroidManifest comment +
            // BackgroundWhisperAutoPlayer).
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.walk_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.walk_notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun actionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, WalkTrackingService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

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
            .setContentText(walkNotificationText(this, state, unitsPreferences.distanceUnits.value))
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPending)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(buildLockScreenNotification(state))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        addWalkActionsForState(builder, this, state, notificationActions)
        return builder.build()
    }

    private fun buildLockScreenNotification(state: WalkState): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.walk_notification_lock_screen_title))
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        addWalkActionsForState(builder, this, state, notificationActions)
        return builder.build()
    }

    /**
     * State-class fingerprint + 5 m distance bucket. The notification
     * text formats distance with `%.2f km` (HALF_UP rounding at the
     * 0.005 km = 5 m boundary), so a 10 m bucket would skip every
     * second display tick — visible as up to 5 m of stale km on the
     * notification. 5 m alignment matches the rounding boundary
     * exactly. Notify-rate stays in the ~100/walk range (vs the
     * untrottled ~5400/walk), well below any vendor's update-
     * suppression threshold.
     */
    private var lastNotifiedFingerprint: Long = -1L

    private fun updateNotification(state: WalkState) {
        val fingerprint = notificationFingerprint(state)
        if (fingerprint == lastNotifiedFingerprint) return
        lastNotifiedFingerprint = fingerprint
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun notificationFingerprint(state: WalkState): Long {
        // Pack the state-class ordinal + 5m-bucketed distance + units
        // ordinal into one Long. State-class change always re-renders
        // (action set + text both depend on it); within a single
        // state-class only crossing a 5m boundary re-renders, matching
        // the displayed text's rounding granularity.
        //
        // The units ordinal MUST be in the fingerprint so a Settings
        // toggle from Metric→Imperial (or vice versa) mid-walk forces
        // the notification to re-render with the new unit. Without
        // this, the notification would show stale km/mi until the
        // user walked another 5m.
        val classOrdinal = when (state) {
            WalkState.Idle -> 0L
            is WalkState.Active -> 1L
            is WalkState.Paused -> 2L
            is WalkState.Meditating -> 3L
            is WalkState.Finished -> 4L
        }
        val distanceBucket = when (state) {
            is WalkState.Active -> (state.walk.distanceMeters / 5.0).toLong()
            is WalkState.Paused -> (state.walk.distanceMeters / 5.0).toLong()
            is WalkState.Meditating -> (state.walk.distanceMeters / 5.0).toLong()
            else -> 0L
        }
        val unitsOrdinal = unitsPreferences.distanceUnits.value.ordinal.toLong()
        return classOrdinal * 100_000_000L + distanceBucket * 10L + unitsOrdinal
    }

    /**
     * What the state-collector should do for the just-observed [state],
     * given whether the service has previously seen any in-progress
     * state. Returns the new latch value and the action.
     *
     * Pure function — extracted so the discard self-stop path can be
     * unit-tested without standing up a full Robolectric service +
     * Hilt environment. See `WalkTrackingServiceDecisionTest`.
     */
    internal enum class StateAction { SelfStop, UpdateNotification }

    companion object {
        /**
         * Per-process "is the FGS alive in THIS process" flag. Set in
         * onCreate / cleared in onDestroy. Used by the same-process
         * decideStateAction path — the service queries its own state,
         * so the per-process scope is correct here.
         *
         * **Do NOT read this from the UI process** — under the
         * `:tracker` process split the flag is only ever set in
         * `:tracker`, so UI reads always see false. Cross-process
         * callers must use [isFgsAlive] instead, which queries
         * ActivityManager.getRunningServices for the canonical answer.
         */
        private val isRunning = java.util.concurrent.atomic.AtomicBoolean(false)

        /**
         * Cross-process "is the WalkTrackingService alive in any
         * process of this app" query. Called by
         * `MainActivity.onCreate` to discriminate the warm-launch-
         * after-swipe case (FGS gone, walk row stale → recover) from
         * the notification-tap-to-return case (FGS still alive in
         * `:tracker`, do not finalize a live walk).
         *
         * Under the `:tracker` process split, the previous
         * `isRunning.get()` implementation was unsafe: the static is
         * only set in the `:tracker` process, so the UI process's
         * classloader copy stays false forever and warm-launch
         * recovery would falsely finalize the live walk on every
         * re-open of the app.
         *
         * [ActivityManager.getRunningServices] remains accessible to
         * the calling app for its own services on API 26+ — the
         * third-party restriction documented in the API only applies
         * to OTHER apps' services. We pass `Int.MAX_VALUE` because
         * the list always contains the caller's services regardless
         * of the limit.
         */
        fun isFgsAlive(context: android.content.Context): Boolean {
            val am = context.getSystemService(android.app.ActivityManager::class.java)
                ?: return false
            val name = WalkTrackingService::class.java.name
            return try {
                @Suppress("DEPRECATION")
                am.getRunningServices(Int.MAX_VALUE)
                    .any { it.service.className == name && it.foreground }
            } catch (t: Throwable) {
                // ActivityManager can throw on some hardened ROMs.
                // Fall back to the conservative answer (assume FGS
                // alive) so we DO NOT finalize a possibly-live walk.
                // The warm-launch recovery is a backstop; a missed
                // recovery just means the user sees the walk re-open
                // — far less harmful than tombstoning a live walk.
                android.util.Log.w(
                    "WalkTrackingService",
                    "isFgsAlive: ActivityManager query failed, assuming alive",
                    t,
                )
                true
            }
        }

        /**
         * The value [onStartCommand] returns. Named so a Robolectric
         * test can pin the contract without Hilt-injecting the service
         * (the project deliberately has no hilt-android-testing dep —
         * see [WalkTrackingServiceDiscardTest]'s rationale). Mirrors the
         * [decideStateAction] pure-extraction precedent.
         *
         * START_REDELIVER_INTENT (not START_NOT_STICKY): an OEM
         * power-manager mid-walk kill is revived by the OS WITH the last
         * ACTION_START intent re-delivered, so [startTracking] re-runs
         * and [WalkController.restoreActiveWalk] rebuilds the live walk
         * from the unfinished Room row. START_STICKY is still wrong (it
         * revives with a null intent → no pipeline + API 31+ FGS-start
         * timeout crash); REDELIVER_INTENT carries the real ACTION_START.
         */
        const val START_MODE: Int = Service.START_REDELIVER_INTENT

        const val ACTION_START = "org.walktalkmeditate.pilgrim.service.WalkTrackingService.START"
        const val ACTION_PAUSE = "org.walktalkmeditate.pilgrim.service.WalkTrackingService.PAUSE"
        const val ACTION_RESUME = "org.walktalkmeditate.pilgrim.service.WalkTrackingService.RESUME"
        const val ACTION_START_MEDITATION =
            "org.walktalkmeditate.pilgrim.service.WalkTrackingService.START_MEDITATION"
        const val ACTION_END_MEDITATION =
            "org.walktalkmeditate.pilgrim.service.WalkTrackingService.END_MEDITATION"
        const val ACTION_MARK_WAYPOINT =
            "org.walktalkmeditate.pilgrim.service.WalkTrackingService.MARK_WAYPOINT"
        const val ACTION_FINISH = "org.walktalkmeditate.pilgrim.service.WalkTrackingService.FINISH"
        const val ACTION_DISCARD = "org.walktalkmeditate.pilgrim.service.WalkTrackingService.DISCARD"
        const val ACTION_SET_INTENTION =
            "org.walktalkmeditate.pilgrim.service.WalkTrackingService.SET_INTENTION"

        /** Extra: starting walk's intention text, or new intention on
         *  [ACTION_SET_INTENTION]. UTF-8 string, ≤140 chars (server-side
         *  controller still re-sanitizes). */
        const val EXTRA_INTENTION = "extra.intention"

        /** Extra: flag distinguishing a UI-initiated [ACTION_START]
         *  (where the service must insert the walk row) from a
         *  START_REDELIVER_INTENT revival (where the service restores
         *  from an existing Room row). Boolean. */
        const val EXTRA_FRESH_START = "extra.fresh_start"

        /** Extra: explicit Done-tap millis for [ACTION_END_MEDITATION].
         *  Long. Absent → service uses its own clock. */
        const val EXTRA_END_MILLIS = "extra.end_millis"

        /** Extra: optional waypoint label (UTF-8 string) for
         *  [ACTION_MARK_WAYPOINT]. */
        const val EXTRA_WAYPOINT_LABEL = "extra.waypoint_label"

        /** Extra: optional waypoint icon key (UTF-8 string) for
         *  [ACTION_MARK_WAYPOINT]. */
        const val EXTRA_WAYPOINT_ICON = "extra.waypoint_icon"

        private const val TAG = "WalkTrackingService"
        /** Persist `walk.steps` this often while Active so a mid-walk
         *  OEM kill recovers with the last live counter intact. */
        private const val STEP_FLUSH_INTERVAL_MS = 30_000L
        private const val CHANNEL_ID = "walk_tracking"
        private const val NOTIFICATION_ID = 1
        private const val REQUEST_CODE_CONTENT = 0
        private const val REQUEST_CODE_PAUSE = 1
        private const val REQUEST_CODE_RESUME = 2
        private const val REQUEST_CODE_END_MEDITATION = 3
        private const val REQUEST_CODE_MARK_WAYPOINT = 4
        private const val REQUEST_CODE_FINISH = 5

        fun startIntent(context: Context): Intent =
            Intent(context, WalkTrackingService::class.java).apply { action = ACTION_START }

        /**
         * Pure decision: given the latest observed [state] and whether
         * the service has seen any in-progress state since onCreate,
         * return the new latch value and what the collector should do.
         *
         * Behavior:
         *  - Finished → always SelfStop (controller has reached terminal).
         *  - Idle when latch=true → SelfStop (Stage 9.5-C discard path).
         *  - Idle when latch=false → UpdateNotification (cold-start
         *    initial Idle, before any walk has been dispatched).
         *  - Active|Paused|Meditating → UpdateNotification + flip latch true.
         */
        internal fun decideStateAction(
            state: WalkState,
            hasBeenActive: Boolean,
        ): Pair<Boolean, StateAction> {
            val nextLatch = hasBeenActive ||
                state is WalkState.Active ||
                state is WalkState.Paused ||
                state is WalkState.Meditating
            val action = when {
                state is WalkState.Finished -> StateAction.SelfStop
                state is WalkState.Idle && hasBeenActive -> StateAction.SelfStop
                else -> StateAction.UpdateNotification
            }
            return nextLatch to action
        }
    }
}
