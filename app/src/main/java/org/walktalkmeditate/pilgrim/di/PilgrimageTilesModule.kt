// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import android.content.Context
import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.DataStorePilgrimageTilesCalibration
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.MapboxTileRegionLoader
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageTilesCalibration
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageTilesManager
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.UiPilgrimageWalkSignals

/**
 * Stage 21-3's offline maps (spec D C2 §C2.12, C3 §11). UI process only:
 * every consumer reaches the tiles manager through a `Provider`, and only
 * the UI launch work and the UI's screens resolve one, so `:tracker` never
 * builds the manager, its loader, or the calibration store. Building them
 * touches no Mapbox class; the loader opens the store on its first call.
 *
 * Nothing under `data/honor/pilgrimage/` logs, so the scopes' handlers here
 * do, with the failure's type only: never a route or region id.
 */
@Module
@InstallIn(SingletonComponent::class)
object PilgrimageTilesModule {

    private const val TAG = "PilgrimageTiles"

    /** Apart from `pilgrim_prefs`, so a corrupt figure resets only itself, to the seed. */
    private const val CALIBRATION_DATASTORE_NAME = "pilgrimage_tiles"

    /**
     * The tiles manager's main-thread scope, which the loader's SDK hops run
     * in too. The handler is required: a hook, the launch sweep or a hop
     * that throws reaches it rather than the UI process's uncaught handler.
     */
    @Provides
    @Singleton
    @PilgrimageTilesScope
    fun provideTilesScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + loggingHandler("tiles"))

    @Provides
    @Singleton
    fun provideTileRegionLoader(@PilgrimageTilesScope scope: CoroutineScope): MapboxTileRegionLoader = MapboxTileRegionLoader(scope)

    /** Its own DataStore, created with its own scope, which also writes the figure; a failed write is logged and the figure lives in memory. */
    @Provides
    @Singleton
    fun provideTilesCalibration(@ApplicationContext context: Context): PilgrimageTilesCalibration {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + loggingHandler("calibration"))
        val dataStore = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
            produceFile = { context.preferencesDataStoreFile(CALIBRATION_DATASTORE_NAME) },
        )
        return DataStorePilgrimageTilesCalibration(dataStore, scope)
    }

    @Provides
    @Singleton
    fun provideTilesManager(
        loader: MapboxTileRegionLoader,
        calibration: PilgrimageTilesCalibration,
        signals: UiPilgrimageWalkSignals,
        @PilgrimageTilesScope scope: CoroutineScope,
    ): PilgrimageTilesManager = PilgrimageTilesManager(loader, calibration, signals, Dispatchers.Main.immediate, scope)

    private fun loggingHandler(what: String) = CoroutineExceptionHandler { _, failure ->
        Log.w(TAG, "uncaught in the $what scope (${failure::class.simpleName})")
    }
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PilgrimageTilesScope
