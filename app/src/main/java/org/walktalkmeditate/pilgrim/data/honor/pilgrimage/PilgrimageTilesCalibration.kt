// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor.pilgrimage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Where a route's measured bytes per pack lives (iOS's `UserDefaults` key,
 * `PilgrimageTilesManager.swift:158-188@7c200bf`, spec D C1 §9). Raw
 * values only: the tiles manager reads a missing or non-positive one as
 * the seed.
 */
interface PilgrimageTilesCalibration {

    /** The route's figure as last written, null when none ever was. */
    suspend fun stored(routeId: String): Long?

    /**
     * Writes without suspending, as iOS's `UserDefaults.set` does, so a
     * save's last step never waits on a disk write before it goes idle
     * (spec D §C2.12 point 9). A [stored] after it sees the value.
     */
    fun store(routeId: String, bytesPerPack: Long)
}

/**
 * [PilgrimageTilesCalibration] in its own Preferences DataStore, under
 * iOS's key as a `Long` (an `Int` key of the same name couldn't read it
 * back). A write lands in memory at once and on disk in [scope]; each disk
 * write takes the newest value, so two writes landing out of order still
 * leave the last one. A failed disk write reaches [scope]'s exception
 * handler, which the provider supplies and logs with, since nothing in
 * this package logs; the figure then lives in memory until the process
 * ends. Rides a device transfer, not a cloud backup (spec D C1 A5): a lost
 * figure reads as the seed until the route's next save.
 */
class DataStorePilgrimageTilesCalibration(
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
) : PilgrimageTilesCalibration {

    init {
        require(scope.coroutineContext[CoroutineExceptionHandler] != null) {
            "the calibration scope needs an exception handler, or a failed write crashes the process"
        }
    }

    private val written = ConcurrentHashMap<String, Long>()

    override suspend fun stored(routeId: String): Long? = written[routeId] ?: dataStore.data
        .catch { failure -> if (failure is IOException) emit(emptyPreferences()) else throw failure }
        .first()[key(routeId)]

    override fun store(routeId: String, bytesPerPack: Long) {
        written[routeId] = bytesPerPack
        scope.launch {
            dataStore.edit { it[key(routeId)] = written.getValue(routeId) }
        }
    }

    companion object {
        fun key(routeId: String): Preferences.Key<Long> = longPreferencesKey("pilgrimage.tiles.bytesPerPack.$routeId")
    }
}
