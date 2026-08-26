// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression coverage for the Stage 2-F crash class (an expedited
 * `OneTimeWorkRequest` paired with `setRequiresBatteryNotLow(true)`
 * throws `IllegalArgumentException` at `.build()`) — precedent
 * `WorkManagerTranscriptionSchedulerTest`. The backfill request must
 * build successfully WITH the BatteryNotLow constraint, which is only
 * possible if it is NOT expedited: `.build()` succeeding here is itself
 * proof of that (an expedited+BatteryNotLow combination cannot reach a
 * successful build at all).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ThreadsBackfillWorkRequestTest {

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
    fun `ensureScheduled builds a non-expedited request with BatteryNotLow, without crashing`() = runTest {
        val preferences = FakeThreadsPreferencesRepository()
        val scheduler = WorkManagerThreadsBackfillScheduler(context, preferences)

        // Would throw IllegalArgumentException if a future edit
        // accidentally re-adds setExpedited alongside BatteryNotLow.
        scheduler.ensureScheduled()

        val workManager = WorkManager.getInstance(context)
        val workInfos = workManager.getWorkInfosForUniqueWork(WorkManagerThreadsBackfillScheduler.UNIQUE_WORK_NAME).get()
        assertEquals(1, workInfos.size)
        assertTrue(workInfos.single().constraints.requiresBatteryNotLow())
    }

    @Test
    fun `ensureScheduled is idempotent - KEEP policy, not REPLACE`() = runTest {
        val preferences = FakeThreadsPreferencesRepository()
        val scheduler = WorkManagerThreadsBackfillScheduler(context, preferences)
        val workManager = WorkManager.getInstance(context)

        scheduler.ensureScheduled()
        val firstId = workManager.getWorkInfosForUniqueWork(WorkManagerThreadsBackfillScheduler.UNIQUE_WORK_NAME)
            .get().single().id

        scheduler.ensureScheduled()
        val secondId = workManager.getWorkInfosForUniqueWork(WorkManagerThreadsBackfillScheduler.UNIQUE_WORK_NAME)
            .get().single().id

        assertEquals("KEEP must not replace an already-enqueued/running sweep", firstId, secondId)
    }

    @Test
    fun `setEnabled(true) clears completion and checkpoint, then schedules`() = runTest {
        val preferences = FakeThreadsPreferencesRepository()
        preferences.setBackfillCompleted(version = TranscriptContext.ANALYSIS_VERSION, atImportGeneration = 0)
        preferences.setBackfillCheckpoint(
            BackfillCheckpoint(watermark = "u-010", forImportGeneration = 0, atAnalysisVersion = TranscriptContext.ANALYSIS_VERSION),
        )
        val scheduler = WorkManagerThreadsBackfillScheduler(context, preferences)

        scheduler.setEnabled(true)

        assertEquals(null, preferences.backfillCompletedAtVersion())
        assertEquals(BackfillCheckpoint.EMPTY, preferences.backfillCheckpoint())
        assertTrue(preferences.threadsAfterWalks.value)
        val workInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(WorkManagerThreadsBackfillScheduler.UNIQUE_WORK_NAME).get()
        assertEquals(1, workInfos.size)
    }

    @Test
    fun `setEnabled(false) writes the preference without resetting completion`() = runTest {
        val preferences = FakeThreadsPreferencesRepository()
        preferences.setBackfillCompleted(version = TranscriptContext.ANALYSIS_VERSION, atImportGeneration = 0)
        val scheduler = WorkManagerThreadsBackfillScheduler(context, preferences)

        scheduler.setEnabled(false)

        assertFalse(preferences.threadsAfterWalks.value)
        assertEquals(TranscriptContext.ANALYSIS_VERSION, preferences.backfillCompletedAtVersion())
    }
}
