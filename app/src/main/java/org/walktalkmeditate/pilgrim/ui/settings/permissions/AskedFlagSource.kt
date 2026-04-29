// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.permissions

import org.walktalkmeditate.pilgrim.permissions.PermissionAskedStore

/**
 * Sync-friendly view of [PermissionAskedStore] so [PermissionsCardViewModel]
 * can compute status without suspending. Backed by a runBlocking
 * adapter (see PermissionAskedStoreAdapter) — DataStore's first read is
 * sub-ms for our 3-boolean payload.
 */
interface AskedFlagSource {
    fun isAsked(key: PermissionAskedStore.Key): Boolean
    suspend fun markAsked(key: PermissionAskedStore.Key)
}
