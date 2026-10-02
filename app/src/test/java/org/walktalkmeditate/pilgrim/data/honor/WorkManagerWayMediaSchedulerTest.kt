// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The house builder rule (CLAUDE.md, `WorkManagerTranscriptionSchedulerTest`):
 * the real media `WorkRequest` is built and enqueued, never through a fake.
 * Its constraints are iOS's (S3 §5): any network, and no storage, battery,
 * or expedited setting, so the `Expedited + BatteryNotLow` crash class
 * can't arise. `KEEP` follows the gather already pending; "try again"
 * replaces it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WorkManagerWayMediaSchedulerTest {

    private lateinit var context: Context
    private lateinit var scheduler: WorkManagerWayMediaDownloadScheduler

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        scheduler = WorkManagerWayMediaDownloadScheduler(context)
    }

    private fun infos(): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(WayMediaDownloadWorker.uniqueWorkName(WAY_ID)).get()

    @Test
    fun `the real request builds with any network as its only constraint`() {
        val request = WorkManagerWayMediaDownloadScheduler.request(WAY_ID)

        val constraints = request.workSpec.constraints
        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        assertFalse(constraints.requiresStorageNotLow())
        assertFalse(constraints.requiresBatteryNotLow())
        assertFalse(constraints.requiresCharging())
        assertFalse("never expedited", request.workSpec.expedited)
        assertEquals(WAY_ID, request.workSpec.input.getString(WayMediaDownloadWorker.KEY_WAY_ID))
    }

    @Test
    fun `a gather enqueues one unique work for the Way, and its flow follows it`() = runBlocking {
        val work = scheduler.gather(WAY_ID, replace = false).first()

        assertEquals(1, infos().size)
        assertEquals(WayMediaWork.State.WAITING, work?.state)
        assertEquals(NetworkType.CONNECTED, infos().single().constraints.requiredNetworkType)
    }

    @Test
    fun `a second gather keeps the work pending, and try again replaces it`() = runBlocking {
        scheduler.gather(WAY_ID, replace = false)
        val pending = infos().single().id

        scheduler.gather(WAY_ID, replace = false)
        assertEquals(pending, infos().single().id)

        scheduler.gather(WAY_ID, replace = true)
        assertNotEquals(pending, infos().single { !it.state.isFinished }.id)
    }

    // KEEP kept a pending work, and it finished before the gather read the name's works.
    @Test
    fun `a kept work that has finished since is still the one followed`() {
        val enqueued = UUID.randomUUID()
        val kept = WorkInfo(UUID.randomUUID(), WorkInfo.State.SUCCEEDED, emptySet())

        assertEquals(kept.id, WorkManagerWayMediaDownloadScheduler.followedId(listOf(kept), enqueued))
    }

    @Test
    fun `the gather's own work wins, then a pending one`() {
        val enqueued = WorkInfo(UUID.randomUUID(), WorkInfo.State.ENQUEUED, emptySet())
        val pending = WorkInfo(UUID.randomUUID(), WorkInfo.State.RUNNING, emptySet())
        val finished = WorkInfo(UUID.randomUUID(), WorkInfo.State.CANCELLED, emptySet())

        assertEquals(enqueued.id, WorkManagerWayMediaDownloadScheduler.followedId(listOf(pending, enqueued), enqueued.id))
        assertEquals(pending.id, WorkManagerWayMediaDownloadScheduler.followedId(listOf(finished, pending), UUID.randomUUID()))
        val none = UUID.randomUUID()
        assertEquals(none, WorkManagerWayMediaDownloadScheduler.followedId(emptyList(), none))
    }

    @Test
    fun `cancel stops the Way's work`() = runBlocking {
        scheduler.gather(WAY_ID, replace = false)

        scheduler.cancel(WAY_ID)

        assertEquals(WorkInfo.State.CANCELLED, infos().single().state)
    }

    private companion object {
        const val WAY_ID = "share:Qoi4YmPHLN"
    }
}
