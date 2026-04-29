// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.permissions

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import org.walktalkmeditate.pilgrim.permissions.PermissionAskedStore

@Singleton
class PermissionAskedStoreAdapter @Inject constructor(
    private val store: PermissionAskedStore,
) : AskedFlagSource {
    override fun asked(key: PermissionAskedStore.Key): Flow<Boolean> =
        store.askedFlow(key)

    override suspend fun markAsked(key: PermissionAskedStore.Key) =
        store.markAsked(key)
}
