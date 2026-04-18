// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.mapbox.common.MapboxOptions
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import org.walktalkmeditate.pilgrim.audio.OrphanSweeperScheduler

@HiltAndroidApp
class PilgrimApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var orphanSweeperScheduler: OrphanSweeperScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Mapbox reads the public access token (pk.xxx) here — the token
        // value is injected from local.properties at build time via
        // BuildConfig.MAPBOX_ACCESS_TOKEN. Empty token is accepted but
        // map tiles will fail to load; the placeholder map card handles
        // that visually until a valid token is configured.
        MapboxOptions.accessToken = BuildConfig.MAPBOX_ACCESS_TOKEN

        // KEEP policy means this is a no-op after the first launch; the
        // periodic sweeper runs on its own daily cadence regardless of
        // how often the user opens the app.
        orphanSweeperScheduler.scheduleDaily()
    }
}
