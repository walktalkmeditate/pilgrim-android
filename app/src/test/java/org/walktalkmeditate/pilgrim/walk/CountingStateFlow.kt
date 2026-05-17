// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk

import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Test-only [StateFlow] decorator that exposes how many emissions a
 * downstream collector has fully handled.
 *
 * The Walk*Observer classes swallow their FIRST collected value
 * unconditionally (the `firstEmission` latch — at real app start that
 * value is the cold-process `Idle`, a no-op rather than a transition).
 * A test that mutates the source `StateFlow` before the observer's IO
 * collector has consumed that first value loses the real transition to
 * StateFlow conflation: the mutated value becomes emission #1 and the
 * latch eats it. The old guard was a blind `Thread.sleep` sized to
 * "probably" cover subscribe + first delivery — which flaked on
 * saturated CI runners where neither had happened in the window.
 *
 * Wrapping the source in this and awaiting [processed] `>= 1` is a
 * deterministic handshake: [processed] increments only AFTER the
 * downstream collector returns from handling a value, so reaching 1
 * proves the observer has finished the initial `Idle` (its
 * `firstEmission` latch is now spent) — regardless of runner load and
 * with no guessed timeout.
 */
internal class CountingStateFlow<T>(
    private val delegate: StateFlow<T>,
) : StateFlow<T> by delegate {
    private val _processed = MutableStateFlow(0)

    /** Running count of emissions the downstream collector has fully handled. */
    val processed: StateFlow<Int> = _processed.asStateFlow()

    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        delegate.collect(
            object : FlowCollector<T> {
                override suspend fun emit(value: T) {
                    collector.emit(value)
                    _processed.update { it + 1 }
                }
            },
        )
    }
}
