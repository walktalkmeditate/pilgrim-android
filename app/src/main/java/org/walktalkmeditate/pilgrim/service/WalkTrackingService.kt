// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.walktalkmeditate.pilgrim.MainActivity
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.audio.soundscape.SoundscapeOrchestrator
import org.walktalkmeditate.pilgrim.audio.walk.UiAudioGate
import org.walktalkmeditate.pilgrim.audio.walk.UiAudioGateKind
import org.walktalkmeditate.pilgrim.audio.walk.UiAudioGateSignal
import org.walktalkmeditate.pilgrim.core.flags.ReleaseFlags
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.data.units.UnitsPreferencesRepository
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.WalkMode
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.domain.seek.SeekChainCodec
import org.walktalkmeditate.pilgrim.domain.seek.SeekDirectionHint
import org.walktalkmeditate.pilgrim.domain.seek.SeekGlanceState
import org.walktalkmeditate.pilgrim.location.LocationSource
import org.walktalkmeditate.pilgrim.location.MockLocationReplay
import org.walktalkmeditate.pilgrim.walk.HonorSettings
import org.walktalkmeditate.pilgrim.walk.HonorStart
import org.walktalkmeditate.pilgrim.walk.WalkController
import org.walktalkmeditate.pilgrim.walk.WalkStartRequest
import org.walktalkmeditate.pilgrim.walk.honor.HonorCommand
import org.walktalkmeditate.pilgrim.walk.honor.HonorGlanceState
import org.walktalkmeditate.pilgrim.walk.honor.HonorSession
import org.walktalkmeditate.pilgrim.walk.seek.SeekPlacement
import org.walktalkmeditate.pilgrim.walk.seek.SeekSonarSettings
import org.walktalkmeditate.pilgrim.walk.seek.SeekStart
import org.walktalkmeditate.pilgrim.walk.seek.SeekTrackerSession
import org.walktalkmeditate.pilgrim.widget.DeepLinkTarget

/**
 * Foreground service that binds the physical location stream to the
 * [WalkController] and surfaces the walk as an ongoing notification with
 * media-style action buttons.
 *
 * Starts via [startIntent]. Stopping is state-driven only: the service
 * observes [WalkController.state] and calls `stopSelf()` once the
 * controller reaches [WalkState.Finished].
 *
 * Action buttons (per state) deliver via `PendingIntent.getService(...)`
 * directly back to this service — no BroadcastReceiver hop. Direct service
 * delivery sidesteps the API 26+ implicit-broadcast filter and shaves the
 * latency that an extra IPC would add to a tap from the lock screen.
 */
@AndroidEntryPoint
class WalkTrackingService : Service() {

    @Inject lateinit var controller: WalkController

    @Inject lateinit var locationSource: LocationSource

    @Inject lateinit var unitsPreferences: UnitsPreferencesRepository

    @Inject lateinit var repository: org.walktalkmeditate.pilgrim.data.WalkRepository

    @Inject lateinit var backgroundWhisperAutoPlayer: BackgroundWhisperAutoPlayer

    @Inject lateinit var soundscapeOrchestrator: SoundscapeOrchestrator

    @Inject lateinit var releaseFlags: Provider<ReleaseFlags>

    @Inject lateinit var honorSessionProvider: Provider<HonorSession>

    /** The UI's audio gates; resolved only with the release flag on. */
    @Inject lateinit var uiAudioGateProvider: Provider<UiAudioGate>

    /** The debug Way replayer's cleanup hook; a no-op in release. */
    @Inject lateinit var mockLocationReplay: Provider<MockLocationReplay>

    /** Seek in this process (plan U25); resolved only with the release flag on. */
    @Inject lateinit var seekSessionProvider: Provider<SeekTrackerSession>

    /** Resolved only with the release flag on: with it off, Seek runs in the UI process as it always has. */
    private var seekSession: SeekTrackerSession? = null

    /** Resolved only with the release flag on: with it off, nothing Honor is built here. */
    private var honorSession: HonorSession? = null

    /** The units the Honor glance speaks for this walk, fixed at its start (spec D §10.2). */
    private var honorGlanceUnits: UnitSystem? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var locationJob: Job? = null
    private var notificationJob: Job? = null
    private var stepFlushJob: Job? = null

    /**
     * Latch: true once the controller has emitted any in-progress state
     * (Active|Paused|Meditating) since the service started observing.
     * Required to distinguish the cold-start initial Idle (do NOT
     * self-stop — service is freshly promoted to FGS, controller hasn't
     * dispatched anything yet) from the Stage 9.5-C discardWalk
     * Active→Idle transition (DO self-stop — walk row was just
     * cascade-deleted, service has nothing left to track). Reset is
     * unnecessary because the service is destroyed between walks.
     */
    private var hasBeenActive = false

    private lateinit var notificationActions: WalkNotificationActions

    override fun onCreate() {
        super.onCreate()
        isRunning.set(true)
        mockLocationReplay.get().onTrackerStart()
        createNotificationChannel()
        notificationActions = WalkNotificationActions(
            pause = actionPendingIntent(ACTION_PAUSE, REQUEST_CODE_PAUSE),
            resume = actionPendingIntent(ACTION_RESUME, REQUEST_CODE_RESUME),
            endMeditation = actionPendingIntent(ACTION_END_MEDITATION, REQUEST_CODE_END_MEDITATION),
            markWaypoint = actionPendingIntent(ACTION_MARK_WAYPOINT, REQUEST_CODE_MARK_WAYPOINT),
            finish = actionPendingIntent(ACTION_FINISH, REQUEST_CODE_FINISH),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (val action = intent?.action) {
            ACTION_START -> startTracking(intent)
            ACTION_PAUSE,
            ACTION_RESUME,
            ACTION_START_MEDITATION,
            ACTION_END_MEDITATION,
            ACTION_MARK_WAYPOINT,
            ACTION_FINISH,
            ACTION_DISCARD,
            ACTION_SET_INTENTION -> handleControllerAction(action, intent)
            ACTION_SET_SOUNDSCAPE,
            ACTION_SELECT_SOUNDSCAPE,
            ACTION_CLEAR_SOUNDSCAPE_SELECTION -> handleSoundscapeAction(action, intent)
            ACTION_UPDATE_SEEK_GLANCE -> handleSeekGlanceAction(intent)
            ACTION_HONOR_COMMAND -> handleHonorCommand(intent, redelivered = flags and START_FLAG_REDELIVERY != 0)
            ACTION_UI_AUDIO_GATE -> handleUiAudioGate(intent, redelivered = flags and START_FLAG_REDELIVERY != 0)
            ACTION_SEEK_ANEW,
            ACTION_SEEK_PREFERENCES,
            ACTION_SEEK_SESSION -> handleSeekIntent(action, intent, redelivered = flags and START_FLAG_REDELIVERY != 0)
            null -> {
                // START_REDELIVER_INTENT redelivers the LAST delivered
                // intent (the original ACTION_START), so a null intent
                // here is not the revival path — it only happens on a
                // genuinely malformed start. We have no tracking pipeline
                // and would crash on the API 31+
                // ForegroundServiceDidNotStartInTimeException timer. Bail.
                stopSelf()
            }
        }
        // START_REDELIVER_INTENT: if the OS kills the service mid-walk
        // (OEM power manager force-kill after the screen has been off for
        // ~30-40 min — the OnePlus/OxygenOS failure that ended long
        // backgrounded walks), the system revives the service AND
        // redelivers the last ACTION_START intent. onStartCommand then
        // receives ACTION_START again → startTracking() rebuilds the
        // location pipeline against the still-unfinished Room walk. The
        // old START_NOT_STICKY rejected START_STICKY because that revives
        // with a NULL intent (no pipeline + API 31+ FGS-start-timeout
        // crash); START_REDELIVER_INTENT sidesteps both — the redelivered
        // intent is the real ACTION_START, and startTracking() restores
        // the controller from Room (restoreActiveWalk) before promoting,
        // so a bare revived process re-establishes a live walk instead of
        // silently dropping GPS into an Idle controller.
        return START_MODE
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning.set(false)
        // Tear down the whisper auto-player before cancelling our scope.
        // stop() cancelAndJoins its own collectors FIRST (so a buffered
        // Entered event can't drive a play() mid-teardown), then stops the
        // detector + clears its dedup. Quick job-cancel + suspend cleanup,
        // so blocking briefly in onDestroy is acceptable.
        if (this::backgroundWhisperAutoPlayer.isInitialized) {
            kotlinx.coroutines.runBlocking {
                runCatching { backgroundWhisperAutoPlayer.stop() }
            }
        }
        // Same shape: the session's actor runs on its own dispatcher, so
        // joining it here can't wait on the main thread this blocks.
        honorSession?.let { session ->
            kotlinx.coroutines.runBlocking {
                try {
                    session.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "Honor session teardown failed: ${e::class.simpleName}")
                }
            }
        }
        seekSession?.let { session ->
            kotlinx.coroutines.runBlocking {
                try {
                    session.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "seek session teardown failed: ${e::class.simpleName}")
                }
            }
        }
        scope.cancel()
        // Explicit teardown so the FGS notification is gone the moment
        // the service stops, not whenever the OS gets around to clearing
        // it. Closes the window where a finishWalk emission posts the
        // "Walk complete." render and stopSelf() schedules teardown,
        // leaving a tappable-but-dead notification visible for the
        // milliseconds before destroy lands.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    private fun startTracking(startIntent: Intent?) {
        // Re-entrant START intents are a no-op: if the pipeline is already
        // live, cancelling and relaunching would race the old subscription's
        // awaitClose cleanup against the new one's subscribe and produce
        // duplicate route samples in the window between.
        if (locationJob?.isActive == true) return

        // Absent (legacy intent, notification revival) or unknown mode
        // values collapse to Wander. Restored walks ignore the extras
        // entirely — their mode is re-derived from the persisted marker.
        val honorEnabled = releaseFlags.get().honor
        val extras = startExtrasFrom(startIntent, honorEnabled)
        // The gates hold before the session, and the arbiter it builds, read
        // them: what this process knew of the UI's gates is stale once a
        // pipeline (re)starts, until the UI answers the session's bumped
        // gate generation.
        if (honorEnabled) {
            uiAudioGateProvider.get().holdUntilRefreshed()
            honorSession = honorSessionProvider.get()
        }
        if (SeekPlacement.of(honorEnabled) == SeekPlacement.TRACKER) {
            seekSession = seekSessionProvider.get()
        }
        honorGlanceUnits = extras.honorGlanceUnits

        // API 34+ rejects startForeground(type=location) with SecurityException
        // if FINE location isn't granted at that moment; API 33+ silently
        // suppresses the notification without POST_NOTIFICATIONS. Bail loud
        // rather than limp along — UI must gate this intent on permissions.
        if (!hasRequiredPermissions()) {
            Log.w(TAG, "startTracking aborted: required permissions not granted")
            stopSelf()
            return
        }

        // Read the controller's current state synchronously so the
        // initial promote matches reality. If the user resumed an
        // already-Active walk (restoreActiveWalk ran on the way in),
        // hard-coding Idle here would flash a zero-action "Preparing
        // your walk…" notification for the sub-second window before
        // the state collector delivers the first real emission.
        //
        // promoteToForeground MUST run synchronously here (not inside the
        // launch below): the API 31+ FGS-start timeout requires
        // startForeground() promptly after the OS hands us the
        // (redelivered) start intent. The Idle notification is acceptable
        // for the sub-second window before restore + the first real
        // state emission re-render it.
        promoteToForeground(buildNotification(controller.state.value))

        locationJob = scope.launch {
            // Three paths land here, all distinguished by Room state +
            // the [EXTRA_FRESH_START] flag:
            //
            //  1. UI fresh start (`isFreshStart=true`, no active walk in
            //     Room): insert the walk row + transition to Active here.
            //     UI's [UiWalkController.startWalk] awaits the new row
            //     via [WalkRepository.observeActiveWalk] before returning
            //     a [Walk] to the caller.
            //  2. START_REDELIVER_INTENT revival (the OS killed the
            //     service mid-walk and re-delivered the last intent into
            //     a fresh process): controller is Idle, walk row already
            //     in Room, nothing to insert. `restoreActiveWalk` rebuilds
            //     the in-memory state from the persisted row + events +
            //     samples. The redelivered intent may carry the original
            //     `isFreshStart=true` flag — the restored-walk check
            //     short-circuits the insert before we'd double-create.
            //  3. UI fresh start raced with a prior in-flight walk row
            //     (defensive — shouldn't happen via UiWalkController
            //     because UI's flow only fires ACTION_START when no
            //     active walk is observed, but a stale process or
            //     test-time corner can land here). `restoreActiveWalk`
            //     adopts the existing walk; the `isFreshStart` insert is
            //     skipped.
            // restoreActiveWalk is only meaningful when the controller
            // is Idle — Finished walks are already closed in Room. A
            // start carrying a Begin-minted uuid is resolved against
            // Room before anything is inserted (WalkTrackingStarter).
            val starter = WalkTrackingStarter(controller, repository, honorSession, seekSession, ::lastKnownFix)
            if (!starter.resolve(extras)) {
                stopSelf()
                return@launch
            }
            // After the restore and the start decision, as the whisper
            // auto-player is wired: the session replaces any session a
            // cached process still holds.
            val honorTap = starter.startHonor(scope)
            starter.startSeek(scope)
            try {
                collectWalkFixes(locationSource.locationFlow(), honorTap) { point ->
                    controller.recordLocation(point)
                }
            } catch (e: SecurityException) {
                // Permission revoked mid-walk via Settings. Finish the walk
                // through the controller so in-memory state and DB row stay
                // consistent, then let the Finished observer stop us.
                Log.w(TAG, "location permission revoked mid-walk", e)
                runCatching { controller.finishWalk() }
            }
        }

        notificationJob = scope.launch {
            // Observe controller state AND units preference: a Settings
            // toggle from Metric→Imperial mid-walk must re-render the
            // notification text immediately, not wait for the next GPS
            // fix to push a fresh `controller.state` emission. The
            // fingerprint already includes the units ordinal — combining
            // here ensures the collector actually fires when units flip.
            // Combining a `_` for units (we don't use the value here;
            // `notificationFingerprint` reads `unitsPreferences.distanceUnits.value`
            // synchronously) keeps the existing decideStateAction path
            // untouched.
            // The Honor glance joins the same collector: a changed glance
            // re-renders at once, and the fingerprint decides whether the
            // words changed (spec D §10.3).
            val honorGlance = honorSession?.glance ?: flowOf(null)
            val trackerSeekGlance = seekSession?.glance ?: flowOf(null)
            combine(
                controller.state,
                unitsPreferences.distanceUnits,
                honorGlance,
                trackerSeekGlance,
            ) { state, _, _, _ -> state }
                .collect { state ->
                    val (nextLatch, action) = decideStateAction(state, hasBeenActive)
                    hasBeenActive = nextLatch
                    when (action) {
                        StateAction.SelfStop -> {
                            // Skip the Finished render — onDestroy's
                            // stopForeground(REMOVE) is about to clear the
                            // notification anyway, and posting a "Walk
                            // complete." rebuild here just lets the user
                            // briefly see it flash on slower devices.
                            // For Idle-after-in-progress (Stage 9.5-C
                            // discard), same reasoning: the walk row was
                            // just cascade-deleted, no point re-rendering.
                            stopSelf()
                        }
                        StateAction.UpdateNotification -> updateNotification(state)
                    }
                }
        }

        // Persist the step count every 30s while Active so an OEM
        // mid-walk kill (e.g. OnePlus o-kill at 24min in walk 16) can
        // be recovered with the last live counter. Previously
        // `updateSteps` fired only on the Finish path
        // (WalkEffect.PersistWalk), so any kill-then-recoverStaleWalks
        // path produced a NULL `walk.steps` and the Steps row hid on
        // the summary. `collectLatest` rotates the inner block on
        // state changes so the ticker auto-cancels when leaving
        // Active.
        stepFlushJob = scope.launch {
            controller.state.collectLatest { state ->
                if (state is org.walktalkmeditate.pilgrim.domain.WalkState.Active) {
                    val walkId = state.walk.walkId
                    while (isActive) {
                        delay(STEP_FLUSH_INTERVAL_MS)
                        val steps = controller.liveSteps.value ?: continue
                        try {
                            repository.updateSteps(walkId, steps)
                        } catch (ce: kotlinx.coroutines.CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            Log.w(TAG, "step flush failed for walk $walkId", t)
                        }
                    }
                }
            }
        }

        // Whisper proximity auto-play runs HERE (in :tracker) rather than
        // in the UI so a nearby whisper plays even when the screen is
        // locked / the UI process is gone. Fed by the controller's live
        // state; tears down in onDestroy.
        backgroundWhisperAutoPlayer.start(scope, controller.state)

        // Soundscape meditation playback ALSO runs HERE, not in the UI.
        // Soundscape only plays during WalkState.Meditating, which is
        // reachable only from an Active walk (WalkReducer) — so :tracker
        // is always alive when it matters, and running it here means the
        // ambient loop survives a UI-process o-kill mid-meditation. The
        // orchestrator observes the real WalkControllerImpl.state via its
        // @SoundscapeObservedWalkState binding (the same controller this
        // service drives). start() is idempotent so a cached :tracker
        // process reused across walks doesn't double-wire it. The UI
        // process no longer starts it (see PilgrimApp) to avoid two
        // ExoPlayers looping the same file when both processes are alive.
        soundscapeOrchestrator.start()
    }

    private fun handleSoundscapeAction(action: String, intent: Intent?) {
        // Soundscape playback lives in this process's orchestrator. These
        // commands only make sense while a walk pipeline is live; ignore
        // them otherwise so a stray intent can't revive a dead service
        // with no walk to attach soundscape to (unlike controller
        // actions, there's nothing to restore from Room here).
        if (locationJob?.isActive != true) {
            // No live walk — most likely startService spun up a fresh
            // service instance after the walk ended (or after an OEM kill).
            // Stop it so we don't leave a started-but-unpromoted service
            // lingering, matching the null-intent bail path above.
            Log.w(TAG, "ignoring $action — no active walk pipeline")
            stopSelf()
            return
        }
        when (action) {
            ACTION_SET_SOUNDSCAPE ->
                soundscapeOrchestrator.setManualSoundscapeRequested(
                    intent?.getBooleanExtra(EXTRA_SOUNDSCAPE_ON, false) == true,
                )
            ACTION_SELECT_SOUNDSCAPE -> {
                val id = intent?.getStringExtra(EXTRA_SOUNDSCAPE_ID)
                if (id.isNullOrBlank()) {
                    Log.w(TAG, "SELECT_SOUNDSCAPE with no id")
                } else {
                    soundscapeOrchestrator.selectSoundscape(id)
                }
            }
            ACTION_CLEAR_SOUNDSCAPE_SELECTION ->
                soundscapeOrchestrator.clearSoundscapeSelection()
        }
    }

    /**
     * U10 glance intake: the UI-process [org.walktalkmeditate.pilgrim
     * .walk.seek.SeekOrchestrator] publishes a changed seek glance over
     * the [org.walktalkmeditate.pilgrim.walk.WalkActionPublisher]
     * intent channel; this process (the renderer) stores it and
     * re-renders immediately — a changed glance is a notify trigger,
     * not something to sit on until the next state emission. Port spec:
     * docs/parity/2026-07-14-port-seek-glance-u10.md B3.
     */
    private fun handleSeekGlanceAction(intent: Intent?) {
        when (decideSeekGlanceAction(pipelineActive = locationJob?.isActive == true)) {
            SeekGlanceAction.StopNoPipeline -> {
                // Mirror the soundscape-action guard: a stray glance
                // intent must not leave a started-but-unpromoted service
                // lingering.
                Log.w(TAG, "ignoring seek glance — no active walk pipeline")
                stopSelf()
            }
            SeekGlanceAction.StoreAndRender -> {
                latestSeekGlance = seekGlanceFromExtras(
                    present = intent?.getBooleanExtra(EXTRA_SEEK_GLANCE_PRESENT, false) == true,
                    bucketMeters = intent?.getIntExtra(EXTRA_SEEK_GLANCE_BUCKET, 0) ?: 0,
                    directionName = intent?.getStringExtra(EXTRA_SEEK_GLANCE_DIRECTION),
                    isComplete = intent?.getBooleanExtra(EXTRA_SEEK_GLANCE_COMPLETE, false) == true,
                )
                updateNotification(controller.state.value)
            }
        }
    }

    /**
     * A walker's command from a card or the listening chip. The OS
     * redelivers every start after a kill, commands included; a replayed
     * skip or rate would act twice, so a redelivered command is dropped
     * here, and the session's sequence number drops any replay that
     * slips past (plan U17, the non-redelivery rule).
     */
    private fun handleHonorCommand(intent: Intent?, redelivered: Boolean) {
        val action = decideHonorCommandAction(
            honorEnabled = releaseFlags.get().honor,
            redelivered = redelivered,
            pipelineActive = locationJob?.isActive == true,
        )
        when (action) {
            HonorCommandAction.StopNoPipeline -> {
                Log.w(TAG, "ignoring an Honor command — no active walk pipeline")
                stopSelf()
            }
            HonorCommandAction.Ignore -> Log.i(TAG, "Honor command ignored (redelivered=$redelivered)")
            HonorCommandAction.Apply -> {
                val seq = intent?.getLongExtra(EXTRA_HONOR_COMMAND_SEQ, 0L) ?: 0L
                val command = honorCommandFromExtras(
                    kind = intent?.getStringExtra(EXTRA_HONOR_COMMAND),
                    momentId = intent?.getStringExtra(EXTRA_HONOR_MOMENT_ID),
                    fraction = intent?.getDoubleExtra(EXTRA_HONOR_SCRUB_FRACTION, 0.0) ?: 0.0,
                )
                if (command == null || seq <= 0L) {
                    Log.w(TAG, "malformed Honor command dropped")
                    return
                }
                honorSession?.command(seq, command)
            }
        }
    }

    /**
     * A guide prompt or a recording starting or ending in the UI (plan U18).
     * The OS redelivers every start after a kill, gate intents included; a
     * replayed start's Binder can outlive the gate it held, so a redelivered
     * gate is dropped, and the UI re-sends its gates for the bumped
     * generation instead.
     */
    private fun handleUiAudioGate(intent: Intent?, redelivered: Boolean) {
        val action = decideUiAudioGateAction(
            honorEnabled = releaseFlags.get().honor,
            redelivered = redelivered,
            pipelineActive = locationJob?.isActive == true,
        )
        when (action) {
            UiAudioGateAction.StopNoPipeline -> {
                Log.w(TAG, "ignoring a UI audio gate — no active walk pipeline")
                stopSelf()
            }
            UiAudioGateAction.Ignore -> Log.i(TAG, "UI audio gate ignored (redelivered=$redelivered)")
            UiAudioGateAction.Apply -> {
                val signal = uiAudioGateSignalFromExtras(intent)
                if (signal == null) {
                    Log.w(TAG, "malformed UI audio gate dropped")
                    return
                }
                uiAudioGateProvider.get().apply(signal)
            }
        }
    }

    /**
     * "Seek anew", the walker's sonar settings, or a seek session handed
     * over after its walk started, for the seek session here (plan U25). A
     * redelivered one is dropped: it already acted before the kill, and the
     * row it wrote is what a revival reads.
     */
    private fun handleSeekIntent(action: String, intent: Intent?, redelivered: Boolean) {
        val decision = decideSeekIntentAction(
            placement = SeekPlacement.of(releaseFlags.get().honor),
            redelivered = redelivered,
            pipelineActive = locationJob?.isActive == true,
        )
        when (decision) {
            SeekIntentAction.StopNoPipeline -> {
                Log.w(TAG, "ignoring a seek intent — no active walk pipeline")
                stopSelf()
            }
            SeekIntentAction.Ignore -> Log.i(TAG, "seek intent ignored (redelivered=$redelivered)")
            SeekIntentAction.Apply -> applySeekIntent(action, intent)
        }
    }

    private fun applySeekIntent(action: String, intent: Intent?) {
        val session = seekSession ?: return
        when (action) {
            ACTION_SEEK_SESSION -> {
                val start = seekStartFromExtras(intent)
                if (start == null) {
                    Log.w(TAG, "malformed seek session dropped")
                    return
                }
                scope.launch {
                    val result = session.attach(scope, start, controller.state)
                    Log.i(TAG, "late seek session: ${result::class.simpleName}")
                }
            }
            else -> {
                val seq = intent?.getLongExtra(EXTRA_SEEK_SEQ, 0L) ?: 0L
                if (seq <= 0L) {
                    Log.w(TAG, "unnumbered seek intent dropped")
                    return
                }
                if (action == ACTION_SEEK_ANEW) {
                    session.seekAnew(seq)
                } else {
                    session.applyPreferences(seq, seekSonarSettingsFromExtras(intent))
                }
            }
        }
    }

    /** With Seek here, its glance is this process's own; otherwise the UI's last published one (U10). */
    private fun currentSeekGlance(): SeekGlanceState? = seekSession?.glance?.value ?: latestSeekGlance

    /**
     * The system's last fix, for a fresh Honor session's Begin input: the
     * nearest thing `:tracker` has to iOS's replayed pre-Start fix. Bounded
     * so a slow provider only delays the first recorded sample briefly.
     */
    private suspend fun lastKnownFix(): LocationPoint? = try {
        withTimeoutOrNull(LAST_KNOWN_FIX_TIMEOUT_MS) { locationSource.lastKnownLocation() }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (e: Exception) {
        Log.w(TAG, "last known fix unavailable: ${e::class.simpleName}")
        null
    }

    private fun handleControllerAction(action: String, intent: Intent?) {
        scope.launch {
            // Robustness path: if service was destroyed
            // (locationJob inactive) but a UI action or
            // notification tap arrives, restore the walk from Room
            // first so the action targets the in-progress walk
            // instead of bailing silently. Pre-:tracker-split this
            // path was rare; under the split the tracker service can
            // be torn down (FGS timeout, OEM cleanup) while the
            // process remains and Room still holds the active walk —
            // ACTION_FINISH on such a recreated service used to no-
            // op and the walk would never get its end_timestamp set.
            if (locationJob?.isActive != true) {
                val restored = runCatching { controller.restoreActiveWalk() }
                    .onFailure { Log.w(TAG, "restoreActiveWalk in action handler failed", it) }
                    .getOrNull()
                if (restored == null && controller.state.value is WalkState.Idle) {
                    Log.w(TAG, "no active walk to apply $action to — bailing")
                    // Clear any orphan notification posted by a prior
                    // process instance (FGS notifications are normally
                    // cleared on service-destroy, but a stale
                    // notification can outlive abnormal process
                    // termination).
                    getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                    stopSelf()
                    return@launch
                }
                Log.i(TAG, "applying $action on restored walk (service was inactive)")
            }
            try {
                when (action) {
                    ACTION_PAUSE -> controller.pauseWalk()
                    ACTION_RESUME -> controller.resumeWalk()
                    ACTION_START_MEDITATION -> controller.startMeditation()
                    ACTION_END_MEDITATION -> {
                        // EXTRA_END_MILLIS carries the captured Done-tap
                        // timestamp from the meditation screen so the
                        // closing 6.5s ceremony doesn't inflate the
                        // recorded interval. Notification-tap path
                        // omits it → controller falls back to its
                        // injected clock.
                        val endMillis = intent
                            ?.takeIf { it.hasExtra(EXTRA_END_MILLIS) }
                            ?.getLongExtra(EXTRA_END_MILLIS, -1L)
                            ?.takeIf { it > 0 }
                        controller.endMeditation(endMillis)
                    }
                    ACTION_MARK_WAYPOINT -> {
                        val label = intent?.getStringExtra(EXTRA_WAYPOINT_LABEL)
                        val icon = intent?.getStringExtra(EXTRA_WAYPOINT_ICON)
                        controller.recordWaypoint(label = label, icon = icon)
                    }
                    ACTION_FINISH -> controller.finishWalk()
                    ACTION_DISCARD -> controller.discardWalk()
                    ACTION_SET_INTENTION -> {
                        val text = intent?.getStringExtra(EXTRA_INTENTION) ?: ""
                        controller.setIntention(text)
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                // State-machine rejection (e.g. Pause from a transient Idle
                // window after a stale tap) or a repository write failure
                // must not crash the service scope. Sibling jobs (location
                // collector + notification observer) survive.
                Log.w(TAG, "controller action $action failed", t)
            }
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val ctx = applicationContext
        val fineGranted = ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val notifyGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                ctx,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return fineGranted && notifyGranted
    }

    private fun promoteToForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // location keeps the process alive; mediaPlayback covers the
            // background whisper / soundscape / voice-guide audio that
            // plays with the screen locked (see AndroidManifest comment +
            // BackgroundWhisperAutoPlayer).
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.walk_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.walk_notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun actionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, WalkTrackingService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun buildNotification(state: WalkState): Notification {
        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(DeepLinkTarget.EXTRA_DEEP_LINK, DeepLinkTarget.DEEP_LINK_ACTIVE_WALK)
        }
        val contentPending = PendingIntent.getActivity(
            this,
            REQUEST_CODE_CONTENT,
            activityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(
                walkNotificationText(
                    this,
                    state,
                    unitsPreferences.distanceUnits.value,
                    currentSeekGlance(),
                    honorSession?.glance?.value,
                    honorUnits(),
                ),
            )
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPending)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(buildLockScreenNotification(state))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        addWalkActionsForState(builder, this, state, notificationActions)
        return builder.build()
    }

    private fun buildLockScreenNotification(state: WalkState): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.walk_notification_lock_screen_title))
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        addWalkActionsForState(builder, this, state, notificationActions)
        return builder.build()
    }

    /**
     * State-class fingerprint + 5 m distance bucket (wander) or the
     * seek glance (Active seek). The wander notification text formats
     * distance with `%.2f km` (HALF_UP rounding at the 0.005 km = 5 m
     * boundary), so a 10 m bucket would skip every second display tick
     * — visible as up to 5 m of stale km on the notification. 5 m
     * alignment matches the rounding boundary exactly. Notify-rate
     * stays in the ~100/walk range (vs the untrottled ~5400/walk),
     * well below any vendor's update-suppression threshold. Active
     * seek walks swap the distance component for the glance — the
     * 100 m bucket IS the seek line's display rounding — plus a 15 s
     * floor so the walked-distance prefix never goes long-stale
     * (U10 port spec B4).
     */
    private var lastNotifiedFingerprint: Long = -1L
    private var lastNotifyElapsedMillis: Long = 0L

    /** The UI-process orchestrator's latest published glance (U10). */
    private var latestSeekGlance: SeekGlanceState? = null

    /** A revival without start extras has no fixed units; it speaks the ones this process reads. */
    private fun honorUnits(): UnitSystem = honorGlanceUnits ?: unitsPreferences.distanceUnits.value

    private fun updateNotification(state: WalkState) {
        val honorGlance = honorSession?.glance?.value
        val fingerprint = notificationFingerprint(
            state = state,
            seekGlance = currentSeekGlance(),
            unitsOrdinal = unitsPreferences.distanceUnits.value.ordinal.toLong(),
            honorGlance = honorGlance,
            honorUnits = honorUnits(),
        )
        val nowElapsed = SystemClock.elapsedRealtime()
        val notify = shouldNotify(
            fingerprint = fingerprint,
            lastFingerprint = lastNotifiedFingerprint,
            floorApplies = notifyFloorApplies(state, honorGlance),
            millisSinceLastNotify = nowElapsed - lastNotifyElapsedMillis,
        )
        if (!notify) return
        lastNotifiedFingerprint = fingerprint
        lastNotifyElapsedMillis = nowElapsed
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    /**
     * What the state-collector should do for the just-observed [state],
     * given whether the service has previously seen any in-progress
     * state. Returns the new latch value and the action.
     *
     * Pure function — extracted so the discard self-stop path can be
     * unit-tested without standing up a full Robolectric service +
     * Hilt environment. See `WalkTrackingServiceDecisionTest`.
     */
    internal enum class StateAction { SelfStop, UpdateNotification }

    /**
     * Decision taken by [startTracking] against the controller's current
     * state + the intent's `EXTRA_FRESH_START` flag + the result of an
     * already-attempted `restoreActiveWalk`. Pure shape so the
     * regression that left `Finished + isFreshStart` un-dispatched can
     * be locked down by a unit test instead of waiting for on-device QA.
     */
    internal enum class StartAction {
        /** Call `controller.startWalk(intentionExtra)`. */
        StartFresh,

        /** `restoreActiveWalk` already adopted an in-progress walk —
         *  the collector below picks up the live state, no further
         *  controller call is needed (Idle revival path). */
        AdoptRestored,

        /** Nothing actionable for this intent — stop the service. */
        StopNoWalk,

        /** Controller already in an in-progress state (e.g., race with
         *  another ACTION_START); leave it alone and let the collector
         *  keep recording. */
        IgnoreInProgress,
    }

    /**
     * What [handleSeekGlanceAction] should do given whether the location
     * pipeline is live — pure per the [decideStateAction] precedent so
     * the stray-intent stop path (a late glance intent after the tracker
     * pipeline tore down) is unit-testable without a Hilt service.
     */
    internal enum class SeekGlanceAction { StopNoPipeline, StoreAndRender }

    /** The walk-uuid replay guard on [StartAction.StartFresh] (plan U17's `:tracker` rules). */
    internal enum class FreshStartAction {
        /** No uuid, or one Room has never seen: insert the walk. */
        Insert,

        /** A redelivered start whose walk is still unfinished: adopt it, as any restored walk. */
        AdoptExisting,

        /** A redelivered start whose walk, or its Honor marker, shows it finished. */
        StopFinished,
    }

    /** What the location job does with the Honor session once the start has resolved. */
    internal sealed interface HonorSessionAction {
        /** No honor walk in progress: end any session a cached process holds. */
        data object Stop : HonorSessionAction

        /** Start or revive the session of [walkId]; the session decides which from Room. */
        data class Start(val walkId: Long) : HonorSessionAction
    }

    internal enum class HonorCommandAction { StopNoPipeline, Ignore, Apply }

    /** What the location job does with the seek session once the start has resolved (plan U25). */
    internal sealed interface SeekSessionAction {
        /** No seek walk in progress: end any session a cached process holds. */
        data object Stop : SeekSessionAction

        /** Start or revive the session of [walkId]; the session decides which from Room. */
        data class Start(val walkId: Long) : SeekSessionAction
    }

    internal enum class SeekIntentAction { StopNoPipeline, Ignore, Apply }

    internal enum class UiAudioGateAction { StopNoPipeline, Ignore, Apply }

    companion object {
        /**
         * Per-process "is the FGS alive in THIS process" flag. Set in
         * onCreate / cleared in onDestroy. Used by the same-process
         * decideStateAction path — the service queries its own state,
         * so the per-process scope is correct here.
         *
         * **Do NOT read this from the UI process** — under the
         * `:tracker` process split the flag is only ever set in
         * `:tracker`, so UI reads always see false. Cross-process
         * callers must use [isFgsAlive] instead, which queries
         * ActivityManager.getRunningServices for the canonical answer.
         */
        private val isRunning = java.util.concurrent.atomic.AtomicBoolean(false)

        /**
         * Cross-process "is the WalkTrackingService alive in any
         * process of this app" query. Called by
         * `MainActivity.onCreate` to discriminate the warm-launch-
         * after-swipe case (FGS gone, walk row stale → recover) from
         * the notification-tap-to-return case (FGS still alive in
         * `:tracker`, do not finalize a live walk).
         *
         * Under the `:tracker` process split, the previous
         * `isRunning.get()` implementation was unsafe: the static is
         * only set in the `:tracker` process, so the UI process's
         * classloader copy stays false forever and warm-launch
         * recovery would falsely finalize the live walk on every
         * re-open of the app.
         *
         * [ActivityManager.getRunningServices] remains accessible to
         * the calling app for its own services on API 26+ — the
         * third-party restriction documented in the API only applies
         * to OTHER apps' services. We pass `Int.MAX_VALUE` because
         * the list always contains the caller's services regardless
         * of the limit.
         */
        fun isFgsAlive(context: android.content.Context): Boolean {
            val am = context.getSystemService(android.app.ActivityManager::class.java)
                ?: return false
            val name = WalkTrackingService::class.java.name
            return try {
                @Suppress("DEPRECATION")
                am.getRunningServices(Int.MAX_VALUE)
                    .any { it.service.className == name && it.foreground }
            } catch (t: Throwable) {
                // ActivityManager can throw on some hardened ROMs.
                // Fall back to the conservative answer (assume FGS
                // alive) so we DO NOT finalize a possibly-live walk.
                // The warm-launch recovery is a backstop; a missed
                // recovery just means the user sees the walk re-open
                // — far less harmful than tombstoning a live walk.
                android.util.Log.w(
                    "WalkTrackingService",
                    "isFgsAlive: ActivityManager query failed, assuming alive",
                    t,
                )
                true
            }
        }

        /**
         * The value [onStartCommand] returns. Named so a Robolectric
         * test can pin the contract without Hilt-injecting the service
         * (the project deliberately has no hilt-android-testing dep —
         * see [WalkTrackingServiceDiscardTest]'s rationale). Mirrors the
         * [decideStateAction] pure-extraction precedent.
         *
         * START_REDELIVER_INTENT (not START_NOT_STICKY): an OEM
         * power-manager mid-walk kill is revived by the OS WITH the last
         * ACTION_START intent re-delivered, so [startTracking] re-runs
         * and [WalkController.restoreActiveWalk] rebuilds the live walk
         * from the unfinished Room row. START_STICKY is still wrong (it
         * revives with a null intent → no pipeline + API 31+ FGS-start
         * timeout crash); REDELIVER_INTENT carries the real ACTION_START.
         */
        const val START_MODE: Int = Service.START_REDELIVER_INTENT

        const val ACTION_START = "org.walktalkmeditate.pilgrim.service.WalkTrackingService.START"
        const val ACTION_PAUSE = "org.walktalkmeditate.pilgrim.service.WalkTrackingService.PAUSE"
        const val ACTION_RESUME = "org.walktalkmeditate.pilgrim.service.WalkTrackingService.RESUME"
        const val ACTION_START_MEDITATION =
            "org.walktalkmeditate.pilgrim.service.WalkTrackingService.START_MEDITATION"
        const val ACTION_END_MEDITATION =
            "org.walktalkmeditate.pilgrim.service.WalkTrackingService.END_MEDITATION"
        const val ACTION_MARK_WAYPOINT =
            "org.walktalkmeditate.pilgrim.service.WalkTrackingService.MARK_WAYPOINT"
        const val ACTION_FINISH = "org.walktalkmeditate.pilgrim.service.WalkTrackingService.FINISH"
        const val ACTION_DISCARD = "org.walktalkmeditate.pilgrim.service.WalkTrackingService.DISCARD"
        const val ACTION_SET_INTENTION =
            "org.walktalkmeditate.pilgrim.service.WalkTrackingService.SET_INTENTION"
        const val ACTION_UPDATE_SEEK_GLANCE =
            "org.walktalkmeditate.pilgrim.service.WalkTrackingService.UPDATE_SEEK_GLANCE"
        const val ACTION_SET_SOUNDSCAPE =
            "org.walktalkmeditate.pilgrim.service.WalkTrackingService.SET_SOUNDSCAPE"
        const val ACTION_SELECT_SOUNDSCAPE =
            "org.walktalkmeditate.pilgrim.service.WalkTrackingService.SELECT_SOUNDSCAPE"
        const val ACTION_CLEAR_SOUNDSCAPE_SELECTION =
            "org.walktalkmeditate.pilgrim.service.WalkTrackingService.CLEAR_SOUNDSCAPE_SELECTION"
        const val ACTION_HONOR_COMMAND =
            "org.walktalkmeditate.pilgrim.service.WalkTrackingService.HONOR_COMMAND"

        /** Extra: the uuid the Honor Begin use case minted for the walk, on
         *  [ACTION_START]. The replay guard of a redelivered start. */
        const val EXTRA_WALK_UUID = "extra.walk_uuid"

        /** Extra: the Way an honor walk follows, on [ACTION_START]. Its
         *  presence is what makes the start an Honor start. */
        const val EXTRA_HONOR_WAY_ID = "extra.honor_way_id"

        /** Extra: [HonorSettings.voicesEnabled], frozen at Start. Boolean. */
        const val EXTRA_HONOR_VOICES_ENABLED = "extra.honor_voices_enabled"

        /** Extra: [HonorSettings.softTapEnabled], frozen at Start. Boolean. */
        const val EXTRA_HONOR_SOFT_TAP_ENABLED = "extra.honor_soft_tap_enabled"

        /** Extra: the [UnitSystem] name the Honor glance speaks for the
         *  walk (iOS fixes the Live Activity's units at its start). */
        const val EXTRA_HONOR_GLANCE_UNITS = "extra.honor_glance_units"

        /** Extra: the command's sequence number, rising across UI restarts. Long. */
        const val EXTRA_HONOR_COMMAND_SEQ = "extra.honor_command_seq"

        /** Extra: which [HonorCommand], by its wire name ([HONOR_COMMAND_SKIP] and the rest). */
        const val EXTRA_HONOR_COMMAND = "extra.honor_command"

        /** Extra: the moment a toggle, pause or resume, scrub, skip, or reply names. */
        const val EXTRA_HONOR_MOMENT_ID = "extra.honor_moment_id"

        /** Extra: a scrub's fraction of the voice. Double. */
        const val EXTRA_HONOR_SCRUB_FRACTION = "extra.honor_scrub_fraction"

        const val ACTION_UI_AUDIO_GATE =
            "org.walktalkmeditate.pilgrim.service.WalkTrackingService.UI_AUDIO_GATE"

        const val ACTION_SEEK_ANEW = "org.walktalkmeditate.pilgrim.service.WalkTrackingService.SEEK_ANEW"
        const val ACTION_SEEK_PREFERENCES =
            "org.walktalkmeditate.pilgrim.service.WalkTrackingService.SEEK_PREFERENCES"
        const val ACTION_SEEK_SESSION = "org.walktalkmeditate.pilgrim.service.WalkTrackingService.SEEK_SESSION"

        /** Extra: a seek intent's sequence number, rising across UI restarts. Long. */
        const val EXTRA_SEEK_SEQ = "extra.seek_seq"

        /** Extra: the hand-off's chain, as [SeekChainCodec] writes it. Its presence makes a start a seek hand-off. */
        const val EXTRA_SEEK_CHAIN = "extra.seek_chain"

        /** Extra: the clearing the pre-departure engine stood on. Int. */
        const val EXTRA_SEEK_ACTIVE_INDEX = "extra.seek_active_index"

        /** Extra: the duration chosen at setup. Int. */
        const val EXTRA_SEEK_DURATION_MINUTES = "extra.seek_duration_minutes"

        /** Extra: the celestial fog tint, absent under an ordinary sky. */
        const val EXTRA_SEEK_TINT_HEX = "extra.seek_tint_hex"

        /** Extra: the setup's seed, as its 64 bits. Long. */
        const val EXTRA_SEEK_SEED = "extra.seek_seed"

        /** Extra: when the seed was drawn. Long. */
        const val EXTRA_SEEK_SEEDED_AT = "extra.seek_seeded_at"

        /** Extra: the intention voiced at setup, which a reroll re-asks with. */
        const val EXTRA_SEEK_INTENTION = "extra.seek_intention"

        /** Extra: when the pre-departure engine's next pulse was due. Long; absent with none scheduled. */
        const val EXTRA_SEEK_PULSE_DUE_AT = "extra.seek_pulse_due_at"

        /** Extra: the sonar switch. Boolean. */
        const val EXTRA_SEEK_SONAR_ENABLED = "extra.seek_sonar_enabled"

        /** Extra: the sonar volume, 0 to 1. Float. */
        const val EXTRA_SEEK_SONAR_VOLUME = "extra.seek_sonar_volume"

        /** Extra: the master Sounds switch. Boolean. */
        const val EXTRA_SEEK_SOUNDS_ENABLED = "extra.seek_sounds_enabled"

        /** Extra: which UI gate, by [org.walktalkmeditate.pilgrim.audio.walk.UiAudioGateKind.wireName]. */
        const val EXTRA_UI_AUDIO_GATE = "extra.ui_audio_gate"

        /** Extra: true for a gate that started, false for one that ended. Boolean. */
        const val EXTRA_UI_AUDIO_GATE_HELD = "extra.ui_audio_gate_held"

        /** Extra: the gate's sequence number, rising across UI restarts. Long. */
        const val EXTRA_UI_AUDIO_GATE_SEQ = "extra.ui_audio_gate_seq"

        /** Extra: a Bundle holding a started gate's Binder under [UI_AUDIO_GATE_TOKEN_KEY]. */
        const val EXTRA_UI_AUDIO_GATE_TOKEN = "extra.ui_audio_gate_token"
        const val UI_AUDIO_GATE_TOKEN_KEY = "token"

        const val HONOR_COMMAND_TOGGLE_PLAYBACK = "toggle_playback"
        const val HONOR_COMMAND_PAUSE_RESUME = "pause_resume"
        const val HONOR_COMMAND_SCRUB = "scrub"
        const val HONOR_COMMAND_SKIP = "skip"
        const val HONOR_COMMAND_CYCLE_RATE = "cycle_rate"
        const val HONOR_COMMAND_PLAY_REPLY = "play_reply"

        /** Extra: starting walk's intention text, or new intention on
         *  [ACTION_SET_INTENTION]. UTF-8 string, ≤140 chars (server-side
         *  controller still re-sanitizes). */
        const val EXTRA_INTENTION = "extra.intention"

        /** Extra: flag distinguishing a UI-initiated [ACTION_START]
         *  (where the service must insert the walk row) from a
         *  START_REDELIVER_INTENT revival (where the service restores
         *  from an existing Room row). Boolean. */
        const val EXTRA_FRESH_START = "extra.fresh_start"

        /** Extra: the walk's [org.walktalkmeditate.pilgrim.domain.WalkMode]
         *  enum name on [ACTION_START]. Absent/unknown → Wander
         *  (forward-compat, same convention as WalkEventType.UNKNOWN).
         *  Only the fresh-start path consumes it; restore paths re-derive
         *  mode from the persisted SEEK_MODE walk event. */
        const val EXTRA_WALK_MODE = "extra.walk_mode"

        /** Extra: explicit Done-tap millis for [ACTION_END_MEDITATION].
         *  Long. Absent → service uses its own clock. */
        const val EXTRA_END_MILLIS = "extra.end_millis"

        /** Extra: optional waypoint label (UTF-8 string) for
         *  [ACTION_MARK_WAYPOINT]. */
        const val EXTRA_WAYPOINT_LABEL = "extra.waypoint_label"

        /** Extra: optional waypoint icon key (UTF-8 string) for
         *  [ACTION_MARK_WAYPOINT]. */
        const val EXTRA_WAYPOINT_ICON = "extra.waypoint_icon"

        /** Extra: whether [ACTION_UPDATE_SEEK_GLANCE] carries a glance.
         *  Boolean; false ≙ iOS `seek: nil` — clears the stored glance
         *  (mid-walk this happens right after a reveal, while the
         *  engine's distance is null until the next fix). */
        const val EXTRA_SEEK_GLANCE_PRESENT = "extra.seek_glance_present"

        /** Extra: the glance's 100 m distance bucket in meters. Int. */
        const val EXTRA_SEEK_GLANCE_BUCKET = "extra.seek_glance_bucket"

        /** Extra: [org.walktalkmeditate.pilgrim.domain.seek
         *  .SeekDirectionHint] enum name. Absent or unknown → no hint
         *  (same forgiving-wire convention as [EXTRA_WALK_MODE]). */
        const val EXTRA_SEEK_GLANCE_DIRECTION = "extra.seek_glance_direction"

        /** Extra: the glance's terminal "seeking complete" flag. Boolean. */
        const val EXTRA_SEEK_GLANCE_COMPLETE = "extra.seek_glance_complete"

        /** Extra: desired walk-long soundscape on/off for
         *  [ACTION_SET_SOUNDSCAPE]. Boolean. */
        const val EXTRA_SOUNDSCAPE_ON = "extra.soundscape_on"

        /** Extra: soundscape asset id for [ACTION_SELECT_SOUNDSCAPE].
         *  UTF-8 string. */
        const val EXTRA_SOUNDSCAPE_ID = "extra.soundscape_id"

        private const val TAG = "WalkTrackingService"
        /** Persist `walk.steps` this often while Active so a mid-walk
         *  OEM kill recovers with the last live counter intact. */
        private const val STEP_FLUSH_INTERVAL_MS = 30_000L
        private const val LAST_KNOWN_FIX_TIMEOUT_MS = 1_000L
        private const val CHANNEL_ID = "walk_tracking"
        private const val NOTIFICATION_ID = 1
        private const val REQUEST_CODE_CONTENT = 0
        private const val REQUEST_CODE_PAUSE = 1
        private const val REQUEST_CODE_RESUME = 2
        private const val REQUEST_CODE_END_MEDITATION = 3
        private const val REQUEST_CODE_MARK_WAYPOINT = 4
        private const val REQUEST_CODE_FINISH = 5

        fun startIntent(context: Context): Intent =
            Intent(context, WalkTrackingService::class.java).apply { action = ACTION_START }

        /**
         * Pure decision: given the latest observed [state] and whether
         * the service has seen any in-progress state since onCreate,
         * return the new latch value and what the collector should do.
         *
         * Behavior:
         *  - Active|Paused|Meditating → UpdateNotification + flip latch true.
         *  - Idle / Finished when latch=true → SelfStop (we entered an
         *    in-progress state and now left it — the walk is over).
         *  - Idle / Finished when latch=false → UpdateNotification
         *    (startup snapshot of the cached @Singleton controller's
         *    state from a prior walk — locationJob's controller.startWalk
         *    is about to transition state into the active range).
         *
         * The Finished+!hasBeenActive=UpdateNotification arm is critical
         * for the second-walk-in-cached-tracker case. Without it, the
         * notificationJob's first emission (Finished, from walk N-1's
         * @Singleton state) races the locationJob's controller.startWalk
         * dispatch: SelfStop → onDestroy → scope.cancel() can interrupt
         * startWalk after `repository.startWalk` (row insert) but before
         * `_state.value = Active`. The walk row lands in Room but the
         * controller stays Finished — subsequent ACTION_FINISH no-ops
         * (`reduceFinished(Finish) → effect=None`) and the walk row's
         * endTimestamp is never set. The Idle case already used this
         * latch pattern; Finished needs it too.
         */
        internal fun decideStateAction(
            state: WalkState,
            hasBeenActive: Boolean,
        ): Pair<Boolean, StateAction> {
            val nextLatch = hasBeenActive ||
                state is WalkState.Active ||
                state is WalkState.Paused ||
                state is WalkState.Meditating
            val action = when {
                state is WalkState.Active ||
                    state is WalkState.Paused ||
                    state is WalkState.Meditating -> StateAction.UpdateNotification
                hasBeenActive -> StateAction.SelfStop
                else -> StateAction.UpdateNotification
            }
            return nextLatch to action
        }

        /**
         * Pure decision for [startTracking]: given the controller's
         * current state, whether the intent carries `EXTRA_FRESH_START`,
         * and whether `restoreActiveWalk` already adopted an
         * in-progress Room walk, return the action to apply.
         *
         * Crucial Finished case: the `:tracker` process commonly
         * survives between walks (Android keeps it cached), so the
         * @Singleton `WalkController` sits in `Finished` until the next
         * `startWalk`. `WalkReducer.reduceFinished` accepts `Start →
         * startFresh`, but this service used to gate the dispatch on
         * `Idle` only — silently dropping the second walk. Treat
         * `Finished + isFreshStart` the same as the fresh `Idle` path.
         */
        internal fun decideStartAction(
            state: WalkState,
            isFreshStart: Boolean,
            hasRestoredWalk: Boolean,
        ): StartAction = when {
            state is WalkState.Idle && hasRestoredWalk -> StartAction.AdoptRestored
            state is WalkState.Idle && isFreshStart -> StartAction.StartFresh
            state is WalkState.Idle -> StartAction.StopNoWalk
            state is WalkState.Finished && isFreshStart -> StartAction.StartFresh
            state is WalkState.Finished -> StartAction.StopNoWalk
            else -> StartAction.IgnoreInProgress
        }

        // ─── U17 Honor (plan docs/plans/2026-09-29-001-feat-honor-groundwork-own-shared-walks-plan.md U17) ───

        /**
         * The uuid replay guard for [StartAction.StartFresh]. The OS's
         * redelivery is the main revival after an OEM kill, and it replays
         * the original fresh start: Room, not the stale extras, says what
         * that start still means. [walkWithUuid] is the walk under the
         * start's uuid; [hasHonorMarker] whether that uuid survives only in
         * its Honor marker (a finished walk the walker has since deleted).
         */
        internal fun decideFreshStart(
            walkUuid: String?,
            walkWithUuid: Walk?,
            hasHonorMarker: Boolean,
        ): FreshStartAction = when {
            walkUuid == null -> FreshStartAction.Insert
            walkWithUuid != null && walkWithUuid.endTimestamp == null -> FreshStartAction.AdoptExisting
            walkWithUuid != null -> FreshStartAction.StopFinished
            hasHonorMarker -> FreshStartAction.StopFinished
            else -> FreshStartAction.Insert
        }

        /** An honor walk in progress gets its session; anything else ends the one a cached process holds. */
        internal fun decideHonorSessionAction(state: WalkState): HonorSessionAction {
            val walk = state.inProgressWalk()?.takeIf { it.mode == WalkMode.Honor }
                ?: return HonorSessionAction.Stop
            return HonorSessionAction.Start(walk.walkId)
        }

        /**
         * A command with no pipeline must not leave a started-but-unpromoted
         * service behind (the seek-glance guard's twin). A redelivered one is
         * never applied: it already acted before the kill.
         */
        internal fun decideHonorCommandAction(
            honorEnabled: Boolean,
            redelivered: Boolean,
            pipelineActive: Boolean,
        ): HonorCommandAction = when {
            !pipelineActive -> HonorCommandAction.StopNoPipeline
            !honorEnabled || redelivered -> HonorCommandAction.Ignore
            else -> HonorCommandAction.Apply
        }

        /** A seek walk in progress gets its session; anything else ends the one a cached process holds. */
        internal fun decideSeekSessionAction(state: WalkState): SeekSessionAction {
            val walk = state.inProgressWalk()?.takeIf { it.mode == WalkMode.Seek }
                ?: return SeekSessionAction.Stop
            return SeekSessionAction.Start(walk.walkId)
        }

        /**
         * [decideHonorCommandAction]'s rule for the seek intents: with Seek in
         * the UI process ([SeekPlacement.UI_PROCESS], the flag off) nothing
         * here takes them, and a redelivered one already acted before the kill.
         */
        internal fun decideSeekIntentAction(
            placement: SeekPlacement,
            redelivered: Boolean,
            pipelineActive: Boolean,
        ): SeekIntentAction = when {
            !pipelineActive -> SeekIntentAction.StopNoPipeline
            placement != SeekPlacement.TRACKER || redelivered -> SeekIntentAction.Ignore
            else -> SeekIntentAction.Apply
        }

        /**
         * Pure decode of a seek hand-off's extras, on ACTION_START or
         * [ACTION_SEEK_SESSION]: null without a chain, or with one that
         * doesn't decode, which leaves the walk a seek walk with no guidance.
         */
        internal fun seekStartFromExtras(intent: Intent?): SeekStart? {
            val chain = intent?.getStringExtra(EXTRA_SEEK_CHAIN)?.let(SeekChainCodec::decode) ?: return null
            return SeekStart(
                chain = chain,
                activeIndex = intent.getIntExtra(EXTRA_SEEK_ACTIVE_INDEX, 0),
                durationMinutes = intent.getIntExtra(EXTRA_SEEK_DURATION_MINUTES, 0),
                tintHex = intent.getStringExtra(EXTRA_SEEK_TINT_HEX),
                seed = intent.getLongExtra(EXTRA_SEEK_SEED, 0L),
                seededAtEpochMillis = intent.getLongExtra(EXTRA_SEEK_SEEDED_AT, 0L),
                intention = intent.getStringExtra(EXTRA_SEEK_INTENTION),
                nextPulseDueAtMillis = intent.takeIf { it.hasExtra(EXTRA_SEEK_PULSE_DUE_AT) }
                    ?.getLongExtra(EXTRA_SEEK_PULSE_DUE_AT, 0L),
                sonar = seekSonarSettingsFromExtras(intent),
            )
        }

        /** Pure decode of the sonar settings; a volume outside 0 to 1 is clamped, and NaN reads as silent. */
        internal fun seekSonarSettingsFromExtras(intent: Intent?): SeekSonarSettings {
            val volume = intent?.getFloatExtra(EXTRA_SEEK_SONAR_VOLUME, 0f) ?: 0f
            return SeekSonarSettings(
                sonarEnabled = intent?.getBooleanExtra(EXTRA_SEEK_SONAR_ENABLED, false) == true,
                sonarVolume = if (volume.isNaN()) 0f else volume.coerceIn(0f, 1f),
                soundsEnabled = intent?.getBooleanExtra(EXTRA_SEEK_SOUNDS_ENABLED, false) == true,
            )
        }

        /** [decideHonorCommandAction]'s rule: a redelivered gate already acted before the kill. */
        internal fun decideUiAudioGateAction(
            honorEnabled: Boolean,
            redelivered: Boolean,
            pipelineActive: Boolean,
        ): UiAudioGateAction = when {
            !pipelineActive -> UiAudioGateAction.StopNoPipeline
            !honorEnabled || redelivered -> UiAudioGateAction.Ignore
            else -> UiAudioGateAction.Apply
        }

        /** Pure decode of [ACTION_UI_AUDIO_GATE]'s extras; an unknown gate or an unnumbered one decodes to null. */
        internal fun uiAudioGateSignalFromExtras(intent: Intent?): UiAudioGateSignal? {
            val kind = UiAudioGateKind.fromWire(intent?.getStringExtra(EXTRA_UI_AUDIO_GATE)) ?: return null
            val seq = intent?.getLongExtra(EXTRA_UI_AUDIO_GATE_SEQ, 0L) ?: 0L
            if (seq <= 0L) return null
            return UiAudioGateSignal(
                kind = kind,
                held = intent?.getBooleanExtra(EXTRA_UI_AUDIO_GATE_HELD, false) == true,
                seq = seq,
                token = intent?.getBundleExtra(EXTRA_UI_AUDIO_GATE_TOKEN)?.getBinder(UI_AUDIO_GATE_TOKEN_KEY),
            )
        }

        /** Pure decode of [ACTION_HONOR_COMMAND]'s extras; an unknown kind or a missing moment decodes to null. */
        internal fun honorCommandFromExtras(kind: String?, momentId: String?, fraction: Double): HonorCommand? =
            when (kind) {
                HONOR_COMMAND_TOGGLE_PLAYBACK -> momentId?.let { HonorCommand.TogglePlayback(it) }
                HONOR_COMMAND_PAUSE_RESUME -> momentId?.let { HonorCommand.PauseResume(it) }
                HONOR_COMMAND_SCRUB -> momentId?.let { HonorCommand.Scrub(it, fraction) }
                HONOR_COMMAND_SKIP -> momentId?.let { HonorCommand.Skip(it) }
                HONOR_COMMAND_CYCLE_RATE -> HonorCommand.CycleRate
                HONOR_COMMAND_PLAY_REPLY -> momentId?.let { HonorCommand.PlayReply(it) }
                else -> null
            }

        /**
         * Reads [ACTION_START]'s extras once. With [honorEnabled] off the
         * uuid, the Way, and a seek hand-off are never read, so a stray Honor
         * start is an ordinary one and the controller starts it as a wander,
         * and a seek start is the UI-process seek it has always been.
         */
        internal fun startExtrasFrom(intent: Intent?, honorEnabled: Boolean): TrackerStartExtras {
            val wayId = intent?.getStringExtra(EXTRA_HONOR_WAY_ID)?.takeIf { honorEnabled }
            val honor = wayId?.let {
                HonorStart(
                    wayId = it,
                    settings = HonorSettings(
                        voicesEnabled = intent.getBooleanExtra(EXTRA_HONOR_VOICES_ENABLED, false),
                        softTapEnabled = intent.getBooleanExtra(EXTRA_HONOR_SOFT_TAP_ENABLED, false),
                    ),
                )
            }
            return TrackerStartExtras(
                isFreshStart = intent?.getBooleanExtra(EXTRA_FRESH_START, false) == true,
                request = WalkStartRequest(
                    intention = intent?.getStringExtra(EXTRA_INTENTION),
                    mode = WalkMode.fromWire(intent?.getStringExtra(EXTRA_WALK_MODE)),
                    walkUuid = intent?.getStringExtra(EXTRA_WALK_UUID)?.takeIf { honorEnabled },
                    honor = honor,
                    seek = seekStartFromExtras(intent)
                        ?.takeIf { SeekPlacement.of(honorEnabled) == SeekPlacement.TRACKER },
                ),
                honorGlanceUnits = intent?.getStringExtra(EXTRA_HONOR_GLANCE_UNITS)
                    ?.takeIf { honor != null }
                    ?.let { name -> UnitSystem.entries.firstOrNull { it.name == name } },
            )
        }

        // ─── U10 seek glance (port spec
        //     docs/parity/2026-07-14-port-seek-glance-u10.md B3/B4) ───

        /** ≙ iOS `WalkActivityManager.timeThreshold = 15` s. */
        internal const val SEEK_NOTIFY_FLOOR_MILLIS = 15_000L

        internal fun decideSeekGlanceAction(pipelineActive: Boolean): SeekGlanceAction =
            if (pipelineActive) SeekGlanceAction.StoreAndRender else SeekGlanceAction.StopNoPipeline

        /**
         * Pure decode of the [ACTION_UPDATE_SEEK_GLANCE] extras. An
         * unknown direction name collapses to no hint rather than
         * crashing on a stale intent from a future binary — the
         * [org.walktalkmeditate.pilgrim.domain.WalkMode.fromWire]
         * convention.
         */
        internal fun seekGlanceFromExtras(
            present: Boolean,
            bucketMeters: Int,
            directionName: String?,
            isComplete: Boolean,
        ): SeekGlanceState? {
            if (!present) return null
            val direction = directionName?.let { name ->
                SeekDirectionHint.entries
                    .firstOrNull { it.name == name }
            }
            return SeekGlanceState(
                distanceBucketMeters = bucketMeters,
                directionHint = direction,
                isComplete = isComplete,
            )
        }

        /**
         * Pack the state-class ordinal + a payload + the units ordinal
         * into one Long. State-class change always re-renders (action
         * set + text both depend on it); the units ordinal MUST be in
         * the fingerprint so a Settings toggle from Metric→Imperial
         * mid-walk re-renders immediately. The payload is the 5 m
         * distance bucket (matching the wander text's `%.2f km`
         * rounding granularity) for every state EXCEPT Active seek,
         * where it is the glance — presence, 100 m bucket, hint,
         * completion (≙ iOS `seek != lastSeekGlance`): distance ticks
         * alone never rebuild a seek notification, the 15 s floor
         * refreshes the walked-distance prefix instead (Samsung
         * update-suppression budget).
         *
         * An honor walk showing its glance, in any state, swaps the
         * payload for the glance's displayed line the same way (spec D
         * §10.3: iOS pushes on a glance change), so the fingerprint moves
         * only when the words on the notification do.
         */
        internal fun notificationFingerprint(
            state: WalkState,
            seekGlance: SeekGlanceState?,
            unitsOrdinal: Long,
            honorGlance: HonorGlanceState? = null,
            honorUnits: UnitSystem = UnitSystem.DEFAULT,
        ): Long {
            val classOrdinal = when (state) {
                WalkState.Idle -> 0L
                is WalkState.Active -> 1L
                is WalkState.Paused -> 2L
                is WalkState.Meditating -> 3L
                is WalkState.Finished -> 4L
            }
            val isActiveSeek = state is WalkState.Active &&
                state.walk.mode == WalkMode.Seek
            val shownHonor = shownHonorGlance(state, honorGlance)
            val payload = when {
                isActiveSeek -> seekGlancePayload(seekGlance)
                shownHonor != null -> HONOR_GLANCE_PAYLOAD_BASE + honorGlanceDisplayCode(shownHonor, honorUnits)
                state is WalkState.Active -> (state.walk.distanceMeters / 5.0).toLong()
                state is WalkState.Paused -> (state.walk.distanceMeters / 5.0).toLong()
                state is WalkState.Meditating -> (state.walk.distanceMeters / 5.0).toLong()
                else -> 0L
            }
            return classOrdinal * 100_000_000L + payload * 10L + unitsOrdinal
        }

        /**
         * Disjoint decimal slots: presence (10⁴), bucket÷100 ∈ 0..20
         * (10²), hint ordinal+1 ∈ 0..4 (10¹), completion (10⁰). Any
         * field change produces a distinct payload — the packed
         * equivalent of [org.walktalkmeditate.pilgrim.domain.seek
         * .SeekGlanceState]'s structural equality.
         */
        private fun seekGlancePayload(
            glance: SeekGlanceState?,
        ): Long {
            if (glance == null) return 0L
            val bucketSlot = (glance.distanceBucketMeters / 100).coerceIn(0, 20).toLong()
            val hintSlot = glance.directionHint?.let { it.ordinal + 1L } ?: 0L
            val completeSlot = if (glance.isComplete) 1L else 0L
            return 10_000L + bucketSlot * 100L + hintSlot * 10L + completeSlot
        }

        /** Clear of the seek payload's 10⁴ slot; [honorGlanceDisplayCode] stays under 10³. */
        private const val HONOR_GLANCE_PAYLOAD_BASE = 20_000L

        /**
         * The notify gate: any fingerprint change (state class, units,
         * glance — or, off seek, the 5 m distance bucket) notifies;
         * an Active seek walk ALSO notifies on the 15 s floor so the
         * walked-distance prefix stays fresh (≙ iOS `shouldPush`'s
         * `secondsSinceLastPush >= timeThreshold` arm,
         * `WalkActivityManager.swift:26-36@c1745e8`). Wander walks are
         * deliberately floor-free — their fingerprint already tracks
         * distance, so U10 changes nothing about their cadence.
         * [notifyFloorApplies] says which walks take the floor.
         */
        internal fun shouldNotify(
            fingerprint: Long,
            lastFingerprint: Long,
            floorApplies: Boolean,
            millisSinceLastNotify: Long,
        ): Boolean = fingerprint != lastFingerprint ||
            (floorApplies && millisSinceLastNotify >= SEEK_NOTIFY_FLOOR_MILLIS)

        /**
         * The walks whose fingerprint leaves out the walked distance: an
         * Active seek walk, and an Active honor walk showing its glance
         * (iOS keeps the 15 s arm for Honor, spec D §10.3). Paused and
         * sitting lines show no walked distance, so they need no floor.
         */
        internal fun notifyFloorApplies(state: WalkState, honorGlance: HonorGlanceState?): Boolean =
            state is WalkState.Active &&
                (state.walk.mode == WalkMode.Seek || shownHonorGlance(state, honorGlance) != null)
    }
}
