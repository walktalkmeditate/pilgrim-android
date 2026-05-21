// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Belt-and-suspenders FGS revival mechanism. The `:tracker` process
 * relies on START_REDELIVER_INTENT to revive `WalkTrackingService`
 * after an OEM kill. That works in the common case; this watchdog
 * covers the rare cases where revival fails (hardened ROMs, repeated
 * RAM pressure, OEM service-restart suppression).
 *
 * **How it works.** While a walk is active, an [AlarmManager] alarm
 * fires every [INTERVAL_MS] (5 min) at a [WatchdogReceiver]. The
 * receiver checks the cross-process FGS-alive state via
 * [WalkTrackingService.isFgsAlive] and the persisted active walk in
 * Room. When BOTH are true, the walk is in good shape and the
 * watchdog just re-schedules. When the walk is active in Room but
 * the FGS has vanished, the receiver fires a fresh
 * `startForegroundService(ACTION_START)` (without `EXTRA_FRESH_START`
 * → service restores the walk from Room) to wake the tracker back
 * up.
 *
 * **Scheduling lifecycle.**
 *  - [schedule]: called when a walk starts. Idempotent; cancels any
 *    prior alarm first so a rapid restart doesn't double-schedule.
 *  - [cancel]: called on finish / discard / cold-launch recovery.
 *  - Receiver re-schedules itself on each fire (chained one-shot).
 *
 * **Why [setAndAllowWhileIdle] instead of [setExactAndAllowWhileIdle].**
 * Inexact is acceptable here — being a few minutes late on the
 * revival check doesn't matter when we're already in the
 * watchdog-of-last-resort territory. Exact alarms require
 * `SCHEDULE_EXACT_ALARM` permission on API 31+, which the user
 * would have to grant via Settings. Inexact alarms don't.
 * `setAndAllowWhileIdle` bypasses Doze (subject to OS-enforced ~9
 * min minimum interval during deep Doze), which is what we need on
 * long screen-off walks.
 */
@Singleton
class WalkTrackingWatchdog @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun schedule() {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent()
        am.cancel(pending)
        val triggerAt = System.currentTimeMillis() + INTERVAL_MS
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            Log.i(TAG, "watchdog scheduled for ${INTERVAL_MS / 1000}s from now")
        } catch (t: Throwable) {
            // OEM ROMs occasionally throw SecurityException for alarm
            // ops despite the permission being implicit. Swallow so a
            // failed schedule doesn't crash the walk-start path; the
            // user falls back to the un-watchdogged baseline behavior.
            Log.w(TAG, "watchdog schedule failed", t)
        }
    }

    fun cancel() {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        try {
            am.cancel(pendingIntent())
            Log.i(TAG, "watchdog cancelled")
        } catch (t: Throwable) {
            Log.w(TAG, "watchdog cancel failed", t)
        }
    }

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(context, WatchdogReceiver::class.java).apply {
            action = ACTION_WATCHDOG_CHECK
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val ACTION_WATCHDOG_CHECK =
            "org.walktalkmeditate.pilgrim.walk.WalkTrackingWatchdog.CHECK"
        private const val TAG = "WalkTrackingWatchdog"
        private const val REQUEST_CODE = 1001

        /**
         * Check interval. 5 min is above the OS-enforced ~9 min
         * minimum during deep Doze (alarms in Doze are coalesced),
         * but small enough that a long walk gets at least one
         * revival attempt within minutes of an OEM kill.
         */
        const val INTERVAL_MS = 5L * 60L * 1000L
    }
}
