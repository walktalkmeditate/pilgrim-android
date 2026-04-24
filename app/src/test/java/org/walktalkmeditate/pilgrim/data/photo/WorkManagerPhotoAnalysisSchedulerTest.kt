// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.photo

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Per CLAUDE.md's platform-object builder rule (see Stage 2-F
 * regression): this test invokes the production
 * [WorkManagerPhotoAnalysisScheduler] directly — not a fake — so the
 * `OneTimeWorkRequestBuilder.build()` path actually runs. A builder
 * constraint mismatch (e.g. a future attempt to pair Expedited with
 * BatteryNotLow) would throw IllegalArgumentException here rather
 * than silently shipping on-device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WorkManagerPhotoAnalysisSchedulerTest {

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
    fun `scheduleForWalk enqueues a single work request`() {
        val scheduler = WorkManagerPhotoAnalysisScheduler(context)

        scheduler.scheduleForWalk(walkId = 42L)

        val workManager = WorkManager.getInstance(context)
        val workInfos = workManager
            .getWorkInfosForUniqueWork("photo-analysis-walk-42")
            .get()
        assertEquals(1, workInfos.size)
    }

    @Test
    fun `scheduleForWalk twice with KEEP keeps the original request`() {
        val scheduler = WorkManagerPhotoAnalysisScheduler(context)
        scheduler.scheduleForWalk(walkId = 7L)
        val firstId = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("photo-analysis-walk-7")
            .get()
            .single()
            .id

        scheduler.scheduleForWalk(walkId = 7L)

        val infos: List<WorkInfo> = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("photo-analysis-walk-7")
            .get()
        assertEquals(1, infos.size)
        assertEquals(
            "KEEP policy must preserve the in-flight request id",
            firstId,
            infos.single().id,
        )
    }

    @Test
    fun `scheduleForWalk for different walks enqueues independent requests`() {
        val scheduler = WorkManagerPhotoAnalysisScheduler(context)

        scheduler.scheduleForWalk(walkId = 1L)
        scheduler.scheduleForWalk(walkId = 2L)

        val wm = WorkManager.getInstance(context)
        assertEquals(
            1,
            wm.getWorkInfosForUniqueWork("photo-analysis-walk-1").get().size,
        )
        assertEquals(
            1,
            wm.getWorkInfosForUniqueWork("photo-analysis-walk-2").get().size,
        )
    }
}
