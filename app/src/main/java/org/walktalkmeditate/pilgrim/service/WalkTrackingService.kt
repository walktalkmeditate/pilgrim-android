// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
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

/**
 * Foreground service that binds the physical location stream to the
 * [WalkController] and surfaces the walk as an ongoing notification.
 * Starts via [startIntent], stops via [stopIntent] or when the controller
 * reaches [WalkState.Finished].
 */
@AndroidEntryPoint
class WalkTrackingService : Service() {

    @Inject lateinit var controller: WalkController

    @Inject lateinit var locationSource: LocationSource

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var locationJob: Job? = null
    private var notificationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTracking()
            ACTION_STOP -> stopTracking()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startTracking() {
        promoteToForeground(buildNotification(getString(R.string.walk_notification_starting)))

        locationJob?.cancel()
        locationJob = scope.launch {
            locationSource.locationFlow().collect { point ->
                controller.recordLocation(point)
            }
        }

        notificationJob?.cancel()
        notificationJob = scope.launch {
            controller.state.collect { state ->
                updateNotification(state)
                if (state is WalkState.Finished) stopSelf()
            }
        }
    }

    private fun stopTracking() {
        locationJob?.cancel()
        notificationJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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

    private fun buildNotification(text: String): Notification {
        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPending = PendingIntent.getActivity(
            this,
            /* requestCode = */ 0,
            activityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(contentPending)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(state: WalkState) {
        val text = when (state) {
            WalkState.Idle -> getString(R.string.walk_notification_idle)
            is WalkState.Active -> getString(
                R.string.walk_notification_active,
                state.walk.distanceMeters / 1_000.0,
            )
            is WalkState.Paused -> getString(R.string.walk_notification_paused)
            is WalkState.Meditating -> getString(R.string.walk_notification_meditating)
            is WalkState.Finished -> getString(R.string.walk_notification_finished)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        const val ACTION_START = "org.walktalkmeditate.pilgrim.service.WalkTrackingService.START"
        const val ACTION_STOP = "org.walktalkmeditate.pilgrim.service.WalkTrackingService.STOP"

        private const val CHANNEL_ID = "walk_tracking"
        private const val NOTIFICATION_ID = 1

        fun startIntent(context: Context): Intent =
            Intent(context, WalkTrackingService::class.java).apply { action = ACTION_START }

        fun stopIntent(context: Context): Intent =
            Intent(context, WalkTrackingService::class.java).apply { action = ACTION_STOP }
    }
}
