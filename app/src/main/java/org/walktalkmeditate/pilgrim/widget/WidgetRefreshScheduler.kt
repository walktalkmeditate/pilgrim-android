// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.widget

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules a one-shot refresh of the home-screen widget. Called from
 * `WalkViewModel.finishWalk` so the widget reflects the just-completed
 * walk within the success-criteria 1-minute budget.
 */
interface WidgetRefreshScheduler {
    fun scheduleRefresh()
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

    companion object {
        const val UNIQUE_WORK_NAME = "widget_refresh"
    }
}
