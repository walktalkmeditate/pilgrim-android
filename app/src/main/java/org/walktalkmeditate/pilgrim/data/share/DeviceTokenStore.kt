// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
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
) : DeviceTokenSource {
    /**
     * Idempotent generate-or-read. `edit` serialises through the
     * DataStore actor — two concurrent first-launch callers both
     * observe the other's write under the actor's lock and converge
     * on the same UUID. The token is captured from the edit-block's
     * Preferences snapshot so we avoid a redundant second
     * `data.first()` read after the write.
     */
    override suspend fun getToken(): String {
        context.deviceTokenDataStore.data.first()[KEY]?.let { return it }
        var token: String? = null
        context.deviceTokenDataStore.edit { prefs ->
            val existing = prefs[KEY]
            if (existing != null) {
                token = existing
            } else {
                val fresh = UUID.randomUUID().toString()
                prefs[KEY] = fresh
                token = fresh
            }
        }
        return requireNotNull(token) {
            "device token missing after atomic generate-or-read"
        }
    }

    companion object {
        private val KEY = stringPreferencesKey("share_device_token")
    }
}

private val Context.deviceTokenDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "share_device_token",
    // Without this, a preferences_pb truncated mid-write (OS kill, disk
    // full) makes every read throw CorruptionException forever — and
    // this token gates the `X-Device-Token` header on the collective
    // counter, share, feedback, cairn, whisper and geocache calls. Each
    // of those would fail silently and permanently, with no path back
    // short of a reinstall. Resetting costs the device its identity with
    // the rate limiter (it gets a fresh UUID on the next read), which is
    // the cheap half of the trade: the token is not a secret and carries
    // no history. Same policy as the collective + ledger stores in
    // `CollectiveModule`.
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)
