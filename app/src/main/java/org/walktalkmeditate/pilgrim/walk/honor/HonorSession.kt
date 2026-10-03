// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk.honor

import android.content.Context
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.core.flags.ReleaseFlags
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.honor.HonorEngineState
import org.walktalkmeditate.pilgrim.data.honor.HonorMomentStateEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorNoticeEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorNoticeKind
import org.walktalkmeditate.pilgrim.data.honor.HonorVoiceEnd
import org.walktalkmeditate.pilgrim.data.honor.WayArrival
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.data.honor.isStagedPerWalk
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.domain.honor.HonorEngine
import org.walktalkmeditate.pilgrim.domain.honor.HonorEngineEvent
import org.walktalkmeditate.pilgrim.domain.honor.HonorMomentTracker
import org.walktalkmeditate.pilgrim.domain.honor.HonorPersistence
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayMoment
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind

/** Arrival's compare-and-set, which the tracker's controller runs under its dispatch mutex. */
interface HonorArrivalRecorder {

    /**
     * On a walk still in progress, flips the session's phase once, keeping
     * [arrival] and the [walkedMeters] the engine had credited, then writes
     * HONOR_ARRIVAL and the reserved waypoint labelled [waypointLabel], at
     * [at] or else the walk's last fix.
     *
     * @return true only when this call flipped the phase.
     */
    suspend fun recordHonorArrival(
        walkId: Long,
        arrival: WayArrival,
        walkedMeters: Double,
        waypointLabel: String,
        at: LocationPoint?,
    ): Boolean
}

/**
 * The Honor session of one walk, inside the `:tracker` foreground service
 * (plan U17), built like
 * [org.walktalkmeditate.pilgrim.service.BackgroundWhisperAutoPlayer]: the
 * service hands it the walk and its state, and it runs as a child of the
 * service's scope on its own dispatcher until the walk leaves progress or
 * [stop] tears it down. iOS runs all of this in `ActiveWalkViewModel+Honor`
 * on its main thread and never survives the process.
 *
 * - **Keyed to the walk id.** Every [start] replaces the session before
 *   it, so a cached process carries nothing from an earlier walk. The
 *   Way, the frozen preferences, and the engine's state come from Room
 *   and the Ways store alone; a revival restores the anchor, the queue,
 *   and a stage's water spoken and quiet clock, never replays the voice
 *   the dead process was playing or a notice it spoke, and bumps the gate
 *   generation so the UI re-sends its gates. An own walk's Way and a
 *   stage's are the copies staged under the walk at Begin, so a package
 *   changed meanwhile never redraws a stage under its walk (owner
 *   decision 2 of the pilgrimage-stage spec).
 * - **The engine fed in iOS's order.** One actor takes the fixes, the 1 Hz
 *   engine clock, the gates, the player's callbacks, and the walker's
 *   commands, in arrival order. Begin feeds the latest fix before the
 *   gates open, as iOS's `bind` delivers its replayed pre-Begin fix
 *   (`honor/golden/README.md`, "Found along the way").
 * - **Persist before ritual.** Each engine call's state and every row its
 *   events change commit in one transaction that first re-checks the walk
 *   is unfinished; only then do sounds, haptics, and arrival run, in the
 *   events' order. A refused commit ends the session, so no Honor row
 *   lands after finalize. The UI draws cards from those rows.
 */
@Singleton
class HonorSession internal constructor(
    private val database: PilgrimDatabase,
    private val wayStore: WayStore,
    private val arrivalRecorder: HonorArrivalRecorder,
    private val voice: WayVoicePort,
    private val duck: SoundscapeDuckPort,
    private val haptics: HonorHapticsPort,
    private val gatePort: HonorGatePort,
    private val media: HonorMediaFiles,
    private val releaseFlags: ReleaseFlags,
    private val clock: Clock,
    private val arrivalLabel: (wayTitle: String) -> String,
    // Not the service's Main.immediate scope: onDestroy's runBlocking { stop() }
    // on the main thread would otherwise deadlock on Main-dispatched children.
    private val sessionDispatcher: CoroutineDispatcher,
    /** The engine clock's cadence; zero leaves ticks to [tickNow]. */
    private val tickMillis: Long,
) {
    @Inject
    constructor(
        database: PilgrimDatabase,
        wayStore: WayStore,
        arrivalRecorder: HonorArrivalRecorder,
        voice: WayVoicePort,
        duck: SoundscapeDuckPort,
        haptics: HonorHapticsPort,
        gatePort: HonorGatePort,
        media: HonorMediaFiles,
        releaseFlags: ReleaseFlags,
        clock: Clock,
        @ApplicationContext context: Context,
    ) : this(
        database = database,
        wayStore = wayStore,
        arrivalRecorder = arrivalRecorder,
        voice = voice,
        duck = duck,
        haptics = haptics,
        gatePort = gatePort,
        media = media,
        releaseFlags = releaseFlags,
        clock = clock,
        arrivalLabel = { title -> HonorPersistence.arrivalWaypointLabel(context.resources, title) },
        sessionDispatcher = Dispatchers.IO,
        tickMillis = ENGINE_TICK_MILLIS,
    )

    private val _glance = MutableStateFlow<HonorGlanceState?>(null)

    /** The lock-screen glance of the walk in session; null with none. */
    val glance: StateFlow<HonorGlanceState?> = _glance.asStateFlow()

    @Volatile
    private var run: Run? = null

    /** The walk in session, if any. */
    val walkId: Long? get() = run?.takeUnless { it.ended }?.walkId

    /**
     * Starts the session of [walkId], or revives it from Room when a
     * session already ran for it, replacing any session before. Call it
     * once the controller holds the walk, from the service's location job;
     * [walkState] is the controller's state. [initialFix], the latest fix
     * known before Start, is fed to a fresh session only: a revival never
     * feeds a fix twice.
     */
    suspend fun start(
        scope: CoroutineScope,
        walkId: Long,
        walkState: StateFlow<WalkState>,
        initialFix: LocationPoint? = null,
    ): HonorSessionStart {
        stop()
        if (!releaseFlags.honor) return HonorSessionStart.Disabled
        val prepared = try {
            withContext(sessionDispatcher) { prepare(walkId) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: SQLiteException) {
            refuse(walkId, "its rows could not be read")
        }
        val ready = when (prepared) {
            is Prepared.Ready -> prepared
            is Prepared.Done -> return prepared.result
        }
        val next = Run(ready, walkState, SupervisorJob(scope.coroutineContext[Job]))
        run = next
        next.launch(initialFix.takeIf { !ready.revived })
        Log.i(TAG, "session for walk $walkId ${if (ready.revived) "revived" else "started"}")
        return HonorSessionStart.Started(revived = ready.revived)
    }

    /** A fix from the walk's accuracy-gated collector, fed before the reducer sees it. */
    fun onFix(point: LocationPoint) {
        run?.inputs?.trySend(Input.Fix(point))
    }

    /**
     * Applies [command] once. A [seq] at or below the last one applied is
     * dropped, so a replayed intent changes nothing; the UI numbers its
     * commands so they keep rising across its own restarts.
     */
    fun command(seq: Long, command: HonorCommand) {
        run?.inputs?.trySend(Input.Command(seq, command))
    }

    /** The walk's end, a discard, or the service's teardown: the voice stops at once, with no fade (spec C §12.3). */
    suspend fun stop() {
        val current = run ?: return
        run = null
        current.job.cancelAndJoin()
        current.teardownAudio()
        _glance.value = null
    }

    /** One engine clock tick now, for a session built with no cadence. */
    @VisibleForTesting
    internal fun tickNow() {
        run?.inputs?.trySend(Input.Tick)
    }

    /**
     * Returns once every input sent before it has been handled, the walk's
     * current state and gates included, or once the session has ended.
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
        val dao = database.honorDao()
        val session = dao.getSession(walkId) ?: return Prepared.Done(HonorSessionStart.NotHonor)
        val walk = database.walkDao().getById(walkId)
        if (walk == null || walk.endTimestamp != null) return refuse(walkId, "the walk is not in progress")
        if (!WayStore.isValidId(session.wayId)) return refuse(walkId, "its Way id fails the store's allow-list")
        val staged = if (session.sourceKind.isStagedPerWalk) wayStore.staged(walk.uuid) else null
        val way = staged?.takeIf { it.id == session.wayId } ?: wayStore.load(session.wayId)
            ?: return refuse(walkId, "no staged or listed Way loads")

        val revived = session.gateGeneration > 0
        val rows = dao.getMomentStates(walkId).associateByTo(mutableMapOf()) { it.momentId }
        val engine = HonorEngine(
            way = way,
            softTapEnabled = session.softTapEnabled,
            voicesEnabled = session.voicesEnabled,
            clock = clock,
        )
        if (revived) engine.restore(session.engineSnapshot(rows.values, dao.getNotices(walkId)))
        val now = clock.now()
        val interrupted = rows.values
            .filter { it.voiceStartedAt != null && it.voiceEnd == null }
            .map { it.copy(voiceEndedAt = now, voiceEnd = HonorVoiceEnd.INTERRUPTED) }
        val hold = VoiceHold(rate = session.voiceRate.toFloat())
        val live = database.withTransaction {
            if (dao.countLiveSessionOnUnfinishedWalk(walkId) == 0) return@withTransaction false
            dao.bumpGateGeneration(walkId)
            if (!revived) {
                way.stage?.let { dao.recordStageIdentity(walkId, it.routeId, it.index, it.name, it.distanceKm) }
            }
            interrupted.forEach { dao.upsertMomentState(it) }
            dao.updateVoiceState(hold.toVoiceState(walkId))
            true
        }
        if (!live) return refuse(walkId, "the walk finished as its session started")
        interrupted.forEach { rows[it.momentId] = it }
        return Prepared.Ready(walkId, way, engine, rows, hold, revived)
    }

    private fun refuse(walkId: Long, reason: String): Prepared.Done {
        Log.e(TAG, "refusing the Honor session of walk $walkId: $reason")
        return Prepared.Done(HonorSessionStart.Refused(reason))
    }

    private sealed interface Prepared {
        class Done(val result: HonorSessionStart) : Prepared
        class Ready(
            val walkId: Long,
            val way: Way,
            val engine: HonorEngine,
            val rows: MutableMap<String, HonorMomentStateEntity>,
            val hold: VoiceHold,
            val revived: Boolean,
        ) : Prepared
    }

    private sealed interface Input {
        class Begin(val fix: LocationPoint?) : Input
        data object Sync : Input
        data object Tick : Input
        class Fix(val point: LocationPoint) : Input
        class Command(val seq: Long, val command: HonorCommand) : Input
        class VoiceEnded(val token: Long, val end: HonorVoiceEnd) : Input
        class VoicePausedForRoute(val token: Long) : Input
        class Barrier(val done: CompletableDeferred<Unit>) : Input
    }

    /** A side effect, performed only after the rows that record its cause commit. */
    private sealed interface Ritual {
        class Play(val file: File, val gain: Float, val token: Long, val byEngine: Boolean) : Ritual
        class PlayReply(val file: File, val token: Long) : Ritual
        data object Pause : Ritual
        data object Resume : Ritual
        data object Stop : Ritual
        class Seek(val fraction: Double) : Ritual
        class Rate(val rate: Float) : Ritual
        data object Duck : Ritual
        data object Restore : Ritual
        data object MomentHaptic : Ritual
        data object SoftTapHaptic : Ritual
        data object WaterHaptic : Ritual
        class Arrive(val arrival: WayArrival, val walkedMeters: Double, val at: LocationPoint?) : Ritual
    }

    private inner class Run(
        prepared: Prepared.Ready,
        private val walkState: StateFlow<WalkState>,
        val job: Job,
    ) {
        val walkId = prepared.walkId
        val inputs = Channel<Input>(Channel.UNLIMITED)
        private val way = prepared.way
        private val engine = prepared.engine
        private val rows = prepared.rows
        private val revived = prepared.revived
        private val momentsById = way.moments.associateBy { it.id }

        private var hold = prepared.hold
        private var committedHold = hold
        private var committedEngineState: HonorEngineState = engine.snapshot().toEngineState(walkId)

        /** The play the player holds; a callback carrying any other token is stale. */
        private var playerToken: Long? = null
        private var nextToken = 0L
        private var ducked = false
        private var latestState: WalkState = walkState.value
        private var appliedGates: HonorMomentTracker.Gates? = null

        /** The arbiter's last word on the voice handed over: silent behind a prompt or a call, though not paused. */
        private var voiceHeld = false

        @Volatile
        var ended = false
            private set

        fun launch(initialFix: LocationPoint?) {
            val scope = CoroutineScope(job + sessionDispatcher)
            inputs.trySend(Input.Begin(initialFix))
            scope.launch { consume() }
            scope.launch { walkState.collect { inputs.trySend(Input.Sync) } }
            scope.launch { gatePort.gates.collect { inputs.trySend(Input.Sync) } }
            if (tickMillis > 0) {
                scope.launch {
                    while (true) {
                        delay(tickMillis)
                        inputs.trySend(Input.Tick)
                    }
                }
            }
        }

        fun teardownAudio() {
            if (playerToken != null) {
                playerToken = null
                voice.stop()
            }
            if (ducked) {
                ducked = false
                duck.restoreAfterWayVoice()
            }
            hold = hold.released()
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
                    // A port or file error costs this one input, not the walk's Way.
                    Log.w(TAG, "walk $walkId: ${input::class.simpleName} failed: ${e::class.simpleName}")
                }
                if (ended) break
            }
        }

        private suspend fun handle(input: Input) {
            when (input) {
                is Input.Begin -> begin(input.fix)
                Input.Sync -> applyGates()
                Input.Tick -> tick()
                is Input.Fix -> {
                    applyGates()
                    step(input.point) { engine.processLocation(input.point) }
                    publishGlance()
                }
                is Input.Command -> {
                    applyGates()
                    command(input.seq, input.command)
                }
                is Input.VoiceEnded -> {
                    applyGates()
                    voiceEnded(input.token, input.end)
                }
                is Input.VoicePausedForRoute -> {
                    applyGates()
                    voicePausedForRoute(input.token)
                }
                is Input.Barrier -> try {
                    applyGates()
                } finally {
                    input.done.complete(Unit)
                }
            }
        }

        /** False, ending the session, once its walk is no longer the one in progress. */
        private fun followWalk(): Boolean {
            val state = walkState.value
            if (state.inProgressWalk()?.walkId != walkId) {
                end()
                return false
            }
            latestState = state
            return true
        }

        /**
         * Always on the actor's own coroutine, so a [stop] that joins it
         * knows this teardown is done before any next session plays.
         */
        private fun end() {
            if (ended) return
            ended = true
            teardownAudio()
            _glance.value = null
            inputs.close()
            while (true) {
                val left = inputs.tryReceive().getOrNull() ?: break
                (left as? Input.Barrier)?.done?.complete(Unit)
            }
            job.cancel()
        }

        /**
         * iOS's first main-queue turn after `bind` (`ActiveWalkViewModel+Honor.swift:74-81@7c200bf`):
         * the replayed fix on the engine's default open gates, the clock, the
         * gates while the status still reads not-recording, then the gates
         * once it does. A revival goes straight to the clock and the gates.
         */
        private suspend fun begin(fix: LocationPoint?) {
            if (revived) voice.setRate(hold.rate)
            if (fix != null) step(fix) { engine.processLocation(fix) }
            tick()
            val gates = currentGates()
            if (!revived) step { engine.setGates(gates.copy(paused = true)) }
            appliedGates = gates
            step { engine.setGates(gates) }
            publishGlance()
        }

        private fun tick() {
            honorEngineSeconds(latestState, clock.now())?.let(engine::updateActiveDuration)
        }

        /** The voice's hold first, which no engine gate reads (spec C §3.6–§3.7), then the engine's gates. */
        private suspend fun applyGates() {
            val external = gatePort.gates.value
            if (external.wayVoiceHeld != voiceHeld) {
                voiceHeld = external.wayVoiceHeld
                commitAndPerform(Plan(clock.now()))
            }
            val gates = currentGates(external)
            if (gates == appliedGates) return
            appliedGates = gates
            step { engine.setGates(gates) }
        }

        /** A sitting leaves the walk recording, so it is not "paused" (spec B §2.4). */
        private fun currentGates(external: HonorExternalGates = gatePort.gates.value): HonorMomentTracker.Gates {
            return HonorMomentTracker.Gates(
                paused = latestState is WalkState.Paused,
                meditating = latestState is WalkState.Meditating,
                recording = external.recording,
                externalAudio = external.externalAudio,
            )
        }

        private fun publishGlance() {
            if (!ended) _glance.value = engine.glance()
        }

        private suspend fun step(fix: LocationPoint? = null, call: () -> List<HonorEngineEvent>) {
            val plan = Plan(clock.now())
            planEvents(plan, call(), fix)
            commitAndPerform(plan)
        }

        /** iOS `handleHonorEvent` (`ActiveWalkViewModel+Honor.swift:175-212@7c200bf`), split into its writes and its effects. */
        private fun planEvents(plan: Plan, events: List<HonorEngineEvent>, fix: LocationPoint?) {
            for (event in events) {
                when (event) {
                    is HonorEngineEvent.MomentReached -> plan.rituals += Ritual.MomentHaptic
                    is HonorEngineEvent.VoiceStart -> planVoiceStart(plan, event.moment, fix)
                    HonorEngineEvent.VoicePause -> {
                        hold = hold.pausedAt(plan.now)
                        plan.rituals += Ritual.Pause
                    }
                    HonorEngineEvent.VoiceResume -> {
                        hold = hold.resumedAt(plan.now)
                        plan.rituals += Ritual.Resume
                    }
                    is HonorEngineEvent.VoiceDropped -> {
                        if (hold.active == event.moment) {
                            plan.stopPlayer()
                            hold = hold.released()
                        }
                        plan.endVoice(event.moment, HonorVoiceEnd.DROPPED)
                    }
                    is HonorEngineEvent.SoftTap -> plan.rituals += Ritual.SoftTapHaptic
                    is HonorEngineEvent.MarkAhead -> {
                        plan.notices += HonorNoticeEntity(
                            walkId = walkId,
                            kind = HonorNoticeKind.WATER,
                            refId = event.mark.id,
                            meters = event.meters,
                            firedAt = plan.now,
                        )
                        plan.rituals += Ritual.WaterHaptic
                    }
                    is HonorEngineEvent.Arrived -> plan.rituals += Ritual.Arrive(
                        WayArrival(theirSeconds = event.theirSeconds, yourSeconds = event.yourSeconds),
                        walkedMeters = engine.distanceWalkedMeters,
                        at = fix,
                    )
                }
            }
        }

        /**
         * Heard means handed to the player, before any sound (spec C §9). A
         * voice whose file is gone was never heard and hands the engine its
         * turn straight back (`ActiveWalkViewModel+Honor.swift:214-218@7c200bf`);
         * a voice start is always its list's last event, so the nested turn
         * keeps iOS's order.
         */
        private fun planVoiceStart(plan: Plan, moment: WayMoment, fix: LocationPoint?) {
            val file = media.voiceFile(way.id, moment)
            if (file == null) {
                planEvents(plan, engine.voiceDidFinish(), fix)
                return
            }
            hold.active?.let { replaced -> plan.endVoice(replaced, HonorVoiceEnd.REPLACED) }
            plan.startVoice(moment)
            plan.play(file, moment.voiceGain(), byEngine = true)
        }

        /** iOS `onFinished` (`ActiveWalkViewModel+Honor.swift:64-71@7c200bf`): the chip clears, then the engine gets its turn. */
        private suspend fun voiceEnded(token: Long, end: HonorVoiceEnd) {
            if (token != playerToken) return
            playerToken = null
            val plan = Plan(clock.now())
            hold.active?.let { plan.endVoice(it, end) }
            hold = hold.released()
            plan.rituals += Ritual.Restore
            planEvents(plan, engine.voiceDidFinish(), fix = null)
            commitAndPerform(plan)
        }

        /** The headphones went and the player paused: the walker's pause, so the chip reads paused and one tap resumes. */
        private suspend fun voicePausedForRoute(token: Long) {
            if (token != playerToken || hold.active == null || hold.paused) return
            val plan = Plan(clock.now())
            hold = hold.pausedAt(plan.now)
            commitAndPerform(plan)
        }

        private suspend fun command(seq: Long, command: HonorCommand) {
            if (database.honorDao().applyCommandSeq(walkId, seq) != 1) return
            val plan = Plan(clock.now())
            when (command) {
                is HonorCommand.TogglePlayback -> planToggle(plan, command.momentId)
                is HonorCommand.PauseResume -> if (isHeld(command.momentId)) planPauseResume(plan)
                is HonorCommand.Scrub -> planScrub(plan, command.momentId, command.fraction)
                is HonorCommand.Skip -> if (isHeld(command.momentId)) planSkip(plan)
                HonorCommand.CycleRate -> planRate(plan)
                is HonorCommand.PlayReply -> planReply(plan, command.momentId)
            }
            commitAndPerform(plan)
        }

        /** Whether [momentId] is still the voice held, as it was when the walker tapped. */
        private fun isHeld(momentId: String): Boolean = hold.active?.id == momentId

        /**
         * iOS `togglePlayback(of:)` (`ActiveWalkViewModel+Honor.swift:384-403@7c200bf`). A
         * replay ignores the voices preference and isn't the engine's: the
         * engine keeps counting its own voice until the replay ends.
         */
        private fun planToggle(plan: Plan, momentId: String) {
            val moment = momentsById[momentId] ?: return
            if (moment == hold.active) {
                planPauseResume(plan)
                return
            }
            val file = media.voiceFile(way.id, moment) ?: return
            hold.active?.let { replaced -> plan.endVoice(replaced, HonorVoiceEnd.REPLACED) }
            plan.stopPlayer()
            plan.startVoice(moment)
            plan.play(file, moment.voiceGain(), byEngine = false)
        }

        /** A pause during a guide's or a call's hold becomes the walker's own pause, where the voice was held (spec C §3.6). */
        private fun planPauseResume(plan: Plan) {
            if (hold.paused) {
                hold = hold.resumedAt(plan.now)
                plan.rituals += Ritual.Resume
            } else {
                hold = hold.pausedAt(plan.now)
                plan.rituals += Ritual.Pause
            }
        }

        /** iOS `seekVoice` (`ActiveWalkViewModel+Honor.swift:405-411@7c200bf`). */
        private fun planScrub(plan: Plan, momentId: String, fraction: Double) {
            val moment = momentsById[momentId] ?: return
            if (moment != hold.active) planToggle(plan, momentId)
            if (moment != hold.active) return
            val clamped = fraction.coerceIn(0.0, MAX_SCRUB_FRACTION)
            val durationSeconds = (moment.kind as? WayMomentKind.Voice)?.duration ?: 0.0
            hold = hold.movedTo((clamped * durationSeconds * MILLIS_PER_SECOND).roundToLong(), plan.now)
            plan.rituals += Ritual.Seek(clamped)
        }

        /** iOS `skipVoice` (`ActiveWalkViewModel+Honor.swift:421-427@7c200bf`): no retire, and the engine's turn. */
        private fun planSkip(plan: Plan) {
            val active = hold.active ?: return
            plan.endVoice(active, HonorVoiceEnd.SKIPPED)
            plan.stopPlayer()
            hold = hold.released()
            planEvents(plan, engine.voiceDidFinish(), fix = null)
        }

        private fun planRate(plan: Plan) {
            val next = nextVoiceRate(hold.rate)
            val rebased = if (hold.active != null && !hold.paused) hold.movedTo(hold.positionMillis(plan.now), plan.now) else hold
            hold = rebased.copy(rate = next)
            plan.rituals += Ritual.Rate(next)
        }

        /**
         * iOS `playReply(url:)` (`ActiveWalkViewModel+Replies.swift:65-75@7c200bf`): the
         * held voice is given up, not paused, and the reply is neither held
         * nor heard. When it ends, the engine gets its turn back.
         */
        private fun planReply(plan: Plan, momentId: String) {
            val moment = momentsById[momentId] ?: return
            val n = voiceOriginIndex(moment.id) ?: return
            val relativePath = wayStore.replies(way.id)[n] ?: return
            val file = media.recordingFile(relativePath) ?: return
            hold.active?.let { plan.endVoice(it, HonorVoiceEnd.INTERRUPTED) }
            plan.stopPlayer()
            hold = hold.released()
            plan.playReply(file)
        }

        private suspend fun commitAndPerform(plan: Plan) {
            hold = hold.heldIf(voiceHeld, plan.now)
            val snapshot = engine.snapshot()
            planTracker(plan, snapshot.tracker)
            val engineState = snapshot.toEngineState(walkId)
            val writeEngine = engineState != committedEngineState
            val writeHold = hold != committedHold
            if (writeEngine || writeHold || plan.rows.isNotEmpty() || plan.notices.isNotEmpty()) {
                val committed = commit(
                    engineState = engineState.takeIf { writeEngine },
                    hold = hold.takeIf { writeHold },
                    changedRows = plan.rows.values,
                    notices = plan.notices,
                )
                if (!committed) {
                    end()
                    return
                }
                committedEngineState = engineState
                committedHold = hold
                rows.putAll(plan.rows)
            }
            perform(plan.rituals)
        }

        /** What the tracker reached and holds waiting, which it reports through no event. */
        private fun planTracker(plan: Plan, tracker: HonorMomentTracker.Snapshot) {
            for (id in tracker.reached) {
                if (plan.row(id).reachedAt == null) plan.update(id) { it.copy(reachedAt = plan.now) }
            }
            val positions = tracker.queue.withIndex().associate { (index, id) -> id to index }
            for (id in rows.keys + plan.rows.keys + positions.keys) {
                val position = positions[id]
                if (plan.row(id).queuePosition != position) plan.update(id) { it.copy(queuePosition = position) }
            }
        }

        private suspend fun commit(
            engineState: HonorEngineState?,
            hold: VoiceHold?,
            changedRows: Collection<HonorMomentStateEntity>,
            notices: Collection<HonorNoticeEntity>,
        ): Boolean = try {
            database.withTransaction {
                val dao = database.honorDao()
                if (dao.countLiveSessionOnUnfinishedWalk(walkId) == 0) return@withTransaction false
                engineState?.let { dao.updateEngineState(it) }
                hold?.let { dao.updateVoiceState(it.toVoiceState(walkId)) }
                changedRows.forEach { dao.upsertMomentState(it) }
                notices.forEach { dao.insertNotice(it) }
                true
            }
        } catch (e: SQLiteException) {
            Log.w(TAG, "walk $walkId: Honor rows refused (${e::class.simpleName}); the session ends")
            false
        }

        private suspend fun perform(rituals: List<Ritual>) {
            for (ritual in rituals) {
                when (ritual) {
                    is Ritual.Play -> voice.play(ritual.file, ritual.gain, Listener(ritual.token, ritual.byEngine))
                    is Ritual.PlayReply -> voice.playReply(ritual.file, Listener(ritual.token, byEngine = false))
                    Ritual.Pause -> voice.pause()
                    Ritual.Resume -> voice.resume()
                    Ritual.Stop -> voice.stop()
                    is Ritual.Seek -> voice.seek(ritual.fraction)
                    is Ritual.Rate -> voice.setRate(ritual.rate)
                    Ritual.Duck -> if (!ducked) {
                        ducked = true
                        duck.duckForWayVoice()
                    }
                    Ritual.Restore -> if (ducked) {
                        ducked = false
                        duck.restoreAfterWayVoice()
                    }
                    Ritual.MomentHaptic -> haptics.momentReached()
                    Ritual.SoftTapHaptic -> haptics.softTap()
                    Ritual.WaterHaptic -> haptics.waterAhead()
                    is Ritual.Arrive -> {
                        val won = arrivalRecorder.recordHonorArrival(
                            walkId = walkId,
                            arrival = ritual.arrival,
                            walkedMeters = ritual.walkedMeters,
                            waypointLabel = arrivalLabel(way.title),
                            at = ritual.at,
                        )
                        if (won) haptics.arrival()
                    }
                }
            }
        }

        /** One engine call or command: the rows it changes, the notices it speaks, and the rituals after the commit. */
        private inner class Plan(val now: Long) {
            val rows = LinkedHashMap<String, HonorMomentStateEntity>()
            val notices = mutableListOf<HonorNoticeEntity>()
            val rituals = mutableListOf<Ritual>()

            fun row(id: String): HonorMomentStateEntity =
                rows[id] ?: this@Run.rows[id] ?: HonorMomentStateEntity(walkId = walkId, momentId = id)

            fun update(id: String, change: (HonorMomentStateEntity) -> HonorMomentStateEntity) {
                val before = row(id)
                val after = change(before)
                if (after != before) rows[id] = after
            }

            fun startVoice(moment: WayMoment) {
                hold = hold.started(moment, now)
                update(moment.id) { it.copy(voiceStartedAt = now, voiceEndedAt = null, voiceEnd = null, heard = true) }
            }

            fun endVoice(moment: WayMoment, how: HonorVoiceEnd) =
                update(moment.id) { it.copy(voiceEndedAt = now, voiceEnd = how) }

            /**
             * Replacing a play keeps the duck; only an empty player takes one
             * (spec C resolution 9). [byEngine] for the engine's own voice,
             * whose card only a start failure raises again (spec C §10.2).
             */
            fun play(file: File, gain: Float, byEngine: Boolean) {
                val token = ++nextToken
                playerToken = token
                rituals += Ritual.Duck
                rituals += Ritual.Play(file, gain, token, byEngine)
            }

            fun playReply(file: File) {
                val token = ++nextToken
                playerToken = token
                rituals += Ritual.Duck
                rituals += Ritual.PlayReply(file, token)
            }

            fun stopPlayer() {
                if (playerToken == null) return
                playerToken = null
                rituals += Ritual.Stop
                rituals += Ritual.Restore
            }
        }

        private inner class Listener(private val token: Long, private val byEngine: Boolean) : WayVoiceListener {
            override fun onFinished() {
                inputs.trySend(Input.VoiceEnded(token, HonorVoiceEnd.FINISHED))
            }

            override fun onFailed(atHandOff: Boolean) {
                val end = if (atHandOff && byEngine) HonorVoiceEnd.FAILED_AT_START else HonorVoiceEnd.FAILED
                inputs.trySend(Input.VoiceEnded(token, end))
            }

            override fun onPausedForRoute() {
                inputs.trySend(Input.VoicePausedForRoute(token))
            }
        }
    }

    private companion object {
        const val TAG = "HonorSession"

        /** iOS's engine clock is a 1 s timer (`ActiveWalkViewModel.swift:535-546@7c200bf`). */
        const val ENGINE_TICK_MILLIS = 1_000L
        const val MAX_SCRUB_FRACTION = 0.999
        const val MILLIS_PER_SECOND = 1_000.0

        fun HonorEngine.setGates(gates: HonorMomentTracker.Gates): List<HonorEngineEvent> = setGates(
            paused = gates.paused,
            meditating = gates.meditating,
            recording = gates.recording,
            externalAudio = gates.externalAudio,
        )

        fun WalkState.inProgressWalk(): WalkAccumulator? = when (this) {
            is WalkState.Active -> walk
            is WalkState.Paused -> walk
            is WalkState.Meditating -> walk
            WalkState.Idle, is WalkState.Finished -> null
        }
    }
}
