// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

interface OrphanSweeperScheduler {
    fun scheduleDaily()
}

@Singleton
class WorkManagerOrphanSweeperScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : OrphanSweeperScheduler {

    override fun scheduleDaily() {
        val request = PeriodicWorkRequestBuilder<OrphanSweeperWorker>(
            repeatInterval = 1L,
            repeatIntervalTimeUnit = TimeUnit.DAYS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_NAME,
            // KEEP: scheduling on every onCreate would otherwise reset
            // the period each launch, defeating the daily cadence.
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    private companion object {
        const val UNIQUE_NAME = "orphan-recording-sweeper"
    }
}
