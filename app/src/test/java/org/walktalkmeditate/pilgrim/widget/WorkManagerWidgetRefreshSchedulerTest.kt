// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.widget

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage 2-F lesson regression: any WorkRequest builder MUST be
 * exercised on the production class via Robolectric, NOT a fake
 * scheduler — fakes hide the `Expedited + BatteryNotLow` IllegalArg
 * that ships through 6 review cycles otherwise.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WorkManagerWidgetRefreshSchedulerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @Test
    fun `scheduleRefresh builds an expedited request without crashing`() {
        val scheduler = WorkManagerWidgetRefreshScheduler(context)

        // Would throw IllegalArgumentException if expedited work +
        // battery-not-low constraint combo regresses (Stage 2-F lesson).
        scheduler.scheduleRefresh()

        val workManager = WorkManager.getInstance(context)
        val workInfos = workManager
            .getWorkInfosForUniqueWork(WorkManagerWidgetRefreshScheduler.UNIQUE_WORK_NAME)
            .get()
        assertEquals(1, workInfos.size)
    }

    @Test
    fun `back-to-back scheduleRefresh calls coalesce under KEEP policy`() {
        val scheduler = WorkManagerWidgetRefreshScheduler(context)

        scheduler.scheduleRefresh()
        scheduler.scheduleRefresh()
        scheduler.scheduleRefresh()

        // KEEP policy means the second + third calls are no-ops while
        // the first is enqueued — there's still exactly one WorkInfo
        // under the unique name.
        val workInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(WorkManagerWidgetRefreshScheduler.UNIQUE_WORK_NAME)
            .get()
        assertEquals(1, workInfos.size)
    }

    @Test
    fun `scheduleMidnightRefresh enqueues a delayed work request`() {
        val scheduler = WorkManagerWidgetRefreshScheduler(context)

        scheduler.scheduleMidnightRefresh()

        val workInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(WorkManagerWidgetRefreshScheduler.MIDNIGHT_WORK_NAME)
            .get()
        assertEquals(1, workInfos.size)
    }

    @Test
    fun `scheduleMidnightRefresh REPLACE policy keeps exactly one pending`() {
        val scheduler = WorkManagerWidgetRefreshScheduler(context)

        // Three rapid arms — REPLACE means the latest wins; the queue
        // still contains exactly one pending midnight worker.
        scheduler.scheduleMidnightRefresh()
        scheduler.scheduleMidnightRefresh()
        scheduler.scheduleMidnightRefresh()

        val workInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(WorkManagerWidgetRefreshScheduler.MIDNIGHT_WORK_NAME)
            .get()
        assertEquals(1, workInfos.size)
    }
}
