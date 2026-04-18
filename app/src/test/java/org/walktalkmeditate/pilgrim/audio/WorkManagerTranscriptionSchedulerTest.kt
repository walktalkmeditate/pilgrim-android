// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for a Stage 2-D→2-E production crash: an expedited
 * `OneTimeWorkRequest` paired with `setRequiresBatteryNotLow(true)`
 * throws at `build()` with
 *
 *     IllegalArgumentException: Expedited jobs only support network
 *     and storage constraints
 *
 * The original code compiled and passed the existing test suite
 * (scheduling via a `FakeTranscriptionScheduler`), but the production
 * `WorkManagerTranscriptionScheduler` actually calls
 * `OneTimeWorkRequestBuilder.build()` — which only fails at runtime.
 * This test covers that gap by invoking `scheduleForWalk` against a
 * WorkManager test harness and asserting no exception is thrown.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WorkManagerTranscriptionSchedulerTest {

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
    fun `scheduleForWalk builds an expedited request without crashing`() {
        val scheduler = WorkManagerTranscriptionScheduler(context)

        // Would throw IllegalArgumentException if expedited work +
        // battery-not-low constraint combo regresses.
        scheduler.scheduleForWalk(walkId = 42L)

        val workManager = WorkManager.getInstance(context)
        val workInfos = workManager.getWorkInfosForUniqueWork("transcribe-walk-42").get()
        org.junit.Assert.assertEquals(1, workInfos.size)
    }
}
