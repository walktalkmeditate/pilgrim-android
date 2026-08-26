// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.core.threads.AutoTranscriptionSkipReason
import org.walktalkmeditate.pilgrim.core.threads.FakeAutoTranscriptionSkipState
import org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkState

/**
 * U6: two of the parity spec's five [org.walktalkmeditate.pilgrim.core.threads.AutoTranscriptionSkipState]
 * clear-sites — see [AutoTranscriptionSkipClearObserver]'s KDoc for the
 * full five-site mapping.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AutoTranscriptionSkipClearObserverTest {

    private lateinit var skipState: FakeAutoTranscriptionSkipState
    private lateinit var stateFlow: MutableStateFlow<WalkState>
    private lateinit var observedFlow: CountingStateFlow<WalkState>
    private lateinit var observerScope: CoroutineScope

    @Before
    fun setUp() {
        skipState = FakeAutoTranscriptionSkipState()
        stateFlow = MutableStateFlow(WalkState.Idle)
        observedFlow = CountingStateFlow(stateFlow)
        observerScope = CoroutineScope(SupervisorJob() + TestRealTimeDispatcher.instance)
    }

    @After
    fun tearDown() {
        observerScope.coroutineContext[Job]?.cancel()
    }

    private fun buildObserver(): AutoTranscriptionSkipClearObserver {
        val observer = AutoTranscriptionSkipClearObserver(
            walkState = observedFlow,
            scope = observerScope,
            skipState = skipState,
        )
        awaitCollectorAttached()
        return observer
    }

    private fun awaitCollectorAttached() = runBlocking {
        withTimeout(COLLECTOR_SUBSCRIBE_TIMEOUT_MS) { observedFlow.processed.first { it >= 1 } }
    }

    private fun awaitUntil(timeoutMs: Long = WAIT_MS, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate() && System.currentTimeMillis() < deadline) Thread.sleep(10L)
    }

    @Test
    fun `cold-process initial Idle emission is not a transition - does not clear`() {
        skipState.setSkipped()
        buildObserver()
        Thread.sleep(SETTLE_MS)
        // The observer's firstEmission latch swallows the initial cold
        // Idle — a pre-existing skip flag from before this observer even
        // started must survive it (there was no real transition).
        assertEquals(AutoTranscriptionSkipReason.LowBattery, skipState.skipReason.value)
    }

    @Test
    fun `starting a fresh walk clears a stale skip reason from a previous session`() {
        skipState.setSkipped()
        buildObserver()

        stateFlow.value = WalkState.Active(WalkAccumulator(walkId = 1L, startedAt = 0L))

        awaitUntil { skipState.skipReason.value == null }
        assertNull(skipState.skipReason.value)
    }

    @Test
    fun `resuming from Paused to Active is not a fresh start - does not clear`() {
        buildObserver()
        stateFlow.value = WalkState.Active(WalkAccumulator(walkId = 1L, startedAt = 0L))
        stateFlow.value = WalkState.Paused(WalkAccumulator(walkId = 1L, startedAt = 0L), pausedAt = 100L)
        awaitUntil { stateFlow.value is WalkState.Paused }
        skipState.setSkipped()

        stateFlow.value = WalkState.Active(WalkAccumulator(walkId = 1L, startedAt = 0L))
        Thread.sleep(SETTLE_MS)

        assertEquals(
            "resume must not clear — only a genuinely fresh start does",
            AutoTranscriptionSkipReason.LowBattery,
            skipState.skipReason.value,
        )
    }

    @Test
    fun `discarding a walk (transition to Idle) clears the skip reason`() {
        buildObserver()
        stateFlow.value = WalkState.Active(WalkAccumulator(walkId = 1L, startedAt = 0L))
        awaitUntil { stateFlow.value is WalkState.Active }
        skipState.setSkipped()

        stateFlow.value = WalkState.Idle

        awaitUntil { skipState.skipReason.value == null }
        assertNull(skipState.skipReason.value)
    }

    @Test
    fun `finishing a walk (transition to Finished) does NOT clear - it must survive into the summary`() {
        buildObserver()
        stateFlow.value = WalkState.Active(WalkAccumulator(walkId = 1L, startedAt = 0L))
        awaitUntil { stateFlow.value is WalkState.Active }
        skipState.setSkipped()

        stateFlow.value = WalkState.Finished(
            WalkAccumulator(walkId = 1L, startedAt = 0L),
            endedAt = 1_000L,
        )
        Thread.sleep(SETTLE_MS)

        assertEquals(
            "Finished must not clear the flag — the summary screen needs it to show the banner",
            AutoTranscriptionSkipReason.LowBattery,
            skipState.skipReason.value,
        )
    }

    private companion object {
        const val WAIT_MS = 3_000L
        const val SETTLE_MS = 150L
        const val COLLECTOR_SUBSCRIBE_TIMEOUT_MS = 15_000L
    }
}
