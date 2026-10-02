// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk.seek

import android.content.Context
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.audio.seek.SeekSoundSettings
import org.walktalkmeditate.pilgrim.core.flags.ReleaseFlags
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.seek.SeekDisplayState
import org.walktalkmeditate.pilgrim.data.seek.SeekSessionEntity
import org.walktalkmeditate.pilgrim.data.seek.displayState
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.domain.seek.SeekChain
import org.walktalkmeditate.pilgrim.domain.seek.SeekChainCodec
import org.walktalkmeditate.pilgrim.domain.seek.SeekChainGenerator
import org.walktalkmeditate.pilgrim.domain.seek.SeekEngine
import org.walktalkmeditate.pilgrim.domain.seek.SeekEngineEvent
import org.walktalkmeditate.pilgrim.domain.seek.SeekEnginePhase
import org.walktalkmeditate.pilgrim.domain.seek.SeekFogModel
import org.walktalkmeditate.pilgrim.domain.seek.SeekGlanceModel
import org.walktalkmeditate.pilgrim.domain.seek.SeekGlanceState
import org.walktalkmeditate.pilgrim.domain.seek.SeekPersistence
import org.walktalkmeditate.pilgrim.domain.seek.SeekPoint
import org.walktalkmeditate.pilgrim.domain.seek.SeekPowerTier
import org.walktalkmeditate.pilgrim.domain.seek.SeekSeed
import org.walktalkmeditate.pilgrim.location.LocationSource

/** Qualifier for the senses `:tracker`'s seek session drives (plan U25): its own sonar player, ping gate, and whisper route. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TrackerSeekSenses

/** The seek session's writes that must not interleave with a finish; the tracker's controller runs them under its dispatch mutex. */
interface SeekSessionWriter {

    /**
     * On the walk still in progress, flips the clearing at [clearingIndex]
     * to arrived once, then writes SEEK_ARRIVAL and the reserved waypoint
     * labelled by [label], at [at] or else the walk's last fix.
     *
     * @return true only when this call flipped it.
     */
    suspend fun recordSeekArrival(
        walkId: Long,
        clearingIndex: Int,
        at: LocationPoint?,
        label: (ordinal: Int) -> String,
    ): Boolean

    /**
     * A seek session handed over after its walk started (the chain locked
     * after Start), written for the seek walk in progress if it has none.
     *
     * @return that walk's id, or null when no walk took it.
     */
    suspend fun attachSeekSession(start: SeekStart): Long?
}

/** What [SeekTrackerSession.start] did with a walk. */
sealed interface SeekSessionStart {

    /** The release flag is off: Seek runs in the UI process, as it always has. */
    data object Disabled : SeekSessionStart

    /** The walk has no seek session row: not a seek walk handed to `:tracker`. */
    data object NotSeek : SeekSessionStart

    /** The walk carries a session the tracker can't run; nothing started. [reason] names no place. */
    data class Refused(val reason: String) : SeekSessionStart

    /** Running: from Begin's hand-off, or [revived] from Room after the process that ran it died. */
    data class Started(val revived: Boolean) : SeekSessionStart
}

/**
 * `:tracker`'s sonar settings: the session row's, then the UI's later
 * changes by intent, never the UI process's preferences, whose reads freeze
 * here. Silent until a session applies its row.
 */
@Singleton
class TrackerSeekSoundSettings @Inject constructor() : SeekSoundSettings {

    private val _sonarEnabled = MutableStateFlow(false)
    private val _sonarVolume = MutableStateFlow(0f)
    private val _soundsEnabled = MutableStateFlow(false)

    override val sonarEnabled: StateFlow<Boolean> = _sonarEnabled.asStateFlow()
    override val sonarVolume: StateFlow<Float> = _sonarVolume.asStateFlow()
    override val soundsEnabled: StateFlow<Boolean> = _soundsEnabled.asStateFlow()

    fun apply(settings: SeekSonarSettings) {
        _sonarVolume.value = settings.sonarVolume
        _sonarEnabled.value = settings.sonarEnabled
        _soundsEnabled.value = settings.soundsEnabled
    }
}

/**
 * A seek walk's session inside the `:tracker` foreground service with the
 * release flag on (plan U25), on Honor's lifecycle
 * ([org.walktalkmeditate.pilgrim.walk.honor.HonorSession]): the service
 * starts it in the location job once the walk is settled, it runs as a
 * child of the service's scope on its own single thread (the engine's
 * confinement contract), and it ends when its walk leaves progress or
 * [stop] tears it down. The engine, the sonar and haptics, the reveal
 * whisper, the glance, and the arrival writes all live here, so a pocketed
 * walk keeps its guidance when the UI process is reclaimed.
 *
 * - **Restarted from Room.** Begin's hand-off lands in the walk's seek
 *   session row, and every start (Begin's, a redelivered START's, the
 *   watchdog's) rebuilds the engine from that row alone: the chain as it
 *   stands, the clearing it was on, an arrival in progress, and when its
 *   next pulse was due. Nothing is re-seeded.
 * - **Its own location feed.** The engine reads an ungated FLP
 *   subscription, as it did in the UI process (U9 port spec D2). iOS feeds
 *   Seek the same filtered stream as Honor (own-walk spec B §1.1): a dated
 *   divergence the parity gate weighs.
 * - **Persist before ritual.** Each pulse, arrival, reveal, and completion
 *   commits the row the UI draws from, checking the walk is unfinished,
 *   before any sound or haptic; arrival is a compare-and-set that records
 *   a clearing once. A refused commit ends the session.
 * - **Display through Room.** The row carries fog, pulse, and phase; it is
 *   written at each pulse and each change the map shows, so the crescent
 *   and pulse the UI draws may lag the ping.
 */
@Singleton
class SeekTrackerSession internal constructor(
    private val database: PilgrimDatabase,
    private val locationSource: LocationSource,
    private val powerTiers: Flow<SeekPowerTier>,
    private val writer: SeekSessionWriter,
    private val senses: SeekSenses,
    private val soundSettings: TrackerSeekSoundSettings,
    private val releaseFlags: ReleaseFlags,
    private val clock: Clock,
    private val arrivalLabel: (ordinal: Int) -> String,
    // One thread: the engine's confinement contract. Not the service's
    // Main.immediate scope, which onDestroy's runBlocking { stop() } would deadlock.
    private val sessionDispatcher: CoroutineDispatcher,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Inject
    constructor(
        database: PilgrimDatabase,
        locationSource: LocationSource,
        @SeekPowerTiers powerTiers: Flow<@JvmSuppressWildcards SeekPowerTier>,
        writer: SeekSessionWriter,
        @TrackerSeekSenses senses: SeekSenses,
        soundSettings: TrackerSeekSoundSettings,
        releaseFlags: ReleaseFlags,
        clock: Clock,
        @ApplicationContext context: Context,
    ) : this(
        database = database,
        locationSource = locationSource,
        powerTiers = powerTiers,
        writer = writer,
        senses = senses,
        soundSettings = soundSettings,
        releaseFlags = releaseFlags,
        clock = clock,
        arrivalLabel = { ordinal -> SeekPersistence.arrivalWaypointLabel(context.resources, ordinal) },
        sessionDispatcher = Dispatchers.Default.limitedParallelism(1),
    )

    private val _glance = MutableStateFlow<SeekGlanceState?>(null)

    /** The notification's seek line for the walk in session; null with none. */
    val glance: StateFlow<SeekGlanceState?> = _glance.asStateFlow()

    @Volatile
    private var run: Run? = null

    /** The walk in session, if any. */
    val walkId: Long? get() = run?.takeUnless { it.ended }?.walkId

    /**
     * Starts the session of [walkId] from its seek session row, replacing
     * any session before; a session that ran before for it is revived.
     * Call it once the controller holds the walk, from the service's
     * location job; [walkState] is the controller's state.
     */
    suspend fun start(scope: CoroutineScope, walkId: Long, walkState: StateFlow<WalkState>): SeekSessionStart {
        stop()
        if (!releaseFlags.honor) return SeekSessionStart.Disabled
        val prepared = try {
            withContext(sessionDispatcher) { prepare(walkId) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: SQLiteException) {
            refuse(walkId, "its row could not be read")
        }
        val ready = when (prepared) {
            is Prepared.Ready -> prepared
            is Prepared.Done -> return prepared.result
        }
        soundSettings.apply(ready.row.sonarSettings())
        val next = Run(ready, walkState, SupervisorJob(scope.coroutineContext[Job]))
        run = next
        next.launch()
        Log.i(TAG, "session for walk $walkId ${if (ready.revived) "revived" else "started"}")
        return SeekSessionStart.Started(revived = ready.revived)
    }

    /**
     * A hand-off that came after its walk started: writes the row for the
     * seek walk in progress, if it has none, then starts its session.
     */
    suspend fun attach(scope: CoroutineScope, start: SeekStart, walkState: StateFlow<WalkState>): SeekSessionStart {
        if (!releaseFlags.honor) return SeekSessionStart.Disabled
        val walkId = writer.attachSeekSession(start) ?: return SeekSessionStart.NotSeek
        return start(scope, walkId, walkState)
    }

    /** "Seek anew", applied once by [seq]: a number at or below the last applied changes nothing. */
    fun seekAnew(seq: Long) {
        run?.inputs?.trySend(Input.SeekAnew(seq))
    }

    /** The UI's sonar settings, applied once by [seq] and kept in the row for a revival. */
    fun applyPreferences(seq: Long, settings: SeekSonarSettings) {
        run?.inputs?.trySend(Input.Preferences(seq, settings))
    }

    /** The walk's end, a discard, or the service's teardown. */
    suspend fun stop() {
        val current = run ?: return
        run = null
        current.job.cancelAndJoin()
        current.teardown()
        _glance.value = null
    }

    /**
     * Returns once every input sent before it has been handled, or once the
     * session has ended.
     */
    @VisibleForTesting
    internal suspend fun awaitIdle() {
        val current = run ?: return
        val done = CompletableDeferred<Unit>()
        if (current.inputs.trySend(Input.Barrier(done)).isFailure) return
        val handle = current.job.invokeOnCompletion { done.complete(Unit) }
        try {
            done.await()
        } finally {
            handle.dispose()
        }
    }

    private suspend fun prepare(walkId: Long): Prepared {
        val dao = database.seekDao()
        val row = dao.getSession(walkId) ?: return Prepared.Done(SeekSessionStart.NotSeek)
        val walk = database.walkDao().getById(walkId)
        if (walk == null || walk.endTimestamp != null) return refuse(walkId, "the walk is not in progress")
        val chain = SeekChainCodec.decode(row.chain) ?: return refuse(walkId, "its chain does not decode")
        val live = database.withTransaction {
            if (dao.countLiveSessionOnUnfinishedWalk(walkId) == 0) return@withTransaction false
            dao.bumpGateGeneration(walkId)
            true
        }
        if (!live) return refuse(walkId, "the walk finished as its session started")
        return Prepared.Ready(row, chain, revived = row.gateGeneration > 0)
    }

    private fun refuse(walkId: Long, reason: String): Prepared.Done {
        Log.e(TAG, "refusing the seek session of walk $walkId: $reason")
        return Prepared.Done(SeekSessionStart.Refused(reason))
    }

    private sealed interface Prepared {
        class Done(val result: SeekSessionStart) : Prepared
        class Ready(val row: SeekSessionEntity, val chain: SeekChain, val revived: Boolean) : Prepared
    }

    private sealed interface Input {
        data object Sync : Input
        data object Display : Input
        class Event(val event: SeekEngineEvent) : Input
        class SeekAnew(val seq: Long) : Input
        class Preferences(val seq: Long, val settings: SeekSonarSettings) : Input
        class Barrier(val done: CompletableDeferred<Unit>) : Input
    }

    /** What the map shows; the row is written when it changes. */
    private data class DisplayKey(
        val chain: SeekChain,
        val activeIndex: Int,
        val phase: SeekEnginePhase,
        val fogBucket: Int?,
    )

    private inner class Run(
        prepared: Prepared.Ready,
        private val walkState: StateFlow<WalkState>,
        val job: Job,
    ) {
        val walkId = prepared.row.walkId
        val inputs = Channel<Input>(Channel.UNLIMITED)
        private val scope = CoroutineScope(job + sessionDispatcher)
        private val intention = prepared.row.intention
        private val tintHex = prepared.row.tintHex

        // Confined to the session's thread, as the engine is.
        private var latestFix: LocationPoint? = null
        private var previousActiveBucket: Int? = prepared.row.fogBucket
        private var committed: SeekDisplayState = prepared.row.displayState()
        private var committedPhase: SeekEnginePhase = prepared.row.phase
        private var committedKey: DisplayKey? = null
        private var encodedChain: Pair<SeekChain, String> = prepared.chain to prepared.row.chain
        private var whisperGeneration = 0L

        private val engine = SeekEngine(
            chain = prepared.chain,
            scope = scope,
            clock = clock,
            locations = engineLocations(),
            walkStates = walkState,
            powerTiers = powerTiers,
            initialActiveIndex = prepared.row.activeIndex,
            initialPhase = prepared.row.phase,
            arrivedAtMillis = prepared.row.arrivedAt,
            firstPulseDueAtMillis = prepared.row.nextPulseDueAt,
        )

        @Volatile
        var ended = false
            private set

        /** Collectors before the engine starts, so no event is missed (FIFO on the one thread). */
        fun launch() {
            scope.launch { consume() }
            scope.launch { engine.events.collect { inputs.trySend(Input.Event(it)) } }
            scope.launch {
                combine(engine.chain, engine.activeIndex, engine.phase, engine.distanceToActiveMeters) { _, _, _, _ -> }
                    .collect { inputs.trySend(Input.Display) }
            }
            scope.launch { walkState.collect { inputs.trySend(Input.Sync) } }
            scope.launch {
                if (engine.phase.value != SeekEnginePhase.COMPLETE) senses.soundPlayer.prepare()
                engine.start()
            }
        }

        /** iOS `teardownSeek` (`ActiveWalkViewModel+Seek.swift:121-126@c1745e8`). */
        fun teardown() {
            whisperGeneration += 1
            engine.stop()
            senses.soundPlayer.stop()
        }

        /**
         * The unfiltered feed (U9 spec D2), each fix cached for the fog's
         * walker, the arrival waypoint, the glance, and a reroll's seed.
         */
        private fun engineLocations(): Flow<LocationPoint> =
            locationSource.rawLocationFlow()
                .onEach { latestFix = it }
                .catch { t ->
                    if (t is SecurityException) {
                        Log.w(TAG, "walk $walkId: the seek feed lost fine location")
                    } else {
                        throw t
                    }
                }

        private suspend fun consume() {
            for (input in inputs) {
                if (!followWalk()) {
                    (input as? Input.Barrier)?.done?.complete(Unit)
                    break
                }
                try {
                    handle(input)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (e: Exception) {
                    Log.w(TAG, "walk $walkId: ${input::class.simpleName} failed: ${e::class.simpleName}")
                }
                if (ended) break
            }
        }

        private suspend fun handle(input: Input) {
            when (input) {
                Input.Sync -> Unit
                Input.Display -> if (showFog() != committedKey) commit()
                is Input.Event -> onEvent(input.event)
                is Input.SeekAnew -> seekAnew(input.seq)
                is Input.Preferences -> applyPreferences(input.seq, input.settings)
                is Input.Barrier -> input.done.complete(Unit)
            }
        }

        /** False, ending the session, once its walk is no longer the one in progress. */
        private fun followWalk(): Boolean {
            if (walkState.value.inProgressWalk()?.walkId != walkId) {
                end()
                return false
            }
            return true
        }

        private fun end() {
            if (ended) return
            ended = true
            teardown()
            _glance.value = null
            inputs.close()
            while (true) {
                val left = inputs.tryReceive().getOrNull() ?: break
                (left as? Input.Barrier)?.done?.complete(Unit)
            }
            job.cancel()
        }

        /** iOS `handleSeekEvent` (`ActiveWalkViewModel+Seek.swift:132-157@c1745e8`), each ritual after its row. */
        private suspend fun onEvent(event: SeekEngineEvent) {
            when (event) {
                is SeekEngineEvent.Pulse -> if (commit(pulse = event)) {
                    // One call carries ear and skin: the tick or aligned haptic rides the player.
                    senses.soundPlayer.playPing(
                        aligned = event.aligned,
                        closeness = SeekEngine.closeness(event.distanceMeters).toFloat(),
                    )
                }
                is SeekEngineEvent.Arrived -> {
                    if (!commit()) return
                    val won = writer.recordSeekArrival(walkId, event.clearingIndex, latestFix, arrivalLabel)
                    if (won) {
                        committedPhase = SeekEnginePhase.ARRIVED
                        senses.arrivalHaptic()
                    }
                }
                is SeekEngineEvent.StillnessBegan -> senses.breathInHaptic()
                is SeekEngineEvent.RevealedNext -> if (commit()) {
                    senses.soundPlayer.playBowl()
                    scheduleRevealWhisper()
                }
                SeekEngineEvent.SeekComplete -> if (commit()) senses.soundPlayer.playCompletionBowl()
            }
        }

        /** iOS `seekAnewRequested` (`ActiveWalkViewModel+Seek.swift:196-210@c1745e8`): the same intention, a new moment. */
        private suspend fun seekAnew(seq: Long) {
            if (database.seekDao().applyCommandSeq(walkId, seq) != 1) return
            val fix = latestFix
            val point = fix?.let { SeekPoint(it.latitude, it.longitude) }
                ?: walkState.value.inProgressWalk()?.lastLocation?.let { SeekPoint(it.latitude, it.longitude) }
                ?: return
            engine.seekAnew(
                currentLocation = point,
                seed = SeekSeed.make(intention = intention, momentEpochMillis = clock.now(), fix = fix),
            )
            commit()
        }

        private suspend fun applyPreferences(seq: Long, settings: SeekSonarSettings) {
            val applied = database.seekDao().applyPreferences(
                walkId = walkId,
                seq = seq,
                sonarEnabled = settings.sonarEnabled,
                sonarVolume = settings.sonarVolume,
                soundsEnabled = settings.soundsEnabled,
            )
            if (applied == 1) soundSettings.apply(settings)
        }

        /**
         * iOS `updateSeekFog` and `currentSeekGlance`
         * (`ActiveWalkViewModel+Seek.swift:219-290@c1745e8`): the fog's
         * bucket with its hysteresis and the glance follow every fix.
         */
        private fun showFog(): DisplayKey {
            val chain = engine.chain.value
            val activeIndex = engine.activeIndex.value
            val phase = engine.phase.value
            val distance = engine.distanceToActiveMeters.value
            val fog = SeekFogModel.fogState(
                chain = chain,
                activeIndex = activeIndex,
                phase = phase,
                distanceToActiveMeters = distance,
                previousActiveBucket = previousActiveBucket,
                tintHex = tintHex,
            )
            previousActiveBucket = fog.activeFogBucket
            if (!ended) _glance.value = glance(chain, activeIndex, phase, distance)
            return DisplayKey(chain, activeIndex, phase, fog.activeFogBucket)
        }

        private fun glance(chain: SeekChain, activeIndex: Int, phase: SeekEnginePhase, distance: Double?): SeekGlanceState? {
            val fix = latestFix
            val clearing = chain.clearings.getOrNull(activeIndex)
            val bearing = if (fix != null && clearing != null) {
                SeekChainGenerator.bearingDegrees(from = SeekPoint(fix.latitude, fix.longitude), to = clearing.center)
            } else {
                null
            }
            return SeekGlanceModel.glance(
                distanceToActiveMeters = distance,
                courseDegrees = fix?.bearingDegrees?.toDouble(),
                speedMetersPerSecond = fix?.speedMetersPerSecond?.toDouble(),
                bearingToClearingDegrees = bearing,
                phase = phase,
            )
        }

        /**
         * The row as the engine stands, written with the walk unfinished; a
         * [pulse] advances its token. ARRIVED is never written here, only by
         * arrival's compare-and-set, so a display write landing first can't
         * win the arrival from it.
         *
         * @return false once the walk has finished, which ends the session.
         */
        private suspend fun commit(pulse: SeekEngineEvent.Pulse? = null): Boolean {
            val key = showFog()
            val fix = latestFix
            val display = SeekDisplayState(
                walkId = walkId,
                chain = encoded(key.chain),
                activeIndex = key.activeIndex,
                distanceToActiveMeters = engine.distanceToActiveMeters.value,
                fogBucket = key.fogBucket,
                walkerLatitude = fix?.latitude ?: committed.walkerLatitude,
                walkerLongitude = fix?.longitude ?: committed.walkerLongitude,
                pulseToken = committed.pulseToken + if (pulse != null) 1 else 0,
                pulseAligned = pulse?.aligned ?: committed.pulseAligned,
                pulseCloseness = pulse?.let { SeekEngine.closeness(it.distanceMeters) } ?: committed.pulseCloseness,
                nextPulseDueAt = engine.nextPulseDueAtMillis,
            )
            val phase = key.phase.takeIf { it != SeekEnginePhase.ARRIVED && it != committedPhase }
            if (display != committed || phase != null) {
                val written = try {
                    database.withTransaction {
                        val dao = database.seekDao()
                        if (dao.countLiveSessionOnUnfinishedWalk(walkId) == 0) return@withTransaction false
                        dao.updateDisplay(display)
                        phase?.let { dao.setPhase(walkId, it) }
                        true
                    }
                } catch (e: SQLiteException) {
                    Log.w(TAG, "walk $walkId: seek row refused (${e::class.simpleName}); the session ends")
                    false
                }
                if (!written) {
                    end()
                    return false
                }
                committed = display
                phase?.let { committedPhase = it }
            }
            committedKey = key
            return true
        }

        private fun encoded(chain: SeekChain): String {
            if (encodedChain.first !== chain) encodedChain = chain to SeekChainCodec.encode(chain)
            return encodedChain.second
        }

        /**
         * One downloaded whisper after the bowl has rung, superseded by a
         * later reveal or the teardown (iOS `scheduleSeekRevealWhisper`,
         * `ActiveWalkViewModel+Seek.swift:244-253@c1745e8`).
         */
        private fun scheduleRevealWhisper() {
            if (!soundSettings.soundsEnabled.value) return
            whisperGeneration += 1
            val generation = whisperGeneration
            scope.launch {
                delay(senses.revealWhisperDelayMillis)
                if (generation != whisperGeneration) return@launch
                try {
                    val whisper = senses.pickRevealWhisper() ?: return@launch
                    senses.playWhisper(whisper)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (e: Exception) {
                    Log.w(TAG, "walk $walkId: reveal whisper failed: ${e::class.simpleName}")
                }
            }
        }
    }

    private companion object {
        const val TAG = "SeekTrackerSession"

        fun SeekSessionEntity.sonarSettings() = SeekSonarSettings(
            sonarEnabled = sonarEnabled,
            sonarVolume = sonarVolume,
            soundsEnabled = soundsEnabled,
        )

        fun WalkState.inProgressWalk(): WalkAccumulator? = when (this) {
            is WalkState.Active -> walk
            is WalkState.Paused -> walk
            is WalkState.Meditating -> walk
            WalkState.Idle, is WalkState.Finished -> null
        }
    }
}
