// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim

import android.app.Application
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.audio.model.ModelDownloadWork
import org.walktalkmeditate.pilgrim.audio.model.WhisperModelDownloadScheduler

/**
 * Pins [ensureModelDownloadOnce]'s latch-on-success contract: the flag
 * latches only when `ensureEnqueued` returns normally; failure and
 * cancellation reset it so the next resume re-fires (KEEP-idempotent,
 * so an extra fire is harmless); the CAS keeps concurrent resumes to a
 * single in-flight call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EnsureModelDownloadOnceTest {

    private class ScriptedScheduler(
        private val behavior: suspend () -> Unit = {},
    ) : WhisperModelDownloadScheduler {
        var ensureEnqueuedCalls = 0
            private set

        override suspend fun ensureEnqueued() {
            ensureEnqueuedCalls++
            behavior()
        }

        override suspend fun retry() = Unit
        override suspend fun setCellularOverride(enabled: Boolean) = Unit
        override fun observeCellularOverride(): Flow<Boolean> = flowOf(false)
        override fun observe(): Flow<ModelDownloadWork?> = flowOf(null)
    }

    @Test
    fun `success latches the flag and dedupes later calls`() = runTest {
        val scheduler = ScriptedScheduler()
        val flag = AtomicBoolean(false)

        ensureModelDownloadOnce(scheduler, flag)
        ensureModelDownloadOnce(scheduler, flag)

        assertTrue(flag.get())
        assertEquals(1, scheduler.ensureEnqueuedCalls)
    }

    @Test
    fun `failure resets the flag so the next resume re-fires`() = runTest {
        var failFirst = true
        val scheduler = ScriptedScheduler {
            if (failFirst) {
                failFirst = false
                throw IllegalStateException("WorkManager not ready")
            }
        }
        val flag = AtomicBoolean(false)

        ensureModelDownloadOnce(scheduler, flag)
        assertFalse("failure must reset the flag", flag.get())

        ensureModelDownloadOnce(scheduler, flag)
        assertTrue(flag.get())
        assertEquals(2, scheduler.ensureEnqueuedCalls)
    }

    @Test
    fun `cancellation resets the flag so the next resume re-fires`() = runTest {
        val scheduler = ScriptedScheduler { awaitCancellation() }
        val flag = AtomicBoolean(false)

        val job = launch { ensureModelDownloadOnce(scheduler, flag) }
        runCurrent()
        assertEquals(1, scheduler.ensureEnqueuedCalls)
        job.cancel()
        job.join()

        assertFalse("cancellation must reset the flag", flag.get())
        assertTrue(job.isCancelled)
    }

    @Test
    fun `CAS keeps concurrent resumes to a single in-flight call`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val scheduler = ScriptedScheduler { gate.await() }
        val flag = AtomicBoolean(false)

        val first = launch { ensureModelDownloadOnce(scheduler, flag) }
        runCurrent()
        val second = launch { ensureModelDownloadOnce(scheduler, flag) }
        runCurrent()
        gate.complete(Unit)
        first.join()
        second.join()

        assertTrue(flag.get())
        assertEquals(1, scheduler.ensureEnqueuedCalls)
    }
}
