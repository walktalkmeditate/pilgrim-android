// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.max
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.core.flags.ReleaseFlags
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.honor.HonorDao
import org.walktalkmeditate.pilgrim.data.honor.HonorMomentStateEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorSessionEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorSourceKind
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.domain.honor.HonorPhase
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayCoordinate
import org.walktalkmeditate.pilgrim.domain.honor.WayGeometry
import org.walktalkmeditate.pilgrim.domain.honor.WayMoment
import org.walktalkmeditate.pilgrim.honor.OwnWalkWays
import org.walktalkmeditate.pilgrim.ui.walk.map.HonorWayLine
import org.walktalkmeditate.pilgrim.ui.walk.map.WayPin
import org.walktalkmeditate.pilgrim.ui.walk.map.wayPins
import org.walktalkmeditate.pilgrim.walk.WalkController
import org.walktalkmeditate.pilgrim.walk.honor.honorEngineSeconds

/** What the walk map draws of the Way: built from the source walk before Start, read from Room after. */
@Immutable
data class HonorWalkUiState(
    val way: Way,
    val line: HonorWayLine,
    val pins: List<WayPin>,
    /** The walk's Honor session; null on the pre-walk screen, before Start. */
    val session: HonorLiveSession?,
)

/** The live rows `:tracker` writes for the walk in progress, as the walk screen reads them (plan U17). */
@Immutable
data class HonorLiveSession(
    val walkId: Long,
    val anchor: HonorAnchor,
    val progressFrac: Double,
    val reachedMomentIds: Set<String>,
    /** Marked when a voice is handed to the player, before any sound (parity spec C §9). */
    val heardVoiceIds: Set<String>,
    /** The voice the player holds (iOS `activeVoice`); null while nothing plays, or a reply does. */
    val playingMomentId: String?,
    val voicePaused: Boolean,
    val arrived: Boolean,
)

/** Where the engine joined the walker to the Way; the companion's clock runs from here. */
@Immutable
data class HonorAnchor(
    /** Null until the first accepted fix anchors the walker. */
    val startFrac: Double?,
    /** Begin found no Way within 60 m: frac 0 until an on-Way fix re-anchors. */
    val anchoredByFallback: Boolean,
    val anchorActiveSeconds: Double,
    val companionT0Seconds: Double,
)

/**
 * The walk screen's Honor state in the UI process (plan U22, parity spec
 * E §1–§6): the Way, the companion, the pin taps, and the fly-to. Before
 * Start the Way is built from the walk being honored, as iOS draws it from
 * the moment the walk screen appears; once the walk runs, everything comes
 * from Room (the session row `:tracker` writes, its moment rows) and from
 * the Ways store (the staged or listed Way the session names), so a
 * restarted UI process draws the same map (AE1). iOS keeps all of it on its
 * view model in one process.
 *
 * With the release flag off nothing here touches Room or the store.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HonorWalkViewModel internal constructor(
    private val controller: WalkController,
    private val honorDao: HonorDao,
    private val repository: WalkRepository,
    private val wayStore: WayStore,
    private val ownWalkWays: OwnWalkWays,
    releaseFlags: ReleaseFlags,
    private val clock: Clock,
    private val ioDispatcher: CoroutineDispatcher,
    /** iOS's walk duration tick, the companion's read cadence. */
    private val tickMillis: Long,
) : ViewModel() {

    @Inject
    constructor(
        controller: WalkController,
        honorDao: HonorDao,
        repository: WalkRepository,
        wayStore: WayStore,
        ownWalkWays: OwnWalkWays,
        releaseFlags: ReleaseFlags,
        clock: Clock,
    ) : this(
        controller, honorDao, repository, wayStore, ownWalkWays, releaseFlags, clock,
        ioDispatcher = Dispatchers.IO,
        tickMillis = COMPANION_TICK_MILLIS,
    )

    private val enabled = releaseFlags.honor

    private val previewState = MutableStateFlow<HonorWalkUiState?>(null)
    private var previewSourceWalkId: Long? = null

    private val live: Flow<LiveHonor?> = if (!enabled) {
        flowOf(null)
    } else {
        controller.state.map { it.inProgressWalkId() }.distinctUntilChanged()
            .flatMapLatest { walkId -> if (walkId == null) flowOf(null) else observeLive(walkId) }
            .shareIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS), replay = 1)
    }

    /**
     * The Way on this walk screen, or null for one with no Way. The
     * session's Way wins once it is in Room; until then (the pre-walk
     * screen, and the moment between Start and its rows landing) the
     * source walk's build stands in.
     */
    val state: StateFlow<HonorWalkUiState?> = if (!enabled) {
        MutableStateFlow(null)
    } else {
        combine(live, previewState) { live, preview -> live?.state ?: preview }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS), null)
    }

    /**
     * The companion (parity spec E §3): where the Way's walker stood at this
     * walk's engine time, from Start until the walk ends, never on a stage.
     * It moves in jumps at most every 2 s; in a pause it stands still, as
     * the engine clock does (owner decision 1); while meditating it isn't
     * moved at all, and on the way back it jumps to where the clock, which
     * counts the sitting, puts it. A backgrounded screen stops collecting,
     * so it isn't moved then either.
     */
    val companion: StateFlow<WayCoordinate?> = if (!enabled) {
        MutableStateFlow(null)
    } else {
        val engineClocks = controller.state.map { it.engineClockOnly() }.distinctUntilChanged()
        combine(companionSources(), engineClocks) { source, walk -> source?.let { CompanionInputs(it, walk) } }
            .distinctUntilChanged()
            .flatMapLatest { inputs ->
                when {
                    inputs == null -> flowOf(null)
                    inputs.walk is WalkState.Meditating -> emptyFlow()
                    else -> ticks().map { now -> inputs.coordinateAt(now) }
                }
            }
            .companionJumps(clock::now, COMPANION_JUMP_MILLIS)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS), null)
    }

    private val _tappedMomentId = MutableStateFlow<String?>(null)

    /** The moment whose pin was last tapped during the walk; the card host takes it from here. */
    val tappedMomentId: StateFlow<String?> = _tappedMomentId.asStateFlow()

    private val _focus = MutableStateFlow<WayCoordinate?>(null)

    /** A moment the walker asked the map to show (iOS `honorFocus`); null lets the map follow them. */
    val focus: StateFlow<WayCoordinate?> = _focus.asStateFlow()

    /** Builds the Way of [sourceWalkId] for the pre-walk screen, once per source walk. */
    fun showWay(sourceWalkId: Long) {
        if (!enabled || previewSourceWalkId == sourceWalkId) return
        previewSourceWalkId = sourceWalkId
        viewModelScope.launch {
            val way = (ownWalkWays.build(sourceWalkId) as? OwnWalkWays.Built.Ready)?.way ?: return@launch
            previewState.value = withContext(ioDispatcher) {
                HonorWalkUiState(way, HonorWayLine.of(way), wayPins(way, heardVoiceIds = emptySet()), session = null)
            }
        }
    }

    /**
     * iOS `showWayCard(for:)` (`ActiveWalkView+Honor.swift:26-34@7c200bf`):
     * a tap before Start does nothing, and nor does one on a moment the Way
     * doesn't carry.
     */
    fun onWayPinTap(momentId: String) {
        val honor = state.value ?: return
        if (honor.session == null || honor.way.moments.none { it.id == momentId }) return
        _tappedMomentId.value = momentId
    }

    /**
     * A card header's tap (iOS `toggleFocus(on:)`, `ActiveWalkViewModel+Honor.swift:289-303@7c200bf`):
     * the map flies to the moment, or home when it is already there.
     */
    fun flyTo(moment: WayMoment) {
        val there = focusTarget(moment) ?: return
        _focus.value = if (_focus.value == there) null else there
    }

    /** A dismissed card takes the map home (iOS `dismissTopCard`). */
    fun clearFocus() {
        _focus.value = null
    }

    /**
     * iOS `coordinate(of:)`: the moment's own place, else the line at its
     * frac; for the latter, nothing before Start, as iOS needs its engine's
     * geometry. Not the pin's `pin`, which only a stage sets.
     */
    private fun focusTarget(moment: WayMoment): WayCoordinate? {
        moment.at?.let { return it }
        val honor = state.value?.takeIf { it.session != null } ?: return null
        return WayGeometry(honor.way.route).coordinate(atFrac = moment.frac)
    }

    private fun observeLive(walkId: Long): Flow<LiveHonor?> {
        val sessions = honorDao.observeSession(walkId).distinctUntilChanged()
        val loaded = sessions
            .map { session -> session?.let { WayKey(it.wayId, it.sourceKind) } }
            .distinctUntilChanged()
            .mapLatest { key -> key?.let { loadWay(walkId, it) } }
        val drawn = combine(loaded, honorDao.observeMomentStates(walkId).distinctUntilChanged()) { way, rows ->
            way?.let { DrawnWay.of(it, rows) }
        }
        // The session row changes with every fix; the pins are rebuilt only with a moment row.
        return combine(sessions, drawn) { session, way ->
            if (session == null || way == null) null else LiveHonor.of(walkId, session, way)
        }.flowOn(ioDispatcher)
    }

    /** The staged Way an own-walk session follows, else the listed one, as `:tracker`'s session loads it. */
    private suspend fun loadWay(walkId: Long, key: WayKey): LoadedWay? = withContext(ioDispatcher) {
        val walkUuid = repository.getWalk(walkId)?.uuid ?: return@withContext null
        val staged = if (key.sourceKind == HonorSourceKind.OWN_WALK) wayStore.staged(walkUuid) else null
        val way = staged?.takeIf { it.id == key.wayId } ?: wayStore.load(key.wayId) ?: return@withContext null
        LoadedWay(way, HonorWayLine.of(way), WayGeometry(way.route))
    }

    private fun companionSources(): Flow<CompanionSource?> = live
        .map { live ->
            val session = live?.state?.session ?: return@map null
            if (live.loaded.way.isPilgrimageStage) return@map null
            CompanionSource(live.loaded.way.id, live.loaded.geometry, session.anchor)
        }
        .distinctUntilChanged()

    private fun ticks(): Flow<Long> = flow {
        while (true) {
            emit(clock.now())
            delay(tickMillis)
        }
    }

    private data class WayKey(val wayId: String, val sourceKind: HonorSourceKind)

    private class LoadedWay(val way: Way, val line: HonorWayLine, val geometry: WayGeometry)

    /** What the pins and moment sets need, rebuilt only when a moment row changes, not on every fix. */
    private class DrawnWay(
        val loaded: LoadedWay,
        val reached: Set<String>,
        val heard: Set<String>,
        val pins: List<WayPin>,
    ) {
        companion object {
            fun of(loaded: LoadedWay, rows: List<HonorMomentStateEntity>): DrawnWay {
                val heard = rows.filter { it.heard }.mapTo(mutableSetOf()) { it.momentId }
                return DrawnWay(
                    loaded = loaded,
                    reached = rows.filter { it.reachedAt != null }.mapTo(mutableSetOf()) { it.momentId },
                    heard = heard,
                    pins = wayPins(loaded.way, heard),
                )
            }
        }
    }

    private class LiveHonor(val loaded: LoadedWay, val state: HonorWalkUiState) {
        companion object {
            fun of(walkId: Long, session: HonorSessionEntity, drawn: DrawnWay) = LiveHonor(
                loaded = drawn.loaded,
                state = HonorWalkUiState(
                    way = drawn.loaded.way,
                    line = drawn.loaded.line,
                    pins = drawn.pins,
                    session = HonorLiveSession(
                        walkId = walkId,
                        anchor = HonorAnchor(
                            startFrac = session.startFrac,
                            anchoredByFallback = session.anchoredByFallback,
                            anchorActiveSeconds = session.anchorActiveSeconds,
                            companionT0Seconds = session.companionT0Seconds,
                        ),
                        progressFrac = session.progressFrac,
                        reachedMomentIds = drawn.reached,
                        heardVoiceIds = drawn.heard,
                        playingMomentId = session.playingMomentId,
                        voicePaused = session.voicePaused,
                        arrived = session.phase == HonorPhase.ARRIVED,
                    ),
                ),
            )
        }
    }

    /** The geometry is the loaded Way's own instance, so equality follows the Way id and the anchor. */
    private data class CompanionSource(val wayId: String, val geometry: WayGeometry, val anchor: HonorAnchor)

    private data class CompanionInputs(val source: CompanionSource, val walk: WalkState) {
        fun coordinateAt(nowMillis: Long): WayCoordinate? {
            val seconds = honorEngineSeconds(walk, nowMillis) ?: return null
            return source.geometry.coordinate(atFrac = companionFrac(source.geometry, source.anchor, seconds))
        }
    }

    private companion object {
        const val COMPANION_TICK_MILLIS = 1_000L

        /** iOS `companionUpdateInterval` (`PilgrimMapView+HonorWay.swift:67@7c200bf`). */
        const val COMPANION_JUMP_MILLIS = 2_000L
        const val SUBSCRIBER_GRACE_MS = 5_000L
    }
}

/**
 * iOS `HonorEngine.companionFrac` (`HonorEngine.swift:124-177@7c200bf`),
 * from the anchor `:tracker` persists: the Way's first point until the
 * first fix anchors the walker, its anchor while Begin's fallback stands,
 * then the Way's own timing advanced by the engine time since the anchor.
 */
private fun companionFrac(geometry: WayGeometry, anchor: HonorAnchor, engineSeconds: Double): Double = when {
    anchor.startFrac == null -> 0.0
    anchor.anchoredByFallback -> geometry.frac(atElapsed = anchor.companionT0Seconds)
    else -> geometry.frac(atElapsed = anchor.companionT0Seconds + max(0.0, engineSeconds - anchor.anchorActiveSeconds))
}

/**
 * iOS rewrites the companion's point at most once every [intervalMillis]
 * (`PilgrimMapView+HonorWay.swift:238-245@7c200bf`), with no animation: a
 * first position and a removal go through at once, a move only once the
 * interval has passed since the last one.
 */
private fun Flow<WayCoordinate?>.companionJumps(nowMillis: () -> Long, intervalMillis: Long): Flow<WayCoordinate?> =
    flow {
        var emitted = false
        var last: WayCoordinate? = null
        var lastAt = 0L
        collect { next ->
            if (emitted && next == last) return@collect
            val now = nowMillis()
            val isMove = last != null && next != null
            if (isMove && now - lastAt < intervalMillis) return@collect
            emit(next)
            emitted = true
            last = next
            lastAt = now
        }
    }

private fun WalkState.inProgressWalkId(): Long? = when (this) {
    is WalkState.Active -> walk.walkId
    is WalkState.Paused -> walk.walkId
    is WalkState.Meditating -> walk.walkId
    WalkState.Idle, is WalkState.Finished -> null
}

/** The walk state with only what the engine clock reads, so a fix that moves the walker isn't a clock change. */
private fun WalkState.engineClockOnly(): WalkState = when (this) {
    is WalkState.Active -> WalkState.Active(walk.clockOnly())
    is WalkState.Paused -> copy(walk = walk.clockOnly())
    is WalkState.Meditating -> copy(walk = walk.clockOnly())
    WalkState.Idle, is WalkState.Finished -> WalkState.Idle
}

private fun WalkAccumulator.clockOnly() =
    WalkAccumulator(walkId = walkId, startedAt = startedAt, totalPausedMillis = totalPausedMillis)
