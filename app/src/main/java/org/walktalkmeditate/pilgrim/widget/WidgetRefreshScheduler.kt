// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.widget

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules a one-shot refresh of the home-screen widget. Called from
 * `WalkViewModel.finishWalk` so the widget reflects the just-completed
 * walk within the success-criteria 1-minute budget.
 */
interface WidgetRefreshScheduler {
    fun scheduleRefresh()

    /**
     * Enqueue a one-shot refresh at the next local midnight + a small
     * jitter buffer so the relative-date label transitions from
     * "Today" → "Yesterday" promptly and the empty-state mantra
     * rotates daily. The refresh worker re-schedules this on each
     * run so the chain is self-perpetuating across multi-day spans.
     *
     * Manifest-registered `ACTION_DATE_CHANGED` / `ACTION_TIME_SET`
     * BroadcastReceivers do NOT fire on API 26+ apps (implicit-
     * broadcast blocklist), so we use WorkManager instead. Persists
     * across reboots via WorkManager's database-backed queue.
     */
    fun scheduleMidnightRefresh()
}

@Singleton
class WorkManagerWidgetRefreshScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : WidgetRefreshScheduler {

    override fun scheduleRefresh() {
        // Stage 2-F lesson: pairing setExpedited with
        // setRequiresBatteryNotLow crashes at WorkRequest.build() —
        // expedited is only allowed alongside network or storage
        // constraints. Storage-not-low only (Worker reads Room).
        val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        // KEEP + unique work name so back-to-back finishWalks coalesce —
        // the worker reads "most recent finished walk" itself, no
        // per-walk input data, so dedup is correct (latest walk wins
        // either way).
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    override fun scheduleMidnightRefresh() {
        val delayMs = millisUntilNextMidnight()
        val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder().setRequiresStorageNotLow(true).build(),
            )
            .build()
        // REPLACE policy on the midnight slot so a re-schedule from
        // PilgrimApp.onCreate (after a reboot) supersedes any stale
        // pending midnight worker without leaving duplicates behind.
        WorkManager.getInstance(context).enqueueUniqueWork(
            MIDNIGHT_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /**
     * Milliseconds from now until the next local-midnight + 1 minute
     * jitter buffer. The buffer ensures the worker fires AFTER the
     * date has rolled over (small clock-skew safety).
     */
    private fun millisUntilNextMidnight(): Long {
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        val nextMidnight = LocalDate.now(zone)
            .plusDays(1)
            .atStartOfDay(zone)
            .plusMinutes(MIDNIGHT_JITTER_MINUTES)
        return Duration.between(now, nextMidnight).toMillis().coerceAtLeast(0)
    }

    companion object {
        const val UNIQUE_WORK_NAME = "widget_refresh"
        const val MIDNIGHT_WORK_NAME = "widget_refresh_midnight"
        private const val MIDNIGHT_JITTER_MINUTES = 1L
    }
}
