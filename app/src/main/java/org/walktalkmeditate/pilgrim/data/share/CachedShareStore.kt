// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Stage 8-A: DataStore-backed per-walk share cache. One preference
 * key per walk, value is a JSON blob (single atomic update vs five
 * separate writes).
 *
 * Key format: `share_cache_<uuid-without-dashes>`. DataStore
 * Preferences keys are restricted to valid identifiers; stripping
 * hyphens from the walk UUID keeps keys safe and human-readable.
 *
 * Observer pattern: `observe(walkUuid)` returns
 * `Flow<CachedShare?>` — null when no cache exists for the walk.
 * Expiry evaluation happens at read time via
 * [CachedShare.isExpiredAt] in the consumer (VM / UI).
 */
@Singleton
class CachedShareStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    fun observe(walkUuid: String): Flow<CachedShare?> =
        context.cachedShareDataStore.data
            .map { prefs -> prefs[keyFor(walkUuid)]?.let(::decode) }
            .distinctUntilChanged()

    suspend fun put(walkUuid: String, share: CachedShare) {
        val blob = json.encodeToString(CachedSharePrefs.serializer(), share.toPrefs())
        context.cachedShareDataStore.edit { prefs ->
            prefs[keyFor(walkUuid)] = blob
        }
    }

    suspend fun clear(walkUuid: String) {
        context.cachedShareDataStore.edit { prefs ->
            prefs.remove(keyFor(walkUuid))
        }
    }

    private fun decode(blob: String): CachedShare? = try {
        json.decodeFromString(CachedSharePrefs.serializer(), blob).toDomain()
    } catch (ce: kotlinx.coroutines.CancellationException) {
        // Flow cancellation during collect would propagate through
        // the map operator's invocation of decode; kotlin stdlib's
        // `runCatching { }` swallows CE (Stage 5-C lesson), so we
        // use a plain try/catch with explicit CE re-throw.
        throw ce
    } catch (_: Throwable) {
        null
    }

    private fun keyFor(walkUuid: String) =
        stringPreferencesKey("share_cache_" + walkUuid.replace("-", ""))

    /**
     * JSON-persisted shape. Kept separate from [CachedShare] so the
     * on-disk format can evolve independently of the in-memory type
     * (e.g., if iOS adds a `viewCount` field we can accept + decode
     * without breaking Android readers).
     */
    @Serializable
    private data class CachedSharePrefs(
        val url: String,
        val id: String,
        @SerialName("expiry_ms") val expiryMs: Long,
        @SerialName("share_date_ms") val shareDateMs: Long,
        @SerialName("expiry_option") val expiryOption: String? = null,
    ) {
        fun toDomain() = CachedShare(
            url = url,
            id = id,
            expiryEpochMs = expiryMs,
            shareDateEpochMs = shareDateMs,
            expiryOption = ExpiryOption.fromCacheKey(expiryOption),
        )
    }

    private fun CachedShare.toPrefs() = CachedSharePrefs(
        url = url,
        id = id,
        expiryMs = expiryEpochMs,
        shareDateMs = shareDateEpochMs,
        expiryOption = expiryOption?.cacheKey,
    )
}

private val Context.cachedShareDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "share_cache",
)
