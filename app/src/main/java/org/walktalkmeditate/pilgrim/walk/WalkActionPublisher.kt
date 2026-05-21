// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.walktalkmeditate.pilgrim.service.WalkTrackingService

/**
 * Cross-process bridge: every user-initiated walk action in the UI
 * process travels through here as a service intent so it lands at the
 * `:tracker` process's [org.walktalkmeditate.pilgrim.walk.WalkControllerImpl].
 *
 * Without this indirection, UI's in-app buttons (Pause / Resume /
 * Finish / etc.) would mutate the UI process's WalkController singleton,
 * which under the manifest split is a different object than the
 * tracker's controller — the GPS pipeline would keep recording on a
 * paused walk, finish would not stop the tracker, etc.
 *
 * Notification-button taps already follow this exact path
 * ([android.app.PendingIntent.getService]); this class exposes the
 * same channel to UI code.
 */
@Singleton
class WalkActionPublisher @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Begin a new walk. Uses `startForegroundService` because the
     * service is not running yet on the first start; the service's
     * onStartCommand promotes to FG before the API 31+ deadline.
     */
    fun start(intention: String?) {
        val intent = baseIntent(WalkTrackingService.ACTION_START).apply {
            putExtra(WalkTrackingService.EXTRA_FRESH_START, true)
            if (intention != null) putExtra(WalkTrackingService.EXTRA_INTENTION, intention)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun pause() = fireService(WalkTrackingService.ACTION_PAUSE)

    fun resume() = fireService(WalkTrackingService.ACTION_RESUME)

    fun startMeditation() = fireService(WalkTrackingService.ACTION_START_MEDITATION)

    /**
     * @param endMillis explicit Done-tap timestamp; null lets the
     *   service use its own clock. iOS parity
     *   `MeditationView.swift:609-615@db4196e` — the closing ceremony
     *   that plays after Done must not inflate the recorded interval.
     */
    fun endMeditation(endMillis: Long?) {
        val intent = baseIntent(WalkTrackingService.ACTION_END_MEDITATION).apply {
            if (endMillis != null) {
                putExtra(WalkTrackingService.EXTRA_END_MILLIS, endMillis)
            }
        }
        context.startService(intent)
    }

    fun finish() = fireService(WalkTrackingService.ACTION_FINISH)

    fun discard() = fireService(WalkTrackingService.ACTION_DISCARD)

    fun markWaypoint(label: String?, icon: String?) {
        val intent = baseIntent(WalkTrackingService.ACTION_MARK_WAYPOINT).apply {
            if (label != null) putExtra(WalkTrackingService.EXTRA_WAYPOINT_LABEL, label)
            if (icon != null) putExtra(WalkTrackingService.EXTRA_WAYPOINT_ICON, icon)
        }
        context.startService(intent)
    }

    fun setIntention(text: String) {
        val intent = baseIntent(WalkTrackingService.ACTION_SET_INTENTION).apply {
            putExtra(WalkTrackingService.EXTRA_INTENTION, text)
        }
        context.startService(intent)
    }

    private fun fireService(action: String) {
        context.startService(baseIntent(action))
    }

    private fun baseIntent(action: String): Intent =
        Intent(context, WalkTrackingService::class.java).apply { this.action = action }
}
