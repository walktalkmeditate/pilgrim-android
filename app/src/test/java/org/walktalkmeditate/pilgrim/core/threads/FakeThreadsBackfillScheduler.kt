// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

/**
 * In-memory test double for [ThreadsBackfillScheduler]. Deliberately
 * independent of any [ThreadsPreferencesRepository] fake — callers that
 * want to prove a toggle routes through [setEnabled] (rather than writing
 * a preferences repository directly) can assert on [setEnabledCalls] and
 * confirm no unrelated preferences fake changed as a side effect.
 */
class FakeThreadsBackfillScheduler : ThreadsBackfillScheduler {
    val setEnabledCalls = mutableListOf<Boolean>()
    var ensureScheduledCallCount = 0
        private set

    override fun ensureScheduled() {
        ensureScheduledCallCount++
    }

    override suspend fun setEnabled(enabled: Boolean) {
        setEnabledCalls += enabled
    }
}
