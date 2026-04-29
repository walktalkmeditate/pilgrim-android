// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.permissions

import kotlinx.coroutines.flow.Flow
import org.walktalkmeditate.pilgrim.permissions.PermissionAskedStore

/**
 * Flow-shaped view of [PermissionAskedStore] so [PermissionsCardViewModel]
 * can compose state without runBlocking on Main. Backed in production
 * by [PermissionAskedStoreAdapter]; tests provide an in-memory fake.
 */
interface AskedFlagSource {
    fun asked(key: PermissionAskedStore.Key): Flow<Boolean>
    suspend fun markAsked(key: PermissionAskedStore.Key)
}
