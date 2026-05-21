// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.service.WalkTrackingService

/**
 * Broadcast handler for [WalkTrackingWatchdog]'s periodic alarm. Runs
 * in the UI process. Decides whether to revive the tracker FGS based
 * on the cross-process FGS-alive check and the persisted active walk
 * row in Room. Always re-schedules itself if a walk is still active,
 * so revival attempts continue at [WalkTrackingWatchdog.INTERVAL_MS]
 * cadence until the user finishes / discards the walk.
 *
 * Why a `goAsync` coroutine instead of inline work in `onReceive`:
 * Room reads + service start can suspend, and broadcast receivers
 * have a ~10s onReceive budget on Android. The
 * `BroadcastReceiver.PendingResult.finish()` hold-open mechanism is
 * the documented way to do bounded async work.
 */
@AndroidEntryPoint
class WatchdogReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: WalkRepository

    @Inject lateinit var watchdog: WalkTrackingWatchdog

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WalkTrackingWatchdog.ACTION_WATCHDOG_CHECK) return
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                handleCheck(context)
            } catch (t: Throwable) {
                Log.w(TAG, "watchdog onReceive failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handleCheck(context: Context) {
        val activeWalk = repository.getActiveWalk()
        if (activeWalk == null) {
            // The walk completed or was discarded — but our cancel()
            // path may have lost the race against this alarm firing.
            // Nothing to revive; do not re-schedule.
            Log.i(TAG, "watchdog: no active walk, stopping")
            return
        }
        val fgsAlive = WalkTrackingService.isFgsAlive(context)
        if (fgsAlive) {
            Log.i(TAG, "watchdog: FGS alive, walk=${activeWalk.id} healthy")
        } else {
            Log.w(TAG, "watchdog: FGS dead but walk=${activeWalk.id} still active, reviving")
            try {
                val startIntent = Intent(context, WalkTrackingService::class.java).apply {
                    action = WalkTrackingService.ACTION_START
                    // Omit EXTRA_FRESH_START — this is a revival path,
                    // not a UI-initiated fresh start. Service's
                    // startTracking treats no-fresh-start as a
                    // restoreActiveWalk + resume-pipeline flow.
                }
                ContextCompat.startForegroundService(context, startIntent)
            } catch (t: Throwable) {
                Log.w(TAG, "watchdog: startForegroundService failed", t)
            }
        }
        // Chain the next check. Walk is still active; keep watching.
        watchdog.schedule()
    }

    private companion object {
        const val TAG = "WatchdogReceiver"
    }
}
