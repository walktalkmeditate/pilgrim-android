// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.mapbox.common.MapboxOptions
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.audio.MeditationBellObserver
import org.walktalkmeditate.pilgrim.audio.OrphanSweeperScheduler
import org.walktalkmeditate.pilgrim.data.sounds.SoundsPreferencesSeeder
import org.walktalkmeditate.pilgrim.audio.soundscape.SoundscapeOrchestrator
import org.walktalkmeditate.pilgrim.audio.voiceguide.VoiceGuideOrchestrator
import org.walktalkmeditate.pilgrim.data.collective.CollectiveRepoScope
import org.walktalkmeditate.pilgrim.data.collective.CollectiveRepository
import org.walktalkmeditate.pilgrim.data.launcher.IconSwitcher
import org.walktalkmeditate.pilgrim.data.soundscape.SoundscapeAutoDownloadObserver
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuideDownloadObserver
import org.walktalkmeditate.pilgrim.data.recovery.WalkRecoveryRepository
import org.walktalkmeditate.pilgrim.data.walk.WalkMetricsBackfillCoordinator
import org.walktalkmeditate.pilgrim.walk.WalkController
import org.walktalkmeditate.pilgrim.walk.WalkFinalizationObserver
import org.walktalkmeditate.pilgrim.walk.WalkLifecycleObserver

@HiltAndroidApp
class PilgrimApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var orphanSweeperScheduler: OrphanSweeperScheduler

    /**
     * E2 cold-start reconcile for the launcher-icon switcher. When the
     * user picks an icon, [IconSwitcher.switchTo] intentionally leaves
     * the previously-running alias enabled so the live MainActivity
     * task is not torn down (which would evict the user to the home
     * screen). That stale alias is reaped here on the next cold start,
     * where no activity task exists yet so disabling it is safe.
     */
    @Inject lateinit var iconSwitcher: IconSwitcher

    /**
     * First-launch defaults for bell + soundscape selections. iOS
     * mirrors this via `AppDelegate.swift:61-73`. Must run BEFORE
     * [meditationBellObserver] starts collecting so the start/end
     * bell ids are non-null on the first meditation transition.
     */
    @Inject lateinit var soundsPreferencesSeeder: SoundsPreferencesSeeder

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

    /**
     * App-scoped orchestrator for soundscape ambient loop playback
     * during meditation. Watches walk-state + selected-soundscape-id
     * and plays/stops the looping ExoPlayer-backed player.
     */
    @Inject lateinit var soundscapeOrchestrator: SoundscapeOrchestrator

    /**
     * App-scoped auto-download observer for soundscapes. Matches
     * iOS's behavior of downloading all soundscapes in the background
     * as soon as the manifest is fetched — users never need to tap
     * download in the picker. Kicks a manifest sync on start so fresh
     * installs begin downloading immediately.
     */
    @Inject lateinit var soundscapeAutoDownloadObserver: SoundscapeAutoDownloadObserver

    /**
     * Stage 8-B: collective counter. Boot-time fetch warms the cached
     * stats blob so Settings renders aggregates instantly on first
     * navigation. The 216s in-memory TTL inside the repo prevents a
     * config-change re-fetch storm — Application.onCreate fires once
     * per process so this is the right hook (matches iOS AppDelegate).
     */
    @Inject lateinit var collectiveRepository: CollectiveRepository

    @Inject @CollectiveRepoScope lateinit var collectiveScope: CoroutineScope

    /**
     * Stage 9-A: home-screen widget refresh scheduler. PilgrimApp.onCreate
     * arms the next-midnight refresh so the widget's relative-date label
     * and daily-rotating mantra stay current even when the user never
     * opens the app. The Worker self-reschedules on each run, so this
     * boot-time enqueue is only needed when WorkManager's queue is empty
     * (fresh install, "Clear data", or rare WorkManager DB corruption).
     */
    @Inject lateinit var widgetRefreshScheduler: org.walktalkmeditate.pilgrim.widget.WidgetRefreshScheduler

    /**
     * Stage 9-B: subscribes to `WalkController.state` and runs the
     * post-finish side-effect bundle (transcription scheduling,
     * hemisphere refresh, collective contribution, widget refresh) on
     * every transition to Finished. Centralizing here means the
     * notification-action Finish path gets the same finalize
     * orchestration as the in-app Finish path. Eager `@Inject` so the
     * `init { scope.launch { ... } }` block runs at app start.
     */
    @Inject lateinit var walkFinalizationObserver: WalkFinalizationObserver

    /**
     * Stage 9.5-C: voice-recorder auto-stop on every in-progress →
     * terminal transition (Active|Paused|Meditating → Idle|Finished).
     * Lives separately from [walkFinalizationObserver] because that
     * observer only fires on Finished — leaving the discardWalk path
     * (Active → Idle, parent walk row already cascade-deleted) with a
     * leaked recorder + a guaranteed FK-violation if it tried to
     * insert. Eager `@Inject` so the `init { scope.launch { ... } }`
     * block runs at app start.
     */
    @Inject lateinit var walkLifecycleObserver: WalkLifecycleObserver

    /**
     * Cold-launch recovery: any Walk row whose `end_timestamp IS NULL`
     * is a walk the OS killed (swipe-from-recents, force-stop, low-mem
     * kill) without going through the normal `finishWalk` path.
     * `recoverStaleWalks` finalizes them in Room and returns the most
     * recent recovered walkId so the Path tab can show a transient
     * banner — iOS-parity recovery UX.
     *
     * Runs once at process start. Warm launches (the process was already
     * alive) don't re-run this — `Application.onCreate` only fires on
     * cold start, exactly when we want recovery to apply.
     */
    @Inject lateinit var walkController: WalkController
    @Inject lateinit var walkRepository: org.walktalkmeditate.pilgrim.data.WalkRepository
    @Inject lateinit var walkRecoveryRepository: WalkRecoveryRepository

    /**
     * Stage 11-A: drains stale walk-metrics cache columns for legacy
     * rows seeded NULL by MIGRATION_4_5 and for walks where the
     * finalize-hook crashed before invoking the cache. Started here so
     * the collector survives the whole process — `start()` is
     * idempotent (AtomicBoolean), so a re-call after a config change
     * is a safe no-op.
     */
    @Inject lateinit var walkMetricsBackfillCoordinator: WalkMetricsBackfillCoordinator

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
        //
        // Safe to set in either process: it's a static config write that
        // the `:tracker` process doesn't read (tracker doesn't render a
        // MapView), but the assignment is cheap and keeps the call site
        // identical across processes.
        MapboxOptions.accessToken = BuildConfig.MAPBOX_ACCESS_TOKEN

        // WalkTrackingService runs in the `:tracker` process (manifest
        // android:process=":tracker") so the UI process being OEM-killed
        // mid-walk no longer kills GPS tracking. The tracker process
        // instantiates its own PilgrimApp / Hilt graph; we skip every
        // UI-side init below so its rss stays lean (~50MB target vs
        // ~440MB in UI) and the eager observers don't double-fire side
        // effects across processes.
        //
        // Hilt's @AndroidEntryPoint Service still injects deps via the
        // per-process SingletonComponent — no PilgrimApp.onCreate side
        // effects are required for the service to operate.
        if (!isMainProcess()) {
            Log.i(TAG, "onCreate: skipping UI inits in non-main process ${getProcessName()}")
            return
        }

        // KEEP policy means this is a no-op after the first launch; the
        // periodic sweeper runs on its own daily cadence regardless of
        // how often the user opens the app.
        orphanSweeperScheduler.scheduleDaily()

        // Seed bell + soundscape selection defaults on first launch
        // so MeditationBellObserver's null-id "None" guard doesn't
        // silence a fresh install. runBlocking matches the
        // recoverStaleWalks precedent — single DataStore.edit, <50ms.
        try {
            kotlinx.coroutines.runBlocking { soundsPreferencesSeeder.seedDefaultsIfNeeded() }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            Log.w(TAG, "seedDefaultsIfNeeded failed", t)
        }

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

        // Start the soundscape playback orchestrator. Observes the
        // walk-state flow + selected-soundscape-id flow and drives
        // the looping ExoPlayer-backed player during meditation.
        soundscapeOrchestrator.start()

        // Start the soundscape auto-download observer. Triggers a
        // manifest sync and enqueues background downloads for any
        // soundscape assets not already on disk (iOS parity).
        soundscapeAutoDownloadObserver.start()

        // Stage 8-B: warm the collective-counter cache once per
        // process. fetchIfStale is TTL-gated so a re-launch within
        // 216s is a no-op.
        collectiveScope.launch {
            try {
                collectiveRepository.fetchIfStale()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                Log.w(TAG, "boot fetchIfStale failed", t)
            }
        }

        // Stage 9-A: ensure the widget's midnight refresh is enqueued.
        // Worker self-reschedules on each run; this boot-time call
        // covers the empty-queue case (fresh install, after "Clear
        // data", or after a long stretch where the chain ran out).
        // REPLACE policy in the scheduler de-dupes if a chain run is
        // already pending.
        widgetRefreshScheduler.scheduleMidnightRefresh()

        // Force Hilt to instantiate the walk-finalization observer so
        // its `init { scope.launch { ... } }` block subscribes to the
        // controller state flow for the whole process. Without this
        // reference the binding stays lazy and finalize side-effects
        // would silently not fire — most consequentially, the
        // collective counter would lose any walk finished from the
        // notification's Finish button.
        walkFinalizationObserver.hashCode()

        // Force Hilt to instantiate the walk-lifecycle observer so
        // its `init { scope.launch { ... } }` block subscribes to the
        // controller state flow. Without this reference the binding
        // stays lazy and voice auto-stop on the discardWalk path
        // (Active → Idle) silently fails — leaving the recorder
        // running and an orphan WAV on disk that the user has no UI
        // to recover.
        walkLifecycleObserver.hashCode()

        // Stage 11-A: arm the cache backfill coordinator. Idempotent
        // start() — re-invocation is a no-op via AtomicBoolean. The
        // collector lives on @CollectiveRepoScope (SupervisorJob +
        // Dispatchers.IO) so it survives the whole process.
        walkMetricsBackfillCoordinator.start()

        // Cold-launch stale-walk recovery. Any walk with end_timestamp
        // NULL is one the OS killed (swipe-from-recents, force-stop,
        // low-memory kill) without a normal finishWalk. Auto-finalize
        // in Room + arm the recovery banner. iOS-parity UX (their
        // WalkSessionGuard.recoverIfNeeded does the same on cold start
        // via the JSON checkpoint file).
        //
        // runBlocking on the main thread is acceptable here: the recovery
        // path is a single Room SELECT + a small fixed number of UPDATEs
        // (typically 0-1 walks). Total cost <50ms in practice. Running
        // synchronously here guarantees the banner is armed before any
        // UI composition reads `recoveredWalkId`, eliminating a
        // visible-then-flash-away race.
        Log.i(TAG, "recoverStaleWalks: starting cold-launch recovery sweep")
        try {
            val recoveredId = kotlinx.coroutines.runBlocking {
                walkController.recoverStaleWalks()
            }
            if (recoveredId != null) {
                walkRecoveryRepository.markRecoveredBlocking(recoveredId)
                Log.i(TAG, "recoverStaleWalks armed banner for walk=$recoveredId")
            } else {
                Log.i(TAG, "recoverStaleWalks: no stale walks")
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            Log.w(TAG, "recoverStaleWalks failed", t)
        }

        // E2: reap any launcher alias left enabled by an in-place icon
        // switch in a previous session. Deferred to AFTER stale-walk
        // recovery so the controller's state is settled, then skipped
        // entirely while a walk is in progress: when the OS revives a
        // backgrounded long walk's process, churning launcher aliases on
        // every restart force-cycles the alias the user is mid-walk
        // under. recoverStaleWalks() finalizes any orphan walk to
        // Finished/Idle, so a remaining in-progress state here means the
        // process is genuinely hosting a live walk — leave the launcher
        // alone. IconSwitcher.reconcile()'s own current==persisted
        // no-op guard is the primary churn defense (covers the dominant
        // case even when this in-progress check can't); this gate adds
        // belt-and-braces for the live-walk window. setComponentEnabled
        // can throw SecurityException on hardened ROMs — swallow so a
        // fresh install still boots.
        // Under the :tracker process split, walkController.state in the
        // UI process derives from Room asynchronously — `.value` is
        // Idle until the first Room emission lands. The icon-reconcile
        // gate needs a stable answer, so probe Room directly via the
        // same blocking pattern recoverStaleWalks uses. `recoverStaleWalks`
        // above has already finalized any orphan walk, so any walk
        // still in `getActiveWalk()` is genuinely live in :tracker.
        val activeWalk = kotlinx.coroutines.runBlocking { walkRepository.getActiveWalk() }
        val walkInProgress = activeWalk != null
        if (walkInProgress) {
            Log.i(TAG, "icon reconcile skipped: walk in progress")
        } else {
            try {
                iconSwitcher.reconcile()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                Log.w(TAG, "icon reconcile failed", t)
            }
        }
    }

    /**
     * Identify the process this Application instance is running in.
     * `:tracker` (WalkTrackingService) gets a process name distinct
     * from the main UI process; gating PilgrimApp's UI-side inits on
     * this saves rss + avoids cross-process double-firing of eager
     * observers.
     *
     * [Application.getProcessName] is API 28+, matching our minSdk 28
     * — no fallback path needed.
     */
    private fun isMainProcess(): Boolean = getProcessName() == packageName

    /**
     * Drop the Coil image-memory cache when the system signals the UI
     * is off-screen / under pressure. Pinned-photo thumbnails on the
     * Walk Summary reliquary easily reach 10-50 MB each in cache; on a
     * long backgrounded walk that bloats `rss` and makes the process
     * a juicier target for OxygenOS's RAM manager (`o-kill(6)` —
     * killed walk 16 at 24:18 with rss=445 MB even with battery
     * exemption granted). Mapbox v11 manages its own MapView/tile
     * lifecycle via the host Lifecycle; Coil doesn't auto-trim on
     * background. The location FGS keeps tracking either way.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.i(TAG, "onTrimMemory level=$level")
        if (level >= TRIM_MEMORY_BACKGROUND) {
            runCatching {
                coil3.SingletonImageLoader.get(this).memoryCache?.clear()
            }.onFailure { Log.w(TAG, "Coil memory cache clear failed", it) }
        }
    }

    private companion object {
        const val TAG = "PilgrimApp"
    }
}
