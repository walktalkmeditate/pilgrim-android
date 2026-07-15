// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk.seek

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.WalkEvent
import org.walktalkmeditate.pilgrim.data.entity.Waypoint
import org.walktalkmeditate.pilgrim.data.sounds.SoundsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.whisper.WhisperDefinition
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.WalkEventType
import org.walktalkmeditate.pilgrim.domain.WalkMode
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.domain.seek.SeekChain
import org.walktalkmeditate.pilgrim.domain.seek.SeekChainGenerator
import org.walktalkmeditate.pilgrim.domain.seek.SeekEngine
import org.walktalkmeditate.pilgrim.domain.seek.SeekEngineEvent
import org.walktalkmeditate.pilgrim.domain.seek.SeekEnginePhase
import org.walktalkmeditate.pilgrim.domain.seek.SeekFogModel
import org.walktalkmeditate.pilgrim.domain.seek.SeekFogState
import org.walktalkmeditate.pilgrim.domain.seek.SeekGlanceModel
import org.walktalkmeditate.pilgrim.domain.seek.SeekGlanceState
import org.walktalkmeditate.pilgrim.domain.seek.SeekPersistence
import org.walktalkmeditate.pilgrim.domain.seek.SeekPoint
import org.walktalkmeditate.pilgrim.domain.seek.SeekPowerTier
import org.walktalkmeditate.pilgrim.domain.seek.SeekPulseVisual
import org.walktalkmeditate.pilgrim.domain.seek.SeekSeed
import org.walktalkmeditate.pilgrim.domain.seek.SeekSeededGenerator
import org.walktalkmeditate.pilgrim.domain.seek.SeekTuning
import org.walktalkmeditate.pilgrim.audio.seek.SeekSoundPlaying
import org.walktalkmeditate.pilgrim.location.LocationSource

/**
 * Qualifier for the single-threaded [CoroutineScope] that owns the seek
 * session: the engine's state confinement contract (U3 port spec B15)
 * requires every engine interaction — collectors, `seekAnew`, `stop` —
 * to run on ONE dispatcher, and the fog math belongs on Default (U6/U7
 * dispatcher notes). Provided as
 * `Dispatchers.Default.limitedParallelism(1)`.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SeekScope

/**
 * Qualifier for the read-only `StateFlow<WalkState>` the orchestrator
 * observes — bound to `WalkController.state` like
 * `MeditationObservedWalkState`, so tests inject any flow without a
 * full controller.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SeekObservedWalkState

/**
 * Qualifier for the power-tier flow feeding the engine's pulse-clock
 * floor — bound to
 * [org.walktalkmeditate.pilgrim.power.SeekPowerTierSource.tiers] (a
 * cold callbackFlow: the engine collects it once per session in
 * `start()` and cancels it in `stop()`, so the receiver never leaks).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SeekPowerTiers

/**
 * Injectable seek side-effect hooks (iOS `SeekSenses`,
 * `ActiveWalkViewModel+Seek.swift:19-30@c1745e8`). Production defaults
 * are built in [org.walktalkmeditate.pilgrim.di.SeekModule]; tests swap
 * spies in. Deliberately absent vs iOS: `isAppActive` — Android seek
 * haptics fire regardless of foreground state (plan Key Decision; iOS
 * only gates because it discards background CoreHaptics), and
 * `seekCompleteSoundStopDelay` — the completion release lives inside
 * [SeekSoundPlaying.playCompletionBowl] (U5 spec §2.5). Tick/aligned
 * pulse haptics are not hooks here either: they ride
 * [SeekSoundPlaying.playPing]'s coupled path (U5 spec §3.3).
 */
class SeekSenses(
    val soundPlayer: SeekSoundPlaying,
    val arrivalHaptic: () -> Unit,
    val breathInHaptic: () -> Unit,
    val pickRevealWhisper: () -> WhisperDefinition?,
    val playWhisper: (WhisperDefinition) -> Unit,
    val revealWhisperDelayMillis: Long = REVEAL_WHISPER_DELAY_MS,
) {
    companion object {
        /** iOS `revealWhisperDelay = 2.5` (`ActiveWalkViewModel+Seek.swift:26`). */
        const val REVEAL_WHISPER_DELAY_MS = 2_500L
    }
}

/**
 * UI→tracker seek-glance transport seam (U10). Production binding in
 * [org.walktalkmeditate.pilgrim.di.SeekModule] forwards to
 * [org.walktalkmeditate.pilgrim.walk.WalkActionPublisher.publishSeekGlance];
 * tests record publishes without a Context. `null` ≙ iOS `seek: nil`
 * (clears the tracker's stored glance).
 */
fun interface SeekGlancePublisher {
    fun publish(glance: SeekGlanceState?)
}

/**
 * App-scoped owner of the live seek session: boots the engine from the
 * setup's pending session when a seek walk starts, routes engine events
 * to the senses (sonar, haptics, whisper) and persistence, feeds the
 * map's fog/pulse state and the notification's glance line (U10), and
 * implements "Seek anew". Port of the seek half of
 * `ActiveWalkViewModel+Seek.swift@c1745e8`; full contract in
 * `docs/parity/2026-07-14-port-seek-orchestrator-u9.md` +
 * `docs/parity/2026-07-14-port-seek-glance-u10.md`.
 *
 * Process topology (spec D1): UI process only — instantiated eagerly
 * from [org.walktalkmeditate.pilgrim.PilgrimApp.onCreate] (which
 * early-returns in `:tracker`), observing the process-local
 * `WalkController.state` exactly like `MeditationBellObserver` and
 * `VoiceGuideOrchestrator`. The engine's GPS feed is a UI-process FLP
 * subscription ([LocationSource.rawLocationFlow]); screen-off delivery
 * is entitled at the UID level by the `:tracker` location FGS. A
 * UI-process death mid-walk ends seek guidance but never recording —
 * the restored walk never reboots the engine (see [handleWalkState]).
 *
 * Restore-path filter (spec D3): the boot key is
 * [SeekSessionStore.pending], consumed at boot. A restored walk — new
 * process, in-memory store empty — can never satisfy the boot
 * condition, so no first-emission latch is needed.
 */
@Singleton
class SeekOrchestrator @Inject constructor(
    @SeekObservedWalkState private val walkState: StateFlow<@JvmSuppressWildcards WalkState>,
    @SeekScope private val scope: CoroutineScope,
    private val sessionStore: SeekSessionStore,
    private val repository: WalkRepository,
    private val locationSource: LocationSource,
    @SeekPowerTiers private val powerTiers: Flow<@JvmSuppressWildcards SeekPowerTier>,
    private val senses: SeekSenses,
    private val soundsPreferences: SoundsPreferencesRepository,
    private val clock: Clock,
    private val glancePublisher: SeekGlancePublisher,
    @ApplicationContext private val context: Context,
) {

    private val _fogState = MutableStateFlow<SeekFogState?>(null)

    /**
     * Map fog input for `PilgrimMap(seekFog =)`. Null on wander walks
     * and outside seek sessions — the renderer's `null == null` fast
     * path keeps wander maps off the style entirely.
     */
    val fogState: StateFlow<SeekFogState?> = _fogState.asStateFlow()

    private val _pulse = MutableStateFlow(SeekPulseVisual.NONE)

    /**
     * Map pulse input for `PilgrimMap(seekPulse =)`. Tokens start at 1
     * and advance monotonically per session (the renderer initializes
     * `lastHandledPulseToken = 0` and would swallow a token-0 pulse).
     */
    val pulse: StateFlow<SeekPulseVisual> = _pulse.asStateFlow()

    private val _enginePhase = MutableStateFlow<SeekEnginePhase?>(null)

    /**
     * The live engine's phase, null when no seek session is running.
     * Drives the options sheet (`COMPLETE` disables "Seek Anew") and is
     * the U10 glance seam's phase input.
     */
    val enginePhase: StateFlow<SeekEnginePhase?> = _enginePhase.asStateFlow()

    /** Pre-departure gate input for the options sheet (spec B11). */
    val pendingSession: StateFlow<SeekPendingSession?> get() = sessionStore.pending

    private val started = AtomicBoolean(false)

    // Session state — all confined to [scope]'s single thread.
    private var engine: SeekEngine? = null
    private var eventsJob: Job? = null
    private var fogJob: Job? = null
    private var sessionWalkId: Long? = null
    private var sessionIntention: String? = null
    private var tintHex: String? = null
    private var previousActiveBucket: Int? = null
    private var latestFix: LocationPoint? = null
    private var whisperGeneration = 0L
    private var publishedGlance: SeekGlanceState? = null
    private var hasPublishedGlance = false

    /** Started explicitly from PilgrimApp (visible, cancellable); idempotent. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            walkState.collect { state ->
                try {
                    handleWalkState(state)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    // One throwing transition must not silently kill seek
                    // routing for the process lifetime (Stage 5-D).
                    Log.w(TAG, "seek walk-state handling failed", t)
                }
            }
        }
    }

    /**
     * R17 "Seek anew": regenerates the remainder of the chain from the
     * walker's current position. Uncapped by design. A reroll re-asks —
     * the same intention, a new moment — so it is seeded like the
     * original generation (iOS `seekAnewRequested`,
     * `ActiveWalkViewModel+Seek.swift:196-210@c1745e8`). Dispatched onto
     * the seek scope: the engine is confined to it.
     *
     * Pre-departure (setup Ready, walk not started — engine not yet
     * booted) the reroll regenerates the PENDING session's chain
     * instead; no engine means no immediate pulse there (spec D8).
     */
    fun seekAnewRequested() {
        scope.launch {
            try {
                val live = engine
                if (live != null) {
                    val fix = latestFix
                    val point = fix?.let { SeekPoint(it.latitude, it.longitude) }
                        ?: accumulatorLocation()?.let { SeekPoint(it.latitude, it.longitude) }
                        ?: return@launch
                    live.seekAnew(
                        currentLocation = point,
                        seed = SeekSeed.make(
                            intention = sessionIntention,
                            momentEpochMillis = clock.now(),
                            fix = fix,
                        ),
                    )
                } else {
                    rerollPendingSession()
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "seek anew failed", t)
            }
        }
    }

    // ─── Walk-state observation ───────────────────────────────────────

    internal suspend fun handleWalkState(state: WalkState) {
        // StateFlow conflation can elide the terminal emission between
        // two walks under contention (the WalkLifecycleObserver-
        // documented race): a live engine observing a DIFFERENT walk's
        // in-progress state is a stale session — tear it down before
        // the boot check so arrivals can never persist to the old walk.
        val inProgressWalkId = when (state) {
            is WalkState.Active -> state.walk.walkId
            is WalkState.Paused -> state.walk.walkId
            is WalkState.Meditating -> state.walk.walkId
            else -> null
        }
        if (engine != null && inProgressWalkId != null && inProgressWalkId != sessionWalkId) {
            Log.w(TAG, "stale seek session (walk $sessionWalkId) under walk $inProgressWalkId — tearing down")
            // Keep the pending session: in this race it belongs to the
            // NEW walk and is about to be consumed by the boot below.
            teardownSession(clearPending = false)
        }
        when {
            state is WalkState.Active &&
                state.walk.mode == WalkMode.Seek &&
                engine == null -> {
                // Boot ONLY on (Active seek + pending session): a
                // restored walk (process death) has an empty in-memory
                // store; a wander walk fails the mode check; the UI
                // process's brief first-frame Wander (event row not yet
                // combined) simply boots on the next emission.
                val session = sessionStore.pending.value ?: return
                bootEngine(session, walkId = state.walk.walkId)
            }
            (state is WalkState.Finished || state is WalkState.Idle) && engine != null ->
                teardownSession()
            else -> Unit
        }
    }

    /** iOS `startSeekEngine` (`ActiveWalkViewModel+Seek.swift:72-119@c1745e8`). */
    private fun bootEngine(session: SeekPendingSession, walkId: Long) {
        // Consume the pending session — the boot key exists exactly
        // between chain lock and walk start, which is what makes the
        // restore filter structural rather than heuristic.
        sessionStore.clear()
        sessionWalkId = walkId
        sessionIntention = session.intention
        tintHex = session.tint?.fogHex
        previousActiveBucket = null
        latestFix = null
        publishedGlance = null
        hasPublishedGlance = false

        val engine = SeekEngine(
            chain = session.chain,
            scope = scope,
            clock = clock,
            locations = engineLocations(),
            walkStates = walkState,
            powerTiers = powerTiers,
        )

        senses.soundPlayer.prepare()

        // Collectors launched BEFORE start() so nothing is missed; the
        // single-threaded scope is the analogue of iOS's main-confined
        // synchronous sinks ("keeping the arrival commit synchronous
        // with the engine's state transition").
        eventsJob = scope.launch {
            engine.events.collect { event ->
                try {
                    handleSeekEvent(event)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Log.w(TAG, "seek event routing failed for $event", t)
                }
            }
        }
        fogJob = scope.launch {
            combine(
                engine.chain,
                engine.activeIndex,
                engine.phase,
                engine.distanceToActiveMeters,
                ::FogInputs,
            ).collect { inputs ->
                try {
                    updateFog(inputs)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Log.w(TAG, "seek fog update failed", t)
                }
            }
        }

        engine.start()
        this.engine = engine
        Log.i(TAG, "seek engine booted for walk=$walkId (${session.chain.clearings.size} clearings)")
    }

    /**
     * The engine's location feed: unfiltered fixes (spec D2 — the walk
     * pipeline's 20 m gate would starve the engine's 50 m
     * arrival/stillness gates), with the latest fix cached for fog
     * walker-position, arrival waypoints, and reroll seeds.
     * SecurityException is unreachable behind U8's accuracy gate but
     * caught defensively — a dead feed must not kill the collector's
     * siblings.
     */
    private fun engineLocations(): Flow<LocationPoint> =
        locationSource.rawLocationFlow()
            .onEach { latestFix = it }
            .catch { t ->
                if (t is SecurityException) {
                    Log.w(TAG, "seek location feed lost fine-location permission", t)
                } else {
                    throw t
                }
            }

    /** iOS `teardownSeek` (`ActiveWalkViewModel+Seek.swift:121-126@c1745e8`). */
    private fun teardownSession(clearPending: Boolean = true) {
        whisperGeneration += 1
        eventsJob?.cancel()
        eventsJob = null
        fogJob?.cancel()
        fogJob = null
        engine?.stop()
        engine = null
        senses.soundPlayer.stop()
        sessionWalkId = null
        sessionIntention = null
        tintHex = null
        previousActiveBucket = null
        latestFix = null
        _fogState.value = null
        _pulse.value = SeekPulseVisual.NONE
        _enginePhase.value = null
        // No teardown clear intent (U10 spec D4): walk end destroys the
        // per-walk service — and its stored glance — with the
        // notification; a post-stop intent would only resurrect the
        // service to stopSelf(). Local publish state resets so the next
        // session starts from a clean latch.
        publishedGlance = null
        hasPublishedGlance = false
        // Belt-and-suspenders with WalkLifecycleObserver's terminal
        // clear — idempotent either way. Skipped only on the stale-
        // session teardown, where the store already holds the NEXT
        // walk's session.
        if (clearPending) sessionStore.clear()
        Log.i(TAG, "seek session torn down")
    }

    // ─── Event routing ────────────────────────────────────────────────

    /**
     * The single seam between engine events and the senses. Internal so
     * tests can drive events directly (iOS `handleSeekEvent`,
     * `ActiveWalkViewModel+Seek.swift:132-157@c1745e8`).
     */
    internal suspend fun handleSeekEvent(event: SeekEngineEvent) {
        when (event) {
            is SeekEngineEvent.Pulse -> {
                val closeness = SeekEngine.closeness(event.distanceMeters)
                _pulse.value = SeekPulseVisual(
                    token = _pulse.value.token + 1,
                    aligned = event.aligned,
                    closeness = closeness,
                )
                // One call carries ear AND skin: the tick/aligned haptic
                // rides the player's coupled path (U5 spec §3.3).
                senses.soundPlayer.playPing(
                    aligned = event.aligned,
                    closeness = closeness.toFloat(),
                )
            }
            is SeekEngineEvent.Arrived -> {
                // The persistence commit happens before any ritual
                // effect so an interruption mid-ritual can never lose
                // the arrival (iOS comment ported).
                recordSeekArrival()
                senses.arrivalHaptic()
            }
            is SeekEngineEvent.StillnessBegan -> senses.breathInHaptic()
            is SeekEngineEvent.RevealedNext -> {
                senses.soundPlayer.playBowl()
                scheduleRevealWhisper()
            }
            SeekEngineEvent.SeekComplete ->
                // Bowl + generation-guarded release of the audio
                // consumer once it has rung (the engine already stopped
                // itself; no further ping can ever come). The final fog
                // emission — all halos — stays on the map until walk
                // end, and [enginePhase] stays COMPLETE for the options
                // sheet's disabled row.
                senses.soundPlayer.playCompletionBowl()
        }
    }

    /**
     * iOS `recordSeekArrival` (`ActiveWalkViewModel+Seek.swift:179-190
     * @c1745e8`): one SEEK_ARRIVAL event + one marked waypoint, ordinal
     * counted from arrivals already persisted this walk rather than the
     * engine's clearing index — after "Seek anew" from inside an
     * unrevealed clearing, the replacement clearing replays the same
     * index, which would duplicate labels and inflate the unknowns-found
     * count. Direct awaited Room writes (spec D6): the UI→tracker
     * intent path is fire-and-forget and could not uphold
     * persist-before-ritual.
     */
    private suspend fun recordSeekArrival() {
        val walkId = sessionWalkId ?: return
        repository.recordEvent(
            WalkEvent(
                walkId = walkId,
                timestamp = clock.now(),
                eventType = WalkEventType.SEEK_ARRIVAL,
            ),
        )
        val fix = latestFix ?: accumulatorLocation()
        if (fix == null) {
            // Unreachable (arrival needs three good fixes) but must not
            // crash a walk: keep the event, skip the waypoint.
            Log.w(TAG, "seek arrival with no fix — waypoint skipped")
            return
        }
        val ordinal = SeekPersistence.arrivalOrdinal(
            repository.waypointsFor(walkId).map { it.icon },
        )
        repository.addWaypoint(
            Waypoint(
                walkId = walkId,
                timestamp = clock.now(),
                latitude = fix.latitude,
                longitude = fix.longitude,
                label = SeekPersistence.arrivalWaypointLabel(context.resources, ordinal),
                icon = SeekPersistence.ARRIVAL_WAYPOINT_ICON,
            ),
        )
    }

    /**
     * One whisper from the walker's already-downloaded catalog, played
     * after the bowl has had room to ring. No whisper available → the
     * ritual proceeds without it. Generation-guarded so teardown or a
     * superseding reveal cancels the pending play (iOS
     * `scheduleSeekRevealWhisper`,
     * `ActiveWalkViewModel+Seek.swift:244-253@c1745e8`).
     */
    private fun scheduleRevealWhisper() {
        if (!soundsPreferences.soundsEnabled.value) return
        whisperGeneration += 1
        val generation = whisperGeneration
        scope.launch {
            delay(senses.revealWhisperDelayMillis)
            if (generation != whisperGeneration) return@launch
            try {
                val whisper = senses.pickRevealWhisper() ?: return@launch
                senses.playWhisper(whisper)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "reveal whisper failed", t)
            }
        }
    }

    // ─── Fog feed ─────────────────────────────────────────────────────

    private data class FogInputs(
        val chain: SeekChain,
        val activeIndex: Int,
        val phase: SeekEnginePhase,
        val distanceMeters: Double?,
    )

    /**
     * iOS `updateSeekFog` (`ActiveWalkViewModel+Seek.swift:269-290
     * @c1745e8`): the active bucket feeds back as the next call's
     * hysteresis reference; the tint is fixed per walk from the pending
     * session. Runs on the seek scope's Default-backed thread (U6/U7
     * dispatcher note) — the renderer applies it on main via the
     * StateFlow → composition → `PilgrimMap` path.
     */
    private fun updateFog(inputs: FogInputs) {
        val state = SeekFogModel.fogState(
            chain = inputs.chain,
            activeIndex = inputs.activeIndex,
            phase = inputs.phase,
            distanceToActiveMeters = inputs.distanceMeters,
            previousActiveBucket = previousActiveBucket,
            tintHex = tintHex,
            walkerPosition = latestFix?.let { SeekPoint(it.latitude, it.longitude) },
        )
        previousActiveBucket = state.activeFogBucket
        if (_fogState.value != state) {
            _fogState.value = state
        }
        _enginePhase.value = inputs.phase
        publishGlance(deriveGlance(inputs))
    }

    /**
     * iOS `currentSeekGlance()` (`ActiveWalkViewModel+Seek.swift:219-236
     * @c1745e8`), fed from the same combine emission as the fog — the
     * engine's distance re-publishes on every processed fix, so the
     * effective cadence matches iOS's 1 Hz Live Activity loop without a
     * second clock (U10 spec B2/D2).
     */
    private fun deriveGlance(inputs: FogInputs): SeekGlanceState? {
        val fix = latestFix
        val clearing = inputs.chain.clearings.getOrNull(inputs.activeIndex)
        val bearing = if (fix != null && clearing != null) {
            SeekChainGenerator.bearingDegrees(
                from = SeekPoint(fix.latitude, fix.longitude),
                to = clearing.center,
            )
        } else {
            null
        }
        return SeekGlanceModel.glance(
            distanceToActiveMeters = inputs.distanceMeters,
            courseDegrees = fix?.bearingDegrees?.toDouble(),
            speedMetersPerSecond = fix?.speedMetersPerSecond?.toDouble(),
            bearingToClearingDegrees = bearing,
            phase = inputs.phase,
        )
    }

    /**
     * UI-side pre-throttle (U10 spec B3): fire the cross-process intent
     * only when the glance VALUE changes (≙ iOS `seek != lastSeekGlance`
     * — here the cheap half; the tracker's fingerprint re-checks). The
     * latch suppresses the leading nulls before the first fix so a
     * session that never derived a glance never touches the channel.
     */
    private fun publishGlance(glance: SeekGlanceState?) {
        if (!hasPublishedGlance && glance == null) return
        if (hasPublishedGlance && glance == publishedGlance) return
        hasPublishedGlance = true
        publishedGlance = glance
        glancePublisher.publish(glance)
    }

    // ─── Reroll helpers ───────────────────────────────────────────────

    /**
     * Pre-departure "Seek anew" (spec D8): the engine is not booted yet
     * (Android boots at walk start, not at the gateway like iOS), so
     * regenerate the pending session's whole chain from the last known
     * position under the full budget. Silent by design — no engine, no
     * sonar channel; the sheet dismissal is the feedback.
     */
    private suspend fun rerollPendingSession() {
        val pending = sessionStore.pending.value ?: return
        val fix = try {
            locationSource.lastKnownLocation()
        } catch (se: SecurityException) {
            null
        } ?: return
        val seed = SeekSeed.make(
            intention = pending.intention,
            momentEpochMillis = clock.now(),
            fix = fix,
        )
        val chain = pending.chain.regeneratingRemainder(
            fromActiveIndex = 0,
            current = SeekPoint(fix.latitude, fix.longitude),
            remainingBudgetMeters = max(
                pending.chain.budgetMeters,
                SeekTuning.REROLL_MIN_BUDGET_METERS,
            ),
            rng = SeekSeededGenerator(seed),
        )
        sessionStore.set(pending.copy(chain = chain))
    }

    /** ≙ iOS's `routeCoordinates.last` fallback for the reroll position. */
    private fun accumulatorLocation(): LocationPoint? = when (val state = walkState.value) {
        is WalkState.Active -> state.walk.lastLocation
        is WalkState.Paused -> state.walk.lastLocation
        is WalkState.Meditating -> state.walk.lastLocation
        else -> null
    }

    private companion object {
        const val TAG = "SeekOrchestrator"
    }
}
