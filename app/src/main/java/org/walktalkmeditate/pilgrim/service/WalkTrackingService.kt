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
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.MainActivity
import org.walktalkmeditate.pilgrim.R
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

    @Inject lateinit var repository: org.walktalkmeditate.pilgrim.data.WalkRepository

    @Inject lateinit var walkRecoveryRepository:
        org.walktalkmeditate.pilgrim.data.recovery.WalkRecoveryRepository

    @Inject lateinit var clock: org.walktalkmeditate.pilgrim.domain.Clock

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var locationJob: Job? = null
    private var notificationJob: Job? = null

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
            ACTION_START -> startTracking()
            ACTION_PAUSE,
            ACTION_RESUME,
            ACTION_END_MEDITATION,
            ACTION_MARK_WAYPOINT,
            ACTION_FINISH -> handleControllerAction(action)
            null -> {
                // We return START_NOT_STICKY, so the system shouldn't revive
                // us with a null intent — but be defensive: if it ever does,
                // we have no tracking pipeline and would crash on the API 31+
                // ForegroundServiceDidNotStartInTimeException timer. Bail.
                stopSelf()
            }
        }
        // START_NOT_STICKY: if the OS kills the service mid-walk, the walk
        // row in Room remains unfinished. The app surfaces it on next open
        // and the user resumes explicitly, rather than the service silently
        // re-promoting itself with a null intent (and no tracking pipeline).
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Fires when the user swipes the app away from recents while a walk
     * is in progress. iOS-parity behavior: the walk auto-finalizes (gets
     * `endTimestamp` written), the recovery marker is persisted to
     * DataStore, and a banner appears on the Path tab on next launch.
     *
     * iOS reaches this UX via process kill (CoreData has no walk row,
     * so a JSON checkpoint is required for crash recovery). Android
     * reaches it via this hook (Room writes incrementally during the
     * walk; we just need to flip endTimestamp to "now"). Same UX, no
     * JSON checkpoint dance.
     *
     * Called on the main thread before `onDestroy`. The OS may begin
     * tearing the process down within milliseconds, so the finalize
     * uses synchronous `runBlocking` writes — both are sub-frame fast
     * (single Room UPDATE + single DataStore PUT).
     *
     * Manifest sets `stopWithTask="false"` so this hook fires (vs
     * the default auto-stop) and we control the order: persist first,
     * then stopSelf.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "onTaskRemoved — finalizing in-progress walk for recovery banner")
        try {
            val activeWalk = kotlinx.coroutines.runBlocking { repository.getActiveWalk() }
            if (activeWalk != null) {
                val endedAt = clock.now()
                val finalized = kotlinx.coroutines.runBlocking {
                    repository.finishWalkAtomic(walkId = activeWalk.id, endTimestamp = endedAt)
                }
                if (finalized) {
                    walkRecoveryRepository.markRecoveredBlocking(activeWalk.id)
                    Log.i(
                        TAG,
                        "onTaskRemoved finalized walk=${activeWalk.id} at=$endedAt; banner armed",
                    )
                } else {
                    Log.w(TAG, "onTaskRemoved: finishWalkAtomic returned false for walk=${activeWalk.id}")
                }
            } else {
                Log.i(TAG, "onTaskRemoved: no active walk to finalize")
            }
        } catch (t: Throwable) {
            // Best-effort. If Room is wedged or the process is being torn
            // down hard, we'd rather lose the recovery banner than crash.
            Log.w(TAG, "onTaskRemoved finalize failed", t)
        }
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
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

    private fun startTracking() {
        // Re-entrant START intents are a no-op: if the pipeline is already
        // live, cancelling and relaunching would race the old subscription's
        // awaitClose cleanup against the new one's subscribe and produce
        // duplicate route samples in the window between.
        if (locationJob?.isActive == true) return

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
        promoteToForeground(buildNotification(controller.state.value))

        locationJob = scope.launch {
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
            controller.state.collect { state ->
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
    }

    private fun handleControllerAction(action: String) {
        // Defensive: action intents only make sense while tracking is live.
        // If the notification persisted past stopSelf and a stale tap
        // reached a fresh service instance, locationJob will be null —
        // the controller has no in-memory walk to act on, and processing
        // the action would be a no-op at best and inconsistent at worst
        // (e.g., dispatching Pause against a controller that's still
        // Idle from cold-start). Bail and clear any orphan notification.
        // (Note: PendingIntent.getService delivers via startService(),
        // NOT startForegroundService(), so the API 31+ FGS timeout
        // doesn't apply here — bailing is correctness, not deadline
        // avoidance.)
        if (locationJob?.isActive != true) {
            Log.w(TAG, "ignoring action $action — tracking not active")
            // Clear any orphan notification posted by a prior process
            // instance (FGS notifications are normally cleared on
            // service-destroy, but a stale notification can outlive an
            // abnormal process termination). Without this, every tap
            // spawns a fresh service that bails — the notification
            // appears tappable but does nothing.
            getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
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
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
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
            .setContentText(walkNotificationText(this, state))
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
        // Pack the state-class ordinal + 5m-bucketed distance into one
        // Long. State-class change always re-renders (action set + text
        // both depend on it); within a single state-class only crossing
        // a 5m boundary re-renders, matching the displayed text's
        // rounding granularity.
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
        return classOrdinal * 10_000_000L + distanceBucket
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
        const val ACTION_START = "org.walktalkmeditate.pilgrim.service.WalkTrackingService.START"
        const val ACTION_PAUSE = "org.walktalkmeditate.pilgrim.service.WalkTrackingService.PAUSE"
        const val ACTION_RESUME = "org.walktalkmeditate.pilgrim.service.WalkTrackingService.RESUME"
        const val ACTION_END_MEDITATION =
            "org.walktalkmeditate.pilgrim.service.WalkTrackingService.END_MEDITATION"
        const val ACTION_MARK_WAYPOINT =
            "org.walktalkmeditate.pilgrim.service.WalkTrackingService.MARK_WAYPOINT"
        const val ACTION_FINISH = "org.walktalkmeditate.pilgrim.service.WalkTrackingService.FINISH"

        private const val TAG = "WalkTrackingService"
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
