// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.permissions

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.walktalkmeditate.pilgrim.permissions.PermissionAskedStore

/**
 * Sync-snapshot adapter over [PermissionAskedStore]. `runBlocking`
 * reads are acceptable: the DataStore payload is 3 booleans, first
 * emission lands in <1ms, and callers run on Main only during user
 * interaction (post-permission-callback recompute) where the cost is
 * imperceptible.
 */
@Singleton
class PermissionAskedStoreAdapter @Inject constructor(
    private val store: PermissionAskedStore,
) : AskedFlagSource {
    override fun isAsked(key: PermissionAskedStore.Key): Boolean =
        runBlocking { store.askedFlow(key).first() }

    override suspend fun markAsked(key: PermissionAskedStore.Key) =
        store.markAsked(key)
}
