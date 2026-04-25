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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var locationJob: Job? = null
    private var notificationJob: Job? = null

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

    override fun onDestroy() {
        scope.cancel()
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

        promoteToForeground(buildNotification(WalkState.Idle))

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
                updateNotification(state)
                if (state is WalkState.Finished) stopSelf()
            }
        }
    }

    private fun handleControllerAction(action: String) {
        // Defensive: action intents only make sense while tracking is live.
        // If the notification persisted past stopSelf and a stale tap reached
        // a fresh service instance, locationJob will be null — we never
        // called startForeground, so on API 31+ the system would kill us
        // with ForegroundServiceDidNotStartInTimeException 5s later.
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

    private fun updateNotification(state: WalkState) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(state))
    }

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
    }
}
