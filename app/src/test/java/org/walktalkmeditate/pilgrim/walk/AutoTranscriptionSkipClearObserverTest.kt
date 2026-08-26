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
 *
 * Every multi-transition test synchronizes on [CountingStateFlow.processed]
 * after EACH `stateFlow.value = ...` assignment, not just the first
 * (`buildObserver`'s own handshake). A test that fires two transitions
 * back-to-back on the main thread (e.g. Active then Finished) races the
 * observer's async collector: if the collector hasn't yet processed the
 * FIRST transition (and updated its internal `previousInProgress`) by
 * the time the SECOND transition lands, StateFlow conflation can make
 * the collector observe only the final value — misclassifying a resume
 * or a terminal transition as a fresh start (or vice versa). Waiting for
 * an exact `processed` count after every transition removes the race
 * instead of hoping a fixed sleep is long enough.
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
        awaitProcessed(1)
        return observer
    }

    /**
     * Blocks until the observer's collector has fully handled the
     * [count]-th emission (its `firstEmission`/`previousInProgress`
     * bookkeeping for that emission is guaranteed committed) — the
     * exact handshake [awaitCollectorAttached] in the sibling
     * WalkLifecycleObserverTest/WalkFinalizationObserverTest files use
     * for their own first-emission case, generalized to every step.
     */
    private fun awaitProcessed(count: Int) = runBlocking {
        withTimeout(COLLECTOR_SUBSCRIBE_TIMEOUT_MS) { observedFlow.processed.first { it >= count } }
    }

    @Test
    fun `cold-process initial Idle emission is not a transition - does not clear`() {
        skipState.setSkipped()
        buildObserver()
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
        awaitProcessed(2)

        assertNull(skipState.skipReason.value)
    }

    @Test
    fun `resuming from Paused to Active is not a fresh start - does not clear`() {
        buildObserver()
        stateFlow.value = WalkState.Active(WalkAccumulator(walkId = 1L, startedAt = 0L))
        awaitProcessed(2)
        stateFlow.value = WalkState.Paused(WalkAccumulator(walkId = 1L, startedAt = 0L), pausedAt = 100L)
        awaitProcessed(3)
        skipState.setSkipped()

        stateFlow.value = WalkState.Active(WalkAccumulator(walkId = 1L, startedAt = 0L))
        awaitProcessed(4)

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
        awaitProcessed(2)
        skipState.setSkipped()

        stateFlow.value = WalkState.Idle
        awaitProcessed(3)

        assertNull(skipState.skipReason.value)
    }

    @Test
    fun `finishing a walk (transition to Finished) does NOT clear - it must survive into the summary`() {
        buildObserver()
        stateFlow.value = WalkState.Active(WalkAccumulator(walkId = 1L, startedAt = 0L))
        awaitProcessed(2)
        skipState.setSkipped()

        stateFlow.value = WalkState.Finished(
            WalkAccumulator(walkId = 1L, startedAt = 0L),
            endedAt = 1_000L,
        )
        awaitProcessed(3)

        assertEquals(
            "Finished must not clear the flag — the summary screen needs it to show the banner",
            AutoTranscriptionSkipReason.LowBattery,
            skipState.skipReason.value,
        )
    }

    private companion object {
        const val COLLECTOR_SUBSCRIBE_TIMEOUT_MS = 15_000L
    }
}
