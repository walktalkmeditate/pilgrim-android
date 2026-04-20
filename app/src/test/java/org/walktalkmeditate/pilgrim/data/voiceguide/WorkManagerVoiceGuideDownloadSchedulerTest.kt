// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voiceguide

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
 * Smoke test that [WorkManagerVoiceGuideDownloadScheduler] builds its
 * `OneTimeWorkRequest` without exploding on runtime constraint
 * validation. Per CLAUDE.md: any PR constructing a `WorkRequest`
 * MUST call `.build()` on the production class in at least one
 * Robolectric test (see Stage 2-D → 2-E incident where a Fake-only
 * test suite hid an `Expedited + BatteryNotLow` crash through six
 * review cycles).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WorkManagerVoiceGuideDownloadSchedulerTest {

    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @Test fun `enqueue builds a request without crashing`() {
        val scheduler = WorkManagerVoiceGuideDownloadScheduler(context)
        scheduler.enqueue("morning-walk")

        val wm = WorkManager.getInstance(context)
        val infos = wm.getWorkInfosForUniqueWork(
            VoiceGuideDownloadWorker.uniqueWorkName("morning-walk"),
        ).get()
        assertEquals(1, infos.size)
    }

    @Test fun `enqueue twice with KEEP keeps the first`() {
        val scheduler = WorkManagerVoiceGuideDownloadScheduler(context)
        scheduler.enqueue("noon-sit")
        val firstId = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(VoiceGuideDownloadWorker.uniqueWorkName("noon-sit"))
            .get().first().id

        scheduler.enqueue("noon-sit") // second call — KEEP

        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(VoiceGuideDownloadWorker.uniqueWorkName("noon-sit"))
            .get()
        assertEquals(1, infos.size)
        assertEquals(firstId, infos.first().id)
    }

    @Test fun `retry replaces a failed request`() {
        val scheduler = WorkManagerVoiceGuideDownloadScheduler(context)
        scheduler.enqueue("evening-walk")
        val firstId = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(VoiceGuideDownloadWorker.uniqueWorkName("evening-walk"))
            .get().first().id

        scheduler.retry("evening-walk")

        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(VoiceGuideDownloadWorker.uniqueWorkName("evening-walk"))
            .get()
        // REPLACE cancels the old work and enqueues a new one; the most
        // recent one has a different id.
        assertEquals(true, infos.any { it.id != firstId })
    }

    @Test fun `cancel cancels the unique work`() {
        val scheduler = WorkManagerVoiceGuideDownloadScheduler(context)
        scheduler.enqueue("morning-walk")

        scheduler.cancel("morning-walk")

        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(VoiceGuideDownloadWorker.uniqueWorkName("morning-walk"))
            .get()
        assertEquals(WorkInfo.State.CANCELLED, infos.last().state)
    }
}
