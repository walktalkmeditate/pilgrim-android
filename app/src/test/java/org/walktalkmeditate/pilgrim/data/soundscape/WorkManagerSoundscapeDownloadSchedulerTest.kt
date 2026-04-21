// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.soundscape

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
 * Mandatory `.build()` exercise for soundscape's WorkRequest
 * (CLAUDE.md: "any PR constructing a WorkRequest MUST include a
 * Robolectric test that calls `.build()` on the production class"
 * — Stage 2-D lesson).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WorkManagerSoundscapeDownloadSchedulerTest {

    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @Test fun `enqueue builds a request without crashing`() {
        val scheduler = WorkManagerSoundscapeDownloadScheduler(context)
        scheduler.enqueue("forest-morning")

        val wm = WorkManager.getInstance(context)
        val infos = wm.getWorkInfosForUniqueWork(
            SoundscapeDownloadWorker.uniqueWorkName("forest-morning"),
        ).get()
        assertEquals(1, infos.size)
    }

    @Test fun `enqueue twice with KEEP keeps the first`() {
        val scheduler = WorkManagerSoundscapeDownloadScheduler(context)
        scheduler.enqueue("river")
        val firstId = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(SoundscapeDownloadWorker.uniqueWorkName("river"))
            .get().first().id

        scheduler.enqueue("river")

        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(SoundscapeDownloadWorker.uniqueWorkName("river"))
            .get()
        assertEquals(1, infos.size)
        assertEquals(firstId, infos.first().id)
    }

    @Test fun `retry replaces a failed request`() {
        val scheduler = WorkManagerSoundscapeDownloadScheduler(context)
        scheduler.enqueue("rain")
        val firstId = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(SoundscapeDownloadWorker.uniqueWorkName("rain"))
            .get().first().id

        scheduler.retry("rain")

        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(SoundscapeDownloadWorker.uniqueWorkName("rain"))
            .get()
        assertEquals(true, infos.any { it.id != firstId })
    }

    @Test fun `cancel cancels the unique work`() {
        val scheduler = WorkManagerSoundscapeDownloadScheduler(context)
        scheduler.enqueue("ocean")

        scheduler.cancel("ocean")

        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(SoundscapeDownloadWorker.uniqueWorkName("ocean"))
            .get()
        assertEquals(WorkInfo.State.CANCELLED, infos.last().state)
    }
}
