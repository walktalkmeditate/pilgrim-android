// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Stage 8-A: persistent per-device UUID used as the `X-Device-Token`
 * header on share requests. Not a secret — the Cloudflare Worker
 * hashes it with a server-side salt for rate-limiting only.
 *
 * Exposed as a `suspend fun getToken()` so future flows beyond share
 * (e.g., feedback-trace tagging per iOS `deviceTokenForFeedback()`)
 * can reuse the same accessor without duplicating DataStore wiring.
 */
@Singleton
class DeviceTokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Idempotent generate-or-read. `edit` makes the check-then-write
     * atomic — two concurrent first-launch callers will both see the
     * same UUID after their edits settle because each observes the
     * other's `edit[KEY]` under the DataStore actor's serialization.
     */
    suspend fun getToken(): String {
        context.deviceTokenDataStore.data.first()[KEY]?.let { return it }
        context.deviceTokenDataStore.edit { prefs ->
            if (prefs[KEY] == null) {
                prefs[KEY] = UUID.randomUUID().toString()
            }
        }
        return requireNotNull(context.deviceTokenDataStore.data.first()[KEY]) {
            "device token missing after atomic generate-or-read"
        }
    }

    companion object {
        private val KEY = stringPreferencesKey("share_device_token")
    }
}

private val Context.deviceTokenDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "share_device_token",
)
