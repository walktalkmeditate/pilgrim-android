// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.mapbox.common.MapboxOptions
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import org.walktalkmeditate.pilgrim.audio.MeditationBellObserver
import org.walktalkmeditate.pilgrim.audio.OrphanSweeperScheduler
import org.walktalkmeditate.pilgrim.audio.voiceguide.VoiceGuideOrchestrator
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuideDownloadObserver

@HiltAndroidApp
class PilgrimApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var orphanSweeperScheduler: OrphanSweeperScheduler

    /**
     * Referenced in [onCreate] to force Hilt to instantiate the
     * `@Singleton` observer at app start. Without the reference, the
     * binding is lazy and the observer's `init { scope.launch { ... } }`
     * block would never run — bells would silently not fire.
     */
    @Inject lateinit var meditationBellObserver: MeditationBellObserver

    /**
     * App-scoped auto-select observer for the voice-guide picker —
     * calls `selectIfUnset` when a pack transitions to Downloaded so
     * the first successful download becomes the active guide. Started
     * explicitly (like a service) rather than via `init { launch }`
     * so the subscription is visible + cancellable from the owning
     * Application class.
     */
    @Inject lateinit var voiceGuideDownloadObserver: VoiceGuideDownloadObserver

    /**
     * App-scoped orchestrator for voice-guide prompt playback.
     * Watches the walk controller's state + the selected pack and
     * spawns per-session scheduler coroutines that call the
     * `VoiceGuidePlayer` at the right moments. Same start-once,
     * runs-for-process-lifetime shape as the bell observer + download
     * observer above.
     */
    @Inject lateinit var voiceGuideOrchestrator: VoiceGuideOrchestrator

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

        // Force Hilt to instantiate the bell observer so its `init`
        // block subscribes to the walk-state flow for the whole app
        // process. Without this reference the `@Singleton` binding
        // stays lazy and bells silently don't fire. `hashCode()` is a
        // side-effect-free op that ensures the field is actually used.
        meditationBellObserver.hashCode()

        // Start the voice-guide auto-select observer for the app's
        // process lifetime. Its collection on `catalog.packStates`
        // lives on `VoiceGuideCatalogScope`, so no per-screen tether.
        voiceGuideDownloadObserver.start()

        // Start the voice-guide playback orchestrator. Observes the
        // walk-state flow + selected-pack flow and drives the player
        // via per-session scheduler coroutines on VoiceGuidePlaybackScope.
        voiceGuideOrchestrator.start()
    }
}
