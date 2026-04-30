// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.collective

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stage 11-B: detects when the collective walk total has crossed a
 * sacred-number threshold the user has not yet been shown, and exposes
 * the resulting [CollectiveMilestone] as a hot StateFlow.
 *
 * Persistence parity with iOS `@Published` semantics: the milestone
 * survives nav-away from Settings until the consumer calls [clear].
 *
 * The check is a SUSPENDING read of `lastSeenCollectiveWalks` (not a
 * StateFlow `.value` snapshot) so a process cold-start race can't fire
 * a stale milestone before DataStore has emitted the persisted value.
 */
@Singleton
class CollectiveMilestoneDetector @Inject constructor(
    private val storage: MilestoneStorage,
) : MilestoneChecking, MilestoneSurface {

    private val _milestone = MutableStateFlow<CollectiveMilestone?>(null)
    override val milestone: StateFlow<CollectiveMilestone?> = _milestone.asStateFlow()

    override suspend fun check(totalWalks: Int) {
        try {
            val lastSeen = storage.firstReadyLastSeenCollectiveWalks()
            for (number in CollectiveMilestone.SACRED_NUMBERS) {
                if (totalWalks >= number && lastSeen < number) {
                    storage.setLastSeenCollectiveWalks(number)
                    _milestone.value = CollectiveMilestone.forNumber(number)
                    break
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "milestone check failed", t)
        }
    }

    override fun clear() {
        _milestone.value = null
    }

    private companion object {
        const val TAG = "MilestoneDetector"
    }
}

/**
 * Single-method seam consumed by [CollectiveRepository] (Task 12) so
 * the repository can trigger milestone detection after a successful
 * stats fetch without the repository owning the StateFlow lifecycle
 * itself.
 */
interface MilestoneChecking {
    suspend fun check(totalWalks: Int)
}

/**
 * Read-side seam consumed by [SettingsViewModel] (Task 15). Exposes the
 * detector's pending-milestone StateFlow plus the imperative `clear()`
 * the dismiss action calls. Extracted as an interface (matching the
 * [MilestoneStorage] / [MilestoneChecking] pattern) so VM tests can
 * substitute a `MutableStateFlow`-backed fake without subclassing the
 * `@Singleton` detector or relying on `open` modifiers.
 */
interface MilestoneSurface {
    val milestone: StateFlow<CollectiveMilestone?>
    fun clear()
}

/**
 * Storage seam for the detector — the minimum subset of
 * [CollectiveCacheStore] the detector needs. Extracted so unit tests
 * can substitute a throwing fake without subclassing the @Singleton
 * concrete cache store.
 */
interface MilestoneStorage {
    suspend fun firstReadyLastSeenCollectiveWalks(): Int
    suspend fun setLastSeenCollectiveWalks(value: Int)
}
