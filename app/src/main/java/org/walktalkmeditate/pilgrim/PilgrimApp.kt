// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.mapbox.common.MapboxOptions
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.audio.MeditationBellObserver
import org.walktalkmeditate.pilgrim.audio.OrphanSweeperScheduler
import org.walktalkmeditate.pilgrim.data.sounds.SoundsPreferencesSeeder
import org.walktalkmeditate.pilgrim.audio.voiceguide.VoiceGuideOrchestrator
import org.walktalkmeditate.pilgrim.data.collective.CollectiveRepoScope
import org.walktalkmeditate.pilgrim.data.collective.CollectiveRepository
import org.walktalkmeditate.pilgrim.data.collective.routes.CollectiveRouteCatalogService
import org.walktalkmeditate.pilgrim.data.launcher.IconSwitcher
import org.walktalkmeditate.pilgrim.data.soundscape.SoundscapeAutoDownloadObserver
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuideDownloadObserver
import org.walktalkmeditate.pilgrim.data.recovery.WalkRecoveryRepository
import org.walktalkmeditate.pilgrim.data.walk.WalkMetricsBackfillCoordinator
import org.walktalkmeditate.pilgrim.walk.WalkController
import org.walktalkmeditate.pilgrim.walk.WalkFinalizationObserver
import org.walktalkmeditate.pilgrim.walk.WalkLifecycleObserver
import org.walktalkmeditate.pilgrim.walk.seek.SeekOrchestrator

@HiltAndroidApp
class PilgrimApp : Application(), Configuration.Provider {

    // Every UI-only @Inject is wrapped in `Provider<T>` so the
    // tracker process (which early-returns from onCreate before any
    // .get() is called) never builds the underlying instance + its
    // downstream graph. Cuts tracker rss by ~30-60 MB of objects
    // (observer coroutine scopes, voice-guide / soundscape DataStore
    // subscriptions, ML Kit / Mapbox-init side effects). WorkManager's
    // configuration getter is one of the deferred consumers — tracker
    // never initializes WorkManager so workerFactory stays uninstantiated.
    @Inject lateinit var workerFactoryProvider: Provider<HiltWorkerFactory>
    @Inject lateinit var orphanSweeperSchedulerProvider: Provider<OrphanSweeperScheduler>

    /**
     * U6: iOS parity `MainCoordinator.init()`'s unconditional
     * `Task { @MainActor in ThreadsBackfill.runIfNeeded() }` — called on
     * every process start regardless of the toggle or battery state;
     * [org.walktalkmeditate.pilgrim.core.threads.ThreadsBackfillScheduler.ensureScheduled]'s
     * own KEEP-policy enqueue and [org.walktalkmeditate.pilgrim.core.threads.ThreadsBackfillWorker]'s
     * internal guards decide whether real work happens, not this call
     * site. Without it the backfill never runs at all.
     */
    @Inject lateinit var threadsBackfillSchedulerProvider:
        Provider<org.walktalkmeditate.pilgrim.core.threads.ThreadsBackfillScheduler>

    /**
     * E2 cold-start reconcile for the launcher-icon switcher. When the
     * user picks an icon, [IconSwitcher.switchTo] intentionally leaves
     * the previously-running alias enabled so the live MainActivity
     * task is not torn down (which would evict the user to the home
     * screen). That stale alias is reaped here on the next cold start,
     * where no activity task exists yet so disabling it is safe.
     */
    @Inject lateinit var iconSwitcherProvider: Provider<IconSwitcher>

    /**
     * First-launch defaults for bell + soundscape selections. iOS
     * mirrors this via `AppDelegate.swift:61-73`. Must run BEFORE
     * [meditationBellObserver] starts collecting so the start/end
     * bell ids are non-null on the first meditation transition.
     */
    @Inject lateinit var soundsPreferencesSeederProvider: Provider<SoundsPreferencesSeeder>

    /**
     * Referenced in [onCreate] to force Hilt to instantiate the
     * `@Singleton` observer at app start. Without the reference, the
     * binding is lazy and the observer's `init { scope.launch { ... } }`
     * block would never run — bells would silently not fire.
     */
    @Inject lateinit var meditationBellObserverProvider: Provider<MeditationBellObserver>

    /**
     * App-scoped auto-select observer for the voice-guide picker —
     * calls `selectIfUnset` when a pack transitions to Downloaded so
     * the first successful download becomes the active guide. Started
     * explicitly (like a service) rather than via `init { launch }`
     * so the subscription is visible + cancellable from the owning
     * Application class.
     */
    @Inject lateinit var voiceGuideDownloadObserverProvider: Provider<VoiceGuideDownloadObserver>

    /**
     * App-scoped orchestrator for voice-guide prompt playback.
     * Watches the walk controller's state + the selected pack and
     * spawns per-session scheduler coroutines that call the
     * `VoiceGuidePlayer` at the right moments. Same start-once,
     * runs-for-process-lifetime shape as the bell observer + download
     * observer above.
     */
    @Inject lateinit var voiceGuideOrchestratorProvider: Provider<VoiceGuideOrchestrator>

    /**
     * App-scoped auto-download observer for soundscapes. Matches
     * iOS's behavior of downloading all soundscapes in the background
     * as soon as the manifest is fetched — users never need to tap
     * download in the picker. Kicks a manifest sync on start so fresh
     * installs begin downloading immediately.
     */
    @Inject lateinit var soundscapeAutoDownloadObserverProvider: Provider<SoundscapeAutoDownloadObserver>

    /**
     * U9: app-scoped seek session owner. Boots the seek engine from the
     * setup's pending session when a seek walk starts, routes engine
     * events to sonar/haptics/persistence/map, and implements "Seek
     * anew". Started explicitly (like the voice-guide orchestrator) so
     * the walk-state subscription is visible + cancellable. UI process
     * only — the engine and every sense it drives live here; the
     * `:tracker` FGS keeps recording regardless.
     */
    @Inject lateinit var seekOrchestratorProvider: Provider<SeekOrchestrator>

    /**
     * Stage 8-B: collective counter. Boot-time fetch warms the cached
     * stats blob so Settings renders aggregates instantly on first
     * navigation. The 216s in-memory TTL inside the repo prevents a
     * config-change re-fetch storm — Application.onCreate fires once
     * per process so this is the right hook (matches iOS AppDelegate).
     */
    @Inject lateinit var collectiveRepositoryProvider: Provider<CollectiveRepository>

    @Inject @CollectiveRepoScope lateinit var collectiveScopeProvider: Provider<CoroutineScope>

    /**
     * U3: collective route-catalog CDN refresh. Fire-and-forget off the
     * launch path, mirroring iOS AppDelegate's post-setup `syncIfNeeded`
     * family. The service awaits its own initial load internally, so the
     * cache/bootstrap tier can never be stomped by a fast response.
     */
    @Inject lateinit var collectiveRouteCatalogServiceProvider: Provider<CollectiveRouteCatalogService>

    /**
     * Stage 9-A: home-screen widget refresh scheduler. PilgrimApp.onCreate
     * arms the next-midnight refresh so the widget's relative-date label
     * and daily-rotating mantra stay current even when the user never
     * opens the app. The Worker self-reschedules on each run, so this
     * boot-time enqueue is only needed when WorkManager's queue is empty
     * (fresh install, "Clear data", or rare WorkManager DB corruption).
     */
    @Inject lateinit var widgetRefreshSchedulerProvider: Provider<org.walktalkmeditate.pilgrim.widget.WidgetRefreshScheduler>

    /**
     * Stage 9-B: subscribes to `WalkController.state` and runs the
     * post-finish side-effect bundle (transcription scheduling,
     * hemisphere refresh, collective contribution, widget refresh) on
     * every transition to Finished. Centralizing here means the
     * notification-action Finish path gets the same finalize
     * orchestration as the in-app Finish path. Eager `@Inject` so the
     * `init { scope.launch { ... } }` block runs at app start.
     */
    @Inject lateinit var walkFinalizationObserverProvider: Provider<WalkFinalizationObserver>

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
    @Inject lateinit var walkLifecycleObserverProvider: Provider<WalkLifecycleObserver>

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
    @Inject lateinit var walkControllerProvider: Provider<WalkController>
    @Inject lateinit var walkRepositoryProvider: Provider<org.walktalkmeditate.pilgrim.data.WalkRepository>
    @Inject lateinit var walkRecoveryRepositoryProvider: Provider<WalkRecoveryRepository>
    @Inject lateinit var walkTrackingWatchdogProvider: Provider<org.walktalkmeditate.pilgrim.walk.WalkTrackingWatchdog>

    /**
     * Stage 11-A: drains stale walk-metrics cache columns for legacy
     * rows seeded NULL by MIGRATION_4_5 and for walks where the
     * finalize-hook crashed before invoking the cache. Started here so
     * the collector survives the whole process — `start()` is
     * idempotent (AtomicBoolean), so a re-call after a config change
     * is a safe no-op.
     */
    @Inject lateinit var walkMetricsBackfillCoordinatorProvider: Provider<WalkMetricsBackfillCoordinator>

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactoryProvider.get())
            .setMinimumLoggingLevel(Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
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
        //
        // Even the Mapbox token init below is gated: tracker never
        // creates a MapView, so loading the Mapbox common SDK (which
        // happens transitively when [MapboxOptions] is touched) is
        // wasted rss there.
        if (!isMainProcess()) {
            Log.i(TAG, "onCreate: skipping UI inits in non-main process ${getProcessName()}")
            return
        }

        // Mapbox reads the public access token (pk.xxx) here — the token
        // value is injected from local.properties at build time via
        // BuildConfig.MAPBOX_ACCESS_TOKEN. Empty token is accepted but
        // map tiles will fail to load; the placeholder map card handles
        // that visually until a valid token is configured.
        MapboxOptions.accessToken = BuildConfig.MAPBOX_ACCESS_TOKEN

        // Every `.get()` below forces Hilt to construct the underlying
        // singleton + its dependency graph. Tracker process early-
        // returned above, so it never reaches these calls and the
        // associated graph (observer coroutine scopes, voice-guide /
        // soundscape DataStore subscriptions, etc.) never instantiates
        // there — that's the rss savings.
        orphanSweeperSchedulerProvider.get().scheduleDaily()

        // U6: unconditional on every process start — the scheduler's KEEP
        // policy dedupes a redundant call while a sweep is already
        // enqueued/running, and the worker's own toggle/battery/
        // completion guards decide whether real work happens.
        threadsBackfillSchedulerProvider.get().ensureScheduled()

        // Seed bell + soundscape selection defaults on first launch
        // so MeditationBellObserver's null-id "None" guard doesn't
        // silence a fresh install. runBlocking matches the
        // recoverStaleWalks precedent — single DataStore.edit, <50ms.
        try {
            kotlinx.coroutines.runBlocking {
                soundsPreferencesSeederProvider.get().seedDefaultsIfNeeded()
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            Log.w(TAG, "seedDefaultsIfNeeded failed", t)
        }

        // Force Hilt to instantiate the bell observer so its `init`
        // block subscribes to the walk-state flow for the whole app
        // process. Without this reference the `@Singleton` binding
        // stays lazy and bells silently don't fire.
        meditationBellObserverProvider.get()

        // Start the voice-guide auto-select observer for the app's
        // process lifetime. Its collection on `catalog.packStates`
        // lives on `VoiceGuideCatalogScope`, so no per-screen tether.
        voiceGuideDownloadObserverProvider.get().start()

        // Start the voice-guide playback orchestrator. Observes the
        // walk-state flow + selected-pack flow and drives the player
        // via per-session scheduler coroutines on VoiceGuidePlaybackScope.
        voiceGuideOrchestratorProvider.get().start()

        // Start the seek orchestrator (U9). Observes the walk-state flow
        // and boots/tears down the seek engine per session; wander walks
        // cost one no-op state check per transition.
        seekOrchestratorProvider.get().start()

        // The soundscape playback orchestrator is NOT started here. It
        // runs in the :tracker process (WalkTrackingService) so the
        // ambient loop survives a UI-process o-kill mid-meditation;
        // starting it here too would loop two ExoPlayers on the same
        // file whenever both processes are alive. (Auto-download below
        // stays in the UI process — downloads are UI-driven, anytime.)

        // Start the soundscape auto-download observer. Triggers a
        // manifest sync and enqueues background downloads for any
        // soundscape assets not already on disk (iOS parity).
        soundscapeAutoDownloadObserverProvider.get().start()

        // U3: refresh the collective route catalog once per process —
        // Application.onCreate matches iOS AppDelegate's once-per-launch
        // semantics, and the service's CAS dedup makes re-entry a no-op.
        collectiveRouteCatalogServiceProvider.get().syncIfNeeded()

        // Stage 8-B: warm the collective-counter cache once per
        // process. fetchIfStale is TTL-gated so a re-launch within
        // 216s is a no-op.
        val collectiveRepository = collectiveRepositoryProvider.get()
        collectiveScopeProvider.get().launch {
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
        widgetRefreshSchedulerProvider.get().scheduleMidnightRefresh()

        // Force Hilt to instantiate the walk-finalization observer so
        // its `init { scope.launch { ... } }` block subscribes to the
        // controller state flow for the whole process.
        walkFinalizationObserverProvider.get()

        // Force Hilt to instantiate the walk-lifecycle observer so
        // its `init { scope.launch { ... } }` block subscribes to the
        // controller state flow. Without this, voice auto-stop on the
        // discardWalk path (Active → Idle) silently fails.
        walkLifecycleObserverProvider.get()

        // Stage 11-A: arm the cache backfill coordinator. Idempotent
        // start() — re-invocation is a no-op via AtomicBoolean.
        walkMetricsBackfillCoordinatorProvider.get().start()

        // Cold-launch stale-walk recovery. Any walk with end_timestamp
        // NULL is one the OS killed (swipe-from-recents, force-stop,
        // low-memory kill) without a normal finishWalk. Auto-finalize
        // in Room + arm the recovery banner. iOS-parity UX (their
        // WalkSessionGuard.recoverIfNeeded does the same on cold start
        // via the JSON checkpoint file).
        //
        // CRITICAL gate under the `:tracker` process split: if
        // WalkTrackingService FGS is still alive in `:tracker`, the
        // walk is in-progress there — UI was o-killed mid-walk and is
        // just restarting. Finalizing here would tombstone a live
        // walk: tracker keeps writing route samples to a row that UI
        // has marked Finished, and the user sees the recovered banner
        // instead of the ActiveWalkScreen they expected. Same FGS
        // check the warm-launch path uses.
        //
        // runBlocking on the main thread is acceptable here: the recovery
        // path is a single Room SELECT + a small fixed number of UPDATEs
        // (typically 0-1 walks). Total cost <50ms in practice. Running
        // synchronously here guarantees the banner is armed before any
        // UI composition reads `recoveredWalkId`, eliminating a
        // visible-then-flash-away race.
        if (org.walktalkmeditate.pilgrim.service.WalkTrackingService.isFgsAlive(this)) {
            Log.i(TAG, "recoverStaleWalks: FGS alive in :tracker, NOT finalizing on cold launch")
        } else {
            Log.i(TAG, "recoverStaleWalks: starting cold-launch recovery sweep")
            try {
                val recoveredId = kotlinx.coroutines.runBlocking {
                    walkControllerProvider.get().recoverStaleWalks()
                }
                if (recoveredId != null) {
                    walkRecoveryRepositoryProvider.get().markRecoveredBlocking(recoveredId)
                    Log.i(TAG, "recoverStaleWalks armed banner for walk=$recoveredId")
                } else {
                    Log.i(TAG, "recoverStaleWalks: no stale walks")
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                Log.w(TAG, "recoverStaleWalks failed", t)
            }
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
        val activeWalk = kotlinx.coroutines.runBlocking {
            walkRepositoryProvider.get().getActiveWalk()
        }
        val walkInProgress = activeWalk != null
        // Arm the watchdog on every cold launch where a walk is in
        // progress — covers the case where the UI process was o-killed
        // mid-walk, the FGS is still alive in :tracker, and the new
        // UI process needs to resume watchdog duty. Idempotent;
        // cancels any prior schedule first.
        if (walkInProgress) {
            walkTrackingWatchdogProvider.get().schedule()
        } else {
            walkTrackingWatchdogProvider.get().cancel()
        }
        if (walkInProgress) {
            Log.i(TAG, "icon reconcile skipped: walk in progress")
        } else {
            try {
                iconSwitcherProvider.get().reconcile()
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
